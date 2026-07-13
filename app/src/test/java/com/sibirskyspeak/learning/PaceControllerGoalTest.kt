package com.sibirskyspeak.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the safety-composition invariants for PaceInputs.goalPaceRatio (the
 * Learning Goal feature's pressure term): it may only ever raise the new-item
 * ceiling/floor within what the existing safety guards already sanctioned, and
 * must never be able to bypass a real fatigue/accuracy/capacity stop or touch
 * targetRetention (the anti-cramming guarantee). These must hold regardless of
 * how the rest of PaceController's internals evolve.
 */
class PaceControllerGoalTest {

    private fun healthyInputs(goalPaceRatio: Double? = null) = PaceInputs(
        capacity = CapacityBelief(28.0, 2.0),
        recentAccuracy = 0.95,
        fatigue = 0.1,
        productionSigma = 2.0,
        goalPaceRatio = goalPaceRatio
    )

    @Test
    fun goalPressureNeverBypassesARealFatigueStop() {
        val tiredButBadlyBehindSchedule = PaceInputs(
            capacity = CapacityBelief(6.0, 4.0),
            recentAccuracy = 0.65,
            fatigue = 0.8,
            goalPaceRatio = 0.02
        )
        val pace = PaceController.generatePace(tiredButBadlyBehindSchedule)
        assertEquals(0, pace.newItemBudget)
        assertEquals(StopPolicy.EARLY_STOP, pace.stretchStopPolicy)
    }

    @Test
    fun goalPressureNeverBypassesALowAccuracyStop() {
        val lowAccuracyBadlyBehindSchedule = PaceInputs(
            capacity = CapacityBelief(28.0, 2.0),
            recentAccuracy = 0.70,
            fatigue = 0.1,
            goalPaceRatio = 0.02
        )
        assertEquals(0, PaceController.generatePace(lowAccuracyBadlyBehindSchedule).newItemBudget)
    }

    @Test
    fun goalPressureNeverBypassesALowCapacityFitStop() {
        val poorCapacityBadlyBehindSchedule = PaceInputs(
            capacity = CapacityBelief(2.0, 8.0),
            recentAccuracy = 0.95,
            fatigue = 0.1,
            goalPaceRatio = 0.02
        )
        assertEquals(0, PaceController.generatePace(poorCapacityBadlyBehindSchedule).newItemBudget)
    }

    @Test
    fun goalPressureNeverLowersTargetRetention() {
        val withoutGoal = PaceController.generatePace(healthyInputs(goalPaceRatio = null))
        val badlyBehindGoal = PaceController.generatePace(healthyInputs(goalPaceRatio = 0.05))
        assertEquals(withoutGoal.targetRetention, badlyBehindGoal.targetRetention, 0.0)
    }

    @Test
    fun goalPressureRaisesTheCeilingWhenBehindScheduleButNeverShrinksItWhenAhead() {
        val noGoal = PaceController.generatePace(healthyInputs(goalPaceRatio = null))
        val behind = PaceController.generatePace(healthyInputs(goalPaceRatio = 0.3))
        val wayAhead = PaceController.generatePace(healthyInputs(goalPaceRatio = 5.0))

        assertTrue("behind-schedule pressure must raise (or at worst match) the ceiling", behind.newItemBudget >= noGoal.newItemBudget)
        assertEquals("ahead-of-schedule must be a no-op, never a shrink", noGoal.newItemBudget, wayAhead.newItemBudget)
    }

    @Test
    fun goalPressureSaturatesAtThePressureCeilingRegardlessOfHowFarBehind() {
        val atCeilingRatio = 1.0 / GoalMath.PRESSURE_CEILING
        val atCeiling = PaceController.generatePace(healthyInputs(goalPaceRatio = atCeilingRatio))
        val wayBeyondCeiling = PaceController.generatePace(healthyInputs(goalPaceRatio = 0.0001))
        assertEquals(
            "pressure must plateau at PRESSURE_CEILING, not keep growing with an ever-more-extreme ratio",
            atCeiling.newItemBudget,
            wayBeyondCeiling.newItemBudget
        )
    }
}
