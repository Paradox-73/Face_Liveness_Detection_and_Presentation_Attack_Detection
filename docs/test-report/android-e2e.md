# Android Registration Client: end-to-end test report (PS04)

Every face liveness flow of the Android Registration Client (ARC) was run on the real app (MOSIP Android
Registration Client 1.2.0.2 with this project's `livenessmanager` module and ARC changes, debug APK) on an
Android 14 x86_64 emulator, against MockSync as the MOSIP server. The camera was replaced by frame packs
(recorded frames played by the app's debug replay); the live webcam was not used.

- **Reference run (run 7):** 2026-09-30, 09:09 to 09:40 (IST), `scripts/demo-android.sh`: **58 checks passed, 0
  failed**. Its results, log, Logcat extracts, frame times and packet evidence are in `docs/test-report/android-e2e/`.
- **Run 8** (09:51 to 10:23, the committed version of the script, which differs from run 7's only in how Logcat
  lines are saved and when the offline screenshot is taken): 48 of 57 checks passed. The 9 failures are one real
  finding: a printed photo passed the passive check under the shipped registration policy (section 3, section 8
  item 1). Its results and attack-section Logcat are in `docs/test-report/android-e2e/run8/`.
- **Re-run at the shipped defaults (2026-10-05, section 11):** 12 checks ran in one pass of `login` and `resident`,
  5 passed and 7 failed; nothing else could be run. The runs above used the replay profile (`PASSIVE_THEN_ACTIVE`,
  8 passive frames, SMILE and BLINK challenges only), not the shipped policy, so the tables in sections 3 and 5 are
  not measurements of the shipped defaults.
- **Committed screenshots** without any face: `docs/ux/screenshots/android/e2e-*.png` (the capture screen's preview is
  kept dark by the replay's `masked` option; ARC screens that show the stored face are not committed).
- **Earlier runs** of the same script (same day) found the problems in section 4; each was fixed and the script run
  again. Run 5 passed 54 of 56 checks (two failures: the script's Back press closed ARC on the Operational Tasks tab);
  run 6 passed 49 of 55 (the replay harness triggered the engine's loop check, section 4; and four offline checks
  after the exception photo did not complete, although the same steps passed when repeated by hand right after. The
  script now checks that Logcat was really cleared and saves a screenshot of every failed check).

## 1. Set-up

| Part | What was used |
|---|---|
| Client | ARC debug APK from `scripts/android-build.sh` (server `http://127.0.0.1:8080`), 326,405,878 bytes, commit `4f80821` plus the changes of this report. Liveness engine `mosip-onnx-default@1.0.0` (the same `liveness-core` and `liveness-onnx` as desktop), ONNX Runtime 1.28.0. |
| Device | Emulator `arc_api34`: Android 14 (API 34) Google APIs x86_64, 10.1-inch 1280x800 tablet profile, 2.5 GB RAM, WHPX, `-gpu host` (NVIDIA RTX 3050 laptop GPU), both cameras `emulated` (no webcam). Face device: the built-in camera as an SBI L0 device (`mosip.registration.liveness.android.device=BUILTIN`), with a device key issued by MockSync's DEVICE CA. |
| Server | MockSync (`tools/mock-sync`), HTTP port 8080 through `adb reverse`; tablet registered as machine `aiqhyqisx9n1` (10002, clientType `ANDROID`); users `officer1` (officer) and `supervisor1` (supervisor), both onboarded on the tablet with pack `s3` through the liveness capture (`onboard_auth`). Liveness keys from `data/mock-sync-defaults.properties`; overrides in force: `active.challenge_types=BLINK,SMILE` (same as the desktop run). |
| Faces | Evaluation frame packs in `run/frame-packs` (never committed; `eval/packs/sources.yaml`), played by ARC's debug replay: `s3` the enrolled person; `s3n` = `s3` with camera noise (passive score below 80) as the low-confidence resident; `s1` a live person who is not enrolled; attack packs `a-s2-print-still` (printed photo), `a-s2-identical` (injected still stream), `a-s2-screen-replay`, `a-s3-screen-replay` (screen replays). `follow <prefix>` makes the replay answer the challenge prompts after 500 ms, like a person. |
| Driver | `scripts/demo-android.sh` (helpers in `scripts/android-ui.sh`): taps through ARC with `adb shell input`, reads the screen with `uiautomator dump`, follows the capture screen and the audits through Logcat, changes MockSync's configuration through `/admin/config` and reads the packets back through `/admin/packets/{name}/liveness`. |
| Machine | Windows 11 laptop, 12 threads, 16 GB; other engineers' work and about 25 IDE Java processes ran at the same time. |

Policies in force (shipped values, as on desktop): resident `registration` PASSIVE_THEN_ACTIVE, passive threshold
80; `login_auth`, `packet_auth`, `eod_auth`, `onboard_auth` PASSIVE_THEN_ACTIVE with passive threshold 95;
`pad.reject_threshold` 20; 2 challenges; 3 attempts; `exception_photo` OFF;
`mosip.registration.android.offline_approval.enable=Y`.

## 2. How to reproduce

```bash
. toolchain/env.sh; use_java21                          # JDK 21, Flutter 3.10.4, ANDROID_HOME
ARC_WEBCAM=emulated bash scripts/android-run-emulator.sh --headless --no-install
bash scripts/android-build.sh && adb install -r -g clients/android/build/app/outputs/flutter-apk/app-debug.apk
bash scripts/demo-android.sh                            # shipped policy (default); --policy replay = the profile of runs 5 to 8
```

Prerequisites (MockSync, machine registration, onboarded users, frame packs) are in `docs/install/android.md`
sections 5 and 8. Every section can run alone (`--sections "resident attacks"`).

## 3. Results

"LVxx" are the audit events `REG-EVT-LVxx`, read from Logcat (`Audit stored ...`, written when ARC's audit
store saved the row) and from the packets' `audit.json`. Evidence paths are relative to `docs/`; `log` is
`test-report/android-e2e/demo-log.txt`, `logcat` the files `test-report/android-e2e/logcat-<section>.txt`.

| # | Flow (PS04) | Steps | Expected | Observed | Evidence |
|---|---|---|---|---|---|
| A1 | Operator password sign-in (online) | `officer1`, password | home screen | home screen after the online login (MockSync `useridpwd`) | log 09:09:33-09:10:20 |
| A2 | Operator face sign-in (`login_auth`) | user ID, "Sign in with face", pack `s3` | liveness, then 1:1 match with the onboarding face, home screen | "Sign in with face" offered; passive pass on 8 frames (LV01, LV02, LV10), `FaceMatch[MATCHED, score=97, threshold=40]`, home screen | `ux/screenshots/android/e2e-01-sign-in-with-face.png`, logcat-login 09:10:57-09:11:00 |
| A3 | A live person who is not enrolled | `officer1`, face `s1` (answers the challenges) | liveness passes, match fails, no sign-in | passive score below 95, two challenges answered (LV03, LV04, LV05), `PASS`, then `FaceMatch[NOT_MATCHED, score=13]`; "The face does not match this user. Please try again." on the password step | `e2e-02-face-no-match.png`, logcat-login 09:11:44-09:11:57 |
| A4 | Screen replay at sign-in | face `a-s3-screen-replay` | refused with a generic message | passive score below 95, "Please smile" asked, no smile: `TIMEOUT` (`CHALLENGE_TIMEOUT`, LV06, LV07): "We could not verify face liveness. Please try again." with Retry, "Attempt 1 of 3"; cancelled | `e2e-03-sign-in-replay-refused.png`, logcat-login 09:12:06-09:12:31 |
| A5 | Resident: camera unavailable, recovery | Lost UIN, fingers and irises marked as exceptions; Face, Scan while the replay folder is missing | device message, no attempt used, recovery with Retry | "The face camera is not available. Check that it is connected and select Retry." (`ERROR/DEVICE`, `attempts=0/3`, LV12 `STREAM_OPEN_FAILED`); folder restored, Retry opened the stream | `e2e-04-camera-unavailable.png`, logcat-resident 09:15:33 |
| A6 | Resident: attack on attempt 1, retry | the "camera" shows `a-s2-screen-replay`; then the real resident `s3n`, Retry | refusal counts one attempt; the next attempt can pass | attempt 1 `FAIL_PAD` (`PAD_SCORE`, LV08): "Face verification could not be completed. Please try again.", "Attempt 1 of 3. Select Retry to try again."; attempt 2 passed | `e2e-05-resident-replay-refused.png`, logcat-resident 09:15:47 |
| A7 | Low-confidence resident, active challenges | `s3n` (noisy camera, passive score below 80) | challenges asked and answered, pass | "Please smile", "Step 1 of 2", then the second challenge; `PASS` on attempt 2 of 3 (LV03, LV04, LV05, LV10); packet: `LIVENESS_METHOD=PASSIVE_ACTIVE`, `LIVENESS_SCORE=75`, `LIVENESS_CHALLENGES=SMILE:OK,SMILE:OK` | `e2e-06-low-confidence-challenge.png`, packet `...034336` |
| A8 | Exception photo | NEXT to the exception photo, Scan | photo taken with the built-in camera, no liveness (policy `exception_photo=OFF`) | `Photo capture ended: ... exception_photo, PASS, reasons=[LIVENESS_NOT_REQUIRED]`; ARC stored it and enabled CONTINUE; preview shown | logcat-resident 09:16:25-09:16:28 |
| A9 | Operator packet authentication (`packet_auth`) | Authentication step, "Authenticate with face" (`s3`), AUTHENTICATE | liveness and match, acknowledgement, officer face in the packet | passive pass, `FaceMatch[MATCHED, score=98]`, "Face verified", AUTHENTICATE without a password, "Registration Acknowledgement"; "Operator face kept for the packet"; packet `...034336` has `officer_bio_cbeff.xml` with `LIVENESS_DECISION=PASS`, `LIVENESS_METHOD=PASSIVE` | `e2e-07-packet-auth-face-verified.png`, packet `...034336` |
| A10 | Attacks on resident capture, maximum attempts | new registration; Scan with `a-s2-print-still`, Cancel; Scan with `a-s2-identical`, Cancel; Scan with `a-s2-screen-replay` | each refused with a generic message; the count continues across screens; after 3 attempts recovery guidance; capture blocked for this registration | print: `CHALLENGE_TIMEOUT` (attempt 1 of 3); injected still: `FAIL_PAD/INTEGRITY` `FROZEN` (attempt 2 of 3); screen replay: `FAIL_PAD` `PAD_SCORE` (attempt 3 of 3), "Face capture could not be completed after 3 attempts. Please ask your supervisor to follow the centre's recovery process." without Retry; LV11 once (`"attempts":"3"`); ARC shows the same text under Scan; a further Scan opened no screen | `e2e-08-max-attempts.png`, `e2e-09-blocked-under-scan.png`, logcat-attacks |
| A11 | Supervisor face sign-in, approval with face (`eod_auth`), upload | `supervisor1` face sign-in; Pending Approval, review, APPROVE each application; AUTHENTICATE, supervisor dialog: face with `a-s3-screen-replay`, Cancel, face with `s3`; Application Upload | replay refused, live face passes, applications approved and uploaded | face sign-in passed; replay: "Supervisor verification" screen, a challenge was asked and timed out ("We could not verify face liveness. Please try again.", "Time is up for this action"); live face passed, `FaceMatch[MATCHED, score=94]`; "Displaying 0 Applications"; upload reached MockSync | `e2e-10-pending-approval.png`, `e2e-11-supervisor-replay-refused.png`, logcat-approval, log 09:23:55-09:26:26 |
| A12 | Configuration change by sync | MockSync `PUT /admin/config policy.registration=ALWAYS_ACTIVE`; Synchronize Data; new registration with `s3` (passive score above 80); packet authenticated by face; key removed and synchronised back | ARC applies the policy after the sync; challenges even for a high passive score; audit of the change | "Sync Completed"; LV15 `{"changed":"mosip.registration.liveness.policy.registration"}`; challenges without a passive pass; packet `...035755`: `LIVENESS_METHOD=ACTIVE`, `LIVENESS_SCORE=100`; after the second sync the shipped policy was back: the next resident capture (A13) was `PASSIVE_ACTIVE` again, not `ACTIVE` | `e2e-12-config-active-challenge.png`, logcat-config, packet `...035755` |
| A13 | Offline operation | `adb reverse` removed (the tablet cannot reach MockSync, as if it were stopped) and airplane mode; password sign-in; registration with `s3n`; packet authentication by face; supervisor face sign-in; approval: `a-s2-screen-replay` refused, then live face; network back; upload | identical liveness behaviour offline; approval without network (`offline_approval.enable=Y`); upload after reconnection | MockSync received no request from the tablet between 09:32:00 and 09:39:20; offline sign-in; capture screen showed "Offline – verification runs on this device" (read from the view tree); resident `PASS` (`PASSIVE_ACTIVE`, score 46); packet authentication and supervisor approval by face passed on the tablet, replay refused; after reconnection the offline packet and the configuration section's packet were uploaded (packet sync at 09:39:47) | `e2e-13-offline-approved.png` (airplane icon), logcat-offline, packet `...040300` |
| A14 | Audit events | Logcat `Audit stored` lines and the packets' `audit.json` | LV events with workflow, session and buckets, no image, no raw score | LV01-LV08, LV10-LV12 and LV15 seen in this run; descriptions such as `{"workflow":"registration","sessionId":"...","reason":"PAD_SCORE"}`, `{"errorCode":"STREAM_OPEN_FAILED"}`, `{"attempts":"3","onMaxFailure":"SUPERVISOR_OVERRIDE"}`; the same rows are in each packet's `audit.json` | logcat-*.txt, `test-report/android-e2e/packets/*.json` (`livenessAudits`) |
| A15 | Packet evidence | MockSync decrypts each uploaded packet (`GET /admin/packets/{name}/liveness`) | `LIVENESS_*` entries in every face BIR, meta info fields | resident and officer face records carry `LIVENESS_DECISION`, `LIVENESS_METHOD`, `LIVENESS_SCORE`, `LIVENESS_ENGINE=mosip-onnx-default@1.0.0`, `LIVENESS_MODEL_SHA`, `LIVENESS_SESSION`, `LIVENESS_DEVICE_LEVEL=L0`, `LIVENESS_BINDING` and, after challenges, `LIVENESS_CHALLENGES`; `packet_meta_info.json` has `livenessDecision` and `livenessMethod` for the resident face | `test-report/android-e2e/packets/*.json` |
| A16 | Per-frame latency | `Liveness frame time` of every attempt | measured and reported | section 5 | `test-report/android-e2e/frame-times.txt` |

The same script run again at 09:51 (`test-report/android-e2e/run8/`, with the final version of the script) passed
48 of 57 checks: the printed photo `a-s2-print-still` was accepted by the passive check (score bucket 80-89) under
the shipped registration policy, so the rest of that section could not refuse anything. This is a real result, not
a script problem; section 8, item 1 gives the repeated measurement.

## 4. Problems found and fixed

| Problem | Where | Fix |
|---|---|---|
| No registration with a biometric exception could be finished on a tablet whose face device is the built-in camera: the exception photo went to a vendor SBI face app that does not exist there. | `BiometricsDetailsApi` | The exception photo also uses the capture screen (workflow `exception_photo`); with the policy `OFF` it takes a signed photo without a liveness check (`session.PhotoCaptureRunner`), as desktop does; tests `FaceCaptureLivenessGateTest.exceptionPhotoIsKeptApartFromTheFaceCapture`, `LivenessServiceTest`. |
| The Biometric Details step never enabled CONTINUE after a face capture of quality 90 with threshold 90 (`quality <= threshold` counted as too low; onboarding accepts `>=`). | upstream `biometric_capture_scan_block_portrait.dart` | `<` instead of `<=` (upstream off-by-one). |
| A new capture screen for the same registration started again at "Attempt 1 of 3", so the last screen could allow more than `max_retries` attempts. | `FaceCaptureLivenessGate`, `LivenessRunner`, `LivenessCaptureRequest` | the screen continues the count (`priorAttempts`); A-A1 to A-A7. |
| The packet had no officer face and no liveness fields in `packet_meta_info.json` (desktop has both). | `RegistrationServiceImpl`, `RegistrationDto`, `OperatorFaceEvidence`, `FaceAuthOutcome` | officer face of a face-authenticated packet written as `officer_bio_cbeff` with its `LIVENESS_*` entries; `livenessDecision` / `livenessMethod` in the `biometrics` meta info; tests `RegistrationServiceImplTest.testAddLivenessMetaInfo`, `FaceAuthenticatorTest`. |
| The success state showed a blue ring over the green outline. | `OvalGuideView` | the finished progress arc takes the success colour. |
| The Offline badge was hidden whenever the replay badge was shown. | `LivenessCaptureActivity` | both labels are shown. |
| The emulator process crashed every few minutes (access violation in generated code, Windows event 1000), mostly on ARC's biometric screens. | emulator, `-gpu swiftshader_indirect` (and `lavapipe`) | `-gpu host`; the emulator then ran from 06:36 to past 10:30 (runs 3 to 8) without a crash; `scripts/android-run-emulator.sh` defaults to `auto` (`docs/install/android.md` section 7). |
| Harness: the prompt-following replay restarted the neutral clip at its first frame after each movement; the engine's loop check then reported the repeated frames as a replayed video (`FAIL_PAD/INTEGRITY`, `LOOP`) in run 6. | `ReplayFrameSource`, `ReplayPromptFollower` (debug only) | the neutral clip continues where it was left (`switchBack`); test `ReplayPromptFollowerTest.neutralPackContinuesWhereItWasLeft`. The engine was right: a clip that restarts is a replay. |
| Harness: Back on the Operational Tasks tab closed ARC; `adb logcat -c` sometimes failed silently. | `scripts/demo-android.sh` | tab-aware navigation; checked Logcat clearing; a screenshot for every failed check. |

## 5. Per-frame latency on the emulator

`session.FrameTimer` measures the time the session spends on each frame it analyses (decoding, face detection,
landmarks, the PAD models while they run, challenge tracking), not the wait for the next camera frame. Per attempt of
the reference run (`frame-times.txt`):

| Kind of attempt | Attempts | Frames per attempt | p50 per frame | p95 per frame | Slowest frame |
|---|---|---|---|---|---|
| Passive check only (sign-in, packet and supervisor authentication, attacks refused early) | 9 | 6-8 | 57-251 ms (7 of 9 between 119 and 190 ms) | 295-998 ms | 998 ms |
| Passive check, then challenges (or a challenge timeout) | 6 | 81-141 | 37-45 ms | 106-374 ms | 1,151 ms |

- Passive frames are the expensive ones: each runs the face detector, Face Mesh and the two MiniFASNet PAD models.
  During challenges the PAD models run only on every n-th frame (`EngineParameter.ACTIVE_PAD_STRIDE`), which is why
  long attempts have a p50 near 40 ms.
- Single frames of up to 1.15 s occurred; these logs do not show which step was slow.
- Conditions: x86_64 emulator (4 virtual CPUs, WHPX), CPU inference through ONNX Runtime 1.28.0 (no NNAPI or GPU),
  while about 25 other Java processes and the emulator's host GPU renderer shared the laptop's 12 threads. The run
  at 09:51 measured a median p50 of 150 ms over 17 attempts (`run8/frame-times.txt`); run-to-run spread is large.
- User-visible time (audit rows LV02 `ms` and LV10 `totalMs`): the passive check took 1.6-2.3 s and a passive pass
  2.2-3.0 s in total; a capture with two challenges took 7.4-12.1 s.
- The per-frame target (p95 at most 20 ms, T-PERF-01 in [../traceability.md](../traceability.md)) is not met on the emulator. The desktop evaluation measured p95
  38-45 ms on the host JVM (`docs/eval/calibration-notes.md`). No physical arm64 tablet was measured.

## 6. APK size

| Build | Size | Notes |
|---|---|---|
| Debug, arm64-v8a + x86_64 (used for the run) | 326,405,878 bytes | Flutter debug kernel 24 MB, unshrunk dex with coverage instrumentation, native libraries stored uncompressed for two CPU types. |
| Release, arm64-v8a only, native libraries uncompressed | 152,997,540 bytes | `scripts/android-build.sh --release --abis arm64-v8a` before the packaging change. |
| **Release, arm64-v8a only (as shipped now)** | **120,856,656 bytes** | Release builds store the native libraries compressed (`androidComponents` block in `android/app/build.gradle`). Content: dex 58.6 MB (upstream builds without shrinking), native libraries 53.1 MB before compression, liveness models 23.6 MB (SFace 19.4 MB of it), ICU data 10.5 MB. |
| Release, x86_64 only | 123,142,904 bytes | Installed on the emulator: ARC started and logged "Liveness engine ready: mosip-onnx-default@1.0.0 in 1069 ms", so the compressed ONNX Runtime library is extracted and loads. |
| Upstream release before this project (four CPU types, no liveness) | 149,398,259 bytes | `docs/install/android.md` section 2. |

A release for one CPU type is smaller than upstream's four-type release although it carries the engine and the
models. Further reduction needs R8 shrinking of the dex files, which upstream does not use (Jackson, Dagger,
Room, BouncyCastle and the ONNX Runtime JNI would need keep rules); not attempted.

## 7. Same messages and states as desktop

- Messages: the capture screen and ARC's Flutter panels resolve the engine's message keys. All 46 message keys the
  desktop client has in English (`labels_en.properties`, `liveness.*`) exist in the Android module's
  `values/strings.xml` with the same text (checked by script on 2026-09-30; the three desktop-only keys are the
  window labels `liveness.ui.busy`, `liveness.ui.close`, `liveness.ui.override`).
- States seen in this run, with the same meaning as on desktop: camera unavailable with Retry and no attempt used;
  challenge prompt with "Step n of 2"; "Action detected"; generic refusals "Face verification could not be
  completed. Please try again." (PAD, integrity) and "We could not verify face liveness. Please try again."
  (challenge timeout); after the third failure "Face capture could not be completed after 3 attempts. Please ask
  your supervisor to follow the centre's recovery process." (resident) and "... Maximum attempts reached. Use
  another sign-in method or contact your supervisor." (authentication); success "Good, face captured
  successfully"; "Supervisor verification" title for approval; "Offline – verification runs on this device".
- Differences that remain (by design, `docs/design/android-liveness.md` 11.5):
  - after the last resident failure desktop offers a supervisor override; Android blocks (the built-in camera
    keeps no face from a failed session);
  - a face that does not match at sign-in shows ARC's own text "The face does not match this user. Please try
    again." (desktop shows the upstream text "Face match not found");
  - Android matches operator faces on the tablet (SFace in the app) against the onboarding face; desktop uses
    its biometric SDK (the same SFace model, `face-match-sdk`).

## 8. Known issues and limits

1. **Printed photos pass the passive check under the shipped registration policy.** Repeated on the emulator's
   capture screen with the shipped `PASSIVE_THEN_ACTIVE` (threshold 80): `a-s3-print-still` was accepted 6 of 6
   times (score bucket 90-100) and `a-s2-print-still` 1 of 6 (80-89; the other 5 had 70-79 and failed the
   challenges). With `policy.registration=ALWAYS_ACTIVE` (debug override on the same screen) all 6 attempts with
   both prints were refused (`CHALLENGE_TIMEOUT`) (`test-report/android-e2e/print-attack-trials.txt`). This matches
   the desktop evaluation (`docs/eval/calibration-notes.md` 3.2: `a-s3-print-still` accepted passively, score 99)
   and the desktop report's known issue 1. A12 shows that the stricter policy reaches the tablets with a normal sync;
   choosing it is a policy decision (about 7 s more per capture).
2. **The stored face can show the challenge expression.** The engine keeps offering capture candidates while the
   challenges run (`DefaultLivenessSession.onActiveFrame` → `offerCandidate`), so after a smile challenge the stored
   resident photo was a smiling frame (seen in a development run on 2026-09-30). The engine is shared with desktop
   (`liveness/liveness-core`, not changed here); a fix is to stop replacing a neutral frontal candidate during
   challenges.
3. **Only SMILE challenges on the emulator.** BLINK needs a minimum analysed frame rate
   (`ChallengeScheduler.eligible`); on the loaded emulator it was usually not eligible, so both challenges were
   SMILE (`SMILE:OK,SMILE:OK`); one earlier run got `SMILE:OK,BLINK:OK`. Desktop runs got BLINK and SMILE.
4. **No supervisor override for residents on Android** (deviation, `docs/design/android-liveness.md` 11.5): the
   built-in camera keeps no face from a failed session. The LV11 description still reports the configured
   `onMaxFailure` (`SUPERVISOR_OVERRIDE`); Android shows the recovery message and blocks the capture.
5. **Emulator only.** No physical tablet was used; the CameraX path was not run (automated runs must not use the
   webcam; the replay path shares everything after the frame source). The built-in device key was provisioned from
   MockSync's DEVICE CA (`scripts/android-liveness-device-cert.sh`).
6. **Offline is simulated at the tablet**, by removing `adb reverse` (MockSync unreachable, exactly as if it were
   stopped) and switching on airplane mode. MockSync itself kept running because the desktop engineers share it.
7. **No screenshot of the Offline badge.** In airplane mode `adb exec-out screencap` returned the previous screen
   while the capture screen was open (three tries, 09:41-09:46); the badge text was read from the view tree by
   check A-O2. Capture-screen screenshots taken online were correct.
8. **The Lost UIN steps of the demo tap fixed points** on the finger and eye pictures of the exception screens, so the
   demo expects the 1280x800 tablet profile.
9. ARC uploads approved packets by itself during a manual sync; the demo counts only the packets that arrive while
   its own upload runs, so "n new" can be smaller than the number of approved packets.
10. A face that does not match at sign-in counts as a failed sign-in (`recordFailedLoginAttempt`), like a wrong
    password; after `mosip.registration.invalid_login_count` failures the account is locked for
    `invalid_login_time` minutes (default 2).
11. The packet's `audit.json` holds every audit row since the previous packet (ARC's `savedPoint`), so it also shows
    LV rows of other workflows (sign-ins, attacks of an abandoned registration).
12. The acknowledgement page is a web view that renders after the screenshot is taken, so the demo's
    acknowledgement screenshots are blank; the check uses the "Registration Acknowledgement" title.

## 9. Unit tests run with these changes

| Module | Command (in `clients/android/android`, JDK 21) | Result |
|---|---|---|
| `livenessmanager` | `./gradlew :livenessmanager:testDebugUnitTest` | 82 tests, 0 failures (new: `ReplayPromptFollowerTest` 5, `FrameTimerTest` 3; extended: `LivenessServiceTest`, `FaceAuthenticatorTest`) |
| `app` | `./gradlew :app:testDebugUnitTest` | 27 tests, 0 failures (extended: `FaceCaptureLivenessGateTest`, `LivenessCaptureLauncherTest`, `LivenessApiTest`) |
| `clientmanager` | `./gradlew :clientmanager:testDebugUnitTest` | 1186 tests, 0 failures (new: `RegistrationServiceImplTest.testAddLivenessMetaInfo`); `scripts/android-build.sh --test-only`, 2026-09-30 10:3x. Flutter: `flutter test test/liveness/` 13 passed. |

## 10. Evidence files

| File | Content |
|---|---|
| `docs/test-report/android-e2e/results.txt` | every check of the reference run with its section |
| `docs/test-report/android-e2e/demo-log.txt` | steps, replay selections, checks and screenshot names of the reference run |
| `docs/test-report/android-e2e/logcat-<section>.txt` | Logcat of the tags `Liveness`, `AuthenticationApi`, `RegistrationServiceImpl`: screen states, attempts, audits as stored, face matches, frame times (no images, no raw scores) |
| `docs/test-report/android-e2e/frame-times.txt` | per-frame processing time of every liveness attempt |
| `docs/test-report/android-e2e/packets/*.liveness.json` | liveness evidence of the packets uploaded in the reference run, as decrypted by MockSync |
| `docs/test-report/android-e2e/run8/` | results, log, attack-section Logcat and frame times of run 8 |
| `docs/test-report/android-e2e/print-attack-trials.txt` | 18 repeated print attacks on the capture screen, shipped and `ALWAYS_ACTIVE` registration policy |
| `docs/ux/screenshots/android/e2e-*.png` | screenshots of runs 7 and 8 (capture screen masked; no face); `e2e-14` is run 8's accepted print |

## 11. Re-run at the shipped defaults (2026-10-05)

`scripts/demo-android.sh` now runs at the policy MockSync ships (`--policy shipped`, the default): registration and
all authentication workflows `ALWAYS_ACTIVE`, 30 passive frames, two challenges drawn from the five actions BLINK,
SMILE, TURN_LEFT, TURN_RIGHT, LOOK_UP, three attempts. `--policy replay` applies the earlier profile of runs 5 to 8.
Before the run two leftover MockSync overrides (`active.challenge_types=BLINK,SMILE`, `registration.max_retries=3`)
were removed, so the pool and limits were the shipped ones. Client: debug APK built 2026-10-05 from the current tree
(x86_64, `scripts/android-build.sh --abis x86_64`), emulator `arc_api34` headless, `ARC_WEBCAM=emulated`.

| Flow | Result (2026-10-05) | Measured detail |
|---|---|---|
| Profile step (A-P0) | PASS | no policy override; tablet synchronised |
| A1 operator password sign-in | PASS (A-L1) | home screen |
| A2 "Sign in with face" offered | PASS (A-L2) | |
| A2 operator face sign-in, pack `s3` | FAIL (A-L3, A-L4, A-L5) | attempt 1: passive pass (bucket 90-100), SMILE answered, then TURN_LEFT asked; the pack has no matching footage and the clip repeated: `FAIL_PAD/INTEGRITY`, `LOOP` (LV08). Attempt 2: TURN_LEFT drawn again, `WRONG_ACTION` (LV06). The 3 attempts were used up. |
| A3 live person not enrolled, pack `s1` | FAIL (A-L6, A-L7, A-L8) | passive bucket 60-69, then TURN_RIGHT / TURN_LEFT asked; `s1` has no turn footage: `CHALLENGE_TIMEOUT` on attempts 1, 2, 3, then `MAX_ATTEMPTS`. The no-match step was never reached. |
| A4 screen replay at sign-in | FAIL (A-L9), not a verdict | the sign-in attempts of the earlier steps were already used up ("Face authentication not started: attempts used up for login_auth"), so no attack attempt ran. The generic refusal text was shown (A-L10 PASS). |
| A5 resident, Lost UIN to the biometrics grid | PASS (A-R0) | |
| A5 to A16 (resident capture, attacks, packet authentication, approval, upload, configuration change, offline, override, latency) | NOT RUN | the emulator process exited at about 03:46 during the resident section, with no check of that section run; see below |

Totals of the run: 12 checks, 5 passed, 7 failed. The 7 failures come from two causes: the packs have no footage
for the turn actions the shipped pool asks for (A-L3 to A-L8), and the attempts spent on them (A-L9). None of them
shows a faulty refusal of an attack.

What could not run, and why:

- **Genuine turn and look-up footage does not exist** (WP04: no LOOK_UP footage of any subject; the enrolled face
  `s3` has main, blink, smile and one left turn that the engine rejects as `WRONG_ACTION`, no right turn; `s1` has no
  turn at all). At the shipped pool, a replayed genuine person passes only when both drawn actions are ones the pack
  holds, about 3 of 10 pairs for `s3`. Recording `s3-turn-right` and `s3-look-up` is the missing input; no clip was
  synthesised from stills. Every flow that needs a genuine pass (A2, A7, A9, A11, A12, A13) is therefore not
  reproducible at the shipped defaults with the current packs.
- **The emulator stopped twice on the biometrics screens** (`adb: device 'emulator-5554' not found`, 03:46 in the first
  stretch and about 04:03 in the second, which was to run the `attacks` section, with the renderer in `-gpu auto`
  mode; section 4 records the same crash and the `-gpu host` remedy). The second stretch ended after the profile
  step with no attack check run. A third stretch with `ARC_EMULATOR_GPU=host` was not started: the emulator lock
  directory `toolchain/locks/emulator` was left behind when the second run's processes were stopped, and removing
  it was refused, so the lock was not taken again.
- The emulator is not running after these runs. Rows A5 to A16 of section 3 keep the values of run 7 (replay
  profile, 2026-09-30) and are not shipped-default measurements.

Raw output of the runs (outside the repository): `run/demo/android-20261005-wp20a/` (results, log, Logcat of the
login section, screenshots) and `run/demo/android-20261005-wp20b/`.

### 11.1 Emulator session of 2026-10-05, 21:45-21:58 (IST)

- Debug APK rebuilt from the current tree (`scripts/android-build.sh --abis x86_64`, with the exposure correction and
  screen-pattern check in the engine); emulator started with `ARC_EMULATOR_GPU=host` and `ARC_WEBCAM=emulated`
  (`scripts/android-run-emulator.sh --headless`). It ran for the whole session without a crash.
- MockSync was not reachable in this session (its HTTPS port had stopped answering, see
  [desktop-e2e.md](desktop-e2e.md); after a restart attempt nothing answered on 8080), so `scripts/demo-android.sh`
  could not start (it checks MockSync first). The scripted demo at the shipped defaults is still to run.
- What ran without the server, in airplane mode (the client works offline):
  - password sign-in of officer1 from the stored credentials;
  - "Sign in with face" with an empty replay pack: the positioning state (amber oval and banner "No face detected.
    Keep your face within the capture area.", progress bar) and the badge "Offline – verification runs on this
    device"; the attempt ended without using up an attempt ("Attempt 1 of 3");
  - Settings, Device Settings: the "Import liveness model" button with "Model in use: version 1.3.0".
- Pictures: [a-p1-positioning-offline.png](../ux/screenshots/android/a-p1-positioning-offline.png),
  [a-p2-no-face-offline.png](../ux/screenshots/android/a-p2-no-face-offline.png),
  [a-s1-model-import-button.png](../ux/screenshots/android/a-s1-model-import-button.png). The capture screen shows the
  masked replay (black preview), never a face.
