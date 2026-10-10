#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Creates the release signing key of the Android Registration Client (clients/android) OUTSIDE the repository
# and points the Gradle build at it through clients/android/android/local.properties (git-ignored).
#
# What it does:
#   1. keytool creates an RSA 3072 key pair with a self-signed certificate valid 30 years in a PKCS#12 keystore
#      (default ../secrets/android-release.jks, next to the repository), with a
#      random 32-character store/key password. An existing keystore is never overwritten.
#   2. The password and alias are written next to the keystore (<keystore>.properties, same folder, not in git)
#      so the key can be backed up and reused on another build machine or as CI secrets.
#   3. storeFile, storePassword, keyAlias and keyPassword are set in android/local.properties, which
#      android/app/build.gradle reads for the release signing config (other lines are kept).
#
# The keystore and passwords must never be committed. In CI, pass them as the environment variables
# ARC_RELEASE_STORE_FILE, ARC_RELEASE_STORE_PASSWORD, ARC_RELEASE_KEY_ALIAS and ARC_RELEASE_KEY_PASSWORD instead.
#
# Usage (Git Bash on Windows, or bash):
#   scripts/android-release-keystore.sh                    # create (or reuse) the keystore and configure Gradle
#   scripts/android-release-keystore.sh --keystore PATH    # another location (must be outside the repository)
#   scripts/android-release-keystore.sh --show             # print the certificate fingerprint only
#
# Environment:
#   ARC_RELEASE_KEYSTORE   default for --keystore
#   JAVA_HOME              a JDK (keytool)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_PROPERTIES="$REPO_ROOT/clients/android/android/local.properties"
KEYSTORE="${ARC_RELEASE_KEYSTORE:-$(cd "$REPO_ROOT/.." && pwd)/secrets/android-release.jks}"
ALIAS="arc-release"
SHOW_ONLY=0

LOG_TAG=android-release-keystore; . "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

while [ $# -gt 0 ]; do
  case "$1" in
    --keystore) [ $# -ge 2 ] || die "--keystore needs a value"; KEYSTORE="$2"; shift 2 ;;
    --show) SHOW_ONLY=1; shift ;;
    -h|--help) sed -n "2,$(( $(grep -n '^set -euo pipefail' "${BASH_SOURCE[0]}" | cut -d: -f1) - 1 ))p" "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$KEYTOOL" >/dev/null 2>&1 || die "keytool not found (set JAVA_HOME to a JDK)"
SECRETS_FILE="$KEYSTORE.properties"

# The keystore must not end up in the repository.
keystore_abs="$(cd "$(dirname "$KEYSTORE")" 2>/dev/null && pwd || true)"
case "$keystore_abs/" in
  "$REPO_ROOT"/*) die "the keystore must be outside the repository ($REPO_ROOT)" ;;
esac

if [ "$SHOW_ONLY" = "1" ]; then
  [ -f "$SECRETS_FILE" ] || die "no $SECRETS_FILE"
  pass="$(sed -n 's/^storePassword=//p' "$SECRETS_FILE")"
  "$KEYTOOL" -list -v -keystore "$KEYSTORE" -storepass "$pass" -alias "$ALIAS" | grep -E 'Owner|SHA256|Valid'
  exit 0
fi

git -C "$REPO_ROOT" check-ignore -q "$LOCAL_PROPERTIES" || die "$LOCAL_PROPERTIES is not git-ignored; refusing to write passwords into it"

mkdir -p "$(dirname "$KEYSTORE")"
if [ -f "$KEYSTORE" ]; then
  [ -f "$SECRETS_FILE" ] || die "$KEYSTORE exists but $SECRETS_FILE (its password) does not"
  log "reusing $KEYSTORE"
  pass="$(sed -n 's/^storePassword=//p' "$SECRETS_FILE")"
else
  pass="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"
  [ "${#pass}" -eq 32 ] || die "could not generate a password"
  log "creating $KEYSTORE (RSA 3072, PKCS#12, alias $ALIAS)"
  "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" -keyalg RSA -keysize 3072 \
    -validity 10950 -storepass "$pass" -keypass "$pass" \
    -dname "CN=Android Registration Client release, OU=Badmosip, O=MOSIP Decode 2026" >/dev/null
  umask 077
  printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
    "$(cd "$(dirname "$KEYSTORE")" && pwd -W 2>/dev/null || pwd)/$(basename "$KEYSTORE")" "$pass" "$ALIAS" "$pass" \
    > "$SECRETS_FILE"
fi

# Gradle reads Java properties: backslashes must be forward slashes.
store_path="$(sed -n 's/^storeFile=//p' "$SECRETS_FILE" | tr '\\' '/')"
touch "$LOCAL_PROPERTIES"
tmp="$(mktemp)"
grep -v -E '^(storeFile|storePassword|keyAlias|keyPassword)=' "$LOCAL_PROPERTIES" > "$tmp" || true
printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' "$store_path" "$pass" "$ALIAS" "$pass" >> "$tmp"
mv "$tmp" "$LOCAL_PROPERTIES"
log "release signing configured in $LOCAL_PROPERTIES (git-ignored)"
"$KEYTOOL" -list -v -keystore "$KEYSTORE" -storepass "$pass" -alias "$ALIAS" | grep -E 'SHA256:' | head -n 1 \
  | sed 's/^/[android-release-keystore] certificate /'
