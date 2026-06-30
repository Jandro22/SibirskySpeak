package com.sibirskyspeak.learning

import kotlin.math.exp
import kotlin.math.sqrt

data class CapacityBelief(val mu: Double = 12.0, val sigma: Double = 8.0) {
    val sustainableMinutes: Double get() = (mu - 0.5 * sigma).coerceAtLeast(0.0)
}

data class SessionDemand(
    val minutes: Double,
    val newFraction: Double = 0.0,
    val productionFraction: Double = 0.0,
    val hardness: Double = 0.0,
    val debtPressure: Double = 0.0
)

object CapacityModel {
    const val OBS_NOISE = 4.0
    const val TAU = 0.5

    fun effortAdjustedDemand(demand: SessionDemand): Double =
        demand.minutes * (1.0 + 0.6 * demand.newFraction + 0.4 * demand.productionFraction + 0.5 * demand.hardness + 0.3 * demand.debtPressure)

    fun successProbability(capacity: CapacityBelief, demand: SessionDemand): Double {
        val scale = sqrt(capacity.sigma * capacity.sigma + OBS_NOISE * OBS_NOISE + TrueSkill.BETA * TrueSkill.BETA)
        return Normal.cdf((capacity.mu - effortAdjustedDemand(demand)) / scale)
    }

    fun update(capacity: CapacityBelief, observedGoodMinutes: Double): CapacityBelief {
        val predictedVariance = capacity.sigma * capacity.sigma + TAU * TAU
        val gain = predictedVariance / (predictedVariance + OBS_NOISE * OBS_NOISE)
        val mu = capacity.mu + gain * (observedGoodMinutes - capacity.mu)
        val sigma = sqrt((predictedVariance * (1.0 - gain)).coerceAtLeast(0.0001))
        return CapacityBelief(mu, sigma)
    }
}

data class WillingnessSignals(
    val completed: Boolean = false,
    val flow: Boolean = false,
    val cleanFinish: Boolean = false,
    val returnStreak: Boolean = false,
    val quit: Boolean = false,
    val overload: Boolean = false,
    val reviewDebtHigh: Boolean = false
)

data class ReturnContext(
    val hoursSinceLastZ: Double = 0.0,
    val streakZ: Double = 0.0,
    val lastSessionFatigue: Double = 0.0,
    val lastDebtRatio: Double = 0.0
)

data class WillingnessBelief(
    val habit: Double = 0.0,
    val coeffs: DoubleArray = WillingnessModel.priorMeans.copyOf()
) {
    override fun equals(other: Any?): Boolean = other is WillingnessBelief && habit == other.habit && coeffs.contentEquals(other.coeffs)
    override fun hashCode(): Int = 31 * habit.hashCode() + coeffs.contentHashCode()
}

object WillingnessModel {
    val priorMeans = doubleArrayOf(0.2, 0.8, -0.5, 0.4, -0.6, -0.7)
    private val priorSds = doubleArrayOf(0.5, 0.3, 0.3, 0.3, 0.3, 0.3)

    fun transition(habit: Double, signals: WillingnessSignals): Double =
        0.92 * habit +
            0.4 * signals.completed.asDouble() +
            0.5 * signals.flow.asDouble() +
            0.5 * signals.cleanFinish.asDouble() +
            0.3 * signals.returnStreak.asDouble() -
            0.8 * signals.quit.asDouble() -
            0.7 * signals.overload.asDouble() -
            0.5 * signals.reviewDebtHigh.asDouble()

    fun returnProbability(belief: WillingnessBelief, context: ReturnContext): Double {
        val x = features(belief.habit, context)
        return logistic(belief.coeffs.indices.sumOf { i -> belief.coeffs[i] * x[i] })
    }

    fun updateReturn(belief: WillingnessBelief, context: ReturnContext, returned: Boolean, step: Double = 0.08): WillingnessBelief {
        val x = features(belief.habit, context)
        val p = returnProbability(belief, context)
        val error = returned.asDouble() - p
        val next = belief.coeffs.copyOf()
        for (i in next.indices) {
            val unclamped = next[i] + step * error * x[i]
            next[i] = unclamped.coerceIn(priorMeans[i] - 2.0 * priorSds[i], priorMeans[i] + 2.0 * priorSds[i])
        }
        return belief.copy(coeffs = next)
    }

    private fun features(habit: Double, context: ReturnContext): DoubleArray =
        doubleArrayOf(1.0, habit, context.hoursSinceLastZ, context.streakZ, context.lastSessionFatigue, context.lastDebtRatio)

    private fun logistic(x: Double): Double = 1.0 / (1.0 + exp(-x))
}

private fun Boolean.asDouble(): Double = if (this) 1.0 else 0.0
