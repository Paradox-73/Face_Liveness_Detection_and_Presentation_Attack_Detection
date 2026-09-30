package io.mosip.registration.service.bio.liveness;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
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
import io.mosip.liveness.core.audit.DiagnosticRecord;
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
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.LivenessContext;

/**
 * One liveness transaction for one person in one workflow: the sessions of the first attempt and its retries.
 *
 * <p><b>Threading</b> (design section 4.9). All session calls run on this run's single {@code liveness-worker}
 * thread, which owns the session: it opens the device stream, moves frames from the {@link FrameHandOff} (newest
 * frame only, filled by the {@code sbi-stream-reader} thread) into the session, calls {@code tick()} at least every
 * 100 ms, and executes the capture calls the liveness gate submits. Public methods are safe from any thread;
 * {@link LivenessRunListener} callbacks come from the worker (UI states, capture ready, session end) or the reader
 * (preview frames).
 *
 * <p><b>Capture ready.</b> When the session issues its pass token the run registers it in the
 * {@link LivenessTokenStore} and calls {@link LivenessRunListener#onCaptureReady}. For resident workflows the
 * stream stays open so the preview keeps running during "Hold still". For authentication workflows the stream is
 * closed first and the device is polled every 100 ms until it reports Ready (at most 1,500 ms), because SBI devices
 * are Busy while they stream and the authentication capture checks availability (design section 4.4).
 *
 * <p><b>Retries</b> follow {@link LivenessTransaction}: failures that count use up an attempt, device, engine,
 * quality and cancellation failures do not. After the last attempt {@code on_max_failure} applies and audit
 * {@code REG-EVT-LV11} is written.
 */
public final class LivenessRun implements AutoCloseable {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessRun.class);

	static final long TICK_MS = 100L;
	static final long DEVICE_READY_TIMEOUT_MS = 1_500L;
	static final long OVERRIDE_VALIDITY_MS = 60_000L;
	private static final long CALL_TIMEOUT_MS = 5_000L;
	private static final SecureRandom RANDOM = new SecureRandom();

	/** Supplies the engine; may block while models load. */
	@FunctionalInterface
	interface EngineSupplier {
		LivenessEngine get() throws EngineException;
	}

	/** Supplies the face device. */
	@FunctionalInterface
	interface DeviceSupplier {
		FaceCaptureDevice get() throws DeviceException;
	}

	private final LivenessRequest request;
	private final LivenessPolicy policy;
	private final LivenessRunListener listener;
	private final EngineSupplier engines;
	private final DeviceSupplier devices;
	private final LivenessTokenStore tokens;
	private final AuditSink audit;
	private final DiagnosticTap diagnostics;
	private final Clock clock;
	private final ScheduledExecutorService worker;
	private final LivenessTransaction transaction;

	private FaceCaptureDevice device;
	private volatile Attempt current;
	private volatile LivenessContext captureContext;
	private volatile LivenessOutcome lastOutcome;
	private volatile LivenessResult lastResult;
	private volatile boolean closed;

	LivenessRun(LivenessRequest request, LivenessPolicy policy, LivenessRunListener listener, EngineSupplier engines,
			DeviceSupplier devices, LivenessTokenStore tokens, AuditSink audit, DiagnosticSink diagnostics,
			Clock clock) {
		this.request = Objects.requireNonNull(request, "request");
		this.policy = Objects.requireNonNull(policy, "policy");
		this.listener = Objects.requireNonNull(listener, "listener");
		this.engines = Objects.requireNonNull(engines, "engines");
		this.devices = Objects.requireNonNull(devices, "devices");
		this.tokens = Objects.requireNonNull(tokens, "tokens");
		this.audit = Objects.requireNonNull(audit, "audit");
		this.diagnostics = new DiagnosticTap(Objects.requireNonNull(diagnostics, "diagnostics"));
		this.clock = Objects.requireNonNull(clock, "clock");
		this.transaction = new LivenessTransaction(policy);
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, "liveness-worker");
			t.setDaemon(true);
			return t;
		});
		executor.setRemoveOnCancelPolicy(true);
		this.worker = executor;
	}

	/** Opens the first session. */
	void begin() {
		submit(this::openSession);
	}

	/**
	 * Returns the workflow of this run.
	 *
	 * @return the workflow
	 */
	public Workflow workflow() {
		return request.workflow();
	}

	/**
	 * Returns the resolved policy used by all sessions of this run.
	 *
	 * @return the policy
	 */
	public LivenessPolicy policy() {
		return policy;
	}

	/**
	 * Returns the latest diagnostic record of this run's sessions, for the diagnostic line of the liveness window.
	 *
	 * @return the record; empty when diagnostic mode is off or no session reported yet
	 */
	public Optional<DiagnosticRecord> lastDiagnostic() {
		return diagnostics.last();
	}

	/**
	 * Returns the capture context while a pass is waiting for its signed capture.
	 *
	 * @return the context between capture ready and the end of the session
	 */
	public Optional<LivenessContext> captureContext() {
		return Optional.ofNullable(captureContext);
	}

	/**
	 * Returns the outcome of the last session.
	 *
	 * @return the outcome; empty while the first session runs
	 */
	public Optional<LivenessOutcome> lastOutcome() {
		return Optional.ofNullable(lastOutcome);
	}

	/**
	 * Tells whether a session is running (including capture ready).
	 *
	 * @return {@code true} while a session is open
	 */
	public boolean isSessionActive() {
		return current != null;
	}

	/**
	 * Tells whether the run is closed.
	 *
	 * @return {@code true} after {@link #close()}
	 */
	public boolean isClosed() {
		return closed;
	}

	/**
	 * Opens the next session of the transaction.
	 *
	 * @throws IllegalStateException if the run is closed, a session is running, or the retry policy allows no
	 *                               further attempt
	 */
	public void retry() {
		LivenessOutcome o = lastOutcome;
		if (closed || current != null || o == null || !o.isRetryAllowed()) {
			throw new IllegalStateException("no retry available");
		}
		submit(this::openSession);
	}

	/** Cancels the running session, if any; safe from any thread. The session ends with {@code CANCELLED}. */
	public void cancel() {
		Attempt a = current;
		if (a != null) {
			a.session.cancel();
			submit(() -> a.afterCall());
		}
	}

	/**
	 * Lets a supervisor accept the resident's face after the last attempt failed ({@code SUPERVISOR_OVERRIDE}). The
	 * screen calls it only after the supervisor authenticated. The returned context goes on the capture request
	 * like a pass; the capture is stored with {@code LIVENESS_DECISION=OVERRIDE} and audit {@code REG-EVT-LV16}.
	 *
	 * @param supervisorId the supervisor's user ID
	 * @return a single-use context valid for {@value #OVERRIDE_VALIDITY_MS} ms
	 * @throws IllegalStateException if the last outcome does not allow an override (never after a suspected
	 *                               attack, never in authentication workflows)
	 */
	public LivenessContext grantOverride(String supervisorId) {
		LivenessOutcome o = lastOutcome;
		LivenessResult failed = lastResult;
		if (supervisorId == null || supervisorId.trim().isEmpty()) {
			throw new IllegalArgumentException("supervisorId is required");
		}
		if (closed || o == null || !o.isOverrideAllowed() || failed == null) {
			throw new IllegalStateException(RegistrationExceptionConstants.LIVENESS_OVERRIDE_NOT_ALLOWED.getErrorMessage());
		}
		LivenessPassToken token = LivenessPassToken.builder().sessionId("override-" + randomText(12))
				.workflow(request.workflow()).subjectRef(request.subjectRef())
				.deviceSerial(failed.device().serialNumber()).nonce(randomText(16)).issuedAt(clock.now())
				.expiresAt(clock.now().plusMillis(OVERRIDE_VALIDITY_MS)).build();
		tokens.register(token, new OverrideHandle(supervisorId, failed));
		return LivenessContext.withPass(token, request.subjectRef());
	}

	/** Cancels the running session, stops the worker and releases the stream. Idempotent. */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		Attempt a = current;
		if (a != null) {
			a.session.cancel();
		}
		try {
			worker.execute(() -> {
				Attempt c = current;
				if (c != null) {
					c.afterCall();
				}
			});
		} catch (RejectedExecutionException ignored) {
			// Already shut down.
		}
		worker.shutdown();
	}

	/**
	 * Waits for the worker to finish its queued work (tests).
	 *
	 * @param millis longest wait
	 * @return {@code true} if the worker ended
	 * @throws InterruptedException if interrupted
	 */
	boolean awaitTermination(long millis) throws InterruptedException {
		return worker.awaitTermination(millis, TimeUnit.MILLISECONDS);
	}

	// ------------------------------------------------------------------ worker thread

	private void openSession() {
		if (closed || current != null) {
			return;
		}
		LivenessEngine engine;
		try {
			engine = engines.get();
		} catch (EngineException e) {
			noSession(FailureReason.ENGINE, RegistrationExceptionConstants.LIVENESS_ENGINE_ERROR.getErrorCode(),
					uiState(UiPhase.FAILED, MessageKeys.ERROR_ENGINE, PromptIcon.ERROR,
							!e.getErrorCode().isModelIntegrityFailure()),
					new AuditPayloadBuilder(request.workflow(), "", clock).engineError(e.getErrorCode(),
							e.getClass().getSimpleName()),
					!e.getErrorCode().isModelIntegrityFailure());
			return;
		}
		try {
			if (device == null) {
				device = devices.get();
			}
		} catch (DeviceException e) {
			noSession(FailureReason.DEVICE, RegistrationExceptionConstants.LIVENESS_DEVICE_ERROR.getErrorCode(),
					uiState(UiPhase.DEVICE_ERROR, LivenessUiStateMapper.deviceMessageKey(e.getErrorCode()),
							PromptIcon.CAMERA_OFF, true),
					new AuditPayloadBuilder(request.workflow(), "", clock).deviceError(e.getErrorCode()), true);
			return;
		}
		if (request.workflow().isAuth() && transaction.sessions() > 0) {
			waitForDeviceReady();
		}
		SessionRequest sessionRequest = SessionRequest.builder(policy, request.subjectRef(), device.describe())
				.attempt(transaction.nextAttempt()).clock(clock).diagnosticSink(diagnostics).build();
		Attempt a;
		try {
			a = new Attempt();
			a.session = engine.open(sessionRequest, a);
		} catch (EngineException | RuntimeException e) {
			LOGGER.error("Liveness session could not be opened: {}", e.getClass().getSimpleName());
			noSession(FailureReason.ENGINE, RegistrationExceptionConstants.LIVENESS_ENGINE_ERROR.getErrorCode(),
					uiState(UiPhase.FAILED, MessageKeys.ERROR_ENGINE, PromptIcon.ERROR, true), null, true);
			return;
		}
		current = a;
		a.session.start();
		if (a.afterCall()) {
			return;
		}
		startStream(a);
	}

	private void startStream(Attempt a) {
		try {
			a.source = device.openStream(streamRequest());
			a.handOff = FrameHandOff.latestOnly();
			a.source.start(new PreviewTee(a.handOff));
		} catch (DeviceException e) {
			a.session.onError(e.getError());
			a.afterCall();
			return;
		} catch (RuntimeException e) {
			a.session.onError(DeviceError.of(DeviceErrorCode.STREAM_OPEN_FAILED, "stream could not start", e));
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
			a.handOff.deliverNext(a.session, TICK_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			a.session.cancel();
		}
		a.session.tick();
		if (!a.afterCall()) {
			submit(() -> pump(a));
		}
	}

	private void onCaptureReady(Attempt a) {
		Optional<LivenessPassToken> token = a.session.passToken();
		if (!token.isPresent()) {
			a.session.cancel();
			a.afterCall();
			return;
		}
		if (request.workflow().isAuth()) {
			closeSource(a);
			if (!waitForDeviceReady()) {
				a.session.captureFailed(DeviceError.of(DeviceErrorCode.NOT_READY,
						"device did not report Ready within " + DEVICE_READY_TIMEOUT_MS + " ms"));
				a.afterCall();
				return;
			}
			a.session.captureStarted();
		}
		tokens.register(token.get(), a);
		captureContext = LivenessContext.withPass(token.get(), request.subjectRef());
		a.ticker = worker.scheduleWithFixedDelay(() -> {
			if (!a.finished) {
				a.session.tick();
				a.afterCall();
			}
		}, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
		callListener(() -> listener.onCaptureReady(captureContext));
	}

	private boolean waitForDeviceReady() {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEVICE_READY_TIMEOUT_MS);
		while (true) {
			if (device.status() == DeviceStatus.READY) {
				return true;
			}
			if (System.nanoTime() >= deadline) {
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
		tokens.revoke(a.session.sessionId());
		captureContext = null;
		if (result != null && transaction.status() == LivenessTransaction.Status.IN_PROGRESS) {
			transaction.record(result);
		}
		if (result != null && !result.isPass()) {
			lastResult = result;
		}
		// The engine writes REG-EVT-LV11 itself when the last counted attempt fails; write it here only for an
		// engine that did not, so that the audit trail holds it exactly once.
		if (transaction.status() == LivenessTransaction.Status.EXHAUSTED && !a.maxRetriesAudited) {
			audit.emit(new AuditPayloadBuilder(request.workflow(), a.session.sessionId(), clock)
					.maxRetries(transaction.attemptsUsed(), transaction.onMaxFailure()));
		}
		LivenessOutcome.Builder b = LivenessOutcome.builder(request.workflow(), transaction, policy.maxRetries());
		if (result != null) {
			b.result(result);
		} else {
			b.failure(FailureReason.CANCELLED, null);
		}
		LivenessOutcome outcome = b.retryAllowed(transaction.canRetry() && !closed).build();
		lastOutcome = outcome;
		a.session.close();
		if (current == a) {
			current = null;
		}
		LOGGER.info("Liveness session ended: {}", outcome);
		callListener(() -> listener.onSessionEnded(outcome));
		if (closed) {
			worker.shutdown();
		}
	}

	private void noSession(FailureReason reason, String errorCode, LivenessUiState state, LivenessAuditEvent event,
			boolean retry) {
		if (event != null) {
			audit.emit(event);
		}
		LivenessOutcome outcome = LivenessOutcome.builder(request.workflow(), transaction, policy.maxRetries())
				.failure(reason, errorCode).retryAllowed(retry && !closed && transaction.canRetry()).build();
		lastOutcome = outcome;
		LOGGER.error("Liveness session could not start: {}", outcome);
		callListener(() -> listener.onUiState(state));
		callListener(() -> listener.onSessionEnded(outcome));
	}

	private StreamRequest streamRequest() {
		return StreamRequest.builder().streamTimeoutMs(policy.streamTimeoutMs())
				.firstFrameTimeoutMs(StreamRequest.DEFAULT_FIRST_FRAME_TIMEOUT_MS)
				.socketTimeoutMs(StreamRequest.DEFAULT_SOCKET_TIMEOUT_MS)
				.reconnectAttempts(policy.deviceReconnectAttempts()).build();
	}

	private LivenessUiState uiState(UiPhase phase, String messageKey, PromptIcon icon, boolean retry) {
		LivenessUiState.Builder b = LivenessUiState.builder(phase, messageKey).promptIcon(icon)
				.severity(Severity.ERROR).retryAvailable(retry)
				.attempt(Math.min(transaction.attemptsUsed() + 1, policy.maxRetries()), policy.maxRetries());
		if (retry) {
			b.guidance(MessageKeys.RETRY_AVAILABLE);
		}
		return b.build();
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
			LOGGER.error("Liveness listener failed: {}", e.getClass().getSimpleName());
		}
	}

	private void submit(Runnable task) {
		try {
			worker.execute(task);
		} catch (RejectedExecutionException e) {
			LOGGER.debug("Liveness worker stopped; task dropped");
		}
	}

	private <T> T call(Callable<T> task, long timeoutMs) throws LivenessTokenStore.LivenessTimeoutException {
		Future<T> f;
		try {
			f = worker.submit(task);
		} catch (RejectedExecutionException e) {
			throw new LivenessTokenStore.LivenessTimeoutException("liveness session is closed");
		}
		try {
			return f.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LivenessTokenStore.LivenessTimeoutException("interrupted while waiting for the session");
		} catch (TimeoutException e) {
			f.cancel(false);
			throw new LivenessTokenStore.LivenessTimeoutException("liveness session did not answer in time");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			}
			throw new IllegalStateException(cause);
		}
	}

	private static String randomText(int bytes) {
		byte[] b = new byte[bytes];
		RANDOM.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	/**
	 * One session: the core listener (on the worker thread) and the pass handle the gate calls (from the capture
	 * thread, forwarded to the worker).
	 */
	private final class Attempt implements LivenessListener, LivenessTokenStore.PassHandle {

		LivenessSession session;
		FaceFrameSource source;
		FrameHandOff handOff;
		ScheduledFuture<?> ticker;
		boolean captureReadyHandled;
		boolean finished;
		/** The engine already wrote {@code REG-EVT-LV11} for this attempt (worker thread only). */
		boolean maxRetriesAudited;

		/**
		 * Acts on the session state after a session call returned: capture ready or end. Worker thread only.
		 *
		 * @return {@code true} when the stream loop must stop
		 */
		boolean afterCall() {
			if (finished) {
				return true;
			}
			SessionState s = session.state();
			if (s.isTerminal()) {
				finish(this);
				return true;
			}
			if (!captureReadyHandled && (s == SessionState.CAPTURE_READY || s == SessionState.CAPTURING)) {
				captureReadyHandled = true;
				onCaptureReady(this);
				return true;
			}
			return captureReadyHandled;
		}

		@Override
		public void onUi(LivenessUiState state) {
			callListener(() -> listener.onUiState(state));
		}

		@Override
		public void onAudit(LivenessAuditEvent event) {
			if (event.eventId() == AuditEventId.LIVENESS_MAX_RETRIES) {
				maxRetriesAudited = true;
			}
			audit.emit(event);
		}

		@Override
		public void captureStarted() {
			submit(() -> {
				if (!finished) {
					session.captureStarted();
					afterCall();
				}
			});
		}

		@Override
		public void captureFailed(DeviceError error) {
			submit(() -> {
				if (!finished) {
					session.captureFailed(error);
					afterCall();
				}
			});
		}

		@Override
		public LivenessTokenStore.BindOutcome bind(BindingRequest bindingRequest, long timeoutMs)
				throws LivenessTokenStore.LivenessTimeoutException {
			return call(() -> {
				if (finished) {
					return new LivenessTokenStore.BindOutcome(
							BindingVerdict.failed(FailureReason.BINDING, io.mosip.liveness.core.ReasonCode.TOKEN_REUSED)
									.build(), null);
				}
				session.captureStarted();
				BindingVerdict verdict = session.bind(bindingRequest);
				LivenessResult result = session.result().orElse(null);
				afterCall();
				return new LivenessTokenStore.BindOutcome(verdict, result);
			}, Math.max(timeoutMs, CALL_TIMEOUT_MS));
		}
	}

	/** Pass handle of a supervisor override: no session to bind to; the gate records the override. */
	private static final class OverrideHandle implements LivenessTokenStore.PassHandle {

		private final String supervisorId;
		private final LivenessResult failed;

		OverrideHandle(String supervisorId, LivenessResult failed) {
			this.supervisorId = supervisorId;
			this.failed = failed;
		}

		@Override
		public void captureStarted() {
		}

		@Override
		public void captureFailed(DeviceError error) {
		}

		@Override
		public LivenessTokenStore.BindOutcome bind(BindingRequest request, long timeoutMs) {
			return new LivenessTokenStore.BindOutcome(BindingVerdict.passed().build(), failed);
		}

		@Override
		public Optional<String> overrideSupervisor() {
			return Optional.of(supervisorId);
		}
	}

	/** Sends each stream frame to the preview (a private copy) and to the session's hand-off. Reader thread. */
	private final class PreviewTee implements FrameSink {

		private final FrameSink target;

		PreviewTee(FrameSink target) {
			this.target = target;
		}

		@Override
		public void onFrame(FaceFrame frame) {
			try {
				listener.onPreviewFrame(FaceFrame.builder().data(Arrays.copyOf(frame.data(), frame.data().length))
						.format(frame.format()).size(frame.width(), frame.height())
						.rotationDegrees(frame.rotationDegrees()).mirrored(frame.isMirrored())
						.monotonicNanos(frame.monotonicNanos()).sequence(frame.sequence()).sourceId(frame.sourceId())
						.build());
			} catch (RuntimeException e) {
				LOGGER.debug("Preview listener failed: {}", e.getClass().getSimpleName());
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
}
