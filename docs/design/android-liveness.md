# Android liveness module (Registration Client, native layer)

This document describes `livenessmanager`, the native Android library module that brings face liveness and
presentation attack detection (PAD) to the Android Registration Client (ARC, `clients/android`). It covers the
module's parts, how a capture runs, where the configuration and audits go, the built-in camera's trust model,
the build, the tests and the known gaps.

The engine is the same Java code the desktop client runs: `io.mosip.liveness:liveness-core` (sessions, policy,
challenges, binding, audits, UI states) and `liveness-onnx` (YuNet face detector, Face Mesh landmarks,
MiniFASNet PAD ensemble, SFace embedder, signed model registry). This module adds only what is specific to
Android: camera, image decoding, the capture screen, ARC's configuration and audit stores, and the device key.

## 1. Components

Package `io.mosip.registration.livenessmanager` (module folder `clients/android/android/livenessmanager`).

| Class | Role |
|---|---|
| `LivenessService` / `DefaultLivenessService` | The facade for the rest of ARC (resident capture, operator onboarding and packet authentication, supervisor approval): `policy(workflow)`, `isEnforced(workflow)`, `newCaptureIntent(context, request)`, `parseResult(resultCode, data)`, `preload()`, `isBuiltInDeviceProvisioned()`. Request code `LivenessService.REQUEST_CODE = 6` (ARC uses 1-5 for its SBI intents). |
| `di.LivenessModule` | Dagger module added to ARC's `AppComponent`. Provides the config source, the audit sink, the process-wide `LivenessRuntime` and the `LivenessService`; starts loading the models in the background when liveness is on. |
| `LivenessRuntime` | Process-wide pieces shared by the service and the screen: config, audit and diagnostic sinks, image decoder, engine loader, device serial and device key. A debug launch of the screen from adb before ARC started uses a standalone runtime (shipped defaults, audits to Logcat). |
| `LivenessCaptureRequest` / `LivenessCaptureResult` | What to verify (workflow, subject reference, language; debug builds also frame source and config overrides) and how it ended (decision, failure reason, reason codes, message key, attempts, retry / override / lock rules, pass-token nonce, signed capture, packet evidence). Both travel as `Intent` extras. |
| `session.LivenessRunner` | One liveness transaction: the first session and its retries. It builds the core `LivenessSessionDriver` (shared with the desktop client), which owns the single `liveness-worker` thread that makes every session call, moves frames from a latest-frame hand-off into the session, calls `tick()` at least every 100 ms, counts attempts in one `LivenessTransaction` and writes `REG-EVT-LV11`. The runner keeps the Android parts: `FrameTimer` as the driver's frame-sink decorator, the capture, signing, decoding and binding of the built-in device in the driver's capture handler (a copy of the capture frame, wiped afterwards), and the mapping of each `AttemptOutcome` to a `LivenessCaptureResult`. |
| `ui.LivenessCaptureActivity` | The native capture screen (section 3). Renders only the engine's `LivenessUiState`, so its behaviour matches the desktop screen. |
| `ui.MessageResolver` | Message key to text: the module's string resource in the screen's language, replaced by a synced `mosip.registration.liveness.guidance.<key>.<lang>` text when one is configured (same rule as desktop). |
| `ui.OvalGuideView` | Oval face guide with a progress arc; colour follows the state's severity. |
| `device.CameraXFrameSource` | Front camera through CameraX `ImageAnalysis` (`RGBA_8888`, `STRATEGY_KEEP_ONLY_LATEST`), bound to the screen's lifecycle. |
| `device.ReplayFrameSource` | Plays a folder of JPEG/PNG frames as a camera stream (APK assets or the app's external files folder). Debug builds only. |
| `device.BuiltInCameraL0Device` | The device's own camera as an SBI L0 face device inside the client (`FaceCaptureDevice`). |
| `device.FaceIsoRecords` | ISO/IEC 19794-5:2011 face record with a JPEG 2000 image, written with MOSIP `biometrics-util` and the JP2ForAndroid (OpenJPEG) library ARC already ships. |
| `device.SbiCaptureCodec` | Writes and reads the SBI 0.9.5 `rCapture` response JSON (fields of ARC's `CaptureDto`, RS256 JWS with `x5c`). |
| `device.DeviceSigner` / `AndroidKeystoreSigner` | Device key in the Android Keystore (section 5). |
| `device.FaceDeviceSelection` | Reads `mosip.registration.liveness.android.device` (`BUILTIN` default, `SBI`; blank or unknown means `BUILTIN`, with a Logcat warning) and routes each face capture (section 4). |
| `device.SbiIntentDevice` | Adapter for a vendor SBI face app reached through Android intents (ARC's SBI 0.9.5 path), wired through `FaceDeviceSelection` and `BiometricsDetailsApi`. Its `Bridge` has `discover`, `canStream`, `openStream` and `capture`; `discover(bridge, purpose)` parses the SBI 0.9.5 discover JSON (digital ID as base64 or JWS, skips errors and non-face entries, prefers `Ready`; `Not Registered` gives `NOT_READY`, none gives `NOT_FOUND`). The intents carry no preview frames, so `no_stream_policy` decides: `BLOCK` (default) fails the Flutter call and audits `R_CAPTURE_FAILED`, `CAPTURE_ONLY` stores the app's capture after ARC's checks with `LIVENESS_METHOD=CAPTURE_ONLY`. Only a Robolectric run exists; no vendor SBI app has been run on a device or emulator. |
| `device.DeviceProvisioning`, `DeviceProvisioningActivity`, `DeviceProvisioningInitializer` | In-app device key provisioning that works on release builds (section 5). |
| `image.AndroidImageDecoder` | `ImageDecoder` for Android: RGBA camera frames, JPEG/PNG through `BitmapFactory`, JPEG 2000 through JP2ForAndroid, to upright RGB888. |
| `image.PixelOrientation` | Rotation, optional mirror flip and channel order in one pass; pure Java, tested on the JVM. |
| `engine.EngineLoader` | Loads the engine once per process on a background thread (`preload()`, `await(ms)`); a model integrity failure is kept until restart (fail closed, `REG-EVT-LV14`). `fromConfig(...)` picks the engine by `mosip.registration.liveness.engine`: blank or `mosip-onnx-default` gives the built-in Android ONNX engine (assets, accelerator, warm-up), any other ID is looked up with `ServiceLoader<LivenessEngineProvider>`; no provider with that ID gives `CONFIG_INVALID` (fails closed, retried). |
| `engine.AssetModelSource` | `ModelSource` over `assets/liveness/models/` for the engine's `ModelRegistry`. |
| `engine.ModelBundleImporter` / `engine.ModelBundleStore` | Offline import of a signed model bundle by a supervisor, and the store of imported bundles (section 14). |
| `config.GlobalParamConfigSource` | `ConfigSource` over ARC's `GlobalParamRepository` (section 4). |
| `config.OverlayConfigSource` | Debug-only overrides passed to the screen from adb. |
| `audit.ArcAuditSink` | Writes `REG-EVT-LV01..17` through ARC's `AuditManagerService.auditWithArguments` on one background thread. |
| `audit.LogcatSinks` | Diagnostic lines and standalone audits under the Logcat tag `Liveness`. |
| `SupervisorOverride` | A granted override of a resident capture: supervisor, failed evidence, `OVERRIDE` evidence, audit `REG-EVT-LV16` (section 13.1). |
| `metrics.MetricsReport` / `metrics.MetricsLog` | Anonymised metrics line and summary; the rotating `files/liveness/metrics.log` (section 13.2). |
| `audit.DiagnosticModeTracker` | `REG-EVT-LV17` when diagnostic mode changes (section 13.3). |
| `engine.Accelerator` / `engine.AcceleratorCustomizer` | Execution provider from the configuration, with CPU fallback (section 13.5). |

Changes outside the module (surgical, upstream style kept):

- `android/settings.gradle`: `include ':livenessmanager'`.
- `android/build.gradle`: versions (`livenessEngineVersion`, `onnxruntimeAndroidVersion = 1.20.0`,
  `cameraxVersion = 1.4.2`, `androidxTestRunnerVersion`), `mavenLocal()` limited to the group `io.mosip.liveness`,
  and the module added to the list of own modules that keep lint.
- `android/app/build.gradle`: `implementation project(':livenessmanager')`; debug build type
  `ndk { abiFilters 'arm64-v8a', 'x86_64' }`.
- `AppComponent` and `MainActivity`: `LivenessModule` added to the component, `LivenessService` injected (which
  installs the runtime and starts the model preload).
- `clientmanager` `AuditEvent`: `LIVENESS_*` entries `REG-EVT-LV01..17` with description `%s` (ARC drops unknown
  event IDs, so enum entries are required); `Components.LIVENESS` = `REG-MOD-111`.

## 2. Flow of one capture

1. The caller checks `liveness.isEnforced(workflow)`. If `false`, it captures as before.
2. It starts the screen: `startActivityForResult(liveness.newCaptureIntent(activity, request), 6)`.
3. The screen resolves the policy through `LivenessRuntime.resolve(workflow)`, which writes `REG-EVT-LV15` when
   synced liveness keys changed since the last resolution or a value was corrected to its floor or range.
4. If liveness is off for the workflow, the screen closes at once with `CANCELLED`. If
   `mosip.registration.liveness.android.block_emulator=Y` and the app runs on an emulator, it shows the emulator
   error and writes `REG-EVT-LV12` (`EMULATOR_BLOCKED`).
5. Camera permission is asked for when missing; a refusal shows the permission error with Retry and
   "Open settings".
6. The runner waits for the engine (at most 30 s; normally it was preloaded when ARC started: 10.7 s on the
   x86_64 emulator, measured) and opens a session. The frame source starts on its own thread and puts frames into
   a `FrameHandOff.latestOnly()`. The worker delivers the newest frame to the session and ticks it.
7. The session runs the passive PAD ensemble, then the random challenges, and emits UI states and audits. The
   screen renders the states on the main thread; the audit sink stores the events.
8. At `CAPTURE_READY` the worker takes the session's selected frame (frontal, neutral, sharp), and the built-in
   device encodes and signs exactly that frame (section 5). The worker decodes the image back out of the signed
   ISO record and calls `session.bind(...)`: face similarity, time gap and token checks. Only a bound capture is a
   pass.
9. The screen returns `RESULT_OK` with the result for a pass (after showing the success state for 0.9 s), or
   `RESULT_CANCELED` with the failure details. On a failure that allows a retry the screen offers Retry; the
   caller decides what to do after the last attempt (`on_max_failure`: `BLOCK`, `SUPERVISOR_OVERRIDE` for resident
   workflows, `BLOCK_AND_LOCK` for authentication workflows).

A pass result carries: `rCaptureResponse()` (the SBI JSON, for ARC's unchanged
`Biometrics095Service.handleRCaptureResponse` checks), `faceIso()`, `transactionId()`, `tokenNonce()`,
`evidence()` (the `LIVENESS_*` packet evidence entries: decision, method, score buckets, challenges, engine and
model versions, session ID) and `isDeviceProvisioned()`. The ISO record and the JSON stay well below the
1 MB Binder limit of an `Intent` (a JPEG 2000 face at about 2 bits per pixel).

## 3. Capture screen

- Full-screen `PreviewView` (mirrored like a selfie; analysis frames are not mirrored, so "turn left" means the
  subject's left), oval guide with progress arc, title (supervisor title for approval workflows), prompt with
  icon, guidance lines, challenge step "Step n of m", progress bar, attempt dots, Retry, Cancel and
  "Open settings" (permission error only).
- The prompt sits in a light status banner whose border, icon circle and text colours are the desktop panel's (`liveness-panel.css`: neutral, success, attention, failure), checked against the CSS file and for WCAG contrast in tests; the oval strokes use the desktop values and the Retry button is `#005BAA` with white text. The screen stays dark and full-screen, so the layout differs from the desktop dialog while texts and colours match. The positioning step and "Look directly at the camera and hold still" show a face-outline icon (`ic_liveness_face`), not the smiley. `liveness.retry.max_resident` is a plurals resource in all six languages. No screenshot of the new colours exists (no emulator run since the change).
- A badge shows "Offline" when there is no network (liveness itself never needs the network), or
  "Replay (debug build)" for the replay source.
- Text comes from the engine's message keys (`io.mosip.liveness.core.ui.MessageKeys`) through Android string
  resources in `res/values{,-fr,-ar,-hi,-kn,-ta}/strings.xml`; ARC language codes (`eng`, `fra`, `ara`, `hin`,
  `kan`, `tam`) select the locale, and Arabic switches the layout to right-to-left.
- Accessibility: every state has text, an icon and a colour, never colour alone; the prompt is a polite live
  region and each new prompt is announced for TalkBack; icons and attempt dots have content descriptions; text
  sizes are in `sp`, so the system font size applies; buttons are at least 48 dp high.
- The screen keeps itself on while open and cancels the session when it stops (the camera stops with it).
- Orientation is `locked`: the screen keeps the orientation the device had when it opened (no letterboxing on a
  landscape tablet), so the camera's target rotation stays valid for the session. The oval guide is sized to the
  area above the prompt panel, so portrait phones and landscape tablets both show the whole oval.
- The language comes from the request, not from the device: the layout is inflated with a context in that
  locale. The module manifest sets `android:supportsRtl="true"` (merged into ARC, which declared no value);
  without it Android lays out every native view left-to-right. ARC's Flutter screens are not affected.

## 4. Configuration

`GlobalParamConfigSource` reads the same `mosip.registration.liveness.*` keys as the desktop client from ARC's
`GlobalParamRepository`. Values come with the normal config sync and stay in the local database, so the last
synced values apply offline. Only keys that `LivenessConfigKeys.isLocalOverrideAllowed` marks as tunable read
the merged value (synced, then the operator's local setting); the master switch, `policy.*`, the engine, the
minimum model version and `on_max_failure` always use the synced value, so a local setting can never switch
liveness off. Missing or malformed values never throw: the core `PolicyResolver` applies defaults, floors and
ranges and reports corrections (audit `REG-EVT-LV15`).

Android-only keys: `mosip.registration.liveness.android.device` (`BUILTIN`, default, or `SBI`; a blank or unknown
value means `BUILTIN` for face capture routing and for `LivenessService.isBuiltInCameraFaceDevice`; with liveness off (`enable=N` or policy `OFF`) the built-in screen takes a plain photo with no evidence and no attempt counted, and an installed SBI app takes ARC's upstream capture path),
`mosip.registration.liveness.android.block_emulator` (default `N`) and
`mosip.registration.android.offline_approval.enable`.

Also read on Android: `mosip.registration.liveness.engine.execution_provider` (`CPU`, `XNNPACK`, `NNAPI`; section
13.5), `metrics.enable` (metrics log, section 13.2) and `diagnostic.enable` (diagnostic panel and
`REG-EVT-LV17`, section 13.3).

Debug builds accept `config.<key>` extras on the screen's intent (see `scripts/android-liveness-demo.sh`);
release builds ignore them.

## 5. Built-in camera as an SBI L0 device, and its trust model

SBI L0 means "trust is provided at the software level". The built-in device:

- streams from the session's frame source and captures no new picture: the signed face is the frame the session
  selected, so the stored face is by construction one the liveness check saw;
- encodes it upright as JPEG 2000 inside an ISO/IEC 19794-5 record (what ARC stores for FACE from any SBI
  device);
- signs an SBI 0.9.5 capture response with RS256 and the device certificate chain in `x5c`. The payload has
  exactly the fields of ARC's `CaptureDto` (ARC's Jackson mapper rejects unknown fields); the link to the
  liveness session (session ID, SHA-256 of the stored frame, pass-token nonce) is in the signed JWS protected
  header, which ARC keeps with the signature in the packet.

The private key is created in, or imported into, the Android Keystore (alias `mosip-liveness-face-device`) and
cannot be exported by the app.

- **Provisioned**: a PKCS#12 file with a key and a certificate issued by a DEVICE certificate authority that ARC
  syncs is placed in the app's `files/liveness/device.p12` (password in `device.p12.pass`); the next capture
  imports it into the Keystore and deletes both files. For demos against MockSync,
  `scripts/android-liveness-device-cert.sh` issues such a certificate from MockSync's DEVICE CA and copies it in
  with `adb shell run-as` (debuggable builds only). In production the certificate comes from the device provider
  through MOSIP partner management.
- **Provisioned in the app (release builds too)**: the app creates the device key in the Keystore and writes a
  PKCS#10 request (`device.csr`) and `status.txt` to `Android/data/io.mosip.registration_client/files/liveness/`, which
  `adb` can read and write on release builds. `DeviceProvisioningActivity` (exported, protected by
  `android.permission.DUMP`, so only the adb shell or the system can start it) runs the step;
  `scripts/android-liveness-device-cert.sh --csr` signs the request with MockSync's DEVICE CA and pushes
  `device-chain.pem`. The chain is checked before use and again at the next capture: certificate key equals the
  Keystore key (`KEY_MISMATCH`), may sign, valid now, each certificate issued by the next, issuers are CAs, the chain
  ends in a self-signed CA; a chain from another CA is refused (`UNTRUSTED_CA`) unless the request says
  `replace_ca=true` (`--replace-ca`). Only the certificate chain is replaced; the private key never leaves the
  Keystore. Run on the headless emulator (debug APK) on 2026-10-05: status `PROVISIONED`, chain length 2, issuer
  `MockSync DEVICE CA`, no `run-as`; the brand-new-key case is covered by unit tests only, and no release build was
  captured on a physical phone. ARC's `DEVICE` trust check still decides at capture time.
- **Not provisioned**: a self-signed key is created on first use. Liveness works, but ARC's DEVICE trust check
  rejects the capture, so a resident capture cannot be stored. `LivenessService.isBuiltInDeviceProvisioned()`
  lets the caller show this before starting.

What the model does not protect against: a rooted device where an attacker controls the app process can feed
frames or use the key; L0 accepts that risk by definition. `android.block_emulator=Y` refuses emulators; a
hardware-backed L1 device would need a vendor SBI app (`SbiIntentDevice`).

## 6. Threads

| Thread | Work |
|---|---|
| main | Screen, permission flow, rendering of UI states posted by the runner. |
| `liveness-worker` (one per screen) | Every session call: open, frame delivery, `tick()`, capture, sign, bind, close. |
| CameraX analysis thread / `liveness-replay` | Frame copies into the latest-frame hand-off. |
| engine loader | Model verification and ONNX Runtime session creation, once per process. |
| `liveness-model-import` | Offline model import: checks, copy, new engine build and self-test (section 14). |
| audit writer | Audit rows in order, off the worker thread. |

The session is only touched by its worker; `cancel()` is the one call made from another thread, as the core
contract allows. Buffers are wiped when a session closes; the device gets a copy of the capture frame and wipes
it after signing.

## 7. Build

The engine jars are not committed. `scripts/android-build.sh` checks `~/.m2/repository/io/mosip/liveness/` for
the version in `android/build.gradle` (`livenessEngineVersion`) and, when missing (or with
`--install-engine`), runs `mvn -f liveness/pom.xml -pl liveness-core,liveness-onnx,face-match-sdk -am install -DskipTests`; `--test-only` runs the code generation, copies the Gradle wrapper from Flutter's cache when it is missing and then runs the unit tests of `clientmanager`, `livenessmanager` and `app`.
A fresh clone therefore builds with one command: `scripts/android-build.sh` (plus `--test` for the unit tests,
`--device-test` for the instrumented test).

- `:livenessmanager:prepareLivenessModels` runs the engine's own `ModelRegistry.verifyAll()` on the repository's
  `models/` bundle (manifest signature and SHA-256 of every model) at build time and copies the verified files to
  `assets/liveness/models/`. A changed model, manifest or signature fails the build. The app verifies them again
  when it loads them.
- `:livenessmanager:verifyNoOnnxTelemetry` (runs before `preBuild`) reads the manifests of every Android library
  on the debug runtime class path and fails the build if one declares a `TelemetryInitializer` or if ONNX Runtime
  asks for a permission. ONNX Runtime is 1.20.0, the desktop client's version (ADR 0003); 1.29 and later would also add
  a telemetry initializer.
  `OnnxRuntimeSupplyChainTest` also checks the pinned version and that the ONNX Runtime package has no telemetry
  initializer class.
- ABI filters: debug APKs carry `arm64-v8a` and `x86_64` only (ONNX Runtime is about 30 MB per ABI). The release
  build type keeps upstream's four ABIs.
- CameraX 1.4.2: the newest line that builds with compileSdk 34 and AGP 8.3.2 (1.5 needs compileSdk 35).

## 8. Tests

| Test | Kind | Checks |
|---|---|---|
| `GlobalParamConfigSourceTest` | JUnit 4 + Mockito | tunable keys read the local setting, a local setting cannot switch liveness off, synced master switch, blank and failing values, trimming, change detection |
| `ArcAuditSinkTest` | Robolectric | every core event ID exists in ARC's `AuditEvent`; JSON description with component `REG-MOD-111`; a failing store never reaches the session |
| `AndroidImageDecoderTest`, `PixelOrientationTest` | Robolectric / JVM | RGBA camera frame turned upright, JPEG and PNG, mirror flip, JPEG 2000 signatures, corrupt data; rotation 0/90/180/270, channel order |
| `FaceIsoRecordsTest`, `SbiCaptureCodecTest` | Robolectric | ISO record carries size and quality, malformed record rejected; signed payload has exactly ARC's `CaptureDto` fields, signature covers header and payload, the response parses with ARC's own DTOs |
| `EngineLoaderTest` | Robolectric | loads once, integrity failure kept until restart, other failures retried, slow load reports not ready |
| `EngineLoaderTest` (more rows) | Robolectric | `fromConfig`: blank or default ID gives the built-in engine, another ID goes through `ServiceLoader` (`FakeVendorEngineProvider`), an unknown ID fails closed with `CONFIG_INVALID` |
| `SbiIntentDeviceTest`, `FaceDeviceSelectionTest` | Robolectric | discover JSON parsing and states, `no_stream_policy`; `android.device` routing and fallbacks |
| `DeviceProvisioningTest` | Robolectric | CSR content, every chain check (`KEY_MISMATCH`, `NOT_FOR_SIGNING`, `NOT_VALID_NOW`, `NOT_ISSUED_BY_CA`, `NOT_A_CA`, `NO_CA_CERTIFICATE`, `UNTRUSTED_CA`), `replace_ca`, file cleanup |
| `ModelBundleImporterTest` | Robolectric | offline model import (section 14) |
| `LivenessParityDeviceTest`, `LivenessPerformanceTest` | instrumented (emulator or phone) | desktop-parity replay (6 committed cases; [../test-report/interoperability.md](../test-report/interoperability.md)); per-frame time and memory kit ([../test-report/android-performance.md](../test-report/android-performance.md)) |
| `LivenessServiceTest` | Robolectric | policy from the synced configuration, capture intent round trip, safe defaults for adb extras, pass and failure results through the intent, missing result = cancelled |
| `MessageResolverTest` | Robolectric | every engine message key has a string in all six languages with the same placeholders; synced guidance override; ARC language codes; Arabic is right-to-left |
| `LivenessCaptureActivityTest` | Robolectric | the screen uses the request's language (Arabic text, right-to-left layout; English left-to-right); liveness off closes the screen with `CANCELLED` |
| `OnnxRuntimeSupplyChainTest` | Robolectric | ONNX Runtime pinned to 1.20.0; no telemetry initializer class in the onnxruntime-android classes jar (the Gradle task `verifyNoOnnxTelemetry` checks the library manifests) |
| `LivenessEngineReplayTest` | instrumented (emulator) | signed models verify from the APK assets; YuNet finds one face in the replay frame; the real engine rejects the replayed still (`FAIL_PAD`, reason `FROZEN`, `REG-EVT-LV11` once) |
| `AndroidKeystoreSignerTest` | instrumented (emulator) | self-signed key before provisioning; a provisioned PKCS#12 (the format of `scripts/android-liveness-device-cert.sh`, throwaway test CA in `src/androidTest/assets/liveness-test/`) is imported into the Keystore, the files are deleted, signatures verify with the issued certificate |

Commands (Git Bash, JDK 21):

```bash
scripts/android-build.sh --test-only                     # unit tests: clientmanager (1,186), livenessmanager (210), app (64)
scripts/android-run-emulator.sh --headless               # boot, install, launch
scripts/android-build.sh --test-only --device-test       # instrumented test on the emulator
scripts/android-liveness-demo.sh --shots 6               # capture screen on replay frames, screenshots
```

Automated runs never use the live webcam: they replay frame packs. Debug builds bundle one pack,
`liveness-replay/mockmds-still`: the Gradle task `prepareReplayFrames` copies MockMDS's face stream image
(`devices/mock-mds/Biometric Devices/Face/Stream Image/0.jpeg`, MPL-2.0) into the debug assets at build time, so
no second copy is kept in git and release builds carry no replay frames. More packs can be pushed with
`scripts/android-liveness-demo.sh --push DIR --pack NAME`; use synthetic or openly licensed frames only.

## 9. Design decisions for the module build

- The engine is not copied as a jar into `livenessmanager/libs/` with a SHA-256 check. The module reads the
  engine from the local Maven repository instead, built from the same commit by `scripts/android-build.sh`: no
  binary in git, one version for desktop and Android, and the Gradle build uses the engine's own
  `ModelRegistry` to verify the model bundle. `mavenLocal()` is limited to the group `io.mosip.liveness`.
- No typed liveness getters are added to `GlobalParamRepository`; the `GlobalParamConfigSource` adapter reads the keys, so
  `clientmanager` needs no liveness-specific code and the core resolver applies defaults and ranges exactly as on
  desktop.

## 10. Not done in this module (next steps)

- Done in section 11. ARC call sites (face capture, `MainActivity` result handling, `BiometricsDto`, BIR `others`, packet and supervisor face authentication, onboarding, Flutter screens and strings): `BiometricsDetailsApi` must start the screen for FACE when
  `isEnforced(workflow)` is true, `MainActivity.onActivityResult` must handle request code 6, and the result must
  feed `handleRCaptureResponse`, the BIR `others` entries and the Flutter UI. The Flutter strings (ARB files) and
  the Pigeon calls belong there too.
- `Biometrics095Service.isDeviceValid` accepts only certification `L1` today; accepting the
  `mosip.registration.mds.allowed_certifications` list is needed before a built-in L0 capture is stored.
- Done in section 11.7 with the `s3-main` pack. A passing replay demo needs a frame pack of a live subject doing the challenges; only the still pack is
  bundled, so the emulator demo shows the attack path (failure, retry, maximum attempts).

## 11. ARC flows that use the module

This section covers the ARC side (the call sites listed in section 10): the resident and onboarding face
capture, operator and supervisor face authentication, the Flutter screens and what was run on the emulator. It
replaces the first and last bullets of section 10.

### 11.1 Parts

| Part | Where | Job |
|---|---|---|
| `FaceCaptureLivenessGate` | `android/app/.../registration_client/liveness/` | Liveness rule for faces that are stored: the resident face (workflow `registration`) and the operator onboarding face (`onboard_auth`). Counts failed attempts per subject (registration ID or operator) and field across capture screens, so closing the screen and pressing Scan again does not reset `max_retries`. Keeps the last outcome per field for Flutter. |
| `LivenessCaptureLauncher` | same | Starts `LivenessCaptureActivity` (request code 6) from `MainActivity`, one at a time, in the display language. `MainActivity.onActivityResult` hands it every result code, so a cancel also completes the Flutter call. |
| `FaceAuthFlow` | same | One face authentication: `FaceAuthenticator.precheck` (no camera when refused), the capture screen, then the 1:1 match on a worker thread. A successful `login_auth` leaves a one-time sign-in grant (60 s) for that user. |
| `DebugReplay` | same | Debug builds only: the file `<external files>/liveness-replay/flow-source.txt` (for example `s3-main 15`) makes every ARC capture replay that frame pack instead of the camera. |
| `LivenessApi` (Pigeon) | `android/app/.../api_services/LivenessApi.java`, `pigeon/liveness.dart` | Flutter bridge: `isEnforced`, `isFaceAuthAvailable`, `setLanguage`, `getLastCaptureOutcome`, `authenticateFace`. Messages are resolved on the native side from the engine catalogue in the display language, so Flutter shows the same text as the capture screen and the desktop client. |
| `FaceAuthenticator`, `ArcOperatorDirectory`, `EngineFaceVerifier` | `livenessmanager/.../auth`, `.../match` | Liveness first, then the role check (`REGISTRATION_SUPERVISOR` for `eod_auth` and `exception_auth`), then a match of the capture's ISO face against the user's onboarding faces (`user_biometric` rows of type `FACE`), using the engine's own YuNet and SFace sessions through the `face-match-sdk` matcher. Threshold `mosip.registration.liveness.face_auth.match_threshold` (default 40, floor 20, the same 0-100 cosine scale as the desktop SDK). `BLOCK_AND_LOCK` after the last attempt locks the account; a face mismatch at sign-in counts as a failed sign-in. |
| Flutter widgets | `lib/ui/liveness/` | `FaceAuthPanel` ("Sign in with face" / "Authenticate with face" and its outcome), `LivenessOutcomeBanner` (one-line result with icon, colour and text; a live region for screen readers), `liveness_texts.dart` (ARB fallbacks). |

### 11.2 Resident and onboarding face capture

1. Flutter calls `BiometricsApi.invokeDiscoverSbi(field, "Face")` as before. `BiometricsDetailsApi` asks the gate
   whether the field's workflow is enforced. If not, the upstream SBI discovery runs unchanged.
2. If the subject has used up its attempts, the call completes with `BLOCKED` and no screen opens.
3. Otherwise the capture screen runs (the retries of one transaction happen inside it). Only a `PASS` with an
   rCapture response continues: the response goes through the same `handleRCaptureResponse` checks as an external
   SBI device (signature, `DEVICE` trust, timestamp, transaction ID). Then the `LIVENESS_*` evidence is attached to
   each `BiometricsDto`, and `RegistrationServiceImpl.buildBIR` writes it into the BIR `others`. The built-in
   device signs `bioSubType` `""` for FACE; it is set to `null` because ARC's face lookups expect that.
4. Any other result completes the call with `FAILED`, `BLOCKED` or `CANCELLED`. Flutter then skips
   `getBestBiometrics`, so no face is stored and no ARC attempt is counted (PS04 task 5). The Flutter block shows
   the outcome from `getLastCaptureOutcome` under the Scan button.

The built-in capture does not go through SBI discovery, so `Biometrics095Service.isDeviceValid` (L1 only; section 10)
is not on this path.

### 11.3 Operator and supervisor face authentication

| Screen | Workflow | Behaviour |
|---|---|---|
| Login, password step | `login_auth` | "Sign in with face" appears when face authentication is available for the user. On a pass, `AuthResponseApi.loginWithFace` consumes the grant and opens the session from the token of an earlier online sign-in, like offline password sign-in. Without a fresh grant it answers `REG_FACE_NOT_VERIFIED`. Works offline. |
| New registration, Authentication step | `packet_auth` | "Authenticate with face" next to the password. A pass enables the Authenticate button without a password. |
| Pending approval, supervisor dialog | `eod_auth` | Shown whenever liveness is on for `eod_auth`; a non-supervisor gets "This user is not a supervisor" and no camera opens. A pass submits the selected packets. Approval no longer needs a network connection unless `mosip.registration.android.offline_approval.enable=N`. |

Audit events: `LOGIN_WITH_FACE` (REG-AUTH-008) and `REG_SUPERVISOR_AUTH_FACE` (REG-EVT-101) as on the desktop
client; `REG_OPERATOR_AUTH_FACE` is REG-EVT-115 because ARC already uses REG-EVT-054 for `CREATE_PACKET_FAILED`.
The LV01-LV17 events come from the engine through `ArcAuditSink`.

`LoginService` now saves the signed-in user ID (`SessionManager.saveUserId`). Upstream never wrote `USER_ID`, so
onboarding stored the operator's templates under an empty user ID and no face could be matched to a user.

### 11.4 Flutter text and layout

New strings are in `assets/l10n/app_{en,ar,fr,hi,kn,ta}.arb` (`liveness_*` keys; two ICU `select` messages
cover every status and error code). Engine messages arrive already translated from the native side. The widget
tests run the panel in Arabic and check the right-to-left layout.

### 11.5 Deviations

- `SUPERVISOR_OVERRIDE` for residents: implemented in section 13.1 (earlier versions treated it as `BLOCK`).
- Face authentication matches against the onboarding face kept on the device instead of calling the server, so it
  works offline.

### 11.6 Tests

- `flutter test test/liveness/`: 26 widget tests (panel availability, pass in the screen language, busy state,
  engine and ARB messages, supervisor refusal, Arabic right-to-left, banner semantics).
- `./gradlew :app:testDebugUnitTest`: `LivenessApiTest` (6), `FaceAuthFlowTest` (5),
  `FaceCaptureLivenessGateTest` (8), `LivenessCaptureLauncherTest` (6).
- `:livenessmanager:testDebugUnitTest` includes `FaceAuthenticatorTest` (12), `ArcOperatorDirectoryTest` (4) and
  `EngineFaceVerifierTest` (5). In `:clientmanager`, `RegistrationServiceImplTest.testBuildBIR_LivenessEvidence`
  checks the BIR `others`.

### 11.7 Run on the emulator (2026-09-30, MockSync, debug APK, frame packs from `run/frame-packs`)

| Flow | Pack | Observed |
|---|---|---|
| Resident face, new registration | `s3-main` | `PASS`; face stored (quality 90 %, attempt 1); banner "Good, face captured successfully" |
| Resident face, retake | `a-s2-screen-photo` | `FAIL_PAD` (`PAD_SCORE`) 3 of 3; the capture screen shows the supervisor recovery message; the ARC banner reads "Face capture could not be completed after 3 attempts..."; pressing Scan again opens no screen |
| Resident face, pack missing | none | `DEVICE_ERROR`: "The face camera is not available..." with Retry |
| Supervisor onboarding face | `s3-main` | `onboard_auth` `PASS`; onboarding saved |
| Supervisor face sign-in | `s3-main` | `login_auth` `PASS`, `FaceMatch[MATCHED, score=94, threshold=40]`, home screen |
| Operator face sign-in, attack | `mockmds-still` | maximum attempts; "Use another sign-in method or contact your supervisor" |

Operator `packet_auth`, supervisor `eod_auth` approval, packet upload, configuration by sync and offline
operation were run end to end later, with `scripts/demo-android.sh`; see section 12 and
`docs/test-report/android-e2e.md`. The emulator crashes seen here were the host's software GPU renderer
(section 12.4), not memory.

Engine limit seen here: with the registration policy shipped at the time (`PASSIVE_THEN_ACTIVE`, threshold 80)
the printed-photo pack `a-s3-print-still` passed passively, as `docs/eval/calibration-notes.md` records. The
`ALWAYS_ACTIVE` registration policy, the default since 2026-09-30, rejects it.

Screenshots without a real face: `docs/ux/screenshots/android/`.

## 12. Changes from the end-to-end run

The scripted end-to-end run (`scripts/demo-android.sh`, report in `docs/test-report/android-e2e.md`) found the
problems below; each is fixed in the code named.

### 12.1 A registration could not be finished on a tablet

| Problem | Where | Fix |
|---|---|---|
| With any finger or iris marked as an exception, ARC asks for an exception photo through a vendor SBI face app. A tablet whose face device is the built-in camera had none, so the registration could not continue. | `BiometricsDetailsApi.startLivenessCapture` | When `mosip.registration.liveness.android.device` is `BUILTIN` (default), the exception photo also opens the capture screen, with workflow `exception_photo` and `LivenessCaptureRequest.photoWhenOff`. With the shipped policy `exception_photo=OFF` the screen runs `session.PhotoCaptureRunner` (also when liveness is off for a resident capture: `FaceDeviceSelection` routes `BUILTIN` with liveness off there, gate outcome `NOT_REQUIRED`, no attempt counted): it streams 1.5 s, signs the newest frame with the built-in device (same ISO record and SBI response as a liveness capture) and returns a pass marked `LIVENESS_NOT_REQUIRED`, without liveness evidence (as on desktop). A policy other than `OFF` runs the full liveness check. Attempts are counted under their own key (`FaceCaptureLivenessGate.exceptionPhotoField`). |
| The Biometric Details step never enabled CONTINUE after a face capture from the built-in device: it reports quality 90, ARC's face threshold is 90, and the registration screen treated `quality <= threshold` as not good enough (operator onboarding accepts `quality >= threshold`). | `lib/ui/process_ui/widgets_mobile/biometric_capture_scan_block_portrait.dart` (`validateCaptureException`) | Upstream off-by-one: `<=` changed to `<`, the same rule as onboarding. |

### 12.2 Attempts across capture screens

Closing the capture screen and pressing Scan again started the screen at "Attempt 1 of 3" again, while the gate
already counted the earlier failures; the last screen could therefore allow more attempts than
`max_retries`. The gate now passes the attempts already used (`usedAttempts`) as
`LivenessCaptureRequest.priorAttempts`; `LivenessRunner` numbers the sessions from there and passes them to the shared driver as prior failures (`priorFailures`), so the driver's one transaction continues the count and the screen shows "Attempt 2 of 3", the third failure shows the
recovery message and writes `REG-EVT-LV11`, and a further Scan opens no screen.

Since 2026-10-05 the next screen also receives two more values of the earlier screens of the same subject and
field (resident and onboarding capture: `FaceCaptureLivenessGate.nextRequest`, opened through
`LivenessCaptureLauncher.launch(gate, subject, field, photoWhenOff, callback)`; face authentication: per workflow
and user in `FaceAuthFlow`):

- `liveness.prior_suspected_attack` (`LivenessCaptureRequest.priorSuspectedAttack`): a counted PAD, frame
  integrity or binding failure since the last pass. The screen's transaction starts with it, so the screen never
  offers the supervisor override, even when its own failures were timeouts.
- `liveness.prior_low_light` (`LivenessCaptureRequest.priorLowLight`): the uncounted dim-light results since the
  last counted failure. `LivenessRunner` always turns on the engine's low-light rule
  (`LivenessSessionDriver.Builder.trackLowLight(true)`, with the carried count through
  `priorFailures(attempts, attack, lowLight)`): a PAD rejection of a dark face ends as "We could not verify face
  liveness. Please try again." with "Improve lighting", not counted, at most `passive.low_light_max_uncounted` (3)
  times; the next one counts. Each result reports the count (`LivenessCaptureResult.lowLightRetries()`, extra
  `liveness.result.low_light`); the gate and `FaceAuthFlow` keep it and reset it on a counted failure, a block or a
  pass. Dim, dim, close the screen, Scan again, dim is therefore still uncounted, and the next dim try counts as
  attempt 1.

Tests (2026-10-05, JDK 21, `--rerun`: clientmanager 1,186, livenessmanager 194, app 64, 0 failures; Flutter
`test/liveness/` 26): `LivenessRunnerTest` (dim, dim, close, reopen, dim uncounted, then the next dim counts;
an earlier attack never offers the override; every session gets the count; range clamped),
`FaceCaptureLivenessGateTest` and `FaceAuthFlowTest` (count kept per key, reset by a counted failure, a block or
a pass; the next request carries attempts, attack and count), `LivenessCaptureActivityTest` (both extras round
trip, missing extras mean `false` and 0), `LivenessServiceTest` (`isBuiltInCameraFaceDevice` true for missing,
blank, `BUILTIN` and unknown values, false only for `SBI`). No camera or emulator run of this change yet.

### 12.3 Packet evidence as on desktop

- `packet_meta_info.json`: the `biometrics` entry of a face that passed liveness has `livenessDecision` and
  `livenessMethod` (`RegistrationServiceImpl.addLivenessMetaInfo`).
- Operator face: a successful `packet_auth` face authentication keeps the signed capture
  (`FaceAuthOutcome.capture()`); `OperatorFaceEvidence` runs ARC's SBI checks on it and stores it with its
  `LIVENESS_*` evidence as the registration's officer biometrics, which the packet carries as
  `officer_bio_cbeff` (meta `officerBiometricFileName`, `officerPassword=false`), like the desktop client.

### 12.4 Test support (debug builds only)

- Prompt-following replay: `flow-source.txt` = `follow <prefix> [fps] [masked]` plays `<prefix>-main` and, 500 ms
  after the screen asks for a blink, smile or head turn, switches to `<prefix>-blink` / `-smile` / `-turn-left` /
  `-turn-right` (`device.ReplayPromptFollower`, `ReplayFrameSource.switchTo`). After a movement the neutral clip
  continues where it was left (`switchBack`): restarting it from its first frame repeats frames, which the engine's
  loop check correctly reports as a replayed video. The replay folder is read when an attempt starts, so a test can
  change what the "camera" shows between attempts.
- Follow mode answers every action of the pool (`ReplayPromptFollower.PACK_ACTIONS` covers every challenge type: blink, smile, turn left and right, look up and down, gaze left and right); pack names are matched without regard to case, with `_` or a space read as `-`, so `S3-Turn_Left` works, over pushed folders and APK asset packs. Every other prompt, including "Look directly at the camera and hold still", returns to `<prefix>-main` at once.
- `masked` keeps the preview dark, so screenshots of the capture screen show no face.
- Logcat lines for tests and measurements (no image data): `Screen state <phase> <message key>` on each new
  prompt, `Audit stored <event> <description>` for each stored audit, and `Liveness frame time <workflow>
  session <n>: frames=… p50=… p95=… max=…` at the end of each attempt (`session.FrameTimer` measures the
  session's own processing of each frame, not the wait for the camera).
- The capture screen shows the Offline badge also during a replay.
- Emulator: the host software renderers (`-gpu swiftshader_indirect`, and `lavapipe` as the `guest` fallback)
  crashed the emulator with an access violation in generated code every few minutes on ARC's biometric
  screens (Windows Application log, event 1000). `-gpu host` did not crash; `scripts/android-run-emulator.sh`
  now defaults to `auto`.

## 13. Supervisor override, metrics, diagnostics, execution provider, release build

This section closes the Android rows 8.9, G4, G7, G8 and B8 of `docs/traceability.md` and adds a release build.
It replaces the first bullet of section 11.5.

### 13.1 Supervisor override for residents (`on_max_failure=SUPERVISOR_OVERRIDE`)

Same rule as the desktop client: after the resident's last attempt failed, a second person with the supervisor role may accept the face; the
packet then says `LIVENESS_DECISION=OVERRIDE` and audit `REG-EVT-LV16` names the supervisor. The engine's
`LivenessTransaction` decides whether an override is possible (`LivenessCaptureResult.isOverrideAllowed()`): only
for resident workflows, never after `PAD`, `INTEGRITY` or `BINDING` failures. The supervisor must pass face
authentication with liveness first (the desktop client has the same rule: another user ID, face authentication, 60 s
validity and a checked photo).

| Step | Where | What happens |
|---|---|---|
| 1 | `ui.LivenessCaptureActivity` | After the last attempt, when the result allows an override and the policy is `SUPERVISOR_OVERRIDE`, the screen shows "Supervisor override" next to Cancel. A dialog shows `liveness.override.confirm` and asks for the supervisor's user ID (the field starts empty). `SupervisorOverrideFlow` refuses the signed-in operator's own ID, or no signed-in operator, with `NOT_SUPERVISOR` before any camera opens (since 2026-10-10; the desktop already refused it). The screen closes with the failure and `overrideRequestedBy()` set. |
| 2 | `FaceCaptureLivenessGate.record` | Keeps the failed transaction's packet evidence (`overrideEvidence`) while the field is blocked. |
| 3 | `SupervisorOverrideFlow` (app) | Supervisor face authentication, workflow `exception_auth`: liveness, `REGISTRATION_SUPERVISOR` role, 1:1 match with the supervisor's onboarding face (`FaceAuthFlow`). A refusal (not a supervisor, no match, liveness failed, `exception_auth` not enforced) takes no photo; the field stays blocked with the refusal's message. |
| 4 | capture screen with `LivenessCaptureRequest.override(SupervisorOverride)` | `session.PhotoCaptureRunner` takes the resident's photo with the built-in device, signed like any capture, without a liveness check. The result is a pass with reason `SUPERVISOR_OVERRIDE` and the evidence of step 2 with `LIVENESS_DECISION=OVERRIDE`. The screen writes `REG-EVT-LV16` (`SupervisorOverride.auditEvent`, supervisor ID, session ID of the failed transaction). |
| 5 | `BiometricsDetailsApi.storeLivenessCapture` | The photo goes through ARC's SBI checks like every capture and is stored with its evidence in the BIR `others`; `packet_meta_info.json` gets `livenessDecision=OVERRIDE`. The gate resets the count and Flutter shows `liveness.override.used` ("Face stored with a supervisor override. It is flagged for review in the packet.") under Scan. |

Pressing Scan again while the field is blocked and an override is still possible opens the capture screen in
"override offer" mode (`LivenessCaptureRequest.overrideOffer(true)`): it shows the maximum-attempts message and
the override button, and runs no session. The capture screen re-checks the policy for both override modes and
refuses them for authentication workflows or a policy other than `SUPERVISOR_OVERRIDE`.

Difference from desktop: the desktop client checks the photo (exactly one face, passive PAD) before it stores it and
writes one `REG-EVT-LV16` per grant or refusal; Android writes `LV16` when the override photo is signed (the screen
that took it), so a photo that ARC's SBI checks then reject still has its `LV16` row, and it takes the override photo
without a face-count or PAD check.

### 13.2 Anonymised metrics (G7, G8)

`LivenessRuntime` owns one `LivenessMetrics` (the engine's collector) and passes it to the engine
(`EngineLoader.onnx(..., metrics, ...)`, which delegates to `fromConfig`), so every session of the process is counted: resident captures and
face authentications.

- After every attempt the capture screen calls `LivenessRuntime.publishMetrics(policy.isMetricsEnabled())`
  (`metrics.enable`, default `Y`). One line (`metrics.MetricsReport.line`) goes to Logcat (tag
  `LivenessMetrics`) and to `files/liveness/metrics.log` in the app's private storage (`metrics.MetricsLog`, one
  background thread, rotated to `metrics.log.1` at 256 KB).
- The line has the same names as the desktop client's `logs/metrics.log`: `sessions`, `retries`, `passes`,
  `passive_passes`, `active_triggers`, `challenges_passed`, `frames_analysed`, non-zero `failures.<REASON>`,
  `time.total.avg_ms` / `max_ms`, `time.passive.avg_ms`, `time.challenge.avg_ms` (challenge completion time),
  `time.frame.avg_ms` / `max_ms` (processing time), `rate.retry`, `rate.failure`.
- No user ID, registration ID, device serial, image or per-capture score is in the line.

Example from the emulator (`adb logcat -s LivenessMetrics`, 2026-09-30):

```
2026-09-30T06:43:32.971166Z liveness sessions=2 retries=0 passes=1 passive_passes=0 active_triggers=1 challenges_passed=2 frames_analysed=128 failures.ENGINE=1 time.total.avg_ms=7019 time.total.max_ms=10223 time.passive.avg_ms=0 time.challenge.avg_ms=1734 time.frame.avg_ms=80 time.frame.max_ms=1630 rate.retry=0.000 rate.failure=0.500
```

### 13.3 Diagnostic mode (G5 on Android) and `REG-EVT-LV17`

- With `diagnostic.enable=Y` the capture screen shows a panel under the title: workflow, policy mode, execution
  provider, the last attempt's frame times (`frames= p50= p95= max=`) and the three summary lines of the metrics
  (`MetricsReport.summary`). The engine's diagnostic lines go to Logcat (tag `Liveness`), as before.
- `audit.DiagnosticModeTracker` writes `REG-EVT-LV17` when the value a policy resolution sees differs from the
  last one seen on this tablet (kept in the module's shared preferences). On Android the value arrives by sync or
  as the operator's local setting, so there is no switch to hook; the event names the signed-in user
  (`LivenessRuntime.setUserIdSupplier`, set by `LivenessModule` from ARC's `SessionManager`).

### 13.4 Configurable guidance (G4)

`ui.MessageResolver` applies `mosip.registration.liveness.guidance.<messageKey>.<lang>` through the engine's
`LivenessUiStateMapper.resolveText` for the capture screen, and `LivenessMessages` uses the same resolver for the
Flutter banners, so a synced text replaces the bundled one everywhere. Checked by `MessageResolverTest`
(`fillsArgumentsAndUsesSyncedGuidanceOverride`); not re-checked on the emulator in this round.

### 13.5 Execution provider (B8)

| Item | Value |
|---|---|
| Key | `mosip.registration.liveness.engine.execution_provider`: `CPU` (default), `XNNPACK`, `NNAPI`; the key name the engine uses for the same setting on desktop. Synced only (not a local override). Read when the engine loads, so a change applies at the next app start. |
| Code | `engine.Accelerator` (parsing), `engine.AcceleratorCustomizer` (adds the provider through the engine's `OnnxRuntimeOptions.SessionCustomizer` hook), `engine.EngineLoader` (fallback and status). |
| XNNPACK | `SessionOptions.addXnnpack` with `intra_op_num_threads` = the engine's thread count; the session's own intra-op pool is set to 1 thread, as ONNX Runtime recommends. |
| NNAPI | `SessionOptions.addNnapi()` with default flags. |
| Fallback | A provider missing from the ONNX Runtime build is skipped (`UNAVAILABLE`); a provider that rejects the options is skipped for that model (`REJECTED`); either way that model runs on the CPU. If the engine still fails to load (anything but a model integrity failure), `EngineLoader` loads it again on the CPU. A provider problem never switches liveness off. |
| Status | Logcat `Liveness engine ready: ... execution <status>` and the diagnostic panel: `CPU`, `XNNPACK`, `XNNPACK (partly CPU)`, `CPU (NNAPI unavailable)`, `CPU (NNAPI rejected)`, `CPU (XNNPACK failed to load)`. |

Measured: nothing on a phone yet. On the emulator every load reported `execution CPU` (the shipped value). XNNPACK and NNAPI
were not run on a device; the selection and every fallback path are covered by `AcceleratorCustomizerTest`. The
measurement kit (`scripts/android-perf.sh`, instrumented test `LivenessPerformanceTest`: CPU, XNNPACK and NNAPI by
1, 2 and 4 threads, per-frame time and memory) is written and compiles, but the test APK was not built or installed and
no phone or emulator run exists; the results table in [../test-report/android-performance.md](../test-report/android-performance.md) is empty. Whether the NNAPI provider loads in `onnxruntime-android` 1.20.0 is unverified (B7/B8 in `docs/traceability.md`).

Model warm-up: `EngineLoader` runs the face detector and the PAD models once on a blank 640x480 frame after
loading (100-500 ms on the emulator). Without it, the first frames of the first session after an app start took
640-1630 ms on the loaded emulator, three in a row exceeded `engine.inference_slow_ms` (500), and the session ended
with `INFERENCE_TIMEOUT` (observed twice on 2026-09-30). The Face Mesh model is not warmed (it needs a detected
face), so a very slow device can still hit this on its first session.

### 13.6 Release build

| Item | How |
|---|---|
| Server URL | `arcReleaseServerBaseUrl` (Gradle property, environment variable `ARC_RELEASE_SERVER_BASE_URL`, or `local.properties`) sets `BuildConfig.BASE_URL` of `app` and `clientmanager` for release builds; empty keeps the upstream `serverBaseURL`. `scripts/android-build.sh --release` passes `--server-url` (default `http://127.0.0.1:8080`, MockSync through `adb reverse`). |
| Plain HTTP | Only when the release URL is `http://127.0.0.1` or `http://localhost` (any port): `app/src/releaseLoopback/res/xml/network_security_config.xml` is added to the release resources and allows cleartext to those two hosts only. Any other release server must be HTTPS. |
| Signing | `signingConfigs.release` reads `storeFile`, `storePassword`, `keyAlias`, `keyPassword` from `android/local.properties` or `key.properties` (both git-ignored), else from `ARC_RELEASE_STORE_FILE`, `ARC_RELEASE_STORE_PASSWORD`, `ARC_RELEASE_KEY_ALIAS`, `ARC_RELEASE_KEY_PASSWORD`. `scripts/android-release-keystore.sh` creates an RSA 3072 PKCS#12 keystore outside the repository (default `../secrets/android-release.jks`) with a random password and writes the four properties. `scripts/android-build.sh --release` sets `ARC_REQUIRE_RELEASE_SIGNING=1`, so a release without a keystore fails instead of silently using the debug key (`--allow-debug-signing` for size checks). |
| Side-by-side install | `--app-id-suffix .release` (`ARC_RELEASE_APPLICATION_ID_SUFFIX`) installs the release app next to the debug app on a test device; real releases leave it empty. |
| Debug-only features | Replay frame packs, `config.<key>` extras and the adb launch of the capture screen are debug-only (`BuildConfig.DEBUG`); the release APK carries no replay frames. |

Run on 2026-09-30 (`scripts/android-build.sh --skip-codegen --release --abis x86_64 --app-id-suffix .release`):
build 153 s, `app-release.apk` 123,180,952 bytes, signer `CN=Android Registration Client release, OU=Badmosip,
O=MOSIP Decode 2026`, certificate SHA-256 `810ff5c0...2e009d`. Installed next to the debug app on the emulator,
it loaded the engine ("Liveness engine ready ... execution CPU"), reached the login screen and called MockSync
over plain HTTP through `adb reverse` (MockSync log: `GET /v1/syncdata/actuator/health -> 200`). A full sign-in
was not done: a new installation has a new machine key that MockSync must register first (install guide section
5). The `ARC_REQUIRE_RELEASE_SIGNING` refusal was not exercised (the keystore was configured).

### 13.7 Tests

| Test | Checks |
|---|---|
| `SupervisorOverrideTest` | who may override (resident + `SUPERVISOR_OVERRIDE` only), evidence with `OVERRIDE`, `REG-EVT-LV16` with supervisor and session, request and result extras round trip, no override after a suspected attack |
| `LivenessCaptureActivityTest` (5 new) | override offer refused under `BLOCK`; override photo refused under `BLOCK`; override button, supervisor ID required, result carries the request; diagnostic panel shown only with `diagnostic.enable=Y` |
| `PhotoCaptureRunnerTest` | a photo with liveness off has no evidence; an override photo is flagged and carries the evidence |
| `MetricsReportTest` | metrics line and summary, no zero failure counters, file rotation, write failure contained |
| `DiagnosticModeTrackerTest` | shipped `N` is not a change, on/off audited once each with the signed-in user, first value `Y` audited |
| `AcceleratorCustomizerTest` | key values, provider added when available, CPU fallback when missing, rejected or the runtime cannot list providers, status text |
| `FaceCaptureLivenessGateTest` (3 new) | override evidence kept only when policy and failure allow it; override photo unblocks with the override message; refused supervisor keeps the field blocked |
| `SupervisorOverrideFlowTest` | verified supervisor leads to the override photo request; non-supervisor, failed liveness, face mismatch and `exception_auth` not enforced take no photo |

Demo: `scripts/demo-android.sh` has a new section `override` (checks `A-V1`..`A-V18`, and `A-E8` in the approval
section for the uploaded packet). It also sets a replay profile first (MockSync's shipped policy now runs
challenges for residents and operator sign-in with 30 passive frames, longer than the recorded 4-6 s clips) and
puts every changed key back by value, because ARC keeps a synchronised value when the server stops sending the key.

### 13.8 Run on the emulator (2026-09-30)

| Step | Result |
|---|---|
| Unit tests (`scripts/android-build.sh --test-only`) | clientmanager 1186, livenessmanager 109, app 35: all passed |
| Capture screen, override policy (debug extras, replay `s1-main`, masked, `max_retries=1`) | attempt ended `TIMEOUT` (no attack), `REG-EVT-LV11` with `onMaxFailure=SUPERVISOR_OVERRIDE`, "Supervisor override" shown, diagnostic panel with `CPU`, frame times and metrics (`docs/ux/screenshots/android/capture-override-offered-diagnostics.png`) |
| Override dialog, supervisor ID entered, confirmed | `Capture screen result CANCELED: ... override requested` (`capture-override-supervisor-dialog.png`) |
| Screen in override-photo mode (`liveness.override_supervisor`) | `Photo capture ended: PASS, reasons=[SUPERVISOR_OVERRIDE]`, `REG-EVT-LV16 {"supervisorId":"supervisor1"}`, `Capture screen result OK` |
| Metrics file | `files/liveness/metrics.log` written; the demo's check found no user or registration ID in it (A-V14, A-V15 passed) |
| Full `scripts/demo-android.sh` | Not completed. Run 1 (before the replay profile) passed 40 of 71 checks; the failures came from MockSync's changed shipped policy (above) and from the loaded host: the emulator's app and launcher hit ANRs (main thread waiting for the render thread, 37% kernel time, major page faults), so taps and text input were lost. The ARC-side override path (Scan, gate, supervisor face authentication, storage, packet) therefore ran only in unit tests (`SupervisorOverrideFlowTest`, `FaceCaptureLivenessGateTest`), not end to end. |

## 14. Offline model update (bonus B6)

A supervisor can replace the liveness models without an app update, from a signed bundle copied to the tablet
(for example from a USB drive). Same checks, store layout and audits as the desktop client's
`LivenessModelImporter`; the signature, hash and version checks are the engine's own `ModelRegistry`
(`liveness-onnx`), not a second copy.

| Class | Role |
|---|---|
| `engine.ModelBundleImporter` | Checks a bundle (folder or `.zip`), copies it into the store, verifies the copy, activates it and switches the engine; refuses and audits otherwise. Imports are serialised. |
| `engine.ModelBundleStore` | `files/liveness/model-store/` in the app's private storage: one folder per imported bundle and `active.properties` (active folder, version, previous folder, version floor), replaced atomically. |
| `engine.EngineLoader` | Loads the active imported bundle when there is one, else the APK bundle; minimum version is `model.min_version` or the store's floor, whichever is higher. `switchModels(source)` builds a new engine, self-tests it (ready, face detector and passive PAD on a blank frame) and only then swaps it in and closes the old one. |
| app `LivenessApi.importModelBundle(path)` / `getModelBundle()` | Pigeon calls (`pigeon/liveness.dart`) for Flutter; the import runs on a background thread and answers on the main thread. An empty path reads `Android/data/<app>/files/liveness-models/`. |

Checks, in order; the first failure stops the import and the running models stay active:

1. the signed-in user holds `REGISTRATION_SUPERVISOR` or `REGISTRATION_ADMIN` (roles from ARC's `LoginService`);
2. the configured engine is the built-in ONNX engine (a vendor engine manages its own models);
3. `manifest.jws` verifies against the trust anchors built into the engine (RS256);
4. the bundle version is at least `model.min_version` and newer than the running bundle and the store's floor
   (anti-rollback: the floor is the highest version ever activated, so an older signed bundle is refused);
5. every model file in the manifest has the size and SHA-256 the manifest states;
6. the copy in the store verifies again (that copy is what the engine loads);
7. a new engine built from the store copy passes the self-test; the pointer is then switched. If the build or the
   self-test fails, the pointer goes back to the bundle that ran before and the new folder is deleted.

The store keeps the active bundle and the one before it; older imported folders are deleted after a successful
switch. A `.zip` is extracted to the app's cache first, refusing absolute paths, `..`, more than 64 entries or more
than 512 MB.

Audits (through `ArcAuditSink`, user ID added by ARC): `REG-EVT-LV14` for a signature, hash or version refusal,
`REG-EVT-LV13` when the verified models cannot start, `REG-EVT-LV15` with `model.bundle=<name>:<old>-><new>` after
a switch. Results: `IMPORTED`, `NOT_ALLOWED`, `NOT_SUPPORTED`, `NOT_FOUND`, `INVALID`, `TOO_OLD`, `LOAD_FAILED`.

Tests: `ModelBundleImporterTest` (livenessmanager, Robolectric) builds its bundles the way
`scripts/make-sample-model-bundles.sh` does (the model files of `models/`, manifest re-signed with a key generated
at run time, one byte of `MiniFASNetV2.onnx` changed at offset 4096 for the tampered bundle) and covers: valid
bundle activates (also from a `.zip`); tampered file refused; bundle signed by another key refused; older version
and same version refused, also after a newer import; failed self-test leaves the old bundle active; officer
refused; the previous bundle is kept; `EngineLoader.switchModels` keeps the running engine when the new one is
not ready. The engine switch itself is scripted there, because ONNX Runtime for Android does not run in a JVM unit
test.

Not done: no run on a physical phone or an emulator (the engine switch is scripted in the unit tests); no Flutter screen calls `importModelBundle` yet (the desktop client has its import window); a
supervisor button, for example in the device settings tab, is the next step. The import does not check whether a
capture screen is open (the desktop client refuses with `BUSY` during a capture); the import is started from
Flutter, so ARC's main screen is in front and no capture screen is open.
