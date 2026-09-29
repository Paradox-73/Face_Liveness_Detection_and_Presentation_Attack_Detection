#!/usr/bin/env bash
# Provisions the built-in face camera of the Registration Client debug build on
# an attached emulator or device with a DEVICE certificate issued by MockSync's
# DEVICE CA, so the client's unchanged SBI trust check accepts its captures.
#
# Trust model (see AndroidKeystoreSigner): without this step the app signs with
# a self-signed key; liveness works, but a resident face capture is rejected by
# the DEVICE trust check. With it, the app imports the key into the Android
# Keystore at the next capture and deletes the file.
#
# Steps:
#   1. Reads the CA key and certificate from MockSync's keys folder
#      (tools/mock-sync/work/keys/device-ca.p12, created when MockSync first runs).
#   2. Creates a new RSA 2048 device key and a certificate for it signed by that
#      CA (CN = the given serial, 1 year).
#   3. Packs both into a PKCS#12 file with a random password and copies the file
#      and the password into the app's private folder files/liveness/ with
#      'adb shell run-as' (works for debuggable builds only).
#
# This is for demos against MockSync. In production the device key is issued
# by the device provider through MOSIP partner management; the CA key used here
# never leaves the developer's machine and nothing is committed.
#
# Usage (Git Bash on Windows, or bash on Linux/macOS; needs openssl and adb):
#   scripts/android-liveness-device-cert.sh
#   scripts/android-liveness-device-cert.sh --serial BUILTIN-DEMO
#   scripts/android-liveness-device-cert.sh --ca-p12 PATH --ca-pass PASSWORD
set -euo pipefail
# Git Bash would rewrite device paths such as /data/local/tmp into Windows paths; local paths are converted
# explicitly with native_path instead.
export MSYS_NO_PATHCONV=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="io.mosip.registration_client"
CA_P12="$REPO_ROOT/tools/mock-sync/work/keys/device-ca.p12"
CA_PASS="mocksync"
SERIAL="BUILTIN-FACE"

log() { printf '[device-cert] %s\n' "$*"; }
die() { printf '[device-cert] ERROR: %s\n' "$*" >&2; exit 1; }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="${2:?}"; shift 2 ;;
    --ca-p12) CA_P12="${2:?}"; shift 2 ;;
    --ca-pass) CA_PASS="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,27p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

command -v openssl >/dev/null 2>&1 || die "openssl is not on PATH"
ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
[ -x "$ADB" ] || [ -x "$ADB.exe" ] || ADB="$(command -v adb || true)"
[ -n "$ADB" ] || die "adb not found (set ANDROID_HOME)"
[ -f "$CA_P12" ] || die "no DEVICE CA at $CA_P12 (start MockSync once: tools/mock-sync/scripts/run-mock-sync.sh)"
"$ADB" get-state >/dev/null 2>&1 || die "no device attached"

CA_P12="$(native_path "$CA_P12")"
work="$(native_path "$(mktemp -d)")"
trap 'rm -rf "$work"' EXIT

log "issuing a device certificate for CN=$SERIAL from $(basename "$CA_P12")"
# Java 11 writes PKCS#12 files with RC2/3DES, which OpenSSL 3 reads only with -legacy.
read_ca() {
  openssl pkcs12 -in "$CA_P12" -passin "pass:$CA_PASS" "$@" 2>/dev/null \
    || openssl pkcs12 -legacy -in "$CA_P12" -passin "pass:$CA_PASS" "$@" 2>/dev/null
}
read_ca -nocerts -nodes -out "$work/ca.key" || die "cannot read the CA key from $CA_P12"
read_ca -clcerts -nokeys -out "$work/ca.crt" || die "cannot read the CA certificate from $CA_P12"
printf '[req]\nprompt=no\ndistinguished_name=dn\n[dn]\nCN=%s\nOU=Built-in face camera\nO=MOSIP Registration Client\n' \
  "$SERIAL" > "$work/req.cnf"
openssl req -new -newkey rsa:2048 -nodes -config "$work/req.cnf" -keyout "$work/device.key" \
  -out "$work/device.csr" 2>/dev/null || die "cannot create the device key"
printf 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\n' > "$work/ext.cnf"
openssl x509 -req -in "$work/device.csr" -CA "$work/ca.crt" -CAkey "$work/ca.key" -CAcreateserial \
  -days 365 -sha256 -extfile "$work/ext.cnf" -out "$work/device.crt" 2>/dev/null \
  || die "cannot issue the certificate"
openssl verify -CAfile "$work/ca.crt" "$work/device.crt" >/dev/null || die "issued certificate does not verify"

password="$(openssl rand -hex 16)"
printf '%s' "$password" > "$work/device.p12.pass"
# SHA-1/3DES protection: readable by every Android version's PKCS12 key store.
openssl pkcs12 -export -inkey "$work/device.key" -in "$work/device.crt" -certfile "$work/ca.crt" \
  -name device -passout "pass:$password" -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1 \
  -out "$work/device.p12"

tmp="/data/local/tmp/liveness-device"
"$ADB" shell "rm -rf $tmp && mkdir -p $tmp"
"$ADB" push "$work/device.p12" "$work/device.p12.pass" "$tmp/" >/dev/null 2>&1
"$ADB" shell "chmod 644 $tmp/* && run-as $PACKAGE sh -c 'mkdir -p files/liveness && cp $tmp/device.p12 $tmp/device.p12.pass files/liveness/ && chmod 700 files/liveness && chmod 600 files/liveness/*' && rm -rf $tmp" \
  || die "copy into the app folder failed (is $PACKAGE a debug build?)"
log "device key waiting in files/liveness/ of $PACKAGE; the app imports it at the next capture"
