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

        assertEquals(Diagnosis("GEN_SG", "DAT_SG"), diagnosis)
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
}
