"""Tests for tools/eval/sheet-to-attempts.py: the hand-filled results sheet becomes analysable attempt records."""

import csv
import importlib.util
from pathlib import Path

import pytest

import analyze

_EVAL = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("sheet_to_attempts", _EVAL / "sheet-to-attempts.py")
sheet = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sheet)

EXAMPLE = _EVAL.parents[1] / "docs" / "eval" / "self-test-sheet.csv"


def _rows():
    with open(EXAMPLE, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def test_example_sheet_converts_and_marks_last_attempt():
    recs = sheet.convert(_rows(), "t")
    assert len(recs) == 6
    t002 = [r for r in recs if r["txn_id"] == "T002"]
    assert [r["last_in_txn"] for r in t002] == [False, False, True]
    assert {r["txn_outcome"] for r in t002} == {"REJECT"}
    t003 = [r for r in recs if r["txn_id"] == "T003"]
    assert [r["outcome"] for r in t003] == ["NO_RESPONSE", "ACCEPT"]
    assert t003[-1]["txn_outcome"] == "ACCEPT"


def test_converted_records_feed_the_live_report():
    recs = [analyze.Record(r) for r in sheet.convert(_rows(), "t")]
    report = analyze.build_report(recs, None, False, 3, None)
    assert report.startswith("# Liveness live test report")
    assert "| P1 | 3 | 0 | 3 | 0 |" in report


@pytest.mark.parametrize("field, value", [("result", "MAYBE"), ("kind", "print"), ("subject_id", "")])
def test_bad_rows_are_refused_with_the_line_number(field, value):
    rows = _rows()
    rows[1][field] = value
    with pytest.raises(ValueError, match="line 3"):
        sheet.convert(rows, "t")


def _row(txn, attempt, result, reason="", kind="attack", species="P1", workflow="LOGIN", **extra):
    row = {"txn_id": txn, "platform": "desktop", "subject_id": "S1", "kind": kind, "species": species,
           "condition": "" if kind == "attack" else "C1", "workflow": workflow, "attempt_no": str(attempt),
           "result": result, "reason": reason, "notes": ""}
    row.update(extra)
    return row


def test_match_failure_is_a_liveness_accept_not_a_pad_rejection():
    recs = sheet.convert([_row("T1", 1, "FAIL", "MATCH"), _row("T1", 2, "FAIL", "match")], "t")
    assert [r["outcome"] for r in recs] == ["ACCEPT", "ACCEPT"]
    assert [r["match_outcome"] for r in recs] == ["REJECT", "REJECT"]
    assert recs[-1]["txn_outcome"] == "ACCEPT"
    assert recs[0]["failure_reason"] is None and recs[0]["reason_codes"] == ["MATCH"]
    m = analyze.compute([analyze.Record(r) for r in recs], "transaction")
    assert m.species["P1"].accept == 1                      # liveness let the attack through
    species, _ = analyze.compute_full_system([analyze.Record(r) for r in recs])
    assert species["P1"].accept_rate().value == 0.0         # the matcher stopped it


def test_login_pass_means_both_accepted_and_other_rows_carry_no_match():
    recs = sheet.convert([_row("T1", 1, "PASS"), _row("T2", 1, "FAIL", "PAD"),
                          _row("T3", 1, "PASS", workflow="REGISTRATION"),
                          _row("T4", 1, "PASS", workflow="SUPERVISOR_AUTH")], "t")
    assert [r["match_outcome"] for r in recs] == ["ACCEPT", None, None, "ACCEPT"]
    assert recs[1]["outcome"] == "REJECT"


def test_explicit_match_column_and_artefact_id():
    recs = sheet.convert([_row("T1", 1, "PASS", workflow="REGISTRATION", match="FAIL", artefact_id="A2")], "t")
    assert recs[0]["match_outcome"] == "REJECT" and recs[0]["artefact_id"] == "A2"
    plain = sheet.convert([_row("T1", 1, "FAIL", "PAD", workflow="REGISTRATION")], "t")
    c = analyze.compute([analyze.Record(r) for r in plain], "transaction").species["P1"]
    assert c.artefact_text() == "- / 1"                     # artefact not recorded, not "0 artefacts"
    with pytest.raises(ValueError, match="line 2"):
        sheet.convert([_row("T1", 1, "FAIL", "PAD", match="PASS")], "t")
    with pytest.raises(ValueError, match="line 2"):
        sheet.convert([_row("T1", 1, "PASS", match="MAYBE")], "t")


def test_live_report_gives_iapar_for_login_rows():
    rows = [_row("T1", 1, "FAIL", "MATCH"), _row("T1", 2, "PASS"),
            _row("T2", 1, "FAIL", "PAD"), _row("T2", 2, "FAIL", "PAD"),
            _row("T3", 1, "PASS", kind="bona_fide", species="BONA_FIDE")]
    recs = [analyze.Record(r) for r in sheet.convert(rows, "t")]
    report = analyze.build_report(recs, None, False, 3, None)
    section = report[report.index("## Full-system results"):report.index("## Per scenario")]
    assert "| login | P1 | 2 | 50.0% (1/2;" in section
    assert "| login | 1 | 0.0% (0/1;" in section
