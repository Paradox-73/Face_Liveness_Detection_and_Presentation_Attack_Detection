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
| RAM | 16 GB machine | Gradle runs with `-Xmx1536M` (`android/gradle.properties`); the emulator uses 2 GB. |

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
```

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
| `:clientmanager:testDebugUnitTest` | 1184 tests in 63 classes: 1184 passed, 0 failed, 0 errors, 0 skipped (Gradle task 2 min 23 s) |
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

Release builds are unchanged: `BuildConfig.BASE_URL` stays `serverBaseURL`
(checked with `ARC_DEBUG_SERVER_BASE_URL` set during a release build), and the
release APK carries clientmanager's original network security config, which
allows no plain HTTP. Upstream CI's `sed` replacement of the sandbox host still
works because `serverBaseURL` is untouched.

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

It then cold-boots it (`-no-snapshot`, `-gpu swiftshader_indirect`), waits for
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

First login needs a reachable server (MockSync): offline login only works for a
user whose password hash an earlier online login saved
(`AuthenticationApi.offlineLogin` in `android/app`).

## 5. Changes to upstream files for this monorepo

| File | Change | Reason |
|---|---|---|
| `pubspec.yaml` | removed the assets `.git/` and `.git/refs/heads/` | Upstream bundles files from its own `.git` folder to show the commit id in the footer. In this monorepo `clients/android/.git` does not exist: every build prints `Error: unable to find directory entry in pubspec.yaml: ...\.git\` twice (Flutter 3.10.4 still finishes the APK) and the footer shows an empty commit id. |
| `lib/provider/global_provider.dart` | `setGitHeadAttributes()` first uses `--dart-define=ARC_GIT_COMMIT` / `ARC_GIT_BRANCH` when set | Keeps the commit id in the footer. Without the defines it falls back to the upstream code, which already catches the missing asset. |
| `pubspec.lock` | refreshed by `flutter pub get` | The upstream lock file still listed `document_scanner` (removed from `pubspec.yaml`) and lacked `geolocator`, `restart_app` and `uuid` (added to `pubspec.yaml`). No existing package version changed. |
| `.gitignore` | ignore `/test/*.mocks.dart` and `/android/clientmanager/null/` | `build_runner` (run by `pigeon.sh`) generates `test/login_test.mocks.dart`; the clientmanager unit tests write `android/clientmanager/null/null/id.zip`. |
| `android/build.gradle`, `android/app/build.gradle`, `android/clientmanager/build.gradle`, `android/app/src/debug/res/xml/network_security_config.xml` | debug-only server URL and plain-HTTP rule | Section 3. |

## 6. Troubleshooting

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
- **`OutOfMemoryError` in D8 during dex merging.** Upstream notes this can happen
  with `org.gradle.jvmargs=-Xmx1536M`; it did not happen here. Raise the value in
  `~/.gradle/gradle.properties` (for example `-Xmx3g`) rather than in the
  tracked file.
