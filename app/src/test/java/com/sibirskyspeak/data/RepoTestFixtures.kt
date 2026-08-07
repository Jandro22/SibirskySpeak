package com.sibirskyspeak.data

import com.sibirskyspeak.scheduler.FsrsScheduler
import kotlinx.coroutines.Dispatchers
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
    bootstrapManifest: String? = null,
    restoreBackup: (suspend () -> String?)? = null,
    writeBackup: (suspend (String) -> Unit)? = null,
    writeBackupLines: (suspend (Sequence<String>) -> Unit)? = null,
    config: () -> LearningConfig = { LearningConfig() },
    withTelemetry: Boolean = false,
    contentDao: ContentDao? = null,
    bootstrapTransformations: String? = null,
    bootstrapPhonology: String? = null,
    morphologyEngine: com.sibirskyspeak.morph.MorphologyEngine? = null,
    learningModelDao: LearningModelDao? = null,
    settingsStore: SettingsStore? = null
) {
    val notes = FakeNoteDao()
    val evidence = FakeNoteEvidenceDao()
    val forms = FakeNoteFormDao()
    val readerEncounters = FakeReaderEncounterDao()
    val cards = FakeCardDao(notes, readerEncounters)
    val logs = FakeReviewLogDao(cards, notes).also { cards.reviewLogs = it }
    val pairs = FakeConfusablePairDao()
    val readers = FakeReaderTextDao()
    val bookmarks = FakeReaderBookmarkDao()
    val readingSchedules = FakeReadingScheduleDao()
    val readingActivities = FakeReadingActivityDao()
    val telemetry = if (withTelemetry) FakeTelemetryDao() else null
    val confusionEvents = FakeConfusionEventDao()
    val checkpointResults = FakeCheckpointResultDao()
    val curriculumState = FakeCurriculumStateDao()
    val communicativeLearning = FakeCommunicativeLearningDao { noteId ->
        notes.notes.firstOrNull { it.id == noteId }?.status
    }
    val repository = LearningRepository(
        notes,
        cards,
        logs,
        pairs,
        readers,
        FsrsScheduler(),
        bootstrapNotes = { bootstrapNotes },
        bootstrapReaderTexts = { bootstrapReaderTexts },
        bootstrapManifest = { bootstrapManifest },
        config = config,
        restoreBackup = restoreBackup,
        writeBackup = writeBackup,
        writeBackupLines = writeBackupLines,
        telemetryDao = telemetry,
        readingScheduleDao = readingSchedules,
        readerEncounterDao = readerEncounters,
        readerBookmarkDao = bookmarks,
        readingActivityDao = readingActivities,
        confusionEventDao = confusionEvents,
        checkpointResultDao = checkpointResults,
        curriculumStateDao = curriculumState,
        communicativeLearningDao = communicativeLearning,
        noteEvidenceDao = evidence,
        noteFormDao = forms,
        contentDao = contentDao,
        morphologyEngine = morphologyEngine,
        bootstrapTransformations = { bootstrapTransformations },
        bootstrapPhonology = { bootstrapPhonology },
        learningModelDao = learningModelDao,
        settingsStore = settingsStore,
        // Unconfined runs the repository's withContext(compute) blocks inline on the
        // caller, so the deterministic test scheduler still controls all of its work.
        computeDispatcher = Dispatchers.Unconfined
    )
}

internal class FakeCommunicativeLearningDao(
    private val currentNoteStatus: (Long) -> WordStatus? = { null }
) : CommunicativeLearningDao {
    val components = linkedMapOf<String, KnowledgeComponent>()
    val evidence = mutableListOf<CapabilityEvidence>()
    val progress = linkedMapOf<String, CapabilityProgress>()
    private var nextEvidenceId = 1L

    override suspend fun upsertComponent(component: KnowledgeComponent) { components[component.key] = component }
    override suspend fun upsertComponents(components: List<KnowledgeComponent>) { components.forEach { upsertComponent(it) } }
    override suspend fun updateComponent(component: KnowledgeComponent) { components[component.key] = component }
    override suspend fun deleteComponent(key: String) { components.remove(key) }
    override suspend fun component(key: String): KnowledgeComponent? = components[key]
    override suspend fun componentsForCapability(capabilityKey: String): List<KnowledgeComponent> =
        components.values.filter { it.capabilityKey == capabilityKey && !it.retired }.sortedWith(compareBy({ it.due }, { it.key }))
    override suspend fun allComponentsForCapability(capabilityKey: String): List<KnowledgeComponent> =
        components.values.filter { it.capabilityKey == capabilityKey }.sortedWith(compareBy({ it.due }, { it.key }))
    override suspend fun dueComponents(now: Long, limit: Int): List<KnowledgeComponent> =
        components.values.filter { !it.retired && it.due <= now }.sortedWith(compareBy<KnowledgeComponent> { it.due }.thenByDescending { it.difficulty }).take(limit)
    override suspend fun componentCount(): Int = components.size
    override suspend fun allComponents(): List<KnowledgeComponent> = components.values.sortedBy { it.key }
    override suspend fun retireComponentsForNote(noteId: Long): Int {
        val matches = components.values.filter { it.noteId == noteId }
        matches.forEach { components[it.key] = it.copy(retired = true) }
        return matches.size
    }
    override suspend fun reactivateComponentsForNote(noteId: Long, now: Long): Int {
        val matches = components.values.filter { it.noteId == noteId }
        matches.forEach { components[it.key] = it.copy(retired = false, due = minOf(it.due, now)) }
        return matches.size
    }
    override suspend fun retireComponentsForKnownNotes(): Int {
        val knownIds = components.values.mapNotNull { it.noteId }.filter { noteId ->
            currentNoteStatus(noteId) in setOf(WordStatus.KNOWN, WordStatus.IGNORED)
        }.toSet()
        var changed = 0
        knownIds.forEach { changed += retireComponentsForNote(it) }
        return changed
    }
    override suspend fun insertEvidence(evidence: CapabilityEvidence): Long {
        if (this.evidence.any { it.episodeId == evidence.episodeId && it.taskId == evidence.taskId && it.componentKey == evidence.componentKey }) return -1L
        val id = if (evidence.id == 0L) nextEvidenceId++ else evidence.id
        this.evidence += evidence.copy(id = id)
        return id
    }
    override suspend fun deleteEvidenceForTaskComponent(episodeId: String, taskId: String, componentKey: String) {
        evidence.removeAll { it.episodeId == episodeId && it.taskId == taskId && it.componentKey == componentKey }
    }
    override suspend fun evidenceForTaskComponent(episodeId: String, taskId: String, componentKey: String): CapabilityEvidence? =
        evidence.firstOrNull { it.episodeId == episodeId && it.taskId == taskId && it.componentKey == componentKey }
    override suspend fun recentEvidence(componentKey: String, limit: Int): List<CapabilityEvidence> =
        evidence.filter { it.componentKey == componentKey }.sortedByDescending { it.observedAt }.take(limit)
    override suspend fun recentEvidenceForCapability(capabilityKey: String, limit: Int): List<CapabilityEvidence> {
        val keys = components.values.filter { it.capabilityKey == capabilityKey }.mapTo(hashSetOf()) { it.key }
        return evidence.filter { it.componentKey in keys }
            .sortedWith(compareByDescending<CapabilityEvidence> { it.observedAt }.thenByDescending { it.id })
            .take(limit)
    }
    override suspend fun allEvidence(): List<CapabilityEvidence> = evidence.sortedWith(compareBy({ it.observedAt }, { it.id }))
    override suspend fun upsertProgress(progress: CapabilityProgress) { this.progress[progress.capabilityKey] = progress }
    override suspend fun progress(capabilityKey: String): CapabilityProgress? = progress[capabilityKey]
    override suspend fun allProgress(): List<CapabilityProgress> = progress.values.sortedWith(compareBy({ it.band }, { it.unit }))
}

internal class FakeNoteEvidenceDao : NoteEvidenceDao {
    val rows = mutableMapOf<Long, NoteEvidence>()
    override suspend fun ensure(value: NoteEvidence): Long {
        rows.putIfAbsent(value.noteId, value)
        return value.noteId
    }
    override suspend fun upsert(value: NoteEvidence): Long { rows[value.noteId] = value; return value.noteId }
    override suspend fun get(noteId: Long): NoteEvidence? = rows[noteId]
    override suspend fun all(): List<NoteEvidence> = rows.values.toList()
    override suspend fun delete(noteId: Long): Int = if (rows.remove(noteId) != null) 1 else 0
    override suspend fun incrementDirect(noteId: Long, at: Long): Int = update(noteId) { it.copy(directRetrievals = it.directRetrievals + 1, lastDirectAt = at) }
    override suspend fun incrementPassive(noteId: Long, at: Long): Int = update(noteId) { it.copy(passiveExposures = it.passiveExposures + 1, lastPassiveAt = at) }
    override suspend fun incrementReading(noteId: Long): Int = update(noteId) { it.copy(completedReadings = it.completedReadings + 1) }
    override suspend fun incrementLookup(noteId: Long, at: Long): Int = update(noteId) { it.copy(lookups = it.lookups + 1, lastLookupAt = at) }
    override suspend fun incrementPlacement(noteId: Long): Int = update(noteId) { it.copy(placementPriors = it.placementPriors + 1) }
    private fun update(noteId: Long, block: (NoteEvidence) -> NoteEvidence): Int {
        val current = rows[noteId] ?: return 0
        rows[noteId] = block(current)
        return 1
    }
}

internal class FakeNoteFormDao : NoteFormDao {
    val rows = linkedMapOf<String, NoteForm>()
    override suspend fun insertAll(values: List<NoteForm>): List<Long> = values.map { value ->
        if (rows.putIfAbsent(value.surface, value) == null) 1L else -1L
    }
    override suspend fun all(): List<NoteForm> = rows.values.toList()
    override suspend fun count(): Int = rows.size
    override suspend fun deleteForNote(noteId: Long): Int {
        val before = rows.size
        rows.entries.removeAll { it.value.noteId == noteId }
        return before - rows.size
    }
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
    override suspend fun getByIds(ids: List<Long>): List<Note> = notes.filter { it.id in ids }
    override suspend fun getByLemma(lemma: String): Note? = notes.firstOrNull { it.lemma == lemma }
    override suspend fun getByLemmas(lemmas: List<String>): List<Note> = notes.filter { it.lemma in lemmas }
    override suspend fun count(): Int = notes.size
    override fun observeAll(): Flow<List<Note>> = flowOf(notes)
    override suspend fun getAll(): List<Note> = notes.toList()
    override suspend fun getFrameLexemes(): List<com.sibirskyspeak.generation.FrameLexeme> =
        notes.filter { it.tier == 0 && it.partOfSpeech in setOf("noun", "verb", "adjective") }
            .map {
                com.sibirskyspeak.generation.FrameLexeme(
                    lemma = it.lemma,
                    translation = it.translation,
                    gender = it.gender,
                    aspect = it.aspect,
                    partOfSpeech = it.partOfSpeech
                )
            }
    override suspend fun qualityCounts(): NoteQualityCounts {
        fun readable(note: Note): Boolean = CardFactory.hasReadableExample(note)
        fun nominal(note: Note): Boolean =
            note.partOfSpeech.lowercase() in setOf("noun", "adjective") &&
                !note.declensionJson.isNullOrBlank() && !note.gender.isNullOrBlank() &&
                note.domainFreqRank != null && readable(note)
        fun aspectVerb(note: Note): Boolean =
            note.partOfSpeech.equals("verb", true) && note.aspectPartner != null &&
                !note.aspect.isNullOrBlank() && !note.aktionsart.isNullOrBlank() &&
                note.domainFreqRank != null && readable(note)
        return NoteQualityCounts(
            totalNotes = notes.size,
            readyNominalRows = notes.count(::nominal),
            aspectReadyVerbRows = notes.count(::aspectVerb),
            verifiedAktionsartVerbRows = notes.count {
                aspectVerb(it) && it.aktionsartConfidence?.lowercase() in setOf("high", "manual", "verified")
            },
            domainRankedRows = notes.count { it.domainFreqRank != null },
            exampleRows = notes.count(::readable)
        )
    }
    override suspend fun examplesNeedingSplit(): List<Note> = notes.filter { n ->
        val es = n.exampleSentence
        n.exampleTranslation.isNullOrBlank() && es != null &&
            (es.contains(" - ") || es.contains(" — ") || es.contains(" – ")) &&
            es.any { it in 'a'..'z' || it in 'A'..'Z' }
    }
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
    // Set by RepoFixture right after constructing FakeReviewLogDao (which itself
    // needs `cards` to already exist) — only getGrammarDrillFirstReviewOutcomes
    // below needs cross-DAO access, so a late-bound reference beats restructuring
    // construction order for every other method that doesn't need it.
    var reviewLogs: FakeReviewLogDao? = null
    private fun Card.isIgnored(): Boolean = notes.notes.firstOrNull { it.id == noteId }?.status == WordStatus.IGNORED
    override suspend fun dashboardCounts(now: Long): CardDashboardCounts = CardDashboardCounts(
        vocabCards = cards.count { it.queue == Queue.VOCAB },
        grammarCards = cards.count { it.queue == Queue.GRAMMAR },
        dueVocab = cards.count { it.queue == Queue.VOCAB && it.due <= now && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended && !it.isIgnored() },
        dueGrammar = cards.count { it.queue == Queue.GRAMMAR && it.due <= now && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended && !it.isIgnored() }
    )

    override suspend fun getDueCards(now: Long, limit: Int): List<Card> =
        cards.filter { it.due <= now && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended && !it.isIgnored() }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
    override suspend fun getDueCardsByQueue(now: Long, queue: Queue, limit: Int): List<Card> =
        cards.filter { it.due <= now && it.queue == queue && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended && !it.isIgnored() }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
    override suspend fun countDueBetween(start: Long, end: Long): Int =
        cards.count { it.due > start && it.due <= end && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended && !it.isIgnored() }
    override suspend fun countDueByDay(start: Long, end: Long, dayMillis: Long): List<DueDayCount> =
        cards.asSequence()
            .filter { it.due > start && it.due <= end && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended && !it.isIgnored() }
            .groupingBy { ((it.due - start - 1) / dayMillis).toInt() }
            .eachCount()
            .map { (day, count) -> DueDayCount(day, count) }
    override suspend fun getLeechCards(threshold: Int): List<Card> =
        cards.filter { it.suspended && it.lapses >= threshold }.sortedWith(compareByDescending<Card> { it.lapses }.thenBy { it.id })
    override suspend fun getProblemCards(minReps: Int, limit: Int): List<Card> =
        cards.filter { it.reps >= minReps && (it.lapses > 0 || it.difficulty >= 8.0) && it.state != CardState.GRADUATED && !it.suspended }
            .sortedWith(compareByDescending<Card> { it.lapses }.thenByDescending { it.difficulty }).take(limit)
    override suspend fun getOverdueCards(cutoff: Long, limit: Int): List<Card> =
        cards.filter { it.due <= cutoff && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended && !it.isIgnored() }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
    override suspend fun getAllDueCards(now: Long): List<Card> =
        cards.filter { it.due <= now && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended && !it.isIgnored() }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id })
    override suspend fun getDueSoonNoteIds(cutoff: Long): List<Long> =
        cards.filter { it.due <= cutoff && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended && !it.isIgnored() }.map { it.noteId }.distinct()
    override suspend fun getGraduatedRecognitionCards(): List<Card> =
        cards.filter { it.cardType == CardType.RU_TO_MEANING && it.state == CardState.GRADUATED }.sortedBy { it.lastReview ?: 0L }
    override suspend fun getNewCards(limit: Int): List<Card> =
        cards.filter { it.state == CardState.NEW && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
    override suspend fun getSampleCardsOfType(cardType: CardType, limit: Int): List<Card> =
        cards.filter { it.cardType == cardType && !it.suspended }.sortedWith(compareByDescending<Card> { it.reps }.thenBy { it.id }).take(limit)
    override suspend fun getGrammarConceptOutcomes(): List<GrammarConceptOutcome> =
        cards.filter { it.queue == Queue.GRAMMAR && it.cardType != CardType.LESSON && !it.suspended }
            .mapNotNull { card ->
                GrammarConcepts.forCard(card)?.id?.let { concept -> concept to card }
            }
            .groupBy({ it.first }, { it.second })
            .map { (concept, conceptCards) ->
                GrammarConceptOutcome(
                    concept = concept,
                    probationCardId = conceptCards.minOf { it.id },
                    everSucceeded = conceptCards.any { card ->
                        reviewLogs?.logs?.any {
                            it.cardId == card.id && it.rating != Rating.AGAIN
                        } ?: false
                    }
                )
            }
    override suspend fun getNewCardsOrdered(limit: Int, maxCefrOrdinal: Int): List<Card> {
        val cefrOrder = listOf("A1", "A2", "B1", "B2", "C1", "C2")
        fun cefrOrdinal(n: Note?): Int = cefrOrder.indexOf(n?.cefrLevel).let { if (it < 0) 0 else it }
        fun rank(card: Card): IntArray {
            val n = notes.notes.firstOrNull { it.id == card.noteId }
            val tierPhase = if (n?.tier == 0) 0 else 1
            val unit = n?.unit ?: Int.MAX_VALUE
            val freq = n?.domainFreqRank ?: n?.generalFreqRank ?: Int.MAX_VALUE
            return intArrayOf(cefrOrdinal(n), tierPhase, unit, freq)
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
                cefrOrdinal(n) <= maxCefrOrdinal &&
                (card.queue != Queue.GRAMMAR || card.cardType == CardType.LESSON || (n?.encounterCount ?: 0) > 0) &&
                (!advanced || matureRecognition)
        }
        return cards.filter { eligible(it) }
            .sortedWith(
                compareBy<Card>({ rank(it)[0] }, { rank(it)[1] }, { rank(it)[2] }, { rank(it)[3] }, { it.id })
            )
            .take(limit)
    }
    override suspend fun getNewCardsOrderedPage(
        limit: Int,
        offset: Int,
        maxCefrOrdinal: Int,
        reviewedNotesOnly: Boolean
    ): List<Card> {
        val reviewedNoteIds = reviewLogs?.logs.orEmpty()
            .mapNotNull { log -> cards.firstOrNull { it.id == log.cardId }?.noteId }
            .toHashSet()
        return getNewCardsOrdered(Int.MAX_VALUE, maxCefrOrdinal)
            .filter { !reviewedNotesOnly || it.cardType == CardType.LESSON || it.noteId in reviewedNoteIds }
            .drop(offset)
            .take(limit)
    }
    override suspend fun graduateVocabForNote(
        noteId: Long,
        due: Long,
        now: Long,
        stability: Double,
        difficulty: Double,
        scheduledDays: Int
    ): Int {
        var changed = 0
        cards.replaceAll { card ->
            if (card.noteId == noteId && card.queue == Queue.VOCAB &&
                (card.state != CardState.GRADUATED || card.stability <= 0.0 || card.difficulty <= 0.0 || card.due != due)
            ) {
                changed += 1
                card.copy(
                    state = CardState.GRADUATED,
                    due = due,
                    stability = stability,
                    difficulty = difficulty,
                    scheduledDays = scheduledDays,
                    elapsedDays = 0,
                    reps = maxOf(card.reps, 3),
                    consecutiveCorrect = maxOf(card.consecutiveCorrect, 3),
                    lastReview = now
                )
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
    override suspend fun repairGraduatedRecognitionMaturity(): Int {
        var changed = 0
        cards.replaceAll { card ->
            if (card.cardType == CardType.RU_TO_MEANING && card.state == CardState.GRADUATED &&
                (card.reps < 3 || card.consecutiveCorrect < 3)
            ) {
                changed += 1
                card.copy(reps = maxOf(card.reps, 3), consecutiveCorrect = maxOf(card.consecutiveCorrect, 3))
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
    override suspend fun getTaperedConceptIds(): List<String> =
        cards.filter { it.cardType == CardType.CONCEPT_APPLY && it.gramConcept != null && it.reps >= 4 && it.consecutiveCorrect >= 3 }
            .mapNotNull { it.gramConcept }
            .distinct()
    override suspend fun getByNoteAndType(noteId: Long, cardType: CardType): Card? = cards.firstOrNull { it.noteId == noteId && it.cardType == cardType }
    override suspend fun countDue(now: Long): Int = cards.count { it.due <= now && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended && !it.isIgnored() }
    override suspend fun countDueByQueue(now: Long, queue: Queue): Int = cards.count { it.due <= now && it.queue == queue && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended && !it.isIgnored() }
    override suspend fun countByQueue(queue: Queue): Int = cards.count { it.queue == queue }
    override suspend fun getGrammarCardsForNounCategory(gramCase: String, gramGender: String, gramNumber: String): List<Card> =
        cards.filter { it.queue == Queue.GRAMMAR && it.gramCase == gramCase && it.gramGender == gramGender && it.gramNumber == gramNumber }
    override suspend fun getGrammarCardsForNotes(noteIds: List<Long>): List<Card> = cards.filter { it.queue == Queue.GRAMMAR && it.noteId in noteIds }
    override suspend fun getAspectCards(): List<Card> = cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.ASPECT_SELECT && it.state != CardState.GRADUATED && !it.suspended }
    override suspend fun suspendDeprecatedAspectCueCards(): Int {
        var changed = 0
        cards.replaceAll { card ->
            if (card.cardType == CardType.ASPECT_SELECT && card.gramContextCue in setOf("RESULT", "SINGLE_EVENT") && !card.suspended) {
                changed++
                card.copy(suspended = true)
            } else card
        }
        return changed
    }
    override suspend fun suspendUnglossedChunkCards(): Int {
        val unglossed = notes.notes.filter {
            it.partOfSpeech == "chunk" && it.translation.isBlank()
        }.mapTo(hashSetOf()) { it.id }
        var changed = 0
        cards.replaceAll { card ->
            if (card.cardType == CardType.CHUNK && card.noteId in unglossed && !card.suspended) {
                changed++
                card.copy(suspended = true)
            } else card
        }
        return changed
    }
    override suspend fun suspendExistentialHomographMorphologyCards(): Int {
        val noteIds = notes.notes.filter {
            it.lemma == "есть" && it.translation.startsWith("there is")
        }.mapTo(hashSetOf()) { it.id }
        var changed = 0
        cards.replaceAll { card ->
            if (!card.suspended && card.noteId in noteIds &&
                card.cardType in setOf(CardType.VERB_FORM, CardType.TRANSFORM)
            ) {
                changed++
                card.copy(suspended = true)
            } else card
        }
        return changed
    }
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
    override suspend fun getCardsForConcept(concept: String): List<Card> =
        cards.filter { it.gramConcept == concept && it.cardType != CardType.LESSON }
    override suspend fun getByIds(cardIds: List<Long>): List<Card> = cards.filter { it.id in cardIds }
    override suspend fun getAll(): List<Card> = cards.toList()
    override suspend fun getSchedulingCards(): List<Card> =
        cards.filter { it.state !in setOf(CardState.NEW, CardState.GRADUATED) && !it.suspended && !it.isIgnored() }
    override suspend fun countEstablishedCards(): Int =
        cards.count { it.state == CardState.GRADUATED || it.reps >= 2 }
    override suspend fun getAllVocabCards(): List<Card> = cards.filter { it.queue == Queue.VOCAB }
    override suspend fun cefrVocabProgress(): List<CefrVocabProgressRow> =
        notes.notes.asSequence()
            .filter { it.tier == 0 && it.cefrLevel != null && it.status != WordStatus.IGNORED }
            .groupBy { it.cefrLevel!! }
            .map { (band, levelNotes) ->
                CefrVocabProgressRow(
                    band = band,
                    total = levelNotes.size,
                    mastered = levelNotes.count { note ->
                        cards.filter {
                            it.noteId == note.id && it.cardType == CardType.RU_TO_MEANING && !it.suspended
                        }.any {
                            it.state == CardState.GRADUATED ||
                                (it.reps >= 2 && it.consecutiveCorrect >= 2)
                        }
                    }
                )
            }
    override suspend fun unitVocabProgress(): List<UnitVocabProgressRow> =
        cards.asSequence()
            .filter { card ->
                val note = notes.notes.firstOrNull { it.id == card.noteId }
                note?.tier == 0 && note.unit != null && note.status != WordStatus.IGNORED &&
                    card.cardType == CardType.RU_TO_MEANING && !card.suspended
            }
            .groupBy { card ->
                val note = notes.notes.first { it.id == card.noteId }
                (note.cefrLevel ?: "A1") to note.unit!!
            }
            .map { (key, unitCards) ->
                UnitVocabProgressRow(
                    band = key.first,
                    unit = key.second,
                    total = unitCards.size,
                    mastered = unitCards.count {
                        it.state == CardState.GRADUATED || (it.reps >= 2 && it.consecutiveCorrect >= 2)
                    },
                    introduced = unitCards.count { it.state != CardState.NEW }
                )
            }
    override suspend fun unitGrammarObjectiveProgress(): List<UnitGrammarObjectiveProgressRow> =
        cards.asSequence()
            .filter { card ->
                val note = notes.notes.firstOrNull { it.id == card.noteId }
                note?.tier == 0 && note.unit != null &&
                    note.status !in setOf(WordStatus.KNOWN, WordStatus.IGNORED) &&
                    card.queue == Queue.GRAMMAR && card.cardType != CardType.LESSON && !card.suspended
            }
            .groupBy { card ->
                val note = notes.notes.first { it.id == card.noteId }
                Triple(
                    note.cefrLevel ?: "A1",
                    note.unit!!,
                    card.gramConcept ?: "${card.cardType}:${card.noteId}"
                )
            }
            .map { (key, objectiveCards) ->
                UnitGrammarObjectiveProgressRow(
                    band = key.first,
                    unit = key.second,
                    objective = key.third,
                    mastered = if (objectiveCards.any { it.reps >= 2 && it.consecutiveCorrect >= 2 }) 1 else 0
                )
            }
    override suspend fun getKnownVocabNoteIds(): List<Long> =
        cards.filter {
            it.queue == Queue.VOCAB &&
                !it.suspended &&
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
    override suspend fun suspendAllForNote(noteId: Long): Int {
        var changed = 0
        cards.replaceAll {
            if (it.noteId == noteId && !it.suspended) {
                changed += 1
                it.copy(suspended = true)
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

    override suspend fun insert(log: ReviewLog): Long {
        val id = nextId++
        logs += log.copy(id = id)
        return id
    }
    override suspend fun insertAll(logs: List<ReviewLog>) { logs.forEach { insert(it) } }
    override suspend fun getAll(): List<ReviewLog> = logs.sortedWith(compareBy<ReviewLog> { it.reviewDatetime }.thenBy { it.id })
    override suspend fun recentReviewTimes(limit: Int): List<Long> = recallLogs().sortedByDescending { it.reviewDatetime }.take(limit).map { it.reviewDatetime }
    override suspend fun passiveEvidenceCountSince(cardId: Long, dayStart: Long): Int =
        logs.count {
            it.cardId == cardId && it.reviewDatetime >= dayStart &&
                it.source in setOf(ReviewSource.READING, ReviewSource.LISTENING, ReviewSource.PRODUCTION, ReviewSource.CAPSTONE_CHOICE)
        }

    private fun recallLogs(): List<ReviewLog> = logs.filter {
        it.source == ReviewSource.SRS_REVIEW || it.source == ReviewSource.GRAMMAR_DRILL
    }

    override suspend fun recentDirectRatings(limit: Int): List<Rating> = recallLogs()
        .sortedWith(compareByDescending<ReviewLog> { it.reviewDatetime }.thenByDescending { it.id })
        .take(limit)
        .map { it.rating }

    override suspend fun recentDirectRatingsSince(since: Long, limit: Int): List<Rating> = recallLogs()
        .filter { it.reviewDatetime >= since }
        .sortedWith(compareByDescending<ReviewLog> { it.reviewDatetime }.thenByDescending { it.id })
        .take(limit)
        .map { it.rating }

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
    override suspend fun matureRetentionByCardType(since: Long): List<CardTypeRetention> =
        recallLogs()
            .filter { it.reviewDatetime >= since && (it.stateBefore == CardState.REVIEW || it.stateBefore == CardState.RELEARNING) && it.elapsedDays > 0 }
            .mapNotNull { log -> cards.cards.firstOrNull { it.id == log.cardId }?.cardType?.let { it to log } }
            .groupBy({ it.first }, { it.second })
            .map { (type, ls) -> CardTypeRetention(type, ls.size, ls.count { it.rating != Rating.AGAIN }) }
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
    override suspend fun deleteById(id: Long) {
        logs.removeAll { it.id == id }
    }
    override suspend fun reviewDayBuckets(tzOffset: Long, dayMillis: Long): List<Long> =
        recallLogs().map { (it.reviewDatetime + tzOffset) / dayMillis }.distinct().sortedDescending()
    override suspend fun recallActivityTimestamps(): List<Long> =
        recallLogs().map { it.reviewDatetime }.sorted()
    override suspend fun reviewCountsByDay(tzOffset: Long, dayMillis: Long, sinceDay: Long): List<ActivityDayCount> =
        recallLogs().map { (it.reviewDatetime + tzOffset) / dayMillis }
            .filter { it >= sinceDay }
            .groupingBy { it }
            .eachCount()
            .map { (day, count) -> ActivityDayCount(day, count) }
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
    override suspend fun countBySource(source: String): Int = texts.count { it.source == source }
    override suspend fun getAll(): List<ReaderText> = texts
    override suspend fun getById(id: Long): ReaderText? = texts.firstOrNull { it.id == id }
    override suspend fun deleteById(id: Long) { texts.removeAll { it.id == id } }
    override suspend fun updateSource(id: Long, source: String): Int {
        val index = texts.indexOfFirst { it.id == id }
        if (index < 0) return 0
        texts[index] = texts[index].copy(source = source)
        return 1
    }
}

internal class FakeReaderBookmarkDao : ReaderBookmarkDao {
    val bookmarks = mutableListOf<ReaderBookmark>()
    private var nextId = 1L
    override suspend fun insert(bookmark: ReaderBookmark): Long {
        val id = nextId++
        bookmarks += bookmark.copy(id = id)
        return id
    }
    override suspend fun getForText(readerTextId: Long): List<ReaderBookmark> = bookmarks.filter { it.readerTextId == readerTextId }.sortedBy { it.tokenIndex }
    override suspend fun getAt(readerTextId: Long, tokenIndex: Int): ReaderBookmark? = bookmarks.firstOrNull { it.readerTextId == readerTextId && it.tokenIndex == tokenIndex }
    override suspend fun deleteById(id: Long): Int = if (bookmarks.removeIf { it.id == id }) 1 else 0
    override suspend fun deleteForText(readerTextId: Long): Int {
        val before = bookmarks.size
        bookmarks.removeAll { it.readerTextId == readerTextId }
        return before - bookmarks.size
    }
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
    override suspend fun deletePristineForTexts(readerTextIds: List<Long>): Int {
        val before = schedules.size
        schedules.removeAll { it.readerTextId in readerTextIds && it.reps == 0 && it.lastCompleted == null }
        return before - schedules.size
    }
}

internal class FakeReaderEncounterDao : ReaderEncounterDao {
    val encounters = mutableListOf<ReaderEncounter>()
    override suspend fun insert(encounter: ReaderEncounter): Long {
        encounters.removeAll { it.readerTextId == encounter.readerTextId && it.noteId == encounter.noteId }
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
    override suspend fun activityTimestamps(): List<Long> = activities.map { it.completedAt }.sorted()
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
    override suspend fun recentByTypes(eventTypes: List<String>, limit: Int): List<TelemetryEvent> =
        events.filter { it.eventType in eventTypes }.sortedByDescending { it.timestamp }.take(limit)
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
    override suspend fun countByTypeAndSession(eventType: String, sessionId: String): Int =
        events.count { it.eventType == eventType && it.sessionId == sessionId }
}

/** In-memory stand-in for ContentDao (P4.3 tests). Only [framesForConcept]/[allFrames]
 * are meaningfully implemented — everything else here is unused by LearningRepository
 * paths that don't need morphology/corpus data. */
internal class FakeContentDao(
    private val framesByConcept: Map<String, List<ContentFrame>> = emptyMap(),
    private val chunksByLemma: Map<String, List<ContentCollocation>> = emptyMap(),
    private val legacySingleLetterRoots: List<String> = emptyList(),
    private val dialogues: List<ContentDialogue> = emptyList(),
    private val dialogueNodes: Map<String, List<ContentDialogueNode>> = emptyMap(),
    private val analysesBySurface: Map<String, List<MorphAnalysisRow>> = emptyMap()
) : ContentDao {
    override suspend fun candidatesForLemma(lemma: String, limit: Int) = emptyList<SentenceCandidate>()
    override suspend fun chunksForLemma(lemma: String, limit: Int): List<ContentCollocation> =
        chunksByLemma[lemma].orEmpty().take(limit)
    override suspend fun familyForLemma(lemma: String, limit: Int) = emptyList<ContentRootFamily>()
    override suspend fun singleLetterPrefixRoots(): List<String> = legacySingleLetterRoots
    override suspend fun emojiForLemma(lemma: String): String? = null
    override suspend fun neighborsForLemma(lemma: String, limit: Int) = emptyList<SemanticNeighbor>()
    override suspend fun metadata(key: String): String? = null
    override fun inflection(lemma: String, feats: String): ParadigmForm? = null
    override fun paradigm(lemma: String) = emptyList<ParadigmForm>()
    override fun analyses(surfaceNorm: String) = analysesBySurface[surfaceNorm].orEmpty()
    override suspend fun sentencesFor(unitMax: Int, bandMax: String, requiredLemma: String?, requiredFeat: String?, limit: Int) = emptyList<BankSentence>()
    override suspend fun sentencesContaining(chunk: String, limit: Int) = emptyList<ContentSentence>()
    override suspend fun framesForConcept(conceptId: String): List<ContentFrame> = framesByConcept[conceptId].orEmpty()
    override suspend fun allFrames(): List<ContentFrame> = framesByConcept.values.flatten()
    override suspend fun dialoguesFor(unitMax: Int) = dialogues.filter { it.unitMin <= unitMax }
    override suspend fun nodesForDialogue(dialogueId: String) = dialogueNodes[dialogueId].orEmpty()
}

internal class FakeConfusionEventDao : ConfusionEventDao {
    val events = mutableListOf<ConfusionEvent>()
    private var nextId = 1L
    override suspend fun insert(event: ConfusionEvent): Long {
        val id = nextId++
        events += event.copy(id = id)
        return id
    }
    override suspend fun insertAll(events: List<ConfusionEvent>): List<Long> = events.map { insert(it) }
    override suspend fun all(): List<ConfusionEvent> = events.sortedBy { it.at }
    override suspend fun topPairSince(since: Long): ConfusionPairCount? =
        events.filter { it.at >= since }
            .groupBy { Triple(it.expectedKey, it.producedKey, it.cardType) }
            .maxByOrNull { it.value.size }
            ?.let { (key, rows) -> ConfusionPairCount(key.first, key.second, key.third, rows.size) }
    override suspend fun deleteOlderThan(cutoff: Long): Int {
        val before = events.size
        events.removeAll { it.at < cutoff }
        return before - events.size
    }
}

internal class FakeCheckpointResultDao : CheckpointResultDao {
    val results = mutableListOf<CheckpointResult>()
    private var nextId = 1L
    override suspend fun insert(result: CheckpointResult): Long {
        val id = nextId++
        results += result.copy(id = id)
        return id
    }
    override suspend fun insertAll(results: List<CheckpointResult>): List<Long> = results.map { insert(it) }
    override suspend fun since(since: Long): List<CheckpointResult> = results.filter { it.at >= since }.sortedByDescending { it.at }
    override suspend fun recent(limit: Int): List<CheckpointResult> = results.sortedByDescending { it.at }.take(limit)
    override suspend fun all(): List<CheckpointResult> = results.sortedBy { it.at }
}

internal class FakeCurriculumStateDao : CurriculumStateDao {
    var state: CurriculumState? = null
    val reports = mutableListOf<CurriculumMigrationReport>()
    val exitTicketResults = mutableListOf<ExitTicketResult>()
    private var nextReportId = 1L
    private var nextExitTicketId = 1L
    override suspend fun current(): CurriculumState? = state
    override suspend fun upsert(value: CurriculumState) { state = value }
    override suspend fun insertReport(value: CurriculumMigrationReport): Long {
        val id = nextReportId++
        reports += value.copy(id = id)
        return id
    }
    override suspend fun pendingReport(): CurriculumMigrationReport? = reports.firstOrNull { !it.shown }
    override suspend fun markShown(id: Long) {
        reports.replaceAll { if (it.id == id) it.copy(shown = true) else it }
    }
    override suspend fun insertExitTicket(value: ExitTicketResult): Long {
        val id = nextExitTicketId++
        exitTicketResults += value.copy(id = id)
        return id
    }
    override suspend fun exitTickets(): List<ExitTicketResult> = exitTicketResults.sortedByDescending { it.completedAt }
}

/** Small in-memory adaptive-model DAO for snapshot integration tests. */
internal class FakeLearningModelDao : LearningModelDao {
    val difficulties = linkedMapOf<Long, ItemDifficulty>()
    val masteries = linkedMapOf<String, ConceptMastery>()
    val parameterRows = linkedMapOf<String, OptimizerParameter>()
    val skillRows = linkedMapOf<String, SkillRating>()
    val paceRows = linkedMapOf<Long, PaceLog>()
    val matchRows = mutableListOf<MatchHistory>()
    val banditPendingRows = linkedMapOf<Long, BanditPending>()
    val banditArmRows = linkedMapOf<String, BanditArmState>()
    val ghostRows = linkedMapOf<Long, GhostSnapshot>()
    var capacity: CapacityState? = null
    var willingness: WillingnessState? = null
    var rival: RivalState? = null
    private var nextMatchId = 1L

    override suspend fun upsertDifficulty(value: ItemDifficulty) { difficulties[value.cardId] = value }
    override suspend fun difficulty(cardId: Long): ItemDifficulty? = difficulties[cardId]
    override suspend fun difficulties(): List<ItemDifficulty> = difficulties.values.toList()
    override suspend fun difficultiesFor(cardIds: List<Long>): List<ItemDifficulty> = cardIds.mapNotNull(difficulties::get)
    override suspend fun deleteDifficulty(cardId: Long): Int = if (difficulties.remove(cardId) != null) 1 else 0
    override suspend fun deleteDifficulties(cardIds: List<Long>): Int =
        cardIds.count { difficulties.remove(it) != null }

    override suspend fun upsertMastery(value: ConceptMastery) { masteries[value.concept] = value }
    override suspend fun mastery(concept: String): ConceptMastery? = masteries[concept]
    override suspend fun masteries(): List<ConceptMastery> = masteries.values.toList()
    override suspend fun deleteMasteries(concepts: List<String>): Int = concepts.count { masteries.remove(it) != null }

    override suspend fun upsertParameter(value: OptimizerParameter) { parameterRows[value.key] = value }
    override suspend fun parameters(): List<OptimizerParameter> = parameterRows.values.toList()
    override suspend fun deleteParameters(keys: List<String>): Int = keys.count { parameterRows.remove(it) != null }

    override suspend fun upsertSkillRating(value: SkillRating) { skillRows[value.skill] = value }
    override suspend fun skillRatings(): List<SkillRating> = skillRows.values.toList()
    override suspend fun skillRating(skill: String): SkillRating? = skillRows[skill]
    override suspend fun deleteSkillRatings(skills: List<String>): Int = skills.count { skillRows.remove(it) != null }

    override suspend fun upsertCapacityState(value: CapacityState) { capacity = value }
    override suspend fun capacityState(): CapacityState? = capacity
    override suspend fun upsertWillingnessState(value: WillingnessState) { willingness = value }
    override suspend fun willingnessState(): WillingnessState? = willingness
    override suspend fun upsertRivalState(value: RivalState) { rival = value }
    override suspend fun rivalState(): RivalState? = rival

    override suspend fun insertGhostSnapshot(value: GhostSnapshot) { ghostRows[value.takenAt] = value }
    override suspend fun latestGhostSnapshot(): GhostSnapshot? = ghostRows.values.maxByOrNull { it.takenAt }
    override suspend fun ghostSnapshotAtOrBefore(cutoff: Long): GhostSnapshot? =
        ghostRows.values.filter { it.takenAt <= cutoff }.maxByOrNull { it.takenAt }
    override suspend fun ghostSnapshots(): List<GhostSnapshot> = ghostRows.values.toList()

    override suspend fun insertMatchHistory(value: MatchHistory): Long {
        val id = if (value.id == 0L) nextMatchId++ else value.id
        matchRows += value.copy(id = id)
        return id
    }
    override suspend fun matchHistory(limit: Int): List<MatchHistory> = matchRows.sortedByDescending { it.at }.take(limit)
    override suspend fun allMatchHistory(): List<MatchHistory> = matchRows.sortedBy { it.at }

    override suspend fun upsertPaceLog(value: PaceLog) { paceRows[value.at] = value }
    override suspend fun paceLogs(limit: Int): List<PaceLog> = paceRows.values.sortedByDescending { it.at }.take(limit)
    override suspend fun allPaceLogs(): List<PaceLog> = paceRows.values.sortedBy { it.at }
    override suspend fun paceLogCount(): Int = paceRows.size

    override suspend fun upsertBanditPending(value: BanditPending) { banditPendingRows[value.showAt] = value }
    override suspend fun pendingBanditCredits(itemId: Long): List<BanditPending> =
        banditPendingRows.values.filter { it.itemId == itemId }.sortedBy { it.showAt }
    override suspend fun allBanditPending(): List<BanditPending> = banditPendingRows.values.toList()
    override suspend fun deleteBanditPending(showAt: Long): Int = if (banditPendingRows.remove(showAt) != null) 1 else 0

    override suspend fun upsertBanditArmState(value: BanditArmState) { banditArmRows[value.action] = value }
    override suspend fun banditArmStates(): List<BanditArmState> = banditArmRows.values.toList()
}
