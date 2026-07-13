package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card

/** The Gaussian portion of learner state used by world-model decisions. */
data class WorldSkills(
    val global: Gaussian,
    val skills: Map<AbilitySkill, Gaussian>
) {
    fun worldState(fatigue: Double): LearnerWorldState =
        LearnerWorldState(global = global, skills = skills, fatigue = fatigue)
}

/**
 * One read-operation's view of the persisted learner model and the derived
 * signals consumed by pacing and forward projections.
 *
 * This is deliberately not a session-state object: in-memory review tracking
 * and write/update paths must continue to use their own current values.
 */
data class LearnerSnapshot(
    val capacity: CapacityBelief,
    val willingness: WillingnessBelief,
    val willingnessObserved: Boolean,
    val returnContext: ReturnContext,
    val world: WorldSkills,
    val activeCards: List<Card>,
    val totalKnown: Int,
    val recentAccuracy: Double,
    val fatigue: Double,
    val productionSigma: Double,
    val medianReviewMinutes: Double,
    val sessionsPerDayExpected: Double,
    val decay: Double,
    val calibration: WorldModel.Calibration,
    val evidence: AdaptiveEvidence,
    val tunedTargetRetention: Double?,
    val tunedNewBudgetScale: Double,
    /** stablePace / requiredPace for the active learning goal, or null with no
     * active goal. See PaceInputs.goalPaceRatio for how this is used. */
    val goalPaceRatio: Double? = null
) {
    fun paceInputs(plannedNewFraction: Double): PaceInputs = PaceInputs(
        capacity = capacity,
        willingness = willingness,
        willingnessObserved = willingnessObserved,
        returnContext = returnContext,
        activeCards = activeCards,
        plannedNewFraction = plannedNewFraction,
        totalKnown = totalKnown,
        recentAccuracy = recentAccuracy,
        fatigue = fatigue,
        productionSigma = productionSigma,
        medianReviewMinutes = medianReviewMinutes,
        sessionsPerDayExpected = sessionsPerDayExpected,
        decay = decay,
        tunedTargetRetention = tunedTargetRetention,
        tunedNewBudgetScale = tunedNewBudgetScale,
        goalPaceRatio = goalPaceRatio
    )
}
