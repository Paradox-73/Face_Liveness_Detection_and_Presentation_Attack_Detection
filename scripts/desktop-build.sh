#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Builds the desktop Registration Client (clients/desktop/registration) and
# assembles a local run layout outside the git tree:
#
#   <run-dir>/jre/        Zulu 11.0.8 JRE with JavaFX (the runtime MOSIP bundles)
#   <run-dir>/lib/        client jars, logback.xml, mock-sdk.jar
#   <run-dir>/run.bat     double-click launcher
#   <run-dir>/BUILD-INFO.txt
#
# The layout mirrors what clients/desktop/registration/configure.sh produces
# for the official reg-client.zip, minus jar signing and MANIFEST.MF, which
# environment=LOCAL does not check. State created by the client at run time
# (db/, .mosipkeys/, logs/) is kept when the layout is re-assembled.
#
# Usage (Git Bash on Windows):
#   scripts/desktop-build.sh                    # default host (dev.mosip.net)
#   scripts/desktop-build.sh --mocksync         # host localhost:8443 (MockSync)
#   scripts/desktop-build.sh --hostname HOST    # any other host[:port]
#   scripts/desktop-build.sh --skip-build       # re-assemble from the last build
#
# Options:
#   --mocksync          run layout talks to MockSync (mosip.hostname=localhost:8443)
#   --hostname HOST     run layout talks to HOST (mosip.hostname=HOST)
#                       Both rewrite props/mosip-application.properties in the
#                       run layout's registration-services jar with 'jar uf', as
#                       upstream configure.sh does; the Maven build is upstream's.
#   --fast              build only registration-client and the modules it needs
#   --skip-build        do not run Maven; assemble from existing target/ folders
#   --skip-assemble     run Maven only
#   --run-dir DIR       run layout folder (default: $MOSIP_RUN_ROOT/desktop)
#
# Environment:
#   MOSIP_RUN_ROOT      folder for run layouts and downloads (default: <repo>/../run)
#   JAVA11_HOME         JDK 11 used for the build; must contain javac. When it is
#                       missing or incomplete, the Zulu 11.0.8 FX JDK is downloaded
#                       into $MOSIP_RUN_ROOT/cache and used instead.
#   MAVEN_ARGS          extra Maven arguments, e.g. "-s mirror-settings.xml"
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RC_DIR="$REPO_ROOT/clients/desktop/registration"
RUN_ROOT="${MOSIP_RUN_ROOT:-$(cd "$REPO_ROOT/.." && pwd)/run}"
CACHE_DIR="$RUN_ROOT/cache"
RUN_DIR="$RUN_ROOT/desktop"

ZULU_BASE_URL="https://cdn.azul.com/zulu/bin"
ZULU_JDK="zulu11.41.23-ca-fx-jdk11.0.8-win_x64"
ZULU_JDK_SHA256="e6248a59ddc49b5896a0b29a481fc8987695e2fbe9390f8665868d0caadcdecd"
ZULU_JRE="zulu11.41.23-ca-fx-jre11.0.8-win_x64"
ZULU_JRE_SHA256="fda266e6cce0a00d0cdb796d8908d1b793c255fe7adce11021d02ab6e68d0a9f"
# configure.sh installs mock-sdk 1.1.5 as lib/mock-sdk.jar; the same version is on Maven Central.
MOCK_SDK_URL="https://repo1.maven.org/maven2/io/mosip/mock/sdk/mock-sdk/1.1.5/mock-sdk-1.1.5.jar"
MOCK_SDK_SHA256="9193f2e39df41b6a5ada3b2585240341efb006c34cdf9d673a4ad220e04a9fdf"

MOCKSYNC=0
HOST_OVERRIDE=""
FAST=0
DO_BUILD=1
DO_ASSEMBLE=1

LOG_TAG=desktop-build; . "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# Prints the header comment of this file (everything up to the first code line).
usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
	case "$1" in
		--mocksync) MOCKSYNC=1 ;;
		--hostname) [ $# -ge 2 ] || die "--hostname needs a value"; HOST_OVERRIDE="$2"; shift ;;
		--fast) FAST=1 ;;
		--skip-build) DO_BUILD=0 ;;
		--skip-assemble) DO_ASSEMBLE=0 ;;
		--run-dir) [ $# -ge 2 ] || die "--run-dir needs a value"; RUN_DIR="$2"; shift ;;
		-h|--help) usage; exit 0 ;;
		*) die "unknown option: $1 (see --help)" ;;
	esac
	shift
done

# Downloads $1 to $2 unless $2 already exists, then checks its SHA-256 against $3.
fetch() {
	local url="$1" dest="$2" sha="$3"
	mkdir -p "$(dirname "$dest")"
	if [ ! -f "$dest" ]; then
		log "downloading $url"
		curl -fsSL --retry 3 -o "$dest.part" "$url"
		mv "$dest.part" "$dest"
	fi
	echo "$sha *$dest" | sha256sum -c --status - || die "checksum mismatch for $dest; delete it and retry"
}

has_javac() { [ -n "$1" ] && { [ -x "$1/bin/javac" ] || [ -x "$1/bin/javac.exe" ]; }; }

# Picks a complete JDK 11 for the build. A JDK without javac, or without the
# crypto policy files, cannot compile or download dependencies over HTTPS.
select_jdk() {
	local candidate
	for candidate in "${JAVA11_HOME:-}" "${JAVA_HOME:-}"; do
		if has_javac "$candidate" && [ -f "$candidate/conf/security/policy/unlimited/default_local.policy" ] \
			&& "$candidate/bin/java" -version 2>&1 | grep -q 'version "11\.'; then
			echo "$candidate"
			return
		fi
	done
	fetch "$ZULU_BASE_URL/$ZULU_JDK.zip" "$CACHE_DIR/$ZULU_JDK.zip" "$ZULU_JDK_SHA256" >&2
	if [ ! -d "$CACHE_DIR/$ZULU_JDK" ]; then
		log "unpacking $ZULU_JDK" >&2
		unzip -q "$CACHE_DIR/$ZULU_JDK.zip" -d "$CACHE_DIR"
	fi
	echo "$CACHE_DIR/$ZULU_JDK"
}

run_maven() {
	export JAVA_HOME
	JAVA_HOME="$(select_jdk)"
	export PATH="$JAVA_HOME/bin:$PATH"
	command -v mvn >/dev/null || die "mvn is not on PATH"
	log "JDK: $JAVA_HOME"

	local args=(-B clean install -DskipTests -Dgpg.skip)
	[ "$FAST" = 1 ] && args+=(-pl registration-client -am)

	log "mvn ${args[*]}  (in $RC_DIR)"
	local start end
	start=$(date +%s)
	(cd "$RC_DIR" && mvn "${args[@]}")
	end=$(date +%s)
	log "Maven build finished in $((end - start)) s"
}

client_version() {
	local jar
	jar="$(ls "$RC_DIR"/registration-client/target/registration-client-*.jar 2>/dev/null \
		| grep -v -e '-sources\.jar$' -e '-javadoc\.jar$' | head -n 1)"
	[ -n "$jar" ] || die "no registration-client jar in registration-client/target; build first"
	basename "$jar" .jar | sed 's/^registration-client-//'
}

install_jre() {
	if [ -x "$RUN_DIR/jre/bin/javaw.exe" ]; then
		log "keeping existing $RUN_DIR/jre (and any certificates imported into it)"
		return
	fi
	fetch "$ZULU_BASE_URL/$ZULU_JRE.zip" "$CACHE_DIR/$ZULU_JRE.zip" "$ZULU_JRE_SHA256"
	log "unpacking $ZULU_JRE into $RUN_DIR/jre"
	local tmp="$RUN_DIR/.jre-unpack"
	rm -rf "$tmp"
	unzip -q "$CACHE_DIR/$ZULU_JRE.zip" -d "$tmp"
	mv "$tmp/$ZULU_JRE" "$RUN_DIR/jre"
	rm -rf "$tmp"
}

write_run_bat() {
	# Same command line as the run.bat written by configure.sh, without -Xms2048m.
	printf '%s\r\n' \
		'@echo off' \
		'rem Starts the MOSIP Registration Client from this folder. Generated by scripts/desktop-build.sh.' \
		'cd /d "%~dp0"' \
		'start "" jre\bin\javaw -Xmx2048m -Dfile.encoding=UTF-8 -cp "lib/*" io.mosip.registration.controller.Initialization' \
		> "$RUN_DIR/run.bat"
}

# Writes the server host into props/mosip-application.properties of the given jar.
set_hostname() {
	local host="$HOST_OVERRIDE" tmp="$RUN_DIR/.props" jar
	jar="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
	[ -z "$host" ] && [ "$MOCKSYNC" = 1 ] && host="localhost:8443"
	[ -n "$host" ] || return 0
	rm -rf "$tmp"
	mkdir -p "$tmp/props"
	unzip -p "$jar" props/mosip-application.properties | tr -d '\r' \
		| sed "s|^mosip.hostname=.*|mosip.hostname=$host|" > "$tmp/props/mosip-application.properties"
	(cd "$tmp" && "$(select_jdk)/bin/jar" uf "$jar" props/mosip-application.properties)
	rm -rf "$tmp"
}

assemble() {
	local version target services_jar hostname environment
	version="$(client_version)"
	target="$RC_DIR/registration-client/target"
	services_jar="$target/lib/registration-services-$version.jar"
	[ -f "$services_jar" ] || die "missing $services_jar"

	mkdir -p "$RUN_DIR"
	log "assembling run layout in $RUN_DIR (client $version)"
	rm -rf "$RUN_DIR/lib"
	mkdir -p "$RUN_DIR/lib"
	cp "$target"/lib/*.jar "$RUN_DIR/lib/"
	cp "$target/registration-client-$version.jar" "$RUN_DIR/lib/"
	cp "$RC_DIR/lib/logback.xml" "$RUN_DIR/lib/logback.xml"
	fetch "$MOCK_SDK_URL" "$CACHE_DIR/mock-sdk-1.1.5.jar" "$MOCK_SDK_SHA256"
	cp "$CACHE_DIR/mock-sdk-1.1.5.jar" "$RUN_DIR/lib/mock-sdk.jar"

	install_jre
	write_run_bat

	services_jar="$RUN_DIR/lib/registration-services-$version.jar"
	set_hostname "$services_jar"
	hostname="$(unzip -p "$services_jar" props/mosip-application.properties | tr -d '\r' | sed -n 's/^mosip.hostname=//p')"
	environment="$(unzip -p "$services_jar" props/mosip-application.properties | tr -d '\r' | sed -n 's/^environment=//p')"
	{
		echo "client.version=$version"
		echo "mosip.hostname=$hostname"
		echo "environment=$environment"
		echo "git.commit=$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
		echo "assembled=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
		echo "jar.count=$(ls "$RUN_DIR/lib" | grep -c '\.jar$')"
	} > "$RUN_DIR/BUILD-INFO.txt"
	log "mosip.hostname=$hostname environment=$environment"
	log "run with: powershell -ExecutionPolicy Bypass -File scripts/desktop-run.ps1   (or $RUN_DIR/run.bat)"
}

[ "$DO_BUILD" = 1 ] && run_maven
[ "$DO_ASSEMBLE" = 1 ] && assemble
log "done"
