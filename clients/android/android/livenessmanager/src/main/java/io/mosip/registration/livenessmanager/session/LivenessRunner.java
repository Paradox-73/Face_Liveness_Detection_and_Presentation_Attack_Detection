package io.mosip.registration.livenessmanager.session;

import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.CaptureRequest;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.FaceCaptureDevice;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.PadMethod;
import io.mosip.liveness.core.device.PadRequest;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.liveness.core.engine.LivenessTransaction;
import io.mosip.liveness.core.engine.LivenessUiStateMapper;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.stream.FrameHandOff;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.PromptIcon;
import io.mosip.liveness.core.ui.Severity;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.device.BuiltInCameraL0Device;
import io.mosip.registration.livenessmanager.device.FaceIsoRecords;
import io.mosip.registration.livenessmanager.engine.EngineLoader;

/**
 * One liveness transaction on Android: the first attempt and its retries, from the frame stream to the signed,
 * session-bound face capture.
 *
 * <p><b>Threading</b> (design section 4.9). Every session call runs on this runner's single
 * {@code liveness-worker} thread, which owns the session. The frame source (CameraX or replay) runs on its own
 * thread and only puts frames into a {@link FrameHandOff} that keeps the newest frame; the worker moves them into
 * the session and calls {@code tick()} at least every {@value #TICK_MS} ms. {@link Listener} callbacks come from the
 * worker; the screen forwards them to the main thread.
 *
 * <p><b>Capture</b>. When the session reaches {@code CAPTURE_READY} the worker copies the session's capture
 * frame, lets the device sign it (the built-in L0 camera signs that very frame), decodes the image back out of
 * the ISO record and binds it to the session ({@code LivenessSession.bind}). Only a bound capture is a pass.
 *
 * <p><b>Retries</b> follow {@link LivenessTransaction}: failures that count use an attempt; device, engine,
 * quality and cancellation failures do not. Attempts already used for the same subject on an earlier screen
 * ({@code priorAttempts}) count too, so "Attempt n of m" and the last attempt are the same across screens. After the last attempt the session writes audit {@code REG-EVT-LV11}
 * (once) and the result reports {@code on_max_failure}.
 */
public final class LivenessRunner implements CaptureRunner {

    /** Longest interval between two {@code tick()} calls. */
    public static final long TICK_MS = 100L;
    /** Longest wait for the engine to load before a session fails with {@code NOT_READY}. */
    static final long ENGINE_WAIT_MS = 30_000L;

    private static final String TAG = "Liveness";

    /** Receives the runner's output on the worker thread. */
    public interface Listener {

        /**
         * Receives a new state to render.
         *
         * @param state the state
         */
        void onUiState(LivenessUiState state);

        /**
         * Receives the outcome of an attempt: a pass with the capture, or a failure (check
         * {@link LivenessCaptureResult#isRetryAllowed()}).
         *
         * @param result the outcome
         */
        void onAttemptEnded(LivenessCaptureResult result);
    }

    private final LivenessPolicy policy;
    private final String subjectRef;
    private final EngineLoader engines;
    private final FaceCaptureDevice device;
    private final ImageDecoder decoder;
    private final AuditSink audit;
    private final DiagnosticSink diagnostics;
    private final Clock clock;
    private final Listener listener;
    private final String blockedMessageKey;
    private final LivenessTransaction transaction;
    private final int priorAttempts;
    private final ScheduledThreadPoolExecutor worker;

    private volatile Attempt current;
    private volatile LivenessCaptureResult lastResult;
    private volatile String lastFrameTimes = "";
    private volatile boolean closed;

    /**
     * Creates a runner; nothing happens until {@link #start()}.
     *
     * @param policy            resolved policy of the workflow
     * @param subjectRef        registration ID or user ID
     * @param engines           engine loader
     * @param device            face device
     * @param decoder           image decoder (for the image inside the signed capture)
     * @param audit             audit sink
     * @param diagnostics       diagnostic sink (no-op unless diagnostics are enabled)
     * @param clock             session clock
     * @param blockedMessageKey message key when liveness must not run on this device (for example
     *                          {@code liveness.error.emulator}), or {@code null}
     * @param listener          receiver of states and outcomes
     */
    public LivenessRunner(LivenessPolicy policy, String subjectRef, EngineLoader engines, FaceCaptureDevice device,
                          ImageDecoder decoder, AuditSink audit, DiagnosticSink diagnostics, Clock clock,
                          String blockedMessageKey, Listener listener) {
        this(policy, subjectRef, engines, device, decoder, audit, diagnostics, clock, blockedMessageKey, 0, listener);
    }

    /**
     * Creates a runner that continues a transaction started on an earlier screen; nothing happens until
     * {@link #start()}.
     *
     * @param policy            resolved policy of the workflow
     * @param subjectRef        registration ID or user ID
     * @param engines           engine loader
     * @param device            face device
     * @param decoder           image decoder (for the image inside the signed capture)
     * @param audit             audit sink
     * @param diagnostics       diagnostic sink (no-op unless diagnostics are enabled)
     * @param clock             session clock
     * @param blockedMessageKey message key when liveness must not run on this device, or {@code null}
     * @param priorAttempts     attempts the subject already used for this capture, 0 to {@code max_retries - 1}
     * @param listener          receiver of states and outcomes
     */
    public LivenessRunner(LivenessPolicy policy, String subjectRef, EngineLoader engines, FaceCaptureDevice device,
                          ImageDecoder decoder, AuditSink audit, DiagnosticSink diagnostics, Clock clock,
                          String blockedMessageKey, int priorAttempts, Listener listener) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.subjectRef = subjectRef == null ? "" : subjectRef;
        this.engines = Objects.requireNonNull(engines, "engines");
        this.device = Objects.requireNonNull(device, "device");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.blockedMessageKey = blockedMessageKey;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.priorAttempts = Math.max(0, Math.min(priorAttempts, policy.maxRetries() - 1));
        // The transaction counts the attempts left; attempt numbers shown and audited include the earlier ones.
        this.transaction = new LivenessTransaction(this.priorAttempts == 0 ? policy
                : policy.toBuilder().maxRetries(policy.maxRetries() - this.priorAttempts).build());
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "liveness-worker");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        this.worker = executor;
    }

    /** Opens the first session. */
    @Override
    public void start() {
        submit(this::openSession);
    }

    /**
     * Opens the next session after a failed attempt.
     *
     * @return {@code false} if no retry is available (closed, running, passed or no attempts left)
     */
    @Override
    public boolean retry() {
        LivenessCaptureResult last = lastResult;
        if (closed || current != null || last == null || !last.isRetryAllowed()) {
            return false;
        }
        submit(this::openSession);
        return true;
    }

    /** Cancels the running session, if any; the attempt ends with {@code CANCELLED}. Safe from any thread. */
    @Override
    public void cancel() {
        Attempt a = current;
        if (a != null && a.session != null) {
            a.session.cancel();
            submit(() -> {
                a.session.tick();
                a.afterCall();
            });
        }
    }

    /**
     * Returns the outcome of the last attempt.
     *
     * @return the outcome; empty while the first attempt runs
     */
    public Optional<LivenessCaptureResult> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    @Override
    public String lastFrameTimes() {
        return lastFrameTimes;
    }

    /**
     * Returns the policy used by every attempt.
     *
     * @return the policy
     */
    public LivenessPolicy policy() {
        return policy;
    }

    /** Cancels the running session, releases the camera and stops the worker. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Attempt a = current;
        if (a != null && a.session != null) {
            a.session.cancel();
        }
        try {
            worker.execute(() -> {
                Attempt c = current;
                if (c != null && c.session != null) {
                    c.session.tick();
                    c.afterCall();
                }
                worker.shutdown();
            });
        } catch (RejectedExecutionException ignored) {
            // Already stopped.
        }
    }

    // ------------------------------------------------------------------ worker thread

    private void openSession() {
        if (closed || current != null) {
            return;
        }
        int attempt = priorAttempts + transaction.nextAttempt();
        LivenessUiStateMapper mapper = new LivenessUiStateMapper(policy, attempt);
        if (blockedMessageKey != null) {
            noSession(FailureReason.DEVICE, errorState(UiPhase.DEVICE_ERROR, blockedMessageKey, PromptIcon.LOCKED,
                    false), new AuditPayloadBuilder(policy.workflow(), "", clock)
                    .deviceError(DeviceErrorCode.EMULATOR_BLOCKED), false);
            return;
        }
        callListener(() -> listener.onUiState(mapper.starting()));
        LivenessEngine engine;
        try {
            engine = engines.await(ENGINE_WAIT_MS);
        } catch (EngineException e) {
            boolean integrity = e.getErrorCode().isModelIntegrityFailure();
            AuditPayloadBuilder events = new AuditPayloadBuilder(policy.workflow(), "", clock);
            if (integrity) {
                audit.emit(events.modelIntegrityFailed("bundle"));
            }
            noSession(FailureReason.ENGINE, errorState(UiPhase.FAILED, MessageKeys.ERROR_ENGINE, PromptIcon.ERROR,
                    !integrity), events.engineError(e.getErrorCode(), e.getClass().getSimpleName()), !integrity);
            return;
        }
        Attempt a = new Attempt();
        try {
            SessionRequest request = SessionRequest.builder(policy, subjectRef, device.describe()).attempt(attempt)
                    .clock(clock).diagnosticSink(diagnostics).build();
            a.session = engine.open(request, a);
        } catch (EngineException | RuntimeException e) {
            Log.e(TAG, "Liveness session could not be opened: " + e.getClass().getSimpleName());
            noSession(FailureReason.ENGINE, errorState(UiPhase.FAILED, MessageKeys.ERROR_ENGINE, PromptIcon.ERROR,
                    true), null, true);
            return;
        }
        current = a;
        a.session.start();
        if (!a.afterCall()) {
            startStream(a);
        }
    }

    private void startStream(Attempt a) {
        try {
            a.source = device.openStream(StreamRequest.builder().streamTimeoutMs(policy.streamTimeoutMs())
                    .reconnectAttempts(policy.deviceReconnectAttempts()).build());
            a.handOff = FrameHandOff.latestOnly();
            a.timer = new FrameTimer(a.session, System::nanoTime);
            a.source.start(a.handOff);
        } catch (DeviceException e) {
            a.session.onError(e.getError());
            a.afterCall();
            return;
        } catch (RuntimeException e) {
            a.session.onError(DeviceError.of(DeviceErrorCode.STREAM_OPEN_FAILED, "camera could not start", e));
            a.afterCall();
            return;
        }
        submit(() -> pump(a));
    }

    private void pump(Attempt a) {
        if (a.finished || a != current) {
            return;
        }
        try {
            a.handOff.deliverNext(a.timer, TICK_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            a.session.cancel();
        }
        a.session.tick();
        if (!a.afterCall()) {
            submit(() -> pump(a));
        }
    }

    private void captureAndBind(Attempt a) {
        LivenessSession session = a.session;
        Optional<LivenessPassToken> token = session.passToken();
        Optional<FaceFrame> selected = session.captureFrame();
        if (!token.isPresent() || !selected.isPresent()) {
            session.captureFailed(DeviceError.of(DeviceErrorCode.CAPTURE_FAILED, "no capture frame"));
            return;
        }
        // The session wipes its capture frame at close(); the device gets a copy.
        FaceFrame f = selected.get();
        FaceFrame frame = FaceFrame.builder().data(Arrays.copyOf(f.data(), f.data().length)).format(f.format())
                .size(f.width(), f.height()).rotationDegrees(f.rotationDegrees()).mirrored(f.isMirrored())
                .monotonicNanos(f.monotonicNanos()).sequence(f.sequence()).sourceId(f.sourceId()).build();
        session.captureStarted();
        ImageBuffer image = null;
        try {
            CaptureRequest.Builder request = CaptureRequest.builder().transactionId(UUID.randomUUID().toString())
                    .deviceSubId(StreamRequest.FACE_DEVICE_SUB_ID).sourceFrame(frame);
            if (device.describe().isPadCapable()) {
                request.pad(PadRequest.of(true, PadMethod.PASSIVE, token.get().nonce()));
            }
            SignedFaceCapture capture = device instanceof BuiltInCameraL0Device
                    ? ((BuiltInCameraL0Device) device).capture(request.build(), session.sessionId(),
                    token.get().nonce())
                    : device.capture(request.build());
            image = decoder.decode(FaceIsoRecords.imageOf(capture.isoRecord()));
            a.capture = capture;
            a.tokenNonce = token.get().nonce();
            session.bind(BindingRequest.from(capture, image, token.get()));
        } catch (DeviceException e) {
            session.captureFailed(e.getError());
        } catch (ImageDecodeException | IOException | RuntimeException e) {
            session.captureFailed(DeviceError.of(DeviceErrorCode.CAPTURE_FAILED, "captured image unreadable", e));
        } finally {
            frame.wipe();
            if (image != null) {
                image.wipe();
            }
        }
    }

    private void finish(Attempt a) {
        if (a.finished) {
            return;
        }
        a.finished = true;
        closeSource(a);
        LivenessResult result = a.session.result().orElse(null);
        if (result != null && transaction.status() == LivenessTransaction.Status.IN_PROGRESS) {
            // The session itself writes REG-EVT-LV11 when its failure uses the last attempt.
            transaction.record(result);
        }
        LivenessCaptureResult outcome = toCaptureResult(a, result);
        lastResult = outcome;
        a.session.close();
        if (current == a) {
            current = null;
        }
        Log.i(TAG, "Liveness attempt ended: " + outcome);
        if (a.timer != null && a.timer.frames() > 0) {
            lastFrameTimes = a.timer.summary();
            Log.i(TAG, "Liveness frame time " + policy.workflow().id() + " session " + transaction.sessions()
                    + ": " + lastFrameTimes);
        }
        callListener(() -> listener.onAttemptEnded(outcome));
    }

    private LivenessCaptureResult toCaptureResult(Attempt a, LivenessResult result) {
        boolean pass = result != null && result.isPass() && a.capture != null;
        Decision decision = result == null ? Decision.CANCELLED : (pass ? Decision.PASS
                : (result.isPass() ? Decision.ERROR : result.decision()));
        LivenessCaptureResult.Builder b = LivenessCaptureResult.builder(policy.workflow(), decision)
                .sessionId(a.session.sessionId())
                .attempts(attemptsUsed(), policy.maxRetries())
                .retryAllowed(!pass && !closed && transaction.canRetry())
                .overrideAllowed(!pass && transaction.isOverrideAllowed())
                .lockRequired(!pass && transaction.isLockRequired())
                .onMaxFailure(transaction.onMaxFailure())
                .messageKey(a.lastMessageKey);
        if (result == null) {
            b.failureReason(FailureReason.CANCELLED);
        } else {
            result.failureReason().ifPresent(b::failureReason);
            List<String> codes = new ArrayList<>();
            for (ReasonCode c : result.reasonCodes()) {
                codes.add(c.name());
            }
            b.reasonCodes(codes).evidence(PacketEvidenceKeys.of(result));
        }
        if (pass) {
            String response = device instanceof BuiltInCameraL0Device
                    ? ((BuiltInCameraL0Device) device).lastResponseJson() : null;
            boolean provisioned = device instanceof BuiltInCameraL0Device
                    && ((BuiltInCameraL0Device) device).isProvisioned();
            b.capture(a.capture.transactionId(), a.capture.isoRecord(), response, provisioned)
                    .tokenNonce(a.tokenNonce);
        }
        return b.build();
    }

    private void noSession(FailureReason reason, LivenessUiState state, LivenessAuditEvent event, boolean retry) {
        if (event != null) {
            audit.emit(event);
        }
        LivenessCaptureResult outcome = LivenessCaptureResult.builder(policy.workflow(), reason.decision())
                .failureReason(reason).messageKey(state.messageKey())
                .attempts(attemptsUsed(), policy.maxRetries())
                .retryAllowed(retry && !closed && transaction.canRetry())
                .onMaxFailure(transaction.onMaxFailure()).build();
        lastResult = outcome;
        callListener(() -> listener.onUiState(state));
        callListener(() -> listener.onAttemptEnded(outcome));
    }

    private LivenessUiState errorState(UiPhase phase, String key, PromptIcon icon, boolean retry) {
        LivenessUiState.Builder b = LivenessUiState.builder(phase, key).promptIcon(icon).severity(Severity.ERROR)
                .retryAvailable(retry)
                .attempt(Math.min(attemptsUsed() + 1, policy.maxRetries()), policy.maxRetries());
        if (retry) {
            b.guidance(MessageKeys.RETRY_AVAILABLE);
        }
        return b.build();
    }

    /** Attempts used by the subject, including those of earlier screens. */
    private int attemptsUsed() {
        return priorAttempts + transaction.attemptsUsed();
    }

    private static void closeSource(Attempt a) {
        FaceFrameSource s = a.source;
        a.source = null;
        if (s != null) {
            s.close();
        }
    }

    private void callListener(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            Log.e(TAG, "Liveness listener failed: " + e.getClass().getSimpleName());
        }
    }

    private void submit(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            Log.d(TAG, "Liveness worker stopped; task dropped");
        }
    }

    /** One session and its stream. Worker thread only, except the listener callbacks the session makes. */
    private final class Attempt implements LivenessListener {

        LivenessSession session;
        FaceFrameSource source;
        FrameHandOff handOff;
        FrameTimer timer;
        SignedFaceCapture capture;
        String tokenNonce;
        String lastMessageKey;
        boolean captureHandled;
        boolean finished;

        /**
         * Acts on the session state after a call: capture when ready, finish when terminal.
         *
         * @return {@code true} when the stream loop must stop
         */
        boolean afterCall() {
            if (finished) {
                return true;
            }
            SessionState s = session.state();
            if (!s.isTerminal() && !captureHandled && s == SessionState.CAPTURE_READY) {
                captureHandled = true;
                captureAndBind(this);
                s = session.state();
                if (!s.isTerminal()) {
                    // Binding always ends the session; this only guards against an unexpected state.
                    session.cancel();
                    session.tick();
                    s = session.state();
                }
            }
            if (s.isTerminal()) {
                finish(this);
                return true;
            }
            return captureHandled;
        }

        @Override
        public void onUi(LivenessUiState state) {
            lastMessageKey = state.messageKey();
            callListener(() -> listener.onUiState(state));
        }

        @Override
        public void onAudit(LivenessAuditEvent event) {
            audit.emit(event);
        }
    }
}
