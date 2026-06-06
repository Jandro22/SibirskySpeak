package com.sibirskyspeak.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerNormalizerTest {
    @Test
    fun normalizesStressCaseWhitespaceAndYo() {
        assertEquals("молоко", normalizeRussian("  МОЛОКО\u0301 "))
        assertEquals("все", normalizeRussian("всё"))
        assertTrue(isRussianAnswerCorrect("молоко\u0301", "молоко"))
    }
}
