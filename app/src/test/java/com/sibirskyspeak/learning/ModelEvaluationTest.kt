package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.ItemDifficulty
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.scheduler.FsrsScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ModelEvaluationTest {
    @Test fun `randomized engine invariants hold across ten thousand hostile states`() {
        val random = Random(918273)
        repeat(10_000) {
            fun hostile(): Double = when (random.nextInt(30)) {
                0 -> Double.NaN; 1 -> Double.POSITIVE_INFINITY; 2 -> Double.NEGATIVE_INFINITY
                else -> random.nextDouble(-1_000.0, 1_000.0)
            }
            val retention = FsrsScheduler.retrievabilityOf(hostile(), hostile(), hostile())
            assertTrue(retention.isFinite() && retention in 0.0..1.0)
            val mastery = MasteryModel.update(hostile(), random.nextBoolean(), hostile(), hostile(), hostile())
            assertTrue(mastery.isFinite() && mastery in .001.. .999)
            val capacity = CapacityModel.successProbability(
                CapacityBelief(hostile(), hostile()), SessionDemand(hostile(), hostile(), hostile(), hostile(), hostile())
            )
            assertTrue(capacity.isFinite() && capacity in 0.0..1.0)
            val uncertainty = UncertaintyAwareSelection.utility(hostile(), hostile(), hostile(), hostile())
            assertTrue(uncertainty.isFinite() && uncertainty in 0.0..0.30)
            val update = TrueSkill.update(Gaussian(hostile(), hostile()), Gaussian(hostile(), hostile()), MatchOutcome.entries.random(random))
            assertTrue(listOf(update.a.mu, update.a.sigma, update.b.mu, update.b.sigma).all(Double::isFinite))
        }
    }

    @Test fun `forgetting curve is bounded and monotonic over time and stability`() {
        for (stability in listOf(.1, 1.0, 10.0, 100.0)) {
            var previous = 1.0
            for (day in 0..365) {
                val r = FsrsScheduler.retrievabilityOf(day.toDouble(), stability, .1542)
                assertTrue(r <= previous + 1e-12)
                previous = r
            }
        }
        for (day in listOf(1.0, 10.0, 100.0)) {
            assertTrue(FsrsScheduler.retrievabilityOf(day, 20.0, .1542) > FsrsScheduler.retrievabilityOf(day, 2.0, .1542))
        }
    }

    @Test fun `calibration reports proper scores segments and detects drift`() {
        val calibrated = List(1_000) { i ->
            val p = (i % 10 + .5) / 10.0
            PredictionObservation(p, (i * 37 % 100) < (p * 100).toInt(), if (i % 2 == 0) "VOCAB" else "LISTENING", i.toLong(), if (i % 3 == 0) "A1" else "B1")
        }
        val report = CalibrationDiagnostics.report(calibrated)
        assertEquals(1_000, report.count)
        assertTrue(report.brier in 0.0..1.0 && report.logLoss > 0.0)
        assertTrue(report.expectedCalibrationError < .08)
        assertEquals(setOf("VOCAB", "LISTENING", "CEFR:A1", "CEFR:B1"), report.bySegment.keys)

        val degraded = List(300) { PredictionObservation(.95, it % 2 == 0) }
        val drift = CalibrationDiagnostics.drift(calibrated.take(300), degraded)
        assertTrue(drift.drifted)
        assertTrue(drift.brierDelta > 0.03)

        val versioned = List(40) { PredictionObservation(.5, it % 2 == 0, at = it.toLong(), modelVersion = 1) } +
            List(40) { PredictionObservation(.99, it % 2 == 0, at = (100 + it).toLong(), modelVersion = 2) }
        val versionDrift = CalibrationDiagnostics.driftByVersionOrTime(versioned)
        assertTrue(versionDrift != null && versionDrift.drifted)
    }

    @Test fun `paired counterfactual replay is deterministic and guards workload and return`() {
        val profiles = PopulationSimulator.profiles(500, 42)
        val baseline = SimulationPolicy("baseline", .86, 8)
        val candidate = SimulationPolicy("candidate", .89, 7, uncertaintyWeight = .15)
        val first = CounterfactualEvaluator.compare(profiles, baseline, candidate, 120, 99)
        val second = CounterfactualEvaluator.compare(profiles, baseline, candidate, 120, 99)
        assertEquals(first, second)
        assertTrue(first.candidate.recallRate in 0.0..1.0)
        assertTrue(first.candidate.returnRate in 0.0..1.0)
        assertTrue(first.candidate.overloadRate in 0.0..1.0)
        assertTrue(first.worstCapacityQuartileLift.isFinite())
    }

    @Test fun `simulator charges introductions once and higher retention increases review load`() {
        val profiles = List(200) { LearnerProfile(1.0, 1.0, 120.0, 1.0, 1.0) }
        val low = PopulationSimulator.run(profiles, SimulationPolicy("low", .85, 8), 180, 5)
        val high = PopulationSimulator.run(profiles, SimulationPolicy("high", .95, 8), 180, 5)
        assertTrue("higher retention must cost more reviews", high.reviews > low.reviews)

        val oneDay = PopulationSimulator.run(
            listOf(LearnerProfile(1.0, 1.0, 120.0, 1.0, 1.0)),
            SimulationPolicy("new-only", .9, 10), 1, 1
        )
        assertEquals("ten introductions at 0.32 minutes each", 3.2, oneDay.minutes, 1e-9)
        assertEquals("new cards are not mature reviews", 0.0, oneDay.reviews, 0.0)

        val constrained = PopulationSimulator.run(
            listOf(LearnerProfile(1.0, 1.0, 2.0, 1.0, 1.0)),
            SimulationPolicy("constrained", .95, 30), 90, 2
        )
        assertTrue("unfinished due work must be conserved as backlog", constrained.endingBacklog > 0.0)
    }

    @Test fun `replay tuner evaluates full grid and returns its best guarded policy`() {
        val profiles = PopulationSimulator.profiles(250, 7)
        val baseline = SimulationPolicy("baseline", .86, 8)
        val result = ReplayParameterTuner.tune(
            profiles, baseline, listOf(.85, .88, .90), listOf(5, 8, 12), listOf(0.0, .15), days = 60, seed = 8
        )
        assertEquals(18, result.candidatesEvaluated)
        assertTrue(result.policy.targetRetention in .80.. .95)
        assertTrue(result.comparison.candidate.utility.isFinite())
    }

    @Test fun `uncertainty exploration is bounded and concentrated near learning frontier`() {
        val near = UncertaintyAwareSelection.utility(.84, TrueSkill.SIGMA0, .85)
        val far = UncertaintyAwareSelection.utility(.10, TrueSkill.SIGMA0, .85)
        val known = UncertaintyAwareSelection.utility(.84, .1, .85)
        assertTrue(near > far)
        assertTrue(near > known)
        assertTrue(near <= .30)
    }

    @Test fun `promoted tuning parameters directly control production pace`() {
        val tuned = PaceController.generatePace(PaceInputs(
            capacity = CapacityBelief(25.0, 2.0), recentAccuracy = .9,
            tunedTargetRetention = .92, tunedNewBudgetScale = .5
        ))
        assertEquals(.92, tuned.targetRetention, 0.0)
        assertTrue(tuned.newItemBudget >= 0)
    }

    @Test fun `sample prediction is finite and responds to memory evidence`() {
        val weak = WorldModel.CalibrationSample(true, 0.0, -1.0, 0.0, 0.0, 10.0)
        val strong = weak.copy(memoryProbit = 1.0)
        assertTrue(WorldModel.predictedProbability(strong) > WorldModel.predictedProbability(weak))
        assertEquals(.5, WorldModel.predictedProbability(weak.copy(scale = Double.NaN)), 0.0)
    }

    @Test fun `model governance rejects bad snapshots gates promotion and rolls back`() {
        val v1 = ModelSnapshot(1, mapOf("target_retention" to .86), 1L)
        val v2 = ModelSnapshot(2, mapOf("target_retention" to .89), 2L, parentVersion = 1)
        assertTrue(ModelGovernance.validate(v1).isEmpty())
        assertFalse(ModelGovernance.validate(ModelSnapshot(0, mapOf("x" to Double.NaN), 0)).isEmpty())

        val baseline = SimulationResult("a", 1, 1, .85, 5.0, 10.0, 3.0, .9, .1, 4.0)
        val candidate = baseline.copy(policy = "b", utility = 5.0, returnRate = .9, overloadRate = .1)
        val comparison = PolicyComparison(candidate, baseline, 1.0, 0.0, 0.0, true)
        assertFalse(ModelGovernance.promotionDecision(comparison, null).promote)
        assertTrue(ModelGovernance.promotionDecision(comparison, null, requireCalibration = false).promote)
        assertEquals(v1, ModelGovernance.rollback(v2, listOf(v1, v2)))

        val longHistory = (1..40).map { version ->
            ModelSnapshot(version, mapOf("x" to version.toDouble()), version.toLong(), parentVersion = if (version == 30) 1 else null)
        }
        val retained = ModelGovernance.versionsToRetain(longHistory, currentVersion = 30, limit = 5)
        assertTrue("active version survives pruning", 30 in retained)
        assertTrue("rollback parent survives pruning", 1 in retained)
        assertTrue("newest audit points survive pruning", (36..40).all { it in retained })
    }

    @Test fun `hot model paths satisfy generous phone scale throughput floor`() {
        val card = Card(id = 1, noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
            state = CardState.REVIEW, stability = 10.0, lastReview = 0L)
        val benchmark = ModelBenchmark.measure(20_000) { i ->
            WorldModel.successProbability(card, ItemDifficulty(1), now = i * 86_400L)
        }
        assertTrue("model throughput was ${benchmark.operationsPerSecond}", benchmark.operationsPerSecond > 1_000.0)
    }

    @Test fun `session planning remains bounded with fifty thousand card history`() {
        val now = 400L * 86_400_000L
        val cards = List(50_000) { index ->
            Card(
                id = index.toLong() + 1, noteId = index.toLong() + 1,
                cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
                state = CardState.REVIEW, stability = 1.0 + index % 365,
                lastReview = now - (index % 90) * 86_400_000L
            )
        }
        lateinit var blueprint: SessionBlueprint
        val benchmark = ModelBenchmark.measure(1) {
            blueprint = BlueprintBuilder.build(cards, now, .88, 15, 30, backlog = false, recentAccuracy = .86)
        }
        assertTrue(benchmark.elapsedNanos < 5_000_000_000L)
        assertTrue(blueprint.totalBudget in 0..30)
        assertTrue(blueprint.atRiskCardIds.size <= blueprint.reviewBudget)
    }
}
