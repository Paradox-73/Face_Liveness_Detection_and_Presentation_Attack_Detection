# Architecture decision records

Each record states one decision: the situation, what was decided, what it costs, and what was rejected. Records
are not rewritten when a decision changes; a new record replaces the old one and the old one is marked
"Superseded".

| No. | Decision | Status | Date |
|---|---|---|---|
| [0001](0001-monorepo.md) | One repository with the upstream clients and the mock device imported as folders | Accepted | 2026-09-28 |
| [0002](0002-shared-engine.md) | One platform-neutral Java engine shared by the desktop and Android clients | Accepted | 2026-09-28 |
| [0003](0003-onnxruntime-1.20.0-pin.md) | Pin ONNX Runtime 1.20.0 on the desktop and on Android (Android 1.28.0 until 2026-10-05) | Accepted | 2026-09-28 |
| [0004](0004-mocksync.md) | Build MockSync, a local stand-in for the MOSIP server | Accepted | 2026-09-28 |
| [0005](0005-sface-face-match-sdk.md) | Ship an SFace-based `IBioApiV2` SDK for FACE matching | Accepted | 2026-09-29 |
| [0006](0006-camerax-built-in-l0-device.md) | Use the Android camera (CameraX) as a built-in SBI L0 device | Accepted | 2026-09-29 |
| [0007](0007-service-layer-gates.md) | Enforce liveness in the service layer, bound to the signed capture | Accepted | 2026-09-29 |

Template:

```markdown
# NNNN. Title
- Status: Proposed | Accepted | Superseded by NNNN
- Date: YYYY-MM-DD
## Context
## Decision
## Consequences
## Alternatives considered
```
