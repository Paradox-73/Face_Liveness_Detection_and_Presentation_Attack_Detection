#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Starts or stops MockMDS (devices/mock-mds) as the face, finger and iris device for the desktop Registration Client,
# from a run folder outside the git tree, with the evaluation frame packs allowed as face frame sources.
#
#   <run-root>/mockmds/application.properties   copy of the built one, frame-pack root added to allowed.roots
#   <run-root>/mockmds/Biometric Devices/        keys, device descriptors and stock images (copied from target/)
#   <run-root>/mockmds/Profile/                  stock finger/iris/face profiles
#   <run-root>/mockmds/mockmds.log, mockmds.pid
#
# MockMDS reads ./application.properties and "./Biometric Devices" from its working directory, so it always runs
# from that folder. It listens on 127.0.0.1, first free port from 4501.
#
# Usage (Git Bash):
#   scripts/mockmds-run.sh [--frames DIR] [--build]   start (does nothing if port 4501 already answers)
#   scripts/mockmds-run.sh --stop                     stop the MockMDS this script started
#   scripts/mockmds-run.sh --status                   print the admin state
#
# Options:
#   --frames DIR   folder that holds the frame packs (default: $MOSIP_RUN_ROOT/frame-packs)
#   --build        rebuild devices/mock-mds first (Java 21, about 2 minutes with its tests)
#
# Environment: MOSIP_RUN_ROOT (default <repo>/../run), JAVA21_HOME (MockMDS needs Java 21).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MDS_SRC="$REPO_ROOT/devices/mock-mds"
RUN_ROOT="${MOSIP_RUN_ROOT:-$(cd "$REPO_ROOT/.." && pwd)/run}"
RUN_DIR="$RUN_ROOT/mockmds"
FRAMES="$RUN_ROOT/frame-packs"
PORT=4501
ACTION=start
BUILD=0

log() { echo "[mockmds-run $(date +%H:%M:%S)] $*"; }
die() { echo "[mockmds-run] ERROR: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
	case "$1" in
		--frames) FRAMES="$2"; shift ;;
		--build) BUILD=1 ;;
		--stop) ACTION=stop ;;
		--status) ACTION=status ;;
		-h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"; exit 0 ;;
		*) die "unknown option $1" ;;
	esac
	shift
done

to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
answering() { curl -s -m 3 "http://127.0.0.1:$PORT/admin/liveness" >/dev/null 2>&1; }

if [ "$ACTION" = status ]; then
	curl -s -m 3 "http://127.0.0.1:$PORT/admin/liveness" || die "MockMDS is not answering on $PORT"
	echo
	exit 0
fi

if [ "$ACTION" = stop ]; then
	[ -f "$RUN_DIR/mockmds.pid" ] || { log "no pid file; nothing to stop"; exit 0; }
	pid="$(cat "$RUN_DIR/mockmds.pid")"
	if command -v taskkill >/dev/null 2>&1; then
		taskkill //PID "$pid" //F >/dev/null 2>&1 || true
	else
		kill "$pid" 2>/dev/null || true
	fi
	rm -f "$RUN_DIR/mockmds.pid"
	log "stopped MockMDS (pid $pid)"
	exit 0
fi

if answering; then
	log "MockMDS already answers on port $PORT; not starting another one"
	exit 0
fi

JAVA21="${JAVA21_HOME:-}"
[ -n "$JAVA21" ] && [ -x "$JAVA21/bin/java" -o -x "$JAVA21/bin/java.exe" ] || die "set JAVA21_HOME to a Java 21 JDK"

if [ "$BUILD" = 1 ] || [ ! -f "$MDS_SRC/target/mock-mds-1.3.1.jar" ]; then
	log "building MockMDS"
	(cd "$MDS_SRC" && JAVA_HOME="$JAVA21" PATH="$JAVA21/bin:$PATH" mvn -q -B package -Dgpg.skip=true)
fi

mkdir -p "$RUN_DIR"
rm -rf "$RUN_DIR/Biometric Devices" "$RUN_DIR/Profile"
cp -r "$MDS_SRC/Biometric Devices" "$RUN_DIR/"
cp -r "$MDS_SRC/target/Profile" "$RUN_DIR/"
# Same properties as the build, with the frame-pack folder added to the allowed face source roots.
sed "s#^mosip.mock.sbi.face.source.allowed.roots=.*#mosip.mock.sbi.face.source.allowed.roots=$(to_native "$FRAMES");.#" \
	"$MDS_SRC/target/application.properties" >"$RUN_DIR/application.properties"

CP="$(to_native "$MDS_SRC/target/mock-mds-1.3.1.jar");$(to_native "$MDS_SRC/target/lib")/*"
[ -n "$(command -v cygpath || true)" ] || CP="${CP//;/:}"
log "starting MockMDS from $RUN_DIR (frame packs: $FRAMES)"
cd "$RUN_DIR"
"$JAVA21/bin/java" -Xmx256m -cp "$CP" io.mosip.mock.sbi.test.TestMockSBI \
	"mosip.mock.sbi.device.purpose=Registration" "mosip.mock.sbi.biometric.type=Biometric Device" \
	>"$RUN_DIR/mockmds.log" 2>&1 &
echo $! >"$RUN_DIR/mockmds.pid"
# $! is the MSYS pid; keep the Windows pid for taskkill.
if command -v ps >/dev/null 2>&1 && ps -W >/dev/null 2>&1; then
	winpid="$(ps -p "$(cat "$RUN_DIR/mockmds.pid")" -o winpid= 2>/dev/null | tr -d ' ' || true)"
	[ -n "$winpid" ] && echo "$winpid" >"$RUN_DIR/mockmds.pid"
fi

for _ in $(seq 1 60); do
	if answering; then
		log "MockMDS answers on 127.0.0.1:$PORT (pid $(cat "$RUN_DIR/mockmds.pid"))"
		exit 0
	fi
	sleep 1
done
die "MockMDS did not answer on port $PORT within 60 s; see $RUN_DIR/mockmds.log"
