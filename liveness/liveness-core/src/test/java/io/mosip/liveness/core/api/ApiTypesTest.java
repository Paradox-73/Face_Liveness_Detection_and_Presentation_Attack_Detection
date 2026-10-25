/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DevicePadDecision;
import io.mosip.liveness.core.device.DevicePadResult;
import io.mosip.liveness.core.device.DeviceTransport;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTypesTest {

    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");
    private static final String SHA_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SHA_B = "FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210";

    private static DeviceDescriptor device() {
        return DeviceDescriptor.builder().serialNumber("2345678901").certification(CertificationLevel.L1)
                .specVersion("0.9.5").transport(DeviceTransport.SBI_HTTP).build();
    }

    private static EngineInfo engine() {
        return EngineInfo.builder("mosip-onnx-default", "1.0.0").model(ModelInfo.of("minifasnet_v2", "1.0.0", SHA_A))
                .model(ModelInfo.of("sface", "1.0.0", SHA_B))
                .supportedChallenges(Arrays.asList(ChallengeType.BLINK, ChallengeType.TURN_LEFT)).build();
    }

    private static LivenessPassToken token() {
        return LivenessPassToken.builder().sessionId("s1").workflow(Workflow.PACKET_AUTH).subjectRef("officer1")
                .deviceSerial("2345678901").nonce("Qm9ndXNOb25jZVZhbHVlMDE").issuedAt(T0)
                .expiresAt(T0.plusMillis(15_000)).build();
    }

    @Test
    void passTokenExpiresAndHidesSecrets() {
        LivenessPassToken t = token();
        assertFalse(t.isExpiredAt(T0.plusMillis(15_000)));
        assertTrue(t.isExpiredAt(T0.plusMillis(15_001)));
        assertFalse(t.toString().contains("officer1"));
        assertFalse(t.toString().contains("Qm9ndXNOb25jZVZhbHVlMDE"));
        assertEquals(t, token());
        assertTrue(t.nonceEquals("Qm9ndXNOb25jZVZhbHVlMDE"));
        assertFalse(t.nonceEquals("Qm9ndXNOb25jZVZhbHVlMDI"));
        assertFalse(t.nonceEquals(null));
        assertThrows(IllegalArgumentException.class, () -> LivenessPassToken.builder().sessionId("s").workflow(Workflow.EOD_AUTH)
                .subjectRef("u").deviceSerial("d").nonce("short").issuedAt(T0).expiresAt(T0.plusMillis(1)).build());
        assertThrows(IllegalArgumentException.class, () -> LivenessPassToken.builder().sessionId("s").workflow(Workflow.EOD_AUTH)
                .subjectRef("u").deviceSerial("d").nonce("0123456789abcdef").issuedAt(T0).expiresAt(T0).build());
    }

    @Test
    void engineAndModelTags() {
        EngineInfo e = engine();
        assertEquals("mosip-onnx-default@1.0.0", e.tag());
        assertEquals("fedcba98", e.models().get(1).sha256Prefix());
        assertThrows(IllegalArgumentException.class, () -> ModelInfo.of("m", "1", "xyz"));
        assertThrows(IllegalArgumentException.class, () -> EngineInfo.builder("a@b", "1"));
    }

    @Test
    void resultDecisionFollowsTheFailureReason() {
        LivenessResult fail = LivenessResult.builder("s2", Workflow.REGISTRATION, engine(), device()).fail(FailureReason.INTEGRITY)
                .method(LivenessMethod.PASSIVE).reason(ReasonCode.FROZEN).period(T0, T0.plusMillis(900))
                .timings(900, 0, 900).build();
        assertEquals(Decision.FAIL_PAD, fail.decision());
        assertFalse(fail.isPass());
        assertEquals(FailureReason.INTEGRITY, fail.failureReason().get());
        assertThrows(IllegalStateException.class, () -> LivenessResult.builder("s", Workflow.REGISTRATION, engine(), device())
                .method(LivenessMethod.PASSIVE).period(T0, T0).build());
        assertThrows(IllegalArgumentException.class, () -> LivenessResult.builder("s", Workflow.REGISTRATION, engine(), device())
                .pass().method(LivenessMethod.PASSIVE).period(T0, T0).timings(10, 10, 5).build());
    }

    @Test
    void packetEvidenceOfAPassingResult() {
        LivenessResult pass = LivenessResult.builder("s1", Workflow.REGISTRATION, engine(), device()).pass()
                .method(LivenessMethod.PASSIVE_ACTIVE).passiveScore(64).bindingSimilarity(83)
                .challenge(ChallengeRecord.passed(ChallengeType.BLINK, 0, 420, 310, 1200))
                .challenge(ChallengeRecord.passed(ChallengeType.TURN_LEFT, 1, 600, 320, 1500))
                .devicePad(DevicePadResult.of(DevicePadDecision.PASS, null, null))
                .period(T0, T0.plusMillis(6000)).timings(2500, 3000, 6000).build();
        Map<String, String> m = PacketEvidenceKeys.of(pass);
        assertEquals("PASS", m.get("LIVENESS_DECISION"));
        assertEquals("PASSIVE_ACTIVE", m.get("LIVENESS_METHOD"));
        assertEquals("64", m.get("LIVENESS_SCORE"));
        assertEquals("BLINK:OK,TURN_LEFT:OK", m.get("LIVENESS_CHALLENGES"));
        assertEquals("mosip-onnx-default@1.0.0", m.get("LIVENESS_ENGINE"));
        assertEquals("01234567,fedcba98", m.get("LIVENESS_MODEL_SHA"));
        assertEquals("s1", m.get("LIVENESS_SESSION"));
        assertEquals("80-89", m.get("LIVENESS_BINDING"));
        assertEquals("L1", m.get("LIVENESS_DEVICE_LEVEL"));
        assertEquals("PASS", m.get("LIVENESS_DEVICE_PAD"));
        assertEquals(10, m.size());
    }

    @Test
    void challengeRecordEvidence() {
        assertEquals("SMILE:TIMEOUT", ChallengeRecord.failed(ChallengeType.SMILE, 0, ChallengeOutcome.TIMEOUT, 5000).evidence());
        assertThrows(IllegalArgumentException.class,
                () -> ChallengeRecord.failed(ChallengeType.SMILE, 0, ChallengeOutcome.PASSED, 5000));
        assertEquals(420L, ChallengeRecord.passed(ChallengeType.BLINK, 0, 420, 310, 1200).onsetMs().getAsLong());
    }

    @Test
    void bindingVerdictRules() {
        BindingVerdict ok = BindingVerdict.passed().similarity(88).gapMs(1200).challengeEcho(ChallengeEchoStatus.MATCHED).build();
        assertTrue(ok.isPassed());
        assertTrue(ok.reasonCodes().isEmpty());
        BindingVerdict bad = BindingVerdict.failed(FailureReason.BINDING, ReasonCode.SIMILARITY_LOW).similarity(31).build();
        assertEquals(FailureReason.BINDING, bad.failureReason().get());
        assertThrows(IllegalArgumentException.class, () -> BindingVerdict.failed(FailureReason.ACTIVE, ReasonCode.WRONG_ACTION));
        assertThrows(IllegalArgumentException.class, () -> BindingVerdict.failed(FailureReason.PAD));
        assertThrows(IllegalArgumentException.class,
                () -> BindingVerdict.passed().challengeEcho(ChallengeEchoStatus.MISSING).build());
    }

    @Test
    void bindingRequestFromASignedCapture() {
        SignedFaceCapture cap = SignedFaceCapture.builder().isoRecord(new byte[]{1}).signedData("h.p.s").capturedAt(T0)
                .transactionId("tx").deviceSerial("2345678901").certification(CertificationLevel.L0).specVersion("0.9.5")
                .echoedChallenge("Qm9ndXNOb25jZVZhbHVlMDE").build();
        ImageBuffer img = ImageBuffer.ofBytes(2, 2, PixelFormat.RGB888, new byte[12]);
        BindingRequest r = BindingRequest.from(cap, img, token());
        assertEquals("2345678901", r.deviceSerial());
        assertEquals("Qm9ndXNOb25jZVZhbHVlMDE", r.echoedChallenge().get());
        assertFalse(r.devicePad().isPresent());
        assertTrue(r.token().isPresent());
        assertFalse(BindingRequest.from(cap, img, null).token().isPresent());
    }

    @Test
    void sessionRequestChecksTheAttempt() {
        LivenessPolicy policy = LivenessPolicy.defaults(Workflow.LOGIN_AUTH);
        SessionRequest r = SessionRequest.builder(policy, "officer1", device()).attempt(3).build();
        assertEquals(Workflow.LOGIN_AUTH, r.workflow());
        assertFalse(r.toString().contains("officer1"));
        assertThrows(IllegalArgumentException.class, () -> SessionRequest.builder(policy, "officer1", device()).attempt(4).build());
        assertThrows(IllegalArgumentException.class, () -> SessionRequest.builder(policy, " ", device()));
    }
}
