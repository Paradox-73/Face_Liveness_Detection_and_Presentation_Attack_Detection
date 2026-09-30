# Face capture device adapter API

- **Package:** `io.mosip.liveness.core.device` in `io.mosip.liveness:liveness-core`.
- **Purpose:** the common face capture device interface (PS04 "Common Face Capture Device Interface", "Device
  Adapter Pattern for supporting multiple biometric vendors"). The engine and the clients see only this interface.
- **Authentication:** none at the Java level. The capture returned must carry a device signature that the client's
  existing trust check accepts (desktop `MosipDeviceSpecificationHelper`, Android `Biometrics095Service`).

Design: [../design/device-interface.md](../design/device-interface.md).

## 1. Interfaces and value types

| Type | Methods / fields |
|---|---|
| `FaceCaptureDevice` | `DeviceDescriptor describe()`, `DeviceStatus status()`, `boolean isAvailable()`, `FaceFrameSource openStream(StreamRequest)`, `SignedFaceCapture capture(CaptureRequest)` |
| `FaceFrameSource` | `void start(FrameSink)`, `StreamStats stats()`, `void close()` |
| `FrameSink` | `onFrame(FaceFrame)`, `onError(DeviceError)`, `onEnd(EndReason)` |
| `DeviceDescriptor` | `deviceId`, `serialNumber`, `make`, `model`, `providerId`, `certification` (`L0`/`L1`), `specVersion`, `purpose` (`REGISTRATION`/`AUTH`), `transport` (`SBI_HTTP`/`SBI_INTENT`/`BUILTIN_CAMERA`), `isStreamCapable`, `isPadCapable` |
| `StreamRequest` | `streamTimeoutMs` (default 60,000), `firstFrameTimeoutMs` (3,000), `socketTimeoutMs` (2,000), `reconnectAttempts` (1), device sub-ID (`"0"` for face) |
| `FaceFrame` | `data`, `format` (`PixelFormat`), `width`, `height`, `rotationDegrees`, `isMirrored`, `monotonicNanos`, `sequence`, `sourceId`; `wipe()` |
| `CaptureRequest` | `transactionId`, `deviceSubId`, `timeoutMs` (default 10,000), optional `PadRequest pad`, optional `FaceFrame sourceFrame` (in-process devices sign this frame) |
| `PadRequest` | `PadRequest.of(required, PadMethod, challenge)`; sent to devices that advertise `padCapability` |
| `SignedFaceCapture` | `isoRecord` (ISO/IEC 19794-5 bytes), `signedData` (the device's JWS), `capturedAt`, `transactionId`, `deviceSerial`, `certification`, `specVersion`, `qualityScore`, `echoedChallenge`, `devicePad` (`DevicePadResult`: `PASS`/`FAIL`, method, score), `sessionFrameSha256` |
| `DeviceStatus` | `READY`, `BUSY`, `NOT_READY`, `NOT_REGISTERED`, `NOT_FOUND` |
| `EndReason` | `END_OF_STREAM`, `STREAM_TIMEOUT`, `CLOSED` |

Every `toString()` prints sizes and IDs only, never pixels, nonces or subject references.

## 2. Contract

1. `openStream` returns a source that has not started; `start(sink)` begins delivery on the source's own thread.
2. Frames have strictly increasing `sequence` and `monotonicNanos`. A regression fails the session as an integrity
   violation.
3. Exactly one terminal call per stream: `onEnd(...)` or `onError(...)`.
4. `close()` is safe from any thread and ends the stream with `onEnd(CLOSED)`.
5. A device that cannot stream throws `DeviceException(DeviceErrorCode.NO_STREAM)` from `openStream`; the engine
   then applies `no_stream_policy`.
6. `capture` blocks until the signed capture is available or `timeoutMs` passes (`CAPTURE_TIMEOUT`).
7. Frames must be upright after applying `rotationDegrees`, and flagged `mirrored` if they are mirror images.

## 3. Errors

`DeviceException(DeviceErrorCode, message[, cause])` and `DeviceError` values passed to `FrameSink.onError`:

| Code | Raise when | User message key |
|---|---|---|
| `NOT_FOUND` | no face device discovered | `liveness.error.device_unavailable` |
| `NOT_READY` | device busy or not ready | `liveness.error.device_unavailable` |
| `NO_STREAM` | device has no preview | `liveness.error.device_no_stream` |
| `STREAM_OPEN_FAILED` | stream request refused | `liveness.error.device_unavailable` |
| `FIRST_FRAME_TIMEOUT` | no frame within `firstFrameTimeoutMs` | `liveness.error.device_unavailable` |
| `STALLED` | no data within `socketTimeoutMs` | `liveness.error.frames_invalid` |
| `DISCONNECTED` | connection lost | `liveness.error.device_disconnected` |
| `INVALID_STREAM` | too many invalid parts | `liveness.error.frames_invalid` |
| `CAPTURE_FAILED`, `CAPTURE_TIMEOUT`, `SIGNATURE_INVALID` | signed capture problems | `liveness.error.device_unavailable` |
| `CAMERA_PERMISSION_DENIED` | Android permission refused | `liveness.error.camera_permission` |
| `CAMERA_IN_USE` | camera held by another app | `liveness.error.camera_unavailable` |
| `EMULATOR_BLOCKED` | `android.block_emulator=Y` on an emulator | `liveness.error.emulator` |

None of these use a liveness attempt; each is audited as LV12 with the code.

## 4. Implementations in this repository

| Class | Client | Notes |
|---|---|---|
| `io.mosip.registration.service.bio.liveness.SbiFaceCaptureDevice` | desktop | SBI 0.9.5 / 1.0 over loopback HTTP; `resolve(factory, modality, clock)` finds the discovered face device |
| `io.mosip.registration.service.bio.liveness.FaceStreamSession` | desktop | `FaceFrameSource` over `MjpegFrameReader` with reconnect |
| `io.mosip.registration.livenessmanager.device.BuiltInCameraL0Device` | Android | CameraX stream; signs `CaptureRequest.sourceFrame()` |
| `io.mosip.registration.livenessmanager.device.CameraXFrameSource`, `ReplayFrameSource` | Android | camera frames; frame packs in debug builds |
| `io.mosip.registration.livenessmanager.device.SbiIntentDevice` | Android | vendor SBI app via intents; no stream |

## 5. Use case: add a vendor camera SDK

A vendor ships a Java SDK for a USB face camera with its own PAD and signing. The adapter below is the whole
integration on the device side (`VendorCam`, `VendorFrame` and `VendorCapture` stand for the vendor's classes):

```java
public final class VendorCamDevice implements FaceCaptureDevice {
    private final VendorCam cam;

    public VendorCamDevice(VendorCam cam) { this.cam = cam; }

    @Override public DeviceDescriptor describe() {
        return DeviceDescriptor.builder()
                .deviceId(cam.id()).serialNumber(cam.serial()).make("Vendor").model(cam.model())
                .providerId("VENDOR.PROVIDER").certification(CertificationLevel.L1).specVersion("0.9.5")
                .purpose(DevicePurpose.REGISTRATION).transport(DeviceTransport.SBI_HTTP)
                .streamCapable(true).padCapable(cam.hasPad()).build();
    }

    @Override public DeviceStatus status() {
        return cam.isConnected() ? (cam.isBusy() ? DeviceStatus.BUSY : DeviceStatus.READY) : DeviceStatus.NOT_FOUND;
    }

    @Override public FaceFrameSource openStream(StreamRequest request) throws DeviceException {
        return new FaceFrameSource() {
            private final AtomicLong seq = new AtomicLong();
            @Override public void start(FrameSink sink) throws DeviceException {
                try {
                    cam.startPreview(request.streamTimeoutMs(), (VendorFrame f) -> sink.onFrame(FaceFrame.builder()
                            .data(f.jpegBytes()).format(PixelFormat.JPEG).size(f.width(), f.height())
                            .monotonicNanos(System.nanoTime()).sequence(seq.incrementAndGet())
                            .sourceId(cam.serial()).build()),
                        () -> sink.onEnd(EndReason.END_OF_STREAM),
                        e -> sink.onError(DeviceError.of(DeviceErrorCode.DISCONNECTED, "camera lost", e)));
                } catch (IOException e) {
                    throw new DeviceException(DeviceErrorCode.STREAM_OPEN_FAILED, "preview refused", e);
                }
            }
            @Override public StreamStats stats() { return StreamStats.EMPTY; }
            @Override public void close() { cam.stopPreview(); }
        };
    }

    @Override public SignedFaceCapture capture(CaptureRequest request) throws DeviceException {
        try {
            VendorCapture c = cam.capture(request.timeoutMs(), request.pad().map(PadRequest::challenge).orElse(null));
            return SignedFaceCapture.builder()
                    .isoRecord(c.iso19794()).signedData(c.jws()).capturedAt(c.time())
                    .transactionId(request.transactionId()).deviceSerial(cam.serial())
                    .certification(CertificationLevel.L1).specVersion("0.9.5")
                    .echoedChallenge(c.echoedChallenge())
                    .devicePad(c.padPassed() ? DevicePadResult.of(DevicePadDecision.PASS, PadMethod.PASSIVE, c.padScore())
                                             : DevicePadResult.of(DevicePadDecision.FAIL, PadMethod.PASSIVE, c.padScore()))
                    .build();
        } catch (IOException e) {
            throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "capture failed", e);
        }
    }
}
```

Plug it in on the desktop with `LivenessRequest.builder(workflow, subject).device(new VendorCamDevice(cam))` (see
[desktop-services.md](desktop-services.md)). The engine then streams, decides, binds the signed capture and
records `LIVENESS_DEVICE_LEVEL=L1` and, if the device passed its own PAD, `LIVENESS_DEVICE_PAD=PASS`. A device PAD
`FAIL` fails the session as a presentation attack.

For SBI-compliant devices no adapter is needed: `SbiFaceCaptureDevice` already speaks SBI 0.9.5 and 1.0
([sbi-used.md](sbi-used.md)).
