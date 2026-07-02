# -*- coding: utf-8 -*-
"""C2 curriculum (tier 0, units 42-48): near-native command — hedged/reported
stance, emphatic and contrastive particles, bookish subordinators, marked word
order for emphasis, and deeper hypothetical/concessive nuance with бы — plus
abstract/philosophical vocabulary and the verbs of argumentation. This is the
CEFR "differentiating finer shades of meaning" band: the words and
constructions that separate advanced (C1) from near-native (C2) control.
Grammar here is taught (lesson cards) and consolidated through controlled
sentences built only from vocabulary already introduced at A1-C1 plus this
unit's own new words. Each word ships 2 example contexts where useful (a
trailing list of extra (ru, en) pairs).
"""
from __future__ import annotations

from curriculum_common import build_level

UNITS = [
    {
        "unit": 45,
        "title": "Hedging and reported stance",
        "concept": "DISCOURSE_PARTICLES",
        "words": [
            ("мол", "particle", "so he/she says (hedge on reported speech)",
             "Он сказа́л, мол, э́то не его́ вопро́с.",
             "He said - or so he claims - it's not his issue.",
             [("Она́ сказа́ла, мол, э́то ва́жно.", "She said - as she put it - this is important.")]),
            ("де́скать", "particle", "allegedly, he claims (distancing the speaker from a quote)",
             "Он сказа́л, де́скать, он не хо́чет чита́ть.",
             "He said, supposedly, that he doesn't want to read."),
            ("я́кобы", "particle", "allegedly, supposedly",
             "Она́ сказа́ла, я́кобы э́то но́вый план.",
             "She said, allegedly, that this is a new plan."),
            ("вро́де бы", "particle", "sort of, as if (soft hedge)",
             "Э́то вро́де бы тру́дный вопро́с.",
             "This is sort of a difficult question."),
            ("вро́де", "adverb", "kind of, sort of (standalone)",
             "Он вро́де рабо́тает.",
             "He's kind of working."),
        ],
    },
    {
        "unit": 46,
        "title": "Emphasis and contrast particles",
        "concept": "EMPHATIC_PARTICLES",
        "words": [
            ("ведь", "particle", "after all, you know (appeals to shared knowledge)",
             "Он ведь не чита́л э́ту кни́гу.",
             "He hasn't read this book, you know."),
            ("всё-та́ки", "particle", "still, nevertheless",
             "Э́то всё-та́ки тру́дный вопро́с.",
             "This is nevertheless a difficult question."),
            ("уж", "particle", "indeed, quite (emphatic intensifier)",
             "Э́то уж сло́жный вопро́с.",
             "This is quite a difficult question, indeed."),
            ("лишь", "particle", "only, merely",
             "Он лишь чита́ет кни́гу.",
             "He is merely reading a book."),
        ],
    },
    {
        "unit": 47,
        "title": "Literary connectors",
        "concept": "BOOKISH_SUBORDINATION",
        "words": [
            ("и́бо", "conjunction", "for, since (bookish)",
             "Он не пришёл, и́бо э́то бы́ло тру́дно.",
             "He didn't come, for it was difficult."),
            ("да́бы", "conjunction", "in order that (archaic, bookish)",
             "Он рабо́тал до́лго, да́бы разви́ть прое́кт.",
             "He worked for a long time, in order to develop the project."),
            ("поско́льку", "conjunction", "insofar as, since",
             "Поско́льку вопро́с сло́жный, ну́жно вре́мя.",
             "Since the question is complex, time is needed."),
            ("всле́дствие того́ что", "conjunction", "owing to the fact that",
             "Всле́дствие того́ что ситуа́ция сло́жная, он продолжа́ет рабо́тать.",
             "Owing to the fact that the situation is complex, he continues to work."),
            ("всле́дствие", "preposition", "as a consequence of, owing to (+ genitive)",
             "Всле́дствие э́того он не пришёл.",
             "As a consequence of this, he didn't come."),
        ],
    },
    {
        "unit": 48,
        "title": "Emphatic word order",
        "concept": "INVERSION_EMPHASIS",
        "words": [
            ("и́менно", "particle", "precisely, exactly (marks the focused word)",
             "И́менно э́то ва́жно.",
             "This precisely is what matters."),
            ("недаро́м", "adverb", "not without reason, it's no accident that",
             "Недаро́м он изуча́ет язы́к до́лго.",
             "It's no accident that he's been studying the language for a long time."),
            ("тем не ме́нее", "conjunction", "nonetheless",
             "Бы́ло тру́дно, тем не ме́нее он продолжа́л рабо́тать.",
             "It was difficult; nonetheless he kept working."),
            ("тем", "particle", "by that (fixed in тем не менее, тем самым)",
             "Он тем помо́г мне.",
             "By that, he helped me."),
            ("ме́нее", "adverb", "less",
             "Э́то ме́нее ва́жно.",
             "This is less important."),
            ("в свою́ о́чередь", "adverb", "in turn",
             "Он реши́л вопро́с; она́, в свою́ о́чередь, продолжа́ла рабо́тать.",
             "He solved the question; she, in turn, kept working."),
        ],
        "nouns": [
            ("о́чередь", "f_soft", "F", False, "turn, queue", "Э́то моя́ о́чередь.", "This is my turn.", None,
             None),
        ],
    },
    {
        "unit": 49,
        "title": "Deeper hypotheticals",
        "concept": "SUBJUNCTIVE_NUANCE",
        "words": [
            ("что бы ни", "conjunction", "whatever, no matter what",
             "Что бы ни бы́ло, он продо́лжит рабо́тать.",
             "Whatever happens, he'll keep working."),
            ("как бы ни", "conjunction", "however much, no matter how",
             "Как бы ни бы́ло тру́дно, она́ продолжа́ет рабо́тать.",
             "However difficult it may be, she keeps working."),
            ("е́сли бы не", "conjunction", "if not for",
             "Е́сли бы не о́пыт, он не реши́л бы вопро́с.",
             "If not for experience, he wouldn't have solved the question."),
            ("хотя́ бы", "particle", "at least",
             "Скажи́ хотя́ бы одно́ сло́во.",
             "Say at least one word."),
            ("хотя́", "conjunction", "although",
             "Он рабо́тал, хотя́ бы́ло тру́дно.",
             "He worked, although it was difficult."),
        ],
    },
    {
        "unit": 50,
        "title": "Abstract and philosophical vocabulary",
        "concept": None,
        "nouns": [
            ("со́весть", "f_soft", "F", False, "conscience", "У него́ есть со́весть.", "He has a conscience.", None,
             [("Э́то вопро́с со́вести.", "This is a matter of conscience.")]),
            ("убежде́ние", "n_ie", "N", False, "conviction, belief", "Э́то моё убежде́ние.", "This is my conviction.", None,
             [("У него́ есть убежде́ние.", "He has a conviction.")]),
            ("взгляд", "m_hard", "M", False, "view, outlook", "Э́то мой взгляд на вопро́с.", "This is my view on the question.", None,
             [("У него́ но́вый взгляд.", "He has a new outlook.")]),
            ("су́щность", "f_soft", "F", False, "essence", "Э́то и есть су́щность.", "This is precisely the essence.", None,
             None),
        ],
    },
    {
        "unit": 51,
        "title": "Nuanced argumentation",
        "concept": None,
        "verbs": [
            ("утвержда́ть", "to assert, to claim", "Он утвержда́ет, что э́то ва́жно.", "He asserts that this is important.", "IPF", "state", None,
             [("Она́ утвержда́ет обра́тное.", "She asserts the opposite.")]),
            ("опроверга́ть", "to refute, to disprove", "Она́ опроверга́ет его́ мне́ние.", "She refutes his opinion.", "IPF", "activity", None,
             None),
            ("подразумева́ть", "to imply", "Э́то подразумева́ет но́вый прое́кт.", "This implies a new project.", "IPF", "state", None,
             None),
        ],
        "nouns": [
            # мнение (opinion) was already introduced in B2 unit 32 — reusing it here
            # (in "опровергать его мнение") rather than re-authoring a dead-weight
            # duplicate entry that build_level's cross-level dedup would silently drop.
            ("обра́тное", "n_ie", "N", False, "the opposite", "Он сказа́л обра́тное.", "He said the opposite.", "sg",
             None),
        ],
    },
]


def c2_rows():
    return build_level(UNITS, "C2")


def c2_reader_texts():
    return [
        {
            "title": "C2 · Мне́ние и убежде́ние",
            "source": "graded:c2",
            "body": "Он утвержда́ет, что вопро́с прост, одна́ко я, в свою́ о́чередь, "
                    "опроверга́ю э́то мне́ние. Поско́льку ситуа́ция сло́жная, ну́жно вре́мя, "
                    "тем не ме́нее он всё-та́ки продолжа́ет рабо́тать. Недаро́м говоря́т, мол, "
                    "и́стина рожда́ется в спо́ре.",
        },
        {
            "title": "C2 · Со́весть и взгляд",
            "source": "graded:c2",
            "body": "Со́весть — э́то вну́тренний го́лос, кото́рый подска́зывает, что пра́вильно. "
                    "И́бо без со́вести нет и по́длинного убежде́ния. Что бы ни случи́лось, "
                    "челове́к с со́вестью всегда́ и́щет и́стину, да́же е́сли э́то тру́дно.",
        },
        {
            "title": "C2 · Спор об и́стине",
            "source": "graded:c2",
            "body": "Он я́кобы сказа́л, де́скать, его́ взгляд еди́нственно ве́рный. Ведь и́менно "
                    "так ду́мают все, кто не гото́в подразумева́ть друго́е мне́ние. "
                    "Как бы ни возража́ли ему́, он лишь повторя́ет своё убежде́ние, "
                    "хотя́ да́же он сам вро́де бы не уве́рен в нём по́лностью.",
        },
        {
            "title": "C2 · Всле́дствие обстоя́тельств",
            "source": "graded:c2",
            "body": "Всле́дствие того́ что обстоя́тельства измени́лись, реше́ние, приня́тое ра́ньше, "
                    "уж не годи́тся. Поско́льку вопро́с сло́жный, ну́жно вре́мя на разду́мья, "
                    "тем не ме́нее хотя́ бы одно́ остаётся я́сным: недаро́м говоря́т, "
                    "что су́щность де́ла ре́дко лежи́т на пове́рхности.",
        },
    ]


if __name__ == "__main__":
    rows = c2_rows()
    print(f"c2 notes: {len(rows)} ({sum(1 for r in rows if r['pos']=='lesson')} lessons)")
