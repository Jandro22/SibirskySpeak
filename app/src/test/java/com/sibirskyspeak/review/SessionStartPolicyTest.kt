package com.sibirskyspeak.review

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStartPolicyTest {
    @Test
    fun initialStudyDestinationMayAutoStartOncePlanIsReady() {
        assertTrue(
            shouldAutoStartStudySession(
                autoStartAttempted = false,
                inStudySession = false,
                planReady = true,
                completedCards = 0,
                hasMatchReport = false,
                ratingInProgress = false
            )
        )
    }

    @Test
    fun completedSittingCannotAutoStartAnotherSession() {
        assertFalse(
            shouldAutoStartStudySession(
                autoStartAttempted = false,
                inStudySession = false,
                planReady = true,
                completedCards = 1,
                hasMatchReport = false,
                ratingInProgress = false
            )
        )
    }

    @Test
    fun completionSaveWindowCannotAutoStart() {
        assertFalse(
            shouldAutoStartStudySession(
                autoStartAttempted = false,
                inStudySession = true,
                planReady = true,
                completedCards = 1,
                hasMatchReport = false,
                ratingInProgress = true
            )
        )
    }
}
