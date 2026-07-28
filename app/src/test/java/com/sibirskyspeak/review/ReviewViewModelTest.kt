package com.sibirskyspeak.review

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.LearningConfig
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.RepoFixture
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReviewLog
import com.sibirskyspeak.data.ReviewSource
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
            """{"russian":"войска","lemma":"$lemma","pos":"noun","translation":"troops","gender":"PL","declensionJson":{"NOM_PL":"войска","GEN_PL":"войск"},"domainFreqRank":$freqRank,"cefrLevel":"B1","exampleSentence":"Здесь нет войск."}"""
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
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore(), Dispatchers.Unconfined)
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
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore(), Dispatchers.Unconfined)
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
        val recognition = fixture.cards.cards.first { it.cardType == CardType.RU_TO_MEANING }
        assertEquals(CardState.LEARNING, recognition.state)
        assertEquals(0, recognition.scheduledDays)
        assertEquals(com.sibirskyspeak.learning.EvidenceStrength.INSTRUCTION, fixture.logs.logs.last().evidenceStrength)
        assertEquals(0, fixture.evidence.rows[recognition.noteId]?.directRetrievals)
    }

    @Test
    fun missedProductionCardAutoGradesAgainThenOverrideKnewItUndoesWithoutDoubleCounting() = runTest(dispatcher) {
        val (fixture, caseFillCard) = caseFillOnlyFixture(lemma = "войска-override", freqRank = 10)
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore(), Dispatchers.Unconfined)
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
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore(), Dispatchers.Unconfined)
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
    fun duplicateRevealCommitsAProductionMissOnlyOnce() = runTest(dispatcher) {
        val (fixture, caseFillCard) = caseFillOnlyFixture(lemma = "войска-double", freqRank = 13)
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore(), Dispatchers.Unconfined)
        advanceUntilIdle()
        viewModel.startStudySession()
        advanceUntilIdle()

        viewModel.setTypedAnswer("wrong")
        viewModel.reveal()
        viewModel.reveal()
        advanceUntilIdle()

        val saved = fixture.cards.cards.first { it.id == caseFillCard.id }
        assertEquals("one physical attempt must produce one lapse", 1, saved.lapses)
        assertEquals(1, viewModel.state.value.sessionReviewed)
        assertEquals(1, viewModel.state.value.sessionCompletedCards)
    }

    @Test
    fun nearbyWrongCaseEndingIsNotAcceptedAsATypo() = runTest(dispatcher) {
        val (fixture, caseFillCard) = caseFillOnlyFixture(lemma = "войска-ending", freqRank = 14)
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore(), Dispatchers.Unconfined)
        advanceUntilIdle()
        viewModel.startStudySession()
        advanceUntilIdle()

        val expected = viewModel.state.value.prompt!!.expectedAnswer
        assertEquals("войск", expected)
        viewModel.setTypedAnswer("войска")
        viewModel.reveal()
        advanceUntilIdle()

        assertEquals(AnswerMatch.WRONG, viewModel.state.value.answerMatch)
        assertEquals(1, fixture.cards.cards.first { it.id == caseFillCard.id }.lapses)
    }

    @Test
    fun overridingPracticeMissRemovesItsFalseScaffold() = runTest(dispatcher) {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"дом","lemma":"дом-practice","pos":"noun","translation":"house","tier":0,"unit":1}"""
        )
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore(), Dispatchers.Unconfined)
        advanceUntilIdle()
        viewModel.startStudySession()
        advanceUntilIdle()

        // Introduction -> first scheduled recall -> unscheduled acquisition recall.
        viewModel.rate(Rating.GOOD)
        advanceUntilIdle()
        val recall = viewModel.state.value.prompt!!
        viewModel.setTypedAnswer(recall.expectedAnswer)
        viewModel.reveal()
        viewModel.rate(Rating.GOOD)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.prompt?.practiceOnly == true)

        viewModel.setTypedAnswer("wrong")
        viewModel.reveal()
        viewModel.rate(Rating.AGAIN)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.autoRatedAgain)
        viewModel.overrideKnewIt()
        advanceUntilIdle()
        viewModel.rate(Rating.GOOD)
        advanceUntilIdle()

        assertFalse("override must not leave the miss scaffold queued", viewModel.state.value.prompt?.supportOnly == true)
        assertEquals(1, viewModel.state.value.sessionReviewed)
    }

    @Test
    fun studyOpenedBeforeStartupLoadAdoptsTheFinishedPlan() = runTest {
        val standard = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(standard)
        val fixture = RepoFixture(bootstrapNotes = """
            {"russian":"one","lemma":"one","pos":"noun","translation":"one"}
            {"russian":"two","lemma":"two","pos":"noun","translation":"two"}
        """.trimIndent())
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore(), Dispatchers.Unconfined)

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

    /**
     * The in-progress typed answer lives in its own [ReviewViewModel.typedAnswer]
     * flow (kept out of ReviewUiState so a keystroke doesn't re-emit the whole state
     * and recompose the screen). It must still feed reveal()'s grading and reset when
     * the card advances — the behavior the old per-card ReviewUiState rebuild gave us.
     */
    @Test
    fun typedAnswerUsesADedicatedFlowAndResetsWhenTheCardAdvances() = runTest(dispatcher) {
        val (fixture, caseFillCard) = caseFillOnlyFixture(lemma = "войска-typed", freqRank = 12)
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore(), Dispatchers.Unconfined)
        advanceUntilIdle()
        viewModel.startStudySession()
        advanceUntilIdle()

        val prompt = viewModel.state.value.prompt
        assertNotNull("expected a queued prompt after starting the session", prompt)
        assertEquals(caseFillCard.id, prompt!!.card.id)

        // Typing is observable on the dedicated flow.
        viewModel.setTypedAnswer("draft answer")
        assertEquals("draft answer", viewModel.typedAnswer.value)

        // reveal() must grade against the flow's value, not a stale ReviewUiState field.
        viewModel.setTypedAnswer(prompt.expectedAnswer)
        viewModel.reveal()
        advanceUntilIdle()
        assertEquals(true, viewModel.state.value.isAnswerCorrect)

        // Committing the card rebuilds the session, which must clear the input.
        viewModel.rate(Rating.GOOD)
        advanceUntilIdle()
        assertEquals("", viewModel.typedAnswer.value)
    }

    @Test
    fun fullBackupStreamsAValidatedSnapshotWithoutRenderingThePayload() = runTest(dispatcher) {
        var writtenLines = 0
        val fixture = RepoFixture(writeBackupLines = { lines -> lines.forEach { writtenLines++ } })
        fixture.repository.addNote(Note(
            russian = "дом",
            lemma = "дом-backup",
            translation = "house",
            partOfSpeech = "noun"
        ))
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore(), Dispatchers.Unconfined)
        advanceUntilIdle()

        viewModel.exportFullState()
        advanceUntilIdle()

        assertTrue(writtenLines > 0)
        assertTrue("the full-state payload must not be copied into Compose state", viewModel.state.value.exportText.isBlank())
        assertEquals("Full backup saved and validated.", viewModel.state.value.statusMessage)
    }

    private fun dayBucketTimestamp(now: Long, bucket: Long): Long {
        val dayMillis = 86_400_000L
        val tzOffset = java.util.TimeZone.getDefault().getOffset(now)
        return bucket * dayMillis - tzOffset + dayMillis / 2
    }

    /** P2.5: the repository's gamificationStats only *reports* which gap day
     * insurance bridged (see LearningRepositoryTest's streak-insurance tests) —
     * this covers the other half, that ReviewViewModel (the only layer with a
     * writable SettingsStore) actually spends the credit exactly once when it
     * loads a session reflecting a freshly insured gap, then never spends it
     * again for the same gap on a later reload (e.g. reopening the app). */
    @Test
    fun loadingASessionWithAnInsuredGapSpendsExactlyOneCreditOnce() = runTest(dispatcher) {
        val settings = FakeSettingsStore()
        settings.restDayCredits = 1
        // Mirrors production di/AppModule.kt wiring: config() reads restDayCredits
        // live from the same settings the ViewModel writes to.
        val fixture = RepoFixture(config = { LearningConfig(restDayCredits = settings.restDayCredits) })
        val noteId = fixture.notes.insert(Note(russian = "тест", lemma = "тест-vm-insure", translation = "test", partOfSpeech = "noun"))
        val card = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB)).let { id ->
            fixture.cards.cards.first { it.id == id }
        }
        val now = System.currentTimeMillis()
        val tzOffset = java.util.TimeZone.getDefault().getOffset(now)
        val todayBucket = (now + tzOffset) / 86_400_000L
        fun log(bucket: Long) = ReviewLog(
            cardId = card.id, reviewDatetime = dayBucketTimestamp(now, bucket), rating = Rating.GOOD,
            stateBefore = CardState.NEW, scheduledDays = 1, elapsedDays = 0, source = ReviewSource.SRS_REVIEW
        )
        fixture.logs.insert(log(todayBucket))
        fixture.logs.insert(log(todayBucket - 2))

        val viewModel = ReviewViewModel(fixture.repository, settings, Dispatchers.Unconfined)
        advanceUntilIdle()

        assertEquals(3, viewModel.state.value.sessionPlan?.gamification?.currentStreak)
        assertEquals("the one available credit must be spent on the insured gap", 0, settings.restDayCredits)
        assertEquals(todayBucket - 1, settings.lastInsuredGapDay)

        // Simulate reopening the app later the same day: a fresh ViewModel loads
        // the same historical gap again. It must not find another credit to spend
        // (there isn't one) or re-charge the already-insured day.
        val reopened = ReviewViewModel(fixture.repository, settings, Dispatchers.Unconfined)
        advanceUntilIdle()
        assertEquals(0, settings.restDayCredits)
        assertEquals(todayBucket - 1, settings.lastInsuredGapDay)
        assertNotNull(reopened.state.value.sessionPlan)
    }

    /** P6.4: the monthly checkpoint must be independently gradable end-to-end
     * (start -> answer -> completion summary) and, critically, must never touch
     * the graded card's FSRS state or write a review log — its entire value is
     * being unbiased by the regular scheduler. */
    @Test
    fun checkpointSessionGradesAnswersAndNeverTouchesFsrsState() = runTest(dispatcher) {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "стол", lemma = "стол-checkpoint", translation = "table", partOfSpeech = "noun"))
        val graduatedCard = fixture.cards.insert(
            Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.GRADUATED, stability = 40.0, lastReview = 0L)
        ).let { id -> fixture.cards.cards.first { it.id == id } }
        val viewModel = ReviewViewModel(fixture.repository, FakeSettingsStore(), Dispatchers.Unconfined)
        advanceUntilIdle()

        viewModel.startCheckpoint()
        advanceUntilIdle()
        val session = viewModel.state.value.checkpointSession
        assertNotNull("expected at least the one graduated note as a checkpoint item", session)
        assertTrue(session!!.items.isNotEmpty())
        assertEquals(0, viewModel.state.value.checkpointIndex)

        val item = session.items.first { it.itemKey == "note:${noteId}" }
        viewModel.submitCheckpointAnswer(item.expectedAnswer)
        advanceUntilIdle()

        // The card itself must be exactly as it was: no review, no FSRS mutation.
        val unchanged = fixture.cards.cards.first { it.id == graduatedCard.id }
        assertEquals(graduatedCard, unchanged)
        assertEquals(0, fixture.logs.logs.size)
        assertEquals(1, viewModel.state.value.checkpointResults.size)
        assertTrue(viewModel.state.value.checkpointResults.first())

        // Answer every remaining item (wrong, to prove the summary counts failures too).
        while (viewModel.state.value.checkpointSession != null &&
            viewModel.state.value.checkpointIndex < session.items.size
        ) {
            viewModel.submitCheckpointAnswer("completely wrong answer")
            advanceUntilIdle()
        }
        assertEquals(session.items.size, viewModel.state.value.checkpointResults.size)
        assertTrue(viewModel.state.value.checkpointFeedback.orEmpty().startsWith("Checkpoint complete"))

        viewModel.dismissCheckpoint()
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.checkpointSession)
    }
}
