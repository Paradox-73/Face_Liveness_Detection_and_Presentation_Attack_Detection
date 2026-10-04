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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.SignedFaceCapture;

/**
 * Writes and reads SBI 0.9.5 face capture responses (the JSON a device returns to {@code rCapture}).
 *
 * <p>The built-in camera writes exactly the fields of ARC's {@code CaptureDto} in the signed payload, because ARC
 * parses the payload with a default Jackson mapper that rejects unknown fields. The link to the liveness session
 * (session ID, SHA-256 of the session frame that was stored, pass-token nonce) is therefore carried in the signed
 * JWS protected header under {@value #LIVENESS_HEADER}; the signature covers header and payload, and ARC keeps
 * the header together with the signature in the packet ({@code getJWTSignatureWithHeader}).
 */
public final class SbiCaptureCodec {

    /** SBI specification version of the response. */
    public static final String SPEC_VERSION = "0.9.5";
    /** JWS protected-header member that holds the liveness binding. */
    public static final String LIVENESS_HEADER = "liveness";
    /** Timestamp format of SBI 0.9.5 as ARC checks it ({@code BiometricsService.validateResponseTimestamp}). */
    public static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private SbiCaptureCodec() {
    }

    /** What the device signs in one capture. */
    public static final class CaptureContent {
        final DeviceDescriptor device;
        final byte[] isoRecord;
        final String transactionId;
        final Instant capturedAt;
        final String purpose;
        final String environment;
        final float qualityScore;
        final String requestedScore;
        final String sessionId;
        final String frameSha256;
        final String nonce;

        /**
         * Collects the content.
         *
         * @param device         the device
         * @param isoRecord      ISO/IEC 19794-5 face record
         * @param transactionId  the transaction ID of the capture request
         * @param capturedAt     capture time
         * @param purpose        {@code Registration} or {@code Auth}
         * @param environment    SBI environment, for example {@code Developer}
         * @param qualityScore   reported quality 0-100
         * @param requestedScore the requested quality, as text
         * @param sessionId      liveness session ID, or {@code null}
         * @param frameSha256    hex SHA-256 of the session frame, or {@code null}
         * @param nonce          pass-token nonce, or {@code null}
         */
        public CaptureContent(DeviceDescriptor device, byte[] isoRecord, String transactionId, Instant capturedAt,
                              String purpose, String environment, float qualityScore, String requestedScore,
                              String sessionId, String frameSha256, String nonce) {
            this.device = device;
            this.isoRecord = isoRecord;
            this.transactionId = transactionId;
            this.capturedAt = capturedAt;
            this.purpose = purpose;
            this.environment = environment;
            this.qualityScore = qualityScore;
            this.requestedScore = requestedScore;
            this.sessionId = sessionId;
            this.frameSha256 = frameSha256;
            this.nonce = nonce;
        }
    }

    /**
     * Builds and signs the capture JWS: header with {@code x5c} and the liveness binding, payload with the
     * {@code CaptureDto} fields and a signed digital ID.
     *
     * @param content what to sign
     * @param signer  the device key
     * @return the compact JWS
     * @throws GeneralSecurityException if signing fails
     */
    public static String signCapture(CaptureContent content, DeviceSigner signer) throws GeneralSecurityException {
        try {
            JSONObject binding = new JSONObject();
            putIfPresent(binding, "sessionId", content.sessionId);
            putIfPresent(binding, "frameSha256", content.frameSha256);
            putIfPresent(binding, "nonce", content.nonce);
            binding.put("capturedAt", TIMESTAMP.format(content.capturedAt));
            JSONObject header = header(signer).put(LIVENESS_HEADER, binding);

            JSONObject payload = new JSONObject();
            payload.put("digitalId", signDigitalId(content.device, content.capturedAt, signer));
            payload.put("bioType", "Face");
            payload.put("deviceCode", content.device.serialNumber());
            payload.put("deviceServiceVersion", content.device.specVersion());
            payload.put("bioSubType", "");
            payload.put("purpose", content.purpose);
            payload.put("env", content.environment);
            payload.put("bioValue", B64URL.encodeToString(content.isoRecord));
            payload.put("transactionId", content.transactionId);
            payload.put("timestamp", TIMESTAMP.format(content.capturedAt));
            payload.put("requestedScore", content.requestedScore);
            payload.put("qualityScore", (double) content.qualityScore);
            return sign(header, payload, signer);
        } catch (JSONException e) {
            throw new GeneralSecurityException("capture could not be written", e);
        }
    }

    /**
     * Wraps a signed capture in the SBI 0.9.5 {@code rCapture} response JSON that ARC parses.
     *
     * @param signedData the capture JWS
     * @param isoRecord  the face record, for the SBI hash
     * @return the response JSON
     */
    public static String responseJson(String signedData, byte[] isoRecord) {
        try {
            JSONObject error = new JSONObject().put("errorCode", "0").put("errorInfo", "Success");
            JSONObject bio = new JSONObject().put("specVersion", SPEC_VERSION).put("data", signedData)
                    .put("hash", chainedHash(isoRecord)).put("error", error);
            return new JSONObject().put("biometrics", new JSONArray().put(bio)).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads the first face capture of an {@code rCapture} response. The signature is not verified here: ARC
     * verifies it against its DEVICE trust store ({@code Biometrics095Service.handleRCaptureResponse}).
     *
     * @param responseJson  the device response
     * @param certification the device's certification level
     * @return the capture
     * @throws DeviceException with {@code CAPTURE_FAILED} if the device reported an error or the response is
     *                         malformed
     */
    public static SignedFaceCapture parse(String responseJson, CertificationLevel certification)
            throws DeviceException {
        try {
            JSONObject bio = new JSONObject(responseJson).getJSONArray("biometrics").getJSONObject(0);
            JSONObject error = bio.optJSONObject("error");
            if (error != null && !"0".equals(error.optString("errorCode", "0"))) {
                throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED,
                        "device reported error " + error.optString("errorCode"));
            }
            String jws = bio.getString("data");
            String[] parts = jws.split("\\.");
            if (parts.length != 3) {
                throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "capture is not a compact JWS");
            }
            JSONObject header = new JSONObject(new String(B64URL_DEC.decode(parts[0]), StandardCharsets.UTF_8));
            JSONObject payload = new JSONObject(new String(B64URL_DEC.decode(parts[1]), StandardCharsets.UTF_8));
            SignedFaceCapture.Builder b = SignedFaceCapture.builder()
                    .isoRecord(B64URL_DEC.decode(payload.getString("bioValue")))
                    .signedData(jws)
                    .capturedAt(parseTimestamp(payload.optString("timestamp")))
                    .transactionId(payload.optString("transactionId"))
                    .deviceSerial(payload.optString("deviceCode"))
                    .certification(certification)
                    .specVersion(bio.optString("specVersion", SPEC_VERSION));
            if (payload.has("qualityScore")) {
                b.qualityScore(payload.getDouble("qualityScore"));
            }
            JSONObject binding = header.optJSONObject(LIVENESS_HEADER);
            if (binding != null && binding.has("frameSha256")) {
                b.sessionFrameSha256(binding.getString("frameSha256"));
            }
            return b.build();
        } catch (JSONException | IllegalArgumentException e) {
            throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "capture response is malformed", e);
        }
    }

    /**
     * Returns the hex SHA-256 of some bytes.
     *
     * @param data bytes
     * @return lower-case hex digest
     */
    public static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String signDigitalId(DeviceDescriptor device, Instant at, DeviceSigner signer)
            throws GeneralSecurityException, JSONException {
        JSONObject id = new JSONObject();
        id.put("serialNo", device.serialNumber());
        id.put("make", device.make());
        id.put("model", device.model());
        id.put("type", "Face");
        id.put("deviceSubType", "Full face");
        id.put("deviceProvider", device.make());
        id.put("deviceProviderId", device.providerId());
        id.put("dateTime", TIMESTAMP.format(at));
        return sign(header(signer), id, signer);
    }

    private static JSONObject header(DeviceSigner signer) throws GeneralSecurityException, JSONException {
        JSONObject header = new JSONObject().put("alg", "RS256").put("typ", "JWT");
        List<X509Certificate> chain = signer.certificateChain();
        if (!chain.isEmpty()) {
            JSONArray x5c = new JSONArray();
            for (X509Certificate c : chain) {
                x5c.put(Base64.getEncoder().encodeToString(c.getEncoded()));
            }
            header.put("x5c", x5c);
        }
        return header;
    }

    private static String sign(JSONObject header, JSONObject payload, DeviceSigner signer)
            throws GeneralSecurityException {
        String input = B64URL.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
                + B64URL.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + B64URL.encodeToString(signature);
    }

    /** SBI hash of the first capture of a transaction: SHA-256 of (empty previous hash + SHA-256 of the data). */
    private static String chainedHash(byte[] isoRecord) {
        return sha256Hex(sha256Hex(isoRecord).getBytes(StandardCharsets.US_ASCII));
    }

    private static Instant parseTimestamp(String text) throws DeviceException {
        try {
            return Instant.from(TIMESTAMP.parse(text));
        } catch (DateTimeParseException | NullPointerException e) {
            throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "capture timestamp is malformed", e);
        }
    }

    private static void putIfPresent(JSONObject o, String key, String value) throws JSONException {
        if (value != null) {
            o.put(key, value);
        }
    }
}
