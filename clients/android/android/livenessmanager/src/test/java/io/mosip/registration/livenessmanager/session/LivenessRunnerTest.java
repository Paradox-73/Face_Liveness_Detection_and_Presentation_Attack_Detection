/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.device.CaptureRequest;
import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.DevicePurpose;
import io.mosip.liveness.core.device.DeviceStatus;
import io.mosip.liveness.core.device.DeviceTransport;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceCaptureDevice;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.liveness.core.device.StreamStats;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.liveness.core.engine.LivenessTransaction;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.device.FaceIsoRecords;
import io.mosip.registration.livenessmanager.engine.EngineLoader;

/**
 * The Android runner on top of the engine's shared session driver: the signed capture is bound on the worker, the
 * counted failures, the suspected attack and the dim-light count of earlier screens continue, the blocked emulator
 * and engine failures end the attempt without a session, and each driver outcome becomes the
 * {@link LivenessCaptureResult} the screen and the app read. The engine is scripted; attempt accounting itself is
 * tested in liveness-core.
 */
public class LivenessRunnerTest {

    private static final long WAIT_S = 10;
    private static final String SERIAL = "FAKE-0001";
    private static final byte[] JP2 = {0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, (byte) 0x87,
            0x0A, 9, 8, 7, 6, 5, 4, 3, 2, 1};
    private static final LivenessPolicy RESIDENT = LivenessPolicy.defaults(Workflow.REGISTRATION).toBuilder()
            .maxRetries(3).onMaxFailure(OnMaxFailure.SUPERVISOR_OVERRIDE).build();

    private final ScriptedEngine engine = new ScriptedEngine();
    private final AtomicInteger engineLoads = new AtomicInteger();
    private final FakeDevice device = new FakeDevice();
    private final RecordingAudit audit = new RecordingAudit();
    private final Recorder recorder = new Recorder();
    private final List<LivenessRunner> runners = new ArrayList<>();
    private volatile EngineException engineFailure;

    @After
    public void closeRunners() {
        for (LivenessRunner r : runners) {
            r.close();
        }
    }

    private LivenessRunner runner(LivenessPolicy policy, String blocked, int prior, boolean attack) {
        return runner(policy, blocked, prior, attack, 0);
    }

    private LivenessRunner runner(LivenessPolicy policy, String blocked, int prior, boolean attack, int lowLight) {
        EngineLoader engines = new EngineLoader(() -> {
            engineLoads.incrementAndGet();
            EngineException e = engineFailure;
            if (e != null) {
                throw e;
            }
            return engine;
        });
        LivenessRunner r = new LivenessRunner(policy, "subject-1", engines, device, new FakeDecoder(), audit,
                DiagnosticSink.noop(), Clock.system(), blocked, prior, attack, lowLight, recorder);
        runners.add(r);
        return r;
    }

    private LivenessRunner runner(LivenessPolicy policy) {
        return runner(policy, null, 0, false);
    }

    @Test
    public void passIsTheBoundSignedCaptureOfTheSession() throws Exception {
        engine.then(Script.pass(3));
        LivenessRunner r = runner(RESIDENT);
        assertFalse("no retry before the first attempt", r.retry());
        r.start();

        LivenessCaptureResult result = recorder.next();
        assertTrue(result.toString(), result.isPass());
        assertEquals(Decision.PASS, result.decision());
        assertEquals("session-1", result.sessionId());
        assertEquals(0, result.attemptsUsed());
        assertEquals(3, result.maxAttempts());
        assertFalse(result.isRetryAllowed());
        assertFalse(result.isOverrideAllowed());
        assertArrayEquals("the ISO record the device signed", device.lastIso, result.faceIso());
        assertEquals("nonce-session-1-0123456789", result.tokenNonce());
        assertEquals("the device signed a copy of the session's capture frame", 99L, device.lastSourceSequence);
        assertArrayEquals("the copy is wiped after binding", new byte[4], device.lastSourceFrame.data());
        assertEquals(Optional.of(result), r.lastResult());
        assertTrue("frame times are kept for the diagnostic view", r.lastFrameTimes().startsWith("frames="));
        assertEquals("callbacks come from the worker", "liveness-worker", recorder.endedOn);
        assertFalse(r.retry());
    }

    @Test
    public void earlierScreensContinueTheTransaction() throws Exception {
        engine.then(Script.failAt(2, FailureReason.ACTIVE));
        LivenessRunner r = runner(RESIDENT, null, 2, false);
        r.start();

        LivenessCaptureResult result = recorder.next();
        assertEquals("the session ran as the last attempt", 3, engine.requests.get(0).attempt());
        assertEquals(3, result.attemptsUsed());
        assertFalse(result.isRetryAllowed());
        assertTrue("a failed challenge after two earlier failures offers the override", result.isOverrideAllowed());
        assertEquals(OnMaxFailure.SUPERVISOR_OVERRIDE, result.onMaxFailure());
        assertEquals("LV11 once", 1, audit.count(AuditEventId.LIVENESS_MAX_RETRIES));
        assertFalse(r.retry());
    }

    @Test
    public void earlierSuspectedAttackRulesOutTheOverride() throws Exception {
        engine.then(Script.failAt(2, FailureReason.ACTIVE));
        LivenessRunner r = runner(RESIDENT, null, 2, true);
        r.start();

        LivenessCaptureResult result = recorder.next();
        assertEquals(3, result.attemptsUsed());
        assertFalse("an attack on an earlier screen forbids the override", result.isOverrideAllowed());
    }

    @Test
    public void attackOnTheFirstScreenAloneIsCarriedWithoutCountedFailures() throws Exception {
        LivenessPolicy single = RESIDENT.toBuilder().maxRetries(1).build();
        engine.then(Script.failAt(2, FailureReason.TIMEOUT));
        LivenessRunner r = runner(single, null, 0, true);
        r.start();

        LivenessCaptureResult result = recorder.next();
        assertEquals(1, result.attemptsUsed());
        assertFalse(result.isOverrideAllowed());
    }

    @Test
    public void earlierSuspectedAttackNeverOffersTheOverride() throws Exception {
        engine.then(Script.failAt(2, FailureReason.TIMEOUT));
        LivenessRunner r = runner(RESIDENT, null, 0, true);
        r.start();

        for (int attempt = 1; attempt <= 3; attempt++) {
            LivenessCaptureResult result = recorder.next();
            assertEquals(attempt, result.attemptsUsed());
            assertFalse("attempt " + attempt + ": an attack on an earlier screen forbids the override",
                    result.isOverrideAllowed());
            if (attempt < 3) {
                assertTrue(r.retry());
            } else {
                assertFalse("the last attempt ends the transaction", result.isRetryAllowed());
                assertEquals(OnMaxFailure.SUPERVISOR_OVERRIDE, result.onMaxFailure());
            }
        }
    }

    @Test
    public void everySessionReceivesTheDimLightCount() throws Exception {
        engine.then(Script.pass(3));
        LivenessRunner r = runner(RESIDENT);
        r.start();

        LivenessCaptureResult result = recorder.next();
        assertTrue(result.isPass());
        assertEquals("the low-light rule is on: the first session gets 0, not LOW_LIGHT_NOT_TRACKED", 0,
                engine.requests.get(0).lowLightRetries());
        assertEquals(0, result.lowLightRetries());
    }

    @Test
    public void dimDimCloseReopenDimStaysUncountedAndTheNextDimCounts() throws Exception {
        engine.then(Script.dimFace(2, 3));
        LivenessRunner first = runner(RESIDENT);
        first.start();
        LivenessCaptureResult dim1 = recorder.next();
        assertTrue(first.retry());
        LivenessCaptureResult dim2 = recorder.next();
        first.close();

        assertLowLight(dim1, 1);
        assertLowLight(dim2, 2);
        assertEquals("dim-light results write no LV11", 0, audit.count(AuditEventId.LIVENESS_MAX_RETRIES));

        // The app opens a new screen with what the closed one reported.
        LivenessRunner second = runner(RESIDENT, null, dim2.attemptsUsed(), false, dim2.lowLightRetries());
        second.start();
        LivenessCaptureResult dim3 = recorder.next();
        assertEquals("the reopened screen continues the count", 2, engine.requests.get(2).lowLightRetries());
        assertLowLight(dim3, 3);

        assertTrue(second.retry());
        LivenessCaptureResult counted = recorder.next();
        assertEquals(3, engine.requests.get(3).lowLightRetries());
        assertEquals("the fourth dim try counts", FailureReason.PAD, counted.failureReason());
        assertEquals(1, counted.attemptsUsed());
        assertEquals("a counted failure resets the dim-light count", 0, counted.lowLightRetries());
        assertTrue(counted.isRetryAllowed());
        assertFalse(counted.reasonCodes().contains(ReasonCode.LOW_LIGHT.name()));
    }

    @Test
    public void dimLightCountAtTheLimitMakesTheFirstDimTryCount() throws Exception {
        engine.then(Script.dimFace(2, 3));
        LivenessRunner r = runner(RESIDENT, null, 0, false, 3);
        r.start();

        LivenessCaptureResult counted = recorder.next();
        assertEquals(3, engine.requests.get(0).lowLightRetries());
        assertEquals(FailureReason.PAD, counted.failureReason());
        assertEquals(1, counted.attemptsUsed());
        assertEquals(0, counted.lowLightRetries());
    }

    @Test
    public void priorDimLightCountOutsideItsRangeIsClamped() throws Exception {
        engine.then(Script.failAt(2, FailureReason.ACTIVE));
        LivenessRunner high = runner(RESIDENT, null, 0, false, 99);
        high.start();
        recorder.next();
        assertEquals(LivenessTransaction.maxPriorLowLightRetries(), engine.requests.get(0).lowLightRetries());
        LivenessRunner negative = runner(RESIDENT, null, 0, false, -4);
        negative.start();
        recorder.next();
        assertEquals(0, engine.requests.get(1).lowLightRetries());
    }

    @Test
    public void priorAttemptsOutsideTheLimitAreClamped() throws Exception {
        engine.then(Script.failAt(2, FailureReason.PAD));
        LivenessRunner r = runner(RESIDENT, null, 99, false);
        r.start();

        LivenessCaptureResult result = recorder.next();
        assertEquals(3, engine.requests.get(0).attempt());
        assertEquals(3, result.attemptsUsed());
        assertFalse("PAD failure: no override", result.isOverrideAllowed());
        assertEquals(Decision.FAIL_PAD, result.decision());
        assertEquals(Collections.singletonList(ReasonCode.PAD_SCORE.name()), result.reasonCodes());
    }

    @Test
    public void blockedDeviceEndsWithoutLoadingTheEngineOrARetry() throws Exception {
        LivenessRunner r = runner(RESIDENT, MessageKeys.ERROR_EMULATOR, 0, false);
        r.start();

        LivenessCaptureResult result = recorder.next();
        assertEquals(Decision.ERROR, result.decision());
        assertEquals(FailureReason.DEVICE, result.failureReason());
        assertEquals(MessageKeys.ERROR_EMULATOR, result.messageKey());
        assertNull("no session opened", result.sessionId());
        assertFalse(result.isRetryAllowed());
        assertEquals(0, result.attemptsUsed());
        assertEquals("the engine is not loaded", 0, engineLoads.get());
        assertTrue(engine.requests.isEmpty());
        assertEquals("LV12", 1, audit.count(AuditEventId.LIVENESS_DEVICE_ERROR));
        LivenessUiState last = recorder.lastState();
        assertEquals(UiPhase.DEVICE_ERROR, last.phase());
        assertEquals(MessageKeys.ERROR_EMULATOR, last.messageKey());
        assertFalse(r.retry());
    }

    @Test
    public void modelIntegrityFailureIsAuditedAndEndsTheRetries() throws Exception {
        engineFailure = new EngineException(EngineErrorCode.MODEL_INTEGRITY, "bad hash");
        LivenessRunner r = runner(RESIDENT);
        r.start();

        LivenessCaptureResult result = recorder.next();
        assertEquals(FailureReason.ENGINE, result.failureReason());
        assertEquals(MessageKeys.ERROR_ENGINE, result.messageKey());
        assertFalse(result.isRetryAllowed());
        assertEquals(0, result.attemptsUsed());
        List<String> ids = audit.ids();
        assertEquals("LV14 then LV13", java.util.Arrays.asList(AuditEventId.LIVENESS_MODEL_INTEGRITY_FAILED.id(),
                AuditEventId.LIVENESS_ENGINE_ERROR.id()), ids);
    }

    @Test
    public void engineStillLoadingAllowsARetry() throws Exception {
        engineFailure = new EngineException(EngineErrorCode.NOT_READY, "loading");
        LivenessRunner r = runner(RESIDENT);
        r.start();

        LivenessCaptureResult first = recorder.next();
        assertEquals(FailureReason.ENGINE, first.failureReason());
        assertTrue(first.isRetryAllowed());
        assertEquals(0, audit.count(AuditEventId.LIVENESS_MODEL_INTEGRITY_FAILED));

        engineFailure = null;
        engine.then(Script.pass(2));
        assertTrue(r.retry());
        LivenessCaptureResult second = recorder.next();
        assertTrue(second.isPass());
        assertEquals("an engine error never uses an attempt", 1, engine.requests.get(0).attempt());
    }

    @Test
    public void secondStartOpensTheNextAttemptLikeRetry() throws Exception {
        device.openFailures.add(new DeviceException(DeviceErrorCode.STREAM_OPEN_FAILED, "camera permission"));
        engine.then(Script.pass(2)).then(Script.pass(2));
        LivenessRunner r = runner(RESIDENT);
        r.start();

        LivenessCaptureResult first = recorder.next();
        assertEquals(FailureReason.DEVICE, first.failureReason());
        assertTrue(first.isRetryAllowed());
        assertEquals(0, first.attemptsUsed());

        // The screen calls start() again once the camera permission is granted.
        r.start();
        LivenessCaptureResult second = recorder.next();
        assertTrue(second.toString(), second.isPass());
        assertEquals(2, engine.sessions.size());
        assertEquals("a device failure never uses an attempt", 1, engine.requests.get(1).attempt());
    }

    @Test
    public void failedSignedCaptureIsADeviceFailureThatDoesNotCount() throws Exception {
        device.captureFailure = new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "sensor");
        engine.then(Script.pass(2));
        LivenessRunner r = runner(RESIDENT);
        r.start();

        LivenessCaptureResult result = recorder.next();
        assertFalse(result.isPass());
        assertEquals(FailureReason.DEVICE, result.failureReason());
        assertEquals(0, result.attemptsUsed());
        assertTrue(result.isRetryAllowed());
        assertNull(result.faceIso());
        assertNull(result.tokenNonce());
    }

    @Test
    public void cancelEndsTheAttemptWithoutUsingIt() throws Exception {
        engine.then(Script.hold());
        LivenessRunner r = runner(RESIDENT);
        r.start();
        engine.session(0);
        r.cancel();

        LivenessCaptureResult result = recorder.next();
        assertEquals(Decision.CANCELLED, result.decision());
        assertEquals(FailureReason.CANCELLED, result.failureReason());
        assertEquals(0, result.attemptsUsed());
        assertTrue(result.isRetryAllowed());
        assertTrue("the stream is closed", device.sources.get(0).closed);
    }

    @Test
    public void closeEndsTheRunningAttemptWithoutARetry() throws Exception {
        engine.then(Script.hold());
        LivenessRunner r = runner(RESIDENT);
        r.start();
        engine.session(0);
        r.close();

        LivenessCaptureResult result = recorder.next();
        assertEquals(Decision.CANCELLED, result.decision());
        assertFalse(result.isRetryAllowed());
        assertFalse(r.retry());
        r.close();
    }

    @Test
    public void attemptStatesComeFromTheDriverAndTheSession() throws Exception {
        engine.then(Script.failAt(2, FailureReason.ACTIVE));
        LivenessRunner r = runner(RESIDENT);
        r.start();
        recorder.next();

        List<LivenessUiState> states = recorder.snapshot();
        assertEquals("the attempt starts with the starting state", UiPhase.STARTING, states.get(0).phase());
        assertTrue(states.stream().anyMatch(s -> s.phase() == UiPhase.FAILED));
    }

    private static void assertLowLight(LivenessCaptureResult result, int count) {
        assertEquals(result.toString(), FailureReason.QUALITY, result.failureReason());
        assertTrue(result.reasonCodes().contains(ReasonCode.LOW_LIGHT.name()));
        assertEquals("a dim-light result is not counted", 0, result.attemptsUsed());
        assertTrue(result.isRetryAllowed());
        assertFalse(result.isOverrideAllowed());
        assertEquals(count, result.lowLightRetries());
    }

    // ------------------------------------------------------------------ fakes

    private static DeviceDescriptor descriptor() {
        return DeviceDescriptor.builder().serialNumber(SERIAL).certification(CertificationLevel.L0)
                .specVersion("0.9.5").transport(DeviceTransport.BUILTIN_CAMERA).build();
    }

    private static FaceFrame frame(long sequence) {
        return FaceFrame.builder().data(new byte[] {1, 2, 3, 4}).format(PixelFormat.GRAY8).size(2, 2)
                .monotonicNanos(sequence * 1_000_000L).sequence(sequence).sourceId("fake").build();
    }

    /** What one scripted session does. */
    private static final class Script {

        final int frames;
        final FailureReason failure;
        final boolean capture;
        /** Dim-light allowance ({@code passive.low_light_max_uncounted}); negative for a normal face. */
        final int lowLightAllowance;

        private Script(int frames, FailureReason failure, boolean capture) {
            this(frames, failure, capture, -1);
        }

        private Script(int frames, FailureReason failure, boolean capture, int lowLightAllowance) {
            this.frames = frames;
            this.failure = failure;
            this.capture = capture;
            this.lowLightAllowance = lowLightAllowance;
        }

        static Script pass(int frames) {
            return new Script(frames, null, true);
        }

        static Script failAt(int frames, FailureReason reason) {
            return new Script(frames, reason, false);
        }

        static Script hold() {
            return new Script(Integer.MAX_VALUE, null, false);
        }

        /**
         * A dark face the PAD model rejects, ended as the engine's low-light rule does: {@code QUALITY} with
         * {@code LOW_LIGHT}, not counted, while the request's dim-light count is tracked and below
         * {@code allowance}; otherwise a counted {@code PAD} failure.
         */
        static Script dimFace(int frames, int allowance) {
            return new Script(frames, FailureReason.PAD, false, allowance);
        }
    }

    /** Engine whose sessions follow the queued scripts; the last script repeats. */
    private static final class ScriptedEngine implements LivenessEngine {

        final Deque<Script> scripts = new ArrayDeque<>();
        final List<SessionRequest> requests = new CopyOnWriteArrayList<>();
        final List<ScriptedSession> sessions = new CopyOnWriteArrayList<>();
        private Script last = Script.hold();

        synchronized ScriptedEngine then(Script script) {
            scripts.add(script);
            return this;
        }

        @Override
        public EngineInfo info() {
            return EngineInfo.builder("scripted", "1.0").build();
        }

        @Override
        public synchronized LivenessSession open(SessionRequest request, LivenessListener listener) {
            Script s = scripts.isEmpty() ? last : scripts.poll();
            last = s;
            requests.add(request);
            ScriptedSession session = new ScriptedSession(this, s, request, listener, "session-" + (sessions.size() + 1));
            sessions.add(session);
            return session;
        }

        ScriptedSession session(int index) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
            while (sessions.size() <= index && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            return sessions.get(index);
        }
    }

    /** Session that follows a script. */
    private static final class ScriptedSession implements LivenessSession {

        private final ScriptedEngine engine;
        private final Script script;
        private final SessionRequest request;
        private final LivenessListener listener;
        private final String id;
        private volatile SessionState state = SessionState.IDLE;
        private volatile boolean cancelRequested;
        private volatile LivenessResult result;
        private LivenessPassToken token;
        private int frames;

        ScriptedSession(ScriptedEngine engine, Script script, SessionRequest request, LivenessListener listener,
                        String id) {
            this.engine = engine;
            this.script = script;
            this.request = request;
            this.listener = listener;
            this.id = id;
        }

        private boolean live() {
            if (cancelRequested && !state.isTerminal()) {
                end(null);
                return false;
            }
            return !state.isTerminal();
        }

        @Override
        public String sessionId() {
            return id;
        }

        @Override
        public SessionState state() {
            return state;
        }

        @Override
        public void start() {
            if (live() && state == SessionState.IDLE) {
                state = SessionState.ACQUIRING;
                listener.onUi(LivenessUiState.builder(UiPhase.POSITIONING, "liveness.prompt.position").build());
            }
        }

        @Override
        public void tick() {
            live();
        }

        @Override
        public void onFrame(FaceFrame frame) {
            if (!live() || state.ordinal() >= SessionState.CAPTURE_READY.ordinal()) {
                return;
            }
            if (++frames < script.frames) {
                return;
            }
            if (script.failure != null && script.lowLightAllowance >= 0 && request.lowLightRetries() >= 0
                    && request.lowLightRetries() < script.lowLightAllowance) {
                endLowLight();
            } else if (script.failure != null) {
                end(script.failure);
            } else if (script.capture) {
                token = LivenessPassToken.builder().sessionId(id).workflow(request.workflow())
                        .subjectRef(request.subjectRef()).deviceSerial(SERIAL).nonce("nonce-" + id + "-0123456789")
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(10)).build();
                state = SessionState.CAPTURE_READY;
            }
        }

        @Override
        public void onError(DeviceError error) {
            if (live() && state.ordinal() < SessionState.CAPTURE_READY.ordinal()) {
                endDevice(error.code());
            }
        }

        @Override
        public void onEnd(EndReason reason) {
            if (live() && state.ordinal() < SessionState.CAPTURE_READY.ordinal()) {
                endDevice(DeviceErrorCode.DISCONNECTED);
            }
        }

        @Override
        public Optional<LivenessPassToken> passToken() {
            return state == SessionState.CAPTURE_READY || state == SessionState.CAPTURING ? Optional.of(token)
                    : Optional.empty();
        }

        @Override
        public Optional<FaceFrame> captureFrame() {
            return state == SessionState.CAPTURE_READY || state == SessionState.CAPTURING ? Optional.of(frame(99))
                    : Optional.empty();
        }

        @Override
        public void captureStarted() {
            if (live() && state == SessionState.CAPTURE_READY) {
                state = SessionState.CAPTURING;
            }
        }

        @Override
        public void captureFailed(DeviceError error) {
            if (live() && (state == SessionState.CAPTURE_READY || state == SessionState.CAPTURING)) {
                endDevice(error.code());
            }
        }

        @Override
        public BindingVerdict bind(BindingRequest bindingRequest) {
            if (!live() || state != SessionState.CAPTURING || !bindingRequest.token().isPresent()
                    || !bindingRequest.token().get().equals(token)) {
                end(FailureReason.BINDING);
                return BindingVerdict.failed(FailureReason.BINDING, ReasonCode.TOKEN_REUSED).build();
            }
            state = SessionState.PASSED;
            result = resultBuilder().pass().build();
            listener.onUi(LivenessUiState.builder(UiPhase.SUCCESS, "liveness.success").build());
            return BindingVerdict.passed().build();
        }

        @Override
        public Optional<LivenessResult> result() {
            return Optional.ofNullable(result);
        }

        @Override
        public void cancel() {
            if (!state.isTerminal()) {
                cancelRequested = true;
            }
        }

        @Override
        public void close() {
            if (!state.isTerminal()) {
                end(null);
            }
        }

        private LivenessResult.Builder resultBuilder() {
            Instant now = Instant.now();
            return LivenessResult.builder(id, request.workflow(), engine.info(), request.device())
                    .attempt(request.attempt()).method(LivenessMethod.PASSIVE).period(now, now);
        }

        private void endDevice(DeviceErrorCode code) {
            result = resultBuilder().fail(FailureReason.DEVICE).deviceError(code).build();
            state = SessionState.FAILED;
            listener.onUi(LivenessUiState.builder(UiPhase.DEVICE_ERROR, "liveness.error.device").build());
        }

        private void endLowLight() {
            result = resultBuilder().fail(FailureReason.QUALITY).reason(ReasonCode.LOW_LIGHT).build();
            state = SessionState.FAILED;
            listener.onUi(LivenessUiState.builder(UiPhase.FAILED, "liveness.fail").build());
        }

        /** Ends the session: {@code reason == null} cancels. */
        private void end(FailureReason reason) {
            LivenessResult.Builder b = resultBuilder().fail(reason == null ? FailureReason.CANCELLED : reason);
            if (reason == FailureReason.PAD) {
                b.reason(ReasonCode.PAD_SCORE);
            }
            result = b.build();
            state = reason == null ? SessionState.CANCELLED : SessionState.FAILED;
            listener.onUi(LivenessUiState.builder(reason == null ? UiPhase.CANCELLED : UiPhase.FAILED,
                    reason == null ? "liveness.cancelled" : "liveness.fail").build());
            if (reason != null && reason.countsAsAttempt() && request.attempt() >= request.policy().maxRetries()) {
                listener.onAudit(new AuditPayloadBuilder(request.workflow(), id, request.clock())
                        .maxRetries(request.attempt(), request.policy().onMaxFailure()));
            }
        }
    }

    /** Device whose stream pushes a frame every few milliseconds until closed; signs with a real ISO record. */
    private static final class FakeDevice implements FaceCaptureDevice {

        final Deque<DeviceException> openFailures = new ArrayDeque<>();
        final List<FakeSource> sources = new CopyOnWriteArrayList<>();
        volatile DeviceException captureFailure;
        volatile byte[] lastIso;
        volatile long lastSourceSequence = -1;
        volatile FaceFrame lastSourceFrame;

        @Override
        public DeviceDescriptor describe() {
            return descriptor();
        }

        @Override
        public DeviceStatus status() {
            return DeviceStatus.READY;
        }

        @Override
        public synchronized FaceFrameSource openStream(StreamRequest request) throws DeviceException {
            DeviceException e = openFailures.poll();
            if (e != null) {
                throw e;
            }
            FakeSource s = new FakeSource();
            sources.add(s);
            return s;
        }

        @Override
        public SignedFaceCapture capture(CaptureRequest request) throws DeviceException {
            request.sourceFrame().ifPresent(f -> {
                lastSourceSequence = f.sequence();
                lastSourceFrame = f;
            });
            DeviceException e = captureFailure;
            if (e != null) {
                throw e;
            }
            byte[] iso;
            try {
                iso = FaceIsoRecords.toIsoRecord(JP2, 2, 2, DevicePurpose.REGISTRATION, 90, new Date());
            } catch (java.io.IOException io) {
                throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "iso", io);
            }
            lastIso = iso;
            return SignedFaceCapture.builder().isoRecord(iso).signedData("signed").capturedAt(Instant.now())
                    .transactionId(request.transactionId()).deviceSerial(SERIAL).certification(CertificationLevel.L0)
                    .specVersion("0.9.5").build();
        }
    }

    /** Frame source fed by a daemon thread. */
    private static final class FakeSource implements FaceFrameSource {

        volatile boolean closed;

        @Override
        public void start(FrameSink sink) {
            Thread feeder = new Thread(() -> {
                long seq = 0;
                while (!closed) {
                    sink.onFrame(frame(seq++));
                    try {
                        Thread.sleep(3);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                sink.onEnd(EndReason.CLOSED);
            }, "fake-reader");
            feeder.setDaemon(true);
            feeder.start();
        }

        @Override
        public StreamStats stats() {
            return StreamStats.EMPTY;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Decoder that checks it got the image of the ISO record. */
    private static final class FakeDecoder implements ImageDecoder {

        @Override
        public ImageBuffer decode(FaceFrame frame) {
            return ImageBuffer.ofBytes(2, 2, PixelFormat.GRAY8, new byte[4]);
        }

        @Override
        public ImageBuffer decode(byte[] encoded) {
            assertArrayEquals("the image inside the signed ISO record", JP2, encoded);
            return ImageBuffer.ofBytes(2, 2, PixelFormat.GRAY8, new byte[4]);
        }
    }

    /** Listener that records states and queues results. */
    private static final class Recorder implements LivenessRunner.Listener {

        final List<LivenessUiState> states = Collections.synchronizedList(new ArrayList<>());
        final BlockingQueue<LivenessCaptureResult> results = new LinkedBlockingQueue<>();
        volatile String endedOn;

        @Override
        public void onUiState(LivenessUiState state) {
            states.add(state);
        }

        @Override
        public void onAttemptEnded(LivenessCaptureResult result) {
            endedOn = Thread.currentThread().getName();
            results.add(result);
        }

        LivenessCaptureResult next() throws InterruptedException {
            LivenessCaptureResult r = results.poll(WAIT_S, TimeUnit.SECONDS);
            assertNotNull("no attempt result within " + WAIT_S + " s", r);
            return r;
        }

        List<LivenessUiState> snapshot() {
            synchronized (states) {
                return new ArrayList<>(states);
            }
        }

        LivenessUiState lastState() {
            List<LivenessUiState> s = snapshot();
            return s.get(s.size() - 1);
        }
    }

    /** Audit sink that keeps every event. */
    private static final class RecordingAudit implements AuditSink {

        final List<LivenessAuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void emit(LivenessAuditEvent event) {
            events.add(event);
        }

        long count(AuditEventId id) {
            return events.stream().filter(e -> e.eventId() == id).count();
        }

        List<String> ids() {
            List<String> ids = new ArrayList<>();
            for (LivenessAuditEvent e : events) {
                ids.add(e.eventId().id());
            }
            return ids;
        }
    }
}
