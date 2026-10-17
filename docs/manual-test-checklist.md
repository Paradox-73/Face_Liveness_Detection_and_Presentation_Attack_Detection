# Manual test checklist: the steps only a person can do

This checklist covers the tests that need a live face, a physical attack artefact, a real phone or a camera
operator. Scripts and the automated suite cannot do these steps. Each step has an id. Write the result of each step
into [manual-test-results-template.md](manual-test-results-template.md) under the same id.

| Section | Ids | People | Time |
|---|---|---|---|
| A. Webcam recordings of each team member | `MT-REC-01` to `MT-REC-08` | the subject and one helper | about 20 minutes per person |
| B. ISO/IEC 30107-3 volunteer attack session | `MT-PAD-01` to `MT-PAD-11` | a tester, a recorder and the volunteers | half a day |
| C. Desktop client with a webcam at the shipped settings | `MT-DT-01` to `MT-DT-19` | two team members (operator and supervisor) | about 90 minutes |
| D. Android client on a real phone | `MT-AND-01` to `MT-AND-12` | one or two team members | about 90 minutes |
| E. Demo video dry run | `MT-VID-01` to `MT-VID-06` | the whole team | about 2 hours |

Commands run in Git Bash from the repository root unless marked PowerShell. `../run/` is the folder next to the
repository (`E:\Projects\MOSIP\run\` on the build laptop). Logins and passwords are in [test-data.md](test-data.md).

The screen texts in quotes are the English texts of `docs/ux/message-catalogue.csv`. The desktop client shows them
from `labels_en.properties`, and the Android client from the `livenessmanager` `strings.xml` and `app_en.arb`. A text
in braces, such as `{0}`, is a number the client fills in.

## A. Webcam recordings of each team member

The recordings give the automated suite real faces of consenting people. Until now the suite used only openly
licensed clips of three strangers and software-made attacks. `tools/eval/ingest-recordings.py` turns the recordings
into frame packs and scenarios and reruns the metrics (step `MT-REC-08`).

**Subjects.** Kanav, Ivan, Ishaan Sodhi and Nikunj each record one set as a subject. Each person gets a pseudonym
`S1` to `S4`. Write the name-to-pseudonym list on paper and keep it apart from the data, as
[eval/protocol.md](eval/protocol.md) section 10 requires. File names, sheets and reports use the pseudonym only.

**Consent.** The recordings follow the same rule as the licensed clips in `eval/packs/sources.yaml`. They are never
committed, never shown in the video or slides without a separate written opt-in, and never used to train a model.
The frame packs cut from them stay in `../run/frame-packs/`, outside git.

**Clips.** Each subject records 14 clips: 7 clips in normal light (`C1`) and the same 7 in dim light (`C2`).

| Clip name | Length | What the subject does |
|---|---|---|
| `neutral` | 18 s (15 to 20 s) | Look at the camera with a neutral face. Blink naturally and keep small natural head movement. Do not smile, turn, nod or talk. Record one continuous take. Never loop, cut or join clips. |
| `blink` | 4 s | Neutral for about 1 s, then one clear blink, then neutral until the end. |
| `smile` | 4 s | Neutral for about 1 s, then smile and hold the smile for about 1 s, then neutral. |
| `turn-left` | 4 s | Neutral for about 1 s, then turn the head to your own left by about 30 to 45 degrees, hold about 1 s, then back to frontal. |
| `turn-right` | 4 s | The same to your own right. |
| `look-up` | 4 s | Neutral for about 1 s, then tilt the head up by about 20 to 30 degrees, hold about 1 s, then back. |
| `look-down` | 4 s | The same, tilting down. |

`LOOK_UP` and `LOOK_DOWN` are not in the shipped challenge pool (`BLINK,SMILE,TURN_LEFT,TURN_RIGHT`), but an operator
can configure them. Record them anyway, because no real face has ever been tested on them.

**File names.** Every recording goes into `../run/recordings/` with the name `<subject>_<condition>_<clip>.mp4`, for
example `S2_C1_neutral.mp4`, `S2_C2_turn-left.mp4` or `S3_P6_neutral.mp4`.

- `<subject>` is the pseudonym, `S1` to `S99`. Never a name.
- `<condition>` is `C1` to `C5` for a genuine recording (lighting, [eval/protocol.md](eval/protocol.md) section 6)
  or `P1` to `P8` for a recording of an attack artefact (species, protocol section 7; see `MT-PAD-05`).
- `<clip>` is one of `neutral`, `blink`, `smile`, `turn-left`, `turn-right`, `look-up`, `look-down`.
- The extension may be `.mp4`, `.mov`, `.mkv`, `.webm` or `.avi`. The video must be landscape (4:3 or wider).
- A second take replaces the first under the same name. The ingest script refuses any video file with another name.

| Id | Step | Expected result |
|---|---|---|
| `MT-REC-01` | Each subject reads and signs the consent form of [eval/protocol.md](eval/protocol.md) section 10, including the separate opt-in for the video and slides. Write the pseudonym list on paper. | Four signed forms. One paper list. |
| `MT-REC-02` | Prepare the camera. Close the desktop client and the Android emulator, and switch MockMDS away from the webcam (`bash scripts/mockmds-run.sh --stop`), because the webcam serves one program at a time. In Windows Settings, open Bluetooth & devices, Cameras, the webcam, and switch off background blur, eye contact, automatic framing and any other Studio Effects. Find the webcam name with `ffmpeg -hide_banner -list_devices true -f dshow -i dummy`, then list its modes with `ffmpeg -hide_banner -f dshow -list_options true -i video="Integrated Camera"`. | The webcam name in quotes, for example `Integrated Camera`. A mode of 1280x720 at 30 fps, or else 640x480 at 30 fps. Never a virtual camera (OBS, ManyCam, names containing `Virtual`). |
| `MT-REC-03` | Prepare the room and the folder. Put the laptop on mains power. Sit 40 to 80 cm from the webcam, face centred, whole head in the picture, plain background, no other person and no photo of a face in view. Wear glasses only if you normally wear them. Run `mkdir -p ../run/recordings`. | The folder exists. The preview in the Windows Camera app shows only one face. Close the Camera app afterwards. |
| `MT-REC-04` | Record the 7 clips in normal office light (`C1`). Run the commands below once per subject. The helper says "go" when ffmpeg starts and counts the seconds aloud. | 7 files `S<n>_C1_<clip>.mp4` in `../run/recordings/`. |
| `MT-REC-05` | Record the same 7 clips in dim light (`C2`): one lamp, below about 50 lux, face still visible. Set `L=C2` and run the same commands. | 7 files `S<n>_C2_<clip>.mp4`. |
| `MT-REC-06` | Check each clip. Play it in a video player, and print its length with `ffprobe -v error -show_entries format=duration -of csv=p=0 ../run/recordings/S1_C1_neutral.mp4`. | The face is visible in every frame. The action happens once. `neutral` lasts 15 to 20 s and each action clip about 4 s. Record a bad clip again. |
| `MT-REC-07` | Hand over the recordings (PowerShell commands below). | One zip file per subject, its SHA-256 written in the results template, and the checksum matched by the receiver. |
| `MT-REC-08` | Kanav ingests all recordings and reruns the metrics with `python tools/eval/ingest-recordings.py` (details below). | The last line is `report: <path>`, for example `report: E:\Projects\MOSIP\run\eval\recordings-<time>\report.md`. Copy the headline table of that report into the results template. |

Recording commands for `MT-REC-04` and `MT-REC-05`. Set the webcam name, the pseudonym and the light first. Then run
one `rec` line per clip, and start the action when the helper says "go":

```bash
CAM="Integrated Camera"; S=S1; L=C1
rec() { ffmpeg -hide_banner -loglevel error -f dshow -video_size 1280x720 -framerate 30 -i video="$CAM" \
          -t "$2" -an -c:v libx264 -crf 18 -pix_fmt yuv420p "../run/recordings/${S}_${L}_$1.mp4"; }
rec neutral 18
rec blink 4
rec smile 4
rec turn-left 4
rec turn-right 4
rec look-up 4
rec look-down 4
```

- If ffmpeg answers `Could not set video options`, the webcam lacks that mode. Use `-video_size 640x480`.
- If a file exists already, ffmpeg asks `Overwrite? [y/N]`. Answer `y` only for a deliberate second take.
- Leave auto exposure and auto white balance on. They are the webcam's defaults.

Hand-over commands for `MT-REC-07` (PowerShell, in the parent folder of the repository, `E:\Projects\MOSIP` on the build laptop):

```powershell
Compress-Archive -Path run\recordings\S1_* -DestinationPath run\handover-S1.zip
Get-FileHash run\handover-S1.zip -Algorithm SHA256
```

- Give the zip to Kanav on a USB stick, or put it in a shared-drive folder that only the four team members can open.
  Protocol section 10 promises storage on one BitLocker-encrypted laptop and never in the cloud. Use a cloud drive
  only if every subject agreed to it in writing. Delete the shared copy after Kanav confirms the checksum.
- Kanav checks the checksum with `Get-FileHash` and unzips into `run\recordings\`.
- Never copy a recording, a frame pack or a zip into the repository. Never attach one to an issue or a chat.
- Delete all raw media on 2026-11-30 (protocol section 10).

Ingest command for `MT-REC-08`. It needs JDK 11 or newer (`source ../toolchain/env.sh` on the build laptop), ffmpeg,
Python with `pip install -r tools/eval/requirements.txt`, and the runner jar
(`mvn -f tools/eval/scenario-runner/pom.xml package`):

```bash
python tools/eval/ingest-recordings.py
python tools/eval/ingest-recordings.py --config policy.registration=PASSIVE_THEN_ACTIVE   # the other candidate default
```

- The script cuts each clip into `../run/frame-packs/rec-<subject>-<condition>-<clip>/` (640x480, 15 fps).
- It writes one scenario per subject and condition into `../run/eval/recordings-<time>/scenarios/`. In a genuine
  scenario the subject's own action clips answer the prompts, and the challenge pool is the recorded actions only.
- It runs the scenario runner with the shipped settings and then `tools/eval/analyze.py`.
- It skips a group that has no `neutral` clip, a genuine group with fewer than two action clips, and an attack
  group with exactly one action clip, and prints `skipped ...` for each. It stops on a video file with another
  name, and on two files for one recording (for example `.mp4` and `.mov`).
- Options: `--recordings DIR`, `--packs DIR`, `--out DIR`. Any other option goes to the runner unchanged, for
  example `--config KEY=VALUE` or `--threads 1`.
- Each scenario runs 4 transactions. A test with 20 transactions took 11 minutes on the build laptop while other
  programs ran, so plan about 30 s per transaction. Four subjects in two lights give 32 genuine transactions, about
  16 minutes, plus 2 minutes per attack recording.

## B. ISO/IEC 30107-3 volunteer attack session

This section runs the in-house evaluation of [eval/protocol.md](eval/protocol.md) with real artefacts. The protocol is
the authority. This section turns it into steps. Do the dry run of [eval/team-self-test.md](eval/team-self-test.md)
first, with team members as subjects S1 to S4 (development split). Volunteers are subjects S5 to S12 (test split).

**Roles.** The tester presents every artefact and is never the person who chose the thresholds. The recorder fills
the results sheet and is not filmed in the same session. Both write down their experience (years, earlier PAD work).

**Artefacts.** Make every artefact only from the source subject's own images.

| Species | Artefact | Cost | Preparation |
|---|---|---|---|
| P1 | Colour print of a frontal photo on A4 matte paper, held flat by hand | about USD 1 | 15 min |
| P2 | Glossy photo print (10x15 cm), gently curved in the hand | about USD 2 | 15 min |
| P3 | A4 print with the eyes (and mouth) cut out, the tester's own eyes behind (optional cut-out) | about USD 1 | 30 min |
| P4 | Photo full-screen on a smartphone at full brightness | own phone | 5 min |
| P5 | Photo on a laptop or tablet screen | own device | 5 min |
| P6 | Recorded video of the subject (blinks, slight motion) replayed on a laptop or tablet | none | 20 min |
| P7 | Adaptive replay: one clip per challenge action (blink, smile, turn left, turn right), the tester plays the clip that matches the prompt | none | 45 min |
| P8 (optional) | Paper mask: print glued on card, eye holes cut, worn by the tester | about USD 3 | 60 min |

**Counts.** One transaction is one capture until the client accepts it or the last attempt fails (3 attempts by
default). Write one sheet row per attempt.

| Workflow | Genuine transactions | Attack transactions | Species | Source |
|---|---|---|---|---|
| Resident registration, desktop | 200 (8 subjects x 5 conditions x 5) | at least 60 per species (8 source subjects x 8) | P1 to P7, P8 optional | protocol sections 6 and 7 |
| Resident registration, desktop, smaller team | 100 | 30 per species | P1, P4, P6, P7 | protocol section 7, "Smaller team fallback" |
| Face sign-in (`login_auth`), desktop, full-system test (c) | 40 genuine operator logins | 60: 4 enrolled operators x 5 attempts x 3 species | P1, P4, P6 | protocol section 7, "Full-system test (c)" |
| Supervisor workflows (`eod_auth`, `exception_auth`, `packet_auth`), desktop | 5 per workflow | 5 per species per workflow | P1, P4, P6 | team addition, not in protocol.md |
| Resident registration, Android, lowest-specification phone | 60 | 30 per species | P1, P4, P6 | protocol section 7, "Android replication" |

[test-data.md](test-data.md) lists three accounts (`officer1`, `officer2`, `supervisor1`). The full-system test needs
four enrolled operators. Add a fourth account to `tools/mock-sync/data/users.json` as test-data.md describes.

| Id | Step | Expected result |
|---|---|---|
| `MT-PAD-01` | Brief the tester for 30 minutes (protocol section 3: basic attack potential, no PAD expertise). Name the tester and the recorder. Each volunteer signs the consent form of protocol section 10. Send the one-paragraph notice to the faculty adviser or ethics contact. | Signed forms. Names of tester and recorder with their experience in the results template. |
| `MT-PAD-02` | Freeze and record the item under test (protocol sections 1 and 8, step 4): `git rev-parse HEAD`; the engine line `Liveness engine mosip-onnx-default@1.0.0 ready` from `../run/desktop/logs/registration.log`; the model bundle version; the policy in effect with `curl -s http://127.0.0.1:8080/admin/config/mosip.registration.liveness.policy.registration`; the webcam model and resolution. | The values in the results template. No change to the engine, the models or the thresholds after this step. |
| `MT-PAD-03` | Source session for each volunteer (about 20 minutes). Take frontal photos, a 10 s P6 video and the P7 action clips with a phone camera, never with the webcam under test. | Photos and clips on the encrypted laptop, labelled with the pseudonym. |
| `MT-PAD-04` | Make the artefacts of the table above. Label each physical artefact `A1`, `A2`, and so on, and write the label on the sheet. Photograph each artefact type once, with a consenting subject only, for the report. | One labelled artefact per species and source subject. One photo per artefact type. |
| `MT-PAD-05` | Record each artefact with the webcam for the automated suite, with the client closed. Use the `rec` function of section A with `L` set to the species. Record `S5_P1_neutral.mp4` (18 s, the print held 40 to 60 cm from the webcam) and the same for the other species. For P7, also record `S5_P7_blink.mp4`, `S5_P7_smile.mp4`, `S5_P7_turn-left.mp4` and `S5_P7_turn-right.mp4`, each clip played on the replay screen. | Files in `../run/recordings/`. `ingest-recordings.py` runs them as attack scenarios. |
| `MT-PAD-06` | Registration session on the desktop client (set-up as in section C, steps `MT-DT-02` to `MT-DT-05`, diagnostic mode on as in [eval/team-self-test.md](eval/team-self-test.md) section 2). Interleave about one genuine transaction for every two attacks. Hold each artefact 40 to 60 cm from the webcam and try honestly to pass, answering prompts with the P7 clips. | One sheet row per attempt. The result of an attack is `FAIL` with reason `PAD`, `ACTIVE`, `TIMEOUT` or `INTEGRITY`, or `PASS` (an accepted attack: note species, light and prompt). |
| `MT-PAD-07` | Face sign-in, full-system test (c). Each enrolled operator signs in five times with each species made from their own face, and gives the genuine logins. | Sheet rows with workflow `LOGIN`. A refusal by the face matcher alone ("Face match not found") is `FAIL` with reason `MATCH`. |
| `MT-PAD-08` | Supervisor workflows: end-of-day approval, exception authentication and packet authentication with `supervisor1` (enrolled with a team member's face, step `MT-DT-11`). | Sheet rows with workflow `EOD_AUTH`, `EXCEPTION_AUTH` or `PACKET_AUTH`. |
| `MT-PAD-09` | Android replication on the lowest-specification phone available (section D set-up). | Sheet rows with platform `android`. |
| `MT-PAD-10` | Film the session for the evidence and the video. Put a phone on a tripod behind the tester's shoulder so the shot shows the artefact, the webcam and the client screen. Film one take per species, and only consenting people with the video opt-in. Never film the paper pseudonym list. | One clip per species outside git, named `<date>_<species>_scene.mp4`. |
| `MT-PAD-11` | Convert the sheet and compute the metrics. Shred the prints. | `report.md` with APCER per species per transaction and on the first attempt, BPCER per condition, and IAPAR and FRR for the sign-in rows. |

**The results sheet.** Copy [eval/self-test-sheet.csv](eval/self-test-sheet.csv) and delete its example rows. Add the
optional columns `match` and `artefact_id`. Fill one row per attempt:

| Column | Values |
|---|---|
| `txn_id` | `T001`, `T002`, and so on, unique per platform |
| `platform` | `desktop` or `android` |
| `subject_id` | `S1` to `S12`, the pseudonym of the person or of the artefact's source person |
| `kind` | `bona_fide` or `attack` |
| `species` | `BONA_FIDE`, or `P1` to `P8` |
| `condition` | `C1` to `C5` for genuine rows; empty for attacks |
| `workflow` | `REGISTRATION`, `LOGIN`, `EOD_AUTH`, `EXCEPTION_AUTH` or `PACKET_AUTH` |
| `attempt_no` | `1`, `2`, `3` |
| `result` | `PASS`, `FAIL`, or `NO_FACE` when the client never found a usable face |
| `reason` | the reason from the diagnostic line, for example `PAD`, `ACTIVE`, `TIMEOUT`, `INTEGRITY`, `MATCH`, `too dark` |
| `notes` | anything odd, never a name |
| `match` | optional: `PASS` or `FAIL`, the face matcher's result on sign-in rows |
| `artefact_id` | optional: `A1`, `A2`, and so on |

Commands for `MT-PAD-11`:

```bash
python tools/eval/sheet-to-attempts.py ../run/pad-session/results.csv --out ../run/pad-session/attempts.jsonl \
       --model-set mosip-onnx-default@1.3.0
python tools/eval/analyze.py ../run/pad-session/attempts.jsonl --out ../run/pad-session/report.md
```

Reason lines on the desktop: `grep "LIVENESS-DIAG" ../run/desktop/logs/registration.log | tail -20`. On Android:
`adb logcat -d -s Liveness | tail -20`. Keep the sheet and the report outside git until the team agrees to publish
the aggregated report under `docs/eval/results/`.

## C. Desktop client with a webcam at the shipped settings

This section follows the README route "Evaluate in 15 minutes" ([install/README.md](install/README.md) section 10)
and then every face workflow. Two team members take part: the operator (`officer2`, onboarded with their own face)
and the supervisor (`supervisor1`, onboarded with the second person's face). Time the steps `MT-DT-02` to `MT-DT-12`,
because nobody has timed the 15-minute route yet.

### What the liveness window shows

The shipped default policy is `ALWAYS_ACTIVE`: after the passive check every capture asks for two random actions
([design/decision-flow.md](design/decision-flow.md) section 5). Step `MT-DT-04` reads the value MockSync serves.
Each screen and its text: [install/README.md](install/README.md#102-what-you-should-see) section 10.2; every
message: [ux/message-catalogue.csv](ux/message-catalogue.csv). What the tester needs to know:

- "Checking face liveness..." takes about 2 s (30 frames); nothing to do.
- Each prompt ("Please blink", "Please smile", "Please turn your head to the left" or "to the right") has 5 s.
- Under `PASSIVE_THEN_ACTIVE` a passive score at or above the threshold (80 for registration, 95 for the
  authentication workflows) skips the prompts. Write down in the notes whether prompts appeared.
- A suspected attack shows only "Face verification could not be completed. Please try again.", never the reason.
- Dim light: the first three dark tries of one capture use no attempt; the fourth does.

### Steps

| Id | Step | Expected result |
|---|---|---|
| `MT-DT-01` | Once per laptop: install the tools and build as in [install/README.md](install/README.md) sections 1 to 5 (engine, MockSync, MockMDS, `bash scripts/desktop-build.sh --mocksync`, machine registration). Install ffmpeg (`winget install --id Gyan.FFmpeg`). | `ffmpeg -version` prints a version. `../run/desktop/run.bat` exists. |
| `MT-DT-02` | Start MockSync in its own window: `(cd tools/mock-sync && scripts/run-mock-sync.sh)`. Check it with `curl -s http://127.0.0.1:8080/v1/syncdata/actuator/health`. | `{"status":"UP"}` |
| `MT-DT-03` | Start MockMDS with `bash scripts/mockmds-run.sh` and switch its face camera to the webcam with `curl -s -X POST http://127.0.0.1:4501/admin/source -d '{"type":"Face","source":"FFMPEG","camera":"Integrated Camera","fps":15}'` (use the name from `MT-REC-02`). | The answer starts with `{"errorCode":"0","errorInfo":"Success"`. |
| `MT-DT-04` | Read the settings MockSync serves: `curl -s http://127.0.0.1:8080/admin/config/mosip.registration.liveness.policy.registration` and the same for `policy.login_auth`, `policy.eod_auth` and `active.challenge_types`. | Policy `ALWAYS_ACTIVE` or `PASSIVE_THEN_ACTIVE` (write which one). Pool `BLINK,SMILE,TURN_LEFT,TURN_RIGHT`. |
| `MT-DT-05` | Start the client (PowerShell): `powershell -ExecutionPolicy Bypass -File scripts\desktop-run.ps1`. Log in as `officer1`. The first login syncs and asks for a restart. Start again and log in again. | The pre-loader shows `Machine is ONLINE`. `../run/desktop/logs/registration.log` contains `Liveness engine mosip-onnx-default@1.0.0 ready in ... ms`. The home screen appears. |
| `MT-DT-06` | Operator onboarding with your own face. Log out, log in as `officer2`. The client asks for onboarding ("Onboard Operator"). Capture fingers and irises (they come from MockMDS stock images), then the face with **Scan**. Save. | The liveness window as described above. "The user is successfully onboarded". |
| `MT-DT-07` | Resident registration with a live face. Select New Registration, fill the demographic pages, open Biometrics, Face, and select **Scan**. | The liveness window, then the capture screen shows your face. Write the time from **Scan** to the face on screen. |
| `MT-DT-08` | Genuine failure and retry. Select **Scan** again, then ignore the first prompt (keep a neutral face). After the failure select **Retry** and answer the prompts. If no prompt appears (a passive pass under `PASSIVE_THEN_ACTIVE`), write that in the notes and skip the step. | "We could not verify face liveness. Please try again." or "Time is up for this action. Please try again.", then "Attempt 2 of 3", then a pass. |
| `MT-DT-09` | Attacks at registration. Select **Scan** and hold a printed photo of yourself 40 to 60 cm from the webcam. Repeat with the photo full-screen on a phone, and with a video of yourself on a phone. Answer prompts with the artefact if you can. | Each attempt fails with "Face verification could not be completed. Please try again." or "We could not verify face liveness. Please try again.". An accepted attack is a finding: write species, light and prompt. |
| `MT-DT-10` | Dim light and quality. Turn off the main light (one lamp only) and capture. Then capture with a second person in view, and with your face out of the picture. | "Improve lighting." on the dark tries, or a pass. "More than one face detected. Only the person being captured should be in view." "No face detected. Keep your face within the capture area." |
| `MT-DT-11` | Supervisor onboarding with the second person's face. Log in as `supervisor1`. If the client asks for onboarding, capture the face. If `supervisor1` was already onboarded with a frame pack (golden state of `scripts/demo-desktop.sh`), use "Update Operator Biometrics" to replace the face. | The liveness window titled "Face verification", then "The user is successfully onboarded". |
| `MT-DT-12` | Face sign-in (`login_auth`). Log out, log in as `officer2` and choose face ("Bring your face near the camera"). Then let the second person try to sign in as `officer2`. | Your own face: liveness, then the home screen. The other person: liveness passes, then "Face match not found". |
| `MT-DT-13` | Packet authentication (`packet_auth`). Finish a registration as `officer2`. On the authentication page select **Authenticate**, then **Scan** for the face. | The liveness window, then "Registration Acknowledgement". |
| `MT-DT-14` | Exception registration and supervisor exception authentication (`exception_auth`). Start a registration, mark the left little finger as an exception in Biometrics, take the exception photo (policy `exception_photo` is `OFF`, so the photo has no liveness check). At the end the client asks for a supervisor: type `supervisor1` and its password, select **Authenticate**, then **Scan**. The second person faces the camera. | The liveness window titled "Supervisor verification", then "Registration Acknowledgement". |
| `MT-DT-15` | End-of-day approval (`eod_auth`). Log in as `supervisor1` with face. Open the EOD Process page ("Pending Approval"), select **Approve** on every row, then **Authenticate**. Enter the password, **Submit**, then **Scan**. Hold a phone with a video of the supervisor first; then select **Retry** and let the supervisor face the camera. Then open Upload Packet and select **Upload**. | Replay: "Face verification could not be completed. Please try again." or "We could not verify face liveness. Please try again.". Live: the alert "Authentication successful". The packets upload. |
| `MT-DT-16` | Supervisor override with a second supervisor. MockSync ships `registration.on_max_failure=SUPERVISOR_OVERRIDE`. As `officer2`, start a resident capture and fail all 3 attempts. Keep a neutral face at every prompt, or hold up the printed photo when no prompt appears (a passive pass under `PASSIVE_THEN_ACTIVE`). Select **Supervisor override**. Type `officer1` first, then `supervisor1`, and let the supervisor face the camera. Confirm. Then log in as `supervisor1`, fail a resident capture 3 times, select **Supervisor override** and type `supervisor1`. | After the third failure: "Face capture could not be completed after 3 attempts. Please ask your supervisor to follow the centre's recovery process." and **Supervisor override**. The prompt: "A supervisor other than the logged-in operator must approve this override. Enter the supervisor's user ID; the supervisor's face is then verified." with the field "Supervisor user ID". `officer1`: "This user is not a supervisor. A supervisor must verify." `supervisor1` from `officer2`'s session: the "Supervisor verification" window, then "Supervisor override: this face will be flagged for review in the packet. Continue?". `supervisor1` from its own session: "The logged-in operator cannot approve this override. Another supervisor must verify." |
| `MT-DT-17` | Offline. Stop MockSync with Ctrl+C in its window. Sign in as `officer2` with face, register a resident with packet authentication, sign in as `supervisor1` and approve at end of day. Try to upload. Start MockSync again and upload. | The client shows that it is offline ("System Offline"). The liveness window shows "Offline – verification runs on this device". Every liveness step behaves as online. The upload fails with "System is in offline. Unable to Upload." and succeeds after MockSync is back. |
| `MT-DT-18` | Device fault. During a capture run `bash scripts/mockmds-run.sh --stop`. Start it again, repeat the `curl` command of `MT-DT-03`, and select **Retry**. | "The face camera was disconnected. Reconnect it and select Retry." or "The face camera is not available. Check that it is connected and select Retry.". No attempt is used. The retry passes. |
| `MT-DT-19` | Sign-in lock (do this last; it locks `officer2` for `mosip.registration.invalid_login_time`). Fail the face sign-in 3 times: ignore the prompts, or hold up the printed photo when no prompt appears. | "Face verification could not be completed. Maximum attempts reached. Use another sign-in method or contact your supervisor." The account is locked. |

If a step fails, [install/README.md](install/README.md) section 10.3 lists the usual causes. Write the screen text and
the last 20 lines of `../run/desktop/logs/registration.log` in the notes.

## D. Android client on a real phone

Use a phone with Android 9 (API 28) or newer and an arm64 CPU. Close the emulator first
(`bash scripts/android-run-emulator.sh --stop`), so `adb` talks to the phone. `scripts/android-mocksync-setup.sh`
works only with the emulator, so steps `MT-AND-03` and `MT-AND-04` register the phone by hand.

| Id | Step | Expected result |
|---|---|---|
| `MT-AND-01` | On the phone, open Settings, About phone, tap Build number seven times, then switch on USB debugging in Developer options. Connect the cable and accept the computer's key. Run `adb devices`. | One line with the phone's serial and `device`. |
| `MT-AND-02` | Build and install (JDK 21): `export JAVA_HOME="$JAVA21_HOME"; export PATH="$JAVA_HOME/bin:$PATH"`, then `bash scripts/android-build.sh --abis arm64-v8a`, then `adb install -r -g clients/android/build/app/outputs/flutter-apk/app-debug.apk`. Then `adb reverse tcp:8080 tcp:8080`. | `Success` from `adb install`. The app opens on the login screen. |
| `MT-AND-03` | Read the phone's machine keys. On the login screen, long-press the MOSIP footer. In the "Device Credentials" dialog tap COPY TEXT, then paste into the user name field. Run `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml`, open `ui.xml`, and copy the `text` of the user name field, with `&quot;` replaced by `"`. | A JSON text with `name`, `publicKey` and `signPublicKey`. |
| `MT-AND-04` | Register the phone in MockSync: `curl -s -X POST -H 'Content-Type: application/json' http://127.0.0.1:8080/admin/machines -d '{"name":"<name>","publicKey":"<publicKey>","signPublicKey":"<signPublicKey>","clientType":"ANDROID"}'`. Clear the user name field. | An answer with `machineId` and `keyIndex`. |
| `MT-AND-05` | First login and sync. Log in as `officer1`. After "Sync Completed Successfully" the app restarts about 5 s later. Log in again. | "Sync Completed Successfully" (7 of 7 steps), then the home screen "HELLO, Test Officer One". |
| `MT-AND-06` | Device certificate for the built-in camera: `bash scripts/android-liveness-device-cert.sh --csr`. | `provisioned: subject ... issuer CN=MockSync DEVICE CA`. Without this step a resident face capture is refused. |
| `MT-AND-07` | Operator onboarding with your own face: "GET ONBOARDED", "Officer's Biometric Onboarding", face capture. Then the supervisor (second person): log in as `supervisor1`, "Supervisor's Biometric Onboarding". | The capture screen with the same texts as the desktop window ("Checking face liveness…", the prompts, "Good, face captured successfully"). "You have onboarded successfully." Never "Face verification is not allowed on this device." (that text is for emulators). |
| `MT-AND-08` | Face sign-in (`login_auth`): "Sign in with face" as `officer1`; then the second person tries as `officer1`. | Own face: the home screen. Other person: "The face does not match this user. Please try again." |
| `MT-AND-09` | Resident registration and packet authentication: "NEW REGISTRATION", fill the pages, face capture, then "Authenticate with face". Try a printed photo and a phone replay on the next registration. | Genuine: "Good, face captured successfully", then the acknowledgement. Attack: "We could not verify face liveness. Please try again." or "Face verification could not be completed. Please try again.". |
| `MT-AND-10` | Supervisor approval (`eod_auth`) and override. As `supervisor1`, open "Pending Approval", "APPROVE", authenticate with face, then "UPLOAD". For the override, fail a resident capture 3 times as `officer1`, tap "Supervisor override", enter `supervisor1`. | Approval passes with the live supervisor face. The override shows "Supervisor user ID", then "Face stored with a supervisor override. It is flagged for review in the packet." |
| `MT-AND-11` | Offline: `adb reverse --remove tcp:8080` and airplane mode on. Sign in with face, register with packet authentication, approve as supervisor. Then `adb reverse tcp:8080 tcp:8080`, airplane mode off, upload. | Liveness behaves as online ("Offline – verification runs on this device"). The upload succeeds only after the network is back. |
| `MT-AND-12` | B7 and B8 measurement. Unplug the phone or let it charge fully, screen on, no other heavy app. Run `bash scripts/android-perf.sh --dry-run`, then `bash scripts/android-perf.sh --build`. Run it a second time and compare. | `run/perf/<date>/perf.csv`, `summary.md`, `device.txt` and `raw/`. Every row has `status` `OK`, or `FAIL` with a reason in `raw/<provider>-t<threads>.txt`. |

What to paste into [test-report/android-performance.md](test-report/android-performance.md) section 4 after
`MT-AND-12`. Fill one table row per provider and thread count:

| Column of section 4 | Where it comes from |
|---|---|
| Phone (model, SoC) | `model` and `soc` of `perf.csv` (also in `device.txt`) |
| Android | `android` |
| ABI | `abi` |
| Provider used | `provider_effective`. If it differs from `provider_requested`, the runtime fell back to CPU; write both. |
| Threads | `threads` |
| First frame (ms) | `first_frame_ms` |
| p50 (ms), p95 (ms) | `p50_ms`, `p95_ms` (the target is p95 of at most 20 ms per frame) |
| Peak PSS (MB) | `peak_pss_dumpsys_kb` divided by 1024 |
| Date | `date` |
| Build | the output of `git rev-parse --short HEAD` and `debug` or `release` |

Also replace the sentence "To be filled after the phone run." with the date and the phone, and add the second run's
p95 values to the notes, because phones slow down when hot.

## E. Demo video dry run

Follow [pitch/video-script.md](pitch/video-script.md). It holds the segments, the voice-over and the rules about
faces, labels and numbers. Do one full dry run before the recording day.

| Id | Step | Expected result |
|---|---|---|
| `MT-VID-01` | Prepare the desktop: `bash scripts/desktop-build.sh --mocksync`; rebuild MockMDS once so it reports iris quality 80 (`bash scripts/mockmds-run.sh --stop`, then `bash scripts/mockmds-run.sh --build`); on a machine without a golden snapshot `bash scripts/demo-desktop.sh --onboard` once; then `bash scripts/demo-desktop.sh --restore-golden --policy recorded`. Check MockSync, MockMDS and the emulator or phone. Hide bookmarks, the wallpaper, stickers and title bars that show a user or machine name. | The fresh-clone run of 2026-10-15 passed 69 of 71 checks ("Hold still" in the screens section and the second supervisor override failed). Without `--policy recorded` the recorded face cannot answer turn challenges and many checks fail. No institution name or logo is visible. |
| `MT-VID-02` | Live segments 1, 3, 4, 5 and 6c with a webcam (`curl` command of the recording checklist in video-script.md, `"fps":10`). Only consenting team members appear. Attack artefacts are made from the face of the person holding them. | Each live take shows the texts of section C. Each take carries the corner label `LIVE WEBCAM`. |
| `MT-VID-03` | Replayed segments 6a and 6b (flow `85-wrong-person`), 7 (`80-offline`), 9 (`60-config-sync`) and 10 (`20-registration-passive` for the disconnect, `70-device-l1`). A flow file alone does not start the client: make a file in `tools/mock-sync/e2e/flows/` with the four lines `include 00-start.flow`, `include 10-login.flow`, `section <nn>-<name>.flow`, `exit`, and run `bash scripts/demo-desktop.sh --restore-golden --policy recorded --flow <that file>`. | The section's checks pass. Each take carries the label `REPLAYED FRAMES`. |
| `MT-VID-04` | Android segment 8: the phone from section D, or `bash scripts/demo-android.sh --sections "login resident attacks approval"` on the emulator with `adb shell screenrecord`. | The phone or emulator shows "Sign in with face", the prompts, the refusal and the maximum-attempts message. |
| `MT-VID-05` | Time the whole cut with captions. | 4:45 or less (hard limit 5:00). The limitation line of segment 11 stays at least 4 s. |
| `MT-VID-06` | Final check of the cut. | No face of anyone who did not consent. Every number matches `docs/test-report/README.md` or `docs/eval/results/20261014-shipped/README.md`. The video file stays outside git. |
