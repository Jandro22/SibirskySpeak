# -*- coding: utf-8 -*-
import re
from pathlib import Path

import pytest

from textbook_ingest import (
    TEXTBOOKS,
    extract_activities,
    vocab_notes_from_activities,
    reader_texts_from_activities,
)

CYR = re.compile(r"[А-Яа-яЁё]")
LAT = re.compile(r"[A-Za-z]")


def _missing_textbooks():
    return [str(meta["path"]) for meta in TEXTBOOKS if not Path(meta["path"]).exists()]


@pytest.mark.skipif(_missing_textbooks(), reason="local Между нами PDFs not present")
def test_extracts_all_four_textbooks_into_activities():
    activities = extract_activities()
    slugs = {a.textbook_slug for a in activities}

    assert slugs == {meta["slug"] for meta in TEXTBOOKS}
    assert len(activities) >= 250
    assert any(a.kind == "homework" for a in activities)
    assert any(a.kind == "classroom" for a in activities)


@pytest.mark.skipif(_missing_textbooks(), reason="local Между нами PDFs not present")
def test_reader_texts_are_connected_prose_and_level_tagged():
    readers = reader_texts_from_activities(extract_activities())

    assert len(readers) >= 200
    for row in readers:
        # Level-tagged textbook source (A1 = units 1-5, A2 = units 6-9).
        assert re.match(r"textbook:a[12]:", row["source"]), row["source"]
        body = row["body"]
        # Real connected prose: multiple sentences, no fill-in blanks or exercise
        # alternations, overwhelmingly Cyrillic.
        assert "_" not in body and "/" not in body
        assert len(re.findall(r"[.!?]", body)) >= 2
        assert len(CYR.findall(body)) > 4 * len(LAT.findall(body))
    assert any(":a1:" in r["source"] for r in readers)
    assert any(":a2:" in r["source"] for r in readers)


@pytest.mark.skipif(_missing_textbooks(), reason="local Между нами PDFs not present")
def test_vocab_notes_carry_real_glosses_not_metadata():
    notes = vocab_notes_from_activities(extract_activities(), limit=400)

    assert len(notes) >= 100
    for n in notes:
        # Real, honest translations — never the old fabricated metadata.
        assert not n["translation"].lower().startswith("textbook phrase")
        assert not n["translation"].lower().startswith("practice phrase")
        assert n["pos"] == "word"
        assert n["lemma"].startswith("tb_")          # namespaced, no deck collisions
        assert CYR.search(n["russian"])
        assert n["cefrLevel"] in {"A1", "A2"}
        # No "unit": the textbook's own chapter numbers (1-9) are a different
        # namespace from the curated spine's unit numbers and collide by
        # coincidence, not by topic, so these notes intentionally stay unit-less
        # (tier 0, but not folded into spine unit-mastery stats). The textbook
        # chapter is still recoverable from the "unit-N" tag.
        assert "unit" not in n
        assert re.search(r"unit-[1-9]\b", n["tags"])
        # A gloss is a short meaning, not a sentence-long instruction.
        assert 1 <= len(n["translation"].split()) <= 5
    # Known good glosses are recovered from the book.
    glosses = {n["translation"].lower() for n in notes}
    assert any(g in glosses for g in ("march", "seven", "dictionary", "car"))
    assert not ({"barbara", "masha", "yuri", "sasha", "yalta", "malta"} & glosses)
