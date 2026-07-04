package com.sibirskyspeak.learning

import com.sibirskyspeak.data.PaceLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoctrineAdvisorTest {
    private fun log(mode: SessionMode) = PaceLog(
        at = 0L, T = 20.0, N = 10, rho = 0.9, debtRatio = 0.3, pReturn = 0.9,
        doctrine = Doctrine.BALANCED.name, modeChosen = mode.name
    )

    @Test
    fun suggestsEasierDoctrineAfterRepeatedEarlyStops() {
        val logs = List(4) { log(SessionMode.QUICK) }
        val nudge = DoctrineAdvisor.suggest(logs, Doctrine.BALANCED)
        assertEquals(NudgeDirection.EASIER, nudge?.direction)
        assertEquals(Doctrine.CONSERVE, nudge?.suggested)
    }

    @Test
    fun suggestsHarderDoctrineAfterRepeatedStretches() {
        val logs = List(4) { log(SessionMode.STRETCH) }
        val nudge = DoctrineAdvisor.suggest(logs, Doctrine.BALANCED)
        assertEquals(NudgeDirection.HARDER, nudge?.direction)
        assertEquals(Doctrine.AMBITIOUS, nudge?.suggested)
    }

    @Test
    fun doesNotNudgeAtTheExtremesOfIntensity() {
        assertNull(DoctrineAdvisor.suggest(List(4) { log(SessionMode.QUICK) }, Doctrine.RECOVERY))
        assertNull(DoctrineAdvisor.suggest(List(4) { log(SessionMode.STRETCH) }, Doctrine.SPRINT))
    }

    @Test
    fun doesNotNudgeOnAMixedOrShortHistory() {
        // Not enough samples yet.
        assertNull(DoctrineAdvisor.suggest(List(3) { log(SessionMode.QUICK) }, Doctrine.BALANCED))
        // Mixed outcomes — no consistent pattern.
        val mixed = listOf(log(SessionMode.QUICK), log(SessionMode.STRETCH), log(SessionMode.FULL), log(SessionMode.QUICK), log(SessionMode.STRETCH))
        assertNull(DoctrineAdvisor.suggest(mixed, Doctrine.BALANCED))
    }

    @Test
    fun onlyLooksAtTheMostRecentFiveSessions() {
        // 5 old STRETCH sessions followed by 4 recent QUICK ones (newest first) —
        // should follow the recent pattern, not the stale one further back.
        val logs = List(4) { log(SessionMode.QUICK) } + List(5) { log(SessionMode.STRETCH) }
        val nudge = DoctrineAdvisor.suggest(logs, Doctrine.BALANCED)
        assertEquals(NudgeDirection.EASIER, nudge?.direction)
    }
}
