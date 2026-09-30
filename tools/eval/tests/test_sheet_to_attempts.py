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
