"""Checks the committed scenario suite (eval/scenarios) against the frame-pack manifests (eval/packs).

These are static checks of the YAML files; the scenario runner itself is tested by its own Maven build.
"""

import re

import pytest
import yaml

import packlib

SCENARIO_DIR = packlib.REPO_ROOT / "eval" / "scenarios"
SUPERVISOR_WORKFLOWS = {"packet_auth": "packet-auth", "exception_auth": "exception-auth", "eod_auth": "eod-auth"}
COPIED_ATTACKS = ("print-still", "print-moved", "looped-clip", "screen-replay")
LOOP_SECONDS = (2, 4, 8)
FPS = 15


def load_scenarios():
    out = {}
    for path in sorted(SCENARIO_DIR.glob("*.yaml")):
        with open(path, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        assert data["id"] == path.stem, f"{path.name}: id {data['id']} does not match the file name"
        out[data["id"]] = data
    return out


SCENARIOS = load_scenarios()
MANIFESTS = {m.id: m for m in packlib.load_manifests()}


def packs_of(sc):
    stream = sc["stream"]
    packs = [stream["main"], *(stream.get("actions") or {}).values()]
    if stream.get("fixed_pack"):
        packs.append(stream["fixed_pack"])
    return packs


def pool_override(sc):
    return (sc.get("config") or {}).get("active.challenge_types")


def is_adaptive(sc_id):
    return "adaptive" in sc_id


def test_every_scenario_pack_has_a_manifest():
    for sc in SCENARIOS.values():
        for pack in packs_of(sc):
            assert pack in MANIFESTS, f"{sc['id']}: pack {pack} has no manifest in eval/packs"


def test_attacks_that_do_not_answer_prompts_run_on_the_shipped_pool():
    for sc in SCENARIOS.values():
        if sc["kind"] == "attack" and not is_adaptive(sc["id"]):
            assert pool_override(sc) is None, f"{sc['id']} narrows the shipped challenge pool"


def test_every_narrowed_pool_states_its_reason():
    narrowed = [sc for sc in SCENARIOS.values() if pool_override(sc) is not None]
    assert narrowed, "expected the bona fide scenarios to keep a two-action pool"
    for sc in narrowed:
        assert sc["kind"] in ("bona_fide", "other") or is_adaptive(sc["id"]), sc["id"]
        notes = sc.get("notes") or ""
        assert "challenge pool is limited" in notes and "genuine clips" in notes, sc["id"]
        for action in pool_override(sc).split(","):
            assert action.strip() in (sc["stream"].get("actions") or {}), \
                f"{sc['id']}: pool action {action} has no clip to answer it"


@pytest.mark.parametrize("subject", ["s2", "s3"])
@pytest.mark.parametrize("attack", COPIED_ATTACKS)
@pytest.mark.parametrize("workflow", sorted(SUPERVISOR_WORKFLOWS))
def test_attacks_are_copied_to_the_supervisor_workflows(subject, attack, workflow):
    base = SCENARIOS[f"atk-{subject}-{attack}"]
    copy = SCENARIOS.get(f"atk-{subject}-{attack}-{SUPERVISOR_WORKFLOWS[workflow]}")
    assert copy is not None, f"missing atk-{subject}-{attack}-{SUPERVISOR_WORKFLOWS[workflow]}"
    assert copy["workflow"] == workflow
    assert copy["kind"] == "attack" and copy["expect"]["outcome"] == "reject"
    assert copy["stream"] == base["stream"] and copy["species"] == base["species"]
    assert copy.get("repeat") == base.get("repeat")
    # the shipped policy and pool of the workflow are what is tested
    assert not any(k.startswith("policy.") for k in (copy.get("config") or {}))
    assert pool_override(copy) is None


def test_supervisor_workflow_suffix_matches_the_workflow():
    pattern = re.compile(r"-(packet|exception|eod)-auth$")
    for sc in SCENARIOS.values():
        m = pattern.search(sc["id"])
        if m:
            assert sc["workflow"] == f"{m.group(1)}_auth", sc["id"]


@pytest.mark.parametrize("subject", ["s2", "s3"])
@pytest.mark.parametrize("seconds", LOOP_SECONDS)
def test_long_loop_packs_have_the_stated_period(subject, seconds):
    m = MANIFESTS[f"a-{subject}-loop-{seconds}s"]
    r = m.recipe
    frames = seconds * FPS
    assert r["generator"] == "loop" and int(r["fps"]) == FPS
    assert int(r["last"]) - int(r["first"]) + 1 == frames
    assert m.outputs["frames"] == frames
    base = MANIFESTS[r["base"]]
    assert base.data["kind"] == "bona_fide" and base.data["subject"] == subject.upper()
    assert int(r["last"]) < base.outputs["frames"], f"{m.id} reads past the end of {base.id}"
    assert m.data["labels"]["species"] == "SYN-A8" and "SYNTHETIC" in m.data["description"]


@pytest.mark.parametrize("subject", ["s2", "s3"])
@pytest.mark.parametrize("seconds", LOOP_SECONDS)
@pytest.mark.parametrize("suffix,workflow", [("", "registration"), ("-packet-auth", "packet_auth")])
def test_long_loop_scenarios_replay_the_pack_in_a_loop(subject, seconds, suffix, workflow):
    sc = SCENARIOS[f"atk-{subject}-looped-clip-{seconds}s{suffix}"]
    assert sc["workflow"] == workflow and sc["kind"] == "attack" and sc["species"] == "SYN-A8"
    assert sc["stream"]["main"] == f"a-{subject}-loop-{seconds}s" and sc["stream"]["at_end"] == "loop"
    assert sc["expect"]["outcome"] == "reject"
    assert pool_override(sc) is None
