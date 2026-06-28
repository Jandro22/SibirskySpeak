package com.sibirskyspeak.review

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.RepoFixture
import com.sibirskyspeak.data.Rating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the hand-maintained session-counter bookkeeping in ReviewViewModel
 * (sessionReviewed/sessionCorrect incremented in rate()/reveal(), then rolled
 * back by undoLastReview()/overrideKnewIt()). This is the most fragile part of
 * the ViewModel — a deque of deltas tracked manually — and previously had no
 * test coverage despite the repository and prompt-building layers being well
 * tested.
 *
 * The ViewModel launches its work on Dispatchers.Main; the shared
 * [UnconfinedTestDispatcher] is installed as Main *and* passed to runTest so both
 * use one scheduler, and advanceUntilIdle() drains any suspended resumptions
 * before assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Builds a fixture with exactly one eligible card: a CASE_FILL drill on a
     * freshly imported noun. Every sibling card for the note is suspended and
     * encounterCount is bumped so the new-grammar "wait for reader exposure"
     * gate doesn't also exclude it — giving a deterministic single-card queue. */
    private suspend fun caseFillOnlyFixture(lemma: String, freqRank: Int): Pair<RepoFixture, Card> {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"войска","lemma":"$lemma","pos":"noun","translation":"troops","gender":"PL","declensionJson":{"NOM_PL":"войска","GEN_PL":"войск"},"domainFreqRank":$freqRank}"""
        )
        val note = fixture.notes.getByLemma(lemma)!!
        fixture.notes.update(note.copy(encounterCount = 5))
        val caseFillCard = fixture.cards.cards.first { it.noteId == note.id && it.cardType == CardType.CASE_FILL }
        fixture.cards.cards.filter { it.noteId == note.id && it.id != caseFillCard.id }.forEach { other ->
            fixture.cards.update(other.copy(suspended = true))
        }
        return fixture to caseFillCard
    }

    @Test
    fun correctRateThenUndoRestoresSessionCountersAndPrompt() = runTest(dispatcher) {
        // A CASE_FILL drill is graded on first interaction (unlike a brand-new vocab
        // card, whose first exposure is an uncounted spaced introduction — see
        // spacedIntroductionOfNewVocabIsNotCountedAsRecall).
        val (fixture, caseFillCard) = caseFillOnlyFixture(lemma = "войска-rate", freqRank = 9)
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore())
        advanceUntilIdle()
        viewModel.startStudySession()
        advanceUntilIdle()

        val prompt = viewModel.state.value.prompt
        assertNotNull("expected a queued prompt after starting the session", prompt)
        assertEquals(caseFillCard.id, prompt!!.card.id)

        viewModel.setTypedAnswer(prompt.expectedAnswer)
        viewModel.reveal()
        advanceUntilIdle()
        assertEquals(true, viewModel.state.value.isAnswerCorrect)
        assertFalse("a correct answer must not auto-grade Again", viewModel.state.value.autoRatedAgain)

        viewModel.rate(Rating.GOOD)
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.sessionReviewed)
        assertEquals(1, viewModel.state.value.sessionCorrect)
        assertEquals(1, viewModel.state.value.sessionCompletedCards)

        viewModel.undoLastReview()
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value.sessionReviewed)
        assertEquals(0, viewModel.state.value.sessionCorrect)
        assertEquals(0, viewModel.state.value.sessionCompletedCards)
        assertFalse(viewModel.state.value.revealed)
        assertEquals(caseFillCard.id, viewModel.state.value.prompt?.card?.id)
    }

    @Test
    fun spacedIntroductionOfNewVocabIsNotCountedAsRecall() = runTest(dispatcher) {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1}"""
        )
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore())
        advanceUntilIdle()
        viewModel.startStudySession()
        advanceUntilIdle()

        val prompt = viewModel.state.value.prompt
        assertNotNull(prompt)
        assertEquals(CardType.RU_TO_MEANING, prompt!!.card.cardType)
        // First exposure of a new vocab card is a teaching screen, graded with
        // "Got it"; it must advance the session (one action done) without polluting
        // the sitting's recall accuracy.
        assertEquals(AnswerMode.LESSON, prompt.answerMode)

        viewModel.rate(Rating.GOOD)
        advanceUntilIdle()
        assertEquals("intro is not a graded recall", 0, viewModel.state.value.sessionReviewed)
        assertEquals(0, viewModel.state.value.sessionCorrect)
        assertEquals("but it still counts as one completed action", 1, viewModel.state.value.sessionCompletedCards)
        assertEquals(1, viewModel.state.value.sessionPlan?.reviewQueue?.size)
        assertFalse(viewModel.state.value.sessionPlan?.reviewQueue?.single()?.practiceOnly == true)
        assertEquals(AnswerMode.ENGLISH, viewModel.state.value.prompt?.answerMode)
    }

    @Test
    fun missedProductionCardAutoGradesAgainThenOverrideKnewItUndoesWithoutDoubleCounting() = runTest(dispatcher) {
        val (fixture, caseFillCard) = caseFillOnlyFixture(lemma = "войска-override", freqRank = 10)
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore())
        advanceUntilIdle()
        viewModel.startStudySession()
        advanceUntilIdle()

        val prompt = viewModel.state.value.prompt
        assertNotNull("expected the CASE_FILL drill to be queued", prompt)
        assertEquals(caseFillCard.id, prompt!!.card.id)

        // A deliberately wrong typed answer on a production (committed-miss) card
        // auto-rates AGAIN inside reveal() itself.
        viewModel.setTypedAnswer("definitely-not-the-right-form")
        viewModel.reveal()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.autoRatedAgain)
        assertTrue(viewModel.state.value.correctionRequired)
        assertEquals(1, viewModel.state.value.sessionReviewed)
        assertEquals(0, viewModel.state.value.sessionCorrect)
        assertEquals(1, viewModel.state.value.sessionCompletedCards)

        // The learner claims they actually knew it: roll back the silent AGAIN.
        viewModel.overrideKnewIt()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.autoRatedAgain)
        assertFalse(viewModel.state.value.correctionRequired)
        assertEquals(true, viewModel.state.value.isAnswerCorrect)
        assertEquals(0, viewModel.state.value.sessionReviewed)
        assertEquals(0, viewModel.state.value.sessionCorrect)
        assertEquals(0, viewModel.state.value.sessionCompletedCards)

        // Grading it for real afterward must count exactly once, not twice.
        viewModel.rate(Rating.GOOD)
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.sessionReviewed)
        assertEquals(1, viewModel.state.value.sessionCorrect)
        assertEquals("undoing the miss must remove its repair and final-recovery inserts", null, viewModel.state.value.prompt)

        viewModel.undoLastReview()
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value.sessionReviewed)
        assertEquals(0, viewModel.state.value.sessionCorrect)
    }

    @Test
    fun autoAgainOnCommittedMissAdvancesLapsesAndGatesOnCorrection() = runTest(dispatcher) {
        val (fixture, caseFillCard) = caseFillOnlyFixture(lemma = "войска-leech", freqRank = 11)
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore())
        advanceUntilIdle()
        viewModel.startStudySession()
        advanceUntilIdle()
        assertEquals(caseFillCard.id, viewModel.state.value.prompt?.card?.id)

        viewModel.setTypedAnswer("wrong")
        viewModel.reveal()
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.sessionReviewed)
        assertEquals(0, viewModel.state.value.sessionCorrect)
        assertTrue(viewModel.state.value.correctionRequired)
        val afterFirstMiss = fixture.cards.cards.first { it.id == caseFillCard.id }
        assertEquals(1, afterFirstMiss.lapses)

        // The correction gate blocks continuing until the learner reproduces the
        // expected answer.
        viewModel.continueAfterRating()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.correctionRequired)
        viewModel.setCorrectionAnswer(viewModel.state.value.prompt!!.expectedAnswer)
        viewModel.submitCorrection()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.correctionAccepted)
    }

    @Test
    fun studyOpenedBeforeStartupLoadAdoptsTheFinishedPlan() = runTest {
        val standard = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(standard)
        val fixture = RepoFixture(bootstrapNotes = """
            {"russian":"one","lemma":"one","pos":"noun","translation":"one"}
            {"russian":"two","lemma":"two","pos":"noun","translation":"two"}
        """.trimIndent())
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore())

        // Deliberately race the Study screen against the queued startup load.
        viewModel.startStudySession()
        advanceUntilIdle()
        val first = viewModel.state.value.prompt
        assertNotNull(first)

        viewModel.rate(Rating.GOOD)
        advanceUntilIdle()

        assertNotNull("the second planned card must remain in the frozen queue", viewModel.state.value.prompt)
        assertTrue(viewModel.state.value.prompt!!.card.id != first!!.card.id)
    }
}
