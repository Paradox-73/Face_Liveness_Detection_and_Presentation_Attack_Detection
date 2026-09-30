# Pitch deck outline (7 core slides)

Team Badmosip, MOSIP Decode 2026, Problem Statement 04. Seven slides, about 7 minutes spoken (one minute per
slide). No institution names or logos anywhere. Every number on a slide comes from
[../test-report/README.md](../test-report/README.md) or [../traceability.md](../traceability.md); the speaker
notes name the source so a question can be answered with the document open.

Visual rules: one message per slide, white background, the clients' own blue (`#005BAA`) as accent, screenshots
from `docs/ux/screenshots/` (none shows a real face), numbers large, limitations printed on the slide that makes
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
  - Desktop resident challenge: `screenshots/desktop/e2e-03-low-confidence-challenge.png` ("Please blink", step 1
    of 2).
  - Android challenge: `screenshots/android/e2e-06-low-confidence-challenge.png` ("Please smile").
  - Desktop supervisor verification refusing a replay: `screenshots/desktop/e2e-09-eod-replay-refused.png`.
- Under them, the flow in five words: **passive → challenge → bind → gate → evidence**.
- One line: "One offline engine, both clients, six workflows: registration, exception photo, operator login, packet
  authentication, supervisor approval, operator onboarding."

**Speaker notes**
- Passive first: most live people pass in under a second of video without doing anything.
- When the passive score is below the workflow's threshold (80 for residents, 95 for operators and supervisors), the
  client asks for randomly chosen actions: blink, smile, turn, look. The user never picks the action.
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

- Side table "which layer stops which attack" from `docs/test-report/README.md` 3.2 (shipped suite): injected still
  → integrity; screen photo → passive PAD; print → challenges; screen replay → active PAD; looped clip → loop
  detection; wrong clip → challenge check.

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
| Global parameters + sync | 44 `mosip.registration.liveness.*` keys, per workflow; a change on the server reaches the client at the next sync (audit LV15) |
| Packet (CBEFF biometric record `others`) | `LIVENESS_DECISION`, `METHOD`, score bucket, challenges, engine and model hash on every face |
| Audit framework | 17 events `REG-EVT-LV01..17`, same IDs on desktop and Android, no images or raw scores |
| Biometric SDK interface (`IBioApiV2`) | SFace face-match SDK loaded by MOSIP's own SDK factory for FACE |
| Existing screens | liveness window opened from the existing capture and authentication screens |

- Footer: "Upstream code changed surgically: 84 files in the desktop client, 148 in the Android client, each
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
  - **2,224** unit and component tests, 0 failures (13 suites).
  - **41 / 41** checks: scripted end-to-end run on the real desktop client.
  - **58 / 58** checks: scripted end-to-end run on the real Android client.
  - **39** automated liveness and attack scenarios, scored with ISO/IEC 30107-3 metrics.
- Small table (per attempt, synthetic attacks):

| Configuration | Worst attack species | All attacks | Genuine rejected |
|---|---|---|---|
| before: passive first everywhere | 100% (print moved, looped clip) | 17.6% | 58.2% |
| **shipped now** (challenges on registration, sign-in, onboarding; engine fixes) | 25% (adaptive replay) | **1.7%** | **29.4%** |

- Printed on the slide: "Synthetic attacks, three people from open clips: regression results, not an ISO/IEC
  30107-3 evaluation. Volunteer evaluation next."

**Speaker notes**
- The end-to-end runs cover every PS04 flow on both clients: passive pass, challenges, attacks, maximum attempts,
  device disconnect, supervisor approval, configuration by sync, offline, a live but wrong person refused by the
  face match, audit rows and packet evidence decrypted on the server side.
- The suite changed our defaults: passive first let synthetic prints through, so registration, sign-in and
  onboarding now always ask for challenges (one synced key per workflow). All attacks fell from 17.6% to 1.7%;
  cost about 3 seconds per capture (median 9.7 s instead of 6.6 s).
- The same run found an engine defect (a left head turn read as a smile) that we fixed: genuine rejection fell from
  58.2% to 29.4%, and 12 of the remaining 15 rejections come from one synthetic very dark clip.
- What still passes: a replay that plays the right clip for each prompt, 4 of 16 attempts. That needs a stronger
  model or device-side PAD.
- Per-frame time is 21-23 ms median on a laptop, 38-45 ms at the 95th percentile: above our own 20 ms target.

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
  mock server, automated attack suite, end-to-end runs on both clients.
- Next (in order):
  1. Run the volunteer evaluation (12 people, 8 attack types, ISO/IEC 30107-3 method).
  2. Android: vendor SBI face devices and L1; measurements on a physical low-end tablet, including the XNNPACK and
     NNAPI accelerators already selectable.
  3. Device-side PAD for L1 devices (challenge sent in the capture request), the answer to adaptive replays.
  4. Offline model import on Android (built on desktop).
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
| A4 | Per-species results with confidence intervals | `docs/eval/results/20260930-engine-fixes/shipped/report.md` (before: `docs/eval/results/20260930/*/report.md`) |
| A5 | Latency tables | `docs/test-report/README.md` section 5 |
| A6 | Limitations and claims we do not make | `docs/test-report/README.md` section 6 |
| A7 | Traceability summary | `docs/traceability.md` |
| A9 | Technologies considered and why these | `docs/design/technology-evaluation.md` |
| A8 | Screen index, both clients | `docs/ux/README.md` |
