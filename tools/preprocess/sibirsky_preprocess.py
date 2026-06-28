#!/usr/bin/env python3
"""Offline preprocessing for SibirskySpeak imports.

The Android app imports JSON Lines. This script builds those files from local
text/lexicon resources and deliberately keeps runtime network work out of the app.
"""

from __future__ import annotations

import argparse
import collections
import html
import json
import re
import sys
from pathlib import Path
from typing import Iterable

TOKEN_RE = re.compile(r"[А-Яа-яЁё]+")


def load_morph():
    # Prefer pymorphy3 (maintained, works on modern Python); fall back to pymorphy2.
    try:
        import pymorphy3  # type: ignore
        return pymorphy3.MorphAnalyzer()
    except Exception:
        pass
    try:
        import pymorphy2  # type: ignore
        return pymorphy2.MorphAnalyzer()
    except Exception:
        return None


def normalize(text: str) -> str:
    return text.lower().replace("ё", "е").replace("\u0301", "")


def iter_texts(path: Path) -> Iterable[str]:
    if path.is_file():
        yield path.read_text(encoding="utf-8")
        return
    for item in sorted(path.rglob("*.txt")):
        yield item.read_text(encoding="utf-8")


def lemmatize(token: str, morph) -> tuple[str, str | None]:
    if morph is None:
        return normalize(token), None
    parsed = morph.parse(token)[0]
    return normalize(parsed.normal_form), str(parsed.tag.POS) if parsed.tag.POS else None


def rank_domain(args: argparse.Namespace) -> None:
    morph = load_morph()
    counts: collections.Counter[str] = collections.Counter()
    pos_by_lemma: dict[str, str] = {}
    for text in iter_texts(Path(args.input)):
        for match in TOKEN_RE.finditer(text):
            lemma, pos = lemmatize(match.group(0), morph)
            counts[lemma] += 1
            if pos and lemma not in pos_by_lemma:
                pos_by_lemma[lemma] = pos

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("rank\tlemma\tcount\tpos\n")
        for rank, (lemma, count) in enumerate(counts.most_common(), start=1):
            fh.write(f"{rank}\t{lemma}\t{count}\t{pos_by_lemma.get(lemma, '')}\n")


def load_frequency(path: Path) -> dict[str, int]:
    ranks: dict[str, int] = {}
    if not path.exists():
        return ranks
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("rank\t"):
            continue
        rank, lemma, *_ = line.split("\t")
        ranks[normalize(lemma)] = int(rank)
    return ranks


def build_notes(args: argparse.Namespace) -> None:
    domain = load_frequency(Path(args.domain_frequency))
    general = load_frequency(Path(args.general_frequency)) if args.general_frequency else {}
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    with Path(args.lexicon).open("r", encoding="utf-8") as src, out.open("w", encoding="utf-8", newline="\n") as dst:
        for line in src:
            if not line.strip():
                continue
            note = json.loads(line)
            lemma = normalize(note["lemma"])
            note.setdefault("russian", note["lemma"])
            note.setdefault("translation", "translation pending")
            note.setdefault("pos", note.get("partOfSpeech", "unknown"))
            note["domainFreqRank"] = note.get("domainFreqRank") or domain.get(lemma)
            note["generalFreqRank"] = note.get("generalFreqRank") or general.get(lemma)
            dst.write(json.dumps(note, ensure_ascii=False, separators=(",", ":")) + "\n")


AKTIONSART_RUBRIC = {
    "state": "static condition, no inherent endpoint",
    "activity": "dynamic ongoing process, no inherent endpoint",
    "accomplishment": "process with a natural endpoint or created result",
    "achievement": "near-instantaneous change of state",
}


def draft_aktionsart(args: argparse.Namespace) -> None:
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    verbs = [line.strip() for line in Path(args.verbs).read_text(encoding="utf-8").splitlines() if line.strip()]
    with out.open("w", encoding="utf-8", newline="\n") as fh:
        for verb in verbs:
            # Conservative draft only. Human verification is required by design.
            guess = "accomplishment" if normalize(verb).startswith(("на", "раз", "с", "по")) else "activity"
            fh.write(json.dumps({
                "lemma": verb,
                "aktionsart": guess,
                "confidence": "low",
                "justification": f"Draft heuristic; verify against rubric: {AKTIONSART_RUBRIC[guess]}",
                "verified": False,
            }, ensure_ascii=False) + "\n")


def generate_mvp(args: argparse.Namespace) -> None:
    """Generate a structurally complete MVP import set.

    This is a scaffold for app/system testing. Replace or enrich it with
    Wiktionary/Tatoeba-derived rows before treating the data as learning content.
    """
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    for i in range(1, args.nouns + 1):
        lemma = f"термин{i}"
        rows.append({
            "russian": lemma,
            "lemma": lemma,
            "pos": "noun",
            "translation": f"domain term {i}",
            "gender": "M",
            "declensionJson": {
                "NOM_SG": lemma,
                "GEN_SG": f"{lemma}а",
                "DAT_SG": f"{lemma}у",
                "ACC_SG": lemma,
                "INS_SG": f"{lemma}ом",
                "PREP_SG": f"{lemma}е",
            },
            "domainFreqRank": i,
            "generalFreqRank": 5000 + i,
            "exampleSentence": f"{lemma} упоминается в документе.",
            "exampleTranslation": f"Domain term {i} is mentioned in the document.",
        })
    for i in range(1, args.verbs + 1):
        ipf = f"анализировать{i}"
        pf = f"проанализировать{i}"
        rows.append({
            "russian": ipf,
            "lemma": ipf,
            "pos": "verb",
            "translation": f"to analyze {i}",
            "aspect": "IPF",
            "aspectPartner": pf,
            "aktionsart": "activity",
            "aktionsartConfidence": "low",
            "domainFreqRank": args.nouns + i,
            "exampleSentence": f"Совет {ipf} ситуацию.",
            "exampleTranslation": f"The council was analyzing situation {i}.",
        })
        rows.append({
            "russian": pf,
            "lemma": pf,
            "pos": "verb",
            "translation": f"to complete analyzing {i}",
            "aspect": "PF",
            "aspectPartner": ipf,
            "aktionsart": "accomplishment",
            "aktionsartConfidence": "low",
            "domainFreqRank": args.nouns + args.verbs + i,
            "exampleSentence": f"Совет {pf} ситуацию.",
            "exampleTranslation": f"The council analyzed situation {i}.",
        })
    with out.open("w", encoding="utf-8", newline="\n") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def validate_notes(args: argparse.Namespace) -> None:
    rows = read_jsonl(Path(args.input))
    report = build_validation_report(
        rows,
        min_nouns=args.min_nouns,
        min_verbs=args.min_verbs,
        require_verified_aktionsart=args.require_verified_aktionsart,
    )
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    if report["errors"]:
        raise SystemExit(1)


def read_jsonl(path: Path) -> list[dict]:
    rows = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"{path}:{line_number}: invalid JSON: {exc}") from exc
        if not isinstance(row, dict):
            raise SystemExit(f"{path}:{line_number}: expected a JSON object")
        row["_line"] = line_number
        rows.append(row)
    return rows


def build_validation_report(
    rows: list[dict],
    min_nouns: int,
    min_verbs: int,
    require_verified_aktionsart: bool,
) -> dict:
    errors: list[str] = []
    warnings: list[str] = []
    lemmas = {normalize(str(row.get("lemma", ""))) for row in rows if row.get("lemma")}
    noun_rows = [row for row in rows if pos_of(row) in {"noun", "adjective"}]
    verb_rows = [row for row in rows if pos_of(row) == "verb"]

    ready_nominals = [row for row in noun_rows if has_nominal_readiness(row)]
    aspect_verbs = [row for row in verb_rows if has_aspect_readiness(row)]
    verified_aktionsart = [row for row in aspect_verbs if has_verified_aktionsart(row)]
    ranked_rows = [row for row in rows if row.get("domainFreqRank") is not None]
    examples = [row for row in rows if str(row.get("exampleSentence", "")).strip()]

    if len(ready_nominals) < min_nouns:
        errors.append(f"ready noun/adjective rows {len(ready_nominals)} below required {min_nouns}")
    if len(aspect_verbs) < min_verbs:
        errors.append(f"aspect-ready verb rows {len(aspect_verbs)} below required {min_verbs}")
    if require_verified_aktionsart and len(verified_aktionsart) < min_verbs:
        errors.append(f"verified Aktionsart verb rows {len(verified_aktionsart)} below required {min_verbs}")

    for row in rows:
        missing = missing_fields(row)
        if missing:
            warnings.append(f"line {row['_line']}: missing {', '.join(missing)}")
        partner = str(row.get("aspectPartner", "")).strip()
        if partner and normalize(partner) not in lemmas:
            errors.append(f"line {row['_line']}: aspectPartner lemma not found: {partner}")

    return {
        "totalRows": len(rows),
        "readyNominalRows": len(ready_nominals),
        "aspectReadyVerbRows": len(aspect_verbs),
        "verifiedAktionsartVerbRows": len(verified_aktionsart),
        "domainRankedRows": len(ranked_rows),
        "exampleRows": len(examples),
        "minNouns": min_nouns,
        "minVerbs": min_verbs,
        "requireVerifiedAktionsart": require_verified_aktionsart,
        "meetsDesignDocMinimum": not errors,
        "warnings": warnings[:50],
        "errors": errors,
    }


def pos_of(row: dict) -> str:
    return str(row.get("pos") or row.get("partOfSpeech") or "").lower()


def has_nominal_readiness(row: dict) -> bool:
    return all([
        pos_of(row) in {"noun", "adjective"},
        bool(row.get("declensionJson")),
        bool(row.get("gender")),
        row.get("domainFreqRank") is not None,
        bool(str(row.get("exampleSentence", "")).strip()),
    ])


def has_aspect_readiness(row: dict) -> bool:
    return all([
        pos_of(row) == "verb",
        bool(str(row.get("aspect", "")).strip()),
        bool(str(row.get("aspectPartner", "")).strip()),
        bool(str(row.get("aktionsart", "")).strip()),
        row.get("domainFreqRank") is not None,
        bool(str(row.get("exampleSentence", "")).strip()),
    ])


def has_verified_aktionsart(row: dict) -> bool:
    confidence = str(row.get("aktionsartConfidence") or row.get("confidence") or "").lower()
    return bool(row.get("verified")) or confidence in {"verified", "manual", "high"}


def missing_fields(row: dict) -> list[str]:
    required = ["russian", "lemma", "translation", "domainFreqRank", "exampleSentence"]
    if pos_of(row) in {"noun", "adjective"}:
        required += ["declensionJson", "gender"]
    if pos_of(row) == "verb":
        required += ["aspect", "aspectPartner", "aktionsart", "aktionsartConfidence"]
    return [field for field in required if row.get(field) in (None, "")]


def scrape(args: argparse.Namespace) -> None:
    import requests
    from bs4 import BeautifulSoup

    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    for index, url in enumerate(args.urls, start=1):
        response = requests.get(url, timeout=30, headers={"User-Agent": "SibirskySpeak research preprocessing"})
        response.raise_for_status()
        soup = BeautifulSoup(response.text, "html.parser")
        text = html.unescape(soup.get_text("\n"))
        clean = "\n".join(line.strip() for line in text.splitlines() if line.strip())
        (out / f"source_{index:04d}.txt").write_text(clean, encoding="utf-8")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(required=True)

    p = sub.add_parser("rank-domain")
    p.add_argument("--input", required=True)
    p.add_argument("--output", required=True)
    p.set_defaults(func=rank_domain)

    p = sub.add_parser("build-notes")
    p.add_argument("--lexicon", required=True)
    p.add_argument("--domain-frequency", required=True)
    p.add_argument("--general-frequency")
    p.add_argument("--output", required=True)
    p.set_defaults(func=build_notes)

    p = sub.add_parser("draft-aktionsart")
    p.add_argument("--verbs", required=True)
    p.add_argument("--output", required=True)
    p.set_defaults(func=draft_aktionsart)

    p = sub.add_parser("scrape")
    p.add_argument("--output", required=True)
    p.add_argument("urls", nargs="+")
    p.set_defaults(func=scrape)

    p = sub.add_parser("generate-mvp")
    p.add_argument("--output", required=True)
    p.add_argument("--nouns", type=int, default=200)
    p.add_argument("--verbs", type=int, default=100)
    p.set_defaults(func=generate_mvp)

    p = sub.add_parser("validate-notes")
    p.add_argument("--input", required=True)
    p.add_argument("--min-nouns", type=int, default=200)
    p.add_argument("--min-verbs", type=int, default=100)
    p.add_argument("--require-verified-aktionsart", action="store_true")
    p.set_defaults(func=validate_notes)

    args = parser.parse_args(argv)
    args.func(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
