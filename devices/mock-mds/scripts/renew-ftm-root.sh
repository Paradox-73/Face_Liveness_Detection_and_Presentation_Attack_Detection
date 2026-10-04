#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Re-issues the MockMDS FTM root certificate (CN=RootCAFTM) with the SAME key pair and a new validity period, then
# puts the new root into every certificate chain that ends in it.
#
# Why: the upstream rootcaftm.cer expired on 2022-06-10. The Registration Client refuses to store an expired CA
# (KER-PCM-004 "Certificate Dates are not valid"), so the L1 digitalId (signed with a *ftm.p12 key whose
# certificate is issued by RootCAFTM) could never be trusted. The device certificates themselves (CN=MosipFaceFTM
# and the finger/iris ones) are valid until 2031 and carry no authority key identifier, so they stay unchanged and
# still verify against the re-issued root, because the root key is the same.
#
# Files changed (all under "Biometric Devices"):
#   rootcaftm.cer                     new self-signed root (PEM)
#   mosiprootcaftm.p12                root entry "rootcaftm" re-signed; chains of the device entries updated
#   */Keys/*ftm.p12                   chain of the device key updated (leaf unchanged, new root)
#
# Usage: devices/mock-mds/scripts/renew-ftm-root.sh [--years N] [--start YYYY/MM/DD]
#   Needs keytool from JDK 11 or newer on PATH (or JAVA_HOME). Run it again whenever the root nears its end date,
#   then rebuild MockMDS (the build copies "Biometric Devices" into target/) and restart MockSync, which serves
#   rootcaftm.cer under the FTM and DEVICE partner domains.
set -euo pipefail

YEARS=10
START="2026/01/01 00:00:00"
while [ $# -gt 0 ]; do
	case "$1" in
	--years) YEARS="$2"; shift 2 ;;
	--start) START="$2 00:00:00"; shift 2 ;;
	-h|--help) sed -n '2,20p' "$0"; exit 0 ;;
	*) echo "unknown option $1" >&2; exit 2 ;;
	esac
done

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DEV="$HERE/Biometric Devices"
KEYTOOL="keytool"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then KEYTOOL="$JAVA_HOME/bin/keytool"; fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool.exe" ]; then KEYTOOL="$JAVA_HOME/bin/keytool.exe"; fi

ROOT_STORE="$DEV/mosiprootcaftm.p12"
ROOT_PASS="mosip"
ROOT_ALIAS="rootcaftm"
ROOT_DN="CN=RootCAFTM, OU=mosip.io, O=mosip.io, L=Bangalore, ST=Karnataka, C=IN"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp "$ROOT_STORE" "$WORK/root.p12"
"$KEYTOOL" -selfcert -alias "$ROOT_ALIAS" -keystore "$WORK/root.p12" -storepass "$ROOT_PASS" \
	-keypass "$ROOT_PASS" -dname "$ROOT_DN" -sigalg SHA256withRSA -startdate "$START" -validity $((YEARS * 365)) \
	-ext "BasicConstraints=ca:true" -ext "KeyUsage=keyCertSign,cRLSign" >/dev/null 2>&1
"$KEYTOOL" -exportcert -rfc -alias "$ROOT_ALIAS" -keystore "$WORK/root.p12" \
	-storepass "$ROOT_PASS" -file "$WORK/rootcaftm.cer" >/dev/null 2>&1

# Replaces the chain of one private-key entry with <its own certificate, new root>.
# Arguments: key store, store password, alias; the key password is the alias (upstream convention).
# No -storetype: keytool detects it (mosiprootcaftm.p12 is a JKS file despite its name, the device stores are
# PKCS12), and JKS entries have their own key passwords.
rechain() {
	local store="$1" pass="$2" alias="$3"
	"$KEYTOOL" -exportcert -rfc -alias "$alias" -keystore "$store" -storepass "$pass" \
		-file "$WORK/leaf.pem" >/dev/null 2>&1
	cat "$WORK/leaf.pem" "$WORK/rootcaftm.cer" >"$WORK/chain.pem"
	"$KEYTOOL" -importcert -noprompt -alias "$alias" -keystore "$store" -storepass "$pass" \
		-keypass "$alias" -file "$WORK/chain.pem" >/dev/null 2>&1
	echo "  re-chained $alias in $(basename "$store")"
}

# Device entries inside the root store.
for alias in mosipfaceftm mosipfingersingleftm mosipfingerslapftm mosipirisdoubleftm mosipirissingleftm; do
	rechain "$WORK/root.p12" "$ROOT_PASS" "$alias"
done

# Per-device key stores (store password = alias = file name, as in application.properties).
declare -a DEVICE_STORES=(
	"Face/Keys/mosipfaceftm.p12"
	"Finger/Single/Keys/mosipfingersingleftm.p12"
	"Finger/Slap/Keys/mosipfingerslapftm.p12"
	"Iris/Double/Keys/mosipirisdoubleftm.p12"
	"Iris/Single/Keys/mosipirissingleftm.p12"
)
for rel in "${DEVICE_STORES[@]}"; do
	name="$(basename "$rel" .p12)"
	cp "$DEV/$rel" "$WORK/$name.p12"
	rechain "$WORK/$name.p12" "$name" "$name"
done

# Everything worked: replace the originals.
cp "$WORK/root.p12" "$ROOT_STORE"
cp "$WORK/rootcaftm.cer" "$DEV/rootcaftm.cer"
for rel in "${DEVICE_STORES[@]}"; do
	name="$(basename "$rel" .p12)"
	cp "$WORK/$name.p12" "$DEV/$rel"
done

"$KEYTOOL" -printcert -file "$DEV/rootcaftm.cer" | grep -E "Owner|Valid"
