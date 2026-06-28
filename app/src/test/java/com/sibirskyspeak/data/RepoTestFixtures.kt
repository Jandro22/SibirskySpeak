package com.sibirskyspeak.data

import com.sibirskyspeak.scheduler.FsrsScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared in-memory LearningRepository test double, used by LearningRepositoryTest
 * and ReviewViewModelTest. Kept as plain in-memory lists (no Room) so both test
 * classes can exercise the repository/ViewModel without an Android runtime.
 */
internal class RepoFixture(
    bootstrapNotes: String? = null,
    bootstrapReaderTexts: String? = null,
    restoreBackup: (suspend () -> String?)? = null,
    writeBackup: (suspend (String) -> Unit)? = null,
    config: () -> LearningConfig = { LearningConfig() },
    withTelemetry: Boolean = false
) {
    val notes = FakeNoteDao()
    val readerEncounters = FakeReaderEncounterDao()
    val cards = FakeCardDao(notes, readerEncounters)
    val logs = FakeReviewLogDao(cards, notes)
    val pairs = FakeConfusablePairDao()
    val readers = FakeReaderTextDao()
    val readingSchedules = FakeReadingScheduleDao()
    val readingActivities = FakeReadingActivityDao()
    val telemetry = if (withTelemetry) FakeTelemetryDao() else null
    val repository = LearningRepository(
        notes,
        cards,
        logs,
        pairs,
        readers,
        FsrsScheduler(),
        bootstrapNotes = { bootstrapNotes },
        bootstrapReaderTexts = { bootstrapReaderTexts },
        config = config,
        restoreBackup = restoreBackup,
        writeBackup = writeBackup,
        telemetryDao = telemetry,
        readingScheduleDao = readingSchedules,
        readerEncounterDao = readerEncounters,
        readingActivityDao = readingActivities
    )
}

internal class FakeNoteDao : NoteDao {
    val notes = mutableListOf<Note>()
    private var nextId = 1L

    override suspend fun insert(note: Note): Long {
        val id = nextId++
        notes += note.copy(id = id)
        return id
    }

    override suspend fun insertAll(notes: List<Note>): List<Long> = notes.map { insert(it) }

    override suspend fun getById(id: Long): Note? = notes.firstOrNull { it.id == id }
    override suspend fun getByLemma(lemma: String): Note? = notes.firstOrNull { it.lemma == lemma }
    override suspend fun getByLemmas(lemmas: List<String>): List<Note> = notes.filter { it.lemma in lemmas }
    override suspend fun count(): Int = notes.size
    override fun observeAll(): Flow<List<Note>> = flowOf(notes)
    override suspend fun getAll(): List<Note> = notes.toList()
    override suspend fun getByCefrLevels(levels: List<String>): List<Note> = notes.filter { it.cefrLevel in levels }
    override suspend fun search(query: String, limit: Int): List<Note> =
        notes.filter {
            it.russian.contains(query, true) || it.lemma.contains(query, true) || it.translation.contains(query, true)
        }.take(limit)
    override suspend fun update(note: Note) {
        notes.replaceAll { if (it.id == note.id) note else it }
    }
    override suspend fun updateAll(notes: List<Note>) {
        notes.forEach { update(it) }
    }
    override suspend fun deleteById(id: Long) { notes.removeAll { it.id == id } }
    override suspend fun moveAspectPartnerReferences(sourceId: Long, targetId: Long): Int {
        var changed = 0
        notes.replaceAll {
            if (it.aspectPartner == sourceId) {
                changed += 1
                it.copy(aspectPartner = targetId)
            } else it
        }
        return changed
    }
    override suspend fun clearSelfAspectPartners(): Int {
        var changed = 0
        notes.replaceAll {
            if (it.aspectPartner == it.id) {
                changed += 1
                it.copy(aspectPartner = null)
            } else it
        }
        return changed
    }
}

internal class FakeCardDao(
    private val notes: FakeNoteDao,
    private val readerEncounters: FakeReaderEncounterDao
) : CardDao {
    val cards = mutableListOf<Card>()
    private var nextId = 1L

    override suspend fun getDueCards(now: Long, limit: Int): List<Card> =
        cards.filter { it.due <= now && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
    override suspend fun getDueCardsByQueue(now: Long, queue: Queue, limit: Int): List<Card> =
        cards.filter { it.due <= now && it.queue == queue && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
    override suspend fun countDueBetween(start: Long, end: Long): Int =
        cards.count { it.due > start && it.due <= end && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended }
    override suspend fun countDueByDay(start: Long, end: Long, dayMillis: Long): List<DueDayCount> =
        cards.asSequence()
            .filter { it.due > start && it.due <= end && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended }
            .groupingBy { ((it.due - start - 1) / dayMillis).toInt() }
            .eachCount()
            .map { (day, count) -> DueDayCount(day, count) }
    override suspend fun getLeechCards(threshold: Int): List<Card> =
        cards.filter { it.suspended && it.lapses >= threshold }.sortedWith(compareByDescending<Card> { it.lapses }.thenBy { it.id })
    override suspend fun getProblemCards(minReps: Int, limit: Int): List<Card> =
        cards.filter { it.reps >= minReps && (it.lapses > 0 || it.difficulty >= 8.0) && it.state != CardState.GRADUATED && !it.suspended }
            .sortedWith(compareByDescending<Card> { it.lapses }.thenByDescending { it.difficulty }).take(limit)
    override suspend fun getOverdueCards(cutoff: Long, limit: Int): List<Card> =
        cards.filter { it.due <= cutoff && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
    override suspend fun getAllDueCards(now: Long): List<Card> =
        cards.filter { it.due <= now && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id })
    override suspend fun getNewCards(limit: Int): List<Card> =
        cards.filter { it.state == CardState.NEW && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
    override suspend fun getNewCardsOrdered(limit: Int): List<Card> {
        fun rank(card: Card): IntArray {
            val n = notes.notes.firstOrNull { it.id == card.noteId }
            val tierPhase = if (n?.tier == 0) 0 else 1
            val unit = n?.unit ?: Int.MAX_VALUE
            val freq = n?.domainFreqRank ?: n?.generalFreqRank ?: Int.MAX_VALUE
            return intArrayOf(tierPhase, unit, freq)
        }
        fun eligible(card: Card): Boolean {
            val n = notes.notes.firstOrNull { it.id == card.noteId }
            val advanced = card.cardType in setOf(
                CardType.MEANING_TO_RU, CardType.CLOZE, CardType.SPEAK, CardType.AUDIO_TO_RU,
                CardType.DICTATION, CardType.SENTENCE_BUILD, CardType.STRESS_MARK
            )
            val matureRecognition = cards.any {
                it.noteId == card.noteId && it.cardType == CardType.RU_TO_MEANING &&
                    it.reps >= 3 && it.consecutiveCorrect >= 2 && it.state in setOf(CardState.REVIEW, CardState.GRADUATED)
            }
            return card.state == CardState.NEW && !card.suspended &&
                n?.status != WordStatus.KNOWN && n?.status != WordStatus.IGNORED &&
                n?.translation != "lookup pending" &&
                (card.queue != Queue.GRAMMAR || card.cardType == CardType.LESSON || (n?.encounterCount ?: 0) > 0) &&
                (!advanced || matureRecognition)
        }
        return cards.filter { eligible(it) }
            .sortedWith(
                compareBy<Card>({ rank(it)[0] }, { rank(it)[1] }, { rank(it)[2] }, { it.id })
            )
            .take(limit)
    }
    override suspend fun getNewCardsOrderedPage(limit: Int, offset: Int): List<Card> =
        getNewCardsOrdered(Int.MAX_VALUE).drop(offset).take(limit)
    override suspend fun graduateVocabForNote(noteId: Long, due: Long): Int {
        var changed = 0
        cards.replaceAll { card ->
            if (card.noteId == noteId && card.queue == Queue.VOCAB) {
                changed += 1
                card.copy(state = CardState.GRADUATED, due = due)
            } else card
        }
        return changed
    }
    override suspend fun reactivateVocabForNote(noteId: Long): Int {
        var changed = 0
        cards.replaceAll { card ->
            if (card.noteId == noteId && card.queue == Queue.VOCAB && card.state == CardState.GRADUATED) {
                changed += 1
                card.copy(state = CardState.NEW, due = 0, reps = 0, lapses = 0, stability = 0.0, difficulty = 0.0, elapsedDays = 0, scheduledDays = 0, consecutiveCorrect = 0, suspended = false, lastReview = null)
            } else card
        }
        return changed
    }
    override suspend fun getIntroducedConceptIds(): List<String> =
        cards.filter { it.cardType == CardType.LESSON && it.gramConcept != null && it.state != CardState.NEW }
            .mapNotNull { it.gramConcept }
            .distinct()
    override suspend fun getConceptIdsWithLessons(): List<String> =
        cards.filter { it.cardType == CardType.LESSON && it.gramConcept != null }
            .mapNotNull { it.gramConcept }
            .distinct()
    override suspend fun getByNoteAndType(noteId: Long, cardType: CardType): Card? = cards.firstOrNull { it.noteId == noteId && it.cardType == cardType }
    override suspend fun countDue(now: Long): Int = cards.count { it.due <= now && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended }
    override suspend fun countDueByQueue(now: Long, queue: Queue): Int = cards.count { it.due <= now && it.queue == queue && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended }
    override suspend fun countByQueue(queue: Queue): Int = cards.count { it.queue == queue }
    override suspend fun getGrammarCardsForNounCategory(gramCase: String, gramGender: String, gramNumber: String): List<Card> =
        cards.filter { it.queue == Queue.GRAMMAR && it.gramCase == gramCase && it.gramGender == gramGender && it.gramNumber == gramNumber }
    override suspend fun getGrammarCardsForNotes(noteIds: List<Long>): List<Card> = cards.filter { it.queue == Queue.GRAMMAR && it.noteId in noteIds }
    override suspend fun getAspectCards(): List<Card> = cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.ASPECT_SELECT && it.state != CardState.GRADUATED && !it.suspended }
    override suspend fun getAllGrammarCards(): List<Card> = cards.filter { it.queue == Queue.GRAMMAR }
    override suspend fun getCaseCategoryKeys(): List<CaseCategoryRow> =
        cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.CASE_FILL && it.gramCase != null && it.gramGender != null && it.gramNumber != null }
            .map { CaseCategoryRow(it.gramCase.orEmpty(), it.gramGender.orEmpty(), it.gramNumber.orEmpty()) }
            .distinct()
    override suspend fun getAspectCategoryKeys(): List<AspectCategoryRow> =
        cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.ASPECT_SELECT && it.gramContextCue != null }
            .mapNotNull { card ->
                val note = notes.notes.firstOrNull { it.id == card.noteId } ?: return@mapNotNull null
                val aktionsart = note.aktionsart ?: return@mapNotNull null
                val aspect = note.aspect ?: return@mapNotNull null
                AspectCategoryRow(aktionsart, aspect, card.gramContextCue.orEmpty())
            }
            .distinct()
    override suspend fun getVerbFormCategoryKeys(): List<String> =
        cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.VERB_FORM && it.gramContextCue != null }
            .map { it.gramContextCue.orEmpty() }
            .distinct()
    override suspend fun getCaseDrillCards(gramCase: String, gramGender: String, gramNumber: String, limit: Int): List<Card> =
        cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.CASE_FILL && it.gramCase == gramCase && it.gramGender == gramGender && it.gramNumber == gramNumber && it.state != CardState.GRADUATED && !it.suspended }
            .sortedWith(compareBy<Card> { it.due }.thenBy { it.id })
            .take(limit)
    override suspend fun getVerbFormCards(formKey: String, limit: Int): List<Card> =
        cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.VERB_FORM && it.gramContextCue == formKey && it.state != CardState.GRADUATED && !it.suspended }
            .sortedWith(compareBy<Card> { it.due }.thenBy { it.id })
            .take(limit)
    override suspend fun getGrammarDrillCards(limit: Int): List<Card> =
        cards.filter { it.queue == Queue.GRAMMAR && it.state != CardState.GRADUATED && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
    override suspend fun getCardsForNote(noteId: Long): List<Card> = cards.filter { it.noteId == noteId }
    override suspend fun getCardsForNotes(noteIds: List<Long>): List<Card> = cards.filter { it.noteId in noteIds }
    override suspend fun getByIds(cardIds: List<Long>): List<Card> = cards.filter { it.id in cardIds }
    override suspend fun getAll(): List<Card> = cards.toList()
    override suspend fun getAllVocabCards(): List<Card> = cards.filter { it.queue == Queue.VOCAB }
    override suspend fun getKnownVocabNoteIds(): List<Long> =
        cards.filter {
            it.queue == Queue.VOCAB &&
                (it.state == CardState.GRADUATED || (it.reps >= 2 && it.consecutiveCorrect >= 2 && it.state == CardState.REVIEW))
        }.map { it.noteId }.distinct()
    override suspend fun update(card: Card) {
        cards.replaceAll { if (it.id == card.id) card else it }
    }
    override suspend fun updateAll(cards: List<Card>) {
        cards.forEach { update(it) }
    }
    override suspend fun graduateCaseCategory(gramCase: String, gramGender: String, gramNumber: String): Int {
        var changed = 0
        cards.replaceAll { card ->
            if (card.queue == Queue.GRAMMAR && card.cardType == CardType.CASE_FILL &&
                card.gramCase == gramCase && card.gramGender == gramGender && card.gramNumber == gramNumber &&
                card.state != CardState.GRADUATED
            ) {
                changed += 1
                card.copy(state = CardState.GRADUATED)
            } else card
        }
        return changed
    }
    override suspend fun graduateAspectCategory(aktionsart: String, aspect: String, contextCue: String): Int {
        var changed = 0
        cards.replaceAll { card ->
            val note = notes.notes.firstOrNull { it.id == card.noteId }
            if (card.queue == Queue.GRAMMAR && card.cardType == CardType.ASPECT_SELECT &&
                card.gramContextCue == contextCue && note?.aktionsart == aktionsart && note.aspect == aspect &&
                card.state != CardState.GRADUATED
            ) {
                changed += 1
                card.copy(state = CardState.GRADUATED)
            } else card
        }
        return changed
    }
    override suspend fun graduateVerbFormCategory(formKey: String): Int {
        var changed = 0
        cards.replaceAll { card ->
            if (card.queue == Queue.GRAMMAR && card.cardType == CardType.VERB_FORM &&
                card.gramContextCue == formKey && card.state != CardState.GRADUATED
            ) {
                changed += 1
                card.copy(state = CardState.GRADUATED)
            } else card
        }
        return changed
    }
    override suspend fun graduateVocabForReaderEncounters(minEncounterCount: Int): Int {
        val eligible = readerEncounters.encounters.groupingBy { it.noteId }.eachCount()
            .filterValues { it >= minEncounterCount }.keys
        var changed = 0
        cards.replaceAll { card ->
            if (card.queue == Queue.VOCAB && card.noteId in eligible && card.state != CardState.GRADUATED) {
                changed += 1
                card.copy(state = CardState.GRADUATED)
            } else card
        }
        return changed
    }
    override suspend fun insert(card: Card): Long {
        val id = nextId++
        cards += card.copy(id = id)
        return id
    }
    override suspend fun insertAll(cards: List<Card>): List<Long> = cards.map { insert(it) }
    override suspend fun moveToNote(cardId: Long, targetNoteId: Long) {
        cards.replaceAll { if (it.id == cardId) it.copy(noteId = targetNoteId) else it }
    }
    override suspend fun deleteById(cardId: Long) { cards.removeAll { it.id == cardId } }
    override suspend fun suspendAmbiguousProduction(noteId: Long): Int {
        var changed = 0
        cards.replaceAll {
            if (it.noteId == noteId && it.cardType in setOf(CardType.MEANING_TO_RU, CardType.CLOZE, CardType.SENTENCE_BUILD) && !it.suspended) {
                changed += 1; it.copy(suspended = true)
            } else it
        }
        return changed
    }
}

internal class FakeReviewLogDao(
    private val cards: FakeCardDao,
    private val notes: FakeNoteDao
) : ReviewLogDao {
    val logs = mutableListOf<ReviewLog>()
    private var nextId = 1L

    override suspend fun insert(log: ReviewLog) {
        logs += log.copy(id = nextId++)
    }
    override suspend fun insertAll(logs: List<ReviewLog>) { logs.forEach { insert(it) } }
    override suspend fun getAll(): List<ReviewLog> = logs.sortedWith(compareBy<ReviewLog> { it.reviewDatetime }.thenBy { it.id })

    private fun recallLogs(): List<ReviewLog> = logs.filter { it.source != ReviewSource.READER_LOOKUP }

    override suspend fun countSince(since: Long): Int = recallLogs().count { it.reviewDatetime >= since }
    override suspend fun countAll(): Int = recallLogs().size
    override suspend fun weightedXp(): Int = recallLogs().sumOf { log ->
        val quality = when (log.rating) {
            Rating.AGAIN -> 2
            Rating.HARD -> 8
            Rating.GOOD -> 10
            Rating.EASY -> 14
        }
        quality + if ((log.stateBefore == CardState.REVIEW || log.stateBefore == CardState.RELEARNING) && log.elapsedDays > 0) 2 else 0
    }
    override suspend fun matureReviewCount(since: Long): Int =
        recallLogs().count { it.reviewDatetime >= since && (it.stateBefore == CardState.REVIEW || it.stateBefore == CardState.RELEARNING) && it.elapsedDays > 0 }
    override suspend fun matureRetainedCount(since: Long): Int =
        recallLogs().count { it.reviewDatetime >= since && (it.stateBefore == CardState.REVIEW || it.stateBefore == CardState.RELEARNING) && it.elapsedDays > 0 && it.rating != Rating.AGAIN }
    override suspend fun reviewFitRows(since: Long): List<ReviewFitRow> =
        recallLogs().filter { it.reviewDatetime >= since }
            .sortedWith(compareBy<ReviewLog> { it.cardId }.thenBy { it.reviewDatetime }.thenBy { it.id })
            .map { ReviewFitRow(it.cardId, it.reviewDatetime, it.rating, it.stateBefore, it.elapsedDays, it.stabilityBefore) }
    override suspend fun countNewIntroducedSince(since: Long): Int =
        recallLogs().filter { log ->
            log.reviewDatetime >= since &&
                log.stateBefore == CardState.NEW &&
                cards.cards.firstOrNull { it.id == log.cardId }?.cardType != CardType.LESSON
        }.mapNotNull { log -> cards.cards.firstOrNull { it.id == log.cardId }?.noteId }.distinct().size
    override suspend fun getReviewedNoteIds(): List<Long> =
        recallLogs().mapNotNull { log -> cards.cards.firstOrNull { it.id == log.cardId } }
            .filter { it.cardType != CardType.LESSON }
            .map { it.noteId }
            .distinct()
    override suspend fun getReviewedCardsSince(since: Long): List<Card> =
        recallLogs().filter { it.reviewDatetime >= since }
            .mapNotNull { log -> cards.cards.firstOrNull { it.id == log.cardId } }
            .distinctBy { it.id }
    override suspend fun deleteLatestForCard(cardId: Long) {
        val last = logs.filter { it.cardId == cardId }.maxByOrNull { it.id } ?: return
        logs.remove(last)
    }
    override suspend fun reviewDayBuckets(tzOffset: Long, dayMillis: Long): List<Long> =
        recallLogs().map { (it.reviewDatetime + tzOffset) / dayMillis }.distinct().sortedDescending()
    override suspend fun recentCategoryRatings(limit: Int): List<ReviewCategoryRatingRow> =
        recallLogs().sortedWith(compareByDescending<ReviewLog> { it.reviewDatetime }.thenByDescending { it.id })
            .mapNotNull { log ->
                val card = cards.cards.firstOrNull { it.id == log.cardId } ?: return@mapNotNull null
                if (card.cardType !in setOf(CardType.CASE_FILL, CardType.ASPECT_SELECT, CardType.VERB_FORM)) return@mapNotNull null
                val note = notes.notes.firstOrNull { it.id == card.noteId }
                ReviewCategoryRatingRow(
                    cardType = card.cardType,
                    gramCase = card.gramCase,
                    gramGender = card.gramGender,
                    gramNumber = card.gramNumber,
                    contextCue = card.gramContextCue,
                    aktionsart = note?.aktionsart,
                    aspect = note?.aspect,
                    rating = log.rating
                )
            }
            .take(limit)
    override suspend fun nounCategoryRatings(gramCase: String, gramGender: String, gramNumber: String, limit: Int): List<Rating> =
        recallLogs().sortedByDescending { it.reviewDatetime }
            .filter { log ->
                val card = cards.cards.firstOrNull { it.id == log.cardId }
                card?.gramCase == gramCase && card.gramGender == gramGender && card.gramNumber == gramNumber
            }
            .take(limit)
            .map { it.rating }
    override suspend fun aspectCategoryRatings(aktionsart: String, aspect: String, contextCue: String, limit: Int): List<Rating> =
        recallLogs().sortedByDescending { it.reviewDatetime }
            .filter { log ->
                val card = cards.cards.firstOrNull { it.id == log.cardId }
                val note = card?.let { notes.notes.firstOrNull { note -> note.id == it.noteId } }
                note?.aktionsart == aktionsart && note.aspect == aspect && card.gramContextCue == contextCue
            }
            .take(limit)
            .map { it.rating }
    override suspend fun verbFormCategoryRatings(formKey: String, limit: Int): List<Rating> =
        recallLogs().sortedByDescending { it.reviewDatetime }
            .filter { log ->
                val card = cards.cards.firstOrNull { it.id == log.cardId }
                card?.cardType == CardType.VERB_FORM && card.gramContextCue == formKey
            }
            .take(limit)
            .map { it.rating }
    override suspend fun moveLogs(sourceCardId: Long, targetCardId: Long) {
        logs.replaceAll { if (it.cardId == sourceCardId) it.copy(cardId = targetCardId) else it }
    }
}

internal class FakeConfusablePairDao : ConfusablePairDao {
    val pairs = mutableListOf<ConfusablePair>()
    private var nextId = 1L
    override suspend fun insert(pair: ConfusablePair): Long {
        val id = nextId++
        pairs += pair.copy(id = id)
        return id
    }
    override suspend fun getForNote(noteId: Long): List<ConfusablePair> = pairs.filter { it.firstNoteId == noteId || it.secondNoteId == noteId }
    override suspend fun getAll(): List<ConfusablePair> = pairs
    override suspend fun moveFirstReferences(sourceId: Long, targetId: Long) {
        pairs.replaceAll { if (it.firstNoteId == sourceId) it.copy(firstNoteId = targetId) else it }
    }
    override suspend fun moveSecondReferences(sourceId: Long, targetId: Long) {
        pairs.replaceAll { if (it.secondNoteId == sourceId) it.copy(secondNoteId = targetId) else it }
    }
    override suspend fun deleteSelfPairs() { pairs.removeAll { it.firstNoteId == it.secondNoteId } }
    override suspend fun deleteDuplicatePairs(): Int {
        val seen = mutableSetOf<Triple<Long, Long, String>>()
        val before = pairs.size
        pairs.removeAll { pair ->
            !seen.add(Triple(minOf(pair.firstNoteId, pair.secondNoteId), maxOf(pair.firstNoteId, pair.secondNoteId), pair.reason))
        }
        return before - pairs.size
    }
}

internal class FakeReaderTextDao : ReaderTextDao {
    val texts = mutableListOf<ReaderText>()
    private var nextId = 1L
    override suspend fun insert(text: ReaderText): Long {
        val id = nextId++
        texts += text.copy(id = id)
        return id
    }
    override suspend fun insertAll(texts: List<ReaderText>): List<Long> = texts.map { insert(it) }
    override suspend fun count(): Int = texts.size
    override suspend fun getAll(): List<ReaderText> = texts
    override suspend fun getById(id: Long): ReaderText? = texts.firstOrNull { it.id == id }
    override suspend fun deleteById(id: Long) { texts.removeAll { it.id == id } }
}

internal class FakeReadingScheduleDao : ReadingScheduleDao {
    val schedules = mutableListOf<ReadingSchedule>()
    override suspend fun insert(schedule: ReadingSchedule): Long {
        schedules.removeAll { it.readerTextId == schedule.readerTextId }
        schedules += schedule
        return schedule.readerTextId
    }
    override suspend fun insertAll(schedules: List<ReadingSchedule>): List<Long> = schedules.map { insert(it) }
    override suspend fun update(schedule: ReadingSchedule) { insert(schedule) }
    override suspend fun get(readerTextId: Long): ReadingSchedule? = schedules.firstOrNull { it.readerTextId == readerTextId }
    override suspend fun nextDue(now: Long): ReadingSchedule? = schedules.filter { it.due <= now }.minByOrNull { it.due }
    override suspend fun getAll(): List<ReadingSchedule> = schedules.toList()
    override suspend fun deleteForText(readerTextId: Long) { schedules.removeAll { it.readerTextId == readerTextId } }
}

internal class FakeReaderEncounterDao : ReaderEncounterDao {
    val encounters = mutableListOf<ReaderEncounter>()
    override suspend fun insert(encounter: ReaderEncounter): Long {
        if (encounters.any { it.readerTextId == encounter.readerTextId && it.noteId == encounter.noteId }) return -1L
        encounters += encounter
        return encounters.size.toLong()
    }
    override suspend fun insertAll(encounters: List<ReaderEncounter>): List<Long> = encounters.map { insert(it) }
    override suspend fun getAll(): List<ReaderEncounter> = encounters.toList()
    override suspend fun getForText(readerTextId: Long): List<ReaderEncounter> = encounters.filter { it.readerTextId == readerTextId }
    override suspend fun getForNote(noteId: Long): List<ReaderEncounter> = encounters.filter { it.noteId == noteId }
    override suspend fun noteIdsWithMinimumEncounters(minimum: Int): List<Long> =
        encounters.groupingBy { it.noteId }.eachCount().filterValues { it >= minimum }.keys.toList()
    override suspend fun deleteForText(readerTextId: Long) { encounters.removeAll { it.readerTextId == readerTextId } }
    override suspend fun deleteForNote(noteId: Long) { encounters.removeAll { it.noteId == noteId } }
}

internal class FakeReadingActivityDao : ReadingActivityDao {
    val activities = mutableListOf<ReadingActivity>()
    private var nextId = 1L
    override suspend fun insert(activity: ReadingActivity): Long {
        val id = nextId++
        activities += activity.copy(id = id)
        return id
    }
    override suspend fun insertAll(activities: List<ReadingActivity>): List<Long> = activities.map { insert(it) }
    override suspend fun getAll(): List<ReadingActivity> = activities.sortedBy { it.completedAt }
    override suspend fun getForText(readerTextId: Long): List<ReadingActivity> = activities.filter { it.readerTextId == readerTextId }.sortedBy { it.completedAt }
    override suspend fun countAll(): Int = activities.size
    override suspend fun countSince(since: Long): Int = activities.count { it.completedAt >= since }
    override suspend fun dayBuckets(tzOffset: Long, dayMillis: Long): List<Long> =
        activities.map { (it.completedAt + tzOffset) / dayMillis }.distinct().sortedDescending()
    override suspend fun moveToText(sourceId: Long, targetId: Long): Int {
        var changed = 0
        activities.replaceAll {
            if (it.readerTextId == sourceId) {
                changed += 1
                it.copy(readerTextId = targetId)
            } else it
        }
        return changed
    }
}

internal class FakeTelemetryDao : TelemetryDao {
    val events = mutableListOf<TelemetryEvent>()
    private var nextId = 1L
    override suspend fun insert(event: TelemetryEvent): Long {
        val id = nextId++
        events += event.copy(id = id)
        return id
    }
    override suspend fun insertAll(events: List<TelemetryEvent>): List<Long> = events.map { insert(it) }
    override suspend fun recent(limit: Int): List<TelemetryEvent> = events.sortedByDescending { it.timestamp }.take(limit)
    override suspend fun getAll(): List<TelemetryEvent> = events.sortedBy { it.timestamp }
    override suspend fun deleteOlderThan(cutoff: Long): Int {
        val before = events.size
        events.removeAll { it.timestamp < cutoff }
        return before - events.size
    }
    override suspend fun eventDayBuckets(eventType: String, tzOffset: Long, dayMillis: Long): List<Long> =
        events.filter { it.eventType == eventType }.map { (it.timestamp + tzOffset) / dayMillis }.distinct().sortedDescending()
    override suspend fun countByType(eventType: String): Int = events.count { it.eventType == eventType }
    override suspend fun countByTypeSince(eventType: String, since: Long): Int =
        events.count { it.eventType == eventType && it.timestamp >= since }
}
