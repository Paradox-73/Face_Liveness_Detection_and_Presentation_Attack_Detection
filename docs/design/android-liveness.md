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
| `session.LivenessRunner` | One liveness transaction: the first session and its retries. Owns the single `liveness-worker` thread that makes every session call; moves frames from a latest-frame hand-off into the session and calls `tick()` at least every 100 ms; captures, signs and binds on `CAPTURE_READY`. |
| `ui.LivenessCaptureActivity` | The native capture screen (section 3). Renders only the engine's `LivenessUiState`, so its behaviour matches the desktop screen. |
| `ui.MessageResolver` | Message key to text: the module's string resource in the screen's language, replaced by a synced `mosip.registration.liveness.guidance.<key>.<lang>` text when one is configured (same rule as desktop). |
| `ui.OvalGuideView` | Oval face guide with a progress arc; colour follows the state's severity. |
| `device.CameraXFrameSource` | Front camera through CameraX `ImageAnalysis` (`RGBA_8888`, `STRATEGY_KEEP_ONLY_LATEST`), bound to the screen's lifecycle. |
| `device.ReplayFrameSource` | Plays a folder of JPEG/PNG frames as a camera stream (APK assets or the app's external files folder). Debug builds only. |
| `device.BuiltInCameraL0Device` | The device's own camera as an SBI L0 face device inside the client (`FaceCaptureDevice`). |
| `device.FaceIsoRecords` | ISO/IEC 19794-5:2011 face record with a JPEG 2000 image, written with MOSIP `biometrics-util` and the JP2ForAndroid (OpenJPEG) library ARC already ships. |
| `device.SbiCaptureCodec` | Writes and reads the SBI 0.9.5 `rCapture` response JSON (fields of ARC's `CaptureDto`, RS256 JWS with `x5c`). |
| `device.DeviceSigner` / `AndroidKeystoreSigner` | Device key in the Android Keystore (section 5). |
| `device.SbiIntentDevice` | Adapter for a vendor SBI face app reached through Android intents (ARC's current path); it has no preview stream, so the engine's `no_stream_policy` applies. |
| `image.AndroidImageDecoder` | `ImageDecoder` for Android: RGBA camera frames, JPEG/PNG through `BitmapFactory`, JPEG 2000 through JP2ForAndroid, to upright RGB888. |
| `image.PixelOrientation` | Rotation, optional mirror flip and channel order in one pass; pure Java, tested on the JVM. |
| `engine.EngineLoader` | Loads the engine once per process on a background thread (`preload()`, `await(ms)`); a model integrity failure is kept until restart (fail closed, `REG-EVT-LV14`). |
| `engine.AssetModelSource` | `ModelSource` over `assets/liveness/models/` for the engine's `ModelRegistry`. |
| `config.GlobalParamConfigSource` | `ConfigSource` over ARC's `GlobalParamRepository` (section 4). |
| `config.OverlayConfigSource` | Debug-only overrides passed to the screen from adb. |
| `audit.ArcAuditSink` | Writes `REG-EVT-LV01..17` through ARC's `AuditManagerService.auditWithArguments` on one background thread. |
| `audit.LogcatSinks` | Diagnostic lines and standalone audits under the Logcat tag `Liveness`. |

Changes outside the module (surgical, upstream style kept):

- `android/settings.gradle`: `include ':livenessmanager'`.
- `android/build.gradle`: versions (`livenessEngineVersion`, `onnxruntimeAndroidVersion = 1.28.0`,
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

Android-only keys: `mosip.registration.liveness.android.device` (`BUILTIN`, default, or `SBI`),
`mosip.registration.liveness.android.block_emulator` (default `N`) and
`mosip.registration.android.offline_approval.enable`.

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
| audit writer | Audit rows in order, off the worker thread. |

The session is only touched by its worker; `cancel()` is the one call made from another thread, as the core
contract allows. Buffers are wiped when a session closes; the device gets a copy of the capture frame and wipes
it after signing.

## 7. Build

The engine jars are not committed. `scripts/android-build.sh` checks `~/.m2/repository/io/mosip/liveness/` for
the version in `android/build.gradle` (`livenessEngineVersion`) and, when missing (or with
`--install-engine`), runs `mvn -f liveness/pom.xml -pl liveness-core,liveness-onnx -am install -DskipTests`.
A fresh clone therefore builds with one command: `scripts/android-build.sh` (plus `--test` for the unit tests,
`--device-test` for the instrumented test).

- `:livenessmanager:prepareLivenessModels` runs the engine's own `ModelRegistry.verifyAll()` on the repository's
  `models/` bundle (manifest signature and SHA-256 of every model) at build time and copies the verified files to
  `assets/liveness/models/`. A changed model, manifest or signature fails the build. The app verifies them again
  when it loads them.
- `:livenessmanager:verifyNoOnnxTelemetry` (runs before `preBuild`) reads the manifests of every Android library
  on the debug runtime class path and fails the build if one declares a `TelemetryInitializer` or if ONNX Runtime
  asks for a permission. ONNX Runtime stays at 1.28.0: 1.29 and later add a telemetry initializer.
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
| `LivenessServiceTest` | Robolectric | policy from the synced configuration, capture intent round trip, safe defaults for adb extras, pass and failure results through the intent, missing result = cancelled |
| `MessageResolverTest` | Robolectric | every engine message key has a string in all six languages with the same placeholders; synced guidance override; ARC language codes; Arabic is right-to-left |
| `LivenessCaptureActivityTest` | Robolectric | the screen uses the request's language (Arabic text, right-to-left layout; English left-to-right); liveness off closes the screen with `CANCELLED` |
| `OnnxRuntimeSupplyChainTest` | Robolectric | ONNX Runtime pinned to 1.28.0; no telemetry initializer class in the ONNX Runtime package (the Gradle task `verifyNoOnnxTelemetry` checks the library manifests) |
| `LivenessEngineReplayTest` | instrumented (emulator) | signed models verify from the APK assets; YuNet finds one face in the replay frame; the real engine rejects the replayed still (`FAIL_PAD`, reason `FROZEN`, `REG-EVT-LV11` once) |
| `AndroidKeystoreSignerTest` | instrumented (emulator) | self-signed key before provisioning; a provisioned PKCS#12 (the format of `scripts/android-liveness-device-cert.sh`, throwaway test CA in `src/androidTest/assets/liveness-test/`) is imported into the Keystore, the files are deleted, signatures verify with the issued certificate |

Commands (Git Bash, JDK 21):

```bash
scripts/android-build.sh --test-only                     # unit tests: livenessmanager (53) and clientmanager
scripts/android-run-emulator.sh --headless               # boot, install, launch
scripts/android-build.sh --test-only --device-test       # instrumented test on the emulator
scripts/android-liveness-demo.sh --shots 6               # capture screen on replay frames, screenshots
```

Automated runs never use the live webcam: they replay frame packs. Debug builds bundle one pack,
`liveness-replay/mockmds-still`: the Gradle task `prepareReplayFrames` copies MockMDS's face stream image
(`devices/mock-mds/Biometric Devices/Face/Stream Image/0.jpeg`, MPL-2.0) into the debug assets at build time, so
no second copy is kept in git and release builds carry no replay frames. More packs can be pushed with
`scripts/android-liveness-demo.sh --push DIR --pack NAME`; use synthetic or openly licensed frames only.

## 9. Deviations from the plan

- Plan section 4.13 N1 puts the core jar in `livenessmanager/libs/` with a SHA-256 check. The module reads the
  engine from the local Maven repository instead, built from the same commit by `scripts/android-build.sh`: no
  binary in git, one version for desktop and Android, and the Gradle build uses the engine's own
  `ModelRegistry` to verify the model bundle. `mavenLocal()` is limited to the group `io.mosip.liveness`.
- N15 (typed getters in `GlobalParamRepository`) is replaced by the `GlobalParamConfigSource` adapter, so
  `clientmanager` needs no liveness-specific code and the core resolver applies defaults and ranges exactly as on
  desktop.

## 10. Not done in this module (next steps)

- Done in section 11. ARC call sites (plan N3-N5, N8-N13, N17, N18): `BiometricsDetailsApi` must start the screen for FACE when
  `isEnforced(workflow)` is true, `MainActivity.onActivityResult` must handle request code 6, and the result must
  feed `handleRCaptureResponse`, the BIR `others` entries and the Flutter UI. The Flutter strings (ARB files) and
  the Pigeon calls belong there too.
- `Biometrics095Service.isDeviceValid` accepts only certification `L1` today; plan N7 (accept the
  `mosip.registration.mds.allowed_certifications` list) is needed before a built-in L0 capture is stored.
- Done in section 11.7 with the `s3-main` pack. A passing replay demo needs a frame pack of a live subject doing the challenges; only the still pack is
  bundled, so the emulator demo shows the attack path (failure, retry, maximum attempts).

## 11. ARC flows that use the module

This section covers the ARC side (plan section 4.13, N3-N5, N8-N13, N17, N18): the resident and onboarding face
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

The built-in capture does not go through SBI discovery, so `Biometrics095Service.isDeviceValid` (L1 only, plan N7)
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

- `SUPERVISOR_OVERRIDE` for residents behaves as `BLOCK`: the built-in camera stores only the frame a passing
  session selected, so after a failure there is no face to store with an override. The resident sees the recovery
  message.
- Face authentication matches against the onboarding face kept on the device instead of calling the server, so it
  works offline.

### 11.6 Tests

- `flutter test test/liveness/`: 13 widget tests (panel availability, pass in the screen language, busy state,
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

Engine limit seen here: with the shipped registration policy (`PASSIVE_THEN_ACTIVE`, threshold 80) the
printed-photo pack `a-s3-print-still` passes passively, as `docs/eval/calibration-notes.md` records. The
proposed `ALWAYS_ACTIVE` registration policy rejects it.

Screenshots without a real face: `docs/ux/screenshots/android/`.

## 12. Changes from the end-to-end run

The scripted end-to-end run (`scripts/demo-android.sh`, report in `docs/test-report/android-e2e.md`) found the
problems below; each is fixed in the code named.

### 12.1 A registration could not be finished on a tablet

| Problem | Where | Fix |
|---|---|---|
| With any finger or iris marked as an exception, ARC asks for an exception photo through a vendor SBI face app. A tablet whose face device is the built-in camera had none, so the registration could not continue. | `BiometricsDetailsApi.startLivenessCapture` | When `mosip.registration.liveness.android.device` is `BUILTIN` (default), the exception photo also opens the capture screen, with workflow `exception_photo` and `LivenessCaptureRequest.photoWhenOff`. With the shipped policy `exception_photo=OFF` the screen runs `session.PhotoCaptureRunner`: it streams 1.5 s, signs the newest frame with the built-in device (same ISO record and SBI response as a liveness capture) and returns a pass marked `LIVENESS_NOT_REQUIRED`, without liveness evidence (as on desktop). A policy other than `OFF` runs the full liveness check. Attempts are counted under their own key (`FaceCaptureLivenessGate.exceptionPhotoField`). |
| The Biometric Details step never enabled CONTINUE after a face capture from the built-in device: it reports quality 90, ARC's face threshold is 90, and the registration screen treated `quality <= threshold` as not good enough (operator onboarding accepts `quality >= threshold`). | `lib/ui/process_ui/widgets_mobile/biometric_capture_scan_block_portrait.dart` (`validateCaptureException`) | Upstream off-by-one: `<=` changed to `<`, the same rule as onboarding. |

### 12.2 Attempts across capture screens

Closing the capture screen and pressing Scan again started the screen at "Attempt 1 of 3" again, while the gate
already counted the earlier failures; the last screen could therefore allow more attempts than
`max_retries`. The gate now passes the attempts already used (`usedAttempts`) as
`LivenessCaptureRequest.priorAttempts`; `LivenessRunner` numbers the sessions from there and gives its
transaction only the remaining attempts, so the screen shows "Attempt 2 of 3", the third failure shows the
recovery message and writes `REG-EVT-LV11`, and a further Scan opens no screen.

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
