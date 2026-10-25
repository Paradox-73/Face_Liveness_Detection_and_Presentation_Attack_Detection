# Face liveness and presentation attack detection for MOSIP registration clients

Team Badmosip · MOSIP Decode 2026 · Problem Statement 04 · summary

## 1. The problem (PS04 overview)

- MOSIP's desktop and Android Registration Clients capture faces from L0/L1 biometric devices for resident
  registration, operator authentication and supervisor authentication, but did not check that a live person was in
  front of the camera.
- A printed photo, a picture on a screen or a replayed video could therefore become a resident's face, sign a
  packet as an operator, or approve packets as a supervisor.
- PS04 asks for a hybrid check inside both clients: passive liveness first, and randomly chosen facial actions
  (blink, smile, turn, look) when passive confidence is below a configurable threshold.
- It must block capture and authentication on failure, work the same online and offline, be configurable per
  workflow, give clear guidance with a generic message for attacks, and be audited.
- It must stay vendor-neutral (common device interface, adapters) and be evaluated against ISO/IEC 30107.

## 2. Solution and value

- **One engine, both clients.** A Java 11 library (`liveness/`) runs inside the desktop client and inside a new
  native Android module; same code, same models, same configuration keys, same audit event IDs, same messages.
- **Hybrid, offline check.** Quality gate → passive PAD (two MiniFASNet models) → if needed (and always, in the shipped defaults, for every workflow except the exception photo), 2 challenges drawn
  with a secure random generator from 8 action types → frame integrity (frozen frames, loops, timestamps) and a
  flat-face check → capture binding: the device's signed capture must show the same face within the session's
  time window, with a one-use token.
- **Cannot be skipped.** Gates in the service layer refuse any face capture or face authentication without a valid
  pass, whichever screen calls them.
- **Six workflows.** Resident capture, exception photo (policy off by default), operator login, packet
  authentication, supervisor approval and reviewer step, operator onboarding. Operator and supervisor faces are
  also matched 1:1 with SFace, so a live but different person is refused.
- **Evidence for MOSIP.** Each face record in the packet carries `LIVENESS_*` entries (decision, method, score
  bucket, challenges, engine, model hash); 17 audit events `REG-EVT-LV01..17`; no images or raw scores in logs.
- **Configurable through MOSIP sync.** 46 default settings (44 `mosip.registration.liveness.*` keys, 2 other): enable, thresholds, active policy
  per workflow, challenge types and count, timeout, retries, behaviour after the last failure, guidance texts.
- **Clear UX.** PS04 wording, oval guide, step and attempt counters, generic attack message, recovery guidance;
  English, French and Arabic (right to left) on desktop, plus Hindi, Kannada and Tamil on Android.
- **Runnable by anyone.** A mock face device that streams real frames and injects faults, and a local MOSIP server
  that both real clients log in and sync against.

## 3. Architecture

Picture: [`docs/design/img/architecture.png`](../design/img/architecture.png) (component view). The text version:

```
 SBI face device (L0/L1, desktop)      built-in camera as L0 device (Android)
              \                                  /
               FaceCaptureDevice (common device interface, adapters)
                              |
         liveness-core session (offline, shared)
           quality → passive PAD → challenges → integrity/planarity → binding
                              |                                  |
             service gate (capture, authentication)     audit REG-EVT-LV01..17
                              |
          packet: LIVENESS_* in the face biometric record, meta info
          configuration: MOSIP global parameters, synced from the server
          models: 5 ONNX files, SHA-256 + RSA-signed manifest, checked at every start
```

Design details: `liveness/README.md`, `docs/design/desktop-liveness-services.md`,
`docs/design/desktop-liveness-ui.md`, `docs/design/android-liveness.md`, `docs/ux/flows.md`.

## 4. Evidence

| Evidence | Result | Where |
|---|---|---|
| Unit and component tests (engine, clients, mocks, tools) | 2,699 passed on 2026-10-25, 0 failures; about 1,340 are written by the team, the rest are upstream MOSIP tests; engine line coverage 93% | `docs/test-report/README.md` section 2 |
| Desktop client, scripted end-to-end run (13 flows) | 41 of 41 checks passed at the earlier defaults; 71 of 71 on 2026-10-17 and in two runs on 2026-10-20 at the current policy with blink and smile only | `docs/test-report/desktop-e2e.md` |
| Android client, scripted end-to-end run (16 flows) | 58 of 58 checks passed at the earlier defaults; 59 of 74 on 2026-10-17 at the current policy with blink and smile only | `docs/test-report/android-e2e.md` |
| Desktop and Android give the same decision (bonus B4) | 19 replayed cases on desktop, 19 in Android unit tests, 6 on an emulator; one ONNX Runtime release (1.20.0) on both clients | `docs/test-report/interoperability.md` |
| Automated scenarios (75, 275 transactions) with ISO/IEC 30107-3 metrics | shipped defaults (2026-10-14), per transaction: no attack transaction accepted, APCER 0.0% for every species and workflow; genuine rejected 0.0%. Passive first, as the problem statement describes: attacks accepted (counts in the linked file) | `docs/eval/results/20261014-shipped/README.md` |
| Per-frame processing, 640x480 | latency run of 2026-10-20, 1 thread, shared laptop (Intel Core i5-12450H): p50 39.4 ms, p95 127.7 ms; the 2026-09-30 quieter-machine run gave p50 21-23 ms, p95 38-45 ms (desktop JVM); target 20 ms not met | `docs/eval/results/20261020-latency/README.md`, `docs/test-report/README.md` section 5 |
| Android phone measurement (bonus B7, B8) | OnePlus CPH2569, Android 15, charging at about 40%, two runs of 2026-10-24: best is 2 threads on CPU or XNNPACK, p50 58-71 ms, p95 74-92 ms per frame (target 20 ms missed); NNAPI 5-8 times slower; peak PSS 239-338 MB | `docs/test-report/android-performance.md` |
| PS04 requirements | every bullet traced to code and test; open items listed | `docs/traceability.md` |

**What the results cover:** attacks in the automated suite are synthetic and the genuine data is three people from
open clips, so these are regression results, not an ISO/IEC 30107-3 evaluation. A team-run run with real faces and
real artefacts on 2026-10-24/25 (`docs/eval/results/real-faces-2026-10-24.md`) gave: genuine 8 of 8 phone-camera
transactions accepted; 0 of 32 attack transactions accepted (print, phone photo, video replay); the phone photo of one
subject refused by PAD 4 of 4 (APCER 0/4); 28 transactions ended at the capture-quality gate with no decision (APNRR);
6 of 6 instrumented tests on a OnePlus CPH2569. The volunteer evaluation is planned (`docs/eval/protocol.md`). Because synthetic prints passed the
passive check, every workflow that matches a face now always asks for challenges; this costs time, not rejections
(median accepted registration attempt 11.0 s, `docs/eval/results/20261014-latency/README.md`). Every synthetic replay is refused, including an adaptive screen replay that plays the right clip
for each prompt (it passed 4 of 8 transactions until a screen-pattern check was added on 2026-10-05; proven on
software-made screens). Android ran on an emulator with replayed frames and, for the instrumented tests and the
latency measurement, on a physical phone (OnePlus CPH2569); L1 and vendor face devices are next.

## 5. Next steps

1. Run the in-house volunteer evaluation (12 people, 8 attack types, both clients).
2. Android: vendor SBI face devices and L1; repeat the phone measurement unplugged and on a second phone model (one phone measured on 2026-10-24;
   see docs/test-report/android-performance.md).
3. Device-side PAD for L1 devices, against adaptive replays.
4. Offline import of signed model updates: done on both clients (bonus B6; Android button in Settings, Device
   Settings); run it on a physical phone.
5. Upstream contribution as reviewable pull requests.

## 6. Links

| Item | Location |
|---|---|
| Source code | this repository; start at `README.md` |
| Install and run | `docs/install/desktop.md`, `docs/install/android.md` |
| Test data (demo users, frame packs) | `docs/test-data.md` |
| Screens, both clients | `docs/ux/README.md` |
| Technologies considered | `docs/design/technology-evaluation.md` |
| Demo video | https://youtu.be/rod0HENUmQQ |
