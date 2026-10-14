# Scenario suites at the new shipped defaults, 2026-10-05

Three folders hold the re-run of the automated suites after the 2026-10-04 and 2026-10-05 engine and configuration changes (loop window 300 frames, model bundle 1.3.0, dim-light ending, `ALWAYS_ACTIVE` for `registration`, `login_auth`, `onboard_auth` and the three supervisor workflows (`packet_auth`, `exception_auth`, `eod_auth`), `passive.min_frames=30`, `quality.min_sharpness=40`):

| Folder | What ran | Configuration |
|---|---|---|
| `20261005-shipped` | all scenarios, 275 transactions, 755 attempt records | the shipped defaults: no global overrides, signed bundle 1.3.0 supplies `integrity.loop_window_frames=300` and `quality.min_sharpness=40` |
| `20261005-legacy` | the same 275 transactions, 517 attempt records | the configuration before 2026-09-30: `PASSIVE_THEN_ACTIVE` for all six workflows, `passive.min_frames=8` |
| `20261005-latency` | the 6 bona fide registration scenarios, 24 transactions | shipped defaults, 1 ONNX Runtime thread (the shipped and legacy suites use 2) |

Each folder holds `report.md` (metrics), `summary.md` (one row per transaction), `run.json` (models, parameters, platform) and had `attempts.jsonl` (one JSON line per attempt; never images), now in git history only ([../README.md](../README.md)). `20261005-shipped` and `20261005-legacy` also hold `det.png`; the latency run has no DET plot (no attack scenarios, and `20260929-latency` has none either).

Machine, all three runs: Windows 10 laptop, 12 logical CPUs, Java 11.0.8, ONNX Runtime 1.20.0 on CPU, model set SHA-256 `6c1759d2bd2d9acf...`. The laptop was shared with other work during the runs, so wall-clock times are noisy. The frames are replayed into the engine; this is technology regression data on synthetic attacks and three openly licensed clips, not an ISO/IEC 30107-3 evaluation.

## Which legacy run is stored

`legacy` was run twice into the same output folder. A second `run-suites.sh` for legacy alone was started at 00:16 to save time and wrote `report.md`, `summary.md`, `run.json` and `det.png` at 01:37. The first `run-suites.sh` reached its own legacy suite after `shipped` and overwrote those four files at 02:18. This folder holds the final one (02:18; `legacy.log` has the same time). The 01:37 files no longer exist and were not compared with the 02:18 ones.

## Headline: shipped defaults

Copied from the "Headline" section of `20261005-shipped/report.md`. A transaction ends at its first pass or its last allowed attempt. The report's intervals treat transactions as independent, which they are not (replays of one artefact repeat the same frames), so read the counts first; the report also gives 95% confidence intervals and the share of source artefacts accepted.

APCER (attack presentation classification error rate: the share of attack transactions the system accepted) per species per transaction and on the first attempt:

| workflow | species | transactions | APCER per transaction | APCER on the first attempt |
|---|---|---|---|---|
| eod_auth | SYN-A2 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| eod_auth | SYN-A3 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| eod_auth | SYN-A6 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| eod_auth | SYN-A8 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| exception_auth | SYN-A2 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| exception_auth | SYN-A3 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| exception_auth | SYN-A6 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| exception_auth | SYN-A8 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| login_auth | SYN-A2 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| login_auth | SYN-A6 | 4 | 0.0% (0/4) | 0.0% (0/4) |
| login_auth | bona fide (BPCER) | 12 | 0.0% (0/12) | 0.0% (0/12) |
| packet_auth | SYN-A2 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| packet_auth | SYN-A3 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| packet_auth | SYN-A6 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| packet_auth | SYN-A8 | 32 | 0.0% (0/32) | 0.0% (0/32) |
| registration | SYN-A1 | 4 | 0.0% (0/4) | 0.0% (0/4) |
| registration | SYN-A2 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| registration | SYN-A3 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| registration | SYN-A4 | 4 | 0.0% (0/4) | 0.0% (0/4) |
| registration | SYN-A5 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| registration | SYN-A6 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| registration | SYN-A7 | 8 | 50.0% (4/8) | 50.0% (4/8) |
| registration | SYN-A8 | 32 | 0.0% (0/32) | 0.0% (0/32) |
| registration | SYN-P0 | 2 | 0.0% (0/2) | 0.0% (0/2) |
| registration | bona fide (BPCER) | 28 | 14.3% (4/28) | 14.3% (4/28) |

BPCER (bona fide presentation classification error rate: the share of genuine transactions wrongly rejected), per transaction and on the first attempt, as the report states it:

- `login_auth`: 0.0% (0/12), first attempt 0.0% (0/12)
- `registration`: 14.3% (4/28; 95% CI 4.0%-32.7%), first attempt 14.3% (4/28); all 4 are `bf-s3-dim` (below)
- `eod_auth`, `exception_auth`, `packet_auth`: no bona fide scenarios, n/a

Worst species (APCER_max) per workflow, per transaction and on the first attempt:

- `eod_auth`, `exception_auth`, `login_auth`, `packet_auth`: 0.0% (0/8; 95% CI 0.0%-36.9%; one-sided 95% upper bound 31.2%), species SYN-A2 named as the maximum because every species is at 0.0%
- `registration`: 50.0% (4/8; 95% CI 15.7%-84.3%), species SYN-A7 (screen attack that adapts to the challenge). `atk-s3-adaptive-screen` is accepted in 4 of 4 seeds; `atk-s2-adaptive-screen` is rejected in 4 of 4 (active PAD). Source artefacts accepted: 1 of 2.
- Pooled attack figures (secondary): `registration` 4.9% (4/82) per transaction and on the first attempt, 1.7% (4/238) per attempt; the other four workflows 0.0%

DET (passive score alone): BPCER20 100.0% at threshold 101 for `registration` and for all workflows, 66.7% at threshold 100 for `login_auth`. The passive score alone does not separate genuine from attack here; the full engine adds challenge, integrity and planarity checks, which give the table above.

IAPAR and FRR (error rates of the full system with face matching) are not computed: no record carries a match result.

## Known mismatches against the scenario expectations (shipped)

The report's expectation check lists 9 of 275 transactions:

- `bf-s3-dim` (genuine clip in dim light, 4 seeds): expected ACCEPT, observed REJECT in all 4. Each transaction used 3 attempts, every one ending `passive_pad` with `PAD_SCORE` (passive score 3). The runner used for this run did not pass the low-light retry count to the engine, so the dim-light ending (`LOW_LIGHT`, not counted, up to 3 times) never appeared. The later runner fix for this (check run kept outside the repository in `run/eval/f01`) shows 3 `LOW_LIGHT` endings and then a counted `PAD_SCORE` failure, still REJECT, so the mismatch is not fixed. The expectation in `eval/scenarios/bf-s3-dim.yaml` stays ACCEPT. This is the whole `registration` BPCER of 14.3%.
- `atk-s3-adaptive-screen` (4 seeds): expected REJECT, observed ACCEPT in all 4; this is the 50.0% SYN-A7 figure.
- `other-s3-ignores-prompt` seed 2: expected REJECT, observed REJECT, listed because the last attempt ended in the integrity layer with reason `LOOP` (the repeated clip caught by the 300-frame loop window).

## Headline: legacy configuration

From `20261005-legacy/report.md`. Pooled attack APCER per transaction and on the first attempt: `eod_auth` 50.0% (16/32), `exception_auth` 50.0% (16/32), `login_auth` 33.3% (4/12), `packet_auth` 71.4% (40/56), `registration` 63.4% (52/82). APCER_max over all workflows 100.0% (80/80; 95% CI 95.5%-100.0%), species SYN-A8. 136 of 275 transactions did not meet the scenario expectation.

| workflow | species | transactions | APCER per transaction | APCER on the first attempt |
|---|---|---|---|---|
| eod_auth | SYN-A2 | 8 | 50.0% (4/8) | 50.0% (4/8) |
| eod_auth | SYN-A3 | 8 | 50.0% (4/8) | 50.0% (4/8) |
| eod_auth | SYN-A6 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| eod_auth | SYN-A8 | 8 | 100.0% (8/8) | 100.0% (8/8) |
| exception_auth | SYN-A2 | 8 | 50.0% (4/8) | 50.0% (4/8) |
| exception_auth | SYN-A3 | 8 | 50.0% (4/8) | 50.0% (4/8) |
| exception_auth | SYN-A6 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| exception_auth | SYN-A8 | 8 | 100.0% (8/8) | 100.0% (8/8) |
| login_auth | SYN-A2 | 8 | 50.0% (4/8) | 50.0% (4/8) |
| login_auth | SYN-A6 | 4 | 0.0% (0/4) | 0.0% (0/4) |
| login_auth | bona fide (BPCER) | 12 | 8.3% (1/12) | 25.0% (3/12) |
| packet_auth | SYN-A2 | 8 | 50.0% (4/8) | 50.0% (4/8) |
| packet_auth | SYN-A3 | 8 | 50.0% (4/8) | 50.0% (4/8) |
| packet_auth | SYN-A6 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| packet_auth | SYN-A8 | 32 | 100.0% (32/32) | 100.0% (32/32) |
| registration | SYN-A1 | 4 | 0.0% (0/4) | 0.0% (0/4) |
| registration | SYN-A2 | 8 | 50.0% (4/8) | 50.0% (4/8) |
| registration | SYN-A3 | 8 | 100.0% (8/8) | 100.0% (8/8) |
| registration | SYN-A4 | 4 | 0.0% (0/4) | 0.0% (0/4) |
| registration | SYN-A5 | 8 | 0.0% (0/8) | 0.0% (0/8) |
| registration | SYN-A6 | 8 | 50.0% (4/8) | 50.0% (4/8) |
| registration | SYN-A7 | 8 | 50.0% (4/8) | 50.0% (4/8) |
| registration | SYN-A8 | 32 | 100.0% (32/32) | 100.0% (32/32) |
| registration | SYN-P0 | 2 | 0.0% (0/2) | 0.0% (0/2) |
| registration | bona fide (BPCER) | 28 | 21.4% (6/28) | 35.7% (10/28) |

BPCER per transaction / on the first attempt: `login_auth` 8.3% (1/12) / 25.0% (3/12); `registration` 21.4% (6/28) / 35.7% (10/28). `bf-s3-dim` is REJECT in 4 of 4 as in `shipped`; `bf-s1-login#1`, `bf-s1-active#1` and `bf-s1-registration#1` end `NEUTRAL_TIMEOUT`.

## Latency (shipped defaults, 1 ONNX Runtime thread, `20261005-latency`)

24 bona fide `registration` transactions, 1 attempt each, 24 of 24 accepted, 0 expectation mismatches. Per analysed frame (decode, analysis and PAD, wall clock): n=4410, mean 79.7 ms, p50 69.4 ms, p95 162.4 ms, max 482.4 ms.

- decode: p50 6.7 ms, p95 14.5 ms
- analyze: p50 50.7 ms, p95 109.1 ms
- pad: p50 18.7 ms, p95 40.5 ms
- embed: n=152, p50 76.9 ms, p95 148.0 ms
- session time on the replayed 15 fps clock: median 11033 ms, p95 17966 ms

The shipped suite's timing table (2 threads) gives per-frame p50 67.4 ms and worst-attempt p95 1319.4 ms over all `registration` attempts; it covers attack and bona fide attempts, so it is not like-for-like with the latency run.

## Differences from 20260930-engine-fixes

Compare the two `report.md` files directly; this README does not restate the old numbers. Changed since then: bundle 1.3.0 (loop window 300), the dim-light and neutral-capture rules, the legacy suite now sets `packet_auth`, `exception_auth` and `eod_auth` to `PASSIVE_THEN_ACTIVE` (the 2026-09-30 legacy rows for those workflows are not comparable), and the report's headline section counts transactions and first attempts per species.

Regenerate with `bash tools/eval/run-suites.sh` (decisions reproduce, wall-clock timings do not).
