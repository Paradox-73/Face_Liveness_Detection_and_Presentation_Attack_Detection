# Face liveness user flows (desktop Registration Client)

Mermaid diagrams (GitHub renders them). Design and code: [../design/desktop-liveness-ui.md](../design/desktop-liveness-ui.md).
Screenshots: [desktop-liveness.md](desktop-liveness.md).

## 1. Liveness panel states

Every state shows text, an icon and a colour. Terminal states are the ones after the last arrow of each path.

```mermaid
stateDiagram-v2
    [*] --> Checking: window opens\n"Checking face liveness..."
    Checking --> Positioning: face not in the oval\n"Look directly at the camera." / "No face detected..."
    Positioning --> Checking: face found
    Checking --> Challenge: passive result inconclusive\n(policy PASSIVE_THEN_ACTIVE)
    Challenge --> ActionDetected: "Action detected"
    ActionDetected --> Challenge: next challenge (step i of k)
    ActionDetected --> HoldStill: last challenge done
    Checking --> HoldStill: passive pass
    HoldStill --> Verifying: signed capture (rCapture)\nbound to the session
    Verifying --> Success: "Good, face captured successfully"
    Success --> [*]: window closes after 0.9 s
    Checking --> Failed: below threshold / timeout\n"We could not verify face liveness..." + tips
    Challenge --> Failed: challenge timeout / wrong action
    Checking --> FailedPad: PAD, integrity, binding\n"Face verification could not be completed..."
    Verifying --> FailedPad
    Checking --> DeviceError: camera unavailable,\ndisconnected, frames invalid
    Failed --> Checking: Retry (attempts left)
    FailedPad --> Checking: Retry (attempts left)
    DeviceError --> Checking: Retry
    Failed --> MaxAttempts: last attempt used
    FailedPad --> MaxAttempts: last attempt used
    MaxAttempts --> Override: resident only, SUPERVISOR_OVERRIDE,\nnever after a suspected attack
    MaxAttempts --> [*]: Close (auth: invalid-login rule)
    Override --> [*]: capture flagged OVERRIDE in the packet
```

## 2. Resident face capture

```mermaid
sequenceDiagram
    actor Op as Operator
    participant Screen as GenericBiometricsController
    participant Win as LivenessDialog + LivenessPanel
    participant Orc as LivenessOrchestrator / LivenessRun
    participant Dev as SBI face device
    participant Gate as LivenessGate (BioServiceImpl)
    Op->>Screen: Scan (FACE)
    Screen->>Win: openLivenessDialog(registration, RID)
    Win->>Orc: start(request, listener)
    Orc->>Dev: STREAM
    Dev-->>Orc: MJPEG frames
    Orc-->>Win: UI states + preview (FX thread)
    Orc-->>Win: capture ready (pass token)
    Win->>Screen: captureReady(context)
    Screen->>Gate: captureModality(request + context)
    Gate->>Dev: RCAPTURE
    Dev-->>Gate: signed ISO face
    Gate->>Orc: bind(face, time, token)
    Orc-->>Win: session passed
    Gate-->>Screen: BiometricsDto + LIVENESS_* evidence
    Win-->>Op: "Good, face captured successfully", closes
    Screen-->>Op: captured face shown, quality and attempts
```

If binding or PAD on the captured image fails, the gate throws `REG-LIV-*`, the screen stores nothing, and the
window shows the failure with Retry.

## 3. Operator login and packet authentication

```mermaid
sequenceDiagram
    actor U as Operator
    participant L as LoginController / AuthenticationController
    participant Win as LivenessDialog
    participant Orc as LivenessRun
    participant Dev as SBI face device
    participant S as SessionContext.validateFace / captureAndValidateFace
    U->>L: face pane shown (login starts automatically) / Scan
    L->>Win: openLivenessDialog(login_auth | packet_auth, userId)
    Win->>Orc: start
    Orc->>Dev: STREAM ... session passes
    Orc->>Dev: close stream, poll until Ready (max 1.5 s)
    Orc-->>Win: capture ready
    Win->>L: captureReady
    L->>S: existing capture + 1:1 match task
    S->>S: contextFor(workflow, user) = pending pass
    S->>Dev: RCAPTURE (captureModalityForAuth, device Ready)
    S->>S: gate binds; authValidator refuses a face without PASS
    S-->>L: match result -> next screen or "face match failed"
```

## 4. Supervisor: reviewer and end-of-day approval

Same as section 3 with workflow `exception_auth` (reviewer step of packet authentication) or `eod_auth`
(`EODAuthenticationController`); the window title is "Supervisor verification".

## 5. Failure and recovery

```mermaid
flowchart TD
    F[Session failed] --> R{Retry allowed?}
    R -- yes --> RT[Retry button, keyboard focus, Enter] --> S[New session, attempt i+1]
    R -- no --> M{Workflow}
    M -- resident --> O{on_max_failure = SUPERVISOR_OVERRIDE\nand not a suspected attack?}
    O -- yes --> OV[Supervisor override button\nconfirm, supervisor role checked] --> C[Capture with OVERRIDE evidence]
    O -- no --> RS["Face capture could not be completed after n attempts.\nPlease ask your supervisor to follow the centre's recovery process."]
    M -- login / auth --> LK["Face verification could not be completed. Maximum attempts reached.\nUse another sign-in method or contact your supervisor."]
    LK --> IL[BLOCK_AND_LOCK: invalid-login count]
```

## 6. Device error

```mermaid
flowchart LR
    A[Stream refused / closed / stalled / bad frames] --> B[Device error state\ncamera-off icon, message per cause]
    B --> C[Retry: no attempt used]
    B --> D[Cancel: screen re-enabled, nothing stored]
```
