# Resident, operator and supervisor integration flows

PS04 Deliverable 1, bullets 6-8 (resident registration, operator authentication, supervisor authentication) and
mandatory tasks 5-7. This page lists every face capture point of both clients, the workflow ID it uses, and
what happens on pass and failure. The step-by-step sequence diagrams are in [sequences.md](sequences.md).

## 1. Workflows

`io.mosip.liveness.core.Workflow` names the seven places where a face is taken. Five reuse MOSIP process names.

| Workflow ID | Who | Face is | Default policy | Default passive threshold |
|---|---|---|---|---|
| `registration` | Resident | stored in the packet | `ALWAYS_ACTIVE` | 80 |
| `exception_photo` | Resident with a biometric exception | stored as the exception photo | `OFF` | (80) |
| `onboard_auth` | Operator or supervisor at onboarding | stored as the user's reference template | `ALWAYS_ACTIVE` | 95 |
| `login_auth` | Operator or supervisor at sign-in | matched 1:1, not stored | `ALWAYS_ACTIVE` | 95 |
| `packet_auth` | Operator confirming a packet | matched 1:1, stored as officer biometrics | `ALWAYS_ACTIVE` | 95 |
| `exception_auth` | Supervisor reviewing an exception registration | matched 1:1, stored as supervisor biometrics | `ALWAYS_ACTIVE` | 95 |
| `eod_auth` | Supervisor approving packets at end of day | matched 1:1 | `ALWAYS_ACTIVE` | 95 |

`onboard_auth` matters for security: a spoofed onboarding face would become the reference that later face
authentications are matched against.

## 2. Resident face capture (`registration`)

PS04 steps 1-10 map to the code as follows.

| PS04 step | Desktop | Android |
|---|---|---|
| 1. Start face capture | `GenericBiometricsController.scan` opens `LivenessDialog` when `isLivenessEnforced(REGISTRATION)` | Flutter `BiometricsApi.invokeDiscoverSbi(field, "Face")` reaches `BiometricsDetailsApi`, which asks `FaceCaptureLivenessGate` and starts `LivenessCaptureActivity` (request code 6) |
| 2. Receive frames from the L0/L1 device | `LivenessRun` opens SBI `STREAM` through `SbiFaceCaptureDevice` | `LivenessRunner` starts `CameraXFrameSource` (built-in L0 device) |
| 3-4. Passive liveness and PAD, evaluate the score | `DefaultLivenessSession` on the `liveness-worker` thread, driven by the core `LivenessSessionDriver` | same class, same driver, same thread model |
| 5. Accept when the threshold is met | pass token, then the existing capture task; `LivenessGate` binds the signed capture | the built-in device signs the frame the session selected; `session.bind(...)` |
| 6-8. Otherwise start a challenge, prompt, analyse frames | automatic; prompts drawn by `ChallengeScheduler` | same |
| 9. Accept only after verification | `BioServiceImpl.captureModality` refuses a face without a valid, unused, bound pass (`REG-LIV-001..009`) | only `RESULT_OK` with a signed rCapture response continues to ARC's unchanged `Biometrics095Service.handleRCaptureResponse` checks |
| 10. Retry within the configured limit | Retry button while `LivenessOutcome.isRetryAllowed()`; after the last attempt `on_max_failure` | Retry inside the capture screen; attempts counted across screens by `FaceCaptureLivenessGate` |

**Evidence stored:** `LIVENESS_*` keys in the face BIR `others` map (`BIRBuilder` on desktop,
`RegistrationServiceImpl.buildBIR` on Android) and `livenessDecision` / `livenessMethod` in `packet_meta_info.json`
([audit-and-evidence.md](audit-and-evidence.md)).

**After the maximum attempts** (`mosip.registration.liveness.registration.on_max_failure`):
- `BLOCK` (shipped default): no face can be stored for this registration; the screen shows "Face capture could
  not be completed after 3 attempts. Please ask your supervisor to follow the centre's recovery process."
- `SUPERVISOR_OVERRIDE` (MockSync demo profile): desktop shows a "Supervisor override" button; it asks for another
  supervisor's user ID and authenticates that person by face (valid 60 s), then checks the resident photo (one face,
  passive PAD). It is never offered after a PAD, integrity or binding failure, in this or an earlier window. The capture is stored with `LIVENESS_DECISION=OVERRIDE` and
  audit LV16. Android treats it as `BLOCK`, because the built-in camera keeps no face after a failure (deviation,
  `docs/design/android-liveness.md` section 11.5).
- The block survives closing and reopening the window: desktop `AttemptHistory`, Android
  `FaceCaptureLivenessGate` keep the counted attempts, the attack mark and the low-light count per registration.

**Exception photo** (`exception_photo`, policy `OFF`): captured without liveness, and the capture gate refuses an
exception-photo request that lists no biometric exception (`REG-LIV-013`), so this path cannot replace the
resident's face.

## 3. Operator authentication

| Point | Workflow | Desktop | Android |
|---|---|---|---|
| Sign-in with face | `login_auth` | `LoginController`: liveness starts when the face pane opens; `SessionContext.validateFace` picks up the pending pass, captures, then 1:1 match | "Sign in with face" on the password step (`FaceAuthPanel`, `LivenessApi.authenticateFace`), then `AuthResponseApi.loginWithFace` with a one-time grant (60 s) |
| Packet authentication | `packet_auth` | `AuthenticationController.validateFace` through `BaseController.authenticateFace` | "Authenticate with face" on the Authentication step; the officer's signed face is kept for the packet (`officer_bio_cbeff`) |
| Onboarding face | `onboard_auth` | `BiometricsController.scan` / `rCapture` | `FaceCaptureLivenessGate` for the onboarding field |

Order of checks in every face authentication:
1. **Pre-checks** (Android `FaceAuthenticator.precheck`): account not locked, face enrolled, supervisor role where
   needed. No camera opens when one fails.
2. **Liveness** session, with the device closed and polled until `Ready` before the capture on SBI devices.
3. **Binding** of the signed capture to the session (capture gate).
4. **Liveness gate before the match:** desktop `AuthenticationServiceImpl.authValidator` rejects a face sample
   without `LIVENESS_DECISION=PASS` from an authentication workflow (`REG-LIV-015`); a resident pass cannot
   authenticate an operator.
5. **1:1 match** with the SFace SDK against the user's onboarding face (desktop through MOSIP's `BioAPIFactory`
   and `SFaceBioApi`; Android through `EngineFaceVerifier` on the engine's SFace session). Threshold 40 on a 0-100
   cosine scale.

After the maximum attempts: `BLOCK` (default) or `BLOCK_AND_LOCK`, which counts as a failed sign-in under the
client's existing `mosip.registration.invalid_login_count` / `invalid_login_time` rule. The screen shows "Face
verification could not be completed. Maximum attempts reached. Use another sign-in method or contact your
supervisor."

PS04 wording "liveness shall provide an additional control to ensure that the matched face is presented by a live
person": the match runs only after a bound liveness pass (desktop run D11 and Android A3 show a live but
unenrolled person passing liveness and then failing the match).

## 4. Supervisor authentication

| Point | Workflow | Desktop | Android |
|---|---|---|---|
| Reviewer of an exception registration | `exception_auth` | `AuthenticationController` reviewer step, window title "Supervisor verification" | supported by the native API (`LivenessApi.authenticateFace` accepts it and checks the supervisor role), but no ARC 1.2.x screen calls it |
| End-of-day approval | `eod_auth` | `EODAuthenticationController.validateFace` | Pending Approval, supervisor dialog (`authenticate_dialogbox.dart`) with a face option; role check `REGISTRATION_SUPERVISOR` before the camera opens |
| Supervisor sign-in | `login_auth` | same as operators | same as operators |
| Supervisor override of a resident failure | (resident transaction) | "Supervisor override" button, requires the signed-in user to hold `REGISTRATION_SUPERVISOR`; audit LV16 with the supervisor ID | not offered (see section 2) |

The same engine, messages and retry rules apply; only the title changes ("Supervisor verification"). Approval
works offline on Android unless `mosip.registration.android.offline_approval.enable=N` (upstream ARC required a
network connection for approval).

## 5. Offline

Every flow above runs with the server unreachable: models, challenges, decisions, binding and 1:1 matching are on
the machine, configuration comes from the last sync, audits and packets wait for the next upload. Checked on both
clients: desktop run D10, Android run A13 ([online-offline.md](online-offline.md)).

## 6. Differences between the two clients

| Topic | Desktop | Android | Reason |
|---|---|---|---|
| Face device | Any SBI 0.9.5 / 1.0 device (L0 or L1) | Built-in camera as an L0 device; vendor SBI app adapter present but only unit-tested | ARC's upstream path is an intent-based vendor app without a preview stream |
| Face screen | Modal `LivenessDialog` over the existing screen | Native full-screen `LivenessCaptureActivity` | JavaFX vs Android views; both render the same `LivenessUiState` |
| Supervisor override | Offered | Treated as `BLOCK` | No failed face is kept on Android |
| Reviewer (`exception_auth`) | Wired to the reviewer screen | API ready, no screen calls it | ARC 1.2.x has no reviewer screen |
| Face sign-in | Upstream feature, now gated | New in ARC (upstream ARC has no biometric sign-in) | PS04 operator authentication |

Detailed per-client designs: [desktop-liveness-services.md](desktop-liveness-services.md),
[desktop-liveness-ui.md](desktop-liveness-ui.md), [android-liveness.md](android-liveness.md).
