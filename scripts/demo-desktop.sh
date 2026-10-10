#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Scripted end-to-end demo of the PS04 desktop flows on the real Registration Client, against MockSync (server)
# and MockMDS (devices), with the evaluation frame packs as the camera.
#
# It starts MockSync and MockMDS when they are not already running, then runs DesktopFlowDriver
# (tools/mock-sync/e2e) in the client's run folder: the driver starts the client in its own JVM, clicks through
# the flows in tools/mock-sync/e2e/flows/demo-desktop.flow, switches the MockMDS face source and faults, checks
# the texts on screen and the client database, and saves masked PNG snapshots (no faces). At the end the liveness
# evidence of every packet MockSync received is saved next to the driver log.
#
# Usage (Git Bash, repository root):
#   scripts/demo-desktop.sh [--flow FILE] [--out DIR] [--restore-golden]
#   scripts/demo-desktop.sh --save-golden        copy the client state (db/, .mosipkeys/) to the golden folder
#
# Options:
#   --flow FILE        flow file to run (default tools/mock-sync/e2e/flows/demo-desktop.flow)
#   --out DIR          output folder (default $MOSIP_RUN_ROOT/demo/desktop-<time>)
#   --restore-golden   replace the client state with the golden snapshot (and drop imported liveness models)
#                      before the run
#   --save-golden      save the client state as the golden snapshot and exit (client must be stopped)
#
# Needs: a client built with scripts/desktop-build.sh --mocksync, its machine registered in MockSync, officer1 and
# supervisor1 onboarded with face pack s3 (the golden snapshot holds such a state; it only works on the machine
# that made it, because the database key is sealed by that machine's TPM), and the frame packs in
# $MOSIP_RUN_ROOT/frame-packs (tools/eval/fetch-frame-packs.py). A JDK 11 with JavaFX and javac: JAVA11_HOME, or
# the cached Zulu 11 FX JDK that desktop-build.sh downloads.
# Environment: MOSIP_RUN_ROOT (default <repo>/../run).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_ROOT="${MOSIP_RUN_ROOT:-$(cd "$REPO/.." && pwd)/run}"
CLIENT_DIR="$RUN_ROOT/desktop"
GOLDEN="$RUN_ROOT/golden/desktop"
PACKS="$RUN_ROOT/frame-packs"
MOCKSYNC_DIR="$REPO/tools/mock-sync"
FLOW="$MOCKSYNC_DIR/e2e/flows/demo-desktop.flow"
OUT="$RUN_ROOT/demo/desktop-$(date +%Y%m%d-%H%M%S)"
ADMIN="http://127.0.0.1:8080"
ACTION=run
RESTORE=0

LOG_TAG=demo-desktop; . "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

while [ $# -gt 0 ]; do
	case "$1" in
		--flow) FLOW="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"; shift ;;
		--out) OUT="$2"; shift ;;
		--restore-golden) RESTORE=1 ;;
		--save-golden) ACTION=save ;;
		-h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"; exit 0 ;;
		*) die "unknown option $1" ;;
	esac
	shift
done

client_running() {
	[ -f "$CLIENT_DIR/client.pid" ] || return 1
	local pid; pid="$(tr -d '\r\n ' <"$CLIENT_DIR/client.pid")"
	[ -n "$pid" ] && tasklist //FI "PID eq $pid" 2>/dev/null | grep -q "$pid"
}

# The Derby database must be copied while no client has it open.
copy_state() {
	local from="$1" to="$2"
	mkdir -p "$to"
	rm -rf "$to/db" "$to/.mosipkeys"
	cp -r "$from/db" "$to/db"
	[ -d "$from/.mosipkeys" ] && cp -r "$from/.mosipkeys" "$to/.mosipkeys"
	return 0
}

if [ "$ACTION" = save ]; then
	client_running && die "stop the client first (scripts/desktop-run.ps1 -Stop)"
	copy_state "$CLIENT_DIR" "$GOLDEN"
	cp "$MOCKSYNC_DIR/work/config-overrides.properties" "$GOLDEN/mock-sync-config-overrides.properties" 2>/dev/null || true
	date -u +%Y-%m-%dT%H:%M:%SZ >"$GOLDEN/SAVED-AT.txt"
	log "golden snapshot saved in $GOLDEN"
	exit 0
fi

# ---------------------------------------------------------------------------------------------- prerequisites
[ -d "$CLIENT_DIR/lib" ] || die "no client in $CLIENT_DIR; run scripts/desktop-build.sh --mocksync"
[ -d "$PACKS/s3-main" ] || die "frame packs missing in $PACKS; run tools/eval/fetch-frame-packs.py"
client_running && die "a client started by desktop-run.ps1 is running; stop it first"

JDK=""
for candidate in "${JAVA11_HOME:-}" "$RUN_ROOT/cache/zulu11.41.23-ca-fx-jdk11.0.8-win_x64"; do
	[ -n "$candidate" ] || continue
	if [ -x "$candidate/bin/javac" ] || [ -x "$candidate/bin/javac.exe" ]; then
		JDK="$candidate"; break
	fi
done
[ -n "$JDK" ] || die "no JDK 11 with javac and JavaFX; set JAVA11_HOME"

# Low-confidence resident: pack s3 seen through a noisy camera (tools/mock-sync/e2e/NoisyPack.java).
for action in main blink smile; do
	if [ ! -d "$PACKS/s3n-$action" ]; then
		"$JDK/bin/java" "$(native "$MOCKSYNC_DIR/e2e/NoisyPack.java")" "$(native "$PACKS/s3-$action")" 			"$(native "$PACKS/s3n-$action")"
	fi
done

if [ "$RESTORE" = 1 ]; then
	[ -d "$GOLDEN/db" ] || die "no golden snapshot in $GOLDEN"
	copy_state "$GOLDEN" "$CLIENT_DIR"
	# Model bundles imported by an earlier run (flow 65) would stay active; go back to the installed models.
	rm -rf "$CLIENT_DIR/liveness-models"
	log "client state restored from $GOLDEN"
fi

if curl -s -m 3 -o /dev/null "$ADMIN/admin/config"; then
	log "MockSync already running"
else
	log "starting MockSync"
	(cd "$MOCKSYNC_DIR" && nohup bash scripts/run-mock-sync.sh >>"$MOCKSYNC_DIR/work/mock-sync.out" 2>&1 &)
	for _ in $(seq 1 120); do curl -s -m 2 -o /dev/null "$ADMIN/admin/config" && break; sleep 1; done
	curl -s -m 3 -o /dev/null "$ADMIN/admin/config" || die "MockSync did not start; see $MOCKSYNC_DIR/work/mock-sync.out"
fi
bash "$REPO/scripts/mockmds-run.sh" --frames "$PACKS"

STORE="$MOCKSYNC_DIR/work/keys/client-truststore.p12"
[ -f "$STORE" ] || bash "$MOCKSYNC_DIR/scripts/trust-mock-sync.sh" --truststore "$STORE"
# Initialization.main reads lib/logback.xml relative to the working directory (logs/registration.log).
[ -f "$CLIENT_DIR/lib/logback.xml" ] || cp "$REPO/clients/desktop/registration/lib/logback.xml" "$CLIENT_DIR/lib/"

# ---------------------------------------------------------------------------------------------- run
mkdir -p "$OUT"
# The driver is several source files, so it is compiled first (single-file source mode cannot see the others).
DRIVER_SRC="$MOCKSYNC_DIR/e2e"
log "compiling the flow driver"
DRIVER_SRCS=()
for f in DesktopFlowDriver FlowContext FlowDb FlowHttp FlowInterpreter FlowWatcher FxNodes PromptFollower; do
	DRIVER_SRCS+=("$(native "$DRIVER_SRC/$f.java")")
done
(cd "$CLIENT_DIR" && "$JDK/bin/javac" -encoding UTF-8 -cp "lib/*" -d "$(native "$OUT/driver-classes")" "${DRIVER_SRCS[@]}") \
	|| die "the flow driver did not compile"
log "running $(basename "$FLOW"); output in $OUT"
set +e
(cd "$CLIENT_DIR" && "$JDK/bin/java" -Xmx1400m -Dfile.encoding=UTF-8 \
	-Dframe.packs="$(native "$PACKS")" \
	-Djavax.net.ssl.trustStore="$(native "$STORE")" -Djavax.net.ssl.trustStorePassword=changeit \
	-Djavax.net.ssl.trustStoreType=PKCS12 \
	-cp "lib/*;$(native "$OUT/driver-classes")" DesktopFlowDriver "$(native "$OUT")" "$(native "$FLOW")" \
	>"$OUT/stdout.log" 2>&1)
status=$?
set -e

# ---------------------------------------------------------------------------------------------- evidence
mkdir -p "$OUT/packets"
curl -s -m 10 "$ADMIN/admin/packets" >"$OUT/packets/list.json" || true
if command -v python >/dev/null 2>&1; then
	for name in $(python -c "import json,sys; print(' '.join(p['name'] for p in json.load(open(sys.argv[1]))))" \
		"$(native "$OUT/packets/list.json")" 2>/dev/null); do
		curl -s -m 20 "$ADMIN/admin/packets/$name/liveness" >"$OUT/packets/${name%.zip}.liveness.json" || true
	done
fi

grep -E "RESULT|^.{9}====" "$OUT/driver.log" >"$OUT/results.txt" || true
passed="$(grep -c "RESULT PASS" "$OUT/driver.log" || true)"
failed="$(grep -c "RESULT FAIL" "$OUT/driver.log" || true)"
log "checks passed: $passed, failed: $failed (driver exit $status); summary in $OUT/results.txt"
exit "$status"
