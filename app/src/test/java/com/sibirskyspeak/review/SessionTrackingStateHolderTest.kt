package com.sibirskyspeak.review

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTrackingStateHolderTest {
    @Test
    fun resetCountersDoesNotDiscardTheActiveQueue() {
        val holder = SessionTrackingStateHolder()
        holder.counterDeltas.addLast(SessionCounterDelta(1, 1))
        holder.originCardIds += 42L
        holder.resetCounters()
        assertEquals(0, holder.counterDeltas.size)
        assertEquals(setOf(42L), holder.originCardIds)
    }
}
