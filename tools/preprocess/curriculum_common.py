# -*- coding: utf-8 -*-
"""Shared builders for the tiered, progressive CEFR curriculum (A1–C1, tier 0).

Each level module (a1_starter, a2_starter, …) defines a list of UNIT dicts and
calls :func:`build_level`. A unit looks like::

    {"unit": n, "title": str, "concept": concept_id_or_None,
     "nouns": [...], "verbs": [...], "adjs": [...], "words": [...]}

Entry formats:
  noun:  (citation, decl_class, gender, animate, translation, ex_ru, ex_en)
  verb:  (citation, translation, ex_ru, ex_en, aspect, aktionsart, partner|None)
  adj:   (citation, translation, ex_ru, ex_en)
  word:  (citation, pos, translation, ex_ru, ex_en)

Every example sentence must be readable (real translation) and use only
vocabulary already introduced in this or an earlier unit (validated by tests).

A grammar concept declared on a unit produces one ``pos:"lesson"`` note whose
``conceptId`` must match com.sibirskyspeak.data.GrammarConcepts.
"""
from __future__ import annotations

from russian_morph import decline_adjective, decline_noun, strip_stress

# Lesson note display titles. Keys/ids must match GrammarConcepts.kt.
CONCEPT_TITLES = {
    # A1
    "GENDER": "Noun gender",
    "NOM_PL": "Making plurals",
    "ACC": "The accusative (direct object)",
    "GEN": "The genitive (\"of\", absence)",
    "PREP": "The prepositional (location)",
    "DAT": "The dative (\"to/for\")",
    "INS": "The instrumental (\"with/by\")",
    "PAST": "The past tense",
    "ADJ_AGREE": "Adjective agreement",
    "ASPECT": "Verb aspect",
    # A2
    "FUTURE": "The future tense",
    "IMPERATIVE": "Commands (imperative)",
    "REFLEXIVE": "Reflexive verbs (-ся)",
    "COMPARATIVE": "Comparing things",
    "MODAL": "Can, must, need (dative)",
    "MOTION": "Verbs of motion (go)",
    "POSSESSIVE_SVOJ": "One's own (свой)",
    # B1
    "MOTION_PREFIX": "Prefixed motion verbs",
    "CONDITIONAL": "Would / conditional (бы)",
    "RELATIVE": "Which / who (который)",
    "SUPERLATIVE": "The most (superlative)",
    "PURPOSE": "In order to (чтобы)",
    "NUMERAL_CASE": "Numbers and nouns",
    # B2
    "PARTICIPLE_ACTIVE": "Active participles",
    "PARTICIPLE_PASSIVE": "Passive participles",
    "GERUND": "Verbal adverbs (gerunds)",
    "PASSIVE": "Passive constructions",
    "REPORTED": "Reported speech",
    # C1
    "COMPLEX_SYNTAX": "Connecting ideas",
    "NOMINALIZATION": "Nominal style",
    "ASPECT_NUANCE": "Fine points of aspect",
    "REGISTER": "Register and style",
    "IDIOM": "Set phrases and idiom",
}


def _decline_safe(lemma, cls, animate, sg_only=False):
    """Full SG+PL table when the engine supports it, else SG-only, else no table.
    Keeps a noun usable (singular declension/drills) even when its plural class
    isn't implemented, instead of crashing the build."""
    if not sg_only:
        try:
            return decline_noun(lemma, cls, animate=animate, numbers=("SG", "PL"))
        except Exception:
            pass
    try:
        return decline_noun(lemma, cls, animate=animate, numbers=("SG",))
    except Exception:
        return None


def _noun_note(entry, unit, rank, level):
    # An optional 8th field corrects forms the rule engine can't derive:
    #   "sg"   -> singular-only table (no plural drills)
    #   {dict} -> explicit (unstressed) forms merged over the engine output, used
    #             for irregular plurals (друг→друзья) and fleeting-vowel genitive
    #             plurals (письмо→писем) so no incorrect drill ever ships.
    citation, cls, gender, animate, translation, ex_ru, ex_en = entry[:7]
    override = entry[7] if len(entry) > 7 else None
    lemma = strip_stress(citation).lower()
    if override == "sg":
        table = _decline_safe(lemma, cls, animate, sg_only=True)
    else:
        table = _decline_safe(lemma, cls, animate)
        if isinstance(override, dict) and table is not None:
            table = {**table, **{k: strip_stress(v) for k, v in override.items()}}
    return {
        "russian": citation, "lemma": lemma, "pos": "noun",
        "translation": translation, "gender": gender, "declensionJson": table,
        "generalFreqRank": rank, "exampleSentence": ex_ru,
        "exampleTranslation": ex_en, "tier": 0, "unit": unit,
        "cefrLevel": level, "tags": f"a1 noun {level.lower()}",
    }


def _adj_note(entry, unit, rank, level):
    citation, translation, ex_ru, ex_en = entry
    lemma = strip_stress(citation).lower()
    note = {
        "russian": citation, "lemma": lemma, "pos": "adjective",
        "translation": translation, "gender": "M", "generalFreqRank": rank,
        "exampleSentence": ex_ru, "exampleTranslation": ex_en, "tier": 0,
        "unit": unit, "cefrLevel": level, "tags": f"adjective {level.lower()}",
    }
    try:
        note["declensionJson"] = decline_adjective(citation)
    except Exception:
        pass
    return note


def _verb_note(entry, unit, rank, level):
    citation, translation, ex_ru, ex_en, aspect, aktionsart, partner = entry
    lemma = strip_stress(citation).lower()
    note = {
        "russian": citation, "lemma": lemma, "pos": "verb",
        "translation": translation, "aspect": aspect, "aktionsart": aktionsart,
        "aktionsartConfidence": "manual", "generalFreqRank": rank,
        "exampleSentence": ex_ru, "exampleTranslation": ex_en, "tier": 0,
        "unit": unit, "cefrLevel": level, "tags": f"verb {level.lower()}",
    }
    if partner:
        note["aspectPartner"] = strip_stress(partner).lower()
    return note


def _word_note(entry, unit, rank, level):
    citation, pos, translation, ex_ru, ex_en = entry
    return {
        "russian": citation, "lemma": strip_stress(citation).lower(), "pos": pos,
        "translation": translation, "generalFreqRank": rank,
        "exampleSentence": ex_ru, "exampleTranslation": ex_en, "tier": 0,
        "unit": unit, "cefrLevel": level, "tags": f"word {level.lower()}",
    }


def _lesson_note(concept, unit, rank, level):
    return {
        "russian": CONCEPT_TITLES.get(concept, concept),
        "lemma": f"lesson_{concept.lower()}", "pos": "lesson",
        "translation": CONCEPT_TITLES.get(concept, concept), "conceptId": concept,
        "generalFreqRank": rank, "tier": 0, "unit": unit, "cefrLevel": level,
        "tags": f"lesson {level.lower()}",
    }


def build_level(units, level, seen=None, rank_start=0):
    """Build all tier-0 notes for one level, lessons leading each unit, in order.

    [seen] is a shared set of already-emitted lemmas so a word taught at an
    earlier level is never re-taught later. Returns the level's note rows.
    """
    if seen is None:
        seen = set()
    rows = []
    rank = rank_start
    for u in units:
        unit = u["unit"]
        if u.get("concept"):
            note = _lesson_note(u["concept"], unit, rank, level); rank += 1
            if note["lemma"] not in seen:
                seen.add(note["lemma"]); rows.append(note)
        for entry in u.get("nouns", []):
            note = _noun_note(entry, unit, rank, level); rank += 1
            if note["lemma"] in seen:
                continue
            seen.add(note["lemma"]); rows.append(note)
        for entry in u.get("verbs", []):
            note = _verb_note(entry, unit, rank, level); rank += 1
            if note["lemma"] in seen:
                continue
            seen.add(note["lemma"]); rows.append(note)
        for entry in u.get("adjs", []):
            note = _adj_note(entry, unit, rank, level); rank += 1
            if note["lemma"] in seen:
                continue
            seen.add(note["lemma"]); rows.append(note)
        for entry in u.get("words", []):
            note = _word_note(entry, unit, rank, level); rank += 1
            if note["lemma"] in seen:
                continue
            seen.add(note["lemma"]); rows.append(note)
    return rows
