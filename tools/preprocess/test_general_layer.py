# -*- coding: utf-8 -*-
"""Correctness + quality gates for the frequency "reading-matrix" layer (the ~9k
words promoted to curated study cards). These guarantee the layer never ships a
half-glossed card or a declension form that contradicts the deck, and that the app
will treat it as the reading-matrix (vocab/comprehension only, no morphology drills).
"""
import json

from general_layer import SOURCE, _full_table, general_rows
from russian_morph import strip_stress

ROWS = general_rows(set())  # no domain lemmas to exclude → full layer


def test_layer_is_nonempty():
    assert len(ROWS) > 5000, f"expected the full frequency layer, got {len(ROWS)}"


def test_no_half_glossed_examples():
    """A card with an example must have a real translation — never a blank gloss."""
    bad = [r["lemma"] for r in ROWS
           if r.get("exampleSentence") and not (r.get("exampleTranslation") or "").strip()]
    assert not bad, f"{len(bad)} notes have an example with an empty gloss: {bad[:15]}"


def test_examples_are_aligned_pairs():
    for r in ROWS:
        if r.get("exampleSentence"):
            assert r.get("exampleTranslation"), f"{r['lemma']} example without gloss"
            # Russian sentence should actually contain Cyrillic.
            assert any("а" <= ch.lower() <= "я" or ch == "ё" for ch in r["exampleSentence"]), \
                f"{r['lemma']} example not Cyrillic: {r['exampleSentence']!r}"


def test_all_notes_tagged_reading_matrix():
    """The app keys morphology-drill suppression on the 'matrix' tag (see cardsFor).
    Every frequency note must carry it, or it would wrongly get grammar drills built
    from unverified engine tables."""
    bad = [r["lemma"] for r in ROWS if "matrix" not in r.get("tags", "")]
    assert not bad, f"{len(bad)} frequency notes missing 'matrix' tag: {bad[:15]}"


def test_no_synthetic_extra_examples():
    """Multi-context depth is reserved for the hand-authored tier-0 course; the
    frequency layer ships only its single real deck example (no templated 2nd/3rd)."""
    bad = [r["lemma"] for r in ROWS if r.get("exampleSentence2") or r.get("exampleSentence3")]
    assert not bad, f"{len(bad)} frequency notes carry synthetic extra examples: {bad[:15]}"


def test_declension_tables_never_contradict_deck():
    """For every noun, any case form the deck supplies must equal the form we ship —
    the _full_table validation invariant. Guarantees no wrong drillable/reader form."""
    violations = []
    for line in SOURCE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        e = json.loads(line)
        gender = {"masc": "M", "femn": "F", "neut": "N"}.get(
            (e.get("gender") or "").strip().lower())
        if not gender:
            continue
        word = e["word"]
        table, _used = _full_table(
            word, gender, e.get("gen_sg"), e.get("gen_pl"), e.get("prep_sg"), e.get("nom_pl"))
        for key, deck_val in (("GEN_SG", e.get("gen_sg")), ("PREP_SG", e.get("prep_sg")),
                              ("NOM_PL", e.get("nom_pl")), ("GEN_PL", e.get("gen_pl"))):
            if not deck_val:
                continue
            shipped = (table.get(key) or "")
            if strip_stress(deck_val).lower() != shipped.lower():
                violations.append((word, key, deck_val, shipped))
    assert not violations, f"declension contradicts deck: {violations[:15]}"


def test_translations_present():
    bad = [r["lemma"] for r in ROWS if not (r.get("translation") or "").strip()]
    assert not bad, f"{len(bad)} notes have no translation: {bad[:15]}"
