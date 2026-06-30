package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.ItemDifficulty
import kotlin.math.abs
import kotlin.math.sqrt

data class RivalBelief(
    val rating: Gaussian = Gaussian(),
    val handicap: Double = 0.0,
    val winStreak: Int = 0,
    val persona: String = "rival"
)

data class MatchReport(
    val opponent: String,
    val perfYou: Double,
    val perfOpponent: Double,
    val outcome: MatchOutcome,
    val before: Gaussian,
    val after: Gaussian,
    val opponentAfter: Gaussian,
    val ghostOutcome: MatchOutcome? = null,
    val tier: String = Rival.tier(after.conservativeRating),
    val promotionProgress: String? = null
)

data class PromotionSeries(val lockedTier: Int = 0, val targetTier: Int = 0, val games: Int = 0, val wins: Int = 0)

data class PromotionUpdate(val series: PromotionSeries, val locked: Boolean, val failed: Boolean)

object Rival {
    val tierBoundaries = doubleArrayOf(Double.NEGATIVE_INFINITY, 0.0, 8.0, 16.0, 24.0, 32.0)

    fun tierIndex(displayRating: Double): Int = tierBoundaries.indexOfLast { displayRating >= it }.coerceAtLeast(0)

    fun seasonSigma(sigma: Double, elapsedDays: Double): Double =
        if (elapsedDays >= 60.0) (sigma * 1.3).coerceAtMost(TrueSkill.SIGMA0) else sigma

    fun ghostPerformance(rivalPerformance: Double, ghostMu: Double, rivalMu: Double): Double =
        (rivalPerformance + (ghostMu - rivalMu) / TrueSkill.MU0).coerceIn(0.0, 1.0)

    fun rubberBand(rival: RivalBelief, userGlobal: Gaussian): RivalBelief {
        val target = userGlobal.mu - 0.5 * userGlobal.sigma + rival.handicap
        return rival.copy(rating = rival.rating.copy(mu = rival.rating.mu * 0.7 + target * 0.3))
    }

    fun expectedCorrect(
        rival: Gaussian,
        card: Card,
        difficulty: ItemDifficulty,
        learnerSkill: Gaussian,
        cohortMeanSkill: Double = TrueSkill.MU0,
        weaknessBoostWeight: Double = 0.8
    ): Double {
        val boost = weaknessBoostWeight * (cohortMeanSkill - learnerSkill.mu)
        val scale = sqrt(2.0 * TrueSkill.BETA * TrueSkill.BETA + rival.variance + difficulty.sigma * difficulty.sigma)
        return Normal.cdf((rival.mu - difficulty.elo + boost) / scale)
    }

    fun resolve(user: Gaussian, rival: RivalBelief, perfYou: Double, perfRival: Double): MatchReport {
        val outcome = TrueSkill.outcomeFromPerformance(perfYou, perfRival)
        val update = TrueSkill.update(user, rival.rating, outcome)
        return MatchReport("rival", perfYou, perfRival, outcome, user, update.a, update.b)
    }

    fun nextHandicap(handicap: Double, outcome: MatchOutcome): Double = when (outcome) {
        MatchOutcome.WIN -> (handicap + 0.5).coerceAtMost(6.0)
        MatchOutcome.LOSS -> (handicap - 0.5).coerceAtLeast(-2.0)
        MatchOutcome.DRAW -> handicap
    }

    fun tier(displayRating: Double): String = when {
        displayRating < 0.0 -> "Новичок A1"
        displayRating < 8.0 -> "A1"
        displayRating < 16.0 -> "A2"
        displayRating < 24.0 -> "B1"
        displayRating < 32.0 -> "B2"
        else -> "Гроссмейстер C1"
    }

    fun promotionLocked(results: List<MatchOutcome>): Boolean =
        results.take(3).count { it == MatchOutcome.WIN } >= 2

    fun updatePromotion(series: PromotionSeries, displayRating: Double, outcome: MatchOutcome): PromotionUpdate {
        val crossed = tierIndex(displayRating)
        val active = when {
            series.targetTier > series.lockedTier -> series
            crossed > series.lockedTier -> PromotionSeries(series.lockedTier, series.lockedTier + 1, 0, 0)
            else -> series
        }
        if (active.targetTier <= active.lockedTier) return PromotionUpdate(active, locked = false, failed = false)
        val games = active.games + 1
        val wins = active.wins + if (outcome == MatchOutcome.WIN) 1 else 0
        if (wins >= 2) return PromotionUpdate(PromotionSeries(active.targetTier, active.targetTier, 0, 0), locked = true, failed = false)
        if (games >= 3) return PromotionUpdate(PromotionSeries(active.lockedTier, active.lockedTier, 0, 0), locked = false, failed = true)
        return PromotionUpdate(active.copy(games = games, wins = wins), locked = false, failed = false)
    }

    fun isRubberBanded(rival: Gaussian, user: Gaussian, tolerance: Double = 4.0): Boolean =
        abs(rival.mu - (user.mu - 0.5 * user.sigma)) <= tolerance
}
