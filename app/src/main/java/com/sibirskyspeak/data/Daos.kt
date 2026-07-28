package com.sibirskyspeak.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sibirskyspeak.generation.FrameLexeme
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

data class ActivityDayCount(
    val day: Long,
    val count: Int
)

data class CardDashboardCounts(
    val vocabCards: Int,
    val grammarCards: Int,
    val dueVocab: Int,
    val dueGrammar: Int
)

data class NoteQualityCounts(
    val totalNotes: Int,
    val readyNominalRows: Int,
    val aspectReadyVerbRows: Int,
    val verifiedAktionsartVerbRows: Int,
    val domainRankedRows: Int,
    val exampleRows: Int
)

/** Compact curriculum-progress projections. Keeping this aggregation in SQLite
 * avoids materializing and filtering every card row during each plan build. */
data class UnitVocabProgressRow(
    val band: String,
    val unit: Int,
    val total: Int,
    val mastered: Int,
    val introduced: Int
)

data class CefrVocabProgressRow(
    val band: String,
    val total: Int,
    val mastered: Int
)

data class UnitGrammarObjectiveProgressRow(
    val band: String,
    val unit: Int,
    val objective: String,
    val mastered: Int
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

/** Mature-review retention broken out by card facet, so a low aggregate number can
 *  be attributed to specific quiz types (e.g. typed-production vs. recognition). */
data class CardTypeRetention(
    val cardType: CardType,
    val total: Int,
    val retained: Int
)

/** Compact per-concept probation state. SQLite performs the sibling-card reduction
 * before Room allocates Kotlin objects for the session planner. */
data class GrammarConceptOutcome(
    val concept: String,
    val probationCardId: Long,
    val everSucceeded: Boolean
)

@Dao
interface NoteEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun ensure(value: NoteEvidence): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: NoteEvidence): Long
    @Query("SELECT * FROM note_evidence WHERE noteId = :noteId") suspend fun get(noteId: Long): NoteEvidence?
    @Query("SELECT * FROM note_evidence") suspend fun all(): List<NoteEvidence>
    @Query("DELETE FROM note_evidence WHERE noteId = :noteId") suspend fun delete(noteId: Long): Int
    @Query("UPDATE note_evidence SET directRetrievals = directRetrievals + 1, lastDirectAt = :at WHERE noteId = :noteId") suspend fun incrementDirect(noteId: Long, at: Long): Int
    @Query("UPDATE note_evidence SET passiveExposures = passiveExposures + 1, lastPassiveAt = :at WHERE noteId = :noteId") suspend fun incrementPassive(noteId: Long, at: Long): Int
    @Query("UPDATE note_evidence SET completedReadings = completedReadings + 1 WHERE noteId = :noteId") suspend fun incrementReading(noteId: Long): Int
    @Query("UPDATE note_evidence SET lookups = lookups + 1, lastLookupAt = :at WHERE noteId = :noteId") suspend fun incrementLookup(noteId: Long, at: Long): Int
    @Query("UPDATE note_evidence SET placementPriors = placementPriors + 1 WHERE noteId = :noteId") suspend fun incrementPlacement(noteId: Long): Int
}

@Dao
interface NoteFormDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(values: List<NoteForm>): List<Long>
    @Query("SELECT * FROM note_forms") suspend fun all(): List<NoteForm>
    @Query("SELECT COUNT(*) FROM note_forms") suspend fun count(): Int
    @Query("DELETE FROM note_forms WHERE noteId = :noteId") suspend fun deleteForNote(noteId: Long): Int
}

@Dao
interface CardDao {
    @Query("""
        SELECT
          COALESCE(SUM(CASE WHEN c.queue = 'VOCAB' THEN 1 ELSE 0 END),0) AS vocabCards,
          COALESCE(SUM(CASE WHEN c.queue = 'GRAMMAR' THEN 1 ELSE 0 END),0) AS grammarCards,
          COALESCE(SUM(CASE WHEN c.queue = 'VOCAB' AND c.due <= :now AND c.state NOT IN ('NEW','GRADUATED') AND c.suspended = 0 AND n.status != 'IGNORED' THEN 1 ELSE 0 END),0) AS dueVocab,
          COALESCE(SUM(CASE WHEN c.queue = 'GRAMMAR' AND c.due <= :now AND c.state NOT IN ('NEW','GRADUATED') AND c.suspended = 0 AND n.status != 'IGNORED' THEN 1 ELSE 0 END),0) AS dueGrammar
        FROM cards c JOIN notes n ON c.noteId = n.id
    """)
    suspend fun dashboardCounts(now: Long): CardDashboardCounts

    @Query("SELECT c.* FROM cards c JOIN notes n ON c.noteId = n.id WHERE c.due <= :now AND c.state NOT IN ('NEW', 'GRADUATED') AND c.suspended = 0 AND n.status != 'IGNORED' ORDER BY c.due ASC, c.id ASC LIMIT :limit")
    suspend fun getDueCards(now: Long, limit: Int = 100): List<Card>

    @Query("SELECT c.* FROM cards c JOIN notes n ON c.noteId = n.id WHERE c.due <= :now AND c.queue = :queue AND c.state NOT IN ('NEW', 'GRADUATED') AND c.suspended = 0 AND n.status != 'IGNORED' ORDER BY c.due ASC, c.id ASC LIMIT :limit")
    suspend fun getDueCardsByQueue(now: Long, queue: Queue, limit: Int = 100): List<Card>

    @Query("SELECT c.* FROM cards c JOIN notes n ON c.noteId = n.id WHERE c.due <= :cutoff AND c.state NOT IN ('NEW', 'GRADUATED') AND c.suspended = 0 AND n.status != 'IGNORED' ORDER BY c.due ASC, c.id ASC LIMIT :limit")
    suspend fun getOverdueCards(cutoff: Long, limit: Int = 100): List<Card>

    @Query("SELECT c.* FROM cards c JOIN notes n ON c.noteId = n.id WHERE c.due <= :now AND c.state NOT IN ('NEW', 'GRADUATED') AND c.suspended = 0 AND n.status != 'IGNORED' ORDER BY c.due ASC, c.id ASC")
    suspend fun getAllDueCards(now: Long): List<Card>

    /** Distinct notes with a due-soon card (P5.2 dueOverlap reader scoring): the
     * reader deliberately favors texts that smuggle in words FSRS wants reviewed. */
    @Query("SELECT DISTINCT c.noteId FROM cards c JOIN notes n ON c.noteId = n.id WHERE c.due <= :cutoff AND c.state NOT IN ('NEW', 'GRADUATED') AND c.suspended = 0 AND n.status != 'IGNORED'")
    suspend fun getDueSoonNoteIds(cutoff: Long): List<Long>

    @Query("SELECT * FROM cards WHERE state = 'NEW' AND suspended = 0 ORDER BY due ASC, id ASC LIMIT :limit")
    suspend fun getNewCards(limit: Int): List<Card>

    /** Debug-only: any card of [cardType], most-practiced first, so a QA build can jump
     * straight to a card type instead of waiting for the adaptive session to surface one. */
    @Query("SELECT * FROM cards WHERE cardType = :cardType AND suspended = 0 ORDER BY reps DESC, id ASC LIMIT :limit")
    suspend fun getSampleCardsOfType(cardType: CardType, limit: Int = 5): List<Card>

    /**
     * New cards in curriculum order, CEFR-first: nothing above [maxCefrOrdinal] (the
     * position of a note's cefrLevel in LearningRepository's CEFR_LEVELS list, kept in
     * sync manually here since Room can't reference Kotlin constants in a `@Query`) is
     * even eligible, regardless of tier —
     * tier is provenance/pedagogy-type (hand-authored spine vs. reading matrix vs.
     * formal/political domain), not a CEFR gate. Within the same band, tier 0 (the
     * hand-authored spine) still leads by unit, then everything else interleaves by
     * frequency rank. This is what keeps formal/political-domain vocabulary (tier 2,
     * B2+ by construction) from surfacing before the learner's effective level reaches
     * it, instead of merely being deprioritized behind tier 0 as before.
     */
    @Query("""
        SELECT c.* FROM cards c
        JOIN notes n ON c.noteId = n.id
        WHERE c.state = 'NEW' AND c.suspended = 0
          AND n.status NOT IN ('KNOWN', 'IGNORED')
          AND n.translation != 'lookup pending'
          AND (c.queue != 'GRAMMAR' OR c.cardType = 'LESSON' OR n.encounterCount > 0)
          AND (
              CASE n.cefrLevel
                  WHEN 'A1' THEN 0 WHEN 'A2' THEN 1 WHEN 'B1' THEN 2
                  WHEN 'B2' THEN 3 WHEN 'C1' THEN 4 WHEN 'C2' THEN 5 ELSE 0
              END
          ) <= :maxCefrOrdinal
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
            (CASE n.cefrLevel
                WHEN 'A1' THEN 0 WHEN 'A2' THEN 1 WHEN 'B1' THEN 2
                WHEN 'B2' THEN 3 WHEN 'C1' THEN 4 WHEN 'C2' THEN 5 ELSE 0
            END) ASC,
            (CASE WHEN n.tier = 0 THEN 0 ELSE 1 END) ASC,
            COALESCE(n.unit, 2147483647) ASC,
            COALESCE(n.domainFreqRank, n.generalFreqRank, 2147483647) ASC,
            c.id ASC
        LIMIT :limit
    """)
    suspend fun getNewCardsOrdered(limit: Int, maxCefrOrdinal: Int): List<Card>

    @Query("""
        SELECT c.* FROM cards c
        JOIN notes n ON c.noteId = n.id
        WHERE c.state = 'NEW' AND c.suspended = 0
          AND n.status NOT IN ('KNOWN', 'IGNORED')
          AND n.translation != 'lookup pending'
          AND (
              :reviewedNotesOnly = 0
              OR c.cardType = 'LESSON'
              OR EXISTS (
                  SELECT 1
                  FROM review_logs reviewed
                  JOIN cards reviewed_card ON reviewed_card.id = reviewed.cardId
                  WHERE reviewed_card.noteId = c.noteId
              )
          )
          AND (c.queue != 'GRAMMAR' OR c.cardType = 'LESSON' OR n.encounterCount > 0)
          AND (
              CASE n.cefrLevel
                  WHEN 'A1' THEN 0 WHEN 'A2' THEN 1 WHEN 'B1' THEN 2
                  WHEN 'B2' THEN 3 WHEN 'C1' THEN 4 WHEN 'C2' THEN 5 ELSE 0
              END
          ) <= :maxCefrOrdinal
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
            (CASE n.cefrLevel
                WHEN 'A1' THEN 0 WHEN 'A2' THEN 1 WHEN 'B1' THEN 2
                WHEN 'B2' THEN 3 WHEN 'C1' THEN 4 WHEN 'C2' THEN 5 ELSE 0
            END) ASC,
            (CASE WHEN n.tier = 0 THEN 0 ELSE 1 END) ASC,
            COALESCE(n.unit, 2147483647) ASC,
            COALESCE(n.domainFreqRank, n.generalFreqRank, 2147483647) ASC,
            c.id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getNewCardsOrderedPage(
        limit: Int,
        offset: Int,
        maxCefrOrdinal: Int,
        reviewedNotesOnly: Boolean = false
    ): List<Card>

    /** Mark a single note's VOCAB cards known (graduated, pushed far out) — used when
     *  the learner marks the word KNOWN/IGNORED in the reader, so practice stops
     *  quizzing a word they already know. Writes a coherent FSRS state (long
     *  [stability], low [difficulty], reps>=3/consecutiveCorrect>=3, [now] as lastReview)
     *  rather than leaving the all-zero "never scheduled" state that corrupts the weight
     *  fit and any later review; callers pass the [FsrsScheduler.KNOWN_STABILITY_DAYS] /
     *  [FsrsScheduler.KNOWN_DIFFICULTY] constants so there is a single source of truth.
     *  reps/consecutiveCorrect must clear the same "recognition matured" bar used by
     *  [FsrsScheduler.markKnown] and isAdvancedFacetBeforeRecognitionMatures — otherwise
     *  a note graduated this way can never unlock its own production facets or grammar. */
    @Query(
        "UPDATE cards SET state = 'GRADUATED', due = :due, stability = :stability, " +
            "difficulty = :difficulty, scheduledDays = :scheduledDays, elapsedDays = 0, " +
            "reps = MAX(reps, 3), consecutiveCorrect = MAX(consecutiveCorrect, 3), lastReview = :now " +
            "WHERE noteId = :noteId AND queue = 'VOCAB' " +
            "AND (state != 'GRADUATED' OR stability <= 0.0 OR difficulty <= 0.0 OR due != :due)"
    )
    suspend fun graduateVocabForNote(
        noteId: Long,
        due: Long,
        now: Long,
        stability: Double,
        difficulty: Double,
        scheduledDays: Int
    ): Int

    /** Re-activate a note's graduated VOCAB cards as fresh NEW — used when the learner
     *  marks a previously-known word as LEARNING again, pulling it back into practice. */
    @Query("UPDATE cards SET state = 'NEW', due = 0, reps = 0, lapses = 0, stability = 0.0, difficulty = 0.0, elapsedDays = 0, scheduledDays = 0, consecutiveCorrect = 0, suspended = 0, lastReview = NULL WHERE noteId = :noteId AND queue = 'VOCAB' AND state = 'GRADUATED'")
    suspend fun reactivateVocabForNote(noteId: Long): Int

    /** One-time data repair: RU_TO_MEANING cards graduated "known" before
     *  graduateVocabForNote/markKnown required reps>=3/consecutiveCorrect>=3 left those
     *  fields too low to clear isAdvancedFacetBeforeRecognitionMatures, permanently
     *  blocking that note's production facets and grammar drills. */
    @Query("UPDATE cards SET reps = MAX(reps, 3), consecutiveCorrect = MAX(consecutiveCorrect, 3) WHERE cardType = 'RU_TO_MEANING' AND state = 'GRADUATED' AND (reps < 3 OR consecutiveCorrect < 3)")
    suspend fun repairGraduatedRecognitionMaturity(): Int

    /** All graduated recognition cards, oldest review first — the sampling frame
     * for the monthly checkpoint (P6.4): stratifying across this list samples
     * uniformly over graduation age without needing a dedicated timestamp field. */
    @Query("SELECT * FROM cards WHERE cardType = 'RU_TO_MEANING' AND state = 'GRADUATED' ORDER BY lastReview ASC")
    suspend fun getGraduatedRecognitionCards(): List<Card>

    /** Concept ids whose LESSON card has been seen (drills on them may now surface). */
    @Query("SELECT DISTINCT gramConcept FROM cards WHERE cardType = 'LESSON' AND gramConcept IS NOT NULL AND state != 'NEW'")
    suspend fun getIntroducedConceptIds(): List<String>

    /** Concept ids that have a LESSON card at all (so we know which drills to gate). */
    @Query("SELECT DISTINCT gramConcept FROM cards WHERE cardType = 'LESSON' AND gramConcept IS NOT NULL")
    suspend fun getConceptIdsWithLessons(): List<String>

    /** Concept ids whose CONCEPT_APPLY card has proven transfer (P4.3 taper gate):
     * once true, new per-note grammar drills for that concept stop being introduced
     * (existing card state is untouched — this only affects future selection). */
    @Query("SELECT DISTINCT gramConcept FROM cards WHERE cardType = 'CONCEPT_APPLY' AND gramConcept IS NOT NULL AND reps >= 4 AND consecutiveCorrect >= 3")
    suspend fun getTaperedConceptIds(): List<String>

    /**
     * One compact probation row per concept. SQLite reduces sibling grammar cards
     * before Room materializes results. A concept succeeds once any admitted drill
     * has ever recorded a non-miss review.
     *
     * Previous versions materialized every non-suspended grammar drill card plus
     * whether it had EVER recorded a
     * non-miss review (not necessarily its first attempt — a card that missed once
     * and later succeeded on a retry still counts). Backs the "concept stays on
     * probation until its one admitted drill succeeds" gate in
     * LearningRepository.conceptGate: a miss doesn't need special handling here,
     * since ReviewViewModel's existing repair/scaffold retry loop already reteaches
     * any missed card in place until it's eventually gotten right.
     */
    @Query("""
        SELECT concept,
               MIN(cardId) AS probationCardId,
               MAX(everSucceeded) AS everSucceeded
        FROM (
            SELECT c.id AS cardId,
                   CASE
                       WHEN c.gramConcept IS NOT NULL THEN c.gramConcept
                       WHEN c.cardType = 'CASE_FILL' THEN c.gramCase
                       WHEN c.cardType = 'GENDER_ID' THEN 'GENDER'
                       WHEN c.cardType = 'ADJ_AGREE' THEN 'ADJ_AGREE'
                       WHEN c.cardType = 'ASPECT_SELECT' THEN 'ASPECT'
                       WHEN c.cardType = 'VERB_FORM' AND c.gramContextCue LIKE 'PRES_%' THEN 'PRESENT'
                       WHEN c.cardType = 'VERB_FORM' THEN 'PAST'
                       ELSE NULL
                   END AS concept,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM review_logs rl
                       WHERE rl.cardId = c.id AND rl.rating != 'AGAIN'
                   ) THEN 1 ELSE 0 END AS everSucceeded
            FROM cards c
            WHERE c.queue = 'GRAMMAR'
              AND c.cardType != 'LESSON'
              AND c.suspended = 0
        )
        WHERE concept IS NOT NULL
        GROUP BY concept
    """)
    suspend fun getGrammarConceptOutcomes(): List<GrammarConceptOutcome>

    @Query("SELECT * FROM cards WHERE noteId = :noteId AND cardType = :cardType LIMIT 1")
    suspend fun getByNoteAndType(noteId: Long, cardType: CardType): Card?

    @Query("SELECT COUNT(*) FROM cards c JOIN notes n ON c.noteId = n.id WHERE c.due <= :now AND c.state NOT IN ('NEW', 'GRADUATED') AND c.suspended = 0 AND n.status != 'IGNORED'")
    suspend fun countDue(now: Long): Int

    /** Cards becoming due in the window (:start, :end], for the upcoming-load forecast. */
    @Query("SELECT COUNT(*) FROM cards c JOIN notes n ON c.noteId = n.id WHERE c.due > :start AND c.due <= :end AND c.state NOT IN ('NEW', 'GRADUATED') AND c.suspended = 0 AND n.status != 'IGNORED'")
    suspend fun countDueBetween(start: Long, end: Long): Int

    @Query("""
        SELECT CAST((c.due - :start - 1) / :dayMillis AS INTEGER) AS day, COUNT(*) AS count
        FROM cards c JOIN notes n ON c.noteId = n.id
        WHERE c.due > :start AND c.due <= :end AND c.state NOT IN ('NEW', 'GRADUATED') AND c.suspended = 0 AND n.status != 'IGNORED'
        GROUP BY day
    """)
    suspend fun countDueByDay(start: Long, end: Long, dayMillis: Long): List<DueDayCount>

    /** Auto-parked leeches: suspended cards that lapsed past the threshold. */
    @Query("SELECT * FROM cards WHERE suspended = 1 AND lapses >= :threshold ORDER BY lapses DESC, id ASC")
    suspend fun getLeechCards(threshold: Int): List<Card>

    @Query("SELECT * FROM cards WHERE reps >= :minReps AND (lapses > 0 OR difficulty >= 8.0) AND state != 'GRADUATED' AND suspended = 0 ORDER BY lapses DESC, difficulty DESC, reps DESC LIMIT :limit")
    suspend fun getProblemCards(minReps: Int = 2, limit: Int = 20): List<Card>

    @Query("SELECT COUNT(*) FROM cards c JOIN notes n ON c.noteId = n.id WHERE c.due <= :now AND c.queue = :queue AND c.state NOT IN ('NEW', 'GRADUATED') AND c.suspended = 0 AND n.status != 'IGNORED'")
    suspend fun countDueByQueue(now: Long, queue: Queue): Int

    @Query("SELECT COUNT(*) FROM cards WHERE queue = :queue")
    suspend fun countByQueue(queue: Queue): Int

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND gramCase = :gramCase AND gramGender = :gramGender AND gramNumber = :gramNumber")
    suspend fun getGrammarCardsForNounCategory(gramCase: String, gramGender: String, gramNumber: String): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND noteId IN (:noteIds)")
    suspend fun getGrammarCardsForNotes(noteIds: List<Long>): List<Card>

    @Query("SELECT * FROM cards WHERE queue = 'GRAMMAR' AND cardType = 'ASPECT_SELECT' AND state != 'GRADUATED' AND suspended = 0")
    suspend fun getAspectCards(): List<Card>

    @Query("UPDATE cards SET suspended = 1 WHERE cardType = 'ASPECT_SELECT' AND gramContextCue IN ('RESULT', 'SINGLE_EVENT') AND suspended = 0")
    suspend fun suspendDeprecatedAspectCueCards(): Int

    @Query("""
        UPDATE cards SET suspended = 1
        WHERE suspended = 0
          AND cardType IN ('VERB_FORM', 'TRANSFORM')
          AND noteId IN (
              SELECT id FROM notes
              WHERE lemma = 'есть' AND translation LIKE 'there is%'
          )
    """)
    suspend fun suspendExistentialHomographMorphologyCards(): Int

    @Query("""
        UPDATE cards SET suspended = 1
        WHERE cardType = 'CHUNK' AND suspended = 0
          AND noteId IN (
              SELECT id FROM notes
              WHERE partOfSpeech = 'chunk' AND TRIM(translation) = ''
          )
    """)
    suspend fun suspendUnglossedChunkCards(): Int

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

    @Query("SELECT * FROM cards WHERE gramConcept = :concept AND cardType != 'LESSON'")
    suspend fun getCardsForConcept(concept: String): List<Card>

    @Query("SELECT * FROM cards WHERE id IN (:cardIds)")
    suspend fun getByIds(cardIds: List<Long>): List<Card>

    @Query("SELECT * FROM cards")
    suspend fun getAll(): List<Card>

    /** Small working set used by pace/debt forecasts; excludes dormant new and
     * graduated cards so a large curriculum does not inflate every session build. */
    @Query("SELECT c.* FROM cards c JOIN notes n ON c.noteId = n.id WHERE c.state NOT IN ('NEW', 'GRADUATED') AND c.suspended = 0 AND n.status != 'IGNORED'")
    suspend fun getSchedulingCards(): List<Card>

    @Query("SELECT COUNT(*) FROM cards WHERE state = 'GRADUATED' OR reps >= 2")
    suspend fun countEstablishedCards(): Int

    @Query("SELECT * FROM cards WHERE queue = 'VOCAB'")
    suspend fun getAllVocabCards(): List<Card>

    /** CEFR spine gate computed in SQLite instead of materializing every vocab card. */
    @Query("""
        SELECT COALESCE(n.cefrLevel, 'A1') AS band,
               COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN recognition.mastered = 1 THEN 1 ELSE 0 END), 0) AS mastered
        FROM notes n
        LEFT JOIN (
            SELECT noteId,
                   MAX(CASE WHEN state = 'GRADUATED'
                                  OR (reps >= 2 AND consecutiveCorrect >= 2)
                            THEN 1 ELSE 0 END) AS mastered
            FROM cards
            WHERE cardType = 'RU_TO_MEANING' AND suspended = 0
            GROUP BY noteId
        ) recognition ON recognition.noteId = n.id
        WHERE n.tier = 0
          AND n.cefrLevel IS NOT NULL
          AND n.status != 'IGNORED'
        GROUP BY COALESCE(n.cefrLevel, 'A1')
    """)
    suspend fun cefrVocabProgress(): List<CefrVocabProgressRow>

    @Query("""
        SELECT COALESCE(n.cefrLevel, 'A1') AS band,
               n.unit AS unit,
               COUNT(*) AS total,
               SUM(CASE WHEN c.state = 'GRADUATED'
                              OR (c.reps >= 2 AND c.consecutiveCorrect >= 2)
                        THEN 1 ELSE 0 END) AS mastered,
               SUM(CASE WHEN c.state != 'NEW' THEN 1 ELSE 0 END) AS introduced
        FROM cards c
        JOIN notes n ON n.id = c.noteId
        WHERE n.tier = 0
          AND n.unit IS NOT NULL
          AND n.status != 'IGNORED'
          AND c.cardType = 'RU_TO_MEANING'
          AND c.suspended = 0
        GROUP BY COALESCE(n.cefrLevel, 'A1'), n.unit
    """)
    suspend fun unitVocabProgress(): List<UnitVocabProgressRow>

    @Query("""
        SELECT COALESCE(n.cefrLevel, 'A1') AS band,
               n.unit AS unit,
               COALESCE(c.gramConcept, c.cardType || ':' || c.noteId) AS objective,
               MAX(CASE WHEN c.reps >= 2 AND c.consecutiveCorrect >= 2
                        THEN 1 ELSE 0 END) AS mastered
        FROM cards c
        JOIN notes n ON n.id = c.noteId
        WHERE n.tier = 0
          AND n.unit IS NOT NULL
          AND n.status NOT IN ('KNOWN', 'IGNORED')
          AND c.queue = 'GRAMMAR'
          AND c.cardType != 'LESSON'
          AND c.suspended = 0
        GROUP BY COALESCE(n.cefrLevel, 'A1'), n.unit,
                 COALESCE(c.gramConcept, c.cardType || ':' || c.noteId)
    """)
    suspend fun unitGrammarObjectiveProgress(): List<UnitGrammarObjectiveProgressRow>

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

    @Query("SELECT * FROM notes WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Note>

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

    /** Lightweight inventory for dynamic grammar frames; excludes large note JSON fields. */
    @Query("""
        SELECT lemma, translation, gender, aspect, partOfSpeech
        FROM notes
        WHERE tier = 0
          AND partOfSpeech IN ('noun', 'verb', 'adjective')
    """)
    suspend fun getFrameLexemes(): List<FrameLexeme>

    @Query("""
        SELECT COUNT(*) AS totalNotes,
               COALESCE(SUM(CASE
                   WHEN LOWER(partOfSpeech) IN ('noun', 'adjective')
                    AND declensionJson IS NOT NULL AND TRIM(declensionJson) != ''
                    AND gender IS NOT NULL AND TRIM(gender) != ''
                    AND domainFreqRank IS NOT NULL
                    AND exampleSentence IS NOT NULL AND TRIM(exampleSentence) != ''
                    AND exampleTranslation IS NOT NULL AND TRIM(exampleTranslation) != ''
                    AND LOWER(TRIM(exampleTranslation)) != LOWER(TRIM(translation))
                    AND INSTR(TRIM(exampleTranslation), ' ') > 0
                   THEN 1 ELSE 0 END), 0) AS readyNominalRows,
               COALESCE(SUM(CASE
                   WHEN LOWER(partOfSpeech) = 'verb'
                    AND aspectPartner IS NOT NULL
                    AND aspect IS NOT NULL AND TRIM(aspect) != ''
                    AND aktionsart IS NOT NULL AND TRIM(aktionsart) != ''
                    AND domainFreqRank IS NOT NULL
                    AND exampleSentence IS NOT NULL AND TRIM(exampleSentence) != ''
                    AND exampleTranslation IS NOT NULL AND TRIM(exampleTranslation) != ''
                    AND LOWER(TRIM(exampleTranslation)) != LOWER(TRIM(translation))
                    AND INSTR(TRIM(exampleTranslation), ' ') > 0
                   THEN 1 ELSE 0 END), 0) AS aspectReadyVerbRows,
               COALESCE(SUM(CASE
                   WHEN LOWER(partOfSpeech) = 'verb'
                    AND aspectPartner IS NOT NULL
                    AND aspect IS NOT NULL AND TRIM(aspect) != ''
                    AND aktionsart IS NOT NULL AND TRIM(aktionsart) != ''
                    AND domainFreqRank IS NOT NULL
                    AND exampleSentence IS NOT NULL AND TRIM(exampleSentence) != ''
                    AND exampleTranslation IS NOT NULL AND TRIM(exampleTranslation) != ''
                    AND LOWER(TRIM(exampleTranslation)) != LOWER(TRIM(translation))
                    AND INSTR(TRIM(exampleTranslation), ' ') > 0
                    AND LOWER(aktionsartConfidence) IN ('high', 'manual', 'verified')
                   THEN 1 ELSE 0 END), 0) AS verifiedAktionsartVerbRows,
               COALESCE(SUM(CASE WHEN domainFreqRank IS NOT NULL THEN 1 ELSE 0 END), 0) AS domainRankedRows,
               COALESCE(SUM(CASE
                   WHEN exampleSentence IS NOT NULL AND TRIM(exampleSentence) != ''
                    AND exampleTranslation IS NOT NULL AND TRIM(exampleTranslation) != ''
                    AND LOWER(TRIM(exampleTranslation)) != LOWER(TRIM(translation))
                    AND INSTR(TRIM(exampleTranslation), ' ') > 0
                   THEN 1 ELSE 0 END), 0) AS exampleRows
        FROM notes
    """)
    suspend fun qualityCounts(): NoteQualityCounts

    /** Notes whose primary example looks like a "Русский - English" concatenation that
     *  was never split (translation blank, a spaced dash present, Latin letters in the
     *  sentence). Feeds the one-time example-repair pass; returns nothing once repaired. */
    @Query("""
        SELECT * FROM notes
        WHERE (exampleTranslation IS NULL OR exampleTranslation = '')
          AND exampleSentence IS NOT NULL
          AND (exampleSentence LIKE '% - %' OR exampleSentence LIKE '% — %' OR exampleSentence LIKE '% – %')
          AND exampleSentence GLOB '*[A-Za-z]*'
    """)
    suspend fun examplesNeedingSplit(): List<Note>

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
    suspend fun insert(log: ReviewLog): Long

    @Insert
    suspend fun insertAll(logs: List<ReviewLog>)

    @Query("SELECT * FROM review_logs ORDER BY reviewDatetime ASC, id ASC")
    suspend fun getAll(): List<ReviewLog>

    @Query("SELECT reviewDatetime FROM review_logs WHERE source IN ('SRS_REVIEW','GRAMMAR_DRILL') ORDER BY reviewDatetime DESC LIMIT :limit")
    suspend fun recentReviewTimes(limit: Int = 1000): List<Long>

    @Query("SELECT rating FROM review_logs WHERE source IN ('SRS_REVIEW','GRAMMAR_DRILL') ORDER BY reviewDatetime DESC, id DESC LIMIT :limit")
    suspend fun recentDirectRatings(limit: Int = 200): List<Rating>

    @Query("SELECT rating FROM review_logs WHERE reviewDatetime >= :since AND source IN ('SRS_REVIEW','GRAMMAR_DRILL') ORDER BY reviewDatetime DESC, id DESC LIMIT :limit")
    suspend fun recentDirectRatingsSince(since: Long, limit: Int = 200): List<Rating>

    @Query("SELECT COUNT(*) FROM review_logs WHERE cardId = :cardId AND reviewDatetime >= :dayStart AND source IN ('READING','LISTENING','PRODUCTION','CAPSTONE_CHOICE')")
    suspend fun passiveEvidenceCountSince(cardId: Long, dayStart: Long): Int

    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewDatetime >= :since AND source IN ('SRS_REVIEW','GRAMMAR_DRILL')")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM review_logs WHERE source IN ('SRS_REVIEW','GRAMMAR_DRILL')")
    suspend fun countAll(): Int

    /** Recall quality and card maturity both contribute; reader lookups earn no XP. */
    @Query("""
        SELECT COALESCE(SUM(
            CASE rating WHEN 'AGAIN' THEN 2 WHEN 'HARD' THEN 8 WHEN 'GOOD' THEN 10 WHEN 'EASY' THEN 14 END
            + CASE WHEN stateBefore IN ('REVIEW', 'RELEARNING') AND elapsedDays > 0 THEN 2 ELSE 0 END
        ), 0)
        FROM review_logs WHERE source IN ('SRS_REVIEW','GRAMMAR_DRILL')
    """)
    suspend fun weightedXp(): Int

    /**
     * Reviews of mature cards (already in the REVIEW/RELEARNING phase) within a
     * rolling window. The window keeps the retention instrument *responsive*: a
     * lifetime count becomes a frozen slab average after a few thousand reviews,
     * which silently calcifies every retention-driven adaptation. Pass [since] = 0
     * for the all-time figure.
     */
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewDatetime >= :since AND stateBefore IN ('REVIEW', 'RELEARNING') AND elapsedDays > 0 AND source IN ('SRS_REVIEW','GRAMMAR_DRILL')")
    suspend fun matureReviewCount(since: Long = 0): Int

    /** Mature-card reviews the learner got right (did not lapse), within the same
     * rolling window as [matureReviewCount]. True-retention numerator. */
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewDatetime >= :since AND stateBefore IN ('REVIEW', 'RELEARNING') AND elapsedDays > 0 AND rating != 'AGAIN' AND source IN ('SRS_REVIEW','GRAMMAR_DRILL')")
    suspend fun matureRetainedCount(since: Long = 0): Int

    /** Mature-review retention grouped by card type, over the same rolling window as
     * [matureReviewCount]. Diagnoses which quiz facets drag the aggregate down. */
    @Query("""
        SELECT cards.cardType AS cardType,
               COUNT(*) AS total,
               SUM(CASE WHEN review_logs.rating != 'AGAIN' THEN 1 ELSE 0 END) AS retained
        FROM review_logs
        INNER JOIN cards ON cards.id = review_logs.cardId
        WHERE review_logs.reviewDatetime >= :since
          AND review_logs.stateBefore IN ('REVIEW', 'RELEARNING')
          AND review_logs.elapsedDays > 0
          AND review_logs.source IN ('SRS_REVIEW','GRAMMAR_DRILL')
        GROUP BY cards.cardType
        HAVING total > 0
    """)
    suspend fun matureRetentionByCardType(since: Long = 0): List<CardTypeRetention>

    // Lexemes introduced since [since]. Multiple facets of one note consume one
    // daily slot, so breadth is not divided by the number of card types.
    @Query("""
        SELECT COUNT(DISTINCT cards.noteId)
        FROM review_logs
        INNER JOIN cards ON cards.id = review_logs.cardId
        WHERE reviewDatetime >= :since
          AND stateBefore = 'NEW'
          AND source IN ('SRS_REVIEW','GRAMMAR_DRILL')
          AND cards.cardType != 'LESSON'
    """)
    suspend fun countNewIntroducedSince(since: Long): Int

    /** Notes with real card history, excluding passive lessons and reader lookups. */
    @Query("""
        SELECT DISTINCT cards.noteId
        FROM review_logs
        INNER JOIN cards ON cards.id = review_logs.cardId
        WHERE review_logs.source IN ('SRS_REVIEW','GRAMMAR_DRILL')
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
          AND review_logs.source IN ('SRS_REVIEW','GRAMMAR_DRILL')
    """)
    suspend fun getReviewedCardsSince(since: Long): List<Card>

    // Undo deletes the exact row returned by @Insert. "Latest for card" is unsafe:
    // passive reading/listening evidence may be recorded after the direct review.
    @Query("DELETE FROM review_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Distinct local-day buckets that have at least one review, newest first.
    // Used for streak and active-day stats without loading every log row.
    @Query("SELECT DISTINCT (reviewDatetime + :tzOffset) / :dayMillis AS day FROM review_logs WHERE source IN ('SRS_REVIEW','GRAMMAR_DRILL') ORDER BY day DESC")
    suspend fun reviewDayBuckets(tzOffset: Long, dayMillis: Long): List<Long>

    /** Raw activity instants are bucketed in Kotlin with ZoneRules. A single SQL
     * offset cannot represent historical DST transitions (or a timezone move). */
    @Query("SELECT reviewDatetime FROM review_logs WHERE source IN ('SRS_REVIEW','GRAMMAR_DRILL') ORDER BY reviewDatetime ASC")
    suspend fun recallActivityTimestamps(): List<Long>

    // Per-day review counts (not just presence/absence) since :sinceDay, for the
    // GitHub/Anki-style activity heatmap — StreakCard needs intensity, not just a
    // boolean, to color a cell.
    @Query("""
        SELECT (reviewDatetime + :tzOffset) / :dayMillis AS day, COUNT(*) AS count
        FROM review_logs
        WHERE source IN ('SRS_REVIEW','GRAMMAR_DRILL') AND (reviewDatetime + :tzOffset) / :dayMillis >= :sinceDay
        GROUP BY day
    """)
    suspend fun reviewCountsByDay(tzOffset: Long, dayMillis: Long, sinceDay: Long): List<ActivityDayCount>

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
        WHERE rl.source IN ('SRS_REVIEW','GRAMMAR_DRILL')
          AND c.cardType IN ('CASE_FILL', 'ASPECT_SELECT', 'VERB_FORM')
        ORDER BY rl.reviewDatetime DESC, rl.id DESC
        LIMIT :limit
    """)
    suspend fun recentCategoryRatings(limit: Int = 100000): List<ReviewCategoryRatingRow>

    @Query("""
        SELECT rl.rating FROM review_logs rl
        JOIN cards c ON rl.cardId = c.id
        WHERE c.gramCase = :gramCase AND c.gramGender = :gramGender AND c.gramNumber = :gramNumber
          AND rl.source IN ('SRS_REVIEW','GRAMMAR_DRILL')
        ORDER BY rl.reviewDatetime DESC
        LIMIT :limit
    """)
    suspend fun nounCategoryRatings(gramCase: String, gramGender: String, gramNumber: String, limit: Int = 30): List<Rating>

    @Query("""
        SELECT rl.rating FROM review_logs rl
        JOIN cards c ON rl.cardId = c.id
        JOIN notes n ON c.noteId = n.id
        WHERE n.aktionsart = :aktionsart AND n.aspect = :aspect AND c.gramContextCue = :contextCue
          AND rl.source IN ('SRS_REVIEW','GRAMMAR_DRILL')
        ORDER BY rl.reviewDatetime DESC
        LIMIT :limit
    """)
    suspend fun aspectCategoryRatings(aktionsart: String, aspect: String, contextCue: String, limit: Int = 30): List<Rating>

    @Query("""
        SELECT rl.rating FROM review_logs rl
        JOIN cards c ON rl.cardId = c.id
        WHERE c.cardType = 'VERB_FORM' AND c.gramContextCue = :formKey
          AND rl.source IN ('SRS_REVIEW','GRAMMAR_DRILL')
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
        WHERE source IN ('SRS_REVIEW','GRAMMAR_DRILL') AND reviewDatetime >= :since
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

    @Query("SELECT COUNT(*) FROM reader_texts WHERE source = :source")
    suspend fun countBySource(source: String): Int

    @Query("SELECT * FROM reader_texts ORDER BY createdAt ASC")
    suspend fun getAll(): List<ReaderText>

    @Query("SELECT * FROM reader_texts WHERE id = :id")
    suspend fun getById(id: Long): ReaderText?

    @Query("DELETE FROM reader_texts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE reader_texts SET source = :source WHERE id = :id")
    suspend fun updateSource(id: Long, source: String): Int
}

@Dao
interface ReaderBookmarkDao {
    @Insert
    suspend fun insert(bookmark: ReaderBookmark): Long

    @Query("SELECT * FROM reader_bookmarks WHERE readerTextId = :readerTextId ORDER BY tokenIndex")
    suspend fun getForText(readerTextId: Long): List<ReaderBookmark>

    @Query("SELECT * FROM reader_bookmarks WHERE readerTextId = :readerTextId AND tokenIndex = :tokenIndex LIMIT 1")
    suspend fun getAt(readerTextId: Long, tokenIndex: Int): ReaderBookmark?

    @Query("DELETE FROM reader_bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM reader_bookmarks WHERE readerTextId = :readerTextId")
    suspend fun deleteForText(readerTextId: Long): Int
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

    /** Remove only never-started schedules that are not currently readable. A
     * completed reading keeps its durable recurrence even if coverage later moves. */
    @Query("DELETE FROM reading_schedules WHERE readerTextId IN (:readerTextIds) AND reps = 0 AND lastCompleted IS NULL")
    suspend fun deletePristineForTexts(readerTextIds: List<Long>): Int
}

@Dao
interface ReaderEncounterDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(encounter: ReaderEncounter): Long

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
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

    @Query("SELECT completedAt FROM reading_activities ORDER BY completedAt ASC")
    suspend fun activityTimestamps(): List<Long>

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

    @Query("SELECT * FROM telemetry_events WHERE eventType IN (:eventTypes) ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentByTypes(eventTypes: List<String>, limit: Int): List<TelemetryEvent>

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

@Dao
interface MinedExampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(example: MinedExample): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(examples: List<MinedExample>): List<Long>

    @Query("SELECT * FROM mined_examples WHERE noteId = :noteId")
    suspend fun forNote(noteId: Long): MinedExample?

    @Query("SELECT * FROM mined_examples WHERE noteId IN (:noteIds)")
    suspend fun forNotes(noteIds: List<Long>): List<MinedExample>

    @Query("SELECT * FROM mined_examples ORDER BY score DESC")
    suspend fun getAll(): List<MinedExample>

    @Query("DELETE FROM mined_examples WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: Long): Int
}

@Dao
interface LearningModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDifficulty(value: ItemDifficulty)

    @Query("SELECT * FROM item_difficulty WHERE cardId = :cardId")
    suspend fun difficulty(cardId: Long): ItemDifficulty?

    @Query("SELECT * FROM item_difficulty")
    suspend fun difficulties(): List<ItemDifficulty>

    @Query("SELECT * FROM item_difficulty WHERE cardId IN (:cardIds)")
    suspend fun difficultiesFor(cardIds: List<Long>): List<ItemDifficulty>

    @Query("DELETE FROM item_difficulty WHERE cardId = :cardId")
    suspend fun deleteDifficulty(cardId: Long): Int

    @Query("DELETE FROM item_difficulty WHERE cardId IN (:cardIds)")
    suspend fun deleteDifficulties(cardIds: List<Long>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMastery(value: ConceptMastery)

    @Query("SELECT * FROM concept_mastery WHERE concept = :concept")
    suspend fun mastery(concept: String): ConceptMastery?

    @Query("SELECT * FROM concept_mastery") suspend fun masteries(): List<ConceptMastery>

    @Query("DELETE FROM concept_mastery WHERE concept IN (:concepts)")
    suspend fun deleteMasteries(concepts: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParameter(value: OptimizerParameter)

    @androidx.room.Transaction
    suspend fun upsertParameters(values: List<OptimizerParameter>) {
        values.forEach { upsertParameter(it) }
    }

    @androidx.room.Transaction
    suspend fun replaceParameters(deleteKeys: List<String>, values: List<OptimizerParameter>) {
        if (deleteKeys.isNotEmpty()) deleteParameters(deleteKeys)
        values.forEach { upsertParameter(it) }
    }

    @Query("SELECT * FROM optimizer_parameters")
    suspend fun parameters(): List<OptimizerParameter>

    @Query("DELETE FROM optimizer_parameters WHERE `key` IN (:keys)")
    suspend fun deleteParameters(keys: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkillRating(value: SkillRating)

    @Query("SELECT * FROM skill_rating")
    suspend fun skillRatings(): List<SkillRating>

    @Query("SELECT * FROM skill_rating WHERE skill = :skill")
    suspend fun skillRating(skill: String): SkillRating?

    @Query("DELETE FROM skill_rating WHERE skill IN (:skills)")
    suspend fun deleteSkillRatings(skills: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCapacityState(value: CapacityState)

    @Query("SELECT * FROM capacity_state WHERE id = 0")
    suspend fun capacityState(): CapacityState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWillingnessState(value: WillingnessState)

    @Query("SELECT * FROM willingness_state WHERE id = 0")
    suspend fun willingnessState(): WillingnessState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRivalState(value: RivalState)

    @Query("SELECT * FROM rival_state WHERE id = 0")
    suspend fun rivalState(): RivalState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGhostSnapshot(value: GhostSnapshot)

    @Query("SELECT * FROM ghost_snapshot ORDER BY takenAt DESC LIMIT 1")
    suspend fun latestGhostSnapshot(): GhostSnapshot?

    @Query("SELECT * FROM ghost_snapshot WHERE takenAt <= :cutoff ORDER BY takenAt DESC LIMIT 1")
    suspend fun ghostSnapshotAtOrBefore(cutoff: Long): GhostSnapshot?
    @Query("SELECT * FROM ghost_snapshot") suspend fun ghostSnapshots(): List<GhostSnapshot>

    @Insert
    suspend fun insertMatchHistory(value: MatchHistory): Long

    @Query("SELECT * FROM match_history ORDER BY at DESC LIMIT :limit")
    suspend fun matchHistory(limit: Int = 20): List<MatchHistory>
    @Query("SELECT * FROM match_history ORDER BY at") suspend fun allMatchHistory(): List<MatchHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPaceLog(value: PaceLog)

    @Query("SELECT * FROM pace_log ORDER BY at DESC LIMIT :limit")
    suspend fun paceLogs(limit: Int = 20): List<PaceLog>
    @Query("SELECT * FROM pace_log ORDER BY at") suspend fun allPaceLogs(): List<PaceLog>
    @Query("SELECT COUNT(*) FROM pace_log") suspend fun paceLogCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBanditPending(value: BanditPending)

    @Query("SELECT * FROM bandit_pending WHERE itemId = :itemId ORDER BY showAt ASC")
    suspend fun pendingBanditCredits(itemId: Long): List<BanditPending>
    @Query("SELECT * FROM bandit_pending") suspend fun allBanditPending(): List<BanditPending>

    @Query("DELETE FROM bandit_pending WHERE showAt = :showAt")
    suspend fun deleteBanditPending(showAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBanditArmState(value: BanditArmState)

    @Query("SELECT * FROM bandit_arm_state")
    suspend fun banditArmStates(): List<BanditArmState>
}

@Dao interface WeeklyReportDao {
    @Insert suspend fun insert(report: WeeklyReport): Long
    @Insert suspend fun insertAll(reports: List<WeeklyReport>): List<Long>
    @Query("SELECT * FROM weekly_reports ORDER BY generatedAt DESC LIMIT :limit") suspend fun recent(limit: Int=12): List<WeeklyReport>
    @Query("SELECT * FROM weekly_reports ORDER BY generatedAt") suspend fun all(): List<WeeklyReport>
}

@Dao interface ConfusionEventDao {
    @Insert suspend fun insert(event: ConfusionEvent): Long
    @Insert suspend fun insertAll(events: List<ConfusionEvent>): List<Long>
    @Query("SELECT * FROM confusion_events ORDER BY at") suspend fun all(): List<ConfusionEvent>
    @Query("""
        SELECT expectedKey, producedKey, category, cardType, COUNT(*) as count FROM confusion_events
        WHERE at >= :since GROUP BY expectedKey, producedKey, category, cardType
        ORDER BY count DESC, expectedKey LIMIT 1
    """)
    suspend fun topPairSince(since: Long): ConfusionPairCount?
    @Query("DELETE FROM confusion_events WHERE at < :cutoff") suspend fun deleteOlderThan(cutoff: Long): Int
}

@Dao interface CheckpointResultDao {
    @Insert suspend fun insert(result: CheckpointResult): Long
    @Insert suspend fun insertAll(results: List<CheckpointResult>): List<Long>
    @Query("SELECT * FROM checkpoint_results WHERE at >= :since ORDER BY at DESC") suspend fun since(since: Long): List<CheckpointResult>
    @Query("SELECT * FROM checkpoint_results ORDER BY at DESC LIMIT :limit") suspend fun recent(limit: Int = 200): List<CheckpointResult>
    @Query("SELECT * FROM checkpoint_results ORDER BY at") suspend fun all(): List<CheckpointResult>
}

@Dao interface CurriculumStateDao {
    @Query("SELECT * FROM curriculum_state WHERE id = 0") suspend fun current(): CurriculumState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: CurriculumState)
    @Insert suspend fun insertReport(value: CurriculumMigrationReport): Long
    @Query("SELECT * FROM curriculum_migration_reports WHERE shown = 0 ORDER BY createdAt DESC LIMIT 1") suspend fun pendingReport(): CurriculumMigrationReport?
    @Query("UPDATE curriculum_migration_reports SET shown = 1 WHERE id = :id") suspend fun markShown(id: Long)
    @Insert suspend fun insertExitTicket(value: ExitTicketResult): Long
    @Query("SELECT * FROM exit_ticket_results ORDER BY completedAt DESC") suspend fun exitTickets(): List<ExitTicketResult>
}
