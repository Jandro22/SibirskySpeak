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
        exampleSentence = "They discussed the question."
    )

    @Test
    fun aspectSelectNoCueUsesDefaultAspectForm() {
        val note = verbNote(1, "discuss_ipf", "IPF", "activity", partnerId = 2)
        val partner = verbNote(2, "discuss_pf", "PF", "accomplishment", partnerId = 1)
        val card = Card(noteId = 1, cardType = CardType.ASPECT_SELECT, queue = Queue.GRAMMAR, gramContextCue = "NO_CUE")

        val prompt = buildPrompt(card, note, emptyMap(), partner)

        assertEquals("discuss_ipf", prompt.expectedAnswer)
        assertTrue(prompt.choices.contains("discuss_ipf"))
        assertTrue(prompt.choices.contains("discuss_pf"))
        assertTrue(prompt.prompt.contains("no explicit completion marker"))
        assertTrue(prompt.explanation!!.contains("action type"))
    }

    @Test
    fun aspectSelectHasCueUsesPerfectivePartnerWhenCurrentNoteIsImperfective() {
        val note = verbNote(1, "discuss_ipf", "IPF", "activity", partnerId = 2)
        val partner = verbNote(2, "discuss_pf", "PF", "accomplishment", partnerId = 1)
        val card = Card(noteId = 1, cardType = CardType.ASPECT_SELECT, queue = Queue.GRAMMAR, gramContextCue = "HAS_CUE")

        val prompt = buildPrompt(card, note, emptyMap(), partner)

        assertEquals("discuss_pf", prompt.expectedAnswer)
        assertTrue(prompt.prompt.contains("completion or boundary marker"))
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
            russian = "troops",
            lemma = "troops",
            translation = "troops",
            partOfSpeech = "noun",
            gender = "PL",
            declensionJson = """{"NOM_PL":"troops","GEN_PL":"troops_gen"}""",
            exampleSentence = "The position of troops_gen matters."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals("troops_gen", prompt.expectedAnswer)
        assertTrue(prompt.prompt.contains("genitive plural"))
        assertTrue(prompt.prompt.contains("____"))
    }

    @Test
    fun caseFillDoesNotSurfaceNominativePromptForExistingNomCard() {
        val card = Card(
            noteId = 1,
            cardType = CardType.CASE_FILL,
            queue = Queue.GRAMMAR,
            gramCase = "NOM",
            gramGender = "M",
            gramNumber = "SG"
        )
        val note = Note(
            id = 1,
            russian = "state",
            lemma = "state",
            translation = "state",
            partOfSpeech = "noun",
            gender = "M",
            declensionJson = """{"NOM_SG":"state","GEN_SG":"state_gen"}""",
            exampleSentence = "The role of state_gen is large."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals("state_gen", prompt.expectedAnswer)
        assertTrue(prompt.prompt.contains("genitive singular"))
    }

    @Test
    fun verbFormUsesCardGrammarCueForExpectedConjugation() {
        val card = Card(
            noteId = 1,
            cardType = CardType.VERB_FORM,
            queue = Queue.GRAMMAR,
            gramContextCue = "PAST_F"
        )
        val note = Note(
            id = 1,
            russian = "\u043f\u0438\u0441\u0430\u0442\u044c",
            lemma = "\u043f\u0438\u0441\u0430\u0442\u044c",
            translation = "to write",
            partOfSpeech = "verb",
            exampleSentence = "\u041e\u043d\u0430 \u043f\u0438\u0441\u0430\u043b\u0430 \u043f\u0438\u0441\u044c\u043c\u043e."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals("\u043f\u0438\u0441\u0430\u043b\u0430", prompt.expectedAnswer)
        assertTrue(prompt.prompt.contains("past feminine singular"))
        assertTrue(prompt.prompt.contains("___"))
    }

    @Test
    fun clozeBlanksLemmaWhenDisplayFormDiffersFromSentenceText() {
        val card = Card(noteId = 1, cardType = CardType.CLOZE, queue = Queue.VOCAB)
        val note = Note(
            id = 1,
            russian = "term_stressed",
            lemma = "term",
            translation = "term",
            partOfSpeech = "noun",
            exampleSentence = "This term matters."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertTrue(prompt.prompt.contains("____"))
    }

    @Test
    fun clozeExpectsTheActualBlankedSentenceForm() {
        val card = Card(noteId = 1, cardType = CardType.CLOZE, queue = Queue.VOCAB)
        val note = Note(
            id = 1,
            russian = "state",
            lemma = "state",
            translation = "state",
            partOfSpeech = "noun",
            declensionJson = """{"NOM_SG":"state","GEN_SG":"state_gen"}""",
            exampleSentence = "The role of state_gen is large."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertTrue(prompt.prompt.contains("____"))
        assertEquals("state_gen", prompt.expectedAnswer)
    }

    @Test
    fun allPracticeCardTypesHaveCoherentPromptAnswerModes() {
        val note = Note(
            id = 1,
            russian = "state",
            lemma = "state",
            translation = "state",
            partOfSpeech = "noun",
            gender = "M",
            declensionJson = """{"NOM_SG":"state","GEN_SG":"state_gen"}""",
            exampleSentence = "The role of state_gen is large."
        )

        val prompts = listOf(
            buildPrompt(Card(noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB), note, emptyMap()),
            buildPrompt(Card(noteId = 1, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB), note, emptyMap()),
            buildPrompt(Card(noteId = 1, cardType = CardType.CLOZE, queue = Queue.VOCAB), note, emptyMap()),
            buildPrompt(Card(noteId = 1, cardType = CardType.AUDIO_TO_RU, queue = Queue.VOCAB), note, emptyMap()),
            buildPrompt(Card(noteId = 1, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramCase = "GEN", gramNumber = "SG"), note, emptyMap()),
            buildPrompt(Card(noteId = 1, cardType = CardType.VERB_FORM, queue = Queue.GRAMMAR, gramContextCue = "PAST_M"), note.copy(partOfSpeech = "verb"), emptyMap())
        )

        prompts.forEach { prompt ->
            assertTrue("Prompt should not expose empty expected answer", prompt.expectedAnswer.isNotBlank())
            if (prompt.answerMode != AnswerMode.AUDIO_ONLY) {
                assertTrue("Prompt text should be usable for ${prompt.card.cardType}", prompt.prompt.isNotBlank())
            }
        }
        assertEquals(AnswerMode.ENGLISH, prompts[0].answerMode)
        assertEquals(AnswerMode.RUSSIAN_TYPED, prompts[1].answerMode)
        assertEquals(AnswerMode.RUSSIAN_TYPED, prompts[2].answerMode)
        assertEquals(AnswerMode.AUDIO_ONLY, prompts[3].answerMode)
        assertEquals(AnswerMode.RUSSIAN_TYPED, prompts[4].answerMode)
        assertEquals(AnswerMode.RUSSIAN_TYPED, prompts[5].answerMode)
    }
}
