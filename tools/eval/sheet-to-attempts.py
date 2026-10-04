#!/usr/bin/env python3
"""Converts the recorder's results sheet (CSV) from a hands-on test session into attempt records for analyze.py.

The sheet has one row per attempt, filled in by the recorder while the tester uses the real client
(docs/eval/team-self-test.md). Columns:

  txn_id       transaction number, unique per platform (T001, T002, ...)
  platform     desktop | android
  subject_id   pseudonym of the person or of the artefact's source person (S1, S2, ...)
  kind         bona_fide | attack
  species      BONA_FIDE for bona fide rows; P1..P8 for attacks (docs/eval/protocol.md section 7)
  condition    C1..C5 for bona fide rows (lighting); empty for attacks
  workflow     REGISTRATION | LOGIN | ONBOARDING | ...
  attempt_no   1, 2, 3 within the transaction
  result       PASS | FAIL | NO_FACE   (what the client showed: accepted, rejected, or never found a usable face)
  reason       optional: the message on screen or the reason code from the diagnostic log
  notes        optional, free text (never names)
  match        optional: PASS | FAIL, the face matcher's result when the recorder saw it
  artefact_id  optional: label of the physical artefact used (A1, A2, ...), for counting artefacts

Liveness and the face matcher are recorded apart. A FAIL row with reason MATCH means liveness passed and the
matcher refused the face: it becomes a liveness ACCEPT with ``match_outcome`` REJECT, never a PAD rejection.
In login workflows (LOGIN..., ...AUTH...) a PASS row means both liveness and the matcher accepted, so its
``match_outcome`` is ACCEPT. Other rows carry no match result unless the ``match`` column gives one.
analyze.py computes IAPAR (impostor attacks accepted by liveness and the matcher) from these fields.

The last row of each transaction is its final attempt; the transaction is accepted by liveness if any
attempt passed liveness.

  python tools/eval/sheet-to-attempts.py results.csv --out attempts.jsonl
  python tools/eval/analyze.py attempts.jsonl --out report.md
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import OrderedDict
from pathlib import Path

SCHEMA = "liveness-eval-attempt/1"
OUTCOMES = {"PASS": "ACCEPT", "FAIL": "REJECT", "NO_FACE": "NO_RESPONSE"}
REQUIRED = ("txn_id", "platform", "subject_id", "kind", "species", "attempt_no", "result")
MATCH_REASON = "MATCH"
MATCH_RESULTS = {"PASS": "ACCEPT", "FAIL": "REJECT"}


def is_login_workflow(workflow):
    """Workflows that run the face matcher after liveness (operator or supervisor login)."""
    w = workflow.upper()
    return w.startswith("LOGIN") or "AUTH" in w


def liveness_and_match(row, line_no):
    """(liveness outcome, match outcome or None) for one sheet row."""
    result, reason = row["result"], (row.get("reason") or "").strip().upper()
    explicit = (row.get("match") or "").strip().upper()
    if explicit and explicit not in MATCH_RESULTS:
        raise ValueError(f"line {line_no}: match {row['match']!r}, expected PASS, FAIL or empty")
    if result == "FAIL" and reason == MATCH_REASON:
        if explicit == "PASS":
            raise ValueError(f"line {line_no}: reason MATCH but match PASS")
        return "ACCEPT", "REJECT"
    liveness = OUTCOMES[result]
    if explicit:
        if liveness != "ACCEPT":
            raise ValueError(f"line {line_no}: match result given but liveness did not pass")
        return liveness, MATCH_RESULTS[explicit]
    if result == "PASS" and is_login_workflow(row.get("workflow") or ""):
        return liveness, "ACCEPT"
    return liveness, None


def convert(rows, run_id, model_set="not recorded (manual sheet)"):
    txns = OrderedDict()
    for no, row in enumerate(rows, 2):
        row = {k.strip(): (v or "").strip() for k, v in row.items() if k}
        missing = [c for c in REQUIRED if not row.get(c)]
        if missing:
            raise ValueError(f"line {no}: missing {', '.join(missing)}")
        result = row["result"].upper()
        if result not in OUTCOMES:
            raise ValueError(f"line {no}: result {row['result']!r}, expected PASS, FAIL or NO_FACE")
        kind = row["kind"].lower()
        if kind not in ("bona_fide", "attack"):
            raise ValueError(f"line {no}: kind {row['kind']!r}, expected bona_fide or attack")
        row["result"], row["kind"] = result, kind
        row["_liveness"], row["_match"] = liveness_and_match(row, no)
        txns.setdefault((row["platform"].lower(), row["txn_id"]), []).append(row)

    out = []
    for (platform, txn_id), attempts in txns.items():
        attempts.sort(key=lambda r: int(r["attempt_no"]))
        txn_outcome = "ACCEPT" if any(r["_liveness"] == "ACCEPT" for r in attempts) else \
            "REJECT" if any(r["_liveness"] == "REJECT" for r in attempts) else "NO_RESPONSE"
        for i, r in enumerate(attempts):
            outcome = r["_liveness"]
            out.append({
                "schema": SCHEMA,
                "run_id": f"{run_id}-{platform}",
                "txn_id": txn_id,
                "scenario": f"{platform}-{r['species'].upper()}",
                "kind": r["kind"],
                "species": r["species"].upper(),
                "subject_id": r["subject_id"],
                "condition": r.get("condition") or None,
                "split": "team",
                "workflow": (r.get("workflow") or "REGISTRATION").upper(),
                "attempt_no": int(r["attempt_no"]),
                "last_in_txn": i == len(attempts) - 1,
                "txn_outcome": txn_outcome,
                "outcome": outcome,
                "failure_reason": r.get("reason") or None if outcome != "ACCEPT" else None,
                "reason_codes": [r["reason"]] if r.get("reason") else [],
                "layer": None,
                "no_response_reason": r.get("reason") or None if outcome == "NO_RESPONSE" else None,
                "method": None,
                "active_triggered": None,
                "passive_score": None,
                "challenges": [],
                "t_passive_ms": None,
                "t_total_ms": None,
                "frame_ms": {},
                "model_set_sha256": model_set,
                "config_hash": "not recorded (manual sheet)",
                "platform": {"client": platform, "source": "manual sheet"},
                "notes": r.get("notes") or None,
                "match_outcome": r["_match"],
                "artefact_id": r.get("artefact_id") or None,
            })
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sheet", type=Path, help="results CSV")
    ap.add_argument("--out", type=Path, required=True, help="attempts.jsonl to write")
    ap.add_argument("--run-id", default=None, help="run id (default: the sheet's file name)")
    ap.add_argument("--model-set", default="not recorded (manual sheet)",
                    help="model bundle tested, for example the version shown in the client log")
    args = ap.parse_args(argv)
    with open(args.sheet, newline="", encoding="utf-8-sig") as f:
        records = convert(csv.DictReader(f), args.run_id or args.sheet.stem, args.model_set)
    with open(args.out, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r) + "\n")
    print(f"{len(records)} attempts written to {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
