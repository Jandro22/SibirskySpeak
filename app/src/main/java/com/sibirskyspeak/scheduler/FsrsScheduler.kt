package com.sibirskyspeak.scheduler

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReviewLog
import com.sibirskyspeak.data.ReviewSource
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

class FsrsScheduler(
    private val desiredRetentionProvider: () -> Double = { 0.9 },
    // Data-driven personalization: a bounded multiplier on every computed interval,
    // learned from the user's own retention vs target. 1.0 = stock FSRS behaviour.
    private val intervalModifierProvider: () -> Double = { 1.0 },
    private val maximumIntervalDays: Int = 36_500,
    private val weights: DoubleArray = DEFAULT_WEIGHTS,
    // Interval fuzz spreads review dates so a batch of cards graded the same way on
    // one day doesn't all resurface on the same future day (the "avalanche" that
    // makes review days lumpy and makes you re-type the same words together). This
    // is the standard FSRS/Anki fuzz. Off by default so unit tests stay
    // deterministic; the app turns it on. [random] is injectable for repeatable tests.
    private val enableFuzz: Boolean = false,
    private val random: Random = Random.Default
) : Scheduler {
    override fun review(card: Card, rating: Rating, now: Long): Pair<Card, ReviewLog> {
        val elapsedDays = elapsedDays(card, now)
        val reviewed = applyQueueConstraints(nextCard(card, rating, now, elapsedDays))
        val log = ReviewLog(
            cardId = card.id,
            reviewDatetime = now,
            rating = rating,
            stateBefore = card.state,
            scheduledDays = reviewed.scheduledDays,
            elapsedDays = elapsedDays,
            source = if (card.queue == Queue.GRAMMAR) ReviewSource.GRAMMAR_DRILL else ReviewSource.SRS_REVIEW
        )
        return reviewed to log
    }

    override fun preview(card: Card, now: Long): Map<Rating, Int> =
        Rating.entries.associateWith { rating ->
            applyQueueConstraints(nextCard(card, rating, now, elapsedDays(card, now))).scheduledDays
        }

    override fun applyQueueConstraints(card: Card): Card {
        if (card.queue != Queue.GRAMMAR || card.state == CardState.GRADUATED || card.scheduledDays <= GRAMMAR_INTERVAL_CEILING_DAYS) {
            return card
        }
        return card.copy(
            scheduledDays = GRAMMAR_INTERVAL_CEILING_DAYS,
            due = (card.lastReview ?: System.currentTimeMillis()) + GRAMMAR_INTERVAL_CEILING_DAYS * DAY_MILLIS
        )
    }

    private fun nextCard(card: Card, rating: Rating, now: Long, elapsedDays: Int): Card {
        // Guard against non-finite / non-positive prior state. A NaN or Infinity
        // (e.g. from a hand-edited backup or a divide-by-zero in older data) would
        // otherwise propagate and pin the card's due date to garbage forever. We
        // fall back to the fresh-card priors so the card simply re-learns cleanly.
        val nextDifficulty = if (card.state == CardState.NEW || !card.difficulty.isFinite() || card.difficulty <= 0.0) {
            initDifficulty(rating)
        } else {
            nextDifficulty(card.difficulty, rating)
        }

        val currentStability = card.stability.takeIf { it.isFinite() && it > 0.0 } ?: initStability(rating)
        val nextStability = when {
            card.state == CardState.NEW -> initStability(rating)
            elapsedDays == 0 -> sameDayStability(currentStability, rating)
            rating == Rating.AGAIN -> forgettingStability(nextDifficulty, currentStability, retrievability(elapsedDays, currentStability))
            else -> recallStability(nextDifficulty, currentStability, retrievability(elapsedDays, currentStability), rating)
        }.let { if (it.isFinite()) it else initStability(rating) }
            .coerceIn(MIN_STABILITY, maximumIntervalDays.toDouble())

        val scheduledDays = scheduledDays(nextStability, rating, card.state)
        return card.copy(
            due = dueAt(now, scheduledDays, rating, card.state),
            stability = nextStability,
            difficulty = nextDifficulty,
            elapsedDays = elapsedDays,
            scheduledDays = scheduledDays,
            reps = card.reps + 1,
            lapses = card.lapses + if (rating == Rating.AGAIN) 1 else 0,
            consecutiveCorrect = if (rating == Rating.GOOD || rating == Rating.EASY) card.consecutiveCorrect + 1 else 0,
            state = nextState(card.state, rating),
            lastReview = now
        )
    }

    private fun initStability(rating: Rating): Double = weights[rating.value - 1].coerceAtLeast(MIN_STABILITY)

    private fun initDifficulty(rating: Rating): Double =
        (weights[4] - exp(weights[5] * (rating.value - 1)) + 1.0).clampDifficulty()

    private fun nextDifficulty(difficulty: Double, rating: Rating): Double {
        val delta = -weights[6] * (rating.value - 3)
        val damped = difficulty + delta * (10.0 - difficulty) / 9.0
        return (weights[7] * initDifficulty(Rating.EASY) + (1.0 - weights[7]) * damped).clampDifficulty()
    }

    private fun retrievability(elapsedDays: Int, stability: Double): Double =
        (1.0 + factor() * elapsedDays / stability).pow(-decay())

    private fun sameDayStability(stability: Double, rating: Rating): Double {
        val increase = exp(weights[17] * (rating.value - 3 + weights[18])) * stability.pow(-weights[19])
        return stability * if (rating.value >= Rating.GOOD.value) max(1.0, increase) else increase
    }

    private fun recallStability(difficulty: Double, stability: Double, retrievability: Double, rating: Rating): Double {
        val hardPenalty = if (rating == Rating.HARD) weights[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) weights[16] else 1.0
        val increase = exp(weights[8]) *
            (11.0 - difficulty) *
            stability.pow(-weights[9]) *
            (exp(weights[10] * (1.0 - retrievability)) - 1.0) *
            hardPenalty *
            easyBonus
        return stability * (increase + 1.0)
    }

    private fun forgettingStability(difficulty: Double, stability: Double, retrievability: Double): Double =
        weights[11] *
            difficulty.pow(-weights[12]) *
            ((stability + 1.0).pow(weights[13]) - 1.0) *
            exp(weights[14] * (1.0 - retrievability))

    private fun interval(stability: Double): Int {
        val desiredRetention = desiredRetentionProvider().coerceIn(0.80, 0.97)
        val modifier = intervalModifierProvider().coerceIn(0.5, 2.0)
        val raw = stability / factor() * (desiredRetention.pow(-1.0 / decay()) - 1.0) * modifier
        return (if (raw.isFinite()) raw.roundToInt() else 1).coerceIn(1, maximumIntervalDays)
    }

    private fun decay(): Double = weights[20]

    private fun factor(): Double = 0.9.pow(-1.0 / decay()) - 1.0

    private fun scheduledDays(stability: Double, rating: Rating, state: CardState): Int =
        when {
            rating == Rating.AGAIN -> 0
            state == CardState.NEW && rating == Rating.HARD -> 0
            else -> applyFuzz(interval(stability))
        }

    /**
     * FSRS interval fuzz: widen the interval by a small, length-dependent band and
     * pick a random day within it. Intervals under ~2.5 days are left exact so the
     * daily learning cadence is preserved. Bands match the py-fsrs reference.
     */
    private fun applyFuzz(intervalDays: Int): Int {
        if (!enableFuzz || intervalDays < 2) return intervalDays
        val ivl = intervalDays.toDouble()
        var delta = 1.0
        delta += 0.15 * (min(ivl, 7.0) - 2.5).coerceAtLeast(0.0)
        delta += 0.10 * (min(ivl, 20.0) - 7.0).coerceAtLeast(0.0)
        delta += 0.05 * (ivl - 20.0).coerceAtLeast(0.0)
        var minIvl = (ivl - delta).roundToInt().coerceAtLeast(2)
        val maxIvl = (ivl + delta).roundToInt().coerceAtMost(maximumIntervalDays)
        if (minIvl > maxIvl) minIvl = maxIvl
        return if (minIvl >= maxIvl) maxIvl else random.nextInt(minIvl, maxIvl + 1)
    }

    private fun dueAt(now: Long, scheduledDays: Int, rating: Rating, state: CardState): Long {
        val delayMillis = when {
            rating == Rating.AGAIN -> 10 * MINUTE_MILLIS
            state == CardState.NEW && rating == Rating.HARD -> 10 * MINUTE_MILLIS
            else -> scheduledDays * DAY_MILLIS
        }
        return now + delayMillis
    }

    private fun nextState(state: CardState, rating: Rating): CardState =
        when {
            state == CardState.GRADUATED && rating != Rating.AGAIN -> CardState.GRADUATED
            rating == Rating.AGAIN && state == CardState.REVIEW -> CardState.RELEARNING
            rating == Rating.AGAIN && state == CardState.GRADUATED -> CardState.RELEARNING
            rating == Rating.AGAIN -> CardState.LEARNING
            state == CardState.NEW && rating == Rating.HARD -> CardState.LEARNING
            else -> CardState.REVIEW
        }

    private fun elapsedDays(card: Card, now: Long): Int {
        val previous = card.lastReview ?: return 0
        return max(0, ((now - previous) / DAY_MILLIS).toInt())
    }

    private fun Double.clampDifficulty(): Double = if (isFinite()) coerceIn(1.0, 10.0) else 5.0

    companion object {
        private const val DAY_MILLIS = 86_400_000L
        private const val MINUTE_MILLIS = 60_000L
        private const val GRAMMAR_INTERVAL_CEILING_DAYS = 10
        private const val MIN_STABILITY = 0.01

        val DEFAULT_WEIGHTS: DoubleArray = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,
            0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629,
            1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
        )
    }
}
