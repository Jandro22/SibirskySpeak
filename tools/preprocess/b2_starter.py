# -*- coding: utf-8 -*-
"""B2 curriculum (tier 0, units 27–33): active and passive participles, verbal
adverbs (gerunds), passive constructions, and reported speech, with more
abstract vocabulary. The participle/gerund concepts are taught (lesson cards)
but not auto-drilled, since those forms can't be derived safely by the engine.
Each word ships 2 example contexts.
"""
from __future__ import annotations

from curriculum_common import build_level

UNITS = [
    {
        "unit": 27,
        "title": "The person who is reading",
        "concept": "PARTICIPLE_ACTIVE",
        "verbs": [
            ("ду́мать", "to think", "Я ду́маю о но́вом пла́не.", "I'm thinking about the new plan.", "IPF", "activity", None,
             [("Я ду́маю о рабо́те.", "I am thinking about work.")]),
            ("счита́ть", "to consider, to count", "Я счита́ю, что э́то ва́жно.", "I think that this is important.", "IPF", "state", None,
             [("Я счита́ю, что он рабо́тает хорошо́.", "I think that he works well.")]),
        ],
        "nouns": [
            ("результа́т", "m_hard", "M", False, "result", "Э́то хоро́ший результа́т.", "This is a good result.", None,
             [("Результа́т о́чень ва́жный.", "The result is very important.")]),
            ("мысль", "f_soft", "F", False, "thought", "Э́то ва́жная мысль.", "This is an important thought.", None,
             [("У меня́ есть хоро́шая мысль.", "I have a good idea.")]),
        ],
    },
    {
        "unit": 28,
        "title": "The letter that was written",
        "concept": "PARTICIPLE_PASSIVE",
        "verbs": [
            ("создава́ть", "to create", "Учёный создава́л докуме́нт.", "The scientist was creating a document.", "IPF", "accomplishment", "создать",
             [("Они́ создава́ли но́вую шко́лу.", "They were creating a new school.")]),
            ("созда́ть", "to create (once)", "Он со́здал но́вый план.", "He created a new plan.", "PF", "accomplishment", "создавать",
             [("Он со́здал но́вую шко́лу.", "He created a new school.")]),
        ],
        "nouns": [
            ("докуме́нт", "m_hard", "M", False, "document", "Я чита́ю ва́жный докуме́нт.", "I'm reading an important document.", None,
             [("Я пишу́ ва́жный докуме́нт.", "I'm writing an important document.")]),
            ("статья́", "f_ya", "F", False, "article", "Я чита́ю но́вую статью́.", "I'm reading a new article.", {"INS_SG": "статьёй"},
             [("Э́то интере́сная статья́.", "This is an interesting article.")]),
        ],
        "words": [
            # учёный is a substantivized adjective (declines учёного, учёные…), which
            # the noun engine can't model, so keep it vocab-only and used in nominative.
            ("учёный", "noun", "scientist, scholar", "Учёный реша́ет тру́дный вопро́с.", "The scientist is solving a difficult question.",
             [("Учёный пи́шет кни́гу.", "The scientist is writing a book.")]),
        ],
    },
    {
        "unit": 29,
        "title": "Doing two things at once",
        "concept": "GERUND",
        "verbs": [
            ("отвеча́ть", "to answer", "Я отвеча́ю на вопро́с.", "I'm answering the question.", "IPF", "accomplishment", "ответить",
             [("Он отвеча́ет на письмо́.", "He answers the letter.")]),
            ("отве́тить", "to answer (once)", "Он отве́тил на письмо́.", "He answered the letter.", "PF", "achievement", "отвечать",
             [("Я отве́тил на вопро́с.", "I answered the question.")]),
            ("объясня́ть", "to explain", "Я объясня́ю уро́к.", "I explain the lesson.", "IPF", "accomplishment", "объяснить",
             [("Он объясня́ет тру́дное сло́во.", "He explains a difficult word.")]),
            ("объясни́ть", "to explain (once)", "Он объясни́л тру́дный вопро́с.", "He explained the difficult question.", "PF", "achievement", "объяснять",
             [("Объясни́ э́то сло́во, пожа́луйста.", "Explain this word, please.")]),
        ],
        "nouns": [
            ("отве́т", "m_hard", "M", False, "answer", "Э́то пра́вильный отве́т.", "This is the correct answer.", None,
             [("Я зна́ю отве́т.", "I know the answer.")]),
        ],
        "adjs": [
            ("пра́вильный", "correct", "Э́то пра́вильный отве́т.", "This is the correct answer.",
             [("Он дал пра́вильный отве́т.", "He gave the correct answer.")]),
        ],
    },
    {
        "unit": 30,
        "title": "Being done",
        "concept": "PASSIVE",
        "verbs": [
            ("стро́ить", "to build", "Рабо́тники стро́ят но́вый дом.", "The workers are building a new house.", "IPF", "accomplishment", "построить",
             [("Они́ стро́ят шко́лу.", "They are building a school.")]),
            ("постро́ить", "to build (finish)", "Они́ постро́или шко́лу.", "They built a school.", "PF", "accomplishment", "строить",
             [("Мы постро́или но́вый дом.", "We built a new house.")]),
        ],
        "nouns": [
            ("зда́ние", "n_ie", "N", False, "building", "Э́то большо́е зда́ние.", "This is a big building.", None,
             [("Зда́ние о́чень краси́вое.", "The building is very beautiful.")]),
            ("заво́д", "m_hard", "M", False, "factory", "Они́ стро́ят но́вый заво́д.", "They are building a new factory.", None,
             [("Он рабо́тает на заво́де.", "He works at the factory.")]),
        ],
    },
    {
        "unit": 31,
        "title": "He said that…",
        "concept": "REPORTED",
        "verbs": [
            ("спра́шивать", "to ask", "Он спра́шивает, где я рабо́таю.", "He asks where I work.", "IPF", "accomplishment", "спросить",
             [("Я спра́шиваю, что э́то.", "I ask what this is.")]),
            ("спроси́ть", "to ask (once)", "Я спроси́л, придёт ли он.", "I asked whether he would come.", "PF", "achievement", "спрашивать",
             [("Он спроси́л, где шко́ла.", "He asked where the school is.")]),
        ],
        "nouns": [
            ("пра́вда", "f_a", "F", False, "truth", "Он сказа́л пра́вду.", "He told the truth.", None,
             [("Э́то пра́вда.", "This is true.")]),
            ("мне́ние", "n_ie", "N", False, "opinion", "Э́то моё мне́ние.", "This is my opinion.", None,
             [("Я зна́ю твоё мне́ние.", "I know your opinion.")]),
        ],
    },
    {
        "unit": 32,
        "title": "Ideas and decisions",
        "concept": None,
        "nouns": [
            ("реше́ние", "n_ie", "N", False, "decision, solution", "Э́то пра́вильное реше́ние.", "This is the correct decision.", None,
             [("Я при́нял ва́жное реше́ние.", "I made an important decision.")]),
            ("иде́я", "f_ya", "F", False, "idea", "У меня́ есть но́вая иде́я.", "I have a new idea.", None,
             [("Э́то о́чень хоро́шая иде́я.", "This is a very good idea.")]),
            ("приме́р", "m_hard", "M", False, "example", "Э́то хоро́ший приме́р.", "This is a good example.", None,
             [("Учи́тель дал приме́р.", "The teacher gave an example.")]),
        ],
        "verbs": [
            ("предлага́ть", "to propose, to offer", "Я предлага́ю но́вое реше́ние.", "I'm proposing a new solution.", "IPF", "accomplishment", "предложить",
             [("Он предлага́ет хоро́шую иде́ю.", "He proposes a good idea.")]),
            ("предложи́ть", "to propose (once)", "Он предложи́л но́вую иде́ю.", "He proposed a new idea.", "PF", "achievement", "предлагать",
             [("Я предложи́л но́вый план.", "I proposed a new plan.")]),
        ],
    },
    {
        "unit": 33,
        "title": "Society and work",
        "concept": None,
        "nouns": [
            ("о́бщество", "n_o", "N", False, "society", "Он живёт в большо́м о́бществе.", "He lives in a big society.", None,
             [("Я ду́маю об о́бществе.", "I think about society.")]),
            ("прое́кт", "m_hard", "M", False, "project", "Мы создаём но́вый прое́кт.", "We are creating a new project.", None,
             [("Э́то большо́й прое́кт.", "This is a big project.")]),
        ],
        "adjs": [
            ("совреме́нный", "modern, contemporary", "Э́то совреме́нное зда́ние.", "This is a modern building.",
             [("Э́то совреме́нный го́род.", "This is a modern city.")]),
            ("изве́стный", "famous, well-known", "Он изве́стный учёный.", "He is a famous scientist.",
             [("Э́то изве́стная кни́га.", "This is a famous book.")]),
        ],
    },
]


def b2_rows():
    return build_level(UNITS, "B2")


def b2_reader_texts():
    return [
        {
            "title": "B2 · Новый проект",
            "source": "graded:b2",
            "body": "Учёный до́лго ду́мал и реши́л созда́ть но́вый прое́кт. "
                    "Он счита́ет, что результа́т бу́дет ва́жным для всего́ о́бщества. "
                    "Когда́ его́ спроси́ли, заче́м э́то ну́жно, он отве́тил, "
                    "что хо́чет постро́ить совреме́нное зда́ние, в кото́ром бу́дут рабо́тать лю́ди.",
        },
        {
            "title": "B2 · Правильное решение",
            "source": "graded:b2",
            "body": "На рабо́те ну́жно бы́ло приня́ть тру́дное реше́ние. "
                    "Оди́н рабо́тник предложи́л но́вую иде́ю, отвеча́я на ка́ждый вопро́с споко́йно. "
                    "Докуме́нт, со́зданный им, был о́чень ва́жным. "
                    "В конце́ концо́в все согласи́лись, что э́то пра́вильное реше́ние.",
        },
    ]


if __name__ == "__main__":
    rows = b2_rows()
    print(f"b2 notes: {len(rows)} ({sum(1 for r in rows if r['pos']=='lesson')} lessons)")
