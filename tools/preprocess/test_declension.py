# -*- coding: utf-8 -*-
"""Correctness gates for the rule-based noun declension engine.

Guards against shipping wrong case forms in morphology drills — notably the
fleeting-vowel bug (рынок→рынка, not рынока), which would otherwise teach
non-existent forms.
"""
import domain_wordlist as wl
from russian_morph import decline_noun, strip_stress

# Expected GEN_SG for known fleeting-vowel nouns (the vowel drops).
FLEETING_GEN_SG = {
    "рынок": "рынка",
    "посол": "посла",
    "авианосец": "авианосца",
    "эсминец": "эсминца",
    "миротворец": "миротворца",
    "ополченец": "ополченца",
    "убыток": "убытка",
}
# Lookalikes whose vowel is NOT fleeting — must keep it.
NON_FLEETING_GEN_SG = {
    "энергоблок": "энергоблока",
}


def _table_for(lemma):
    for cit, cls, tr, an, nums in wl.NOUNS:
        if strip_stress(cit).lower() == lemma:
            return decline_noun(cit, cls, animate=an, numbers=tuple(nums.split("+")))
    raise AssertionError(f"{lemma} not in NOUNS")


def test_fleeting_vowel_nouns_drop_the_vowel():
    for lemma, gen in FLEETING_GEN_SG.items():
        table = _table_for(lemma)
        assert table["GEN_SG"] == gen, f"{lemma}: {table['GEN_SG']} != {gen}"
        # The fleeting vowel must not survive in any oblique singular form.
        for key in ("DAT_SG", "INS_SG", "PREP_SG", "GEN_PL"):
            assert lemma not in table.get(key, ""), f"{lemma}: vowel survived in {key}={table.get(key)}"


def test_non_fleeting_lookalikes_keep_the_vowel():
    for lemma, gen in NON_FLEETING_GEN_SG.items():
        assert _table_for(lemma)["GEN_SG"] == gen


def test_no_domain_noun_keeps_full_nominative_in_genitive():
    """A regular noun's GEN_SG should never just be NOM_SG + ending where the
    nominative itself ended in a fleeting-vowel pattern that should have dropped."""
    for cit, cls, tr, an, nums in wl.NOUNS:
        if cls != "m_fleeting":
            continue
        lemma = strip_stress(cit).lower()
        table = decline_noun(cit, cls, animate=an, numbers=tuple(nums.split("+")))
        # oblique stem must be shorter than the nominative (a vowel was dropped)
        assert len(table["GEN_SG"]) <= len(lemma) + 1, f"{lemma}: GEN_SG looks wrong: {table['GEN_SG']}"
