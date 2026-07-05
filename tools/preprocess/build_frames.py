#!/usr/bin/env python3
"""Load frames.json (curated clause templates, P4.1) into the frame table of
tatoeba.db, after verifying every frame actually realizes for a sample of
known-inventory fillers.

This is the build-time twin of app/.../generation/FrameRealizer.kt: both walk
the same slot schema (case/number/tense/person/aspect/agreesWith/pool/
fixedLemma/feats) and resolve forms through the same paradigm table, so a
frame that passes here is guaranteed inflectable on device.
"""
from __future__ import annotations

import argparse
import json
import random
import sqlite3
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PERSONS = {1: "1", 2: "2", 3: "3"}


def norm(value: str) -> str:
    value = unicodedata.normalize("NFD", (value or "").lower().replace("ё", "е"))
    return value.replace("́", "").replace("̈", "").strip()


def load_inventory(notes_path: Path) -> dict[str, list[dict]]:
    """tier-0 notes grouped by POS, each row carrying gender/aspect for slot filtering."""
    by_pos: dict[str, list[dict]] = {"noun": [], "verb": [], "adjective": []}
    with notes_path.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            if row.get("tier") != 0:
                continue
            pos = row.get("pos")
            if pos in by_pos:
                by_pos[pos].append({
                    "lemma": norm(row.get("lemma") or row.get("russian") or ""),
                    "gender": row.get("gender"),
                    "aspect": row.get("aspect"),
                })
    return by_pos


def noun_feats(case: str, number: str) -> str:
    return f"{case}_{number}"


def adj_feats(case: str, gender: str | None, number: str) -> str | None:
    if number == "PL":
        return f"PL_{case}"
    if gender == "M":
        return f"{case}_SG"
    if gender == "F":
        return f"FEM_{case}"
    if gender == "N":
        return f"NEUT_{case}"
    return None


def verb_feats(slot: dict, gender: str | None) -> str | None:
    if "feats" in slot:
        return slot["feats"]
    tense = slot.get("tense")
    number = slot.get("number", "SG")
    if tense in ("PRES", "FUT"):
        person = slot.get("person")
        if person not in PERSONS:
            return None
        return f"{tense}_{PERSONS[person]}{number}"
    if tense == "PAST":
        if number == "PL":
            return "PAST_PL"
        if slot.get("agreesWith") and gender:
            return f"PAST_{gender}"
        return None
    return None


class FrameError(Exception):
    pass


def pick_lemma(slot: dict, inventory: dict[str, list[dict]], pools: dict[str, list[str]], rng: random.Random) -> dict:
    if "fixedLemma" in slot:
        return {"lemma": norm(slot["fixedLemma"]), "gender": None, "aspect": None}
    pos = "adjective" if slot["pos"] == "adj" else slot["pos"]
    candidates = inventory.get(pos, [])
    if "pool" in slot:
        allowed = {norm(x) for x in pools[slot["pool"]]}
        candidates = [c for c in candidates if c["lemma"] in allowed]
    if slot.get("aspect"):
        candidates = [c for c in candidates if c.get("aspect") == slot["aspect"]]
    if not candidates:
        raise FrameError(f"no inventory candidates for slot {slot['role']}")
    return rng.choice(candidates)


def realize_frame(frame: dict, inventory: dict[str, list[dict]], pools: dict[str, list[str]],
                   paradigm: dict[tuple[str, str], str], rng: random.Random) -> dict[str, str]:
    chosen: dict[str, dict] = {}
    forms: dict[str, str] = {}
    # Resolve independent slots first, then slots with agreesWith/pool-of-a-role deps.
    ordered = sorted(frame["slots"], key=lambda s: 1 if ("agreesWith" in s) else 0)
    for slot in ordered:
        role = slot["role"]
        entry = pick_lemma(slot, inventory, pools, rng)
        chosen[role] = entry
        lemma = entry["lemma"]
        if slot["pos"] == "noun":
            feats = noun_feats(slot["case"], slot.get("number", "SG"))
        elif slot["pos"] == "adj":
            target = chosen.get(slot["agreesWith"])
            if target is None:
                raise FrameError(f"adj slot {role} references unresolved role {slot['agreesWith']}")
            feats = adj_feats(slot["case"], target.get("gender"), slot.get("number", "SG"))
        else:  # verb
            gender = None
            if slot.get("agreesWith"):
                target = chosen.get(slot["agreesWith"])
                if target is None:
                    raise FrameError(f"verb slot {role} references unresolved role {slot['agreesWith']}")
                gender = target.get("gender")
            feats = verb_feats(slot, gender)
        if not feats:
            raise FrameError(f"slot {role} produced no feats key")
        surface = paradigm.get((lemma, feats))
        if not surface:
            raise FrameError(f"no paradigm form for {lemma}/{feats} (slot {role})")
        forms[role] = surface
    return forms


def render(template: str, forms: dict[str, str]) -> str:
    return template.format(**forms)


def validate_frames(frames_doc: dict, inventory: dict[str, list[dict]], db: sqlite3.Connection, samples: int = 20) -> None:
    paradigm: dict[tuple[str, str], str] = {}
    for lemma, feats, stressed in db.execute("SELECT lemma, feats, stressed FROM paradigm"):
        paradigm.setdefault((lemma, feats), stressed)
    pools = frames_doc["pools"]
    for frame in frames_doc["frames"]:
        rng = random.Random(frame["id"])
        ok = 0
        last_error = None
        for _ in range(samples):
            try:
                forms = realize_frame(frame, inventory, pools, paradigm, rng)
                render(frame["ruFrame"], forms)
                render(frame["enFrame"], forms)
                ok += 1
            except FrameError as exc:
                last_error = exc
        if ok == 0:
            raise SystemExit(f"frame {frame['id']} never realized: {last_error}")
        if ok < samples:
            print(f"warning: frame {frame['id']} realized {ok}/{samples} samples")


def build(db_path: Path, notes_path: Path, frames_path: Path, room_schema: Path) -> dict:
    frames_doc = json.loads(frames_path.read_text(encoding="utf-8"))
    inventory = load_inventory(notes_path)
    db = sqlite3.connect(db_path)
    validate_frames(frames_doc, inventory, db)
    db.executescript("""
        DROP TABLE IF EXISTS frame;
        CREATE TABLE frame(id TEXT NOT NULL PRIMARY KEY, concept TEXT NOT NULL, band TEXT NOT NULL,
          slots_json TEXT NOT NULL, ru_frame TEXT NOT NULL, en_frame TEXT NOT NULL,
          domain TEXT NOT NULL, register TEXT NOT NULL, minStage INTEGER NOT NULL,
          tier INTEGER NOT NULL, requiresAudioPack INTEGER NOT NULL, contrastConcept TEXT);
        CREATE INDEX index_frame_concept ON frame(concept);
        CREATE INDEX index_frame_domain ON frame(domain);
    """)
    pools = frames_doc["pools"]

    def inline_pools(slots: list[dict]) -> list[dict]:
        resolved = []
        for slot in slots:
            slot = dict(slot)
            pool_name = slot.pop("pool", None)
            if pool_name:
                slot["poolLemmas"] = [norm(x) for x in pools[pool_name]]
            resolved.append(slot)
        return resolved

    rows = [
        (f["id"], f["concept"], f["band"], json.dumps(inline_pools(f["slots"]), ensure_ascii=False), f["ruFrame"], f["enFrame"],
         f.get("domain", "general"), f.get("register", "neutral"), f.get("minStage", 1), f.get("tier", 1),
         1 if f.get("requiresAudioPack", False) else 0, f.get("contrastConcept"))
        for f in frames_doc["frames"]
    ]
    db.executemany("INSERT INTO frame VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", rows)
    schema = json.loads(room_schema.read_text(encoding="utf-8"))
    db.execute("INSERT OR REPLACE INTO room_master_table VALUES(42, ?)", (schema["database"]["identityHash"],))
    db.execute("PRAGMA user_version=6")
    db.commit()
    db.execute("VACUUM")
    db.close()
    return {"frames": len(rows)}


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser()
    p.add_argument("--db", type=Path, default=ROOT / "app/src/main/assets/tatoeba.db")
    p.add_argument("--notes", type=Path, default=ROOT / "app/src/main/assets/bootstrap_notes.jsonl")
    p.add_argument("--frames", type=Path, default=Path(__file__).parent / "frames.json")
    p.add_argument("--room-schema", type=Path, default=ROOT / "app/schemas/com.sibirskyspeak.data.ContentDatabase/6.json")
    return p


if __name__ == "__main__":
    args = parser().parse_args()
    print(json.dumps(build(args.db, args.notes, args.frames, args.room_schema), ensure_ascii=False, indent=2))
