import json
import re
from pathlib import Path

import pymorphy3

from build_stories import known_lemmas, unknown_words, validate_series

ROOT = Path(__file__).resolve().parents[2]
STORIES_DIR = Path(__file__).parent / "stories"
WORD = re.compile(r"[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?")


def _stories():
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(STORIES_DIR.glob("*.json"))]


def test_at_least_one_story_series_is_authored():
    assert _stories(), "expected at least one story series under tools/preprocess/stories/"


def test_chapters_are_numbered_contiguously_from_one():
    for doc in _stories():
        numbers = [c["chapter"] for c in doc["chapters"]]
        assert numbers == list(range(1, len(numbers) + 1)), f"{doc['seriesId']}: {numbers}"


def test_chapter_length_is_within_the_narrow_reading_band():
    for doc in _stories():
        for chapter in doc["chapters"]:
            word_count = len(WORD.findall(chapter["body"]))
            assert 40 <= word_count <= 300, f"{doc['seriesId']} ch.{chapter['chapter']}: {word_count} words"


def test_chapter_vocabulary_stays_within_the_gloss_budget():
    vocab = known_lemmas(ROOT / "app/src/main/assets/bootstrap_notes.jsonl")
    morph = pymorphy3.MorphAnalyzer()
    for doc in _stories():
        for chapter in doc["chapters"]:
            unknown = unknown_words(chapter["body"], morph, vocab)
            assert len(unknown) <= 3, f"{doc['seriesId']} ch.{chapter['chapter']}: unglossed words {unknown}"


def test_series_validation_helper_rejects_a_broken_chapter_sequence():
    vocab = known_lemmas(ROOT / "app/src/main/assets/bootstrap_notes.jsonl")
    morph = pymorphy3.MorphAnalyzer()
    broken = {
        "seriesId": "broken",
        "band": "A1",
        "title": "Broken",
        "chapters": [{"chapter": 1, "title": "One", "body": "x"}, {"chapter": 3, "title": "Three", "body": "y"}],
    }
    try:
        validate_series(broken, vocab, morph)
        assert False, "expected non-contiguous chapter numbering to be rejected"
    except SystemExit:
        pass


def test_bundled_reader_texts_include_the_story_installment():
    reader_texts_path = ROOT / "app/src/main/assets/bootstrap_reader_texts.jsonl"
    rows = [json.loads(line) for line in reader_texts_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    story_rows = [r for r in rows if r.get("source", "").startswith("story:")]
    assert len(story_rows) >= sum(len(doc["chapters"]) for doc in _stories())
