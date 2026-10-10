# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Statistics helpers for the attempt analysis: exact binomial intervals, rates, percentiles and a bootstrap."""

from __future__ import annotations

import math
import random
from dataclasses import dataclass
from typing import Dict, Optional, Sequence, Tuple

CONFIDENCE = 0.95
BOOTSTRAP_RESAMPLES = 2000
BOOTSTRAP_SEED = 30107


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
