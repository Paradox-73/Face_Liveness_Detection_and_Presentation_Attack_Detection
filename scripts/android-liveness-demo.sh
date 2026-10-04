#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Starts the native face liveness capture screen (LivenessCaptureActivity) of the
# Registration Client debug build on an attached emulator or device, feeds it a
# replay frame pack instead of the camera, and saves a series of screenshots and
# the liveness log lines.
#
# Only debug builds export the screen and accept the replay source and the
# config.* overrides (see clients/android/android/livenessmanager/src/debug).
#
# Usage (Git Bash on Windows, or bash on Linux/macOS):
#   scripts/android-liveness-demo.sh                          # registration, English, bundled still pack
#   scripts/android-liveness-demo.sh --workflow packet_auth   # operator authentication (supervisor title)
#   scripts/android-liveness-demo.sh --lang ara               # eng fra ara hin kan tam
#   scripts/android-liveness-demo.sh --config mosip.registration.liveness.max_retries=1
#   scripts/android-liveness-demo.sh --push DIR --pack NAME   # copy a folder of JPEG/PNG frames to the
#                                                             # device and replay it
#   scripts/android-liveness-demo.sh --shots 8 --interval 3   # 8 screenshots, 3 s apart
#   scripts/android-liveness-demo.sh --out DIR                # screenshot folder
#
# Frame packs: an asset folder liveness-replay/<pack>/ inside the APK (debug
# builds bundle 'mockmds-still', the MockMDS stream image), or a folder pushed
# to <app external files>/liveness-replay/<pack>/ with --push. Use synthetic or
# openly licensed frames only; never store images of real people in the repo.
#
# Environment:
#   ANDROID_HOME   Android SDK root (adb is taken from platform-tools)
#   ANDROID_SERIAL adb serial of the target (default: the only attached device)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="io.mosip.registration_client"
ACTIVITY="$PACKAGE/io.mosip.registration.livenessmanager.ui.LivenessCaptureActivity"

WORKFLOW="registration"
LANGUAGE="eng"
PACK="mockmds-still"
FPS=15
PUSH_DIR=""
SHOTS=6
INTERVAL=2
OUT_DIR="$REPO_ROOT/clients/android/build/liveness-demo"
CONFIG=()

log() { printf '[liveness-demo] %s\n' "$*"; }
die() { printf '[liveness-demo] ERROR: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --workflow) WORKFLOW="${2:?}"; shift 2 ;;
    --lang) LANGUAGE="${2:?}"; shift 2 ;;
    --pack) PACK="${2:?}"; shift 2 ;;
    --fps) FPS="${2:?}"; shift 2 ;;
    --push) PUSH_DIR="${2:?}"; shift 2 ;;
    --config) CONFIG+=("${2:?}"); shift 2 ;;
    --shots) SHOTS="${2:?}"; shift 2 ;;
    --interval) INTERVAL="${2:?}"; shift 2 ;;
    --out) OUT_DIR="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,27p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
[ -x "$ADB" ] || [ -x "$ADB.exe" ] || ADB="$(command -v adb || true)"
[ -n "$ADB" ] || die "adb not found (set ANDROID_HOME)"
"$ADB" get-state >/dev/null 2>&1 || die "no device attached (start one with scripts/android-run-emulator.sh)"
"$ADB" shell pm path "$PACKAGE" >/dev/null 2>&1 || die "$PACKAGE is not installed (scripts/android-run-emulator.sh installs it)"

if [ -n "$PUSH_DIR" ]; then
  [ -d "$PUSH_DIR" ] || die "no such folder: $PUSH_DIR"
  target="/sdcard/Android/data/$PACKAGE/files/liveness-replay/$PACK"
  log "pushing $(ls "$PUSH_DIR" | wc -l | tr -d ' ') frames to $target"
  "$ADB" shell rm -rf "$target"
  "$ADB" shell mkdir -p "$target"
  # MSYS_NO_PATHCONV keeps Git Bash from rewriting the device path.
  MSYS_NO_PATHCONV=1 "$ADB" push "$PUSH_DIR/." "$target/" >/dev/null
fi

args=(shell am start -W -n "$ACTIVITY"
  --es liveness.workflow "$WORKFLOW" --es liveness.subject demo --es liveness.language "$LANGUAGE"
  --es liveness.source replay --es liveness.replay "$PACK" --ei liveness.replay_fps "$FPS")
for kv in "${CONFIG[@]+"${CONFIG[@]}"}"; do
  case "$kv" in *=*) ;; *) die "--config needs key=value: $kv" ;; esac
  args+=(--es "config.${kv%%=*}" "${kv#*=}")
done

mkdir -p "$OUT_DIR"
stamp="$(date +%Y%m%d-%H%M%S)"
"$ADB" logcat -c || true
log "starting $WORKFLOW ($LANGUAGE) on replay pack $PACK at $FPS fps"
MSYS_NO_PATHCONV=1 "$ADB" "${args[@]}"
for i in $(seq 1 "$SHOTS"); do
  sleep "$INTERVAL"
  file="$OUT_DIR/$stamp-$WORKFLOW-$LANGUAGE-$i.png"
  "$ADB" exec-out screencap -p > "$file"
  log "screenshot $file"
done
log "liveness log lines:"
"$ADB" logcat -d -s Liveness:V | tail -n 40
log "done"
