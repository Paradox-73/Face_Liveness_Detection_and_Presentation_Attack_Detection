#!/usr/bin/env bash
# Starts MockMDS (face, finger and iris SBI 0.9.5 device simulator) in the foreground.
# It listens on 127.0.0.1, first free port from 4501. Ctrl+C stops it.
# Settings: application.properties in this folder (face frame source, faults, certification level).
# Java: runtime/ in this folder, else $JAVA_HOME, else java on PATH (Java 21 or newer).
# MOCKMDS_PURPOSE=Auth starts an authentication device instead of a registration device.
set -euo pipefail
cd "$(dirname "$0")"
JAVA_EXE=java
[ -n "${JAVA_HOME:-}" ] && JAVA_EXE="$JAVA_HOME/bin/java"
for candidate in runtime/bin/java runtime/bin/java.exe; do
	[ -x "$candidate" ] && JAVA_EXE="$candidate"
done
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac
exec "$JAVA_EXE" ${MOCKMDS_JAVA_OPTS:--Xmx256m} -cp "mock-mds.jar${SEP}lib/*" io.mosip.mock.sbi.test.TestMockSBI \
	"mosip.mock.sbi.device.purpose=${MOCKMDS_PURPOSE:-Registration}" "mosip.mock.sbi.biometric.type=Biometric Device"
