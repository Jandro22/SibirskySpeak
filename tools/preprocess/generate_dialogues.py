#!/usr/bin/env python3
"""Generate one validated, deterministic guided role-play for every unit.

Learner turns are not invented: they are selected from the unit's shipped,
verified example sentences.  When a very small/lesson-only unit has fewer than
three usable examples, earlier examples in the same CEFR band provide review
material.  NPC turns supply a concise role-play cue and the English intention.
"""
from __future__ import annotations

import json
import re
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
ASSETS = ROOT / "app/src/main/assets"
WORD = re.compile(r"[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?")


def unstress(value: str) -> str:
    value = unicodedata.normalize("NFD", value).replace("\u0301", "")
    return unicodedata.normalize("NFC", value).strip()


def load_notes(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def usable(note: dict) -> bool:
    ru = note.get("exampleSentence") or ""
    en = note.get("exampleTranslation") or ""
    count = len(WORD.findall(ru))
    return bool(ru and en and 2 <= count <= 18 and "http" not in en.lower())


def candidates_for(unit: dict, notes: list[dict]) -> list[dict]:
    band, number = unit["band"], unit["unit"]
    exact = [n for n in notes if n.get("cefrLevel") == band and n.get("unit") == number and usable(n)]
    earlier = [n for n in notes if n.get("cefrLevel") == band and (n.get("unit") or -1) <= number and usable(n)]
    # Exact-unit language first; then nearest earlier material. Prefer concise
    # sentences and remove duplicate carriers shared by multiple lemma notes.
    ordered = sorted(exact, key=lambda n: (len(WORD.findall(n["exampleSentence"])), n.get("lemma", "")))
    ordered += sorted(earlier, key=lambda n: (number - int(n.get("unit") or 0), len(WORD.findall(n["exampleSentence"])), n.get("lemma", "")))
    result, seen = [], set()
    for note in ordered:
        key = unstress(note["exampleSentence"]).lower()
        if key in seen:
            continue
        seen.add(key)
        result.append(note)
    return result


def cue_for(band: str, index: int) -> tuple[str, str]:
    if band in {"A1", "A2"}:
        russian = ("Что вы скажете?", "Хорошо. А теперь?", "И что ещё?")[index]
    else:
        russian = ("Как бы вы это сформулировали?", "Хорошо. Продолжайте.", "И в заключение?")[index]
    return russian, "Respond in Russian"


def build_dialogue(unit: dict, notes: list[dict]) -> dict:
    selected = candidates_for(unit, notes)[:3]
    if len(selected) < 3:
        raise ValueError(f"{unit['id']}: fewer than three usable current-or-prior examples")
    dialogue_id = unit["dialogueRef"]
    nodes = []
    for index, note in enumerate(selected):
        cue_ru, cue_en = cue_for(unit["band"], index)
        npc_id = f"{dialogue_id}:{index * 2 + 1}"
        learner_id = f"{dialogue_id}:{index * 2 + 2}"
        nodes.append({
            "id": npc_id,
            "speaker": "npc",
            "ru": cue_ru,
            "en": f"{cue_en}: {note['exampleTranslation']}",
            "nextIds": [learner_id],
        })
        answer = note["exampleSentence"].strip()
        nodes.append({
            "id": learner_id,
            "speaker": "learner",
            "ru": answer,
            "en": note["exampleTranslation"].strip(),
            "acceptable": [answer, unstress(answer)],
            "sourceUnit": int(note.get("unit") or unit["unit"]),
            "sourceLemma": note.get("lemma") or note.get("russian"),
            "nextIds": [] if index == 2 else [f"{dialogue_id}:{index * 2 + 3}"],
        })
    return {
        "id": dialogue_id,
        "band": unit["band"],
        "unitMin": unit["unit"],
        "function": unit["canDo"],
        "title": f"Unit role-play: {unit['canDo']}",
        "nodes": nodes,
    }


def generate(units_path: Path, notes_path: Path, output_path: Path) -> dict:
    units = json.loads(units_path.read_text(encoding="utf-8"))["units"]
    notes = load_notes(notes_path)
    dialogues = [build_dialogue(unit, notes) for unit in units]
    output_path.write_text(json.dumps({"schemaVersion": 2, "dialogues": dialogues}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"dialogues": len(dialogues), "nodes": sum(len(d["nodes"]) for d in dialogues)}


def main() -> None:
    result = generate(HERE / "units.yaml", ASSETS / "bootstrap_notes.jsonl", HERE / "dialogues.json")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
