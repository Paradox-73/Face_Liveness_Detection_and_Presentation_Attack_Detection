# Demo video script (4:45)

Length 4:45 (hard limit 5:00), 1080p, English voice-over with burned-in captions. No institution names or logos on
screen: hide browser bookmarks, the desktop wallpaper, laptop stickers and the window title bar where it shows a
user or machine name. Team alias "Badmosip" only.

## Ground rules

- **Real, unedited attempts.** Cuts only between attempts, never inside one. Every attack shown carries an on-screen
  label with its measured rate from the automated suite (for example "synthetic print moved in 2-D, registration: accepted 8/8
  transactions with passive first, 0/8 with the shipped defaults"; the adaptive screen replay is labelled "accepted 4/8 before the screen-pattern check, 0/8 now; synthetic screen").
- **Label the source of every take** in the corner: `LIVE WEBCAM` (a real person in front of the camera) or
  `REPLAYED FRAMES` (a recorded frame pack played by the mock device or the Android debug replay).
- **Faces:** only consenting team members appear on camera. Attack artefacts (prints, phone photos, replay videos)
  are made only from the face of the team member who holds them. The committed repository never contains these
  images; the video file lives outside git.
- **Numbers:** only figures from `docs/test-report/README.md` and `docs/eval/results/20261014-shipped/README.md`; the limitation line in segment 11 stays on screen for
  at least 4 seconds.
- Start every recording day from the golden client state (`bash scripts/demo-desktop.sh --restore-golden` restores
  the onboarded desktop client) and check MockSync, MockMDS and the emulator are running.

## What must be recorded live and what can be replayed

| Segment | Live with a real person | Replayed (scripted, frame packs) | Why |
|---|---|---|---|
| 1 Hook | yes: team member holds a printed photo of themself | — | Judges must see a physical artefact accepted when liveness is off |
| 3 Resident, live person | yes | fallback: `demo-desktop.sh` D3 | Shows real speed and the prompts on a real face |
| 4 Low confidence, challenges | yes (backlit or dim room) | fallback: D4 (`s3n` noisy pack) | A real blink and smile answering random prompts is the core claim |
| 5 Attacks | yes: print and phone-screen replay held to the webcam | injected still stream (D6) is replay only: it is an injection, not something a person can hold | Physical attacks must be physical |
| 6 Operator and supervisor | supervisor pass live | wrong-person refusal (D11) and EOD replay refusal (D7) replayed | Wrong-person needs a second face that is not enrolled; replay keeps it to consenting material |
| 7 Offline | — | D10 | Network cut is scripted and repeatable |
| 8 Android | live if a physical phone is available | otherwise `demo-android.sh` on the emulator (A2, A7, A10, A11) | The emulator cannot use the webcam in scripted runs |
| 9 Configuration by sync | — | D8 | Server-side change and sync are scripted |
| 10 Device faults | — | D2, D9, A5 | Faults are injected through the mock device |
| 11 Evidence and results | — | screen recording of files and terminal | |

## Script

| Time | Segment | Shots (what is on screen) | Voice-over | PS04 items |
|---|---|---|---|---|
| 0:00-0:15 | 1. Hook | Desktop client, resident face capture. MockSync config shows `mosip.registration.liveness.enable=N`. A team member holds a printed photo of their own face to the webcam; the client captures it. Cut to the MockSync terminal: `enable` removed (back to Y), Synchronize Data. Label `LIVE WEBCAM`. | "Today, a printed photo can become someone's identity in a registration centre. We switched on one setting." | problem; 8.1 |
| 0:15-0:35 | 2. How it works | Animated architecture diagram (from `docs/pitch/deck-outline.md` slide 3): device → common device interface → five layers → service gate → packet and audit. Highlight "offline" and "both clients". | "One liveness engine runs offline inside both MOSIP registration clients. Passive check first, random challenges when needed, and a signed capture that must match the live session." | 1.7, O3-O9 |
| 0:35-1:00 | 3. Resident, live person | Desktop resident capture: oval guide, "Checking face liveness…", then two random prompts (for example "Please smile", "Step 1 of 2", "Action detected"), "Hold still", "Good, face captured successfully"; window closes by itself; biometrics screen shows the face. Small inset: timer (the median accepted registration attempt is about 11 s of stream: 2 s of passive check, then two actions; `docs/eval/results/20261014-latency/README.md`). Label `LIVE WEBCAM`. | "A live resident passes in about ten seconds: the client checks the face for two seconds, asks for two random actions, and the capture continues automatically." | 2.1-2.4, 5.1, UX1-UX2 |
| 1:00-1:30 | 4. Low confidence | Same screen, team member backlit by a window, a second take with different prompts. "Please blink" with the eye icon, "Step 1 of 2", "Action detected", "Please smile", "Hold still", success. Caption: "challenge order drawn at random each time". | "The client chooses the actions at random on every capture. The resident never picks the action, and a recording cannot know the order in advance." | 4.1-4.7, O10-O11, UX3-UX4 |
| 1:30-2:05 | 5. Attacks and retries | (a) Printed photo held to the webcam: "Please blink" → "We could not verify face liveness. Please try again.", "Attempt 1 of 3. Select Retry to try again." (b) Phone showing a video of the team member: "Face verification could not be completed. Please try again.", "Attempt 2 of 3. Select Retry to try again." (c) `REPLAYED FRAMES`: injected still stream refused, then "Face capture could not be completed after 3 attempts. Please ask your supervisor to follow the centre's recovery process." Rate labels on each. | "Attacks get the same short message, with no detail an attacker could learn from. Attempts are counted across retries, and after the limit the centre's recovery process takes over." | 3.1-3.3, 5.2-5.5, 9.8-9.11, UX5-UX7 |
| 2:05-2:35 | 6. Operator and supervisor | (a) `REPLAYED FRAMES`: operator face login, liveness pass, face match, home screen. (b) `REPLAYED FRAMES`: same operator ID with a different person's frame pack: "Face match not found". (c) End-of-day approval: a screen replay of the supervisor refused; then the supervisor, `LIVE WEBCAM`, passes; packets approved and uploaded. | "Operators and supervisors go through the same checks, challenges included. Liveness proves a live person; the face match proves it is the right one." | 6.1-6.6, 7.1-7.5, OP7, UX8 |
| 2:35-2:55 | 7. Offline | `REPLAYED FRAMES`: the client header shows "System Offline"; the liveness window shows "Offline – verification runs on this device"; login, capture and approval pass; MockSync request log stays empty (side-by-side terminal). | "Network state never decides whether liveness is enforced. Everything runs on the machine, and the packets upload later." | 2.5, 6.6, 7.6, OF1-OF3 |
| 2:55-3:30 | 8. Android | Android tablet (physical phone `LIVE WEBCAM`, or the emulator `REPLAYED FRAMES`): "Sign in with face" → home; new registration, "Please smile", "Step 1 of 2", success banner; screen replay refused and the maximum-attempts message; supervisor approval by face; the capture screen in Arabic, right to left. Split screen with the desktop showing the same prompt text. | "The Android client runs the same engine, with the same settings, messages and audit events, and it works offline for supervisor approval too." | O2, UX8, consistency |
| 3:30-3:50 | 9. Configuration by sync | `REPLAYED FRAMES` (desktop flow 60): MockSync `PUT /admin/config` sets `policy.registration=PASSIVE_THEN_ACTIVE` (the passive-first flow of the problem statement; keeping the existing overrides); client "Synchronize Data"; a genuine replayed resident passes passively (audit LV10 method PASSIVE). Then the override is deleted and the client syncs back to the shipped default `ALWAYS_ACTIVE`; the rigid-motion print is asked to blink and is refused. Audit row LV15 names the key. | "Every setting comes through MOSIP's normal configuration sync, per workflow. Here registration is switched to the passive-first flow and back to the shipped always-challenge default, and with that default the same print is refused." | 8.1-8.9, C11, OF4 |
| 3:50-4:10 | 10. Device faults | `REPLAYED FRAMES`: mock device disconnect during capture → "The face camera was disconnected. Reconnect it and select Retry." (no attempt used) → Retry passes. L1 device: digital ID accepted, audit shows certification L1. Android: "The face camera is not available…". | "Device problems get their own message and never cost the resident an attempt. The client works with L0 and L1 devices through the common device interface." | 1.1, 1.5-1.7, 9.1-9.3, B1 |
| 4:10-4:30 | 11. Evidence and results | Terminal: audit rows `REG-EVT-LV01…LV17` with score buckets, no images. `tools/mock-sync/scripts/show-packet.sh`: `LIVENESS_DECISION`, `LIVENESS_METHOD`, `LIVENESS_CHALLENGES` in the uploaded packet. Then a results card: 2,691 tests passed on 2026-10-14 (about 1,330 written by the team, the rest upstream), desktop 41/41 and Android 58/58 (earlier defaults), attacks per transaction (shipped defaults: 0 of 226 accepted; passive first: 100 of 226; synthetic, `docs/eval/results/20261014-shipped/README.md`). On-screen line: "Synthetic attacks, three people from open clips: regression results, not an ISO/IEC 30107-3 evaluation." | "Details go to audit and into the packet, not to the screen. We test every flow on both real clients, and we publish where the engine is weak, and what we changed because of it." | E20, UX6, 3.5, B2-B3 |
| 4:30-4:45 | 12. Close | Deck slide 7 (next steps) with the repository path and "Badmosip". | "Liveness where MOSIP captures faces: both clients, offline, bound to the signed capture. Next: the volunteer evaluation and upstream contribution." | — |

## Recording checklist

| Step | Command or action |
|---|---|
| Build and restore the desktop client | `bash scripts/desktop-build.sh --mocksync`, then `bash scripts/demo-desktop.sh --restore-golden --policy recorded` for the full replayed run (69 of 71 checks on 2026-10-15). For one segment, write a flow file next to the others with `include 00-start.flow`, `include 10-login.flow`, `section <nn>-<name>.flow` and `exit`, and pass it with `--flow` (`docs/manual-test-checklist.md` `MT-VID-03`) |
| Live webcam on desktop | `curl -s -X POST http://127.0.0.1:4501/admin/source -d '{"type":"Face","source":"FFMPEG","camera":"<webcam name>","fps":10}'` (list names with `ffmpeg -hide_banner -list_devices true -f dshow -i dummy`); switch back to a frame pack afterwards |
| Liveness off for the hook | `PUT /admin/config` on MockSync with `mosip.registration.liveness.enable=N` plus the existing overrides; Synchronize Data in the client; undo after the take |
| Android replayed segments | `bash scripts/android-run-emulator.sh --headless --no-install`, then `bash scripts/demo-android.sh --sections "login resident attacks approval"` with screen recording (`adb shell screenrecord`) |
| Captions | burn in the voice-over text above; add the `LIVE WEBCAM` / `REPLAYED FRAMES` label and rate labels in post |
| Final check | watch the whole cut for faces of anyone who did not consent, institution names or logos, and numbers that differ from `docs/test-report/README.md` |
