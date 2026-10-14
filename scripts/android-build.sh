#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Builds the Android Registration Client (clients/android) debug APK and,
# optionally, runs its native unit tests.
#
# Steps:
#   0. Face liveness engine: when io.mosip.liveness:liveness-core,
#      liveness-onnx or face-match-sdk is not in the local Maven repository
#      (~/.m2), all three are built from this repository's liveness/ folder
#      with Maven and installed there, with the tests jar of liveness-core
#      that the parity test uses (the livenessmanager module and its
#      Gradle build read them from ~/.m2; no binary jar is committed).
#      --install-engine rebuilds them.
#   1. Code generation: flutter pub get, build_runner and the pigeon bridge
#      classes, driven by the upstream clients/android/pigeon.sh. The iOS
#      outputs of pigeon.sh are redirected to clients/android/build/ so the
#      tracked ios/Runner/pigeon.{h,m} files stay unchanged.
#   2. flutter build apk --debug (or --release), with
#        - the server URL (debug: ARC_DEBUG_SERVER_BASE_URL; release: ARC_RELEASE_SERVER_BASE_URL,
#          both read by android/build.gradle),
#        - the monorepo commit passed as --dart-define=ARC_GIT_COMMIT/ARC_GIT_BRANCH
#          (shown in the app footer; upstream read it from a bundled .git folder).
#   3. With --test: ./gradlew :clientmanager:testDebugUnitTest
#      :livenessmanager:testDebugUnitTest :app:testDebugUnitTest and a pass/fail
#      summary per module. --test-only skips steps 1 and 2 when their outputs
#      exist; on a fresh clone it runs the code generation (step 1) and creates
#      the git-ignored Gradle wrapper from Flutter's copy, as a build would.
#   4. With --device-test: ./gradlew :livenessmanager:connectedDebugAndroidTest
#      on the attached emulator or device (the real engine on replay frames).
#
# Usage (Git Bash on Windows, or bash on Linux/macOS):
#   scripts/android-build.sh                         # debug APK, server http://127.0.0.1:8080
#   scripts/android-build.sh --server-url URL        # any other debug server URL
#   scripts/android-build.sh --server-url ""         # keep upstream serverBaseURL
#   scripts/android-build.sh --skip-codegen          # reuse generated code from the last run
#   scripts/android-build.sh --test                  # also run the native unit tests
#   scripts/android-build.sh --test-only             # only run the unit tests
#   scripts/android-build.sh --device-test           # also run the liveness instrumented test
#   scripts/android-build.sh --install-engine        # rebuild the liveness engine jars first
#   scripts/android-build.sh --abis x86_64           # only these CPU types (comma list); smaller APK
#   scripts/android-build.sh --release --abis arm64-v8a   # release APK for arm64 tablets, server
#                                                    # http://127.0.0.1:8080 (MockSync through adb reverse),
#                                                    # signed with the release keystore (required)
#   scripts/android-build.sh --release --server-url https://mosip.example.org   # release for a real server
#   scripts/android-build.sh --release --allow-debug-signing   # size checks only: debug key allowed
#   scripts/android-build.sh --release --app-id-suffix .release  # installable next to the debug app
#
# Release signing: scripts/android-release-keystore.sh creates the keystore outside the repository and writes
# its path and passwords into android/local.properties (git-ignored); CI passes ARC_RELEASE_STORE_FILE,
# ARC_RELEASE_STORE_PASSWORD, ARC_RELEASE_KEY_ALIAS and ARC_RELEASE_KEY_PASSWORD instead.
#
# Requirements (see docs/install/android.md):
#   flutter 3.10.4 on PATH, JAVA_HOME = JDK 21, ANDROID_HOME = Android SDK with
#   platforms;android-34, build-tools;34.0.0 and ndk;23.1.7779620;
#   Maven 3.6+ on PATH the first time (to build the liveness engine).
#
# Environment:
#   ARC_SERVER_URL            default for --server-url (http://127.0.0.1:8080)
#   ARC_ABIS                  default for --abis (read by android/app/build.gradle)
#   ARC_KEEP_FLUTTER_DESKTOP  set to 1 to leave Flutter's desktop targets enabled
#                             (see the note in disable_desktop_targets)
#   PUB_CACHE                 Dart package cache; on Windows it must be on the same
#                             drive as this repository (see use_same_drive_pub_cache)
#   MOSIP_RUN_ROOT            parent of the default same-drive package cache
#                             (default: the folder next to this repository, ../run;
#                             for a clone inside that folder, the folder itself)
#   MAVEN_LOCAL_REPO          local Maven repository the liveness engine is installed
#                             into and read from, by Maven and by Gradle (default
#                             ~/.m2/repository); a separate one keeps the engine of
#                             one checkout from replacing that of another
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARC_DIR="$REPO_ROOT/clients/android"
BUILD_DIR="$ARC_DIR/build"
BUILD_TYPE=debug
ABIS="${ARC_ABIS:-}"

SERVER_URL="${ARC_SERVER_URL-http://127.0.0.1:8080}"
DO_CODEGEN=1
DO_BUILD=1
DO_TEST=0
DO_DEVICE_TEST=0
FORCE_ENGINE=0
ALLOW_DEBUG_SIGNING=0
APP_ID_SUFFIX=""

LOG_TAG=android-build; . "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

while [ $# -gt 0 ]; do
  case "$1" in
    --server-url) [ $# -ge 2 ] || die "--server-url needs a value"; SERVER_URL="$2"; shift 2 ;;
    --skip-codegen) DO_CODEGEN=0; shift ;;
    --test) DO_TEST=1; shift ;;
    --test-only) DO_CODEGEN=0; DO_BUILD=0; DO_TEST=1; shift ;;
    --device-test) DO_DEVICE_TEST=1; shift ;;
    --install-engine) FORCE_ENGINE=1; shift ;;
    --release) BUILD_TYPE=release; shift ;;
    --abis) [ $# -ge 2 ] || die "--abis needs a value"; ABIS="$2"; shift 2 ;;
    --allow-debug-signing) ALLOW_DEBUG_SIGNING=1; shift ;;
    --app-id-suffix) [ $# -ge 2 ] || die "--app-id-suffix needs a value"; APP_ID_SUFFIX="$2"; shift 2 ;;
    -h|--help) sed -n "2,$(( $(grep -n '^set -euo pipefail' "${BASH_SOURCE[0]}" | cut -d: -f1) - 1 ))p" "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done
APK="$BUILD_DIR/app/outputs/flutter-apk/app-$BUILD_TYPE.apk"

# Flutter's --target-platform values for the Android CPU types in $ABIS (x86 has no Flutter release engine).
flutter_platforms() {
  local abi out=""
  for abi in ${ABIS//,/ }; do
    case "$abi" in
      arm64-v8a) out="$out,android-arm64" ;;
      armeabi-v7a) out="$out,android-arm" ;;
      x86_64) out="$out,android-x64" ;;
    esac
  done
  printf '%s' "${out#,}"
}

check_tools() {
  command -v flutter >/dev/null 2>&1 || die "flutter is not on PATH (Flutter 3.10.4 is required)"
  [ -n "${JAVA_HOME:-}" ] || die "JAVA_HOME is not set (JDK 21 is required)"
  [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] || die "ANDROID_HOME is not set"
  local java_version
  java_version="$("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"
  case "$java_version" in
    *'"21'*) ;;
    *) die "JAVA_HOME must point to JDK 21 (AGP 8.3.2 + Java 21 sources); found: $java_version" ;;
  esac
  local flutter_version
  # head exits after one line; Flutter then fails writing its first-run banner (exit 255), which set -e must not see.
  flutter_version="$(flutter --version 2>/dev/null | head -n 1)" || true
  case "$flutter_version" in
    'Flutter 3.10.4 '*) ;;
    *) log "WARNING: upstream builds with Flutter 3.10.4; found: $flutter_version" ;;
  esac
}

# Flutter creates plugin symlinks for the Windows and Linux targets on every
# 'pub get'. On Windows that needs Developer Mode (admin rights), and with the
# desktop targets enabled 'pub get' also rewrites tracked desktop plugin
# registrants. The Registration Client ships for Android only, so the desktop
# targets are switched off in the user's Flutter settings.
disable_desktop_targets() {
  if [ "${ARC_KEEP_FLUTTER_DESKTOP:-0}" = "1" ]; then
    return
  fi
  log "switching off Flutter desktop targets (set ARC_KEEP_FLUTTER_DESKTOP=1 to skip)"
  flutter config --no-enable-windows-desktop --no-enable-linux-desktop \
    --no-enable-macos-desktop >/dev/null
}

# On Windows, Gradle fails with "this and base files have different roots" when
# the Flutter plugin sources (in the Dart package cache, by default on C:) and
# the Gradle build folder (clients/android/build) are on different drives.
# When PUB_CACHE is not set and the drives differ, a package cache on the
# repository's drive is used instead.
use_same_drive_pub_cache() {
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) ;; *) return ;; esac
  if [ -n "${PUB_CACHE:-}" ] || [ -z "${LOCALAPPDATA:-}" ]; then
    return
  fi
  local repo_drive cache_drive
  repo_drive="$(cygpath -w "$REPO_ROOT" | cut -c1 | tr '[:lower:]' '[:upper:]')"
  cache_drive="$(printf '%s' "$LOCALAPPDATA" | cut -c1 | tr '[:lower:]' '[:upper:]')"
  if [ "$repo_drive" != "$cache_drive" ]; then
    local parent run_root
    parent="$(cd "$REPO_ROOT/.." && pwd)"
    run_root="${MOSIP_RUN_ROOT:-$parent/run}"
    # A clone made inside the run folder (for example a fresh-clone check) reuses its package cache.
    if [ -z "${MOSIP_RUN_ROOT:-}" ] && [ ! -d "$run_root/cache/pub-cache" ] && [ -d "$parent/cache/pub-cache" ]; then
      run_root="$parent"
    fi
    mkdir -p "$run_root/cache/pub-cache"
    PUB_CACHE="$(cygpath -w "$run_root/cache/pub-cache")"
    export PUB_CACHE
    log "repository is on drive $repo_drive:, so PUB_CACHE=$PUB_CACHE"
  fi
}

# The livenessmanager module (and its Gradle build script, which verifies the
# signed model bundle with the engine's ModelRegistry) needs the liveness engine
# in the local Maven repository: liveness-core, liveness-onnx and the face
# matcher face-match-sdk (operator and supervisor face authentication). The
# version is the one android/build.gradle asks for (livenessEngineVersion).
ENGINE_ARTIFACTS="liveness-core liveness-onnx face-match-sdk"
MAVEN_LOCAL_REPO_NATIVE=""

# With MAVEN_LOCAL_REPO set, Gradle (the unit tests and 'flutter build apk') must read the engine from
# that repository too: its mavenLocal() follows the maven.repo.local system property, which Gradle
# passes on from GRADLE_OPTS to the build.
use_maven_local_repo() {
  [ -n "${MAVEN_LOCAL_REPO:-}" ] || return 0
  mkdir -p "$MAVEN_LOCAL_REPO"
  MAVEN_LOCAL_REPO="$(cd "$MAVEN_LOCAL_REPO" && pwd)"
  local native="$MAVEN_LOCAL_REPO"
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) native="$(cygpath -w "$MAVEN_LOCAL_REPO")" ;; esac
  case "$native" in *" "*) die "MAVEN_LOCAL_REPO must not contain spaces: $native" ;; esac
  MAVEN_LOCAL_REPO_NATIVE="$native"
  export GRADLE_OPTS="${GRADLE_OPTS:+$GRADLE_OPTS }-Dmaven.repo.local=$native"
  log "local Maven repository for the liveness engine: $native"
}
ensure_liveness_engine() {
  local version repo jar missing="" artifact
  version="$(sed -n 's/^ *livenessEngineVersion *= *"\([^"]*\)".*/\1/p' "$ARC_DIR/android/build.gradle" | head -n 1)"
  [ -n "$version" ] || die "livenessEngineVersion not found in android/build.gradle"
  repo="${MAVEN_LOCAL_REPO:-$HOME/.m2/repository}"
  for artifact in $ENGINE_ARTIFACTS; do
    jar="$repo/io/mosip/liveness/$artifact/$version/$artifact-$version.jar"
    [ -f "$jar" ] || missing="$missing $artifact"
  done
  # The tests jar of liveness-core holds the cross-client parity replay (livenessmanager LivenessParityTest).
  [ -f "$repo/io/mosip/liveness/liveness-core/$version/liveness-core-$version-tests.jar" ]     || missing="$missing liveness-core:tests"
  if [ -z "$missing" ] && [ "$FORCE_ENGINE" = "0" ]; then
    log "liveness engine $version ($ENGINE_ARTIFACTS) found in $repo"
    return
  fi
  [ -z "$missing" ] || log "not in $repo:$missing"
  command -v mvn >/dev/null 2>&1 || die "Maven (mvn) is needed once to build the liveness engine (liveness/)"
  log "building the liveness engine $version ($ENGINE_ARTIFACTS) into $repo"
  local args=()
  [ -z "${MAVEN_LOCAL_REPO:-}" ] || args+=("-Dmaven.repo.local=$MAVEN_LOCAL_REPO_NATIVE")
  mvn -q -f "$REPO_ROOT/liveness/pom.xml" ${args[@]+"${args[@]}"} \
      -pl "$(printf '%s' "$ENGINE_ARTIFACTS" | tr ' ' ',')" -am install -DskipTests
  for artifact in $ENGINE_ARTIFACTS; do
    jar="$repo/io/mosip/liveness/$artifact/$version/$artifact-$version.jar"
    [ -f "$jar" ] || die "Maven did not install $jar"
  done
  jar="$repo/io/mosip/liveness/liveness-core/$version/liveness-core-$version-tests.jar"
  [ -f "$jar" ] || die "Maven did not install $jar"
}

run_codegen() {
  log "code generation: pub get, build_runner, pigeon (upstream pigeon.sh)"
  mkdir -p "$BUILD_DIR/pigeon-ios"
  {
    echo 'set -e'
    sed -e 's/\r$//' \
        -e 's#ios/Runner/pigeon\.h#build/pigeon-ios/pigeon.h#g' \
        -e 's#ios/Runner/pigeon\.m#build/pigeon-ios/pigeon.m#g' \
        "$ARC_DIR/pigeon.sh"
  } > "$BUILD_DIR/pigeon-android.sh"
  (cd "$ARC_DIR" && sh build/pigeon-android.sh)

  local expected actual
  expected="$(grep -c '^dart run pigeon' "$ARC_DIR/pigeon.sh")"
  actual="$(ls "$ARC_DIR"/lib/pigeon/*_pigeon.dart 2>/dev/null | wc -l | tr -d ' ')"
  [ "$actual" -ge "$expected" ] || die "pigeon generated $actual of $expected Dart files"
  log "pigeon generated $actual Dart bridge files"
}

# Prints the first missing output of the code generation that the Gradle build
# of the native modules reads (nothing when all exist): local.properties with
# flutter.sdk (settings.gradle), the plugin registrant and the pigeon bridge
# classes (app module). All are git-ignored.
codegen_missing() {
  local expected actual
  if ! grep -q '^flutter\.sdk=' "$ARC_DIR/android/local.properties" 2>/dev/null; then
    echo "android/local.properties"
  elif [ ! -f "$ARC_DIR/android/app/src/main/java/io/flutter/plugins/GeneratedPluginRegistrant.java" ]; then
    echo "GeneratedPluginRegistrant.java"
  else
    expected="$(grep -c '^dart run pigeon' "$ARC_DIR/pigeon.sh")"
    actual="$(ls "$ARC_DIR"/android/app/src/main/java/io/mosip/registration_client/model/*Pigeon.java 2>/dev/null \
      | wc -l | tr -d ' ')"
    [ "$actual" -ge "$expected" ] || echo "pigeon Java classes ($actual of $expected)"
  fi
}

# The Gradle wrapper (gradlew, gradlew.bat, gradle/wrapper/gradle-wrapper.jar)
# is git-ignored; 'flutter build apk' copies it from Flutter's cache
# (bin/cache/artifacts/gradle_wrapper) into android/ when it is missing. The
# same copy is made here so that the tests can run without a build. Existing
# files and the tracked gradle-wrapper.properties (which pins the Gradle
# version) are kept.
ensure_gradle_wrapper() {
  local android="$ARC_DIR/android" file missing=0 flutter_root src
  for file in gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar; do
    [ -f "$android/$file" ] || missing=1
  done
  [ "$missing" = "1" ] || return 0
  # local.properties escapes the path (E\:\\flutter); fall back to the flutter on PATH.
  flutter_root="$(sed -n 's/^flutter\.sdk=//p' "$android/local.properties" 2>/dev/null | head -n 1 \
    | tr -d '\r' | sed -e 's/\\\\/\//g' -e 's/\\:/:/g')"
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) [ -z "$flutter_root" ] || flutter_root="$(cygpath -u "$flutter_root")" ;;
  esac
  if [ -z "$flutter_root" ] || [ ! -d "$flutter_root" ]; then
    flutter_root="$(cd "$(dirname "$(command -v flutter)")/.." && pwd)"
  fi
  src="$flutter_root/bin/cache/artifacts/gradle_wrapper"
  if [ ! -f "$src/gradle/wrapper/gradle-wrapper.jar" ]; then
    log "downloading Flutter's Gradle wrapper (flutter precache --android)"
    flutter precache --android >/dev/null
  fi
  [ -f "$src/gradle/wrapper/gradle-wrapper.jar" ] || die "Gradle wrapper not found in $src"
  log "creating the Gradle wrapper in android/ from $src"
  for file in gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar; do
    if [ ! -f "$android/$file" ]; then
      mkdir -p "$(dirname "$android/$file")"
      cp "$src/$file" "$android/$file"
    fi
  done
  chmod +x "$android/gradlew"
}

build_apk() {
  local commit branch start secs
  commit="$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD 2>/dev/null || true)"
  branch="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  if [ -n "$SERVER_URL" ]; then
    log "$BUILD_TYPE server URL: $SERVER_URL"
  else
    log "$BUILD_TYPE server URL: upstream serverBaseURL"
  fi
  export ARC_DEBUG_SERVER_BASE_URL="$SERVER_URL"
  if [ "$BUILD_TYPE" = "release" ]; then
    export ARC_RELEASE_SERVER_BASE_URL="$SERVER_URL"
    export ARC_RELEASE_APPLICATION_ID_SUFFIX="$APP_ID_SUFFIX"
    if [ "$ALLOW_DEBUG_SIGNING" = "1" ]; then
      export ARC_REQUIRE_RELEASE_SIGNING=0
    else
      export ARC_REQUIRE_RELEASE_SIGNING=1
    fi
  fi
  export ARC_ABIS="$ABIS"
  local extra=()
  if [ -n "$ABIS" ]; then
    log "CPU types: $ABIS"
    [ -z "$(flutter_platforms)" ] || extra+=(--target-platform "$(flutter_platforms)")
  fi

  start="$(date +%s)"
  (cd "$ARC_DIR" && flutter build apk "--$BUILD_TYPE" "${extra[@]}" \
      --dart-define="ARC_GIT_COMMIT=$commit" \
      --dart-define="ARC_GIT_BRANCH=$branch")
  secs=$(( $(date +%s) - start ))
  [ -f "$APK" ] || die "APK not found at $APK"
  log "APK: $APK"
  log "APK size: $(wc -c < "$APK" | tr -d ' ') bytes; build time: ${secs}s"
  if [ "$BUILD_TYPE" = "release" ]; then
    report_signature
  fi
}

# Prints the certificate the release APK is signed with (apksigner from the newest build-tools).
report_signature() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" apksigner
  apksigner="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)apksigner"
  [ -f "$apksigner" ] || apksigner="$apksigner.bat"
  if [ -f "$apksigner" ]; then
    "$apksigner" verify --print-certs "$APK" 2>/dev/null | grep -E 'Signer #1 certificate (DN|SHA-256)'       | sed 's/^/[android-build] /' || log "apksigner could not read the signature"
  fi
}

# Prints totals from the JUnit XML reports of one Gradle test task.
summarise_tests() {
  local dir="$1"
  [ -d "$dir" ] || { log "no test reports in $dir"; return; }
  { cat "$dir"/*.xml 2>/dev/null || true; } | { grep -o '<testsuite [^>]*>' || true; } | awk '
    function attr(s, name,   m) {
      if (match(s, name "=\"[0-9]+\"")) { m = substr(s, RSTART, RLENGTH); gsub(/[^0-9]/, "", m); return m + 0 }
      return 0
    }
    { t += attr($0, "tests"); f += attr($0, "failures"); e += attr($0, "errors"); s += attr($0, "skipped") }
    END { printf "[android-build] tests: %d, failures: %d, errors: %d, skipped: %d, passed: %d\n", t, f, e, s, t - f - e - s }'
}

run_tests() {
  local gradlew="./gradlew"
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) gradlew="./gradlew.bat" ;; esac
  [ -f "$ARC_DIR/android/${gradlew#./}" ] || die "Gradle wrapper missing in $ARC_DIR/android"
  log "native unit tests: :clientmanager:testDebugUnitTest :livenessmanager:testDebugUnitTest :app:testDebugUnitTest"
  export ARC_DEBUG_SERVER_BASE_URL="$SERVER_URL"
  local status=0 module
  (cd "$ARC_DIR/android" && "$gradlew" :clientmanager:testDebugUnitTest \
      :livenessmanager:testDebugUnitTest :app:testDebugUnitTest --continue) || status=$?
  for module in clientmanager livenessmanager app; do
    log "$module:"
    summarise_tests "$BUILD_DIR/$module/test-results/testDebugUnitTest"
    log "HTML report: $BUILD_DIR/$module/reports/tests/testDebugUnitTest/index.html"
  done
  return "$status"
}

# Instrumented test of the liveness module on the attached emulator or device:
# ONNX Runtime and the signed models from the APK assets, on the bundled replay
# frames (liveness-replay/mockmds-still).
run_device_tests() {
  local gradlew="./gradlew"
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) gradlew="./gradlew.bat" ;; esac
  log "instrumented test: :livenessmanager:connectedDebugAndroidTest"
  export ARC_DEBUG_SERVER_BASE_URL="$SERVER_URL"
  local status=0
  (cd "$ARC_DIR/android" && "$gradlew" :livenessmanager:connectedDebugAndroidTest) || status=$?
  summarise_tests "$BUILD_DIR/livenessmanager/outputs/androidTest-results/connected/debug"
  log "HTML report: $BUILD_DIR/livenessmanager/reports/androidTests/connected/debug/index.html"
  return "$status"
}

check_tools
use_same_drive_pub_cache
use_maven_local_repo
ensure_liveness_engine
if [ "$DO_BUILD" = "0" ] && [ "$DO_CODEGEN" = "0" ] && [ "$DO_TEST" = "1" ]; then
  # --test-only: a fresh clone has no generated code; generate it as a build would.
  MISSING_CODEGEN="$(codegen_missing)"
  if [ -n "$MISSING_CODEGEN" ]; then
    log "generated code missing ($MISSING_CODEGEN); running the code generation first"
    DO_CODEGEN=1
  fi
fi
if [ "$DO_CODEGEN" = "1" ] || [ "$DO_BUILD" = "1" ]; then
  disable_desktop_targets
fi
if [ "$DO_CODEGEN" = "1" ]; then run_codegen; fi
if [ "$DO_BUILD" = "1" ]; then build_apk; fi
if [ "$DO_TEST" = "1" ] || [ "$DO_DEVICE_TEST" = "1" ]; then ensure_gradle_wrapper; fi
if [ "$DO_TEST" = "1" ]; then run_tests; fi
if [ "$DO_DEVICE_TEST" = "1" ]; then run_device_tests; fi
log "done"
