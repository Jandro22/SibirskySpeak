package com.sibirskyspeak.review

/**
 * The Study destination may survive a process restart, so it can need one implicit
 * start after the first usable plan arrives. A completed sitting must never qualify
 * for that implicit start: beginning another sitting is an explicit user action from
 * Practice/Dashboard.
 */
internal fun shouldAutoStartStudySession(
    autoStartAttempted: Boolean,
    inStudySession: Boolean,
    planReady: Boolean,
    completedCards: Int,
    hasMatchReport: Boolean,
    ratingInProgress: Boolean
): Boolean =
    !autoStartAttempted &&
        !inStudySession &&
        planReady &&
        completedCards == 0 &&
        !hasMatchReport &&
        !ratingInProgress
