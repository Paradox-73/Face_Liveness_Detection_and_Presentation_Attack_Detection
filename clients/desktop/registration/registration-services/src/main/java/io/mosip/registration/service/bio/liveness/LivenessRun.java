/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.DiagnosticRecord;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.run.AttemptOutcome;
import io.mosip.liveness.core.run.CaptureHandOff;
import io.mosip.liveness.core.run.LivenessSessionDriver;
import io.mosip.liveness.core.run.LivenessSessionDriverBuilder;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.LivenessContext;

/**
 * One liveness transaction for one person in one workflow: the sessions of the first attempt and its retries.
 *
 * <p>The attempt loop is the shared {@link LivenessSessionDriver} of {@code liveness-core}, the same one the Android
 * client runs: it opens each session, moves the SBI stream frames into it on one {@code liveness-worker} thread,
 * counts retries in a {@code LivenessTransaction} and writes {@code REG-EVT-LV11}, {@code LV12} and {@code LV13}.
 * This class adds what is specific to the desktop client: the pass token is registered in the
 * {@link LivenessTokenStore} for the liveness gate in {@code BioServiceImpl.captureModality}, the outcome becomes a
 * {@link LivenessOutcome} with the client's {@code REG-LIV-*} error codes, the latest diagnostic record is kept for
 * the liveness window, and the supervisor override is checked and audited here.
 *
 * <p><b>Threading.</b> Public methods are safe from any thread. {@link LivenessRunListener} callbacks come from the
 * worker (UI states, capture ready, session end) or the stream reader (preview frames, private copies).
 *
 * <p><b>Capture ready.</b> When the session issues its pass token the run registers it in the
 * {@link LivenessTokenStore} and calls {@link LivenessRunListener#onCaptureReady}. For resident workflows the
 * stream stays open so the preview keeps running during "Hold still". For authentication workflows the stream is
 * closed first and the device is polled every 100 ms until it reports Ready (at most
 * {@value #DEVICE_READY_TIMEOUT_MS} ms), because SBI devices are Busy while they stream and the authentication
 * capture checks availability.
 *
 * <p><b>Retries</b> follow the rules of {@code LivenessTransaction}: failures that count use up an attempt; device,
 * engine, quality and cancellation failures do not. The count starts at the failures that earlier liveness windows
 * of the same workflow and person used up ({@link AttemptHistory}), so closing the window does not give new
 * attempts. After the last attempt {@code on_max_failure} applies. A suspected attack in this or an earlier window
 * forbids the supervisor override.
 *
 * <p><b>Low light.</b> The run turns on the engine's low-light ending
 * ({@link LivenessSessionDriverBuilder#trackLowLight(boolean)}): a passive PAD failure on a face lit too weakly ends
 * {@code QUALITY} with {@code LOW_LIGHT}, "Improve lighting.", without using up an attempt and without
 * {@code REG-EVT-LV08}, up to {@code passive.low_light_max_uncounted} times since the last counted failure; the next
 * one counts as {@code PAD}. The count starts at the low-light results of earlier windows ({@link AttemptHistory}),
 * so Cancel and a new window do not give new uncounted tries.
 *
 * <p><b>Supervisor override</b> needs a second person and a checked photo: {@link OverrideRequest#grant} refuses a
 * user without the supervisor role, the logged-in operator, and an authentication older than
 * {@value #OVERRIDE_VALIDITY_MS} ms; the photo taken after the grant must show exactly one face whose passive PAD
 * score is not below {@code pad.reject_threshold}. Grants and refusals are audited as {@code REG-EVT-LV16} with both
 * user IDs and score buckets, never images.
 */
public final class LivenessRun implements AutoCloseable {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessRun.class);

	/** Longest wait for an SBI device to report Ready after its stream closed (authentication workflows). */
	static final long DEVICE_READY_TIMEOUT_MS = 1_500L;
	static final long OVERRIDE_VALIDITY_MS = 60_000L;
	/** Tolerance for an authentication time slightly ahead of this run's clock. */
	static final long OVERRIDE_CLOCK_SKEW_MS = 2_000L;
	/** Audit attribute of {@code REG-EVT-LV16}: {@code GRANTED} or {@code REFUSED}. */
	static final String AUDIT_DECISION = "decision";
	/** Audit attribute of {@code REG-EVT-LV16}: number of faces in the override photo. */
	static final String AUDIT_FACES = "faces";
	/** Shortest wait for the worker when the gate binds a capture. */
	private static final long CALL_TIMEOUT_MS = 5_000L;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final LivenessRequest request;
	private final LivenessPolicy policy;
	private final LivenessRunListener listener;
	private final LivenessSessionDriver.EngineSupplier engines;
	private final LivenessTokenStore tokens;
	private final AuditSink audit;
	private final DiagnosticTap diagnostics;
	private final Clock clock;
	private final Supplier<String> operatorId;
	private final OverridePhotoCheck.ModelLookup photoModels;
	private final LivenessSessionDriver driver;

	private volatile LivenessContext captureContext;
	private volatile LivenessOutcome lastOutcome;
	private volatile LivenessResult lastResult;

	/**
	 * Creates a run that continues the counted failures and low-light results of earlier liveness windows.
	 *
	 * @param carriedFailures counted failures of earlier windows for the same workflow and person, below
	 *                        {@code max_retries}
	 * @param carriedAttack   whether one of those failures was a suspected attack
	 * @param carriedLowLight uncounted low-light results of earlier windows since their last counted failure, 0 to
	 *                        {@link io.mosip.liveness.core.engine.LivenessTransaction#maxPriorLowLightRetries()}
	 * @param operatorId      supplies the logged-in operator's user ID (empty when nobody is logged in)
	 * @param photoModels     finds the engine models that check an override photo
	 * @throws IllegalArgumentException if {@code carriedFailures} leaves no attempt or {@code carriedLowLight} is
	 *                                  outside its range
	 */
	LivenessRun(LivenessRequest request, LivenessPolicy policy, int carriedFailures, boolean carriedAttack,
			int carriedLowLight, LivenessRunListener listener, LivenessSessionDriver.EngineSupplier engines,
			LivenessSessionDriver.DeviceSupplier devices, LivenessTokenStore tokens, AuditSink audit,
			DiagnosticSink diagnostics, Clock clock, Supplier<String> operatorId,
			OverridePhotoCheck.ModelLookup photoModels) {
		this.request = Objects.requireNonNull(request, "request");
		this.policy = Objects.requireNonNull(policy, "policy");
		this.listener = Objects.requireNonNull(listener, "listener");
		this.engines = Objects.requireNonNull(engines, "engines");
		this.tokens = Objects.requireNonNull(tokens, "tokens");
		this.audit = Objects.requireNonNull(audit, "audit");
		this.diagnostics = new DiagnosticTap(Objects.requireNonNull(diagnostics, "diagnostics"));
		this.clock = Objects.requireNonNull(clock, "clock");
		this.operatorId = Objects.requireNonNull(operatorId, "operatorId");
		this.photoModels = Objects.requireNonNull(photoModels, "photoModels");
		LivenessSessionDriverBuilder b = LivenessSessionDriver.builder(policy, request.subjectRef()).engine(engines)
				.device(Objects.requireNonNull(devices, "devices")).audit(audit).listener(new DriverListener())
				.captureHandler(this::onCaptureReady).clock(clock).diagnosticSink(this.diagnostics)
				.previewListener(listener::onPreviewFrame)
				.priorFailures(Math.max(0, carriedFailures), carriedAttack, carriedLowLight).trackLowLight(true)
				.errorReporter((what, error) -> LOGGER.error("Liveness: {} ({})", what,
						error.getClass().getSimpleName()));
		if (request.workflow().isAuth()) {
			b.releaseDeviceBeforeCapture(DEVICE_READY_TIMEOUT_MS);
		}
		this.driver = b.build();
	}

	/** Opens the first session. */
	void begin() {
		driver.start();
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
	 * Tells whether a session is opening or running (including capture ready).
	 *
	 * @return {@code true} until the session's outcome is reported
	 */
	public boolean isSessionActive() {
		return driver.isSessionActive();
	}

	/**
	 * Tells whether the run is closed.
	 *
	 * @return {@code true} after {@link #close()}
	 */
	public boolean isClosed() {
		return driver.isClosed();
	}

	/**
	 * Opens the next session of the transaction.
	 *
	 * @throws IllegalStateException if the run is closed, a session is running, or the retry policy allows no
	 *                               further attempt
	 */
	public void retry() {
		if (!driver.retry()) {
			throw new IllegalStateException("no retry available");
		}
	}

	/** Cancels the running session, if any; safe from any thread. The session ends with {@code CANCELLED}. */
	public void cancel() {
		driver.cancel();
	}

	/**
	 * Starts a supervisor override after the last attempt failed ({@code SUPERVISOR_OVERRIDE}). The liveness window
	 * calls it when the Supervisor override button is pressed, then authenticates a supervisor and calls
	 * {@link OverrideRequest#grant}. The request stays valid when this run is closed in between (the supervisor's own
	 * face authentication starts another liveness run, which closes this one).
	 *
	 * @return the request, usable once
	 * @throws IllegalStateException if the last outcome does not allow an override (never after a suspected attack
	 *                               in this or an earlier window, never in authentication workflows)
	 */
	public OverrideRequest requestOverride() {
		LivenessOutcome o = lastOutcome;
		LivenessResult failed = lastResult;
		if (driver.isClosed() || o == null || !o.isOverrideAllowed() || failed == null) {
			throw new IllegalStateException(RegistrationExceptionConstants.LIVENESS_OVERRIDE_NOT_ALLOWED.getErrorMessage());
		}
		return new OverrideRequest(failed);
	}

	/**
	 * Lets an authenticated supervisor accept the resident's face after the last attempt failed: the same as
	 * {@code requestOverride().grant(approval)}.
	 *
	 * @param approval the supervisor who authenticated
	 * @return a single-use context for the capture request
	 * @throws OverrideRefusedException if the approval is refused (audited)
	 * @throws IllegalStateException    if the last outcome does not allow an override
	 */
	public LivenessContext grantOverride(SupervisorApproval approval) {
		return requestOverride().grant(approval);
	}

	/** Cancels the running session, stops the worker and releases the stream. Idempotent. */
	@Override
	public void close() {
		driver.close();
	}

	/**
	 * Waits for the worker to finish its queued work after {@link #close()} (tests).
	 *
	 * @param millis longest wait
	 * @return {@code true} if the worker ended
	 * @throws InterruptedException if interrupted
	 */
	boolean awaitTermination(long millis) throws InterruptedException {
		return driver.awaitTermination(millis);
	}

	// ------------------------------------------------------------------ worker thread

	/** Registers the pass token for the liveness gate and tells the screen to run the signed capture. */
	private void onCaptureReady(CaptureHandOff handOff) {
		LivenessPassToken token = handOff.passToken();
		tokens.register(token, new SessionHandle(handOff));
		LivenessContext context = LivenessContext.withPass(token, request.subjectRef());
		captureContext = context;
		callListener(() -> listener.onCaptureReady(context));
	}

	/**
	 * Client error code of a failure that happened before a session existed.
	 *
	 * @return {@code REG-LIV-*} code, or {@code null}
	 */
	private static String errorCode(AttemptOutcome o) {
		if (o.sessionId().isPresent() || !o.failureReason().isPresent()) {
			return null;
		}
		FailureReason reason = o.failureReason().get();
		if (reason == FailureReason.ENGINE) {
			return RegistrationExceptionConstants.LIVENESS_ENGINE_ERROR.getErrorCode();
		}
		if (reason == FailureReason.DEVICE) {
			return RegistrationExceptionConstants.LIVENESS_DEVICE_ERROR.getErrorCode();
		}
		return null;
	}

	private void callListener(Runnable callback) {
		try {
			callback.run();
		} catch (RuntimeException e) {
			LOGGER.error("Liveness listener failed: {}", e.getClass().getSimpleName());
		}
	}

	private static String randomText(int bytes) {
		byte[] b = new byte[bytes];
		RANDOM.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	/** Receives the driver's UI states and attempt outcomes (worker thread). */
	private final class DriverListener implements LivenessSessionDriver.Listener {

		@Override
		public void onUiState(LivenessUiState state) {
			callListener(() -> listener.onUiState(state));
		}

		@Override
		public void onAttemptEnded(AttemptOutcome attempt) {
			// An unused pass token is withdrawn before the screen hears that the session ended.
			attempt.sessionId().ifPresent(tokens::revoke);
			captureContext = null;
			attempt.result().filter(r -> !r.isPass()).ifPresent(r -> lastResult = r);
			LivenessOutcome outcome = LivenessOutcome.of(attempt, errorCode(attempt));
			lastOutcome = outcome;
			if (outcome.errorCode().isPresent()) {
				LOGGER.error("Liveness session could not start: {}", outcome);
			} else {
				LOGGER.info("Liveness session ended: {}", outcome);
			}
			callListener(() -> listener.onSessionEnded(outcome));
		}
	}

	/**
	 * Pass handle of a session: the gate's calls (from the capture thread) go to the driver, which runs them on the
	 * worker.
	 */
	private static final class SessionHandle implements LivenessTokenStore.PassHandle {

		private final CaptureHandOff handOff;

		SessionHandle(CaptureHandOff handOff) {
			this.handOff = handOff;
		}

		@Override
		public void captureStarted() {
			handOff.captureStarted();
		}

		@Override
		public void captureFailed(DeviceError error) {
			handOff.captureFailed(error);
		}

		@Override
		public LivenessTokenStore.BindOutcome bind(BindingRequest bindingRequest, long timeoutMs)
				throws LivenessTokenStore.LivenessTimeoutException {
			CaptureHandOff.BindOutcome o;
			try {
				o = handOff.bind(bindingRequest, Math.max(timeoutMs, CALL_TIMEOUT_MS));
			} catch (TimeoutException e) {
				throw new LivenessTokenStore.LivenessTimeoutException(e.getMessage());
			}
			return new LivenessTokenStore.BindOutcome(o.verdict(), o.result().orElse(null));
		}
	}

	private LivenessAuditEvent overrideEvent(String sessionId, String decision, String reason, String supervisorId,
			String operator, OverridePhotoCheck.Result photo) {
		LivenessAuditEvent.Builder b = LivenessAuditEvent.builder(AuditEventId.LIVENESS_OVERRIDE_USED,
				request.workflow(), sessionId, clock.now()).put(AUDIT_DECISION, decision)
				.put(AuditAttributes.REASON, reason).put(AuditAttributes.SUPERVISOR_ID, auditText(supervisorId))
				.put(AuditAttributes.USER_ID, auditText(operator));
		if (photo != null) {
			b.put(AUDIT_FACES, photo.faces);
			if (photo.padScore >= 0) {
				b.put(AuditAttributes.SCORE_BUCKET, AuditAttributes.bucket(photo.padScore));
			}
		}
		return b.build();
	}

	private static String auditText(String s) {
		String t = s == null ? "" : s;
		return t.length() <= LivenessAuditEvent.MAX_VALUE_LENGTH ? t
				: t.substring(0, LivenessAuditEvent.MAX_VALUE_LENGTH);
	}

	/**
	 * A supervisor override waiting for its approval. Created by {@link #requestOverride()} after the last attempt
	 * failed; {@link #grant} checks who approved and issues the single-use override pass.
	 */
	public final class OverrideRequest {

		private final LivenessResult failed;
		private boolean used;

		private OverrideRequest(LivenessResult failed) {
			this.failed = failed;
		}

		/**
		 * Grants the override to an authenticated supervisor. The returned context goes on the capture request like
		 * a pass; the photo is then checked (one face, passive PAD not below {@code pad.reject_threshold}) before it
		 * is stored with {@code LIVENESS_DECISION=OVERRIDE}.
		 *
		 * @param approval the supervisor who authenticated
		 * @return a single-use context, valid until {@value LivenessRun#OVERRIDE_VALIDITY_MS} ms after the
		 *         supervisor's authentication
		 * @throws OverrideRefusedException if the user lacks the supervisor role, is the logged-in operator, or
		 *                                  authenticated longer ago than the override validity (audited)
		 * @throws IllegalStateException    if this request was already granted
		 */
		public synchronized LivenessContext grant(SupervisorApproval approval) {
			Objects.requireNonNull(approval, "approval");
			if (used) {
				throw new IllegalStateException(
						RegistrationExceptionConstants.LIVENESS_OVERRIDE_NOT_ALLOWED.getErrorMessage());
			}
			String operator = operatorId.get();
			operator = operator == null ? "" : operator.trim();
			Instant now = clock.now();
			OverrideRefusedException.Reason refusal = null;
			if (!approval.hasSupervisorRole()) {
				refusal = OverrideRefusedException.Reason.NOT_SUPERVISOR;
			} else if (operator.isEmpty()) {
				refusal = OverrideRefusedException.Reason.NO_OPERATOR;
			} else if (operator.equalsIgnoreCase(approval.supervisorId())) {
				refusal = OverrideRefusedException.Reason.SAME_USER;
			} else {
				long ageMs = Duration.between(approval.authenticatedAt(), now).toMillis();
				if (ageMs > OVERRIDE_VALIDITY_MS || ageMs < -OVERRIDE_CLOCK_SKEW_MS) {
					refusal = OverrideRefusedException.Reason.AUTH_EXPIRED;
				}
			}
			if (refusal != null) {
				LOGGER.info("Supervisor override refused: {}", refusal);
				audit.emit(overrideEvent(failed.sessionId(), "REFUSED", refusal.name(), approval.supervisorId(),
						operator, null));
				throw new OverrideRefusedException(refusal);
			}
			used = true;
			Instant expiresAt = approval.authenticatedAt().plusMillis(OVERRIDE_VALIDITY_MS);
			LivenessPassToken token = LivenessPassToken.builder().sessionId("override-" + randomText(12))
					.workflow(request.workflow()).subjectRef(request.subjectRef())
					.deviceSerial(failed.device().serialNumber()).nonce(randomText(16)).issuedAt(now)
					.expiresAt(expiresAt.isAfter(now) ? expiresAt : now.plusMillis(1)).build();
			tokens.register(token, new OverrideHandle(token.sessionId(), approval.supervisorId(), operator, failed));
			return LivenessContext.withPass(token, request.subjectRef());
		}
	}

	/**
	 * Pass handle of a supervisor override: no session to bind to. The photo is checked with the engine's models
	 * (one face, passive PAD) and the grant or refusal is audited here; the gate records the override in the packet.
	 */
	private final class OverrideHandle implements LivenessTokenStore.PassHandle {

		private final String sessionId;
		private final String supervisorId;
		private final String operator;
		private final LivenessResult failed;
		private boolean bound;

		OverrideHandle(String sessionId, String supervisorId, String operator, LivenessResult failed) {
			this.sessionId = sessionId;
			this.supervisorId = supervisorId;
			this.operator = operator;
			this.failed = failed;
		}

		@Override
		public void captureStarted() {
		}

		@Override
		public void captureFailed(DeviceError error) {
		}

		@Override
		public synchronized LivenessTokenStore.BindOutcome bind(BindingRequest bindingRequest, long timeoutMs) {
			if (bound) {
				return new LivenessTokenStore.BindOutcome(
						BindingVerdict.failed(FailureReason.BINDING, io.mosip.liveness.core.ReasonCode.TOKEN_REUSED)
								.build(), failed);
			}
			bound = true;
			OverridePhotoCheck.Models models = null;
			try {
				models = photoModels.modelsOf(engines.get()).orElse(null);
			} catch (EngineException | RuntimeException e) {
				LOGGER.error("Liveness engine not available for the override photo: {}", e.getClass().getSimpleName());
			}
			ImageBuffer image = bindingRequest == null ? null : bindingRequest.captureImage();
			OverridePhotoCheck.Result photo = OverridePhotoCheck.check(models, image, policy.padRejectThreshold());
			LOGGER.info("Supervisor override photo {}: {}", photo.isPassed() ? "accepted" : "refused",
					photo.auditReason());
			audit.emit(overrideEvent(sessionId, photo.isPassed() ? "GRANTED" : "REFUSED", photo.auditReason(),
					supervisorId, operator, photo));
			return new LivenessTokenStore.BindOutcome(photo.verdict, failed);
		}

		@Override
		public Optional<String> overrideSupervisor() {
			return Optional.of(supervisorId);
		}

		@Override
		public boolean auditsOverride() {
			return true;
		}
	}
}
