#!/usr/bin/env python3
"""Bake pymorphy3 paradigms and a reverse analysis index into tatoeba.db.

The Android runtime performs indexed lookups only. Running this script is the
single source for both forward inflection and reverse form analysis.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import unicodedata
from pathlib import Path

import pymorphy3

ROOT = Path(__file__).resolve().parents[2]
ACUTE = "\u0301"
CASES = {"nomn": "NOM", "gent": "GEN", "gen2": "GEN", "datv": "DAT",
         "accs": "ACC", "ablt": "INS", "loct": "PRE", "loc2": "PRE"}
NUMBERS = {"sing": "SG", "plur": "PL"}
GENDERS = {"masc": "M", "femn": "F", "neut": "N"}
PERSONS = {"1per": "1", "2per": "2", "3per": "3"}


def norm(value: str) -> str:
    # NFD decomposition is only a means to strip the combining stress mark
    # (́) cleanly -- but it ALSO splits й (U+0439) into и + a
    # combining breve (U+0306) as a side effect. Left decomposed, that string
    # no longer matches pymorphy3's dictionary (which indexes precomposed
    # й), so morph.parse() silently falls back to low-confidence guess
    # parses for every word containing й -- corrupting their generated
    # declension/analysis tables. Recompose back to NFC before returning.
    value = unicodedata.normalize("NFD", value.lower().replace("ё", "е")).replace(ACUTE, "")
    value = value.replace("\u0308", "").strip()
    return unicodedata.normalize("NFC", value)


def analysis_feats(tag) -> str:
    values = []
    for raw, mapping in ((tag.case, CASES), (tag.number, NUMBERS),
                         (tag.gender, GENDERS), (tag.person, PERSONS)):
        if raw in mapping:
            values.append(mapping[raw])
    if tag.tense:
        values.append({"pres": "PRES", "past": "PAST", "futr": "FUT"}.get(tag.tense, tag.tense.upper()))
    if "impr" in tag:
        values.append("IMP")
    if "perf" in tag:
        values.append("PF")
    if "impf" in tag:
        values.append("IPF")
    return "+".join(values)


def legacy_key(tag) -> str | None:
    case = CASES.get(tag.case)
    number = NUMBERS.get(tag.number)
    pos = str(tag.POS or "")
    gender = GENDERS.get(tag.gender)
    if case and number and pos == "NOUN":
        return f"{case}_{number}"
    if case and number and pos in {"ADJF", "PRTF"}:
        if number == "PL":
            return f"PL_{case}"
        if gender == "M":
            return f"{case}_SG"
        return f"{ {'F': 'FEM', 'N': 'NEUT'}.get(gender, gender) }_{case}" if gender else None
    if pos in {"VERB", "INFN"}:
        if tag.tense == "pres" and tag.person and number:
            return f"PRES_{PERSONS[tag.person]}{number}"
        if tag.tense == "futr" and tag.person and number:
            return f"FUT_{PERSONS[tag.person]}{number}"
        if tag.tense == "past" and number == "PL":
            return "PAST_PL"
        if tag.tense == "past" and gender:
            return f"PAST_{gender}"
        if "impr" in tag and number:
            return f"IMP_{number}"
        if pos == "INFN":
            return "INF"
    return None


def load_lemmas(notes: Path) -> dict[str, str]:
    """normalized lemma -> a representative original-spelling surface.

    pymorphy3's dictionary is keyed on precomposed text; feeding it the
    NFD-decomposed, breve-retained form norm() produces (every lemma
    containing "й", i.e. almost every -ый/-ий/-ой adjective and many verbs)
    fails to parse and silently falls back to a garbage first guess. Parsing
    must run on the original spelling; only the *keys* we store are normalized.
    """
    reps: dict[str, str] = {}
    with notes.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            raw = (row.get("lemma") or row.get("russian") or "").strip()
            if not raw:
                continue
            key = norm(raw)
            if key and key not in reps:
                reps[key] = raw
    return reps


def build(db_path: Path, notes: Path, room_schema: Path) -> dict[str, int]:
    morph = pymorphy3.MorphAnalyzer()
    identity = json.loads(room_schema.read_text(encoding="utf-8"))["database"]["identityHash"]
    db = sqlite3.connect(db_path)
    db.executescript("""
        DROP TABLE IF EXISTS paradigm;
        DROP TABLE IF EXISTS analysis;
        CREATE TABLE paradigm(lemma TEXT NOT NULL, pos TEXT NOT NULL, feats TEXT NOT NULL,
          surface TEXT NOT NULL, stressed TEXT NOT NULL, PRIMARY KEY(lemma,feats,surface));
        CREATE INDEX index_paradigm_lemma ON paradigm(lemma);
        CREATE INDEX index_paradigm_surface ON paradigm(surface);
        CREATE TABLE analysis(surface_norm TEXT NOT NULL, lemma TEXT NOT NULL, pos TEXT NOT NULL,
          feats TEXT NOT NULL, PRIMARY KEY(surface_norm,lemma,feats));
        CREATE INDEX index_analysis_surface_norm ON analysis(surface_norm);
        CREATE INDEX index_analysis_lemma ON analysis(lemma);
    """)
    paradigms, analyses = set(), set()
    for lemma, raw in load_lemmas(notes).items():
        parses = [p for p in morph.parse(raw) if norm(p.normal_form) == lemma]
        if not parses:
            parses = morph.parse(raw)[:1]
        for parsed in parses[:3]:
            pos = str(parsed.tag.POS or "UNKN")
            for form in parsed.lexeme:
                # Participles/gerunds roughly triple a verb's lexeme size and aren't
                # consumed by any card type, frame, or concept yet (PARTICIPLE_ACTIVE/
                # PARTICIPLE_PASSIVE/GERUND are Phase 6 work) — skip them for now to
                # keep the shipped asset under the CI size budget; add back with their
                # own feats keys when a real consumer needs them.
                form_pos = str(form.tag.POS or pos)
                if form_pos in {"PRTF", "PRTS", "GRND"}:
                    continue
                surface = form.word.lower()
                feats = analysis_feats(form.tag)
                if feats:
                    analyses.add((norm(surface), lemma, form_pos, feats))
                key = legacy_key(form.tag)
                if key:
                    paradigms.add((lemma, form_pos, key, surface, surface))
    db.executemany("INSERT OR IGNORE INTO paradigm VALUES(?,?,?,?,?)", sorted(paradigms))
    db.executemany("INSERT OR IGNORE INTO analysis VALUES(?,?,?,?)", sorted(analyses))
    db.execute("INSERT OR REPLACE INTO meta VALUES('paradigm_forms', ?)", (str(len(paradigms)),))
    db.execute("INSERT OR REPLACE INTO room_master_table VALUES(42, ?)", (identity,))
    db.execute("PRAGMA user_version=6")
    db.commit()
    db.execute("ANALYZE")
    db.execute("VACUUM")
    db.close()
    return {"lemmas": len(load_lemmas(notes)), "paradigms": len(paradigms), "analyses": len(analyses)}


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser()
    p.add_argument("--db", type=Path, default=ROOT / "app/src/main/assets/tatoeba.db")
    p.add_argument("--notes", type=Path, default=ROOT / "app/src/main/assets/bootstrap_notes.jsonl")
    p.add_argument("--room-schema", type=Path, default=ROOT / "app/schemas/com.sibirskyspeak.data.ContentDatabase/6.json")
    return p


if __name__ == "__main__":
    args = parser().parse_args()
    print(json.dumps(build(args.db, args.notes, args.room_schema), ensure_ascii=False, indent=2))
