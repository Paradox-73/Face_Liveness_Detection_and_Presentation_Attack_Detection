#!/usr/bin/env bash
# Provisions the built-in face camera of the Registration Client on an attached
# emulator or device with a DEVICE certificate issued by MockSync's DEVICE CA,
# so the client's unchanged SBI trust check accepts its captures.
#
# Trust model (see AndroidKeystoreSigner): without this step the app signs with
# a self-signed key; liveness works, but a resident face capture is rejected by
# the DEVICE trust check.
#
# --csr (release and debug builds; the private key never leaves the phone):
#   1. Starts the app's provisioning step over adb (DeviceProvisioningActivity):
#      the app creates its device key in the Android Keystore when missing and
#      writes a PKCS#10 certificate request to its app-specific external files
#      folder, Android/data/io.mosip.registration_client/files/liveness/.
#   2. Pulls device.csr, checks its signature, and issues a certificate for it
#      from MockSync's DEVICE CA (tools/mock-sync/work/keys/device-ca.p12,
#      created when MockSync first runs; 1 year, the request's subject).
#   3. Pushes the chain (device certificate, CA certificate) back as
#      device-chain.pem and starts the provisioning step again: the app checks
#      that the certificate holds its Keystore key and comes from the CA in the
#      chain (once provisioned: the same CA as before, unless --replace-ca),
#      installs it, and writes status.txt.
#   4. Reads status.txt and prints the subject and issuer of the device
#      certificate the app now uses.
#
# Without --csr (debug builds only; the older path):
#   Creates a new RSA 2048 device key and a certificate for it on this PC (CN =
#   --serial), packs both into a PKCS#12 file with a random password and copies
#   it into the app's private folder files/liveness/ with 'adb shell run-as'.
#   The app imports the key into the Android Keystore at the next capture and
#   deletes the file.
#
# This is for demos against MockSync. In production the device certificate is
# issued by the device provider through MOSIP partner management; the CA key
# used here never leaves the developer's machine and nothing is committed.
#
# Usage (Git Bash on Windows, or bash on Linux/macOS; needs openssl and adb):
#   scripts/android-liveness-device-cert.sh --csr
#   scripts/android-liveness-device-cert.sh --csr --replace-ca
#   scripts/android-liveness-device-cert.sh
#   scripts/android-liveness-device-cert.sh --serial BUILTIN-DEMO
#   scripts/android-liveness-device-cert.sh --ca-p12 PATH --ca-pass PASSWORD
#
# Options:
#   --csr            in-app key and certificate request (works on release builds)
#   --replace-ca     with --csr: accept a CA other than the one of the current
#                    device certificate (for example after MockSync's keys were
#                    created again)
#   --serial S       without --csr: common name of the new certificate
#   --ca-p12 PATH    PKCS#12 file with the CA key and certificate
#   --ca-pass PASS   its password (default mocksync)
#   --package NAME   application ID (default io.mosip.registration_client)
#   --timeout S      seconds to wait for the app's status (default 120; the
#                    first step waits for the app to start)
set -euo pipefail
# Git Bash would rewrite device paths such as /data/local/tmp into Windows paths; local paths are converted
# explicitly with native_path instead.
export MSYS_NO_PATHCONV=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="io.mosip.registration_client"
PROVISIONING_ACTIVITY="io.mosip.registration.livenessmanager.device.DeviceProvisioningActivity"
CA_P12="$REPO_ROOT/tools/mock-sync/work/keys/device-ca.p12"
CA_PASS="mocksync"
SERIAL="BUILTIN-FACE"
CSR=0
REPLACE_CA=false
TIMEOUT=120

log() { printf '[device-cert] %s\n' "$*"; }
die() { printf '[device-cert] ERROR: %s\n' "$*" >&2; exit 1; }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

while [ $# -gt 0 ]; do
  case "$1" in
    --csr) CSR=1; shift ;;
    --replace-ca) REPLACE_CA=true; shift ;;
    --serial) SERIAL="${2:?}"; shift 2 ;;
    --ca-p12) CA_P12="${2:?}"; shift 2 ;;
    --ca-pass) CA_PASS="${2:?}"; shift 2 ;;
    --package) PACKAGE="${2:?}"; shift 2 ;;
    --timeout) TIMEOUT="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,53p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done
[ "$REPLACE_CA" = "false" ] || [ "$CSR" = "1" ] || die "--replace-ca needs --csr"

command -v openssl >/dev/null 2>&1 || die "openssl is not on PATH"
ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
[ -x "$ADB" ] || [ -x "$ADB.exe" ] || ADB="$(command -v adb || true)"
[ -n "$ADB" ] || die "adb not found (set ANDROID_HOME)"
[ -f "$CA_P12" ] || die "no DEVICE CA at $CA_P12 (start MockSync once: tools/mock-sync/scripts/run-mock-sync.sh)"
"$ADB" get-state >/dev/null 2>&1 || die "no device attached"

CA_P12="$(native_path "$CA_P12")"
work="$(native_path "$(mktemp -d)")"
trap 'rm -rf "$work"' EXIT

# Java 11 writes PKCS#12 files with RC2/3DES, which OpenSSL 3 reads only with -legacy.
read_ca() {
  openssl pkcs12 -in "$CA_P12" -passin "pass:$CA_PASS" "$@" 2>/dev/null \
    || openssl pkcs12 -legacy -in "$CA_P12" -passin "pass:$CA_PASS" "$@" 2>/dev/null
}
read_ca -nocerts -nodes -out "$work/ca.key" || die "cannot read the CA key from $CA_P12"
read_ca -clcerts -nokeys -out "$work/ca-bag.crt" || die "cannot read the CA certificate from $CA_P12"
# Plain PEM without OpenSSL's "Bag Attributes" lines.
openssl x509 -in "$work/ca-bag.crt" -out "$work/ca.crt" || die "cannot read the CA certificate from $CA_P12"
printf 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\n' > "$work/ext.cnf"

# ---------------------------------------------------------------- --csr: in-app key

# The app's exchange folder (app-specific external files; adb can read and write it on every build type).
APP_DIR="/sdcard/Android/data/$PACKAGE/files/liveness"

app_status() { "$ADB" shell cat "$APP_DIR/status.txt" 2>/dev/null | tr -d '\r' || true; }
status_field() { printf '%s\n' "$1" | sed -n "s/^$2=//p" | head -n 1; }

# Starts one provisioning step in the app and waits until status.txt carries this step's request ID.
provisioning_step() {
  local request="$1" replace="$2" waited=0 out status
  # 0x18000000 = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK: a new instance even while an earlier
  # step is still closing (on a busy emulator that can take a while).
  out="$("$ADB" shell am start -W -f 0x18000000 -n "$PACKAGE/$PROVISIONING_ACTIVITY" \
    --es request "$request" --ez replace_ca "$replace" 2>&1 | tr -d '\r')"
  case "$out" in
    *Error*|*Exception*) die "cannot start the provisioning step: $out" ;;
  esac
  while :; do
    status="$(app_status)"
    if [ "$(status_field "$status" request)" = "$request" ]; then
      printf '%s\n' "$status"
      return 0
    fi
    [ "$waited" -lt "$TIMEOUT" ] || die "no status from the app after ${TIMEOUT}s (adb logcat -s Liveness)"
    sleep 1
    waited=$((waited + 1))
  done
}

csr_provisioning() {
  "$ADB" shell pm path "$PACKAGE" 2>/dev/null | grep -q '^package:' || die "$PACKAGE is not installed"
  local first second status state

  log "step 1: the app creates its Keystore key and a certificate request"
  first="csr-$(openssl rand -hex 8)"
  status="$(provisioning_step "$first" false)"
  state="$(status_field "$status" state)"
  [ "$state" != "ERROR" ] || die "the app could not create the request: $(status_field "$status" detail)"
  log "app status before: $state, subject $(status_field "$status" subject), issuer $(status_field "$status" issuer)"

  log "step 2: issuing a certificate from $(basename "$CA_P12")"
  "$ADB" pull "$APP_DIR/device.csr" "$work/device.csr" >/dev/null 2>&1 || die "cannot pull $APP_DIR/device.csr"
  openssl req -in "$work/device.csr" -noout -verify >/dev/null 2>&1 \
    || die "the certificate request is not signed by its key"
  log "request $(openssl req -in "$work/device.csr" -noout -subject)"
  openssl x509 -req -in "$work/device.csr" -CA "$work/ca.crt" -CAkey "$work/ca.key" -CAcreateserial \
    -days 365 -sha256 -extfile "$work/ext.cnf" -out "$work/device.crt" 2>/dev/null \
    || die "cannot issue the certificate"
  openssl verify -CAfile "$work/ca.crt" "$work/device.crt" >/dev/null || die "issued certificate does not verify"
  cat "$work/device.crt" "$work/ca.crt" > "$work/device-chain.pem"

  log "step 3: pushing the chain; the app checks it against its key and the CA"
  # Written under another name first, so a capture never reads half a file.
  "$ADB" push "$work/device-chain.pem" "$APP_DIR/device-chain.pem.part" >/dev/null 2>&1 \
    || die "cannot push to $APP_DIR"
  "$ADB" shell "mv $APP_DIR/device-chain.pem.part $APP_DIR/device-chain.pem" || die "cannot rename the chain file"
  second="csr-$(openssl rand -hex 8)"
  status="$(provisioning_step "$second" "$REPLACE_CA")"

  log "step 4: provisioning status read from the app"
  printf '%s\n' "$status" | sed 's/^/  /'
  if [ "$(status_field "$status" last_import)" != "ACCEPTED" ]; then
    die "the app refused the certificate: $(status_field "$status" reason) $(status_field "$status" detail)"
  fi
  [ "$(status_field "$status" state)" = "PROVISIONED" ] || die "the app is not provisioned after the import"
  log "provisioned: subject $(status_field "$status" subject)"
  log "             issuer  $(status_field "$status" issuer)"
}

if [ "$CSR" = "1" ]; then
  csr_provisioning
  exit 0
fi

# ---------------------------------------------------------------- default: key file (debug builds)

log "issuing a device certificate for CN=$SERIAL from $(basename "$CA_P12")"
printf '[req]\nprompt=no\ndistinguished_name=dn\n[dn]\nCN=%s\nOU=Built-in face camera\nO=MOSIP Registration Client\n' \
  "$SERIAL" > "$work/req.cnf"
openssl req -new -newkey rsa:2048 -nodes -config "$work/req.cnf" -keyout "$work/device.key" \
  -out "$work/device.csr" 2>/dev/null || die "cannot create the device key"
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
  || die "copy into the app folder failed (is $PACKAGE a debug build? use --csr for release builds)"
log "device key waiting in files/liveness/ of $PACKAGE; the app imports it at the next capture"
