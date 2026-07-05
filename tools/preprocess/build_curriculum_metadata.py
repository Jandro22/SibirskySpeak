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

def all_rows():
    return a1_rows() + a2_rows() + b1_rows() + b2_rows() + c1_rows() + c2_rows() + spine2_rows()

def band_for(unit, rows):
    bands = [r.get("cefrLevel") for r in rows if r.get("unit") == unit and r.get("cefrLevel")]
    return bands[0] if bands else ("A1" if unit <= 11 else "A2" if unit <= 20 else "B1" if unit <= 49 else "B2" if unit <= 128 else "C1" if unit <= 220 else "C2")

def completeness():
    db = sqlite3.connect(ASSETS / "tatoeba.db")
    try:
        total = dict(db.execute("SELECT band, COUNT(*) FROM sentence_bank GROUP BY band"))
        all_sentences = db.execute("SELECT COUNT(*) FROM sentence").fetchone()[0]
    except sqlite3.OperationalError:
        total, all_sentences = {}, 0
    finally:
        db.close()
    return {band: {"parseableSentences": count, "corpusSentences": all_sentences,
                   "percent": round(100.0 * count / all_sentences, 2) if all_sentences else 0.0}
            for band, count in sorted(total.items())}

def main():
    rows = all_rows()
    units = []
    for unit in range(1, 263):
        concepts = sorted({r["conceptId"] for r in rows if r.get("unit") == unit and r.get("conceptId")})
        band = band_for(unit, rows)
        can_do = FUNCTIONS.get(unit, f"understand and use unit {unit} {band} vocabulary in connected language")
        units.append({
            "id": f"unit_{unit:03d}", "unit": unit, "band": band, "canDo": can_do,
            "concepts": concepts, "capstone": f"{band.lower()}_{(unit - 1) // 10 + 1}",
            "exitTicket": {"recognition": 1, "production": 1, "listening": 1, "reading": 1,
                           "function": can_do, "evidence": "PRACTICE"},
            "dialogueRef": f"unit_{unit:03d}_dialogue", "readerRef": f"unit_{unit:03d}_reader",
        })
    # JSON is a strict subset of YAML and avoids adding a runtime/build dependency.
    (HERE / "units.yaml").write_text(json.dumps({"schemaVersion": 1, "units": units}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (ASSETS / "units.json").write_text(json.dumps({"units": units}, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    metrics = completeness()
    (ASSETS / "curriculum_completeness.json").write_text(json.dumps(metrics, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    for shipped_asset in ("phonology.json", "transformations.json"):
        (ASSETS / shipped_asset).write_text((HERE / shipped_asset).read_text(encoding="utf-8"), encoding="utf-8")
    print(json.dumps({"units": len(units), "completeness": metrics}, ensure_ascii=False))

if __name__ == "__main__":
    main()
