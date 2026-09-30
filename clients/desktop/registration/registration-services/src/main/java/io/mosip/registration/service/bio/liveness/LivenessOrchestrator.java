package io.mosip.registration.service.bio.liveness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.FaceCaptureDevice;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.liveness.core.engine.LivenessTransaction;
import io.mosip.liveness.core.policy.PolicyResolution;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.context.SessionContext;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory;

/**
 * Entry point of the screens into face liveness: the UI-agnostic service a JavaFX controller calls.
 *
 * <p>Typical use by a face capture screen:
 * <ol>
 * <li>{@link #isEnforced(Workflow)}: if {@code false}, capture as before with
 * {@link LivenessContext#withoutPass(Workflow, String)} on the request.</li>
 * <li>{@link #start(LivenessRequest, LivenessRunListener)}: opens the device stream and the first session; the
 * listener receives UI states and preview frames.</li>
 * <li>{@link LivenessRunListener#onCaptureReady(LivenessContext)}: put the context on the {@code MDMRequestDto}
 * and run the existing capture service; the liveness gate in {@code BioServiceImpl.captureModality} binds the
 * signed capture to the session.</li>
 * <li>{@link LivenessRunListener#onSessionEnded(LivenessOutcome)}: on failure offer {@link LivenessRun#retry()}
 * or, when allowed, {@link LivenessRun#grantOverride(String)}; always {@link LivenessRun#close()} when the screen
 * closes.</li>
 * </ol>
 *
 * <p>One run exists at a time, because the client has one face device. Starting a run while another one has an
 * open session is refused ({@code REG-LIV-016}); a previous run without an open session is closed first.
 *
 * <p>When a run uses up its attempts ({@code max_retries}), the same person cannot start a new run in the same
 * workflow for a while ({@code REG-LIV-014}, see {@link LivenessConfig#exhaustedBlockMinutes(Workflow)}): closing the
 * liveness window and opening it again does not give a fresh set of attempts.
 *
 * <p>Thread-safe.
 */
@Component
public class LivenessOrchestrator {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessOrchestrator.class);
	private static final DiagnosticSink NO_DIAGNOSTICS = record -> {
	};

	private final LivenessConfig config;
	private final LivenessEngineRegistry engines;
	private final LivenessTokenStore tokens;
	private final AuditSink audit;
	private final DiagnosticSink diagnostics;
	private final MosipDeviceSpecificationFactory deviceFactory;
	private final Clock clock;

	private final ExhaustedTransactions exhausted = new ExhaustedTransactions();
	private final DiagnosticModeAudit diagnosticMode;
	private Supplier<String> userId = LivenessOrchestrator::sessionUserId;
	private LivenessRun current;

	/**
	 * Creates the orchestrator.
	 *
	 * @param config        liveness configuration
	 * @param engines       engine registry
	 * @param tokens        pass token store shared with the gate
	 * @param audit         audit sink
	 * @param diagnostics   diagnostic log sink
	 * @param deviceFactory the client's SBI device registry
	 */
	@Autowired
	public LivenessOrchestrator(LivenessConfig config, LivenessEngineRegistry engines, LivenessTokenStore tokens,
			DesktopAuditSink audit, DesktopDiagnosticSink diagnostics, MosipDeviceSpecificationFactory deviceFactory) {
		this(config, engines, tokens, audit, diagnostics, deviceFactory, Clock.system());
	}

	LivenessOrchestrator(LivenessConfig config, LivenessEngineRegistry engines, LivenessTokenStore tokens,
			AuditSink audit, DiagnosticSink diagnostics, MosipDeviceSpecificationFactory deviceFactory, Clock clock) {
		this.config = Objects.requireNonNull(config, "config");
		this.engines = Objects.requireNonNull(engines, "engines");
		this.tokens = Objects.requireNonNull(tokens, "tokens");
		this.audit = Objects.requireNonNull(audit, "audit");
		this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
		this.deviceFactory = deviceFactory;
		this.clock = Objects.requireNonNull(clock, "clock");
		this.diagnosticMode = new DiagnosticModeAudit(this.audit, this.clock);
	}

	/**
	 * Tells whether a face capture in a workflow needs a liveness pass. Read at each call, so a config sync applies
	 * to the next capture.
	 *
	 * @param workflow the workflow
	 * @return {@code true} when the master switch is on and the workflow's policy is not {@code OFF}
	 */
	public boolean isEnforced(Workflow workflow) {
		return config.isEnforced(workflow);
	}

	/**
	 * Returns the resolved policy of a workflow (defaults, floors and per-workflow overrides applied).
	 *
	 * @param workflow the workflow
	 * @return the policy
	 */
	public LivenessPolicy policy(Workflow workflow) {
		return config.policy(workflow);
	}

	/**
	 * Returns the load state of the liveness engine, for a status line or the diagnostic page.
	 *
	 * @return the status
	 */
	public LivenessEngineRegistry.Status engineStatus() {
		return engines.status();
	}

	/**
	 * Returns the description of the loaded engine (engine tag and model versions), for the diagnostic line.
	 *
	 * @return the description; empty before the engine is ready
	 */
	public Optional<EngineInfo> engineInfo() {
		return engines.info();
	}

	/**
	 * Returns the liveness configuration, for texts replaced by {@code guidance.<messageKey>.<lang>}.
	 *
	 * @return the configuration source (synced values, read at each call)
	 */
	public ConfigSource config() {
		return config;
	}

	/**
	 * Replaces the source of the logged-in user ID written in {@code REG-EVT-LV17} (tests).
	 *
	 * @param userId supplier of the user ID
	 */
	void setUserIdSupplier(Supplier<String> userId) {
		this.userId = Objects.requireNonNull(userId, "userId");
	}

	/**
	 * Starts a liveness transaction: resolves the policy, writes {@code REG-EVT-LV15} when configuration values
	 * changed or were corrected and {@code REG-EVT-LV17} when diagnostic mode was switched, and opens the first
	 * session on the liveness worker thread.
	 *
	 * @param request  workflow, subject and device
	 * @param listener receives UI states, preview frames, capture ready and session ends; called on liveness
	 *                 threads, never on the UI thread
	 * @return the run; the caller closes it when the screen closes
	 * @throws RegBaseCheckedException {@code REG-LIV-016} if another run has an open session; {@code REG-LIV-014} if
	 *                                 this person used up the attempts of this workflow recently
	 * @throws IllegalStateException   if liveness is not enforced for the workflow
	 */
	public synchronized LivenessRun start(LivenessRequest request, LivenessRunListener listener)
			throws RegBaseCheckedException {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(listener, "listener");
		if (current != null && !current.isClosed()) {
			if (current.isSessionActive()) {
				throw new RegBaseCheckedException(RegistrationExceptionConstants.LIVENESS_BUSY.getErrorCode(),
						RegistrationExceptionConstants.LIVENESS_BUSY.getErrorMessage());
			}
			current.close();
		}
		PolicyResolution resolution = config.resolve(request.workflow());
		LivenessPolicy policy = resolution.policy();
		if (!policy.isEnabled()) {
			throw new IllegalStateException("liveness is not enforced for " + request.workflow().id());
		}
		if (isBlocked(request.workflow(), request.subjectRef())) {
			LOGGER.info("Liveness attempts for {} are used up; new run refused", request.workflow().id());
			throw new RegBaseCheckedException(RegistrationExceptionConstants.LIVENESS_MAX_ATTEMPTS.getErrorCode(),
					RegistrationExceptionConstants.LIVENESS_MAX_ATTEMPTS.getErrorMessage());
		}
		auditConfigChanges(request.workflow(), resolution);
		diagnosticMode.observe(policy.isDiagnosticEnabled(), userId.get(), request.workflow());
		LivenessRun run = new LivenessRun(request, policy, trackExhaustion(request, listener), engines::engine,
				() -> device(request), tokens, audit, policy.isDiagnosticEnabled() ? diagnostics : NO_DIAGNOSTICS, clock);
		current = run;
		LOGGER.info("Starting liveness for {} with policy {}", request, policy.mode());
		run.begin();
		return run;
	}

	/**
	 * Tells whether a person is blocked in a workflow because a recent liveness transaction used up its attempts.
	 *
	 * @param workflow   the workflow
	 * @param subjectRef the registration ID or user ID
	 * @return {@code true} while the block lasts
	 */
	public boolean isBlocked(Workflow workflow, String subjectRef) {
		return subjectRef != null && exhausted.blockedUntil(workflow, subjectRef, clock.now()).isPresent();
	}

	/**
	 * Returns the current run.
	 *
	 * @return the last started run while it is not closed
	 */
	public synchronized Optional<LivenessRun> current() {
		return current == null || current.isClosed() ? Optional.<LivenessRun>empty() : Optional.of(current);
	}

	/**
	 * Returns the capture context of a pass that is waiting for its signed capture, for capture code that cannot
	 * receive the context from its screen (for example {@code SessionContext.validateFace} at login).
	 *
	 * @param workflow   the workflow of the capture
	 * @param subjectRef the user ID or registration ID being captured
	 * @return the context when exactly one unused, unexpired pass exists and it matches workflow and subject
	 */
	public Optional<LivenessContext> pendingContext(Workflow workflow, String subjectRef) {
		Instant now = clock.now();
		Optional<LivenessPassToken> token = tokens.pendingToken(now);
		if (!token.isPresent() || token.get().workflow() != workflow
				|| !token.get().subjectRef().equals(subjectRef)) {
			return Optional.empty();
		}
		return Optional.of(LivenessContext.withPass(token.get(), subjectRef));
	}

	/**
	 * Returns the context to put on a face capture request built by code that cannot receive it from its screen:
	 * the pending pass for this workflow and subject, else a context without a pass, which the gate accepts only
	 * when the workflow's policy is {@code OFF}.
	 *
	 * @param workflow   the workflow of the capture
	 * @param subjectRef the user ID or registration ID being captured
	 * @return the context; never {@code null}
	 */
	public LivenessContext contextFor(Workflow workflow, String subjectRef) {
		return pendingContext(workflow, subjectRef).orElseGet(() -> LivenessContext.withoutPass(workflow, subjectRef));
	}

	/** Wraps the screen's listener so that a transaction that uses up its attempts blocks the next run. */
	private LivenessRunListener trackExhaustion(LivenessRequest request, LivenessRunListener screen) {
		return new LivenessRunListener() {
			@Override
			public void onUiState(LivenessUiState state) {
				screen.onUiState(state);
			}

			@Override
			public void onPreviewFrame(FaceFrame frame) {
				screen.onPreviewFrame(frame);
			}

			@Override
			public void onCaptureReady(LivenessContext context) {
				screen.onCaptureReady(context);
			}

			@Override
			public void onSessionEnded(LivenessOutcome outcome) {
				if (outcome.status() == LivenessTransaction.Status.EXHAUSTED) {
					exhausted.record(request.workflow(), request.subjectRef(), clock.now()
							.plus(Duration.ofMinutes(config.exhaustedBlockMinutes(request.workflow()))));
				}
				screen.onSessionEnded(outcome);
			}
		};
	}

	private FaceCaptureDevice device(LivenessRequest request)
			throws DeviceException {
		if (request.device() != null) {
			return request.device();
		}
		if (deviceFactory == null) {
			throw new DeviceException(
					DeviceErrorCode.NOT_FOUND, "no device registry");
		}
		return SbiFaceCaptureDevice.resolve(deviceFactory, request.modality(), clock);
	}

	private static String sessionUserId() {
		try {
			return SessionContext.isSessionContextAvailable() ? SessionContext.userId() : "";
		} catch (RuntimeException e) {
			return "";
		}
	}

	private void auditConfigChanges(Workflow workflow, PolicyResolution resolution) {
		List<String> changed = config.changedKeysSinceLastCheck();
		if (!changed.isEmpty()) {
			audit.emit(new AuditPayloadBuilder(workflow, "", clock).configApplied(String.join(",", changed)));
		}
		resolution.configAppliedEvent(clock.now()).ifPresent(audit::emit);
		if (!resolution.issues().isEmpty()) {
			LOGGER.info("Liveness configuration corrected for {}: {}", workflow.id(), resolution.clampedKeys());
		}
	}
}
