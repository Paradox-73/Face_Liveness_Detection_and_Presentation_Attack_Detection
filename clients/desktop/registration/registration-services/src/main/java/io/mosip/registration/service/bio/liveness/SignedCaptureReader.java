package io.mosip.registration.service.bio.liveness;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.biometrics.util.ConvertRequestDto;
import io.mosip.biometrics.util.face.FaceDecoder;
import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DevicePadDecision;
import io.mosip.liveness.core.device.DevicePadResult;
import io.mosip.liveness.core.device.PadMethod;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.mdm.dto.MdmBioDevice;

/**
 * Reads a signed SBI face capture ({@link BiometricsDto} from {@code rCapture}) for stream-to-capture binding.
 *
 * <p>The capture's JWT signature has already been verified by the device provider against the DEVICE trust
 * domain. This class only extracts what binding needs: the ISO/IEC 19794-5 record, the capture time, the
 * transaction ID and, for devices with {@code padCapability}, the echoed challenge and the device's own PAD
 * decision (provisional shape of MOSIP issue #1117: {@code "pad": {"method", "challenge", "decision"}}).
 */
public final class SignedCaptureReader {

	/** ISO/IEC 19794-5 version name used by the MOSIP biometrics utility. */
	public static final String FACE_ISO_VERSION = "ISO19794_5_2011";

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final DateTimeFormatter SBI_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

	private SignedCaptureReader() {
	}

	/**
	 * Builds the core capture from a signed SBI capture.
	 *
	 * @param capture    the face capture returned by {@code rCapture}
	 * @param device     the device that signed it
	 * @param receivedAt time the capture arrived; used when the payload has no readable timestamp
	 * @return the capture
	 * @throws IllegalArgumentException if the capture has no ISO record
	 */
	public static SignedFaceCapture read(BiometricsDto capture, MdmBioDevice device, Instant receivedAt) {
		if (capture.getAttributeISO() == null || capture.getAttributeISO().length == 0) {
			throw new IllegalArgumentException("face capture has no ISO record");
		}
		JsonNode payload = parse(capture.getPayLoad());
		SignedFaceCapture.Builder b = SignedFaceCapture.builder().isoRecord(capture.getAttributeISO())
				.signedData(capture.getPayLoad() == null ? "" : capture.getPayLoad())
				.capturedAt(timestamp(payload).orElse(receivedAt))
				.transactionId(text(payload, "transactionId").orElse(""))
				.deviceSerial(serialOf(device))
				.certification(certificationOf(device))
				.specVersion(firstNonEmpty(capture.getSpecVersion(), device.getSpecVersion(), "unknown"));
		double quality = capture.getQualityScore();
		if (quality >= 0 && quality <= 100) {
			b.qualityScore(quality);
		}
		JsonNode pad = payload == null ? null : payload.get("pad");
		if (pad != null && pad.isObject()) {
			text(pad, "challenge").ifPresent(b::echoedChallenge);
			devicePad(pad).ifPresent(b::devicePad);
		}
		return b.build();
	}

	/**
	 * Decodes the face image inside a signed ISO/IEC 19794-5 record (JPEG or JPEG 2000) to BGR888.
	 *
	 * @param isoRecord the record
	 * @param decoder   the image decoder
	 * @return the image
	 * @throws ImageDecodeException if the record or its image cannot be decoded
	 */
	public static ImageBuffer decodeFace(byte[] isoRecord, DesktopImageDecoder decoder) throws ImageDecodeException {
		ConvertRequestDto request = new ConvertRequestDto();
		request.setVersion(FACE_ISO_VERSION);
		request.setInputBytes(isoRecord);
		BufferedImage image;
		try {
			image = FaceDecoder.convertFaceISOToBufferedImage(request);
		} catch (Exception e) {
			throw new ImageDecodeException("ISO face record could not be decoded: " + e.getClass().getSimpleName(), e);
		}
		return decoder.toBgr(image);
	}

	/**
	 * Returns the serial number that identifies a device in pass tokens and bindings.
	 *
	 * @param device the device
	 * @return the serial number, else the device ID, else {@code "unknown"}
	 */
	public static String serialOf(MdmBioDevice device) {
		return firstNonEmpty(device.getSerialNumber(), device.getDeviceId(), "unknown");
	}

	/**
	 * Returns the SBI certification level of a device.
	 *
	 * @param device the device
	 * @return L0 or L1; L0 when the device reports another or no value
	 */
	public static CertificationLevel certificationOf(MdmBioDevice device) {
		return CertificationLevel.fromCode(device.getCertification()).orElse(CertificationLevel.L0);
	}

	private static Optional<DevicePadResult> devicePad(JsonNode pad) {
		Optional<String> decision = text(pad, "decision");
		if (!decision.isPresent()) {
			return Optional.empty();
		}
		PadMethod method = text(pad, "method").flatMap(PadMethod::fromWireValue).orElse(null);
		String d = decision.get().toUpperCase(Locale.ROOT);
		if ("ATTACK".equals(d) || "FAIL".equals(d)) {
			return Optional.of(DevicePadResult.of(DevicePadDecision.FAIL, method, null));
		}
		if ("NO_ATTACK".equals(d) || "PASS".equals(d)) {
			return Optional.of(DevicePadResult.of(DevicePadDecision.PASS, method, null));
		}
		return Optional.empty();
	}

	private static Optional<Instant> timestamp(JsonNode payload) {
		Optional<String> ts = text(payload, "timestamp");
		if (!ts.isPresent()) {
			return Optional.empty();
		}
		try {
			return Optional.of(Instant.parse(ts.get()));
		} catch (DateTimeParseException e) {
			try {
				return Optional.of(LocalDateTime.parse(ts.get(), SBI_TIMESTAMP).toInstant(ZoneOffset.UTC));
			} catch (DateTimeParseException e2) {
				return Optional.empty();
			}
		}
	}

	private static JsonNode parse(String payload) {
		if (payload == null || payload.isEmpty()) {
			return null;
		}
		try {
			return MAPPER.readTree(payload);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static Optional<String> text(JsonNode node, String field) {
		if (node == null) {
			return Optional.empty();
		}
		JsonNode v = node.get(field);
		return v == null || v.isNull() || v.asText().trim().isEmpty() ? Optional.<String>empty()
				: Optional.of(v.asText().trim());
	}

	private static String firstNonEmpty(String... values) {
		for (String v : values) {
			if (v != null && !v.trim().isEmpty()) {
				return v.trim();
			}
		}
		return "";
	}
}
