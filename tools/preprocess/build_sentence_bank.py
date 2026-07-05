#!/usr/bin/env python3
"""Index bundled sentences by first readable curriculum unit and grammar features."""
import json, re, sqlite3, unicodedata
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
WORD=re.compile(r"[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?")
def norm(s): return unicodedata.normalize('NFD',s.lower()).replace('\u0301','').replace('ё','е')

def main():
    notes=[json.loads(x) for x in open(ROOT/'app/src/main/assets/bootstrap_notes.jsonl',encoding='utf8')]
    unit={norm(n.get('lemma') or n.get('russian','')):n.get('unit',9999) for n in notes if n.get('tier')==0 and n.get('unit')}
    db=sqlite3.connect(ROOT/'app/src/main/assets/tatoeba.db')
    analyses={}
    for surface,lemma,feats in db.execute('SELECT surface_norm,lemma,feats FROM analysis'):
        if lemma in unit: analyses.setdefault(surface,[]).append((lemma,feats))
    db.executescript('DROP TABLE IF EXISTS sentence_bank; CREATE TABLE sentence_bank(sent_id INTEGER NOT NULL PRIMARY KEY,unit_min INTEGER NOT NULL,band TEXT NOT NULL,token_count INTEGER NOT NULL,grammar_feats TEXT NOT NULL,source TEXT NOT NULL); CREATE INDEX index_sentence_bank_unit_min ON sentence_bank(unit_min); CREATE INDEX index_sentence_bank_band ON sentence_bank(band);')
    rows=[]
    for sid,ru,count in db.execute('SELECT id,ru_plain,n_tokens FROM sentence'):
        readings=[analyses.get(norm(w),[]) for w in WORD.findall(ru)]
        if not readings or any(not r for r in readings): continue
        unit_min=max(min(unit.get(lemma,9999) for lemma,_ in r) for r in readings)
        if unit_min>=9999: continue
        feats=sorted({f for r in readings for _,fs in r for f in fs.split('+')})
        band='A1' if unit_min<=30 else 'A2' if unit_min<=60 else 'B1' if unit_min<=100 else 'B2+'
        rows.append((sid,unit_min,band,count,json.dumps(feats,separators=(',',':')),'tatoeba'))
    db.executemany('INSERT INTO sentence_bank VALUES(?,?,?,?,?,?)',rows)
    schema=json.loads((ROOT/'app/schemas/com.sibirskyspeak.data.ContentDatabase/6.json').read_text(encoding='utf8'))
    db.execute('INSERT OR REPLACE INTO room_master_table VALUES(42,?)',(schema['database']['identityHash'],)); db.execute('PRAGMA user_version=6'); db.commit(); db.execute('VACUUM'); db.close()
    print(json.dumps({'sentences':len(rows)}))
if __name__=='__main__': main()
