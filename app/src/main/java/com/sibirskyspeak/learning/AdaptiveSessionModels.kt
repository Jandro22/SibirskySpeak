package com.sibirskyspeak.learning

import com.sibirskyspeak.review.AnswerMode
import kotlin.math.ln

data class ObjectiveAttempt(
    val itemId: Long,
    val correct: Boolean,
    val responseMs: Long,
    val answerMode: AnswerMode,
    val itemDifficulty: Double
)

object PerformanceModel {
    fun targetTimeMs(mode: AnswerMode): Long = when (mode) {
        AnswerMode.ENGLISH -> 6_000
        AnswerMode.RUSSIAN_TYPED, AnswerMode.RUSSIAN_STRESS_TYPED, AnswerMode.SPEAK -> 12_000
        AnswerMode.CHOICE -> 4_000
        AnswerMode.AUDIO_ONLY -> 10_000
        AnswerMode.LESSON -> 0
    }

    fun isObjective(mode: AnswerMode): Boolean = mode != AnswerMode.LESSON

    fun score(attempts: List<ObjectiveAttempt>): Double {
        val graded = attempts.filter { isObjective(it.answerMode) }
        if (graded.isEmpty()) return 0.0
        var weightedScore = 0.0
        var weightSum = 0.0
        graded.forEach { attempt ->
            val speed = (targetTimeMs(attempt.answerMode).toDouble() / attempt.responseMs.coerceAtLeast(1)).coerceIn(0.5, 1.5)
            val difficulty = attempt.itemDifficulty.takeIf(Double::isFinite) ?: TrueSkill.MU0
            val weight = (1.0 + (difficulty - TrueSkill.MU0) / TrueSkill.SIGMA0).coerceIn(0.5, 2.0)
            weightedScore += weight * if (attempt.correct) speed else 0.0
            weightSum += weight
        }
        return (weightedScore / weightSum.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
    }

    fun effectiveMinutes(attempts: List<ObjectiveAttempt>): Double = attempts
        .filter { isObjective(it.answerMode) }
        .sumOf { attempt ->
            val cap = targetTimeMs(attempt.answerMode).coerceAtLeast(1) * 2
            attempt.responseMs.coerceIn(0, cap).toDouble()
        } / 60_000.0
}

object FatigueModel {
    fun estimate(responseMs: List<Long>, correct: List<Boolean>): Double {
        // Only aligned observations are meaningful. During process restoration the
        // latency and correctness buffers can briefly differ in length.
        val count = minOf(responseMs.size, correct.size)
        if (count == 0) return 0.0
        val latencies = responseMs.takeLast(count).map { it.coerceAtLeast(1L) }
        val outcomes = correct.takeLast(count)
        val baseline = latencies.take(3).sorted().let { it[it.size / 2].toDouble() }
        val rollingLatency = latencies.takeLast(3).average()
        val recentOutcomes = outcomes.takeLast(4)
        val rollingAccuracy = recentOutcomes.count { it }.toDouble() / recentOutcomes.size
        return (0.6 * ((rollingLatency / baseline) - 1.0).coerceAtLeast(0.0) + 0.4 * (1.0 - rollingAccuracy)).coerceIn(0.0, 1.0)
    }
}

object CausalFormatReward {
    const val TIME_COST_PER_MINUTE = 0.05
    const val FATIGUE_COST = 0.3

    fun reward(recalled: Boolean, counterfactualBase: Double, timeMinutes: Double, fatigueDelta: Double): Double =
        (if (recalled) 1.0 else 0.0) -
            (counterfactualBase.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.5) -
            TIME_COST_PER_MINUTE * (timeMinutes.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0) -
            FATIGUE_COST * (fatigueDelta.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0)
}

data class BanditCredit(val action: String, val context: DoubleArray, val reward: Double)

object ColdStartModel {
    const val DAYS = 14
    const val INITIAL_INFO_GAIN = 0.6
    const val MIN_INFO_GAIN = 0.05

    fun blend(personal: Double, cohort: Double, observations: Int, priorStrength: Int): Double {
        val weight = observations.coerceAtLeast(0).toDouble() / (observations.coerceAtLeast(0) + priorStrength.coerceAtLeast(1))
        val safeCohort = cohort.takeIf(Double::isFinite) ?: 0.0
        val safePersonal = personal.takeIf(Double::isFinite) ?: safeCohort
        return weight * safePersonal + (1.0 - weight) * safeCohort
    }

    fun infoGainWeight(activeDays: Int): Double =
        (INITIAL_INFO_GAIN * Math.pow(0.97, activeDays.coerceAtLeast(0).toDouble())).coerceAtLeast(MIN_INFO_GAIN)

    fun gaussianInformationGain(beforeSigma: Double, afterSigma: Double): Double =
        if (beforeSigma <= 0.0 || afterSigma <= 0.0 || afterSigma >= beforeSigma) 0.0 else ln(beforeSigma / afterSigma)

    fun active(activeDays: Int): Boolean = activeDays < DAYS
}
