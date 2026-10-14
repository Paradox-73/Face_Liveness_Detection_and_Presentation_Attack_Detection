#!/usr/bin/env python3
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Computes ISO/IEC 30107-3-style metrics from scenario-runner attempt records and writes a Markdown report.

Input: one or more ``attempts.jsonl`` files written by tools/eval/scenario-runner or by
tools/eval/sheet-to-attempts.py (schema ``liveness-eval-attempt/1``, one JSON object per attempt, no
images). Output: a Markdown report and, when matplotlib is installed, a DET plot of the passive PAD score.

  python tools/eval/analyze.py RUN/attempts.jsonl --out RUN/report.md --det RUN/det.png

Classification (docs/eval/protocol.md): every attempt is ACCEPT, REJECT or NO_RESPONSE. APCER and BPCER
use only attempts (or transactions) that produced a decision; the others count toward APNRR and BPNRR.

  APCER(species) = attacks of that species accepted / attacks of that species with a decision
  APCER_max      = the highest APCER over the species (ISO/IEC 30107-3 "APCER_AP")
  BPCER          = bona fide presentations rejected / bona fide presentations with a decision
  APNRR / BPNRR  = presentations without a decision / all presentations of that kind
  IAPAR(species) = impostor attack presentations accepted by liveness and by the face matcher /
                   impostor attack presentations with a decision (only for records with ``match_outcome``)

Counting units. A transaction is one capture until the first accepted attempt or the last allowed attempt;
the runner and the clients both stop at the first pass. The report therefore leads with APCER per species
per transaction and on the first attempt (attempt 1 of every transaction). Per-attempt figures dilute a
species that passes at once with the extra rejected attempts of transactions that never pass, so they and
all pooled figures are secondary.

Every table is grouped by workflow (registration, operator login, ...). Species named with
``--genuine-species`` (default SYN-A9, a genuine clip of the wrong action) are bona fide faces, not
artefacts: they appear in their own table and are left out of APCER, BPCER and the DET curve.

Intervals are exact Clopper-Pearson at 95%. For zero observed errors the report also gives the one-sided
95% upper bound (1 - 0.05^(1/n)) and labels which bound is shown. Attempt and transaction intervals treat
every presentation as independent; replays of the same artefact are not, so the report labels them and
adds an interval over source artefacts (``artefact_id`` when recorded, else the source subject).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# The code lives in sibling modules; main() and the tests reach these names through ``import analyze``.
from metrics import (GENUINE_WRONG_ACTION_SPECIES, bpcer_at_apcer, compute, compute_full_system,  # noqa: F401
                     det_points, det_points_worst_species, has_match_results, scores_for_det)
from records import SCHEMA, Record, by_workflow, load_records  # noqa: F401
from report import build_report, plot_det
from stats import (Rate, attacker_success, bootstrap_rate, clopper_pearson, one_sided_upper,  # noqa: F401
                   percentile)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="+", type=Path, help="attempts.jsonl files")
    ap.add_argument("--out", type=Path, help="Markdown report file (default: print)")
    ap.add_argument("--det", type=Path, help="DET plot PNG file (default: det.png next to --out)")
    ap.add_argument("--retries", type=int, default=3, help="attempts per transaction for attacker success")
    ap.add_argument("--threshold", type=float, default=80.0, help="passive threshold marked on the DET plot")
    ap.add_argument("--det-conditions", nargs="*", default=["C1"],
                    help="bona fide conditions on the DET plot (default C1; the option alone = all)")
    ap.add_argument("--genuine-species", nargs="*", default=list(GENUINE_WRONG_ACTION_SPECIES),
                    help="attack species that show a genuine face (wrong-action clips), kept out of APCER, "
                         "BPCER and the DET (default SYN-A9; the option alone = none)")
    args = ap.parse_args(argv)
    records = load_records(args.inputs)
    if not records:
        print("no attempt records", file=sys.stderr)
        return 2
    genuine = tuple(args.genuine_species)
    det = args.det or (args.out.with_name("det.png") if args.out else None)
    conditions = args.det_conditions or None
    det_ok = plot_det(records, det, args.threshold, conditions, genuine) if det else False
    det_ref = (det.name if args.out and det and det.parent == args.out.parent else str(det)) if det else None
    report = build_report(records, det_ref, det_ok, args.retries, args.threshold, conditions, genuine)
    if args.out:
        args.out.write_text(report, encoding="utf-8")
        print(f"wrote {args.out}" + (f" and {det}" if det_ok else ""))
    else:
        print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
