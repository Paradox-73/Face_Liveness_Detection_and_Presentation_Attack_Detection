# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
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


# ------------------------------------------------------------------ first attempt, workflow, artefacts, IAPAR


def attempts(txn, n_attempts, accept_on=None, species="SYN-A7", subject="S3", workflow="registration",
             packs=("p1",), kind="attack", **extra):
    """Attempts of one transaction that ends at its first pass, like the runner and the clients."""
    out = []
    last_no = accept_on or n_attempts
    for no in range(1, last_no + 1):
        outcome = "ACCEPT" if no == accept_on else "REJECT"
        out.append(rec(txn, kind, outcome, species, "none" if outcome == "ACCEPT" else "active_pad",
                       last=no == last_no, txn_outcome="ACCEPT" if accept_on else "REJECT", subject=subject,
                       workflow=workflow, attempt_no=no, frames_per_pack={p: 10 for p in packs}, **extra))
    return out


def audit_sample():
    # One artefact passes every first attempt; the other is refused three times per transaction.
    records = []
    for i in range(4):
        records += attempts(f"s3#{i}", 3, accept_on=1, subject="S3", packs=("a-s3-blink", "a-s3-replay"))
        records += attempts(f"s2#{i}", 3, subject="S2", packs=("a-s2-replay",))
    return records


def test_per_attempt_rate_dilutes_a_species_that_passes_at_once():
    records = audit_sample()
    assert analyze.compute(records, "attempt").species["SYN-A7"].apcer().value == pytest.approx(0.25)
    assert analyze.compute(records, "transaction").species["SYN-A7"].apcer().value == pytest.approx(0.5)
    first = analyze.compute(records, "first_attempt").species["SYN-A7"]
    assert (first.accept, first.reject) == (4, 4)


def test_first_attempt_is_attempt_one_even_when_lines_are_out_of_order():
    txn = attempts("t#1", 3, accept_on=2)
    m = analyze.compute(list(reversed(txn)), "first_attempt")
    assert (m.species["SYN-A7"].accept, m.species["SYN-A7"].reject) == (0, 1)
    m = analyze.compute(attempts("t#2", 3, accept_on=1), "first_attempt")
    assert m.species["SYN-A7"].accept == 1


def test_species_rows_count_packs_subjects_and_artefacts():
    c = analyze.compute(audit_sample(), "transaction").species["SYN-A7"]
    assert c.packs == {"a-s3-blink", "a-s3-replay", "a-s2-replay"}
    assert c.subjects == {"S2", "S3"}
    assert c.artefact_text() == "3 / 2"
    art = c.artefact_apcer()                      # one of two source artefacts accepted
    assert (art.errors, art.n) == (1, 2)
    assert art.interval[0] < 0.02 and art.interval[1] > 0.98


def test_recorded_artefact_id_replaces_packs_and_subject_as_the_unit():
    records = (attempts("a#1", 1, accept_on=1, subject="S1", artefact_id="print-1")
               + attempts("a#2", 3, subject="S1", artefact_id="print-2"))
    c = analyze.compute(records, "transaction").species["SYN-A7"]
    assert c.packs == {"print-1", "print-2"}
    assert (c.artefact_apcer().errors, c.artefact_apcer().n) == (1, 2)


def test_report_leads_with_transaction_and_first_attempt_per_species():
    records = audit_sample()
    report = analyze.build_report(records, None, False, 3, None)
    headline = report.index("## Headline: APCER per species per transaction and on the first attempt")
    assert headline < report.index("## Evaluation type (b)") < report.index("## First attempt of each transaction") \
        < report.index("## Evaluation type (a): per attempt (secondary)")
    block = report[headline:report.index("## Evaluation type (b)")]
    assert "| registration | SYN-A7 | 3 / 2 | 8 | 50.0% (4/8;" in block
    assert "APCER on the first attempt" in block and "source artefacts accepted" in block
    assert "50.0% (1/2;" in block                                   # interval over artefacts
    assert "Transactions are not independent" in block
    pooled = [line for line in block.splitlines() if "pooled" in line]
    assert pooled and all(line.startswith("- Secondary") for line in pooled)
    assert "attempts not independent" in report


def test_every_table_is_grouped_by_workflow():
    records = audit_sample() + attempts("l#1", 3, species="SYN-A2", subject="S2", workflow="LOGIN_AUTH") + [
        rec("f#1", "bona_fide", "ACCEPT", workflow="login_auth", challenges=[
            {"type": "BLINK", "outcome": "PASSED", "duration_ms": 900}])]
    report = analyze.build_report(records, None, False, 3, None)
    assert "login_auth 2, registration 8" in report
    metadata = ("| field", "| runs", "| attempt records", "| transactions", "| model set", "| configuration",
                "| platform")
    for line in report.splitlines():
        if line.startswith("| ") and not line.startswith(metadata):
            assert line.split("|")[1].strip() in ("workflow", "login_auth", "registration"), line
    assert "| login_auth | SYN-A2 |" in report and "| registration | SYN-A7 |" in report
    assert "| login_auth | BLINK | 1/1" in report


def test_workflows_are_computed_separately():
    records = audit_sample() + attempts("l#1", 1, accept_on=1, species="SYN-A7", subject="S9", workflow="login_auth")
    groups = analyze.by_workflow(records)
    assert list(groups) == ["login_auth", "registration"]
    assert analyze.compute(groups["login_auth"], "transaction").species["SYN-A7"].apcer().value == 1.0
    assert analyze.compute(groups["registration"], "transaction").species["SYN-A7"].apcer().value == 0.5


def wrong_action_sample():
    return [rec("w#1", "attack", "REJECT", "SYN-A9", "challenge", passive=90, scenario="atk-wrong-clip"),
            rec("b#1", "attack", "REJECT", "SYN-B", "passive_pad", passive=10),
            rec("f#1", "bona_fide", "ACCEPT", passive=95)]


def test_wrong_action_clips_stay_out_of_apcer_and_the_det():
    m = analyze.compute(wrong_action_sample(), "attempt")
    assert set(m.species) == {"SYN-B"}
    assert m.wrong_action["SYN-A9"].reject == 1
    assert m.attacks_total().total == 1
    bona, attacks = analyze.scores_for_det(wrong_action_sample())
    assert set(attacks) == {"SYN-B"} and bona == [95.0]
    pts = analyze.det_points(bona, [s for v in attacks.values() for s in v])
    assert analyze.bpcer_at_apcer(pts, 0.05)[0] == 0.0           # not 100%: the genuine clip no longer counts


def test_wrong_action_species_can_be_changed_or_cleared():
    m = analyze.compute(wrong_action_sample(), "attempt", genuine_species=())
    assert set(m.species) == {"SYN-A9", "SYN-B"} and not m.wrong_action
    bona, attacks = analyze.scores_for_det(wrong_action_sample(), genuine_species=())
    assert set(attacks) == {"SYN-A9", "SYN-B"}


def test_report_lists_wrong_action_clips_in_their_own_section():
    report = analyze.build_report(wrong_action_sample(), None, False, 3, None)
    section = report[report.index("## Genuine clips of the wrong action"):report.index("## Full-system results")]
    assert "| - | SYN-A9 |" in section                     # no workflow recorded: grouped under "-"
    headline = report[report.index("## Headline"):report.index("## Evaluation type (b)")]
    assert "SYN-A9" not in headline


def login(txn, kind, steps, species="P1", subject="S1"):
    """Attempts of one login transaction: steps are (liveness outcome, match outcome or None)."""
    out = []
    for no, (outcome, match) in enumerate(steps, 1):
        last = no == len(steps)
        out.append(rec(txn, kind, outcome, species if kind == "attack" else "BONA_FIDE", "none", last=last,
                       txn_outcome="ACCEPT" if any(o == "ACCEPT" for o, _ in steps) else "REJECT",
                       subject=subject, workflow="login", attempt_no=no, match_outcome=match))
    return out


def test_iapar_counts_attacks_accepted_by_liveness_and_the_matcher():
    records = (login("t1", "attack", [("ACCEPT", "ACCEPT")])                         # impostor accepted
               + login("t2", "attack", [("ACCEPT", "REJECT"), ("ACCEPT", "ACCEPT")])  # accepted on retry
               + login("t3", "attack", [("ACCEPT", "REJECT"), ("REJECT", None)])      # matcher stopped it
               + login("t4", "attack", [("REJECT", None), ("REJECT", None)])          # liveness stopped it
               + login("t5", "attack", [("ACCEPT", None)]))                           # match not recorded
    assert analyze.has_match_results(records)
    species, _ = analyze.compute_full_system(records)
    c = species["P1"]
    assert (c.accept, c.reject, c.missing) == (2, 2, 1)
    assert c.accept_rate().value == pytest.approx(0.5)
    assert c.matcher_refused == 2
    assert (c.first_accept, c.first_decided) == (1, 4)
    assert c.first_accept_rate().value == pytest.approx(0.25)


def test_frr_counts_genuine_users_refused_by_liveness_or_the_matcher():
    records = (login("g1", "bona_fide", [("ACCEPT", "ACCEPT")])
               + login("g2", "bona_fide", [("ACCEPT", "REJECT")])
               + login("g3", "bona_fide", [("REJECT", None)]))
    _, bona = analyze.compute_full_system(records)
    assert bona.reject_rate().value == pytest.approx(2 / 3)
    assert analyze.compute(records, "transaction").bona_fide.bpcer().value == pytest.approx(1 / 3)


def test_report_shows_iapar_only_for_workflows_with_match_results():
    records = login("t1", "attack", [("ACCEPT", "ACCEPT")]) + audit_sample()
    report = analyze.build_report(records, None, False, 3, None)
    section = report[report.index("## Full-system results"):report.index("## Per scenario")]
    assert "| login | P1 | 1 | 100.0% (1/1;" in section
    assert "registration" not in section
    plain = analyze.build_report(audit_sample(), None, False, 3, None)
    assert "IAPAR and FRR are not computed" in plain


def test_shipped_run_reproduces_the_audit_figures():
    path = (analyze.Path(__file__).resolve().parents[3]
            / "docs/eval/results/20260930-engine-fixes/shipped/attempts.jsonl")
    if not path.exists():
        pytest.skip("shipped results not in this checkout")
    records = analyze.load_records([path])
    reg = analyze.by_workflow(records)["registration"]
    assert analyze.compute(reg, "attempt").species["SYN-A7"].apcer().value == pytest.approx(0.25)
    first = analyze.compute(reg, "first_attempt").species["SYN-A7"]
    assert (first.accept, first.decided) == (4, 8)
    assert "SYN-A9" not in analyze.compute(records, "transaction").species
