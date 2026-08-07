#!/usr/bin/env python3
"""Load dialogues.json (curated dialogues, P6.2) into the dialogue/dialogue_node
tables of tatoeba.db, after validating the node graph is well-formed.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def validate(doc: dict) -> None:
    for dialogue in doc["dialogues"]:
        node_ids = {node["id"] for node in dialogue["nodes"]}
        for node in dialogue["nodes"]:
            if node["speaker"] not in ("npc", "learner"):
                raise SystemExit(f"{node['id']}: speaker must be 'npc' or 'learner'")
            if node["speaker"] == "learner" and not node.get("acceptable"):
                raise SystemExit(f"{node['id']}: learner turns need at least one acceptable answer")
            for next_id in node["nextIds"]:
                if next_id not in node_ids:
                    raise SystemExit(f"{node['id']}: nextIds references unknown node {next_id}")
        if not dialogue["nodes"]:
            raise SystemExit(f"{dialogue['id']}: dialogue has no nodes")


def build(db_path: Path, dialogues_path: Path, room_schema: Path) -> dict:
    doc = json.loads(dialogues_path.read_text(encoding="utf-8"))
    validate(doc)
    db = sqlite3.connect(db_path)
    db.executescript("""
        DROP TABLE IF EXISTS dialogue;
        DROP TABLE IF EXISTS dialogue_node;
        CREATE TABLE dialogue(
          id TEXT NOT NULL PRIMARY KEY, unit_min INTEGER NOT NULL, function TEXT NOT NULL, title TEXT NOT NULL,
          band TEXT NOT NULL, objective TEXT NOT NULL, settingsJson TEXT NOT NULL, intention TEXT NOT NULL,
          register TEXT NOT NULL, activity TEXT NOT NULL, informationGap TEXT NOT NULL,
          expectedCompletions INTEGER NOT NULL, blindTransfer INTEGER NOT NULL
        );
        CREATE TABLE dialogue_node(id TEXT NOT NULL PRIMARY KEY, dialogueId TEXT NOT NULL, speaker TEXT NOT NULL,
          ru TEXT NOT NULL, en TEXT NOT NULL, acceptable_json TEXT, next_ids_json TEXT NOT NULL);
        CREATE INDEX index_dialogue_node_dialogueId ON dialogue_node(dialogueId);
    """)
    dialogue_rows = [(
        d["id"], d["unitMin"], d["function"], d["title"], d["band"], d["objective"],
        json.dumps(d["settings"], ensure_ascii=False), d["intention"], d["register"], d["activity"],
        d["informationGap"], d["expectedCompletions"], int(d["blindTransfer"])
    ) for d in doc["dialogues"]]
    node_rows = [
        (
            node["id"], dialogue["id"], node["speaker"], node["ru"], node["en"],
            json.dumps(node["acceptable"], ensure_ascii=False) if node.get("acceptable") else None,
            json.dumps(node["nextIds"], ensure_ascii=False),
        )
        for dialogue in doc["dialogues"]
        for node in dialogue["nodes"]
    ]
    db.executemany("INSERT INTO dialogue VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", dialogue_rows)
    db.executemany("INSERT INTO dialogue_node VALUES(?,?,?,?,?,?,?)", node_rows)
    schema = json.loads(room_schema.read_text(encoding="utf-8"))
    db.execute("INSERT OR REPLACE INTO room_master_table VALUES(42, ?)", (schema["database"]["identityHash"],))
    db.execute("PRAGMA user_version=11")
    db.commit()
    db.execute("VACUUM")
    db.close()
    return {"dialogues": len(dialogue_rows), "nodes": len(node_rows)}


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser()
    p.add_argument("--db", type=Path, default=ROOT / "app/src/main/assets/tatoeba.db")
    p.add_argument("--dialogues", type=Path, default=Path(__file__).parent / "dialogues.json")
    p.add_argument("--room-schema", type=Path, default=ROOT / "app/schemas/com.sibirskyspeak.data.ContentDatabase/11.json")
    return p


if __name__ == "__main__":
    args = parser().parse_args()
    print(json.dumps(build(args.db, args.dialogues, args.room_schema), ensure_ascii=False, indent=2))
