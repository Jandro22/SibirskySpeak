package com.sibirskyspeak.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluencySimEngineTest {
    @Test
    fun alreadyReachedMilestonesAreZeroDays() {
        val result = FluencySimEngine.runSimulation(
            currentCapacity = CapacityBelief(mu = 24.0, sigma = 2.0),
            currentWillingness = WillingnessBelief(habit = 2.0),
            initialActiveCards = emptyList(),
            totalKnownStart = 1_400,
            evidenceDays = 14,
            startTimeMillis = 0L
        )

        assertEquals(0, result.daysToA1)
        assertEquals(0, result.daysToA2)
        assertNotNull(result.daysToB1)
    }

    @Test
    fun introducedWordsCreateRealReviewLoad() {
        val result = FluencySimEngine.runSimulation(
            currentCapacity = CapacityBelief(mu = 30.0, sigma = 2.0),
            currentWillingness = WillingnessBelief(habit = 3.0),
            initialActiveCards = emptyList(),
            totalKnownStart = 6_700,
            evidenceDays = 20,
            startTimeMillis = 0L
        )

        assertNotNull(result.daysToC2)
        assertTrue("new cards must become future FSRS reviews", result.finalReviewLoad > 0)
        assertTrue(result.stablePace > 0.0)
    }

    @Test
    fun uncertaintyNarrowsAsHistoryAccrues() {
        fun forecast(evidenceDays: Int) = FluencySimEngine.runSimulation(
            currentCapacity = CapacityBelief(mu = 24.0, sigma = 2.0),
            currentWillingness = WillingnessBelief(habit = 2.0),
            initialActiveCards = emptyList(),
            totalKnownStart = 6_800,
            evidenceDays = evidenceDays,
            startTimeMillis = 0L
        )
        val early = forecast(2)
        val mature = forecast(30)
        val earlyRange = early.ranges.getValue("C2")
        val matureRange = mature.ranges.getValue("C2")

        assertTrue(early.isEarlyEstimate)
        assertTrue(!mature.isEarlyEstimate)
        assertTrue(earlyRange.high - earlyRange.low > matureRange.high - matureRange.low)
    }

    @Test
    fun forecastExposesCleanCapacitySeparatelyFromStartingReviewBacklog() {
        val cards = (1L..120L).map { id ->
            com.sibirskyspeak.data.Card(
                id = id,
                noteId = id,
                cardType = com.sibirskyspeak.data.CardType.RU_TO_MEANING,
                queue = com.sibirskyspeak.data.Queue.VOCAB,
                stability = 0.2,
                difficulty = 5.0,
                state = com.sibirskyspeak.data.CardState.REVIEW,
                due = 0L,
                lastReview = 0L
            )
        }
        val result = FluencySimEngine.runSimulation(
            currentCapacity = CapacityBelief(mu = 12.0, sigma = 8.0),
            currentWillingness = WillingnessBelief(habit = 0.0),
            initialActiveCards = cards,
            totalKnownStart = 21,
            evidenceDays = 5,
            recentAccuracy = 0.85,
            startTimeMillis = 0L
        )

        assertEquals(120, result.startingReviewLoad)
        assertTrue("review backlog must not be reported as zero sustainable pace", result.sustainablePace > 0.0)
        assertTrue(result.sustainableMinutes > 0.0)
    }
}
