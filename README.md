# Face liveness and presentation attack detection for MOSIP Registration Clients

Team **Badmosip** — MOSIP Decode 2026, Problem Statement 04:
*Face Liveness Detection and Presentation Attack Detection for Registration and Biometric Authentication.*

This repository adds offline face liveness and presentation attack detection (PAD) to both MOSIP
registration clients, for resident face capture, operator authentication and supervisor authentication.

## Repository map

| Path | What it is | Licence |
|---|---|---|
| `liveness/` | Shared liveness engine used by both clients (Java 11 library) | MIT |
| `clients/desktop/` | MOSIP desktop Registration Client (JavaFX), upstream `mosip/registration-client` `master@22fe01f`, plus our changes | MPL-2.0 |
| `clients/android/` | MOSIP Android Registration Client (Flutter + Java), upstream `mosip/android-registration-client` `release-1.2.x@c4d3ca7`, plus our changes | MIT |
| `devices/mock-mds/` | MOSIP mock biometric device service, upstream `mosip/mosip-mock-services` `master@3d2174d` (`MockMDS/`), extended to stream real frames | MPL-2.0 |
| `tools/` | Mock MOSIP server, model signer, evaluation scripts | MIT |
| `models/` | Model files, hashes and licences | see `models/SOURCES.md` |
| `docs/` | Design, API, UX, evaluation and install documentation | MIT |

Each upstream directory was imported unchanged in its own commit, so `git log -- <path>` and
`git diff <import-commit> -- <path>` show exactly what this project changed.

## Status (Sep 29, 2026)

| Part | State |
|---|---|
| Shared liveness engine (`liveness/`) | Passive check, active challenges, frame-integrity and planarity checks, capture binding, policy/config, audit; 343 tests pass |
| ONNX models (`models/`) | YuNet, Face Mesh, MiniFASNet V2 + V1SE, SFace; SHA-256 manifest with signature |
| Mock face device (`devices/mock-mds/`) | Streams image sequences, video or webcam; capture built from the streamed frame; fault injection |
| Mock MOSIP server (`tools/mock-sync/`) | Desktop client completes first login and initial sync against it |
| Desktop client | Builds and launches; liveness integration in progress |
| Android client | Debug APK builds; runs on the emulator; liveness integration in progress |

## Quick start

- Build and test the engine: `mvn -f liveness/pom.xml verify`
- Desktop client: see `docs/install/desktop.md`
- Android client: see `docs/install/android.md`
- Mock MOSIP server: see `tools/mock-sync/README.md`
- Mock face device: see `devices/mock-mds/LIVENESS-EXTENSIONS.md`
