/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.device;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.DevicePurpose;
import io.mosip.liveness.core.device.DeviceTransport;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.registration.clientmanager.dto.sbi.CaptureDto;
import io.mosip.registration.clientmanager.dto.sbi.CaptureResponse;

/** Checks the built-in camera's SBI 0.9.5 capture against ARC's own response classes. */
@RunWith(RobolectricTestRunner.class)
public class SbiCaptureCodecTest {

    private static final Instant AT = Instant.parse("2026-09-29T08:30:15Z");
    private static final byte[] ISO = {0x46, 0x41, 0x43, 0x00, 1, 2, 3, 4, 5};

    private final KeyPair keys = rsa();
    private final DeviceSigner signer = new DeviceSigner() {
        @Override
        public byte[] sign(byte[] data) throws java.security.GeneralSecurityException {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(keys.getPrivate());
            s.update(data);
            return s.sign();
        }

        @Override
        public List<X509Certificate> certificateChain() {
            return Collections.emptyList();
        }

        @Override
        public boolean isProvisioned() {
            return false;
        }
    };

    @Test
    public void signedPayloadHasExactlyArcCaptureDtoFields() throws Exception {
        String jws = SbiCaptureCodec.signCapture(content(), signer);
        String[] parts = jws.split("\\.");
        assertEquals(3, parts.length);
        // ARC's Biometrics095Service parses the payload with a default ObjectMapper, which rejects unknown fields.
        CaptureDto dto = new ObjectMapper().readValue(Base64.getUrlDecoder().decode(parts[1]),
                new TypeReference<CaptureDto>() { });
        assertEquals("Face", dto.getBioType());
        assertEquals("", dto.getBioSubType());
        assertEquals("Registration", dto.getPurpose());
        assertEquals("tx-1", dto.getTransactionId());
        assertEquals("2026-09-29T08:30:15Z", dto.getTimestamp());
        assertEquals("SERIAL-1", dto.getDeviceCode());
        assertEquals(90f, dto.getQualityScore(), 0.001);
        assertArrayEquals(ISO, Base64.getUrlDecoder().decode(dto.getBioValue()));
        // The digital ID is itself a signed JWT.
        assertEquals(3, dto.getDigitalId().split("\\.").length);
    }

    @Test
    public void signatureCoversHeaderAndPayload() throws Exception {
        String jws = SbiCaptureCodec.signCapture(content(), signer);
        int dot = jws.lastIndexOf('.');
        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(keys.getPublic());
        verify.update(jws.substring(0, dot).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verify.verify(Base64.getUrlDecoder().decode(jws.substring(dot + 1))));

        JSONObject header = new JSONObject(new String(Base64.getUrlDecoder().decode(jws.split("\\.")[0]),
                StandardCharsets.UTF_8));
        assertEquals("RS256", header.getString("alg"));
        JSONObject binding = header.getJSONObject(SbiCaptureCodec.LIVENESS_HEADER);
        assertEquals("session-1", binding.getString("sessionId"));
        assertEquals("abcd", binding.getString("frameSha256"));
        assertEquals("nonce-0123456789ab", binding.getString("nonce"));
        assertFalse("no certificate for an unprovisioned test key", header.has("x5c"));
    }

    @Test
    public void responseParsesWithArcAndBack() throws Exception {
        String jws = SbiCaptureCodec.signCapture(content(), signer);
        String json = SbiCaptureCodec.responseJson(jws, ISO);

        CaptureResponse arc = new ObjectMapper().readValue(json, CaptureResponse.class);
        assertEquals(1, arc.getBiometrics().size());
        assertEquals("0.9.5", arc.getBiometrics().get(0).getSpecVersion());
        assertEquals("0", arc.getBiometrics().get(0).getError().getErrorCode());
        assertEquals(jws, arc.getBiometrics().get(0).getData());

        SignedFaceCapture capture = SbiCaptureCodec.parse(json, CertificationLevel.L0);
        assertArrayEquals(ISO, capture.isoRecord());
        assertEquals("tx-1", capture.transactionId());
        assertEquals(AT, capture.capturedAt());
        assertEquals("SERIAL-1", capture.deviceSerial());
        assertEquals("abcd", capture.sessionFrameSha256().orElse(""));
    }

    @Test
    public void deviceErrorIsReported() {
        try {
            SbiCaptureCodec.parse("{\"biometrics\":[{\"specVersion\":\"0.9.5\",\"data\":\"\","
                    + "\"error\":{\"errorCode\":\"701\",\"errorInfo\":\"Capture timeout\"}}]}", CertificationLevel.L0);
            fail("expected DeviceException");
        } catch (DeviceException e) {
            assertTrue(e.getMessage().contains("701"));
        }
    }

    @Test
    public void sha256HexMatchesKnownValue() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SbiCaptureCodec.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    private static SbiCaptureCodec.CaptureContent content() {
        DeviceDescriptor device = DeviceDescriptor.builder().deviceId("builtin-face-camera").serialNumber("SERIAL-1")
                .make("MOSIP").model("Built-in camera").providerId("MOSIP.LIVENESS")
                .certification(CertificationLevel.L0).specVersion("0.9.5").purpose(DevicePurpose.REGISTRATION)
                .transport(DeviceTransport.BUILTIN_CAMERA).streamCapable(true).build();
        return new SbiCaptureCodec.CaptureContent(device, ISO, "tx-1", AT, "Registration", "Developer", 90f, "90",
                "session-1", "abcd", "nonce-0123456789ab");
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
