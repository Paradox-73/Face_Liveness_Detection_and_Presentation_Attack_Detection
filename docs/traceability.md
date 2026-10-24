# PS04 requirement traceability

Every requirement of Problem Statement 04 ("Face Liveness Detection and Presentation Attack Detection for
Registration and Biometric Authentication"), where it is implemented on each client, the test that shows it, and
its status on 2026-10-05. Rows were first checked against the code on 2026-09-30; the engine, configuration and client changes of 2026-10-04 and 2026-10-05 (supervisor workflows with challenges on every capture, the retry store, the shared session driver, the low-light ending, model bundle 1.3.0, Android model import, one ONNX Runtime release) are applied to the rows they touch. Figures carry their date.

- Source of the requirements: the PS04 document (overview, resident / operator / supervisor sections,
  configuration, online and offline, recommended stack, expected tasks, mandatory tasks 1-10, UI/UX 1-8, bonus,
  good-to-have, deliverables). Every bullet has one row.
- Status was checked against the code on `main` and against the test runs listed in
  [test-report/README.md](test-report/README.md), not copied from plans.

## How to read the tables

**Status**

| Value | Meaning |
|---|---|
| done | Implemented on every platform the bullet applies to, and shown by a unit test and, where the bullet is a user flow, by an end-to-end run. |
| partial | Implemented but with a stated gap: one platform only, not run end to end, a measured weakness, or only part of the bullet. The note says which. |
| not done | No implementation. |

**Evidence codes**

| Code | Where to find it |
|---|---|
| `DE-D1` ... `DE-D13`, `DE-I1` ... `DE-I3` | Rows of the desktop end-to-end report, [test-report/desktop-e2e.md](test-report/desktop-e2e.md) section 3 (real desktop client, MockSync, MockMDS; reference run 41/41 checks on 2026-09-30 at the earlier passive-first defaults; at the current policy only the runs with the challenge pool cut to blink and smile, 71 of 71 on 2026-10-17 and twice on 2026-10-20) |
| `AE-A1` ... `AE-A16` | Rows of the Android end-to-end report, [test-report/android-e2e.md](test-report/android-e2e.md) section 3 (real Android client on an emulator; reference run 58/58 checks on 2026-09-30 at the earlier passive-first defaults; at the current policy only the runs with the challenge pool cut to blink and smile, 59 of 74 on 2026-10-17, section 15) |
| `scn:<id>` | Scenario `eval/scenarios/<id>.yaml`, replayed through the real engine and models. Results at the current engine and defaults (2026-10-14): [eval/results/20261014-shipped/](eval/results/20261014-shipped/README.md); earlier runs of 2026-10-05: [eval/results/20261005-shipped/](eval/results/20261005-shipped/README.md) (morning), [eval/results/20261005b-shipped/](eval/results/20261005b-shipped/README.md) (evening); results of 2026-09-30: [eval/results/20260930-engine-fixes/](eval/results/20260930-engine-fixes/) (suite `shipped`); results before those fixes: [eval/results/20260930/](eval/results/20260930/). Comparison: [eval/calibration-notes.md](eval/calibration-notes.md) section 0 |
| `CN0` | [eval/calibration-notes.md](eval/calibration-notes.md) section 0: the defaults and engine changes adopted on 2026-09-30, with before and after figures |
| `DE-e2e-13` ... `DE-e2e-16` | Desktop flow `tools/mock-sync/e2e/flows/65-diagnostic-and-models.flow` (diagnostic mode, replacement texts, model import; 15 of 15 checks on 2026-09-30), [design/desktop-liveness-services.md](design/desktop-liveness-services.md) section 12; screenshots `ux/screenshots/desktop/e2e-13..16` |
| `AND-13` | [design/android-liveness.md](design/android-liveness.md) section 13 (override, metrics, diagnostics, execution provider, release build; unit tests and the emulator run of 2026-09-30) |
| `APCER`, `BPCER` | Attack acceptance rate and genuine-user rejection rate, ISO/IEC 30107-3 metric definitions, from the automated suite (technology regression results, not an ISO/IEC 30107-3 evaluation) |
| Test class name | JUnit test class; the module table below gives its folder |
| `T-CORE-*`, `T-SCN-*`, `T-MOCK-*`, `T-SYNC-*`, `T-PERF-*`, `T-MI-*` | Team test IDs, defined in the [Test IDs](#test-ids) table below |

**Path prefixes used in the tables**

| Prefix | Folder |
|---|---|
| `core/` | `liveness/liveness-core/src/main/java/io/mosip/liveness/core/` (shared engine, used by both clients) |
| `onnx/` | `liveness/liveness-onnx/src/main/java/io/mosip/liveness/onnx/` (models and engine factory) |
| `fm/` | `liveness/face-match-sdk/src/main/java/io/mosip/liveness/facematch/` (SFace face-match SDK for MOSIP) |
| `RS/` | `clients/desktop/registration/registration-services/src/main/java/io/mosip/registration/` (desktop service layer) |
| `RC/` | `clients/desktop/registration/registration-client/src/main/java/io/mosip/registration/` (desktop screens) |
| `LM/` | `clients/android/android/livenessmanager/src/main/java/io/mosip/registration/livenessmanager/` (Android native liveness module) |
| `APP/` | `clients/android/android/app/src/main/java/io/mosip/registration_client/` (Android app, native side) |
| `CM/` | `clients/android/android/clientmanager/src/main/java/io/mosip/registration/clientmanager/` (Android client services) |
| `lib/` | `clients/android/lib/` (Android Flutter screens) |

**Where the unit tests live**

| Test classes | Folder |
|---|---|
| `ChallengeSchedulerTest`, `ChallengeObserverTest`, `ActivePhaseTest`, `LivenessTransactionTest`, `QualityAndDecisionTest`, `IntegrityAndPlanarityTest`, `CaptureBindingTest`, `SessionScenarioTest`, `SessionEdgeCaseTest`, `ReportingTest`, `UiStateMapperTest`, `FrameHandOffTest`, `MjpegFrameReaderTest`, `MjpegEdgeCaseTest`, `PolicyResolverTest`, `LivenessPolicyTest`, `LivenessConfigKeysTest`, `AuditTest`, `UiContractTest`, `ImageBufferTest`, `DeviceTypesTest`, `ArchitectureTest`, `LivenessSessionDriverTest`, `ParityCheckTest` | `liveness/liveness-core/src/test/java/` |
| `ModelRegistryTest`, `ModelSignerTest`, `OnnxGoldenTest`, `OnnxLivenessEnginesTest`, `MiniFasCropTest`, `YuNetDetectorTest`, `HeadPoseEstimatorTest` | `liveness/liveness-onnx/src/test/java/` |
| `SFaceBioApiContractTest`, `MosipSdkLoaderTest`, `FaceIsoRecordTest` | `liveness/face-match-sdk/src/test/java/` |
| `LivenessGateTest`, `BioServiceLivenessGateTest`, `LivenessOrchestratorTest`, `FaceStreamSessionTest`, `LivenessConfigTest`, `DesktopAuditSinkTest`, `LivenessPacketEvidenceTest`, `LivenessTokenStoreTest`, `DesktopImageDecoderTest`, `SbiFaceCaptureDeviceResolveTest`, `LivenessModelImporterTest`, `LivenessOnMaxFailureTest`, `LivenessAccountLockTest`, `AuditFactoryTest`, `LivenessRunDriverTest`, `LivenessRunOverrideTest`, `LivenessParityTest` | `clients/desktop/registration/registration-services/src/test/java/` |
| `LivenessViewModelTest`, `LivenessBundleParityTest`, `LivenessTextsTest`, `FxLivenessListenerTest`, `DiagnosticTextTest`, `LivenessModelImportDialogTest`, `LivenessDialogSizingTest`, `SupervisorOverrideFlowTest` | `clients/desktop/registration/registration-client/src/test/java/` |
| `FaceAuthenticatorTest`, `GlobalParamConfigSourceTest`, `ArcAuditSinkTest`, `LivenessServiceTest`, `LivenessCaptureActivityTest`, `MessageResolverTest`, `AndroidImageDecoderTest`, `SbiCaptureCodecTest`, `FaceIsoRecordsTest`, `EngineFaceVerifierTest`, `FrameTimerTest`, `OnnxRuntimeSupplyChainTest`, `SupervisorOverrideTest`, `PhotoCaptureRunnerTest`, `MetricsReportTest`, `DiagnosticModeTrackerTest`, `AcceleratorCustomizerTest`, `EngineLoaderTest`, `ModelBundleImporterTest`, `DeviceProvisioningTest`, `FaceDeviceSelectionTest`, `SbiIntentDeviceTest`, `ReplayPromptFollowerTest`, `LivenessParityTest` | `clients/android/android/livenessmanager/src/test/java/` |
| `FaceCaptureLivenessGateTest`, `FaceAuthFlowTest`, `LivenessCaptureLauncherTest`, `LivenessApiTest`, `SupervisorOverrideFlowTest`, `BiometricsDetailsApiLivenessOffTest` | `clients/android/android/app/src/test/java/` |
| `RegistrationServiceImplTest` | `clients/android/android/clientmanager/src/test/java/` |
| `face_auth_panel_test.dart`, `liveness_outcome_banner_test.dart` | `clients/android/test/liveness/` |
| `LivenessParityDeviceTest`, `LivenessEngineReplayTest`, `LivenessPerformanceTest` (instrumented: they need an emulator or a phone) | `clients/android/android/livenessmanager/src/androidTest/java/` |

**Test counts**: per suite, with commands, in [test-report/README.md](test-report/README.md) section 2.

## Test IDs

Test IDs used in code comments, scenario titles and docs across the repository. Each family has a fixed meaning;
a range such as `T-CORE-SM-01` ... `T-CORE-SM-20` is one test class whose methods cover those cases.

Where it runs:
- **engine:** `mvn -f liveness/pom.xml test` (JDK 11 with JavaFX or JDK 21), module `liveness-core`, class named in the row.
- **scenario:** `tools/eval/scenario-runner` replays `eval/scenarios/<id>.yaml` through the real engine and models
  (headless; [eval/README.md](eval/README.md)). `*` stands for each subject that has the scenario (`s1`, `s2`, `s3`).
- **MockMDS:** `devices/mock-mds`, `mvn package -Dgpg.skip=true` on JDK 21; fault types in
  `devices/mock-mds/LIVENESS-EXTENSIONS.md`, set at run time with `POST /admin/fault`.
- **desktop:** `clients/desktop/registration/registration-services` unit tests (`mvn -pl registration-services test`).
- **MockSync:** `mvn -f tools/mock-sync/pom.xml verify` (JDK 11).

**Engine unit tests (`T-CORE-*`)**

| ID | What it checks | Where it runs |
|---|---|---|
| `T-CORE-ACT-01` ... `T-CORE-ACT-12` | Challenge action detectors on synthetic landmark sequences at 20 fps: each action detected, wrong direction, onset too fast, action not held | engine, `ChallengeObserverTest` |
| `T-CORE-ACT-07` | Head-turn direction uses the subject's own left and right (positive yaw is a turn to the subject's left) | engine, `ChallengeObserverTest` |
| `T-CORE-API-02` | The core has no `javafx`, `android`, `java.awt` or `java.net` dependency, so it runs unchanged on desktop and Android API 28 | engine, `ArchitectureTest` |
| `T-CORE-AUD-01` | Audit payloads of a whole session carry no image bytes, subject reference or raw score | engine, `ReportingTest` |
| `T-CORE-AUD-02` | Diagnostic log lines hold technical signals only (no images, embeddings, user or registration IDs) | engine, `ReportingTest` |
| `T-CORE-BND-01` ... `T-CORE-BND-03` | Stream-to-capture binding: pass token single use, expiry, bound to subject, workflow and device; similarity threshold; time window | engine, `CaptureBindingTest` |
| `T-CORE-CH-01` ... `T-CORE-CH-06` | Challenge drawing: no public API to choose a challenge (CH-01), no immediate repeats, BLINK excluded at low frame rates, uniform draws, a head turn when planarity is on, seed only from test code | engine, `ChallengeSchedulerTest`; CH-01 also `ArchitectureTest` |
| `T-CORE-DEC-01` ... `T-CORE-DEC-10` | Passive decision table just below, at and above each threshold, per policy and per workflow | engine, `QualityAndDecisionTest` |
| `T-CORE-DEC-08` | Authentication workflows default to passive threshold 95 (DEC-09: at or above it they pass passively) | engine, `QualityAndDecisionTest` |
| `T-CORE-INT-01` ... `T-CORE-INT-08` | Frame integrity: frozen frames, loops, timestamps going backwards, frame rate below minimum, several faces, face switch, corrupt JPEG, duplicate frames | engine, `IntegrityAndPlanarityTest` |
| `T-CORE-MET-01` ... `T-CORE-MET-05` | Metrics: counters and timers, average and completion time, retry and failure rate | engine, `ReportingTest` |
| `T-CORE-MET-02` | No identifiers (user, registration, device serial, session) in metric names or tags | engine, `ReportingTest` |
| `T-CORE-MJ-01` ... `T-CORE-MJ-10` | MJPEG stream reader on synthetic, truncated and malformed streams | engine, `MjpegFrameReaderTest` |
| `T-CORE-MJ-01` | At end of stream the reader returns instead of spinning like the stock `Streamer` loop | engine, `MjpegFrameReaderTest` |
| `T-CORE-MJ-06` | A read timeout (for example the 2 s socket timeout) is reported as a stall | engine, `MjpegFrameReaderTest` |
| `T-CORE-MJ-07` | Bytes arrive but no valid frame within 3 s | engine, `MjpegFrameReaderTest` |
| `T-CORE-MJ-10` | A reset connection | engine, `MjpegFrameReaderTest` |
| `T-CORE-PLN-01` ... `T-CORE-PLN-04` | Planarity check: synthetic 3-D versus flat landmark sets | engine, `IntegrityAndPlanarityTest` |
| `T-CORE-POL-01` ... `T-CORE-POL-08` | Configuration keys to per-workflow policy | engine, `PolicyResolverTest` |
| `T-CORE-POL-01` | Master switch; reads are null-safe | engine, `PolicyResolverTest` |
| `T-CORE-POL-02` | Passive threshold, its floor and per-workflow overrides | engine, `PolicyResolverTest` |
| `T-CORE-POL-03` | Policy per workflow (POL-08: the active switch) | engine, `PolicyResolverTest` |
| `T-CORE-POL-04` | Challenge pool from `active.challenge_types` | engine, `PolicyResolverTest` |
| `T-CORE-POL-05` | Counts, timeouts and retries kept within their ranges (POL-05 to POL-07) | engine, `PolicyResolverTest` |
| `T-CORE-Q-01` ... `T-CORE-Q-03` | Quality gate: no face, poor quality, off-pose give guidance keys | engine, `QualityAndDecisionTest` |
| `T-CORE-SM-01` ... `T-CORE-SM-20` | Session state transitions driven by a fake clock, device events, cancel and engine errors | engine, `SessionEdgeCaseTest` |
| `T-CORE-TX-01` | Only counted failures use an attempt | engine, `ReportingTest` |
| `T-CORE-TX-02` | `<workflow>.on_max_failure` decides what happens after the last attempt | engine, `ReportingTest`; key parsing in `PolicyResolverTest` |
| `T-CORE-TX-03` | A PAD, integrity or binding failure marks the whole transaction as a suspected attack, so a later timeout or failed challenge on the last attempt cannot unlock the supervisor override | engine, `LivenessTransactionTest` |
| `T-CORE-UI-01` ... `T-CORE-UI-10` | Session states and results to message keys; every suspected attack maps to the one generic message | engine, `UiStateMapperTest` |
| `T-CORE-UI-09` | A synced `guidance.<messageKey>.<lang>` key replaces the bundle text for one language | engine, `UiStateMapperTest` |

**Liveness scenarios (`T-SCN-L-*`)**

| ID | What it checks | Where it runs |
|---|---|---|
| `T-SCN-L-01` | Genuine user in good light passes with the shipped policy | scenario `bf-*-registration`, `bf-*-login` |
| `T-SCN-L-02` | Genuine user in a dim room: challenges, then pass | scenario `bf-s3-dim` |
| `T-SCN-L-03` | Blink challenge passed by a genuine user | scenario `bf-s1-active`, `bf-s3-active` |
| `T-SCN-L-04` | Smile challenge passed by a genuine user | scenario `bf-s1-active`, `bf-s3-active` |
| `T-SCN-L-05` | Turn-left and turn-right challenges passed by a genuine user | scenario `bf-s2-active` |
| `T-SCN-L-06` | Look-up challenge | engine `ChallengeObserverTest` only; no committed frame pack has a look-up clip, although the `bf-*-active` titles name the range L-03..06 |
| `T-SCN-L-07` | User performs another action than the one prompted: refused | scenario `other-s1-wrong-action` |
| `T-SCN-L-08` | User ignores the prompt: challenge timeout | scenario `other-s3-ignores-prompt` |
| `T-SCN-L-09` | Empty scene, no face | scenario `other-no-face` |
| `T-SCN-L-10` | Two faces in view | scenario `other-two-faces` |
| `T-SCN-L-11` | Blurred or very dark frames give guidance, not a pass | scenario `other-blurred`, `other-too-dark` |
| `T-SCN-L-12` | Failed attempt, then success on retry | no committed scenario; retry counting is `T-CORE-TX-01` |
| `T-SCN-L-13` | Maximum retries reached | scenario `other-s3-ignores-prompt` |

**Attack scenarios (`T-SCN-P-*`)** ([eval/protocol.md](eval/protocol.md) section 12 maps them to the volunteer attack species)

| ID | What it checks | Where it runs |
|---|---|---|
| `T-SCN-P-01` | Printed photo held by hand or moved | scenario `atk-*-print-still`, `atk-*-print-still-login`, `atk-*-print-moved` |
| `T-SCN-P-02` | Curved or tilted print used to fake head turns | scenario `atk-s2-print-tilted` |
| `T-SCN-P-03` | Print with the eyes cut out | none (needs a physical artefact and a live attacker; volunteer species P3) |
| `T-SCN-P-04` | Photo shown on a phone screen | scenario `atk-*-screen-photo` |
| `T-SCN-P-05` | Photo shown on a laptop screen | scenario `atk-*-screen-photo` |
| `T-SCN-P-06` | Recorded video replayed on a screen | scenario `atk-*-screen-replay`, `atk-s3-screen-replay-login` |
| `T-SCN-P-07` | Adaptive replay: a clip chosen to match each prompt | scenario `atk-*-adaptive-screen`, `atk-*-adaptive-wrong-clip` |
| `T-SCN-P-08` | One frozen image sent as every frame | scenario `atk-*-injected-still`, `atk-m-mockmds-stock` |
| `T-SCN-P-09` | Short genuine clip looped | scenario `atk-*-looped-clip` |
| `T-SCN-P-10` | Face swapped between the liveness stream and the stored capture | engine `CaptureBindingTest` (`T-CORE-BND-01` ... `T-CORE-BND-03`); no frame pack |

**Mock device faults (`T-MOCK-*`)**: the fault is produced by MockMDS and checked in `FaceFaultInjectorTest`
(MockMDS); the client reaction is checked where the row says.

| ID | What it checks | Where it runs |
|---|---|---|
| `T-MOCK-01` | Device status Not Ready (`NOT_READY`): the client says the camera is not available and uses no attempt | MockMDS `FaceFaultInjectorTest`; client reaction not covered by an automated test |
| `T-MOCK-02` | TCP reset of the stream (`RESET`) | MockMDS; engine `MjpegFrameReaderTest` (`T-CORE-MJ-10`) |
| `T-MOCK-03` | Camera cannot be opened (`UNAVAILABLE`) | MockMDS; desktop `FaceStreamSessionTest` (refused stream) |
| `T-MOCK-07` | Corrupt, truncated or non-JPEG parts and a wrong `Content-Length` are dropped | MockMDS; engine `MjpegFrameReaderTest`; desktop `FaceStreamSessionTest` |
| `T-MOCK-08` | Stream stays open with no data (`STALL`): device error after the 2 s socket timeout | MockMDS; engine `MjpegFrameReaderTest` (`T-CORE-MJ-06`); desktop `FaceStreamSessionTest` |
| `T-MOCK-09` | Clean close in the middle of a session (`DISCONNECT`): one reconnect, reader thread ends | MockMDS; desktop `FaceStreamSessionTest`; desktop end-to-end flow `tools/mock-sync/e2e/flows/20-registration-passive.flow` |
| `T-MOCK-10` | Frame rate below the minimum (`SLOW_FPS`) | MockMDS; engine `IntegrityAndPlanarityTest` (`T-CORE-INT-01` ... `T-CORE-INT-08`) |
| `T-MOCK-11` | Frozen stream (`FREEZE`, or the stock `STATIC` source) is reported as frozen | MockMDS; engine `IntegrityAndPlanarityTest`; scenario `atk-m-mockmds-stock` |

**MockSync (`T-SYNC-*`)**

| ID | What it checks | Where it runs |
|---|---|---|
| `T-SYNC-01` | Responses encrypted by MockSync decrypt with the algorithms each client type uses | MockSync, `ClientCryptoTest` |
| `T-SYNC-02` | MockSync signatures verify with keymanager `SignatureServiceImpl.jwtVerify` | MockSync, `ResponseSignatureTest` |
| `T-SYNC-03` | The real desktop client's first login and initial sync against MockSync | `tools/mock-sync/e2e/run-desktop-e2e.sh` with a running MockSync (starts the desktop client; not part of `mvn verify`) |
| `T-SYNC-04` | A simulated Android tablet registers, logs in and runs the seven sync steps | MockSync, `AndroidClientContractTest` |
| `T-SYNC-06` | `actuator/info` gives the server version and build fields the Android client uses to pick the sync APIs | MockSync, `AndroidClientContractTest` |

**Performance (`T-PERF-*`)**

| ID | What it checks | Where it runs |
|---|---|---|
| `T-PERF-01` | Per-frame time including JPEG decode at 640x480; target p95 at most 20 ms | scenario runner latency figures ([eval/calibration-notes.md](eval/calibration-notes.md)); Android: [test-report/android-e2e.md](test-report/android-e2e.md) section 5. Target not met |
| `T-PERF-02` | Stream time to the passive decision; target p95 at most 2.5 s | scenario runner ([eval/calibration-notes.md](eval/calibration-notes.md)) |

**Model import (`T-MI-*`)**

| ID | What it checks | Where it runs |
|---|---|---|
| `T-MI-06` | Online model upgrade through a MockSync upgrade endpoint | unit tests only: MockSync serves a signed bundle at `GET /v1/syncdata/liveness/model-bundle` (`SyncEndpointsTest`), and both clients download and import it with the offline checks (`ModelBundleUpdateTest`, desktop `LivenessModelImporterTest`, Android `ModelBundleImporterTest`, each against a local HTTP server). No client run against MockSync yet; a client release through MockSync is still not possible ([design/online-offline.md](design/online-offline.md) section 3) |

## 1. Overview and hybrid flow

| ID | PS04 requirement | Desktop | Android | Evidence | Status | Notes |
|---|---|---|---|---|---|---|
| O1 | Process face image streams from L0/L1 devices in the desktop and Android clients | `RS/service/bio/liveness/SbiFaceCaptureDevice`, `FaceStreamSession` (SBI MJPEG stream) | `LM/device/BuiltInCameraL0Device`, `CameraXFrameSource`, `ReplayFrameSource` | DE-D1..D13, AE-A1..A16 | partial | Hardware and integration gap (no vendor Android face app to test with). Desktop: SBI L0 and L1 devices (DE-D9). Android: the built-in camera as an L0 device only; external SBI face apps give no preview stream (`LM/device/SbiIntentDevice` is wired through `LM/device/FaceDeviceSelection` and `APP/api_services/BiometricsDetailsApi` when `mosip.registration.liveness.android.device=SBI`, covered by Robolectric tests only and never run with a vendor app; Android SBI 0.9.5 intents carry no preview frames, so `no_stream_policy` decides what happens), and ARC's `Biometrics095Service.isDeviceValid` still accepts L0 only. |
| O2 | Applies to resident face capture, operator authentication, supervisor authentication | 6 face screens (see sections 2-4) | resident, onboarding, sign-in, packet auth, supervisor approval | DE-D1, D3, D5, D7; AE-A2, A7, A9, A11 | done | |
| O3 | Passive liveness first, no user action, quality/confidence score | `core/engine/PassivePadAggregator`, `onnx/pad/MiniFasPassivePad` | same engine | `QualityAndDecisionTest`, `SessionScenarioTest`; DE-D3 (score 84, no challenge); AE-A2 | done | Score 0-100; only buckets (for example `80-89`) reach audit and packet. |
| O4 | Compare the passive result with a configurable threshold | `core/engine/DecisionPolicy`, key `passive.threshold` (80 resident, 95 authentication) | same | `QualityAndDecisionTest`, `PolicyResolverTest` | done | |
| O5 | Threshold met and no attack: proceed | `DecisionPolicy` → `CAPTURE_READY` | same | DE-D3, AE-A2, AE-A9 | done | |
| O6 | Threshold not met: start active verification automatically | `DecisionPolicy` (`PASSIVE_THEN_ACTIVE`) | same | `SessionScenarioTest`; DE-D4 (score 74 → 2 challenges); AE-A7 | done | Shipped defaults: `ALWAYS_ACTIVE` (challenges whatever the score, after the passive stage) for every workflow except `exception_photo` (`OFF`): `registration`, `login_auth`, `onboard_auth` since 2026-09-30 (CN0, P1), and `packet_auth`, `exception_auth`, `eod_auth` since 2026-10-04 (`LivenessConfigKeys.defaultPolicyMode`, MockSync `mock-sync-defaults.properties`). Measured on 2026-10-14 with the current engine and every workflow at `PASSIVE_THEN_ACTIVE` (suite `passive-first`): 100 of 226 attack transactions accepted, against 0 of 226 at the shipped defaults, BPCER 0.0% in both (`eval/results/20261014-shipped/README.md`); so the default stays `ALWAYS_ACTIVE`. Under the earlier passive-first configuration the `legacy` suite of 2026-10-05 accepted attacks on the supervisor workflows (pooled APCER `packet_auth` 71.4%, `eod_auth` and `exception_auth` 50.0%). One synced key per workflow switches back; the literal passive-first flow (`PASSIVE_THEN_ACTIVE`) is shown by desktop flow 60 and the `legacy` suite. |
| O7 | Prompt dynamically selected facial actions | `core/engine/ChallengeScheduler` (secure random draw) | same | `ChallengeSchedulerTest.drawsAreUniformOverTheAllowedSequences`, `noImmediateRepeats` | done | |
| O8 | Analyse the following frames to decide whether the action was done by a live person | `core/engine/ChallengeObserver`, `ActiveStage` (active PAD, integrity, planarity during the challenge) | same | `ChallengeObserverTest`, `ActivePhaseTest`; `scn:atk-*-adaptive-wrong-clip` (36/36 refused) | done | |
| O9 | Proceed only when liveness and PAD criteria are met | `RS/service/bio/liveness/LivenessGate` (capture and authentication gates) | `APP/liveness/FaceCaptureLivenessGate`, `LM/auth/FaceAuthenticator` | `LivenessGateTest`, `BioServiceLivenessGateTest`, `FaceCaptureLivenessGateTest`, `FaceAuthenticatorTest` | done | |
| O10 | The user does not select the challenge | `ChallengeScheduler` has no selection API | same | `ChallengeSchedulerTest.noPublicSelectionApi` | done | |
| O11 | Challenge sequence dynamic and unpredictable | `ChallengeScheduler` with `core/SecureRandomSource`, random delays | same | `ChallengeSchedulerTest` | done | The end-to-end demos narrowed the pool to `BLINK,SMILE` from MockSync while the head-turn defect was open (O13, now fixed). |
| O12 | Challenges: blink, smile | `core/ChallengeType` BLINK, SMILE | same | DE-D4 (`SMILE:OK,BLINK:OK`); AE-A7 | done | On the loaded emulator BLINK was usually not eligible (needs 10 analysed frames per second), so Android runs drew SMILE twice. |
| O13 | Challenges: turn head left/right | TURN_LEFT, TURN_RIGHT | same | `ChallengeObserverTest` (turn with a yaw-inflated mouth ratio passes; without the yaw limit it fails as a smile); `scn:bf-*-active`; CN0 | done | Fixed on 2026-09-30 (engine change E1, `active.smile_max_yaw_deg` 15): genuine TURN_LEFT completes 15/15 and TURN_RIGHT 15/15 in the suite (was 3/36). Not re-run on the clients end to end at the current defaults (the four-action pool is the default; no desktop run through the screens and no Android scripted demo at these defaults yet). |
| O14 | Challenges: look in a specified direction; other movements | LOOK_UP, LOOK_DOWN, GAZE_LEFT, GAZE_RIGHT | same | `ChallengeObserverTest` | partial | Implemented and unit-tested on synthetic landmarks; not exercised by the frame packs or the end-to-end runs. No LOOK_UP footage of any subject exists, so scripted demos cannot answer a LOOK_UP prompt. Not in the default pool since 2026-10-14; configurable through `active.challenge_types`. |
| O15 | Challenge set depends on the technology's capabilities | `ChallengeScheduler` filters by `FaceAnalyzer.features()`, face size and frame rate | same | `ChallengeSchedulerTest.poolIsFilteredByFeaturesFaceSizeAndEngine` | done | |
| O16 | PAD against printed photographs | passive PAD + challenges + planarity | same | `scn:atk-*-print-*`; CN0; DE-D6, DE-D8; AE-A10, AE-A12; `test-report/android-e2e/print-attack-trials.txt` | done | Shipped defaults (`ALWAYS_ACTIVE` for registration, sign-in and onboarding): 0 accepted of 84 print attempts (SYN-A2 0/48, A3 0/24, A4 0/12). Before 2026-09-30 the passive-first registration policy accepted SYN-A3 8/8. Run of 2026-10-05 (`eval/results/20261005-shipped/README.md`): SYN-A2 0.0% (0/8) and SYN-A3 0.0% (0/8) per transaction and on the first attempt in every workflow that has them, the three supervisor workflows included. Prints are synthetic; real prints wait for the volunteer evaluation (E6). |
| O17 | PAD against digital images on screens | passive PAD | same | `scn:atk-s2-screen-photo`, `atk-s3-screen-photo` | done | SYN-A5 APCER 0% (0/24) in every suite. |
| O18 | PAD against video replay | passive PAD, challenges, loop detection | same | `scn:atk-*-screen-replay*`, `atk-*-looped-clip`, `atk-*-adaptive-screen`; CN0; DE-D7; AE-A4, A6, A11 | partial | Real video replay (P6, 8 transactions, 2026-10-24/25, [results](eval/results/real-faces-2026-10-24.md)): 0 accepted, all 8 ended at the capture-quality gate with no decision. Run of 2026-10-05 (`20261005-shipped`): SYN-A6 screen replay 0.0% (0/8) in each workflow that has it; SYN-A8 looped clip 0.0% (0/8 `eod_auth` and `exception_auth`, 0/32 `packet_auth` and `registration`; signed loop window 300 frames, bundle 1.3.0). The adaptive replay SYN-A7 (a clip that answers each prompt) is accepted in `registration`: 50.0% (4/8) per transaction and on the first attempt (`atk-s3-adaptive-screen` 4 of 4 seeds, `atk-s2-adaptive-screen` refused 4 of 4; source artefacts accepted 1 of 2); it was 4 of 16 attempts on 2026-09-30. Challenge-response cannot tell a clip of the real person answering the prompt from the person, and the planarity check (a flat-versus-3-D test on head turns) has no attack evidence and cannot stop a screen that replays a real head turn. Evening run of 2026-10-05 (`20261005b-shipped`), after the screen-pattern check in the passive PAD (`ScreenPatternMeter`, unit test `ScreenPatternMeterTest`): SYN-A7 0.0% (0/8), every bona fide score unchanged; proven on synthetic screens; the real video replays of 2026-10-24/25 ended at the quality gate before this check, so real screens still need the volunteer test. |
| O19 | Other attack techniques | frame integrity (frozen frames, loops, timestamp regression), capture binding | same | `IntegrityAndPlanarityTest`, `CaptureBindingTest`; `scn:atk-*-injected-still` (SYN-A1 0%); DE-D6 (injected still, `FROZEN`) | partial | Injected still streams are refused. Masks, deepfakes and injection of new video were not tested. |
| O20 | Never accept a capture or complete authentication when liveness/PAD fails | `LivenessGate.admit/complete` in `BioServiceImpl.captureModality`; `AuthenticationServiceImpl.authValidator` | `FaceCaptureLivenessGate` (only a PASS is stored); `FaceAuthenticator` (match only after PASS) | `BioServiceLivenessGateTest`, `LivenessGateTest`, `FaceCaptureLivenessGateTest`, `FaceAuthFlowTest`; DE-D6, AE-A10 | done | A resident capture labelled with a workflow whose policy is OFF passes the desktop gate without evidence (design doc `desktop-liveness-services.md` section 9). |

## 2. Resident registration workflow (steps 1-10)

| ID | PS04 step | Desktop | Android | Evidence | Status | Notes |
|---|---|---|---|---|---|---|
| R1 | Start face capture | `RC/controller/device/GenericBiometricsController.scan` → `BaseController.startFaceLiveness` opens `RC/controller/liveness/LivenessDialog` | `APP/api_services/BiometricsDetailsApi` → `APP/liveness/FaceCaptureCoordinator` → `APP/liveness/LivenessCaptureLauncher` → `LM/ui/LivenessCaptureActivity` | DE-D3, AE-A7 | done | |
| R2 | Receive frames from the L0/L1 device | `FaceStreamSession` + `core/stream/MjpegFrameReader` | `BuiltInCameraL0Device` (CameraX or debug replay) | `FaceStreamSessionTest`, `MjpegFrameReaderTest` | done | Android CameraX path not run (automated runs must not use the webcam); the replay path shares everything after the frame source. |
| R3 | Passive liveness and PAD | engine | engine | O3 | done | |
| R4 | Evaluate quality/confidence | `core/engine/QualityGate`, `DecisionPolicy` | same | `QualityAndDecisionTest` | done | |
| R5 | Threshold met: accept | signed capture after the pass, bound to the session (`core/engine/CaptureBinder`) | same | DE-D3; AE-A13 | done | |
| R6 | Threshold not met: start an active challenge | engine | engine | DE-D4, AE-A7 | done | |
| R7 | Prompt the resident with the selected action | `LivenessPanel` prompt, icon, "Step i of k" | `LivenessCaptureActivity` | `docs/ux/screenshots/desktop/e2e-03-low-confidence-challenge.png`, `android/e2e-06-low-confidence-challenge.png` | done | |
| R8 | Analyse the following frames | O8 | O8 | O8 | done | |
| R9 | Accept only after successful verification | O20 | O20 | O20 | done | |
| R10 | Retry within the configured retry policy | `core/engine/LivenessTransaction`, `RS/.../LivenessOrchestrator`, `RS/.../AttemptHistory`, `core/run/LivenessSessionDriver` | `LivenessRunner` (on the same driver) with `priorAttempts` across screens, `APP/liveness/FaceCaptureLivenessGate` | `LivenessOrchestratorTest`, `FaceCaptureLivenessGateTest`; DE-D6, AE-A6, AE-A10 | done | Reopening the window does not reset attempts on either client. Desktop `AttemptHistory` keeps counted failures, the suspected-attack mark and the uncounted low-light count per workflow and person until the block time ends; a pass clears them. The Android gate keeps max(stored, attempts used) per workflow and user. |

## 3. Operator and supervisor authentication (overview sections)

| ID | PS04 requirement | Desktop | Android | Evidence | Status | Notes |
|---|---|---|---|---|---|---|
| OP1 | Receive the operator's face stream | `LoginController`, `AuthenticationController`, `BiometricsController` (onboarding) via `BaseController.authenticateFace` | `lib/ui/login_page.dart` ("Sign in with face"), `lib/ui/process_ui/generic_process.dart` ("Authenticate with face"), `lib/ui/liveness/face_auth_panel.dart` → `APP/liveness/FaceAuthFlow` | DE-D1, DE-D3, DE-I1; AE-A2, AE-A9 | done | Upstream Android has no face sign-in; this project adds it. |
| OP2 | Passive liveness and PAD | engine, workflows `login_auth`, `packet_auth`, `onboard_auth` | same workflows | DE-D1; AE-A2 | done | |
| OP3 | Active verification when required | authentication threshold 95 | same | DE-D10 (replay had to answer a blink); AE-A3, AE-A4 | done | |
| OP4 | Prompt the operator for the action | `LivenessPanel` | `LivenessCaptureActivity` | AE-A3 | done | |
| OP5 | Validate the action from the stream | engine | engine | AE-A3 (two challenges answered) | done | |
| OP6 | Complete authentication only when criteria are met | `AuthenticationServiceImpl.authValidator` → `LivenessGate.rejectsAuthCapture`; `SessionContext.validateFace` | `FaceAuthenticator`: liveness, then 1:1 SFace match | `LivenessGateTest`, `FaceAuthenticatorTest`; AE-A4 | done | |
| OP7 | Face verification: the matched face must come from a live person | match only after PASS; the capture is bound to the liveness session (similarity check) | `LM/match/EngineFaceVerifier` after PASS | `CaptureBindingTest`; DE-D11, AE-A3 (live wrong person refused, SFace score 11 / 13 against threshold 40) | done | SFace threshold 40 is not calibrated on a large set. |
| SV1 | Same mechanism for supervisor authentication | `EODAuthenticationController` (`eod_auth`), reviewer step in `AuthenticationController` (`exception_auth`) | `lib/ui/approve_packet/widget/authenticate_dialogbox.dart` (`eod_auth`) | DE-D5, DE-D7; AE-A11 | done | |
| SV2 | Supervisor physically present, not a presentation attack | same engine and threshold 95; Android also checks the REGISTRATION_SUPERVISOR role | same | DE-D7 and AE-A11 (screen replay refused, then live face passed) | done | Desktop supervisor override (resident only) needs a second person: the operator enters another supervisor's user ID, who must hold REGISTRATION_SUPERVISOR and pass face authentication (with liveness under `exception_auth`); the grant is valid 60 s from that match (`LivenessRun.OVERRIDE_VALIDITY_MS`), the photo taken after it must show exactly one face with a passive PAD score not below `pad.reject_threshold` (`OverridePhotoCheck`), and every grant or refusal writes one `REG-EVT-LV16` with decision, reason, operator, supervisor, face count and score bucket only. Tests: `LivenessRunOverrideTest`, desktop `SupervisorOverrideFlowTest`; not run through the screens. |
| SV3 | Active challenge when passive confidence is insufficient | threshold 95 | same | DE-D10, AE-A11 | done | |

## 4. Configuration, online and offline (overview sections)

| ID | PS04 requirement | Desktop | Android | Evidence | Status | Notes |
|---|---|---|---|---|---|---|
| C1 | Configurable at Registration Client level | `RS/service/bio/liveness/LivenessConfig` over `ApplicationContext.map()` (MOSIP global params) | `LM/config/GlobalParamConfigSource` over `GlobalParamRepository` | `LivenessConfigTest`, `GlobalParamConfigSourceTest` | done | Same key names on both clients: `mosip.registration.liveness.*` (`core/config/LivenessConfigKeys`, 45 defaults, 43 of them under that prefix). |
| C2-C10 | Enable/disable, passive threshold, active enablement, minimum challenges, challenge types, timeout, max retries, per-workflow policies, behaviour on repeated failure | see mandatory task 8 below | | | done | |
| C11 | Configuration applies online and offline | values cached in the client database after sync | same | DE-D10, AE-A13 | done | |
| OF1 | Liveness works online and offline | engine and models run locally; no network code in the core (ArchUnit rule) | same | `ArchitectureTest`; DE-D10; AE-A13 | done | Offline was simulated: desktop by routing the client's HTTPS calls to a closed port, Android by removing `adb reverse` plus airplane mode. MockSync logged no request in both windows. |
| OF2 | Core liveness, PAD, challenge generation and action validation available locally | as above | as above | as above | done | |
| OF3 | Same behaviour in online registration, offline registration, operator and supervisor authentication | same engine and keys | same | DE-D10, AE-A13 | done | Known differences: see UX8. |
| OF4 | Configuration and model updates synchronised securely when online | config: MOSIP sync (signed responses); models: signed bundle inside the client release, offline import, or "Check for model update" in the import window, which downloads the bundle from the synced `mosip.registration.liveness.model.update_url` | config: MOSIP sync; models: APK assets checked against the signed manifest at build time, offline import, or "Check for model update" in Device Settings (same download) | DE-D8, AE-A12 (policy change arrives by sync, audit LV15); `ModelRegistryTest`; `ModelBundleUpdateTest`, `LivenessModelImporterTest`, `ModelBundleImporterTest` (Android), `SyncEndpointsTest` | done | Config: done. Models: signature, hashes and version are checked at every load. Both clients import a newer signed bundle offline (B6) or download it from the synced URL (since 2026-10-14). The download is only a new way to get the `.zip`: the shared `ModelBundleImporter.updateFromServer` passes it to the offline importer, so a tampered, unsigned or older bundle is refused and the running models stay. HTTPS is required except to a loopback host; no redirects, 30 s timeouts, 10 minute limit, 512 MB cap. The key is empty by default (feature off). A supervisor starts the download; the sync job does not. Unit tests only: no client run against MockSync and no phone run. |

## 5. Recommended stack and integration

| ID | PS04 item | Where | Evidence | Status | Notes |
|---|---|---|---|---|---|
| TS1 | Desktop: Java, existing device framework, vendor SDK/API, camera/device abstraction | Java 11 engine; SBI 0.9.5 (tested with MockMDS) and 1.0 (implemented, untested) providers extended (`RS/mdm/...`); `core/device/FaceCaptureDevice` | DE-D1..D13 | done | |
| TS2 | Android: Java/Flutter, camera APIs, vendor SDK/API | `LM/` (Java), CameraX, Flutter widgets in `lib/ui/liveness/` | AE-A1..A16 | partial | Hardware and integration gap (no vendor Android face app to test with). CameraX not run with a real camera; the vendor SBI path (`SbiIntentDevice`) is wired but never run with a vendor app (O1). |
| TS3 | Common face capture device interface | `core/device/FaceCaptureDevice`, `FaceFrameSource` | `DeviceTypesTest` | done | |
| TS4 | Device adapter pattern for multiple vendors | `SbiFaceCaptureDevice` (desktop SBI), `BuiltInCameraL0Device`, `SbiIntentDevice` (Android) | `SbiFaceCaptureDeviceResolveTest`, `SbiCaptureCodecTest`, `SbiIntentDeviceTest`, `FaceDeviceSelectionTest` | partial | Hardware gap. Only MockMDS was used as a vendor device; no second vendor tested. The SBI 1.0 provider on the desktop is upstream code that this project did not test against a face device: implemented, untested. |
| TS5 | Configurable liveness quality validation | `quality.*` keys, `QualityGate` | `QualityAndDecisionTest` | done | |
| TS6 | Existing configuration framework | MOSIP global params on both clients | C1 | done | |
| TS7 | Independent of any vendor | engine plug-in interface `core/api/LivenessEngineProvider` (Java `ServiceLoader`; Android `LM/engine/EngineLoader` loads a vendor engine named by `mosip.registration.liveness.engine` the same way, unit-tested only); SFace SDK loaded through MOSIP's `IBioApiV2` | `OnnxLivenessEngineProviderTest`, `MosipSdkLoaderTest` | done | |

## 6. Expected tasks

| ID | Expected task | Where | Evidence | Status | Notes |
|---|---|---|---|---|---|
| E1 | Analyse the existing device integration | `docs/design/architecture.md` section 3, `docs/design/device-interface.md`, `docs/design/desktop-liveness-services.md` section 8 (upstream edits), `docs/design/android-liveness.md` section 11 | review | partial | The analysis of the upstream code is spread over the design pages; there is no separate as-is document. |
| E2 | Analyse the desktop and Android face capture workflows | `docs/design/integration-flows.md`, `docs/design/sequences.md`, `docs/ux/flows.md` | review | partial | As E1. |
| E3 | Define the face frame streaming interface | `core/device/FaceFrameSource`, `FrameSink`, `FaceFrame`; `core/stream/MjpegFrameReader`, `FrameHandOff` (Javadoc) | `MjpegFrameReaderTest`, `FrameHandOffTest` | done | |
| E4 | Define the liveness/PAD processing interface | `core/api/LivenessEngine`, `LivenessSession`, `core/vision/PassivePad`, `FaceAnalyzer` (Javadoc); `liveness/README.md` | `ApiTypesTest`, `UiContractTest` | done | |
| E5 | Evaluate liveness and PAD technologies | [design/technology-evaluation.md](design/technology-evaluation.md) (candidates, criteria, choice, sources); `models/SOURCES.md` (model licences) | review | done | Passive CNN models, landmark challenges, rPPG, depth/IR hardware, geometric checks, commercial SDKs and cloud APIs compared on offline use, licence, size, CPU, vendor neutrality and accuracy evidence. |
| E6 | Evaluate against ISO/IEC 30107 | `docs/eval/protocol.md`, `tools/eval/`, `docs/eval/calibration-notes.md` | 3 suites x 131 transactions | partial | Automated technology regression with ISO/IEC 30107-3 metrics is done. A team-run evaluation with real faces and real artefacts ran on 2026-10-24/25 ([results](eval/results/real-faces-2026-10-24.md)): genuine 8 of 8 phone-camera transactions accepted; 0 of 32 attack transactions accepted (P1 print, P4 phone photo, P6 video replay); P4 APCER 0/4, 28 transactions ended at the quality gate with no decision (APNRR). The volunteer evaluation of the [protocol](eval/protocol.md) (about 12 subjects) follows. |
| E7-E19 | Implement passive, PAD, active, dynamic selection, action detection, thresholds, resident, operator, supervisor, online/offline, retry, UI/UX, configuration | mandatory tasks 1-10 below | | see rows | |
| E20 | Audit and diagnostic logging | `core/audit/*`, `RS/.../DesktopAuditSink`, `DesktopDiagnosticSink`; `LM/audit/ArcAuditSink`, `LogcatSinks`; events `REG-EVT-LV01..17` on both clients | `AuditTest`, `DesktopAuditSinkTest`, `ArcAuditSinkTest`; DE-D12, AE-A14 | done | No image, embedding or raw score in any audit row. |
| E21 | Performance and interoperability testing | latency: `docs/eval/calibration-notes.md` sections 0.3 and 5, `test-report/android-e2e.md` section 5 | see [test-report/README.md](test-report/README.md) section 5 | partial | The per-frame target (p95 at most 20 ms) is not met: desktop JVM p95 127.7 ms on 2026-10-20 (shared laptop at 51-69% load, one ONNX Runtime thread; [eval/results/20261020-latency/README.md](eval/results/20261020-latency/README.md)), 38-45 ms in earlier runs on a quieter machine; the emulator is slower. No idle-machine or physical-tablet measurement (hardware). Interoperability: the desktop/Android parity test is done (B4). |

## 7. Mandatory tasks

### 7.1 Task 1: L0/L1 device integration

| ID | Bullet | Desktop | Android | Evidence | Status | Notes |
|---|---|---|---|---|---|---|
| 1.1 | Integrate with L0/L1 devices | SBI devices through MOSIP's device framework; L1 digital ID checked against the FTM root | built-in camera as L0 | DE-D9 (LV01 `"certification":"L1"`); AE-A15 (`LIVENESS_DEVICE_LEVEL=L0`) | partial | Hardware and integration gap (no vendor Android face app to test with). Android has no L1 path (O1). L0 and L1 are tested with MockMDS only (SBI 0.9.5); SBI 1.0 is implemented, untested. |
| 1.2 | Receive face frame streams | `FaceStreamSession` (SBI STREAM, MJPEG) | `CameraXFrameSource`, `ReplayFrameSource` | `FaceStreamSessionTest`; DE-D3 | partial | Hardware gap. Android CameraX not run (R2). |
| 1.3 | Continuous or periodic frame processing | `FrameHandOff.latestOnly()`, key `frame.sample_interval_ms` | CameraX `KEEP_ONLY_LATEST`; PAD every n-th frame during challenges | `FrameHandOffTest`, `FrameTimerTest` | done | |
| 1.4 | Handle device frame formats | `RS/.../DesktopImageDecoder` (JPEG, ISO 19794-5 JPEG 2000), `core/image/PixelFormat` | `LM/image/AndroidImageDecoder` (RGBA_8888, JPEG), `PixelOrientation` | `ImageBufferTest`, `DesktopImageDecoderTest`, `AndroidImageDecoderTest`, `PixelOrientationTest` | done | |
| 1.5 | Handle device communication failures | read timeouts (2 s), `DeviceErrorCode` (`STALLED`, `FIRST_FRAME_TIMEOUT`, `INVALID_STREAM`) | camera errors mapped to `DeviceErrorCode` | `MjpegEdgeCaseTest`, `FaceStreamSessionTest`; DE-D2 | done | |
| 1.6 | Handle connection and disconnection | disconnect → message, no attempt used, Retry reopens the stream; waits for a running device scan | camera unavailable → message and Retry | DE-D2; AE-A5; `SbiFaceCaptureDeviceResolveTest` | done | A refused stream (MockMDS `UNAVAILABLE`) shows "The face camera is not available..." on desktop (picture `ux/screenshots/desktop/d-s8-device-unavailable.png`, 2026-10-22); a disconnect during capture shows the "disconnected" text. |
| 1.7 | Common device abstraction | `core/device/FaceCaptureDevice` implemented by `SbiFaceCaptureDevice` | implemented by `BuiltInCameraL0Device`, `SbiIntentDevice` | `DeviceTypesTest` | done | |

### 7.2 Task 2: Passive liveness

| ID | Bullet | Where (both clients use the shared engine) | Evidence | Status | Notes |
|---|---|---|---|---|---|
| 2.1 | Analyse incoming frames | `onnx/OnnxFaceAnalyzer` (YuNet, Face Mesh), `onnx/pad/MiniFasPassivePad` (MiniFASNet V2 + V1SE) | `OnnxGoldenTest`, `YuNetDetectorTest`, `MiniFasCropTest` | done | |
| 2.2 | Generate quality/confidence | `PassivePadAggregator` score 0-100; `QualityGate` | `QualityAndDecisionTest` | done | |
| 2.3 | Compare with the configured threshold | `DecisionPolicy`, `passive.threshold` | `QualityAndDecisionTest`, `PolicyResolverTest`; DE-D8 | done | |
| 2.4 | Decide whether active is required | `DecisionPolicy` | DE-D3 vs DE-D4; AE-A2 vs AE-A7 | done | |
| 2.5 | Passive works offline | local ONNX models | DE-D10, AE-A13 | done | |

### 7.3 Task 3: Presentation attack detection

| ID | Bullet | Where | Evidence | Status | Notes |
|---|---|---|---|---|---|
| 3.1 | PAD as part of liveness | passive PAD, active PAD during challenges, `FrameIntegrityChecker`, `PlanarityChecker`, `CaptureBinder` | `IntegrityAndPlanarityTest`, `ActivePhaseTest`, `CaptureBindingTest` | done | |
| 3.2 | Detect applicable attack types | as above | 22 attack scenarios; run of 2026-10-14 at the shipped defaults (`eval/results/20261014-shipped/README.md`): 0 of 226 attack transactions accepted, APCER 0.0% per transaction and on the first attempt for every species and workflow, including the adaptive screen replay SYN-A7 (0/8 since the screen-pattern check of 2026-10-05; 4/8 before it); species are not averaged (worst species per workflow in that report). The same suite at `PASSIVE_THEN_ACTIVE` accepts 100 of 226 (O6). | partial | Every synthetic species is refused at the shipped defaults. Genuine users: BPCER `registration` 0.0% (0/28), `login_auth` 0.0% (0/12), three subjects only. Synthetic attacks only; masks and deepfakes not tested (O19); the volunteer protocol is `manual-test-checklist.md` section B. |
| 3.3 | Prevent face capture on attack | `LivenessGate` (desktop), `FaceCaptureLivenessGate` (Android) | DE-D6; AE-A6, AE-A10 | done | For attacks the engine detects. |
| 3.4 | Prevent authentication on attack | `authValidator`, `FaceAuthenticator` | DE-D7, DE-D10; AE-A4, AE-A11 | done | For attacks the engine detects. |
| 3.5 | Evaluate against ISO/IEC 30107 | `docs/eval/` | E6 | partial | Volunteer gap. Volunteer evaluation pending; placeholder in [test-report/README.md](test-report/README.md) section 7. |

### 7.4 Task 4: Active liveness

| ID | Bullet | Where | Evidence | Status | Notes |
|---|---|---|---|---|---|
| 4.1 | Start active verification automatically | `DecisionPolicy` → `CHALLENGE_WAIT` | DE-D4, AE-A7 | done | |
| 4.2 | Select the challenge dynamically | `ChallengeScheduler` | `ChallengeSchedulerTest` | done | |
| 4.3 | Prompt the selected action | `core/engine/LivenessUiStateMapper` → `LivenessPanel` / `LivenessCaptureActivity` | `UiStateMapperTest`, `LivenessViewModelTest`; screenshots `e2e-03` (desktop), `e2e-06` (Android) | done | |
| 4.4 | Receive and analyse later frames | `ChallengeObserver` | `ChallengeObserverTest` | done | |
| 4.5 | Verify the requested action | `ChallengeObserver`, `ActionLevels`, `NeutralBaseline` | `ChallengeObserverTest`; CN0 (bona fide challenge completion TURN_LEFT 15/15, TURN_RIGHT 15/15, BLINK 24/24, SMILE 24/24) | done | Head-turn defect fixed (O13). Kept on purpose: a blink already under way at the prompt fails as `ONSET_TOO_FAST` and the person blinks again (seen 3 times under the old configuration, DE-D11); accepting it would let a continuously blinking replay pass (CN0 0.1, proposal E2 not adopted). |
| 4.6 | Re-evaluate liveness during active verification | active PAD every n-th frame, integrity and planarity on every frame | `ActivePhaseTest`; `scn:atk-s2-screen-replay` under the proposed policy (refused by active PAD) | done | |
| 4.7 | Complete only after success | `CAPTURE_READY` needs every check | `SessionScenarioTest` | done | Since 2026-10-05 the capture frame is a neutral one: after the last challenge both clients show "Look directly at the camera and hold still" (`liveness.feedback.look_straight`) until a frame has yaw at most 15 degrees (`quality.max_yaw_deg`), pitch at most 20, eyes open, no smile or blink, held 300 ms; none within `active.challenge_timeout_ms` ends the attempt as `TIMEOUT` / `NEUTRAL_TIMEOUT`, counted. Unit and scenario tests only. |

### 7.5 Task 5: Resident registration

| ID | Bullet | Desktop | Android | Evidence | Status | Notes |
|---|---|---|---|---|---|---|
| 5.1 | Liveness in resident face capture | `GenericBiometricsController.scan` → `BaseController.startFaceLiveness`; rCapture only after the pass | `BiometricsDetailsApi` → `FaceCaptureCoordinator` → `FaceCaptureLivenessGate` | DE-D3, DE-D4; AE-A7 | done | |
| 5.2 | Never accept a face that fails | `LivenessGate` in `BioServiceImpl.captureModality` | only PASS stored | DE-D6, AE-A10 | done | On Android, faces from earlier passing attempts of the same registration stay stored after a later block (upstream attempt model). |
| 5.3 | Prompt the resident automatically | `LivenessDialog` | `LivenessCaptureActivity` | DE-D4, AE-A7 | done | |
| 5.4 | Retry after a failure | Retry button, attempt counter | same | DE-D6, AE-A6 | done | |
| 5.5 | Enforce the retry limit | `LivenessTransaction`, `AttemptHistory`, `LivenessSessionDriver` | `priorAttempts` and the suspected-attack mark across capture screens (`FaceCaptureLivenessGate`) | DE-D6 (LV11 once), AE-A10 (further Scan opens nothing) | done | After the last failure desktop offers a supervisor override (second person and checked photo, SV2), never once the transaction is marked a suspected attack: any PAD, integrity or binding failure in this or an earlier window (T-CORE-TX-03; DE-I2). Android blocks (no face is kept from a failed session) and never offers its override after an earlier attack. Dim-light endings (`LOW_LIGHT`, up to `passive.low_light_max_uncounted` = 3 per capture, carried across Cancel and reopened windows on both clients) do not use an attempt; the fourth counts (9.6). |

### 7.6 Task 6: Operator authentication

| ID | Bullet | Desktop | Android | Evidence | Status | Notes |
|---|---|---|---|---|---|---|
| 6.1 | Liveness in operator biometric authentication | login, packet authentication, onboarding | sign-in, packet authentication, onboarding | DE-D1, DE-D3, DE-I1; AE-A2, AE-A9 | done | |
| 6.2 | Passive liveness and PAD | engine | engine | DE-D1, AE-A2 | done | |
| 6.3 | Active challenge when required | threshold 95 | threshold 95 | AE-A3, DE-D10 | done | |
| 6.4 | Validate the operator's action | engine | engine | AE-A3 | done | |
| 6.5 | Prevent authentication when liveness fails | `authValidator` | `FaceAuthenticator` | AE-A4; `LivenessGateTest` | done | |
| 6.6 | Offline authentication | local engine, local match | local engine, on-device SFace match | DE-D10, AE-A13 | done | |

### 7.7 Task 7: Supervisor authentication

| ID | Bullet | Desktop | Android | Evidence | Status | Notes |
|---|---|---|---|---|---|---|
| 7.1 | Liveness in supervisor authentication | `eod_auth`, `exception_auth` | `eod_auth` (approval dialog, role check) | DE-D5, DE-D7; AE-A11 | done | |
| 7.2 | Passive liveness and PAD | engine | engine | DE-D7, AE-A11 | done | |
| 7.3 | Active challenge when required | threshold 95 | threshold 95 | AE-A11 (challenge asked, replay timed out) | done | |
| 7.4 | Validate the supervisor's action | engine | engine | DE-D10, AE-A11 | done | |
| 7.5 | Prevent authentication when liveness fails | `authValidator` | `FaceAuthenticator` (also `NOT_SUPERVISOR`) | DE-D7, AE-A11; `FaceAuthenticatorTest` | done | |
| 7.6 | Offline authentication | as 6.6 | offline approval (`mosip.registration.android.offline_approval.enable=Y`) | DE-D10, AE-A13 | done | |

### 7.8 Task 8: Configuration

Keys are `mosip.registration.liveness.<name>` on both clients, resolved by `core/policy/PolicyResolver` (ranges,
floors and an LV15 audit when a synced value changes). A local (machine-level) setting may change only the keys of `LivenessConfigKeys.isLocalOverrideAllowed`: `passive.threshold` and `pad.reject_threshold` (both floored), `active.challenge_timeout_ms`, `stream.timeout_ms`, `max_retries`, `diagnostic.enable`, and each workflow's threshold and `max_retries`; every other key uses the synced value or the shipped default (`LivenessConfigKeysTest`, desktop `LivenessConfigTest`, `GlobalParamConfigSourceTest`).

| ID | Bullet | Key(s) | Evidence | Status | Notes |
|---|---|---|---|---|---|
| 8.1 | Liveness enablement | `enable` (no local override) | `PolicyResolverTest`, `LivenessGateTest` (allowed when OFF) | done | |
| 8.2 | Passive threshold | `passive.threshold`, `<workflow>.passive.threshold`, `pad.reject_threshold` | `PolicyResolverTest` | done | |
| 8.3 | Active liveness policy | `active.enable`, `policy.<workflow>` (`OFF`, `PASSIVE_ONLY`, `PASSIVE_THEN_ACTIVE`, `ALWAYS_ACTIVE`) | DE-D8, AE-A12 (change by sync) | done | Defaults: `ALWAYS_ACTIVE` for every workflow except `exception_photo` (`OFF`) (O6). |
| 8.4 | Challenge types | `active.challenge_types` (default `BLINK,SMILE,TURN_LEFT,TURN_RIGHT`) | `PolicyResolverTest`; the demo uses `BLINK,SMILE` from MockSync | done | |
| 8.5 | Minimum challenge count | `active.min_challenges` (1-5) | `PolicyResolverTest` | done | |
| 8.6 | Challenge timeout | `active.challenge_timeout_ms` (2000-15000) | `PolicyResolverTest`; DE-D6 (`CHALLENGE_TIMEOUT`) | done | |
| 8.7 | Maximum retry count | `max_retries` (1-10), per workflow | DE-D6, AE-A10 | done | |
| 8.8 | Workflow-specific policies | `policy.<workflow>` and `<workflow>.*` for `registration`, `exception_photo`, `login_auth`, `packet_auth`, `exception_auth`, `eod_auth`, `onboard_auth` | `LivenessPolicyTest`, `PolicyResolverTest` | done | |
| 8.9 | Behaviour on repeated failure | `<workflow>.on_max_failure` (`BLOCK`, `SUPERVISOR_OVERRIDE` for residents, `BLOCK_AND_LOCK` for authentication) | `LivenessOrchestratorTest`, `LivenessOnMaxFailureTest`, `LivenessAccountLockTest` (desktop); `SupervisorOverrideTest`, `SupervisorOverrideFlowTest`, `FaceCaptureLivenessGateTest` (Android); DE-I2 (override); AND-13 | done | Desktop: all three behaviours; `BLOCK_AND_LOCK` counts one failed login and locks at `invalid_login_count` (it does not end the current session). Android: `SUPERVISOR_OVERRIDE` built on 2026-09-30 (the supervisor must pass face authentication with liveness first); run on the emulator screen by screen, not in the full scripted demo (the host was overloaded). |

### 7.9 Task 9: Error handling

Every error has a message key (`core/ui/MessageKeys`), an icon, a Retry rule and an audit event. Device and
engine errors never use an attempt.

| ID | Error | Where handled | Evidence | Status | Notes |
|---|---|---|---|---|---|
| 9.1 | Device unavailable | `DeviceErrorCode.NOT_FOUND/NOT_READY` → `liveness.error.device_unavailable` | `screenshots/desktop/d-r10-device-error.png`; AE-A5 | done | Desktop shows the "disconnected" text for a refused stream (1.6). |
| 9.2 | Device connection failure | `DISCONNECTED`, `STALLED`, `STREAM_OPEN_FAILED` | DE-D2; `FaceStreamSessionTest` | done | |
| 9.3 | Camera unavailable | `liveness.error.camera_unavailable`, `CAMERA_IN_USE`, `CAMERA_PERMISSION_DENIED` | AE-A5 (`STREAM_OPEN_FAILED`, LV12) | done | |
| 9.4 | Face not detected | `ReasonCode.NO_FACE` → guidance, no attempt used | `screenshots/desktop/d-r12-no-face.png`; `QualityAndDecisionTest`; `scn:other-no-face` (no response, no attempt counted) | done | |
| 9.5 | Multiple faces | `ReasonCode.MULTI_FACE`, `CAPTURE_MULTI_FACE` | `QualityAndDecisionTest`, `CaptureBindingTest`; `scn:other-two-faces` (`MULTI_FACE`, no attempt counted) | done | Not part of the client end-to-end runs. Since 2026-10-05, 3 frames in a row (`active.multi_face_frames`) with more than one face in the active phase end the attempt with `MULTI_FACE` as a counted `TIMEOUT` (a challenge phase has started), with `liveness.error.multiple_faces`; unit and scenario tests only. |
| 9.6 | Poor face quality | `TOO_DARK`, `TOO_BRIGHT`, `BLURRY`, `FACE_TOO_SMALL`, `OFF_POSE` | `QualityAndDecisionTest`; `scn:other-too-dark` (`TOO_DARK`), `scn:other-blurred` (`BLURRY`); CN0 | done | Since model bundle 1.0.1 (`quality.min_sharpness=40`, P2) blurred frames stop at the quality gate: no attempt is used and the final message is `liveness.error.poor_quality`. While positioning, `BLURRY` shows the guidance `liveness.guide.face_visible`; there is no blur-specific text. Dim light (since 2026-10-05): a passive PAD failure on a face whose median grey level is below `quality.low_light_brightness` (75) ends as `QUALITY` / `LOW_LIGHT`, not counted, no LV08, guidance "Improve lighting", up to `passive.low_light_max_uncounted` (3) per capture; the fourth counts as PAD with the generic message. Both clients have it on and carry the count across windows. Calibrated on replayed frame packs only (the dim clip scores 66.5-69.5, attacks at least 82.1); no dim-room recording of a real person exists. In the scenario suite `bf-s3-dim` still ends REJECT (three `LOW_LIGHT` endings, then a counted `PAD_SCORE`). |
| 9.7 | Liveness below threshold | `BELOW_THRESHOLD` → active | DE-D4, AE-A7 | done | |
| 9.8 | PAD failure | `FAIL_PAD` → generic message only | DE-D6, AE-A6 | done | |
| 9.9 | Active challenge failure | `WRONG_ACTION`, `NOT_HELD`, `ONSET_TOO_FAST` | `ChallengeObserverTest`; `scn:atk-*-adaptive-wrong-clip` | done | |
| 9.10 | Challenge timeout | `CHALLENGE_TIMEOUT` → "Time is up for this action" | DE-D6, AE-A4 | done | |
| 9.11 | Maximum retries exceeded | LV11, recovery message, capture blocked | DE-D6, AE-A10 | done | |
| 9.12 | Invalid or incomplete frame data | `MjpegFrameReader` (`INVALID_STREAM`), `INVALID_FRAMES`, `TIMESTAMP_REGRESSION` | `MjpegEdgeCaseTest`, `IntegrityAndPlanarityTest` | done | Unit tests only. |
| 9.13 | Liveness engine errors | `EngineErrorCode` (`MODEL_INTEGRITY` fails closed, `INFERENCE_TIMEOUT`), LV14 | `ModelRegistryTest`, `SessionEdgeCaseTest`; desktop report 5.4 (`INFERENCE_TIMEOUT` under load, retry passed) | done | |

### 7.10 Task 10: UI/UX requirements

Texts: [ux/message-catalogue.csv](ux/message-catalogue.csv) (desktop en/fr/ar); Android has the same English texts
in `livenessmanager/src/main/res/values*/strings.xml` (en, fr, ar, hi, kn, ta) and the Flutter ARB files.

| ID | PS04 UI/UX item | Desktop | Android | Evidence | Status | Notes |
|---|---|---|---|---|---|---|
| UX1 | Capture screen: live preview, positioning guidance, liveness status, capture progress, clear "being verified" indication | `RC/controller/liveness/LivenessPanel` + `fxml/LivenessPanel.fxml` | `LM/ui/LivenessCaptureActivity`, `OvalGuideView` | `screenshots/desktop/d-r1-positioning.png`, `android/e2e-06-low-confidence-challenge.png` | done | Technical details only with `diagnostic.enable=Y`. |
| UX2 | "Checking face liveness..." and automatic progress without user action | `liveness.status.checking` | same key | `docs/ux/desktop-liveness.md` section 2; Android Logcat screen states | done | No committed screenshot of this state (the pass path used real-person frames). |
| UX3 | Automatic challenge display ("Please blink", "Please smile", "Please turn your head to the left") | `liveness.challenge.*` | same | DE-D4, AE-A7 | done | |
| UX4 | Real-time feedback ("Please continue", "Action detected", "Hold still", "Good, face captured successfully") | `liveness.feedback.*` | same | `screenshots/android/a-r1-resident-face-passed.png` (success banner); `LivenessViewModelTest` | done | |
| UX5 | Liveness failure with actionable guidance (look at the camera, face visible, lighting, capture area, follow the prompt) | `liveness.fail.liveness` + `liveness.guide.*` | same | `screenshots/desktop/e2e-10-config-sync-print-refused.png`, `android/e2e-03-sign-in-replay-refused.png` | done | |
| UX6 | PAD failure: generic message, details only in audit/diagnostics | `liveness.fail.pad`; reason in LV08 only | same | `d-r7-pad-fail-retry.png`, `e2e-06-injected-still-refused.png`, `android/e2e-05-resident-replay-refused.png`; `UiStateMapperTest` | done | |
| UX7 | Retry indication; maximum attempts message with recovery process | `liveness.retry.available`, `retry.max_resident`, `retry.max_auth` | same | `e2e-07-max-attempts-guidance.png`, `android/e2e-08-max-attempts.png` | done | |
| UX8 | Same principles for operator and supervisor authentication | same `LivenessDialog` in 6 screens; "Supervisor verification" title | same activity; Flutter `FaceAuthPanel` | `d-e1-eod-supervisor-arabic.png`, `android/e2e-11-supervisor-replay-refused.png` | done | Differences: desktop resident override vs Android block; wrong-person text differs (see `test-report/android-e2e.md` section 7). |

## 8. Bonus tasks

| ID | Bonus task | Where | Evidence | Status | Notes |
|---|---|---|---|---|---|
| B1 | Mock L0/L1 device | `devices/mock-mds` (frame sources, capture from the streamed frame, fault injection, L1 toggle; `LIVENESS-EXTENSIONS.md`) | MockMDS tests ([test-report/README.md](test-report/README.md) section 2); DE-D2, DE-D9 | done | Android uses the in-app replay source instead. |
| B2 | Automated liveness scenarios | `tools/eval/scenario-runner`, 10 bona fide + 7 quality scenarios; scripted client demos `scripts/demo-desktop.sh`, `scripts/demo-android.sh` | 3 suites of 131 transactions; desktop 41/41, Android 58/58 checks (2026-09-30, earlier defaults; at the current policy with blink and smile only: 71 of 71 on 2026-10-17 and twice on 2026-10-20, 59 of 74 on 2026-10-17); suites of 2026-10-14: 275 transactions, [eval/results/20261014-shipped/](eval/results/20261014-shipped/README.md) | done | |
| B3 | Automated PAD attack scenarios | 22 attack scenarios over 25 synthetic attack packs (`eval/packs/`) | `docs/eval/results/20260930/*/report.md`, `docs/eval/results/20261014-shipped/report.md` | done | Attacks are software-made; not real artefacts. |
| B4 | Interoperability testing framework | One ONNX Runtime release on both clients (1.20.0; Android moved down from 1.28.0, [ADR 0003](adr/0003-onnxruntime-1.20.0-pin.md)); parity test: the same recorded frames through each client's engine loader, configuration and image decoder (`core` test jar `interop/ParityCheck`, `ParityReplay`) | `ParityCheckTest`, desktop `LivenessParityTest`, Android `LivenessParityTest`, `LivenessParityDeviceTest`; [test-report/interoperability.md](test-report/interoperability.md): desktop 19/19, Android unit 19/19, emulator 6/6 committed cases, all decisions identical, largest score difference 0 points over 98 scores | done | Compares the clients with each other, not device vendors: only MockMDS (SBI 0.9.5) was tested; no second vendor device, no SBI 1.0 device, no physical phone (arm64 ONNX Runtime untested), Linux CI not run yet. |
| B5 | Model version management | `onnx/model/ModelRegistry` (`model.min_version`), signed `models/manifest.json`; `LIVENESS_ENGINE` and `LIVENESS_MODEL_SHA` in every packet | `ModelRegistryTest`; DE-D13, AE-A15 | done | Since 2026-10-04 the default `model.min_version` is 1.3.0 (`LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION`, MockSync default) and bundle 1.3.0 signs `integrity.loop_window_frames=300` and `quality.min_sharpness=40`; a client still holding bundle 1.0.1 refuses to start liveness (`MODEL_VERSION`, fails closed) until the bundle is replaced. |
| B6 | Secure offline model updates | Signed manifest (RSA-3072, `onnx/tools/ModelSigner`), checked at every load, fail closed. Desktop: supervisor import of a signed bundle from a folder or ZIP (`RS/service/bio/liveness/LivenessModelImporter`, `LivenessModelStore`; header menu "Import liveness models") with signature, hash and anti-rollback checks | `ModelRegistryTest`, `ModelSignerTest`, `LivenessModelImporterTest`, `LivenessModelImportDialogTest`, `ModelBundleImporterTest` (Android); DE-e2e-14..16 (tampered refused, 1.1.0 imported, 1.0.0 refused as rollback) | done | Desktop done. Android done in code: `LM/engine/ModelBundleImporter` and `ModelBundleStore` run the same checks as the desktop importer (signature, per-file SHA-256, anti-rollback, self-test, atomic switch, previous bundle kept, audit LV13/14/15) behind the Flutter bridge call `importModelBundle`; `ModelBundleImporterTest` 15 cases. The "Import liveness model" button in Settings, Device Settings calls it (emulator picture `ux/screenshots/android/a-s1-model-import-button.png`); nothing was run on a phone, and a capture screen open during an import is not checked. How to run it: [install/android.md section 10](install/android.md#10-import-a-liveness-model-bundle-offline-model-update), [design/android-liveness.md section 14](design/android-liveness.md#14-offline-model-update-bonus-b6). The trust anchor is `mosip.registration.liveness.model.trust_anchor_pem` when set (both clients, engine load and import; since 2026-10-10), else the development key built into `liveness-onnx`. |
| B7 | Low-resource Android optimisation | latest-frame-only camera, PAD on every n-th challenge frame, thread count setting, model warm-up after load, arm64-only release (116.7 MB, build of 2026-10-14) | `test-report/android-e2e.md` sections 5-6; AND-13 | done | Measured on an x86_64 emulator (p50 per frame 37-251 ms) and on a physical phone (OnePlus CPH2569, Android 15, charging at about 40% battery, two runs of 2026-10-24: best p50 58-71 ms and p95 74-92 ms at 2 threads on CPU or XNNPACK; 20 ms target missed; [test-report/android-performance.md](test-report/android-performance.md)). One phone and a plugged-in state; the team step `MT-AND-12` in `manual-test-checklist.md` stays open for other phones. |
| B8 | Hardware acceleration | `onnx/runtime/OnnxRuntimeOptions.ExecutionProvider` (`CPU`, `XNNPACK`, `NNAPI`) and key `engine.execution_provider`; Android `LM/engine/Accelerator`, `AcceleratorCustomizer` with CPU fallback | `AcceleratorCustomizerTest`; CN0 0.4; AND-13 | done | Selection and fallback are built and unit-tested. Measured on a phone (OnePlus CPH2569, Android 15, 2026-10-24, [test-report/android-performance.md](test-report/android-performance.md)): the NNAPI provider loads in the Android ONNX Runtime package without falling back, and is 5-8 times slower than CPU (p50 312-468 ms against 58-71 ms); XNNPACK with 2 threads matches CPU with 2 threads (p50 58-71 ms). Measured best is 2 threads on CPU or XNNPACK; shipped value stays `CPU` with 2 threads. One phone only. The Windows ONNX Runtime package offers only CPU, so desktop stays on CPU; on Android all three were measured on the phone (NNAPI p50 312-468 ms, slower than CPU). Shipped value `CPU`. |

## 9. Good-to-have tasks

| ID | Task | Where | Evidence | Status | Notes |
|---|---|---|---|---|---|
| G1 | Configurable challenge pools | `active.challenge_types` | DE and AE runs use a synced `BLINK,SMILE` pool | done | |
| G2 | Multiple facial actions | 8 challenge types (`core/ChallengeType`) | `ChallengeObserverTest`; CN0 | partial | Blink, smile and both head turns verified on replayed people (O13). LOOK_UP, LOOK_DOWN and the gaze types are unit-tested on synthetic landmarks only (O14), so the default pool holds blink, smile and both turns. |
| G3 | Different policies per workflow | `policy.<workflow>`, `<workflow>.*` | 8.8 | done | |
| G4 | Configurable user guidance | `guidance.<messageKey>.<lang>` via `LivenessUiStateMapper.resolveText`; desktop `RC/controller/liveness/LivenessTexts.withOverrides`; Android `LM/ui/MessageResolver` | `UiStateMapperTest`, `LivenessTextsTest`, `MessageResolverTest`; DE-e2e-13 (replaced "Checking" text on screen) | done | Both clients apply the synced replacement text for any liveness message and language. Android checked by unit test only. |
| G5 | Diagnostic mode | `diagnostic.enable`; `core/engine/DiagnosticLog`; desktop `DesktopDiagnosticSink`, `DiagnosticTap`, diagnostic lines on the panel (`RC/.../DiagnosticText`), audit `REG-EVT-LV17` (`DiagnosticModeAudit`); Android diagnostic panel, `LogcatSinks`, `LV17` (`LM/audit/DiagnosticModeTracker`) | `AuditTest.diagnosticLineHasOnlyTechnicalFields`, `DiagnosticTextTest`, `DiagnosticModeTrackerTest`, `LivenessCaptureActivityTest`; DE-e2e-13 (lines on screen, `LV17` in the audit table); AND-13 (panel on the emulator) | done | Off by default. Shows only technical fields (state, PAD score, frame rate, model versions); no image, template or ID. The desktop line shows `ENGINE` for engine failures without the engine error code. |
| G6 | Multiple device vendors | adapter interface (TS4); SBI 0.9.5 (tested) and 1.0 (implemented, untested) providers on desktop | — | partial | Hardware gap. One vendor (MockMDS) tested. |
| G7 | Anonymised operational metrics | `core/engine/LivenessMetrics`; desktop `RS/.../LivenessMeterBinder` → `logs/metrics.log`; Android `LM/metrics/MetricsReport`, `MetricsLog` → Logcat tag `LivenessMetrics` and `files/liveness/metrics.log` (`metrics.enable`) | `ReportingTest`, `MetricsReportTest`; AND-13 (file written, no user or registration ID) | done | Same metric names on both clients. |
| G8 | Average processing time, challenge completion time, retry rate, failure rate | `LivenessMetrics.snapshot()`: `time.frame.*`, `time.challenge.avg_ms`, `rate.retry`, `rate.failure` | `ReportingTest`, `MetricsReportTest` | done | As G7. |
| G9 | Device capability discovery | `padCapability` read from SBI discovery (desktop DTOs accept unknown fields); certification level reported in LV01 | `LivenessGateTest` (signed capture reading); DE-D9 (certification level in LV01) | partial | Hardware gap. Discovery tolerates and reads `padCapability`, but no test device advertises it, and the rCapture request does not yet send the challenge to such devices. |

## 10. Deliverables

### 10.1 Technical design

The design pages in `docs/design/` answer the PS04 bullets one by one; the engine and client pages give the detail.

| Bullet | Document | Status | Notes |
|---|---|---|---|
| High-level architecture for L0/L1 device integration | `docs/design/architecture.md` | done | Component and deployment views (Mermaid); component view also as a picture, `docs/design/img/architecture.svg` / `.png`. |
| Face frame streaming architecture | `docs/design/streaming.md` | done | |
| Liveness and PAD processing architecture | `docs/design/engine.md`, `liveness/README.md` "Engine algorithms" | done | |
| Passive-to-active decision flow | `docs/design/decision-flow.md` | done | Also as a picture, `docs/design/img/decision-flow.svg` / `.png`. |
| Dynamic challenge mechanism | `docs/design/challenges.md` | done | |
| Resident registration integration flow | `docs/design/integration-flows.md` section 2, `docs/design/sequences.md` sections 1 and 4 | done | |
| Operator authentication integration flow | `docs/design/integration-flows.md` section 3, `docs/design/sequences.md` sections 2 and 5 | done | |
| Supervisor authentication integration flow | `docs/design/integration-flows.md` section 4, `docs/design/sequences.md` sections 3 and 5 | done | |
| Online and offline architecture | `docs/design/online-offline.md` | done | |
| Configuration design | `docs/design/configuration.md` | done | |
| Device integration interface | `docs/design/device-interface.md` | done | |
| Liveness engine interface | `docs/design/engine-interface.md`, `core/api` Javadoc | done | |
| Error handling and recovery flow | `docs/ux/flows.md` sections 5-6, `docs/design/sequences.md` section 6 | done | |
| Sequence diagrams | `docs/design/sequences.md` (8 diagrams, desktop and Android), `docs/ux/flows.md` | done | |
| Security and privacy considerations | `docs/design/security-privacy.md` (assets, threats and mitigations, stream-to-capture binding, privacy, keys, supply chain, what is not covered), `docs/design/android-liveness.md` section 5 (device key trust model), `docs/design/desktop-liveness-services.md` section 4 (gates, pass tokens) | done | |
| ISO/IEC 30107 alignment | `docs/eval/protocol.md`, `docs/eval/calibration-notes.md` | partial | The alignment matrix of the protocol (section 11) is filled only after the volunteer evaluation. |

### 10.2 UI/UX design

See [ux/README.md](ux/README.md) for the screen index. Summary:

| Bullet | Status | Notes |
|---|---|---|
| Updated resident face capture screen | done | Both platforms, screenshots. |
| Updated operator authentication screen | done | Desktop login (French, offline), Android sign-in with face. |
| Updated supervisor authentication screen | done | Desktop EOD (Arabic), Android supervisor approval. |
| Live face preview | done | Desktop `e2e-03`, `e2e-05`, `e2e-09` and `e2e-17` show the preview or the captured face with recorded stock faces (Pexels License, not project members); the other pictures hide the preview. |
| Liveness verification status | done | Desktop "Checking face liveness...", "Action detected", "Hold still" and success pictured (`d-s1` to `d-s5`, 2026-10-22); Android states drawn in the wireframes only. |
| Active challenge prompts; facial action guidance; challenge progress | done | Desktop `e2e-03`, Android `e2e-06`, `e2e-12`. |
| Liveness failure messages; PAD/failure messages | done | |
| Retry flow; maximum retry flow | done | |
| Device error handling | done | |
| Online/offline UI behaviour | partial | Desktop offline badge pictured; the Android badge was read from the view tree only. |
| Consistent desktop and Android experience | done | Same message keys and texts (`test-report/android-e2e.md` section 7). |

### 10.3 Implementation / build

| Bullet | Where | Status |
|---|---|---|
| L0/L1 device integration | 7.1 | partial (Android L0 only) |
| Face frame stream handling | 1.2-1.5 | done |
| Liveness/PAD engine integration | `liveness/`, both clients | done |
| Passive liveness detection | 7.2 | done |
| Active liveness challenge | 7.4 | done |
| Dynamic challenge selection | 4.2 | done |
| Facial action validation | 4.5 | done |
| Configurable liveness threshold | 8.2 | done |
| Resident face capture integration | 7.5 | done |
| Operator authentication integration | 7.6 | done |
| Supervisor authentication integration | 7.7 | done |
| Retry and failure handling | 5.4, 5.5, 7.9 | done |
| Online/offline support | OF1-OF4 | done (models: offline signed import on both clients, Android without a screen) |
| Configuration support | 7.8 | done |
| Audit and diagnostic logging | E20 | done |
| Desktop Registration Client changes | `clients/desktop` (117 files added or changed against the upstream import `96f4c56`, whitespace-only changes ignored, counted on 2026-10-22) | done |
| Android Registration Client changes | `clients/android` (209 files added or changed against the upstream import `7f7e094`, whitespace-only changes ignored, counted on 2026-10-22) | done |

## 11. Summary of open items

| Item | Rows | Kind of gap | What would close it |
|---|---|---|---|
| Adaptive screen replay (SYN-A7): accepted in `registration` 50.0% (4/8) per transaction in the morning run of 2026-10-05, 0.0% (0/8) after the screen-pattern check of that evening; the check is shown on synthetic screens only, and frames enlarged by 1.5x or a whole number before the engine can look like a screen | O18, 3.2 | engine | Volunteer test with real screens; then a stronger passive / active PAD model, or device-side PAD on L1 hardware |
| Volunteer evaluation (extends the real-person run of [2026-10-24](eval/results/real-faces-2026-10-24.md)) | E6, 3.5 | volunteers | Run [eval/protocol.md](eval/protocol.md) (about 12 subjects, printed, screen and replay artefacts) |
| Android: no L1 or vendor SBI face device | O1, 1.1, 1.2, TS2 | hardware and integration | Accept L1 in `Biometrics095Service.isDeviceValid`; run `SbiIntentDevice` (wired, unit-tested only) with a vendor face app; run CameraX with a real camera |
| Only one device vendor (MockMDS) tested; SBI 1.0 implemented, untested | TS1, TS4, G6 | hardware | Run a second vendor's SBI 0.9.5 / 1.0 face device (the desktop-versus-Android parity test, B4, is done) |
| Speed: per-frame p95 above 20 ms on desktop and on the phone (phone p95 74-92 ms at the best setting, 2 threads; NNAPI 5-8 times slower); one phone, plugged in, two runs | E21, B7, B8 | hardware | Measure an unplugged phone under sustained load and a second phone model; measure an idle PC; proposal E4 (Face Mesh only during challenges) |
| Android model import (Device Settings button, `device_settings_tab.dart`) was not run on a phone | B6 | integration | Run the import on a phone |
| Scripted demos at the current defaults, live-person runs and the volunteer evaluation | B2, 3.5, O13 | people and hardware | Desktop run through the screens, Android scripted demo, live webcam runs, [eval/protocol.md](eval/protocol.md) |
| Look up / down and gaze challenges only unit-tested | O14, G2 | data | Record bona fide clips with those actions |
| Device capability discovery sends no challenge to devices with PAD | G9 | hardware | A device that advertises `padCapability` |

Closed on 2026-09-30: prints and looped clips passing the passive check (O16; defaults `ALWAYS_ACTIVE` and
`passive.min_frames=30`), head turn left misread as a smile (O13, 4.5), blurred frames reaching the challenges
(9.6), desktop replacement texts (G4), diagnostic mode (G5), Android metrics (G7, G8), Android supervisor
override (8.9), desktop offline model import (B6, desktop part), the technology evaluation (E5).

Closed on 2026-10-04 and 2026-10-05: challenges on every capture for the supervisor workflows (O6), the retry counters kept across Cancel and reopened windows (5.5; across client restarts since 2026-10-10), the override refused after any attack and needing a second, different supervisor on both clients (SV2), binding that fails closed (T-CORE-TX-03 and `CaptureBinder`), long loops caught by the 300-frame window (O18), the local-settings allow-list (7.8), the dim-light and multiple-face endings (9.5, 9.6), Android model import with its Device Settings button (B6), one ONNX Runtime release on both clients with a parity test (B4), a phone measurement kit (B7, B8; measured on a phone 2026-10-24).
