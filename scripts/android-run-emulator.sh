#!/usr/bin/env bash
# Starts an Android 14 (API 34) x86_64 emulator whose front camera is the PC
# webcam, installs the Registration Client debug APK, launches it and saves a
# screenshot.
#
# What it does:
#   1. Installs the SDK packages 'emulator' and
#      'system-images;android-34;google_apis;x86_64' when they are missing.
#   2. Creates the AVD (emulator profile) $ARC_AVD_NAME when it is missing:
#      a 10.1-inch 1280x800 tablet, front camera = webcam0 (the first PC webcam).
#   3. Boots it (cold boot, -no-snapshot) unless it is already running, and
#      waits for sys.boot_completed.
#   4. adb reverse tcp:8080 tcp:8080, so http://127.0.0.1:8080 inside the
#      emulator reaches a MockSync listening on port 8080 of the PC.
#   5. adb install -r -g the debug APK, starts MainActivity, waits, and saves a
#      PNG screenshot.
#
# Usage (Git Bash on Windows, or bash on Linux/macOS):
#   scripts/android-run-emulator.sh                 # emulator window + install + launch
#   scripts/android-run-emulator.sh --headless      # no emulator window (-no-window)
#   scripts/android-run-emulator.sh --apk PATH      # another APK
#   scripts/android-run-emulator.sh --no-install    # boot + adb reverse only
#   scripts/android-run-emulator.sh --screenshot F  # screenshot path
#   scripts/android-run-emulator.sh --stop          # shut the emulator down (adb emu kill)
#
# Environment:
#   ANDROID_HOME         Android SDK root (required)
#   ARC_AVD_NAME         AVD name (default arc_api34)
#   ARC_AVD_DEVICE       avdmanager device profile (default "10.1in WXGA (Tablet)")
#   ARC_AVD_RAM_MB       emulator RAM in MB (default 2048)
#   ARC_EMULATOR_PORT    console port; the adb serial is emulator-<port> (default 5554)
#   ARC_EMULATOR_GPU     -gpu mode (default swiftshader_indirect, works without a GPU driver)
#   ARC_WEBCAM           host camera for the front camera (default webcam0;
#                        list them with: $ANDROID_HOME/emulator/emulator -webcam-list)
#   ARC_REVERSE_PORT     port forwarded with adb reverse (default 8080; empty = none)
#   ARC_BOOT_TIMEOUT     seconds to wait for boot (default 600)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARC_DIR="$REPO_ROOT/clients/android"
OUT_DIR="$ARC_DIR/build/emulator"

PACKAGE="io.mosip.registration_client"
ACTIVITY="$PACKAGE/.MainActivity"
SYSTEM_IMAGE="system-images;android-34;google_apis;x86_64"
AVD_NAME="${ARC_AVD_NAME:-arc_api34}"
AVD_DEVICE="${ARC_AVD_DEVICE:-10.1in WXGA (Tablet)}"
RAM_MB="${ARC_AVD_RAM_MB:-2048}"
PORT="${ARC_EMULATOR_PORT:-5554}"
GPU="${ARC_EMULATOR_GPU:-swiftshader_indirect}"
WEBCAM="${ARC_WEBCAM:-webcam0}"
REVERSE_PORT="${ARC_REVERSE_PORT-8080}"
BOOT_TIMEOUT="${ARC_BOOT_TIMEOUT:-600}"
export ANDROID_SERIAL="emulator-$PORT"

APK="$ARC_DIR/build/app/outputs/flutter-apk/app-debug.apk"
HEADLESS=0
INSTALL=1
STOP=0
SCREENSHOT=""

log() { printf '[android-emulator] %s\n' "$*"; }
die() { printf '[android-emulator] ERROR: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --headless) HEADLESS=1; shift ;;
    --apk) [ $# -ge 2 ] || die "--apk needs a path"; APK="$2"; shift 2 ;;
    --no-install) INSTALL=0; shift ;;
    --screenshot) [ $# -ge 2 ] || die "--screenshot needs a path"; SCREENSHOT="$2"; shift 2 ;;
    --stop) STOP=1; shift ;;
    -h|--help) sed -n '2,39p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || die "ANDROID_HOME is not set"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe"; BAT=".bat" ;;
  *) EXE=""; BAT="" ;;
esac
ADB="$SDK/platform-tools/adb$EXE"
EMULATOR="$SDK/emulator/emulator$EXE"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager$BAT"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager$BAT"
[ -x "$ADB" ] || die "adb not found at $ADB (install 'platform-tools')"

# sdkmanager and avdmanager (cmdline-tools) need Java 17 or newer.
require_java17() {
  local java="${JAVA_HOME:+$JAVA_HOME/bin/}java" version major
  version="$("$java" -version 2>&1 | head -n 1 | awk -F'"' '{print $2}' | cut -d. -f1)"
  major="${version:-0}"
  case "$major" in ''|*[!0-9]*) major=0 ;; esac
  [ "$major" -ge 17 ] || die "sdkmanager/avdmanager need Java 17+; JAVA_HOME points to Java $version (use JDK 21)"
}

is_running() {
  "$ADB" devices | grep -q "^$ANDROID_SERIAL[[:space:]]*device"
}

ensure_sdk_packages() {
  local image_dir="$SDK/system-images/android-34/google_apis/x86_64"
  if [ -x "$EMULATOR" ] && [ -f "$image_dir/system.img" ]; then
    return
  fi
  [ -f "$SDKMANAGER" ] || die "sdkmanager not found at $SDKMANAGER"
  require_java17
  log "installing SDK packages: emulator, $SYSTEM_IMAGE"
  "$SDKMANAGER" --install "emulator" "$SYSTEM_IMAGE"
}

# Sets key=value in an AVD config.ini, replacing an existing line.
set_ini() {
  local file="$1" key="$2" value="$3"
  if grep -q "^$key[[:space:]]*=" "$file"; then
    sed -i "s|^$key[[:space:]]*=.*|$key=$value|" "$file"
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
  fi
}

ensure_avd() {
  local avd_home="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
  local config="$avd_home/$AVD_NAME.avd/config.ini"
  if [ ! -f "$config" ]; then
    [ -f "$AVDMANAGER" ] || die "avdmanager not found at $AVDMANAGER"
    require_java17
    log "creating AVD $AVD_NAME ($AVD_DEVICE, $SYSTEM_IMAGE)"
    echo no | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d "$AVD_DEVICE"
  fi
  [ -f "$config" ] || die "AVD config not found at $config"
  set_ini "$config" hw.camera.front "$WEBCAM"
  set_ini "$config" hw.camera.back emulated
  set_ini "$config" hw.ramSize "$RAM_MB"
  set_ini "$config" hw.keyboard yes
  set_ini "$config" disk.dataPartition.size 6G
}

start_emulator() {
  mkdir -p "$OUT_DIR"
  local args=(-avd "$AVD_NAME" -port "$PORT" -no-snapshot -no-boot-anim -no-audio -gpu "$GPU" -camera-front "$WEBCAM")
  [ "$HEADLESS" = "1" ] && args+=(-no-window)
  log "starting emulator: ${args[*]}"
  nohup "$EMULATOR" "${args[@]}" > "$OUT_DIR/emulator.log" 2>&1 &
  EMULATOR_PID=$!
  log "emulator log: $OUT_DIR/emulator.log"
}

wait_for_boot() {
  local waited=0
  log "waiting for $ANDROID_SERIAL to boot (up to ${BOOT_TIMEOUT}s)"
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    if [ -n "${EMULATOR_PID:-}" ] && ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
      die "the emulator exited during boot; see $OUT_DIR/emulator.log"
    fi
    sleep 5
    waited=$((waited + 5))
    [ "$waited" -lt "$BOOT_TIMEOUT" ] || die "boot timed out; see $OUT_DIR/emulator.log"
  done
  log "booted after about ${waited}s"
}

install_and_launch() {
  [ -f "$APK" ] || die "APK not found: $APK (run scripts/android-build.sh first)"
  log "installing $APK"
  "$ADB" install -r -g "$APK"
  log "launching $ACTIVITY"
  "$ADB" shell am start -W -n "$ACTIVITY"
  sleep 20
  [ -n "$SCREENSHOT" ] || SCREENSHOT="$OUT_DIR/screen-$(date +%Y%m%d-%H%M%S).png"
  mkdir -p "$(dirname "$SCREENSHOT")"
  "$ADB" exec-out screencap -p > "$SCREENSHOT"
  log "screenshot: $SCREENSHOT"
}

if [ "$STOP" = "1" ]; then
  if is_running; then
    "$ADB" emu kill
    log "emulator $ANDROID_SERIAL stopped"
  else
    log "emulator $ANDROID_SERIAL is not running"
  fi
  exit 0
fi

ensure_sdk_packages
ensure_avd
if is_running; then
  log "$ANDROID_SERIAL is already running"
else
  start_emulator
fi
wait_for_boot
if [ -n "$REVERSE_PORT" ]; then
  "$ADB" reverse "tcp:$REVERSE_PORT" "tcp:$REVERSE_PORT"
  log "adb reverse tcp:$REVERSE_PORT -> PC port $REVERSE_PORT"
fi
if [ "$INSTALL" = "1" ]; then install_and_launch; fi
log "done (stop the emulator with: scripts/android-run-emulator.sh --stop)"
