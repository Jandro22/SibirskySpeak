package com.sibirskyspeak.learning

import com.sibirskyspeak.data.MinedExample
import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.ItemDifficulty
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.scheduler.FsrsScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRobustnessTest {
    @Test fun `empty session has no phantom reading checkpoint`() {
        val blueprint = BlueprintBuilder.build(
            cards = emptyList(), now = 0L, desiredRetention = .9,
            dailyNewCap = 0, capacity = 0, backlog = false, recentAccuracy = .9
        )
        assertEquals(0, blueprint.totalBudget)
        assertTrue(blueprint.readingInsertions.isEmpty())

        val malformed = BlueprintBuilder.build(
            cards = emptyList(), now = 0L, desiredRetention = Double.NaN,
            dailyNewCap = -10, capacity = -5, backlog = false, recentAccuracy = Double.NaN
        )
        assertEquals(0, malformed.totalBudget)
        assertTrue(malformed.targetRetention.isFinite())
    }

    @Test fun `zero narrow reading limit returns no content`() {
        val example = MinedExample(
            noteId = 1, ru = "До́м.", en = "House.", sentenceId = 1,
            anchoredGloss = "house", score = 1.0, knownAtMine = 1
        )
        assertTrue(NarrowReadingGenerator.chain(listOf(example), limit = 0).isEmpty())
    }

    @Test fun `bandit repairs malformed persisted state and ignores non finite telemetry`() {
        val bandit = ContextualBandit(dimensions = 3)
        bandit.restore(listOf(ContextualBandit.Snapshot(
            action = "cloze", pulls = -4,
            reward = doubleArrayOf(Double.NaN),
            precision = doubleArrayOf(0.0, Double.POSITIVE_INFINITY)
        )))
        bandit.update("cloze", doubleArrayOf(Double.NaN, 1.0), Double.NaN)
        val score = bandit.score("cloze", doubleArrayOf(Double.NaN, 1.0, 2.0))
        assertTrue(score.isFinite())
        val saved = bandit.snapshot().single()
        assertEquals(1, saved.pulls)
        assertEquals(3, saved.reward.size)
        assertTrue(saved.precision.all { it.isFinite() && it > 0.0 })
    }

    @Test fun `adaptive scalar models cannot be poisoned by non finite inputs`() {
        val attempt = ObjectiveAttempt(1, true, 1000, AnswerMode.ENGLISH, Double.NaN)
        assertTrue(PerformanceModel.score(listOf(attempt)).isFinite())
        assertTrue(ColdStartModel.blend(Double.NaN, 0.6, 10, 10).isFinite())
        assertTrue(MasteryModel.update(Double.NaN, true, Double.NaN, Double.NaN, Double.NaN).isFinite())
        val capacity = CapacityModel.updateFromSession(
            CapacityBelief(Double.NaN, Double.NaN), Double.NaN, stoppedEarly = false, fatigue = Double.NaN
        )
        assertTrue(capacity.mu.isFinite() && capacity.sigma.isFinite() && capacity.sustainableMinutes.isFinite())

        val malformedWillingness = WillingnessBelief(Double.NaN, doubleArrayOf(Double.NaN))
        assertTrue(WillingnessModel.returnProbability(malformedWillingness, ReturnContext()).isFinite())
        val repairedWillingness = WillingnessModel.updateReturn(malformedWillingness, ReturnContext(), true)
        assertEquals(WillingnessModel.priorMeans.size, repairedWillingness.coeffs.size)
        assertTrue(repairedWillingness.coeffs.all(Double::isFinite))

        val pace = PaceController.generatePace(PaceInputs(
            capacity = CapacityBelief(Double.NaN, Double.NaN),
            plannedNewFraction = Double.NaN,
            recentAccuracy = Double.NaN,
            fatigue = Double.NaN,
            productionSigma = Double.NaN,
            medianReviewMinutes = Double.NaN,
            sessionsPerDayExpected = Double.NaN,
            decay = Double.NaN
        ))
        assertTrue(listOf(pace.targetMinutes, pace.targetRetention, pace.targetDifficulty,
            pace.productionRatio, pace.debtRatio, pace.pReturn).all(Double::isFinite))
        assertTrue(pace.newItemBudget >= 0 && pace.reviewBudget >= 0)
        assertTrue(ReviewControl.intensity(Double.NaN, 0.0, Double.NaN).isFinite())
        assertTrue(ReviewControl.optimalRetention(Double.NaN).isFinite())
        assertTrue(ReviewControl.optimalRetention(0.4) > ReviewControl.optimalRetention(0.0))
        assertTrue(CausalFormatReward.reward(true, Double.NaN, Double.NaN, Double.NaN).isFinite())
    }

    @Test fun `fatigue only uses aligned latency and outcome observations`() {
        val withOrphanLatency = FatigueModel.estimate(listOf(1000, 100_000), listOf(true))
        val aligned = FatigueModel.estimate(listOf(100_000), listOf(true))
        assertEquals(aligned, withOrphanLatency, 0.0)
    }

    @Test fun `lookahead tolerates invalid boundaries and still returns a valid choice`() {
        val choice = SessionLookahead.choose(-10, listOf(-5, 2), Double.NaN)
        assertEquals(0, choice.newCards)
        assertEquals(2, choice.projectedReviews)
        assertTrue(choice.utility.isFinite())
    }

    @Test fun `world and forgetting models repair corrupt persisted numbers`() {
        val card = Card(id = 1, noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB)
        val probability = WorldModel.successProbability(
            card = card.copy(stability = Double.NaN),
            itemDifficulty = ItemDifficulty(1, elo = Double.NaN, sigma = Double.NaN),
            state = LearnerWorldState(
                global = Gaussian(Double.NaN, Double.NaN),
                skills = mapOf(AbilitySkill.VOCAB to Gaussian(Double.NaN, -1.0)),
                fatigue = Double.NaN
            ),
            decay = Double.NaN,
            calibration = WorldModel.Calibration(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        )
        assertTrue(probability.isFinite() && probability in 0.0..1.0)
        assertEquals(1.0, FsrsScheduler.retrievabilityOf(Double.NaN, 10.0, Double.NaN), 1e-9)
        assertEquals(0.0, FsrsScheduler.retrievabilityOf(1.0, Double.NaN, .15), 0.0)

        val rating = TrueSkill.update(
            Gaussian(Double.NaN, -1.0), Gaussian(Double.POSITIVE_INFINITY, Double.NaN),
            MatchOutcome.WIN, beta = Double.NaN, tau = Double.NaN, drawMargin = Double.NaN
        )
        assertTrue(listOf(rating.a.mu, rating.a.sigma, rating.b.mu, rating.b.sigma).all(Double::isFinite))
        assertEquals(MatchOutcome.DRAW, TrueSkill.outcomeFromPerformance(Double.NaN, 0.5))
        val rival = Rival.rubberBand(
            RivalBelief(Gaussian(Double.NaN, Double.NaN), handicap = Double.NaN),
            Gaussian(Double.NaN, Double.NaN)
        )
        assertTrue(rival.rating.mu.isFinite() && rival.rating.sigma.isFinite() && rival.handicap.isFinite())
        assertTrue(Rival.nextHandicap(Double.NaN, MatchOutcome.DRAW).isFinite())
    }

    @Test fun `calibration ignores corrupt samples rather than poisoning parameters`() {
        val valid = WorldModel.CalibrationSample(true, 0.0, 0.2, 0.1, 0.1, 10.0)
        val corrupt = valid.copy(scale = Double.NaN)
        val fitted = SuccessCalibrationFitter.fit(List(120) { valid } + List(50) { corrupt })
        assertEquals(120, fitted.observations)
        assertTrue(listOf(fitted.intercept, fitted.memoryScale, fitted.masteryScale, fitted.loadScale).all(Double::isFinite))

        val repairedPrior = SuccessCalibrationFitter.fit(emptyList(),
            WorldModel.Calibration(Double.NaN, Double.NaN, Double.NaN, Double.NaN, -2))
        assertTrue(listOf(repairedPrior.intercept, repairedPrior.memoryScale,
            repairedPrior.masteryScale, repairedPrior.loadScale).all(Double::isFinite))
        assertEquals(0, repairedPrior.observations)
    }
}
