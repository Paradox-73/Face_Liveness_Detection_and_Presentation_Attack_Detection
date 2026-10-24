# Real-person results, 2026-10-24 and 2026-10-25

Real faces and real physical artefacts, passed through the shipped liveness engine and the shipped configuration. The
frames come from phone-camera and laptop-camera recordings of two team members and from photographs and recordings of
printed, phone-screen and replayed faces. Frames are cut into packs by `tools/eval/ingest-recordings.py` and replayed
by the scenario runner (`tools/eval/scenario-runner`); `tools/eval/analyze.py` computes the metrics. The report
follows the ISO/IEC 30107-3 metric definitions. It is a team-run technology evaluation, not an ISO/IEC 30107-3
certification and not an accredited laboratory evaluation.

No video, frame or face image is stored in this repository. The run folders hold `attempts.jsonl` (decisions, reason
codes, scores, timings), `summary.md` and `report.md` and stay outside git.

## Headline

| Measure | Result |
|---|---|
| Genuine (bona fide) transactions accepted, phone camera, normal light (C1), subjects S1 and S2 | **8 of 8** (BPCER 0/8 per transaction) |
| Genuine attempts refused on the way, phone camera | 4 of 12 attempts (`INTEGRITY` / `FACE_SWITCH`), all in transactions that drew `LOOK_DOWN`; each of those transactions passed on a later attempt |
| Genuine transactions accepted, laptop camera, subject S2 | 2 of 4 (both refusals: `BLINK` `NOT_HELD`, see the notes below) |
| Attack transactions accepted | **0 of 32** |
| Attack transactions refused by passive PAD | 4 of 32 (P4 phone photo of S2, passive score 0, `PAD_SCORE`) |
| Attack transactions with no decision (quality gate) | 28 of 32 |
| Per-frame time on the laptop (decode + analysis + PAD), phone-clip run | p50 12.1 ms, p95 18.5 ms (n = 10,847 frames) |
| Per-frame time on the laptop (decode + analysis + PAD), laptop-clip run | p50 17.2 ms, p95 25.7 ms (n = 1,204 frames) |
| On-phone instrumented suite (OnePlus CPH2569, Android 15) | **6 of 6** passed |

The per-frame times use the same measurement as the latency suite of 2026-10-20 (p95 127.7 ms), which ran on the same
laptop while other builds were running; on 2026-10-24 the laptop ran no other builds.

## Attacks per species

Unit: the transaction (one capture until the first accepted attempt or the last allowed attempt). "No decision
(quality gate)" means the client never accepted a frame for analysis, so no liveness decision exists. These
transactions are reported as APNRR (presentations without a decision), separate from APCER, as
[protocol.md](../protocol.md) section 9 requires. They are not counted as PAD refusals.

| Species | Artefact | Source subjects / groups | Transactions | Accepted | Refused by PAD | No decision (quality gate) | APCER per transaction |
|---|---|---|---|---|---|---|---|
| P1 | printed photo | S1, S3, S4, S5 (4 groups) | 16 | 0 | 0 | 16 | no figure (0 accepted, no PAD decision) |
| P4 | photo on a phone screen | S1, S2 (2 groups) | 8 | 0 | 4 (S2) | 4 (S1) | 0/4 (95% CI 0.0%-60.2%) |
| P6 | video replay on a screen | S1, S2 (2 groups) | 8 | 0 | 0 | 8 | no figure (0 accepted, no PAD decision) |
| Total | | 8 artefact groups | 32 | 0 | 4 | 28 | |

- The P4 photo of S2 reached the passive PAD in all 4 transactions (3 attempts each) with passive score 0 and was
  refused with `PAD_SCORE`. PAD made a decision on this group, so the P4 APCER of 0/4 is the APCER figure of this run.
- The other 28 transactions ended at the capture-quality gate with `FACE_TOO_SMALL` (6 groups) or `BLURRY` (1 group,
  the S3 print): the face on the artefact was smaller than a real face at capture distance, so the client accepted no
  frame for analysis and nothing reached PAD. No attack was accepted at the gate or later.

## Genuine users

| Clips | Subjects | Transactions | Accepted | First attempt accepted |
|---|---|---|---|---|
| phone camera, C1 | S1, S2 | 8 (4 each) | 8 | 6 (2 transactions needed a second or third attempt) |
| laptop camera, C1 | S2 | 4 | 2 | 1 |

- Phone clips: in the 2 transactions that drew `LOOK_DOWN`, two attempts each ended with `INTEGRITY` /
  `FACE_SWITCH` and the third attempt passed (4 of 12 attempts in all). `LOOK_DOWN` is outside the shipped challenge
  pool (`BLINK`, `SMILE`, `TURN_LEFT`, `TURN_RIGHT`), so it does not occur in a shipped session.
- `TURN_LEFT` and `TURN_RIGHT` passed on real faces for every subject.
- Laptop clips: the set holds a natural, short blink and no deliberate blink clip, so the two refusals ended with
  `BLINK` / `NOT_HELD` (the blink in the clip is shorter than the minimum hold time). The other 2 transactions
  passed.

## Method

1. Subjects: two team members (S1, S2, pseudonyms) recorded themselves with consent. Three further people (S3-S5)
   supplied photographs for print attacks, with consent. Names are not recorded.
2. `tools/eval/ingest-recordings.py` cut the recordings into `rec-*` frame packs and generated one scenario per
   subject, condition and artefact; the scenario runner replayed them through the shipped engine (real ONNX models,
   signed model bundle) and the shipped configuration; `tools/eval/analyze.py` wrote the report.
3. Attacks: P1 print held to the camera (4 subjects), P4 photo shown on a phone screen (2 subjects), P6 video
   replayed on a screen (2 subjects); 4 transactions per group, seeds 1 to 4.
4. On the phone, the `livenessmanager` instrumented suite ran on a OnePlus CPH2569 (Android 15): Android Keystore
   signer (provisioned key import, self-signed key), signed model manifest check, face detector on the replay frame,
   an injected still image rejected by the real engine, and desktop/Android parity on the committed cases. 6 of 6
   passed.
5. Run folders (outside git): `run/eval/rec-phone-and-attacks/` (40 transactions, 164 attempt records),
   `run/eval/rec-laptop/` (4 transactions) and `run/phone-ps04/instrumented.txt`.

The runner prints `expectation mismatches` for the attack groups that ended at the quality gate (expected REJECT,
observed NO_RESPONSE). That is the correct classification and the reason these are reported as APNRR.

## What the refusal reasons mean

| Reason | Layer | Meaning |
|---|---|---|
| `PAD_SCORE` | passive PAD | the passive presentation-attack score is below `pad.reject_threshold`; the frame looks like a photo or a screen |
| `FACE_SWITCH` | integrity | the face identity (SFace embedding) changed during the attempt, as if another person stepped in |
| `NOT_HELD` | challenge | the prompted action (here a blink) was detected but not held for the minimum time |
| `FACE_TOO_SMALL` | quality gate | the face is smaller than the minimum size, so the frame is not analysed |
| `BLURRY` | quality gate | the frame is below the sharpness minimum, so it is not analysed |
| `PASSIVE_TIMEOUT` | passive stage | the passive stage ended by its time limit; with a quality reason next to it, no frame passed the quality gate in time and the attempt ends without a decision |

## Reading the numbers

- APCER for P4 is 0/4 transactions (one source artefact, so the interval over artefacts is wide). For P1 and P6 no
  APCER is reported because no attempt reached a PAD decision.
- BPCER is 0/8 per transaction on the phone clips (two-sided 95% CI 0.0%-36.9%).
- Two subjects and one lighting condition (C1) were recorded. Dim light (C2) and further subjects follow the
  in-house protocol ([protocol.md](../protocol.md)).
- The full machine-made report of each run is `report.md` in its run folder.
