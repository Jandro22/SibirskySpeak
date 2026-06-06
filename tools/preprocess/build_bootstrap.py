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
from pathlib import Path

from russian_morph import (decline_adjective, decline_noun,
                           decline_plurale_tantum, past_masculine, strip_stress)
import domain_wordlist as wl

ASSETS = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets"
HERE = Path(__file__).resolve().parent


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

GENDER_BY_CLASS = {
    "m_hard": "M", "m_j": "M", "m_iy": "M", "m_soft": "M",
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
        rows.append({
            "russian": citation,
            "lemma": lemma,
            "pos": "noun",
            "translation": translation,
            "gender": GENDER_BY_CLASS[cls],
            "declensionJson": table,
            "domainFreqRank": domain_rank(lemma, 2000 + i),
            "generalFreqRank": 1000 + i * 7,
            "exampleSentence": ex_ru,
            "exampleTranslation": ex_en,
            "tier": 2,
            "tags": "domain noun",
        })
    return rows


def adjective_rows(start_rank: int):
    rows = []
    for i, (citation, translation) in enumerate(wl.ADJECTIVES):
        table = decline_adjective(citation)
        lemma = strip_stress(citation)
        ex_ru, ex_en = adjective_example(table, citation, translation, i)
        rows.append({
            "russian": citation,
            "lemma": lemma,
            "pos": "adjective",
            "translation": translation,
            "gender": "M",
            "declensionJson": table,
            "domainFreqRank": domain_rank(lemma, start_rank + i),
            "generalFreqRank": 2000 + i * 9,
            "exampleSentence": ex_ru,
            "exampleTranslation": ex_en,
            "tier": 2,
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
    note = {
        "russian": citation,
        "lemma": inf,
        "pos": "verb",
        "translation": translation,
        "aspect": aspect,
        "aktionsart": aktionsart,
        "aktionsartConfidence": "manual",
        "domainFreqRank": domain_rank(inf, 2000 + rank),
        "generalFreqRank": 1500 + rank,
        "exampleSentence": ex_ru,
        "exampleTranslation": ex_en,
        "tier": 2,
        "tags": tags,
    }
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


def write_jsonl(path: Path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


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
        from curriculum_common import build_level
        import a1_starter, a2_starter, b1_starter, b2_starter, c1_starter
        seen = set()
        a1_notes = (
            build_level(a1_starter.UNITS, "A1", seen)
            + build_level(a2_starter.UNITS, "A2", seen)
            + build_level(b1_starter.UNITS, "B1", seen)
            + build_level(b2_starter.UNITS, "B2", seen)
            + build_level(c1_starter.UNITS, "C1", seen)
        )
        a1_readers = (
            a1_starter.a1_reader_texts() + a2_starter.a2_reader_texts()
            + b1_starter.b1_reader_texts() + b2_starter.b2_reader_texts()
            + c1_starter.c1_reader_texts()
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

    notes = a1_notes + domain + general
    reader_texts = a1_readers + all_reader_texts()
    write_jsonl(ASSETS / "bootstrap_notes.jsonl", notes)
    write_jsonl(ASSETS / "bootstrap_reader_texts.jsonl", reader_texts)
    nominal = len(nouns) + len(adjs)
    aspect_ready = sum(1 for v in verbs if "aspectPartner" in v)
    a1_lessons = sum(1 for n in a1_notes if n.get("pos") == "lesson")
    by_level = {}
    for n in a1_notes:
        by_level[n.get("cefrLevel")] = by_level.get(n.get("cefrLevel"), 0) + 1
    print(f"notes: {len(notes)}  (curriculum={len(a1_notes)}: lessons={a1_lessons}, "
          f"levels={by_level}; domain={len(domain)}: nominal={nominal}, "
          f"verbs={len(verbs)}, aspect-ready={aspect_ready}; general={len(general)})")
    print(f"reader texts: {len(reader_texts)}")
    print(f"wrote -> {ASSETS}")


if __name__ == "__main__":
    main()
