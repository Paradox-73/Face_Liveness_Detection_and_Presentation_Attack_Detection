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
| Unit and component tests (engine, clients, mocks, tools) | 2,619 passed on 2026-10-05, 0 failures: about 1,361 are upstream MOSIP tests, about 1,258 are written by the team; engine line coverage 93% | `docs/test-report/README.md` section 2 |
| Desktop client, scripted end-to-end run (13 flows) | 41 of 41 checks passed (run at the earlier defaults; not repeated at the current ones) | `docs/test-report/desktop-e2e.md` |
| Android client, scripted end-to-end run (16 flows) | 58 of 58 checks passed (same limit) | `docs/test-report/android-e2e.md` |
| Desktop and Android give the same decision (bonus B4) | 19 replayed cases on desktop, 19 in Android unit tests, 6 on an emulator; one ONNX Runtime release (1.20.0) on both clients | `docs/test-report/interoperability.md` |
| Automated scenarios (75, 275 transactions) with ISO/IEC 30107-3 metrics | shipped defaults, per transaction: worst species APCER 50.0% (4/8, adaptive screen replay SYN-A7, registration; 1 of 2 source artefacts accepted); every other species 0.0%; registration genuine rejected 14.3% (4/28, all one synthetic dark clip), sign-in 0.0% (0/12). Legacy configuration (passive first everywhere): APCER_max 100.0% (80/80, SYN-A8) | `docs/eval/results/20261005-shipped/README.md` |
| Per-frame processing, 640x480 | latency run, 1 thread, shared laptop: p50 69.4 ms, p95 162.4 ms; the 2026-09-30 quieter-machine run gave p50 21-23 ms, p95 38-45 ms (desktop JVM); target 20 ms not met | `docs/eval/results/20261005-latency/report.md`, `docs/test-report/README.md` section 5 |
| Android phone measurement (bonus B7, B8) | measurement kit written; no phone run yet, emulator numbers only | `docs/test-report/android-performance.md` |
| PS04 requirements | every bullet traced to code and test; open items listed | `docs/traceability.md` |

**Limits we state openly:** attacks in the automated suite are synthetic and the genuine data is three people from
open clips, so these are regression results, not an ISO/IEC 30107-3 evaluation (the volunteer evaluation is planned,
`docs/eval/protocol.md`; no real person or real artefact has been tested yet). Because synthetic prints passed the
passive check, every workflow that matches a face now always asks for challenges; this costs about 3 s per capture
(median 9.7 s). Every synthetic replay is refused, including an adaptive screen replay that plays the right clip
for each prompt (it passed 4 of 8 transactions until a screen-pattern check was added on 2026-10-05; proven on
software-made screens only). Android was tested on an emulator with replayed
frames, built-in camera only; no physical phone or L1 device yet, and no live webcam run through the full client.

## 5. Next steps

1. Run the in-house volunteer evaluation (12 people, 8 attack types, both clients).
2. Android: vendor SBI face devices and L1; run the measurement kit on a physical phone (CPU, XNNPACK, NNAPI;
   code done, not yet run on a phone).
3. Device-side PAD for L1 devices, against adaptive replays.
4. Offline import of signed model updates: done on desktop and, as code with unit tests, on Android (bonus B6); the
   Flutter button that starts the Android import is not built yet.
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
