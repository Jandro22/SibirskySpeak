package com.sibirskyspeak.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerNormalizerTest {
    @Test
    fun normalizesStressCaseWhitespaceAndYo() {
        assertEquals("молоко", normalizeRussian("  МОЛОКО\u0301 "))
        assertEquals("все", normalizeRussian("всё"))
        assertTrue(isRussianAnswerCorrect("молоко\u0301", "молоко"))
    }

    @Test
    fun stressSensitiveModeRequiresStressMarks() {
        val stressed = "\u043c\u043e\u043b\u043e\u043a\u043e\u0301"
        val unstressed = "\u043c\u043e\u043b\u043e\u043a\u043e"

        assertFalse(evaluateRussianAnswer(stressed, unstressed, ignoreStress = false).accepted)
        assertTrue(evaluateRussianAnswer(stressed, stressed, ignoreStress = false).accepted)
    }

    @Test
    fun stressSensitiveModeExplainsStressOnlyMisses() {
        val stressed = "\u043c\u043e\u043b\u043e\u043a\u043e\u0301"
        val unstressed = "\u043c\u043e\u043b\u043e\u043a\u043e"

        val evaluation = evaluateRussianAnswer(stressed, unstressed, ignoreStress = false)

        assertEquals(AnswerMatch.WRONG, evaluation.match)
        assertFalse(evaluation.accepted)
        assertTrue(evaluation.message.orEmpty().contains("stress differs"))
        assertEquals(stressed, evaluation.expected)
    }

    @Test
    fun stressSensitiveModeExplainsWrongStressPlacement() {
        val expected = "\u043c\u043e\u043b\u043e\u043a\u043e\u0301"
        val wrongVowel = "\u043c\u043e\u0301\u043b\u043e\u043a\u043e"

        val evaluation = evaluateRussianAnswer(expected, wrongVowel, ignoreStress = false)

        assertEquals(AnswerMatch.WRONG, evaluation.match)
        assertFalse(evaluation.accepted)
        assertTrue(evaluation.message.orEmpty().contains("stress differs"))
        assertEquals(expected, evaluation.expected)
    }

    @Test
    fun acceptsEnglishTranslationAlternatives() {
        assertTrue(isEnglishAnswerCorrect("state, government", "state"))
        assertTrue(isEnglishAnswerCorrect("to write / to complete writing", "to complete writing"))
    }

    @Test
    fun acceptsRussianSlashAlternatives() {
        assertTrue(isRussianAnswerCorrect("писать/написать", "написать"))
    }

    @Test
    fun acceptsSmallRussianTyposAsCloseEnough() {
        val evaluation = evaluateRussianAnswer("молоко", "малоко")

        assertEquals(AnswerMatch.CLOSE, evaluation.match)
        assertTrue(evaluation.accepted)
    }

    @Test
    fun rejectsShortRussianWordsUnlessExact() {
        assertFalse(isRussianAnswerCorrect("он", "она"))
    }

    @Test
    fun acceptsEnglishInfinitiveWithOrWithoutTo() {
        assertTrue(isEnglishAnswerCorrect("to go", "go"))
        assertTrue(isEnglishAnswerCorrect("go", "to go"))
    }

    @Test
    fun acceptsEnglishGlossIgnoringParenthetical() {
        assertTrue(isEnglishAnswerCorrect("book (notebook)", "book"))
        assertTrue(isEnglishAnswerCorrect("house (building)", "house"))
    }

    @Test
    fun acceptsRussianIliAndParentheticalAlternatives() {
        assertTrue(isRussianAnswerCorrect("большой или крупный", "крупный"))
        assertTrue(isRussianAnswerCorrect("идти (пешком)", "идти"))
    }

    @Test
    fun toleratesSmallEnglishTypos() {
        assertTrue(isEnglishAnswerCorrect("government", "goverment"))
        assertTrue(isEnglishAnswerCorrect("receive", "recieve"))
        assertTrue(isEnglishAnswerCorrect("beautiful", "beatiful"))
    }

    @Test
    fun smallEnglishTyposAreMarkedCloseForRatingGuidance() {
        val evaluation = evaluateEnglishAnswer("government", "goverment")

        assertEquals(AnswerMatch.CLOSE, evaluation.match)
        assertTrue(evaluation.accepted)
    }

    @Test
    fun stillRejectsWrongShortEnglishWords() {
        assertFalse(isEnglishAnswerCorrect("go", "do"))
        assertFalse(isEnglishAnswerCorrect("cat", "dog"))
    }
}
