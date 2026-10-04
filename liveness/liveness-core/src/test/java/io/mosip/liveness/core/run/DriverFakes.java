package io.mosip.liveness.core.run;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.device.CaptureRequest;
import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
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
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.UiPhase;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Fake device, scripted engine and recording receivers for {@link LivenessSessionDriverTest}. */
final class DriverFakes {

    static final String SERIAL = "FAKE-0001";
    static final long WAIT_S = 10;

    private DriverFakes() {
    }

    static DeviceDescriptor descriptor() {
        return DeviceDescriptor.builder().serialNumber(SERIAL).certification(CertificationLevel.L0).specVersion("0.9.5")
                .transport(DeviceTransport.SBI_HTTP).build();
    }

    static FaceFrame frame(long sequence) {
        return FaceFrame.builder().data(new byte[] {1, 2, 3, 4}).format(PixelFormat.GRAY8).size(2, 2)
                .monotonicNanos(sequence * 1_000_000L).sequence(sequence).sourceId("fake").build();
    }

    static ImageBuffer image() {
        return ImageBuffer.ofBytes(2, 2, PixelFormat.GRAY8, new byte[4]);
    }

    static SignedFaceCapture capture() {
        return SignedFaceCapture.builder().isoRecord(new byte[] {9}).signedData("signed").capturedAt(Instant.now())
                .transactionId("tx-1").deviceSerial(SERIAL).certification(CertificationLevel.L0).specVersion("0.9.5")
                .build();
    }

    /** What one scripted session does. */
    static final class Script {

        final int frames;
        final FailureReason failure;
        final boolean capture;
        final FailureReason bindFailure;
        EngineException openFailure;
        int lowLightAllowance = -1;

        private Script(int frames, FailureReason failure, boolean capture, FailureReason bindFailure) {
            this.frames = frames;
            this.failure = failure;
            this.capture = capture;
            this.bindFailure = bindFailure;
        }

        /** Reaches CAPTURE_READY after {@code frames} frames; a capture bound in CAPTURING passes. */
        static Script pass(int frames) {
            return new Script(frames, null, true, null);
        }

        /** Fails for {@code reason} after {@code frames} frames. */
        static Script failAt(int frames, FailureReason reason) {
            return new Script(frames, reason, false, null);
        }

        /** Reaches CAPTURE_READY, then binding fails for {@code reason}. */
        static Script bindFails(int frames, FailureReason reason) {
            return new Script(frames, null, true, reason);
        }

        /**
         * A dim face whose passive PAD check fails after {@code frames} frames, decided as the default engine does:
         * {@code FAILED(QUALITY, LOW_LIGHT)}, not counted, while the request's
         * {@link SessionRequest#lowLightRetries()} is 0 or more and below {@code allowance}
         * ({@code passive.low_light_max_uncounted}); otherwise a counted {@code PAD} failure.
         */
        static Script dimFace(int frames, int allowance) {
            Script s = new Script(frames, FailureReason.PAD, false, null);
            s.lowLightAllowance = allowance;
            return s;
        }

        /** Runs until cancelled. */
        static Script hold() {
            return new Script(Integer.MAX_VALUE, null, false, null);
        }

        /** {@code LivenessEngine.open} throws. */
        static Script openFails(EngineException e) {
            Script s = new Script(0, null, false, null);
            s.openFailure = e;
            return s;
        }
    }

    /** Engine whose sessions follow the queued scripts; the last script repeats. */
    static final class ScriptedEngine implements LivenessEngine {

        final Deque<Script> scripts = new ArrayDeque<>();
        final List<SessionRequest> requests = new CopyOnWriteArrayList<>();
        final List<ScriptedSession> sessions = new CopyOnWriteArrayList<>();
        volatile boolean auditsMaxRetries = true;
        private Script last = Script.hold();

        ScriptedEngine then(Script script) {
            scripts.add(script);
            return this;
        }

        @Override
        public EngineInfo info() {
            return EngineInfo.builder("scripted", "1.0").build();
        }

        @Override
        public synchronized LivenessSession open(SessionRequest request, LivenessListener listener)
                throws EngineException {
            Script s = scripts.isEmpty() ? last : scripts.poll();
            last = s;
            requests.add(request);
            if (s.openFailure != null) {
                throw s.openFailure;
            }
            ScriptedSession session = new ScriptedSession(this, s, request, listener);
            sessions.add(session);
            return session;
        }

        /** Returns a session, waiting until the driver has opened it. */
        ScriptedSession session(int index) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
            while (sessions.size() <= index && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            return sessions.get(index);
        }
    }

    /** Session that follows a script; owner-thread calls are counted. */
    static final class ScriptedSession implements LivenessSession {

        private final ScriptedEngine engine;
        private final Script script;
        private final SessionRequest request;
        private final LivenessListener listener;
        private final String id;
        private volatile SessionState state = SessionState.IDLE;
        private volatile boolean cancelRequested;
        private volatile LivenessResult result;
        private LivenessPassToken token;
        final AtomicInteger frames = new AtomicInteger();
        final AtomicInteger ticks = new AtomicInteger();
        final AtomicInteger ticksAfterCaptureReady = new AtomicInteger();
        final CountDownLatch receivingFrames = new CountDownLatch(1);
        volatile boolean closed;
        volatile Thread ownerThread;

        ScriptedSession(ScriptedEngine engine, Script script, SessionRequest request, LivenessListener listener) {
            this.engine = engine;
            this.script = script;
            this.request = request;
            this.listener = listener;
            this.id = "session-" + (engine.sessions.size() + 1);
        }

        SessionRequest request() {
            return request;
        }

        String passTokenNonce() {
            return token == null ? null : token.nonce();
        }

        private boolean owner() {
            ownerThread = Thread.currentThread();
            if (cancelRequested && !state.isTerminal()) {
                end(null, null);
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
            if (owner() && state == SessionState.IDLE) {
                state = SessionState.ACQUIRING;
                listener.onUi(LivenessUiState.builder(UiPhase.POSITIONING, "liveness.prompt.position").build());
            }
        }

        @Override
        public void tick() {
            ticks.incrementAndGet();
            if (state == SessionState.CAPTURE_READY || state == SessionState.CAPTURING) {
                ticksAfterCaptureReady.incrementAndGet();
            }
            owner();
        }

        @Override
        public void onFrame(FaceFrame frame) {
            if (!owner() || state.ordinal() >= SessionState.CAPTURE_READY.ordinal()) {
                return;
            }
            int n = frames.incrementAndGet();
            receivingFrames.countDown();
            if (n < script.frames) {
                return;
            }
            if (script.failure != null && script.lowLightAllowance >= 0 && request.lowLightRetries() >= 0
                    && request.lowLightRetries() < script.lowLightAllowance) {
                end(FailureReason.QUALITY, null, ReasonCode.LOW_LIGHT);
            } else if (script.failure != null) {
                end(script.failure, null);
            } else if (script.capture) {
                token = LivenessPassToken.builder().sessionId(id).workflow(request.workflow())
                        .subjectRef(request.subjectRef()).deviceSerial(SERIAL).nonce("nonce-" + id + "-0123456789")
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(10)).build();
                state = SessionState.CAPTURE_READY;
            }
        }

        @Override
        public void onError(DeviceError error) {
            if (owner() && state.ordinal() < SessionState.CAPTURE_READY.ordinal()) {
                end(FailureReason.DEVICE, error.code());
            }
        }

        @Override
        public void onEnd(EndReason reason) {
            if (owner() && state.ordinal() < SessionState.CAPTURE_READY.ordinal()) {
                end(FailureReason.DEVICE, DeviceErrorCode.DISCONNECTED);
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
            if (owner() && state == SessionState.CAPTURE_READY) {
                state = SessionState.CAPTURING;
            }
        }

        @Override
        public void captureFailed(DeviceError error) {
            if (owner() && (state == SessionState.CAPTURE_READY || state == SessionState.CAPTURING)) {
                end(FailureReason.DEVICE, error.code());
            }
        }

        @Override
        public BindingVerdict bind(BindingRequest bindingRequest) {
            if (!owner() || state != SessionState.CAPTURING) {
                return BindingVerdict.failed(FailureReason.BINDING, ReasonCode.TOKEN_REUSED).build();
            }
            if (!bindingRequest.token().isPresent() || !bindingRequest.token().get().equals(token)) {
                end(FailureReason.BINDING, null);
                return BindingVerdict.failed(FailureReason.BINDING, ReasonCode.TOKEN_REUSED).build();
            }
            if (script.bindFailure != null) {
                end(script.bindFailure, null);
                return BindingVerdict.failed(script.bindFailure, ReasonCode.SIMILARITY_LOW).build();
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
                end(null, null);
            }
            closed = true;
        }

        private LivenessResult.Builder resultBuilder() {
            Instant now = Instant.now();
            return LivenessResult.builder(id, request.workflow(), engine.info(), request.device())
                    .attempt(request.attempt()).method(LivenessMethod.PASSIVE).period(now, now);
        }

        /** Ends the session: {@code reason == null} cancels. */
        private void end(FailureReason reason, DeviceErrorCode deviceError) {
            end(reason, deviceError, null);
        }

        /** Ends the session with an optional reason code: {@code reason == null} cancels. */
        private void end(FailureReason reason, DeviceErrorCode deviceError, ReasonCode code) {
            LivenessResult.Builder b = resultBuilder().fail(reason == null ? FailureReason.CANCELLED : reason);
            if (deviceError != null) {
                b.deviceError(deviceError);
            }
            if (code != null) {
                b.reason(code);
            }
            result = b.build();
            state = reason == null ? SessionState.CANCELLED : SessionState.FAILED;
            listener.onUi(LivenessUiState.builder(reason == null ? UiPhase.CANCELLED : UiPhase.FAILED,
                    reason == null ? "liveness.cancelled" : "liveness.fail." + reason.name().toLowerCase()).build());
            if (reason != null && reason.countsAsAttempt() && engine.auditsMaxRetries
                    && request.attempt() >= request.policy().maxRetries()) {
                listener.onAudit(new AuditPayloadBuilder(request.workflow(), id, request.clock())
                        .maxRetries(request.attempt(), request.policy().onMaxFailure()));
            }
        }
    }

    /** Device whose stream pushes a frame every few milliseconds until closed. */
    static final class FakeDevice implements FaceCaptureDevice {

        volatile DeviceException openFailure;
        volatile DeviceErrorCode streamError;
        volatile int streamErrorAfterFrames;
        volatile DeviceStatus status = DeviceStatus.READY;
        final List<FakeSource> sources = new CopyOnWriteArrayList<>();
        final AtomicInteger statusCalls = new AtomicInteger();

        @Override
        public DeviceDescriptor describe() {
            return descriptor();
        }

        @Override
        public DeviceStatus status() {
            statusCalls.incrementAndGet();
            return status;
        }

        @Override
        public FaceFrameSource openStream(StreamRequest request) throws DeviceException {
            DeviceException e = openFailure;
            if (e != null) {
                openFailure = null;
                throw e;
            }
            DeviceErrorCode error = streamError;
            streamError = null;
            FakeSource s = new FakeSource(error, streamErrorAfterFrames);
            sources.add(s);
            return s;
        }

        @Override
        public SignedFaceCapture capture(CaptureRequest request) {
            return DriverFakes.capture();
        }

        FakeSource source(int index) {
            return sources.get(index);
        }
    }

    /** Frame source fed by a daemon thread. */
    static final class FakeSource implements FaceFrameSource {

        private final DeviceErrorCode error;
        private final int errorAfter;
        volatile boolean closed;
        private Thread feeder;

        FakeSource(DeviceErrorCode error, int errorAfter) {
            this.error = error;
            this.errorAfter = errorAfter;
        }

        @Override
        public void start(FrameSink sink) {
            feeder = new Thread(() -> {
                long seq = 0;
                while (!closed) {
                    if (error != null && seq >= errorAfter) {
                        sink.onError(DeviceError.of(error, "scripted stream error"));
                        return;
                    }
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

    /** Listener that records states and queues outcomes. */
    static final class Recorder implements LivenessSessionDriver.Listener {

        final List<LivenessUiState> states = Collections.synchronizedList(new ArrayList<>());
        final BlockingQueue<AttemptOutcome> outcomes = new LinkedBlockingQueue<>();

        @Override
        public void onUiState(LivenessUiState state) {
            states.add(state);
        }

        @Override
        public void onAttemptEnded(AttemptOutcome outcome) {
            outcomes.add(outcome);
        }

        AttemptOutcome next() throws InterruptedException {
            AttemptOutcome o = outcomes.poll(WAIT_S, TimeUnit.SECONDS);
            assertNotNull(o, "no attempt outcome within " + WAIT_S + " s");
            return o;
        }

        List<LivenessUiState> statesSnapshot() {
            synchronized (states) {
                return new ArrayList<>(states);
            }
        }
    }

    /** Audit sink that keeps every event. */
    static final class RecordingAudit implements AuditSink {

        final List<LivenessAuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void emit(LivenessAuditEvent event) {
            events.add(event);
        }

        long count(AuditEventId id) {
            return events.stream().filter(e -> e.eventId() == id).count();
        }
    }
}
