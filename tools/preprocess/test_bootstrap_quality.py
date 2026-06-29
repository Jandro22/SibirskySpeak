from validate_bootstrap_quality import checksum, load_notes, machine_problems, unreviewed


def test_quality_gate_accepts_complete_note_and_checksum_tracks_edits():
    note = {"russian": "до́м", "lemma": "дом", "pos": "noun", "translation": "house",
            "exampleSentence": "Э́то мой до́м.", "exampleTranslation": "This is my house."}
    assert machine_problems([note]) == []
    changed = dict(note, translation="home")
    assert checksum(note) != checksum(changed)


def test_quality_gate_rejects_incomplete_note():
    note = {"russian": "до́м", "lemma": "дом", "pos": "noun", "translation": "house"}
    problems = machine_problems([note])
    assert any("missing exampleSentence" in problem for problem in problems)


def test_every_shipped_note_is_complete_and_evidence_verified():
    notes = load_notes()
    assert machine_problems(notes) == []
    assert unreviewed(notes) == []
