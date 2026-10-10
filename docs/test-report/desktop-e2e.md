# Desktop Registration Client: end-to-end test report (PS04)

Every face liveness flow of the desktop Registration Client was run on the real client (MOSIP Registration Client
1.2.0.2 with this project's liveness integration), against MockSync as the MOSIP server and MockMDS as the face,
finger and iris devices. The camera was replaced by frame packs (recorded frames played by MockMDS).

- **Reference run:** 2026-09-30, 04:49 to 04:59 (IST), `scripts/demo-desktop.sh --restore-golden`:
  **41 checks passed, 0 failed.** Output folder `run/demo/desktop-run4` (outside git).
- **Committed evidence:** `docs/test-report/desktop-e2e/` (check results, driver log without screen-state lines,
  liveness evidence of five uploaded packets) and masked screenshots in `docs/ux/screenshots/desktop/e2e-*.png`.
- **Earlier runs of the same script** found several of the problems in section 4 (run 1: stopped in the attack section; run 2:
  34 passed, 2 failed; run 3: 35 passed, 4 failed). Each was fixed and the script run again from the same
  starting state.
- Two flows were run step by step with the same driver in interactive mode rather than by the script: first
  operator onboarding and supervisor override (section 3.2).

**Status on 2026-10-05: this report describes the code and defaults of 2026-09-30.** The 41 checks ran with all
six workflows (except the exception photo, which is off) set to `PASSIVE_THEN_ACTIVE`: a high passive score passed
without challenges. Since then the shipped
defaults changed: `registration`, `login_auth`, `onboard_auth` and the three supervisor workflows (`packet_auth`,
`exception_auth`, `eod_auth`) are `ALWAYS_ACTIVE` (challenges on every capture), the retry counters survive Cancel, reopened windows and restarts, an override needs a different second supervisor's face check and a checked photo, the desktop run uses the
shared session driver of `liveness-core`, and the model bundle is 1.3.0. The flow descriptions below for D1, D3, D5, D7
and D11 (a pass "without challenges" or a passive pass) and the 41 of 41 result are therefore **not a result at the
shipped defaults**. A re-run of `scripts/demo-desktop.sh` at the shipped defaults is pending: it needs a desktop
window (the team has not run it since the change) and recordings of the enrolled face turning right and looking up
(the enrolled pack `s3` has only main, blink, smile and turn-left; with the five-action pool and two challenges it can
answer about 3 of 10 challenge pairs, so those flows would log "FOLLOW no pack for turn_right/look_up"). Flow 60 of the
demo script was rewritten for the PS04 passive-first flow (it switches `policy.registration` to
`PASSIVE_THEN_ACTIVE`, then back) and has not been run either.

**Attempt of 2026-10-05, 21:26-21:32 (IST), at the shipped defaults** (output `run/demo/desktop-wp23-screens`, outside
git):

- The run layout `run/desktop` was rebuilt with `scripts/desktop-build.sh --mocksync` (database and keys kept); it now
  carries the engine of this commit and the signed model bundle 1.3.0 (before, its models jar held bundle 1.0.1,
  which the minimum version 1.3.0 refuses).
- The client started from the golden snapshot (`--restore-golden`), and officer1's face sign-in (`login_auth`,
  challenges on every capture) passed on attempt 1 with the frame pack `s3`.
- The next step, Synchronize Data, failed: MockSync's HTTPS port 8443 accepted connections but never completed the
  TLS handshake (the client logged "No Internet Access", read time-out on `/v1/syncdata/actuator/health`; port 8080
  still answered). The run stopped there. The full demo, the new section `tools/mock-sync/e2e/flows/95-screens.flow`
  (checking, challenge, action detected, hold still, success, two faces, supervisor override refused for the
  logged-in officer and granted for supervisor1, with snapshots) and the screen-sized window check are still to run
  once MockSync answers on 8443 (`tools/mock-sync/scripts/run-mock-sync.sh`).

**Attempt of 2026-10-05, 23:33-23:50 (IST), MockSync answering on 8443 and 8080** (three runs of
`scripts/demo-desktop.sh --restore-golden`, outputs `run/demo/desktop-wp24`, `-wp24b`, `-wp24c`, outside git; summaries in
`test-report/desktop-e2e/shipped-20261005/`):

- `run/desktop` was rebuilt again with `scripts/desktop-build.sh --mocksync` (Maven 396 s; `lib/` replaced, `db`, keys
  and logs kept) and carries the engine and signed model bundle 1.3.0 of commit `3f0b5a3`. Synchronise Data and the
  startup sync worked ("Machine is ONLINE", sync completed in 12 s).
- **Result: 0 checks passed, 1 failed, the demo aborted at the first step.** The operator face sign-in (`login_auth`,
  challenges on every capture) used up its 3 attempts in all three runs:
  - Run 1, shipped pool of five actions: the second challenge drawn was TURN_RIGHT or LOOK_UP, for which no recorded
    footage of the enrolled face `s3` exists (`FOLLOW no pack for turn_right`, `look_up`). Not a defect of the client.
  - Runs 2 and 3, `active.challenge_types` set to `BLINK,SMILE,TURN_LEFT` and then `BLINK,SMILE` (a restriction to
    actions with footage; removed afterwards): attempts 1 and 2 ended with `FAILED(INTEGRITY)`, reason `LOOP`
    (audit LV08 `{"workflow":"login_auth","reason":"LOOP"}`, three rows, `audit-lv08-login.txt`). The cause is the
    replay, not a person: the frame packs are 4 to 6 s clips that MockMDS repeats, and the packs switch back to
    `s3-main` after each challenge, so the same frames reappear inside one session. Since bundle 1.3.0 the signed
    loop window is 300 frames (WP06), which catches that; the window of 30 frames did not. A genuine person does not
    repeat frames.
- **Nothing after the login ran**: not the 95-screens flow (checking, action detected, hold still, success, two
  faces, supervisor override refused and granted), not the screen-size check, not any other section. D1 to D13 above
  therefore stay the results of 2026-09-30 and are not measurements of the shipped defaults.
- **No new desktop screenshots were taken**: the existing `d-*` and `e2e-*` pictures are unchanged, the mis-captioned
  and "after 1 attempts" ones are not yet replaced.
- **What is missing to run it**: recordings of the enrolled face that are long enough not to repeat inside a session
  (about 10 to 20 s of neutral face plus each action, none of them looped) and the turn-right and look-up clips;
  a real person on the webcam route would also do. No clip was made by repeating or editing stills.

**Run of 2026-10-10, 23:22-23:28 (IST), commit `7ddb454`** (after the controller, Android coordinator, model bundle
module, policy-key and driver-split refactors; `scripts/demo-desktop.sh --restore-golden`, MockSync started by the
script, output `run/demo/desktop-20261010-232240`, outside git):

- **Result: 0 passed, 1 failed, the demo aborted at the first step**, the same as on 2026-10-05: officer1's face sign-in
  (`login_auth`) used its 3 attempts. The driver log shows attempt 1 refused after the BLINK/SMILE answers and
  `FOLLOW no pack for look_up` before attempt 2 (no recorded look-up footage of `s3`).
- The refactored split driver compiled, started the client and MockMDS, switched face sources, read the failure text
  and wrote the snapshots; the client's face sign-in path (shared `BaseController` entry points) reached the engine
  and refused as before. Nothing after the sign-in ran, so the other flows are not measured.
- `stdout.log` shows `ClassNotFoundException: io.mosip.registration.config.DerbySlf4jBridge` at start; it also
  appears in the older runs `desktop-run1` to `desktop-run4`.

## 1. Set-up

| Part | What was used |
|---|---|
| Client | `scripts/desktop-build.sh --mocksync` (hostname `localhost:8443`, environment LOCAL), run folder `run/desktop`, bundled Zulu 11.0.8 FX runtime. `lib/` holds `liveness-core`, `liveness-onnx`, `liveness-onnx-...-models` (signed bundle), ONNX Runtime 1.20.0, `face-match-sdk` (SFace, FACE matcher) and MOSIP's `mock-sdk.jar` (finger, iris). |
| Server | MockSync (`tools/mock-sync`), machine `batcomputer` registered with its TPM keys; users `officer1` (officer) and `supervisor1` (supervisor). Liveness keys from `data/mock-sync-defaults.properties`; overrides in force: `active.challenge_types=BLINK,SMILE` (head turns fail for genuine people, engine proposal E1 in `docs/eval/calibration-notes.md`) and `operator.onboarding.bioattributes` = ten fingers and face. FACE matcher `io.mosip.liveness.facematch.SFaceBioApi`, threshold 40. |
| Devices | MockMDS (`devices/mock-mds`, `scripts/mockmds-run.sh`) on port 4501, face device L0 (L1 in D9), frame source switched per step through `/admin/source`, faults through `/admin/fault`. |
| Faces | Evaluation frame packs in `run/frame-packs` (never committed; `eval/packs/sources.yaml`): `s3` enrolled for both users; `s2` resident with a high passive score; `s3n` = `s3` with camera noise (`tools/mock-sync/e2e/NoisyPack.java`; session passive score 74 in the reference run) as the low-confidence resident; `s1` a live person who is not enrolled; attack packs `a-s2-*`, `a-s3-*`. |
| Starting state | Golden snapshot `run/golden/desktop` (client database with both users onboarded), restored by `--restore-golden`. |
| Driver | `tools/mock-sync/e2e/DesktopFlowDriver.java` (entry point; the flow interpreter and the JavaFX helpers are the other `Flow*.java`, `FxNodes.java` and `PromptFollower.java` files in that folder, compiled by `scripts/demo-desktop.sh`) runs the client in its own JVM and executes `tools/mock-sync/e2e/flows/demo-desktop.flow`. It fires the client's own buttons (no operating-system input), so it works on a locked screen. Snapshots hide the camera preview, captured faces and HTML previews (`mask on`). |
| Machine | Windows 11 laptop, 12 threads, 16 GB; other engineers' builds and an Android emulator ran at the same time. |

Policies in force during the reference run (the shipped values of 2026-09-30, not today's): resident `registration` PASSIVE_THEN_ACTIVE, passive threshold 80; `login_auth`,
`packet_auth`, `exception_auth`, `eod_auth` and `onboard_auth` PASSIVE_THEN_ACTIVE with passive threshold 95;
`pad.reject_threshold` 20; 2 challenges; 3 attempts; on the last failure SUPERVISOR_OVERRIDE for residents and BLOCK
for operators; `exception_photo` OFF.

## 2. How to reproduce

```bash
export JAVA_HOME="$JAVA11_HOME"                      # JDK 11 with JavaFX and javac (docs/install/README.md section 1)
bash scripts/desktop-build.sh --mocksync             # once
bash scripts/demo-desktop.sh --restore-golden        # about 10 minutes; results in run/demo/desktop-<time>/results.txt
```

The script starts MockSync and MockMDS when they are not running, makes the `s3n` packs when missing, runs the flow
and saves the liveness evidence of every packet MockSync holds (`packets/*.liveness.json`). Each flow file in
`tools/mock-sync/e2e/flows/` covers one row group below and can be run on its own after `00-start` and `10-login`.

## 3. Results

### 3.1 Scripted run (reference run, all checks passed)

"LVxx" are the client audit events `REG-EVT-LVxx` read from the client database (`AUDIT.APP_AUDIT_LOG`). Evidence
paths are relative to `docs/`; `log` is `test-report/desktop-e2e/driver-summary.log`.

| # | Flow (PS04) | Steps | Expected | Observed | Evidence |
|---|---|---|---|---|---|
| D1 | Operator face login (`login_auth`) | user ID, password, face `s3` | liveness, then 1:1 match with the enrolled face, home screen | liveness passed on attempt 1, SFace match score 100 against threshold 40 (`BioProviderImpl_V_0_9` analytics in the client log), home screen | log 04:50:05-04:50:10 |
| D2 | Device disconnect and recovery (resident) | MockMDS `DISCONNECT` after 6 frames during the face check; fault cleared; Retry | device message, no attempt used, recovery | "The face camera was disconnected. Reconnect it and select Retry." with "Attempt 1 of 3" (a device error uses no attempt); after Retry the passive check passed at once | `ux/screenshots/desktop/e2e-02-device-disconnected.png`, log 04:50:46 |
| D3 | Resident face capture, passive pass; operator packet authentication (`packet_auth`) | resident `s2`; preview; officer password and face | pass without challenges; packet authentication with liveness; acknowledgement | acknowledgement shown; packet `...232013`: resident `LIVENESS_METHOD=PASSIVE`, `LIVENESS_SCORE=84`, officer `PASSIVE`, score 100, both `LIVENESS_DECISION=PASS`, `LIVENESS_BINDING=90-100` | `test-report/desktop-e2e/packets/10001100010000420260929232013.liveness.json` |
| D4 | Low-confidence resident, active challenges | resident `s3n` (noisy camera); the frame source follows the prompts | passive score below 80, random challenges, pass | prompts "Please blink" / "Please smile" (step 1 of 2), pass on attempt 1; packet: `LIVENESS_METHOD=PASSIVE_ACTIVE`, `LIVENESS_SCORE=74`, `LIVENESS_CHALLENGES=SMILE:OK,BLINK:OK` | `e2e-03-low-confidence-challenge.png`, packet `...232100` |
| D5 | Exception photo and reviewer authentication (`exception_auth`) | left little finger marked missing; exception photo; after the officer, `supervisor1` password and face | exception photo without liveness (policy OFF); reviewer face with liveness | exception photo captured directly; reviewer authentication passed; packet has a third face record (`supervisor_bio_cbeff.xml`) with `LIVENESS_DECISION=PASS` | `e2e-04-reviewer-authentication.png`, packet `...232100` |
| D6 | Attacks on resident capture | `a-s2-print-still`, then `a-s2-identical` (injected still stream), then `a-s2-screen-replay` | each refused with a generic message; after 3 attempts recovery guidance | print: "We could not verify face liveness" (LV06 BLINK `CHALLENGE_TIMEOUT`); still stream: "Face verification could not be completed" (LV08 `FROZEN`); replay: LV08 `PAD_SCORE`; then "Face capture could not be completed after 3 attempts. Please ask your supervisor to follow the centre's recovery process." and LV11 once; registration abandoned | `e2e-05-print-refused.png`, `e2e-06-injected-still-refused.png`, `e2e-07-max-attempts-guidance.png`, log 04:52:06-04:52:56 |
| D7 | End of day: supervisor login and EOD approval (`eod_auth`), upload | `supervisor1` face login; every pending packet approved; supervisor password; replay `a-s2-screen-replay`, then live face; upload | replay refused; live face passes; packets uploaded | replay refused with "Face verification could not be completed. Please try again." (attempt 1 of 3), live face passed, "Authentication successful", both packets approved and uploaded (no packet left in APPROVED) | `e2e-08-eod-pending.png`, `e2e-09-eod-replay-refused.png`, log 04:52:56-04:53:36 |
| D8 | Configuration change by sync | MockSync `PUT /admin/config policy.registration=ALWAYS_ACTIVE`; Synchronize Data; resident attempt with the rigid-motion print `a-s2-print-affine`, then live resident `s3`; key removed and synchronised back | the client applies the new policy after sync; the print, which passes the passive check under the shipped policy, is refused; the live face answers challenges | `REG.GLOBAL_PARAM` changed to ALWAYS_ACTIVE; LV15 names `policy.registration`; the print got challenges and was refused; `s3` passed with `LIVENESS_METHOD=ACTIVE`, `LIVENESS_CHALLENGES=BLINK:OK,SMILE:OK`; after the second sync the value was PASSIVE_THEN_ACTIVE again | `e2e-10-config-sync-print-refused.png`, packet `...232400`, log 04:53:36-04:55:20 |
| D9 | L1 device | MockMDS certification L1 (digitalId signed with the renewed FTM key); Device Settings, Scan Now; operator face capture | device accepted, session records L1 | liveness passed; LV01 `"certification":"L1"`; device set back to L0 and rescanned | `e2e-11-l1-device-onboarding.png`, log 04:55:20-04:57:07 |
| D10 | Offline | driver `network off` (all HTTPS calls of the client fail); officer login; resident `s2` with packet authentication; supervisor login; EOD with replay `a-s3-screen-replay`; network back; upload | identical liveness behaviour offline | client logged "No Internet Access"; MockSync received no request between 04:57:07 and 04:58:40; login, capture, packet authentication and EOD liveness passed and the window showed "Offline – verification runs on this device"; the replay of the supervisor's own face (passive score above the reject threshold, below 95) had to answer a blink and was refused on the timeout; after the network came back both packets were uploaded | log 04:57:07-04:59:03, packet `...232717` |
| D11 | Face match refuses a live wrong person | `officer1` password, face `s1` (live, not enrolled); then face `s3` | liveness passes, match fails; the right face logs in | liveness passed on the third attempt (two `ONSET_TOO_FAST`, see 5.3); "Face match not found" (SFace score 11, threshold 40); then `s3` passed and reached the home screen | `e2e-12-face-match-not-found.png`, log 04:59:03-04:59:19 |
| D12 | Audit evidence | query `AUDIT.APP_AUDIT_LOG` | LV events with workflow, session and buckets, no images or raw scores | rows for LV01-LV08, LV10-LV13, LV15, LV16; descriptions are JSON such as `{"workflow":"registration","sessionId":"...","scoreBucket":"70-79","reason":"BELOW_THRESHOLD"}`; face login events are stored before a session exists (`SESSION_USER_ID=NA`) | log 04:59:19 |
| D13 | Packet evidence | MockSync decrypts each uploaded packet (`GET /admin/packets/{name}/liveness`, `tools/mock-sync/scripts/show-packet.sh`) | `LIVENESS_*` entries in the face BIRs, meta info, LV rows in `audit.json` | every face record (resident, officer, supervisor) carries `LIVENESS_DECISION`, `LIVENESS_METHOD`, `LIVENESS_SCORE`, `LIVENESS_ENGINE=mosip-onnx-default@1.0.0`, `LIVENESS_MODEL_SHA`, `LIVENESS_SESSION`, `LIVENESS_DEVICE_LEVEL`, and `LIVENESS_CHALLENGES` or `LIVENESS_BINDING` where they apply; `packet_meta_info.json` has `livenessDecision` and `livenessMethod` | `test-report/desktop-e2e/packets/*.json` |

### 3.2 Flows run step by step (interactive driver sessions, same day)

| # | Flow | Steps | Observed | Evidence |
|---|---|---|---|---|
| I1 | First operator onboarding | after the first login: fingers and irises from MockMDS stock images, face with liveness (`onboard_auth`), Save | MockSync's IDA mock received the request (`IDA auth for supervisor1: biometrics {FACE=1, FINGER=10} -> authStatus true`); templates stored in `REG.USER_BIOMETRIC` (`Face`, quality 95) for `officer1` and `supervisor1` | MockSync log `work/mock-sync.out` 03:43:34; flow `flows/lib/onboard.flow` |
| I2 | Supervisor override | logged in as `supervisor1`; resident attempts with `a-s2-print-still` until the third failure (challenge timeouts); "Supervisor override", confirmation dialog, OK; registration finished and uploaded | LV11, LV16 `{"supervisorId":"supervisor1"}`; packet `...213659`: resident face `LIVENESS_DECISION=OVERRIDE`, `LIVENESS_CHALLENGES=BLINK:TIMEOUT`, meta info `livenessDecision=OVERRIDE` | `test-report/desktop-e2e/packets/10001100010000320260929213659.liveness.json` |
| I3 | SFace SDK selected for FACE | client start after the config sync | `BioAPIFactory: Starting initialization for vendor mockvendor ... FACE={classname=io.mosip.liveness.facematch.SFaceBioApi, threshold=40}`, FINGER and IRIS on `SampleSDK`; match analytics `{score=100, threshold=40, model=sface@2021dec-fp16w}` | `run/desktop/logs/registration.log` |

## 4. Problems found and fixed

| Problem | Where | Fix |
|---|---|---|
| Liveness audits of face login were lost: `AuditManagerSerivceImpl` read `SessionContext.map()` before login, when it is null (NullPointerException, 22 lost events in the log) | `clients/desktop/.../audit/AuditManagerSerivceImpl.java` | null check; test `AuditFactoryTest.auditBeforeLoginIsStored`; 0 lost events since |
| `REG-EVT-LV11` was written twice per exhausted transaction: by the engine session and again by `LivenessRun` | `clients/desktop/.../service/bio/liveness/LivenessRun.java` | `LivenessRun` writes it only when the engine did not; test `LivenessOrchestratorTest.maxRetriesIsAuditedOnceWhenTheEngineReportsIt`; one LV11 per transaction since |
| A liveness check started while the client rescanned its devices failed with "The face camera is not available" | `SbiFaceCaptureDevice.resolve` | waits up to 3 s for the running scan; `SbiFaceCaptureDeviceResolveTest` (4 tests) |
| Closing and reopening the liveness window gave a fresh set of attempts | `LivenessOrchestrator` | used-up transactions are remembered per workflow and person (`ExhaustedTransactions` at the time of this run, now `AttemptHistory`; `REG-LIV-014`); fixed earlier in this phase |
| Every manual sync ended with a "Registration Packet Status Reader: Failed" alert: MockSync answered the packet status query with an empty list | `tools/mock-sync/.../web/RegProcController.java` | one `RECEIVED` status per requested packet; `SyncEndpointsTest.packetStatusAnswersEveryRequestedPacket` |
| L1 devices could never be trusted: MockMDS's FTM root expired in 2022 and the client refuses expired CAs (`KER-PCM-004`) | `devices/mock-mds/Biometric Devices` | root re-issued with the same key, valid to 2035 (`devices/mock-mds/scripts/renew-ftm-root.sh`); MockSync serves it under DEVICE and FTM; MockMDS tests 226/226 pass |
| The client and demo needed the SFace SDK, the liveness jars, ONNX Runtime and MOSIP's mock SDK in `lib/` | `registration-services/pom.xml`, `scripts/desktop-build.sh`, MockSync config | runtime dependency on `face-match-sdk`; FACE keys in `mock-sync-defaults.properties`; the build copies `mock-sdk.jar` from `run/cache` |
| Pack `s1` cannot serve as a low-confidence resident: its clip blinks every third of a second (eye aspect ratio measured per frame), so a blink always lands at the prompt (`ONSET_TOO_FAST`) | demo data | `s3n` packs (`NoisyPack.java`): same person and movements with camera noise, passive score 74 (below 80) |

## 5. Known issues and limits

1. **The rigid-motion print passed the passive check under the policy of 2026-09-30 (closed by the shipped defaults since).** In run 1 `a-s2-print-affine` was
   accepted as a resident face 2 s after Retry, without any challenge (04:12:03-04:12:05: "Checking face
   liveness", "Hold still", "Good, face captured successfully"). The evaluation measured a passive score of 90 for
   this pack; `docs/eval/calibration-notes.md` 3.2 lists four such synthetic attacks. With `policy.registration` set
   to ALWAYS_ACTIVE (part of evaluation proposal P1, which also covers `login_auth` and `onboard_auth`) the same
   print is refused (D8). MockSync's defaults shipped PASSIVE_THEN_ACTIVE at the time of the run; they now ship ALWAYS_ACTIVE for
   registration, sign-in, onboarding and the three supervisor workflows (two challenges add about 7 s to a capture).
2. **Supervisor override after a print.** In I2 the override accepted the face of a printed photo: its three
   failures were challenge timeouts, and the core allows an override after `ACTIVE`/`TIMEOUT` failures (never after
   PAD, integrity or binding failures). The capture is marked `OVERRIDE` in the packet for review. Since then the
   override needs a second supervisor (user ID and face check, valid 60 s) and the photo is checked for one face and a
   passive PAD score; that flow has unit tests but was not run on a screen.
3. **Spontaneous blinks at the prompt fail a blink challenge** (`ONSET_TOO_FAST`, engine proposal E2). Seen with
   pack `s1` in D11 (two of three attempts) and in earlier runs with `s1` as supervisor; the driver presses Retry
   like an operator would, and the retry passes.
4. **`INFERENCE_TIMEOUT` under load.** Once (interactive session, 03:27) three frames in a row took longer than
   500 ms while about 27 Java processes shared the CPUs; the session failed with "Face verification is unavailable
   on this machine", which does not use an attempt, and the retry passed.
5. **Offline is simulated inside the client JVM**, by sending its HTTPS calls to a closed local proxy port. MockSync
   itself was not stopped because the Android engineer was using it at the same time. The client saw the same
   connection failures as with the server down ("No Internet Access", no request reached MockSync).
6. **UNAVAILABLE device fault** (`/admin/fault UNAVAILABLE`, STREAM refused with 608) shows the "device
   disconnected" message rather than a separate "device unavailable" text (from the desktop UI engineer's notes; not
   re-tested here).
7. **Notice `liveness.config.updated`** after a sync that changes liveness keys is translated but not shown; the
   change itself is applied and audited (LV15), as D8 shows.
8. **The golden snapshot works only on this machine**: the client database password is sealed with the machine's
   TPM key, and MockSync must still hold the machine registration.
9. **Face images in the run folder.** Screenshots in `run/demo/*` taken before the HTML views were masked (runs 1-3)
   show the resident photo on the preview and EOD pages; they stay outside git. The committed screenshots come from
   run 4 and were checked by eye.
10. Match scores come from frames of the same clip as the enrolled face (score 100); a different photo of the same
    person scored 62 in the face-match SDK's own tests. The threshold (40) is not calibrated on a larger set.

## 6. Unit tests run with these changes

| Module | Command | Result |
|---|---|---|
| `clients/desktop/registration/registration-services`, liveness and audit tests | `mvn -o -pl registration-services test "-Dtest=io/mosip/registration/service/bio/liveness/*Test.java,**/AuditFactoryTest.java"` | 85 run, 0 failures, 0 errors |
| same module, full suite | `mvn -o -pl registration-services test` | 818 run, 0 failures, 4 errors, 17 skipped. Errors: the two known expired-certificate tests (`ClientIntegrityValidatorTest`, `ManifestCreatorTest`, `docs/install/desktop.md` section 7) and `BioServiceTest` twice, because it binds port 4501, which the running MockMDS held. The suite rewrites `MANIFEST.MF` and creates `lib/`, `SCHEMA_0.5.json`, `newProcess.json`, `.UNKNOWN_JARS`; they were restored and deleted afterwards. |
| `tools/mock-sync` | `mvn -o test` | 37 run, 0 failures |
| `devices/mock-mds` (renewed FTM keys) | `mvn -o test` with Java 21 | 226 run, 0 failures |

These counts are from 2026-09-30. Current counts per suite, with the commands a reader can run, are in
[README.md](README.md) section 2 (desktop liveness tests on 2026-10-05: `registration-services` 150, `registration-client` 63, 0 failures).

## 7. Evidence files

| File | Content |
|---|---|
| `docs/test-report/desktop-e2e/results.txt` | every check of the reference run with its section |
| `docs/test-report/desktop-e2e/driver-summary.log` | commands, alerts, results, SQL rows and MockMDS calls of the reference run (screen-state lines removed) |
| `docs/test-report/desktop-e2e/packets/*.liveness.json` | liveness evidence of five packets as decrypted by MockSync: `...232013` (D3), `...232100` (D4, D5), `...232400` (D8), `...232717` (D10), `...213659` (I2, override) |
| `docs/ux/screenshots/desktop/e2e-*.png` | masked snapshots of the reference run (no face shown) |
| `run/demo/desktop-run4/` (not in git) | full driver log with screen states, all snapshots, `packets/` |
