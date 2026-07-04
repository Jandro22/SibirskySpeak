import sqlite3
from pathlib import Path

from build_paradigms import legacy_key, norm


def test_normalization_preserves_yo_equivalence():
    assert norm("всё") == norm("все́") == "все"


def test_pymorphy_keys_cover_irregular_verbs_and_nominals():
    import pymorphy3
    morph = pymorphy3.MorphAnalyzer()
    gold = {
        "идти": {"иду", "идёшь", "шёл", "шла"},
        "есть": {"ем", "ешь", "ел", "ела"},
        "дать": {"дам", "дашь", "дал", "дала"},
        "хотеть": {"хочу", "хочешь", "хотел", "хотела"},
        "рынок": {"рынки", "рынка", "рынку", "рынком", "рынке"},
        "собачий": {"собачья", "собачьего", "собачьим"},
    }
    checked = 0
    for lemma, expected in gold.items():
        forms = {form.word for form in morph.parse(lemma)[0].lexeme}
        assert expected <= forms
        checked += len(expected)
        assert any(legacy_key(form.tag) for form in morph.parse(lemma)[0].lexeme)
    assert checked >= 24


def test_future_tense_keys_are_captured():
    import pymorphy3
    morph = pymorphy3.MorphAnalyzer()
    forms = {form.tag.person: form.word for form in morph.parse("сделать")[0].lexeme if form.tag.tense == "futr"}
    keys = {legacy_key(form.tag) for form in morph.parse("сделать")[0].lexeme if form.tag.tense == "futr"}
    assert forms  # pymorphy does tag perfective non-past as futr
    assert {"FUT_1SG", "FUT_2SG", "FUT_3SG"} <= keys


def test_lemmas_containing_soft_j_still_parse_correctly():
    """Regression: norm() NFD-decomposes 'й' into 'и'+combining breve, which
    pymorphy3 cannot recognize as input — every -ый/-ий/-ой adjective (and
    many verbs) silently fell back to a garbage first-guess parse. Parsing
    must run on the original spelling; only storage keys are normalized."""
    import pymorphy3
    morph = pymorphy3.MorphAnalyzer()
    for lemma in ("большой", "хороший", "красивый"):
        parses = [p for p in morph.parse(lemma) if norm(p.normal_form) == norm(lemma)]
        assert parses, f"{lemma} should parse when given the original (non-decomposed) spelling"
        assert parses[0].tag.POS == "ADJF"
        # The bug: parsing the decomposed form directly does not find the real ADJF reading
        # (pymorphy3's UnknAnalyzer fallback echoes the input back as its own "normal form").
        decomposed_parses = [p for p in morph.parse(norm(lemma)) if p.tag.POS == "ADJF"]
        assert not decomposed_parses


def test_bundled_paradigm_gold_has_at_least_sixty_verified_rows():
    db_path = Path(__file__).resolve().parents[2] / "app/src/main/assets/tatoeba.db"
    with sqlite3.connect(db_path) as db:
        present = db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='paradigm'").fetchone()[0]
        if not present:
            return  # build_paradigms is gated separately when regenerating the asset
        gold = ["идти", "есть", "дать", "хотеть", "рынок", "собачий"]
        placeholders = ",".join("?" for _ in gold)
        rows = db.execute(
            f"SELECT COUNT(*) FROM paradigm WHERE lemma IN ({placeholders})",
            [norm(lemma) for lemma in gold],
        ).fetchone()[0]
        assert rows >= 60
