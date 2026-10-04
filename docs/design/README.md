# Technical design (PS04 Deliverable 1)

Design of face liveness and presentation attack detection (PAD) in the MOSIP desktop and Android Registration
Clients. Every page describes the code as built in this repository and names the classes that implement it. Where
the code takes a different route from an earlier design choice, the page says so.

## Map from the PS04 deliverable list to the pages

| PS04 Deliverable 1 bullet | Page |
|---|---|
| High-level architecture for integrating L0/L1 biometric devices with the Registration Client | [architecture.md](architecture.md) |
| Face frame streaming architecture | [streaming.md](streaming.md) |
| Liveness and PAD processing architecture | [engine.md](engine.md) |
| Passive-to-active liveness decision flow | [decision-flow.md](decision-flow.md) |
| Dynamic challenge mechanism | [challenges.md](challenges.md) |
| Resident registration integration flow | [integration-flows.md](integration-flows.md) section 2, [sequences.md](sequences.md) 1 and 4 |
| Operator authentication integration flow | [integration-flows.md](integration-flows.md) section 3, [sequences.md](sequences.md) 2 and 5 |
| Supervisor authentication integration flow | [integration-flows.md](integration-flows.md) section 4, [sequences.md](sequences.md) 3 and 5 |
| Online and offline architecture | [online-offline.md](online-offline.md) |
| Configuration design | [configuration.md](configuration.md) |
| Device integration interface | [device-interface.md](device-interface.md) |
| Liveness engine interface | [engine-interface.md](engine-interface.md) |
| Error handling and recovery flow | [errors.md](errors.md) |
| Sequence diagrams | [sequences.md](sequences.md) |
| Security and privacy considerations | [security-privacy.md](security-privacy.md) |
| ISO/IEC 30107 alignment considerations | [iso-30107.md](iso-30107.md) |
| (Expected task E5) Evaluation of liveness and PAD technologies | [technology-evaluation.md](technology-evaluation.md) |

Also here:
- [audit-and-evidence.md](audit-and-evidence.md): audit events `REG-EVT-LV01..17`, packet evidence keys,
  diagnostic log, metrics.
- Per-client detailed designs written with the code:
  - [desktop-liveness-services.md](desktop-liveness-services.md): desktop service layer (orchestrator, run,
    gates, tokens, SBI stream, audit sink, packet evidence).
  - [desktop-liveness-ui.md](desktop-liveness-ui.md): desktop screens (liveness panel, dialog, the six capture
    points).
  - [android-liveness.md](android-liveness.md): Android `livenessmanager` module and the ARC flows.
- Engine internals and every tunable parameter: `liveness/README.md`, `liveness/liveness-onnx/README.md`,
  `liveness/face-match-sdk/README.md`.

## Related deliverables

| Topic | Where |
|---|---|
| Architecture decisions (why) | [../adr/README.md](../adr/README.md) |
| API documentation | [../api/README.md](../api/README.md) |
| UI/UX design (Deliverable 2) | [../ux/README.md](../ux/README.md): [wireframe sheet](../ux/wireframes.svg) (desktop and Android side by side, every state), [user flows](../ux/flows.md) (desktop and Android), [desktop screens](../ux/desktop-liveness.md), [message catalogue](../ux/message-catalogue.csv), screenshots |
| Installation | [../install/README.md](../install/README.md) |
| Test reports | `docs/test-report/desktop-e2e.md`, `docs/test-report/android-e2e.md` |
| PAD evaluation | `docs/eval/protocol.md`, `docs/eval/calibration-notes.md` |
| Licences and SBOM | `licenses/`, `NOTICE`, [../sbom/README.md](../sbom/README.md) |

## Diagrams

All diagrams are Mermaid blocks inside the pages (GitHub renders them). Two are also committed as pictures, for
the README and the slides: [img/architecture.svg](img/architecture.svg) / `.png` (component view) and
[img/decision-flow.svg](img/decision-flow.svg) / `.png` (passive-to-active decision). Render them again after
changing the Mermaid source. To get SVG files, for example for a PDF,
use the Mermaid CLI with a local Chrome:

```bash
npm install @mermaid-js/mermaid-cli@11                     # in a scratch folder; PUPPETEER_SKIP_DOWNLOAD=1 skips the bundled browser
echo '{"executablePath":"C:/Program Files/Google/Chrome/Application/chrome.exe"}' > pup.json
npx mmdc -p pup.json -i <repo>/docs/design/sequences.md -o out/sequences.md   # writes out/sequences-1.svg ... -8.svg
```

Every block in `docs/design/` rendered without errors this way on 2026-09-30 (Mermaid CLI 11).

## Reading order

1. [architecture.md](architecture.md) for the parts and where they live.
2. [decision-flow.md](decision-flow.md) and [challenges.md](challenges.md) for what the user experiences.
3. [integration-flows.md](integration-flows.md) and [sequences.md](sequences.md) for how each screen uses it.
4. [security-privacy.md](security-privacy.md) and [iso-30107.md](iso-30107.md) for what it protects against and
   what is measured.

## Terms used across these pages

| Term | Meaning |
|---|---|
| RC / ARC | Desktop Registration Client (`clients/desktop`) / Android Registration Client (`clients/android`) |
| SBI, MDS | Secure Biometric Interface: MOSIP's local device API; MDS is the device service that offers it |
| L0 / L1 | SBI trust levels: device key in software / in a secure chip (FTM) |
| PAD | Presentation attack detection |
| Bona fide | A genuine, live presentation |
| Session / transaction | One liveness attempt / all attempts (up to `max_retries`) for one person in one workflow |
| Workflow | Where a face is taken: `registration`, `exception_photo`, `onboard_auth`, `login_auth`, `packet_auth`, `exception_auth`, `eod_auth` |
| Pass token | Single-use proof that a session passed, presented with the signed capture |
| BIR `others` | Free key-value map inside each biometric record of a MOSIP packet |
| LVxx | Audit event `REG-EVT-LVxx` |
| MockMDS / MockSync | Mock face device (`devices/mock-mds`) / local stand-in for the MOSIP server (`tools/mock-sync`) |
| Frame pack | A folder of recorded frames played as a camera stream in tests; never committed |

## Status summary

| Area | State | Evidence |
|---|---|---|
| Engine (passive, active, integrity, binding, policy, audit) | Implemented, 264 core tests | `liveness/README.md` |
| ONNX models and signed bundle | Implemented, 93 tests | `liveness/liveness-onnx/README.md` |
| Desktop integration (resident, onboarding, login, packet, reviewer, EOD) | Implemented; 41 of 41 end-to-end checks | `docs/test-report/desktop-e2e.md` |
| Android integration (resident, onboarding, sign-in, packet, approval) | Implemented; 58 of 58 end-to-end checks in the reference run | `docs/test-report/android-e2e.md` |
| Online model update, offline bundle import | Not done | [online-offline.md](online-offline.md) section 3 |
| Live volunteer PAD evaluation | Protocol written, not run | [iso-30107.md](iso-30107.md) section 4 |
| Per-frame latency target (p95 at most 20 ms) | Not met (desktop p95 38-45 ms) | [engine.md](engine.md) section 10 |
