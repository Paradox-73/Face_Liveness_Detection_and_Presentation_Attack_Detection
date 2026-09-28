package io.mosip.mock.sbi.devicehelper.face;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Run-time settings of the mock face device that are not about the frame
 * source: certification level, PAD capability (MOSIP issue
 * android-registration-client#1117), capture mode and default quality score.
 * Read from {@code application.properties} and changed by the admin API.
 * Thread-safe.
 */
public class FaceDeviceSettings {
	/** Property: certification level L0 or L1; empty keeps DeviceInfo.json. */
	public static final String KEY_CERTIFICATION = "mosip.mock.sbi.face.certification";
	/** Property: advertise padCapability in the signed DeviceInfo. */
	public static final String KEY_PAD_CAPABILITY = "mosip.mock.sbi.face.pad.capability";
	/** Property: also advertise padCapability in discovery (breaks stock clients). */
	public static final String KEY_PAD_DISCOVERY = "mosip.mock.sbi.face.pad.discovery";
	/** Property: PAD decision echoed in RCAPTURE. */
	public static final String KEY_PAD_DECISION = "mosip.mock.sbi.face.pad.decision";
	/** Property: capture mode FRAME or PROFILE. */
	public static final String KEY_CAPTURE_MODE = "mosip.mock.sbi.face.capture.mode";
	/** Property: face quality score reported when /admin/score has not set one. */
	public static final String KEY_QUALITY_SCORE = "mosip.mock.sbi.face.quality.score";

	/** Certification level L0 (software trust). */
	public static final String L0 = "L0";
	/** Certification level L1 (trust from a secure chip, FTM-signed digitalId). */
	public static final String L1 = "L1";
	/** Capture mode: build the ISO from the current stream frame. */
	public static final String CAPTURE_FRAME = "FRAME";
	/** Capture mode: return the profile's Face.iso (stock behaviour). */
	public static final String CAPTURE_PROFILE = "PROFILE";
	/** PAD decisions accepted by {@link #setPadDecision(String)}. */
	public static final List<String> PAD_DECISIONS = List.of("NOT_EVALUATED", "NO_ATTACK", "ATTACK");
	/** Default face quality score, above the usual face threshold of 90. */
	public static final float DEFAULT_QUALITY_SCORE = 95.0f;

	private volatile String certification;
	private volatile boolean padCapability;
	private volatile boolean padInDiscovery;
	private volatile String padDecision = "NOT_EVALUATED";
	private volatile String captureMode = CAPTURE_FRAME;
	private volatile float qualityScore = DEFAULT_QUALITY_SCORE;

	/**
	 * Reads the settings from properties; missing keys keep their defaults.
	 *
	 * @param properties function that returns a property value or null
	 * @return the settings
	 */
	public static FaceDeviceSettings fromProperties(UnaryOperator<String> properties) {
		FaceDeviceSettings settings = new FaceDeviceSettings();
		String value = clean(properties.apply(KEY_CERTIFICATION));
		if (value != null)
			settings.setCertification(value);
		value = clean(properties.apply(KEY_PAD_CAPABILITY));
		if (value != null)
			settings.setPadCapability(Boolean.parseBoolean(value));
		value = clean(properties.apply(KEY_PAD_DISCOVERY));
		if (value != null)
			settings.setPadInDiscovery(Boolean.parseBoolean(value));
		value = clean(properties.apply(KEY_PAD_DECISION));
		if (value != null)
			settings.setPadDecision(value);
		value = clean(properties.apply(KEY_CAPTURE_MODE));
		if (value != null)
			settings.setCaptureMode(value);
		value = clean(properties.apply(KEY_QUALITY_SCORE));
		if (value != null)
			settings.setQualityScore(Float.parseFloat(value));
		return settings;
	}

	/**
	 * Returns the padCapability object advertised to clients. Provisional shape,
	 * pending the final SBI text for issue #1117.
	 *
	 * @return {@code {"methods":["passive"],"challenge":true}}
	 */
	public Map<String, Object> padCapabilityObject() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("methods", List.of("passive"));
		map.put("challenge", Boolean.TRUE);
		return map;
	}

	/**
	 * Describes the settings for the admin state response.
	 *
	 * @return a map of setting names to values
	 */
	public Map<String, Object> describe() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("certification", certification == null ? "(DeviceInfo.json)" : certification);
		map.put("padCapability", padCapability);
		map.put("padInDiscovery", padInDiscovery);
		map.put("padDecision", padDecision);
		map.put("captureMode", captureMode);
		map.put("defaultQualityScore", qualityScore);
		return map;
	}

	private static String clean(String value) {
		if (value == null)
			return null;
		String trimmed = value.trim().replace("\"", "");
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** @return L0, L1, or null to keep the value in DeviceInfo.json */
	public String getCertification() {
		return certification;
	}

	/**
	 * @param value L0 or L1 (any case)
	 * @throws IllegalArgumentException for other values
	 */
	public void setCertification(String value) {
		String level = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		if (!L0.equals(level) && !L1.equals(level))
			throw new IllegalArgumentException("certification must be L0 or L1");
		this.certification = level;
	}

	/** @return true when the device is L1 */
	public boolean isL1() {
		return L1.equals(certification);
	}

	/** @return true when padCapability is advertised in the signed DeviceInfo */
	public boolean isPadCapability() {
		return padCapability;
	}

	/** @param padCapability advertise padCapability in the signed DeviceInfo */
	public void setPadCapability(boolean padCapability) {
		this.padCapability = padCapability;
	}

	/** @return true when padCapability is also put in discovery responses */
	public boolean isPadInDiscovery() {
		return padInDiscovery;
	}

	/** @param padInDiscovery also put padCapability in discovery responses */
	public void setPadInDiscovery(boolean padInDiscovery) {
		this.padInDiscovery = padInDiscovery;
	}

	/** @return PAD decision echoed in RCAPTURE */
	public String getPadDecision() {
		return padDecision;
	}

	/**
	 * @param value NOT_EVALUATED, NO_ATTACK or ATTACK (any case)
	 * @throws IllegalArgumentException for other values
	 */
	public void setPadDecision(String value) {
		String decision = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		if (!PAD_DECISIONS.contains(decision))
			throw new IllegalArgumentException("padDecision must be one of " + PAD_DECISIONS);
		this.padDecision = decision;
	}

	/** @return FRAME or PROFILE */
	public String getCaptureMode() {
		return captureMode;
	}

	/** @return true when RCAPTURE builds the ISO from the current frame */
	public boolean isCaptureFromFrame() {
		return CAPTURE_FRAME.equals(captureMode);
	}

	/**
	 * @param value FRAME or PROFILE (any case)
	 * @throws IllegalArgumentException for other values
	 */
	public void setCaptureMode(String value) {
		String mode = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		if (!CAPTURE_FRAME.equals(mode) && !CAPTURE_PROFILE.equals(mode))
			throw new IllegalArgumentException("captureMode must be FRAME or PROFILE");
		this.captureMode = mode;
	}

	/** @return face quality score used when /admin/score has not set one */
	public float getQualityScore() {
		return qualityScore;
	}

	/**
	 * @param value score in (0, 100]
	 * @throws IllegalArgumentException outside that range
	 */
	public void setQualityScore(float value) {
		if (!(value > 0.0f) || value > 100.0f)
			throw new IllegalArgumentException("quality score must be greater than 0 and at most 100");
		this.qualityScore = value;
	}
}
