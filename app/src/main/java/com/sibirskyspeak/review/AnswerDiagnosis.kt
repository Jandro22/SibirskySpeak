package com.sibirskyspeak.review

import com.sibirskyspeak.data.CardType
import org.json.JSONObject

/**
 * Structured half of the classification [diagnosticFeedbackFor] already computes
 * to build its display string (P4.5) — this is the machine-usable part, persisted
 * as a confusion_event row (LearningRepository.recordConfusionEvent) so a
 * confusion pair with enough recurrences can drive a contrastive-pair insertion.
 * The UI strings in ReviewPrompt.kt are unchanged; this only extracts the same
 * "which wrong form did the learner produce" logic into data instead of prose.
 */
enum class ErrorCategory {
    CASE_ROLE, CASE_ENDING, PREPOSITION_CASE, GENDER, NUMBER, AGREEMENT,
    VERB_CONJUGATION, TENSE, ASPECT_CHOICE, MOTION_CONSTRUAL, REFLEXIVE,
    WORD_ORDER, ORTHOGRAPHY, LISTENING_DISCRIMINATION, LEXICAL_CONFUSION, REGISTER
}

data class Diagnosis(
    val expectedKey: String,
    val producedKey: String,
    val category: ErrorCategory = ErrorCategory.ORTHOGRAPHY
)

/**
 * Phase G3: the deterministic repair recipe for a recurring [ErrorCategory] — which
 * [CardType] best re-drills that category, tried first against the same note the
 * miss happened on. Distinct from P4.5's cross-session, DB-persisted confusion-pair
 * reordering (LearningRepository.applyContrastivePairing/applyInterferenceSeeding):
 * this is a same-session, immediate injection the moment a category recurs (see
 * ReviewViewModel.immediateRepairIfRecurring). Categories with no single card type
 * that isolates the contrast in today's engine (WORD_ORDER, ORTHOGRAPHY, REGISTER)
 * return null rather than guessing.
 */
fun repairCardTypeFor(category: ErrorCategory): CardType? = when (category) {
    ErrorCategory.CASE_ROLE, ErrorCategory.CASE_ENDING, ErrorCategory.PREPOSITION_CASE -> CardType.CASE_FILL
    ErrorCategory.GENDER -> CardType.GENDER_ID
    ErrorCategory.NUMBER, ErrorCategory.AGREEMENT -> CardType.ADJ_AGREE
    ErrorCategory.VERB_CONJUGATION, ErrorCategory.TENSE, ErrorCategory.REFLEXIVE -> CardType.VERB_FORM
    ErrorCategory.ASPECT_CHOICE, ErrorCategory.MOTION_CONSTRUAL -> CardType.ASPECT_SELECT
    ErrorCategory.LISTENING_DISCRIMINATION -> CardType.AUDIO_TO_RU
    ErrorCategory.LEXICAL_CONFUSION -> CardType.MEANING_TO_RU
    ErrorCategory.WORD_ORDER, ErrorCategory.ORTHOGRAPHY, ErrorCategory.REGISTER -> null
}

fun classifyAnswer(prompt: ReviewPrompt, actualAnswer: String): Diagnosis? = when (prompt.card.cardType) {
    CardType.CASE_FILL -> classifyCaseFill(prompt, actualAnswer)
    CardType.ADJ_AGREE -> classifyAdjAgree(prompt, actualAnswer)
    CardType.VERB_FORM -> classifyVerbForm(prompt, actualAnswer)
    CardType.ASPECT_SELECT -> if (normalizeRussian(actualAnswer).isNotBlank() &&
        normalizeRussian(actualAnswer) != normalizeRussian(prompt.expectedAnswer)) {
        Diagnosis(prompt.expectedAnswer, actualAnswer, ErrorCategory.ASPECT_CHOICE)
    } else null
    CardType.DICTATION, CardType.AUDIO_TO_RU -> if (normalizeRussian(actualAnswer).isNotBlank() &&
        editDistanceAtMostOne(normalizeRussian(actualAnswer), normalizeRussian(prompt.expectedAnswer))) {
        Diagnosis(prompt.expectedAnswer, actualAnswer, ErrorCategory.LISTENING_DISCRIMINATION)
    } else null
    CardType.MEANING_TO_RU -> typedMismatch(prompt, actualAnswer, ErrorCategory.LEXICAL_CONFUSION)
    CardType.TRANSFORM, CardType.NOVEL_PRODUCE -> {
        val actual = normalizeRussian(actualAnswer)
        val expected = normalizeRussian(prompt.expectedAnswer)
        if (actual.isBlank() || actual == expected) null
        else if (actual.split(' ').sorted() == expected.split(' ').sorted()) Diagnosis(expected, actual, ErrorCategory.WORD_ORDER)
        else typedMismatch(prompt, actualAnswer, ErrorCategory.ORTHOGRAPHY)
    }
    else -> null
}

private fun typedMismatch(prompt: ReviewPrompt, actualAnswer: String, fallback: ErrorCategory): Diagnosis? {
    val actual = normalizeRussian(actualAnswer)
    val expected = normalizeRussian(prompt.expectedAnswer)
    if (actual.isBlank() || actual == expected) return null
    return Diagnosis(expected, actual, if (editDistanceAtMostOne(actual, expected)) ErrorCategory.ORTHOGRAPHY else fallback)
}

private fun classifyCaseFill(prompt: ReviewPrompt, actualAnswer: String): Diagnosis? {
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
        val matchedCase = cases.keys().asSequence().firstOrNull { key -> normalizeRussian(cases.optString(key)) == actual }
        if (matchedCase != null && matchedCase != target) Diagnosis(target, matchedCase, ErrorCategory.CASE_ENDING) else null
    }.getOrNull()
}

private fun classifyAdjAgree(prompt: ReviewPrompt, actualAnswer: String): Diagnosis? {
    val raw = prompt.note.declensionJson ?: return null
    val actual = normalizeRussian(actualAnswer)
    if (actual.isBlank()) return null
    return runCatching {
        val json = JSONObject(raw)
        val forms = if (json.has("cases")) json.getJSONObject("cases") else json
        val labelKeys = setOf("NOM_SG", "FEM_NOM", "NEUT_NOM", "PL_NOM")
        val matched = labelKeys.firstOrNull { normalizeRussian(forms.optString(it)) == actual } ?: return@runCatching null
        val target = when (prompt.card.gramContextCue) {
            "FEM" -> "FEM_NOM"
            "NEUT" -> "NEUT_NOM"
            "PL" -> "PL_NOM"
            else -> "NOM_SG"
        }
        if (matched != target) Diagnosis(target, matched, ErrorCategory.AGREEMENT) else null
    }.getOrNull()
}

private fun classifyVerbForm(prompt: ReviewPrompt, actualAnswer: String): Diagnosis? {
    val target = prompt.card.gramContextCue ?: "PAST_M"
    val actual = normalizeRussian(actualAnswer)
    if (actual.isBlank()) return null
    val matchedForm = verbFormsFromJson(prompt.note.declensionJson)
        .firstOrNull { (_, form) -> normalizeRussian(form) == actual }
        ?: return null
    val category = if (target.startsWith("PAST") || matchedForm.first.startsWith("PAST")) ErrorCategory.TENSE else ErrorCategory.VERB_CONJUGATION
    return if (matchedForm.first != target) Diagnosis(target, matchedForm.first, category) else null
}

private fun editDistanceAtMostOne(a: String, b: String): Boolean {
    if (a == b || kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0; var j = 0; var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) { i++; j++; continue }
        if (++edits > 1) return false
        when {
            a.length > b.length -> i++
            b.length > a.length -> j++
            else -> { i++; j++ }
        }
    }
    return edits + (if (i < a.length || j < b.length) 1 else 0) == 1
}
