package com.sibirskyspeak.learning

import org.junit.Assert.assertEquals
import org.junit.Test

class GoalMathTest {
    @Test
    fun requiredPaceIsWordsNeededOverDaysRemaining() {
        val pace = GoalMath.requiredPace(milestone = 4209, totalKnown = 209, targetDateEpochDay = 1000, nowEpochDay = 0)
        assertEquals(4.0, pace, 0.0001)
    }

    @Test
    fun requiredPaceNeverNegativeWhenMilestoneAlreadyReached() {
        val pace = GoalMath.requiredPace(milestone = 700, totalKnown = 900, targetDateEpochDay = 1000, nowEpochDay = 0)
        assertEquals(0.0, pace, 0.0001)
    }

    @Test
    fun requiredPaceFloorsDaysRemainingAtOneToAvoidDivideByZero() {
        val pace = GoalMath.requiredPace(milestone = 1000, totalKnown = 0, targetDateEpochDay = 5, nowEpochDay = 5)
        assertEquals(1000.0, pace, 0.0001)
    }

    @Test
    fun feasibilityIsComfortableWhenRequiredPaceAtOrBelowCurrentPace() {
        assertEquals(GoalVerdict.COMFORTABLE, GoalMath.feasibility(requiredPace = 5.0, currentStablePace = 10.0).verdict)
        assertEquals(GoalVerdict.COMFORTABLE, GoalMath.feasibility(requiredPace = 10.0, currentStablePace = 10.0).verdict)
    }

    @Test
    fun feasibilityIsStretchWithinThePressureCeiling() {
        // 10 * 1.35 = 13.5 — just under that boundary.
        assertEquals(GoalVerdict.STRETCH, GoalMath.feasibility(requiredPace = 13.0, currentStablePace = 10.0).verdict)
    }

    @Test
    fun feasibilityIsUnsustainableBeyondThePressureCeiling() {
        assertEquals(GoalVerdict.UNSUSTAINABLE, GoalMath.feasibility(requiredPace = 14.0, currentStablePace = 10.0).verdict)
    }

    @Test
    fun feasibilityTreatsNonFiniteOrZeroCurrentPaceSafely() {
        assertEquals(GoalVerdict.COMFORTABLE, GoalMath.feasibility(requiredPace = 0.0, currentStablePace = 0.0).verdict)
        assertEquals(GoalVerdict.UNSUSTAINABLE, GoalMath.feasibility(requiredPace = 5.0, currentStablePace = Double.NaN).verdict)
    }

    @Test
    fun paceRatioIsAheadWhenCurrentExceedsRequired() {
        assertEquals(2.0, GoalMath.paceRatio(currentStablePace = 10.0, requiredPace = 5.0), 0.0001)
    }

    @Test
    fun paceRatioTreatsAlreadyMetGoalAsComfortablyOnTrack() {
        assertEquals(1.0, GoalMath.paceRatio(currentStablePace = 3.0, requiredPace = 0.0), 0.0001)
    }

    @Test
    fun trackStateBucketsByPaceRatioThresholds() {
        assertEquals(GoalTrackState.ON_TRACK, GoalMath.trackState(0.95))
        assertEquals(GoalTrackState.ON_TRACK, GoalMath.trackState(1.5))
        assertEquals(GoalTrackState.DRIFTING, GoalMath.trackState(0.80))
        assertEquals(GoalTrackState.OFF_TRACK, GoalMath.trackState(0.5))
    }
}
