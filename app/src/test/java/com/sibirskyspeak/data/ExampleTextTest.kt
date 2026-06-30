package com.sibirskyspeak.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExampleTextTest {
    @Test
    fun splitsSimpleRussianEnglishConcatenation() {
        val r = ExampleText.splitBilingual("Ты действительно не глуп - You're really not stupid.")
        assertEquals("Ты действительно не глуп" to "You're really not stupid.", r)
    }

    @Test
    fun keepsRussianEmDashCopulaIntact() {
        // A clean Russian sentence using an em-dash copula must not be split, even if a
        // trailing English clause exists: the rightmost Cyrillic-prefix/Latin-suffix
        // boundary is what cuts, so internal Russian dashes stay with the Russian.
        val r = ExampleText.splitBilingual("Гидрофон — это микрофон под водой - A hydrophone is a microphone underwater.")
        assertEquals("Гидрофон — это микрофон под водой" to "A hydrophone is a microphone underwater.", r)
    }

    @Test
    fun doesNotSplitCleanRussianOnlySentence() {
        assertNull(ExampleText.splitBilingual("Книга на столе."))
        assertNull(ExampleText.splitBilingual("Гидрофон — это микрофон под водой."))
    }

    @Test
    fun doesNotSplitWhenSuffixContainsCyrillic() {
        // " - " separating two Russian fragments is not a translation boundary.
        assertNull(ExampleText.splitBilingual("Эхо улиц - ведущий орган прессы"))
    }

    @Test
    fun leavesHyphenatedRussianWordsAlone() {
        assertNull(ExampleText.splitBilingual("кто-то из-за угла"))
    }

    @Test
    fun withSplitExamplesOnlyTouchesBlankTranslations() {
        val polluted = Note(
            russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun",
            tags = "", exampleSentence = "Это мой дом - This is my house.", exampleTranslation = null
        )
        val fixed = polluted.withSplitExamples()
        assertEquals("Это мой дом", fixed.exampleSentence)
        assertEquals("This is my house.", fixed.exampleTranslation)

        // Already-clean note is returned unchanged (idempotent / no-op).
        val clean = polluted.copy(exampleSentence = "Это мой дом", exampleTranslation = "This is my house.")
        assertEquals(clean, clean.withSplitExamples())
    }
}
