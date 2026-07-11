package com.sibirskyspeak.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sibirskyspeak.scheduler.FsrsScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real repository review/undo transaction against real Room DAOs. */
@RunWith(AndroidJUnit4::class)
class ReviewTransactionInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: LearningRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LearningRepository(
            noteDao = db.noteDao(),
            cardDao = db.cardDao(),
            reviewLogDao = db.reviewLogDao(),
            confusablePairDao = db.confusablePairDao(),
            readerTextDao = db.readerTextDao(),
            scheduler = FsrsScheduler(),
            transactionRunner = { block -> db.withTransaction(block) }
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun reviewAndUndoRestoreCardAndLogAtomically() = runBlocking {
        val noteId = db.noteDao().insert(Note(russian = "дом", translation = "house", partOfSpeech = "noun", lemma = "дом", status = WordStatus.LEARNING))
        val card = Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, due = 0L, state = CardState.REVIEW)
        val cardId = db.cardDao().insert(card)
        val stored = db.cardDao().getByIds(listOf(cardId)).single()

        repository.review(stored, Rating.GOOD, now = 1_000L)
        val afterReview = db.cardDao().getByIds(listOf(cardId)).single()
        assertNotEquals(stored, afterReview)
        assertEquals(1, db.reviewLogDao().getAll().size)

        repository.undoLastReview()
        assertEquals(stored, db.cardDao().getByIds(listOf(cardId)).single())
        assertEquals(0, db.reviewLogDao().getAll().size)
    }
}
