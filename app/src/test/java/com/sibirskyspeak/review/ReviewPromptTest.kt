package com.sibirskyspeak.review

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Queue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromptTest {
    private fun verbNote(id: Long, lemma: String, aspect: String, aktionsart: String, partnerId: Long? = null) = Note(
        id = id,
        russian = lemma,
        lemma = lemma,
        translation = "to discuss",
        partOfSpeech = "verb",
        aspect = aspect,
        aktionsart = aktionsart,
        aspectPartner = partnerId,
        exampleSentence = "Стороны важно обсудить этот вопрос вовремя."
    )

    @Test
    fun aspectSelectNoCueUsesFinitePastForm() {
        val note = verbNote(1, "обсуждать", "IPF", "activity", partnerId = 2)
        val partner = verbNote(2, "обсудить", "PF", "accomplishment", partnerId = 1)
        val card = Card(noteId = 1, cardType = CardType.ASPECT_SELECT, queue = Queue.GRAMMAR, gramContextCue = "NO_CUE")

        val prompt = buildPrompt(card, note, emptyMap(), partner)

        assertEquals("обсуждал", prompt.expectedAnswer)
        assertTrue(prompt.choices.contains("обсуждал"))
        assertTrue(prompt.choices.contains("обсудил"))
        assertTrue(prompt.prompt.contains("NO_CUE"))
        assertTrue(prompt.explanation!!.contains("Aktionsart"))
    }

    @Test
    fun aspectSelectHasCuePromptMentionsBoundaryCue() {
        val note = verbNote(1, "обсуждать", "IPF", "activity", partnerId = 2)
        val partner = verbNote(2, "обсудить", "PF", "accomplishment", partnerId = 1)
        val card = Card(noteId = 1, cardType = CardType.ASPECT_SELECT, queue = Queue.GRAMMAR, gramContextCue = "HAS_CUE")

        val prompt = buildPrompt(card, note, emptyMap(), partner)

        assertTrue(prompt.prompt.contains("HAS_CUE"))
        assertTrue(prompt.explanation!!.contains("boundary"))
    }

    @Test
    fun caseFillUsesCardGrammarTagsForExpectedInflection() {
        val card = Card(
            noteId = 1,
            cardType = CardType.CASE_FILL,
            queue = Queue.GRAMMAR,
            gramCase = "GEN",
            gramGender = "PL",
            gramNumber = "PL"
        )
        val note = Note(
            id = 1,
            russian = "войска́",
            lemma = "войска",
            translation = "troops",
            partOfSpeech = "noun",
            gender = "PL",
            declensionJson = """{"NOM_PL":"войска","GEN_PL":"войск"}""",
            exampleSentence = "У границы стоят войска́."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals("войск", prompt.expectedAnswer)
        assertTrue(prompt.prompt.contains("GEN PL"))
        assertTrue(prompt.prompt.contains("PL PL"))
    }
}
