/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.PolicyMode;

/**
 * Tests {@link LivenessConfig}: typed defaults for missing keys, malformed values, per-workflow policies, floors,
 * the master switch and change detection.
 */
public class LivenessConfigTest {

	private final Map<String, Object> values = new HashMap<>();
	private final LivenessConfig config = LivenessConfig.of(values);

	@Test
	public void emptyMapGivesShippedDefaults() {
		assertTrue(config.isMasterSwitchOn());
		assertFalse(config.isDiagnosticEnabled());
		assertTrue(config.isMetricsEnabled());
		assertEquals("mosip-onnx-default", config.engineId());
		assertEquals("1.3.0", config.modelMinVersion());
		for (Workflow w : Workflow.values()) {
			LivenessPolicy p = config.policy(w);
			if (w == Workflow.EXCEPTION_PHOTO) {
				assertEquals(PolicyMode.OFF, p.mode());
				assertFalse(config.isEnforced(w));
			} else {
				assertEquals(w.id(), LivenessConfigKeys.defaultPolicyMode(w), p.mode());
				assertTrue(config.isEnforced(w));
			}
		}
		assertEquals(60_000L, config.policy(Workflow.REGISTRATION).streamTimeoutMs());
	}

	@Test
	public void missingOrUnavailableMapNeverThrows() {
		LivenessConfig nullMap = new LivenessConfig(() -> null);
		assertEquals(Optional.empty(), nullMap.get("mosip.registration.liveness.enable"));
		assertTrue(nullMap.isEnforced(Workflow.LOGIN_AUTH));
		LivenessConfig failing = new LivenessConfig(() -> {
			throw new IllegalStateException("map not ready");
		});
		assertEquals(Optional.empty(), failing.get("x"));
		assertTrue(failing.isMasterSwitchOn());
		assertEquals(Optional.empty(), config.get(null));
	}

	@Test
	public void valuesAreTrimmedAndBlankMeansMissing() {
		values.put("mosip.registration.liveness.engine", "  vendor-x ");
		values.put("mosip.registration.liveness.model.min_version", "   ");
		assertEquals("vendor-x", config.engineId());
		assertEquals("1.3.0", config.modelMinVersion());
	}

	@Test
	public void masterSwitchOffDisablesEveryWorkflow() {
		values.put("mosip.registration.liveness.enable", "N");
		assertFalse(config.isMasterSwitchOn());
		assertFalse(config.isEnforcedForAny(Workflow.values()));
	}

	@Test
	public void unreadableYesNoKeepsDefault() {
		values.put("mosip.registration.liveness.enable", "perhaps");
		values.put("mosip.registration.liveness.diagnostic.enable", "true");
		assertTrue(config.isMasterSwitchOn());
		assertTrue(config.isDiagnosticEnabled());
	}

	@Test
	public void perWorkflowPolicyAndOverrides() {
		values.put("mosip.registration.liveness.policy.eod_auth", "ALWAYS_ACTIVE");
		values.put("mosip.registration.liveness.policy.registration", "OFF");
		values.put("mosip.registration.liveness.packet_auth.max_retries", "5");
		assertEquals(PolicyMode.ALWAYS_ACTIVE, config.policy(Workflow.EOD_AUTH).mode());
		assertFalse(config.isEnforced(Workflow.REGISTRATION));
		assertEquals(5, config.policy(Workflow.PACKET_AUTH).maxRetries());
	}

	@Test
	public void malformedAndOutOfRangeValuesAreCorrected() {
		values.put("mosip.registration.liveness.passive.threshold", "abc");
		values.put("mosip.registration.liveness.pad.reject_threshold", Integer.valueOf(1));
		values.put("mosip.registration.liveness.max_retries", "99");
		LivenessPolicy p = config.policy(Workflow.REGISTRATION);
		assertTrue(p.passiveThreshold() >= 50);
		assertTrue("reject threshold is clamped to its floor", p.padRejectThreshold() >= 5);
		assertTrue(p.maxRetries() <= 10);
		assertFalse(config.resolve(Workflow.REGISTRATION).issues().isEmpty());
	}

	/**
	 * Fills the application map the way the client builds it at start-up: synced values first, then the local
	 * preferences copied over them.
	 */
	private void syncedAndLocal(Map<String, Object> synced, Map<String, String> local) {
		values.putAll(synced);
		values.putAll(local);
		config.setLocalSettings(() -> local, () -> synced);
	}

	@Test
	public void localOverrideOfPolicyAndMasterSwitchIsIgnoredButThresholdApplies() {
		Map<String, Object> synced = new HashMap<>();
		synced.put(LivenessConfigKeys.ENABLE, "Y");
		synced.put(LivenessConfigKeys.PASSIVE_THRESHOLD, "80");
		Map<String, String> local = new HashMap<>();
		local.put(LivenessConfigKeys.ENABLE, "N");
		local.put(LivenessConfigKeys.policyKey(Workflow.REGISTRATION), "OFF");
		local.put(LivenessConfigKeys.PASSIVE_THRESHOLD, "90");
		syncedAndLocal(synced, local);

		assertTrue("a local value cannot switch liveness off", config.isMasterSwitchOn());
		assertTrue(config.isEnforced(Workflow.REGISTRATION));
		assertEquals("no synced policy: the shipped default applies",
				LivenessConfigKeys.defaultPolicyMode(Workflow.REGISTRATION), config.policy(Workflow.REGISTRATION).mode());
		assertEquals("passive.threshold is on the allow-list", 90,
				config.policy(Workflow.REGISTRATION).passiveThreshold());
		assertEquals(Optional.of("90"), config.get(LivenessConfigKeys.PASSIVE_THRESHOLD));
		assertEquals(Arrays.asList(LivenessConfigKeys.ENABLE, LivenessConfigKeys.policyKey(Workflow.REGISTRATION)),
				config.refusedLocalKeys());
	}

	@Test
	public void localOverrideOfPolicyFallsBackToTheSyncedValue() {
		Map<String, Object> synced = new HashMap<>();
		synced.put(LivenessConfigKeys.policyKey(Workflow.PACKET_AUTH), "ALWAYS_ACTIVE");
		synced.put(LivenessConfigKeys.ENABLE, "Y");
		Map<String, String> local = new HashMap<>();
		local.put(LivenessConfigKeys.policyKey(Workflow.PACKET_AUTH), "PASSIVE_ONLY");
		local.put(LivenessConfigKeys.ENABLE, "N");
		syncedAndLocal(synced, local);

		assertEquals(PolicyMode.ALWAYS_ACTIVE, config.policy(Workflow.PACKET_AUTH).mode());
		assertEquals(Optional.of("Y"), config.get(LivenessConfigKeys.ENABLE));
	}

	@Test
	public void localOverrideOfAllowListedOrForeignKeysStillApplies() {
		Map<String, Object> synced = new HashMap<>();
		synced.put(LivenessConfigKeys.MAX_RETRIES, "3");
		synced.put("mosip.registration.some_upstream_key", "1");
		Map<String, String> local = new HashMap<>();
		local.put(LivenessConfigKeys.MAX_RETRIES, "4");
		local.put(LivenessConfigKeys.DIAGNOSTIC_ENABLE, "Y");
		local.put("mosip.registration.some_upstream_key", "2");
		syncedAndLocal(synced, local);

		assertEquals(4, config.policy(Workflow.REGISTRATION).maxRetries());
		assertTrue(config.isDiagnosticEnabled());
		assertEquals("keys outside liveness keep the upstream local preference rules", Optional.of("2"),
				config.get("mosip.registration.some_upstream_key"));
		assertEquals(Collections.emptyList(), config.refusedLocalKeys());
	}

	@Test
	public void localOverrideIgnoredKeysAreReportedWithTheirEffectiveValue() {
		Map<String, Object> synced = new HashMap<>();
		synced.put(LivenessConfigKeys.ENABLE, "Y");
		Map<String, String> local = new HashMap<>();
		local.put(LivenessConfigKeys.ENABLE, "N");
		syncedAndLocal(synced, local);

		assertEquals(Collections.emptyList(), config.changedKeysSinceLastCheck());
		values.put(LivenessConfigKeys.ENABLE, "N");
		assertEquals("the effective value did not change", Collections.emptyList(),
				config.changedKeysSinceLastCheck());
	}

	@Test
	public void localOverrideReadFailureNeverThrows() {
		values.put(LivenessConfigKeys.PASSIVE_THRESHOLD, "85");
		config.setLocalSettings(() -> {
			throw new IllegalStateException("database not ready");
		}, () -> null);
		assertEquals(Optional.of("85"), config.get(LivenessConfigKeys.PASSIVE_THRESHOLD));
		assertTrue(config.isMasterSwitchOn());
	}

	@Test
	public void changedKeysAreReportedOnce() {
		values.put("mosip.registration.liveness.passive.threshold", "80");
		values.put("unrelated.key", "1");
		assertEquals(Collections.emptyList(), config.changedKeysSinceLastCheck());
		values.put("mosip.registration.liveness.passive.threshold", "85");
		values.put("mosip.registration.liveness.max_retries", "4");
		values.put("unrelated.key", "2");
		assertEquals(Arrays.asList("mosip.registration.liveness.max_retries",
				"mosip.registration.liveness.passive.threshold"), config.changedKeysSinceLastCheck());
		assertEquals(Collections.emptyList(), config.changedKeysSinceLastCheck());
		values.remove("mosip.registration.liveness.max_retries");
		assertEquals(Collections.singletonList("mosip.registration.liveness.max_retries"),
				config.changedKeysSinceLastCheck());
	}
}
