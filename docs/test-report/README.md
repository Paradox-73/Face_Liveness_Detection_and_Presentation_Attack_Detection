# Test report (PS04 face liveness and PAD)

One page that collects every test result of the project: unit tests per module, the automated liveness and attack
scenarios, the end-to-end runs on the real desktop and Android clients, latency, and known limits. The details
stay in the linked reports; requirement-by-requirement status is in [../traceability.md](../traceability.md).

| Report | What it covers |
|---|---|
| [desktop-e2e.md](desktop-e2e.md) | Real desktop Registration Client against MockSync (mock MOSIP server) and MockMDS (mock face device): 13 scripted flows, 3 interactive flows, audit and packet evidence |
| [android-e2e.md](android-e2e.md) | Real Android Registration Client on an Android 14 emulator against MockSync: 16 flows, latency, APK size |
| [../eval/calibration-notes.md](../eval/calibration-notes.md) | Automated suite: the real engine and models over 39 replayed scenarios, ISO/IEC 30107-3 metrics |
| [../traceability.md](../traceability.md) | Every PS04 requirement → code → test → status |
| [../test-data.md](../test-data.md) | Demo users, machine registration, frame packs |

## 1. Summary

| Area | Result |
|---|---|
| Unit and component tests | **2,224 tests, 0 failures, 0 errors** in 13 test suites (section 2). All run on 2026-09-30; the counts grew after the engine and gap fixes of that afternoon |
| Automated liveness and attack scenarios | 39 scenarios, 131 transactions per suite; decisions reproducible run to run. **Shipped defaults: pooled APCER 1.7% (4/238) per attempt, BPCER 29.4% (15/51)**; before the fixes of 2026-09-30: 17.6% and 58.2% (section 3) |
| Desktop end-to-end | reference run 2026-09-30 04:49-04:59: **41 of 41 checks passed** (section 4) |
| Android end-to-end | reference run 2026-09-30 09:09-09:40: **58 of 58 checks passed**; a repeat run found one real weakness (section 4) |
| Headline weakness | an adaptive screen replay that answers each prompt (SYN-A7) is accepted 4 of 16 times; every other synthetic attack species is refused. The earlier weakness, prints and looped clips passing the passive check, is closed by the new defaults (`ALWAYS_ACTIVE` for registration, sign-in and onboarding; 30 passive frames) (section 6) |
| Evaluation with real people and artefacts | not run yet; placeholder in section 7 |

## 2. Unit and component tests

Commands run from the repository root in Git Bash (Windows) or bash (Linux), after the setup of
[../install/README.md](../install/README.md) section 1: JDK 11 with JavaFX as the default `JAVA_HOME`, JDK 21 where the
row says so, Maven 3.9, and the Maven mirror settings file that the repository ships as `.github/maven-settings.xml`.
Set it once per shell with `export MAVEN_ARGS="-s .github/maven-settings.xml"` (from the repository root). The
counts below were taken on the project laptop (Windows 11, 12 threads, 16 GB, other builds running at the same time).
The last column names the job of `.github/workflows/ci.yml` that runs the same command on a clean `ubuntu-latest`
machine at every push and pull request. As of 2026-10-04 the jobs `mock-mds`, `scenario-runner`, `eval-python`,
`desktop-liveness` and `android-unit` have been run only as local commands (GitHub runs a workflow after the push);
the first GitHub run is the check of the Linux-only steps. "Upstream" means tests that came with the imported MOSIP
code and still pass with this project's changes.

| Module | What it tests | Command | Tests | Failures / errors | Skipped | CI job |
|---|---|---|---|---|---|---|
| `liveness/liveness-core` | engine: session state machine, passive decision, challenges, action detection, integrity, planarity, capture binding, policy, audit, UI states, MJPEG reader | `mvn -B -f liveness/pom.xml install -Dliveness.benchmark.frames=0` (the job runs `verify`; `install` also puts the engine in `~/.m2`, which the rows below need) | 272 | 0 | 0 | `liveness` |
| `liveness/liveness-onnx` | ONNX adapters, golden outputs of the 5 models, signed model registry, engine factory, execution providers | same run | 95 | 0 | 0 | `liveness` |
| `liveness/face-match-sdk` | SFace face-match SDK behind MOSIP's `IBioApiV2`, ISO 19794-5 records, MOSIP loader | same run | 42 | 0 | 0 | `liveness` |
| desktop `registration-services` (liveness and audit tests) | capture and authentication gates, stream session, orchestrator, config, audit sink, packet evidence, pass tokens, model import, behaviour on repeated failure, account lock | after the engine install: `mvn -B -f clients/desktop/registration/pom.xml test -pl registration-services,registration-client -am -Dgpg.skip -Djacoco.skip=true -DfailIfNoTests=false "-Dtest=io/mosip/registration/service/bio/liveness/*Test.java,**/AuditFactoryTest.java,io/mosip/registration/controller/liveness/*Test.java"` (on Linux, put `xvfb-run -a` in front: the JavaFX tests need a display) | 111 | 0 | 0 | `desktop-liveness` |
| desktop `registration-client` (liveness tests) | liveness panel view model, texts and replacement texts, bundle parity (en/fr/ar), JavaFX listener, diagnostic lines, model import window | same run | 36 | 0 | 0 | `desktop-liveness` |
| Android `livenessmanager` | engine loader, config, audit sink, face authenticator, capture screen (Robolectric), image decoding, SBI codec, replay, supervisor override, metrics, diagnostic mode, execution provider | with JDK 21 as `JAVA_HOME`, Flutter 3.10.4 and the Android SDK of [../install/README.md](../install/README.md) section 1: `bash scripts/android-build.sh --test-only` (installs the engine, generates the code a fresh clone lacks, runs the three `testDebugUnitTest` tasks) | 109 | 0 | 0 | `android-unit` |
| Android `app` | capture gate, face-auth flow, capture launcher, Flutter bridge, supervisor override flow | same run | 35 | 0 | 0 | `android-unit` |
| Android `clientmanager` (upstream + liveness) | packet building with `LIVENESS_*` evidence, meta info; all upstream tests | same run | 1,186 | 0 | 0 | `android-unit` |
| Android Flutter widgets | face-auth panel, outcome banner, Arabic right-to-left | after the Android run above: `cd clients/android && flutter test test/liveness/` | 13 | 0 | 0 | `android-unit` |
| `tools/mock-sync` | sync endpoints, Android contract (key wrapping), response signatures, packet inspector | `mvn -B -f tools/mock-sync/pom.xml verify` | 37 | 0 | 0 | `mock-sync` |
| `devices/mock-mds` (upstream + extensions) | frame sources, capture from the streamed frame, faults, renewed FTM keys | with JDK 21: `mvn -B -f devices/mock-mds/pom.xml package -Dgpg.skip=true` | 226 | 0 | 0 | `mock-mds` |
| `tools/eval/scenario-runner` | scenario loading, playlists, classification, output | after the engine install: `mvn -B -f tools/eval/scenario-runner/pom.xml package` | 29 | 0 | 0 | `scenario-runner` |
| `tools/eval` Python | APCER/BPCER, confidence intervals, frame-pack manifests | `pip install -r tools/eval/requirements.txt`, then `python -m pytest -q tools/eval/tests` | 33 | 0 | 0 | `eval-python` |
| **Total** | | | **2,224** | **0** | **0** | |

When each count was taken (all 2026-09-30):

- `liveness/*` and `tools/eval/scenario-runner`: the engine engineer's run after the engine fixes (surefire reports of 11:49-11:53, no engine source changed since).
- desktop `registration-services` and `registration-client` liveness tests: re-run for this page at 15:20, after the desktop gap fixes.
- Android `livenessmanager`, `app` and `clientmanager`: the Android engineer's run after the Android gap fixes (`scripts/android-build.sh --test-only`, [../design/android-liveness.md](../design/android-liveness.md) section 13.8). Not re-run for this page: the emulator host was overloaded.
- Flutter, `tools/mock-sync`, `devices/mock-mds` and the Python tests: unchanged since the 10:40-10:55 run.

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
clock with seeded challenge order, so a re-run gives the same decisions. 39 scenarios in `eval/scenarios/`:
10 bona fide (three people from openly licensed clips, S1-S3), 22 attack (25 synthetic attack packs made in
software from those clips), 7 quality and behaviour cases. Full method: [../eval/README.md](../eval/README.md).

**These are technology regression results on synthetic attacks, not an ISO/IEC 30107-3 evaluation.**

### 3.1 Headline

The engine and defaults in this repository are the **shipped (after)** row, run on 2026-09-30 after the engine
fixes ([../eval/results/20260930-engine-fixes/](../eval/results/20260930-engine-fixes/)). The other rows are the
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

### 3.2 Per attack species, per attempt

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

### 3.3 Quality and behaviour scenarios (shipped (after) suite)

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
about 10 minutes. Reference run: **41 checks passed, 0 failed.**

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
Reference run 7: **58 checks passed, 0 failed.**

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
| Passive pass, user-visible | Android emulator | 2.2-3.0 s; with 2 challenges 7.4-12.1 s | android-e2e section 5 |

- The design target of p95 at most 20 ms per frame is **not met** on either platform (T-PERF-01). Face analysis (YuNet plus
  Face Mesh on every frame) is two thirds of the frame; proposal E4 runs the mesh only during challenges.
- No idle-machine measurement and no physical Android device yet.

## 6. Known limitations

| # | Limitation | Effect | Where described |
|---|---|---|---|
| 1 | Adaptive screen replay of subject S3 (SYN-A7) passes every layer | APCER_max 25% per attempt (4/16), 50% per transaction, in every configuration | calibration notes 0.2, 4.9 |
| 2 | The synthetic very dim pack `bf-s3-dim` is rejected by passive PAD (score 3) | 12 of the 15 rejected bona fide attempts; real dim-light behaviour unknown until the volunteer evaluation | calibration notes 0.2, 4.5 |
| 3 | Every registration, sign-in and onboarding now answers 2 challenges | median accepted attempt 9.7 s instead of 6.6 s; one synced key per workflow switches back to passive first | calibration notes 0.2; `design/decision-flow.md` section 5 |
| 4 | A blink already in progress at the prompt fails (`ONSET_TOO_FAST`); kept on purpose against replays that blink continuously | the person blinks again (0 cases in the shipped suite, 3 under the old configuration) | calibration notes 0.1, 4.7; desktop 5.3 |
| 5 | The capture frame can be the last streamed frame while the head is still turned | 3 of 15 S2 attempts fail `CAPTURE_PAD_SCORE`, each followed by an accepted retry | calibration notes 0.2 |
| 6 | Desktop supervisor override accepted a print after challenge timeouts (override is refused only after PAD, integrity or binding failures) | the capture is marked `OVERRIDE` in the packet for review; same rule on Android since 2026-09-30 | desktop 5.2; `design/android-liveness.md` 13.1 |
| 7 | Android: built-in camera as L0 only; no L1 and no vendor SBI face app path; CameraX path not run | Android tested with replayed frames on an emulator | Android 8.5; traceability O1 |
| 8 | Offline simulated (client-side network cut), not by stopping the server | same client-side failure as a real outage | desktop 5.5; Android 8.6 |
| 9 | Per-frame latency above target; no physical tablet measured; accelerators (XNNPACK, NNAPI) selectable but not measured | see section 5 | section 5; calibration notes 0.4 |
| 10 | Under heavy CPU load the inference watchdog (3 inferences over 500 ms) can end a session with `ENGINE` / `INFERENCE_TIMEOUT` | the person retries; no attempt is used | desktop 5.4; `design/android-liveness.md` 13.5 |
| 11 | The stored resident photo can show the challenge expression (smile) | image quality for the ID record | Android 8.2 |
| 12 | SFace match threshold 40 chosen from published values, not calibrated on a larger set | match error rates unknown | desktop 5.10; `liveness/face-match-sdk/README.md` |
| 13 | All attack data is synthetic; bona fide data is three people from stock clips | numbers are regression figures, not security claims | section 3 |
| 14 | The end-to-end client runs (sections 4.1, 4.2) were made before the new defaults; the full Android demo was not completed again after the gap fixes | the clients' behaviour under `ALWAYS_ACTIVE` is shown by DE-D8 and AE-A12 and the new desktop flow 65 (15/15), not by a full re-run | `design/android-liveness.md` 13.8 |

Closed on 2026-09-30: prints and looped clips passing the passive check (old #1), head turns to the left misread as
a smile (old #3), blurred frames reaching the challenges.

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
