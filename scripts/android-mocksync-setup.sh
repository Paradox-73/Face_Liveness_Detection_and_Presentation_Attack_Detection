#!/usr/bin/env bash
# Connects the Android Registration Client (debug APK) on the emulator to MockSync
# and, optionally, runs its first online login and initial sync.
#
# What it does:
#   1. Checks that MockSync answers on $MOCKSYNC_URL (starts it with --start-mocksync).
#   2. Boots the emulator and runs 'adb reverse tcp:8080 tcp:8080'
#      (scripts/android-run-emulator.sh --no-install), installs the debug APK when
#      the app is missing, and hides the on-screen keyboard (the AVD has a hardware
#      keyboard; the on-screen one drops characters typed with 'adb shell input').
#   3. Reads the tablet's machine name and public keys from the app itself: long
#      press on the MOSIP footer of the login screen, COPY TEXT in the "Device
#      Credentials" dialog, paste into the username field, read it back with
#      uiautomator. Saves them to <out>/machine-keys.json.
#   4. Registers the machine in MockSync (POST /admin/machines, clientType ANDROID).
#   5. With --login: logs in, waits for "Sync Completed Successfully" (7 of 7 sync
#      steps), restarts the app, logs in again and waits for the home screen.
#   6. With --offline-check: removes the adb reverse rule (MockSync unreachable),
#      logs in offline, then restores the rule.
#   7. With --device-cert: provisions the built-in face camera with a DEVICE
#      certificate from MockSync's DEVICE CA, inside the app (Keystore key and
#      certificate request; scripts/android-liveness-device-cert.sh --csr), so
#      resident face captures pass ARC's device trust check.
# Screenshots of every stage go to <out>/.
#
# Usage (Git Bash on Windows, or bash on Linux/macOS), from the repository root:
#   scripts/android-mocksync-setup.sh                       # steps 1-4, then prints the manual login steps
#   scripts/android-mocksync-setup.sh --login               # steps 1-5 as officer1
#   scripts/android-mocksync-setup.sh --login --offline-check
#   scripts/android-mocksync-setup.sh --login --device-cert
#   scripts/android-mocksync-setup.sh --reset --login       # wipe the app first: new machine name and keys
#   scripts/android-mocksync-setup.sh --keys-file F         # register keys copied by hand
#
# Options:
#   --login                 run the first login and sync (step 5)
#   --user U --password P   account for --login (default officer1 / Mosip@Officer1; no spaces or quotes)
#   --offline-check         step 6 (needs a user who logged in online before)
#   --device-cert           step 7 (works on release and debug builds)
#   --reset                 'pm clear' the app and remove its old MockSync registration first
#   --keys-file F           register the Machine Keys JSON in F instead of reading it from the app
#   --start-mocksync        start MockSync in the background when it does not answer
#                           (log: tools/mock-sync/work/mock-sync-android.log)
#   --headless              boot the emulator without a window
#   --out DIR               screenshots and keys (default clients/android/build/emulator/mocksync)
#
# Environment:
#   ANDROID_HOME            Android SDK root (required)
#   MOCKSYNC_URL            MockSync HTTP base URL on this PC (default http://127.0.0.1:8080)
#   ARC_EMULATOR_PORT       emulator console port; serial emulator-<port> (default 5554)
#   ARC_APK                 APK installed when the app is missing
#                           (default clients/android/build/app/outputs/flutter-apk/app-debug.apk)
#   ARC_UI_TIMEOUT          seconds to wait for a screen (default 60); the sync gets 4x this
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="io.mosip.registration_client"
ACTIVITY="$PACKAGE/.MainActivity"
MOCKSYNC_URL="${MOCKSYNC_URL:-http://127.0.0.1:8080}"
PORT="${ARC_EMULATOR_PORT:-5554}"
APK="${ARC_APK:-$REPO_ROOT/clients/android/build/app/outputs/flutter-apk/app-debug.apk}"
UI_TIMEOUT="${ARC_UI_TIMEOUT:-60}"
export ANDROID_SERIAL="emulator-$PORT"
# Device paths such as /sdcard/... must reach adb unchanged (Git Bash would rewrite them).
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

OUT_DIR="$REPO_ROOT/clients/android/build/emulator/mocksync"
USER_ID="officer1"
PASSWORD="Mosip@Officer1"
DO_LOGIN=0
DO_OFFLINE=0
DO_RESET=0
DO_DEVICE_CERT=0
KEYS_FILE=""
START_MOCKSYNC=0
EMULATOR_ARGS=(--no-install)
UI_XML=""

log() { printf '[android-mocksync] %s\n' "$*"; }
die() { printf '[android-mocksync] ERROR: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --login) DO_LOGIN=1; shift ;;
    --user) [ $# -ge 2 ] || die "--user needs a value"; USER_ID="$2"; shift 2 ;;
    --password) [ $# -ge 2 ] || die "--password needs a value"; PASSWORD="$2"; shift 2 ;;
    --offline-check) DO_OFFLINE=1; shift ;;
    --reset) DO_RESET=1; shift ;;
    --device-cert) DO_DEVICE_CERT=1; shift ;;
    --keys-file) [ $# -ge 2 ] || die "--keys-file needs a path"; KEYS_FILE="$2"; shift 2 ;;
    --start-mocksync) START_MOCKSYNC=1; shift ;;
    --headless) EMULATOR_ARGS+=(--headless); shift ;;
    --out) [ $# -ge 2 ] || die "--out needs a path"; OUT_DIR="$2"; shift 2 ;;
    -h|--help) sed -n '2,52p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || die "ANDROID_HOME is not set"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;; *) EXE="" ;; esac
ADB="$SDK/platform-tools/adb$EXE"
[ -x "$ADB" ] || die "adb not found at $ADB"
command -v curl >/dev/null 2>&1 || die "curl is required"
mkdir -p "$OUT_DIR"

adb_() { "$ADB" "$@"; }
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

# ---------------------------------------------------------------- MockSync

mocksync_up() { curl -sf -m 3 "$MOCKSYNC_URL/v1/syncdata/actuator/health" >/dev/null 2>&1; }

ensure_mocksync() {
  if mocksync_up; then
    log "MockSync answers at $MOCKSYNC_URL"
    return
  fi
  [ "$START_MOCKSYNC" = "1" ] || die "MockSync does not answer at $MOCKSYNC_URL; start it (tools/mock-sync/scripts/run-mock-sync.sh) or pass --start-mocksync"
  local dir="$REPO_ROOT/tools/mock-sync" waited=0
  mkdir -p "$dir/work"
  log "starting MockSync (log: $dir/work/mock-sync-android.log)"
  (cd "$dir" && nohup bash scripts/run-mock-sync.sh > work/mock-sync-android.log 2>&1 &)
  until mocksync_up; do
    sleep 3; waited=$((waited + 3))
    [ "$waited" -lt 180 ] || die "MockSync did not start; see $dir/work/mock-sync-android.log"
  done
  log "MockSync started after about ${waited}s"
}

# ---------------------------------------------------------------- emulator and app

ensure_emulator_and_app() {
  bash "$REPO_ROOT/scripts/android-run-emulator.sh" "${EMULATOR_ARGS[@]}"
  if ! app_installed; then
    [ -f "$APK" ] || die "the app is not installed and $APK does not exist (run scripts/android-build.sh)"
    log "installing $APK"
    adb_ install -r -g "$(host_path "$APK")"
  fi
  adb_ shell settings put secure show_ime_with_hard_keyboard 0
}

app_installed() { adb_ shell pm list packages "$PACKAGE" | grep -qw "package:$PACKAGE"; }

grant_runtime_permissions() {
  local perm
  for perm in $(adb_ shell dumpsys package "$PACKAGE" | tr -d '\r' \
      | sed -n '/requested permissions:/,/install permissions:/p' | grep -o 'android\.permission\.[A-Z_]*'); do
    adb_ shell pm grant "$PACKAGE" "$perm" >/dev/null 2>&1 || true
  done
}

restart_app() {
  adb_ shell am force-stop "$PACKAGE"
  adb_ shell am start -W -n "$ACTIVITY" >/dev/null
  wait_for_desc 'NEXT' "$UI_TIMEOUT" || die "the login screen did not appear; see $(screenshot login-timeout)"
}

screenshot() {
  local file="$OUT_DIR/$1.png"
  adb_ exec-out screencap -p > "$file"
  printf '%s' "$file"
}

# ---------------------------------------------------------------- UI helpers (uiautomator)

# Refreshes UI_XML with one <node ...> per line.
ui_dump() {
  adb_ shell uiautomator dump /sdcard/arc-ui.xml >/dev/null 2>&1 || true
  UI_XML="$(adb_ shell cat /sdcard/arc-ui.xml 2>/dev/null | tr -d '\r' | awk '{ gsub(/<node /, "\n<node "); print }')"
}

# Prints "x y" for the centre of the first node matching the grep pattern.
node_center() {
  local bounds
  bounds="$(printf '%s\n' "$UI_XML" | grep -m1 -- "$1" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | tr -c '0-9\n' ' ')" || true
  [ -n "$bounds" ] || return 1
  set -- $bounds
  printf '%s %s' $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

tap_node() {
  local xy
  xy="$(node_center "$1")" || return 1
  adb_ shell input tap $xy
}

has_desc() { printf '%s\n' "$UI_XML" | grep -Eq "content-desc=\"($1)"; }

# Waits until a node whose content-desc starts with one of the alternatives in $1 is on screen.
wait_for_desc() {
  local pattern="$1" timeout="$2" waited=0
  while :; do
    ui_dump
    has_desc "$pattern" && return 0
    [ "$waited" -lt "$timeout" ] || return 1
    sleep 2; waited=$((waited + 2))
  done
}

# Text of the first EditText (uiautomator writes text='...' when the value contains double quotes).
edit_text_value() {
  local node
  node="$(printf '%s\n' "$UI_XML" | grep -m1 'class="android.widget.EditText"')" || true
  case "$node" in
    *" text='"*) printf '%s' "$node" | sed -n "s/.* text='\([^']*\)'.*/\1/p" ;;
    *) printf '%s' "$node" | sed -n 's/.* text="\([^"]*\)".*/\1/p' | sed 's/&quot;/"/g' ;;
  esac | sed 's/&amp;/\&/g'
}

focus_and_clear_edit_text() {
  ui_dump
  tap_node 'class="android.widget.EditText"' || die "no text field on screen; see $(screenshot no-field)"
  sleep 1
  adb_ shell input keycombination 113 29   # Ctrl+A
  adb_ shell input keyevent 67             # Delete
}

type_into_field() {
  focus_and_clear_edit_text
  adb_ shell "input text '$1'"
  sleep 1
}

# ---------------------------------------------------------------- machine keys

read_machine_keys_from_app() {
  local json
  local xy
  restart_app
  # The MOSIP footer is the only long-clickable node; a long press is a zero-length swipe held for 1.5 s.
  xy="$(node_center 'long-clickable="true"')" || die "MOSIP footer not found on the login screen"
  adb_ shell input swipe $xy $xy 1500
  wait_for_desc 'COPY TEXT' 15 || die "the Device Credentials dialog did not open; see $(screenshot keys-dialog)"
  screenshot 01-device-credentials >/dev/null
  tap_node 'content-desc="COPY TEXT"'
  sleep 1
  restart_app                      # closes the dialog
  focus_and_clear_edit_text
  adb_ shell input keycombination 113 50   # Ctrl+V
  sleep 1
  ui_dump
  json="$(edit_text_value)"
  focus_and_clear_edit_text
  case "$json" in
    *'"publicKey"'*'"name"'*|*'"name"'*'"publicKey"'*) ;;
    *) die "could not read the machine keys from the app (got: ${json:0:80}); use --keys-file" ;;
  esac
  printf '%s\n' "$json" > "$OUT_DIR/machine-keys.json"
  log "machine keys read from the app: $OUT_DIR/machine-keys.json"
}

json_field() { sed -n "s/.*\"$1\" *: *\"\([^\"]*\)\".*/\1/p" "$2" | head -n 1; }

machine_name() { json_field name "$OUT_DIR/machine-keys.json"; }

register_machine() {
  local file="$1" name public sign body response
  name="$(json_field name "$file")"
  public="$(json_field publicKey "$file")"
  sign="$(json_field signPublicKey "$file")"
  [ -n "$name" ] && [ -n "$public" ] && [ -n "$sign" ] || die "$file must hold name, publicKey and signPublicKey"
  body="$(printf '{"name":"%s","publicKey":"%s","signPublicKey":"%s","clientType":"ANDROID"}' "$name" "$public" "$sign")"
  response="$(curl -sf -X POST -H 'Content-Type: application/json' --data-binary "$body" "$MOCKSYNC_URL/admin/machines")" \
    || die "MockSync refused the machine registration"
  printf '%s\n' "$response" > "$OUT_DIR/machine-registration.json"
  log "registered machine $name in MockSync: machineId $(printf '%s' "$response" | sed -n 's/.*"machineId":"\([^"]*\)".*/\1/p'), keyIndex $(printf '%s' "$response" | sed -n 's/.*"keyIndex":"\([^"]*\)".*/\1/p')"
}

reset_app() {
  local old=""
  if app_installed; then
    [ -f "$OUT_DIR/machine-keys.json" ] && old="$(machine_name)"
    log "clearing the app's data (new machine name and keys on next launch)"
    adb_ shell pm clear "$PACKAGE" >/dev/null
    grant_runtime_permissions
  fi
  if [ -n "$old" ]; then
    curl -s -o /dev/null -X DELETE "$MOCKSYNC_URL/admin/machines/$old" && log "removed machine $old from MockSync" || true
  fi
  rm -f "$OUT_DIR/machine-keys.json"
}

# ---------------------------------------------------------------- login

LOGIN_ERRORS='Login Failed|Login failed|This device has not been onboarded|Password incorrect|Credentials not found|User not found|Sync\. Failed|Machine is not active|Center is not active'
HOME_SCREEN='HELLO, |GET ONBOARDED|Registration Tasks|NEW REGISTRATION|HOME'

# Enters user and password; returns once LOGIN is tapped.
submit_login() {
  type_into_field "$USER_ID"
  tap_node 'content-desc="NEXT"' || die "NEXT button not found"
  wait_for_desc 'Password|User not found' 20 || die "password screen did not appear; see $(screenshot password-timeout)"
  has_desc 'User not found' && die "user $USER_ID is not in the synced user list"
  type_into_field "$PASSWORD"
  tap_node 'content-desc="LOGIN"' || die "LOGIN button not found"
}

# Prints "home", "synced" or "error:<text>".
wait_after_login() {
  local timeout="$1" waited=0 text
  while [ "$waited" -lt "$timeout" ]; do
    sleep 3; waited=$((waited + 3))
    ui_dump
    if has_desc "$HOME_SCREEN"; then printf 'home'; return; fi
    if has_desc 'Sync Completed Successfully'; then printf 'synced'; return; fi
    if has_desc "$LOGIN_ERRORS"; then
      text="$(printf '%s\n' "$UI_XML" | grep -Eo "content-desc=\"($LOGIN_ERRORS)[^\"]*" | head -n 1 | cut -d'"' -f2)"
      printf 'error:%s' "$text"; return
    fi
  done
  printf 'error:timeout after %ss' "$timeout"
}

login_until_home() {
  local result
  restart_app
  submit_login
  result="$(wait_after_login $((UI_TIMEOUT * 4)))"
  if [ "$result" = "synced" ]; then
    log "initial sync finished: $(screenshot 02-sync-completed)"
    # The app should soft-restart itself after 5 s (RestartWidget); the script does not rely on that
    # and restarts the app itself (docs/install/android.md, section 5).
    sleep 8
    restart_app
    submit_login
    result="$(wait_after_login $((UI_TIMEOUT * 2)))"
  fi
  case "$result" in
    home) log "home screen reached: $(screenshot 03-home-online)" ;;
    *) die "login did not reach the home screen (${result#error:}); see $(screenshot login-failed)" ;;
  esac
}

offline_check() {
  log "offline check: removing adb reverse tcp:8080 so MockSync is unreachable"
  adb_ reverse --remove tcp:8080 || true
  restart_app
  submit_login
  local result; result="$(wait_after_login $((UI_TIMEOUT * 2)))"
  adb_ reverse tcp:8080 tcp:8080 >/dev/null
  log "adb reverse tcp:8080 restored"
  [ "$result" = "home" ] || die "offline login failed (${result#error:}); see $(screenshot offline-failed)"
  log "offline login reached the home screen: $(screenshot 04-home-offline)"
}

print_manual_steps() {
  cat <<EOF
[android-mocksync] Machine registered. First login on the emulator:
  1. Log in as $USER_ID / $PASSWORD (the app shows "Sync 1 of 7" ... then "Sync Completed Successfully").
  2. If that dialog stays open for more than 10 s, restart the app:
       adb shell am force-stop $PACKAGE && adb shell am start -n $ACTIVITY
  3. Log in again: the home screen ("HELLO, Test Officer One") appears.
  Or run: scripts/android-mocksync-setup.sh --login
Device certificate of the built-in face camera (needed to store a resident face):
  scripts/android-liveness-device-cert.sh --csr
EOF
}

# ---------------------------------------------------------------- main

ensure_mocksync
ensure_emulator_and_app
[ "$DO_RESET" = "1" ] && reset_app
if [ -n "$KEYS_FILE" ]; then
  [ -f "$KEYS_FILE" ] || die "keys file not found: $KEYS_FILE"
  cp "$KEYS_FILE" "$OUT_DIR/machine-keys.json"
else
  read_machine_keys_from_app
fi
register_machine "$OUT_DIR/machine-keys.json"
if [ "$DO_LOGIN" = "1" ]; then
  login_until_home
else
  print_manual_steps
fi
[ "$DO_OFFLINE" = "1" ] && offline_check
if [ "$DO_DEVICE_CERT" = "1" ]; then
  log "provisioning the built-in face camera with a DEVICE certificate (in-app key)"
  bash "$REPO_ROOT/scripts/android-liveness-device-cert.sh" --csr
fi
log "done (screenshots in $OUT_DIR)"
