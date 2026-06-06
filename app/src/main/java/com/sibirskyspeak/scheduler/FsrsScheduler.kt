package com.sibirskyspeak.scheduler

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReviewLog
import com.sibirskyspeak.data.ReviewSource
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class FsrsScheduler(
    private val desiredRetention: Double = 0.9,
    private val maximumIntervalDays: Int = 36_500,
    private val weights: DoubleArray = DEFAULT_WEIGHTS
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
        val nextDifficulty = if (card.state == CardState.NEW || card.difficulty <= 0.0) {
            initDifficulty(rating)
        } else {
            nextDifficulty(card.difficulty, rating)
        }

        val currentStability = card.stability.takeIf { it > 0.0 } ?: initStability(rating)
        val nextStability = when {
            card.state == CardState.NEW -> initStability(rating)
            elapsedDays == 0 -> sameDayStability(currentStability, rating)
            rating == Rating.AGAIN -> forgettingStability(nextDifficulty, currentStability, retrievability(elapsedDays, currentStability))
            else -> recallStability(nextDifficulty, currentStability, retrievability(elapsedDays, currentStability), rating)
        }.coerceAtLeast(MIN_STABILITY)

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
        (1.0 + FACTOR * elapsedDays / stability).pow(DECAY)

    private fun sameDayStability(stability: Double, rating: Rating): Double =
        stability * exp(weights[17] * (rating.value - 3 + weights[18]))

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
        val raw = stability / FACTOR * (desiredRetention.pow(1.0 / DECAY) - 1.0)
        return raw.roundToInt().coerceIn(1, maximumIntervalDays)
    }

    private fun scheduledDays(stability: Double, rating: Rating, state: CardState): Int =
        when {
            rating == Rating.AGAIN -> 0
            state == CardState.NEW && rating == Rating.HARD -> 0
            else -> interval(stability)
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
        return max(0, ceil((now - previous).toDouble() / DAY_MILLIS).toInt())
    }

    private fun Double.clampDifficulty(): Double = coerceIn(1.0, 10.0)

    companion object {
        private const val DAY_MILLIS = 86_400_000L
        private const val MINUTE_MILLIS = 60_000L
        private const val GRAMMAR_INTERVAL_CEILING_DAYS = 10
        private const val DECAY = -0.5
        private const val FACTOR = 19.0 / 81.0
        private const val MIN_STABILITY = 0.01

        val DEFAULT_WEIGHTS: DoubleArray = doubleArrayOf(
            0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604,
            0.0046, 1.54575, 0.1192, 1.01925, 1.9395, 0.11, 0.29605,
            2.2698, 0.2315, 2.9898, 0.51655, 0.6621
        )
    }
}
