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

Status: work in progress.
