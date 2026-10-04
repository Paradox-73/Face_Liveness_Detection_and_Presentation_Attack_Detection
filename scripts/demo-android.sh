#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Scripted end-to-end demo of the PS04 Android flows on the real Android Registration Client (ARC debug APK) on
# the emulator, against MockSync as the MOSIP server, with the evaluation frame packs as the camera.
#
# The camera is replaced by ARC's debug replay (livenessmanager ReplayFrameSource): the file
# liveness-replay/flow-source.txt in the app's external files folder selects the pack that every capture screen
# plays. "follow <prefix>" makes the replay answer the challenge prompts (<prefix>-main, -blink, -smile) after a
# reaction time, like a person in front of the camera. "masked" keeps the preview dark, so the screenshots of the
# capture screen show no face. The live webcam is never used.
#
# Sections (run in this order; --sections picks some):
#   login       officer password sign-in, face sign-in (login_auth), a live person who is not enrolled, a replay
#               attack at sign-in
#   resident    Lost UIN registration as officer1: camera unavailable, Retry after the camera is back, a screen
#               replay refused on attempt 1, the real low-confidence resident answering challenges on attempt 2,
#               exception photo, operator packet authentication with face (packet_auth), acknowledgement
#   attacks     second registration: printed photo, injected still stream and screen replay, one per attempt,
#               until the maximum attempts block the capture for that registration
#   approval    supervisor face sign-in, end-of-day approval with face (eod_auth: replay refused, live face
#               accepted), packet upload, liveness evidence of the uploaded packets read back from MockSync
#   config      MockSync changes policy.packet_auth to ALWAYS_ACTIVE, ARC synchronises, the operator's packet
#               authentication by face now asks for challenges; the change is reverted and synchronised back
#   override    MockSync sets registration.on_max_failure=SUPERVISOR_OVERRIDE (one attempt, challenges always,
#               diagnostic mode on); a resident who does not answer the challenge fails, the capture screen offers
#               "Supervisor override", supervisor1 verifies with face liveness, the resident's photo is stored
#               flagged OVERRIDE (REG-EVT-LV16); diagnostic panel, REG-EVT-LV17 and the metrics file are checked;
#               the settings are reverted. The approval section then uploads the packet and checks OVERRIDE in it.
#   offline     MockSync unreachable (adb reverse removed, so the server is gone for the tablet) and no network
#               (airplane mode): password sign-in, registration with packet authentication by face, supervisor
#               face sign-in and approval by face; upload fails; network back, upload succeeds
#   latency     per-frame processing time of every liveness attempt of the run, from Logcat
#
# Usage (Git Bash on Windows, or bash, from the repository root):
#   scripts/demo-android.sh                         # every section
#   scripts/demo-android.sh --sections "login resident"
#   scripts/demo-android.sh --policy replay         # the policy the recorded packs fit (see below); default: shipped
#   scripts/demo-android.sh --out DIR               # output folder (default $MOSIP_RUN_ROOT/demo/android-<time>)
#   . scripts/demo-android.sh --out DIR             # only defines the functions, for running single steps
#
# Replay profile: the recorded frame packs are 4-6 s long, and a clip that repeats is (correctly) refused as a
# replayed video. MockSync's shipped policy (challenges always for residents and operator sign-in, 30 passive
# frames) needs longer clips, so the demo first sets the policy the packs were recorded for (DEMO_PROFILE below:
# passive first, 8 passive frames) and synchronises. ARC keeps a synchronised value when the server stops sending
# the key, so every key the demo changes is put back by setting MockSync's own value again (read at the start) and
# synchronising; the demo's MockSync overrides are removed at the end, when they equal the shipped values.
#
# Output: results.txt (one PASS/FAIL line per check), demo.log, logcat-<section>.txt (tags Liveness,
# AuthenticationApi, RegistrationServiceImpl), screenshots <nn>-<name>.png, packets/<rid>.liveness.json,
# frame-times.txt.
#
# Needs:
#   - the emulator running with the debug APK (scripts/android-run-emulator.sh --headless; ARC_WEBCAM=emulated
#     keeps the webcam out), the machine registered in MockSync and officer1 / supervisor1 onboarded with face
#     pack s3 (docs/install/android.md sections 5 and 6);
#   - MockSync on http://127.0.0.1:8080 (tools/mock-sync/scripts/run-mock-sync.sh);
#   - the frame packs in $MOSIP_RUN_ROOT/frame-packs (tools/eval/fetch-frame-packs.py; s3n-* from
#     tools/mock-sync/e2e/NoisyPack.java). They are pushed to the app when missing.
#   - Python 3 (screen parsing), curl.
#   The Lost UIN steps tap fixed points on the fingers and eyes of the exception screens: they assume the
#   1280x800 tablet profile of scripts/android-run-emulator.sh.
#
# Environment:
#   MOSIP_RUN_ROOT     default <repo>/../run
#   MOCKSYNC_URL       default http://127.0.0.1:8080
#   ARC_EMULATOR_PORT  emulator console port; serial emulator-<port> (default 5554)
#   OFFICER, OFFICER_PASSWORD, SUPERVISOR, SUPERVISOR_PASSWORD   accounts (default officer1, supervisor1)
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_ROOT="${MOSIP_RUN_ROOT:-$(cd "$REPO/.." && pwd)/run}"
PACKS="$RUN_ROOT/frame-packs"
MOCKSYNC_URL="${MOCKSYNC_URL:-http://127.0.0.1:8080}"
PORT="${ARC_EMULATOR_PORT:-5554}"
OFFICER="${OFFICER:-officer1}"
OFFICER_PASSWORD="${OFFICER_PASSWORD:-Mosip@Officer1}"
SUPERVISOR="${SUPERVISOR:-supervisor1}"
SUPERVISOR_PASSWORD="${SUPERVISOR_PASSWORD:-Mosip@Supervisor1}"
PACKAGE="io.mosip.registration_client"
REPLAY_DIR="/sdcard/Android/data/$PACKAGE/files/liveness-replay"
PACK_LIST="s1-main s1-blink s1-smile s3-main s3-blink s3-smile s3n-main s3n-blink s3n-smile a-s2-print-still
  a-s2-identical a-s2-screen-replay a-s3-screen-replay"
ALL_SECTIONS="login resident attacks override approval config offline latency"
SECTIONS="$ALL_SECTIONS"
POLICY_MODE=shipped
OUT="$RUN_ROOT/demo/android-$(date +%Y%m%d-%H%M%S)"
export ANDROID_SERIAL="emulator-$PORT"
# Device paths such as /sdcard/... must reach adb unchanged (Git Bash would rewrite them).
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

while [ $# -gt 0 ]; do
  case "$1" in
    --sections) SECTIONS="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --policy) POLICY_MODE="$2"; shift 2 ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option $1 (see --help)" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT/packets"
export ARC_UI_XML="$OUT/.ui.xml"
# shellcheck source=android-ui.sh
. "$REPO/scripts/android-ui.sh"

SHOT=0
PASSED=0
FAILED=0
SECTION=setup

log() { echo "[demo-android $(date +%H:%M:%S)] $*" | tee -a "$OUT/demo.log"; }

# check ID DESCRIPTION COMMAND...: runs the command and records PASS or FAIL.
check() {
  local id="$1" what="$2"
  shift 2
  if "$@" >/dev/null 2>&1; then
    PASSED=$((PASSED + 1)); echo "PASS $id [$SECTION] $what" | tee -a "$OUT/results.txt" "$OUT/demo.log"
  else
    FAILED=$((FAILED + 1)); echo "FAIL $id [$SECTION] $what" | tee -a "$OUT/results.txt" "$OUT/demo.log"
    shot "FAIL-$id"
  fi
}

# shot NAME: numbered screenshot in the output folder.
shot() {
  SHOT=$((SHOT + 1))
  local f; f="$OUT/$(printf '%02d' "$SHOT")-$1.png"
  ui_shot "$f"
  log "screenshot $(basename "$f")"
}

host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

# ------------------------------------------------------------------------------------------- Logcat

# logcat_clear: empties the Logcat buffers; "adb logcat -c" sometimes fails on a busy device, so it is checked.
logcat_clear() {
  local i
  logcat_save
  for i in 1 2 3 4 5; do
    adb logcat -c >/dev/null 2>&1
    [ -z "$(logcat_dump | grep -v '^-')" ] && return 0
    sleep 1
  done
  log "Logcat could not be cleared"
  return 1
}
logcat_dump() {
  adb logcat -d -v time -s Liveness LivenessMetrics AuthenticationApi RegistrationServiceImpl 2>/dev/null \
    | tr -d '\r'
}
# logcat_save: adds the current Logcat lines to the section's file (each line once, in time order).
logcat_save() {
  local f="$OUT/logcat-$SECTION.txt"
  { cat "$f" 2>/dev/null; logcat_dump | grep -v '^-'; } | sort -u > "$f.tmp" && mv "$f.tmp" "$f"
}
logcat_has() { logcat_dump | grep -q -- "$1"; }

# wait_log TEXT [SECONDS]: waits until TEXT appears in Logcat.
wait_log() {
  local text="$1" timeout="${2:-60}" start
  start=$(date +%s)
  until logcat_has "$text"; do
    [ $(( $(date +%s) - start )) -lt "$timeout" ] || return 1
    sleep 1
  done
}

# wait_count TEXT N [SECONDS]: waits until TEXT appears at least N times in Logcat.
wait_count() {
  local text="$1" want="$2" timeout="${3:-120}" start
  start=$(date +%s)
  until [ "$(logcat_dump | grep -c -- "$text")" -ge "$want" ]; do
    [ $(( $(date +%s) - start )) -lt "$timeout" ] || return 1
    sleep 1
  done
}

last_attempt() { logcat_dump | grep "Liveness attempt ended" | tail -1; }
# attempt_matches REGEX: the last liveness attempt's result line matches REGEX (grep -E).
attempt_matches() { last_attempt | grep -Eq -- "$1"; }
# result_matches REGEX: the last capture screen result line matches REGEX.
result_matches() { logcat_dump | grep "Capture screen result" | tail -1 | grep -Eq -- "$1"; }
# audited EVENT: the audit sink stored EVENT (for example REG-EVT-LV15) since Logcat was last cleared.
audited() { logcat_has "Audit stored $1"; }
audited_all() { local e; for e in "$@"; do audited "$e" || return 1; done; }
# saved_log_has TEXT / saved_log_count TEXT N: the section's saved Logcat has TEXT (in exactly N distinct lines).
saved_log_has() { grep -q -- "$1" "$OUT/logcat-$SECTION.txt"; }
saved_log_count() { [ "$(grep -h -- "$1" "$OUT/logcat-$SECTION.txt" | sort -u | wc -l)" -eq "$2" ]; }
# no_log TEXT: TEXT is not in Logcat since it was last cleared.
no_log() { ! logcat_has "$1"; }

# ------------------------------------------------------------------------------------------- frame packs

# replay SPEC: what every capture screen plays next, e.g. "s3-main" or "follow s3n"; always masked.
replay() {
  adb shell "echo '$1 15 masked' > $REPLAY_DIR/flow-source.txt"
  log "replay: $1"
}

# push_packs: copies the frame packs the demo uses into the app's replay folder (packs whose frame count differs
# from the local copy).
push_packs() {
  local p local_n device_n
  for p in $PACK_LIST; do
    [ -d "$PACKS/$p" ] || { log "frame pack $PACKS/$p is missing"; return 1; }
    local_n="$(find "$PACKS/$p" -maxdepth 1 -name '*.jpg' | wc -l | tr -d ' ')"
    device_n="$(adb shell "ls $REPLAY_DIR/$p 2>/dev/null | wc -l" | tr -d ' \r')"
    if [ "$local_n" != "$device_n" ]; then
      adb push "$(host_path "$PACKS/$p")" "$REPLAY_DIR/" >/dev/null || return 1
      log "pushed frame pack $p ($local_n frames)"
    fi
  done
}

# camera_pack NAME SOURCE: makes the replay folder NAME a copy of pack SOURCE ("none" removes it). The screen
# reads the folder when an attempt starts, so this changes what the "camera" shows between attempts.
camera_pack() {
  adb shell "rm -rf $REPLAY_DIR/$1"
  [ "$2" = "none" ] || adb shell "cp -r $REPLAY_DIR/$2 $REPLAY_DIR/$1"
}

# ------------------------------------------------------------------------------------------- app navigation

app_restart() {
  adb shell am force-stop "$PACKAGE"
  adb shell am start -n "$PACKAGE/.MainActivity" >/dev/null
  ui_wait "=NEXT" 120
  ui_tap "=English" || true
  sleep 1
}

# enter_user USER: username step of the login screen, up to the password step.
enter_user() {
  ui_fill 1 "$1" && ui_tap "=NEXT" && ui_wait "=LOGIN" 30
}

login_password() {
  enter_user "$1" || return 1
  ui_fill 1 "$2" secret && ui_tap "=LOGIN" || return 1
  ui_wait "New Registration" 240
}

# login_face USER: face sign-in with the current replay; waits for the home screen.
login_face() {
  enter_user "$1" || return 1
  logcat_clear
  ui_tap "Sign in with face" || return 1
  capture_run 2 || return 1
  ui_wait "New Registration" 60
}

# tap_retry / tap_cancel: the capture screen's buttons (Material shows them in capitals).
tap_retry() { ui_tap "=Retry" || ui_tap "=RETRY"; }
tap_cancel() { ui_tap "=Cancel" || ui_tap "=CANCEL"; }

# capture_run MAX: follows an open capture screen through at most MAX attempts, pressing Retry after a failure
# like an operator would. Exit status 0 when an attempt passed. Logcat must have been cleared before the screen
# opened.
capture_run() {
  local max="${1:-1}" n=0
  while :; do
    n=$((n + 1))
    wait_count "Liveness attempt ended" "$n" 150 || return 2
    case "$(last_attempt)" in *", PASS"*) return 0 ;; esac
    [ "$n" -lt "$max" ] || return 1
    sleep 1
    ui_dump
    ui_has "=Retry" || ui_has "=RETRY" || return 1
    log "attempt $n failed: $(last_attempt | sed 's/.*LivenessCaptureResult//'); Retry"
    tap_retry
  done
}

# capture_close: cancels the capture screen if it is still open (after failed attempts) and waits for its result.
capture_close() {
  ui_dump
  if ui_has "=Face verification" || ui_has "=Supervisor verification"; then
    if tap_cancel; then
      wait_log "Capture screen returned" 30
      sleep 2
    fi
  fi
}

# prompt_is_generic: the capture screen shows one of the engine's generic refusal texts, which never name the
# attack type.
prompt_is_generic() {
  ui_dump
  ui_has "Face verification could not be completed" || ui_has "We could not verify face liveness" \
    || ui_has "after 3 attempts"
}

# shot_on_log TEXT NAME [SECONDS]: screenshot as soon as TEXT appears in Logcat (polls without pausing, so a
# challenge prompt is caught before the replayed movement answers it).
shot_on_log() {
  local start
  start=$(date +%s)
  until adb logcat -d -s Liveness 2>/dev/null | grep -q -- "$1"; do
    [ $(( $(date +%s) - start )) -lt "${3:-60}" ] || return 1
  done
  shot "$2"
}

# screen_has TEXT: the screen shows TEXT now.
screen_has() { ui_dump && ui_has "$1"; }

# home: back to the operator home screen from anywhere (confirms "leave the registration" dialogs).
home() {
  local i
  for i in 1 2 3 4 5 6; do
    ui_dump
    ui_has "New Registration" && return 0
    # The home screen's other tab (Operational Tasks), possibly scrolled: Back there would close the app.
    if ui_has "Tab 1 of 4"; then
      adb shell input swipe 640 250 640 650 300; sleep 1
      ui_tap "=Registration Tasks"; sleep 2
      continue
    fi
    if ui_has "Go To Home"; then ui_tap "Go To Home"; sleep 3; continue; fi
    if ui_has "_back_button"; then ui_tap "_back_button"; sleep 3; continue; fi
    ui_back; sleep 2
    ui_dump
    for b in "=YES" "=CONFIRM" "=OK" "=EXIT"; do
      if ui_has "$b"; then ui_tap "$b"; sleep 2; break; fi
    done
  done
  ui_has "New Registration"
}

# lost_uin_start: new Lost UIN registration up to the biometrics grid, every finger and iris marked as an
# exception (the emulator has no finger or iris device).
lost_uin_start() {
  ui_tap "Lost UIN" || return 1
  ui_wait "=SUBMIT" 30 || return 1
  ui_tap "=French"; sleep 1
  ui_tap "=English" 2; sleep 1
  ui_tap "=SUBMIT" || return 1
  ui_wait "=INFORMED" 30 || return 1
  ui_tap "I have read and accept"; sleep 1
  ui_tap "=INFORMED" || return 1
  ui_wait "=CONTINUE" 30 || return 1
  sleep 1
  ui_tap "=CONTINUE" || return 1
  ui_wait "=Thumbs" 30 || return 1
  mark_exceptions "=Iris" "437 440" "840 440"
  mark_exceptions "=Right Hand" "575 420" "625 400" "673 430" "719 480"
  mark_exceptions "=Left Hand" "560 460" "607 420" "654 400" "704 430"
  mark_exceptions "=Thumbs" "580 430" "700 430"
  adb shell input swipe 640 600 640 250 400
  sleep 2
  ui_wait "=Face" 10
}

# mark_exceptions CARD POINT...: opens a modality card, "Mark Exception", taps every finger or eye, goes back.
mark_exceptions() {
  local card="$1" p
  shift
  ui_tap "$card" || return 1
  ui_wait "Mark Exception" 15 || return 1
  ui_tap "Mark Exception"; sleep 2
  for p in "$@"; do
    # shellcheck disable=SC2086
    adb shell input tap $p; sleep 1
  done
  ui_tap menu_back_button; sleep 3
}

# face_page: opens the Face card of the grid and scrolls to its Scan button.
face_page() {
  ui_tap "=Face" || return 1
  sleep 3
  adb shell input swipe 640 550 640 150 400; sleep 1
  adb shell input swipe 640 550 640 150 400; sleep 2
  ui_wait "=Scan" 20
}

# scan: presses Scan with a fresh Logcat.
scan() {
  logcat_clear
  ui_tap "=Scan"
}

# close_capture_dialog: closes ARC's "Face Capture" / "Exception Capture" dialog that shows the stored image.
close_capture_dialog() {
  # The dialog can take several seconds to appear on a loaded emulator.
  ui_wait_any 15 "attempts left" "attempt left" >/dev/null
  if ui_has "attempts left" || ui_has "attempt left"; then
    adb shell input tap 1090 118
    sleep 2
  fi
}

# exception_photo_and_preview: NEXT to the exception photo, photo with the built-in camera (no liveness check,
# policy exception_photo OFF), back to the grid, CONTINUE to the preview and on to the authentication step.
exception_photo_and_preview() {
  ui_tap "=NEXT"; sleep 3
  replay s3-main
  scan
  wait_log "Capture screen returned" 60 || return 1
  close_capture_dialog
  ui_tap "=NEXT"; sleep 3
  ui_tap "=CONTINUE" || return 1
  ui_wait "Photo" 30 || sleep 5
  ui_tap "=CONTINUE" || return 1
  ui_wait "Authenticate with face" 30
}

# packet_auth_face: operator authentication of the packet by face (current replay), then AUTHENTICATE; waits for
# the acknowledgement.
packet_auth_face() {
  logcat_clear
  ui_tap "Authenticate with face" || return 1
  capture_run 2 || return 1
  wait_log "Face authentication packet_auth: FaceMatch\[MATCHED" 60 || return 1
  sleep 2
  adb shell input swipe 640 550 640 250 400; sleep 1
  shot packet-auth-face-verified
  ui_tap "=AUTHENTICATE" || return 1
  ui_wait "Registration Acknowledgement" 90
}

# ops_tile NAME: opens a tile of Operational Tasks (scrolls to it).
ops_tile() {
  home
  ui_tap "Operational Tasks" || return 1
  sleep 3
  ui_dump
  ui_has "$1" || { adb shell input swipe 640 550 640 200 400; sleep 2; }
  ui_tap "$1"
}

# approve_all_pending: supervisor reviews and approves every pending application (the approval itself is
# authenticated afterwards).
approve_all_pending() {
  local ids id
  ui_wait "Pending Approval" 20 || return 1
  ui_dump
  ids="$(ui_text | tr '/' '\n' | grep -o '1000[0-9]\{25\}' | sort -u)"
  for id in $ids; do
    ui_tap "=$id" || continue
    ui_wait "=APPROVE" 20 || continue
    sleep 2
    ui_tap "=APPROVE"; sleep 2
    ui_back; sleep 2
  done
  [ -n "$ids" ]
}

open_and_approve_pending() { ops_tile "Pending Approval" && approve_all_pending; }

# packet_evidence_ok FILE: MockSync's evidence of one packet has the face BIR entries, the LV audit rows, the
# liveness fields of the meta info and the operator's face (packet authenticated by face).
packet_evidence_ok() {
  grep -q '"LIVENESS_DECISION"' "$1" && grep -q '"LIVENESS_ENGINE"' "$1" && grep -q 'REG-EVT-LV' "$1" \
    && grep -q '"livenessDecision"' "$1" && grep -q 'officer_bio_cbeff' "$1"
}

# select_all_rows: the header checkbox of the application tables (Pending Approval, Manage Applications).
select_all_rows() { adb shell input tap 60 339; sleep 1; }

# supervisor_auth_face REPLAY_ATTACK REPLAY_LIVE: AUTHENTICATE on Pending Approval, supervisor dialog, face with
# the attack pack (must be refused, then cancelled), then face with the live pack.
supervisor_auth_face() {
  select_all_rows
  ui_tap "=AUTHENTICATE" || return 1
  ui_wait "Supervisor's Authentication" 20 || return 1
  ui_fill 1 "$SUPERVISOR"
  replay "$1"
  logcat_clear
  ui_tap "Authenticate with face" || return 1
  capture_run 1
  sleep 1
  shot eod-replay-refused
  check "${2}-attack" "supervisor approval: replay refused" attempt_matches "FAIL|TIMEOUT"
  check "${2}-generic" "supervisor approval: generic refusal message" prompt_is_generic
  capture_close
  replay "follow s3"
  logcat_clear
  ui_tap "Authenticate with face" || return 1
  capture_run 2 || return 1
  wait_log "Face authentication eod_auth: FaceMatch\[MATCHED" 60
}

# ------------------------------------------------------------------------------------------- MockSync

mocksync_up() { curl -sf -m 3 "$MOCKSYNC_URL/v1/syncdata/actuator/health" >/dev/null 2>&1; }
mocksync_set() {
  curl -sf -m 5 -X PUT -H "Content-Type: application/json" -d "{\"$1\":\"$2\"}" "$MOCKSYNC_URL/admin/config" \
    >/dev/null
}
mocksync_unset() { curl -sf -m 5 -X DELETE "$MOCKSYNC_URL/admin/config/$1" >/dev/null; }
mocksync_packet_names() {
  curl -sf -m 5 "$MOCKSYNC_URL/admin/packets" \
    | python -c 'import json,sys; [print(p["name"]) for p in json.load(sys.stdin)]' | tr -d '\r'
}

# save_new_packets BEFORE_FILE: stores MockSync's decrypted liveness evidence of every packet that is not listed
# in BEFORE_FILE; prints the number of new packets.
save_new_packets() {
  local n=0 name
  for name in $(mocksync_packet_names); do
    grep -qx "$name" "$1" 2>/dev/null && continue
    if curl -sf -m 20 "$MOCKSYNC_URL/admin/packets/$name/liveness" > "$OUT/packets/.part"; then
      mv "$OUT/packets/.part" "$OUT/packets/${name%%-*}.liveness.json"
      n=$((n + 1))
    fi
  done
  echo "$n"
}

# packet_has RID TEXT: the saved evidence of packet RID contains TEXT.
packet_has() { grep -q -- "$2" "$OUT/packets/$1.liveness.json" 2>/dev/null; }

# upload_all: Manage Applications, select every row, UPLOAD; waits for MockSync to receive packets.
upload_all() {
  local before="$OUT/.packets-before"
  mocksync_packet_names > "$before"
  ops_tile "Application Upload" || return 1
  ui_wait "Manage Applications" 20 || return 1
  select_all_rows
  ui_tap "=UPLOAD" || return 1
  local i
  for i in $(seq 1 30); do
    [ "$(mocksync_packet_names | grep -cvxFf "$before")" -gt 0 ] && break
    sleep 2
  done
  sleep 5
  save_new_packets "$before"
}

# ------------------------------------------------------------------------------------------- sections

section_login() {
  app_restart
  shot login-screen
  check A-L1 "officer password sign-in reaches the home screen" login_password "$OFFICER" "$OFFICER_PASSWORD"
  shot home-officer

  app_restart
  replay "follow s3"
  enter_user "$OFFICER"
  check A-L2 "\"Sign in with face\" offered to an onboarded operator" screen_has "Sign in with face"
  shot password-step-with-face
  logcat_clear
  ui_tap "Sign in with face"
  # Two tries: on a loaded emulator the first frames after the app start can exceed engine.inference_slow_ms,
  # which ends the attempt with an engine error that uses no attempt (the operator presses Retry).
  check A-L3 "face sign-in (login_auth): liveness PASS" capture_run 2
  check A-L4 "face sign-in: 1:1 match with the onboarding face" \
    wait_log "Face authentication login_auth: FaceMatch\[MATCHED" 60
  check A-L5 "face sign-in reaches the home screen" ui_wait "New Registration" 60
  shot home-after-face-sign-in
  logcat_save

  # A live person who is not enrolled: pack s1 answers the challenges; the match must fail.
  app_restart
  replay "follow s1"
  enter_user "$OFFICER"
  logcat_clear
  ui_tap "Sign in with face"
  check A-L6 "live person who is not enrolled: liveness PASS" capture_run 3
  check A-L7 "live person who is not enrolled: no match" wait_log "Face authentication login_auth: FaceMatch\[NOT" 60
  capture_close
  sleep 2
  shot face-sign-in-no-match
  check A-L8 "no match: sign-in stays on the password step with the mismatch message" \
    screen_has "does not match this user"
  logcat_save

  # A screen replay of the enrolled face: refused with a generic message, then cancelled.
  replay a-s3-screen-replay
  logcat_clear
  ui_tap "Sign in with face"
  capture_run 1
  sleep 1
  shot face-sign-in-replay-refused
  check A-L9 "screen replay at sign-in refused" attempt_matches "FAIL|TIMEOUT"
  check A-L10 "refusal shown with a generic message that names no attack type" prompt_is_generic
  capture_close
  logcat_save
}

section_resident() {
  app_restart
  login_password "$OFFICER" "$OFFICER_PASSWORD" || true
  check A-R0 "Lost UIN registration reaches the biometrics grid with exceptions marked" lost_uin_start
  face_page
  # The "camera" is the replay folder cam-*; it is missing at first (camera unavailable).
  camera_pack cam-main none; camera_pack cam-blink none; camera_pack cam-smile none
  replay "follow cam"
  scan
  check A-R1 "camera unavailable: device error shown" ui_wait "The face camera is not available" 60
  shot resident-camera-unavailable
  check A-R2 "camera unavailable uses no attempt" attempt_matches "ERROR/DEVICE.*attempts=0/"
  # The camera is back, but a screen replay is presented: attempt 1 fails.
  camera_pack cam-main a-s2-screen-replay
  tap_retry
  wait_count "Liveness attempt ended" 2 120
  sleep 1
  shot resident-attempt1-refused
  check A-R3 "screen replay refused on attempt 1 with a generic message" prompt_is_generic
  check A-R4 "attempt 1 of 3 used, Retry offered" attempt_matches "attempts=1/3"
  # The real resident (low passive score: noisy camera) is presented; Retry.
  camera_pack cam-main s3n-main; camera_pack cam-blink s3n-blink; camera_pack cam-smile s3n-smile
  tap_retry
  check A-R5 "low-confidence resident: challenge prompt shown" \
    shot_on_log "Screen state CHALLENGE liveness.challenge" resident-challenge 60
  check A-R6 "challenge answered: capture PASS on attempt 2" wait_log "Capture screen result OK" 90
  check A-R7 "session escalated to challenges (REG-EVT-LV03) and passed (REG-EVT-LV10)" \
    audited_all REG-EVT-LV03 REG-EVT-LV10
  logcat_save
  close_capture_dialog
  shot resident-face-accepted
  check A-R8 "exception photo taken with the built-in camera, preview and authentication step reached" \
    exception_photo_and_preview
  check A-R9 "exception photo taken without a liveness check (policy OFF)" logcat_has "LIVENESS_NOT_REQUIRED"
  logcat_save
  replay s3-main
  check A-R10 "operator packet authentication by face (packet_auth): liveness and match, acknowledgement" \
    packet_auth_face
  shot resident-acknowledgement
  logcat_save
  home
}

section_attacks() {
  app_restart
  login_password "$OFFICER" "$OFFICER_PASSWORD" || true
  lost_uin_start
  face_page
  local pack n=0
  for pack in a-s2-print-still a-s2-identical a-s2-screen-replay; do
    n=$((n + 1))
    replay "$pack"
    scan
    wait_log "Liveness attempt ended" 120
    sleep 1
    shot "attack-$n-$pack"
    check "A-A$n" "attack $pack refused (attempt $n of 3)" attempt_matches "FAIL|TIMEOUT"
    check "A-A${n}g" "generic message after attempt $n" prompt_is_generic
    capture_close
    logcat_save
  done
  check A-A4 "third failure: recovery guidance, no Retry" saved_log_has "Screen state MAX_ATTEMPTS"
  check A-A5 "maximum attempts audited once (REG-EVT-LV11)" saved_log_count "Audit stored REG-EVT-LV11" 1
  sleep 2
  adb shell input swipe 640 550 640 250 400
  sleep 1
  shot attack-blocked-banner
  check A-A6 "ARC shows the blocked message under Scan" screen_has "could not be completed"
  logcat_clear
  ui_tap "=Scan"; sleep 5
  check A-A7 "a further Scan opens no capture screen" no_log "Capture screen result"
  logcat_save
  home
}

# Policy the recorded frame packs fit (see "Replay profile" at the top).
DEMO_PROFILE="mosip.registration.liveness.policy.registration=PASSIVE_THEN_ACTIVE
mosip.registration.liveness.policy.login_auth=PASSIVE_THEN_ACTIVE
mosip.registration.liveness.policy.onboard_auth=PASSIVE_THEN_ACTIVE
mosip.registration.liveness.passive.min_frames=8"
# --policy shipped (the default) runs every flow at the values MockSync ships: nothing is overridden for the run
# (the replayed packs must then answer the challenges the shipped policy asks for, see "Replay profile").
case "$POLICY_MODE" in
  shipped) DEMO_PROFILE="" ;;
  replay) ;;
  *) echo "unknown --policy $POLICY_MODE (shipped or replay)" >&2; exit 2 ;;
esac

# Shipped value of a liveness key as MockSync serves it without the demo's overrides (read once at the start;
# keys MockSync does not send get the engine default of LivenessConfigKeys).
declare -A SHIPPED=(
  [mosip.registration.liveness.registration.max_retries]=3
  [mosip.registration.liveness.registration.on_max_failure]=BLOCK
)
read_shipped() {
  local key value
  while IFS='=' read -r key value; do
    [ -n "$key" ] && SHIPPED[$key]="$value"
  done < <(curl -sf -m 5 "$MOCKSYNC_URL/admin/config" \
    | python -c 'import json,sys; [print(k + "=" + v) for k, v in json.load(sys.stdin)["liveness"].items()]' \
    | tr -d '\r')
}

# settings set|restore|forget LIST: applies the key=value lines; restore sets each key back to its shipped value
# (so the tablet gets it at the next synchronisation); forget removes the overrides from MockSync.
settings() {
  local line key
  for line in $2; do
    key="${line%%=*}"
    case "$1" in
      set) mocksync_set "$key" "${line#*=}" || return 1 ;;
      restore) mocksync_set "$key" "${SHIPPED[$key]:?no shipped value for $key}" || return 1 ;;
      forget) mocksync_unset "$key" || return 1 ;;
    esac
  done
}

# demo_profile: MockSync gets the replay profile and the tablet synchronises it.
demo_profile() {
  # Also puts back values an interrupted earlier run may have left on the tablet.
  settings restore "$OVERRIDE_SETTINGS" && settings restore "$CONFIG_SETTINGS" || return 1
  settings set "$DEMO_PROFILE" || return 1
  app_restart
  login_password "$OFFICER" "$OFFICER_PASSWORD" || return 1
  sync_now
}

# The override section's settings: challenges always, one attempt, a short challenge time (the replayed resident
# does not answer, so the attempt ends before its clip would repeat), supervisor override, diagnostic mode.
OVERRIDE_SETTINGS="mosip.registration.liveness.policy.registration=ALWAYS_ACTIVE
mosip.registration.liveness.registration.on_max_failure=SUPERVISOR_OVERRIDE
mosip.registration.liveness.registration.max_retries=1
mosip.registration.liveness.active.challenge_timeout_ms=2000
mosip.registration.liveness.diagnostic.enable=Y"

# override_settings set|unset: unset puts back the shipped values and the replay profile's policy.registration.
override_settings() {
  if [ "$1" = "set" ]; then
    settings set "$OVERRIDE_SETTINGS"
  else
    settings restore "$OVERRIDE_SETTINGS" && settings set "$DEMO_PROFILE"
  fi
}

# sync_now: manual synchronisation from the home screen.
sync_now() {
  home
  ui_tap "Synchronize Data" || return 1
  ui_wait "Sync Completed" 240 || return 1
  after_sync
}

# after_sync: a synchronisation that changed the configuration asks to restart the app; restart and sign in again.
after_sync() {
  sleep 2
  if screen_has "=Restart"; then
    ui_tap "=Restart"
    sleep 5
    ui_wait "=NEXT" 120 || return 1
    ui_tap "=English" || true
    sleep 1
    login_password "$OFFICER" "$OFFICER_PASSWORD"
  fi
}

# metrics_file_has TEXT: the app's metrics log (files/liveness/metrics.log, debug build: run-as) contains TEXT.
metrics_file_has() {
  adb shell "run-as $PACKAGE cat files/liveness/metrics.log" 2>/dev/null | grep -q -- "$1"
}

# override_dialog SUPERVISOR: "Supervisor override" on the capture screen, supervisor user ID, confirm.
override_dialog() {
  ui_tap "=Supervisor override" || ui_tap "=SUPERVISOR OVERRIDE" || return 1
  ui_wait "flagged for review" 20 || return 1
  shot override-confirm
  ui_fill 1 "$1" || return 1
  ui_tap "=SUPERVISOR OVERRIDE" || ui_tap "=Supervisor override"
}

section_override() {
  check A-V1 "MockSync: supervisor override policy for residents" override_settings set
  app_restart
  login_password "$OFFICER" "$OFFICER_PASSWORD" || true
  check A-V2 "synchronised: override policy on the tablet" sync_now
  lost_uin_start
  face_page
  # A live resident who does not answer the challenge (the replay does not follow prompts): TIMEOUT, which
  # allows an override (a suspected attack would not).
  replay s1-main
  scan
  check A-V3 "resident fails the only attempt without a suspected attack" wait_log "Liveness attempt ended" 90
  sleep 2
  check A-V4 "failure allows an override (not PAD, integrity or binding)" attempt_matches "FAIL_LIVENESS|TIMEOUT"
  check A-V5 "capture screen offers \"Supervisor override\"" ui_wait_any 20 "=Supervisor override" "=SUPERVISOR OVERRIDE"
  check A-V6 "diagnostic panel shows metrics and the execution provider" screen_has "retry rate"
  shot override-offered
  check A-V7 "diagnostic mode switched on is audited (REG-EVT-LV17)" audited REG-EVT-LV17
  # The supervisor verifies with the enrolled face (answers the prompts).
  replay "follow s3"
  logcat_clear
  check A-V8 "operator asks for the override and enters the supervisor" override_dialog "$SUPERVISOR"
  check A-V9 "supervisor face authentication (exception_auth): liveness and match" \
    wait_log "Face authentication exception_auth: FaceMatch\[MATCHED" 120
  check A-V10 "resident photo taken under the override and returned" wait_log "Capture screen result OK" 90
  check A-V11 "override photo flagged SUPERVISOR_OVERRIDE" logcat_has "SUPERVISOR_OVERRIDE"
  check A-V12 "override audited with the supervisor (REG-EVT-LV16)" audited REG-EVT-LV16
  logcat_save
  close_capture_dialog
  adb shell input swipe 640 550 640 250 400
  sleep 1
  shot override-stored
  check A-V13 "ARC shows the override message under Scan" screen_has "supervisor override"
  check A-V14 "anonymised metrics written to files/liveness/metrics.log" metrics_file_has "liveness sessions="
  check A-V15 "metrics lines carry no user or registration ID" \
    bash -c "! adb shell 'run-as $PACKAGE cat files/liveness/metrics.log' | grep -Eq '$OFFICER|$SUPERVISOR|1000[0-9]{25}'"
  exception_photo_and_preview
  replay s3-main
  check A-V16 "packet with the override face authenticated by face and created" packet_auth_face
  OVERRIDE_PACKET=1
  logcat_save
  home
  check A-V17 "MockSync: override settings reverted" override_settings unset
  check A-V18 "synchronised back to the shipped policy" sync_now
  home
}

section_approval() {
  app_restart
  replay "follow s3"
  check A-E1 "supervisor face sign-in" login_face "$SUPERVISOR"
  check A-E2 "pending applications reviewed and approved" open_and_approve_pending
  shot eod-pending-approved
  supervisor_auth_face a-s3-screen-replay A-E3
  check A-E4 "supervisor face authentication (eod_auth): liveness and match" \
    logcat_has "Face authentication eod_auth: FaceMatch\[MATCHED"
  sleep 5
  shot eod-approved
  check A-E5 "no application left pending" screen_has "Displaying 0 Applications"
  ui_tap pending_approval_back_button; sleep 2
  local n
  n="$(upload_all)"
  shot upload-done
  check A-E6 "packets uploaded to MockSync ($n new)" test "${n:-0}" -ge 1
  local f
  for f in "$OUT"/packets/*.liveness.json; do
    [ -f "$f" ] || continue
    check "A-E7-$(basename "$f" .liveness.json | tail -c 7)" \
      "packet $(basename "$f" .liveness.json): LIVENESS_* in the face BIR, LV audits, meta info" packet_evidence_ok "$f"
  done
  if [ -n "${OVERRIDE_PACKET:-}" ]; then
    check A-E8 "the override registration's packet carries LIVENESS_DECISION OVERRIDE and REG-EVT-LV16" \
      bash -c "grep -l 'OVERRIDE' '$OUT'/packets/*.liveness.json | xargs grep -l 'REG-EVT-LV16'"
  fi
  logcat_save
}

CONFIG_SETTINGS="mosip.registration.liveness.policy.packet_auth=ALWAYS_ACTIVE"

section_config() {
  check A-C1 "MockSync: policy.packet_auth=ALWAYS_ACTIVE" mocksync_set mosip.registration.liveness.policy.packet_auth ALWAYS_ACTIVE
  app_restart
  login_password "$OFFICER" "$OFFICER_PASSWORD" || true
  logcat_clear
  ui_tap "Synchronize Data"
  check A-C2 "manual synchronisation completes" ui_wait "Sync Completed" 240
  shot config-sync-done
  after_sync
  home
  lost_uin_start
  face_page
  replay "follow s3"
  scan
  wait_log "Capture screen result OK" 120
  logcat_save
  close_capture_dialog
  exception_photo_and_preview
  # The enrolled operator answers the prompts: before the sync packet_auth passed on the passive check alone.
  replay "follow s3"
  logcat_clear
  ui_tap "Authenticate with face"
  check A-C3 "after the sync the operator's packet authentication asks for a challenge" \
    shot_on_log "Screen state CHALLENGE liveness.challenge" config-challenge 60
  check A-C4 "packet authentication passes with the challenge answered" \
    wait_log "Face authentication packet_auth: FaceMatch\[MATCHED" 120
  check A-C5 "configuration change audited (REG-EVT-LV15)" audited REG-EVT-LV15
  sleep 2
  adb shell input swipe 640 550 640 250 400; sleep 1
  ui_tap "=AUTHENTICATE"
  check A-C6 "packet created" ui_wait "Registration Acknowledgement" 90
  logcat_save
  home
  check A-C7 "MockSync: policy.packet_auth back to the shipped value" settings restore "$CONFIG_SETTINGS"
  check A-C8 "synchronised back to the shipped policy" sync_now
  home
}

section_offline() {
  adb reverse --remove tcp:8080 >/dev/null 2>&1
  adb shell cmd connectivity airplane-mode enable
  sleep 5
  log "offline: adb reverse removed (MockSync unreachable), airplane mode on"
  app_restart
  check A-O1 "offline password sign-in" login_password "$OFFICER" "$OFFICER_PASSWORD"
  lost_uin_start
  face_page
  replay "follow s3n"
  scan
  shot_on_log "Screen state" offline-capture-badge 30
  check A-O2 "offline: capture screen shows the Offline badge" screen_has "Offline"
  check A-O3 "offline: resident face passes on the device" wait_log "Capture screen result OK" 120
  logcat_save
  close_capture_dialog
  exception_photo_and_preview
  replay s3-main
  check A-O4 "offline: packet authentication by face" packet_auth_face
  logcat_save
  app_restart
  replay "follow s3"
  check A-O5 "offline: supervisor face sign-in" login_face "$SUPERVISOR"
  open_and_approve_pending
  supervisor_auth_face a-s2-screen-replay A-O6
  check A-O7 "offline: supervisor approval by face (offline_approval.enable=Y)" \
    logcat_has "Face authentication eod_auth: FaceMatch\[MATCHED"
  sleep 5
  shot offline-approved
  ui_tap pending_approval_back_button; sleep 2
  logcat_save
  adb shell cmd connectivity airplane-mode disable
  adb reverse tcp:8080 tcp:8080 >/dev/null
  sleep 10
  log "online again: airplane mode off, adb reverse restored"
  local n
  n="$(upload_all)"
  check A-O8 "after the network is back the offline packet is uploaded ($n new)" test "${n:-0}" -ge 1
  shot offline-upload-done
}

section_latency() {
  cat "$OUT"/logcat-*.txt 2>/dev/null | grep "Liveness frame time" | sort -u > "$OUT/frame-times.txt"
  check A-P1 "per-frame times recorded" test -s "$OUT/frame-times.txt"
  python - "$(host_path "$OUT/frame-times.txt")" <<'PY' | tee -a "$OUT/demo.log"
import re, statistics, sys
rows = open(sys.argv[1], encoding="utf-8").read().splitlines()
def values(key):
    return [float(m.group(1)) for r in rows for m in [re.search(key + r"=([\d.]+)ms", r)] if m]
p50, p95, worst = values("p50"), values("p95"), values("max")
frames = sum(int(m.group(1)) for r in rows for m in [re.search(r"frames=(\d+)", r)] if m)
if p50:
    print(f"attempts={len(p50)} frames={frames} median p50={statistics.median(p50):.1f} ms "
          f"median p95={statistics.median(p95):.1f} ms worst frame={max(worst):.1f} ms")
PY
}

# ------------------------------------------------------------------------------------------- main

main() {
  log "output: $OUT"
  adb get-state >/dev/null 2>&1 || { log "no emulator or device on $ANDROID_SERIAL"; exit 1; }
  mocksync_up || { log "MockSync does not answer at $MOCKSYNC_URL"; exit 1; }
  adb reverse tcp:8080 tcp:8080 >/dev/null
  adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
  adb shell settings put secure show_ime_with_hard_keyboard 0 >/dev/null 2>&1 || true
  push_packs || { log "frame packs could not be pushed"; exit 1; }
  read_shipped
  SECTION=profile
  check A-P0 "replay profile set on MockSync and synchronised" demo_profile
  local s
  for s in $SECTIONS; do
    SECTION="$s"
    log "=== section $s"
    adb logcat -c >/dev/null 2>&1   # the previous section's lines are already in its own file
    "section_$s"
    logcat_save
  done
  SECTION=end
  # Back to the shipped policy on the tablet, then MockSync without the demo's overrides.
  settings restore "$DEMO_PROFILE"
  app_restart
  login_password "$OFFICER" "$OFFICER_PASSWORD" && sync_now || log "final synchronisation failed"
  settings forget "$DEMO_PROFILE"
  settings forget "$OVERRIDE_SETTINGS"
  settings forget "$CONFIG_SETTINGS"
  adb shell "rm -f $REPLAY_DIR/flow-source.txt"
  log "checks: $PASSED passed, $FAILED failed (results: $OUT/results.txt)"
  [ "$FAILED" -eq 0 ]
}

# Sourcing the script (". scripts/demo-android.sh --out DIR") only defines the functions.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main
fi
