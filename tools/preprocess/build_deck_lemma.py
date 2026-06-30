#!/usr/bin/env python3
"""Build app/src/main/assets/deck_lemma.json: a normalized-surface -> base-lemma map
for the deck, using the SAME pymorphy3 lemmatizer the Tatoeba corpus was indexed with.

The miner needs this because some deck notes (notably the textbook layer) store a
namespaced / inflected pseudo-lemma (e.g. "tb_нашему") that never matches the corpus
base-form index. We resolve the note's real surface (russian) to its base lemma offline
so runtime lookups hit the corpus. Only surfaces whose base lemma actually exists in the
corpus are emitted, so the map never points at a dead key."""
import json, re, sqlite3, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NOTES = ROOT / "app/src/main/assets/bootstrap_notes.jsonl"
CORPUS = ROOT / "app/src/main/assets/tatoeba.db"
OUT = ROOT / "app/src/main/assets/deck_lemma.json"

def norm(s: str) -> str:
    return re.sub("́", "", s).replace("ё", "е").replace("Ё", "Е").lower().strip() if s else ""

def _clean(s: str) -> str:
    # Strip a "tb_"/namespace prefix some legacy layers stored on the lemma.
    return re.sub(r"^[a-z]+_", "", norm(s))

def surfaces_from_jsonl() -> set[str]:
    out: set[str] = set()
    for line in NOTES.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            o = json.loads(line)
        except Exception:
            continue
        for s in (_clean(o.get("russian") or ""), _clean(o.get("lemma") or "")):
            if s and " " not in s:
                out.add(s)
    return out

def surfaces_from_db(path: str) -> set[str]:
    out: set[str] = set()
    for ru, lemma in sqlite3.connect(path).execute("SELECT russian, lemma FROM notes"):
        for s in (_clean(ru or ""), _clean(lemma or "")):
            if s and " " not in s:
                out.add(s)
    return out

def main(argv: list[str]) -> int:
    import pymorphy3
    morph = pymorphy3.MorphAnalyzer()
    corpus_lemmas = set(r[0] for r in sqlite3.connect(CORPUS).execute("SELECT DISTINCT lemma FROM lemma_index"))
    # Canonical, reproducible source is the shipped bootstrap deck. A deck DB snapshot
    # may be unioned in (`--deck-db path`) to also resolve legacy textbook surfaces that
    # predate the current asset, until those notes are cleaned up.
    surfaces = surfaces_from_jsonl()
    for i, a in enumerate(argv):
        if a == "--deck-db" and i + 1 < len(argv):
            surfaces |= surfaces_from_db(argv[i + 1])
    mp: dict[str, str] = {}
    for s in surfaces:
        base = morph.parse(s)[0].normal_form.replace("ё", "е").lower()
        if base in corpus_lemmas:
            mp[s] = base
    OUT.write_text(json.dumps(mp, ensure_ascii=False, separators=(",", ":"), sort_keys=True), encoding="utf-8")
    print(f"surfaces={len(surfaces)}  mapped(in-corpus)={len(mp)}  -> {OUT.relative_to(ROOT)} ({OUT.stat().st_size//1024} KB)")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
