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
		assertEquals("1.0.0", config.modelMinVersion());
		for (Workflow w : Workflow.values()) {
			LivenessPolicy p = config.policy(w);
			if (w == Workflow.EXCEPTION_PHOTO) {
				assertEquals(PolicyMode.OFF, p.mode());
				assertFalse(config.isEnforced(w));
			} else {
				assertEquals(w.id(), PolicyMode.PASSIVE_THEN_ACTIVE, p.mode());
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
		assertEquals("1.0.0", config.modelMinVersion());
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
