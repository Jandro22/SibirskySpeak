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
            val weight = (1.0 + (attempt.itemDifficulty - TrueSkill.MU0) / TrueSkill.SIGMA0).coerceIn(0.5, 2.0)
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
        if (responseMs.isEmpty() || correct.isEmpty()) return 0.0
        val baseline = responseMs.take(3).sorted().let { it[it.size / 2].toDouble() }.coerceAtLeast(1.0)
        val rollingLatency = responseMs.takeLast(3).average()
        val rollingAccuracy = correct.takeLast(4).count { it }.toDouble() / correct.takeLast(4).size
        return (0.6 * ((rollingLatency / baseline) - 1.0).coerceAtLeast(0.0) + 0.4 * (1.0 - rollingAccuracy)).coerceIn(0.0, 1.0)
    }
}

object CausalFormatReward {
    const val TIME_COST_PER_MINUTE = 0.05
    const val FATIGUE_COST = 0.3

    fun reward(recalled: Boolean, counterfactualBase: Double, timeMinutes: Double, fatigueDelta: Double): Double =
        (if (recalled) 1.0 else 0.0) - counterfactualBase.coerceIn(0.0, 1.0) -
            TIME_COST_PER_MINUTE * timeMinutes.coerceAtLeast(0.0) - FATIGUE_COST * fatigueDelta.coerceAtLeast(0.0)
}

data class BanditCredit(val action: String, val context: DoubleArray, val reward: Double)

object ColdStartModel {
    const val DAYS = 14
    const val INITIAL_INFO_GAIN = 0.6
    const val MIN_INFO_GAIN = 0.05

    fun blend(personal: Double, cohort: Double, observations: Int, priorStrength: Int): Double {
        val weight = observations.coerceAtLeast(0).toDouble() / (observations.coerceAtLeast(0) + priorStrength.coerceAtLeast(1))
        return weight * personal + (1.0 - weight) * cohort
    }

    fun infoGainWeight(activeDays: Int): Double =
        (INITIAL_INFO_GAIN * Math.pow(0.97, activeDays.coerceAtLeast(0).toDouble())).coerceAtLeast(MIN_INFO_GAIN)

    fun gaussianInformationGain(beforeSigma: Double, afterSigma: Double): Double =
        if (beforeSigma <= 0.0 || afterSigma <= 0.0 || afterSigma >= beforeSigma) 0.0 else ln(beforeSigma / afterSigma)

    fun active(activeDays: Int): Boolean = activeDays < DAYS
}
