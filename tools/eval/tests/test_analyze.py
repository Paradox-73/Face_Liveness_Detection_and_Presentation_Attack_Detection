"""Tests for tools/eval/analyze.py: exact intervals, outcome counting, DET and the report."""

import json

import pytest

import analyze


# ------------------------------------------------------------------ intervals


@pytest.mark.parametrize("n, expected", [(30, 0.0950), (40, 0.0722), (60, 0.0487)])
def test_zero_error_one_sided_bounds_match_the_protocol(n, expected):
    # docs/eval/protocol.md quotes these bounds: 0/30 -> 9.5%, 0/40 -> 7.2%, 0/60 -> 4.9%.
    assert analyze.one_sided_upper(0, n) == pytest.approx(expected, abs=5e-4)


def test_zero_error_two_sided_upper_bound_is_larger_than_one_sided():
    lo, hi = analyze.clopper_pearson(0, 60)
    assert lo == 0.0
    assert hi == pytest.approx(0.0596, abs=5e-4)
    assert hi > analyze.one_sided_upper(0, 60)


@pytest.mark.parametrize("x, n, lo, hi", [
    (5, 20, 0.0866, 0.4910),   # textbook values of the exact interval
    (1, 10, 0.0025, 0.4450),
    (10, 10, 0.6915, 1.0),
])
def test_clopper_pearson_known_values(x, n, lo, hi):
    got = analyze.clopper_pearson(x, n)
    assert got[0] == pytest.approx(lo, abs=5e-4)
    assert got[1] == pytest.approx(hi, abs=5e-4)


def test_interval_is_uninformative_without_decisions():
    assert analyze.clopper_pearson(0, 0) == (0.0, 1.0)
    assert analyze.Rate(0, 0).text().startswith("n/a")


def test_interval_rejects_impossible_counts():
    with pytest.raises(ValueError):
        analyze.clopper_pearson(3, 2)


def test_large_n_does_not_overflow():
    lo, hi = analyze.clopper_pearson(50, 5000)
    assert 0.007 < lo < 0.01 < hi < 0.014


def test_one_sided_bound_with_errors_is_below_two_sided_upper():
    assert analyze.one_sided_upper(2, 60) < analyze.clopper_pearson(2, 60)[1]


def test_attacker_success_over_retries():
    assert analyze.attacker_success(0.1, 3) == pytest.approx(0.271)
    assert analyze.attacker_success(0.0, 3) == 0.0


def test_percentile_is_nearest_rank():
    assert analyze.percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 95) == 10
    assert analyze.percentile([1, 2, 3, 4], 50) == 2
    assert analyze.percentile([], 50) is None


def test_bootstrap_needs_two_subjects_and_is_deterministic():
    assert analyze.bootstrap_rate({"S1": (1, 10)}) is None
    groups = {"S1": (0, 10), "S2": (5, 10), "S3": (1, 10)}
    a = analyze.bootstrap_rate(groups, resamples=500)
    assert a == analyze.bootstrap_rate(groups, resamples=500)
    assert 0.0 <= a[0] <= 0.2 <= a[1] <= 0.5


# ------------------------------------------------------------------ counting


def rec(txn, kind, outcome, species="BF", layer="none", last=True, txn_outcome=None, subject="S1", condition="C1",
        passive=None, scenario="sc", **extra):
    d = {"schema": analyze.SCHEMA, "run_id": "r1", "txn_id": txn, "scenario": scenario, "kind": kind,
         "species": species, "subject_id": subject, "condition": condition, "outcome": outcome,
         "txn_outcome": (txn_outcome or outcome) if last else None, "last_in_txn": last, "layer": layer,
         "passive_score": passive, "active_triggered": False, "challenges": [], "t_total_ms": 1000,
         "t_passive_ms": 500, "frame_ms": {"n": 10, "p50": 30.0, "p95": 50.0}, "expected_outcome": None,
         "expected_layers": [], "reason_codes": []}
    d.update(extra)
    return analyze.Record(d)


def sample():
    return [
        # attack species A: txn 1 rejected twice then accepted; txn 2 rejected once, then no response
        rec("a#1", "attack", "REJECT", "SYN-A", "passive_pad", last=False),
        rec("a#1", "attack", "REJECT", "SYN-A", "challenge", last=False),
        rec("a#1", "attack", "ACCEPT", "SYN-A", "none", txn_outcome="ACCEPT"),
        rec("a#2", "attack", "REJECT", "SYN-A", "integrity", last=False),
        rec("a#2", "attack", "NO_RESPONSE", "SYN-A", "quality", txn_outcome="REJECT"),
        # attack species B: always rejected
        rec("b#1", "attack", "REJECT", "SYN-B", "passive_pad"),
        # bona fide: one accepted, one rejected then accepted, one no response
        rec("f#1", "bona_fide", "ACCEPT", subject="S1"),
        rec("f#2", "bona_fide", "REJECT", layer="challenge", last=False, subject="S2", condition="C2"),
        rec("f#2", "bona_fide", "ACCEPT", subject="S2", condition="C2", txn_outcome="ACCEPT"),
        rec("f#3", "bona_fide", "NO_RESPONSE", layer="quality", subject="S3"),
        # quality scenario, excluded from both rates
        rec("o#1", "other", "NO_RESPONSE", "OTHER", "quality", scenario="other-no-face"),
    ]


def test_attempt_level_counts():
    m = analyze.compute(sample(), "attempt")
    a = m.species["SYN-A"]
    assert (a.accept, a.reject, a.no_response) == (1, 3, 1)
    assert a.apcer().value == pytest.approx(0.25)          # 1 accepted of 4 decisions
    assert a.nrr().value == pytest.approx(0.2)             # 1 of 5 presentations
    assert a.layers == {"passive_pad": 1, "challenge": 1, "integrity": 1, "quality": 1}
    assert m.bona_fide.bpcer().value == pytest.approx(1 / 3)
    assert m.bona_fide.nrr().value == pytest.approx(1 / 4)
    assert set(m.other) == {"other-no-face"}


def test_apcer_max_is_the_worst_species():
    name, rate = analyze.compute(sample(), "attempt").apcer_max()
    assert name == "SYN-A"
    assert rate.value == pytest.approx(0.25)


def test_transaction_level_uses_the_transaction_outcome():
    m = analyze.compute(sample(), "transaction")
    a = m.species["SYN-A"]
    assert (a.accept, a.reject, a.no_response) == (1, 1, 0)
    assert m.bona_fide.bpcer().value == 0.0
    assert m.bona_fide.no_response == 1
    assert m.bona_fide_by_condition["C2"].accept == 1


def test_unknown_level_is_rejected():
    with pytest.raises(ValueError):
        analyze.compute(sample(), "session")


def test_load_records_rejects_foreign_schema(tmp_path):
    good = tmp_path / "a.jsonl"
    good.write_text(json.dumps(sample()[0].raw) + "\n\n", encoding="utf-8")
    assert len(analyze.load_records([good])) == 1
    bad = tmp_path / "b.jsonl"
    bad.write_text(json.dumps({"schema": "other/1"}) + "\n", encoding="utf-8")
    with pytest.raises(ValueError):
        analyze.load_records([bad])


# ------------------------------------------------------------------ DET and report


def test_det_points_sweep_the_threshold():
    pts = analyze.det_points([90, 80, 70, 60], [10, 30, 85])
    by_t = {t: (a, b) for t, a, b in pts}
    assert by_t[0.0] == (1.0, 0.0)          # everything accepted
    assert by_t[101.0] == (0.0, 1.0)        # everything rejected
    assert by_t[80] == (pytest.approx(1 / 3), 0.5)


def test_bpcer_at_apcer_picks_the_lowest_bpcer_that_meets_the_target():
    pts = analyze.det_points([90, 80, 70, 60], [10, 30, 85])
    assert analyze.bpcer_at_apcer(pts, 0.05) == (0.75, 90)   # only 90 and above stop the 85
    assert analyze.bpcer_at_apcer([(50, 0.5, 0.0)], 0.05) is None


def test_report_contains_the_headline_figures(tmp_path):
    records = sample()
    records[0].raw["passive_score"] = 40
    records[6].raw["passive_score"] = 95
    det = tmp_path / "det.png"
    ok = analyze.plot_det(records, det, 80)
    report = analyze.build_report(records, det.name, ok, 3, 80)
    assert "APCER_max" in report and "SYN-A" in report
    assert "one-sided 95% upper bound" in report          # SYN-B has zero errors
    assert "not an ISO/IEC 30107-3 evaluation" in report
    assert "other-no-face" in report
    if ok:
        assert det.stat().st_size > 1000


def test_main_writes_report(tmp_path):
    f = tmp_path / "attempts.jsonl"
    f.write_text("".join(json.dumps(r.raw) + "\n" for r in sample()), encoding="utf-8")
    out = tmp_path / "report.md"
    assert analyze.main([str(f), "--out", str(out)]) == 0
    text = out.read_text(encoding="utf-8")
    assert text.startswith("# Liveness technology regression report")
    assert "Evaluation type (b)" in text
