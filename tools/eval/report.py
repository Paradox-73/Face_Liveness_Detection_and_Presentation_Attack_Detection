# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Renders the Markdown report and the DET plot from the attempt records and their metrics."""

from __future__ import annotations

import statistics
from collections import Counter, OrderedDict, defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

from metrics import (GENUINE_WRONG_ACTION_SPECIES, KindCounts, Metrics, bpcer_at_apcer, compute, compute_full_system,
                     det_points, det_points_worst_species, has_match_results, scores_for_det)
from records import ACCEPT, Record, attempts_by_txn, by_workflow, is_wrong_action, workflow_of
from stats import BOOTSTRAP_RESAMPLES, attacker_success, pct, percentile


def plot_det(records: Sequence[Record], path: Path, operating_threshold: Optional[float],
             bona_conditions: Optional[Sequence[str]] = None,
             genuine_species: Sequence[str] = GENUINE_WRONG_ACTION_SPECIES) -> bool:
    """Draws DET curves (normal-deviate axes) of the passive score per species and for all attacks.

    Returns False when matplotlib is missing or there are no scores; the report says so.
    """
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return False
    bona, attacks = scores_for_det(records, bona_conditions, genuine_species)
    if not bona or not attacks:
        return False
    nd = statistics.NormalDist()
    lo, hi = 0.001, 0.999

    def dev(p):
        return nd.inv_cdf(min(hi, max(lo, p)))

    fig, ax = plt.subplots(figsize=(6.4, 5.6), dpi=110)
    series = list(attacks.items())
    all_attack = [s for v in attacks.values() for s in v]
    series.append(("all attacks", all_attack))
    for name, scores in series:
        pts = det_points(bona, scores)
        ax.plot([dev(a) for _, a, _ in pts], [dev(b) for _, _, b in pts], drawstyle="steps-post",
                linewidth=2.2 if name == "all attacks" else 1.2, label=f"{name} (n={len(scores)})")
    if operating_threshold is not None:
        a = sum(1 for s in all_attack if s >= operating_threshold) / len(all_attack)
        b = sum(1 for s in bona if s < operating_threshold) / len(bona)
        ax.plot([dev(a)], [dev(b)], marker="o", color="black", linestyle="none",
                label=f"passive threshold {operating_threshold:g}")
    ticks = [0.001, 0.01, 0.05, 0.2, 0.5, 0.8, 0.95, 0.99]
    ax.set_xticks([dev(t) for t in ticks])
    ax.set_xticklabels([f"{100 * t:g}%" for t in ticks])
    ax.set_yticks([dev(t) for t in ticks])
    ax.set_yticklabels([f"{100 * t:g}%" for t in ticks])
    ax.set_xlabel("APCER (attack transactions accepted by the passive score alone)")
    ax.set_ylabel(f"BPCER (bona fide transactions rejected; n={len(bona)})")
    ax.set_title("DET of the passive PAD score, all workflows (technology regression packs)")
    ax.grid(True, alpha=0.3)
    ax.legend(fontsize=7, loc="upper right")
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)
    return True


# --------------------------------------------------------------------------- report


def _table(header: Sequence[str], rows: Iterable[Sequence[object]]) -> str:
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return "\n".join(lines) + "\n"


def _layers(c: KindCounts) -> str:
    return ", ".join(f"{k} {v}" for k, v in c.layers.most_common()) or "-"


def _worst(m: Metrics) -> str:
    name, worst = m.apcer_max()
    return f"**{worst.text()}**, species {name}" if worst else "n/a (no attack decisions)"


def _headline(records: Sequence[Record], genuine: Sequence[str]) -> str:
    """APCER per species per transaction and on the first attempt, per workflow; pooled lines after."""
    out = ["Unit: the **transaction** (one capture until the first accepted attempt or the last allowed attempt) "
           "and the **first attempt** of every transaction. The runner and the clients both end a transaction at "
           "its first pass, so a species that passes at once leaves one accepted attempt while a species that "
           "never passes leaves every allowed attempt; per-attempt rates mix the two and are secondary "
           "(section (a)).\n",
           "Intervals in the transaction and first-attempt columns treat every transaction as an independent "
           "trial. Transactions are not independent: replays of one artefact repeat the same frames. The column "
           "'source artefacts accepted' counts each source artefact once (accepted in at least one "
           "transaction) and its interval is over artefacts. "
           "`packs / subjects` = distinct frame packs (or recorded artefact ids; '-' when none is recorded) "
           "and distinct source subjects behind the row.\n"]
    rows, bullets = [], []
    groups = by_workflow(records)
    for wf, sub in groups.items():
        txn = compute(sub, "transaction", genuine)
        first = compute(sub, "first_attempt", genuine)
        for name, c in txn.species.items():
            f = first.species.get(name, KindCounts())
            rows.append((wf, name, c.artefact_text(), c.total, c.apcer().compact(), f.apcer().compact(),
                         c.artefact_apcer().compact(), c.nrr().short()))
        bf_t, bf_f = txn.bona_fide, first.bona_fide
        if bf_t.total:
            rows.append((wf, "bona fide (BPCER)", bf_t.artefact_text(), bf_t.total, bf_t.bpcer().compact(),
                         bf_f.bpcer().compact(), "-", bf_t.nrr().short()))
        if txn.species or bf_t.total:
            bullets.append(f"- Workflow **{wf}**: APCER_max per transaction {_worst(txn)}; "
                           f"APCER_max on the first attempt {_worst(first)}; "
                           f"BPCER per transaction {bf_t.bpcer().text()}, on the first attempt "
                           f"{bf_f.bpcer().short()}")
    out.append(_table(["workflow", "species", "packs / subjects", "transactions", "APCER per transaction",
                       "APCER on the first attempt", "source artefacts accepted (CI over artefacts)",
                       "APNRR per transaction"], rows))
    out.append("\n".join(bullets) + "\n")
    secondary = []
    for wf, sub in groups.items():
        pooled_t = compute(sub, "transaction", genuine).attacks_total()
        pooled_f = compute(sub, "first_attempt", genuine).attacks_total()
        pooled_a = compute(sub, "attempt", genuine).attacks_total()
        if pooled_t.total:
            secondary.append(f"- Secondary, workflow {wf}, all attack species pooled: per transaction "
                             f"{pooled_t.apcer().short()}, first attempt {pooled_f.apcer().short()}, "
                             f"per attempt {pooled_a.apcer().short()}")
    if len(groups) > 1:
        txn_all = compute(records, "transaction", genuine)
        first_all = compute(records, "first_attempt", genuine)
        secondary.append(f"- Secondary, all workflows pooled: APCER_max per transaction "
                         f"{_worst(txn_all).replace('**', '')}; on the first attempt "
                         f"{_worst(first_all).replace('**', '')}")
    if secondary:
        out.append("\n" + "\n".join(secondary) + "\n")
    return "\n".join(out)


def _metrics_section(records: Sequence[Record], level: str, retries: int, genuine: Sequence[str]) -> str:
    """Species, bona fide condition and bona fide subject tables at one level, grouped by workflow."""
    unit = {"attempt": "attempts", "transaction": "transactions", "first_attempt": "first attempts"}[level]
    groups = by_workflow(records)
    metrics = {wf: compute(sub, level, genuine) for wf, sub in groups.items()}
    rows = []
    for wf, m in metrics.items():
        for name, c in m.species.items():
            a = c.apcer()
            row = [wf, name, c.total, c.accept, c.reject, c.no_response, a.text(), c.nrr().short(),
                   c.artefact_text(), _layers(c)]
            if level == "attempt":
                row.append("-" if a.value is None else pct(attacker_success(a.value, retries)))
            rows.append(row)
    header = ["workflow", "species", unit, "accepted", "rejected", "no response",
              f"APCER (95% CI; {unit} not independent)", "APNRR", "packs / subjects", "rejected by layer"]
    if level == "attempt":
        header.append(f"attacker success in {retries} tries (if attempts were independent)")
    out = [_table(header, rows)]
    bullets = []
    for wf, m in metrics.items():
        total = m.attacks_total()
        bf = m.bona_fide
        boot = m.bpcer_bootstrap()
        bullets.append(f"- Workflow **{wf}**: **APCER_max** (worst species) {_worst(m)}")
        bullets.append(f"  - **BPCER** (all bona fide conditions): {bf.bpcer().text()}"
                       + (f"; subject-level bootstrap 95% interval {pct(boot[0])}-{pct(boot[1])} "
                          f"({BOOTSTRAP_RESAMPLES} resamples)" if boot else "")
                       + f"; **BPNRR** {bf.nrr().short()}")
        if bf.layers:
            bullets.append(f"  - Bona fide rejections by layer: {_layers(bf)}")
        if total.total:
            bullets.append(f"  - Secondary, all attack species pooled: APCER {total.apcer().text()}; "
                           f"APNRR {total.nrr().short()}")
    out.append("\n".join(bullets) + "\n")
    out.append("\n" + _table(["workflow", "bona fide condition", unit, "accepted", "rejected", "no response",
                              "BPCER", "BPNRR", "packs / subjects"],
                             [(wf, k, c.total, c.accept, c.reject, c.no_response, c.bpcer().text(),
                               c.nrr().short(), c.artefact_text())
                              for wf, m in metrics.items() for k, c in m.bona_fide_by_condition.items()]))
    out.append("\n" + _table(["workflow", "bona fide subject", unit, "accepted", "rejected", "no response", "BPCER"],
                             [(wf, k, c.total, c.accept, c.reject, c.no_response, c.bpcer().text())
                              for wf, m in metrics.items() for k, c in m.bona_fide_by_subject.items()]))
    return "\n".join(out)


def _wrong_action(records: Sequence[Record], genuine: Sequence[str]) -> str:
    rows = []
    for wf, sub in by_workflow(records).items():
        txn = compute(sub, "transaction", genuine)
        first = compute(sub, "first_attempt", genuine)
        for name, c in txn.wrong_action.items():
            f = first.wrong_action.get(name, KindCounts())
            rows.append((wf, name, c.artefact_text(), c.total, c.apcer().compact(), f.apcer().compact(),
                         _layers(c)))
    head = (f"Species {', '.join(genuine) or '(none)'}: a genuine face answering with a clip of the wrong action. "
            "These are bona fide presentations, so they test the challenge check, not presentation attack "
            "detection; they are left out of APCER, BPCER and the DET curve. The correct result is a refusal.\n\n")
    if not rows:
        return head + "None in these records.\n"
    return head + _table(["workflow", "species", "packs / subjects", "transactions",
                          "accepted per transaction (wrong action passed)", "accepted on the first attempt",
                          "refused by layer"], rows)


def _full_system(records: Sequence[Record], genuine: Sequence[str]) -> str:
    """IAPAR per species and FRR, per workflow, for the workflows whose records carry a match result."""
    rows, frr_rows = [], []
    for wf, sub in by_workflow(records).items():
        if not has_match_results(sub):
            continue
        species, bona = compute_full_system(sub, genuine)
        for name, c in species.items():
            rows.append((wf, name, c.total, c.accept_rate().compact(), c.first_accept_rate().compact(),
                         c.matcher_refused, c.missing, c.no_response))
        if bona.total:
            frr_rows.append((wf, bona.total, bona.reject_rate().compact(), bona.first_reject_rate().compact(),
                             bona.matcher_refused, bona.missing, bona.no_response))
    if not rows and not frr_rows:
        return ("No attempt record carries a face-match result (`match_outcome`), so IAPAR and FRR are not "
                "computed. The results sheet supplies it for login rows (tools/eval/sheet-to-attempts.py).\n")
    out = ["IAPAR = impostor attack transactions accepted by liveness **and** the face matcher / impostor attack "
           "transactions with a decision. Attacks refused by liveness count as refused. FRR = genuine "
           "transactions refused by liveness or the matcher / genuine transactions with a decision. Only "
           "workflows whose records carry a match result are listed.\n"]
    if rows:
        out.append(_table(["workflow", "species", "transactions", "IAPAR per transaction",
                           "IAPAR on the first attempt", "liveness passed, matcher refused",
                           "match result missing", "no response"], rows))
    if frr_rows:
        out.append("\n" + _table(["workflow", "genuine transactions", "FRR per transaction",
                                  "FRR on the first attempt", "liveness passed, matcher refused",
                                  "match result missing", "no response"], frr_rows))
    return "\n".join(out)


def _stage_attribution(records: Sequence[Record], genuine: Sequence[str]) -> str:
    rows = []
    by_species: Dict[Tuple[str, str], Counter] = defaultdict(Counter)
    for r in records:
        if r.kind == "attack" and r.outcome != ACCEPT and not is_wrong_action(r, genuine):
            stage = "active stage" if r.raw.get("active_triggered") else "passive stage"
            by_species[(workflow_of(r), r.species)][stage] += 1
    for (wf, sp), c in sorted(by_species.items()):
        n = sum(c.values())
        rows.append((wf, sp, n, f"{c['passive stage']} ({pct(c['passive stage'] / n)})",
                     f"{c['active stage']} ({pct(c['active stage'] / n)})"))
    return _table(["workflow", "species", "attempts stopped", "before any challenge", "during or after challenges"],
                  rows)


def _scenarios(records: Sequence[Record], genuine: Sequence[str]) -> str:
    """One row per scenario: transaction outcomes, how accepted attempts passed and what stopped the rest."""
    groups: Dict[Tuple[str, str], List[List[Record]]] = OrderedDict()
    for txn in attempts_by_txn(records).values():
        groups.setdefault((workflow_of(txn[0]), txn[0].scenario), []).append(txn)
    rows = []
    for (wf, name), txns in sorted(groups.items()):
        first = txns[0][0]
        outcomes = Counter(t[-1].raw.get("txn_outcome") or t[-1].outcome for t in txns)
        first_accepted = sum(1 for t in txns if t[0].outcome == ACCEPT)
        methods = Counter(a.raw.get("method") for t in txns for a in t if a.outcome == ACCEPT)
        layers = Counter(a.layer for t in txns for a in t if a.outcome != ACCEPT)
        scores = [t[0].passive_score for t in txns if t[0].raw.get("passive_score") is not None]
        attempts = sum(len(t) for t in txns)
        kind = "genuine clip, wrong action" if is_wrong_action(first, genuine) else first.kind
        rows.append((wf, name, kind, first.species, len(txns),
                     " ".join(f"{k.lower()} {v}" for k, v in sorted(outcomes.items())),
                     f"{first_accepted}/{len(txns)}", attempts,
                     f"{statistics.median(scores):g}" if scores else "-",
                     ", ".join(f"{str(k).lower()} {v}" for k, v in methods.most_common()) or "-",
                     ", ".join(f"{k} {v}" for k, v in layers.most_common()) or "-"))
    return _table(["workflow", "scenario", "kind", "species", "transactions", "transaction outcomes",
                   "accepted on the first attempt", "attempts", "passive score (median)",
                   "accepted attempts by method", "other attempts ended by"], rows)


def _challenges(records: Sequence[Record]) -> str:
    stats: Dict[Tuple[str, str], List] = defaultdict(lambda: [0, 0, []])
    for r in records:
        if r.kind != "bona_fide":
            continue
        for ch in r.raw.get("challenges") or []:
            s = stats[(workflow_of(r), ch["type"])]
            s[1] += 1
            if ch["outcome"] == "PASSED":
                s[0] += 1
                s[2].append(ch["duration_ms"])
    rows = [(wf, t, f"{p}/{n} ({pct(p / n)})", f"{statistics.median(d):.0f}" if d else "-")
            for (wf, t), (p, n, d) in sorted(stats.items())]
    return _table(["workflow", "challenge (bona fide)", "completed", "median time to complete (ms)"], rows) \
        if rows else "No challenges were issued to bona fide presentations.\n"


def _timings(records: Sequence[Record]) -> str:
    rows = []
    for wf, sub in by_workflow(records).items():
        groups = [("bona fide", [r for r in sub if r.kind == "bona_fide"]),
                  ("attack", [r for r in sub if r.kind == "attack"]),
                  ("all", list(sub))]
        for name, rs in groups:
            if not rs:
                continue
            total = [r.t_total_ms for r in rs if r.raw.get("t_total_ms") is not None]
            passive = [r.t_passive_ms for r in rs if r.raw.get("t_passive_ms")]
            frame_p50 = [r.frame_ms["p50"] for r in rs if (r.raw.get("frame_ms") or {}).get("n")]
            frame_p95 = [r.frame_ms["p95"] for r in rs if (r.raw.get("frame_ms") or {}).get("n")]
            frames = sum(r.frame_ms.get("n", 0) for r in rs if r.raw.get("frame_ms"))
            rows.append((wf, name, len(rs),
                         f"{statistics.median(total):.0f} / {percentile(total, 95):.0f}" if total else "-",
                         f"{statistics.median(passive):.0f} / {percentile(passive, 95):.0f}" if passive else "-",
                         frames, f"{statistics.median(frame_p50):.1f}" if frame_p50 else "-",
                         f"{max(frame_p95):.1f}" if frame_p95 else "-"))
    return _table(["workflow", "attempts", "n", "session time median / p95 (ms, stream clock)",
                   "passive stage median / p95 (ms)", "frames analysed",
                   "per-frame p50 (median over attempts, ms)", "per-frame p95 (worst attempt, ms)"], rows)


def _other(records: Sequence[Record], genuine: Sequence[str]) -> str:
    rows = [(wf, k, c.total, c.accept, c.reject, c.no_response, _layers(c))
            for wf, sub in by_workflow(records).items()
            for k, c in compute(sub, "attempt", genuine).other.items()]
    if not rows:
        return "None.\n"
    return _table(["workflow", "scenario", "presentations", "accepted", "rejected", "no response", "ended by layer"],
                  rows)


def _expectations(records: Sequence[Record]) -> str:
    rows, bad = [], 0
    for key, txn in attempts_by_txn(records).items():
        last = txn[-1]
        exp = last.raw.get("expected_outcome")
        got = last.raw.get("txn_outcome") or last.outcome
        layers = set(last.raw.get("expected_layers") or [])
        layer_ok = all(a.outcome == ACCEPT or not layers or a.layer in layers for a in txn)
        ok = (exp is None or exp == got) and layer_ok
        if not ok:
            bad += 1
            rows.append((workflow_of(last), last.txn_id, exp, got, ", ".join(str(a.layer) for a in txn),
                         "+".join(last.raw.get("reason_codes") or []) or "-"))
    head = f"{bad} of {len(attempts_by_txn(records))} transactions did not meet the scenario's expectation.\n\n"
    if not rows:
        return head
    return head + _table(["workflow", "transaction", "expected", "observed", "layer per attempt",
                          "reasons (last attempt)"], sorted(rows))


def _det_summary(records: Sequence[Record], bona_conditions: Optional[Sequence[str]],
                 genuine: Sequence[str]) -> str:
    groups = list(by_workflow(records).items())
    if len(groups) > 1:
        groups.append(("all workflows", list(records)))
    rows = []
    for wf, sub in groups:
        bona, attacks = scores_for_det(sub, bona_conditions, genuine)
        scores = [s for v in attacks.values() for s in v]
        if not bona or not scores:
            rows.append((wf, len(bona), len(scores), "-", "-"))
            continue
        pts = det_points_worst_species(bona, attacks)
        b20, b10 = bpcer_at_apcer(pts, 0.05), bpcer_at_apcer(pts, 0.10)
        rows.append((wf, len(bona), len(scores),
                     f"{pct(b20[0])} at threshold {b20[1]:g}" if b20 else "not reached",
                     f"{pct(b10[0])} at threshold {b10[1]:g}" if b10 else "not reached"))
    return _table(["workflow", "bona fide transactions", "attack transactions",
                   "BPCER20 (BPCER at APCER 5% of the worst species, passive score only)", "BPCER10"], rows)


def build_report(records: Sequence[Record], det_file: Optional[str], det_ok: bool, retries: int,
                 operating_threshold: Optional[float], bona_conditions: Optional[Sequence[str]] = None,
                 genuine_species: Sequence[str] = GENUINE_WRONG_ACTION_SPECIES) -> str:
    """Renders the Markdown report."""
    genuine = tuple(genuine_species or ())
    runs = sorted({r.run_id for r in records})
    models = sorted({r.raw.get("model_set_sha256", "?") for r in records})
    configs = sorted({r.raw.get("config_hash", "?") for r in records})
    platform = records[0].raw.get("platform", {}) if records else {}
    manual = bool(records) and all((r.raw.get("platform") or {}).get("source") == "manual sheet" for r in records)
    if manual:
        head = [
            "# Liveness live test report\n",
            "In-house test following ISO/IEC 30107-3:2023 methodology: real people and home-made attack artefacts "
            "in front of the real client, results recorded by hand (tools/eval/sheet-to-attempts.py). It is not a "
            "certified laboratory evaluation. Per ISO/IEC 30107-3, a non-zero APCER shows that an attack can "
            "succeed; zero observed errors bound the error rate but do not prove security.\n",
        ]
    else:
        head = [
            "# Liveness technology regression report\n",
            "Computed with ISO/IEC 30107-3:2023 metric definitions from replayed frame packs. The attack packs are "
            "synthetic (software-made prints, screens and replays) and the frames are injected into the engine, so "
            "these figures are **technology regression results, not an ISO/IEC 30107-3 evaluation** and not "
            "comparable to laboratory results. Per ISO/IEC 30107-3, a non-zero APCER shows that an attack can "
            "succeed; zero observed errors bound the error rate but do not prove security.\n",
        ]
    txn_per_wf = Counter(workflow_of(t[0]) for t in attempts_by_txn(records).values())
    parts = head + [
        "## Item under test\n",
        _table(["field", "value"], [
            ("runs", ", ".join(runs)),
            ("attempt records", len(records)),
            ("transactions", len(attempts_by_txn(records))),
            ("transactions per workflow", ", ".join(f"{k} {v}" for k, v in sorted(txn_per_wf.items()))),
            ("model set SHA-256", ", ".join((m or "?")[:16] + "..." for m in models)),
            ("configuration hashes", len(configs)),
            ("platform", ", ".join(f"{k} {v}" for k, v in platform.items())),
        ]),
        "## Headline: APCER per species per transaction and on the first attempt\n",
        _headline(records, genuine),
        "\n## Evaluation type (b): per transaction (retries included)\n",
        _metrics_section(records, "transaction", retries, genuine),
        "\n## First attempt of each transaction\n",
        "Attempt 1 of every transaction only: what an attacker gets from one try, before any retry.\n\n",
        _metrics_section(records, "first_attempt", retries, genuine),
        "\n## Evaluation type (a): per attempt (secondary)\n",
        "Every attempt counted. A transaction ends at its first pass, so this level undercounts a species that "
        "passes at once; read the per-transaction and first-attempt figures above first.\n\n",
        _metrics_section(records, "attempt", retries, genuine),
        "\n## Genuine clips of the wrong action (not in APCER or BPCER)\n",
        _wrong_action(records, genuine),
        "\n## Full-system results: IAPAR and FRR\n",
        _full_system(records, genuine),
        "\n## Per scenario\n",
        "Method `passive` means the passive score alone passed; `passive_active` and `active` mean challenges "
        "were needed.\n\n",
        _scenarios(records, genuine),
        "\n## Where attacks were stopped (per attempt)\n",
        _stage_attribution(records, genuine),
        "\n## Bona fide challenges\n",
        _challenges(records),
        "\n## Timings\n",
        "Session times are on the replayed stream clock (15 fps). Per-frame times are wall-clock time of "
        "`onFrame` plus `tick` on the machine named above (JPEG decode, face analysis, PAD every other frame).\n\n",
        _timings(records),
        "\n## DET of the passive score\n",
    ]
    excluded = f" Genuine clips of the wrong action ({', '.join(genuine)}) are left out." if genuine else ""
    if det_ok:
        parts.append(f"![DET]({det_file})\n\n")
        parts.append(f"One point per transaction (its first attempt); the plot pools all workflows; bona fide "
                     f"conditions: {', '.join(bona_conditions) if bona_conditions else 'all'}.{excluded} ")
        parts.append("The curve sweeps the passive score alone; the full engine adds integrity, planarity, "
                     "challenge and binding checks, so the system's operating point is the per-transaction table "
                     "above, not this curve.\n\n")
        parts.append(_det_summary(records, bona_conditions, genuine))
        if operating_threshold is not None:
            parts.append(f"\n- Marked point: passive threshold {operating_threshold:g}.\n")
    else:
        parts.append("Not drawn (matplotlib missing, or no bona fide or attack attempt reached a passive score).\n")
    parts += ["\n## Quality and behaviour scenarios (not in APCER or BPCER)\n", _other(records, genuine),
              "\n## Expectation check\n", _expectations(records)]
    return "\n".join(parts)
