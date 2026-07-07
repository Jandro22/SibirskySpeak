#!/usr/bin/env python3
"""Fast authoring loop for a reader_gap_report.py vocabulary band.

Examples:
  python tools/preprocess/author_reader_refill.py vocab --max-rank 100
  python tools/preprocess/author_reader_refill.py validate tools/preprocess/stories/beginner_variety_a1.json

Validation calls build_stories.validate_series and unknown_words directly,
then applies the reader's real 90% target to every chapter using the exact
rank-bounded tier-0 vocabulary used by reader_gap_report.py. It does not
modify bundled assets.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import pymorphy3

from build_stories import known_lemmas, unknown_words, validate_series
from reader_gap_report import CHARACTER_NAMES, NOTES, WORD_RE, coverage, known_lemmas_at_rank, load_ranked_tier0_notes, normalize


def target_vocab(max_rank: int) -> set[str]:
    return known_lemmas_at_rank(load_ranked_tier0_notes(), max_rank)


def validate(path: Path, threshold: float) -> int:
    doc = json.loads(path.read_text(encoding="utf-8"))
    morph = pymorphy3.MorphAnalyzer()
    full_vocab = known_lemmas(NOTES)
    validate_series(doc, full_vocab, morph)
    intended = target_vocab(doc["targetMaxRank"])
    failures = []
    for chapter in doc["chapters"]:
        # Keep this explicit even though validate_series also enforces the
        # budget: authors need the actual candidates during fast iteration.
        glosses = unknown_words(chapter["body"], morph, full_vocab)
        tokens = WORD_RE.findall(chapter["body"])
        score = coverage(tokens, intended, morph)
        gloss_note = f"  gloss candidates={glosses}" if glosses else ""
        print(f"ch.{chapter['chapter']:02d}  coverage={score:.1%}  {chapter['title']}{gloss_note}")
        if score < threshold:
            misses = []
            for token in tokens:
                if normalize(token) in CHARACTER_NAMES:
                    continue
                if not ({normalize(p.normal_form) for p in morph.parse(token)} & intended):
                    misses.append(token)
            print(f"       outside target: {misses}")
            failures.append((chapter["chapter"], score))
    if failures:
        print(f"FAIL: {len(failures)} chapter(s) below {threshold:.0%}: {failures}")
        return 1
    formats = {c.get("format", doc["format"]) for c in doc["chapters"]}
    topics = {c.get("topic", doc["topic"]) for c in doc["chapters"]}
    print(f"OK: build constraints and target coverage pass; formats={sorted(formats)}, topics={sorted(topics)}")
    return 0


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    vocab = sub.add_parser("vocab", help="print the exact allowed lemmas for a rank band")
    vocab.add_argument("--max-rank", type=int, required=True)
    check = sub.add_parser("validate", help="validate a draft without rebuilding assets")
    check.add_argument("story", type=Path)
    check.add_argument("--threshold", type=float, default=0.90)
    args = parser.parse_args()
    if args.command == "vocab":
        words = sorted(target_vocab(args.max_rank))
        print(f"# {len(words)} normalized tier-0 lemmas at generalFreqRank <= {args.max_rank}")
        print("\n".join(words))
        return 0
    return validate(args.story, args.threshold)


if __name__ == "__main__":
    raise SystemExit(main())
