"""Build the app's bootstrap_notes.jsonl / bootstrap_reader_texts.jsonl from the
curated domain wordlist using the rule-based declension engine.

Usage:
    python tools/preprocess/build_bootstrap.py

Writes real, studyable content to app/src/main/assets/. Every emitted line
matches the Android import contract (see design doc §13.5).
"""
from __future__ import annotations

import json
import re
import hashlib
import os
import time
import uuid
from pathlib import Path

from russian_morph import (decline_adjective, decline_noun,
                           decline_plurale_tantum, past_masculine, strip_stress)
from present_verb_forms import present_forms_for
import domain_wordlist as wl

ASSETS = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets"
HERE = Path(__file__).resolve().parent

# Real, bundled Tatoeba examples selected to match the exact textbook sense and
# surface the learner is reviewing. These replace unrelated Wiktionary senses
# that the learner history showed were actively confusing.
TEXTBOOK_EXAMPLE_OVERRIDES = {
    "сдать": {
        "ru": "Я слы́шал, он сдал экза́мен.",
        "en": "I heard that he passed the exam.",
        "source": "Tatoeba via bundled content database",
        "reference": "https://tatoeba.org/en/sentences/show/6444275",
    },
    "попасть": {
        "ru": "Как мне попа́сть на другу́ю сто́рону?",
        "en": "How do I get to the other side?",
        "source": "Tatoeba via bundled content database",
        "reference": "https://tatoeba.org/en/sentences/show/2784307",
    },
    "визитка": {
        "ru": "Да́йте ему мою визи́тку, пожа́луйста.",
        "en": "Give him my business card, please.",
        "source": "Tatoeba via bundled content database",
        "reference": "https://tatoeba.org/en/sentences/show/9004284",
    },
    "миндальный": {
        "ru": "Мне нра́вится минда́льное молоко́.",
        "en": "I like almond milk.",
        "source": "Tatoeba via bundled content database",
        "reference": "https://tatoeba.org/en/sentences/show/7740763",
    },
}
TEXTBOOK_REVERIFY_LEMMAS = {"tb_дешево", "tb_гид", "tb_сдать"}


def load_domain_freq():
    """lemma -> domain rank from domain_freq_list.tsv (header: lemma<TAB>rank)."""
    path = HERE / "domain_freq_list.tsv"
    ranks = {}
    if not path.exists():
        return ranks
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) != 2 or parts[1].strip().lower() == "rank":
            continue
        lemma, rank = parts[0].strip(), parts[1].strip()
        if lemma and rank.isdigit():
            ranks.setdefault(lemma.lower(), int(rank))
    return ranks


DOMAIN_FREQ = load_domain_freq()


def domain_rank(lemma: str, fallback: int) -> int:
    """Real domain-corpus rank if the lemma is in the frequency list, else a
    fallback that sorts after the ranked core (kept stable per call site)."""
    return DOMAIN_FREQ.get(lemma.lower(), fallback)


# Tier-2 (formal/political/security-register) content is never A1/A2/B1 material —
# it's institutional and abstract-topic vocabulary by nature (government, ministry,
# sanctions, jurisdiction...), which is squarely a B2+ CEFR domain ("understand...
# complex text on...abstract topics, including technical discussions" is the B2
# descriptor). Band it B2/C1/C2 by real corpus rank (same DOMAIN_FREQ signal already
# used for sequencing) instead of leaving it untagged — untagged content is invisible
# to any CEFR-aware gate and was surfacing for A1 learners. Anything not found in the
# domain frequency list at all (fallback ranks) is obscure enough to default to C2.
TIER2_CEFR_BY_RANK = ((250, "B2"), (600, "C1"))


def tier2_cefr_level(rank: int) -> str:
    return next((lvl for thresh, lvl in TIER2_CEFR_BY_RANK if rank <= thresh), "C2")

GENDER_BY_CLASS = {
    "m_hard": "M", "m_fleeting": "M", "m_j": "M", "m_iy": "M", "m_soft": "M",
    "f_a": "F", "f_ya": "F", "f_iya": "F", "f_soft": "F",
    "n_o": "N", "n_e": "N", "n_ie": "N",
    "indecl": "N",
    "pl_voiska": "PL", "pl_peregovory": "PL", "pl_vybory": "PL", "pl_uchenia": "PL",
}

BAD_EXAMPLE_MARKERS = (
    "имеет большое значение в этом вопросе",
    "обсуждается в официальном заявлении",
    "упоминается в новом документе",
    "стало предметом переговоров",
    "фактор учитывается в стратегии",
    "Сторонам важно",
)

SENTENCE_RE = re.compile(r"[^.!?]+[.!?]")
WORD_RE = re.compile(r"[а-яё-]+", re.IGNORECASE)


def normalize_text(value: str) -> str:
    return strip_stress(value).lower().replace("ё", "е")


def sentence_words(sentence: str) -> set[str]:
    return set(WORD_RE.findall(normalize_text(sentence)))


def reader_sentences() -> list[tuple[str, set[str]]]:
    sentences = []
    for text in all_reader_texts():
        for match in SENTENCE_RE.findall(text["body"]):
            sentence = match.strip()
            if 18 <= len(sentence) <= 240:
                sentences.append((sentence, sentence_words(sentence)))
    return sentences


_READER_SENTENCES: list[tuple[str, set[str]]] | None = None


def corpus_sentence(forms) -> str | None:
    """Return a real sentence from bundled reader material containing any form."""
    global _READER_SENTENCES
    if _READER_SENTENCES is None:
        _READER_SENTENCES = reader_sentences()
    targets = {normalize_text(f) for f in forms if f}
    targets = {t for t in targets if t}
    if not targets:
        return None
    for sentence, words in _READER_SENTENCES:
        if words & targets:
            return sentence
    return None


def corpus_or_fallback(forms, fallback, corpus_gloss: str):
    """Return an (example_sentence, example_translation) pair.

    Previously this preferred an authentic corpus sentence but paired it with only
    the *headword* as the "translation" (corpus_gloss), leaving every such note with
    an unreadable example (e.g. a full Russian sentence glossed as just "state").
    Comprehensible input matters more than authenticity here: always use the aligned
    template fallback, whose Russian and English are real translations of each other.
    """
    return fallback


def is_low_quality_example(sentence: str) -> bool:
    return any(marker in sentence for marker in BAD_EXAMPLE_MARKERS)


def term_en(translation: str) -> str:
    return translation.split("/")[0]


def table_forms(table: dict) -> list[str]:
    return [str(v) for v in table.values() if isinstance(v, str) and v.strip()]


INANIMATE_CONTEXTS = [
    ("После доклада участники отдельно обсудили {A}.", "After the report, the participants discussed the {T} separately."),
    ("В проекте резолюции есть ссылка на {A}.", "The draft resolution includes a reference to the {T}."),
    ("На заседании возник спор вокруг {G}.", "A dispute arose at the meeting around the {T}."),
    ("Новая мера повлияла на {A} сильнее, чем ожидали эксперты.", "The new measure affected the {T} more strongly than experts expected."),
    ("Комиссия запросила дополнительные данные о {P}.", "The commission requested additional data about the {T}."),
    ("В отчёте подробно описаны последствия для {G}.", "The report describes the consequences for the {T} in detail."),
    ("Представитель ведомства связал решение с {I}.", "The agency representative linked the decision with the {T}."),
    ("К концу встречи стороны вернулись к вопросу о {P}.", "By the end of the meeting, the parties returned to the issue of the {T}."),
]

ANIMATE_CONTEXTS = [
    ("{N} ответил на вопросы журналистов после заседания.", "The {T} answered journalists' questions after the meeting."),
    ("{N} прибыл на переговоры вместе с делегацией.", "The {T} arrived at the negotiations with the delegation."),
    ("По словам источника, {N} поддержал компромиссный вариант.", "According to a source, the {T} supported the compromise option."),
    ("В ходе визита {N} встретился с представителями региона.", "During the visit, the {T} met with regional representatives."),
    ("Позднее {N} пояснил позицию своей стороны.", "Later, the {T} explained his side's position."),
]

ADJECTIVE_CONTEXTS = [
    ("{M} вопрос вынесли на отдельное обсуждение.", "The {T} issue was put up for separate discussion."),
    ("Комитет подготовил {M} доклад к заседанию.", "The committee prepared a {T} report for the session."),
    ("Ведомство опубликовало {N} заявление вечером.", "The agency published a {T} statement in the evening."),
    ("Участники отметили {F} роль региональных партнёров.", "The participants noted the {T} role of regional partners."),
    ("В проект включили {P} меры поддержки.", "The project included {T} support measures."),
    ("Аналитики назвали ситуацию {F} для всего рынка.", "Analysts called the situation {T} for the whole market."),
]

VERB_CONTEXTS = [
    ("Делегации удалось {INF} до конца встречи.", "The delegation managed to {T} before the end of the meeting."),
    ("Комитет предложил {INF} после консультаций.", "The committee proposed to {T} after consultations."),
    ("Стороны договорились {INF} без дополнительных условий.", "The parties agreed to {T} without additional conditions."),
    ("Эксперты считают, что необходимо {INF} заранее.", "Experts believe it is necessary to {T} in advance."),
    ("Власти намерены {INF} в ближайшие месяцы.", "The authorities intend to {T} in the coming months."),
    ("Рабочая группа продолжит {INF} на следующем этапе.", "The working group will continue to {T} at the next stage."),
]


def cap(word: str) -> str:
    return word[:1].upper() + word[1:] if word else word


def noun_example(table: dict, nom_unstressed: str, translation: str, animate: bool, idx: int):
    forms = table_forms(table) + [nom_unstressed]
    if animate:
        ru, en = ANIMATE_CONTEXTS[idx % len(ANIMATE_CONTEXTS)]
        fallback = (
            ru.replace("{N}", cap(nom_unstressed)),
            en.replace("{T}", term_en(translation)),
        )
    else:
        ru, en = INANIMATE_CONTEXTS[idx % len(INANIMATE_CONTEXTS)]
        acc = table.get("ACC_SG") or table.get("ACC_PL") or nom_unstressed
        gen = table.get("GEN_SG") or table.get("GEN_PL") or nom_unstressed
        ins = table.get("INS_SG") or table.get("INS_PL") or nom_unstressed
        prep = table.get("PREP_SG") or table.get("PREP_PL") or nom_unstressed
        fallback = (
            ru.replace("{A}", acc)
              .replace("{G}", gen)
              .replace("{I}", ins)
              .replace("{P}", prep),
            en.replace("{T}", term_en(translation)),
        )
    return corpus_or_fallback(forms, fallback, term_en(translation))


def adjective_example(table: dict, citation: str, translation: str, idx: int):
    lemma = strip_stress(citation)
    ru, en = ADJECTIVE_CONTEXTS[idx % len(ADJECTIVE_CONTEXTS)]
    fallback = (
        ru.replace("{M}", table.get("NOM_M_SG", lemma))
          .replace("{F}", table.get("NOM_F_SG", lemma))
          .replace("{N}", table.get("NOM_N_SG", lemma))
          .replace("{P}", table.get("NOM_PL", lemma)),
        en.replace("{T}", term_en(translation)),
    )
    return corpus_or_fallback(table_forms(table) + [lemma], fallback, term_en(translation))


def verb_example(citation: str, translation: str, idx: int):
    inf = strip_stress(citation)
    core_en = translation[3:] if translation.startswith("to ") else translation
    ru, en = VERB_CONTEXTS[idx % len(VERB_CONTEXTS)]
    fallback = (
        ru.replace("{INF}", inf),
        en.replace("{T}", core_en),
    )
    return corpus_or_fallback([inf], fallback, core_en)


def noun_rows():
    rows = []
    for i, (citation, cls, translation, animate, numbers) in enumerate(wl.NOUNS):
        nums = tuple(numbers.split("+"))
        if cls.startswith("pl_"):
            table = decline_plurale_tantum(citation, wl.PLURALE_TANTUM[cls])
            nom = table["NOM_PL"]
        elif cls == "indecl":
            base = strip_stress(citation)
            keys = ["NOM_SG", "GEN_SG", "DAT_SG", "ACC_SG", "INS_SG", "PREP_SG"]
            table = {k: base for k in keys}
            nom = base
        else:
            table = decline_noun(citation, cls, animate=animate, numbers=nums)
            nom = table.get("NOM_SG") or table.get("NOM_PL")
        ex_ru, ex_en = noun_example(table, nom, translation, animate, i)
        lemma = strip_stress(citation)
        rank = domain_rank(lemma, 2000 + i)
        rows.append({
            "russian": citation,
            "lemma": lemma,
            "pos": "noun",
            "translation": translation,
            "gender": GENDER_BY_CLASS[cls],
            "declensionJson": table,
            "domainFreqRank": rank,
            "generalFreqRank": 1000 + i * 7,
            "exampleSentence": ex_ru,
            "exampleTranslation": ex_en,
            "tier": 2,
            "cefrLevel": tier2_cefr_level(rank),
            "tags": "domain noun",
        })
    return rows


def adjective_rows(start_rank: int):
    rows = []
    for i, (citation, translation) in enumerate(wl.ADJECTIVES):
        table = decline_adjective(citation)
        lemma = strip_stress(citation)
        ex_ru, ex_en = adjective_example(table, citation, translation, i)
        rank = domain_rank(lemma, start_rank + i)
        rows.append({
            "russian": citation,
            "lemma": lemma,
            "pos": "adjective",
            "translation": translation,
            "gender": "M",
            "declensionJson": table,
            "domainFreqRank": rank,
            "generalFreqRank": 2000 + i * 9,
            "exampleSentence": ex_ru,
            "exampleTranslation": ex_en,
            "tier": 2,
            "cefrLevel": tier2_cefr_level(rank),
            "tags": "domain adjective",
        })
    return rows


def _verb_example(ipf_past: str, pf_past: str, translation: str):
    ru = f"Совет {ipf_past} вопрос, а затем {pf_past} его."
    en = f"The council was {translation} the issue, and then {translation} it."
    return ru, en


def verb_rows(start_rank: int):
    rows = []
    rank = start_rank
    entries = [(e, False) for e in wl.VERB_PAIRS] + [(e, True) for e in wl.MOTION_VERBS]
    for entry, is_motion in entries:
        ipf, pf, translation, akt_ipf, akt_pf = entry[0], entry[1], entry[2], entry[3], entry[4]
        flags = set(entry[5:]) if len(entry) > 5 else set()
        base_tags = "domain verb" + (" motion" if is_motion else "")
        if "bi" in flags:
            # biaspectual single verb
            rows.append(_verb_note(ipf, "", translation, akt_ipf, "BI", None,
                                   base_tags + " biaspectual", rank))
            rank += 1
            continue
        if "bi-skip" in flags or not pf:
            # unpaired / multiword verb: vocab only, no aspect drill partner
            rows.append(_verb_note(ipf, "", translation, akt_ipf, "IPF", None,
                                   base_tags + " no_aspect_pair", rank))
            rank += 1
            continue
        ipf_lemma, pf_lemma = strip_stress(ipf), strip_stress(pf)
        rows.append(_verb_note(ipf, "", translation, akt_ipf, "IPF", pf_lemma, base_tags, rank))
        rows.append(_verb_note(pf, "", translation, akt_pf, "PF", ipf_lemma, base_tags, rank + 1))
        rank += 2
    return rows


def _verb_note(citation, _unused, translation, aktionsart, aspect, partner_lemma, tags, rank):
    inf = strip_stress(citation)
    ex_ru, ex_en = verb_example(citation, translation, rank)
    present_forms = present_forms_for(inf)
    domain_freq = domain_rank(inf, 2000 + rank)
    note = {
        "russian": citation,
        "lemma": inf,
        "pos": "verb",
        "translation": translation,
        "aspect": aspect,
        "aktionsart": aktionsart,
        "aktionsartConfidence": "manual",
        "domainFreqRank": domain_freq,
        "generalFreqRank": 1500 + rank,
        "exampleSentence": ex_ru,
        "exampleTranslation": ex_en,
        "tier": 2,
        "cefrLevel": tier2_cefr_level(domain_freq),
        "tags": tags,
    }
    if present_forms:
        note["declensionJson"] = {"verbForms": present_forms}
    if partner_lemma:
        note["aspectPartner"] = partner_lemma
    return note


READER_TEXTS = [
    {
        "title": "Граница и переговоры (graded)",
        "source": "graded:domain",
        "body": "Войска стоят у границы. Совет обсудил новые санкции против государства. "
                "Министр выступил с заявлением и предложил начать переговоры. "
                "Стороны подписали соглашение и приняли резолюцию.",
    },
    {
        "title": "Военная доктрина (graded)",
        "source": "graded:domain",
        "body": "Доктрина описывает стратегию обороны и сдерживания. "
                "Командование развернуло войска и усилило безопасность границы. "
                "Армия провела учения, а правительство сократило военный бюджет.",
    },
    {
        "title": "Дипломатический брифинг (target sample)",
        "source": "target:tass",
        "body": "Президент и министр обсудили кризис и угрозу эскалации. "
                "Делегация прибыла на саммит, чтобы провести переговоры и заключить договор. "
                "Совет принял решение ввести санкции, но стороны достигли компромисса "
                "и подтвердили намерение поддержать урегулирование конфликта.",
    },
    {
        "title": "Санкции и экономика (target sample)",
        "source": "target:kommersant",
        "body": "Правительство объявило о новых пошлинах и ограничило экспорт нефти и газа. "
                "Корпорация потеряла доступ к рынку, а инфляция увеличила давление на бюджет. "
                "Министерство заявило, что готово рассмотреть инициативу и обеспечить стабильность.",
    },
]


def all_reader_texts():
    """Graded seed texts plus the authentic-style target-source passages."""
    texts = list(READER_TEXTS)
    try:
        from reader_texts import EXTENDED_READER_TEXTS
        texts += EXTENDED_READER_TEXTS
    except ImportError:
        pass
    return texts


# Cumulative CEFR frequency benchmarks used to label the promoted course band.
# Beyond the last threshold (5500, "C1") falls through to "C2" — see
# promote_to_course. The A1-C1 hand-authored spine (a1_starter..c1_starter)
# covers structured units at each level; this frequency tail extends the same
# gated course into C1/C2 breadth using verified deck data, no new authorship.
CEFR_BY_RANK = ((800, "A1"), (1500, "A2"), (2750, "B1"), (4500, "B2"), (5500, "C1"))


def promote_to_course(general, target=6500, per_unit=40, start_unit=100):
    """Promote the top-frequency reading-matrix words into the gated tier-0 course.

    The original frequency layer is "sorted by frequency" but not "gated by unit" —
    it's a flat dictionary. This lifts the top [target] words into the structured
    course: frequency-sorted, assigned to numbered units (so the app gates them like
    course material) and CEFR levels (by frequency benchmark). They REUSE the
    deck-verified gloss/example/declension — no generated Russian, honoring
    correctness-first — and keep the "matrix" tag, so the morphology-drill guard
    (see cardsFor) still skips them: they become vocab/comprehension study cards
    whose tables serve the reader index only. Returns (promoted, remaining).
    """
    ranked = sorted(general, key=lambda g: g.get("generalFreqRank") or 10 ** 9)
    promoted, remaining = ranked[:target], ranked[target:]
    for i, g in enumerate(promoted):
        g["tier"] = 0
        g["unit"] = start_unit + i // per_unit
        rank = g.get("generalFreqRank") or 10 ** 9
        g["cefrLevel"] = next((lvl for thresh, lvl in CEFR_BY_RANK if rank <= thresh), "C2")
    return promoted, remaining


def write_jsonl(path: Path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    # Sync providers can briefly lock an existing generated asset immediately
    # after the initial build. Write a sibling file and atomically replace it,
    # retrying only the short replace window instead of losing the whole rebuild.
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as fh:
            for row in rows:
                fh.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
        for attempt in range(10):
            try:
                os.replace(temporary, path)
                break
            except OSError:
                if attempt == 9:
                    raise
                time.sleep(0.25 * (attempt + 1))
    finally:
        temporary.unlink(missing_ok=True)


def finalize_notes(notes):
    """Enrich incomplete notes from cited examples, then remove exact duplicates."""
    examples_path = HERE / "lexical_examples.json"
    examples = json.loads(examples_path.read_text(encoding="utf-8")) if examples_path.exists() else {}
    verified_path = HERE / "lexical_verified_identities.json"
    verified = set(json.loads(verified_path.read_text(encoding="utf-8"))) if verified_path.exists() else set()

    def key(note):
        lemma = note.get("lemma", "")
        if lemma.startswith("tb_"):
            lemma = lemma[3:]
        if not lemma or " " in lemma or "(" in lemma:
            lemma = note.get("russian", lemma).split("/")[0]
        return normalize_text(lemma).strip()

    unique = []
    identities = set()
    lemmas = set()
    for note in notes:
        verified_identity = json.dumps(
            [normalize_text(note.get("lemma", "")), note.get("pos", ""), note.get("translation", "").strip().lower()],
            ensure_ascii=False, separators=(",", ":"),
        )
        if note.get("pos") != "lesson" and verified and verified_identity not in verified:
            # Textbook rows are freshly re-verified in the next rebuild step.
            # Rejecting a corrected gloss here removed it from the initial asset,
            # so verify_lexicon never got a chance to approve the correction.
            if not note.get("authored") and str(note.get("lemma", "")) not in TEXTBOOK_REVERIFY_LEMMAS:
                continue
        textbook_example = TEXTBOOK_EXAMPLE_OVERRIDES.get(key(note)) if str(note.get("lemma", "")).startswith("tb_") else None
        if textbook_example:
            note["exampleSentence"] = textbook_example["ru"]
            note["exampleTranslation"] = textbook_example["en"]
            note["exampleSource"] = textbook_example["source"]
            note["exampleReference"] = textbook_example["reference"]
        elif note.get("pos") != "lesson" and (not note.get("exampleSentence") or not note.get("exampleTranslation")):
            example = examples.get(key(note))
            if example:
                note["exampleSentence"] = example["ru"]
                note["exampleTranslation"] = example["en"]
                note["exampleSource"] = example["source"]
                note["exampleReference"] = example["reference"]
        if note.get("pos") != "lesson" and (not note.get("exampleSentence") or not note.get("exampleTranslation")):
            continue
        identity = (normalize_text(note.get("lemma", "")), note.get("pos", ""), note.get("translation", "").lower())
        lemma_key = normalize_text(note.get("lemma", ""))
        if identity in identities or (note.get("pos") != "lesson" and lemma_key in lemmas):
            continue
        identities.add(identity)
        if note.get("pos") != "lesson":
            lemmas.add(lemma_key)
        note.pop("authored", None)
        unique.append(note)
    # A verb's aspect partner can independently fail lexicon verification
    # (above) or dedup out, leaving the surviving half pointing at a lemma
    # that was never shipped. The app no-ops gracefully on that (no crash),
    # but it's still a dangling reference in shipped data — drop it here so
    # every aspectPartner resolves to a real note, regardless of which half
    # of the pair happened to survive verification.
    surviving_lemmas = {normalize_text(n.get("lemma", "")) for n in unique}
    for note in unique:
        partner = note.get("aspectPartner")
        if partner and normalize_text(partner) not in surviving_lemmas:
            del note["aspectPartner"]
    return unique


def _split_top_level(text: str, seps: str) -> list[str]:
    """Split on any char in `seps`, but never inside (...) or [...] — a gloss
    like "to go (on foot, now)" must not be split at the comma that's part of
    the parenthetical qualifier, or downstream code (mnemonic/secondSense
    derivation) mistakes the parenthetical's tail for a whole extra sense."""
    parts = []
    depth = 0
    current = []
    for ch in text:
        if ch in "([":
            depth += 1
            current.append(ch)
        elif ch in ")]":
            depth = max(0, depth - 1)
            current.append(ch)
        elif ch in seps and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    parts.append("".join(current))
    return parts


# Generated source fallbacks for these lemmas have a verified, sense-aligned
# candidate in mined_examples.json. Promote that sourced sentence to the primary
# teaching example instead of keeping it only as optional context 2/3.
MINED_PRIMARY_REPAIRS = {
    "общий", "спать", "удар", "замок", "будущее", "очередной", "норма",
    "спешить", "печать", "взрослый", "ведущий", "тепло", "цивилизация",
    "пачка", "коэффициент", "яма", "владыка", "ванная", "рай", "воплощение",
    "октябрьский", "балет", "скот", "осколок", "выпускник", "индекс",
    "спичка", "примитивный", "матушка", "речной", "героический", "палач",
    "приморский", "противодействие", "классовый", "дефект", "профессионализм",
    "навещать", "воспроизводство", "открытость", "заметать", "княжна",
    "незамеченный", "помещик", "сговор", "неравенство", "презумпция",
    "племянница",
}


def apply_phase3_enrichment(notes):
    """Merge mined variety and derive morphology-backed compatibility fields."""
    mined_path = HERE / "mined_examples.json"
    mined = json.loads(mined_path.read_text(encoding="utf-8")) if mined_path.exists() else {}
    try:
        import pymorphy3
        from build_paradigms import legacy_key, norm as paradigm_norm
        morph = pymorphy3.MorphAnalyzer()
    except Exception:
        morph = None
        paradigm_norm = normalize_text
        legacy_key = lambda _: None
    polysemes = 0
    for note in notes:
        if note.get("pos") == "lesson":
            continue
        stored_lemma = note.get("lemma") or note.get("russian", "")
        morphology_lemma = stored_lemma[3:] if stored_lemma.startswith("tb_") else stored_lemma
        lemma = paradigm_norm(morphology_lemma)
        candidates = mined.get(lemma, [])
        if (
            note.get("exampleSource") == "generated-quality-fallback"
            and lemma in MINED_PRIMARY_REPAIRS
            and candidates
        ):
            candidate = candidates[0]
            note["exampleSentence"] = candidate["ru"]
            note["exampleTranslation"] = candidate["en"]
            note["exampleSource"] = "Tatoeba via bundled content database"
            note["exampleReference"] = f"Tatoeba sentence {candidate['sentenceId']}"
        current = {normalize_text(note.get(k, "")) for k in ("exampleSentence", "exampleSentence2", "exampleSentence3") if note.get(k)}
        slots = [("exampleSentence2", "exampleTranslation2"), ("exampleSentence3", "exampleTranslation3")]
        for candidate in candidates:
            normalized = normalize_text(candidate["ru"])
            if normalized in current:
                continue
            slot = next(((ru, en) for ru, en in slots if not note.get(ru)), None)
            if slot is None:
                break
            note[slot[0]], note[slot[1]] = candidate["ru"], candidate["en"]
            current.add(normalized)
        # Corpus coverage is finite. For the remaining course lemmas, compose a
        # discourse frame around the verified authored example so runtime rotation
        # still changes the retrieval context without inventing lexical content.
        if note.get("tier") == 0 and not note.get("exampleSentence2") and note.get("exampleSentence"):
            quoted = note["exampleSentence"].strip()
            translated = note.get("exampleTranslation", "").strip()
            note["exampleSentence2"] = f"Он сказал: «{quoted}»"
            note["exampleTranslation2"] = f"He said: “{translated}”"
        if note.get("tier") == 0 and note.get("cefrLevel") in {"A1", "A2"} and not note.get("mnemonic"):
            meaning = _split_top_level(note.get("translation", ""), ",")[0].strip()
            note["mnemonic"] = f"Picture {meaning or 'the meaning'} saying «{note.get('russian', lemma)}» out loud."[:120]
        # pymorphy3 analyzes single tokens; feeding it a multi-word idiom (e.g.
        # "принять во внимание") makes it silently match the last word's
        # normal_form and inflect the *whole phrase* using that word's suffix
        # rules, producing nonsense forms like "принять во вниманием". Skip
        # morphology entirely for fixed multi-word expressions.
        if morph is not None and " " not in lemma:
            # Parse on the note's original spelling, not the normalized
            # `lemma` -- pymorphy3's dictionary is keyed on precomposed
            # text, and even with norm() recomposing its own output, a
            # defensive raw-spelling parse (matching build_paradigms.py's
            # documented safe pattern) avoids ever depending on that.
            raw_spelling = morphology_lemma
            parses = [p for p in morph.parse(raw_spelling) if paradigm_norm(p.normal_form) == lemma]
            if parses:
                parse = parses[0]
                # pymorphy3's lexeme can list a non-standard-register variant
                # (Litr=literary/archaic, Infr=informal/colloquial, Slng)
                # for a case/number slot BEFORE the standard form -- e.g.
                # ACC_SG of "огонь" lists archaic "огнь" ahead of "огонь",
                # and setdefault() would lock onto whichever comes first.
                # Two passes: standard forms always win when both exist, but
                # a variant still fills a key that has no standard form.
                REGISTER_VARIANT_GRAMMEMES = {"Litr", "Infr", "Slng"}
                table = {}
                variant_forms = []
                for form in parse.lexeme:
                    key = legacy_key(form.tag)
                    if not key:
                        continue
                    if any(g in form.tag for g in REGISTER_VARIANT_GRAMMEMES):
                        variant_forms.append((key, form.word))
                        continue
                    table.setdefault(key, form.word)
                for key, word in variant_forms:
                    table.setdefault(key, word)
                if table:
                    note["declensionJson"] = {"verbForms": table} if str(parse.tag.POS) in {"VERB", "INFN"} else table
                if note.get("pos", "").lower().startswith("verb") and not note.get("aspect"):
                    note["aspect"] = "PF" if "perf" in parse.tag else "IPF" if "impf" in parse.tag else None
        if polysemes < 300 and not note.get("secondSense"):
            senses = [s.strip() for s in _split_top_level(note.get("translation", ""), ",;/") if s.strip()]
            if len(senses) >= 2:
                note["secondSense"] = senses[1]
                note["secondSenseExample"] = note.get("exampleSentence2") or note.get("exampleSentence")
                note["secondSenseExampleTranslation"] = note.get("exampleTranslation2") or note.get("exampleTranslation")
                polysemes += 1
    return notes


def main():
    nouns = noun_rows()
    adjs = adjective_rows(start_rank=100 + len(wl.NOUNS))
    verbs = verb_rows(start_rank=400)
    domain = nouns + adjs + verbs
    domain_lemmas = {n["lemma"] for n in domain}

    # Progressive CEFR course (tier 0): A1→C1, concrete and fully readable, with a
    # teach-before-test grammar spine (lesson notes). This is what a learner meets
    # first, level by level, before the general matrix and political/security domain.
    # A shared `seen` set dedups across levels so a word is taught only once.
    a1_notes = []
    a1_readers = []
    a1_lemmas = set()
    try:
        from curriculum_common import build_level, spine2_rows
        import a1_starter, a2_starter, b1_starter, b2_starter, c1_starter, c2_starter
        seen = set()
        a1_notes = (
            a1_starter.a1_foundation_rows()
            + build_level(a1_starter.UNITS, "A1", seen, rank_start=len(a1_starter.a1_foundation_rows()))
            + build_level(a2_starter.UNITS, "A2", seen)
            + build_level(b1_starter.UNITS, "B1", seen)
            + build_level(b2_starter.UNITS, "B2", seen)
            + build_level(c1_starter.UNITS, "C1", seen)
            + build_level(c2_starter.UNITS, "C2", seen)
            + spine2_rows()
        )
        for n in a1_notes:
            n["authored"] = True
        a1_readers = (
            a1_starter.a1_reader_texts() + a2_starter.a2_reader_texts()
            + b1_starter.b1_reader_texts() + b2_starter.b2_reader_texts()
            + c1_starter.c1_reader_texts() + c2_starter.c2_reader_texts()
        )
        a1_lemmas = {n["lemma"] for n in a1_notes if n.get("pos") != "lesson"}
    except ImportError:
        pass

    # General reading-matrix layer (function words + common vocab) from the
    # Anki deck. Deduped against the domain corpus; sequencing unified through
    # the domain frequency list so function words sort ahead of domain content.
    general = []
    try:
        from general_layer import general_rows
        # Don't duplicate A1 starter words in the general layer.
        general = general_rows(domain_lemmas | a1_lemmas)
        for g in general:
            g["tier"] = 1
            r = DOMAIN_FREQ.get(g["lemma"])
            if r is not None:
                g["domainFreqRank"] = r
    except ImportError:
        pass

    # Promote the top-frequency words into the gated tier-0 course (frequency-sorted,
    # unit-gated, CEFR-labelled). The hand-authored spine (a1_notes) leads with
    # controlled comprehensible input; the promoted band extends the course to ~5k
    # words using deck-verified data.
    promoted, general = promote_to_course(general)

    # The remaining (non-promoted) general-matrix words never left tier 1, so
    # promote_to_course's per-word CEFR tagging never touched them either. Tag them
    # too, by the same frequency benchmark, so "no cefrLevel at all" never becomes a
    # way for content to dodge CEFR-aware gating — vocab-only reading fuel with no
    # grammar drills still deserves an honest level for display and pacing.
    for g in general:
        rank = g.get("generalFreqRank") or 10 ** 9
        g["cefrLevel"] = next((lvl for thresh, lvl in CEFR_BY_RANK if rank <= thresh), "C2")

    course = a1_notes + promoted
    notes = course + domain + general

    # Reader-coverage supplement: content lemmas that appear in the bundled reader
    # texts but were missing from the deck. Deduped against everything above; the app's
    # morphology then resolves all their inflected forms for full offline coverage.
    try:
        from reader_supplement import supplement_rows
        existing_lemmas = {n["lemma"] for n in notes}
        supplement = supplement_rows(existing_lemmas)
        for r in supplement:
            rank = DOMAIN_FREQ.get(r["lemma"])
            if rank is not None:
                r["domainFreqRank"] = rank
            # Reader-coverage words are pulled in only because they appear in the
            # bundled (mostly domain-flavored) reader texts, with no frequency
            # signal of their own to band by — tag them C1 rather than leave them
            # untagged; they're vocab-only (no grammar drills) so the risk of an
            # imprecise level is low, but "no tag at all" is worse than an estimate.
            r.setdefault("cefrLevel", "C1")
        notes = notes + supplement
    except ImportError:
        supplement = []

    # Local Между нами textbooks (four PDFs in ~/Documents when available). These
    # are activity/workbook books, so the ingestion (a) mines only the words the book
    # itself glosses inline in English — real translations, nothing invented — into
    # vocab notes, and (b) reconstructs the embedded narrative into level-tagged
    # graded reader passages. Vocab is deduped against the curated deck by surface
    # form so the textbook never re-teaches a word the course already owns.
    textbook_notes = []
    textbook_readers = []
    try:
        from textbook_ingest import textbook_reader_texts, textbook_rows
        existing = {n["lemma"].lower().replace("ё", "е") for n in notes}

        def _tb_lemma(note):
            # Textbook lemmas are namespaced "tb_<dictionary lemma>"; compare the bare
            # dictionary lemma (and the surface, for the coordinated-phrase notes whose
            # lemma is the phrase itself) against the curated deck so the textbook never
            # re-teaches — in any inflection — a word the course already owns.
            lemma = note["lemma"]
            base = lemma[3:] if lemma.startswith("tb_") else lemma
            return base.lower().replace("ё", "е")

        textbook_notes = [n for n in textbook_rows() if _tb_lemma(n) not in existing]
        textbook_readers = textbook_reader_texts()
        notes = notes + textbook_notes
    except ImportError:
        pass

    notes = apply_phase3_enrichment(finalize_notes(notes))
    reader_texts = a1_readers + all_reader_texts() + textbook_readers
    write_jsonl(ASSETS / "bootstrap_notes.jsonl", notes)
    write_jsonl(ASSETS / "bootstrap_reader_texts.jsonl", reader_texts)
    from build_stories import build as build_stories
    story_result = build_stories(
        HERE / "stories", ASSETS / "bootstrap_notes.jsonl", ASSETS / "bootstrap_reader_texts.jsonl"
    )
    print(f"story chapters appended: {story_result['appended']}")
    from build_curriculum_metadata import main as build_curriculum_metadata
    build_curriculum_metadata()
    notes_bytes = (ASSETS / "bootstrap_notes.jsonl").read_bytes()
    checksum = hashlib.sha256(notes_bytes).hexdigest()
    counts = {}
    tiers = {}
    for note in notes:
        band = note.get("cefrLevel") or "UNSPECIFIED"
        counts[band] = counts.get(band, 0) + 1
        tier = str(note.get("tier", 0))
        tiers[tier] = tiers.get(tier, 0) + 1
    def asset_version(name):
        path = HERE / name
        return hashlib.sha256(path.read_bytes()).hexdigest()[:16] if path.exists() else "absent"
    manifest = {
        "curriculumVersion": f"2026-07-g12-{checksum[:8]}",
        "schemaVersion": 2,
        "contentChecksum": checksum,
        "provenance": {
            "generatedBy": "SibirskySpeak offline curriculum pipeline",
            "sources": [
                {
                    "id": "tatoeba",
                    "attribution": "Example sentences from Tatoeba via OPUS",
                    "license": "CC-BY 2.0 FR",
                },
                {
                    "id": "wiktionary",
                    "attribution": "Lexical verification from Wiktionary via Kaikki",
                    "license": "CC BY-SA 4.0",
                },
                {
                    "id": "graded-curriculum",
                    "attribution": "SibirskySpeak graded curriculum and lesson authoring",
                    "license": "Project content",
                },
            ],
        },
        "noteCountsByBand": dict(sorted(counts.items())),
        "noteCountsByTier": dict(sorted(tiers.items())),
        "assets": {
            "frames": asset_version("frames.json"),
            "stories": asset_version("stories/anna_i_ivan_a1.json") + ":" + asset_version("stories/maria_i_petr_a2.json"),
            "dialogues": asset_version("dialogues.json"),
            "units": asset_version("units.yaml"),
            "phonology": asset_version("phonology.json"),
            "transformations": asset_version("transformations.json"),
        },
    }
    (ASSETS / "curriculum_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    nominal = len(nouns) + len(adjs)
    aspect_ready = sum(1 for v in verbs if "aspectPartner" in v)
    a1_lessons = sum(1 for n in a1_notes if n.get("pos") == "lesson")
    by_level = {}
    for n in course:
        by_level[n.get("cefrLevel")] = by_level.get(n.get("cefrLevel"), 0) + 1
    print(f"notes: {len(notes)}  (tier-0 course={len(course)} [spine={len(a1_notes)} + "
          f"promoted={len(promoted)}], lessons={a1_lessons}, levels={by_level}; "
          f"domain={len(domain)}: nominal={nominal}, verbs={len(verbs)}, "
          f"aspect-ready={aspect_ready}; general(reading-fuel)={len(general)}; "
          f"textbook phrases={len(textbook_notes)})")
    print(f"reader texts: {len(reader_texts)}")
    if textbook_readers:
        print(f"textbook reader/practice texts: {len(textbook_readers)}")
    print(f"wrote -> {ASSETS}")


if __name__ == "__main__":
    main()
