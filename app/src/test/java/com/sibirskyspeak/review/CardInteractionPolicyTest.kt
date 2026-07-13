package com.sibirskyspeak.review

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Queue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardInteractionPolicyTest {
    private fun prompt(mode: AnswerMode) = ReviewPrompt(
        card = Card(noteId = 1, cardType = CardType.CONCEPT_APPLY, queue = Queue.GRAMMAR),
        note = com.sibirskyspeak.data.Note(id = 1, russian = "слово", lemma = "слово", translation = "word", partOfSpeech = "noun"),
        prompt = "cue",
        expectedAnswer = "ответ",
        answerMode = mode,
        intervalPreview = emptyMap()
    )

    @Test
    fun objectiveModesCommitMisses() {
        assertTrue(shouldAutoCommitMiss(prompt(AnswerMode.CHOICE)))
        assertTrue(shouldAutoCommitMiss(prompt(AnswerMode.RUSSIAN_TYPED)))
        assertTrue(shouldAutoCommitMiss(prompt(AnswerMode.RUSSIAN_STRESS_TYPED)))
        assertTrue(shouldAutoCommitMiss(prompt(AnswerMode.AUDIO_ONLY)))
    }

    @Test
    fun recognitionAndSpeechPracticeRemainSelfRated() {
        assertFalse(shouldAutoCommitMiss(prompt(AnswerMode.ENGLISH)))
        assertFalse(shouldAutoCommitMiss(prompt(AnswerMode.SPEAK)))
        assertFalse(shouldAutoCommitMiss(prompt(AnswerMode.LESSON)))
    }

    @Test
    fun sentencePunctuationIsPreservedButCompactAlternativesStillWork() {
        assertEquals("Он пришёл, и мы начали.", com.sibirskyspeak.tileAnswerText("Он пришёл, и мы начали."))
        assertEquals("Да, конечно.", com.sibirskyspeak.tileAnswerText("Да, коне\u0301чно."))
        assertEquals("ответ", com.sibirskyspeak.tileAnswerText("ответ,ответ"))
        assertEquals(listOf("он", "пришёл", "и", "мы", "начали"), com.sibirskyspeak.sentenceTileWords("Он пришёл, и мы начали."))
    }
}
