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

data class DueDayCount(
    val day: Int,
    val count: Int
)

data class ReviewCategoryRatingRow(
    val cardType: CardType,
    val gramCase: String?,
    val gramGender: String?,
    val gramNumber: String?,
    val contextCue: String?,
    val aktionsart: String?,
    val aspect: String?,
    val rating: Rating
)

/** Minimal per-review projection consumed by the on-device FSRS weight fit. */
data class ReviewFitRow(
    val cardId: Long,
    val reviewDatetime: Long,
    val rating: Rating,
    val stateBefore: CardState,
    val elapsedDays: Int,
    val stabilityBefore: Double
)

@Dao
interface CardDao {
    @Query("SELECT * FROM cards WHERE due <= :now AND state NOT IN ('NEW', 'GRADUATED') AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getDueCards(now: Long, limit: Int = 100): List<Card>

    @Query("SELECT * FROM cards WHERE due <= :now AND queue = :queue AND state NOT IN ('NEW', 'GRADUATED') AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getDueCardsByQueue(now: Long, queue: Queue, limit: Int = 100): List<Card>

    @Query("SELECT * FROM cards WHERE due <= :cutoff AND state NOT IN ('NEW', 'GRADUATED') AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getOverdueCards(cutoff: Long, limit: Int = 100): List<Card>

    @Query("SELECT * FROM cards WHERE due <= :now AND state NOT IN ('NEW', 'GRADUATED') AND suspended = 0 ORDER BY due ASC, id ASC")
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
          AND n.status NOT IN ('KNOWN', 'IGNORED')
          AND n.translation != 'lookup pending'
          AND (c.queue != 'GRAMMAR' OR c.cardType = 'LESSON' OR n.encounterCount > 0)
          AND (
              c.cardType NOT IN ('MEANING_TO_RU', 'CLOZE', 'SPEAK', 'AUDIO_TO_RU', 'DICTATION', 'SENTENCE_BUILD', 'STRESS_MARK')
              OR EXISTS (
                  SELECT 1 FROM cards recognition
                  WHERE recognition.noteId = c.noteId
                    AND recognition.cardType = 'RU_TO_MEANING'
                    AND recognition.reps >= 3
                    AND recognition.consecutiveCorrect >= 2
                    AND recognition.state IN ('REVIEW', 'GRADUATED')
              )
          )
        ORDER BY
            (CASE WHEN n.tier = 0 THEN 0 ELSE 1 END) ASC,
            COALESCE(n.unit, 2147483647) ASC,
            COALESCE(n.domainFreqRank, n.generalFreqRank, 2147483647) ASC,
            c.id ASC
        LIMIT :limit
    """)
    suspend fun getNewCardsOrdered(limit: Int): List<Card>

    @Query("""
        SELECT c.* FROM cards c
        JOIN notes n ON c.noteId = n.id
        WHERE c.state = 'NEW' AND c.suspended = 0
          AND n.status NOT IN ('KNOWN', 'IGNORED')
          AND n.translation != 'lookup pending'
          AND (c.queue != 'GRAMMAR' OR c.cardType = 'LESSON' OR n.encounterCount > 0)
          AND (
              c.cardType NOT IN ('MEANING_TO_RU', 'CLOZE', 'SPEAK', 'AUDIO_TO_RU', 'DICTATION', 'SENTENCE_BUILD', 'STRESS_MARK')
              OR EXISTS (
                  SELECT 1 FROM cards recognition
                  WHERE recognition.noteId = c.noteId
                    AND recognition.cardType = 'RU_TO_MEANING'
                    AND recognition.reps >= 3
                    AND recognition.consecutiveCorrect >= 2
                    AND recognition.state IN ('REVIEW', 'GRADUATED')
              )
          )
        ORDER BY
            (CASE WHEN n.tier = 0 THEN 0 ELSE 1 END) ASC,
            COALESCE(n.unit, 2147483647) ASC,
            COALESCE(n.domainFreqRank, n.generalFreqRank, 2147483647) ASC,
            c.id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getNewCardsOrderedPage(limit: Int, offset: Int): List<Card>

    /** Mark a single note's VOCAB cards known (graduated, pushed far out) — used when
     *  the learner marks the word KNOWN/IGNORED in the reader, so practice stops
     *  quizzing a word they already know. */
    @Query("UPDATE cards SET state = 'GRADUATED', due = :due WHERE noteId = :noteId AND queue = 'VOCAB' AND (state != 'GRADUATED' OR due != :due)")
    suspend fun graduateVocabForNote(noteId: Long, due: Long): Int

    /** Re-activate a note's graduated VOCAB cards as fresh NEW — used when the learner
     *  marks a previously-known word as LEARNING again, pulling it back into practice. */
    @Query("UPDATE cards SET state = 'NEW', due = 0, reps = 0, lapses = 0, stability = 0.0, difficulty = 0.0, elapsedDays = 0, scheduledDays = 0, consecutiveCorrect = 0, suspended = 0, lastReview = NULL WHERE noteId = :noteId AND queue = 'VOCAB' AND state = 'GRADUATED'")
    suspend fun reactivateVocabForNote(noteId: Long): Int

    /** Concept ids whose LESSON card has been seen (drills on them may now surface). */
    @Query("SELECT DISTINCT gramConcept FROM cards WHERE cardType = 'LESSON' AND gramConcept IS NOT NULL AND state != 'NEW'")
    suspend fun getIntroducedConceptIds(): List<String>

    /** Concept ids that have a LESSON card at all (so we know which drills to gate). */
    @Query("SELECT DISTINCT gramConcept FROM cards WHERE cardType = 'LESSON' AND gramConcept IS NOT NULL")
    suspend fun getConceptIdsWithLessons(): List<String>

    @Query("SELECT * FROM cards WHERE noteId = :noteId AND cardType = :cardType LIMIT 1")
    suspend fun getByNoteAndType(noteId: Long, cardType: CardType): Card?

    @Query("SELECT COUNT(*) FROM cards WHERE due <= :now AND state NOT IN ('NEW', 'GRADUATED') AND suspended = 0")
    suspend fun countDue(now: Long): Int

    /** Cards becoming due in the window (:start, :end], for the upcoming-load forecast. */
    @Query("SELECT COUNT(*) FROM cards WHERE due > :start AND due <= :end AND state NOT IN ('NEW', 'GRADUATED') AND suspended = 0")
    suspend fun countDueBetween(start: Long, end: Long): Int

    @Query("""
        SELECT CAST((due - :start - 1) / :dayMillis AS INTEGER) AS day, COUNT(*) AS count
        FROM cards
        WHERE due > :start AND due <= :end AND state NOT IN ('NEW', 'GRADUATED') AND suspended = 0
        GROUP BY day
    """)
    suspend fun countDueByDay(start: Long, end: Long, dayMillis: Long): List<DueDayCount>

    /** Auto-parked leeches: suspended cards that lapsed past the threshold. */
    @Query("SELECT * FROM cards WHERE suspended = 1 AND lapses >= :threshold ORDER BY lapses DESC, id ASC")
    suspend fun getLeechCards(threshold: Int): List<Card>

    @Query("SELECT * FROM cards WHERE reps >= :minReps AND (lapses > 0 OR difficulty >= 8.0) AND state != 'GRADUATED' AND suspended = 0 ORDER BY lapses DESC, difficulty DESC, reps DESC LIMIT :limit")
    suspend fun getProblemCards(minReps: Int = 2, limit: Int = 20): List<Card>

    @Query("SELECT COUNT(*) FROM cards WHERE due <= :now AND queue = :queue AND state NOT IN ('NEW', 'GRADUATED') AND suspended = 0")
    suspend fun countDueByQueue(now: Long, queue: Queue): Int

    @Query("SELECT COUNT(*) FROM cards WHERE queue = :queue")
    suspend fun countByQueue(queue: Queue): Int

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND gramCase = :gramCase AND gramGender = :gramGender AND gramNumber = :gramNumber")
    suspend fun getGrammarCardsForNounCategory(gramCase: String, gramGender: String, gramNumber: String): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND noteId IN (:noteIds)")
    suspend fun getGrammarCardsForNotes(noteIds: List<Long>): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND cardType = 'ASPECT_SELECT' AND state != 'GRADUATED' AND suspended = 0")
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

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND cardType = 'CASE_FILL' AND gramCase = :gramCase AND gramGender = :gramGender AND gramNumber = :gramNumber AND state != 'GRADUATED' AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getCaseDrillCards(gramCase: String, gramGender: String, gramNumber: String, limit: Int): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND cardType = 'VERB_FORM' AND gramContextCue = :formKey AND state != 'GRADUATED' AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getVerbFormCards(formKey: String, limit: Int): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND state != 'GRADUATED' AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getGrammarDrillCards(limit: Int): List<Card>

    @Query("SELECT * FROM cards WHERE noteId = :noteId")
    suspend fun getCardsForNote(noteId: Long): List<Card>

    @Query("SELECT * FROM cards WHERE noteId IN (:noteIds)")
    suspend fun getCardsForNotes(noteIds: List<Long>): List<Card>

    @Query("SELECT * FROM cards WHERE id IN (:cardIds)")
    suspend fun getByIds(cardIds: List<Long>): List<Card>

    @Query("SELECT * FROM cards")
    suspend fun getAll(): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'VOCAB'")
    suspend fun getAllVocabCards(): List<Card>

    @Query("""
        SELECT DISTINCT noteId
        FROM cards
        WHERE queue = 'VOCAB'
          AND suspended = 0
          AND (
              state = 'GRADUATED'
              OR (reps >= 2 AND consecutiveCorrect >= 2 AND state = 'REVIEW')
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
          AND reps >= 3
          AND consecutiveCorrect >= 3
    """)
    suspend fun graduateCaseCategory(gramCase: String, gramGender: String, gramNumber: String): Int

    @Query("""
        UPDATE cards
        SET state = 'GRADUATED'
        WHERE queue = 'GRAMMAR'
          AND cardType = 'ASPECT_SELECT'
          AND gramContextCue = :contextCue
          AND state != 'GRADUATED'
          AND reps >= 3
          AND consecutiveCorrect >= 3
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
          AND reps >= 3
          AND consecutiveCorrect >= 3
    """)
    suspend fun graduateVerbFormCategory(formKey: String): Int

    @Query("""
        UPDATE cards
        SET state = 'GRADUATED'
        WHERE queue = 'VOCAB'
          AND state != 'GRADUATED'
          AND noteId IN (
              SELECT noteId FROM reader_encounters GROUP BY noteId HAVING COUNT(*) >= :minEncounterCount
          )
    """)
    suspend fun graduateVocabForReaderEncounters(minEncounterCount: Int): Int

    @Insert
    suspend fun insert(card: Card): Long

    @Insert
    suspend fun insertAll(cards: List<Card>): List<Long>

    @Query("UPDATE cards SET noteId = :targetNoteId WHERE id = :cardId")
    suspend fun moveToNote(cardId: Long, targetNoteId: Long)

    @Query("DELETE FROM cards WHERE id = :cardId")
    suspend fun deleteById(cardId: Long)

    @Query("UPDATE cards SET suspended = 1 WHERE noteId = :noteId AND cardType IN ('MEANING_TO_RU', 'CLOZE', 'SENTENCE_BUILD') AND suspended = 0")
    suspend fun suspendAmbiguousProduction(noteId: Long): Int

    @Query("UPDATE cards SET suspended = 1 WHERE noteId = :noteId AND suspended = 0")
    suspend fun suspendAllForNote(noteId: Long): Int
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

    @Query("SELECT * FROM notes WHERE cefrLevel IN (:levels)")
    suspend fun getByCefrLevels(levels: List<String>): List<Note>

    @Update
    suspend fun update(note: Note)

    @Update
    suspend fun updateAll(notes: List<Note>)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE notes SET aspectPartner = :targetId WHERE aspectPartner = :sourceId")
    suspend fun moveAspectPartnerReferences(sourceId: Long, targetId: Long): Int

    @Query("UPDATE notes SET aspectPartner = NULL WHERE aspectPartner = id")
    suspend fun clearSelfAspectPartners(): Int
}

@Dao
interface ReviewLogDao {
    @Insert
    suspend fun insert(log: ReviewLog)

    @Insert
    suspend fun insertAll(logs: List<ReviewLog>)

    @Query("SELECT * FROM review_logs ORDER BY reviewDatetime ASC, id ASC")
    suspend fun getAll(): List<ReviewLog>

    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewDatetime >= :since AND source != 'READER_LOOKUP'")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM review_logs WHERE source != 'READER_LOOKUP'")
    suspend fun countAll(): Int

    /** Recall quality and card maturity both contribute; reader lookups earn no XP. */
    @Query("""
        SELECT COALESCE(SUM(
            CASE rating WHEN 'AGAIN' THEN 2 WHEN 'HARD' THEN 8 WHEN 'GOOD' THEN 10 WHEN 'EASY' THEN 14 END
            + CASE WHEN stateBefore IN ('REVIEW', 'RELEARNING') AND elapsedDays > 0 THEN 2 ELSE 0 END
        ), 0)
        FROM review_logs WHERE source != 'READER_LOOKUP'
    """)
    suspend fun weightedXp(): Int

    /**
     * Reviews of mature cards (already in the REVIEW/RELEARNING phase) within a
     * rolling window. The window keeps the retention instrument *responsive*: a
     * lifetime count becomes a frozen slab average after a few thousand reviews,
     * which silently calcifies every retention-driven adaptation. Pass [since] = 0
     * for the all-time figure.
     */
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewDatetime >= :since AND stateBefore IN ('REVIEW', 'RELEARNING') AND elapsedDays > 0 AND source != 'READER_LOOKUP'")
    suspend fun matureReviewCount(since: Long = 0): Int

    /** Mature-card reviews the learner got right (did not lapse), within the same
     * rolling window as [matureReviewCount]. True-retention numerator. */
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewDatetime >= :since AND stateBefore IN ('REVIEW', 'RELEARNING') AND elapsedDays > 0 AND rating != 'AGAIN' AND source != 'READER_LOOKUP'")
    suspend fun matureRetainedCount(since: Long = 0): Int

    // Lexemes introduced since [since]. Multiple facets of one note consume one
    // daily slot, so breadth is not divided by the number of card types.
    @Query("""
        SELECT COUNT(DISTINCT cards.noteId)
        FROM review_logs
        INNER JOIN cards ON cards.id = review_logs.cardId
        WHERE reviewDatetime >= :since
          AND stateBefore = 'NEW'
          AND source != 'READER_LOOKUP'
          AND cards.cardType != 'LESSON'
    """)
    suspend fun countNewIntroducedSince(since: Long): Int

    /** Notes with real card history, excluding passive lessons and reader lookups. */
    @Query("""
        SELECT DISTINCT cards.noteId
        FROM review_logs
        INNER JOIN cards ON cards.id = review_logs.cardId
        WHERE review_logs.source != 'READER_LOOKUP'
          AND cards.cardType != 'LESSON'
    """)
    suspend fun getReviewedNoteIds(): List<Long>

    /** Card variants reviewed in the current local day. The queue uses this to
     * bury only sibling variants, while still allowing the failed card itself to
     * return for relearning. */
    @Query("""
        SELECT DISTINCT cards.*
        FROM review_logs
        INNER JOIN cards ON cards.id = review_logs.cardId
        WHERE review_logs.reviewDatetime >= :since
          AND review_logs.source != 'READER_LOOKUP'
    """)
    suspend fun getReviewedCardsSince(since: Long): List<Card>

    // Removes the most recent log row for a card. Used by the undo path to roll
    // back a review (the matching Card row is restored separately from a snapshot).
    @Query("DELETE FROM review_logs WHERE id = (SELECT MAX(id) FROM review_logs WHERE cardId = :cardId)")
    suspend fun deleteLatestForCard(cardId: Long)

    // Distinct local-day buckets that have at least one review, newest first.
    // Used for streak and active-day stats without loading every log row.
    @Query("SELECT DISTINCT (reviewDatetime + :tzOffset) / :dayMillis AS day FROM review_logs WHERE source != 'READER_LOOKUP' ORDER BY day DESC")
    suspend fun reviewDayBuckets(tzOffset: Long, dayMillis: Long): List<Long>

    /** One bounded query replaces a separate query for every grammar category. */
    @Query("""
        SELECT c.cardType AS cardType,
               c.gramCase AS gramCase,
               c.gramGender AS gramGender,
               c.gramNumber AS gramNumber,
               c.gramContextCue AS contextCue,
               n.aktionsart AS aktionsart,
               n.aspect AS aspect,
               rl.rating AS rating
        FROM review_logs rl
        JOIN cards c ON rl.cardId = c.id
        JOIN notes n ON c.noteId = n.id
        WHERE rl.source != 'READER_LOOKUP'
          AND c.cardType IN ('CASE_FILL', 'ASPECT_SELECT', 'VERB_FORM')
        ORDER BY rl.reviewDatetime DESC, rl.id DESC
        LIMIT :limit
    """)
    suspend fun recentCategoryRatings(limit: Int = 100000): List<ReviewCategoryRatingRow>

    @Query("""
        SELECT rl.rating FROM review_logs rl
        JOIN cards c ON rl.cardId = c.id
        WHERE c.gramCase = :gramCase AND c.gramGender = :gramGender AND c.gramNumber = :gramNumber
          AND rl.source != 'READER_LOOKUP'
        ORDER BY rl.reviewDatetime DESC
        LIMIT :limit
    """)
    suspend fun nounCategoryRatings(gramCase: String, gramGender: String, gramNumber: String, limit: Int = 30): List<Rating>

    @Query("""
        SELECT rl.rating FROM review_logs rl
        JOIN cards c ON rl.cardId = c.id
        JOIN notes n ON c.noteId = n.id
        WHERE n.aktionsart = :aktionsart AND n.aspect = :aspect AND c.gramContextCue = :contextCue
          AND rl.source != 'READER_LOOKUP'
        ORDER BY rl.reviewDatetime DESC
        LIMIT :limit
    """)
    suspend fun aspectCategoryRatings(aktionsart: String, aspect: String, contextCue: String, limit: Int = 30): List<Rating>

    @Query("""
        SELECT rl.rating FROM review_logs rl
        JOIN cards c ON rl.cardId = c.id
        WHERE c.cardType = 'VERB_FORM' AND c.gramContextCue = :formKey
          AND rl.source != 'READER_LOOKUP'
        ORDER BY rl.reviewDatetime DESC
        LIMIT :limit
    """)
    suspend fun verbFormCategoryRatings(formKey: String, limit: Int = 30): List<Rating>

    @Query("UPDATE review_logs SET cardId = :targetCardId WHERE cardId = :sourceCardId")
    suspend fun moveLogs(sourceCardId: Long, targetCardId: Long)

    /**
     * Per-review rows for the on-device FSRS weight fit, oldest first and grouped by
     * card so the fitter can walk each card's history (first rating → second outcome,
     * and stability-before → recall for the decay curve). Reader lookups never enter.
     */
    @Query("""
        SELECT cardId, reviewDatetime, rating, stateBefore, elapsedDays, stabilityBefore
        FROM review_logs
        WHERE source != 'READER_LOOKUP' AND reviewDatetime >= :since
        ORDER BY cardId ASC, reviewDatetime ASC, id ASC
    """)
    suspend fun reviewFitRows(since: Long = 0): List<ReviewFitRow>
}

@Dao
interface ConfusablePairDao {
    @Insert
    suspend fun insert(pair: ConfusablePair): Long

    @Query("SELECT * FROM confusable_pairs WHERE firstNoteId = :noteId OR secondNoteId = :noteId")
    suspend fun getForNote(noteId: Long): List<ConfusablePair>

    @Query("SELECT * FROM confusable_pairs")
    suspend fun getAll(): List<ConfusablePair>

    @Query("UPDATE confusable_pairs SET firstNoteId = :targetId WHERE firstNoteId = :sourceId")
    suspend fun moveFirstReferences(sourceId: Long, targetId: Long)

    @Query("UPDATE confusable_pairs SET secondNoteId = :targetId WHERE secondNoteId = :sourceId")
    suspend fun moveSecondReferences(sourceId: Long, targetId: Long)

    @Query("DELETE FROM confusable_pairs WHERE firstNoteId = secondNoteId")
    suspend fun deleteSelfPairs()

    @Query("""
        DELETE FROM confusable_pairs
        WHERE id NOT IN (
            SELECT MIN(id) FROM confusable_pairs
            GROUP BY
                CASE WHEN firstNoteId < secondNoteId THEN firstNoteId ELSE secondNoteId END,
                CASE WHEN firstNoteId < secondNoteId THEN secondNoteId ELSE firstNoteId END,
                reason
        )
    """)
    suspend fun deleteDuplicatePairs(): Int
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

    @Query("DELETE FROM reader_texts WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ReadingScheduleDao {
    @Insert
    suspend fun insert(schedule: ReadingSchedule): Long

    @Insert
    suspend fun insertAll(schedules: List<ReadingSchedule>): List<Long>

    @Update
    suspend fun update(schedule: ReadingSchedule)

    @Query("SELECT * FROM reading_schedules WHERE readerTextId = :readerTextId")
    suspend fun get(readerTextId: Long): ReadingSchedule?

    @Query("SELECT * FROM reading_schedules WHERE due <= :now ORDER BY due ASC, reps ASC LIMIT 1")
    suspend fun nextDue(now: Long): ReadingSchedule?

    @Query("SELECT * FROM reading_schedules")
    suspend fun getAll(): List<ReadingSchedule>

    @Query("DELETE FROM reading_schedules WHERE readerTextId = :readerTextId")
    suspend fun deleteForText(readerTextId: Long)
}

@Dao
interface ReaderEncounterDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insert(encounter: ReaderEncounter): Long

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertAll(encounters: List<ReaderEncounter>): List<Long>

    @Query("SELECT * FROM reader_encounters")
    suspend fun getAll(): List<ReaderEncounter>

    @Query("SELECT * FROM reader_encounters WHERE readerTextId = :readerTextId")
    suspend fun getForText(readerTextId: Long): List<ReaderEncounter>

    @Query("SELECT * FROM reader_encounters WHERE noteId = :noteId")
    suspend fun getForNote(noteId: Long): List<ReaderEncounter>

    @Query("SELECT noteId FROM reader_encounters GROUP BY noteId HAVING COUNT(*) >= :minimum")
    suspend fun noteIdsWithMinimumEncounters(minimum: Int): List<Long>

    @Query("DELETE FROM reader_encounters WHERE readerTextId = :readerTextId")
    suspend fun deleteForText(readerTextId: Long)

    @Query("DELETE FROM reader_encounters WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: Long)
}

@Dao
interface ReadingActivityDao {
    @Insert
    suspend fun insert(activity: ReadingActivity): Long

    @Insert
    suspend fun insertAll(activities: List<ReadingActivity>): List<Long>

    @Query("SELECT * FROM reading_activities ORDER BY completedAt ASC, id ASC")
    suspend fun getAll(): List<ReadingActivity>

    @Query("SELECT * FROM reading_activities WHERE readerTextId = :readerTextId ORDER BY completedAt ASC")
    suspend fun getForText(readerTextId: Long): List<ReadingActivity>

    @Query("SELECT COUNT(*) FROM reading_activities")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM reading_activities WHERE completedAt >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT DISTINCT (completedAt + :tzOffset) / :dayMillis FROM reading_activities ORDER BY 1 DESC")
    suspend fun dayBuckets(tzOffset: Long, dayMillis: Long): List<Long>

    @Query("UPDATE reading_activities SET readerTextId = :targetId WHERE readerTextId = :sourceId")
    suspend fun moveToText(sourceId: Long, targetId: Long): Int
}

@Dao
interface TelemetryDao {
    @Insert
    suspend fun insert(event: TelemetryEvent): Long

    @Insert
    suspend fun insertAll(events: List<TelemetryEvent>): List<Long>

    @Query("SELECT * FROM telemetry_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 1000): List<TelemetryEvent>

    /** Every recorded event, oldest first — used by the full-state backup export. */
    @Query("SELECT * FROM telemetry_events ORDER BY timestamp ASC")
    suspend fun getAll(): List<TelemetryEvent>

    @Query("DELETE FROM telemetry_events WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT DISTINCT (timestamp + :tzOffset) / :dayMillis FROM telemetry_events WHERE eventType = :eventType ORDER BY 1 DESC")
    suspend fun eventDayBuckets(eventType: String, tzOffset: Long, dayMillis: Long): List<Long>

    @Query("SELECT COUNT(*) FROM telemetry_events WHERE eventType = :eventType")
    suspend fun countByType(eventType: String): Int

    @Query("SELECT COUNT(*) FROM telemetry_events WHERE eventType = :eventType AND timestamp >= :since")
    suspend fun countByTypeSince(eventType: String, since: Long): Int
}
