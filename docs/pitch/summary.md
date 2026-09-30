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
- **Hybrid, offline check.** Quality gate → passive PAD (two MiniFASNet models) → if needed (and always for
  registration, sign-in and onboarding in the shipped defaults), 2 challenges drawn
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
- **Configurable through MOSIP sync.** 44 `mosip.registration.liveness.*` keys: enable, thresholds, active policy
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
| Unit and component tests (engine, clients, mocks, tools) | 2,224 run, 0 failures; engine line coverage 93% | `docs/test-report/README.md` section 2 |
| Desktop client, scripted end-to-end run (13 flows) | 41 of 41 checks passed | `docs/test-report/desktop-e2e.md` |
| Android client, scripted end-to-end run (16 flows) | 58 of 58 checks passed | `docs/test-report/android-e2e.md` |
| Automated scenarios (39) with ISO/IEC 30107-3 metrics | shipped defaults: worst species APCER 25% (adaptive replay), all attacks 1.7%, genuine rejected 29.4% per attempt. Before the fixes of 2026-09-30 (passive first everywhere): 100% / 17.6% / 58.2% | `docs/eval/calibration-notes.md` section 0 |
| Per-frame processing, 640x480 | median 21-23 ms, 95th percentile 38-45 ms (desktop JVM); target 20 ms not met | `docs/test-report/README.md` section 5 |
| PS04 requirements | every bullet traced to code and test; open items listed | `docs/traceability.md` |

**Limits we state openly:** attacks in the automated suite are synthetic and the genuine data is three people from
open clips, so these are regression results, not an ISO/IEC 30107-3 evaluation (the volunteer evaluation is planned,
`docs/eval/protocol.md`). Because synthetic prints passed the passive check, registration, sign-in and onboarding
now always ask for challenges; this costs about 3 s per capture (median 9.7 s). An adaptive replay that plays the
right clip for each prompt still passes 4 of 16 attempts. Android was tested on an emulator with replayed frames,
built-in camera only; no physical tablet or L1 device yet.

## 5. Next steps

1. Run the in-house volunteer evaluation (12 people, 8 attack types, both clients).
2. Android: vendor SBI face devices and L1; a physical low-end tablet, with the selectable XNNPACK / NNAPI
   accelerators measured there.
3. Device-side PAD for L1 devices, against adaptive replays.
4. Offline import of signed model updates on Android (built on desktop).
5. Upstream contribution as reviewable pull requests.

## 6. Links

| Item | Location |
|---|---|
| Source code | this repository; start at `README.md` |
| Install and run | `docs/install/desktop.md`, `docs/install/android.md` |
| Test data (demo users, frame packs) | `docs/test-data.md` |
| Screens, both clients | `docs/ux/README.md` |
| Technologies considered | `docs/design/technology-evaluation.md` |
| Demo video | _link to be added after recording_ |
