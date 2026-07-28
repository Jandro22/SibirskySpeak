# -*- coding: utf-8 -*-
"""Transform the extracted general-vocabulary source (general_source.jsonl) into
Android-import note dicts for the reading-matrix layer.

Design intent (doc 8.1): the general list is the *reading matrix* — function
words plus common vocabulary that makes authentic text legible. Its job is
reader coverage, not grammar drilling. So:

  * Nouns get a declension table built from the deck's real (often irregular)
    case forms, which feeds the reader form index — but are tagged "general"
    so the app does NOT generate CASE_FILL grammar cards for them.
  * Adjectives are run through the regular adjective engine (adjectives are
    highly regular) for the same coverage benefit.
  * Verbs are vocab-only; the app derives regular past forms for coverage.

Deduplication: any lemma already present in the curated domain corpus wins and
the general entry is skipped. Sequencing is unified through the domain frequency
list when the lemma appears there (so function words sort first).
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from russian_morph import decline_adjective, decline_noun, strip_stress

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "general_source.jsonl"

# A handful of general_source.jsonl's scraped "example" fields are mojibake:
# genuine CP1251 Russian text that got decoded as Mac Cyrillic somewhere
# upstream, landing on Cyrillic Supplement code points (the Macedonian/Serbian
# letters, U+0400-U+045F minus the legitimate "Ё"/"ё") that never appear in
# real Russian. The corruption is a clean single-hop mis-decode — re-encoding
# as mac_cyrillic to recover the original bytes and decoding those as cp1251
# reverses it losslessly (verified against every affected row in the source
# file), so examples are repaired rather than dropped: finalize_notes() in
# build_bootstrap.py drops any non-lesson note that ends up without an
# example, and there's no reason to lose real vocabulary over a fixable
# encoding bug.
_MOJIBAKE_CYRILLIC = re.compile("[ѐђ-џЀЂ-Џ]")


_LATIN_TOKEN = re.compile(r"[A-Za-z\u0400-\u04FF]+(?:[-'][A-Za-z\u0400-\u04FF]+)*")


def _studyable_example(ru: str, en: str) -> bool:
    """Return whether a scraped example is safe to put in a learner card."""
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
    lower_ascii_words = 0
    for match in _LATIN_TOKEN.finditer(ru):
        token = match.group(0)
        has_latin = any("A" <= ch <= "Z" or "a" <= ch <= "z" for ch in token)
        if has_latin:
            letters = "".join(ch for ch in token if ch.isascii() and ch.isalpha())
            in_quote = ru[:match.start()].count('"') % 2 == 1
            if len(letters) >= 3 and not token[0].isupper() and not token.isupper() and not in_quote:
                lower_ascii_words += 1
    # Three or more ordinary lower-case English words in the Russian half
    # is the characteristic signature of a sentence boundary that was missed;
    # isolated product names/usernames (``dwm``, ``prototype.js``) remain valid.
    if lower_ascii_words >= 3:
        return False
    return True


def _repair_mojibake(text: str) -> str:
    if not _MOJIBAKE_CYRILLIC.search(text):
        return text
    try:
        return text.encode("mac_cyrillic").decode("cp1251")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return text

POS_MAP = {
    "noun": "noun", "adj.": "adjective", "adjs": "adjective", "prtf": "adjective",
    "verb": "verb", "adv.": "adverb", "conj.": "conjunction", "prep.": "preposition",
    "num.": "numeral", "prcl.": "particle", "intj.": "interjection",
    "pron.": "pronoun", "pred.": "predicative", "nonpro.": "other",
}

# general_source.jsonl's "definition" field packs a genuinely polysemous word's
# unrelated senses into one comma list (e.g. "world, peace") with a single
# example anchoring only the first — the app never gives the second sense its
# own retrieval practice or context (see ReviewPrompt.secondSenseExposure,
# which reads these fields once the card is well-established). Deliberately
# small and hand-curated rather than derived: verifying that two senses are
# genuinely *unrelated* (not near-synonyms) and authoring a correct example
# for the overlooked one takes a human, one word at a time.
SECOND_SENSE_OVERRIDES = {
    "мир": ("peace", "Он хочет мира.", "He wants peace."),
    "лук": ("onion", "Мама режет лук для супа.", "Mom is cutting an onion for the soup."),
}
# A few scraped glosses carried a second-language explanation from the source
# dictionary. Keep the learner-facing meaning in one language; the examples
# remain the evidence for the Russian sense.
TRANSLATION_OVERRIDES = {
    "из-за": "because of; owing to",
    "кафе": "café",
    "жених": "fiancé (male)",
    "диагноз": "diagnosis",
    "плотность": "density",
    "переворот": "coup d'état",
    "коммюнике": "communiqué",
    "атташе": "attaché",
    "регистрировать": "to register",
    "легкомысленный": "flippant; thoughtless; light-minded",
    "мэтр": "maître",
    "пренебрегать": "to neglect; to treat without respect",
    "спешить": "to hurry",
    "общий": "common; shared; public",
    "давно": "a long time ago; for a long time",
    "пол": "floor",
    "норма": "norm; standard",
    "партийный": "party-related; partisan",
    "вон": "there; over there; out",
    "печать": "printing; the press; seal",
    "тепло": "warm; warmly",
    "пачка": "pack; packet",
    "плёнка": "film; thin membrane",
    "ванная": "bathroom",
    "открыто": "openly; openly stated",
    "воплощение": "embodiment; realization",
    "октябрьский": "October; October-related",
    "осколок": "shard; fragment; splinter",
    "острота": "sharpness; wit",
    "экспертный": "expert; expert-level",
    "примитивный": "primitive; rudimentary",
    "матушка": "mother; mum",
    "зам": "deputy",
    "речной": "river; river-related",
    "классовый": "class-related",
    "рёв": "roar",
    "противодействие": "counteraction; resistance",
    "оформлять": "to prepare or process documents; formalize",
    "заметать": "to sweep; cover up",
    "отрабатывать": "to practice; refine; work off",
    "общечеловеческий": "universal; shared by all humanity",
    "раскинуть": "to spread out; pitch",
}

# Minimal, human-reviewed repairs for source examples that are structurally
# unusable and have no sense-aligned mined sentence. These deliberately stay
# short: a plain correct sentence is better learning evidence than an elaborate
# generated context whose case, agreement, or lexical selection may be wrong.
FALLBACK_EXAMPLE_OVERRIDES = {
    "давно": ("Мы давно знакомы.", "We have known each other for a long time."),
    "пол": ("Книга лежит на полу.", "The book is lying on the floor."),
    "партийный": ("Это партийное решение.", "This is a party decision."),
    "вон": ("Вон наш дом.", "There is our house."),
    "увы": ("Увы, поезд уже ушёл.", "Alas, the train has already left."),
    "фактически": ("Фактически работа уже закончена.", "In fact, the work is already finished."),
    "критика": ("Критика была полезной.", "The criticism was helpful."),
    "плёнка": ("Камеру заряжают плёнкой.", "The camera is loaded with film."),
    "открыто": ("Они открыто обсудили проблему.", "They discussed the problem openly."),
    "острота": ("Он известен остротой ума.", "He is known for his sharp mind."),
    "издали": ("Я увидел дом издали.", "I saw the house from a distance."),
    "экспертный": ("Нам нужна экспертная оценка.", "We need an expert assessment."),
    "зам": ("Зам директора подписал документ.", "The deputy director signed the document."),
    "бережно": ("Он бережно хранил письмо.", "He kept the letter with care."),
    "воробей": ("На ветке сидит воробей.", "A sparrow is sitting on the branch."),
    "закупка": ("Компания начала закупку оборудования.", "The company began purchasing equipment."),
    "райком": ("Райком обсудил новый план.", "The district committee discussed the new plan."),
    "даром": ("Эту книгу отдали даром.", "This book was given away for free."),
    "рёв": ("Мы услышали рёв мотора.", "We heard the roar of the engine."),
    "профилактика": ("Профилактика помогает предотвратить болезнь.", "Prevention helps avert illness."),
    "реорганизация": ("Компания начала реорганизацию.", "The company began a reorganization."),
    "рудник": ("Рудник закрыли на ремонт.", "The mine was closed for repairs."),
    "оформлять": ("Она помогает оформлять документы.", "She helps prepare the documents."),
    "полпред": ("Полпред прибыл на встречу.", "The plenipotentiary arrived for the meeting."),
    "фанерный": ("На окне стоял фанерный щит.", "A plywood panel covered the window."),
    "беглый": ("Полиция ищет беглого заключённого.", "The police are looking for the escaped prisoner."),
    "синева": ("Синева неба отражалась в воде.", "The blue of the sky was reflected in the water."),
    "отрабатывать": ("Мы отрабатываем новый навык.", "We are practicing a new skill."),
    "присвоение": ("Суд рассмотрел дело о присвоении денег.", "The court considered a case of misappropriated funds."),
    "общечеловеческий": ("Это общечеловеческая ценность.", "This is a universal human value."),
    "духовность": ("Для него духовность важна.", "Spirituality is important to him."),
    "раскинуть": ("Они раскинули палатку у реки.", "They pitched a tent by the river."),
    "подворотня": ("Они встретились в подворотне.", "They met in the passageway."),
    "навеки": ("Он навеки запомнил этот день.", "He remembered that day forever."),
    "вибрация": ("Вибрация двигателя стала сильнее.", "The engine vibration became stronger."),
}
GENDER_MAP = {"masc": "M", "femn": "F", "neut": "N"}


def _norm(citation: str) -> str:
    return (citation.strip().lower()
            .replace("́", "").replace("̀", "").replace("́", "")
            .replace("ё", "е"))


def _pos(raw: str) -> str:
    return POS_MAP.get(raw.strip().lower(), "other")


def _partial_table(word, gen_sg, gen_pl, prep_sg, nom_pl):
    """Partial declension table from the deck's real (stress-stripped) forms.
    Correct including suppletives (человек -> люди), but only the forms the deck
    supplies. Used when the rule engine can't be trusted for this noun."""
    def s(v):
        return strip_stress(v) if v else ""
    table = {"NOM_SG": s(word)}
    if gen_sg:  table["GEN_SG"] = s(gen_sg)
    if prep_sg: table["PREP_SG"] = s(prep_sg)
    if nom_pl:  table["NOM_PL"] = s(nom_pl)
    if gen_pl:  table["GEN_PL"] = s(gen_pl)
    return table


def _guess_class(lemma: str, gender: str):
    """Guess a declension class from ending + gender. Returns None for shapes
    the engine shouldn't attempt (irregular -мя, etc.)."""
    w = lemma
    if gender == "N":
        if w.endswith("мя"):       return None          # время/имя: irregular
        if w.endswith("ие"):       return "n_ie"
        if w.endswith("ье"):       return "n_e"
        if w.endswith("е"):        return "n_e"
        if w.endswith("о"):        return "n_o"
        return None
    if gender == "M":
        if w.endswith("ий"):       return "m_iy"
        if w.endswith("й"):        return "m_j"
        if w.endswith("ь"):        return "m_soft"
        if w[-1:] in "бвгджзклмнпрстфхцчшщ":  return "m_hard"
        return None
    if gender == "F":
        if w.endswith("ия"):       return "f_iya"
        if w.endswith("ья"):       return "f_ya"
        if w.endswith("я"):        return "f_ya"
        if w.endswith("а"):        return "f_a"
        if w.endswith("ь"):        return "f_soft"
        return None
    return None


# Substantivized adjectives: words tagged pos="noun" in the source (because
# they function as one, e.g. "рабочий" = "a worker") but that are
# structurally adjectives and MUST decline like one (рабочий -> рабочего,
# not the regular -ий noun pattern -> рабочия). _guess_class can't tell
# these apart from real -ий/-ый/-ой nouns like "герой"/"часовой" (the noun,
# hour hand) by ending alone, and the source never supplies gen_sg for them
# to validate against, so the wrong engine's non-words shipped silently.
SUBSTANTIVIZED_ADJECTIVE_NOUNS = {
    "малый", "старший", "рабочий", "мёртвый", "пьяный", "рядовой",
    "полицейский", "участковый", "часовой", "виновный", "безработный",
    "военнопленный", "придворный",
}


def _full_table(word, gender, gen_sg, gen_pl, prep_sg, nom_pl, animate=False):
    """Try a full 12-case table via the rule engine, VALIDATED against the deck's
    real forms. If the engine disagrees on any form the deck supplies (irregular
    stems, fleeting vowels, stress-shift plurals), fall back to the partial real
    table — so no incorrect form ever enters the index.
    Returns (table, used_engine)."""
    partial = _partial_table(word, gen_sg, gen_pl, prep_sg, nom_pl)
    stripped = strip_stress(word).lower()
    if stripped in SUBSTANTIVIZED_ADJECTIVE_NOUNS and gender == "M" and not nom_pl:
        try:
            adj = decline_adjective(word)
        except Exception:
            return partial, False
        keys = ("NOM_SG", "GEN_SG", "DAT_SG", "ACC_SG", "INS_SG", "PREP_SG")
        return {k: adj[k] for k in keys}, True
    cls = _guess_class(stripped, gender)
    if cls is None:
        return partial, False
    numbers = ("SG", "PL") if nom_pl else ("SG",)
    try:
        gen = decline_noun(word, cls, animate=animate, numbers=numbers)
    except Exception:
        return partial, False
    # validate against every deck-supplied form
    checks = [("GEN_SG", gen_sg), ("PREP_SG", prep_sg), ("NOM_PL", nom_pl), ("GEN_PL", gen_pl)]
    for key, deck_val in checks:
        if not deck_val:
            continue
        if strip_stress(deck_val).lower() != (gen.get(key) or "").lower():
            return partial, False
    return gen, True


# ── Template Arrays ────────────────────────────────────────────────
# Each entry: (Russian template, English template)
# {A}=accusative, {G}=genitive, {I}=instrumental, {P}=prepositional
# {N}=nominative(animate), {INF}=infinitive, {ADV}=adverb
# {M}=masc-nom, {F}=fem-nom, {N}=neut-nom(adj), {P}=plural-nom(adj)
# {T}=English translation term

INANIMATE_CONTEXTS = [
    # ── accusative ──
    ("После доклада участники обсудили {A}.", "After the report, the participants discussed the {T}."),
    ("В резолюции есть ссылка на {A}.", "The resolution includes a reference to the {T}."),
    ("Новая мера повлияла на {A}.", "The new measure affected the {T}."),
    ("Правительство рассмотрело {A} на заседании.", "The government considered the {T} at the session."),
    ("Все ждали {A} с нетерпением.", "Everyone was looking forward to the {T}."),
    ("Они изучили {A} в деталях.", "They studied the {T} in detail."),
    ("Закон регулирует {A}.", "The law regulates the {T}."),
    # ── genitive ──
    ("На заседании возник спор вокруг {G}.", "A dispute arose at the meeting around the {T}."),
    ("В отчёте описаны последствия для {G}.", "The report describes the consequences for the {T}."),
    ("Учёные провели исследование {G}.", "Scientists conducted a study of the {T}."),
    ("Значение {G} трудно переоценить.", "The significance of the {T} is hard to overestimate."),
    ("Без {G} невозможно продолжить.", "Without the {T} it is impossible to continue."),
    ("Нам не хватает {G}.", "We lack the {T}."),
    # ── instrumental ──
    ("Представитель связал решение с {I}.", "The representative linked the decision with the {T}."),
    ("Журналист заинтересовался {I}.", "The journalist became interested in the {T}."),
    ("Специалист занимался {I} несколько лет.", "The specialist worked on the {T} for several years."),
    ("Компания управляет {I}.", "The company manages the {T}."),
    ("Учитель был доволен {I}.", "The teacher was pleased with the {T}."),
    # ── prepositional ──
    ("Комиссия запросила данные о {P}.", "The commission requested data about the {T}."),
    ("Стороны вернулись к вопросу о {P}.", "The parties returned to the issue of the {T}."),
    ("Эксперты высказались о {P}.", "The experts spoke about the {T}."),
    ("Мы долго думали о {P}.", "We thought about the {T} for a long time."),
    ("В газете написали о {P}.", "The newspaper wrote about the {T}."),
    ("Студенты узнали о {P} на лекции.", "The students learned about the {T} in a lecture."),
    ("Речь шла о {P}.", "The discussion was about the {T}."),
]

ANIMATE_CONTEXTS = [
    ("{N} ответил на вопросы после заседания.", "The {T} answered questions after the meeting."),
    ("{N} прибыл на переговоры с делегацией.", "The {T} arrived at the negotiations with the delegation."),
    ("{N} поддержал компромиссный вариант.", "The {T} supported the compromise option."),
    ("{N} встретился с представителями региона.", "The {T} met with regional representatives."),
    ("{N} пояснил позицию своей стороны.", "The {T} explained his side's position."),
    ("{N} выступил с официальным заявлением.", "The {T} made an official statement."),
    ("{N} принял участие в конференции.", "The {T} participated in the conference."),
    ("{N} подготовил доклад к заседанию.", "The {T} prepared a report for the session."),
    ("{N} работал в этой области много лет.", "The {T} worked in this field for many years."),
    ("{N} предложил новый подход.", "The {T} proposed a new approach."),
    ("{N} выразил обеспокоенность ситуацией.", "The {T} expressed concern about the situation."),
    ("{N} получил награду за достижения.", "The {T} received an award for achievements."),
]

ADJECTIVE_CONTEXTS = [
    ("{M} вопрос вынесли на обсуждение.", "The {T} issue was put up for discussion."),
    ("Комитет подготовил {M} доклад.", "The committee prepared a {T} report."),
    ("Ведомство опубликовало {Neut} заявление.", "The agency published a {T} statement."),
    ("Участники отметили {F} роль партнёров.", "The participants noted the {T} role of the partners."),
    ("В проект включили {Pl} меры поддержки.", "The project included {T} support measures."),
    ("Ситуацию назвали {F} для рынка.", "The situation was called {T} for the market."),
    ("Это был {M} момент в истории.", "It was a {T} moment in history."),
    ("Она приняла {Neut} решение.", "She made a {T} decision."),
    ("Он прочитал {F} книгу за вечер.", "He read a {T} book in one evening."),
    ("В городе открыли {M} музей.", "A {T} museum was opened in the city."),
    ("Учёный сделал {Neut} открытие.", "The scientist made a {T} discovery."),
    ("{Pl} результаты превзошли ожидания.", "The {T} results exceeded expectations."),
    ("Директор предложил {M} план развития.", "The director proposed a {T} development plan."),
    ("Закон ввёл {Pl} ограничения.", "The law introduced {T} restrictions."),
    ("Компания представила {Neut} оборудование.", "The company presented {T} equipment."),
    ("{F} погода установилась на неделю.", "The {T} weather lasted for a week."),
]

VERB_CONTEXTS = [
    ("Делегации удалось {INF} до конца встречи.", "The delegation managed to {T} before the end of the meeting."),
    ("Комитет предложил {INF} после консультаций.", "The committee proposed to {T} after consultations."),
    ("Стороны договорились {INF} без условий.", "The parties agreed to {T} without conditions."),
    ("Эксперты считают, что необходимо {INF}.", "Experts believe it is necessary to {T}."),
    ("Власти намерены {INF} в ближайшие месяцы.", "The authorities intend to {T} in the coming months."),
    ("Рабочая группа продолжит {INF}.", "The working group will continue to {T}."),
    ("Важно {INF} вовремя.", "It is important to {T} on time."),
    ("Они решили {INF} на следующей неделе.", "They decided to {T} next week."),
    ("Нужно {INF} каждый день.", "It is necessary to {T} every day."),
    ("Он хотел {INF}, но не успел.", "He wanted to {T} but did not have time."),
    ("Мы начали {INF} с утра.", "We began to {T} in the morning."),
    ("Она умеет {INF} очень хорошо.", "She can {T} very well."),
    ("Трудно {INF} в таких условиях.", "It is difficult to {T} in such conditions."),
    ("Можно {INF} и по-другому.", "One can also {T} differently."),
    ("Нельзя {INF} без подготовки.", "One must not {T} without preparation."),
    ("Пора {INF}.", "It is time to {T}."),
    ("Не стоит {INF} слишком быстро.", "One should not {T} too quickly."),
    ("Я люблю {INF} по вечерам.", "I love to {T} in the evenings."),
    ("Учитель попросил {INF} внимательно.", "The teacher asked to {T} carefully."),
    ("Надо {INF} как можно скорее.", "It is necessary to {T} as soon as possible."),
]

ADVERB_CONTEXTS = [
    ("Он {ADV} объяснил свою позицию.", "He explained his position {T}."),
    ("Она {ADV} выполнила задание.", "She completed the task {T}."),
    ("Мы {ADV} обсудили проблему.", "We discussed the problem {T}."),
    ("Они {ADV} работали весь день.", "They worked {T} all day."),
    ("Спортсмен {ADV} пробежал дистанцию.", "The athlete ran the distance {T}."),
    ("Студент {ADV} подготовился к экзамену.", "The student prepared for the exam {T}."),
    ("Машина {ADV} остановилась.", "The car stopped {T}."),
    ("Директор {ADV} принял решение.", "The director made the decision {T}."),
    ("Всё закончилось {ADV}.", "Everything ended {T}."),
    ("Процесс {ADV} развивается.", "The process is developing {T}."),
    ("Мы {ADV} согласились продолжить.", "We {T} agreed to continue."),
    ("Стороны {ADV} завершили переговоры.", "The parties {T} completed the negotiations."),
]


# ── Generator helpers ──────────────────────────────────────────────

def term_en(translation: str) -> str:
    """Extract the first clean English term from a multi-meaning translation.
    'to speak, to suggest / to say' → 'to speak'"""
    return translation.split("/")[0].split(",")[0].strip()


def cap(word: str) -> str:
    """Capitalize first letter."""
    return word[:1].upper() + word[1:] if word else word


def _primary_verb(citation: str) -> str:
    """Extract the primary form from a slash-separated citation.
    'мочь/смочь' → 'мочь', 'говорить/сказать/поговорить' → 'говорить'
    Then strip stress marks for template insertion."""
    return strip_stress(citation.split("/")[0].strip())


def _verb_en(translation: str) -> str:
    """Clean English verb for template insertion.
    'to speak, to suggest' → 'speak'
    'be able, may' → 'be able'"""
    t = term_en(translation)
    return t[3:].strip() if t.lower().startswith("to ") else t


# ── Animate noun detection ─────────────────────────────────────────

_ANIMATE_KEYWORDS = frozenset([
    "person", "man", "woman", "people", "child", "boy", "girl",
    "doctor", "teacher", "friend", "brother", "sister", "father", "mother",
    "president", "minister", "officer", "soldier", "worker", "director",
    "author", "judge", "leader", "expert", "specialist", "engineer",
    "artist", "musician", "singer", "writer", "actor", "actress",
    "student", "professor", "scientist", "journalist", "reporter",
    "neighbor", "colleague", "boss", "king", "queen", "prince", "princess",
    "husband", "wife", "son", "daughter", "uncle", "aunt",
    "grandfather", "grandmother", "baby", "citizen", "master",
    "dog", "cat", "animal", "bird", "horse", "bear", "fish", "wolf",
    "hero", "jew", "major", "genius", "scoundrel", "parrot", "villain",
    "priest", "lieutenant", "sergeant", "sniper", "captive", "prisoner",
    "policeman", "sentry", "convict", "guard", "criminal", "suspect",
])


def _seems_animate(gender_raw: str, translation: str) -> bool:
    """Heuristic: does this noun refer to a living being?
    Only triggers for masculine/feminine (neuter animate nouns are very rare)."""
    if gender_raw not in ("masc", "femn"):
        return False
    tl = translation.lower()
    return any(kw in tl for kw in _ANIMATE_KEYWORDS)


# ── Example generators ─────────────────────────────────────────────

def noun_example_gen(table: dict, nom_unstressed: str, translation: str, animate: bool, idx: int):
    """Generate a context sentence for a noun using its case forms."""
    if animate:
        ru, en = ANIMATE_CONTEXTS[idx % len(ANIMATE_CONTEXTS)]
        return (
            ru.replace("{N}", cap(nom_unstressed)),
            en.replace("{T}", term_en(translation)),
        )
    else:
        ru, en = INANIMATE_CONTEXTS[idx % len(INANIMATE_CONTEXTS)]
        acc = table.get("ACC_SG") or table.get("ACC_PL") or nom_unstressed
        gen = table.get("GEN_SG") or table.get("GEN_PL") or nom_unstressed
        ins = table.get("INS_SG") or table.get("INS_PL") or nom_unstressed
        prep = table.get("PREP_SG") or table.get("PREP_PL") or nom_unstressed
        return (
            ru.replace("{A}", acc)
              .replace("{G}", gen)
              .replace("{I}", ins)
              .replace("{P}", prep),
            en.replace("{T}", term_en(translation)),
        )


def adjective_example_gen(table: dict, citation: str, translation: str, idx: int):
    """Generate a context sentence for an adjective using its agreement forms."""
    lemma = strip_stress(citation)
    ru, en = ADJECTIVE_CONTEXTS[idx % len(ADJECTIVE_CONTEXTS)]
    m_nom  = table.get("NOM_M_SG") or table.get("NOM_SG") or lemma
    f_nom  = table.get("FEM_NOM") or lemma
    n_nom  = table.get("NEUT_NOM") or lemma
    pl_nom = table.get("PL_NOM") or lemma
    # Capitalize forms that appear at the start of the sentence
    if ru.startswith("{M}"):   m_nom  = cap(m_nom)
    if ru.startswith("{F}"):   f_nom  = cap(f_nom)
    if ru.startswith("{Neut}"): n_nom = cap(n_nom)
    if ru.startswith("{Pl}"):  pl_nom = cap(pl_nom)
    return (
        ru.replace("{M}", m_nom)
          .replace("{F}", f_nom)
          .replace("{Neut}", n_nom)
          .replace("{Pl}", pl_nom),
        en.replace("{T}", term_en(translation)),
    )


def verb_example_gen(citation: str, translation: str, idx: int):
    """Generate a context sentence for a verb using its infinitive form.
    Handles slash-separated citations: 'мочь/смочь' → uses only 'мочь'."""
    inf = _primary_verb(citation)
    en_verb = _verb_en(translation)
    ru, en = VERB_CONTEXTS[idx % len(VERB_CONTEXTS)]
    return (
        ru.replace("{INF}", inf),
        en.replace("{T}", en_verb),
    )


def adverb_example_gen(citation: str, translation: str, idx: int):
    """Generate a context sentence for an adverb."""
    adv = strip_stress(citation)
    ru, en = ADVERB_CONTEXTS[idx % len(ADVERB_CONTEXTS)]
    return (
        ru.replace("{ADV}", adv),
        en.replace("{T}", term_en(translation)),
    )


# ── Main builder ───────────────────────────────────────────────────

def general_rows(domain_lemmas: set[str]) -> list[dict]:
    if not SOURCE.exists():
        return []
    rows = []
    # Dedup on the same normalization the app uses (stress-stripped, ё→е) so
    # ё-spelled domain words (партнёр) also shadow their general duplicates.
    seen = {l.replace("ё", "е") for l in domain_lemmas}
    engine_tables = 0
    curated_count = 0
    for line in SOURCE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        e = json.loads(line)
        raw_word = e["word"]
        # Source entries may have slash-separated forms ("честь/считать",
        # "печь/испечь") — extract only the primary form.
        word = raw_word.split("/")[0].strip()
        lemma = strip_stress(word).lower()
        key = _norm(word)
        if not lemma or key in seen:
            continue
        seen.add(key)
        source_translation = e.get("definition", "").strip()
        translation = TRANSLATION_OVERRIDES.get(lemma, source_translation)
        if not translation:
            continue
        pos = _pos(e.get("pos", ""))
        rank = e.get("rank")

        # Every frequency note is upgraded to a rich vocab/comprehension study card.
        # The tag contains both "general" and "matrix" so the app reliably treats it
        # as the reading-matrix layer: it gets vocab/cloze/dictation/audio/stress cards
        # and keeps its declension table for reader coverage, but NO morphology drills
        # (its engine-derived oblique forms aren't deck-verified). See cardsFor.
        is_curated = True
        tag_name = "general curated matrix"

        note = {
            "russian": word,
            "lemma": lemma,
            "pos": pos,
            "translation": translation,
            "generalFreqRank": rank if rank else None,
            "tags": tag_name,
        }
        if translation != source_translation:
            # This is a deliberate local editorial correction, not an
            # unreviewed scrape; finalize_notes preserves it for the next
            # evidence-ledger rebuild.
            note["authored"] = True
        curated_count += 1

        second_sense = SECOND_SENSE_OVERRIDES.get(lemma)
        if second_sense:
            note["secondSense"], note["secondSenseExample"], note["secondSenseExampleTranslation"] = second_sense

        example = e.get("example", "").strip()
        needs_quality_fallback = False
        if example:
            # Deck examples are "Russian - English". Split into sentence + gloss; only
            # keep the example if BOTH halves are present — a sentence with an empty
            # gloss is not comprehensible input, so we drop it (note stays vocab-only)
            # rather than ship a half-glossed card.
            ru, en = "", ""
            # Only spaced separators — a bare "-" wrongly splits dates/hyphenated words.
            # Russian sentences sometimes use an em dash as a copula (e.g.
            # "История — учительница жизни"), so the RU/EN boundary is not
            # necessarily the *first* separator in the string — usually it's
            # the *last* one (English glosses rarely contain a spaced dash).
            # But occasionally the English gloss itself contains a spaced
            # dash too (e.g. "Full brake-power - we're plummeting - !"), so
            # try candidate split points from rightmost to leftmost and take
            # the first one that actually looks like a Russian/English
            # boundary (Cyrillic before, Latin after) rather than assuming
            # the rightmost occurrence is always correct.
            candidates = []
            for sep in (" — ", " -- ", " - "):
                start = 0
                while True:
                    idx = example.find(sep, start)
                    if idx == -1:
                        break
                    candidates.append((idx, sep))
                    start = idx + 1
            candidates.sort(key=lambda c: -c[0])
            for idx, sep in candidates:
                head = example[:idx].strip()
                tail = example[idx + len(sep):].strip()
                has_cyr_head = any("а" <= ch.lower() <= "я" or ch == "ё" for ch in head)
                has_latin_tail = any("a" <= ch.lower() <= "z" for ch in tail)
                if head and tail and has_cyr_head and has_latin_tail:
                    ru, en = head, tail
                    break
            # Repair each half independently, not the joined string — an isolated
            # mojibake character in one half (e.g. a single scrambled proper noun in
            # the English gloss) must not have the mac_cyrillic/cp1251 transform
            # applied to the OTHER, already-clean half, which would corrupt it fresh.
            ru = _repair_mojibake(ru)
            en = _repair_mojibake(en)
            has_cyrillic = any("а" <= ch.lower() <= "я" or ch == "ё" for ch in ru)
            has_latin = any("a" <= ch.lower() <= "z" for ch in en)
            is_mojibake = bool(_MOJIBAKE_CYRILLIC.search(ru)) or bool(_MOJIBAKE_CYRILLIC.search(en))
            if ru and en and has_cyrillic and has_latin and not is_mojibake and _studyable_example(ru, en):
                note["exampleSentence"] = ru
                note["exampleTranslation"] = en
            else:
                needs_quality_fallback = True

        gender_raw = e.get("gender", "").strip().lower()
        gender = GENDER_MAP.get(gender_raw)
        table = None
        if pos == "noun" and gender:
            note["gender"] = gender
            animate = _seems_animate(gender_raw, translation)
            table, used_engine = _full_table(
                word, gender, e.get("gen_sg"), e.get("gen_pl"), e.get("prep_sg"), e.get("nom_pl"),
                animate=animate)
            note["declensionJson"] = table
            if used_engine:
                engine_tables += 1
        elif pos == "adjective":
            note["gender"] = "M"
            try:
                table = decline_adjective(word)
                note["declensionJson"] = table
            except Exception:
                pass  # irregular/comparative-only: vocab-only is fine

        # A small number of scraped records are structurally corrupted (quote
        # truncation, an English tail embedded in RU, or mojibake punctuation).
        # Keep the vocabulary card, but replace only those records with a
        # morphology-aware, explicitly marked fallback context. This is safer
        # than silently teaching the corrupted source sentence or dropping a
        # high-frequency note altogether.
        if needs_quality_fallback:
            curated_fallback = FALLBACK_EXAMPLE_OVERRIDES.get(lemma)
            if curated_fallback:
                fallback_ru, fallback_en = curated_fallback
                note["exampleSource"] = "human-reviewed quality repair"
            elif pos == "noun" and table:
                fallback_ru, fallback_en = noun_example_gen(
                    table, strip_stress(word), translation,
                    _seems_animate(gender_raw, translation), len(rows))
            elif pos == "adjective" and table:
                fallback_ru, fallback_en = adjective_example_gen(table, word, translation, len(rows))
            elif pos == "verb":
                fallback_ru, fallback_en = verb_example_gen(word, translation, len(rows))
            elif pos == "adverb":
                fallback_ru, fallback_en = adverb_example_gen(word, translation, len(rows))
            else:
                fallback_ru = f"Мы изучаем слово «{strip_stress(word)}»."
                fallback_en = f"We are studying the word “{term_en(translation)}”."
            note["exampleSentence"] = fallback_ru
            note["exampleTranslation"] = fallback_en
            note.setdefault("exampleSource", "generated-quality-fallback")

        # NOTE: we intentionally do NOT synthesize 2nd/3rd examples here. The
        # templated generators (noun_example_gen, etc.) produce formulaic, low-quality
        # sentences — the "weak spot" of the frequency layer. One real, deck-authored
        # example beats one real + two templated. Multi-context depth is reserved for
        # the hand-authored tier-0 course.

        rows.append(note)
    print(f"  general: {len(rows)} notes (all {curated_count} curated/study), {engine_tables} full engine-declined noun tables")
    return rows
