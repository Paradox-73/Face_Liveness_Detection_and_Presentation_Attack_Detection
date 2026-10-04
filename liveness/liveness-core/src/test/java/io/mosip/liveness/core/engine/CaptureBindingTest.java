/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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

/** T-CORE-BND-01..03: stream-to-capture binding (docs/design/engine.md section 7). */
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

    /**
     * With an embedder configured, a session that built no reference face (no passive frame had landmarks) must not
     * skip the face check: binding fails instead of accepting a capture of an unknown face.
     */
    @Test
    void noReferenceWithEmbedderFailsBinding() {
        SessionHarness h = new SessionHarness(REG).start();
        h.runPassive(TestFaces.neutral().noLandmarks());
        assertEquals(SessionState.CAPTURE_READY, h.state());
        BindingVerdict v = h.captureAndBind();
        assertFalse(v.isPassed());
        assertEquals(FailureReason.BINDING, v.failureReason().get());
        assertTrue(v.reasonCodes().contains(ReasonCode.SIMILARITY_LOW), v.reasonCodes().toString());
        assertFalse(v.similarity().isPresent());
        assertEquals(SessionState.FAILED, h.state());
        assertEquals(FailureReason.BINDING, h.result().failureReason().get());
        assertEquals(MessageKeys.FAIL_PAD, h.listener.lastUi().messageKey());
        assertFalse(h.listener.lastUi().isOverrideAvailable());
    }

    /** Without an embedder there is no reference to miss: the session binds without a similarity, as before. */
    @Test
    void noReferenceWithoutEmbedderBindsWithoutSimilarity() {
        SessionHarness h = new SessionHarness(REG);
        h.useEmbedder = false;
        h.start();
        h.runPassive(TestFaces.neutral().yaw(15).pitch(20));
        assertEquals(SessionState.CAPTURE_READY, h.state());
        BindingVerdict v = h.captureAndBind();
        assertTrue(v.isPassed(), v.toString());
        assertFalse(v.similarity().isPresent());
    }

    /** A capture face without landmarks cannot be compared; with an embedder that fails binding too. */
    @Test
    void captureWithoutLandmarksFailsBindingWithEmbedder() {
        SessionHarness h = ready(REG, false);
        LivenessPassToken t = start(h);
        h.analyzer.faces(TestFaces.neutral().noLandmarks().build());
        BindingVerdict v = h.session.bind(h.bindingRequest(t).build());
        assertEquals(FailureReason.BINDING, v.failureReason().get());
        assertTrue(v.reasonCodes().contains(ReasonCode.SIMILARITY_LOW), v.reasonCodes().toString());
    }

    /**
     * Every passive frame is admitted by the quality gate (yaw 15 = {@code quality.max_yaw_deg}, pitch 20 = the
     * pitch limit) but none is inside the 10-degree neutral window: the reference comes from the most frontal of
     * them, so binding still compares the captured face.
     */
    @Test
    void referenceFromFrontalFramesWithinQualityGate() {
        SessionHarness same = new SessionHarness(REG).start();
        same.runPassive(TestFaces.neutral().yaw(15).pitch(20));
        assertEquals(SessionState.CAPTURE_READY, same.state());
        int referenceEmbeddings = same.embedder.calls;
        assertEquals(same.params.getInt(EngineParameter.REFERENCE_FRAMES), referenceEmbeddings,
                "the reference is built once, from identity.reference_frames frames");
        BindingVerdict v = same.captureAndBind();
        assertTrue(v.isPassed(), v.toString());
        assertEquals(100, v.similarity().getAsInt());
        assertEquals(100, same.result().bindingSimilarity().getAsInt());

        SessionHarness swapped = new SessionHarness(REG).start();
        swapped.runPassive(TestFaces.neutral().yaw(-15).pitch(-20));
        assertEquals(SessionState.CAPTURE_READY, swapped.state());
        LivenessPassToken t = start(swapped);
        swapped.embedder.next = ScriptedModels.PERSON_B;
        BindingVerdict other = bind(swapped, swapped.bindingRequest(t).build());
        assertEquals(FailureReason.BINDING, other.failureReason().get());
        assertTrue(other.reasonCodes().contains(ReasonCode.SIMILARITY_LOW));
        assertEquals(11, other.similarity().getAsInt());
    }
}
