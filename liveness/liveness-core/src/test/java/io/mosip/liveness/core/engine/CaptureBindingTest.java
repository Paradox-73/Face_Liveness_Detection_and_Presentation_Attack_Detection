package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.ChallengeEchoStatus;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.DevicePadDecision;
import io.mosip.liveness.core.device.DevicePadResult;
import io.mosip.liveness.core.device.PadMethod;
import io.mosip.liveness.core.ui.MessageKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-CORE-BND-01..03: stream-to-capture binding (design section 4.10). */
class CaptureBindingTest {

    private static final LivenessPolicy REG = TestPolicies.passiveFirst(Workflow.REGISTRATION);

    private static SessionHarness ready(LivenessPolicy policy, boolean padCapable) {
        SessionHarness h = new SessionHarness(policy);
        h.device = SessionHarness.device(padCapable, true);
        h.start();
        h.runPassive(TestFaces.neutral());
        assertEquals(SessionState.CAPTURE_READY, h.state());
        return h;
    }

    private static BindingVerdict bind(SessionHarness h, BindingRequest request) {
        h.analyzer.faces(TestFaces.neutral().build());
        return h.session.bind(request);
    }

    private static LivenessPassToken start(SessionHarness h) {
        LivenessPassToken t = h.session.passToken().get();
        h.session.captureStarted();
        assertEquals(SessionState.CAPTURING, h.state());
        assertEquals(t, h.session.passToken().get());
        return t;
    }

    @Test
    void tokenIsBoundToSessionWorkflowSubjectAndDevice() {
        SessionHarness h = ready(REG, false);
        LivenessPassToken t = h.session.passToken().get();
        assertEquals(h.session.sessionId(), t.sessionId());
        assertEquals(Workflow.REGISTRATION, t.workflow());
        assertEquals(SessionHarness.SUBJECT, t.subjectRef());
        assertEquals(SessionHarness.SERIAL, t.deviceSerial());
        assertEquals(32, t.nonce().length());
        assertEquals(15_000, t.expiresAt().toEpochMilli() - t.issuedAt().toEpochMilli());
    }

    @Test
    void missingTokenFails() {
        SessionHarness h = ready(REG, false);
        start(h);
        BindingVerdict v = bind(h, h.bindingRequest(null).build());
        assertEquals(FailureReason.BINDING, v.failureReason().get());
        assertTrue(v.reasonCodes().contains(ReasonCode.TOKEN_MISSING));
        assertEquals(SessionState.FAILED, h.state());
        assertEquals(MessageKeys.FAIL_PAD, h.listener.lastUi().messageKey());
        assertTrue(h.listener.lastAudit(AuditEventId.LIVENESS_BINDING_FAILED).attributes().get(AuditAttributes.REASON)
                .contains("TOKEN_MISSING"));
    }

    @Test
    void tokenIsSingleUse() {
        SessionHarness h = ready(REG, false);
        LivenessPassToken t = start(h);
        assertTrue(bind(h, h.bindingRequest(t).build()).isPassed());
        BindingVerdict again = bind(h, h.bindingRequest(t).build());
        assertTrue(again.reasonCodes().contains(ReasonCode.TOKEN_REUSED));
        assertEquals(SessionState.PASSED, h.state(), "a second capture does not change the result");
    }

    @Test
    void tokenOfAnotherSubjectDoesNotMatch() {
        SessionHarness h = ready(REG, false);
        LivenessPassToken t = start(h);
        LivenessPassToken forged = LivenessPassToken.builder().sessionId(t.sessionId()).workflow(t.workflow())
                .subjectRef("someone-else").deviceSerial(t.deviceSerial()).nonce(t.nonce()).issuedAt(t.issuedAt())
                .expiresAt(t.expiresAt()).build();
        assertTrue(bind(h, h.bindingRequest(forged).build()).reasonCodes().contains(ReasonCode.TOKEN_MISMATCH));
    }

    @Test
    void expiredTokenAndLateCaptureFail() {
        SessionHarness h = ready(REG, false);
        LivenessPassToken t = start(h);
        h.clock.advanceMs(16_000);
        BindingVerdict v = bind(h, h.bindingRequest(t).build());
        assertTrue(v.reasonCodes().contains(ReasonCode.TOKEN_EXPIRED));
        assertTrue(v.reasonCodes().contains(ReasonCode.OUTSIDE_TIME_WINDOW));
        assertTrue(v.gapMs().getAsLong() >= 16_000);
    }

    @Test
    void captureTimeBeforeThePassFails() {
        SessionHarness h = ready(REG, false);
        LivenessPassToken t = start(h);
        BindingRequest early = BindingRequest.builder(h.captureImage(), t.issuedAt().minusSeconds(5), SessionHarness.SERIAL)
                .token(t).build();
        assertTrue(bind(h, early).reasonCodes().contains(ReasonCode.OUTSIDE_TIME_WINDOW));
    }

    @Test
    void authWindowStartsWhenTheCaptureStarts() {
        SessionHarness h = ready(TestPolicies.passiveFirst(Workflow.PACKET_AUTH).toBuilder().passiveThreshold(80).build(),
                false);
        LivenessPassToken t = h.session.passToken().get();
        h.clock.advanceMs(1_400);
        h.session.captureStarted();
        BindingRequest early = BindingRequest.builder(h.captureImage(), t.issuedAt().plusMillis(200), SessionHarness.SERIAL)
                .token(t).build();
        BindingVerdict v = bind(h, early);
        assertEquals(-1_200, v.gapMs().getAsLong(), "gap measured from the start of the capture after device Ready");
        assertTrue(v.isPassed(), "1.2 s of device clock skew is tolerated: " + v);
    }

    @Test
    void otherDeviceFails() {
        SessionHarness h = ready(REG, false);
        LivenessPassToken t = start(h);
        BindingRequest other = BindingRequest.builder(h.captureImage(), h.clock.now(), "OTHER-SERIAL").token(t).build();
        assertTrue(bind(h, other).reasonCodes().contains(ReasonCode.DEVICE_MISMATCH));
    }

    @Test
    void padCapableDeviceMustEchoTheNonce() {
        SessionHarness ok = ready(REG, true);
        LivenessPassToken t = start(ok);
        BindingVerdict matched = bind(ok, ok.bindingRequest(t).echoedChallenge(t.nonce()).build());
        assertTrue(matched.isPassed());
        assertEquals(ChallengeEchoStatus.MATCHED, matched.challengeEcho());

        SessionHarness wrong = ready(REG, true);
        LivenessPassToken t2 = start(wrong);
        BindingVerdict mismatched = bind(wrong, wrong.bindingRequest(t2).echoedChallenge("another-nonce-value").build());
        assertEquals(ChallengeEchoStatus.MISMATCHED, mismatched.challengeEcho());
        assertTrue(mismatched.reasonCodes().contains(ReasonCode.CHALLENGE_ECHO_MISMATCH));
        assertEquals("false", wrong.listener.lastAudit(AuditEventId.LIVENESS_BINDING_FAILED).attributes()
                .get(AuditAttributes.CHALLENGE_ECHO_OK));

        SessionHarness missing = ready(REG, true);
        LivenessPassToken t3 = start(missing);
        assertEquals(ChallengeEchoStatus.MISSING, bind(missing, missing.bindingRequest(t3).build()).challengeEcho());
    }

    @Test
    void devicePadFailIsAPadFailure() {
        SessionHarness h = ready(REG, false);
        LivenessPassToken t = start(h);
        BindingVerdict v = bind(h, h.bindingRequest(t)
                .devicePad(DevicePadResult.of(DevicePadDecision.FAIL, PadMethod.PASSIVE, 12)).build());
        assertEquals(FailureReason.PAD, v.failureReason().get());
        assertTrue(v.reasonCodes().contains(ReasonCode.DEVICE_PAD));
        assertEquals("DEVICE_PAD", h.listener.lastAudit(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED).attributes()
                .get(AuditAttributes.REASON));
        assertEquals(MessageKeys.FAIL_PAD, h.listener.lastUi().messageKey());
        assertTrue(h.result().devicePad().get().isFail());
    }

    @Test
    void devicePadPassIsRecordedAsEvidence() {
        SessionHarness h = ready(REG, false);
        LivenessPassToken t = start(h);
        assertTrue(bind(h, h.bindingRequest(t)
                .devicePad(DevicePadResult.of(DevicePadDecision.PASS, PadMethod.PASSIVE, 97)).build()).isPassed());
        assertEquals("PASS", PacketEvidenceKeys.of(h.result()).get(PacketEvidenceKeys.DEVICE_PAD));
    }

    @Test
    void capturedImageChecks() {
        SessionHarness none = ready(REG, false);
        LivenessPassToken t = start(none);
        none.analyzer.faces();
        assertTrue(none.session.bind(none.bindingRequest(t).build()).reasonCodes().contains(ReasonCode.CAPTURE_NO_FACE));

        SessionHarness two = ready(REG, false);
        LivenessPassToken t2 = start(two);
        two.analyzer.faces(TestFaces.neutral().build(), TestFaces.neutral().shiftX(200).build());
        assertTrue(two.session.bind(two.bindingRequest(t2).build()).reasonCodes()
                .contains(ReasonCode.CAPTURE_MULTI_FACE));

        SessionHarness swapped = ready(REG, false);
        LivenessPassToken t3 = start(swapped);
        swapped.embedder.next = ScriptedModels.PERSON_B;
        BindingVerdict v = bind(swapped, swapped.bindingRequest(t3).build());
        assertTrue(v.reasonCodes().contains(ReasonCode.SIMILARITY_LOW));
        assertEquals(11, v.similarity().getAsInt());
        assertEquals("10-19", swapped.listener.lastAudit(AuditEventId.LIVENESS_BINDING_FAILED).attributes()
                .get(AuditAttributes.SIMILARITY_BUCKET));

        SessionHarness attack = ready(REG, false);
        LivenessPassToken t4 = start(attack);
        attack.pad.next = 0.1;
        BindingVerdict pad = bind(attack, attack.bindingRequest(t4).build());
        assertEquals(FailureReason.PAD, pad.failureReason().get());
        assertTrue(pad.reasonCodes().contains(ReasonCode.CAPTURE_PAD_SCORE));
        assertEquals(10, pad.capturePadScore().getAsInt());
    }

    @Test
    void bindOutsideCapturingChangesNothing() {
        SessionHarness h = new SessionHarness(REG).start();
        h.frames(3, TestFaces.neutral());
        BindingVerdict early = h.session.bind(h.bindingRequest(null).build());
        assertTrue(early.reasonCodes().contains(ReasonCode.TOKEN_MISSING));
        assertEquals(SessionState.ACQUIRING, h.state());
        h.runPassive(TestFaces.neutral());
        LivenessPassToken t = h.session.passToken().get();
        BindingVerdict notStarted = h.session.bind(h.bindingRequest(t).build());
        assertTrue(notStarted.reasonCodes().contains(ReasonCode.TOKEN_MISMATCH));
        assertEquals(SessionState.CAPTURE_READY, h.state());
        assertTrue(h.captureAndBind().isPassed());
    }

    @Test
    void engineFailureDuringBindingIsAnEngineError() {
        SessionHarness h = ready(REG, false);
        LivenessPassToken t = start(h);
        h.analyzer.failure = new EngineException(EngineErrorCode.INFERENCE, "oom");
        BindingVerdict v = h.session.bind(h.bindingRequest(t).build());
        assertTrue(v.reasonCodes().contains(ReasonCode.ENGINE_ERROR));
        assertEquals(FailureReason.ENGINE, h.result().failureReason().get());

        SessionHarness crash = ready(REG, false);
        LivenessPassToken t2 = start(crash);
        crash.analyzer.crash = new IllegalArgumentException("bad image");
        crash.session.bind(crash.bindingRequest(t2).build());
        assertEquals(EngineErrorCode.INTERNAL, crash.result().engineError().get());
    }

    @Test
    void withoutAnEmbedderSimilarityIsSkipped() {
        SessionHarness h = new SessionHarness(REG);
        h.useEmbedder = false;
        h.start();
        h.runPassive(TestFaces.neutral());
        BindingVerdict v = h.captureAndBind();
        assertTrue(v.isPassed());
        assertFalse(v.similarity().isPresent());
        assertFalse(h.result().bindingSimilarity().isPresent());
    }
}
