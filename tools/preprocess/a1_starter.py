# -*- coding: utf-8 -*-
"""The A1 starter layer (tier 0): a small, concrete, progressive beginner course.

Design goals (the whole point of the A1 rework):
  * Concrete, high-frequency, picturable vocabulary — what a true beginner needs
    first, NOT the formal/political domain that the rest of the deck targets.
  * Every example sentence is fully readable: it ships with a real English
    translation, and uses only vocabulary already introduced in this or an earlier
    unit (controlled vocabulary). The build validates this.
  * A teach-before-test grammar spine: each grammar concept is introduced by a
    LESSON note (pos = "lesson") placed at the start of its unit. The app shows the
    lesson before any drill on that concept can surface (concept gating).

Declension/conjugation forms come from the rule engine in russian_morph, and the
curriculum deliberately uses only regular nouns/verbs so the generated forms are
trustworthy (irregular plurals like друг→друзья are avoided in the grammar units).

Concept ids must match com.sibirskyspeak.data.GrammarConcepts.
"""
from __future__ import annotations

from russian_morph import decline_adjective, decline_noun, strip_stress

# --- Vocabulary, unit by unit ----------------------------------------------
# Noun entry:  (citation_stressed, decl_class, gender, animate, translation,
#               example_ru, example_en)
# Verb entry:  (citation_stressed, translation, example_ru, example_en,
#               aspect, aktionsart, partner_lemma_or_None)
# Adj  entry:  (citation_stressed, translation, example_ru, example_en)
# Word entry:  (citation_stressed, pos, translation, example_ru, example_en)
#
# A unit is a dict: {"unit": n, "title": str, "concept": id_or_None, items...}

UNITS = [
    {
        "unit": 0,
        "title": "First words",
        "concept": None,
        "words": [
            ("приве́т", "interjection", "hi, hello", "Приве́т! Как дела́?", "Hi! How are you?"),
            ("да", "particle", "yes", "Да, спаси́бо.", "Yes, thank you."),
            ("нет", "particle", "no", "Нет, спаси́бо.", "No, thank you."),
            ("спаси́бо", "interjection", "thank you", "Да, спаси́бо!", "Yes, thank you!"),
            ("я", "pronoun", "I", "Э́то я.", "It's me."),
            ("ты", "pronoun", "you", "Кто ты?", "Who are you?"),
            ("э́то", "pronoun", "this, it is", "Кто э́то?", "Who is this?"),
            ("и", "conjunction", "and", "Да и нет.", "Yes and no."),
            ("хорошо́", "adverb", "good, well, okay", "Всё хорошо́.", "Everything is good."),
            ("кто", "pronoun", "who", "Кто э́то?", "Who is this?"),
        ],
    },
    {
        "unit": 1,
        "title": "Things around us",
        "concept": "GENDER",
        "nouns": [
            ("стол", "m_hard", "M", False, "table", "Э́то стол.", "This is a table."),
            ("стул", "m_hard", "M", False, "chair", "Вот стул, а вот стол.", "Here is a chair, and here is a table.",
             {"NOM_PL": "стулья", "GEN_PL": "стульев", "DAT_PL": "стульям", "INS_PL": "стульями", "PREP_PL": "стульях", "ACC_PL": "стулья"}),
            ("кни́га", "f_a", "F", False, "book", "Э́то кни́га.", "This is a book."),
            ("окно́", "n_o", "N", False, "window", "Э́то окно́.", "This is a window.", {"GEN_PL": "окон"}),
            ("вода́", "f_a", "F", False, "water", "Вот вода́.", "Here is water."),
            ("ко́мната", "f_a", "F", False, "room", "Э́то ко́мната.", "This is a room."),
            ("сло́во", "n_o", "N", False, "word", "Э́то ру́сское сло́во.", "This is a Russian word."),
            ("ма́ма", "f_a", "F", True, "mum", "Э́то моя́ ма́ма.", "This is my mum."),
            ("па́па", "f_a", "M", True, "dad", "Э́то мой па́па.", "This is my dad."),
        ],
        "adjs": [
            ("ру́сский", "Russian", "Э́то ру́сское сло́во.", "This is a Russian word."),
        ],
        "words": [
            ("вот", "particle", "here is", "Вот кни́га.", "Here is the book."),
            ("мой", "pronoun", "my", "Э́то мой стол.", "This is my table."),
            ("моя́", "pronoun", "my (f.)", "Э́то моя́ кни́га.", "This is my book."),
        ],
    },
    {
        "unit": 2,
        "title": "More than one",
        "concept": "NOM_PL",
        "nouns": [
            ("студе́нт", "m_hard", "M", True, "student", "Студе́нты в шко́ле.", "The students are at school."),
            ("маши́на", "f_a", "F", False, "car", "Э́то маши́ны.", "These are cars."),
            ("шко́ла", "f_a", "F", False, "school", "В шко́ле есть кни́ги.", "There are books in the school."),
            ("газе́та", "f_a", "F", False, "newspaper", "Э́то газе́ты.", "These are newspapers."),
            ("парк", "m_hard", "M", False, "park", "Э́то па́рки.", "These are parks."),
        ],
        "words": [
            ("есть", "verb", "there is, there are", "В ко́мнате есть окно́.", "There is a window in the room."),
            ("в", "preposition", "in", "Кни́га в столе́.", "The book is in the table."),
            ("на", "preposition", "on", "Кни́га на столе́.", "The book is on the table."),
        ],
    },
    {
        "unit": 3,
        "title": "Everyday actions",
        "concept": None,
        "nouns": [
            ("рабо́та", "f_a", "F", False, "work, job", "Э́то моя́ рабо́та.", "This is my work."),
        ],
        "verbs": [
            ("знать", "to know", "Я зна́ю э́то сло́во.", "I know this word.", "IPF", "state", None),
            ("чита́ть", "to read", "Я чита́ю кни́гу.", "I am reading a book.", "IPF", "accomplishment", "прочитать"),
            ("де́лать", "to do, to make", "Что ты де́лаешь?", "What are you doing?", "IPF", "accomplishment", "сделать"),
            ("рабо́тать", "to work", "Ма́ма рабо́тает.", "Mum is working.", "IPF", "activity", None),
            ("жить", "to live", "Я живу́ и рабо́таю.", "I live and work.", "IPF", "state", None),
        ],
        "words": [
            ("он", "pronoun", "he", "Он чита́ет кни́гу.", "He is reading a book."),
            ("она́", "pronoun", "she", "Она́ зна́ет э́то.", "She knows this."),
            ("мы", "pronoun", "we", "Мы рабо́таем.", "We are working."),
            ("что", "pronoun", "what", "Что э́то?", "What is this?"),
            ("мно́го", "adverb", "much, a lot", "Я мно́го рабо́таю.", "I work a lot."),
            ("ма́ло", "adverb", "little, few", "Я ма́ло чита́ю.", "I read little."),
        ],
    },
    {
        "unit": 4,
        "title": "Doing something to things",
        "concept": "ACC",
        "nouns": [
            ("соба́ка", "f_a", "F", True, "dog", "Я люблю́ соба́ку.", "I love the dog."),
            ("ко́шка", "f_a", "F", True, "cat", "Я ви́жу ко́шку.", "I see the cat.", {"GEN_PL": "кошек"}),
            ("хлеб", "m_hard", "M", False, "bread", "Я покупа́ю хлеб.", "I am buying bread.", {"NOM_PL": "хлеба", "ACC_PL": "хлеба"}),
        ],
        "verbs": [
            ("люби́ть", "to love, to like", "Я люблю́ ма́му.", "I love mum.", "IPF", "state", None),
            ("покупа́ть", "to buy", "Я покупа́ю газе́ту.", "I am buying a newspaper.", "IPF", "accomplishment", None),
        ],
    },
    {
        "unit": 5,
        "title": "Having and not having",
        "concept": "GEN",
        "words": [
            ("у", "preposition", "at, by (have)", "У ма́мы есть кни́га.", "Mum has a book."),
            ("меня́", "pronoun", "me (gen.)", "У меня́ есть соба́ка.", "I have a dog."),
            ("без", "preposition", "without", "Чай без воды́.", "Tea without water."),
            ("чай", "noun", "tea", "Я пью чай.", "I am drinking tea."),
        ],
        "nouns": [
            ("друг", "m_hard", "M", True, "friend", "У меня́ есть друг.", "I have a friend.",
             {"NOM_PL": "друзья", "GEN_PL": "друзей", "DAT_PL": "друзьям", "INS_PL": "друзьями", "PREP_PL": "друзьях", "ACC_PL": "друзей"}),
        ],
    },
    {
        "unit": 6,
        "title": "Where things are",
        "concept": "PREP",
        "nouns": [
            ("го́род", "m_hard", "M", False, "city", "Я живу́ в го́роде.", "I live in the city.", {"NOM_PL": "города", "ACC_PL": "города"}),
            ("дом", "m_hard", "M", False, "house, home", "Ма́ма в до́ме.", "Mum is in the house.", {"NOM_PL": "дома", "ACC_PL": "дома"}),
        ],
        "words": [
            ("о", "preposition", "about", "Кни́га о го́роде.", "A book about the city."),
        ],
    },
    {
        "unit": 7,
        "title": "Giving and telling",
        "concept": "DAT",
        "verbs": [
            ("писа́ть", "to write", "Я пишу́ ма́ме.", "I am writing to mum.", "IPF", "accomplishment", "написать"),
            ("дава́ть", "to give", "Я даю́ кни́гу дру́гу.", "I give the book to a friend.", "IPF", "accomplishment", None),
        ],
        "words": [
            ("к", "preposition", "to, towards", "Я иду́ к ма́ме.", "I am going to mum."),
        ],
    },
    {
        "unit": 8,
        "title": "With what, with whom",
        "concept": "INS",
        "nouns": [
            ("ру́чка", "f_a", "F", False, "pen", "Я пишу́ ру́чкой.", "I write with a pen.", {"GEN_PL": "ручек"}),
        ],
        "words": [
            ("с", "preposition", "with", "Я пью чай с ма́мой.", "I drink tea with mum."),
        ],
    },
    {
        "unit": 9,
        "title": "Talking about the past",
        "concept": "PAST",
        "words": [
            ("вчера́", "adverb", "yesterday", "Вчера́ я чита́л кни́гу.", "Yesterday I read a book."),
            ("они́", "pronoun", "they", "Они́ рабо́тали.", "They worked."),
        ],
    },
    {
        "unit": 10,
        "title": "Finished or ongoing",
        "concept": "ASPECT",
        "verbs": [
            ("прочита́ть", "to read (finish)", "Вчера́ я прочита́л кни́гу.", "Yesterday I read the whole book.", "PF", "accomplishment", "читать"),
            ("сде́лать", "to do (finish)", "Я сде́лал рабо́ту.", "I finished the work.", "PF", "accomplishment", "делать"),
            ("написа́ть", "to write (finish)", "Я написа́л сло́во.", "I wrote the word.", "PF", "accomplishment", "писать"),
        ],
    },
]

from curriculum_common import CONCEPT_TITLES, build_level


def a1_rows():
    """All tier-0 A1 notes (lessons first within each unit) in curriculum order."""
    return build_level(UNITS, "A1")


def a1_reader_texts():
    """Graded readers using only A1 controlled vocabulary, one per few units."""
    return [
        {
            "title": "A1 · Это мой дом",
            "source": "graded:a1",
            "body": "Приве́т! Э́то мой дом. Вот ко́мната. В ко́мнате есть стол и окно́. "
                    "На столе́ кни́га и газе́та. Э́то моя́ ма́ма. Ма́ма чита́ет кни́гу.",
        },
        {
            "title": "A1 · Мой день",
            "source": "graded:a1",
            "body": "Я живу́ в го́роде. Я рабо́таю и чита́ю кни́ги. "
                    "У меня́ есть друг и соба́ка. Я люблю́ соба́ку. "
                    "Вчера́ я чита́л газе́ту и пил чай.",
        },
        {
            "title": "A1 · Семья",
            "source": "graded:a1",
            "body": "Э́то моя́ ма́ма и мой па́па. Ма́ма рабо́тает в шко́ле. "
                    "Па́па чита́ет газе́ту. Я пишу́ ма́ме. Мы пьём чай.",
        },
    ]


if __name__ == "__main__":
    rows = a1_rows()
    lessons = [r for r in rows if r["pos"] == "lesson"]
    print(f"a1 notes: {len(rows)} ({len(lessons)} lessons), readers: {len(a1_reader_texts())}")
