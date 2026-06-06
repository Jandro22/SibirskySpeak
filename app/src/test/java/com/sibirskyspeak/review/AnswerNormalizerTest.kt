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
}
