# -*- coding: utf-8 -*-
"""Always-on quality gate for the *shipped* textbook content.

Unlike test_textbook_ingest.py (which needs the local Между нами PDFs and skips
without them), this validates the committed bootstrap_notes.jsonl /
bootstrap_reader_texts.jsonl that actually ship in the APK. It runs everywhere —
CI, a clean checkout — so the exercise debris and mis-glossed inflected forms that
previously reached learners cannot regress silently.
"""
import json
import re
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets"
NOTES = ASSETS / "bootstrap_notes.jsonl"
READERS = ASSETS / "bootstrap_reader_texts.jsonl"

CYR_WORD = re.compile(r"[А-Яа-яЁё́]+")


def _strip(s: str) -> str:
    return s.replace("́", "")


def _load(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _textbook_notes():
    return [n for n in _load(NOTES) if "textbook" in n.get("tags", "")]


def _textbook_readers():
    return [r for r in _load(READERS) if "textbook:" in r.get("source", "")]


# --- the standalone intermediate files must never ship in the APK --------------

def test_no_standalone_textbook_assets_packaged():
    for name in ("textbook_notes.jsonl", "textbook_reader_texts.jsonl"):
        assert not (ASSETS / name).exists(), (
            f"{name} is an ingest intermediate and must not live under app/src/main/assets "
            f"(it would be packaged into the APK as dead weight); it belongs in tools/preprocess/out"
        )


# --- vocabulary quality --------------------------------------------------------

def test_textbook_vocab_is_clean_dictionary_vocabulary():
    notes = _textbook_notes()
    assert notes, "expected textbook vocabulary in bootstrap_notes.jsonl"
    for n in notes:
        ru, gloss = n["russian"], n["translation"]
        assert CYR_WORD.search(ru), n
        # No fabricated metadata translations.
        assert not gloss.lower().startswith(("textbook phrase", "practice phrase")), n
        # A dictionary gloss is short, not a clause/instruction/description.
        assert 1 <= len(gloss.split()) <= 3, f"phrase-like gloss leaked: {n}"
        assert not re.search(r"\b(is|are|was|were)\b", gloss.lower()), f"clause gloss: {n}"
        assert gloss.lower() not in {
            "finish", "correct", "recall", "change", "connect", "past tense", "plural",
        }, f"instruction/metadata gloss: {n}"
        # Lemma is namespaced so it never collides with the curated deck.
        assert n["lemma"].startswith("tb_"), n
        # No instruction-imperative surfaces ("Закончите", "Соедините").
        bare = _strip(ru).lower()
        if " и " not in f" {bare} ":  # exclude coordinated phrases
            assert not (bare.endswith(("йте", "ите", "ьте")) and len(bare) > 5), f"imperative surface: {n}"


def test_recognition_only_marks_inflected_textbook_forms():
    # Recognition-only notes carry the tag the app uses to suppress reverse-production
    # on an oblique form. They must still be real, glossed words.
    for n in _textbook_notes():
        if "recognition_only" in n["tags"]:
            assert CYR_WORD.search(n["russian"]) and n["translation"]


# --- reader quality ------------------------------------------------------------

def test_textbook_readers_are_connected_prose():
    readers = _textbook_readers()
    assert len(readers) > 100, "expected a substantial textbook reading band"
    for r in readers:
        body = r["body"]
        assert re.match(r"textbook:a[12]:", r["source"]), r["source"]
        # No fill-in blanks, choice slashes, alternation, or ellipsis stubs.
        assert not any(ch in body for ch in ("_", "/", "…", "=")), f"exercise debris: {r['title']}"
        words = CYR_WORD.findall(body)
        real = [w for w in words if len(_strip(w)) >= 2]
        assert len(real) >= 8, r["title"]
        # Not single-letter alphabet/sound-drill soup.
        singles = sum(1 for w in words if len(_strip(w)) == 1)
        assert singles / len(words) <= 0.15, f"alphabet soup: {r['title']} :: {body[:60]}"
        # Real narrative carries lowercase content words (a name roster does not).
        lower_content = [w for w in real if _strip(w)[:1].islower() and len(_strip(w)) >= 3]
        assert lower_content, f"name roster, not prose: {r['title']} :: {body[:60]}"
        # Title has no trailing English translator parenthetical.
        assert not re.search(r"\([A-Za-z][^)]*\)\s*$", r["title"]), f"instruction title: {r['title']}"
