# Interoperability: desktop and Android give the same liveness decision (bonus B4)

The desktop Registration Client and the Android Registration Client share the engine (`liveness-core`,
`liveness-onnx`) and the signed model bundle. Each client adds its own parts: the engine loader, the configuration
source, the image decoder and the ONNX Runtime package. This test checks that these client parts do not change a
decision: the same recorded frames go through both clients' code and must give the same result.

## 1. What is compared

- **One ONNX Runtime release on both clients: 1.20.0.** The desktop cannot move past 1.20.0 (newer releases do not
  load on its bundled Zulu 11.0.8 JRE). Android moved from `onnxruntime-android` 1.28.0 to 1.20.0 on 2026-10-05
  ([ADR 0003](../adr/0003-onnxruntime-1.20.0-pin.md)).
  - Desktop: `clients/desktop/registration/registration-services/pom.xml`, property `onnxruntime.version`.
  - Android: `clients/android/android/build.gradle`, `onnxruntimeAndroidVersion`.
- **Parity file** `eval/interop/liveness-parity.json`, with one line per case:
  - the frames, the workflow, the configuration and the random seed;
  - `expected`: the result the desktop client recorded.
- **Replay** (`liveness/liveness-core/src/test/java/io/mosip/liveness/core/interop/`, shipped in the `liveness-core`
  tests jar). It runs one session per case on stream time:
  - a manual clock advances one frame interval (15 fps) per frame;
  - the challenge draws use a seeded random source, so both clients get the same prompts;
  - prompts are answered from the case's action clips, as the scenario runner does;
  - at `CAPTURE_READY`, the last frame is decoded by the client's decoder and bound to the session.
- **Per client**, the test supplies the client's own code:

  | | Desktop `LivenessParityTest` | Android `LivenessParityTest` (unit) | Android `LivenessParityDeviceTest` |
  |---|---|---|---|
  | Engine | `LivenessEngineRegistry` (models from the class path) | `EngineLoader.onnx` (models from the APK assets) | same, on the device |
  | Configuration | `LivenessConfig` | `GlobalParamConfigSource` + `PolicyResolver` | `ConfigSource` + `PolicyResolver` |
  | Decoder | `DesktopImageDecoder` (Java ImageIO) | `AndroidImageDecoder` (Android `BitmapFactory`, Robolectric native graphics) | `AndroidImageDecoder` |
  | ONNX Runtime | `onnxruntime` 1.20.0 | `onnxruntime` 1.20.0 (JVM package; unit tests run on the build machine) | `onnxruntime-android` 1.20.0 |
  | Cases | all | all | committed cases only |

- **Must be identical:**
  - decision and failure reason;
  - reason codes;
  - method;
  - engine error;
  - challenges and their outcomes;
  - number of frames streamed.
- **Allowed to differ by up to 2 points** (`scoreTolerance`, on the 0-100 scale): the passive score, the active PAD
  score, the binding similarity and the PAD score of every analysed frame. Two different JPEG decoders and a
  different thread count can move a score slightly.
- **Cases:**
  - 6 committed cases from the MockMDS images in `liveness/liveness-onnx/src/test/resources/faces/` (MPL-2.0).
    They run everywhere, CI included:
    - 3 replay a still image, so they end on `FROZEN`;
    - 3 add the same seeded noise to every pixel after decoding, so frames differ the way camera frames do and the
      session reaches the PAD and challenge steps.
  - 13 cases from the frame packs outside git (`../run/frame-packs`; `LIVENESS_FRAME_PACKS` or
    `-Dliveness.framePacks` names another folder). They mirror the scenario files of `eval/scenarios/`:
    - 4 genuine (S1, S2, S3; registration and login);
    - 5 attacks (injected still, print, screen replay, looped clip, moved print);
    - wrong action, no face, too dark, two faces.
    - Where the packs are missing (CI), these cases are skipped and logged.

## 2. How to run

From the repository root in Git Bash. The commands are the same as in [README.md](README.md) section 2.

```bash
# 1. engine and the liveness-core tests jar (JDK 11)
mvn -B -f liveness/pom.xml install -Dliveness.benchmark.frames=0

# 2. desktop (JDK 11 with JavaFX)
mvn -B -f clients/desktop/registration/pom.xml test -pl registration-services -am -Dgpg.skip -Djacoco.skip=true \
  -DfailIfNoTests=false "-Dtest=io/mosip/registration/service/bio/liveness/LivenessParityTest.java"

# 3. Android, unit test (JDK 21)
cd clients/android/android && ./gradlew :livenessmanager:testDebugUnitTest --tests "*LivenessParityTest"

# 4. Android, on an emulator or device (JDK 21)
./gradlew :livenessmanager:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=io.mosip.registration.livenessmanager.LivenessParityDeviceTest"
```

- Each test prints one line per case: decision, reason codes and the largest score difference.
- A difference fails the test and names the case and the field.
- **Re-recording.** After an intended engine, model or policy change, re-record the expected results:
  - run step 2 with `-Dliveness.parity.update=true`, or with the environment variable `LIVENESS_PARITY_UPDATE=true`;
  - do this with the frame packs present, so that all 19 cases are recorded;
  - then run steps 3 and 4.
- **CI** (`.github/workflows/ci.yml`):
  - the `desktop-liveness` job runs the desktop test through its `bio/liveness/*Test.java` pattern;
  - the `android-unit` job runs the Android unit test through `scripts/android-build.sh --test-only`;
  - both run the 6 committed cases.
  - The device test needs an emulator and is not in CI.

## 3. Measured result (2026-10-05)

Host: Windows 11 laptop, 12 threads. Desktop on Zulu 11.0.8. Android unit tests on JDK 21 with Robolectric 4.13
(SDK 34). Device run on the `arc_api34` emulator (Android 14, x86_64, headless). Engine `mosip-onnx-default@1.0.0`,
model bundle `mosip-liveness-models` 1.3.0, ONNX Runtime 1.20.0 everywhere.

| Run | Cases run | Decisions, reasons, challenges, frames identical | Largest score difference |
|---|---|---|---|
| Desktop `LivenessParityTest` (records, then checks itself) | 19 of 19 | 19 of 19 | 0 points |
| Android `LivenessParityTest` (unit, JVM) | 19 of 19 | 19 of 19 | 0 points |
| Android `LivenessParityDeviceTest` (emulator, `onnxruntime-android`) | 6 of 6 (committed) | 6 of 6 | 0 points |

- 98 scores were compared:
  - 20 single scores (passive, active PAD and binding);
  - 78 per-frame PAD scores.
- All 98 were equal on both clients, so the 2-point tolerance was not used.

The recorded results (the same on both clients):

| Case | Frames | Workflow | Decision | Reason codes | Frames streamed | Passive / active PAD / binding | Per-frame PAD scores |
|---|---|---|---|---|---|---|---|
| `committed-mockmds-still-registration` | committed | registration | FAIL_PAD | FROZEN | 6 | - / - / - | 0 |
| `committed-mockmds-still-login` | committed | login_auth | FAIL_PAD | FROZEN | 6 | - / - / - | 0 |
| `committed-exception-photo-still` | committed | registration | FAIL_PAD | FROZEN | 6 | - / - / - | 0 |
| `committed-mockmds-noisy-registration` | committed | registration | TIMEOUT | CHALLENGE_TIMEOUT | 127 | 23 / - / - | 4 |
| `committed-mockmds-noisy-login` | committed | login_auth | TIMEOUT | CHALLENGE_TIMEOUT | 118 | 23 / - / - | 4 |
| `committed-exception-photo-noisy` | committed | registration | TIMEOUT | CHALLENGE_TIMEOUT | 121 | 100 / - / - | 4 |
| `bf-s1-registration` | packs | registration | PASS | - | 265 | 64 / 64 / 91 | 11 |
| `bf-s2-registration` | packs | registration | PASS | - | 158 | 94 / 96 / 91 | 11 |
| `bf-s2-login` | packs | login_auth | PASS | - | 164 | 94 / 95 / 94 | 11 |
| `bf-s3-registration` | packs | registration | PASS | - | 149 | 100 / 99 / 92 | 11 |
| `atk-s2-injected-still` | packs | registration | FAIL_PAD | FROZEN | 6 | - / - / - | 2 |
| `atk-s2-print-still` | packs | registration | FAIL_PAD | LOOP | 103 | 76 / - / - | 4 |
| `atk-s2-screen-replay` | packs | registration | FAIL_PAD | PAD_SCORE | 89 | 20 / - / - | 4 |
| `atk-s2-looped-clip` | packs | registration | FAIL_PAD | LOOP | 41 | 97 / - / - | 4 |
| `atk-s3-print-moved` | packs | registration | TIMEOUT | CHALLENGE_TIMEOUT | 114 | 99 / - / - | 4 |
| `other-s1-wrong-action` | packs | registration | FAIL_LIVENESS | WRONG_ACTION | 102 | 64 / - / - | 4 |
| `other-no-face` | packs | registration | FAIL_QUALITY | NO_FACE, PASSIVE_TIMEOUT | 62 | - / - / - | 0 |
| `other-too-dark` | packs | registration | FAIL_QUALITY | TOO_DARK, PASSIVE_TIMEOUT | 62 | - / - / - | 0 |
| `other-two-faces` | packs | registration | FAIL_QUALITY | MULTI_FACE, PASSIVE_TIMEOUT | 62 | - / - / - | 0 |

## 4. Limits

- **Session flow is not covered.** The replay drives the engine session directly, on stream time. The shared
  `LivenessSessionDriver` (threads, the 100 ms tick, device hand-off) and each client's screen code are not part of
  this comparison: they run on wall-clock time, so their timing differs from run to run. Both clients use the same
  driver class, so it holds no client-specific code.
- **Android unit test runtime.** The Android unit test runs ONNX Runtime's JVM package, not
  `onnxruntime-android`. Only the device test runs `onnxruntime-android`, and only on the 6 committed cases.
- **One emulator only.** No physical phone was used. The emulator is x86_64, so the arm64 build of
  `onnxruntime-android` is not covered.
- **Linux CI not yet run.** The committed cases have not been run on Linux. The CI jobs will be the first run
  there (the ONNX Runtime CPU code for Linux x64 differs from the Windows build).
- **Narrow frame set.** The frame-pack cases cover 3 subjects and synthetic attacks only. This is a regression
  test of the clients' code, not an ISO/IEC 30107-3 evaluation.
