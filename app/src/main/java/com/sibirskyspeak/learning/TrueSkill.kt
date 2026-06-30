package com.sibirskyspeak.learning

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class Gaussian(val mu: Double = TrueSkill.MU0, val sigma: Double = TrueSkill.SIGMA0) {
    val variance: Double get() = sigma * sigma
    val conservativeRating: Double get() = mu - TrueSkill.DISPLAY_SIGMA_MULTIPLIER * sigma
}

enum class MatchOutcome { WIN, DRAW, LOSS }

data class RatingUpdate(val a: Gaussian, val b: Gaussian)

object TrueSkill {
    const val MU0 = 25.0
    const val SIGMA0 = 25.0 / 3.0
    const val BETA = 25.0 / 6.0
    const val TAU = SIGMA0 / 100.0
    const val TAU_IDLE = 0.20
    const val P_DRAW = 0.10
    const val EPS_PERF = 0.05
    const val DISPLAY_SIGMA_MULTIPLIER = 3.0
    private val minVariance = (0.01 * SIGMA0).pow(2)

    fun drawMargin(beta: Double = BETA, pDraw: Double = P_DRAW): Double =
        Normal.invCdf((pDraw + 1.0) / 2.0) * sqrt(2.0) * beta

    fun update(
        a: Gaussian,
        b: Gaussian,
        outcomeForA: MatchOutcome,
        beta: Double = BETA,
        tau: Double = TAU,
        drawMargin: Double = drawMargin(beta)
    ): RatingUpdate {
        if (outcomeForA == MatchOutcome.LOSS) {
            val reversed = update(b, a, MatchOutcome.WIN, beta, tau, drawMargin)
            return RatingUpdate(reversed.b, reversed.a)
        }
        val varianceA = a.variance + tau * tau
        val varianceB = b.variance + tau * tau
        // Canonical TrueSkill match scale: c^2 = 2*beta^2 + varA + varB. (No tuned
        // multiplier — earlier code used a fitted constant that only matched one
        // operating point and biased every asymmetric update.)
        val c = sqrt(2.0 * beta * beta + varianceA + varianceB)
        val t = (a.mu - b.mu) / c
        val margin = drawMargin / c
        val factors = if (outcomeForA == MatchOutcome.DRAW) drawFactors(t, margin) else winFactors(t, margin)
        val muA = a.mu + (varianceA / c) * factors.v
        val muB = b.mu - (varianceB / c) * factors.v
        val sigA2 = (varianceA * (1.0 - (varianceA / (c * c)) * factors.w)).coerceAtLeast(minVariance)
        val sigB2 = (varianceB * (1.0 - (varianceB / (c * c)) * factors.w)).coerceAtLeast(minVariance)
        return RatingUpdate(Gaussian(muA, sqrt(sigA2)), Gaussian(muB, sqrt(sigB2)))
    }

    fun outcomeFromPerformance(you: Double, opponent: Double, epsilon: Double = EPS_PERF): MatchOutcome =
        when {
            abs(you - opponent) <= epsilon -> MatchOutcome.DRAW
            you > opponent -> MatchOutcome.WIN
            else -> MatchOutcome.LOSS
        }

    fun idleDecay(rating: Gaussian, days: Double, tauIdle: Double = TAU_IDLE): Gaussian {
        val sigma = sqrt(min(SIGMA0 * SIGMA0, rating.variance + tauIdle * tauIdle * days.coerceAtLeast(0.0)))
        return rating.copy(sigma = sigma)
    }

    fun displayed(rating: Gaussian): Double = rating.conservativeRating

    private data class Factors(val v: Double, val w: Double)

    // Canonical truncated-Gaussian factors. w in [0,1] is the variance-reduction
    // fraction; v shifts the mean. No tuning constants.
    private fun winFactors(t: Double, a: Double): Factors {
        val x = t - a
        val denom = Normal.cdf(x)
        if (denom < 1e-9) return Factors(-x, 1.0)   // extreme upset guard
        val v = Normal.pdf(x) / denom
        return Factors(v, (v * (v + x)).coerceIn(0.0, 1.0))
    }

    private fun drawFactors(t: Double, a: Double): Factors {
        val low = -a - t
        val high = a - t
        val denom = Normal.cdf(high) - Normal.cdf(low)
        if (denom < 1e-9) return Factors(0.0, 1.0)  // degenerate (only at draw-margin 0)
        val v = (Normal.pdf(low) - Normal.pdf(high)) / denom
        val w = v * v + (high * Normal.pdf(high) - low * Normal.pdf(low)) / denom
        return Factors(v, w.coerceIn(0.0, 1.0))
    }
}

object Normal {
    fun pdf(x: Double): Double = exp(-0.5 * x * x) / sqrt(2.0 * PI)

    fun cdf(x: Double): Double = 0.5 * (1.0 + erf(x / sqrt(2.0)))

    fun invCdf(p: Double): Double {
        require(p in 0.0..1.0) { "p must be in [0, 1]" }
        if (p == 0.0) return Double.NEGATIVE_INFINITY
        if (p == 1.0) return Double.POSITIVE_INFINITY
        val a = doubleArrayOf(-39.69683028665376, 220.9460984245205, -275.9285104469687, 138.357751867269, -30.66479806614716, 2.506628277459239)
        val b = doubleArrayOf(-54.47609879822406, 161.5858368580409, -155.6989798598866, 66.80131188771972, -13.28068155288572)
        val c = doubleArrayOf(-0.007784894002430293, -0.3223964580411365, -2.400758277161838, -2.549732539343734, 4.374664141464968, 2.938163982698783)
        val d = doubleArrayOf(0.007784695709041462, 0.3224671290700398, 2.445134137142996, 3.754408661907416)
        val plow = 0.02425
        val phigh = 1.0 - plow
        return when {
            p < plow -> {
                val q = sqrt(-2.0 * ln(p))
                (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
            }
            p <= phigh -> {
                val q = p - 0.5
                val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
            }
            else -> {
                val q = sqrt(-2.0 * ln(1.0 - p))
                -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
            }
        }
    }

    private fun erf(x: Double): Double {
        val sign = if (x < 0) -1 else 1
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return sign * y
    }
}
