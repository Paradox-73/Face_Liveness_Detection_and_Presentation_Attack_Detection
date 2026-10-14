# Recorded runs of the automated suite

Outputs of `tools/eval/scenario-runner` and `tools/eval/analyze.py` quoted in [../calibration-notes.md](../calibration-notes.md). Every folder holds `attempts.jsonl` (one JSON line per attempt: decisions, reason codes, scores, timings; never images), `summary.md` (one row per transaction) and `run.json` (models, parameters, platform); the full suites also hold `report.md` (the ISO/IEC 30107-3-style metrics) and `det.png`.

The runs before 2026-10-14 keep only `report.md`, `summary.md`, `run.json` and `det.png`; their `attempts.jsonl` files (8.3 MB) left the tree on 2026-10-15 and stay in git history: `git show a1b5bfa:docs/eval/results/<folder>/attempts.jsonl`. The exception is `20260930-engine-fixes/shipped/attempts.jsonl`, which `tools/eval/tests/test_analyze.py` reads.

All of it is technology regression data on synthetic attacks and three openly licensed clips, not an ISO/IEC 30107-3 evaluation.

| Folder | What ran | Configuration |
|---|---|---|
| `20261014-shipped`, `20261014-passive-first` | all 75 scenarios, 275 transactions each, on the final engine (challenge pool without `LOOK_UP`): 0 of 226 attack transactions accepted at the shipped defaults, 100 of 226 with the passive-first flow; the current results | the shipped defaults, and the same with `PASSIVE_THEN_ACTIVE` for six workflows; start with [20261014-shipped/README.md](20261014-shipped/README.md) |
| `20261014-latency` | the 6 bona fide registration scenarios, 24 transactions | shipped defaults, 1 ONNX Runtime thread; [20261014-latency/README.md](20261014-latency/README.md) |
| `20261005b-shipped` | all 75 scenarios, 275 transactions, 755 attempt records, after two PAD additions of 2026-10-05 evening (exposure correction in a dark frame, screen-pattern check); before and after against `20261005-shipped` | the shipped defaults, as `20261005-shipped`; start with [20261005b-shipped/README.md](20261005b-shipped/README.md) |
| `20261005b-legacy`, `20261005b-latency` | the legacy and latency suites on the same engine | as `20261005-legacy` and `20261005-latency` |
| `20261005-shipped` | all 75 scenarios, 275 transactions, 755 attempt records, engine and model bundle 1.3.0 after the 2026-10-04 and 2026-10-05 changes; headline per species per transaction and on the first attempt | the shipped defaults: `ALWAYS_ACTIVE` for every workflow except `exception_photo`, `passive.min_frames=30`, loop window 300 and `quality.min_sharpness=40` from the signed bundle; start with [20261005-shipped/README.md](20261005-shipped/README.md) |
| `20261005-legacy` | the same 275 transactions, 517 attempt records | the configuration before 2026-09-30: `PASSIVE_THEN_ACTIVE` for all six workflows, `passive.min_frames=8` (see the note in 20261005-shipped/README.md on which run is stored) |
| `20261005-latency` | the 6 bona fide registration scenarios, 24 transactions | shipped defaults, 1 ONNX Runtime thread |
| `20260930-engine-fixes/shipped` | all 39 scenarios, engine after the 2026-09-30 fixes (smile unmeasured beyond 15 degrees of yaw, profile frames left out of the active PAD score, passive decision at the timeout on slow devices), model bundle 1.0.1 | the calibrated shipped defaults: `ALWAYS_ACTIVE` for `registration`, `login_auth` and `onboard_auth`, `passive.min_frames=30`, `quality.min_sharpness=40` from the signed bundle |
| `20260930-engine-fixes/legacy` | all 39 scenarios, same engine | the configuration shipped before 2026-09-30 (passive first everywhere, `passive.min_frames=8`, `quality.min_sharpness=10`): isolates the engine fixes |
| `20260930-engine-fixes/legacy-strict` | all 39 scenarios, same engine | `legacy` with `policy.registration=ALWAYS_ACTIVE` (the old `strict` suite) |
| `20260930/shipped` | all 39 scenarios, engine before the fixes ("before" of the calibration notes; re-run on 2026-09-30 with the same decisions for every attempt) | defaults shipped before 2026-09-30 |
| `20260930/strict` | all 39 scenarios | `policy.registration=ALWAYS_ACTIVE` |
| `20260930/proposed` | all 39 scenarios | the proposal P1-P3 of the calibration notes: `ALWAYS_ACTIVE` for `registration`, `login_auth` and `onboard_auth`, `passive.min_frames=30`, `quality.min_sharpness=40` |
| `20260929/shipped`, `20260929/strict` | the first 36 scenarios; only `summary.md` and `run.json` kept | as the 2026-09-30 suites; kept for the per-frame latency on a less loaded machine (decisions identical to 2026-09-30) |
| `20260930/login-probe` | the 3 `atk-*-login` scenarios alone, before they were added to the suites | shipped defaults |
| `20260929-latency` | the 6 bona fide registration and always-active scenarios | shipped defaults, 1 ONNX Runtime thread |
| `20260929-proposed-draft` | 36 scenarios (before the login ones existed) | `passive.min_frames=30`, `quality.min_sharpness=40`, `planarity.min_residual=0.05`: the first draft of the proposal, kept for the loop-detection and planarity-residual evidence |
| `experiments/smile-ratio-0.25`, `experiments/smile-ratio-0.4` | `bf-s1-active`, `bf-s2-active`, `bf-s3-active` | `active.smile_ratio_increase` 0.25 and 0.4 (shipped 0.12), the rejected parameter fix for the head-turn defect |

Regenerate with `bash tools/eval/run-suites.sh` (see [../README.md](../README.md)); the decisions are reproducible, the wall-clock timings are not.
