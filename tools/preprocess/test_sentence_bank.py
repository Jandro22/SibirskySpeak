import sqlite3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def test_sentence_bank_has_graded_inventory_from_unit_three():
    with sqlite3.connect(ROOT/'app/src/main/assets/tatoeba.db') as db:
        assert db.execute("select count(*) from sentence_bank where unit_min>=3").fetchone()[0] >= 100
        assert db.execute("select count(distinct band) from sentence_bank").fetchone()[0] >= 2
        assert db.execute("select count(*) from sentence_bank where unit_min<1 or token_count<3").fetchone()[0] == 0
