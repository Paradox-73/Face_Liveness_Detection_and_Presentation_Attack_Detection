# In-house PAD evaluation protocol

**Title used in every report:** "In-house PAD evaluation following ISO/IEC 30107-3:2023 methodology: basic attack potential".

This protocol describes how the team measures the face liveness and presentation attack detection (PAD) of the MOSIP desktop and Android Registration Clients with real people and real attack artefacts. It uses the metric definitions of ISO/IEC 30107-3:2023. It is an in-house test by the development team, not a certified laboratory test, and its results must never be presented as one.

The automated scenario suite in `eval/scenarios/` (see [README.md](README.md)) is a separate thing: it replays recorded frames and synthetic artefacts into the engine, which is itself an injection path. Its results are **technology regression results** and are reported separately from the evaluation described here.

- **Standards read:** ISO/IEC 30107-1:2023 and ISO/IEC 30107-3:2023 through their public previews (definitions, table of contents, introduction). Annex C ("roles in PAD testing") was not read; the role rules below are team rules informed by its title.
- **Terms:** a *presentation* is one interaction of a person or artefact with the capture device. A *PAI* (presentation attack instrument) is one artefact, for example one printed photo. A *PAIS* (PAI species) is one recipe for making PAIs, for example "A4 matte colour print held flat".

## 1. Item under test

Every report names the exact system that was tested. The scenario runner writes most of these facts into `run.json` and into every attempt record.

| Fact | Where it comes from |
|---|---|
| Git commit of the umbrella repository and of each fork | `git rev-parse HEAD` in each repository |
| Liveness engine id and version | `mosip-onnx-default` 1.0.0 (`OnnxLivenessEngines.ENGINE_ID`, `ENGINE_VERSION`) |
| Model bundle version and the SHA-256 of every model file | `models/manifest.json`; the runner's `model_set_sha256` hashes the list |
| Full liveness configuration and its hash | the `mosip.registration.liveness.*` values from MockSync or the client, hashed as sorted `key=value` text |
| Capture path | webcam model and resolution, then MockMDS (L0 mock device), then the Registration Client version |
| Android device | phone model, Android version, camera used |
| Test dates and station | date, room, station 1 or 2 |

## 2. Evaluation types and the unit of counting

ISO/IEC 30107-3 lets a presentation be "a single attempt, a multi-attempt transaction, or another type of interaction". This protocol reports both and labels each table.

- **(a) Per attempt (PAD subsystem):** every liveness session is one presentation. This measures the engine.
- **(b) Per transaction (what an attacker faces):** one transaction is all attempts until a pass or until `max_retries` is used up. A transaction is accepted if any attempt was accepted.
- **(c) Full-system verification (operator and supervisor authentication):** an impostor presents an artefact of an enrolled operator at login; the result is accepted only if liveness passes **and** the face matcher accepts. This gives IAPAR (impostor attack presentation accept rate) per species, and FRR (false rejection rate) for genuine operator logins.

**Classification of one attempt** (implemented in `tools/eval/scenario-runner` class `Outcome` and in `tools/eval/analyze.py`):

| Engine result | Class | Counts toward |
|---|---|---|
| `PASS` | ACCEPT (classified as bona fide) | APCER for attacks |
| failure whose `FailureReason.countsAsAttempt()` is true: `PAD`, `INTEGRITY`, `ACTIVE`, `TIMEOUT`, `BELOW_THRESHOLD`, `BINDING` | REJECT (classified as attack) | BPCER for bona fide |
| any other ending: `QUALITY` (no face, several faces, too dark, blurred), `DEVICE`, `ENGINE`, `CANCELLED`, runner time limit | NO_RESPONSE | APNRR / BPNRR only |

A challenge timeout (the person or artefact did not perform the prompted action in time) is a REJECT, because the engine made a liveness decision. A session that never found a usable face is a NO_RESPONSE, never a correct rejection.

## 3. Attack potential

"Basic", defined per species as:

- at most 8 hours to prepare and use;
- at most USD 30 of materials;
- commodity equipment only: office printer, phone, laptop or tablet;
- a tester with a 30-minute briefing and no PAD expertise.

These values mirror the parameters of a well-known commercial Level 1 test, but the report never uses the words "Level 1".

## 4. Roles

- **Tester (attacker):** never the person who tuned the models or chose the thresholds.
- **Recorder:** a second person who logs the species, condition, subject and result of each presentation. The recorder is not captured in the same session.
- Both people's experience (years, prior PAD work) is written in the report.
- Subjects are cooperative for bona fide presentations; the tester presents all artefacts.

## 5. Subjects and splits

- **12 adults**, pseudonymous IDs S1-S12.
- **Development split S1-S4 (team members):** used only to choose thresholds; never reported as test results.
- **Test split S5-S12 (volunteers):** thresholds are frozen before any test-split capture.
- Recorded for each subject, reported descriptively only (group sizes are too small for statistics): skin tone on the Monk 10-point scale (self-reported), glasses yes or no, age band.
- The openly licensed clips used by the automated suite (subjects S1-S3 in `eval/packs/sources.yaml`) are unrelated people; they are never part of either split.

## 6. Bona fide conditions and counts

| Condition | Description |
|---|---|
| C1 | office light, frontal, 40-80 cm from the camera |
| C2 | dim room, below about 50 lux (one lamp) |
| C3 | backlit (window behind the subject) |
| C4 | strong side light |
| C5 | clear glasses (the subject's own, or lent ones) |

- Test split: 8 subjects x 5 conditions x 5 transactions = **200 bona fide transactions** (desktop).
- Development split: 4 subjects x 5 conditions x 3 transactions = 60 transactions, recorded as frame packs so thresholds can be tuned offline with the scenario runner.
- Everyday headwear (cap, hijab, turban) is kept on if the subject normally wears it.

## 7. Attack species

At least 60 attack transactions per species on the test split (8 source subjects x 8 transactions, rounded up), made only from the source subject's own images.

| PAIS | Recipe | Material cost | Preparation |
|---|---|---|---|
| P1 | Colour print of a frontal photo on A4 matte paper, held flat by hand | about USD 1 | 15 min |
| P2 | Glossy photo print (10x15 cm), gently curved in the hand | about USD 2 | 15 min |
| P3 | A4 print with the eyes (and mouth) cut out, the attacker's own eyes behind | about USD 1 | 30 min |
| P4 | Photo full-screen on a smartphone at full brightness | none (own phone) | 5 min |
| P5 | Photo on a laptop or tablet screen | none (own device) | 5 min |
| P6 | Recorded bona fide video (blinks, slight motion) replayed on a laptop or tablet | none | 20 min |
| P7 | Adaptive replay: one clip per challenge action, the tester plays the clip that matches the prompt | none | 45 min |
| P8 (optional) | Paper mask: print glued on card, eye holes cut, worn by the tester | about USD 3 | 60 min |

- **Out of scope, stated in the report:** 3-D masks (silicone, latex, resin), make-up, deepfakes and face swaps, and every injection attack (virtual camera, emulator, stream substitution, replaying frames into the device API).
- **Order:** about one bona fide transaction for every two attack transactions, interleaved, so lighting drift and tester fatigue affect both.
- **Smaller team fallback** (plan section 10.6): P1, P4, P6 and P7 x 30 transactions plus 100 bona fide.
- **Android replication:** P1, P4 and P6 x 30 plus 60 bona fide on the lowest-specification phone available.
- **Full-system test (c):** 4 enrolled operators x 5 attempts x (P1, P4, P6) = 60 impostor presentations, plus 40 genuine operator logins.

## 8. Procedure

1. **Source sessions** (test split): frontal photos, a P6 video and P7 per-action clips of each subject, taken with a phone camera, never with the webcam under test. About 20 minutes per subject.
2. **Artefact production:** print P1-P3 and P8; load P4-P7 onto the replay devices. Photograph each artefact type once (consenting subject only) for the report.
3. **Threshold choice on the development split:** run the development frame packs through the scenario runner and choose the lowest passive threshold that gives zero accepted development attacks with BPCER at most 10%. Write the chosen values and the evidence into the report.
4. **Freeze:** after the freeze no engine, model or threshold changes are allowed. Any change after the freeze needs a full re-run of the automated suite and a note in the report.
5. **Capture:** run the transactions on both stations (same webcam model). A transaction ends at a pass or at the maximum number of attempts. The recorder logs every attempt.
6. **Analysis:** `python tools/eval/analyze.py attempts.jsonl --out report.md`.

**One JSON line per attempt, no images** (schema `liveness-eval-attempt/1`, the same one the scenario runner writes, so one analysis script serves both). Required fields:

`schema, run_id, txn_id, scenario, kind (bona_fide | attack | other), species, subject_id, condition, split, workflow, attempt_no, last_in_txn, txn_outcome, outcome (ACCEPT | REJECT | NO_RESPONSE), failure_reason, reason_codes[], layer, no_response_reason, method, active_triggered, passive_score, challenges[], t_passive_ms, t_total_ms, frame_ms, model_set_sha256, config_hash, platform`.

For live capture the values come from the client's liveness diagnostic log (`mosip.registration.liveness.diagnostic.enable=Y` on the test stations only) plus the recorder's labels (subject, species, condition). The diagnostic log never contains images or embeddings.

## 9. Metrics and statistics

All computed by `tools/eval/analyze.py`, separately for evaluation types (a) and (b).

- **APCER per species** = attacks of that species accepted / attacks of that species with a decision.
- **APCER_max** ("APCER_AP" in the standard) = the highest APCER over all species of the basic attack potential. It is the headline security figure, in bold. Species are never averaged.
- **BPCER** = bona fide presentations rejected / bona fide presentations with a decision, overall and per condition C1-C5.
- **APNRR and BPNRR** = presentations without a decision / all presentations of that kind. Reported next to, never inside, APCER and BPCER.
- **Intervals:** exact Clopper-Pearson, 95%, two-sided, per species and for BPCER. BPCER also gets a subject-level bootstrap interval (2,000 resamples of whole subjects), because attempts by one person are correlated.
- **Zero errors:** the report writes "0/60 accepted (APCER 0%, one-sided 95% upper bound 4.9%)", never "0% APCER" alone. One-sided upper bounds for 0/n: n=30 9.5%, n=40 7.2%, n=60 4.9%. The two-sided upper bound for 0/60 is 5.96%; the report labels which bound it shows.
- **Stage attribution:** share of rejected attacks stopped before any challenge (passive PAD, integrity) and during or after challenges.
- **Challenge usability:** bona fide completion rate per challenge type and median time to complete.
- **Retry amplification:** with per-attempt APCER p and r allowed attempts, attacker success per transaction = 1 - (1 - p)^r; the per-transaction table measures it directly.
- **Processing time:** PAD time per decision and full-system time, median and p95, on the named hardware, for desktop and Android.
- **DET curve** of the passive score per species, normal-deviate axes, with the frozen operating point marked; **BPCER20** (BPCER at APCER 5%) and BPCER10. BPCER100 is not reported: with 60 attacks per species the APCER resolution is 1.7%.
- **Never the headline:** ACER, HTER or EER (averaged error rates).

## 10. Consent and data handling

- **Consent form** (signed before any capture) covers: purpose (hackathon PAD test of the MOSIP registration clients), what is recorded (face video and photos, and artefacts made from them), storage on one BitLocker-encrypted laptop (never cloud, never git), deletion of all raw media on 2026-11-30, a separate opt-in for appearing in the demo video or slides, the right to withdraw at any time without giving a reason, adults only, and that artefacts are made only from the subject's own images and prints are shredded after the test.
- **Pseudonyms:** results use S-numbers only; the name-to-ID map is kept on paper, apart from the data.
- **Notice:** a one-paragraph summary goes to a faculty adviser or ethics contact before capture.
- **Law:** the principles of India's Digital Personal Data Protection Act 2023 are followed: consent, purpose limitation and erasure.
- **Logs:** attempt records and diagnostic logs hold scores, decisions, reason codes and timings only; no images, embeddings or templates.
- **Automated suite:** the openly licensed clips are used only for automated regression tests, stay outside git (`../run/frame-packs/`), are never shown in the video or slides, and are never used to train a model. Only their manifests (source URL, licence, attribution, commands, SHA-256) are committed.

## 11. Report layout

`eval/report.md` (laid out like a laboratory confirmation letter, using ISO/IEC 30107-3 terms):

1. Item under test (section 1 facts).
2. Test approach, attack potential, roles and their experience, cooperative subjects.
3. PAIS table: recipe, materials, cost, preparation time, one photo per artefact type (consenting subject only).
4. Results per species, per attempt and per transaction; APCER_max in bold.
5. Bona fide results per condition: n, BPCER with intervals, BPNRR, share that needed challenges, median time.
6. DET plot and BPCER20.
7. Full-system results: IAPAR per species and FRR.
8. Processing times, desktop and Android.
9. ISO/IEC 30107 alignment matrix (met, partial, not addressed): terminology, PAD types (passive = anatomical characteristics, active = voluntary reactions), documented approach, species rated separately, APCER_max and BPCER, non-response, efficiency, enrolment versus verification scenarios, offline PAD, full-system IAPAR, roles, injection (out of scope, addressed by design).
10. Limitations, verbatim:
    - "Results were obtained by the development team on N subjects and M attack presentations with basic-attack-potential artefacts."
    - "Per ISO/IEC 30107-3, non-zero APCER only proves an attack can succeed, and zero observed errors bound but do not prove security (one-sided 95% upper bound X%)."
    - "Error rates are not comparable to third-party lab results. Subjects are not demographically representative."
    - "High-attack-potential PAIs (3D masks), deepfakes and injection attacks were not tested. The mock L0 device provides no hardware trust."

## 12. Relation to the automated suite

| Plan test | Automated scenario (synthetic, technology regression) | Evaluation species (real artefacts) |
|---|---|---|
| T-SCN-P-01 A4 print | `atk-*-print-still` (SYN-A2), `atk-*-print-moved` (SYN-A3) | P1 |
| T-SCN-P-02 curved print | `atk-s2-print-tilted` (SYN-A4, a flat print tilted to fake head turns) | P2 |
| T-SCN-P-03 eye cut-out | none (needs a physical artefact and a live attacker) | P3 |
| T-SCN-P-04/05 phone or laptop photo | `atk-*-screen-photo` (SYN-A5) | P4, P5 |
| T-SCN-P-06 video replay | `atk-*-screen-replay` (SYN-A6) | P6 |
| T-SCN-P-07 adaptive clips | `atk-*-adaptive-screen` (SYN-A7); `atk-*-adaptive-wrong-clip` (SYN-A9, wrong clip) | P7 |
| T-SCN-P-08 frozen frame | `atk-*-injected-still` (SYN-A1), `atk-m-mockmds-stock` (SYN-P0) | injection, out of scope |
| T-SCN-P-09 looped replay | `atk-*-looped-clip` (SYN-A8) | injection, out of scope |
| T-SCN-P-10 swap at capture | covered by the engine's binding unit tests, not by frame packs | - |
| Full-system test (c), impostor at operator login | `atk-*-login` (the print and replay packs on the `login_auth` workflow; liveness only, no face matcher) | P1, P6 at login |

Synthetic species names always start with `SYN-`, so they cannot be mixed up with the P1-P8 results.
