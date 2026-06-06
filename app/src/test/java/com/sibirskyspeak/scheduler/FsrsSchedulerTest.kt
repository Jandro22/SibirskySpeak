package com.sibirskyspeak.scheduler

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.Rating
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsSchedulerTest {
    @Test
    fun capsNonGraduatedGrammarIntervals() {
        val scheduler = FsrsScheduler()
        val card = Card(
            noteId = 1,
            cardType = CardType.CASE_FILL,
            queue = Queue.GRAMMAR,
            stability = 100.0,
            difficulty = 4.0,
            state = CardState.REVIEW,
            lastReview = 0L
        )

        val (reviewed) = scheduler.review(card, Rating.EASY, now = 20L * 86_400_000L)

        assertEquals(10, reviewed.scheduledDays)
    }

    @Test
    fun leavesVocabAndGraduatedGrammarUncapped() {
        val scheduler = FsrsScheduler()
        val vocab = Card(
            noteId = 1,
            cardType = CardType.RU_TO_MEANING,
            queue = Queue.VOCAB,
            stability = 100.0,
            difficulty = 4.0,
            state = CardState.REVIEW,
            lastReview = 0L
        )
        val graduated = vocab.copy(cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, state = CardState.GRADUATED)

        assertTrue(scheduler.review(vocab, Rating.EASY, now = 20L * 86_400_000L).first.scheduledDays > 10)
        assertTrue(scheduler.review(graduated, Rating.EASY, now = 20L * 86_400_000L).first.scheduledDays > 10)
    }

    @Test
    fun matchesCheckedReferenceFixtures() {
        val scheduler = FsrsScheduler()
        val raw = javaClass.classLoader!!.getResource("fsrs_reference_cases.json")!!.readText()
        val cases = JSONArray(raw)
        repeat(cases.length()) { index ->
            val item = cases.getJSONObject(index)
            val cardJson = item.getJSONObject("card")
            val expected = item.getJSONObject("expected")
            val card = Card(
                noteId = 1,
                cardType = CardType.valueOf(cardJson.getString("type")),
                queue = Queue.valueOf(cardJson.getString("queue")),
                stability = cardJson.getDouble("stability"),
                difficulty = cardJson.getDouble("difficulty"),
                state = CardState.valueOf(cardJson.getString("state")),
                lastReview = if (cardJson.isNull("lastReview")) null else cardJson.getLong("lastReview")
            )

            val reviewed = scheduler.review(card, Rating.valueOf(item.getString("rating")), item.getLong("now")).first

            assertEquals(item.getString("name"), CardState.valueOf(expected.getString("state")), reviewed.state)
            assertEquals(item.getString("name"), expected.getInt("scheduledDays"), reviewed.scheduledDays)
            if (expected.has("stability")) {
                assertEquals(item.getString("name"), expected.getDouble("stability"), reviewed.stability, 0.0001)
            }
            if (expected.has("difficulty")) {
                assertEquals(item.getString("name"), expected.getDouble("difficulty"), reviewed.difficulty, 0.0001)
            }
        }
    }
}
