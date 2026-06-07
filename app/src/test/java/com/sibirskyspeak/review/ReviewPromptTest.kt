package com.sibirskyspeak.review

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Queue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun aspectSelectProcessCueUsesImperfectiveContext() {
        val note = verbNote(1, "discuss_ipf", "IPF", "activity", partnerId = 2)
        val partner = verbNote(2, "discuss_pf", "PF", "accomplishment", partnerId = 1)
        val card = Card(noteId = 1, cardType = CardType.ASPECT_SELECT, queue = Queue.GRAMMAR, gramContextCue = "PROCESS")

        val prompt = buildPrompt(card, note, emptyMap(), partner)

        assertEquals("discuss_ipf", prompt.expectedAnswer)
        assertTrue(prompt.prompt.contains("ongoing process"))
        assertTrue(prompt.prompt.contains("долго"))
        assertTrue(prompt.explanation!!.contains("imperfective"))
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
    fun caseFillDiagnosticNamesWrongCaseForm() {
        val card = Card(
            noteId = 1,
            cardType = CardType.CASE_FILL,
            queue = Queue.GRAMMAR,
            gramCase = "GEN",
            gramNumber = "SG"
        )
        val note = Note(
            id = 1,
            russian = "state",
            lemma = "state",
            translation = "state",
            partOfSpeech = "noun",
            gender = "M",
            declensionJson = """{"NOM_SG":"state","GEN_SG":"state_gen","DAT_SG":"state_dat"}""",
            exampleSentence = "The role of state_gen is large."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals(
            "You made dative singular; this prompt asks for genitive singular.",
            diagnosticFeedbackFor(prompt, "state_dat")
        )
    }

    @Test
    fun caseFillDiagnosticNamesDictionaryForm() {
        val card = Card(
            noteId = 1,
            cardType = CardType.CASE_FILL,
            queue = Queue.GRAMMAR,
            gramCase = "GEN",
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

        assertEquals(
            "You left it in the dictionary/nominative form; this prompt asks for genitive singular.",
            diagnosticFeedbackFor(prompt, "state")
        )
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
    fun perfectiveVerbFormCueIsLabeledAsFuture() {
        val card = Card(
            noteId = 1,
            cardType = CardType.VERB_FORM,
            queue = Queue.GRAMMAR,
            gramContextCue = "PRES_1SG"
        )
        val note = Note(
            id = 1,
            russian = "\u043d\u0430\u043f\u0438\u0441\u0430\u0442\u044c",
            lemma = "\u043d\u0430\u043f\u0438\u0441\u0430\u0442\u044c",
            translation = "to write (finish)",
            partOfSpeech = "verb",
            aspect = "PF",
            declensionJson = """{"verbForms":{"PRES_1SG":"\u043d\u0430\u043f\u0438\u0448\u0443"}}""",
            exampleSentence = "\u042f \u043d\u0430\u043f\u0438\u0448\u0443 \u043f\u0438\u0441\u044c\u043c\u043e."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals("\u043d\u0430\u043f\u0438\u0448\u0443", prompt.expectedAnswer)
        assertTrue(prompt.prompt.contains("future 1st person singular"))
        assertTrue(prompt.prompt.contains("___"))
    }

    @Test
    fun promptRotatesAvailableExampleContextsByRepetition() {
        val card = Card(
            noteId = 1,
            cardType = CardType.CLOZE,
            queue = Queue.VOCAB,
            reps = 1
        )
        val note = Note(
            id = 1,
            russian = "дом",
            lemma = "дом",
            translation = "house",
            partOfSpeech = "noun",
            exampleSentence = "Это дом.",
            exampleTranslation = "This is a house.",
            exampleSentence2 = "Мой дом здесь.",
            exampleTranslation2 = "My house is here."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals("Мой ____ здесь.", prompt.prompt)
        assertEquals("Мой дом здесь.", prompt.exampleSentence)
        assertEquals("My house is here.", prompt.exampleTranslation)
    }

    @Test
    fun advancedMeaningRecallUsesRussianContextInsteadOfEnglishGloss() {
        val card = Card(noteId = 1, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB)
        val note = Note(
            id = 1,
            russian = "\u0434\u043e\u043c",
            lemma = "\u0434\u043e\u043c",
            translation = "house",
            partOfSpeech = "noun",
            tier = 1,
            exampleSentence = "\u042d\u0442\u043e \u0434\u043e\u043c.",
            exampleTranslation = "This is a house."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertTrue(prompt.prompt.contains("\u041f\u043e \u043a\u043e\u043d\u0442\u0435\u043a\u0441\u0442\u0443"))
        assertTrue(prompt.prompt.contains("____"))
        assertFalse(prompt.prompt.contains("house"))
        assertEquals("\u0434\u043e\u043c", prompt.expectedAnswer)
    }

    @Test
    fun beginnerMeaningRecallKeepsEnglishScaffold() {
        val card = Card(noteId = 1, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB)
        val note = Note(
            id = 1,
            russian = "\u0434\u043e\u043c",
            lemma = "\u0434\u043e\u043c",
            translation = "house",
            partOfSpeech = "noun",
            tier = 0,
            cefrLevel = "A1",
            exampleSentence = "\u042d\u0442\u043e \u0434\u043e\u043c.",
            exampleTranslation = "This is a house."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals("house", prompt.prompt)
        assertEquals("\u0434\u043e\u043c", prompt.expectedAnswer)
    }

    @Test
    fun advancedSentenceBuildUsesRussianWordBankInsteadOfEnglishTranslation() {
        val card = Card(noteId = 1, cardType = CardType.SENTENCE_BUILD, queue = Queue.GRAMMAR)
        val note = Note(
            id = 1,
            russian = "\u043c\u043e\u043b\u043e\u043a\u043e",
            lemma = "\u043c\u043e\u043b\u043e\u043a\u043e",
            translation = "milk",
            partOfSpeech = "noun",
            tier = 1,
            exampleSentence = "\u042f \u043f\u044c\u044e \u043c\u043e\u043b\u043e\u043a\u043e.",
            exampleTranslation = "I drink milk."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertTrue(prompt.prompt.contains("\u0421\u043e\u0431\u0435\u0440\u0438\u0442\u0435"))
        assertTrue(prompt.prompt.contains("\u043c\u043e\u043b\u043e\u043a\u043e / \u043f\u044c\u044e / \u042f"))
        assertFalse(prompt.prompt.contains("I drink milk"))
        assertEquals("\u042f \u043f\u044c\u044e \u043c\u043e\u043b\u043e\u043a\u043e.", prompt.expectedAnswer)
    }

    @Test
    fun stressPromptRequiresMarkedHeadword() {
        val card = Card(noteId = 1, cardType = CardType.STRESS_MARK, queue = Queue.VOCAB)
        val note = Note(
            id = 1,
            russian = "\u043c\u043e\u043b\u043e\u043a\u043e\u0301",
            lemma = "\u043c\u043e\u043b\u043e\u043a\u043e",
            translation = "milk",
            partOfSpeech = "noun"
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals(AnswerMode.RUSSIAN_STRESS_TYPED, prompt.answerMode)
        assertEquals("\u043c\u043e\u043b\u043e\u043a\u043e\u0301", prompt.expectedAnswer)
        assertTrue(prompt.prompt.contains("\u043c\u043e\u043b\u043e\u043a\u043e"))
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
    fun lessonCardBuildsTeachingContentFromConcept() {
        val note = Note(
            id = 1,
            russian = "Noun gender",
            lemma = "lesson_gender",
            translation = "Noun gender",
            partOfSpeech = "lesson",
            conceptId = "GENDER"
        )
        val card = Card(noteId = 1, cardType = CardType.LESSON, queue = Queue.GRAMMAR, gramConcept = "GENDER")

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals(AnswerMode.LESSON, prompt.answerMode)
        assertEquals("Noun gender", prompt.lesson?.title)
        assertTrue(prompt.lesson!!.body.isNotBlank())
        assertTrue(prompt.lesson!!.exampleRu.isNotBlank())
        assertTrue(prompt.lesson!!.exampleEn.isNotBlank())
    }

    @Test
    fun conceptDrillBuildsAuthoredUpperGrammarPractice() {
        val note = Note(
            id = 1,
            russian = "Numbers and nouns",
            lemma = "lesson_numeral_case",
            translation = "Numbers and nouns",
            partOfSpeech = "lesson",
            conceptId = "NUMERAL_CASE"
        )
        val card = Card(
            noteId = 1,
            cardType = CardType.CONCEPT_DRILL,
            queue = Queue.GRAMMAR,
            gramConcept = "NUMERAL_CASE",
            gramContextCue = "NUMERAL_CASE_TWO_BOOKS"
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals(AnswerMode.RUSSIAN_TYPED, prompt.answerMode)
        assertEquals("\u0434\u0432\u0435 \u043a\u043d\u0438\u0433\u0438", prompt.expectedAnswer)
        assertTrue(prompt.prompt.contains("2-4"))
        assertTrue(prompt.teachingHint!!.contains("5+"))
    }

    @Test
    fun conceptDrillCanBeMultipleChoiceForAuthoredForms() {
        val note = Note(
            id = 1,
            russian = "Active participles",
            lemma = "lesson_participle_active",
            translation = "Active participles",
            partOfSpeech = "lesson",
            conceptId = "PARTICIPLE_ACTIVE"
        )
        val card = Card(
            noteId = 1,
            cardType = CardType.CONCEPT_DRILL,
            queue = Queue.GRAMMAR,
            gramConcept = "PARTICIPLE_ACTIVE",
            gramContextCue = "PARTICIPLE_ACTIVE_AUTHORED"
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals(AnswerMode.CHOICE, prompt.answerMode)
        assertEquals("\u0447\u0438\u0442\u0430\u044e\u0449\u0438\u0439", prompt.expectedAnswer)
        assertTrue(prompt.choices.contains("\u0447\u0438\u0442\u0430\u044e\u0449\u0438\u0439"))
        assertTrue(prompt.explanation!!.contains("authored"))
    }

    @Test
    fun grammarDrillsCarryAPromptSideTeachingHint() {
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
        val caseCard = Card(noteId = 1, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramCase = "GEN", gramNumber = "SG", gramConcept = "GEN")
        val vocabCard = Card(noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB)

        assertTrue(buildPrompt(caseCard, note, emptyMap()).teachingHint!!.isNotBlank())
        assertEquals(null, buildPrompt(vocabCard, note, emptyMap()).teachingHint)
    }

    @Test
    fun speakCardAsksLearnerToSayTheRussianAloud() {
        val note = Note(
            id = 1,
            russian = "кни́га",
            lemma = "книга",
            translation = "book",
            partOfSpeech = "noun"
        )
        val card = Card(noteId = 1, cardType = CardType.SPEAK, queue = Queue.VOCAB)

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals(AnswerMode.SPEAK, prompt.answerMode)
        assertEquals("кни́га", prompt.expectedAnswer)
        assertTrue(prompt.prompt.contains("кни́га"))
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
