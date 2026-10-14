#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Register a desktop Registration Client machine in a running MockSync.
#
# usage: scripts/register-machine.sh <client-dir> [client-lib-dir]
#   <client-dir>      the client's working directory (where .mosipkeys/ and db/ are created on first launch)
#   [client-lib-dir]  the client's jar directory; default <client-dir>/lib
#
# With the client's jars available, MachineKeyTool.java asks keymanager for the machine keys (TPM or local, the
# same choice the client makes). Without them, .mosipkeys/readme.txt is used, which exists only for local keys.
# Environment: MOCKSYNC_ADMIN_URL (default http://127.0.0.1:8080).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CLIENT_DIR="${1:?usage: register-machine.sh <client-dir> [client-lib-dir]}"
LIB_DIR="${2:-$CLIENT_DIR/lib}"
ADMIN="${MOCKSYNC_ADMIN_URL:-http://127.0.0.1:8080}"
to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

if ls "$LIB_DIR"/*.jar >/dev/null 2>&1; then
	OUT="$(mktemp)"
	(cd "$CLIENT_DIR" && java -cp "$(to_native "$LIB_DIR")/*" "$(to_native "$HERE/MachineKeyTool.java")" \
		"$(to_native "$OUT")" > /dev/null 2>&1)
	echo "Machine keys: $(cat "$OUT")"
	curl -sS -f -X POST -H "Content-Type: application/json" --data-binary "@$OUT" "$ADMIN/admin/machines"
	rm -f "$OUT"
elif [ -f "$CLIENT_DIR/.mosipkeys/readme.txt" ]; then
	curl -sS -f -X POST -H "Content-Type: text/plain" --data-binary "@$CLIENT_DIR/.mosipkeys/readme.txt" \
		"$ADMIN/admin/machines/readme"
else
	echo "No client jars in $LIB_DIR and no $CLIENT_DIR/.mosipkeys/readme.txt; launch the client once first." >&2
	exit 1
fi
echo
