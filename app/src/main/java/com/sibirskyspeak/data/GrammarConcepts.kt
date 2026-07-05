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
     * True teaching order across the whole curriculum (matches the unit sequence
     * in a1_starter.py..c2_starter.py) — informational/display ordering only, not
     * consulted by concept gating (see [LearningRepository.conceptGate],
     * which derives locking from actual card/lesson/drill-outcome state, not this field).
     * Concepts that are part of the core spine ([spine] = true) gate the formal/
     * political domain tier: the domain stays locked until every spine concept's
     * lesson has been seen.
     */
    val order: Int,
    val spine: Boolean = true,
    /** The CEFR band this concept is introduced at — matches the `// --- A1/A2/... ---`
     *  section each concept already lived in below; making it an explicit field (rather
     *  than only a comment + [order] range) lets CEFR-aware gating/display read it
     *  directly instead of re-deriving it from position. */
    val cefrLevel: String,
    /** Stable curriculum graph edges. Prerequisites must form a DAG. */
    val prerequisites: List<String> = emptyList(),
    /** Concepts that should be contrasted after both have been introduced. */
    val interferesWith: List<String> = emptyList(),
    /** A macro-strand and its progressive stage (G7). */
    val family: String = id.substringBefore('_'),
    val stage: Int = 1
)

object GrammarConcepts {
    val GEN_CHUNK_POSSESSION = GrammarConcept(
        id = "GEN_CHUNK_POSSESSION", title = "Having: у меня есть",
        lesson = "Russian expresses possession as something existing 'by' a person. Learn the whole frame before producing genitive endings.",
        exampleRu = "У меня есть книга.", exampleEn = "I have a book.", hint = "Use у + person + есть.",
        order = 55, cefrLevel = "A1", family = "GEN", stage = 0
    )
    val GEN_CHUNK_ABSENCE = GrammarConcept(
        id = "GEN_CHUNK_ABSENCE", title = "Not having: у меня нет",
        lesson = "Absence uses the fixed frame у + person + нет. Treat the following form as part of the phrase for now.",
        exampleRu = "У меня нет времени.", exampleEn = "I have no time.", hint = "Use у + person + нет.",
        order = 56, cefrLevel = "A1", prerequisites = listOf("GEN_CHUNK_POSSESSION"), family = "GEN", stage = 0
    )
    val PREP_CHUNK_LOCATION = GrammarConcept(
        id = "PREP_CHUNK_LOCATION", title = "Location chunks",
        lesson = "Learn common locations as whole phrases with в/на: в школе, на работе. The ending system comes later.",
        exampleRu = "Я в школе.", exampleEn = "I am at school.", hint = "Where? Use a learned в/на location chunk.",
        order = 65, cefrLevel = "A1", family = "PREP", stage = 0
    )
    val DAT_CHUNK_EXPERIENCER = GrammarConcept(
        id = "DAT_CHUNK_EXPERIENCER", title = "Experiencer chunks",
        lesson = "Russian often puts the person experiencing a feeling in forms such as мне/тебе. Learn these high-frequency frames whole.",
        exampleRu = "Мне нравится музыка.", exampleEn = "I like music.", hint = "Use мне/тебе + experience.",
        order = 75, cefrLevel = "A1", family = "DAT", stage = 0
    )
    val INS_CHUNK_WITH = GrammarConcept(
        id = "INS_CHUNK_WITH", title = "With someone",
        lesson = "Learn с + person as a useful whole phrase. Productive instrumental endings unlock later.",
        exampleRu = "Я иду с другом.", exampleEn = "I am going with a friend.", hint = "Together with: с + learned form.",
        order = 85, cefrLevel = "A1", family = "INS", stage = 0
    )
    val GENDER = GrammarConcept(
        id = "GENDER",
        title = "Noun gender",
        lesson = "Every Russian noun is masculine, feminine, or neuter. You usually " +
            "can tell from the ending: a consonant or -й is masculine, -а/-я is " +
            "feminine, -о/-е is neuter. Gender matters because adjectives and past-" +
            "tense verbs change their endings to match the noun.",
        exampleRu = "стол (masculine) · книга (feminine) · окно (neuter)",
        exampleEn = "table (masculine) · book (feminine) · window (neuter)",
        hint = "Ending → gender: consonant/-й = masculine, -а/-я = feminine, -о/-е = neuter.",
        order = 10,
        cefrLevel = "A1"
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
        order = 30,
        cefrLevel = "A1"
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
        order = 50,
        cefrLevel = "A1"
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
        order = 60,
        cefrLevel = "A1"
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
        order = 70,
        cefrLevel = "A1"
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
        order = 80,
        cefrLevel = "A1"
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
        order = 90,
        cefrLevel = "A1"
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
        order = 100,
        cefrLevel = "A1"
    )

    val ADJ_AGREE = GrammarConcept(
        id = "ADJ_AGREE",
        title = "Adjective agreement",
        lesson = "Adjectives copy the gender and number of the noun they describe. " +
            "The dictionary form is masculine (-ый/-ий); feminine ends -ая, neuter " +
            "-ое, and plural -ые/-ие.",
        exampleRu = "новый дом · новая книга · новое окно · новые дома",
        exampleEn = "new house · new book · new window · new houses",
        hint = "Adjective matches its noun: -ый/-ий/-ой (masculine), -ая/-яя (feminine), -ое/-ее (neuter), -ые/-ие (plural).",
        order = 20,
        spine = false,
        cefrLevel = "A1"
    )

    val PRESENT = GrammarConcept(
        id = "PRESENT",
        title = "The present tense",
        lesson = "Russian present tense uses six personal forms: я, ты, он/она/оно, мы, вы, они. The hard part is the stem: common verbs change consonants or vowels, so learn the full mini-paradigm instead of guessing from the infinitive.",
        exampleRu = "писать -> пишу, пишешь, пишет; любить -> люблю, любишь, любит",
        exampleEn = "to write -> I write, you write, he writes; to love -> I love, you love, he loves",
        hint = "Present: learn the stored stem pattern; watch mutations like писать -> пишу, любить -> люблю.",
        order = 40,
        cefrLevel = "A1"
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
        order = 110,
        spine = false,
        cefrLevel = "A1"
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
        order = 120, spine = false,
        cefrLevel = "A2"
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
        order = 130, spine = false,
        cefrLevel = "A2"
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
        order = 140, spine = false,
        cefrLevel = "A2"
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
        order = 150, spine = false,
        cefrLevel = "A2"
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
        order = 160, spine = false,
        cefrLevel = "A2"
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
        order = 170, spine = false,
        cefrLevel = "A2"
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
        order = 180, spine = false,
        cefrLevel = "A2"
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
        order = 190, spine = false,
        cefrLevel = "B1"
    )
    val CONDITIONAL = GrammarConcept(
        id = "CONDITIONAL",
        title = "Would / conditional (бы)",
        lesson = "The particle бы plus a past-tense verb expresses a hypothetical: " +
            "\"would\". For \"if\", use е́сли бы … (past), … бы … (past) in both clauses.",
        exampleRu = "Я бы помо́г. Е́сли бы я знал, я бы сказа́л.",
        exampleEn = "I would help. If I had known, I would have said.",
        hint = "бы + past = \"would\". Если бы … , … бы …",
        order = 200, spine = false,
        cefrLevel = "B1"
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
        order = 210, spine = false,
        cefrLevel = "B1"
    )
    val SUPERLATIVE = GrammarConcept(
        id = "SUPERLATIVE",
        title = "The most (superlative)",
        lesson = "The most common superlative puts са́мый (agreeing like an adjective) " +
            "before the adjective. Some forms also use -ейший/-айший.",
        exampleRu = "Э́то са́мый интере́сный го́род.",
        exampleEn = "This is the most interesting city.",
        hint = "самый + adjective = \"the most\".",
        order = 220, spine = false,
        cefrLevel = "B1"
    )
    val PURPOSE = GrammarConcept(
        id = "PURPOSE",
        title = "In order to (чтобы)",
        lesson = "что́бы expresses purpose. With the same subject, use чтобы + infinitive. " +
            "With a different subject, use чтобы + past tense (the subjunctive use).",
        exampleRu = "Я чита́ю, что́бы учи́ться. Я хочу́, что́бы ты пришёл.",
        exampleEn = "I read in order to study. I want you to come.",
        hint = "чтобы + infinitive (same subj) / + past (different subj).",
        order = 230, spine = false,
        cefrLevel = "B1"
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
        order = 240, spine = false,
        cefrLevel = "B1"
    )

    val SHORT_FORM_ADJ = GrammarConcept(
        id = "SHORT_FORM_ADJ",
        title = "Predicate short forms",
        lesson = "Many adjectives have a short form used only as the predicate (after " +
            "an implied \"is/are\", never before a noun): drop the full ending and add " +
            "nothing for masculine, -а for feminine, -о for neuter, -ы/-и for plural. " +
            "You already know до́лжен this way. Short forms describe a temporary state " +
            "or judgment; the full form (краси́вый, etc.) still names an inherent quality.",
        exampleRu = "Он рад. Она́ ра́да. Мы ра́ды.",
        exampleEn = "He is glad. She is glad. We are glad.",
        hint = "Predicate-only form: masculine (bare stem), feminine -а, neuter -о, plural -ы/-и.",
        order = 245, spine = false,
        cefrLevel = "B1"
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
        order = 250, spine = false,
        cefrLevel = "B2"
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
        order = 260, spine = false,
        cefrLevel = "B2"
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
        order = 270, spine = false,
        cefrLevel = "B2"
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
        order = 280, spine = false,
        cefrLevel = "B2"
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
        order = 290, spine = false,
        cefrLevel = "B2"
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
        order = 300, spine = false,
        cefrLevel = "C1"
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
        order = 310, spine = false,
        cefrLevel = "C1"
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
        order = 320, spine = false,
        cefrLevel = "C1"
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
        order = 330, spine = false,
        cefrLevel = "C1"
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
        order = 340, spine = false,
        cefrLevel = "C1"
    )

    // --- C2 -----------------------------------------------------------------
    val DISCOURSE_PARTICLES = GrammarConcept(
        id = "DISCOURSE_PARTICLES",
        title = "Hedging and reported stance",
        lesson = "Near-native Russian marks the speaker's distance from a quoted claim " +
            "with reporting particles: мол and де́скать flag casual reported speech " +
            "(\"so they say\"), я́кобы signals the claim may be false (\"allegedly\"), " +
            "and вро́де бы softens a statement (\"sort of\"). None of these change the " +
            "sentence's grammar — they only signal the speaker's stance toward it.",
        exampleRu = "Он сказа́л, мол, всё гото́во, но э́то я́кобы непра́вда.",
        exampleEn = "He said - so he claims - everything's ready, but that's allegedly not true.",
        hint = "мол/дескать = casual reported speech; якобы = doubted claim; вроде бы = soft hedge.",
        order = 350, spine = false,
        cefrLevel = "C2"
    )
    val EMPHATIC_PARTICLES = GrammarConcept(
        id = "EMPHATIC_PARTICLES",
        title = "Emphasis and contrast particles",
        lesson = "A small set of particles carry pure emphasis with no dictionary " +
            "translation of their own: ведь appeals to something the listener should " +
            "already accept (\"after all\"), всё-та́ки signals a stubborn contrast " +
            "(\"still, despite that\"), уж intensifies (\"quite, indeed\"), and лишь " +
            "restricts (\"only, merely\", more literary than то́лько).",
        exampleRu = "Он ведь зна́л об э́том, но всё-та́ки не сказа́л ни сло́ва.",
        exampleEn = "He knew about it, after all, but still didn't say a word.",
        hint = "ведь = appeals to shared knowledge; всё-таки = despite that; уж = indeed; лишь = only.",
        order = 360, spine = false,
        cefrLevel = "C2"
    )
    val BOOKISH_SUBORDINATION = GrammarConcept(
        id = "BOOKISH_SUBORDINATION",
        title = "Literary connectors",
        lesson = "Formal and literary Russian has its own tier of subordinators above " +
            "the C1 set: и́бо (\"for\", explains a cause), да́бы (archaic \"in order " +
            "that\"), поско́льку (\"insofar as, since\"), and the phrase всле́дствие " +
            "того́ что (\"owing to the fact that\") for citing a cause formally.",
        exampleRu = "Он не отве́тил, поско́льку не был уве́рен, и́бо вопро́с был сло́жным.",
        exampleEn = "He didn't answer, since he wasn't sure, for the question was complex.",
        hint = "ибо = for; дабы = in order that (archaic); поскольку = insofar as; вследствие того что = owing to.",
        order = 370, spine = false,
        cefrLevel = "C2"
    )
    val INVERSION_EMPHASIS = GrammarConcept(
        id = "INVERSION_EMPHASIS",
        title = "Emphatic word order",
        lesson = "Russian word order is freer than English, and near-native speakers " +
            "use that freedom deliberately: fronting a word with и́менно marks it as " +
            "the precise focus (\"this one, specifically\"), недаро́м implies the " +
            "following clause is no coincidence, тем не ме́нее pivots to a contrast, " +
            "and в свою́ о́чередь hands the topic to the next actor in turn.",
        exampleRu = "И́менно он реши́л вопро́с; она́, в свою́ о́чередь, начала́ но́вый прое́кт.",
        exampleEn = "He specifically was the one who solved it; she, in turn, started a new project.",
        hint = "именно = precisely this one; недаром = no accident; тем не менее = nonetheless; в свою очередь = in turn.",
        order = 380, spine = false,
        cefrLevel = "C2"
    )
    val SUBJUNCTIVE_NUANCE = GrammarConcept(
        id = "SUBJUNCTIVE_NUANCE",
        title = "Deeper hypotheticals",
        lesson = "Beyond the B1 бы conditional, C2 control includes concessive бы " +
            "chains: что бы ни / как бы ни + past tense (\"no matter what/how\"), " +
            "е́сли бы не (\"if not for\") for a counterfactual condition, and хотя́ бы " +
            "(\"at least\") for a minimal concession.",
        exampleRu = "Что бы ни случи́лось, е́сли бы не твоя́ по́мощь, я бы не спра́вился.",
        exampleEn = "Whatever happened, if not for your help, I wouldn't have managed.",
        hint = "что/как бы ни = no matter what/how; если бы не = if not for; хотя бы = at least.",
        order = 390, spine = false,
        cefrLevel = "C2"
    )

    private data class StagedSpec(val id: String, val title: String, val family: String, val stage: Int, val order: Int, val band: String)
    private val stagedSpecs = listOf(
        StagedSpec("QUESTIONS", "Question formation", "QUESTIONS", 1, 25, "A1"),
        StagedSpec("CONNECTORS_A_NO", "And, but, and contrast: и/а/но", "CONNECTORS", 1, 35, "A1"),
        StagedSpec("CARDINALS", "Cardinal numbers 0–100", "NUMERALS", 1, 45, "A1"),
        StagedSpec("TIME_TELLING", "Telling time", "TIME", 1, 115, "A2"),
        StagedSpec("ORDINALS_DATES", "Ordinals and dates", "NUMERALS", 2, 125, "A2"),
        StagedSpec("NUMERAL_GOV_234", "Two, three, four + genitive singular", "NUMERALS", 3, 185, "B1"),
        StagedSpec("NUMERAL_GOV_5", "Five and above + genitive plural", "NUMERALS", 4, 195, "B1"),
        StagedSpec("ASPECT_PAST", "Aspect in the past", "ASPECT", 2, 105, "A2"),
        StagedSpec("ASPECT_FUTURE", "Aspect in the future", "ASPECT", 3, 135, "A2"),
        StagedSpec("ASPECT_INFINITIVE", "Aspect after modals and phase verbs", "ASPECT", 4, 175, "B1"),
        StagedSpec("ASPECT_IMPERATIVE", "Aspect in commands", "ASPECT", 5, 205, "B1"),
        StagedSpec("ASPECT_NEGATION", "Aspect under negation", "ASPECT", 6, 235, "B2"),
        StagedSpec("MOTION_UNIDIRECTIONAL", "идти/ехать: motion in one direction", "MOTION", 1, 145, "A2"),
        StagedSpec("MOTION_MULTIDIRECTIONAL", "ходить/ездить: habitual and round-trip motion", "MOTION", 2, 165, "B1"),
        StagedSpec("MOTION_CARRY", "Carrying and leading motion verbs", "MOTION", 3, 215, "B1"),
        StagedSpec("MOTION_TRANSPORT", "Choosing motion by transport and context", "MOTION", 4, 225, "B1"),
        StagedSpec("MOTION_PREFIX_ARRIVE_LEAVE", "Motion prefixes при-/у-", "MOTION_PREFIX", 1, 245, "B2"),
        StagedSpec("MOTION_PREFIX_ENTER_EXIT", "Motion prefixes в-/вы-", "MOTION_PREFIX", 2, 255, "B2"),
        StagedSpec("MOTION_PREFIX_APPROACH", "Motion prefixes под-/от-/до-", "MOTION_PREFIX", 3, 265, "B2"),
        StagedSpec("MOTION_PREFIX_CROSS_PASS", "Motion prefixes за-/пере-/про-", "MOTION_PREFIX", 4, 275, "B2"),
        StagedSpec("GEN_PL", "Genitive plural", "CASE_PLURAL", 1, 155, "B1"),
        StagedSpec("DAT_PL", "Dative plural", "CASE_PLURAL", 2, 175, "B1"),
        StagedSpec("INS_PL", "Instrumental plural", "CASE_PLURAL", 3, 195, "B1"),
        StagedSpec("PREP_PL", "Prepositional plural", "CASE_PLURAL", 4, 215, "B1"),
        StagedSpec("GOV_INTEREST_INS", "интересоваться/заниматься + instrumental", "GOVERNMENT", 1, 185, "B1"),
        StagedSpec("GOV_FEAR_GEN", "бояться + genitive", "GOVERNMENT", 2, 205, "B1"),
        StagedSpec("GOV_HELP_DAT", "помогать + dative", "GOVERNMENT", 3, 225, "B1"),
        StagedSpec("GOV_WAIT_GEN_ACC", "ждать + genitive or accusative", "GOVERNMENT", 4, 245, "B2"),
        StagedSpec("GOV_DEPEND_ON", "зависеть от + genitive", "GOVERNMENT", 5, 265, "B2"),
        StagedSpec("GOV_MANAGE_INS", "управлять + instrumental", "GOVERNMENT", 6, 285, "B2"),
        StagedSpec("GOV_USE_INS", "пользоваться + instrumental", "GOVERNMENT", 7, 305, "B2"),
        StagedSpec("GOV_CONGRAT_DAT", "благодарить + accusative; благодарен + dative", "GOVERNMENT", 8, 325, "C1"),
        StagedSpec("POSITION_STATE", "Standing, lying, and hanging", "POSITION", 1, 235, "B1"),
        StagedSpec("POSITION_CAUSED", "Putting, laying, and hanging", "POSITION", 2, 255, "B2"),
        StagedSpec("TIME_WEEK_ACC", "Days and recurrence with в + accusative", "TIME", 2, 145, "A2"),
        StagedSpec("TIME_MONTH_PREP", "Months and years with в + prepositional", "TIME", 3, 165, "B1"),
        StagedSpec("TIME_DURATION", "Duration: назад and в течение", "TIME", 4, 205, "B1"),
        StagedSpec("TIME_DEADLINE", "Deadline and completion: к, за, на", "TIME", 5, 245, "B2"),
        StagedSpec("PREFIX_SEMANTICS", "Productive verb-prefix meanings", "WORD_FORMATION", 1, 285, "B2"),
        StagedSpec("SUFFIX_OST", "Abstract nouns in -ость", "WORD_FORMATION", 2, 305, "B2"),
        StagedSpec("SUFFIX_ENIE", "Event nouns in -ение", "WORD_FORMATION", 3, 325, "C1"),
        StagedSpec("AGENT_SUFFIXES", "People and roles: -тель/-ник", "WORD_FORMATION", 4, 345, "C1"),
        StagedSpec("DIMINUTIVES", "Diminutives and stance", "WORD_FORMATION", 5, 365, "C1"),
        StagedSpec("ASPECT_PREFIXATION", "Building aspect through prefixation", "WORD_FORMATION", 6, 385, "C1"),
        StagedSpec("INDEFINITE_TO", "Specific unknowns with -то", "INDEFINITES", 1, 275, "B2"),
        StagedSpec("INDEFINITE_NIBUD", "Open-choice unknowns with -нибудь", "INDEFINITES", 2, 295, "B2"),
        StagedSpec("NEGATIVE_NI", "Negative pronouns with ни-", "NEGATION", 1, 315, "B2"),
        StagedSpec("NEGATION_GENITIVE", "Genitive under negation", "NEGATION", 2, 335, "C1"),
        StagedSpec("THEME_RHEME", "Theme and new information", "INFORMATION_STRUCTURE", 1, 315, "B2"),
        StagedSpec("FOCUS_ORDER", "Word order for contrastive focus", "INFORMATION_STRUCTURE", 2, 345, "C1"),
        StagedSpec("PARTICIPLE_FORM", "Forming participles", "PARTICIPLE", 1, 295, "B2"),
        StagedSpec("PARTICIPLE_RECOGNIZE", "Reading participial phrases", "PARTICIPLE", 2, 325, "C1"),
        StagedSpec("PARTICIPLE_RELATIVE_TRANSFORM", "Participle and который transformations", "PARTICIPLE", 3, 355, "C1"),
        StagedSpec("PARTICIPLE_PRODUCE", "Producing formal participial clauses", "PARTICIPLE", 4, 405, "C2"),
        StagedSpec("GERUND_SIMULTANEOUS", "Simultaneous verbal adverbs", "GERUND", 1, 325, "C1"),
        StagedSpec("GERUND_ANTERIOR", "Prior-action verbal adverbs", "GERUND", 2, 365, "C1"),
        StagedSpec("CONCESSIVE_HOTYA", "Concession with хотя", "CONCESSIVES", 1, 305, "B2"),
        StagedSpec("CONCESSIVE_NESMOTRYA", "Concession with несмотря на", "CONCESSIVES", 2, 345, "C1"),
        StagedSpec("CONNECTIVES_FORMAL", "Formal connective contrasts", "CONCESSIVES", 3, 385, "C1"),
        StagedSpec("PARTICLE_ZHE", "Discourse particle же", "PARTICLES", 1, 335, "C1"),
        StagedSpec("PARTICLE_VED", "Discourse particle ведь", "PARTICLES", 2, 365, "C1"),
        StagedSpec("PARTICLE_TO", "Contrastive particle -то", "PARTICLES", 3, 395, "C2"),
        StagedSpec("PARTICLE_UZH", "Stance particle уж", "PARTICLES", 4, 425, "C2"),
        StagedSpec("FORMAL_NEWS", "News-report register", "FORMAL_GENRES", 1, 355, "C1"),
        StagedSpec("FORMAL_OFFICIAL", "Official-statement register", "FORMAL_GENRES", 2, 395, "C2"),
        StagedSpec("FORMAL_ACADEMIC", "Academic hedging and argumentation", "FORMAL_GENRES", 3, 435, "C2")
    )

    private val SPINE_2: List<GrammarConcept> = stagedSpecs.map { spec ->
        val familyRoots = mapOf(
            "ASPECT" to "ASPECT", "MOTION" to "MOTION", "MOTION_PREFIX" to "MOTION_PREFIX",
            "TIME" to "TIME_TELLING", "PARTICIPLE" to "PARTICIPLE_ACTIVE", "GERUND" to "GERUND"
        )
        val previous = stagedSpecs.firstOrNull { it.family == spec.family && it.stage == spec.stage - 1 }?.id
            ?: if (spec.stage > 1) familyRoots[spec.family] else null
        val interferenceFamilies = setOf("ASPECT", "MOTION", "MOTION_PREFIX", "CASE_PLURAL", "GOVERNMENT", "NEGATION")
        val interference = if (spec.family in interferenceFamilies) stagedSpecs
            .filter { it.family == spec.family && it.id != spec.id }.map { it.id }.take(4) else emptyList()
        GrammarConcept(
            id = spec.id, title = spec.title,
            lesson = "This stage develops ${spec.title.lowercase()} as a reusable contrast in connected Russian.",
            exampleRu = spec.title, exampleEn = spec.title,
            hint = spec.title,
            order = spec.order, spine = false, cefrLevel = spec.band,
            prerequisites = listOfNotNull(previous), interferesWith = interference,
            family = spec.family, stage = spec.stage
        )
    }

    val ALL: List<GrammarConcept> =
        listOf(
            // A1 core spine
            GENDER, NOM_PL, ACC, GEN_CHUNK_POSSESSION, GEN_CHUNK_ABSENCE,
            PREP_CHUNK_LOCATION, DAT_CHUNK_EXPERIENCER, INS_CHUNK_WITH,
            GEN, PREP, DAT, INS, PAST, PRESENT, ADJ_AGREE, ASPECT,
            // A2
            FUTURE, IMPERATIVE, REFLEXIVE, COMPARATIVE, MODAL, MOTION, POSSESSIVE_SVOJ,
            // B1
            MOTION_PREFIX, CONDITIONAL, RELATIVE, SUPERLATIVE, PURPOSE, NUMERAL_CASE,
            // B2
            SHORT_FORM_ADJ, PARTICIPLE_ACTIVE, PARTICIPLE_PASSIVE, GERUND, PASSIVE, REPORTED,
            // C1
            COMPLEX_SYNTAX, NOMINALIZATION, ASPECT_NUANCE, REGISTER, IDIOM,
            // C2
            DISCOURSE_PARTICLES, EMPHATIC_PARTICLES, BOOKISH_SUBORDINATION, INVERSION_EMPHASIS, SUBJUNCTIVE_NUANCE
        ) + SPINE_2

    private val byId: Map<String, GrammarConcept> = ALL.associateBy { it.id }

    init {
        require(byId.size == ALL.size) { "Duplicate grammar concept id" }
        val ids = byId.keys
        ALL.forEach { concept ->
            require(concept.prerequisites.all(ids::contains)) { "Unknown prerequisite on ${concept.id}" }
            require(concept.interferesWith.all(ids::contains)) { "Unknown interference edge on ${concept.id}" }
        }
        fun visit(id: String, visiting: MutableSet<String>, visited: MutableSet<String>) {
            if (id in visited) return
            require(visiting.add(id)) { "Grammar prerequisite cycle at $id" }
            byId.getValue(id).prerequisites.forEach { visit(it, visiting, visited) }
            visiting.remove(id); visited.add(id)
        }
        ALL.forEach { visit(it.id, mutableSetOf(), mutableSetOf()) }
    }

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
            CardType.CONCEPT_DRILL -> byId[card.gramConcept]
            CardType.VERB_FORM -> when {
                card.gramContextCue?.startsWith("PRES_") == true -> PRESENT
                else -> PAST
            }
            else -> null
        }
    }
}
