#!/usr/bin/env python3
"""Mine short, distinct corpus examples for every shipped lemma."""
import json, re, sqlite3, unicodedata
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# Sentences carry combining-acute stress marks (U+0301) *inside* words (e.g.
# "се́вер"). Without ́ in the class, the regex fragments such a word at
# the mark ("се" + "вер"), and a short fragment can spuriously collide with
# an unrelated lemma's surface form (e.g. "вер" is the genitive plural of
# "вера" — so every "север" sentence got mined as an example of "вера").
WORD = re.compile(r"[А-Яа-яЁё́]+(?:-[А-Яа-яЁё́]+)?")

def norm(s):
    # ё must be folded to е *before* NFD decomposition: NFD splits it into
    # е + U+0308 (combining diaeresis), so a replace("ё","е") that runs
    # afterward never fires -- every ё-containing lemma then silently fails
    # to match its analysis-table surface forms (already е-folded).
    # NFD also splits й into и + a combining breve; recompose back to
    # NFC afterward so results match tatoeba.db's analysis/paradigm tables,
    # which store precomposed text (build_paradigms.norm() does the same).
    value = unicodedata.normalize("NFD", s.lower().replace("ё", "е")).replace("\u0301", "")
    return unicodedata.normalize("NFC", value)

# "отчий" (paternal, a rare bookish adjective) has multiple case forms that
# are homographs of a much more common unrelated word -- GEN_SG "отчего" is
# also the everyday adverb "why", INS_SG "отчим" is also the noun
# "stepfather". Corpus sentences using those surfaces are ~always the other
# word, and mining can't disambiguate word sense from surface form alone, so
# skip automated mining for this lemma entirely; its curated primary example
# (general_source.jsonl) is enough, and apply_phase3_enrichment's
# "Он сказал: «...»" wrapper covers exampleSentence2 safely from that.
# "махать" (to wave) collides with multiple inflected forms of the very
# common name "Маша" (маши=imperative/genitive-of-Маша, машу=1sg/accusative-
# of-Машу, ...) -- excluding one surface at a time chases an open-ended list,
# so skip mining for the whole lemma instead. Its two curated/idiom examples
# plus the "Он сказал: «...»" wrapper are enough.
LEMMA_MINING_EXCLUDE = {norm("отчий"), norm("махать")}

# Same homograph problem, but narrow enough to exclude just one surface form
# instead of the whole lemma: PRE_SG "басе" of "бас" (bass) is spelled
# identically (after ё-folding) to "Басё", the transliteration of the poet
# Matsuo Bashō's name -- so every sentence mentioning him got mined as an
# example of the musical term.
SURFACE_MINING_EXCLUDE = {
    norm("басе"): {norm("бас")},
    # "боёк" (the noun for a hammer/firing-pin striking part) is a homograph
    # of the short-form predicative "бойкий" (lively) -- "он боек" is valid
    # grammar, but corpus sentences using this surface are ~always about the
    # mechanical part, not someone's temperament.
    norm("боек"): {norm("бойкий")},
    # "маши" is the imperative of "махать" (to wave) but is overwhelmingly
    # the genitive of the extremely common name "Маша" in real sentences.
    norm("маши"): {norm("махать")},
    # "суете"/"суёте" is both the dative/prepositional of "суета" (bustle)
    # and 2nd-person-plural present of the unrelated verb "совать" (to
    # stick/poke) -- corpus sentences overwhelmingly use the verb sense.
    norm("суете"): {norm("суета")},
}


def main():
    notes = [json.loads(x) for x in open(ROOT/'app/src/main/assets/bootstrap_notes.jsonl', encoding='utf8')]
    wanted = {norm(n.get('lemma') or n.get('russian','')) for n in notes if n.get('pos') != 'lesson'} - LEMMA_MINING_EXCLUDE
    db = sqlite3.connect(ROOT/'app/src/main/assets/tatoeba.db')
    forms = defaultdict(set)
    for surface, lemma in db.execute("SELECT surface_norm,lemma FROM analysis"):
        surface_n = norm(surface)
        if norm(lemma) in SURFACE_MINING_EXCLUDE.get(surface_n, ()):
            continue
        if lemma in wanted: forms[surface].add(lemma)
    found = defaultdict(list)
    for sid, ru, en, count in db.execute("SELECT id,ru_stressed,en,n_tokens FROM sentence WHERE n_tokens BETWEEN 4 AND 10 ORDER BY rating DESC,id"):
        lemmas = set()
        for word in WORD.findall(ru): lemmas.update(forms.get(norm(word), ()))
        for lemma in lemmas:
            if len(found[lemma]) < 4 and all(norm(row['ru']) != norm(ru) for row in found[lemma]):
                found[lemma].append({'ru':ru, 'en':en, 'sentenceId':sid})
    out = ROOT/'tools/preprocess/mined_examples.json'
    out.write_text(json.dumps(found, ensure_ascii=False, indent=2, sort_keys=True), encoding='utf8')
    print(json.dumps({'lemmas':len(found), 'examples':sum(map(len,found.values()))}))

if __name__ == '__main__': main()
