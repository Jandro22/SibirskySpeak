"""Cross-check the shipped lexicon and collect cited bilingual examples.

Reference data comes from the English Wiktionary extraction by Kaikki. Missing
examples are queried from Tatoeba and frozen in ``lexical_examples.json`` so app
builds remain deterministic and offline. This tool is intentionally fail-closed.
"""
from __future__ import annotations

import argparse
import gzip
import json
import re
import urllib.parse
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from validate_bootstrap_quality import NOTES, checksum, load_notes

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DEFAULT_DUMP = ROOT / ".cache/lexical/kaikki-russian.jsonl.gz"
DEFAULT_TATOEBA_RU = ROOT / ".cache/lexical/tatoeba/Tatoeba.en-ru.ru"
DEFAULT_TATOEBA_EN = ROOT / ".cache/lexical/tatoeba/Tatoeba.en-ru.en"
EXAMPLES = HERE / "lexical_examples.json"
EVIDENCE = HERE / "bootstrap_verified.json"
VERIFIED_IDENTITIES = HERE / "lexical_verified_identities.json"
WORD = re.compile(r"[a-z]+")
STOP = {"a", "an", "the", "to", "of", "be", "is", "one", "someone", "something", "attr"}
POS = {
    "adjective": "adj", "adj": "adj", "noun": "noun", "verb": "verb",
    "adverb": "adv", "conjunction": "conj", "preposition": "prep",
    "numeral": "num", "particle": "particle", "interjection": "intj",
    "pronoun": "pron", "predicative": "predicative",
}


def norm_ru(value: str) -> str:
    return value.lower().replace("\u0301", "").replace("\u0300", "").replace("ё", "е").strip()


def lookup_key(note: dict) -> str:
    lemma = str(note.get("lemma", ""))
    if lemma.startswith("tb_"):
        lemma = lemma[3:]
    if not lemma or " " in lemma or "(" in lemma:
        lemma = str(note.get("russian", lemma)).split("/")[0]
    return norm_ru(lemma)


def english_words(value: str) -> set[str]:
    return {w for w in WORD.findall(value.lower()) if w not in STOP and len(w) > 1}


def identity(note: dict) -> str:
    return json.dumps(
        [norm_ru(str(note.get("lemma", ""))), str(note.get("pos", "")), str(note.get("translation", "")).strip().lower()],
        ensure_ascii=False, separators=(",", ":"),
    )


def load_reference(path: Path, keys: set[str]) -> dict[str, list[dict]]:
    found: dict[str, list[dict]] = defaultdict(list)
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        for line in stream:
            entry = json.loads(line)
            key = norm_ru(entry.get("word", ""))
            if key in keys:
                found[key].append(entry)
    return found


def compatible_pos(note_pos: str, entries: list[dict]) -> bool:
    expected = POS.get(note_pos, note_pos)
    if expected in {"word", "other"}:
        return bool(entries)
    return any(POS.get(e.get("pos", ""), e.get("pos")) == expected for e in entries)


def reference_glosses(entries: list[dict]) -> list[str]:
    return [g for e in entries for sense in e.get("senses", []) for g in sense.get("glosses", [])]


def best_wiktionary_example(entries: list[dict]) -> dict | None:
    candidates = []
    for entry in entries:
        for sense in entry.get("senses", []):
            for example in sense.get("examples", []):
                ru = example.get("text", "").strip()
                en = (example.get("english") or example.get("translation") or "").strip()
                if len(ru) >= 12 and len(en) >= 8:
                    candidates.append((example.get("type") != "quotation", len(ru), ru, en, example.get("ref", "")))
    if not candidates:
        return None
    _, _, ru, en, ref = sorted(candidates)[0]
    return {"ru": ru, "en": en, "source": "English Wiktionary via Kaikki", "reference": ref}


def corpus_examples(notes: list[dict], reference: dict[str, list[dict]], ru_path: Path, en_path: Path) -> dict[str, dict]:
    """Pick the shortest aligned Tatoeba sentence containing any attested form."""
    forms_to_keys: dict[str, set[str]] = defaultdict(set)
    for note in notes:
        key = lookup_key(note)
        forms = {key}
        for entry in reference.get(key, []):
            forms.update(norm_ru(f.get("form", "")) for f in entry.get("forms", []) if f.get("form"))
        for form in forms:
            if len(form) >= 2 and " " not in form:
                forms_to_keys[form].add(key)
    result = {}
    token_re = re.compile(r"[А-Яа-яЁё-]+")
    with ru_path.open(encoding="utf-8") as ru_stream, en_path.open(encoding="utf-8") as en_stream:
        for index, (ru, en) in enumerate(zip(ru_stream, en_stream), 1):
            ru, en = ru.strip(), en.strip()
            tokens = {norm_ru(t) for t in token_re.findall(ru)}
            for token in tokens.intersection(forms_to_keys):
                for key in forms_to_keys[token]:
                    current = result.get(key)
                    if current is None or len(ru) < len(current["ru"]):
                        result[key] = {
                            "ru": ru, "en": en, "source": "Tatoeba via OPUS",
                            "reference": f"OPUS Tatoeba v2023-04-12 aligned row {index}",
                        }
    return result


def tatoeba_example(query: str) -> dict | None:
    params = urllib.parse.urlencode({"lang": "rus", "q": query, "trans:lang": "eng", "sort": "relevance", "limit": 10})
    request = urllib.request.Request(f"https://api.tatoeba.org/v1/sentences?{params}", headers={"User-Agent": "SibirskySpeak lexical audit"})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = json.load(response)
    candidates = []
    for sentence in data.get("data", []):
        if sentence.get("is_unapproved"):
            continue
        for translation in sentence.get("translations", []):
            if translation.get("lang") == "eng" and translation.get("is_direct") and not translation.get("is_unapproved"):
                candidates.append((len(sentence["text"]), sentence, translation))
    if not candidates:
        return None
    _, sentence, translation = sorted(candidates, key=lambda item: item[0])[0]
    return {
        "ru": sentence["text"], "en": translation["text"], "source": "Tatoeba",
        "reference": f"https://tatoeba.org/en/sentences/show/{sentence['id']}",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dump", type=Path, default=DEFAULT_DUMP)
    parser.add_argument("--tatoeba-ru", type=Path, default=DEFAULT_TATOEBA_RU)
    parser.add_argument("--tatoeba-en", type=Path, default=DEFAULT_TATOEBA_EN)
    parser.add_argument("--fetch-examples", action="store_true")
    args = parser.parse_args()
    notes = load_notes()
    keys = {lookup_key(n) for n in notes if n.get("pos") != "lesson"}
    reference = load_reference(args.dump, keys)
    prior_examples = json.loads(EXAMPLES.read_text(encoding="utf-8")) if EXAMPLES.exists() else {}
    missing = [n for n in notes if n.get("pos") != "lesson" and (not n.get("exampleSentence") or not n.get("exampleTranslation"))]

    examples = dict(prior_examples)
    for note in missing:
        key = lookup_key(note)
        if not examples.get(key):
            examples[key] = best_wiktionary_example(reference.get(key, []))
    corpus = corpus_examples(missing, reference, args.tatoeba_ru, args.tatoeba_en)
    for key, example in corpus.items():
        if not examples.get(key):
            examples[key] = example
    if args.fetch_examples:
        todo = {lookup_key(n) for n in missing if not examples.get(lookup_key(n))}
        with ThreadPoolExecutor(max_workers=8) as pool:
            futures = {pool.submit(tatoeba_example, key): key for key in todo}
            for future in as_completed(futures):
                key = futures[future]
                try:
                    result = future.result()
                except Exception as error:
                    print(f"Tatoeba error for {key}: {error}")
                    continue
                if result:
                    examples[key] = result
    examples = {key: value for key, value in sorted(examples.items()) if value}
    EXAMPLES.write_text(json.dumps(examples, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    verified = {}
    errors = []
    for note in notes:
        if note.get("pos") == "lesson":
            verified[checksum(note)] = {"method": "authored grammar lesson", "source": "in-repository curriculum tests"}
            continue
        key = lookup_key(note)
        entries = reference.get(key, [])
        if not entries:
            errors.append(f"{key}: absent from English Wiktionary")
            continue
        if not compatible_pos(str(note.get("pos", "")), entries):
            errors.append(f"{key}: POS {note.get('pos')} conflicts with Wiktionary")
            continue
        own = english_words(str(note.get("translation", "")))
        glosses = reference_glosses(entries)
        reference_words = set().union(*(english_words(g) for g in glosses)) if glosses else set()
        if own and reference_words and not own.intersection(reference_words):
            errors.append(f"{key}: gloss {note.get('translation')!r} has no overlap with Wiktionary")
            continue
        verified[checksum(note)] = {
            "method": "automated lexical cross-check", "source": "English Wiktionary via Kaikki",
            "lookup": key, "pos": note.get("pos"), "matched_glosses": glosses[:3],
        }
    EVIDENCE.write_text(json.dumps({"schema": 2, "approved": verified}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    approved_hashes = set(verified)
    identities = sorted({identity(n) for n in notes if checksum(n) in approved_hashes})
    VERIFIED_IDENTITIES.write_text(json.dumps(identities, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for error in errors[:200]:
        print(f"ERROR {error}")
    no_example = [lookup_key(n) for n in missing if lookup_key(n) not in examples]
    print(f"Verified {len(verified)}/{len(notes)} records; {len(errors)} conflicts; examples {len(missing) - len(no_example)}/{len(missing)}")
    if no_example:
        print("NO EXAMPLE: " + ", ".join(no_example))
    return 1 if errors or no_example else 0


if __name__ == "__main__":
    raise SystemExit(main())
