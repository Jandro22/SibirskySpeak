"""Exhaustive quality gate for the vocabulary that actually ships in the APK.

Machine checks catch incomplete/corrupt records. Lexical corroboration is deliberately
separate: ``--require-reviewed`` only passes when every exact JSON record has cited
evidence in ``bootstrap_verified.json``. Changing a record invalidates that evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NOTES = ROOT / "app/src/main/assets/bootstrap_notes.jsonl"
LEDGER = Path(__file__).with_name("bootstrap_verified.json")
CYRILLIC = re.compile(r"[А-Яа-яЁё]")
MOJIBAKE = ("Ð", "Ñ", "Ã", "â€")
PLACEHOLDERS = ("todo", "tbd", "translation missing", "{t}", "{inf}")


def checksum(note: dict) -> str:
    canonical = json.dumps(note, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def load_notes() -> list[dict]:
    return [json.loads(line) for line in NOTES.read_text(encoding="utf-8").splitlines() if line.strip()]


def machine_problems(notes: list[dict]) -> list[str]:
    problems: list[str] = []
    identities: set[tuple[str, str, str]] = set()
    lemmas: set[str] = set()
    for line, note in enumerate(notes, 1):
        label = note.get("lemma") or note.get("russian") or f"line {line}"
        required = ("russian", "lemma", "pos", "translation")
        missing = [key for key in required if not str(note.get(key, "")).strip()]
        if missing:
            problems.append(f"{label}: missing {', '.join(missing)}")
        if note.get("pos") != "lesson":
            if not CYRILLIC.search(str(note.get("russian", ""))):
                problems.append(f"{label}: citation has no Cyrillic")
            for key in ("exampleSentence", "exampleTranslation"):
                if not str(note.get(key, "")).strip():
                    problems.append(f"{label}: missing {key}")
        searchable = " ".join(str(v) for v in note.values() if isinstance(v, (str, int)))
        lowered = searchable.lower()
        if any(marker in searchable for marker in MOJIBAKE):
            problems.append(f"{label}: mojibake detected")
        if any(marker in lowered for marker in PLACEHOLDERS):
            problems.append(f"{label}: placeholder text detected")
        identity = (str(note.get("lemma", "")).lower(), str(note.get("pos", "")), str(note.get("translation", "")).lower())
        if identity in identities:
            problems.append(f"{label}: duplicate lemma/POS/meaning")
        identities.add(identity)
        lemma_key = str(note.get("lemma", "")).lower().replace("ё", "е")
        if note.get("pos") != "lesson" and lemma_key in lemmas:
            problems.append(f"{label}: duplicate lemma")
        if note.get("pos") != "lesson":
            lemmas.add(lemma_key)
    return problems


def unreviewed(notes: list[dict]) -> list[str]:
    ledger = json.loads(LEDGER.read_text(encoding="utf-8")) if LEDGER.exists() else {"approved": {}}
    approved = ledger.get("approved", {})
    return [f"{n.get('lemma', n.get('russian', '?'))}: {checksum(n)}" for n in notes if checksum(n) not in approved]


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-reviewed", action="store_true")
    args = parser.parse_args()
    notes = load_notes()
    problems = machine_problems(notes)
    pending = unreviewed(notes) if args.require_reviewed else []
    for problem in problems[:100]:
        print(f"ERROR {problem}")
    for item in pending[:100]:
        print(f"UNREVIEWED {item}")
    print(f"Audited {len(notes)} shipped notes: {len(problems)} machine errors, {len(pending)} unreviewed")
    return 1 if problems or pending else 0


if __name__ == "__main__":
    raise SystemExit(main())
