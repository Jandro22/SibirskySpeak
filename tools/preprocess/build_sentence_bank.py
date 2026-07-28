#!/usr/bin/env python3
"""Index corpus sentences by their first genuinely readable curriculum point."""
from __future__ import annotations

import json
import re
import sqlite3
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORD = re.compile(r"[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?")
BANDS = ("A1", "A2", "B1", "B2", "C1", "C2")
BAND_ORDER = {band: index for index, band in enumerate(BANDS)}


def norm(value: str) -> str:
    """Match the NFC, stressless keys used by the morphology tables.

    NFD decomposes й as well as accented vowels.  Removing the acute accent
    without recomposing silently turns every й-word into a non-matching key.
    """
    value = unicodedata.normalize("NFD", value.lower().replace("ё", "е"))
    value = value.replace("\u0301", "")
    return unicodedata.normalize("NFC", value)


def curriculum_lexicon(notes: list[dict]) -> dict[str, tuple[int, int, str]]:
    """Return each lemma's earliest (band order, unit, band) introduction."""
    result: dict[str, tuple[int, int, str]] = {}
    for note in notes:
        band = note.get("cefrLevel")
        unit = note.get("unit")
        if note.get("tier") != 0 or band not in BAND_ORDER or not isinstance(unit, int) or unit < 1:
            continue
        lemma = norm(note.get("lemma") or note.get("russian", ""))
        if not lemma:
            continue
        candidate = (BAND_ORDER[band], unit, band)
        if lemma not in result or candidate[:2] < result[lemma][:2]:
            result[lemma] = candidate
    return result


def main() -> None:
    notes = [
        json.loads(line)
        for line in (ROOT / "app/src/main/assets/bootstrap_notes.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    lexicon = curriculum_lexicon(notes)
    db = sqlite3.connect(ROOT / "app/src/main/assets/tatoeba.db")
    analyses: dict[str, set[str]] = {}
    features_by_surface: dict[str, set[str]] = {}
    for surface, lemma, feature_string in db.execute("SELECT surface_norm, lemma, feats FROM analysis"):
        normalized_lemma = norm(lemma)
        if normalized_lemma in lexicon:
            surface_key = norm(surface)
            analyses.setdefault(surface_key, set()).add(normalized_lemma)
            features_by_surface.setdefault(surface_key, set()).update(feature_string.split("+"))

    db.executescript(
        "DROP TABLE IF EXISTS sentence_bank; "
        "CREATE TABLE sentence_bank(sent_id INTEGER NOT NULL PRIMARY KEY,unit_min INTEGER NOT NULL,"
        "band TEXT NOT NULL,token_count INTEGER NOT NULL,grammar_feats TEXT NOT NULL,source TEXT NOT NULL); "
        "CREATE INDEX index_sentence_bank_unit_min ON sentence_bank(unit_min); "
        "CREATE INDEX index_sentence_bank_band ON sentence_bank(band);"
    )
    rows = []
    for sentence_id, russian, token_count in db.execute("SELECT id, ru_plain, n_tokens FROM sentence"):
        readings = [analyses.get(norm(word), set()) for word in WORD.findall(russian)]
        if not readings or any(not choices for choices in readings):
            continue
        # Resolve ambiguous analyses to the earliest curriculum-known lemma for
        # each token, then the latest required token determines readability.
        token_stages = [min((lexicon[lemma] for lemma in choices), key=lambda stage: stage[:2]) for choices in readings]
        band_order, unit_min, band = max(token_stages, key=lambda stage: stage[:2])
        del band_order
        features = sorted({feature for word in WORD.findall(russian) for feature in features_by_surface[norm(word)]})
        rows.append((sentence_id, unit_min, band, token_count, json.dumps(features, separators=(",", ":")), "tatoeba"))

    db.executemany("INSERT INTO sentence_bank VALUES(?,?,?,?,?,?)", rows)
    schema = json.loads((ROOT / "app/schemas/com.sibirskyspeak.data.ContentDatabase/7.json").read_text(encoding="utf-8"))
    db.execute("INSERT OR REPLACE INTO room_master_table VALUES(42,?)", (schema["database"]["identityHash"],))
    db.execute("PRAGMA user_version=7")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(json.dumps({"sentences": len(rows)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
