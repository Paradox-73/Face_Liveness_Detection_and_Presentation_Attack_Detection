# Calibration notes: the automated suite on the shipped engine

Results of `tools/eval/run-suites.sh` on the engine as shipped, what they show about each detection layer, and the
parameter and code changes they support. Sections 1-6 are the original study (runs of 2026-09-29 and 2026-09-30,
no default changed). **Section 0 records what was adopted on 2026-09-30 and the before and after runs**; where
sections 1-6 say "shipped", they mean the configuration shipped before that change (now the `legacy` suite).

Read this first:

- The attack packs are synthetic (software-made prints, screens and replays) and the bona fide packs are three
  unrelated people from openly licensed clips. The numbers are **technology regression results**, not an
  ISO/IEC 30107-3 evaluation, and they say nothing about demographic behaviour.
- Every scenario is replayed on a stream clock with seeded challenge order, so a re-run on the same models and
  packs gives the same decisions; only the wall-clock timings move.
- Checked: the 2026-09-29 and 2026-09-30 runs of `shipped` and `strict` gave the same outcome, decision, reason
  codes, passive score, active PAD score and binding similarity for every one of the 256 and 312 attempts they
  share, although the second run was three times slower per frame.
- Raw data: `results/<run>/<suite>/attempts.jsonl` (one JSON line per attempt, no images; for runs before
  2026-10-14 in git history only, see [results/README.md](results/README.md)), `report.md` (the
  metrics), `summary.md` (one row per transaction) and `run.json` (models, parameters, platform). The frame
  measurements quoted below come from the `inspect` command of the runner (`inspect --all`, one CSV row per frame).

## Current results (2026-10-14)

At the shipped defaults (`ALWAYS_ACTIVE`) no attack transaction is accepted; with the six workflows at
`PASSIVE_THEN_ACTIVE`, some are ([results/20261014-shipped/README.md](results/20261014-shipped/README.md)). The
median accepted registration attempt takes 11.2 s; per analysed frame p50 39.4 ms, p95 127.7 ms on 2026-10-20
([results/20261020-latency/README.md](results/20261020-latency/README.md)). The updates and studies below are the
dated history behind the thresholds.

## Update 2026-10-05 (evening): exposure correction and screen-pattern check

Two additions to the passive PAD in `liveness-onnx` (`MiniFasPassivePad`; design: [../design/engine.md](../design/engine.md)
section 4), measured with the full shipped suite before and after
([results/20261005b-shipped/README.md](results/20261005b-shipped/README.md)):

- **Exposure correction** (`SceneExposure`) answers F5 (section 4.5): when the whole frame is dark (mean grey below
  90), the PAD crop is brightened by `160 / mean`, at most 2.5x. `bf-s3-dim` goes from passive score 3 (rejected
  4 of 4) to 55 (accepted 4 of 4 on the first attempt). The trigger is the frame, not the face, because the
  darker-skinned S1 in normal light (face grey 66, frame 105) lost score when its face was brightened in a test
  (48 to 6); S1 is untouched and still scores 64.
- **Screen-pattern check** (`ScreenPatternMeter`) answers F9 (section 4.9): the 64x64 centre of the face box is
  searched for isolated frequency peaks (pixel grid, moire). Per-frame peak ratio on the packs: live faces median
  15-55, largest single frame 748; screen packs median 436-5,990; prints at live values. The ratio maps to a live
  probability of 1 up to 600 and 0 from 1,500 and caps the MiniFASNet score. `atk-s3-adaptive-screen` now ends at
  passive PAD with score 0 in 4 of 4 seeds.
- Checked before keeping them: every other bona fide passive score is unchanged (S1 64, S2 94, S3 100); the 19
  desktop/Android parity cases re-recorded with no change (`eval/interop/liveness-parity.json` identical); the
  MockMDS stock photo measures 494, below the 600 limit.
- Suite results before and after (shipped: SYN-A7 50.0% to 0.0%, `registration` BPCER 14.3% to 0.0%; legacy:
  prints and looped clips still pass a passive-only check), cost and limits:
  [results/20261005b-shipped/README.md](results/20261005b-shipped/README.md).

## Update 2026-10-05: defaults and results of that date

The sections below keep the dated studies of 2026-09-29 and 2026-09-30. Since then the defaults and the engine changed again (2026-10-04 and 2026-10-05), and the three suites were re-run; their results are in [results/20261005-shipped/README.md](results/20261005-shipped/README.md).

What changed in the shipped defaults:

- `packet_auth`, `exception_auth` and `eod_auth` are `ALWAYS_ACTIVE` too (they were passive first at threshold 95); `exception_photo` stays off. Every workflow that matches a face therefore asks for challenges on every capture.
- Model bundle 1.3.0 (minimum accepted version 1.3.0) carries `integrity.loop_window_frames` 300 in its signed parameters, up from 30: loops of 2, 4 and 8 s (30, 60 and 120 frames) are now caught; the longest period the window finds is 294 frames (9.8 s at 30 fps).
- Dim light: a passive PAD failure on a dark face (median grey level below 75) ends as `LOW_LIGHT`, not counted, up to 3 times per transaction; the 4th counts. The runner tracks this by default (`--no-low-light` reproduces older runs).
- Capture frame: after the last challenge the engine waits for a neutral, frontal, eyes-open frame ("Look directly at the camera and hold still"); `active.neutral_max_pitch_deg` is 20.
- Local settings can no longer weaken the checks: only the keys on the local allow-list (passive and PAD reject thresholds with floors, challenge timeout, stream timeout, `max_retries`, `diagnostic.enable`, per-workflow threshold and `max_retries`) are read from the client.

Results (275 transactions, 75 scenarios): APCER 0.0% per transaction for every species and workflow except
`registration` SYN-A7, the adaptive screen replay, at 50.0% (4/8); BPCER `registration` 14.3% (4/28), all from the
dim clip `bf-s3-dim`. The per-species tables, the legacy configuration and the latency run are in
[results/20261005-shipped/README.md](results/20261005-shipped/README.md). The 2026-09-30 timing figures below came
from a quieter machine and are not comparable with later runs.

Not measured: real people, real artefacts, a physical phone, a live webcam through the full client. The figures of sections 0.2 and 1-6 (including the 29.4% per-attempt BPCER and 25% worst species) are the historic 2026-09-30 data of `results/20260930-engine-fixes/`; the headline of the protocol is per species and per transaction, never pooled.

## 0. Adopted changes and before/after results (2026-09-30)

Historic: dated 2026-09-30, folder `results/20260930-engine-fixes/`. The current figures are in "Current results" above.

### 0.1 What changed

| Id | Change | Where |
|---|---|---|
| P1 | `policy.registration`, `policy.onboard_auth`, `policy.login_auth` default to `ALWAYS_ACTIVE`; the three supervisor workflows stayed `PASSIVE_THEN_ACTIVE` at threshold 95 on 2026-09-30 (since 2026-10-04 they are `ALWAYS_ACTIVE` too, see the update above) | `LivenessConfigKeys.defaultPolicyMode`, `tools/mock-sync/data/mock-sync-defaults.properties` |
| P2 | `quality.min_sharpness` 40 (was 10), set in the signed `engineParams` of model bundle 1.0.1 (bundle 1.3.0 today, with the same value plus `integrity.loop_window_frames` 300), because the sharpness scale belongs to the analyser; the compiled default stays 10 | `models/manifest.json`, `models/manifest.jws` |
| P3 | `passive.min_frames` default 30 (was 8) | `PolicyKey.PASSIVE_MIN_FRAMES`, MockSync defaults |
| E1 | The smile is unmeasured while the head is turned more than `active.smile_max_yaw_deg` (new engine parameter, 15) from the neutral face, so a head turn can no longer be taken as a smile | `ActionLevels.smile` |
| E3 | Frames with \|yaw\| above `active.pad_max_yaw_deg` (new, 30) are not scored by PAD during the challenges | `DefaultLivenessSession.scoreActivePad` |
| new | When `passive.timeout_ms` (4 s) ends before 30 good frames, a session that has at least `passive.timeout_min_frames` (new, 8) decides on those frames instead of failing with `PASSIVE_TIMEOUT`. Needed because the Android emulator analysed passive frames in 57-251 ms (`docs/test-report/android-e2e.md` section 5), too slow for 30 frames in 4 s | `DefaultLivenessSession.onPassiveTimeout` |
| B8 | `mosip.registration.liveness.engine.execution_provider` = `CPU` (default), `XNNPACK` or `NNAPI`; see 0.4 | `OnnxRuntimeOptions`, `OnnxLivenessEngineProvider` |

Not adopted: E2 (a blink under way at the prompt still fails with `ONSET_TOO_FAST`; ignoring it would let a replay
that blinks continuously pass on its next blink, and the 30-frame passive stage already removed the S1 case in these
suites) and E4 (mesh only from the challenges on; a speed change, not measured here).

### 0.2 Before and after

"Before" is the engine and configuration of sections 1-6. It was re-run on 2026-09-30 before any change and gave
the same outcome, decision, reason codes, passive score, active PAD score and binding similarity for every attempt as
the committed `results/20260930/` runs, so those files are the "before" data. "After" is
`results/20260930-engine-fixes/`. All 39 scenarios in every row.

| Run | Engine | Configuration | APCER_max per attempt | APCER pooled per attempt | APCER pooled per transaction | BPCER per attempt | BPCER per transaction |
|---|---|---|---|---|---|---|---|
| before `shipped` | before | old defaults | 100% (8/8) SYN-A3, SYN-A8 | 17.6% (32/182) | 39.0% (32/82) | 58.2% (39/67) | 30.0% (12/40) |
| before `strict` | before | old + registration always active | 25% (4/16) SYN-A7 | 3.5% (8/230) | 9.8% (8/82) | 68.0% (51/75) | 40.0% (16/40) |
| before `proposed` | before | P1-P3 | 25% (4/16) SYN-A7 | 1.7% (4/238) | 4.9% (4/82) | 66.7% (48/72) | 40.0% (16/40) |
| after `legacy` | after | old defaults | 100% (8/8) SYN-A3, SYN-A8 | 17.6% (32/182) | 39.0% (32/82) | 29.4% (15/51) | 10.0% (4/40) |
| after `legacy-strict` | after | old + registration always active | 25% (4/16) SYN-A7 | 3.5% (8/230) | 9.8% (8/82) | 29.4% (15/51) | 10.0% (4/40) |
| **after `shipped`** | after | **new defaults (P1-P3)** | **25% (4/16) SYN-A7** | **1.7% (4/238)** | **4.9% (4/82)** | **29.4% (15/51)** | **10.0% (4/40)** |

- **Attack rejection is unchanged by the engine fixes.** `legacy` against before `shipped`, and `legacy-strict`
  against before `strict` (same configurations): every attack and quality scenario has the same accepted count and
  the same reason codes. The fixes change only
  bona fide results.
- **New shipped defaults against old shipped defaults:** APCER_max 100% to 25%, pooled APCER per attempt 17.6% to
  1.7%, per transaction 39.0% to 4.9%; the prints moved in 2-D (SYN-A3), the looped clips (SYN-A8, now `LOOP` 12/12
  per pack), the still prints including the one at operator login (SYN-A2) and the S3 screen replay (SYN-A6) are all
  rejected. BPCER per attempt 58.2% to 29.4%, per transaction 30.0% to 10.0%.
- Bona fide challenge completion (all bona fide attempts of the run):

| Challenge | before `strict` | before `proposed` | after `shipped` |
|---|---|---|---|
| TURN_LEFT | 3/36 (33 `WRONG_ACTION`) | 3/33 (30 `WRONG_ACTION`) | **15/15** |
| TURN_RIGHT | 15/15 | 6/9 (3 `WRONG_ACTION`) | **15/15** |
| BLINK | 20/23 (3 `ONSET_TOO_FAST`) | 24/24 | 24/24 |
| SMILE | 20/20 | 24/24 | 24/24 |

- What still fails, after `shipped`:
  - SYN-A7, the S3 adaptive screen replay: accepted 4/16 attempts, as in every earlier suite (4.9). It is the only
    accepted attack.
  - The synthetic dim pack `bf-s3-dim`: 12/12 attempts rejected by passive PAD (score 3), as before (4.5). It is 12
    of the 15 rejected bona fide attempts and all 4 rejected bona fide transactions.
  - S2: 3 of 15 attempts end in `CAPTURE_PAD_SCORE` (binding layer): the challenge passed, but the capture image is
    the last streamed frame, which in these clips is still turned by 40-65 degrees, where MiniFASNet scores low
    (4.8). Each of these attempts is followed by an accepted retry, so no S2 transaction is rejected. A live person
    faces the camera again at "Hold still"; the clips do not.
  - C1 bona fide (normal light, S1-S3): 36/39 attempts and 36/36 transactions accepted.
- Blur (`other-blurred`, 4.4): before, 3 counted attempts ended in `NEUTRAL_TIMEOUT` after the person had been
  prompted; after, the frames never pass the quality gate, every session ends as a no-response `BLURRY` +
  `PASSIVE_TIMEOUT` (final message `liveness.error.poor_quality`; while positioning, the engine maps `BLURRY` to the
  guidance `liveness.guide.face_visible`), and no attempt is consumed.
- Cost for genuine users (stream time of accepted bona fide attempts, median): 6.6 s before (`shipped`, mixing
  passive-only and challenge attempts) to 9.7 s after (every attempt runs 2 challenges); the passive stage takes
  2.0 s instead of 0.53 s. The replayed clips include a 400 ms reaction delay and full-length recorded actions.

### 0.3 Per-frame cost

The engine fixes do not change the work per frame. The before `proposed` suite and the after `shipped` suite have
the same configuration and ran at the same time on the same machine (2026-09-30 11:52-12:40, the two runs in
parallel with other builds): per analysed frame p50 34.8 ms / p95 77.2 ms before and 34.1 ms / 78.4 ms after.
The after suite analyses about twice as many frames as the old `shipped` suite (30,964 against 15,927), because every
registration and sign-in now runs 30 passive frames and 2 challenges.

### 0.4 ONNX Runtime execution providers (B8)

- `OnnxRuntimeOptions.ExecutionProvider` offers `CPU`, `XNNPACK` and `NNAPI`; the configuration key
  `mosip.registration.liveness.engine.execution_provider` selects one when the engine is created through
  `OnnxLivenessEngineProvider` or `OnnxRuntimeOptions.fromConfig`. A provider that the installed runtime does not
  include falls back to CPU, and `effectiveExecutionProvider()` reports the one in use; an unknown value also means
  CPU, so a typing error cannot stop the engine loading.
- Desktop measurement: the ONNX Runtime 1.20.0 Java package for Windows x64 reports
  `OrtEnvironment.getAvailableProviders()` = `[CPU]` (`OrtModelTest`, printed as `[runtime] execution providers
  available: [CPU]`). XNNPACK is not in that build, so on desktop a request for it runs on CPU and cannot be faster;
  **the default stays `CPU`**.
- Android: `onnxruntime-android` includes XNNPACK and NNAPI. The Android module reads the same key through its own
  session customizer (`livenessmanager/engine/AcceleratorCustomizer`), which must not also set
  `OnnxRuntimeOptions.Builder.executionProvider`, or the provider would be added twice. No timing on a device or
  the emulator is part of this work. A phone run of 2026-10-24 ([../test-report/android-performance.md](../test-report/android-performance.md)) measured the best setting as 2 threads on CPU or XNNPACK (p50 58-71 ms, p95 74-92 ms per frame); NNAPI was 5-8 times slower. The shipped value stays `CPU` with 2 threads.

## 1. Item under test

| Fact | Value |
|---|---|
| Engine | `DefaultLivenessEngine` from `liveness-core` 1.0.0-SNAPSHOT with the `liveness-onnx` adapters (YuNet, Face Mesh, MiniFASNet v2 + v1SE ensemble, SFace) |
| Model bundle | 1.0.0, model set SHA-256 `6c1759d2bd2d9acf382bb474cf80a1e953ab6eb177eb41e18256b409625213b2`; signed `engineParams` empty, so the engine ran on its compiled defaults |
| Configuration | `LivenessConfigKeys.defaultValues()`: registration policy `PASSIVE_THEN_ACTIVE` with passive threshold 80, authentication workflows passive threshold 95, `pad.reject_threshold` 20, `passive.min_frames` 8, `max_retries` 3, 2 challenges |
| Runner | `tools/eval/scenario-runner` 1.0.0-SNAPSHOT, ONNX Runtime 1.20.0, 2 intra-op threads (1 for the latency suite) |
| Machine | Windows 11 laptop, 12 logical CPUs, Zulu JDK 11.0.8, other builds running at the same time (much heavier load on 2026-09-30 than on 2026-09-29, see section 5) |
| Packs | 10 bona fide packs cut from 5 clips (subjects S1-S3), 25 synthetic packs, 640x480 at 15 fps; hashes in `eval/packs/*.yaml` |
| Scenarios | 39 in `eval/scenarios/`: 10 bona fide, 22 attack (including the 3 login-workflow attacks added after the first run), 7 quality/behaviour |

## 2. Headline numbers

Per attempt (evaluation type (a), the PAD subsystem) and per transaction (type (b), up to 3 attempts), all 39
scenarios, runs of 2026-09-30 (`results/20260930/`). The suites differ only in configuration:

| Suite | Configuration | APCER_max per attempt (species) | APCER pooled per attempt | APCER pooled per transaction | BPCER per attempt | BPCER per transaction |
|---|---|---|---|---|---|---|
| shipped | defaults | **100% (8/8) SYN-A3 and SYN-A8** | 17.6% (32/182) | 39.0% (32/82) | 58.2% (39/67) | 30.0% (12/40) |
| strict | `policy.registration=ALWAYS_ACTIVE` | **25% (4/16) SYN-A7** | 3.5% (8/230) | 9.8% (8/82) | 68.0% (51/75) | 40.0% (16/40) |
| proposed (P1-P3 below) | `policy.registration`, `policy.login_auth`, `policy.onboard_auth` = `ALWAYS_ACTIVE`, `passive.min_frames=30`, `quality.min_sharpness=40` | **25% (4/16) SYN-A7** | 1.7% (4/238) | 4.9% (4/82) | 66.7% (48/72) | 40.0% (16/40) |

The first draft of the proposal (36 scenarios, 2026-09-29, `results/20260929-proposed-draft`: `passive.min_frames=30`,
`quality.min_sharpness=40`, `planarity.min_residual=0.05`, registration still passive first) gave APCER_max 25%
(4/16) for SYN-A2, A3, A7 and A8, pooled APCER 9.0% (16/178) and BPCER 56.2% (36/64) per attempt; it is kept
for the loop and planarity evidence in 4.3 and 4.6.

All BPCER figures are dominated by one subject and one defect: every S2 head-turn transaction fails (section 4.2),
which is 8 of the 12 rejected bona fide transactions in the shipped suite. Without S2, the shipped suite rejects
4 of 28 bona fide transactions, all of them the synthetic dim condition (section 4.5).

The 95% Clopper-Pearson intervals are in the reports; with 8-36 attempts per species they are wide (0/12 gives a
one-sided upper bound of 22%), which is why no number here is a security claim.

## 3. What each pack does

### 3.1 Bona fide packs

| Scenario | Policy | Passive score | Result | Notes |
|---|---|---|---|---|
| bf-s1-registration, bf-s1-login | passive then active | 75 | accepted after challenges, 4/4 transactions | 75 is below both thresholds (80 and 95), so S1 always answers challenges. One attempt of seed 2 fails `ONSET_TOO_FAST` (section 4.7) and the retry passes. |
| bf-s1-active | always active | 75 | accepted 4/4 | same |
| bf-s2-registration | passive then active | 84 | accepted passively 4/4 | |
| bf-s2-login (threshold 95) | passive then active | 84 | **rejected 4/4** | every TURN_LEFT ends in `WRONG_ACTION` (section 4.2) |
| bf-s2-active | always active | 84 | **rejected 4/4** | same |
| bf-s3-registration, bf-s3-login | passive then active | 100 | accepted passively 4/4 | |
| bf-s3-active | always active | 100 | accepted 4/4, blink and smile complete every time | |
| bf-s3-dim (synthetic, 45% brightness) | passive then active | 3-4 | **rejected 4/4** by passive PAD | section 4.5 |

Challenge completion by bona fide packs (strict suite): BLINK 20/23, SMILE 20/20, TURN_RIGHT 15/15, TURN_LEFT
3/36. Median time from prompt to completion 1.2-1.9 s.

### 3.2 Attack packs, and which layer stops them

| Species | Pack(s) | shipped (passive first) | strict (always active) |
|---|---|---|---|
| SYN-A1 injected still (identical frames) | a-s2/s3-identical | rejected, `FROZEN` (integrity) after 5 frames | same |
| SYN-P0 stock MockMDS image | m-stream-static | rejected, `FROZEN` | same |
| SYN-A2 print, hand-held | a-s2-print-still | passive 73, then challenge timeouts | challenge timeouts |
| | a-s3-print-still | **accepted passively, score 99** | challenge timeouts |
| SYN-A3 print moved in 2-D | a-s2-print-affine | **accepted passively, score 90** | challenge timeouts |
| | a-s3-print-affine | **accepted passively, score 99** | challenge timeouts |
| SYN-A4 print tilted to fake a turn | a-s2-print-yaw-left/right | passive 73, then challenge timeouts | challenge timeouts |
| SYN-A5 photo on a screen | a-s2-screen-photo | rejected, passive 8 | same |
| | a-s3-screen-photo | passive 65, then challenge timeouts | challenge timeouts |
| SYN-A6 video replay on a screen | a-s2-screen-replay | rejected, passive 14 | same |
| | a-s3-screen-replay | **accepted passively, score 82** | challenge timeouts (the replayed video has no blink or smile at the right time) |
| SYN-A7 adaptive replay on a screen | a-s2-screen-* | rejected, passive 14 | same |
| | a-s3-screen-* | **accepted passively, score 82** | **accepted: active PAD 70-77, challenges answered by the matching clips** |
| SYN-A8 looped 1.3 s clip | a-s2-loop | **accepted passively, score 97** | rejected, `LOOP` during the challenges |
| | a-s3-loop | **accepted passively, score 100** | rejected, `LOOP` |
| SYN-A9 genuine clip of the wrong action | s1/s2/s3 action packs | rejected: `WRONG_ACTION` or challenge timeout, 36/36 | same |

Two facts explain most of the table:

- The passive PAD (MiniFASNet) separates the S2 attacks well (8-20) but scores the S3 print and screen packs 82-99.
  The S3 clip is warm indoor light with a lamp in frame; the synthetic print and screen looks do not move its
  score. A real printed photo behaves differently from a software-made one, so this is a limit of the synthetic
  packs as much as of the model, but it is enough to show that a passive score of 80 or even 95 must not end a
  registration session on its own.
- Whenever a challenge is required, every attack except the S3 adaptive replay is stopped, because a print or a
  replayed video cannot produce the prompted action at the prompted time.

## 4. Findings

### 4.1 Passive-only acceptance lets synthetic prints and replays through (F1)

- **Status 2026-09-30: fixed by P1 (adopted), see 0.2.**

- Shipped: 7 attack scenarios (28 attempts) pass with method `PASSIVE` and never see a challenge. The passive
  stage ends after 8 frames (533 ms of stream), which is also too short for the loop detector (4.3).
- Strict (`ALWAYS_ACTIVE` for registration): the same registration attacks are all rejected. APCER pooled over
  all species drops from 17.6% (32/182) to 3.5% (8/230) per attempt; the remaining accepted attempts are the S3
  adaptive screen replay (4.9) and the S3 print on the operator login workflow, which the strict suite leaves
  passive first (4.10).
- The authentication threshold of 95 does not help against a pack scoring 99: see the login-workflow probe in
  section 4.10.
- Cost for genuine users: every registration needs 2 challenges. On the stream clock an accepted bona fide
  attempt takes 0.83 s when the passive score alone passes and 6.8 s (median) with 2 challenges; the replayed
  clips include a 400 ms reaction delay and the full length of each recorded action, so a live user may be
  faster. BPCER moves from 30% to 40% per transaction in this suite,
  all of it S2 head turns (4.2). With the S2 defect fixed the strict suite rejects no C1 bona fide transaction
  of S1 or S3.

### 4.2 Genuine head turns to the left fail as WRONG_ACTION (F2)

- **Status 2026-09-30: fixed by E1 (smile unmeasured beyond 15 degrees of yaw); TURN_LEFT 15/15 and TURN_RIGHT
  15/15 in the after `shipped` suite, attack results unchanged, see 0.2.**

- S2 TURN_LEFT completes 3 of 36 times (strict suite) while TURN_RIGHT completes 15 of 15 and both packs reach
  more than 60 degrees of yaw with the correct sign (inspect: `s2-turn-left` yaw 4 to 65 degrees, `s2-turn-right`
  3 to -66).
- Cause: `ActiveStage.contraryTypes()` treats every detectable challenge type except the target, BLINK and gaze
  as a contrary action, and the SMILE measure is mouth width divided by inter-eye distance. When the head turns,
  the inter-eye distance shrinks faster than the mouth width, so the ratio rises without a smile: in
  `s2-turn-left` it goes from 0.716 to 0.898 (+25%) at 43 degrees of yaw, in `s2-turn-right` from 0.714 to 0.824
  (+15%), on the screen-replay copies +34% and +29%. The smile trigger is a 12% increase
  (`active.smile_ratio_increase`), held for 2 frames (`active.contrary_min_frames`), so the turn is reported as a
  smile and the challenge fails with `WRONG_ACTION` before the 300 ms hold at 20 degrees completes.
- Raising the smile trigger is not a fix. `--engine-param active.smile_ratio_increase=0.25`: S2 turns pass 4/4,
  S1 smiles fail 4/4 (its smile is +20%, `results/experiments/smile-ratio-0.25`). At 0.4 S3 smiles also fail
  (`NOT_HELD`; S3 smiles are +24%).
- Proposal E1 (engine code): while the target is a head movement, drop SMILE from the contrary list as gaze already
  is, or treat the mouth ratio as unmeasured when |yaw - neutral yaw| exceeds about 15 degrees. Either makes the
  turn packs pass without touching the smile threshold. The same yaw-dependence will hit real users who turn
  further than the 20 degree trigger, which is what the prompt asks them to do.

### 4.3 Loop detection cannot run inside an 8-frame passive stage (F3)

- **Status 2026-09-30: P3 adopted; both looped packs rejected with `LOOP` 12/12 in the after `shipped` suite. Update 2026-10-05: the signed loop window is 300 frames (it was 30), measured on this laptop at about 23-27 us per frame in the worst case; the 2, 4 and 8 s loop packs (`a-s2-loop-*`, `a-s3-loop-*`) are not flagged at a window of 30 and are flagged at 150-600; none of the 17 genuine packs and 5 source clips is flagged at 30-600.**

- `FrameIntegrityChecker` reports `LOOP` when the last `loop_match_frames` (6) hashes repeat an earlier run at a
  lag of at least 6 frames. That needs at least 12 analysed frames plus the loop length, so a 20-frame loop is
  detectable only after 26 or more frames. The passive stage passes after 8 frames (`passive.min_frames`), so
  both looped packs pass in the shipped suite with scores 97 and 100.
- With `passive.min_frames=30` (2 s at 15 fps; first proposed suite) the S3 loop is rejected in the passive stage
  (`LOOP`, 12/12 attempts). The S2 loop still passes: S2 barely moves (yaw between -3.5 and 2.0 degrees over the
  20 frames) and its motion stays below `integrity.loop_min_motion_bits` (12), the rule that keeps a still
  person from being reported as a loop. In the strict suite the S2 loop is caught during the challenges, after
  more frames.
- Proposal P3: `passive.min_frames` 8 to 30. The passive decision then takes 2.0 s of stream time instead of
  0.53 s, inside the 2.5 s target (T-PERF-02), and the frozen-frame check is unchanged (5 frames).

### 4.4 Blurred frames reach the challenges instead of the quality gate (F4)

- **Status 2026-09-30: P2 adopted through the signed model bundle 1.0.1 (the current bundle is 1.3.0 with the same value); blurred sessions end as `BLURRY` no-responses.**

- The blurred pack (`q-s3-blur`, Gaussian blur 6 px) has sharpness 11.5-12.3 by the analyser's measure. The gate
  `quality.min_sharpness` is 10, so the frames pass, the face is found, the passive score is 46 (between reject
  and pass), the person is prompted, the mesh cannot follow the eyes (EAR 0.05-0.14 on a blurred face) and three
  attempts end in `NEUTRAL_TIMEOUT` or `CHALLENGE_TIMEOUT`, each counted as a failed attempt.
- Every bona fide pack is far sharper: minimum 63 (`s3-dim-main`), 129 (`s3-main`), 213 (`s1-main`), 931
  (`s2-main`); the softened synthetic prints are 73-200.
- With `--engine-param quality.min_sharpness=40` the blurred scenario ends as `BLURRY` non-responses with the
  "hold still / clean the lens" guidance and no attempt is consumed; no other scenario changes.
- Proposal P2: `quality.min_sharpness` 10 to 40. The measure depends on the analyser's scale (variance of the
  Laplacian on the face crop), so re-check the value if the analyser changes.

### 4.5 Synthetic dim light collapses the passive score (F5; exposure correction adopted 2026-10-05, see the update at the top)

- `s3-dim-*` is S3 scaled to 45% brightness with extra noise (mean brightness 66-69 of 255). The quality gate
  (`quality.min_brightness` 50) passes it, MiniFASNet scores 3-4, below `pad.reject_threshold` 20, and every
  attempt is rejected as PAD without a challenge.
- A brightness gate cannot separate this case: the S1 packs (a genuine 2014 webcam clip) sit at 61-63 and pass
  with a score of 75. The darkening is also not what a real camera does in a dim room (gain and gamma go up, noise
  changes), so this pack is a weak stand-in for condition C2.
- Not proposed now. Record real C2 packs on the development split before choosing a value; then either raise
  `quality.min_brightness` (guidance "more light" instead of a PAD failure that consumes an attempt) or let a
  score below the reject threshold fall through to challenges when brightness is low (engine change).

### 4.6 The planarity check is never reached by a flat print (F6, keep 0.02)

- The residual is computed only when a head-turn challenge passes (`ActiveStage.observeFrame`). The tilted-print
  packs (`a-s2-print-yaw-*`, a flat print turned 35 degrees about its vertical axis) move the estimated yaw by
  only 4-5 degrees, far from the 20 degree trigger, so they end in challenge timeouts and no residual exists.
- The only residual recorded in 3 suites is 0.198 for a genuine S2 turn (first proposed suite), ten times the
  0.02 threshold: a real head is clearly non-planar to this measure.
- Do not change `planarity.min_residual` on this evidence. Collect residuals from the development split with
  `diagnostic.enable=Y` (every passed head turn writes one) and a printed photo moved by hand, then set the value
  between the two distributions.

### 4.7 A blink already in progress at the prompt fails the attempt (F7)

- **Status 2026-09-30: E2 not adopted (see 0.1); no `ONSET_TOO_FAST` bona fide attempt in the after `shipped` suite.**

- The S1 clip is a person blinking repeatedly. In seed 2 of the S1 scenarios a spontaneous blink starts at the
  moment the BLINK prompt is shown, the observer measures an onset under 200 ms (`active.min_onset_ms`) and fails
  the attempt with `ONSET_TOO_FAST`; the retry passes. This is 3 of the 4 non-S2 bona fide attempt rejections in
  the shipped suite.
- Proposal E2 (engine code): for BLINK, ignore a closure that began before the prompt and wait for the next one,
  instead of failing. A too-fast onset only protects against replays that start the action before the prompt,
  and a replay cannot know the prompt in advance in any case.

### 4.8 MiniFASNet scores drop at large yaw (F8)

- **Status 2026-09-30: E3 adopted for the active PAD score; the capture-time check (`CAPTURE_PAD_SCORE`, 3 of 15
  S2 attempts) is still affected, see 0.2.**

- In `s2-turn-left` the per-frame live probability falls from 0.96 frontal to 0.15-0.3 beyond 40 degrees of
  yaw; `s2-turn-right` behaves the same. The active PAD aggregates frames during the challenges, so genuine S2
  turns produced `PAD_SCORE` (active) rejections: 1 of 12 attempts in the shipped suite and 2 of 6 first attempts
  in the smile-ratio experiment once the turns were allowed to complete.
- The model was trained on near-frontal faces. Proposal E3 (engine code): exclude frames with |yaw| above about
  30 degrees from the active PAD aggregate, or weight the aggregate towards the frontal frames before and after
  the turn. A real user stops near the prompt's 20 degrees, so the effect is smaller than in these clips, which
  turn to a full profile.

### 4.9 The S3 adaptive screen replay passes every layer (F9; screen-pattern check adopted 2026-10-05, see the update at the top)

- `atk-s3-adaptive-screen` is accepted in all suites: passive 73-82, active PAD 69-77, and the blink and smile
  clips answer the prompts within the onset and hold rules. It is the only attack that survives the strict
  configuration, and it is the realistic replay attacker of species P7.
- Nothing in the shipped parameter set separates it: the screen look on the S3 frames does not move MiniFASNet,
  the loop detector sees no repetition and the planarity check needs a head turn. A head turn is the check the
  engine has for this: `ChallengeScheduler` draws one turn whenever the planarity check is enabled and the
  analysed frame rate allows it (`active.head_turn_min_fps`, 5), and the planarity residual is meant to flag a
  flat surface. The planarity check has no attack evidence yet, though: the S3 clips contain no head turn, and the S2 screen
  copies of the turns (`a-s2-screen-turn-*`) are rejected by the passive score before any challenge, so no attack
  reached the residual. A screen that replays a real head turn is flat, but the replayed face still turns, so planarity cannot be
  relied on to stop it; treat the adaptive replay as the known limit of challenge-response.
- Keep P1 and record the S3-style case (warm light, no turn available) in the protocol as the P7 species to watch.

### 4.10 Authentication threshold 95 is not a substitute for challenges (F10)

- **Status 2026-09-30: P1 adopted for `login_auth` and `onboard_auth`; the supervisor workflows kept passive first then. Update 2026-10-04: `packet_auth`, `exception_auth` and `eod_auth` are `ALWAYS_ACTIVE` as well, for the reason below (a print above 95 passed passively).**

Three scenarios added after the first run replay the print and screen packs on the `login_auth` workflow
(passive threshold 95; first alone in `results/20260930/login-probe`, then inside the `shipped` and `strict`
suites of `results/20260930/` with the same results):

- `atk-s3-print-still-login`: **accepted passively, 4/4 transactions, score 99**. The operator login policy is
  passive first, and 99 clears 95. An attacker with a print that the passive model likes logs in without a
  challenge.
- `atk-s3-screen-replay-login`: score 82 is below 95, so the login falls through to challenges and every attempt
  times out (12/12 rejected).
- `atk-s2-print-still-login`: score 73, challenges, 12/12 rejected.
- Proposal P1 therefore also covers the five authentication workflows (`policy.onboard_auth`,
  `policy.login_auth`, `policy.packet_auth`, `policy.exception_auth`, `policy.eod_auth`), or at least
  `login_auth` and `onboard_auth`, which admit an operator into the client. The cost is the same 2 challenges per
  login.
- Confirmed by the `proposed` suite (`login_auth=ALWAYS_ACTIVE`): `atk-s3-print-still-login` is rejected 12/12
  by challenge timeouts, and the genuine `bf-s1-login` and `bf-s3-login` still pass 4/4.

### 4.11 Side effects of the proposal seen in the proposed suite (F11)

- A 30-frame passive stage averages more frames, which moves some passive scores (median over attempts, shipped
  to proposed): S1 75 to 64, S2 84 to 94, S3 screen replay 82 to 73, S2 screen replay 14 to 20. The S2 screen
  replay now sits exactly on `pad.reject_threshold` (20), is not rejected passively and is stopped by the active
  PAD aggregate instead, 5.9 s of stream time into the attempt. Still rejected 12/12, but on a thin margin; the development split should
  show whether 20 is the right reject threshold for a 30-frame average.
- S2 bona fide now also fails 3 of 36 attempts on `CAPTURE_PAD_SCORE` (binding layer): the challenge passed, but
  the signed capture was taken from the last streamed frame, which in these clips is still at a large yaw where
  MiniFASNet scores low (4.8). A live user facing the camera again at capture would not hit this; E3 and a
  "face the camera" step before capture would remove it.
- S1 no longer shows `ONSET_TOO_FAST` (4.7). This is a timing coincidence (the prompt now comes after a longer
  passive stage, so it no longer lands on a spontaneous blink), not a fix; E2 is still needed.
- Net result per attempt: APCER pooled 17.6% to 1.7%, APCER_max 100% to 25% (the S3 adaptive screen replay,
  4.9), BPCER 58.2% to 66.7%, of which 36 of 48 rejections are the S2 head-turn defect (4.2) and 12 the
  synthetic dim pack (4.5). Every C1 bona fide attempt of S1 and S3 is accepted.

## 5. Per-frame latency

Wall-clock time of `onFrame` plus `tick` per analysed frame, JPEG decode included, 640x480, on the shared laptop
(footer of each run's `summary.md`):

| Run, suite (threads) | frames | mean | p50 | p95 | max | decode p50 | analyse p50 (YuNet + Face Mesh) | PAD p50 (every 2nd frame) | embed p50 |
|---|---|---|---|---|---|---|---|---|---|
| 2026-09-29 shipped (2) | 13,586 | 24.0 ms | 23.0 | 40.0 | 121 | 3.6 | 15.3 | 6.9 | 17.4 |
| 2026-09-29 strict (2) | 20,457 | 23.6 | 22.6 | 38.0 | 98 | 3.5 | 15.2 | 6.8 | 17.1 |
| 2026-09-29 latency (1) | 2,501 | 23.6 | 20.7 | 45.0 | 110 | 2.2 | 13.8 | 5.4 | 20.1 |
| 2026-09-30 shipped (2) | 15,927 | 68.8 | 60.0 | 154.4 | 2,468 | 7.5 | 41.3 | 18.7 | 54.7 |
| 2026-09-30 strict (2) | 22,798 | 78.4 | 74.9 | 128.6 | 274 | 10.5 | 49.3 | 22.5 | 68.3 |
| 2026-09-30 proposed (2) | 31,382 | 41.2 | 25.1 | 117.8 | 1,354 | 3.9 | 15.8 | 7.1 | 18.2 |

- The 2026-09-30 runs were made while about 25 other Java processes (other engineers' builds and clients) ran
  on the same 12 CPUs; every stage, including JPEG decode, is 2.5-3 times slower than the day before, so the
  difference is machine load, not the engine. The decisions did not change (section "Read this first").
  The load fell during the `proposed` run (its p50 is back to 25 ms while its mean and p95 stay high), so its
  row mixes both conditions.
- The target (T-PERF-01, p95 at or below 20 ms at 640x480 including decode) is **not met** even in the
  quieter runs: p95 is 38-45 ms, p50 21-23 ms. A measurement on an otherwise idle machine is still needed
  before quoting a figure outside this document.
- Face analysis is two thirds of the frame: YuNet plus the Face Mesh on every frame. The mesh is needed only for
  challenges (blink, smile, gaze and the planarity points). Proposal E4: run `detectorOnlyAnalyzer()` in the
  passive stage and the mesh only from `CHALLENGE_WAIT` on, which the liveness-onnx notes already suggest; expected
  saving about 8-10 ms per passive frame.
- Time to the passive decision is 533 ms of stream time (8 frames) today and 2.0 s with P3; both are under the
  2.5 s target (T-PERF-02).

## 6. Proposed changes

Status 2026-09-30: P1, P2, P3, E1 and E3 are adopted (section 0.1); E2 and E4 are not.

Configuration (MockSync `data/mock-sync-defaults.properties` and the clients' shipped defaults; all three are
`mosip.registration.liveness.*` keys that the resolver already accepts, and P1 is a `policy.*` key that only the
server may set):

| Id | Key | Shipped | Proposed | Evidence | Cost |
|---|---|---|---|---|---|
| P1 | `policy.registration`, and `policy.login_auth` and `policy.onboard_auth` for the operator flows | `PASSIVE_THEN_ACTIVE` | `ALWAYS_ACTIVE` | 4.1 and 4.10: pooled APCER 17.6% (shipped) to 1.7% (proposed) per attempt; the 7 passively accepted registration attack scenarios all rejected; the S3 print no longer logs in | 2 challenges on every capture and login: 6.8 s instead of 0.83 s of stream time per accepted replayed attempt |
| P2 | engine param `quality.min_sharpness` | 10 | 40 | 4.4: blur 11-12 vs bona fide 63 and above; blurred capture becomes guidance, not failed attempts | none seen in the suite |
| P3 | `passive.min_frames` | 8 | 30 | 4.3: S3 loop caught in the passive stage | passive decision 2.0 s instead of 0.53 s |

The former `proposed` suite ran exactly P1-P3 (P1 on `registration`, `login_auth` and `onboard_auth`; no scenario uses `onboard_auth` yet); since the adoption this is the `shipped` suite of `run-suites.sh`.

Engine code (liveness-core; not parameters, so not part of the proposed suite):

| Id | Change | Evidence |
|---|---|---|
| E1 | `ActiveStage.contraryTypes()`: exclude SMILE while the target is a head movement, or make the mouth ratio unmeasured beyond about 15 degrees of yaw | 4.2: TURN_LEFT 3/36, mouth ratio +25% on a turn without a smile; the parameter route breaks S1 and S3 smiles |
| E2 | `ChallengeObserver`: a blink in progress at the prompt is ignored, not `ONSET_TOO_FAST` | 4.7 |
| E3 | Active PAD aggregate: leave out frames beyond about 30 degrees of yaw | 4.8: live probability 0.15-0.3 at 40-65 degrees |
| E4 | Passive stage on the detector-only analyser, mesh from the challenges on | 5: analysis is 15 ms of a 23 ms frame |

Not proposed, with the reason: `planarity.min_residual` (4.6, no data on either side of the threshold),
`active.smile_ratio_increase` (4.2, any value that frees the turns breaks a genuine smile),
`quality.min_brightness` (4.5, the synthetic dim pack overlaps a genuine bright-enough pack).

## 7. Reproducing

```bash
python tools/eval/fetch-frame-packs.py --verify-only        # 12 bona fide packs match their manifests
python tools/eval/make-attack-packs.py --verify-only        # 31 synthetic packs match
mvn -f tools/eval/scenario-runner/pom.xml package           # runner jar + 31 tests
bash tools/eval/run-suites.sh ../run/eval/mine              # shipped, legacy, legacy-strict, latency
# "before" suites on an older engine: build that engine's runner, copy target/scenario-runner.jar and target/lib
# elsewhere, then LIVENESS_RUNNER_JAR=<copy>/scenario-runner.jar bash tools/eval/run-suites.sh <out> legacy
java -jar tools/eval/scenario-runner/target/scenario-runner.jar inspect --packs ../run/frame-packs --all --out packs.csv
```

Single experiments quoted above:

```bash
java -jar tools/eval/scenario-runner/target/scenario-runner.jar run --packs ../run/frame-packs --scenarios eval/scenarios \
     --out ../run/eval/smile --no-fail --filter '^bf-s[123]-active$' --engine-param active.smile_ratio_increase=0.25
java -jar tools/eval/scenario-runner/target/scenario-runner.jar run --packs ../run/frame-packs --scenarios eval/scenarios \
     --out ../run/eval/login --no-fail --filter '^atk-s[23]-.*-login$'
```
