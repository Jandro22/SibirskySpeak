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
    val fatigue: Double = 0.0
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
        val fatigue: Double,
        val scale: Double
    )

    fun predictedProbability(sample: CalibrationSample, calibration: Calibration = Calibration()): Double {
        if (!sample.abilityMinusDifficulty.isFinite() || !sample.memoryProbit.isFinite() ||
            !sample.masteryCentered.isFinite() || !sample.fatigue.isFinite() ||
            !sample.scale.isFinite() || sample.scale <= 0.0) return 0.5
        val scale = sample.scale.coerceAtLeast(1e-3)
        val intercept = calibration.intercept.takeIf(Double::isFinite)?.coerceIn(-2.0, 2.0) ?: 0.0
        val memory = calibration.memoryScale.takeIf(Double::isFinite)?.coerceIn(2.0, 10.0) ?: S_MEM
        val mastery = calibration.masteryScale.takeIf(Double::isFinite)?.coerceIn(2.0, 14.0) ?: K_K
        val load = calibration.loadScale.takeIf(Double::isFinite)?.coerceIn(2.0, 12.0) ?: LAMBDA_LOAD
        val z = intercept + (sample.abilityMinusDifficulty + memory * sample.memoryProbit +
            mastery * sample.masteryCentered - load * sample.fatigue) / scale
        return Normal.cdf(z).coerceIn(0.0, 1.0)
    }

    fun masteryKeys(concept: String, roots: Collection<String>): List<String> =
        listOf(concept) + roots.filter { it.isNotBlank() }.distinct().map { "root:$it" }

    fun skillWeights(card: Card): Map<AbilitySkill, Double> = when (card.cardType) {
        CardType.RU_TO_MEANING -> mapOf(AbilitySkill.VOCAB to 1.0)
        CardType.MEANING_TO_RU -> mapOf(AbilitySkill.PRODUCTION to 0.7, AbilitySkill.VOCAB to 0.3)
        CardType.AUDIO_TO_RU -> mapOf(AbilitySkill.LISTENING to 0.55, AbilitySkill.PHONOLOGY to 0.25, AbilitySkill.VOCAB to 0.20)
        CardType.DICTATION -> mapOf(AbilitySkill.LISTENING to 0.45, AbilitySkill.PHONOLOGY to 0.15, AbilitySkill.PRODUCTION to 0.20, AbilitySkill.SYNTAX to 0.20)
        CardType.CLOZE -> if (card.gramConcept?.contains("CASE", ignoreCase = true) == true) {
            mapOf(AbilitySkill.VOCAB to 0.5, AbilitySkill.CASES to 0.5)
        } else {
            mapOf(AbilitySkill.VOCAB to 0.5, AbilitySkill.SYNTAX to 0.5)
        }
        CardType.CASE_FILL -> mapOf(AbilitySkill.CASES to 0.8, AbilitySkill.VOCAB to 0.2)
        CardType.ASPECT_SELECT -> mapOf(AbilitySkill.ASPECT to 0.8, AbilitySkill.VOCAB to 0.2)
        CardType.SPEAK -> mapOf(AbilitySkill.PHONOLOGY to 0.6, AbilitySkill.PRODUCTION to 0.4)
        CardType.SENTENCE_BUILD -> mapOf(AbilitySkill.SYNTAX to 0.55, AbilitySkill.PRODUCTION to 0.35, AbilitySkill.VOCAB to 0.10)
        CardType.STRESS_MARK -> mapOf(AbilitySkill.PHONOLOGY to 0.85, AbilitySkill.VOCAB to 0.15)
        CardType.VERB_FORM -> mapOf(AbilitySkill.PRODUCTION to 0.45, AbilitySkill.SYNTAX to 0.35, AbilitySkill.VOCAB to 0.20)
        CardType.ADJ_AGREE -> mapOf(AbilitySkill.CASES to 0.45, AbilitySkill.SYNTAX to 0.40, AbilitySkill.PRODUCTION to 0.15)
        CardType.GENDER_ID -> mapOf(AbilitySkill.CASES to 0.55, AbilitySkill.VOCAB to 0.30, AbilitySkill.SYNTAX to 0.15)
        CardType.CONCEPT_DRILL -> mapOf(AbilitySkill.SYNTAX to 0.65, AbilitySkill.PRODUCTION to 0.20, AbilitySkill.READING to 0.15)
        CardType.LESSON -> mapOf(AbilitySkill.READING to 0.50, AbilitySkill.SYNTAX to 0.50)
    }

    fun effectiveAbility(card: Card, state: LearnerWorldState): Gaussian {
        val weights = skillWeights(card)
        val global = state.global.sanitized(TrueSkill.MU0)
        val mu = global.mu + weights.entries.sumOf { (skill, weight) -> weight * state.skill(skill).sanitized(0.0).mu }
        val variance = global.variance + weights.entries.sumOf { (skill, weight) ->
            weight * weight * state.skill(skill).sanitized(0.0).variance
        }
        return Gaussian(mu, sqrt(variance))
    }

    fun successProbability(
        card: Card,
        itemDifficulty: ItemDifficulty = ItemDifficulty(card.id),
        mastery: ConceptMastery? = null,
        state: LearnerWorldState = LearnerWorldState(),
        now: Long = System.currentTimeMillis(),
        decay: Double = FsrsScheduler.decayOf(FsrsScheduler.DEFAULT_WEIGHTS),
        calibration: Calibration = Calibration()
    ): Double {
        val ability = effectiveAbility(card, state)
        val elapsedDays = ((now - (card.lastReview ?: now)).coerceAtLeast(0) / 86_400_000.0)
        val retrievability = if (card.lastReview == null || card.stability <= 0.0) 0.5 else FsrsScheduler.retrievabilityOf(elapsedDays, card.stability, decay)
        val memoryScale = calibration.memoryScale.takeIf(Double::isFinite)?.coerceIn(2.0, 10.0) ?: S_MEM
        val masteryScale = calibration.masteryScale.takeIf(Double::isFinite)?.coerceIn(2.0, 14.0) ?: K_K
        val loadScale = calibration.loadScale.takeIf(Double::isFinite)?.coerceIn(2.0, 12.0) ?: LAMBDA_LOAD
        val intercept = calibration.intercept.takeIf(Double::isFinite)?.coerceIn(-2.0, 2.0) ?: 0.0
        val memory = memoryScale * Normal.invCdf(retrievability.coerceIn(1e-3, 1.0 - 1e-3))
        val masteryProbability = mastery?.probability?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.5
        val k = masteryScale * (masteryProbability - 0.5)
        val fatigue = state.fatigue.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val load = loadScale * fatigue
        val difficultyMu = itemDifficulty.elo.takeIf(Double::isFinite) ?: TrueSkill.MU0
        val difficultySigma = itemDifficulty.sigma.takeIf { it.isFinite() && it >= 0.0 } ?: TrueSkill.SIGMA0
        val scale = sqrt(2.0 * TrueSkill.BETA * TrueSkill.BETA + ability.variance + difficultySigma * difficultySigma)
        return Normal.cdf(intercept + (ability.mu + memory + k - difficultyMu - load) / scale).coerceIn(0.0, 1.0)
    }

    fun applyAbilityDelta(
        ratings: Map<AbilitySkill, SkillRating>,
        card: Card,
        delta: Double,
        now: Long = System.currentTimeMillis(),
        sigmaRatio: Double = 1.0
    ): List<SkillRating> {
        val weights = skillWeights(card)
        val boundedSigmaRatio = sigmaRatio.takeIf(Double::isFinite)?.coerceIn(0.01, 1.0) ?: 1.0
        val safeDelta = delta.takeIf(Double::isFinite) ?: 0.0
        return weights.map { (skill, weight) ->
            val current = ratings[skill] ?: SkillRating(skill.name.lowercase())
            current.copy(
                mu = (current.mu.takeIf(Double::isFinite) ?: 0.0) + 0.4 * safeDelta * weight,
                sigma = ((current.sigma.takeIf { it.isFinite() && it >= 0.0 } ?: TrueSkill.SIGMA0) * boundedSigmaRatio).coerceAtLeast(0.01 * TrueSkill.SIGMA0),
                observations = current.observations + 1,
                updatedAt = now
            )
        }
    }
}

object SuccessCalibrationFitter {
    const val MIN_SAMPLES = 120

    fun fit(samples: List<WorldModel.CalibrationSample>, initial: WorldModel.Calibration = WorldModel.Calibration()): WorldModel.Calibration {
        val safeInitial = WorldModel.Calibration(
            intercept = initial.intercept.takeIf(Double::isFinite)?.coerceIn(-2.0, 2.0) ?: 0.0,
            memoryScale = initial.memoryScale.takeIf(Double::isFinite)?.coerceIn(2.0, 10.0) ?: WorldModel.S_MEM,
            masteryScale = initial.masteryScale.takeIf(Double::isFinite)?.coerceIn(2.0, 14.0) ?: WorldModel.K_K,
            loadScale = initial.loadScale.takeIf(Double::isFinite)?.coerceIn(2.0, 12.0) ?: WorldModel.LAMBDA_LOAD,
            observations = initial.observations.coerceAtLeast(0)
        )
        val validSamples = samples.filter { sample ->
            sample.abilityMinusDifficulty.isFinite() && sample.memoryProbit.isFinite() &&
                sample.masteryCentered.isFinite() && sample.fatigue.isFinite() &&
                sample.scale.isFinite() && sample.scale > 0.0
        }
        if (validSamples.size < MIN_SAMPLES) return safeInitial
        val theta = doubleArrayOf(safeInitial.intercept, safeInitial.memoryScale, safeInitial.masteryScale, safeInitial.loadScale)
        repeat(240) {
            val gradient = DoubleArray(4)
            validSamples.forEach { sample ->
                val scale = sample.scale.coerceAtLeast(1e-3)
                val x = doubleArrayOf(1.0, sample.memoryProbit / scale, sample.masteryCentered / scale, -sample.fatigue / scale)
                val z = theta[0] + sample.abilityMinusDifficulty / scale +
                    theta[1] * x[1] + theta[2] * x[2] + theta[3] * x[3]
                val p = Normal.cdf(z).coerceIn(1e-6, 1.0 - 1e-6)
                val pdf = exp(-0.5 * z * z) / sqrt(2.0 * Math.PI)
                val error = (if (sample.correct) 1.0 else 0.0) - p
                val multiplier = error * pdf / (p * (1.0 - p))
                for (i in gradient.indices) gradient[i] += multiplier * x[i]
            }
            val rate = 0.025 / validSamples.size
            for (i in theta.indices) theta[i] += rate * gradient[i]
            theta[0] = theta[0].coerceIn(-2.0, 2.0)
            theta[1] = theta[1].coerceIn(2.0, 10.0)
            theta[2] = theta[2].coerceIn(2.0, 14.0)
            theta[3] = theta[3].coerceIn(2.0, 12.0)
        }
        return WorldModel.Calibration(theta[0], theta[1], theta[2], theta[3], validSamples.size)
    }
}

private fun Gaussian.sanitized(defaultMu: Double): Gaussian = Gaussian(
    mu = mu.takeIf(Double::isFinite) ?: defaultMu,
    sigma = sigma.takeIf { it.isFinite() && it >= 0.0 } ?: TrueSkill.SIGMA0
)
