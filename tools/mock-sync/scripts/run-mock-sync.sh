#!/usr/bin/env bash
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
