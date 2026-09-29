#!/usr/bin/env python3
"""Downloads the openly licensed source clips and cuts the bona fide frame packs.

Reads eval/packs/sources.yaml (what to download, licence, attribution, SHA-256) and every
eval/packs/<pack>.yaml whose recipe type is ``video_cut`` or ``image_repeat``. Writes the packs under
the frame-pack root (default $LIVENESS_FRAME_PACKS, else ../run/frame-packs next to the repository),
never into git.

  python tools/eval/fetch-frame-packs.py                      # download, cut, verify against manifests
  python tools/eval/fetch-frame-packs.py --update-manifests   # record new hashes after a recipe change
  python tools/eval/fetch-frame-packs.py --only s2-turn-left  # one pack

Cutting: ffmpeg decodes the whole clip once with the recipe's filter (crop to 4:3, scale to 640x480,
resample to 15 fps) into <root>/_full/, then frames ``first``..``last`` are copied into the pack.
Bit-exact flags make the JPEG bytes reproducible with the same ffmpeg build; another ffmpeg version may
produce different bytes, which --verify reports as differences (the packs are still usable).
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
from pathlib import Path

import packlib

FFMPEG_ARGS = ["-hide_banner", "-loglevel", "error", "-y", "-threads", "1"]
ENCODE_ARGS = ["-sws_flags", "bicubic+accurate_rnd+full_chroma_int+bitexact", "-fflags", "+bitexact",
               "-flags:v", "+bitexact", "-map_metadata", "-1", "-q:v", "3", "-start_number", "0"]


def ffmpeg_version() -> str:
    out = subprocess.run(["ffmpeg", "-version"], capture_output=True, text=True, check=True).stdout
    return out.splitlines()[0].split(" Copyright")[0]


def full_extraction(root: Path, source: dict, vf: str) -> Path:
    """Decodes a whole source clip once per filter chain into <root>/_full/ and returns the folder."""
    key = hashlib.sha256(f"{source['id']}|{vf}".encode()).hexdigest()[:10]
    out = root / "_full" / f"{source['id']}-{key}"
    done = out / ".complete"
    if done.exists():
        return out
    out.mkdir(parents=True, exist_ok=True)
    for p in out.glob("*.jpg"):
        p.unlink()
    src = root / "_sources" / source["file"]
    cmd = ["ffmpeg", *FFMPEG_ARGS, "-i", str(src), "-vf", vf, *ENCODE_ARGS, str(out / "%05d.jpg")]
    subprocess.run(cmd, check=True)
    done.write_text(" ".join(cmd), encoding="utf-8")
    return out


def command_text(source: dict, vf: str) -> str:
    """The ffmpeg command as recorded in the manifest, with portable placeholders for the paths."""
    return " ".join(["ffmpeg", *FFMPEG_ARGS, "-i", f"<root>/_sources/{source['file']}", "-vf", vf, *ENCODE_ARGS,
                     "<root>/_full/<clip>/%05d.jpg"])


def cut_video(root: Path, m: packlib.Manifest, sources: dict):
    """Builds a video_cut pack; returns the generator description and the exact command."""
    r = m.recipe
    source = sources[r["source"]]
    full = full_extraction(root, source, r["filter"])
    frames = sorted(full.glob("*.jpg"))
    first, last = int(r["first"]), int(r["last"])
    if not 0 <= first <= last < len(frames):
        raise ValueError(f"{m.id}: frames {first}-{last} outside the clip's 0-{len(frames) - 1}")
    pack = root / m.id
    packlib.reset_dir(pack)
    for i, src in enumerate(frames[first:last + 1]):
        shutil.copyfile(src, pack / packlib.FRAME_PATTERN.format(i))
    command = command_text(source, r["filter"]) + f" ; then frames {first}-{last} copied as frame_00000.jpg on"
    return f"{ffmpeg_version()} (bit-exact flags)", command


def extract_iso_image(iso: bytes, offset: int) -> bytes:
    """Returns the image bytes stored in an ISO/IEC 19794-5 face record from ``offset`` to the end."""
    return iso[offset:]


def repeat_image(root: Path, m: packlib.Manifest):
    """Builds an image_repeat pack: one repository image, byte-identical in every frame (stock MockMDS).

    Returns the generator description and a description of the steps."""
    r = m.recipe
    src = packlib.REPO_ROOT / r["file"]
    data = src.read_bytes()
    if r.get("iso_offset") is not None:
        data = extract_iso_image(data, int(r["iso_offset"]))
    if r.get("reencode_jpeg"):
        import io
        from PIL import Image

        with Image.open(io.BytesIO(data)) as im:
            buf = io.BytesIO()
            im.convert("RGB").save(buf, format="JPEG", quality=int(r.get("quality", 92)))
            data = buf.getvalue()
    pack = root / m.id
    packlib.reset_dir(pack)
    for i in range(int(r["frames"])):
        (pack / packlib.FRAME_PATTERN.format(i)).write_bytes(data)
    steps = f"read {r['file']}" + (f" from byte {r['iso_offset']}" if r.get("iso_offset") is not None else "")
    steps += (f", re-encode as JPEG quality {r.get('quality', 92)} with Pillow" if r.get("reencode_jpeg") else "")
    steps += f", write the same bytes {r['frames']} times"
    generator = "Pillow " + __import__("PIL").__version__ if r.get("reencode_jpeg") else "byte copy"
    return generator, steps


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, default=packlib.default_root(), help="frame-pack root folder")
    ap.add_argument("--only", nargs="*", help="pack ids to build (default: all)")
    ap.add_argument("--update-manifests", action="store_true", help="record new output hashes in the manifests")
    ap.add_argument("--verify-only", action="store_true", help="only compare packs on disk with the manifests")
    args = ap.parse_args(argv)

    sources = packlib.load_sources()
    manifests = packlib.load_manifests(types=["video_cut", "image_repeat"])
    if args.only:
        manifests = [m for m in manifests if m.id in set(args.only)]
    problems = []
    if not args.verify_only:
        needed = {m.recipe["source"] for m in manifests if m.recipe["type"] == "video_cut"}
        for sid in sorted(needed):
            s = sources[sid]
            digest = packlib.download(s["url"], args.root / "_sources" / s["file"], s.get("sha256"))
            print(f"source {sid}: {s['file']} sha256 {digest[:16]}... ({s['licence']})")
    for m in manifests:
        pack = args.root / m.id
        if not args.verify_only:
            if m.recipe["type"] == "video_cut":
                generator, command = cut_video(args.root, m, sources)
            else:
                generator, command = repeat_image(args.root, m)
            if args.update_manifests:
                packlib.record_outputs(m, pack, generator, int(m.recipe.get("fps", 15)))
                m.data["outputs"]["command"] = command
                packlib.write_manifest(m)
        diffs = packlib.verify_outputs(m, pack)
        status = "ok" if not diffs else f"{len(diffs)} difference(s)"
        print(f"pack {m.id}: {len(list(pack.glob('frame_*.jpg')))} frames, {status}")
        problems.extend(diffs)
    for p in problems[:20]:
        print("  " + p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
