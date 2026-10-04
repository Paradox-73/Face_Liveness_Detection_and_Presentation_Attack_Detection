# Liveness technology regression report

Computed with ISO/IEC 30107-3:2023 metric definitions from replayed frame packs. The attack packs are synthetic (software-made prints, screens and replays) and the frames are injected into the engine, so these figures are **technology regression results, not an ISO/IEC 30107-3 evaluation** and not comparable to laboratory results. Per ISO/IEC 30107-3, a non-zero APCER shows that an attack can succeed; zero observed errors bound the error rate but do not prove security.

## Item under test

| field | value |
|---|---|
| runs | legacy |
| attempt records | 517 |
| transactions | 275 |
| transactions per workflow | eod_auth 32, exception_auth 32, login_auth 24, packet_auth 56, registration 131 |
| model set SHA-256 | 6c1759d2bd2d9acf... |
| configuration hashes | 6 |
| platform | os Windows 10 10.0, arch amd64, java Azul Systems, Inc. 11.0.8, cpus 12, onnxruntime 1.20.0, ort_intra_op_threads 2, ort_execution_provider CPU, liveness 1.0.0-SNAPSHOT, runner 1.0.0-SNAPSHOT |

## Headline: APCER per species per transaction and on the first attempt

Unit: the **transaction** (one capture until the first accepted attempt or the last allowed attempt) and the **first attempt** of every transaction. The runner and the clients both end a transaction at its first pass, so a species that passes at once leaves one accepted attempt while a species that never passes leaves every allowed attempt; per-attempt rates mix the two and are secondary (section (a)).

Intervals in the transaction and first-attempt columns treat every transaction as an independent trial. Transactions are not independent: replays of one artefact repeat the same frames. The column 'source artefacts accepted' counts each source artefact once (accepted in at least one transaction) and its interval is over artefacts. `packs / subjects` = distinct frame packs (or recorded artefact ids; '-' when none is recorded) and distinct source subjects behind the row.

| workflow | species | packs / subjects | transactions | APCER per transaction | APCER on the first attempt | source artefacts accepted (CI over artefacts) | APNRR per transaction |
|---|---|---|---|---|---|---|---|
| eod_auth | SYN-A2 | 2 / 2 | 8 | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (1/2; CI 1.3%-98.7%) | 0.0% (0/8) |
| eod_auth | SYN-A3 | 2 / 2 | 8 | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (1/2; CI 1.3%-98.7%) | 0.0% (0/8) |
| eod_auth | SYN-A6 | 2 / 2 | 8 | 0.0% (0/8; CI 0.0%-36.9%; one-sided <= 31.2%) | 0.0% (0/8; CI 0.0%-36.9%; one-sided <= 31.2%) | 0.0% (0/2; CI 0.0%-84.2%; one-sided <= 77.6%) | 0.0% (0/8) |
| eod_auth | SYN-A8 | 2 / 2 | 8 | 100.0% (8/8; CI 63.1%-100.0%) | 100.0% (8/8; CI 63.1%-100.0%) | 100.0% (2/2; CI 15.8%-100.0%) | 0.0% (0/8) |
| exception_auth | SYN-A2 | 2 / 2 | 8 | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (1/2; CI 1.3%-98.7%) | 0.0% (0/8) |
| exception_auth | SYN-A3 | 2 / 2 | 8 | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (1/2; CI 1.3%-98.7%) | 0.0% (0/8) |
| exception_auth | SYN-A6 | 2 / 2 | 8 | 0.0% (0/8; CI 0.0%-36.9%; one-sided <= 31.2%) | 0.0% (0/8; CI 0.0%-36.9%; one-sided <= 31.2%) | 0.0% (0/2; CI 0.0%-84.2%; one-sided <= 77.6%) | 0.0% (0/8) |
| exception_auth | SYN-A8 | 2 / 2 | 8 | 100.0% (8/8; CI 63.1%-100.0%) | 100.0% (8/8; CI 63.1%-100.0%) | 100.0% (2/2; CI 15.8%-100.0%) | 0.0% (0/8) |
| login_auth | SYN-A2 | 2 / 2 | 8 | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (1/2; CI 1.3%-98.7%) | 0.0% (0/8) |
| login_auth | SYN-A6 | 1 / 1 | 4 | 0.0% (0/4; CI 0.0%-60.2%; one-sided <= 52.7%) | 0.0% (0/4; CI 0.0%-60.2%; one-sided <= 52.7%) | 0.0% (0/1; CI 0.0%-97.5%; one-sided <= 95.0%) | 0.0% (0/4) |
| login_auth | bona fide (BPCER) | 7 / 3 | 12 | 8.3% (1/12; CI 0.2%-38.5%) | 25.0% (3/12; CI 5.5%-57.2%) | - | 0.0% (0/12) |
| packet_auth | SYN-A2 | 2 / 2 | 8 | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (1/2; CI 1.3%-98.7%) | 0.0% (0/8) |
| packet_auth | SYN-A3 | 2 / 2 | 8 | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (1/2; CI 1.3%-98.7%) | 0.0% (0/8) |
| packet_auth | SYN-A6 | 2 / 2 | 8 | 0.0% (0/8; CI 0.0%-36.9%; one-sided <= 31.2%) | 0.0% (0/8; CI 0.0%-36.9%; one-sided <= 31.2%) | 0.0% (0/2; CI 0.0%-84.2%; one-sided <= 77.6%) | 0.0% (0/8) |
| packet_auth | SYN-A8 | 8 / 2 | 32 | 100.0% (32/32; CI 89.1%-100.0%) | 100.0% (32/32; CI 89.1%-100.0%) | 100.0% (2/2; CI 15.8%-100.0%) | 0.0% (0/32) |
| registration | SYN-A1 | 2 / 2 | 4 | 0.0% (0/4; CI 0.0%-60.2%; one-sided <= 52.7%) | 0.0% (0/4; CI 0.0%-60.2%; one-sided <= 52.7%) | 0.0% (0/2; CI 0.0%-84.2%; one-sided <= 77.6%) | 0.0% (0/4) |
| registration | SYN-A2 | 2 / 2 | 8 | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (1/2; CI 1.3%-98.7%) | 0.0% (0/8) |
| registration | SYN-A3 | 2 / 2 | 8 | 100.0% (8/8; CI 63.1%-100.0%) | 100.0% (8/8; CI 63.1%-100.0%) | 100.0% (2/2; CI 15.8%-100.0%) | 0.0% (0/8) |
| registration | SYN-A4 | 3 / 1 | 4 | 0.0% (0/4; CI 0.0%-60.2%; one-sided <= 52.7%) | 0.0% (0/4; CI 0.0%-60.2%; one-sided <= 52.7%) | 0.0% (0/1; CI 0.0%-97.5%; one-sided <= 95.0%) | 0.0% (0/4) |
| registration | SYN-A5 | 2 / 2 | 8 | 0.0% (0/8; CI 0.0%-36.9%; one-sided <= 31.2%) | 0.0% (0/8; CI 0.0%-36.9%; one-sided <= 31.2%) | 0.0% (0/2; CI 0.0%-84.2%; one-sided <= 77.6%) | 0.0% (0/8) |
| registration | SYN-A6 | 2 / 2 | 8 | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (1/2; CI 1.3%-98.7%) | 0.0% (0/8) |
| registration | SYN-A7 | 2 / 2 | 8 | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (4/8; CI 15.7%-84.3%) | 50.0% (1/2; CI 1.3%-98.7%) | 0.0% (0/8) |
| registration | SYN-A8 | 8 / 2 | 32 | 100.0% (32/32; CI 89.1%-100.0%) | 100.0% (32/32; CI 89.1%-100.0%) | 100.0% (2/2; CI 15.8%-100.0%) | 0.0% (0/32) |
| registration | SYN-P0 | 1 / 1 | 2 | 0.0% (0/2; CI 0.0%-84.2%; one-sided <= 77.6%) | 0.0% (0/2; CI 0.0%-84.2%; one-sided <= 77.6%) | 0.0% (0/1; CI 0.0%-97.5%; one-sided <= 95.0%) | 0.0% (0/2) |
| registration | bona fide (BPCER) | 10 / 3 | 28 | 21.4% (6/28; CI 8.3%-41.0%) | 35.7% (10/28; CI 18.6%-55.9%) | - | 0.0% (0/28) |

- Workflow **eod_auth**: APCER_max per transaction **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A8; APCER_max on the first attempt **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A8; BPCER per transaction n/a (0 decisions), on the first attempt n/a
- Workflow **exception_auth**: APCER_max per transaction **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A8; APCER_max on the first attempt **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A8; BPCER per transaction n/a (0 decisions), on the first attempt n/a
- Workflow **login_auth**: APCER_max per transaction **50.0% (4/8; 95% CI 15.7%-84.3% two-sided)**, species SYN-A2; APCER_max on the first attempt **50.0% (4/8; 95% CI 15.7%-84.3% two-sided)**, species SYN-A2; BPCER per transaction 8.3% (1/12; 95% CI 0.2%-38.5% two-sided), on the first attempt 25.0% (3/12)
- Workflow **packet_auth**: APCER_max per transaction **100.0% (32/32; 95% CI 89.1%-100.0% two-sided)**, species SYN-A8; APCER_max on the first attempt **100.0% (32/32; 95% CI 89.1%-100.0% two-sided)**, species SYN-A8; BPCER per transaction n/a (0 decisions), on the first attempt n/a
- Workflow **registration**: APCER_max per transaction **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A3; APCER_max on the first attempt **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A3; BPCER per transaction 21.4% (6/28; 95% CI 8.3%-41.0% two-sided), on the first attempt 35.7% (10/28)


- Secondary, workflow eod_auth, all attack species pooled: per transaction 50.0% (16/32), first attempt 50.0% (16/32), per attempt 25.0% (16/64)
- Secondary, workflow exception_auth, all attack species pooled: per transaction 50.0% (16/32), first attempt 50.0% (16/32), per attempt 25.0% (16/64)
- Secondary, workflow login_auth, all attack species pooled: per transaction 33.3% (4/12), first attempt 33.3% (4/12), per attempt 14.3% (4/28)
- Secondary, workflow packet_auth, all attack species pooled: per transaction 71.4% (40/56), first attempt 71.4% (40/56), per attempt 45.5% (40/88)
- Secondary, workflow registration, all attack species pooled: per transaction 63.4% (52/82), first attempt 63.4% (52/82), per attempt 36.6% (52/142)
- Secondary, all workflows pooled: APCER_max per transaction 100.0% (80/80; 95% CI 95.5%-100.0% two-sided), species SYN-A8; on the first attempt 100.0% (80/80; 95% CI 95.5%-100.0% two-sided), species SYN-A8


## Evaluation type (b): per transaction (retries included)

| workflow | species | transactions | accepted | rejected | no response | APCER (95% CI; transactions not independent) | APNRR | packs / subjects | rejected by layer |
|---|---|---|---|---|---|---|---|---|---|
| eod_auth | SYN-A2 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 4 |
| eod_auth | SYN-A3 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 4 |
| eod_auth | SYN-A6 | 8 | 0 | 8 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) | 0.0% (0/8) | 2 / 2 | passive_pad 4, challenge 4 |
| eod_auth | SYN-A8 | 8 | 8 | 0 | 0 | 100.0% (8/8; 95% CI 63.1%-100.0% two-sided) | 0.0% (0/8) | 2 / 2 | - |
| exception_auth | SYN-A2 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 4 |
| exception_auth | SYN-A3 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 4 |
| exception_auth | SYN-A6 | 8 | 0 | 8 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) | 0.0% (0/8) | 2 / 2 | passive_pad 4, challenge 4 |
| exception_auth | SYN-A8 | 8 | 8 | 0 | 0 | 100.0% (8/8; 95% CI 63.1%-100.0% two-sided) | 0.0% (0/8) | 2 / 2 | - |
| login_auth | SYN-A2 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 4 |
| login_auth | SYN-A6 | 4 | 0 | 4 | 0 | 0.0% (0/4; 95% CI 0.0%-60.2% two-sided; one-sided 95% upper bound 52.7%) | 0.0% (0/4) | 1 / 1 | challenge 4 |
| packet_auth | SYN-A2 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 4 |
| packet_auth | SYN-A3 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 4 |
| packet_auth | SYN-A6 | 8 | 0 | 8 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) | 0.0% (0/8) | 2 / 2 | passive_pad 4, challenge 4 |
| packet_auth | SYN-A8 | 32 | 32 | 0 | 0 | 100.0% (32/32; 95% CI 89.1%-100.0% two-sided) | 0.0% (0/32) | 8 / 2 | - |
| registration | SYN-A1 | 4 | 0 | 4 | 0 | 0.0% (0/4; 95% CI 0.0%-60.2% two-sided; one-sided 95% upper bound 52.7%) | 0.0% (0/4) | 2 / 2 | integrity 4 |
| registration | SYN-A2 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 4 |
| registration | SYN-A3 | 8 | 8 | 0 | 0 | 100.0% (8/8; 95% CI 63.1%-100.0% two-sided) | 0.0% (0/8) | 2 / 2 | - |
| registration | SYN-A4 | 4 | 0 | 4 | 0 | 0.0% (0/4; 95% CI 0.0%-60.2% two-sided; one-sided 95% upper bound 52.7%) | 0.0% (0/4) | 3 / 1 | challenge 4 |
| registration | SYN-A5 | 8 | 0 | 8 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) | 0.0% (0/8) | 2 / 2 | passive_pad 4, challenge 2, integrity 2 |
| registration | SYN-A6 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | passive_pad 4 |
| registration | SYN-A7 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | passive_pad 4 |
| registration | SYN-A8 | 32 | 32 | 0 | 0 | 100.0% (32/32; 95% CI 89.1%-100.0% two-sided) | 0.0% (0/32) | 8 / 2 | - |
| registration | SYN-P0 | 2 | 0 | 2 | 0 | 0.0% (0/2; 95% CI 0.0%-84.2% two-sided; one-sided 95% upper bound 77.6%) | 0.0% (0/2) | 1 / 1 | integrity 2 |

- Workflow **eod_auth**: **APCER_max** (worst species) **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A8
  - **BPCER** (all bona fide conditions): n/a (0 decisions); **BPNRR** n/a
  - Secondary, all attack species pooled: APCER 50.0% (16/32; 95% CI 31.9%-68.1% two-sided); APNRR 0.0% (0/32)
- Workflow **exception_auth**: **APCER_max** (worst species) **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A8
  - **BPCER** (all bona fide conditions): n/a (0 decisions); **BPNRR** n/a
  - Secondary, all attack species pooled: APCER 50.0% (16/32; 95% CI 31.9%-68.1% two-sided); APNRR 0.0% (0/32)
- Workflow **login_auth**: **APCER_max** (worst species) **50.0% (4/8; 95% CI 15.7%-84.3% two-sided)**, species SYN-A2
  - **BPCER** (all bona fide conditions): 8.3% (1/12; 95% CI 0.2%-38.5% two-sided); subject-level bootstrap 95% interval 0.0%-25.0% (2000 resamples); **BPNRR** 0.0% (0/12)
  - Bona fide rejections by layer: challenge 1
  - Secondary, all attack species pooled: APCER 33.3% (4/12; 95% CI 9.9%-65.1% two-sided); APNRR 0.0% (0/12)
- Workflow **packet_auth**: **APCER_max** (worst species) **100.0% (32/32; 95% CI 89.1%-100.0% two-sided)**, species SYN-A8
  - **BPCER** (all bona fide conditions): n/a (0 decisions); **BPNRR** n/a
  - Secondary, all attack species pooled: APCER 71.4% (40/56; 95% CI 57.8%-82.7% two-sided); APNRR 0.0% (0/56)
- Workflow **registration**: **APCER_max** (worst species) **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A3
  - **BPCER** (all bona fide conditions): 21.4% (6/28; 95% CI 8.3%-41.0% two-sided); subject-level bootstrap 95% interval 0.0%-33.3% (2000 resamples); **BPNRR** 0.0% (0/28)
  - Bona fide rejections by layer: passive_pad 4, challenge 2
  - Secondary, all attack species pooled: APCER 63.4% (52/82; 95% CI 52.0%-73.8% two-sided); APNRR 0.0% (0/82)


| workflow | bona fide condition | transactions | accepted | rejected | no response | BPCER | BPNRR | packs / subjects |
|---|---|---|---|---|---|---|---|---|
| login_auth | C1 | 12 | 11 | 1 | 0 | 8.3% (1/12; 95% CI 0.2%-38.5% two-sided) | 0.0% (0/12) | 7 / 3 |
| registration | C1 | 24 | 22 | 2 | 0 | 8.3% (2/24; 95% CI 1.0%-27.0% two-sided) | 0.0% (0/24) | 9 / 3 |
| registration | C2-synthetic | 4 | 0 | 4 | 0 | 100.0% (4/4; 95% CI 39.8%-100.0% two-sided) | 0.0% (0/4) | 1 / 1 |


| workflow | bona fide subject | transactions | accepted | rejected | no response | BPCER |
|---|---|---|---|---|---|---|
| login_auth | S1 | 4 | 3 | 1 | 0 | 25.0% (1/4; 95% CI 0.6%-80.6% two-sided) |
| login_auth | S2 | 4 | 4 | 0 | 0 | 0.0% (0/4; 95% CI 0.0%-60.2% two-sided; one-sided 95% upper bound 52.7%) |
| login_auth | S3 | 4 | 4 | 0 | 0 | 0.0% (0/4; 95% CI 0.0%-60.2% two-sided; one-sided 95% upper bound 52.7%) |
| registration | S1 | 8 | 6 | 2 | 0 | 25.0% (2/8; 95% CI 3.2%-65.1% two-sided) |
| registration | S2 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |
| registration | S3 | 12 | 8 | 4 | 0 | 33.3% (4/12; 95% CI 9.9%-65.1% two-sided) |


## First attempt of each transaction

Attempt 1 of every transaction only: what an attacker gets from one try, before any retry.


| workflow | species | first attempts | accepted | rejected | no response | APCER (95% CI; first attempts not independent) | APNRR | packs / subjects | rejected by layer |
|---|---|---|---|---|---|---|---|---|---|
| eod_auth | SYN-A2 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 2, integrity 2 |
| eod_auth | SYN-A3 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 4 |
| eod_auth | SYN-A6 | 8 | 0 | 8 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) | 0.0% (0/8) | 2 / 2 | passive_pad 4, challenge 4 |
| eod_auth | SYN-A8 | 8 | 8 | 0 | 0 | 100.0% (8/8; 95% CI 63.1%-100.0% two-sided) | 0.0% (0/8) | 2 / 2 | - |
| exception_auth | SYN-A2 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 2, integrity 2 |
| exception_auth | SYN-A3 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 4 |
| exception_auth | SYN-A6 | 8 | 0 | 8 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) | 0.0% (0/8) | 2 / 2 | passive_pad 4, challenge 4 |
| exception_auth | SYN-A8 | 8 | 8 | 0 | 0 | 100.0% (8/8; 95% CI 63.1%-100.0% two-sided) | 0.0% (0/8) | 2 / 2 | - |
| login_auth | SYN-A2 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 2, integrity 2 |
| login_auth | SYN-A6 | 4 | 0 | 4 | 0 | 0.0% (0/4; 95% CI 0.0%-60.2% two-sided; one-sided 95% upper bound 52.7%) | 0.0% (0/4) | 1 / 1 | challenge 4 |
| packet_auth | SYN-A2 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 2, integrity 2 |
| packet_auth | SYN-A3 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 4 |
| packet_auth | SYN-A6 | 8 | 0 | 8 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) | 0.0% (0/8) | 2 / 2 | passive_pad 4, challenge 4 |
| packet_auth | SYN-A8 | 32 | 32 | 0 | 0 | 100.0% (32/32; 95% CI 89.1%-100.0% two-sided) | 0.0% (0/32) | 8 / 2 | - |
| registration | SYN-A1 | 4 | 0 | 4 | 0 | 0.0% (0/4; 95% CI 0.0%-60.2% two-sided; one-sided 95% upper bound 52.7%) | 0.0% (0/4) | 2 / 2 | integrity 4 |
| registration | SYN-A2 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | challenge 2, integrity 2 |
| registration | SYN-A3 | 8 | 8 | 0 | 0 | 100.0% (8/8; 95% CI 63.1%-100.0% two-sided) | 0.0% (0/8) | 2 / 2 | - |
| registration | SYN-A4 | 4 | 0 | 4 | 0 | 0.0% (0/4; 95% CI 0.0%-60.2% two-sided; one-sided 95% upper bound 52.7%) | 0.0% (0/4) | 3 / 1 | challenge 3, integrity 1 |
| registration | SYN-A5 | 8 | 0 | 8 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) | 0.0% (0/8) | 2 / 2 | passive_pad 4, challenge 2, integrity 2 |
| registration | SYN-A6 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | passive_pad 4 |
| registration | SYN-A7 | 8 | 4 | 4 | 0 | 50.0% (4/8; 95% CI 15.7%-84.3% two-sided) | 0.0% (0/8) | 2 / 2 | passive_pad 4 |
| registration | SYN-A8 | 32 | 32 | 0 | 0 | 100.0% (32/32; 95% CI 89.1%-100.0% two-sided) | 0.0% (0/32) | 8 / 2 | - |
| registration | SYN-P0 | 2 | 0 | 2 | 0 | 0.0% (0/2; 95% CI 0.0%-84.2% two-sided; one-sided 95% upper bound 77.6%) | 0.0% (0/2) | 1 / 1 | integrity 2 |

- Workflow **eod_auth**: **APCER_max** (worst species) **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A8
  - **BPCER** (all bona fide conditions): n/a (0 decisions); **BPNRR** n/a
  - Secondary, all attack species pooled: APCER 50.0% (16/32; 95% CI 31.9%-68.1% two-sided); APNRR 0.0% (0/32)
- Workflow **exception_auth**: **APCER_max** (worst species) **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A8
  - **BPCER** (all bona fide conditions): n/a (0 decisions); **BPNRR** n/a
  - Secondary, all attack species pooled: APCER 50.0% (16/32; 95% CI 31.9%-68.1% two-sided); APNRR 0.0% (0/32)
- Workflow **login_auth**: **APCER_max** (worst species) **50.0% (4/8; 95% CI 15.7%-84.3% two-sided)**, species SYN-A2
  - **BPCER** (all bona fide conditions): 25.0% (3/12; 95% CI 5.5%-57.2% two-sided); subject-level bootstrap 95% interval 0.0%-75.0% (2000 resamples); **BPNRR** 0.0% (0/12)
  - Bona fide rejections by layer: challenge 3
  - Secondary, all attack species pooled: APCER 33.3% (4/12; 95% CI 9.9%-65.1% two-sided); APNRR 0.0% (0/12)
- Workflow **packet_auth**: **APCER_max** (worst species) **100.0% (32/32; 95% CI 89.1%-100.0% two-sided)**, species SYN-A8
  - **BPCER** (all bona fide conditions): n/a (0 decisions); **BPNRR** n/a
  - Secondary, all attack species pooled: APCER 71.4% (40/56; 95% CI 57.8%-82.7% two-sided); APNRR 0.0% (0/56)
- Workflow **registration**: **APCER_max** (worst species) **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A3
  - **BPCER** (all bona fide conditions): 35.7% (10/28; 95% CI 18.6%-55.9% two-sided); subject-level bootstrap 95% interval 0.0%-75.0% (2000 resamples); **BPNRR** 0.0% (0/28)
  - Bona fide rejections by layer: challenge 6, passive_pad 4
  - Secondary, all attack species pooled: APCER 63.4% (52/82; 95% CI 52.0%-73.8% two-sided); APNRR 0.0% (0/82)


| workflow | bona fide condition | first attempts | accepted | rejected | no response | BPCER | BPNRR | packs / subjects |
|---|---|---|---|---|---|---|---|---|
| login_auth | C1 | 12 | 9 | 3 | 0 | 25.0% (3/12; 95% CI 5.5%-57.2% two-sided) | 0.0% (0/12) | 7 / 3 |
| registration | C1 | 24 | 18 | 6 | 0 | 25.0% (6/24; 95% CI 9.8%-46.7% two-sided) | 0.0% (0/24) | 9 / 3 |
| registration | C2-synthetic | 4 | 0 | 4 | 0 | 100.0% (4/4; 95% CI 39.8%-100.0% two-sided) | 0.0% (0/4) | 1 / 1 |


| workflow | bona fide subject | first attempts | accepted | rejected | no response | BPCER |
|---|---|---|---|---|---|---|
| login_auth | S1 | 4 | 1 | 3 | 0 | 75.0% (3/4; 95% CI 19.4%-99.4% two-sided) |
| login_auth | S2 | 4 | 4 | 0 | 0 | 0.0% (0/4; 95% CI 0.0%-60.2% two-sided; one-sided 95% upper bound 52.7%) |
| login_auth | S3 | 4 | 4 | 0 | 0 | 0.0% (0/4; 95% CI 0.0%-60.2% two-sided; one-sided 95% upper bound 52.7%) |
| registration | S1 | 8 | 2 | 6 | 0 | 75.0% (6/8; 95% CI 34.9%-96.8% two-sided) |
| registration | S2 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |
| registration | S3 | 12 | 8 | 4 | 0 | 33.3% (4/12; 95% CI 9.9%-65.1% two-sided) |


## Evaluation type (a): per attempt (secondary)

Every attempt counted. A transaction ends at its first pass, so this level undercounts a species that passes at once; read the per-transaction and first-attempt figures above first.


| workflow | species | attempts | accepted | rejected | no response | APCER (95% CI; attempts not independent) | APNRR | packs / subjects | rejected by layer | attacker success in 3 tries (if attempts were independent) |
|---|---|---|---|---|---|---|---|---|---|---|
| eod_auth | SYN-A2 | 16 | 4 | 12 | 0 | 25.0% (4/16; 95% CI 7.3%-52.4% two-sided) | 0.0% (0/16) | 2 / 2 | challenge 9, integrity 3 | 57.8% |
| eod_auth | SYN-A3 | 16 | 4 | 12 | 0 | 25.0% (4/16; 95% CI 7.3%-52.4% two-sided) | 0.0% (0/16) | 2 / 2 | challenge 12 | 57.8% |
| eod_auth | SYN-A6 | 24 | 0 | 24 | 0 | 0.0% (0/24; 95% CI 0.0%-14.2% two-sided; one-sided 95% upper bound 11.7%) | 0.0% (0/24) | 2 / 2 | passive_pad 12, challenge 12 | 0.0% |
| eod_auth | SYN-A8 | 8 | 8 | 0 | 0 | 100.0% (8/8; 95% CI 63.1%-100.0% two-sided) | 0.0% (0/8) | 2 / 2 | - | 100.0% |
| exception_auth | SYN-A2 | 16 | 4 | 12 | 0 | 25.0% (4/16; 95% CI 7.3%-52.4% two-sided) | 0.0% (0/16) | 2 / 2 | challenge 9, integrity 3 | 57.8% |
| exception_auth | SYN-A3 | 16 | 4 | 12 | 0 | 25.0% (4/16; 95% CI 7.3%-52.4% two-sided) | 0.0% (0/16) | 2 / 2 | challenge 12 | 57.8% |
| exception_auth | SYN-A6 | 24 | 0 | 24 | 0 | 0.0% (0/24; 95% CI 0.0%-14.2% two-sided; one-sided 95% upper bound 11.7%) | 0.0% (0/24) | 2 / 2 | passive_pad 12, challenge 12 | 0.0% |
| exception_auth | SYN-A8 | 8 | 8 | 0 | 0 | 100.0% (8/8; 95% CI 63.1%-100.0% two-sided) | 0.0% (0/8) | 2 / 2 | - | 100.0% |
| login_auth | SYN-A2 | 16 | 4 | 12 | 0 | 25.0% (4/16; 95% CI 7.3%-52.4% two-sided) | 0.0% (0/16) | 2 / 2 | challenge 9, integrity 3 | 57.8% |
| login_auth | SYN-A6 | 12 | 0 | 12 | 0 | 0.0% (0/12; 95% CI 0.0%-26.5% two-sided; one-sided 95% upper bound 22.1%) | 0.0% (0/12) | 1 / 1 | challenge 12 | 0.0% |
| packet_auth | SYN-A2 | 16 | 4 | 12 | 0 | 25.0% (4/16; 95% CI 7.3%-52.4% two-sided) | 0.0% (0/16) | 2 / 2 | challenge 9, integrity 3 | 57.8% |
| packet_auth | SYN-A3 | 16 | 4 | 12 | 0 | 25.0% (4/16; 95% CI 7.3%-52.4% two-sided) | 0.0% (0/16) | 2 / 2 | challenge 12 | 57.8% |
| packet_auth | SYN-A6 | 24 | 0 | 24 | 0 | 0.0% (0/24; 95% CI 0.0%-14.2% two-sided; one-sided 95% upper bound 11.7%) | 0.0% (0/24) | 2 / 2 | passive_pad 12, challenge 12 | 0.0% |
| packet_auth | SYN-A8 | 32 | 32 | 0 | 0 | 100.0% (32/32; 95% CI 89.1%-100.0% two-sided) | 0.0% (0/32) | 8 / 2 | - | 100.0% |
| registration | SYN-A1 | 12 | 0 | 12 | 0 | 0.0% (0/12; 95% CI 0.0%-26.5% two-sided; one-sided 95% upper bound 22.1%) | 0.0% (0/12) | 2 / 2 | integrity 12 | 0.0% |
| registration | SYN-A2 | 16 | 4 | 12 | 0 | 25.0% (4/16; 95% CI 7.3%-52.4% two-sided) | 0.0% (0/16) | 2 / 2 | challenge 9, integrity 3 | 57.8% |
| registration | SYN-A3 | 8 | 8 | 0 | 0 | 100.0% (8/8; 95% CI 63.1%-100.0% two-sided) | 0.0% (0/8) | 2 / 2 | - | 100.0% |
| registration | SYN-A4 | 12 | 0 | 12 | 0 | 0.0% (0/12; 95% CI 0.0%-26.5% two-sided; one-sided 95% upper bound 22.1%) | 0.0% (0/12) | 3 / 1 | challenge 10, integrity 2 | 0.0% |
| registration | SYN-A5 | 24 | 0 | 24 | 0 | 0.0% (0/24; 95% CI 0.0%-14.2% two-sided; one-sided 95% upper bound 11.7%) | 0.0% (0/24) | 2 / 2 | passive_pad 12, challenge 6, integrity 6 | 0.0% |
| registration | SYN-A6 | 16 | 4 | 12 | 0 | 25.0% (4/16; 95% CI 7.3%-52.4% two-sided) | 0.0% (0/16) | 2 / 2 | passive_pad 12 | 57.8% |
| registration | SYN-A7 | 16 | 4 | 12 | 0 | 25.0% (4/16; 95% CI 7.3%-52.4% two-sided) | 0.0% (0/16) | 2 / 2 | passive_pad 12 | 57.8% |
| registration | SYN-A8 | 32 | 32 | 0 | 0 | 100.0% (32/32; 95% CI 89.1%-100.0% two-sided) | 0.0% (0/32) | 8 / 2 | - | 100.0% |
| registration | SYN-P0 | 6 | 0 | 6 | 0 | 0.0% (0/6; 95% CI 0.0%-45.9% two-sided; one-sided 95% upper bound 39.3%) | 0.0% (0/6) | 1 / 1 | integrity 6 | 0.0% |

- Workflow **eod_auth**: **APCER_max** (worst species) **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A8
  - **BPCER** (all bona fide conditions): n/a (0 decisions); **BPNRR** n/a
  - Secondary, all attack species pooled: APCER 25.0% (16/64; 95% CI 15.0%-37.4% two-sided); APNRR 0.0% (0/64)
- Workflow **exception_auth**: **APCER_max** (worst species) **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A8
  - **BPCER** (all bona fide conditions): n/a (0 decisions); **BPNRR** n/a
  - Secondary, all attack species pooled: APCER 25.0% (16/64; 95% CI 15.0%-37.4% two-sided); APNRR 0.0% (0/64)
- Workflow **login_auth**: **APCER_max** (worst species) **25.0% (4/16; 95% CI 7.3%-52.4% two-sided)**, species SYN-A2
  - **BPCER** (all bona fide conditions): 31.2% (5/16; 95% CI 11.0%-58.7% two-sided); subject-level bootstrap 95% interval 0.0%-62.5% (2000 resamples); **BPNRR** 0.0% (0/16)
  - Bona fide rejections by layer: challenge 5
  - Secondary, all attack species pooled: APCER 14.3% (4/28; 95% CI 4.0%-32.7% two-sided); APNRR 0.0% (0/28)
- Workflow **packet_auth**: **APCER_max** (worst species) **100.0% (32/32; 95% CI 89.1%-100.0% two-sided)**, species SYN-A8
  - **BPCER** (all bona fide conditions): n/a (0 decisions); **BPNRR** n/a
  - Secondary, all attack species pooled: APCER 45.5% (40/88; 95% CI 34.8%-56.4% two-sided); APNRR 0.0% (0/88)
- Workflow **registration**: **APCER_max** (worst species) **100.0% (8/8; 95% CI 63.1%-100.0% two-sided)**, species SYN-A3
  - **BPCER** (all bona fide conditions): 50.0% (22/44; 95% CI 34.6%-65.4% two-sided); subject-level bootstrap 95% interval 0.0%-62.5% (2000 resamples); **BPNRR** 0.0% (0/44)
  - Bona fide rejections by layer: passive_pad 12, challenge 10
  - Secondary, all attack species pooled: APCER 36.6% (52/142; 95% CI 28.7%-45.1% two-sided); APNRR 0.0% (0/142)


| workflow | bona fide condition | attempts | accepted | rejected | no response | BPCER | BPNRR | packs / subjects |
|---|---|---|---|---|---|---|---|---|
| login_auth | C1 | 16 | 11 | 5 | 0 | 31.2% (5/16; 95% CI 11.0%-58.7% two-sided) | 0.0% (0/16) | 7 / 3 |
| registration | C1 | 32 | 22 | 10 | 0 | 31.2% (10/32; 95% CI 16.1%-50.0% two-sided) | 0.0% (0/32) | 9 / 3 |
| registration | C2-synthetic | 12 | 0 | 12 | 0 | 100.0% (12/12; 95% CI 73.5%-100.0% two-sided) | 0.0% (0/12) | 1 / 1 |


| workflow | bona fide subject | attempts | accepted | rejected | no response | BPCER |
|---|---|---|---|---|---|---|
| login_auth | S1 | 8 | 3 | 5 | 0 | 62.5% (5/8; 95% CI 24.5%-91.5% two-sided) |
| login_auth | S2 | 4 | 4 | 0 | 0 | 0.0% (0/4; 95% CI 0.0%-60.2% two-sided; one-sided 95% upper bound 52.7%) |
| login_auth | S3 | 4 | 4 | 0 | 0 | 0.0% (0/4; 95% CI 0.0%-60.2% two-sided; one-sided 95% upper bound 52.7%) |
| registration | S1 | 16 | 6 | 10 | 0 | 62.5% (10/16; 95% CI 35.4%-84.8% two-sided) |
| registration | S2 | 8 | 8 | 0 | 0 | 0.0% (0/8; 95% CI 0.0%-36.9% two-sided; one-sided 95% upper bound 31.2%) |
| registration | S3 | 20 | 8 | 12 | 0 | 60.0% (12/20; 95% CI 36.1%-80.9% two-sided) |


## Genuine clips of the wrong action (not in APCER or BPCER)

Species SYN-A9: a genuine face answering with a clip of the wrong action. These are bona fide presentations, so they test the challenge check, not presentation attack detection; they are left out of APCER, BPCER and the DET curve. The correct result is a refusal.

| workflow | species | packs / subjects | transactions | accepted per transaction (wrong action passed) | accepted on the first attempt | refused by layer |
|---|---|---|---|---|---|---|
| registration | SYN-A9 | 9 / 3 | 12 | 0.0% (0/12; CI 0.0%-26.5%; one-sided <= 22.1%) | 0.0% (0/12; CI 0.0%-26.5%; one-sided <= 22.1%) | challenge 12 |


## Full-system results: IAPAR and FRR

No attempt record carries a face-match result (`match_outcome`), so IAPAR and FRR are not computed. The results sheet supplies it for login rows (tools/eval/sheet-to-attempts.py).


## Per scenario

Method `passive` means the passive score alone passed; `passive_active` and `active` mean challenges were needed.


| workflow | scenario | kind | species | transactions | transaction outcomes | accepted on the first attempt | attempts | passive score (median) | accepted attempts by method | other attempts ended by |
|---|---|---|---|---|---|---|---|---|---|---|
| eod_auth | atk-s2-looped-clip-eod-auth | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 97 | passive 4 | - |
| eod_auth | atk-s2-print-moved-eod-auth | attack | SYN-A3 | 4 | reject 4 | 0/4 | 12 | 90 | - | challenge 12 |
| eod_auth | atk-s2-print-still-eod-auth | attack | SYN-A2 | 4 | reject 4 | 0/4 | 12 | 73 | - | challenge 9, integrity 3 |
| eod_auth | atk-s2-screen-replay-eod-auth | attack | SYN-A6 | 4 | reject 4 | 0/4 | 12 | 14 | - | passive_pad 12 |
| eod_auth | atk-s3-looped-clip-eod-auth | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 100 | passive 4 | - |
| eod_auth | atk-s3-print-moved-eod-auth | attack | SYN-A3 | 4 | accept 4 | 4/4 | 4 | 99 | passive 4 | - |
| eod_auth | atk-s3-print-still-eod-auth | attack | SYN-A2 | 4 | accept 4 | 4/4 | 4 | 99 | passive 4 | - |
| eod_auth | atk-s3-screen-replay-eod-auth | attack | SYN-A6 | 4 | reject 4 | 0/4 | 12 | 82 | - | challenge 12 |
| exception_auth | atk-s2-looped-clip-exception-auth | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 97 | passive 4 | - |
| exception_auth | atk-s2-print-moved-exception-auth | attack | SYN-A3 | 4 | reject 4 | 0/4 | 12 | 90 | - | challenge 12 |
| exception_auth | atk-s2-print-still-exception-auth | attack | SYN-A2 | 4 | reject 4 | 0/4 | 12 | 73 | - | challenge 9, integrity 3 |
| exception_auth | atk-s2-screen-replay-exception-auth | attack | SYN-A6 | 4 | reject 4 | 0/4 | 12 | 14 | - | passive_pad 12 |
| exception_auth | atk-s3-looped-clip-exception-auth | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 100 | passive 4 | - |
| exception_auth | atk-s3-print-moved-exception-auth | attack | SYN-A3 | 4 | accept 4 | 4/4 | 4 | 99 | passive 4 | - |
| exception_auth | atk-s3-print-still-exception-auth | attack | SYN-A2 | 4 | accept 4 | 4/4 | 4 | 99 | passive 4 | - |
| exception_auth | atk-s3-screen-replay-exception-auth | attack | SYN-A6 | 4 | reject 4 | 0/4 | 12 | 82 | - | challenge 12 |
| login_auth | atk-s2-print-still-login | attack | SYN-A2 | 4 | reject 4 | 0/4 | 12 | 73 | - | challenge 9, integrity 3 |
| login_auth | atk-s3-print-still-login | attack | SYN-A2 | 4 | accept 4 | 4/4 | 4 | 99 | passive 4 | - |
| login_auth | atk-s3-screen-replay-login | attack | SYN-A6 | 4 | reject 4 | 0/4 | 12 | 82 | - | challenge 12 |
| login_auth | bf-s1-login | bona_fide | BF | 4 | accept 3 reject 1 | 1/4 | 8 | 75 | passive_active 3 | challenge 5 |
| login_auth | bf-s2-login | bona_fide | BF | 4 | accept 4 | 4/4 | 4 | 84 | passive_active 4 | - |
| login_auth | bf-s3-login | bona_fide | BF | 4 | accept 4 | 4/4 | 4 | 100 | passive 4 | - |
| packet_auth | atk-s2-looped-clip-2s-packet-auth | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 97 | passive 4 | - |
| packet_auth | atk-s2-looped-clip-4s-packet-auth | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 97 | passive 4 | - |
| packet_auth | atk-s2-looped-clip-8s-packet-auth | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 97 | passive 4 | - |
| packet_auth | atk-s2-looped-clip-packet-auth | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 97 | passive 4 | - |
| packet_auth | atk-s2-print-moved-packet-auth | attack | SYN-A3 | 4 | reject 4 | 0/4 | 12 | 90 | - | challenge 12 |
| packet_auth | atk-s2-print-still-packet-auth | attack | SYN-A2 | 4 | reject 4 | 0/4 | 12 | 73 | - | challenge 9, integrity 3 |
| packet_auth | atk-s2-screen-replay-packet-auth | attack | SYN-A6 | 4 | reject 4 | 0/4 | 12 | 14 | - | passive_pad 12 |
| packet_auth | atk-s3-looped-clip-2s-packet-auth | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 100 | passive 4 | - |
| packet_auth | atk-s3-looped-clip-4s-packet-auth | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 100 | passive 4 | - |
| packet_auth | atk-s3-looped-clip-8s-packet-auth | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 100 | passive 4 | - |
| packet_auth | atk-s3-looped-clip-packet-auth | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 100 | passive 4 | - |
| packet_auth | atk-s3-print-moved-packet-auth | attack | SYN-A3 | 4 | accept 4 | 4/4 | 4 | 99 | passive 4 | - |
| packet_auth | atk-s3-print-still-packet-auth | attack | SYN-A2 | 4 | accept 4 | 4/4 | 4 | 99 | passive 4 | - |
| packet_auth | atk-s3-screen-replay-packet-auth | attack | SYN-A6 | 4 | reject 4 | 0/4 | 12 | 82 | - | challenge 12 |
| registration | atk-m-mockmds-stock | attack | SYN-P0 | 2 | reject 2 | 0/2 | 6 | - | - | integrity 6 |
| registration | atk-s1-adaptive-wrong-clip | genuine clip, wrong action | SYN-A9 | 4 | reject 4 | 0/4 | 12 | 75 | - | challenge 12 |
| registration | atk-s2-adaptive-screen | attack | SYN-A7 | 4 | reject 4 | 0/4 | 12 | 14 | - | passive_pad 12 |
| registration | atk-s2-adaptive-wrong-clip | genuine clip, wrong action | SYN-A9 | 4 | reject 4 | 0/4 | 12 | 84 | - | challenge 12 |
| registration | atk-s2-injected-still | attack | SYN-A1 | 2 | reject 2 | 0/2 | 6 | - | - | integrity 6 |
| registration | atk-s2-looped-clip | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 97 | passive 4 | - |
| registration | atk-s2-looped-clip-2s | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 97 | passive 4 | - |
| registration | atk-s2-looped-clip-4s | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 97 | passive 4 | - |
| registration | atk-s2-looped-clip-8s | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 97 | passive 4 | - |
| registration | atk-s2-print-moved | attack | SYN-A3 | 4 | accept 4 | 4/4 | 4 | 90 | passive 4 | - |
| registration | atk-s2-print-still | attack | SYN-A2 | 4 | reject 4 | 0/4 | 12 | 73 | - | challenge 9, integrity 3 |
| registration | atk-s2-print-tilted | attack | SYN-A4 | 4 | reject 4 | 0/4 | 12 | 73 | - | challenge 10, integrity 2 |
| registration | atk-s2-screen-photo | attack | SYN-A5 | 4 | reject 4 | 0/4 | 12 | 8 | - | passive_pad 12 |
| registration | atk-s2-screen-replay | attack | SYN-A6 | 4 | reject 4 | 0/4 | 12 | 14 | - | passive_pad 12 |
| registration | atk-s3-adaptive-screen | attack | SYN-A7 | 4 | accept 4 | 4/4 | 4 | 82 | passive 4 | - |
| registration | atk-s3-adaptive-wrong-clip | genuine clip, wrong action | SYN-A9 | 4 | reject 4 | 0/4 | 12 | 100 | - | challenge 12 |
| registration | atk-s3-injected-still | attack | SYN-A1 | 2 | reject 2 | 0/2 | 6 | - | - | integrity 6 |
| registration | atk-s3-looped-clip | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 100 | passive 4 | - |
| registration | atk-s3-looped-clip-2s | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 100 | passive 4 | - |
| registration | atk-s3-looped-clip-4s | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 100 | passive 4 | - |
| registration | atk-s3-looped-clip-8s | attack | SYN-A8 | 4 | accept 4 | 4/4 | 4 | 100 | passive 4 | - |
| registration | atk-s3-print-moved | attack | SYN-A3 | 4 | accept 4 | 4/4 | 4 | 99 | passive 4 | - |
| registration | atk-s3-print-still | attack | SYN-A2 | 4 | accept 4 | 4/4 | 4 | 99 | passive 4 | - |
| registration | atk-s3-screen-photo | attack | SYN-A5 | 4 | reject 4 | 0/4 | 12 | 65 | - | challenge 6, integrity 6 |
| registration | atk-s3-screen-replay | attack | SYN-A6 | 4 | accept 4 | 4/4 | 4 | 82 | passive 4 | - |
| registration | bf-s1-active | bona_fide | BF | 4 | accept 3 reject 1 | 1/4 | 8 | 75 | active 3 | challenge 5 |
| registration | bf-s1-registration | bona_fide | BF | 4 | accept 3 reject 1 | 1/4 | 8 | 75 | passive_active 3 | challenge 5 |
| registration | bf-s2-active | bona_fide | BF | 4 | accept 4 | 4/4 | 4 | 84 | active 4 | - |
| registration | bf-s2-registration | bona_fide | BF | 4 | accept 4 | 4/4 | 4 | 84 | passive 4 | - |
| registration | bf-s3-active | bona_fide | BF | 4 | accept 4 | 4/4 | 4 | 100 | active 4 | - |
| registration | bf-s3-dim | bona_fide | BF | 4 | reject 4 | 0/4 | 12 | 4 | - | passive_pad 12 |
| registration | bf-s3-registration | bona_fide | BF | 4 | accept 4 | 4/4 | 4 | 100 | passive 4 | - |
| registration | other-blurred | other | OTHER | 1 | reject 1 | 0/1 | 3 | 46 | - | challenge 3 |
| registration | other-no-face | other | OTHER | 1 | no_response 1 | 0/1 | 5 | - | - | quality 5 |
| registration | other-s1-wrong-action | other | OTHER | 2 | reject 2 | 0/2 | 6 | 75 | - | challenge 6 |
| registration | other-s3-ignores-prompt | other | OTHER | 2 | reject 2 | 0/2 | 6 | 100 | - | challenge 6 |
| registration | other-stream-ends | other | OTHER | 1 | no_response 1 | 0/1 | 5 | - | - | error 5 |
| registration | other-too-dark | other | OTHER | 1 | no_response 1 | 0/1 | 5 | - | - | quality 5 |
| registration | other-two-faces | other | OTHER | 1 | no_response 1 | 0/1 | 5 | - | - | quality 5 |


## Where attacks were stopped (per attempt)

| workflow | species | attempts stopped | before any challenge | during or after challenges |
|---|---|---|---|---|
| eod_auth | SYN-A2 | 12 | 0 (0.0%) | 12 (100.0%) |
| eod_auth | SYN-A3 | 12 | 0 (0.0%) | 12 (100.0%) |
| eod_auth | SYN-A6 | 24 | 12 (50.0%) | 12 (50.0%) |
| exception_auth | SYN-A2 | 12 | 0 (0.0%) | 12 (100.0%) |
| exception_auth | SYN-A3 | 12 | 0 (0.0%) | 12 (100.0%) |
| exception_auth | SYN-A6 | 24 | 12 (50.0%) | 12 (50.0%) |
| login_auth | SYN-A2 | 12 | 0 (0.0%) | 12 (100.0%) |
| login_auth | SYN-A6 | 12 | 0 (0.0%) | 12 (100.0%) |
| packet_auth | SYN-A2 | 12 | 0 (0.0%) | 12 (100.0%) |
| packet_auth | SYN-A3 | 12 | 0 (0.0%) | 12 (100.0%) |
| packet_auth | SYN-A6 | 24 | 12 (50.0%) | 12 (50.0%) |
| registration | SYN-A1 | 12 | 12 (100.0%) | 0 (0.0%) |
| registration | SYN-A2 | 12 | 0 (0.0%) | 12 (100.0%) |
| registration | SYN-A4 | 12 | 0 (0.0%) | 12 (100.0%) |
| registration | SYN-A5 | 24 | 12 (50.0%) | 12 (50.0%) |
| registration | SYN-A6 | 12 | 12 (100.0%) | 0 (0.0%) |
| registration | SYN-A7 | 12 | 12 (100.0%) | 0 (0.0%) |
| registration | SYN-P0 | 6 | 6 (100.0%) | 0 (0.0%) |


## Bona fide challenges

| workflow | challenge (bona fide) | completed | median time to complete (ms) |
|---|---|---|---|
| login_auth | BLINK | 7/8 (87.5%) | 466 |
| login_auth | SMILE | 7/7 (100.0%) | 1333 |
| login_auth | TURN_LEFT | 4/4 (100.0%) | 1866 |
| login_auth | TURN_RIGHT | 4/4 (100.0%) | 1700 |
| registration | BLINK | 18/20 (90.0%) | 1200 |
| registration | SMILE | 18/18 (100.0%) | 1333 |
| registration | TURN_LEFT | 4/4 (100.0%) | 1866 |
| registration | TURN_RIGHT | 4/4 (100.0%) | 1700 |


## Timings

Session times are on the replayed stream clock (15 fps). Per-frame times are wall-clock time of `onFrame` plus `tick` on the machine named above (JPEG decode, face analysis, PAD every other frame).


| workflow | attempts | n | session time median / p95 (ms, stream clock) | passive stage median / p95 (ms) | frames analysed | per-frame p50 (median over attempts, ms) | per-frame p95 (worst attempt, ms) |
|---|---|---|---|---|---|---|---|
| eod_auth | attack | 64 | 6133 / 7000 | 533 / 533 | 3769 | 47.1 | 407.8 |
| eod_auth | all | 64 | 6133 / 7000 | 533 / 533 | 3769 | 47.1 | 407.8 |
| exception_auth | attack | 64 | 6133 / 7000 | 533 / 533 | 3769 | 47.7 | 284.1 |
| exception_auth | all | 64 | 6133 / 7000 | 533 / 533 | 3769 | 47.7 | 284.1 |
| login_auth | bona fide | 16 | 9666 / 17966 | 533 / 533 | 1983 | 38.9 | 100.8 |
| login_auth | attack | 28 | 6500 / 7000 | 533 / 533 | 2393 | 51.1 | 248.9 |
| login_auth | all | 44 | 6600 / 13666 | 533 / 533 | 4376 | 40.9 | 248.9 |
| packet_auth | attack | 88 | 833 / 7000 | 533 / 533 | 3961 | 47.3 | 217.0 |
| packet_auth | all | 88 | 833 / 7000 | 533 / 533 | 3961 | 47.3 | 217.0 |
| registration | bona fide | 44 | 4483 / 13666 | 533 / 533 | 3983 | 44.0 | 308.1 |
| registration | attack | 178 | 833 / 7000 | 533 / 533 | 6891 | 42.2 | 350.3 |
| registration | all | 257 | 1933 / 9700 | 533 / 4133 | 13589 | 41.6 | 350.3 |


## DET of the passive score

![DET](det.png)


One point per transaction (its first attempt); the plot pools all workflows; bona fide conditions: C1. Genuine clips of the wrong action (SYN-A9) are left out. 
The curve sweeps the passive score alone; the full engine adds integrity, planarity, challenge and binding checks, so the system's operating point is the per-transaction table above, not this curve.


| workflow | bona fide transactions | attack transactions | BPCER20 (BPCER at APCER 5%, passive score only) | BPCER10 |
|---|---|---|---|---|
| eod_auth | 0 | 32 | - | - |
| exception_auth | 0 | 32 | - | - |
| login_auth | 12 | 12 | 66.7% at threshold 100 | 66.7% at threshold 100 |
| packet_auth | 0 | 56 | - | - |
| registration | 24 | 76 | 100.0% at threshold 101 | 100.0% at threshold 101 |
| all workflows | 36 | 208 | 100.0% at threshold 101 | 100.0% at threshold 101 |


- Marked point: passive threshold 80.


## Quality and behaviour scenarios (not in APCER or BPCER)

| workflow | scenario | presentations | accepted | rejected | no response | ended by layer |
|---|---|---|---|---|---|---|
| registration | other-blurred | 3 | 0 | 3 | 0 | challenge 3 |
| registration | other-no-face | 5 | 0 | 0 | 5 | quality 5 |
| registration | other-s1-wrong-action | 6 | 0 | 6 | 0 | challenge 6 |
| registration | other-s3-ignores-prompt | 6 | 0 | 6 | 0 | challenge 6 |
| registration | other-stream-ends | 5 | 0 | 0 | 5 | error 5 |
| registration | other-too-dark | 5 | 0 | 0 | 5 | quality 5 |
| registration | other-two-faces | 5 | 0 | 0 | 5 | quality 5 |


## Expectation check

136 of 275 transactions did not meet the scenario's expectation.

| workflow | transaction | expected | observed | layer per attempt | reasons (last attempt) |
|---|---|---|---|---|---|
| eod_auth | atk-s2-looped-clip-eod-auth#1 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s2-looped-clip-eod-auth#2 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s2-looped-clip-eod-auth#3 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s2-looped-clip-eod-auth#4 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s3-looped-clip-eod-auth#1 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s3-looped-clip-eod-auth#2 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s3-looped-clip-eod-auth#3 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s3-looped-clip-eod-auth#4 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s3-print-moved-eod-auth#1 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s3-print-moved-eod-auth#2 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s3-print-moved-eod-auth#3 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s3-print-moved-eod-auth#4 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s3-print-still-eod-auth#1 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s3-print-still-eod-auth#2 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s3-print-still-eod-auth#3 | REJECT | ACCEPT | none | - |
| eod_auth | atk-s3-print-still-eod-auth#4 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s2-looped-clip-exception-auth#1 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s2-looped-clip-exception-auth#2 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s2-looped-clip-exception-auth#3 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s2-looped-clip-exception-auth#4 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s3-looped-clip-exception-auth#1 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s3-looped-clip-exception-auth#2 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s3-looped-clip-exception-auth#3 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s3-looped-clip-exception-auth#4 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s3-print-moved-exception-auth#1 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s3-print-moved-exception-auth#2 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s3-print-moved-exception-auth#3 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s3-print-moved-exception-auth#4 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s3-print-still-exception-auth#1 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s3-print-still-exception-auth#2 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s3-print-still-exception-auth#3 | REJECT | ACCEPT | none | - |
| exception_auth | atk-s3-print-still-exception-auth#4 | REJECT | ACCEPT | none | - |
| login_auth | atk-s3-print-still-login#1 | REJECT | ACCEPT | none | - |
| login_auth | atk-s3-print-still-login#2 | REJECT | ACCEPT | none | - |
| login_auth | atk-s3-print-still-login#3 | REJECT | ACCEPT | none | - |
| login_auth | atk-s3-print-still-login#4 | REJECT | ACCEPT | none | - |
| login_auth | bf-s1-login#1 | ACCEPT | REJECT | challenge, challenge, challenge | NEUTRAL_TIMEOUT |
| packet_auth | atk-s2-looped-clip-2s-packet-auth#1 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-2s-packet-auth#2 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-2s-packet-auth#3 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-2s-packet-auth#4 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-4s-packet-auth#1 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-4s-packet-auth#2 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-4s-packet-auth#3 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-4s-packet-auth#4 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-8s-packet-auth#1 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-8s-packet-auth#2 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-8s-packet-auth#3 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-8s-packet-auth#4 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-packet-auth#1 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-packet-auth#2 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-packet-auth#3 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s2-looped-clip-packet-auth#4 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-2s-packet-auth#1 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-2s-packet-auth#2 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-2s-packet-auth#3 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-2s-packet-auth#4 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-4s-packet-auth#1 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-4s-packet-auth#2 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-4s-packet-auth#3 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-4s-packet-auth#4 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-8s-packet-auth#1 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-8s-packet-auth#2 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-8s-packet-auth#3 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-8s-packet-auth#4 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-packet-auth#1 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-packet-auth#2 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-packet-auth#3 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-looped-clip-packet-auth#4 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-print-moved-packet-auth#1 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-print-moved-packet-auth#2 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-print-moved-packet-auth#3 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-print-moved-packet-auth#4 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-print-still-packet-auth#1 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-print-still-packet-auth#2 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-print-still-packet-auth#3 | REJECT | ACCEPT | none | - |
| packet_auth | atk-s3-print-still-packet-auth#4 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip#1 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip#2 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip#3 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip#4 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip-2s#1 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip-2s#2 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip-2s#3 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip-2s#4 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip-4s#1 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip-4s#2 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip-4s#3 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip-4s#4 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip-8s#1 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip-8s#2 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip-8s#3 | REJECT | ACCEPT | none | - |
| registration | atk-s2-looped-clip-8s#4 | REJECT | ACCEPT | none | - |
| registration | atk-s2-print-moved#1 | REJECT | ACCEPT | none | - |
| registration | atk-s2-print-moved#2 | REJECT | ACCEPT | none | - |
| registration | atk-s2-print-moved#3 | REJECT | ACCEPT | none | - |
| registration | atk-s2-print-moved#4 | REJECT | ACCEPT | none | - |
| registration | atk-s3-adaptive-screen#1 | REJECT | ACCEPT | none | - |
| registration | atk-s3-adaptive-screen#2 | REJECT | ACCEPT | none | - |
| registration | atk-s3-adaptive-screen#3 | REJECT | ACCEPT | none | - |
| registration | atk-s3-adaptive-screen#4 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip#1 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip#2 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip#3 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip#4 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip-2s#1 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip-2s#2 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip-2s#3 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip-2s#4 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip-4s#1 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip-4s#2 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip-4s#3 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip-4s#4 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip-8s#1 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip-8s#2 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip-8s#3 | REJECT | ACCEPT | none | - |
| registration | atk-s3-looped-clip-8s#4 | REJECT | ACCEPT | none | - |
| registration | atk-s3-print-moved#1 | REJECT | ACCEPT | none | - |
| registration | atk-s3-print-moved#2 | REJECT | ACCEPT | none | - |
| registration | atk-s3-print-moved#3 | REJECT | ACCEPT | none | - |
| registration | atk-s3-print-moved#4 | REJECT | ACCEPT | none | - |
| registration | atk-s3-print-still#1 | REJECT | ACCEPT | none | - |
| registration | atk-s3-print-still#2 | REJECT | ACCEPT | none | - |
| registration | atk-s3-print-still#3 | REJECT | ACCEPT | none | - |
| registration | atk-s3-print-still#4 | REJECT | ACCEPT | none | - |
| registration | atk-s3-screen-replay#1 | REJECT | ACCEPT | none | - |
| registration | atk-s3-screen-replay#2 | REJECT | ACCEPT | none | - |
| registration | atk-s3-screen-replay#3 | REJECT | ACCEPT | none | - |
| registration | atk-s3-screen-replay#4 | REJECT | ACCEPT | none | - |
| registration | bf-s1-active#1 | ACCEPT | REJECT | challenge, challenge, challenge | NEUTRAL_TIMEOUT |
| registration | bf-s1-registration#1 | ACCEPT | REJECT | challenge, challenge, challenge | NEUTRAL_TIMEOUT |
| registration | bf-s3-dim#1 | ACCEPT | REJECT | passive_pad, passive_pad, passive_pad | PAD_SCORE |
| registration | bf-s3-dim#2 | ACCEPT | REJECT | passive_pad, passive_pad, passive_pad | PAD_SCORE |
| registration | bf-s3-dim#3 | ACCEPT | REJECT | passive_pad, passive_pad, passive_pad | PAD_SCORE |
| registration | bf-s3-dim#4 | ACCEPT | REJECT | passive_pad, passive_pad, passive_pad | PAD_SCORE |
| registration | other-blurred#1 | NO_RESPONSE | REJECT | challenge, challenge, challenge | NEUTRAL_TIMEOUT |
