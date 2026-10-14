# Install and run: clean Windows machine quick start

This page takes a Windows 11 machine with nothing installed to: the liveness engine built and tested, MockSync
(local MOSIP server) and MockMDS (mock face device) running, the desktop Registration Client logged in, and the
Android Registration Client on an emulator. Each step names the script it runs; the detailed pages are
[desktop.md](desktop.md) and [android.md](android.md).

Paths: commands run in **Git Bash** from the repository root unless marked PowerShell. `<repo>` is the clone,
`<run>` is `<repo>/../run` (override with `MOSIP_RUN_ROOT`); build outputs, client state, downloads and frame packs
go there, outside git.

## 1. Install the tools

| Tool | Version used | Needed for | Notes |
|---|---|---|---|
| Git for Windows (Git Bash) | any recent | everything | provides `bash`, `curl`, `unzip`, `sha256sum` |
| Maven | 3.9.x | all Java builds | on `PATH` |
| JDK 11 with JavaFX and `javac` | Azul Zulu 11.0.8 FX (`zulu11.41.23-ca-fx-jdk11.0.8-win_x64`) | desktop client, engine, MockSync | optional: `scripts/desktop-build.sh` downloads it into `<run>/cache/` when `JAVA11_HOME` is missing or incomplete |
| JDK 21 | Temurin 21 | MockMDS, Android build (`sdkmanager`, Gradle) | `JAVA21_HOME` |
| Flutter | 3.10.4 (Dart 3.0.3) | Android client | on `PATH` |
| Android SDK | `platforms;android-34`, `build-tools;34.0.0`, `ndk;23.1.7779620`, `platform-tools`, `cmdline-tools` | Android client | `ANDROID_HOME`; the emulator script installs the emulator and system image |
| Python 3.9+ | with `pip install -r tools/eval/requirements.txt` | frame packs, evaluation, Android demo script | optional for a first run |
| ffmpeg | any recent | frame packs from video; MockMDS webcam source | optional; not bundled |

**Long paths on Windows.** The deepest file in the repository has a 167-character path, so a clone under a deep
folder fails with `Filename too long`. Turn on long paths in Git before you clone:

```bash
git config --global core.longpaths true
git clone https://github.com/Paradox-73/Mosip-face-liveness-pad.git
```

`git clone -c core.longpaths=true <url>` sets it for this clone only.

Hardware: 16 GB RAM is enough for one client plus the mocks; the Android emulator needs hardware virtualisation
(WHPX) and about 18 GB of disk for Flutter, the Android SDK and Gradle caches.

**Maven mirror.** The upstream desktop POM refers to retired Sonatype repositories. The repository ships a
settings file that routes them to Maven Central, [`scripts/release/maven-settings.xml`](../../scripts/release/maven-settings.xml);
pass it in `MAVEN_ARGS` with an absolute path (CI does the same).

**Environment** (for example in `~/.bashrc`; adjust the paths):

```bash
export JAVA11_HOME=/c/tools/zulu11.41.23-ca-fx-jdk11.0.8-win_x64   # or leave unset: desktop-build.sh downloads it
export JAVA21_HOME=/c/tools/jdk-21
export JAVA_HOME="$JAVA11_HOME"
export MAVEN_ARGS="-s C:/src/Mosip-face-liveness-pad/scripts/release/maven-settings.xml"   # your clone
export ANDROID_HOME=/c/tools/android-sdk
export PATH="$JAVA_HOME/bin:/c/tools/maven/bin:/c/tools/flutter/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

## 2. Build and test the liveness engine

```bash
mvn -f liveness/pom.xml install -Dliveness.benchmark.frames=0
```

Builds and installs `liveness-core`, `liveness-onnx` (with the `models` jar) and `face-match-sdk` into `~/.m2`,
runs the tests (2 benchmark tests skipped by that flag; counts in [../test-report/README.md](../test-report/README.md) section 2) and the Android
API 28 check. Both clients take the engine from `~/.m2`.

## 3. Start MockSync (local MOSIP server)

```bash
cd tools/mock-sync
scripts/run-mock-sync.sh            # builds target/mock-sync.jar on first use; HTTPS :8443, HTTP :8080; Ctrl+C stops
```

Check from a second shell: `curl -s http://127.0.0.1:8080/v1/syncdata/actuator/health` answers `{"status":"UP"}`.
The first start writes keys and the HTTPS certificate to `tools/mock-sync/work/` (never committed; delete the
folder only to start over).

## 4. Start MockMDS (mock face device)

```bash
bash scripts/mockmds-run.sh --build     # needs JAVA21_HOME; builds devices/mock-mds (tests included, about 2 min)
bash scripts/mockmds-run.sh --status    # prints the admin state
```

It runs from `<run>/mockmds/` on `127.0.0.1:4501`, with `<run>/frame-packs` allowed as face sources. Without frame
packs the face device streams MockMDS's stock still image, which the liveness engine correctly refuses as a frozen
stream. For live-looking input, create the packs (optional):

```bash
python tools/eval/fetch-frame-packs.py      # downloads openly licensed clips, cuts packs into <run>/frame-packs
python tools/eval/make-attack-packs.py      # synthetic attack packs
curl -s -X POST http://127.0.0.1:4501/admin/source -d '{"type":"Face","source":"SEQUENCE","path":"<run>/frame-packs/s3-main","fps":15,"loop":true}'
```

(The frames show real people from the licensed clips: keep them out of git and out of screenshots.)

## 5. Desktop Registration Client

1. Build for MockSync (Git Bash):
   ```bash
   bash scripts/desktop-build.sh --mocksync
   ```
   Output: `<run>/desktop/` with `jre/`, `lib/` (client, liveness jars, ONNX Runtime 1.20.0, SFace SDK, MOSIP
   `mock-sdk.jar`), `run.bat`.
2. Register the machine with MockSync before the first login (the client must have started once so its keys exist):
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\desktop-run.ps1 -TrustCert tools\mock-sync\work\keys\tls-cert.pem
   powershell -ExecutionPolicy Bypass -File scripts\desktop-run.ps1 -Stop
   ```
   ```bash
   tools/mock-sync/scripts/register-machine.sh "$(cd "${MOSIP_RUN_ROOT:-../run}/desktop" && pwd)"   # absolute path; JDK 11 java on PATH; TPM or local keys
   ```
3. Start and log in (PowerShell):
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\desktop-run.ps1
   ```
   The pre-loader shows `mosip.hostname : localhost:8443` and `Machine is ONLINE`. Log in with an operator from
   `docs/test-data.md`. The first login runs the initial sync and ends with a restart alert; start again and log in
   again to reach the home screen.
4. Onboard the operator (face through liveness, fingers and irises from MockMDS), then use registration, face
   login and end-of-day approval. Scripted run of every flow: `bash scripts/demo-desktop.sh --restore-golden`
   (needs an onboarded snapshot and the frame packs; see [desktop.md](desktop.md) section 6).

Logs: `<run>/desktop/logs/registration.log` (success line `Login screen loaded`; liveness lines contain `Liveness`).

## 6. Android Registration Client on the emulator

```bash
export JAVA_HOME="$JAVA21_HOME"; export PATH="$JAVA_HOME/bin:$PATH"
bash scripts/android-build.sh                               # debug APK, server http://127.0.0.1:8080; builds the engine if missing;
                                                            # puts the Dart cache on the repository drive (<run>/cache/pub-cache) when needed
ARC_WEBCAM=emulated bash scripts/android-run-emulator.sh --headless   # AVD arc_api34, adb reverse 8080, install, launch
bash scripts/android-mocksync-setup.sh --login              # registers the tablet in MockSync, first login and 7/7 sync
bash scripts/android-liveness-device-cert.sh --csr          # device certificate from MockSync's DEVICE CA for the built-in camera (also on release builds; android.md 5.1)
```

`ARC_WEBCAM=emulated` keeps the laptop webcam out; automated runs replay frame packs instead
(`scripts/android-liveness-demo.sh`, `scripts/demo-android.sh`). Details, troubleshooting and the full demo:
[android.md](android.md) sections 2-8.

## 7. Check that everything answers

| Check | Command | Expected |
|---|---|---|
| MockSync | `curl -s http://127.0.0.1:8080/v1/syncdata/actuator/health` | `{"status":"UP"}` |
| MockSync liveness config | `curl -s http://127.0.0.1:8080/admin/config/mosip.registration.liveness.enable` | `{"key":"mosip.registration.liveness.enable","value":"Y"}` |
| MockMDS | `curl -s -X MOSIPDISC http://127.0.0.1:4501/device -d '{"type":"Face"}'` | one face device, `"deviceStatus":"Ready"` |
| Desktop client | `<run>/desktop/logs/registration.log` | `Liveness engine mosip-onnx-default@1.0.0 ready in ... ms` after start-up |
| Android | `adb logcat -s Liveness` | `Liveness engine ready: mosip-onnx-default@1.0.0` |

## 8. Stop

```bash
bash scripts/mockmds-run.sh --stop
bash scripts/android-run-emulator.sh --stop
```
```powershell
powershell -ExecutionPolicy Bypass -File scripts\desktop-run.ps1 -Stop
```
MockSync: Ctrl+C in its window.

## 9. Common problems

| Symptom | Cause and fix |
|---|---|
| Maven: `Missing mandatory jurisdiction policy files: unlimited` or `release version 11 not supported` | The JDK 11 is a trimmed runtime without `javac` and policy files; use a full JDK or unset `JAVA11_HOME` so `desktop-build.sh` downloads one |
| `git clone` or `git checkout` fails with `Filename too long` | Long paths are off in Git; run `git config --global core.longpaths true` and clone again (section 1) |
| Maven cannot resolve `ossrh` artefacts | `MAVEN_ARGS` does not point at the mirror settings |
| Client log `BIO_SDK_007 --> SDK provider registry is empty!` | `mock-sdk.jar` missing in `lib/`; rerun `bash scripts/desktop-build.sh --mocksync --skip-build` |
| Desktop login error, MockSync log shows `KER-SNC-155` | Machine not registered; step 5.2 |
| Liveness says "The face camera is not available" | MockMDS not running or not discovered yet; start it and select Retry |
| Liveness always "Face verification could not be completed" with MockMDS | MockMDS streams its stock still image (frozen stream): select a frame pack with `/admin/source` |
| Android build uses the sandbox URL | Build through `scripts/android-build.sh` (it sets `ARC_DEBUG_SERVER_BASE_URL=http://127.0.0.1:8080`) |

## 10. Evaluate in 15 minutes with your own webcam

For a reviewer with a Windows laptop and a webcam: see the liveness check accept your own live face, refuse a
photo of you, and let you sign in with your face, in the desktop Registration Client. The 15 minutes are
hands-on time after the tools of section 1 are installed and the builds of sections 2-5 have finished once. A
first build downloads its dependencies and takes longer; the team has not timed it on a clean machine.

**Status on 2026-10-14: the team has not yet run these steps end to end** (the team's steps and results sheet: [manual-test-checklist.md](../manual-test-checklist.md) section C, `MT-DT-01` to `MT-DT-19`). What has been run, and what has not:

| Part | State (dated in each row) | Where |
|---|---|---|
| Engine build and tests; MockSync, MockMDS, desktop client build and login; every liveness flow with recorded frame packs instead of a webcam | run on 2026-09-30 at the earlier defaults (supervisor workflows passive first); 41 of 41 checks; at the shipped policy only the 2026-10-14 run with the challenge pool cut to blink and smile (5 of 16), because the enrolled face has no turn recordings | [../test-report/desktop-e2e.md](../test-report/desktop-e2e.md) |
| MockMDS webcam source (`FFMPEG`) streaming a laptop's built-in camera (first frame after about 1.2 s) | run, outside the client; ffmpeg command lines and a live ffmpeg test pattern are covered by MockMDS tests | `devices/mock-mds/LIVENESS-EXTENSIONS.md` sections 2 and 5 |
| A live face from a webcam passing liveness in the desktop client | **not run** | [../eval/team-self-test.md](../eval/team-self-test.md) (status of 2026-10-14) |
| Onboarding a live face from the webcam, then face sign-in with it | **not run** | same |
| A printed photo or a phone screen held to the webcam | **not run**; results with synthetic attacks: [../eval/calibration-notes.md](../eval/calibration-notes.md) | same |
| This section followed start to finish on a clean machine, timed | **not run** | — |

If a step fails, section 10.3 lists the usual causes. Please tell us what happened (the screen text and the last
lines of `<run>/desktop/logs/registration.log`).

### 10.1 Steps

Commands run in Git Bash from the repository root unless marked PowerShell.

| # | Step | Target time | Command or action |
|---|---|---|---|
| 1 | Put ffmpeg on `PATH` (MockMDS reads the webcam through it; not bundled because of its GPL licence) | 2 min | for example `winget install --id Gyan.FFmpeg`, then open a new Git Bash; check with `ffmpeg -version` |
| 2 | Find the webcam's name | 1 min | `ffmpeg -hide_banner -list_devices true -f dshow -i dummy` prints it in quotes, for example `Integrated Camera`. Virtual cameras (OBS, ManyCam, XSplit, names containing `Virtual`) are refused |
| 3 | Start MockSync, the local stand-in for the MOSIP server, in its own window | 1 min | `(cd tools/mock-sync && scripts/run-mock-sync.sh)`; check: `curl -s http://127.0.0.1:8080/v1/syncdata/actuator/health` answers `{"status":"UP"}` |
| 4 | Start MockMDS, the mock face device, and switch its face camera to the webcam | 1 min | `bash scripts/mockmds-run.sh` (add `--build` the first time), then the `curl` command below |
| 5 | Start the desktop client and log in | 3 min | Once: build and register the machine (section 5 steps 1-2). Then, in PowerShell, `powershell -ExecutionPolicy Bypass -File scripts\desktop-run.ps1` and log in as `officer1` (password in [../test-data.md](../test-data.md)). The first login syncs and asks for a restart: start again and log in again |
| 6 | Onboard your own face | 3 min | Log out and log in as `officer2`; the client asks for operator onboarding. Capture the face: the liveness window opens and uses your webcam (screens in 10.2). Fingers and irises come from MockMDS's stock images. Save |
| 7 | Register a resident: you | 2 min | New Registration, fill the demographic pages, Biometrics, Face, **Scan** |
| 8 | Try an attack | 1 min | **Scan** again and hold a photo of yourself (printed, or on a phone at full brightness) 40-60 cm in front of the webcam; answer the prompts with it if you can |
| 9 | Sign in with your face | 1 min | Log out, log in as `officer2` and choose face: liveness runs first, then the match against your onboarding face |

Step 4: switch the face camera of MockMDS to the webcam (use the name from step 2). The answer starts with
`{"errorCode":"0","errorInfo":"Success"`.

```bash
curl -s -X POST http://127.0.0.1:4501/admin/source \
  -d '{"type":"Face","source":"FFMPEG","camera":"Integrated Camera","fps":15}'
```

The webcam light turns on only while the client streams. The setting lasts until MockMDS stops; after a restart
of MockMDS, send the command again. All frame-source options: `devices/mock-mds/LIVENESS-EXTENSIONS.md`.

### 10.2 What you should see

MockSync ships the policy `ALWAYS_ACTIVE` for resident capture, onboarding and face sign-in, so every capture asks
for actions. The prompts appear on the client's screen; here you are operator and resident at once.

| Moment | Screen | Picture |
|---|---|---|
| Liveness window opens | title "Face verification", camera preview with a dashed oval, "Look directly at the camera.", "Attempt 1 of 3" | [d-r1-positioning.png](../ux/screenshots/desktop/d-r1-positioning.png) |
| Passive check | "Checking face liveness..." with a busy indicator; nothing to do. Registration, face sign-in and onboarding always continue with actions after it (the shipped policy is `ALWAYS_ACTIVE`) | [wireframe sheet](../ux/wireframes.svg), row 2 |
| Actions | for example "Please smile", "Please blink", "Please turn your head to the left", with an icon and "Step 1 of 2"; 5 s per action by default | [e2e-03-low-confidence-challenge.png](../ux/screenshots/desktop/e2e-03-low-confidence-challenge.png) |
| After each action | "Action detected"; after the last one "Look directly at the camera and hold still." until your face is frontal and neutral, then "Hold still" while the device takes the signed capture | wireframe sheet, rows 4 and 5 |
| Pass | "Good, face captured successfully", green oval; the window closes after about 1 s and the capture screen shows your face | wireframe sheet, row 6 |
| Not enough evidence of liveness | "We could not verify face liveness. Please try again." with a guidance line and Retry | [e2e-05-print-refused.png](../ux/screenshots/desktop/e2e-05-print-refused.png) |
| Suspected attack | only "Face verification could not be completed. Please try again.", never the reason | [d-r7-pad-fail-retry.png](../ux/screenshots/desktop/d-r7-pad-fail-retry.png) |
| Dim light | "We could not verify face liveness. Please try again." with "Improve lighting."; the first three dark tries of one capture do not use an attempt, the fourth does |
| Third failure | recovery message with a lock icon, Close | [e2e-07-max-attempts-guidance.png](../ux/screenshots/desktop/e2e-07-max-attempts-guidance.png) |
| Face sign-in by someone else | liveness passes, then "Face match not found" | [e2e-12-face-match-not-found.png](../ux/screenshots/desktop/e2e-12-face-match-not-found.png) |

Every state of both clients, side by side: [../ux/wireframes.svg](../ux/wireframes.svg).

### 10.3 If it does not work

| Symptom | Cause and fix |
|---|---|
| The `curl` command answers error `512` | ffmpeg not found, or the webcam is busy: close other programs that use the camera (video calls), check `ffmpeg -version`, send the command again |
| The `curl` command answers error `511` | the request is malformed, for example a wrong source name; copy the command again |
| "The face camera is not available" | MockMDS not running, or it lost the webcam: `bash scripts/mockmds-run.sh --status`, send the `curl` command again, select Retry |
| Always "Face verification could not be completed" and the webcam light stays off | MockMDS still streams its stock still image: the `curl` command was not accepted |
| One kind of action fails for you again and again | limit the actions to blink and smile, as in the team's recorded desktop runs: `curl -s -X PUT http://127.0.0.1:8080/admin/config -H 'Content-Type: application/json' -d '{"mosip.registration.liveness.active.challenge_types":"BLINK,SMILE"}'`, then Sync Data in the client |
| Genuine attempts fail in dim light | face a window or a lamp: the synthetic dim-light clip is refused by the passive check in every attempt (the client now asks for better lighting for the first three tries; real dim-room recordings are still missing) ([../eval/calibration-notes.md](../eval/calibration-notes.md) section 4.5) |
| You want to see why an attempt failed | diagnostic mode: [../eval/team-self-test.md](../eval/team-self-test.md) section 2, item 3; reason lines: `grep LIVENESS-DIAG <run>/desktop/logs/registration.log` |

The Android client can use the webcam too (the emulator's front camera is the PC webcam, `ARC_WEBCAM`; see
[android.md](android.md)). It is not part of these 15 minutes, and the webcam serves one program at a time.
