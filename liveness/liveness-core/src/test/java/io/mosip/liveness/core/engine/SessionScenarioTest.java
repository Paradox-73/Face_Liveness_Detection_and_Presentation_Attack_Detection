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
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.UiPhase;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
     * then always run the challenges, however high the passive score; supervisor workflows stay passive first.
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
        SessionHarness supervisor = new SessionHarness(LivenessPolicy.defaults(Workflow.PACKET_AUTH)).start();
        supervisor.pad.next = 0.99;
        supervisor.runPassive(TestFaces.neutral());
        assertEquals(SessionState.CAPTURE_READY, supervisor.state());
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
