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
    val cloze = note.exampleSentence?.replace(note.russian, "____", ignoreCase = true)
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
            prompt = cloze ?: note.translation,
            expectedAnswer = note.russian,
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
        CardType.ASPECT_SELECT -> {
            val cue = card.gramContextCue ?: "NO_CUE"
            val selfForm = RussianForms.pastMasculine(note.lemma) ?: note.russian
            val partnerForm = aspectPartner?.let { RussianForms.pastMasculine(it.lemma) ?: it.russian }
            val drill = aspectDrill(cue, note, selfForm)
            ReviewPrompt(
                card = card,
                note = note,
                prompt = buildString {
                    append("Verb pair: ")
                    append(note.lemma)
                    if (aspectPartner != null) append(" / ${aspectPartner.lemma}")
                    append("  [${note.aktionsart?.uppercase() ?: "AKTIONSART"}]\n")
                    append(drill.carrier)
                    append("\nContext: ${drill.cueLabel}")
                },
                expectedAnswer = selfForm,
                answerMode = AnswerMode.CHOICE,
                intervalPreview = intervalPreview,
                choices = listOfNotNull(selfForm, partnerForm).distinct().shuffled(),
                explanation = drill.rationale
            )
        }
    }
}

private data class AspectDrill(val carrier: String, val cueLabel: String, val rationale: String)

private fun aspectDrill(cue: String, note: Note, selfForm: String): AspectDrill {
    val aktLabel = note.aktionsart?.replaceFirstChar { it.uppercase() } ?: "Unknown"
    val inf = note.lemma
    return when (cue) {
        "HAS_CUE" -> {
            // Boundary-supplying context: object or endpoint phrase overrides Aktionsart default
            val carrier = note.exampleSentence
                ?: "Он наконец ___ это дело до конца."
            AspectDrill(
                carrier = carrier.replace(inf, "___", ignoreCase = true).replace(selfForm, "___", ignoreCase = true),
                cueLabel = "HAS_CUE — sentence contains a completion/boundary marker",
                rationale = buildString {
                    append("Context supplies a boundary cue (result object, endpoint phrase, nakonets, etc.), ")
                    append("which overrides the $aktLabel Aktionsart default. ")
                    if (note.aspect == "IPF") {
                        append("Even though the verb's Aktionsart leans toward an ongoing event, the explicit cue forces the bounded (PF) reading.")
                    } else {
                        append("The boundary cue confirms the bounded (PF) reading expected from $aktLabel Aktionsart.")
                    }
                }
            )
        }
        else -> {
            // NO_CUE: Aktionsart decides
            val carrier = note.exampleSentence
                ?: "Стороны ___ этот вопрос на переговорах."
            val defaultAspect = when (note.aktionsart?.lowercase()) {
                "achievement", "accomplishment" -> "PF"
                else -> "IPF"  // state, activity → imperfective default
            }
            AspectDrill(
                carrier = carrier.replace(inf, "___", ignoreCase = true).replace(selfForm, "___", ignoreCase = true),
                cueLabel = "NO_CUE — no explicit completion marker in context",
                rationale = buildString {
                    append("No boundary cue in context, so Aktionsart decides. ")
                    append("$aktLabel verbs default to ")
                    append(if (defaultAspect == "PF") "bounded (PF)" else "unbounded (IPF)")
                    append(" when context is neutral. ")
                    append("Remember: context cues can override this default — ")
                    append("see the HAS_CUE variant of this card.")
                }
            )
        }
    }
}

private data class CaseDrill(val prompt: String, val answer: String)

private fun caseDrillFromJson(card: Card, note: Note, rawJson: String): CaseDrill? = runCatching {
    val json = JSONObject(rawJson)
    val cases = if (json.has("cases")) json.getJSONObject("cases") else json
    val target = listOfNotNull(card.gramCase, card.gramNumber)
        .joinToString("_")
        .uppercase()
        .takeIf { it.isNotBlank() && cases.optString(it).isNotBlank() }
        ?: fallbackCaseKey(cases)
        ?: return@runCatching null
    val carrier = note.exampleSentence?.replace(note.russian, "____", ignoreCase = true) ?: "____"
    CaseDrill(
        prompt = "Fill ${target.replace('_', ' ')} (${listOfNotNull(card.gramGender, card.gramNumber).joinToString(" ")}): $carrier",
        answer = cases.getString(target)
    )
}.getOrNull()

private fun fallbackCaseKey(cases: JSONObject): String? {
    val keys = listOf("GEN_SG", "DAT_SG", "ACC_SG", "INS_SG", "PREP_SG", "GEN_PL", "DAT_PL", "ACC_PL", "INS_PL", "PREP_PL")
    return keys.firstOrNull { cases.optString(it).isNotBlank() }
}
