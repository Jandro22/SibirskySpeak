import json
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIALOGUES_PATH = Path(__file__).parent / "dialogues.json"


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


def test_bundled_dialogue_tables_exist_once_built():
    db_path = ROOT / "app/src/main/assets/tatoeba.db"
    with sqlite3.connect(db_path) as db:
        present = db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='dialogue'").fetchone()[0]
        if not present:
            return  # build_dialogues is gated separately when regenerating the asset
        dialogues = db.execute("SELECT COUNT(*) FROM dialogue").fetchone()[0]
        nodes = db.execute("SELECT COUNT(*) FROM dialogue_node").fetchone()[0]
        assert dialogues >= 1
        assert nodes >= 4
