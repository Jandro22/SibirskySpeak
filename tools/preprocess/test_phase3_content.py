import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
def notes(): return [json.loads(x) for x in open(ROOT/'app/src/main/assets/bootstrap_notes.jsonl',encoding='utf8')]

def test_aspect_labels_cover_ninety_percent_of_verbs():
    verbs=[n for n in notes() if n.get('pos','').lower().startswith('verb')]
    assert sum(bool(n.get('aspect')) for n in verbs)/len(verbs) >= .90

def test_a1_a2_mnemonics_are_complete_and_bounded():
    band=[n for n in notes() if n.get('tier')==0 and n.get('cefrLevel') in {'A1','A2'} and n.get('pos')!='lesson']
    assert band and all(n.get('mnemonic') and len(n['mnemonic'])<=120 for n in band)

def test_second_sense_inventory_is_material():
    assert sum(bool(n.get('secondSense')) for n in notes()) >= 250

def test_mined_examples_have_no_within_note_duplicates():
    for n in notes():
        values=[n.get(k,'').strip().lower() for k in ('exampleSentence','exampleSentence2','exampleSentence3') if n.get(k)]
        assert len(values)==len(set(values))

def test_every_tier_zero_note_rotates_context():
    course=[n for n in notes() if n.get('tier')==0 and n.get('pos')!='lesson']
    assert course and all(n.get('exampleSentence2') for n in course)
