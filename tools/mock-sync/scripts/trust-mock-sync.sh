#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Make a Java runtime trust MockSync's HTTPS certificate (work/keys/tls-cert.pem, created on MockSync's first start).
#
# usage: scripts/trust-mock-sync.sh --truststore <file.p12>   write a trust store that holds only this certificate;
#                                                             run the client with
#                                                             -Djavax.net.ssl.trustStore=<file.p12>
#                                                             -Djavax.net.ssl.trustStorePassword=changeit
#        scripts/trust-mock-sync.sh --jre <java home>         import it into <java home>/lib/security/cacerts
#                                                             (the JRE bundled with the client zip)
# Environment: MOCKSYNC_WORK_DIR (default tools/mock-sync/work).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
CERT="${MOCKSYNC_WORK_DIR:-$HERE/work}/keys/tls-cert.pem"
[ -f "$CERT" ] || { echo "$CERT not found; start MockSync once first." >&2; exit 1; }
to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

pick_keytool() {
	for candidate in "$1/bin/keytool" "$1/bin/keytool.exe" "$(command -v keytool || true)" "${JAVA_HOME:-}/bin/keytool"; do
		if [ -n "$candidate" ] && [ -x "$candidate" ]; then echo "$candidate"; return; fi
	done
	echo "keytool not found (the client JRE, PATH and JAVA_HOME were checked)" >&2
	exit 1
}

case "${1:-}" in
--truststore)
	STORE="${2:?truststore file required}"
	KEYTOOL="$(pick_keytool "${JAVA_HOME:-}")"
	"$KEYTOOL" -importcert -noprompt -alias mocksync -file "$(to_native "$CERT")" -keystore "$(to_native "$STORE")" \
		-storetype PKCS12 -storepass changeit
	;;
--jre)
	JRE="${2:?java home required}"
	KEYTOOL="$(pick_keytool "$JRE")"
	"$KEYTOOL" -delete -noprompt -alias mocksync -keystore "$(to_native "$JRE/lib/security/cacerts")" \
		-storepass changeit >/dev/null 2>&1 || true
	"$KEYTOOL" -importcert -noprompt -alias mocksync -file "$(to_native "$CERT")" \
		-keystore "$(to_native "$JRE/lib/security/cacerts")" -storepass changeit
	;;
*)
	echo "usage: $0 --truststore <file.p12> | --jre <java home>" >&2
	exit 2
	;;
esac
