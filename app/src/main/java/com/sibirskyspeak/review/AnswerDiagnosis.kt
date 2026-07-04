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
data class Diagnosis(val expectedKey: String, val producedKey: String)

fun classifyAnswer(prompt: ReviewPrompt, actualAnswer: String): Diagnosis? = when (prompt.card.cardType) {
    CardType.CASE_FILL -> classifyCaseFill(prompt, actualAnswer)
    CardType.ADJ_AGREE -> classifyAdjAgree(prompt, actualAnswer)
    CardType.VERB_FORM -> classifyVerbForm(prompt, actualAnswer)
    else -> null
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
        if (matchedCase != null && matchedCase != target) Diagnosis(target, matchedCase) else null
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
        if (matched != target) Diagnosis(target, matched) else null
    }.getOrNull()
}

private fun classifyVerbForm(prompt: ReviewPrompt, actualAnswer: String): Diagnosis? {
    val target = prompt.card.gramContextCue ?: "PAST_M"
    val actual = normalizeRussian(actualAnswer)
    if (actual.isBlank()) return null
    val matchedForm = verbFormsFromJson(prompt.note.declensionJson)
        .firstOrNull { (_, form) -> normalizeRussian(form) == actual }
        ?: return null
    return if (matchedForm.first != target) Diagnosis(target, matchedForm.first) else null
}
