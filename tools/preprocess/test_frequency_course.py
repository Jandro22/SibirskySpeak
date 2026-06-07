# -*- coding: utf-8 -*-
"""Tests for promoting the top-frequency reading-matrix words into the gated tier-0
course (frequency-sorted, unit-gated, CEFR-banded), reusing deck-verified data."""
from build_bootstrap import promote_to_course


def _fake_general(n):
    return [
        {"lemma": f"w{i}", "generalFreqRank": i + 1,
         "tags": "general curated matrix", "tier": 1,
         "exampleSentence": "x", "exampleTranslation": "y"}
        for i in range(n)
    ]


def test_promotes_target_to_tier0_unit_and_level():
    promoted, remaining = promote_to_course(_fake_general(6000), target=4750,
                                            per_unit=40, start_unit=100)
    assert len(promoted) == 4750
    assert len(remaining) == 1250
    assert all(p["tier"] == 0 for p in promoted)
    assert all(p["unit"] >= 100 for p in promoted)
    assert all(p["cefrLevel"] in {"A1", "A2", "B1", "B2", "C1"} for p in promoted)
    # Keep the "matrix" tag so the app builds no unverified morphology drills.
    assert all("matrix" in p["tags"] for p in promoted)
    # Frequency-sorted: unit numbers are non-decreasing with rank.
    units = [p["unit"] for p in promoted]
    assert units == sorted(units)
    # Remaining words stay tier-1 reading fuel.
    assert all(r.get("tier") == 1 for r in remaining)


def test_cefr_banding_by_frequency_rank():
    promoted, _ = promote_to_course(_fake_general(5000), target=5000)
    by_rank = {p["generalFreqRank"]: p["cefrLevel"] for p in promoted}
    assert by_rank[1] == "A1"
    assert by_rank[800] == "A1"
    assert by_rank[801] == "A2"
    assert by_rank[1500] == "A2"
    assert by_rank[2750] == "B1"
    assert by_rank[4500] == "B2"
    assert by_rank[4501] == "C1"


def test_target_caps_at_available_words():
    promoted, remaining = promote_to_course(_fake_general(100), target=4750)
    assert len(promoted) == 100
    assert remaining == []


def test_promoted_notes_keep_their_verified_example():
    promoted, _ = promote_to_course(_fake_general(10), target=10)
    assert all(p.get("exampleSentence") and p.get("exampleTranslation") for p in promoted)
