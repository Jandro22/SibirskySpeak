package com.sibirskyspeak.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDifficultyAnalyzerTest {
    @Test
    fun emptyTextIsFiniteAndGentle() {
        val metrics = ReaderDifficultyAnalyzer.analyze(coverage = 1.0, tokenCount = 0, sentenceCount = 0, morphologyCount = 0, idiomCount = 0)
        assertEquals(0.0, metrics.syntaxComplexity, 0.0001)
        assertEquals(0.0, metrics.difficultyScore, 0.0001)
    }

    @Test
    fun difficultyRisesWithUnknownLongMorphologicalIdiomaticText() {
        val easy = ReaderDifficultyAnalyzer.analyze(1.0, 8, 1, 0, 0)
        val hard = ReaderDifficultyAnalyzer.analyze(0.1, 80, 2, 30, 10)
        assertTrue(hard.difficultyScore > easy.difficultyScore)
        assertTrue(hard.difficultyScore <= 1.0)
    }
}
