"""Build the app's bootstrap_notes.jsonl / bootstrap_reader_texts.jsonl from the
curated domain wordlist using the rule-based declension engine.

Usage:
    python tools/preprocess/build_bootstrap.py

Writes real, studyable content to app/src/main/assets/. Every emitted line
matches the Android import contract (see design doc §13.5).
"""
from __future__ import annotations

import json
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

INANIMATE_TEMPLATES = [
    ("{C} имеет большое значение в этом вопросе.", "{T} has great importance in this matter."),
    ("{C} обсуждается в официальном заявлении.", "{T} is discussed in the official statement."),
    ("{C} упоминается в новом документе.", "{T} is mentioned in the new document."),
    ("{C} стало предметом переговоров.", "{T} became a subject of the negotiations."),
]
ANIMATE_TEMPLATES = [
    ("{C} выступил с заявлением.", "The {T} made a statement."),
    ("{C} принял участие в переговорах.", "The {T} took part in the negotiations."),
]


def cap(word: str) -> str:
    return word[:1].upper() + word[1:] if word else word


def example_for(nom_unstressed: str, translation: str, animate: bool, idx: int):
    tpls = ANIMATE_TEMPLATES if animate else INANIMATE_TEMPLATES
    ru, en = tpls[idx % len(tpls)]
    return (ru.replace("{C}", cap(nom_unstressed)),
            en.replace("{T}", translation.split("/")[0]))


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
        ex_ru, ex_en = example_for(nom, translation, animate, i)
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
            "tags": "domain noun",
        })
    return rows


def adjective_rows(start_rank: int):
    rows = []
    for i, (citation, translation) in enumerate(wl.ADJECTIVES):
        table = decline_adjective(citation)
        lemma = strip_stress(citation)
        rows.append({
            "russian": citation,
            "lemma": lemma,
            "pos": "adjective",
            "translation": translation,
            "gender": "M",
            "declensionJson": table,
            "domainFreqRank": domain_rank(lemma, start_rank + i),
            "generalFreqRank": 2000 + i * 9,
            "exampleSentence": f"{cap(strip_stress(citation))} фактор учитывается в стратегии.",
            "exampleTranslation": f"The {translation.split('/')[0]} factor is taken into account in the strategy.",
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
    core_en = translation[3:] if translation.startswith("to ") else translation
    # multiword verbs (e.g. "вводить санкции") already carry their own object
    frame_ru = f"Сторонам важно {inf} вовремя." if " " in inf else f"Сторонам важно {inf} этот вопрос вовремя."
    frame_en = f"It is important for the parties to {core_en} in time."
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
        # Infinitive frame: grammatical for every verb regardless of aspect or
        # irregular past stem, and it keeps the target verb in citation form.
        "exampleSentence": frame_ru,
        "exampleTranslation": frame_en,
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

    # General reading-matrix layer (function words + common vocab) from the
    # Anki deck. Deduped against the domain corpus; sequencing unified through
    # the domain frequency list so function words sort ahead of domain content.
    general = []
    try:
        from general_layer import general_rows
        general = general_rows(domain_lemmas)
        for g in general:
            r = DOMAIN_FREQ.get(g["lemma"])
            if r is not None:
                g["domainFreqRank"] = r
    except ImportError:
        pass

    notes = domain + general
    reader_texts = all_reader_texts()
    write_jsonl(ASSETS / "bootstrap_notes.jsonl", notes)
    write_jsonl(ASSETS / "bootstrap_reader_texts.jsonl", reader_texts)
    nominal = len(nouns) + len(adjs)
    aspect_ready = sum(1 for v in verbs if "aspectPartner" in v)
    print(f"notes: {len(notes)}  (domain={len(domain)}: nominal={nominal}, verbs={len(verbs)}, "
          f"aspect-ready={aspect_ready}; general={len(general)})")
    print(f"reader texts: {len(reader_texts)}")
    print(f"wrote -> {ASSETS}")


if __name__ == "__main__":
    main()
