package com.sibirskyspeak.learning

import org.junit.Assert.assertEquals
import org.junit.Test

class LearnerSnapshotTest {
    @Test
    fun paceInputsCopiesEveryPacingField() {
        val capacity = CapacityBelief(mu = 17.0, sigma = 3.5)
        val willingness = WillingnessBelief(habit = 0.72, coeffs = doubleArrayOf(0.1, -0.2, 0.3))
        val returnContext = ReturnContext(1.2, -0.4, 0.3, 0.18)
        val world = WorldSkills(
            global = Gaussian(27.0, 4.0),
            skills = mapOf(AbilitySkill.PRODUCTION to Gaussian(1.5, 5.0))
        )
        val snapshot = LearnerSnapshot(
            capacity = capacity,
            willingness = willingness,
            willingnessObserved = true,
            returnContext = returnContext,
            world = world,
            activeCards = emptyList(),
            totalKnown = 321,
            recentAccuracy = 0.81,
            fatigue = 0.22,
            productionSigma = 5.0,
            medianReviewMinutes = 0.24,
            sessionsPerDayExpected = 1.7,
            decay = 0.9,
            calibration = WorldModel.Calibration(intercept = 0.1, observations = 42),
            evidence = AdaptiveEvidence(completedSessions = 9, calibratedObservations = 42),
            tunedTargetRetention = 0.91,
            tunedNewBudgetScale = 1.15
        )

        val actual = snapshot.paceInputs(plannedNewFraction = 0.37)

        assertEquals(capacity, actual.capacity)
        assertEquals(willingness, actual.willingness)
        assertEquals(true, actual.willingnessObserved)
        assertEquals(returnContext, actual.returnContext)
        assertEquals(emptyList<Any>(), actual.activeCards)
        assertEquals(0.37, actual.plannedNewFraction, 0.0)
        assertEquals(321, actual.totalKnown)
        assertEquals(0.81, actual.recentAccuracy, 0.0)
        assertEquals(0.22, actual.fatigue, 0.0)
        assertEquals(5.0, actual.productionSigma, 0.0)
        assertEquals(0.24, actual.medianReviewMinutes, 0.0)
        assertEquals(1.7, actual.sessionsPerDayExpected, 0.0)
        assertEquals(0.9, actual.decay, 0.0)
        assertEquals(0.91, actual.tunedTargetRetention ?: -1.0, 0.0)
        assertEquals(1.15, actual.tunedNewBudgetScale, 0.0)
    }

    @Test
    fun worldSkillsPreservesWorldStateAndEventFatigue() {
        val world = WorldSkills(
            global = Gaussian(29.0, 5.5),
            skills = mapOf(
                AbilitySkill.VOCAB to Gaussian(1.2, 4.2),
                AbilitySkill.CASES to Gaussian(-0.8, 6.1)
            )
        )

        assertEquals(
            LearnerWorldState(
                global = Gaussian(29.0, 5.5),
                skills = world.skills,
                fatigue = 0.63
            ),
            world.worldState(0.63)
        )
    }
}
