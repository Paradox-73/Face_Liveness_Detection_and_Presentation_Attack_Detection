# Desktop liveness services (Registration Client, service layer)

This document describes the UI-free face liveness services inside the desktop Registration Client
(`clients/desktop/registration/registration-services`), how they are threaded, where liveness is enforced, and
which configuration they read. The JavaFX screens call these services; the services never touch JavaFX.

The engine itself (sessions, passive PAD, challenges, binding rules) lives in `liveness/liveness-core` and
`liveness/liveness-onnx`. This layer adapts it to the client: device access over SBI, configuration from the
synced global parameters, audits in the client database, packet evidence, metrics and the capture gates.

## 1. Components

All new classes are in `io.mosip.registration.service.bio.liveness` unless stated otherwise.

| Class | Role |
|---|---|
| `LivenessOrchestrator` | Entry point for the screens. `isEnforced(workflow)`, `start(request, listener)`, `pendingContext(workflow, subject)`, `contextFor(workflow, subject)`, `engineStatus()`, `engineInfo()`, `config()` (for the replacement texts). One run at a time. |
| `LivenessRun` | One liveness transaction for one person: the first session and its retries. Owns the `liveness-worker` thread. `retry()`, `cancel()`, `grantOverride(supervisorId)`, `close()`. |
| `LivenessRunListener` | Callbacks to the screen: `onUiState`, `onPreviewFrame`, `onCaptureReady`, `onSessionEnded`. Called on liveness threads. |
| `LivenessRequest`, `LivenessOutcome` | What to verify (workflow, subject reference, optional device) and how a session ended (pass or failure reason, attempts, retry / override / lock rules). |
| `LivenessGate` | The service-layer checks: capture gate in `BioServiceImpl.captureModality`, authentication gate in `AuthenticationServiceImpl.authValidator`. |
| `LivenessTokenStore` | Single-use pass tokens waiting for their signed capture. |
| `LivenessEngineRegistry` | Creates the configured engine once (background preload at start-up), runs a self-test, fails closed with an audit on error. |
| `LivenessConfig` | `ConfigSource` over `ApplicationContext.map()`; typed defaults, never throws on missing or malformed keys. |
| `SbiFaceCaptureDevice` | `FaceCaptureDevice` adapter over any discovered SBI 0.9.5 or 1.0 face device. |
| `FaceStreamSession` | A running SBI stream: the core `MjpegFrameReader` on its own `sbi-stream-reader` thread, with reconnect. |
| `SignedCaptureReader` | Reads the signed rCapture result (ISO record, capture time, echoed challenge, device PAD block) and decodes the ISO face with the client's `FaceDecoder`. |
| `DesktopImageDecoder` | `javax.imageio` decoder: JPEG, PNG (and JPEG 2000 through the plug-in the client ships) to BGR888. |
| `DesktopAuditSink` | Writes `REG-EVT-LV01..17` through `AuditManagerService` on one background thread. |
| `DesktopDiagnosticSink` | Writes `LIVENESS-DIAG` lines to `logs/registration.log` when diagnostics are on. |
| `DiagnosticTap` | Sits in front of the diagnostic sink of each run and keeps the latest record, so the liveness window can show it (`LivenessRun.lastDiagnostic()`). |
| `DiagnosticModeAudit` | Writes `REG-EVT-LV17` when `diagnostic.enable` is found in a new position at the start of a run (section 6). |
| `LivenessModelImporter`, `LivenessModelStore` | Secure offline model update (bonus B6): verify a signed bundle from a folder or `.zip`, store it, switch the engine (section 6.1). |
| `LivenessAccountLock` | `on_max_failure=BLOCK_AND_LOCK` for the authentications inside a session: counts a used-up transaction as a failed login (section 2.3). |
| `LivenessMeterBinder` | Publishes the anonymised liveness counters and timers through the client's Micrometer registry (`logs/metrics.log`). |
| `io.mosip.registration.mdm.dto.LivenessContext` | Workflow, subject and pass token carried on `MDMRequestDto.livenessContext`. |
| `io.mosip.registration.mdm.service.impl.MdmStreamResponse` | Stream body whose `close()` drops the connection instead of reading an endless MJPEG body. |

## 2. Flow

### 2.1 Resident face capture (`registration`)

1. The screen calls `orchestrator.isEnforced(REGISTRATION)`. If `false`, it captures as before with
   `LivenessContext.withoutPass(REGISTRATION, rid)` on the request.
2. `orchestrator.start(LivenessRequest.builder(REGISTRATION, rid).build(), listener)`:
   resolves the policy, writes `REG-EVT-LV15` if liveness keys changed or were clamped, and opens the first
   session on the worker thread.
3. The worker opens the SBI `STREAM` (timeout `liveness.stream.timeout_ms`, 2 s socket read timeout, 3 s
   first-frame deadline). Frames flow to the session; UI states and preview frames flow to the listener.
4. When the session issues its pass token, the run registers it in `LivenessTokenStore` and calls
   `listener.onCaptureReady(context)`. The resident stream stays open, so the preview keeps running during
   "Hold still".
5. The screen sets `mdmRequestDto.setLivenessContext(context)` and runs its existing capture task, which calls
   `BioServiceImpl.captureModality`.
6. The capture gate consumes the token, the device captures, the gate decodes the signed ISO face and binds it to
   the session. On a pass, the face `BiometricsDto` carries `livenessEvidence` and `livenessWorkflow`, and the
   session ends with `onSessionEnded(passed)`.
7. `BIRBuilder` copies the evidence into the BIR `others` map; `PacketHandlerServiceImpl` copies decision and
   method into the packet `meta_info`.

### 2.2 Operator and supervisor authentication (`login_auth`, `packet_auth`, `exception_auth`, `eod_auth`, `onboard_auth`)

Same as 2.1, with two differences:

- At capture ready the run **closes the stream and polls the device every 100 ms until it reports Ready**
  (at most 1,500 ms, else the session fails with a device error). SBI devices are Busy while they stream, and
  `captureModalityForAuth` refuses a device that is not Ready.
- After the capture gate, `authValidator` refuses a face sample without a liveness pass for an enforced
  authentication workflow, before the 1:1 match.

Login (`SessionContext.validateFace`) builds its own `MDMRequestDto`. It asks
`orchestrator.contextFor(LOGIN_AUTH, userId)`: the pass of the running login session, or a context without a
pass that the gate accepts only when the `login_auth` policy is `OFF`. The screen therefore does not need to pass
the context through the static login code.

### 2.3 Retries, overrides and locking

- `LivenessTransaction` (core) counts attempts. PAD, integrity, active, timeout, below-threshold and binding
  failures use an attempt; device, engine, quality and cancellation failures do not.
- `LivenessOutcome.isRetryAllowed()` says whether `run.retry()` may be called.
- After the last attempt `REG-EVT-LV11` is written and `on_max_failure` applies:
  - `SUPERVISOR_OVERRIDE` (resident workflows only, never after a suspected attack):
    `run.grantOverride(supervisorId)` returns a single-use context (valid 60 s). The capture is stored with
    `LIVENESS_DECISION=OVERRIDE` and `REG-EVT-LV16`.
  - `BLOCK_AND_LOCK` (authentication workflows): `LivenessOutcome.isLockRequired()`; the used-up transaction
    counts as one failed login under the client's rule `mosip.registration.invalid_login_count` /
    `invalid_login_time`. The login screen calls its own `validateInvalidLogin`; the authentications inside a
    session (`packet_auth`, `exception_auth`, `eod_auth`) call `LivenessAccountLock.apply(workflow, userId)` from
    `BaseController.authenticateFaceWithLiveness`, which goes through the same `LoginService.validateInvalidLogin`.
    When the count is reached the alert says the account is locked for the lock time, and the next login of that
    user is refused until it has passed. Residents have no account: `BLOCK_AND_LOCK` behaves as `BLOCK` for them
    (the core resolver does not allow it for resident workflows).
  - `BLOCK` (default): no override, no lock; the window shows the recovery message and Close.
- The three behaviours are tested end to end on the services in `LivenessOnMaxFailureTest` (orchestrator, run,
  token store and the real capture gate): `BLOCK` offers neither override nor lock; `SUPERVISOR_OVERRIDE` is only
  offered after the last resident attempt, never in authentication workflows, and the capture is stored with
  `LIVENESS_DECISION=OVERRIDE` plus `REG-EVT-LV16`; `BLOCK_AND_LOCK` asks for the lock after the last attempt
  only.
- A used-up transaction is remembered per workflow and person (`ExhaustedTransactions`, in memory): a new run for
  the same registration ID or user ID is refused with `REG-LIV-014` for `exhaustedBlockMinutes` (residents: until
  the registration is abandoned; operators: the client's invalid-login lock time). Without this, closing the
  liveness window and opening it again gave a fresh set of attempts (found in the end-to-end run).

## 3. Threading

| Thread | Owner | Work |
|---|---|---|
| `liveness-worker` | one per `LivenessRun` (single-thread scheduled executor) | Owns the session: opens the stream, moves the newest frame from `FrameHandOff.latestOnly()` into the session, calls `tick()` at least every 100 ms, runs the bind call the gate submits. No locks in the session. |
| `sbi-stream-reader` | one per `FaceStreamSession` | Runs `MjpegFrameReader`; writes frames into the hand-off (newest only, never queued) and a private copy to `onPreviewFrame`. |
| capture thread | the screen's existing JavaFX `Service` task | Calls `captureModality`; the gate forwards bind to the worker and waits at most 10 s. |
| `liveness-audit` | `DesktopAuditSink` | Writes audit rows in order; a slow or failing database never delays frame analysis. |
| `liveness-engine-loader` | `LivenessEngineRegistry` | Loads and self-tests the models once at start-up (about 1.4 s on the development laptop). |

Listener callbacks run on `liveness-worker` (UI states, capture ready, session end) or `sbi-stream-reader`
(preview). The screen must hand work to the FX thread (`Platform.runLater`) and must not block in a callback.
`cancel()` and `close()` are safe from any thread.

Stream end, stall, disconnect and invalid frames each end the stream with exactly one terminal callback; there is
no busy loop. A disconnect or stall is retried `device.reconnect_attempts` times (default 1) with one continuous
frame sequence.

## 4. Where liveness is enforced

Liveness cannot be skipped by a screen, because the checks sit in the two service methods every face capture and
every face authentication goes through.

### 4.1 Capture gate (`BioServiceImpl.captureModality`)

Every face capture of the client passes through this method (resident, exception photo, onboarding, login,
packet / reviewer / EOD authentication via `captureModalityForAuth`).

| Case | Result |
|---|---|
| Modality is not face | Not checked. |
| Workflow policy `OFF` or master switch off | Capture proceeds; `livenessWorkflow` is recorded. |
| Workflow `exception_photo` but the request lists no biometric exception | Refused, `REG-LIV-013` (the exception path cannot stand in for the resident's face). |
| Enforced, request has no `LivenessContext` | Refused before the device is called, `REG-LIV-001`. |
| Enforced, context without token | `REG-LIV-002`. |
| Token for another subject / device | `REG-LIV-006` / `REG-LIV-007`. |
| Token unknown, used, expired | `REG-LIV-003` / `REG-LIV-004` / `REG-LIV-005`. |
| Signed capture unreadable | `REG-LIV-012`; the session fails with a device error. |
| Binding fails (face, time window, challenge echo) / PAD on the captured image fails | `REG-LIV-008` / `REG-LIV-009`. |
| Bind did not answer in time | `REG-LIV-018`. |

The workflow comes from the context. A request without a context is checked as `registration`, or as
`exception_photo` when it lists exceptions. The token is consumed **before** the device call, so a failed or
repeated capture cannot reuse it. Every refusal is logged with its code and audited as `REG-EVT-LV09` with a
reason code; the screen only shows the generic liveness message.

### 4.2 Authentication gate (`AuthenticationServiceImpl.authValidator`)

For modality `FACE`, each sample must carry `livenessEvidence.LIVENESS_DECISION=PASS` from an authentication
workflow whose policy is not `OFF`. A sample without a workflow, or with a resident workflow, is rejected while
any authentication workflow is enforced, so a resident pass cannot authenticate an operator. Rejection returns
`false` (the existing "authentication failed" path) and logs `REG-LIV-015`.

### 4.3 Pass tokens

- Issued by the session at capture ready: session ID, workflow, subject, device serial, nonce (at least 16
  characters), `issuedAt`, `expiresAt = issuedAt + binding.max_gap_ms + capture timeout`.
- Single use: the first presentation marks it used; later ones are refused as reused.
- A token that differs from the issued one in any field is refused as unknown.
- The run withdraws the token when the session ends or is cancelled before its capture.
- At most 64 entries are kept; used and expired entries are kept 60 s after expiry so a replay is reported as
  reused, then purged.

## 5. Configuration

`LivenessConfig` reads `ApplicationContext.map()` at each session start and each gate check, so a config sync
applies to the next capture, online or offline (the last synced values in `REG.GLOBAL_PARAM`). Missing, blank or
malformed values never throw: the core `PolicyResolver` applies the shipped defaults, clamps values to their
floors and ranges, and reports each correction; the orchestrator audits corrections and changes as
`REG-EVT-LV15`.

| Key (prefix `mosip.registration.liveness.`) | Default | Read by |
|---|---|---|
| `enable` | `Y` | gate, orchestrator, engine preload |
| `policy.<workflow>` | core `LivenessConfigKeys.defaultPolicyMode(workflow)`: `ALWAYS_ACTIVE` for `registration`, `onboard_auth`, `login_auth`; `PASSIVE_THEN_ACTIVE` for the other authentication workflows; `exception_photo`: `OFF` | gate, orchestrator |
| `engine` | `mosip-onnx-default` | engine registry |
| `engine.execution_provider` | `CPU` | not read on desktop: `LivenessEngineRegistry` builds the engine with `OnnxLivenessEngines.builder` and the default runtime options, and the Windows x64 ONNX Runtime 1.20.0 package offers only the CPU provider ([calibration notes](../eval/calibration-notes.md) 0.4) |
| `model.min_version` | `1.0.0` | engine registry, model import |
| `model.store_dir` (desktop only) | `liveness-models` in the client's working folder | engine registry, model import |
| `guidance.<messageKey>.<lang>` | unset (bundle text) | liveness window (`LivenessTexts.withOverrides`) |
| `passive.threshold`, `pad.reject_threshold`, `passive.min_frames`, `passive.timeout_ms` | 80 / 20 / 30 / 4000 | session |
| `active.*` (`enable`, `min_challenges`, `challenge_types`, `challenge_timeout_ms`, `min_onset_ms`, `hold_ms`) | see [configuration.md](configuration.md) section 5 | session |
| `max_retries`, `on_max_failure`, `<workflow>.on_max_failure`, `<workflow>.{passive.threshold, min_challenges, max_retries}` | 3, `BLOCK` | run |
| `stream.timeout_ms` | 60000 | stream request (replaces `capture_time_out` for liveness streams) |
| `device.reconnect_attempts` | 1 | stream session |
| `binding.min_similarity`, `binding.max_gap_ms` | 60, 5000 | session bind, token expiry |
| `diagnostic.enable` | `N` | diagnostic sink |
| `metrics.enable` | `Y` | meter binder (read at start-up) |

JVM system property `mosip.liveness.preload=false` turns off the start-up model preload (set for the unit tests
in `registration-services/pom.xml`); the engine then loads at first use.

Temporary switch-off for testing other features: serve `mosip.registration.liveness.enable=N` from MockSync.

## 6. Audit, diagnostics, metrics

- **Audit:** `AuditEvent.LIVENESS_*` (`REG-EVT-LV01..17`, same IDs as the core `AuditEventId`), component
  `Components.REG_LIVENESS` (`REG-MOD-153`). The description column holds the core JSON payload (workflow,
  session ID, buckets and codes; no images, templates, embeddings or raw scores). The new
  `AuditManagerService.audit(..., description)` overload carries it. Because the IDs contain `REG-EVT`, the
  existing audit code records them against the RID during a registration, and they travel in the packet.
- **Engine-level events** (load failure `LV13`, model integrity `LV14`) have no session; they are recorded under
  the `registration` workflow with an empty session ID.
- **Before login** (face login, `login_auth`) there is no session map yet. `AuditManagerSerivceImpl` now checks
  `SessionContext.map()` for null before it looks for a registration; before this fix every liveness audit of a
  face login failed with a NullPointerException and was lost (found in the end-to-end run).
- **Diagnostics:** when `diagnostic.enable=Y`, one `LIVENESS-DIAG` line per session state change in
  `logs/registration.log` (INFO level, so no logging change is needed). The core `DiagnosticRecord` has fields
  for state, PAD score, flags, planarity, binding similarity, frame rate and model versions only, so no image,
  template or identifier can reach the log. `DiagnosticTap` keeps the latest record per run for the diagnostic
  lines of the liveness window. Off by default (MockSync default `N`).
- **Diagnostic mode audit (`REG-EVT-LV17`):** `diagnostic.enable` is a synced key, so a switch is noticed at the
  next run start (like `LV15`). `DiagnosticModeAudit` writes `LV17` with the logged-in user ID and the new
  position (`Y`/`N`) when the position differs from the one seen at the previous run; the first run after client
  start writes `LV17` only when diagnostics are on.
- **Metrics:** `liveness.sessions`, `liveness.retries`, `liveness.passes`, `liveness.passive_passes`,
  `liveness.active_triggers`, `liveness.challenges_passed`, `liveness.frames_analysed`,
  `liveness.failures.<REASON>` (function counters); `liveness.time.{total,passive,challenge,frame}.avg_ms`,
  `liveness.rate.{retry,failure}` (gauges). No user IDs, RIDs or device serials in names or tags. The client's
  registry adds its usual `machine` common tag to every meter.

### 6.1 Secure offline model update (bonus B6)

A supervisor can replace the liveness models without a client upgrade, for example from a USB stick:
header menu, "Import liveness models" (shown only to `REGISTRATION_SUPERVISOR` and `REGISTRATION_ADMIN`), then a
folder or `.zip` file that holds `manifest.json`, `manifest.jws` and the model files (the layout of `models/` in
the repository, produced by `ModelSigner update` and `ModelSigner sign`).

`LivenessModelImporter.importBundle(location, userId, roles)` checks, and stops at the first failure:

| Step | Check | Result when it fails | Audit |
|---|---|---|---|
| 1 | user holds a supervisor or administrator role (checked again in the service) | `NOT_ALLOWED` | - |
| 2 | no liveness session is running; the configured engine is `mosip-onnx-default` | `BUSY`, `NOT_SUPPORTED` | - |
| 3 | `.zip`: entry names are relative, no `..`, at most 64 entries and 512 MB | `NOT_FOUND` | - |
| 4 | RS256 JWS signature of `manifest.json` against the built-in trust anchors | `INVALID` | `LV14` |
| 5 | version at least `model.min_version` and **newer** than the running bundle and the store's version floor (anti-rollback) | `TOO_OLD` | `LV14` |
| 6 | size and SHA-256 of every listed model file | `INVALID` | `LV14` |
| 7 | only the listed files are copied into `<model.store_dir>/<bundle>-<version>-<millis>/`, and the copy is verified again (it is what the engine loads) | `INVALID` | `LV14` |
| 8 | `active.properties` is replaced atomically; a new engine is built from the copy and passes the start-up self-test; only then is it swapped in and the old one closed (`LivenessEngineRegistry.switchModels`) | `LOAD_FAILED`: pointer restored, copy deleted, old engine keeps running | `LV13` |
| - | success | `IMPORTED` | `LV15` with `model.bundle=<name>:<old>-><new>` |

The client's audit service adds the logged-in user to every row. The result window shows the running bundle,
the result text in the display language and the models (identifier, version, start of the SHA-256).

**At every engine load** the registry prefers the active imported bundle to the one on the class path, and raises
the minimum version to the store's floor (the highest version ever activated), so a validly signed older bundle
is refused even if the pointer file is edited. A stored bundle that fails its checks makes the engine fail closed
(`LV14`); the client does not fall back to the older bundle of the installation. To go back to the installed
models, an administrator deletes the `liveness-models` folder while the client is stopped.

**Trust:** the store is not trusted: signature, hashes and version are checked again at every load. The trust
anchor is the key built into `liveness-onnx` (`ModelTrustAnchors`); a production deployment replaces it
([security-privacy.md](security-privacy.md) section 5).

## 7. Packet evidence

The gate sets `BiometricsDto.livenessEvidence` from `PacketEvidenceKeys.of(result)`:
`LIVENESS_DECISION` (`PASS` or `OVERRIDE`), `LIVENESS_METHOD`, `LIVENESS_SCORE`, `LIVENESS_CHALLENGES`,
`LIVENESS_ENGINE`, `LIVENESS_MODEL_SHA`, `LIVENESS_SESSION`, `LIVENESS_BINDING` (bucket),
`LIVENESS_DEVICE_LEVEL`, `LIVENESS_DEVICE_PAD` (when the device sent a passing PAD block).

- `BIRBuilder.buildBIR` adds these entries to the BIR `others` map next to the stock entries; they survive CBEFF
  XML (tested). Captures without evidence are unchanged.
- `BiometricsMetaInfoDto` gains `livenessDecision` and `livenessMethod`, written to `meta_info` only for face
  captures that carry evidence (`@JsonInclude(NON_NULL)`).
- Officer and supervisor biometrics keep the same `BiometricsDto`, so `addOfficerBiometrics` /
  `addSupervisorBiometrics` carry the evidence as well.

## 8. Upstream edits (surgical)

| Change ID | File | Change |
|---|---|---|
| D1 | `mdm/integrator/MosipDeviceSpecificationProvider` | default `stream(device, modality, StreamRequest)` overload |
| D1 | `MosipDeviceSpecification_095_ProviderImpl`, `MosipDeviceSpecification_SBI_1_0_ProviderImpl` | implement it with the liveness stream timeout and a socket read timeout; first frame not consumed |
| D1 | `mdm/service/impl/MosipDeviceSpecificationHelper` | `openStream(url, method, body, socketTimeoutMs)` returning `MdmStreamResponse` |
| D3 (partial) | `registration-client/.../controller/device/Streamer.java` | the `read() != 255` loop now ends with `EOFException` at end of stream instead of spinning |
| D7 | `mdm/dto/MDMRequestDto` | nullable `livenessContext` |
| D8 | `service/bio/impl/BioServiceImpl.captureModality` | `livenessGate.admit` / `complete` / `abort` |
| D9 | `dto/packetmanager/BiometricsDto` | `livenessEvidence`, `livenessWorkflow` |
| D10 | `util/common/BIRBuilder` | evidence into `others` |
| D11 | `service/packet/impl/PacketHandlerServiceImpl`, `dto/.../BiometricsMetaInfoDto` | decision and method in `meta_info` |
| D12 | `context/SessionContext.validateFace` | pending login pass on the capture request |
| D14 | `service/security/impl/AuthenticationServiceImpl.authValidator` | `livenessGate.rejectsAuthCapture` |
| D19 | `constants/AuditEvent`, `constants/Components`, `audit/AuditManagerService(+Impl)` | LV01..17, `REG-MOD-153`, description overload; no NullPointerException when auditing before login |
| D20 | `exception/RegistrationExceptionConstants` | `REG-LIV-001..018` |
| D23 | `registration-services/pom.xml` | `liveness-core`, `liveness-onnx`, `liveness-onnx:models`, `onnxruntime:1.20.0`; surefire property |
| D25 | `DeviceDiscoveryMDSResponse`, `SbiDeviceDiscoveryMDSResponse`, `MdmSbiDeviceInfo`, `RCaptureResponseDataDTO` | `@JsonIgnoreProperties(ignoreUnknown = true)` so an unknown `padCapability` or `pad` field does not break discovery or capture |

## 9. Design decisions

The IDs D1-D25 number the desktop edit points (section 8 lists the service-layer ones,
[desktop-liveness-ui.md](desktop-liveness-ui.md) section 3 the screens). For the edit points below the code takes another route:

- **D2:** no `BioService.openFaceStream`. The stream is opened through `SbiFaceCaptureDevice.openStream`, which
  the orchestrator uses; screens never open a liveness stream themselves.
- **D9:** `BiometricsDto` carries one `livenessEvidence` map (the `LIVENESS_*` keys of `PacketEvidenceKeys`) plus
  `livenessWorkflow`, instead of seven separate fields, so the BIR `others` entries and the packet `meta_info`
  are derived from the same map and a new evidence key needs no DTO change.
- **D22:** no liveness defaults in `spring.properties`. `LivenessConfig` and the core `PolicyResolver` supply
  typed defaults for every key, so `getIntValueFromApplicationMap` returning `null` cannot happen on this path.
- **D23:** onnxruntime is not yet repackaged to win-x64 natives only; the 93 MB jar ships as is.
- **D7:** the rCapture request does not yet carry the `bio[].pad` challenge for `padCapability` devices; the
  reader already extracts an echoed challenge and a device PAD block from the signed payload when present.
- **D12 / D13:** the close-stream-and-wait-for-Ready step is done by `LivenessRun` for every authentication
  workflow, not in `SessionContext` or `BaseController`.
- **Engine events** without a session are audited under the `registration` workflow (the core audit payload
  needs a workflow).

**Known limit:** the capture gate trusts the workflow named in the request's `LivenessContext`. A screen that
labels a resident capture with a workflow whose policy is `OFF` would pass the gate; the capture then carries that
workflow in `livenessWorkflow` and no `LIVENESS_*` evidence, so it is visible in the packet but not refused.
Screens must use the workflow of section 10, step 2.

## 10. For the screens (integration checklist)

1. Get `LivenessOrchestrator` from Spring.
2. Choose the workflow: resident `REGISTRATION`, exception photo `EXCEPTION_PHOTO`, onboarding `ONBOARD_AUTH`,
   login `LOGIN_AUTH`, operator packet `PACKET_AUTH`, reviewer `EXCEPTION_AUTH`, EOD `EOD_AUTH`.
3. If not enforced, set `LivenessContext.withoutPass(workflow, subject)` on the request and capture as before.
4. Otherwise `start(...)` with a listener that forwards to the FX thread; draw `LivenessUiState` (message keys are
   the shared `liveness.*` catalogue) and the latest preview frame.
5. On `onCaptureReady(context)`: `mdmRequestDto.setLivenessContext(context)` and run the existing capture task.
   For login, `SessionContext.validateFace` picks the pending pass up itself.
6. On `onSessionEnded(outcome)`: pass, or offer retry / supervisor override / lock according to the outcome.
7. Always `run.close()` when the screen closes; turn auto-logout off while a run is open
   (`SessionContext.setAutoLogout(false)`) and back on at close, as `BiometricsController` does for streaming.

Until the screens do this, every face capture with liveness enforced is refused (`REG-LIV-001`): the gate fails
closed.

## 11. Tests

JUnit 4 in `registration-services/src/test/java/io/mosip/registration/service/bio/liveness/` (106 tests):

| Test | Covers |
|---|---|
| `LivenessConfigTest` | defaults, null or failing map, trimming, master switch, per-workflow policy and overrides, clamping, change detection |
| `LivenessTokenStoreTest` | single use, expiry, altered and unknown tokens, withdrawal, pending lookup, purge |
| `LivenessGateTest` | which captures need a pass, every refusal code, binding with a real ISO/IEC 19794-5 record built by `FaceEncoder`, packet evidence, override, abort, authentication check |
| `BioServiceLivenessGateTest` | `captureModality` refuses a face capture without a pass before the device is called; proceeds when off; PAD failure; token reuse |
| `LivenessOrchestratorTest` | scripted engine and synthetic stream: capture ready, token registration, bind, resident stream kept open, auth stream closed and Ready wait, device never Ready, retries to exhaustion and LV11, busy, cancel, stream and engine failures, config audit, override rules, close |
| `FaceStreamSessionTest` | MJPEG over HTTP from `MockWebServer` through `MosipDeviceSpecificationHelper.openStream`: order, single terminal call, stall via 500 ms socket timeout, disconnect with reconnect, invalid parts, close from another thread, refused stream |
| `DesktopAuditSinkTest` | every core event maps to the same client ID, component, JSON description without raw score, failure isolation, background writer |
| `LivenessPacketEvidenceTest` | BIR `others`, CBEFF XML round trip, `meta_info` fields |
| `DesktopImageDecoderTest` | JPEG, PNG pixel order, rotation, raw frames, bad input |
| `LivenessModelImporterTest` | bundles signed with a key generated by the test: newer bundle stored and switched (`LV15`, floor raised, only listed files copied), same and older version refused, below `model.min_version`, changed model file, unknown signing key, missing signature, `.zip` import, `..` zip entry, empty folder, engine start failure restores the previous bundle (`LV13`), officer refused, vendor engine, pointer outside the store ignored |
| `LivenessOnMaxFailureTest` | `BLOCK`; `SUPERVISOR_OVERRIDE` through the real capture gate (`LIVENESS_DECISION=OVERRIDE`, `LV16`, `LV11` with the behaviour); no override in authentication workflows; `BLOCK_AND_LOCK` after the last attempt only and not for residents; `LV17` once per switch; `DiagnosticTap` |
| `LivenessAccountLockTest` | used-up authentication counted with the configured count and time, lock reported, residents and unknown users untouched, bad config falls back to the client defaults |

Run: `mvn -o test -Dtest='io.mosip.registration.service.bio.liveness.*Test'` in `registration-services`.

## 12. End-to-end verification

The services were run on the real client against MockSync and MockMDS with `scripts/demo-desktop.sh` (flows in
`tools/mock-sync/e2e/flows/`). Flow-by-flow results, evidence files and the problems found are in
`docs/test-report/desktop-e2e.md`.

Diagnostic mode, replacement texts and the model import have their own flow,
`tools/mock-sync/e2e/flows/65-diagnostic-and-models.flow` (after `00-start` and `10-login`, with `MODEL_BUNDLES`
set to a folder holding a `tampered`, a `v1.1.0` and a `v1.0.0-old` bundle signed with the development key). On
2026-09-30 it passed 15 of 15 checks: the replaced "Checking" text and the diagnostic lines on screen, `LV17` in
the audit table, the tampered bundle refused, 1.1.0 imported and running (`LV15`), 1.0.0 refused by the rollback
check (`LV14` rows), and a face login on the imported models. Screenshots:
`docs/ux/screenshots/desktop/e2e-13-diagnostic-mode.png` to `e2e-16-model-import-rollback-refused.png`.
