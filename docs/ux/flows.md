# Face liveness user flows (desktop and Android Registration Clients)

Mermaid diagrams (GitHub renders them). Sections 1-6 cover the desktop client, sections 7-12 the Android client.
Both clients take their states, texts and icons from the same engine class (`LivenessUiStateMapper`), so the state
diagram of section 1 also holds for the Android capture screen. How each state looks on both clients, side by
side: [wireframes.svg](wireframes.svg).

- Desktop design and code: [../design/desktop-liveness-ui.md](../design/desktop-liveness-ui.md); screenshots:
  [desktop-liveness.md](desktop-liveness.md).
- Android design and code: [../design/android-liveness.md](../design/android-liveness.md) sections 3, 11 and 13;
  screenshots: [README.md](README.md).

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
    S->>S: gate binds, authValidator refuses a face without PASS
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

## 7. Android: overview of the screens

ARC = Android Registration Client. Its Flutter screens call the native liveness module through `LivenessApi`
(sign-in, packet authentication, approval) or `BiometricsDetailsApi` (face capture). The module opens one
full-screen capture screen (`LivenessCaptureActivity`) that uses the tablet camera as a built-in L0 face device.

```mermaid
flowchart TD
    L[Login: user ID, then password step] -- Sign in with face --> FA1[Capture screen\nworkflow login_auth]
    L -- password --> H[Home]
    FA1 -- pass and face match --> H
    H --> OB[Operator onboarding]
    OB --> FA4[Capture screen\nworkflow onboard_auth]
    FA4 -- pass --> OS[Onboarding face saved,\nused later for face sign-in]
    H --> NR[New registration: demographic pages]
    NR --> B[Biometrics: Face, Scan]
    B --> CS[Capture screen\nworkflow registration]
    CS -- pass --> B2[Face stored, green banner\nGood, face captured successfully]
    CS -- maximum attempts --> BL[Field blocked, recovery banner]
    BL -- policy SUPERVISOR_OVERRIDE --> OV[Supervisor override, section 11]
    B2 --> AU[Authentication step]
    AU -- Authenticate with face --> FA2[Capture screen\nworkflow packet_auth]
    FA2 -- pass and face match --> PK[Packet created]
    H --> PA[Pending approval: select packets]
    PA -- supervisor dialog --> FA3[Capture screen\nworkflow eod_auth\ntitle Supervisor verification]
    FA3 -- pass, supervisor role, face match --> AP[Packets approved]
```

## 8. Android: resident face capture

```mermaid
sequenceDiagram
    actor Op as Operator
    participant F as Flutter biometrics screen
    participant B as BiometricsDetailsApi
    participant G as FaceCaptureLivenessGate
    participant S as LivenessCaptureActivity (capture screen)
    participant E as Liveness engine + built-in camera device
    Op->>F: Scan (Face)
    F->>B: invokeDiscoverSbi(field, "Face")
    B->>G: workflow registration enforced? attempts left?
    alt attempts used up
        G-->>F: BLOCKED, no screen opens
        F-->>Op: "Face capture could not be completed after n attempts..."
    else attempts left
        B->>S: open (registration ID, display language)
        S->>E: start a session on the camera frames
        E-->>S: positioning, checking, challenge, action detected, hold still
        E-->>S: PASS + signed capture (rCapture response)
        S-->>B: result
        B->>B: signature, device trust, time and transaction checks, as for an external SBI device
        B-->>F: face + LIVENESS_* evidence in the BIR others
        F-->>Op: face shown, "Good, face captured successfully"
    end
```

Any result other than a pass (failed, blocked, cancelled) stores no face and uses no ARC attempt; the banner under
Scan shows the result in the display language. The retries of one transaction happen inside the capture screen.

## 9. Android: face sign-in

```mermaid
sequenceDiagram
    actor U as Operator
    participant L as Flutter login (password step)
    participant A as LivenessApi.authenticateFace
    participant FA as FaceAuthFlow / FaceAuthenticator
    participant S as Capture screen (login_auth)
    participant M as Face match (SFace, onboarding face on the tablet)
    U->>L: "Sign in with face" (shown only when face sign-in is available for the user)
    L->>A: authenticateFace(user, login_auth)
    A->>FA: precheck, no camera opens if refused
    FA->>S: open
    S-->>FA: PASS + signed capture
    FA->>M: 1:1 match against the user's onboarding faces
    alt match
        FA-->>L: one-time sign-in grant (60 s)
        L->>L: loginWithFace uses the grant, session from an earlier online sign-in
        L-->>U: home screen (works offline)
    else no match
        FA-->>L: "The face does not match this user. Please try again." (a failed sign-in)
    else liveness refused on the last attempt
        FA-->>L: "... Maximum attempts reached. Use another sign-in method or contact your supervisor."
        Note over FA,L: BLOCK_AND_LOCK locks the account, as after failed passwords
    end
```

## 10. Android: packet authentication and supervisor approval

| Step | Packet authentication (`packet_auth`) | Supervisor approval (`eod_auth`) |
|---|---|---|
| Where | New registration, Authentication step: "Authenticate with face" next to the password | Pending approval: select packets, then the supervisor dialog |
| Before the camera opens | face authentication must be available for the signed-in operator | role check: a user who is not a supervisor gets "This user is not a supervisor. A supervisor must verify." and no camera opens |
| Capture screen title | "Face verification" | "Supervisor verification" |
| After a pass | 1:1 match with the operator's onboarding face; "Face verified" enables Authenticate without a password | 1:1 match with the supervisor's onboarding face; the selected packets are approved, also offline |
| After the last attempt | "... Maximum attempts reached. Use another sign-in method or contact your supervisor." | same message; the packets are not approved |

```mermaid
flowchart LR
    P[Pending approval\nselect packets] --> D[Supervisor dialog: user ID]
    D --> R{REGISTRATION_SUPERVISOR\nrole?}
    R -- no --> X[This user is not a supervisor.\nA supervisor must verify.]
    R -- yes --> C[Capture screen eod_auth\nSupervisor verification]
    C -- pass --> M{Face matches the\nsupervisor's onboarding face?}
    M -- yes --> OK[Packets approved\naudit REG_SUPERVISOR_AUTH_FACE]
    M -- no --> F[Refused, packets stay pending]
    C -- failed or maximum attempts --> F
```

## 11. Android: supervisor override of a resident capture

Only for resident capture (`registration`) with `mosip.registration.liveness.on_max_failure=SUPERVISOR_OVERRIDE`,
and never when a failure was a suspected attack. Details: [../design/android-liveness.md](../design/android-liveness.md)
section 13.1.

```mermaid
flowchart TD
    MX[Last attempt failed,\nno suspected attack] --> OB[Capture screen: recovery message,\nbuttons Cancel and Supervisor override]
    OB -- Supervisor override --> DL[Dialog Supervisor verification:\noverride confirmation text\nand supervisor user ID]
    DL -- Cancel --> BL[Field stays blocked]
    DL -- confirm --> SV[Supervisor face authentication\nworkflow exception_auth:\nliveness, role, 1:1 match]
    SV -- refused --> BL
    SV -- verified --> PH[Resident photo with the built-in camera,\nsigned, no liveness check]
    PH --> ST[Stored with LIVENESS_DECISION=OVERRIDE,\naudit REG-EVT-LV16 names the supervisor,\nbanner: flagged for review in the packet]
    BL -. Scan again while an override is possible .-> OB
```

Texts: confirmation "Supervisor override: this face will be flagged for review in the packet. Continue?"; after
the photo is stored, "Face stored with a supervisor override. It is flagged for review in the packet."

## 12. Android: device errors and offline

- Camera cannot be opened, camera access off, stream interrupted: the capture screen shows the camera-off icon,
  the message for the cause and Retry (plus "Open settings" when camera access is off); no attempt is used.
- No network: the capture screen shows the badge "Offline – verification runs on this device"; capture, sign-in,
  packet authentication and approval all run on the tablet.
