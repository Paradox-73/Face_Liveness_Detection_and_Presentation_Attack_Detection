#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Builds every release artefact into dist/ with one command, then writes
# SHA256SUMS.txt and manifest.json next to them.
#
# Artefacts (<v> = release version, see --version):
#   engine    liveness-core, liveness-onnx, liveness-onnx models and face-match-sdk jars
#   desktop   mosip-liveness-desktop-client-<v>-win-x64.zip: Registration Client built for MockSync
#             (-Pmocksync), lib/ with the liveness jars, ONNX Runtime, signed models, face-match SDK and
#             MOSIP mock SDK, Zulu 11.0.8 FX JRE, start/stop scripts, MockSync trust and machine setup
#   android   mosip-liveness-android-<v>-<abi>.apk: release APKs (arm64-v8a for tablets, x86_64 for the
#             emulator) from scripts/android-build.sh --release
#   mocksync  mosip-liveness-mocksync-<v>[-<os>-x64].zip (jar, reference data, run scripts) and the bare
#             executable jar mock-sync-<v>.jar
#   mockmds   mosip-liveness-mockmds-<v>[-<os>-x64].zip: MockMDS jar, runtime libraries, device keys and
#             profiles, run scripts, frame-source documentation, sample frame sequence
# The MockSync and MockMDS zips carry a small Java 21 runtime (jlink) unless --no-runtime is given; the
# zip name then ends in the runtime's platform, e.g. -win-x64.
#
# Usage (Git Bash on Windows; bash on Linux for the platform-independent parts):
#   scripts/release.sh                               # everything
#   scripts/release.sh --only engine,mocksync,mockmds --no-runtime   # what CI builds
#   scripts/release.sh --only desktop --no-build     # re-package the last desktop build
#
# Options:
#   --only LIST       comma list of parts: engine,desktop,android,mocksync,mockmds (default: all)
#   --version V       release version in file names (default: git describe --tags --always --dirty)
#   --out DIR         output folder (default: <repo>/dist; gitignored)
#   --android-abis L  comma list of Android CPU types, one APK each (default: arm64-v8a,x86_64)
#   --no-runtime      do not bundle a Java runtime in the MockSync and MockMDS zips
#   --no-build        engine and desktop: package the existing build output instead of rebuilding
#   --skip-tests      skip unit tests in the engine, MockSync and MockMDS builds
#   --keep-staging    keep <out>/.staging (the unpacked packages) for inspection
#
# Environment:
#   JAVA21_HOME     JDK 21 with jmods (jar --date, jlink, MockMDS); JAVA_HOME is used when it is a JDK 21
#   JAVA11_HOME     JDK 11: MockSync build (its tests need Java 11) and the desktop client (JavaFX;
#                   see scripts/desktop-build.sh)
#   MAVEN_ARGS      extra Maven arguments; when it names no settings file, scripts/release/maven-settings.xml
#                   (retired MOSIP repositories routed to Maven Central) is used
#   MOSIP_LOCK_DIR  folder for the shared build locks (default: <repo>/../toolchain/locks when it exists)
#   SOURCE_DATE_EPOCH  timestamp for every zip entry (default: the last commit's time), for repeatable zips
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATES="$REPO_ROOT/scripts/release"
OUT="$REPO_ROOT/dist"
PARTS="engine,desktop,android,mocksync,mockmds"
VERSION=""
ANDROID_ABIS="arm64-v8a,x86_64"
RUNTIME=1
BUILD=1
TESTS=1
KEEP_STAGING=0
PREFIX="mosip-liveness"
# Modules of the bundled Java 21 runtime: Java SE plus what Spring Boot, Tomcat and the crypto code load.
RUNTIME_MODULES="java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.management,jdk.naming.dns,jdk.localedata,jdk.charsets,jdk.httpserver,jdk.net"

log() { echo "[release $(date +%H:%M:%S)] $*"; }
die() { echo "[release] ERROR: $*" >&2; exit 1; }
usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
	case "$1" in
		--only) [ $# -ge 2 ] || die "--only needs a value"; PARTS="$2"; shift ;;
		--version) [ $# -ge 2 ] || die "--version needs a value"; VERSION="$2"; shift ;;
		--out) [ $# -ge 2 ] || die "--out needs a value"; OUT="$2"; shift ;;
		--android-abis) [ $# -ge 2 ] || die "--android-abis needs a value"; ANDROID_ABIS="$2"; shift ;;
		--no-runtime) RUNTIME=0 ;;
		--no-build) BUILD=0 ;;
		--skip-tests) TESTS=0 ;;
		--keep-staging) KEEP_STAGING=1 ;;
		-h|--help) usage; exit 0 ;;
		*) die "unknown option: $1 (see --help)" ;;
	esac
	shift
done

wants() { case ",$PARTS," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }
for part in ${PARTS//,/ }; do
	case "$part" in engine|desktop|android|mocksync|mockmds) ;; *) die "unknown part: $part" ;; esac
done

# ---------------------------------------------------------------- environment

is_java21() { [ -n "$1" ] && "$1/bin/java" -version 2>&1 | head -n 1 | grep -q '"21'; }
JAVA21=""
for candidate in "${JAVA21_HOME:-}" "${JAVA_HOME:-}"; do
	if is_java21 "$candidate"; then JAVA21="$candidate"; break; fi
done
[ -n "$JAVA21" ] || die "set JAVA21_HOME to a JDK 21 (needed for jar --date, jlink and MockMDS)"
command -v mvn >/dev/null || die "mvn is not on PATH"
command -v sha256sum >/dev/null || die "sha256sum is not on PATH"

# Maven 3.9 reads MAVEN_ARGS itself, so the settings file also reaches the builds that the client build
# scripts start.
case " ${MAVEN_ARGS:-} " in
	*" -s "*|*" --settings "*) ;;
	*)
		settings="$TEMPLATES/maven-settings.xml"
		command -v cygpath >/dev/null 2>&1 && settings="$(cygpath -w "$settings")"
		export MAVEN_ARGS="${MAVEN_ARGS:-} -s $settings" ;;
esac
MVN=(mvn -B)
TEST_ARGS=()
[ "$TESTS" = 1 ] || TEST_ARGS=(-DskipTests)

if [ -z "$VERSION" ]; then
	VERSION="$(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo snapshot)"
fi
VERSION="${VERSION#v}"
COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$REPO_ROOT" log -1 --format=%ct 2>/dev/null || date +%s)}"
ZIP_DATE="$(date -u -d "@$EPOCH" +%Y-%m-%dT%H:%M:%SZ)"

case "$(uname -s)" in
	MINGW*|MSYS*|CYGWIN*) PLATFORM="win-x64" ;;
	Darwin) PLATFORM="macos-$(uname -m | sed 's/arm64/aarch64/;s/x86_64/x64/')" ;;
	*) PLATFORM="linux-$(uname -m | sed 's/x86_64/x64/')" ;;
esac
RUNTIME_SUFFIX=""
[ "$RUNTIME" = 1 ] && RUNTIME_SUFFIX="-$PLATFORM"

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
STAGE="$OUT/.staging"
rm -rf "$STAGE"
mkdir -p "$STAGE"

# ---------------------------------------------------------------- helpers

LOCK_DIR="${MOSIP_LOCK_DIR:-}"
[ -n "$LOCK_DIR" ] || { [ -d "$REPO_ROOT/../toolchain/locks" ] && LOCK_DIR="$REPO_ROOT/../toolchain/locks"; } || true
HELD_LOCKS=()
release_locks() { local l; for l in "${HELD_LOCKS[@]:-}"; do [ -n "$l" ] && rmdir "$l" 2>/dev/null; done; return 0; }
trap release_locks EXIT

# Runs a command while holding the shared lock NAME (a directory in $LOCK_DIR), so parallel builds of the
# same module on this machine do not overwrite each other's output. Without a lock folder it just runs.
locked() {
	local name="$1"; shift
	if [ -z "$LOCK_DIR" ]; then "$@"; return; fi
	local dir="$LOCK_DIR/$name" waited=0
	until mkdir "$dir" 2>/dev/null; do
		[ "$waited" = 0 ] && log "waiting for build lock '$name'"
		sleep 15; waited=$((waited + 15))
		[ "$waited" -lt 3600 ] || die "build lock '$name' still held after 60 min ($dir)"
	done
	HELD_LOCKS+=("$dir")
	local rc=0
	"$@" || rc=$?
	rmdir "$dir"
	HELD_LOCKS=("${HELD_LOCKS[@]/$dir}")
	return "$rc"
}

# Copies source folders (tracked and untracked files, without build output) into $STAGE/src with the same
# relative paths, so modules whose target/ folder is in use by a running server are built elsewhere.
stage_sources() {
	local rel
	for rel in "$@"; do
		mkdir -p "$STAGE/src/$rel"
		if git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
			# Files deleted in the working tree are still listed as tracked; skip them.
			(cd "$REPO_ROOT" && git ls-files -z --cached --others --exclude-standard -- "$rel" \
				| while IFS= read -r -d '' f; do [ -f "$f" ] && printf '%s\0' "$f"; done \
				| xargs -0 cp --parents -t "$STAGE/src")
		else
			(cd "$REPO_ROOT/$rel" && tar --exclude=./target --exclude=./work -cf - .) | (cd "$STAGE/src/$rel" && tar -xf -)
		fi
	done
}

# Copies a text file and gives it Windows line endings (.bat, .cmd) or Unix line endings (anything else).
copy_text() {
	local src="$1" dest="$2"
	mkdir -p "$(dirname "$dest")"
	case "$dest" in
		*.bat|*.cmd) sed -e 's/\r$//' -e 's/$/\r/' "$src" > "$dest" ;;
		*) sed -e 's/\r$//' "$src" > "$dest" ;;
	esac
}

# Zips $1/$2 (a folder) into $3 with every entry dated $ZIP_DATE, so an unchanged tree gives the same zip.
make_zip() {
	local parent="$1" name="$2" dest="$3"
	rm -f "$dest"
	(cd "$parent" && "$JAVA21/bin/jar" --create --no-manifest --date="$ZIP_DATE" --file "$dest" "$name")
	log "wrote $(basename "$dest") ($(du -h "$dest" | cut -f1))"
}

# Copies the small Java 21 runtime into $1/runtime, linking it with jlink the first time.
add_java_runtime() {
	local rt="$STAGE/runtime"
	if [ ! -d "$rt" ]; then
		[ -d "$JAVA21/jmods" ] || die "$JAVA21 has no jmods folder; jlink needs a full JDK 21 (or use --no-runtime)"
		log "linking Java 21 runtime ($RUNTIME_MODULES)"
		"$JAVA21/bin/jlink" --add-modules "$RUNTIME_MODULES" --include-locales=en,fr,ar \
			--strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "$rt"
	fi
	cp -r "$rt" "$1/runtime"
}

copy_licences() {
	local dest="$1"
	mkdir -p "$dest/licenses"
	copy_text "$REPO_ROOT/LICENSE" "$dest/licenses/LICENSE.txt"
	copy_text "$REPO_ROOT/NOTICE" "$dest/licenses/NOTICE.txt"
}

with_java21() { JAVA_HOME="$JAVA21" PATH="$JAVA21/bin:$PATH" "$@"; }

# ---------------------------------------------------------------- parts

build_engine() {
	log "== engine (liveness-core, liveness-onnx, face-match-sdk)"
	rm -f "$OUT"/liveness-core-*.jar "$OUT"/liveness-onnx-*.jar "$OUT"/face-match-sdk-*.jar
	if [ "$BUILD" = 1 ]; then
		locked liveness-build with_java21 "${MVN[@]}" -f "$REPO_ROOT/liveness/pom.xml" install \
			-Dliveness.benchmark.frames=0 "${TEST_ARGS[@]}"
	fi
	local jar found=0
	for jar in "$REPO_ROOT"/liveness/liveness-core/target/liveness-core-*.jar \
			"$REPO_ROOT"/liveness/liveness-onnx/target/liveness-onnx-*.jar \
			"$REPO_ROOT"/liveness/face-match-sdk/target/face-match-sdk-*.jar; do
		case "$jar" in *-sources.jar|*-javadoc.jar|*-tests.jar|*'*'*) continue ;; esac
		cp "$jar" "$OUT/"
		found=$((found + 1))
	done
	[ "$found" -ge 4 ] || die "expected 4 engine jars in liveness/*/target, found $found"
}

build_desktop() {
	log "== desktop client"
	local name="$PREFIX-desktop-client-$VERSION-win-x64" pkg
	pkg="$STAGE/desktop/$name"
	rm -f "$OUT/$PREFIX"-desktop-client-*.zip
	mkdir -p "$STAGE/desktop"
	local args=(--mocksync --run-dir "$pkg")
	[ "$BUILD" = 1 ] || args+=(--skip-build)
	locked desktop-build bash "$REPO_ROOT/scripts/desktop-build.sh" "${args[@]}"

	copy_text "$REPO_ROOT/scripts/desktop-run.ps1" "$pkg/scripts/desktop-run.ps1"
	copy_text "$TEMPLATES/desktop/scripts/setup-mocksync.ps1" "$pkg/scripts/setup-mocksync.ps1"
	local f
	for f in start-client.bat stop-client.bat setup-mocksync.bat README.txt; do
		copy_text "$TEMPLATES/desktop/$f" "$pkg/$f"
	done

	# MachineKeyTool reads the machine keys with the client's own key library; compiled here because the
	# bundled JRE has no compiler.
	local classes="$STAGE/machine-key-tool"
	mkdir -p "$classes" "$pkg/tools"
	"$JAVA21/bin/javac" --release 11 -d "$classes" "$REPO_ROOT/tools/mock-sync/scripts/MachineKeyTool.java"
	"$JAVA21/bin/jar" --create --date="$ZIP_DATE" --file "$pkg/tools/machine-key-tool.jar" -C "$classes" .

	copy_licences "$pkg"
	cp "$REPO_ROOT"/models/LICENSES/* "$pkg/licenses/"
	copy_text "$REPO_ROOT/models/SOURCES.md" "$pkg/licenses/MODELS-SOURCES.md"
	copy_text "$REPO_ROOT/docs/test-data.md" "$pkg/docs/TEST-DATA.md"
	echo "release.version=$VERSION" >> "$pkg/BUILD-INFO.txt"
	make_zip "$STAGE/desktop" "$name" "$OUT/$name.zip"
}

build_android() {
	log "== android (release APKs: $ANDROID_ABIS)"
	rm -f "$OUT/$PREFIX"-android-*.apk
	local apk="$REPO_ROOT/clients/android/build/app/outputs/flutter-apk/app-release.apk" abi first=1
	for abi in ${ANDROID_ABIS//,/ }; do
		local args=(--release --abis "$abi")
		[ "$first" = 1 ] || args+=(--skip-codegen)
		rm -f "$apk"
		locked android-build with_java21 bash "$REPO_ROOT/scripts/android-build.sh" "${args[@]}"
		[ -f "$apk" ] || die "no APK at $apk"
		cp "$apk" "$OUT/$PREFIX-android-$VERSION-$abi.apk"
		log "wrote $PREFIX-android-$VERSION-$abi.apk ($(du -h "$apk" | cut -f1))"
		first=0
	done
}

build_mocksync() {
	log "== MockSync"
	local name="$PREFIX-mocksync-$VERSION$RUNTIME_SUFFIX" pkg src
	rm -f "$OUT/$PREFIX"-mocksync-*.zip "$OUT"/mock-sync-*.jar
	stage_sources tools/mock-sync "devices/mock-mds/Biometric Devices"
	src="$STAGE/src/tools/mock-sync"
	# Java 11: the tests check MockSync's output with MOSIP keymanager code that asks for AES/GCM/PKCS5Padding,
	# which Java 17+ refuses. The server itself runs on Java 11 or newer.
	local jdk11="${JAVA11_HOME:-}"
	[ -n "$jdk11" ] && "$jdk11/bin/java" -version 2>&1 | head -n 1 | grep -q '"11\.' \
		|| die "set JAVA11_HOME to a JDK 11 for the MockSync build"
	JAVA_HOME="$jdk11" PATH="$jdk11/bin:$PATH" "${MVN[@]}" -f "$src/pom.xml" package "${TEST_ARGS[@]}"
	[ -f "$src/target/mock-sync.jar" ] || die "MockSync build produced no target/mock-sync.jar"

	pkg="$STAGE/mocksync/$name"
	mkdir -p "$pkg/certs" "$pkg/docs"
	cp "$src/target/mock-sync.jar" "$pkg/mock-sync.jar"
	cp -r "$src/data" "$pkg/data"
	cp "$STAGE/src/devices/mock-mds/Biometric Devices/rootca.cer" "$STAGE/src/devices/mock-mds/Biometric Devices/rootcaftm.cer" "$pkg/certs/"
	copy_text "$TEMPLATES/mocksync/config/application.properties" "$pkg/config/application.properties"
	local f
	for f in start-mocksync.bat start-mocksync.sh README.txt; do copy_text "$TEMPLATES/mocksync/$f" "$pkg/$f"; done
	for f in set-config.ps1 set-config.sh; do copy_text "$src/scripts/$f" "$pkg/$f"; done
	copy_text "$src/README.md" "$pkg/docs/MOCKSYNC.md"
	copy_text "$REPO_ROOT/docs/test-data.md" "$pkg/docs/TEST-DATA.md"
	copy_licences "$pkg"
	if [ "$RUNTIME" = 1 ]; then add_java_runtime "$pkg"; fi
	cp "$src/target/mock-sync.jar" "$OUT/mock-sync-$VERSION.jar"
	make_zip "$STAGE/mocksync" "$name" "$OUT/$name.zip"
}

build_mockmds() {
	log "== MockMDS"
	local name="$PREFIX-mockmds-$VERSION$RUNTIME_SUFFIX" pkg src jar
	rm -f "$OUT/$PREFIX"-mockmds-*.zip
	stage_sources devices/mock-mds
	src="$STAGE/src/devices/mock-mds"
	pkg="$STAGE/mockmds/$name"
	# The upstream pom sets skipTests=false explicitly, so only maven.test.skip turns the tests off.
	local skip=()
	[ "$TESTS" = 1 ] || skip=(-Dmaven.test.skip=true)
	# The upstream pom records the Git commit; a staging folder outside the work tree has no .git to read.
	git -C "$src" rev-parse --git-dir >/dev/null 2>&1 || skip+=(-Dmaven.gitcommitid.skip=true)
	with_java21 "${MVN[@]}" -f "$src/pom.xml" package -Dgpg.skip=true -Dmaven.javadoc.skip=true "${skip[@]}"
	# Runtime libraries only (the build's own target/lib also holds the test libraries).
	with_java21 "${MVN[@]}" -q -f "$src/pom.xml" dependency:copy-dependencies -DincludeScope=runtime \
		-DoutputDirectory="$pkg/lib"
	jar="$(ls "$src"/target/mock-mds-*.jar | grep -v -e '-sources' -e '-javadoc' | head -n 1)"
	[ -n "$jar" ] || die "MockMDS build produced no jar"

	cp "$jar" "$pkg/mock-mds.jar"
	cp "$src/target/application.properties" "$pkg/application.properties"
	cp -r "$src/Biometric Devices" "$pkg/"
	cp -r "$src/target/Profile" "$pkg/"
	mkdir -p "$pkg/docs" "$pkg/frames"
	if [ -d "$src/target/test-frames/e2e-sequence" ]; then
		cp -r "$src/target/test-frames/e2e-sequence" "$pkg/frames/sample-sequence"
	fi
	local f
	for f in start-mockmds.bat start-mockmds.sh README.txt; do copy_text "$TEMPLATES/mockmds/$f" "$pkg/$f"; done
	copy_text "$src/LIVENESS-EXTENSIONS.md" "$pkg/docs/LIVENESS-EXTENSIONS.md"
	copy_text "$src/README.md" "$pkg/docs/MOCKMDS-UPSTREAM-README.md"
	copy_text "$src/LICENSE" "$pkg/LICENSE.txt"
	if [ "$RUNTIME" = 1 ]; then add_java_runtime "$pkg"; fi
	make_zip "$STAGE/mockmds" "$name" "$OUT/$name.zip"
}

# ---------------------------------------------------------------- checksums and manifest

describe() {
	case "$1" in
		*desktop-client*) echo "Desktop Registration Client with face liveness, built for MockSync, with Java runtime (Windows x64)" ;;
		*android*arm64-v8a.apk) echo "Android Registration Client release APK for arm64-v8a tablets" ;;
		*android*x86_64.apk) echo "Android Registration Client release APK for the x86_64 emulator" ;;
		*android*) echo "Android Registration Client release APK" ;;
		*mocksync*.zip) echo "MockSync local MOSIP server: jar, reference data, run scripts" ;;
		mock-sync-*.jar) echo "MockSync executable jar (needs data/ and config/ from the zip, Java 11+)" ;;
		*mockmds*.zip) echo "MockMDS SBI device simulator with liveness face-stream extensions and run scripts" ;;
		liveness-core-*) echo "Liveness engine core (pure Java 11, no native code)" ;;
		liveness-onnx-*-models.jar) echo "Signed ONNX model bundle (class-path resources)" ;;
		liveness-onnx-*) echo "ONNX Runtime adapters: face detection, landmarks, passive PAD, embedding" ;;
		face-match-sdk-*) echo "MOSIP IBioApi face-match SDK (SFace)" ;;
		*) echo "" ;;
	esac
}

write_checksums() {
	local files=() f
	for f in "$OUT"/*.zip "$OUT"/*.apk "$OUT"/*.jar; do [ -f "$f" ] && files+=("$(basename "$f")"); done
	[ "${#files[@]}" -gt 0 ] || die "no artefacts in $OUT"
	(cd "$OUT" && sha256sum -b "${files[@]}" | sed 's/ \*/  /' > SHA256SUMS.txt)
	{
		printf '{\n  "name": "MOSIP face liveness and PAD for the Registration Clients",\n'
		printf '  "version": "%s",\n  "commit": "%s",\n  "sourceDateEpoch": %s,\n' "$VERSION" "$COMMIT" "$EPOCH"
		printf '  "built": "%s",\n  "builtOn": "%s",\n  "artefacts": [\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$PLATFORM"
		local i=0 sha size
		for f in "${files[@]}"; do
			sha="$(grep " $f\$" "$OUT/SHA256SUMS.txt" | cut -d' ' -f1)"
			size="$(wc -c < "$OUT/$f" | tr -d ' ')"
			[ "$i" = 0 ] || printf ',\n'
			printf '    {"file": "%s", "bytes": %s, "sha256": "%s", "description": "%s"}' "$f" "$size" "$sha" "$(describe "$f")"
			i=$((i + 1))
		done
		printf '\n  ]\n}\n'
	} > "$OUT/manifest.json"
	log "wrote SHA256SUMS.txt and manifest.json (${#files[@]} artefacts)"
}

# ---------------------------------------------------------------- main

log "version $VERSION, commit ${COMMIT:0:12}, parts $PARTS, output $OUT"
wants engine && build_engine
wants desktop && build_desktop
wants android && build_android
wants mocksync && build_mocksync
wants mockmds && build_mockmds
write_checksums
[ "$KEEP_STAGING" = 1 ] || rm -rf "$STAGE"
log "done"
(cd "$OUT" && ls -l *.zip *.apk *.jar 2>/dev/null || true)
