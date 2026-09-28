package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.NoStreamPolicy;
import io.mosip.liveness.core.config.PolicyMode;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.liveness.core.vision.AnalyzerFeature;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-CORE-SM-01..20: transitions driven by the fake clock, device events, cancel and engine errors. */
class SessionEdgeCaseTest {

    private static final LivenessPolicy REG = LivenessPolicy.defaults(Workflow.REGISTRATION);

    private static SessionHarness started() {
        return new SessionHarness(REG).start();
    }

    @Test
    void callsBeforeStartAreIgnored() {
        SessionHarness h = new SessionHarness(REG).open();
        h.frame(TestFaces.neutral());
        h.session.tick();
        h.session.onError(DeviceError.of(DeviceErrorCode.STALLED, "x"));
        h.session.onEnd(EndReason.END_OF_STREAM);
        h.session.captureStarted();
        assertEquals(SessionState.IDLE, h.state());
        assertFalse(h.session.passToken().isPresent());
        assertFalse(h.session.result().isPresent());
        assertEquals(32, h.session.sessionId().length());
        h.start();
        h.start();
        assertEquals(SessionState.OPENING, h.state());
        assertEquals(AuditEventId.LIVENESS_SESSION_STARTED, h.listener.audits.get(0).eventId());
        assertEquals("1", h.listener.audits.get(0).attributes().get(AuditAttributes.ATTEMPT));
    }

    @Test
    void noFirstFrameIsADeviceError() {
        SessionHarness h = started();
        h.tick(2_900);
        assertEquals(SessionState.OPENING, h.state());
        h.tick(200);
        assertEquals(SessionState.FAILED, h.state());
        LivenessResult r = h.result();
        assertEquals(FailureReason.DEVICE, r.failureReason().get());
        assertEquals(DeviceErrorCode.FIRST_FRAME_TIMEOUT, r.deviceError().get());
        assertEquals("FIRST_FRAME_TIMEOUT",
                h.listener.lastAudit(AuditEventId.LIVENESS_DEVICE_ERROR).attributes().get(AuditAttributes.ERROR_CODE));
        LivenessUiState ui = h.listener.lastUi();
        assertEquals(UiPhase.DEVICE_ERROR, ui.phase());
        assertEquals(MessageKeys.ERROR_DEVICE_UNAVAILABLE, ui.messageKey());
        assertTrue(ui.isRetryAvailable());
    }

    @Test
    void streamErrorsBeforeCaptureReadyFailAsDevice() {
        SessionHarness h = started();
        h.frame(TestFaces.neutral());
        h.session.onError(DeviceError.of(DeviceErrorCode.DISCONNECTED, "reset"));
        assertEquals(DeviceErrorCode.DISCONNECTED, h.result().deviceError().get());
        assertEquals(MessageKeys.ERROR_DEVICE_DISCONNECTED, h.listener.lastUi().messageKey());

        SessionHarness end = started();
        end.frames(3, TestFaces.neutral());
        end.session.onEnd(EndReason.END_OF_STREAM);
        assertEquals(DeviceErrorCode.DISCONNECTED, end.result().deviceError().get());

        SessionHarness timeout = started();
        timeout.session.onEnd(EndReason.STREAM_TIMEOUT);
        assertEquals(DeviceErrorCode.STALLED, timeout.result().deviceError().get());
    }

    @Test
    void streamEndAfterCaptureReadyIsNormal() {
        SessionHarness h = started();
        h.runPassive(TestFaces.neutral());
        h.session.onEnd(EndReason.CLOSED);
        h.session.onError(DeviceError.of(DeviceErrorCode.STALLED, "late"));
        h.frame(TestFaces.neutral());
        assertEquals(SessionState.CAPTURE_READY, h.state());
        assertTrue(h.captureAndBind().isPassed());
    }

    @Test
    void stalledStreamDuringPassiveIsADeviceError() {
        SessionHarness h = started();
        h.frames(3, TestFaces.neutral());
        h.tick(1_500);
        assertEquals(SessionState.ACQUIRING, h.state());
        h.tick(600);
        assertEquals(DeviceErrorCode.STALLED, h.result().deviceError().get());
        assertEquals(MessageKeys.ERROR_FRAMES_INVALID, h.listener.lastUi().messageKey());
    }

    @Test
    void noFaceUntilThePassiveTimeoutIsAQualityFailure() {
        SessionHarness h = started();
        for (int i = 0; i < 90 && !h.state().isTerminal(); i++) {
            h.frameWith();
        }
        LivenessResult r = h.result();
        assertEquals(FailureReason.QUALITY, r.failureReason().get());
        assertTrue(r.reasonCodes().contains(ReasonCode.NO_FACE));
        assertTrue(r.reasonCodes().contains(ReasonCode.PASSIVE_TIMEOUT));
        assertEquals(MessageKeys.ERROR_NO_FACE, h.listener.lastUi().messageKey());
        assertTrue(h.listener.sawUi(MessageKeys.ERROR_NO_FACE), "positioning guidance was shown");
        assertEquals("ACQUIRING",
                h.listener.lastAudit(AuditEventId.LIVENESS_TIMEOUT).attributes().get(AuditAttributes.PHASE));
    }

    @Test
    void twoFacesShowTheMultipleFacesMessage() {
        SessionHarness h = started();
        for (int i = 0; i < 90 && !h.state().isTerminal(); i++) {
            h.frameWith(TestFaces.neutral().build(), TestFaces.neutral().shiftX(150).build());
        }
        assertTrue(h.listener.sawUi(MessageKeys.ERROR_MULTIPLE_FACES));
        assertTrue(h.result().reasonCodes().contains(ReasonCode.MULTI_FACE));
        assertEquals(MessageKeys.ERROR_MULTIPLE_FACES, h.listener.lastUi().messageKey());
    }

    @Test
    void badFramesInPassiveShowGuidanceButDoNotReset() {
        SessionHarness h = started();
        h.frames(5, TestFaces.neutral());
        assertEquals(SessionState.PASSIVE, h.state());
        h.frame(TestFaces.neutral().brightness(20));
        assertTrue(h.listener.lastUi().guidanceKeys().contains(MessageKeys.GUIDE_LIGHTING));
        h.frames(3, TestFaces.neutral());
        assertEquals(SessionState.CAPTURE_READY, h.state());
    }

    @Test
    void timestampRegressionFailsIntegrity() {
        SessionHarness h = started();
        h.frames(2, TestFaces.neutral());
        FaceFrame old = FaceFrame.builder().data(h.noise()).format(PixelFormat.GRAY8).size(64, 48)
                .monotonicNanos(h.clock.monotonicNanos() - 1_000_000L).sequence(0).sourceId("x").build();
        h.session.onFrame(old);
        assertTrue(h.result().reasonCodes().contains(ReasonCode.TIMESTAMP_REGRESSION));
        assertEquals(MessageKeys.FAIL_PAD, h.listener.lastUi().messageKey());
    }

    @Test
    void frameRateBelowTheSbiMinimumFails() {
        SessionHarness h = new SessionHarness(REG);
        h.frameIntervalMs = 450;
        h.start();
        h.runPassive(TestFaces.neutral());
        assertTrue(h.result().reasonCodes().contains(ReasonCode.INVALID_FRAMES));
        assertEquals(FailureReason.INTEGRITY, h.result().failureReason().get());
    }

    @Test
    void corruptFramesFailAfterTheLimit() {
        SessionHarness h = started();
        for (int i = 0; i < 12 && !h.state().isTerminal(); i++) {
            h.clock.advanceMs(50);
            FaceFrame bad = FaceFrame.builder().data(new byte[] {0, 1, 2}).format(PixelFormat.JPEG)
                    .monotonicNanos(h.clock.monotonicNanos()).sequence(i).sourceId("x").build();
            h.session.onFrame(bad);
        }
        assertEquals(SessionState.FAILED, h.state());
        assertTrue(h.result().reasonCodes().contains(ReasonCode.INVALID_FRAMES));
    }

    @Test
    void jpegFramesAreDecodedAndLoopsDetected() {
        SessionHarness h = started();
        h.pad.next = 0.6;
        List<byte[]> clip = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            byte[] j = h.noise();
            j[0] = (byte) 0xFF;
            clip.add(j);
        }
        for (int i = 0; i < 40 && !h.state().isTerminal(); i++) {
            h.clock.advanceMs(50);
            h.analyzer.faces(TestFaces.neutral().build());
            h.session.onFrame(FaceFrame.builder().data(clip.get(i % 8).clone()).format(PixelFormat.JPEG)
                    .monotonicNanos(h.clock.monotonicNanos()).sequence(i).sourceId("x").build());
        }
        // The passive decision (frame 8) escalates; the repeated run is found at frame 14, during the challenges.
        assertTrue(h.result().reasonCodes().contains(ReasonCode.LOOP), h.result().toString());
        assertEquals(FailureReason.INTEGRITY, h.result().failureReason().get());
    }

    @Test
    void frameSamplingSkipsFrames() {
        SessionHarness h = new SessionHarness(REG.toBuilder().frameSampleIntervalMs(100).build()).start();
        h.frames(16, TestFaces.neutral());
        assertEquals(SessionState.CAPTURE_READY, h.state());
        assertEquals(8, h.pad.calls, "every second frame analysed");
    }

    @Test
    void analyserFailureEndsTheSessionAsEngineError() {
        SessionHarness h = started();
        h.frame(TestFaces.neutral());
        h.analyzer.failure = new EngineException(EngineErrorCode.MODEL_INTEGRITY, "hash mismatch");
        h.frame(TestFaces.neutral());
        LivenessResult r = h.result();
        assertEquals(FailureReason.ENGINE, r.failureReason().get());
        assertEquals(EngineErrorCode.MODEL_INTEGRITY, r.engineError().get());
        assertEquals("EngineException",
                h.listener.lastAudit(AuditEventId.LIVENESS_ENGINE_ERROR).attributes().get(AuditAttributes.ERROR_CLASS));
        assertNotNull(h.listener.lastAudit(AuditEventId.LIVENESS_MODEL_INTEGRITY_FAILED));
        assertEquals(MessageKeys.ERROR_ENGINE, h.listener.lastUi().messageKey());

        SessionHarness crash = started();
        crash.analyzer.crash = new IllegalStateException("bug");
        crash.frame(TestFaces.neutral());
        assertEquals(EngineErrorCode.INTERNAL, crash.result().engineError().get());
    }

    @Test
    void slowInferenceTripsTheWatchdog() {
        SessionHarness h = started();
        h.analyzer.delayMs = 600;
        h.frames(3, TestFaces.neutral());
        assertEquals(EngineErrorCode.INFERENCE_TIMEOUT, h.result().engineError().get());
        assertEquals(600, h.metrics.snapshot().maxFrameMs(), "frame processing time is measured");
        assertEquals(3, h.metrics.snapshot().counter(LivenessMetrics.FRAMES_ANALYSED));
    }

    @Test
    void policyOffSkipsLivenessButStillBinds() {
        SessionHarness h = new SessionHarness(LivenessPolicy.defaults(Workflow.EXCEPTION_PHOTO)).start();
        assertEquals(SessionState.CAPTURE_READY, h.state());
        assertFalse(h.session.captureFrame().isPresent());
        assertTrue(h.captureAndBind().isPassed());
        assertEquals(LivenessMethod.NONE, h.result().method());
        assertEquals(0, h.analyzer.calls, "no image check without liveness");
    }

    @Test
    void deviceWithoutStream() {
        SessionHarness blocked = new SessionHarness(REG);
        blocked.device = SessionHarness.device(false, false);
        blocked.start();
        assertEquals(DeviceErrorCode.NO_STREAM, blocked.result().deviceError().get());
        assertEquals(MessageKeys.ERROR_DEVICE_NO_STREAM, blocked.listener.lastUi().messageKey());

        SessionHarness captureOnly = new SessionHarness(REG.toBuilder().noStreamPolicy(NoStreamPolicy.CAPTURE_ONLY).build());
        captureOnly.device = SessionHarness.device(false, false);
        captureOnly.start();
        assertEquals(SessionState.CAPTURE_READY, captureOnly.state());
        assertTrue(captureOnly.captureAndBind().isPassed());
        assertEquals(LivenessMethod.CAPTURE_ONLY, captureOnly.result().method());
    }

    @Test
    void alwaysActiveWithoutUsableChallengesIsAnEngineError() {
        SessionHarness h = new SessionHarness(REG.toBuilder().mode(PolicyMode.ALWAYS_ACTIVE).build());
        h.analyzer.features = EnumSet.of(AnalyzerFeature.QUALITY);
        h.start();
        h.runPassive(TestFaces.neutral());
        assertEquals(EngineErrorCode.UNSUPPORTED, h.result().engineError().get());
    }

    @Test
    void cancelFromAnotherThreadTakesEffectOnTheNextCall() throws Exception {
        SessionHarness h = started();
        h.frames(2, TestFaces.neutral());
        Thread t = new Thread(h.session::cancel);
        t.start();
        t.join();
        assertEquals(SessionState.ACQUIRING, h.state());
        h.session.tick();
        assertEquals(SessionState.CANCELLED, h.state());
        assertEquals(FailureReason.CANCELLED, h.result().failureReason().get());
        assertTrue(h.result().reasonCodes().contains(ReasonCode.CANCELLED_BY_USER));
        assertEquals(UiPhase.CANCELLED, h.listener.lastUi().phase());
        h.session.cancel();
        assertEquals(1, h.listener.results.size(), "no effect once terminal");
    }

    @Test
    void cancelOnTheOwningThreadAndFromACallback() {
        SessionHarness h = started();
        h.session.cancel();
        assertEquals(SessionState.CANCELLED, h.state());

        SessionHarness cb = new SessionHarness(REG);
        cb.open();
        RecordingListener inner = cb.listener;
        cb.session = null;
        io.mosip.liveness.core.api.LivenessSession[] holder = new io.mosip.liveness.core.api.LivenessSession[1];
        io.mosip.liveness.core.api.LivenessListener cancelling = new io.mosip.liveness.core.api.LivenessListener() {
            @Override
            public void onUi(LivenessUiState state) {
                inner.onUi(state);
                if (state.phase() == UiPhase.POSITIONING) {
                    holder[0].cancel();
                }
            }

            @Override
            public void onAudit(io.mosip.liveness.core.audit.LivenessAuditEvent event) {
                inner.onAudit(event);
            }
        };
        try {
            holder[0] = cb.engine.open(SessionRequest.builder(REG, SessionHarness.SUBJECT, cb.device).clock(cb.clock)
                    .random(new SeededRandom(9)).build(), cancelling);
        } catch (EngineException e) {
            throw new IllegalStateException(e);
        }
        cb.session = holder[0];
        cb.start();
        cb.frame(TestFaces.neutral());
        assertEquals(SessionState.CANCELLED, cb.state(), "a cancel from a callback applies when the call returns");
    }

    @Test
    void closeCancelsAndWipesTheCaptureFrame() {
        SessionHarness h = started();
        h.runPassive(TestFaces.neutral());
        FaceFrame kept = h.session.captureFrame().get();
        byte[] data = kept.data();
        assertTrue(nonZero(data));
        h.session.close();
        assertEquals(SessionState.CANCELLED, h.state());
        assertFalse(nonZero(data), "frame buffer overwritten");
        h.session.close();
        assertFalse(h.session.captureFrame().isPresent());

        SessionHarness idle = new SessionHarness(REG).open();
        idle.session.close();
        assertEquals(SessionState.CANCELLED, idle.state());
        assertTrue(idle.session.result().isPresent());
    }

    private static boolean nonZero(byte[] data) {
        for (byte b : data) {
            if (b != 0) {
                return true;
            }
        }
        return false;
    }

    @Test
    void captureFailureAndTokenExpiry() {
        SessionHarness failed = started();
        failed.runPassive(TestFaces.neutral());
        failed.session.captureStarted();
        failed.session.captureFailed(DeviceError.of(DeviceErrorCode.NOT_READY, "device busy"));
        assertEquals(DeviceErrorCode.NOT_READY, failed.result().deviceError().get());

        SessionHarness nothing = started();
        nothing.runPassive(TestFaces.neutral());
        nothing.session.captureFailed(null);
        assertEquals(DeviceErrorCode.CAPTURE_FAILED, nothing.result().deviceError().get());

        SessionHarness expired = started();
        expired.runPassive(TestFaces.neutral());
        expired.tick(14_000);
        assertEquals(SessionState.CAPTURE_READY, expired.state());
        expired.tick(2_000);
        assertEquals(DeviceErrorCode.CAPTURE_TIMEOUT, expired.result().deviceError().get());
    }

    @Test
    void metricsAreNotRecordedWhenDisabled() {
        SessionHarness h = new SessionHarness(REG.toBuilder().metricsEnabled(false).build()).start();
        h.runPassive(TestFaces.neutral());
        h.captureAndBind();
        assertEquals(0, h.metrics.snapshot().counter(LivenessMetrics.SESSIONS));
        SessionHarness on = started();
        on.runPassive(TestFaces.neutral());
        on.captureAndBind();
        assertEquals(1, on.metrics.snapshot().counter(LivenessMetrics.PASSES));
    }

    @Test
    void trackLossDuringChallengesWithoutAnEmbedderFails() {
        SessionHarness h = new SessionHarness(REG);
        h.useEmbedder = false;
        h.start();
        h.pad.next = 0.6;
        h.runPassive(TestFaces.neutral());
        assertEquals(SessionState.CHALLENGE_WAIT, h.state());
        h.frame(TestFaces.neutral().shiftX(-250));
        assertTrue(h.result().reasonCodes().contains(ReasonCode.TRACKING_LOST));
    }

    @Test
    void faceSwitchAfterTrackLossIsCaughtByTheEmbedder() {
        SessionHarness h = SessionScenarioTest.escalated(REG);
        h.embedder.next = ScriptedModels.PERSON_B;
        h.frame(TestFaces.neutral().shiftX(-250));
        assertTrue(h.result().reasonCodes().contains(ReasonCode.FACE_SWITCH));

        SessionHarness same = SessionScenarioTest.escalated(REG);
        same.frame(TestFaces.neutral().shiftX(-250));
        same.frame(TestFaces.neutral().shiftX(-250));
        assertFalse(same.state().isTerminal(), "the same person may move");
    }

    @Test
    void trackLossDuringPassiveRestartsCollection() {
        SessionHarness h = started();
        h.frames(6, TestFaces.neutral());
        int calls = h.pad.calls;
        h.frame(TestFaces.neutral().shiftX(-250));
        assertEquals(SessionState.PASSIVE, h.state());
        h.frames(2, TestFaces.neutral().shiftX(-250));
        assertEquals(SessionState.PASSIVE, h.state(), "scores collected before the jump were discarded");
        h.frames(5, TestFaces.neutral().shiftX(-250));
        assertEquals(SessionState.CAPTURE_READY, h.state());
        assertTrue(h.pad.calls > calls);
    }

    @Test
    void analyserGetsThePreviousBoxAsHint() {
        SessionHarness h = started();
        h.frames(2, TestFaces.neutral());
        assertNotNull(h.analyzer.lastHint);
    }

    @Test
    void listenerExceptionsDoNotBreakTheSession() {
        SessionHarness h = new SessionHarness(REG);
        h.open();
        io.mosip.liveness.core.api.LivenessListener throwing = new io.mosip.liveness.core.api.LivenessListener() {
            @Override
            public void onUi(LivenessUiState state) {
                throw new IllegalStateException("ui");
            }

            @Override
            public void onAudit(io.mosip.liveness.core.audit.LivenessAuditEvent event) {
                throw new IllegalStateException("audit");
            }

            @Override
            public void onStateChanged(SessionState previous, SessionState current) {
                throw new IllegalStateException("state");
            }

            @Override
            public void onResult(LivenessResult result) {
                throw new IllegalStateException("result");
            }
        };
        try {
            h.session = h.engine.open(SessionRequest.builder(REG, SessionHarness.SUBJECT, h.device).clock(h.clock)
                    .random(new SeededRandom(1)).build(), throwing);
        } catch (EngineException e) {
            throw new IllegalStateException(e);
        }
        h.start();
        h.runPassive(TestFaces.neutral());
        assertTrue(h.captureAndBind().isPassed());
        assertEquals(SessionState.PASSED, h.state());
    }

    @Test
    void closedEngineRefusesNewSessions() {
        SessionHarness h = new SessionHarness(REG).open();
        assertTrue(h.engine.isReady());
        assertSame(h.metrics, h.engine.metrics());
        assertEquals(EngineParameters.defaults(), h.engine.parameters());
        assertEquals("mosip-onnx-default", h.engine.info().id());
        h.engine.close();
        assertFalse(h.engine.isReady());
        EngineException e = assertThrows(EngineException.class, () -> h.engine.open(
                SessionRequest.builder(REG, SessionHarness.SUBJECT, h.device).build(), new RecordingListener()));
        assertEquals(EngineErrorCode.NOT_READY, e.getErrorCode());
        assertThrows(NullPointerException.class, () -> DefaultLivenessEngine.builder(SessionHarness.engineInfo()).build());
    }
}
