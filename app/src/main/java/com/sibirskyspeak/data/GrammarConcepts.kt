package com.sibirskyspeak.data

/**
 * The A1 grammar spine. Each [GrammarConcept] is taught by a LESSON card *before*
 * any drill on it can surface (see [LearningRepository] concept gating), and its
 * one-line [hint] is shown on the drill prompt itself so the learner is reminded
 * of the rule while answering — not only after revealing the answer.
 *
 * Concept ids are stable strings stored on cards (`gramConcept`). For case drills
 * the id is the case code ("ACC", "GEN", ...); other drills map via [forCard].
 */
data class GrammarConcept(
    val id: String,
    val title: String,
    /** Plain-English explanation shown on the LESSON card. */
    val lesson: String,
    /** A worked Russian example with its translation, shown on the LESSON card. */
    val exampleRu: String,
    val exampleEn: String,
    /** One-line reminder shown on every drill prompt for this concept. */
    val hint: String,
    /**
     * Ordering within the A1 spine. Lower comes first. Concepts that are part of the
     * core spine ([spine] = true) gate the formal/political domain tier: the domain
     * stays locked until every spine concept's lesson has been seen.
     */
    val order: Int,
    val spine: Boolean = true
)

object GrammarConcepts {
    val GENDER = GrammarConcept(
        id = "GENDER",
        title = "Noun gender",
        lesson = "Every Russian noun is masculine, feminine, or neuter. You usually " +
            "can tell from the ending: a consonant or -й is masculine, -а/-я is " +
            "feminine, -о/-е is neuter. Gender matters because adjectives and past-" +
            "tense verbs change their endings to match the noun.",
        exampleRu = "стол (м.) · книга (ж.) · окно (ср.)",
        exampleEn = "table (m.) · book (f.) · window (n.)",
        hint = "Ending → gender: consonant/-й = masc, -а/-я = fem, -о/-е = neut.",
        order = 10
    )

    val NOM_PL = GrammarConcept(
        id = "NOM_PL",
        title = "Making plurals",
        lesson = "To talk about more than one thing, most masculine and feminine " +
            "nouns add -ы or -и, and most neuter nouns change -о/-е to -а/-я. This " +
            "is the plural you use as the subject of a sentence.",
        exampleRu = "стол → столы · книга → книги · окно → окна",
        exampleEn = "table → tables · book → books · window → windows",
        hint = "Plural: usually -ы/-и; neuter -о/-е → -а/-я.",
        order = 20
    )

    val ACC = GrammarConcept(
        id = "ACC",
        title = "The accusative (direct object)",
        lesson = "The accusative marks the direct object — the thing the action " +
            "happens to. Masculine inanimate and neuter nouns look the same as the " +
            "dictionary form; feminine nouns change -а → -у and -я → -ю.",
        exampleRu = "Я читаю книгу.",
        exampleEn = "I'm reading a book. (книга → книгу)",
        hint = "Direct object → accusative. Feminine -а → -у, -я → -ю.",
        order = 30
    )

    val GEN = GrammarConcept(
        id = "GEN",
        title = "The genitive (\"of\", absence)",
        lesson = "The genitive shows possession or \"of\" something, and is used for " +
            "absence (нет + genitive) and after words like без, из, у. Masculine/" +
            "neuter nouns usually take -а/-я; feminine -а/-я become -ы/-и.",
        exampleRu = "У меня нет времени.",
        exampleEn = "I have no time. (время → времени)",
        hint = "\"of\" / нет / без / из / у → genitive.",
        order = 40
    )

    val PREP = GrammarConcept(
        id = "PREP",
        title = "The prepositional (location)",
        lesson = "The prepositional only appears after a preposition — mainly в/на " +
            "for location (\"in/at\") and о for a topic (\"about\"). Most nouns simply " +
            "take the ending -е.",
        exampleRu = "Книга на столе.",
        exampleEn = "The book is on the table. (стол → столе)",
        hint = "After в/на (where) and о (about) → prepositional, usually -е.",
        order = 50
    )

    val DAT = GrammarConcept(
        id = "DAT",
        title = "The dative (\"to/for\")",
        lesson = "The dative marks the recipient — the person something is given, " +
            "said, or shown to. It also appears after к and по, and in age and " +
            "\"need\" phrases. Masculine/neuter take -у/-ю; feminine take -е.",
        exampleRu = "Я пишу другу.",
        exampleEn = "I'm writing to a friend. (друг → другу)",
        hint = "Recipient \"to/for\", к, по, age → dative.",
        order = 60
    )

    val INS = GrammarConcept(
        id = "INS",
        title = "The instrumental (\"with/by\")",
        lesson = "The instrumental shows the means \"by/with\" which something is done, " +
            "and follows с (\"together with\"). Masculine/neuter take -ом/-ем; " +
            "feminine take -ой/-ей.",
        exampleRu = "Я пишу ручкой.",
        exampleEn = "I write with a pen. (ручка → ручкой)",
        hint = "Means \"by/with\", and с (together with) → instrumental.",
        order = 70
    )

    val PAST = GrammarConcept(
        id = "PAST",
        title = "The past tense",
        lesson = "The Russian past tense is easy: drop -ть and add -л for a male " +
            "subject, -ла for female, -ло for neuter, -ли for plural. It agrees with " +
            "the subject's gender and number, not the person.",
        exampleRu = "Он читал. Она читала. Они читали.",
        exampleEn = "He read. She read. They read.",
        hint = "Past = stem + -л/-ла/-ло/-ли (agrees with subject gender/number).",
        order = 80
    )

    val ADJ_AGREE = GrammarConcept(
        id = "ADJ_AGREE",
        title = "Adjective agreement",
        lesson = "Adjectives copy the gender and number of the noun they describe. " +
            "The dictionary form is masculine (-ый/-ий); feminine ends -ая, neuter " +
            "-ое, and plural -ые/-ие.",
        exampleRu = "новый дом · новая книга · новое окно · новые дома",
        exampleEn = "new house · new book · new window · new houses",
        hint = "Adjective matches its noun: -ый(м) / -ая(ж) / -ое(ср) / -ые(мн).",
        order = 90,
        spine = false
    )

    val ASPECT = GrammarConcept(
        id = "ASPECT",
        title = "Verb aspect",
        lesson = "Most Russian verbs come in pairs. The imperfective describes an " +
            "ongoing or repeated action (\"was doing\"); the perfective describes a " +
            "single completed action with a result (\"did / got done\"). Choose by " +
            "whether the action reached its end.",
        exampleRu = "Я писал письмо. / Я написал письмо.",
        exampleEn = "I was writing a letter. / I wrote (finished) a letter.",
        hint = "Ongoing/repeated = imperfective; completed result = perfective.",
        order = 100,
        spine = false
    )

    // --- A2 -----------------------------------------------------------------
    val FUTURE = GrammarConcept(
        id = "FUTURE",
        title = "The future tense",
        lesson = "Russian has two futures. The imperfective future uses бу́ду, бу́дешь, " +
            "бу́дет … plus the imperfective infinitive (ongoing/repeated). The " +
            "perfective future is just the perfective verb conjugated like the " +
            "present — it already means a single completed future action.",
        exampleRu = "Я бу́ду чита́ть. / Я прочита́ю кни́гу.",
        exampleEn = "I will be reading. / I will read (finish) the book.",
        hint = "Future: буду + imperfective inf, OR conjugated perfective.",
        order = 110, spine = false
    )
    val IMPERATIVE = GrammarConcept(
        id = "IMPERATIVE",
        title = "Commands (imperative)",
        lesson = "To tell someone to do something, take the present-tense stem and add " +
            "-й (after a vowel), -и (after a consonant, stressed), or -ь. Add -те for " +
            "polite/plural. Imperfective invites; perfective asks for one result.",
        exampleRu = "Чита́й! Чита́йте! Напиши́те письмо́.",
        exampleEn = "Read! Read (pl.)! Write the letter.",
        hint = "Command: stem + -й/-и(+те). Perfective = one result.",
        order = 120, spine = false
    )
    val REFLEXIVE = GrammarConcept(
        id = "REFLEXIVE",
        title = "Reflexive verbs (-ся)",
        lesson = "Verbs ending in -ся/-сь turn the action back on the subject or make " +
            "it intransitive: учи́ть (teach) → учи́ться (study), нача́ть (begin sth) → " +
            "нача́ться (begin, of itself). The ending is -ся after a consonant, -сь " +
            "after a vowel.",
        exampleRu = "Я учу́сь. Уро́к начина́ется.",
        exampleEn = "I study. The lesson begins.",
        hint = "-ся/-сь = action on oneself / intransitive.",
        order = 130, spine = false
    )
    val COMPARATIVE = GrammarConcept(
        id = "COMPARATIVE",
        title = "Comparing things",
        lesson = "For \"more\", most adjectives and adverbs add -ее (бы́стрый → быстре́е), " +
            "with some irregulars (хоро́ший → лу́чше, большо́й → бо́льше). \"Than\" is чем, " +
            "or put the compared thing in the genitive.",
        exampleRu = "Э́та кни́га интере́снее. Москва́ бо́льше.",
        exampleEn = "This book is more interesting. Moscow is bigger.",
        hint = "More: adjective + -ее (лучше/больше). \"Than\" = чем or genitive.",
        order = 140, spine = false
    )
    val MODAL = GrammarConcept(
        id = "MODAL",
        title = "Can, must, need (dative)",
        lesson = "\"Possible/allowed\" мо́жно, \"necessary\" на́до/ну́жно, \"impossible/" +
            "forbidden\" нельзя́ are used with the dative of the person and an " +
            "infinitive. There is no \"to be\" in the present.",
        exampleRu = "Мне на́до рабо́тать. Здесь мо́жно кури́ть?",
        exampleEn = "I need to work. May one smoke here?",
        hint = "надо/нужно/можно/нельзя + dative person + infinitive.",
        order = 150, spine = false
    )
    val MOTION = GrammarConcept(
        id = "MOTION",
        title = "Verbs of motion (go)",
        lesson = "Russian splits \"go\" by manner and direction. Use идти́/е́хать for one " +
            "trip in one direction now; ходи́ть/е́здить for repeated, round, or general " +
            "movement. идти́/ходи́ть = on foot, е́хать/е́здить = by vehicle.",
        exampleRu = "Я иду́ в шко́лу. Я ча́сто хожу́ в парк.",
        exampleEn = "I'm going to school (now). I often go to the park.",
        hint = "идти/ехать = one trip now; ходить/ездить = repeated/round.",
        order = 160, spine = false
    )
    val POSSESSIVE_SVOJ = GrammarConcept(
        id = "POSSESSIVE_SVOJ",
        title = "One's own (свой)",
        lesson = "свой means \"one's own\" and refers back to the subject of the clause. " +
            "It agrees like мой. Use it instead of его/её/их when the owner is the " +
            "subject, to avoid ambiguity.",
        exampleRu = "Он чита́ет свою́ кни́гу.",
        exampleEn = "He is reading his (own) book.",
        hint = "свой = belonging to the subject; agrees like мой.",
        order = 170, spine = false
    )

    // --- B1 -----------------------------------------------------------------
    val MOTION_PREFIX = GrammarConcept(
        id = "MOTION_PREFIX",
        title = "Prefixed motion verbs",
        lesson = "Prefixes add direction to motion verbs: при- (arrive), у- (leave), " +
            "в-/во- (enter), вы- (exit), по- (set off). On идти-type stems they form " +
            "aspect pairs (приходи́ть/прийти́).",
        exampleRu = "Он пришёл домо́й и ушёл сно́ва.",
        exampleEn = "He came home and left again.",
        hint = "при-=arrive, у-=leave, в-=enter, вы-=exit, по-=set off.",
        order = 210, spine = false
    )
    val CONDITIONAL = GrammarConcept(
        id = "CONDITIONAL",
        title = "Would / conditional (бы)",
        lesson = "The particle бы plus a past-tense verb expresses a hypothetical: " +
            "\"would\". For \"if\", use е́сли бы … (past), … бы … (past) in both clauses.",
        exampleRu = "Я бы помо́г. Е́сли бы я знал, я бы сказа́л.",
        exampleEn = "I would help. If I had known, I would have said.",
        hint = "бы + past = \"would\". Если бы … , … бы …",
        order = 220, spine = false
    )
    val RELATIVE = GrammarConcept(
        id = "RELATIVE",
        title = "Which / who (который)",
        lesson = "кото́рый links clauses (\"which/who/that\"). It agrees in gender and " +
            "number with the noun it refers to, but takes its case from its role in " +
            "its own clause.",
        exampleRu = "Кни́га, кото́рую я чита́ю, интере́сная.",
        exampleEn = "The book that I'm reading is interesting.",
        hint = "который: gender/number from antecedent, case from its own clause.",
        order = 230, spine = false
    )
    val SUPERLATIVE = GrammarConcept(
        id = "SUPERLATIVE",
        title = "The most (superlative)",
        lesson = "The most common superlative puts са́мый (agreeing like an adjective) " +
            "before the adjective. Some forms also use -ейший/-айший.",
        exampleRu = "Э́то са́мый интере́сный го́род.",
        exampleEn = "This is the most interesting city.",
        hint = "самый + adjective = \"the most\".",
        order = 240, spine = false
    )
    val PURPOSE = GrammarConcept(
        id = "PURPOSE",
        title = "In order to (чтобы)",
        lesson = "что́бы expresses purpose. With the same subject, use чтобы + infinitive. " +
            "With a different subject, use чтобы + past tense (the subjunctive use).",
        exampleRu = "Я чита́ю, что́бы учи́ться. Я хочу́, что́бы ты пришёл.",
        exampleEn = "I read in order to study. I want you to come.",
        hint = "чтобы + infinitive (same subj) / + past (different subj).",
        order = 250, spine = false
    )
    val NUMERAL_CASE = GrammarConcept(
        id = "NUMERAL_CASE",
        title = "Numbers and nouns",
        lesson = "After numbers the noun changes: 1 → nominative; 2–4 → genitive " +
            "singular; 5 and up → genitive plural. This also follows ско́лько and " +
            "мно́го.",
        exampleRu = "одна́ кни́га, две кни́ги, пять книг",
        exampleEn = "one book, two books, five books",
        hint = "1 → nom; 2–4 → gen sg; 5+ → gen pl.",
        order = 260, spine = false
    )

    // --- B2 -----------------------------------------------------------------
    val PARTICIPLE_ACTIVE = GrammarConcept(
        id = "PARTICIPLE_ACTIVE",
        title = "Active participles",
        lesson = "Active participles work like \"-ing/who-does\" adjectives. Present: " +
            "stem + -ущ/-ющ/-ащ/-ящ + adjective endings; past: -вш-. They agree with " +
            "their noun and replace a кото́рый clause where that noun is the subject.",
        exampleRu = "челове́к, чита́ющий кни́гу",
        exampleEn = "the person reading a book (who reads a book)",
        hint = "Active participle = \"who does X\"; agrees like an adjective.",
        order = 310, spine = false
    )
    val PARTICIPLE_PASSIVE = GrammarConcept(
        id = "PARTICIPLE_PASSIVE",
        title = "Passive participles",
        lesson = "Passive participles mean \"-ed/being done\". Present: -ем/-им; past: " +
            "-нн/-енн/-т. The short past form (-н, -на, -но, -ны) builds the perfect " +
            "passive: «письмо́ напи́сано».",
        exampleRu = "кни́га, напи́санная им; письмо́ напи́сано",
        exampleEn = "the book written by him; the letter is written",
        hint = "Passive participle = \"X-ed\"; short form -н/-на/-но/-ны = result.",
        order = 320, spine = false
    )
    val GERUND = GrammarConcept(
        id = "GERUND",
        title = "Verbal adverbs (gerunds)",
        lesson = "A gerund (деепричастие) describes a secondary action by the same " +
            "subject. Imperfective: stem + -я (\"while doing\"); perfective: -в/-вши " +
            "(\"having done\"). It never changes form.",
        exampleRu = "Чита́я письмо́, он молча́л.",
        exampleEn = "Reading the letter, he was silent.",
        hint = "Gerund: -я (while doing) / -в (having done); invariable.",
        order = 330, spine = false
    )
    val PASSIVE = GrammarConcept(
        id = "PASSIVE",
        title = "Passive constructions",
        lesson = "Russian forms the passive two ways: with a reflexive imperfective " +
            "verb (дом строи́тся — \"the house is being built\") or with a short passive " +
            "participle for completed results (дом постро́ен). The agent, if named, " +
            "goes in the instrumental.",
        exampleRu = "Дом стро́ится рабо́чими. Догово́р подпи́сан.",
        exampleEn = "The house is being built by workers. The treaty is signed.",
        hint = "Passive: -ся verb (process) or short participle (result); agent = instrumental.",
        order = 340, spine = false
    )
    val REPORTED = GrammarConcept(
        id = "REPORTED",
        title = "Reported speech",
        lesson = "Russian keeps the original tense in reported speech (no backshift). " +
            "Use что for statements, ли for yes/no questions, and the original " +
            "question word otherwise.",
        exampleRu = "Он сказа́л, что рабо́тает. Я спроси́л, придёт ли он.",
        exampleEn = "He said (that) he works. I asked whether he would come.",
        hint = "Reported: что / ли / question word; tense doesn't shift.",
        order = 350, spine = false
    )

    // --- C1 -----------------------------------------------------------------
    val COMPLEX_SYNTAX = GrammarConcept(
        id = "COMPLEX_SYNTAX",
        title = "Connecting ideas",
        lesson = "Cohesive C1 writing chains clauses with connectors: одна́ко " +
            "(however), поэ́тому (therefore), несмотря́ на то что (despite the fact " +
            "that), в то вре́мя как (whereas), благодаря́ тому́ что (thanks to). They " +
            "let you subordinate and contrast precisely.",
        exampleRu = "Несмотря́ на то что бы́ло тру́дно, он успе́л.",
        exampleEn = "Despite the fact that it was hard, he made it in time.",
        hint = "однако / поэтому / несмотря на то что / в то время как.",
        order = 410, spine = false
    )
    val NOMINALIZATION = GrammarConcept(
        id = "NOMINALIZATION",
        title = "Nominal style",
        lesson = "Formal Russian prefers verbal nouns over verbs: реши́ть → приня́тие " +
            "реше́ния (taking a decision), разви́ть → разви́тие. This dense nominal " +
            "style dominates official and academic registers.",
        exampleRu = "приня́тие реше́ния заняло́ вре́мя",
        exampleEn = "taking the decision took time",
        hint = "Formal register nominalises verbs: решить → принятие решения.",
        order = 420, spine = false
    )
    val ASPECT_NUANCE = GrammarConcept(
        id = "ASPECT_NUANCE",
        title = "Fine points of aspect",
        lesson = "Beyond completion, aspect carries nuance: the imperfective can deny a " +
            "fact (Я не чита́л — \"I didn't read it\"), state a general fact, or soften; " +
            "the perfective stresses result and single occurrence. Negated commands " +
            "usually take the imperfective.",
        exampleRu = "Не чита́й э́то! / Прочита́й э́то!",
        exampleEn = "Don't read this! / Read this (through)!",
        hint = "Imperfective: process/fact/negated commands; perfective: single result.",
        order = 430, spine = false
    )
    val REGISTER = GrammarConcept(
        id = "REGISTER",
        title = "Register and style",
        lesson = "C1 control means matching register: neutral vs. bookish/official " +
            "(данный = \"this\", осуществля́ть = \"carry out\", в связи́ с = \"in connection " +
            "with\") vs. colloquial. Word choice signals formality as much as grammar.",
        exampleRu = "в связи́ с да́нным реше́нием",
        exampleEn = "in connection with this decision (formal)",
        hint = "Match register: officialese (данный, осуществлять, в связи с) vs. neutral.",
        order = 440, spine = false
    )
    val IDIOM = GrammarConcept(
        id = "IDIOM",
        title = "Set phrases and idiom",
        lesson = "Fluent Russian relies on fixed collocations and idioms whose meaning " +
            "isn't literal: име́ть в виду́ (to mean), приня́ть во внима́ние (to take into " +
            "account), име́ть значе́ние (to matter). Learn them as whole units.",
        exampleRu = "Я име́ю в виду́ друго́е.",
        exampleEn = "I mean something else.",
        hint = "Learn set phrases whole: иметь в виду, принять во внимание.",
        order = 450, spine = false
    )

    val ALL: List<GrammarConcept> =
        listOf(
            // A1 core spine
            GENDER, NOM_PL, ACC, GEN, PREP, DAT, INS, PAST, ADJ_AGREE, ASPECT,
            // A2
            FUTURE, IMPERATIVE, REFLEXIVE, COMPARATIVE, MODAL, MOTION, POSSESSIVE_SVOJ,
            // B1
            MOTION_PREFIX, CONDITIONAL, RELATIVE, SUPERLATIVE, PURPOSE, NUMERAL_CASE,
            // B2
            PARTICIPLE_ACTIVE, PARTICIPLE_PASSIVE, GERUND, PASSIVE, REPORTED,
            // C1
            COMPLEX_SYNTAX, NOMINALIZATION, ASPECT_NUANCE, REGISTER, IDIOM
        )

    private val byId: Map<String, GrammarConcept> = ALL.associateBy { it.id }

    fun byId(id: String?): GrammarConcept? = id?.let { byId[it] }

    /** Concept ids that gate the formal/political domain tier (the A1 core spine). */
    val spineIds: Set<String> = ALL.filter { it.spine }.map { it.id }.toSet()

    /**
     * The concept a card belongs to. Prefers the explicit [Card.gramConcept]; falls
     * back to deriving it from the card type / case so older cards (and domain cards
     * generated without an explicit concept) still map correctly.
     */
    fun forCard(card: Card): GrammarConcept? {
        card.gramConcept?.let { byId[it]?.let { c -> return c } }
        return when (card.cardType) {
            CardType.CASE_FILL -> byId[card.gramCase]
            CardType.GENDER_ID -> GENDER
            CardType.ADJ_AGREE -> ADJ_AGREE
            CardType.ASPECT_SELECT -> ASPECT
            CardType.VERB_FORM -> PAST
            else -> null
        }
    }
}
