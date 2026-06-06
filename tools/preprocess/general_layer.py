# -*- coding: utf-8 -*-
"""Transform the extracted general-vocabulary source (general_source.jsonl) into
Android-import note dicts for the reading-matrix layer.

Design intent (doc 8.1): the general list is the *reading matrix* — function
words plus common vocabulary that makes authentic text legible. Its job is
reader coverage, not grammar drilling. So:

  * Nouns get a declension table built from the deck's real (often irregular)
    case forms, which feeds the reader form index — but are tagged "general"
    so the app does NOT generate CASE_FILL grammar cards for them.
  * Adjectives are run through the regular adjective engine (adjectives are
    highly regular) for the same coverage benefit.
  * Verbs are vocab-only; the app derives regular past forms for coverage.

Deduplication: any lemma already present in the curated domain corpus wins and
the general entry is skipped. Sequencing is unified through the domain frequency
list when the lemma appears there (so function words sort first).
"""
from __future__ import annotations

import json
from pathlib import Path

from russian_morph import decline_adjective, decline_noun, strip_stress

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "general_source.jsonl"

POS_MAP = {
    "noun": "noun", "adj.": "adjective", "adjs": "adjective", "prtf": "adjective",
    "verb": "verb", "adv.": "adverb", "conj.": "conjunction", "prep.": "preposition",
    "num.": "numeral", "prcl.": "particle", "intj.": "interjection",
    "pron.": "pronoun", "pred.": "predicative", "nonpro.": "other",
}
GENDER_MAP = {"masc": "M", "femn": "F", "neut": "N"}


def _norm(citation: str) -> str:
    return (citation.strip().lower()
            .replace("́", "").replace("̀", "").replace("́", "")
            .replace("ё", "е"))


def _pos(raw: str) -> str:
    return POS_MAP.get(raw.strip().lower(), "other")


def _partial_table(word, gen_sg, gen_pl, prep_sg, nom_pl):
    """Partial declension table from the deck's real (stress-stripped) forms.
    Correct including suppletives (человек -> люди), but only the forms the deck
    supplies. Used when the rule engine can't be trusted for this noun."""
    def s(v):
        return strip_stress(v) if v else ""
    table = {"NOM_SG": s(word)}
    if gen_sg:  table["GEN_SG"] = s(gen_sg)
    if prep_sg: table["PREP_SG"] = s(prep_sg)
    if nom_pl:  table["NOM_PL"] = s(nom_pl)
    if gen_pl:  table["GEN_PL"] = s(gen_pl)
    return table


def _guess_class(lemma: str, gender: str):
    """Guess a declension class from ending + gender. Returns None for shapes
    the engine shouldn't attempt (irregular -мя, etc.)."""
    w = lemma
    if gender == "N":
        if w.endswith("мя"):       return None          # время/имя: irregular
        if w.endswith("ие"):       return "n_ie"
        if w.endswith("ье"):       return "n_e"
        if w.endswith("е"):        return "n_e"
        if w.endswith("о"):        return "n_o"
        return None
    if gender == "M":
        if w.endswith("ий"):       return "m_iy"
        if w.endswith("й"):        return "m_j"
        if w.endswith("ь"):        return "m_soft"
        if w[-1:] in "бвгджзклмнпрстфхцчшщ":  return "m_hard"
        return None
    if gender == "F":
        if w.endswith("ия"):       return "f_iya"
        if w.endswith("ья"):       return "f_ya"
        if w.endswith("я"):        return "f_ya"
        if w.endswith("а"):        return "f_a"
        if w.endswith("ь"):        return "f_soft"
        return None
    return None


def _full_table(word, gender, gen_sg, gen_pl, prep_sg, nom_pl):
    """Try a full 12-case table via the rule engine, VALIDATED against the deck's
    real forms. If the engine disagrees on any form the deck supplies (irregular
    stems, fleeting vowels, stress-shift plurals), fall back to the partial real
    table — so no incorrect form ever enters the index.
    Returns (table, used_engine)."""
    partial = _partial_table(word, gen_sg, gen_pl, prep_sg, nom_pl)
    cls = _guess_class(strip_stress(word).lower(), gender)
    if cls is None:
        return partial, False
    numbers = ("SG", "PL") if nom_pl else ("SG",)
    try:
        gen = decline_noun(word, cls, animate=False, numbers=numbers)
    except Exception:
        return partial, False
    # validate against every deck-supplied form
    checks = [("GEN_SG", gen_sg), ("PREP_SG", prep_sg), ("NOM_PL", nom_pl), ("GEN_PL", gen_pl)]
    for key, deck_val in checks:
        if not deck_val:
            continue
        if strip_stress(deck_val).lower() != (gen.get(key) or "").lower():
            return partial, False
    return gen, True


def general_rows(domain_lemmas: set[str]) -> list[dict]:
    if not SOURCE.exists():
        return []
    rows = []
    # Dedup on the same normalization the app uses (stress-stripped, ё→е) so
    # ё-spelled domain words (партнёр) also shadow their general duplicates.
    seen = {l.replace("ё", "е") for l in domain_lemmas}
    engine_tables = 0
    for line in SOURCE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        e = json.loads(line)
        word = e["word"]
        lemma = strip_stress(word).lower()
        key = _norm(word)
        if not lemma or key in seen:
            continue
        seen.add(key)
        translation = e.get("definition", "").strip()
        if not translation:
            continue
        pos = _pos(e.get("pos", ""))
        rank = e.get("rank")
        note = {
            "russian": word,
            "lemma": lemma,
            "pos": pos,
            "translation": translation,
            "generalFreqRank": rank if rank else None,
            "tags": "general matrix",
        }
        example = e.get("example", "").strip()
        if example:
            note["exampleSentence"] = example
        gender = GENDER_MAP.get(e.get("gender", "").strip().lower())
        if pos == "noun" and gender:
            note["gender"] = gender
            table, used_engine = _full_table(
                word, gender, e.get("gen_sg"), e.get("gen_pl"), e.get("prep_sg"), e.get("nom_pl"))
            note["declensionJson"] = table
            if used_engine:
                engine_tables += 1
        elif pos == "adjective":
            note["gender"] = "M"
            try:
                note["declensionJson"] = decline_adjective(word)
            except Exception:
                pass  # irregular/comparative-only: vocab-only is fine
        rows.append(note)
    print(f"  general: {len(rows)} notes, {engine_tables} full engine-declined noun tables")
    return rows
