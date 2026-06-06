package com.sibirskyspeak.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
    @Query("SELECT * FROM cards WHERE due <= :now AND state != 'NEW' ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getDueCards(now: Long, limit: Int = 100): List<Card>

    @Query("SELECT * FROM cards WHERE due <= :now AND queue = :queue AND state != 'NEW' ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getDueCardsByQueue(now: Long, queue: Queue, limit: Int = 100): List<Card>

    @Query("SELECT * FROM cards WHERE due <= :cutoff AND state != 'NEW' ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getOverdueCards(cutoff: Long, limit: Int = 100): List<Card>

    @Query("SELECT * FROM cards WHERE due <= :now AND state != 'NEW' ORDER BY due ASC, id ASC")
    suspend fun getAllDueCards(now: Long): List<Card>

    @Query("SELECT * FROM cards WHERE state = 'NEW' ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getNewCards(limit: Int): List<Card>

    @Query("SELECT * FROM cards WHERE noteId = :noteId AND cardType = :cardType LIMIT 1")
    suspend fun getByNoteAndType(noteId: Long, cardType: CardType): Card?

    @Query("SELECT COUNT(*) FROM cards WHERE due <= :now AND state != 'NEW'")
    suspend fun countDue(now: Long): Int

    @Query("SELECT COUNT(*) FROM cards WHERE due <= :now AND queue = :queue AND state != 'NEW'")
    suspend fun countDueByQueue(now: Long, queue: Queue): Int

    @Query("SELECT COUNT(*) FROM cards WHERE queue = :queue")
    suspend fun countByQueue(queue: Queue): Int

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND gramCase = :gramCase AND gramGender = :gramGender AND gramNumber = :gramNumber")
    suspend fun getGrammarCardsForNounCategory(gramCase: String, gramGender: String, gramNumber: String): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND noteId IN (:noteIds)")
    suspend fun getGrammarCardsForNotes(noteIds: List<Long>): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND cardType = 'ASPECT_SELECT'")
    suspend fun getAspectCards(): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR'")
    suspend fun getAllGrammarCards(): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND cardType = 'CASE_FILL' AND gramCase = :gramCase AND gramGender = :gramGender AND gramNumber = :gramNumber ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getCaseDrillCards(gramCase: String, gramGender: String, gramNumber: String, limit: Int): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getGrammarDrillCards(limit: Int): List<Card>

    @Query("SELECT * FROM cards WHERE noteId = :noteId")
    suspend fun getCardsForNote(noteId: Long): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'VOCAB'")
    suspend fun getAllVocabCards(): List<Card>

    @Update
    suspend fun update(card: Card)

    @Insert
    suspend fun insert(card: Card): Long

    @Insert
    suspend fun insertAll(cards: List<Card>): List<Long>
}

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: Note): Long

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): Note?

    @Query("SELECT * FROM notes WHERE lemma = :lemma LIMIT 1")
    suspend fun getByLemma(lemma: String): Note?

    @Query("SELECT * FROM notes WHERE lemma IN (:lemmas)")
    suspend fun getByLemmas(lemmas: List<String>): List<Note>

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun count(): Int

    @Query("SELECT * FROM notes ORDER BY COALESCE(domainFreqRank, generalFreqRank, 2147483647), russian")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<Note>

    @Update
    suspend fun update(note: Note)
}

@Dao
interface ReviewLogDao {
    @Insert
    suspend fun insert(log: ReviewLog)

    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewDatetime >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM review_logs")
    suspend fun countAll(): Int

    // Distinct local-day buckets that have at least one review, newest first.
    // Used for streak and active-day stats without loading every log row.
    @Query("SELECT DISTINCT (reviewDatetime + :tzOffset) / :dayMillis AS day FROM review_logs ORDER BY day DESC")
    suspend fun reviewDayBuckets(tzOffset: Long, dayMillis: Long): List<Long>

    @Query("""
        SELECT rl.rating FROM review_logs rl
        JOIN cards c ON rl.cardId = c.id
        WHERE c.gramCase = :gramCase AND c.gramGender = :gramGender AND c.gramNumber = :gramNumber
        ORDER BY rl.reviewDatetime DESC
        LIMIT :limit
    """)
    suspend fun nounCategoryRatings(gramCase: String, gramGender: String, gramNumber: String, limit: Int = 30): List<Rating>

    @Query("""
        SELECT rl.rating FROM review_logs rl
        JOIN cards c ON rl.cardId = c.id
        JOIN notes n ON c.noteId = n.id
        WHERE n.aktionsart = :aktionsart AND n.aspect = :aspect AND c.gramContextCue = :contextCue
        ORDER BY rl.reviewDatetime DESC
        LIMIT :limit
    """)
    suspend fun aspectCategoryRatings(aktionsart: String, aspect: String, contextCue: String, limit: Int = 30): List<Rating>
}

@Dao
interface ConfusablePairDao {
    @Insert
    suspend fun insert(pair: ConfusablePair): Long

    @Query("SELECT * FROM confusable_pairs WHERE firstNoteId = :noteId OR secondNoteId = :noteId")
    suspend fun getForNote(noteId: Long): List<ConfusablePair>

    @Query("SELECT * FROM confusable_pairs")
    suspend fun getAll(): List<ConfusablePair>
}

@Dao
interface ReaderTextDao {
    @Insert
    suspend fun insert(text: ReaderText): Long

    @Query("SELECT COUNT(*) FROM reader_texts")
    suspend fun count(): Int

    @Query("SELECT * FROM reader_texts ORDER BY createdAt ASC")
    suspend fun getAll(): List<ReaderText>

    @Query("SELECT * FROM reader_texts WHERE id = :id")
    suspend fun getById(id: Long): ReaderText?
}
