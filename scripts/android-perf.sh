#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Measures the liveness engine on a connected physical Android phone: per-frame latency (p50, p95), first-frame
# time, model load time and peak memory for each ONNX Runtime execution provider (CPU, XNNPACK, NNAPI) and each
# intra-op thread count. Writes a CSV and a markdown summary to run/perf/<date>/.
#
# What runs on the phone: the instrumented test LivenessPerformanceTest of the livenessmanager module
# (clients/android/android/livenessmanager/src/androidTest). It loads the signed model bundle from the test APK,
# decodes one JPEG frame (640x480 MockMDS stream image) and times decode + face analysis + passive PAD per frame,
# the same steps the capture screen runs on every frame. One 'am instrument' call is one setting.
#
# The app APK (debug or release) is installed with --apk so the measured build is the one under test; the
# benchmark itself runs from the instrumented test APK, because a release APK cannot be instrumented.
#
# Usage (Git Bash on Windows, or bash on Linux/macOS):
#   scripts/android-perf.sh --build                          # build the test APK, run the full matrix
#   scripts/android-perf.sh --providers "CPU XNNPACK" --threads "1 2 4" --frames 200
#   scripts/android-perf.sh --apk path/to/app-release.apk    # also install this app APK first
#   scripts/android-perf.sh --release                        # install the default release APK instead of debug
#   scripts/android-perf.sh --serial R58M123ABC              # one phone out of several
#   scripts/android-perf.sh --dry-run                        # print what would run; no phone, no adb needed
#
# Options:
#   --build             run ./gradlew :livenessmanager:assembleDebugAndroidTest first (JDK 21, engine in ~/.m2)
#   --apk PATH          app APK to install (default: clients/android/build/app/outputs/flutter-apk/app-debug.apk,
#                       or app-release.apk with --release; skipped when the file does not exist)
#   --release           use the release APK as the default --apk
#   --no-install-app    do not install an app APK, only the test APK
#   --test-apk PATH     instrumented test APK (default: the livenessmanager androidTest debug output)
#   --providers LIST    space separated: CPU XNNPACK NNAPI (default: all three)
#   --threads LIST      intra-op thread counts (default: "1 2 4")
#   --frames N          measured frames per setting (default 100)
#   --warmup N          frames before measuring (default 20)
#   --out DIR           output folder (default run/perf/<YYYYMMDD>)
#   --serial ID         adb serial (default: the only attached device; also ANDROID_SERIAL)
#   --dry-run           show the plan and commands, do not touch a device
#   -h, --help          this text
#
# Environment: ANDROID_HOME (adb is taken from platform-tools when adb is not on PATH).
# Use the phone on battery, screen on, not charging-throttled, no other heavy apps; run twice and compare.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/clients/android/android"
TEST_PACKAGE="io.mosip.registration.livenessmanager.test"
RUNNER="$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="io.mosip.registration.livenessmanager.LivenessPerformanceTest#perFrameLatency"

BUILD=0
RELEASE=0
INSTALL_APP=1
APP_APK=""
TEST_APK="$ANDROID_DIR/livenessmanager/build/outputs/apk/androidTest/debug/livenessmanager-debug-androidTest.apk"
PROVIDERS="CPU XNNPACK NNAPI"
THREADS="1 2 4"
FRAMES=100
WARMUP=20
OUT_DIR=""
SERIAL="${ANDROID_SERIAL:-}"
DRY_RUN=0

log() { printf '[android-perf] %s\n' "$*"; }
die() { printf '[android-perf] ERROR: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --build) BUILD=1; shift ;;
    --apk) APP_APK="${2:?}"; shift 2 ;;
    --release) RELEASE=1; shift ;;
    --no-install-app) INSTALL_APP=0; shift ;;
    --test-apk) TEST_APK="${2:?}"; shift 2 ;;
    --providers) PROVIDERS="${2:?}"; shift 2 ;;
    --threads) THREADS="${2:?}"; shift 2 ;;
    --frames) FRAMES="${2:?}"; shift 2 ;;
    --warmup) WARMUP="${2:?}"; shift 2 ;;
    --out) OUT_DIR="${2:?}"; shift 2 ;;
    --serial) SERIAL="${2:?}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,37p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

case "$FRAMES$WARMUP" in *[!0-9]*) die "--frames and --warmup must be whole numbers" ;; esac
[ "$FRAMES" -gt 0 ] || die "--frames must be at least 1 (0 skips the benchmark)"
for p in $PROVIDERS; do
  case "$p" in CPU|XNNPACK|NNAPI) ;; *) die "unknown provider '$p' (CPU, XNNPACK, NNAPI)" ;; esac
done
for t in $THREADS; do
  case "$t" in ''|*[!0-9]*|0) die "thread counts must be positive whole numbers: '$t'" ;; esac
done

DATE_TAG="$(date +%Y%m%d)"
[ -n "$OUT_DIR" ] || OUT_DIR="$(cd "$REPO_ROOT/.." && pwd)/run/perf/$DATE_TAG"
if [ -z "$APP_APK" ]; then
  if [ "$RELEASE" = "1" ]; then APP_TYPE=release; else APP_TYPE=debug; fi
  APP_APK="$REPO_ROOT/clients/android/build/app/outputs/flutter-apk/app-$APP_TYPE.apk"
fi

# ---- adb ---------------------------------------------------------------------------------------------------------
ADB=""
find_adb() {
  if command -v adb >/dev/null 2>&1; then ADB="adb"; return; fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then ADB="$sdk/platform-tools/adb"; return; fi
  if [ -n "$sdk" ] && [ -f "$sdk/platform-tools/adb.exe" ]; then ADB="$sdk/platform-tools/adb.exe"; return; fi
  die "adb not found (put platform-tools on PATH or set ANDROID_HOME)"
}
adb_() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }
prop() { adb_ shell getprop "$1" | tr -d '\r'; }

instrument_cmd() { # provider threads
  printf 'adb shell am instrument -w -r -e class %s -e provider %s -e threads %s -e frames %s -e warmup %s %s' \
    "$TEST_CLASS" "$1" "$2" "$FRAMES" "$WARMUP" "$RUNNER"
}

# ---- dry run -----------------------------------------------------------------------------------------------------
if [ "$DRY_RUN" = "1" ]; then
  log "dry run: no device is contacted, nothing is built or installed"
  log "output folder: $OUT_DIR"
  [ "$BUILD" = "1" ] && log "would run: (cd $ANDROID_DIR && ./gradlew :livenessmanager:assembleDebugAndroidTest)"
  if [ "$INSTALL_APP" = "1" ]; then
    if [ -f "$APP_APK" ]; then log "would install app APK: adb install -r -t $APP_APK"
    else log "app APK not found, would be skipped: $APP_APK"; fi
  fi
  if [ -f "$TEST_APK" ]; then log "would install test APK: adb install -r -t $TEST_APK"
  else log "test APK not built yet (use --build): $TEST_APK"; fi
  n=0
  for p in $PROVIDERS; do
    for t in $THREADS; do
      n=$((n + 1))
      log "setting $n: $(instrument_cmd "$p" "$t")"
    done
  done
  log "$n settings; each also samples 'adb shell dumpsys meminfo $TEST_PACKAGE' once a second"
  log "would write: $OUT_DIR/perf.csv, $OUT_DIR/summary.md, $OUT_DIR/raw/*.txt, $OUT_DIR/device.txt"
  exit 0
fi

# ---- build and install -------------------------------------------------------------------------------------------
find_adb
if [ "$BUILD" = "1" ]; then
  log "building the instrumented test APK"
  (cd "$ANDROID_DIR" && ./gradlew :livenessmanager:assembleDebugAndroidTest)
fi
[ -f "$TEST_APK" ] || die "test APK not found: $TEST_APK (run with --build, JDK 21 and the engine in ~/.m2)"

devices="$("$ADB" devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')"
if [ -z "$SERIAL" ]; then
  count="$(printf '%s\n' "$devices" | grep -c . || true)"
  [ "$count" -ge 1 ] || die "no device attached (adb devices shows none in state 'device')"
  [ "$count" -eq 1 ] || die "several devices attached; pick one with --serial ($(printf '%s' "$devices" | tr '\n' ' '))"
  SERIAL="$devices"
fi
case "$(prop ro.kernel.qemu)$(prop ro.hardware)" in
  *1*|*ranchu*|*goldfish*) log "WARNING: this looks like an emulator; numbers are not phone numbers" ;;
esac

mkdir -p "$OUT_DIR/raw"
MODEL="$(prop ro.product.model)"; MAKER="$(prop ro.product.manufacturer)"
ABI="$(prop ro.product.cpu.abi)"; ANDROID_REL="$(prop ro.build.version.release)"; SDK="$(prop ro.build.version.sdk)"
SOC="$(prop ro.soc.model)"; [ -n "$SOC" ] || SOC="$(prop ro.board.platform)"
CORES="$(adb_ shell nproc 2>/dev/null | tr -d '\r' || true)"
{
  echo "date: $(date '+%Y-%m-%d %H:%M:%S')"
  echo "manufacturer: $MAKER"
  echo "model: $MODEL"
  echo "abi: $ABI (all: $(prop ro.product.cpu.abilist))"
  echo "android: $ANDROID_REL (sdk $SDK)"
  echo "soc: $SOC"
  echo "cpu cores: $CORES"
  echo "serial: $SERIAL"
  echo "app apk: $([ "$INSTALL_APP" = "1" ] && [ -f "$APP_APK" ] && echo "$APP_APK ($(wc -c < "$APP_APK" | tr -d ' ') bytes)" || echo none)"
} > "$OUT_DIR/device.txt"
log "device: $MAKER $MODEL, $ABI, Android $ANDROID_REL (sdk $SDK)"

if [ "$INSTALL_APP" = "1" ]; then
  if [ -f "$APP_APK" ]; then
    log "installing app APK $APP_APK"
    adb_ install -r -t "$APP_APK" >/dev/null || log "WARNING: the app APK did not install (different signature installed?); continuing"
  else
    log "app APK not found, skipped: $APP_APK"
  fi
fi
log "installing test APK $TEST_APK"
adb_ install -r -t "$TEST_APK" >/dev/null || die "test APK did not install"

# ---- run the matrix ----------------------------------------------------------------------------------------------
CSV="$OUT_DIR/perf.csv"
echo "date,manufacturer,model,abi,android,sdk,soc,provider_requested,provider_effective,threads,frames,frame_size,model_load_ms,first_frame_ms,p50_ms,p95_ms,mean_ms,max_ms,peak_pss_dumpsys_kb,peak_pss_test_kb,status" > "$CSV"

peak_pss_sampler() { # writes the highest TOTAL PSS (kB) seen to $1 until killed
  local out="$1" peak=0 v line
  echo 0 > "$out"
  while true; do
    line="$(adb_ shell dumpsys meminfo "$TEST_PACKAGE" 2>/dev/null | tr -d '\r' | grep -m1 -E '^ *TOTAL( PSS:|  )' || true)"
    if [ -n "$line" ]; then
      case "$line" in
        *"TOTAL PSS:"*) v="$(printf '%s' "$line" | sed -E 's/.*TOTAL PSS: *([0-9]+).*/\1/')" ;;
        *) v="$(printf '%s' "$line" | awk '{print $2}')" ;;
      esac
      case "$v" in ''|*[!0-9]*) ;; *) if [ "$v" -gt "$peak" ]; then peak="$v"; echo "$peak" > "$out"; fi ;; esac
    fi
    sleep 1
  done
}

status_value() { # key file -> value of INSTRUMENTATION_STATUS: key=value
  sed -n -E "s/^INSTRUMENTATION_STATUS: $1=(.*)$/\\1/p" "$2" | tr -d '\r' | tail -n 1
}

for p in $PROVIDERS; do
  for t in $THREADS; do
    tag="${p}-t${t}"
    raw="$OUT_DIR/raw/$tag.txt"
    log "running $tag ($FRAMES frames, $WARMUP warm-up)"
    adb_ shell am force-stop "$TEST_PACKAGE" >/dev/null 2>&1 || true
    pssfile="$(mktemp)"
    peak_pss_sampler "$pssfile" &
    sampler=$!
    set +e
    adb_ shell am instrument -w -r -e class "$TEST_CLASS" -e provider "$p" -e threads "$t" \
      -e frames "$FRAMES" -e warmup "$WARMUP" "$RUNNER" > "$raw" 2>&1
    rc=$?
    set -e
    kill "$sampler" 2>/dev/null || true
    wait "$sampler" 2>/dev/null || true
    dump_peak="$(cat "$pssfile")"; rm -f "$pssfile"

    status=FAIL
    if tr -d '\r' < "$raw" | grep -q '^OK (1 test)'; then status=OK; fi
    if [ "$rc" -ne 0 ]; then status=FAIL; fi
    effective="$(status_value perf_effective_provider "$raw")"
    row="$(date +%Y-%m-%d),$MAKER,$MODEL,$ABI,$ANDROID_REL,$SDK,$SOC,$p,${effective:-},$t,$(status_value perf_frames "$raw"),$(status_value perf_frame_size "$raw"),$(status_value perf_model_load_ms "$raw"),$(status_value perf_first_frame_ms "$raw"),$(status_value perf_p50_ms "$raw"),$(status_value perf_p95_ms "$raw"),$(status_value perf_mean_ms "$raw"),$(status_value perf_max_ms "$raw"),$dump_peak,$(status_value perf_peak_pss_kb "$raw"),$status"
    echo "$row" | tr -d '"' >> "$CSV"
    log "$tag: $status p50=$(status_value perf_p50_ms "$raw") p95=$(status_value perf_p95_ms "$raw") first=$(status_value perf_first_frame_ms "$raw") (raw: $raw)"
  done
done

# ---- markdown summary --------------------------------------------------------------------------------------------
SUMMARY="$OUT_DIR/summary.md"
{
  echo "# Android liveness performance, $(date +%Y-%m-%d)"
  echo
  echo "- Device: $MAKER $MODEL, ABI $ABI, Android $ANDROID_REL (sdk $SDK), SoC $SOC, $CORES CPU cores"
  echo "- Frames per setting: $FRAMES measured after $WARMUP warm-up; frame 640x480 JPEG, steps timed together: decode, face analysis, passive PAD"
  echo "- App APK: $(sed -n 's/^app apk: //p' "$OUT_DIR/device.txt")"
  echo "- Raw instrumentation output: raw/<provider>-t<threads>.txt; data: perf.csv"
  echo
  echo "| Provider asked | Provider used | Threads | First frame (ms) | p50 (ms) | p95 (ms) | Max (ms) | Model load (ms) | Peak PSS dumpsys (MB) | Peak PSS test (MB) | Status |"
  echo "|---|---|---|---|---|---|---|---|---|---|---|"
  awk -F, 'NR>1 {
    d = ($19 ~ /^[0-9]+$/) ? sprintf("%.1f", $19/1024) : "-"
    t = ($20 ~ /^[0-9]+$/) ? sprintf("%.1f", $20/1024) : "-"
    printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n", $8, $9, $10, $14, $15, $16, $18, $13, d, t, $21
  }' "$CSV"
  echo
  echo "Provider used differs from provider asked when the installed ONNX Runtime does not include the provider and the engine fell back to CPU."
} > "$SUMMARY"
log "wrote $CSV and $SUMMARY"
