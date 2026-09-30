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

Hardware: 16 GB RAM is enough for one client plus the mocks; the Android emulator needs hardware virtualisation
(WHPX) and about 18 GB of disk for Flutter, the Android SDK and Gradle caches.

**Maven mirror.** The upstream desktop POM refers to retired Sonatype repositories. Create a settings file that
routes them to Maven Central and pass it in `MAVEN_ARGS`:

```xml
<!-- C:/tools/maven-settings.xml -->
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>central-for-retired-repos</id>
      <mirrorOf>ossrh,sonatype-snapshots,snapshots-repo,oss-sonatype,spring-milestones,spring-snapshots</mirrorOf>
      <url>https://repo.maven.apache.org/maven2</url>
    </mirror>
  </mirrors>
</settings>
```

**Environment** (for example in `~/.bashrc`; adjust the paths):

```bash
export JAVA11_HOME=/c/tools/zulu11.41.23-ca-fx-jdk11.0.8-win_x64   # or leave unset: desktop-build.sh downloads it
export JAVA21_HOME=/c/tools/jdk-21
export JAVA_HOME="$JAVA11_HOME"
export MAVEN_ARGS="-s C:/tools/maven-settings.xml"
export ANDROID_HOME=/c/tools/android-sdk
export PATH="$JAVA_HOME/bin:/c/tools/maven/bin:/c/tools/flutter/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

## 2. Build and test the liveness engine

```bash
mvn -f liveness/pom.xml install -Dliveness.benchmark.frames=0
```

Builds and installs `liveness-core`, `liveness-onnx` (with the `models` jar) and `face-match-sdk` into `~/.m2`,
runs the tests (core 264, onnx 93 with 2 benchmark tests skipped by that flag, face-match-sdk 42) and the Android
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
   tools/mock-sync/scripts/register-machine.sh "$(cd ../run/desktop && pwd)"   # absolute path; JDK 11 java on PATH; TPM or local keys
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
bash scripts/android-liveness-device-cert.sh                # device certificate from MockSync's DEVICE CA for the built-in camera
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
| Maven cannot resolve `ossrh` artefacts | `MAVEN_ARGS` does not point at the mirror settings |
| Client log `BIO_SDK_007 --> SDK provider registry is empty!` | `mock-sdk.jar` missing in `lib/`; rerun `bash scripts/desktop-build.sh --mocksync --skip-build` |
| Desktop login error, MockSync log shows `KER-SNC-155` | Machine not registered; step 5.2 |
| Liveness says "The face camera is not available" | MockMDS not running or not discovered yet; start it and select Retry |
| Liveness always "Face verification could not be completed" with MockMDS | MockMDS streams its stock still image (frozen stream): select a frame pack with `/admin/source` |
| Android build uses the sandbox URL | Build through `scripts/android-build.sh` (it sets `ARC_DEBUG_SERVER_BASE_URL=http://127.0.0.1:8080`) |
