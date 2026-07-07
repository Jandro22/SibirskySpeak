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
            ("пожа́луйста", "interjection", "please, you're welcome", "Да, пожа́луйста.", "Yes, please.",
             [("Спаси́бо! — Пожа́луйста.", "Thank you! - You're welcome.")]),
            ("коне́чно", "adverb", "of course, certainly", "Да, коне́чно.", "Yes, of course.",
             [("Э́то хорошо́? — Коне́чно.", "Is this good? - Of course.")]),
            ("ну", "interjection", "well, now", "Ну, хорошо́.", "Well, okay.",
             [("Ну да.", "Well, yes.")]),
        ],
    },
    {
        # G8 spiral resequencing: the five A1 case-family concepts (ACC,
        # GEN_CHUNK_POSSESSION/ABSENCE, PREP_CHUNK_LOCATION, DAT_CHUNK_EXPERIENCER,
        # INS_CHUNK_WITH) used to sit in five back-to-back units — textbook
        # blocked/blocked practice, the sequencing choice SLA interleaving
        # research consistently finds worse than spacing same-family concepts
        # apart. GENDER's vocabulary got split across two units (GEN1 here,
        # "More things around us" below) purely to create enough non-case
        # material to interleave with; no words moved levels or were dropped.
        "unit": 1,
        "title": "Things around us",
        "concept": "GENDER",
        "topic": "home",
        "nouns": [
            ("стол", "m_hard", "M", False, "table", "Э́то стол.", "This is a table.", None,
             [("Вот мой стол.", "Here is my table.")]),
            ("стул", "m_hard", "M", False, "chair", "Вот стул, а вот стол.", "Here is a chair, and here is a table.",
             {"NOM_PL": "стулья", "GEN_PL": "стульев", "DAT_PL": "стульям", "INS_PL": "стульями", "PREP_PL": "стульях", "ACC_PL": "стулья"},
             [("Э́то мой стул.", "This is my chair.")]),
            ("кни́га", "f_a", "F", False, "book", "Э́то кни́га.", "This is a book.", None,
             [("Вот моя́ кни́га.", "Here is my book.")]),
            ("окно́", "n_o", "N", False, "window", "Вот окно́.", "Here is a window.", {"GEN_PL": "окон"},
             [("Э́то окно́.", "This is a window.")]),
            ("вода́", "f_a", "F", False, "water", "Вот вода́.", "Here is water.", None,
             [("Э́то вода́.", "This is water.")]),
            ("ко́мната", "f_a", "F", False, "room", "Э́то ко́мната.", "This is a room.", None,
             [("Вот моя́ ко́мната.", "Here is my room.")]),
        ],
        "adjs": [
            ("ру́сский", "Russian", "Э́то ру́сская кни́га.", "This is a Russian book.",
             [("Вот ру́сский стол.", "Here is a Russian table.")]),
        ],
        "words": [
            ("вот", "particle", "here is", "Вот кни́га.", "Here is the book.",
             [("Вот стол.", "Here is a table.")]),
            ("мой", "pronoun", "my", "Э́то мой стол.", "This is my table.",
             [("Вот мой стул.", "Here is my chair.")]),
            ("моя́", "pronoun", "my, feminine", "Э́то моя́ кни́га.", "This is my book.",
             [("Вот моя́ ко́мната.", "Here is my room.")]),
            ("э́тот", "pronoun", "this, that", "Э́тот стол мой.", "This table is mine.",
             [("Вот э́тот стол.", "Here is this table.")]),
        ],
    },
    {
        "unit": 2,
        "title": "More things around us",
        "concept": None,
        "topic": "home",
        "nouns": [
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
        "words": [
            ("тот", "pronoun", "that", "Тот телефо́н мой.", "That phone is mine.",
             [("Вот тот телефо́н.", "Here is that phone.")]),
            ("твой", "pronoun", "your, yours (sg. informal)", "Э́то твой телефо́н.", "This is your phone.",
             [("Вот твой стул.", "Here is your chair.")]),
            ("ведь", "conjunction", "after all, you know", "Э́то ведь хорошо́.", "This is good, after all.",
             [("Вот ведь стол.", "Here is the table, after all.")]),
        ],
    },
    {
        # Adjective agreement depends on knowing noun gender, so it's taught
        # immediately after GENDER (units 1-2) and before anything else — every
        # example sentence from here on can lean on it. Previously this concept
        # had a full Kotlin lesson (GrammarConcepts.ADJ_AGREE) but no curriculum
        # unit ever taught it, so CardType.ADJ_AGREE drills fired with no lesson
        # ever shown first, silently violating teach-before-test.
        "unit": 3,
        "title": "Describing things",
        "concept": "ADJ_AGREE",
        "adjs": [
            ("тёплый", "warm", "Э́то тёплая вода́.", "This is warm water.",
             [("Вот тёплый стол.", "Here is a warm table.")]),
            ("холо́дный", "cold", "Э́то холо́дная ко́мната.", "This is a cold room.",
             [("Вот холо́дное окно́.", "Here is a cold window.")]),
            ("друго́й", "other, another", "Вот тёплый стул, а вот друго́й стул.", "Here is a warm chair, and here is another chair.",
             [("Э́то ру́сский стол? — Нет, друго́й.", "Is this a Russian table? - No, another one.")]),
        ],
        "words": [
            ("наш", "pronoun", "our, ours", "Э́то наш стол.", "This is our table.",
             [("Вот наш стул.", "Here is our chair.")]),
            ("ваш", "pronoun", "your, yours (pl. or formal)", "Э́то ваш стол.", "This is your table.",
             [("Вот ваш стул.", "Here is your chair.")]),
            ("тако́й", "pronoun", "such, like this", "Э́то тако́й тёплый стол.", "This is such a warm table.",
             [("Вот тако́й стул.", "Here is a chair like this.")]),
            ("како́й", "pronoun", "which, what kind of", "Како́й э́то стол?", "Which table is this?",
             [("Кака́я э́то кни́га?", "Which book is this.")]),
        ],
    },
    {
        "unit": 4,
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
        # First case-family unit of the spiral (see G8 note on unit 1).
        "unit": 5,
        "title": "Having and not having",
        "concept": "GEN_CHUNK_POSSESSION",
        "extraConcepts": ["GEN_CHUNK_ABSENCE"],
        "words": [
            ("у", "preposition", "at, by (have)", "У ма́мы есть кни́га.", "Mum has a book.",
             [("У меня́ есть друг.", "I have a friend.")]),
            ("меня́", "pronoun", "me (gen.)", "У меня́ есть чай.", "I have tea.",
             [("У меня́ нет воды́.", "I have no water.")]),
            ("без", "preposition", "without", "Чай без воды́.", "Tea without water.",
             [("Стол без воды́.", "A table without water.")]),
            ("чай", "noun", "tea", "Я пью чай.", "I am drinking tea.",
             [("Я люблю́ чай.", "I love tea.")]),
        ],
        "nouns": [
            ("друг", "m_hard", "M", True, "friend", "У меня́ есть друг.", "I have a friend.",
             {"NOM_PL": "друзья", "GEN_PL": "друзей", "DAT_PL": "друзьям", "INS_PL": "друзьями", "PREP_PL": "друзьях", "ACC_PL": "друзей"},
             [("У ма́мы есть друг.", "Mum has a friend.")]),
        ],
    },
    {
        "unit": 6,
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
            ("да́же", "adverb", "even", "Он да́же зна́ет э́то сло́во.", "He even knows this word.",
             [("Он да́же де́лает рабо́ту.", "He even does the work.")]),
        ],
    },
    {
        # Second case-family unit of the spiral (see G8 note on unit 1) —
        # spaced from GEN_CHUNK_POSSESSION (unit 5) by PRESENT (unit 6).
        "unit": 7,
        "title": "Doing something to things",
        "concept": "ACC",
        "topic": "food_drink",
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
        "unit": 8,
        "title": "More everyday actions",
        "concept": None,
        "verbs": [
            ("жить", "to live", "Я живу́ и рабо́таю.", "I live and work.", "IPF", "state", None,
             [("Она́ живёт в шко́ле?", "Does she live at school?")]),
            ("игра́ть", "to play", "Я игра́ю.", "I am playing.", "IPF", "activity", None,
             [("Он игра́ет в па́рке.", "He plays in the park.")]),
            ("гуля́ть", "to walk, to stroll", "Я гуля́ю в па́рке.", "I walk in the park.", "IPF", "activity", None,
             [("Мы гуля́ем в па́рке.", "We walk in the park.")]),
        ],
        "words": [
            ("мно́го", "adverb", "much, a lot", "Я мно́го рабо́таю.", "I work a lot.",
             [("Он мно́го чита́ет.", "He reads a lot.")]),
            ("ма́ло", "adverb", "little, few", "Я ма́ло чита́ю.", "I read little.",
             [("Он ма́ло рабо́тает.", "He works little.")]),
            ("то́же", "adverb", "also, too", "Я то́же чита́ю.", "I read too.",
             [("Он то́же рабо́тает.", "He works too.")]),
            ("сейча́с", "adverb", "now", "Я сейча́с рабо́таю.", "I am working now.",
             [("Он сейча́с чита́ет.", "He is reading now.")]),
            ("куда́", "adverb", "where to, whither", "Куда́ ты идёшь?", "Where are you going?",
             [("Куда́ он идёт?", "Where is he going?")]),
            ("лишь", "adverb", "only, just", "У меня́ лишь одна́ кни́га.", "I only have one book.",
             [("Я зна́ю лишь э́то сло́во.", "I only know this word.")]),
            ("и́менно", "adverb", "exactly, precisely", "И́менно э́тот парк.", "Exactly this park.",
             [("И́менно э́та кни́га.", "Exactly this book.")]),
            ("ра́зве", "adverb", "really?, is it possible?", "Ра́зве ты не зна́ешь э́то?", "Don't you know this?",
             [("Ра́зве э́то твой стул?", "Is that really your chair?")]),
            ("наве́рное", "adverb", "probably, perhaps", "Он, наве́рное, рабо́тает.", "He is probably working.",
             [("Она́, наве́рное, чита́ет кни́гу.", "She is probably reading a book.")]),
            ("та́кже", "adverb", "also, too", "Я та́кже люблю́ чай.", "I also love tea.",
             [("Он та́кже рабо́тает.", "He also works.")]),
            ("вообще́-то", "adverb", "as a matter of fact, actually", "Вообще́-то, я рабо́таю.", "Actually, I am working.",
             [("Вообще́-то, э́то мой стул.", "Actually, this is my chair.")]),
            ("одна́ко", "adverb", "however, yet", "Она́, одна́ко, рабо́тает.", "She, however, is working.",
             [("Я, одна́ко, чита́ю кни́гу.", "I, however, am reading a book.")]),
        ],
    },
    {
        # Third case-family unit of the spiral (see G8 note on unit 1) —
        # spaced from ACC (unit 7) by "More everyday actions" (unit 8).
        "unit": 9,
        "title": "Where things are",
        "concept": "PREP_CHUNK_LOCATION",
        "topic": "city_transport",
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
        "unit": 10,
        "title": "Talking about the past",
        "concept": "PAST",
        "topic": "time",
        "words": [
            ("вчера́", "adverb", "yesterday", "Вчера́ я чита́л кни́гу.", "Yesterday I read a book.",
             [("Вчера́ я рабо́тал.", "Yesterday I worked.")]),
            ("они́", "pronoun", "they", "Они́ рабо́тали.", "They worked.",
             [("Они́ чита́ли кни́гу.", "They read a book.")]),
        ],
    },
    {
        # Fourth case-family unit of the spiral (see G8 note on unit 1) —
        # spaced from PREP_CHUNK_LOCATION (unit 9) by PAST (unit 10). Needs
        # "друг" (introduced unit 5, GEN_CHUNK_POSSESSION) for the dative form.
        "unit": 11,
        "title": "Giving and telling",
        "concept": "DAT_CHUNK_EXPERIENCER",
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
            ("к", "preposition", "to, toward", "Я иду́ к ма́ме.", "I am going to mum.",
             [("Я иду́ к дру́гу.", "I am going to a friend.")]),
            ("жаль", "adjective", "it's a pity, sorry", "Мне жаль.", "I am sorry.",
             [("Мне жаль дру́га.", "I feel sorry for my friend.")]),
        ],
    },
    {
        # Depends on DAT_CHUNK_EXPERIENCER (unit 11): написать's aspect
        # partner писать is taught there.
        "unit": 12,
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
    {
        # Fifth case-family unit of the spiral (see G8 note on unit 1) —
        # spaced from DAT_CHUNK_EXPERIENCER (unit 11) by ASPECT (unit 12).
        # Needs "друг" (unit 5) and "ма́ма" (unit 2) for instrumental forms.
        "unit": 13,
        "title": "With what, with whom",
        "concept": "INS_CHUNK_WITH",
        "nouns": [
            ("ру́чка", "f_a", "F", False, "pen", "Я пишу́ ру́чкой.", "I write with a pen.", {"GEN_PL": "ручек"},
             [("Э́то моя́ ру́чка.", "This is my pen.")]),
        ],
        "words": [
            ("с", "preposition", "with", "Я пью чай с ма́мой.", "I drink tea with mum.",
             [("Я иду́ с дру́гом.", "I am going with a friend.")]),
            ("пе́ред", "preposition", "in front of, before", "Маши́на пе́ред до́мом.", "The car is in front of the house.",
             [("Я был пе́ред до́мом.", "I was in front of the house.")]),
        ],
    },
    {
        # G9: cardinals 0 and 11-20 as chunk-first vocabulary. 1-10 already
        # exist as notes at B1 unit 26 (concept NUMERAL_CASE, which teaches the
        # productive numeral+noun government system: 2-4 + genitive singular,
        # 5+ + genitive plural — NUMERAL_GOV_234/NUMERAL_GOV_5 in
        # GrammarConcepts.kt); re-declaring those lemmas here would just waste
        # review budget (see test_authored_lemmas_are_unique_across_levels), so
        # this unit only adds the numerals B1 doesn't already cover. At A1
        # these are memorized as invariant counting words, not yet combined
        # live with declined nouns. CARDINALS (the G7 spine lesson introducing
        # numerals as a topic) is scheduled at unit 11 by spine2_rows(), so it
        # leads this vocabulary.
        "unit": 14,
        "title": "Counting: 0, and 11-20",
        "concept": None,
        "topic": "numerals",
        "words": [
            ("ноль", "noun", "zero", "У меня́ ноль книг.", "I have zero books.",
             [("Здесь ноль столо́в.", "There are zero tables here.")]),
            ("оди́ннадцать", "numeral", "eleven", "У меня́ оди́ннадцать книг.", "I have eleven books.",
             [("Здесь оди́ннадцать столо́в.", "There are eleven tables here.")]),
            ("двена́дцать", "numeral", "twelve", "У меня́ двена́дцать книг.", "I have twelve books.",
             [("Здесь двена́дцать о́кон.", "There are twelve windows here.")]),
            ("трина́дцать", "numeral", "thirteen", "У меня́ трина́дцать книг.", "I have thirteen books.",
             [("Здесь трина́дцать столо́в.", "There are thirteen tables here.")]),
            ("четы́рнадцать", "numeral", "fourteen", "У меня́ четы́рнадцать книг.", "I have fourteen books.",
             [("Здесь четы́рнадцать о́кон.", "There are fourteen windows here.")]),
            ("пятна́дцать", "numeral", "fifteen", "У меня́ пятна́дцать книг.", "I have fifteen books.",
             [("Здесь пятна́дцать столо́в.", "There are fifteen tables here.")]),
            ("шестна́дцать", "numeral", "sixteen", "У меня́ шестна́дцать книг.", "I have sixteen books.",
             [("Здесь шестна́дцать о́кон.", "There are sixteen windows here.")]),
            ("семна́дцать", "numeral", "seventeen", "У меня́ семна́дцать книг.", "I have seventeen books.",
             [("Здесь семна́дцать столо́в.", "There are seventeen tables here.")]),
            ("восемна́дцать", "numeral", "eighteen", "У меня́ восемна́дцать книг.", "I have eighteen books.",
             [("Здесь восемна́дцать о́кон.", "There are eighteen windows here.")]),
            ("девятна́дцать", "numeral", "nineteen", "У меня́ девятна́дцать книг.", "I have nineteen books.",
             [("Здесь девятна́дцать столо́в.", "There are nineteen tables here.")]),
            ("два́дцать", "numeral", "twenty", "У меня́ два́дцать книг.", "I have twenty books.",
             [("Здесь два́дцать о́кон.", "There are twenty windows here.")]),
        ],
    },
    {
        # G9: tens and hundreds 30-1000, plus a chunk-first price phrase
        # (numeral + ruble/cost as fixed memorized phrases, per the numerals
        # topic checklist). Government of the following noun's case is not
        # explained here — see NUMERAL_GOV_234/5 (B1).
        "unit": 15,
        "title": "Counting: tens and prices",
        "concept": None,
        "topic": "numerals",
        "nouns": [
            ("рубль", "m_soft", "M", False, "ruble", "Кни́га сто́ит два́дцать рубле́й.", "The book costs twenty rubles.", "sg",
             [("Хлеб сто́ит два́дцать рубле́й.", "The bread costs twenty rubles.")]),
        ],
        "verbs": [
            ("сто́ить", "to cost", "Кни́га сто́ит два́дцать рубле́й.", "The book costs twenty rubles.", "IPF", "state", None,
             [("Хлеб сто́ит два́дцать рубле́й.", "The bread costs twenty rubles.")]),
        ],
        "words": [
            ("три́дцать", "numeral", "thirty", "У меня́ три́дцать рубле́й.", "I have thirty rubles.",
             [("Кни́га сто́ит три́дцать рубле́й.", "The book costs thirty rubles.")]),
            ("со́рок", "numeral", "forty", "У меня́ со́рок рубле́й.", "I have forty rubles.",
             [("Хлеб сто́ит со́рок рубле́й.", "The bread costs forty rubles.")]),
            ("пятьдеся́т", "numeral", "fifty", "У меня́ пятьдеся́т рубле́й.", "I have fifty rubles.",
             [("Кни́га сто́ит пятьдеся́т рубле́й.", "The book costs fifty rubles.")]),
            ("шестьдеся́т", "numeral", "sixty", "У меня́ шестьдеся́т рубле́й.", "I have sixty rubles.",
             [("Хлеб сто́ит шестьдеся́т рубле́й.", "The bread costs sixty rubles.")]),
            ("се́мьдесят", "numeral", "seventy", "У меня́ се́мьдесят рубле́й.", "I have seventy rubles.",
             [("Кни́га сто́ит се́мьдесят рубле́й.", "The book costs seventy rubles.")]),
            ("во́семьдесят", "numeral", "eighty", "У меня́ во́семьдесят рубле́й.", "I have eighty rubles.",
             [("Хлеб сто́ит во́семьдесят рубле́й.", "The bread costs eighty rubles.")]),
            ("девяно́сто", "numeral", "ninety", "У меня́ девяно́сто рубле́й.", "I have ninety rubles.",
             [("Кни́га сто́ит девяно́сто рубле́й.", "The book costs ninety rubles.")]),
            ("сто", "numeral", "one hundred", "У меня́ сто рубле́й.", "I have one hundred rubles.",
             [("Хлеб сто́ит сто рубле́й.", "The bread costs one hundred rubles.")]),
            ("две́сти", "numeral", "two hundred", "Кни́га сто́ит две́сти рубле́й.", "The book costs two hundred rubles.",
             [("У меня́ две́сти рубле́й.", "I have two hundred rubles.")]),
            ("три́ста", "numeral", "three hundred", "Кни́га сто́ит три́ста рубле́й.", "The book costs three hundred rubles.",
             [("У меня́ три́ста рубле́й.", "I have three hundred rubles.")]),
            ("пятьсо́т", "numeral", "five hundred", "Кни́га сто́ит пятьсо́т рубле́й.", "The book costs five hundred rubles.",
             [("У меня́ пятьсо́т рубле́й.", "I have five hundred rubles.")]),
            ("ты́сяча", "numeral", "one thousand", "У меня́ ты́сяча рубле́й.", "I have one thousand rubles.",
             [("Здесь ты́сяча книг.", "There are a thousand books here.")]),
        ],
    },
    {
        # G9: time-telling chunks (hour + parts of the day) — the "few
        # time-telling/price chunks" the numerals checklist item calls for, so
        # this unit counts toward both the "numerals" and "time" thematic
        # fields (see build_level's list-valued "topic" support).
        "unit": 16,
        "title": "Telling time",
        "concept": None,
        "topic": ["numerals", "time"],
        "nouns": [
            ("час", "m_hard", "M", False, "hour, o'clock", "Сейча́с оди́н час.", "It is one o'clock now.", "sg",
             [("У меня́ уро́к в час.", "I have a lesson at one o'clock.")]),
        ],
        "words": [
            ("по́лдень", "noun", "noon", "Сейча́с по́лдень.", "It is noon now.",
             [("У меня́ уро́к в по́лдень.", "I have a lesson at noon.")]),
            ("по́лночь", "noun", "midnight", "Сейча́с по́лночь.", "It is midnight now.",
             [("У меня́ уро́к в по́лночь.", "I have a lesson at midnight.")]),
            ("у́тро", "noun", "morning", "Сейча́с у́тро.", "It is morning now.",
             [("Здесь тёплое у́тро.", "It's a warm morning here.")]),
            ("ве́чер", "noun", "evening", "Сейча́с ве́чер.", "It is evening now.",
             [("Здесь тёплый ве́чер.", "It's a warm evening here.")]),
        ],
    },
    {
        # G9 thematic-coverage repair: a dedicated family unit (unit 2 already
        # has ма́ма/па́па but is tagged "home" for its other nouns; "сестра́" is
        # already an A2 note, so it isn't repeated here — see
        # test_authored_lemmas_are_unique_across_levels).
        "unit": 17,
        "title": "Family",
        "concept": None,
        "topic": "family",
        "nouns": [
            ("брат", "m_hard", "M", True, "brother", "Э́то мой брат.", "This is my brother.",
             {"NOM_PL": "братья", "GEN_PL": "братьев", "DAT_PL": "братьям", "INS_PL": "братьями", "PREP_PL": "братьях", "ACC_PL": "братьев"},
             [("У меня́ есть брат.", "I have a brother.")]),
            ("ба́бушка", "f_a", "F", True, "grandmother", "Э́то моя́ ба́бушка.", "This is my grandmother.", {"GEN_PL": "бабушек"},
             [("У меня́ есть ба́бушка.", "I have a grandmother.")]),
            ("де́душка", "f_a", "M", True, "grandfather", "Э́то мой де́душка.", "This is my grandfather.", {"GEN_PL": "дедушек"},
             [("У меня́ есть де́душка.", "I have a grandfather.")]),
            ("семья́", "f_ya", "F", False, "family", "Э́то моя́ семья́.", "This is my family.", None,
             [("У меня́ хоро́шая семья́.", "I have a good family.")]),
        ],
    },
    {
        # G9 thematic-coverage repair: body/health field (previously absent
        # from A1). боле́ть used impersonally (У меня́ боли́т голова́) is a
        # very common A1 chunk; the full dative-experiencer pattern echoes
        # DAT_CHUNK_EXPERIENCER (unit 11).
        "unit": 18,
        "title": "Body and health",
        "concept": None,
        "topic": "body_health",
        "nouns": [
            ("голова́", "f_a", "F", False, "head", "У меня́ боли́т голова́.", "My head hurts.",
             {"NOM_PL": "головы", "ACC_PL": "головы", "GEN_PL": "голов"},
             [("Голова́ не боли́т.", "My head doesn't hurt.")]),
            ("рука́", "f_a", "F", False, "hand, arm", "У меня́ боли́т рука́.", "My hand hurts.",
             {"NOM_PL": "руки", "ACC_PL": "руки", "GEN_PL": "рук"},
             [("Вот моя́ рука́.", "Here is my hand.")]),
            ("нога́", "f_a", "F", False, "leg, foot", "У меня́ боли́т нога́.", "My leg hurts.",
             {"NOM_PL": "ноги", "ACC_PL": "ноги", "GEN_PL": "ног"},
             [("Вот моя́ нога́.", "Here is my leg.")]),
        ],
        "verbs": [
            ("боле́ть", "to hurt, to ache", "У меня́ боли́т голова́.", "My head hurts.", "IPF", "state", None,
             [("У меня́ боли́т нога́.", "My leg hurts.")]),
        ],
    },
    {
        # G9 thematic-coverage repair: weather field (previously absent).
        # Reuses тёплый/холо́дный (ADJ_AGREE, unit 3) instead of new adjectives.
        "unit": 19,
        "title": "Weather",
        "concept": None,
        "topic": "weather",
        "nouns": [
            ("пого́да", "f_a", "F", False, "weather", "Сего́дня хоро́шая пого́да.", "The weather is good today.", None,
             [("Здесь тёплая пого́да.", "The weather is warm here.")]),
            ("со́лнце", "n_o", "N", False, "sun", "Сего́дня есть со́лнце.", "There is sun today.", "sg",
             [("Со́лнце тёплое.", "The sun is warm.")]),
            ("дождь", "m_hard", "M", False, "rain", "Сего́дня дождь.", "It is raining today.", None,
             [("Вчера́ был дождь.", "Yesterday there was rain.")]),
            ("снег", "m_hard", "M", False, "snow", "Сего́дня снег.", "It is snowing today.", "sg",
             [("Вчера́ был снег.", "Yesterday there was snow.")]),
        ],
        "words": [
            ("сего́дня", "adverb", "today", "Сего́дня хоро́шая пого́да.", "The weather is good today.",
             [("Сего́дня есть со́лнце.", "There is sun today.")]),
        ],
    },
    {
        # G9 thematic-coverage repair: clothing field (previously absent).
        "unit": 20,
        "title": "Clothing",
        "concept": None,
        "topic": "clothing",
        "nouns": [
            ("руба́шка", "f_a", "F", False, "shirt", "Э́то моя́ руба́шка.", "This is my shirt.", {"GEN_PL": "рубашек"},
             [("Вот но́вая руба́шка.", "Here is a new shirt.")]),
            ("пла́тье", "n_e", "N", False, "dress", "Э́то моё пла́тье.", "This is my dress.", None,
             [("Вот но́вое пла́тье.", "Here is a new dress.")]),
            ("ку́ртка", "f_a", "F", False, "jacket", "Э́то моя́ ку́ртка.", "This is my jacket.", {"GEN_PL": "курток"},
             [("Вот тёплая ку́ртка.", "Here is a warm jacket.")]),
            ("о́бувь", "f_soft", "F", False, "footwear, shoes", "Э́то моя́ о́бувь.", "This is my footwear.", "sg",
             [("Вот но́вая о́бувь.", "Here is new footwear.")]),
        ],
    },
    {
        # G9 thematic-coverage repair: basic emotions field (previously
        # absent). "счастли́вый" is already a B1 note (test_curriculum_quality
        # forbids reintroducing a lemma), so distinct A1-appropriate emotion
        # adjectives are used here instead.
        "unit": 21,
        "title": "Feelings",
        "concept": None,
        "topic": "emotions",
        "adjs": [
            ("ве́сёлый", "cheerful, happy", "Он ве́сёлый студе́нт.", "He is a cheerful student.",
             [("Э́то ве́сёлая соба́ка.", "This is a cheerful dog.")]),
            ("гру́стный", "sad", "Она́ гру́стная сего́дня.", "She is sad today.",
             [("Э́то гру́стная кни́га.", "This is a sad book.")]),
            ("уста́лый", "tired", "Я уста́лый сего́дня.", "I am tired today.",
             [("Он уста́лый по́сле рабо́ты.", "He is tired after work.")]),
        ],
        "words": [
            ("по́сле", "preposition", "after", "Он уста́лый по́сле рабо́ты.", "He is tired after work.",
             [("По́сле шко́лы я гуля́ю.", "After school I go for a walk.")]),
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
        {
            "title": "A1 · Тёплый чай",
            "source": "graded:a1",
            "body": "Вот тёплый чай и тёплая ко́мната. "
                    "Мой друг живёт здесь. У меня́ есть кни́га о го́роде. "
                    "Мы пьём чай и чита́ем кни́гу.",
        },
        {
            "title": "A1 · Мой сосе́д",
            "source": "graded:a1",
            "body": "У меня́ есть сосе́д. Он рабо́тает в рестора́не. "
                    "Он идёт на рабо́ту. "
                    "Вчера́ я написа́л сло́во сосе́ду.",
        },
    ]


if __name__ == "__main__":
    rows = a1_rows()
    lessons = [r for r in rows if r["pos"] == "lesson"]
    print(f"a1 notes: {len(rows)} ({len(lessons)} lessons), readers: {len(a1_reader_texts())}")
