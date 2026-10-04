#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Change config values served by a running MockSync. Clients receive them on their next config sync
# (desktop: Sync Data from the menu, or the scheduled sync job).
#
# usage: scripts/set-config.sh key=value [key=value ...]     set overrides
#        scripts/set-config.sh --unset key [key ...]          go back to the reference value
#        scripts/set-config.sh --show                         list overrides and effective liveness keys
# Environment: MOCKSYNC_ADMIN_URL (default http://127.0.0.1:8080).
set -euo pipefail
ADMIN="${MOCKSYNC_ADMIN_URL:-http://127.0.0.1:8080}"
json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

case "${1:-}" in
"" | --show)
	curl -sS -f "$ADMIN/admin/config"
	;;
--unset)
	shift
	for key in "$@"; do
		curl -sS -o /dev/null -w "$key -> %{http_code}\n" -X DELETE "$ADMIN/admin/config/$key"
	done
	;;
*)
	body="{"
	for pair in "$@"; do
		key="${pair%%=*}"
		value="${pair#*=}"
		[ "$body" = "{" ] || body="$body,"
		body="$body\"$(json_escape "$key")\":\"$(json_escape "$value")\""
	done
	curl -sS -f -X PUT -H "Content-Type: application/json" --data-binary "$body}" "$ADMIN/admin/config"
	;;
esac
echo
