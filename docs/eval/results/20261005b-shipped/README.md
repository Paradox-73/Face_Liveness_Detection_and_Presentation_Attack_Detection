# Scenario suites after the exposure correction and the screen-pattern check, 2026-10-05 (evening)

Re-run of the automated suites after two additions to the passive PAD (`liveness-onnx` `MiniFasPassivePad`, see
[../../../design/engine.md](../../../design/engine.md) section 4):

- **Exposure correction:** a dark frame (mean grey level below 90) is brightened before the MiniFASNet models score it.
- **Screen-pattern check:** isolated peaks in the spatial frequency spectrum of the face (a screen's pixel grid and
  moire) cap the PAD score.

Nothing else changed: same scenarios, frame packs, signed model bundle 1.3.0, shipped configuration and runner
(low-light tracking on). The "before" run is [../20261005-shipped/](../20261005-shipped/), made the same morning.

| Folder | What ran | Configuration |
|---|---|---|
| `20261005b-shipped` | all 75 scenarios, 275 transactions, 755 attempt records | shipped defaults, as `20261005-shipped` |
| `20261005b-legacy` | the same 275 transactions | the configuration before 2026-09-30 (passive first on every workflow, 8 passive frames), as `20261005-legacy` |
| `20261005b-latency` | 6 bona fide registration scenarios, 24 transactions | shipped defaults, 1 ONNX Runtime thread |

Machine: the same Windows laptop (12 logical CPUs, Java 11.0.8, ONNX Runtime 1.20.0 on CPU, model set SHA-256
`6c1759d2bd2d9acf...`), shared with a desktop client build, an Android APK build and an emulator during the runs, so
wall-clock times are noisy. Frames are replayed into the engine; this is technology regression data on synthetic
attacks and three openly licensed clips, not an ISO/IEC 30107-3 evaluation.

## Before and after, shipped defaults

Per transaction (one capture until the first accepted attempt or the last allowed attempt); in both runs the
first-attempt figures equal the per-transaction ones.

| Measure | Before (`20261005-shipped`) | After (`20261005b-shipped`) |
|---|---|---|
| APCER, `registration`, SYN-A7 (adaptive screen replay) | 50.0% (4/8): `atk-s3-adaptive-screen` accepted in 4 of 4 seeds | **0.0% (0/8)**: rejected in 4 of 4 seeds, passive PAD score 0 on each attempt |
| APCER, every other species and workflow | 0.0% | 0.0% (no change) |
| BPCER, `registration` | 14.3% (4/28), all from `bf-s3-dim` | **0.0% (0/28)**: `bf-s3-dim` accepted on the first attempt, passive score 55 (was 3) |
| BPCER, `login_auth` | 0.0% (0/12) | 0.0% (0/12) |
| Passive score of the other bona fide scenarios | S1 64, S2 94, S3 100 | unchanged: S1 64, S2 94, S3 100 |
| Transactions that missed their scenario expectation | 9 of 275 | 1 of 275 (`other-s3-ignores-prompt` seed 2 is rejected by the loop check, where the scenario expects the challenge layer; the same miss as before) |
| Per analysed frame, latency suite (1 thread, 24 bona fide transactions, shared laptop) | p50 69.4 ms, p95 162.4 ms; PAD step p50 18.7 ms | p50 65.7 ms, p95 242.4 ms; PAD step p50 18.7 ms (the PAD step, which now includes the screen-pattern check, costs the same at the median; the p95 rose with the load of the Android tests running at the same time) |

## Before and after, legacy configuration (passive first everywhere, 8 passive frames)

| Measure | Before (`20261005-legacy`) | After (`20261005b-legacy`) |
|---|---|---|
| APCER per transaction, `registration`, SYN-A6 (screen replay) | 50.0% (4/8) | **0.0% (0/8)** |
| APCER per transaction, `registration`, SYN-A7 (adaptive screen replay) | 50.0% (4/8) | **0.0% (0/8)** |
| Pooled attack APCER per transaction, `registration` (secondary) | 63.4% (52/82) | 53.7% (44/82) |
| Other workflows, pooled per transaction | `eod_auth` 50.0%, `exception_auth` 50.0%, `login_auth` 33.3%, `packet_auth` 71.4% | unchanged |
| APCER_max | 100.0% (prints and looped clips pass a passive-only check) | 100.0%, unchanged: this is why the shipped defaults ask for challenges |
| BPCER, `registration`, per transaction / first attempt | 21.4% (6/28) / 35.7% (10/28) | 7.1% (2/28) / 21.4% (6/28) |
| BPCER, `login_auth` | 8.3% (1/12) | 8.3% (1/12) |
| Transactions that missed their expectation | 136 of 275 | 124 of 275 |

What this does and does not show:

- The S3 adaptive replay was the one synthetic attack that passed every layer (calibration notes 4.9). The screen
  look of `tools/eval/synth.py` puts a pixel grid with a 3-pixel period and moire on the face; the new check finds
  those peaks. A real screen filmed by a real camera leaves a grid and moire too, but at other frequencies and
  strengths: the effect on real replays is unknown until the volunteer test (`docs/eval/protocol.md`).
- `bf-s3-dim` is the S3 footage darkened to 45% with extra noise; a real dim room with an auto-exposure camera looks
  different. The darker-skinned subject S1 in normal light (frame grey level 105) is not touched by the correction
  and keeps its score of 64.
- Known false-alarm risk of the screen check: frames enlarged by a whole-number or 1.5x factor before they reach the
  engine show the same kind of peaks. No such frames are in the suite; the volunteer and live-person runs must
  include the real cameras.

The full metrics, intervals and the per-scenario table are in `report.md` and `summary.md`.
