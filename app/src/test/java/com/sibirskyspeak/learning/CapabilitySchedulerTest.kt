package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitySchedulerTest {
    @Test fun `same concept and facet collapse to one varied task`() {
        val cards = listOf(
            Card(id = 1, noteId = 10, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramConcept = "GEN", due = 0),
            Card(id = 2, noteId = 11, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramConcept = "GEN", due = 0),
            Card(id = 3, noteId = 12, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, due = 0)
        )
        val collapsed = CapabilityScheduler.collapse(cards, now = 0)
        assertEquals(2, collapsed.size)
        assertEquals(1, collapsed.count { it.gramConcept == "GEN" })
    }

    @Test fun `strong successful evidence defers sibling without graduating it`() {
        val reviewed = Card(id = 1, noteId = 10, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramConcept = "GEN")
        val scheduled = reviewed.copy(state = CardState.REVIEW, scheduledDays = 20, stability = 12.0, difficulty = 5.0, reps = 4)
        val sibling = Card(id = 2, noteId = 11, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramConcept = "GEN", due = 0, state = CardState.LEARNING, reps = 1)
        val transferred = CapabilityScheduler.transferSuccess(
            reviewed, scheduled, sibling, Rating.GOOD, EvidenceStrength.STRONG, now = 1_000
        )!!
        assertEquals(CardState.LEARNING, transferred.state)
        assertEquals(13, transferred.scheduledDays)
        assertTrue(transferred.due > 1_000)
        assertEquals(1, transferred.reps)
    }

    @Test fun `unseen sibling stays dormant instead of becoming phantom debt`() {
        val reviewed = Card(id = 1, noteId = 10, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramConcept = "GEN")
        val scheduled = reviewed.copy(state = CardState.REVIEW, scheduledDays = 20, stability = 12.0, difficulty = 5.0)
        val unseen = Card(id = 2, noteId = 11, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramConcept = "GEN")
        assertNull(CapabilityScheduler.transferSuccess(reviewed, scheduled, unseen, Rating.GOOD, EvidenceStrength.STRONG, 0))
    }

    @Test fun `new carrier selection preserves curriculum order while review debt stays collapsed`() {
        val cards = listOf(
            Card(id = 20, noteId = 20, cardType = CardType.GENDER_ID, queue = Queue.GRAMMAR, gramConcept = "GENDER"),
            Card(id = 10, noteId = 10, cardType = CardType.GENDER_ID, queue = Queue.GRAMMAR, gramConcept = "GENDER")
        )
        assertEquals(20L, CapabilityScheduler.collapse(cards, now = 99L, preserveInputOrder = true).single().id)
    }

    @Test fun `new task formats on one carrier rotate instead of starving behind the first variant`() {
        val cards = listOf(
            Card(id = 10, noteId = 10, cardType = CardType.AUDIO_TO_RU, queue = Queue.VOCAB),
            Card(id = 20, noteId = 10, cardType = CardType.DICTATION, queue = Queue.VOCAB)
        )

        val selected = (0L..1L).map { day ->
            CapabilityScheduler.collapse(cards, now = day * 86_400_000L, preserveInputOrder = true).single().cardType
        }.toSet()

        assertEquals(setOf(CardType.AUDIO_TO_RU, CardType.DICTATION), selected)
    }

    @Test fun `failure and unrelated capabilities never transfer`() {
        val reviewed = Card(id = 1, noteId = 10, cardType = CardType.AUDIO_TO_RU, queue = Queue.VOCAB)
        val sibling = Card(id = 2, noteId = 10, cardType = CardType.DICTATION, queue = Queue.VOCAB)
        val unrelated = Card(id = 3, noteId = 11, cardType = CardType.DICTATION, queue = Queue.VOCAB)
        val scheduled = reviewed.copy(state = CardState.RELEARNING, scheduledDays = 1, stability = 1.0, difficulty = 7.0)
        assertNull(CapabilityScheduler.transferSuccess(reviewed, scheduled, sibling, Rating.AGAIN, EvidenceStrength.MODERATE, 0))
        assertNull(CapabilityScheduler.transferSuccess(reviewed, scheduled, unrelated, Rating.GOOD, EvidenceStrength.MODERATE, 0))
    }
}
