#!/usr/bin/env python3
"""Mine short, distinct corpus examples for every shipped lemma."""
import json, re, sqlite3, unicodedata
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORD = re.compile(r"[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?")

def norm(s): return unicodedata.normalize("NFD", s.lower()).replace("\u0301", "").replace("ё", "е")

def main():
    notes = [json.loads(x) for x in open(ROOT/'app/src/main/assets/bootstrap_notes.jsonl', encoding='utf8')]
    wanted = {norm(n.get('lemma') or n.get('russian','')) for n in notes if n.get('pos') != 'lesson'}
    db = sqlite3.connect(ROOT/'app/src/main/assets/tatoeba.db')
    forms = defaultdict(set)
    for surface, lemma in db.execute("SELECT surface_norm,lemma FROM analysis"):
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
