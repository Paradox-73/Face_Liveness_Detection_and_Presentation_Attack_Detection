# Desktop liveness UI (Registration Client, screens)

This document describes the JavaFX side of face liveness in the desktop Registration Client
(`clients/desktop/registration/registration-client`): the reusable liveness panel, the window that hosts it, and how
each face capture screen uses it. The services it calls (`LivenessOrchestrator`, `LivenessRun`, the capture and
authentication gates) are described in [desktop-liveness-services.md](desktop-liveness-services.md). Screenshots,
state diagram and flows are in [../ux/desktop-liveness.md](../ux/desktop-liveness.md).

## 1. Components

All new classes are in `io.mosip.registration.controller.liveness`.

| Class / file | Role |
|---|---|
| `LivenessPanel` + `fxml/LivenessPanel.fxml` + `liveness-panel.css` | The reusable panel (`fx:root` VBox): live preview (mirrored like a selfie) with an oval face guide, prompt banner (icon, 26 px text, guidance lines, busy indicator), progress bar, "Step i of k", "Attempt i of n", offline badge, diagnostic line, Retry / Cancel / Supervisor override buttons. It only draws. |
| `LivenessViewModel` | Pure mapping from the engine's `LivenessUiState` to what the panel shows (texts resolved, which controls are visible, style class). No JavaFX, unit-tested. |
| `LivenessTexts` | Resolves the shared `liveness.*` keys and the desktop `liveness.ui.*` labels from `labels_<lang>.properties`, with English and then the key as fallbacks; fills `{0}` placeholders; keeps French apostrophes. |
| `LivenessIcons` | SVG path outlines for every `PromptIcon` (blink, smile, head turns, gaze, check, warning, error, camera off, lock). No image files. |
| `FxLivenessListener` | `LivenessRunListener` that moves callbacks from the `liveness-worker` / `sbi-stream-reader` threads to the FX thread (`Platform.runLater`), in order. Preview frames are coalesced: a frame is decoded only when the previous one was drawn, so the screen never queues frames. |
| `LivenessDialog` | One verification on screen: a modal window with the panel, driven by one `LivenessRun`. Callbacks `captureReady(context)`, `passed()`, `closedWithoutPass(lastOutcome)`. |
| `BaseController` (upstream, additions) | `livenessOrchestrator` bean, `isLivenessEnforced(workflow)`, `openLivenessDialog(...)`, `authenticateFaceWithLiveness(...)`, `faceAuthWorkflow(isPacketAuth, isReviewer)`, `isLivenessRefusal(exception)`; `captureAndValidateFace` puts the liveness context on its capture request. |

## 2. Flow of one verification

1. The screen checks `isLivenessEnforced(workflow)` (master switch on and the workflow's policy not `OFF`, read at
   each call so a config sync applies to the next capture). If not enforced, the screen captures as before, with
   `LivenessContext.withoutPass(workflow, subject)` on the request.
2. `openLivenessDialog(workflow, subject, owner, callbacks)` opens the window. The panel shows
   "Checking face liveness..." at once; the orchestrator opens the SBI stream and the first session on its worker.
3. Every `LivenessUiState` is mapped to a `LivenessViewModel` and drawn. Preview frames are drawn as they come.
4. On capture ready the dialog calls `callbacks.captureReady(context)`. The screen puts the context on its
   `MDMRequestDto` (or, for login and operator / supervisor authentication, `SessionContext.validateFace` and
   `BaseController.captureAndValidateFace` fetch the pending pass with `orchestrator.contextFor(workflow, user)`)
   and runs its **existing** capture task. The service gate binds the signed capture to the session.
5. When the session ends with a pass, the panel shows "Good, face captured successfully" for 0.9 s, the window
   closes and `passed()` is called. The screen's own capture task then finishes as before (store the face, or
   match it).
6. When the session fails, the panel shows the failure text and guidance; Retry appears while the policy allows
   another attempt (`LivenessRun.retry()`). After the last attempt Cancel becomes Close; for resident workflows
   with `on_max_failure=SUPERVISOR_OVERRIDE` (never after a suspected attack) a Supervisor override button
   appears. Closing calls `closedWithoutPass(lastOutcome)`; authentication screens apply the lock rule when
   `lastOutcome.isLockRequired()`.

The window always closes its run (`LivenessRun.close()`), which stops the stream and the worker. Auto-logout is
turned off while the window is open and back on when it closes.

## 3. Screens (plan section 4.12)

| Plan | Screen / class | Workflow | Change |
|---|---|---|---|
| D4, D5, D6 | Resident face and exception photo, `GenericBiometricsController.scan` / `rCapture` | `registration`, `exception_photo` | FACE with liveness enforced: no parallel stream + capture any more; the liveness window runs first and `rCaptureTaskService()` starts only from `captureReady`. `rCapture` sets the context (pass, or `withoutPass` when not enforced). On a gate refusal the capture task fails and the face is never stored; while the window is open the generic "unable to scan" alert is suppressed because the window shows the failure. |
| D18 | Operator onboarding, `BiometricsController.scan` / `rCapture` | `onboard_auth` | Same pattern for the face modality. |
| D15 | Login with face, `LoginController` | `login_auth` | Liveness starts automatically when the face pane is shown; Submit and the old Stream button open the same window. The existing `executeValidationTask` (capture + 1:1 match in `SessionContext`) runs from `captureReady`. After the last attempt with `BLOCK_AND_LOCK`, `validateInvalidLogin` counts a failed login. |
| D16 | Operator packet authentication and reviewer, `AuthenticationController.validateFace` | `packet_auth`, `exception_auth` (reviewer) | `authenticateFaceWithLiveness` then the existing `executeFaceValidationTask`. The Stream button starts the liveness window instead of a plain stream. Reviewer window title: "Supervisor verification". |
| D17 | End-of-day approval, `EODAuthenticationController.validateFace` | `eod_auth` | Same as D16; title "Supervisor verification". |
| D13 | `BaseController.captureAndValidateFace` | from `faceAuthWorkflow(isPacketAuth, isReviewer)` | Sets `livenessContext` on the request. |
| D21 | `labels_{en,fr,ar}`, `messages_{en,fr,ar}`, `RegistrationUIConstants` | | 49 label keys and 2 alert messages in each language. |

**One service-layer fix found during the screen tests:** `MosipDeviceSpecification_095_ProviderImpl.getRCaptureRequest`
sent `"previousHash": null`; MockMDS 1.3.1 (and SBI 0.9.5, which lists the field as mandatory) refuses that request
(`IllegalArgumentException: previousHash is required`), so no face could be captured at all. The request now sends
an empty `previousHash` (first capture of a transaction).

**Device busy (plan section 4.4):** SBI devices are Busy while they stream. For authentication workflows the
service run closes the stream at capture ready and waits for Ready (at most 1.5 s) before `captureReady` reaches
the screen, so `captureModalityForAuth` finds a Ready device. The screens no longer start a plain `Streamer`
stream when liveness is enforced (it would keep the device busy).

## 4. Threading

| Thread | Work |
|---|---|
| JavaFX application thread | everything in `LivenessDialog`, `LivenessPanel`, and the screens' callbacks |
| `liveness-worker` | UI states, capture ready and session end; `FxLivenessListener` only posts them |
| `sbi-stream-reader` | preview frames; decoded to a JavaFX `Image` on this thread, at most one pending |
| screen capture task (JavaFX `Service`) | the existing capture; the gate forwards the bind to the worker |
| `liveness-online-check` | one `ServiceDelegateUtil.isNetworkAvailable()` call for the offline badge |

## 5. UX rules applied (plan section 11)

- PS04 wording for status, prompts, feedback and failure messages; a suspected attack (PAD, integrity, binding)
  only ever shows the generic "Face verification could not be completed. Please try again." (the engine picks the
  key, the panel cannot show the reason).
- No scores and no buttons during the passive check; Retry only in a terminal state that allows it; step counter
  only in the challenge phase; attempt counter only when more than one attempt is allowed.
- Every state has text, an icon and a colour; colours are checked at 4.5:1 or better against their background.
- Prompt text 26 px bold, guidance and counters 16 px; buttons at least 44 px high.
- Retry is the default button (Enter) and gets keyboard focus when it appears; Cancel is the cancel button
  (Escape); the prompt label carries its text as accessible text for screen readers.
- Right-to-left: the window takes the scene orientation of the application language; the preview and the icons
  are kept left-to-right (the preview is mirrored like a selfie, head-turn arrows show the physical direction).
- No model names, scores or reasons unless `mosip.registration.liveness.diagnostic.enable=Y`, in which case one
  monospace line shows workflow, policy, engine state, phase, attempts and the failure reason code.
- Offline badge: "Offline - verification runs on this device" when the server is not reachable (liveness itself
  never needs the network).

## 6. Tests

JUnit 4 in `registration-client/src/test/java/io/mosip/registration/controller/liveness/` (26 tests; the client
module had no tests before, so `junit` was added with test scope):

| Test | Covers |
|---|---|
| `LivenessBundleParityTest` | every `MessageKeys.all()` key and every `liveness.ui.*` label in `labels_en/fr/ar`; same `liveness.*` key set in the three bundles; same placeholders; alert messages with `#TYPE#` in `messages_en/fr/ar`; French and Arabic differ from English; bundles equal `docs/ux/message-catalogue.csv` |
| `LivenessViewModelTest` | passive, challenge, feedback, liveness failure with guidance, PAD failure, max attempts with count, device error, success, French and Arabic resolution |
| `LivenessTextsTest` | primary / fallback / key lookup, placeholders with apostrophes, null bundles, language code mapping |
| `FxLivenessListenerTest` | callbacks only run on the UI executor, in order; detach drops queued and later callbacks; invalid preview frames ignored |

Run: `mvn -B test -Djacoco.skip=true` in `registration-client`.

Screens and flows were checked against the real client, MockSync and MockMDS with
`docs/ux/capture/LivenessScreensDriver.java` (see [../ux/desktop-liveness.md](../ux/desktop-liveness.md)).

## 7. Deviations from the plan

- **Window instead of inline panel.** The plan embeds the panel in each screen's FXML (`GenericBiometricFXML`,
  `RegistrationLogin`, `OperatorAuthentication`, `Authentication`). The panel is hosted in one modal window
  (`LivenessDialog`) for all six screens instead: the upstream FXML stays unchanged, the preview is large enough
  for challenges in every screen (the login and authentication panes are 350-450 px wide), and behaviour is
  identical everywhere. The panel itself is a plain `fx:root` component and can still be embedded.
- **D3 (Streamer).** `Streamer` is not turned into a preview consumer; the liveness window draws the preview from
  the run's frames, and `Streamer` stays for modalities and workflows without liveness. It keeps only the
  end-of-stream loop fix.
- **Supervisor override** is granted by the logged-in user when that user has the `REGISTRATION_SUPERVISOR` role;
  there is no separate supervisor authentication step in the window yet. Other users see "This user is not a
  supervisor. A supervisor must verify."
- **Config updated toast** (`liveness.config.updated`) has its text in all bundles but is not shown yet; the
  orchestrator audits configuration changes (`REG-EVT-LV15`).
- **Generator:** `scripts/gen_i18n.py` from plan section 11.3 is not written; `docs/ux/message-catalogue.csv` is the
  catalogue and `LivenessBundleParityTest` fails the build when a bundle and the catalogue differ.

## 8. Known limits

- Operator face login and face authentication end in the existing 1:1 match, which needs the operator's face
  template (onboarding) and a face matcher SDK. With the default mock SDK the match result does not depend on the
  face; the liveness part in front of it is complete.
- The window does not yet announce each new prompt to screen readers actively (JavaFX 11 has no live-region
  announcement); the prompt's accessible text is updated.
