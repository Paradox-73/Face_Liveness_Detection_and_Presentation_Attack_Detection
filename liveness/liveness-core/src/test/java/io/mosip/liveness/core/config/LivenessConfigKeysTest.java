/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Workflow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivenessConfigKeysTest {

    private static final String[] RESTART_SUBSTRINGS = {"kernel", "mosip.primary", "mosip.biometric.sdk", "languages"};

    @Test
    void shippedDefaultsMatchTheDesignTable() {
        Map<String, String> d = LivenessConfigKeys.defaultValues();
        assertEquals("Y", d.get("mosip.registration.liveness.enable"));
        assertEquals("mosip-onnx-default", d.get("mosip.registration.liveness.engine"));
        assertEquals("CPU", d.get("mosip.registration.liveness.engine.execution_provider"));
        assertEquals("80", d.get("mosip.registration.liveness.passive.threshold"));
        assertEquals("20", d.get("mosip.registration.liveness.pad.reject_threshold"));
        assertEquals("30", d.get("mosip.registration.liveness.passive.min_frames"));
        assertEquals("4000", d.get("mosip.registration.liveness.passive.timeout_ms"));
        assertEquals("Y", d.get("mosip.registration.liveness.active.enable"));
        assertEquals("2", d.get("mosip.registration.liveness.active.min_challenges"));
        assertEquals("BLINK,SMILE,TURN_LEFT,TURN_RIGHT", d.get("mosip.registration.liveness.active.challenge_types"));
        assertEquals("5000", d.get("mosip.registration.liveness.active.challenge_timeout_ms"));
        assertEquals("200", d.get("mosip.registration.liveness.active.min_onset_ms"));
        assertEquals("300", d.get("mosip.registration.liveness.active.hold_ms"));
        assertEquals("3", d.get("mosip.registration.liveness.max_retries"));
        assertEquals("BLOCK", d.get("mosip.registration.liveness.on_max_failure"));
        assertEquals("60000", d.get("mosip.registration.liveness.stream.timeout_ms"));
        assertEquals("0", d.get("mosip.registration.liveness.frame.sample_interval_ms"));
        assertEquals("1", d.get("mosip.registration.liveness.device.reconnect_attempts"));
        assertEquals("60", d.get("mosip.registration.liveness.quality.min_ied_px"));
        assertEquals("15", d.get("mosip.registration.liveness.quality.max_yaw_deg"));
        assertEquals("5", d.get("mosip.registration.liveness.integrity.max_frozen_frames"));
        assertEquals("3", d.get("mosip.registration.liveness.integrity.min_fps"));
        assertEquals("60", d.get("mosip.registration.liveness.binding.min_similarity"));
        assertEquals("5000", d.get("mosip.registration.liveness.binding.max_gap_ms"));
        assertEquals("BLOCK", d.get("mosip.registration.liveness.no_stream_policy"));
        assertEquals("1.3.0", d.get("mosip.registration.liveness.model.min_version"));
        assertEquals("N", d.get("mosip.registration.liveness.diagnostic.enable"));
        assertEquals("Y", d.get("mosip.registration.liveness.metrics.enable"));
        assertEquals("L0,L1", d.get("mosip.registration.mds.allowed_certifications"));
        assertEquals("BUILTIN", d.get("mosip.registration.liveness.android.device"));
        assertEquals("N", d.get("mosip.registration.liveness.android.block_emulator"));
        assertEquals("Y", d.get("mosip.registration.android.offline_approval.enable"));
    }

    /** All 45 shipped defaults, in the order clients write them; a change here is a change of shipped behaviour. */
    @Test
    void fullDefaultMapIsPinned() {
        List<String> expected = Arrays.asList(
                "mosip.registration.liveness.enable=Y",
                "mosip.registration.liveness.engine=mosip-onnx-default",
                "mosip.registration.liveness.engine.execution_provider=CPU",
                "mosip.registration.liveness.passive.threshold=80",
                "mosip.registration.liveness.pad.reject_threshold=20",
                "mosip.registration.liveness.passive.min_frames=30",
                "mosip.registration.liveness.passive.timeout_ms=4000",
                "mosip.registration.liveness.active.enable=Y",
                "mosip.registration.liveness.active.min_challenges=2",
                "mosip.registration.liveness.active.challenge_types=BLINK,SMILE,TURN_LEFT,TURN_RIGHT",
                "mosip.registration.liveness.active.challenge_timeout_ms=5000",
                "mosip.registration.liveness.active.min_onset_ms=200",
                "mosip.registration.liveness.active.hold_ms=300",
                "mosip.registration.liveness.max_retries=3",
                "mosip.registration.liveness.on_max_failure=BLOCK",
                "mosip.registration.liveness.stream.timeout_ms=60000",
                "mosip.registration.liveness.frame.sample_interval_ms=0",
                "mosip.registration.liveness.device.reconnect_attempts=1",
                "mosip.registration.liveness.quality.min_ied_px=60",
                "mosip.registration.liveness.quality.max_yaw_deg=15",
                "mosip.registration.liveness.integrity.max_frozen_frames=5",
                "mosip.registration.liveness.integrity.min_fps=3",
                "mosip.registration.liveness.binding.min_similarity=60",
                "mosip.registration.liveness.binding.max_gap_ms=5000",
                "mosip.registration.liveness.no_stream_policy=BLOCK",
                "mosip.registration.liveness.model.min_version=1.3.0",
                "mosip.registration.liveness.diagnostic.enable=N",
                "mosip.registration.liveness.metrics.enable=Y",
                "mosip.registration.mds.allowed_certifications=L0,L1",
                "mosip.registration.liveness.android.device=BUILTIN",
                "mosip.registration.liveness.android.block_emulator=N",
                "mosip.registration.android.offline_approval.enable=Y",
                "mosip.registration.liveness.policy.registration=ALWAYS_ACTIVE",
                "mosip.registration.liveness.policy.onboard_auth=ALWAYS_ACTIVE",
                "mosip.registration.liveness.onboard_auth.passive.threshold=95",
                "mosip.registration.liveness.policy.login_auth=ALWAYS_ACTIVE",
                "mosip.registration.liveness.login_auth.passive.threshold=95",
                "mosip.registration.liveness.login_auth.on_max_failure=BLOCK_AND_LOCK",
                "mosip.registration.liveness.policy.packet_auth=ALWAYS_ACTIVE",
                "mosip.registration.liveness.packet_auth.passive.threshold=95",
                "mosip.registration.liveness.policy.exception_auth=ALWAYS_ACTIVE",
                "mosip.registration.liveness.exception_auth.passive.threshold=95",
                "mosip.registration.liveness.policy.eod_auth=ALWAYS_ACTIVE",
                "mosip.registration.liveness.eod_auth.passive.threshold=95",
                "mosip.registration.liveness.policy.exception_photo=OFF");
        List<String> actual = new ArrayList<>();
        LivenessConfigKeys.defaultValues().forEach((k, v) -> actual.add(k + "=" + v));
        assertEquals(expected, actual);
    }

    @Test
    void workflowDefaultsMatchTheDesignTable() {
        Map<String, String> d = LivenessConfigKeys.defaultValues();
        for (Workflow w : Workflow.values()) {
            String expected = expectedPolicy(w);
            assertEquals(expected, d.get("mosip.registration.liveness.policy." + w.id()));
            String threshold = d.get("mosip.registration.liveness." + w.id() + ".passive.threshold");
            assertEquals(w.isAuth() ? "95" : null, threshold);
        }
        assertEquals("BLOCK_AND_LOCK", d.get("mosip.registration.liveness.login_auth.on_max_failure"));
        assertEquals(LivenessConfigKeys.globalKeys().size() + Workflow.values().length + 6, d.size());
    }

    /** Proposal P1 of the calibration notes: every workflow that checks liveness always runs challenges. */
    private static String expectedPolicy(Workflow w) {
        return w == Workflow.EXCEPTION_PHOTO ? "OFF" : "ALWAYS_ACTIVE";
    }

    /**
     * The supervisor workflows must never accept a face on the passive score alone: a synthetic print scored 99
     * passively, above the authentication threshold of 95.
     */
    @Test
    void supervisorWorkflowsDefaultToAlwaysActive() {
        Map<String, String> d = LivenessConfigKeys.defaultValues();
        for (Workflow w : new Workflow[] {Workflow.PACKET_AUTH, Workflow.EXCEPTION_AUTH, Workflow.EOD_AUTH}) {
            assertEquals(PolicyMode.ALWAYS_ACTIVE, LivenessConfigKeys.defaultPolicyMode(w), w.id());
            assertEquals("ALWAYS_ACTIVE", d.get(LivenessConfigKeys.policyKey(w)), w.id());
            assertEquals(PolicyMode.ALWAYS_ACTIVE, LivenessPolicy.defaults(w).mode(), w.id());
        }
        for (Workflow w : Workflow.values()) {
            PolicyMode expected = w == Workflow.EXCEPTION_PHOTO ? PolicyMode.OFF : PolicyMode.ALWAYS_ACTIVE;
            assertEquals(expected, LivenessConfigKeys.defaultPolicyMode(w), w.id());
        }
    }

    @Test
    void everyGlobalKeyHasADefault() {
        for (String key : LivenessConfigKeys.globalKeys()) {
            assertTrue(LivenessConfigKeys.defaultValues().containsKey(key), key);
        }
    }

    @Test
    void noKeyForcesAClientRestart() {
        List<String> keys = new ArrayList<>(LivenessConfigKeys.defaultValues().keySet());
        keys.addAll(LivenessConfigKeys.localOverrideAllowedKeys());
        for (Workflow w : Workflow.values()) {
            keys.add(LivenessConfigKeys.workflowKey(w, LivenessConfigKeys.WORKFLOW_MIN_CHALLENGES_SUFFIX));
            keys.add(LivenessConfigKeys.workflowKey(w, LivenessConfigKeys.WORKFLOW_ON_MAX_FAILURE_SUFFIX));
        }
        keys.add(LivenessConfigKeys.guidanceKey("liveness.challenge.blink", "eng"));
        for (String key : keys) {
            assertTrue(key.startsWith("mosip.registration."), key);
            for (String bad : RESTART_SUBSTRINGS) {
                assertFalse(key.contains(bad), key + " contains " + bad);
            }
        }
    }

    @Test
    void keyHelpersBuildTheDocumentedNames() {
        assertEquals("mosip.registration.liveness.policy.eod_auth", LivenessConfigKeys.policyKey(Workflow.EOD_AUTH));
        assertEquals("mosip.registration.liveness.registration.on_max_failure",
                LivenessConfigKeys.workflowKey(Workflow.REGISTRATION, LivenessConfigKeys.WORKFLOW_ON_MAX_FAILURE_SUFFIX));
        assertEquals("mosip.registration.liveness.guidance.liveness.guide.lighting.fra",
                LivenessConfigKeys.guidanceKey("liveness.guide.lighting", "fra"));
    }

    @Test
    void protectedKeysCannotBeOverriddenLocally() {
        assertFalse(LivenessConfigKeys.isLocalOverrideAllowed(LivenessConfigKeys.ENABLE));
        assertFalse(LivenessConfigKeys.isLocalOverrideAllowed(LivenessConfigKeys.ENGINE));
        assertFalse(LivenessConfigKeys.isLocalOverrideAllowed(LivenessConfigKeys.MODEL_MIN_VERSION));
        assertFalse(LivenessConfigKeys.isLocalOverrideAllowed(LivenessConfigKeys.ON_MAX_FAILURE));
        assertFalse(LivenessConfigKeys.isLocalOverrideAllowed(null));
        for (Workflow w : Workflow.values()) {
            assertFalse(LivenessConfigKeys.isLocalOverrideAllowed(LivenessConfigKeys.policyKey(w)));
            assertTrue(LivenessConfigKeys.isLocalOverrideAllowed(
                    LivenessConfigKeys.workflowKey(w, LivenessConfigKeys.WORKFLOW_PASSIVE_THRESHOLD_SUFFIX)));
        }
        assertTrue(LivenessConfigKeys.isLocalOverrideAllowed(LivenessConfigKeys.PASSIVE_THRESHOLD));
        assertTrue(LivenessConfigKeys.isLocalOverrideAllowed(LivenessConfigKeys.DIAGNOSTIC_ENABLE));
    }

    @Test
    void localOverrideOfBindingMinSimilarityRejected() {
        assertFalse(LivenessConfigKeys.isLocalOverrideAllowed(LivenessConfigKeys.BINDING_MIN_SIMILARITY));
        assertFalse(LivenessConfigKeys.localOverrideAllowedKeys().contains(LivenessConfigKeys.BINDING_MIN_SIMILARITY));
    }

    @Test
    void localOverrideOfChallengeTypesRejected() {
        assertFalse(LivenessConfigKeys.isLocalOverrideAllowed(LivenessConfigKeys.ACTIVE_CHALLENGE_TYPES));
        assertFalse(LivenessConfigKeys.localOverrideAllowedKeys().contains(LivenessConfigKeys.ACTIVE_CHALLENGE_TYPES));
    }

    @Test
    void localOverrideOfPassiveTimeoutRejected() {
        assertFalse(LivenessConfigKeys.isLocalOverrideAllowed(LivenessConfigKeys.PASSIVE_TIMEOUT_MS));
        assertFalse(LivenessConfigKeys.localOverrideAllowedKeys().contains(LivenessConfigKeys.PASSIVE_TIMEOUT_MS));
    }

    @Test
    void localOverrideAllowListIsExactlyTheFlooredAndHarmlessKeys() {
        Set<String> expected = new java.util.HashSet<>(Arrays.asList(LivenessConfigKeys.PASSIVE_THRESHOLD,
                LivenessConfigKeys.PAD_REJECT_THRESHOLD, LivenessConfigKeys.ACTIVE_CHALLENGE_TIMEOUT_MS,
                LivenessConfigKeys.STREAM_TIMEOUT_MS, LivenessConfigKeys.MAX_RETRIES,
                LivenessConfigKeys.DIAGNOSTIC_ENABLE));
        for (Workflow w : Workflow.values()) {
            expected.add(LivenessConfigKeys.workflowKey(w, LivenessConfigKeys.WORKFLOW_PASSIVE_THRESHOLD_SUFFIX));
            expected.add(LivenessConfigKeys.workflowKey(w, LivenessConfigKeys.WORKFLOW_MAX_RETRIES_SUFFIX));
        }
        assertEquals(expected, LivenessConfigKeys.localOverrideAllowedKeys());
    }

    @Test
    void localOverrideRejectedKeysStillHaveSyncedDefaults() {
        Map<String, String> d = LivenessConfigKeys.defaultValues();
        assertEquals("60", d.get(LivenessConfigKeys.BINDING_MIN_SIMILARITY));
        assertEquals("4000", d.get(LivenessConfigKeys.PASSIVE_TIMEOUT_MS));
        assertTrue(LivenessConfigKeys.globalKeys().contains(LivenessConfigKeys.ACTIVE_CHALLENGE_TYPES),
                "the server still sets the pool through sync");
    }

    @Test
    void defaultChallengePoolMatchesThePolicyDefault() {
        Set<ChallengeType> parsed = EnumSet.noneOf(ChallengeType.class);
        for (String name : PolicyKey.ACTIVE_CHALLENGE_TYPES.defaultValue().split(",")) {
            parsed.add(ChallengeType.fromName(name).orElseThrow(IllegalStateException::new));
        }
        assertEquals(parsed, LivenessPolicy.defaults(Workflow.REGISTRATION).challengeTypes());
    }

    @Test
    void configSourceReadsAMapCopy() {
        Map<String, String> values = new java.util.HashMap<>();
        values.put(LivenessConfigKeys.ENABLE, "N");
        ConfigSource source = ConfigSource.of(values);
        values.put(LivenessConfigKeys.ENABLE, "Y");
        assertEquals("N", source.get(LivenessConfigKeys.ENABLE).orElse(null));
        assertFalse(source.get(LivenessConfigKeys.ENGINE).isPresent());
        assertFalse(ConfigSource.empty().get(LivenessConfigKeys.ENABLE).isPresent());
    }
}
