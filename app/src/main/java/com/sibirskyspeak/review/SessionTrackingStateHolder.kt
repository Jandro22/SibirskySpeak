package com.sibirskyspeak.review

import java.util.ArrayDeque

/** Owns mutable per-sitting queue/counter state so the ViewModel remains an
 * action coordinator rather than the storage for every session detail. */
internal data class SessionCounterDelta(val reviewed: Int, val correct: Int)

internal class SessionTrackingStateHolder {
    val counterDeltas = ArrayDeque<SessionCounterDelta>()
    val activeQueue = mutableListOf<ReviewPrompt>()
    val originCardIds = linkedSetOf<Long>()
    var sessionState: SessionState = SessionState()

    fun reset() {
        counterDeltas.clear()
        activeQueue.clear()
        originCardIds.clear()
        sessionState = SessionState()
    }

    fun resetCounters() {
        counterDeltas.clear()
    }
}
