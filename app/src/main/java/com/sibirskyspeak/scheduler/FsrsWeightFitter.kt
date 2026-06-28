package com.sibirskyspeak.scheduler

import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReviewFitRow
import kotlin.math.ln

/**
 * Lightweight, on-device FSRS weight personalization.
 *
 * A single global interval multiplier (see `ReviewViewModel.recalibrateScheduling`)
 * can only scale *every* interval by the same amount; it cannot correct a forgetting
 * curve whose shape or whose per-rating initial stability is miscalibrated for this
 * learner. This fitter re-estimates the two highest-leverage parts of the weight
 * vector directly from the learner's own review history by maximum likelihood:
 *
 *  - **Initial stability `w[0..3]`** — one value per first-review rating, estimated
 *    from how well the *next* spaced recall went for cards introduced with that
 *    rating. This is where SRS users see the biggest accuracy gains.
 *  - **Decay `w[20]`** — the shape of the forgetting curve, estimated across all
 *    mature reviews from (stability-going-in, elapsed days, recalled?).
 *
 * Everything else is left at the stock FSRS-6 value. Estimates are blended toward the
 * current weights with a confidence that grows with sample size, and clamped to safe
 * ranges, so the fit moves smoothly and can never produce a degenerate scheduler.
 */
object FsrsWeightFitter {

    /** Per-rating minimum first→second transitions before that init-stability is fit. */
    const val MIN_INIT_SAMPLES_PER_RATING = 30
    /** Minimum mature-review samples before the decay parameter is fit. */
    const val MIN_DECAY_SAMPLES = 200

    // Confidence half-saturation: a parameter reaches a 0.5 blend toward its fitted
    // value at this many samples, approaching (but never fully trusting) it beyond.
    private const val INIT_BLEND_K = 120.0
    private const val DECAY_BLEND_K = 600.0

    private const val MIN_INIT_STABILITY = 0.05
    private const val MAX_INIT_STABILITY = 100.0
    private const val MIN_DECAY = 0.10
    private const val MAX_DECAY = 0.50
    private const val PROB_EPS = 1e-6

    data class Result(
        val weights: DoubleArray,
        /** rating value (1..4) -> number of first→second transitions used. */
        val initStabilitySamples: Map<Int, Int>,
        val decaySamples: Int,
        val changed: Boolean
    ) {
        override fun equals(other: Any?): Boolean =
            other is Result && weights.contentEquals(other.weights) &&
                initStabilitySamples == other.initStabilitySamples &&
                decaySamples == other.decaySamples && changed == other.changed

        override fun hashCode(): Int = weights.contentHashCode()
    }

    /** A single (elapsed-days, recalled?) observation against a known stability. */
    private data class Obs(val elapsedDays: Double, val stability: Double, val recalled: Boolean)

    fun fit(rows: List<ReviewFitRow>, currentWeights: DoubleArray): Result {
        val weights = currentWeights.copyOf()
        val decay = FsrsScheduler.decayOf(currentWeights)

        // --- Initial stability: first-review rating -> next spaced recall outcome ---
        val initByRating = HashMap<Int, MutableList<Obs>>()
        forEachCard(rows) { cardRows ->
            val introIndex = cardRows.indexOfFirst { it.stateBefore == CardState.NEW }
            if (introIndex < 0) return@forEachCard
            val ratingValue = cardRows[introIndex].rating.value
            // The next review that actually waited a day or more tests the assigned
            // initial stability; same-day relearns don't probe the forgetting curve.
            val probe = cardRows.drop(introIndex + 1).firstOrNull { it.elapsedDays > 0 } ?: return@forEachCard
            initByRating.getOrPut(ratingValue) { mutableListOf() }
                .add(Obs(probe.elapsedDays.toDouble(), stability = 0.0, recalled = probe.rating != Rating.AGAIN))
        }

        val initSamples = LinkedHashMap<Int, Int>()
        for (ratingValue in 1..4) {
            val obs = initByRating[ratingValue].orEmpty()
            initSamples[ratingValue] = obs.size
            if (obs.size < MIN_INIT_SAMPLES_PER_RATING) continue
            val fitted = mleInitStability(obs, decay)
            val blend = obs.size / (obs.size + INIT_BLEND_K)
            val index = ratingValue - 1
            weights[index] = (weights[index] * (1 - blend) + fitted * blend)
                .coerceIn(MIN_INIT_STABILITY, MAX_INIT_STABILITY)
        }
        // FSRS requires non-decreasing initial stability across rating quality.
        for (i in 1..3) weights[i] = maxOf(weights[i], weights[i - 1])

        // --- Decay: forgetting-curve shape over all mature reviews ---
        val decayObs = ArrayList<Obs>()
        for (row in rows) {
            if (row.stateBefore != CardState.REVIEW && row.stateBefore != CardState.RELEARNING) continue
            if (row.elapsedDays <= 0 || row.stabilityBefore <= 0.0 || !row.stabilityBefore.isFinite()) continue
            decayObs.add(Obs(row.elapsedDays.toDouble(), row.stabilityBefore, row.rating != Rating.AGAIN))
        }
        if (decayObs.size >= MIN_DECAY_SAMPLES) {
            val fitted = mleDecay(decayObs)
            val blend = decayObs.size / (decayObs.size + DECAY_BLEND_K)
            weights[FsrsScheduler.DECAY_INDEX] =
                (weights[FsrsScheduler.DECAY_INDEX] * (1 - blend) + fitted * blend)
                    .coerceIn(MIN_DECAY, MAX_DECAY)
        }

        return Result(
            weights = weights,
            initStabilitySamples = initSamples,
            decaySamples = decayObs.size,
            changed = !weights.contentEqualsApprox(currentWeights)
        )
    }

    /** Walk [rows] (sorted by cardId, then time) one card's history at a time. */
    private inline fun forEachCard(rows: List<ReviewFitRow>, block: (List<ReviewFitRow>) -> Unit) {
        var start = 0
        while (start < rows.size) {
            var end = start + 1
            while (end < rows.size && rows[end].cardId == rows[start].cardId) end++
            block(rows.subList(start, end))
            start = end
        }
    }

    /** MLE for initial stability S0 given a fixed decay (1-D grid over log space). */
    private fun mleInitStability(obs: List<Obs>, decay: Double): Double {
        val factor = FsrsScheduler.factorOf(decay)
        return argmax(MIN_INIT_STABILITY, MAX_INIT_STABILITY, logScale = true) { s0 ->
            var ll = 0.0
            for (o in obs) {
                val r = (1.0 + factor * o.elapsedDays / s0).pow(-decay).clampProb()
                ll += if (o.recalled) ln(r) else ln(1.0 - r)
            }
            ll
        }
    }

    /** MLE for decay over (elapsed, stability, recalled) observations. */
    private fun mleDecay(obs: List<Obs>): Double =
        argmax(MIN_DECAY, MAX_DECAY, logScale = false) { d ->
            val factor = FsrsScheduler.factorOf(d)
            var ll = 0.0
            for (o in obs) {
                val r = (1.0 + factor * o.elapsedDays / o.stability).pow(-d).clampProb()
                ll += if (o.recalled) ln(r) else ln(1.0 - r)
            }
            ll
        }

    /**
     * Maximize [score] over [lo, hi] with a coarse scan then a golden-section refine.
     * The coarse scan guards against a non-unimodal likelihood; the refine sharpens
     * the winning bracket. Cheap (≈70 evals) and runs at most once per day.
     */
    private fun argmax(lo: Double, hi: Double, logScale: Boolean, score: (Double) -> Double): Double {
        val steps = 40
        fun at(i: Int): Double = if (logScale) {
            val lLo = ln(lo); val lHi = ln(hi)
            kotlin.math.exp(lLo + (lHi - lLo) * i / steps)
        } else lo + (hi - lo) * i / steps
        var bestI = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0..steps) {
            val s = score(at(i))
            if (s > bestScore) { bestScore = s; bestI = i }
        }
        // Golden-section refine within the neighbouring brackets of the grid winner.
        var a = at((bestI - 1).coerceAtLeast(0))
        var b = at((bestI + 1).coerceAtMost(steps))
        val gr = 0.6180339887498949
        var c = b - (b - a) * gr
        var d = a + (b - a) * gr
        repeat(20) {
            if (score(c) < score(d)) a = c else b = d
            c = b - (b - a) * gr
            d = a + (b - a) * gr
        }
        return (a + b) / 2.0
    }

    private fun Double.clampProb(): Double = coerceIn(PROB_EPS, 1.0 - PROB_EPS)

    private fun Double.pow(exp: Double): Double = Math.pow(this, exp)

    private fun DoubleArray.contentEqualsApprox(other: DoubleArray, eps: Double = 1e-4): Boolean {
        if (size != other.size) return false
        for (i in indices) if (kotlin.math.abs(this[i] - other[i]) > eps) return false
        return true
    }
}
