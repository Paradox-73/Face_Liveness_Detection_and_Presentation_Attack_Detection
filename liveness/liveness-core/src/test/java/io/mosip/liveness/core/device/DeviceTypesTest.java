package io.mosip.liveness.core.device;

import io.mosip.liveness.core.image.PixelFormat;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceTypesTest {

    @Test
    void jpegFrameMayHaveUnknownSize() {
        FaceFrame f = FaceFrame.builder().data(new byte[]{(byte) 0xFF, (byte) 0xD8}).format(PixelFormat.JPEG)
                .monotonicNanos(5).sequence(1).sourceId("2345678901").build();
        assertEquals(0, f.width());
        assertEquals(PixelFormat.JPEG, f.format());
        assertEquals("FaceFrame[seq=1, JPEG, 0x0, rot=0, mirrored=false, bytes=2, source=2345678901]", f.toString());
    }

    @Test
    void rawFrameMustMatchItsSize() {
        FaceFrame.Builder ok = FaceFrame.builder().data(new byte[16]).format(PixelFormat.RGBA8888).size(2, 2)
                .rotationDegrees(270).mirrored(true).sourceId("camera-front");
        FaceFrame f = ok.build();
        assertEquals(270, f.rotationDegrees());
        assertTrue(f.isMirrored());
        f.wipe();
        assertThrows(IllegalArgumentException.class, () -> FaceFrame.builder().data(new byte[15])
                .format(PixelFormat.RGBA8888).size(2, 2).sourceId("c").build());
        assertThrows(IllegalArgumentException.class, () -> FaceFrame.builder().data(new byte[16])
                .format(PixelFormat.RGBA8888).size(2, 2).rotationDegrees(45).sourceId("c").build());
        assertThrows(IllegalArgumentException.class, () -> FaceFrame.builder().data(new byte[4])
                .format(PixelFormat.ARGB8888_INT).size(1, 1).sourceId("c").build());
        assertThrows(NullPointerException.class, () -> FaceFrame.builder().data(new byte[1]).format(PixelFormat.JPEG).build());
    }

    @Test
    void descriptorRequiresIdentityAndTrustLevel() {
        DeviceDescriptor d = DeviceDescriptor.builder().deviceId("3").serialNumber("2345678901").make("MOSIP")
                .model("FACE01").certification(CertificationLevel.L0).specVersion("0.9.5")
                .transport(DeviceTransport.SBI_HTTP).build();
        assertTrue(d.isStreamCapable());
        assertFalse(d.isPadCapable());
        assertEquals(DevicePurpose.REGISTRATION, d.purpose());
        assertEquals(d, DeviceDescriptor.builder().deviceId("3").serialNumber("2345678901").make("MOSIP").model("FACE01")
                .certification(CertificationLevel.L0).specVersion("0.9.5").transport(DeviceTransport.SBI_HTTP).build());
        assertThrows(NullPointerException.class, () -> DeviceDescriptor.builder().serialNumber("1").specVersion("0.9.5")
                .transport(DeviceTransport.REPLAY).build());
        assertThrows(IllegalArgumentException.class, () -> DeviceDescriptor.builder().serialNumber("").specVersion("0.9.5")
                .certification(CertificationLevel.L1).transport(DeviceTransport.REPLAY).build());
    }

    @Test
    void sbiWireValuesParse() {
        assertEquals(CertificationLevel.L1, CertificationLevel.fromCode(" l1 ").orElse(null));
        assertFalse(CertificationLevel.fromCode("L2").isPresent());
        assertEquals(DevicePurpose.AUTH, DevicePurpose.fromCode("auth").orElse(null));
        assertEquals(DeviceStatus.NOT_READY, DeviceStatus.fromCode("Not Ready").orElse(null));
        assertFalse(DeviceStatus.fromCode("").isPresent());
        assertEquals(PadMethod.PASSIVE, PadMethod.fromWireValue("passive").orElse(null));
        assertEquals("any", PadMethod.ANY.wireValue());
    }

    @Test
    void requestsHaveDesignDefaults() {
        StreamRequest s = StreamRequest.defaults();
        assertEquals("0", s.deviceSubId());
        assertEquals(60_000L, s.streamTimeoutMs());
        assertEquals(3_000L, s.firstFrameTimeoutMs());
        assertEquals(2_000, s.socketTimeoutMs());
        assertEquals(1, s.reconnectAttempts());
        assertThrows(IllegalArgumentException.class, () -> StreamRequest.builder().socketTimeoutMs(0).build());

        PadRequest pad = PadRequest.of(true, PadMethod.PASSIVE, "nonce-0123456789");
        CaptureRequest c = CaptureRequest.builder().transactionId("tx1").pad(pad).build();
        assertEquals(pad, c.pad().orElse(null));
        assertFalse(c.sourceFrame().isPresent());
        assertFalse(pad.toString().contains("nonce-0123456789"));
        assertThrows(IllegalArgumentException.class, () -> CaptureRequest.builder().transactionId("").build());
    }

    @Test
    void signedCaptureCarriesDevicePad() {
        SignedFaceCapture cap = SignedFaceCapture.builder().isoRecord(new byte[]{1, 2, 3}).signedData("a.b.c")
                .capturedAt(Instant.EPOCH).transactionId("tx1").deviceSerial("2345678901")
                .certification(CertificationLevel.L0).specVersion("0.9.5").qualityScore(95.0)
                .devicePad(DevicePadResult.of(DevicePadDecision.FAIL, PadMethod.PASSIVE, 12)).build();
        assertTrue(cap.devicePad().get().isFail());
        assertEquals(95.0, cap.qualityScore().getAsDouble());
        assertFalse(cap.toString().contains("a.b.c"));
        assertThrows(IllegalArgumentException.class, () -> DevicePadResult.of(DevicePadDecision.PASS, null, 101));
    }

    @Test
    void deviceExceptionCarriesItsError() {
        DeviceException e = new DeviceException(DeviceErrorCode.FIRST_FRAME_TIMEOUT, "no frame in 3000 ms");
        assertEquals(DeviceErrorCode.FIRST_FRAME_TIMEOUT, e.getErrorCode());
        assertEquals("no frame in 3000 ms", e.getMessage());
        assertFalse(e.getError().cause().isPresent());
    }
}
