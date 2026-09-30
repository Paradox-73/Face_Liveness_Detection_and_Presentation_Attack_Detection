# UX: screens, flows and messages

Index of every committed screen picture of the face liveness feature on both clients, grouped by state, plus the
states that have no picture yet. All pictures are real screens of the running clients (not drawn mockups). None
shows a real face: the desktop driver and the Android replay hide the camera preview, so the dark oval is where the
camera image would be.

| Document | Content |
|---|---|
| [desktop-liveness.md](desktop-liveness.md) | Desktop pictures with captions, accessibility checks (contrast 6.8:1 or more, keyboard, screen reader), how to reproduce them |
| [flows.md](flows.md) | State diagram of the liveness panel and user flows (resident, operator, supervisor, failure and recovery, device error) as Mermaid diagrams |
| [message-catalogue.csv](message-catalogue.csv) | Every liveness text in English, French and Arabic, with its PS04 source (the same keys and English texts are in the Android module) |
| [../design/desktop-liveness-ui.md](../design/desktop-liveness-ui.md) | Desktop screen design and deviations |
| [../design/android-liveness.md](../design/android-liveness.md) | Android capture screen (section 3) and ARC screens (section 11) |
| [capture/LivenessScreensDriver.java](capture/LivenessScreensDriver.java) | Drives the real desktop client through each state and saves pictures |

Picture folders: `screenshots/desktop/` (JavaFX client, 1280x800 window) and `screenshots/android/` (10.1-inch
tablet emulator, 1280x800). IDs such as D-R1 follow the mockup list of the design plan (D = desktop, A = Android,
R = resident, L = login, E = end of day).

## 1. Resident face capture

| State | Desktop | Android |
|---|---|---|
| Positioning (oval guide, "Look directly at the camera.") | [d-r1-positioning.png](screenshots/desktop/d-r1-positioning.png) | — |
| Checking face liveness (passive, no action needed) | — (described in [desktop-liveness.md](desktop-liveness.md) section 2) | — |
| Active challenge, step 1 of 2, icon and progress | [e2e-03-low-confidence-challenge.png](screenshots/desktop/e2e-03-low-confidence-challenge.png) ("Please blink") | [e2e-06-low-confidence-challenge.png](screenshots/android/e2e-06-low-confidence-challenge.png) ("Please smile") |
| Challenge forced by policy (`ALWAYS_ACTIVE` after a sync) | [e2e-10-config-sync-print-refused.png](screenshots/desktop/e2e-10-config-sync-print-refused.png) | [e2e-12-config-active-challenge.png](screenshots/android/e2e-12-config-active-challenge.png) |
| Action detected / hold still | — | — |
| Success, "Good, face captured successfully" | — (described in [desktop-liveness.md](desktop-liveness.md) section 2) | [a-r1-resident-face-passed.png](screenshots/android/a-r1-resident-face-passed.png) (ARC biometrics screen: banner, quality 90%, attempt 1) |

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
| Screen replay refused at approval | [e2e-09-eod-replay-refused.png](screenshots/desktop/e2e-09-eod-replay-refused.png) | [e2e-11-supervisor-replay-refused.png](screenshots/android/e2e-11-supervisor-replay-refused.png) |
| Reviewer authentication after a biometric exception | [e2e-04-reviewer-authentication.png](screenshots/desktop/e2e-04-reviewer-authentication.png) | — (no reviewer step in the Android client) |
| Approval completed offline | — | [e2e-13-offline-approved.png](screenshots/android/e2e-13-offline-approved.png) (airplane mode, 0 applications left) |

## 4. Failures, retry and recovery

| State | Desktop | Android |
|---|---|---|
| Liveness not established (challenge timeout): "We could not verify face liveness. Please try again." with guidance | [e2e-05-print-refused.png](screenshots/desktop/e2e-05-print-refused.png) | [e2e-03-sign-in-replay-refused.png](screenshots/android/e2e-03-sign-in-replay-refused.png) |
| Suspected attack (PAD, integrity): generic "Face verification could not be completed. Please try again." | [d-r7-pad-fail-retry.png](screenshots/desktop/d-r7-pad-fail-retry.png), [e2e-06-injected-still-refused.png](screenshots/desktop/e2e-06-injected-still-refused.png) | [e2e-05-resident-replay-refused.png](screenshots/android/e2e-05-resident-replay-refused.png) |
| Retry available, "Attempt n of 3" | [d-r7-pad-fail-retry.png](screenshots/desktop/d-r7-pad-fail-retry.png) | [e2e-05-resident-replay-refused.png](screenshots/android/e2e-05-resident-replay-refused.png) |
| Maximum attempts, recovery guidance | [d-r8-max-attempts.png](screenshots/desktop/d-r8-max-attempts.png), [e2e-07-max-attempts-guidance.png](screenshots/desktop/e2e-07-max-attempts-guidance.png) | [e2e-08-max-attempts.png](screenshots/android/e2e-08-max-attempts.png) |
| Capture blocked for the registration after maximum attempts | — | [e2e-09-blocked-under-scan.png](screenshots/android/e2e-09-blocked-under-scan.png), [a-r3-resident-max-attempts.png](screenshots/android/a-r3-resident-max-attempts.png) |
| No face in view (no attempt used) | [d-r12-no-face.png](screenshots/desktop/d-r12-no-face.png) | — |
| Printed photo accepted under the shipped policy (known weakness, kept as evidence) | — | [e2e-14-print-accepted-shipped-policy.png](screenshots/android/e2e-14-print-accepted-shipped-policy.png) |

## 5. Device errors

| State | Desktop | Android |
|---|---|---|
| Device unavailable / stream refused | [d-r10-device-error.png](screenshots/desktop/d-r10-device-error.png) (shows the "disconnected" text; see gaps) | — |
| Device disconnected during capture | [e2e-02-device-disconnected.png](screenshots/desktop/e2e-02-device-disconnected.png) | — |
| Camera unavailable | — | [a-r2-camera-unavailable.png](screenshots/android/a-r2-camera-unavailable.png), [e2e-04-camera-unavailable.png](screenshots/android/e2e-04-camera-unavailable.png) |

## 6. Offline and languages

| State | Desktop | Android |
|---|---|---|
| Offline badge "Offline – verification runs on this device" | [d-l1-login-french-offline.png](screenshots/desktop/d-l1-login-french-offline.png) | — (text read from the view tree in the end-to-end run; screen capture failed in airplane mode) |
| Arabic, right-to-left | [d-e1-eod-supervisor-arabic.png](screenshots/desktop/d-e1-eod-supervisor-arabic.png) | — (verified in scratch pictures and the widget test `Arabic: translated label and right-to-left layout`) |
| French | [d-l1-login-french-offline.png](screenshots/desktop/d-l1-login-french-offline.png) | — |

## 7. Missing states (no committed picture)

| State | Desktop | Android | Why missing / how to get it |
|---|---|---|---|
| Checking face liveness | missing | missing | Only frames of real people reach this state long enough; take it with a masked preview (`mask on` in the desktop driver, `masked` replay on Android) |
| Action detected, hold still | missing | missing | Same; the states were observed in logs (`desktop-liveness.md` section 2, Android Logcat) |
| Success on the capture window itself | missing | only the ARC banner | Same |
| Multiple faces | missing | missing | No two-face frame pack in the client runs (`eval/scenarios/other-two-faces.yaml` runs only in the scenario runner) |
| Poor quality (too dark, blurred) | missing | missing | Needs a masked run with the dim or blurred pack |
| Resident positioning | present | missing | Android capture screen goes to checking at once with replay |
| Device unavailable with its own wording | wrong text | not applicable | Desktop maps a refused stream to the "disconnected" message; fix in the services layer |
| Offline badge | present | missing | Screen capture returns a stale frame in airplane mode; use `uiautomator` screenshot or a physical device |
| Supervisor override (resident, desktop only) | missing | not applicable | Run section I2 of the desktop report with snapshots on |
| Packet authentication by face | missing | present | Desktop driver section D3 has no snapshot step |
| Arabic / Hindi capture screen on Android | missing | missing | Pictures exist only in the scratch folder of the module engineer; re-take with the masked replay |
| Diagnostic mode (scores shown to a supervisor) | missing | missing | `mosip.registration.liveness.diagnostic.enable=Y` was not used in any recorded run |
| Configuration updated notice (`liveness.config.updated`) | missing | missing | Text exists but no screen shows it yet |
| Drawn wireframes / Penpot mockups | missing | missing | The index above uses real screens instead |

## 8. Consistency between the clients

- Same message keys and English texts: all 46 desktop `liveness.*` label keys exist in the Android module's
  `values/strings.xml` with the same text (checked on 2026-09-30, `../test-report/android-e2e.md` section 7).
- Same states and meanings: device error without using an attempt, "Step n of k" challenges, generic PAD message,
  "Attempt n of 3", maximum-attempts recovery text, "Supervisor verification" title, offline badge.
- Differences by design: desktop offers a supervisor override after the last resident failure (not after a
  suspected attack), Android blocks; the wrong-person text at sign-in differs ("Face match not found" on desktop,
  "The face does not match this user. Please try again." on Android).
