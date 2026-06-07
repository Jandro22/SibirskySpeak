# -*- coding: utf-8 -*-
"""Coverage checks for authored present/future verb-form tables."""

import domain_wordlist as wl
from present_verb_forms import FORM_KEYS, present_forms_for
from russian_morph import strip_stress


def _single_word_domain_verbs():
    lemmas = []
    for entry in wl.VERB_PAIRS + wl.MOTION_VERBS:
        for citation in entry[:2]:
            lemma = strip_stress(citation).lower() if citation else ""
            if lemma and " " not in lemma:
                lemmas.append(lemma)
    return list(dict.fromkeys(lemmas))


def test_every_curated_domain_verb_has_six_verified_present_forms():
    missing = []
    incomplete = []
    for lemma in _single_word_domain_verbs():
        forms = present_forms_for(lemma)
        if forms is None:
            missing.append(lemma)
        elif set(forms) != set(FORM_KEYS) or any(not forms[key].strip() for key in FORM_KEYS):
            incomplete.append(lemma)

    assert missing == []
    assert incomplete == []
