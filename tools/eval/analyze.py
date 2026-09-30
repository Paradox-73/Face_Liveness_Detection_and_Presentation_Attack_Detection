#!/usr/bin/env python3
"""Computes ISO/IEC 30107-3-style metrics from scenario-runner attempt records and writes a Markdown report.

Input: one or more ``attempts.jsonl`` files written by tools/eval/scenario-runner (schema
``liveness-eval-attempt/1``, one JSON object per attempt, no images). Output: a Markdown report and,
when matplotlib is installed, a DET plot of the passive PAD score.

  python tools/eval/analyze.py RUN/attempts.jsonl --out RUN/report.md --det RUN/det.png

Classification (docs/eval/protocol.md): every attempt is ACCEPT, REJECT or NO_RESPONSE. APCER and BPCER
use only attempts (or transactions) that produced a decision; the others count toward APNRR and BPNRR.

  APCER(species) = attacks of that species accepted / attacks of that species with a decision
  APCER_max      = the highest APCER over the species (ISO/IEC 30107-3 "APCER_AP")
  BPCER          = bona fide presentations rejected / bona fide presentations with a decision
  APNRR / BPNRR  = presentations without a decision / all presentations of that kind

Intervals are exact Clopper-Pearson at 95%. For zero observed errors the report also gives the one-sided
95% upper bound (1 - 0.05^(1/n)) and labels which bound is shown.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import statistics
import sys
from collections import Counter, OrderedDict, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

SCHEMA = "liveness-eval-attempt/1"
ACCEPT, REJECT, NO_RESPONSE = "ACCEPT", "REJECT", "NO_RESPONSE"
CONFIDENCE = 0.95
BOOTSTRAP_RESAMPLES = 2000
BOOTSTRAP_SEED = 30107


# --------------------------------------------------------------------------- statistics


def _log_binom_pmf(k: int, n: int, p: float) -> float:
    if p <= 0.0:
        return 0.0 if k == 0 else -math.inf
    if p >= 1.0:
        return 0.0 if k == n else -math.inf
    return (math.lgamma(n + 1) - math.lgamma(k + 1) - math.lgamma(n - k + 1)
            + k * math.log(p) + (n - k) * math.log1p(-p))


def binom_cdf(x: int, n: int, p: float) -> float:
    """P(X <= x) for X ~ Binomial(n, p), summed in log space so large n does not overflow."""
    if x < 0:
        return 0.0
    if x >= n:
        return 1.0
    return min(1.0, sum(math.exp(_log_binom_pmf(k, n, p)) for k in range(x + 1)))


def _solve(f, lo: float = 0.0, hi: float = 1.0, iterations: int = 100) -> float:
    """Bisection for a decreasing function f on [lo, hi] with a root."""
    for _ in range(iterations):
        mid = (lo + hi) / 2.0
        if f(mid) > 0:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2.0


def clopper_pearson(x: int, n: int, confidence: float = CONFIDENCE) -> Tuple[float, float]:
    """Two-sided exact (Clopper-Pearson) interval for a binomial proportion x/n.

    Returns (lower, upper). For n == 0 the interval is (0, 1): nothing was measured.
    """
    if n <= 0:
        return 0.0, 1.0
    if not 0 <= x <= n:
        raise ValueError(f"x must be in 0..n: x={x}, n={n}")
    a = (1.0 - confidence) / 2.0
    lower = 0.0 if x == 0 else _solve(lambda p: a - (1.0 - binom_cdf(x - 1, n, p)))
    upper = 1.0 if x == n else _solve(lambda p: binom_cdf(x, n, p) - a)
    return lower, upper


def one_sided_upper(x: int, n: int, confidence: float = CONFIDENCE) -> float:
    """One-sided exact upper bound: the p at which P(X <= x) = 1 - confidence.

    For x == 0 this is 1 - (1 - confidence)^(1/n), for example 4.9% for 0/60 at 95%.
    """
    if n <= 0:
        return 1.0
    if x >= n:
        return 1.0
    a = 1.0 - confidence
    if x == 0:
        return 1.0 - a ** (1.0 / n)
    return _solve(lambda p: binom_cdf(x, n, p) - a)


@dataclass
class Rate:
    """An error rate with its exact interval."""

    errors: int
    n: int

    @property
    def value(self) -> Optional[float]:
        return None if self.n == 0 else self.errors / self.n

    @property
    def interval(self) -> Tuple[float, float]:
        return clopper_pearson(self.errors, self.n)

    @property
    def upper_one_sided(self) -> float:
        return one_sided_upper(self.errors, self.n)

    def short(self) -> str:
        """Rate and count only, for columns where the interval would add noise."""
        return "n/a" if self.n == 0 else f"{pct(self.value)} ({self.errors}/{self.n})"

    def text(self) -> str:
        """Rate, count and interval as report text, with the zero-error bound labelled."""
        if self.n == 0:
            return "n/a (0 decisions)"
        lo, hi = self.interval
        s = f"{pct(self.value)} ({self.errors}/{self.n}; 95% CI {pct(lo)}-{pct(hi)} two-sided"
        if self.errors == 0:
            s += f"; one-sided 95% upper bound {pct(self.upper_one_sided)}"
        return s + ")"


def pct(v: Optional[float]) -> str:
    return "n/a" if v is None else f"{100.0 * v:.1f}%"


def attacker_success(p: float, retries: int) -> float:
    """Chance that at least one of ``retries`` independent attempts is accepted: 1 - (1 - p)^r."""
    return 1.0 - (1.0 - p) ** retries


def percentile(values: Sequence[float], q: float) -> Optional[float]:
    """Nearest-rank percentile (q in 0..100), the same rule as the Java runner's LatencyStats."""
    if not values:
        return None
    s = sorted(values)
    rank = max(1, math.ceil(q / 100.0 * len(s)))
    return s[min(rank, len(s)) - 1]


def bootstrap_rate(groups: Dict[str, Tuple[int, int]], resamples: int = BOOTSTRAP_RESAMPLES,
                   seed: int = BOOTSTRAP_SEED) -> Optional[Tuple[float, float]]:
    """Percentile bootstrap 95% interval of a pooled rate, resampling whole groups (subjects).

    ``groups`` maps a subject id to (errors, n). Returns None with fewer than two subjects, where a
    subject-level interval means nothing.
    """
    keys = [k for k, (_, n) in groups.items() if n > 0]
    if len(keys) < 2:
        return None
    rng = random.Random(seed)
    rates = []
    for _ in range(resamples):
        e = t = 0
        for _k in keys:
            ge, gn = groups[rng.choice(keys)]
            e += ge
            t += gn
        rates.append(e / t)
    rates.sort()
    return rates[int(0.025 * (resamples - 1))], rates[int(0.975 * (resamples - 1))]


# --------------------------------------------------------------------------- records


@dataclass
class Record:
    """The fields of one attempt line that the analysis uses."""

    raw: dict

    def __getattr__(self, name):
        try:
            return self.raw[name]
        except KeyError as e:
            raise AttributeError(name) from e


def load_records(paths: Iterable[Path]) -> List[Record]:
    """Reads attempt lines; rejects lines of another schema so mixed files cannot skew the figures."""
    out = []
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for no, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                d = json.loads(line)
                if d.get("schema") != SCHEMA:
                    raise ValueError(f"{path}:{no}: schema {d.get('schema')!r}, expected {SCHEMA}")
                out.append(Record(d))
    return out


def transactions(records: Sequence[Record]) -> List[Record]:
    """The last attempt of every transaction; its ``txn_outcome`` is the transaction's outcome."""
    return [r for r in records if r.last_in_txn]


def attempts_by_txn(records: Sequence[Record]) -> Dict[str, List[Record]]:
    out: Dict[str, List[Record]] = OrderedDict()
    for r in records:
        out.setdefault(f"{r.run_id}/{r.txn_id}", []).append(r)
    return out


# --------------------------------------------------------------------------- metrics


@dataclass
class KindCounts:
    """Outcome counts for one group of presentations."""

    accept: int = 0
    reject: int = 0
    no_response: int = 0
    layers: Counter = field(default_factory=Counter)

    def add(self, outcome: str, layer: Optional[str] = None) -> None:
        if outcome == ACCEPT:
            self.accept += 1
        elif outcome == REJECT:
            self.reject += 1
        elif outcome == NO_RESPONSE:
            self.no_response += 1
        else:
            raise ValueError(f"unknown outcome {outcome!r}")
        if layer and outcome != ACCEPT:
            self.layers[layer] += 1

    @property
    def total(self) -> int:
        return self.accept + self.reject + self.no_response

    @property
    def decided(self) -> int:
        return self.accept + self.reject

    def apcer(self) -> Rate:
        return Rate(self.accept, self.decided)

    def bpcer(self) -> Rate:
        return Rate(self.reject, self.decided)

    def nrr(self) -> Rate:
        return Rate(self.no_response, self.total)


@dataclass
class Metrics:
    """All figures for one level (attempt or transaction)."""

    level: str
    species: Dict[str, KindCounts]
    bona_fide: KindCounts
    bona_fide_by_condition: Dict[str, KindCounts]
    bona_fide_by_subject: Dict[str, KindCounts]
    other: Dict[str, KindCounts]

    def apcer_max(self) -> Tuple[Optional[str], Optional[Rate]]:
        """The species with the highest APCER among species with at least one decision."""
        best = None
        for name, c in self.species.items():
            r = c.apcer()
            if r.n and (best is None or r.value > best[1].value):
                best = (name, r)
        return best if best else (None, None)

    def attacks_total(self) -> KindCounts:
        total = KindCounts()
        for c in self.species.values():
            total.accept += c.accept
            total.reject += c.reject
            total.no_response += c.no_response
            total.layers.update(c.layers)
        return total

    def bpcer_bootstrap(self) -> Optional[Tuple[float, float]]:
        return bootstrap_rate({s: (c.reject, c.decided) for s, c in self.bona_fide_by_subject.items()})


def compute(records: Sequence[Record], level: str = "attempt") -> Metrics:
    """Counts outcomes per attack species, bona fide condition and subject.

    ``level`` is ``attempt`` (evaluation type a: every attempt is a presentation) or ``transaction``
    (type b: a transaction with retries is one presentation, accepted if any attempt was accepted).
    """
    if level not in ("attempt", "transaction"):
        raise ValueError(level)
    species: Dict[str, KindCounts] = defaultdict(KindCounts)
    bf = KindCounts()
    by_cond: Dict[str, KindCounts] = defaultdict(KindCounts)
    by_subj: Dict[str, KindCounts] = defaultdict(KindCounts)
    other: Dict[str, KindCounts] = defaultdict(KindCounts)
    if level == "attempt":
        rows = [(r, r.outcome, r.layer) for r in records]
    else:
        rows = []
        for txn in attempts_by_txn(records).values():
            last = txn[-1]
            outcome = last.txn_outcome or last.outcome
            layer = next((a.layer for a in reversed(txn) if a.outcome == outcome), last.layer)
            rows.append((last, outcome, layer))
    for r, outcome, layer in rows:
        if r.kind == "attack":
            species[r.species].add(outcome, layer)
        elif r.kind == "bona_fide":
            bf.add(outcome, layer)
            by_cond[r.condition or "-"].add(outcome, layer)
            by_subj[r.subject_id or "-"].add(outcome, layer)
        else:
            other[r.scenario].add(outcome, layer)
    return Metrics(level, dict(sorted(species.items())), bf, dict(sorted(by_cond.items())),
                   dict(sorted(by_subj.items())), dict(sorted(other.items())))


# --------------------------------------------------------------------------- DET


def det_points(bona_scores: Sequence[float], attack_scores: Sequence[float]) -> List[Tuple[float, float, float]]:
    """(threshold, APCER, BPCER) for every candidate threshold of a score where higher means more live.

    A presentation is accepted when its score is at or above the threshold.
    """
    thresholds = sorted(set(bona_scores) | set(attack_scores) | {0.0, 101.0})
    out = []
    for t in thresholds:
        apcer = sum(1 for s in attack_scores if s >= t) / len(attack_scores) if attack_scores else float("nan")
        bpcer = sum(1 for s in bona_scores if s < t) / len(bona_scores) if bona_scores else float("nan")
        out.append((t, apcer, bpcer))
    return out


def bpcer_at_apcer(points: Sequence[Tuple[float, float, float]], target_apcer: float) -> Optional[Tuple[float, float]]:
    """Lowest BPCER among thresholds whose APCER is at or below ``target_apcer`` (BPCER20: target 5%).

    Returns (BPCER, threshold) or None if no threshold reaches the target.
    """
    ok = [(b, t) for t, a, b in points if a <= target_apcer]
    return min(ok) if ok else None


def scores_for_det(records: Sequence[Record], bona_conditions: Optional[Sequence[str]] = None
                   ) -> Tuple[List[float], Dict[str, List[float]]]:
    """Passive scores for the DET: one per transaction (its first attempt, because retries replay the same
    frames and would only repeat the score), bona fide limited to ``bona_conditions`` when given."""
    bona, attacks = [], defaultdict(list)
    for txn in attempts_by_txn(records).values():
        r = txn[0]
        s = r.raw.get("passive_score")
        if s is None:
            continue
        if r.kind == "bona_fide":
            if bona_conditions and r.condition not in bona_conditions:
                continue
            bona.append(float(s))
        elif r.kind == "attack":
            attacks[r.species].append(float(s))
    return bona, dict(sorted(attacks.items()))


def plot_det(records: Sequence[Record], path: Path, operating_threshold: Optional[float],
             bona_conditions: Optional[Sequence[str]] = None) -> bool:
    """Draws DET curves (normal-deviate axes) of the passive score per species and for all attacks.

    Returns False when matplotlib is missing or there are no scores; the report says so.
    """
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return False
    bona, attacks = scores_for_det(records, bona_conditions)
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
    ax.set_title("DET of the passive PAD score (technology regression packs)")
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


def _metrics_section(m: Metrics, retries: int) -> str:
    out = []
    unit = "attempts" if m.level == "attempt" else "transactions"
    rows = []
    for name, c in m.species.items():
        a = c.apcer()
        risk = "-" if a.value is None or m.level != "attempt" else pct(attacker_success(a.value, retries))
        rows.append((name, c.total, c.accept, c.reject, c.no_response, a.text(), c.nrr().short(), _layers(c), risk))
    header = ["species", unit, "accepted", "rejected", "no response", "APCER", "APNRR", "rejected by layer"]
    if m.level == "attempt":
        header.append(f"attacker success in {retries} tries")
        out.append(_table(header, rows))
    else:
        out.append(_table(header, [r[:-1] for r in rows]))
    name, worst = m.apcer_max()
    total = m.attacks_total()
    bf = m.bona_fide
    boot = m.bpcer_bootstrap()
    bullets = [f"- **APCER_max** (worst species): **{worst.text() if worst else 'n/a'}**"
               + (f", species {name}" if name else ""),
               f"- All attack species pooled: APCER {total.apcer().text()}; APNRR {total.nrr().short()}",
               f"- **BPCER** (all bona fide conditions): {bf.bpcer().text()}"
               + (f"; subject-level bootstrap 95% interval {pct(boot[0])}-{pct(boot[1])} "
                  f"({BOOTSTRAP_RESAMPLES} resamples)" if boot else ""),
               f"- **BPNRR**: {bf.nrr().short()}"]
    if bf.layers:
        bullets.append(f"- Bona fide rejections by layer: {_layers(bf)}")
    out.append("\n".join(bullets) + "\n")
    out.append("\n" + _table(["bona fide condition", unit, "accepted", "rejected", "no response", "BPCER", "BPNRR"],
                             [(k, c.total, c.accept, c.reject, c.no_response, c.bpcer().text(), c.nrr().short())
                              for k, c in m.bona_fide_by_condition.items()]))
    out.append("\n" + _table(["bona fide subject", unit, "accepted", "rejected", "no response", "BPCER"],
                             [(k, c.total, c.accept, c.reject, c.no_response, c.bpcer().text())
                              for k, c in m.bona_fide_by_subject.items()]))
    return "\n".join(out)


def _stage_attribution(records: Sequence[Record]) -> str:
    rows = []
    by_species: Dict[str, Counter] = defaultdict(Counter)
    for r in records:
        if r.kind == "attack" and r.outcome != ACCEPT:
            stage = "active stage" if r.active_triggered else "passive stage"
            by_species[r.species][stage] += 1
    for sp, c in sorted(by_species.items()):
        n = sum(c.values())
        rows.append((sp, n, f"{c['passive stage']} ({pct(c['passive stage'] / n)})",
                     f"{c['active stage']} ({pct(c['active stage'] / n)})"))
    return _table(["species", "attempts stopped", "before any challenge", "during or after challenges"], rows)


def _scenarios(records: Sequence[Record]) -> str:
    """One row per scenario: transaction outcomes, how accepted attempts passed and what stopped the rest."""
    groups: Dict[str, List[List[Record]]] = OrderedDict()
    for txn in attempts_by_txn(records).values():
        groups.setdefault(txn[0].scenario, []).append(txn)
    rows = []
    for name, txns in sorted(groups.items()):
        first = txns[0][0]
        outcomes = Counter(t[-1].txn_outcome or t[-1].outcome for t in txns)
        methods = Counter(a.raw.get("method") for t in txns for a in t if a.outcome == ACCEPT)
        layers = Counter(a.layer for t in txns for a in t if a.outcome != ACCEPT)
        scores = [t[0].passive_score for t in txns if t[0].raw.get("passive_score") is not None]
        attempts = sum(len(t) for t in txns)
        rows.append((name, first.kind, first.species, len(txns),
                     " ".join(f"{k.lower()} {v}" for k, v in sorted(outcomes.items())), attempts,
                     f"{statistics.median(scores):g}" if scores else "-",
                     ", ".join(f"{str(k).lower()} {v}" for k, v in methods.most_common()) or "-",
                     ", ".join(f"{k} {v}" for k, v in layers.most_common()) or "-"))
    return _table(["scenario", "kind", "species", "transactions", "transaction outcomes", "attempts",
                   "passive score (median)", "accepted attempts by method", "other attempts ended by"], rows)


def _challenges(records: Sequence[Record]) -> str:
    stats: Dict[str, List] = defaultdict(lambda: [0, 0, []])
    for r in records:
        if r.kind != "bona_fide":
            continue
        for ch in r.raw.get("challenges") or []:
            s = stats[ch["type"]]
            s[1] += 1
            if ch["outcome"] == "PASSED":
                s[0] += 1
                s[2].append(ch["duration_ms"])
    rows = [(t, f"{p}/{n} ({pct(p / n)})", f"{statistics.median(d):.0f}" if d else "-")
            for t, (p, n, d) in sorted(stats.items())]
    return _table(["challenge (bona fide)", "completed", "median time to complete (ms)"], rows) if rows else \
        "No challenges were issued to bona fide presentations.\n"


def _timings(records: Sequence[Record]) -> str:
    rows = []
    groups = [("bona fide", [r for r in records if r.kind == "bona_fide"]),
              ("attack", [r for r in records if r.kind == "attack"]),
              ("all", list(records))]
    for name, rs in groups:
        if not rs:
            continue
        total = [r.t_total_ms for r in rs if r.raw.get("t_total_ms") is not None]
        passive = [r.t_passive_ms for r in rs if r.raw.get("t_passive_ms")]
        frame_p50 = [r.frame_ms["p50"] for r in rs if r.raw.get("frame_ms", {}).get("n")]
        frame_p95 = [r.frame_ms["p95"] for r in rs if r.raw.get("frame_ms", {}).get("n")]
        frames = sum(r.frame_ms.get("n", 0) for r in rs if r.raw.get("frame_ms"))
        rows.append((name, len(rs), f"{statistics.median(total):.0f} / {percentile(total, 95):.0f}" if total else "-",
                     f"{statistics.median(passive):.0f} / {percentile(passive, 95):.0f}" if passive else "-",
                     frames, f"{statistics.median(frame_p50):.1f}" if frame_p50 else "-",
                     f"{max(frame_p95):.1f}" if frame_p95 else "-"))
    return _table(["attempts", "n", "session time median / p95 (ms, stream clock)",
                   "passive stage median / p95 (ms)", "frames analysed",
                   "per-frame p50 (median over attempts, ms)", "per-frame p95 (worst attempt, ms)"], rows)


def _other(m: Metrics) -> str:
    if not m.other:
        return "None.\n"
    return _table(["scenario", "presentations", "accepted", "rejected", "no response", "ended by layer"],
                  [(k, c.total, c.accept, c.reject, c.no_response, _layers(c)) for k, c in m.other.items()])


def _expectations(records: Sequence[Record]) -> str:
    rows, bad = [], 0
    for key, txn in attempts_by_txn(records).items():
        last = txn[-1]
        exp = last.raw.get("expected_outcome")
        got = last.txn_outcome or last.outcome
        layers = set(last.raw.get("expected_layers") or [])
        layer_ok = all(a.outcome == ACCEPT or not layers or a.layer in layers for a in txn)
        ok = (exp is None or exp == got) and layer_ok
        if not ok:
            bad += 1
            rows.append((last.txn_id, exp, got, ", ".join(a.layer for a in txn),
                         "+".join(last.raw.get("reason_codes") or []) or "-"))
    head = f"{bad} of {len(attempts_by_txn(records))} transactions did not meet the scenario's expectation.\n\n"
    if not rows:
        return head
    return head + _table(["transaction", "expected", "observed", "layer per attempt", "reasons (last attempt)"], rows)


def build_report(records: Sequence[Record], det_file: Optional[str], det_ok: bool, retries: int,
                 operating_threshold: Optional[float], bona_conditions: Optional[Sequence[str]] = None) -> str:
    """Renders the Markdown report."""
    runs = sorted({r.run_id for r in records})
    models = sorted({r.raw.get("model_set_sha256", "?") for r in records})
    configs = sorted({r.raw.get("config_hash", "?") for r in records})
    platform = records[0].raw.get("platform", {}) if records else {}
    att = compute(records, "attempt")
    txn = compute(records, "transaction")
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
    parts = head + [
        "## Item under test\n",
        _table(["field", "value"], [
            ("runs", ", ".join(runs)),
            ("attempt records", len(records)),
            ("transactions", len(attempts_by_txn(records))),
            ("model set SHA-256", ", ".join((m or "?")[:16] + "..." for m in models)),
            ("configuration hashes", len(configs)),
            ("platform", ", ".join(f"{k} {v}" for k, v in platform.items())),
        ]),
        "## Evaluation type (a): per attempt\n",
        _metrics_section(att, retries),
        "\n## Evaluation type (b): per transaction (retries included)\n",
        _metrics_section(txn, retries),
        "\n## Per scenario\n",
        "Method `passive` means the passive score alone passed; `passive_active` and `active` mean challenges "
        "were needed.\n\n",
        _scenarios(records),
        "\n## Where attacks were stopped (per attempt)\n",
        _stage_attribution(records),
        "\n## Bona fide challenges\n",
        _challenges(records),
        "\n## Timings\n",
        "Session times are on the replayed stream clock (15 fps). Per-frame times are wall-clock time of "
        "`onFrame` plus `tick` on the machine named above (JPEG decode, face analysis, PAD every other frame).\n\n",
        _timings(records),
        "\n## DET of the passive score\n",
    ]
    if det_ok:
        bona, attacks = scores_for_det(records, bona_conditions)
        pts = det_points(bona, [s for v in attacks.values() for s in v])
        b20, b10 = bpcer_at_apcer(pts, 0.05), bpcer_at_apcer(pts, 0.10)
        parts.append(f"![DET]({det_file})\n\n")
        parts.append(f"One point per transaction (its first attempt); bona fide conditions: "
                     f"{', '.join(bona_conditions) if bona_conditions else 'all'}. ")
        parts.append("The curve sweeps the passive score alone; the full engine adds integrity, planarity, "
                     "challenge and binding checks, so the system's operating point is the per-attempt table "
                     "above, not this curve.\n\n")
        parts.append(f"- BPCER20 (BPCER at APCER 5%, passive score only): "
                     f"{pct(b20[0]) + f' at threshold {b20[1]:g}' if b20 else 'not reached'}\n")
        parts.append(f"- BPCER10: {pct(b10[0]) + f' at threshold {b10[1]:g}' if b10 else 'not reached'}\n")
        if operating_threshold is not None:
            parts.append(f"- Marked point: passive threshold {operating_threshold:g}.\n")
    else:
        parts.append("Not drawn (matplotlib missing, or no bona fide or attack attempt reached a passive score).\n")
    parts += ["\n## Quality and behaviour scenarios (not in APCER or BPCER)\n", _other(att),
              "\n## Expectation check\n", _expectations(records)]
    return "\n".join(parts)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="+", type=Path, help="attempts.jsonl files")
    ap.add_argument("--out", type=Path, help="Markdown report file (default: print)")
    ap.add_argument("--det", type=Path, help="DET plot PNG file (default: det.png next to --out)")
    ap.add_argument("--retries", type=int, default=3, help="attempts per transaction for attacker success")
    ap.add_argument("--threshold", type=float, default=80.0, help="passive threshold marked on the DET plot")
    ap.add_argument("--det-conditions", nargs="*", default=["C1"],
                    help="bona fide conditions on the DET plot (default C1; the option alone = all)")
    args = ap.parse_args(argv)
    records = load_records(args.inputs)
    if not records:
        print("no attempt records", file=sys.stderr)
        return 2
    det = args.det or (args.out.with_name("det.png") if args.out else None)
    conditions = args.det_conditions or None
    det_ok = plot_det(records, det, args.threshold, conditions) if det else False
    det_ref = (det.name if args.out and det and det.parent == args.out.parent else str(det)) if det else None
    report = build_report(records, det_ref, det_ok, args.retries, args.threshold, conditions)
    if args.out:
        args.out.write_text(report, encoding="utf-8")
        print(f"wrote {args.out}" + (f" and {det}" if det_ok else ""))
    else:
        print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
