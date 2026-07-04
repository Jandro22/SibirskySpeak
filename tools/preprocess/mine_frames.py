#!/usr/bin/env python3
"""Dependency-parse a sample of the bundled Tatoeba corpus to surface candidate
clause templates (frame candidates) for an agent to curate into frames.json.

This is a mining aid, not a gate: its output (frame_candidates.json) is read by
a human/agent during curation, not consumed at build time or by any test. The
actual shipped frames live in frames.json and are validated by test_frames.py.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

CASE_MAP = {"Nom": "NOM", "Gen": "GEN", "Dat": "DAT", "Acc": "ACC", "Ins": "INS", "Loc": "PRE"}


def load_sentences(db_path: Path, limit: int) -> list[str]:
    db = sqlite3.connect(db_path)
    rows = db.execute(
        "SELECT ru_plain FROM sentence WHERE n_tokens BETWEEN 4 AND 10 ORDER BY rating DESC LIMIT ?",
        (limit,),
    ).fetchall()
    db.close()
    return [r[0] for r in rows]


def signature_for(doc) -> tuple[str, list[str]] | None:
    """Reduce a parsed sentence to (structural signature, fixed prepositions)."""
    root = next((t for t in doc.tokens if t.rel == "root" and t.pos == "VERB"), None)
    if root is None:
        return None
    children = [t for t in doc.tokens if t.head_id == root.id]
    parts = ["verb:VERB"]
    fixed: list[str] = []
    for rel_wanted, tag in (("nsubj", "subj"), ("obj", "obj"), ("iobj", "iobj")):
        dep = next((c for c in children if c.rel == rel_wanted and c.pos in ("NOUN", "PRON")), None)
        if dep is None:
            continue
        case = CASE_MAP.get(dep.feats.get("Case", ""))
        if case:
            parts.append(f"{tag}:{case}")
    obl = next((c for c in children if c.rel == "obl" and c.pos == "NOUN"), None)
    if obl is not None:
        case = CASE_MAP.get(obl.feats.get("Case", ""))
        prep = next((t for t in doc.tokens if t.head_id == obl.id and t.rel == "case"), None)
        if case and prep is not None:
            parts.append(f"obl:{case}")
            fixed.append(prep.text.lower())
    return " ".join(sorted(parts)), fixed


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=ROOT / "app/src/main/assets/tatoeba.db")
    ap.add_argument("--sample", type=int, default=4000)
    ap.add_argument("--out", type=Path, default=Path(__file__).parent / "frame_candidates.json")
    ap.add_argument("--top", type=int, default=60)
    args = ap.parse_args()

    from natasha import Segmenter, NewsEmbedding, NewsMorphTagger, NewsSyntaxParser, Doc

    seg = Segmenter()
    emb = NewsEmbedding()
    morph_tagger = NewsMorphTagger(emb)
    syntax_parser = NewsSyntaxParser(emb)

    sentences = load_sentences(args.db, args.sample)
    counts: Counter[str] = Counter()
    examples: dict[str, list[str]] = {}
    fixed_by_sig: dict[str, Counter[str]] = {}

    for text in sentences:
        doc = Doc(text)
        doc.segment(seg)
        doc.tag_morph(morph_tagger)
        doc.parse_syntax(syntax_parser)
        result = signature_for(doc)
        if result is None:
            continue
        sig, fixed = result
        counts[sig] += 1
        examples.setdefault(sig, [])
        if len(examples[sig]) < 3:
            examples[sig].append(text)
        bucket = fixed_by_sig.setdefault(sig, Counter())
        for f in fixed:
            bucket[f] += 1

    ranked = [
        {
            "signature": sig,
            "count": count,
            "examples": examples[sig],
            "common_prepositions": [w for w, _ in fixed_by_sig.get(sig, Counter()).most_common(3)],
        }
        for sig, count in counts.most_common(args.top)
    ]
    args.out.write_text(json.dumps(ranked, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"sentences_parsed": len(sentences), "signatures": len(counts), "out": str(args.out)}))


if __name__ == "__main__":
    main()
