# Audit events, diagnostic log, metrics and packet evidence

PS04 expected task "implement audit and diagnostic logging", good-to-have tasks "anonymised operational metrics",
"liveness performance metrics" and "diagnostic mode", and the PS04 UI rule that detailed PAD information is
available only through diagnostic or audit mechanisms. The same IDs and payloads are used on both clients.

## 1. Audit events

Defined in `io.mosip.liveness.core.audit.AuditEventId`; payload built by `AuditPayloadBuilder`; serialised by
`LivenessAuditEvent.descriptionJson()` into the audit description column. Every payload starts with `workflow` and
`sessionId`; values are at most 256 characters, the whole JSON at most 2,048 (the desktop `LOG_DESC VARCHAR(2048)`).

| ID | Name | Written when | Payload fields besides workflow and sessionId |
|---|---|---|---|
| REG-EVT-LV01 | LIVENESS_SESSION_STARTED | session starts | `attempt`, `deviceSerial`, `certification` (L0/L1), `engine` (`id@version`) |
| REG-EVT-LV02 | LIVENESS_PASSIVE_PASSED | passive pass | `scoreBucket` (10-point band), `frames`, `ms` |
| REG-EVT-LV03 | LIVENESS_ACTIVE_TRIGGERED | escalation to challenges | `scoreBucket`, `reason` (`BELOW_THRESHOLD` / `POLICY`) |
| REG-EVT-LV04 | LIVENESS_CHALLENGE_ISSUED | prompt shown | `type`, `index`, `nonceSha256Prefix` (8 hex characters) |
| REG-EVT-LV05 | LIVENESS_CHALLENGE_PASSED | action verified | `type`, `onsetMs`, `holdMs` |
| REG-EVT-LV06 | LIVENESS_CHALLENGE_FAILED | wrong, early, not held or no action | `type`, `reason` |
| REG-EVT-LV07 | LIVENESS_TIMEOUT | passive or challenge timeout | `phase` (session state) |
| REG-EVT-LV08 | LIVENESS_PAD_ATTACK_SUSPECTED | PAD or integrity failure (not written for a dim-light `LOW_LIGHT` ending) | `reason` (`PAD_SCORE`, `FROZEN`, `LOOP`, `PLANAR`, `FACE_SWITCH`, `DEVICE_PAD`, ...) |
| REG-EVT-LV09 | LIVENESS_BINDING_FAILED | signed capture does not match the session, or a gate refusal | `similarityBucket`, `gapMs`, `challengeEchoOk`, `reason` |
| REG-EVT-LV10 | LIVENESS_PASSED | session passed after binding | `method`, `totalMs` |
| REG-EVT-LV11 | LIVENESS_MAX_RETRIES | last counted attempt failed (once per transaction) | `attempts`, `onMaxFailure` |
| REG-EVT-LV12 | LIVENESS_DEVICE_ERROR | device unavailable, disconnect, invalid stream, Ready not reached, emulator | `errorCode` (`DeviceErrorCode`) |
| REG-EVT-LV13 | LIVENESS_ENGINE_ERROR | load or inference error | `errorCode` (`EngineErrorCode`), `errorClass` |
| REG-EVT-LV14 | LIVENESS_MODEL_INTEGRITY_FAILED | signature, hash or version check failed | `modelId` |
| REG-EVT-LV15 | LIVENESS_CONFIG_APPLIED | liveness keys changed since the last check, or a value was clamped | `changed` (key names) |
| REG-EVT-LV16 | LIVENESS_OVERRIDE_USED | supervisor override of a resident failure; desktop writes one per grant or refusal | `supervisorId`; desktop also `decision` (`GRANTED`, `REFUSED`), `reason`, `userId`, `faces`, `scoreBucket` |
| REG-EVT-LV17 | LIVENESS_DIAGNOSTIC_MODE | diagnostic mode toggled | `userId` |

- **Components:** desktop `REG-MOD-153` (`Components.REG_LIVENESS`), Android `REG-MOD-111` (`Components.LIVENESS`).
- **Who writes them:** the session writes LV01-LV14 through `LivenessListener.onAudit`; clients write LV15 (from
  `PolicyResolution.configAppliedEvent`) and LV16; desktop `DesktopAuditSink` and Android `ArcAuditSink` store them
  on a background thread, so a slow database never delays frame analysis.
- **Travel:** the IDs contain `REG-EVT`, so the desktop audit code records them against the RID during a
  registration and they go into the packet's `audit.json`, as on Android.
- **LV17** is defined on both clients but no screen toggles diagnostic mode at run time; the switch is the synced
  key `diagnostic.enable`, whose change is covered by LV15.
- **Face login before a user session:** the desktop audit writer now tolerates a missing session map; before this
  fix every face-login liveness audit was lost (found in the end-to-end run).

Real example (desktop packet `...232100`, from `docs/test-report/desktop-e2e/packets/`):

```json
{"eventId":"REG-EVT-LV03","eventName":"LIVENESS_ACTIVE_TRIGGERED","sessionUserId":"officer1",
 "description":"{\"workflow\":\"registration\",\"sessionId\":\"f2bfc93f5552bf822eea793b67b9487f\",\"scoreBucket\":\"70-79\",\"reason\":\"BELOW_THRESHOLD\"}"}
{"eventId":"REG-EVT-LV05","eventName":"LIVENESS_CHALLENGE_PASSED","sessionUserId":"officer1",
 "description":"{\"workflow\":\"registration\",\"sessionId\":\"f2bfc93f5552bf822eea793b67b9487f\",\"type\":\"SMILE\",\"onsetMs\":\"1199\",\"holdMs\":\"334\"}"}
```

Client events that existed before this project and still apply: desktop and Android `LOGIN_WITH_FACE`
(REG-AUTH-008) and `REG_SUPERVISOR_AUTH_FACE` (REG-EVT-101). Android adds `REG_OPERATOR_AUTH_FACE` as REG-EVT-115,
because ARC already uses REG-EVT-054 for another event.

## 2. Packet evidence

`io.mosip.liveness.core.api.PacketEvidenceKeys.of(result)` builds the entries that go into the CBEFF BIR `others`
map of every face record that passed liveness (resident, officer, supervisor):

| Key | Values | Example |
|---|---|---|
| `LIVENESS_DECISION` | `PASS`, `OVERRIDE`, `NOT_CHECKED` (Android vendor app with `no_stream_policy=CAPTURE_ONLY`: stored without a liveness check; since 2026-10-10, before it was `PASS`) | `PASS` |
| `LIVENESS_METHOD` | `PASSIVE`, `PASSIVE_ACTIVE`, `ACTIVE`, `NONE` | `PASSIVE_ACTIVE` |
| `LIVENESS_SCORE` | passive score 0-100 | `74` |
| `LIVENESS_CHALLENGES` | type:outcome list | `SMILE:OK,BLINK:OK` |
| `LIVENESS_ENGINE` | `id@version` | `mosip-onnx-default@1.0.0` |
| `LIVENESS_MODEL_SHA` | 8-hex SHA-256 prefix of each model | `ebafce4e,db4b7684,b32929ad,ebab7f90,d2899b3e` |
| `LIVENESS_SESSION` | session ID (random, not derived from the person) | `f2bfc93f5552bf822eea793b67b9487f` |
| `LIVENESS_BINDING` | similarity bucket of capture vs session | `90-100` |
| `LIVENESS_DEVICE_LEVEL` | `L0`, `L1` | `L0` |
| `LIVENESS_DEVICE_PAD` | `PASS` when the device sent a passing PAD block | (absent) |

- `packet_meta_info.json` gets `livenessDecision` and `livenessMethod` for face entries that carry evidence
  (desktop `PacketHandlerServiceImpl`, Android `RegistrationServiceImpl.addLivenessMetaInfo`).
- Captures with policy `OFF` carry no evidence, so a missing `LIVENESS_DECISION` is visible to the server.
- The keys survive the CBEFF XML round trip (desktop `LivenessPacketEvidenceTest`, Android
  `RegistrationServiceImplTest.testBuildBIR_LivenessEvidence`) and were read back from uploaded packets by MockSync
  (`GET /admin/packets/{name}/liveness`) in both end-to-end runs.
- Migration note: when MOSIP adopts the ISO/IEC 39794-5 PAD data block (issue #1117), these entries map to it.

## 3. Diagnostic log

When `mosip.registration.liveness.diagnostic.enable=Y`:
- `DiagnosticLog` writes one `DiagnosticRecord` per session state change: state, PAD score, flags, planarity
  residual, binding similarity, fps, model IDs and versions, optional detail text (at most 256 characters).
- Desktop: `LIVENESS-DIAG` lines in `logs/registration.log` (`DesktopDiagnosticSink`); the panel also shows a
  diagnostic line. Android: Logcat tag `Liveness` (`LogcatSinks`).
- Never subject references, images or embeddings (`ReportingTest`).
- Configuration fallbacks (unparsable values) are always written as diagnostics.

## 4. Metrics

`io.mosip.liveness.core.engine.LivenessMetrics` (when `metrics.enable=Y`), published on the desktop by
`LivenessMeterBinder` into the client's Micrometer registry (`logs/metrics.log`):

| Name | Kind | PS04 metric |
|---|---|---|
| `liveness.sessions`, `liveness.retries`, `liveness.passes`, `liveness.passive_passes`, `liveness.active_triggers`, `liveness.challenges_passed`, `liveness.frames_analysed` | counters | operational counts |
| `liveness.failures.<REASON>` | counter per `FailureReason` | failure breakdown |
| `liveness.time.total.avg_ms` | gauge | average liveness processing time |
| `liveness.time.passive.avg_ms` | gauge | passive decision time |
| `liveness.time.challenge.avg_ms` | gauge | challenge completion time |
| `liveness.time.frame.avg_ms` | gauge | per-frame processing time |
| `liveness.rate.retry`, `liveness.rate.failure` | gauges | retry rate, failure rate |

No user IDs, RIDs or device serials appear in names or tags. Android does not publish `LivenessMetrics` yet: it
logs per-attempt frame times (`session.FrameTimer`, p50 / p95 / max) to Logcat, and user-visible times are in the
LV02 `ms` and LV10 `totalMs` audit fields on both clients.
