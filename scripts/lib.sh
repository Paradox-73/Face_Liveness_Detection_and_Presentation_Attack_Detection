# Shared helpers for the shell scripts in this folder. Source it after the script has set LOG_TAG:
#   LOG_TAG=release; . "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
# log MESSAGE prints "[tag HH:MM:SS] MESSAGE"; die MESSAGE prints "[tag] ERROR: MESSAGE" to stderr and exits 1.
log() { printf '[%s %s] %s\n' "$LOG_TAG" "$(date +%H:%M:%S)" "$*"; }
die() { printf '[%s] ERROR: %s\n' "$LOG_TAG" "$*" >&2; exit 1; }
