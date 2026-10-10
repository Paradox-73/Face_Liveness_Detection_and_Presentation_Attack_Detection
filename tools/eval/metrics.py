# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""PAD metrics: APCER, BPCER, APNRR, BPNRR, IAPAR and FRR counts, and the DET points of the passive score."""

from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

from records import ACCEPT, NO_RESPONSE, REJECT, Record, is_wrong_action, packs_of, artefact_unit, attempts_by_txn
from stats import Rate, bootstrap_rate

GENUINE_WRONG_ACTION_SPECIES = ("SYN-A9",)
LEVELS = ("attempt", "transaction", "first_attempt")


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


def det_points_worst_species(bona_scores: Sequence[float], attacks: Dict[str, List[float]]
                             ) -> List[Tuple[float, float, float]]:
    """Like ``det_points`` but APCER is the highest APCER over the attack species at each threshold.

    ISO/IEC 30107-3 defines BPCER at a given APCER_AP, the worst species; pooling species hides a hard one
    behind many easy ones.
    """
    species = [v for v in attacks.values() if v]
    pooled = [s for v in species for s in v]
    return [(t, max(sum(1 for s in v if s >= t) / len(v) for v in species), bpcer)
            for t, _, bpcer in det_points(bona_scores, pooled)]


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
