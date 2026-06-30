package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.ItemDifficulty
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.SkillRating
import com.sibirskyspeak.review.AnswerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativePaceWorldModelTest {
    @Test fun `success calibration stays prior below threshold and fits at threshold`() {
        val sample = WorldModel.CalibrationSample(
            correct = true,
            abilityMinusDifficulty = 0.0,
            memoryProbit = 0.5,
            masteryCentered = 0.2,
            formatFit = 0.0,
            fatigue = 0.1,
            scale = 10.0
        )
        val prior = WorldModel.Calibration()
        assertEquals(prior, SuccessCalibrationFitter.fit(List(119) { sample }, prior))
        val fitted = SuccessCalibrationFitter.fit(List(120) { index -> sample.copy(correct = index % 4 != 0) }, prior)
        assertEquals(120, fitted.observations)
        assertTrue(fitted.memoryScale in 2.0..10.0)
        assertTrue(fitted.masteryScale in 2.0..14.0)
    }

    @Test fun `promotion requires two wins within three matches`() {
        var series = PromotionSeries(lockedTier = 0)
        val first = Rival.updatePromotion(series, displayRating = 4.0, outcome = MatchOutcome.WIN)
        assertTrue(!first.locked)
        series = first.series
        val second = Rival.updatePromotion(series, displayRating = 4.0, outcome = MatchOutcome.LOSS)
        assertTrue(!second.locked)
        val third = Rival.updatePromotion(second.series, displayRating = 4.0, outcome = MatchOutcome.WIN)
        assertTrue(third.locked)
        assertEquals(1, third.series.lockedTier)
    }

    @Test fun `ghost comparison is fixed and season reset widens uncertainty`() {
        assertTrue(Rival.ghostPerformance(0.7, ghostMu = 30.0, rivalMu = 25.0) > 0.7)
        assertEquals(4.0, Rival.seasonSigma(4.0, 59.0), 0.0)
        assertEquals(5.2, Rival.seasonSigma(4.0, 60.0), 0.0001)
        assertEquals(TrueSkill.SIGMA0, Rival.seasonSigma(8.0, 60.0), 0.0001)
    }

    @Test fun `mastery fans out to concept and distinct root families`() {
        assertEquals(listOf("ACC", "root:ход", "root:вод"), WorldModel.masteryKeys("ACC", listOf("ход", "ход", "", "вод")))
    }

    @Test fun `ability delta updates weighted skills and uncertainty together`() {
        val card = Card(id = 1, noteId = 1, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB)
        val ratings = mapOf(
            AbilitySkill.PRODUCTION to SkillRating(skill = "production", mu = 1.0, sigma = 5.0),
            AbilitySkill.VOCAB to SkillRating(skill = "vocab", mu = 2.0, sigma = 5.0)
        )

        val updated = WorldModel.applyAbilityDelta(ratings, card, delta = 2.0, now = 123L, sigmaRatio = 0.5).associateBy { it.skill }

        assertEquals(1.56, updated.getValue("production").mu, 0.0001)
        assertEquals(2.24, updated.getValue("vocab").mu, 0.0001)
        assertEquals(2.5, updated.getValue("production").sigma, 0.0001)
        assertEquals(123L, updated.getValue("vocab").updatedAt)
    }

    @Test fun `live MPC stops tired sessions and stretches only strong safe flow`() {
        val firstMiss = LiveSessionState(shown = 1, recent = listOf(2_000L to false))
        assertEquals(MpcAction.CARD, SessionMpcController.decide(true, firstMiss, MpcInputs(fatigue = 0.4)))

        val tired = LiveSessionState(shown = 6, recent = listOf(1_000L to true, 2_000L to false, 2_200L to false, 2_400L to false))
        assertEquals(MpcAction.STOP, SessionMpcController.decide(true, tired, MpcInputs(fatigue = 0.9)))

        val flow = LiveSessionState(shown = 6, recent = listOf(2_000L to true, 1_900L to true, 1_800L to true, 1_700L to true))
        assertEquals(MpcAction.STRETCH, SessionMpcController.decide(true, flow, MpcInputs(fatigue = 0.1, debtRatio = 0.1, pReturn = 0.9)))
        assertEquals(MpcAction.CARD, SessionMpcController.decide(true, flow, MpcInputs(fatigue = 0.1, debtRatio = 0.6, pReturn = 0.9)))
    }

    @Test fun `objective performance weights correctness speed and difficulty`() {
        val score = PerformanceModel.score(listOf(
            ObjectiveAttempt(1, true, 4_000, AnswerMode.ENGLISH, 25.0),
            ObjectiveAttempt(2, false, 4_000, AnswerMode.CHOICE, 30.0)
        ))
        assertTrue(score in 0.4..0.8)
        assertEquals(0.0, PerformanceModel.score(listOf(ObjectiveAttempt(1, true, 1_000, AnswerMode.LESSON, 25.0))), 0.0)
        val paused = ObjectiveAttempt(1, true, 60L * 60_000, AnswerMode.ENGLISH, 25.0)
        assertEquals(0.2, PerformanceModel.effectiveMinutes(listOf(paused)), 0.0001)
    }

    @Test fun `fatigue follows latency drift and rolling errors`() {
        val fresh = FatigueModel.estimate(listOf(1_000, 1_100, 900, 1_000), listOf(true, true, true, true))
        val tired = FatigueModel.estimate(listOf(1_000, 1_100, 900, 2_000, 2_300, 2_500), listOf(true, true, true, false, false, true))
        assertTrue(tired > fresh)
        assertTrue(tired in 0.0..1.0)
    }

    @Test fun `causal format reward waits for durable recall and charges time and fatigue`() {
        val recalled = CausalFormatReward.reward(true, 0.6, 0.2, 0.1)
        val forgotten = CausalFormatReward.reward(false, 0.6, 0.2, 0.1)
        assertTrue(recalled > forgotten)
        assertTrue(CausalFormatReward.reward(true, 0.6, 1.0, 0.5) < recalled)
    }

    @Test fun `cold start shrinkage and information gain match specification`() {
        assertEquals(25.0, ColdStartModel.blend(40.0, 25.0, 0, 20), 0.0)
        assertEquals(32.5, ColdStartModel.blend(40.0, 25.0, 20, 20), 0.0)
        assertTrue(ColdStartModel.active(13))
        assertTrue(!ColdStartModel.active(14))
        assertTrue(ColdStartModel.infoGainWeight(10) < ColdStartModel.infoGainWeight(0))
        assertTrue(ColdStartModel.gaussianInformationGain(8.0, 4.0) > 0.0)
    }

    @Test fun `trueskill canonical updates at the real operating point (default margin and tau)`() {
        // Exercise the ACTUAL code path real matches use (default drawMargin and tau),
        // and assert the genuine canonical TrueSkill values — not a fitted operating point.
        val a = Gaussian()
        val b = Gaussian()
        val win = TrueSkill.update(a, b, MatchOutcome.WIN)
        assertEquals(29.3956, win.a.mu, 0.002)
        assertEquals(20.6044, win.b.mu, 0.002)
        assertEquals(7.1711, win.a.sigma, 0.002)
        assertEquals(7.1711, win.b.sigma, 0.002)

        val draw = TrueSkill.update(a, b, MatchOutcome.DRAW)
        assertEquals(25.0, draw.a.mu, 0.001)   // equal players: a draw shifts neither mean
        assertEquals(25.0, draw.b.mu, 0.001)
        assertEquals(6.458, draw.a.sigma, 0.01) // canonical draw variance reduction (was a fudged 7.4655)
        assertEquals(6.458, draw.b.sigma, 0.01)
    }

    @Test fun `trueskill asymmetric update matches canonical reference`() {
        // A confident-strong player beats a weak-uncertain one. Reference computed from
        // canonical 1v1 TrueSkill (c^2=2b^2+varA+varB, w=v(v+x)) at default margin/tau.
        val r = TrueSkill.update(Gaussian(30.0, 3.0), Gaussian(20.0, 8.0), MatchOutcome.WIN)
        assertEquals(30.2857, r.a.mu, 0.01)
        assertEquals(2.9503, r.a.sigma, 0.01)
        assertEquals(17.9695, r.b.mu, 0.01)
        assertEquals(6.9795, r.b.sigma, 0.01)
    }

    @Test fun `trueskill invariants hold`() {
        val a = Gaussian(28.0, 5.0)
        val b = Gaussian(22.0, 6.0)
        // Symmetry: A-wins and B-loses (with operands swapped) are identical.
        val aWins = TrueSkill.update(a, b, MatchOutcome.WIN)
        val bLoses = TrueSkill.update(b, a, MatchOutcome.LOSS)
        assertEquals(aWins.a.mu, bLoses.b.mu, 1e-9)
        assertEquals(aWins.b.mu, bLoses.a.mu, 1e-9)
        // A decisive result strictly reduces both players' sigma, never below the floor.
        assertTrue(aWins.a.sigma < a.sigma && aWins.b.sigma < b.sigma)
        assertTrue(aWins.a.sigma > 0.0 && aWins.b.sigma > 0.0)
        // Winner mu rises, loser mu falls.
        assertTrue(aWins.a.mu > a.mu && aWins.b.mu < b.mu)
        // Equal-sigma players: winner's gain equals loser's loss (mean conservation).
        val eq = TrueSkill.update(Gaussian(25.0, 5.0), Gaussian(25.0, 5.0), MatchOutcome.WIN)
        assertEquals(eq.a.mu - 25.0, 25.0 - eq.b.mu, 1e-9)
    }

    @Test fun `normal cdf and inverse round-trip`() {
        for (p in listOf(0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99)) {
            assertEquals(p, Normal.cdf(Normal.invCdf(p)), 1e-3)
        }
        assertEquals(0.0, Normal.invCdf(0.5), 1e-6)
        assertEquals(0.5, Normal.cdf(0.0), 1e-9)
    }

    @Test fun `capacity kalman converges toward observations and tightens`() {
        var cap = CapacityBelief()                 // mu 12, sigma 8
        val first = CapacityModel.update(cap, 20.0)
        assertTrue("sigma must shrink on an observation", first.sigma < cap.sigma)
        assertTrue("mu moves toward the observation", first.mu in cap.mu..20.0)
        cap = first
        repeat(15) { cap = CapacityModel.update(cap, 20.0) }
        assertTrue("mu converges near the steady observation", cap.mu in 18.0..20.0)
        assertTrue("conservative estimate stays below the mean", cap.sustainableMinutes < cap.mu)
        // Higher effort-adjusted demand lowers predicted success.
        val easy = SessionDemand(minutes = 10.0)
        val hard = SessionDemand(minutes = 10.0, newFraction = 1.0, productionFraction = 1.0, hardness = 1.0)
        assertTrue(CapacityModel.successProbability(cap, hard) < CapacityModel.successProbability(cap, easy))
    }

    @Test fun `willingness transition and return model behave and stay prior-bounded`() {
        val good = WillingnessModel.transition(0.0, WillingnessSignals(completed = true, flow = true, cleanFinish = true))
        val bad = WillingnessModel.transition(0.0, WillingnessSignals(quit = true, overload = true))
        assertTrue("positive signals raise habit above negative ones", good > bad)
        val belief = WillingnessBelief(habit = 1.0)
        val p = WillingnessModel.returnProbability(belief, ReturnContext())
        assertTrue("probability is a valid logistic output", p > 0.0 && p < 1.0)
        // Observing a return when predicted < 1 pushes the intercept up, but never past
        // the +2sd prior clamp (single-learner data discipline).
        val updated = WillingnessModel.updateReturn(belief, ReturnContext(), returned = true)
        assertTrue(updated.coeffs[0] >= belief.coeffs[0])
        assertTrue(updated.coeffs[0] <= 0.2 + 2.0 * 0.5 + 1e-9)
    }

    @Test fun `rival rubber-bands toward the learner and weakness-boost favors weak skills`() {
        val user = Gaussian(30.0, 4.0)
        var rival = RivalBelief(rating = Gaussian(10.0, 8.0))
        assertFalse(Rival.isRubberBanded(rival.rating, user))
        repeat(20) { rival = Rival.rubberBand(rival, user) }
        assertTrue("rival converges to the learner's skill edge", Rival.isRubberBanded(rival.rating, user))
        // Handicap clamps.
        assertEquals(6.0, Rival.nextHandicap(6.0, MatchOutcome.WIN), 0.0)
        assertEquals(-2.0, Rival.nextHandicap(-2.0, MatchOutcome.LOSS), 0.0)
    }

    @Test fun `idle decay raises sigma and caps at prior`() {
        val rating = Gaussian(30.0, 2.0)
        val rusted = TrueSkill.idleDecay(rating, days = 10.0)
        assertTrue(rusted.sigma > rating.sigma)
        assertEquals(TrueSkill.SIGMA0, TrueSkill.idleDecay(rating, days = 100_000.0).sigma, 0.0001)
    }

    @Test fun `success model is monotonic in memory and ability`() {
        val now = 30L * 86_400_000L
        val hard = Card(id = 1, noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, stability = 1.0, lastReview = now - 20L * 86_400_000L)
        val fresh = hard.copy(stability = 30.0, lastReview = now - 1L * 86_400_000L)
        val difficulty = ItemDifficulty(cardId = 1, elo = 25.0, sigma = 8.3333)
        val low = WorldModel.successProbability(hard, difficulty, state = LearnerWorldState(global = Gaussian(25.0, 4.0)), now = now)
        val highMemory = WorldModel.successProbability(fresh, difficulty, state = LearnerWorldState(global = Gaussian(25.0, 4.0)), now = now)
        val highAbility = WorldModel.successProbability(hard, difficulty, state = LearnerWorldState(global = Gaussian(35.0, 4.0)), now = now)
        assertTrue(highMemory > low)
        assertTrue(highAbility > low)
    }

    @Test fun `capacity kalman converges toward observed minutes with conservative sustainable minutes`() {
        val updated = listOf(10.0, 14.0, 9.0, 16.0).fold(CapacityBelief()) { belief, y -> CapacityModel.update(belief, y) }
        assertTrue(updated.mu in 11.0..14.0)
        assertTrue(updated.sigma < 8.0)
        assertTrue(updated.sustainableMinutes < listOf(10.0, 14.0, 9.0, 16.0).average())
    }

    @Test fun `review debt decreases new budget as load rises and uses tiers`() {
        val debt = 0.6
        val sustainable = 12.0
        val low = PaceController.maxNewItems(loadNow = 1.0, debtNew = debt, sustainableMinutesPerDay = sustainable, delta = 0.35)
        val high = PaceController.maxNewItems(loadNow = 6.0, debtNew = debt, sustainableMinutesPerDay = sustainable, delta = 0.35)
        assertTrue(low > high)
        assertEquals(0, PaceController.maxNewItems(loadNow = 5.0, debtNew = debt, sustainableMinutesPerDay = sustainable, delta = 0.35))
        assertEquals(0.35, PaceController.debtDelta(399), 0.0)
        assertEquals(0.50, PaceController.debtDelta(400), 0.0)
        assertEquals(0.70, PaceController.debtDelta(1500), 0.0)
    }

    @Test fun `pace controller protects tired learner and arms stretch on strong day`() {
        val tired = PaceController.generatePace(
            PaceInputs(capacity = CapacityBelief(6.0, 4.0), recentAccuracy = 0.65, fatigue = 0.8),
            Doctrine.BALANCED
        )
        assertEquals(0, tired.newItemBudget)
        assertEquals(StopPolicy.EARLY_STOP, tired.stretchStopPolicy)
        assertTrue(tired.readingInserts.isNotEmpty())
        assertTrue(tired.pReturn >= 0.80)

        val strong = PaceController.generatePace(
            PaceInputs(capacity = CapacityBelief(28.0, 2.0), recentAccuracy = 0.95, fatigue = 0.1, productionSigma = 2.0),
            Doctrine.AMBITIOUS
        )
        assertTrue(strong.newItemBudget > tired.newItemBudget)
        assertTrue(strong.productionRatio > tired.productionRatio)
        assertEquals(StopPolicy.STRETCH_ARMED, strong.stretchStopPolicy)
        assertTrue(strong.pReturn >= 0.80)
    }

    @Test fun `cold start pace adoption blends settings instead of hard capping them`() {
        val pace = Pace(
            targetMinutes = 8.0,
            newItemBudget = 0,
            reviewBudget = 1,
            targetRetention = 0.85,
            targetDifficulty = 0.8,
            productionRatio = 0.25,
            readingInserts = emptyList(),
            stretchStopPolicy = StopPolicy.STRETCH_ARMED,
            debtRatio = 0.1,
            pReturn = 0.9,
            doctrine = Doctrine.BALANCED
        )

        val cold = PaceController.adoptForSessionSettings(
            pace = pace,
            configuredSessionSize = 20,
            configuredNewCardsPerDay = 10,
            configuredRetention = 0.90,
            hasAdaptiveSignal = false
        )
        assertTrue("cold start should not collapse to a one-card session", cold.capacity > 1)
        assertTrue("cold start should still respond to generated pace", cold.capacity < 20)
        assertEquals("stretch needs learner evidence", SessionMode.FULL, cold.mode)

        val learned = PaceController.adoptForSessionSettings(
            pace = pace,
            configuredSessionSize = 20,
            configuredNewCardsPerDay = 10,
            configuredRetention = 0.90,
            hasAdaptiveSignal = true
        )
        assertEquals(1, learned.capacity)
        assertEquals(SessionMode.STRETCH, learned.mode)
    }

    @Test fun `rival rubber bands and weakness boost raises weakest skill odds`() {
        val user = Gaussian(32.0, 4.0)
        val rival = Rival.rubberBand(RivalBelief(Gaussian(10.0, 8.0)), user)
        assertTrue(Rival.isRubberBanded(rival.rating, user, tolerance = 16.0))
        val card = Card(id = 1, noteId = 1, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR)
        val difficulty = ItemDifficulty(cardId = 1, elo = 25.0, sigma = 8.3333)
        val weak = Rival.expectedCorrect(rival.rating, card, difficulty, learnerSkill = Gaussian(10.0, 4.0), cohortMeanSkill = 25.0)
        val strong = Rival.expectedCorrect(rival.rating, card, difficulty, learnerSkill = Gaussian(30.0, 4.0), cohortMeanSkill = 25.0)
        assertTrue(weak > strong)
        assertTrue(Rival.resolve(user, rival, perfYou = 0.9, perfRival = 0.7).after.conservativeRating > user.conservativeRating)
    }
}
