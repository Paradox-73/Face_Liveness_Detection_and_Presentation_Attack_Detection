# UX: screens, flows and messages

Index of every committed screen picture of the face liveness feature on both clients, grouped by state, plus the
states that have no picture yet. All pictures are real screens of the running clients. The desktop pictures
`e2e-03`, `e2e-05`, `e2e-09` and `e2e-16` show the camera preview with recorded faces (taken 2026-10-21 with
`tools/mock-sync/e2e/flows/screens-faces.flow`): subject S3 is Pexels 6781549 by Artem Podrez, the attack packs are
made from Pexels 5474329 and 5474326 by cottonbro studio, all under the Pexels License (attribution:
[../eval/README.md](../eval/README.md)); the people are not project members. The other `e2e-*` pictures and the
Android pictures hide the camera preview, so the dark oval is where the camera image would be. The desktop
`d-*` pictures show, in the preview, the stock stream image that ships with MOSIP MockMDS (MPL-2.0; see
[desktop-liveness.md](desktop-liveness.md)), never a team member or a volunteer.

Wireframes of every state, desktop and Android side by side: [wireframes.svg](wireframes.svg) (one row per
PS04 section 10 state plus device error, multiple faces, offline and supervisor override; drawn 2026-10-04 on an
opaque light card, so it reads the same on light and dark pages).

| Document | Content |
|---|---|
| [wireframes.svg](wireframes.svg) | Wireframe sheet: desktop dialog and Android capture screen for each of 14 states, with message keys and the matching committed pictures. Drawn by [capture/draw_wireframes.py](capture/draw_wireframes.py) from the texts of the message catalogue |
| [desktop-liveness.md](desktop-liveness.md) | Desktop pictures with captions, accessibility checks (contrast 6.8:1 or more, keyboard, screen reader), how to reproduce them |
| [flows.md](flows.md) | State diagram of the liveness panel and user flows as Mermaid diagrams: desktop (resident, operator, supervisor, failure and recovery, device error) and Android (screen overview, resident capture, face sign-in, packet authentication, supervisor approval, supervisor override, device errors and offline) |
| [message-catalogue.csv](message-catalogue.csv) | Every liveness text in English, French and Arabic, with its PS04 source (the same keys and English texts are in the Android module) |
| [../design/desktop-liveness-ui.md](../design/desktop-liveness-ui.md) | Desktop screen design and deviations |
| [../design/android-liveness.md](../design/android-liveness.md) | Android capture screen (section 3) and ARC screens (section 11) |
| [capture/LivenessScreensDriver.java](capture/LivenessScreensDriver.java) | Drives the real desktop client through each state and saves pictures |

Picture folders: `screenshots/desktop/` (JavaFX client, 1280x800 window) and `screenshots/android/` (10.1-inch
tablet emulator, 1280x800). IDs such as D-R1 name one screen state (D = desktop, A = Android,
R = resident, L = login, E = end of day).

## 1. Resident face capture

| State | Desktop | Android |
|---|---|---|
| Positioning (oval guide, "Look directly at the camera.") | [d-r1-positioning.png](screenshots/desktop/d-r1-positioning.png) | [a-p1-positioning-offline.png](screenshots/android/a-p1-positioning-offline.png) (amber oval and banner "No face detected. Keep your face within the capture area.", progress bar, offline badge; taken 2026-10-05 in airplane mode with an empty replay pack) |
| Positioning tip during an attempt (amber banner and oval, "Ensure your face is clearly visible.", attempt 3 of 3) | [d-r8-max-attempts.png](screenshots/desktop/d-r8-max-attempts.png) (the file name says maximum attempts, but the picture was taken before the attempt ended; see section 7) | — |
| Checking face liveness (passive, no action needed) | — (described in [desktop-liveness.md](desktop-liveness.md) section 2; wireframe row 2) | — (wireframe row 2) |
| Active challenge, step 1 of 2, icon and progress | [e2e-03-low-confidence-challenge.png](screenshots/desktop/e2e-03-low-confidence-challenge.png) ("Please smile", smile icon, face in the preview) | [e2e-06-low-confidence-challenge.png](screenshots/android/e2e-06-low-confidence-challenge.png) ("Please smile") |
| Challenge forced by policy (`ALWAYS_ACTIVE` after a sync) | [e2e-10-config-sync-print-refused.png](screenshots/desktop/e2e-10-config-sync-print-refused.png) | [e2e-12-config-active-challenge.png](screenshots/android/e2e-12-config-active-challenge.png) |
| Action detected / hold still | — (wireframe rows 4 and 5) | — (wireframe rows 4 and 5) |
| Success, "Good, face captured successfully" | [e2e-16-resident-face-passed.png](screenshots/desktop/e2e-16-resident-face-passed.png) (biometrics screen after the pass: captured face, quality 95%, attempt 1; the window's own success line is described in [desktop-liveness.md](desktop-liveness.md) section 2) | [a-r1-resident-face-passed.png](screenshots/android/a-r1-resident-face-passed.png) (ARC biometrics screen: banner, quality 90%, attempt 1) |

## 2. Operator authentication

| State | Desktop | Android |
|---|---|---|
| Face login / "Sign in with face" | [d-l1-login-french-offline.png](screenshots/desktop/d-l1-login-french-offline.png) (French, offline badge) | [a-l1-sign-in-with-face.png](screenshots/android/a-l1-sign-in-with-face.png), [e2e-01-sign-in-with-face.png](screenshots/android/e2e-01-sign-in-with-face.png) |
| Live person, wrong operator (liveness passes, face match fails) | [e2e-12-face-match-not-found.png](screenshots/desktop/e2e-12-face-match-not-found.png) | [e2e-02-face-no-match.png](screenshots/android/e2e-02-face-no-match.png) |
| Packet authentication by face | — | [e2e-07-packet-auth-face-verified.png](screenshots/android/e2e-07-packet-auth-face-verified.png) ("Face verified") |
| Operator onboarding (face captured through an L1 device) | [e2e-11-l1-device-onboarding.png](screenshots/desktop/e2e-11-l1-device-onboarding.png) | — |
| Maximum attempts at sign-in | — | [a-l2-face-sign-in-max-attempts.png](screenshots/android/a-l2-face-sign-in-max-attempts.png) ("Use another sign-in method or contact your supervisor") |

## 3. Supervisor authentication

| State | Desktop | Android |
|---|---|---|
| Approval list before supervisor verification | [e2e-08-eod-pending.png](screenshots/desktop/e2e-08-eod-pending.png) | [e2e-10-pending-approval.png](screenshots/android/e2e-10-pending-approval.png) |
| "Supervisor verification" window | [d-e1-eod-supervisor-arabic.png](screenshots/desktop/d-e1-eod-supervisor-arabic.png) (Arabic, right to left) | [e2e-11-supervisor-replay-refused.png](screenshots/android/e2e-11-supervisor-replay-refused.png) |
| Screen replay refused at approval | [e2e-09-eod-replay-refused.png](screenshots/desktop/e2e-09-eod-replay-refused.png) (the replayed video is visible in the preview) | [e2e-11-supervisor-replay-refused.png](screenshots/android/e2e-11-supervisor-replay-refused.png) |
| Reviewer authentication after a biometric exception | [e2e-04-reviewer-authentication.png](screenshots/desktop/e2e-04-reviewer-authentication.png) | — (no reviewer step in the Android client) |
| Approval completed offline | — | [e2e-13-offline-approved.png](screenshots/android/e2e-13-offline-approved.png) (airplane mode, 0 applications left) |

## 4. Failures, retry and recovery

| State | Desktop | Android |
|---|---|---|
| Liveness not established (challenge timeout): "We could not verify face liveness. Please try again." with guidance | [e2e-10-config-sync-print-refused.png](screenshots/desktop/e2e-10-config-sync-print-refused.png) | [e2e-03-sign-in-replay-refused.png](screenshots/android/e2e-03-sign-in-replay-refused.png) |
| Suspected attack (PAD, integrity): generic "Face verification could not be completed. Please try again." | [e2e-05-print-refused.png](screenshots/desktop/e2e-05-print-refused.png) (printed photo in the preview), [d-r7-pad-fail-retry.png](screenshots/desktop/d-r7-pad-fail-retry.png), [e2e-06-injected-still-refused.png](screenshots/desktop/e2e-06-injected-still-refused.png) | [e2e-05-resident-replay-refused.png](screenshots/android/e2e-05-resident-replay-refused.png) |
| Retry available, "Attempt n of 3" | [d-r7-pad-fail-retry.png](screenshots/desktop/d-r7-pad-fail-retry.png) | [e2e-05-resident-replay-refused.png](screenshots/android/e2e-05-resident-replay-refused.png) |
| Maximum attempts, recovery guidance | [e2e-07-max-attempts-guidance.png](screenshots/desktop/e2e-07-max-attempts-guidance.png) | [e2e-08-max-attempts.png](screenshots/android/e2e-08-max-attempts.png) |
| Capture blocked for the registration after maximum attempts | — | [e2e-09-blocked-under-scan.png](screenshots/android/e2e-09-blocked-under-scan.png), [a-r3-resident-max-attempts.png](screenshots/android/a-r3-resident-max-attempts.png) |
| No face in view (no attempt used) | [d-r12-no-face.png](screenshots/desktop/d-r12-no-face.png) | [a-p2-no-face-offline.png](screenshots/android/a-p2-no-face-offline.png) (still "Attempt 1 of 3" with Retry; offline badge) |
| Printed photo accepted under the shipped policy (known weakness, kept as evidence) | — | [e2e-14-print-accepted-shipped-policy.png](screenshots/android/e2e-14-print-accepted-shipped-policy.png) |
| Supervisor override offered after the last resident attempt (policy `SUPERVISOR_OVERRIDE`, no suspected attack) | — | [capture-override-offered-diagnostics.png](screenshots/android/capture-override-offered-diagnostics.png) (run with `max_retries=1`; the picture was taken before the plural fix and reads "after 1 attempts", the app now reads "after 1 attempt"; diagnostic panel on) |
| Supervisor override confirmation, supervisor user ID | — | [capture-override-supervisor-dialog.png](screenshots/android/capture-override-supervisor-dialog.png) |

## 5. Device errors

| State | Desktop | Android |
|---|---|---|
| Device unavailable / stream refused | [d-r10-device-error.png](screenshots/desktop/d-r10-device-error.png) (shows the "disconnected" text; see gaps) | — |
| Device disconnected during capture | [e2e-02-device-disconnected.png](screenshots/desktop/e2e-02-device-disconnected.png) | — |
| Camera unavailable | — | [a-r2-camera-unavailable.png](screenshots/android/a-r2-camera-unavailable.png), [e2e-04-camera-unavailable.png](screenshots/android/e2e-04-camera-unavailable.png) |

## 6. Offline and languages

| State | Desktop | Android |
|---|---|---|
| Offline badge "Offline – verification runs on this device" | [d-l1-login-french-offline.png](screenshots/desktop/d-l1-login-french-offline.png) | [a-p1-positioning-offline.png](screenshots/android/a-p1-positioning-offline.png), [a-p2-no-face-offline.png](screenshots/android/a-p2-no-face-offline.png) (badge under the title, next to the debug-build "Replay" label) |
| Offline model import (bonus B6): Settings, Device Settings, "Import liveness model", version in use | [e2e-15-model-import-verified.png](screenshots/desktop/e2e-15-model-import-verified.png) | [a-s1-model-import-button.png](screenshots/android/a-s1-model-import-button.png) ("Model in use: version 1.3.0") |
| Arabic, right-to-left | [d-e1-eod-supervisor-arabic.png](screenshots/desktop/d-e1-eod-supervisor-arabic.png) | — (verified in scratch pictures and the widget test `Arabic: translated label and right-to-left layout`) |
| French | [d-l1-login-french-offline.png](screenshots/desktop/d-l1-login-french-offline.png) | — |

## 7. Missing states (no committed picture)

| State | Desktop | Android | Why missing / how to get it |
|---|---|---|---|
| Checking face liveness | missing | missing | Only frames of real people reach this state long enough; take it with a masked preview (`mask on` in the desktop driver, `masked` replay on Android) |
| Action detected, hold still | missing | missing | Same; the states were observed in logs (`desktop-liveness.md` section 2, Android Logcat) |
| Success on the capture window itself | only the biometrics screen after the pass (`e2e-16`) | only the ARC banner | Same |
| Multiple faces | missing | missing | No two-face frame pack in the client runs (`eval/scenarios/other-two-faces.yaml` runs only in the scenario runner) |
| Poor quality (too dark, blurred) | missing | missing | Needs a masked run with the dim or blurred pack |
| Device unavailable with its own wording | wrong text | not applicable | Desktop maps a refused stream to the "disconnected" message; fix in the services layer |
| Supervisor override (resident) | missing | present (text "after 1 attempts" from before the plural fix, see retakes) | Desktop: run section I2 of the desktop report with snapshots on |
| Packet authentication by face | missing | present | Desktop driver section D3 has no snapshot step |
| Arabic / Hindi capture screen on Android | missing | missing | Pictures exist only in the scratch folder of the module engineer; re-take with the masked replay |
| Diagnostic mode (scores shown to a supervisor) | present: [e2e-13-diagnostic-mode.png](screenshots/desktop/e2e-13-diagnostic-mode.png) (not yet captioned in [desktop-liveness.md](desktop-liveness.md)) | present: [capture-override-offered-diagnostics.png](screenshots/android/capture-override-offered-diagnostics.png) | — |
| Configuration updated notice (`liveness.config.updated`) | missing | missing | Text exists but no screen shows it yet |
| Drawn wireframes | present | present | [wireframes.svg](wireframes.svg), both clients side by side, 14 states |

### 7.1 Retakes still needed (2026-10-05)

| Picture | Problem | Retake |
|---|---|---|
| [d-r8-max-attempts.png](screenshots/desktop/d-r8-max-attempts.png) | Taken during attempt 3 while the positioning tip "Ensure your face is clearly visible." was shown, not after the attempt ended; captioned as what it shows | Run the driver's `static-pad` scenario again and snapshot after the third failure (lock icon, Close), or drop the file: [e2e-07-max-attempts-guidance.png](screenshots/desktop/e2e-07-max-attempts-guidance.png) already shows that state |
| [e2e-03-low-confidence-challenge.png](screenshots/desktop/e2e-03-low-confidence-challenge.png) | Shows "Please smile" (challenges are random); earlier captions said "Please blink". Captions in this folder are fixed; [../pitch/deck-outline.md](../pitch/deck-outline.md) is corrected separately | No retake needed; fix the deck caption |
| [capture-override-offered-diagnostics.png](screenshots/android/capture-override-offered-diagnostics.png), [capture-override-supervisor-dialog.png](screenshots/android/capture-override-supervisor-dialog.png) | Text reads "after 1 attempts" (run with `max_retries=1`, before the plural fix: both clients now use a singular form, "after 1 attempt"; the Android resource has a plural form in all six languages, the desktop bundles in en, fr and ar) | Retake with the current build, or with the shipped 3 attempts |
| Desktop `d-*` pictures | Preview shows the MockMDS stock photo with mirrored overlay text | Retake with the masked preview like the `e2e-*` pictures, for a consistent look |
| New window and colours | The desktop window that scrolls and is capped at the monitor height, the second prompt line in the resident's language, the "Look directly at the camera and hold still" step, the supervisor override dialog with the second-person prompt and the Android capture screen in the shared status colours were never shown on a screen (no window was opened for these changes) | Masked runs on both clients |
| States marked missing above | No picture | Masked runs as described in each row; the wireframe sheet stands in until then |

## 8. Consistency between the clients

- Same message keys and English texts: the desktop bundles hold 75 `liveness.*` label keys per language (en, fr, ar;
  the 2026-09-30 check covered 46 keys against the Android module's `values/strings.xml`, `../test-report/android-e2e.md`
  section 7). The Android module has the engine keys in six languages; a unit test on each client fails when a key
  of the engine's message list has no text.
- Attempt counts use plural forms on both clients ("after 1 attempt", "after 3 attempts"): desktop with a choice
  pattern in the en, fr and ar bundles, Android with a `plurals` resource in en, hi, kn, ta, fr and ar.
- Same states and meanings: device error without using an attempt, "Step n of k" challenges, generic PAD message,
  "Attempt n of 3", maximum-attempts recovery text, "Supervisor verification" title, offline badge.
- Supervisor override: both clients offer it after the last resident failure when the policy is
  `SUPERVISOR_OVERRIDE`, never after a suspected attack, and a second, different supervisor approves it with face
  verification. Steps and texts: [../design/desktop-liveness-ui.md](../design/desktop-liveness-ui.md) section 7
  (desktop), [../design/android-liveness.md](../design/android-liveness.md) section 13.1 (Android).
- Shared look: both clients show the status line, icon and oval in the same colours (neutral, success, attention,
  failure and guidance values of the desktop `liveness-panel.css`; Android tests compare them with the desktop file
  and check the contrast ratios). Android still shows a dark full screen with the status banner on top.
- Desktop window: a scroll area around the panel, height capped at the visible height of the monitor, so Retry and
  Close stay reachable on 1366x768 screens (sizes: [../design/desktop-liveness-ui.md](../design/desktop-liveness-ui.md)
  section 5). For resident workflows a second prompt line in the
  resident's language (en, fr or ar, when it differs from the operator's) appears under the prompt.
- Differences by design: the desktop dialog is a light 520 px window over the capture screen, Android a dark
  full screen (each follows its platform); the wrong-person text at sign-in differs ("Face match not found" on
  desktop, "The face does not match this user. Please try again." on Android). Side by side: [wireframes.svg](wireframes.svg).
