package io.mosip.liveness.core.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Workflow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
        assertEquals("BLINK,SMILE,TURN_LEFT,TURN_RIGHT,LOOK_UP", d.get("mosip.registration.liveness.active.challenge_types"));
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
        assertEquals("1.0.0", d.get("mosip.registration.liveness.model.min_version"));
        assertEquals("N", d.get("mosip.registration.liveness.diagnostic.enable"));
        assertEquals("Y", d.get("mosip.registration.liveness.metrics.enable"));
        assertEquals("L0,L1", d.get("mosip.registration.mds.allowed_certifications"));
        assertEquals("BUILTIN", d.get("mosip.registration.liveness.android.device"));
        assertEquals("N", d.get("mosip.registration.liveness.android.block_emulator"));
        assertEquals("Y", d.get("mosip.registration.android.offline_approval.enable"));
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
        assertEquals(LivenessConfigKeys.globalKeys().size() + Workflow.values().length + 5, d.size());
    }

    /** Proposal P1 of the calibration notes: resident capture and operator sign-in always run challenges. */
    private static String expectedPolicy(Workflow w) {
        switch (w) {
            case EXCEPTION_PHOTO:
                return "OFF";
            case REGISTRATION:
            case ONBOARD_AUTH:
            case LOGIN_AUTH:
                return "ALWAYS_ACTIVE";
            default:
                return "PASSIVE_THEN_ACTIVE";
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
        assertTrue(LivenessConfigKeys.isLocalOverrideAllowed(LivenessConfigKeys.ACTIVE_CHALLENGE_TYPES));
        assertTrue(LivenessConfigKeys.isLocalOverrideAllowed(LivenessConfigKeys.DIAGNOSTIC_ENABLE));
    }

    @Test
    void defaultChallengePoolMatchesThePolicyDefault() {
        Set<ChallengeType> parsed = EnumSet.noneOf(ChallengeType.class);
        for (String name : LivenessConfigKeys.DEFAULT_CHALLENGE_TYPES.split(",")) {
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
