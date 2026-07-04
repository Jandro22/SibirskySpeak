package com.sibirskyspeak.learning

import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.RussianForms
import com.sibirskyspeak.review.ReviewPrompt

/** The learning job a card performs. These are deliberately separate from UI input
 * modes: a cloze and a case drill are both typed, but they train different knowledge. */
enum class LearningFacet { MEANING, FORM, CONTEXT, LISTENING, PRONUNCIATION, SYNTAX, MORPHOLOGY, INSTRUCTION }

enum class LearningStage { FIRST_CONTACT, ACQUISITION, CONSOLIDATION, TRANSFER }

enum class EvidenceStrength {
    /** A real independent retrieval that can carry normal scheduling evidence. */
    STRONG,
    /** Useful retrieval, but cueing/choice/ASR makes success weaker evidence. */
    MODERATE,
    /** Deliberate practice whose success should be interpreted conservatively. */
    PRACTICE,
    /** Exposition, not evidence of memory. */
    INSTRUCTION
}

data class CardPedagogyProfile(
    val facet: LearningFacet,
    val evidence: EvidenceStrength,
    /** Relative mental/time cost used to prevent a session full of typing marathons. */
    val cognitiveCost: Double,
    /** Stage preferences never reach zero: every implemented format remains available
     * when it is due, while generation/content guards still prevent invalid prompts. */
    val stageWeights: Map<LearningStage, Double>,
    val transferTarget: String
) {
    fun weight(stage: LearningStage): Double = stageWeights.getValue(stage)
}

/** Single source of truth for the pedagogical semantics of all fifteen persisted
 * card types. Queue construction, live selection, telemetry, and reports use this
 * instead of inferring learning value from keyboard/choice/audio UI alone. */
object CardPedagogy {
    private fun weights(first: Double, acquisition: Double, consolidation: Double, transfer: Double) = mapOf(
        LearningStage.FIRST_CONTACT to first,
        LearningStage.ACQUISITION to acquisition,
        LearningStage.CONSOLIDATION to consolidation,
        LearningStage.TRANSFER to transfer
    )

    val profiles: Map<CardType, CardPedagogyProfile> = mapOf(
        CardType.RU_TO_MEANING to CardPedagogyProfile(LearningFacet.MEANING, EvidenceStrength.STRONG, 0.55, weights(1.50, 1.30, 0.95, 0.65), "understand the form in new reading contexts"),
        CardType.MEANING_TO_RU to CardPedagogyProfile(LearningFacet.FORM, EvidenceStrength.STRONG, 1.05, weights(0.35, 1.15, 1.35, 1.20), "retrieve the Russian form for an intended meaning"),
        CardType.CLOZE to CardPedagogyProfile(LearningFacet.CONTEXT, EvidenceStrength.STRONG, 1.00, weights(0.30, 0.90, 1.35, 1.50), "supply the form in an unseen sentence"),
        CardType.AUDIO_TO_RU to CardPedagogyProfile(LearningFacet.LISTENING, EvidenceStrength.MODERATE, 0.95, weights(0.45, 0.90, 1.05, 1.10), "decode a heard word and reconnect it to meaning"),
        CardType.SPEAK to CardPedagogyProfile(LearningFacet.PRONUNCIATION, EvidenceStrength.PRACTICE, 0.90, weights(0.30, 0.80, 1.00, 1.15), "produce intelligible speech without written support"),
        CardType.DICTATION to CardPedagogyProfile(LearningFacet.LISTENING, EvidenceStrength.STRONG, 1.65, weights(0.20, 0.55, 1.10, 1.40), "segment and transcribe connected speech"),
        CardType.SENTENCE_BUILD to CardPedagogyProfile(LearningFacet.SYNTAX, EvidenceStrength.MODERATE, 1.55, weights(0.20, 0.55, 1.05, 1.45), "construct a grammatical sentence rather than memorize one order"),
        CardType.STRESS_MARK to CardPedagogyProfile(LearningFacet.PRONUNCIATION, EvidenceStrength.MODERATE, 0.50, weights(0.35, 0.75, 0.75, 0.55), "perceive and produce unpredictable lexical stress"),
        CardType.CASE_FILL to CardPedagogyProfile(LearningFacet.MORPHOLOGY, EvidenceStrength.STRONG, 1.20, weights(0.20, 0.85, 1.30, 1.50), "select and form case from a governing context"),
        CardType.VERB_FORM to CardPedagogyProfile(LearningFacet.MORPHOLOGY, EvidenceStrength.STRONG, 1.10, weights(0.20, 0.90, 1.30, 1.45), "produce person, number, tense, and gender forms in context"),
        CardType.ADJ_AGREE to CardPedagogyProfile(LearningFacet.MORPHOLOGY, EvidenceStrength.STRONG, 1.05, weights(0.20, 0.85, 1.25, 1.45), "infer agreement from the noun phrase"),
        CardType.GENDER_ID to CardPedagogyProfile(LearningFacet.MORPHOLOGY, EvidenceStrength.MODERATE, 0.45, weights(0.55, 0.75, 0.50, 0.30), "apply noun gender in agreement"),
        CardType.ASPECT_SELECT to CardPedagogyProfile(LearningFacet.SYNTAX, EvidenceStrength.MODERATE, 0.85, weights(0.15, 0.55, 1.00, 1.35), "choose aspect in a novel, verb-appropriate context"),
        CardType.CONCEPT_DRILL to CardPedagogyProfile(LearningFacet.SYNTAX, EvidenceStrength.STRONG, 1.10, weights(0.25, 1.00, 1.30, 1.45), "apply an authored grammar concept to a new example"),
        CardType.LESSON to CardPedagogyProfile(LearningFacet.INSTRUCTION, EvidenceStrength.INSTRUCTION, 0.65, weights(1.50, 0.50, 0.20, 0.10), "complete guided practice after explanation"),
        // Transfer-heaviest profile in the deck: the carrier sentence is freshly
        // composed every rep (FrameRealizer), so success can only come from applying
        // the rule, never from recognizing a memorized string. See CLAUDE.md P4.3.
        CardType.CONCEPT_APPLY to CardPedagogyProfile(LearningFacet.SYNTAX, EvidenceStrength.STRONG, 1.15, weights(0.10, 0.60, 1.20, 1.60), "apply the concept's rule inside a sentence that has never been seen before"),
        // Chunks are the unit of fluent speech, not a grammar drill — closer in cost
        // to plain production than to a morphology drill.
        CardType.CHUNK to CardPedagogyProfile(LearningFacet.CONTEXT, EvidenceStrength.STRONG, 1.05, weights(0.20, 0.75, 1.15, 1.30), "produce a natural multi-word chunk, not just the bare word"),
        // Infinite, novel carriers with zero authored content — re-inflecting a held
        // sentence is exactly the skill fluent production requires.
        CardType.TRANSFORM to CardPedagogyProfile(LearningFacet.SYNTAX, EvidenceStrength.STRONG, 1.30, weights(0.15, 0.55, 1.15, 1.50), "re-inflect a real sentence under a novel instruction"),
        // The ladder's payoff: producing a sentence that has never existed before.
        // Grading is lexical/word-order-free (see AnswerNormalizer), not a full
        // morphological check, so evidence is PRACTICE despite the high cost/value —
        // per CLAUDE.md principle 2, grade weaker when uncertain about the retrieval.
        CardType.NOVEL_PRODUCE to CardPedagogyProfile(LearningFacet.SYNTAX, EvidenceStrength.PRACTICE, 1.60, weights(0.10, 0.35, 0.90, 1.60), "produce a wholly novel sentence from an English cue alone"),
        // ASR noise plus the ceiling-probe nature of imitation ("you cannot repeat
        // above your competence") both argue for PRACTICE, not STRONG, evidence.
        CardType.SPEAK_SENTENCE to CardPedagogyProfile(LearningFacet.PRONUNCIATION, EvidenceStrength.PRACTICE, 1.45, weights(0.20, 0.60, 1.05, 1.25), "repeat a full sentence from memory, proving you parsed it")
    )

    fun profile(type: CardType): CardPedagogyProfile = profiles.getValue(type)

    fun stage(prompt: ReviewPrompt): LearningStage = when {
        prompt.card.cardType == CardType.LESSON || (prompt.card.reps == 0 && prompt.note.encounterCount == 0) -> LearningStage.FIRST_CONTACT
        prompt.card.reps == 0 || prompt.card.reps < 2 -> LearningStage.ACQUISITION
        prompt.card.reps < 5 || prompt.card.consecutiveCorrect < 3 -> LearningStage.CONSOLIDATION
        else -> LearningStage.TRANSFER
    }

    /** Small bounded selector term. Due-date urgency remains dominant; this only
     * breaks ties toward a balanced, stage-appropriate learning diet. */
    fun selectorUtility(prompt: ReviewPrompt): Double {
        val profile = profile(prompt.card.cardType)
        val stageFit = profile.weight(stage(prompt))
        val evidence = when (profile.evidence) {
            EvidenceStrength.STRONG -> 0.30
            EvidenceStrength.MODERATE -> 0.15
            EvidenceStrength.PRACTICE -> 0.05
            EvidenceStrength.INSTRUCTION -> 0.0
        }
        val formatSpecific = if (prompt.card.cardType == CardType.GENDER_ID) {
            if (hasTransparentGender(prompt)) -0.25 else 0.15
        } else 0.0
        return (stageFit - 0.75) * 0.55 + evidence + formatSpecific -
            (profile.cognitiveCost - 1.0).coerceAtLeast(0.0) * 0.12
    }

    private fun hasTransparentGender(prompt: ReviewPrompt): Boolean {
        val gender = (prompt.card.gramGender ?: prompt.note.gender)?.uppercase() ?: return false
        val ending = RussianForms.normalize(prompt.note.russian).lastOrNull() ?: return false
        return when (gender) {
            "M" -> ending !in "аяь"
            "F" -> ending in "ая"
            "N" -> ending in "ое"
            else -> false
        }
    }
}
