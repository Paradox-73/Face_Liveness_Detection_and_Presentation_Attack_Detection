# Scenario suites of 2026-10-14: shipped defaults against the PS04 passive-first flow

Two runs of all 75 scenarios (275 transactions each) with the real engine, the signed model bundle 1.3.0 and ONNX
Runtime 1.20.0 on CPU, on commit `f96416a` (the default challenge pool without `LOOK_UP`):

| Folder | Configuration | Attempt records |
|---|---|---|
| `20261014-shipped` | shipped defaults: `ALWAYS_ACTIVE` for `registration`, `login_auth`, `onboard_auth`, `packet_auth`, `exception_auth` and `eod_auth`; pool `BLINK,SMILE,TURN_LEFT,TURN_RIGHT`; 30 passive frames | 755 |
| [`20261014-passive-first`](../20261014-passive-first/) | the same, except that those six workflows use `PASSIVE_THEN_ACTIVE`, the flow the problem statement describes (challenges only after a passive score below the threshold: 80 for `registration`, 95 for the authentication workflows) | 555 |

The question the runs answer: can the shipped default follow the statement's passive-first flow without letting an
attack in the existing attack set through? The answer is no.

## Result

Per transaction (one capture until the first accepted attempt or the last allowed attempt); the first-attempt
figures are the same in both runs.

| Measure | Shipped (`ALWAYS_ACTIVE`) | Passive first (`PASSIVE_THEN_ACTIVE`) |
|---|---|---|
| Attack transactions accepted, all species and workflows | **0 of 226** | **100 of 226** |
| APCER_max, `registration` | 0.0% | 87.5% (28/32; 95% CI 71.0%-96.5%), SYN-A8 looped clip |
| APCER_max, `packet_auth` | 0.0% | 87.5% (28/32), SYN-A8 |
| APCER_max, `login_auth`, `eod_auth`, `exception_auth` | 0.0% | 50.0% (4/8; 95% CI 15.7%-84.3%), SYN-A2 still print |
| SYN-A2 still print and SYN-A3 moved print | 0.0% in every workflow | 50.0% (4/8) in every workflow that has them |
| SYN-A6 screen replay, SYN-A7 adaptive screen replay | 0.0% | 0.0% |
| BPCER, `registration` / `login_auth` | 0.0% (0/28) / 0.0% (0/12) | 0.0% (0/28) / 0.0% (0/12) |
| Transactions that missed their scenario expectation | 2 of 275 (`other-s3-ignores-prompt` seeds 1 and 2, refused by the loop check where the scenario expects the challenge layer) | 102 of 275 (the 100 accepted attack transactions and the same 2) |

What the passive-first numbers are made of (from `attempts.jsonl`, passive score of the accepted attempt):

- The S3 print, still and moved (`atk-s3-print-still*`, `atk-s3-print-moved*`), scores 99 in every workflow, above
  both thresholds, so it passes on the passive stage and never meets a challenge. The S2 print scores below the
  threshold, gets a challenge and fails it. One of two source prints accepted gives the 4/8 rows.
- Looped clips of genuine video (`atk-s2-looped-clip*`, scores 96-97, and `atk-s3-looped-clip-2s/4s/8s*`, score
  100) pass on the 30 passive frames before a repeat is detected. 28 of 32 looped-clip transactions are accepted
  in `registration` and in `packet_auth`.
- With `ALWAYS_ACTIVE` the same prints and clips must answer randomly drawn challenges, which they cannot, so every
  one is refused (challenge layer or loop check).

Decision: the shipped default stays `ALWAYS_ACTIVE` for these six workflows. `PASSIVE_THEN_ACTIVE` stays available
per workflow by one synced setting (`mosip.registration.liveness.policy.<workflow>`), for a deployment that accepts
this risk or has a stronger passive model. The genuine-user cost of challenges on every capture is time, not
rejections: BPCER is 0.0% in both runs.

## Limits

- Every attack is synthetic (software-made prints, screens and replays from three openly licensed clips); this is
  technology regression data, not an ISO/IEC 30107-3 evaluation. The volunteer protocol is
  [`docs/eval/protocol.md`](../../protocol.md); the team's steps are in
  [`docs/manual-test-checklist.md`](../../../manual-test-checklist.md).
- Three bona fide subjects only, so the BPCER intervals are wide (registration 0.0%, 95% CI 0.0%-12.3%).
- How the runs were made: each suite ran as three scenario-runner processes in parallel, each with a `--filter`
  over a third of the scenarios, and the three `attempts.jsonl` files were joined before
  `tools/eval/analyze.py`. Each transaction is independent of the others, so the split does not change a decision.
  The laptop (12 logical CPUs) also ran other builds; per-frame times in `report.md` are not latency figures (see
  the latency run in the main README).
