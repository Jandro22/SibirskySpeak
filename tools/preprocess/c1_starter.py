# -*- coding: utf-8 -*-
"""C1 curriculum (tier 0, units 34–40): connecting ideas, nominal style, fine
points of aspect, register, and idiom — the discourse-level skills that mark
advanced command. Grammar here is taught (lesson cards) and consolidated through
formal/abstract vocabulary in controlled sentences.
"""
from __future__ import annotations

from curriculum_common import build_level

UNITS = [
    {
        "unit": 34,
        "title": "Connecting ideas",
        "concept": "COMPLEX_SYNTAX",
        "verbs": [
            ("развива́ть", "to develop", "Шко́ла развива́ет но́вый прое́кт.", "The school is developing a new project.", "IPF", "accomplishment", "развить"),
            ("разви́ть", "to develop (fully)", "Они́ разви́ли но́вую иде́ю.", "They developed a new idea.", "PF", "accomplishment", "развивать"),
        ],
        "nouns": [
            ("разви́тие", "n_ie", "N", False, "development", "Э́то ва́жное разви́тие.", "This is an important development."),
            ("проце́сс", "m_hard", "M", False, "process", "Э́то ва́жный проце́сс.", "This is an important process."),
        ],
        "words": [
            ("одна́ко", "conjunction", "however", "Бы́ло тру́дно, одна́ко он реши́л вопро́с.", "It was difficult; however, he solved the question."),
            ("поэ́тому", "adverb", "therefore", "Э́то ва́жно, поэ́тому я чита́ю докуме́нт.", "This is important, therefore I'm reading the document."),
        ],
    },
    {
        "unit": 35,
        "title": "Nominal style",
        "concept": "NOMINALIZATION",
        "verbs": [
            ("име́ть", "to have (formal)", "Э́тот вопро́с име́ет значе́ние.", "This question has importance.", "IPF", "state", None),
            ("означа́ть", "to mean", "Э́то сло́во означа́ет вре́мя.", "This word means time.", "IPF", "state", None),
            ("представля́ть", "to present, to represent", "Я представля́ю но́вый план.", "I'm presenting a new plan.", "IPF", "accomplishment", None),
        ],
        "nouns": [
            ("значе́ние", "n_ie", "N", False, "meaning, importance", "Э́то реше́ние име́ет большо́е значе́ние.", "This decision has great importance."),
        ],
    },
    {
        "unit": 36,
        "title": "Shades of aspect",
        "concept": "ASPECT_NUANCE",
        "verbs": [
            ("продолжа́ть", "to continue", "Я продолжа́ю чита́ть кни́гу.", "I continue reading the book.", "IPF", "activity", None),
        ],
        "nouns": [
            ("ситуа́ция", "f_iya", "F", False, "situation", "Э́то тру́дная ситуа́ция.", "This is a difficult situation."),
        ],
        "words": [
            ("никогда́", "adverb", "never", "Я никогда́ не чита́л э́ту кни́гу.", "I have never read this book."),
            ("иногда́", "adverb", "sometimes", "Иногда́ я чита́ю ме́дленно.", "Sometimes I read slowly."),
        ],
    },
    {
        "unit": 37,
        "title": "Register and tone",
        "concept": "REGISTER",
        "verbs": [
            ("осуществля́ть", "to carry out (formal)", "Власть осуществля́ет но́вый план.", "The authorities are carrying out a new plan.", "IPF", "accomplishment", None),
        ],
        "nouns": [
            ("власть", "f_soft", "F", False, "power, authority", "Власть приняла́ реше́ние.", "The authority made a decision."),
        ],
        "adjs": [
            ("официа́льный", "official", "Э́то официа́льный докуме́нт.", "This is an official document."),
        ],
        "words": [
            ("да́нный", "adjective", "this, the given (formal)", "Да́нный докуме́нт о́чень ва́жный.", "This document is very important."),
            ("сле́дует", "verb", "one should (formal)", "Сле́дует чита́ть э́тот докуме́нт.", "One should read this document."),
        ],
    },
    {
        "unit": 38,
        "title": "Set phrases",
        "concept": "IDIOM",
        "nouns": [
            ("внима́ние", "n_ie", "N", False, "attention", "Он обраща́ет внима́ние на де́ло.", "He pays attention to the matter."),
        ],
        "verbs": [
            ("обраща́ть", "to turn, to direct", "Я обраща́ю внима́ние на вопро́с.", "I pay attention to the question.", "IPF", "accomplishment", None),
        ],
        "words": [
            ("име́ть в виду́", "verb", "to mean, to have in mind", "Я име́ю в виду́ э́тот вопро́с.", "I mean this question."),
            ("приня́ть во внима́ние", "verb", "to take into account", "Ну́жно приня́ть во внима́ние э́тот вопро́с.", "One must take this question into account."),
        ],
    },
    {
        "unit": 39,
        "title": "Goals and systems",
        "concept": None,
        "nouns": [
            ("цель", "f_soft", "F", False, "goal, aim", "У меня́ ва́жная цель.", "I have an important goal."),
            ("систе́ма", "f_a", "F", False, "system", "Э́то совреме́нная систе́ма.", "This is a modern system."),
            ("о́пыт", "m_hard", "M", False, "experience", "У меня́ есть большо́й о́пыт.", "I have a lot of experience."),
        ],
        "adjs": [
            ("сло́жный", "complex", "Э́то сло́жная ситуа́ция.", "This is a complex situation."),
        ],
    },
    {
        "unit": 40,
        "title": "Knowledge and language",
        "concept": None,
        "nouns": [
            ("язы́к", "m_hard", "M", False, "language, tongue", "Я изуча́ю ру́сский язы́к.", "I study the Russian language."),
            ("зна́ние", "n_ie", "N", False, "knowledge", "Зна́ние име́ет большо́е значе́ние.", "Knowledge has great importance."),
            ("иссле́дование", "n_ie", "N", False, "research, study", "Э́то ва́жное иссле́дование.", "This is an important study."),
        ],
        "verbs": [
            ("изуча́ть", "to study (a subject)", "Я изуча́ю ру́сский язы́к.", "I study the Russian language.", "IPF", "accomplishment", None),
        ],
    },
]


def c1_rows():
    return build_level(UNITS, "C1")


def c1_reader_texts():
    return [
        {
            "title": "C1 · Развитие общества",
            "source": "graded:c1",
            "body": "Разви́тие совреме́нного о́бщества име́ет большо́е значе́ние. "
                    "Власть осуществля́ет но́вые прое́кты, одна́ко не все реше́ния пра́вильные. "
                    "Учёные обраща́ют внима́ние на сло́жные ситуа́ции и предлага́ют свои́ иде́и, "
                    "поэ́тому развива́ть зна́ние ну́жно ка́ждый день.",
        },
        {
            "title": "C1 · Язык и знание",
            "source": "graded:c1",
            "body": "Изуча́ть язы́к — э́то сло́жная, но ва́жная цель. "
                    "Когда́ я говорю́, что зна́ние име́ет значе́ние, я име́ю в виду́, "
                    "что без него́ нельзя́ поня́ть совреме́нное о́бщество. "
                    "Да́нный вопро́с тре́бует вре́мени и внима́ния.",
        },
    ]


if __name__ == "__main__":
    rows = c1_rows()
    print(f"c1 notes: {len(rows)} ({sum(1 for r in rows if r['pos']=='lesson')} lessons)")
