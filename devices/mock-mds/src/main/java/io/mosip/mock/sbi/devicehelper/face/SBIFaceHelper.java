package io.mosip.mock.sbi.devicehelper.face;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.biometric.provider.JwtUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.mock.sbi.SBIConstant;
import io.mosip.mock.sbi.devicehelper.SBICaptureInfo;
import io.mosip.mock.sbi.devicehelper.SBIDeviceHelper;
import io.mosip.mock.sbi.devicehelper.face.fault.FaceFaultInjector;
import io.mosip.mock.sbi.devicehelper.face.source.FaceFrame;
import io.mosip.mock.sbi.devicehelper.face.source.FaceSourceSettings;
import io.mosip.mock.sbi.util.ApplicationPropertyHelper;
import io.mosip.mock.sbi.util.BioUtilHelper;
import io.mosip.mock.sbi.util.FileHelper;
import io.mosip.mock.sbi.util.StringHelper;
import io.mosip.registration.mdm.dto.DeviceInfo;
import io.mosip.registration.mdm.dto.DigitalId;
import io.mosip.registration.mdm.dto.DiscoverDto;

/**
 * Mock face device. Besides the stock behaviour it streams frames from a
 * pluggable {@link io.mosip.mock.sbi.devicehelper.face.source.FaceFrameSource},
 * builds the RCAPTURE ISO record from the last streamed frame, injects faults,
 * and can report itself as L1 with an optional padCapability (see
 * LIVENESS-EXTENSIONS.md).
 */
public class SBIFaceHelper extends SBIDeviceHelper {
	private static final Logger LOGGER = LoggerFactory.getLogger(SBIFaceHelper.class);

	private FaceDeviceSettings faceSettings;
	private FaceFaultInjector faultInjector;
	private FaceFrameFeed frameFeed;
	private PrivateKey ftmKey;
	private X509Certificate ftmCertificate;

	private SBIFaceHelper(int port, String purpose, String keystoreFilePath, String biometricImageType) {
		super(port, purpose, SBIConstant.MOSIP_BIOMETRIC_TYPE_FACE, SBIConstant.MOSIP_BIOMETRIC_SUBTYPE_FACE,
				keystoreFilePath, biometricImageType);
		initLivenessExtensions();
		// the super constructor ran before the settings existed; apply them now
		initDeviceDetails();
	}

	private void initLivenessExtensions() {
		FaceSourceSettings sourceSettings;
		try {
			faceSettings = FaceDeviceSettings.fromProperties(ApplicationPropertyHelper::getPropertyKeyValue);
			sourceSettings = FaceSourceSettings.fromProperties(ApplicationPropertyHelper::getPropertyKeyValue);
		} catch (IllegalArgumentException ex) {
			LOGGER.error("Invalid mosip.mock.sbi.face.* setting, using defaults :: {}", ex.getMessage());
			faceSettings = new FaceDeviceSettings();
			sourceSettings = new FaceSourceSettings();
		}
		faultInjector = new FaceFaultInjector();
		Path workingDirectory;
		try {
			workingDirectory = Path.of(FileHelper.getCanonicalPath());
		} catch (IOException ex) {
			workingDirectory = Path.of(".").toAbsolutePath().normalize();
		}
		String streamImage = ApplicationPropertyHelper.getPropertyKeyValue(SBIConstant.MOSIP_STREAM_FACE_SUBID_FULLFACE);
		Path defaultImage = Path.of(workingDirectory + (streamImage == null ? "" : streamImage));
		frameFeed = new FaceFrameFeed(sourceSettings, workingDirectory, defaultImage, faultInjector);
	}

	// synchronized method to control simultaneous access
	public static synchronized SBIFaceHelper getInstance(int port, String purpose, String keystoreFilePath,
			String biometricImageType) {
		return new SBIFaceHelper(port, purpose, keystoreFilePath, biometricImageType);
	}

	@Override
	public long initDevice() {
		SBIFaceCaptureInfo captureInfo = new SBIFaceCaptureInfo();
		captureInfo.initCaptureInfo();
		setCaptureInfo(captureInfo);
		return 0;
	}

	@Override
	public int deInitDevice() {
		if (getCaptureInfo() != null)
			getCaptureInfo().deInitCaptureInfo();
		setCaptureInfo(null);
		frameFeed.close();
		return 0;
	}

	/**
	 * Reads the next frame from the current frame source (the stock code read
	 * {@code Stream Image/0.jpeg} here).
	 *
	 * @return 0 with the frame in the capture info, or -1 when the source has
	 *         ended and the stream must stop
	 */
	@Override
	public int getLiveStream() {
		if (isHoldingStreamForCapture()) {
			// SBI 0.9.5: frames are not added to the stream while RCAPTURE runs
			getCaptureInfo().setImage(null);
			return 0;
		}
		FaceFrame frame = frameFeed.nextFrame();
		if (frame == null || frame.getJpeg().length == 0)
			return -1;
		getCaptureInfo().setImage(frame.getJpeg());

		return 0;
	}

	/**
	 * Tells whether STREAM must pause because an RCAPTURE is running and a frame
	 * for it has already been streamed. Until one frame has been streamed the
	 * stream continues, so a capture started together with the stream (as the
	 * desktop client does) still gets a frame.
	 *
	 * @return true while the stream must not send frames
	 */
	public boolean isHoldingStreamForCapture() {
		SBICaptureInfo info = getCaptureInfo();
		return info != null && info.isCaptureStarted() && frameFeed.getLastServedFrame() != null;
	}

	/**
	 * Fills the capture info for RCAPTURE (or CAPTURE). In FRAME capture mode a
	 * registration capture builds the face ISO record from the last frame
	 * written to STREAM, or from a fresh source frame when no stream runs; the
	 * exception photo and auth captures keep the stock profile files.
	 */
	@Override
	public int getBioCapture(boolean isUsedForAuthenication) throws Exception {
		if (!isUsedForAuthenication && faceSettings.isCaptureFromFrame())
			return getBioCaptureFromFrame();
		return getBioCaptureFromProfile(isUsedForAuthenication);
	}

	@SuppressWarnings({ "java:S112" })
	private int getBioCaptureFromFrame() throws Exception {
		SBIFaceCaptureInfo captureInfo = (SBIFaceCaptureInfo) getCaptureInfo();
		if (captureInfo.isCaptureFace())
			return 0;
		FaceFrame frame = captureInfo.isLiveStreamStarted() ? frameFeed.getLastServedFrame() : frameFeed.grabFrame();
		if (frame == null)
			return 0; // no intact frame yet; RCAPTURE polls again until its timeout
		byte[] isoData = FaceIsoBuilder.buildFaceIso(frame.getJpeg(), getPurpose());
		// stock path first: exception photo, scores and the completed flag
		getBioCaptureFromProfile(false);
		captureInfo.setBioValueFace(StringHelper.base64UrlEncode(isoData));
		if (isScoreFromIso())
			captureInfo.setCaptureScoreFace(BioUtilHelper.getFaceQualityScoreFromIso(getPurpose(), isoData));
		else
			captureInfo.setCaptureScoreFace(getQualityScore());
		captureInfo.setCaptureFace(true);
		frameFeed.markCaptured(frame);
		LOGGER.info("getBioCapture :: face ISO built from frame sequence {} of source {}", frame.getSequence(),
				frame.getSourceName());
		return 0;
	}

	@SuppressWarnings({ "java:S3776" })
	private int getBioCaptureFromProfile(boolean isUsedForAuthenication) throws Exception {
		byte[] isoData = null;

		String seedName = "";
		if (this.getProfileId().equalsIgnoreCase(SBIConstant.PROFILE_AUTOMATIC)) {
			int seedValue = -1;
			if (this.getPurpose().equalsIgnoreCase(SBIConstant.PURPOSE_AUTH) && ApplicationPropertyHelper
					.getPropertyKeyValue(SBIConstant.MOSIP_BIOMETRIC_AUTH_SEED_FACE) != null) {
				seedValue = Integer.parseInt(
						ApplicationPropertyHelper.getPropertyKeyValue(SBIConstant.MOSIP_BIOMETRIC_AUTH_SEED_FACE));
				seedName = String.format("%04d", getRandomNumberForSeed(seedValue)).trim();
			} else if (this.getPurpose().equalsIgnoreCase(SBIConstant.PURPOSE_REGISTRATION) && ApplicationPropertyHelper
					.getPropertyKeyValue(SBIConstant.MOSIP_BIOMETRIC_REGISTRATION_SEED_FACE) != null) {
				seedValue = Integer.parseInt(ApplicationPropertyHelper
						.getPropertyKeyValue(SBIConstant.MOSIP_BIOMETRIC_REGISTRATION_SEED_FACE));
				seedName = String.format("%04d", getRandomNumberForSeed(seedValue)).trim();
			}
		}

		if (!isUsedForAuthenication) {
			isoData = getBiometricISOImage(seedName, SBIConstant.PROFILE_BIO_FILE_NAME_FACE);
			if (isoData != null && !((SBIFaceCaptureInfo) getCaptureInfo()).isCaptureFace()) {
				((SBIFaceCaptureInfo) getCaptureInfo()).setBioValueFace(StringHelper.base64UrlEncode(isoData));
				if (isScoreFromIso())
					((SBIFaceCaptureInfo) getCaptureInfo())
							.setCaptureScoreFace(BioUtilHelper.getFaceQualityScoreFromIso(getPurpose(), isoData));
				else
					((SBIFaceCaptureInfo) getCaptureInfo()).setCaptureScoreFace(getQualityScore());
			}
			((SBIFaceCaptureInfo) getCaptureInfo()).setCaptureFace(true);

			isoData = getBiometricISOImage(seedName, SBIConstant.PROFILE_BIO_FILE_NAME_FACE_EXCEPTION);
			if (isoData != null && !((SBIFaceCaptureInfo) getCaptureInfo()).isCaptureExceptionPhoto()) {
				((SBIFaceCaptureInfo) getCaptureInfo())
						.setBioValueExceptionPhoto(StringHelper.base64UrlEncode(isoData));
				if (isScoreFromIso())
					((SBIFaceCaptureInfo) getCaptureInfo())
							.setCaptureScoreFace(BioUtilHelper.getFaceQualityScoreFromIso(getPurpose(), isoData));
				else
					((SBIFaceCaptureInfo) getCaptureInfo()).setCaptureScoreFace(getQualityScore());
			}
			((SBIFaceCaptureInfo) getCaptureInfo()).setCaptureExceptionPhoto(true);

			if (((SBIFaceCaptureInfo) getCaptureInfo()).isCaptureFace()
					|| ((SBIFaceCaptureInfo) getCaptureInfo()).isCaptureExceptionPhoto()) {
				getCaptureInfo().setCaptureCompleted(true);
			}
		} else {
			isoData = getBiometricISOImage(seedName, SBIConstant.PROFILE_BIO_FILE_NAME_FACE);
			if (isoData != null && !((SBIFaceCaptureInfo) getCaptureInfo()).isCaptureFace()) {
				getCaptureInfo().addBiometricForBioSubType(SBIConstant.BIO_NAME_UNKNOWN,
						StringHelper.base64UrlEncode(isoData));
				if (isScoreFromIso())
					((SBIFaceCaptureInfo) getCaptureInfo())
							.setCaptureScoreFace(BioUtilHelper.getFaceQualityScoreFromIso(getPurpose(), isoData));
				else
					((SBIFaceCaptureInfo) getCaptureInfo()).setCaptureScoreFace(getQualityScore());
				((SBIFaceCaptureInfo) getCaptureInfo()).setCaptureFace(true);
			}

			if (((SBIFaceCaptureInfo) getCaptureInfo()).isCaptureFace())
				getCaptureInfo().setCaptureCompleted(true);
		}
		return 0;
	}

	/**
	 * Returns the face quality score: the value set by {@code /admin/score} for
	 * Face, else {@code mosip.mock.sbi.face.quality.score} (default 95), so that
	 * captures pass a face threshold of 90 without being force-captured.
	 */
	@Override
	public float getQualityScore() {
		if (faceSettings == null || isQualityScoreSet())
			return super.getQualityScore();
		return faceSettings.getQualityScore();
	}

	/**
	 * Applies the configured certification level and, when enabled for
	 * discovery, the padCapability object.
	 */
	@Override
	protected DiscoverDto getDiscoverInfo(String deviceType, String deviceSubType, DigitalId digitalId) {
		DiscoverDto discoverDto = super.getDiscoverInfo(deviceType, deviceSubType, digitalId);
		if (discoverDto == null || faceSettings == null)
			return discoverDto;
		if (faceSettings.getCertification() != null)
			discoverDto.setCertification(faceSettings.getCertification());
		if (faceSettings.isPadCapability() && faceSettings.isPadInDiscovery())
			discoverDto.setPadCapability(faceSettings.padCapabilityObject());
		return discoverDto;
	}

	/**
	 * Applies the configured certification level. For L1 the digitalId is signed
	 * with the FTM key ({@code mosipfaceftm.p12}) as the SBI specification
	 * requires; the stock code does that only for Auth purpose. Adds
	 * padCapability to the signed DeviceInfo when enabled.
	 */
	@Override
	protected DeviceInfo getDeviceInfo(String deviceType, String deviceSubType, DigitalId digitalId) {
		DeviceInfo deviceInfo = super.getDeviceInfo(deviceType, deviceSubType, digitalId);
		if (deviceInfo == null || faceSettings == null)
			return deviceInfo;
		if (faceSettings.getCertification() != null)
			deviceInfo.setCertification(faceSettings.getCertification());
		if (faceSettings.isL1() && !SBIConstant.PURPOSE_AUTH.equalsIgnoreCase(getPurpose())
				&& !SBIConstant.DEVICE_STATUS_NOTREGISTERED.equalsIgnoreCase(getDeviceStatus())) {
			String signed = signWithFtmKey(digitalId);
			if (signed != null)
				deviceInfo.setDigitalId(signed);
		}
		if (faceSettings.isPadCapability())
			deviceInfo.setPadCapability(faceSettings.padCapabilityObject());
		return deviceInfo;
	}

	private synchronized String signWithFtmKey(DigitalId digitalId) {
		try {
			if (ftmKey == null) {
				String keystore = getKeystoreFilePath() + ApplicationPropertyHelper
						.getPropertyKeyValue(SBIConstant.MOSIP_STREAM_FACE_KEYSTORE_FILE_NAME_FTM);
				String alias = ApplicationPropertyHelper.getPropertyKeyValue(SBIConstant.MOSIP_STREAM_FACE_KEY_ALIAS_FTM);
				char[] password = ApplicationPropertyHelper
						.getPropertyKeyValue(SBIConstant.MOSIP_STREAM_FACE_KEYSTORE_PWD_FTM).toCharArray();
				KeyStore keyStore = KeyStore.getInstance("PKCS12");
				try (FileInputStream in = new FileInputStream(keystore)) {
					keyStore.load(in, password);
				}
				ftmKey = (PrivateKey) keyStore.getKey(alias, password);
				ftmCertificate = (X509Certificate) keyStore.getCertificate(alias);
			}
			byte[] payload = new ObjectMapper().writeValueAsString(digitalId).getBytes(StandardCharsets.UTF_8);
			return JwtUtility.getJwt(payload, ftmKey, ftmCertificate);
		} catch (Exception ex) {
			LOGGER.error("L1 digitalId could not be signed with the FTM key", ex);
			return null;
		}
	}

	/**
	 * Builds the PAD block echoed in the signed RCAPTURE data (MOSIP issue
	 * android-registration-client#1117). Returned only when the device
	 * advertises padCapability and the request carries {@code bio[0].pad}, so
	 * stock clients never see the field.
	 *
	 * @param rawRequest the raw RCAPTURE request text
	 * @return {@code {method, challenge, decision}}, or null
	 */
	public Map<String, Object> getPadEcho(String rawRequest) {
		if (faceSettings == null || !faceSettings.isPadCapability() || rawRequest == null
				|| rawRequest.indexOf('{') < 0)
			return null;
		try {
			JsonNode pad = new ObjectMapper().readTree(rawRequest.substring(rawRequest.indexOf('{'))).path("bio")
					.path(0).path("pad");
			if (!pad.isObject())
				return null;
			Map<String, Object> echo = new LinkedHashMap<>();
			echo.put("method", pad.path("method").asText("passive"));
			if (pad.hasNonNull("challenge"))
				echo.put("challenge", pad.path("challenge").asText());
			echo.put("decision", faceSettings.getPadDecision());
			return echo;
		} catch (IOException ex) {
			LOGGER.warn("getPadEcho :: request is not valid JSON :: {}", ex.getMessage());
			return null;
		}
	}

	/** @return the face device settings (certification, PAD, capture mode, score) */
	public FaceDeviceSettings getFaceSettings() {
		return faceSettings;
	}

	/** @return the fault injector */
	public FaceFaultInjector getFaultInjector() {
		return faultInjector;
	}

	/** @return the frame feed (current source and served frames) */
	public FaceFrameFeed getFrameFeed() {
		return frameFeed;
	}
}