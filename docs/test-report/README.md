# Test report (PS04 face liveness and PAD)

One page that collects every test result of the project: unit tests per module, the automated liveness and attack
scenarios, the end-to-end runs on the real desktop and Android clients, latency, and known limits. The details
stay in the linked reports; requirement-by-requirement status is in [../traceability.md](../traceability.md).

| Report | What it covers |
|---|---|
| [desktop-e2e.md](desktop-e2e.md) | Real desktop Registration Client against MockSync (mock MOSIP server) and MockMDS (mock face device): 13 scripted flows, 3 interactive flows, audit and packet evidence. Re-run 2026-10-17 at the shipped policy with the challenge pool cut to blink and smile: 71 of 71 (2026-10-15: 69 of 71) |
| [android-e2e.md](android-e2e.md) | Real Android Registration Client on an Android 14 emulator against MockSync: 16 flows, latency, APK size. Re-run 2026-10-17 at the shipped policy with the challenge pool cut to blink and smile: 59 of 74 (2026-10-14: 55 of 75) |
| [interoperability.md](interoperability.md) | Desktop and Android give the same liveness decision: 19 replayed cases through each client's engine loader, configuration and decoder, one ONNX Runtime release (1.20.0) on both (bonus B4) |
| [android-performance.md](android-performance.md) | Per-frame latency and memory of the engine on a physical Android phone (bonus B7 and B8): measured on a OnePlus CPH2569 on 2026-10-24 (best p50 58-71 ms, p95 74-92 ms at 2 threads; 20 ms target missed) |
| [../install/android.md](../install/android.md#10-import-a-liveness-model-bundle-offline-model-update), [../design/android-liveness.md](../design/android-liveness.md#14-offline-model-update-bonus-b6) | Offline import of a signed model bundle on Android (bonus B6): how to run it and how it works; unit tests only, no phone run |
| [../eval/calibration-notes.md](../eval/calibration-notes.md) | Automated suite: the real engine and models over replayed scenarios, ISO/IEC 30107-3-style metrics |
| [../eval/results/20261014-shipped/README.md](../eval/results/20261014-shipped/README.md), [../eval/results/20261014-latency/README.md](../eval/results/20261014-latency/README.md) | The current scenario suites (2026-10-14) at the shipped defaults and at passive first, and the latency run |
| [../traceability.md](../traceability.md) | Every PS04 requirement → code → test → status |
| [../test-data.md](../test-data.md) | Demo users, machine registration, frame packs |

## 1. Summary

| Area | Result |
|---|---|
| Unit and component tests | **2,706 tests, 0 failures, 0 errors** in the runs of 2026-10-25 over 13 test suites (section 2; Flutter count from 2026-10-14) |
| Automated liveness and attack scenarios | run of 2026-10-14 at the shipped defaults: 275 transactions, 755 attempt records (section 3.0). no attack transaction accepted at the shipped defaults (APCER 0.0% for every species and workflow); counts, bona fide rates and the passive-first result: [results](../eval/results/20261014-shipped/README.md) |
| Desktop end-to-end | reference run 2026-09-30: 41 of 41 checks passed **at the old defaults** (all workflows passive first); 2026-10-17 at the shipped policy with the pool cut to blink and smile: 71 of 71, again in two runs on 2026-10-20 (section 4) |
| Android end-to-end | reference run 2026-09-30: 58 of 58 checks passed **at the old defaults**; a repeat run found one real weakness; 2026-10-17 at the shipped policy with the pool cut to blink and smile: 59 of 74 (section 4) |
| Bonus items | B4 interoperability done (19 of 19 replayed cases agree on desktop and on Android unit tests, 6 of 6 on an emulator); B6 model import done on both clients (Android button in Device Settings); B7 and B8 measured on one phone on 2026-10-24 (best p95 74-92 ms per frame at 2 threads, 20 ms target missed; section 8) |
| Headline weakness | every synthetic attack species is refused, but the attacks are software-made: the screen-pattern check that now stops the adaptive screen replay (SYN-A7, 4 of 8 accepted before it) is proven on synthetic screens only, and frames enlarged by 1.5x or a whole number before the engine could look like a screen to it. The dim clip passes since the exposure correction; real dim rooms are untested (section 6) |
| Evaluation with real people and artefacts | run on 2026-10-24/25 ([results](../eval/results/real-faces-2026-10-24.md), section 7.0): genuine 8 of 8 phone-camera transactions accepted (BPCER 0/8); 0 of 32 attack transactions accepted (P1 print 16, P4 phone photo 8, P6 video replay 8); P4 APCER 0/4, 28 transactions ended at the quality gate with no decision (APNRR); OnePlus CPH2569 instrumented suite 6 of 6 |

## 2. Unit and component tests

Commands run from the repository root in Git Bash (Windows) or bash (Linux), after the setup of
[../install/README.md](../install/README.md) section 1: JDK 11 with JavaFX as the default `JAVA_HOME`, JDK 21 where the
row says so, Maven 3.9, and the Maven mirror settings file that the repository ships as `scripts/release/maven-settings.xml`.
Set it once per shell with `export MAVEN_ARGS="-s $PWD/scripts/release/maven-settings.xml"` (from the repository root). The
counts below were taken on the project laptop (Windows 11, 12 threads, 16 GB, other builds running at the same time).
The last column names the job of `.github/workflows/ci.yml` that runs the same command on a clean `ubuntu-latest`
machine at every push and pull request. The hosted re-run of 2026-10-14 on `86799c8` passed every job except `android-unit` (see the list below the
totals); the hosted result for the final commit will be recorded after the push. "Upstream" means tests that came with the imported MOSIP
code and still pass with this project's changes.

| Module | What it tests | Command | Tests | Failures / errors | Skipped | CI job |
|---|---|---|---|---|---|---|
| `liveness/liveness-core` | engine: session state machine, passive decision, challenges, action detection, integrity, planarity, capture binding, policy, audit, UI states, MJPEG reader | `mvn -B -f liveness/pom.xml install -Dliveness.benchmark.frames=0` (the job runs `verify`; `install` also puts the engine in `~/.m2`, which the rows below need) | 376 | 0 | 0 | `liveness` |
| `liveness/liveness-onnx` | ONNX adapters, golden outputs of the 5 models, signed model registry, engine factory, execution providers, exposure correction and screen-pattern check, shared model-bundle importer and store | same run | 127 | 0 | 2 | `liveness` |
| `liveness/face-match-sdk` | SFace face-match SDK behind MOSIP's `IBioApiV2`, ISO 19794-5 records, MOSIP loader | same run | 44 | 0 | 0 | `liveness` |
| desktop `registration-services` (liveness and audit tests) | capture and authentication gates, stream session, orchestrator, config, audit sink, packet evidence, pass tokens, model import wiring, behaviour on repeated failure, account lock | after the engine install: `mvn -B -f clients/desktop/registration/pom.xml test -pl registration-services,registration-client -am -Dgpg.skip -Djacoco.skip=true -DfailIfNoTests=false "-Dtest=io/mosip/registration/service/bio/liveness/*Test.java,**/AuditFactoryTest.java,io/mosip/registration/controller/liveness/*Test.java"` (on Linux, put `xvfb-run -a` in front: the JavaFX tests need a display) | 159 | 0 | 0 | `desktop-liveness` |
| desktop `registration-client` (liveness tests) | liveness panel view model, texts and replacement texts, bundle parity (en/fr/ar), JavaFX listener, diagnostic lines, model import window | same run | 64 | 0 | 0 | `desktop-liveness` |
| Android `livenessmanager` | engine loader, config, audit sink, face authenticator, capture screen (Robolectric), image decoding, SBI codec, replay, supervisor override, metrics, diagnostic mode, execution provider | with JDK 21 as `JAVA_HOME`, Flutter 3.10.4 and the Android SDK of [../install/README.md](../install/README.md) section 1: `bash scripts/android-build.sh --test-only` (installs the engine, generates the code a fresh clone lacks, runs the three `testDebugUnitTest` tasks) | 216 | 0 | 0 | `android-unit` |
| Android `app` | capture gate, face-auth flow, capture launcher, Flutter bridge, supervisor override flow | same run | 78 | 0 | 0 | `android-unit` |
| Android `clientmanager` (upstream + liveness) | packet building with `LIVENESS_*` evidence, meta info; all upstream tests | same run | 1,186 | 0 | 0 | `android-unit` |
| Android Flutter widgets | face-auth panel, outcome banner, Arabic right-to-left, model import panel | after the Android run above: `cd clients/android && flutter test test/liveness/` (on Windows with the clone off drive C:, first set `PUB_CACHE` to the folder the build log names, so the packages it fetched are reused) | 35 | 0 | 0 | `android-unit` |
| `tools/mock-sync` | sync endpoints, Android contract (key wrapping), response signatures, packet inspector | `mvn -B -f tools/mock-sync/pom.xml verify` | 41 | 0 | 0 | `mock-sync` |
| `devices/mock-mds` (upstream + extensions) | frame sources, capture from the streamed frame, faults, renewed FTM keys | with JDK 21: `mvn -B -f devices/mock-mds/pom.xml package -Dgpg.skip=true` | 228 | 0 | 0 | `mock-mds` |
| `tools/eval/scenario-runner` | scenario loading, playlists, classification, output | after the engine install: `mvn -B -f tools/eval/scenario-runner/pom.xml package` | 31 | 0 | 0 | `scenario-runner` |
| `tools/eval` Python | APCER/BPCER, confidence intervals, frame-pack manifests | `pip install -r tools/eval/requirements.txt`, then `python -m pytest -q tools/eval/tests` | 121 | 0 | 0 | `eval-python` |
| **Total** | | | **2,706, run on 2026-10-25** | **0** | **2** | |

**Totals.** On 2026-10-25, after the maintainer-review fixes, every suite above except the Flutter widgets
(not changed, 35 from 2026-10-14) ran again: 2,699 tests, 0 failures, 0 errors, 2 benchmark tests skipped
(`scripts/test-all.sh`, the desktop command, `scripts/android-build.sh --test-only`). New since 2026-10-14: the session
close race test (core 376), the fail-closed signing-key test (`liveness-onnx` 126 again), three desktop tests (158) and
the Flutter-reply and corrupt-counter tests (`app` 75). The second fix round of the same evening (16:50-17:45)
re-ran the engine, scenario runner, desktop and Android suites: **2,706 tests, 0 failures, 0 errors**. New: the PAD
helper-pool close test (`liveness-onnx` 127), the model-store default test (desktop 159), the CameraX start-error
and attempt-text tests (`livenessmanager` 216) and the officer-face and missing-registration tests (`app` 78);
MockSync, MockMDS and the Python tools did not change and keep the counts of the first run.

The verification run of 2026-10-14 (19:46-20:03, logs outside the repository) ran every suite of the
table on the final code of the PS04 completion pass and found **2,691 tests, 0 failures, 0 errors**, 2 benchmark
tests skipped. New since 2026-10-10: the online model update (`liveness-onnx` 115 to 126, MockSync 40 to 41, desktop
154 + 63 to 155 + 64, Flutter 32 to 35), the MockMDS replay `pingpong` and `resume` (226 to 228), the Android replay
and model-update tests (212 to 214) and the recording ingest tests (103 to 120). The first run of that evening failed
`LivenessParityTest` on desktop and Android: the default pool without `LOOK_UP` changes the seeded challenge draws,
so the parity file was re-recorded (decisions and reasons unchanged). The Flutter tests needed the Pigeon code
generated again (`scripts/android-build.sh --test`, not `--test-only`, after a Pigeon change).

After the cleanup of 2026-10-15 `liveness-onnx` has 125 tests: the test of the deleted, unused `ImageOps.crop` went
with it. `scripts/test-all.sh` (engine, MockSync, MockMDS, scenario runner, pytest) passed on that commit. A fresh-clone
check the same night added one `tools/eval` Python test (the hand-session report keeps the model bundle label), so
that suite now has 121. If its
MockSync step fails while packaging, a MockSync started by a demo still holds `mock-sync.jar`; stop it and re-run.

**Upstream and team-written.** About 1,361 of the tests are upstream: the Android `clientmanager` suite (1,186, all upstream
tests, passing with this project's changes) and about 175 in MockMDS (an estimate from the first audit count; the 228 in the
table also include the tests added for the mock face device). The remaining 1,345 or so of the 2,706 are team-written (an
approximate figure).

Hosted CI:

- GitHub Actions (`.github/workflows/ci.yml`, hosted `ubuntu-latest`): the repository is public and `ci` runs on every push to `main`. The re-run of 2026-10-14 on `86799c8` (run 38079969255) passed six jobs (liveness engine, MockSync, MockMDS, scenario runner, evaluation pytest, desktop liveness tests) and failed the Android job at its first step: `sdkmanager: command not found`, because current runner images keep `sdkmanager` under `$ANDROID_HOME/cmdline-tools/latest/bin`. The workflow now puts that folder on `PATH`. On `0ef751a` (2026-10-25, run 40183409981) every job passed, the Android unit tests included; the DCO job runs on pull requests only and was skipped.

Not re-run for this page (results from the engineers' reports of the same day):

| Suite | Result | Source |
|---|---|---|
| desktop `registration-services` full suite (upstream + liveness) | 818 run, 0 failures, 4 errors, 17 skipped. The 4 errors are 2 upstream test fixtures with an expired certificate and 2 `BioServiceTest` cases that bind port 4501 while MockMDS runs. | [desktop-e2e.md](desktop-e2e.md) section 6 |
| Android instrumented tests on the emulator (`:livenessmanager:connectedDebugAndroidTest`) | 5 run, 0 failures (bundled models verify against the signed manifest; Android Keystore signing; one replay session end to end) | `docs/design/android-liveness.md` section 8 |

**Coverage** (JaCoCo, from the `verify` run above):

| Module | Instructions | Lines | Branches |
|---|---|---|---|
| `liveness-core` | 92.9% | 93.2% | 79.4% |
| `liveness-onnx` | 94.6% | 93.3% | 79.3% |
| `face-match-sdk` | 96.0% | 96.4% | 83.0% |

No coverage figure is measured for the client modules.

## 3. Automated liveness and attack scenarios

`tools/eval/scenario-runner` plays recorded frame packs into the real engine with the real ONNX models, on a stream
clock with seeded challenge order, so a re-run gives the same decisions. 75 scenarios in `eval/scenarios/`:
10 bona fide (three people from openly licensed clips, S1-S3), 58 attack (synthetic attack packs made in
software from those clips, now including the supervisor workflows and long loops), 7 quality and behaviour cases.
The tables from section 3.1 on are the 39-scenario suites of 2026-09-30, kept as dated history. Full method: [../eval/README.md](../eval/README.md).

**These are technology regression results on synthetic attacks, not an ISO/IEC 30107-3 evaluation.**

### 3.0 Current run: 2026-10-14 at the shipped defaults

At the shipped defaults (`ALWAYS_ACTIVE` for every workflow that matches a face) **no attack transaction is
accepted**: APCER 0.0% per transaction for every species and workflow. The same 275 transactions at `PASSIVE_THEN_ACTIVE`, the passive-first flow of the problem
statement, accept a print and looped clips that score above the passive threshold. Counts, BPCER and per-species table,
method and limits: [../eval/results/20261014-shipped/README.md](../eval/results/20261014-shipped/README.md).

History: the morning run of 2026-10-05 ([../eval/results/20261005-shipped/README.md](../eval/results/20261005-shipped/README.md))
accepted the adaptive screen replay SYN-A7 in 4 of 8 `registration` transactions and rejected 4 of 28 bona fide
`registration` transactions (the dim clip); the evening run
([../eval/results/20261005b-shipped/README.md](../eval/results/20261005b-shipped/README.md)), after the exposure
correction and the screen-pattern check ([../design/engine.md](../design/engine.md) section 4), brought both to 0.

### 3.1 Headline of 2026-09-30 (history)

The row **shipped (after)** below was the engine and defaults of 2026-09-30 after the engine fixes ([../eval/results/20260930-engine-fixes/](../eval/results/20260930-engine-fixes/)). The other rows are the
engine before those fixes ([../eval/results/20260930/](../eval/results/20260930/)); comparison in
[../eval/calibration-notes.md](../eval/calibration-notes.md) section 0.

| Suite | Configuration | APCER_max per attempt (worst species) | APCER pooled per attempt | APCER pooled per transaction | BPCER per attempt | BPCER per transaction |
|---|---|---|---|---|---|---|
| **shipped (after)** | new defaults: `ALWAYS_ACTIVE` for `registration`, `login_auth`, `onboard_auth`; `passive.min_frames=30`; `quality.min_sharpness=40`; engine fixes E1 and E3 | **25% (4/16)**, SYN-A7 | **1.7% (4/238)** | **4.9% (4/82)** | **29.4% (15/51)** | **10.0% (4/40)** |
| before: old shipped | old defaults (registration passive first, threshold 80) | **100% (8/8)**, SYN-A3 and SYN-A8 | 17.6% (32/182) | 39.0% (32/82) | 58.2% (39/67) | 30.0% (12/40) |
| before: strict | `policy.registration=ALWAYS_ACTIVE` | **25% (4/16)**, SYN-A7 | 3.5% (8/230) | 9.8% (8/82) | 68.0% (51/75) | 40.0% (16/40) |
| before: proposed | same configuration as shipped (after), old engine | **25% (4/16)**, SYN-A7 | 1.7% (4/238) | 4.9% (4/82) | 66.7% (48/72) | 40.0% (16/40) |

- APCER: share of attack attempts accepted. BPCER: share of genuine attempts rejected. A transaction is up to 3
  attempts. Intervals (95% Clopper-Pearson) are in each `report.md`; with 8-48 attempts per species they are wide.
- Before the fixes, BPCER was dominated by one engine defect: every head turn to the left by subject S2 was
  misread as a smile (`WRONG_ACTION`). Fixed: TURN_LEFT now completes 15/15.
- Now 12 of the 15 rejected bona fide attempts, and all 4 rejected bona fide transactions, come from one synthetic
  very dim pack (`bf-s3-dim`, passive score 3). The other 3 are S2 attempts whose capture frame is still turned
  40-65 degrees (`CAPTURE_PAD_SCORE`); each is followed by an accepted retry. In normal light (C1), 36 of 39
  bona fide attempts and 36 of 36 transactions are accepted.

### 3.2 Per attack species, per attempt (2026-09-30 suites)

| Species | What it is | before: old shipped | before: strict | before: proposed | **shipped (after)** | Layer that stops it |
|---|---|---|---|---|---|---|
| SYN-P0 | stock MockMDS still image | 0/6 | 0/6 | 0/6 | 0/6 | frame integrity (`FROZEN`) |
| SYN-A1 | injected still (identical frames) | 0/12 | 0/12 | 0/12 | 0/12 | frame integrity (`FROZEN`) |
| SYN-A2 | hand-held print | 8/32 | 4/40 | 0/48 | 0/48 | challenges (timeout) |
| SYN-A3 | print moved in 2-D | **8/8** | 0/24 | 0/24 | 0/24 | challenges |
| SYN-A4 | print tilted to fake a turn | 0/12 | 0/12 | 0/12 | 0/12 | challenges |
| SYN-A5 | photo on a screen | 0/24 | 0/24 | 0/24 | 0/24 | passive PAD or challenges |
| SYN-A6 | video replay on a screen | 4/28 | 0/36 | 0/36 | 0/36 | passive PAD or challenges |
| SYN-A7 | adaptive replay (clip answers the prompt) | 4/16 | 4/16 | **4/16** | **4/16** | passive PAD for S2; S3 passes every layer |
| SYN-A8 | looped 1.3 s clip | **8/8** | 0/24 | 0/24 | 0/24 | loop detection during challenges |
| SYN-A9 | genuine clip of the wrong action | 0/36 | 0/36 | 0/36 | 0/36 | challenges (`WRONG_ACTION`) |

### 3.3 Quality and behaviour scenarios (shipped (after) suite of 2026-09-30)

| Scenario | Result | Expected |
|---|---|---|
| `other-no-face` | no response (`NO_FACE`), no attempt counted | yes |
| `other-two-faces` | no response (`MULTI_FACE`), no attempt counted | yes |
| `other-too-dark` | no response (`TOO_DARK`) | yes |
| `other-stream-ends` | no response (device error) | yes |
| `other-s1-wrong-action`, `other-s3-ignores-prompt` | rejected (`CHALLENGE_TIMEOUT`) | yes |
| `other-blurred` | no response (`BLURRY`, then `PASSIVE_TIMEOUT`), no attempt counted, final message `liveness.error.poor_quality` | yes (before the fixes: rejected as `NEUTRAL_TIMEOUT` after a prompt) |

## 4. End-to-end runs on the real clients

- **Desktop** ([desktop-e2e.md](desktop-e2e.md)): real MOSIP desktop Registration Client with MockSync and MockMDS,
  faces from frame packs (no webcam), `scripts/demo-desktop.sh --restore-golden`, about 10 minutes. Reference run of
  2026-09-30: **41 of 41 checks passed at the old defaults** (every workflow passive first); flows in
  [desktop-e2e.md section 3.1](desktop-e2e.md#31-scripted-run-reference-run-all-checks-passed). Run of 2026-10-17 at
  the shipped policy with the challenge pool cut to blink and smile, from a client onboarded by `--onboard`: 71 of
  71 (2026-10-15: 69 of 71; 2026-10-14: 5 of 16, stopped by a MockMDS iris quality below the client's threshold,
  since fixed).
- **Android** ([android-e2e.md](android-e2e.md)): real MOSIP Android Registration Client (debug APK) on an Android 14
  x86_64 emulator with MockSync, faces through the app's debug replay, `scripts/demo-android.sh`, about 30 minutes.
  Reference run 7 of 2026-09-30: **58 of 58 checks passed at the old defaults**; flows in
  [android-e2e.md section 3](android-e2e.md#3-results). Run 8 found one real weakness: a printed photo passed the
  passive check under the passive-first registration policy of that time; with `ALWAYS_ACTIVE` it was refused 0/12
  ([android-e2e/print-attack-trials.txt](android-e2e/print-attack-trials.txt)). Run of 2026-10-17 at the shipped
  policy with the pool cut to blink and smile: 59 of 74
  ([android-e2e.md section 15](android-e2e.md#15-re-run-with-the-host-gpu-2026-10-17-1706-to-1749-ist); 2026-10-14:
  55 of 75).
- Turn challenges and the longer flows at the shipped policy need a live face or longer recordings.

## 5. Latency

| Measurement | Platform | Result | Source |
|---|---|---|---|
| Engine start (models verified and loaded) | desktop client | 1,079-1,271 ms | client log |
| Engine start | Android emulator | 1,069-1,138 ms | Logcat |
| Per analysed frame, 640x480, decode included, quieter machine | desktop JVM (scenario runner) | p50 21-23 ms, p95 38-45 ms, max 98-121 ms | calibration notes section 5 |
| Per analysed frame, same, loaded machine (about 25 other Java processes) | desktop JVM | p50 25-75 ms, p95 118-154 ms | calibration notes section 5 |
| Per analysed frame, passive-only attempts | Android x86_64 emulator, CPU | p50 57-251 ms, p95 295-998 ms | [android-e2e.md](android-e2e.md) section 5 |
| Per analysed frame, attempts with challenges | Android emulator | p50 37-45 ms, p95 106-374 ms | same |
| Time to a passive decision | stream clock (replay) | 2.0 s (30 frames, shipped); 0.53 s with the old 8 frames | calibration notes 0.2 |
| Accepted bona fide `registration` attempt, median | stream clock | 11.0 s under the shipped defaults (2 challenges on every capture, 2026-10-14); 6.6 s under the old defaults (2026-09-30) | [../eval/results/20261014-latency/README.md](../eval/results/20261014-latency/README.md); calibration notes 0.2 |
| Per analysed frame, shipped suite, two suites in parallel on a loaded machine | desktop JVM | p50 34.1 ms, p95 78.4 ms | calibration notes 0.3 |
| Per analysed frame, shipped engine, 1 ONNX Runtime thread, shared laptop (Intel Core i5-12450H) at 51-69% load (2026-10-20) | desktop JVM (scenario runner) | p50 39.4 ms, p95 127.7 ms, max 283.4 ms (n=4410) | [../eval/results/20261020-latency/README.md](../eval/results/20261020-latency/README.md) |
| Passive pass, user-visible | Android emulator | 2.2-3.0 s; with 2 challenges 7.4-12.1 s | android-e2e section 5 |

- The design target of p95 at most 20 ms per frame is **not met** on either platform (T-PERF-01). Face analysis (YuNet plus
  Face Mesh on every frame) is two thirds of the frame. Running the mesh only during challenges (proposal E4) is not
  safe without a new calibration: the passive stage's quality gate and stored-frame choice use mesh values
  ([../design/engine.md](../design/engine.md) section 10).
- No idle-machine measurement and no physical Android device yet; the phone measurement kit is described in [android-performance.md](android-performance.md) and has not been run on a phone.

## 6. Known limitations

| # | Limitation | Effect | Where described |
|---|---|---|---|
| 1 | The screen-pattern check that refuses the adaptive screen replay (SYN-A7, 50% per transaction before it) is measured on software-made screens only; a camera frame enlarged by 1.5x or a whole number before the engine shows the same kind of frequency peaks | real-screen APCER unknown until the volunteer test; a client or device that upscales frames could see false rejections (none in the suite: every bona fide score unchanged) | [../design/engine.md](../design/engine.md) section 4; [../eval/results/20261005b-shipped/README.md](../eval/results/20261005b-shipped/README.md) |
| 2 | Dim light is tested only with the synthetic pack `bf-s3-dim` (45% brightness) | with the exposure correction it passes (score 55, BPCER 0/28; it was 14.3% before). A real dim room with an auto-exposure camera is untested; faces that still fail end with "Improve lighting." without counting an attempt, up to 3 times per capture | [../eval/results/20261005b-shipped/README.md](../eval/results/20261005b-shipped/README.md); calibration notes, update of 2026-10-05 (evening) |
| 3 | Every registration, sign-in, onboarding and supervisor authentication now answers 2 challenges | median accepted `registration` attempt 11.0 s (2026-10-14) instead of 6.6 s (old defaults); one synced key per workflow switches back to passive first | section 5; `design/decision-flow.md` section 5 |
| 4 | A blink already in progress at the prompt fails (`ONSET_TOO_FAST`); kept on purpose against replays that blink continuously | the person blinks again (0 cases in the shipped suite, 3 under the old configuration) | calibration notes 0.1, 4.7; desktop 5.3 |
| 5 | The capture frame can be the last streamed frame while the head is still turned | 3 of 15 S2 attempts fail `CAPTURE_PAD_SCORE`, each followed by an accepted retry | calibration notes 0.2 |
| 6 | The supervisor override can still be granted after challenge timeouts alone (it is refused for the whole transaction after any PAD, integrity or binding failure, also across windows). On the desktop it now needs a second supervisor (user ID, face check valid 60 s) and a photo with one face and a passive PAD score | the capture is marked `OVERRIDE` in the packet for review; the 2026-09-30 run (desktop 5.2) accepted a print this way before the second-person rule; the new flow has unit tests but was not run on a screen | desktop 5.2; `design/android-liveness.md` 13.1 |
| 7 | Android: built-in camera as L0; a vendor SBI face app is routed through intents (no preview frames, so a configured policy decides: block, or capture only) and a vendor engine can be loaded by ID, both tested with Robolectric only, never with a vendor app; no L1; CameraX path not run on a phone | Android tested with replayed frames on an emulator | Android 8.5; traceability O1 |
| 8 | Offline simulated (client-side network cut), not by stopping the server | same client-side failure as a real outage | desktop 5.5; Android 8.6 |
| 9 | Per-frame latency above target; no physical tablet or phone measured; accelerators (XNNPACK, NNAPI) selectable, the measurement kit exists but has not been run on a phone | see section 5 | section 5; [android-performance.md](android-performance.md); calibration notes 0.4 |
| 10 | Under heavy CPU load the inference watchdog (3 inferences over 500 ms) can end a session with `ENGINE` / `INFERENCE_TIMEOUT` | the person retries; no attempt is used | desktop 5.4; `design/android-liveness.md` 13.5 |
| 11 | The stored resident photo can show the challenge expression (smile) | image quality for the ID record | Android 8.2 |
| 12 | SFace match threshold 40 chosen from published values, not calibrated on a larger set | match error rates unknown | desktop 5.10; `liveness/face-match-sdk/README.md` |
| 13 | All attack data is synthetic; bona fide data is three people from stock clips | numbers are regression figures, not security claims | section 3 |
| 14 | The full end-to-end client runs (section 4) were made at the old defaults; at the shipped policy only the runs with the pool cut to blink and smile exist (desktop 71 of 71 and Android 59 of 74 on 2026-10-17) | turn challenges and the longer flows at the shipped policy are untested on the clients; they need a live face or longer recordings | `design/android-liveness.md` 13.8 |
| 15 | Real-person frames (2026-10-24/25) went through the engine and, on a physical phone, through the instrumented tests; the full desktop and Android client runs of section 4 use replayed frames | sections 3 and 4 are replayed frames; section 7.0 adds the real-person run | section 7 |

Closed on 2026-09-30: prints and looped clips passing the passive check (old #1), head turns to the left misread as
a smile (old #3), blurred frames reaching the challenges. Closed on 2026-10-05: the Android import button (old #15,
Settings, Device Settings, with a BUSY check while a capture screen is open).

## 7. In-house evaluation with volunteers (ISO/IEC 30107-3 method) — placeholder

Sections 7.1 to 7.7 are filled after the volunteer capture sessions described in [../eval/protocol.md](../eval/protocol.md).
Title of the final report: "In-house PAD evaluation following ISO/IEC 30107-3:2023 methodology: basic
attack potential".

### 7.0 Real faces and real artefacts, 2026-10-24 and 25

Full page: [../eval/results/real-faces-2026-10-24.md](../eval/results/real-faces-2026-10-24.md). Shipped engine and
configuration; frames cut from recordings of two team members (S1, S2) and of printed, phone-screen and replayed faces
(S1-S5, with consent); team-run, not an ISO/IEC 30107-3 certification and not an accredited evaluation.

| Measure | Result |
|---|---|
| Genuine, phone camera, C1 (S1, S2) | 8 of 8 transactions accepted (BPCER 0/8); 4 of 12 attempts refused with `INTEGRITY` / `FACE_SWITCH`, all in transactions that drew `LOOK_DOWN`, which is outside the shipped pool; each passed on a later attempt |
| Genuine, laptop camera (S2) | 2 of 4 transactions accepted; both refusals `BLINK` `NOT_HELD` (the clip set has a natural, short blink and no deliberate blink clip) |
| Attacks | 0 of 32 transactions accepted: P1 print 16, P4 phone photo 8, P6 video replay 8 |
| Refused by passive PAD | 4 (P4 photo of S2, passive score 0, `PAD_SCORE`); P4 APCER 0/4 |
| No decision (quality gate), APNRR | 28 (`FACE_TOO_SMALL`, one group `BLURRY`); not counted as PAD refusals |
| Per-frame time on the laptop | p50 12.1 ms, p95 18.5 ms (phone clips); p50 17.2 ms, p95 25.7 ms (laptop clips) |
| Physical phone, OnePlus CPH2569, Android 15 | `livenessmanager` instrumented suite 6 of 6 |

### 7.1 Item under test

| Field | Value |
|---|---|
| Engine, model set SHA-256, configuration hash | _to fill at freeze_ |
| Clients and builds | _desktop build, APK_ |
| Capture stations and cameras | _to fill_ |
| Dates, testers and their roles | _to fill_ |

### 7.2 Subjects and conditions

| Split | Subjects | Bona fide transactions (C1 office, C2 dim, C3 backlit, C4 side light, C5 glasses) |
|---|---|---|
| Development (threshold choice only) | S1-S4 | _n_ |
| Test | S5-S12 | _n_ (planned 200) |

### 7.3 Attack results per species (test split)

| PAIS | Recipe | Attempts | Accepted | APCER per attempt (95% CI) | APCER per transaction | Layer |
|---|---|---|---|---|---|---|
| P1 | A4 matte print, held flat | | | | | |
| P2 | glossy print, curved | | | | | |
| P3 | print with eye/mouth cut-outs | | | | | |
| P4 | photo on a phone screen | | | | | |
| P5 | photo on a laptop or tablet screen | | | | | |
| P6 | recorded video replayed | | | | | |
| P7 | adaptive replay (clip per prompt) | | | | | |
| P8 (optional) | paper mask | | | | | |

**APCER_max:** _to fill_.

### 7.4 Bona fide results and timing

| Condition | Transactions | BPCER (95% CI) | BPNRR | Share that needed challenges | Median time to pass |
|---|---|---|---|---|---|
| C1-C5 | | | | | |

### 7.5 Full-system (operator login with an impostor artefact)

| PAIS | Impostor presentations | IAPAR | Genuine logins | FRR |
|---|---|---|---|---|
| P1, P4, P6 | | | | |

### 7.6 Android replication

| PAIS | Attempts | APCER | Bona fide | BPCER |
|---|---|---|---|---|
| P1, P4, P6 | | | | |

### 7.7 Limitations (verbatim, numbers to fill)

- "Results were obtained by the development team on N subjects and M attack presentations with
  basic-attack-potential artefacts."
- "Per ISO/IEC 30107-3, non-zero APCER only proves an attack can succeed, and zero observed errors bound but do not
  prove security (one-sided 95% upper bound X%)."
- "Error rates are not comparable to third-party lab results. Subjects are not demographically representative."
- "High-attack-potential PAIs (3D masks), deepfakes and injection attacks were not tested. The mock L0 device
  provides no hardware trust."

## 8. Bonus items

| Item | State on 2026-10-05 | Evidence |
|---|---|---|
| B4 interoperability: one ONNX Runtime release on both clients and a desktop/Android parity test | **done.** Desktop and Android both use ONNX Runtime 1.20.0 (Android moved down from 1.28.0 because the desktop runtime cannot go past 1.20.0). The same replayed cases give identical decisions: desktop 19 of 19, Android unit tests 19 of 19, emulator `arc_api34` 6 of 6 committed cases; maximum score difference 0 points over 98 scores. Not covered: a physical phone (arm64 library untested), the session threading and screens, a Linux CI run | [interoperability.md](interoperability.md) |
| B6 offline model update on Android | **done on both clients** (desktop earlier). Same checks as the desktop import: signature, per-file SHA-256, anti-rollback, self-test, atomic switch, previous bundle kept, audit events LV13 to LV15. Tests: `ModelBundleImporterTest` 15 of 15 and the loader tests. The "Import liveness model" button in Settings, Device Settings calls it (seen on the emulator, [picture](../ux/screenshots/android/a-s1-model-import-button.png)). **Not done:** no import run on an emulator or phone | [../install/android.md](../install/android.md#10-import-a-liveness-model-bundle-offline-model-update), [../design/android-liveness.md](../design/android-liveness.md#14-offline-model-update-bonus-b6) |
| B7 and B8 latency and memory on a physical phone | **measured on 2026-10-24.** OnePlus CPH2569, Android 15, charging at about 40% battery (may throttle), two runs of CPU, XNNPACK and NNAPI over 1, 2 and 4 threads, 100 frames after 20 warm-up each. Best is CPU or XNNPACK with 2 threads: p50 58-71 ms, p95 74-92 ms per frame (target 20 ms missed); NNAPI is 5-8 times slower; 4 threads are no faster than 2; peak PSS 239-338 MB. One phone only | [android-performance.md](android-performance.md) |

The phone measurement covers one phone. Real-person results: [section 7.0](#70-real-faces-and-real-artefacts-2026-10-24-and-25).
