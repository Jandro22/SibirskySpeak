import json
import random
import re
import sqlite3
from pathlib import Path

from build_frames import FrameError, load_inventory, norm, realize_frame, render

ROOT = Path(__file__).resolve().parents[2]
FRAMES_PATH = Path(__file__).parent / "frames.json"
DB_PATH = ROOT / "app/src/main/assets/tatoeba.db"


def _valid_concept_ids() -> set[str]:
    text = (ROOT / "app/src/main/java/com/sibirskyspeak/data/GrammarConcepts.kt").read_text(encoding="utf-8")
    return set(re.findall(r'id\s*=\s*"([A-Z_]+)"', text))


def _load():
    frames_doc = json.loads(FRAMES_PATH.read_text(encoding="utf-8"))
    inventory = load_inventory(ROOT / "app/src/main/assets/bootstrap_notes.jsonl")
    db = sqlite3.connect(DB_PATH)
    paradigm = {}
    for lemma, feats, stressed in db.execute("SELECT lemma, feats, stressed FROM paradigm"):
        paradigm.setdefault((lemma, feats), stressed)
    analyses = {}
    for surface, lemma, feats in db.execute("SELECT surface_norm, lemma, feats FROM analysis"):
        analyses.setdefault(surface, []).append((lemma, set(feats.split("+"))))
    db.close()
    return frames_doc, inventory, paradigm, analyses


def test_every_frame_targets_a_real_grammar_concept():
    frames_doc = json.loads(FRAMES_PATH.read_text(encoding="utf-8"))
    valid = _valid_concept_ids()
    assert valid, "could not read GrammarConcept ids from GrammarConcepts.kt"
    for frame in frames_doc["frames"]:
        assert frame["concept"] in valid, f"{frame['id']} references unknown concept {frame['concept']}"


def test_frames_realize_for_twenty_known_inventory_fills():
    frames_doc, inventory, paradigm, _ = _load()
    if not paradigm:
        return  # paradigm table only exists once build_paradigms.py has been run
    for frame in frames_doc["frames"]:
        rng = random.Random(frame["id"])
        ok = 0
        for _ in range(20):
            try:
                forms = realize_frame(frame, inventory, frames_doc["pools"], paradigm, rng)
                ru = render(frame["ruFrame"], forms)
                en = render(frame["enFrame"], forms)
                assert ru.strip() and en.strip()
                assert "{" not in ru and "{" not in en
                ok += 1
            except FrameError:
                pass
        assert ok >= 10, f"frame {frame['id']} realized only {ok}/20 known-inventory fills"


def test_adj_agree_frames_produce_case_number_gender_agreement():
    """Mirrors MorphologyEngine.agreementOk: the realized adjective and noun
    surfaces must share case/number (and gender when singular) per the
    reverse analysis index — not merely "some paradigm row existed"."""
    frames_doc, inventory, paradigm, analyses = _load()
    if not paradigm or not analyses:
        return
    cases = {"NOM", "GEN", "DAT", "ACC", "INS", "PRE"}
    numbers = {"SG", "PL"}
    genders = {"M", "F", "N"}
    checked = 0
    for frame in frames_doc["frames"]:
        adj_slot = next((s for s in frame["slots"] if s["pos"] == "adj"), None)
        if adj_slot is None:
            continue
        noun_role = adj_slot["agreesWith"]
        rng = random.Random(frame["id"] + "_agree")
        for _ in range(20):
            try:
                forms = realize_frame(frame, inventory, frames_doc["pools"], paradigm, rng)
            except FrameError:
                continue
            adj_readings = analyses.get(norm(forms[adj_slot["role"]]), [])
            noun_readings = analyses.get(norm(forms[noun_role]), [])
            adj_feats = {f for _, feats in adj_readings for f in feats}
            noun_feats = {f for _, feats in noun_readings for f in feats}
            assert (adj_feats & cases) & (noun_feats & cases), frame["id"]
            assert (adj_feats & numbers) & (noun_feats & numbers), frame["id"]
            if "PL" not in (adj_feats & numbers):
                assert (adj_feats & genders) & (noun_feats & genders), frame["id"]
            checked += 1
    assert checked >= 20
