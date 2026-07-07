package com.sibirskyspeak.learning

import kotlin.math.exp
import kotlin.math.sqrt

data class CapacityBelief(val mu: Double = 12.0, val sigma: Double = 8.0) {
    val sustainableMinutes: Double get() {
        val safeMu = mu.takeIf(Double::isFinite) ?: 12.0
        val safeSigma = sigma.takeIf { it.isFinite() && it >= 0.0 } ?: 8.0
        return (safeMu - 0.5 * safeSigma).coerceAtLeast(0.0)
    }
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
        (demand.minutes.takeIf(Double::isFinite) ?: 0.0).coerceAtLeast(0.0) *
            (1.0 + 0.6 * demand.newFraction.safeFraction() + 0.4 * demand.productionFraction.safeFraction() +
                0.5 * demand.hardness.safeFraction() + 0.3 * demand.debtPressure.safeFraction())

    fun successProbability(capacity: CapacityBelief, demand: SessionDemand): Double {
        val mu = capacity.mu.takeIf(Double::isFinite) ?: 12.0
        val sigma = capacity.sigma.takeIf { it.isFinite() && it >= 0.0 } ?: 8.0
        val scale = sqrt(sigma * sigma + OBS_NOISE * OBS_NOISE + TrueSkill.BETA * TrueSkill.BETA)
        return Normal.cdf((mu - effortAdjustedDemand(demand)) / scale).coerceIn(0.0, 1.0)
    }

    fun update(capacity: CapacityBelief, observedGoodMinutes: Double, coldStartWeight: Double = 1.0): CapacityBelief {
        val priorMu = capacity.mu.takeIf(Double::isFinite) ?: 12.0
        val priorSigma = capacity.sigma.takeIf { it.isFinite() && it >= 0.0 } ?: 8.0
        val observation = observedGoodMinutes.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: return CapacityBelief(priorMu, priorSigma)
        val predictedVariance = priorSigma * priorSigma + TAU * TAU
        val rawGain = predictedVariance / (predictedVariance + OBS_NOISE * OBS_NOISE)
        val gain = rawGain * coldStartWeight.coerceIn(0.0, 1.0)
        val mu = priorMu + gain * (observation - priorMu)
        val sigma = sqrt((predictedVariance * (1.0 - gain)).coerceAtLeast(0.0001))
        return CapacityBelief(mu, sigma)
    }

    /** A session shorter than this tells us nothing about the learner's ceiling —
     * it's far more likely the queue simply ran out of due/eligible cards than
     * that the learner hit their limit after a couple of minutes. */
    const val MIN_INFORMATIVE_MINUTES = 2.0

    /** Update from a session duration without treating an engine-imposed early stop
     * as proof that the learner was incapable of continuing. Short, non-fatigued
     * sessions are right-censored observations: they establish a lower bound only. */
    fun updateFromSession(
        capacity: CapacityBelief,
        observedMinutes: Double,
        stoppedEarly: Boolean,
        fatigue: Double,
        coldStartWeight: Double = 1.0
    ): CapacityBelief {
        val repaired = CapacityBelief(
            mu = (capacity.mu.takeIf(Double::isFinite) ?: 12.0).coerceAtLeast(5.0),
            sigma = capacity.sigma.takeIf { it.isFinite() && it >= 0.0 } ?: 8.0
        )
        val observed = observedMinutes.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: return repaired
        val safeFatigue = fatigue.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        return when {
            stoppedEarly && safeFatigue < 0.70 -> repaired
            safeFatigue >= 0.70 -> update(repaired, observed.coerceAtLeast(1.0), coldStartWeight)
            observed < MIN_INFORMATIVE_MINUTES -> repaired
            else -> update(repaired, observed, coldStartWeight)
        }
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
        0.92 * (habit.takeIf(Double::isFinite) ?: 0.0) +
            0.4 * signals.completed.asDouble() +
            0.5 * signals.flow.asDouble() +
            0.5 * signals.cleanFinish.asDouble() +
            0.3 * signals.returnStreak.asDouble() -
            0.8 * signals.quit.asDouble() -
            0.7 * signals.overload.asDouble() -
            0.5 * signals.reviewDebtHigh.asDouble()

    fun returnProbability(belief: WillingnessBelief, context: ReturnContext): Double {
        val x = features(belief.habit, context)
        val z = x.indices.sumOf { i ->
            val coefficient = belief.coeffs.getOrNull(i)?.takeIf(Double::isFinite) ?: priorMeans[i]
            coefficient * x[i]
        }
        return logistic(z)
    }

    fun updateReturn(belief: WillingnessBelief, context: ReturnContext, returned: Boolean, step: Double = 0.08): WillingnessBelief {
        val x = features(belief.habit, context)
        val p = returnProbability(belief, context)
        val error = returned.asDouble() - p
        val next = DoubleArray(priorMeans.size) { i -> belief.coeffs.getOrNull(i)?.takeIf(Double::isFinite) ?: priorMeans[i] }
        for (i in next.indices) {
            val unclamped = next[i] + step * error * x[i]
            next[i] = unclamped.coerceIn(priorMeans[i] - 2.0 * priorSds[i], priorMeans[i] + 2.0 * priorSds[i])
        }
        return belief.copy(coeffs = next)
    }

    private fun features(habit: Double, context: ReturnContext): DoubleArray =
        doubleArrayOf(1.0, habit, context.hoursSinceLastZ, context.streakZ, context.lastSessionFatigue, context.lastDebtRatio)
            .map { it.takeIf(Double::isFinite) ?: 0.0 }.toDoubleArray()

    private fun logistic(x: Double): Double = 1.0 / (1.0 + exp(-x))
}

private fun Boolean.asDouble(): Double = if (this) 1.0 else 0.0
private fun Double.safeFraction(): Double = takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
