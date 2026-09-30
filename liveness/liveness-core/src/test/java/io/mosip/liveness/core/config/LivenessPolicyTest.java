package io.mosip.liveness.core.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Workflow;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivenessPolicyTest {

    @Test
    void residentDefaults() {
        LivenessPolicy p = LivenessPolicy.defaults(Workflow.REGISTRATION);
        assertEquals(PolicyMode.ALWAYS_ACTIVE, p.mode());
        assertEquals(80, p.passiveThreshold());
        assertEquals(20, p.padRejectThreshold());
        assertEquals(30, p.passiveMinFrames());
        assertEquals(2, p.minChallenges());
        assertEquals(3, p.maxRetries());
        assertEquals(OnMaxFailure.BLOCK, p.onMaxFailure());
        assertEquals(60_000L, p.streamTimeoutMs());
        assertTrue(p.isEnabled());
        assertTrue(p.isActiveEnabled());
        assertTrue(p.isMetricsEnabled());
        assertFalse(p.isDiagnosticEnabled());
    }

    @Test
    void authWorkflowsUseTheHigherThresholdAndExceptionPhotoIsOff() {
        for (Workflow w : Workflow.values()) {
            LivenessPolicy p = LivenessPolicy.defaults(w);
            assertEquals(w.isAuth() ? 95 : 80, p.passiveThreshold(), w.id());
        }
        assertEquals(PolicyMode.OFF, LivenessPolicy.defaults(Workflow.EXCEPTION_PHOTO).mode());
        assertFalse(LivenessPolicy.defaults(Workflow.EXCEPTION_PHOTO).isEnabled());
    }

    @Test
    void rejectThresholdMustNotExceedPassiveThreshold() {
        LivenessPolicy.Builder b = LivenessPolicy.builder(Workflow.REGISTRATION).passiveThreshold(50).padRejectThreshold(51);
        assertThrows(IllegalArgumentException.class, b::build);
        LivenessPolicy.Builder equal = LivenessPolicy.builder(Workflow.REGISTRATION).passiveThreshold(50).padRejectThreshold(50);
        assertEquals(50, equal.build().padRejectThreshold());
    }

    @Test
    void structuralRangesAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> LivenessPolicy.builder(Workflow.REGISTRATION).passiveThreshold(101).build());
        assertThrows(IllegalArgumentException.class, () -> LivenessPolicy.builder(Workflow.REGISTRATION).minChallenges(0).build());
        assertThrows(IllegalArgumentException.class, () -> LivenessPolicy.builder(Workflow.REGISTRATION).maxRetries(0).build());
        assertThrows(IllegalArgumentException.class, () -> LivenessPolicy.builder(Workflow.REGISTRATION).challengeTimeoutMs(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> LivenessPolicy.builder(Workflow.REGISTRATION).challengeTypes(EnumSet.noneOf(ChallengeType.class)).build());
        assertThrows(IllegalArgumentException.class, () -> LivenessPolicy.builder(Workflow.REGISTRATION).modelMinVersion(" ").build());
    }

    @Test
    void onMaxFailureMustSuitTheWorkflow() {
        assertEquals(OnMaxFailure.SUPERVISOR_OVERRIDE, LivenessPolicy.builder(Workflow.REGISTRATION)
                .onMaxFailure(OnMaxFailure.SUPERVISOR_OVERRIDE).build().onMaxFailure());
        assertEquals(OnMaxFailure.BLOCK_AND_LOCK, LivenessPolicy.builder(Workflow.LOGIN_AUTH)
                .onMaxFailure(OnMaxFailure.BLOCK_AND_LOCK).build().onMaxFailure());
        assertThrows(IllegalArgumentException.class,
                () -> LivenessPolicy.builder(Workflow.PACKET_AUTH).onMaxFailure(OnMaxFailure.SUPERVISOR_OVERRIDE).build());
        assertThrows(IllegalArgumentException.class,
                () -> LivenessPolicy.builder(Workflow.REGISTRATION).onMaxFailure(OnMaxFailure.BLOCK_AND_LOCK).build());
    }

    @Test
    void challengePoolIsCopiedAndUnmodifiable() {
        Set<ChallengeType> pool = EnumSet.of(ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT);
        LivenessPolicy p = LivenessPolicy.builder(Workflow.PACKET_AUTH).challengeTypes(pool).build();
        pool.add(ChallengeType.BLINK);
        assertEquals(EnumSet.of(ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT), p.challengeTypes());
        assertThrows(UnsupportedOperationException.class, () -> p.challengeTypes().add(ChallengeType.SMILE));
    }

    @Test
    void toBuilderRoundTripsEveryField() {
        LivenessPolicy p = LivenessPolicy.builder(Workflow.EOD_AUTH).mode(PolicyMode.ALWAYS_ACTIVE).passiveThreshold(90)
                .padRejectThreshold(10).minChallenges(3).maxRetries(5).onMaxFailure(OnMaxFailure.BLOCK_AND_LOCK)
                .diagnosticEnabled(true).noStreamPolicy(NoStreamPolicy.CAPTURE_ONLY).bindingMinSimilarity(70).build();
        assertEquals(p, p.toBuilder().build());
        assertEquals(p.hashCode(), p.toBuilder().build().hashCode());
        assertNotEquals(p, p.toBuilder().holdMs(400).build());
    }

    @Test
    void enumParsersIgnoreCaseAndRejectUnknownValues() {
        assertEquals(PolicyMode.PASSIVE_ONLY, PolicyMode.fromName(" passive_only ").orElse(null));
        assertEquals(OnMaxFailure.BLOCK_AND_LOCK, OnMaxFailure.fromName("block_and_lock").orElse(null));
        assertEquals(NoStreamPolicy.CAPTURE_ONLY, NoStreamPolicy.fromName("Capture_Only").orElse(null));
        assertFalse(PolicyMode.fromName("ON").isPresent());
        assertFalse(OnMaxFailure.fromName(null).isPresent());
    }
}
