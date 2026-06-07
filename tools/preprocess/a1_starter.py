# -*- coding: utf-8 -*-
"""The A1 starter layer (tier 0): a small, concrete, progressive beginner course.

Design goals (the whole point of the A1 rework):
  * Concrete, high-frequency, picturable vocabulary — what a true beginner needs
    first, NOT the formal/political domain that the rest of the deck targets.
  * Every example sentence is fully readable: it ships with a real English
    translation, and uses only vocabulary already introduced in this or an earlier
    unit (controlled vocabulary). The build validates this.
  * A teach-before-test grammar spine: each grammar concept is introduced by a
    LESSON note (pos = "lesson") placed at the start of its unit.
  * Each word ships 2 example contexts (a trailing list of extra (ru, en) pairs).

Concept ids must match com.sibirskyspeak.data.GrammarConcepts.
"""
from __future__ import annotations

from curriculum_common import CONCEPT_TITLES, build_level

UNITS = [
    {
        "unit": 0,
        "title": "First words",
        "concept": None,
        "words": [
            ("приве́т", "interjection", "hi, hello", "Приве́т! Как дела́?", "Hi! How are you?",
             [("Приве́т! Э́то я.", "Hi! It's me.")]),
            ("да", "particle", "yes", "Да, спаси́бо.", "Yes, thank you.",
             [("Да, э́то я.", "Yes, it's me.")]),
            ("нет", "particle", "no", "Нет, спаси́бо.", "No, thank you.",
             [("Нет, э́то не я.", "No, it's not me.")]),
            ("спаси́бо", "interjection", "thank you", "Да, спаси́бо!", "Yes, thank you!",
             [("Спаси́бо! Всё хорошо́.", "Thank you! All is well.")]),
            ("я", "pronoun", "I", "Э́то я.", "It's me.",
             [("Кто я?", "Who am I?")]),
            ("ты", "pronoun", "you", "Кто ты?", "Who are you?",
             [("Э́то ты?", "Is it you?")]),
            ("э́то", "pronoun", "this, it is", "Кто э́то?", "Who is this?",
             [("Э́то хорошо́.", "This is good.")]),
            ("и", "conjunction", "and", "Да и нет.", "Yes and no.",
             [("Я и ты.", "Me and you.")]),
            ("хорошо́", "adverb", "good, well, okay", "Всё хорошо́.", "Everything is good.",
             [("Да, хорошо́.", "Yes, okay.")]),
            ("кто", "pronoun", "who", "Кто э́то?", "Who is this?",
             [("Кто ты?", "Who are you?")]),
        ],
    },
    {
        "unit": 1,
        "title": "Things around us",
        "concept": "GENDER",
        "nouns": [
            ("стол", "m_hard", "M", False, "table", "Э́то стол.", "This is a table.", None,
             [("Вот мой стол.", "Here is my table.")]),
            ("стул", "m_hard", "M", False, "chair", "Вот стул, а вот стол.", "Here is a chair, and here is a table.",
             {"NOM_PL": "стулья", "GEN_PL": "стульев", "DAT_PL": "стульям", "INS_PL": "стульями", "PREP_PL": "стульях", "ACC_PL": "стулья"},
             [("Э́то мой стул.", "This is my chair.")]),
            ("кни́га", "f_a", "F", False, "book", "Э́то кни́га.", "This is a book.", None,
             [("Вот моя́ кни́га.", "Here is my book.")]),
            ("окно́", "n_o", "N", False, "window", "Э́то окно́.", "This is a window.", {"GEN_PL": "окон"},
             [("Вот окно́.", "Here is a window.")]),
            ("вода́", "f_a", "F", False, "water", "Вот вода́.", "Here is water.", None,
             [("Э́то вода́.", "This is water.")]),
            ("ко́мната", "f_a", "F", False, "room", "Э́то ко́мната.", "This is a room.", None,
             [("Вот моя́ ко́мната.", "Here is my room.")]),
            ("сло́во", "n_o", "N", False, "word", "Э́то ру́сское сло́во.", "This is a Russian word.", None,
             [("Э́то сло́во.", "This is a word.")]),
            ("ма́ма", "f_a", "F", True, "mum", "Э́то моя́ ма́ма.", "This is my mum.", None,
             [("Вот моя́ ма́ма.", "Here is my mum.")]),
            ("па́па", "f_a", "M", True, "dad", "Э́то мой па́па.", "This is my dad.", None,
             [("Вот мой па́па.", "Here is my dad.")]),
            ("телефо́н", "m_hard", "M", False, "telephone", "Э́то мой телефо́н.", "This is my phone.", None,
             [("Вот телефо́н.", "Here is a phone.")]),
            ("дверь", "f_soft", "F", False, "door", "Вот дверь.", "Here is a door.", None,
             [("Э́то дверь.", "This is a door.")]),
            ("стена́", "f_a", "F", False, "wall", "Вот стена́.", "Here is a wall.", {"NOM_PL": "стены", "ACC_PL": "стены", "GEN_PL": "стен"},
             [("Э́то стена́.", "This is a wall.")]),
        ],
        "adjs": [
            ("ру́сский", "Russian", "Э́то ру́сское сло́во.", "This is a Russian word.",
             [("Вот ру́сская кни́га.", "Here is a Russian book.")]),
        ],
        "words": [
            ("вот", "particle", "here is", "Вот кни́га.", "Here is the book.",
             [("Вот стол.", "Here is a table.")]),
            ("мой", "pronoun", "my", "Э́то мой стол.", "This is my table.",
             [("Вот мой стул.", "Here is my chair.")]),
            ("моя́", "pronoun", "my (f.)", "Э́то моя́ кни́га.", "This is my book.",
             [("Вот моя́ дверь.", "Here is my door.")]),
        ],
    },
    {
        "unit": 2,
        "title": "More than one",
        "concept": "NOM_PL",
        "nouns": [
            ("студе́нт", "m_hard", "M", True, "student", "Студе́нты в шко́ле.", "The students are at school.", None,
             [("Э́то студе́нт.", "This is a student.")]),
            ("маши́на", "f_a", "F", False, "car", "Э́то маши́ны.", "These are cars.", None,
             [("Вот маши́на.", "Here is a car.")]),
            ("шко́ла", "f_a", "F", False, "school", "В шко́ле есть кни́ги.", "There are books in the school.", None,
             [("Э́то моя́ шко́ла.", "This is my school.")]),
            ("газе́та", "f_a", "F", False, "newspaper", "Э́то газе́ты.", "These are newspapers.", None,
             [("Вот газе́та.", "Here is a newspaper.")]),
            ("парк", "m_hard", "M", False, "park", "Э́то па́рки.", "These are parks.", None,
             [("Вот парк.", "Here is a park.")]),
        ],
        "words": [
            ("есть", "verb", "there is, there are", "В ко́мнате есть окно́.", "There is a window in the room.",
             [("В ко́мнате есть стол.", "There is a table in the room.")]),
            ("в", "preposition", "in", "Кни́га в столе́.", "The book is in the table.",
             [("Кни́га в шко́ле.", "The book is in the school.")]),
            ("на", "preposition", "on", "Кни́га на столе́.", "The book is on the table.",
             [("Газе́та на столе́.", "The newspaper is on the table.")]),
        ],
    },
    {
        "unit": 3,
        "title": "Everyday actions",
        "concept": "PRESENT",
        "nouns": [
            ("рабо́та", "f_a", "F", False, "work, job", "Э́то моя́ рабо́та.", "This is my work.", None,
             [("Моя́ рабо́та — чита́ть.", "My job is to read.")]),
        ],
        "verbs": [
            ("знать", "to know", "Я зна́ю э́то сло́во.", "I know this word.", "IPF", "state", None,
             [("Ты зна́ешь э́то сло́во?", "Do you know this word?")]),
            ("чита́ть", "to read", "Я чита́ю кни́гу.", "I am reading a book.", "IPF", "accomplishment", "прочитать",
             [("Он чита́ет газе́ту.", "He is reading a newspaper."), ("Мы мно́го чита́ем.", "We read a lot.")]),
            ("де́лать", "to do, to make", "Что ты де́лаешь?", "What are you doing?", "IPF", "accomplishment", "сделать",
             [("Я де́лаю рабо́ту.", "I am doing the work.")]),
            ("рабо́тать", "to work", "Ма́ма рабо́тает.", "Mum is working.", "IPF", "activity", None,
             [("Я рабо́таю в шко́ле.", "I work at school.")]),
            ("жить", "to live", "Я живу́ и рабо́таю.", "I live and work.", "IPF", "state", None,
             [("Она́ живёт в шко́ле?", "Does she live at school?")]),
            ("игра́ть", "to play", "Я игра́ю.", "I am playing.", "IPF", "activity", None,
             [("Он игра́ет в па́рке.", "He plays in the park.")]),
            ("гуля́ть", "to walk, to stroll", "Я гуля́ю в па́рке.", "I walk in the park.", "IPF", "activity", None,
             [("Мы гуля́ем в па́рке.", "We walk in the park.")]),
        ],
        "words": [
            ("он", "pronoun", "he", "Он чита́ет кни́гу.", "He is reading a book.",
             [("Он рабо́тает.", "He works.")]),
            ("она́", "pronoun", "she", "Она́ зна́ет э́то.", "She knows this.",
             [("Она́ чита́ет.", "She reads.")]),
            ("мы", "pronoun", "we", "Мы рабо́таем.", "We are working.",
             [("Мы зна́ем э́то сло́во.", "We know this word.")]),
            ("что", "pronoun", "what", "Что э́то?", "What is this?",
             [("Что ты чита́ешь?", "What are you reading?")]),
            ("мно́го", "adverb", "much, a lot", "Я мно́го рабо́таю.", "I work a lot.",
             [("Он мно́го чита́ет.", "He reads a lot.")]),
            ("ма́ло", "adverb", "little, few", "Я ма́ло чита́ю.", "I read little.",
             [("Он ма́ло рабо́тает.", "He works little.")]),
            ("то́же", "adverb", "also, too", "Я то́же чита́ю.", "I read too.",
             [("Он то́же рабо́тает.", "He works too.")]),
            ("сейча́с", "adverb", "now", "Я сейча́с рабо́таю.", "I am working now.",
             [("Он сейча́с чита́ет.", "He is reading now.")]),
        ],
    },
    {
        "unit": 4,
        "title": "Doing something to things",
        "concept": "ACC",
        "nouns": [
            ("соба́ка", "f_a", "F", True, "dog", "Я люблю́ соба́ку.", "I love the dog.", None,
             [("Соба́ка пьёт молоко́.", "The dog is drinking milk.")]),
            ("ко́шка", "f_a", "F", True, "cat", "Я ви́жу ко́шку.", "I see the cat.", {"GEN_PL": "кошек"},
             [("Ко́шка пьёт молоко́.", "The cat drinks milk.")]),
            ("хлеб", "m_hard", "M", False, "bread", "Я покупа́ю хлеб.", "I am buying bread.", {"NOM_PL": "хлеба", "ACC_PL": "хлеба"},
             [("Я ем хлеб.", "I eat bread.")]),
            ("молоко́", "n_o", "N", False, "milk", "Я пью молоко́.", "I drink milk.", "sg",
             [("Я покупа́ю молоко́.", "I am buying milk.")]),
            ("суп", "m_hard", "M", False, "soup", "Я ем суп.", "I eat soup.", None,
             [("Я люблю́ суп.", "I love soup.")]),
            ("я́блоко", "n_o", "N", False, "apple", "Я ем я́блоко.", "I eat an apple.",
             {"NOM_PL": "яблоки", "ACC_PL": "яблоки", "GEN_PL": "яблок"},
             [("Я покупа́ю я́блоко.", "I am buying an apple.")]),
        ],
        "verbs": [
            ("люби́ть", "to love, to like", "Я люблю́ ма́му.", "I love mum.", "IPF", "state", None,
             [("Я люблю́ соба́ку и ко́шку.", "I love the dog and the cat.")]),
            ("покупа́ть", "to buy", "Я покупа́ю газе́ту.", "I am buying a newspaper.", "IPF", "accomplishment", None,
             [("Я покупа́ю хлеб и молоко́.", "I am buying bread and milk.")]),
            ("пить", "to drink", "Я пью молоко́.", "I am drinking milk.", "IPF", "activity", None,
             [("Соба́ка пьёт во́ду.", "The dog drinks water.")]),
        ],
    },
    {
        "unit": 5,
        "title": "Having and not having",
        "concept": "GEN",
        "words": [
            ("у", "preposition", "at, by (have)", "У ма́мы есть кни́га.", "Mum has a book.",
             [("У меня́ есть друг.", "I have a friend.")]),
            ("меня́", "pronoun", "me (gen.)", "У меня́ есть соба́ка.", "I have a dog.",
             [("У меня́ есть чай.", "I have tea.")]),
            ("без", "preposition", "without", "Чай без воды́.", "Tea without water.",
             [("Суп без воды́.", "Soup without water.")]),
            ("чай", "noun", "tea", "Я пью чай.", "I am drinking tea.",
             [("Я люблю́ чай.", "I love tea.")]),
        ],
        "nouns": [
            ("друг", "m_hard", "M", True, "friend", "У меня́ есть друг.", "I have a friend.",
             {"NOM_PL": "друзья", "GEN_PL": "друзей", "DAT_PL": "друзьям", "INS_PL": "друзьями", "PREP_PL": "друзьях", "ACC_PL": "друзей"},
             [("Мой друг чита́ет.", "My friend is reading.")]),
        ],
    },
    {
        "unit": 6,
        "title": "Where things are",
        "concept": "PREP",
        "nouns": [
            ("го́род", "m_hard", "M", False, "city", "Я живу́ в го́роде.", "I live in the city.", {"NOM_PL": "города", "ACC_PL": "города"},
             [("Я зна́ю э́тот го́род.", "I know this city.")]),
            ("дом", "m_hard", "M", False, "house, home", "Ма́ма в до́ме.", "Mum is in the house.", {"NOM_PL": "дома", "ACC_PL": "дома"},
             [("Я живу́ в э́том до́ме.", "I live in this house.")]),
            ("страна́", "f_a", "F", False, "country", "Я живу́ в стране́.", "I live in a country.", {"NOM_PL": "страны", "ACC_PL": "страны", "GEN_PL": "стран"},
             [("Я зна́ю э́ту страну́.", "I know this country.")]),
            ("рестора́н", "m_hard", "M", False, "restaurant", "Я иду́ в рестора́н.", "I am going to the restaurant.", None,
             [("Я рабо́таю в рестора́не.", "I work in the restaurant.")]),
        ],
        "words": [
            ("о", "preposition", "about", "Кни́га о го́роде.", "A book about the city.",
             [("Кни́га о стране́.", "A book about the country.")]),
            ("здесь", "adverb", "here", "Я здесь рабо́таю.", "I work here.",
             [("Я живу́ здесь.", "I live here.")]),
        ],
    },
    {
        "unit": 7,
        "title": "Giving and telling",
        "concept": "DAT",
        "verbs": [
            ("писа́ть", "to write", "Я пишу́ ма́ме.", "I am writing to mum.", "IPF", "accomplishment", "написать",
             [("Я пишу́ дру́гу.", "I am writing to a friend.")]),
            ("дава́ть", "to give", "Я даю́ кни́гу дру́гу.", "I give the book to a friend.", "IPF", "accomplishment", None,
             [("Ма́ма даёт молоко́ ко́шке.", "Mum gives milk to the cat.")]),
        ],
        "nouns": [
            ("сосе́д", "m_hard", "M", True, "neighbour", "Я пишу́ сосе́ду.", "I am writing to my neighbour.",
             {"NOM_PL": "соседи", "GEN_PL": "соседей", "DAT_PL": "соседям", "INS_PL": "соседями", "PREP_PL": "соседях", "ACC_PL": "соседей"},
             [("Мой сосе́д рабо́тает.", "My neighbour works.")]),
            ("учи́тель", "m_soft", "M", True, "teacher", "Я даю́ кни́гу учи́телю.", "I give the book to the teacher.",
             {"NOM_PL": "учителя", "GEN_PL": "учителей", "DAT_PL": "учителям", "INS_PL": "учителями", "PREP_PL": "учителях", "ACC_PL": "учителей"},
             [("Учи́тель чита́ет кни́гу.", "The teacher reads a book.")]),
        ],
        "words": [
            ("к", "preposition", "to, towards", "Я иду́ к ма́ме.", "I am going to mum.",
             [("Я иду́ к дру́гу.", "I am going to a friend.")]),
        ],
    },
    {
        "unit": 8,
        "title": "With what, with whom",
        "concept": "INS",
        "nouns": [
            ("ру́чка", "f_a", "F", False, "pen", "Я пишу́ ру́чкой.", "I write with a pen.", {"GEN_PL": "ручек"},
             [("Э́то моя́ ру́чка.", "This is my pen.")]),
        ],
        "words": [
            ("с", "preposition", "with", "Я пью чай с ма́мой.", "I drink tea with mum.",
             [("Я иду́ с дру́гом.", "I am going with a friend.")]),
        ],
    },
    {
        "unit": 9,
        "title": "Talking about the past",
        "concept": "PAST",
        "words": [
            ("вчера́", "adverb", "yesterday", "Вчера́ я чита́л кни́гу.", "Yesterday I read a book.",
             [("Вчера́ я рабо́тал.", "Yesterday I worked.")]),
            ("они́", "pronoun", "they", "Они́ рабо́тали.", "They worked.",
             [("Они́ чита́ли кни́гу.", "They read a book.")]),
        ],
    },
    {
        "unit": 10,
        "title": "Finished or ongoing",
        "concept": "ASPECT",
        "verbs": [
            ("прочита́ть", "to read (finish)", "Вчера́ я прочита́л кни́гу.", "Yesterday I read the whole book.", "PF", "accomplishment", "читать",
             [("Я прочита́л сло́во.", "I read the word.")]),
            ("сде́лать", "to do (finish)", "Я сде́лал рабо́ту.", "I finished the work.", "PF", "accomplishment", "делать",
             [("Он сде́лал рабо́ту.", "He did the work.")]),
            ("написа́ть", "to write (finish)", "Я написа́л сло́во.", "I wrote the word.", "PF", "accomplishment", "писать",
             [("Он написа́л сло́во.", "He wrote the word.")]),
        ],
    },
]


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
