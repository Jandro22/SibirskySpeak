package com.sibirskyspeak.review

import com.sibirskyspeak.data.ReaderRecommendation
import com.sibirskyspeak.data.ReaderStatus
import com.sibirskyspeak.data.ReaderText
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderRecommendationTest {
    @Test
    fun recommendationKeepsBestFitWhenTextIsNotStarted() {
        val notStartedBestFit = recommendation(id = 1, title = "Not started best fit", coverage = 0.94, totalTokens = 10)
        val nextBestFit = recommendation(id = 2, title = "Next best fit", coverage = 0.92, totalTokens = 12)

        val recommendation = recommendNextReaderUi(
            listOf(notStartedBestFit, nextBestFit),
            progressFor = { id -> if (id == 1L) -1 else 0 }
        )

        assertEquals(notStartedBestFit, recommendation)
    }

    @Test
    fun recommendationKeepsBestFitUntilLastTokenIsReached() {
        val inProgressBestFit = recommendation(id = 1, title = "In progress best fit", coverage = 0.94, totalTokens = 10)
        val nextBestFit = recommendation(id = 2, title = "Next best fit", coverage = 0.92, totalTokens = 12)

        val recommendation = recommendNextReaderUi(
            listOf(inProgressBestFit, nextBestFit),
            progressFor = { id -> if (id == 1L) 8 else 0 }
        )

        assertEquals(inProgressBestFit, recommendation)
    }

    @Test
    fun recommendationSkipsCompletedBestFitWhenAnotherTextIsAvailable() {
        val finishedBestFit = recommendation(id = 1, title = "Finished best fit", coverage = 0.94, totalTokens = 10)
        val nextBestFit = recommendation(id = 2, title = "Next best fit", coverage = 0.92, totalTokens = 12)

        val recommendation = recommendNextReaderUi(
            listOf(finishedBestFit, nextBestFit),
            progressFor = { id -> if (id == 1L) 9 else 0 }
        )

        assertEquals(nextBestFit, recommendation)
    }

    @Test
    fun recommendationFallsBackToCompletedTextWhenEverythingIsDone() {
        val finishedBestFit = recommendation(id = 1, title = "Finished best fit", coverage = 0.94, totalTokens = 10)
        val finishedHarder = recommendation(id = 2, title = "Finished harder", coverage = 0.86, totalTokens = 12)

        val recommendation = recommendNextReaderUi(
            listOf(finishedHarder, finishedBestFit),
            progressFor = { id -> if (id == 1L) 9 else 11 }
        )

        assertEquals(finishedBestFit, recommendation)
    }

    private fun recommendation(id: Long, title: String, coverage: Double, totalTokens: Int): ReaderRecommendation =
        ReaderRecommendation(
            text = ReaderText(id = id, title = title, body = title),
            coverage = coverage,
            knownTokens = (coverage * totalTokens).toInt(),
            totalTokens = totalTokens,
            status = ReaderStatus.PRODUCTIVE,
            authenticReady = false
        )
}
