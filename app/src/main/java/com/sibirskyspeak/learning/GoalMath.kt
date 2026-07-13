package com.sibirskyspeak.learning

/** Verdict shown to the learner before they commit to a target date. */
enum class GoalVerdict { COMFORTABLE, STRETCH, UNSUSTAINABLE }

data class GoalFeasibility(
    val requiredPace: Double,
    val currentPace: Double,
    val verdict: GoalVerdict
)

/** Weekly on-track state derived from projected-vs-required pace. */
enum class GoalTrackState { ON_TRACK, DRIFTING, OFF_TRACK }

data class GoalStatus(
    val targetLevel: String,
    val targetDateEpochDay: Long,
    val paceRatio: Double,
    val state: GoalTrackState
)

/**
 * Pure arithmetic shared by the live feasibility check (Settings, called on every
 * slider tick — must never trigger a new FluencySimEngine simulation) and the
 * daily/weekly goal-status check (fed the already-computed SimResult). Neither
 * function here touches the DB or the simulator; callers supply totalKnown/
 * stablePace/etc. from data they already hold.
 */
object GoalMath {
    /**
     * Ceiling on how much a live goal may raise the new-item budget scale in
     * PaceController.generatePace's goalPressure term (Phase B). Kept here so the
     * feasibility verdict shown at goal-creation time uses the exact same bound
     * the controller will actually honor later — a "stretch" goal must be
     * reachable within the real ceiling, not a made-up one.
     */
    const val PRESSURE_CEILING = 1.35

    /** Words/day needed to reach [milestone] from [totalKnown] by [targetDateEpochDay]. */
    fun requiredPace(milestone: Int, totalKnown: Int, targetDateEpochDay: Long, nowEpochDay: Long): Double {
        val wordsNeeded = (milestone - totalKnown).coerceAtLeast(0)
        val daysRemaining = (targetDateEpochDay - nowEpochDay).coerceAtLeast(1)
        return wordsNeeded.toDouble() / daysRemaining
    }

    fun feasibility(requiredPace: Double, currentStablePace: Double): GoalFeasibility {
        val safePace = currentStablePace.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val verdict = when {
            requiredPace <= safePace -> GoalVerdict.COMFORTABLE
            requiredPace <= safePace * PRESSURE_CEILING -> GoalVerdict.STRETCH
            else -> GoalVerdict.UNSUSTAINABLE
        }
        return GoalFeasibility(requiredPace, safePace, verdict)
    }

    /** >1 = ahead of schedule, <1 = behind. Infinite/undefined required pace (goal
     * already met) reads as comfortably ahead rather than an unusable number. */
    fun paceRatio(currentStablePace: Double, requiredPace: Double): Double {
        if (requiredPace <= 0.0) return 1.0
        val safePace = currentStablePace.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        return safePace / requiredPace
    }

    fun trackState(paceRatio: Double): GoalTrackState = when {
        paceRatio >= 0.95 -> GoalTrackState.ON_TRACK
        paceRatio >= 0.75 -> GoalTrackState.DRIFTING
        else -> GoalTrackState.OFF_TRACK
    }
}
