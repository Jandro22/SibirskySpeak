package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Queue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression test for a real cold-start account (2026-07-06 db pull: default
 * mu=12/sigma=8 capacity, habit=0 willingness, 21 known words, ~56 active
 * review cards) whose Dashboard forecast showed 0 words/day forever and never
 * reached any milestone. Root cause: the day-loop fed `pace.targetMinutes`
 * (derived from the very capacity belief being updated) back in as an
 * "observed" session length, and later, even after removing that direct
 * reference, the same circularity persisted one level indirect through
 * `added` (also capacity-derived) — capacity walked down to its floor and
 * never recovered, permanently zeroing the new-card budget. Fixed by holding
 * capacity fixed for the simulated horizon instead of re-deriving it from the
 * simulation's own pacing decisions. */
class FluencyRealAccountDiagnosticTest {
    private val realCardsFromDb = listOf(
        Card(45, 7, CardType.RU_TO_MEANING, Queue.VOCAB, 1783355224956, 3.9460540679694778, 1.0, 0, 0, 2, 0, CardState.REVIEW, 1782923224956, consecutiveCorrect = 2),
        Card(49, 8, CardType.RU_TO_MEANING, Queue.VOCAB, 1784644093220, 17.766311018997655, 1.0, 0, 0, 3, 0, CardState.REVIEW, 1783175293220, consecutiveCorrect = 3),
        Card(50, 8, CardType.MEANING_TO_RU, Queue.VOCAB, 1783522940659, 2.3065, 2.118103970459015, 0, 0, 1, 0, CardState.REVIEW, 1783350140659, consecutiveCorrect = 1),
        Card(52, 8, CardType.SENTENCE_BUILD, Queue.GRAMMAR, 1783521487030, 2.3065, 2.118103970459015, 0, 0, 1, 0, CardState.REVIEW, 1783262287030, consecutiveCorrect = 1),
        Card(54, 8, CardType.AUDIO_TO_RU, Queue.VOCAB, 1783435077399, 2.3065, 2.118103970459015, 0, 0, 1, 0, CardState.REVIEW, 1783262277399, consecutiveCorrect = 1),
        Card(56, 9, CardType.RU_TO_MEANING, Queue.VOCAB, 1786654905619, 35.93232945789446, 1.0, 0, 0, 3, 0, CardState.REVIEW, 1783285305619, consecutiveCorrect = 3),
        Card(59, 9, CardType.SENTENCE_BUILD, Queue.GRAMMAR, 1783544559811, 2.3065, 2.118103970459015, 0, 0, 1, 0, CardState.REVIEW, 1783285359811, consecutiveCorrect = 1),
        Card(61, 9, CardType.AUDIO_TO_RU, Queue.VOCAB, 1783522958044, 2.3065, 2.118103970459015, 0, 0, 1, 0, CardState.REVIEW, 1783350158044, consecutiveCorrect = 1),
        Card(64, 10, CardType.RU_TO_MEANING, Queue.VOCAB, 1783436518233, 1.276406326526732, 7.930573484469132, 0, 0, 7, 2, CardState.REVIEW, 1783350118233, consecutiveCorrect = 2),
        Card(65, 10, CardType.MEANING_TO_RU, Queue.VOCAB, 1783521469203, 2.3065, 2.118103970459015, 0, 0, 1, 0, CardState.REVIEW, 1783262269203, consecutiveCorrect = 1)
    )

    @Test
    fun coldStartAccountMakesRealProgressInsteadOfStallingAtZeroForever() {
        val result = FluencySimEngine.runSimulation(
            currentCapacity = CapacityBelief(mu = 12.0, sigma = 8.0),
            currentWillingness = WillingnessBelief(habit = 0.0),
            initialActiveCards = realCardsFromDb,
            totalKnownStart = 21,
            evidenceDays = 5,
            doctrine = Doctrine.BALANCED,
            recentAccuracy = 0.85,
            startTimeMillis = 1783353794014L
        )
        assertNotNull("cold-start account must reach A1, not stall forever", result.daysToA1)
        assertTrue("stablePace must reflect real (nonzero) progress, not a permanent capacity-floor stall", result.stablePace > 0.0)
    }
}
