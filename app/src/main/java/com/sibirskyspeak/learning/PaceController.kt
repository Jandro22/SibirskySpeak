package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.scheduler.FsrsScheduler
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

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
    val pReturn: Double
)

data class AdoptedPacePlan(
    val capacity: Int,
    val newBudget: Int,
    val retention: Double,
    val adaptiveTrust: Double = 0.35,
    val trustReason: String = "Cold-start settings prior"
)

data class AdaptiveEvidence(
    val completedSessions: Int = 0,
    val calibratedObservations: Int = 0,
    val capacitySigma: Double = TrueSkill.SIGMA0,
    val calibrationDrifted: Boolean = false
)

object AdaptiveTrustPolicy {
    fun trust(evidence: AdaptiveEvidence): Double {
        if (evidence.completedSessions <= 0 && evidence.calibratedObservations <= 0) return 0.35
        val sessionConfidence = 1.0 - exp(-evidence.completedSessions.coerceAtLeast(0) / 12.0)
        val calibrationConfidence = 1.0 - exp(-evidence.calibratedObservations.coerceAtLeast(0) / 80.0)
        val uncertaintyConfidence = ((TrueSkill.SIGMA0 - evidence.capacitySigma) / (TrueSkill.SIGMA0 - 2.0))
            .coerceIn(0.0, 1.0)
        val learnedConfidence = (0.50 * sessionConfidence + 0.30 * calibrationConfidence + 0.20 * uncertaintyConfidence)
            .coerceIn(0.0, 1.0)
        val trust = 0.35 + 0.65 * learnedConfidence
        return if (evidence.calibrationDrifted) trust.coerceAtMost(0.50) else trust
    }

    fun reason(evidence: AdaptiveEvidence, trust: Double): String = when {
        evidence.calibrationDrifted -> "Adaptive influence limited because recent predictions drifted"
        trust < 0.50 -> "Mostly using your chosen settings while the tutor learns your pace"
        trust < 0.80 -> "Blending your settings with ${evidence.completedSessions} completed adaptive sessions"
        else -> "Primarily using a well-supported personal pace model"
    }
}

data class PaceInputs(
    val capacity: CapacityBelief = CapacityBelief(),
    val willingness: WillingnessBelief = WillingnessBelief(),
    val willingnessObserved: Boolean = false,
    val returnContext: ReturnContext = ReturnContext(),
    val activeCards: List<Card> = emptyList(),
    val plannedNewFraction: Double = 0.0,
    val totalKnown: Int = 0,
    val recentAccuracy: Double = 0.85,
    val fatigue: Double = 0.0,
    val productionSigma: Double = TrueSkill.SIGMA0,
    val medianReviewMinutes: Double = 0.18,
    val sessionsPerDayExpected: Double = 1.0,
    val decay: Double = FsrsScheduler.decayOf(FsrsScheduler.DEFAULT_WEIGHTS),
    val tunedTargetRetention: Double? = null,
    val tunedNewBudgetScale: Double = 1.0,
    /** stablePace / requiredPace from the active learning goal (GoalMath.paceRatio),
     * or null with no active goal. Raises the new-item ceiling and floor below when
     * behind schedule; never shrinks them when ahead, and never touches
     * tunedTargetRetention or bypasses the safety guards further down. */
    val goalPaceRatio: Double? = null
)

object PaceController {
    private const val HORIZON_DAYS = 14
    private const val GROWTH = 2.5
    private const val TAU_RETURN = 0.80

    fun adoptForSessionSettings(
        pace: Pace,
        configuredSessionSize: Int,
        configuredNewCardsPerDay: Int,
        configuredRetention: Double,
        evidence: AdaptiveEvidence = AdaptiveEvidence(),
        adaptiveEnabled: Boolean = true
    ): AdoptedPacePlan {
        if (!adaptiveEnabled) return AdoptedPacePlan(
            capacity = configuredSessionSize.coerceAtLeast(1),
            newBudget = configuredNewCardsPerDay.coerceIn(0, configuredSessionSize.coerceAtLeast(1)),
            retention = configuredRetention.coerceIn(0.80, 0.95),
            adaptiveTrust = 0.0,
            trustReason = "Adaptive pacing paused; using your selected settings"
        )
        // Numeric settings are cold-start priors only. Once personal evidence exists,
        // executing a blend with hidden legacy sliders makes the one-button tutor learn
        // from and prescribe different policies indefinitely.
        val trust = AdaptiveTrustPolicy.trust(evidence)
        val hasAdaptiveSignal = evidence.completedSessions > 0 || evidence.calibratedObservations > 0
        val paceCapacity = (pace.reviewBudget + pace.newItemBudget).coerceAtLeast(1)
        val capacity = blendCount(configuredSessionSize.coerceAtLeast(1), paceCapacity, trust)
        val configuredNewBudget = configuredNewCardsPerDay.coerceAtLeast(0)
        val blendedNewBudget = blendCount(configuredNewBudget, pace.newItemBudget.coerceAtLeast(0), trust)
        val newBudget = if (hasAdaptiveSignal) {
            blendedNewBudget
        } else {
            blendedNewBudget.coerceAtMost(configuredNewBudget)
        }.coerceAtMost(capacity)
        val retention = blendDouble(configuredRetention.coerceIn(0.80, 0.95), pace.targetRetention, trust).coerceIn(0.80, 0.95)
        return AdoptedPacePlan(capacity, newBudget, retention, trust, AdaptiveTrustPolicy.reason(evidence, trust))
    }

    // Fixed, generous safety ceiling on new items per session — not a tunable
    // preset. The real regulator is the continuous demand/debt math below; this
    // just bounds worst-case cognitive load regardless of how favorable the
    // signals look (matches the old SPRINT doctrine's cap, kept as a constant
    // rather than a selectable intensity).
    private const val SAFETY_NEW_CAP = 40

    fun generatePace(inputs: PaceInputs, now: Long = System.currentTimeMillis()): Pace {
        val sustainable = inputs.capacity.sustainableMinutes.coerceAtLeast(5.0)
        val reviewMinutes = inputs.medianReviewMinutes.takeIf { it.isFinite() && it > 0.0 } ?: 0.18
        val decay = inputs.decay.takeIf { it.isFinite() && it in 0.05..1.0 }
            ?: FsrsScheduler.decayOf(FsrsScheduler.DEFAULT_WEIGHTS)
        val accuracy = inputs.recentAccuracy.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.85
        val fatigue = inputs.fatigue.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val sessionsPerDay = inputs.sessionsPerDayExpected.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val productionSigma = inputs.productionSigma.takeIf { it.isFinite() && it >= 0.0 } ?: TrueSkill.SIGMA0
        val recoveryLike = accuracy < 0.75 || fatigue > 0.65
        // Continuous replacements for the old Doctrine presets' three knobs — same
        // numeric ranges the presets used to span, now derived from live signals
        // instead of a hand-picked name so there's nothing to manually select.
        val debtToleranceMultiplier = (0.75 + 0.5 * accuracy - 0.3 * fatigue).coerceIn(0.5, 1.25)
        val productionBias = (-0.20 + 0.35 * accuracy).coerceIn(-0.20, 0.15)
        val delta = (debtDelta(inputs.totalKnown) * debtToleranceMultiplier).coerceIn(0.05, 0.95)
        val loadNow = reviewLoadNow(inputs.activeCards, reviewMinutes, decay)
        val debtPerNew = debtNew(reviewMinutes, decay)
        // Use an optimistic habit-forming prior only before the first willingness
        // observation. Once personal evidence exists, a low return forecast must be
        // respected rather than hidden behind the cold-start floor.
        val learnedPReturn = WillingnessModel.returnProbability(inputs.willingness, inputs.returnContext).coerceIn(0.0, 1.0)
        val pReturnBase = if (inputs.willingnessObserved) learnedPReturn else maxOf(0.86, learnedPReturn)
        val targetRetention = inputs.tunedTargetRetention?.takeIf(Double::isFinite)?.coerceIn(0.85, 0.95)
            ?: ReviewControl.optimalRetention((delta - currentDebtRatio(loadNow, 0, debtPerNew, sustainable, HORIZON_DAYS)).coerceAtLeast(0.0))
        val atRisk = atRisk(inputs.activeCards, now, targetRetention, decay)
        val forecast = dueForecast(inputs.activeCards, now, decay)
        val productionFraction = ((if (productionSigma < 4.0 && !recoveryLike) 0.45 else 0.25) + productionBias).coerceIn(0.0, 1.0)
        val demandProbe = SessionDemand(
            minutes = (sustainable * 0.55).coerceIn(5.0, 28.0),
            newFraction = inputs.plannedNewFraction.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0,
            productionFraction = productionFraction,
            hardness = 1.0 - accuracy,
            debtPressure = currentDebtRatio(loadNow, 0, debtPerNew, sustainable, HORIZON_DAYS).coerceIn(0.0, 1.0)
        )
        val capacityFit = CapacityModel.successProbability(inputs.capacity, demandProbe)
        val demandScale = (0.60 + 0.60 * capacityFit).coerceIn(0.60, 1.20)
        val t0 = (sustainable * (0.70 + 0.30 * capacityFit) * demandScale).coerceIn(5.0, 40.0)
        val tunedScale = inputs.tunedNewBudgetScale.takeIf(Double::isFinite)?.coerceIn(0.5, 1.5) ?: 1.0
        // A live goal behind schedule raises the ceiling (never shrinks it when
        // ahead — the multiplier floors at 1.0) up to GoalMath.PRESSURE_CEILING, the
        // exact bound the Settings feasibility verdict already promises the learner.
        // This composes multiplicatively with the existing tuned scale and is the
        // only place goal pressure enters — everything downstream (accuracyScaled's
        // recovery gating, SAFETY_NEW_CAP, and safeNewBudget's debt/pReturn/capacity
        // zeroing below) still runs unmodified afterward, so goal pressure can only
        // raise what those guards already allowed, never bypass them.
        val goalPressure = inputs.goalPaceRatio?.takeIf(Double::isFinite)
            ?.let { ratio -> (1.0 / ratio.coerceAtLeast(0.05)).coerceIn(1.0, GoalMath.PRESSURE_CEILING) } ?: 1.0
        val budgetScale = (tunedScale * goalPressure).coerceIn(0.5, 1.5 * GoalMath.PRESSURE_CEILING)
        val nMax = (maxNewItems(loadNow, debtPerNew, sustainable * sessionsPerDay, delta) * budgetScale).toInt().coerceAtLeast(0)
        val lookahead = SessionLookahead.choose(cap = nMax.coerceAtLeast(0), dueForecast = forecast, retention = targetRetention)
        val reviewBudget = minOf(atRisk.size, (t0 / reviewMinutes.coerceAtLeast(0.05)).toInt().coerceAtLeast(0))
        val accuracyScaled = when {
            recoveryLike -> 0
            accuracy < 0.82 -> nMax / 2
            else -> nMax
        }
        // Anti-stagnation floor: without a goal, a homeostat-only controller's sole
        // equilibrium is the minimum pace that preserves the habit, so soft comfort
        // signals alone can legitimately walk newBudget to zero. With a live goal AND
        // signals that are merely soft (not real fatigue/low-accuracy/low-capacity —
        // those still zero accuracyScaled/safeNewBudget via their own independent
        // checks), guarantee a small trickle instead of letting it settle at zero.
        val goalFloor = if (inputs.goalPaceRatio != null && !recoveryLike && capacityFit >= 0.55 && accuracy >= 0.82) {
            (nMax * 0.15).roundToInt().coerceIn(0, 3)
        } else 0
        val newBudget = maxOf(
            minOf(accuracyScaled, lookahead.newCards, SAFETY_NEW_CAP, ((t0 - reviewBudget * reviewMinutes) / 0.45).toInt().coerceAtLeast(0)),
            goalFloor
        )
        val debtRatio = currentDebtRatio(loadNow, newBudget, debtPerNew, sustainable, HORIZON_DAYS)
        val rawPReturn = (pReturnBase - 0.005 * (newBudget / 5.0) - 0.006 * (t0 / 20.0) - 0.12 * fatigue - 0.08 * (1.0 - capacityFit)).coerceIn(0.0, 1.0)
        // When the unconstrained candidate risks tomorrow, the controller chooses an
        // early clean finish. That habit-preserving action has positive return value;
        // account for it before enforcing the hard return constraint.
        val pReturn = if (rawPReturn < TAU_RETURN) {
            (rawPReturn + 0.12 * (1.0 - t0 / 40.0)).coerceIn(0.0, 1.0)
        } else rawPReturn
        val safeNewBudget = if (debtRatio >= delta || pReturn < TAU_RETURN || capacityFit < 0.45) 0 else newBudget
        val total = reviewBudget + safeNewBudget
        val reading = if (total > 0 && (debtRatio > delta * 0.8 || fatigue > 0.45 || capacityFit < 0.65)) {
            listOf(max(1, total / 2), total).distinct()
        } else emptyList()
        val stopPolicy = when {
            fatigue > 0.65 || accuracy < 0.75 || capacityFit < 0.45 -> StopPolicy.EARLY_STOP
            accuracy > 0.9 && fatigue < 0.5 && debtRatio < delta && pReturn >= TAU_RETURN && capacityFit >= 0.55 -> StopPolicy.STRETCH_ARMED
            else -> StopPolicy.CLEAN_STOP
        }
        return Pace(
            targetMinutes = t0,
            newItemBudget = safeNewBudget,
            reviewBudget = reviewBudget,
            targetRetention = targetRetention,
            targetDifficulty = (0.80 + 0.12 * capacityFit - 0.10 * fatigue).coerceIn(0.75, 0.95),
            productionRatio = productionFraction,
            readingInserts = reading,
            stretchStopPolicy = stopPolicy,
            debtRatio = currentDebtRatio(loadNow, safeNewBudget, debtPerNew, sustainable, HORIZON_DAYS),
            pReturn = pReturn
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

    private fun reviewLoadNow(cards: List<Card>, reviewMinutes: Double, decay: Double): Double =
        cards.filter { it.state != CardState.NEW && it.state != CardState.GRADUATED }.sumOf { card ->
            reviewMinutes / max(intervalFor(card.stability.coerceAtLeast(0.1), 0.88, decay), 0.5)
        }

    private fun debtNew(reviewMinutes: Double, decay: Double): Double {
        val i0 = intervalFor(1.0, 0.88, decay).coerceAtLeast(0.5)
        val reviews = floor(ln(HORIZON_DAYS / i0) / ln(GROWTH)).toInt() + 1
        return reviewMinutes * reviews.coerceAtLeast(1)
    }

    private fun intervalFor(stability: Double, retention: Double, decay: Double): Double {
        val factor = FsrsScheduler.factorOf(decay)
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
            val interval = intervalFor(card.stability.coerceAtLeast(0.1), 0.88, decay).coerceAtLeast(1.0).roundToInt()
            val elapsed = ((now - (card.lastReview ?: now)).coerceAtLeast(0) / 86_400_000.0).roundToInt()
            val dueDay = (interval - elapsed).coerceIn(1, HORIZON_DAYS)
            counts[dueDay - 1] += 1
        }
        return counts.toList()
    }

    private fun blendCount(configured: Int, generated: Int, trust: Double): Int =
        blendDouble(configured.toDouble(), generated.toDouble(), trust).roundToInt().coerceAtLeast(0)

    private fun blendDouble(configured: Double, generated: Double, trust: Double): Double {
        val boundedTrust = trust.coerceIn(0.0, 1.0)
        return configured * (1.0 - boundedTrust) + generated * boundedTrust
    }
}
