package com.sibirskyspeak.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct coverage for CardFactory now that it's a standalone pure unit — no DB,
 * fixture, or dispatcher needed, unlike the indirect coverage through
 * LearningRepositoryTest's importJsonLines-based fixtures.
 */
class CardFactoryTest {
    @Test
    fun lessonNoteProducesOnlyALessonCardPlusItsConceptDrills() {
        val note = Note(id = 1, russian = "", translation = "", partOfSpeech = "lesson", lemma = "GENDER_LESSON", conceptId = "GENDER")
        val cards = CardFactory.cardsFor(note)
        assertTrue(cards.any { it.cardType == CardType.LESSON && it.queue == Queue.GRAMMAR })
        assertTrue(cards.all { it.cardType == CardType.LESSON || it.cardType == CardType.CONCEPT_DRILL })
    }

    @Test
    fun plainVocabNoteGetsRecognitionAndProductionCards() {
        val note = Note(id = 1, russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun")
        val cards = CardFactory.cardsFor(note)
        assertTrue(cards.any { it.cardType == CardType.RU_TO_MEANING })
        assertTrue(cards.any { it.cardType == CardType.MEANING_TO_RU })
    }

    @Test
    fun ambiguousFunctionWordSkipsProductionButKeepsRecognition() {
        val note = Note(
            id = 1, russian = "то", lemma = "то", translation = "that, then, so",
            partOfSpeech = "conjunction"
        )
        assertTrue(CardFactory.isAmbiguousFunctionNote(note))
        val cards = CardFactory.cardsFor(note)
        assertTrue(cards.any { it.cardType == CardType.RU_TO_MEANING })
        assertFalse(cards.any { it.cardType == CardType.MEANING_TO_RU })
    }

    @Test
    fun readingMatrixNoteSkipsAllMorphologyDrillsDespiteHavingATable() {
        val note = Note(
            id = 1, russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun",
            gender = "M", tier = 1, tags = "general curated matrix",
            declensionJson = """{"NOM_SG":"дом","GEN_SG":"дома"}""",
            exampleSentence = "У меня нет дома.", exampleTranslation = "I don't have a house."
        )
        val cards = CardFactory.cardsFor(note)
        assertFalse(cards.any { it.cardType in setOf(CardType.CASE_FILL, CardType.GENDER_ID, CardType.ADJ_AGREE, CardType.VERB_FORM, CardType.ASPECT_SELECT) })
        // Vocab/comprehension cards still generate — the matrix layer is vocab-only, not empty.
        assertTrue(cards.any { it.cardType == CardType.RU_TO_MEANING })
    }

    @Test
    fun caseFillOnlyGeneratesForAttestedFormsNeverNominative() {
        val note = Note(
            id = 1, russian = "стол", lemma = "стол", translation = "table", partOfSpeech = "noun",
            gender = "M", tier = 0, cefrLevel = "A2",
            declensionJson = """{"NOM_SG":"стол","GEN_SG":"стола","DAT_SG":"столу"}""",
            exampleSentence = "Я купил стола.", exampleTranslation = "I bought the table."
        )
        val cards = CardFactory.cardsFor(note)
        val caseCards = cards.filter { it.cardType == CardType.CASE_FILL }
        // GEN_SG ("стола") is attested in the example; DAT_SG ("столу") is not.
        assertTrue(caseCards.any { it.gramCase == "GEN" })
        assertFalse(caseCards.any { it.gramCase == "DAT" })
        assertFalse(caseCards.any { it.gramCase == "NOM" })
    }

    @Test
    fun caseFillPacingRestrictsDepthByTheNotesOwnCefrLevel() {
        // Same attested forms, three different declared levels: A1 should see only
        // the accusative singular "core"; A2 unlocks the rest of the singular
        // paradigm; B1 finally unlocks plural declension.
        fun noteAt(level: String) = Note(
            id = 1, russian = "кни́га", lemma = "книга", translation = "book", partOfSpeech = "noun",
            gender = "F", tier = 0, cefrLevel = level,
            declensionJson = """{"NOM_SG":"книга","ACC_SG":"книгу","GEN_SG":"книги","NOM_PL":"книги","GEN_PL":"книг"}""",
            exampleSentence = "Я купил книгу, прочитал книги, а теперь у меня нет книг.",
            exampleTranslation = "I bought a book, read some books, and now I have no books."
        )
        val a1Cases = CardFactory.cardsFor(noteAt("A1")).filter { it.cardType == CardType.CASE_FILL }
        assertTrue(a1Cases.any { it.gramCase == "ACC" && it.gramNumber == "SG" })
        assertFalse(a1Cases.any { it.gramCase == "GEN" })

        val a2Cases = CardFactory.cardsFor(noteAt("A2")).filter { it.cardType == CardType.CASE_FILL }
        assertTrue(a2Cases.any { it.gramCase == "GEN" && it.gramNumber == "SG" })
        assertFalse(a2Cases.any { it.gramNumber == "PL" })

        val b1Cases = CardFactory.cardsFor(noteAt("B1")).filter { it.cardType == CardType.CASE_FILL }
        assertTrue(b1Cases.any { it.gramCase == "GEN" && it.gramNumber == "PL" })
    }

    @Test
    fun genderCardOnlyForNounsWithARecognizedGender() {
        val noun = Note(id = 1, russian = "стол", lemma = "стол", translation = "table", partOfSpeech = "noun", gender = "M", tier = 0)
        val adjective = Note(id = 2, russian = "новый", lemma = "новый", translation = "new", partOfSpeech = "adjective", tier = 0)
        assertTrue(CardFactory.cardsFor(noun).any { it.cardType == CardType.GENDER_ID })
        assertFalse(CardFactory.cardsFor(adjective).any { it.cardType == CardType.GENDER_ID })
    }

    @Test
    fun aspectSelectRequiresAVerifiedAktionsartAndPartner() {
        val incomplete = Note(
            id = 1, russian = "делать", lemma = "делать", translation = "to do", partOfSpeech = "verb",
            aspect = "IPF", tier = 0
            // no aktionsart, no aspectPartner — must not produce a half-formed drill.
        )
        val complete = incomplete.copy(id = 2, aktionsart = "activity", aspectPartner = 99L)
        assertFalse(CardFactory.cardsFor(incomplete).any { it.cardType == CardType.ASPECT_SELECT })
        assertTrue(CardFactory.cardsFor(complete).any { it.cardType == CardType.ASPECT_SELECT })
    }

    @Test
    fun stressMarkIsRetiredEvenForExplicitlyMarkedWords() {
        val marked = Note(id = 1, russian = "дома́", lemma = "дом", translation = "houses", partOfSpeech = "noun", tier = 0)
        val unmarked = Note(id = 2, russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun", tier = 0)
        assertFalse(CardFactory.cardsFor(marked).any { it.cardType == CardType.STRESS_MARK })
        assertFalse(CardFactory.cardsFor(unmarked).any { it.cardType == CardType.STRESS_MARK })
    }

    @Test
    fun hasReadableExampleRequiresARealMultiWordGlossDistinctFromTheDictionaryTranslation() {
        val noExample = Note(id = 1, russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun")
        val glossEqualsTranslation = noExample.copy(exampleSentence = "Дом.", exampleTranslation = "house")
        val realExample = noExample.copy(exampleSentence = "Это мой дом.", exampleTranslation = "This is my house.")
        assertFalse(CardFactory.hasReadableExample(noExample))
        assertFalse(CardFactory.hasReadableExample(glossEqualsTranslation))
        assertTrue(CardFactory.hasReadableExample(realExample))
    }
}
