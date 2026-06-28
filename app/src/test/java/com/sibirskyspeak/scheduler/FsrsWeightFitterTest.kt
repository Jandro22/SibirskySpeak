package com.sibirskyspeak.scheduler

import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReviewFitRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class FsrsWeightFitterTest {

    private val defaults = FsrsScheduler.DEFAULT_WEIGHTS

    @Test
    fun emptyHistoryKeepsStockWeights() {
        val result = FsrsWeightFitter.fit(emptyList(), defaults.copyOf())
        assertFalse(result.changed)
        assertTrue(result.weights.contentEquals(defaults))
    }

    @Test
    fun smallSampleDoesNotMoveWeights() {
        // Below the per-rating minimum: nothing should be fit.
        val rows = initStabilityRows(ratingValue = 3, trueStability = 30.0, decay = defaults[20], perCell = 2)
        val result = FsrsWeightFitter.fit(rows, defaults.copyOf())
        assertEquals(defaults[2], result.weights[2], 1e-9)
    }

    @Test
    fun recoversHigherInitialStabilityForGood() {
        // Generate next-recall outcomes consistent with a true GOOD initial stability
        // far above the stock value; the fit must raise w[2] toward it.
        val trueS0 = 12.0
        val rows = initStabilityRows(ratingValue = 3, trueStability = trueS0, decay = defaults[20], perCell = 250)
        val result = FsrsWeightFitter.fit(rows, defaults.copyOf())

        assertTrue("fit must report a change", result.changed)
        assertTrue("GOOD init stability should rise above stock ${defaults[2]}", result.weights[2] > defaults[2] + 1.0)
        assertTrue("GOOD init stability should approach the true value", result.weights[2] in 7.0..13.0)
        // Initial stability must stay non-decreasing across rating quality.
        for (i in 1..3) assertTrue(result.weights[i] >= result.weights[i - 1])
    }

    @Test
    fun recoversSteeperDecay() {
        val trueDecay = 0.30
        val rows = decayRows(trueDecay = trueDecay, perCell = 220)
        val result = FsrsWeightFitter.fit(rows, defaults.copyOf())

        assertTrue("decay sample should clear the gate", result.decaySamples >= FsrsWeightFitter.MIN_DECAY_SAMPLES)
        val fittedDecay = result.weights[FsrsScheduler.DECAY_INDEX]
        assertTrue("decay should rise above stock ${defaults[20]}", fittedDecay > defaults[20] + 0.02)
        assertTrue("decay should approach the true value", fittedDecay in 0.22..0.34)
    }

    /**
     * One card per "outcome": a NEW first review with [ratingValue], then a spaced
     * probe whose recall is split in exact proportion to the true forgetting curve,
     * so the MLE has a clean target. stabilityBefore is left 0 so these rows never
     * feed the decay estimator.
     */
    private fun initStabilityRows(ratingValue: Int, trueStability: Double, decay: Double, perCell: Int): List<ReviewFitRow> {
        val rating = Rating.entries.first { it.value == ratingValue }
        val out = mutableListOf<ReviewFitRow>()
        var cardId = 1L
        for (t in intArrayOf(1, 2, 4, 8, 16)) {
            val r = FsrsScheduler.retrievabilityOf(t.toDouble(), trueStability, decay)
            val recalled = (perCell * r).roundToInt()
            repeat(perCell) { i ->
                val good = i < recalled
                out += ReviewFitRow(cardId, 1_000L, rating, CardState.NEW, 0, 0.0)
                out += ReviewFitRow(cardId, 2_000L, if (good) Rating.GOOD else Rating.AGAIN, CardState.LEARNING, t, 0.0)
                cardId++
            }
        }
        return out
    }

    /** Mature reviews across several (stability, elapsed) cells, recall split in exact
     * proportion to the true decay, for a clean decay MLE target. */
    private fun decayRows(trueDecay: Double, perCell: Int): List<ReviewFitRow> {
        val out = mutableListOf<ReviewFitRow>()
        var cardId = 1L
        for (s in doubleArrayOf(10.0, 30.0, 90.0)) {
            for (t in intArrayOf(5, 20, 60)) {
                val r = FsrsScheduler.retrievabilityOf(t.toDouble(), s, trueDecay)
                val recalled = (perCell * r).roundToInt()
                repeat(perCell) { i ->
                    val good = i < recalled
                    out += ReviewFitRow(cardId, 3_000L, if (good) Rating.GOOD else Rating.AGAIN, CardState.REVIEW, t, s)
                    cardId++
                }
            }
        }
        return out
    }
}
