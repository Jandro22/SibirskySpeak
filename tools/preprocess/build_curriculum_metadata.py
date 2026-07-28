#!/usr/bin/env python3
"""Build deterministic G4/G6/G10/G11 curriculum metadata assets."""
from __future__ import annotations

import json
import sqlite3
from pathlib import Path

from a1_starter import a1_rows
from a2_starter import a2_rows
from b1_starter import b1_rows
from b2_starter import b2_rows
from c1_starter import c1_rows
from c2_starter import c2_rows
from curriculum_common import spine2_rows

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
ASSETS = ROOT / "app/src/main/assets"

FUNCTIONS = {
    1: "introduce yourself and identify people and things",
    2: "describe people and objects",
    3: "talk about more than one object",
    4: "describe a present routine",
    5: "say what you like, see, eat, or buy",
    6: "say what you have and do not have",
    7: "say where people and things are",
    8: "say who receives or experiences something",
    9: "say who you are with",
    10: "describe a past event",
    11: "contrast an ongoing and completed action",
    12: "describe a future plan",
    13: "ask someone to do something politely",
    14: "describe reflexive and reciprocal activities",
    15: "compare two things",
    16: "say what someone can, must, or needs to do",
    17: "say where and how you are going",
    18: "arrange a practical meeting or journey",
    19: "refer to someone's own possession",
}

FUNCTION_PATTERNS = {
    "A1": ["exchange basic personal information", "describe familiar people and objects", "handle a simple everyday transaction", "understand a short message about daily life"],
    "A2": ["describe routines and recent events", "make and respond to practical plans", "ask for and give everyday information", "follow a short connected account on a familiar topic"],
    "B1": ["narrate an experience with supporting detail", "explain a preference or decision", "handle an unplanned travel or social situation", "understand the main point of connected standard language"],
    "B2": ["develop and support a viewpoint", "reformulate information in a more formal register", "follow detailed argument on a concrete or abstract topic", "interact fluently in a practical or professional situation"],
    "C1": ["express a nuanced position precisely", "adapt register to an academic or professional context", "interpret implicit relationships in extended discourse", "synthesize complex information into a coherent response"],
    "C2": ["convey fine shades of meaning idiomatically", "restructure dense discourse without loss of nuance", "interpret stylistic and pragmatic implications", "produce precise language for a complex unfamiliar situation"],
}

def all_rows():
    return a1_rows() + a2_rows() + b1_rows() + b2_rows() + c1_rows() + c2_rows() + spine2_rows()

def can_do_for(band, unit):
    if band == "A1" and unit in FUNCTIONS:
        return FUNCTIONS[unit]
    patterns = FUNCTION_PATTERNS[band]
    return patterns[unit % len(patterns)]

def completeness():
    db = sqlite3.connect(ASSETS / "tatoeba.db")
    try:
        exact = dict(db.execute("SELECT band, COUNT(*) FROM sentence_bank GROUP BY band"))
        all_sentences = db.execute("SELECT COUNT(*) FROM sentence").fetchone()[0]
    except sqlite3.OperationalError:
        exact, all_sentences = {}, 0
    finally:
        db.close()
    cumulative = 0
    result = {}
    for band in ("A1", "A2", "B1", "B2", "C1", "C2"):
        cumulative += exact.get(band, 0)
        result[band] = {
            "parseableSentences": cumulative,
            "corpusSentences": all_sentences,
            "percent": round(100.0 * cumulative / all_sentences, 2) if all_sentences else 0.0,
        }
    return result

def main():
    rows = all_rows()
    units = []
    keys = sorted(
        {(r["cefrLevel"], int(r["unit"])) for r in rows if r.get("cefrLevel") and r.get("unit") is not None},
        key=lambda item: (("A1", "A2", "B1", "B2", "C1", "C2").index(item[0]), item[1]),
    )
    for band, unit in keys:
        concepts = sorted({r["conceptId"] for r in rows if r.get("unit") == unit and r.get("cefrLevel") == band and r.get("conceptId")})
        can_do = can_do_for(band, unit)
        units.append({
            "id": f"{band.lower()}_unit_{unit:03d}", "unit": unit, "band": band, "canDo": can_do,
            "concepts": concepts, "capstone": f"{band.lower()}_{(unit - 1) // 10 + 1}",
            "exitTicket": {"recognition": 1, "production": 1, "listening": 1, "reading": 1,
                           "function": can_do, "evidence": "PRACTICE"},
            "dialogueRef": f"{band.lower()}_unit_{unit:03d}_dialogue", "readerRef": f"{band.lower()}_unit_{unit:03d}_reader",
        })
    # JSON is a strict subset of YAML and avoids adding a runtime/build dependency.
    (HERE / "units.yaml").write_text(json.dumps({"schemaVersion": 1, "units": units}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (ASSETS / "units.json").write_text(json.dumps({"units": units}, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    from generate_dialogues import generate
    generate(HERE / "units.yaml", ASSETS / "bootstrap_notes.jsonl", HERE / "dialogues.json")
    metrics = completeness()
    (ASSETS / "curriculum_completeness.json").write_text(json.dumps(metrics, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    for shipped_asset in ("phonology.json", "transformations.json"):
        (ASSETS / shipped_asset).write_text((HERE / shipped_asset).read_text(encoding="utf-8"), encoding="utf-8")
    print(json.dumps({"units": len(units), "completeness": metrics}, ensure_ascii=False))

if __name__ == "__main__":
    main()
