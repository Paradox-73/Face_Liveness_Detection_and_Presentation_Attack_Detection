package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.ChallengeOutcome;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.config.PolicyMode;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.liveness.core.vision.DetectedFace;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end scenarios of the session state machine with synthetic face observations (design sections 4.5-4.10,
 * tests T-CORE-SM, T-SCN-L).
 */
class SessionScenarioTest {

    private static LivenessPolicy registration() {
        return TestPolicies.passiveFirst(Workflow.REGISTRATION);
    }

    @Test
    void bonaFideFacePassesPassivelyAndBinds() {
        SessionHarness h = new SessionHarness(registration()).start();
        assertEquals(SessionState.OPENING, h.state());
        h.pad.next = 0.92;
        h.runPassive(TestFaces.neutral());
        assertEquals(SessionState.CAPTURE_READY, h.state());
        assertTrue(h.session.passToken().isPresent());
        assertTrue(h.session.captureFrame().isPresent());

        BindingVerdict v = h.captureAndBind();
        assertTrue(v.isPassed(), v.toString());
        assertEquals(SessionState.PASSED, h.state());
        LivenessResult r = h.result();
        assertEquals(Decision.PASS, r.decision());
        assertEquals(LivenessMethod.PASSIVE, r.method());
        assertEquals(92, r.passiveScore().getAsInt());
        assertEquals(100, r.bindingSimilarity().getAsInt());
        assertTrue(r.challenges().isEmpty());
        assertEquals(Arrays.asList(AuditEventId.LIVENESS_SESSION_STARTED, AuditEventId.LIVENESS_PASSIVE_PASSED,
                AuditEventId.LIVENESS_PASSED), h.listener.auditIds());
        assertEquals(Arrays.asList(SessionState.OPENING, SessionState.ACQUIRING, SessionState.PASSIVE,
                SessionState.CAPTURE_READY, SessionState.CAPTURING, SessionState.BINDING, SessionState.PASSED),
                h.listener.states);
        assertTrue(h.listener.sawUi(MessageKeys.STATUS_CHECKING));
        assertTrue(h.listener.sawUi(MessageKeys.FEEDBACK_HOLD_STILL));
        assertEquals(MessageKeys.FEEDBACK_SUCCESS, h.listener.lastUi().messageKey());
        assertEquals(1, h.listener.results.size());
        Map<String, String> evidence = PacketEvidenceKeys.of(r);
        assertEquals("PASS", evidence.get(PacketEvidenceKeys.DECISION));
        assertEquals("90-100", evidence.get(PacketEvidenceKeys.BINDING));
        assertEquals(1, h.metrics.snapshot().counter(LivenessMetrics.PASSIVE_PASSES));
    }

    @Test
    void lowScoreEscalatesAndCorrectActionsPass() {
        SessionHarness h = new SessionHarness(registration()).start();
        h.pad.next = 0.6;
        h.runPassive(TestFaces.neutral());
        assertEquals(SessionState.CHALLENGE_WAIT, h.state());
        LivenessAuditEvent triggered = h.listener.lastAudit(AuditEventId.LIVENESS_ACTIVE_TRIGGERED);
        assertEquals("BELOW_THRESHOLD", triggered.attributes().get(AuditAttributes.REASON));
        assertEquals("60-69", triggered.attributes().get(AuditAttributes.SCORE_BUCKET));

        h.pad.next = 0.7;
        List<ChallengeType> done = h.runActive(false);
        assertEquals(2, done.size());
        assertTrue(ChallengeScheduler.containsTurn(done), "a head turn is always drawn when planarity is on");
        assertEquals(SessionState.CAPTURE_READY, h.state(), h.listener.auditIds().toString());
        assertTrue(h.captureAndBind().isPassed());
        LivenessResult r = h.result();
        assertEquals(LivenessMethod.PASSIVE_ACTIVE, r.method());
        assertEquals(done.get(0).name() + ":OK," + done.get(1).name() + ":OK", r.challengeEvidence());
        assertEquals(70, r.activePadScore().getAsInt());
        assertTrue(r.activeMs() > 0 && r.totalMs() >= r.passiveMs() + r.activeMs());
        assertEquals(2, count(h, AuditEventId.LIVENESS_CHALLENGE_ISSUED));
        assertEquals(2, count(h, AuditEventId.LIVENESS_CHALLENGE_PASSED));
        assertTrue(h.listener.sawUi(MessageKeys.FEEDBACK_DETECTED));
        assertTrue(h.listener.sawUi(MessageKeys.challengeKey(done.get(0))));
        LivenessAuditEvent passed = h.listener.lastAudit(AuditEventId.LIVENESS_CHALLENGE_PASSED);
        assertTrue(Long.parseLong(passed.attributes().get(AuditAttributes.ONSET_MS)) >= 200);
        assertTrue(Long.parseLong(passed.attributes().get(AuditAttributes.HOLD_MS)) >= 80);
    }

    @Test
    void wrongActionFailsTheChallenge() {
        SessionHarness h = escalated(registration());
        ChallengeType asked = h.awaitPrompt();
        ChallengeType wrong = asked == ChallengeType.TURN_LEFT ? ChallengeType.TURN_RIGHT : ChallengeType.TURN_LEFT;
        h.frames(8, TestFaces.neutral());
        h.frames(6, TestFaces.acting(wrong));
        assertEquals(SessionState.FAILED, h.state());
        LivenessResult r = h.result();
        assertEquals(FailureReason.ACTIVE, r.failureReason().get());
        assertTrue(r.reasonCodes().contains(ReasonCode.WRONG_ACTION));
        assertEquals(ChallengeOutcome.WRONG_ACTION, r.challenges().get(0).outcome());
        assertEquals("WRONG_ACTION",
                h.listener.lastAudit(AuditEventId.LIVENESS_CHALLENGE_FAILED).attributes().get(AuditAttributes.REASON));
        assertEquals(MessageKeys.FAIL_LIVENESS, h.listener.lastUi().messageKey());
        assertTrue(h.listener.lastUi().guidanceKeys().contains(MessageKeys.GUIDE_FOLLOW_ACTION));
        assertTrue(h.listener.lastUi().isRetryAvailable());
    }

    @Test
    void noActionTimesOut() {
        SessionHarness h = escalated(registration());
        assertNotNull(h.awaitPrompt());
        h.frames(90, TestFaces.neutral());
        h.frames(20, TestFaces.neutral());
        assertEquals(SessionState.FAILED, h.state());
        LivenessResult r = h.result();
        assertEquals(FailureReason.TIMEOUT, r.failureReason().get());
        assertEquals(Decision.TIMEOUT, r.decision());
        assertTrue(r.reasonCodes().contains(ReasonCode.CHALLENGE_TIMEOUT));
        assertEquals(ChallengeOutcome.TIMEOUT, r.challenges().get(0).outcome());
        assertEquals("CHALLENGE_OBSERVE",
                h.listener.lastAudit(AuditEventId.LIVENESS_TIMEOUT).attributes().get(AuditAttributes.PHASE));
        assertTrue(h.listener.sawUi(MessageKeys.GUIDE_FOLLOW_ACTION) || hasGuidance(h, MessageKeys.GUIDE_FOLLOW_ACTION));
        assertTrue(h.listener.lastUi().guidanceKeys().contains(MessageKeys.ERROR_TIMEOUT));
    }

    @Test
    void timeoutAlsoFiresFromTicksWithoutFrames() {
        SessionHarness h = escalated(registration());
        assertNotNull(h.awaitPrompt());
        // The stream stalls: the no-frame watchdog ends the session as a device error, not a counted timeout.
        h.tick(1_000);
        h.tick(1_100);
        assertEquals(SessionState.FAILED, h.state());
        assertEquals(FailureReason.DEVICE, h.result().failureReason().get());
        assertEquals(ChallengeOutcome.CANCELLED, h.result().challenges().get(0).outcome());
    }

    @Test
    void rigidPhotoMotionFailsPlanarity() {
        SessionHarness h = escalated(registration());
        h.runActive(true);
        assertEquals(SessionState.FAILED, h.state());
        LivenessResult r = h.result();
        assertEquals(FailureReason.INTEGRITY, r.failureReason().get());
        assertTrue(r.reasonCodes().contains(ReasonCode.PLANAR));
        assertEquals(Decision.FAIL_PAD, r.decision());
        assertEquals("PLANAR",
                h.listener.lastAudit(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED).attributes().get(AuditAttributes.REASON));
        assertEquals(MessageKeys.FAIL_PAD, h.listener.lastUi().messageKey());
    }

    @Test
    void frozenFramesFailIntegrity() {
        SessionHarness h = new SessionHarness(registration()).start();
        byte[] still = h.noise();
        for (int i = 0; i < 10 && !h.state().isTerminal(); i++) {
            h.clock.advanceMs(33);
            h.analyzer.faces(TestFaces.neutral().build());
            h.session.onFrame(h.grayFrame(still.clone()));
        }
        assertEquals(SessionState.FAILED, h.state());
        LivenessResult r = h.result();
        assertEquals(FailureReason.INTEGRITY, r.failureReason().get());
        assertTrue(r.reasonCodes().contains(ReasonCode.FROZEN));
        assertEquals("FROZEN",
                h.listener.lastAudit(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED).attributes().get(AuditAttributes.REASON));
        assertEquals(MessageKeys.FAIL_PAD, h.listener.lastUi().messageKey());
        assertFalse(h.listener.lastUi().isOverrideAvailable());
    }

    @Test
    void maxRetriesExhaustTheTransaction() {
        LivenessPolicy policy = registration().toBuilder().onMaxFailure(OnMaxFailure.SUPERVISOR_OVERRIDE).build();
        LivenessTransaction tx = new LivenessTransaction(policy);
        SessionHarness last = null;
        while (tx.canRetry()) {
            SessionHarness h = escalated(policy, tx.nextAttempt());
            h.awaitPrompt();
            h.frames(120, TestFaces.neutral());
            assertEquals(FailureReason.TIMEOUT, h.result().failureReason().get());
            tx.record(h.result());
            last = h;
        }
        assertEquals(LivenessTransaction.Status.EXHAUSTED, tx.status());
        assertEquals(3, tx.attemptsUsed());
        assertTrue(tx.isOverrideAllowed());
        assertNotNull(last.listener.lastAudit(AuditEventId.LIVENESS_MAX_RETRIES));
        assertEquals("SUPERVISOR_OVERRIDE", last.listener.lastAudit(AuditEventId.LIVENESS_MAX_RETRIES).attributes()
                .get(AuditAttributes.ON_MAX_FAILURE));
        assertEquals(UiPhase.MAX_ATTEMPTS, last.listener.lastUi().phase());
        assertEquals(MessageKeys.RETRY_MAX_RESIDENT, last.listener.lastUi().messageKey());
        assertEquals(Arrays.asList("3"), last.listener.lastUi().args());
        assertTrue(last.listener.lastUi().isOverrideAvailable());
    }

    @Test
    void padScoreBelowRejectThresholdFailsWithoutChallenge() {
        SessionHarness h = new SessionHarness(registration()).start();
        h.pad.next = 0.1;
        h.runPassive(TestFaces.neutral());
        assertEquals(SessionState.FAILED, h.state());
        LivenessResult r = h.result();
        assertEquals(FailureReason.PAD, r.failureReason().get());
        assertTrue(r.reasonCodes().contains(ReasonCode.PAD_SCORE));
        assertEquals(10, r.passiveScore().getAsInt());
        assertEquals("PAD_SCORE",
                h.listener.lastAudit(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED).attributes().get(AuditAttributes.REASON));
        assertEquals(MessageKeys.FAIL_PAD, h.listener.lastUi().messageKey());
        assertTrue(r.challenges().isEmpty());
    }

    @Test
    void authWorkflowBelowItsThresholdEscalatesAndAtThresholdPasses() {
        SessionHarness below = new SessionHarness(TestPolicies.passiveFirst(Workflow.PACKET_AUTH)).start();
        below.pad.next = 0.94;
        below.runPassive(TestFaces.neutral());
        assertEquals(SessionState.CHALLENGE_WAIT, below.state());

        SessionHarness at = new SessionHarness(TestPolicies.passiveFirst(Workflow.PACKET_AUTH)).start();
        at.pad.next = 0.95;
        at.runPassive(TestFaces.neutral());
        assertEquals(SessionState.CAPTURE_READY, at.state());
    }

    @Test
    void passiveOnlyBelowThresholdFails() {
        SessionHarness h = new SessionHarness(registration().toBuilder().mode(PolicyMode.PASSIVE_ONLY).build()).start();
        h.pad.next = 0.5;
        h.runPassive(TestFaces.neutral());
        assertEquals(FailureReason.BELOW_THRESHOLD, h.result().failureReason().get());
        assertEquals(MessageKeys.FAIL_LIVENESS, h.listener.lastUi().messageKey());
        assertTrue(h.listener.lastUi().guidanceKeys().contains(MessageKeys.GUIDE_LIGHTING));
    }

    @Test
    void alwaysActiveRunsChallengesEvenAboveThreshold() {
        SessionHarness h = new SessionHarness(registration().toBuilder().mode(PolicyMode.ALWAYS_ACTIVE).build()).start();
        h.pad.next = 0.99;
        h.runPassive(TestFaces.neutral());
        assertEquals(SessionState.CHALLENGE_WAIT, h.state());
        assertEquals("POLICY",
                h.listener.lastAudit(AuditEventId.LIVENESS_ACTIVE_TRIGGERED).attributes().get(AuditAttributes.REASON));
        h.runActive(false);
        assertEquals(SessionState.CAPTURE_READY, h.state());
        assertTrue(h.captureAndBind().isPassed());
        assertEquals(LivenessMethod.ACTIVE, h.result().method());
    }

    /**
     * Shipped defaults (calibration notes P1 and P3): registration and operator sign-in collect 30 passive frames and
     * then always run the challenges, however high the passive score.
     */
    @Test
    void shippedDefaultsRunChallengesForRegistrationAndSignIn() {
        for (Workflow w : new Workflow[] {Workflow.REGISTRATION, Workflow.LOGIN_AUTH, Workflow.ONBOARD_AUTH}) {
            SessionHarness h = new SessionHarness(LivenessPolicy.defaults(w)).start();
            h.pad.next = 0.99;
            h.runPassive(TestFaces.neutral());
            assertEquals(SessionState.CHALLENGE_WAIT, h.state(), w.id());
            assertEquals(30, h.pad.calls, w.id() + ": passive frames before the decision");
            h.runActive(false);
            assertEquals(SessionState.CAPTURE_READY, h.state(), w.id());
        }
    }

    /**
     * Shipped defaults of the supervisor workflows: a passive score of 99 (what a synthetic print reached, above the
     * authentication threshold of 95) still leads to {@code active.min_challenges} challenges before any capture.
     */
    @Test
    void supervisorWorkflowChallengesDespiteHighPassiveScore() {
        for (Workflow w : new Workflow[] {Workflow.PACKET_AUTH, Workflow.EXCEPTION_AUTH, Workflow.EOD_AUTH}) {
            LivenessPolicy policy = LivenessPolicy.defaults(w);
            assertEquals(PolicyMode.ALWAYS_ACTIVE, policy.mode(), w.id());
            SessionHarness h = new SessionHarness(policy).start();
            h.pad.next = 0.99;
            h.runPassive(TestFaces.neutral());
            assertEquals(SessionState.CHALLENGE_WAIT, h.state(), w.id() + ": no capture on the passive score alone");
            assertFalse(h.session.passToken().isPresent(), w.id());
            assertEquals("POLICY", h.listener.lastAudit(AuditEventId.LIVENESS_ACTIVE_TRIGGERED).attributes()
                    .get(AuditAttributes.REASON), w.id());
            List<ChallengeType> done = h.runActive(false);
            assertEquals(policy.minChallenges(), done.size(), w.id());
            assertEquals(policy.minChallenges(), count(h, AuditEventId.LIVENESS_CHALLENGE_ISSUED), w.id());
            assertEquals(SessionState.CAPTURE_READY, h.state(), w.id());
            assertTrue(h.captureAndBind().isPassed(), w.id());
            assertEquals(LivenessMethod.ACTIVE, h.result().method(), w.id());
            assertEquals(99, h.result().passiveScore().getAsInt(), w.id());
        }
    }

    @Test
    void faceSwitchBeforeAPromptFailsIntegrity() {
        SessionHarness h = escalated(registration());
        h.embedder.next = ScriptedModels.PERSON_B;
        h.awaitPrompt();
        assertEquals(SessionState.FAILED, h.state());
        assertTrue(h.result().reasonCodes().contains(ReasonCode.FACE_SWITCH));
        assertEquals(FailureReason.INTEGRITY, h.result().failureReason().get());
    }

    @Test
    void padDropDuringChallengesFails() {
        SessionHarness h = escalated(registration());
        h.pad.next = 0.05;
        h.awaitPrompt();
        h.frames(40, TestFaces.neutral());
        assertEquals(SessionState.FAILED, h.state());
        assertEquals(FailureReason.PAD, h.result().failureReason().get());
    }

    // ------------------------------------------------------------------ dim light (audit section 3.3, fix 17)

    /** Grey level of the synthetic dim face: above the TOO_DARK floor (50), below quality.low_light_brightness. */
    private static final double DIM = 65;

    private static SessionHarness passive(LivenessPolicy policy, int lowLightRetries, int attempt, double brightness,
            double live) {
        SessionHarness h = new SessionHarness(policy);
        h.lowLightRetries = lowLightRetries;
        h.attempt = attempt;
        h.start();
        h.pad.next = live;
        h.runPassive(TestFaces.neutral().brightness(brightness));
        return h;
    }

    @Test
    void dimLightPadFailureEndsUncountedWithLightingGuidance() {
        SessionHarness h = passive(registration(), 0, 1, DIM, 0.1);
        assertEquals(SessionState.FAILED, h.state());
        LivenessResult r = h.result();
        assertEquals(FailureReason.QUALITY, r.failureReason().get());
        assertEquals(Decision.FAIL_QUALITY, r.decision());
        assertFalse(r.failureReason().get().countsAsAttempt());
        assertTrue(r.isLowLightRetry());
        assertEquals(EnumSet.of(ReasonCode.LOW_LIGHT), r.reasonCodes());
        assertEquals(10, r.passiveScore().getAsInt(), "the low score stays in the result and the audit trail");
        assertTrue(r.challenges().isEmpty());
        assertNull(h.listener.lastAudit(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED));
        LivenessUiState ui = h.listener.lastUi();
        assertEquals(UiPhase.FAILED, ui.phase());
        assertEquals(MessageKeys.FAIL_LIVENESS, ui.messageKey());
        assertEquals(MessageKeys.GUIDE_LIGHTING, ui.guidanceKeys().get(0));
        assertTrue(ui.isRetryAvailable());
    }

    /** The shipped defaults (always active, 30 passive frames) take the same path: the passive PAD check fails first. */
    @Test
    void dimLightShippedDefaultsEndUncounted() {
        SessionHarness h = passive(LivenessPolicy.defaults(Workflow.REGISTRATION), 0, 1, DIM, 0.03);
        assertEquals(FailureReason.QUALITY, h.result().failureReason().get());
        assertTrue(h.result().isLowLightRetry());
        assertEquals(3, h.result().passiveScore().getAsInt());
    }

    /** After three uncounted low-light results the fourth counts as a PAD failure; the allowance then starts again. */
    @Test
    void dimLightCountsAfterThreeUncountedInARow() {
        LivenessTransaction tx = new LivenessTransaction(registration());
        for (int i = 1; i <= 3; i++) {
            SessionHarness h = passive(registration(), tx.lowLightRetries(), tx.nextAttempt(), DIM, 0.1);
            assertEquals(FailureReason.QUALITY, h.result().failureReason().get(), "low-light result " + i);
            tx.record(h.result());
            assertEquals(i, tx.lowLightRetries());
            assertEquals(0, tx.attemptsUsed());
            assertFalse(tx.isSuspectedAttack());
        }
        SessionHarness fourth = passive(registration(), tx.lowLightRetries(), tx.nextAttempt(), DIM, 0.1);
        LivenessResult r = fourth.result();
        assertEquals(FailureReason.PAD, r.failureReason().get());
        assertEquals(EnumSet.of(ReasonCode.PAD_SCORE), r.reasonCodes());
        assertFalse(r.isLowLightRetry());
        assertEquals(MessageKeys.FAIL_PAD, fourth.listener.lastUi().messageKey());
        assertNotNull(fourth.listener.lastAudit(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED));
        tx.record(r);
        assertEquals(1, tx.attemptsUsed());
        assertEquals(0, tx.lowLightRetries(), "a counted failure starts the allowance again");
        assertTrue(tx.isSuspectedAttack());
        SessionHarness fifth = passive(registration(), tx.lowLightRetries(), tx.nextAttempt(), DIM, 0.1);
        assertEquals(2, fifth.result().attempt());
        assertTrue(fifth.result().isLowLightRetry());
    }

    /** A cancel or a device error between low-light results does not restart the allowance. */
    @Test
    void dimLightAllowanceSurvivesUncountedResults() {
        LivenessTransaction tx = new LivenessTransaction(registration());
        tx.record(passive(registration(), tx.lowLightRetries(), 1, DIM, 0.1).result());
        SessionHarness cancelled = new SessionHarness(registration()).start();
        cancelled.session.cancel();
        assertEquals(FailureReason.CANCELLED, cancelled.result().failureReason().get());
        tx.record(cancelled.result());
        tx.record(passive(registration(), tx.lowLightRetries(), 1, DIM, 0.1).result());
        assertEquals(2, tx.lowLightRetries());
        assertEquals(0, tx.attemptsUsed());
    }

    /** A caller that does not track low-light results never gets uncounted PAD failures. */
    @Test
    void dimLightNotTrackedCountsAsPad() {
        SessionHarness h = passive(registration(), -1, 1, DIM, 0.1);
        assertEquals(FailureReason.PAD, h.result().failureReason().get());
        assertEquals(MessageKeys.FAIL_PAD, h.listener.lastUi().messageKey());
        assertThrows(IllegalArgumentException.class, () -> io.mosip.liveness.core.api.SessionRequest.builder(
                registration(), SessionHarness.SUBJECT, SessionHarness.device(false, true)).lowLightRetries(-1));
    }

    /** Attacks in normal light, and at the calibrated level itself, keep the counted PAD failure and generic message. */
    @Test
    void dimLightNormalLightAttackDecisionUnchanged() {
        for (double brightness : new double[] {128, 75}) {
            SessionHarness h = passive(registration(), 0, 1, brightness, 0.1);
            LivenessResult r = h.result();
            assertEquals(FailureReason.PAD, r.failureReason().get(), "brightness " + brightness);
            assertEquals(EnumSet.of(ReasonCode.PAD_SCORE), r.reasonCodes());
            assertTrue(r.failureReason().get().countsAsAttempt());
            assertEquals(MessageKeys.FAIL_PAD, h.listener.lastUi().messageKey());
            assertEquals("PAD_SCORE", h.listener.lastAudit(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED).attributes()
                    .get(AuditAttributes.REASON));
        }
    }

    /** Only the passive PAD failure is affected: a genuine dim face that scores well passes as before. */
    @Test
    void dimLightGenuinePassUnaffected() {
        SessionHarness h = passive(registration(), 0, 1, DIM, 0.92);
        assertEquals(SessionState.CAPTURE_READY, h.state());
        assertTrue(h.captureAndBind().isPassed());
    }

    /** {@code passive.low_light_max_uncounted=0} in the signed parameters turns the rule off. */
    @Test
    void dimLightRuleOffWithZeroAllowance() {
        SessionHarness h = new SessionHarness(registration());
        h.params = EngineParameters.builder().set(EngineParameter.LOW_LIGHT_MAX_UNCOUNTED, 0).build();
        h.lowLightRetries = 0;
        h.start();
        h.pad.next = 0.1;
        h.runPassive(TestFaces.neutral().brightness(DIM));
        assertEquals(FailureReason.PAD, h.result().failureReason().get());
    }

    /** The allowance is for the passive decision only: a PAD drop during the challenges still counts. */
    @Test
    void dimLightPadDropDuringChallengesStillCounts() {
        SessionHarness h = new SessionHarness(registration());
        h.lowLightRetries = 0;
        h.start();
        h.pad.next = 0.6;
        h.runPassive(TestFaces.neutral().brightness(DIM));
        assertEquals(SessionState.CHALLENGE_WAIT, h.state());
        h.pad.next = 0.05;
        for (int i = 0; i < 80 && !h.state().isTerminal(); i++) {
            h.frame(TestFaces.neutral().brightness(DIM));
        }
        assertEquals(FailureReason.PAD, h.result().failureReason().get());
    }

    @Test
    void dimLightCalibratedLevelLiesAboveTheTooDarkFloor() {
        assertTrue(EngineParameter.LOW_LIGHT_BRIGHTNESS.defaultValue() > EngineParameter.MIN_BRIGHTNESS.defaultValue());
        assertEquals(3, (int) EngineParameter.LOW_LIGHT_MAX_UNCOUNTED.defaultValue());
    }

    // ------------------------------------------------------------------ capture frame (appendix A.1 task 5)

    /** A policy with one challenge of the given type and no head-turn requirement, prompted and answered. */
    private static SessionHarness answered(ChallengeType type) {
        LivenessPolicy policy = registration().toBuilder().challengeTypes(EnumSet.of(type)).minChallenges(1).build();
        SessionHarness h = new SessionHarness(policy);
        h.params = EngineParameters.builder().set(EngineParameter.PLANARITY_ENABLED, 0).build();
        h.start();
        h.pad.next = 0.6;
        h.runPassive(TestFaces.neutral());
        assertEquals(SessionState.CHALLENGE_WAIT, h.state());
        h.pad.next = 0.8;
        assertEquals(type, h.awaitPrompt());
        h.frames(8, TestFaces.neutral());
        h.frames(10, TestFaces.acting(type));
        assertEquals(SessionState.CHALLENGE_PASSED, h.state());
        return h;
    }

    /** The stored frame is never the smile: the session waits for a neutral face after the last challenge. */
    @Test
    void neutralCaptureFrameIsNeverTheChallengeExpression() {
        SessionHarness h = answered(ChallengeType.SMILE);
        h.frames(40, TestFaces.acting(ChallengeType.SMILE));
        assertEquals(SessionState.CHALLENGE_PASSED, h.state(), "still smiling: no capture yet");
        assertFalse(h.session.passToken().isPresent());
        assertEquals(MessageKeys.FEEDBACK_LOOK_STRAIGHT, h.listener.lastUi().messageKey());
        assertEquals(UiPhase.HOLD_STILL, h.listener.lastUi().phase());
        long lastSmile = h.lastSequence();
        h.frames(10, TestFaces.neutral());
        assertEquals(SessionState.CAPTURE_READY, h.state());
        assertTrue(h.session.captureFrame().get().sequence() > lastSmile, "capture frame taken after the smile");
        assertEquals(MessageKeys.FEEDBACK_HOLD_STILL, h.listener.lastUi().messageKey());
        assertTrue(h.captureAndBind().isPassed());
        assertEquals("SMILE:OK", h.result().challengeEvidence());
    }

    /** Closed eyes, a head beyond the quality gate's yaw and a smile are all refused; a gate-admitted pose is kept. */
    @Test
    void neutralCaptureFrameNeedsOpenEyesAndAPoseInsideTheQualityGate() {
        SessionHarness h = answered(ChallengeType.SMILE);
        h.frames(30, TestFaces.neutral().ear(0.08));
        h.frames(30, TestFaces.neutral().yaw(18));
        assertEquals(SessionState.CHALLENGE_PASSED, h.state());
        assertEquals(MessageKeys.FEEDBACK_LOOK_STRAIGHT, h.listener.lastUi().messageKey());
        assertEquals(Arrays.asList(MessageKeys.GUIDE_LOOK_CAMERA), h.listener.lastUi().guidanceKeys(),
                "a head beyond the gate gets the look-at-the-camera guidance");
        long lastRefused = h.lastSequence();
        h.frames(10, TestFaces.neutral().yaw(12).pitch(16));
        assertEquals(SessionState.CAPTURE_READY, h.state(), "yaw 12 and pitch 16 lie inside the quality gate");
        assertTrue(h.session.captureFrame().get().sequence() > lastRefused);
    }

    /** No neutral face within the challenge timeout after the last challenge: a counted neutral-face timeout. */
    @Test
    void neutralCaptureFrameTimesOutAsACountedTimeout() {
        SessionHarness h = answered(ChallengeType.SMILE);
        h.frames(200, TestFaces.acting(ChallengeType.SMILE));
        assertEquals(SessionState.FAILED, h.state());
        LivenessResult r = h.result();
        assertEquals(FailureReason.TIMEOUT, r.failureReason().get());
        assertTrue(r.reasonCodes().contains(ReasonCode.NEUTRAL_TIMEOUT));
        assertEquals("SMILE:OK", r.challengeEvidence(), "the answered challenge stays recorded");
        assertEquals("CHALLENGE_PASSED",
                h.listener.lastAudit(AuditEventId.LIVENESS_TIMEOUT).attributes().get(AuditAttributes.PHASE));
        assertTrue(h.listener.lastUi().guidanceKeys().contains(MessageKeys.ERROR_TIMEOUT));
        assertTrue(h.listener.lastUi().guidanceKeys().contains(MessageKeys.GUIDE_LOOK_CAMERA));
    }

    /** A passive pass keeps an open-eyed frame even when a later blink frame is more frontal. */
    @Test
    void neutralCaptureFramePassiveSkipsABlink() {
        SessionHarness h = new SessionHarness(registration()).start();
        h.pad.next = 0.92;
        h.frames(5, TestFaces.neutral().yaw(8));
        long lastOpen = h.lastSequence();
        h.runPassive(TestFaces.neutral().ear(0.08));
        assertEquals(SessionState.CAPTURE_READY, h.state());
        assertTrue(h.session.captureFrame().get().sequence() <= lastOpen, "the blink frame is not stored");
    }

    // ------------------------------------------------------------------ multiple faces (appendix A.1 task 9)

    @Test
    void multiFaceDuringChallengeEndsNotCounted() {
        SessionHarness h = escalated(registration());
        assertNotNull(h.awaitPrompt());
        DetectedFace subject = TestFaces.neutral().build();
        DetectedFace other = TestFaces.neutral().shiftX(220).build();
        for (int i = 0; i < 3; i++) {
            h.frameWith(subject, other);
        }
        assertEquals(SessionState.FAILED, h.state());
        LivenessResult r = h.result();
        assertEquals(FailureReason.QUALITY, r.failureReason().get());
        assertEquals(Decision.FAIL_QUALITY, r.decision());
        assertTrue(r.reasonCodes().contains(ReasonCode.MULTI_FACE));
        assertEquals(ChallengeOutcome.CANCELLED, r.challenges().get(0).outcome());
        assertNull(h.listener.lastAudit(AuditEventId.LIVENESS_TIMEOUT));
        LivenessUiState ui = h.listener.lastUi();
        assertEquals(MessageKeys.ERROR_MULTIPLE_FACES, ui.messageKey());
        assertTrue(ui.guidanceKeys().contains(MessageKeys.GUIDE_FACE_VISIBLE));
        assertTrue(ui.isRetryAvailable());
        LivenessTransaction tx = new LivenessTransaction(registration());
        tx.record(r);
        assertEquals(0, tx.attemptsUsed(), "not counted");
        assertFalse(tx.isSuspectedAttack());
    }

    @Test
    void multiFaceDuringChallengeWaitAlsoEnds() {
        SessionHarness h = escalated(registration());
        DetectedFace subject = TestFaces.neutral().build();
        DetectedFace other = TestFaces.neutral().shiftX(-220).build();
        for (int i = 0; i < 3 && !h.state().isTerminal(); i++) {
            h.frameWith(subject, other);
        }
        assertEquals(FailureReason.QUALITY, h.result().failureReason().get());
        assertTrue(h.result().reasonCodes().contains(ReasonCode.MULTI_FACE));
    }

    /** One or two frames with a second face (a detector glitch) do not end the challenge. */
    @Test
    void multiFaceDuringChallengeIgnoresAShortGlitch() {
        SessionHarness h = escalated(registration());
        ChallengeType asked = h.awaitPrompt();
        assertNotNull(asked);
        DetectedFace subject = TestFaces.neutral().build();
        DetectedFace other = TestFaces.neutral().shiftX(220).build();
        h.frameWith(subject, other);
        h.frameWith(subject, other);
        h.frame(TestFaces.neutral());
        h.frameWith(subject, other);
        h.frameWith(subject, other);
        assertEquals(SessionState.CHALLENGE_OBSERVE, h.state());
    }

    // ------------------------------------------------------------------ neutral pitch limit (audit section 4.3)

    @Test
    void neutralPitchLimitMatchesTheQualityGate() {
        assertEquals(EngineParameter.MAX_PITCH_DEG.defaultValue(), EngineParameter.NEUTRAL_MAX_PITCH_DEG.defaultValue());
        assertEquals(20, (int) EngineParameter.NEUTRAL_MAX_PITCH_DEG.defaultValue());
    }

    /** A face tilted 16 degrees (camera above or below eye level) passes the gate and now also gets its prompt. */
    @Test
    void neutralPitchInsideTheQualityGateGetsThePrompt() {
        SessionHarness h = new SessionHarness(registration()).start();
        h.pad.next = 0.6;
        h.runPassive(TestFaces.neutral().pitch(16));
        assertEquals(SessionState.CHALLENGE_WAIT, h.state());
        h.pad.next = 0.8;
        for (int i = 0; i < 200 && h.state() == SessionState.CHALLENGE_WAIT; i++) {
            h.frame(TestFaces.neutral().pitch(16));
        }
        assertEquals(SessionState.CHALLENGE_OBSERVE, h.state());
    }

    /** Beyond the gate's pitch the face is not neutral: no prompt, and the neutral-face timeout follows. */
    @Test
    void neutralPitchBeyondTheQualityGateIsNotNeutral() {
        SessionHarness h = escalated(registration());
        h.frames(200, TestFaces.neutral().pitch(22));
        assertEquals(SessionState.FAILED, h.state());
        assertTrue(h.result().reasonCodes().contains(ReasonCode.NEUTRAL_TIMEOUT));
        assertEquals(0, count(h, AuditEventId.LIVENESS_CHALLENGE_ISSUED));
    }

    static SessionHarness escalated(LivenessPolicy policy) {
        return escalated(policy, 1);
    }

    static SessionHarness escalated(LivenessPolicy policy, int attempt) {
        SessionHarness h = new SessionHarness(policy);
        h.attempt = attempt;
        h.seed = 1000 + attempt;
        h.start();
        h.pad.next = 0.6;
        h.runPassive(TestFaces.neutral());
        assertEquals(SessionState.CHALLENGE_WAIT, h.state());
        h.pad.next = 0.8;
        return h;
    }

    private static int count(SessionHarness h, AuditEventId id) {
        int n = 0;
        for (AuditEventId e : h.listener.auditIds()) {
            if (e == id) {
                n++;
            }
        }
        return n;
    }

    private static boolean hasGuidance(SessionHarness h, String key) {
        return h.listener.ui.stream().anyMatch(s -> s.guidanceKeys().contains(key));
    }
}
