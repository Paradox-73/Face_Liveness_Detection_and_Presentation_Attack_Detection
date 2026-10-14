# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Tests for tools/eval/ingest-recordings.py: recording file names become frame packs and runnable scenarios."""

import importlib.util
from pathlib import Path

import pytest
import yaml

_EVAL = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("ingest_recordings", _EVAL / "ingest-recordings.py")
ingest = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ingest)


def _clips(*names):
    return [ingest.parse_name(Path(n)) for n in names]


def test_name_gives_subject_condition_clip_and_pack():
    c = ingest.parse_name(Path("S2_C1_turn-left.mp4"))
    assert (c.subject, c.condition, c.clip, c.kind) == ("S2", "C1", "turn-left", "bona_fide")
    assert c.pack == "rec-s2-c1-turn-left"


def test_name_is_case_insensitive_and_attack_conditions_are_species():
    c = ingest.parse_name(Path("s12_p7_Blink.MOV"))
    assert (c.subject, c.condition, c.clip, c.kind, c.pack) == ("S12", "P7", "blink", "attack", "rec-s12-p7-blink")


@pytest.mark.parametrize("name", ["S2_C1_wave.mp4", "Kanav_C1_blink.mp4", "S2_C9_blink.mp4", "S2-C1-blink.mp4",
                                  "S2_C1_blink.txt", "S2_C1_blink_take2.mp4", "S123_C1_blink.mp4"])
def test_names_outside_the_convention_are_refused(name):
    with pytest.raises(ValueError):
        ingest.parse_name(Path(name))


def test_find_clips_ignores_other_files_and_reports_bad_video_names(tmp_path):
    for n in ["S1_C1_neutral.mp4", "S1_C1_blink.mp4", "recordings.sha256", "notes.txt", "S1_C1_nod.mp4"]:
        (tmp_path / n).write_bytes(b"")
    clips, errors = ingest.find_clips(tmp_path)
    assert [c.path.name for c in clips] == ["S1_C1_blink.mp4", "S1_C1_neutral.mp4"]
    assert len(errors) == 1 and errors[0].startswith("S1_C1_nod.mp4")


def test_two_files_for_one_recording_are_refused(tmp_path):
    for n in ["S1_C1_blink.mp4", "S1_C1_blink.mov", "s1_c1_Smile.mp4", "S1_C1_smile.mkv"]:
        (tmp_path / n).write_bytes(b"")
    clips, errors = ingest.find_clips(tmp_path)
    assert sorted(c.clip for c in clips) == ["blink", "smile"]
    assert len(errors) == 2


def test_output_inside_the_repository_is_refused(tmp_path, capsys):
    (tmp_path / "S1_C1_neutral.mp4").write_bytes(b"")
    assert ingest.main(["--recordings", str(tmp_path), "--out", str(ingest.packlib.REPO_ROOT / "eval")]) == 2
    assert "inside the repository" in capsys.readouterr().err
    assert (ingest.packlib.REPO_ROOT / "eval" / "scenarios" / "bf-s3-registration.yaml").exists()


def test_genuine_group_answers_prompts_with_its_own_actions_only():
    scenarios, skipped = ingest.build_scenarios(_clips("S1_C2_neutral.mp4", "S1_C2_smile.mp4", "S1_C2_blink.mp4"))
    assert skipped == []
    (s,) = scenarios
    assert s["id"] == "rec-bf-s1-c2"
    assert (s["kind"], s["subject"], s["condition"], s["workflow"]) == ("bona_fide", "S1", "C2", "registration")
    assert "species" not in s
    assert s["config"] == {"active.challenge_types": "BLINK,SMILE"}
    assert s["stream"] == {"main": "rec-s1-c2-neutral", "actions": {"BLINK": "rec-s1-c2-blink",
                                                                     "SMILE": "rec-s1-c2-smile"}}
    assert s["expect"] == {"outcome": "accept"}


def test_attack_without_action_clips_ignores_prompts_and_keeps_the_shipped_pool():
    (s,), _ = ingest.build_scenarios(_clips("S3_P6_neutral.mp4"))
    assert (s["id"], s["kind"], s["species"]) == ("rec-atk-s3-p6", "attack", "P6")
    assert "condition" not in s and "config" not in s
    assert s["stream"] == {"main": "rec-s3-p6-neutral"}
    assert s["expect"] == {"outcome": "reject"}


def test_adaptive_replay_answers_each_prompt_with_the_matching_clip():
    (s,), _ = ingest.build_scenarios(_clips("S3_P7_neutral.mp4", "S3_P7_turn-right.mp4", "S3_P7_look-down.mp4"))
    assert s["config"] == {"active.challenge_types": "LOOK_DOWN,TURN_RIGHT"}
    assert s["stream"]["actions"] == {"LOOK_DOWN": "rec-s3-p7-look-down", "TURN_RIGHT": "rec-s3-p7-turn-right"}


def test_incomplete_groups_are_skipped_with_a_reason():
    scenarios, skipped = ingest.build_scenarios(_clips(
        "S1_C1_blink.mp4", "S1_C1_smile.mp4",          # no neutral clip
        "S2_C1_neutral.mp4", "S2_C1_blink.mp4",        # genuine with one action
        "S3_P7_neutral.mp4", "S3_P7_blink.mp4",        # attack with one action
        "S4_C1_neutral.mp4", "S4_C1_blink.mp4", "S4_C1_smile.mp4"))
    assert [s["id"] for s in scenarios] == ["rec-bf-s4-c1"]
    assert [x.split(":")[0] for x in skipped] == ["S1_C1", "S2_C1", "S3_P7"]


def test_written_scenarios_load_back_unchanged_and_replace_an_earlier_run(tmp_path):
    (tmp_path / "rec-bf-s9-c1.yaml").write_text("stale", encoding="utf-8")
    scenarios, _ = ingest.build_scenarios(_clips("S1_C1_neutral.mp4", "S1_C1_blink.mp4", "S1_C1_smile.mp4",
                                                 "S1_P1_neutral.mp4"))
    ingest.write_scenarios(scenarios, tmp_path)
    files = sorted(p.name for p in tmp_path.glob("*.yaml"))
    assert files == ["rec-atk-s1-p1.yaml", "rec-bf-s1-c1.yaml"]
    for s in scenarios:
        assert yaml.safe_load((tmp_path / f"{s['id']}.yaml").read_text(encoding="utf-8")) == s
