#!/usr/bin/env bash
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Builds and tests everything that CI (.github/workflows/ci.yml) runs without a device, in order, and stops at the
# first failed step.
#
#   scripts/test-all.sh [--only engine,mocksync,mockmds,runner,eval]
#
# Steps and the JDK each one needs (JAVA11_HOME and JAVA21_HOME must be set):
#   engine     liveness/ build and tests, then installed into ~/.m2 (JDK 21)
#   mocksync   tools/mock-sync tests (JDK 11)
#   mockmds    devices/mock-mds build and tests (JDK 21)
#   runner     tools/eval/scenario-runner build and tests (JDK 11; needs the engine step first)
#   eval       python -m pytest tools/eval/tests (Python 3 with tools/eval/requirements.txt)
# The desktop client and Android client tests are not included: see docs/test-report/README.md for their commands.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_TAG=test-all; . "$REPO_ROOT/scripts/lib.sh"
ONLY="engine,mocksync,mockmds,runner,eval"
while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY="$2"; shift 2 ;;
    -h|--help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
done

for s in ${ONLY//,/ }; do wants_known="engine mocksync mockmds runner eval"; case " $wants_known " in *" $s "*) ;; *) die "unknown step $s" ;; esac; done
wants() { case ",$ONLY," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }
jdk() { [ -n "${!1:-}" ] || die "$1 is not set"; export JAVA_HOME="${!1}"; export PATH="$JAVA_HOME/bin:$PATH"; }

if wants engine; then
  log "engine"; (jdk JAVA21_HOME; mvn -B -f "$REPO_ROOT/liveness/pom.xml" install -Dliveness.benchmark.frames=0 ${MAVEN_ARGS:-})
fi
if wants mocksync; then
  log "mocksync"; (jdk JAVA11_HOME; mvn -B -f "$REPO_ROOT/tools/mock-sync/pom.xml" verify ${MAVEN_ARGS:-})
fi
if wants mockmds; then
  log "mockmds"; (jdk JAVA21_HOME; mvn -B -f "$REPO_ROOT/devices/mock-mds/pom.xml" package -Dgpg.skip=true ${MAVEN_ARGS:-})
fi
if wants runner; then
  log "runner"; (jdk JAVA11_HOME; mvn -B -f "$REPO_ROOT/tools/eval/scenario-runner/pom.xml" package ${MAVEN_ARGS:-})
fi
if wants eval; then
  log "eval"; python -m pytest -q "$REPO_ROOT/tools/eval/tests"
fi
log "all selected steps passed: $ONLY"
