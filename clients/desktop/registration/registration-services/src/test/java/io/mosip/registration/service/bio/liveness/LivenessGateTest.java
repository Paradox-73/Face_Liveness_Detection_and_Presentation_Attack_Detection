package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import io.mosip.biometrics.util.ConvertRequestDto;
import io.mosip.biometrics.util.face.FaceEncoder;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.mdm.dto.MDMRequestDto;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.service.bio.liveness.LivenessFakes.RecordingAudit;
import io.mosip.registration.service.bio.liveness.LivenessFakes.TestClock;

/**
 * Tests {@link LivenessGate}: which face captures need a pass, token checks before the capture, binding and
 * packet evidence after it, and the authentication check.
 */
public class LivenessGateTest {

	private static final String RID = "10001100010002";

	private final Map<String, Object> values = new HashMap<>();
	private final TestClock clock = new TestClock();
	private final RecordingAudit audit = new RecordingAudit();
	private final LivenessTokenStore tokens = new LivenessTokenStore();
	private LivenessGate gate;
	private MdmBioDevice device;

	@Before
	public void setUp() {
		gate = new LivenessGate(LivenessConfig.of(values), tokens, new DesktopImageDecoder(), audit, clock);
		device = new MdmBioDevice();
		device.setSerialNumber(LivenessFakes.SERIAL);
		device.setDeviceId("dev-1");
		device.setCertification("L0");
		device.setSpecVersion("0.9.5");
	}

	private static MDMRequestDto faceRequest(String... exceptions) {
		return new MDMRequestDto(RegistrationConstants.FACE_FULLFACE, exceptions, "Registration", "test", 10000, 1,
				80);
	}

	private LivenessPassToken token(Workflow workflow, String subject, String serial) {
		return LivenessPassToken.builder().sessionId("s-" + workflow.id() + subject).workflow(workflow)
				.subjectRef(subject).deviceSerial(serial).nonce("nonce-0123456789abcdef").issuedAt(clock.now())
				.expiresAt(clock.now().plusMillis(5_000)).build();
	}

	private static void assertRefused(RegistrationExceptionConstants code, ThrowingRunnable call) {
		try {
			call.run();
			fail("expected " + code.getErrorCode());
		} catch (RegBaseCheckedException e) {
			assertEquals(code.getErrorCode(), e.getErrorCode());
		}
	}

	private interface ThrowingRunnable {
		void run() throws RegBaseCheckedException;
	}

	@Test
	public void faceCaptureWithoutContextIsRefusedWhenEnforced() {
		assertRefused(RegistrationExceptionConstants.LIVENESS_CONTEXT_MISSING, () -> gate.admit(faceRequest(), device));
		assertTrue(audit.has(AuditEventId.LIVENESS_BINDING_FAILED.id()));
	}

	@Test
	public void faceCaptureWithoutPassIsRefused() {
		MDMRequestDto request = faceRequest();
		request.setLivenessContext(LivenessContext.withoutPass(Workflow.PACKET_AUTH, "officer1"));
		assertRefused(RegistrationExceptionConstants.LIVENESS_NOT_VERIFIED, () -> gate.admit(request, device));
	}

	@Test
	public void faceCaptureIsAllowedWhenLivenessIsSwitchedOff() throws Exception {
		values.put("mosip.registration.liveness.enable", "N");
		LivenessGate.Admission a = gate.admit(faceRequest(), device);
		assertFalse(a.requiresBinding());
		BiometricsDto face = faceDto(new byte[] { 1, 2, 3 });
		gate.complete(a, Collections.singletonList(face));
		assertNull(face.getLivenessEvidence());
		assertEquals("registration", face.getLivenessWorkflow());
	}

	@Test
	public void captureWithoutPassFollowsTheNamedWorkflowPolicy() throws Exception {
		values.put("mosip.registration.liveness.policy.login_auth", "OFF");
		MDMRequestDto request = faceRequest();
		request.setLivenessContext(LivenessContext.withoutPass(Workflow.LOGIN_AUTH, "officer1"));
		assertEquals(Workflow.LOGIN_AUTH, gate.admit(request, device).workflow());
	}

	@Test
	public void faceCaptureIsAllowedWhenWorkflowPolicyIsOff() throws Exception {
		values.put("mosip.registration.liveness.policy.registration", "OFF");
		assertFalse(gate.admit(faceRequest(), device).requiresBinding());
	}

	@Test
	public void otherModalitiesAreNeverGated() throws Exception {
		MDMRequestDto request = new MDMRequestDto(RegistrationConstants.IRIS_DOUBLE, new String[0], "Registration",
				"test", 10000, 2, 60);
		LivenessGate.Admission a = gate.admit(request, device);
		assertNull(a.workflow());
		gate.complete(a, new ArrayList<>());
	}

	@Test
	public void exceptionPhotoNeedsListedExceptions() throws Exception {
		assertEquals(Workflow.EXCEPTION_PHOTO, gate.admit(faceRequest("leftIndex"), device).workflow());
		MDMRequestDto request = faceRequest();
		request.setLivenessContext(LivenessContext.withoutPass(Workflow.EXCEPTION_PHOTO, RID));
		assertRefused(RegistrationExceptionConstants.LIVENESS_EXCEPTION_PHOTO_INVALID,
				() -> gate.admit(request, device));
	}

	@Test
	public void unknownTokenIsRefused() {
		MDMRequestDto request = faceRequest();
		request.setLivenessContext(LivenessContext.withPass(token(Workflow.REGISTRATION, RID, LivenessFakes.SERIAL), RID));
		assertRefused(RegistrationExceptionConstants.LIVENESS_TOKEN_INVALID, () -> gate.admit(request, device));
	}

	@Test
	public void tokenForAnotherSubjectOrDeviceIsRefused() {
		LivenessPassToken t = token(Workflow.REGISTRATION, RID, LivenessFakes.SERIAL);
		tokens.register(t, new FakeHandle(true));
		MDMRequestDto other = faceRequest();
		other.setLivenessContext(LivenessContext.withPass(t, "another-rid"));
		assertRefused(RegistrationExceptionConstants.LIVENESS_SUBJECT_MISMATCH, () -> gate.admit(other, device));

		LivenessPassToken t2 = token(Workflow.REGISTRATION, RID, "SN-OTHER");
		tokens.register(t2, new FakeHandle(true));
		MDMRequestDto wrongDevice = faceRequest();
		wrongDevice.setLivenessContext(LivenessContext.withPass(t2, RID));
		assertRefused(RegistrationExceptionConstants.LIVENESS_DEVICE_MISMATCH, () -> gate.admit(wrongDevice, device));
	}

	@Test
	public void tokenIsSingleUse() throws Exception {
		LivenessPassToken t = token(Workflow.REGISTRATION, RID, LivenessFakes.SERIAL);
		tokens.register(t, new FakeHandle(true));
		MDMRequestDto request = faceRequest();
		request.setLivenessContext(LivenessContext.withPass(t, RID));
		assertTrue(gate.admit(request, device).requiresBinding());
		assertRefused(RegistrationExceptionConstants.LIVENESS_TOKEN_REUSED, () -> gate.admit(request, device));
	}

	@Test
	public void expiredTokenIsRefused() {
		LivenessPassToken t = token(Workflow.REGISTRATION, RID, LivenessFakes.SERIAL);
		tokens.register(t, new FakeHandle(true));
		clock.advance(6_000);
		MDMRequestDto request = faceRequest();
		request.setLivenessContext(LivenessContext.withPass(t, RID));
		assertRefused(RegistrationExceptionConstants.LIVENESS_TOKEN_EXPIRED, () -> gate.admit(request, device));
	}

	@Test
	public void boundCaptureCarriesPacketEvidence() throws Exception {
		FakeHandle handle = new FakeHandle(true);
		BiometricsDto face = admitAndComplete(handle, isoFace());
		Map<String, String> evidence = face.getLivenessEvidence();
		assertEquals(PacketEvidenceKeys.DECISION_PASS, evidence.get(PacketEvidenceKeys.DECISION));
		assertEquals("PASSIVE", evidence.get(PacketEvidenceKeys.METHOD));
		assertEquals("91", evidence.get(PacketEvidenceKeys.SCORE));
		assertEquals("L0", evidence.get(PacketEvidenceKeys.DEVICE_LEVEL));
		assertEquals("registration", face.getLivenessWorkflow());
		assertTrue("the signed capture was decoded before binding", handle.boundImageWidth.get() > 0);
	}

	@Test
	public void failedBindingRejectsCapture() throws Exception {
		try {
			admitAndComplete(new FakeHandle(false), isoFace());
			fail("binding failure must reject the capture");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_BINDING_FAILED.getErrorCode(), e.getErrorCode());
		}
	}

	@Test
	public void unreadableCaptureFailsTheSession() throws Exception {
		FakeHandle handle = new FakeHandle(true);
		try {
			admitAndComplete(handle, new byte[] { 1, 2, 3, 4 });
			fail("an unreadable ISO record must be refused");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_CAPTURE_INVALID.getErrorCode(), e.getErrorCode());
		}
		assertTrue(handle.failed);
	}

	@Test
	public void overrideIsRecordedAndAudited() throws Exception {
		FakeHandle handle = new FakeHandle(true);
		handle.supervisor = "supervisor1";
		BiometricsDto face = admitAndComplete(handle, isoFace());
		assertEquals(PacketEvidenceKeys.DECISION_OVERRIDE, face.getLivenessEvidence().get(PacketEvidenceKeys.DECISION));
		assertTrue(audit.has(AuditEventId.LIVENESS_OVERRIDE_USED.id()));
	}

	@Test
	public void abortTellsTheSessionThatCaptureFailed() throws Exception {
		LivenessPassToken t = token(Workflow.REGISTRATION, RID, LivenessFakes.SERIAL);
		FakeHandle handle = new FakeHandle(true);
		tokens.register(t, handle);
		MDMRequestDto request = faceRequest();
		request.setLivenessContext(LivenessContext.withPass(t, RID));
		gate.abort(gate.admit(request, device));
		assertTrue(handle.failed);
		gate.abort(null);
	}

	@Test
	public void authSampleWithoutPassIsRejectedWhileEnforced() {
		BiometricsDto sample = faceDto(new byte[] { 1 });
		assertTrue(gate.rejectsAuthCapture("Face", Collections.singletonList(sample)));
		sample.setLivenessWorkflow("login_auth");
		assertTrue(gate.rejectsAuthCapture("Face", Collections.singletonList(sample)));
		sample.setLivenessEvidence(Collections.singletonMap(PacketEvidenceKeys.DECISION, "PASS"));
		assertFalse(gate.rejectsAuthCapture("Face", Collections.singletonList(sample)));
	}

	@Test
	public void residentPassCannotAuthenticateAnOperator() {
		BiometricsDto sample = faceDto(new byte[] { 1 });
		sample.setLivenessWorkflow("registration");
		sample.setLivenessEvidence(Collections.singletonMap(PacketEvidenceKeys.DECISION, "PASS"));
		assertTrue(gate.rejectsAuthCapture("FACE", Collections.singletonList(sample)));
	}

	@Test
	public void authCheckIgnoresOtherModalitiesAndDisabledLiveness() {
		BiometricsDto sample = faceDto(new byte[] { 1 });
		assertFalse(gate.rejectsAuthCapture("Finger", Collections.singletonList(sample)));
		values.put("mosip.registration.liveness.enable", "N");
		assertFalse(gate.rejectsAuthCapture("Face", Collections.singletonList(sample)));
	}

	@Test
	public void authCheckAppliesThePolicyOfTheSampleWorkflow() {
		values.put("mosip.registration.liveness.policy.eod_auth", "OFF");
		BiometricsDto sample = faceDto(new byte[] { 1 });
		sample.setLivenessWorkflow("eod_auth");
		assertFalse(gate.rejectsAuthCapture("Face", Collections.singletonList(sample)));
	}

	private BiometricsDto admitAndComplete(FakeHandle handle, byte[] iso) throws RegBaseCheckedException {
		LivenessPassToken t = token(Workflow.REGISTRATION, RID, LivenessFakes.SERIAL);
		handle.token = t;
		tokens.register(t, handle);
		MDMRequestDto request = faceRequest();
		request.setLivenessContext(LivenessContext.withPass(t, RID));
		LivenessGate.Admission a = gate.admit(request, device);
		BiometricsDto face = faceDto(iso);
		List<BiometricsDto> captures = new ArrayList<>();
		captures.add(face);
		gate.complete(a, captures);
		return face;
	}

	private static BiometricsDto faceDto(byte[] iso) {
		BiometricsDto dto = new BiometricsDto("face", iso, 90);
		dto.setPayLoad("{\"timestamp\":\"" + Instant.now().toString() + "\",\"transactionId\":\"t1\"}");
		return dto;
	}

	/** Builds a signed-capture-like ISO/IEC 19794-5 record around a synthetic JPEG. */
	static byte[] isoFace() {
		ConvertRequestDto request = new ConvertRequestDto();
		request.setModality("Face");
		request.setVersion(SignedCaptureReader.FACE_ISO_VERSION);
		request.setPurpose("Registration");
		request.setImageType(0);
		request.setInputBytes(LivenessFakes.jpeg(120, 160, 3));
		try {
			return FaceEncoder.convertFaceImageToISO(request);
		} catch (Exception e) {
			throw new IllegalStateException("ISO face record could not be built", e);
		}
	}

	/** Pass handle whose binding passes or fails as configured. */
	private final class FakeHandle implements LivenessTokenStore.PassHandle {
		private final boolean pass;
		final AtomicReference<Integer> boundImageWidth = new AtomicReference<>(0);
		LivenessPassToken token;
		String supervisor;
		volatile boolean failed;

		FakeHandle(boolean pass) {
			this.pass = pass;
		}

		@Override
		public void captureStarted() {
		}

		@Override
		public void captureFailed(DeviceError error) {
			failed = true;
		}

		@Override
		public LivenessTokenStore.BindOutcome bind(BindingRequest request, long timeoutMs) {
			boundImageWidth.set(request.captureImage().width());
			if (!pass) {
				return new LivenessTokenStore.BindOutcome(
						BindingVerdict.failed(FailureReason.BINDING, ReasonCode.SIMILARITY_LOW).build(), null);
			}
			Instant now = clock.now();
			LivenessResult result = LivenessResult
					.builder(token.sessionId(), Workflow.REGISTRATION, LivenessFakes.engineInfo(),
							LivenessFakes.descriptor())
					.pass().method(LivenessMethod.PASSIVE).passiveScore(91).bindingSimilarity(88).period(now, now)
					.build();
			return new LivenessTokenStore.BindOutcome(BindingVerdict.passed().build(), result);
		}

		@Override
		public Optional<String> overrideSupervisor() {
			return Optional.ofNullable(supervisor);
		}
	}
}
