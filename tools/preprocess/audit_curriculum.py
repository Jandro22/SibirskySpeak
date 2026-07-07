# -*- coding: utf-8 -*-
"""Report ALL curriculum violations at once (not just the first failing assert),
so a batch of new content can be authored and then fixed in one pass.

Usage:  python audit_curriculum.py
"""
from __future__ import annotations

import re
import json
from pathlib import Path

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
    import sys
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
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

    # 1. Concept ID bi-directional validation
    here = Path(__file__).resolve().parent
    concepts_file = here / "concepts.json"
    concept_problems = []
    if concepts_file.exists():
        concepts_data = json.loads(concepts_file.read_text(encoding="utf-8"))
        defined_concepts = {c["id"] for c in concepts_data["core_concepts"]} | {s["id"] for s in concepts_data["staged_specs"]}
        taught_concepts = {n["conceptId"] for n in rows if n.get("pos") == "lesson" and "conceptId" in n}
        
        # Forward check: taught concepts must be defined
        for c in taught_concepts:
            if c not in defined_concepts:
                concept_problems.append(f"  Taught concept {c!r} is not defined in concepts.json")
        # Reverse check: defined concepts must be taught
        for c in defined_concepts:
            if c not in taught_concepts:
                concept_problems.append(f"  Defined concept {c!r} has no LESSON note in the curriculum")
    else:
        concept_problems.append("  concepts.json not found, skipping concept validation")

    # 2. Frequency vs. CEFR level mismatch check
    gen_source = here / "general_source.jsonl"
    mismatch_problems = []
    if gen_source.exists():
        ranks = {}
        with open(gen_source, "r", encoding="utf-8") as f:
            for line in f:
                e = json.loads(line)
                lem = _norm(e.get("lemma", ""))
                rank = e.get("rank")
                if rank:
                    ranks[lem] = int(rank)
                    
        band_indices = {"A1": 0, "A2": 1, "B1": 2, "B2": 3, "C1": 4, "C2": 5}
        for note in rows:
            if note.get("pos") == "lesson":
                continue
            lemma = note.get("lemma")
            level = note.get("cefrLevel")
            if not lemma or not level or level not in band_indices:
                continue
            
            rank = ranks.get(lemma)
            if not rank:
                continue
                
            # Expected band based on rank
            if rank <= 1000:
                exp_idx = 0
            elif rank <= 2000:
                exp_idx = 1
            elif rank <= 4000:
                exp_idx = 2
            elif rank <= 6000:
                exp_idx = 3
            elif rank <= 8000:
                exp_idx = 4
            else:
                exp_idx = 5
                
            auth_idx = band_indices[level]
            if abs(exp_idx - auth_idx) >= 3:
                mismatch_problems.append(
                    f"  U{note['unit']} {level} {note['lemma']}: "
                    f"rank {rank} (expected {list(band_indices.keys())[exp_idx]}) "
                    f"differs by {abs(exp_idx - auth_idx)} bands"
                )

    def report(title, items):
        print(f"\n{title}: {len(items)}")
        for it in items[:80]:
            print(it)

    report("UNCONTROLLED VOCAB", vocab_problems)
    report("MISSING STRESS", stress_problems)
    report("WEAK GLOSS", gloss_problems)
    report("A1 THEMATIC COVERAGE", thematic_problems)
    report("CONCEPT ALIGNMENT", concept_problems)
    report("FREQUENCY LEVEL MISMATCH", mismatch_problems)
    total = len(vocab_problems) + len(stress_problems) + len(gloss_problems) + len(thematic_problems) + len(concept_problems) + len(mismatch_problems)
    print(f"\nTOTAL problems: {total} | notes: {len(rows)}")


if __name__ == "__main__":
    main()
