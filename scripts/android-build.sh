#!/usr/bin/env bash
# Builds the Android Registration Client (clients/android) debug APK and,
# optionally, runs its native unit tests.
#
# Steps:
#   0. Face liveness engine: when io.mosip.liveness:liveness-core and
#      liveness-onnx are not in the local Maven repository (~/.m2), they are
#      built from this repository's liveness/ folder with Maven and installed
#      there (the livenessmanager module and its Gradle build read them from
#      ~/.m2; no binary jar is committed). --install-engine rebuilds them.
#   1. Code generation: flutter pub get, build_runner and the pigeon bridge
#      classes, driven by the upstream clients/android/pigeon.sh. The iOS
#      outputs of pigeon.sh are redirected to clients/android/build/ so the
#      tracked ios/Runner/pigeon.{h,m} files stay unchanged.
#   2. flutter build apk --debug, with
#        - the debug-only server URL (Gradle property arcDebugServerBaseUrl),
#        - the monorepo commit passed as --dart-define=ARC_GIT_COMMIT/ARC_GIT_BRANCH
#          (shown in the app footer; upstream read it from a bundled .git folder).
#   3. With --test: ./gradlew :clientmanager:testDebugUnitTest
#      :livenessmanager:testDebugUnitTest and a pass/fail summary per module.
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
#
# Requirements (see docs/install/android.md):
#   flutter 3.10.4 on PATH, JAVA_HOME = JDK 21, ANDROID_HOME = Android SDK with
#   platforms;android-34, build-tools;34.0.0 and ndk;23.1.7779620;
#   Maven 3.6+ on PATH the first time (to build the liveness engine).
#
# Environment:
#   ARC_SERVER_URL            default for --server-url (http://127.0.0.1:8080)
#   ARC_KEEP_FLUTTER_DESKTOP  set to 1 to leave Flutter's desktop targets enabled
#                             (see the note in disable_desktop_targets)
#   PUB_CACHE                 Dart package cache; on Windows it must be on the same
#                             drive as this repository (see use_same_drive_pub_cache)
#   MOSIP_RUN_ROOT            parent of the default same-drive package cache
#                             (default: the folder next to this repository, ../run)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARC_DIR="$REPO_ROOT/clients/android"
BUILD_DIR="$ARC_DIR/build"
APK="$BUILD_DIR/app/outputs/flutter-apk/app-debug.apk"

SERVER_URL="${ARC_SERVER_URL-http://127.0.0.1:8080}"
DO_CODEGEN=1
DO_BUILD=1
DO_TEST=0
DO_DEVICE_TEST=0
FORCE_ENGINE=0

log() { printf '[android-build] %s\n' "$*"; }
die() { printf '[android-build] ERROR: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --server-url) [ $# -ge 2 ] || die "--server-url needs a value"; SERVER_URL="$2"; shift 2 ;;
    --skip-codegen) DO_CODEGEN=0; shift ;;
    --test) DO_TEST=1; shift ;;
    --test-only) DO_CODEGEN=0; DO_BUILD=0; DO_TEST=1; shift ;;
    --device-test) DO_DEVICE_TEST=1; shift ;;
    --install-engine) FORCE_ENGINE=1; shift ;;
    -h|--help) sed -n '2,46p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

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
  flutter_version="$(flutter --version 2>/dev/null | head -n 1)"
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
    local run_root="${MOSIP_RUN_ROOT:-$(cd "$REPO_ROOT/.." && pwd)/run}"
    mkdir -p "$run_root/cache/pub-cache"
    PUB_CACHE="$(cygpath -w "$run_root/cache/pub-cache")"
    export PUB_CACHE
    log "repository is on drive $repo_drive:, so PUB_CACHE=$PUB_CACHE"
  fi
}

# The livenessmanager module (and its Gradle build script, which verifies the
# signed model bundle with the engine's ModelRegistry) needs the liveness engine
# in the local Maven repository. The version is the one android/build.gradle
# asks for (livenessEngineVersion).
ensure_liveness_engine() {
  local version repo jar missing=0 artifact
  version="$(sed -n 's/^ *livenessEngineVersion *= *"\([^"]*\)".*/\1/p' "$ARC_DIR/android/build.gradle" | head -n 1)"
  [ -n "$version" ] || die "livenessEngineVersion not found in android/build.gradle"
  repo="${MAVEN_LOCAL_REPO:-$HOME/.m2/repository}"
  for artifact in liveness-core liveness-onnx; do
    jar="$repo/io/mosip/liveness/$artifact/$version/$artifact-$version.jar"
    [ -f "$jar" ] || missing=1
  done
  if [ "$missing" = "0" ] && [ "$FORCE_ENGINE" = "0" ]; then
    log "liveness engine $version found in $repo"
    return
  fi
  command -v mvn >/dev/null 2>&1 || die "Maven (mvn) is needed once to build the liveness engine (liveness/)"
  log "building the liveness engine $version (liveness-core, liveness-onnx) into $repo"
  mvn -q -f "$REPO_ROOT/liveness/pom.xml" -pl liveness-core,liveness-onnx -am install -DskipTests
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

build_apk() {
  local commit branch start secs
  commit="$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD 2>/dev/null || true)"
  branch="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  if [ -n "$SERVER_URL" ]; then
    log "debug server URL: $SERVER_URL"
  else
    log "debug server URL: upstream serverBaseURL"
  fi
  export ARC_DEBUG_SERVER_BASE_URL="$SERVER_URL"

  start="$(date +%s)"
  (cd "$ARC_DIR" && flutter build apk --debug \
      --dart-define="ARC_GIT_COMMIT=$commit" \
      --dart-define="ARC_GIT_BRANCH=$branch")
  secs=$(( $(date +%s) - start ))
  [ -f "$APK" ] || die "APK not found at $APK"
  log "APK: $APK"
  log "APK size: $(wc -c < "$APK" | tr -d ' ') bytes; build time: ${secs}s"
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
  [ -f "$ARC_DIR/android/${gradlew#./}" ] || die "Gradle wrapper missing; run a build first (flutter creates it)"
  log "native unit tests: :clientmanager:testDebugUnitTest :livenessmanager:testDebugUnitTest"
  export ARC_DEBUG_SERVER_BASE_URL="$SERVER_URL"
  local status=0 module
  (cd "$ARC_DIR/android" && "$gradlew" :clientmanager:testDebugUnitTest \
      :livenessmanager:testDebugUnitTest --continue) || status=$?
  for module in clientmanager livenessmanager; do
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
ensure_liveness_engine
if [ "$DO_CODEGEN" = "1" ] || [ "$DO_BUILD" = "1" ]; then
  disable_desktop_targets
fi
if [ "$DO_CODEGEN" = "1" ]; then run_codegen; fi
if [ "$DO_BUILD" = "1" ]; then build_apk; fi
if [ "$DO_TEST" = "1" ]; then run_tests; fi
if [ "$DO_DEVICE_TEST" = "1" ]; then run_device_tests; fi
log "done"
