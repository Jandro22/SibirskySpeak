package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.PaceLog
import com.sibirskyspeak.scheduler.FsrsScheduler
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Named per-doctrine tuning knobs, in addition to the new-card cap. Values are
 * ordered consistently with [doctrineNewCap]'s existing intensity ranking
 * (RECOVERY < CONSERVE < BALANCED < AMBITIOUS < SPRINT).
 *
 * @param debtTolerance multiplies [debtDelta] — how much future review load this
 *   doctrine is willing to bank before it stops offering new material.
 * @param productionBias additive nudge to the production-vs-recognition ratio
 *   ([Pace.productionRatio] and the production term in the internal demand probe).
 * @param demandScale multiplies the sustainable-minutes target ([Pace.targetMinutes]'s
 *   base before capacity-fit blending) — how large a session this doctrine reaches for.
 */
enum class Doctrine(val doctrineNewCap: Int, val debtTolerance: Double, val productionBias: Double, val demandScale: Double) {
    BALANCED(15, 1.00, 0.00, 1.00),
    CONSERVE(8, 0.75, -0.10, 0.85),
    AMBITIOUS(24, 1.15, 0.10, 1.10),
    SPRINT(30, 1.25, 0.15, 1.20),
    RECOVERY(0, 0.50, -0.20, 0.60)
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

data class AdoptedPacePlan(
    val capacity: Int,
    val newBudget: Int,
    val retention: Double,
    val mode: SessionMode
)

data class PaceInputs(
    val capacity: CapacityBelief = CapacityBelief(),
    val willingness: WillingnessBelief = WillingnessBelief(),
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
    val tunedNewBudgetScale: Double = 1.0
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
        hasAdaptiveSignal: Boolean
    ): AdoptedPacePlan {
        // Personal evidence should steer the user's settings, not erase them. Full
        // trust created a self-reinforcing tiny-session loop after a few short runs.
        val trust = if (hasAdaptiveSignal) 0.70 else 0.35
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
        val mode = when {
            pace.stretchStopPolicy == StopPolicy.EARLY_STOP -> SessionMode.QUICK
            hasAdaptiveSignal && pace.stretchStopPolicy == StopPolicy.STRETCH_ARMED -> SessionMode.STRETCH
            else -> SessionMode.FULL
        }
        return AdoptedPacePlan(capacity, newBudget, retention, mode)
    }

    fun generatePace(inputs: PaceInputs, doctrine: Doctrine = Doctrine.BALANCED, now: Long = System.currentTimeMillis()): Pace {
        val sustainable = inputs.capacity.sustainableMinutes.coerceAtLeast(5.0)
        val reviewMinutes = inputs.medianReviewMinutes.takeIf { it.isFinite() && it > 0.0 } ?: 0.18
        val decay = inputs.decay.takeIf { it.isFinite() && it in 0.05..1.0 }
            ?: FsrsScheduler.decayOf(FsrsScheduler.DEFAULT_WEIGHTS)
        val accuracy = inputs.recentAccuracy.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.85
        val fatigue = inputs.fatigue.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val sessionsPerDay = inputs.sessionsPerDayExpected.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val productionSigma = inputs.productionSigma.takeIf { it.isFinite() && it >= 0.0 } ?: TrueSkill.SIGMA0
        val delta = (debtDelta(inputs.totalKnown) * doctrine.debtTolerance).coerceIn(0.05, 0.95)
        val loadNow = reviewLoadNow(inputs.activeCards, reviewMinutes, decay)
        val debtPerNew = debtNew(reviewMinutes, decay)
        val pReturnBase = maxOf(0.86, WillingnessModel.returnProbability(inputs.willingness, inputs.returnContext))
        val targetRetention = inputs.tunedTargetRetention?.takeIf(Double::isFinite)?.coerceIn(0.85, 0.95)
            ?: ReviewControl.optimalRetention((delta - currentDebtRatio(loadNow, 0, debtPerNew, sustainable, HORIZON_DAYS)).coerceAtLeast(0.0))
        val atRisk = atRisk(inputs.activeCards, now, targetRetention, decay)
        val forecast = dueForecast(inputs.activeCards, now, decay)
        val productionFraction = ((if (productionSigma < 4.0 && doctrine != Doctrine.RECOVERY) 0.45 else 0.25) + doctrine.productionBias).coerceIn(0.0, 1.0)
        val demandProbe = SessionDemand(
            minutes = (sustainable * 0.55).coerceIn(5.0, 28.0),
            newFraction = inputs.plannedNewFraction.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0,
            productionFraction = productionFraction,
            hardness = 1.0 - accuracy,
            debtPressure = currentDebtRatio(loadNow, 0, debtPerNew, sustainable, HORIZON_DAYS).coerceIn(0.0, 1.0)
        )
        val capacityFit = CapacityModel.successProbability(inputs.capacity, demandProbe)
        val t0 = (sustainable * (0.70 + 0.30 * capacityFit) * doctrine.demandScale).coerceIn(5.0, 40.0)
        val budgetScale = inputs.tunedNewBudgetScale.takeIf(Double::isFinite)?.coerceIn(0.5, 1.5) ?: 1.0
        val nMax = (maxNewItems(loadNow, debtPerNew, sustainable * sessionsPerDay, delta) * budgetScale).toInt().coerceAtLeast(0)
        val lookahead = SessionLookahead.choose(cap = nMax.coerceAtLeast(0), dueForecast = forecast, retention = targetRetention)
        val reviewBudget = minOf(atRisk.size, (t0 / reviewMinutes.coerceAtLeast(0.05)).toInt().coerceAtLeast(0))
        val accuracyScaled = when {
            accuracy < 0.75 || doctrine == Doctrine.RECOVERY || fatigue > 0.65 -> 0
            accuracy < 0.82 -> nMax / 2
            else -> nMax
        }
        val newBudget = minOf(accuracyScaled, lookahead.newCards, doctrine.doctrineNewCap, ((t0 - reviewBudget * reviewMinutes) / 0.45).toInt().coerceAtLeast(0))
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

enum class NudgeDirection { EASIER, HARDER }

data class DoctrineNudge(
    val direction: NudgeDirection,
    val suggested: Doctrine,
    val reason: String
)

/**
 * [PaceController.generatePace] already silently damps the daily new-card cap
 * session to session (see `adaptDailyLoad`), but a learner stuck on the wrong
 * [Doctrine] entirely — repeatedly cutting sessions short, or repeatedly
 * finishing with room to spare — has no visible signal that a coarser lever
 * exists. This reads the same [PaceLog] history the dashboard already
 * persists and proposes (never applies) a one-step doctrine change when a
 * majority of recent sessions land on the same extreme, so a run of one or
 * two hard days doesn't nag — see [MIN_SAMPLES]/[TRIGGER_FRACTION].
 */
object DoctrineAdvisor {
    // Ordered by intensity (matches Doctrine's own doctrineNewCap ordering, not
    // its declaration order): a "step" moves exactly one entry either way.
    private val INTENSITY = listOf(Doctrine.RECOVERY, Doctrine.CONSERVE, Doctrine.BALANCED, Doctrine.AMBITIOUS, Doctrine.SPRINT)
    private const val MIN_SAMPLES = 4
    private const val SAMPLE_WINDOW = 5
    private const val TRIGGER_FRACTION = 0.75

    fun suggest(recentLogsNewestFirst: List<PaceLog>, currentDoctrine: Doctrine): DoctrineNudge? {
        val sample = recentLogsNewestFirst.take(SAMPLE_WINDOW)
        if (sample.size < MIN_SAMPLES) return null
        val idx = INTENSITY.indexOf(currentDoctrine)
        if (idx < 0) return null
        val threshold = (sample.size * TRIGGER_FRACTION)
        val early = sample.count { it.modeChosen == SessionMode.QUICK.name }
        val stretch = sample.count { it.modeChosen == SessionMode.STRETCH.name }
        return when {
            early >= threshold && idx > 0 -> {
                val next = INTENSITY[idx - 1]
                DoctrineNudge(
                    NudgeDirection.EASIER, next,
                    "Your last ${sample.size} sessions cut short more often than not. " +
                        "Switching to ${next.name.lowercase().replaceFirstChar(Char::uppercase)} eases the daily load."
                )
            }
            stretch >= threshold && idx < INTENSITY.lastIndex -> {
                val next = INTENSITY[idx + 1]
                DoctrineNudge(
                    NudgeDirection.HARDER, next,
                    "Your last ${sample.size} sessions finished with room to spare. " +
                        "Switching to ${next.name.lowercase().replaceFirstChar(Char::uppercase)} raises the pace."
                )
            }
            else -> null
        }
    }
}
