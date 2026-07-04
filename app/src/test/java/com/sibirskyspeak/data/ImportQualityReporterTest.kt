package com.sibirskyspeak.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct coverage for ImportQualityReporter now that it's a standalone pure unit —
 * complements the indirect coverage through LearningRepositoryTest's
 * importQualityReport()-based fixtures.
 */
class ImportQualityReporterTest {
    private val readyNominal = Note(
        id = 1, russian = "стол", lemma = "стол", translation = "table", partOfSpeech = "noun",
        gender = "M", declensionJson = """{"NOM_SG":"стол"}""", domainFreqRank = 10,
        exampleSentence = "Это мой стол.", exampleTranslation = "This is my table."
    )
    private val readyVerb = Note(
        id = 2, russian = "делать", lemma = "делать", translation = "to do", partOfSpeech = "verb",
        aspect = "IPF", aktionsart = "activity", aktionsartConfidence = "high", aspectPartner = 99L,
        domainFreqRank = 20, exampleSentence = "Я делаю уроки.", exampleTranslation = "I am doing homework."
    )

    @Test
    fun countsReadyNominalsAndVerbsSeparately() {
        val report = ImportQualityReporter.report(listOf(readyNominal, readyVerb), emptyList(), authenticReadyCoverage = 0.90)
        assertEquals(1, report.readyNominalRows)
        assertEquals(1, report.aspectReadyVerbRows)
        assertEquals(1, report.verifiedAktionsartVerbRows)
    }

    @Test
    fun nounMissingDeclensionOrExampleIsNotReady() {
        val noDeclension = readyNominal.copy(declensionJson = null)
        val noExample = readyNominal.copy(exampleSentence = null, exampleTranslation = null)
        assertEquals(0, ImportQualityReporter.report(listOf(noDeclension), emptyList(), 0.90).readyNominalRows)
        assertEquals(0, ImportQualityReporter.report(listOf(noExample), emptyList(), 0.90).readyNominalRows)
    }

    @Test
    fun verbAktionsartConfidenceGatesTheVerifiedCountButNotReadiness() {
        val lowConfidence = readyVerb.copy(aktionsartConfidence = "low")
        val report = ImportQualityReporter.report(listOf(lowConfidence), emptyList(), 0.90)
        assertEquals(1, report.aspectReadyVerbRows)
        assertEquals(0, report.verifiedAktionsartVerbRows)
    }

    @Test
    fun meetsDesignDocMinimumOnlyWhenEveryThresholdClears() {
        val short = ImportQualityReporter.report(listOf(readyNominal), emptyList(), 0.90)
        assertFalse(short.meetsDesignDocMinimum)
        assertTrue(short.warnings.isNotEmpty())
    }

    @Test
    fun targetReadyCoverageUsesTheSuppliedThresholdNotAHardcodedOne() {
        val recommendation = ReaderRecommendation(
            text = ReaderText(id = 1, title = "t", body = "b", source = "target:x"),
            coverage = 0.85,
            knownTokens = 85,
            totalTokens = 100,
            status = ReaderStatus.PRODUCTIVE,
            authenticReady = false
        )
        val strict = ImportQualityReporter.report(emptyList(), listOf(recommendation), authenticReadyCoverage = 0.90)
        val lenient = ImportQualityReporter.report(emptyList(), listOf(recommendation), authenticReadyCoverage = 0.80)
        assertEquals(0, strict.targetTextsAtOrAbove90)
        assertEquals(1, lenient.targetTextsAtOrAbove90)
    }
}
