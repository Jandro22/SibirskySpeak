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
}
