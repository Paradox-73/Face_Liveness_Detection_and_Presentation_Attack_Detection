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
GENUINE_WRONG_ACTION_SPECIES = ("SYN-A9",)
LEVELS = ("attempt", "transaction", "first_attempt")


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

    def compact(self) -> str:
        """Rate, count and two-sided interval in one table cell; zero errors add the one-sided bound."""
        if self.n == 0:
            return "n/a"
        lo, hi = self.interval
        s = f"{pct(self.value)} ({self.errors}/{self.n}; CI {pct(lo)}-{pct(hi)}"
        if self.errors == 0:
            s += f"; one-sided <= {pct(self.upper_one_sided)}"
        return s + ")"

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
    """Attempts of every transaction, in attempt order (``attempt_no`` when recorded, else file order)."""
    out: Dict[str, List[Record]] = OrderedDict()
    for r in records:
        out.setdefault(f"{r.run_id}/{r.txn_id}", []).append(r)
    for key, txn in out.items():
        if all(a.raw.get("attempt_no") is not None for a in txn):
            out[key] = sorted(txn, key=lambda a: int(a.raw["attempt_no"]))
    return out


def workflow_of(r: Record) -> str:
    """The workflow a record belongs to, lower case so sheet values (LOGIN) and runner values (login) agree."""
    return str(r.raw.get("workflow") or "-").lower()


def by_workflow(records: Sequence[Record]) -> Dict[str, List[Record]]:
    """Records split by workflow, workflows in name order."""
    out: Dict[str, List[Record]] = defaultdict(list)
    for r in records:
        out[workflow_of(r)].append(r)
    return OrderedDict(sorted(out.items()))


def packs_of(r: Record) -> List[str]:
    """Artefacts (frame packs) one attempt used: ``artefact_id`` when recorded, else the packs streamed."""
    if r.raw.get("artefact_id"):
        return [str(r.raw["artefact_id"])]
    return sorted((r.raw.get("frames_per_pack") or {}).keys())


def artefact_unit(r: Record) -> str:
    """The source artefact an attempt replays, the unit of the artefact-level interval.

    ``artefact_id`` when the record names one; otherwise the source subject, because the packs of one
    species made from one person's material (a print and its tilted views, the clips of one adaptive
    replay) are views of one artefact, not independent artefacts.
    """
    return str(r.raw.get("artefact_id") or r.raw.get("subject_id") or "-")


def is_wrong_action(r: Record, genuine_species: Sequence[str]) -> bool:
    """An 'attack' row that actually shows a genuine face (a genuine clip of the wrong action)."""
    return r.raw.get("kind") == "attack" and r.raw.get("species") in genuine_species


# --------------------------------------------------------------------------- metrics


@dataclass
class KindCounts:
    """Outcome counts for one group of presentations, with the artefacts and subjects behind them."""

    accept: int = 0
    reject: int = 0
    no_response: int = 0
    layers: Counter = field(default_factory=Counter)
    packs: set = field(default_factory=set)
    subjects: set = field(default_factory=set)
    units: Dict[str, List[int]] = field(default_factory=dict)

    def add(self, outcome: str, layer: Optional[str] = None, members: Sequence[Record] = ()) -> None:
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
        for m in members:
            self.packs.update(packs_of(m))
            if m.raw.get("subject_id"):
                self.subjects.add(m.raw["subject_id"])
        if members:
            unit = self.units.setdefault(artefact_unit(members[0]), [0, 0])
            if outcome == ACCEPT:
                unit[0] += 1
            if outcome in (ACCEPT, REJECT):
                unit[1] += 1

    def merge(self, other: "KindCounts") -> None:
        self.accept += other.accept
        self.reject += other.reject
        self.no_response += other.no_response
        self.layers.update(other.layers)
        self.packs |= other.packs
        self.subjects |= other.subjects
        for k, (a, n) in other.units.items():
            u = self.units.setdefault(k, [0, 0])
            u[0] += a
            u[1] += n

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

    def artefact_apcer(self) -> Rate:
        """Source artefacts accepted at least once / source artefacts with a decision: the interval over
        artefacts, the independent units of a replay test."""
        decided = [a for a, n in self.units.values() if n > 0]
        return Rate(sum(1 for a in decided if a > 0), len(decided))

    def artefact_text(self) -> str:
        """Distinct packs (or artefact ids; '-' when none is recorded) and distinct subjects."""
        return f"{len(self.packs) or '-'} / {len(self.subjects)}"


@dataclass
class Metrics:
    """All figures for one level (attempt, transaction or first attempt)."""

    level: str
    species: Dict[str, KindCounts]
    bona_fide: KindCounts
    bona_fide_by_condition: Dict[str, KindCounts]
    bona_fide_by_subject: Dict[str, KindCounts]
    other: Dict[str, KindCounts]
    wrong_action: Dict[str, KindCounts] = field(default_factory=dict)

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
            total.merge(c)
        return total

    def bpcer_bootstrap(self) -> Optional[Tuple[float, float]]:
        return bootstrap_rate({s: (c.reject, c.decided) for s, c in self.bona_fide_by_subject.items()})


def _presentations(records: Sequence[Record], level: str) -> List[Tuple[Record, str, Optional[str], List[Record]]]:
    """(representative record, outcome, layer, attempts) for every presentation at ``level``."""
    if level == "attempt":
        return [(r, r.outcome, r.layer, [r]) for r in records]
    rows = []
    for txn in attempts_by_txn(records).values():
        if level == "first_attempt":
            first = txn[0]
            rows.append((first, first.outcome, first.layer, [first]))
            continue
        last = txn[-1]
        outcome = last.raw.get("txn_outcome") or last.outcome
        layer = next((a.layer for a in reversed(txn) if a.outcome == outcome), last.layer)
        rows.append((last, outcome, layer, txn))
    return rows


def compute(records: Sequence[Record], level: str = "attempt",
            genuine_species: Sequence[str] = GENUINE_WRONG_ACTION_SPECIES) -> Metrics:
    """Counts outcomes per attack species, bona fide condition and subject.

    ``level`` is ``attempt`` (evaluation type a: every attempt is a presentation), ``transaction``
    (type b: a transaction with retries is one presentation, accepted if any attempt was accepted) or
    ``first_attempt`` (attempt 1 of every transaction only). Attack rows of ``genuine_species`` go to
    ``wrong_action`` and never into APCER.
    """
    if level not in LEVELS:
        raise ValueError(level)
    species: Dict[str, KindCounts] = defaultdict(KindCounts)
    wrong: Dict[str, KindCounts] = defaultdict(KindCounts)
    bf = KindCounts()
    by_cond: Dict[str, KindCounts] = defaultdict(KindCounts)
    by_subj: Dict[str, KindCounts] = defaultdict(KindCounts)
    other: Dict[str, KindCounts] = defaultdict(KindCounts)
    for r, outcome, layer, members in _presentations(records, level):
        if is_wrong_action(r, genuine_species):
            wrong[r.species].add(outcome, layer, members)
        elif r.kind == "attack":
            species[r.species].add(outcome, layer, members)
        elif r.kind == "bona_fide":
            bf.add(outcome, layer, members)
            by_cond[r.raw.get("condition") or "-"].add(outcome, layer, members)
            by_subj[r.raw.get("subject_id") or "-"].add(outcome, layer, members)
        else:
            other[r.scenario].add(outcome, layer, members)
    return Metrics(level, dict(sorted(species.items())), bf, dict(sorted(by_cond.items())),
                   dict(sorted(by_subj.items())), dict(sorted(other.items())), dict(sorted(wrong.items())))


# --------------------------------------------------------------------------- full system (IAPAR)


def has_match_results(records: Sequence[Record]) -> bool:
    """True when at least one record carries a face-match result (``match_outcome`` ACCEPT or REJECT)."""
    return any(r.raw.get("match_outcome") in (ACCEPT, REJECT) for r in records)


def full_system_outcome(attempts: Sequence[Record]) -> str:
    """Outcome of liveness plus face matcher over the given attempts.

    ``ACCEPT``: some attempt passed liveness and the matcher. ``MISSING``: some attempt passed liveness but
    its match result is not recorded. ``NO_RESPONSE``: no attempt produced a liveness decision. Otherwise
    ``REJECT`` (liveness or the matcher refused every attempt).
    """
    if any(a.outcome == ACCEPT and a.raw.get("match_outcome") == ACCEPT for a in attempts):
        return ACCEPT
    if any(a.outcome == ACCEPT and a.raw.get("match_outcome") not in (ACCEPT, REJECT) for a in attempts):
        return "MISSING"
    if all(a.outcome == NO_RESPONSE for a in attempts):
        return NO_RESPONSE
    return REJECT


@dataclass
class FullSystemCounts:
    """Liveness-plus-matcher outcomes for one group of transactions."""

    accept: int = 0
    reject: int = 0
    no_response: int = 0
    missing: int = 0
    first_accept: int = 0
    first_decided: int = 0
    matcher_refused: int = 0

    @property
    def total(self) -> int:
        return self.accept + self.reject + self.no_response + self.missing

    def accept_rate(self) -> Rate:
        """IAPAR for attacks: accepted by both / transactions with a decision."""
        return Rate(self.accept, self.accept + self.reject)

    def reject_rate(self) -> Rate:
        """FRR for genuine users: refused by liveness or the matcher / transactions with a decision."""
        return Rate(self.reject, self.accept + self.reject)

    def first_accept_rate(self) -> Rate:
        return Rate(self.first_accept, self.first_decided)

    def first_reject_rate(self) -> Rate:
        return Rate(self.first_decided - self.first_accept, self.first_decided)


def compute_full_system(records: Sequence[Record], genuine_species: Sequence[str] = GENUINE_WRONG_ACTION_SPECIES
                        ) -> Tuple[Dict[str, FullSystemCounts], FullSystemCounts]:
    """IAPAR counts per attack species and FRR counts for bona fide, per transaction and first attempt.

    Every attack transaction counts, including those liveness refused before the matcher ran (their
    ``match_outcome`` is empty): an impostor attack presentation is accepted only when liveness passes and
    the matcher accepts.
    """
    species: Dict[str, FullSystemCounts] = defaultdict(FullSystemCounts)
    bona = FullSystemCounts()
    for txn in attempts_by_txn(records).values():
        r = txn[0]
        if r.kind == "attack" and not is_wrong_action(r, genuine_species):
            c = species[r.species]
        elif r.kind == "bona_fide":
            c = bona
        else:
            continue
        outcome = full_system_outcome(txn)
        if outcome == ACCEPT:
            c.accept += 1
        elif outcome == REJECT:
            c.reject += 1
        elif outcome == NO_RESPONSE:
            c.no_response += 1
        else:
            c.missing += 1
        if any(a.outcome == ACCEPT and a.raw.get("match_outcome") == REJECT for a in txn):
            c.matcher_refused += 1
        first = full_system_outcome(txn[:1])
        if first in (ACCEPT, REJECT):
            c.first_decided += 1
            c.first_accept += first == ACCEPT
    return dict(sorted(species.items())), bona


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


def scores_for_det(records: Sequence[Record], bona_conditions: Optional[Sequence[str]] = None,
                   genuine_species: Sequence[str] = GENUINE_WRONG_ACTION_SPECIES
                   ) -> Tuple[List[float], Dict[str, List[float]]]:
    """Passive scores for the DET: one per transaction (its first attempt, because retries replay the same
    frames and would only repeat the score), bona fide limited to ``bona_conditions`` when given.

    Genuine clips of the wrong action (``genuine_species``) are left out: their faces are live, so they
    would count as attacks the passive score cannot stop and make every APCER target unreachable.
    """
    bona, attacks = [], defaultdict(list)
    for txn in attempts_by_txn(records).values():
        r = txn[0]
        s = r.raw.get("passive_score")
        if s is None or is_wrong_action(r, genuine_species):
            continue
        if r.kind == "bona_fide":
            if bona_conditions and r.raw.get("condition") not in bona_conditions:
                continue
            bona.append(float(s))
        elif r.kind == "attack":
            attacks[r.species].append(float(s))
    return bona, dict(sorted(attacks.items()))


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
        pts = det_points(bona, scores)
        b20, b10 = bpcer_at_apcer(pts, 0.05), bpcer_at_apcer(pts, 0.10)
        rows.append((wf, len(bona), len(scores),
                     f"{pct(b20[0])} at threshold {b20[1]:g}" if b20 else "not reached",
                     f"{pct(b10[0])} at threshold {b10[1]:g}" if b10 else "not reached"))
    return _table(["workflow", "bona fide transactions", "attack transactions",
                   "BPCER20 (BPCER at APCER 5%, passive score only)", "BPCER10"], rows)


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
