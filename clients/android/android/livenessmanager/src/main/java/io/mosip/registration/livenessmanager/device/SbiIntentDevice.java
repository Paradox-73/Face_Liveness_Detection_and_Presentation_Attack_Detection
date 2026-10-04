/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.device;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

import io.mosip.liveness.core.device.CaptureRequest;
import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.DevicePurpose;
import io.mosip.liveness.core.device.DeviceStatus;
import io.mosip.liveness.core.device.DeviceTransport;
import io.mosip.liveness.core.device.FaceCaptureDevice;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.device.StreamRequest;

/**
 * A vendor SBI face device app reached through Android intents ({@code io.sbi.device} discovery and
 * {@code rCapture}), the path ARC uses for external biometric devices. It is the face device when
 * {@code mosip.registration.liveness.android.device=SBI} (see {@link FaceDeviceSelection}).
 *
 * <p>SBI 0.9.5 on Android gives the client no preview frames (the device app shows its own), so a device found by
 * {@link #discover} is not stream-capable unless the {@link Bridge} says it can stream: {@link #openStream} then
 * fails with {@code NO_STREAM} and {@code mosip.registration.liveness.no_stream_policy} decides (refuse the capture,
 * or keep a capture recorded with method {@code CAPTURE_ONLY}). The capture itself is the device app's signed
 * {@code rCapture} response, which ARC checks as for any SBI device.
 */
public final class SbiIntentDevice implements FaceCaptureDevice {

    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
    private static final Base64.Decoder B64 = Base64.getDecoder();

    /** Runs ARC's SBI intents for one device app. */
    public interface Bridge {

        /**
         * Runs the device app's discovery for face devices ({@code io.sbi.device} with type {@code Face}).
         *
         * @return the SBI 0.9.5 discover response JSON (an array of devices)
         * @throws DeviceException if no device app answers, it is cancelled or times out
         */
        String discover() throws DeviceException;

        /**
         * Tells whether the device app can stream preview frames to the client. SBI 0.9.5 intents cannot.
         *
         * @return {@code true} if {@link #openStream} delivers frames
         */
        default boolean canStream() {
            return false;
        }

        /**
         * Opens the device app's preview stream.
         *
         * @param request stream parameters
         * @return the frame source
         * @throws DeviceException {@code NO_STREAM} unless {@link #canStream()} is {@code true}
         */
        default FaceFrameSource openStream(StreamRequest request) throws DeviceException {
            throw new DeviceException(DeviceErrorCode.NO_STREAM, "Android SBI devices do not stream to the client");
        }

        /**
         * Starts the device app's {@code rCapture} and waits for its answer.
         *
         * @param request capture parameters (transaction ID, timeout, optional PAD request)
         * @return the SBI 0.9.5 response JSON
         * @throws DeviceException if the device app fails, is cancelled or times out
         */
        String capture(CaptureRequest request) throws DeviceException;
    }

    private final DeviceDescriptor descriptor;
    private final Bridge bridge;
    private final DeviceStatus status;
    private final String callbackId;
    private volatile String lastResponseJson;

    /**
     * Creates the adapter from what ARC's discovery and device info found.
     *
     * @param descriptor device identity; transport {@code SBI_INTENT}
     * @param status     status from the last discovery or device info call
     * @param bridge     ARC's intent chain
     */
    public SbiIntentDevice(DeviceDescriptor descriptor, DeviceStatus status, Bridge bridge) {
        this(descriptor, status, bridge, "");
    }

    private SbiIntentDevice(DeviceDescriptor descriptor, DeviceStatus status, Bridge bridge, String callbackId) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.status = Objects.requireNonNull(status, "status");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.callbackId = callbackId == null ? "" : callbackId;
    }

    /**
     * Discovers the face device of the installed SBI app and creates the adapter for it. Devices that report an
     * error are skipped; a {@code Ready} device is preferred.
     *
     * @param bridge  ARC's intent chain
     * @param purpose purpose to use when the device does not state one
     * @return the device
     * @throws DeviceException {@code NOT_FOUND} if no face device answered, {@code NOT_READY} if the only face
     *                         devices are not registered, or the bridge's error
     */
    public static SbiIntentDevice discover(Bridge bridge, DevicePurpose purpose) throws DeviceException {
        Objects.requireNonNull(bridge, "bridge");
        Objects.requireNonNull(purpose, "purpose");
        String json = bridge.discover();
        if (json == null || json.trim().isEmpty()) {
            throw new DeviceException(DeviceErrorCode.NOT_FOUND, "no SBI face device answered");
        }
        JSONArray devices;
        try {
            String trimmed = json.trim();
            devices = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONArray().put(new JSONObject(trimmed));
        } catch (JSONException e) {
            throw new DeviceException(DeviceErrorCode.NOT_FOUND, "discover response is malformed", e);
        }
        JSONObject chosen = null;
        DeviceStatus chosenStatus = null;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null || hasError(d) || !isFace(d)) {
                continue;
            }
            DeviceStatus s = DeviceStatus.fromCode(d.optString("deviceStatus")).orElse(DeviceStatus.NOT_READY);
            if (chosen == null || (s == DeviceStatus.READY && chosenStatus != DeviceStatus.READY)) {
                chosen = d;
                chosenStatus = s;
            }
        }
        if (chosen == null) {
            throw new DeviceException(DeviceErrorCode.NOT_FOUND, "no SBI face device answered");
        }
        if (chosenStatus == DeviceStatus.NOT_REGISTERED) {
            throw new DeviceException(DeviceErrorCode.NOT_READY, "SBI face device is not registered");
        }
        return new SbiIntentDevice(describe(chosen, purpose, bridge.canStream()), chosenStatus, bridge,
                chosen.optString("callbackId", ""));
    }

    @Override
    public DeviceDescriptor describe() {
        return descriptor;
    }

    @Override
    public DeviceStatus status() {
        return status;
    }

    /**
     * Returns the intent action prefix of the device app, from discovery.
     *
     * @return for example {@code io.sbi.device}; empty when unknown
     */
    public String callbackId() {
        return callbackId;
    }

    @Override
    public FaceFrameSource openStream(StreamRequest request) throws DeviceException {
        if (!descriptor.isStreamCapable()) {
            throw new DeviceException(DeviceErrorCode.NO_STREAM, "Android SBI devices do not stream to the client");
        }
        FaceFrameSource source = bridge.openStream(request);
        if (source == null) {
            throw new DeviceException(DeviceErrorCode.STREAM_OPEN_FAILED, "device app returned no stream");
        }
        return source;
    }

    @Override
    public SignedFaceCapture capture(CaptureRequest request) throws DeviceException {
        String json = bridge.capture(request);
        if (json == null || json.isEmpty()) {
            throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "device app returned no capture");
        }
        SignedFaceCapture capture = SbiCaptureCodec.parse(json, descriptor.certification());
        lastResponseJson = json;
        return capture;
    }

    /**
     * Returns the device app's last successful {@code rCapture} response, for ARC's SBI response checks.
     *
     * @return the response JSON, or {@code null} before a capture
     */
    public String lastResponseJson() {
        return lastResponseJson;
    }

    private static boolean hasError(JSONObject device) {
        JSONObject error = device.optJSONObject("error");
        return error != null && !"0".equals(error.optString("errorCode", "0").trim());
    }

    private static boolean isFace(JSONObject device) {
        JSONObject id = digitalId(device);
        String type = id != null ? id.optString("type", "") : "";
        if (type.isEmpty()) {
            type = device.optString("type", "Face");
        }
        return "face".equalsIgnoreCase(type.trim());
    }

    private static DeviceDescriptor describe(JSONObject device, DevicePurpose fallbackPurpose, boolean streams)
            throws DeviceException {
        JSONObject id = digitalId(device);
        String serial = id != null ? id.optString("serialNo", "") : "";
        if (serial.isEmpty()) {
            serial = device.optString("deviceCode", "");
        }
        if (serial.isEmpty()) {
            serial = device.optString("deviceId", "");
        }
        if (serial.isEmpty()) {
            throw new DeviceException(DeviceErrorCode.NOT_FOUND, "SBI face device has no serial number");
        }
        CertificationLevel level = CertificationLevel.fromCode(device.optString("certification"))
                .orElse(CertificationLevel.L0);
        return DeviceDescriptor.builder()
                .deviceId(device.optString("deviceId", ""))
                .serialNumber(serial)
                .make(id != null ? id.optString("make", "") : "")
                .model(id != null ? id.optString("model", "") : "")
                .providerId(id != null ? id.optString("deviceProviderId", "") : "")
                .certification(level)
                .specVersion(specVersion(device))
                .purpose(DevicePurpose.fromCode(device.optString("purpose")).orElse(fallbackPurpose))
                .transport(DeviceTransport.SBI_INTENT)
                .streamCapable(streams)
                .padCapable(device.optBoolean("padCapability", false))
                .build();
    }

    private static String specVersion(JSONObject device) {
        JSONArray versions = device.optJSONArray("specVersion");
        String v = versions != null && versions.length() > 0 ? versions.optString(0, "")
                : device.optString("specVersion", "");
        return v.trim().isEmpty() ? SbiCaptureCodec.SPEC_VERSION : v.trim();
    }

    /** The discover response's digital ID: base64 JSON, or a JWS whose payload is that JSON. */
    private static JSONObject digitalId(JSONObject device) {
        String raw = device.optString("digitalId", "").trim();
        if (raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split("\\.");
        String payload = parts.length == 3 ? parts[1] : raw;
        for (Base64.Decoder decoder : new Base64.Decoder[] {B64URL, B64}) {
            try {
                return new JSONObject(new String(decoder.decode(payload), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException | JSONException e) {
                // Try the other alphabet, then give up.
            }
        }
        return null;
    }
}
