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
    )

    private val byId = drills.associateBy { it.id }
    private val byConcept = drills.groupBy { it.conceptId }

    fun byId(id: String?): ConceptDrill? = id?.let(byId::get)

    fun forConcept(conceptId: String?): List<ConceptDrill> =
        conceptId?.let { byConcept[it] }.orEmpty()

    fun coveredConceptIds(): Set<String> = byConcept.keys
}
