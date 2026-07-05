package com.sibirskyspeak.review

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Queue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerDiagnosisTest {
    @Test
    fun classifiesCaseFillConfusionAsExpectedVsProducedKeys() {
        val card = Card(noteId = 1, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramCase = "GEN", gramNumber = "SG")
        val note = Note(
            id = 1, russian = "state", lemma = "state", translation = "state", partOfSpeech = "noun", gender = "M",
            declensionJson = """{"NOM_SG":"state","GEN_SG":"state_gen","DAT_SG":"state_dat"}"""
        )
        val prompt = buildPrompt(card, note, emptyMap())

        val diagnosis = classifyAnswer(prompt, "state_dat")

        assertEquals(Diagnosis("GEN_SG", "DAT_SG", ErrorCategory.CASE_ENDING), diagnosis)
    }

    @Test
    fun returnsNullWhenTheAnswerDoesNotMatchAnyKnownForm() {
        val card = Card(noteId = 1, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramCase = "GEN", gramNumber = "SG")
        val note = Note(
            id = 1, russian = "state", lemma = "state", translation = "state", partOfSpeech = "noun", gender = "M",
            declensionJson = """{"NOM_SG":"state","GEN_SG":"state_gen","DAT_SG":"state_dat"}"""
        )
        val prompt = buildPrompt(card, note, emptyMap())

        assertNull(classifyAnswer(prompt, "gibberish"))
        assertNull(classifyAnswer(prompt, "state_gen")) // correct answer: no confusion to classify
    }

    @Test
    fun returnsNullForCardTypesWithNoClassifier() {
        val card = Card(noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB)
        val note = Note(id = 1, russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun")
        val prompt = buildPrompt(card, note, emptyMap())

        assertNull(classifyAnswer(prompt, "anything"))
    }

    // --- TRANSFORM (register-ladder pairs, Phase G6 §13.6) --------------------
    // AnswerDiagnosis already handles CardType.TRANSFORM generically by string
    // comparison against prompt.expectedAnswer — these confirm that grading
    // shape holds for a register-ladder (neutral->formal) expected answer too,
    // exactly as it already does for a negation transform's expected answer.

    @Test
    fun transformExactRegisterLadderMatchHasNoConfusionToClassify() {
        val card = Card(noteId = 1, cardType = CardType.TRANSFORM, queue = Queue.VOCAB)
        val note = Note(id = 1, russian = "сказать", lemma = "сказать", translation = "to say", partOfSpeech = "verb")
        val prompt = buildPrompt(card, note, emptyMap()).copy(expectedAnswer = "Он сообщил о готовности решения.")

        assertNull(classifyAnswer(prompt, "Он сообщил о готовности решения."))
    }

    @Test
    fun transformRegisterLadderWordOrderMissIsClassifiedAsWordOrder() {
        val card = Card(noteId = 1, cardType = CardType.TRANSFORM, queue = Queue.VOCAB)
        val note = Note(id = 1, russian = "сказать", lemma = "сказать", translation = "to say", partOfSpeech = "verb")
        val prompt = buildPrompt(card, note, emptyMap()).copy(expectedAnswer = "решения готовности о сообщил Он")

        val diagnosis = classifyAnswer(prompt, "Он сообщил о готовности решения")

        assertEquals(ErrorCategory.WORD_ORDER, diagnosis?.category)
    }

    @Test
    fun transformRegisterLadderWrongAnswerIsClassifiedAsAMismatch() {
        val card = Card(noteId = 1, cardType = CardType.TRANSFORM, queue = Queue.VOCAB)
        val note = Note(id = 1, russian = "сказать", lemma = "сказать", translation = "to say", partOfSpeech = "verb")
        val prompt = buildPrompt(card, note, emptyMap()).copy(expectedAnswer = "Он сообщил о готовности решения.")

        val diagnosis = classifyAnswer(prompt, "Она сказала совсем другое.")

        assertEquals(normalizeRussian("Он сообщил о готовности решения."), diagnosis?.expectedKey)
        assertEquals(normalizeRussian("Она сказала совсем другое."), diagnosis?.producedKey)
    }

    @Test
    fun repairCardTypeCoversEveryCategoryWithAnUnambiguousDrillOrDeliberatelyNull() {
        assertEquals(CardType.CASE_FILL, repairCardTypeFor(ErrorCategory.CASE_ROLE))
        assertEquals(CardType.CASE_FILL, repairCardTypeFor(ErrorCategory.CASE_ENDING))
        assertEquals(CardType.CASE_FILL, repairCardTypeFor(ErrorCategory.PREPOSITION_CASE))
        assertEquals(CardType.GENDER_ID, repairCardTypeFor(ErrorCategory.GENDER))
        assertEquals(CardType.ADJ_AGREE, repairCardTypeFor(ErrorCategory.NUMBER))
        assertEquals(CardType.ADJ_AGREE, repairCardTypeFor(ErrorCategory.AGREEMENT))
        assertEquals(CardType.VERB_FORM, repairCardTypeFor(ErrorCategory.VERB_CONJUGATION))
        assertEquals(CardType.VERB_FORM, repairCardTypeFor(ErrorCategory.TENSE))
        assertEquals(CardType.VERB_FORM, repairCardTypeFor(ErrorCategory.REFLEXIVE))
        assertEquals(CardType.ASPECT_SELECT, repairCardTypeFor(ErrorCategory.ASPECT_CHOICE))
        assertEquals(CardType.ASPECT_SELECT, repairCardTypeFor(ErrorCategory.MOTION_CONSTRUAL))
        assertEquals(CardType.AUDIO_TO_RU, repairCardTypeFor(ErrorCategory.LISTENING_DISCRIMINATION))
        assertEquals(CardType.MEANING_TO_RU, repairCardTypeFor(ErrorCategory.LEXICAL_CONFUSION))
        assertNull(repairCardTypeFor(ErrorCategory.WORD_ORDER))
        assertNull(repairCardTypeFor(ErrorCategory.ORTHOGRAPHY))
        assertNull(repairCardTypeFor(ErrorCategory.REGISTER))
    }
}
