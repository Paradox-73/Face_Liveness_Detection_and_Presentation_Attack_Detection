# Android Registration Client: build, test and run on an emulator

This page covers building the MOSIP Android Registration Client (ARC) in
`clients/android/` (upstream `mosip/android-registration-client`, branch
`release-1.2.x` at `c4d3ca7`), running its native unit tests, and starting it on
an Android emulator whose front camera is the PC webcam. It also explains how a
debug build is pointed at the local MockSync server in `tools/`.

ARC is a Flutter app (Dart UI in `lib/`) on top of native Android library
modules (`android/clientmanager`, `keymanager`, `packetmanager`,
`transliterationmanager`). Flutter and Java talk through generated
[Pigeon](https://pub.dev/packages/pigeon) classes, which are not committed and
must be generated before every fresh build.

## 1. Prerequisites

| Item | Version used | Notes |
|---|---|---|
| Windows | 11 x64 | Linux and macOS should work with the same scripts (not tested). |
| Git Bash | any | Runs `scripts/android-build.sh` and `scripts/android-run-emulator.sh`. |
| JDK | 21 (Temurin 21.0.12.1) | `JAVA_HOME` must point to it. The Android modules compile with `sourceCompatibility 21`; `sdkmanager`/`avdmanager` need Java 17 or newer. |
| Flutter | 3.10.4 (Dart 3.0.3) | Must be on `PATH`. Upstream CI uses the same version. |
| Android SDK | `platforms;android-34`, `build-tools;34.0.0`, `ndk;23.1.7779620`, `platform-tools` | `ANDROID_HOME` must point to it. The first Gradle build also installs `platforms;android-31` by itself (the `restart_app` 1.2.1 plugin compiles against it). |
| Emulator (optional) | `emulator` 37.1.11, `system-images;android-34;google_apis;x86_64` | The run script installs both when missing. Needs hardware virtualisation (WHPX on Windows). |
| Disk | about 18 GB | Measured: Flutter SDK 2.5 GB; Android SDK 8.7 GB (system image 4.2 GB, NDK 2.5 GB, emulator 1.1 GB); `~/.gradle` 5 GB; Dart package cache 0.2 GB; `clients/android/build/` 2.1 GB after a debug and a release build. |
| RAM | 16 GB machine | Gradle runs with `-Xmx4G` (`android/gradle.properties`); the emulator uses 2 GB. |

One-time setup:

```bash
flutter config --android-sdk "$ANDROID_HOME"
flutter doctor -v        # "Java binary at:" must show the JDK 21 java
```

- Android Studio is not needed. If it is installed, Flutter uses its bundled JDK
  instead of `JAVA_HOME`; check the `Java binary at:` line of `flutter doctor -v`.
- Windows Developer Mode is not needed (see section 6, "symlink support").

## 2. Build the debug APK

From the repository root, in Git Bash:

```bash
bash scripts/android-build.sh                       # debug APK, server http://127.0.0.1:8080
bash scripts/android-build.sh --test                # plus the clientmanager unit tests
bash scripts/android-build.sh --server-url http://10.0.2.2:8080   # another debug server URL
bash scripts/android-build.sh --server-url ""       # keep the upstream sandbox URL
bash scripts/android-build.sh --skip-codegen        # reuse generated code from the last run
bash scripts/android-build.sh --test-only           # only the unit tests
bash scripts/android-build.sh --abis x86_64         # only the emulator's CPU type (smaller APK)
bash scripts/android-build.sh --release --abis arm64-v8a   # signed release APK for arm64 tablets (section 9)
```

`--test` runs the unit tests of `clientmanager`, `livenessmanager` and `app`.
`--abis` (or the environment variable `ARC_ABIS`, read by `android/app/build.gradle`) limits the CPU types packed
into the APK; each one adds about 60 MB of native code (Flutter and ONNX Runtime). `--release` builds
`app-release.apk` signed with the release keystore and pointed at `--server-url` (section 9).

The script:

1. checks that `flutter`, JDK 21 and `ANDROID_HOME` are present;
2. on Windows, when the repository is not on the same drive as the Dart package
   cache, sets `PUB_CACHE` to `<run root>/cache/pub-cache` (section 6, "different
   roots"); `<run root>` is `$MOSIP_RUN_ROOT`, or the folder `run` next to the
   repository, the same as for `scripts/desktop-build.sh`;
3. switches off Flutter's Windows, Linux and macOS targets in the user's Flutter
   settings (ARC ships for Android only; set `ARC_KEEP_FLUTTER_DESKTOP=1` to skip);
4. runs the upstream `clients/android/pigeon.sh` (`flutter pub get`,
   `build_runner`, 18 Pigeon generations), with its iOS outputs redirected to
   `clients/android/build/pigeon-ios/` so the tracked `ios/Runner/pigeon.{h,m}`
   files stay unchanged;
5. runs `flutter build apk --debug` with the debug server URL and the monorepo
   commit (`--dart-define=ARC_GIT_COMMIT=...`, shown in the app footer);
6. prints the APK path, size and build time, and with `--test` runs
   `:clientmanager:testDebugUnitTest` and prints the totals.

Output: `clients/android/build/app/outputs/flutter-apk/app-debug.apk`.

The same steps by hand (from `clients/android/`):

```bash
sh pigeon.sh                          # note: rewrites the tracked ios/Runner/pigeon.{h,m}
ARC_DEBUG_SERVER_BASE_URL=http://127.0.0.1:8080 flutter build apk --debug
cd android && ./gradlew :clientmanager:testDebugUnitTest
```

### Measured on the team laptop (Windows 11, 12 logical CPUs, 16 GB, 2026-09-28)

| Step | Result |
|---|---|
| Code generation (`pigeon.sh`, first run incl. package downloads) | 107 s |
| First complete `assembleDebug` (Gradle dependencies already downloaded by an earlier attempt) | 290 s |
| Rebuild without source changes (`--skip-codegen`) | 40 s |
| Debug APK size | 279,215,183 bytes (266 MiB) when packaged from scratch. Incremental rebuilds can leave free space inside the APK (seen: 304,381,949 bytes with the same 278 MB of content). |
| Release APK (`flutter build apk --release`, debug-signed because no `key.properties`) | 149,398,259 bytes, Gradle 248 s |
| Debug APK with the liveness module (2026-09-30; arm64-v8a and x86_64) | 326,405,878 bytes |
| Release APK with the liveness module, arm64-v8a only (`--release --abis arm64-v8a`, 2026-09-30) | 152,997,540 bytes with uncompressed native libraries; 120,856,656 bytes after release builds store them compressed (the v1.0.0 build of 2026-10-14 is 116,732,329 bytes, [../release/README.md](../release/README.md)) (`androidComponents` block in `android/app/build.gradle`). Content: dex 58.6 MB (not shrunk; upstream keeps `minifyEnabled false`), native libraries 53.1 MB before compression, liveness models 23.6 MB, ICU data 10.5 MB. The x86_64 release build (123,142,904 bytes) started on the emulator and loaded the engine ("Liveness engine ready ... in 1069 ms"). |
| `:clientmanager:testDebugUnitTest` | 1184 tests in 63 classes: 1184 passed, 0 failed, 0 errors, 0 skipped (Gradle task 2 min 23 s); 1,186 tests in the 2026-10-05 verification run |
| `flutter test` (Dart tests in `test/`, not run by upstream CI) | 27 passed; `test/widget_test.dart` does not compile (line 166: required parameter `onTapForgotPassword` of `PasswordComponent` missing). Upstream problem, left unchanged. |

Why the debug APK is large (compressed sizes): native libraries for four CPU
types, about 144 MB (`libflutter.so` alone is 27–34 MB per type); 30 dex files,
about 90 MB, unshrunk and with JaCoCo coverage instrumentation
(`testCoverageEnabled` in the debug build type); and the 24 MB Flutter debug
kernel. The release APK also ships all four CPU types (`abiFilters` in
`android/app/build.gradle`).

## 3. Point a debug build at MockSync

MockSync (the team's local stand-in for the MOSIP server, `tools/`) listens on
HTTP port 8080 for ARC. Debug builds reach it in one of two ways:

| Where ARC runs | Server URL built into the APK | Extra step |
|---|---|---|
| Emulator or USB-connected phone (default) | `http://127.0.0.1:8080` | `adb reverse tcp:8080 tcp:8080` (the run script does this) |
| Emulator, without `adb reverse` | `http://10.0.2.2:8080` | none; `10.0.2.2` is the emulator's address for the PC |

What changed in `clients/android` for this (debug builds only):

- `android/build.gradle`: new `debugServerBaseURL`, taken from the first of
  - the Gradle property `arcDebugServerBaseUrl` (`-P...` or a `gradle.properties` file),
  - the environment variable `ARC_DEBUG_SERVER_BASE_URL`,
  - the key `arcDebugServerBaseUrl` in `android/local.properties`,
  - otherwise `serverBaseURL` (the upstream sandbox URL).
- `android/app/build.gradle` and `android/clientmanager/build.gradle`: the
  `debug` build type sets `BuildConfig.BASE_URL` to `debugServerBaseURL`. The
  Flutter side reads the app's `BASE_URL` through `flutter_config`, the Java side
  reads the clientmanager one, so both follow the same setting.
- `android/app/src/debug/res/xml/network_security_config.xml`: replaces
  clientmanager's `network_security_config.xml` in the debug APK (app resources
  override library resources of the same name). It allows plain HTTP to
  `127.0.0.1`, `localhost` and `10.0.2.2` only.

Release builds do not read `ARC_DEBUG_SERVER_BASE_URL`; they have their own
setting, `arcReleaseServerBaseUrl` (section 9). Without it `BuildConfig.BASE_URL`
stays `serverBaseURL` and the release APK carries clientmanager's original network
security config, which allows no plain HTTP. Upstream CI's `sed` replacement of
the sandbox host still works because `serverBaseURL` is untouched.

Checked on the emulator: with a small request-logging HTTP server on the PC and
`adb reverse`, a login attempt produced `GET /v1/syncdata/actuator/health`,
`GET /v1/syncdata/actuator/info` (both from Dart) and
`POST /v1/syncdata/authenticate/useridpwd` (from Java/OkHttp) on the PC. The app
then showed "Login Failed. Try Again!", as expected without a real server.

The environment variable is upper case on purpose: the `flutter` tool
upper-cases environment variable names on Windows before starting Gradle, so
`ORG_GRADLE_PROJECT_arcDebugServerBaseUrl` arrives as
`ORG_GRADLE_PROJECT_ARCDEBUGSERVERBASEURL` and is ignored.

## 4. Run on the emulator

```bash
bash scripts/android-run-emulator.sh               # emulator window, install, launch, screenshot
bash scripts/android-run-emulator.sh --headless    # no emulator window
bash scripts/android-run-emulator.sh --no-install  # boot + adb reverse only
bash scripts/android-run-emulator.sh --stop        # shut the emulator down
```

The script creates the AVD (emulator profile) `arc_api34` on first use:

- device profile `10.1in WXGA (Tablet)` (1280×800, landscape, like a registration tablet);
- Android 14 (API 34) Google APIs x86_64 image;
- front camera = `webcam0` (the first PC webcam; list them with
  `$ANDROID_HOME/emulator/emulator -webcam-list`), back camera = emulated scene;
- 2 GB RAM, hardware keyboard on.

It then cold-boots it (`-no-snapshot`, `-gpu auto`: the PC's GPU when its driver allows, see section 7), waits for
`sys.boot_completed`, runs `adb reverse tcp:8080 tcp:8080`, installs the APK with
`adb install -r -g` (reinstall keeping data, grant runtime permissions), starts
`MainActivity` and saves a screenshot to `clients/android/build/emulator/`.
Settings can be changed with environment variables (`ARC_AVD_NAME`,
`ARC_AVD_DEVICE`, `ARC_AVD_RAM_MB`, `ARC_EMULATOR_GPU`, `ARC_WEBCAM`,
`ARC_REVERSE_PORT`, ...; see the script header).

Measured on the team laptop: WHPX 10.0.26200 reported usable; headless cold
boot 95 s; the app opened on the Login screen (language selection, user name,
NEXT; `am start -W` returned after 11–13 s). The stock Camera app showed the
live webcam picture on the front camera (webcam started as NV12 1280×960). The emulator log
shows a few `Could not convert from BGR4 to I420` warnings when the camera
starts; the preview works regardless.

Useful on the Login screen:

- The on-screen keyboard can cover the LOGIN button on the 1280×800 screen;
  press Back once to hide it.
- A long press on the MOSIP footer opens the "Device Credentials" dialog
  (seen on the emulator). Its COPY TEXT button puts the machine details
  (`GlobalProvider.machineDetails` as JSON: machine name and public keys, per
  `lib/ui/machine_keys.dart`) on the clipboard; MockSync needs them to register
  the machine. The upstream code creates the machine name and keys on first
  launch, so reinstall with `adb install -r` (the run script does) instead of
  uninstalling.

First login needs a reachable server (MockSync, section 5): offline login only
works for a user whose password hash an earlier online login saved
(`AuthenticationApi.offlineLogin` in `android/app`).

## 5. First login and sync against MockSync

`scripts/android-mocksync-setup.sh` connects the emulator to MockSync and can run
the first login, the initial sync and an offline login by itself (it drives the
app with `adb shell input` and reads the screen with `uiautomator dump`).

```bash
bash tools/mock-sync/scripts/run-mock-sync.sh &                      # or pass --start-mocksync below
bash scripts/android-mocksync-setup.sh                                # emulator, adb reverse, register machine
bash scripts/android-mocksync-setup.sh --login                        # ... then first login + 7/7 sync + home screen
bash scripts/android-mocksync-setup.sh --login --offline-check        # ... then offline login too
bash scripts/android-mocksync-setup.sh --reset --login --offline-check  # start from a wiped app (new machine)
bash scripts/android-mocksync-setup.sh --keys-file keys.json          # register keys copied by hand
```

Steps the script runs, and what each one needs:

1. **MockSync reachable** at `http://127.0.0.1:8080` on the PC
   (`GET /v1/syncdata/actuator/health`).
2. **Emulator booted, `adb reverse tcp:8080 tcp:8080`** (through
   `scripts/android-run-emulator.sh --no-install`), APK installed if missing,
   on-screen keyboard hidden (it drops characters typed with `adb shell input`).
3. **Machine keys read from the app:** long press on the MOSIP footer of the
   login screen, COPY TEXT in the "Device Credentials" dialog, paste into the
   user name field, read the text back. Saved to `<out>/machine-keys.json`
   (`name`, `publicKey`, `signPublicKey`).
4. **Machine registered in MockSync:** `POST /admin/machines` with
   `"clientType":"ANDROID"`. The answer (machine id, centre, key index) is saved
   to `<out>/machine-registration.json`.
5. **`--login`:** user name, NEXT, password, LOGIN. The app then runs the 7 sync
   steps and shows "Sync Completed Successfully". The app is meant to restart
   itself 5 s later (`RestartWidget.restartApp` in `lib/ui/login_page.dart`); the
   script does not rely on that and restarts the app after 8 s, logs in again
   and waits for the home screen ("HELLO, Test Officer One").
6. **`--offline-check`:** removes the `adb reverse` rule so the health check
   fails, logs in again (offline login uses the password hash saved by the
   online login) and restores the rule.

`<out>` defaults to `clients/android/build/emulator/mocksync` (git-ignored);
screenshots `01-device-credentials.png` to `04-home-offline.png` go there too.

Measured on the team laptop (2026-09-29): `--reset --login --offline-check`
took 4 min 24 s from a stopped emulator (boot 65 s) and 2 min 50 s with the
emulator already running; both runs ended on the home screen after the offline
login. MockSync logged, for the first login:
`actuator/health`, `actuator/info`, `authenticate/useridpwd`,
`v2/clientsettings` (twice, each followed by `scripts/applicanttype.mvel`),
`configs/<keyIndex>`, `v2/userdetails`, `latestidschema`, `getCertificate`
(policy key), `getcacertificates`, `getCertificate` (KERNEL SIGN). The second
login sent only `health`, `info`, `useridpwd` and one more `health`; the offline
login sent nothing.

Two `E` lines in `adb logcat -s MasterDataServiceImpl` are expected during the
first sync and do not stop it:

- `Failed to store master data sync last sync time ...
  StringIndexOutOfBoundsException: length=0`: the Flutter side looks up the
  `masterSyncJob` id in the local database before the master data (which holds
  the job list) has been saved, so the first master-data step runs with an empty
  job id (`autoSync` in `lib/provider/sync_provider.dart`). Later steps use real
  ids.
- `Failed to save CA cert : eebe8676...`: MockSync serves the MockMDS FTM root
  `rootcaftm.cer`, which expired in 2022, and the app's keymanager rejects it
  ("Certificate Dates are not valid"). The other DEVICE certificates are saved.

Where the tablet's identity lives (upstream `keymanager` module):

- the signing and encryption key pairs: Android keystore aliases `SIGN` and `ENDEC`;
- the random 12-character machine name: `.mosipkeys/` in the app's data folder,
  encrypted with the `ENDEC` key.

Clearing the app's data (`adb shell pm clear io.mosip.registration_client`, or
`--reset`) creates a new name and new keys on the next launch; the machine must
then be registered again (`--reset` does this, and removes the old registration
from MockSync when the old `machine-keys.json` is in `<out>`). Reinstalling with
`adb install -r` keeps them. Registrations left over from other runs show in
`curl http://127.0.0.1:8080/admin/machines`; remove one with
`curl -X DELETE http://127.0.0.1:8080/admin/machines/<name>` (never remove
`batcomputer`, the desktop client's machine).

If login fails:

| Message in the app | Cause | Fix |
|---|---|---|
| "This device has not been onboarded yet" | MockSync does not know the machine (error `KER-SNC-155`) | register it: run the script again, or `--keys-file` |
| Login fails and the MockSync log shows no request | `adb reverse` missing or MockSync down; app is offline and the user never logged in online | `adb reverse tcp:8080 tcp:8080`; start MockSync |
| The app closes about 5 s after the sync result dialog | a sync step failed (`SystemNavigator.pop` in `lib/ui/login_page.dart`) | `adb logcat -s MasterDataServiceImpl` and the MockSync log |

### 5.1 Give the built-in camera its device certificate (needed to store a face)

The built-in camera signs each face capture with a key kept in the Android Keystore. The client's device trust check
accepts the capture only when that key has a certificate issued by MockSync's DEVICE CA. Without the steps below
liveness runs, but a resident face capture is refused by that check. Works on debug and release builds:

1. Build and install the APK (debug or release, section 2 or 9).
2. Start MockSync once, so `tools/mock-sync/work/keys/device-ca.p12` exists.
3. With the phone or emulator on `adb` (needs `openssl` and `adb`; about 1-2 minutes on a busy emulator, `--timeout S`
   waits longer):

   ```bash
   bash scripts/android-liveness-device-cert.sh --csr
   # or, together with the login: bash scripts/android-mocksync-setup.sh --login --device-cert
   ```

   The script starts the app's provisioning step itself. The app creates its key in the Keystore if missing and
   writes a certificate request; the script has MockSync's DEVICE CA issue the certificate and hands it back; the app
   checks the certificate before it uses it (it must hold the app's own key, be valid now and chain to a CA). The
   private key never leaves the phone. The script prints `provisioned: subject ... issuer CN=MockSync DEVICE CA`.
4. If MockSync's keys were created again (a new DEVICE CA), run `bash scripts/android-liveness-device-cert.sh --csr --replace-ca`;
   without `--replace-ca` the app refuses a certificate from a different CA than the one it already holds.
5. The older path for debug builds only (no `--csr`, uses `adb run-as`) still works.

The files the two sides exchange are in `Android/data/io.mosip.registration_client/files/liveness/` (`device.csr`,
`device-chain.pem`, `status.txt`); the app deletes the request and the chain after it has accepted or refused them.
Checked on 2026-10-05 on the headless emulator `arc_api34` only (status PROVISIONED, certificate chain of 2, replacing the
certificate of a key that already existed); the case of a brand-new key and a release build on a physical phone are
covered by unit tests only. The app cannot read the client's synced CA store, so the client's own DEVICE trust check
still decides at capture time.

## 6. Changes to upstream files for this monorepo

| File | Change | Reason |
|---|---|---|
| `pubspec.yaml` | removed the assets `.git/` and `.git/refs/heads/` | Upstream bundles files from its own `.git` folder to show the commit id in the footer. In this monorepo `clients/android/.git` does not exist: every build prints `Error: unable to find directory entry in pubspec.yaml: ...\.git\` twice (Flutter 3.10.4 still finishes the APK) and the footer shows an empty commit id. |
| `lib/provider/global_provider.dart` | `setGitHeadAttributes()` first uses `--dart-define=ARC_GIT_COMMIT` / `ARC_GIT_BRANCH` when set | Keeps the commit id in the footer. Without the defines it falls back to the upstream code, which already catches the missing asset. |
| `pubspec.lock` | refreshed by `flutter pub get` | The upstream lock file still listed `document_scanner` (removed from `pubspec.yaml`) and lacked `geolocator`, `restart_app` and `uuid` (added to `pubspec.yaml`). No existing package version changed. |
| `.gitignore` | ignore `/test/*.mocks.dart` and `/android/clientmanager/null/` | `build_runner` (run by `pigeon.sh`) generates `test/login_test.mocks.dart`; the clientmanager unit tests write `android/clientmanager/null/null/id.zip`. |
| `android/build.gradle`, `android/app/build.gradle`, `android/clientmanager/build.gradle`, `android/app/src/debug/res/xml/network_security_config.xml` | debug-only server URL and plain-HTTP rule | Section 3. |
| `android/app/build.gradle` | `ARC_ABIS` selects the CPU types of both build types | Section 2 (APK size). |
| `android/gradle.properties` | Gradle heap `-Xmx4G` instead of `-Xmx1536M` | The debug build of a fresh clone ran out of memory in D8 dex merging (section 7). |
| `lib/ui/process_ui/widgets_mobile/biometric_capture_scan_block_portrait.dart` | `validateCaptureException`: a capture whose quality equals the threshold counts as good (`<` instead of `<=`) | Upstream off-by-one: operator onboarding already accepted `quality >= threshold`, registration did not, so the Biometric Details step never enabled CONTINUE for a face of quality 90 with threshold 90 (`docs/design/android-liveness.md` 12.1). |
| `android/app/.../api_services/BiometricsDetailsApi.java`, `android/clientmanager/.../RegistrationServiceImpl.java`, `.../RegistrationDto.java` | exception photo with the built-in camera; liveness fields in the packet meta info; operator face of a face-authenticated packet as officer biometrics | `docs/design/android-liveness.md` section 12. |

## 7. Troubleshooting

- **The emulator disappears every few minutes** (adb: `device 'emulator-5554' not found`). On the team laptop
  the software renderers crashed the emulator process (Windows Application log, event 1000:
  `qemu-system-x86_64-headless.exe`, exception `0xc0000005` in an unknown module, that is, in the renderer's
  generated code), most often on ARC's biometric screens. `-gpu swiftshader_indirect` and `-gpu guest` (which
  falls back to `lavapipe` on this system image) both crashed; `-gpu host` (NVIDIA RTX 3050 laptop GPU, headless)
  ran the whole demo without a crash. `scripts/android-run-emulator.sh` now uses `-gpu auto`; set
  `ARC_EMULATOR_GPU` to force a mode. A Gradle daemon left running after a build also holds about 2 GB of commit
  memory; `./gradlew --stop` (in `clients/android/android`) frees it.
- **"Building with plugins requires symlink support. Please enable Developer
  Mode"** during `flutter pub get` on Windows. Flutter creates plugin symlinks
  for the Windows and Linux targets, which needs Developer Mode (admin rights).
  Either enable Developer Mode or switch the desktop targets off:
  `flutter config --no-enable-windows-desktop --no-enable-linux-desktop --no-enable-macos-desktop`
  (the build script does this).
- **"this and base files have different roots: E:\...\clients\android\build\<plugin> and
  C:\Users\...\Pub\Cache\..."** when Gradle configures a plugin project. The
  repository and the Dart package cache are on different drives. Set `PUB_CACHE`
  to a folder on the repository's drive and run `flutter pub get` again (the
  build script does this).
- **`avdmanager`/`sdkmanager`: `UnsupportedClassVersionError ... class file
  version 61.0`**. `JAVA_HOME` points to Java 11; use JDK 21.
- **Debug APK still uses the sandbox URL.** Check
  `clients/android/build/app/generated/source/buildConfig/debug/io/mosip/registration_client/BuildConfig.java`.
  On Windows, set `ARC_DEBUG_SERVER_BASE_URL`, not
  `ORG_GRADLE_PROJECT_arcDebugServerBaseUrl` (section 3).
- **Warning "One or more plugins require a higher Android NDK version"
  (25.1.8937393).** Only a warning; the build uses NDK 23.1.7779620, the default
  of Flutter 3.10.4, and succeeds.
- **`OutOfMemoryError` in D8 during dex merging, then Flutter asks "Do you want to
  continue with adding multidex support for Android? [y|n]: No element".** The
  multidex question is misleading: the Gradle heap was too small
  (`:app:mergeExtDexDebug`). A fresh clone hit this on 2026-10-15 with upstream's
  `org.gradle.jvmargs=-Xmx1536M`, so `android/gradle.properties` now sets `-Xmx4G`.
  If it still happens, close other builds or raise the value.

## 8. End-to-end liveness demo on the emulator

`scripts/demo-android.sh` drives the real app through every PS04 face flow against MockSync, with the evaluation
frame packs as the camera, and checks the results (report: `docs/test-report/android-e2e.md`).

```bash
ARC_WEBCAM=emulated bash scripts/android-run-emulator.sh --headless   # no webcam attached
bash scripts/android-build.sh && adb install -r -g clients/android/build/app/outputs/flutter-apk/app-debug.apk
bash scripts/demo-android.sh                       # about 35 minutes; output in ../run/demo/android-<time>/
bash scripts/demo-android.sh --sections "login resident"   # some sections only
```

Needs: MockSync on port 8080 with the tablet registered and `officer1` / `supervisor1` onboarded with pack `s3`
(section 5 and `docs/design/android-liveness.md` 11.7); the frame packs in `$MOSIP_RUN_ROOT/frame-packs`
(`tools/eval/fetch-frame-packs.py`; the `s3n-*` packs from `tools/mock-sync/e2e/NoisyPack.java`). The script pushes
the packs it uses to the app's external files folder (`liveness-replay/`), selects them per step through
`liveness-replay/flow-source.txt` (debug builds only; `follow <prefix>` answers the challenge prompts, `masked`
keeps the preview dark) and removes the selection at the end, so the app uses the camera again.

The offline section removes `adb reverse tcp:8080` (MockSync unreachable for the tablet, exactly as if it were
stopped) and switches on airplane mode; it restores both before the final upload.

Output folder: `results.txt` (one PASS/FAIL line per check), `demo.log`, `logcat-<section>.txt`, numbered
screenshots, `packets/<registration id>.liveness.json` (MockSync's decrypted liveness evidence of each uploaded
packet) and `frame-times.txt` (per-frame processing time of every liveness attempt).

The Lost UIN steps tap fixed points on the finger and eye pictures of the exception screens, so the demo expects
the 1280x800 tablet profile that `scripts/android-run-emulator.sh` creates.

## 9. Release APK

A release APK is signed with a release key that never enters the repository, and points at a server chosen at
build time. For the demo the server is MockSync on the PC, reached from the tablet or emulator through
`adb reverse`.

### 9.1 Create the release key (once per build machine)

```bash
bash scripts/android-release-keystore.sh
```

- Creates `../secrets/android-release.jks` next to the repository (RSA 3072, PKCS#12, alias `arc-release`, 30
  years) with a random 32-character password, and `../secrets/android-release.jks.properties` with the password.
  Neither file is in git. An existing keystore is reused, never overwritten. `--keystore PATH` picks another
  location; the script refuses a path inside the repository.
- Writes `storeFile`, `storePassword`, `keyAlias` and `keyPassword` into `clients/android/android/local.properties`
  (git-ignored; the script checks this before writing). `android/app/build.gradle` reads them there, or from
  `key.properties`, or from the environment variables `ARC_RELEASE_STORE_FILE`, `ARC_RELEASE_STORE_PASSWORD`,
  `ARC_RELEASE_KEY_ALIAS`, `ARC_RELEASE_KEY_PASSWORD` (the way to pass them as CI secrets).
- `bash scripts/android-release-keystore.sh --show` prints the certificate's SHA-256 fingerprint.
- Back up the keystore and its properties file: an app signed with a lost key cannot be updated in place.

### 9.2 Build

```bash
bash scripts/android-build.sh --release --abis x86_64               # emulator, server http://127.0.0.1:8080
bash scripts/android-build.sh --release --abis arm64-v8a            # arm64 tablets, same server
bash scripts/android-build.sh --release --server-url https://mosip.example.org --abis arm64-v8a
bash scripts/android-build.sh --release --app-id-suffix .release --abis x86_64   # next to the debug app
bash scripts/android-build.sh --release --allow-debug-signing       # size checks only, debug key
```

- `--server-url` (default `http://127.0.0.1:8080`) becomes `BuildConfig.BASE_URL` of the app and of
  `clientmanager` through `ARC_RELEASE_SERVER_BASE_URL` (Gradle property `arcReleaseServerBaseUrl`, or the same
  name in `local.properties`). `--server-url ""` keeps the upstream `serverBaseURL`.
- Plain HTTP is allowed only for `http://127.0.0.1` and `http://localhost` (the loopback address, which never
  leaves the device): the build then adds `app/src/releaseLoopback/res/xml/network_security_config.xml`. Any other
  release server must use HTTPS.
- The script requires the release key (`ARC_REQUIRE_RELEASE_SIGNING=1`): without it Gradle stops with
  "Release signing is required ..." instead of silently signing with the debug key, which upstream does.
  `--allow-debug-signing` lifts the requirement.
- The script prints the signing certificate of the built APK (`apksigner verify --print-certs`).
- `--app-id-suffix .release` sets `applicationIdSuffix`, so a test device can keep the debug app and its data
  (machine keys, onboarded users) next to the release app. Leave it out for a real release.
- The release APK has no replay frame packs, ignores `config.<key>` extras and cannot be started from adb into
  the capture screen: those are debug-only.

### 9.3 Install and run against MockSync

```bash
adb reverse tcp:8080 tcp:8080
adb install -r clients/android/build/app/outputs/flutter-apk/app-release.apk
```

A fresh install has a new machine key, so MockSync must register the machine again (section 5) before the first
login.

Checked on 2026-09-30: see `docs/design/android-liveness.md` section 13.6 (signed release APK built, installed
with `--app-id-suffix .release`, engine loaded, MockSync reached over `adb reverse`).

## 10. Import a liveness model bundle (offline model update)

New liveness models arrive as a signed bundle: `manifest.json`, `manifest.jws` and the model files, in a folder or
a `.zip`. The app checks the signature against the key built into it, the SHA-256 of every model, and refuses a
version below `mosip.registration.liveness.model.min_version` or not newer than the running bundle. Details:
`docs/design/android-liveness.md` section 14.

1. Copy the bundle to the tablet, into the app's import folder (create it if missing):

   ```bash
   adb shell mkdir -p /sdcard/Android/data/io.mosip.registration_client/files/liveness-models
   adb push valid/. /sdcard/Android/data/io.mosip.registration_client/files/liveness-models/
   ```

   Without adb, copy the files there with a USB file transfer. Put one bundle only in the folder, or a single
   `.zip`; the import reads the folder itself (or the `.zip` path given to it).
2. Sign in to ARC as a supervisor (`REGISTRATION_SUPERVISOR` or `REGISTRATION_ADMIN`). Other users get
   `NOT_ALLOWED`.
3. Start the import. The Flutter call is `LivenessService().importModelBundle('')` (empty path: the import folder
   above). No ARC screen has a button for it yet, so on a test build it is called from Flutter code.
4. Read the result: `IMPORTED` with the new and previous version means the engine runs the new models at once.
   `INVALID` (signature or hash), `TOO_OLD` (version) and `LOAD_FAILED` (models do not start) leave the running
   models active. Logcat tag `Liveness` and the audit events `REG-EVT-LV13/14/15` record the outcome.
5. Delete the copied files from the import folder; the app keeps its own copy in its private storage.

Sample bundles for a trial (a valid, a tampered and an older one) come from
`bash scripts/make-sample-model-bundles.sh --key PRIVATE.pem --out DIR`. They are signed by the key you pass, so a
build only accepts them when that key's public half is one of the app's trust anchors.
