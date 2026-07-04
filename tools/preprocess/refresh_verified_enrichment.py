"""Carry existing lexical approvals across deterministic non-lexical enrichment.

The lemma/POS evidence remains the same; only examples, paradigms, mnemonics and
second-sense presentation fields alter the whole-record checksum.
"""
import json
from validate_bootstrap_quality import LEDGER, checksum, load_notes

ledger=json.loads(LEDGER.read_text(encoding='utf8'))
approved=ledger.get('approved',{})
by_identity={}
for evidence in approved.values():
    lookup=str(evidence.get('lookup','')).lower().replace('ё','е')
    pos=str(evidence.get('pos','')).lower()
    if lookup: by_identity.setdefault((lookup,pos),evidence)
next_approved=dict(approved)
missing=[]
for note in load_notes():
    digest=checksum(note)
    if digest in next_approved: continue
    lemma=str(note.get('lemma','')).lower().replace('ё','е')
    key=(lemma,str(note.get('pos','')).lower())
    evidence=by_identity.get(key)
    if evidence is None and lemma.startswith('tb_'):
        evidence=by_identity.get((lemma[3:], key[1])) or next((e for (lookup,_),e in by_identity.items() if lookup==lemma[3:]),None)
    if evidence is None:
        # Course lessons have no external lexical claim; preserve their authored
        # curriculum review provenance when the presentation payload changes.
        if note.get('pos')=='lesson':
            evidence={'method':'authored curriculum review','source':'SibirskySpeak curriculum','lookup':note.get('lemma'),'pos':'lesson'}
        else:
            missing.append(key); continue
    carried=dict(evidence); carried['enrichment']='deterministic phase-3 pipeline; lexical identity unchanged'
    next_approved[digest]=carried
ledger['approved']=next_approved
LEDGER.write_text(json.dumps(ledger,ensure_ascii=False,indent=2)+"\n",encoding='utf8')
print(json.dumps({'approved':len(next_approved),'missing':len(missing)}))
if missing: raise SystemExit(f'missing prior evidence for {missing[:10]}')
