package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.ConceptMastery
import com.sibirskyspeak.data.ItemDifficulty
import com.sibirskyspeak.data.SkillRating
import com.sibirskyspeak.scheduler.FsrsScheduler
import kotlin.math.sqrt
import kotlin.math.exp

enum class AbilitySkill { VOCAB, READING, LISTENING, PRODUCTION, CASES, ASPECT, SYNTAX, PHONOLOGY }

data class LearnerWorldState(
    val global: Gaussian = Gaussian(),
    val skills: Map<AbilitySkill, Gaussian> = emptyMap(),
    val fatigue: Double = 0.0,
    val formatFit: Double = 0.0
) {
    fun skill(skill: AbilitySkill): Gaussian = skills[skill] ?: Gaussian(0.0, TrueSkill.SIGMA0)
}

object WorldModel {
    const val S_MEM = 6.0
    const val K_K = 8.0
    const val LAMBDA_LOAD = 6.0

    data class Calibration(
        val intercept: Double = 0.0,
        val memoryScale: Double = S_MEM,
        val masteryScale: Double = K_K,
        val loadScale: Double = LAMBDA_LOAD,
        val observations: Int = 0
    )

    data class CalibrationSample(
        val correct: Boolean,
        val abilityMinusDifficulty: Double,
        val memoryProbit: Double,
        val masteryCentered: Double,
        val formatFit: Double,
        val fatigue: Double,
        val scale: Double
    )

    fun masteryKeys(concept: String, roots: Collection<String>): List<String> =
        listOf(concept) + roots.filter { it.isNotBlank() }.distinct().map { "root:$it" }

    fun skillWeights(card: Card): Map<AbilitySkill, Double> = when (card.cardType) {
        CardType.RU_TO_MEANING -> mapOf(AbilitySkill.VOCAB to 1.0)
        CardType.MEANING_TO_RU -> mapOf(AbilitySkill.PRODUCTION to 0.7, AbilitySkill.VOCAB to 0.3)
        CardType.AUDIO_TO_RU, CardType.DICTATION -> mapOf(AbilitySkill.LISTENING to 0.6, AbilitySkill.VOCAB to 0.2, AbilitySkill.PRODUCTION to 0.2)
        CardType.CLOZE -> if (card.gramConcept?.contains("CASE", ignoreCase = true) == true) {
            mapOf(AbilitySkill.VOCAB to 0.5, AbilitySkill.CASES to 0.5)
        } else {
            mapOf(AbilitySkill.VOCAB to 0.5, AbilitySkill.SYNTAX to 0.5)
        }
        CardType.CASE_FILL -> mapOf(AbilitySkill.CASES to 0.8, AbilitySkill.VOCAB to 0.2)
        CardType.ASPECT_SELECT -> mapOf(AbilitySkill.ASPECT to 0.8, AbilitySkill.VOCAB to 0.2)
        CardType.SPEAK -> mapOf(AbilitySkill.PHONOLOGY to 0.6, AbilitySkill.PRODUCTION to 0.4)
        else -> mapOf(AbilitySkill.VOCAB to 0.5, AbilitySkill.READING to 0.5)
    }

    fun effectiveAbility(card: Card, state: LearnerWorldState): Gaussian {
        val weights = skillWeights(card)
        val mu = state.global.mu + weights.entries.sumOf { (skill, weight) -> weight * state.skill(skill).mu }
        val variance = state.global.variance + weights.entries.sumOf { (skill, weight) -> weight * weight * state.skill(skill).variance }
        return Gaussian(mu, sqrt(variance))
    }

    fun successProbability(
        card: Card,
        itemDifficulty: ItemDifficulty = ItemDifficulty(card.id),
        mastery: ConceptMastery? = null,
        state: LearnerWorldState = LearnerWorldState(),
        now: Long = System.currentTimeMillis(),
        decay: Double = 0.1542,
        calibration: Calibration = Calibration()
    ): Double {
        val ability = effectiveAbility(card, state)
        val elapsedDays = ((now - (card.lastReview ?: now)).coerceAtLeast(0) / 86_400_000.0)
        val retrievability = if (card.lastReview == null || card.stability <= 0.0) 0.5 else FsrsScheduler.retrievabilityOf(elapsedDays, card.stability, decay)
        val memory = calibration.memoryScale * Normal.invCdf(retrievability.coerceIn(1e-3, 1.0 - 1e-3))
        val k = calibration.masteryScale * ((mastery?.probability ?: 0.5) - 0.5)
        val load = calibration.loadScale * state.fatigue.coerceIn(0.0, 1.0)
        val scale = sqrt(2.0 * TrueSkill.BETA * TrueSkill.BETA + ability.variance + itemDifficulty.sigma * itemDifficulty.sigma)
        return Normal.cdf(calibration.intercept + (ability.mu + memory + k + state.formatFit - itemDifficulty.elo - load) / scale)
    }

    fun applyAbilityDelta(
        ratings: Map<AbilitySkill, SkillRating>,
        card: Card,
        delta: Double,
        now: Long = System.currentTimeMillis()
    ): List<SkillRating> {
        val weights = skillWeights(card)
        return weights.map { (skill, weight) ->
            val current = ratings[skill] ?: SkillRating(skill.name.lowercase())
            current.copy(
                mu = current.mu + 0.4 * delta * weight,
                observations = current.observations + 1,
                updatedAt = now
            )
        }
    }
}

object SuccessCalibrationFitter {
    const val MIN_SAMPLES = 120

    fun fit(samples: List<WorldModel.CalibrationSample>, initial: WorldModel.Calibration = WorldModel.Calibration()): WorldModel.Calibration {
        if (samples.size < MIN_SAMPLES) return initial
        val theta = doubleArrayOf(initial.intercept, initial.memoryScale, initial.masteryScale, initial.loadScale)
        repeat(240) {
            val gradient = DoubleArray(4)
            samples.forEach { sample ->
                val scale = sample.scale.coerceAtLeast(1e-3)
                val x = doubleArrayOf(1.0, sample.memoryProbit / scale, sample.masteryCentered / scale, -sample.fatigue / scale)
                val z = theta[0] + (sample.abilityMinusDifficulty + sample.formatFit) / scale +
                    theta[1] * x[1] + theta[2] * x[2] + theta[3] * x[3]
                val p = Normal.cdf(z).coerceIn(1e-6, 1.0 - 1e-6)
                val pdf = exp(-0.5 * z * z) / sqrt(2.0 * Math.PI)
                val error = (if (sample.correct) 1.0 else 0.0) - p
                val multiplier = error * pdf / (p * (1.0 - p))
                for (i in gradient.indices) gradient[i] += multiplier * x[i]
            }
            val rate = 0.025 / samples.size
            for (i in theta.indices) theta[i] += rate * gradient[i]
            theta[0] = theta[0].coerceIn(-2.0, 2.0)
            theta[1] = theta[1].coerceIn(2.0, 10.0)
            theta[2] = theta[2].coerceIn(2.0, 14.0)
            theta[3] = theta[3].coerceIn(2.0, 12.0)
        }
        return WorldModel.Calibration(theta[0], theta[1], theta[2], theta[3], samples.size)
    }
}
