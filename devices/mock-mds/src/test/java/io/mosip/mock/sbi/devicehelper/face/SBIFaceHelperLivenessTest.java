package io.mosip.mock.sbi.devicehelper.face;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.mock.sbi.SBIConstant;
import io.mosip.mock.sbi.devicehelper.face.source.FaceFrame;
import io.mosip.mock.sbi.devicehelper.face.source.FaceSourceSettings;
import io.mosip.mock.sbi.devicehelper.face.source.FaceSourceType;
import io.mosip.mock.sbi.devicehelper.face.source.FaceTestFrames;
import io.mosip.mock.sbi.util.StringHelper;

/**
 * T-MOCK: capture from the streamed frame, quality score, L1 signing and
 * padCapability on the face device helper.
 */
class SBIFaceHelperLivenessTest {
	private static final String PURPOSE = SBIConstant.PURPOSE_REGISTRATION;

	@TempDir
	Path tempDir;

	private SBIFaceHelper helper;
	private List<Path> frames;

	@BeforeEach
	void setUp() throws Exception {
		frames = FaceTestFrames.writeSequence(tempDir.resolve("seq"), 6);
		helper = SBIFaceHelper.getInstance(4501, PURPOSE, null, SBIConstant.MOSIP_BIOMETRIC_IMAGE_TYPE_JP2000);
		helper.setProfileId(SBIConstant.PROFILE_DEFAULT);
		FaceSourceSettings settings = new FaceSourceSettings();
		settings.setType(FaceSourceType.SEQUENCE);
		settings.setPath(tempDir.resolve("seq").toString());
		settings.setLoop(false);
		helper.getFrameFeed().setSource(settings);
	}

	@AfterEach
	void tearDown() {
		helper.deInitDevice();
	}

	@Test
	void captureDuringStreamSignsTheLastStreamedFrame() throws Exception {
		helper.initDevice();
		helper.getCaptureInfo().setLiveStreamStarted(true);
		assertTrue(helper.getFrameFeed().open());
		FaceFrame served = null;
		for (int i = 0; i < 4; i++) {
			assertEquals(0, helper.getLiveStream());
			served = helper.getFrameFeed().getCurrentFrame();
			helper.getFrameFeed().markServed(served, true);
		}
		assertEquals(3, served.getSequence());

		assertEquals(0, helper.getBioCapture(false));
		SBIFaceCaptureInfo info = (SBIFaceCaptureInfo) helper.getCaptureInfo();
		assertTrue(info.isCaptureFace());
		assertTrue(info.isCaptureCompleted());
		assertEquals(95.0f, info.getCaptureScoreFace());

		byte[] iso = StringHelper.base64UrlDecode(info.getBioValueFace());
		assertEquals("FAC", new String(iso, 0, 3, StandardCharsets.US_ASCII));
		BufferedImage decoded = FaceIsoBuilder.decodeFaceIso(iso, PURPOSE);
		BufferedImage streamed = FaceTestFrames.decode(served.getJpeg());
		double toStreamed = FaceTestFrames.meanAbsDiff(decoded, streamed);
		double toFirst = FaceTestFrames.meanAbsDiff(decoded, FaceTestFrames.decode(Files.readAllBytes(frames.get(0))));
		assertTrue(toStreamed < 4.0, "decoded capture must match the streamed frame, diff " + toStreamed);
		assertTrue(toStreamed < toFirst, "capture must be closer to frame 3 than to frame 0");
		assertEquals(served.sha256(), helper.getFrameFeed().getLastCapturedFrame().sha256());
	}

	@Test
	void captureWaitsWhileStreamingButNothingWasServed() throws Exception {
		helper.initDevice();
		helper.getCaptureInfo().setLiveStreamStarted(true);
		assertTrue(helper.getFrameFeed().open());
		assertEquals(0, helper.getBioCapture(false));
		assertFalse(((SBIFaceCaptureInfo) helper.getCaptureInfo()).isCaptureFace());
		assertFalse(helper.getCaptureInfo().isCaptureCompleted());
	}

	@Test
	void captureWithoutStreamUsesAFreshSourceFrame() throws Exception {
		helper.initDevice();
		assertEquals(0, helper.getBioCapture(false));
		SBIFaceCaptureInfo info = (SBIFaceCaptureInfo) helper.getCaptureInfo();
		BufferedImage decoded = FaceIsoBuilder.decodeFaceIso(StringHelper.base64UrlDecode(info.getBioValueFace()),
				PURPOSE);
		BufferedImage first = FaceTestFrames.decode(Files.readAllBytes(frames.get(0)));
		assertTrue(FaceTestFrames.meanAbsDiff(decoded, first) < 4.0);
		assertEquals(0, helper.getFrameFeed().getLastCapturedFrame().getSequence());
	}

	@Test
	void profileModeReturnsTheStockIsoFile() throws Exception {
		helper.getFaceSettings().setCaptureMode(FaceDeviceSettings.CAPTURE_PROFILE);
		helper.initDevice();
		helper.getBioCapture(false);
		byte[] expected = Files.readAllBytes(Path.of("Profile", "Default", "Registration", "Face.iso"));
		assertArrayEquals(expected,
				StringHelper.base64UrlDecode(((SBIFaceCaptureInfo) helper.getCaptureInfo()).getBioValueFace()));
	}

	@Test
	void qualityScoreDefaultsTo95UnlessSetByAdminScore() {
		assertEquals(95.0f, helper.getQualityScore());
		helper.setQualityScore(30.0f);
		helper.setQualityScoreSet(true);
		assertEquals(30.0f, helper.getQualityScore());
	}

	@Test
	void l1SignsTheDigitalIdWithTheFtmKey() throws Exception {
		assertEquals("CN=MosipFace", signerCommonName(helper.getDeviceInfo().getDigitalId()));
		assertEquals("L0", helper.getDeviceInfo().getCertification());

		helper.getFaceSettings().setCertification("L1");
		helper.initDeviceDetails();
		assertEquals("L1", helper.getDeviceInfo().getCertification());
		assertEquals("L1", helper.getDiscoverDto().getCertification());
		assertEquals("CN=MosipFaceFTM", signerCommonName(helper.getDeviceInfo().getDigitalId()));
	}

	@Test
	void padCapabilityOnlyInDeviceInfoByDefault() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		assertFalse(mapper.writeValueAsString(helper.getDeviceInfo()).contains("padCapability"),
				"stock output unchanged while disabled");
		assertFalse(mapper.writeValueAsString(helper.getDiscoverDto()).contains("padCapability"));

		helper.getFaceSettings().setPadCapability(true);
		helper.initDeviceDetails();
		JsonNode info = mapper.readTree(mapper.writeValueAsString(helper.getDeviceInfo()));
		assertEquals("passive", info.path("padCapability").path("methods").path(0).asText());
		assertNull(helper.getDiscoverDto().getPadCapability());

		helper.getFaceSettings().setPadInDiscovery(true);
		helper.initDeviceDetails();
		assertNotNull(helper.getDiscoverDto().getPadCapability());
	}

	@Test
	void padEchoRequiresCapabilityAndARequestPadObject() {
		String request = "RCAPTURE /capture HTTP/1.1Content-Length: 99{\"env\":\"Staging\",\"bio\":[{\"type\":\"Face\","
				+ "\"pad\":{\"required\":true,\"method\":\"passive\",\"challenge\":\"n-123\"}}]}";
		assertNull(helper.getPadEcho(request), "no echo while padCapability is off");
		helper.getFaceSettings().setPadCapability(true);
		Map<String, Object> echo = helper.getPadEcho(request);
		assertEquals("n-123", echo.get("challenge"));
		assertEquals("passive", echo.get("method"));
		assertEquals("NOT_EVALUATED", echo.get("decision"));
		assertNull(helper.getPadEcho(request.replace("\"pad\"", "\"other\"")));
	}

	private static String signerCommonName(String jwt) throws Exception {
		String header = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]), StandardCharsets.UTF_8);
		String x5c = new ObjectMapper().readTree(header).path("x5c").path(0).asText();
		X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
				.generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(x5c)));
		String subject = cert.getSubjectX500Principal().getName();
		return subject.substring(subject.indexOf("CN="), subject.indexOf(',', subject.indexOf("CN=")) < 0
				? subject.length() : subject.indexOf(',', subject.indexOf("CN=")));
	}
}
