"""Exhaustive build-time contract for every shipped unit capstone.

Runtime capstones are assembled from the exact unit dialogue plus its unit notes.
These tests keep all 114 authored units assessable without any text-entry fallback.
"""
from __future__ import annotations

import json
import sqlite3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "main" / "assets"


def _units() -> list[dict]:
    return json.loads((ASSETS / "units.json").read_text(encoding="utf-8"))["units"]


def _notes() -> list[dict]:
    return [
        json.loads(line)
        for line in (ASSETS / "bootstrap_notes.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def test_every_manifest_unit_has_a_complete_exact_dialogue():
    units = _units()
    assert len(units) == 114
    with sqlite3.connect(ASSETS / "tatoeba.db") as db:
        for unit in units:
            band = unit["band"]
            number = int(unit["unit"])
            dialogue_id = f"{band.lower()}_unit_{number:03d}_dialogue"
            dialogue = db.execute(
                "SELECT unit_min, title FROM dialogue WHERE id=?", (dialogue_id,)
            ).fetchone()
            assert dialogue is not None, f"{band}:{number} missing {dialogue_id}"
            assert dialogue[0] == number
            assert dialogue[1].strip()

            nodes = db.execute(
                """SELECT id, speaker, ru, en, acceptable_json, next_ids_json
                   FROM dialogue_node WHERE dialogueId=?""",
                (dialogue_id,),
            ).fetchall()
            assert len(nodes) == 6, f"{dialogue_id}: expected 6 nodes, got {len(nodes)}"
            ids = {row[0] for row in nodes}
            referenced: set[str] = set()
            learner_answers: list[str] = []
            for node_id, speaker, ru, en, acceptable_json, next_ids_json in nodes:
                assert speaker in {"npc", "learner"}
                assert ru.strip() and en.strip()
                assert any("\u0400" <= ch <= "\u04ff" for ch in ru)
                next_ids = json.loads(next_ids_json)
                assert all(next_id in ids for next_id in next_ids)
                referenced.update(next_ids)
                if speaker == "learner":
                    acceptable = json.loads(acceptable_json or "[]")
                    assert acceptable, f"{node_id}: learner turn has no accepted response"
                    assert all(any("\u0400" <= ch <= "\u04ff" for ch in answer) for answer in acceptable)
                    learner_answers.extend(acceptable)
                else:
                    assert acceptable_json is None
            roots = ids - referenced
            assert len(roots) == 1, f"{dialogue_id}: dialogue graph needs exactly one root"
            assert sum(row[1] == "learner" for row in nodes) == 3
            assert sum(row[1] == "npc" for row in nodes) == 3
            assert len({answer.strip().lower() for answer in learner_answers}) >= 3


def test_every_unit_has_exact_assessment_content_and_tap_distractors():
    notes = _notes()
    units = _units()
    tier_zero = [
        note for note in notes
        if note.get("tier") == 0 and note.get("pos") != "lesson"
    ]
    for unit in units:
        band = unit["band"]
        number = int(unit["unit"])
        assert str(unit.get("canDo", "")).strip(), f"{band}:{number} missing can-do"
        exact = [
            note for note in tier_zero
            if (note.get("cefrLevel") or "A1") == band and note.get("unit") == number
        ]
        history = [
            note for note in tier_zero
            if (note.get("cefrLevel") or "A1") == band
            and note.get("unit") is not None
            and int(note["unit"]) <= number
        ]
        # Some later units are intentionally lesson/dialogue-only. The exact
        # dialogue tested above is their assessment carrier; prior same-band
        # notes supply plausible distractors without receiving exact-unit credit.
        assert exact or history, f"{band}:{number} has no assessment vocabulary"
        meanings = {
            str(note.get("translation", "")).split(",", 1)[0].strip().lower()
            for note in history
            if str(note.get("translation", "")).strip()
        }
        assert len(meanings) >= 2, f"{band}:{number} cannot form meaning choices"
