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
| `LivenessPanel` + `fxml/LivenessPanel.fxml` + `liveness-panel.css` | The reusable panel (`fx:root` VBox): live preview (mirrored like a selfie) with an oval face guide, prompt banner (icon, 26 px text, guidance lines, busy indicator), and, for resident workflows when the resident's language differs from the operator's, a second prompt line in the resident's language (32 px bold, with its own text direction), progress bar, "Step i of k", "Attempt i of n", offline badge, diagnostic line, Retry / Cancel / Supervisor override buttons. It only draws. |
| `LivenessViewModel` | Pure mapping from the engine's `LivenessUiState` to what the panel shows (texts resolved, which controls are visible, style class). No JavaFX, unit-tested. |
| `LivenessTexts` | Resolves the shared `liveness.*` keys and the desktop `liveness.ui.*` labels from `labels_<lang>.properties`, with English and then the key as fallbacks; fills `{0}` placeholders; keeps French apostrophes. `withOverrides(config, lang)` first takes a synced replacement text `mosip.registration.liveness.guidance.<messageKey>.<lang>` (G4), with the core rule `LivenessUiStateMapper.resolveText` that the Android client uses too. |
| `DiagnosticText` | The diagnostic lines of the panel when `diagnostic.enable=Y` (G5). Pure formatting, unit-tested. |
| `LivenessModelImportDialog` | Supervisor window for the offline model import (B6), opened from the header menu item "Import liveness models". |
| `LivenessIcons` | SVG path outlines for every `PromptIcon` (blink, smile, head turns, gaze, check, warning, error, camera off, lock). No image files. |
| `FxLivenessListener` | `LivenessRunListener` that moves callbacks from the `liveness-worker` / `sbi-stream-reader` threads to the FX thread (`Platform.runLater`), in order. Preview frames are coalesced: a frame is decoded only when the previous one was drawn, so the screen never queues frames. |
| `LivenessDialog`, `LivenessDialogSizing` | One verification on screen: a modal window with the panel inside a scroll area, driven by one `LivenessRun`. `LivenessDialogSizing.fit` caps the window height at the visual bounds of the monitor that holds it (+16 px width when it scrolls) and moves it inside those bounds; terminal states scroll to Retry and Close. Callbacks `captureReady(context)`, `passed()`, `closedWithoutPass(lastOutcome)`. |
| `SupervisorOverrideFlow`, `SupervisorFaceAuth` | The second-person override: prompt for another supervisor's user ID, checks (blank, no operator, the operator himself, role), then face authentication of that supervisor (section 7). |
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
   with `on_max_failure=SUPERVISOR_OVERRIDE` (never after a suspected attack, in this or an earlier window) a
   Supervisor override button appears; it asks for another supervisor's user ID and verifies that person's face before
   the capture (section 7). Closing calls `closedWithoutPass(lastOutcome)`; authentication screens apply the lock rule when
   `lastOutcome.isLockRequired()` (`on_max_failure=BLOCK_AND_LOCK`): the login screen through its
   `validateInvalidLogin`, the other authentication screens through `BaseController.applyLivenessLock`, which
   counts one failed login with `LivenessAccountLock` and shows either the maximum-attempts message or, when the
   count is reached, the client's own "account temporarily locked" message.

The window always closes its run (`LivenessRun.close()`), which stops the stream and the worker. Auto-logout is
turned off while the window is open and back on when it closes.

## 3. Screens

The change IDs number the desktop edit points; [desktop-liveness-services.md](desktop-liveness-services.md) section 8
lists the service-layer ones.

| Change | Screen / class | Workflow | Change |
|---|---|---|---|
| D4, D5, D6 | Resident face and exception photo, `GenericBiometricsController.scan` / `rCapture` | `registration`, `exception_photo` | FACE with liveness enforced: no parallel stream + capture any more; the liveness window runs first and `rCaptureTaskService()` starts only from `captureReady`. `rCapture` sets the context (pass, or `withoutPass` when not enforced). On a gate refusal the capture task fails and the face is never stored; while the window is open the generic "unable to scan" alert is suppressed because the window shows the failure. |
| D18 | Operator onboarding, `BiometricsController.scan` / `rCapture` | `onboard_auth` | Same pattern for the face modality. |
| D15 | Login with face, `LoginController` | `login_auth` | Liveness starts automatically when the face pane is shown; Submit and the old Stream button open the same window. The existing `executeValidationTask` (capture + 1:1 match in `SessionContext`) runs from `captureReady`. After the last attempt with `BLOCK_AND_LOCK`, `validateInvalidLogin` counts a failed login. |
| D16 | Operator packet authentication and reviewer, `AuthenticationController.validateFace` | `packet_auth`, `exception_auth` (reviewer) | `authenticateFaceWithLiveness` then the existing `executeFaceValidationTask`. The Stream button starts the liveness window instead of a plain stream. Reviewer window title: "Supervisor verification". |
| D17 | End-of-day approval, `EODAuthenticationController.validateFace` | `eod_auth` | Same as D16; title "Supervisor verification". |
| D13 | `BaseController.captureAndValidateFace` | from `faceAuthWorkflow(isPacketAuth, isReviewer)` | Sets `livenessContext` on the request. |
| D21 | `labels_{en,fr,ar}`, `messages_{en,fr,ar}`, `RegistrationUIConstants` | | 75 `liveness.*` label keys in each language (the panel and model import window keys, 6 override keys `liveness.override.{confirm,prompt,supervisor_id,same_user,auth_failed,auth_expired,not_allowed}` and `liveness.feedback.look_straight`) and 2 alert messages. English, French and Arabic; `liveness.retry.max_resident` uses a `MessageFormat` choice for the singular and plural forms (Arabic has the dual and the 3-10 forms). |
| B6 | Header menu, `HeaderController.initialize` | - | One line: `LivenessModelImportDialog.addMenuItem(homeSelectionMenu, livenessModelImporter)`. |

**One service-layer fix found during the screen tests:** `MosipDeviceSpecification_095_ProviderImpl.getRCaptureRequest`
sent `"previousHash": null`; MockMDS 1.3.1 (and SBI 0.9.5, which lists the field as mandatory) refuses that request
(`IllegalArgumentException: previousHash is required`), so no face could be captured at all. The request now sends
an empty `previousHash` (first capture of a transaction).

**Device busy:** SBI devices are Busy while they stream. For authentication workflows the
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

## 5. UX rules applied ([../ux/README.md](../ux/README.md))

- PS04 wording for status, prompts, feedback and failure messages; a suspected attack (PAD, integrity, binding)
  only ever shows the generic "Face verification could not be completed. Please try again." (the engine picks the
  key, the panel cannot show the reason).
- No scores and no buttons during the passive check; Retry only in a terminal state that allows it; step counter
  only in the challenge phase; attempt counter only when more than one attempt is allowed.
- Every state has text, an icon and a colour; colours are checked at 4.5:1 or better against their background.
- Prompt text 26 px bold (the resident line 32 px), guidance and counters 16 px; buttons at least 44 px high.
- The window never exceeds the monitor: content that does not fit scrolls vertically (measured without a window: 619 px at the start, 757 px at maximum attempts with the override, 788 px with the resident line); on a 1366x768 screen those states scroll. The real window has not been opened to confirm the frame size and scroll bar width.
- Retry is the default button (Enter) and gets keyboard focus when it appears; Cancel is the cancel button
  (Escape); the prompt label carries its text as accessible text for screen readers.
- Right-to-left: the window takes the scene orientation of the application language; the preview and the icons
  are kept left-to-right (the preview is mirrored like a selfie, head-turn arrows show the physical direction).
- No model names, scores or reasons unless `mosip.registration.liveness.diagnostic.enable=Y` (diagnostic mode,
  G5, off by default, set by the administrator through the synced configuration). Then a monospace block under
  the counters shows, in fixed English technical terms for support staff:
  - `DIAGNOSTIC workflow=... policy=... maxAttempts=... engine=<status> <engine tag>`;
  - `phase=<UI phase> state=<session state> t=<ms> pad=<PAD score> fps=... planarity=... binding=... flags=...`
    from the latest diagnostic record of the run (the same values as the `LIVENESS-DIAG` log line);
  - `attempts=i/n result=<PASS or failure reason> code=<REG-LIV code>` once a session ended;
  - `models=<id>@<version>#<SHA-256 prefix>, ...`.
  No image, template, user ID or registration ID is shown or logged. Switching the mode is audited
  (`REG-EVT-LV17`).
- Replacement texts (G4): an administrator can replace any liveness text for one language through the synced key
  `mosip.registration.liveness.guidance.<messageKey>.<lang>` (for example
  `...guidance.liveness.feedback.success.eng`); `<lang>` is the client language code (`eng`, `fra`, `ara`). A blank
  value is ignored. Placeholders `{0}`, `{1}` work as in the bundles.

### 5.1 Model import window (B6)

The header menu shows "Import liveness models" above "User guide" for `REGISTRATION_SUPERVISOR` and
`REGISTRATION_ADMIN` users only. The window shows the running bundle (name, version, "installed with the client"
or "imported") and its models, a path field with "Folder..." and "ZIP file..." buttons, and "Verify and import"
(default button). The import runs on a background thread (`liveness-model-import`) with a busy indicator; the
result is one sentence in the display language, green for success and red for a refusal, and always says that the
current models stay in use when the bundle is refused. The service rules are in
[desktop-liveness-services.md](desktop-liveness-services.md) section 6.1. All texts are in `labels_en/fr/ar`
(`liveness.models.*`).
- Offline badge: "Offline - verification runs on this device" when the server is not reachable (liveness itself
  never needs the network).

## 6. Tests

JUnit 4 in `registration-client/src/test/java/io/mosip/registration/controller/liveness/` (36 tests; the client
module had no tests before, so `junit` was added with test scope):

| Test | Covers |
|---|---|
| `LivenessBundleParityTest` | every `MessageKeys.all()` key and every `liveness.ui.*` label in `labels_en/fr/ar`; same `liveness.*` key set in the three bundles; same placeholders; alert messages with `#TYPE#` in `messages_en/fr/ar`; French and Arabic differ from English; bundles equal `docs/ux/message-catalogue.csv` |
| `LivenessViewModelTest` | resident language line (`residentLanguage`, `residentPrompt()`), passive, challenge, feedback, liveness failure with guidance, PAD failure, max attempts with count, device error, success, French and Arabic resolution |
| `LivenessTextsTest` | primary / fallback / key lookup, placeholders with apostrophes, null bundles, language code mapping, replacement texts per language (blank ignored, placeholders, unknown key), plural choice forms in English, French and Arabic |
| `LivenessDialogSizingTest` | window height and position against the monitor's visual bounds, scrolling, minimal moves |
| `SupervisorOverrideFlowTest` | candidate checks: blank, no operator, the operator himself (case-insensitive), role |
| `DiagnosticTextTest` | diagnostic lines with policy, engine, PAD score, frame rate, planarity, binding, flags and model hashes; missing parts; model versions from the record |
| `LivenessModelImportDialogTest` | every import status has its text in English, French and Arabic; result texts fill the versions; model lines; only supervisors and administrators see the menu item |
| `FxLivenessListenerTest` | callbacks only run on the UI executor, in order; detach drops queued and later callbacks; invalid preview frames ignored |

Run: `mvn -B test -Djacoco.skip=true` in `registration-client`.

Screens and flows were checked against the real client, MockSync and MockMDS with
`docs/ux/capture/LivenessScreensDriver.java` (see [../ux/desktop-liveness.md](../ux/desktop-liveness.md)).

## 7. Design decisions and open items

- **Window instead of inline panel.** The panel is not embedded in each screen's FXML (`GenericBiometricFXML`,
  `RegistrationLogin`, `OperatorAuthentication`, `Authentication`). The panel is hosted in one modal window
  (`LivenessDialog`) for all six screens instead: the upstream FXML stays unchanged, the preview is large enough
  for challenges in every screen (the login and authentication panes are 350-450 px wide), and behaviour is
  identical everywhere. The panel itself is a plain `fx:root` component and can still be embedded.
- **D3 (Streamer).** `Streamer` is not turned into a preview consumer; the liveness window draws the preview from
  the run's frames, and `Streamer` stays for modalities and workflows without liveness. It keeps only the
  end-of-stream loop fix.
- **Supervisor override** needs a second person. After the confirmation the window asks for the user ID of another
  supervisor (`liveness.override.prompt`); the ID must differ from the logged-in operator's (`liveness.override.same_user`)
  and belong to a user with the `REGISTRATION_SUPERVISOR` role (`liveness.override.not_allowed` otherwise). That
  supervisor's face is verified (nested liveness window under `exception_auth` when enforced; `liveness.override.auth_failed`);
  the approval counts for 60 s (`liveness.override.auth_expired`). Only face authentication is used for the approver.
  The graphical walk-through of this flow has not been run.
- **Config updated toast** (`liveness.config.updated`) has its text in all bundles but is not shown yet; the
  orchestrator audits configuration changes (`REG-EVT-LV15`).
- **No bundle generator:** no script writes the language bundles from the catalogue; `docs/ux/message-catalogue.csv` is the
  catalogue and `LivenessBundleParityTest` fails the build when a bundle and the catalogue differ.

## 8. Known limits

- Operator face login and face authentication end in the existing 1:1 match, which needs the operator's face
  template (onboarding) and a face matcher SDK. With the default mock SDK the match result does not depend on the
  face; the liveness part in front of it is complete.
- The window does not yet announce each new prompt to screen readers actively (JavaFX 11 has no live-region
  announcement); the prompt's accessible text is updated.
