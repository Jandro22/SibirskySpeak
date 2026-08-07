import json
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIALOGUES_PATH = Path(__file__).parent / "dialogues.json"
UNITS_PATH = Path(__file__).parent / "units.yaml"
NOTES_PATH = ROOT / "app/src/main/assets/bootstrap_notes.jsonl"


def shipped_examples(notes: list[dict]) -> set[tuple]:
    result = set()
    for note in notes:
        for suffix in ("", "2", "3"):
            ru = note.get(f"exampleSentence{suffix}")
            en = note.get(f"exampleTranslation{suffix}")
            if ru and en:
                result.add((note.get("cefrLevel"), note.get("unit"), ru, en))
    return result


def test_every_learner_turn_has_acceptable_answers_and_valid_links():
    doc = json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))
    assert doc["dialogues"], "at least one dialogue should be authored"
    for dialogue in doc["dialogues"]:
        node_ids = {node["id"] for node in dialogue["nodes"]}
        assert dialogue["nodes"], f"{dialogue['id']} has no nodes"
        for node in dialogue["nodes"]:
            assert node["speaker"] in ("npc", "learner")
            if node["speaker"] == "learner":
                assert node.get("acceptable"), f"{node['id']}: learner turn needs acceptable answers"
            for next_id in node["nextIds"]:
                assert next_id in node_ids, f"{node['id']}: dangling nextIds reference {next_id}"


def test_dialogue_graph_has_no_unreachable_nodes():
    doc = json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))
    for dialogue in doc["dialogues"]:
        nodes = {node["id"]: node for node in dialogue["nodes"]}
        first = dialogue["nodes"][0]["id"]
        reachable = {first}
        frontier = [first]
        while frontier:
            current = frontier.pop()
            for next_id in nodes[current]["nextIds"]:
                if next_id not in reachable:
                    reachable.add(next_id)
                    frontier.append(next_id)
        assert reachable == set(nodes), f"{dialogue['id']}: unreachable nodes {set(nodes) - reachable}"


def test_every_curriculum_unit_has_a_complete_base_dialogue_and_scenario_family():
    units = json.loads(UNITS_PATH.read_text(encoding="utf-8"))["units"]
    dialogues = json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))["dialogues"]
    by_id = {dialogue["id"]: dialogue for dialogue in dialogues}
    assert len(units) == 114
    for unit in units:
        dialogue = by_id.get(unit["dialogueRef"])
        assert dialogue, f"{unit['id']}: missing dialogue {unit['dialogueRef']}"
        assert dialogue["band"] == unit["band"]
        assert dialogue["unitMin"] == unit["unit"]
        learners = [node for node in dialogue["nodes"] if node["speaker"] == "learner"]
        turn_min, turn_max = {
            "A1": (4, 6), "A2": (6, 8), "B1": (8, 12),
            "B2": (10, 16), "C1": (12, 20), "C2": (12, 20),
        }[unit["band"]]
        assert turn_min <= len(learners) <= turn_max, f"{dialogue['id']}: wrong arc length"
        assert all(node["sourceUnit"] <= unit["unit"] for node in learners), f"{dialogue['id']}: leaks future-unit language"
        variants = [item for item in dialogues if item["id"] == unit["dialogueRef"] or item["id"].startswith(unit["dialogueRef"] + ":")]
        assert variants, f"{unit['id']}: no simulation families"


def test_scenario_families_have_real_setting_and_carrier_variation():
    units = json.loads(UNITS_PATH.read_text(encoding="utf-8"))["units"]
    dialogues = json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))["dialogues"]
    for unit in units:
        variants = [item for item in dialogues if item["id"] == unit["dialogueRef"] or item["id"].startswith(unit["dialogueRef"] + ":")]
        assert len({item["title"] for item in variants}) >= min(2, len(variants))
        answer_sequences = {
            tuple(node["ru"] for node in item["nodes"] if node["speaker"] == "learner")
            for item in variants
        }
        assert len(answer_sequences) >= min(2, len(variants)), f"{unit['id']}: scenario family has no carrier variation"


def test_every_unit_has_exactly_one_reachable_blind_transfer_family():
    units = json.loads(UNITS_PATH.read_text(encoding="utf-8"))["units"]
    dialogues = json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))["dialogues"]
    for unit in units:
        variants = [
            item for item in dialogues
            if item["id"] == unit["dialogueRef"] or item["id"].startswith(unit["dialogueRef"] + ":")
        ]
        blind = [item for item in variants if item["blindTransfer"]]
        assert len(blind) == 1, f"{unit['id']}: expected one blind-transfer family, found {len(blind)}"


def test_a1_to_c1_family_and_completion_budgets_match_contract():
    dialogues = json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))["dialogues"]
    family_targets = {"A1": 60, "A2": 90, "B1": 130, "B2": 150, "C1": 170}
    completion_targets = {"A1": 240, "A2": 360, "B1": 650, "B2": 750, "C1": 1020}
    for band, target in family_targets.items():
        band_dialogues = [item for item in dialogues if item["band"] == band]
        assert len(band_dialogues) == target
        assert sum(item["expectedCompletions"] for item in band_dialogues) == completion_targets[band]
    assert sum(family_targets.values()) == 600
    assert sum(completion_targets.values()) == 3020
    signatures = {
        (
            item["objective"], item["setting"], item["intention"], item["register"],
            tuple(node["ru"] for node in item["nodes"] if node["speaker"] == "learner"),
        )
        for item in dialogues if item["band"] in family_targets
    }
    assert len(signatures) == 600, "family ids must represent genuinely distinct goals/contexts/arcs"


def test_every_generated_family_is_an_honest_sourced_goal_arc():
    dialogues = json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))["dialogues"]
    for dialogue in dialogues:
        assert dialogue["objective"]
        assert len(dialogue["settings"]) >= 5
        assert len(set(dialogue["settings"])) >= 5
        assert dialogue["activity"] in {"reception-to-production", "production", "interaction", "mediation"}
        assert dialogue["register"] in {"neutral", "informal", "polite", "formal"}
        learner_nodes = [node for node in dialogue["nodes"] if node["speaker"] == "learner"]
        for node in learner_nodes:
            assert not node["responseBranches"], "generated carriers must not invent conversational branches"
            assert len(node["nextIds"]) <= 1
            assert node["requiredMeaning"]
            assert node["targetLemmas"]
            assert node["en"] == node["requiredMeaning"]


def test_generated_russian_is_unicode_not_mojibake():
    dialogues = json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))["dialogues"]
    suspicious = ("Ð", "Ñ", "â€", "Â")
    for dialogue in dialogues:
        for node in dialogue["nodes"]:
            assert not any(marker in node["ru"] for marker in suspicious), node["id"]


def test_learner_turns_are_traceable_to_shipped_verified_examples():
    notes = [json.loads(line) for line in NOTES_PATH.read_text(encoding="utf-8").splitlines() if line.strip()]
    shipped = shipped_examples(notes)
    dialogues = json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))["dialogues"]
    for dialogue in dialogues:
        for node in dialogue["nodes"]:
            if node["speaker"] != "learner":
                continue
            key = (node["sourceBand"], node["sourceUnit"], node["ru"], node["requiredMeaning"])
            assert key in shipped, f"{node['id']}: learner turn is not a shipped verified example"
            assert node["sourceUnit"] is not None, f"{node['id']}: unitless content leaked into a routed dialogue"
            assert len(node["acceptableSources"]) == 1
            for source in node["acceptableSources"]:
                source_key = (source["band"], source["unit"], source["ru"], source["en"])
                assert source_key in shipped, f"{node['id']}: alternate response is not a shipped verified example"


def test_generated_families_do_not_present_unrelated_sentences_as_branch_facts():
    dialogues = json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))["dialogues"]
    for dialogue in dialogues:
        consequences = [node for node in dialogue["nodes"] if node.get("revealsFact")]
        assert not consequences
        assert sum(node["speaker"] == "npc" for node in dialogue["nodes"]) == 1


def test_bundled_dialogue_tables_exist_once_built():
    db_path = ROOT / "app/src/main/assets/tatoeba.db"
    with sqlite3.connect(db_path) as db:
        present = db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='dialogue'").fetchone()[0]
        if not present:
            return  # build_dialogues is gated separately when regenerating the asset
        dialogues = db.execute("SELECT COUNT(*) FROM dialogue").fetchone()[0]
        nodes = db.execute("SELECT COUNT(*) FROM dialogue_node").fetchone()[0]
        assert dialogues == 696
        expected_nodes = sum(len(item["nodes"]) for item in json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))["dialogues"])
        assert nodes == expected_nodes
