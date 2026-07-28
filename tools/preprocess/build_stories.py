#!/usr/bin/env python3
"""Validates and appends serial-narrative chapters (P5.1) to
bootstrap_reader_texts.jsonl. Chapters are authored in tools/preprocess/stories/
as one JSON file per series (seriesId, band, title, chapters[]); build_bootstrap.py
never validates content itself, so this script is the only gate.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import unicodedata
from pathlib import Path

import pymorphy3

ROOT = Path(__file__).resolve().parents[2]
WORD = re.compile(r"[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?")
# Proper nouns / recurring cast names aren't in the curriculum lemma list and
# aren't meant to be — the app's own reader already exempts capitalized,
# non-sentence-initial unknowns as likely names. Chapters may introduce a
# small, fixed cast; list them once here instead of per-chapter.
KNOWN_CHARACTER_NAMES = {"анна", "иван", "мария", "петр", "максим", "ольга", "ирина", "денис"}


def norm(value: str) -> str:
    value = unicodedata.normalize("NFD", value.lower()).replace("\u0301", "")
    # NFD decomposes й as well as stress marks; always recompose before using
    # the result as a pymorphy/dictionary key.
    return unicodedata.normalize("NFC", value).replace("ё", "е")


def known_lemmas(notes_path: Path) -> set[str]:
    lemmas = set()
    with notes_path.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            if row.get("tier") == 0:
                lemma = row.get("lemma") or row.get("russian")
                if lemma:
                    lemmas.add(norm(lemma))
    return lemmas


def unknown_words(body: str, morph: pymorphy3.MorphAnalyzer, vocab: set[str]) -> list[str]:
    unknown = []
    for match in WORD.finditer(body):
        word = match.group(0)
        normalized = norm(word)
        if normalized in KNOWN_CHARACTER_NAMES:
            continue
        parses = morph.parse(word)
        lemmas = {norm(p.normal_form) for p in parses}
        if lemmas & KNOWN_CHARACTER_NAMES:
            continue
        if not lemmas & vocab:
            unknown.append(word)
    return unknown


def validate_series(doc: dict, vocab: set[str], morph: pymorphy3.MorphAnalyzer, gloss_budget: int = 3) -> None:
    for field in ("seriesId", "band", "title", "targetMaxRank", "format", "topic"):
        if field not in doc:
            raise SystemExit(f"{doc.get('seriesId', '<unknown>')}: missing required metadata '{field}'")
    if not isinstance(doc["targetMaxRank"], int) or doc["targetMaxRank"] <= 0:
        raise SystemExit(f"{doc['seriesId']}: targetMaxRank must be a positive integer")
    chapters = doc["chapters"]
    if not chapters:
        raise SystemExit(f"{doc['seriesId']}: no chapters")
    numbers = [c["chapter"] for c in chapters]
    if numbers != list(range(1, len(numbers) + 1)):
        raise SystemExit(f"{doc['seriesId']}: chapters must be numbered contiguously from 1, got {numbers}")
    for chapter in chapters:
        word_count = len(WORD.findall(chapter["body"]))
        if not (40 <= word_count <= 300):
            raise SystemExit(f"{doc['seriesId']} ch.{chapter['chapter']}: {word_count} words, outside the 40-300 band")
        translation = chapter.get("translation", "").strip()
        if translation:
            russian_sentences = len(re.findall(r"[.!?]+", chapter["body"]))
            english_sentences = len(re.findall(r"[.!?]+", translation))
            if russian_sentences != english_sentences:
                raise SystemExit(
                    f"{doc['seriesId']} ch.{chapter['chapter']}: parallel sentence count "
                    f"{russian_sentences} != {english_sentences}"
                )
        unknown = unknown_words(chapter["body"], morph, vocab)
        # A sentence-aligned English companion is an always-available scaffold, so
        # upper-level parallel texts may deliberately carry a richer lexical load.
        # Monolingual stories retain the strict three-word narrow-reading budget.
        budget = max(gloss_budget, math.ceil(word_count * 0.25)) if chapter.get("translation") else gloss_budget
        if len(unknown) > budget:
            raise SystemExit(f"{doc['seriesId']} ch.{chapter['chapter']}: {len(unknown)} unglossed unknown words (budget {budget}): {unknown}")


def build(stories_dir: Path, notes_path: Path, reader_texts_path: Path) -> dict:
    vocab = known_lemmas(notes_path)
    morph = pymorphy3.MorphAnalyzer()
    existing = {json.loads(line)["title"] for line in reader_texts_path.read_text(encoding="utf-8").splitlines() if line.strip()}
    appended = 0
    with reader_texts_path.open("a", encoding="utf-8") as out:
        for story_file in sorted(stories_dir.glob("*.json")):
            doc = json.loads(story_file.read_text(encoding="utf-8"))
            validate_series(doc, vocab, morph)
            for chapter in doc["chapters"]:
                title = f"{doc['band']} · {doc['title']} — {chapter['title']}"
                if title in existing:
                    continue
                row = {
                    "title": title,
                    "source": f"story:{doc['seriesId']}:{chapter['chapter']}",
                    "body": chapter["body"],
                    # Static authoring intent for offline audits. The app still
                    # computes live coverage from each learner's actual words.
                    "targetMaxRank": doc["targetMaxRank"],
                    "format": chapter.get("format", doc["format"]),
                    "topic": chapter.get("topic", doc["topic"]),
                }
                if chapter.get("translation"):
                    row["translationBody"] = chapter["translation"]
                if chapter.get("cast"):
                    row["cast"] = chapter["cast"]
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
                appended += 1
    return {"appended": appended}


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser()
    p.add_argument("--stories-dir", type=Path, default=Path(__file__).parent / "stories")
    p.add_argument("--notes", type=Path, default=ROOT / "app/src/main/assets/bootstrap_notes.jsonl")
    p.add_argument("--reader-texts", type=Path, default=ROOT / "app/src/main/assets/bootstrap_reader_texts.jsonl")
    return p


if __name__ == "__main__":
    args = parser().parse_args()
    print(json.dumps(build(args.stories_dir, args.notes, args.reader_texts), ensure_ascii=False, indent=2))
