#!/usr/bin/env python3
"""Generate the shipped A1-C1 scenario-family curriculum.

Every learner utterance remains traceable to a shipped, verified example.  The
generator supplies an honest goal and setting around those carriers. It does
not claim that unrelated corpus sentences are conversational consequences of
one another: only curated authored content may make that claim. Generated
families are linear banks of sourced communicative moves which the runtime
turns into listening, retrieval, guided response, repair, and transfer tasks.

The family and expected-completion budgets intentionally match the product
curriculum contract.  A1/A2 families recur four times, B1/B2 five times, and C1
six times: 60*4 + 90*4 + 130*5 + 150*5 + 170*6 = 3,020 completions.
"""
from __future__ import annotations

import json
import re
import unicodedata
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
ASSETS = ROOT / "app/src/main/assets"
WORD = re.compile(r"[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?")

FAMILY_TARGETS = {"A1": 60, "A2": 90, "B1": 130, "B2": 150, "C1": 170}
COMPLETIONS_PER_FAMILY = {"A1": 4, "A2": 4, "B1": 5, "B2": 5, "C1": 6}
# C2 is retained as an optional post-C1 extension and is excluded from the 600 /
# 3,020 contract requested for comprehensive A1-C1 education.
OPTIONAL_C2_FAMILIES = 96
TURN_RANGES = {"A1": (4, 6), "A2": (6, 8), "B1": (8, 12), "B2": (10, 16), "C1": (12, 20), "C2": (12, 20)}

SETTINGS = {
    "A1": ["a first meeting", "a classroom", "home", "a shop", "a café", "public transport", "a clinic desk", "a neighbourhood errand"],
    "A2": ["a practical errand", "a short phone call", "a weekend plan", "a work shift", "a travel desk", "a service counter", "a neighbour's home", "a minor disruption"],
    "B1": ["a travel disruption", "a workplace discussion", "a social plan", "a service complaint", "a news conversation", "a personal decision", "a community event", "an unexpected change"],
    "B2": ["a difficult negotiation", "a professional meeting", "a public discussion", "a formal request", "a cultural misunderstanding", "an evidence review", "a conflict-resolution meeting", "a multi-step project"],
    "C1": ["a policy discussion", "an academic exchange", "a professional briefing", "a public interview", "a formal complaint", "a mediation", "a specialist consultation", "a high-stakes decision"],
    "C2": ["a specialist seminar", "a public debate", "an institutional briefing", "a difficult mediation", "a high-stakes negotiation", "an editorial meeting", "a nuanced interview", "a stylistic review"],
}

ACTIVITIES = ["interaction", "mediation", "production", "reception-to-production"]
REGISTERS = ["neutral", "informal", "polite", "formal"]
INTENTIONS = ["request", "clarify", "compare", "correct", "disagree", "relay", "negotiate", "summarize"]
NPC_PROMPTS = [
    "Что вы скажете сначала?",
    "Какую информацию вам нужно узнать?",
    "Спросите об одной важной детали.",
    "Собеседник вас не понял. Скажите иначе.",
    "Сравните варианты и выберите один.",
    "Объясните причину своего выбора.",
    "Передайте другому человеку, что вы узнали.",
    "Уточните, правильно ли вы всё поняли.",
    "Возникла новая проблема. Предложите решение.",
    "Вежливо возразите и объясните почему.",
    "Проверьте, что собеседник согласен.",
    "Подведите итог разговора.",
    "Добавьте условие, которое важно учесть.",
    "Завершите разговор и подтвердите результат.",
    "Объясните это человеку, который не слышал начало.",
    "Исправьте последнее недоразумение.",
    "Сопоставьте сказанное с новой информацией.",
    "Сформулируйте окончательное решение.",
    "Кратко обоснуйте итог.",
    "Передайте результат следующему участнику.",
]


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


def response_variants(note: dict) -> list[tuple[str, str]]:
    pairs = [
        (note.get("exampleSentence"), note.get("exampleTranslation")),
        (note.get("exampleSentence2"), note.get("exampleTranslation2")),
        (note.get("exampleSentence3"), note.get("exampleTranslation3")),
    ]
    result, seen = [], set()
    for ru, en in pairs:
        if not ru or not en or not 2 <= len(WORD.findall(ru)) <= 18:
            continue
        key = unstress(ru).casefold()
        if key not in seen:
            seen.add(key)
            result.append((ru.strip(), en.strip()))
    return result


def candidates_for(unit: dict, notes: list[dict]) -> list[dict]:
    band, number = unit["band"], unit["unit"]
    ranks = {value: index for index, value in enumerate(("A1", "A2", "B1", "B2", "C1", "C2"))}
    exact = [n for n in notes if n.get("cefrLevel") == band and n.get("unit") == number and usable(n)]
    # Unitless textbook/corpus notes are supplemental material, not unit zero.
    # Treating None as 0 leaked arbitrary phrases and advanced carrier sentences
    # into the first A1 dialogue families.
    earlier = [
        n for n in notes
        if n.get("unit") is not None
        and ranks.get(n.get("cefrLevel"), 99) <= ranks[band]
        and int(n["unit"]) <= number
        and usable(n)
    ]
    ordered = sorted(exact, key=lambda n: (len(WORD.findall(n["exampleSentence"])), n.get("lemma", "")))
    ordered += sorted(earlier, key=lambda n: (number - int(n["unit"]), len(WORD.findall(n["exampleSentence"])), n.get("lemma", "")))
    result, seen = [], set()
    for note in ordered:
        key = unstress(note["exampleSentence"]).casefold()
        if key not in seen:
            seen.add(key)
            result.append(note)
    if len(result) < TURN_RANGES[band][0] + 1:
        raise ValueError(f"{unit['id']}: fewer than {TURN_RANGES[band][0] + 1} usable current-or-prior examples")
    return result


def allocate_families(units: list[dict]) -> dict[str, int]:
    grouped: dict[str, list[dict]] = defaultdict(list)
    for unit in units:
        grouped[unit["band"]].append(unit)
    targets = dict(FAMILY_TARGETS, C2=OPTIONAL_C2_FAMILIES)
    allocation: dict[str, int] = {}
    for band, band_units in grouped.items():
        target = targets[band]
        base, extra = divmod(target, len(band_units))
        for index, unit in enumerate(band_units):
            allocation[unit["id"]] = base + (1 if index < extra else 0)
    return allocation


def turn_count(band: str, family_index: int) -> int:
    low, high = TURN_RANGES[band]
    return low + family_index % (high - low + 1)


def build_dialogue(unit: dict, notes: list[dict], family_index: int, family_count: int) -> dict:
    pool = candidates_for(unit, notes)
    count = turn_count(unit["band"], family_index)
    start = (family_index * 7 + unit["unit"] * 3) % len(pool)
    selected = [pool[(start + offset) % len(pool)] for offset in range(count + 1)]
    base_id = unit["dialogueRef"]
    # Every unit needs at least one authored blind carrier or independent
    # production certification is unreachable. A1 has only two or three
    # families per unit, so a modulo-four rule left every A1 unit without one.
    blind = family_index == family_count - 1
    dialogue_id = base_id if family_index == 0 else (
        f"{base_id}:family-{family_index + 1}" + (":blind-transfer" if blind else "")
    )
    setting = SETTINGS[unit["band"]][family_index % len(SETTINGS[unit["band"]])]
    context_variants = [
        SETTINGS[unit["band"]][(family_index + offset) % len(SETTINGS[unit["band"]])]
        for offset in range(5)
    ]
    activity = ACTIVITIES[(unit["unit"] + family_index) % len(ACTIVITIES)]
    register = REGISTERS[(unit["unit"] * 2 + family_index) % len(REGISTERS)]
    intention = INTENTIONS[(unit["unit"] + family_index * 3) % len(INTENTIONS)]
    nodes: list[dict] = []

    opening_id = f"{dialogue_id}:opening"
    first_learner_id = f"{dialogue_id}:learner-1"
    nodes.append({
        "id": opening_id,
        "speaker": "npc",
        "ru": "Здравствуйте. Давайте решим эту задачу вместе.",
        "en": f"You are in {setting}. Your objective is to {unit['canDo']}. Some information is still missing.",
        "nextIds": [first_learner_id],
        "scene": 1,
    })

    for index in range(count):
        primary = selected[index]
        primary_variants = response_variants(primary)
        primary_text, primary_meaning = primary_variants[0]
        learner_id = f"{dialogue_id}:learner-{index + 1}"
        next_learner = f"{dialogue_id}:learner-{index + 2}" if index + 1 < count else None
        nodes.append({
            "id": learner_id,
            "speaker": "learner",
            "ru": primary_text,
            "en": primary_meaning,
            # Stress and punctuation variants of one sourced move are valid.
            # A different sentence with a different meaning is not an alternate
            # answer merely because it came from the same vocabulary note.
            "acceptable": list(dict.fromkeys([primary_text, unstress(primary_text)])),
            "responseBranches": [],
            "requiredMeaning": primary_meaning,
            "requiredMeanings": [primary_meaning],
            "targetLemmas": [primary.get("lemma") or primary.get("russian")],
            "acceptableSources": [
                {"band": primary.get("cefrLevel"), "unit": int(primary.get("unit") or 0), "ru": primary_text, "en": primary_meaning},
            ],
            "sourceUnit": int(primary.get("unit") or 0),
            "sourceBand": primary.get("cefrLevel") or unit["band"],
            "sourceLemma": primary.get("lemma") or primary.get("russian"),
            "nextIds": [next_learner] if next_learner else [],
            "scene": 1 + index // 4,
        })

    repetitions = COMPLETIONS_PER_FAMILY.get(unit["band"], 6)
    return {
        "id": dialogue_id,
        "band": unit["band"],
        "unitMin": unit["unit"],
        "function": unit["canDo"],
        "title": f"{setting.title()}: {unit['canDo']}",
        "objective": unit["canDo"],
        "setting": setting,
        "settings": context_variants,
        "intention": intention,
        "register": register,
        "activity": activity,
        "informationGap": "",
        "expectedCompletions": repetitions,
        "blindTransfer": blind,
        "nodes": nodes,
    }


def generate(units_path: Path, notes_path: Path, output_path: Path) -> dict:
    units = json.loads(units_path.read_text(encoding="utf-8"))["units"]
    notes = load_notes(notes_path)
    allocation = allocate_families(units)
    dialogues = [
        build_dialogue(unit, notes, family_index, allocation[unit["id"]])
        for unit in units
        for family_index in range(allocation[unit["id"]])
    ]
    output_path.write_text(json.dumps({"schemaVersion": 3, "dialogues": dialogues}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    contracted = [d for d in dialogues if d["band"] in FAMILY_TARGETS]
    return {
        "dialogues": len(dialogues),
        "a1ToC1Families": len(contracted),
        "expectedCompletions": sum(d["expectedCompletions"] for d in contracted),
        "nodes": sum(len(d["nodes"]) for d in dialogues),
    }


def main() -> None:
    result = generate(HERE / "units.yaml", ASSETS / "bootstrap_notes.jsonl", HERE / "dialogues.json")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
