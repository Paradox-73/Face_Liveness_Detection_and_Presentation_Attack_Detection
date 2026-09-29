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
| `LivenessOrchestrator` | Entry point for the screens. `isEnforced(workflow)`, `start(request, listener)`, `pendingContext(workflow, subject)`, `contextFor(workflow, subject)`, `engineStatus()`. One run at a time. |
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
  - `BLOCK_AND_LOCK` (authentication workflows): `LivenessOutcome.isLockRequired()`; the screen applies the
    existing invalid-login rule.
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
| `policy.<workflow>` | `PASSIVE_THEN_ACTIVE`; `exception_photo`: `OFF` | gate, orchestrator |
| `engine` | `mosip-onnx-default` | engine registry |
| `model.min_version` | `1.0.0` | engine registry |
| `passive.threshold`, `pad.reject_threshold`, `passive.min_frames`, `passive.timeout_ms` | 80 / 20 / 8 / 4000 | session |
| `active.*` (`enable`, `min_challenges`, `challenge_types`, `challenge_timeout_ms`, `min_onset_ms`, `hold_ms`) | see plan section 4.16 | session |
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
  `logs/registration.log` (INFO level, so no logging change is needed).
- **Metrics:** `liveness.sessions`, `liveness.retries`, `liveness.passes`, `liveness.passive_passes`,
  `liveness.active_triggers`, `liveness.challenges_passed`, `liveness.frames_analysed`,
  `liveness.failures.<REASON>` (function counters); `liveness.time.{total,passive,challenge,frame}.avg_ms`,
  `liveness.rate.{retry,failure}` (gauges). No user IDs, RIDs or device serials in names or tags. The client's
  registry adds its usual `machine` common tag to every meter.

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

| Plan ref | File | Change |
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

## 9. Deviations from the plan

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

JUnit 4 in `registration-services/src/test/java/io/mosip/registration/service/bio/liveness/` (72 tests):

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

Run: `mvn -o test -Dtest='io.mosip.registration.service.bio.liveness.*Test'` in `registration-services`.

## 12. End-to-end verification

The services were run on the real client against MockSync and MockMDS with `scripts/demo-desktop.sh` (flows in
`tools/mock-sync/e2e/flows/`). Flow-by-flow results, evidence files and the problems found are in
`docs/test-report/desktop-e2e.md`.
