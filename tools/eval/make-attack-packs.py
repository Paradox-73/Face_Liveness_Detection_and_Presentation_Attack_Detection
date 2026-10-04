#!/usr/bin/env python3
# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Builds the synthetic attack and quality frame packs from the bona fide packs.

Reads every eval/packs/<pack>.yaml whose recipe type is ``synthetic`` and writes the pack under the
frame-pack root (run tools/eval/fetch-frame-packs.py first: the synthetic packs start from its frames).
All randomness comes from the recipe's seed, so the output is reproducible with the same Pillow and
numpy versions.

  python tools/eval/make-attack-packs.py                      # build and verify against the manifests
  python tools/eval/make-attack-packs.py --update-manifests   # record new hashes after a recipe change

Generators (recipe.generator):
  photo      one base frame treated as a physical artefact (look: print | screen | none) and moved
             (motion: still | affine | yaw), with fresh camera noise in every frame
  video      every frame of a base pack with a look applied (screen | dark | blur | none)
  loop       a short run of base frames copied unchanged; the scenario loops it
  identical  one base frame's bytes repeated (an injected still image)
  blank      a scene with no face
  pair       two base frames side by side (two faces)
"""

from __future__ import annotations

import argparse
import io
import math
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image

import packlib
import synth

JPEG_QUALITY = 90


def load_frame(root: Path, pack: str, index: int) -> np.ndarray:
    with Image.open(root / pack / packlib.FRAME_PATTERN.format(index)) as im:
        return synth.to_array(im)


def save(a: np.ndarray, path: Path) -> None:
    buf = io.BytesIO()
    synth.to_image(a).save(buf, format="JPEG", quality=JPEG_QUALITY, subsampling=2, optimize=False)
    path.write_bytes(buf.getvalue())


def apply_look(a: np.ndarray, look: str, t: float, params: dict) -> np.ndarray:
    if look == "print":
        return synth.print_look(a)
    if look == "screen":
        return synth.screen_look(a, t)
    if look == "dark":
        return synth.scale_brightness(a, float(params.get("brightness", 0.2)))
    if look == "blur":
        return synth.blur(a, float(params.get("radius", 6.0)))
    if look in (None, "none"):
        return a
    raise ValueError(f"unknown look: {look}")


def photo_motion(a: np.ndarray, motion: str, i: int, n: int, rng: np.random.Generator, params: dict,
                 fps: int) -> np.ndarray:
    """Moves the artefact for frame i of n."""
    tremor = float(params.get("tremor_px", 0.8))
    jx, jy = rng.normal(0, tremor, 2)
    if motion == "still":
        return synth.affine(a, jx, jy, 1.0, 0.0)
    if motion == "affine":
        t = i / fps
        dx = float(params.get("pan_px", 25)) * math.sin(2 * math.pi * t / 3.0)
        dy = 0.5 * float(params.get("pan_px", 25)) * math.sin(2 * math.pi * t / 4.3)
        zoom = 1.0 + float(params.get("zoom", 0.06)) * math.sin(2 * math.pi * t / 2.7)
        roll = float(params.get("roll_deg", 6)) * math.sin(2 * math.pi * t / 3.7)
        return synth.affine(a, dx + jx, dy + jy, zoom, roll)
    if motion == "yaw":
        pre = int(params.get("pre_frames", 8))
        ramp = int(params.get("ramp_frames", 6))
        target = float(params["yaw_deg"])
        progress = min(1.0, max(0.0, (i - pre) / ramp)) if ramp > 0 else float(i >= pre)
        turned = synth.rotate_photo(a, target * progress, float(params.get("focal_px", 700)))
        return synth.affine(turned, jx, jy, 1.0, 0.0)
    raise ValueError(f"unknown motion: {motion}")


def build(root: Path, m: packlib.Manifest) -> None:
    r = m.recipe
    gen = r["generator"]
    params = r.get("params", {}) or {}
    fps = int(r.get("fps", 15))
    rng = np.random.default_rng(int(r.get("seed", 1)))
    out = root / m.id
    packlib.reset_dir(out)
    noise = float(params.get("noise_sigma", 2.5))
    if gen == "photo":
        base = load_frame(root, r["base"], int(r["base_frame"]))
        look = r.get("look", "none")
        static_look = apply_look(base, look, 0.0, params) if look != "screen" else None
        for i in range(int(r["frames"])):
            art = static_look if static_look is not None else apply_look(base, look, i / fps, params)
            frame = photo_motion(art, r.get("motion", "still"), i, int(r["frames"]), rng, params, fps)
            save(synth.sensor_noise(frame, rng, noise), out / packlib.FRAME_PATTERN.format(i))
    elif gen == "video":
        src = sorted((root / r["base"]).glob("frame_*.jpg"))
        first, last = int(r.get("first", 0)), int(r.get("last", len(src) - 1))
        for i, path in enumerate(src[first:last + 1]):
            with Image.open(path) as im:
                a = apply_look(synth.to_array(im), r.get("look", "none"), i / fps, params)
            if noise > 0:
                a = synth.sensor_noise(a, rng, noise)
            save(a, out / packlib.FRAME_PATTERN.format(i))
    elif gen == "loop":
        src = sorted((root / r["base"]).glob("frame_*.jpg"))
        for i, path in enumerate(src[int(r["first"]):int(r["last"]) + 1]):
            shutil.copyfile(path, out / packlib.FRAME_PATTERN.format(i))
    elif gen == "identical":
        data = (root / r["base"] / packlib.FRAME_PATTERN.format(int(r["base_frame"]))).read_bytes()
        for i in range(int(r["frames"])):
            (out / packlib.FRAME_PATTERN.format(i)).write_bytes(data)
    elif gen == "blank":
        w, h = int(params.get("width", 640)), int(params.get("height", 480))
        scene = synth.blank_scene(w, h, rng)
        for i in range(int(r["frames"])):
            save(synth.sensor_noise(scene, rng, noise), out / packlib.FRAME_PATTERN.format(i))
    elif gen == "pair":
        pair = synth.side_by_side(load_frame(root, r["base"], int(r["base_frame"])),
                                  load_frame(root, r["base2"], int(r["base2_frame"])))
        for i in range(int(r["frames"])):
            save(synth.sensor_noise(pair, rng, noise), out / packlib.FRAME_PATTERN.format(i))
    else:
        raise ValueError(f"{m.id}: unknown generator {gen}")


def generator_text() -> str:
    import PIL

    return f"tools/eval/make-attack-packs.py with Pillow {PIL.__version__}, numpy {np.__version__}"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, default=packlib.default_root(), help="frame-pack root folder")
    ap.add_argument("--only", nargs="*", help="pack ids to build (default: all synthetic packs)")
    ap.add_argument("--update-manifests", action="store_true", help="record new output hashes in the manifests")
    ap.add_argument("--verify-only", action="store_true", help="only compare packs on disk with the manifests")
    args = ap.parse_args(argv)

    manifests = packlib.load_manifests(types=["synthetic"])
    if args.only:
        manifests = [m for m in manifests if m.id in set(args.only)]
    problems = []
    for m in manifests:
        pack = args.root / m.id
        if not args.verify_only:
            base = m.recipe.get("base")
            if base and not (args.root / base).is_dir():
                raise SystemExit(f"{m.id}: base pack {base} missing; run fetch-frame-packs.py first")
            build(args.root, m)
            if args.update_manifests:
                packlib.record_outputs(m, pack, generator_text(), int(m.recipe.get("fps", 15)))
                packlib.write_manifest(m)
        diffs = packlib.verify_outputs(m, pack)
        print(f"pack {m.id}: {len(list(pack.glob('frame_*.jpg')))} frames, "
              f"{'ok' if not diffs else str(len(diffs)) + ' difference(s)'}")
        problems.extend(diffs)
    for p in problems[:20]:
        print("  " + p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
