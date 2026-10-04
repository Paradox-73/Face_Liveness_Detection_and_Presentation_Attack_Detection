# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Tests for the frame-pack helpers (packlib) and the synthetic attack transforms (synth)."""

import numpy as np
import pytest
from PIL import Image

import packlib
import synth


def frame(seed=0, h=48, w=64):
    rng = np.random.default_rng(seed)
    return rng.integers(0, 256, (h, w, 3), dtype=np.uint8)


def test_tree_hash_is_order_independent():
    a = {"frame_00001.jpg": "bb", "frame_00000.jpg": "aa"}
    assert packlib.tree_sha256(a) == packlib.tree_sha256(dict(reversed(list(a.items()))))
    assert packlib.tree_sha256(a) != packlib.tree_sha256({"frame_00000.jpg": "aa"})


def test_verify_outputs_reports_missing_changed_and_extra_frames(tmp_path):
    pack = tmp_path / "p"
    pack.mkdir()
    Image.fromarray(frame()).save(pack / "frame_00000.jpg")
    m = packlib.Manifest(tmp_path / "p.yaml", {"id": "p", "kind": "x", "recipe": {}})
    packlib.record_outputs(m, pack, "test", 15)
    assert m.outputs["frames"] == 1 and (m.outputs["width"], m.outputs["height"]) == (64, 48)
    assert packlib.verify_outputs(m, pack) == []
    Image.fromarray(frame(1)).save(pack / "frame_00000.jpg")
    Image.fromarray(frame(2)).save(pack / "frame_00001.jpg")
    problems = packlib.verify_outputs(m, pack)
    assert any("differs" in p for p in problems) and any("unexpected" in p for p in problems)


def test_manifest_id_must_match_file_name(tmp_path):
    f = tmp_path / "a.yaml"
    f.write_text("id: b\nkind: bona_fide\nrecipe: {type: video_cut}\n", encoding="utf-8")
    with pytest.raises(ValueError):
        packlib.load_manifest(f)


def test_committed_manifests_are_consistent():
    manifests = packlib.load_manifests()
    ids = {m.id for m in manifests}
    sources = packlib.load_sources()
    for m in manifests:
        r = m.recipe
        if r["type"] == "video_cut":
            assert r["source"] in sources, m.id
        base = r.get("base")
        if base:
            assert base in ids, f"{m.id}: base {base} has no manifest"
        assert m.outputs and m.outputs["frames"] == len(m.outputs["files"]), m.id
        if m.data["kind"] != "bona_fide":
            assert "SYNTHETIC" in m.data["description"] or r["type"] == "image_repeat", m.id
    for s in sources.values():
        assert s["licence"] and s["url"].startswith("https://") and len(s["sha256"]) == 64


def test_affine_identity_keeps_the_image():
    a = frame()
    assert np.abs(synth.affine(a, 0, 0, 1.0, 0.0).astype(int) - a).mean() < 1.0


def test_affine_shift_moves_content():
    a = np.zeros((40, 40, 3), dtype=np.uint8)
    a[10:20, 10:20] = 255
    b = synth.affine(a, 5, 0, 1.0, 0.0, fill=(0, 0, 0))
    assert b[15, 22].mean() > 200 and b[15, 12].mean() < 50


def test_zero_yaw_perspective_is_identity():
    c = synth.perspective_coefficients(640, 480, 0.0, 700)
    assert c == pytest.approx((1, 0, 0, 0, 1, 0, 0, 0))


def test_rotated_photo_is_foreshortened():
    a = np.full((60, 80, 3), 200, dtype=np.uint8)
    b = synth.rotate_photo(a, 40, 100, fill=(0, 0, 0))
    assert (b[:, :, 0] == 0).sum() > 0          # the tilted photo no longer fills the frame


def test_looks_and_noise_are_deterministic_and_change_pixels():
    a = frame()
    for look in (synth.print_look(a), synth.screen_look(a, 0.3)):
        assert look.shape == a.shape and look.dtype == np.uint8
        assert np.abs(look.astype(int) - a).mean() > 1
    n1 = synth.sensor_noise(a, np.random.default_rng(5), 2.5)
    n2 = synth.sensor_noise(a, np.random.default_rng(5), 2.5)
    assert np.array_equal(n1, n2) and not np.array_equal(n1, a)


def test_side_by_side_keeps_size():
    assert synth.side_by_side(frame(1), frame(2)).shape == (48, 64, 3)
