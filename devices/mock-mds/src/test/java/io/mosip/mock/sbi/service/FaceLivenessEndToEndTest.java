package io.mosip.mock.sbi.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.mock.sbi.SBIConstant;
import io.mosip.mock.sbi.devicehelper.face.FaceIsoBuilder;
import io.mosip.mock.sbi.devicehelper.face.source.FaceFrame;
import io.mosip.mock.sbi.devicehelper.face.source.FaceTestFrames;

/**
 * T-MOCK end to end: a real MockMDS (Registration, Face) on a loopback port,
 * driven over raw HTTP the way the Registration Client drives it.
 */
class FaceLivenessEndToEndTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String FACE_KEY = SBIConstant.MOSIP_BIOMETRIC_TYPE_FACE + "_"
			+ SBIConstant.MOSIP_BIOMETRIC_SUBTYPE_FACE;
	private static final String STREAM_BODY = "{\"deviceId\":\"3\",\"deviceSubId\":\"0\",\"timeout\":\"15000\"}";

	private static SBIMockService service;
	private static int port;
	private static Path sequence;
	private static List<byte[]> sequenceFrames;

	@BeforeAll
	static void startMock() throws Exception {
		sequence = Path.of("target", "test-frames", "e2e-sequence");
		sequenceFrames = new ArrayList<>();
		for (Path file : FaceTestFrames.writeSequence(sequence, 30))
			sequenceFrames.add(Files.readAllBytes(file));
		service = new SBIMockService(SBIConstant.PURPOSE_REGISTRATION, SBIConstant.MOSIP_BIOMETRIC_TYPE_FACE, null,
				SBIConstant.MOSIP_BIOMETRIC_IMAGE_TYPE_JP2000);
		Thread thread = new Thread(service, "mock-mds-e2e");
		thread.setDaemon(true);
		thread.start();
		long deadline = System.currentTimeMillis() + 15_000;
		while (service.getDeviceHelper(FACE_KEY) == null || service.getServerPort() == 0) {
			if (System.currentTimeMillis() > deadline)
				fail("mock did not start");
			Thread.sleep(50);
		}
		port = service.getServerPort();
	}

	@AfterAll
	static void stopMock() throws IOException {
		service.setStopped(true);
		service.stop();
	}

	@BeforeEach
	void resetDevice() throws Exception {
		awaitReady();
		assertOk(admin("fault", "{\"type\":\"Face\",\"fault\":\"NONE\"}"));
		assertOk(admin("certification", "{\"type\":\"Face\",\"certification\":\"L0\"}"));
		assertOk(admin("pad", "{\"type\":\"Face\",\"padCapability\":false,\"discovery\":false}"));
		assertOk(admin("source", "{\"type\":\"Face\",\"source\":\"SEQUENCE\",\"path\":\"" + jsonPath(sequence)
				+ "\",\"fps\":15,\"loop\":true,\"captureMode\":\"FRAME\"}"));
	}

	@Test
	void streamServesChangingSequenceFramesAtSourceRate() throws Exception {
		try (StreamClient stream = new StreamClient()) {
			assertTrue(stream.isMultipart());
			List<byte[]> frames = new ArrayList<>();
			long firstAt = 0;
			for (int i = 0; i < 10; i++) {
				frames.add(stream.nextFrame());
				if (i == 0)
					firstAt = System.nanoTime();
			}
			double seconds = (System.nanoTime() - firstAt) / 1e9;
			Set<String> digests = new HashSet<>();
			for (int i = 0; i < frames.size(); i++) {
				assertEquals(sha(sequenceFrames.get(i)), sha(frames.get(i)), "frame " + i + " in file order");
				digests.add(sha(frames.get(i)));
			}
			assertEquals(10, digests.size());
			assertTrue(seconds > 0.45 && seconds < 1.5, "9 intervals at 15 fps took " + seconds + " s");
		}
	}

	@Test
	void rcaptureDuringStreamSignsAFrameTheClientReceived() throws Exception {
		List<byte[]> received = new ArrayList<>();
		try (StreamClient stream = new StreamClient()) {
			for (int i = 0; i < 5; i++)
				received.add(stream.nextFrame());
			JsonNode capture = MAPPER.readTree(send("RCAPTURE", "/capture", rcaptureBody(null)));
			byte[] frame;
			while ((frame = stream.nextFrame()) != null)
				received.add(frame);
			assertTrue(stream.endedCleanly(), "stream must end after the capture");

			JsonNode data = jwtPayload(capture.path("biometrics").path(0).path("data").asText());
			assertEquals("Face", data.path("bioType").asText());
			assertEquals("95.0", data.path("qualityScore").asText());
			assertFalse(data.has("pad"), "no PAD echo for a stock request");
			byte[] iso = Base64.getUrlDecoder().decode(data.path("bioValue").asText());
			BufferedImage decoded = FaceIsoBuilder.decodeFaceIso(iso, SBIConstant.PURPOSE_REGISTRATION);

			String capturedSha = state().path("source").path("lastCapturedFrame").path("sha256").asText();
			int index = -1;
			for (int i = 0; i < received.size(); i++) {
				if (sha(received.get(i)).equals(capturedSha))
					index = i;
			}
			assertTrue(index >= 4, "captured frame must be one the client received after frame 4, index " + index);
			assertTrue(index >= received.size() - 2, "captured frame must be one of the last two streamed");
			double diff = FaceTestFrames.meanAbsDiff(decoded, FaceTestFrames.decode(received.get(index)));
			assertTrue(diff < 4.0, "decoded ISO image must match the streamed frame, diff " + diff);
		}
	}

	@Test
	void disconnectFaultEndsTheStreamCleanlyAfterFiveFrames() throws Exception {
		assertOk(admin("fault", "{\"type\":\"Face\",\"fault\":\"DISCONNECT\",\"afterFrames\":5}"));
		try (StreamClient stream = new StreamClient()) {
			int frames = 0;
			long start = System.nanoTime();
			while (stream.nextFrame() != null)
				frames++;
			assertEquals(5, frames);
			assertTrue(stream.endedCleanly(), "clean close (FIN), not a reset");
			assertTrue((System.nanoTime() - start) / 1e9 < 3.0);
		}
		awaitReady();
	}

	@Test
	void resetFaultResetsTheConnection() throws Exception {
		assertOk(admin("fault", "{\"type\":\"Face\",\"fault\":\"RST\",\"afterFrames\":3}"));
		try (StreamClient stream = new StreamClient()) {
			int frames = 0;
			while (stream.nextFrame() != null)
				frames++;
			assertTrue(frames <= 3);
			assertFalse(stream.endedCleanly(), "a reset must surface as a connection error");
		}
		awaitReady();
	}

	@Test
	void freezeFaultRepeatsOneFrame() throws Exception {
		assertOk(admin("fault", "{\"type\":\"Face\",\"fault\":\"FREEZE\",\"afterFrames\":2}"));
		try (StreamClient stream = new StreamClient()) {
			List<String> digests = new ArrayList<>();
			for (int i = 0; i < 7; i++)
				digests.add(sha(stream.nextFrame()));
			assertFalse(digests.get(0).equals(digests.get(1)));
			for (int i = 3; i < 7; i++)
				assertEquals(digests.get(2), digests.get(i));
		}
	}

	@Test
	void slowFpsFaultLowersTheFrameRate() throws Exception {
		assertOk(admin("fault", "{\"type\":\"Face\",\"fault\":\"FPS=4\"}"));
		try (StreamClient stream = new StreamClient()) {
			stream.nextFrame();
			long start = System.nanoTime();
			for (int i = 0; i < 4; i++)
				stream.nextFrame();
			double seconds = (System.nanoTime() - start) / 1e9;
			assertTrue(seconds > 0.8 && seconds < 1.8, "4 intervals at 4 fps took " + seconds + " s");
		}
	}

	@Test
	void unavailableFaultRefusesTheStreamBeforeAnyFrame() throws Exception {
		assertOk(admin("fault", "{\"type\":\"Face\",\"fault\":\"UNAVAILABLE\"}"));
		String response = send("STREAM", "/stream", STREAM_BODY);
		assertEquals("608", MAPPER.readTree(response).path("errorCode").asText());
		assertEquals("Ready", state().path("device").path("deviceStatus").asText());
	}

	@Test
	void captureWithUnavailableCameraTimesOutAndFreesTheDevice() throws Exception {
		assertOk(admin("fault", "{\"type\":\"Face\",\"fault\":\"UNAVAILABLE\"}"));
		String body = rcaptureBody(null).replace("\"timeout\":\"10000\"", "\"timeout\":\"1500\"");
		JsonNode capture = MAPPER.readTree(send("RCAPTURE", "/capture", body));
		assertEquals("701", capture.path("biometrics").path(0).path("error").path("errorCode").asText());
		assertEquals("Ready", state().path("device").path("deviceStatus").asText());
	}

	@Test
	void busyFaultIsReportedInDiscoveryAndClears() throws Exception {
		assertOk(admin("fault", "{\"type\":\"Face\",\"fault\":\"BUSY\"}"));
		JsonNode discovery = MAPPER.readTree(send("MOSIPDISC", "/device", "{\"type\":\"Face\"}"));
		assertEquals("Busy", discovery.path(0).path("deviceStatus").asText());
		assertEquals("111", MAPPER.readTree(send("STREAM", "/stream", STREAM_BODY)).path("errorCode").asText());
		assertOk(admin("fault", "{\"type\":\"Face\",\"fault\":\"NONE\"}"));
		assertEquals("Ready", state().path("device").path("deviceStatus").asText());
	}

	@Test
	void sourceSwitchDuringStreamKeepsTheStreamRunning() throws Exception {
		byte[] stock = Files.readAllBytes(FaceTestFrames.STOCK_IMAGE);
		try (StreamClient stream = new StreamClient()) {
			for (int i = 0; i < 3; i++)
				assertEquals(sha(sequenceFrames.get(i)), sha(stream.nextFrame()));
			assertOk(admin("source", "{\"type\":\"Face\",\"source\":\"STATIC\",\"fps\":15}"));
			boolean sawStock = false;
			for (int i = 0; i < 8 && !sawStock; i++)
				sawStock = sha(stream.nextFrame()).equals(sha(stock));
			assertTrue(sawStock, "frames from the new source must follow on the same stream");
		}
	}

	@Test
	void certificationL1ShowsInDiscoveryAndSignedInfo() throws Exception {
		assertOk(admin("certification", "{\"type\":\"Face\",\"certification\":\"L1\"}"));
		JsonNode discovery = MAPPER.readTree(send("MOSIPDISC", "/device", "{\"type\":\"Biometric Device\"}"));
		assertEquals("L1", discovery.path(0).path("certification").asText());
		JsonNode info = deviceInfo();
		assertEquals("L1", info.path("certification").asText());
		assertTrue(jwtSigner(info.path("digitalId").asText()).contains("CN=MosipFaceFTM"));

		assertOk(admin("certification", "{\"type\":\"Face\",\"certification\":\"L0\"}"));
		assertTrue(jwtSigner(deviceInfo().path("digitalId").asText()).startsWith("CN=MosipFace,"));
	}

	@Test
	void padCapabilityInSignedInfoOnlyAndChallengeEchoed() throws Exception {
		assertFalse(deviceInfo().has("padCapability"));
		assertOk(admin("pad", "{\"type\":\"Face\",\"padCapability\":true}"));
		assertEquals("passive", deviceInfo().path("padCapability").path("methods").path(0).asText());
		JsonNode discovery = MAPPER.readTree(send("MOSIPDISC", "/device", "{\"type\":\"Face\"}"));
		assertFalse(discovery.path(0).has("padCapability"), "discovery stays stock unless discovery=true");

		String pad = "{\"required\":true,\"method\":\"passive\",\"challenge\":\"nonce-42\"}";
		JsonNode capture = MAPPER.readTree(send("RCAPTURE", "/capture", rcaptureBody(pad)));
		JsonNode data = jwtPayload(capture.path("biometrics").path(0).path("data").asText());
		assertEquals("nonce-42", data.path("pad").path("challenge").asText());
		assertEquals("NOT_EVALUATED", data.path("pad").path("decision").asText());
	}

	@Test
	void adminApiRejectsBrowsersBadPathsAndBadValues() throws Exception {
		String body = "{\"type\":\"Face\",\"source\":\"STATIC\"}";
		String browser = send("POST", "/admin/source", body, "Origin: http://example.test");
		assertEquals("517", MAPPER.readTree(browser).path("errorCode").asText());
		String outside = admin("source", "{\"type\":\"Face\",\"source\":\"SEQUENCE\",\"path\":\"../..\"}");
		assertEquals("511", MAPPER.readTree(outside).path("errorCode").asText());
		assertEquals("513", MAPPER.readTree(admin("fault", "{\"fault\":\"EXPLODE\"}")).path("errorCode").asText());
		assertEquals("514",
				MAPPER.readTree(admin("certification", "{\"certification\":\"L2\"}")).path("errorCode").asText());
		assertEquals("511", MAPPER.readTree(admin("source", "{\"type\":\"Iris\",\"source\":\"STATIC\"}"))
				.path("errorCode").asText());
		JsonNode state = state();
		assertEquals("SEQUENCE", state.path("source").path("source").asText(), "failed calls change nothing");
	}

	// ---- helpers ----

	private static String rcaptureBody(String pad) {
		return "{\"env\":\"Staging\",\"purpose\":\"Registration\",\"specVersion\":\"0.9.5\",\"timeout\":\"10000\","
				+ "\"captureTime\":\"2026-09-28T10:00:00.000Z\",\"transactionId\":\"TXN-1001\",\"bio\":[{\"type\":\"Face\","
				+ "\"count\":\"1\",\"requestedScore\":\"40\",\"deviceId\":\"3\",\"deviceSubId\":\"0\",\"previousHash\":\"\""
				+ (pad == null ? "" : ",\"pad\":" + pad) + "}]}";
	}

	private static JsonNode deviceInfo() throws IOException {
		JsonNode infos = MAPPER.readTree(send("MOSIPDINFO", "/info", "{}"));
		return jwtPayload(infos.path(0).path("deviceInfo").asText());
	}

	private static JsonNode jwtPayload(String jwt) throws IOException {
		return MAPPER.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
	}

	private static String jwtSigner(String jwt) throws Exception {
		JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]));
		byte[] der = Base64.getDecoder().decode(header.path("x5c").path(0).asText());
		return ((java.security.cert.X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
				.generateCertificate(new java.io.ByteArrayInputStream(der))).getSubjectX500Principal().getName();
	}

	private static JsonNode state() throws IOException {
		JsonNode response = MAPPER.readTree(send("GET", "/admin/liveness", ""));
		assertEquals("0", response.path("errorCode").asText(), response.toString());
		return response.path("state");
	}

	private static void awaitReady() throws Exception {
		long deadline = System.currentTimeMillis() + 8_000;
		while (!"Ready".equals(state().path("device").path("deviceStatus").asText())) {
			if (System.currentTimeMillis() > deadline)
				fail("device did not return to Ready: " + state());
			Thread.sleep(100);
		}
	}

	private static String admin(String api, String body) throws IOException {
		return send("POST", "/admin/" + api, body);
	}

	private static void assertOk(String response) throws IOException {
		assertEquals("0", MAPPER.readTree(response).path("errorCode").asText(), response);
	}

	private static String jsonPath(Path path) {
		return path.toAbsolutePath().toString().replace("\\", "\\\\");
	}

	private static String sha(byte[] bytes) {
		return new FaceFrame(bytes, 0, 0, "T").sha256();
	}

	/** Sends one request and returns the response body. */
	private static String send(String verb, String path, String body, String... headers) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(20_000);
			writeRequest(socket.getOutputStream(), verb, path, body, headers);
			String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int bodyStart = response.indexOf("\r\n\r\n");
			return bodyStart < 0 ? response : response.substring(bodyStart + 4).trim();
		}
	}

	private static void writeRequest(OutputStream out, String verb, String path, String body, String... headers)
			throws IOException {
		byte[] content = body.getBytes(StandardCharsets.UTF_8);
		StringBuilder request = new StringBuilder(verb + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n");
		for (String header : headers)
			request.append(header).append("\r\n");
		request.append("Content-Type: application/json\r\nContent-Length: ").append(content.length).append("\r\n\r\n");
		out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
		out.write(content);
		out.flush();
	}

	/** Minimal MJPEG reader that parses the stream the way the desktop client does. */
	private static final class StreamClient implements AutoCloseable {
		private final Socket socket;
		private final InputStream in;
		private final String mainHeader;
		private boolean endedCleanly;

		StreamClient() throws IOException {
			socket = new Socket("127.0.0.1", port);
			socket.setSoTimeout(5_000);
			writeRequest(socket.getOutputStream(), "STREAM", "/stream", STREAM_BODY);
			in = socket.getInputStream();
			mainHeader = readBlock();
		}

		boolean isMultipart() {
			return mainHeader != null && mainHeader.contains("multipart/x-mixed-replace");
		}

		/** @return the next JPEG part, or null when the stream ends (then see endedCleanly) */
		byte[] nextFrame() throws IOException {
			try {
				String headers = readBlock();
				while (headers != null && headers.isBlank())
					headers = readBlock();
				if (headers == null || !headers.contains("image/jpeg")) {
					endedCleanly = true;
					return null;
				}
				int length = -1;
				for (String line : headers.split("\r\n")) {
					if (line.startsWith("Content-Length:"))
						length = Integer.parseInt(line.substring("Content-Length:".length()).trim());
				}
				byte[] jpeg = in.readNBytes(length);
				if (jpeg.length != length) {
					endedCleanly = true;
					return null;
				}
				return jpeg;
			} catch (IOException ex) {
				endedCleanly = false;
				return null;
			}
		}

		boolean endedCleanly() {
			return endedCleanly;
		}

		/** Reads header lines up to an empty line; null at end of stream. */
		private String readBlock() throws IOException {
			ByteArrayOutputStream block = new ByteArrayOutputStream();
			int matched = 0;
			int b;
			while ((b = in.read()) != -1) {
				block.write(b);
				matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
				if (matched == 4)
					return block.toString(StandardCharsets.US_ASCII);
			}
			return block.size() == 0 ? null : block.toString(StandardCharsets.US_ASCII);
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}
}
