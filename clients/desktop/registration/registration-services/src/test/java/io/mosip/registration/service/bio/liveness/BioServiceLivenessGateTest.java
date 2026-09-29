package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.mdm.dto.MDMRequestDto;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.mdm.integrator.MosipDeviceSpecificationProvider;
import io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory;
import io.mosip.registration.service.bio.impl.BioServiceImpl;

/**
 * Tests the liveness gate where it is enforced: {@code BioServiceImpl.captureModality}. A face capture that needs
 * liveness never reaches the device without a valid pass, and every capture path goes through this method.
 */
public class BioServiceLivenessGateTest {

	private final Map<String, Object> values = new HashMap<>();
	private final LivenessTokenStore tokens = new LivenessTokenStore();
	private final LivenessFakes.RecordingAudit audit = new LivenessFakes.RecordingAudit();
	private BioServiceImpl bioService;
	private MosipDeviceSpecificationProvider provider;
	private MdmBioDevice device;

	@Before
	public void setUp() throws Exception {
		ApplicationContext.getInstance();
		if (ApplicationContext.map() == null) {
			ApplicationContext.setApplicationMap(new HashMap<>());
		}
		device = new MdmBioDevice();
		device.setSerialNumber(LivenessFakes.SERIAL);
		device.setSpecVersion("0.9.5");
		device.setCertification("L0");
		MosipDeviceSpecificationFactory factory = Mockito.mock(MosipDeviceSpecificationFactory.class);
		provider = Mockito.mock(MosipDeviceSpecificationProvider.class);
		Mockito.when(factory.getDeviceInfoByModality(Mockito.anyString())).thenReturn(device);
		Mockito.when(factory.getMdsProvider(Mockito.anyString())).thenReturn(provider);
		BiometricsDto face = new BiometricsDto("face", LivenessGateTest.isoFace(), 90);
		face.setPayLoad("{\"timestamp\":\"" + Instant.now() + "\"}");
		List<BiometricsDto> captures = Collections.singletonList(face);
		Mockito.when(provider.rCapture(Mockito.any(), Mockito.any())).thenReturn(captures);

		bioService = new BioServiceImpl();
		ReflectionTestUtils.setField(bioService, "deviceSpecificationFactory", factory);
		ReflectionTestUtils.setField(bioService, "livenessGate", new LivenessGate(LivenessConfig.of(values), tokens,
				new DesktopImageDecoder(), audit, Clock.system()));
	}

	private static MDMRequestDto faceRequest() {
		return new MDMRequestDto(RegistrationConstants.FACE_FULLFACE, new String[0], "Registration", "test", 10000, 1,
				80);
	}

	@Test
	public void faceCaptureWithoutPassNeverReachesTheDevice() throws Exception {
		try {
			bioService.captureModality(faceRequest());
			fail("face capture without liveness must be refused");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_CONTEXT_MISSING.getErrorCode(), e.getErrorCode());
		}
		Mockito.verify(provider, Mockito.never()).rCapture(Mockito.any(), Mockito.any());
	}

	@Test
	public void authCaptureWithoutPassIsRefused() throws Exception {
		MDMRequestDto request = faceRequest();
		request.setLivenessContext(LivenessContext.withoutPass(Workflow.LOGIN_AUTH, "officer1"));
		try {
			bioService.captureModality(request);
			fail("refused");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_NOT_VERIFIED.getErrorCode(), e.getErrorCode());
		}
	}

	@Test
	public void faceCaptureProceedsWhenLivenessIsOff() throws Exception {
		values.put("mosip.registration.liveness.enable", "N");
		List<BiometricsDto> result = bioService.captureModality(faceRequest());
		assertEquals(1, result.size());
	}

	@Test
	public void padFailureRejectsCaptureAndPassCannotBeReused() throws Exception {
		LivenessPassToken t = LivenessPassToken.builder().sessionId("s1").workflow(Workflow.REGISTRATION)
				.subjectRef("rid").deviceSerial(LivenessFakes.SERIAL).nonce("nonce-0123456789abcdef").issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(5)).build();
		tokens.register(t, new LivenessTokenStore.PassHandle() {
			@Override
			public void captureStarted() {
			}

			@Override
			public void captureFailed(DeviceError error) {
			}

			@Override
			public LivenessTokenStore.BindOutcome bind(BindingRequest request, long timeoutMs) {
				return new LivenessTokenStore.BindOutcome(
						BindingVerdict.failed(FailureReason.PAD, ReasonCode.CAPTURE_PAD_SCORE).build(), null);
			}
		});
		MDMRequestDto request = faceRequest();
		request.setLivenessContext(LivenessContext.withPass(t, "rid"));
		try {
			bioService.captureModality(request);
			fail("PAD failure on the captured image must refuse the capture");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_PAD_FAILED.getErrorCode(), e.getErrorCode());
		}
		try {
			bioService.captureModality(request);
			fail("second use of the same pass");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_TOKEN_REUSED.getErrorCode(), e.getErrorCode());
		}
		Mockito.verify(provider, Mockito.times(1)).rCapture(Mockito.any(), Mockito.any());
		assertTrue(audit.events.size() > 0);
	}
}
