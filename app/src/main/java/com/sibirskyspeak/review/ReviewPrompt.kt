package com.sibirskyspeak.review

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
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
    val explanation: String? = null
)

enum class AnswerMode {
    ENGLISH,
    RUSSIAN_TYPED,
    AUDIO_ONLY,
    CHOICE
}

fun buildPrompt(
    card: Card,
    note: Note,
    intervalPreview: Map<Rating, Int>,
    aspectPartner: Note? = null
): ReviewPrompt {
    val cloze = note.exampleSentence?.clozeVocabularyAnswer(note)
    val caseDrill = note.declensionJson?.let { caseDrillFromJson(card, note, it) }
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
            prompt = note.translation,
            expectedAnswer = note.russian,
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
        CardType.CASE_FILL -> ReviewPrompt(
            card = card,
            note = note,
            prompt = caseDrill?.prompt ?: note.exampleSentence ?: note.translation,
            expectedAnswer = caseDrill?.answer ?: note.russian,
            answerMode = AnswerMode.RUSSIAN_TYPED,
            intervalPreview = intervalPreview
        )
        CardType.VERB_FORM -> {
            val drill = verbFormDrill(card, note)
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
            val drill = aspectDrill(cue, note, aspectPartner, expectedForm, listOfNotNull(selfForm, partnerForm))
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
    }
}

private data class ClozeDrill(val prompt: String, val answer: String)

private data class VerbFormDrill(val prompt: String, val answer: String, val explanation: String)

private fun verbFormDrill(card: Card, note: Note): VerbFormDrill {
    val key = card.gramContextCue ?: "PAST_M"
    val answer = RussianForms.verbForm(note.lemma, key) ?: RussianForms.pastMasculine(note.lemma) ?: note.russian
    val blanked = note.exampleSentence?.blankAny(listOf(answer, note.russian, note.lemma))
    return VerbFormDrill(
        prompt = buildString {
            append("Make \"${note.translation}\" ${key.verbFormLabel()}.")
            if (blanked != null) {
                append("\n")
                append(blanked)
            }
        },
        answer = answer,
        explanation = "${key.verbFormLabel().replaceFirstChar { it.uppercase() }} of ${note.lemma}."
    )
}

private fun String.verbFormLabel(): String =
    when (this) {
        "PRES_1SG" -> "present 1st person singular"
        "PRES_2SG" -> "present 2nd person singular"
        "PRES_3SG" -> "present 3rd person singular"
        "PRES_1PL" -> "present 1st person plural"
        "PRES_2PL" -> "present 2nd person plural"
        "PRES_3PL" -> "present 3rd person plural"
        "PAST_M" -> "past masculine singular"
        "PAST_F" -> "past feminine singular"
        "PAST_N" -> "past neuter singular"
        "PAST_PL" -> "past plural"
        else -> lowercase().replace('_', ' ')
    }

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
    val desiredAspect = when {
        cue == "HAS_CUE" -> "PF"
        note.aktionsart?.lowercase() in setOf("achievement", "accomplishment") -> "PF"
        else -> "IPF"
    }
    return when {
        note.aspect == desiredAspect -> selfForm
        aspectPartner?.aspect == desiredAspect && partnerForm != null -> partnerForm
        else -> selfForm
    }
}

private data class AspectDrill(val carrier: String, val cueLabel: String, val rationale: String)

private fun aspectDrill(
    cue: String,
    note: Note,
    aspectPartner: Note?,
    expectedForm: String,
    choiceForms: List<String>
): AspectDrill {
    val aktLabel = note.aktionsart?.replaceFirstChar { it.uppercase() } ?: "Unknown"
    val blankCandidates = choiceForms + listOfNotNull(note.lemma, note.russian, aspectPartner?.lemma, aspectPartner?.russian, expectedForm)
    return when (cue) {
        "HAS_CUE" -> {
            val carrier = note.exampleSentence ?: "He finally ___ the task to completion."
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
            val carrier = note.exampleSentence ?: "The sides ___ this question during negotiations."
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

private fun caseDrillFromJson(card: Card, note: Note, rawJson: String): CaseDrill? = runCatching {
    val json = JSONObject(rawJson)
    val cases = if (json.has("cases")) json.getJSONObject("cases") else json
    val targetFromCard = listOfNotNull(card.gramCase, card.gramNumber)
        .joinToString("_")
        .uppercase()
        .takeIf { it.isNotBlank() && !it.startsWith("NOM_") && cases.optString(it).isNotBlank() }
    val target = targetFromCard ?: fallbackCaseKey(cases)
        ?: return@runCatching null
    val answer = cases.getString(target)
    val carrier = note.exampleSentence
    val blankedCarrier = carrier?.blankCaseAnswer(answer, note)
    CaseDrill(
        prompt = buildString {
            append("Make \"${note.translation}\" ${target.humanCaseLabel()}.")
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
