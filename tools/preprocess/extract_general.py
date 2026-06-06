# -*- coding: utf-8 -*-
"""Extract the general-vocabulary layer from the 'Alex's 10k Russian' Anki deck
into a committed, repo-local artifact (general_source.jsonl).

The deck carries accented case forms (Genetive / Genetive Plural / Prepositional
/ Plural) for ~4,700 nouns — including irregulars/suppletives a rule engine would
get wrong — plus POS, gender, and example sentences. We capture the cleaned
fields here once so build_bootstrap.py can run without the original .apkg.

Usage:
    python tools/preprocess/extract_general.py
    (re-run only when refreshing from the source deck)
"""
from __future__ import annotations

import html
import json
import re
import sqlite3
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
DECK = Path(r"C:\Users\Jandro\Downloads\Alexs_10k_Russian_-_Genders_Accented_Verb_and_Noun_info.apkg")
OUT = HERE / "general_source.jsonl"

TAG_RE = re.compile(r"<[^>]+>")


def clean(text: str) -> str:
    """Strip HTML tags, unescape entities, collapse whitespace."""
    if text in ("", "None", None):
        return ""
    t = TAG_RE.sub(" ", text)
    t = html.unescape(t)
    t = t.replace(" ", " ")
    return re.sub(r"\s+", " ", t).strip()


def main() -> None:
    if not DECK.exists():
        raise SystemExit(f"deck not found: {DECK}")
    with zipfile.ZipFile(DECK) as z:
        db = "collection.anki21" if "collection.anki21" in z.namelist() else "collection.anki2"
        tmp = Path(tempfile.gettempdir()) / "_alex_extract.db"
        tmp.write_bytes(z.read(db))
    con = sqlite3.connect(str(tmp))
    model = next(iter(json.loads(con.execute("SELECT models FROM col").fetchone()[0]).values()))
    idx = {f["name"]: i for i, f in enumerate(model["flds"])}
    rows = [r[0].split("\x1f") for r in con.execute("SELECT flds FROM notes")]
    con.close()

    def field(r, name):
        return r[idx[name]] if idx.get(name) is not None and idx[name] < len(r) else ""

    out = []
    for r in rows:
        word = clean(field(r, "Word"))
        if not word:
            continue
        rank_raw = clean(field(r, "Index"))
        rank = int(rank_raw) if rank_raw.isdigit() else None
        out.append({
            "rank": rank,
            "word": word,                                  # accented citation
            "definition": clean(field(r, "Definition")),
            "pos": clean(field(r, "POS")),
            "gender": clean(field(r, "Gender")),
            "example": clean(field(r, "Example Sentence")),
            "gen_sg": clean(field(r, "Genetive")),
            "gen_pl": clean(field(r, "Genetive Plural")),
            "prep_sg": clean(field(r, "Prepositional")),
            "nom_pl": clean(field(r, "Plural")),
        })

    with OUT.open("w", encoding="utf-8", newline="\n") as fh:
        for row in out:
            fh.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"wrote {len(out)} rows -> {OUT}")


if __name__ == "__main__":
    main()
