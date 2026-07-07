import json
import re
from pathlib import Path

import pymorphy3

from build_stories import known_lemmas, unknown_words, validate_series
from reader_gap_report import DEFAULT_BANDS, WORD_RE, coverage, known_lemmas_at_rank, load_ranked_tier0_notes, report

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
    assert all({"targetMaxRank", "format", "topic"} <= row.keys() for row in story_rows)
    assert any(row.get("cast") == ["Максим", "Ольга"] for row in story_rows)
    assert any(row.get("cast") == ["Денис", "Ирина"] for row in story_rows)


def test_authored_target_rank_matches_measured_coverage():
    notes = load_ranked_tier0_notes()
    morph = pymorphy3.MorphAnalyzer()
    for doc in _stories():
        vocab = known_lemmas_at_rank(notes, doc["targetMaxRank"])
        for chapter in doc["chapters"]:
            score = coverage(WORD_RE.findall(chapter["body"]), vocab, morph)
            assert score >= 0.90, (
                f"{doc['seriesId']} ch.{chapter['chapter']} declares rank "
                f"{doc['targetMaxRank']} but measures {score:.1%}"
            )


def test_every_known_vocabulary_band_has_enough_readable_texts():
    # Regression guard for the 2026-07-06 gap: a learner who knew only the app's
    # own tier-0 curriculum had ~0 texts clearing the reader's 90%-coverage bar
    # below ~300 known words. See reader_gap_report.py for the full methodology
    # and DEFAULT_BANDS for the per-band minimums this asserts.
    text, any_gap = report(DEFAULT_BANDS)
    assert not any_gap, f"reader coverage gap detected:\n{text}"
