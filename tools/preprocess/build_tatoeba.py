#!/usr/bin/env python3
"""Build the immutable, Room-compatible Russian sentence content database.

The fast path uses Tatoeba's per-language weekly exports, avoiding the multi-GB
all-language files:

  python tools/preprocess/build_tatoeba.py \
    --russian rus_sentences.tsv.bz2 --english eng_sentences.tsv.bz2 \
    --links rus-eng_links.tsv.bz2 --audio rus_sentences_with_audio.tsv.bz2

All NLP is offline. Low-confidence stress is deliberately left unmarked; wrong
stress is worse learning data than absent stress.
"""
from __future__ import annotations

import argparse
import bz2
import gzip
import heapq
import json
import re
import sqlite3
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date
from functools import lru_cache
from pathlib import Path
from typing import Iterable, Iterator, TextIO

WORD_RE = re.compile(r"[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?")
STRESSED_WORD_RE = re.compile(r"(?:[А-Яа-яЁё]\u0301?)+(?:-(?:[А-Яа-яЁё]\u0301?)+)?")
URL_RE = re.compile(r"(?:https?://|www\.|\S+@\S+)", re.I)
COMBINING_ACUTE = "\u0301"
PROFANITY = {"блядь", "сука", "хуй", "пизда", "ебать", "ёбаный"}
PREFIXES = tuple(sorted((
    "пред", "пере", "при", "про", "под", "над", "без", "вз", "воз", "вы",
    "до", "за", "из", "на", "об", "от", "по", "раз", "с", "у", "в",
), key=len, reverse=True))
SUFFIXES = ("ость", "ение", "ание", "ировать", "овать", "евать", "ник", "тель", "ный", "ский")
EMOJI = {
    "дом": "🏠", "зонт": "☂️", "собака": "🐕", "кошка": "🐈", "книга": "📖",
    "телефон": "📱", "машина": "🚗", "поезд": "🚆", "самолёт": "✈️",
    "вода": "💧", "огонь": "🔥", "солнце": "☀️", "луна": "🌙", "сердце": "❤️",
    "яблоко": "🍎", "хлеб": "🍞", "кофе": "☕", "школа": "🏫", "больница": "🏥",
}


def open_text(path: Path) -> TextIO:
    if path.suffix == ".bz2":
        return bz2.open(path, "rt", encoding="utf-8", errors="replace")
    if path.suffix == ".gz":
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    return path.open("r", encoding="utf-8", errors="replace")


def plain(value: str) -> str:
    return value.replace(COMBINING_ACUTE, "").replace("ё", "е").replace("Ё", "Е")


def tidy(value: str) -> str:
    return re.sub(r"\s+([,.;:!?…])", r"\1", re.sub(r"\s{2,}", " ", value)).strip()


@dataclass(frozen=True)
class Token:
    surface: str
    lemma: str
    pos: str


class Morphology:
    def __init__(self, allow_simple: bool = False):
        try:
            import pymorphy3  # type: ignore
            self._morph = pymorphy3.MorphAnalyzer()
        except Exception:
            if not allow_simple:
                raise RuntimeError("pymorphy3 is required (or pass --allow-simple for fixture builds)")
            self._morph = None

    def parse(self, text: str) -> list[Token]:
        result = []
        for match in WORD_RE.finditer(text):
            surface = plain(match.group()).lower()
            if self._morph is None:
                result.append(Token(surface, surface, "UNKN"))
            else:
                lemma, pos = self._parse_word(surface)
                result.append(Token(surface, lemma, pos))
        return result

    @lru_cache(maxsize=250_000)
    def _parse_word(self, surface: str) -> tuple[str, str]:
        parsed = self._morph.parse(surface)[0]
        return plain(parsed.normal_form).lower(), str(parsed.tag.POS or "UNKN")


class StressAnnotator:
    """Dictionary lookup keyed by surface and optionally pymorphy POS."""
    def __init__(self, paths: Iterable[Path]):
        self.forms: dict[tuple[str, str], set[str]] = defaultdict(set)
        for path in paths:
            if not path.exists():
                continue
            if path.suffix == ".jsonl":
                self._load_bootstrap(path)
            else:
                with open_text(path) as handle:
                    for line in handle:
                        fields = line.rstrip("\n").split("\t")
                        stressed = fields[1] if len(fields) > 1 else fields[0]
                        pos = fields[2] if len(fields) > 2 else "*"
                        if COMBINING_ACUTE in stressed:
                            self.forms[(plain(stressed).lower(), pos)].add(stressed.lower())

    def _load_bootstrap(self, path: Path) -> None:
        with path.open(encoding="utf-8") as handle:
            for line in handle:
                row = json.loads(line)
                for key in ("russian", "lemma", "exampleSentence", "exampleSentence2", "exampleSentence3"):
                    for word in STRESSED_WORD_RE.findall(row.get(key) or ""):
                        if COMBINING_ACUTE in word:
                            self.forms[(plain(word).lower(), "*")].add(word.lower())

    def annotate(self, text: str, tokens: list[Token]) -> str:
        index = 0
        def replace(match: re.Match[str]) -> str:
            nonlocal index
            token = tokens[index] if index < len(tokens) else Token(match.group().lower(), match.group().lower(), "UNKN")
            index += 1
            choices = self.forms.get((token.surface, token.pos), set()) | self.forms.get((token.surface, "*"), set())
            if len(choices) != 1:
                return match.group()
            stressed = next(iter(choices))
            return stressed[:1].upper() + stressed[1:] if match.group()[:1].isupper() else stressed
        return WORD_RE.sub(replace, text)


@dataclass
class RawSentence:
    id: int
    ru: str
    n_tokens: int
    owner_score: float
    audio: bool = False
    en: str = ""
    tokens: list[Token] | None = None
    quality: float = 0.0


def sentence_rows(path: Path) -> Iterator[tuple[int, str, float]]:
    with open_text(path) as handle:
        for line in handle:
            fields = line.rstrip("\n").split("\t")
            if len(fields) < 2 or not fields[0].isdigit():
                continue
            # Current per-language exports are id, ISO-639-3, text. Older/fixture
            # exports use id, text; detailed exports append owner/date.
            has_language = len(fields) >= 3 and len(fields[1]) == 3 and fields[1].isalpha()
            text_index = 2 if has_language else 1
            owner_index = text_index + 1
            owner = fields[owner_index].strip() if len(fields) > owner_index else ""
            yield int(fields[0]), fields[text_index].strip(), 0.65 if owner and owner != "\\N" else 0.35


def acceptable(text: str) -> tuple[bool, int]:
    words = WORD_RE.findall(text)
    if not 3 <= len(words) <= 12 or URL_RE.search(text):
        return False, len(words)
    letters = "".join(words)
    if letters and letters.upper() == letters:
        return False, len(words)
    if any(char.isdigit() for char in text) and sum(c.isdigit() for c in text) > 2:
        return False, len(words)
    if {plain(w).lower() for w in words} & PROFANITY:
        return False, len(words)
    proper_inside = sum(1 for word in words[1:] if word[:1].isupper())
    if proper_inside > max(1, len(words) // 3):
        return False, len(words)
    return True, len(words)


def load_audio(path: Path | None) -> set[int]:
    if path is None:
        return set()
    ids = set()
    with open_text(path) as handle:
        for line in handle:
            first = line.split("\t", 1)[0]
            if first.isdigit():
                ids.add(int(first))
    return ids


def linked_english(links: Path, russian_ids: set[int]) -> dict[int, int]:
    result = {}
    with open_text(links) as handle:
        for line in handle:
            fields = line.rstrip("\n").split("\t")
            if len(fields) >= 2 and fields[0].isdigit() and fields[1].isdigit():
                ru, en = int(fields[0]), int(fields[1])
                if ru in russian_ids:
                    result.setdefault(ru, en)  # deterministic canonical translation
    return result


def root_parts(lemma: str) -> tuple[str, str, str]:
    prefix = next((p for p in PREFIXES if lemma.startswith(p) and len(lemma) - len(p) >= 4), "")
    body = lemma[len(prefix):]
    suffix = next((s for s in SUFFIXES if body.endswith(s) and len(body) - len(s) >= 3), "")
    root = body[:-len(suffix)] if suffix else body
    return root, prefix, suffix


def schema_identity(schema: Path) -> str:
    return json.loads(schema.read_text(encoding="utf-8"))["database"]["identityHash"]


def create_schema(db: sqlite3.Connection, identity: str) -> None:
    db.executescript("""
        PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA temp_store=MEMORY;
        CREATE TABLE sentence(id INTEGER NOT NULL PRIMARY KEY, ru_stressed TEXT NOT NULL, ru_plain TEXT NOT NULL, en TEXT NOT NULL, n_tokens INTEGER NOT NULL, audio INTEGER NOT NULL, rating REAL NOT NULL);
        CREATE TABLE lemma_index(lemma TEXT NOT NULL, sentence_id INTEGER NOT NULL, target_pos INTEGER NOT NULL, pos TEXT NOT NULL, PRIMARY KEY(lemma,sentence_id,target_pos));
        CREATE INDEX index_lemma_index_lemma ON lemma_index(lemma);
        CREATE INDEX index_lemma_index_sentence_id ON lemma_index(sentence_id);
        CREATE TABLE collocation(lemma TEXT NOT NULL, chunk TEXT NOT NULL, freq INTEGER NOT NULL, PRIMARY KEY(lemma,chunk));
        CREATE INDEX index_collocation_lemma ON collocation(lemma);
        CREATE TABLE root_family(root TEXT NOT NULL, lemma TEXT NOT NULL, prefix TEXT NOT NULL, suffix TEXT NOT NULL, PRIMARY KEY(root,lemma));
        CREATE INDEX index_root_family_lemma ON root_family(lemma);
        CREATE TABLE emoji_map(lemma TEXT NOT NULL PRIMARY KEY, emoji TEXT NOT NULL);
        CREATE TABLE semantic_neighbor(lemma TEXT NOT NULL, neighbor TEXT NOT NULL, similarity REAL NOT NULL, PRIMARY KEY(lemma,neighbor));
        CREATE INDEX index_semantic_neighbor_lemma ON semantic_neighbor(lemma);
        CREATE TABLE meta(`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT);
    """)
    db.execute("INSERT INTO room_master_table VALUES(42, ?)", (identity,))


def semantic_neighbors(path: Path | None, lemmas: set[str], k: int = 8):
    """Chunked exact cosine k-NN over the selected fastText vocabulary."""
    if path is None:
        return []
    try:
        import numpy as np
    except ImportError as exc:
        raise RuntimeError("numpy is required with --fasttext-vectors") from exc
    words, vectors = [], []
    with open_text(path) as handle:
        first = handle.readline().split()
        if len(first) > 2:  # headerless vector file
            handle.seek(0)
        for line in handle:
            fields = line.rstrip().split()
            if len(fields) < 3:
                continue
            word = plain(fields[0]).lower()
            if word not in lemmas:
                continue
            try:
                vectors.append([float(value) for value in fields[1:]])
                words.append(word)
            except ValueError:
                continue
    if not vectors:
        return []
    matrix = np.asarray(vectors, dtype=np.float32)
    matrix /= np.maximum(np.linalg.norm(matrix, axis=1, keepdims=True), 1e-9)
    result = []
    for start in range(0, len(words), 256):
        scores = matrix[start:start + 256] @ matrix.T
        for local, row in enumerate(scores):
            own = start + local
            row[own] = -2.0
            count = min(k, len(words) - 1)
            if count <= 0:
                continue
            best = np.argpartition(row, -count)[-count:]
            for index in best[np.argsort(row[best])[::-1]]:
                result.append((words[own], words[int(index)], float(row[int(index)])))
    return result


def build(args: argparse.Namespace) -> dict[str, int | float]:
    morph = Morphology(args.allow_simple)
    stress = StressAnnotator([Path(p) for p in args.stress_lexicon] + ([args.bootstrap_stress] if args.bootstrap_stress else []))
    audio_ids = load_audio(args.audio)
    russian: dict[int, RawSentence] = {}
    if getattr(args, "parallel_russian", None) and getattr(args, "parallel_english", None):
        english = {}
        with open_text(args.parallel_russian) as ru_handle, open_text(args.parallel_english) as en_handle:
            for sid, (ru, en) in enumerate(zip(ru_handle, en_handle), 1):
                ok, count = acceptable(ru.strip())
                if ok and en.strip():
                    russian[sid] = RawSentence(sid, tidy(ru), count, 0.5, False)
                    english[sid] = tidy(en)
        links = {sid: sid for sid in russian}
    else:
        if not args.russian or not args.english or not args.links:
            raise ValueError("provide --russian/--english/--links or the two --parallel-* files")
        for sid, text, owner in sentence_rows(args.russian):
            ok, count = acceptable(text)
            if ok:
                russian[sid] = RawSentence(sid, tidy(text), count, owner, sid in audio_ids)
        links = linked_english(args.links, set(russian))
        english_needed = set(links.values())
        english = {sid: text for sid, text, _ in sentence_rows(args.english) if sid in english_needed}
    records = []
    for sid, row in russian.items():
        en = english.get(links.get(sid, -1), "")
        if not en:
            continue
        row.en = tidy(en)
        row.tokens = morph.parse(row.ru)
        ideal = 1.0 - min(abs(row.n_tokens - 6.5) / 6.5, 1.0)
        row.quality = ideal + row.owner_score + (0.25 if row.audio else 0.0)
        records.append(row)
    records.sort(key=lambda row: (-row.quality, row.id))

    lemma_counts: Counter[str] = Counter()
    document_frequency: Counter[str] = Counter()
    for row in records:
        document_frequency.update({token.lemma for token in (row.tokens or [])})
    selected: list[RawSentence] = []
    indices: list[tuple[str, int, int, str]] = []
    for row in records:
        useful = [(i, token) for i, token in enumerate(row.tokens or []) if lemma_counts[token.lemma] < args.per_lemma]
        if not useful:
            continue
        # Assign each sentence to its rarest eligible target. Indexing every word in
        # the first few high-quality sentences burns common-lemma caps immediately
        # and collapses a 300k corpus to ~80k duplicate candidates. One target per
        # sentence preserves the strict cap while maximizing long-tail coverage.
        useful = sorted(useful, key=lambda item: (document_frequency[item[1].lemma], item[0]))[:args.indices_per_sentence]
        selected.append(row)
        for position, token in useful:
            if lemma_counts[token.lemma] >= args.per_lemma:
                continue
            indices.append((token.lemma, row.id, position, token.pos))
            lemma_counts[token.lemma] += 1
        if len(selected) >= args.max_sentences:
            break

    output: Path = args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()
    db = sqlite3.connect(output)
    create_schema(db, schema_identity(args.room_schema))
    stressed_words = total_words = 0
    by_id = {}
    for row in selected:
        stressed = stress.annotate(row.ru, row.tokens or [])
        stressed_words += sum(COMBINING_ACUTE in word for word in STRESSED_WORD_RE.findall(stressed))
        total_words += row.n_tokens
        by_id[row.id] = row
        db.execute("INSERT INTO sentence VALUES(?,?,?,?,?,?,?)", (row.id, stressed, plain(row.ru), row.en, row.n_tokens, int(row.audio), row.owner_score))
    db.executemany("INSERT INTO lemma_index VALUES(?,?,?,?)", indices)

    chunks: dict[str, Counter[str]] = defaultdict(Counter)
    for lemma, sid, position, _ in indices:
        tokens = [token.surface for token in (by_id[sid].tokens or [])]
        for size in range(2, 5):
            for start in range(max(0, position - size + 1), min(position + 1, len(tokens) - size + 1)):
                chunks[lemma][" ".join(tokens[start:start + size])] += 1
    db.executemany("INSERT INTO collocation VALUES(?,?,?)", (
        (lemma, chunk, freq) for lemma, counts in chunks.items() for chunk, freq in counts.most_common(8) if freq >= args.min_chunk_freq
    ))
    lemmas = sorted(lemma_counts)
    db.executemany("INSERT OR IGNORE INTO root_family VALUES(?,?,?,?)", ((root_parts(lemma)[0], lemma, root_parts(lemma)[1], root_parts(lemma)[2]) for lemma in lemmas))
    db.executemany("INSERT OR IGNORE INTO emoji_map VALUES(?,?)", ((lemma, emoji) for lemma, emoji in EMOJI.items() if lemma in lemma_counts))
    db.executemany("INSERT OR IGNORE INTO semantic_neighbor VALUES(?,?,?)", semantic_neighbors(args.fasttext_vectors, set(lemmas)))
    meta = {
        "version": args.version, "built_at": date.today().isoformat(), "sentences": str(len(selected)),
        "lemmas": str(len(lemma_counts)), "attribution": "Example sentences from Tatoeba (tatoeba.org), CC-BY 2.0 FR.",
        "license": "CC-BY 2.0 FR", "stress_coverage": f"{stressed_words / max(1, total_words):.6f}",
        "per_lemma_cap": str(args.per_lemma),
    }
    db.executemany("INSERT INTO meta VALUES(?,?)", meta.items())
    db.execute("ANALYZE")
    db.execute("PRAGMA user_version=1")
    db.commit()
    db.execute("VACUUM")
    db.close()
    return {"sentences": len(selected), "lemmas": len(lemma_counts), "stress_coverage": stressed_words / max(1, total_words)}


def parser() -> argparse.ArgumentParser:
    root = Path(__file__).resolve().parents[2]
    p = argparse.ArgumentParser()
    p.add_argument("--russian", type=Path)
    p.add_argument("--english", type=Path)
    p.add_argument("--links", type=Path)
    p.add_argument("--parallel-russian", type=Path, help="aligned OPUS/Moses Russian text")
    p.add_argument("--parallel-english", type=Path, help="aligned OPUS/Moses English text")
    p.add_argument("--audio", type=Path)
    p.add_argument("--stress-lexicon", action="append", default=[])
    p.add_argument("--fasttext-vectors", type=Path, help="optional Russian fastText .vec(.gz) used to bake semantic neighbors")
    p.add_argument("--bootstrap-stress", type=Path, default=root / "app/src/main/assets/bootstrap_notes.jsonl")
    p.add_argument("--room-schema", type=Path, default=root / "app/schemas/com.sibirskyspeak.data.ContentDatabase/1.json")
    p.add_argument("--output", type=Path, default=root / "app/src/main/assets/tatoeba.db")
    p.add_argument("--max-sentences", type=int, default=300_000)
    p.add_argument("--per-lemma", type=int, default=8)
    p.add_argument("--indices-per-sentence", type=int, default=1)
    p.add_argument("--min-chunk-freq", type=int, default=2)
    p.add_argument("--version", default=date.today().isoformat())
    p.add_argument("--allow-simple", action="store_true", help="fixture-only fallback without pymorphy3")
    return p


if __name__ == "__main__":
    result = build(parser().parse_args())
    print(json.dumps(result, ensure_ascii=False, indent=2))
