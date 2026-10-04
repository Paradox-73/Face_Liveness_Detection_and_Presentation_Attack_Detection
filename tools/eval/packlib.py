# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Shared helpers for frame-pack manifests: loading, hashing, verifying and rewriting.

A frame pack is a folder ``<root>/<pack id>/`` of JPEG frames named ``frame_00000.jpg``,
``frame_00001.jpg`` and so on. Its manifest ``eval/packs/<pack id>.yaml`` holds the recipe
(hand-edited) and an ``outputs`` block (written by the generator scripts) with the SHA-256 of
every frame. Frame packs themselves never go into git.
"""

from __future__ import annotations

import hashlib
import os
import shutil
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_DIR = REPO_ROOT / "eval" / "packs"
SOURCES_FILE = MANIFEST_DIR / "sources.yaml"
PACKS_ENV = "LIVENESS_FRAME_PACKS"
FRAME_PATTERN = "frame_{:05d}.jpg"
USER_AGENT = "mosip-liveness-eval/1.0 (frame-pack fetcher)"

MANIFEST_HEADER = (
    "# Frame pack manifest. The recipe is edited by hand; the outputs block is written by\n"
    "# tools/eval/fetch-frame-packs.py or tools/eval/make-attack-packs.py with --update-manifests.\n"
    "# The frames themselves are never committed; regenerate them with those scripts.\n"
)


def default_root() -> Path:
    """Returns the frame-pack root: $LIVENESS_FRAME_PACKS, else ../run/frame-packs next to the repo."""
    env = os.environ.get(PACKS_ENV)
    if env:
        return Path(env)
    return REPO_ROOT.parent / "run" / "frame-packs"


def sha256_file(path: Path) -> str:
    """Returns the lower-case hex SHA-256 of a file."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def tree_sha256(files: Dict[str, str]) -> str:
    """Returns one SHA-256 over sorted ``name sha256`` lines, so a whole pack has one fingerprint."""
    text = "".join(f"{name} {digest}\n" for name, digest in sorted(files.items()))
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def hash_pack(pack_dir: Path) -> Dict[str, str]:
    """Hashes every frame of a pack folder, keyed by file name."""
    return {p.name: sha256_file(p) for p in sorted(pack_dir.glob("frame_*.jpg"))}


@dataclass
class Manifest:
    """A pack manifest and the file it came from."""

    path: Path
    data: dict

    @property
    def id(self) -> str:
        return self.data["id"]

    @property
    def recipe(self) -> dict:
        return self.data["recipe"]

    @property
    def outputs(self) -> Optional[dict]:
        return self.data.get("outputs")


def load_manifest(path: Path) -> Manifest:
    """Loads one manifest and checks the fields every pack needs."""
    with open(path, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    for key in ("id", "kind", "recipe"):
        if key not in data:
            raise ValueError(f"{path.name}: missing '{key}'")
    if path.stem != data["id"]:
        raise ValueError(f"{path.name}: id '{data['id']}' does not match the file name")
    return Manifest(path, data)


def load_manifests(directory: Path = MANIFEST_DIR, types: Optional[Iterable[str]] = None) -> List[Manifest]:
    """Loads every pack manifest in a folder (sources.yaml excluded), optionally only some recipe types."""
    wanted = set(types) if types else None
    out = []
    for path in sorted(directory.glob("*.yaml")):
        if path.name == SOURCES_FILE.name:
            continue
        m = load_manifest(path)
        if wanted is None or m.recipe.get("type") in wanted:
            out.append(m)
    return out


def load_sources(path: Path = SOURCES_FILE) -> Dict[str, dict]:
    """Loads the downloadable sources, keyed by id."""
    with open(path, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    return {s["id"]: s for s in data["sources"]}


def write_manifest(m: Manifest) -> None:
    """Writes a manifest back, keeping key order, with the standard header comment."""
    text = yaml.safe_dump(m.data, sort_keys=False, allow_unicode=True, width=110)
    m.path.write_text(MANIFEST_HEADER + text, encoding="utf-8")


def record_outputs(m: Manifest, pack_dir: Path, generator: str, fps: int) -> None:
    """Replaces the manifest's outputs block with the hashes of the frames now on disk."""
    files = hash_pack(pack_dir)
    width, height = image_size(pack_dir / FRAME_PATTERN.format(0))
    m.data["outputs"] = {
        "generator": generator,
        "width": width,
        "height": height,
        "fps": fps,
        "frames": len(files),
        "tree_sha256": tree_sha256(files),
        "files": files,
    }


def verify_outputs(m: Manifest, pack_dir: Path) -> List[str]:
    """Compares the frames on disk with the manifest; returns human-readable differences."""
    expected = (m.outputs or {}).get("files")
    if not expected:
        return [f"{m.id}: manifest has no recorded outputs"]
    actual = hash_pack(pack_dir)
    problems = []
    for name in sorted(set(expected) | set(actual)):
        if name not in actual:
            problems.append(f"{m.id}: missing {name}")
        elif name not in expected:
            problems.append(f"{m.id}: unexpected {name}")
        elif expected[name] != actual[name]:
            problems.append(f"{m.id}: {name} differs")
    return problems


def image_size(path: Path):
    """Returns (width, height) of an image file."""
    from PIL import Image

    with Image.open(path) as im:
        return im.size


def reset_dir(path: Path) -> None:
    """Empties a pack folder (only frame files and the folder itself are touched)."""
    if path.exists():
        for p in path.glob("frame_*.jpg"):
            p.unlink()
    path.mkdir(parents=True, exist_ok=True)


def download(url: str, target: Path, expected_sha256: Optional[str] = None) -> str:
    """Downloads a file unless an identical copy exists; returns its SHA-256.

    Raises ValueError when the downloaded bytes do not match ``expected_sha256``.
    """
    if target.exists() and (expected_sha256 is None or sha256_file(target) == expected_sha256):
        return sha256_file(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as out:
        shutil.copyfileobj(resp, out)
    digest = sha256_file(tmp)
    if expected_sha256 and digest != expected_sha256:
        tmp.unlink()
        raise ValueError(f"{url}: SHA-256 {digest} does not match the manifest ({expected_sha256})")
    tmp.replace(target)
    return digest
