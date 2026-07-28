package com.sibirskyspeak.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Small real-Room contract tests for the queries the session planner relies on. */
@RunWith(AndroidJUnit4::class)
class DaoIntegrationTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun noteAndCardQueriesRespectDueStateAndSearch() = runBlocking {
        val noteId = db.noteDao().insert(
            Note(
                russian = "дом",
                translation = "house",
                partOfSpeech = "noun",
                lemma = "дом",
                status = WordStatus.LEARNING,
                tier = 0,
                unit = 1,
                cefrLevel = "A1"
            )
        )
        db.cardDao().insert(
            Card(
                noteId = noteId,
                cardType = CardType.RU_TO_MEANING,
                queue = Queue.VOCAB,
                due = 10L,
                state = CardState.REVIEW
            )
        )

        assertEquals(1, db.noteDao().search("house").size)
        val counts = db.cardDao().dashboardCounts(now = 10L)
        assertEquals(1, counts.vocabCards)
        assertEquals(1, counts.dueVocab)
        assertEquals(0, counts.grammarCards)
    }

    @Test
    fun cardForeignKeyAndSuspendQueryKeepRetiredCardsOutOfDueQueue() = runBlocking {
        val noteId = db.noteDao().insert(Note(russian = "слово", translation = "word", partOfSpeech = "noun", lemma = "слово", status = WordStatus.LEARNING))
        val cardId = db.cardDao().insert(
            Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, due = 10L, state = CardState.REVIEW)
        )
        db.cardDao().suspendAllForNote(noteId)
        assertTrue(db.cardDao().getDueCards(10L).none { it.id == cardId })
    }

    @Test
    fun grammarProbationIsAggregatedByConceptBeforeRoomMaterializesRows() = runBlocking {
        val noteId = db.noteDao().insert(
            Note(
                russian = "дом",
                translation = "house",
                partOfSpeech = "noun",
                lemma = "дом",
                status = WordStatus.LEARNING
            )
        )
        val genderFirst = db.cardDao().insert(
            Card(noteId = noteId, cardType = CardType.GENDER_ID, queue = Queue.GRAMMAR)
        )
        db.cardDao().insert(
            Card(noteId = noteId, cardType = CardType.GENDER_ID, queue = Queue.GRAMMAR)
        )
        val accusativeFirst = db.cardDao().insert(
            Card(noteId = noteId, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramCase = "ACC")
        )
        db.cardDao().insert(
            Card(noteId = noteId, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramCase = "ACC")
        )
        db.reviewLogDao().insert(
            ReviewLog(
                cardId = genderFirst,
                reviewDatetime = 100L,
                rating = Rating.GOOD,
                stateBefore = CardState.NEW,
                scheduledDays = 0,
                elapsedDays = 0,
                source = ReviewSource.GRAMMAR_DRILL
            )
        )
        db.reviewLogDao().insert(
            ReviewLog(
                cardId = accusativeFirst,
                reviewDatetime = 101L,
                rating = Rating.AGAIN,
                stateBefore = CardState.NEW,
                scheduledDays = 0,
                elapsedDays = 0,
                source = ReviewSource.GRAMMAR_DRILL
            )
        )

        val outcomes = db.cardDao().getGrammarConceptOutcomes().associateBy { it.concept }

        assertEquals(setOf("GENDER", "ACC"), outcomes.keys)
        assertEquals(genderFirst, outcomes.getValue("GENDER").probationCardId)
        assertTrue(outcomes.getValue("GENDER").everSucceeded)
        assertEquals(accusativeFirst, outcomes.getValue("ACC").probationCardId)
        assertTrue(!outcomes.getValue("ACC").everSucceeded)
    }

    @Test
    fun exhaustedNewBudgetQueryReturnsOnlyReviewedDepthWorkAndLessons() = runBlocking {
        val reviewedNoteId = db.noteDao().insert(
            Note(russian = "дом", translation = "house", partOfSpeech = "noun", lemma = "дом",
                status = WordStatus.LEARNING, tier = 0, cefrLevel = "A1")
        )
        val unseenNoteId = db.noteDao().insert(
            Note(russian = "мост", translation = "bridge", partOfSpeech = "noun", lemma = "мост",
                status = WordStatus.LEARNING, tier = 0, cefrLevel = "A1")
        )
        val lessonNoteId = db.noteDao().insert(
            Note(russian = "урок", translation = "lesson", partOfSpeech = "lesson",
                lemma = "lesson-depth-query", status = WordStatus.LEARNING, tier = 0, cefrLevel = "A1")
        )
        val recognitionId = db.cardDao().insert(
            Card(noteId = reviewedNoteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
                state = CardState.REVIEW, reps = 3, consecutiveCorrect = 2)
        )
        val depthId = db.cardDao().insert(
            Card(noteId = reviewedNoteId, cardType = CardType.CLOZE, queue = Queue.VOCAB)
        )
        val unseenId = db.cardDao().insert(
            Card(noteId = unseenNoteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB)
        )
        val lessonId = db.cardDao().insert(
            Card(noteId = lessonNoteId, cardType = CardType.LESSON, queue = Queue.GRAMMAR)
        )
        db.reviewLogDao().insert(
            ReviewLog(
                cardId = recognitionId,
                reviewDatetime = 100L,
                rating = Rating.GOOD,
                stateBefore = CardState.LEARNING,
                scheduledDays = 1,
                elapsedDays = 1,
                source = ReviewSource.SRS_REVIEW
            )
        )

        val candidates = db.cardDao().getNewCardsOrderedPage(
            limit = 20,
            offset = 0,
            maxCefrOrdinal = 0,
            reviewedNotesOnly = true
        )

        assertEquals(setOf(depthId, lessonId), candidates.map { it.id }.toSet())
        assertTrue(candidates.none { it.id == unseenId })

        val a1 = db.cardDao().cefrVocabProgress().single { it.band == "A1" }
        assertEquals(3, a1.total)
        assertEquals(1, a1.mastered)
    }
}
