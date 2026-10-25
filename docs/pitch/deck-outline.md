# Pitch deck outline (7 core slides)

The submitted deck has 13 slides: these seven, split out and joined by target audience, technology stack, what
is new, limits and roadmap slides.

Team Badmosip, MOSIP Decode 2026, Problem Statement 04. Seven slides, about 7 minutes spoken (one minute per
slide). No institution names or logos anywhere. Every number on a slide comes from
[../test-report/README.md](../test-report/README.md) or [../traceability.md](../traceability.md); the speaker
notes name the source so a question can be answered with the document open.

Visual rules: one message per slide, white background, the clients' own blue (`#005BAA`) as accent, screenshots
from `docs/ux/screenshots/` (faces only from openly licensed stock clips, never a team member), numbers large, limitations printed on the slide that makes
the claim.

---

## Slide 1. Problem

**Title:** A photo should not become a person's identity

**On the slide**
- Left: the resident face capture of the MOSIP desktop client with liveness switched off, a phone photo held to the
  camera, and the result "captured" (frame from the video hook, 0:00-0:12; the photo is of a consenting team member).
- Right, three lines:
  - Registration: a printed or replayed face creates a fraudulent identity record.
  - Operator login and packet authentication: someone else's photo signs the packet.
  - Supervisor approval: a supervisor's photo lets an operator approve their own packets.
- Footer: "Registration centres often work offline, and devices come from many vendors."

**Speaker notes**
- MOSIP's registration clients capture faces through SBI devices (MOSIP's device interface) and store them in the
  packet. Before this work neither client checked that the face in front of the camera was a live person.
- The same gap affects the three workflows PS04 names: resident capture, operator authentication, supervisor
  authentication.
- The fix has to run on the client, offline, with any compliant device, and must leave evidence that the server can
  check later.

---

## Slide 2. Solution

**Title:** Liveness and presentation attack detection in both MOSIP registration clients

**On the slide**
- Three columns with one screenshot each:
  - Desktop resident challenge: `screenshots/desktop/e2e-03-low-confidence-challenge.png` ("Please smile", step 1
    of 2, recorded face in the preview).
  - Android challenge: `screenshots/android/e2e-06-low-confidence-challenge.png` ("Please smile").
  - Desktop supervisor verification refusing a replay: `screenshots/desktop/e2e-09-eod-replay-refused.png` (the
    replayed video visible in the preview).
- Under them, the flow in five words: **passive → challenge → bind → gate → evidence**.
- One line: "One offline engine, both clients, six workflows: registration, exception photo, operator login, packet
  authentication, supervisor approval, operator onboarding."

**Speaker notes**
- Every workflow that matches a face (registration, sign-in, onboarding, packet, exception and end-of-day
  approval) ships `ALWAYS_ACTIVE`: after a passive stage of 30 frames (2 s) the client asks for two randomly chosen
  actions (blink, smile, turn, look). A live resident therefore does need to act; the median accepted registration attempt takes
  11.0 s of stream (2026-10-14). Passive first is one synced setting per workflow (`PASSIVE_THEN_ACTIVE`: actions only when the
  passive score is below 80 for residents, 95 for operators and supervisors); it is not the default because
  synthetic prints scored above the passive threshold. The user never picks the action.
- A pass does not unlock the camera; it unlocks one signed capture from the device, which must show the same face
  within a time window (capture binding). The service layer refuses any face capture or face authentication that
  lacks such a pass, so no screen can skip it.
- Attacks get a generic message; details go to audit and diagnostics only (PS04 UI/UX 6).

---

## Slide 3. How it works

**Title:** Five layers, each stopping a different attack

**On the slide**
- Architecture diagram: [`docs/design/img/architecture.png`](../design/img/architecture.png) (SVG next to it for
  a sharp slide; source in `docs/design/architecture.md` section 1). The passive-to-active decision picture for a
  question: [`docs/design/img/decision-flow.png`](../design/img/decision-flow.png).
- The layers, as speaker reference (not a second picture on the slide):

```
 SBI face device (L0/L1)        Android built-in camera (L0)
        |  MJPEG stream                 |  CameraX frames
        v                               v
   FaceCaptureDevice adapter  (common device interface)
        |
        v
  liveness-core session (Java 11, shared by both clients, offline)
   1 quality gate        no face, two faces, dark, off-pose
   2 passive PAD         MiniFASNet V2 + V1SE ensemble, score 0-100
   3 active challenges   secure random order, 8 action types
   4 frame integrity     frozen frames, loops, timestamps, flat-face check
   5 capture binding     signed device capture = same face, same time window, one-use token
        |
        v
  service gate -> packet (LIVENESS_* in the biometric record) + audit REG-EVT-LV01..17
```

- Side table "which layer stops which attack" from `docs/test-report/README.md` 3.2 (suite of 2026-09-30): injected still
  → integrity; screen photo → passive PAD; print → challenges; screen replay → active PAD; looped clip → loop
  detection; wrong clip → challenge check. Exception: an adaptive screen replay (a clip of the right action for
  each prompt) passes all layers in the synthetic suite.

**Speaker notes**
- Models: YuNet (face detection, MIT), Face Mesh (landmarks, Apache-2.0), two MiniFASNet models (PAD, Apache-2.0),
  SFace (face match, Apache-2.0). 24.5 MiB in total, shipped as a signed bundle: the client verifies every file's
  SHA-256 against an RSA-3072 signed manifest at start and refuses to run on a mismatch.
- The engine is a plug-in behind `LivenessEngineProvider`, so a certified vendor engine can replace ours without
  touching the clients.
- Everything runs on the client; there is no network code in the engine (enforced by an architecture test).

---

## Slide 4. Integration with MOSIP

**Title:** Built into MOSIP's own mechanisms, not beside them

**On the slide**
- Table:

| MOSIP mechanism | What we used it for |
|---|---|
| SBI device interface (STREAM, RCAPTURE, L0/L1 certification) | frame stream and the signed capture; L1 digital ID checked against the device CA |
| Global parameters + sync | 46 default settings (44 `mosip.registration.liveness.*` keys, 2 other), per workflow; a change on the server reaches the client at the next sync (audit LV15) |
| Packet (CBEFF biometric record `others`) | `LIVENESS_DECISION`, `METHOD`, score bucket, challenges, engine and model hash on every face |
| Audit framework | 17 events `REG-EVT-LV01..17`, same IDs on desktop and Android, no images or raw scores |
| Biometric SDK interface (`IBioApiV2`) | SFace face-match SDK loaded by MOSIP's own SDK factory for FACE |
| Existing screens | liveness window opened from the existing capture and authentication screens |

- Footer: "Upstream code changed surgically: 117 files in the desktop client, 209 in the Android client (2026-10-22, whitespace-only changes ignored), each
  upstream import in its own commit so the diff is reviewable."

**Speaker notes**
- Two test tools make this runnable by anyone: MockMDS extended to stream real frames, faults and L1 certificates
  (`devices/mock-mds`), and MockSync, a local MOSIP server that both real clients log in and sync against
  (`tools/mock-sync`). No MOSIP environment access is needed to run the demo.
- The face-match SDK means operator face login actually compares faces; liveness makes sure the matched face is
  a live person (PS04 operator authentication).

---

## Slide 5. Evidence

**Title:** Tested on the real clients, measured honestly

**On the slide**
- Four large numbers:
  - **2,706** tests passed on 2026-10-25 (0 failures); about 1,345 are written by the team, the
    rest come with the upstream MOSIP code (`docs/test-report/README.md` section 2).
  - **71 / 71** checks: scripted end-to-end run on the real desktop client at the shipped policy with the challenge
    pool cut to blink and smile (2026-10-17, and twice on 2026-10-20; 41 of 41 at the earlier defaults).
  - **59 / 74** checks: scripted end-to-end run on the real Android client at the same policy (2026-10-17; face
    sign-in 10 of 10, attacks 10 of 10, supervisor override 10 of 18; 58 of 58 at the earlier defaults).
  - **75** automated liveness and attack scenarios, 275 transactions, scored with ISO/IEC 30107-3 metrics.
- One line (synthetic attacks, per transaction, from `docs/eval/results/20261014-shipped/README.md`): shipped
  defaults no attack transaction accepted, genuine rejected **0.0%**; passive first, as the problem
  statement describes: attacks accepted (counts in that file).

- Add the real-person line (`docs/eval/results/real-faces-2026-10-24.md`): "Real faces and artefacts, 2026-10-24/25:
  genuine 8 of 8 phone-camera transactions accepted; 0 of 32 attack transactions accepted (print, phone photo, video
  replay); phone photo refused by PAD 4 of 4; 28 transactions ended at the quality gate with no decision; 6 of 6
  instrumented tests on a OnePlus CPH2569."
- Printed on the slide: "Team-run technology evaluation with ISO/IEC 30107-3 metrics; automated suites use
  synthetic attacks and three people from open clips. Not an ISO/IEC 30107-3 certification."

**Speaker notes**
- The end-to-end runs cover every PS04 flow on both clients: passive pass, challenges, attacks, maximum attempts,
  device disconnect, supervisor approval, configuration by sync, offline, a live but wrong person refused by the
  face match, audit rows and packet evidence decrypted on the server side.
- The suite changed our defaults: passive first let a synthetic print and looped clips through, so
  every workflow that matches a face now always asks for challenges (one synced key per workflow). The cost is time,
  not rejections: median accepted registration attempt 11.0 s (`docs/eval/results/20261014-latency/README.md`).
- The adaptive screen replay (a clip that answers each prompt) passed 4 of 8 transactions until the screen-pattern
  check of 2026-10-05. Say plainly: that result is on software-made screens. On real screens, the phone photo of S2
  was refused by PAD 4 of 4 and the video replays ended at the quality gate with no decision (8 of 8).
- Per-frame time (latency run of 2026-10-20, 1 thread, shared laptop, Intel Core i5-12450H): p50 39.4 ms, p95 127.7 ms,
  above our own 20 ms target.
  On a OnePlus CPH2569 phone (Android 15, charging at about 40%, two runs of 2026-10-24) the best setting, 2 threads on CPU or XNNPACK, gave p50 58-71 ms and p95 74-92 ms, also above the target; NNAPI was 5-8 times slower (`docs/test-report/android-performance.md`).

---

## Slide 6. Impact

**Title:** What changes for a registration centre

**On the slide**
- Three before/after rows:
  - Resident: "a photo could be enrolled" → "a face is stored only after liveness passes, with evidence in the
    packet".
  - Operator: "password or a photo" → "face login and packet signing need a live, matching face".
  - Supervisor: "approval by a held-up photo" → "approval needs the supervisor present, offline too".
- Audience strip: residents, registration officers, supervisors, programme administrators, device vendors.
- One line: "Works offline, with any SBI-compliant device, open-licensed models, configurable per workflow."

**Speaker notes**
- Residents see simple prompts in their language (English, French, Arabic right to left on desktop; plus Hindi,
  Kannada, Tamil on Android). Scores and model names never appear unless a supervisor turns on diagnostics.
- Administrators tune policy per workflow from the server; auditors can see in each packet how the face was verified.
- Vendors can plug in device-side PAD or their own engine through the published interfaces.

---

## Slide 7. Team and next steps

**Title:** Badmosip: what is done, what comes next

**On the slide**
- Done: shared engine, desktop and Android integration, six workflows, audit and packet evidence, mock device and
  mock server, automated attack suite, end-to-end runs on both clients, desktop and Android decisions compared on
  one ONNX Runtime release (`docs/test-report/interoperability.md`).
- Next (in order):
  1. Run the volunteer evaluation (12 people, 8 attack types, ISO/IEC 30107-3 method), extending the real-person run of 2026-10-24/25.
  2. Android: vendor SBI face devices and L1; repeat the phone measurement unplugged and on a second phone model (one phone measured on 2026-10-24;
     see docs/test-report/android-performance.md).
  3. Device-side PAD for L1 devices (challenge sent in the capture request), the answer to adaptive replays.
  4. Offline model import: built on both clients (Android button in Settings, Device Settings); run it on a
     physical phone.
  5. Contribute the changes upstream as reviewable pull requests.
- Team alias only; links to the repository and the video.

**Speaker notes**
- Everything on this slide is in [../traceability.md](../traceability.md) section 11 with the rows it would close.
- Ask: feedback on the default registration policy trade-off (challenges on every capture: security versus about
  3 extra seconds).

---

## Appendix slides (separate file, shown only on request)

| # | Content | Source |
|---|---|---|
| A1 | Session state diagram | `docs/ux/flows.md` section 1 |
| A2 | Configuration keys and defaults | `liveness/liveness-core/.../config/LivenessConfigKeys.java`, `docs/design/desktop-liveness-services.md` section 5 |
| A3 | Audit events LV01-LV17 | `docs/design/desktop-liveness-services.md` section 6 |
| A4 | Per-species results with confidence intervals | `docs/eval/results/20261014-shipped/report.md` (older: `docs/eval/results/20261005-shipped/report.md`) |
| A5 | Latency tables | `docs/test-report/README.md` section 5; `docs/eval/results/20261014-latency/report.md`; `docs/test-report/android-performance.md` |
| A6 | Limitations and claims we do not make | `docs/test-report/README.md` section 6 |
| A7 | Traceability summary | `docs/traceability.md` |
| A9 | Technologies considered and why these | `docs/design/technology-evaluation.md` |
| A8 | Screen index, both clients | `docs/ux/README.md` |
