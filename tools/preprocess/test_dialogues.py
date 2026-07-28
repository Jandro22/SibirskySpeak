import json
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIALOGUES_PATH = Path(__file__).parent / "dialogues.json"
UNITS_PATH = Path(__file__).parent / "units.yaml"
NOTES_PATH = ROOT / "app/src/main/assets/bootstrap_notes.jsonl"


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


def test_every_curriculum_unit_has_exactly_one_dialogue_with_three_learner_turns():
    units = json.loads(UNITS_PATH.read_text(encoding="utf-8"))["units"]
    dialogues = json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))["dialogues"]
    by_id = {dialogue["id"]: dialogue for dialogue in dialogues}
    assert len(by_id) == len(units) == 114
    for unit in units:
        dialogue = by_id.get(unit["dialogueRef"])
        assert dialogue, f"{unit['id']}: missing dialogue {unit['dialogueRef']}"
        assert dialogue["band"] == unit["band"]
        assert dialogue["unitMin"] == unit["unit"]
        learners = [node for node in dialogue["nodes"] if node["speaker"] == "learner"]
        assert len(learners) == 3, f"{dialogue['id']}: expected three learner turns"
        assert all(node["sourceUnit"] <= unit["unit"] for node in learners), f"{dialogue['id']}: leaks future-unit language"
        assert len({node["ru"] for node in learners}) == 3, f"{dialogue['id']}: repeats a learner turn"


def test_learner_turns_are_traceable_to_shipped_verified_examples():
    notes = [json.loads(line) for line in NOTES_PATH.read_text(encoding="utf-8").splitlines() if line.strip()]
    shipped = {
        (note.get("cefrLevel"), int(note.get("unit") or 0), note.get("exampleSentence"), note.get("exampleTranslation"))
        for note in notes
    }
    dialogues = json.loads(DIALOGUES_PATH.read_text(encoding="utf-8"))["dialogues"]
    for dialogue in dialogues:
        for node in dialogue["nodes"]:
            if node["speaker"] != "learner":
                continue
            key = (dialogue["band"], node["sourceUnit"], node["ru"], node["en"])
            assert key in shipped, f"{node['id']}: learner turn is not a shipped verified example"


def test_bundled_dialogue_tables_exist_once_built():
    db_path = ROOT / "app/src/main/assets/tatoeba.db"
    with sqlite3.connect(db_path) as db:
        present = db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='dialogue'").fetchone()[0]
        if not present:
            return  # build_dialogues is gated separately when regenerating the asset
        dialogues = db.execute("SELECT COUNT(*) FROM dialogue").fetchone()[0]
        nodes = db.execute("SELECT COUNT(*) FROM dialogue_node").fetchone()[0]
        assert dialogues == 114
        assert nodes == 114 * 6
