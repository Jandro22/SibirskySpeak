# -*- coding: utf-8 -*-
"""A2 curriculum (tier 0, units 11–18): builds on A1 with the future tense,
commands, reflexive verbs, comparison, modals, verbs of motion, and свой.
Each word ships 2 example contexts.

Entry formats and rules: see curriculum_common.py. Every example uses only
vocabulary already introduced (A1 + earlier A2 units); validated by tests.
"""
from __future__ import annotations

from curriculum_common import build_level

UNITS = [
    {
        "unit": 11,
        "title": "Talking about tomorrow",
        "concept": "FUTURE",
        "nouns": [
            ("уро́к", "m_hard", "M", False, "lesson, class", "За́втра бу́дет уро́к.", "Tomorrow there will be a lesson.", None,
             [("Уро́к бу́дет за́втра.", "The lesson will be tomorrow.")]),
            ("письмо́", "n_o", "N", False, "letter", "Я бу́ду писа́ть письмо́.", "I will be writing a letter.", {"GEN_PL": "писем"},
             [("Я пишу́ письмо́ ма́ме.", "I'm writing a letter to mum.")]),
            ("экза́мен", "m_hard", "M", False, "exam", "За́втра бу́дет экза́мен.", "Tomorrow there will be an exam.", None,
             [("Сего́дня экза́мен.", "Today is the exam.")]),
        ],
        "verbs": [
            ("понима́ть", "to understand", "Я понима́ю уро́к.", "I understand the lesson.", "IPF", "state", "понять",
             [("Ты понима́ешь э́то сло́во?", "Do you understand this word?")]),
            ("поня́ть", "to understand (grasp)", "Я по́нял э́то сло́во.", "I understood this word.", "PF", "achievement", "понимать",
             [("Я по́нял уро́к.", "I understood the lesson.")]),
        ],
    },
    {
        "unit": 12,
        "title": "Asking and telling",
        "concept": "IMPERATIVE",
        "verbs": [
            ("говори́ть", "to speak, to say", "Говори́ ме́дленно, пожа́луйста.", "Speak slowly, please.", "IPF", "activity", "сказать",
             [("Ма́ма говори́т ме́дленно.", "Mum speaks slowly."), ("Я говорю́ с дру́гом.", "I am speaking with a friend.")]),
            ("сказа́ть", "to say (once)", "Скажи́ э́то сло́во.", "Say this word.", "PF", "achievement", "говорить",
             [("Скажи́, что ты зна́ешь.", "Say what you know.")]),
            ("слу́шать", "to listen", "Слу́шай уро́к!", "Listen to the lesson!", "IPF", "activity", None,
             [("Я слу́шаю ма́му.", "I'm listening to mum.")]),
        ],
        "adjs": [
            ("ме́дленный", "slow", "Э́то ме́дленный уро́к.", "This is a slow lesson.",
             [("Э́то ме́дленная маши́на.", "This is a slow car.")]),
        ],
        "words": [
            ("пожа́луйста", "particle", "please, you're welcome", "Чита́й, пожа́луйста.", "Read, please.",
             [("Помоги́ мне, пожа́луйста.", "Help me, please.")]),
            ("гро́мко", "adverb", "loudly", "Говори́ гро́мко!", "Speak loudly!",
             [("Он говори́т гро́мко.", "He speaks loudly.")]),
        ],
    },
    {
        "unit": 13,
        "title": "Doing it to yourself",
        "concept": "REFLEXIVE",
        "verbs": [
            ("учи́ться", "to study, to learn", "Я учу́сь в шко́ле.", "I study at school.", "IPF", "activity", None,
             [("Я учу́сь и рабо́таю.", "I study and work.")]),
            ("занима́ться", "to be busy with, to study", "Я занима́юсь до́ма.", "I study at home.", "IPF", "activity", None,
             [("Он занима́ется в шко́ле.", "He studies at school.")]),
            ("встреча́ться", "to meet (each other)", "Мы встреча́емся в шко́ле.", "We meet at school.", "IPF", "activity", None,
             [("Они́ встреча́ются в па́рке.", "They meet in the park.")]),
        ],
        "nouns": [
            ("де́ло", "n_o", "N", False, "matter, business", "Э́то ва́жное де́ло.", "This is an important matter.", None,
             [("У меня́ мно́го дел.", "I have a lot of things to do.")]),
        ],
        "adjs": [
            ("ва́жный", "important", "Э́то ва́жный уро́к.", "This is an important lesson.",
             [("Э́то ва́жное де́ло.", "This is an important matter.")]),
        ],
    },
    {
        "unit": 14,
        "title": "More and less",
        "concept": "COMPARATIVE",
        "adjs": [
            ("интере́сный", "interesting", "Э́то интере́сная кни́га.", "This is an interesting book.",
             [("Э́то интере́сный го́род.", "This is an interesting city.")]),
            ("бы́стрый", "fast", "Э́то бы́страя маши́на.", "This is a fast car.",
             [("Э́то бы́страя соба́ка.", "This is a fast dog.")]),
            ("большо́й", "big", "Э́то большо́й го́род.", "This is a big city.",
             [("Э́то большо́й дом.", "This is a big house.")]),
            ("хоро́ший", "good", "Э́то хоро́ший друг.", "This is a good friend.",
             [("Э́то хоро́шая кни́га.", "This is a good book.")]),
            ("дорого́й", "expensive, dear", "Э́то дорога́я маши́на.", "This is an expensive car.",
             [("Э́то дорого́й дом.", "This is an expensive house.")]),
            ("дешёвый", "cheap", "Э́то дешёвая газе́та.", "This is a cheap newspaper.",
             [("Э́то дешёвый хлеб.", "This is cheap bread.")]),
        ],
        "words": [
            ("чем", "conjunction", "than", "Кни́га интере́снее, чем газе́та.", "The book is more interesting than the newspaper.",
             [("Кни́га интере́снее, чем уро́к.", "The book is more interesting than the lesson.")]),
        ],
    },
    {
        "unit": 15,
        "title": "Can, must, need",
        "concept": "MODAL",
        "verbs": [
            ("помога́ть", "to help", "Я помога́ю ма́ме.", "I help mum.", "IPF", "activity", "помочь",
             [("Друг помога́ет мне.", "A friend helps me.")]),
            ("помо́чь", "to help (once)", "Помоги́ дру́гу, пожа́луйста.", "Help your friend, please.", "PF", "achievement", "помогать",
             [("Он помо́г мне.", "He helped me.")]),
        ],
        "words": [
            ("на́до", "predicative", "(it is) necessary", "Мне на́до рабо́тать.", "I need to work.",
             [("На́до мно́го чита́ть.", "One needs to read a lot.")]),
            ("ну́жно", "predicative", "(it is) needed", "Ну́жно чита́ть кни́ги.", "One needs to read books.",
             [("Мне ну́жно рабо́тать.", "I need to work.")]),
            ("мо́жно", "predicative", "(it is) allowed", "Здесь мо́жно чита́ть?", "May one read here?",
             [("Здесь мо́жно рабо́тать.", "One may work here.")]),
            ("нельзя́", "predicative", "(it is) forbidden", "Здесь нельзя́ говори́ть.", "One may not talk here.",
             [("Здесь нельзя́ рабо́тать.", "One may not work here.")]),
            ("до́лжен", "predicative", "must, obliged to", "Я до́лжен мно́го рабо́тать.", "I must work a lot.",
             [("Он до́лжен чита́ть.", "He must read.")]),
        ],
    },
    {
        "unit": 16,
        "title": "Coming and going",
        "concept": "MOTION",
        "verbs": [
            ("идти́", "to go (on foot, now)", "Я иду́ в шко́лу.", "I'm going to school.", "IPF", "activity", None,
             [("Я иду́ в магази́н.", "I'm going to the shop.")]),
            ("ходи́ть", "to go (on foot, often)", "Я ча́сто хожу́ в парк.", "I often go to the park.", "IPF", "activity", None,
             [("Ма́ма ча́сто хо́дит в магази́н.", "Mum often goes to the shop.")]),
            ("е́хать", "to go (by vehicle, now)", "Я е́ду в го́род.", "I'm going to the city.", "IPF", "activity", None,
             [("Я е́ду на авто́бусе.", "I'm going by bus.")]),
            ("е́здить", "to go (by vehicle, often)", "Я ча́сто е́зжу в го́род.", "I often go to the city.", "IPF", "activity", None,
             [("Он е́здит на рабо́ту.", "He goes to work by vehicle.")]),
        ],
        "nouns": [
            ("магази́н", "m_hard", "M", False, "shop", "Я иду́ в магази́н.", "I'm going to the shop.", None,
             [("Магази́н на у́лице.", "The shop is on the street.")]),
            ("у́лица", "f_a", "F", False, "street", "Маши́на на у́лице.", "The car is on the street.", None,
             [("Дом на у́лице.", "The house is on the street.")]),
            ("авто́бус", "m_hard", "M", False, "bus", "Я е́ду на авто́бусе.", "I'm going by bus.", None,
             [("Авто́бус е́дет в го́род.", "The bus goes to the city.")]),
        ],
        "words": [
            ("ча́сто", "adverb", "often", "Я ча́сто чита́ю.", "I often read.",
             [("Он ча́сто хо́дит в парк.", "He often goes to the park.")]),
        ],
    },
    {
        "unit": 17,
        "title": "One's own things",
        "concept": "POSSESSIVE_SVOJ",
        "nouns": [
            ("сестра́", "f_a", "F", True, "sister", "Я люблю́ свою́ сестру́.", "I love my sister.",
             {"NOM_PL": "сёстры", "GEN_PL": "сестёр", "DAT_PL": "сёстрам", "INS_PL": "сёстрами", "PREP_PL": "сёстрах", "ACC_PL": "сестёр"},
             [("Моя́ сестра́ рабо́тает.", "My sister works.")]),
            ("кварти́ра", "f_a", "F", False, "flat, apartment", "Он чита́ет в свое́й кварти́ре.", "He reads in his flat.", None,
             [("Э́то моя́ кварти́ра.", "This is my flat.")]),
        ],
        "verbs": [
            ("забыва́ть", "to forget", "Я не забыва́ю свои́ кни́ги.", "I don't forget my books.", "IPF", "activity", "забыть",
             [("Он ча́сто забыва́ет слова́.", "He often forgets words.")]),
            ("забы́ть", "to forget (once)", "Я забы́л свои́ кни́ги до́ма.", "I forgot my books at home.", "PF", "achievement", "забывать",
             [("Я забы́л своё письмо́.", "I forgot my letter.")]),
        ],
    },
    {
        "unit": 18,
        "title": "Days and weeks",
        "concept": None,
        "nouns": [
            ("неде́ля", "f_ya", "F", False, "week", "Я рабо́таю всю неде́лю.", "I work all week.", None,
             [("Я живу́ здесь неде́лю.", "I have lived here for a week.")]),
            ("ме́сяц", "m_hard", "M", False, "month", "Я живу́ здесь ме́сяц.", "I have lived here for a month.", {"GEN_PL": "месяцев"},
             [("Он рабо́тал здесь ме́сяц.", "He worked here for a month.")]),
            ("год", "m_hard", "M", False, "year", "Я живу́ здесь год.", "I have lived here for a year.", "sg",
             [("Я рабо́таю здесь год.", "I work here for a year.")]),
        ],
        "adjs": [
            ("но́вый", "new", "Э́то но́вая маши́на.", "This is a new car.",
             [("Э́то но́вый дом.", "This is a new house.")]),
        ],
        "words": [
            ("ка́ждый", "pronoun", "every, each", "Я чита́ю ка́ждый день.", "I read every day.",
             [("Ка́ждый день есть уро́к.", "Every day there is a lesson.")]),
            ("день", "noun", "day", "Я рабо́таю ка́ждый день.", "I work every day.",
             [("Сего́дня хоро́ший день.", "Today is a good day.")]),
            ("всю", "pronoun", "all, whole (f. acc)", "Я рабо́таю всю неде́лю.", "I work all week.",
             [("Я чита́л всю кни́гу.", "I read the whole book.")]),
        ],
    },
]


def a2_rows():
    return build_level(UNITS, "A2")


def a2_reader_texts():
    return [
        {
            "title": "A2 · Мой день и мои планы",
            "source": "graded:a2",
            "body": "Ка́ждый день я хожу́ в шко́лу и учу́сь. Сего́дня хоро́ший день. "
                    "За́втра бу́дет уро́к, и я бу́ду чита́ть но́вую кни́гу. "
                    "Э́та кни́га интере́снее, чем газе́та. Мне на́до рабо́тать, но я люблю́ свою́ рабо́ту.",
        },
        {
            "title": "A2 · В магазин",
            "source": "graded:a2",
            "body": "Я е́ду в го́род. Снача́ла я иду́ в магази́н. "
                    "«Скажи́, пожа́луйста, где хлеб?» Я покупа́ю хлеб и газе́ту. "
                    "По́сле я помога́ю сестре́ и чита́ю письмо́.",
        },
    ]


if __name__ == "__main__":
    rows = a2_rows()
    print(f"a2 notes: {len(rows)} ({sum(1 for r in rows if r['pos']=='lesson')} lessons)")
