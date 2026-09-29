# Liveness technology regression report

Computed with ISO/IEC 30107-3:2023 metric definitions from replayed frame packs. The attack packs are synthetic (software-made prints, screens and replays) and the frames are injected into the engine, so these figures are **technology regression results, not an ISO/IEC 30107-3 evaluation** and not comparable to laboratory results. Per ISO/IEC 30107-3, a non-zero APCER shows that an attack can succeed; zero observed errors bound the error rate but do not prove security.

## Item under test

| field | value |
|---|---|
| runs | latency |
| attempt records | 34 |
| transactions | 24 |
| model set SHA-256 | 6c1759d2bd2d9acf... |
| configuration hashes | 4 |
| platform | os Windows 10 10.0, arch amd64, java Azul Systems, Inc. 11.0.8, cpus 12, onnxruntime 1.20.0, ort_intra_op_threads 1, liveness 1.0.0-SNAPSHOT, runner 1.0.0-SNAPSHOT |

## Evaluation type (a): per attempt

| species | attempts | accepted | rejected | no response | APCER | APNRR | rejected by layer | attacker success in 3 tries |
|---|---|---|---|---|---|---|---|---|

- **APCER_max** (worst species): **n/a**
- All attack species pooled: APCER n/a (0 decisions); APNRR n/a
- **BPCER** (all bona fide conditions): 41.2% (14/34; 95% CI 24.6%-59.3% two-sided); subject-level bootstrap 95% interval 0.0%-75.0% (2000 resamples)
- **BPNRR**: 0.0% (0/34)
- Bona fide rejections by layer: challenge 13, active_pad 1


| bona fide condition | attempts | accepted | rejected | no response | BPCER | BPNRR |
|---|---|---|---|---|---|---|
| C1 | 34 | 20 | 14 | 0 | 41.2% (14/34; 95% CI 24.6%-59.3% two-sided) | 0.0% (0/34) |


| bona fide subject | attempts | accepted | rejected | no response | BPCER |
|---|---|---|---|---|---|
| S1 | 10 | 8 | 2 | 0 | 20.0% (2/10; 95% CI 2.5%-55.6% two-sided) |
| S2 | 16 | 4 | 12 | 0 | 75.0% (12/16; 95% CI 47.6%-92.7% two-sided) |
| S3 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |


## Evaluation type (b): per transaction (retries included)

| species | transactions | accepted | rejected | no response | APCER | APNRR | rejected by layer |
|---|---|---|---|---|---|---|---|

- **APCER_max** (worst species): **n/a**
- All attack species pooled: APCER n/a (0 decisions); APNRR n/a
- **BPCER** (all bona fide conditions): 16.7% (4/24; 95% CI 4.7%-37.4% two-sided); subject-level bootstrap 95% interval 0.0%-50.0% (2000 resamples)
- **BPNRR**: 0.0% (0/24)
- Bona fide rejections by layer: challenge 3, active_pad 1


| bona fide condition | transactions | accepted | rejected | no response | BPCER | BPNRR |
|---|---|---|---|---|---|---|
| C1 | 24 | 20 | 4 | 0 | 16.7% (4/24; 95% CI 4.7%-37.4% two-sided) | 0.0% (0/24) |


| bona fide subject | transactions | accepted | rejected | no response | BPCER |
|---|---|---|---|---|---|
| S1 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |
| S2 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) |
| S3 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |


## Per scenario

Method `passive` means the passive score alone passed; `passive_active` and `active` mean challenges were needed.


| scenario | kind | species | transactions | transaction outcomes | attempts | passive score (median) | accepted attempts by method | other attempts ended by |
|---|---|---|---|---|---|---|---|---|
| bf-s1-active | bona_fide | BF | 4 | accept 4 | 5 | 75 | active 4 | challenge 1 |
| bf-s1-registration | bona_fide | BF | 4 | accept 4 | 5 | 75 | passive_active 4 | challenge 1 |
| bf-s2-active | bona_fide | BF | 4 | reject 4 | 12 | 84 | - | challenge 11, active_pad 1 |
| bf-s2-registration | bona_fide | BF | 4 | accept 4 | 4 | 84 | passive 4 | - |
| bf-s3-active | bona_fide | BF | 4 | accept 4 | 4 | 100 | active 4 | - |
| bf-s3-registration | bona_fide | BF | 4 | accept 4 | 4 | 100 | passive 4 | - |


## Where attacks were stopped (per attempt)

| species | attempts stopped | before any challenge | during or after challenges |
|---|---|---|---|


## Bona fide challenges

| challenge (bona fide) | completed | median time to complete (ms) |
|---|---|---|
| BLINK | 12/14 (85.7%) | 1200 |
| SMILE | 12/12 (100.0%) | 1366 |
| TURN_LEFT | 1/12 (8.3%) | 1866 |
| TURN_RIGHT | 5/5 (100.0%) | 1600 |


## Timings

Session times are on the replayed stream clock (15 fps). Per-frame times are wall-clock time of `onFrame` plus `tick` on the machine named above (JPEG decode, face analysis, PAD every other frame).


| attempts | n | session time median / p95 (ms, stream clock) | passive stage median / p95 (ms) | frames analysed | per-frame p50 (median over attempts, ms) | per-frame p95 (worst attempt, ms) |
|---|---|---|---|---|---|---|
| bona fide | 34 | 4850 / 13566 | 533 / 533 | 2501 | 20.9 | 82.8 |
| all | 34 | 4850 / 13566 | 533 / 533 | 2501 | 20.9 | 82.8 |


## DET of the passive score

Not drawn (matplotlib missing, or no bona fide or attack attempt reached a passive score).


## Quality and behaviour scenarios (not in APCER or BPCER)

None.


## Expectation check

4 of 24 transactions did not meet the scenario's expectation.

| transaction | expected | observed | layer per attempt | reasons (last attempt) |
|---|---|---|---|---|
| bf-s2-active#1 | ACCEPT | REJECT | challenge, challenge, challenge | WRONG_ACTION |
| bf-s2-active#2 | ACCEPT | REJECT | challenge, challenge, challenge | WRONG_ACTION |
| bf-s2-active#3 | ACCEPT | REJECT | challenge, challenge, active_pad | PAD_SCORE |
| bf-s2-active#4 | ACCEPT | REJECT | challenge, challenge, challenge | WRONG_ACTION |
