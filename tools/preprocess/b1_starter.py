# -*- coding: utf-8 -*-
"""B1 curriculum (tier 0, units 19–26): prefixed motion verbs, the conditional,
relative clauses (который), superlatives, purpose (чтобы), and number + noun case.
Each word ships 2 example contexts.
"""
from __future__ import annotations

from curriculum_common import build_level

UNITS = [
    {
        "unit": 19,
        "title": "Arriving and leaving",
        "concept": "MOTION_PREFIX",
        "verbs": [
            ("приходи́ть", "to arrive (on foot)", "Он прихо́дит домо́й ка́ждый день.", "He comes home every day.", "IPF", "activity", "прийти",
             [("Друг прихо́дит ра́но.", "A friend arrives early.")]),
            ("прийти́", "to arrive (once)", "Я хочу́, что́бы ты пришёл.", "I want you to come.", "PF", "achievement", "приходить",
             [("Он пришёл домо́й.", "He came home.")]),
            ("уходи́ть", "to leave (on foot)", "Я ухожу́ на рабо́ту ра́но.", "I leave for work early.", "IPF", "activity", "уйти",
             [("Он ухо́дит домо́й.", "He leaves for home.")]),
            ("уйти́", "to leave (once)", "Он ушёл домо́й ра́но.", "He left for home early.", "PF", "achievement", "уходить",
             [("Я ушёл на рабо́ту.", "I left for work.")]),
            ("приезжа́ть", "to arrive (by vehicle)", "Я приезжа́ю в го́род ка́ждый день.", "I arrive in the city every day.", "IPF", "activity", "приехать",
             [("Он приезжа́ет домо́й.", "He arrives home.")]),
            ("прие́хать", "to arrive (by vehicle, once)", "Я прие́хал в го́род ра́но.", "I arrived in the city early.", "PF", "achievement", "приезжать",
             [("Мы прие́хали в го́род.", "We arrived in the city.")]),
        ],
        "words": [
            ("домо́й", "adverb", "home(ward)", "Я иду́ домо́й.", "I'm going home.",
             [("Он идёт домо́й.", "He is going home.")]),
            ("ра́но", "adverb", "early", "Он прихо́дит ра́но.", "He arrives early.",
             [("Я встаю́ ра́но.", "I get up early.")]),
        ],
    },
    {
        "unit": 20,
        "title": "What I would do",
        "concept": "CONDITIONAL",
        "verbs": [
            ("хоте́ть", "to want", "Я хоте́л бы чай.", "I would like some tea.", "IPF", "state", None,
             [("Я хочу́ чита́ть.", "I want to read."), ("Он хо́чет рабо́тать.", "He wants to work.")]),
        ],
        "adjs": [
            ("счастли́вый", "happy", "Он счастли́вый челове́к.", "He is a happy person.",
             [("Э́то счастли́вый день.", "This is a happy day.")]),
            ("молодо́й", "young", "Он молодо́й челове́к.", "He is a young man.",
             [("Э́то молодо́й учи́тель.", "This is a young teacher.")]),
        ],
        "words": [
            # человек has an irregular plural (люди), so keep it vocab-only (no
            # auto-declension) and use it in the singular.
            ("челове́к", "noun", "person", "Он счастли́вый челове́к.", "He is a happy person.",
             [("Э́то молодо́й челове́к.", "This is a young man.")]),
        ],
    },
    {
        "unit": 21,
        "title": "The book that I read",
        "concept": "RELATIVE",
        "verbs": [
            ("ви́деть", "to see", "Я ви́жу кни́гу, кото́рую я чита́ю.", "I see the book that I'm reading.", "IPF", "state", "увидеть",
             [("Я ви́жу дом.", "I see a house.")]),
            ("уви́деть", "to see (catch sight of)", "Я уви́дел но́вый дом.", "I saw a new house.", "PF", "achievement", "видеть",
             [("Я уви́дел дру́га.", "I saw a friend.")]),
        ],
        "nouns": [
            ("ме́сто", "n_o", "N", False, "place, spot", "Э́то хоро́шее ме́сто.", "This is a good place.", None,
             [("Я зна́ю хоро́шее ме́сто.", "I know a good place.")]),
        ],
        "words": [
            ("кото́рый", "pronoun", "which, who, that", "Кни́га, кото́рую я чита́ю, интере́сная.", "The book that I'm reading is interesting.",
             [("Челове́к, кото́рый чита́ет, — учи́тель.", "The person who is reading is a teacher.")]),
            ("там", "adverb", "there", "Я живу́ там.", "I live there.",
             [("Он рабо́тает там.", "He works there.")]),
        ],
    },
    {
        "unit": 22,
        "title": "The biggest and best",
        "concept": "SUPERLATIVE",
        "adjs": [
            ("краси́вый", "beautiful", "Э́то краси́вый го́род.", "This is a beautiful city.",
             [("Э́то краси́вая кни́га.", "This is a beautiful book.")]),
            ("тру́дный", "difficult", "Э́то тру́дный уро́к.", "This is a difficult lesson.",
             [("Э́то тру́дная кни́га.", "This is a difficult book.")]),
            ("ста́рый", "old", "Э́то ста́рый дом.", "This is an old house.",
             [("Э́то ста́рый го́род.", "This is an old city.")]),
        ],
        "words": [
            ("са́мый", "pronoun", "the most", "Э́то са́мый большо́й го́род.", "This is the biggest city.",
             [("Э́то са́мая интере́сная кни́га.", "This is the most interesting book.")]),
        ],
    },
    {
        "unit": 23,
        "title": "In order to",
        "concept": "PURPOSE",
        "verbs": [
            ("реша́ть", "to decide, to solve", "Я реша́ю, что де́лать.", "I am deciding what to do.", "IPF", "accomplishment", "решить",
             [("Он реша́ет тру́дный вопро́с.", "He is solving a difficult question.")]),
            ("реши́ть", "to decide (once)", "Я реши́л прийти́ ра́но.", "I decided to come early.", "PF", "achievement", "решать",
             [("Он реши́л тру́дный вопро́с.", "He solved a difficult question.")]),
        ],
        "nouns": [
            ("вопро́с", "m_hard", "M", False, "question", "Э́то тру́дный вопро́с.", "This is a difficult question.", None,
             [("Э́то ва́жный вопро́с.", "This is an important question.")]),
        ],
    },
    {
        "unit": 24,
        "title": "Counting things",
        "concept": "NUMERAL_CASE",
        "words": [
            ("оди́н", "numeral", "one", "У меня́ оди́н друг.", "I have one friend.",
             [("Я ви́жу оди́н дом.", "I see one house.")]),
            ("два", "numeral", "two", "У меня́ две кни́ги.", "I have two books.",
             [("Я ви́жу два до́ма.", "I see two houses.")]),
            ("три", "numeral", "three", "Я ви́жу три маши́ны.", "I see three cars.",
             [("У меня́ три дру́га.", "I have three friends.")]),
            ("пять", "numeral", "five", "У меня́ пять книг.", "I have five books.",
             [("Здесь пять домо́в.", "There are five houses here.")]),
            ("шесть", "numeral", "six", "Я зна́ю шесть слов.", "I know six words.",
             [("У меня́ шесть книг.", "I have six books.")]),
            ("де́сять", "numeral", "ten", "Здесь де́сять книг.", "There are ten books here.",
             [("Я зна́ю де́сять слов.", "I know ten words.")]),
            ("ско́лько", "adverb", "how many", "Ско́лько книг у тебя́?", "How many books do you have?",
             [("Ско́лько у тебя́ друзе́й?", "How many friends do you have?")]),
        ],
    },
    {
        "unit": 25,
        "title": "Work and people",
        "concept": None,
        "nouns": [
            ("рабо́тник", "m_hard", "M", True, "worker", "Рабо́тник реша́ет тру́дный вопро́с.", "The worker is solving a difficult question.", None,
             [("Рабо́тник прихо́дит ра́но.", "The worker arrives early.")]),
        ],
        "words": [
            ("по́здно", "adverb", "late", "Он прихо́дит по́здно.", "He arrives late.",
             [("Я рабо́таю по́здно.", "I work late.")]),
            # время is irregular (времени, временем); keep vocab-only, used in nom/acc.
            ("вре́мя", "noun", "time", "У меня́ есть вре́мя.", "I have time.",
             [("Сейча́с есть вре́мя.", "There is time now.")]),
        ],
    },
    {
        "unit": 26,
        "title": "Plans and choices",
        "concept": None,
        "verbs": [
            ("выбира́ть", "to choose", "Я выбира́ю но́вую кни́гу.", "I am choosing a new book.", "IPF", "accomplishment", "выбрать",
             [("Он выбира́ет но́вый план.", "He is choosing a new plan.")]),
            ("вы́брать", "to choose (once)", "Я вы́брал но́вую маши́ну.", "I chose a new car.", "PF", "achievement", "выбирать",
             [("Мы вы́брали хоро́ший план.", "We chose a good plan.")]),
            ("начина́ть", "to begin", "Я начина́ю но́вый уро́к.", "I am beginning a new lesson.", "IPF", "accomplishment", "начать",
             [("Он начина́ет рабо́ту.", "He is beginning the work.")]),
            ("нача́ть", "to begin (once)", "Я на́чал но́вую кни́гу.", "I began a new book.", "PF", "achievement", "начинать",
             [("Мы на́чали но́вый план.", "We started a new plan.")]),
        ],
        "nouns": [
            ("план", "m_hard", "M", False, "plan", "У меня́ есть план.", "I have a plan.", None,
             [("Э́то хоро́ший план.", "This is a good plan.")]),
            ("оши́бка", "f_a", "F", False, "mistake", "Э́то моя́ оши́бка.", "This is my mistake.", {"GEN_PL": "ошибок"},
             [("Я ви́жу оши́бку.", "I see a mistake.")]),
        ],
    },
]


def b1_rows():
    return build_level(UNITS, "B1")


def b1_reader_texts():
    return [
        {
            "title": "B1 · Мои планы на неделю",
            "source": "graded:b1",
            "body": "У меня́ есть план на неде́лю. Ка́ждый день я ухожу́ на рабо́ту ра́но "
                    "и прихожу́ домо́й по́здно. Я реши́л, что бу́ду чита́ть бо́льше, "
                    "что́бы лу́чше понима́ть ру́сский язы́к. Кни́га, кото́рую я выбира́ю "
                    "сейча́с, — са́мая интере́сная.",
        },
        {
            "title": "B1 · Трудный вопрос",
            "source": "graded:b1",
            "body": "На рабо́те есть оди́н тру́дный вопро́с. Рабо́тник до́лго реша́л его́. "
                    "Е́сли бы у меня́ бы́ло вре́мя, я бы помо́г. "
                    "Мы реши́ли э́тот вопро́с вме́сте, и тепе́рь все счастли́вы.",
        },
    ]


if __name__ == "__main__":
    rows = b1_rows()
    print(f"b1 notes: {len(rows)} ({sum(1 for r in rows if r['pos']=='lesson')} lessons)")
