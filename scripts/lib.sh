# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Shared helpers for the shell scripts in this folder. Source it after the script has set LOG_TAG:
#   LOG_TAG=release; . "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
# log MESSAGE prints "[tag HH:MM:SS] MESSAGE"; die MESSAGE prints "[tag] ERROR: MESSAGE" to stderr and exits 1.
log() { printf '[%s %s] %s\n' "$LOG_TAG" "$(date +%H:%M:%S)" "$*"; }
die() { printf '[%s] ERROR: %s\n' "$LOG_TAG" "$*" >&2; exit 1; }
