# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# UI helpers for driving the Android Registration Client (ARC) on an emulator or device through adb.
# Source this file; it defines functions only. Used by scripts/demo-android.sh.
#
# The helpers read the screen through `uiautomator dump`. ARC's Flutter screens expose their labels as
# content descriptions and the native liveness screen as text, so every lookup matches either.
#
# Functions:
#   ui_dump                     save the current view tree to $ARC_UI_XML
#   ui_nodes                    print "desc | text | bounds" for every labelled node
#   ui_find NEEDLE [N]          print the centre "x y" of the N-th (default 1) node whose label contains NEEDLE;
#                               NEEDLE "=Label" matches the whole label only
#   ui_has NEEDLE               exit status 0 when a node's label contains NEEDLE (uses the last dump)
#   ui_tap NEEDLE [N]           dump, find and tap; exit status 1 when not found
#   ui_wait NEEDLE [SECONDS]    dump until NEEDLE shows (default 30 s); exit status 1 on timeout
#   ui_wait_any SECONDS N1 N2.. dump until one of the needles shows; prints the one found
#   ui_tap_field N              tap the N-th text field (1-based) of the screen
#   ui_type TEXT                type TEXT into the focused field (no spaces; adb input limitation)
#   ui_fill N TEXT [secret]     clear the N-th text field, type TEXT and check what arrived (the length only
#                               for a password field); retries twice, because adb input can drop characters
#   ui_text                     print every label of the last dump on one line (for logs and checks)
#   ui_shot FILE                save a PNG screenshot
#   ui_back                     press Back
#
# Environment:
#   ANDROID_SERIAL   adb device (default: the only one)
#   ARC_UI_XML       where dumps are kept (default: a file in ${TMPDIR:-/tmp})
#   PYTHON           python 3 interpreter (default: python3, then python)

ARC_UI_XML="${ARC_UI_XML:-${TMPDIR:-/tmp}/arc-ui-$$.xml}"
if [ -z "${PYTHON:-}" ]; then
  if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then PYTHON=python3; else PYTHON=python; fi
fi
export PYTHONIOENCODING=utf-8

# Python helper: reads the dump and answers one query. Kept inline so the script has no extra files.
_ui_py() {
  "$PYTHON" - "$@" <<'PY'
import re, sys
mode, path = sys.argv[1], sys.argv[2]
try:
    xml = open(path, encoding="utf-8", errors="replace").read()
except OSError:
    xml = ""
nodes = []
for m in re.finditer(r"<node [^>]*>", xml):
    n = m.group(0)
    def attr(name):
        a = re.search(r"\s" + name + r'="([^"]*)"', n)
        return a.group(1) if a else ""
    b = [int(v) for v in re.findall(r"\d+", attr("bounds"))] or [0, 0, 0, 0]
    label = (attr("content-desc") + " " + attr("text")).strip()
    label = label.replace("&#10;", " ").replace("&amp;", "&").replace("&quot;", '"').replace("&apos;", "'")
    nodes.append((label, attr("class"), b))
if mode == "nodes":
    for label, cls, b in nodes:
        if label:
            print(f"{label} | {cls.split('.')[-1]} | {b}")
elif mode == "text":
    print(" / ".join(l for l, _, _ in nodes if l))
elif mode == "find":
    needle, index = sys.argv[3], int(sys.argv[4])
    exact = needle.startswith("=")
    needle = needle[1:] if exact else needle
    hits = [b for label, _, b in nodes if (label == needle if exact else needle in label)]
    if len(hits) >= index:
        x1, y1, x2, y2 = hits[index - 1]
        print((x1 + x2) // 2, (y1 + y2) // 2)
elif mode == "field":
    index = int(sys.argv[3])
    hits = [b for _, cls, b in nodes if cls == "android.widget.EditText"]
    if len(hits) >= index:
        x1, y1, x2, y2 = hits[index - 1]
        print((x1 + x2) // 2, (y1 + y2) // 2)
elif mode == "fieldtext":
    index = int(sys.argv[3])
    hits = [label for label, cls, _ in nodes if cls == "android.widget.EditText"]
    if len(hits) >= index:
        print(hits[index - 1])
PY
}

_ui_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

ui_dump() {
  local i
  for i in 1 2 3; do
    if MSYS_NO_PATHCONV=1 adb shell uiautomator dump /sdcard/arc-ui.xml >/dev/null 2>&1; then
      MSYS_NO_PATHCONV=1 adb exec-out cat /sdcard/arc-ui.xml > "$ARC_UI_XML" 2>/dev/null && return 0
    fi
    sleep 1
  done
  : > "$ARC_UI_XML"
  return 1
}

ui_nodes() { ui_dump; _ui_py nodes "$(_ui_path "$ARC_UI_XML")"; }
ui_text() { _ui_py text "$(_ui_path "$ARC_UI_XML")"; }
ui_find() { _ui_py find "$(_ui_path "$ARC_UI_XML")" "$1" "${2:-1}"; }
ui_has() { [ -n "$(ui_find "$1")" ]; }

ui_tap() {
  local c
  ui_dump
  c="$(ui_find "$1" "${2:-1}")"
  [ -n "$c" ] || return 1
  # shellcheck disable=SC2086
  MSYS_NO_PATHCONV=1 adb shell input tap $c
}

ui_wait() {
  local needle="$1" timeout="${2:-30}" start
  start=$(date +%s)
  while :; do
    ui_dump
    ui_has "$needle" && return 0
    [ $(( $(date +%s) - start )) -lt "$timeout" ] || return 1
    sleep 1
  done
}

ui_wait_any() {
  local timeout="$1" start n
  shift
  start=$(date +%s)
  while :; do
    ui_dump
    for n in "$@"; do
      if ui_has "$n"; then printf '%s\n' "$n"; return 0; fi
    done
    [ $(( $(date +%s) - start )) -lt "$timeout" ] || return 1
    sleep 1
  done
}

ui_tap_field() {
  local c
  ui_dump
  c="$(_ui_py field "$(_ui_path "$ARC_UI_XML")" "${1:-1}")"
  [ -n "$c" ] || return 1
  # shellcheck disable=SC2086
  MSYS_NO_PATHCONV=1 adb shell input tap $c
}

ui_fill() {
  local n="$1" text="$2" secret="${3:-}" try got
  for try in 1 2 3; do
    ui_tap_field "$n" || return 1
    MSYS_NO_PATHCONV=1 adb shell input keyevent KEYCODE_MOVE_END
    MSYS_NO_PATHCONV=1 adb shell input keyevent $(printf 'KEYCODE_DEL %.0s' $(seq 1 30))
    ui_type "$text"
    sleep 1
    ui_dump
    got="$(_ui_py fieldtext "$(_ui_path "$ARC_UI_XML")" "$n")"
    if [ -n "$secret" ]; then
      [ "${#got}" -eq "${#text}" ] && return 0
    else
      [ "$got" = "$text" ] && return 0
    fi
  done
  return 1
}

ui_type() { MSYS_NO_PATHCONV=1 adb shell input text "'$1'"; }
ui_back() { MSYS_NO_PATHCONV=1 adb shell input keyevent KEYCODE_BACK; }
ui_shot() { mkdir -p "$(dirname "$1")"; MSYS_NO_PATHCONV=1 adb exec-out screencap -p > "$1"; }
