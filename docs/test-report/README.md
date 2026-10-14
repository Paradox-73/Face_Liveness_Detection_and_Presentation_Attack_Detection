# Test report (PS04 face liveness and PAD)

One page that collects every test result of the project: unit tests per module, the automated liveness and attack
scenarios, the end-to-end runs on the real desktop and Android clients, latency, and known limits. The details
stay in the linked reports; requirement-by-requirement status is in [../traceability.md](../traceability.md).

| Report | What it covers |
|---|---|
| [desktop-e2e.md](desktop-e2e.md) | Real desktop Registration Client against MockSync (mock MOSIP server) and MockMDS (mock face device): 13 scripted flows, 3 interactive flows, audit and packet evidence |
| [android-e2e.md](android-e2e.md) | Real Android Registration Client on an Android 14 emulator against MockSync: 16 flows, latency, APK size Re-run at the shipped defaults 2026-10-06: attacks 11 of 11, resident 7 of 12, login 3 of 10 (genuine-person steps need turn and look-up footage and clips that do not repeat). Desktop re-run 2026-10-05: stopped at the face sign-in (repeated replay clips caught by the loop check), no screenshots retaken |
| [interoperability.md](interoperability.md) | Desktop and Android give the same liveness decision: 19 replayed cases through each client's engine loader, configuration and decoder, one ONNX Runtime release (1.20.0) on both (bonus B4) |
| [android-performance.md](android-performance.md) | Per-frame latency and memory of the engine on a physical Android phone (bonus B7 and B8): the measurement kit is built, the phone table is still empty |
| [../install/android.md](../install/android.md#10-import-a-liveness-model-bundle-offline-model-update), [../design/android-liveness.md](../design/android-liveness.md#14-offline-model-update-bonus-b6) | Offline import of a signed model bundle on Android (bonus B6): how to run it and how it works; unit tests only, no phone run |
| [../eval/calibration-notes.md](../eval/calibration-notes.md) | Automated suite: the real engine and models over replayed scenarios, ISO/IEC 30107-3-style metrics |
| [../eval/results/20261005-shipped/README.md](../eval/results/20261005-shipped/README.md) | The scenario suites re-run on 2026-10-05 at the shipped defaults, in the legacy configuration, and the latency run |
| [../traceability.md](../traceability.md) | Every PS04 requirement → code → test → status |
| [../test-data.md](../test-data.md) | Demo users, machine registration, frame packs |

## 1. Summary

| Area | Result |
|---|---|
| Unit and component tests | **2,653 tests, 0 failures, 0 errors** in the verification run of 2026-10-10 (23:14) over 13 test suites (section 2) |
| Automated liveness and attack scenarios | re-run on 2026-10-05 evening at the shipped defaults, after the exposure correction and the screen-pattern check: 275 transactions, 755 attempt records (section 3.0). **APCER 0.0% per transaction for every species and workflow (SYN-A7 on `registration` was 50.0%, 4/8, in the morning run). Bona fide: `registration` 0.0% (0/28; was 14.3%, all from the dim clip), `login_auth` 0.0% (0/12)** |
| Desktop end-to-end | reference run 2026-09-30: 41 of 41 checks passed **at the old defaults** (all workflows passive first); not re-run at the shipped defaults (section 4) |
| Android end-to-end | reference run 2026-09-30: 58 of 58 checks passed **at the old defaults**; a repeat run found one real weakness; the full demo was not repeated at the shipped defaults (section 4) |
| Bonus items | B4 interoperability done (19 of 19 replayed cases agree on desktop and on Android unit tests, 6 of 6 on an emulator); B6 model import done on both clients (Android button in Device Settings); B7 and B8 measurement kit done, **phone measurement pending** (section 8) |
| Headline weakness | every synthetic attack species is refused, but the attacks are software-made: the screen-pattern check that now stops the adaptive screen replay (SYN-A7, 4 of 8 accepted before it) is proven on synthetic screens only, and frames enlarged by 1.5x or a whole number before the engine could look like a screen to it. The dim clip passes since the exposure correction; real dim rooms are untested (section 6) |
| Evaluation with real people and artefacts | not run yet; placeholder in section 7 |

## 2. Unit and component tests

Commands run from the repository root in Git Bash (Windows) or bash (Linux), after the setup of
[../install/README.md](../install/README.md) section 1: JDK 11 with JavaFX as the default `JAVA_HOME`, JDK 21 where the
row says so, Maven 3.9, and the Maven mirror settings file that the repository ships as `.github/maven-settings.xml`.
Set it once per shell with `export MAVEN_ARGS="-s .github/maven-settings.xml"` (from the repository root). The
counts below were taken on the project laptop (Windows 11, 12 threads, 16 GB, other builds running at the same time).
The last column names the job of `.github/workflows/ci.yml` that runs the same command on a clean `ubuntu-latest`
machine at every push and pull request. The hosted re-run of 2026-10-14 on `86799c8` passed every job except `android-unit` (see the list below the
totals); the hosted result for the final commit will be recorded after the push. "Upstream" means tests that came with the imported MOSIP
code and still pass with this project's changes.

| Module | What it tests | Command | Tests | Failures / errors | Skipped | CI job |
|---|---|---|---|---|---|---|
| `liveness/liveness-core` | engine: session state machine, passive decision, challenges, action detection, integrity, planarity, capture binding, policy, audit, UI states, MJPEG reader | `mvn -B -f liveness/pom.xml install -Dliveness.benchmark.frames=0` (the job runs `verify`; `install` also puts the engine in `~/.m2`, which the rows below need) | 375 | 0 | 0 | `liveness` |
| `liveness/liveness-onnx` | ONNX adapters, golden outputs of the 5 models, signed model registry, engine factory, execution providers, exposure correction and screen-pattern check, shared model-bundle importer and store | same run | 126 | 0 | 2 | `liveness` |
| `liveness/face-match-sdk` | SFace face-match SDK behind MOSIP's `IBioApiV2`, ISO 19794-5 records, MOSIP loader | same run | 44 | 0 | 0 | `liveness` |
| desktop `registration-services` (liveness and audit tests) | capture and authentication gates, stream session, orchestrator, config, audit sink, packet evidence, pass tokens, model import wiring, behaviour on repeated failure, account lock | after the engine install: `mvn -B -f clients/desktop/registration/pom.xml test -pl registration-services,registration-client -am -Dgpg.skip -Djacoco.skip=true -DfailIfNoTests=false "-Dtest=io/mosip/registration/service/bio/liveness/*Test.java,**/AuditFactoryTest.java,io/mosip/registration/controller/liveness/*Test.java"` (on Linux, put `xvfb-run -a` in front: the JavaFX tests need a display) | 155 | 0 | 0 | `desktop-liveness` |
| desktop `registration-client` (liveness tests) | liveness panel view model, texts and replacement texts, bundle parity (en/fr/ar), JavaFX listener, diagnostic lines, model import window | same run | 64 | 0 | 0 | `desktop-liveness` |
| Android `livenessmanager` | engine loader, config, audit sink, face authenticator, capture screen (Robolectric), image decoding, SBI codec, replay, supervisor override, metrics, diagnostic mode, execution provider | with JDK 21 as `JAVA_HOME`, Flutter 3.10.4 and the Android SDK of [../install/README.md](../install/README.md) section 1: `bash scripts/android-build.sh --test-only` (installs the engine, generates the code a fresh clone lacks, runs the three `testDebugUnitTest` tasks) | 214 | 0 | 0 | `android-unit` |
| Android `app` | capture gate, face-auth flow, capture launcher, Flutter bridge, supervisor override flow | same run | 72 | 0 | 0 | `android-unit` |
| Android `clientmanager` (upstream + liveness) | packet building with `LIVENESS_*` evidence, meta info; all upstream tests | same run | 1,186 | 0 | 0 | `android-unit` |
| Android Flutter widgets | face-auth panel, outcome banner, Arabic right-to-left, model import panel | after the Android run above: `cd clients/android && flutter test test/liveness/` | 35 | 0 | 0 | `android-unit` |
| `tools/mock-sync` | sync endpoints, Android contract (key wrapping), response signatures, packet inspector | `mvn -B -f tools/mock-sync/pom.xml verify` | 41 | 0 | 0 | `mock-sync` |
| `devices/mock-mds` (upstream + extensions) | frame sources, capture from the streamed frame, faults, renewed FTM keys | with JDK 21: `mvn -B -f devices/mock-mds/pom.xml package -Dgpg.skip=true` | 228 | 0 | 0 | `mock-mds` |
| `tools/eval/scenario-runner` | scenario loading, playlists, classification, output | after the engine install: `mvn -B -f tools/eval/scenario-runner/pom.xml package` | 31 | 0 | 0 | `scenario-runner` |
| `tools/eval` Python | APCER/BPCER, confidence intervals, frame-pack manifests | `pip install -r tools/eval/requirements.txt`, then `python -m pytest -q tools/eval/tests` | 120 | 0 | 0 | `eval-python` |
| **Total** | | | **2,691, verified on 2026-10-14** | **0** | **2** | |

**Totals.** The verification run of 2026-10-14 (19:46-20:03, logs outside the repository) ran every suite of the
table on the final code of the PS04 completion pass and found **2,691 tests, 0 failures, 0 errors**, 2 benchmark
tests skipped. New since 2026-10-10: the online model update (`liveness-onnx` 115 to 126, MockSync 40 to 41, desktop
154 + 63 to 155 + 64, Flutter 32 to 35), the MockMDS replay `pingpong` and `resume` (226 to 228), the Android replay
and model-update tests (212 to 214) and the recording ingest tests (103 to 120). The first run of that evening failed
`LivenessParityTest` on desktop and Android: the default pool without `LOOK_UP` changes the seeded challenge draws,
so the parity file was re-recorded (decisions and reasons unchanged). The Flutter tests needed the Pigeon code
generated again (`scripts/android-build.sh --test`, not `--test-only`, after a Pigeon change). The verification run of 2026-10-10 (23:14-23:20, logs `PS04-fixes/verify/w3-*.log` outside the
repository, after the maintainability restructuring of that evening: shared model-bundle importer, `PolicyKey`
table, one liveness entry point per client, file splits) ran every suite of the table together and found
**2,653 tests, 0 failures, 0 errors**, 2 benchmark tests skipped. The run earlier that day (21:50, after the
security and correctness fixes) found 2,647; the 2 store tests moved from the desktop to `liveness-onnx`, and 6
tests were added (default map pinned, `PolicyKey` ranges, store pruning, file-system rule). The run of 2026-10-05 evening found 2,619. The earlier runs of that day found 2,542 (morning) and 2,606 (04:20, before the exposure correction and the
screen-pattern check added 13 `liveness-onnx` tests).

**Upstream and team-written.** About 1,361 of the tests are upstream: the Android `clientmanager` suite (1,186, all upstream
tests, passing with this project's changes) and about 175 in MockMDS (an estimate from the first audit count; the 226 in the
table also include the tests added for the mock face device). The remaining 1,330 or so of the 2,691 are team-written (an
approximate figure).

When each count was taken:

- 2026-10-14, 19:46-20:03: every row again (2,691 tests), on the final code of the PS04 completion pass.

- 2026-10-05: all rows, verification run (engine installed first, then every suite with the commands above; Android tests forced with `--rerun`).
- 2026-10-05 evening: every row again in one run (2,619 tests), after the exposure correction and the screen-pattern check. JDK 11 for the engine, desktop, MockSync and runner; JDK 21 for Android and MockMDS.
- 2026-10-10, 23:14: every row again in one run (2,653 tests), after the maintainability restructuring.
- 2026-10-11, 00:55: `scripts/test-all.sh` (default steps engine, mocksync, mockmds, runner, eval): 375, 44, 40, 226 and 31 Java tests and 103 Python tests, 0 failures. A first run at 00:55 stopped at MockSync packaging because a MockSync started by the demo still held `mock-sync.jar`; it passed after that process was stopped.
- 2026-10-10, 21:50: every row again in one run (2,647 tests), after the security and correctness fixes (retry counts saved across restarts, configurable model-signing key, certification-level check, and others). Android unit tests: Gradle reused the results of the same sources from a run 7 minutes earlier with the same counts.
- GitHub Actions (`.github/workflows/ci.yml`, hosted `ubuntu-latest`): the repository is public and `ci` runs on every push to `main`. The re-run of 2026-10-14 on `86799c8` (run 38079969255) passed six jobs (liveness engine, MockSync, MockMDS, scenario runner, evaluation pytest, desktop liveness tests) and failed the Android job at its first step: `sdkmanager: command not found`, because current runner images keep `sdkmanager` under `$ANDROID_HOME/cmdline-tools/latest/bin`. The workflow now puts that folder on `PATH`. The hosted result for the final commit will be recorded here after the push.

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

### 3.0 Re-run on 2026-10-05 at the shipped defaults

**Evening run after the two PAD additions** (exposure correction in a dark frame, screen-pattern check;
[../design/engine.md](../design/engine.md) section 4), same scenarios and defaults, from
[../eval/results/20261005b-shipped/README.md](../eval/results/20261005b-shipped/README.md):

- APCER per transaction and on the first attempt: **0.0% for every species and workflow**; `atk-s3-adaptive-screen`
  (SYN-A7) is refused in 4 of 4 seeds by the passive PAD (score 0).
- BPCER per transaction and on the first attempt: `registration` **0.0% (0/28)**, `login_auth` 0.0% (0/12);
  `bf-s3-dim` passes on the first attempt with passive score 55 (was 3); the other bona fide scores are unchanged.
- 1 of 275 transactions misses its expectation (was 9): `other-s3-ignores-prompt` seed 2, refused by the loop check
  instead of the challenge layer.

The morning run below is the "before".

Quoted from [../eval/results/20261005-shipped/README.md](../eval/results/20261005-shipped/README.md) (copied from the
"Headline" section of `20261005-shipped/report.md`); the full per-species table, the legacy table and the notes are there.
Settings: loop window 300 frames, model bundle 1.3.0, dim-light ending, `ALWAYS_ACTIVE` for `registration`, `login_auth`,
`onboard_auth` and the three supervisor workflows, `passive.min_frames=30`, `quality.min_sharpness=40`. 275 transactions,
755 attempt records. A transaction ends at its first pass or its last allowed attempt. The report's intervals treat
transactions as independent, which they are not (replays of one artefact repeat the same frames), so read the counts first.

- APCER (share of attack transactions accepted), per transaction and on the first attempt: **0.0% for every species and workflow except `registration` SYN-A7: 50.0% (4/8)** in both columns.
- BPCER (share of genuine transactions wrongly rejected), per transaction and on the first attempt: `login_auth` 0.0% (0/12); `registration` 14.3% (4/28; 95% CI 4.0%-32.7%), all 4 are `bf-s3-dim`; the other three workflows have no bona fide scenarios.
- Worst species (APCER_max): `eod_auth`, `exception_auth`, `login_auth`, `packet_auth` 0.0% (0/8; 95% CI 0.0%-36.9%; one-sided 95% upper bound 31.2%); `registration` 50.0% (4/8; 95% CI 15.7%-84.3%), species SYN-A7. `atk-s3-adaptive-screen` is accepted in 4 of 4 seeds; `atk-s2-adaptive-screen` is rejected in 4 of 4. Source artefacts accepted: 1 of 2.
- Pooled attack figures (secondary, not the headline): `registration` 4.9% (4/82) per transaction and on the first attempt, 1.7% (4/238) per attempt; the other four workflows 0.0%.
- Passive score alone (DET): BPCER20 100.0% at threshold 101 for `registration` and for all workflows, 66.7% at threshold 100 for `login_auth`; the passive score does not separate genuine from attack here, the full engine does through challenge, integrity and planarity checks.
- IAPAR and FRR (error rates of the full system with face matching) are not computed: no record carries a match result.
- Legacy configuration (`PASSIVE_THEN_ACTIVE` for all six workflows, `passive.min_frames=8`, 275 transactions, 517 attempt records): pooled APCER per transaction `eod_auth` 50.0% (16/32), `exception_auth` 50.0% (16/32), `login_auth` 33.3% (4/12), `packet_auth` 71.4% (40/56), `registration` 63.4% (52/82); APCER_max 100.0% (80/80), species SYN-A8; 136 of 275 transactions did not meet the scenario expectation.
- Latency (shipped defaults, 1 ONNX Runtime thread, 24 bona fide `registration` transactions, all 24 accepted): per analysed frame n=4410, mean 79.7 ms, p50 69.4 ms, p95 162.4 ms, max 482.4 ms, on a shared laptop.
- Expectation check: **9 of 275 transactions** differ from the scenario expectation. `bf-s3-dim` (genuine clip in dim light, 4 seeds): expected ACCEPT, observed REJECT in all 4 (the runner of that run did not pass the low-light count; a later runner fix shows 3 `LOW_LIGHT` endings and then a counted `PAD_SCORE` failure, still REJECT, so the mismatch is not fixed). `atk-s3-adaptive-screen` (4 seeds): expected REJECT, observed ACCEPT. `other-s3-ignores-prompt` seed 2: listed because the last attempt ended in the integrity layer with reason `LOOP`.

This is technology regression data on synthetic attacks and three openly licensed clips, not an ISO/IEC 30107-3 evaluation.

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

### 4.1 Desktop ([desktop-e2e.md](desktop-e2e.md))

Real MOSIP desktop Registration Client with this project's integration, MockSync as server, MockMDS as face,
finger and iris devices, faces from frame packs (no webcam). `scripts/demo-desktop.sh --restore-golden`,
about 10 minutes. Reference run of 2026-09-30: **41 checks passed, 0 failed, at the old defaults** (every workflow passive first, supervisor workflows included). Not re-run at the shipped defaults (challenges on every capture, a second supervisor for the override, the shared session driver, bundle 1.3.0): that needs a desktop window and recordings of the enrolled face turning right and looking up, which do not exist yet.

| Flow | Result |
|---|---|
| D1 operator face login | passive pass, SFace match 100 (threshold 40), home screen |
| D2 device disconnect during capture | "The face camera was disconnected..." without using an attempt; Retry passed |
| D3 resident, passive pass; operator packet authentication | packet carries `LIVENESS_METHOD=PASSIVE`, score bucket for resident and officer |
| D4 low-confidence resident | passive score 74 < 80 → "Please blink" / "Please smile", pass, `PASSIVE_ACTIVE` in packet |
| D5 exception photo and reviewer (supervisor) authentication | reviewer face with liveness; third face record in the packet |
| D6 three attacks, then maximum attempts | print (challenge timeout), injected still (`FROZEN`), screen replay (`PAD_SCORE`), recovery message, one LV11 audit |
| D7 end-of-day supervisor approval | screen replay refused, live face passed, packets uploaded |
| D8 policy change by sync | `ALWAYS_ACTIVE` arrived by sync (audit LV15); the moving print was refused |
| D9 L1 face device | FTM-signed device accepted, `certification L1` in audit |
| D10 offline | login, capture, packet authentication and EOD liveness identical offline; MockSync received no request |
| D11 live wrong person | liveness passed, SFace match 11 refused |
| D12-D13 audit and packet evidence | LV events with buckets only; `LIVENESS_*` on every face record |
| I1-I3 (interactive) | operator onboarding with liveness; supervisor override marked `OVERRIDE` in the packet; SFace SDK selected for FACE |

Problems found by the runs and fixed: lost face-login audits, a doubled LV11 audit, "camera not available" during a
device rescan, fresh attempts after reopening the window, MockSync packet-status answer, expired MockMDS FTM root.

### 4.2 Android ([android-e2e.md](android-e2e.md))

Real MOSIP Android Registration Client (debug APK) with the `livenessmanager` module, Android 14 x86_64 emulator,
MockSync, faces from frame packs through the app's debug replay. `scripts/demo-android.sh`, about 30 minutes.
Reference run 7 of 2026-09-30: **58 checks passed, 0 failed, at the old defaults.** A full demo at the shipped defaults has not been repeated; the demo script still needs a follow option and the same missing turn and look-up recordings.

| Flow | Result |
|---|---|
| A1-A2 password and face sign-in | face sign-in: passive pass, SFace match 97 |
| A3 live wrong person | challenges answered, match 13 refused |
| A4 screen replay at sign-in | challenge timeout, generic message |
| A5 camera unavailable | device message, no attempt used, Retry recovered |
| A6-A7 resident: replay refused, then low-confidence resident | `FAIL_PAD`, then challenges and pass |
| A8 exception photo | no liveness (policy OFF), photo stored |
| A9 packet authentication by face | match 98, officer face and liveness evidence in the packet |
| A10 three attacks, maximum attempts | count continues across screens; capture blocked for the registration |
| A11 supervisor approval by face | replay refused, live face passed, applications approved and uploaded |
| A12 policy change by sync | `ALWAYS_ACTIVE` applied; challenges even for a high passive score |
| A13 offline | sign-in, capture, packet authentication and supervisor approval offline; upload after reconnection |
| A14-A16 audit, packet evidence, latency | same LV event IDs and `LIVENESS_*` keys as desktop |

- **Run 8** (same day, final script): 48 of 57 checks. The 9 failures are one real finding: the printed photo
  `a-s2-print-still` passed the passive check (score bucket 80-89) under the registration policy shipped at the time (passive first; `ALWAYS_ACTIVE` has been the default since the afternoon of 2026-09-30).
  Repeated on the capture screen: `a-s3-print-still` accepted 6/6, `a-s2-print-still` 1/6 with the old
  policy; 0/12 with `ALWAYS_ACTIVE` ([android-e2e/print-attack-trials.txt](android-e2e/print-attack-trials.txt)).
- Problems found and fixed: exception photo on tablets without an SBI face app, an upstream quality off-by-one,
  attempts reset per screen, missing officer face and meta info in the packet, emulator crashes (GPU mode).

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
| Accepted bona fide attempt, median | stream clock | 9.7 s under the shipped defaults (every registration and sign-in runs 2 challenges); 6.6 s under the old defaults | calibration notes 0.2 |
| Per analysed frame, shipped suite, two suites in parallel on a loaded machine | desktop JVM | p50 34.1 ms, p95 78.4 ms | calibration notes 0.3 |
| Per analysed frame, shipped engine, 1 ONNX Runtime thread, shared laptop at about 63% load (2026-10-14) | desktop JVM (scenario runner) | p50 37.5 ms, p95 115.4 ms, max 558.0 ms (n=4410) | [../eval/results/20261014-latency/README.md](../eval/results/20261014-latency/README.md) |
| Passive pass, user-visible | Android emulator | 2.2-3.0 s; with 2 challenges 7.4-12.1 s | android-e2e section 5 |

- The design target of p95 at most 20 ms per frame is **not met** on either platform (T-PERF-01). Face analysis (YuNet plus
  Face Mesh on every frame) is two thirds of the frame; proposal E4 runs the mesh only during challenges.
- No idle-machine measurement and no physical Android device yet; the phone measurement kit is described in [android-performance.md](android-performance.md) and has not been run on a phone.

## 6. Known limitations

| # | Limitation | Effect | Where described |
|---|---|---|---|
| 1 | The screen-pattern check that refuses the adaptive screen replay (SYN-A7, 50% per transaction before it) is measured on software-made screens only; a camera frame enlarged by 1.5x or a whole number before the engine shows the same kind of frequency peaks | real-screen APCER unknown until the volunteer test; a client or device that upscales frames could see false rejections (none in the suite: every bona fide score unchanged) | [../design/engine.md](../design/engine.md) section 4; [../eval/results/20261005b-shipped/README.md](../eval/results/20261005b-shipped/README.md) |
| 2 | Dim light is tested only with the synthetic pack `bf-s3-dim` (45% brightness) | with the exposure correction it passes (score 55, BPCER 0/28; it was 14.3% before). A real dim room with an auto-exposure camera is untested; faces that still fail end with "Improve lighting." without counting an attempt, up to 3 times per capture | [../eval/results/20261005b-shipped/README.md](../eval/results/20261005b-shipped/README.md); calibration notes, update of 2026-10-05 (evening) |
| 3 | Every registration, sign-in, onboarding and supervisor authentication now answers 2 challenges | median accepted attempt 9.7 s instead of 6.6 s; one synced key per workflow switches back to passive first | calibration notes 0.2; `design/decision-flow.md` section 5 |
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
| 14 | The end-to-end client runs (sections 4.1, 4.2) were made at the old defaults; neither full demo was repeated at the shipped defaults | the clients' behaviour under `ALWAYS_ACTIVE` is shown by DE-D8 and AE-A12, the desktop flow 65 (15/15, bundle 1.0.1 era) and unit tests, not by a full re-run | `design/android-liveness.md` 13.8 |
| 15 | No real person, no physical phone and no real print or screen has been tested through either client | results of sections 3 and 4 are replayed frames only | section 7 |

Closed on 2026-09-30: prints and looped clips passing the passive check (old #1), head turns to the left misread as
a smile (old #3), blurred frames reaching the challenges. Closed on 2026-10-05: the Android import button (old #15,
Settings, Device Settings, with a BUSY check while a capture screen is open).

## 7. In-house evaluation with volunteers (ISO/IEC 30107-3 method) — placeholder

To be filled after the capture sessions described in [../eval/protocol.md](../eval/protocol.md). Nothing below is a
result yet. Title of the final report: "In-house PAD evaluation following ISO/IEC 30107-3:2023 methodology: basic
attack potential".

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
| B7 and B8 latency and memory on a physical phone | **code done, phone measurement pending.** An instrumented benchmark and a script that loops CPU, XNNPACK and NNAPI over 1, 2 and 4 threads and samples memory exist; the results table is empty; only the emulator numbers of the Android report are listed, labelled emulator | [android-performance.md](android-performance.md) |

Real-person and phone results are pending for all three items.
