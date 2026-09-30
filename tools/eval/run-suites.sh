#!/usr/bin/env bash
# Runs the liveness scenario suites with the real engine and models, then writes one report per suite.
#
#   bash tools/eval/run-suites.sh [OUT_DIR] [SUITE...]
#
# Suites (default: shipped legacy legacy-strict latency):
#   shipped        shipped configuration: registration, operator onboarding and login always run challenges,
#                  30 passive frames; blur threshold 40 from the signed model bundle
#   legacy         the configuration shipped before 2026-09-30 (passive first everywhere, 8 passive frames,
#                  blur threshold 10), on the current engine
#   legacy-strict  legacy with challenges forced for registration (the former "strict" suite)
#   latency        bona fide scenarios with one ONNX Runtime thread, for per-frame timing
#
# LIVENESS_RUNNER_JAR selects another runner jar (for example a copy built from an older engine, to compare).
# Needs: a JDK 11+ on PATH (java), Python 3 with PyYAML (and matplotlib for the DET plot), the frame packs
# (tools/eval/fetch-frame-packs.py, then tools/eval/make-attack-packs.py) and the runner jar
# (mvn -f tools/eval/scenario-runner/pom.xml package). OUT_DIR defaults to ../run/eval/<UTC time> next to
# the repository; results never go into git by themselves.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
PACKS="${LIVENESS_FRAME_PACKS:-$REPO/../run/frame-packs}"
OUT="${1:-$REPO/../run/eval/$(date -u +%Y%m%dT%H%M%SZ)}"
shift || true
SUITES=("$@")
[ ${#SUITES[@]} -gt 0 ] || SUITES=(shipped legacy legacy-strict latency)
JAR="${LIVENESS_RUNNER_JAR:-$REPO/tools/eval/scenario-runner/target/scenario-runner.jar}"
PYTHON="${PYTHON:-python}"

# Java on Windows needs Windows paths; elsewhere the path is used as is.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

[ -f "$JAR" ] || { echo "missing $JAR: run mvn -f tools/eval/scenario-runner/pom.xml package" >&2; exit 2; }
[ -d "$PACKS" ] || { echo "missing frame packs in $PACKS: run tools/eval/fetch-frame-packs.py" >&2; exit 2; }
mkdir -p "$OUT"

run_suite() {
    local name=$1; shift
    echo "== suite $name -> $OUT/$name"
    java -Xmx512m -jar "$(native "$JAR")" run --packs "$(native "$PACKS")" \
        --scenarios "$(native "$REPO/eval/scenarios")" --out "$(native "$OUT/$name")" --run-id "$name" --no-fail "$@" \
        > "$OUT/$name.log" 2>&1
    tail -n 8 "$OUT/$name.log"
    "$PYTHON" "$REPO/tools/eval/analyze.py" "$OUT/$name/attempts.jsonl" --out "$OUT/$name/report.md"
}

# The configuration shipped before the calibration was adopted (docs/eval/calibration-notes.md, section 6).
LEGACY=(--config policy.registration=PASSIVE_THEN_ACTIVE --config policy.login_auth=PASSIVE_THEN_ACTIVE
        --config policy.onboard_auth=PASSIVE_THEN_ACTIVE --config passive.min_frames=8
        --engine-param quality.min_sharpness=10)

for suite in "${SUITES[@]}"; do
    case "$suite" in
        shipped)  run_suite shipped ;;
        legacy)   run_suite legacy "${LEGACY[@]}" ;;
        legacy-strict) run_suite legacy-strict "${LEGACY[@]}" --config policy.registration=ALWAYS_ACTIVE ;;
        latency)  run_suite latency --threads 1 --filter '^bf-s[123]-(registration|active)$' ;;
        *) echo "unknown suite: $suite" >&2; exit 2 ;;
    esac
done
echo "reports: $OUT/<suite>/report.md"
