#!/usr/bin/env bash
# Test T-SYNC-03: first login and initial sync of the real desktop Registration Client against a running MockSync.
#
# usage: e2e/run-desktop-e2e.sh <client-run-dir> <out-dir> [userId] [password] [seconds]
#   <client-run-dir>  empty or existing client working directory (db/, .mosipkeys/, logs/ are created there)
#   <out-dir>         driver.log and PNG snapshots
# Environment:
#   CLIENT_TARGET  client build output with registration-client-*.jar and lib/
#                  (default clients/desktop/registration/registration-client/target)
#   EXTRA_LIB      optional directory of extra jars, e.g. the MOSIP mock SDK (io.mosip.mock.sdk:mock-sdk:1.1.5) that
#                  configure.sh normally copies into lib/
#   MOCKSYNC_WORK_DIR  MockSync work directory (default tools/mock-sync/work)
# Needs a JDK 11 with JavaFX (Zulu 11 FX, as the client uses) on PATH: the driver runs as a single-file program.
# The client must be built with -Pmocksync (mosip.hostname=localhost:8443) and its machine registered in MockSync
# (scripts/register-machine.sh) before the first login.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
RUN_DIR="${1:?usage: run-desktop-e2e.sh <client-run-dir> <out-dir> [userId] [password] [seconds]}"
OUT_DIR="${2:?out-dir required}"
USER_ID="${3:-officer1}"
PASSWORD="${4:-Mosip@Officer1}"
SECONDS_TO_RUN="${5:-300}"
TARGET="${CLIENT_TARGET:-$REPO/clients/desktop/registration/registration-client/target}"
WORK="${MOCKSYNC_WORK_DIR:-$HERE/work}"
to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }
SEP=":"; if command -v cygpath >/dev/null 2>&1; then SEP=";"; fi

CLIENT_JAR="$(ls "$TARGET"/registration-client-*.jar | grep -v -e sources -e javadoc | head -1)"
STORE="$WORK/keys/client-truststore.p12"
[ -f "$STORE" ] || "$HERE/scripts/trust-mock-sync.sh" --truststore "$STORE"

mkdir -p "$RUN_DIR/lib" "$OUT_DIR"
# Initialization.main points logback at lib/logback.xml in the working directory (logs/registration.log).
cp "$REPO/clients/desktop/registration/lib/logback.xml" "$RUN_DIR/lib/logback.xml"
CP="$(to_native "$CLIENT_JAR")$SEP$(to_native "$TARGET/lib")/*"
if [ -n "${EXTRA_LIB:-}" ]; then CP="$CP$SEP$(to_native "$EXTRA_LIB")/*"; fi

cd "$RUN_DIR"
exec java -Xmx1536m -Dfile.encoding=UTF-8 \
	-Djavax.net.ssl.trustStore="$(to_native "$STORE")" -Djavax.net.ssl.trustStorePassword=changeit \
	-Djavax.net.ssl.trustStoreType=PKCS12 \
	-cp "$CP" "$(to_native "$HERE/e2e/DesktopLoginDriver.java")" "$USER_ID" "$PASSWORD" "$(to_native "$OUT_DIR")" \
	"$SECONDS_TO_RUN"
