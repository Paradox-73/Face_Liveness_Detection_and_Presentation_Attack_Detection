# Sequence diagrams

PS04 Deliverable 1, bullet 14. Every participant is a real class; method names are the ones in the code.
Diagrams are Mermaid and render on GitHub.

Contents:
1. [Desktop: resident face capture](#1-desktop-resident-face-capture-registration)
2. [Desktop: operator face sign-in](#2-desktop-operator-face-sign-in-login_auth)
3. [Desktop: supervisor end-of-day approval](#3-desktop-supervisor-end-of-day-approval-eod_auth)
4. [Android: resident face capture with the built-in camera](#4-android-resident-face-capture-registration)
5. [Android: operator or supervisor face authentication](#5-android-operator-or-supervisor-face-authentication)
6. [Retry, maximum attempts and supervisor override (desktop)](#6-retry-maximum-attempts-and-supervisor-override-desktop)
7. [Configuration change arriving by sync](#7-configuration-change-arriving-by-sync)
8. [Engine start-up and model verification](#8-engine-start-up-and-model-verification)

## 1. Desktop: resident face capture (`registration`)

```mermaid
sequenceDiagram
  autonumber
  actor Op as Operator
  participant GBC as GenericBiometricsController
  participant DLG as LivenessDialog / LivenessPanel
  participant ORC as LivenessOrchestrator
  participant RUN as LivenessRun
  participant DRV as LivenessSessionDriver (core, liveness-worker)
  participant DEV as SbiFaceCaptureDevice
  participant MDS as SBI device (MockMDS :4501)
  participant SES as LivenessSession (core)
  participant BIO as BioServiceImpl.captureModality
  participant GATE as LivenessGate
  Op->>GBC: Scan (face)
  GBC->>GBC: isLivenessEnforced(REGISTRATION)
  GBC->>DLG: openLivenessDialog(REGISTRATION, rid)
  DLG->>ORC: start(LivenessRequest, listener)
  ORC->>ORC: PolicyResolver.resolve(REGISTRATION), LV15 if keys changed
  ORC->>RUN: new run, first session (attempt 1)
  DRV->>SES: engine.open(SessionRequest), start() (LV01)
  DRV->>DEV: openStream(StreamRequest)
  DEV->>MDS: STREAM {deviceId, deviceSubId, timeout = stream.timeout_ms}
  MDS-->>RUN: MJPEG parts (sbi-stream-reader, MjpegFrameReader -> FrameHandOff)
  loop every frame (newest only) and every 100 ms
    DRV->>SES: onFrame(frame), tick()
    SES-->>DLG: onUi(LivenessUiState) via FxLivenessListener
  end
  Note over SES: passive score >= 80: CAPTURE_READY (LV02)<br/>else challenges first (LV03..LV05)
  SES-->>RUN: onStateChanged(CAPTURE_READY), passToken()
  RUN->>RUN: LivenessTokenStore.register(token)
  RUN-->>DLG: onCaptureReady(LivenessContext)
  DLG-->>GBC: captureReady(context)
  GBC->>BIO: captureModality(MDMRequestDto + livenessContext)
  BIO->>GATE: admit(request, device) - token consumed before the device call
  BIO->>MDS: RCAPTURE {bio:[{type:"Face", ...}]}
  MDS-->>BIO: signed ISO/IEC 19794-5 record (JWT verified against DEVICE trust)
  BIO->>GATE: complete(admission, captures)
  GATE->>RUN: bind(BindingRequest) on the worker (max 10 s)
  RUN->>SES: bind(...) -> PASSED (LV10)
  GATE-->>BIO: BiometricsDto + livenessEvidence (LIVENESS_*)
  BIO-->>GBC: face accepted
  DLG-->>GBC: passed()
  GBC->>GBC: BIRBuilder: LIVENESS_* in BIR others, meta_info livenessDecision
```

## 2. Desktop: operator face sign-in (`login_auth`)

```mermaid
sequenceDiagram
  autonumber
  actor Op as Operator
  participant LC as LoginController
  participant DLG as LivenessDialog
  participant RUN as LivenessRun
  participant MDS as SBI device
  participant SC as SessionContext.validateFace
  participant ORC as LivenessOrchestrator
  participant BIO as BioServiceImpl.captureModalityForAuth
  participant AUTH as AuthenticationServiceImpl.authValidator
  participant SDK as BioAPIFactory -> SFaceBioApi
  Op->>LC: user ID, face login pane opens
  LC->>DLG: openLivenessDialog(LOGIN_AUTH, userId)
  DLG->>RUN: start, STREAM, passive (threshold 95) and challenges
  RUN->>RUN: CAPTURE_READY
  RUN->>MDS: close stream
  loop every 100 ms, at most 1,500 ms
    RUN->>MDS: MOSIPDISC until deviceStatus = Ready
  end
  RUN->>RUN: session.captureStarted() - binding window starts
  RUN-->>DLG: onCaptureReady
  DLG-->>LC: captureReady -> executeValidationTask
  LC->>SC: validateFace(userId)
  SC->>ORC: contextFor(LOGIN_AUTH, userId) - pending pass
  SC->>BIO: capture with livenessContext
  BIO->>MDS: RCAPTURE, gate admits, binds (LV10)
  SC->>AUTH: authValidator(FACE samples)
  AUTH->>AUTH: rejectsAuthCapture: needs LIVENESS_DECISION=PASS from an auth workflow
  AUTH->>SDK: match(probe, USER_BIOMETRIC face template)
  SDK-->>AUTH: score (0-100) >= threshold 40 -> MATCHED
  AUTH-->>LC: authenticated -> home screen
```

## 3. Desktop: supervisor end-of-day approval (`eod_auth`)

```mermaid
sequenceDiagram
  autonumber
  actor Sup as Supervisor
  participant EOD as EODAuthenticationController
  participant BC as BaseController.authenticateFaceWithLiveness
  participant DLG as LivenessDialog ("Supervisor verification")
  participant RUN as LivenessRun
  participant CVF as BaseController.captureAndValidateFace
  participant AUTH as AuthenticationServiceImpl
  Sup->>EOD: select packets, Approve, choose face
  EOD->>BC: authenticateFaceWithLiveness(EOD_AUTH, supervisorId, ...)
  BC->>DLG: open, stream, passive (95) then challenges if needed
  alt replayed video or photo
    RUN-->>DLG: FAILED(PAD or TIMEOUT): generic message, "Attempt 1 of 3", Retry
    Sup->>DLG: Retry with own face
  end
  RUN->>RUN: CAPTURE_READY, close stream, wait for Ready
  DLG-->>BC: captureReady(context)
  BC->>CVF: captureAndValidateFace(..., context) - existing executeFaceValidationTask
  CVF->>AUTH: capture (gate binds), authValidator (liveness gate), SFace match
  AUTH-->>EOD: supervisor verified -> packets approved (APPROVED status), audit REG-EVT-LV10
```

## 4. Android: resident face capture (`registration`)

```mermaid
sequenceDiagram
  autonumber
  actor Op as Operator
  participant FL as Flutter biometric screen
  participant BDA as BiometricsDetailsApi (Pigeon host)
  participant GATE as FaceCaptureLivenessGate
  participant ACT as LivenessCaptureActivity
  participant RUN as LivenessRunner
  participant DRV as LivenessSessionDriver (core, liveness-worker)
  participant CAM as CameraXFrameSource
  participant SES as LivenessSession (core)
  participant DEV as BuiltInCameraL0Device
  participant B095 as Biometrics095Service
  participant REG as RegistrationServiceImpl
  Op->>FL: Scan (Face)
  FL->>BDA: invokeDiscoverSbi(field, "Face")
  BDA->>GATE: enforced? attempts left?
  GATE->>ACT: LivenessCaptureLauncher: startActivityForResult(intent, 6)
  ACT->>RUN: start(policy, priorAttempts, priorSuspectedAttack, priorLowLight)
  RUN->>DRV: builder(...).priorFailures(n, attack, lowLight).trackLowLight(true).start()
  DRV->>CAM: start(FrameHandOff.latestOnly())
  loop newest frame, tick every 100 ms
    DRV->>SES: onFrame / tick
    SES-->>ACT: LivenessUiState (main thread)
  end
  SES-->>RUN: CAPTURE_READY, passToken, captureFrame()
  RUN->>DEV: capture(): JPEG 2000 in ISO 19794-5, RS256 JWS with x5c<br/>(session ID, frame SHA-256, nonce in the protected header)
  RUN->>SES: bind(BindingRequest from the decoded signed record) -> PASSED
  ACT-->>BDA: RESULT_OK + rCapture response + LIVENESS_* evidence (MainActivity.onActivityResult)
  BDA->>B095: handleRCaptureResponse (signature, DEVICE trust, time, transaction ID)
  B095-->>BDA: BiometricsDto
  BDA->>BDA: attach evidence to BiometricsDto
  BDA-->>FL: PASSED, Flutter calls getBestBiometrics
  FL->>REG: packet: buildBIR writes LIVENESS_* into BIR others
```

On any other result the Flutter call completes with `FAILED`, `BLOCKED` or `CANCELLED`, Flutter skips
`getBestBiometrics`, and nothing is stored.

## 5. Android: operator or supervisor face authentication

```mermaid
sequenceDiagram
  autonumber
  actor U as Operator / supervisor
  participant P as FaceAuthPanel (Flutter)
  participant API as LivenessApi.authenticateFace (Pigeon)
  participant FLOW as FaceAuthFlow
  participant FA as FaceAuthenticator
  participant ACT as LivenessCaptureActivity
  participant VER as EngineFaceVerifier (SFace)
  U->>P: "Sign in with face" / "Authenticate with face" / supervisor face option
  P->>API: authenticateFace(userId, workflow)
  API->>FLOW: authenticate(userId, workflow)
  FLOW->>FA: precheck: not locked, face enrolled, supervisor role for eod_auth / exception_auth
  alt precheck fails
    FA-->>P: FAILED (USER_LOCKED / FACE_NOT_ENROLLED / NOT_SUPERVISOR), no camera
  end
  FLOW->>ACT: capture screen, same session and binding as resident capture
  ACT-->>FLOW: PASS + signed capture
  FLOW->>VER: match capture face vs user_biometric FACE rows (threshold 40)
  VER-->>FLOW: MATCHED / NOT_MATCHED
  FLOW-->>P: LivenessOutcome {status, errorCode, message, attemptsUsed, maxAttempts}
  alt login_auth passed
    P->>P: AuthResponseApi.loginWithFace(user) consumes the 60 s grant
  else packet_auth passed
    P->>P: Authenticate enabled, officer face kept for officer_bio_cbeff
  else eod_auth passed
    P->>P: selected packets approved
  end
```

## 6. Retry, maximum attempts and supervisor override (desktop)

```mermaid
sequenceDiagram
  autonumber
  participant DLG as LivenessDialog
  participant RUN as LivenessRun
  participant DRV as LivenessSessionDriver (core)
  participant TX as LivenessTransaction (core)
  participant HIS as AttemptHistory
  participant SUP as SupervisorOverrideFlow / SupervisorFaceAuth
  DRV->>TX: record(result FAILED(PAD))
  TX-->>DRV: canRetry = true (1 of 3 used), suspected attack = true
  DRV-->>RUN: onAttemptEnded(AttemptOutcome)
  RUN->>HIS: counted failures, attack mark, low-light count (per workflow and person)
  RUN-->>DLG: "Face verification could not be completed." + Retry
  DLG->>RUN: retry()
  RUN->>DRV: retry(), attempt 2, new session
  Note over RUN,TX: ... third counted failure ...
  DRV->>TX: record(result FAILED(TIMEOUT))
  TX-->>DRV: canRetry = false; override allowed only if resident, SUPERVISOR_OVERRIDE and no PAD, INTEGRITY or BINDING failure so far
  DRV->>DRV: audit LV11 {attempts, onMaxFailure}
  RUN->>HIS: used up: reopening gives no new attempts (REG-LIV-014)
  RUN-->>DLG: max-attempts message, "Supervisor override" if allowed, else Close
  DLG->>RUN: requestOverride() (refused after a suspected attack)
  DLG->>SUP: another supervisor's user ID, face authentication
  SUP-->>RUN: OverrideRequest.grant(SupervisorApproval): role, second person, at most 60 s old
  RUN-->>DLG: single-use context valid 60 s from the supervisor's match, audit LV16 {decision, supervisorId}
  Note over DLG: photo checked (one face, passive PAD), then stored with LIVENESS_DECISION=OVERRIDE
```

For authentication workflows `on_max_failure=BLOCK_AND_LOCK` makes `LivenessOutcome.isLockRequired()` true and the
screen counts a failed sign-in (`validateInvalidLogin`).

## 7. Configuration change arriving by sync

```mermaid
sequenceDiagram
  autonumber
  actor Admin
  participant MS as MOSIP server / MockSync
  participant CL as Client config sync (desktop GlobalParamServiceImpl, ARC MasterDataServiceImpl)
  participant DB as GLOBAL_PARAM (local DB)
  participant CFG as ConfigSource (LivenessConfig / GlobalParamConfigSource)
  participant PR as PolicyResolver
  Admin->>MS: PUT /admin/config {"mosip.registration.liveness.policy.registration":"ALWAYS_ACTIVE"}
  CL->>MS: GET /v1/syncdata/configs/{keyIndex} (signed, encrypted to the machine key)
  MS-->>CL: globalConfiguration / registrationConfiguration
  CL->>DB: store keys
  Note over CFG,PR: next face capture (online or offline)
  CFG->>DB: read mosip.registration.liveness.*
  PR->>PR: resolve(workflow): defaults, per-workflow keys, floors, ranges
  PR-->>CFG: LivenessPolicy + issues + changed keys
  CFG->>CFG: audit LV15 {"changed":"mosip.registration.liveness.policy.registration"}
```

Seen on both clients: desktop run D8, Android run A12.

## 8. Engine start-up and model verification

```mermaid
sequenceDiagram
  autonumber
  participant APP as Client start-up (LivenessEngineRegistry / EngineLoader)
  participant F as OnnxLivenessEngines.builder
  participant REG as ModelRegistry
  participant SRC as ModelSource (class path jar / APK assets)
  participant ORT as ONNX Runtime
  APP->>F: builder(source).minimumVersion(model.min_version).imageDecoder(d)
  F->>REG: open()
  REG->>SRC: read manifest.json, manifest.jws
  REG->>REG: verify RS256 signature with the trust anchor (ModelTrustAnchors)
  REG->>REG: bundle version >= model.min_version (blocks rollback)
  loop 5 models
    REG->>SRC: read file bytes
    REG->>REG: SHA-256 equals manifest entry
    REG->>ORT: create session from the verified bytes
  end
  alt any check fails
    REG-->>APP: EngineException MODEL_INTEGRITY / MODEL_VERSION / MODEL_MISSING
    APP->>APP: engine unavailable (fail closed), audit LV14, screens show "Face verification is unavailable on this machine."
  else
    F-->>APP: OnnxLivenessEngine ready (self-test on desktop)
  end
```
