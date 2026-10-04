/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.mock.sbi.SBIConstant;
import io.mosip.mock.sbi.devicehelper.SBIDeviceHelper;
import io.mosip.mock.sbi.devicehelper.face.SBIFaceHelper;
import io.mosip.mock.sbi.devicehelper.face.fault.FaceFaultSettings;
import io.mosip.mock.sbi.devicehelper.face.fault.FaceFaultType;
import io.mosip.mock.sbi.devicehelper.face.source.FaceFrameSources;
import io.mosip.mock.sbi.devicehelper.face.source.FaceSourceSettings;
import io.mosip.mock.sbi.devicehelper.face.source.FaceSourceType;
import io.mosip.mock.sbi.util.ApplicationPropertyHelper;
import io.mosip.mock.sbi.util.FileHelper;
import io.mosip.registration.mdm.dto.CertificationRequest;
import io.mosip.registration.mdm.dto.FaceFaultRequest;
import io.mosip.registration.mdm.dto.FaceSourceRequest;
import io.mosip.registration.mdm.dto.PadRequest;

/**
 * Admin API of the face liveness extensions: {@code POST /admin/source},
 * {@code /admin/fault}, {@code /admin/certification}, {@code /admin/pad} and
 * {@code GET|POST /admin/liveness} (state). The JSON contract is in
 * LIVENESS-EXTENSIONS.md.
 *
 * <p>
 * These endpoints refuse requests that carry an {@code Origin} header (sent by
 * web browsers) unless {@code mosip.mock.sbi.face.admin.allow.browser=true},
 * because the service answers any origin (CORS {@code *}) and a web page must
 * not be able to point the device at the webcam or at local files. Source paths
 * must lie under {@code mosip.mock.sbi.face.source.allowed.roots}. The ffmpeg
 * executable and raw ffmpeg arguments can only be set in
 * {@code application.properties}.
 * </p>
 */
public class SBIFaceLivenessAdmin {
	/** Property: accept admin requests that carry an Origin header. */
	public static final String KEY_ALLOW_BROWSER = "mosip.mock.sbi.face.admin.allow.browser";
	/** Property: folders that admin-supplied source paths must lie under. */
	public static final String KEY_ALLOWED_ROOTS = "mosip.mock.sbi.face.source.allowed.roots";

	private static final Logger LOGGER = LoggerFactory.getLogger(SBIFaceLivenessAdmin.class);
	private static final String LANG = "en";
	// SBIWorker joins header lines without separators, so the name can follow any character but '-'
	private static final Pattern ORIGIN_HEADER = Pattern.compile("(?i)(?<!-)origin\\s*:");
	private static final List<String> INPUT_FORMATS = List.of("dshow", "v4l2", "avfoundation");

	private final SBIMockService mockService;
	private final String request;
	private final ObjectMapper mapper;

	/**
	 * Creates a handler for one request.
	 *
	 * @param mockService the running mock service
	 * @param request     the raw request text (request line, headers and body)
	 */
	public SBIFaceLivenessAdmin(SBIMockService mockService, String request) {
		this.mockService = mockService;
		this.request = request == null ? "" : request;
		this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	}

	/**
	 * Handles {@code POST /admin/source}.
	 *
	 * @return the JSON response
	 */
	public String processSetSource() {
		String refusal = checkAccess("admin/source");
		if (refusal != null)
			return refusal;
		SBIFaceHelper face = faceHelper();
		if (face == null)
			return error("503", "");
		try {
			FaceSourceRequest body = parseBody(FaceSourceRequest.class);
			checkType(body.getType());
			if (body.getCaptureMode() != null)
				face.getFaceSettings().setCaptureMode(body.getCaptureMode());
			if (body.getSource() != null)
				face.getFrameFeed().setSource(toSourceSettings(body, face.getFrameFeed().getSettings()));
		} catch (IllegalArgumentException | JsonProcessingException ex) {
			return error("511", ex.getMessage());
		} catch (IOException ex) {
			return error("512", ex.getMessage());
		}
		return success(face);
	}

	/**
	 * Handles {@code POST /admin/fault}.
	 *
	 * @return the JSON response
	 */
	public String processSetFault() {
		String refusal = checkAccess("admin/fault");
		if (refusal != null)
			return refusal;
		SBIFaceHelper face = faceHelper();
		if (face == null)
			return error("503", "");
		try {
			FaceFaultRequest body = parseBody(FaceFaultRequest.class);
			checkType(body.getType());
			FaceFaultSettings settings = toFaultSettings(body);
			FaceFaultType previous = face.getFaultInjector().get().getType();
			face.getFaultInjector().set(settings);
			applyStatusFault(face, previous, settings.getType());
			LOGGER.info("Face fault set :: {}", settings.describe());
		} catch (IllegalArgumentException | JsonProcessingException ex) {
			return error("513", ex.getMessage());
		}
		return success(face);
	}

	/**
	 * Handles {@code POST /admin/certification}. Clients cache the level, so they
	 * must re-discover the device afterwards.
	 *
	 * @return the JSON response
	 */
	public String processSetCertification() {
		String refusal = checkAccess("admin/certification");
		if (refusal != null)
			return refusal;
		SBIFaceHelper face = faceHelper();
		if (face == null)
			return error("503", "");
		try {
			CertificationRequest body = parseBody(CertificationRequest.class);
			checkType(body.getType());
			face.getFaceSettings().setCertification(body.getCertification());
			face.initDeviceDetails();
			LOGGER.info("Face certification set :: {}", face.getFaceSettings().getCertification());
		} catch (IllegalArgumentException | JsonProcessingException ex) {
			return error("514", ex.getMessage());
		}
		return success(face);
	}

	/**
	 * Handles {@code POST /admin/pad}.
	 *
	 * @return the JSON response
	 */
	public String processSetPad() {
		String refusal = checkAccess("admin/pad");
		if (refusal != null)
			return refusal;
		SBIFaceHelper face = faceHelper();
		if (face == null)
			return error("503", "");
		try {
			PadRequest body = parseBody(PadRequest.class);
			checkType(body.getType());
			if (body.getDecision() != null)
				face.getFaceSettings().setPadDecision(body.getDecision());
			if (body.getPadCapability() != null)
				face.getFaceSettings().setPadCapability(body.getPadCapability());
			if (body.getDiscovery() != null)
				face.getFaceSettings().setPadInDiscovery(body.getDiscovery());
			face.initDeviceDetails();
		} catch (IllegalArgumentException | JsonProcessingException ex) {
			return error("515", ex.getMessage());
		}
		return success(face);
	}

	/**
	 * Handles {@code GET /admin/liveness} and {@code POST /admin/liveness}: returns
	 * the face device state without changing it.
	 *
	 * @return the JSON response
	 */
	public String processGetState() {
		String refusal = checkAccess("admin/liveness");
		if (refusal != null)
			return refusal;
		SBIFaceHelper face = faceHelper();
		if (face == null)
			return error("503", "");
		return success(face);
	}

	/**
	 * Builds the state object returned by every successful call.
	 *
	 * @param face the face device
	 * @return source, fault and device sections
	 */
	public static Map<String, Object> describeState(SBIFaceHelper face) {
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("source", face.getFrameFeed().describe());
		state.put("fault", face.getFaultInjector().describe());
		Map<String, Object> device = new LinkedHashMap<>(face.getFaceSettings().describe());
		device.put("purpose", face.getPurpose());
		device.put("deviceStatus", face.getDeviceStatus());
		device.put("qualityScore", face.getQualityScore());
		state.put("device", device);
		return state;
	}

	private FaceSourceSettings toSourceSettings(FaceSourceRequest body, FaceSourceSettings current) {
		FaceSourceSettings settings = current.copy();
		settings.setType(FaceSourceType.parse(body.getSource()));
		settings.setPath(checkAllowedPath(body.getPath()));
		settings.setFile(checkAllowedPath(body.getFile()));
		settings.setCamera(body.getCamera());
		if (body.getCamera() != null || body.getFile() != null)
			settings.setInputArgs(null);
		if (body.getFps() != null)
			settings.setFps(body.getFps());
		if (body.getLoop() != null)
			settings.setLoop(body.getLoop());
		if (body.getWidth() != null)
			settings.setWidth(body.getWidth());
		String format = body.getInputFormat();
		if (format != null && !INPUT_FORMATS.contains(format.toLowerCase(Locale.ROOT)))
			throw new IllegalArgumentException("inputFormat must be one of " + INPUT_FORMATS);
		settings.setInputFormat(format);
		return settings;
	}

	private static FaceFaultSettings toFaultSettings(FaceFaultRequest body) {
		FaceFaultType type = FaceFaultType.parse(body.getFault());
		FaceFaultSettings.Builder builder = FaceFaultSettings.builder(type);
		String name = body.getFault().trim().toUpperCase(Locale.ROOT);
		if (name.startsWith("FPS="))
			builder.fps(Double.parseDouble(name.substring(4)));
		if (body.getFps() != null)
			builder.fps(body.getFps());
		if (body.getAfterFrames() != null)
			builder.afterFrames(body.getAfterFrames());
		if (body.getFrames() != null)
			builder.frames(body.getFrames());
		if (body.getDropRatio() != null)
			builder.dropRatio(body.getDropRatio());
		if (body.getDelayMs() != null)
			builder.delayMillis(body.getDelayMs());
		if (body.getDurationMs() != null)
			builder.durationMillis(body.getDurationMs());
		if (body.getLengthDelta() != null)
			builder.lengthDelta(body.getLengthDelta());
		return builder.build();
	}

	private static void applyStatusFault(SBIDeviceHelper face, FaceFaultType previous, FaceFaultType current) {
		if (current == FaceFaultType.BUSY)
			face.setDeviceStatus(SBIConstant.DEVICE_STATUS_ISBUSY);
		else if (current == FaceFaultType.NOT_READY)
			face.setDeviceStatus(SBIConstant.DEVICE_STATUS_NOTREADY);
		else if (previous.isStatusFault())
			face.setDeviceStatus(SBIConstant.DEVICE_STATUS_ISREADY);
	}

	private String checkAllowedPath(String value) {
		if (value == null || value.isBlank())
			return null;
		Path workingDirectory = workingDirectory();
		Path resolved = FaceFrameSources.resolve(workingDirectory, value.trim());
		String roots = ApplicationPropertyHelper.getPropertyKeyValue(KEY_ALLOWED_ROOTS);
		String rootList = roots == null || roots.isBlank() ? "." : roots.replace("\"", "");
		for (String root : rootList.split("[;,]")) {
			if (!root.isBlank() && resolved.startsWith(FaceFrameSources.resolve(workingDirectory, root.trim())))
				return resolved.toString();
		}
		throw new IllegalArgumentException("path is outside " + KEY_ALLOWED_ROOTS + ": " + value);
	}

	private static Path workingDirectory() {
		try {
			return Path.of(FileHelper.getCanonicalPath());
		} catch (IOException ex) {
			return Path.of(".").toAbsolutePath().normalize();
		}
	}

	private String checkAccess(String api) {
		String allowed = ApplicationPropertyHelper.getPropertyKeyValue(SBIConstant.MOSIP_MOCK_SBI_ALLOWED_ADMIN_APIS);
		if (allowed == null || !allowed.contains(api))
			return error("516", api);
		int bodyStart = request.indexOf('{');
		String head = bodyStart >= 0 ? request.substring(0, bodyStart) : request;
		boolean browser = ORIGIN_HEADER.matcher(head).find();
		if (browser && !Boolean.parseBoolean(
				String.valueOf(ApplicationPropertyHelper.getPropertyKeyValue(KEY_ALLOW_BROWSER)).trim())) {
			LOGGER.warn("Refused {} request with an Origin header", api);
			return error("517", "");
		}
		return null;
	}

	private static void checkType(String type) {
		if (type != null && !type.equals(SBIConstant.MOSIP_BIOMETRIC_TYPE_FACE)
				&& !type.equals(SBIConstant.MOSIP_BIOMETRIC_TYPE_BIOMETRIC_DEVICE))
			throw new IllegalArgumentException("type must be Face or Biometric Device");
	}

	private <T> T parseBody(Class<T> type) throws JsonProcessingException {
		int bodyStart = request.indexOf('{');
		if (bodyStart < 0)
			throw new IllegalArgumentException("request has no JSON body");
		return mapper.readValue(request.substring(bodyStart), type);
	}

	private SBIFaceHelper faceHelper() {
		SBIDeviceHelper helper = mockService.getDeviceHelper(
				SBIConstant.MOSIP_BIOMETRIC_TYPE_FACE + "_" + SBIConstant.MOSIP_BIOMETRIC_SUBTYPE_FACE);
		return helper instanceof SBIFaceHelper face ? face : null;
	}

	private String success(SBIFaceHelper face) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("errorCode", "0");
		response.put("errorInfo", SBIJsonInfo.getErrorDescription(LANG, "0"));
		response.put("state", describeState(face));
		try {
			return mapper.writeValueAsString(response);
		} catch (JsonProcessingException ex) {
			LOGGER.error("Face admin state could not be written", ex);
			return error("999", ex.getMessage());
		}
	}

	private static String error(String code, String detail) {
		return SBIJsonInfo.getAdminApiErrorJson(LANG, code, detail == null ? "" : detail);
	}
}
