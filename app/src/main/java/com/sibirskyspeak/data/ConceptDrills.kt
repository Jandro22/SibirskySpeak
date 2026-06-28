package com.sibirskyspeak.data

data class ConceptDrill(
    val id: String,
    val conceptId: String,
    val prompt: String,
    val expectedAnswer: String,
    val choices: List<String> = emptyList(),
    val explanation: String
) {
    val isChoice: Boolean get() = choices.isNotEmpty()
}

object ConceptDrills {
    private val drills = listOf(
        ConceptDrill(
            id = "MOTION_PREFIX_ARRIVE",
            conceptId = GrammarConcepts.MOTION_PREFIX.id,
            prompt = "Choose the prefix for arriving.\n___\u0439\u0442\u0438 \u0434\u043e\u043c\u043e\u0439 = to come home",
            expectedAnswer = "\u043f\u0440\u0438",
            choices = listOf("\u043f\u0440\u0438", "\u0443", "\u0432\u044b", "\u043f\u043e"),
            explanation = "\u043f\u0440\u0438- marks arrival: \u043f\u0440\u0438\u0439\u0442\u0438 \u0434\u043e\u043c\u043e\u0439."
        ),
        ConceptDrill(
            id = "CONDITIONAL_BY_PAST",
            conceptId = GrammarConcepts.CONDITIONAL.id,
            prompt = "Make this conditional: I would help.\n\u042f ___ \u043f\u043e\u043c\u043e\u0433.",
            expectedAnswer = "\u0431\u044b",
            explanation = "\u0431\u044b plus a past-tense verb makes the conditional: \u042f \u0431\u044b \u043f\u043e\u043c\u043e\u0433."
        ),
        ConceptDrill(
            id = "RELATIVE_KOTORYJ_ACC_F",
            conceptId = GrammarConcepts.RELATIVE.id,
            prompt = "Choose the form of \u043a\u043e\u0442\u043e\u0440\u044b\u0439.\n\u041a\u043d\u0438\u0433\u0430, ___ \u044f \u0447\u0438\u0442\u0430\u044e, \u0438\u043d\u0442\u0435\u0440\u0435\u0441\u043d\u0430\u044f.",
            expectedAnswer = "\u043a\u043e\u0442\u043e\u0440\u0443\u044e",
            choices = listOf("\u043a\u043e\u0442\u043e\u0440\u0443\u044e", "\u043a\u043e\u0442\u043e\u0440\u0430\u044f", "\u043a\u043e\u0442\u043e\u0440\u044b\u0439", "\u043a\u043e\u0442\u043e\u0440\u043e\u0433\u043e"),
            explanation = "The antecedent is feminine singular, and inside the relative clause it is the direct object, so accusative feminine \u043a\u043e\u0442\u043e\u0440\u0443\u044e fits."
        ),
        ConceptDrill(
            id = "SUPERLATIVE_SAMYJ",
            conceptId = GrammarConcepts.SUPERLATIVE.id,
            prompt = "Build the superlative: the most interesting city.\n___ \u0438\u043d\u0442\u0435\u0440\u0435\u0441\u043d\u044b\u0439 \u0433\u043e\u0440\u043e\u0434",
            expectedAnswer = "\u0441\u0430\u043c\u044b\u0439",
            explanation = "\u0441\u0430\u043c\u044b\u0439 agrees with the noun and makes the common analytic superlative."
        ),
        ConceptDrill(
            id = "PURPOSE_SAME_SUBJECT",
            conceptId = GrammarConcepts.PURPOSE.id,
            prompt = "Same subject: choose what follows \u0447\u0442\u043e\u0431\u044b.\n\u042f \u0447\u0438\u0442\u0430\u044e, \u0447\u0442\u043e\u0431\u044b ___.",
            expectedAnswer = "\u0443\u0447\u0438\u0442\u044c\u0441\u044f",
            choices = listOf("\u0443\u0447\u0438\u0442\u044c\u0441\u044f", "\u0443\u0447\u0438\u043b\u0441\u044f", "\u0443\u0447\u0438\u043b\u0430\u0441\u044c"),
            explanation = "With the same subject, purpose uses \u0447\u0442\u043e\u0431\u044b plus an infinitive."
        ),
        ConceptDrill(
            id = "NUMERAL_CASE_TWO_BOOKS",
            conceptId = GrammarConcepts.NUMERAL_CASE.id,
            prompt = "Numbers 2-4 take genitive singular.\n\u0434\u0432\u0435 + \u043a\u043d\u0438\u0433\u0430 -> ?",
            expectedAnswer = "\u0434\u0432\u0435 \u043a\u043d\u0438\u0433\u0438",
            explanation = "After 2, 3, and 4, Russian uses the genitive singular: \u0434\u0432\u0435 \u043a\u043d\u0438\u0433\u0438."
        ),
        ConceptDrill(
            id = "PARTICIPLE_ACTIVE_AUTHORED",
            conceptId = GrammarConcepts.PARTICIPLE_ACTIVE.id,
            prompt = "Choose the active participle: the person reading a book.\n\u0447\u0435\u043b\u043e\u0432\u0435\u043a, ___ \u043a\u043d\u0438\u0433\u0443",
            expectedAnswer = "\u0447\u0438\u0442\u0430\u044e\u0449\u0438\u0439",
            choices = listOf("\u0447\u0438\u0442\u0430\u044e\u0449\u0438\u0439", "\u0447\u0438\u0442\u0430\u0435\u043c\u044b\u0439", "\u043f\u0440\u043e\u0447\u0438\u0442\u0430\u043d\u043d\u044b\u0439"),
            explanation = "This is authored form data: active participle \u0447\u0438\u0442\u0430\u044e\u0449\u0438\u0439 means the person is doing the reading."
        ),
        ConceptDrill(
            id = "PARTICIPLE_PASSIVE_AUTHORED",
            conceptId = GrammarConcepts.PARTICIPLE_PASSIVE.id,
            prompt = "Choose the passive participle: the letter written by him.\n\u043f\u0438\u0441\u044c\u043c\u043e, ___ \u0438\u043c",
            expectedAnswer = "\u043d\u0430\u043f\u0438\u0441\u0430\u043d\u043d\u043e\u0435",
            choices = listOf("\u043d\u0430\u043f\u0438\u0441\u0430\u043d\u043d\u043e\u0435", "\u043f\u0438\u0448\u0443\u0449\u0435\u0435", "\u043d\u0430\u043f\u0438\u0441\u0430\u0432\u0448\u0435\u0435"),
            explanation = "This authored passive participle means the letter was written; it agrees with neuter \u043f\u0438\u0441\u044c\u043c\u043e."
        ),
        ConceptDrill(
            id = "GERUND_AUTHORED",
            conceptId = GrammarConcepts.GERUND.id,
            prompt = "Choose the verbal adverb: Reading the letter, he was silent.\n___ \u043f\u0438\u0441\u044c\u043c\u043e, \u043e\u043d \u043c\u043e\u043b\u0447\u0430\u043b.",
            expectedAnswer = "\u0427\u0438\u0442\u0430\u044f",
            choices = listOf("\u0427\u0438\u0442\u0430\u044f", "\u0427\u0438\u0442\u0430\u044e\u0449\u0438\u0439", "\u041f\u0440\u043e\u0447\u0438\u0442\u0430\u043d\u043d\u044b\u0439"),
            explanation = "\u0427\u0438\u0442\u0430\u044f is an invariable verbal adverb for a secondary action by the same subject."
        ),
        ConceptDrill(
            id = "PASSIVE_PROCESS_RESULT",
            conceptId = GrammarConcepts.PASSIVE.id,
            prompt = "Choose the passive result form.\n\u0414\u043e\u0433\u043e\u0432\u043e\u0440 ___.",
            expectedAnswer = "\u043f\u043e\u0434\u043f\u0438\u0441\u0430\u043d",
            choices = listOf("\u043f\u043e\u0434\u043f\u0438\u0441\u0430\u043d", "\u043f\u043e\u0434\u043f\u0438\u0441\u044b\u0432\u0430\u0435\u0442\u0441\u044f", "\u043f\u043e\u0434\u043f\u0438\u0441\u0430\u043b"),
            explanation = "A completed passive result uses the short passive participle: \u0414\u043e\u0433\u043e\u0432\u043e\u0440 \u043f\u043e\u0434\u043f\u0438\u0441\u0430\u043d."
        ),
        ConceptDrill(
            id = "REPORTED_LI",
            conceptId = GrammarConcepts.REPORTED.id,
            prompt = "Reported yes/no question: insert the marker.\n\u042f \u0441\u043f\u0440\u043e\u0441\u0438\u043b, \u043f\u0440\u0438\u0434\u0435\u0442 ___ \u043e\u043d.",
            expectedAnswer = "\u043b\u0438",
            explanation = "Reported yes/no questions use \u043b\u0438: \u043f\u0440\u0438\u0434\u0435\u0442 \u043b\u0438 \u043e\u043d."
        ),
        ConceptDrill(
            id = "COMPLEX_SYNTAX_CONTRAST",
            conceptId = GrammarConcepts.COMPLEX_SYNTAX.id,
            prompt = "Choose the connector for contrast.\n\u0411\u044b\u043b\u043e \u0442\u0440\u0443\u0434\u043d\u043e, ___ \u043e\u043d \u0443\u0441\u043f\u0435\u043b.",
            expectedAnswer = "\u043e\u0434\u043d\u0430\u043a\u043e",
            choices = listOf("\u043e\u0434\u043d\u0430\u043a\u043e", "\u043f\u043e\u044d\u0442\u043e\u043c\u0443", "\u0431\u043b\u0430\u0433\u043e\u0434\u0430\u0440\u044f \u0442\u043e\u043c\u0443 \u0447\u0442\u043e"),
            explanation = "\u043e\u0434\u043d\u0430\u043a\u043e marks contrast: it was difficult, however he succeeded."
        ),
        ConceptDrill(
            id = "NOMINALIZATION_DECISION",
            conceptId = GrammarConcepts.NOMINALIZATION.id,
            prompt = "Formal nominal style: choose the phrase for 'making a decision'.\n___ \u0440\u0435\u0448\u0435\u043d\u0438\u044f \u0437\u0430\u043d\u044f\u043b\u043e \u0432\u0440\u0435\u043c\u044f.",
            expectedAnswer = "\u043f\u0440\u0438\u043d\u044f\u0442\u0438\u0435",
            explanation = "Formal Russian often uses verbal nouns: \u043f\u0440\u0438\u043d\u044f\u0442\u0438\u0435 \u0440\u0435\u0448\u0435\u043d\u0438\u044f."
        ),
        ConceptDrill(
            id = "ASPECT_NUANCE_NEG_COMMAND",
            conceptId = GrammarConcepts.ASPECT_NUANCE.id,
            prompt = "Negated commands usually use imperfective.\nChoose: Do not read this!",
            expectedAnswer = "\u041d\u0435 \u0447\u0438\u0442\u0430\u0439 \u044d\u0442\u043e!",
            choices = listOf("\u041d\u0435 \u0447\u0438\u0442\u0430\u0439 \u044d\u0442\u043e!", "\u041d\u0435 \u043f\u0440\u043e\u0447\u0438\u0442\u0430\u0439 \u044d\u0442\u043e!"),
            explanation = "For a general prohibition, Russian normally chooses the imperfective command."
        ),
        ConceptDrill(
            id = "REGISTER_OFFICIAL",
            conceptId = GrammarConcepts.REGISTER.id,
            prompt = "Choose the more official wording for 'because of this decision'.",
            expectedAnswer = "\u0432 \u0441\u0432\u044f\u0437\u0438 \u0441 \u0434\u0430\u043d\u043d\u044b\u043c \u0440\u0435\u0448\u0435\u043d\u0438\u0435\u043c",
            choices = listOf("\u0432 \u0441\u0432\u044f\u0437\u0438 \u0441 \u0434\u0430\u043d\u043d\u044b\u043c \u0440\u0435\u0448\u0435\u043d\u0438\u0435\u043c", "\u0438\u0437-\u0437\u0430 \u044d\u0442\u043e\u0433\u043e \u0440\u0435\u0448\u0435\u043d\u0438\u044f", "\u043f\u043e\u0442\u043e\u043c\u0443 \u0447\u0442\u043e \u0440\u0435\u0448\u0438\u043b\u0438"),
            explanation = "\u0432 \u0441\u0432\u044f\u0437\u0438 \u0441 and \u0434\u0430\u043d\u043d\u044b\u0439 are markers of official register."
        ),
        ConceptDrill(
            id = "IDIOM_IMET_V_VIDU",
            conceptId = GrammarConcepts.IDIOM.id,
            prompt = "Choose the set phrase meaning 'to mean / have in mind'.",
            expectedAnswer = "\u0438\u043c\u0435\u0442\u044c \u0432 \u0432\u0438\u0434\u0443",
            choices = listOf("\u0438\u043c\u0435\u0442\u044c \u0432 \u0432\u0438\u0434\u0443", "\u0438\u043c\u0435\u0442\u044c \u0437\u043d\u0430\u0447\u0435\u043d\u0438\u0435", "\u043f\u0440\u0438\u043d\u044f\u0442\u044c \u0432\u043e \u0432\u043d\u0438\u043c\u0430\u043d\u0438\u0435"),
            explanation = "\u0438\u043c\u0435\u0442\u044c \u0432 \u0432\u0438\u0434\u0443 is learned as a whole set phrase: 'to mean / have in mind'."
        )
    ) + retentionDrills()

    /** Varied retrieval over different lexemes; blank choices mean typed production. */
    private fun retentionDrills(): List<ConceptDrill> {
        fun d(id: String, concept: String, prompt: String, answer: String, why: String, choices: List<String> = emptyList()) =
            ConceptDrill(id, concept, prompt, answer, choices, why)
        return listOf(
            d("COND_ADVICE", GrammarConcepts.CONDITIONAL.id, "Type the missing particle: На твоём месте я ___ подождал.", "бы", "бы + past expresses hypothetical advice."),
            d("COND_WISH", GrammarConcepts.CONDITIONAL.id, "Complete: Мы хотели ___ поехать раньше.", "бы", "The conditional particle follows хотели."),
            d("COND_IF", GrammarConcepts.CONDITIONAL.id, "Complete: Если ___ было время, я прочитал бы книгу.", "бы", "бы marks the unreal condition."),
            d("COND_POLITE", GrammarConcepts.CONDITIONAL.id, "Complete the polite request: Не могли ___ вы помочь?", "бы", "бы softens the request."),
            d("REL_GEN", GrammarConcepts.RELATIVE.id, "Дом, у ___ нет сада, старый.", "которого", "у governs genitive; дом is masculine."),
            d("REL_PREP", GrammarConcepts.RELATIVE.id, "Город, в ___ мы живём, большой.", "котором", "в for location governs prepositional."),
            d("REL_DAT", GrammarConcepts.RELATIVE.id, "Друг, ___ я позвонил, приехал.", "которому", "позвонить governs dative."),
            d("REL_PL", GrammarConcepts.RELATIVE.id, "Люди, с ___ мы работаем, здесь.", "которыми", "с governs instrumental plural."),
            d("NUM_FIVE", GrammarConcepts.NUMERAL_CASE.id, "Type the phrase: five books.", "пять книг", "Five and above take genitive plural."),
            d("NUM_21", GrammarConcepts.NUMERAL_CASE.id, "Type: twenty-one students.", "двадцать один студент", "Compound numerals follow their final number."),
            d("NUM_22", GrammarConcepts.NUMERAL_CASE.id, "Type: twenty-two days.", "двадцать два дня", "Final 2 takes genitive singular."),
            d("NUM_INSTR", GrammarConcepts.NUMERAL_CASE.id, "Complete: с двумя ___ (друг).", "друзьями", "After с, both numeral and noun are instrumental."),
            d("PACT_WORK", GrammarConcepts.PARTICIPLE_ACTIVE.id, "Сотрудник, ___ дома, ответил быстро. (работать)", "работающий", "Present active participle names the doer."),
            d("PACT_KNOW", GrammarConcepts.PARTICIPLE_ACTIVE.id, "Девушка, хорошо ___ город, помогла нам. (знать)", "знающая", "The participle agrees with feminine девушка."),
            d("PACT_PAST", GrammarConcepts.PARTICIPLE_ACTIVE.id, "Студент, ___ экзамен, ушёл. (сдать)", "сдавший", "Past active participle names who completed it."),
            d("PACT_PL", GrammarConcepts.PARTICIPLE_ACTIVE.id, "Люди, ___ у окна, разговаривали. (стоять)", "стоящие", "Plural active participle agrees with люди."),
            d("PPASS_OPEN", GrammarConcepts.PARTICIPLE_PASSIVE.id, "___ окно пропускало холод. (открыть)", "открытое", "Passive participle agrees with окно."),
            d("PPASS_READ", GrammarConcepts.PARTICIPLE_PASSIVE.id, "Я вернул ___ книгу. (прочитать)", "прочитанную", "Accusative feminine agrees with книгу."),
            d("PPASS_BUILD", GrammarConcepts.PARTICIPLE_PASSIVE.id, "Дом, ___ в прошлом году, уже продан. (построить)", "построенный", "The house receives the completed action."),
            d("PPASS_PL", GrammarConcepts.PARTICIPLE_PASSIVE.id, "___ документы лежат на столе. (подписать)", "подписанные", "Plural passive participle agrees with документы."),
            d("GERUND_GO", GrammarConcepts.GERUND.id, "___ домой, она слушала музыку. (идти)", "Идя", "The same subject performs both simultaneous actions."),
            d("GERUND_DONE", GrammarConcepts.GERUND.id, "___ работу, он ушёл. (закончить)", "Закончив", "Perfective gerund marks the prior completed action."),
            d("GERUND_SMILE", GrammarConcepts.GERUND.id, "Она ответила, ___. (улыбаться)", "улыбаясь", "Imperfective gerund marks a simultaneous action."),
            d("GERUND_READ", GrammarConcepts.GERUND.id, "___ статью, выпишите новые слова. (читать)", "Читая", "The imperative's understood subject does both actions."),
            d("PASS_DONE", GrammarConcepts.PASSIVE.id, "Работа уже ___. (сделать)", "сделана", "Short passive participle states a result."),
            d("PASS_PROCESS", GrammarConcepts.PASSIVE.id, "Мост сейчас ___. (строить)", "строится", "Reflexive passive emphasizes an ongoing process."),
            d("PASS_PL", GrammarConcepts.PASSIVE.id, "Письма были ___. (отправить)", "отправлены", "Short passive participle agrees in plural."),
            d("PASS_AGENT", GrammarConcepts.PASSIVE.id, "Роман написан ___. (Толстой)", "Толстым", "The passive agent is instrumental."),
            d("REPORT_SAID", GrammarConcepts.REPORTED.id, "Он сказал, ___ придёт завтра.", "что", "Reported statements use что."),
            d("REPORT_ASK", GrammarConcepts.REPORTED.id, "Она спросила, где я ___. (жить)", "живу", "Reported wh-questions keep the question word."),
            d("REPORT_COMMAND", GrammarConcepts.REPORTED.id, "Врач сказал, ___ я больше отдыхал.", "чтобы", "Reported requests and commands use чтобы."),
            d("REPORT_WHETHER", GrammarConcepts.REPORTED.id, "Мы не знали, будет ___ дождь.", "ли", "ли marks an embedded yes/no question."),
            d("ASP_NEG", GrammarConcepts.ASPECT_NUANCE.id, "General prohibition: Не ___ здесь! (курить)", "кури", "General negative commands normally use imperfective."),
            d("ASP_REPEAT", GrammarConcepts.ASPECT_NUANCE.id, "Каждый день она ___ газету. (читать)", "читала", "Habitual repetition calls for imperfective."),
            d("ASP_RESULT", GrammarConcepts.ASPECT_NUANCE.id, "Наконец я ___ письмо. (написать)", "написал", "A completed result calls for perfective."),
            d("ASP_ATTEMPT", GrammarConcepts.ASPECT_NUANCE.id, "Ты когда-нибудь ___ эту книгу? (читать)", "читал", "Experiential 'ever' asks about the activity, usually imperfective.")
        )
    }

    private val byId = drills.associateBy { it.id }
    private val byConcept = drills.groupBy { it.conceptId }

    fun byId(id: String?): ConceptDrill? = id?.let(byId::get)

    fun forConcept(conceptId: String?): List<ConceptDrill> =
        conceptId?.let { byConcept[it] }.orEmpty()

    fun coveredConceptIds(): Set<String> = byConcept.keys
}
