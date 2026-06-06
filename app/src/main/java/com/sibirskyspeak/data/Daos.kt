package com.sibirskyspeak.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class CaseCategoryRow(
    val gramCase: String,
    val gramGender: String,
    val gramNumber: String
)

data class AspectCategoryRow(
    val aktionsart: String,
    val aspect: String,
    val contextCue: String
)

@Dao
interface CardDao {
    @Query("SELECT * FROM cards WHERE due <= :now AND state != 'NEW' AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getDueCards(now: Long, limit: Int = 100): List<Card>

    @Query("SELECT * FROM cards WHERE due <= :now AND queue = :queue AND state != 'NEW' AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getDueCardsByQueue(now: Long, queue: Queue, limit: Int = 100): List<Card>

    @Query("SELECT * FROM cards WHERE due <= :cutoff AND state != 'NEW' AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getOverdueCards(cutoff: Long, limit: Int = 100): List<Card>

    @Query("SELECT * FROM cards WHERE due <= :now AND state != 'NEW' AND suspended = 0 ORDER BY due ASC, id ASC")
    suspend fun getAllDueCards(now: Long): List<Card>

    @Query("SELECT * FROM cards WHERE state = 'NEW' AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getNewCards(limit: Int): List<Card>

    /**
     * New cards in curriculum order: the A1 starter tier (tier 0) first, by unit,
     * then everything else interleaved by frequency rank (the existing unified
     * domain/general sequencing). This is what front-loads everyday A1 vocabulary
     * ahead of the formal/political domain.
     */
    @Query("""
        SELECT c.* FROM cards c
        JOIN notes n ON c.noteId = n.id
        WHERE c.state = 'NEW' AND c.suspended = 0
        ORDER BY
            (CASE WHEN n.tier = 0 THEN 0 ELSE 1 END) ASC,
            COALESCE(n.unit, 2147483647) ASC,
            COALESCE(n.domainFreqRank, n.generalFreqRank, 2147483647) ASC,
            c.id ASC
        LIMIT :limit
    """)
    suspend fun getNewCardsOrdered(limit: Int): List<Card>

    /** Concept ids whose LESSON card has been seen (drills on them may now surface). */
    @Query("SELECT DISTINCT gramConcept FROM cards WHERE cardType = 'LESSON' AND gramConcept IS NOT NULL AND state != 'NEW'")
    suspend fun getIntroducedConceptIds(): List<String>

    /** Concept ids that have a LESSON card at all (so we know which drills to gate). */
    @Query("SELECT DISTINCT gramConcept FROM cards WHERE cardType = 'LESSON' AND gramConcept IS NOT NULL")
    suspend fun getConceptIdsWithLessons(): List<String>

    @Query("SELECT * FROM cards WHERE noteId = :noteId AND cardType = :cardType LIMIT 1")
    suspend fun getByNoteAndType(noteId: Long, cardType: CardType): Card?

    @Query("SELECT COUNT(*) FROM cards WHERE due <= :now AND state != 'NEW' AND suspended = 0")
    suspend fun countDue(now: Long): Int

    @Query("SELECT COUNT(*) FROM cards WHERE due <= :now AND queue = :queue AND state != 'NEW' AND suspended = 0")
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

    @Query("""
        SELECT DISTINCT gramCase, gramGender, gramNumber
        FROM cards
        WHERE queue = 'GRAMMAR'
          AND cardType = 'CASE_FILL'
          AND gramCase IS NOT NULL
          AND gramGender IS NOT NULL
          AND gramNumber IS NOT NULL
    """)
    suspend fun getCaseCategoryKeys(): List<CaseCategoryRow>

    @Query("""
        SELECT DISTINCT n.aktionsart AS aktionsart, n.aspect AS aspect, c.gramContextCue AS contextCue
        FROM cards c
        JOIN notes n ON c.noteId = n.id
        WHERE c.queue = 'GRAMMAR'
          AND c.cardType = 'ASPECT_SELECT'
          AND n.aktionsart IS NOT NULL
          AND n.aspect IS NOT NULL
          AND c.gramContextCue IS NOT NULL
    """)
    suspend fun getAspectCategoryKeys(): List<AspectCategoryRow>

    @Query("""
        SELECT DISTINCT gramContextCue
        FROM cards
        WHERE queue = 'GRAMMAR'
          AND cardType = 'VERB_FORM'
          AND gramContextCue IS NOT NULL
    """)
    suspend fun getVerbFormCategoryKeys(): List<String>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND cardType = 'CASE_FILL' AND gramCase = :gramCase AND gramGender = :gramGender AND gramNumber = :gramNumber AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getCaseDrillCards(gramCase: String, gramGender: String, gramNumber: String, limit: Int): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND cardType = 'VERB_FORM' AND gramContextCue = :formKey AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getVerbFormCards(formKey: String, limit: Int): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getGrammarDrillCards(limit: Int): List<Card>

    @Query("SELECT * FROM cards WHERE noteId = :noteId")
    suspend fun getCardsForNote(noteId: Long): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'VOCAB'")
    suspend fun getAllVocabCards(): List<Card>

    @Query("""
        SELECT DISTINCT noteId
        FROM cards
        WHERE queue = 'VOCAB'
          AND (
              state = 'GRADUATED'
              OR reps > 0
              OR consecutiveCorrect > 0
              OR lastReview IS NOT NULL
          )
    """)
    suspend fun getKnownVocabNoteIds(): List<Long>

    @Update
    suspend fun update(card: Card)

    @Update
    suspend fun updateAll(cards: List<Card>)

    @Query("""
        UPDATE cards
        SET state = 'GRADUATED'
        WHERE queue = 'GRAMMAR'
          AND cardType = 'CASE_FILL'
          AND gramCase = :gramCase
          AND gramGender = :gramGender
          AND gramNumber = :gramNumber
          AND state != 'GRADUATED'
    """)
    suspend fun graduateCaseCategory(gramCase: String, gramGender: String, gramNumber: String): Int

    @Query("""
        UPDATE cards
        SET state = 'GRADUATED'
        WHERE queue = 'GRAMMAR'
          AND cardType = 'ASPECT_SELECT'
          AND gramContextCue = :contextCue
          AND state != 'GRADUATED'
          AND noteId IN (
              SELECT id FROM notes
              WHERE aktionsart = :aktionsart AND aspect = :aspect
          )
    """)
    suspend fun graduateAspectCategory(aktionsart: String, aspect: String, contextCue: String): Int

    @Query("""
        UPDATE cards
        SET state = 'GRADUATED'
        WHERE queue = 'GRAMMAR'
          AND cardType = 'VERB_FORM'
          AND gramContextCue = :formKey
          AND state != 'GRADUATED'
    """)
    suspend fun graduateVerbFormCategory(formKey: String): Int

    @Query("""
        UPDATE cards
        SET state = 'GRADUATED'
        WHERE queue = 'VOCAB'
          AND state != 'GRADUATED'
          AND noteId IN (
              SELECT id FROM notes WHERE encounterCount >= :minEncounterCount
          )
    """)
    suspend fun graduateVocabForEncounteredNotes(minEncounterCount: Int): Int

    @Insert
    suspend fun insert(card: Card): Long

    @Insert
    suspend fun insertAll(cards: List<Card>): List<Long>
}

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: Note): Long

    @Insert
    suspend fun insertAll(notes: List<Note>): List<Long>

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

    @Query(
        """
        SELECT * FROM notes
        WHERE russian LIKE '%' || :query || '%'
           OR lemma LIKE '%' || :query || '%'
           OR translation LIKE '%' || :query || '%'
        ORDER BY COALESCE(domainFreqRank, generalFreqRank, 2147483647), russian
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int = 50): List<Note>

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

    // Cards introduced (first-ever review) since [since], i.e. logs whose card was
    // still NEW when reviewed. Drives the daily new-card throttle.
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewDatetime >= :since AND stateBefore = 'NEW'")
    suspend fun countNewIntroducedSince(since: Long): Int

    // Removes the most recent log row for a card. Used by the undo path to roll
    // back a review (the matching Card row is restored separately from a snapshot).
    @Query("DELETE FROM review_logs WHERE id = (SELECT MAX(id) FROM review_logs WHERE cardId = :cardId)")
    suspend fun deleteLatestForCard(cardId: Long)

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

    @Query("""
        SELECT rl.rating FROM review_logs rl
        JOIN cards c ON rl.cardId = c.id
        WHERE c.cardType = 'VERB_FORM' AND c.gramContextCue = :formKey
        ORDER BY rl.reviewDatetime DESC
        LIMIT :limit
    """)
    suspend fun verbFormCategoryRatings(formKey: String, limit: Int = 30): List<Rating>
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

    @Insert
    suspend fun insertAll(texts: List<ReaderText>): List<Long>

    @Query("SELECT COUNT(*) FROM reader_texts")
    suspend fun count(): Int

    @Query("SELECT * FROM reader_texts ORDER BY createdAt ASC")
    suspend fun getAll(): List<ReaderText>

    @Query("SELECT * FROM reader_texts WHERE id = :id")
    suspend fun getById(id: Long): ReaderText?
}
