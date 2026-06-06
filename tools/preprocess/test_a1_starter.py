# -*- coding: utf-8 -*-
"""Validation for the A1 starter layer: every example must be comprehensible
(real sentence translation) and use only controlled vocabulary."""
import re

from a1_starter import a1_rows, a1_reader_texts, UNITS, CONCEPT_TITLES
from russian_morph import past_masculine, strip_stress

WORD_RE = re.compile(r"[а-яё-]+", re.IGNORECASE)


def _norm(text):
    return strip_stress(text).lower().replace("ё", "е")


# Inflected forms of closed-class words (pronouns, present-tense verbs we don't
# derive) that are legitimately used in graded sentences but can't be generated.
CLOSED_CLASS_FORMS = {
    "как", "дела", "всё", "все", "моя", "пишу", "пью", "иду", "живу", "даю",
    "работаю", "читаю", "делаешь", "читает", "знаю", "знает", "люблю",
    "покупаю", "работает", "работают", "работаем", "читают", "читал", "читали",
    "прочитал", "делал", "сделал", "написал", "работали", "меня", "маме",
    "мамой", "другу", "пьём",
}


def _surface_forms(note):
    """Every readable surface form a note contributes (citation + inflections)."""
    forms = {_norm(note["lemma"]), _norm(note["russian"])}
    table = note.get("declensionJson")
    if isinstance(table, dict):
        for v in table.values():
            if isinstance(v, str) and v:
                forms.add(_norm(v))
    if note["pos"] == "verb":
        pm = past_masculine(note["lemma"])
        if pm:
            forms.add(_norm(pm))
            forms.add(_norm(pm + "а"))   # feminine
            forms.add(_norm(pm + "и"))   # plural
    return forms


def test_every_vocab_note_has_a_real_sentence_gloss():
    for note in a1_rows():
        if note["pos"] == "lesson":
            continue
        ru = note.get("exampleSentence", "")
        en = note.get("exampleTranslation", "")
        assert ru, f"{note['lemma']} has no example sentence"
        assert en, f"{note['lemma']} has no example translation"
        assert en.strip().lower() != note["translation"].strip().lower()
        assert len(en.split()) >= 2, f"{note['lemma']} gloss is not a sentence: {en!r}"


# Controlled-vocabulary checking for A1 is covered (cumulatively, across all levels)
# by test_curriculum.test_cumulative_controlled_vocabulary, which has a more robust
# morphological expander. This file keeps the A1-specific structural checks.


def test_all_grammar_concepts_have_a_lesson_note():
    lesson_concepts = {n["conceptId"] for n in a1_rows() if n["pos"] == "lesson"}
    for u in UNITS:
        if u.get("concept"):
            assert u["concept"] in lesson_concepts
            assert u["concept"] in CONCEPT_TITLES


def test_lessons_lead_their_unit():
    rows = a1_rows()
    first_index = {}
    for i, note in enumerate(rows):
        first_index.setdefault(note["unit"], i)
    for i, note in enumerate(rows):
        if note["pos"] == "lesson":
            assert first_index[note["unit"]] == i, "lesson must be first in its unit"


def test_readers_are_graded_a1():
    for text in a1_reader_texts():
        assert text["source"].startswith("graded:a1")
        assert text["body"].strip()
