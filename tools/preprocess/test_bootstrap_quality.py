from validate_bootstrap_quality import checksum, load_notes, machine_problems, unreviewed
import pymorphy3


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


def test_course_noun_gender_agrees_with_russian_morphology():
    morph = pymorphy3.MorphAnalyzer()
    expected = {"M": "masc", "F": "femn", "N": "neut"}
    mismatches = []
    for note in load_notes():
        if note.get("tier") != 0 or note.get("pos") != "noun" or note.get("gender") not in expected:
            continue
        lemma = note["lemma"].lower().replace("ё", "е")
        genders = {
            parse.tag.gender
            for parse in morph.parse(lemma)
            if "NOUN" in parse.tag and parse.normal_form.replace("ё", "е") == lemma
        }
        if genders and expected[note["gender"]] not in genders:
            mismatches.append((lemma, note["gender"], sorted(str(value) for value in genders)))
    assert mismatches == []
