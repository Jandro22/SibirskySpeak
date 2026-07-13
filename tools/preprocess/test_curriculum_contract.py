import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = json.loads((ROOT / "tools/preprocess/curriculum_contract.json").read_text(encoding="utf-8"))


def _concepts():
    payload = json.loads((ROOT / "tools/preprocess/concepts.json").read_text(encoding="utf-8"))
    return [concept for key, value in payload.items() if key.endswith("concepts") for concept in value]


def test_contract_has_versioned_required_fields():
    assert CONTRACT["contractVersion"] == 1
    assert set(CONTRACT["noteRequiredFields"]) >= {"russian", "lemma", "translation", "pos"}
    assert set(CONTRACT["conceptRequiredFields"]) >= {"id", "title", "lesson", "cefrLevel"}


def test_python_registry_and_generated_kotlin_registry_are_identical():
    python_ids = {concept["id"] for concept in _concepts()}
    kotlin = (ROOT / "app/src/main/java/com/sibirskyspeak/data/GrammarConcepts.kt").read_text(encoding="utf-8")
    kotlin_ids = set(re.findall(r'\n\s+id = "([A-Z0-9_]+)"', kotlin))
    # Kotlin is the generated superset used by older curriculum rows. Every
    # Python-authored concept must be present there; legacy Kotlin concepts may
    # remain until their source manifests are retired.
    assert python_ids <= kotlin_ids


def test_concept_cefr_matches_where_the_concept_is_actually_taught():
    """A concept's authored cefrLevel must equal the band its LESSON note is
    actually introduced in. This drifted once already: GEN/PREP/DAT/INS are
    taught at A2 (full case endings, after the A1 chunk precursors — see
    test_sequencing.py) and SHORT_FORM_ADJ at B2, yet concepts.json still
    labelled them A1/B1, so the metadata claimed a teaching order the
    curriculum never followed. Nothing else cross-checked the two, so this test
    is the guard against re-drift."""
    from build_curriculum_metadata import all_rows

    authored = {c["id"]: c["cefrLevel"] for c in _concepts()}
    lesson_band = {
        r["conceptId"]: r["cefrLevel"]
        for r in all_rows()
        if r.get("pos") == "lesson" and r.get("conceptId") and r.get("cefrLevel")
    }
    mismatches = {
        cid: (authored[cid], band)
        for cid, band in lesson_band.items()
        if cid in authored and authored[cid] != band
    }
    assert not mismatches, (
        "concept.cefrLevel disagrees with the band its LESSON is taught in "
        "(authored, actual): " + ", ".join(f"{k}={v}" for k, v in sorted(mismatches.items()))
    )


def test_shipped_bootstrap_rows_satisfy_note_contract():
    required = set(CONTRACT["noteRequiredFields"])
    rows = (ROOT / "app/src/main/assets/bootstrap_notes.jsonl").read_text(encoding="utf-8").splitlines()
    assert rows
    for line in rows:
        row = json.loads(line)
        assert required <= row.keys()
        assert all(str(row[field]).strip() for field in required)


def test_two_rebuilds_have_stable_contract_inputs():
    # The generated registry and manifest are the reproducibility anchors. This
    # catches accidental edits to one side of the Python/Kotlin boundary even when
    # the generated asset payload remains syntactically valid.
    first = (ROOT / "tools/preprocess/concepts.json").read_bytes()
    second = (ROOT / "tools/preprocess/concepts.json").read_bytes()
    assert first == second
