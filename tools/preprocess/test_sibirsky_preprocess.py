import json
from pathlib import Path

import pytest

from sibirsky_preprocess import main
from build_bootstrap import (
    BAD_EXAMPLE_MARKERS,
    adjective_rows,
    noun_rows,
    verb_rows,
)


def test_rank_domain_and_build_notes(tmp_path: Path):
    raw = tmp_path / "raw"
    raw.mkdir()
    # Use a lemma-stable headword ("письмо" is its own dictionary form) so the test
    # holds whether or not a morphology analyzer (pymorphy3) is installed to lemmatize.
    (raw / "sample.txt").write_text("Письмо здесь. Письмо там.", encoding="utf-8")
    freq = tmp_path / "domain.tsv"
    main(["rank-domain", "--input", str(raw), "--output", str(freq)])
    assert "письмо" in freq.read_text(encoding="utf-8")

    lexicon = tmp_path / "lexicon.jsonl"
    lexicon.write_text(json.dumps({"lemma": "письмо", "russian": "письмо́", "translation": "letter", "pos": "noun"}, ensure_ascii=False), encoding="utf-8")
    notes = tmp_path / "notes.jsonl"
    main(["build-notes", "--lexicon", str(lexicon), "--domain-frequency", str(freq), "--output", str(notes)])
    note = json.loads(notes.read_text(encoding="utf-8"))
    assert note["domainFreqRank"] == 1


def test_draft_aktionsart_requires_verification(tmp_path: Path):
    verbs = tmp_path / "verbs.txt"
    verbs.write_text("написать\nписать\n", encoding="utf-8")
    out = tmp_path / "aktionsart.jsonl"
    main(["draft-aktionsart", "--verbs", str(verbs), "--output", str(out)])
    rows = [json.loads(line) for line in out.read_text(encoding="utf-8").splitlines()]
    assert rows[0]["verified"] is False
    assert rows[0]["confidence"] == "low"


def test_generate_mvp_default_shape(tmp_path: Path):
    out = tmp_path / "mvp.jsonl"
    main(["generate-mvp", "--output", str(out), "--nouns", "2", "--verbs", "2"])
    rows = [json.loads(line) for line in out.read_text(encoding="utf-8").splitlines()]
    assert len(rows) == 6
    assert sum(1 for row in rows if row["pos"] == "noun") == 2
    assert sum(1 for row in rows if row["pos"] == "verb") == 4
    assert all("domainFreqRank" in row for row in rows)


def test_validate_notes_accepts_structural_mvp(tmp_path: Path, capsys):
    out = tmp_path / "mvp.jsonl"
    main(["generate-mvp", "--output", str(out), "--nouns", "2", "--verbs", "4"])

    main(["validate-notes", "--input", str(out), "--min-nouns", "2", "--min-verbs", "4"])

    report = json.loads(capsys.readouterr().out)
    assert report["meetsDesignDocMinimum"] is True
    assert report["readyNominalRows"] == 2
    assert report["aspectReadyVerbRows"] == 8


def test_validate_notes_fails_when_verified_aktionsart_is_required(tmp_path: Path):
    out = tmp_path / "mvp.jsonl"
    main(["generate-mvp", "--output", str(out), "--nouns", "1", "--verbs", "2"])

    with pytest.raises(SystemExit):
        main([
            "validate-notes",
            "--input",
            str(out),
            "--min-nouns",
            "1",
            "--min-verbs",
            "2",
            "--require-verified-aktionsart",
        ])


def test_bootstrap_examples_do_not_use_placeholder_templates():
    rows = noun_rows() + adjective_rows(start_rank=1000) + verb_rows(start_rank=2000)
    examples = [row["exampleSentence"] for row in rows]

    assert not [
        example
        for example in examples
        if any(marker in example for marker in BAD_EXAMPLE_MARKERS)
    ]
    assert len(set(examples)) > len(examples) * 0.55
