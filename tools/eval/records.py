# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Reads attempt records (``liveness-eval-attempt/1`` JSON lines) and groups them into transactions and workflows."""

from __future__ import annotations

import json
from collections import OrderedDict, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Sequence

SCHEMA = "liveness-eval-attempt/1"
ACCEPT, REJECT, NO_RESPONSE = "ACCEPT", "REJECT", "NO_RESPONSE"


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
