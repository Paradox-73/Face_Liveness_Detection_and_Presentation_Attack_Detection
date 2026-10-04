package io.mosip.registration.livenessmanager.device;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import io.mosip.liveness.core.device.CaptureRequest;
import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.DevicePurpose;
import io.mosip.liveness.core.device.DeviceStatus;
import io.mosip.liveness.core.device.DeviceTransport;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.liveness.core.device.StreamStats;
import io.mosip.liveness.core.image.PixelFormat;

/**
 * The vendor SBI face app adapter against scripted SBI 0.9.5 answers: discovery, the (absent or bridged) preview
 * stream, and the signed capture.
 */
@RunWith(RobolectricTestRunner.class)
public class SbiIntentDeviceTest {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final byte[] ISO = {0x46, 0x41, 0x43, 0x00, 9, 8, 7};

    @Test
    public void discoveryDescribesTheVendorFaceDevice() throws Exception {
        FakeBridge bridge = new FakeBridge(discover(device("Ready", "L1", "SN-42", "Face")));

        SbiIntentDevice device = SbiIntentDevice.discover(bridge, DevicePurpose.REGISTRATION);

        assertEquals(DeviceStatus.READY, device.status());
        assertEquals("SN-42", device.describe().serialNumber());
        assertEquals("ACME", device.describe().make());
        assertEquals("FaceCam 9", device.describe().model());
        assertEquals("acme-provider", device.describe().providerId());
        assertEquals(CertificationLevel.L1, device.describe().certification());
        assertEquals("0.9.5", device.describe().specVersion());
        assertEquals(DevicePurpose.REGISTRATION, device.describe().purpose());
        assertEquals(DeviceTransport.SBI_INTENT, device.describe().transport());
        assertFalse("SBI intents carry no preview frames", device.describe().isStreamCapable());
        assertEquals("com.acme.sbi", device.callbackId());
    }

    @Test
    public void discoverySkipsDevicesWithErrorsOrOtherModalitiesAndPrefersReady() throws Exception {
        JSONObject finger = device("Ready", "L0", "FP-1", "Finger");
        JSONObject broken = device("Ready", "L0", "BAD-1", "Face");
        broken.put("error", new JSONObject().put("errorCode", "100").put("errorInfo", "Device not connected"));
        JSONObject busy = device("Busy", "L0", "FACE-BUSY", "Face");
        JSONObject ready = device("Ready", "L0", "FACE-READY", "Face");
        FakeBridge bridge = new FakeBridge(discover(finger, broken, busy, ready));

        SbiIntentDevice device = SbiIntentDevice.discover(bridge, DevicePurpose.AUTH);

        assertEquals("FACE-READY", device.describe().serialNumber());
        assertEquals(DeviceStatus.READY, device.status());
    }

    @Test
    public void signedDigitalIdIsReadToo() throws Exception {
        JSONObject d = device("Ready", "L0", "unused", "Face");
        String payload = B64URL.encodeToString(digitalId("SN-JWS", "Face").toString().getBytes(StandardCharsets.UTF_8));
        d.put("digitalId", "eyJhbGciOiJSUzI1NiJ9." + payload + ".c2ln");
        SbiIntentDevice device = SbiIntentDevice.discover(new FakeBridge(discover(d)), DevicePurpose.REGISTRATION);
        assertEquals("SN-JWS", device.describe().serialNumber());
    }

    @Test
    public void noFaceDeviceIsNotFound() throws Exception {
        assertDiscoverFails(new FakeBridge("[]"), DeviceErrorCode.NOT_FOUND);
        assertDiscoverFails(new FakeBridge(""), DeviceErrorCode.NOT_FOUND);
        assertDiscoverFails(new FakeBridge("not json"), DeviceErrorCode.NOT_FOUND);
        assertDiscoverFails(new FakeBridge(discover(device("Ready", "L0", "FP-1", "Finger"))),
                DeviceErrorCode.NOT_FOUND);
    }

    @Test
    public void unregisteredFaceDeviceIsNotReady() throws Exception {
        assertDiscoverFails(new FakeBridge(discover(device("Not Registered", "L0", "SN-1", "Face"))),
                DeviceErrorCode.NOT_READY);
    }

    @Test
    public void bridgeFailureDuringDiscoveryIsPassedOn() {
        SbiIntentDevice.Bridge bridge = new FakeBridge(null) {
            @Override
            public String discover() throws DeviceException {
                throw new DeviceException(DeviceErrorCode.NOT_FOUND, "no app answered");
            }
        };
        assertDiscoverFails(bridge, DeviceErrorCode.NOT_FOUND);
    }

    @Test
    public void intentDeviceHasNoStream() throws Exception {
        SbiIntentDevice device = SbiIntentDevice.discover(
                new FakeBridge(discover(device("Ready", "L0", "SN-1", "Face"))), DevicePurpose.REGISTRATION);
        try {
            device.openStream(StreamRequest.defaults());
            fail("expected DeviceException");
        } catch (DeviceException e) {
            assertEquals(DeviceErrorCode.NO_STREAM, e.getErrorCode());
        }
    }

    @Test
    public void streamingBridgeDeliversItsFrames() throws Exception {
        FakeBridge bridge = new FakeBridge(discover(device("Ready", "L0", "SN-1", "Face")));
        bridge.frames = 3;
        SbiIntentDevice device = SbiIntentDevice.discover(bridge, DevicePurpose.REGISTRATION);
        assertTrue(device.describe().isStreamCapable());

        List<FaceFrame> received = new ArrayList<>();
        List<EndReason> ends = new ArrayList<>();
        try (FaceFrameSource source = device.openStream(StreamRequest.defaults())) {
            source.start(new FrameSink() {
                @Override
                public void onFrame(FaceFrame frame) {
                    received.add(frame);
                }

                @Override
                public void onError(DeviceError error) {
                    fail("unexpected error " + error);
                }

                @Override
                public void onEnd(EndReason reason) {
                    ends.add(reason);
                }
            });
        }

        assertEquals(3, received.size());
        assertEquals("SN-1", received.get(0).sourceId());
        assertEquals(2, received.get(2).sequence());
        assertEquals(1, ends.size());
    }

    @Test
    public void streamingBridgeWithoutSourceFailsToOpen() throws Exception {
        FakeBridge bridge = new FakeBridge(discover(device("Ready", "L0", "SN-1", "Face")));
        bridge.frames = -1;
        SbiIntentDevice device = SbiIntentDevice.discover(bridge, DevicePurpose.REGISTRATION);
        try {
            device.openStream(StreamRequest.defaults());
            fail("expected DeviceException");
        } catch (DeviceException e) {
            assertEquals(DeviceErrorCode.STREAM_OPEN_FAILED, e.getErrorCode());
        }
    }

    @Test
    public void captureParsesTheSignedResponseAndKeepsItForArc() throws Exception {
        FakeBridge bridge = new FakeBridge(discover(device("Ready", "L1", "SN-42", "Face")));
        bridge.captureResponse = captureResponse("txn-7", "SN-42", "0");
        SbiIntentDevice device = SbiIntentDevice.discover(bridge, DevicePurpose.REGISTRATION);
        assertNull(device.lastResponseJson());

        CaptureRequest request = CaptureRequest.builder().transactionId("txn-7").build();
        SignedFaceCapture capture = device.capture(request);

        assertSame(request, bridge.lastRequest);
        assertArrayEquals(ISO, capture.isoRecord());
        assertEquals("txn-7", capture.transactionId());
        assertEquals("SN-42", capture.deviceSerial());
        assertEquals(CertificationLevel.L1, capture.certification());
        assertEquals(bridge.captureResponse, device.lastResponseJson());
    }

    @Test
    public void captureErrorsAreDeviceErrors() throws Exception {
        FakeBridge bridge = new FakeBridge(discover(device("Ready", "L0", "SN-1", "Face")));
        SbiIntentDevice device = SbiIntentDevice.discover(bridge, DevicePurpose.REGISTRATION);
        CaptureRequest request = CaptureRequest.builder().transactionId("t").build();

        bridge.captureResponse = captureResponse("t", "SN-1", "701");
        assertCaptureFails(device, request, DeviceErrorCode.CAPTURE_FAILED);
        bridge.captureResponse = "";
        assertCaptureFails(device, request, DeviceErrorCode.CAPTURE_FAILED);
        bridge.captureResponse = "{\"biometrics\":[{\"data\":\"not-a-jws\"}]}";
        assertCaptureFails(device, request, DeviceErrorCode.CAPTURE_FAILED);
        assertNull("a refused capture is not kept", device.lastResponseJson());
    }

    // ------------------------------------------------------------------ fakes

    private static void assertDiscoverFails(SbiIntentDevice.Bridge bridge, DeviceErrorCode expected) {
        try {
            SbiIntentDevice.discover(bridge, DevicePurpose.REGISTRATION);
            fail("expected DeviceException");
        } catch (DeviceException e) {
            assertEquals(expected, e.getErrorCode());
        }
    }

    private static void assertCaptureFails(SbiIntentDevice device, CaptureRequest request,
                                           DeviceErrorCode expected) {
        try {
            device.capture(request);
            fail("expected DeviceException");
        } catch (DeviceException e) {
            assertEquals(expected, e.getErrorCode());
        }
    }

    private static String discover(JSONObject... devices) {
        JSONArray a = new JSONArray();
        for (JSONObject d : devices) {
            a.put(d);
        }
        return a.toString();
    }

    private static JSONObject device(String status, String level, String serial, String type) throws Exception {
        return new JSONObject()
                .put("deviceId", "dev-" + serial)
                .put("deviceStatus", status)
                .put("certification", level)
                .put("serviceVersion", "0.9.5")
                .put("deviceSubId", new JSONArray().put("0"))
                .put("callbackId", "com.acme.sbi")
                .put("digitalId", B64URL.encodeToString(digitalId(serial, type).toString()
                        .getBytes(StandardCharsets.UTF_8)))
                .put("deviceCode", "code-" + serial)
                .put("specVersion", new JSONArray().put("0.9.5"))
                .put("purpose", "Registration")
                .put("error", new JSONObject().put("errorCode", "0").put("errorInfo", "Success"));
    }

    private static JSONObject digitalId(String serial, String type) throws Exception {
        return new JSONObject().put("serialNo", serial).put("make", "ACME").put("model", "FaceCam 9")
                .put("type", type).put("deviceSubType", "Full face").put("deviceProvider", "ACME Devices")
                .put("deviceProviderId", "acme-provider").put("dateTime", "2026-10-04T10:00:00Z");
    }

    private static String captureResponse(String transactionId, String serial, String errorCode) throws Exception {
        String header = B64URL.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        JSONObject payload = new JSONObject().put("bioValue", B64URL.encodeToString(ISO))
                .put("transactionId", transactionId).put("deviceCode", serial)
                .put("timestamp", "2026-10-04T10:00:05Z").put("qualityScore", 80);
        String jws = header + "." + B64URL.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8))
                + ".c2lnbmF0dXJl";
        JSONObject bio = new JSONObject().put("specVersion", "0.9.5").put("data", jws)
                .put("error", new JSONObject().put("errorCode", errorCode).put("errorInfo", "x"));
        return new JSONObject().put("biometrics", new JSONArray().put(bio)).toString();
    }

    /** Scripted SBI app: a discover answer, an optional stream of N frames and a capture answer. */
    private static class FakeBridge implements SbiIntentDevice.Bridge {
        private final String discoverResponse;
        /** 0: no stream (SBI intents); N > 0: a stream of N frames; -1: claims a stream but opens none. */
        int frames;
        String captureResponse;
        CaptureRequest lastRequest;

        FakeBridge(String discoverResponse) {
            this.discoverResponse = discoverResponse;
        }

        @Override
        public String discover() throws DeviceException {
            return discoverResponse;
        }

        @Override
        public boolean canStream() {
            return frames != 0;
        }

        @Override
        public FaceFrameSource openStream(StreamRequest request) throws DeviceException {
            if (frames < 0) {
                return null;
            }
            int count = frames;
            return new FaceFrameSource() {
                private int delivered;

                @Override
                public void start(FrameSink sink) {
                    for (int i = 0; i < count; i++) {
                        sink.onFrame(FaceFrame.builder().data(new byte[2 * 2 * 3]).format(PixelFormat.RGB888)
                                .size(2, 2).monotonicNanos(i * 33_000_000L).sequence(i).sourceId("SN-1").build());
                        delivered++;
                    }
                    sink.onEnd(EndReason.END_OF_STREAM);
                }

                @Override
                public StreamStats stats() {
                    return new StreamStats(delivered, 0, 0, 30.0, 0.0);
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public String capture(CaptureRequest request) {
            lastRequest = request;
            return captureResponse;
        }
    }
}
