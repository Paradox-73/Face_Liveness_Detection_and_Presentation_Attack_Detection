# High-level architecture: L0/L1 face devices to the Registration Clients

This page answers PS04 Deliverable 1, bullet 1: how face devices, the liveness engine and the two MOSIP
Registration Clients fit together. Every box names the real class or folder that implements it.

Terms used on this page:

- **RC / ARC:** the desktop Registration Client (JavaFX, `clients/desktop`) and the Android Registration Client
  (Flutter plus native Java, `clients/android`).
- **SBI:** MOSIP's Secure Biometric Interface, the local HTTP API that a biometric device service (MDS) offers on
  `127.0.0.1`, ports 4501-4600. Calls used here: `MOSIPDISC` (discover), `MOSIPDINFO` (device info), `STREAM`
  (unsigned preview frames as MJPEG) and `RCAPTURE` (one signed face record).
- **L0 / L1:** SBI trust levels. L0: the device key is kept in software. L1: the key is kept in a secure chip
  (FTM, Foundational Trust Module).
- **PAD:** presentation attack detection (photo, screen, replay, mask).
- **ONNX / ORT:** the open neural-network model file format, and ONNX Runtime, the library that runs it.

## 1. Component view

![Component view: face devices, the two Registration Clients, the shared liveness engine, the signed model bundle and the MOSIP server](img/architecture.svg)

PNG version for slides: [img/architecture.png](img/architecture.png). The picture is rendered from the Mermaid
source below with the Mermaid CLI ([README.md](README.md), "Diagrams"); edit the source, then render again.

- The clients call only the engine contract (`liveness-core` api, device, config, audit). The engine, the ONNX
  adapters and the model bundle can be replaced behind `LivenessEngineProvider` without a client change.
- The dotted lines are the only network links. Every liveness decision runs on the registration machine, online or
  offline.
- Both clients can also import a newer signed model bundle without a client upgrade, through the same
  `ModelBundleImporter` in `liveness-onnx` ([desktop-liveness-services.md](desktop-liveness-services.md) section 6.1,
  [android-liveness.md](android-liveness.md) section 14).

```mermaid
flowchart TB
  subgraph DEV["Face devices (L0 / L1)"]
    direction LR
    MDS["SBI face device service<br/>vendor MDS or MockMDS (devices/mock-mds)<br/>127.0.0.1:4501-4600"]
    CAM["Tablet camera (CameraX)<br/>built-in L0 device"]
  end
  subgraph RC["Desktop Registration Client (clients/desktop)"]
    direction TB
    RCU["Screens: LivenessDialog, LivenessPanel<br/>login, onboarding, resident capture,<br/>packet, reviewer and end-of-day authentication"]
    RCS["Services: LivenessOrchestrator, LivenessGate,<br/>SbiFaceCaptureDevice, DesktopAuditSink,<br/>LivenessModelImporter"]
    RCU --> RCS
  end
  subgraph ARC["Android Registration Client (clients/android)"]
    direction TB
    APP["Screens: Flutter face panels,<br/>LivenessCaptureActivity"]
    LM["livenessmanager: LivenessRunner,<br/>BuiltInCameraL0Device, FaceCaptureLivenessGate,<br/>FaceAuthenticator, ArcAuditSink"]
    APP --> LM
  end
  subgraph CORE["Shared engine (liveness/, Java 11, the same jar on both clients)"]
    API["liveness-core contract<br/>LivenessEngine, LivenessSession,<br/>FaceCaptureDevice, ConfigSource, AuditSink"]
    ENG["liveness-core engine<br/>passive PAD, then active challenges;<br/>frame integrity, planarity, capture binding;<br/>PolicyResolver, audit events, metrics;<br/>LivenessSessionDriver (attempt loop, both clients)"]
    ONNX["liveness-onnx<br/>YuNet, Face Mesh, MiniFASNet x2, SFace<br/>ModelRegistry (signature check)"]
    SDK["face-match-sdk<br/>SFace IBioApiV2 (1:1 face match)"]
    API --> ENG --> ONNX
  end
  MODELS[("models/ signed bundle<br/>manifest.json + manifest.jws")]
  MS["MOSIP server, or MockSync (tools/mock-sync)<br/>sends mosip.registration.liveness.* keys<br/>receives packets with LIVENESS_* evidence"]

  MDS -- "STREAM (MJPEG preview)<br/>RCAPTURE (signed face)" --> RCS
  CAM -- "camera frames" --> LM
  RCS --> API
  LM --> API
  RCS --> SDK
  LM --> SDK
  MODELS -- "loaded and verified at start-up" --> ONNX
  MS -. "online sync only" .- RC
  MS -. "online sync only" .- ARC
```

## 2. Layers and what each one owns

| Layer | Code | Owns | Does not do |
|---|---|---|---|
| Device | Vendor SBI service or `devices/mock-mds`; Android camera | Frames and the signed capture | Any liveness decision (the SBI `padCapability` echo is recorded, never trusted alone) |
| Device adapter | `io.mosip.liveness.core.device.FaceCaptureDevice`; desktop `SbiFaceCaptureDevice`; Android `BuiltInCameraL0Device`, `SbiIntentDevice` | Turning a device into frames (`FaceFrameSource`) and a `SignedFaceCapture` | UI, policy |
| Engine contract | `liveness-core` packages `api`, `device`, `config`, `audit`, `ui`, `vision`, `image` | Interfaces and value types shared by both clients and any vendor engine | Algorithms |
| Default engine | `liveness-core` packages `engine`, `stream`, `policy`; `liveness-onnx` | Session state machine, passive PAD, challenges, integrity, planarity, capture binding, policy resolution, model loading | Device I/O, storage, screens |
| Client services | Desktop `service/bio/liveness`; Android `livenessmanager` | Threads, device discovery, configuration source, audit storage, packet evidence, the gates that refuse unverified faces | Detection logic |
| Client screens | Desktop `controller/liveness`; Android `ui/LivenessCaptureActivity` and Flutter `lib/ui/liveness` | Drawing `LivenessUiState`, Retry / Cancel / override buttons | Deciding anything |
| Server | MOSIP server or `tools/mock-sync` | Configuration, user list, device CA certificates, packet intake | Liveness (never needed online) |

The engine contract packages never import the engine implementation packages; `ArchitectureTest` in
`liveness/liveness-core/src/test` fails the build if they do. A vendor engine can therefore replace
`DefaultLivenessEngine` behind `LivenessEngineProvider` without any change in the clients.

## 3. How an L0/L1 device reaches the client

| Device | Transport | Adapter | Trust check on the capture | Status |
|---|---|---|---|---|
| Vendor SBI 0.9.5 or 1.0 face device on Windows | HTTP on 127.0.0.1:4501-4600 | `SbiFaceCaptureDevice` (desktop) | Existing RC check: JWT signature of the capture, certificate chained to a CA synced in the `DEVICE` domain (`MosipDeviceSpecificationHelper`) | SBI 0.9.5: run end to end with MockMDS L0 and L1 (`docs/test-report/desktop-e2e.md`, D9). SBI 1.0: implemented through the RC's own provider, untested (no 1.0 device or mock was run) |
| MockMDS (`devices/mock-mds`) | same | same | same; L1 signs the digital ID with the FTM key renewed to 2035 | Used for every desktop test |
| Tablet camera | in-process CameraX | `BuiltInCameraL0Device` (Android) | Existing ARC check `Biometrics095Service.handleRCaptureResponse`: signature, `DEVICE` trust, time, transaction ID; key in the Android Keystore | Run end to end on the emulator (`docs/test-report/android-e2e.md`) |
| Vendor SBI face app on Android | Android intents | `SbiIntentDevice` (Android) | Existing ARC checks | Wired through `FaceDeviceSelection` and `FaceCaptureCoordinator`; unit tests only (no vendor app run); the app has no preview stream, so `no_stream_policy` applies |

Details of the adapters: [device-interface.md](device-interface.md). The SBI calls as the clients send them:
[../api/sbi-used.md](../api/sbi-used.md).

## 4. Deployment view (one registration centre machine)

```mermaid
flowchart TB
  subgraph PC["Windows registration PC"]
    RCJVM["Registration Client JVM (Zulu 11.0.8 FX)<br/>lib/: liveness-core, liveness-onnx, liveness-onnx-models,<br/>onnxruntime 1.20.0, face-match-sdk"]
    MDSP["SBI device service :4501"]
    DB[("Derby DB: GLOBAL_PARAM (synced config),<br/>AUDIT, USER_BIOMETRIC")]
    RCJVM --- DB
    RCJVM -- loopback HTTP --> MDSP
  end
  subgraph TAB["Android tablet"]
    ARCAPP["ARC app: Flutter + livenessmanager<br/>onnxruntime-android 1.20.0, CameraX 1.4.2,<br/>assets/liveness/models"]
    ADB[("Room DB: global params, audit,<br/>user_biometric")]
    ARCAPP --- ADB
  end
  SRV["MOSIP server (production) / MockSync (this repository)"]
  RCJVM -- "HTTPS sync when online" --> SRV
  ARCAPP -- "HTTPS (HTTP for the debug build) sync when online" --> SRV
```

- Everything that decides liveness runs on the registration machine: models, challenge draw, action detection,
  PAD scoring, binding. The server only supplies configuration and receives packets
  ([online-offline.md](online-offline.md)).
- One engine object serves the whole client process. It loads the five models once, in the background, at start-up
  (desktop `LivenessEngineRegistry`, Android `EngineLoader`).

## 5. Where each PS04 capability lives

| PS04 capability | Class or file |
|---|---|
| Common face capture device interface, adapter pattern | `FaceCaptureDevice`, `FaceFrameSource`, `FrameSink` ([device-interface.md](device-interface.md)) |
| Frame stream handling | `MjpegFrameReader`, `FrameHandOff`, desktop `FaceStreamSession`, Android `CameraXFrameSource` ([streaming.md](streaming.md)) |
| Liveness / PAD engine interface | `LivenessEngine`, `LivenessSession`, `LivenessEngineProvider` ([engine-interface.md](engine-interface.md)) |
| Passive liveness and PAD | `PassivePadAggregator`, `MiniFasPassivePad`, `FrameIntegrityChecker`, `PlanarityChecker` ([engine.md](engine.md)) |
| Passive-to-active decision | `DecisionPolicy` ([decision-flow.md](decision-flow.md)) |
| Dynamic challenges, action detection | `ChallengeScheduler`, `ChallengeObserver`, `ActionLevels` ([challenges.md](challenges.md)) |
| Configurable thresholds and per-workflow policy | `PolicyKey`, `LivenessConfigKeys`, `PolicyResolver` ([configuration.md](configuration.md)) |
| Retry and failure handling | `LivenessTransaction`, `FailureReason`, `OnMaxFailure` ([errors.md](errors.md)) |
| Resident, operator, supervisor integration | [integration-flows.md](integration-flows.md), [sequences.md](sequences.md) |
| Audit, diagnostics, metrics, packet evidence | `AuditEventId`, `LivenessMetrics`, `DiagnosticLog`, `PacketEvidenceKeys` ([audit-and-evidence.md](audit-and-evidence.md)) |
| Model integrity and updates | `ModelRegistry`, `ModelTrustAnchors`, `ModelSigner` ([security-privacy.md](security-privacy.md), [online-offline.md](online-offline.md)) |

## 6. Repository layout behind the architecture

Folders, upstream commits and licences: [README.md, "Repository map"](../../README.md#repository-map). In
`liveness/`, `liveness-core` is the engine contract and default engine (no runtime dependencies, checked against
Android API 28), `liveness-onnx` holds the ONNX Runtime adapters, the signed model loader and the factory
`OnnxLivenessEngines`, and `face-match-sdk` is the SFace `IBioApiV2` SDK. `devices/mock-mds` extends upstream
MockMDS with real frame sources, faults, L1 and PAD echo.

Why one repository and one shared engine: [ADR 0001](../adr/0001-monorepo.md) and
[ADR 0002](../adr/0002-shared-engine.md).
