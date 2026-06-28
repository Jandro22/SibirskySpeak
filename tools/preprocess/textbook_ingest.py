# -*- coding: utf-8 -*-
"""Extract the local Между нами textbook PDFs into app bootstrap content.

The four PDFs are activity/workbook books (classroom + homework), NOT answer-key
or dictionary books. An earlier version of this module fabricated "translations"
for arbitrary phrase lines (translation = "Textbook phrase from … unit 1.1"),
which produced hundreds of un-learnable SRS cards whose "answer" was metadata.

This version extracts only content that is *honestly* recoverable from the books:

* **Vocabulary** — Между нами glosses new words inline, in English parentheses
  right after the Russian: «Кто (Who)», «март (March)», «брат и сестра (brother
  and sister)». Those parenthetical glosses are real translations, so we mine
  them into real vocab notes. Nothing is invented.
* **Reader texts** — the books embed connected Russian narrative (the Amanda /
  Katya storyline) inside reading and true/false activities. We reconstruct those
  into clean, level-tagged graded passages, dropping word-lists, matching drills,
  running headers, item numbers, blanks, and English instructions.

Both outputs are sequenced by Урок (unit) and tagged A1 (units 1–5) or A2 (6–9),
which is exactly the A1.5→A2 reading band the app was missing.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
# Intermediate outputs only. The app reads bootstrap_notes.jsonl /
# bootstrap_reader_texts.jsonl, which build_bootstrap.py assembles (it imports the
# textbook_rows()/textbook_reader_texts() functions directly). These standalone
# files are for inspection/debugging and must NOT live under app/src/main/assets,
# or they would be packaged into the APK as dead weight.
OUT_DIR = ROOT / "tools" / "preprocess" / "out"
TEXTBOOK_DIR = Path.home() / "Documents"

ACUTE = "́"
CYRILLIC_RE = re.compile(r"[А-Яа-яЁё]")
LATIN_RE = re.compile(r"[A-Za-z]")
WORD_RE = re.compile(r"[А-Яа-яЁё́-]+")
CYR_WORD_RE = re.compile(r"[А-Яа-яЁё́]+")
PAGE_NO_RE = re.compile(r"^\d{1,3}$")

# A Russian term (1–3 words) immediately followed by an English gloss in parens.
GLOSS_RE = re.compile(
    r"([А-ЯЁ]?[а-яёА-ЯЁ" + ACUTE + r"]+(?:\s+[а-яёА-ЯЁ" + ACUTE + r"]+){0,2})\s*"
    r"\(([A-Za-z][A-Za-z ,'’/\-]{1,40})\)"
)

# Leading exercise markers to strip from a content line: "1.", "12)", "а.", "_____", bullets.
ITEM_MARK_RE = re.compile(r"^\s*(?:_+\s*)?(?:\d{1,2}[.)]|[а-яёa-zА-ЯЁ][.)]|[•·∙])\s*")

# English glosses that are section/instruction titles, not vocabulary.
TITLE_GLOSSES = {
    "classroom activities", "homework assignments", "review activities", "review",
    "work on the text", "let's read russian", "let’s read russian", "greetings",
    "how is it in the text", "what word is it", "which word is extra",
    "learning to read cursive", "learning to write letters", "learning to write words",
    "in what order", "which tense", "aspect pairs", "new verbs", "what is it",
    "who is it", "new",
}

# Inline glosses sometimes annotate character/place names. They are useful in a
# passage but poor recall cards: the learner cannot know whether to translate or
# transliterate them. Keep them in readers and exclude them from vocabulary SRS.
PROPER_NAME_GLOSSES = {
    "barbara", "masha", "yuri", "sasha", "yalta", "malta", "mars",
    "aesop", "zola", "amanda", "katya", "caitlin", "josh",
}
PDF_FRAGMENT_TERMS = {"ться", "ре", "лько", "те"}

# Bare-verb English glosses that are exercise *directions* ("Закончите (finish)",
# "Исправьте (correct)"), not target vocabulary. A Russian -те/-йте surface with
# one of these glosses is an instruction imperative and is dropped.
# Grammar-metadata "glosses" that are exercise annotations, not word meanings
# ("познакомиться (past tense)", "X (plural)").
GRAMMAR_META_GLOSSES = {
    "past tense", "present tense", "future tense", "singular", "plural",
    "imperfective", "perfective", "infinitive", "imperative",
}

INSTRUCTION_VERB_GLOSSES = {
    "finish", "complete", "recall", "correct", "write", "read", "answer", "change",
    "compare", "choose", "fill", "match", "look", "listen", "repeat", "say", "ask",
    "check", "remember", "review", "circle", "underline", "mark", "name", "describe",
    "retell", "translate", "decide", "solve", "write in", "fill in", "cross out",
    "act out", "connect", "continue", "explain", "guess", "make up", "put", "use",
}

# Hand corrections for known miner errors where the PDF's own inline gloss is
# wrong or imprecise. Keyed by the bare normalized lemma (ё->е, no stress). The
# miner otherwise trusts the textbook's parenthetical gloss verbatim.
GLOSS_OVERRIDES = {
    "зебра": "zebra",          # book glossed it generically as "an animal"
    "винительный": "Accusative",  # book glossed the case name as "inanimate"
}

# Surfaces the miner captured that are not real vocabulary at this level: a PDF
# head-split fragment that happens to lemmatize ("тика" <- полиТИКА), or a gloss
# the book attached to the wrong/too-advanced word ("мнить" glossed "to remember",
# which is помнить). Dropped outright. Keyed by stress-stripped lowercase surface.
DROP_SURFACES = {"тика", "мнить"}

# English glosses that begin with these tokens are phrase / prepositional-phrase
# glosses for an inflected form ("в парке (in the park)", "кому (to whom)"), or a
# question prompt — not a clean dictionary translation. Such pairs are dropped.
PHRASE_GLOSS_PREFIXES = (
    "in ", "on ", "under ", "at ", "to the", "to whom", "to a", "with ", "about ",
    "for ", "from ", "of ", "into ", "near ", "by the", "do you", "does ", "did ",
    "have you", "are you", "is it", "what ", "who ", "where ", "when ", "how ",
    "whose ", "which ", "i ", "we ", "they ", "he ", "she ",
)


class _Morph:
    """Lazy pymorphy3 wrapper. Used to (a) recover the dictionary lemma of an
    inflected textbook surface so it becomes a proper headword instead of an
    oblique form, (b) detect proper nouns (names/places) and imperatives that are
    not real vocabulary. Falls back to a no-op when pymorphy3 is unavailable so the
    build still runs (heuristics only)."""

    def __init__(self) -> None:
        self._m = None
        self._tried = False
        self._cache: dict[str, object] = {}

    def _analyzer(self):
        if not self._tried:
            self._tried = True
            try:
                import pymorphy3
                self._m = pymorphy3.MorphAnalyzer()
            except Exception:
                self._m = None
        return self._m

    def _parse(self, word: str):
        m = self._analyzer()
        if m is None:
            return None
        key = _strip_stress(word).lower()
        if key not in self._cache:
            try:
                self._cache[key] = m.parse(key)[0]
            except Exception:
                self._cache[key] = None
        return self._cache[key]

    def available(self) -> bool:
        return self._analyzer() is not None

    def lemma(self, word: str) -> str:
        p = self._parse(word)
        if p is None:
            return _strip_stress(word).lower().replace("ё", "е")
        return p.normal_form.replace("ё", "е")

    def is_dictionary_form(self, word: str) -> bool:
        """True if the surface already is its own dictionary form (nominative
        singular noun, infinitive, etc.) — i.e. not an inflected form."""
        p = self._parse(word)
        if p is None:
            return True  # no analyzer → don't over-drop; treat as headword
        return _strip_stress(word).lower().replace("ё", "е") == p.normal_form.replace("ё", "е")

    def pos(self, word: str) -> str | None:
        p = self._parse(word)
        return str(p.tag.POS) if p is not None and p.tag.POS else None

    def is_known(self, word: str) -> bool:
        """True if pymorphy3's dictionary recognizes the word. Used to drop PDF
        head-split fragments ("стро" from "бы́стро", "нице" from "гости́нице") that
        carry a valid-looking gloss but are not real words. Without an analyzer,
        returns True so the build does not over-drop."""
        m = self._analyzer()
        if m is None:
            return True
        try:
            return m.word_is_known(_strip_stress(word).lower())
        except Exception:
            return True

    def is_proper_noun(self, word: str) -> bool:
        p = self._parse(word)
        if p is None:
            return False
        tag = p.tag
        return any(g in tag for g in ("Name", "Surn", "Patr", "Geox"))

    def is_imperative(self, word: str) -> bool:
        p = self._parse(word)
        if p is None:
            return False
        return p.tag.mood == "impr" or "impr" in p.tag


MORPH = _Morph()

TEXTBOOKS = [
    {"path": TEXTBOOK_DIR / "MN1E RVA1-5 Su2026.pdf", "slug": "mn1e_rva_1_5",
     "kind": "classroom", "label": "Работа в аудитории 1-5", "unit_offset": 0},
    {"path": TEXTBOOK_DIR / "MN1E DZ1-5 Su2026.pdf", "slug": "mn1e_dz_1_5",
     "kind": "homework", "label": "Домашние задания 1-5", "unit_offset": 0},
    {"path": TEXTBOOK_DIR / "MN1E RVA6-9 Su2026.pdf", "slug": "mn1e_rva_6_9",
     "kind": "classroom", "label": "Работа в аудитории 6-9", "unit_offset": 5},
    {"path": TEXTBOOK_DIR / "MN1E DZ6-9 Su2026.pdf", "slug": "mn1e_dz_6_9",
     "kind": "homework", "label": "Домашние задания 6-9", "unit_offset": 5},
]


@dataclass(frozen=True)
class Activity:
    textbook_slug: str
    textbook_label: str
    kind: str
    unit: int
    part: int
    page: int
    activity_id: str
    title: str
    lines: tuple[str, ...]


def _strip_stress(value: str) -> str:
    return value.replace(ACUTE, "")


def clean_line(value: str) -> str:
    value = value.replace("", "•").replace(" ", " ")
    value = re.sub(r"\s+", " ", value).strip()
    return _rejoin_oversplit(value)


# One-letter words that legitimately follow a stressed word ("до́м с окно́м"): never
# fuse these, or "дом с" would become "домс". Guards the un-split repair below.
_STANDALONE_SHORT = {"и", "а", "о", "у", "я", "в", "к", "с", "о́", "и́", "я́", "но",
                     "не", "же", "ли", "бы", "до", "на", "по", "за", "из", "от"}


def _rejoin_oversplit(text: str) -> str:
    """Repair the PDF's stray space *inside* a word after a stressed vowel, WITHOUT
    gluing two real words that simply meet at a stress-final boundary ("она́ оно́"
    must stay two words). Two break shapes are repaired:

    * **broken head** — a 1–2 char stressed syllable fragment followed by its
      lowercase continuation ("бы́ ло" -> "бы́ло", "Ю́ рьевна" -> "Ю́рьевна");
    * **broken tail** — a stressed word followed by a 1–2 char lowercase fragment
      that is not a standalone function word ("говори́ т" -> "говори́т").

    A fragment that is itself a standalone short word (preposition/conjunction/
    pronoun) is never fused, so real word boundaries are preserved.
    """
    if ACUTE not in text:
        return text
    out: list[str] = []
    for tok in text.split(" "):
        if out and out[-1].endswith(ACUTE):
            head = _strip_stress(out[-1])
            tail = _strip_stress(tok)
            head_word = head.lower()
            tail_word = tail.lower()
            broken_head = (
                len(head) <= 2 and head_word not in _STANDALONE_SHORT
                and tok[:1].islower() and 1 <= len(tail) <= 8
            )
            broken_tail = (
                len(tail) <= 2 and tok[:1].islower() and tail_word not in _STANDALONE_SHORT
                and len(head) >= 3
            )
            if broken_head or broken_tail:
                out[-1] = out[-1] + tok
                continue
        out.append(tok)
    return " ".join(out)


def _heading_key(value: str) -> str:
    # For MATCHING only (not content): fuse the PDF's stray post-stress spaces so
    # keywords like "ЗАДА́ НИЕ" / "УРО́ К" read contiguously, then strip stress for a
    # stable key. Reader/vocab content keeps real spacing via the gentle clean_line.
    value = re.sub(rf"{ACUTE}\s+(?=[А-Яа-яЁё])", ACUTE, value)
    return _strip_stress(value).lower().replace("ё", "е")


def _is_noise(line: str) -> bool:
    """Running headers, footers, page numbers, and front-matter boilerplate."""
    if not line or PAGE_NO_RE.match(line):
        return True
    lowered = _heading_key(line)
    return (
        lowered.startswith("между нами")
        or lowered.startswith("last revised")
        or lowered.startswith("creative commons")
        or lowered.startswith("isbn")
        or lowered.startswith("урок ")                 # "урок 6: часть 1" running header
        or lowered.startswith("имя и фамилия")          # homework name/date header
        or lowered.startswith("число")
        or "mezhdunami" in lowered
        or lowered in {"содержание", "введение", "to students"}
    )


def _activity_heading(line: str) -> re.Match[str] | None:
    key = _heading_key(line)
    return re.match(r"^(\d+)\.(\d+)\s+(?:задание|упражнение)\s+([0-9а-яa-z]+)\.?\s*(.*)$", key)


def _lesson_part(line: str) -> tuple[int, int] | None:
    key = _heading_key(line)
    match = re.match(r"^урок\s+(\d+):\s+часть\s+(\d+)", key)
    if not match:
        return None
    return int(match.group(1)), int(match.group(2))


def _page_lines(doc, page_index: int) -> list[str]:
    text = doc.load_page(page_index).get_text("text")
    return [clean_line(raw) for raw in text.splitlines() if clean_line(raw)]


def extract_activities() -> list[Activity]:
    try:
        import fitz  # PyMuPDF
    except ImportError:
        return []

    activities: list[Activity] = []
    for meta in TEXTBOOKS:
        path = Path(meta["path"])
        if not path.exists():
            continue
        doc = fitz.open(path)
        unit = int(meta["unit_offset"]) + 1
        part = 1
        current: dict | None = None

        def flush() -> None:
            nonlocal current
            if not current:
                return
            lines = tuple(current["lines"])
            if sum(1 for line in lines if CYRILLIC_RE.search(line)) >= 2:
                activities.append(Activity(
                    textbook_slug=str(meta["slug"]), textbook_label=str(meta["label"]),
                    kind=str(meta["kind"]), unit=current["unit"], part=current["part"],
                    page=current["page"], activity_id=current["activity_id"],
                    title=current["title"], lines=lines,
                ))
            current = None

        for page_index in range(doc.page_count):
            for line in _page_lines(doc, page_index):
                lp = _lesson_part(line)
                if lp:
                    unit, part = lp
                    continue
                heading = _activity_heading(line)
                if heading:
                    flush()
                    current = {
                        "unit": unit, "part": part, "page": page_index + 1,
                        "activity_id": f"{heading.group(1)}.{heading.group(2)}.{heading.group(3)}",
                        "title": line, "lines": [line],
                    }
                elif current is not None:
                    current["lines"].append(line)
        flush()
    return activities


def _level_for_unit(unit: int) -> str:
    return "A1" if unit <= 5 else "A2"


# --- Reader texts: connected prose only ------------------------------------

# A standalone "OR" / "ИЛИ" choice marker betrays a fill-in/alternation exercise
# rather than prose ("OR — Да, Кевин играет…").
_ALTERNATION_RE = re.compile(r"(?:^|\s)(?:or|или)(?:\s|—|$)", re.IGNORECASE)


def _is_prose_sentence(s: str) -> bool:
    """Accept only genuine connected prose, rejecting the exercise debris that a
    bare word-count filter let through: single-letter alphabet/sound drills, split
    syllable soup, and choice/fill-in fragments. (Proper-name *rosters* are rejected
    at the passage level by [_has_prose_content], so that name-dense *narrative* —
    very common in this textbook's Amanda/Katya storyline — is preserved.)"""
    words = CYR_WORD_RE.findall(s)
    real = [w for w in words if len(_strip_stress(w)) >= 2]   # ignore single-letter "words"
    if len(real) < 4 or len(LATIN_RE.findall(s)) > 2:
        return False
    if any(ch in s for ch in ("_", "/", "…", "•", "=")):
        return False
    if _ALTERNATION_RE.search(s):
        return False
    # Syllable/alphabet soup: too many single letters, too many ≤2-char fragments,
    # or a tiny mean word length.
    singles = sum(1 for w in words if len(_strip_stress(w)) == 1)
    short = sum(1 for w in words if len(_strip_stress(w)) <= 2)
    if words and (singles / len(words) > 0.15 or short / len(words) > 0.40):
        return False
    mean_len = sum(len(_strip_stress(w)) for w in real) / len(real)
    if mean_len < 3.3:
        return False
    return True


def _has_prose_content(body: str) -> bool:
    """Distinguish narrative from a bare proper-name roster ("Аманда Тони Джош…").
    Running prose carries lowercase content words (verbs, adjectives, common nouns);
    a roster carries almost none. Require a real share of lowercase ≥3-letter words."""
    real = [w for w in CYR_WORD_RE.findall(body) if len(_strip_stress(w)) >= 2]
    if not real:
        return False
    lower_content = [w for w in real if _strip_stress(w)[:1].islower() and len(_strip_stress(w)) >= 3]
    return len(lower_content) >= 4 and len(lower_content) / len(real) >= 0.25


def readable_sentences(activity: "Activity") -> list[str]:
    """Reconstruct connected Russian sentences from an activity, dropping markers,
    blanks, English instructions, headers, and word-list fragments."""
    buf: list[str] = []
    for i, line in enumerate(activity.lines):
        if i == 0 or _is_noise(line) or _activity_heading(line):
            continue
        s = ITEM_MARK_RE.sub("", line)
        s = re.sub(r"_{2,}", " ", s).strip()
        if not s:
            continue
        # Skip English-dominant instruction lines.
        if len(LATIN_RE.findall(s)) > len(CYRILLIC_RE.findall(s)):
            continue
        buf.append(s)
    text = clean_line(" ".join(buf))
    return [raw.strip() for raw in re.findall(r"[^.!?…]+[.!?…]", text) if _is_prose_sentence(raw.strip())]


# Trailing English-initiated parenthetical notes on activity titles ("Какое это
# слово? (what word is it?)", "вопрос (using -нибудь)") are translator/grammar
# annotations, not part of the Russian title. The note may contain Cyrillic, so we
# match any content after a Latin-initial "(", plus trailing punctuation/ellipsis.
_TITLE_GLOSS_RE = re.compile(r"\s*\([A-Za-z][^)]*\)[\s.!?…]*$")
_TITLE_STRIP = " .:-—…"


def _clean_title(activity: "Activity") -> str:
    topic = _activity_heading(activity.title)
    tail = clean_line(topic.group(4)) if topic else activity.title
    tail = tail.strip(_TITLE_STRIP)
    tail = _TITLE_GLOSS_RE.sub("", tail)
    tail = tail.strip(_TITLE_STRIP)
    # An English-dominant heading ("Verbs ending in -овать") is a grammar-section
    # label, not a Russian title; use a plain Russian fallback instead.
    if not tail or len(LATIN_RE.findall(tail)) > len(CYRILLIC_RE.findall(tail)):
        tail = "Чтение"
    tail = tail.strip(_TITLE_STRIP) or "Чтение"
    # Title-case-ish: keep as-is but ensure first letter upper.
    if tail and tail[0].islower():
        tail = tail[0].upper() + tail[1:]
    return f"Между нами {activity.unit}.{activity.part}: {tail}"


def reader_texts_from_activities(activities: list[Activity]) -> list[dict]:
    rows = []
    for activity in activities:
        sentences = readable_sentences(activity)
        body = " ".join(sentences)
        total_words = sum(len(CYR_WORD_RE.findall(s)) for s in sentences)
        # Require a genuine passage: >=2 sentences, >=12 Russian words, and real
        # narrative content (not a proper-name roster).
        if len(sentences) < 2 or total_words < 12 or not _has_prose_content(body):
            continue
        level = _level_for_unit(activity.unit)
        rows.append({
            "title": _clean_title(activity),
            "source": f"textbook:{level.lower()}:{activity.textbook_slug}:u{activity.unit}:p{activity.part}",
            "body": " ".join(sentences),
        })
    return rows


# --- Vocabulary: real inline glosses only ----------------------------------

_LEADING_FUNCTION_WORDS = {"и", "а", "но", "в", "во", "на", "по", "с", "со", "к", "о", "об", "у", "не", "это", "э́то"}


def _clean_gloss_term(term: str) -> str | None:
    term = clean_line(term).strip(" .,:;—-")
    words = term.split()
    # Drop leading function words the gloss does not refer to ("и предметы" -> "предметы").
    while len(words) > 1 and _strip_stress(words[0]).lower() in _LEADING_FUNCTION_WORDS:
        words = words[1:]
    # A gloss annotates the word right before it; keep a multi-word span only for a
    # genuine coordinated phrase ("брат и сестра"), and only up to 3 clean tokens.
    is_phrase = "и" in [_strip_stress(w).lower() for w in words]
    if len(words) > 1 and not (is_phrase and len(words) <= 3):
        words = words[-1:]
    # Reject space-join artifacts ("жена́сын"): an over-long single token.
    if any(len(_strip_stress(w)) > 15 for w in words):
        return None
    term = " ".join(words).strip(" .,:;—-")
    if not term or not CYRILLIC_RE.search(term) or term.isupper():
        return None
    return term


_CLAUSE_GLOSS_RE = re.compile(r"\b(is|are|was|were|has|have|will|i'm|it's|he's|she's)\b", re.IGNORECASE)


def _is_phrase_gloss(gloss: str) -> bool:
    """A gloss that describes a phrase / clause / inflected context rather than a
    clean dictionary meaning ("in the park", "to whom", "Caitlin is tactless")."""
    low = gloss.lower()
    if any(low.startswith(p) for p in PHRASE_GLOSS_PREFIXES):
        return True
    if low in INSTRUCTION_VERB_GLOSSES:           # bare direction verb, not vocabulary
        return True
    if _CLAUSE_GLOSS_RE.search(gloss):            # a full clause leaked in as the "gloss"
        return True
    return False


def _is_instruction_imperative(term: str, gloss: str) -> bool:
    """A Russian -те/-йте surface glossed by a bare imperative verb is an exercise
    direction ("Закончите (finish)"), not target vocabulary."""
    bare = _strip_stress(term).lower()
    looks_imper = bare.endswith(("йте", "ите", "ете", "ьте", "айте")) and len(bare) > 4
    if looks_imper and (gloss.lower() in INSTRUCTION_VERB_GLOSSES or MORPH.is_imperative(term)):
        return True
    return MORPH.is_imperative(term) and gloss.lower() in INSTRUCTION_VERB_GLOSSES


def _vocab_note_from(term: str, gloss: str, unit: int) -> dict | None:
    """Build a single clean textbook vocab note from a glossed (term, gloss) pair,
    or None if the pair is not honest, learnable dictionary vocabulary.

    Inflected single-word forms are recovered: the dictionary lemma keys the note
    (so it dedups against the curated deck and never re-teaches a known word), the
    *stressed* surface from the book is kept for display and reader coverage, and
    the note is tagged ``recognition_only`` so the app builds recognition + listening
    + reader-coverage cards but no reverse-production drill (which would wrongly ask
    the learner to *produce* an oblique form). Dictionary-form words get the full set."""
    if not term or not gloss:
        return None
    if gloss.lower() in TITLE_GLOSSES or gloss.isupper():
        return None
    if gloss.lower() in PROPER_NAME_GLOSSES:
        return None
    gloss_words = gloss.split()
    # Gloss-quality gates apply to every term (single word or coordinated phrase):
    if gloss.lower() in GRAMMAR_META_GLOSSES:
        return None              # "past tense", "plural" — metadata, not a translation
    if _is_instruction_imperative(term, gloss):
        return None              # "Закончите (finish)"
    if _is_phrase_gloss(gloss):
        return None              # "в парке (in the park)", "do you know"
    if len(gloss_words) > 3:
        return None              # "someone to share a room", "Holiday of Spring and Labor"
    if _strip_stress(term)[:1].isupper() and gloss.lower().split()[0] in {"a", "an"}:
        return None              # capitalized + "a/an …" => proper-noun definition (Чебурашка)
    # A genuine coordinated phrase ("брат и сестра (brother and sister)") is exactly
    # "W и W" with both sides real non-verb words and an "and"-shaped gloss. Anything
    # else that merely *contains* "и" is a running-text capture ("Антон и предложил")
    # and collapses to its final word for normal single-word processing.
    toks = _strip_stress(term).lower().split()
    is_coordinated = (
        len(toks) == 3 and toks[1] == "и" and " and " in f" {gloss.lower()} "
        and MORPH.is_known(toks[0]) and MORPH.is_known(toks[2])
        and MORPH.pos(toks[0]) != "VERB" and MORPH.pos(toks[2]) != "VERB"
    )
    if not is_coordinated and len(term.split()) > 1:
        term = term.split()[-1]   # reduce running-text capture to its glossed word
    if is_coordinated:
        base_lemma = _strip_stress(term).lower().replace("ё", "е")
        inflected = False
    else:
        # PDF head-split fragments ("стро", "нице") look like glossed words but are
        # not real Russian — drop anything the morphology dictionary doesn't know.
        if not MORPH.is_known(term):
            return None
        if MORPH.is_proper_noun(term):
            return None
        # Verbs are honest vocabulary only in their infinitive (dictionary) form; an
        # inflected/imperative surface ("соедините", "решите") is exercise machinery.
        if MORPH.pos(term) == "VERB" and not MORPH.is_dictionary_form(term):
            return None
        base_lemma = MORPH.lemma(term)
        inflected = not MORPH.is_dictionary_form(term)
        # Recover a legit common word that was merely sentence-initial in the book by
        # lowercasing its display form ("На́бережная" -> "на́бережная").
        if _strip_stress(term)[:1].isupper():
            term = term[:1].lower() + term[1:]
    norm = base_lemma.replace("ё", "е")
    if len(norm) < 3 or norm in PDF_FRAGMENT_TERMS:
        return None
    if _strip_stress(term).lower().replace("ё", "е") in DROP_SURFACES:
        return None
    level = _level_for_unit(unit)
    recognition = " recognition_only" if (not is_coordinated and inflected) else ""
    translation = gloss if (gloss[:1].isupper() and not gloss.split()[0].islower()) else gloss.lower()
    translation = GLOSS_OVERRIDES.get(norm, translation)
    return {
        "russian": term,
        "lemma": f"tb_{norm}",   # dictionary lemma; namespaced from the curated deck
        "pos": "word",
        "translation": translation,
        "tier": 0,
        "unit": unit,
        "cefrLevel": level,
        "tags": f"textbook vocab mn1e unit-{unit} {level.lower()}{recognition}",
    }


def vocab_notes_from_activities(activities: list[Activity], limit: int = 1200) -> list[dict]:
    rows: list[dict] = []
    by_lemma: dict[str, dict] = {}
    for activity in activities:
        text = clean_line(" ".join(activity.lines))
        for m in GLOSS_RE.finditer(text):
            term = _clean_gloss_term(m.group(1))
            gloss = clean_line(m.group(2)).strip(" .,")
            note = _vocab_note_from(term or "", gloss, activity.unit)
            if note is None:
                continue
            lemma = note["lemma"]
            prior = by_lemma.get(lemma)
            if prior is None:
                by_lemma[lemma] = note
                rows.append(note)
                if len(rows) >= limit:
                    return rows
            else:
                # Prefer a dictionary-form surface over an inflected one for the
                # same lemma, so the headword the learner sees is the base form.
                prior_inflected = "recognition_only" in prior["tags"]
                now_inflected = "recognition_only" in note["tags"]
                if prior_inflected and not now_inflected:
                    prior.update(note)
    return rows


def textbook_rows() -> list[dict]:
    return vocab_notes_from_activities(extract_activities())


def textbook_reader_texts() -> list[dict]:
    return reader_texts_from_activities(extract_activities())


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def main() -> None:
    activities = extract_activities()
    notes = vocab_notes_from_activities(activities)
    readers = reader_texts_from_activities(activities)
    write_jsonl(OUT_DIR / "textbook_notes.jsonl", notes)
    write_jsonl(OUT_DIR / "textbook_reader_texts.jsonl", readers)
    by_level = {}
    for r in readers:
        lvl = r["source"].split(":")[1]
        by_level[lvl] = by_level.get(lvl, 0) + 1
    recog = sum(1 for n in notes if "recognition_only" in n["tags"])
    morph = "pymorphy3" if MORPH.available() else "heuristics-only"
    print(f"textbook activities: {len(activities)}; "
          f"glossed vocab notes: {len(notes)} ({recog} inflected/recognition-only); "
          f"reader passages: {len(readers)} {by_level}; morphology: {morph}")
    print(f"wrote -> {OUT_DIR} (intermediate; bootstrap is assembled by build_bootstrap.py)")


if __name__ == "__main__":
    main()
