# 0001. One repository with the upstream clients and the mock device imported as folders

- Status: Accepted
- Date: 2026-09-28

## Context

PS04 needs changes in three MOSIP code bases at once: the desktop Registration Client (`mosip/registration-client`),
the Android Registration Client (`mosip/android-registration-client`) and the mock device service
(`mosip/mosip-mock-services`, MockMDS). The shared engine, the model bundle, the mock server and the evaluation
tools are new. The team plan (`WINNING_PLAN.md` section 8.1) proposed an umbrella repository plus three forks and
a patch series.

A feature in this project usually touches the engine and both clients in one change (for example the pass-token
binding: core `CaptureBinder`, desktop `LivenessGate`, Android `LivenessRunner`). Judges also need one place to
build and run everything.

## Decision

One Git repository. Each upstream code base is imported unchanged at a pinned commit in its own commit, then
changed in later commits:

| Folder | Upstream | Import commit |
|---|---|---|
| `clients/desktop` | `mosip/registration-client` `master@22fe01f` (MPL-2.0) | `96f4c56` |
| `clients/android` | `mosip/android-registration-client` `release-1.2.x@c4d3ca7` (MIT) | `7f7e094` |
| `devices/mock-mds` | `mosip/mosip-mock-services` `master@3d2174d`, folder `MockMDS/` (MPL-2.0) | `5e97221` |

New code lives in `liveness/`, `models/`, `tools/`, `eval/`, `scripts/` and `docs/`.

Rules that keep the upstream history readable:
- upstream files keep their line endings (`.gitattributes` has `* -text`; many upstream files are CRLF) and style;
- edits to upstream files are surgical; new classes go into new `io.mosip.*` packages;
- `git diff <import commit> -- <folder>` shows exactly what this project changed in each upstream code base.

## Consequences

- One commit can change the engine and both clients together; one CI checkout builds everything.
- Upstream licences stay with their folders (MPL-2.0 for the desktop client and MockMDS, MIT for Android); the new
  code is MIT (`LICENSE`, `NOTICE`).
- Contributing a change back to MOSIP needs a patch extracted per folder (`git format-patch <import commit>..
  -- clients/desktop`). This is a manual step.
- The repository is large: the Android build folder and upstream assets are big, and 25.7 MB of model files are in
  plain Git (no Git LFS).

## Alternatives considered

- **Umbrella repository plus three forks and a patch series** (the plan): closer to how MOSIP would take the change,
  but every cross-cutting change needs coordinated commits in four repositories and pinned submodule updates.
  Rejected for team speed during the hackathon.
- **Git submodules:** same coordination cost, and submodules are easy to check out wrongly.
