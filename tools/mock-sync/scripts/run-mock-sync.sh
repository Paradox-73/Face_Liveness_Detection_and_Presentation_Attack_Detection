#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Start MockSync: HTTPS on 8443 (desktop client), HTTP on 8080 (Android debug build).
# Builds target/mock-sync.jar first when it is missing, or always with REBUILD=1.
# Extra arguments go to Spring Boot, e.g.  scripts/run-mock-sync.sh --mocksync.center-id=10002
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"
if [ ! -f target/mock-sync.jar ] || [ "${REBUILD:-0}" = "1" ]; then
	mvn -q -B package -DskipTests
fi
exec java ${JAVA_OPTS:--Xmx512m} -jar target/mock-sync.jar "$@"
