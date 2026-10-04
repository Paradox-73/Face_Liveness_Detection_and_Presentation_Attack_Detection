#!/usr/bin/env bash
# Builds three sample model bundles from models/ so that the offline model update (import of a signed
# bundle, signature and hash checks, refusal of older versions) can be tried without the team's private key.
#
#   valid     models/ at the shipped bundle version, manifest re-signed with the key you pass
#   tampered  the valid bundle with one byte of MiniFASNetV2.onnx changed; manifest and signature are
#             untouched, so the signature still verifies but the model hash no longer matches
#   older     models/ with the previous manifest from git history (an older bundle version), hashes
#             refreshed, signed with the same key; its signature is valid but --min-version refuses it
#
# Each bundle folder also holds SHA256SUMS (sha256sum format, written for the valid files; the tampered
# bundle keeps the valid sums, so `sha256sum -c SHA256SUMS` fails there).
#
# Usage:
#   bash scripts/make-sample-model-bundles.sh --key PRIVATE.pem --out DIR
#   bash scripts/make-sample-model-bundles.sh --self-test
#
# Options:
#   --key FILE     PKCS#8 PEM private key that signs the manifests (required unless --self-test).
#                  Use the team's publishing key; the development key is for the committed models/ only.
#   --out DIR      folder for the three bundles (default: ./sample-model-bundles); must not exist yet
#   --public FILE  public half of --key: after building, the script verifies the three bundles with it
#   --self-test    make a throwaway key with ModelSigner keygen in a temporary folder outside the
#                  repository, build the bundles there and check that valid verifies, tampered fails and
#                  older is refused by --min-version; nothing is left behind
#
# Needs: bash, java (JDK 11+), git, sha256sum, and the engine jars (mvn -f liveness/pom.xml package):
# liveness/liveness-core/target/liveness-core-*.jar and liveness/liveness-onnx/target/liveness-onnx-*.jar,
# or LIVENESS_CORE_JAR and LIVENESS_ONNX_JAR.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
MODELS="$REPO/models"
TAMPER_FILE="MiniFASNetV2.onnx"
TAMPER_OFFSET=4096

KEY=""; OUT=""; PUBLIC=""; SELF_TEST=0
while [ $# -gt 0 ]; do
    case "$1" in
        --key) KEY="${2:?--key needs a file}"; shift 2 ;;
        --out) OUT="${2:?--out needs a folder}"; shift 2 ;;
        --public) PUBLIC="${2:?--public needs a file}"; shift 2 ;;
        --self-test) SELF_TEST=1; shift ;;
        -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
done

native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
fail() { echo "error: $*" >&2; exit 1; }

first_jar() { ls "$1"/$2 2>/dev/null | grep -v -- '-models\|-sources\|-javadoc\|-tests' | head -1 || true; }
CORE_JAR="${LIVENESS_CORE_JAR:-$(first_jar "$REPO/liveness/liveness-core/target" 'liveness-core-*.jar')}"
ONNX_JAR="${LIVENESS_ONNX_JAR:-$(first_jar "$REPO/liveness/liveness-onnx/target" 'liveness-onnx-*.jar')}"
[ -f "$CORE_JAR" ] && [ -f "$ONNX_JAR" ] || fail "engine jars not found: run mvn -f liveness/pom.xml package, or set LIVENESS_CORE_JAR and LIVENESS_ONNX_JAR"
SEP=":"; command -v cygpath >/dev/null 2>&1 && SEP=";"
CP="$(native "$CORE_JAR")$SEP$(native "$ONNX_JAR")"

signer() { java -cp "$CP" io.mosip.liveness.onnx.tools.ModelSigner "$@"; }

manifest_version() { sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1; }

[ -f "$MODELS/manifest.json" ] || fail "missing $MODELS/manifest.json"
SHIPPED="$(manifest_version < "$MODELS/manifest.json")"
[ -n "$SHIPPED" ] || fail "cannot read the bundle version from models/manifest.json"

# Previous manifest: the newest revision in git history whose bundle version is below the shipped one.
previous_manifest() {
    local rev v
    for rev in $(git -C "$REPO" log --format=%H -- models/manifest.json); do
        v="$(git -C "$REPO" show "$rev:models/manifest.json" | manifest_version)"
        if [ -n "$v" ] && [ "$v" != "$SHIPPED" ] && [ "$(printf '%s\n%s\n' "$v" "$SHIPPED" | sort -V | head -1)" = "$v" ]; then
            echo "$rev $v"; return 0
        fi
    done
    return 1
}

copy_files() { # DEST: the model files named in models/manifest.json plus the sources note
    local dest=$1 f
    mkdir -p "$dest"
    for f in $(sed -n 's/^[[:space:]]*"file"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$MODELS/manifest.json"); do
        cp "$MODELS/$f" "$dest/$f"
    done
    [ ! -f "$MODELS/SOURCES.md" ] || cp "$MODELS/SOURCES.md" "$dest/SOURCES.md"
}

write_sums() { # DIR
    ( cd "$1" && sha256sum $(ls | grep -v '^SHA256SUMS$') > SHA256SUMS )
}

flip_byte() { # FILE OFFSET
    local old new
    old=$(dd if="$1" bs=1 skip="$2" count=1 2>/dev/null | od -An -tu1 | tr -d ' ')
    new=$(( old ^ 255 ))
    printf "\\$(printf '%03o' "$new")" | dd of="$1" bs=1 seek="$2" conv=notrunc 2>/dev/null
}

build() { # KEY OUT
    local key=$1 out=$2 prev rev prevv
    [ ! -e "$out" ] || fail "$out already exists; pass a new folder with --out"
    prev="$(previous_manifest)" || fail "no older manifest version in the git history of models/manifest.json"
    rev="${prev% *}"; prevv="${prev#* }"
    mkdir -p "$out"

    echo "== valid: bundle $SHIPPED"
    copy_files "$out/valid"
    cp "$MODELS/manifest.json" "$out/valid/manifest.json"
    signer sign --bundle "$(native "$out/valid")" --private "$(native "$key")"
    write_sums "$out/valid"

    echo "== tampered: bundle $SHIPPED with one byte of $TAMPER_FILE changed"
    cp -r "$out/valid" "$out/tampered"
    flip_byte "$out/tampered/$TAMPER_FILE" "$TAMPER_OFFSET"

    echo "== older: bundle $prevv (manifest from git revision ${rev:0:8})"
    copy_files "$out/older"
    git -C "$REPO" show "$rev:models/manifest.json" > "$out/older/manifest.json"
    signer update --bundle "$(native "$out/older")" > /dev/null
    signer sign --bundle "$(native "$out/older")" --private "$(native "$key")"
    write_sums "$out/older"
}

verify_all() { # OUT PUBLIC: checks the three outcomes; returns 1 when one is wrong
    local out=$1 pub=$2 bad=0 log
    log="$(mktemp)"
    check() { # DESCRIPTION EXPECTED_EXIT COMMAND...
        local what=$1 want=$2 got=0; shift 2
        "$@" > "$log" 2>&1 || got=$?
        if [ "$got" -eq "$want" ]; then
            echo "ok    $what: $(head -1 "$log")"
        else
            echo "WRONG $what: exit $got, expected $want"; cat "$log"; bad=1
        fi
    }
    local v t o; v="$(native "$out/valid")"; t="$(native "$out/tampered")"; o="$(native "$out/older")"
    local p; p="$(native "$pub")"
    check "valid verifies at --min-version $SHIPPED" 0 signer verify --bundle "$v" --public "$p" --min-version "$SHIPPED"
    check "tampered fails (model hash)" 1 signer verify --bundle "$t" --public "$p" --min-version "$SHIPPED"
    local prevv; prevv="$(previous_manifest | cut -d' ' -f2)"
    check "older signature is valid at --min-version $prevv" 0 signer verify --bundle "$o" --public "$p" --min-version "$prevv"
    check "older refused by --min-version $SHIPPED" 1 signer verify --bundle "$o" --public "$p" --min-version "$SHIPPED"
    check "valid sha256sum -c" 0 bash -c "cd '$out/valid' && sha256sum -c SHA256SUMS"
    check "tampered sha256sum -c fails" 1 bash -c "cd '$out/tampered' && sha256sum -c SHA256SUMS"
    grep -q 'FAILED' "$log" || { echo "WRONG tampered sha256sum output has no FAILED line"; bad=1; }
    rm -f "$log"
    return $bad
}

if [ "$SELF_TEST" -eq 1 ]; then
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    case "$(cd "$TMP" && pwd)" in "$REPO"/*) fail "temporary folder is inside the repository" ;; esac
    echo "self-test in $TMP (removed afterwards)"
    signer keygen --private "$(native "$TMP/test-key.pem")" --public "$(native "$TMP/test-key.pub.pem")" --bits 2048
    build "$TMP/test-key.pem" "$TMP/bundles"
    verify_all "$TMP/bundles" "$TMP/test-key.pub.pem" || fail "self-test failed"
    echo "self-test passed"
    exit 0
fi

[ -n "$KEY" ] || fail "--key PRIVATE.pem is required (or --self-test)"
[ -f "$KEY" ] || fail "key file not found: $KEY"
OUT="${OUT:-$PWD/sample-model-bundles}"
build "$KEY" "$OUT"
echo "bundles in $OUT: valid tampered older"
if [ -n "$PUBLIC" ]; then
    verify_all "$OUT" "$PUBLIC" || fail "verification with $PUBLIC failed"
else
    echo "not verified (pass --public with the public half of the key to check the three outcomes)"
fi
