import hashlib
import json
from pathlib import Path

from curriculum_common import CONCEPT_TITLES, spine2_rows

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"
HERE = Path(__file__).resolve().parent


def test_manifest_matches_bundled_notes_exactly():
    notes_path = ASSETS / "bootstrap_notes.jsonl"
    notes = [json.loads(line) for line in notes_path.read_text(encoding="utf-8").splitlines() if line]
    manifest = json.loads((ASSETS / "curriculum_manifest.json").read_text(encoding="utf-8"))
    assert manifest["contentChecksum"] == hashlib.sha256(notes_path.read_bytes()).hexdigest()
    assert sum(manifest["noteCountsByBand"].values()) == len(notes)
    assert sum(manifest["noteCountsByTier"].values()) == len(notes)


def test_every_band_unit_has_a_functional_exit_ticket():
    from build_curriculum_metadata import all_rows
    document = json.loads((HERE / "units.yaml").read_text(encoding="utf-8"))
    expected = {(r["cefrLevel"], int(r["unit"])) for r in all_rows() if r.get("cefrLevel") and r.get("unit") is not None}
    actual = {(u["band"], u["unit"]) for u in document["units"]}
    assert actual == expected
    assert len(actual) == len(document["units"])
    for unit in document["units"]:
        assert unit["canDo"]
        assert "understand and use unit" not in unit["canDo"]
        assert unit["exitTicket"]["function"] == unit["canDo"]
        assert all(unit["exitTicket"][key] == 1 for key in ("recognition", "production", "listening", "reading"))


def test_spine_is_dense_through_upper_register():
    assert len(CONCEPT_TITLES) >= 100
    rows = spine2_rows()
    assert any(row["unit"] > 49 for row in rows)
    assert {row["cefrLevel"] for row in rows} == {"A1", "A2", "B1", "B2", "C1", "C2"}


def test_phonology_marks_unreliable_tts_contrasts():
    items = json.loads((HERE / "phonology.json").read_text(encoding="utf-8"))["items"]
    kinds = {item["kind"] for item in items}
    assert {"MINIMAL_PAIR", "INTONATION", "RULE", "FAST_SPEECH", "STRESS_MOBILITY"} <= kinds
    assert all(item["requiresAudioPack"] for item in items if item["kind"] == "INTONATION")


def test_register_transformations_are_deterministic():
    pairs = json.loads((HERE / "transformations.json").read_text(encoding="utf-8"))["pairs"]
    assert len({pair["id"] for pair in pairs}) == len(pairs)
    assert all(pair["source"] != pair["answer"] and pair["band"] in {"B2", "C1", "C2"} for pair in pairs)


def test_curriculum_completeness_metric_is_reproducible_and_shipped():
    from build_curriculum_metadata import completeness
    shipped = json.loads((ASSETS / "curriculum_completeness.json").read_text(encoding="utf-8"))
    assert set(shipped) == {"A1", "A2", "B1", "B2+"}
    for band, metrics in shipped.items():
        assert set(metrics) == {"corpusSentences", "parseableSentences", "percent"}
        assert metrics["corpusSentences"] > 0
        assert 0 <= metrics["parseableSentences"] <= metrics["corpusSentences"]
        assert metrics["percent"] == round(100.0 * metrics["parseableSentences"] / metrics["corpusSentences"], 2)
    assert completeness() == shipped
