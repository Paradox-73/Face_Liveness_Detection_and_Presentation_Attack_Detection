#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Starts MockSync in the foreground: HTTPS on 8443 (desktop client), HTTP on 8080
# (admin API, Android debug build). Ctrl+C stops it. State is kept in work/.
# Java: runtime/ in this folder, else $JAVA_HOME, else java on PATH (Java 11 or newer).
# Extra arguments go to Spring Boot, e.g. --mocksync.http-port=0
set -euo pipefail
cd "$(dirname "$0")"
JAVA_EXE=java
[ -n "${JAVA_HOME:-}" ] && JAVA_EXE="$JAVA_HOME/bin/java"
for candidate in runtime/bin/java runtime/bin/java.exe; do
	[ -x "$candidate" ] && JAVA_EXE="$candidate"
done
exec "$JAVA_EXE" ${MOCKSYNC_JAVA_OPTS:--Xmx512m} -jar mock-sync.jar "$@"
