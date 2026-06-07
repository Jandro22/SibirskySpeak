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
from russian_morph import strip_stress
from test_curriculum import CLOSED_CLASS, _surface_forms, _example_pairs

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
        a1_rows() + a2_rows() + b1_rows() + b2_rows() + c1_rows(),
        key=lambda n: n["unit"],
    )


def _needs_stress(word):
    if STRESS in word or "ё" in word or "Ё" in word:
        return False
    return sum(1 for ch in word if ch in VOWELS) >= 2


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

    def report(title, items):
        print(f"\n{title}: {len(items)}")
        for it in items[:80]:
            print(it)

    report("UNCONTROLLED VOCAB", vocab_problems)
    report("MISSING STRESS", stress_problems)
    report("WEAK GLOSS", gloss_problems)
    total = len(vocab_problems) + len(stress_problems) + len(gloss_problems)
    print(f"\nTOTAL problems: {total} | notes: {len(rows)}")


if __name__ == "__main__":
    main()
