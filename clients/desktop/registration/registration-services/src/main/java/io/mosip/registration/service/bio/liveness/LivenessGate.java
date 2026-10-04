package io.mosip.registration.service.bio.liveness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.mdm.dto.MDMRequestDto;
import io.mosip.registration.mdm.dto.MdmBioDevice;

/**
 * The service-layer liveness checks that no screen can skip.
 *
 * <p><b>Capture gate</b> ({@code BioServiceImpl.captureModality}, every face capture): {@link #admit} runs before
 * the device capture and {@link #complete} after it.
 * <ul>
 * <li>The workflow comes from the request's {@link LivenessContext}. A request without one is checked as a
 * resident capture ({@code registration}), or as an exception photo when it lists biometric exceptions.</li>
 * <li>When the workflow's policy is {@code OFF} the capture proceeds; an exception photo must list at least one
 * exception, so the exception path cannot stand in for the resident's face.</li>
 * <li>Otherwise the request must carry a pass token that is registered, unused, unexpired, for this subject and
 * for this device. It is consumed before the capture, so a failed capture cannot reuse it.</li>
 * <li>After the capture the signed ISO face is decoded and bound to the session (same face, time window,
 * challenge echo, PAD on the captured image). Only then does the capture reach the caller, with the
 * {@code LIVENESS_*} evidence set on the face {@link BiometricsDto} for the packet.</li>
 * </ul>
 *
 * <p><b>Authentication gate</b> ({@code AuthenticationServiceImpl.authValidator}): {@link #rejectsAuthCapture}
 * refuses a face sample without a liveness pass while liveness is enforced for the authentication workflow, so a
 * face capture made outside the capture gate cannot authenticate a user.
 *
 * <p>Refusals throw {@link RegBaseCheckedException} with a {@code REG-LIV-*} code; details go to the log and the
 * audit trail ({@code REG-EVT-LV09}), never to the screen. Thread-safe.
 */
@Component
public class LivenessGate {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessGate.class);

	/** Longest wait for the session thread to bind a capture. */
	static final long BIND_TIMEOUT_MS = 10_000L;

	private static final Workflow[] AUTH_WORKFLOWS = { Workflow.LOGIN_AUTH, Workflow.PACKET_AUTH,
			Workflow.EXCEPTION_AUTH, Workflow.EOD_AUTH, Workflow.ONBOARD_AUTH };

	private final LivenessConfig config;
	private final LivenessTokenStore tokens;
	private final DesktopImageDecoder decoder;
	private final AuditSink audit;
	private final Clock clock;

	/**
	 * Creates the gate.
	 *
	 * @param config  liveness configuration
	 * @param tokens  pass token store shared with the liveness runs
	 * @param decoder image decoder for the signed capture
	 * @param audit   audit sink
	 */
	@Autowired
	public LivenessGate(LivenessConfig config, LivenessTokenStore tokens, DesktopImageDecoder decoder,
			DesktopAuditSink audit) {
		this(config, tokens, decoder, audit, Clock.system());
	}

	LivenessGate(LivenessConfig config, LivenessTokenStore tokens, DesktopImageDecoder decoder, AuditSink audit,
			Clock clock) {
		this.config = Objects.requireNonNull(config, "config");
		this.tokens = Objects.requireNonNull(tokens, "tokens");
		this.decoder = Objects.requireNonNull(decoder, "decoder");
		this.audit = Objects.requireNonNull(audit, "audit");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/**
	 * What {@link #admit} decided for one capture request; passed back to {@link #complete} or {@link #abort}.
	 */
	public static final class Admission {

		private final Workflow workflow;
		private final LivenessPassToken token;
		private final LivenessTokenStore.PassHandle handle;
		private final MdmBioDevice device;

		private Admission(Workflow workflow, LivenessPassToken token, LivenessTokenStore.PassHandle handle,
				MdmBioDevice device) {
			this.workflow = workflow;
			this.token = token;
			this.handle = handle;
			this.device = device;
		}

		/**
		 * Returns the workflow the capture was checked for.
		 *
		 * @return the workflow; {@code null} for a capture that is not a face
		 */
		public Workflow workflow() {
			return workflow;
		}

		/**
		 * Tells whether the capture must be bound to a liveness session.
		 *
		 * @return {@code true} when a pass was consumed
		 */
		public boolean requiresBinding() {
			return handle != null;
		}
	}

	/**
	 * Checks a capture request before the device is called.
	 *
	 * @param request the capture request
	 * @param device  the device that will capture
	 * @return the admission to pass to {@link #complete} or {@link #abort}
	 * @throws RegBaseCheckedException a {@code REG-LIV-*} error when the capture needs a liveness pass it does not
	 *                                 have
	 */
	public Admission admit(MDMRequestDto request, MdmBioDevice device) throws RegBaseCheckedException {
		if (request == null || !isFace(request.getModality())) {
			return new Admission(null, null, null, device);
		}
		LivenessContext context = request.getLivenessContext();
		Workflow workflow = workflowOf(request);
		if (!config.isEnforced(workflow)) {
			if (workflow == Workflow.EXCEPTION_PHOTO && !listsExceptions(request)) {
				throw refuse(workflow, RegistrationExceptionConstants.LIVENESS_EXCEPTION_PHOTO_INVALID,
						ReasonCode.POLICY);
			}
			return new Admission(workflow, null, null, device);
		}
		if (context == null) {
			throw refuse(workflow, RegistrationExceptionConstants.LIVENESS_CONTEXT_MISSING, ReasonCode.TOKEN_MISSING);
		}
		Optional<LivenessPassToken> token = context.getToken();
		if (!token.isPresent()) {
			throw refuse(workflow, RegistrationExceptionConstants.LIVENESS_NOT_VERIFIED, ReasonCode.TOKEN_MISSING);
		}
		LivenessPassToken t = token.get();
		if (context.getSubjectRef().isPresent() && !context.getSubjectRef().get().equals(t.subjectRef())) {
			throw refuse(workflow, RegistrationExceptionConstants.LIVENESS_SUBJECT_MISMATCH, ReasonCode.TOKEN_MISMATCH);
		}
		if (device != null && !SignedCaptureReader.serialOf(device).equals(t.deviceSerial())) {
			throw refuse(workflow, RegistrationExceptionConstants.LIVENESS_DEVICE_MISMATCH, ReasonCode.DEVICE_MISMATCH);
		}
		try {
			return new Admission(workflow, t, tokens.consume(t, clock.now()), device);
		} catch (LivenessTokenStore.TokenRefusedException e) {
			throw refusal(workflow, e.refusal());
		}
	}

	/**
	 * Binds the signed face capture to its liveness session and sets the packet evidence on it. Does nothing for
	 * captures that need no binding, except recording the workflow on face captures.
	 *
	 * @param admission the admission returned by {@link #admit}; {@code null} is ignored
	 * @param captures  the captures the device returned
	 * @throws RegBaseCheckedException a {@code REG-LIV-*} error when the capture cannot be read or does not match
	 *                                 the session
	 */
	public void complete(Admission admission, List<BiometricsDto> captures) throws RegBaseCheckedException {
		if (admission == null || admission.workflow == null) {
			return;
		}
		BiometricsDto face = faceCapture(captures);
		if (!admission.requiresBinding()) {
			if (face != null) {
				face.setLivenessWorkflow(admission.workflow.id());
			}
			return;
		}
		if (face == null || admission.device == null) {
			admission.handle.captureFailed(DeviceError.of(DeviceErrorCode.CAPTURE_FAILED, "no face record"));
			throw error(RegistrationExceptionConstants.LIVENESS_CAPTURE_INVALID);
		}
		BindingRequest bindingRequest;
		try {
			SignedFaceCapture capture = SignedCaptureReader.read(face, admission.device, clock.now());
			ImageBuffer image = SignedCaptureReader.decodeFace(capture.isoRecord(), decoder);
			bindingRequest = BindingRequest.from(capture, image, admission.token);
		} catch (ImageDecodeException | RuntimeException e) {
			LOGGER.error("Signed face capture could not be read for liveness binding: {}",
					e.getClass().getSimpleName());
			admission.handle.captureFailed(DeviceError.of(DeviceErrorCode.CAPTURE_FAILED, "capture unreadable"));
			throw error(RegistrationExceptionConstants.LIVENESS_CAPTURE_INVALID);
		}
		LivenessTokenStore.BindOutcome outcome;
		try {
			outcome = admission.handle.bind(bindingRequest, BIND_TIMEOUT_MS);
		} catch (LivenessTokenStore.LivenessTimeoutException e) {
			throw error(RegistrationExceptionConstants.LIVENESS_TIMEOUT);
		}
		BindingVerdict verdict = outcome.verdict();
		if (!verdict.isPassed()) {
			LOGGER.error("Liveness binding failed for {}: {}", admission.workflow.id(), verdict.reasonCodes());
			throw error(verdict.failureReason().orElse(FailureReason.BINDING) == FailureReason.PAD
					? RegistrationExceptionConstants.LIVENESS_PAD_FAILED
					: RegistrationExceptionConstants.LIVENESS_BINDING_FAILED);
		}
		face.setLivenessEvidence(evidence(admission, outcome.result().orElse(null)));
		face.setLivenessWorkflow(admission.workflow.id());
	}

	/**
	 * Tells the session that the capture failed, after a device error or a refusal between {@link #admit} and
	 * {@link #complete}. Safe to call more than once and with {@code null}.
	 *
	 * @param admission the admission
	 */
	public void abort(Admission admission) {
		if (admission != null && admission.handle != null) {
			admission.handle.captureFailed(DeviceError.of(DeviceErrorCode.CAPTURE_FAILED, "capture failed"));
		}
	}

	/**
	 * Tells whether a face authentication sample must be rejected because it has no liveness pass.
	 *
	 * @param modality   the authentication modality ({@code FACE} for face)
	 * @param biometrics the samples about to be matched
	 * @return {@code true} if matching must not proceed
	 */
	public boolean rejectsAuthCapture(String modality, List<BiometricsDto> biometrics) {
		if (modality == null || !RegistrationConstants.FACE.equalsIgnoreCase(modality) || biometrics == null) {
			return false;
		}
		for (BiometricsDto sample : biometrics) {
			if (sample != null && !hasAuthPass(sample)) {
				LOGGER.error("{}: face sample of workflow {} rejected",
						RegistrationExceptionConstants.LIVENESS_AUTH_REJECTED.getErrorCode(),
						sample.getLivenessWorkflow());
				return true;
			}
		}
		return false;
	}

	private boolean hasAuthPass(BiometricsDto sample) {
		Optional<Workflow> workflow = Workflow.fromId(sample.getLivenessWorkflow());
		if (!workflow.isPresent() || !workflow.get().isAuth()) {
			return !config.isEnforcedForAny(AUTH_WORKFLOWS);
		}
		if (!config.isEnforced(workflow.get())) {
			return true;
		}
		Map<String, String> evidence = sample.getLivenessEvidence();
		return evidence != null && PacketEvidenceKeys.DECISION_PASS.equals(evidence.get(PacketEvidenceKeys.DECISION));
	}

	private Map<String, String> evidence(Admission admission, LivenessResult result) {
		Map<String, String> evidence = new LinkedHashMap<>();
		if (result != null) {
			evidence.putAll(PacketEvidenceKeys.of(result));
		}
		Optional<String> supervisor = admission.handle.overrideSupervisor();
		if (supervisor.isPresent()) {
			evidence.put(PacketEvidenceKeys.DECISION, PacketEvidenceKeys.DECISION_OVERRIDE);
			if (!admission.handle.auditsOverride()) {
				audit.emit(new AuditPayloadBuilder(admission.workflow, admission.token.sessionId(), clock)
						.overrideUsed(supervisor.get()));
			}
		} else {
			evidence.put(PacketEvidenceKeys.DECISION, PacketEvidenceKeys.DECISION_PASS);
		}
		return evidence;
	}

	/**
	 * Returns the workflow a capture request is checked for.
	 *
	 * @param request the request
	 * @return the context's workflow; else exception photo when exceptions are listed; else resident capture
	 */
	static Workflow workflowOf(MDMRequestDto request) {
		LivenessContext context = request.getLivenessContext();
		if (context != null) {
			return context.getWorkflow();
		}
		return listsExceptions(request) ? Workflow.EXCEPTION_PHOTO : Workflow.REGISTRATION;
	}

	private static boolean listsExceptions(MDMRequestDto request) {
		return request.getExceptions() != null && request.getExceptions().length > 0;
	}

	/**
	 * Tells whether a capture modality is the face.
	 *
	 * @param modality the modality of a capture request
	 * @return {@code true} for {@code FACE_FULL FACE} and {@code FACE}
	 */
	static boolean isFace(String modality) {
		return RegistrationConstants.FACE_FULLFACE.equalsIgnoreCase(modality)
				|| RegistrationConstants.FACE.equalsIgnoreCase(modality);
	}

	private static BiometricsDto faceCapture(List<BiometricsDto> captures) {
		if (captures == null) {
			return null;
		}
		for (BiometricsDto c : captures) {
			if (c != null && c.getAttributeISO() != null && c.getAttributeISO().length > 0) {
				return c;
			}
		}
		return null;
	}

	private RegBaseCheckedException refusal(Workflow workflow, LivenessTokenStore.Refusal refusal) {
		switch (refusal) {
		case REUSED:
			return refuse(workflow, RegistrationExceptionConstants.LIVENESS_TOKEN_REUSED, ReasonCode.TOKEN_REUSED);
		case EXPIRED:
			return refuse(workflow, RegistrationExceptionConstants.LIVENESS_TOKEN_EXPIRED, ReasonCode.TOKEN_EXPIRED);
		default:
			return refuse(workflow, RegistrationExceptionConstants.LIVENESS_TOKEN_INVALID, ReasonCode.TOKEN_MISMATCH);
		}
	}

	private RegBaseCheckedException refuse(Workflow workflow, RegistrationExceptionConstants code, ReasonCode reason) {
		LOGGER.error("Face capture refused by the liveness gate for {}: {} ({})", workflow.id(), code.getErrorCode(),
				reason);
		audit.emit(new AuditPayloadBuilder(workflow, "", clock)
				.bindingFailed(BindingVerdict.failed(FailureReason.BINDING, reason).build()));
		return error(code);
	}

	private static RegBaseCheckedException error(RegistrationExceptionConstants code) {
		return new RegBaseCheckedException(code.getErrorCode(), code.getErrorMessage());
	}
}
