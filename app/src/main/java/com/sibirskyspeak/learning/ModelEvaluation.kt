package com.sibirskyspeak.learning

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

data class PredictionObservation(
    val predicted: Double,
    val recalled: Boolean,
    val segment: String = "all",
    val at: Long = 0L,
    val cefrLevel: String? = null,
    val modelVersion: Int = 0
)

data class CalibrationExposure(
    val sample: WorldModel.CalibrationSample,
    val predicted: Double,
    val modelVersion: Int,
    val cefrLevel: String?,
    val capturedAt: Long
)

data class CalibrationBin(val lower: Double, val upper: Double, val count: Int, val predicted: Double, val observed: Double)

data class CalibrationReport(
    val count: Int,
    val brier: Double,
    val logLoss: Double,
    val expectedCalibrationError: Double,
    val calibrationBias: Double,
    val bins: List<CalibrationBin>,
    val bySegment: Map<String, CalibrationReport> = emptyMap()
)

data class DriftReport(
    val reference: CalibrationReport,
    val recent: CalibrationReport,
    val brierDelta: Double,
    val biasDelta: Double,
    val drifted: Boolean
)

/** Proper-scoring-rule diagnostics used both by replay evaluation and production
 * promotion gates. Inputs are sanitized so diagnostics themselves cannot be poisoned. */
object CalibrationDiagnostics {
    fun report(observations: List<PredictionObservation>, binCount: Int = 10, segmentBreakdown: Boolean = true): CalibrationReport {
        val binsN = binCount.coerceIn(2, 50)
        val clean = observations.map { it.copy(predicted = it.predicted.takeIf(Double::isFinite)?.coerceIn(1e-6, 1.0 - 1e-6) ?: 0.5) }
        if (clean.isEmpty()) return CalibrationReport(0, 0.0, 0.0, 0.0, 0.0, emptyList())
        val bins = (0 until binsN).mapNotNull { index ->
            val lower = index.toDouble() / binsN
            val upper = (index + 1).toDouble() / binsN
            val members = clean.filter { if (index == binsN - 1) it.predicted in lower..upper else it.predicted >= lower && it.predicted < upper }
            if (members.isEmpty()) null else CalibrationBin(lower, upper, members.size, members.map { it.predicted }.average(), members.count { it.recalled }.toDouble() / members.size)
        }
        val brier = clean.map { val y = if (it.recalled) 1.0 else 0.0; (it.predicted - y) * (it.predicted - y) }.average()
        val logLoss = -clean.map { if (it.recalled) ln(it.predicted) else ln(1.0 - it.predicted) }.average()
        val ece = bins.sumOf { it.count.toDouble() / clean.size * abs(it.predicted - it.observed) }
        val bias = clean.map { it.predicted }.average() - clean.count { it.recalled }.toDouble() / clean.size
        val segments = if (segmentBreakdown) buildMap<String, MutableList<PredictionObservation>> {
            clean.forEach { observation ->
                getOrPut(observation.segment) { mutableListOf() }.add(observation)
                observation.cefrLevel?.takeIf { it.isNotBlank() }?.let { level ->
                    getOrPut("CEFR:$level") { mutableListOf() }.add(observation)
                }
            }
        // Tiny slices look precise but carry almost no stable information. Global
        // diagnostics retain every row; segmented reports wait for a descriptive
        // minimum before presenting an estimate.
        }.filterValues { it.size >= MIN_SEGMENT_OBSERVATIONS }
            .mapValues { report(it.value, binsN, false) } else emptyMap()
        return CalibrationReport(clean.size, brier, logLoss, ece, bias, bins, segments)
    }

    fun drift(reference: List<PredictionObservation>, recent: List<PredictionObservation>, brierTolerance: Double = 0.03, biasTolerance: Double = 0.08): DriftReport {
        val a = report(reference)
        val b = report(recent)
        val brierDelta = b.brier - a.brier
        val biasDelta = b.calibrationBias - a.calibrationBias
        return DriftReport(a, b, brierDelta, biasDelta,
            a.count >= 30 && b.count >= 30 && (brierDelta > brierTolerance.coerceAtLeast(0.0) || abs(biasDelta) > biasTolerance.coerceAtLeast(0.0)))
    }

    fun driftByVersionOrTime(observations: List<PredictionObservation>): DriftReport? {
        if (observations.size < 60) return null
        val ordered = observations.sortedBy { it.at }
        val byVersion = ordered.groupBy { it.modelVersion }
        val latestVersion = ordered.last().modelVersion
        val latest = byVersion[latestVersion].orEmpty()
        val prior = ordered.filter { it.modelVersion != latestVersion }
        return if (latest.size >= 30 && prior.size >= 30) {
            drift(prior, latest)
        } else {
            val split = (ordered.size * 2 / 3).coerceIn(30, ordered.size - 30)
            drift(ordered.take(split), ordered.drop(split))
        }
    }

    private const val MIN_SEGMENT_OBSERVATIONS = 20
}

data class LearnerProfile(
    val ability: Double,
    val memory: Double,
    val dailyMinutes: Double,
    val fatigueSensitivity: Double,
    val returnBase: Double
)

data class SimulationPolicy(
    val name: String,
    val targetRetention: Double,
    val newPerDay: Int,
    val uncertaintyWeight: Double = 0.0,
    val fatigueProtection: Boolean = true
)

data class SimulationResult(
    val policy: String,
    val learners: Int,
    val days: Int,
    val recallRate: Double,
    val learnedItems: Double,
    val reviews: Double,
    val minutes: Double,
    val returnRate: Double,
    val overloadRate: Double,
    val utility: Double,
    val endingBacklog: Double = 0.0
)

/** Deterministic seeded population replay. It is intentionally model-agnostic: its
 * job is to reject policies that buy recall by exploding workload or attrition. */
object PopulationSimulator {
    fun profiles(count: Int, seed: Int): List<LearnerProfile> {
        val random = Random(seed)
        return List(count.coerceAtLeast(0)) {
            LearnerProfile(
                ability = random.nextDouble(0.65, 1.25), memory = random.nextDouble(0.65, 1.35),
                dailyMinutes = random.nextDouble(6.0, 35.0), fatigueSensitivity = random.nextDouble(0.5, 1.5),
                returnBase = random.nextDouble(0.82, 0.99)
            )
        }
    }

    fun run(profiles: List<LearnerProfile>, policy: SimulationPolicy, days: Int = 90, seed: Int = 1): SimulationResult {
        if (profiles.isEmpty() || days <= 0) return SimulationResult(policy.name, profiles.size, days.coerceAtLeast(0), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        var correct = 0; var reviews = 0; var learned = 0.0; var minutes = 0.0; var returns = 0; var opportunities = 0; var overloads = 0; var endingBacklog = 0.0
        profiles.forEachIndexed { learnerIndex, learner ->
            val random = Random(seed.toLong() * 1_000_003L + learnerIndex)
            var active = 0.0
            var backlog = 0.0
            repeat(days) { day ->
                opportunities++
                // For an exponential forgetting approximation, review frequency is
                // proportional to 1 / -ln(target retention): demanding higher recall
                // means shorter intervals and therefore MORE reviews. The previous
                // (1-rho) relation inverted this essential scheduling economics.
                val rho = policy.targetRetention.coerceIn(.75, .98)
                val dueReviews = active * 0.012 / -ln(rho) + backlog
                val plannedNew = policy.newPerDay.coerceAtLeast(0).toDouble()
                val capacity = learner.dailyMinutes / 0.32
                val rawLoad = dueReviews + plannedNew
                val expectedLoad = if (policy.fatigueProtection) minOf(rawLoad, capacity) else rawLoad
                val requiredMinutes = expectedLoad * 0.32
                val overload = (requiredMinutes / learner.dailyMinutes.coerceAtLeast(1.0) - 1.0).coerceAtLeast(0.0) * learner.fatigueSensitivity
                if (overload > 0.25) overloads++
                val pReturn = (learner.returnBase - overload * 0.18).coerceIn(0.0, 1.0)
                if (random.nextDouble() > pReturn) return@repeat
                returns++
                val todayReviews = minOf(dueReviews, capacity).toInt().coerceAtLeast(0)
                val introduced = minOf(plannedNew, (capacity - todayReviews).coerceAtLeast(0.0))
                backlog = (dueReviews - todayReviews).coerceAtLeast(0.0)
                val exploration = policy.uncertaintyWeight.coerceIn(0.0, .30)
                val calibrationGain = exploration * 0.035 * (1.0 - kotlin.math.exp(-day / 21.0))
                val explorationCost = exploration * 0.020
                val success = (policy.targetRetention * learner.ability * learner.memory + calibrationGain - explorationCost - overload * 0.08).coerceIn(0.05, 0.995)
                repeat(todayReviews) { if (random.nextDouble() < success) correct++ }
                reviews += todayReviews
                learned += introduced * success
                active += introduced * success
                minutes += (todayReviews + introduced) * 0.32
            }
            endingBacklog += backlog
        }
        val utility = learned - reviews * 0.018 - overloads * 0.12 - endingBacklog * 0.08 + returns * 0.01
        return SimulationResult(policy.name, profiles.size, days, correct.toDouble() / reviews.coerceAtLeast(1), learned / profiles.size,
            reviews.toDouble() / profiles.size, minutes / profiles.size, returns.toDouble() / opportunities, overloads.toDouble() / opportunities,
            utility / profiles.size, endingBacklog / profiles.size)
    }
}

data class PolicyComparison(
    val candidate: SimulationResult,
    val baseline: SimulationResult,
    val utilityLift: Double,
    val recallLift: Double,
    val workloadDelta: Double,
    val safeToPromote: Boolean,
    val utilityLiftLower95: Double = utilityLift,
    val worstCapacityQuartileLift: Double = utilityLift
)

object CounterfactualEvaluator {
    fun compare(profiles: List<LearnerProfile>, baseline: SimulationPolicy, candidate: SimulationPolicy, days: Int = 90, seed: Int = 1): PolicyComparison {
        val a = PopulationSimulator.run(profiles, baseline, days, seed)
        val b = PopulationSimulator.run(profiles, candidate, days, seed)
        val utilityLift = b.utility - a.utility
        val recallLift = b.recallRate - a.recallRate
        val workloadDelta = b.minutes - a.minutes
        val paired = profiles.mapIndexed { index, profile ->
            val localSeed = seed + index * 31
            PopulationSimulator.run(listOf(profile), candidate, days, localSeed).utility -
                PopulationSimulator.run(listOf(profile), baseline, days, localSeed).utility
        }
        val mean = paired.average().takeIf(Double::isFinite) ?: utilityLift
        val variance = if (paired.size > 1) paired.sumOf { (it - mean) * (it - mean) } / (paired.size - 1) else 0.0
        val lower95 = mean - 1.96 * sqrt(variance / paired.size.coerceAtLeast(1))
        val sortedByCapacity = profiles.indices.sortedBy { profiles[it].dailyMinutes }
        val quartileSize = (profiles.size / 4).coerceAtLeast(1)
        val worstQuartile = sortedByCapacity.chunked(quartileSize).minOfOrNull { indices -> indices.map { paired[it] }.average() } ?: mean
        return PolicyComparison(b, a, utilityLift, recallLift, workloadDelta,
            lower95 > 0.0 && b.returnRate >= a.returnRate - 0.01 && b.overloadRate <= a.overloadRate + 0.01 &&
                b.endingBacklog <= a.endingBacklog + 1.0 && worstQuartile >= -0.05,
            lower95, worstQuartile)
    }
}

object UncertaintyAwareSelection {
    /** Bounded information-value term: uncertain items are explored only near the
     * desired-success frontier, never strongly enough to outrank truly urgent work. */
    fun utility(predictedSuccess: Double, sigma: Double, targetSuccess: Double, weight: Double = 0.18): Double {
        val p = predictedSuccess.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.5
        val s = sigma.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(TrueSkill.SIGMA0) ?: TrueSkill.SIGMA0
        val target = targetSuccess.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.85
        val frontier = (1.0 - abs(p - target)).coerceIn(0.0, 1.0)
        return weight.takeIf(Double::isFinite)?.coerceIn(0.0, 0.30)?.times(s / TrueSkill.SIGMA0 * frontier) ?: 0.0
    }
}

data class TuningResult(val policy: SimulationPolicy, val comparison: PolicyComparison, val candidatesEvaluated: Int)
data class StagedTuning(val version: Int, val tuning: TuningResult, val decision: PromotionDecision, val promoted: Boolean)

object ReplayParameterTuner {
    fun tune(profiles: List<LearnerProfile>, baseline: SimulationPolicy, retentions: List<Double>, newCaps: List<Int>, uncertaintyWeights: List<Double>, days: Int = 90, seed: Int = 1): TuningResult {
        val candidates = retentions.flatMap { retention -> newCaps.flatMap { cap -> uncertaintyWeights.map { u ->
            SimulationPolicy("tuned-r${"%.2f".format(java.util.Locale.ROOT, retention)}-n$cap-u$u", retention.coerceIn(.80, .95), cap.coerceAtLeast(0), u.coerceIn(0.0, .3))
        } } }
        require(candidates.isNotEmpty()) { "at least one tuning candidate is required" }
        val comparisons = candidates.map { it to CounterfactualEvaluator.compare(profiles, baseline, it, days, seed) }
        val best = comparisons.maxWith(compareBy<Pair<SimulationPolicy, PolicyComparison>> { it.second.safeToPromote }.thenBy { it.second.utilityLift })
        return TuningResult(best.first, best.second, candidates.size)
    }
}

data class ModelSnapshot(val version: Int, val parameters: Map<String, Double>, val createdAt: Long, val parentVersion: Int? = null)
data class PromotionDecision(val promote: Boolean, val reasons: List<String>)

/** Pure governance rules; Room persistence uses namespaced OptimizerParameter keys,
 * allowing atomic-ish snapshot staging without a schema migration. */
object ModelGovernance {
    const val CURRENT_VERSION_KEY = "model:current_version"
    fun snapshotKey(version: Int, parameter: String) = "model:snapshot:$version:$parameter"

    fun validate(snapshot: ModelSnapshot): List<String> = buildList {
        if (snapshot.version < 1) add("version must be positive")
        if (snapshot.parameters.isEmpty()) add("snapshot has no parameters")
        snapshot.parameters.forEach { (key, value) ->
            if (key.isBlank()) add("blank parameter key")
            if (!value.isFinite()) add("$key is non-finite")
        }
    }

    fun promotionDecision(comparison: PolicyComparison, calibration: DriftReport?, requireCalibration: Boolean = true): PromotionDecision {
        val reasons = buildList {
            if (!comparison.safeToPromote) add("counterfactual guardrails failed")
            if (comparison.utilityLift <= 0.0) add("no positive utility lift")
            if (comparison.worstCapacityQuartileLift < -0.05) add("capacity subgroup regressed")
            if (requireCalibration && calibration == null) add("insufficient live calibration evidence")
            if (calibration?.drifted == true) add("calibration drift detected")
            if (calibration != null && calibration.recent.brier > calibration.reference.brier + 0.03) add("Brier score regressed")
        }
        return PromotionDecision(reasons.isEmpty(), reasons)
    }

    fun rollback(current: ModelSnapshot, history: List<ModelSnapshot>): ModelSnapshot? =
        current.parentVersion?.let { parent -> history.firstOrNull { it.version == parent && validate(it).isEmpty() } }
            ?: history.filter { it.version < current.version && validate(it).isEmpty() }.maxByOrNull { it.version }

    fun versionsToRetain(history: List<ModelSnapshot>, currentVersion: Int?, limit: Int = 24): Set<Int> {
        val valid = history.filter { validate(it).isEmpty() }
        val keep = valid.sortedByDescending { it.version }.take(limit.coerceAtLeast(1)).mapTo(linkedSetOf()) { it.version }
        var cursor = currentVersion
        val visited = mutableSetOf<Int>()
        while (cursor != null && visited.add(cursor)) {
            keep += cursor
            cursor = valid.firstOrNull { it.version == cursor }?.parentVersion
        }
        return keep
    }
}

data class BenchmarkResult(val operations: Int, val elapsedNanos: Long) {
    val operationsPerSecond: Double get() = operations * 1_000_000_000.0 / max(1L, elapsedNanos)
}

object ModelBenchmark {
    inline fun measure(operations: Int, block: (Int) -> Unit): BenchmarkResult {
        val count = operations.coerceAtLeast(0)
        val start = System.nanoTime()
        repeat(count, block)
        return BenchmarkResult(count, System.nanoTime() - start)
    }
}
