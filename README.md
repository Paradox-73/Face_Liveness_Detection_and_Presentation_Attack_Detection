# Face liveness and presentation attack detection for MOSIP Registration Clients

Team **Badmosip** — MOSIP Decode 2026, Problem Statement 04:
*Face Liveness Detection and Presentation Attack Detection for Registration and Biometric Authentication.*

This repository adds offline face liveness and presentation attack detection (PAD) to both MOSIP Registration
Clients (desktop JavaFX and Android), for resident face capture, operator authentication and supervisor
authentication.

- **Hybrid check:** passive PAD on the incoming frames first; if the score is below the configured threshold, the
  client automatically prompts randomly chosen actions (blink, smile, turn or look) and verifies them on the
  following frames. Resident registration, face sign-in and onboarding ship with challenges on every capture
  (`ALWAYS_ACTIVE`), because synthetic prints scored above the passive threshold; each workflow's policy is one
  synced setting. Photos, screens, replays, frozen or looped streams and face swaps are refused.
- **One engine, two clients:** a Java 11 library with no runtime dependencies (`liveness/`) runs unchanged on the
  desktop client and on Android, with the same thresholds, prompts, audit events and packet evidence.
- **Device independent:** any SBI 0.9.5 / 1.0 L0 or L1 face device on the desktop; the tablet camera as a built-in
  L0 device on Android; a mock L0/L1 device with fault injection for tests.
- **Bound to the signed capture:** a single-use pass token and a face-similarity check tie the stored or matched
  face to the stream that passed, enforced in the client's service layer, not in the screens.
- **Configurable and offline:** 44 `mosip.registration.liveness.*` keys synced from the server, per-workflow
  policies, floors that cannot be undercut; everything runs without a network.
- **Evidence:** audit events `REG-EVT-LV01..17` and `LIVENESS_*` entries in every face record of the packet.

## Architecture

![Component view: face devices, desktop and Android Registration Clients, shared liveness engine, signed model bundle, MOSIP server](docs/design/img/architecture.svg)

The two clients call one engine contract; every liveness decision runs on the registration machine, and the server
is used only for configuration sync and packet upload. Details: [docs/design/architecture.md](docs/design/architecture.md);
decision flow picture: [docs/design/img/decision-flow.svg](docs/design/img/decision-flow.svg).

## Status (2026-09-30)

| Part | State | Evidence |
|---|---|---|
| Shared engine (`liveness/liveness-core`) | Passive PAD, active challenges, frame integrity, planarity, capture binding, policy resolution, audit, metrics | 272 tests; `liveness/README.md` |
| ONNX adapters and models (`liveness/liveness-onnx`, `models/`) | YuNet, Face Mesh, MiniFASNet V2 + V1SE, SFace; signed bundle with rollback protection | 95 tests; `models/SOURCES.md` |
| Face-match SDK (`liveness/face-match-sdk`) | SFace `IBioApiV2` for FACE 1:1 matching | 42 tests |
| Desktop client (`clients/desktop`) | Liveness gates resident capture, exception photo, onboarding, face login, packet and reviewer authentication, end-of-day approval; supervisor override, account lock, diagnostic mode, replacement texts, offline import of a signed model bundle | 147 liveness unit tests; end-to-end run 41 of 41 checks (`docs/test-report/desktop-e2e.md`) plus the diagnostic and model-import flow, 15 of 15 |
| Android client (`clients/android`) | `livenessmanager` module with the built-in camera device; resident capture, onboarding, face sign-in, packet authentication, supervisor approval; supervisor override, metrics, diagnostic panel, execution provider choice, signed release build | 144 liveness unit tests; end-to-end run 58 of 58 checks: `docs/test-report/android-e2e.md` (before the gap fixes) |
| Mock face device (`devices/mock-mds`) | Image sequences, video or webcam; capture signed from the streamed frame; 13 fault types; L0/L1; provisional PAD capability | 226 tests; `devices/mock-mds/LIVENESS-EXTENSIONS.md` |
| Mock MOSIP server (`tools/mock-sync`) | Both real clients log in, sync, onboard and upload against it; live config changes; packet evidence viewer | 37 tests; `tools/mock-sync/README.md` |
| Evaluation (`tools/eval`, `eval/`) | Scenario runner with ISO/IEC 30107-3 metrics on 39 scenarios; shipped defaults: pooled APCER 1.7% (4/238), BPCER 29.4% (15/51) per attempt, synthetic data | `docs/eval/calibration-notes.md` section 0 |
| Requirements | Every PS04 bullet mapped to code, test and status | `docs/traceability.md` |

Known limits, stated in the linked documents:

- An adaptive screen replay that answers each prompt passed 4 of 16 attempts in the synthetic suite; every other
  synthetic attack species is refused.
- Challenges on every registration and sign-in cost time: the median accepted attempt takes 9.7 s of stream
  instead of 6.6 s.
- The per-frame latency target (p95 at most 20 ms) is not met (desktop p95 38-45 ms on a quieter machine).
- Hardware and people not yet available: no physical Android tablet, no L1 or vendor face device on Android, no
  second device vendor, no volunteer evaluation with real artefacts.
- Model updates: offline signed import on desktop; Android gets new models with a new APK; no download through
  sync.

## Repository map

| Path | What it is | Licence |
|---|---|---|
| `liveness/` | Shared engine (`liveness-core`), ONNX adapters (`liveness-onnx`), SFace SDK (`face-match-sdk`) | MIT |
| `models/` | Signed ONNX model bundle, hashes, sources | per model (MIT, Apache-2.0) |
| `clients/desktop/` | MOSIP desktop Registration Client, upstream `mosip/registration-client` `master@22fe01f`, plus the integration | MPL-2.0 |
| `clients/android/` | MOSIP Android Registration Client, upstream `mosip/android-registration-client` `release-1.2.x@c4d3ca7`, plus `livenessmanager` | MIT |
| `devices/mock-mds/` | MOSIP MockMDS, upstream `mosip/mosip-mock-services` `master@3d2174d` (`MockMDS/`), extended into a mock L0/L1 face device | MPL-2.0 |
| `tools/mock-sync/` | Local stand-in for the MOSIP server | MIT (data MPL-2.0) |
| `tools/eval/`, `eval/` | Scenario runner, analysis, frame-pack manifests and scenarios | MIT |
| `scripts/` | Build, run and demo scripts for both clients and the mocks | MIT |
| `docs/` | Design, API, install, UX, test reports, evaluation, SBOM | MIT |
| `licenses/`, `NOTICE` | Third-party licence texts and attributions | — |

Each upstream folder was imported unchanged in its own commit, so `git diff <import commit> -- <folder>` shows
exactly what this project changed ([ADR 0001](docs/adr/0001-monorepo.md)).

## Quick start

Full clean-machine guide (Windows): [docs/install/README.md](docs/install/README.md).

```bash
mvn -f liveness/pom.xml install -Dliveness.benchmark.frames=0     # engine, models jar, SDK; runs the tests
(cd tools/mock-sync && scripts/run-mock-sync.sh)                  # own terminal; local MOSIP server: HTTPS 8443, HTTP 8080
bash scripts/mockmds-run.sh --build                               # mock face device on 127.0.0.1:4501 (Java 21)
bash scripts/desktop-build.sh --mocksync                          # desktop client run folder in ../run/desktop
bash scripts/android-build.sh                                     # Android debug APK (JDK 21, Flutter 3.10.4)
```

Then: register the machine with MockSync and start the desktop client with `scripts\desktop-run.ps1`
([docs/install/desktop.md](docs/install/desktop.md)); or boot the emulator with `scripts/android-run-emulator.sh`
and run `scripts/android-mocksync-setup.sh --login` ([docs/install/android.md](docs/install/android.md)).
Scripted demos of every flow: `scripts/demo-desktop.sh`, `scripts/demo-android.sh`. Demo logins:
`docs/test-data.md`.

## Documentation

| Topic | Where |
|---|---|
| Technical design (PS04 Deliverable 1): architecture, streaming, engine, decision flow, challenges, integration flows, online/offline, configuration, interfaces, errors, sequence diagrams, security and privacy, ISO/IEC 30107 | [docs/design/README.md](docs/design/README.md) |
| Evaluation of liveness and PAD technologies (why these models) | [docs/design/technology-evaluation.md](docs/design/technology-evaluation.md) |
| Requirement traceability (PS04 bullet → code → test → status) | [docs/traceability.md](docs/traceability.md) |
| Architecture decision records | [docs/adr/README.md](docs/adr/README.md) |
| API documentation: engine, device adapter, desktop services, Android API, face-match SDK, SBI calls, MockMDS admin, MockSync | [docs/api/README.md](docs/api/README.md) |
| UI/UX design (PS04 Deliverable 2): flows, screens, message catalogue | `docs/ux/` (`flows.md`, `desktop-liveness.md`, `message-catalogue.csv`) |
| Install and run | [docs/install/README.md](docs/install/README.md), [desktop.md](docs/install/desktop.md), [android.md](docs/install/android.md) |
| Test reports | [summary](docs/test-report/README.md), [desktop](docs/test-report/desktop-e2e.md), [Android](docs/test-report/android-e2e.md) |
| Pitch material | [deck outline](docs/pitch/deck-outline.md), [summary](docs/pitch/summary.md), [video script](docs/pitch/video-script.md) |
| PAD evaluation protocol and results | [docs/eval/](docs/eval/README.md) |
| Test data and demo accounts | [docs/test-data.md](docs/test-data.md) |
| Licences and SBOM | [licenses/README.md](licenses/README.md), [NOTICE](NOTICE), [docs/sbom/README.md](docs/sbom/README.md) |

## Licence

Code written for this project: MIT ([LICENSE](LICENSE)). Imported MOSIP code keeps its licence (MPL-2.0 for the
desktop client and MockMDS, MIT for the Android client). Models and third-party components: [NOTICE](NOTICE).
