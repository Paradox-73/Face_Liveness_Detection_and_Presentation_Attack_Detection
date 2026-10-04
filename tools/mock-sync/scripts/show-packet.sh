#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Show the face liveness evidence of a registration packet the desktop client uploaded to MockSync:
# LIVENESS_* entries of every face record (resident, officer, supervisor), the liveness fields of
# packet_meta_info.json and the REG-EVT-LV* rows of audit.json. MockSync decrypts the packet with the machine's
# REGISTRATION key; no biometric data is printed.
#
# usage: scripts/show-packet.sh              list uploaded packets
#        scripts/show-packet.sh latest       evidence of the newest packet
#        scripts/show-packet.sh <file.zip>   evidence of one packet (name as listed)
# Environment: MOCKSYNC_ADMIN_URL (default http://127.0.0.1:8080).
set -euo pipefail
ADMIN="${MOCKSYNC_ADMIN_URL:-http://127.0.0.1:8080}"
pretty() { if command -v python >/dev/null 2>&1; then python -m json.tool; else cat; fi; }

if [ $# -eq 0 ]; then
	curl -sS -f "$ADMIN/admin/packets" | pretty
else
	curl -sS -f "$ADMIN/admin/packets/$1/liveness" | pretty
fi
