import importlib.util
import sqlite3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

SPEC = importlib.util.spec_from_file_location("build_sentence_bank", ROOT / "tools/preprocess/build_sentence_bank.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_sentence_bank_normalization_preserves_precomposed_short_i():
    assert MODULE.norm("кра́й") == "край"
    assert MODULE.norm("Й") == "й"


def test_curriculum_lexicon_keeps_earliest_band_and_unit():
    notes = [
        {"lemma": "идти", "tier": 0, "unit": 17, "cefrLevel": "A2"},
        {"lemma": "идти", "tier": 0, "unit": 99, "cefrLevel": "B1"},
        {"lemma": "край", "tier": 0, "unit": 5, "cefrLevel": "A1"},
    ]
    lexicon = MODULE.curriculum_lexicon(notes)
    assert lexicon["идти"] == (1, 17, "A2")
    assert lexicon["край"] == (0, 5, "A1")


def test_sentence_bank_has_graded_inventory_from_unit_three():
    with sqlite3.connect(ROOT/'app/src/main/assets/tatoeba.db') as db:
        assert db.execute("select count(*) from sentence_bank where unit_min>=3").fetchone()[0] >= 100
        assert db.execute("select count(distinct band) from sentence_bank").fetchone()[0] >= 2
        assert db.execute("select count(*) from sentence_bank where unit_min<1 or token_count<3").fetchone()[0] == 0
        assert db.execute("select count(*) from sentence_bank where band not in ('A1','A2','B1','B2','C1','C2')").fetchone()[0] == 0


def test_bundled_content_database_matches_latest_room_schema_version():
    schema_dir = ROOT / "app/schemas/com.sibirskyspeak.data.ContentDatabase"
    latest = max(int(path.stem) for path in schema_dir.glob("*.json"))
    with sqlite3.connect(ROOT / "app/src/main/assets/tatoeba.db") as db:
        assert db.execute("pragma user_version").fetchone()[0] == latest
