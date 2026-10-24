# Team self-test: try the liveness check with your own faces

This page is for the team: how to sit in front of the real desktop and Android Registration Clients, try genuine
captures and home-made attacks, write down what happened, and turn the notes into APCER / BPCER figures.

- **What it is:** the team-member part of the evaluation protocol ([protocol.md](protocol.md) section 5, subjects
  S1-S4 = team members). Its results are used to check and tune the thresholds.
- **What it is not:** the volunteer test (subjects S5-S12, thresholds frozen first). Team results are reported as
  team results and are never reported as the volunteer test result.
- **Status on 2026-10-25:** two team members (S1, S2) and artefacts of three more people (S3-S5) have been run through
  the shipped engine: genuine 8 of 8 phone-camera transactions accepted, 0 of 32 attack transactions accepted
  ([results/real-faces-2026-10-24.md](results/real-faces-2026-10-24.md)). The sessions below extend that run to the
  full client and more conditions (what has been run:
  [../install/README.md](../install/README.md#10-evaluate-in-15-minutes-with-your-own-webcam) section 10). The
  recording protocol and file names for the team are in [../manual-test-checklist.md](../manual-test-checklist.md)
  section A; `python tools/eval/ingest-recordings.py` turns the recordings into frame packs, runs them and writes
  the report.

## 1. People, time and material

| Item | Detail |
|---|---|
| People | 2 at a time: a **tester** (in front of the camera, presents the attacks) and a **recorder** (fills the sheet, reads the screen). Swap roles. |
| Time | about 45 minutes per person for the desktop list in section 4; the Android list adds about 20 minutes. |
| Machine | the build laptop, because the onboarded client state only works on the machine that created it (its database key is sealed by that laptop's TPM chip). Another laptop needs the full set-up in [../install/README.md](../install/README.md) first. |
| Camera | the laptop's built-in webcam (`Integrated Camera`). No virtual cameras (OBS, ManyCam): the mock device refuses them. |
| Attack material (made from **your own** photos only) | P1: A4 colour print of a frontal photo. P4: the same photo full-screen on a phone at full brightness. P6: a 10 s phone video of you blinking and moving slightly, replayed on a phone or laptop. P7: four short clips of you blinking, smiling, turning left, turning right, to play the one that matches the prompt. Optional P3: the A4 print with the eyes cut out. |
| Lighting | C1 office light; C2 dim (one lamp); C3 window behind you; C4 strong side light; C5 wearing glasses. |

Photos, videos and prints stay on the laptop and are deleted after the test. Nothing with a face goes into git,
screenshots for the deck need the person's written OK.

## 2. Start the three programs (Git Bash, repository root)

1. MockSync, the local stand-in for the MOSIP server (accepted by the organisers for judging, 2026-09-30 call), and
2. MockMDS, the mock face device switched to the real webcam: start both as in
   [../install/README.md](../install/README.md#101-steps) section 10.1, steps 1-4 (the same steps are `MT-DT-02`
   and `MT-DT-03` in [../manual-test-checklist.md](../manual-test-checklist.md)).
3. Diagnostic mode: the client writes one line per liveness step (scores, reason) to its log; no images.

   ```bash
   curl -s -X PUT http://127.0.0.1:8080/admin/config -H 'Content-Type: application/json' \
     -d '{"mosip.registration.liveness.diagnostic.enable":"Y"}'
   ```

4. Start the desktop client and log in as `officer1` (section 10.1 step 5, `MT-DT-05`), run a data sync from the
   home screen so the client downloads the diagnostic-mode setting, then close it (`-Stop`) and start it again so
   the setting is read. With diagnostic mode on, the liveness window shows extra lines (scores and the reason for a
   failure).

If the client state is lost (login refused, home screen missing), `bash scripts/demo-desktop.sh --restore-golden`
puts back the onboarded state and runs the scripted demo once (about 10 minutes); switch MockMDS back to the webcam
afterwards, because the demo leaves it on a frame pack. On a machine without a golden snapshot, run
`bash scripts/demo-desktop.sh --onboard` on a fresh client first (about 5 minutes).

## 3. How one test goes

- **Transaction** = one capture from start until the client accepts the face or refuses after the last try
  (3 tries by default). Each try is one **attempt** = one row on the sheet.
- The recorder writes, per attempt: what the screen showed (**PASS**, **FAIL**, or **NO_FACE** when the client
  never found a usable face: "no face", "too dark", "too blurry", "several faces") and the reason line shown in
  diagnostic mode.
- Alternate: about one genuine transaction for every two attacks, so tiredness and light changes hit both.
- Genuine attempts: behave normally and follow the prompts. Attacks: hold the artefact 40-60 cm from the camera and
  try honestly to pass, including answering prompts with the P7 clips.

## 4. Desktop test list (per person)

| Block | Where in the client | Genuine (bona fide) | Attacks |
|---|---|---|---|
| A. Resident registration | New Registration: fill the demographic pages once, go to Biometrics, Face, **Scan**. After a result, press **Scan** again for the next transaction. | C1 x3, C2 x3, C3 x3, C4 x3, C5 x3 | P1 x5, P4 x5, P6 x5, P7 x5 |
| B. Face login with your own face | once: log in as `officer2` with its password; the client asks for operator onboarding: capture your face from the webcam (fingers and irises come from MockMDS). Then log out and log in as `officer2` choosing face | C1 x5 | P1 x3, P4 x3 of your own face |

- Registration blocks accept any person: the face is the resident's.
- In block B the correct result for an attack is a refusal from liveness **before** face matching; a refusal by the
  matcher alone ("face does not match") means liveness let the attack through: write FAIL with reason `MATCH` and
  add a note.

Reason lines: `grep "LIVENESS-DIAG" ../run/desktop/logs/registration.log | tail -20`

## 5. Android test list (optional, per person)

The emulator's front camera is the laptop webcam (`ARC_WEBCAM` defaults to `webcam0`). Close the desktop client
first: the laptop cannot run both smoothly, and the webcam serves one program at a time.

```bash
export JAVA_HOME="$JAVA21_HOME"; export PATH="$JAVA_HOME/bin:$PATH"
bash scripts/android-run-emulator.sh                        # emulator window, installs the debug APK, starts the app
# quick loop, debug build only: open the liveness screen directly with the live camera
adb shell am start -W -n io.mosip.registration_client/io.mosip.registration.livenessmanager.ui.LivenessCaptureActivity \
  --es liveness.workflow registration --es liveness.subject selftest --es liveness.language eng
```

- Test list: C1 x3, C2 x3, P1 x5, P4 x5, P6 x5 (sheet `platform` = `android`).
- Reason lines: `adb logcat -d -s Liveness | tail -20`
- The full app flow (log in, New Registration, face) also uses the camera; the command above only saves typing.
- A real phone gives a truer result than the emulator: enable USB debugging, then
  `bash scripts/android-build.sh --abis arm64-v8a`, `adb install -r -g clients/android/build/app/outputs/flutter-apk/app-debug.apk`,
  `adb reverse tcp:8080 tcp:8080`, `bash scripts/android-mocksync-setup.sh --login` (registers the phone with
  MockSync) and `bash scripts/android-liveness-device-cert.sh --csr` (the app creates its device key in the Android Keystore and the script has MockSync's device CA sign it; this also works on release builds, the older run-as route without `--csr` only on debug builds). With the emulator stopped, `adb` talks to the phone.
  Not tried yet by the team: write down what fails.

## 6. The results sheet

Copy [self-test-sheet.csv](self-test-sheet.csv) (its rows are examples; delete them) and fill one row per attempt:

| Column | Values |
|---|---|
| `txn_id` | T001, T002, ... unique per platform |
| `platform` | `desktop` or `android` |
| `subject_id` | S1-S4 (pseudonym; the name list stays on paper) |
| `kind` | `bona_fide` or `attack` |
| `species` | `BONA_FIDE`, or P1, P3, P4, P6, P7 |
| `condition` | C1-C5 for genuine rows, empty for attacks |
| `workflow` | `REGISTRATION` or `LOGIN` |
| `attempt_no` | 1, 2, 3 |
| `result` | `PASS`, `FAIL`, `NO_FACE` |
| `reason` | from the diagnostic line, for example `PAD`, `ACTIVE`, `TIMEOUT`, `INTEGRITY`, `too dark` |
| `match` | optional: `PASS` or `FAIL`, the face matcher's result, when the recorder saw it (sign-in rows) |
| `artefact_id` | optional: label of the physical artefact (A1, A2, ...), so the report can count source artefacts |
| `notes` | anything odd (never a name) |

Then:

```bash
python tools/eval/sheet-to-attempts.py results-team.csv --out ../run/selftest/attempts.jsonl --model-set mosip-onnx-default@1.3.0
python tools/eval/analyze.py ../run/selftest/attempts.jsonl --out ../run/selftest/report.md
```

A row with `result` FAIL and `reason` MATCH is converted as liveness passed and matcher refused (liveness ACCEPT, `match_outcome` REJECT), never as a liveness rejection. In sign-in workflows a PASS row means liveness and the matcher both accepted. The report leads with APCER per attack type per transaction and on the first attempt (share of attacks accepted), then BPCER (share of genuine attempts refused), per lighting condition and per person, with confidence intervals labelled not independent where one artefact is replayed; IAPAR and FRR appear for the sign-in rows that carry a match result. Keep the CSV and report in `../run/selftest/`
(outside git) until the team agrees to publish the aggregated report under `docs/eval/results/`.

## 7. What to look for, and when to stop

- **Any accepted attack** (PASS on an attack row): note the species, light and prompt; it is the most useful
  finding of the session.
- **Genuine refusals above about 1 in 5**: note the reason codes. Frequent `ACTIVE` or `TIMEOUT` points at the
  challenge thresholds, frequent `PAD` at the passive threshold, frequent NO_FACE in C2/C3 at the lighting checks.
- Stop and restart the programs if the camera picture freezes or the client shows "The face camera is not
  available" (MockMDS stopped or lost the webcam: repeat the `/admin/source` command).
- Thresholds may be changed only between sessions, never during one, and every change is written into
  [calibration-notes.md](calibration-notes.md). Before the volunteer sessions the thresholds are frozen
  (protocol section 8, step 4).
