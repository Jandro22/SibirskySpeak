from __future__ import annotations

import argparse
import bz2
import json
import sqlite3
from pathlib import Path
from types import SimpleNamespace

import pytest

from build_tatoeba import acceptable, build


def write(path: Path, rows: list[str]) -> Path:
    path.write_text("\n".join(rows) + "\n", encoding="utf-8")
    return path


def fixture_args(tmp_path: Path) -> SimpleNamespace:
    russian = write(tmp_path / "rus.tsv", [
        "1\tЯ читаю эту хорошую книгу.",
        "2\tОна читает новую книгу дома.",
        "3\tКнига лежит на большом столе.",
        "4\tHTTP://BAD.EXAMPLE здесь нельзя читать.",
        "5\tСЛИШКОМ ГРОМКАЯ ФРАЗА ЗДЕСЬ.",
    ])
    english = write(tmp_path / "eng.tsv", [
        "101\tI am reading this good book.", "102\tShe reads a new book at home.",
        "103\tThe book is on the large table.",
    ])
    links = write(tmp_path / "links.tsv", ["1\t101", "2\t102", "3\t103", "4\t101", "5\t101"])
    audio = write(tmp_path / "audio.tsv", ["2\t99"])
    stress = write(tmp_path / "stress.tsv", ["книга\tкни́га\t*", "книгу\tкни́гу\t*"])
    schema = tmp_path / "schema.json"
    schema.write_text(json.dumps({"database": {"identityHash": "test-hash"}}), encoding="utf-8")
    return SimpleNamespace(
        russian=russian, english=english, links=links, audio=audio,
        stress_lexicon=[str(stress)], bootstrap_stress=None, room_schema=schema,
        output=tmp_path / "tatoeba.db", max_sentences=10, per_lemma=2,
        min_chunk_freq=1, version="test", allow_simple=True,
        fasttext_vectors=None,
        indices_per_sentence=1,
    )


def test_hard_filter():
    assert acceptable("Я читаю хорошую книгу.")[0]
    assert not acceptable("Два слова.")[0]
    assert not acceptable("HTTP://example.test нельзя открыть сейчас.")[0]
    assert not acceptable("ЭТО ОЧЕНЬ ГРОМКАЯ ФРАЗА.")[0]


def test_builds_room_asset_with_translation_cap_stress_and_audio(tmp_path: Path):
    args = fixture_args(tmp_path)
    summary = build(args)
    assert summary["sentences"] == 3
    db = sqlite3.connect(args.output)
    assert db.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone()[0] == "test-hash"
    assert db.execute("SELECT COUNT(*) FROM sentence WHERE en='' OR en IS NULL").fetchone()[0] == 0
    assert db.execute("SELECT MAX(n) FROM (SELECT COUNT(*) n FROM lemma_index GROUP BY lemma)").fetchone()[0] <= 2
    assert db.execute("SELECT audio FROM sentence WHERE id=2").fetchone()[0] == 1
    assert "\u0301" in db.execute("SELECT ru_stressed FROM sentence WHERE id=1").fetchone()[0]
    assert db.execute("SELECT value FROM meta WHERE key='attribution'").fetchone()[0].startswith("Example sentences from Tatoeba")
    db.close()


def validate(path: Path, bootstrap: Path, minimum_stress: float = 0.0, room_schema: Path | None = None) -> list[str]:
    errors: list[str] = []
    db = sqlite3.connect(path)
    if db.execute("SELECT COUNT(*) FROM sentence WHERE en='' OR en IS NULL").fetchone()[0]:
        errors.append("sentences without English translations")
    if db.execute("SELECT COUNT(*) FROM sentence WHERE n_tokens < 3 OR n_tokens > 12").fetchone()[0]:
        errors.append("sentence length outside 3..12")
    if db.execute("SELECT COALESCE(MAX(n),0) FROM (SELECT COUNT(*) n FROM lemma_index GROUP BY lemma)").fetchone()[0] > 8:
        errors.append("per-lemma cap exceeded")
    coverage = float(db.execute("SELECT value FROM meta WHERE key='stress_coverage'").fetchone()[0])
    if coverage < minimum_stress:
        errors.append(f"stress coverage {coverage:.3f} below {minimum_stress:.3f}")
    if room_schema is not None:
        expected = json.loads(room_schema.read_text(encoding="utf-8"))["database"]["identityHash"]
        actual = db.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone()[0]
        if expected != actual:
            errors.append(f"Room identity mismatch: asset={actual}, schema={expected}")
    top = []
    with bootstrap.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            if row.get("lemma") and row.get("partOfSpeech") != "lesson":
                top.append(row["lemma"].lower().replace("ё", "е"))
            if len(top) >= 100:
                break
    missing = [lemma for lemma in top if not db.execute("SELECT 1 FROM lemma_index WHERE lemma=? LIMIT 1", (lemma,)).fetchone()]
    if len(missing) > len(top) * 0.25:
        errors.append(f"top-deck coverage too low: {len(top)-len(missing)}/{len(top)}")
    db.close()
    return errors


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument("db", type=Path, nargs="?", default=root / "app/src/main/assets/tatoeba.db")
    parser.add_argument("--bootstrap", type=Path, default=root / "app/src/main/assets/bootstrap_notes.jsonl")
    parser.add_argument("--minimum-stress", type=float, default=0.10)
    parser.add_argument("--room-schema", type=Path, default=root / "app/schemas/com.sibirskyspeak.data.ContentDatabase/1.json")
    options = parser.parse_args()
    problems = validate(options.db, options.bootstrap, options.minimum_stress, options.room_schema)
    if problems:
        raise SystemExit("\n".join(problems))
    print(f"validated {options.db}")
