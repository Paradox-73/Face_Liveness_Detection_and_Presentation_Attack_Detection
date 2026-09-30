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
| Unit and component tests | **2,144 tests (2 skipped by design), 0 failures, 0 errors** in 13 test suites, all re-run on 2026-09-30 between 10:40 and 10:55 (section 2) |
| Automated liveness and attack scenarios | 39 scenarios x 3 configurations, 131 transactions each; decisions reproducible run to run (section 3) |
| Desktop end-to-end | reference run 2026-09-30 04:49-04:59: **41 of 41 checks passed** (section 4) |
| Android end-to-end | reference run 2026-09-30 09:09-09:40: **58 of 58 checks passed**; a repeat run found one real weakness (section 4) |
| Headline weakness | synthetic printed photos and looped clips pass the passive check under the shipped registration policy; the stricter `ALWAYS_ACTIVE` policy refuses them on both clients (section 6) |
| Evaluation with real people and artefacts | not run yet; placeholder in section 7 |

## 2. Unit and component tests

All commands from the repository root after `. toolchain/env.sh` (Git Bash on Windows 11, 12 threads, 16 GB, other
builds running at the same time). "Upstream" means tests that came with the imported MOSIP code and still pass with
this project's changes.

| Module | What it tests | Command | Tests | Failures / errors | Skipped |
|---|---|---|---|---|---|
| `liveness/liveness-core` | engine: session state machine, passive decision, challenges, action detection, integrity, planarity, capture binding, policy, audit, UI states, MJPEG reader | `with_lock liveness-build mvn -o -f liveness/pom.xml verify -Dliveness.benchmark.frames=0` | 264 | 0 | 0 |
| `liveness/liveness-onnx` | ONNX adapters, golden outputs of the 5 models, signed model registry, engine factory | same run | 93 | 0 | 2 (latency benchmark, off by property) |
| `liveness/face-match-sdk` | SFace face-match SDK behind MOSIP's `IBioApiV2`, ISO 19794-5 records, MOSIP loader | same run | 42 | 0 | 0 |
| desktop `registration-services` (liveness and audit tests) | capture and authentication gates, stream session, orchestrator, config, audit sink, packet evidence, pass tokens | `cd clients/desktop/registration && mvn -o -pl registration-services test "-Dtest=io/mosip/registration/service/bio/liveness/*Test.java,**/AuditFactoryTest.java"` | 85 | 0 | 0 |
| desktop `registration-client` | liveness panel view model, texts, bundle parity (en/fr/ar), JavaFX listener | `cd clients/desktop/registration/registration-client && mvn -o test -Dgpg.skip -Djacoco.skip=true` | 27 | 0 | 0 |
| Android `livenessmanager` | engine loader, config, audit sink, face authenticator, capture screen (Robolectric), image decoding, SBI codec, replay | `cd clients/android/android && ./gradlew.bat --no-daemon :livenessmanager:testDebugUnitTest --rerun` | 82 | 0 | 0 |
| Android `app` | capture gate, face-auth flow, capture launcher, Flutter bridge | same, `:app:testDebugUnitTest --rerun` | 27 | 0 | 0 |
| Android `clientmanager` (upstream + liveness) | packet building with `LIVENESS_*` evidence, meta info; all upstream tests | same, `:clientmanager:testDebugUnitTest --rerun` | 1,186 | 0 | 0 |
| Android Flutter widgets | face-auth panel, outcome banner, Arabic right-to-left | `cd clients/android && flutter test test/liveness/` (JDK 21) | 13 | 0 | 0 |
| `tools/mock-sync` | sync endpoints, Android contract (key wrapping), response signatures, packet inspector | `cd tools/mock-sync && mvn -o test` | 37 | 0 | 0 |
| `devices/mock-mds` (upstream + extensions) | frame sources, capture from the streamed frame, faults, renewed FTM keys | `cd devices/mock-mds && mvn -o test -Dgpg.skip=true` (JDK 21) | 226 | 0 | 0 |
| `tools/eval/scenario-runner` | scenario loading, playlists, classification, output | `mvn -o -f tools/eval/scenario-runner/pom.xml test` | 29 | 0 | 0 |
| `tools/eval` Python | APCER/BPCER, confidence intervals, frame-pack manifests | `python -m pytest -q tools/eval/tests` | 33 | 0 | 0 |
| **Total** | | | **2,144** | **0** | **2** |

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

### 3.1 Headline (runs of 2026-09-30, [../eval/results/20260930/](../eval/results/20260930/))

| Suite | Configuration | APCER_max per attempt (worst species) | APCER pooled per attempt | APCER pooled per transaction | BPCER per attempt | BPCER per transaction |
|---|---|---|---|---|---|---|
| shipped | defaults (registration passive first, threshold 80) | **100% (8/8)**, SYN-A3 and SYN-A8 | 17.6% (32/182) | 39.0% (32/82) | 58.2% (39/67) | 30.0% (12/40) |
| strict | `policy.registration=ALWAYS_ACTIVE` | **25% (4/16)**, SYN-A7 | 3.5% (8/230) | 9.8% (8/82) | 68.0% (51/75) | 40.0% (16/40) |
| proposed | `ALWAYS_ACTIVE` for `registration`, `login_auth`, `onboard_auth`; `passive.min_frames=30`; `quality.min_sharpness=40` | **25% (4/16)**, SYN-A7 | 1.7% (4/238) | 4.9% (4/82) | 66.7% (48/72) | 40.0% (16/40) |

- APCER: share of attack attempts accepted. BPCER: share of genuine attempts rejected. A transaction is up to 3
  attempts. Intervals (95% Clopper-Pearson) are in each `report.md`; with 8-48 attempts per species they are wide.
- BPCER is dominated by one engine defect and one synthetic pack: every head turn to the left by subject S2 is
  misread as a smile (`WRONG_ACTION`), and the synthetic dim pack scores 3-4 on the passive check. Every
  normal-light (C1) bona fide attempt of S1 and S3 is accepted under the proposed configuration.

### 3.2 Per attack species, per attempt

| Species | What it is | shipped | strict | proposed | Layer that stops it |
|---|---|---|---|---|---|
| SYN-P0 | stock MockMDS still image | 0/6 | 0/6 | 0/6 | frame integrity (`FROZEN`) |
| SYN-A1 | injected still (identical frames) | 0/12 | 0/12 | 0/12 | frame integrity (`FROZEN`) |
| SYN-A2 | hand-held print | 8/32 | 4/40 | 0/48 | challenges (timeout) |
| SYN-A3 | print moved in 2-D | **8/8** | 0/24 | 0/24 | challenges |
| SYN-A4 | print tilted to fake a turn | 0/12 | 0/12 | 0/12 | challenges |
| SYN-A5 | photo on a screen | 0/24 | 0/24 | 0/24 | passive PAD or challenges |
| SYN-A6 | video replay on a screen | 4/28 | 0/36 | 0/36 | passive PAD or challenges |
| SYN-A7 | adaptive replay (clip answers the prompt) | 4/16 | 4/16 | **4/16** | passive PAD for S2; S3 passes every layer |
| SYN-A8 | looped 1.3 s clip | **8/8** | 0/24 | 0/24 | loop detection during challenges |
| SYN-A9 | genuine clip of the wrong action | 0/36 | 0/36 | 0/36 | challenges (`WRONG_ACTION`) |

### 3.3 Quality and behaviour scenarios (shipped suite)

| Scenario | Result | Expected |
|---|---|---|
| `other-no-face` | no response (`NO_FACE`), no attempt counted | yes |
| `other-two-faces` | no response (`MULTI_FACE`), no attempt counted | yes |
| `other-too-dark` | no response (`TOO_DARK`) | yes |
| `other-stream-ends` | no response (device error) | yes |
| `other-s1-wrong-action`, `other-s3-ignores-prompt` | rejected (`CHALLENGE_TIMEOUT`) | yes |
| `other-blurred` | rejected (`NEUTRAL_TIMEOUT`) instead of a quality message | **no**: blurred frames pass the quality gate (proposal P3 in the calibration notes) |

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
  `a-s2-print-still` passed the passive check (score bucket 80-89) under the shipped registration policy.
  Repeated on the capture screen: `a-s3-print-still` accepted 6/6, `a-s2-print-still` 1/6 with the shipped
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
| Time to a passive decision | stream clock (replay) | 0.53 s (8 frames) | calibration notes |
| Accepted attempt, passive only / with 2 challenges | stream clock | 0.83 s / 6.8 s median | calibration notes 4.1 |
| Passive pass, user-visible | Android emulator | 2.2-3.0 s; with 2 challenges 7.4-12.1 s | android-e2e section 5 |

- The design target of p95 at most 20 ms per frame is **not met** on either platform. Face analysis (YuNet plus
  Face Mesh on every frame) is two thirds of the frame; proposal E4 runs the mesh only during challenges.
- No idle-machine measurement and no physical Android device yet.

## 6. Known limitations

| # | Limitation | Effect | Where described |
|---|---|---|---|
| 1 | Printed photos (SYN-A2/A3) and looped clips (SYN-A8) of one subject pass the passive check under the shipped registration policy (passive first, threshold 80) | attack accepted without a challenge; `ALWAYS_ACTIVE` refuses them on both clients at about 7 s extra per capture; the choice is open | calibration notes 4.1; desktop 5.1; Android 8.1 |
| 2 | Adaptive screen replay of subject S3 (SYN-A7) passes every layer | APCER_max stays 25% per attempt in every configuration | calibration notes 4.9 |
| 3 | Genuine head turns to the left are misread as a smile | BPCER dominated by one subject; demo pool set to `BLINK,SMILE` | calibration notes 4.2 |
| 4 | A blink already in progress at the prompt fails (`ONSET_TOO_FAST`) | one retry needed for frequent blinkers | calibration notes 4.7; desktop 5.3 |
| 5 | Desktop supervisor override accepted a print after challenge timeouts (override is refused only after PAD, integrity or binding failures) | the capture is marked `OVERRIDE` in the packet for review | desktop 5.2 |
| 6 | Android: built-in camera as L0 only; no L1 and no vendor SBI face app path; CameraX path not run | Android tested with replayed frames on an emulator | Android 8.5; traceability O1 |
| 7 | Offline simulated (client-side network cut), not by stopping the server | same client-side failure as a real outage | desktop 5.5; Android 8.6 |
| 8 | Per-frame latency above target; no physical tablet measured | see section 5 | section 5 |
| 9 | The stored resident photo can show the challenge expression (smile) | image quality for the ID record | Android 8.2 |
| 10 | SFace match threshold 40 chosen from published values, not calibrated on a larger set | match error rates unknown | desktop 5.10; `liveness/face-match-sdk/README.md` |
| 11 | All attack data is synthetic; bona fide data is three people from stock clips | numbers are regression figures, not security claims | section 3 |

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
