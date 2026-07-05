# -*- coding: utf-8 -*-
"""Report ALL curriculum violations at once (not just the first failing assert),
so a batch of new content can be authored and then fixed in one pass.

Usage:  python audit_curriculum.py
"""
from __future__ import annotations

import re

from a1_starter import a1_rows
from a2_starter import a2_rows
from b1_starter import b1_rows
from b2_starter import b2_rows
from c1_starter import c1_rows
from c2_starter import c2_rows
from russian_morph import strip_stress
from test_curriculum import CLOSED_CLASS, _surface_forms, _example_pairs
from curriculum_common import spine2_rows

WORD_RE = re.compile(r"[а-яё-]+", re.IGNORECASE)
STRESS = "́"
# Stress check runs on raw text, so the token regex must keep the stress mark
# attached (otherwise "Чита́й" splits into "Чита" + "й" and false-flags).
STRESSED_WORD_RE = re.compile(r"[а-яёА-ЯЁ" + STRESS + "-]+")
VOWELS = set("аеёиоуыэюяАЕЁИОУЫЭЮЯ")


def _norm(text):
    return strip_stress(text).lower().replace("ё", "е")


def all_rows():
    return sorted(
        a1_rows() + a2_rows() + b1_rows() + b2_rows() + c1_rows() + c2_rows() + spine2_rows(),
        key=lambda n: (n["unit"], n["pos"] != "lesson"),
    )


def _needs_stress(word):
    if STRESS in word or "ё" in word or "Ё" in word:
        return False
    return sum(1 for ch in word if ch in VOWELS) >= 2


# G9: A1 thematic/semantic-field coverage checklist. Each field's minimum note
# count is deliberately modest (this is a floor, not a target) — it exists so
# a future edit can't silently delete an entire semantic field down to zero
# notes without the audit noticing. Fields are read from the `topic:<name>`
# tag tokens curriculum_common.build_level appends per-unit (G9); see
# a1_starter.py's per-unit "topic" key.
A1_THEMATIC_CHECKLIST = {
    "family": 3,
    "food_drink": 3,
    "body_health": 3,
    "weather": 3,
    "city_transport": 3,
    "time": 3,
    "home": 3,
    "clothing": 3,
    "emotions": 3,
    "numerals": 15,
}

# G9: cardinals/time-telling/price chunks are chunk-first vocabulary at A1 —
# the productive numeral+noun case-government system (NUMERAL_GOV_234/5) is
# a later B1 concept. This is a floor on total numeral-related A1 notes.
A1_MIN_NUMERAL_NOTES = 30


def _topics(note):
    return {tok[6:] for tok in note.get("tags", "").split() if tok.startswith("topic:")}


def main():
    rows = all_rows()
    known = {_norm(w) for w in CLOSED_CLASS}
    by_unit = {}
    for note in rows:
        by_unit.setdefault(note["unit"], []).append(note)

    vocab_problems = []
    stress_problems = []
    gloss_problems = []
    for unit in sorted(by_unit):
        for note in by_unit[unit]:
            if note["pos"] != "lesson":
                known |= _surface_forms(note)
        for note in by_unit[unit]:
            for ru, en in _example_pairs(note):
                for token in WORD_RE.findall(_norm(ru)):
                    if token not in known:
                        vocab_problems.append(
                            f"  U{unit} {note['cefrLevel']} {note['lemma']}: "
                            f"'{token}' in {ru!r}"
                        )
                for w in STRESSED_WORD_RE.findall(ru):
                    if _needs_stress(w):
                        stress_problems.append(f"  U{unit} {note['lemma']}: '{w}' in {ru!r}")
                if note["pos"] != "lesson":
                    if not en or len(en.split()) < 2 or en.strip().lower() == note["translation"].strip().lower():
                        gloss_problems.append(f"  U{unit} {note['lemma']}: weak gloss {en!r}")

    a1_notes = [n for n in rows if n["cefrLevel"] == "A1" and n["pos"] != "lesson"]
    topic_counts = {}
    for note in a1_notes:
        for topic in _topics(note):
            topic_counts[topic] = topic_counts.get(topic, 0) + 1

    thematic_problems = []
    for field, minimum in A1_THEMATIC_CHECKLIST.items():
        count = topic_counts.get(field, 0)
        if count < minimum:
            thematic_problems.append(f"  {field}: {count} notes (need >= {minimum})")

    numeral_count = sum(1 for n in a1_notes if "numerals" in _topics(n))
    if numeral_count < A1_MIN_NUMERAL_NOTES:
        thematic_problems.append(
            f"  numeral note count: {numeral_count} (need >= {A1_MIN_NUMERAL_NOTES})"
        )

    def report(title, items):
        print(f"\n{title}: {len(items)}")
        for it in items[:80]:
            print(it)

    report("UNCONTROLLED VOCAB", vocab_problems)
    report("MISSING STRESS", stress_problems)
    report("WEAK GLOSS", gloss_problems)
    report("A1 THEMATIC COVERAGE", thematic_problems)
    total = len(vocab_problems) + len(stress_problems) + len(gloss_problems) + len(thematic_problems)
    print(f"\nTOTAL problems: {total} | notes: {len(rows)}")


if __name__ == "__main__":
    main()
