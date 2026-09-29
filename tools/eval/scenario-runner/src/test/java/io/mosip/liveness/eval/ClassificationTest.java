package io.mosip.liveness.eval;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.ChallengeOutcome;
import io.mosip.liveness.core.api.ChallengeRecord;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClassificationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    static LivenessResult fail(FailureReason reason, LivenessMethod method, ReasonCode... codes) {
        LivenessResult.Builder b = LivenessResult.builder("s", Workflow.REGISTRATION, ScriptedEngine.engineInfo(),
                AttemptDriver.device()).fail(reason).method(method).period(T0, T0.plusSeconds(1)).timings(0, 0, 1000);
        for (ReasonCode c : codes) {
            b.reason(c);
        }
        return b.build();
    }

    static LivenessResult pass() {
        return LivenessResult.builder("s", Workflow.REGISTRATION, ScriptedEngine.engineInfo(), AttemptDriver.device())
                .pass().method(LivenessMethod.PASSIVE).passiveScore(90).period(T0, T0.plusSeconds(1))
                .timings(500, 0, 1000).build();
    }

    @Test
    void outcomeFollowsWhetherTheFailureCountsAsAnAttempt() {
        assertEquals(Outcome.ACCEPT, Outcome.of(pass()));
        assertEquals(Outcome.REJECT, Outcome.of(fail(FailureReason.PAD, LivenessMethod.PASSIVE, ReasonCode.PAD_SCORE)));
        assertEquals(Outcome.REJECT, Outcome.of(fail(FailureReason.INTEGRITY, LivenessMethod.PASSIVE, ReasonCode.FROZEN)));
        assertEquals(Outcome.REJECT, Outcome.of(fail(FailureReason.ACTIVE, LivenessMethod.ACTIVE)));
        assertEquals(Outcome.REJECT, Outcome.of(fail(FailureReason.BINDING, LivenessMethod.PASSIVE)));
        assertEquals(Outcome.NO_RESPONSE, Outcome.of(fail(FailureReason.QUALITY, LivenessMethod.NONE)));
        assertEquals(Outcome.NO_RESPONSE, Outcome.of(fail(FailureReason.DEVICE, LivenessMethod.NONE)));
        assertEquals(Outcome.NO_RESPONSE, Outcome.of(fail(FailureReason.ENGINE, LivenessMethod.NONE)));
        assertEquals(Outcome.NO_RESPONSE, Outcome.of(fail(FailureReason.CANCELLED, LivenessMethod.NONE)));
    }

    @Test
    void transactionOutcome() {
        assertEquals(Outcome.ACCEPT, Outcome.ofTransaction(Arrays.asList(Outcome.REJECT, Outcome.ACCEPT)));
        assertEquals(Outcome.REJECT, Outcome.ofTransaction(Arrays.asList(Outcome.NO_RESPONSE, Outcome.REJECT)));
        assertEquals(Outcome.NO_RESPONSE, Outcome.ofTransaction(Arrays.asList(Outcome.NO_RESPONSE)));
        assertThrows(IllegalArgumentException.class, () -> Outcome.ofTransaction(Collections.<Outcome>emptyList()));
    }

    @Test
    void layerNamesTheDefenceThatStoppedTheAttempt() {
        assertEquals(Layer.NONE, Layer.of(pass()));
        assertEquals(Layer.PASSIVE_PAD, Layer.of(fail(FailureReason.PAD, LivenessMethod.PASSIVE, ReasonCode.PAD_SCORE)));
        assertEquals(Layer.ACTIVE_PAD,
                Layer.of(fail(FailureReason.PAD, LivenessMethod.PASSIVE_ACTIVE, ReasonCode.PAD_SCORE)));
        assertEquals(Layer.PASSIVE_PAD, Layer.of(fail(FailureReason.BELOW_THRESHOLD, LivenessMethod.PASSIVE,
                ReasonCode.BELOW_THRESHOLD)));
        assertEquals(Layer.INTEGRITY, Layer.of(fail(FailureReason.INTEGRITY, LivenessMethod.PASSIVE, ReasonCode.LOOP)));
        assertEquals(Layer.PLANARITY,
                Layer.of(fail(FailureReason.INTEGRITY, LivenessMethod.ACTIVE, ReasonCode.PLANAR)));
        assertEquals(Layer.CHALLENGE, Layer.of(fail(FailureReason.ACTIVE, LivenessMethod.ACTIVE,
                ReasonCode.WRONG_ACTION)));
        assertEquals(Layer.CHALLENGE, Layer.of(fail(FailureReason.TIMEOUT, LivenessMethod.ACTIVE,
                ReasonCode.CHALLENGE_TIMEOUT)));
        assertEquals(Layer.BINDING, Layer.of(fail(FailureReason.BINDING, LivenessMethod.PASSIVE,
                ReasonCode.SIMILARITY_LOW)));
        assertEquals(Layer.BINDING, Layer.of(fail(FailureReason.PAD, LivenessMethod.PASSIVE,
                ReasonCode.CAPTURE_PAD_SCORE)));
        assertEquals(Layer.QUALITY, Layer.of(fail(FailureReason.QUALITY, LivenessMethod.NONE, ReasonCode.NO_FACE)));
        assertEquals(Layer.ERROR, Layer.of(fail(FailureReason.DEVICE, LivenessMethod.NONE, ReasonCode.DEVICE_ERROR)));
    }

    @Test
    void failedChallengesMakeAPadFailureAnActivePadFailure() {
        LivenessResult r = LivenessResult.builder("s", Workflow.REGISTRATION, ScriptedEngine.engineInfo(),
                AttemptDriver.device()).fail(FailureReason.PAD).method(LivenessMethod.PASSIVE)
                .reason(ReasonCode.PAD_SCORE)
                .challenge(ChallengeRecord.failed(ChallengeType.BLINK, 0, ChallengeOutcome.PAD_FAILED, 300))
                .period(T0, T0.plusSeconds(1)).timings(0, 0, 1000).build();
        assertEquals(Layer.ACTIVE_PAD, Layer.of(r));
    }

    @Test
    void layerIdsRoundTrip() {
        for (Layer l : Layer.values()) {
            assertEquals(l, Layer.fromId(l.id()));
        }
    }
}
