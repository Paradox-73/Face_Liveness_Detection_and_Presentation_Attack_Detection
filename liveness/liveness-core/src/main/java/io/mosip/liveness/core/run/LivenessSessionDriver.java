/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.run;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.DeviceStatus;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceCaptureDevice;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.liveness.core.engine.LivenessTransaction;
import io.mosip.liveness.core.engine.LivenessUiStateMapper;
import io.mosip.liveness.core.stream.FrameHandOff;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.PromptIcon;
import io.mosip.liveness.core.ui.Severity;
import io.mosip.liveness.core.ui.UiPhase;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

/**
 * One liveness transaction for one person in one workflow: the first attempt and its retries, from the device
 * stream to the bound signed capture. Both registration clients run their sessions through this driver, so retries
 * are counted the same way on the desktop and on Android.
 *
 * <p><b>Attempts.</b> The driver keeps one {@link LivenessTransaction}, optionally continuing the counted failures,
 * the suspected-attack mark and the uncounted low-light results of earlier screens
 * ({@link Builder#priorFailures(int, boolean, int)}). Each attempt
 * opens a session with the transaction's next attempt number, records the result and reports an
 * {@link AttemptOutcome}. Failures for PAD, integrity, challenges, timeouts, below-threshold scores and binding use
 * an attempt; device, engine, quality and cancellation failures never do, and neither does an attempt that could
 * not open a session. After the last attempt audit {@code REG-EVT-LV11} is written exactly once (by the engine, or
 * here for an engine that does not).
 *
 * <p><b>Threading.</b> Every session call runs on one worker thread (the
 * {@link Builder#executor(ScheduledExecutorService)} hook, by default a daemon thread named
 * {@code liveness-worker}), which owns the session. The device's reader thread only puts frames into a
 * {@link FrameHandOff} that keeps the newest frame; the worker moves them into the session and calls {@code tick()}
 * at least every {@value #TICK_MS} ms. {@link Listener} callbacks go through the
 * {@link Builder#listenerExecutor(Executor)} hook (by default straight on the worker). Public methods are safe from
 * any thread.
 *
 * <p><b>Capture.</b> When the session reaches {@code CAPTURE_READY} the driver stops moving frames and calls the
 * {@link CaptureHandler} with a {@link CaptureHandOff}; the client takes the signed capture and binds it, at once or
 * later from another thread. With {@link Builder#releaseDeviceBeforeCapture(long)} the stream is closed first and
 * the device is polled until it reports Ready (SBI devices are Busy while they stream). The driver keeps ticking
 * the session until it ends, so an unused pass token expires on time.
 *
 * <p>The driver makes no liveness decision of its own: decisions, reason codes and the UI states of a running
 * session come from the engine.
 */
public final class LivenessSessionDriver implements AutoCloseable {

    /** Longest interval between two {@code tick()} calls, in milliseconds. */
    public static final long TICK_MS = 100L;

    /** Supplies the engine; may block while the models load. */
    @FunctionalInterface
    public interface EngineSupplier {

        /**
         * Returns the engine.
         *
         * @return the engine, never {@code null}
         * @throws EngineException if no engine can be used; a model integrity failure ends the transaction's retries
         */
        LivenessEngine get() throws EngineException;
    }

    /** Supplies the face device; called once, before the first session. */
    @FunctionalInterface
    public interface DeviceSupplier {

        /**
         * Returns the device.
         *
         * @return the device, never {@code null}
         * @throws DeviceException if no device can be used; {@link DeviceErrorCode#EMULATOR_BLOCKED} offers no retry
         */
        FaceCaptureDevice get() throws DeviceException;
    }

    /** Receives what the driver reports, through the listener executor. */
    public interface Listener {

        /**
         * Receives a new state to render: the session's own states, the starting state of each attempt and the error
         * state of an attempt that could not open a session.
         *
         * @param state the state
         */
        void onUiState(LivenessUiState state);

        /**
         * Receives the outcome of an attempt; {@link AttemptOutcome#isRetryAllowed()} tells whether
         * {@link LivenessSessionDriver#retry()} may follow.
         *
         * @param outcome the outcome
         */
        void onAttemptEnded(AttemptOutcome outcome);
    }

    /** Takes the signed capture of an attempt that reached {@code CAPTURE_READY}. */
    @FunctionalInterface
    public interface CaptureHandler {

        /**
         * Starts the signed capture. Runs on the worker thread and must not block for long; the capture may be
         * bound here or later from another thread.
         *
         * @param handOff the capture step of the attempt
         */
        void onCaptureReady(CaptureHandOff handOff);
    }

    /** Receives a private copy of every stream frame for the preview. */
    @FunctionalInterface
    public interface PreviewListener {

        /**
         * Receives a copy of a stream frame on the device's reader thread; it must return quickly.
         *
         * @param frame the copy, owned by the receiver
         */
        void onPreviewFrame(FaceFrame frame);
    }

    /** Receives failures the driver contained (a listener or capture handler that threw, a session that failed). */
    @FunctionalInterface
    public interface ErrorReporter {

        /**
         * Reports a contained failure. The text never holds subject data.
         *
         * @param what  what failed
         * @param error the failure
         */
        void report(String what, Throwable error);
    }

    private final LivenessPolicy policy;
    private final String subjectRef;
    private final EngineSupplier engines;
    private final DeviceSupplier devices;
    private final AuditSink audit;
    private final DiagnosticSink diagnostics;
    private final Clock clock;
    private final Listener listener;
    private final CaptureHandler captureHandler;
    private final PreviewListener preview;
    private final UnaryOperator<FrameSink> sinkDecorator;
    private final Executor listenerExecutor;
    private final ScheduledExecutorService worker;
    private final ErrorReporter errors;
    private final long deviceReadyTimeoutMs;
    private final boolean trackLowLight;
    private final LivenessTransaction transaction;
    private final ThreadLocal<Boolean> workerFlag = new ThreadLocal<>();
    private final Object lock = new Object();

    /** Worker thread only. */
    private FaceCaptureDevice device;
    /** Worker thread only. */
    private int sessionsOpened;

    private volatile Attempt current;
    private volatile AttemptOutcome lastOutcome;
    private volatile boolean opening;
    private volatile boolean cancelRequested;
    private volatile boolean started;
    private volatile boolean closed;

    private LivenessSessionDriver(Builder b) {
        this.policy = b.policy;
        this.subjectRef = b.subjectRef;
        this.engines = Objects.requireNonNull(b.engines, "engine supplier");
        this.devices = Objects.requireNonNull(b.devices, "device supplier");
        this.audit = Objects.requireNonNull(b.audit, "audit sink");
        this.listener = Objects.requireNonNull(b.listener, "listener");
        this.captureHandler = Objects.requireNonNull(b.captureHandler, "capture handler");
        this.diagnostics = b.diagnostics;
        this.clock = b.clock;
        this.preview = b.preview;
        this.sinkDecorator = b.sinkDecorator;
        this.listenerExecutor = b.listenerExecutor;
        this.errors = b.errors;
        this.deviceReadyTimeoutMs = b.deviceReadyTimeoutMs;
        this.trackLowLight = b.trackLowLight;
        this.transaction = new LivenessTransaction(policy, b.priorCountedFailures, b.priorSuspectedAttack,
                b.priorLowLightRetries);
        this.worker = b.executor != null ? b.executor : defaultWorker();
    }

    /**
     * Starts a builder.
     *
     * @param policy     resolved policy of the workflow, used by every attempt
     * @param subjectRef registration ID or user ID the sessions are bound to
     * @return a builder
     */
    public static Builder builder(LivenessPolicy policy, String subjectRef) {
        return new Builder(policy, subjectRef);
    }

    private static ScheduledExecutorService defaultWorker() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "liveness-worker");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /**
     * Opens the first session.
     *
     * @throws IllegalStateException if the driver was already started
     */
    public void start() {
        synchronized (lock) {
            if (started) {
                throw new IllegalStateException("liveness driver already started");
            }
            started = true;
            if (closed) {
                return;
            }
            opening = true;
            cancelRequested = false;
        }
        submit(this::openSession);
    }

    /**
     * Opens the next session after a failed attempt.
     *
     * @return {@code false} if no retry is available: closed, an attempt is running, the last outcome passed, used
     *         the last attempt, or does not allow a retry
     */
    public boolean retry() {
        synchronized (lock) {
            AttemptOutcome o = lastOutcome;
            if (closed || opening || current != null || o == null || !o.isRetryAllowed()) {
                return false;
            }
            opening = true;
            cancelRequested = false;
        }
        submit(this::openSession);
        return true;
    }

    /**
     * Cancels the running attempt, if any; it ends with {@code CANCELLED}, which does not use an attempt. An attempt
     * that is still opening its session ends cancelled as soon as it can.
     */
    public void cancel() {
        if (opening) {
            cancelRequested = true;
        }
        Attempt a = current;
        if (a != null && !a.finished) {
            a.session.cancel();
            submit(() -> {
                if (!a.finished) {
                    endNow(a);
                }
            });
        }
    }

    /**
     * Cancels the running attempt, closes the stream and stops the worker. The cancelled attempt still reports its
     * outcome, without a retry. Idempotent.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        Attempt a = current;
        if (a != null) {
            a.session.cancel();
        }
        try {
            worker.execute(onWorker(() -> {
                Attempt c = current;
                if (c != null && !c.finished) {
                    endNow(c);
                }
            }));
        } catch (RejectedExecutionException ignored) {
            // Already stopped.
        }
        worker.shutdown();
    }

    /**
     * Waits until the worker has stopped after {@link #close()}.
     *
     * @param millis longest wait
     * @return {@code true} if the worker stopped
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitTermination(long millis) throws InterruptedException {
        return worker.awaitTermination(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns the outcome of the last attempt.
     *
     * @return the outcome; empty while the first attempt runs
     */
    public Optional<AttemptOutcome> lastOutcome() {
        return Optional.ofNullable(lastOutcome);
    }

    /**
     * Tells whether an attempt is opening or running (capture step included).
     *
     * @return {@code true} until the attempt's outcome is reported
     */
    public boolean isSessionActive() {
        return opening || current != null;
    }

    /**
     * Tells whether the driver is closed.
     *
     * @return {@code true} after {@link #close()}
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Returns the policy used by every attempt.
     *
     * @return the policy
     */
    public LivenessPolicy policy() {
        return policy;
    }

    /**
     * Returns the workflow.
     *
     * @return the workflow
     */
    public Workflow workflow() {
        return policy.workflow();
    }

    // ------------------------------------------------------------------ worker thread

    private void openSession() {
        if (closed || current != null || !transaction.canRetry()) {
            opening = false;
            return;
        }
        int attempt = transaction.nextAttempt();
        if (cancelRequested) {
            noSession(attempt, FailureReason.CANCELLED, null, null, null, null, null, null, true);
            return;
        }
        dispatch(() -> listener.onUiState(new LivenessUiStateMapper(policy, attempt).starting()));
        LivenessEngine engine;
        try {
            engine = Objects.requireNonNull(engines.get(), "engine");
        } catch (EngineException e) {
            EngineErrorCode code = e.getErrorCode();
            noSession(attempt, FailureReason.ENGINE, null, code, UiPhase.FAILED, MessageKeys.ERROR_ENGINE,
                    PromptIcon.ERROR, events("").engineError(code, e.getClass().getSimpleName()),
                    !code.isModelIntegrityFailure());
            return;
        } catch (RuntimeException e) {
            errors.report("liveness engine unavailable", e);
            noSession(attempt, FailureReason.ENGINE, null, EngineErrorCode.INTERNAL, UiPhase.FAILED,
                    MessageKeys.ERROR_ENGINE, PromptIcon.ERROR,
                    events("").engineError(EngineErrorCode.INTERNAL, e.getClass().getSimpleName()), true);
            return;
        }
        try {
            if (device == null) {
                device = Objects.requireNonNull(devices.get(), "device");
            }
        } catch (DeviceException e) {
            deviceUnavailable(attempt, e.getErrorCode());
            return;
        } catch (RuntimeException e) {
            errors.report("liveness device unavailable", e);
            deviceUnavailable(attempt, DeviceErrorCode.UNKNOWN);
            return;
        }
        if (deviceReadyTimeoutMs > 0 && sessionsOpened > 0) {
            waitForDeviceReady();
        }
        if (closed || cancelRequested) {
            noSession(attempt, FailureReason.CANCELLED, null, null, null, null, null, null, true);
            return;
        }
        Attempt a = new Attempt(attempt);
        try {
            SessionRequest.Builder request = SessionRequest.builder(policy, subjectRef, device.describe())
                    .attempt(attempt).clock(clock).diagnosticSink(diagnostics);
            if (trackLowLight) {
                request.lowLightRetries(transaction.lowLightRetries());
            }
            a.session = Objects.requireNonNull(engine.open(request.build(), a), "session");
        } catch (EngineException e) {
            errors.report("liveness session could not be opened", e);
            noSession(attempt, FailureReason.ENGINE, null, e.getErrorCode(), UiPhase.FAILED, MessageKeys.ERROR_ENGINE,
                    PromptIcon.ERROR, null, true);
            return;
        } catch (RuntimeException e) {
            errors.report("liveness session could not be opened", e);
            noSession(attempt, FailureReason.ENGINE, null, null, UiPhase.FAILED, MessageKeys.ERROR_ENGINE,
                    PromptIcon.ERROR, null, true);
            return;
        }
        sessionsOpened++;
        current = a;
        opening = false;
        if (cancelRequested) {
            a.session.cancel();
        }
        try {
            a.session.start();
        } catch (RuntimeException e) {
            errors.report("liveness session failed", e);
            endNow(a);
            return;
        }
        if (!afterCall(a)) {
            startStream(a);
        }
    }

    private void deviceUnavailable(int attempt, DeviceErrorCode code) {
        boolean blocked = code == DeviceErrorCode.EMULATOR_BLOCKED;
        noSession(attempt, FailureReason.DEVICE, code, null, UiPhase.DEVICE_ERROR,
                LivenessUiStateMapper.deviceMessageKey(code), blocked ? PromptIcon.LOCKED : PromptIcon.CAMERA_OFF,
                events("").deviceError(code), !blocked);
    }

    private void startStream(Attempt a) {
        try {
            a.sink = Objects.requireNonNull(sinkDecorator.apply(a.session), "frame sink");
            a.handOff = FrameHandOff.latestOnly();
            a.source = Objects.requireNonNull(device.openStream(StreamRequest.builder()
                    .streamTimeoutMs(policy.streamTimeoutMs()).reconnectAttempts(policy.deviceReconnectAttempts())
                    .build()), "frame source");
            a.source.start(preview == null ? a.handOff : new PreviewTee(a.handOff));
        } catch (DeviceException e) {
            a.session.onError(e.getError());
            settleOrEnd(a);
            return;
        } catch (RuntimeException e) {
            a.session.onError(DeviceError.of(DeviceErrorCode.STREAM_OPEN_FAILED, "stream could not start", e));
            settleOrEnd(a);
            return;
        }
        submit(() -> pump(a));
    }

    private void pump(Attempt a) {
        if (a.finished || a != current || a.captureHandled) {
            return;
        }
        try {
            a.handOff.deliverNext(a.sink, TICK_MS);
            a.session.tick();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            endNow(a);
            return;
        } catch (RuntimeException e) {
            errors.report("liveness session failed", e);
            endNow(a);
            return;
        }
        if (!afterCall(a)) {
            submit(() -> pump(a));
        }
    }

    /**
     * Acts on the session state after a session call: the capture step when ready, the outcome when ended.
     *
     * @return {@code true} when the frame loop must stop
     */
    private boolean afterCall(Attempt a) {
        if (a.finished) {
            return true;
        }
        SessionState s = a.session.state();
        if (s.isTerminal()) {
            finish(a);
            return true;
        }
        if (!a.captureHandled && (s == SessionState.CAPTURE_READY || s == SessionState.CAPTURING)) {
            a.captureHandled = true;
            beginCapture(a);
            return true;
        }
        return a.captureHandled;
    }

    private void beginCapture(Attempt a) {
        Optional<LivenessPassToken> token = a.session.passToken();
        if (!token.isPresent()) {
            a.session.captureFailed(DeviceError.of(DeviceErrorCode.CAPTURE_FAILED, "no pass token"));
            settleOrEnd(a);
            return;
        }
        a.token = token.get();
        if (deviceReadyTimeoutMs > 0) {
            closeSource(a);
            if (!waitForDeviceReady()) {
                a.session.captureFailed(DeviceError.of(DeviceErrorCode.NOT_READY,
                        "device did not report Ready within " + deviceReadyTimeoutMs + " ms"));
                settleOrEnd(a);
                return;
            }
            a.session.captureStarted();
        }
        a.inHandler = true;
        try {
            captureHandler.onCaptureReady(new CaptureHandOff(this, a, a.token));
        } catch (RuntimeException e) {
            errors.report("liveness capture hand-off failed", e);
            if (!a.session.state().isTerminal()) {
                a.session.captureFailed(DeviceError.of(DeviceErrorCode.CAPTURE_FAILED, "capture hand-off failed", e));
            }
        } finally {
            a.inHandler = false;
        }
        if (settle(a)) {
            return;
        }
        try {
            a.ticker = worker.scheduleWithFixedDelay(onWorker(() -> {
                if (!a.finished) {
                    try {
                        a.session.tick();
                    } catch (RuntimeException e) {
                        errors.report("liveness session failed", e);
                        endNow(a);
                        return;
                    }
                    settle(a);
                }
            }), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Closing: the close task ends the attempt.
        }
    }

    private boolean waitForDeviceReady() {
        long polls = deviceReadyTimeoutMs / TICK_MS;
        for (long i = 0; ; i++) {
            DeviceStatus status;
            try {
                status = device.status();
            } catch (RuntimeException e) {
                status = DeviceStatus.NOT_FOUND;
            }
            if (status == DeviceStatus.READY) {
                return true;
            }
            if (i >= polls) {
                return false;
            }
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Builds the outcome once the session has ended; worker thread. */
    private boolean settle(Attempt a) {
        if (a.finished) {
            return true;
        }
        if (a.session.state().isTerminal()) {
            finish(a);
            return true;
        }
        return false;
    }

    private void settleOrEnd(Attempt a) {
        if (!settle(a)) {
            endNow(a);
        }
    }

    /** Cancels the session and reports the attempt even if the session does not reach a terminal state. */
    private void endNow(Attempt a) {
        if (a.finished) {
            return;
        }
        try {
            a.session.cancel();
            a.session.tick();
        } catch (RuntimeException e) {
            errors.report("liveness session failed", e);
        }
        if (!settle(a)) {
            finish(a);
        }
    }

    private void finish(Attempt a) {
        if (a.finished) {
            return;
        }
        a.finished = true;
        if (a.ticker != null) {
            a.ticker.cancel(false);
        }
        closeSource(a);
        LivenessResult result = a.session.result().orElse(null);
        a.result = result;
        boolean wasExhausted = transaction.status() == LivenessTransaction.Status.EXHAUSTED;
        if (result != null && transaction.canRetry()) {
            try {
                transaction.record(result);
            } catch (IllegalArgumentException e) {
                errors.report("liveness result of another workflow ignored", e);
            }
        }
        // The engine writes REG-EVT-LV11 itself when the last counted attempt fails; write it here only for an
        // engine that did not, so that the audit trail holds it exactly once.
        if (!wasExhausted && transaction.status() == LivenessTransaction.Status.EXHAUSTED && !a.maxRetriesAudited) {
            emit(events(a.session.sessionId()).maxRetries(transaction.attemptsUsed(), transaction.onMaxFailure()));
        }
        AttemptOutcome.Builder b = AttemptOutcome.builder(policy.workflow(), transaction, policy.maxRetries(), a.number)
                .result(a.session.sessionId(), result).messageKey(a.lastMessageKey)
                .retryAllowed(transaction.canRetry() && !closed);
        if (result != null && result.isPass()) {
            b.pass(a.capture, a.token == null ? null : a.token.nonce());
        }
        AttemptOutcome outcome = b.build();
        lastOutcome = outcome;
        try {
            a.session.close();
        } catch (RuntimeException e) {
            errors.report("liveness session did not close", e);
        }
        if (current == a) {
            current = null;
        }
        dispatch(() -> listener.onAttemptEnded(outcome));
        if (closed) {
            worker.shutdown();
        }
    }

    private void noSession(int attempt, FailureReason reason, DeviceErrorCode deviceCode, EngineErrorCode engineCode,
                           UiPhase phase, String messageKey, PromptIcon icon, LivenessAuditEvent event,
                           boolean retry) {
        boolean allowed = retry && !closed && transaction.canRetry();
        if (event != null) {
            emit(event);
        }
        AttemptOutcome outcome = AttemptOutcome.builder(policy.workflow(), transaction, policy.maxRetries(), attempt)
                .noSession(reason, deviceCode, engineCode).messageKey(messageKey).retryAllowed(allowed).build();
        lastOutcome = outcome;
        opening = false;
        if (phase != null) {
            LivenessUiState.Builder state = LivenessUiState.builder(phase, messageKey).promptIcon(icon)
                    .severity(Severity.ERROR).retryAvailable(allowed)
                    .attempt(Math.min(transaction.attemptsUsed() + 1, policy.maxRetries()), policy.maxRetries());
            if (allowed) {
                state.guidance(MessageKeys.RETRY_AVAILABLE);
            }
            LivenessUiState built = state.build();
            dispatch(() -> listener.onUiState(built));
        }
        dispatch(() -> listener.onAttemptEnded(outcome));
    }

    private AuditPayloadBuilder events(String sessionId) {
        return new AuditPayloadBuilder(policy.workflow(), sessionId, clock);
    }

    private void emit(LivenessAuditEvent event) {
        try {
            audit.emit(event);
        } catch (RuntimeException e) {
            errors.report("liveness audit event not stored", e);
        }
    }

    private static void closeSource(Attempt a) {
        FaceFrameSource s = a.source;
        a.source = null;
        if (s != null) {
            s.close();
        }
    }

    private void dispatch(Runnable callback) {
        Runnable safe = () -> {
            try {
                callback.run();
            } catch (RuntimeException e) {
                errors.report("liveness listener failed", e);
            }
        };
        try {
            listenerExecutor.execute(safe);
        } catch (RuntimeException e) {
            errors.report("liveness listener dispatch failed", e);
        }
    }

    private Runnable onWorker(Runnable task) {
        return () -> {
            workerFlag.set(Boolean.TRUE);
            try {
                task.run();
            } catch (RuntimeException e) {
                errors.report("liveness worker task failed", e);
            } finally {
                workerFlag.remove();
            }
        };
    }

    private boolean onWorkerThread() {
        return Boolean.TRUE.equals(workerFlag.get());
    }

    private void submit(Runnable task) {
        try {
            worker.execute(onWorker(task));
        } catch (RejectedExecutionException e) {
            // The driver is closed; nothing more to do.
        }
    }

    // ------------------------------------------------------------------ capture hand-off

    Optional<FaceFrame> captureFrame(Attempt a) {
        if (!a.inHandler || !onWorkerThread()) {
            throw new IllegalStateException("captureFrame() is only available inside onCaptureReady");
        }
        return a.session.captureFrame().map(LivenessSessionDriver::copy);
    }

    void captureStarted(Attempt a) {
        onWorkerOrQueued(a, () -> a.session.captureStarted());
    }

    void captureFailed(Attempt a, DeviceError error) {
        onWorkerOrQueued(a, () -> a.session.captureFailed(error));
    }

    private void onWorkerOrQueued(Attempt a, Runnable call) {
        Runnable task = () -> {
            if (!a.finished) {
                call.run();
                if (!a.inHandler) {
                    settle(a);
                }
            }
        };
        if (onWorkerThread()) {
            task.run();
        } else {
            submit(task);
        }
    }

    CaptureHandOff.BindOutcome bind(Attempt a, BindingRequest request, long timeoutMs) throws TimeoutException {
        if (onWorkerThread()) {
            return bindOnWorker(a, request);
        }
        FutureTask<CaptureHandOff.BindOutcome> task = new FutureTask<>(() -> bindOnWorker(a, request));
        try {
            worker.execute(onWorker(task));
        } catch (RejectedExecutionException e) {
            throw new TimeoutException("liveness driver is closed");
        }
        try {
            return task.get(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(false);
            throw new TimeoutException("interrupted while waiting for the liveness worker");
        } catch (TimeoutException e) {
            task.cancel(false);
            throw new TimeoutException("liveness worker did not answer within " + timeoutMs + " ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        }
    }

    private CaptureHandOff.BindOutcome bindOnWorker(Attempt a, BindingRequest request) {
        if (a.finished) {
            return new CaptureHandOff.BindOutcome(
                    BindingVerdict.failed(FailureReason.BINDING, ReasonCode.TOKEN_REUSED).build(), a.result);
        }
        a.session.captureStarted();
        BindingVerdict verdict = a.session.bind(request);
        LivenessResult result = a.session.result().orElse(null);
        if (!a.inHandler) {
            settle(a);
        }
        return new CaptureHandOff.BindOutcome(verdict, result);
    }

    private static FaceFrame copy(FaceFrame f) {
        return FaceFrame.builder().data(Arrays.copyOf(f.data(), f.data().length)).format(f.format())
                .size(f.width(), f.height()).rotationDegrees(f.rotationDegrees()).mirrored(f.isMirrored())
                .monotonicNanos(f.monotonicNanos()).sequence(f.sequence()).sourceId(f.sourceId()).build();
    }

    /** One session and its stream. Worker thread only, except the fields marked volatile. */
    final class Attempt implements LivenessListener {

        final int number;
        LivenessSession session;
        FaceFrameSource source;
        FrameHandOff handOff;
        FrameSink sink;
        ScheduledFuture<?> ticker;
        LivenessPassToken token;
        LivenessResult result;
        String lastMessageKey;
        boolean captureHandled;
        boolean inHandler;
        boolean maxRetriesAudited;
        volatile SignedFaceCapture capture;
        volatile boolean finished;

        Attempt(int number) {
            this.number = number;
        }

        @Override
        public void onUi(LivenessUiState state) {
            lastMessageKey = state.messageKey();
            dispatch(() -> listener.onUiState(state));
        }

        @Override
        public void onAudit(LivenessAuditEvent event) {
            if (event.eventId() == AuditEventId.LIVENESS_MAX_RETRIES) {
                maxRetriesAudited = true;
            }
            emit(event);
        }
    }

    /** Sends each stream frame to the preview (a private copy) and to the hand-off. Reader thread. */
    private final class PreviewTee implements FrameSink {

        private final FrameSink target;

        PreviewTee(FrameSink target) {
            this.target = target;
        }

        @Override
        public void onFrame(FaceFrame frame) {
            try {
                preview.onPreviewFrame(copy(frame));
            } catch (RuntimeException e) {
                errors.report("liveness preview failed", e);
            }
            target.onFrame(frame);
        }

        @Override
        public void onError(DeviceError error) {
            target.onError(error);
        }

        @Override
        public void onEnd(EndReason reason) {
            target.onEnd(reason);
        }
    }

    /** Builder for {@link LivenessSessionDriver}. */
    public static final class Builder {

        private final LivenessPolicy policy;
        private final String subjectRef;
        private EngineSupplier engines;
        private DeviceSupplier devices;
        private AuditSink audit;
        private Listener listener;
        private CaptureHandler captureHandler;
        private DiagnosticSink diagnostics = DiagnosticSink.noop();
        private Clock clock = Clock.system();
        private PreviewListener preview;
        private UnaryOperator<FrameSink> sinkDecorator = UnaryOperator.identity();
        private Executor listenerExecutor = Runnable::run;
        private ScheduledExecutorService executor;
        private ErrorReporter errors = (what, error) -> { };
        private long deviceReadyTimeoutMs;
        private boolean trackLowLight;
        private int priorCountedFailures;
        private boolean priorSuspectedAttack;
        private int priorLowLightRetries;

        private Builder(LivenessPolicy policy, String subjectRef) {
            this.policy = Objects.requireNonNull(policy, "policy");
            this.subjectRef = subjectRef == null ? "" : subjectRef;
        }

        /**
         * Sets the engine supplier (required).
         *
         * @param engines supplies the engine before each session
         * @return this builder
         */
        public Builder engine(EngineSupplier engines) {
            this.engines = Objects.requireNonNull(engines, "engines");
            return this;
        }

        /**
         * Sets the device supplier (required).
         *
         * @param devices supplies the face device once
         * @return this builder
         */
        public Builder device(DeviceSupplier devices) {
            this.devices = Objects.requireNonNull(devices, "devices");
            return this;
        }

        /**
         * Sets the audit sink (required); it receives the sessions' events and the driver's own LV11, LV12 and LV13
         * events.
         *
         * @param audit the sink
         * @return this builder
         */
        public Builder audit(AuditSink audit) {
            this.audit = Objects.requireNonNull(audit, "audit");
            return this;
        }

        /**
         * Sets the listener (required).
         *
         * @param listener receiver of UI states and attempt outcomes
         * @return this builder
         */
        public Builder listener(Listener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /**
         * Sets the capture handler (required).
         *
         * @param captureHandler takes and binds the signed capture
         * @return this builder
         */
        public Builder captureHandler(CaptureHandler captureHandler) {
            this.captureHandler = Objects.requireNonNull(captureHandler, "captureHandler");
            return this;
        }

        /**
         * Continues the counted failures of earlier screens or windows for the same workflow and subject; default
         * none. The low-light count starts at 0; {@link #priorFailures(int, boolean, int)} also continues it.
         *
         * @param countedFailures counted failures so far, 0 to {@code max_retries - 1}
         * @param suspectedAttack whether one of them was a PAD, integrity or binding failure
         * @return this builder
         * @throws IllegalArgumentException if {@code countedFailures} is negative or leaves no attempt
         */
        public Builder priorFailures(int countedFailures, boolean suspectedAttack) {
            return priorFailures(countedFailures, suspectedAttack, 0);
        }

        /**
         * Continues the counted failures, the suspected-attack mark and the uncounted low-light results of earlier
         * screens or windows for the same workflow and subject; default none. With {@link #trackLowLight(boolean)}
         * the first session gets {@code lowLightRetries} ({@link SessionRequest.Builder#lowLightRetries(int)}), so
         * closing and reopening the window does not restart the low-light allowance. The client takes the three
         * values from the last {@link AttemptOutcome} of the earlier screen ({@link AttemptOutcome#attemptsUsed()},
         * {@link AttemptOutcome#isSuspectedAttack()}, {@link AttemptOutcome#lowLightRetries()}).
         *
         * @param countedFailures counted failures so far, 0 to {@code max_retries - 1}
         * @param suspectedAttack whether one of them was a PAD, integrity or binding failure
         * @param lowLightRetries uncounted low-light results since the last counted failure, 0 to
         *                        {@link LivenessTransaction#maxPriorLowLightRetries()}
         * @return this builder
         * @throws IllegalArgumentException if {@code countedFailures} is negative or leaves no attempt, or if
         *                                  {@code lowLightRetries} is outside its range
         */
        public Builder priorFailures(int countedFailures, boolean suspectedAttack, int lowLightRetries) {
            if (countedFailures < 0 || countedFailures >= policy.maxRetries()) {
                throw new IllegalArgumentException("prior counted failures must be between 0 and max_retries - 1 ("
                        + (policy.maxRetries() - 1) + "): " + countedFailures);
            }
            int maxLowLight = LivenessTransaction.maxPriorLowLightRetries();
            if (lowLightRetries < 0 || lowLightRetries > maxLowLight) {
                throw new IllegalArgumentException("prior low-light retries must be between 0 and " + maxLowLight
                        + ": " + lowLightRetries);
            }
            this.priorCountedFailures = countedFailures;
            this.priorSuspectedAttack = suspectedAttack;
            this.priorLowLightRetries = lowLightRetries;
            return this;
        }

        /**
         * Sets the clock of the sessions and of the driver's audit events; default {@link Clock#system()}.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Sets the diagnostic sink of the sessions; default discards records.
         *
         * @param diagnostics the sink
         * @return this builder
         */
        public Builder diagnosticSink(DiagnosticSink diagnostics) {
            this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            return this;
        }

        /**
         * Sets the worker. It must run one task at a time on one thread; the driver owns it and shuts it down at
         * {@link LivenessSessionDriver#close()}. Default: a single daemon thread named {@code liveness-worker}.
         *
         * @param executor the worker
         * @return this builder
         */
        public Builder executor(ScheduledExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Sets how listener callbacks are dispatched, for example to the UI thread. It must keep their order.
         * Default: on the worker thread, in the session call that caused them.
         *
         * @param listenerExecutor the dispatcher
         * @return this builder
         */
        public Builder listenerExecutor(Executor listenerExecutor) {
            this.listenerExecutor = Objects.requireNonNull(listenerExecutor, "listenerExecutor");
            return this;
        }

        /**
         * Sends a private copy of every stream frame to the preview; default no preview copies.
         *
         * @param preview receiver of the copies
         * @return this builder
         */
        public Builder previewListener(PreviewListener preview) {
            this.preview = Objects.requireNonNull(preview, "preview");
            return this;
        }

        /**
         * Wraps the session as the receiver of the stream, for example to time the frames. Called on the worker
         * once per session; the returned sink must pass every call on to the session.
         *
         * @param sinkDecorator returns the sink frames are delivered to
         * @return this builder
         */
        public Builder frameSinkDecorator(UnaryOperator<FrameSink> sinkDecorator) {
            this.sinkDecorator = Objects.requireNonNull(sinkDecorator, "sinkDecorator");
            return this;
        }

        /**
         * Closes the stream before the capture step and waits until the device reports Ready, and also waits for
         * Ready before every later session. For SBI devices that are Busy while they stream (desktop authentication
         * workflows). Default 0: the stream stays open until the attempt ends.
         *
         * @param readyTimeoutMs longest wait for Ready; after it the capture fails with {@code NOT_READY}
         * @return this builder
         * @throws IllegalArgumentException if negative
         */
        public Builder releaseDeviceBeforeCapture(long readyTimeoutMs) {
            if (readyTimeoutMs < 0) {
                throw new IllegalArgumentException("readyTimeoutMs must not be negative");
            }
            this.deviceReadyTimeoutMs = readyTimeoutMs;
            return this;
        }

        /**
         * Passes the transaction's uncounted low-light results to each session
         * ({@link SessionRequest.Builder#lowLightRetries(int)}), which turns on the engine's low-light rule. Default
         * off: every PAD failure counts. A client that reopens the window for the same subject continues the count
         * with {@link #priorFailures(int, boolean, int)}; without it each window would start a new allowance.
         *
         * @param trackLowLight {@code true} to pass the number
         * @return this builder
         */
        public Builder trackLowLight(boolean trackLowLight) {
            this.trackLowLight = trackLowLight;
            return this;
        }

        /**
         * Sets the receiver of contained failures, for the client's log; default discards them.
         *
         * @param errors the receiver
         * @return this builder
         */
        public Builder errorReporter(ErrorReporter errors) {
            this.errors = Objects.requireNonNull(errors, "errors");
            return this;
        }

        /**
         * Creates the driver; nothing runs until {@link LivenessSessionDriver#start()}.
         *
         * @return the driver
         * @throws NullPointerException if the engine, device, audit sink, listener or capture handler is missing
         */
        public LivenessSessionDriver build() {
            return new LivenessSessionDriver(this);
        }
    }
}
