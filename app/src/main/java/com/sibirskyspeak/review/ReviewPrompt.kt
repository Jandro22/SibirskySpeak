package com.sibirskyspeak.review

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.ConceptDrills
import com.sibirskyspeak.data.GrammarConcepts
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.RussianForms
import org.json.JSONObject

data class ReviewPrompt(
    val card: Card,
    val note: Note,
    val prompt: String,
    val expectedAnswer: String,
    val answerMode: AnswerMode,
    val intervalPreview: Map<Rating, Int>,
    val choices: List<String> = emptyList(),
    val explanation: String? = null,
    // A short rule reminder shown on the *prompt* side (before answering), so grammar
    // is taught while the learner works, not only revealed afterward. Null for plain
    // vocab cards.
    val teachingHint: String? = null,
    // Lesson cards carry their full teaching text + worked example here for a
    // dedicated teaching screen (no grading).
    val lesson: LessonContent? = null,
    val exampleSentence: String? = null,
    val exampleTranslation: String? = null
)

data class LessonContent(
    val title: String,
    val body: String,
    val exampleRu: String,
    val exampleEn: String
)

enum class AnswerMode {
    ENGLISH,
    RUSSIAN_TYPED,
    RUSSIAN_STRESS_TYPED,
    AUDIO_ONLY,
    // Speak the Russian aloud; on-device speech recognition fills the answer.
    SPEAK,
    CHOICE,
    // A teaching card: no answer to grade, just "Got it".
    LESSON
}

fun buildPrompt(
    card: Card,
    note: Note,
    intervalPreview: Map<Rating, Int>,
    aspectPartner: Note? = null
): ReviewPrompt {
    val example = note.exampleFor(card)
    val cloze = example.sentence?.clozeVocabularyAnswer(note)
    val russianContextCloze = cloze?.takeIf { note.prefersRussianContext(card) && it.prompt.hasRussianText() }
    val caseDrill = note.declensionJson?.let { caseDrillFromJson(card, note, it, example.sentence) }
    return when (card.cardType) {
        CardType.RU_TO_MEANING -> ReviewPrompt(
            card = card,
            note = note,
            prompt = note.russian,
            expectedAnswer = note.translation,
            answerMode = AnswerMode.ENGLISH,
            intervalPreview = intervalPreview
        )
        CardType.MEANING_TO_RU -> ReviewPrompt(
            card = card,
            note = note,
            prompt = russianContextCloze
                ?.let { "По контексту:\n${it.prompt}" }
                ?: note.translation,
            expectedAnswer = russianContextCloze
                ?.answer
                ?: note.russian,
            answerMode = AnswerMode.RUSSIAN_TYPED,
            intervalPreview = intervalPreview
        )
        CardType.CLOZE -> ReviewPrompt(
            card = card,
            note = note,
            prompt = cloze?.prompt ?: note.translation,
            expectedAnswer = cloze?.answer ?: note.russian,
            answerMode = AnswerMode.RUSSIAN_TYPED,
            intervalPreview = intervalPreview
        )
        CardType.AUDIO_TO_RU -> ReviewPrompt(
            card = card,
            note = note,
            prompt = "",
            expectedAnswer = note.russian,
            answerMode = AnswerMode.AUDIO_ONLY,
            intervalPreview = intervalPreview
        )
        CardType.SPEAK -> ReviewPrompt(
            card = card,
            note = note,
            // Show the word/phrase to read aloud; speech recognition scores what's said.
            prompt = note.russian,
            expectedAnswer = note.russian,
            answerMode = AnswerMode.SPEAK,
            intervalPreview = intervalPreview,
            teachingHint = "Say it aloud",
            explanation = note.translation
        )
        CardType.DICTATION -> {
            val sentence = example.sentence ?: note.russian
            ReviewPrompt(
                card = card,
                note = note,
                prompt = "",
                expectedAnswer = sentence,
                answerMode = AnswerMode.AUDIO_ONLY,
                intervalPreview = intervalPreview,
                explanation = example.translation
            )
        }
        CardType.SENTENCE_BUILD -> {
            val sentence = example.sentence ?: note.russian
            val russianOnlyPrompt = sentence
                .takeIf { note.prefersRussianContext(card) && it.hasMultipleRussianWords() }
                ?.let { "Соберите русское предложение.\n${it.reversedWordBank()}" }
            ReviewPrompt(
                card = card,
                note = note,
                prompt = buildString {
                    if (russianOnlyPrompt != null) {
                        append(russianOnlyPrompt)
                    } else {
                        append("Build the Russian sentence.")
                        example.translation?.let {
                            append("\n")
                            append(it)
                        }
                    }
                },
                expectedAnswer = sentence,
                answerMode = AnswerMode.RUSSIAN_TYPED,
                intervalPreview = intervalPreview,
                explanation = sentence
            )
        }
        CardType.STRESS_MARK -> {
            // Tap the correctly-stressed spelling rather than typing a combining
            // accent (which is impractical on a normal keyboard). Choices are the
            // word stressed on each vowel; the learner picks where the stress falls.
            val plain = note.russian.withoutStressMarks()
            val choices = stressChoices(note.russian, plain)
            ReviewPrompt(
                card = card,
                note = note,
                prompt = "Where is the stress?\n$plain",
                expectedAnswer = note.russian,
                answerMode = AnswerMode.CHOICE,
                intervalPreview = intervalPreview,
                choices = choices,
                explanation = "Stress: ${note.russian}"
            )
        }
        CardType.CASE_FILL -> ReviewPrompt(
            card = card,
            note = note,
            prompt = caseDrill?.prompt ?: example.sentence ?: note.translation,
            expectedAnswer = caseDrill?.answer ?: note.russian,
            answerMode = AnswerMode.RUSSIAN_TYPED,
            intervalPreview = intervalPreview,
            explanation = caseTeaching(card.gramCase)
        )
        CardType.ADJ_AGREE -> {
            val drill = adjAgreeDrill(card, note)
            ReviewPrompt(
                card = card,
                note = note,
                prompt = drill.prompt,
                expectedAnswer = drill.answer,
                answerMode = AnswerMode.RUSSIAN_TYPED,
                intervalPreview = intervalPreview,
                explanation = drill.explanation
            )
        }
        CardType.GENDER_ID -> {
            val gender = (card.gramGender ?: note.gender ?: "M").uppercase()
            ReviewPrompt(
                card = card,
                note = note,
                prompt = "What gender is this noun?\n${note.russian}",
                expectedAnswer = genderLabel(gender),
                answerMode = AnswerMode.CHOICE,
                intervalPreview = intervalPreview,
                choices = listOf("Masculine", "Feminine", "Neuter", "Plural-only"),
                explanation = genderTeaching(note.russian, gender)
            )
        }
        CardType.VERB_FORM -> {
            val drill = verbFormDrill(card, note, example.sentence)
            ReviewPrompt(
                card = card,
                note = note,
                prompt = drill.prompt,
                expectedAnswer = drill.answer,
                answerMode = AnswerMode.RUSSIAN_TYPED,
                intervalPreview = intervalPreview,
                explanation = drill.explanation
            )
        }
        CardType.ASPECT_SELECT -> {
            val cue = card.gramContextCue ?: "NO_CUE"
            val selfForm = RussianForms.pastMasculine(note.lemma) ?: note.russian
            val partnerForm = aspectPartner?.let { RussianForms.pastMasculine(it.lemma) ?: it.russian }
            val expectedForm = expectedAspectForm(cue, note, selfForm, aspectPartner, partnerForm)
            val drill = aspectDrill(cue, note, aspectPartner, expectedForm, listOfNotNull(selfForm, partnerForm), example.sentence)
            ReviewPrompt(
                card = card,
                note = note,
                prompt = buildString {
                    append("Choose the past-tense form that fits this context.\n")
                    append(drill.carrier)
                    append("\nContext clue: ${drill.cueLabel}")
                },
                expectedAnswer = expectedForm,
                answerMode = AnswerMode.CHOICE,
                intervalPreview = intervalPreview,
                choices = listOfNotNull(selfForm, partnerForm).distinct().shuffled(),
                explanation = drill.rationale
            )
        }
        CardType.CONCEPT_DRILL -> {
            val drill = ConceptDrills.byId(card.gramContextCue)
            ReviewPrompt(
                card = card,
                note = note,
                prompt = drill?.prompt ?: note.exampleSentence ?: note.translation,
                expectedAnswer = drill?.expectedAnswer ?: note.russian,
                answerMode = if (drill?.isChoice == true) AnswerMode.CHOICE else AnswerMode.RUSSIAN_TYPED,
                intervalPreview = intervalPreview,
                choices = drill?.choices.orEmpty(),
                explanation = drill?.explanation
            )
        }
        CardType.LESSON -> {
            val concept = GrammarConcepts.byId(card.gramConcept ?: note.conceptId)
            val content = if (concept != null) {
                LessonContent(
                    title = concept.title,
                    body = concept.lesson,
                    exampleRu = concept.exampleRu,
                    exampleEn = concept.exampleEn
                )
            } else {
                LessonContent(
                    title = note.translation.ifBlank { "New grammar" },
                    body = note.exampleTranslation ?: note.translation,
                    exampleRu = note.exampleSentence.orEmpty(),
                    exampleEn = note.exampleTranslation.orEmpty()
                )
            }
            ReviewPrompt(
                card = card,
                note = note,
                prompt = content.title,
                expectedAnswer = "Got it",
                answerMode = AnswerMode.LESSON,
                intervalPreview = intervalPreview,
                teachingHint = "New grammar",
                lesson = content
            )
        }
    }.let {
        it.copy(
            teachingHint = it.teachingHint ?: teachingHintFor(card),
            exampleSentence = example.sentence,
            exampleTranslation = example.translation
        )
    }
}

/** The prompt-side rule reminder for a grammar drill card, or null for vocab. */
private fun teachingHintFor(card: Card): String? =
    com.sibirskyspeak.data.GrammarConcepts.forCard(card)?.hint

private data class ExampleContext(val sentence: String?, val translation: String?)

private fun Note.exampleFor(card: Card): ExampleContext {
    val examples = listOf(
        exampleSentence to exampleTranslation,
        exampleSentence2 to exampleTranslation2,
        exampleSentence3 to exampleTranslation3
    ).mapNotNull { (sentence, translation) ->
        sentence?.takeIf { it.isNotBlank() }?.let { ExampleContext(it, translation?.takeIf { value -> value.isNotBlank() }) }
    }
    return examples.getOrNull(card.reps.floorMod(examples.size.coerceAtLeast(1))) ?: ExampleContext(null, null)
}

private fun Int.floorMod(modulus: Int): Int =
    ((this % modulus) + modulus) % modulus

private fun Note.prefersRussianContext(card: Card): Boolean =
    card.reps >= 2 || cefrLevel in setOf("B1", "B2", "C1") || tier >= 1

private fun String.withoutStressMarks(): String =
    replace("\u0301", "")

private val RUSSIAN_VOWELS = "\u0430\u0435\u0451\u0438\u043e\u0443\u044b\u044d\u044e\u044f\u0410\u0415\u0401\u0418\u041e\u0423\u042b\u042d\u042e\u042f".toSet()

/** Every spelling of [plain] with a stress mark on a single vowel. */
private fun stressVariants(plain: String): List<String> =
    plain.mapIndexedNotNull { i, c ->
        if (c in RUSSIAN_VOWELS) plain.substring(0, i + 1) + "\u0301" + plain.substring(i + 1) else null
    }

/**
 * Choices for a stress card: the correct stressed spelling plus up to three decoys
 * that place the stress on a different vowel, shuffled. Falls back to just the
 * correct form for single-vowel words.
 */
private fun stressChoices(correct: String, plain: String): List<String> {
    val variants = stressVariants(plain)
    val decoys = variants.filter { it != correct }.shuffled().take(3)
    return (decoys + correct).distinct().shuffled()
}

private fun String.hasMultipleRussianWords(): Boolean =
    Regex("""\p{IsCyrillic}+""").findAll(this).take(2).count() >= 2

private fun String.hasRussianText(): Boolean =
    Regex("""\p{IsCyrillic}+""").containsMatchIn(this)

private fun String.reversedWordBank(): String {
    val words = Regex("""[\p{L}\p{N}-]+""").findAll(this).map { it.value }.toList()
    return words.asReversed().joinToString(" / ")
}

// --- Adjective agreement ---------------------------------------------------

private data class AdjAgreeDrill(val prompt: String, val answer: String, val explanation: String)

private fun adjAgreeDrill(card: Card, note: Note): AdjAgreeDrill {
    val cue = card.gramContextCue ?: "FEM"
    val table = note.declensionJson?.let { runCatching { JSONObject(it) }.getOrNull() }
    val source = table?.let { if (it.has("cases")) it.getJSONObject("cases") else it }
    val masc = source?.optString("NOM_SG")?.takeIf { it.isNotBlank() } ?: note.russian
    fun form(key: String) = source?.optString(key)?.takeIf { it.isNotBlank() }
    val answer = form(cueKey(cue)) ?: note.russian
    val target = when (cue) {
        "FEM" -> "feminine singular"
        "NEUT" -> "neuter singular"
        "PL" -> "plural"
        else -> cue.lowercase()
    }
    // Teach the full nominative agreement set so the learner sees the pattern.
    val paradigm = listOfNotNull(
        form("NOM_SG")?.let { "м. $it" },
        form("FEM_NOM")?.let { "ж. $it" },
        form("NEUT_NOM")?.let { "ср. $it" },
        form("PL_NOM")?.let { "мн. $it" }
    ).joinToString(" · ")
    return AdjAgreeDrill(
        prompt = "Russian adjectives agree with their noun's gender and number.\n" +
            "Make \"${masc.withoutStressMarks()}\" agree with a $target noun.",
        answer = answer,
        explanation = buildString {
            append("Agreement endings — $paradigm. ")
            append("The adjective copies the gender and number of the noun it describes; ")
            append("the dictionary (masculine) form changes its ending to match.")
        }
    )
}

private fun cueKey(cue: String): String = when (cue) {
    "FEM" -> "FEM_NOM"
    "NEUT" -> "NEUT_NOM"
    "PL" -> "PL_NOM"
    else -> "NOM_SG"
}

// --- Noun gender -----------------------------------------------------------

private fun genderLabel(gender: String): String = when (gender) {
    "M" -> "Masculine"
    "F" -> "Feminine"
    "N" -> "Neuter"
    "PL" -> "Plural-only"
    else -> "Masculine"
}

private fun genderTeaching(russian: String, gender: String): String {
    val rule = "Gender is usually visible in the ending: a consonant or -й → masculine, " +
        "-а/-я → feminine, -о/-е → neuter. A soft sign (-ь) can be either, so those must be memorised."
    val verdict = when (gender) {
        "M" -> "\"$russian\" is masculine."
        "F" -> "\"$russian\" is feminine."
        "N" -> "\"$russian\" is neuter."
        "PL" -> "\"$russian\" is used only in the plural (pluralia tantum), so it has no singular gender."
        else -> ""
    }
    return "$verdict $rule Gender controls adjective and past-tense agreement, so it's worth knowing cold."
}

private fun caseTeaching(gramCase: String?): String = when (gramCase) {
    "GEN" -> "Genitive: possession (\"of\"), absence (нет + gen), and after many quantity words and prepositions (из, от, до, без)."
    "DAT" -> "Dative: the indirect object / recipient (\"to/for\"), and after к, по; also for age and \"need\" constructions."
    "ACC" -> "Accusative: the direct object of an action; also direction with в/на (\"into/onto\")."
    "INS" -> "Instrumental: the means \"by/with\", and after с (\"together with\"); used with быть/стать."
    "PREP" -> "Prepositional: only after prepositions, mainly location with в/на (\"in/at\") and topic with о (\"about\")."
    else -> "Pick the case the sentence requires, then change the ending to match."
}

private data class ClozeDrill(val prompt: String, val answer: String)

private data class VerbFormDrill(val prompt: String, val answer: String, val explanation: String)

private fun verbFormDrill(card: Card, note: Note, exampleSentence: String?): VerbFormDrill {
    val key = card.gramContextCue ?: "PAST_M"
    val answer = RussianForms.verbForm(note, key) ?: RussianForms.pastMasculine(note.lemma) ?: note.russian
    val blanked = exampleSentence?.blankAny(listOf(answer, note.russian, note.lemma))
    return VerbFormDrill(
        prompt = buildString {
            append("Make \"${note.lemma.withoutStressMarks()}\" ${key.verbFormLabel(note)}.")
            if (blanked != null) {
                append("\n")
                append(blanked)
            }
        },
        answer = answer,
        explanation = "${key.verbFormLabel(note).replaceFirstChar { it.uppercase() }} of ${note.lemma}."
    )
}

private fun String.verbFormLabel(note: Note? = null): String =
    when (this) {
        "PRES_1SG" -> "${presentOrFuture(note)} 1st person singular"
        "PRES_2SG" -> "${presentOrFuture(note)} 2nd person singular"
        "PRES_3SG" -> "${presentOrFuture(note)} 3rd person singular"
        "PRES_1PL" -> "${presentOrFuture(note)} 1st person plural"
        "PRES_2PL" -> "${presentOrFuture(note)} 2nd person plural"
        "PRES_3PL" -> "${presentOrFuture(note)} 3rd person plural"
        "PAST_M" -> "past masculine singular"
        "PAST_F" -> "past feminine singular"
        "PAST_N" -> "past neuter singular"
        "PAST_PL" -> "past plural"
        else -> lowercase().replace('_', ' ')
    }

private fun presentOrFuture(note: Note?): String =
    if (note?.aspect == "PF") "future" else "present"

private fun String.clozeVocabularyAnswer(note: Note): ClozeDrill? {
    val candidates = RussianForms.surfaceForms(note) + listOf(note.russian, note.lemma)
    return candidates
        .filter { it.isNotBlank() }
        .distinctBy { RussianForms.normalize(it) }
        .sortedByDescending { it.length }
        .firstNotNullOfOrNull { candidate ->
            val replaced = replace(candidate, "____", ignoreCase = true)
            replaced.takeIf { it != this }?.let { ClozeDrill(prompt = it, answer = candidate) }
        }
}

private fun expectedAspectForm(
    cue: String,
    note: Note,
    selfForm: String,
    aspectPartner: Note?,
    partnerForm: String?
): String {
    val desiredAspect = desiredAspectForCue(cue, note)
    return when {
        note.aspect == desiredAspect -> selfForm
        aspectPartner?.aspect == desiredAspect && partnerForm != null -> partnerForm
        else -> selfForm
    }
}

private fun desiredAspectForCue(cue: String, note: Note): String =
    when (cue) {
        "PROCESS", "HABITUAL" -> "IPF"
        "COMPLETED", "RESULT", "SINGLE_EVENT", "HAS_CUE" -> "PF"
        else -> if (note.aktionsart?.lowercase() in setOf("achievement", "accomplishment")) "PF" else "IPF"
    }

private data class AspectDrill(val carrier: String, val cueLabel: String, val rationale: String)

private fun aspectDrill(
    cue: String,
    note: Note,
    aspectPartner: Note?,
    expectedForm: String,
    choiceForms: List<String>,
    exampleSentence: String?
): AspectDrill {
    val aktLabel = note.aktionsart?.replaceFirstChar { it.uppercase() } ?: "Unknown"
    val blankCandidates = choiceForms + listOfNotNull(note.lemma, note.russian, aspectPartner?.lemma, aspectPartner?.russian, expectedForm)
    return when (cue) {
        "PROCESS" -> AspectDrill(
            carrier = "Вчера он долго ___ этот вопрос.".blankAny(blankCandidates),
            cueLabel = "ongoing process",
            rationale = "A duration/process cue (долго) focuses on the action while it was unfolding, so imperfective fits."
        )
        "HABITUAL" -> AspectDrill(
            carrier = "Раньше он часто ___ этот вопрос.".blankAny(blankCandidates),
            cueLabel = "repeated or habitual action",
            rationale = "A frequency cue (часто) asks for repeated action, which is normally imperfective."
        )
        "COMPLETED" -> AspectDrill(
            carrier = "Вчера он ___ этот вопрос до конца.".blankAny(blankCandidates),
            cueLabel = "completed action",
            rationale = "The endpoint phrase до конца presents the action as completed, so perfective fits."
        )
        "RESULT" -> AspectDrill(
            carrier = "Наконец он ___ этот вопрос, и результат готов.".blankAny(blankCandidates),
            cueLabel = "result now matters",
            rationale = "The context cares about the resulting state, so the bounded perfective form is the better fit."
        )
        "SINGLE_EVENT" -> AspectDrill(
            carrier = "На встрече он сразу ___ этот вопрос.".blankAny(blankCandidates),
            cueLabel = "single bounded event",
            rationale = "A single bounded event cue (сразу, one occasion) pushes toward perfective."
        )
        "HAS_CUE" -> {
            val carrier = exampleSentence ?: "He finally ___ the task to completion."
            AspectDrill(
                carrier = carrier.blankAny(blankCandidates),
                cueLabel = "completion or boundary marker",
                rationale = buildString {
                    append("The context supplies a boundary cue, such as a result object or endpoint phrase. ")
                    append("That cue can override the $aktLabel default and push the answer toward a bounded reading.")
                }
            )
        }
        else -> {
            val carrier = exampleSentence ?: "The sides ___ this question during negotiations."
            val defaultAspect = when (note.aktionsart?.lowercase()) {
                "achievement", "accomplishment" -> "PF"
                else -> "IPF"
            }
            AspectDrill(
                carrier = carrier.blankAny(blankCandidates),
                cueLabel = "no explicit completion marker",
                rationale = buildString {
                    append("No boundary cue appears in the context, so the verb's action type matters more. ")
                    append("$aktLabel verbs usually lean ")
                    append(if (defaultAspect == "PF") "bounded (perfective)" else "unbounded (imperfective)")
                    append(" when the context is neutral.")
                }
            )
        }
    }
}

private fun String.blankAny(candidates: List<String>): String =
    candidates
        .filter { it.isNotBlank() }
        .distinctBy { RussianForms.normalize(it) }
        .sortedByDescending { it.length }
        .firstNotNullOfOrNull { candidate ->
            replace(candidate, "___", ignoreCase = true).takeIf { it != this }
        }
        ?: this

private data class CaseDrill(val prompt: String, val answer: String)

private fun caseDrillFromJson(card: Card, note: Note, rawJson: String, exampleSentence: String?): CaseDrill? = runCatching {
    val json = JSONObject(rawJson)
    val cases = if (json.has("cases")) json.getJSONObject("cases") else json
    val targetFromCard = listOfNotNull(card.gramCase, card.gramNumber)
        .joinToString("_")
        .uppercase()
        .takeIf { it.isNotBlank() && !it.startsWith("NOM_") && cases.optString(it).isNotBlank() }
    val target = targetFromCard ?: fallbackCaseKey(cases)
        ?: return@runCatching null
    val answer = cases.getString(target)
    val carrier = exampleSentence
    val blankedCarrier = carrier?.blankCaseAnswer(answer, note)
    CaseDrill(
        prompt = buildString {
            append("Make \"${note.russian.withoutStressMarks()}\" ${target.humanCaseLabel()}.")
            if (blankedCarrier != null) {
                append("\n")
                append(blankedCarrier)
            }
        },
        answer = answer
    )
}.getOrNull()

private fun String.blankCaseAnswer(answer: String, note: Note): String? {
    val candidates = listOf(answer, note.russian, note.lemma)
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
    for (candidate in candidates) {
        val replaced = replace(candidate, "____", ignoreCase = true)
        if (replaced != this) return replaced
    }
    return null
}

private fun String.humanCaseLabel(): String {
    val parts = split("_")
    val case = when (parts.getOrNull(0)) {
        "NOM" -> "nominative"
        "GEN" -> "genitive"
        "DAT" -> "dative"
        "ACC" -> "accusative"
        "INS" -> "instrumental"
        "PREP" -> "prepositional"
        else -> lowercase()
    }
    val number = when (parts.getOrNull(1)) {
        "SG" -> "singular"
        "PL" -> "plural"
        else -> ""
    }
    return listOf(case, number).filter { it.isNotBlank() }.joinToString(" ")
}

private fun fallbackCaseKey(cases: JSONObject): String? {
    val keys = listOf("GEN_SG", "DAT_SG", "ACC_SG", "INS_SG", "PREP_SG", "GEN_PL", "DAT_PL", "ACC_PL", "INS_PL", "PREP_PL")
    return keys.firstOrNull { cases.optString(it).isNotBlank() }
}

fun diagnosticFeedbackFor(prompt: ReviewPrompt, actualAnswer: String): String? {
    if (prompt.card.cardType != CardType.CASE_FILL) return null
    val rawJson = prompt.note.declensionJson ?: return null
    val actual = normalizeRussian(actualAnswer)
    if (actual.isBlank()) return null
    return runCatching {
        val json = JSONObject(rawJson)
        val cases = if (json.has("cases")) json.getJSONObject("cases") else json
        val target = listOfNotNull(prompt.card.gramCase, prompt.card.gramNumber)
            .joinToString("_")
            .uppercase()
            .takeIf { it.isNotBlank() && cases.optString(it).isNotBlank() }
            ?: fallbackCaseKey(cases)
            ?: return@runCatching null
        val matchedCase = cases.keys().asSequence()
            .firstOrNull { key -> normalizeRussian(cases.optString(key)) == actual }
        when {
            actual == normalizeRussian(prompt.note.russian) || actual == normalizeRussian(prompt.note.lemma) ->
                "You left it in the dictionary/nominative form; this prompt asks for ${target.humanCaseLabel()}."
            matchedCase != null && matchedCase != target ->
                "You made ${matchedCase.humanCaseLabel()}; this prompt asks for ${target.humanCaseLabel()}."
            else ->
                "This prompt asks for ${target.humanCaseLabel()}; check the case ending for ${prompt.note.gender ?: "this noun"} ${prompt.card.gramNumber?.lowercase().orEmpty()}."
        }
    }.getOrNull()
}
