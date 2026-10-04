package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.ChallengeOutcome;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.ui.MessageKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Active-phase details: neutral wait, final checks, cancellation and slow streams. */
class ActivePhaseTest {

    private static final LivenessPolicy REG = TestPolicies.passiveFirst(Workflow.REGISTRATION);

    @Test
    void noNeutralFaceBeforeThePromptTimesOut() {
        SessionHarness h = SessionScenarioTest.escalated(REG);
        h.frames(160, TestFaces.neutral().yaw(14));
        assertEquals(SessionState.FAILED, h.state());
        LivenessResult r = h.result();
        assertEquals(FailureReason.TIMEOUT, r.failureReason().get());
        assertTrue(r.reasonCodes().contains(ReasonCode.NEUTRAL_TIMEOUT));
        assertTrue(r.challenges().isEmpty(), "a challenge that was never shown is not recorded");
        assertEquals("CHALLENGE_WAIT",
                h.listener.lastAudit(AuditEventId.LIVENESS_TIMEOUT).attributes().get(AuditAttributes.PHASE));
        assertTrue(h.listener.lastUi().guidanceKeys().contains(MessageKeys.GUIDE_LOOK_CAMERA));
        assertTrue(h.listener.sawUi(MessageKeys.GUIDE_LOOK_CAMERA));
    }

    @Test
    void movingAwayFromNeutralRestartsTheHold() {
        SessionHarness h = SessionScenarioTest.escalated(REG);
        for (int i = 0; i < 60 && h.state() == SessionState.CHALLENGE_WAIT; i++) {
            // Pitch 25 lies beyond the neutral limit (20 degrees, equal to the quality gate's pitch limit).
            h.frame(i % 5 == 4 ? TestFaces.neutral().pitch(25) : TestFaces.neutral());
        }
        assertEquals(SessionState.CHALLENGE_WAIT, h.state(), "four neutral frames (150 ms) never reach the 300 ms hold");
        assertNotNull(h.awaitPrompt());
        assertTrue(h.listener.sawUi(MessageKeys.FEEDBACK_CONTINUE));
    }

    @Test
    void secondFaceDuringChallengesStopsProgressAndEndsTheAttempt() {
        SessionHarness h = SessionScenarioTest.escalated(REG);
        ChallengeType t = h.awaitPrompt();
        h.frames(8, TestFaces.neutral());
        for (int i = 0; i < 2; i++) {
            h.frameWith(TestFaces.acting(t).build(), TestFaces.neutral().shiftX(200).build());
        }
        assertEquals(SessionState.CHALLENGE_OBSERVE, h.state(), "no single face, no progress");
        h.frameWith(TestFaces.acting(t).build(), TestFaces.neutral().shiftX(200).build());
        // The third frame in a row with two faces ends the attempt (not counted) instead of running to the timeout.
        assertEquals(FailureReason.QUALITY, h.result().failureReason().get());
        assertTrue(h.result().reasonCodes().contains(ReasonCode.MULTI_FACE));
        assertEquals(ChallengeOutcome.CANCELLED, h.result().challenges().get(0).outcome(),
                "an action shown with a second face in view never passes");
    }

    @Test
    void finalPadMedianBelowRejectFails() {
        // 20 passive frames make the rolling window 20 scores; with PAD on every 10th frame it never fills, so only
        // the final median over the whole active phase can catch the attack.
        SessionHarness h = new SessionHarness(REG.toBuilder().passiveMinFrames(20).build());
        h.params = EngineParameters.builder().set(EngineParameter.ACTIVE_PAD_STRIDE, 10).build();
        h.start();
        h.pad.next = 0.6;
        h.runPassive(TestFaces.neutral());
        h.pad.next = 0.1;
        h.runActive(false);
        assertEquals(SessionState.FAILED, h.state(), h.listener.auditIds().toString());
        assertEquals(FailureReason.PAD, h.result().failureReason().get());
        assertEquals(10, h.result().activePadScore().getAsInt());
        assertEquals(2, h.result().challenges().size());
    }

    @Test
    void faceSwitchAfterTheLastChallengeIsCaught() {
        SessionHarness h = SessionScenarioTest.escalated(REG);
        ChallengeType first = h.awaitPrompt();
        h.perform(first, false);
        ChallengeType second = h.awaitPrompt();
        h.frames(8, TestFaces.neutral());
        act(h, second);
        assertEquals(SessionState.CHALLENGE_PASSED, h.state());
        h.embedder.next = ScriptedModels.PERSON_B;
        h.frames(20, TestFaces.neutral());
        assertEquals(SessionState.FAILED, h.state());
        assertTrue(h.result().reasonCodes().contains(ReasonCode.FACE_SWITCH));
        assertEquals(ChallengeOutcome.PASSED, h.result().challenges().get(1).outcome());
    }

    @Test
    void cancelDuringAChallengeRecordsIt() {
        SessionHarness h = SessionScenarioTest.escalated(REG);
        h.awaitPrompt();
        h.frames(3, TestFaces.neutral());
        h.session.cancel();
        LivenessResult r = h.result();
        assertEquals(FailureReason.CANCELLED, r.failureReason().get());
        assertEquals(ChallengeOutcome.CANCELLED, r.challenges().get(0).outcome());
        assertTrue(r.activeMs() > 0);
    }

    @Test
    void goodButTooFewFramesTimeOutWithoutAQualityProblem() {
        SessionHarness h = new SessionHarness(REG.toBuilder().frameSampleIntervalMs(1_000).build()).start();
        h.frames(90, TestFaces.neutral());
        LivenessResult r = h.result();
        assertEquals(FailureReason.QUALITY, r.failureReason().get());
        assertEquals(1, r.reasonCodes().size());
        assertTrue(r.reasonCodes().contains(ReasonCode.PASSIVE_TIMEOUT));
        assertEquals(MessageKeys.ERROR_POOR_QUALITY, h.listener.lastUi().messageKey());
    }

    /** A slow device that cannot deliver 30 passive frames in time decides on the frames it has, if enough. */
    @Test
    void slowDeviceGetsThePassiveDecisionOnTheFramesItHasAtTheTimeout() {
        SessionHarness h = new SessionHarness(REG.toBuilder().passiveMinFrames(30).build());
        h.frameIntervalMs = 250;
        h.start();
        h.frames(40, TestFaces.neutral());
        assertEquals(SessionState.CAPTURE_READY, h.state(), h.listener.auditIds().toString());
        assertNotNull(h.listener.lastAudit(AuditEventId.LIVENESS_PASSIVE_PASSED), "decided passively after 4 s");
    }

    /** Frames turned beyond active.pad_max_yaw_deg (30) are not scored by PAD during the challenges. */
    @Test
    void profileFramesAreNotScoredByTheActivePad() {
        SessionHarness h = SessionScenarioTest.escalated(REG);
        int before = h.pad.calls;
        h.frames(10, TestFaces.neutral().yaw(45));
        assertEquals(before, h.pad.calls, "no PAD run on a face in profile");
        h.frames(10, TestFaces.neutral().yaw(25));
        assertEquals(before + 5, h.pad.calls, "every second frame within 30 degrees");
    }

    @Test
    void remindToFollowTheActionAfterHalfTheTime() {
        SessionHarness h = SessionScenarioTest.escalated(REG);
        h.awaitPrompt();
        h.frames(55, TestFaces.neutral());
        assertTrue(h.listener.lastUi().guidanceKeys().contains(MessageKeys.GUIDE_FOLLOW_ACTION));
    }

    @Test
    void timersAdvanceWithoutFramesUntilTheWatchdog() {
        SessionHarness h = SessionScenarioTest.escalated(REG);
        ChallengeType t = h.awaitPrompt();
        h.frames(8, TestFaces.neutral());
        act(h, t);
        assertEquals(SessionState.CHALLENGE_PASSED, h.state());
        h.tick(700);
        assertEquals(SessionState.CHALLENGE_WAIT, h.state(), "the display ends on a tick");
    }

    /** Performs the action and stops while "Action detected" is shown. */
    private static void act(SessionHarness h, ChallengeType t) {
        if (t == ChallengeType.BLINK) {
            h.frames(3, TestFaces.acting(t));
            h.frames(2, TestFaces.neutral());
        } else {
            h.frames(8, TestFaces.acting(t));
        }
    }
}
