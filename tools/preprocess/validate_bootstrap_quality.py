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
# Cyrillic Supplement letters (Macedonian/Serbian/Ukrainian-only, U+0400-U+045F
# minus the legitimate "Ё"/"ё") never appear in Russian text — their presence
# is a reliable signature of a broken CP1251 decode somewhere upstream.
MOJIBAKE_CYRILLIC = re.compile("[ѐђ-џЀЂ-Џ]")
PLACEHOLDERS = ("todo", "tbd", "translation missing", "{t}", "{inf}")
FOREIGN_GLOSS_MARKERS = (
    "debido a", "situación desfavorable", "không màng đến", "chuẩn đoán",
    "tỷ trọng", "mật độ", "đăng ký", "khinh suất",
)


def _studyable_example(ru: str, en: str) -> bool:
    """Reject corruption that would make any shipped card teach bad text."""
    if not ru.strip() or not en.strip():
        return False
    if not any("\u0400" <= ch <= "\u04FF" for ch in ru):
        return False
    if not any("A" <= ch <= "Z" or "a" <= ch <= "z" for ch in en):
        return False
    if ru.count('"') % 2 or en.count('"') % 2:
        return False
    if any(ord(ch) == 0xFFFD or 0x80 <= ord(ch) <= 0x9F for ch in ru + en):
        return False
    if re.search(r"[\u0400-\u04FF]\u20AC[\u0400-\u04FF]", ru):
        return False
    token_re = re.compile(r"[A-Za-z\u0400-\u04FF]+(?:[-'][A-Za-z\u0400-\u04FF]+)*")
    lower_ascii_words = 0
    for match in token_re.finditer(ru):
        token = match.group(0)
        latin = any("A" <= ch <= "Z" or "a" <= ch <= "z" for ch in token)
        letters = "".join(ch for ch in token if ch.isascii() and ch.isalpha())
        in_quote = ru[:match.start()].count('"') % 2 == 1
        if latin and len(letters) >= 3 and not token[0].isupper() and not token.isupper() and not in_quote:
            lower_ascii_words += 1
    if lower_ascii_words >= 3:
        return False
    return True


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
        for suffix in ("", "2", "3"):
            if note.get("pos") == "lesson":
                continue
            ru = str(note.get(f"exampleSentence{suffix}", ""))
            en = str(note.get(f"exampleTranslation{suffix}", ""))
            if bool(ru.strip()) != bool(en.strip()):
                problems.append(f"{label}: incomplete example pair {suffix or '1'}")
            elif ru.strip() and not _studyable_example(ru, en):
                problems.append(f"{label}: corrupted or mixed-language example {suffix or '1'}")
        second_ru = str(note.get("secondSenseExample", ""))
        second_en = str(note.get("secondSenseExampleTranslation", ""))
        if bool(second_ru.strip()) != bool(second_en.strip()):
            problems.append(f"{label}: incomplete second-sense example")
        elif second_ru.strip() and not _studyable_example(second_ru, second_en):
            problems.append(f"{label}: corrupted second-sense example")
        searchable = " ".join(str(v) for v in note.values() if isinstance(v, (str, int)))
        lowered = searchable.lower()
        if any(marker in searchable for marker in MOJIBAKE):
            problems.append(f"{label}: mojibake detected")
        if MOJIBAKE_CYRILLIC.search(searchable):
            problems.append(f"{label}: mojibake Cyrillic (Macedonian/Serbian code points) detected")
        if any(marker in lowered for marker in PLACEHOLDERS):
            problems.append(f"{label}: placeholder text detected")
        if any(marker in lowered for marker in FOREIGN_GLOSS_MARKERS):
            problems.append(f"{label}: foreign-language gloss metadata detected")
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
