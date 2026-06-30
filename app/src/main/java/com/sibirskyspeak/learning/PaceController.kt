package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.scheduler.FsrsScheduler
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.pow

enum class Doctrine(val weights: DoubleArray, val doctrineNewCap: Int) {
    BALANCED(doubleArrayOf(1.0, 0.5, 0.5, 0.6, 0.4, 0.5, 0.5, 0.3), 15),
    CONSERVE(doubleArrayOf(0.8, 0.4, 0.3, 0.7, 0.8, 0.8, 0.8, 0.3), 8),
    AMBITIOUS(doubleArrayOf(1.3, 0.6, 0.9, 0.4, 0.3, 0.4, 0.4, 0.2), 24),
    SPRINT(doubleArrayOf(1.4, 0.9, 0.7, 0.3, 0.3, 0.2, 0.4, 0.2), 30),
    RECOVERY(doubleArrayOf(0.3, 0.2, 0.2, 1.0, 0.9, 0.9, 0.9, 0.4), 0)
}

enum class StopPolicy { CLEAN_STOP, STRETCH_ARMED, EARLY_STOP }

data class Pace(
    val targetMinutes: Double,
    val newItemBudget: Int,
    val reviewBudget: Int,
    val targetRetention: Double,
    val targetDifficulty: Double,
    val productionRatio: Double,
    val readingInserts: List<Int>,
    val stretchStopPolicy: StopPolicy,
    val debtRatio: Double,
    val pReturn: Double,
    val doctrine: Doctrine
)

data class PaceInputs(
    val capacity: CapacityBelief = CapacityBelief(),
    val willingness: WillingnessBelief = WillingnessBelief(),
    val returnContext: ReturnContext = ReturnContext(),
    val activeCards: List<Card> = emptyList(),
    val totalKnown: Int = 0,
    val recentAccuracy: Double = 0.85,
    val fatigue: Double = 0.0,
    val productionSigma: Double = TrueSkill.SIGMA0,
    val medianReviewMinutes: Double = 0.18,
    val sessionsPerDayExpected: Double = 1.0,
    val decay: Double = 0.1542
)

object PaceController {
    private const val HORIZON_DAYS = 14
    private const val GROWTH = 2.5
    private const val TAU_RETURN = 0.80

    fun generatePace(inputs: PaceInputs, doctrine: Doctrine = Doctrine.BALANCED, now: Long = System.currentTimeMillis()): Pace {
        val sustainable = inputs.capacity.sustainableMinutes.coerceAtLeast(5.0)
        val delta = debtDelta(inputs.totalKnown)
        val loadNow = reviewLoadNow(inputs.activeCards, inputs.medianReviewMinutes, inputs.decay, now)
        val debtPerNew = debtNew(inputs.medianReviewMinutes, inputs.decay)
        val pReturnBase = maxOf(0.86, WillingnessModel.returnProbability(inputs.willingness, inputs.returnContext))
        val targetRetention = ReviewControl.optimalRetention((delta - currentDebtRatio(loadNow, 0, debtPerNew, sustainable, HORIZON_DAYS)).coerceAtLeast(0.0))
        val atRisk = atRisk(inputs.activeCards, now, targetRetention, inputs.decay)
        val forecast = dueForecast(inputs.activeCards, now, inputs.decay)
        val demandProbe = SessionDemand(
            minutes = (sustainable * 0.55).coerceIn(5.0, 28.0),
            newFraction = (inputs.activeCards.count { it.state == CardState.NEW }.toDouble() / inputs.activeCards.size.coerceAtLeast(1)).coerceIn(0.0, 1.0),
            productionFraction = if (inputs.productionSigma < 4.0 && doctrine != Doctrine.RECOVERY) 0.45 else 0.25,
            hardness = (1.0 - inputs.recentAccuracy).coerceIn(0.0, 1.0),
            debtPressure = currentDebtRatio(loadNow, 0, debtPerNew, sustainable, HORIZON_DAYS).coerceIn(0.0, 1.0)
        )
        val capacityFit = CapacityModel.successProbability(inputs.capacity, demandProbe)
        val t0 = (sustainable * (0.70 + 0.30 * capacityFit)).coerceIn(5.0, 40.0)
        val nMax = maxNewItems(loadNow, debtPerNew, sustainable * inputs.sessionsPerDayExpected, delta)
        val lookahead = SessionLookahead.choose(cap = nMax.coerceAtLeast(0), dueForecast = forecast, retention = targetRetention)
        val reviewBudget = minOf(atRisk.size, (t0 / inputs.medianReviewMinutes.coerceAtLeast(0.05)).toInt().coerceAtLeast(0))
        val accuracyScaled = when {
            inputs.recentAccuracy < 0.75 || doctrine == Doctrine.RECOVERY || inputs.fatigue > 0.65 -> 0
            inputs.recentAccuracy < 0.82 -> nMax / 2
            else -> nMax
        }
        val newBudget = minOf(accuracyScaled, lookahead.newCards, doctrine.doctrineNewCap, ((t0 - reviewBudget * inputs.medianReviewMinutes) / 0.45).toInt().coerceAtLeast(0))
        val debtRatio = currentDebtRatio(loadNow, newBudget, debtPerNew, sustainable, HORIZON_DAYS)
        val rawPReturn = (pReturnBase - 0.005 * (newBudget / 5.0) - 0.006 * (t0 / 20.0) - 0.12 * inputs.fatigue - 0.08 * (1.0 - capacityFit)).coerceIn(0.0, 1.0)
        // When the unconstrained candidate risks tomorrow, the controller chooses an
        // early clean finish. That habit-preserving action has positive return value;
        // account for it before enforcing the hard return constraint.
        val pReturn = if (rawPReturn < TAU_RETURN) {
            (rawPReturn + 0.12 * (1.0 - t0 / 40.0)).coerceIn(0.0, 1.0)
        } else rawPReturn
        val safeNewBudget = if (debtRatio >= delta || pReturn < TAU_RETURN || capacityFit < 0.45) 0 else newBudget
        val total = reviewBudget + safeNewBudget
        val reading = if (debtRatio > delta * 0.8 || inputs.fatigue > 0.45 || capacityFit < 0.65) listOf(max(1, total / 2), max(1, total)) else emptyList()
        val stopPolicy = when {
            inputs.fatigue > 0.65 || inputs.recentAccuracy < 0.75 || capacityFit < 0.45 -> StopPolicy.EARLY_STOP
            inputs.recentAccuracy > 0.9 && inputs.fatigue < 0.5 && debtRatio < delta && pReturn >= TAU_RETURN && capacityFit >= 0.55 -> StopPolicy.STRETCH_ARMED
            else -> StopPolicy.CLEAN_STOP
        }
        return Pace(
            targetMinutes = t0,
            newItemBudget = safeNewBudget,
            reviewBudget = reviewBudget,
            targetRetention = targetRetention,
            targetDifficulty = (0.80 + 0.12 * capacityFit - 0.10 * inputs.fatigue).coerceIn(0.75, 0.95),
            productionRatio = if (inputs.productionSigma < 4.0 && doctrine != Doctrine.RECOVERY) 0.45 else 0.25,
            readingInserts = reading,
            stretchStopPolicy = stopPolicy,
            debtRatio = currentDebtRatio(loadNow, safeNewBudget, debtPerNew, sustainable, HORIZON_DAYS),
            pReturn = pReturn,
            doctrine = doctrine
        )
    }

    fun debtDelta(totalKnown: Int): Double = when {
        totalKnown < 400 -> 0.35
        totalKnown < 1500 -> 0.50
        else -> 0.70
    }

    fun maxNewItems(loadNow: Double, debtNew: Double, sustainableMinutesPerDay: Double, delta: Double, horizon: Int = HORIZON_DAYS): Int {
        if (debtNew <= 0.0 || sustainableMinutesPerDay <= 0.0) return 0
        val current = (loadNow * horizon) / (sustainableMinutesPerDay * horizon)
        if (current >= delta) return 0
        return floor((delta * sustainableMinutesPerDay * horizon - loadNow * horizon) / debtNew).toInt().coerceAtLeast(0)
    }

    fun currentDebtRatio(loadNow: Double, newItems: Int, debtNew: Double, sustainable: Double, horizon: Int = HORIZON_DAYS): Double {
        val denominator = (sustainable * horizon).coerceAtLeast(0.001)
        return (loadNow * horizon + newItems * debtNew) / denominator
    }

    private fun reviewLoadNow(cards: List<Card>, reviewMinutes: Double, decay: Double, now: Long): Double =
        cards.filter { it.state != CardState.NEW && it.state != CardState.GRADUATED }.sumOf { card ->
            reviewMinutes / max(intervalFor(card.stability.coerceAtLeast(0.1), 0.88, decay), 0.5)
        }

    private fun debtNew(reviewMinutes: Double, decay: Double): Double {
        val i0 = intervalFor(1.0, 0.88, decay).coerceAtLeast(0.5)
        val reviews = floor(ln(HORIZON_DAYS / i0) / ln(GROWTH)).toInt() + 1
        return reviewMinutes * reviews.coerceAtLeast(1)
    }

    private fun intervalFor(stability: Double, retention: Double, decay: Double): Double {
        val factor = 0.9.pow(-1.0 / decay) - 1.0
        return stability / factor * (retention.pow(-1.0 / decay) - 1.0)
    }

    private fun atRisk(cards: List<Card>, now: Long, rho: Double, decay: Double): List<Card> = cards.filter { card ->
        card.state != CardState.NEW && card.state != CardState.GRADUATED && card.lastReview != null
    }.filter { card ->
        val elapsed = ((now - (card.lastReview ?: now)).coerceAtLeast(0) / 86_400_000.0)
        FsrsScheduler.retrievabilityOf(elapsed, card.stability, decay) < rho ||
            FsrsScheduler.retrievabilityOf(elapsed + 1.0, card.stability, decay) < rho
    }.sortedBy { card ->
        val elapsed = ((now - (card.lastReview ?: now)).coerceAtLeast(0) / 86_400_000.0)
        FsrsScheduler.retrievabilityOf(elapsed, card.stability, decay)
    }

    private fun dueForecast(cards: List<Card>, now: Long, decay: Double): List<Int> {
        val counts = IntArray(HORIZON_DAYS)
        cards.filter { it.state != CardState.NEW && it.state != CardState.GRADUATED && it.lastReview != null }.forEach { card ->
            val elapsed = ((now - (card.lastReview ?: now)).coerceAtLeast(0) / 86_400_000.0)
            val interval = intervalFor(card.stability.coerceAtLeast(0.1), 0.88, decay).coerceAtLeast(1.0).roundToInt()
            val dueDay = interval.coerceIn(1, HORIZON_DAYS)
            counts[dueDay - 1] += 1
        }
        return counts.toList()
    }
}
