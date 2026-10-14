# 0006. Use the Android camera (CameraX) as a built-in SBI L0 device

- Status: Accepted
- Date: 2026-09-29

## Context

On Android, ARC reaches face devices through a vendor SBI app via intents. Such an app shows its own preview and
returns only the signed capture: the client never sees a frame stream, so it cannot run passive PAD or verify
challenges (PS04 mandatory tasks 1-4). PS04's recommended stack lists "Android Camera APIs (where applicable)".
MOSIP's SBI specification defines L0 devices as ones where "trust is provided at the software level".

## Decision

`livenessmanager` contains `BuiltInCameraL0Device implements FaceCaptureDevice`:

- **Stream:** CameraX 1.4.2 `ImageAnalysis` (front camera, `RGBA_8888`, 640x480, keep only the latest frame) through
  `CameraXFrameSource`, bound to the capture screen's lifecycle.
- **Capture:** no new picture. The device signs the frontal, neutral frame the session selected
  (`LivenessSession.captureFrame()`), so the stored face is by construction a frame the liveness check saw.
- **Format:** JPEG 2000 inside an ISO/IEC 19794-5:2011 record (MOSIP `biometrics-util` and the JP2ForAndroid library
  ARC already ships), wrapped in an SBI 0.9.5 capture response with exactly ARC's `CaptureDto` fields, RS256 with the
  device certificate chain in `x5c`; session ID, frame SHA-256 and pass-token nonce in the signed header.
- **Key:** Android Keystore (`AndroidKeystoreSigner`), not exportable. Debug builds import a PKCS#12 issued by a DEVICE CA that ARC
  syncs (MockSync's DEVICE CA in the demo, `scripts/android-liveness-device-cert.sh`). Release builds can provision in the app: the
  app writes a certificate request for the Keystore key and imports the issued chain (`scripts/android-liveness-device-cert.sh --csr`),
  checked before use; run on the headless emulator only.
- **Trust:** the capture goes through ARC's unchanged `Biometrics095Service.handleRCaptureResponse` checks
  (signature, `DEVICE` trust, time, transaction ID).
- CameraX 1.4.2 is the newest line that builds with ARC's compileSdk 34 and AGP 8.3.2 (1.5 needs compileSdk 35).
- `SbiIntentDevice` is wired in (`mosip.registration.liveness.android.device=SBI`, routed by `FaceDeviceSelection`) for vendor apps, with
  `no_stream_policy` deciding what happens without a stream; it falls back to the built-in camera when no app answers `io.sbi.device`.

## Consequences

- Liveness on Android needs only the tablet's own camera and no vendor app (no physical tablet was tested yet); the Android flows ran end to end on the
  emulator (`docs/test-report/android-e2e.md`).
- L0 limits apply: a rooted device controlling the app process could feed frames or use the key.
  `android.block_emulator=Y` refuses emulators; root detection is not implemented.
- An unprovisioned tablet gets a self-signed key; liveness runs but ARC's DEVICE trust check refuses the capture,
  and `LivenessService.isBuiltInDeviceProvisioned()` lets screens say so.
- ARC's `isDeviceValid` (L1 only) is not on this path; accepting the `mosip.registration.mds.allowed_certifications` list there is still needed for L0 vendor SBI apps.
- ONNX Runtime adds about 30 MB per CPU type; debug builds carry `arm64-v8a` and `x86_64` only. A release APK for `arm64-v8a` with the engine and models is 116.7 MB (release build of 2026-10-14; 120.9 MB in the 2026-09-30 build) (`docs/test-report/android-e2e.md` section 6).

## Alternatives considered

- **Vendor SBI app only:** no frames, so no passive PAD or challenges on Android.
- **Camera2 directly:** more code for rotation, lifecycle and buffer handling than CameraX.
- **A separate mock SBI app for Android:** another app to build, sign and install; the replay frame source in debug
  builds covers testing.
