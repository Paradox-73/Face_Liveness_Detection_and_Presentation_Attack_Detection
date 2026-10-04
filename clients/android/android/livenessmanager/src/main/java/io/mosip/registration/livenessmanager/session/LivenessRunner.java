package io.mosip.registration.livenessmanager.session;

import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.EngineInfo;
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
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.CaptureRequest;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.FaceCaptureDevice;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.device.PadMethod;
import io.mosip.liveness.core.device.PadRequest;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.liveness.core.engine.LivenessTransaction;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.run.AttemptOutcome;
import io.mosip.liveness.core.run.CaptureHandOff;
import io.mosip.liveness.core.run.LivenessSessionDriver;
import io.mosip.liveness.core.stream.FrameHandOff;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.device.BuiltInCameraL0Device;
import io.mosip.registration.livenessmanager.device.FaceIsoRecords;
import io.mosip.registration.livenessmanager.engine.EngineLoader;

/**
 * One liveness transaction on Android: the first attempt and its retries, from the frame stream to the signed,
 * session-bound face capture.
 *
 * <p>The attempt loop is the engine's shared {@link LivenessSessionDriver}, the same one the desktop client runs, so
 * both clients count retries, write {@code REG-EVT-LV11}, {@code LV12} and {@code LV13} and report attempts the same
 * way. This class adds what is specific to Android: the worker thread, the signed capture of the built-in camera,
 * the per-frame timing log and the mapping of each {@link AttemptOutcome} to a {@link LivenessCaptureResult} for the
 * capture screen and the app.
 *
 * <p><b>Threading</b> (docs/design/android-liveness.md, section 6). Every session call runs on this runner's single
 * {@code liveness-worker} thread, which owns the session. The frame source (CameraX or replay) runs on its own
 * thread and only puts frames into a {@link FrameHandOff} that keeps the newest frame; the driver moves them into
 * the session and calls {@code tick()} at least every {@value #TICK_MS} ms. {@link Listener} callbacks come from the
 * worker; the screen forwards them to the main thread.
 *
 * <p><b>Capture</b>. When the session reaches {@code CAPTURE_READY} the worker copies the session's capture
 * frame, lets the device sign it (the built-in L0 camera signs that very frame), decodes the image back out of
 * the ISO record and binds it to the session ({@code LivenessSession.bind}). Only a bound capture is a pass.
 *
 * <p><b>Retries</b> follow the driver's {@code LivenessTransaction}: failures that count use an attempt; device,
 * engine, quality and cancellation failures do not. Counted failures of the same subject on an earlier screen
 * ({@code priorAttempts}) and an earlier suspected attack continue in the same transaction, so "Attempt n of m",
 * the last attempt and the override offer are the same across screens. After the last attempt audit
 * {@code REG-EVT-LV11} is written once and the result reports {@code on_max_failure}.
 *
 * <p><b>Dim light</b>. The runner always passes the transaction's dim-light count to each session
 * ({@code LivenessSessionDriver.Builder#trackLowLight}), which turns on the engine's low-light rule: a PAD rejection
 * of a dark face ends as {@code QUALITY} with {@code LOW_LIGHT} ("Improve lighting"), not counted, up to
 * {@code passive.low_light_max_uncounted} times since the last counted failure; the next one counts. The count of
 * earlier screens ({@code priorLowLight}) continues here, so closing and reopening the screen gives no new
 * uncounted tries, and each result reports it ({@link LivenessCaptureResult#lowLightRetries()}).
 */
public final class LivenessRunner implements CaptureRunner {

    /** Longest interval between two {@code tick()} calls. */
    public static final long TICK_MS = LivenessSessionDriver.TICK_MS;
    /** Longest wait for the engine to load before a session fails with {@code NOT_READY}. */
    static final long ENGINE_WAIT_MS = 30_000L;
    /** Longest wait for the worker when binding; the binding normally runs on the worker itself. */
    static final long BIND_TIMEOUT_MS = 10_000L;

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
    private final FaceCaptureDevice device;
    private final ImageDecoder decoder;
    private final AuditSink audit;
    private final Clock clock;
    private final Listener listener;
    private final LivenessSessionDriver driver;
    private final AtomicBoolean started = new AtomicBoolean();

    /** Timer of the running session; worker thread only. */
    private FrameTimer timer;
    /** Sessions that ended so far, for the frame-time log; worker thread only. */
    private int sessions;

    private volatile LivenessCaptureResult lastResult;
    private volatile String lastFrameTimes = "";

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
     * @param blockedMessageKey non-null when liveness must not run on this device (for example
     *                          {@code liveness.error.emulator}): every attempt then fails with the device error
     *                          {@code EMULATOR_BLOCKED}, shown as {@code liveness.error.emulator}, without a retry
     * @param listener          receiver of states and outcomes
     */
    public LivenessRunner(LivenessPolicy policy, String subjectRef, EngineLoader engines, FaceCaptureDevice device,
                          ImageDecoder decoder, AuditSink audit, DiagnosticSink diagnostics, Clock clock,
                          String blockedMessageKey, Listener listener) {
        this(policy, subjectRef, engines, device, decoder, audit, diagnostics, clock, blockedMessageKey, 0, false,
                listener);
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
     * @param blockedMessageKey non-null when liveness must not run on this device, or {@code null}
     * @param priorAttempts     attempts the subject already used for this capture, 0 to {@code max_retries - 1}
     *                          (values outside are clamped)
     * @param listener          receiver of states and outcomes
     */
    public LivenessRunner(LivenessPolicy policy, String subjectRef, EngineLoader engines, FaceCaptureDevice device,
                          ImageDecoder decoder, AuditSink audit, DiagnosticSink diagnostics, Clock clock,
                          String blockedMessageKey, int priorAttempts, Listener listener) {
        this(policy, subjectRef, engines, device, decoder, audit, diagnostics, clock, blockedMessageKey,
                priorAttempts, false, 0, listener);
    }

    /**
     * Creates a runner that continues a transaction started on an earlier screen, including whether one of the
     * earlier failures was a suspected attack (PAD, frame integrity or binding), which rules out the supervisor
     * override; nothing happens until {@link #start()}.
     *
     * @param policy                resolved policy of the workflow
     * @param subjectRef            registration ID or user ID
     * @param engines               engine loader
     * @param device                face device
     * @param decoder               image decoder (for the image inside the signed capture)
     * @param audit                 audit sink
     * @param diagnostics           diagnostic sink (no-op unless diagnostics are enabled)
     * @param clock                 session clock
     * @param blockedMessageKey     non-null when liveness must not run on this device, or {@code null}
     * @param priorAttempts         attempts the subject already used for this capture, 0 to
     *                              {@code max_retries - 1} (values outside are clamped)
     * @param priorSuspectedAttack  whether an earlier screen saw a suspected attack for this subject and capture
     * @param listener              receiver of states and outcomes
     */
    public LivenessRunner(LivenessPolicy policy, String subjectRef, EngineLoader engines, FaceCaptureDevice device,
                          ImageDecoder decoder, AuditSink audit, DiagnosticSink diagnostics, Clock clock,
                          String blockedMessageKey, int priorAttempts, boolean priorSuspectedAttack,
                          Listener listener) {
        this(policy, subjectRef, engines, device, decoder, audit, diagnostics, clock, blockedMessageKey,
                priorAttempts, priorSuspectedAttack, 0, listener);
    }

    /**
     * Creates a runner that continues a transaction started on an earlier screen: its counted failures, whether one
     * of them was a suspected attack (PAD, frame integrity or binding), which rules out the supervisor override, and
     * the dim-light results since its last counted failure; nothing happens until {@link #start()}.
     *
     * @param policy                resolved policy of the workflow
     * @param subjectRef            registration ID or user ID
     * @param engines               engine loader
     * @param device                face device
     * @param decoder               image decoder (for the image inside the signed capture)
     * @param audit                 audit sink
     * @param diagnostics           diagnostic sink (no-op unless diagnostics are enabled)
     * @param clock                 session clock
     * @param blockedMessageKey     non-null when liveness must not run on this device, or {@code null}
     * @param priorAttempts         attempts the subject already used for this capture, 0 to
     *                              {@code max_retries - 1} (values outside are clamped)
     * @param priorSuspectedAttack  whether an earlier screen saw a suspected attack for this subject and capture
     * @param priorLowLight         uncounted dim-light results of earlier screens since their last counted failure,
     *                              0 to {@code LivenessTransaction.maxPriorLowLightRetries()} (values outside are
     *                              clamped)
     * @param listener              receiver of states and outcomes
     */
    public LivenessRunner(LivenessPolicy policy, String subjectRef, EngineLoader engines, FaceCaptureDevice device,
                          ImageDecoder decoder, AuditSink audit, DiagnosticSink diagnostics, Clock clock,
                          String blockedMessageKey, int priorAttempts, boolean priorSuspectedAttack,
                          int priorLowLight, Listener listener) {
        this.policy = Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(engines, "engines");
        this.device = Objects.requireNonNull(device, "device");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.audit = Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = Objects.requireNonNull(listener, "listener");
        boolean blocked = blockedMessageKey != null;
        int prior = Math.max(0, Math.min(priorAttempts, policy.maxRetries() - 1));
        int lowLight = Math.max(0, Math.min(priorLowLight, LivenessTransaction.maxPriorLowLightRetries()));
        LivenessSessionDriver.Builder b = LivenessSessionDriver.builder(policy, subjectRef)
                .engine(blocked ? () -> BlockedEngine.INSTANCE : () -> awaitEngine(engines))
                .device(() -> {
                    if (blocked) {
                        throw new DeviceException(DeviceError.of(DeviceErrorCode.EMULATOR_BLOCKED,
                                "liveness is blocked on this device"));
                    }
                    return device;
                })
                .audit(audit)
                .diagnosticSink(diagnostics)
                .clock(clock)
                .executor(newWorker())
                .frameSinkDecorator(this::timed)
                .trackLowLight(true)
                .captureHandler(this::captureAndBind)
                .errorReporter((what, error) -> Log.e(TAG, what + ": " + error.getClass().getSimpleName()))
                .listener(new LivenessSessionDriver.Listener() {
                    @Override
                    public void onUiState(LivenessUiState state) {
                        LivenessRunner.this.listener.onUiState(state);
                    }

                    @Override
                    public void onAttemptEnded(AttemptOutcome outcome) {
                        attemptEnded(outcome);
                    }
                });
        if (prior > 0 || priorSuspectedAttack || lowLight > 0) {
            b.priorFailures(prior, priorSuspectedAttack, lowLight);
        }
        this.driver = b.build();
    }

    /**
     * Opens the first session. A later call (for example after the camera permission was granted again) opens the
     * next session when a retry is available, like {@link #retry()}.
     */
    @Override
    public void start() {
        if (started.compareAndSet(false, true)) {
            driver.start();
        } else {
            driver.retry();
        }
    }

    /**
     * Opens the next session after a failed attempt.
     *
     * @return {@code false} if no retry is available (closed, running, passed or no attempts left)
     */
    @Override
    public boolean retry() {
        return started.get() && driver.retry();
    }

    /** Cancels the running session, if any; the attempt ends with {@code CANCELLED}. Safe from any thread. */
    @Override
    public void cancel() {
        driver.cancel();
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
        driver.close();
    }

    // ------------------------------------------------------------------ worker thread

    private static ScheduledExecutorService newWorker() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "liveness-worker");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** Waits for the engine; a model integrity failure is audited here, the driver audits the engine error. */
    private LivenessEngine awaitEngine(EngineLoader engines) throws EngineException {
        try {
            return engines.await(ENGINE_WAIT_MS);
        } catch (EngineException e) {
            if (e.getErrorCode().isModelIntegrityFailure()) {
                audit.emit(new AuditPayloadBuilder(policy.workflow(), "", clock).modelIntegrityFailed("bundle"));
            }
            throw e;
        }
    }

    private FrameSink timed(FrameSink session) {
        FrameTimer t = new FrameTimer(session, System::nanoTime);
        timer = t;
        return t;
    }

    private void captureAndBind(CaptureHandOff handOff) {
        LivenessPassToken token = handOff.passToken();
        Optional<FaceFrame> selected = handOff.captureFrame();
        if (!selected.isPresent()) {
            handOff.captureFailed(DeviceError.of(DeviceErrorCode.CAPTURE_FAILED, "no capture frame"));
            return;
        }
        // The driver hands out a copy; the session wipes its own frame at close().
        FaceFrame frame = selected.get();
        handOff.captureStarted();
        ImageBuffer image = null;
        try {
            CaptureRequest.Builder request = CaptureRequest.builder().transactionId(UUID.randomUUID().toString())
                    .deviceSubId(StreamRequest.FACE_DEVICE_SUB_ID).sourceFrame(frame);
            if (device.describe().isPadCapable()) {
                request.pad(PadRequest.of(true, PadMethod.PASSIVE, token.nonce()));
            }
            SignedFaceCapture capture = device instanceof BuiltInCameraL0Device
                    ? ((BuiltInCameraL0Device) device).capture(request.build(), handOff.sessionId(), token.nonce())
                    : device.capture(request.build());
            image = decoder.decode(FaceIsoRecords.imageOf(capture.isoRecord()));
            handOff.attachCapture(capture);
            handOff.bind(BindingRequest.from(capture, image, token), BIND_TIMEOUT_MS);
        } catch (DeviceException e) {
            handOff.captureFailed(e.getError());
        } catch (TimeoutException e) {
            handOff.captureFailed(DeviceError.of(DeviceErrorCode.CAPTURE_FAILED, "binding did not finish", e));
        } catch (ImageDecodeException | IOException | RuntimeException e) {
            handOff.captureFailed(DeviceError.of(DeviceErrorCode.CAPTURE_FAILED, "captured image unreadable", e));
        } finally {
            frame.wipe();
            if (image != null) {
                image.wipe();
            }
        }
    }

    private void attemptEnded(AttemptOutcome outcome) {
        LivenessCaptureResult result = toCaptureResult(outcome);
        lastResult = result;
        Log.i(TAG, "Liveness attempt ended: " + result);
        FrameTimer t = timer;
        timer = null;
        if (outcome.sessionId().isPresent()) {
            sessions++;
        }
        if (t != null && t.frames() > 0) {
            lastFrameTimes = t.summary();
            Log.i(TAG, "Liveness frame time " + policy.workflow().id() + " session " + sessions + ": "
                    + lastFrameTimes);
        }
        listener.onAttemptEnded(result);
    }

    /**
     * Maps a driver outcome to the result the capture screen and the app read. Package-private for tests.
     *
     * @param outcome the outcome
     * @return the result
     */
    LivenessCaptureResult toCaptureResult(AttemptOutcome outcome) {
        Optional<SignedFaceCapture> capture = outcome.capture();
        boolean pass = outcome.isPass() && capture.isPresent();
        Decision decision = pass ? Decision.PASS : (outcome.isPass() ? Decision.ERROR : outcome.decision());
        LivenessCaptureResult.Builder b = LivenessCaptureResult.builder(policy.workflow(), decision)
                .sessionId(outcome.sessionId().orElse(null))
                .attempts(outcome.attemptsUsed(), outcome.maxRetries())
                .lowLightRetries(pass ? 0 : outcome.lowLightRetries())
                .retryAllowed(!pass && outcome.isRetryAllowed())
                .overrideAllowed(!pass && outcome.isOverrideAllowed())
                .lockRequired(!pass && outcome.isLockRequired())
                .onMaxFailure(outcome.onMaxFailure())
                .messageKey(outcome.messageKey().orElse(null));
        outcome.failureReason().ifPresent(b::failureReason);
        Optional<LivenessResult> result = outcome.result();
        if (result.isPresent()) {
            List<String> codes = new ArrayList<>();
            for (ReasonCode c : result.get().reasonCodes()) {
                codes.add(c.name());
            }
            b.reasonCodes(codes).evidence(PacketEvidenceKeys.of(result.get()));
        }
        if (pass) {
            String response = device instanceof BuiltInCameraL0Device
                    ? ((BuiltInCameraL0Device) device).lastResponseJson() : null;
            boolean provisioned = device instanceof BuiltInCameraL0Device
                    && ((BuiltInCameraL0Device) device).isProvisioned();
            b.capture(capture.get().transactionId(), capture.get().isoRecord(), response, provisioned)
                    .tokenNonce(outcome.tokenNonce().orElse(null));
        }
        return b.build();
    }

    /**
     * Engine handed to the driver when liveness is blocked on this device: the device supplier refuses first, so no
     * session is ever opened and the engine is not loaded for nothing.
     */
    private static final class BlockedEngine implements LivenessEngine {

        static final BlockedEngine INSTANCE = new BlockedEngine();

        @Override
        public EngineInfo info() {
            return EngineInfo.builder("blocked", "0").build();
        }

        @Override
        public LivenessSession open(SessionRequest request, LivenessListener listener) throws EngineException {
            throw new EngineException(EngineErrorCode.UNSUPPORTED, "liveness is blocked on this device");
        }
    }
}
