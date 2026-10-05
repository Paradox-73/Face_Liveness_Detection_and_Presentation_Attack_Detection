# Liveness technology regression report

Computed with ISO/IEC 30107-3:2023 metric definitions from replayed frame packs. The attack packs are synthetic (software-made prints, screens and replays) and the frames are injected into the engine, so these figures are **technology regression results, not an ISO/IEC 30107-3 evaluation** and not comparable to laboratory results. Per ISO/IEC 30107-3, a non-zero APCER shows that an attack can succeed; zero observed errors bound the error rate but do not prove security.

## Item under test

| field | value |
|---|---|
| runs | latency |
| attempt records | 24 |
| transactions | 24 |
| transactions per workflow | registration 24 |
| model set SHA-256 | 6c1759d2bd2d9acf... |
| configuration hashes | 2 |
| platform | os Windows 10 10.0, arch amd64, java Azul Systems, Inc. 11.0.8, cpus 12, onnxruntime 1.20.0, ort_intra_op_threads 1, ort_execution_provider CPU, liveness 1.0.0-SNAPSHOT, runner 1.0.0-SNAPSHOT |

## Headline: APCER per species per transaction and on the first attempt

Unit: the **transaction** (one capture until the first accepted attempt or the last allowed attempt) and the **first attempt** of every transaction. The runner and the clients both end a transaction at its first pass, so a species that passes at once leaves one accepted attempt while a species that never passes leaves every allowed attempt; per-attempt rates mix the two and are secondary (section (a)).

Intervals in the transaction and first-attempt columns treat every transaction as an independent trial. Transactions are not independent: replays of one artefact repeat the same frames. The column 'source artefacts accepted' counts each source artefact once (accepted in at least one transaction) and its interval is over artefacts. `packs / subjects` = distinct frame packs (or recorded artefact ids; '-' when none is recorded) and distinct source subjects behind the row.

| workflow | species | packs / subjects | transactions | APCER per transaction | APCER on the first attempt | source artefacts accepted (CI over artefacts) | APNRR per transaction |
|---|---|---|---|---|---|---|---|
| registration | bona fide (BPCER) | 9 / 3 | 24 | 0.0% (0/24; CI 0.0%-14.2%; one-sided <= 11.7%) | 0.0% (0/24; CI 0.0%-14.2%; one-sided <= 11.7%) | - | 0.0% (0/24) |

- Workflow **registration**: APCER_max per transaction n/a (no attack decisions); APCER_max on the first attempt n/a (no attack decisions); BPCER per transaction 0.0% (0/24; 95% CI 0.0%-14.2% two-sided; one-sided 95% upper bound 11.7%), on the first attempt 0.0% (0/24)


## Evaluation type (b): per transaction (retries included)

| workflow | species | transactions | accepted | rejected | no response | APCER (95% CI; transactions not independent) | APNRR | packs / subjects | rejected by layer |
|---|---|---|---|---|---|---|---|---|---|

- Workflow **registration**: **APCER_max** (worst species) n/a (no attack decisions)
  - **BPCER** (all bona fide conditions): 0.0% (0/24; 95% CI 0.0%-14.2% two-sided; one-sided 95% upper bound 11.7%); subject-level bootstrap 95% interval 0.0%-0.0% (2000 resamples); **BPNRR** 0.0% (0/24)


| workflow | bona fide condition | transactions | accepted | rejected | no response | BPCER | BPNRR | packs / subjects |
|---|---|---|---|---|---|---|---|---|
| registration | C1 | 24 | 24 | 0 | 0 | 0.0% (0/24; 95% CI 0.0%-14.2% two-sided; one-sided 95% upper bound 11.7%) | 0.0% (0/24) | 9 / 3 |


| workflow | bona fide subject | transactions | accepted | rejected | no response | BPCER |
|---|---|---|---|---|---|---|
| registration | S1 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |
| registration | S2 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |
| registration | S3 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |


## First attempt of each transaction

Attempt 1 of every transaction only: what an attacker gets from one try, before any retry.


| workflow | species | first attempts | accepted | rejected | no response | APCER (95% CI; first attempts not independent) | APNRR | packs / subjects | rejected by layer |
|---|---|---|---|---|---|---|---|---|---|

- Workflow **registration**: **APCER_max** (worst species) n/a (no attack decisions)
  - **BPCER** (all bona fide conditions): 0.0% (0/24; 95% CI 0.0%-14.2% two-sided; one-sided 95% upper bound 11.7%); subject-level bootstrap 95% interval 0.0%-0.0% (2000 resamples); **BPNRR** 0.0% (0/24)


| workflow | bona fide condition | first attempts | accepted | rejected | no response | BPCER | BPNRR | packs / subjects |
|---|---|---|---|---|---|---|---|---|
| registration | C1 | 24 | 24 | 0 | 0 | 0.0% (0/24; 95% CI 0.0%-14.2% two-sided; one-sided 95% upper bound 11.7%) | 0.0% (0/24) | 9 / 3 |


| workflow | bona fide subject | first attempts | accepted | rejected | no response | BPCER |
|---|---|---|---|---|---|---|
| registration | S1 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |
| registration | S2 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |
| registration | S3 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |


## Evaluation type (a): per attempt (secondary)

Every attempt counted. A transaction ends at its first pass, so this level undercounts a species that passes at once; read the per-transaction and first-attempt figures above first.


| workflow | species | attempts | accepted | rejected | no response | APCER (95% CI; attempts not independent) | APNRR | packs / subjects | rejected by layer | attacker success in 3 tries (if attempts were independent) |
|---|---|---|---|---|---|---|---|---|---|---|

- Workflow **registration**: **APCER_max** (worst species) n/a (no attack decisions)
  - **BPCER** (all bona fide conditions): 0.0% (0/24; 95% CI 0.0%-14.2% two-sided; one-sided 95% upper bound 11.7%); subject-level bootstrap 95% interval 0.0%-0.0% (2000 resamples); **BPNRR** 0.0% (0/24)


| workflow | bona fide condition | attempts | accepted | rejected | no response | BPCER | BPNRR | packs / subjects |
|---|---|---|---|---|---|---|---|---|
| registration | C1 | 24 | 24 | 0 | 0 | 0.0% (0/24; 95% CI 0.0%-14.2% two-sided; one-sided 95% upper bound 11.7%) | 0.0% (0/24) | 9 / 3 |


| workflow | bona fide subject | attempts | accepted | rejected | no response | BPCER |
|---|---|---|---|---|---|---|
| registration | S1 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |
| registration | S2 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |
| registration | S3 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |


## Genuine clips of the wrong action (not in APCER or BPCER)

Species SYN-A9: a genuine face answering with a clip of the wrong action. These are bona fide presentations, so they test the challenge check, not presentation attack detection; they are left out of APCER, BPCER and the DET curve. The correct result is a refusal.

None in these records.


## Full-system results: IAPAR and FRR

No attempt record carries a face-match result (`match_outcome`), so IAPAR and FRR are not computed. The results sheet supplies it for login rows (tools/eval/sheet-to-attempts.py).


## Per scenario

Method `passive` means the passive score alone passed; `passive_active` and `active` mean challenges were needed.


| workflow | scenario | kind | species | transactions | transaction outcomes | accepted on the first attempt | attempts | passive score (median) | accepted attempts by method | other attempts ended by |
|---|---|---|---|---|---|---|---|---|---|---|
| registration | bf-s1-active | bona_fide | BF | 4 | accept 4 | 4/4 | 4 | 64 | active 4 | - |
| registration | bf-s1-registration | bona_fide | BF | 4 | accept 4 | 4/4 | 4 | 64 | active 4 | - |
| registration | bf-s2-active | bona_fide | BF | 4 | accept 4 | 4/4 | 4 | 94 | active 4 | - |
| registration | bf-s2-registration | bona_fide | BF | 4 | accept 4 | 4/4 | 4 | 94 | active 4 | - |
| registration | bf-s3-active | bona_fide | BF | 4 | accept 4 | 4/4 | 4 | 100 | active 4 | - |
| registration | bf-s3-registration | bona_fide | BF | 4 | accept 4 | 4/4 | 4 | 100 | active 4 | - |


## Where attacks were stopped (per attempt)

| workflow | species | attempts stopped | before any challenge | during or after challenges |
|---|---|---|---|---|


## Bona fide challenges

| workflow | challenge (bona fide) | completed | median time to complete (ms) |
|---|---|---|---|
| registration | BLINK | 16/16 (100.0%) | 1233 |
| registration | SMILE | 16/16 (100.0%) | 1033 |
| registration | TURN_LEFT | 8/8 (100.0%) | 1666 |
| registration | TURN_RIGHT | 8/8 (100.0%) | 1800 |


## Timings

Session times are on the replayed stream clock (15 fps). Per-frame times are wall-clock time of `onFrame` plus `tick` on the machine named above (JPEG decode, face analysis, PAD every other frame).


| workflow | attempts | n | session time median / p95 (ms, stream clock) | passive stage median / p95 (ms) | frames analysed | per-frame p50 (median over attempts, ms) | per-frame p95 (worst attempt, ms) |
|---|---|---|---|---|---|---|---|
| registration | bona fide | 24 | 11033 / 17966 | 2000 / 2000 | 4410 | 64.3 | 308.1 |
| registration | all | 24 | 11033 / 17966 | 2000 / 2000 | 4410 | 64.3 | 308.1 |


## DET of the passive score

Not drawn (matplotlib missing, or no bona fide or attack attempt reached a passive score).


## Quality and behaviour scenarios (not in APCER or BPCER)

None.


## Expectation check

0 of 24 transactions did not meet the scenario's expectation.

