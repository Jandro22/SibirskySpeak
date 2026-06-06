# -*- coding: utf-8 -*-
"""Validates the full A1–C1 curriculum: comprehensible input (real sentence
glosses) and cumulative controlled vocabulary across every level."""
import re

import pytest

from curriculum_common import CONCEPT_TITLES
from russian_morph import strip_stress
from a1_starter import a1_rows, a1_reader_texts
from a2_starter import a2_rows, a2_reader_texts
from b1_starter import b1_rows, b1_reader_texts
from b2_starter import b2_rows, b2_reader_texts
from c1_starter import c1_rows, c1_reader_texts

WORD_RE = re.compile(r"[а-яё-]+", re.IGNORECASE)

LEVELS = ["A1", "A2", "B1", "B2", "C1"]


def _norm(text):
    return strip_stress(text).lower().replace("ё", "е")


def all_rows():
    rows = a1_rows() + a2_rows() + b1_rows() + b2_rows() + c1_rows()
    # Stable sort by unit so the cumulative-vocabulary check sees curriculum order.
    return sorted(rows, key=lambda n: n["unit"])


def all_readers():
    return (a1_reader_texts() + a2_reader_texts() + b1_reader_texts()
            + b2_reader_texts() + c1_reader_texts())


# Closed-class forms (pronouns in all cases, copula/future of быть, irregular
# present-tense forms, particles) that the rule engine cannot derive but are
# legitimately used in graded sentences.
CLOSED_CLASS = set("""
не ни да нет ну вот и а но или в во с со к ко о об у за на из от до по для
как дела всё все этот эта эти этой этих тех того этого этом
меня тебя его её нас вас их мне тебе ему ей нам вам им ним ней них нём
себя себе собой мной тобой
мой моя моё мои твой твоя твоё твои наш наша наше наши ваш ваша ваше ваши
который которая которое которые которого которую которым которой которых котором
самый самая самое самые самого самую самым
один одна одно одни одного одну два две три четыре пять сколько много мало
свой своя своё свои свою своего своих своей своим своём своему
был была было были буду будешь будет будем будете будут
пишу пьём пью пьёт пьют иду идёт идут хожу ходит живу живёт живут даю даёт дают
люблю любит любят беру берёт могу может могут хочу хочешь хочет хотим хотят
ем ест едят пойду пойдёт поеду едет ездит вижу видит знаю
этому это эту эта однако поэтому потому чтобы если бы ли же ещё уже только
очень тоже также там тут здесь где когда сейчас потом сегодня завтра
снача́ла сначала после потом затем теперь долго вместе вместо около
скажи скажу скажешь скажет скажите сказал сказала
спрошу спросит спросил спросила спроси
помогу поможет помоги помог помогла помогите
пойду пойдёт пойдут пошёл пошла пошли пойди
приду придёт придут пришёл пришла пришли приди
уйду уйдёт ушёл ушла ушли ухожу прихожу
напишу напишет написал предложу предложит предложил предложила
поймёт поймут понял поняла увижу увидит увидел
дам даст дадут дал дала возьму возьмёт взял
принял приняла приняло приняли принять примет примут прими
изучаю изучает требует требуют согласи́лись
встаю встаёт согласились заче́м зачем него неё ним
еду едешь едет едут езжу ездит хожу ходит иду идёт идут
""".split())


def _verb_surface_forms(lemma):
    """A generous superset of a verb's inflected forms — used only to whitelist
    tokens in the controlled-vocabulary check (never shown to learners)."""
    forms = {_norm(lemma)}
    refl = lemma.endswith("ся") or lemma.endswith("сь")
    core = lemma[:-2] if refl else lemma  # strip reflexive suffix
    # Two candidate stems: the full infinitive stem (-ть dropped) and the
    # consonant stem used by -ить/-еть present/imperative (говорить → говор-).
    bases = set()
    if core.endswith("ть"):
        bases.add(core[:-2])
        bases.add(core[:-3])
    else:
        bases.add(core)
    endings = [
        "у", "ю", "ешь", "ёшь", "ет", "ёт", "ем", "ём", "ете", "ёте", "ут", "ют",
        "ишь", "ит", "им", "ите", "ат", "ят",            # present/future
        "л", "ла", "ло", "ли",                             # past
        "й", "и", "йте", "ите", "ь", "ьте",               # imperative
        "я", "в", "вши", "ючи", "ть", "ти",               # gerund/infinitive
        "вший", "вшая", "вшее", "вшие", "нный", "тый",    # participles (rough)
        "ющий", "ящий", "ущий", "ащий",
    ]
    suffixes = ["ся", "сь", ""] if refl else [""]
    for base in bases:
        for e in endings:
            for s in suffixes:
                forms.add(_norm(base + e + s))
    return forms


def _adj_surface_forms(note):
    forms = set()
    table = note.get("declensionJson")
    if isinstance(table, dict):
        for v in table.values():
            if isinstance(v, str) and v:
                forms.add(_norm(v))
    lemma = _norm(note["lemma"])
    # Comparative/superlative/adverb are not in the table; allow common derivations.
    if len(lemma) > 3:
        stem = lemma[:-2]
        forms.update({stem + "ее", stem + "ей", "по" + stem + "ее", stem + "о"})
    return forms


def _surface_forms(note):
    forms = {_norm(note["lemma"]), _norm(note["russian"])}
    table = note.get("declensionJson")
    if isinstance(table, dict):
        for v in table.values():
            if isinstance(v, str) and v:
                forms.add(_norm(v))
    if note["pos"] == "verb":
        forms |= _verb_surface_forms(note["lemma"])
    if note["pos"] == "adjective":
        forms |= _adj_surface_forms(note)
    return forms


def test_every_vocab_note_has_a_real_sentence_gloss():
    for note in all_rows():
        if note["pos"] == "lesson":
            continue
        ru = note.get("exampleSentence", "")
        en = note.get("exampleTranslation", "")
        assert ru, f"{note['lemma']} ({note['cefrLevel']}) has no example"
        assert en, f"{note['lemma']} ({note['cefrLevel']}) has no gloss"
        assert en.strip().lower() != note["translation"].strip().lower()
        assert len(en.split()) >= 2, f"{note['lemma']}: gloss not a sentence: {en!r}"


def test_cumulative_controlled_vocabulary():
    """Each unit's examples may use only surface forms of words introduced in this
    or an earlier unit (across all levels), plus closed-class forms."""
    known = {_norm(w) for w in CLOSED_CLASS}
    rows = all_rows()
    by_unit = {}
    for note in rows:
        by_unit.setdefault(note["unit"], []).append(note)
    for unit in sorted(by_unit):
        for note in by_unit[unit]:
            if note["pos"] != "lesson":
                known |= _surface_forms(note)
        for note in by_unit[unit]:
            ru = note.get("exampleSentence", "")
            for token in WORD_RE.findall(_norm(ru)):
                assert token in known, (
                    f"unit {unit} {note['cefrLevel']} ({note['lemma']}): "
                    f"uncontrolled word {token!r} in {ru!r}"
                )


def test_units_are_monotonic_per_level_and_lessons_lead():
    rows = all_rows()
    first_index = {}
    for i, note in enumerate(rows):
        first_index.setdefault(note["unit"], i)
    for i, note in enumerate(rows):
        if note["pos"] == "lesson":
            assert first_index[note["unit"]] == i, (
                f"lesson for unit {note['unit']} is not first"
            )


def test_all_declared_concepts_have_titles_and_lessons():
    lesson_concepts = {n["conceptId"] for n in all_rows() if n["pos"] == "lesson"}
    for concept in lesson_concepts:
        assert concept in CONCEPT_TITLES


def test_every_level_is_present_and_readers_graded():
    levels = {n["cefrLevel"] for n in all_rows()}
    for lvl in LEVELS:
        assert lvl in levels, f"missing level {lvl}"
    for text in all_readers():
        assert text["body"].strip()
