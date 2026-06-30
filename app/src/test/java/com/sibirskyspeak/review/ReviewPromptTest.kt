package com.sibirskyspeak.review

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromptTest {
    private fun simplePrompt(id: Long, type: CardType = CardType.RU_TO_MEANING): ReviewPrompt {
        val note = Note(id = id, russian = "word$id", lemma = "word$id", translation = "meaning$id", partOfSpeech = "word")
        return buildPrompt(Card(id = id, noteId = id, cardType = type, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 1), note, emptyMap())
    }

    @Test
    fun tidyPunctuationSpacingFixesTokenJoinedSentences() {
        assertEquals("Давай поду́маем, что мо́жет произойти́.",
            "Давай поду́маем , что мо́жет произойти́ .".tidyPunctuationSpacing())
        assertEquals("«Эхо»: да и нет!", "« Эхо » :  да и нет !".tidyPunctuationSpacing())
        // Clean text is unchanged.
        assertEquals("Кни́га на столе́.", "Кни́га на столе́.".tidyPunctuationSpacing())
    }

    @Test
    fun recognitionPromptRendersTidiedExampleSentence() {
        val note = Note(
            id = 1, russian = "что", lemma = "что", translation = "what, that", partOfSpeech = "pronoun",
            exampleSentence = "Поду́май , что бу́дет .", exampleTranslation = "Think about what will be."
        )
        // reps >= 5 embeds the example sentence into the prompt; it must be tidied.
        val prompt = buildPrompt(
            Card(id = 1, noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 5),
            note, emptyMap()
        )
        assertFalse("no space-before-comma artifact", prompt.prompt.contains(" ,"))
        assertTrue(prompt.prompt.contains("Поду́май, что бу́дет."))
    }

    @Test
    fun failedCardReturnsAfterSixInterveningCardsAndAtTheEnd() {
        val failed = simplePrompt(1, CardType.MEANING_TO_RU)
        val repair = simplePrompt(99, CardType.RU_TO_MEANING)
        val queue = listOf(failed) + (2L..10L).map { simplePrompt(it) }

        val updated = recoveryQueueAfter(queue, failed, Rating.AGAIN, repair)

        assertEquals(99L, updated[6].card.id)
        assertTrue(updated[6].queueReason.orEmpty().startsWith("Repair:"))
        assertEquals(1L, updated.last().card.id)
        assertTrue(updated.last().queueReason.orEmpty().contains("Final recovery"))
    }

    @Test
    fun successfulCardLeavesFrozenQueueWithoutReorderingIt() {
        val current = simplePrompt(1)
        val rest = (2L..5L).map { simplePrompt(it) }
        assertEquals(rest, recoveryQueueAfter(listOf(current) + rest, current, Rating.GOOD))
    }

    @Test
    fun adaptiveLoadChangesGraduallyFromEvidence() {
        assertEquals(-2, adaptiveNewCardDelta(true, 0, 25, 0.95, 100, 0.90, 20))
        assertEquals(-2, adaptiveNewCardDelta(false, 10, 25, 0.82, 100, 0.90, 20))
        assertEquals(1, adaptiveNewCardDelta(false, 5, 25, 0.95, 100, 0.90, 20))
        assertEquals(0, adaptiveNewCardDelta(false, 5, 25, 0.95, 5, 0.90, 20))
    }

    @Test
    fun matureRecognitionMovesFromIsolatedWordIntoRussianContext() {
        val note = Note(
            id = 1, russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun",
            exampleSentence = "Это мой дом.", exampleTranslation = "This is my house."
        )
        val young = buildPrompt(Card(noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB), note, emptyMap())
        val mature = buildPrompt(Card(noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, reps = 5), note, emptyMap())

        assertEquals(AnswerMode.LESSON, young.answerMode)
        assertTrue(young.lesson?.body.orEmpty().contains("house"))
        assertTrue(mature.prompt.contains("Это мой дом."))
        assertTrue(mature.prompt.contains("mean here"))
    }

    @Test
    fun recognitionUsesTheMeaningFromItsExampleInsteadOfAnUnrelatedDictionarySense() {
        val note = Note(
            id = 1, russian = "а", lemma = "а", translation = "and (as contrast)", partOfSpeech = "conjunction",
            exampleSentence = "А да, у меня завтра что-то есть.",
            exampleTranslation = "Oh right, I have something tomorrow."
        )
        val card = Card(noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 1)

        assertEquals("oh / oh right", buildPrompt(card, note, emptyMap()).expectedAnswer)
    }

    @Test
    fun recognitionNeverRequiresAnOversizedDictionarySenseList() {
        val note = Note(
            id = 1, russian = "слово", lemma = "слово",
            translation = "sense one, sense two, sense three, sense four, sense five",
            partOfSpeech = "noun"
        )
        val card = Card(noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 1)

        assertEquals("sense one / sense two", buildPrompt(card, note, emptyMap()).expectedAnswer)
    }

    @Test
    fun productionUsesOneConciseMeaningInsteadOfTheDictionaryEntry() {
        val note = Note(
            id = 1,
            russian = "быть",
            lemma = "быть",
            translation = "to be, (present tense) есть - there is, has been",
            partOfSpeech = "verb"
        )
        val card = Card(noteId = 1, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 1)

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals("to be", prompt.prompt)
        assertEquals("быть", prompt.expectedAnswer)
    }

    @Test
    fun aNewWordIsTaughtThenInsertedLaterForFirstRecall() {
        val note = Note(id = 1, russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun")
        val intro = buildPrompt(Card(id = 1, noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB), note, emptyMap())
        val rest = (2L..9L).map { simplePrompt(it) }

        val queue = recoveryQueueAfter(listOf(intro) + rest, intro, Rating.GOOD)

        assertEquals(AnswerMode.LESSON, intro.answerMode)
        assertTrue(intro.isNewVocabularyIntroduction())
        assertEquals(1L, queue[6].card.id)
        assertTrue(queue[6].queueReason.orEmpty().contains("First recall"))
    }
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
        // Object-free carrier so it reads naturally for any verb (no "этот вопрос").
        assertTrue(prompt.prompt.contains("долго"))
        assertTrue(prompt.explanation!!.contains("imperfective"))
    }

    @Test
    fun aspectDiagnosticExplainsWhyWrongAspectDoesNotFitCue() {
        val note = verbNote(1, "discuss_ipf", "IPF", "activity", partnerId = 2)
        val partner = verbNote(2, "discuss_pf", "PF", "accomplishment", partnerId = 1)
        val card = Card(noteId = 1, cardType = CardType.ASPECT_SELECT, queue = Queue.GRAMMAR, gramContextCue = "PROCESS")

        val prompt = buildPrompt(card, note, emptyMap(), partner)
        val feedback = diagnosticFeedbackFor(prompt, "discuss_pf")

        assertTrue(feedback!!.contains("Cue: ongoing process."))
        assertTrue(feedback.contains("does not fit this context"))
        assertTrue(feedback.contains("imperfective fits"))
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
    fun matureCaseCardSwitchesToCaseSelectionMode() {
        val card = Card(
            noteId = 1,
            cardType = CardType.CASE_FILL,
            queue = Queue.GRAMMAR,
            gramCase = "GEN",
            gramGender = "PL",
            gramNumber = "PL",
            reps = 3
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
        // Once seen a couple of times, the prompt stops naming the case — the learner
        // must infer it from the carrier (case SELECTION, the real A1.5 skill).
        assertTrue(!prompt.prompt.contains("genitive plural"))
        assertTrue(prompt.prompt.contains("____"))
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
    fun genderDiagnosticExplainsExpectedGenderPattern() {
        val card = Card(
            noteId = 1,
            cardType = CardType.GENDER_ID,
            queue = Queue.GRAMMAR,
            gramGender = "F"
        )
        val note = Note(
            id = 1,
            russian = "\u043a\u043d\u0438\u0433\u0430",
            lemma = "\u043a\u043d\u0438\u0433\u0430",
            translation = "book",
            partOfSpeech = "noun",
            gender = "F"
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals(
            "This noun is feminine. Feminine nouns often end in -\u0430 or -\u044f.",
            diagnosticFeedbackFor(prompt, "masculine")
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
    fun verbFormDiagnosticNamesAnotherStoredForm() {
        val card = Card(
            noteId = 1,
            cardType = CardType.VERB_FORM,
            queue = Queue.GRAMMAR,
            gramContextCue = "PRES_1SG"
        )
        val note = Note(
            id = 1,
            russian = "\u0447\u0438\u0442\u0430\u0442\u044c",
            lemma = "\u0447\u0438\u0442\u0430\u0442\u044c",
            translation = "to read",
            partOfSpeech = "verb",
            declensionJson = """{"verbForms":{"PRES_1SG":"\u0447\u0438\u0442\u0430\u044e","PRES_3SG":"\u0447\u0438\u0442\u0430\u0435\u0442"}}"""
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals(
            "You made present 3rd person singular; this prompt asks for present 1st person singular.",
            diagnosticFeedbackFor(prompt, "\u0447\u0438\u0442\u0430\u0435\u0442")
        )
    }

    @Test
    fun verbFormDiagnosticNamesInfinitiveInsteadOfRequestedForm() {
        val card = Card(
            noteId = 1,
            cardType = CardType.VERB_FORM,
            queue = Queue.GRAMMAR,
            gramContextCue = "PRES_1SG"
        )
        val note = Note(
            id = 1,
            russian = "\u0447\u0438\u0442\u0430\u0442\u044c",
            lemma = "\u0447\u0438\u0442\u0430\u0442\u044c",
            translation = "to read",
            partOfSpeech = "verb",
            declensionJson = """{"verbForms":{"PRES_1SG":"\u0447\u0438\u0442\u0430\u044e"}}"""
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals(
            "You used the infinitive/dictionary form; this prompt asks for present 1st person singular.",
            diagnosticFeedbackFor(prompt, "\u0447\u0438\u0442\u0430\u0442\u044c")
        )
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
    fun clozeBlanksOnlyOneRecallSpotWhenTargetRepeats() {
        val card = Card(noteId = 1, cardType = CardType.CLOZE, queue = Queue.VOCAB)
        val note = Note(
            id = 1,
            russian = "dom",
            lemma = "dom",
            translation = "house",
            partOfSpeech = "noun",
            exampleSentence = "dom and dom.",
            exampleTranslation = "A house and a house."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals("____ and dom.", prompt.prompt)
        assertEquals(1, Regex("____").findAll(prompt.prompt).count())
    }

    @Test
    fun clozeDoesNotBlankInsideLargerWords() {
        val card = Card(noteId = 1, cardType = CardType.CLOZE, queue = Queue.VOCAB)
        val note = Note(
            id = 1,
            russian = "uchi",
            lemma = "uchi",
            translation = "study",
            partOfSpeech = "verb",
            exampleSentence = "3uchit should stay intact.",
            exampleTranslation = "It should stay intact."
        )

        val prompt = buildPrompt(card, note, emptyMap())

        assertEquals("study", prompt.prompt)
        assertFalse(prompt.prompt.contains("3____t"))
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

        // Stress is now tap-the-vowel (CHOICE), not typing a combining accent.
        assertEquals(AnswerMode.CHOICE, prompt.answerMode)
        assertEquals("\u043c\u043e\u043b\u043e\u043a\u043e\u0301", prompt.expectedAnswer)
        assertTrue(prompt.prompt.contains("\u043c\u043e\u043b\u043e\u043a\u043e"))
        assertTrue(prompt.choices.contains("\u043c\u043e\u043b\u043e\u043a\u043e\u0301"))
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
        val vocabCard = Card(noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 1)

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
    fun stressCardIsTapTheVowelChoiceNotTyping() {
        val note = Note(
            id = 1,
            russian = "молоко́",
            lemma = "молоко",
            translation = "milk",
            partOfSpeech = "noun"
        )
        val card = Card(noteId = 1, cardType = CardType.STRESS_MARK, queue = Queue.VOCAB)

        val prompt = buildPrompt(card, note, emptyMap())

        // No typing: the learner taps the correctly-stressed spelling.
        assertEquals(AnswerMode.CHOICE, prompt.answerMode)
        assertEquals("молоко́", prompt.expectedAnswer)
        assertTrue("correct stressed form must be a choice", prompt.choices.contains("молоко́"))
        assertTrue("should offer multiple stress placements", prompt.choices.size >= 2)
        // Every choice is the same letters, differing only by where the accent sits.
        assertTrue(prompt.choices.all { it.replace("́", "") == "молоко" })
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
            buildPrompt(Card(noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 1), note, emptyMap()),
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
