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
import kotlin.math.abs
import kotlin.random.Random

class FsrsSchedulerTest {
    @Test
    fun intervalModifierLengthensAndShortensVocabIntervals() {
        fun reviewedDays(modifier: Double): Int {
            val scheduler = FsrsScheduler(intervalModifierProvider = { modifier })
            val card = Card(
                noteId = 1,
                cardType = CardType.RU_TO_MEANING,
                queue = Queue.VOCAB,
                stability = 50.0,
                difficulty = 4.0,
                state = CardState.REVIEW,
                lastReview = 0L
            )
            return scheduler.review(card, Rating.GOOD, now = 10L * 86_400_000L).first.scheduledDays
        }
        val neutral = reviewedDays(1.0)
        assertTrue("modifier > 1 lengthens intervals", reviewedDays(1.8) > neutral)
        assertTrue("modifier < 1 shortens intervals", reviewedDays(0.6) < neutral)
    }

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
    fun sameDayLearningReviewUsesZeroElapsedDays() {
        val scheduler = FsrsScheduler()
        val card = Card(
            noteId = 1,
            cardType = CardType.RU_TO_MEANING,
            queue = Queue.VOCAB,
            stability = 1.0,
            difficulty = 5.0,
            state = CardState.LEARNING,
            lastReview = 0L
        )

        val (_, log) = scheduler.review(card, Rating.GOOD, now = 10L * 60_000L)

        assertEquals(0, log.elapsedDays)
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

    @Test
    fun recoversFromNonFiniteCorruptState() {
        val scheduler = FsrsScheduler()
        val corrupt = Card(
            noteId = 1,
            cardType = CardType.RU_TO_MEANING,
            queue = Queue.VOCAB,
            stability = Double.NaN,
            difficulty = Double.POSITIVE_INFINITY,
            state = CardState.REVIEW,
            lastReview = 0L
        )

        val (reviewed) = scheduler.review(corrupt, Rating.GOOD, now = 10L * 86_400_000L)

        assertTrue("stability must be finite", reviewed.stability.isFinite())
        assertTrue("stability must be positive", reviewed.stability > 0.0)
        assertTrue("difficulty must be finite", reviewed.difficulty.isFinite())
        assertTrue("difficulty in range", reviewed.difficulty in 1.0..10.0)
        assertTrue("interval must be schedulable", reviewed.scheduledDays >= 1)
        assertTrue("due must advance", reviewed.due > 10L * 86_400_000L)
    }

    @Test
    fun fuzzIsOffByDefaultAndDeterministic() {
        val card = Card(
            noteId = 1,
            cardType = CardType.RU_TO_MEANING,
            queue = Queue.VOCAB,
            stability = 100.0,
            difficulty = 5.0,
            state = CardState.REVIEW,
            lastReview = 0L
        )
        val now = 200L * 86_400_000L
        val a = FsrsScheduler().review(card, Rating.GOOD, now).first.scheduledDays
        val b = FsrsScheduler().review(card, Rating.GOOD, now).first.scheduledDays
        assertEquals("default scheduler must be deterministic", a, b)
    }

    @Test
    fun fuzzStaysWithinBandAndVaries() {
        val card = Card(
            noteId = 1,
            cardType = CardType.RU_TO_MEANING,
            queue = Queue.VOCAB,
            stability = 100.0,
            difficulty = 5.0,
            state = CardState.REVIEW,
            lastReview = 0L
        )
        val now = 200L * 86_400_000L
        val base = FsrsScheduler().review(card, Rating.GOOD, now).first.scheduledDays
        assertTrue("interval should be long enough to fuzz", base > 5)

        val results = (1..60).map { seed ->
            FsrsScheduler(enableFuzz = true, random = Random(seed.toLong()))
                .review(card, Rating.GOOD, now).first.scheduledDays
        }
        // Fuzz must keep intervals close to the base (generous envelope) ...
        val band = (base * 0.2).toInt() + 2
        assertTrue("fuzzed intervals stray too far", results.all { abs(it - base) <= band })
        // ... while actually spreading due dates (the whole point).
        assertTrue("fuzz should produce variation", results.toSet().size > 1)
    }

    @Test
    fun previewDoesNotConsumeTheRandomFuzzStream() {
        var randomCalls = 0
        val countingRandom = object : Random() {
            override fun nextBits(bitCount: Int): Int {
                randomCalls += 1
                return 0
            }
        }
        val scheduler = FsrsScheduler(enableFuzz = true, random = countingRandom)
        val card = Card(
            noteId = 1,
            cardType = CardType.RU_TO_MEANING,
            queue = Queue.VOCAB,
            stability = 100.0,
            difficulty = 5.0,
            state = CardState.REVIEW,
            lastReview = 0L
        )
        val now = 200L * 86_400_000L

        scheduler.preview(card, now)
        assertEquals("display-only previews must not alter later due dates", 0, randomCalls)
        scheduler.review(card, Rating.GOOD, now)
        assertTrue("committed reviews still use fuzz", randomCalls > 0)
    }

    @Test
    fun logsStabilityGoingIntoTheReview() {
        val scheduler = FsrsScheduler()
        val mature = Card(
            noteId = 1,
            cardType = CardType.RU_TO_MEANING,
            queue = Queue.VOCAB,
            stability = 42.0,
            difficulty = 5.0,
            state = CardState.REVIEW,
            lastReview = 0L
        )
        val (_, log) = scheduler.review(mature, Rating.GOOD, now = 10L * 86_400_000L)
        assertEquals("mature review must log the prior stability", 42.0, log.stabilityBefore, 1e-9)

        val fresh = mature.copy(stability = 0.0, state = CardState.NEW, lastReview = null)
        val (_, freshLog) = scheduler.review(fresh, Rating.GOOD, now = 0L)
        assertEquals("new-card first review has no prior stability", 0.0, freshLog.stabilityBefore, 1e-9)
    }

    @Test
    fun weightsProviderIsReadLivePerReview() {
        // A higher init-stability weight for GOOD must lengthen the first interval.
        var weights = FsrsScheduler.DEFAULT_WEIGHTS.copyOf()
        val scheduler = FsrsScheduler(weightsProvider = { weights })
        val newCard = Card(
            noteId = 1,
            cardType = CardType.RU_TO_MEANING,
            queue = Queue.VOCAB,
            state = CardState.NEW,
            lastReview = null
        )
        val before = scheduler.review(newCard, Rating.GOOD, now = 0L).first.scheduledDays
        weights = weights.copyOf().also { it[2] = it[2] * 3.0 } // w[2] = init stability for GOOD
        val after = scheduler.review(newCard, Rating.GOOD, now = 0L).first.scheduledDays
        assertTrue("a live weight bump must change scheduling without rebuilding", after > before)
    }
}
