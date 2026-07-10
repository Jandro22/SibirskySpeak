# -*- coding: utf-8 -*-
"""Report reader-text coverage by vocabulary-size band, and flag bands that
don't have enough texts a learner at that stage could actually read.

The reader only recommends a text once a learner's known-vocabulary coverage
of it is >=90% (see LearningRepository.MIN_READER_COVERAGE). A learner's known
vocabulary grows roughly in generalFreqRank order (highest-frequency tier-0
words first), so "how many texts clear 90% coverage if you know the top K
tier-0 words by frequency rank" is a good proxy for "how much can a learner at
this stage actually read" — this is the same analysis that found the reader
had ~0 usable texts below 300 known words (2026-07-06), before 40 new
controlled-vocabulary chapters were authored to fill that band.

Usage:
    python tools/preprocess/reader_gap_report.py
    python tools/preprocess/reader_gap_report.py --min-texts 10 --fail-on-gap
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path

import pymorphy3

ROOT = Path(__file__).resolve().parents[2]
NOTES = ROOT / "app/src/main/assets/bootstrap_notes.jsonl"
TEXTS = ROOT / "app/src/main/assets/bootstrap_reader_texts.jsonl"

WORD_RE = re.compile(r"[^\W\d_]+", re.UNICODE)
# Cast names are exempt from coverage the same way build_stories.py's own
# validator (and the app's reader, which treats capitalized non-sentence-
# initial words as likely names) already exempt them.
CHARACTER_NAMES = {
    "анна", "иван", "мария", "петр", "пётр", "мари",
    "максим", "ольга", "ирина", "денис",
}

# (upper bound of the known-word band, minimum qualifying-text count expected)
# Bands widen as they go up: a beginner needs texts almost immediately, but
# needs many of them (little else to do); an advanced learner has the whole
# "Между нами"/domain library organically clearing 90%, so the bar is lower.
DEFAULT_BANDS = [
    (100, 6), (200, 8), (300, 10), (500, 10), (750, 10),
    (1000, 10), (1500, 8), (2000, 8), (3000, 6), (999_999, 0),
]


def normalize(s: str) -> str:
    s = s.strip().lower().replace("ё", "е")
    s = unicodedata.normalize("NFD", s)
    return s.replace("́", "").replace("̈", "")


def load_ranked_tier0_notes() -> list[dict]:
    rows = []
    with NOTES.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            n = json.loads(line)
            if n.get("tier") == 0 and n.get("translation") != "lookup pending":
                rows.append(n)
    rows.sort(key=lambda n: n.get("generalFreqRank") if n.get("generalFreqRank") is not None else 999_999)
    return rows


def load_texts() -> list[tuple[str, list[str]]]:
    out = []
    with TEXTS.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            t = json.loads(line)
            body = t.get("body", "").replace("́", "").replace("̈", "")
            toks = WORD_RE.findall(body)
            if toks:
                out.append((t.get("title", "?"), toks))
    return out


def known_lemmas_at_rank(notes: list[dict], max_rank: int) -> set[str]:
    """Vocabulary known by a learner who has learned every tier-0 word with
    generalFreqRank <= max_rank. Filtering by rank VALUE (not list position)
    matters: many notes share tied rank values, so truncating by index instead
    of rank undercounts real coverage — a real bug this script's ancestor hit
    (e.g. "они", rank 82, landed at list index 304)."""
    lemmas = set()
    for n in notes:
        rank = n.get("generalFreqRank")
        if rank is not None and rank <= max_rank:
            lemma = normalize(n.get("lemma") or n.get("russian") or "")
            if lemma:
                lemmas.add(lemma)
    return lemmas


def coverage(toks: list[str], vocab: set[str], morph: pymorphy3.MorphAnalyzer) -> float:
    if not toks:
        return 0.0
    miss = 0
    for t in toks:
        nt = normalize(t)
        if nt in CHARACTER_NAMES:
            continue
        lemmas = {normalize(p.normal_form) for p in morph.parse(t)}
        if not (lemmas & vocab):
            miss += 1
    return (len(toks) - miss) / len(toks)


def _token_lemma_sets(toks: list[str], morph: pymorphy3.MorphAnalyzer) -> list[set[str] | None]:
    """Each token's lemma set, computed once. None marks a CHARACTER_NAMES token
    (always exempt, regardless of which band's vocab is being checked)."""
    result: list[set[str] | None] = []
    for t in toks:
        if normalize(t) in CHARACTER_NAMES:
            result.append(None)
        else:
            result.append({normalize(p.normal_form) for p in morph.parse(t)})
    return result


def _coverage_from_lemma_sets(lemma_sets: list[set[str] | None], vocab: set[str]) -> float:
    if not lemma_sets:
        return 0.0
    miss = sum(1 for lemmas in lemma_sets if lemmas is not None and not (lemmas & vocab))
    return (len(lemma_sets) - miss) / len(lemma_sets)


def report(bands: list[tuple[int, int]], threshold: float = 0.90) -> tuple[str, bool]:
    notes = load_ranked_tier0_notes()
    texts = load_texts()
    morph = pymorphy3.MorphAnalyzer()
    # A token's lemma set doesn't depend on which band's vocab is being checked,
    # only band membership does — parse each text's tokens once instead of once
    # per band (9 bands would otherwise re-run pymorphy3 on every token 9x).
    texts_with_lemmas = [(name, _token_lemma_sets(toks, morph)) for name, toks in texts]
    lines = [f"tier0 ranked notes: {len(notes)}, reader texts: {len(texts)}",
             f"{'known words <=':>16} {'qualifying texts':>17} {'min expected':>13} {'status':>8}"]
    any_gap = False
    for max_rank, min_expected in bands:
        if max_rank >= 999_999:
            continue
        vocab = known_lemmas_at_rank(notes, max_rank)
        qualifying = sum(
            1 for _, lemma_sets in texts_with_lemmas
            if _coverage_from_lemma_sets(lemma_sets, vocab) >= threshold
        )
        gap = qualifying < min_expected
        any_gap = any_gap or gap
        status = "GAP" if gap else "ok"
        lines.append(f"{max_rank:>16} {qualifying:>17} {min_expected:>13} {status:>8}")
    return "\n".join(lines), any_gap


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--threshold", type=float, default=0.90, help="coverage fraction counted as 'qualifying' (default 0.90, matching MIN_READER_COVERAGE)")
    p.add_argument("--fail-on-gap", action="store_true", help="exit 1 if any band is below its minimum expected text count")
    return p


def main() -> int:
    args = parser().parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    text, any_gap = report(DEFAULT_BANDS, threshold=args.threshold)
    print(text)
    if any_gap:
        print("\nGAP: one or more known-vocabulary bands don't have enough >=90%-coverage "
              "reader texts. See tools/preprocess/stories/ to author more controlled-vocabulary "
              "chapters targeting the flagged band(s).")
    return 1 if (any_gap and args.fail_on_gap) else 0


if __name__ == "__main__":
    sys.exit(main())
