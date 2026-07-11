package com.sibirskyspeak.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {
    @Test
    fun reducerMakesRapidRevealAndRatingIdempotent() {
        val started = SessionReducer.reduce(
            SessionState(),
            SessionEvent.Start(listOf(10L, 20L), "session", 100L)
        )
        val revealed = SessionReducer.reduce(started, SessionEvent.Reveal)
        assertEquals(SessionPhase.REVEALED, revealed.phase)
        assertEquals(revealed, SessionReducer.reduce(revealed, SessionEvent.Reveal))

        val next = SessionReducer.reduce(revealed, SessionEvent.ReviewCommitted(20L, reviewed = 1, correct = 1))
        assertEquals(SessionPhase.ANSWERING, next.phase)
        assertEquals(listOf(20L), next.queueCardIds)
        assertEquals(20L, next.currentCardId)
        assertEquals(1, next.reviewed)
        assertEquals(1, next.correct)
    }

    @Test
    fun snapshotRoundTripRestoresDurableQueueAndCounters() {
        val state = SessionState(
            phase = SessionPhase.CORRECTION,
            queueCardIds = listOf(3L, 7L),
            currentCardId = 3L,
            sessionId = "abc",
            startedAt = 99L,
            reviewed = 2,
            correct = 1,
            completedActions = 3
        )
        val restored = SessionState.fromJson(state.toJson())
        assertEquals(state, restored)
        assertTrue(restored!!.isActive)
    }

    @Test
    fun pauseAndResumeDoNotLoseCurrentCard() {
        val started = SessionReducer.reduce(SessionState(), SessionEvent.Start(listOf(1L), "s", 1L))
        val paused = SessionReducer.reduce(started, SessionEvent.Pause(2L))
        assertEquals(SessionPhase.PAUSED, paused.phase)
        val resumed = SessionReducer.reduce(paused, SessionEvent.Resume)
        assertEquals(SessionPhase.ANSWERING, resumed.phase)
        assertEquals(1L, resumed.currentCardId)
    }
}
