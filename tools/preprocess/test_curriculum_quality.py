# -*- coding: utf-8 -*-
"""Objective quality gates for the whole A1–C1 curriculum. These encode what
"polished" means and fail loudly on regressions: stress marks, example variety,
aspect-pair symmetry, aktionsart coverage, and concept ids matching the app."""
import re
from collections import Counter
from pathlib import Path

from a1_starter import a1_rows, a1_reader_texts
from a2_starter import a2_rows, a2_reader_texts
from b1_starter import b1_rows, b1_reader_texts
from b2_starter import b2_rows, b2_reader_texts
from c1_starter import c1_rows, c1_reader_texts

STRESS = "́"
VOWELS = set("аеёиоуыэюяАЕЁИОУЫЭЮЯ")
WORD_RE = re.compile(r"[а-яёА-ЯЁ́-]+")
GRAMMAR_CONCEPTS_KT = (
    Path(__file__).resolve().parents[2]
    / "app/src/main/java/com/sibirskyspeak/data/GrammarConcepts.kt"
)


def all_rows():
    return a1_rows() + a2_rows() + b1_rows() + b2_rows() + c1_rows()


def all_readers():
    return (a1_reader_texts() + a2_reader_texts() + b1_reader_texts()
            + b2_reader_texts() + c1_reader_texts())


def _vowel_count(word):
    return sum(1 for ch in word if ch in VOWELS)


def _is_stressed(word):
    """A word reads correctly if it has an explicit stress mark, or contains ё
    (which is always stressed in Russian and never carries a separate mark)."""
    return STRESS in word or "ё" in word or "Ё" in word


def _needs_stress(word):
    """A polysyllabic Russian word should be unambiguously stressed."""
    return _vowel_count(word) >= 2 and not _is_stressed(word)


def test_citations_have_stress_marks():
    bad = []
    for note in all_rows():
        if note["pos"] == "lesson":
            continue  # lesson titles are English
        ru = note["russian"]
        # Multi-word idioms: every polysyllabic part should be stressed.
        for part in ru.split():
            if _needs_stress(part):
                bad.append((note["cefrLevel"], note["lemma"], ru))
                break
    assert not bad, f"citations missing stress marks: {bad}"


def test_example_sentences_have_stress_marks():
    bad = []
    for note in all_rows():
        if note["pos"] == "lesson":
            continue
        ex = note.get("exampleSentence", "")
        # Every polysyllabic word in the example should be stressed, so a learner
        # can read it aloud correctly.
        for token in WORD_RE.findall(ex):
            if _needs_stress(token):
                bad.append((note["cefrLevel"], note["lemma"], token, ex))
                break
    assert not bad, f"example words missing stress: {bad[:20]}"


def test_examples_are_varied():
    """No single example sentence is reused across many notes (monotony guard)."""
    counts = Counter(
        n["exampleSentence"] for n in all_rows()
        if n["pos"] != "lesson" and n.get("exampleSentence")
    )
    overused = {ex: c for ex, c in counts.items() if c > 2}
    assert not overused, f"example sentences reused >2x: {overused}"


def test_aspect_pairs_are_symmetric_and_have_aktionsart():
    by_lemma = {n["lemma"]: n for n in all_rows() if n["pos"] == "verb"}
    problems = []
    for n in by_lemma.values():
        partner = n.get("aspectPartner")
        if not partner:
            continue
        if not n.get("aktionsart"):
            problems.append(f"{n['lemma']} has partner but no aktionsart")
        p = by_lemma.get(partner)
        if p is None:
            problems.append(f"{n['lemma']} -> missing partner {partner}")
        elif p.get("aspectPartner") != n["lemma"]:
            problems.append(f"{n['lemma']} <-> {partner} not symmetric")
    assert not problems, f"aspect issues: {problems}"


def test_lesson_concepts_exist_in_app():
    kt = GRAMMAR_CONCEPTS_KT.read_text(encoding="utf-8")
    app_ids = set(re.findall(r'id = "([A-Z_]+)"', kt))
    lesson_ids = {n["conceptId"] for n in all_rows() if n["pos"] == "lesson"}
    missing = lesson_ids - app_ids
    assert not missing, f"lesson concepts not in GrammarConcepts.kt: {missing}"


def test_every_concept_taught_is_also_reinforced():
    """A grammar unit should introduce at least a couple of vocab notes alongside
    its lesson, so the concept is practised, not just read."""
    by_unit = {}
    for n in all_rows():
        by_unit.setdefault(n["unit"], []).append(n)
    thin = []
    for unit, notes in by_unit.items():
        has_lesson = any(n["pos"] == "lesson" for n in notes)
        vocab = sum(1 for n in notes if n["pos"] != "lesson")
        if has_lesson and vocab < 2:
            thin.append((unit, vocab))
    assert not thin, f"grammar units with <2 vocab notes: {thin}"


def test_translations_present_and_nontrivial():
    for note in all_rows():
        assert note["translation"].strip(), f"{note['lemma']} has no translation"
        if note["pos"] == "lesson":
            continue
        assert note.get("exampleTranslation", "").strip()


# Hand-verified plural forms the rule engine gets wrong (irregular plurals and
# fleeting-vowel genitive plurals). These must match exactly so no wrong drill ships.
EXPECTED_PLURALS = {
    "друг": {"NOM_PL": "друзья", "GEN_PL": "друзей"},
    "город": {"NOM_PL": "города"},
    "дом": {"NOM_PL": "дома"},
    "хлеб": {"NOM_PL": "хлеба"},
    "окно": {"GEN_PL": "окон"},
    "ручка": {"GEN_PL": "ручек"},
    "письмо": {"GEN_PL": "писем"},
    "сестра": {"NOM_PL": "сёстры", "GEN_PL": "сестёр"},
    "стул": {"NOM_PL": "стулья", "GEN_PL": "стульев"},
    "кошка": {"GEN_PL": "кошек"},
    "месяц": {"GEN_PL": "месяцев"},
    "статья": {"INS_SG": "статьёй"},
}


def test_known_irregular_plurals_are_correct():
    by_lemma = {n["lemma"]: n for n in all_rows() if n["pos"] == "noun"}
    for lemma, expected in EXPECTED_PLURALS.items():
        note = by_lemma.get(lemma)
        assert note, f"{lemma} not found as a declinable noun"
        table = note.get("declensionJson") or {}
        for key, form in expected.items():
            assert table.get(key) == form, (
                f"{lemma}.{key} = {table.get(key)!r}, expected {form!r}"
            )


def test_readers_have_titles_and_stress():
    for text in all_readers():
        assert text["title"].strip() and text["body"].strip()
        # Graded readers should be stressed for read-aloud practice.
        assert STRESS in text["body"], f"reader not stressed: {text['title']}"
