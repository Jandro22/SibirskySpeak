# -*- coding: utf-8 -*-
"""G8: interleaved-spiral sequencing gate for the A1 case-family concepts.

Textbook "blocked/blocked" practice — teaching several concepts from the same
grammatical family back-to-back in consecutive units — is the single most
consistently refuted sequencing choice in the SLA interleaving literature
(Rohrer & Taylor 2007; Pan 2015). This test asserts the A1 case-chunk
concepts are spaced apart (not adjacent units) and that each gets a genuine
later re-encounter instead of being taught once and never revisited.

Family ids mirror com.sibirskyspeak.data.GrammarConcepts.kt's
GrammarConcept.family field (default id.substringBefore('_'), overridden
explicitly for the four GEN_CHUNK_*/PREP_CHUNK_*/DAT_CHUNK_*/INS_CHUNK_*
concepts so they share a family with their later "full case" counterpart).
"""
from __future__ import annotations

from a1_starter import a1_rows
from a2_starter import a2_rows

# The five A1 concepts introducing a case family, and the GrammarConcepts.kt
# `family` each belongs to (GEN_CHUNK_POSSESSION/ABSENCE share "GEN" since
# GEN_CHUNK_ABSENCE's family is explicitly set to "GEN" in GrammarConcepts.kt,
# not its own id's prefix).
CASE_FAMILY_CONCEPTS = {
    "ACC": "ACC",
    "GEN_CHUNK_POSSESSION": "GEN",
    "PREP_CHUNK_LOCATION": "PREP",
    "DAT_CHUNK_EXPERIENCER": "DAT",
    "INS_CHUNK_WITH": "INS",
}

# The A2 concept that re-encounters each case family (taught via
# `extraConcepts` on an A2 unit — see a2_starter.py units 12/14/16/18).
FAMILY_REENCOUNTER_CONCEPT = {
    "GEN": "GEN",
    "PREP": "PREP",
    "DAT": "DAT",
    "INS": "INS",
}


def _lesson_units(rows):
    """{concept_id: unit} for every LESSON note (including extraConcepts)."""
    return {n["conceptId"]: n["unit"] for n in rows if n["pos"] == "lesson"}


def test_case_family_concepts_are_not_adjacent():
    """No two of the five A1 case-family concepts are introduced in adjacent
    units — the core G8 spiral-sequencing fix. (They used to occupy five
    back-to-back units: 5, 6, 7, 8, 9.)"""
    units = _lesson_units(a1_rows())
    case_units = sorted(units[c] for c in CASE_FAMILY_CONCEPTS)
    assert len(case_units) == len(CASE_FAMILY_CONCEPTS), "all five case concepts must have a LESSON note"
    for earlier, later in zip(case_units, case_units[1:]):
        gap = later - earlier
        assert gap >= 2, (
            f"case-family concepts introduced in adjacent units {earlier} and {later} "
            f"(gap={gap}) — blocked/blocked practice, interleave with other A1 concepts"
        )


def test_case_family_concepts_get_a_later_reencounter():
    """Each case-family concept is revisited later in the curriculum instead
    of being taught once and never touched again.

    GEN_CHUNK_POSSESSION, PREP_CHUNK_LOCATION, DAT_CHUNK_EXPERIENCER, and
    INS_CHUNK_WITH are re-encountered via their full-case A2 concept
    (GEN/PREP/DAT/INS, taught as an `extraConcepts` LESSON alongside an
    unrelated A2 headline concept — see a2_starter.py). ACC has no
    "chunk-then-full" split (it already *is* the terminal case concept), so
    its review mechanism is structural instead: CardFactory generates a
    CASE_FILL(ACC) drill for every animate/inanimate noun in the deck, so
    every noun introduced in a later unit is itself a fresh ACC re-encounter.
    """
    a1 = a1_rows()
    a2 = a2_rows()
    a1_units = _lesson_units(a1)

    for concept, family in CASE_FAMILY_CONCEPTS.items():
        intro_unit = a1_units[concept]
        if concept == "ACC":
            later_nouns = [
                n for n in a1 + a2
                if n["pos"] in ("noun", "verb") and n["unit"] is not None and n["unit"] > intro_unit
            ]
            assert later_nouns, "ACC has no later units introducing new nouns/verbs to re-drill it against"
            continue
        reencounter_concept = FAMILY_REENCOUNTER_CONCEPT[family]
        a2_units = _lesson_units(a2)
        assert reencounter_concept in a2_units, (
            f"{concept} (family {family}) has no later '{reencounter_concept}' lesson reviewing it"
        )
        assert a2_units[reencounter_concept] > intro_unit, (
            f"{reencounter_concept} lesson must come after {concept}'s introduction (unit {intro_unit})"
        )
