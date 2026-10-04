# Desktop liveness services API

- **Where:** `clients/desktop/registration/registration-services`, package
  `io.mosip.registration.service.bio.liveness` (Spring beans), plus `io.mosip.registration.mdm.dto.LivenessContext`;
  screen helpers in `registration-client`, `io.mosip.registration.controller.BaseController` and
  `io.mosip.registration.controller.liveness.LivenessDialog`.
- **Purpose:** the API a desktop screen uses to run a liveness check before a face capture, and the gates that
  refuse a face without a bound pass.
- **Authentication:** in-process Spring beans. The capture gate requires a single-use `LivenessPassToken` issued by
  a passing session; the authentication gate requires `LIVENESS_DECISION=PASS` from an authentication workflow.

Design: [../design/desktop-liveness-services.md](../design/desktop-liveness-services.md),
[../design/desktop-liveness-ui.md](../design/desktop-liveness-ui.md).

## 1. Screen level (recommended)

`BaseController` (all screens extend it):

| Method | Use |
|---|---|
| `boolean isLivenessEnforced(Workflow w)` | Master switch on and the workflow's policy not `OFF`; read on every call |
| `LivenessDialog openLivenessDialog(Workflow w, String subjectRef, Window owner, LivenessDialog.Callbacks cb)` | Opens the modal liveness window for a resident capture or onboarding |
| `LivenessDialog authenticateFaceWithLiveness(Workflow w, String userId, Node anchor, Runnable captureAndMatch)` | Liveness first, then the screen's existing capture-and-match task |
| `Workflow faceAuthWorkflow(boolean isPacketAuth, boolean isReviewer)` | `packet_auth`, `exception_auth` or `eod_auth` |
| `static boolean isLivenessRefusal(Throwable t)` | True when a capture failed with a `REG-LIV-*` refusal (the window already showed the reason) |

`LivenessDialog.Callbacks`: `captureReady(LivenessContext)` (run the existing capture task now; for authentication
the stream is already closed and the device is Ready), `passed()`, `closedWithoutPass(Optional<LivenessOutcome>)`.

## 2. Service level

`LivenessOrchestrator` (Spring bean, one run at a time):

| Method | Returns |
|---|---|
| `boolean isEnforced(Workflow)` | as above |
| `LivenessPolicy policy(Workflow)` | resolved policy (thresholds, attempts, challenge pool) |
| `LivenessEngineRegistry.Status engineStatus()` | loading, ready or failed (fail closed) |
| `LivenessRun start(LivenessRequest, LivenessRunListener)` | a running transaction; `REG-LIV-016` if one is already running, `REG-LIV-014` if this person used up the attempts (`AttemptHistory` keeps counted failures, the attack mark and the low-light count per workflow and person) |
| `boolean isBlocked(Workflow, String subjectRef)` | attempts used up for this person and workflow |
| `Optional<LivenessContext> pendingContext(Workflow, String subjectRef)` / `LivenessContext contextFor(Workflow, String subjectRef)` | the pass waiting for its capture (used by `SessionContext.validateFace` and `captureAndValidateFace`) |

`LivenessRequest.builder(Workflow, String subjectRef)` with optional `.modality(...)` and `.device(FaceCaptureDevice)`
(default: the discovered SBI face device).

`LivenessRun`: `retry()`, `cancel()`, `requestOverride()` (only when `LivenessOutcome.isOverrideAllowed()`; refused after a
suspected attack in this or an earlier window) returning an `OverrideRequest`, `OverrideRequest.grant(SupervisorApproval)`
(single-use context; the approval must come from another user with the supervisor role and be at most 60 s old, else
`OverrideRefusedException` and `REG-EVT-LV16` `decision=REFUSED`; the context expires 60 s after the supervisor's
face match), `close()`, `lastOutcome()`, `policy()`. `SupervisorApproval.of(supervisorId, roles, authenticatedAt)`.
The run delegates its attempt loop and worker thread to the core `LivenessSessionDriver`.

`LivenessRunListener` (called on the `liveness-worker` or `sbi-stream-reader` thread; hand work to the FX thread):
`onUiState(LivenessUiState)`, `onPreviewFrame(FaceFrame)`, `onCaptureReady(LivenessContext)`,
`onSessionEnded(LivenessOutcome)`.

`LivenessOutcome`: `isPassed()`, `failureReason()`, `errorCode()`, `attempt()` (the attempt that just ended), `attemptsUsed()`, `lowLightRetries()` and `isLowLightRetry()` (an uncounted dim-light ending), `maxAttempts()`,
`isRetryAllowed()`, `isOverrideAllowed()`, `isLockRequired()`, `result()`.

`LivenessContext` (on `MDMRequestDto.livenessContext`): `withPass(LivenessPassToken, subjectRef)` or
`withoutPass(Workflow, subjectRef)` (accepted only when the workflow's policy is `OFF`).

## 3. Gates (called by upstream code, not by screens)

| Gate | Called from | Refuses with |
|---|---|---|
| `LivenessGate.admit(MDMRequestDto, MdmBioDevice)` | `BioServiceImpl.captureModality`, before the device call | `RegBaseCheckedException` `REG-LIV-001..007`, `013`, `014` |
| `LivenessGate.complete(Admission, List<BiometricsDto>)` | same, after `RCAPTURE` | `REG-LIV-008`, `009`, `012`, `018` |
| `LivenessGate.abort(Admission)` | same, when the device call fails | — (token withdrawn, session ends `DEVICE`) |
| `LivenessGate.rejectsAuthCapture(String modality, List<BiometricsDto>)` | `AuthenticationServiceImpl.authValidator` | returns `true` (authentication fails, `REG-LIV-015` logged) |

On success `complete` sets `BiometricsDto.livenessEvidence` (the `LIVENESS_*` map) and `livenessWorkflow`, which
`BIRBuilder` and `PacketHandlerServiceImpl` copy into the packet.

## 4. Example request and response

Capture request after `captureReady` (the gate reads `livenessContext`; the SBI request itself is unchanged):

```java
MDMRequestDto dto = new MDMRequestDto("FACE", null, "Registration", env, timeoutMs, 1, 0);
dto.setLivenessContext(context);     // LivenessContext.withPass(token, rid) from captureReady
List<BiometricsDto> captured = bioService.captureModality(dto);
```

Resulting `BiometricsDto.livenessEvidence` for a passive pass (packet `...232013`, desktop end-to-end run D3):

```json
{"LIVENESS_DECISION":"PASS","LIVENESS_METHOD":"PASSIVE","LIVENESS_SCORE":"84","LIVENESS_BINDING":"90-100",
 "LIVENESS_ENGINE":"mosip-onnx-default@1.0.0","LIVENESS_DEVICE_LEVEL":"L0", "...": "..."}
```

Refusal when a screen captures a face without liveness while the policy is enforced: the capture throws
`RegBaseCheckedException` with code `REG-LIV-001` before the device is called, and the client log gets the line
`Face capture refused by the liveness gate for registration: REG-LIV-001 (<reason code>)`
(`LivenessGate.refuse`), and an LV09 audit row with the reason code.

## 5. Use case: add liveness to a new face screen

```java
public class MyFaceController extends BaseController {
    void onScan() {
        if (!isLivenessEnforced(Workflow.REGISTRATION)) {
            runCapture(LivenessContext.withoutPass(Workflow.REGISTRATION, rid));   // capture as before
            return;
        }
        openLivenessDialog(Workflow.REGISTRATION, rid, null, new LivenessDialog.Callbacks() {
            @Override public void captureReady(LivenessContext context) { runCapture(context); }
            @Override public void closedWithoutPass(Optional<LivenessOutcome> last) { /* nothing stored */ }
        });
    }

    private void runCapture(LivenessContext context) {
        MDMRequestDto dto = buildFaceRequest();          // the screen's existing request
        dto.setLivenessContext(context);
        runExistingCaptureTask(dto);                     // BioServiceImpl.captureModality: the gate binds or refuses
    }
}
```

`buildFaceRequest` and `runExistingCaptureTask` stand for the screen's own code. If the screen forgets the context,
the capture is refused (`REG-LIV-001`): the gate fails closed.
