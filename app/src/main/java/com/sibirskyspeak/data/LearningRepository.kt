package com.sibirskyspeak.data

import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.LessonContent
import com.sibirskyspeak.review.buildPrompt
import com.sibirskyspeak.review.meaningLine
import com.sibirskyspeak.review.mnemonicLine
import com.sibirskyspeak.review.isEnglishAnswerCorrect
import com.sibirskyspeak.scheduler.FsrsScheduler
import com.sibirskyspeak.scheduler.Scheduler
import com.sibirskyspeak.learning.AbilitySkill
import com.sibirskyspeak.learning.CognateDetector
import com.sibirskyspeak.learning.Enrichment
import com.sibirskyspeak.learning.ExampleMiner
import com.sibirskyspeak.learning.MasteryModel
import com.sibirskyspeak.learning.BlueprintBuilder
import com.sibirskyspeak.learning.ContextualBandit
import com.sibirskyspeak.learning.FatigueModel
import com.sibirskyspeak.learning.LiveSessionState
import com.sibirskyspeak.learning.NextCardSelector
import com.sibirskyspeak.learning.SessionMode
import com.sibirskyspeak.learning.NarrowReadingGenerator
import com.sibirskyspeak.learning.CapacityBelief
import com.sibirskyspeak.learning.DoctrineAdvisor
import com.sibirskyspeak.learning.PaceController
import com.sibirskyspeak.learning.PaceInputs
import com.sibirskyspeak.learning.Pace
import com.sibirskyspeak.learning.ReturnContext
import com.sibirskyspeak.learning.WillingnessBelief
import com.sibirskyspeak.learning.WillingnessModel
import com.sibirskyspeak.learning.WillingnessSignals
import com.sibirskyspeak.learning.CapacityModel
import com.sibirskyspeak.learning.BanditCredit
import com.sibirskyspeak.learning.CausalFormatReward
import com.sibirskyspeak.learning.ColdStartModel
import com.sibirskyspeak.learning.CardPedagogy
import com.sibirskyspeak.learning.Gaussian
import com.sibirskyspeak.learning.MatchOutcome
import com.sibirskyspeak.learning.Rival
import com.sibirskyspeak.learning.RivalBelief
import com.sibirskyspeak.learning.PromotionSeries
import com.sibirskyspeak.learning.TrueSkill
import com.sibirskyspeak.learning.WorldModel
import com.sibirskyspeak.learning.SuccessCalibrationFitter
import com.sibirskyspeak.learning.EvidenceEvent
import com.sibirskyspeak.learning.EvidenceStrength
import com.sibirskyspeak.morph.MorphologyEngine
import com.sibirskyspeak.generation.FrameInventory
import com.sibirskyspeak.generation.FrameRealizer
import com.sibirskyspeak.transform.Transformer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

/** A captured pre-review snapshot, enough to roll one review back. */
private data class UndoSnapshot(
    val card: Card,
    val noteId: Long,
    val priorEncounterCount: Int
)

class LearningRepository(
    private val noteDao: NoteDao,
    private val cardDao: CardDao,
    private val reviewLogDao: ReviewLogDao,
    private val confusablePairDao: ConfusablePairDao,
    private val readerTextDao: ReaderTextDao,
    private val scheduler: Scheduler,
    private val bootstrapNotes: (suspend () -> String?)? = null,
    private val bootstrapReaderTexts: (suspend () -> String?)? = null,
    // Runs a block inside a single DB transaction. Seeding inserts ~10k notes
    // and their cards; without this each insert auto-commits, making first
    // launch slow. Defaults to running the block directly (used by tests).
    private val transactionRunner: (suspend (suspend () -> Unit) -> Unit)? = null,
    private val config: () -> LearningConfig = { LearningConfig() },
    // The learner's on-device-fitted forgetting-curve decay (FsrsScheduler.decayOf,
    // weight index 20). This is the single source of truth for retrievability shared
    // by the scheduler, the weight fitter, and interval-modifier recalibration; every
    // downstream consumer of retrievability/at-risk/pacing must read the SAME decay
    // or it silently reasons about a forgetting curve that doesn't match the one
    // actually driving this learner's due dates. Defaults to stock FSRS-6 decay.
    private val decayProvider: () -> Double = { FsrsScheduler.decayOf(FsrsScheduler.DEFAULT_WEIGHTS) },
    // Reads/writes a full-state JSON backup that lives outside the DB, used to
    // auto-recover study history if the database is ever wiped (destructive
    // migration, corruption, reinstall). Null in tests that don't exercise it.
    private val restoreBackup: (suspend () -> String?)? = null,
    private val writeBackup: (suspend (String) -> Unit)? = null,
    private val weeklyReportDao: WeeklyReportDao? = null,
    private val confusionEventDao: ConfusionEventDao? = null,
    private val checkpointResultDao: CheckpointResultDao? = null,
    private val telemetryDao: TelemetryDao? = null,
    private val readingScheduleDao: ReadingScheduleDao? = null,
    private val readerEncounterDao: ReaderEncounterDao? = null,
    private val readingActivityDao: ReadingActivityDao? = null,
    private val minedExampleDao: MinedExampleDao? = null,
    private val learningModelDao: LearningModelDao? = null,
    private val contentDao: ContentDao? = null,
    private val morphologyEngine: MorphologyEngine? = null,
    private val frameRealizer: FrameRealizer? = null,
    // Normalized-surface -> base-lemma map (deck_lemma.json), built offline with the
    // same pymorphy lemmatizer that indexed the corpus. Lets the miner resolve a note's
    // real base lemma when note.lemma is a namespaced/inflected pseudo-lemma (e.g.
    // "tb_нашему") that the corpus base-form index can't match. Null in tests.
    private val corpusLemmaProvider: (suspend () -> String?)? = null,
    // Dispatcher for the repository's CPU-bound work (surface-form indexing, reader
    // tokenization). Injectable so tests can pass a deterministic test dispatcher
    // instead of the real Default pool, which would escape the test scheduler.
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    // Holds the most recent review so the user can undo a misclick or typo. Kept
    // in memory only: undo is a within-session affordance, not durable history.
    @Volatile private var lastUndo: UndoSnapshot? = null

    fun observeNotes(): Flow<List<Note>> = noteDao.observeAll()

    // --- In-memory caches ---------------------------------------------------
    // Generating surface forms for the full note set (tens of thousands of
    // rows) is by far the most expensive operation in the app, and it used to
    // run several times per word tap. Cache the form index and rebuild it only
    // when notes are added. Status/encounter/card changes keep the cached forms
    // and just recompute the cheap "known id" set. NOTE: the cached Note objects
    // may hold stale status/encounter, so any *write* must re-read the row first.
    @Volatile private var notesCache: List<Note>? = null
    @Volatile private var formIndexCache: Map<String, Note>? = null
    @Volatile private var knownIdsCache: Set<Long>? = null
    @Volatile private var frameInventoryCache: FrameInventory? = null
    private val modelTuningMutex = Mutex()
    @Volatile private var lastGraduationReviewCount: Int? = null
    @Volatile private var accuracyCacheReviewCount: Int? = null
    @Volatile private var accuracyCache: List<CategoryKey>? = null
    private val enrichmentCache = mutableMapOf<Long, Enrichment>()

    // Voluntary "extra credit" new cards granted for today, on top of the daily new-card
    // cap, for when the learner wants to push further. In-memory and day-scoped: it
    // resets at the local day rollover (and on app restart), which is the intent.
    @Volatile private var extraCreditDay: Long = -1L
    @Volatile private var extraCreditCount: Int = 0

    /** Add a bounded extra batch of new cards for today, beyond the daily cap. */
    fun grantExtraCredit(amount: Int = EXTRA_CREDIT_BATCH, now: Long = System.currentTimeMillis()): Int {
        val today = startOfLocalDay(now)
        if (extraCreditDay != today) {
            extraCreditDay = today
            extraCreditCount = 0
        }
        val remaining = (EXTRA_CREDIT_DAILY_LIMIT - extraCreditCount).coerceAtLeast(0)
        val granted = minOf(amount.coerceAtLeast(0), remaining)
        extraCreditCount += granted
        return granted
    }

    private fun extraCreditToday(now: Long): Int =
        if (extraCreditDay == startOfLocalDay(now)) extraCreditCount else 0

    private fun invalidateNoteStructure() {
        notesCache = null
        formIndexCache = null
        knownIdsCache = null
        frameInventoryCache = null
        lastGraduationReviewCount = null
        accuracyCacheReviewCount = null
        accuracyCache = null
    }

    private fun invalidateNoteState() {
        notesCache = null
        knownIdsCache = null
    }

    private fun invalidateNoteContent() {
        notesCache = null
        // This index stores whole Note values, so edits to translations/examples
        // require rebuilding it even though the surface-form keys did not change.
        formIndexCache = null
    }

    private suspend fun allNotesCached(): List<Note> =
        notesCache ?: noteDao.getAll().also { notesCache = it }

    /** Known-inventory fillers for FrameRealizer (P4.3), pre-partitioned by POS. */
    private suspend fun frameInventory(): FrameInventory = frameInventoryCache ?: run {
        val notes = allNotesCached().filter { it.tier == 0 }
        FrameInventory(
            nouns = notes.filter { it.partOfSpeech == "noun" },
            verbs = notes.filter { it.partOfSpeech == "verb" },
            adjectives = notes.filter { it.partOfSpeech == "adjective" }
        ).also { frameInventoryCache = it }
    }

    private suspend fun formIndex(): Map<String, Note> =
        formIndexCache ?: run {
            // Surface-form generation over the full note set is the most expensive
            // CPU operation in the app; keep it off the main thread on a cold cache.
            val notes = allNotesCached()
            withContext(computeDispatcher) { buildFormIndex(notes) }.also { formIndexCache = it }
        }

    private suspend fun knownNoteIds(): Set<Long> =
        knownIdsCache ?: computeKnownNoteIds(allNotesCached()).also { knownIdsCache = it }

    private suspend fun runInTransaction(block: suspend () -> Unit) {
        val runner = transactionRunner
        if (runner != null) runner(block) else block()
    }

    private suspend fun computeKnownNoteIds(notes: List<Note>): Set<Long> {
        val cardKnown = cardDao.getKnownVocabNoteIds()
        val readerKnown = readerEncounterDao?.noteIdsWithMinimumEncounters(READER_KNOWN_ENCOUNTERS).orEmpty()
        val statusKnown = notes.filter { note ->
            note.status == WordStatus.KNOWN ||
                note.status == WordStatus.IGNORED
        }.map { it.id }
        return (cardKnown + readerKnown + statusKnown).toHashSet()
    }

    suspend fun seedIfEmpty(runMaintenance: Boolean = true) {
        if (noteDao.count() > 0) {
            if (runMaintenance) performLaunchMaintenance()
            return
        }
        val runner = transactionRunner ?: { block -> block() }
        // Safety net first: if a local backup exists, the empty DB is almost
        // certainly the result of a wipe (destructive migration / reinstall), not a
        // first run. Restore the user's history instead of re-seeding bootstrap data.
        val backup = restoreBackup?.invoke()?.takeIf { it.isNotBlank() }
        if (backup != null) {
            var restored = 0
            runner { restored = importJsonLines(backup) }
            if (restored > 0) {
                // Add newly shipped material after restoring the exact user snapshot,
                // then repair any legacy duplicates/relationships in one pass.
                runCatching { syncBootstrapReaderTexts() }
                runCatching { runner { seedConfusablePairs() } }
                runCatching { performDataMaintenance() }
                return
            }
        }
        var imported = 0
        runner {
            imported = bootstrapNotes?.invoke()?.takeIf { it.isNotBlank() }?.let { importJsonLines(it) } ?: 0
            bootstrapReaderTexts?.invoke()?.takeIf { it.isNotBlank() }?.let { importReaderTextsJsonLines(it) }
        }
        if (imported > 0) {
            runCatching { runner { seedConfusablePairs() } }
            runCatching { performDataMaintenance() }
            runCatching { mineExampleGaps(limit = 96) }
            return
        }

        addNote(
            Note(
                russian = "молоко́",
                translation = "milk",
                partOfSpeech = "noun",
                lemma = "молоко",
                declensionJson = """{"NOM_SG":"молоко","GEN_SG":"молока","DAT_SG":"молоку","ACC_SG":"молоко","INS_SG":"молоком","PREP_SG":"молоке"}""",
                gender = "N",
                exampleSentence = "Я пью молоко́.",
                exampleTranslation = "I drink milk.",
                generalFreqRank = 795,
                domainFreqRank = 1800
            )
        )
        addNote(
            Note(
                russian = "войска́",
                translation = "troops",
                partOfSpeech = "noun",
                lemma = "войска",
                declensionJson = """{"NOM_PL":"войска","GEN_PL":"войск","DAT_PL":"войскам","ACC_PL":"войска","INS_PL":"войсками","PREP_PL":"войсках"}""",
                gender = "PL",
                exampleSentence = "Войска́ стоят у границы.",
                exampleTranslation = "Troops are stationed near the border.",
                exampleSentence2 = "Здесь нет войск.",
                exampleTranslation2 = "There are no troops here.",
                generalFreqRank = 2600,
                domainFreqRank = 120
            )
        )
        val pisat = addNote(
            Note(
                russian = "писа́ть",
                translation = "to write",
                partOfSpeech = "verb",
                lemma = "писать",
                aspect = "IPF",
                aktionsart = "activity",
                aktionsartConfidence = "high",
                exampleSentence = "Вчера я писал письмо.",
                exampleTranslation = "Yesterday I was writing a letter.",
                generalFreqRank = 380,
                domainFreqRank = 900
            )
        )
        val napisat = addNote(
            Note(
                russian = "написа́ть",
                translation = "to write, to complete writing",
                partOfSpeech = "verb",
                lemma = "написать",
                aspect = "PF",
                aktionsart = "accomplishment",
                aktionsartConfidence = "high",
                exampleSentence = "Вчера я написал письмо.",
                exampleTranslation = "Yesterday I wrote a letter.",
                generalFreqRank = 620,
                domainFreqRank = 950
            )
        )

        noteDao.update((noteDao.getById(pisat) ?: return).copy(aspectPartner = napisat))
        noteDao.update((noteDao.getById(napisat) ?: return).copy(aspectPartner = pisat))
        confusablePairDao.insert(ConfusablePair(firstNoteId = pisat, secondNoteId = napisat, reason = "aspect_partner"))
        insertMissingAspectCards(pisat)
        insertMissingAspectCards(napisat)

        if (readerTextDao.count() == 0) {
            readerTextDao.insert(
                ReaderText(
                    title = "Security Brief",
                    body = "Войска стоят у границы. Вчера офицер написал письмо.",
                    source = "seed"
                )
            )
        }
    }

    /** Repairs and additive content syncs are useful, but none is required to draw
     * the first screen. Keep this work off the UI/startup critical path. */
    suspend fun performLaunchMaintenance() = withContext(computeDispatcher) {
        runCatching { cardDao.suspendDeprecatedAspectCueCards() }
        runCatching { syncPedagogicalFacets() }
        syncMissingConceptDrillCards()
        runCatching { syncMissingConceptApplyCards() }
        runCatching { syncMissingNovelProduceCards() }
        runCatching { syncMissingChunkCards() }
        runCatching { syncMissingTransformCards() }
        runCatching { syncMissingSpeakSentenceCards() }
        runCatching { repairConcatenatedExamples() }
        runCatching { syncBootstrapTextbookNotes() }
        runCatching { retireRejectedBootstrapNotes() }
        runCatching { syncBootstrapReaderTexts() }
        runCatching { performDataMaintenance() }
        runCatching { mineExampleGaps(limit = 48) }
    }

    /** Add newly engineered facets to an existing installation without resetting or
     * rewriting any learner state. Scope is the active authored course; the large
     * reading matrix intentionally remains recognition-oriented. */
    private suspend fun syncPedagogicalFacets() {
        val notes = allNotesCached().filter { it.tier == 0 }
        if (notes.isEmpty()) return
        val existing = notes.map { it.id }.chunked(500)
            .flatMap { ids -> cardDao.getCardsForNotes(ids) }
            .groupBy { it.noteId }
        val missing = buildList {
            notes.forEach { note ->
                val present = existing[note.id].orEmpty().mapTo(HashSet()) { it.variantKey() }
                CardFactory.cardsFor(note).forEach { candidate ->
                    if (candidate.variantKey() !in present) add(candidate)
                }
            }
        }
        if (missing.isNotEmpty()) cardDao.insertAll(missing)
    }

    private fun Card.variantKey(): List<String?> = listOf(
        cardType.name, queue.name, gramCase, gramGender, gramNumber, gramContextCue, gramConcept
    )

    suspend fun addNote(note: Note): Long {
        // Repair "Русский - English" example concatenations on the way in so future
        // imports (and backup restores of legacy data) never reintroduce the leak.
        val clean = note.withSplitExamples()
        var noteId = 0L
        runInTransaction {
            noteId = noteDao.insert(clean)
            cardDao.insertAll(CardFactory.cardsFor(clean.copy(id = noteId)))
        }
        invalidateNoteStructure()
        return noteId
    }

    suspend fun importJsonLines(jsonLines: String): Int {
        val pendingPartners = mutableListOf<Pair<Long, String>>()
        var imported = 0
        val telemetryPayloads = mutableListOf<JSONObject>()
        val restoredLogs = mutableListOf<ReviewLog>()
        val readerPayloads = mutableListOf<JSONObject>()
        val pairPayloads = mutableListOf<JSONObject>()
        val modelPayloads = mutableListOf<JSONObject>()
        runInTransaction {
            jsonLines.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val json = JSONObject(line)
                    // Full-state backups interleave telemetry rows (marked "_telemetry")
                    // among the note lines; route those to TelemetryDao instead of
                    // parsing them as a Note (which lacks the required fields).
                    if (json.optBoolean("_telemetry", false)) {
                        telemetryPayloads += json
                        return@forEach
                    }
                    if (json.optBoolean("_readerText", false)) {
                        readerPayloads += json
                        return@forEach
                    }
                    if (json.optBoolean("_confusablePair", false)) {
                        pairPayloads += json
                        return@forEach
                    }
                    if (json.optBoolean("_model", false)) { modelPayloads += json; return@forEach }
                    val partnerLemma = json.optString("aspectPartner").takeIf { it.isNotBlank() && it != "null" }
                    val note = Note(
                        russian = json.getString("russian"),
                        lemma = json.getString("lemma"),
                        translation = json.getString("translation"),
                        partOfSpeech = json.optString("pos", json.optString("partOfSpeech")),
                        aspect = json.optCleanString("aspect"),
                        aktionsart = json.optCleanString("aktionsart"),
                        aktionsartConfidence = json.optCleanString("aktionsartConfidence"),
                        gender = json.optCleanString("gender"),
                        declensionJson = json.optCleanString("declensionJson"),
                        generalFreqRank = json.optIntOrNull("generalFreqRank"),
                        domainFreqRank = json.optIntOrNull("domainFreqRank"),
                        encounterCount = json.optInt("encounterCount", 0),
                        exampleSentence = json.optCleanString("exampleSentence"),
                        exampleTranslation = json.optCleanString("exampleTranslation"),
                        exampleSentence2 = json.optCleanString("exampleSentence2"),
                        exampleTranslation2 = json.optCleanString("exampleTranslation2"),
                        exampleSentence3 = json.optCleanString("exampleSentence3"),
                        exampleTranslation3 = json.optCleanString("exampleTranslation3"),
                        audioPath = json.optCleanString("audioPath"),
                        tags = json.optString("tags", ""),
                        status = json.optCleanString("status")?.let(WordStatus::valueOf) ?: WordStatus.NEW,
                        tier = json.optInt("tier", 1),
                        unit = json.optIntOrNull("unit"),
                        conceptId = json.optCleanString("conceptId"),
                        cefrLevel = json.optCleanString("cefrLevel"),
                        mnemonic = json.optCleanString("mnemonic"),
                        secondSense = json.optCleanString("secondSense"),
                        secondSenseExample = json.optCleanString("secondSenseExample"),
                        secondSenseExampleTranslation = json.optCleanString("secondSenseExampleTranslation")
                    )
                    val noteId = addNote(note)
                    if (partnerLemma != null) pendingPartners += noteId to partnerLemma
                    // Restore SRS state if this is a full-state backup.
                    val cardsJson = if (json.has("_cards")) json.optJSONArray("_cards") else null
                    if (cardsJson != null) {
                        val freshByVariant = cardDao.getCardsForNote(noteId).associateBy { it.srsVariantKey() }.toMutableMap()
                        val updates = mutableListOf<Card>()
                        repeat(cardsJson.length()) { ci ->
                                val cj = cardsJson.getJSONObject(ci)
                                val existing = freshByVariant[cj.srsVariantKey()]
                                val restored = (existing ?: Card(
                                    noteId = noteId,
                                    cardType = CardType.valueOf(cj.getString("cardType")),
                                    queue = Queue.valueOf(cj.getString("queue")),
                                    gramCase = cj.optCleanString("gramCase"),
                                    gramGender = cj.optCleanString("gramGender"),
                                    gramNumber = cj.optCleanString("gramNumber"),
                                    gramContextCue = cj.optCleanString("gramContextCue"),
                                    gramConcept = cj.optCleanString("gramConcept")
                                )).copy(
                                    state = CardState.valueOf(cj.getString("state")),
                                    stability = cj.getDouble("stability"),
                                    difficulty = cj.getDouble("difficulty"),
                                    elapsedDays = cj.getInt("elapsedDays"),
                                    scheduledDays = cj.getInt("scheduledDays"),
                                    reps = cj.getInt("reps"),
                                    lapses = cj.getInt("lapses"),
                                    due = cj.getLong("due"),
                                    lastReview = if (cj.isNull("lastReview")) null else cj.getLong("lastReview"),
                                    consecutiveCorrect = cj.optInt("consecutiveCorrect", 0),
                                    suspended = cj.optBoolean("suspended", false)
                                )
                                val restoredId = if (existing == null) cardDao.insert(restored) else {
                                    updates += restored
                                    existing.id
                                }
                                cj.optJSONArray("_reviews")?.let { reviews ->
                                    repeat(reviews.length()) { ri ->
                                        val rj = reviews.getJSONObject(ri)
                                        restoredLogs += ReviewLog(
                                            cardId = restoredId,
                                            reviewDatetime = rj.getLong("reviewDatetime"),
                                            rating = Rating.valueOf(rj.getString("rating")),
                                            stateBefore = CardState.valueOf(rj.getString("stateBefore")),
                                            scheduledDays = rj.getInt("scheduledDays"),
                                            elapsedDays = rj.getInt("elapsedDays"),
                                            source = ReviewSource.valueOf(rj.getString("source")),
                                            stabilityBefore = rj.optDouble("stabilityBefore", 0.0),
                                            evidenceStrength = rj.optCleanString("evidenceStrength")?.let(EvidenceStrength::valueOf)
                                        )
                                    }
                                }
                        }
                        if (updates.isNotEmpty()) cardDao.updateAll(updates)
                    }
                    imported += 1
                }

            val pairKeys = confusablePairDao.getAll().mapTo(HashSet()) {
                Triple(minOf(it.firstNoteId, it.secondNoteId), maxOf(it.firstNoteId, it.secondNoteId), it.reason)
            }
            pendingPartners.forEach { (noteId, partnerLemma) ->
                val note = noteDao.getById(noteId) ?: return@forEach
                val partner = noteDao.getByLemma(partnerLemma) ?: return@forEach
                noteDao.update(note.copy(aspectPartner = partner.id))
                val key = Triple(minOf(note.id, partner.id), maxOf(note.id, partner.id), "aspect_partner")
                if (pairKeys.add(key)) confusablePairDao.insert(ConfusablePair(firstNoteId = note.id, secondNoteId = partner.id, reason = "aspect_partner"))
                // Re-run cardsFor so BI/no_aspect_pair guards apply and every
                // aspect context cue is added.
                insertMissingAspectCards(note.id)
            }
            pairPayloads.forEach { payload ->
                val first = noteDao.getByLemma(payload.getString("firstLemma")) ?: return@forEach
                val second = noteDao.getByLemma(payload.getString("secondLemma")) ?: return@forEach
                val reason = payload.getString("reason")
                val key = Triple(minOf(first.id, second.id), maxOf(first.id, second.id), reason)
                if (pairKeys.add(key)) confusablePairDao.insert(ConfusablePair(firstNoteId = first.id, secondNoteId = second.id, reason = reason))
            }
            if (restoredLogs.isNotEmpty()) reviewLogDao.insertAll(restoredLogs)
            readerPayloads.forEach { payload ->
                val existing = readerTextDao.getAll().firstOrNull {
                    it.title == payload.getString("title") && it.body == payload.getString("body")
                }
                val textId = existing?.id ?: addReaderText(
                    payload.getString("title"),
                    payload.getString("body"),
                    payload.optString("source", "backup")
                )
                if (readingScheduleDao?.get(textId) == null) readingScheduleDao?.insert(ReadingSchedule(textId))
                payload.optJSONObject("schedule")?.let { sj ->
                    readingScheduleDao?.update(ReadingSchedule(
                        readerTextId = textId,
                        due = sj.getLong("due"),
                        intervalDays = sj.getInt("intervalDays"),
                        reps = sj.getInt("reps"),
                        lapses = sj.getInt("lapses"),
                        lastCompleted = sj.optLongOrNull("lastCompleted")
                    ))
                }
                val encounters = payload.optJSONArray("encounterLemmas")
                if (encounters != null) {
                    val rows = buildList {
                        repeat(encounters.length()) { index ->
                            noteDao.getByLemma(encounters.getString(index))?.let { note ->
                                add(ReaderEncounter(textId, note.id))
                            }
                        }
                    }
                    if (rows.isNotEmpty()) readerEncounterDao?.insertAll(rows)
                }
                payload.optJSONArray("activities")?.let { activities ->
                    val rows = buildList {
                        repeat(activities.length()) { index ->
                            val activity = activities.getJSONObject(index)
                            add(ReadingActivity(
                                readerTextId = textId,
                                completedAt = activity.getLong("completedAt"),
                                mistakes = activity.optInt("mistakes", 0),
                                intervalDays = activity.optInt("intervalDays", 1)
                            ))
                        }
                    }
                    if (rows.isNotEmpty()) readingActivityDao?.insertAll(rows)
                }
            }
            if (telemetryPayloads.isNotEmpty()) {
                val events = telemetryPayloads.map { json ->
                    val restoredNote = json.optCleanString("_noteLemma")?.let { noteDao.getByLemma(it) }
                    val restoredCardId = restoredNote?.let { note ->
                        val key = json.optCleanString("_cardVariantKey")
                        if (key == null) null else cardDao.getCardsForNote(note.id).firstOrNull { it.srsVariantKey() == key }?.id
                    }
                    TelemetryEvent(
                        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        eventType = json.getString("eventType"),
                        sessionId = json.optCleanString("sessionId"),
                        cardId = restoredCardId,
                        noteId = restoredNote?.id,
                        cardType = json.optCleanString("cardType"),
                        queue = json.optCleanString("queue"),
                        answerMode = json.optCleanString("answerMode"),
                        rating = json.optCleanString("rating"),
                        answerMatch = json.optCleanString("answerMatch"),
                        responseMs = json.optLongOrNull("responseMs"),
                        wasRevealed = json.optBoolean("wasRevealed", false),
                        typedLength = json.optInt("typedLength", 0),
                        queueReason = json.optCleanString("queueReason"),
                        sessionRemaining = json.optIntOrNull("sessionRemaining"),
                        dueCount = json.optIntOrNull("dueCount"),
                        newCardLimit = json.optIntOrNull("newCardLimit"),
                        metadataJson = json.optString("metadataJson", "{}")
                    )
                }
                telemetryDao?.insertAll(events)
            }
            val modelDao = learningModelDao
            if (modelDao != null) modelPayloads.forEach { j ->
                when (j.getString("kind")) {
                    "difficulty" -> {
                        val note = noteDao.getByLemma(j.getString("noteLemma")) ?: return@forEach
                        val card = cardDao.getCardsForNote(note.id).firstOrNull { it.srsVariantKey() == j.getString("cardVariant") } ?: return@forEach
                        modelDao.upsertDifficulty(ItemDifficulty(card.id, j.getDouble("elo"), j.optDouble("sigma", TrueSkill.SIGMA0), j.getInt("observations"), j.getLong("updatedAt")))
                    }
                    "mastery" -> modelDao.upsertMastery(ConceptMastery(j.getString("concept"), j.getDouble("probability"), j.getInt("observations"), j.getLong("updatedAt")))
                    "parameter" -> modelDao.upsertParameter(OptimizerParameter(j.getString("key"), j.getDouble("value"), j.getInt("observations"), j.getLong("updatedAt")))
                    "skill" -> modelDao.upsertSkillRating(SkillRating(j.getString("skill"), j.getDouble("muGlobalShare"), j.getDouble("mu"), j.getDouble("sigma"), j.getInt("observations"), j.getLong("updatedAt")))
                    "capacity" -> modelDao.upsertCapacityState(CapacityState(mu=j.getDouble("mu"), sigma=j.getDouble("sigma"), updatedAt=j.getLong("updatedAt")))
                    "willingness" -> modelDao.upsertWillingnessState(WillingnessState(habit=j.getDouble("habit"), coeffsJson=j.getString("coeffsJson"), updatedAt=j.getLong("updatedAt")))
                    "rival" -> modelDao.upsertRivalState(RivalState(mu=j.getDouble("mu"), sigma=j.getDouble("sigma"), handicap=j.getDouble("handicap"), winStreak=j.getInt("winStreak"), persona=j.getString("persona"), updatedAt=j.getLong("updatedAt")))
                    "ghost" -> modelDao.insertGhostSnapshot(GhostSnapshot(j.getLong("takenAt"),j.getDouble("muGlobal"),j.getDouble("sigma")))
                    "pace" -> modelDao.upsertPaceLog(PaceLog(j.getLong("at"),j.getDouble("T"),j.getInt("N"),j.getDouble("rho"),j.getDouble("debtRatio"),j.getDouble("pReturn"),j.getString("doctrine"),j.getString("modeChosen")))
                    "banditArm" -> modelDao.upsertBanditArmState(BanditArmState(j.getString("action"),j.getString("rewardJson"),j.getString("precisionJson"),j.getInt("pulls"),j.getLong("updatedAt")))
                }
            }
        }
        invalidateNoteStructure()
        return imported
    }

    private suspend fun insertMissingAspectCards(noteId: Long) {
        val existingAspectCues = cardDao.getCardsForNote(noteId)
            .filter { it.cardType == CardType.ASPECT_SELECT }
            .mapNotNull { it.gramContextCue }
            .toSet()
        val missing = CardFactory.cardsFor(noteDao.getById(noteId) ?: return)
            .filter { it.cardType == CardType.ASPECT_SELECT && it.gramContextCue !in existingAspectCues }
        if (missing.isNotEmpty()) cardDao.insertAll(missing)
    }

    /**
     * Auto-detect confusable word pairs among the curated course (tier 0) so the SRS
     * surfaces them together for discrimination practice — the most effective fix for
     * "I always mix these two up." Two kinds: spelling-confusable (one edit apart, e.g.
     * дом/дым) and meaning-confusable (same English gloss, e.g. большой/крупный). Each
     * note gets at most one auto-partner to avoid clutter; existing pairs (aspect
     * partners) are never duplicated. Runs once, at first seed.
     */
    private suspend fun seedConfusablePairs() {
        val core = noteDao.getAll().filter {
            it.tier == 0 &&
                !it.partOfSpeech.equals("lesson", ignoreCase = true) &&
                it.translation != "lookup pending" &&
                it.russian.isNotBlank()
        }
        if (core.size < 2) return
        val existing = confusablePairDao.getAll().map { setOf(it.firstNoteId, it.secondNoteId) }.toHashSet()
        val autoPartnered = HashSet<Long>()
        val pairs = mutableListOf<ConfusablePair>()
        val cap = 300

        // Spelling-confusable: normalized forms exactly one edit apart.
        val normalized = core.map { it to normalizeToken(it.russian) }.filter { it.second.length >= 3 }
        for (x in normalized.indices) {
            if (pairs.size >= cap) break
            val (a, sa) = normalized[x]
            if (a.id in autoPartnered) continue
            for (y in x + 1 until normalized.size) {
                val (b, sb) = normalized[y]
                if (b.id in autoPartnered) continue
                if (sa.length - sb.length !in -1..1) continue
                if (withinOneEdit(sa, sb)) {
                    val key = setOf(a.id, b.id)
                    if (key !in existing) {
                        pairs += ConfusablePair(firstNoteId = a.id, secondNoteId = b.id, reason = "confusable_spelling")
                        existing += key; autoPartnered += a.id; autoPartnered += b.id
                        break
                    }
                }
            }
        }

        // Meaning-confusable: notes sharing the same primary English gloss.
        core.groupBy { it.translation.trim().lowercase(Locale.ROOT).substringBefore(',').substringBefore(';').trim() }
            .filterKeys { it.isNotBlank() }
            .values.forEach { group ->
                val avail = group.filter { it.id !in autoPartnered }
                var i = 0
                while (i + 1 < avail.size && pairs.size < cap) {
                    val a = avail[i]; val b = avail[i + 1]
                    val key = setOf(a.id, b.id)
                    if (key !in existing) {
                        pairs += ConfusablePair(firstNoteId = a.id, secondNoteId = b.id, reason = "confusable_meaning")
                        existing += key; autoPartnered += a.id; autoPartnered += b.id
                    }
                    i += 2
                }
            }

        pairs.forEach { confusablePairDao.insert(it) }
    }

    /** True if [a] and [b] differ by exactly one insertion, deletion, or substitution. */
    private fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return false
        val la = a.length; val lb = b.length
        if (la - lb !in -1..1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < la && j < lb) {
            if (a[i] == b[j]) { i++; j++ } else {
                if (++edits > 1) return false
                when {
                    la > lb -> i++
                    la < lb -> j++
                    else -> { i++; j++ }
                }
            }
        }
        if (i < la || j < lb) edits++
        return edits <= 1
    }

    private suspend fun syncMissingConceptDrillCards() {
        val missing = allNotesCached()
            .filter { it.partOfSpeech.equals("lesson", ignoreCase = true) }
            .flatMap { note ->
                val existingCues = cardDao.getCardsForNote(note.id)
                    .filter { it.cardType == CardType.CONCEPT_DRILL }
                    .mapNotNull { it.gramContextCue }
                    .toSet()
                ConceptDrills.forConcept(note.conceptId)
                    .filter { it.id !in existingCues }
                    .map { drill ->
                        Card(
                            noteId = note.id,
                            cardType = CardType.CONCEPT_DRILL,
                            queue = Queue.GRAMMAR,
                            due = 0L,
                            gramContextCue = drill.id,
                            gramConcept = drill.conceptId
                        )
                    }
            }
        if (missing.isNotEmpty()) cardDao.insertAll(missing)
    }

    /**
     * One CONCEPT_APPLY card per grammar concept that the content pipeline has
     * shipped frames for (tools/preprocess/frames.json), attached to that concept's
     * LESSON note so teach-before-test gating applies unchanged. Additive and
     * idempotent, like [syncMissingConceptDrillCards] — safe to call on every launch.
     */
    private suspend fun syncMissingConceptApplyCards() {
        val dao = contentDao ?: return
        val lessonNotesByConcept = allNotesCached()
            .filter { it.partOfSpeech.equals("lesson", ignoreCase = true) && it.conceptId != null }
            .associateBy { it.conceptId }
        val missing = mutableListOf<Card>()
        for (concept in GrammarConcepts.ALL) {
            val note = lessonNotesByConcept[concept.id] ?: continue
            val hasCard = cardDao.getCardsForNote(note.id).any { it.cardType == CardType.CONCEPT_APPLY }
            if (hasCard) continue
            if (dao.framesForConcept(concept.id).isEmpty()) continue
            missing += Card(noteId = note.id, cardType = CardType.CONCEPT_APPLY, queue = Queue.GRAMMAR, due = 0L, gramConcept = concept.id)
        }
        if (missing.isNotEmpty()) cardDao.insertAll(missing)
    }

    /**
     * One NOVEL_PRODUCE card per concept (P4.4 L3), gated on that concept's
     * CONCEPT_APPLY already having a couple of reps — the ladder's payoff comes
     * after guided application has started, not instead of it. Additive and
     * idempotent, like the other concept-card syncs.
     */
    private suspend fun syncMissingNovelProduceCards() {
        val dao = contentDao ?: return
        val lessonNotesByConcept = allNotesCached()
            .filter { it.partOfSpeech.equals("lesson", ignoreCase = true) && it.conceptId != null }
            .associateBy { it.conceptId }
        val missing = mutableListOf<Card>()
        for (concept in GrammarConcepts.ALL) {
            val note = lessonNotesByConcept[concept.id] ?: continue
            val cards = cardDao.getCardsForNote(note.id)
            if (cards.any { it.cardType == CardType.NOVEL_PRODUCE }) continue
            val applyCard = cards.firstOrNull { it.cardType == CardType.CONCEPT_APPLY } ?: continue
            if (applyCard.reps < 2) continue
            if (dao.framesForConcept(concept.id).isEmpty()) continue
            missing += Card(noteId = note.id, cardType = CardType.NOVEL_PRODUCE, queue = Queue.GRAMMAR, due = 0L, gramConcept = concept.id)
        }
        if (missing.isNotEmpty()) cardDao.insertAll(missing)
    }

    /**
     * Mints a small batch of CHUNK notes/cards (P4.4 L1) for tier-0 vocabulary whose
     * recognition has matured, from the on-device collocation table — real
     * collocations ("на диване", not just "диван"), no authored content required
     * ("index and compose"). Idempotent and additive, like the concept-card syncs;
     * bounded per call so a first sync of a mature deck can't mint thousands at once.
     */
    private suspend fun syncMissingChunkCards(limit: Int = 24) {
        val dao = contentDao ?: return
        val notes = allNotesCached()
        val alreadyChunked = notes.mapNotNull { it.chunkParentNoteId }.toHashSet()
        val candidates = notes.filter {
            it.tier == 0 && it.chunkParentNoteId == null && it.id !in alreadyChunked &&
                it.partOfSpeech in setOf("noun", "verb", "adjective")
        }
        if (candidates.isEmpty()) return
        val recognitionByNote = cardDao.getCardsForNotes(candidates.map { it.id })
            .filter { it.cardType == CardType.RU_TO_MEANING }
            .associateBy { it.noteId }
        var minted = 0
        for (parent in candidates) {
            if (minted >= limit) break
            val recognition = recognitionByNote[parent.id] ?: continue
            val mature = recognition.reps >= 3 && recognition.consecutiveCorrect >= 2 &&
                recognition.state in setOf(CardState.REVIEW, CardState.GRADUATED)
            if (!mature) continue
            for (chunk in dao.chunksForLemma(parent.lemma, limit = 2)) {
                if (minted >= limit) break
                val noteId = noteDao.insert(
                    Note(
                        russian = chunk.chunk,
                        translation = "",
                        partOfSpeech = "chunk",
                        lemma = chunk.chunk,
                        tier = parent.tier,
                        chunkParentNoteId = parent.id
                    )
                )
                cardDao.insert(Card(noteId = noteId, cardType = CardType.CHUNK, queue = Queue.VOCAB, due = 0L))
                minted++
            }
        }
        if (minted > 0) invalidateNoteStructure()
    }

    /**
     * Mints a TRANSFORM card (P4.4 L2) directly onto tier-0 verb notes whose
     * recognition has matured — the card's actual sentence and rewrite are computed
     * fresh at review time from the sentence bank (see [transformRealization]), so
     * seeding here doesn't need to pre-validate that a negatable sentence exists.
     */
    private suspend fun syncMissingTransformCards(limit: Int = 24) {
        val notes = allNotesCached()
        val candidates = notes.filter { it.tier == 0 && it.partOfSpeech == "verb" }
        if (candidates.isEmpty()) return
        val existingByNote = cardDao.getCardsForNotes(candidates.map { it.id }).groupBy { it.noteId }
        var minted = 0
        for (verb in candidates) {
            if (minted >= limit) break
            val cards = existingByNote[verb.id].orEmpty()
            if (cards.any { it.cardType == CardType.TRANSFORM }) continue
            val recognition = cards.firstOrNull { it.cardType == CardType.RU_TO_MEANING } ?: continue
            val mature = recognition.reps >= 3 && recognition.consecutiveCorrect >= 2 &&
                recognition.state in setOf(CardState.REVIEW, CardState.GRADUATED)
            if (!mature) continue
            cardDao.insert(Card(noteId = verb.id, cardType = CardType.TRANSFORM, queue = Queue.VOCAB, due = 0L))
            minted++
        }
    }

    /**
     * Mints a SPEAK_SENTENCE card (P6.1 elicited imitation) directly onto tier-0
     * notes whose recognition has matured, any part of speech — the actual
     * sentence is picked fresh from the sentence bank at review time (see
     * [speakSentenceRealization]), so seeding here doesn't need to pre-validate
     * that a suitable sentence exists.
     */
    private suspend fun syncMissingSpeakSentenceCards(limit: Int = 24) {
        val candidates = allNotesCached().filter { it.tier == 0 }
        if (candidates.isEmpty()) return
        val existingByNote = cardDao.getCardsForNotes(candidates.map { it.id }).groupBy { it.noteId }
        var minted = 0
        for (note in candidates) {
            if (minted >= limit) break
            val cards = existingByNote[note.id].orEmpty()
            if (cards.any { it.cardType == CardType.SPEAK_SENTENCE }) continue
            val recognition = cards.firstOrNull { it.cardType == CardType.RU_TO_MEANING } ?: continue
            val mature = recognition.reps >= 3 && recognition.consecutiveCorrect >= 2 &&
                recognition.state in setOf(CardState.REVIEW, CardState.GRADUATED)
            if (!mature) continue
            cardDao.insert(Card(noteId = note.id, cardType = CardType.SPEAK_SENTENCE, queue = Queue.VOCAB, due = 0L))
            minted++
        }
    }

    suspend fun exportJsonLines(): String = exportLines(includeSrs = false)

    suspend fun exportFullState(): String = exportLines(includeSrs = true)

    private suspend fun exportLines(includeSrs: Boolean): String {
        var notes: List<Note> = emptyList()
        var cards: List<Card> = emptyList()
        var logs: List<ReviewLog> = emptyList()
        var readers: List<ReaderText> = emptyList()
        var schedulesSnapshot: List<ReadingSchedule> = emptyList()
        var encountersSnapshot: List<ReaderEncounter> = emptyList()
        var activitiesSnapshot: List<ReadingActivity> = emptyList()
        var pairsSnapshot: List<ConfusablePair> = emptyList()
        var telemetrySnapshot: List<TelemetryEvent> = emptyList()
        var modelLinesSnapshot: List<String> = emptyList()
        // Room guarantees every DAO read in this block observes the same database
        // snapshot. Serialization happens afterwards so the write lock stays short.
        runInTransaction {
            notes = noteDao.getAll()
            if (includeSrs) {
                cards = cardDao.getAll()
                logs = reviewLogDao.getAll()
                readers = readerTextDao.getAll()
                schedulesSnapshot = readingScheduleDao?.getAll().orEmpty()
                encountersSnapshot = readerEncounterDao?.getAll().orEmpty()
                activitiesSnapshot = readingActivityDao?.getAll().orEmpty()
                pairsSnapshot = confusablePairDao.getAll()
                telemetrySnapshot = telemetryDao?.getAll().orEmpty()
                learningModelDao?.let { dao ->
                    val lines = mutableListOf<String>()
                    fun line(kind: String, block: JSONObject.() -> Unit) { lines += JSONObject().put("_model",true).put("kind",kind).apply(block).toString() }
                    dao.difficulties().forEach { d -> cards.firstOrNull { it.id == d.cardId }?.let { card -> line("difficulty") { put("noteLemma", notes.firstOrNull { it.id == card.noteId }?.lemma); put("cardVariant",card.srsVariantKey()); put("elo",d.elo); put("sigma",d.sigma); put("observations",d.observations); put("updatedAt",d.updatedAt) } } }
                    dao.masteries().forEach { v -> line("mastery") { put("concept",v.concept);put("probability",v.probability);put("observations",v.observations);put("updatedAt",v.updatedAt) } }
                    dao.parameters().forEach { v -> line("parameter") { put("key",v.key);put("value",v.value);put("observations",v.observations);put("updatedAt",v.updatedAt) } }
                    dao.skillRatings().forEach { v -> line("skill") { put("skill",v.skill);put("muGlobalShare",v.muGlobalShare);put("mu",v.mu);put("sigma",v.sigma);put("observations",v.observations);put("updatedAt",v.updatedAt) } }
                    dao.capacityState()?.let { v -> line("capacity") { put("mu",v.mu);put("sigma",v.sigma);put("updatedAt",v.updatedAt) } }
                    dao.willingnessState()?.let { v -> line("willingness") { put("habit",v.habit);put("coeffsJson",v.coeffsJson);put("updatedAt",v.updatedAt) } }
                    dao.rivalState()?.let { v -> line("rival") { put("mu",v.mu);put("sigma",v.sigma);put("handicap",v.handicap);put("winStreak",v.winStreak);put("persona",v.persona);put("updatedAt",v.updatedAt) } }
                    dao.ghostSnapshots().forEach { v -> line("ghost") { put("takenAt",v.takenAt);put("muGlobal",v.muGlobal);put("sigma",v.sigma) } }
                    dao.allPaceLogs().forEach { v -> line("pace") { put("at",v.at);put("T",v.T);put("N",v.N);put("rho",v.rho);put("debtRatio",v.debtRatio);put("pReturn",v.pReturn);put("doctrine",v.doctrine);put("modeChosen",v.modeChosen) } }
                    dao.banditArmStates().forEach { v -> line("banditArm") { put("action",v.action);put("rewardJson",v.rewardJson);put("precisionJson",v.precisionJson);put("pulls",v.pulls);put("updatedAt",v.updatedAt) } }
                    modelLinesSnapshot = lines
                }
            }
        }
        val noteById = notes.associateBy { it.id }
        val cardsByNoteId = cards.groupBy { it.noteId }
        val cardsById = cards.associateBy { it.id }
        val logsByCardId = logs.groupBy { it.cardId }
        val noteLines = notes
            .sortedWith(compareBy<Note> { it.domainFreqRank ?: Int.MAX_VALUE }.thenBy { it.generalFreqRank ?: Int.MAX_VALUE }.thenBy { it.lemma })
            .joinToString("\n") { note ->
                JSONObject().apply {
                    put("russian", note.russian)
                    put("lemma", note.lemma)
                    put("pos", note.partOfSpeech)
                    put("translation", note.translation)
                    put("aspect", note.aspect)
                    put("aspectPartner", note.aspectPartner?.let { noteById[it]?.lemma })
                    put("aktionsart", note.aktionsart)
                    put("aktionsartConfidence", note.aktionsartConfidence)
                    put("gender", note.gender)
                    put("declensionJson", note.declensionJson)
                    put("generalFreqRank", note.generalFreqRank)
                    put("domainFreqRank", note.domainFreqRank)
                    put("encounterCount", note.encounterCount)
                    put("exampleSentence", note.exampleSentence)
                    put("exampleTranslation", note.exampleTranslation)
                    put("exampleSentence2", note.exampleSentence2)
                    put("exampleTranslation2", note.exampleTranslation2)
                    put("exampleSentence3", note.exampleSentence3)
                    put("exampleTranslation3", note.exampleTranslation3)
                    put("audioPath", note.audioPath)
                    put("tags", note.tags)
                    put("status", note.status.name)
                    put("tier", note.tier)
                    put("unit", note.unit)
                    put("conceptId", note.conceptId)
                    put("cefrLevel", note.cefrLevel)
                    put("mnemonic", note.mnemonic)
                    put("secondSense", note.secondSense)
                    put("secondSenseExample", note.secondSenseExample)
                    put("secondSenseExampleTranslation", note.secondSenseExampleTranslation)
                    val noteCards = cardsByNoteId[note.id]
                    if (!noteCards.isNullOrEmpty()) {
                        put("_cards", org.json.JSONArray().apply {
                            noteCards.forEach { card ->
                                put(JSONObject().apply {
                                    put("cardType", card.cardType.name)
                                    put("queue", card.queue.name)
                                    put("state", card.state.name)
                                    put("stability", card.stability)
                                    put("difficulty", card.difficulty)
                                    put("elapsedDays", card.elapsedDays)
                                    put("scheduledDays", card.scheduledDays)
                                    put("reps", card.reps)
                                    put("lapses", card.lapses)
                                    put("due", card.due)
                                    put("lastReview", card.lastReview)
                                    put("consecutiveCorrect", card.consecutiveCorrect)
                                    put("suspended", card.suspended)
                                    card.gramCase?.let { put("gramCase", it) }
                                    card.gramGender?.let { put("gramGender", it) }
                                    card.gramNumber?.let { put("gramNumber", it) }
                                    card.gramContextCue?.let { put("gramContextCue", it) }
                                    card.gramConcept?.let { put("gramConcept", it) }
                                    logsByCardId[card.id]?.takeIf { it.isNotEmpty() }?.let { logs ->
                                        put("_reviews", org.json.JSONArray().apply {
                                            logs.forEach { log -> put(JSONObject().apply {
                                                put("reviewDatetime", log.reviewDatetime)
                                                put("rating", log.rating.name)
                                                put("stateBefore", log.stateBefore.name)
                                                put("scheduledDays", log.scheduledDays)
                                                put("elapsedDays", log.elapsedDays)
                                                put("source", log.source.name)
                                                put("stabilityBefore", log.stabilityBefore)
                                                put("evidenceStrength", log.evidenceStrength?.name)
                                            }) }
                                        })
                                    }
                                })
                            }
                        })
                    }
                }.toString()
            }
        if (!includeSrs) return noteLines
        val schedules = schedulesSnapshot.associateBy { it.readerTextId }
        val encounters = encountersSnapshot.groupBy { it.readerTextId }
        val activities = activitiesSnapshot.groupBy { it.readerTextId }
        val readerLines = readers.joinToString("\n") { reader ->
            JSONObject().apply {
                put("_readerText", true)
                put("title", reader.title)
                put("body", reader.body)
                put("source", reader.source)
                schedules[reader.id]?.let { schedule -> put("schedule", JSONObject().apply {
                    put("due", schedule.due)
                    put("intervalDays", schedule.intervalDays)
                    put("reps", schedule.reps)
                    put("lapses", schedule.lapses)
                    put("lastCompleted", schedule.lastCompleted)
                }) }
                put("encounterLemmas", org.json.JSONArray().apply {
                    encounters[reader.id].orEmpty().mapNotNull { noteById[it.noteId]?.lemma }.forEach(::put)
                })
                put("activities", org.json.JSONArray().apply {
                    activities[reader.id].orEmpty().forEach { activity ->
                        put(JSONObject().apply {
                            put("completedAt", activity.completedAt)
                            put("mistakes", activity.mistakes)
                            put("intervalDays", activity.intervalDays)
                        })
                    }
                })
            }.toString()
        }
        val pairLines = pairsSnapshot.joinToString("\n") { pair ->
            JSONObject().apply {
                put("_confusablePair", true)
                put("firstLemma", noteById[pair.firstNoteId]?.lemma)
                put("secondLemma", noteById[pair.secondNoteId]?.lemma)
                put("reason", pair.reason)
            }.toString()
        }
        // Telemetry rides along in the full-state backup only (not the content-only
        // export), marked with a "_telemetry" sentinel so importJsonLines can route
        // these lines to TelemetryDao instead of trying to parse them as notes.
        val telemetryLines = telemetrySnapshot.joinToString("\n") { event ->
            val eventCard = event.cardId?.let(cardsById::get)
            val eventNoteId = event.noteId ?: eventCard?.noteId
            JSONObject().apply {
                put("_telemetry", true)
                put("timestamp", event.timestamp)
                put("eventType", event.eventType)
                put("sessionId", event.sessionId)
                put("cardId", event.cardId)
                put("noteId", event.noteId)
                put("cardType", event.cardType)
                put("queue", event.queue)
                put("answerMode", event.answerMode)
                put("rating", event.rating)
                put("answerMatch", event.answerMatch)
                put("responseMs", event.responseMs)
                put("wasRevealed", event.wasRevealed)
                put("typedLength", event.typedLength)
                put("queueReason", event.queueReason)
                put("sessionRemaining", event.sessionRemaining)
                put("dueCount", event.dueCount)
                put("newCardLimit", event.newCardLimit)
                put("metadataJson", event.metadataJson)
                put("_noteLemma", eventNoteId?.let { noteById[it]?.lemma })
                put("_cardVariantKey", eventCard?.srsVariantKey())
            }.toString()
        }
        return listOf(noteLines, readerLines, pairLines, telemetryLines, modelLinesSnapshot.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n")
    }

    suspend fun addReaderText(title: String, body: String, source: String = "local"): Long {
        val id = readerTextDao.insert(ReaderText(title = title.ifBlank { "Imported Text" }, body = body, source = source))
        readingScheduleDao?.insert(ReadingSchedule(readerTextId = id))
        return id
    }

    private suspend fun syncReadingSchedules() {
        val dao = readingScheduleDao ?: return
        val scheduled = dao.getAll().mapTo(HashSet()) { it.readerTextId }
        val missing = readerTextDao.getAll().filter { it.id !in scheduled }
        if (missing.isNotEmpty()) dao.insertAll(missing.map { ReadingSchedule(readerTextId = it.id) })
    }

    /**
     * Additively import any bootstrap reader texts that aren't already present
     * (matched by title). Runs on every launch so existing users receive newly
     * shipped reading material on update, without wiping their data. Idempotent.
     */
    suspend fun syncBootstrapReaderTexts(): Int {
        val payload = bootstrapReaderTexts?.invoke()?.takeIf { it.isNotBlank() } ?: return 0
        val existingTitles = readerTextDao.getAll().mapTo(HashSet()) { it.title }
        val additions = mutableListOf<ReaderText>()
        payload.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val json = JSONObject(line)
                val title = json.optString("title", "Imported Text")
                if (title !in existingTitles) {
                    additions += ReaderText(
                        title = title.ifBlank { "Imported Text" },
                        body = json.getString("body"),
                        source = json.optString("source", "local")
                    )
                    existingTitles += title
                }
            }
        if (additions.isNotEmpty()) {
            runInTransaction { readerTextDao.insertAll(additions) }
        }
        return additions.size
    }

    /**
     * Additively import newly shipped textbook phrase notes into an existing DB.
     * This is intentionally limited to rows tagged "textbook": a normal bootstrap
     * rebuild can reorder thousands of course/general rows, but textbook rows are
     * generated as stable source-keyed phrases and are safe to merge by lemma.
     */
    suspend fun syncBootstrapTextbookNotes(): Int {
        val payload = bootstrapNotes?.invoke()?.takeIf { it.isNotBlank() } ?: return 0
        val textbookRows = payload.lineSequence().map { it.trim() }.filter { it.isNotBlank() }
            .map(::JSONObject)
            .filter { it.optString("tags", "").contains("textbook") }
            // A source phrase can appear on several textbook pages. Runtime sync
            // is keyed by lemma, so collapse the shipped payload before mutating
            // the DB; otherwise two same-lemma rows absent at function entry are
            // both inserted and maintenance has to merge them on every launch.
            .distinctBy { it.getString("lemma") }
            .toList()
        val validTextbookLemmas = textbookRows.mapTo(HashSet()) { it.getString("lemma") }
        val existing = noteDao.getAll()
        val existingByLemma = existing.associateBy { it.lemma }
        val existingLemmas = existingByLemma.keys.toHashSet()
        var imported = 0
        runInTransaction {
            // Retire names/PDF fragments removed by the improved textbook miner.
            existing.filter { it.tags.contains("textbook") && it.lemma !in validTextbookLemmas }.forEach { stale ->
                if (stale.status != WordStatus.IGNORED) noteDao.update(stale.copy(status = WordStatus.IGNORED))
                graduateVocabKnown(stale.id, System.currentTimeMillis())
            }
            textbookRows.forEach { json ->
                    val tags = json.optString("tags", "")
                    val lemma = json.getString("lemma")
                    val current = existingByLemma[lemma]
                    if (current != null) {
                        // Upgrade existing installs from the old 61..69 numbering and
                        // refresh corrected concise glosses without touching SRS state.
                        val refreshed = current.copy(
                            russian = json.getString("russian"),
                            translation = json.getString("translation"),
                            unit = json.optIntOrNull("unit"),
                            cefrLevel = json.optCleanString("cefrLevel"),
                            mnemonic = json.optCleanString("mnemonic") ?: current.mnemonic,
                            tags = tags
                        )
                        if (refreshed != current) noteDao.update(refreshed)
                        return@forEach
                    }
                    addNote(
                        Note(
                            russian = json.getString("russian"),
                            lemma = lemma,
                            translation = json.getString("translation"),
                            partOfSpeech = json.optString("pos", json.optString("partOfSpeech")),
                            aspect = json.optCleanString("aspect"),
                            aktionsart = json.optCleanString("aktionsart"),
                            aktionsartConfidence = json.optCleanString("aktionsartConfidence"),
                            gender = json.optCleanString("gender"),
                            declensionJson = json.optCleanString("declensionJson"),
                            generalFreqRank = json.optIntOrNull("generalFreqRank"),
                            domainFreqRank = json.optIntOrNull("domainFreqRank"),
                            encounterCount = json.optInt("encounterCount", 0),
                            exampleSentence = json.optCleanString("exampleSentence"),
                            exampleTranslation = json.optCleanString("exampleTranslation"),
                            exampleSentence2 = json.optCleanString("exampleSentence2"),
                            exampleTranslation2 = json.optCleanString("exampleTranslation2"),
                            exampleSentence3 = json.optCleanString("exampleSentence3"),
                            exampleTranslation3 = json.optCleanString("exampleTranslation3"),
                            audioPath = json.optCleanString("audioPath"),
                            tags = tags,
                            tier = json.optInt("tier", 1),
                            unit = json.optIntOrNull("unit"),
                            conceptId = json.optCleanString("conceptId"),
                            cefrLevel = json.optCleanString("cefrLevel"),
                            mnemonic = json.optCleanString("mnemonic")
                        )
                    )
                    existingLemmas += lemma
                    imported += 1
                }
        }
        invalidateNoteStructure()
        return imported
    }

    /**
     * Retire bundled matrix/domain records removed by the release quality gate.
     * Imported and reader-mined notes are deliberately out of scope. We preserve
     * rows and review history, but stop every card so upgrades cannot keep serving
     * material that no longer exists in the verified bootstrap asset.
     */
    suspend fun retireRejectedBootstrapNotes(): Int {
        val payload = bootstrapNotes?.invoke()?.takeIf { it.isNotBlank() } ?: return 0
        val validLemmas = payload.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { RussianForms.normalize(JSONObject(it).getString("lemma")) }
            .toHashSet()
        val priorQualityRetirement = (telemetryDao?.countByType("quality_retirement") ?: 0) > 0
        val rejected = noteDao.getAll().filter { note ->
            val bundledLayer = note.tags.startsWith("general ") || note.tags.startsWith("domain ")
            val alreadyRepaired = priorQualityRetirement && note.status == WordStatus.NEW
            bundledLayer && !alreadyRepaired && RussianForms.normalize(note.lemma) !in validLemmas
        }
        if (rejected.isEmpty()) return 0
        var changedNotes = 0
        var changedCards = 0
        runInTransaction {
            rejected.forEach { note ->
                // NEW is intentional: IGNORED counts as known reader coverage and
                // previously inflated vocabulary totals and word achievements.
                if (note.status != WordStatus.NEW) {
                    noteDao.update(note.copy(status = WordStatus.NEW))
                    changedNotes += 1
                }
                changedCards += cardDao.suspendAllForNote(note.id)
            }
        }
        if (changedNotes > 0 || changedCards > 0) {
            invalidateNoteState()
            recordTelemetry(TelemetryEvent(
                eventType = "quality_retirement",
                metadataJson = JSONObject().put("notes", changedNotes).put("cards", changedCards).toString()
            ))
        }
        return changedNotes
    }

    /**
     * Repair notes whose English translation was concatenated into the Russian example
     * (`"Русский текст - English"`, [exampleTranslation] left blank). Splits each into
     * its sentence/translation pair via [withSplitExamples]. Idempotent: a repaired note
     * gains a non-blank translation and no longer matches [NoteDao.examplesNeedingSplit],
     * so this is a one-time cost that self-heals after backup restores too.
     */
    suspend fun repairConcatenatedExamples(): Int {
        val candidates = noteDao.examplesNeedingSplit()
        if (candidates.isEmpty()) return 0
        val repaired = withContext(computeDispatcher) {
            candidates.mapNotNull { note -> note.withSplitExamples().takeIf { it != note } }
        }
        if (repaired.isEmpty()) return 0
        runInTransaction { noteDao.updateAll(repaired) }
        invalidateNoteContent()
        runCatching {
            telemetryDao?.insert(
                TelemetryEvent(
                    eventType = "example_repair",
                    metadataJson = JSONObject().put("notes", repaired.size).toString()
                )
            )
        }
        return repaired.size
    }

    /** Fill authored-example gaps from the immutable corpus. Cached rows are
     * refreshed after the known vocabulary grows enough to improve i+1 quality. */
    suspend fun mineExampleGaps(limit: Int = 64, now: Long = System.currentTimeMillis()): Int {
        val dao = minedExampleDao ?: return 0
        if (contentDao == null) return 0
        val knownCount = knownNoteIds().size
        // Skip already-cached notes BEFORE taking the batch, so each run ADVANCES to new
        // gaps instead of re-scanning the same first-N (already-mined) every launch — the
        // previous order capped lifetime coverage at "mineable notes among the first N".
        // Re-mine only once the known-set has grown enough to improve i+1 quality.
        val cached = dao.getAll().associateBy { it.noteId }
        val notes = allNotesCached().asSequence()
            .filter { it.exampleSentence.isNullOrBlank() && it.partOfSpeech != "lesson" }
            .filter { note -> cached[note.id]?.let { knownCount - it.knownAtMine >= REMINE_KNOWN_DELTA } ?: true }
            .take(limit)
            .toList()
        var mined = 0
        for (note in notes) {
            if (mineExampleFor(note, knownCount, now) != null) mined++
        }
        return mined
    }

    @Volatile private var corpusLemmaMapCache: Map<String, String>? = null

    private suspend fun corpusLemmaMap(): Map<String, String> =
        corpusLemmaMapCache ?: run {
            val json = corpusLemmaProvider?.invoke()
            val parsed = if (json.isNullOrBlank()) emptyMap() else withContext(computeDispatcher) {
                val obj = JSONObject(json)
                HashMap<String, String>(obj.length()).apply {
                    obj.keys().forEach { k -> put(k, obj.getString(k)) }
                }
            }
            parsed.also { corpusLemmaMapCache = it }
        }

    /** Normalized surface used as the deck_lemma.json key: lowercase, ё→е, combining
     * stress mark removed, and any "tb_"-style namespace prefix stripped. Mirrors the
     * build-time normalization exactly so runtime lookups hit the map. */
    private fun normSurface(value: String): String =
        value.replace("́", "").replace("ё", "е").replace("Ё", "е")
            .lowercase(Locale.ROOT).trim().replace(Regex("^[a-z]+_"), "")

    /** Ordered corpus-lemma lookup keys for a note: the pymorphy base lemma resolved
     * from its surface/lemma via the map first (handles inflected/namespaced lemmas),
     * then the raw cleaned forms as fallbacks. */
    private suspend fun corpusLemmaKeys(note: Note): List<String> {
        val map = corpusLemmaMap()
        val ru = normSurface(note.russian)
        val lem = normSurface(note.lemma)
        return linkedSetOf(map[ru], map[lem], lem, ru).filterNotNull().filter { it.isNotBlank() }
    }

    suspend fun mineExampleFor(note: Note, knownCount: Int? = null, now: Long = System.currentTimeMillis()): MinedExample? {
        val corpus = contentDao ?: return null
        val cache = minedExampleDao ?: return null
        val knownIds = knownNoteIds()
        val knownForms = formIndex().asSequence().filter { (_, value) -> value.id in knownIds }.map { it.key }.toHashSet()
        val candidates = corpusLemmaKeys(note).firstNotNullOfOrNull { key ->
            corpus.candidatesForLemma(key).takeIf { it.isNotEmpty() }
        }.orEmpty()
        val resolvedKnownCount = knownCount ?: knownIds.size
        val ranked = withContext(computeDispatcher) { ExampleMiner.rank(note, candidates, knownForms, resolvedKnownCount, now) }
        val best = ranked.firstOrNull() ?: return null
        cache.upsert(best.example)
        telemetryDao?.insert(TelemetryEvent(
            timestamp = now, eventType = "example_mined", noteId = note.id,
            metadataJson = JSONObject().put("score", best.example.score).put("coverage", best.coverage)
                .put("iPlusOne", best.isIPlusOne).put("unknownCount", best.example.unknownCount)
                .put("source", best.example.source).toString()
        ))
        if (best.example.anchoredGloss != note.translation) telemetryDao?.insert(TelemetryEvent(
            timestamp = now, eventType = "gloss_anchored", noteId = note.id,
            metadataJson = JSONObject().put("sentenceId", best.example.sentenceId).toString()
        ))
        return best.example
    }

    private suspend fun promptNote(note: Note): Pair<Note, MinedExample?> {
        if (!note.exampleSentence.isNullOrBlank()) return note to null
        val knownCount = knownNoteIds().size
        val cached = minedExampleDao?.forNote(note.id)
        val mined = if (cached == null || knownCount - cached.knownAtMine >= REMINE_KNOWN_DELTA) {
            mineExampleFor(note, knownCount) ?: cached
        } else cached
        return if (mined == null) note to null else note.copy(
            translation = mined.anchoredGloss.ifBlank { note.translation },
            exampleSentence = mined.ru,
            exampleTranslation = mined.en
        ) to mined
    }

    private suspend fun enrichmentFor(note: Note): Enrichment {
        enrichmentCache[note.id]?.let { return it }
        val dao = contentDao ?: return Enrichment(cognate = CognateDetector.isCognate(note.russian, note.translation))
        val lemma = note.lemma.lowercase(Locale.ROOT).replace("ё", "е")
        return Enrichment(
            collocations = dao.chunksForLemma(lemma), family = dao.familyForLemma(lemma),
            emoji = dao.emojiForLemma(lemma), neighbors = dao.neighborsForLemma(lemma),
            cognate = CognateDetector.isCognate(note.russian, note.translation)
        ).also { enrichmentCache[note.id] = it }
    }

    /** One-time/idempotent cleanup for upgrades: merge duplicate notes without
     * losing logs or SRS state, remove duplicate reader rows, and retire ambiguous
     * production cards whose English prompt has several valid Russian answers. */
    suspend fun performDataMaintenance(): Int {
        var changes = 0
        runInTransaction {
            val duplicateGroups = noteDao.getAll().groupBy { it.lemma }.values.filter { it.size > 1 }
            for (group in duplicateGroups) {
                val cardsByNote = group.associate { it.id to cardDao.getCardsForNote(it.id) }
                val canonical = group.maxWithOrNull(
                    compareBy<Note> { cardsByNote[it.id].orEmpty().sumOf(Card::reps) }
                        .thenBy { it.encounterCount }
                        .thenBy { -it.id }
                ) ?: continue
                val preferredContent = group.minWithOrNull(
                    compareBy<Note> { if (it.tags.contains("textbook") && it.unit in 1..9) 0 else 1 }
                        .thenBy { it.unit ?: Int.MAX_VALUE }
                        .thenBy { it.id }
                ) ?: canonical
                noteDao.update(canonical.copy(
                    russian = preferredContent.russian,
                    translation = preferredContent.translation,
                    partOfSpeech = preferredContent.partOfSpeech,
                    exampleSentence = preferredContent.exampleSentence ?: canonical.exampleSentence,
                    exampleTranslation = preferredContent.exampleTranslation ?: canonical.exampleTranslation,
                    tags = preferredContent.tags,
                    tier = preferredContent.tier,
                    unit = preferredContent.unit,
                    cefrLevel = preferredContent.cefrLevel,
                    mnemonic = group.firstNotNullOfOrNull { it.mnemonic },
                    status = group.filter { it.status != WordStatus.IGNORED }
                        .maxByOrNull { wordStatusRank(it.status) }?.status ?: WordStatus.IGNORED,
                    encounterCount = group.maxOf { it.encounterCount }
                ))
                val canonicalCards = cardDao.getCardsForNote(canonical.id).toMutableList()
                for (duplicate in group.filter { it.id != canonical.id }) {
                    for (source in cardsByNote[duplicate.id].orEmpty()) {
                        val target = canonicalCards.firstOrNull { it.srsVariantKey() == source.srsVariantKey() }
                        if (target == null) {
                            cardDao.moveToNote(source.id, canonical.id)
                            canonicalCards += source.copy(noteId = canonical.id)
                        } else {
                            reviewLogDao.moveLogs(source.id, target.id)
                            if (source.reps > target.reps || (source.reps == target.reps && source.lastReview.orZero() > target.lastReview.orZero())) {
                                val mergedCard = source.copy(id = target.id, noteId = canonical.id)
                                cardDao.update(mergedCard)
                                val targetIndex = canonicalCards.indexOfFirst { it.id == target.id }
                                if (targetIndex >= 0) canonicalCards[targetIndex] = mergedCard
                            }
                            cardDao.deleteById(source.id)
                        }
                    }
                    confusablePairDao.moveFirstReferences(duplicate.id, canonical.id)
                    confusablePairDao.moveSecondReferences(duplicate.id, canonical.id)
                    noteDao.moveAspectPartnerReferences(duplicate.id, canonical.id)
                    readerEncounterDao?.getForNote(duplicate.id).orEmpty().forEach { encounter ->
                        readerEncounterDao?.insert(encounter.copy(noteId = canonical.id))
                    }
                    readerEncounterDao?.deleteForNote(duplicate.id)
                    noteDao.deleteById(duplicate.id)
                    changes += 1
                }
                val mergedEncounterCount = readerEncounterDao?.getForNote(canonical.id)?.size ?: 0
                noteDao.getById(canonical.id)?.let { merged ->
                    if (mergedEncounterCount > merged.encounterCount) {
                        noteDao.update(merged.copy(encounterCount = mergedEncounterCount))
                    }
                }
            }
            changes += noteDao.clearSelfAspectPartners()
            confusablePairDao.deleteSelfPairs()
            changes += confusablePairDao.deleteDuplicatePairs()

            val duplicateReaders = readerTextDao.getAll()
                .groupBy { "${it.title}\u0000${it.body}" }
                .values.filter { it.size > 1 }
            for (group in duplicateReaders) {
                val canonical = group.minBy { it.createdAt }
                val schedules = group.mapNotNull { readingScheduleDao?.get(it.id) }
                val bestSchedule = schedules.maxWithOrNull(
                    compareBy<ReadingSchedule> { it.lastCompleted ?: Long.MIN_VALUE }
                        .thenBy { it.reps }
                        .thenBy { it.intervalDays }
                )
                if (bestSchedule != null) {
                    val merged = bestSchedule.copy(readerTextId = canonical.id)
                    if (readingScheduleDao?.get(canonical.id) == null) readingScheduleDao?.insert(merged)
                    else readingScheduleDao.update(merged)
                }
                for (duplicate in group.filter { it.id != canonical.id }) {
                    readerEncounterDao?.getForText(duplicate.id).orEmpty().forEach { encounter ->
                        readerEncounterDao?.insert(encounter.copy(readerTextId = canonical.id))
                    }
                    readingActivityDao?.moveToText(duplicate.id, canonical.id)
                    readerEncounterDao?.deleteForText(duplicate.id)
                    readingScheduleDao?.deleteForText(duplicate.id)
                    readerTextDao.deleteById(duplicate.id)
                    changes += 1
                }
            }

            noteDao.getAll().filter(CardFactory::isAmbiguousFunctionNote).forEach { note ->
                changes += cardDao.suspendAmbiguousProduction(note.id)
            }
            // Repair notes graduated "already known" before graduateVocabKnown() also
            // bumped encounterCount: an unmet encounterCount == 0 gate on those notes'
            // still-NEW grammar drills (see isNewGrammarBeforeFirstEncounter) permanently
            // stalls that unit's mastery and every unit after it.
            noteDao.getAll()
                .filter { (it.status == WordStatus.KNOWN || it.status == WordStatus.IGNORED) && it.encounterCount < VOCAB_GRADUATION_ENCOUNTERS }
                .forEach { note ->
                    noteDao.update(note.copy(encounterCount = VOCAB_GRADUATION_ENCOUNTERS))
                    changes += 1
                }
            changes += cardDao.repairGraduatedRecognitionMaturity()
        }
        invalidateNoteStructure()
        if (changes > 0) recordTelemetry(TelemetryEvent(eventType = "data_maintenance", metadataJson = "{\"changes\":$changes}"))
        telemetryDao?.deleteOlderThan(System.currentTimeMillis() - TELEMETRY_RETENTION_MILLIS)
        return changes
    }

    suspend fun recordTelemetry(event: TelemetryEvent) {
        telemetryDao?.insert(event)
    }

    suspend fun captureSuccessCalibrationExposure(
        card: Card,
        fatigue: Double,
        at: Long = System.currentTimeMillis()
    ): com.sibirskyspeak.learning.CalibrationExposure? {
        if (telemetryDao == null) return null
        val sample = successCalibrationSample(card, false, fatigue, at) ?: return null
        val parameters = learningModelDao?.parameters().orEmpty()
        val calibration = successCalibration(parameters)
        val predicted = WorldModel.predictedProbability(sample, calibration)
        val modelVersion = parameters.firstOrNull { it.key == com.sibirskyspeak.learning.ModelGovernance.CURRENT_VERSION_KEY }?.value?.toInt() ?: 0
        val cefrLevel = noteDao.getById(card.noteId)?.cefrLevel
        return com.sibirskyspeak.learning.CalibrationExposure(sample, predicted, modelVersion, cefrLevel)
    }

    suspend fun recordSuccessCalibrationSample(
        card: Card,
        exposure: com.sibirskyspeak.learning.CalibrationExposure,
        correct: Boolean,
        at: Long = System.currentTimeMillis()
    ) {
        val telemetry = telemetryDao ?: return
        val sample = exposure.sample.copy(correct = correct)
        telemetry.insert(TelemetryEvent(
            timestamp = at,
            eventType = "success_calibration_sample",
            cardId = card.id,
            noteId = card.noteId,
            cardType = card.cardType.name,
            answerMatch = if (correct) "CORRECT" else "WRONG",
            metadataJson = JSONObject()
                .put("abilityMinusDifficulty", sample.abilityMinusDifficulty)
                .put("memoryProbit", sample.memoryProbit)
                .put("masteryCentered", sample.masteryCentered)
                .put("fatigue", sample.fatigue)
                .put("scale", sample.scale)
                .put("correct", sample.correct)
                .put("predicted", exposure.predicted)
                .put("modelVersion", exposure.modelVersion)
                .put("cefrLevel", exposure.cefrLevel ?: JSONObject.NULL)
                .toString()
        ))
        maybeFitSuccessCalibration()
    }

    private suspend fun successCalibrationSample(card: Card, correct: Boolean, fatigue: Double, at: Long): WorldModel.CalibrationSample? {
        val dao = learningModelDao ?: return null
        val parameters = dao.parameters().associateBy { it.key }
        val global = Gaussian(parameters["global_skill_mu"]?.value ?: TrueSkill.MU0, parameters["global_skill_sigma"]?.value ?: TrueSkill.SIGMA0)
        val skills = dao.skillRatings().mapNotNull { row ->
            runCatching { AbilitySkill.valueOf(row.skill.uppercase()) }.getOrNull()?.let { it to Gaussian(row.mu, row.sigma) }
        }.toMap()
        val state = com.sibirskyspeak.learning.LearnerWorldState(global = global, skills = skills, fatigue = fatigue)
        val ability = WorldModel.effectiveAbility(card, state)
        val difficulty = dao.difficulty(card.id) ?: ItemDifficulty(card.id, elo = objectiveDifficultyPrior(card))
        val concept = card.gramConcept ?: noteDao.getById(card.noteId)?.lemma
        val mastery = concept?.let { dao.mastery(it) }
        val elapsed = ((at - (card.lastReview ?: at)).coerceAtLeast(0) / DAY_MILLIS.toDouble())
        val retrievability = if (card.lastReview == null || card.stability <= 0.0) 0.5 else FsrsScheduler.retrievabilityOf(elapsed, card.stability, decayProvider())
        val scale = kotlin.math.sqrt(2.0 * TrueSkill.BETA * TrueSkill.BETA + ability.variance + difficulty.sigma * difficulty.sigma)
        return WorldModel.CalibrationSample(
            correct = correct,
            abilityMinusDifficulty = ability.mu - difficulty.elo,
            memoryProbit = com.sibirskyspeak.learning.Normal.invCdf(retrievability.coerceIn(1e-3, 1.0 - 1e-3)),
            masteryCentered = (mastery?.probability ?: 0.5) - 0.5,
            fatigue = fatigue.coerceIn(0.0, 1.0),
            scale = scale
        )
    }

    private suspend fun maybeFitSuccessCalibration() {
        val telemetry = telemetryDao ?: return
        val dao = learningModelDao ?: return
        val totalSamples = telemetry.countByType("success_calibration_sample")
        if (totalSamples < SuccessCalibrationFitter.MIN_SAMPLES) return
        val current = successCalibration(dao.parameters())
        if (current.observations >= totalSamples - 24) return
        val samples = telemetry.recent(5_000).asReversed().mapNotNull { event ->
            if (event.eventType != "success_calibration_sample") return@mapNotNull null
            runCatching {
                val json = JSONObject(event.metadataJson)
                WorldModel.CalibrationSample(
                    correct = json.getBoolean("correct"),
                    abilityMinusDifficulty = json.getDouble("abilityMinusDifficulty"),
                    memoryProbit = json.getDouble("memoryProbit"),
                    masteryCentered = json.getDouble("masteryCentered"),
                    fatigue = json.getDouble("fatigue"),
                    scale = json.getDouble("scale")
                )
            }.getOrNull()
        }
        if (samples.size < SuccessCalibrationFitter.MIN_SAMPLES) return
        val fitted = SuccessCalibrationFitter.fit(samples, current).copy(observations = totalSamples)
        listOf(
            OptimizerParameter("success_intercept", fitted.intercept, fitted.observations),
            OptimizerParameter("success_s_mem", fitted.memoryScale, fitted.observations),
            OptimizerParameter("success_k_k", fitted.masteryScale, fitted.observations),
            OptimizerParameter("success_lambda_load", fitted.loadScale, fitted.observations)
        ).forEach { dao.upsertParameter(it) }
    }

    private fun successCalibration(parameters: List<OptimizerParameter>): WorldModel.Calibration {
        val values = parameters.associateBy { it.key }
        return WorldModel.Calibration(
            intercept = values["success_intercept"]?.value ?: 0.0,
            memoryScale = values["success_s_mem"]?.value ?: WorldModel.S_MEM,
            masteryScale = values["success_k_k"]?.value ?: WorldModel.K_K,
            loadScale = values["success_lambda_load"]?.value ?: WorldModel.LAMBDA_LOAD,
            observations = values["success_s_mem"]?.observations ?: 0
        )
    }

    /** On-device calibration/drift report, segmented by card format. Older events
     * without an at-show prediction are intentionally excluded rather than rebuilt
     * with today's model (which would introduce hindsight leakage). */
    suspend fun calibrationDriftReport(limit: Int = 2_000): com.sibirskyspeak.learning.DriftReport? {
        val observations = telemetryDao?.recent(limit.coerceAtLeast(60)).orEmpty().asReversed().mapNotNull { event ->
            if (event.eventType != "success_calibration_sample") return@mapNotNull null
            runCatching {
                val json = JSONObject(event.metadataJson)
                if (!json.has("predicted")) return@runCatching null
                com.sibirskyspeak.learning.PredictionObservation(
                    predicted = json.getDouble("predicted"),
                    recalled = json.getBoolean("correct"),
                    segment = event.cardType ?: "unknown",
                    at = event.timestamp,
                    cefrLevel = json.optString("cefrLevel").takeIf { it.isNotBlank() && it != "null" },
                    modelVersion = json.optInt("modelVersion", 0)
                )
            }.getOrNull()
        }
        return com.sibirskyspeak.learning.CalibrationDiagnostics.driftByVersionOrTime(observations)
    }

    /** Persist an immutable, namespaced optimizer snapshot. Active parameters are
     * changed only by [promoteModelSnapshot], after offline/online guardrails pass. */
    suspend fun saveModelSnapshot(snapshot: com.sibirskyspeak.learning.ModelSnapshot): Boolean {
        val dao = learningModelDao ?: return false
        if (com.sibirskyspeak.learning.ModelGovernance.validate(snapshot).isNotEmpty()) return false
        val rows = snapshot.parameters.map { (key, value) ->
            OptimizerParameter(
                key = com.sibirskyspeak.learning.ModelGovernance.snapshotKey(snapshot.version, key),
                value = value,
                observations = snapshot.parentVersion ?: 0,
                updatedAt = snapshot.createdAt
            )
        }
        dao.upsertParameters(rows)
        // Bound local history growth while retaining two years of monthly rollback
        // points. Active/parent snapshots are necessarily among the newest entries.
        val history = modelSnapshots()
        val currentVersion = dao.parameters().firstOrNull { it.key == com.sibirskyspeak.learning.ModelGovernance.CURRENT_VERSION_KEY }?.value?.toInt()
        val retainedVersions = com.sibirskyspeak.learning.ModelGovernance.versionsToRetain(history, currentVersion, 24)
        val obsolete = history.filter { it.version !in retainedVersions }
            .flatMap { old -> old.parameters.keys.map { key -> com.sibirskyspeak.learning.ModelGovernance.snapshotKey(old.version, key) } }
        if (obsolete.isNotEmpty()) dao.deleteParameters(obsolete)
        return true
    }

    suspend fun modelSnapshots(): List<com.sibirskyspeak.learning.ModelSnapshot> {
        val rows = learningModelDao?.parameters().orEmpty()
        val prefix = "model:snapshot:"
        return rows.filter { it.key.startsWith(prefix) }.groupBy { row ->
            row.key.removePrefix(prefix).substringBefore(':').toIntOrNull()
        }.mapNotNull { (version, values) ->
            version ?: return@mapNotNull null
            val parameters = values.associate { it.key.removePrefix("$prefix$version:") to it.value }
            com.sibirskyspeak.learning.ModelSnapshot(
                version, parameters, values.maxOfOrNull { it.updatedAt } ?: 0L,
                values.firstOrNull()?.observations?.takeIf { it > 0 }
            ).takeIf { com.sibirskyspeak.learning.ModelGovernance.validate(it).isEmpty() }
        }.sortedBy { it.version }
    }

    suspend fun promoteModelSnapshot(
        version: Int,
        decision: com.sibirskyspeak.learning.PromotionDecision,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (!decision.promote) return false
        val dao = learningModelDao ?: return false
        val snapshot = modelSnapshots().firstOrNull { it.version == version } ?: return false
        val allParameters = dao.parameters()
        val oldVersion = allParameters.firstOrNull { it.key == com.sibirskyspeak.learning.ModelGovernance.CURRENT_VERSION_KEY }?.value?.toInt()
        val oldSnapshot = oldVersion?.let { active -> modelSnapshots().firstOrNull { it.version == active } }
        val obsolete = oldSnapshot?.parameters?.keys.orEmpty() - snapshot.parameters.keys
        val rows = snapshot.parameters.map { (key, value) -> OptimizerParameter(key, value, updatedAt = now) } +
            OptimizerParameter(com.sibirskyspeak.learning.ModelGovernance.CURRENT_VERSION_KEY, version.toDouble(), updatedAt = now)
        dao.replaceParameters(obsolete.toList(), rows)
        return true
    }

    suspend fun rollbackModelSnapshot(now: Long = System.currentTimeMillis()): Boolean {
        val dao = learningModelDao ?: return false
        val parameters = dao.parameters()
        val currentVersion = parameters.firstOrNull { it.key == com.sibirskyspeak.learning.ModelGovernance.CURRENT_VERSION_KEY }
            ?.value?.toInt() ?: return false
        val history = modelSnapshots()
        val current = history.firstOrNull { it.version == currentVersion } ?: return false
        val target = com.sibirskyspeak.learning.ModelGovernance.rollback(current, history) ?: return false
        val obsolete = current.parameters.keys - target.parameters.keys
        val rows = target.parameters.map { (key, value) -> OptimizerParameter(key, value, updatedAt = now) } +
            OptimizerParameter(com.sibirskyspeak.learning.ModelGovernance.CURRENT_VERSION_KEY, target.version.toDouble(), updatedAt = now)
        dao.replaceParameters(obsolete.toList(), rows)
        return true
    }

    /** Seeded replay tuning with counterfactual and calibration guardrails. The
     * candidate is always snapshotted for auditability; activation is optional and
     * impossible unless every promotion guard passes. */
    suspend fun tuneAndStageLearningPolicy(
        populationSize: Int = 500,
        replayDays: Int = 90,
        seed: Int = 17,
        autoPromote: Boolean = false,
        now: Long = System.currentTimeMillis()
    ): com.sibirskyspeak.learning.StagedTuning? = modelTuningMutex.withLock {
        tuneAndStageLearningPolicyUnlocked(populationSize, replayDays, seed, autoPromote, now)
    }

    private suspend fun tuneAndStageLearningPolicyUnlocked(
        populationSize: Int,
        replayDays: Int,
        seed: Int,
        autoPromote: Boolean,
        now: Long
    ): com.sibirskyspeak.learning.StagedTuning? = withContext(computeDispatcher) {
        val dao = learningModelDao ?: return@withContext null
        val settings = config()
        val baseline = com.sibirskyspeak.learning.SimulationPolicy(
            "current", settings.desiredRetention.coerceIn(.85, .95), settings.newCardsPerDay
        )
        val parameterRows = dao.parameters().associateBy { it.key }
        val abilityCenter = ((parameterRows["global_skill_mu"]?.value?.takeIf(Double::isFinite) ?: TrueSkill.MU0) / TrueSkill.MU0).coerceIn(.65, 1.25)
        val capacityRow = dao.capacityState()
        val minutesCenter = capacityRow?.let { CapacityBelief(it.mu, it.sigma).sustainableMinutes }?.coerceIn(5.0, 45.0) ?: 12.0
        val willingnessRow = dao.willingnessState()
        val returnCenter = willingnessRow?.let {
            WillingnessModel.returnProbability(WillingnessBelief(it.habit, parseWillingnessCoefficients(it.coeffsJson)), ReturnContext())
        }?.coerceIn(.75, .995) ?: .90
        // Preserve a broad stress population but center most mass on this learner's
        // observed ability, sustainable time, and return propensity.
        val profiles = com.sibirskyspeak.learning.PopulationSimulator
            .profiles(populationSize.coerceIn(100, 5_000), seed)
            .map { profile -> profile.copy(
                ability = (0.4 * profile.ability + 0.6 * abilityCenter).coerceIn(.55, 1.35),
                dailyMinutes = (0.4 * profile.dailyMinutes + 0.6 * minutesCenter).coerceIn(3.0, 60.0),
                returnBase = (0.4 * profile.returnBase + 0.6 * returnCenter).coerceIn(.70, .999)
            ) }
        val tuning = com.sibirskyspeak.learning.ReplayParameterTuner.tune(
            profiles, baseline,
            retentions = listOf(.85, .88, .90, .92),
            newCaps = listOf(
                (settings.newCardsPerDay * .7).toInt(), settings.newCardsPerDay,
                (settings.newCardsPerDay * 1.2).toInt()
            ).map { it.coerceAtLeast(0) }.distinct(),
            uncertaintyWeights = listOf(0.0, .10, .18, .25),
            days = replayDays.coerceIn(30, 365), seed = seed
        )
        val drift = calibrationDriftReport()
        val decision = com.sibirskyspeak.learning.ModelGovernance.promotionDecision(tuning.comparison, drift)
        val history = modelSnapshots()
        val currentVersion = dao.parameters().firstOrNull { it.key == com.sibirskyspeak.learning.ModelGovernance.CURRENT_VERSION_KEY }
            ?.value?.toInt()?.takeIf { it > 0 }
        val version = (history.maxOfOrNull { it.version } ?: 0) + 1
        val baselineNew = settings.newCardsPerDay.coerceAtLeast(1)
        val snapshot = com.sibirskyspeak.learning.ModelSnapshot(
            version = version,
            parameters = mapOf(
                "tuned_target_retention" to tuning.policy.targetRetention,
                "tuned_new_budget_scale" to (tuning.policy.newPerDay.toDouble() / baselineNew).coerceIn(.5, 1.5),
                "tuned_uncertainty_weight" to tuning.policy.uncertaintyWeight
            ),
            createdAt = now,
            parentVersion = currentVersion
        )
        if (!saveModelSnapshot(snapshot)) return@withContext null
        val promoted = autoPromote && promoteModelSnapshot(version, decision, now)
        com.sibirskyspeak.learning.StagedTuning(version, tuning, decision, promoted)
    }

    suspend fun recentTelemetry(limit: Int = 1000): List<TelemetryEvent> = telemetryDao?.recent(limit).orEmpty()

    suspend fun banditArmStates(): List<ContextualBandit.Snapshot> =
        learningModelDao?.banditArmStates().orEmpty().mapNotNull { row ->
            val reward = runCatching {
                val json = JSONArray(row.rewardJson)
                DoubleArray(json.length()) { json.getDouble(it) }
            }.getOrNull() ?: return@mapNotNull null
            val precision = runCatching {
                val json = JSONArray(row.precisionJson)
                DoubleArray(json.length()) { json.getDouble(it) }
            }.getOrNull() ?: return@mapNotNull null
            ContextualBandit.Snapshot(row.action, row.pulls, reward, precision)
        }

    suspend fun upsertBanditArmStates(snapshots: Collection<ContextualBandit.Snapshot>) {
        val dao = learningModelDao ?: return
        val updatedAt = System.currentTimeMillis()
        snapshots.forEach { snapshot ->
            dao.upsertBanditArmState(
                BanditArmState(
                    action = snapshot.action,
                    rewardJson = JSONArray(snapshot.reward.toList()).toString(),
                    precisionJson = JSONArray(snapshot.precision.toList()).toString(),
                    pulls = snapshot.pulls,
                    updatedAt = updatedAt
                )
            )
        }
    }

    private fun estimatedSessionFatigue(events: List<TelemetryEvent>): Double {
        val samples = events.asReversed()
            .filter { it.eventType == "review_committed" && it.responseMs != null }
            .take(48)
            .mapNotNull { event ->
                val response = event.responseMs?.takeIf { it > 0 } ?: return@mapNotNull null
                val correct = event.answerMatch?.equals("WRONG", ignoreCase = true) == false
                response to correct
            }
        return if (samples.size < 4) 0.0 else FatigueModel.estimate(samples.map { it.first }, samples.map { it.second })
    }

    private fun medianReviewMinutes(events: List<TelemetryEvent>): Double {
        val samples = events.asReversed()
            .filter { it.eventType == "review_committed" && it.responseMs != null }
            .take(60)
            .mapNotNull { it.responseMs?.takeIf { value -> value > 0 } }
        if (samples.isEmpty()) return 0.18
        val sorted = samples.sorted()
        val median = if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0 else sorted[sorted.size / 2].toDouble()
        return (median / 60_000.0).coerceIn(0.08, 1.2)
    }

    private fun expectedSessionsPerDay(events: List<TelemetryEvent>): Double {
        val starts = events.count { it.eventType == "session_start" }
        return (starts / 14.0).coerceIn(0.5, 2.5).takeIf { it.isFinite() } ?: 1.0
    }

    /** Per-review rows (card-grouped, oldest first) for the on-device FSRS weight fit. */
    suspend fun reviewSamplesForFitting(): List<ReviewFitRow> = reviewLogDao.reviewFitRows()

    /** Mature-review retention by card type over the rolling retention window, so the
     *  aggregate retention figure can be attributed to specific quiz facets. */
    suspend fun retentionByCardType(now: Long = System.currentTimeMillis()): List<CardTypeRetention> =
        reviewLogDao.matureRetentionByCardType(now - RETENTION_WINDOW_DAYS * DAY_MILLIS)

    private fun wordStatusRank(status: WordStatus): Int = when (status) {
        WordStatus.NEW -> 0
        WordStatus.LEARNING -> 1
        WordStatus.KNOWN -> 2
        WordStatus.IGNORED -> 3
    }

    private fun Long?.orZero(): Long = this ?: 0L

    suspend fun importReaderTextsJsonLines(jsonLines: String): Int {
        val texts = jsonLines.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                val json = JSONObject(line)
                ReaderText(
                    title = json.optString("title", "Imported Text"),
                    body = json.getString("body"),
                    source = json.optString("source", "local")
                )
            }
            .toList()
        if (texts.isNotEmpty()) {
            runInTransaction { readerTextDao.insertAll(texts) }
        }
        return texts.size
    }

    suspend fun readerTexts(now: Long = System.currentTimeMillis()): List<ReaderRecommendation> {
        val index = formIndex()
        val known = knownNoteIds()
        val dueSoon = cardDao.getDueSoonNoteIds(now + 2 * DAY_MILLIS).toHashSet()
        val covered = readerTextDao.getAll().map { coverageFor(it, index, known, dueSoon) }
        val targetReady = covered.any { it.text.source.startsWith("target:", ignoreCase = true) && it.coverage >= AUTHENTIC_READY_COVERAGE }
        return covered.map { it.copy(authenticReady = targetReady) }
    }

    suspend fun readerTokens(text: ReaderText): List<ReaderToken> {
        val notes = allNotesCached()
        val index = formIndex()
        val known = knownNoteIds()
        val statusById = HashMap<Long, WordStatus>(notes.size)
        for (n in notes) statusById[n.id] = n.status
        val body = text.body
        // Tokenizing the full body and resolving every token against the form index
        // is CPU-bound; run it off the main thread (callers launch from the UI scope).
        return withContext(computeDispatcher) {
        val matches = Regex("""[\p{L}́]+""").findAll(body).toList()
        matches.mapIndexed { i, match ->
            val token = match.value
            val start = match.range.first
            val end = match.range.last + 1
            // Punctuation glued to this word: the run of non-space chars right before it
            // (opening quote/bracket/dash) and right after it (comma, period, etc.).
            val prevEnd = if (i == 0) 0 else matches[i - 1].range.last + 1
            val nextStart = if (i + 1 < matches.size) matches[i + 1].range.first else body.length
            val gapBefore = body.substring(prevEnd, start)
            val leading = gapBefore.takeLastWhile { !it.isWhitespace() }
            val trailing = body.substring(end, nextStart).takeWhile { !it.isWhitespace() }
            val normalized = normalizeToken(token)
            val note = index[normalized]
            // Proper-noun heuristic: an unknown word that is Capitalized mid-sentence
            // (not at a sentence start, where any word is capitalized) is almost
            // certainly a name — treat it as readable (ignored) rather than a missing
            // definition, so names like Вашингтон/Пекин/МИД don't count as gaps.
            val sentenceStart = i == 0 || gapBefore.any { it == '.' || it == '!' || it == '?' || it == '…' }
            val isProperNoun = note == null && !sentenceStart &&
                token.firstOrNull()?.isUpperCase() == true
            val freshStatus = note?.let { statusById[it.id] } ?: WordStatus.NEW
            val derivedKnown = note != null && note.id in known
            val status = when {
                note != null && freshStatus != WordStatus.NEW -> freshStatus
                derivedKnown -> WordStatus.KNOWN
                // Suggest that this may be a proper noun, but do not silently count
                // it as covered. The learner can explicitly mark it ignored.
                isProperNoun -> WordStatus.NEW
                else -> WordStatus.NEW
            }
            ReaderToken(
                surface = token,
                normalized = normalized,
                leading = leading,
                trailing = trailing,
                known = status == WordStatus.KNOWN || status == WordStatus.IGNORED,
                status = status,
                lemma = note?.lemma,
                parse = note?.let { parseToken(token, it) },
                aktionsart = note?.aktionsart,
                stressForm = note?.russian,
                translation = note?.translation ?: if (isProperNoun) "(proper noun)" else null,
                exampleSentence = note?.exampleSentence,
                exampleTranslation = note?.exampleTranslation,
                exampleSentence2 = note?.exampleSentence2,
                exampleTranslation2 = note?.exampleTranslation2,
                exampleSentence3 = note?.exampleSentence3,
                exampleTranslation3 = note?.exampleTranslation3
            )
        }
        }
    }

    /**
     * Explicitly set the reading status of a tapped word. Creates a lightweight
     * tracking note if the word isn't in the deck yet, so status survives.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun setWordStatus(token: String, status: WordStatus, now: Long = System.currentTimeMillis()): Note? {
        val normalized = normalizeToken(token)
        val match = formIndex()[normalized] ?: noteDao.getByLemma(normalized)
        if (match != null) {
            runInTransaction {
                // Re-read the live row so we don't write back a stale encounterCount.
                val fresh = noteDao.getById(match.id) ?: match
                noteDao.update(fresh.copy(status = status))
                // Relay the reader judgement to practice: a word marked KNOWN/IGNORED
                // stops being quizzed; marking it LEARNING/NEW pulls it back in cleanly.
                when (status) {
                    WordStatus.KNOWN, WordStatus.IGNORED ->
                        graduateVocabKnown(match.id, now)
                    WordStatus.LEARNING, WordStatus.NEW ->
                        cardDao.reactivateVocabForNote(match.id)
                }
            }
            invalidateNoteState()
            return noteDao.getById(match.id)
        }
        addNote(
            Note(
                russian = token,
                lemma = normalized,
                translation = "lookup pending",
                partOfSpeech = "unknown",
                status = status,
                tags = "reader_lookup"
            )
        )
        return noteDao.getByLemma(normalized)
    }

    /**
     * Mark the word behind a card as already known, straight from the review screen.
     * Graduates all of the note's vocab cards (so it won't resurface) and flips the
     * note status to KNOWN so the reader reflects it too — the same relay used when a
     * word is marked known while reading.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun markWordKnown(noteId: Long, now: Long = System.currentTimeMillis()) {
        runInTransaction {
            val note = noteDao.getById(noteId) ?: return@runInTransaction
            if (note.status != WordStatus.KNOWN) {
                noteDao.update(note.copy(status = WordStatus.KNOWN))
            }
            graduateVocabKnown(noteId, now)
        }
        invalidateNoteState()
    }

    /**
     * Graduate a note's VOCAB cards as "already known", writing a coherent FSRS state
     * (see [FsrsScheduler.markKnown]) instead of the degenerate all-zero state. Pushed
     * far out ([Long.MAX_VALUE] due) so a known word never resurfaces in practice. The
     * known-state constants live in [FsrsScheduler] so this path, the bulk
     * [placeAfterLevel] path, and the data-repair migration all agree.
     */
    private suspend fun graduateVocabKnown(noteId: Long, now: Long) {
        cardDao.graduateVocabForNote(
            noteId = noteId,
            due = Long.MAX_VALUE,
            now = now,
            stability = FsrsScheduler.KNOWN_STABILITY_DAYS,
            difficulty = FsrsScheduler.KNOWN_DIFFICULTY,
            scheduledDays = FsrsScheduler.KNOWN_STABILITY_DAYS.toInt()
        )
        // Match placeAfterLevel: a word graduated as "already known" must clear the
        // encounterCount == 0 gate too, or its grammar drills (which wait for first
        // encounter) can never be introduced and permanently stall unit progression.
        noteDao.getById(noteId)?.let { note ->
            if (note.encounterCount < VOCAB_GRADUATION_ENCOUNTERS) {
                noteDao.update(note.copy(encounterCount = VOCAB_GRADUATION_ENCOUNTERS))
            }
        }
    }

    suspend fun dashboardStats(
        now: Long = System.currentTimeMillis(),
        recommendations: List<ReaderRecommendation>? = null
    ): DashboardStats = dashboardStatsFrom(now, recommendations ?: readerTexts())

    private suspend fun dashboardStatsFrom(now: Long, recommendations: List<ReaderRecommendation>): DashboardStats {
        val notes = allNotesCached()
        val targetCoverages = recommendations.filter { it.text.source.startsWith("target:", ignoreCase = true) }.map { it.coverage }
        val qualityReport = ImportQualityReporter.report(notes, recommendations, AUTHENTIC_READY_COVERAGE)
        val retentionWindowStart = now - RETENTION_WINDOW_DAYS * DAY_MILLIS
        val matureReviews = reviewLogDao.matureReviewCount(retentionWindowStart)
        val matureRetained = reviewLogDao.matureRetainedCount(retentionWindowStart)
        val dueByDay = cardDao.countDueByDay(now, now + 7 * DAY_MILLIS, DAY_MILLIS)
            .associate { it.day to it.count }
        val forecast = List(7) { day -> dueByDay[day] ?: 0 }
        val goal = recommendations.filter { it.text.source.startsWith("target:", true) }.maxByOrNull { it.coverage }
        return DashboardStats(
            noteCount = notes.size,
            vocabCards = cardDao.countByQueue(Queue.VOCAB),
            grammarCards = cardDao.countByQueue(Queue.GRAMMAR),
            dueVocab = cardDao.countDueByQueue(now, Queue.VOCAB),
            dueGrammar = cardDao.countDueByQueue(now, Queue.GRAMMAR),
            reviewedToday = reviewedToday(now),
            averageReaderCoverage = recommendations.map { it.coverage }.average().takeIf { !it.isNaN() } ?: 0.0,
            bestTargetCoverage = targetCoverages.maxOrNull(),
            authenticReady = targetCoverages.any { it >= AUTHENTIC_READY_COVERAGE },
            importQualityReport = qualityReport,
            matureRetention = if (matureReviews > 0) matureRetained.toDouble() / matureReviews else null,
            matureReviewSample = matureReviews,
            leechCount = cardDao.getLeechCards(LEECH_LAPSES).size,
            dueForecast = forecast,
            goalProgress = goal?.let { GoalProgress(it.text.id, it.text.title, (it.coverage * 100).toInt(), (it.totalTokens - it.knownTokens).coerceAtLeast(0)) }
        )
    }

    suspend fun setReaderGoal(textId: Long): Boolean {
        val text = readerTextDao.getById(textId) ?: return false
        val changed = readerTextDao.updateSource(textId, "target:${text.title}") > 0
        if (changed) invalidateNoteState()
        return changed
    }

    suspend fun importQualityReport(): ImportQualityReport =
        ImportQualityReporter.report(allNotesCached(), readerTexts(), AUTHENTIC_READY_COVERAGE)

    suspend fun dailyPlan(now: Long = System.currentTimeMillis()): DailyPlan {
        val categories = accuracyCategoriesCached()
        return dailyPlanFromCategories(now, categories)
    }

    private suspend fun dailyPlanFromCategories(now: Long, categories: List<CategoryKey>): DailyPlan {
        val eligible = categories.filter { it.sampleSize >= MIN_ACCURACY_SAMPLE }
        val focus = eligible.sortedBy { it.accuracy ?: 1.0 }.take(3)
        val dueCount = cardDao.countDue(now)
        return DailyPlan(
            grammarFocus = focus,
            openBlockedWith = focus.firstOrNull(),
            dueVocab = cardDao.countDueByQueue(now, Queue.VOCAB),
            dueGrammar = cardDao.countDueByQueue(now, Queue.GRAMMAR),
            triageMode = dueCount > TRIAGE_THRESHOLD,
            overdueBacklog = cardDao.getOverdueCards(now - 2 * DAY_MILLIS, limit = 1).isNotEmpty()
        )
    }

    // The session plan interleaves DAO reads with the app's heaviest pure-Kotlin work
    // (per-card prompt/distractor construction, reader-coverage scans, dashboard
    // aggregation, problem-card audit). loadSession calls this from the main-thread
    // viewModelScope, so without this hop that CPU ran on the UI thread and dropped
    // ~100–200 frames at startup. Room's suspend DAOs keep their own executor, so the
    // reads still run correctly when dispatched from here.
    suspend fun sessionPlan(
        now: Long = System.currentTimeMillis(),
        includeReaderInsights: Boolean = true
    ): SessionPlan = withContext(computeDispatcher) {
        refreshGraduationsIfNeeded()
        ensureDailyMicroReading(now)
        syncReadingSchedules()
        val notesById = allNotesCached().associateBy { it.id }
        val categories = accuracyCategoriesCached()
        val daily = dailyPlanFromCategories(now, categories)
        val blocked = blockedGrammarPrompts(daily, now, notesById)
        // Compute once; reuse for both readerRecommendation and dashboardStats.
        // Reader coverage requires a morphology index over the whole deck and a scan
        // of every text. Startup can publish a useful plan first and enrich it later.
        val allTexts = if (includeReaderInsights) readerTexts(now) else emptyList()
        val reviewedNoteIds = reviewLogDao.getReviewedCardsSince(startOfLocalDay(now)).mapTo(HashSet()) { it.noteId }
        val consolidationReader = consolidationReader(allTexts, reviewedNoteIds)
        val mastery = unitMastery()
        val modelDao = learningModelDao
        val capacityState = modelDao?.capacityState()
        val willingnessState = modelDao?.willingnessState()
        val gamification = gamificationStats(now)
        val recentTelemetry = recentTelemetry(200)
        val estimatedFatigue = estimatedSessionFatigue(recentTelemetry)
        val recentPace = modelDao?.paceLogs(5).orEmpty()
        val lastPace = recentPace.firstOrNull()
        val priorPace = recentPace.getOrNull(1)
        val doctrineNudge = DoctrineAdvisor.suggest(recentPace, config().doctrine)
        val sessionsPerDayExpected = expectedSessionsPerDay(recentTelemetry)
        val medianReviewMinutes = medianReviewMinutes(recentTelemetry)
        val parameters = modelDao?.parameters().orEmpty()
        val parametersByKey = parameters.associateBy { it.key }
        val skillRatings = modelDao?.skillRatings().orEmpty()
        val rivalState = modelDao?.rivalState()
        val matchHistory = modelDao?.matchHistory(8).orEmpty()
        val productionSigma = skillRatings.firstOrNull { it.skill == AbilitySkill.PRODUCTION.name.lowercase() }?.sigma ?: TrueSkill.SIGMA0
        val activeCards = cardDao.getSchedulingCards()
        val establishedCardCount = cardDao.countEstablishedCards()
        val recentAccuracy = categories.mapNotNull { it.accuracy }.average().takeIf { !it.isNaN() } ?: 0.85
        val hasAdaptiveSignal = capacityState != null || willingnessState != null || recentTelemetry.any { it.eventType == "review_committed" || it.eventType == "session_complete" }
        val willingnessBelief = willingnessState?.let { WillingnessBelief(it.habit, parseWillingnessCoefficients(it.coeffsJson)) } ?: WillingnessBelief()
        val returnContext = ReturnContext(
            hoursSinceLastZ = lastPace?.let { ((now - it.at) / 3_600_000.0 - 36.0) / 24.0 }?.coerceIn(-3.0, 3.0) ?: 0.0,
            streakZ = ((gamification.currentStreak - 3.0) / 4.0).coerceIn(-3.0, 3.0),
            lastSessionFatigue = estimatedFatigue,
            lastDebtRatio = lastPace?.debtRatio ?: priorPace?.debtRatio ?: (daily.dueVocab.toDouble() / 100.0)
        )
        val capacityBelief = capacityState?.let { CapacityBelief(it.mu, it.sigma) } ?: CapacityBelief()
        val calibration = successCalibration(parameters)
        val worldState = com.sibirskyspeak.learning.LearnerWorldState(
            global = Gaussian(parametersByKey["global_skill_mu"]?.value ?: TrueSkill.MU0, parametersByKey["global_skill_sigma"]?.value ?: TrueSkill.SIGMA0),
            skills = skillRatings.mapNotNull { row ->
                runCatching { AbilitySkill.valueOf(row.skill.uppercase()) }.getOrNull()?.let { skill -> skill to Gaussian(row.mu, row.sigma) }
            }.toMap(),
            fatigue = estimatedFatigue
        )
        val pace = PaceController.generatePace(
            PaceInputs(
                capacity = capacityBelief,
                willingness = willingnessBelief,
                returnContext = returnContext,
                activeCards = activeCards,
                plannedNewFraction = config().newCardsPerDay.toDouble() / config().sessionSize.coerceAtLeast(1),
                totalKnown = establishedCardCount,
                recentAccuracy = recentAccuracy,
                fatigue = estimatedFatigue,
                productionSigma = productionSigma,
                medianReviewMinutes = medianReviewMinutes,
                sessionsPerDayExpected = sessionsPerDayExpected,
                decay = decayProvider(),
                tunedTargetRetention = parametersByKey["tuned_target_retention"]?.value,
                tunedNewBudgetScale = parametersByKey["tuned_new_budget_scale"]?.value ?: 1.0
            ),
            config().doctrine,
            now
        )
        val adoptedPace = PaceController.adoptForSessionSettings(
            pace = pace,
            configuredSessionSize = config().sessionSize,
            configuredNewCardsPerDay = config().newCardsPerDay,
            configuredRetention = config().desiredRetention,
            hasAdaptiveSignal = hasAdaptiveSignal
        )
        val generatedCapacity = adoptedPace.capacity
        val generatedNewBudget = adoptedPace.newBudget
        val generatedRetention = adoptedPace.retention
        val sessionMode = adoptedPace.mode
        val cards = applyContrastivePairing(sessionCards(now, generatedCapacity, daily, mastery, generatedNewBudget), now)
        // Difficulty is only consumed for cards in today's bounded plan. Loading the
        // entire historical table made startup scale with lifetime deck size.
        val itemDifficultyMap = if (cards.isEmpty()) emptyMap() else {
            modelDao?.difficultiesFor(cards.map { it.id }).orEmpty().associateBy { it.cardId }
        }
        val rawPrompts = cards.mapIndexedNotNull { index, card ->
            val reason = queueReason(card, index, cards, now, notesById)
            promptFor(card, now, notesById)?.let { prompt ->
                prompt.copy(
                    queueReason = reason,
                    teachingHint = if (reason.startsWith("Guided practice")) {
                        listOfNotNull("Use the worked example as a pattern", prompt.teachingHint).joinToString(" · ")
                    } else prompt.teachingHint
                )
            }
        }
        val blueprint = BlueprintBuilder.build(
            cards = cards,
            now = now,
            desiredRetention = generatedRetention,
            dailyNewCap = generatedNewBudget,
            capacity = generatedCapacity,
            backlog = daily.triageMode || daily.overdueBacklog,
            recentAccuracy = recentAccuracy,
            mode = sessionMode,
            decay = decayProvider(),
            successProbability = { card ->
                val difficulty = itemDifficultyMap[card.id] ?: ItemDifficulty(card.id)
                WorldModel.successProbability(card, difficulty, null, worldState, now, decay = decayProvider(), calibration = calibration)
            }
        )
        val confusablePairs = confusablePairDao.getAll().mapTo(linkedSetOf()) { it.firstNoteId to it.secondNoteId }
        val prompts = orderPrompts(
            rawPrompts,
            blueprint,
            now,
            confusablePairs,
            pace = pace,
            successProbability = { prompt ->
                val difficulty = itemDifficultyMap[prompt.card.id] ?: ItemDifficulty(prompt.card.id)
                WorldModel.successProbability(prompt.card, difficulty, null, worldState, now, decay = decayProvider(), calibration = calibration)
            },
            itemUncertainty = { prompt ->
                itemDifficultyMap[prompt.card.id]?.sigma ?: TrueSkill.SIGMA0
            },
            uncertaintyWeight = parametersByKey["tuned_uncertainty_weight"]?.value ?: 0.18
        )
        val readingAssignment = dueReadingAssignment(allTexts, consolidationReader, prompts.size, now, pace.readingInserts.firstOrNull())
        val introducedToday = reviewLogDao.countNewIntroducedSince(startOfLocalDay(now))
        val completion = when {
            daily.triageMode || daily.overdueBacklog -> DailyCompletion(DailyLearningStatus.BACKLOG_REMAINING, "Overdue review backlog remaining — new material is paused.", allTexts.isNotEmpty())
            prompts.isNotEmpty() || readingAssignment != null -> DailyCompletion(DailyLearningStatus.WORK_REMAINING, "Scheduled cards and connected reading are still available.", readingAssignment != null)
            introducedToday >= generatedNewBudget || cardDao.getNewCardsOrdered(1).isNotEmpty() -> DailyCompletion(DailyLearningStatus.NEW_LIMIT_REACHED, "Scheduled work complete; today's generated new-word budget is exhausted or remaining siblings are buried.", allTexts.isNotEmpty())
            else -> DailyCompletion(DailyLearningStatus.SCHEDULED_COMPLETE, "Scheduled work complete for today.", allTexts.isNotEmpty())
        }
        SessionPlan(
            ruleSummary = ruleSummaryFor(daily.openBlockedWith),
            reviewQueue = prompts,
            blockedGrammar = blocked,
            interleavedGrammar = interleavedGrammarPrompts(blocked.map { it.card.id }.toSet(), now, notesById),
            readerRecommendation = consolidationReader
                ?: allTexts.minWithOrNull(compareBy<ReaderRecommendation> { distanceFromTarget(it.coverage) }.thenByDescending { it.dueOverlap }.thenByDescending { it.coverage }),
            dashboardStats = dashboardStatsFrom(now, allTexts),
            skillRatings = skillRatings,
            rivalState = rivalState,
            matchHistory = matchHistory,
            dailyPlan = daily,
            gamification = gamification,
            completion = completion,
            unitMastery = mastery,
            readingReason = if (reviewedNoteIds.isNotEmpty() && allTexts.isNotEmpty()) {
                "Consolidates words practiced today in connected text"
            } else null,
            problemCards = problemCardAudit(notesById),
            consolidationLemmas = notesById.values.filter { it.id in reviewedNoteIds }.mapTo(linkedSetOf()) { it.lemma },
            readingAssignment = readingAssignment,
            blueprint = blueprint,
            pace = pace,
            confusablePairs = confusablePairs,
            doctrineNudge = doctrineNudge
        )
    }

    private suspend fun ensureDailyMicroReading(now: Long) {
        val cache = minedExampleDao ?: return
        val offset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val day = (now + offset) / DAY_MILLIS
        val source = "generated:micro:$day"
        if (readerTextDao.getAll().any { it.source == source }) return
        if (cache.getAll().size < 3) mineExampleGaps(limit = 96, now = now)
        val candidates = cache.getAll().filter { it.unknownCount <= 1 }.take(80)
        val chain = NarrowReadingGenerator.chain(candidates, 5)
        if (chain.size < 3) return
        val text = ReaderText(
            title = "Two-minute i+1 read",
            body = chain.joinToString("\n") { it.ru },
            source = source,
            createdAt = now
        )
        val id = readerTextDao.insert(text)
        readingScheduleDao?.insert(ReadingSchedule(readerTextId = id, due = now))
        telemetryDao?.insert(TelemetryEvent(
            timestamp = now, eventType = "narrow_read",
            metadataJson = JSONObject().put("readerTextId", id).put("sentences", chain.size).toString()
        ))
    }

    private fun orderPrompts(
        prompts: List<ReviewPrompt>,
        blueprint: com.sibirskyspeak.learning.SessionBlueprint,
        now: Long,
        confusablePairs: Set<Pair<Long, Long>>,
        pace: Pace,
        successProbability: (ReviewPrompt) -> Double,
        itemUncertainty: (ReviewPrompt) -> Double,
        uncertaintyWeight: Double
    ): List<ReviewPrompt> {
        val pool = prompts.toMutableList()
        val ordered = mutableListOf<ReviewPrompt>()
        var live = LiveSessionState(introducedConcepts = prompts.mapNotNull { it.card.gramConcept }.toSet())
        while (pool.isNotEmpty()) {
            val next = NextCardSelector.select(
                pool,
                blueprint,
                live,
                now,
                confusablePairs,
                targetDifficulty = pace.targetDifficulty,
                productionRatio = pace.productionRatio,
                successProbability = successProbability,
                itemUncertainty = itemUncertainty,
                uncertaintyWeight = uncertaintyWeight
            ) ?: pool.first()
            pool.remove(next)
            ordered += next
            live = live.copy(shown = live.shown + 1, recentNoteIds = (live.recentNoteIds + next.note.id).takeLast(4))
        }
        return ordered
    }

    private suspend fun dueReadingAssignment(
        texts: List<ReaderRecommendation>,
        consolidation: ReaderRecommendation?,
        cardCount: Int,
        now: Long,
        forcedInsertion: Int? = null
    ): ReadingAssignment? {
        val dao = readingScheduleDao ?: return null
        val due = dao.getAll().filter { it.due <= now }.associateBy { it.readerTextId }
        if (due.isEmpty()) return null
        val readable = texts.filter { it.text.id in due && it.coverage >= MIN_READER_COVERAGE }
        val recommendation = consolidation?.takeIf { it.text.id in due && it.coverage >= MIN_READER_COVERAGE }
            ?: readable.minWithOrNull(compareBy<ReaderRecommendation> { distanceFromTarget(it.coverage) }.thenByDescending { it.dueOverlap }.thenBy { due[it.text.id]?.reps ?: 0 })
            ?: return null
        val insertion = forcedInsertion?.coerceIn(0, cardCount.coerceAtLeast(0))
            ?: when {
                cardCount <= 1 -> 0
                cardCount <= 4 -> 1
                else -> (cardCount / 3).coerceIn(3, cardCount - 1)
            }
        val schedule = due.getValue(recommendation.text.id)
        return ReadingAssignment(recommendation, schedule, insertion, mode = ReadingMode.forRep(schedule.reps))
    }

    /** Grade the connected-text checkpoint and set the next distributed reading. */
    suspend fun completeScheduledReading(
        readerTextId: Long,
        mistakes: Int,
        abandoned: Boolean = false,
        now: Long = System.currentTimeMillis()
    ) {
        val dao = readingScheduleDao ?: return
        val existing = dao.get(readerTextId)
        val current = existing ?: ReadingSchedule(readerTextId)
        val passedCleanly = !abandoned && mistakes == 0
        val nextReps = if (abandoned) current.reps else current.reps + 1
        val baseDays = READING_INTERVALS[nextReps.coerceIn(1, READING_INTERVALS.lastIndex)]
        val interval = when {
            abandoned -> 1
            passedCleanly -> baseDays
            mistakes <= 2 -> maxOf(1, baseDays / 2)
            else -> 1
        }
        runInTransaction {
            val next = current.copy(
                due = now + interval * DAY_MILLIS,
                intervalDays = interval,
                reps = nextReps,
                lapses = current.lapses + if (abandoned || mistakes > 2) 1 else 0,
                lastCompleted = if (abandoned) current.lastCompleted else now
            )
            if (existing == null) dao.insert(next) else dao.update(next)
            if (!abandoned) {
                readingActivityDao?.insert(ReadingActivity(
                    readerTextId = readerTextId,
                    completedAt = now,
                    mistakes = mistakes,
                    intervalDays = interval
                ))
                telemetryDao?.insert(TelemetryEvent(
                    timestamp = now,
                    eventType = "scheduled_reading_completed",
                    metadataJson = JSONObject()
                        .put("readerTextId", readerTextId)
                        .put("mistakes", mistakes)
                        .put("intervalDays", interval)
                        .toString()
                ))
                val source = readerTextDao.getById(readerTextId)?.source.orEmpty()
                if (source.startsWith("generated:micro:")) telemetryDao?.insert(TelemetryEvent(
                    timestamp = now, eventType = "reading_microsession",
                    metadataJson = JSONObject().put("readerTextId", readerTextId).put("mistakes", mistakes).toString()
                ))
            }
        }
        if (!abandoned) runCatching { creditReadingEvidence(readerTextId, now) }
    }

    /**
     * Passive reading evidence (P5.4, closes the input loop): for every note in
     * this text whose card is due within a week, nudge it via the P0.2 evidence bus
     * — a word never looked up during this reading is weak positive evidence it's
     * holding; a word that WAS tapped for its meaning is weak negative (the lookup
     * itself is never evidence — see EvidenceEvent's guard — but needing it is).
     * recordEvidence enforces the cap (≤1/card/day) and never changes card state,
     * so this can only ever nudge an interval, never graduate or fail a card.
     */
    private suspend fun creditReadingEvidence(readerTextId: Long, now: Long) {
        val text = readerTextDao.getById(readerTextId) ?: return
        val index = formIndex()
        val noteIds = readerWordOccurrences(text.body)
            .mapNotNull { index[normalizeToken(it.surface)]?.id }
            .distinct()
        if (noteIds.isEmpty()) return
        val dueSoonNoteIds = cardDao.getCardsForNotes(noteIds)
            .filter { !it.suspended && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && it.due <= now + 7 * DAY_MILLIS }
            .map { it.noteId }
            .toSet()
        if (dueSoonNoteIds.isEmpty()) return
        val lookedUp = readerEncounterDao?.getForText(readerTextId)?.mapTo(HashSet()) { it.noteId }.orEmpty()
        for (noteId in dueSoonNoteIds) {
            recordEvidence(EvidenceEvent(
                noteId = noteId,
                facet = com.sibirskyspeak.learning.LearningFacet.CONTEXT,
                strength = EvidenceStrength.PRACTICE,
                correct = noteId !in lookedUp,
                source = ReviewSource.READING,
                at = now
            ))
        }
    }

    private suspend fun problemCardAudit(notesById: Map<Long, Note>): List<ProblemCardSummary> =
        cardDao.getProblemCards(limit = 8).mapNotNull { card ->
            val note = notesById[card.noteId] ?: return@mapNotNull null
            ProblemCardSummary(
                cardId = card.id,
                russian = note.russian,
                conciseMeaning = note.translation.split(',', ';').first().trim(),
                cardType = card.cardType,
                reviews = card.reps,
                lapses = card.lapses,
                difficulty = card.difficulty,
                recommendation = when {
                    note.partOfSpeech.lowercase() in setOf("preposition", "conjunction", "particle") -> "Use one sentence-specific meaning"
                    card.cardType == CardType.MEANING_TO_RU -> "Step back to recognition repair"
                    card.lapses >= LEECH_LAPSES - 1 -> "Edit or suspend before more drilling"
                    else -> "Keep in the repair loop with extra context"
                }
            )
        }

    private suspend fun consolidationReader(
        texts: List<ReaderRecommendation>,
        reviewedNoteIds: Set<Long>
    ): ReaderRecommendation? {
        if (reviewedNoteIds.isEmpty()) return null
        val index = formIndex()
        return texts.map { recommendation ->
            val overlap = readerWordOccurrences(recommendation.text.body).count { occurrence ->
                index[normalizeToken(occurrence.surface)]?.id in reviewedNoteIds
            }
            recommendation to overlap
        }.filter { it.second >= 2 }
            .maxWithOrNull(compareBy<Pair<ReaderRecommendation, Int>> { it.second }.thenBy { it.first.coverage })
            ?.first
    }

    /**
     * P6.5 (curriculum DAG, scoped): strict-linear "every prior unit 100% first"
     * gating is replaced by a sliding window off the current frontier unit — once
     * the learner has genuinely started that unit (not just arrived at it), the
     * next [UNIT_SLIDING_WINDOW] units open too, instead of staying hard-blocked
     * behind full mastery of everything before them. A fully authored per-unit
     * prerequisite-concept graph (tools/preprocess/units.yaml) is a further
     * extension; this generalizes the existing single-unit preview peek using the
     * same progress signal the old linear chain already computed.
     */
    private suspend fun unitMastery(): List<UnitMastery> {
        val notes = allNotesCached().filter { it.tier == 0 && it.unit != null && it.status != WordStatus.IGNORED }
        val byId = notes.associateBy { it.id }
        val vocab = cardDao.getAllVocabCards().filter { it.noteId in byId && it.cardType == CardType.RU_TO_MEANING && !it.suspended }
        // A note marked KNOWN never surfaces new cards of any type (see
        // getNewCardsOrdered), so its own grammar drills (e.g. SENTENCE_BUILD) can never
        // be practiced. Counting them toward the unit's grammar total left that unit's
        // progress permanently short of the mastery threshold, locking every unit after
        // it — exclude them from the denominator the same way IGNORED notes already are.
        val grammar = cardDao.getAllGrammarCards().filter {
            it.noteId in byId && it.cardType != CardType.LESSON && !it.suspended && byId[it.noteId]?.status != WordStatus.KNOWN
        }
        val raw = notes.groupBy { it.unit!! }.toSortedMap().map { (unit, unitNotes) ->
            val ids = unitNotes.mapTo(HashSet()) { it.id }
            val unitVocab = vocab.filter { it.noteId in ids }
            val unitGrammar = grammar.filter { it.noteId in ids }
            UnitMastery(
                unit = unit,
                vocabularyMastered = unitVocab.count {
                    it.state == CardState.GRADUATED || (it.reps >= 2 && it.consecutiveCorrect >= 2)
                },
                vocabularyTotal = unitVocab.size,
                grammarMastered = unitGrammar.count { it.reps >= 2 && it.consecutiveCorrect >= 2 },
                grammarTotal = unitGrammar.size,
                unlocked = false
            )
        }
        val frontierIndex = raw.indexOfFirst { it.progress < UNIT_MASTERY_THRESHOLD }.let { if (it < 0) raw.size else it }
        val frontierStarted = raw.getOrNull(frontierIndex)?.let { it.vocabularyMastered > 0 || it.grammarMastered > 0 } ?: false
        return raw.mapIndexed { index, mastery ->
            val unlocked = index <= frontierIndex || (frontierStarted && index <= frontierIndex + UNIT_SLIDING_WINDOW)
            mastery.copy(unlocked = unlocked)
        }
    }

    suspend fun gamificationStats(now: Long = System.currentTimeMillis()): GamificationStats {
        val dailyGoal = config().dailyGoal
        val tzOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val days = (reviewLogDao.reviewDayBuckets(tzOffset, DAY_MILLIS) +
            readingActivityDao?.dayBuckets(tzOffset, DAY_MILLIS).orEmpty()).distinct()
        val inputDays = readingActivityDao?.dayBuckets(tzOffset, DAY_MILLIS).orEmpty().toHashSet()
        val daySet = days.toHashSet()
        val todayBucket = (now + tzOffset) / DAY_MILLIS

        // Current streak: count back from today (or yesterday, if nothing yet today).
        var currentStreak = 0
        if (todayBucket in daySet || (todayBucket - 1) in daySet) {
            var day = if (todayBucket in daySet) todayBucket else todayBucket - 1
            var insuredGap = config().restDayCredits > 0
            while (day in daySet || (insuredGap && (day - 1) in daySet)) {
                if (day in daySet) currentStreak += 1 else { currentStreak += 1; insuredGap = false }
                day -= 1
            }
        }
        // Longest streak: scan all active days ascending for the longest run.
        var longestStreak = 0
        var run = 0
        var previous: Long? = null
        for (day in days.sorted()) {
            val previousDay = previous
            run = if (previousDay != null && day == previousDay + 1) run + 1 else 1
            if (run > longestStreak) longestStreak = run
            previous = day
        }
        var inputStreak = 0
        if (todayBucket in inputDays || (todayBucket - 1) in inputDays) {
            var inputDay = if (todayBucket in inputDays) todayBucket else todayBucket - 1
            while (inputDay in inputDays) { inputStreak++; inputDay-- }
        }

        val totalReviews = reviewLogDao.countAll()
        val xp = reviewLogDao.weightedXp() + (readingActivityDao?.countAll() ?: 0) * READING_XP
        // Level L costs L * XP_PER_LEVEL_STEP to advance; spend xp level by level.
        var level = 1
        var remaining = xp
        while (remaining >= level * XP_PER_LEVEL_STEP) {
            remaining -= level * XP_PER_LEVEL_STEP
            level += 1
        }
        // Reader coverage treats IGNORED noise/names as covered, but the learner's
        // "words known" total and achievements must only count learned vocabulary.
        val ignoredIds = allNotesCached().asSequence()
            .filter { it.status == WordStatus.IGNORED }
            .map { it.id }
            .toHashSet()
        val knownWords = knownNoteIds().count { it !in ignoredIds }
        val reviewedToday = reviewedToday(now)
        val activeDays = days.size
        val last7 = (6 downTo 0).map { offset -> (todayBucket - offset) in daySet }

        val achievements = listOf(
            // --- Getting started ---
            achievement("first_review", "Liftoff", "Do your first review", totalReviews >= 1),
            achievement("first_words", "First Words", "Know 10 words", knownWords >= 10),
            achievement("goal_met", "On Target", "Hit a daily goal", reviewedToday >= dailyGoal || activeDays >= 1),
            // --- Words known ---
            achievement("words_50", "Getting Going", "Know 50 words", knownWords >= 50),
            achievement("wordsmith", "Wordsmith", "Know 100 words", knownWords >= 100),
            achievement("words_250", "Wordhoard", "Know 250 words", knownWords >= 250),
            achievement("lexicon", "Lexicon", "Know 500 words", knownWords >= 500),
            achievement("words_750", "Shelf Builder", "Know 750 words", knownWords >= 750),
            achievement("polyglot", "Polyglot", "Know 1,000 words", knownWords >= 1000),
            achievement("words_1500", "Phrase Finder", "Know 1,500 words", knownWords >= 1500),
            achievement("words_2000", "Erudite", "Know 2,000 words", knownWords >= 2000),
            achievement("words_3000", "Deep Reader", "Know 3,000 words", knownWords >= 3000),
            achievement("words_5000", "Native Range", "Know 5,000 words", knownWords >= 5000),
            achievement("words_7500", "Library Mind", "Know 7,500 words", knownWords >= 7500),
            achievement("words_10000", "Ten Thousand Words", "Know 10,000 words", knownWords >= 10000),
            // --- Reviews done ---
            achievement("rev_10", "Warming Up", "10 reviews", totalReviews >= 10),
            achievement("rev_25", "First Lap", "25 reviews", totalReviews >= 25),
            achievement("rev_50", "In the Groove", "50 reviews", totalReviews >= 50),
            achievement("centurion", "Centurion", "100 reviews", totalReviews >= 100),
            achievement("rev_250", "Steady Hands", "250 reviews", totalReviews >= 250),
            achievement("rev_500", "Workhorse", "500 reviews", totalReviews >= 500),
            achievement("dedicated", "Dedicated", "1,000 reviews", totalReviews >= 1000),
            achievement("rev_2500", "Review Engine", "2,500 reviews", totalReviews >= 2500),
            achievement("rev_5000", "Relentless", "5,000 reviews", totalReviews >= 5000),
            achievement("rev_10000", "Machine", "10,000 reviews", totalReviews >= 10000),
            achievement("rev_25000", "Unstoppable", "25,000 reviews", totalReviews >= 25000),
            achievement("rev_50000", "Memory Forge", "50,000 reviews", totalReviews >= 50000),
            // --- Streaks ---
            achievement("streak_3", "Habit Forming", "3-day streak", longestStreak >= 3),
            achievement("week_warrior", "Week Warrior", "7-day streak", longestStreak >= 7),
            achievement("streak_10", "Ten-Day Trail", "10-day streak", longestStreak >= 10),
            achievement("streak_14", "Fortnight", "14-day streak", longestStreak >= 14),
            achievement("streak_21", "Three-Week Run", "21-day streak", longestStreak >= 21),
            achievement("month_master", "Month Master", "30-day streak", longestStreak >= 30),
            achievement("streak_45", "Long Haul", "45-day streak", longestStreak >= 45),
            achievement("streak_60", "Two Months", "60-day streak", longestStreak >= 60),
            achievement("streak_90", "Seasoned", "90-day streak", longestStreak >= 90),
            achievement("streak_100", "Century Streak", "100-day streak", longestStreak >= 100),
            achievement("streak_200", "Iron Will", "200-day streak", longestStreak >= 200),
            achievement("streak_300", "Almost a Year", "300-day streak", longestStreak >= 300),
            achievement("streak_365", "Year of Russian", "365-day streak", longestStreak >= 365),
            achievement("streak_500", "Unbroken Path", "500-day streak", longestStreak >= 500),
            // --- Levels ---
            achievement("level_5", "Apprentice", "Reach level 5", level >= 5),
            achievement("level_10", "Adept", "Reach level 10", level >= 10),
            achievement("level_15", "Climber", "Reach level 15", level >= 15),
            achievement("level_20", "Expert", "Reach level 20", level >= 20),
            achievement("level_30", "Specialist", "Reach level 30", level >= 30),
            achievement("level_40", "Veteran", "Reach level 40", level >= 40),
            achievement("level_50", "Master", "Reach level 50", level >= 50),
            achievement("level_75", "Sage", "Reach level 75", level >= 75),
            achievement("level_100", "Grandmaster", "Reach level 100", level >= 100),
            achievement("level_150", "Legend", "Reach level 150", level >= 150),
            // --- XP ---
            achievement("xp_1k", "Spark", "Earn 1,000 XP", xp >= 1000),
            achievement("xp_5k", "Glow", "Earn 5,000 XP", xp >= 5000),
            achievement("xp_10k", "Charged", "Earn 10,000 XP", xp >= 10000),
            achievement("xp_25k", "Voltage", "Earn 25,000 XP", xp >= 25000),
            achievement("xp_50k", "High Current", "Earn 50,000 XP", xp >= 50000),
            achievement("xp_100k", "Overcharged", "Earn 100,000 XP", xp >= 100000),
            achievement("xp_250k", "Powerhouse", "Earn 250,000 XP", xp >= 250000),
            achievement("xp_500k", "Lightning Mind", "Earn 500,000 XP", xp >= 500000),
            // --- Consistency (active days) ---
            achievement("days_10", "Regular", "10 active days", activeDays >= 10),
            achievement("days_25", "Showing Up", "25 active days", activeDays >= 25),
            achievement("days_50", "Committed", "50 active days", activeDays >= 50),
            achievement("days_75", "Clockwork", "75 active days", activeDays >= 75),
            achievement("days_100", "Devoted", "100 active days", activeDays >= 100),
            achievement("days_150", "Deep Roots", "150 active days", activeDays >= 150),
            achievement("days_250", "Long Game", "250 active days", activeDays >= 250),
            achievement("days_365", "All-Year Learner", "365 active days", activeDays >= 365),
            // --- Daily intensity ---
            achievement("goal_plus_10", "Extra Push", "Daily goal +10", reviewedToday >= dailyGoal + 10),
            achievement("goal_double", "Overachiever", "Double the daily goal", reviewedToday >= dailyGoal * 2),
            achievement("goal_triple", "Marathon", "Triple the daily goal", reviewedToday >= dailyGoal * 3),
            achievement("goal_quad", "Big Day", "Quadruple the daily goal", reviewedToday >= dailyGoal * 4),
            achievement("goal_100_today", "Hundred-Card Day", "Review 100 cards today", reviewedToday >= 100),
            achievement("goal_200_today", "Two-Hundred Day", "Review 200 cards today", reviewedToday >= 200)
        )

        return GamificationStats(
            knownWords = knownWords,
            totalReviews = totalReviews,
            xp = xp,
            level = level,
            xpIntoLevel = remaining,
            xpForLevel = level * XP_PER_LEVEL_STEP,
            currentStreak = currentStreak,
            inputStreak = inputStreak,
            longestStreak = longestStreak,
            reviewedToday = reviewedToday,
            dailyGoal = dailyGoal,
            activeDays = days.size,
            last7Days = last7,
            achievements = achievements,
            restDayCredits = config().restDayCredits
        )
    }

    private fun achievement(id: String, title: String, description: String, unlocked: Boolean) =
        Achievement(id, title, description, unlocked)

    /** Cheap stats for the daily reminder notification (no form-index build). */
    suspend fun reminderInfo(now: Long = System.currentTimeMillis()): ReminderInfo {
        val tzOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val daySet = (reviewLogDao.reviewDayBuckets(tzOffset, DAY_MILLIS) +
            readingActivityDao?.dayBuckets(tzOffset, DAY_MILLIS).orEmpty()).toHashSet()
        val todayBucket = (now + tzOffset) / DAY_MILLIS
        var streak = 0
        if (todayBucket in daySet || (todayBucket - 1) in daySet) {
            var day = if (todayBucket in daySet) todayBucket else todayBucket - 1
            while (day in daySet) { streak += 1; day -= 1 }
        }
        val hours = reviewLogDao.recentReviewTimes().map {
            java.util.Calendar.getInstance().apply { timeInMillis = it }.get(java.util.Calendar.HOUR_OF_DAY)
        }.sorted()
        val due = cardDao.countDue(now) + if (readingScheduleDao?.nextDue(now) != null) 1 else 0
        return ReminderInfo(
            currentStreak = streak,
            studiedToday = todayBucket in daySet,
            dueToday = due,
            estimatedMinutes = kotlin.math.ceil(due * 0.35).toInt(),
            preferredHour = hours.getOrNull(hours.size / 2) ?: SettingsStore.DEFAULT_REMINDER_HOUR
        )
    }

    suspend fun createWeeklyReport(now: Long = System.currentTimeMillis()): WeeklyReport? {
        val dao = weeklyReportDao ?: return null
        val stats = gamificationStats(now)
        val retention = retentionByCardType(now - 7 * DAY_MILLIS)
        val worst = retention.filter { it.total >= 3 }.minByOrNull { it.retained.toDouble()/it.total }
        val confusion = topConfusionPair(now)
        val body = JSONObject().put("reviews",stats.reviewedToday).put("activeDays",stats.last7Days.count { it })
            .put("retention", if (retention.sumOf { it.total } == 0) JSONObject.NULL else retention.sumOf { it.retained }.toDouble()/retention.sumOf { it.total })
            .put("attention",worst?.cardType?.name ?: "Keep the current rhythm")
            .put("recommendation", if (stats.last7Days.count { it } >= 5) "Keep the contract steady." else "Lower the activation floor with one micro-session.")
            .put("topConfusion", confusion?.let { "${it.expectedKey} vs ${it.producedKey} (${it.cardType.name}, ${it.count}x)" } ?: JSONObject.NULL)
            .toString()
        val report=WeeklyReport(generatedAt=now,periodStart=now-7*DAY_MILLIS,bodyJson=body)
        return report.copy(id=dao.insert(report))
    }
    suspend fun weeklyReports(): List<WeeklyReport> = weeklyReportDao?.recent().orEmpty()

    /** Persists a confusion classification (P4.5, review/AnswerDiagnosis.kt). */
    suspend fun recordConfusionEvent(diagnosis: com.sibirskyspeak.review.Diagnosis, cardType: CardType, now: Long = System.currentTimeMillis()) {
        confusionEventDao?.insert(ConfusionEvent(expectedKey = diagnosis.expectedKey, producedKey = diagnosis.producedKey, cardType = cardType, at = now))
    }

    /** The most-recurring (expectedKey, producedKey, cardType) confusion in the
     * trailing window, or null if none recurred enough to be worth reporting. */
    suspend fun topConfusionPair(now: Long = System.currentTimeMillis(), windowDays: Long = CONFUSION_WINDOW_DAYS, minCount: Int = CONFUSION_MIN_EVENTS): ConfusionPairCount? =
        confusionEventDao?.topPairSince(now - windowDays * DAY_MILLIS)?.takeIf { it.count >= minCount }

    /** Same key space review/AnswerDiagnosis.kt classifies against, read back off a
     * scheduled card so a recurring confusion pair can be paired for contrastive
     * back-to-back practice (P4.5) using cards already in today's plan — no new
     * content generation needed. */
    private fun confusionKeyFor(card: Card): String? = when (card.cardType) {
        CardType.CASE_FILL -> listOfNotNull(card.gramCase, card.gramNumber ?: "SG").joinToString("_").uppercase()
        CardType.VERB_FORM -> card.gramContextCue
        CardType.ADJ_AGREE -> when (card.gramContextCue) {
            "FEM" -> "FEM_NOM"
            "NEUT" -> "NEUT_NOM"
            "PL" -> "PL_NOM"
            else -> "NOM_SG"
        }
        else -> null
    }

    /** If a confusion pair recurred enough in the trailing window and today's plan
     * already contains a card for each side of it, moves the "produced" (wrongly
     * given) card to sit immediately after the "expected" (correct) card so the
     * learner drills the contrast back-to-back. Leaves [cards] unchanged otherwise —
     * this never fabricates a card, only reorders ones already selected. */
    private suspend fun applyContrastivePairing(cards: List<Card>, now: Long): List<Card> {
        val pair = topConfusionPair(now) ?: return cards
        val expected = cards.firstOrNull { it.cardType == pair.cardType && confusionKeyFor(it) == pair.expectedKey }
        val produced = cards.firstOrNull { it.cardType == pair.cardType && confusionKeyFor(it) == pair.producedKey }
        if (expected == null || produced == null || expected.id == produced.id) return cards
        val without = cards.filterNot { it.id == produced.id }
        val insertAt = without.indexOfFirst { it.id == expected.id } + 1
        return without.toMutableList().apply { add(insertAt, produced) }
    }

    /**
     * Assembles the monthly checkpoint (P6.4): the app's only independent, unbiased
     * assessment. Ordinary evidence all comes from scheduler-chosen moments, which
     * biases everything optimistic — this session writes no FSRS state at all (no
     * card here is ever reviewed by [recordCheckpointResult]; only [checkpointResultDao]
     * is written), so it's the sole honest measure of whether "known" is real.
     */
    suspend fun buildCheckpointSession(now: Long = System.currentTimeMillis(), graduatedSampleSize: Int = 20, novelFrameSampleSize: Int = 10): CheckpointSession {
        val items = graduatedRecallCheckpointItems(now, graduatedSampleSize) + novelFrameCheckpointItems(now, novelFrameSampleSize)
        return CheckpointSession(items = items, generatedAt = now)
    }

    /** 20 graduated notes sampled uniformly over graduation age (stratified across
     * the full oldest-to-newest range, not just the most recent), each paired with
     * the scheduler's own predicted retrievability for later calibration. */
    private suspend fun graduatedRecallCheckpointItems(now: Long, sampleSize: Int): List<CheckpointItem> {
        if (sampleSize <= 0) return emptyList()
        val sorted = cardDao.getGraduatedRecognitionCards()
        if (sorted.isEmpty()) return emptyList()
        val picks = if (sorted.size <= sampleSize) sorted else {
            val span = (sorted.size - 1).coerceAtLeast(1)
            (0 until sampleSize).map { i -> sorted[i * span / (sampleSize - 1).coerceAtLeast(1)] }.distinct()
        }
        val decay = decayProvider()
        return picks.mapNotNull { card ->
            val note = noteDao.getById(card.noteId) ?: return@mapNotNull null
            val elapsedDays = ((now - (card.lastReview ?: now)).coerceAtLeast(0) / DAY_MILLIS.toDouble())
            val predicted = FsrsScheduler.retrievabilityOf(elapsedDays, card.stability, decay)
            CheckpointItem(itemKey = "note:${note.id}", kind = "graduated_recall", prompt = note.russian, expectedAnswer = note.translation, predictedP = predicted)
        }
    }

    /** 10 sentences realized over the learner's own known inventory (same
     * FrameRealizer as CONCEPT_APPLY/NOVEL_PRODUCE) but seeded independently of any
     * card id, so this can never coincide with — or be gamed by — a regular review. */
    private suspend fun novelFrameCheckpointItems(now: Long, sampleSize: Int): List<CheckpointItem> {
        if (sampleSize <= 0) return emptyList()
        val dao = contentDao ?: return emptyList()
        val realizer = frameRealizer ?: return emptyList()
        val frames = dao.allFrames()
        if (frames.isEmpty()) return emptyList()
        val inventory = frameInventory()
        val epochDay = now / DAY_MILLIS
        return frames.shuffled(kotlin.random.Random(now)).take(sampleSize).mapIndexedNotNull { index, frame ->
            val realized = realizer.realize(frame, inventory, epochDay, cardId = -(index + 1L)) ?: return@mapIndexedNotNull null
            CheckpointItem(itemKey = "frame:${frame.id}:$index", kind = "novel_frame", prompt = realized.en, expectedAnswer = realized.ru, predictedP = null)
        }
    }

    /** Persists one checkpoint answer. Deliberately writes nothing but
     * [checkpointResultDao] — no card, no review log, no FSRS state of any kind. */
    suspend fun recordCheckpointResult(item: CheckpointItem, correct: Boolean, now: Long = System.currentTimeMillis()) {
        checkpointResultDao?.insert(CheckpointResult(at = now, itemKey = item.itemKey, kind = item.kind, predictedP = item.predictedP, correct = correct))
    }

    /** Predicted-vs-observed retrievability, bucketed in deciles — the calibration
     * curve the weekly/Lab report surfaces. Only "graduated_recall" results carry a
     * predictedP (novel-frame items have no prior FSRS estimate to compare against). */
    suspend fun checkpointCalibration(now: Long = System.currentTimeMillis(), windowDays: Long = 180L): List<CalibrationBucket> {
        val dao = checkpointResultDao ?: return emptyList()
        val results = dao.since(now - windowDays * DAY_MILLIS).filter { it.predictedP != null }
        return results.groupBy { (((it.predictedP ?: 0.0) * 10).toInt().coerceIn(0, 9)) / 10.0 }
            .map { (bucket, rows) -> CalibrationBucket(bucket, rows.count { it.correct }.toDouble() / rows.size, rows.size) }
            .sortedBy { it.predictedBucket }
    }

    suspend fun nextPrompt(now: Long = System.currentTimeMillis()): ReviewPrompt? =
        sessionPlan(now).reviewQueue.firstOrNull()

    suspend fun gradeInlineEnglish(cardId: Long, answer: String, now: Long = System.currentTimeMillis()): Boolean? {
        val card = cardDao.getByIds(listOf(cardId)).firstOrNull() ?: return null
        val prompt = promptForCard(card, now) ?: return null
        if (prompt.answerMode != AnswerMode.ENGLISH) return null
        val correct = isEnglishAnswerCorrect(prompt.expectedAnswer, answer)
        review(card, if (correct) Rating.GOOD else Rating.AGAIN, now, objectiveCorrect = correct)
        return correct
    }

    /** Build a review prompt for a specific card (used to re-present after undo). */
    suspend fun promptForCard(card: Card, now: Long = System.currentTimeMillis()): ReviewPrompt? =
        promptFor(
            cardDao.getCardsForNote(card.noteId).firstOrNull { it.id == card.id } ?: card,
            now
        )

    /** Build a frozen session queue with one note-cache read instead of one Room lookup per card. */
    suspend fun promptsForCards(cards: List<Card>, now: Long = System.currentTimeMillis()): List<ReviewPrompt> {
        if (cards.isEmpty()) return emptyList()
        val notesById = allNotesCached().associateBy { it.id }
        val liveById = cardDao.getByIds(cards.map { it.id }.distinct()).associateBy { it.id }
        return cards.mapNotNull { snapshot ->
            val live = liveById[snapshot.id] ?: return@mapNotNull null
            if (live.suspended || live.state == CardState.GRADUATED) return@mapNotNull null
            promptFor(live, now, notesById)
        }
    }

    suspend fun promptsForCardIds(cardIds: List<Long>, now: Long = System.currentTimeMillis()): List<ReviewPrompt> =
        promptsForCards(cardDao.getByIds(cardIds), now)

    /** Build a non-scheduling acquisition recall while rotating through examples. */
    suspend fun practicePromptFor(card: Card, round: Int, now: Long = System.currentTimeMillis()): ReviewPrompt? {
        val live = cardDao.getCardsForNote(card.noteId).firstOrNull { it.id == card.id } ?: card
        return promptFor(live.copy(reps = live.reps + round.coerceAtLeast(1)), now)?.copy(practiceOnly = true)
    }

    /**
     * Debug-only: a prompt for an existing card of [cardType], if any exists in the
     * current database, so a debug build's card gallery can jump straight to a card
     * type. Manually reaching a rare type (a specific grammar drill, say) can otherwise
     * take dozens of turns through the adaptive session before it's naturally selected.
     */
    suspend fun debugPromptForCardType(cardType: CardType, now: Long = System.currentTimeMillis()): ReviewPrompt? {
        val candidate = cardDao.getSampleCardsOfType(cardType, limit = 1).firstOrNull() ?: return null
        return promptFor(candidate, now)
    }

    /** Production failures step back to recognition; other misses repeat the
     * precise skill that failed. */
    suspend fun repairPromptFor(card: Card, now: Long = System.currentTimeMillis()): ReviewPrompt? {
        val desired = when (card.cardType) {
            CardType.MEANING_TO_RU, CardType.CLOZE, CardType.SENTENCE_BUILD -> CardType.RU_TO_MEANING
            else -> card.cardType
        }
        val repair = cardDao.getCardsForNote(card.noteId)
            .firstOrNull { it.cardType == desired && !it.suspended } ?: card
        return promptFor(repair, now)
    }

    suspend fun scaffoldPromptFor(card: Card, supportLevel: Int, now: Long = System.currentTimeMillis()): ReviewPrompt? {
        val live = cardDao.getCardsForNote(card.noteId).firstOrNull { it.id == card.id } ?: card
        val rawNote = noteDao.getById(live.noteId) ?: return null
        val (note, _) = promptNote(rawNote)
        val meaning = buildPrompt(live.copy(cardType = CardType.RU_TO_MEANING), note, emptyMap()).expectedAnswer
        val exampleRu = note.exampleSentence.orEmpty()
        val exampleEn = note.exampleTranslation.orEmpty()
        val mnemonic = note.mnemonic?.takeIf { it.isNotBlank() }
        val supportLine = when (supportLevel) {
            2 -> "First-letter cue: ${note.russian.firstOrNull() ?: '—'}…"
            3 -> "Word skeleton: ${note.russian.map { if (it in "аеёиоуыэюяАЕЁИОУЫЭЮЯ") it else '·' }.joinToString("")}"
            else -> "Full form: ${note.russian}"
        }
        val content = LessonContent(
            title = "Reset and reconnect: ${note.russian}",
            body = listOfNotNull(
                meaningLine(meaning),
                mnemonicLine(mnemonic),
                supportLine,
                "Read the example, then retrieve it again after a short gap."
            ),
            exampleRu = exampleRu,
            exampleEn = exampleEn
        )
        return ReviewPrompt(
            card = live,
            note = note,
            prompt = content.title,
            expectedAnswer = "Continue",
            answerMode = AnswerMode.LESSON,
            intervalPreview = scheduler.preview(live, now),
            teachingHint = if (mnemonic == null && live.lapses >= 2) "Hint $supportLevel · add a memory hook if this keeps failing" else "Graduated hint $supportLevel",
            lesson = content,
            queueReason = "Adaptive scaffold after repeated misses",
            supportOnly = true,
            supportLevel = supportLevel
        )
    }

    suspend fun grammarDrillPrompts(now: Long = System.currentTimeMillis(), limit: Int = 10): List<ReviewPrompt> {
        val plan = sessionPlan(now)
        return (plan.blockedGrammar + plan.interleavedGrammar).take(limit)
    }

    /**
     * Apply a rating to a card. Returns true if this review just turned the card into
     * a leech (auto-parked after [LEECH_LAPSES] lapses) so the UI can tell the learner.
     */
    suspend fun review(
        card: Card,
        rating: Rating,
        now: Long = System.currentTimeMillis(),
        objectiveCorrect: Boolean? = null
    ): Boolean {
        var becameLeech = false
        var undoSnapshot: UndoSnapshot? = null
        runInTransaction {
        val live = cardDao.getByIds(listOf(card.id)).firstOrNull() ?: error("Card ${card.id} no longer exists")
        check(!live.suspended && live.state != CardState.GRADUATED) {
            "Card ${card.id} was retired before this rating was saved"
        }
        val note = noteDao.getById(live.noteId)
        // Snapshot the live card + encounter count before mutating, for undo.
        undoSnapshot = UndoSnapshot(card = live, noteId = live.noteId, priorEncounterCount = note?.encounterCount ?: 0)
        // A lesson is "done" the moment it's read: graduate it so it never recurs.
        // We still log it (stateBefore = NEW) so it counts as the concept's
        // introduction — that is what unlocks the concept's drills.
        if (live.cardType == CardType.LESSON) {
            val graduated = live.copy(
                state = CardState.GRADUATED,
                reps = live.reps + 1,
                lastReview = now,
                due = Long.MAX_VALUE
            )
            cardDao.update(graduated)
            reviewLogDao.insert(
                ReviewLog(
                    cardId = live.id,
                    reviewDatetime = now,
                    rating = rating,
                    stateBefore = live.state,
                    scheduledDays = 0,
                    elapsedDays = 0,
                    source = ReviewSource.GRAMMAR_DRILL
                )
            )
        } else {
        val (scheduledCard, rawLog) = scheduler.review(live, rating, now)
        val updatedCard = if (
            note != null && live.queue == Queue.VOCAB && rating != Rating.AGAIN &&
            CognateDetector.isCognate(note.russian, note.translation) && scheduledCard.scheduledDays > 0
        ) {
            val days = maxOf(scheduledCard.scheduledDays + 1, (scheduledCard.scheduledDays * 1.35).toInt())
            scheduledCard.copy(scheduledDays = days, due = now + days * DAY_MILLIS)
        } else scheduledCard
        val log = rawLog.copy(
            scheduledDays = updatedCard.scheduledDays,
            evidenceStrength = CardPedagogy.profile(live.cardType).evidence
        )
        // Leech guard: if this card has lapsed too many times it's burning the
        // learner's time, so park it (suspend) rather than let it recur forever.
        becameLeech = !updatedCard.suspended &&
            updatedCard.state != CardState.GRADUATED &&
            updatedCard.lapses >= LEECH_LAPSES
        cardDao.update(if (becameLeech) updatedCard.copy(suspended = true) else updatedCard)
        reviewLogDao.insert(log)
        }
        note?.let { noteDao.update(it.copy(encounterCount = it.encounterCount + 1)) }
        }
        lastUndo = undoSnapshot
        invalidateNoteState()
        if (objectiveCorrect != null && card.cardType != CardType.LESSON) {
            updateLearnerModels(card, objectiveCorrect, now)
        }
        return becameLeech
    }

    /** Records passive observations from reading/listening/production without
     * pretending they were full reviews. At most one event can affect a card per
     * UTC day; NEW and GRADUATED cards are immutable on this path. */
    suspend fun recordEvidence(event: EvidenceEvent): Int {
        require(event.source in setOf(ReviewSource.READING, ReviewSource.LISTENING, ReviewSource.PRODUCTION)) {
            "Direct reviews must use review(); passive evidence requires an activity source"
        }
        require(event.strength != EvidenceStrength.STRONG) {
            "Strong retrieval must carry an explicit rating through review()"
        }
        val candidates = when {
            event.noteId != null -> cardDao.getCardsForNote(event.noteId)
            else -> cardDao.getAllGrammarCards().filter { it.gramConcept == event.conceptId }
        }
        val engine = scheduler as? FsrsScheduler ?: return 0
        val dayStart = event.at - Math.floorMod(event.at, DAY_MILLIS)
        var applied = 0
        runInTransaction {
            candidates.filter { !it.suspended && it.state != CardState.NEW && it.state != CardState.GRADUATED }
                .forEach { card ->
                    if (reviewLogDao.passiveEvidenceCountSince(card.id, dayStart) > 0) return@forEach
                    val updated = engine.applyPassiveEvidence(card, if (event.correct) 1.15 else 0.90)
                    if (updated == card) return@forEach
                    cardDao.update(updated)
                    reviewLogDao.insert(ReviewLog(
                        cardId = card.id,
                        reviewDatetime = event.at,
                        rating = if (event.correct) Rating.GOOD else Rating.AGAIN,
                        stateBefore = card.state,
                        scheduledDays = card.scheduledDays,
                        elapsedDays = 0,
                        source = event.source,
                        stabilityBefore = card.stability,
                        evidenceStrength = event.strength
                    ))
                    applied++
                }
        }
        if (applied > 0) invalidateNoteState()
        return applied
    }

    private suspend fun updateLearnerModels(card: Card, success: Boolean, now: Long) {
        val dao = learningModelDao ?: return
        val parameters = dao.parameters().associateBy { it.key }
        val globalMuRaw = parameters["global_skill_mu"] ?: OptimizerParameter("global_skill_mu", TrueSkill.MU0)
        val globalSigmaRaw = parameters["global_skill_sigma"] ?: OptimizerParameter("global_skill_sigma", TrueSkill.SIGMA0)
        val globalMu = globalMuRaw.copy(value = globalMuRaw.value.takeIf(Double::isFinite) ?: TrueSkill.MU0)
        val globalSigma = globalSigmaRaw.copy(value = globalSigmaRaw.value.takeIf { it.isFinite() && it > 0.0 } ?: TrueSkill.SIGMA0)
        val persisted = dao.skillRatings().mapNotNull { row ->
            runCatching { AbilitySkill.valueOf(row.skill.uppercase()) }.getOrNull()?.let { skill -> skill to row }
        }.toMap()
        val weights = WorldModel.skillWeights(card)
        val effectiveOffset = weights.entries.sumOf { (skill, weight) ->
            weight * (persisted[skill]?.mu?.takeIf(Double::isFinite) ?: 0.0)
        }
        val effectiveSigma2 = globalSigma.value * globalSigma.value + weights.entries.sumOf { (skill, weight) ->
            val sigma = persisted[skill]?.sigma?.takeIf { it.isFinite() && it >= 0.0 } ?: TrueSkill.SIGMA0
            weight * weight * sigma * sigma
        }
        val itemPrior = objectiveDifficultyPrior(card)
        val itemRaw = dao.difficulty(card.id) ?: ItemDifficulty(card.id, elo = itemPrior)
        val item = itemRaw.copy(
            elo = itemRaw.elo.takeIf(Double::isFinite) ?: itemPrior,
            sigma = itemRaw.sigma.takeIf { it.isFinite() && it >= 0.0 } ?: TrueSkill.SIGMA0
        )
        val result = TrueSkill.update(
            Gaussian(globalMu.value + effectiveOffset, kotlin.math.sqrt(effectiveSigma2)),
            Gaussian(item.elo, item.sigma),
            if (success) MatchOutcome.WIN else MatchOutcome.LOSS
        )
        val delta = result.a.mu - (globalMu.value + effectiveOffset)
        dao.upsertParameter(globalMu.copy(value = globalMu.value + 0.6 * delta, observations = globalMu.observations + 1, updatedAt = now))
        val effectiveSigma = kotlin.math.sqrt(effectiveSigma2)
        val sigmaRatio = (result.a.sigma / effectiveSigma).coerceIn(0.1, 1.0)
        dao.upsertParameter(globalSigma.copy(
            value = (globalSigma.value * (0.4 + 0.6 * sigmaRatio)).coerceAtLeast(0.01 * TrueSkill.SIGMA0),
            observations = globalSigma.observations + 1,
            updatedAt = now
        ))
        WorldModel.applyAbilityDelta(persisted, card, delta, now, sigmaRatio).forEach { updated -> dao.upsertSkillRating(updated) }
        // Blend toward the cognitive-cost prior with confidence that grows with this
        // item's own review history, instead of a fixed 95/5 split that trusted a
        // single TrueSkill update the same whether it was the item's 1st or 500th
        // observation. ITEM_PRIOR_STRENGTH=20 half-saturates: at 20 observations the
        // fitted value and the prior are weighted equally.
        dao.upsertDifficulty(item.copy(
            elo = ColdStartModel.blend(personal = result.b.mu, cohort = itemPrior, observations = item.observations, priorStrength = ITEM_PRIOR_STRENGTH),
            sigma = result.b.sigma,
            observations = item.observations + 1,
            updatedAt = now
        ))
        val note = noteDao.getById(card.noteId)
        val concept = card.gramConcept ?: note?.lemma ?: return
        val roots = note?.let { value -> contentDao?.familyForLemma(value.lemma)?.map { it.root } }.orEmpty()
        WorldModel.masteryKeys(concept, roots).forEach { key ->
            val mastery = dao.mastery(key) ?: ConceptMastery(key)
            dao.upsertMastery(mastery.copy(
                probability = MasteryModel.update(mastery.probability, success),
                observations = mastery.observations + 1,
                updatedAt = now
            ))
        }
    }

    private fun objectiveDifficultyPrior(card: Card): Double {
        val profile = CardPedagogy.profile(card.cardType)
        val typeBias = (profile.cognitiveCost - 0.8) * 2.2
        return TrueSkill.MU0 + typeBias
    }

    suspend fun recordPace(pace: Pace, mode: SessionMode, now: Long = System.currentTimeMillis()) {
        learningModelDao?.upsertPaceLog(PaceLog(
            at = now,
            T = pace.targetMinutes,
            N = pace.newItemBudget,
            rho = pace.targetRetention,
            debtRatio = pace.debtRatio,
            pReturn = pace.pReturn,
            doctrine = pace.doctrine.name,
            modeChosen = mode.name
        ))
    }

    suspend fun expectedRivalPerformance(cardIds: List<Long>): Double {
        val dao = learningModelDao ?: return 0.5
        val cards = cardDao.getByIds(cardIds.distinct()).filter { it.cardType != CardType.LESSON }
        if (cards.isEmpty()) return 0.5
        val parameters = dao.parameters().associateBy { it.key }
        val globalMu = parameters["global_skill_mu"]?.value ?: TrueSkill.MU0
        val skills = dao.skillRatings().associateBy { it.skill }
        val rivalState = dao.rivalState() ?: RivalState()
        val rival = Rival.rubberBand(
            RivalBelief(Gaussian(rivalState.mu, rivalState.sigma), rivalState.handicap, rivalState.winStreak, rivalState.persona),
            Gaussian(globalMu, parameters["global_skill_sigma"]?.value ?: TrueSkill.SIGMA0)
        )
        var weighted = 0.0
        var totalWeight = 0.0
        cards.forEach { card ->
            val difficulty = dao.difficulty(card.id) ?: ItemDifficulty(card.id, elo = objectiveDifficultyPrior(card))
            val dominant = WorldModel.skillWeights(card).maxByOrNull { it.value }?.key ?: AbilitySkill.VOCAB
            val learnerSkill = Gaussian(globalMu + (skills[dominant.name.lowercase()]?.mu ?: 0.0), skills[dominant.name.lowercase()]?.sigma ?: TrueSkill.SIGMA0)
            val probability = Rival.expectedCorrect(rival.rating, difficulty, learnerSkill)
            val weight = (1.0 + (difficulty.elo - TrueSkill.MU0) / TrueSkill.SIGMA0).coerceIn(0.5, 2.0)
            weighted += weight * probability
            totalWeight += weight
        }
        return (weighted / totalWeight.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
    }

    suspend fun observeReturn(now: Long = System.currentTimeMillis()) {
        val dao = learningModelDao ?: return
        val previous = dao.willingnessState() ?: return
        if (previous.updatedAt <= 0L || previous.updatedAt >= now) return
        val hours = (now - previous.updatedAt) / 3_600_000.0
        val coefficients = parseWillingnessCoefficients(previous.coeffsJson)
        val telemetry = recentTelemetry(80)
        val fatigue = estimatedSessionFatigue(telemetry)
        val streak = ((gamificationStats(now).currentStreak - 3.0) / 4.0).coerceIn(-3.0, 3.0)
        val context = ReturnContext(
            hoursSinceLastZ = ((hours - 36.0) / 24.0).coerceIn(-3.0, 3.0),
            streakZ = streak,
            lastSessionFatigue = fatigue,
            lastDebtRatio = dao.paceLogs(1).firstOrNull()?.debtRatio ?: 0.0
        )
        val learned = WillingnessModel.updateReturn(WillingnessBelief(previous.habit, coefficients), context, returned = hours <= 36.0)
        dao.upsertWillingnessState(previous.copy(coeffsJson = JSONArray(learned.coeffs.toList()).toString()))
    }

    suspend fun recordBanditExposure(
        card: Card,
        action: String,
        context: DoubleArray,
        showAt: Long,
        fatigue: Double
    ) {
        val dao = learningModelDao ?: return
        val parameters = dao.parameters().associateBy { it.key }
        val global = Gaussian(
            parameters["global_skill_mu"]?.value ?: TrueSkill.MU0,
            parameters["global_skill_sigma"]?.value ?: TrueSkill.SIGMA0
        )
        val skills = dao.skillRatings().mapNotNull { row ->
            runCatching { AbilitySkill.valueOf(row.skill.uppercase()) }.getOrNull()?.let { skill -> skill to Gaussian(row.mu, row.sigma) }
        }.toMap()
        val item = dao.difficulty(card.id) ?: ItemDifficulty(card.id, elo = objectiveDifficultyPrior(card))
        val concept = card.gramConcept ?: noteDao.getById(card.noteId)?.lemma
        val mastery = concept?.let { dao.mastery(it) }
        val p0 = WorldModel.successProbability(
            card,
            item,
            mastery,
            com.sibirskyspeak.learning.LearnerWorldState(global = global, skills = skills, fatigue = fatigue),
            showAt,
            decay = decayProvider(),
            calibration = successCalibration(parameters.values.toList())
        )
        dao.upsertBanditPending(BanditPending(showAt, card.id, action, JSONArray(context.toList()).toString(), p0))
    }

    suspend fun resolveBanditCredits(
        itemId: Long,
        recalled: Boolean,
        responseMs: Long,
        fatigueDelta: Double,
        currentShowAt: Long
    ): List<BanditCredit> {
        val dao = learningModelDao ?: return emptyList()
        return dao.pendingBanditCredits(itemId)
            .filter { it.showAt < currentShowAt }
            .mapNotNull { pending ->
                val context = runCatching {
                    val json = JSONArray(pending.contextJson)
                    DoubleArray(json.length()) { json.getDouble(it) }
                }.getOrNull()
                dao.deleteBanditPending(pending.showAt)
                context?.let {
                    BanditCredit(
                        pending.action,
                        it,
                        CausalFormatReward.reward(recalled, pending.p0, responseMs / 60_000.0, fatigueDelta)
                    )
                }
            }
    }

    private fun parseWillingnessCoefficients(raw: String): DoubleArray = runCatching {
        val json = JSONArray(raw)
        require(json.length() == WillingnessModel.priorMeans.size)
        DoubleArray(json.length()) { json.getDouble(it) }
    }.getOrElse { WillingnessModel.priorMeans.copyOf() }

    suspend fun finishAdaptiveSession(
        observedMinutes: Double,
        fatigue: Double,
        debtRatio: Double,
        completed: Boolean,
        cleanFinish: Boolean,
        perfYou: Double,
        perfRival: Double,
        rankedMatch: Boolean = true,
        stoppedEarly: Boolean = false,
        now: Long = System.currentTimeMillis()
    ): com.sibirskyspeak.learning.MatchReport? {
        val dao = learningModelDao ?: return null
        val oldCapacity = dao.capacityState() ?: CapacityState()
        val capacity = CapacityModel.updateFromSession(
            CapacityBelief(oldCapacity.mu, oldCapacity.sigma),
            observedMinutes,
            stoppedEarly,
            fatigue
        )
        dao.upsertCapacityState(oldCapacity.copy(mu = capacity.mu, sigma = capacity.sigma, updatedAt = now))

        val oldWillingness = dao.willingnessState() ?: WillingnessState()
        val coefficients = parseWillingnessCoefficients(oldWillingness.coeffsJson)
        val transitioned = WillingnessModel.transition(oldWillingness.habit, WillingnessSignals(
            completed = completed,
            flow = perfYou >= 0.8 && fatigue < 0.5,
            cleanFinish = cleanFinish,
            quit = !completed,
            overload = fatigue >= 0.7,
            reviewDebtHigh = debtRatio >= 0.5
        ))
        val learned = WillingnessBelief(transitioned, coefficients)
        dao.upsertWillingnessState(oldWillingness.copy(
            habit = learned.habit,
            coeffsJson = JSONArray(learned.coeffs.toList()).toString(),
            updatedAt = now
        ))

        if (!rankedMatch) return null

        val parameters = dao.parameters().associateBy { it.key }
        val userMu = parameters["global_skill_mu"]?.value ?: TrueSkill.MU0
        var userSigma = parameters["global_skill_sigma"]?.value ?: TrueSkill.SIGMA0
        val seasonStarted = parameters["season_started_at"]?.value?.toLong() ?: now.also {
            dao.upsertParameter(OptimizerParameter("season_started_at", now.toDouble(), updatedAt = now))
        }
        if (now - seasonStarted >= 60L * DAY_MILLIS) {
            userSigma = Rival.seasonSigma(userSigma, (now - seasonStarted) / DAY_MILLIS.toDouble())
            dao.upsertParameter(OptimizerParameter("season_started_at", now.toDouble(), updatedAt = now))
        }
        val latestSnapshot = dao.latestGhostSnapshot()
        if (latestSnapshot == null || now - latestSnapshot.takenAt >= DAY_MILLIS) {
            dao.insertGhostSnapshot(GhostSnapshot(now, userMu, userSigma))
        }
        val ghost = dao.ghostSnapshotAtOrBefore(now - 21L * DAY_MILLIS)
        val oldRival = dao.rivalState() ?: RivalState()
        val banded = Rival.rubberBand(RivalBelief(Gaussian(oldRival.mu, oldRival.sigma), oldRival.handicap, oldRival.winStreak, oldRival.persona), Gaussian(userMu, userSigma))
        val baseReport = Rival.resolve(Gaussian(userMu, userSigma), banded, perfYou, perfRival)
        val ghostPerformance = ghost?.let { Rival.ghostPerformance(perfRival, it.muGlobal, banded.rating.mu, it.sigma) }
        val ghostOutcome = ghostPerformance?.let { TrueSkill.outcomeFromPerformance(perfYou, it) }
        val promotion = Rival.updatePromotion(
            PromotionSeries(
                lockedTier = parameters["promotion_locked_tier"]?.value?.toInt() ?: 0,
                targetTier = parameters["promotion_target_tier"]?.value?.toInt() ?: 0,
                games = parameters["promotion_games"]?.value?.toInt() ?: 0,
                wins = parameters["promotion_wins"]?.value?.toInt() ?: 0
            ),
            baseReport.after.conservativeRating,
            baseReport.outcome
        )
        val promotionText = when {
            promotion.locked -> "Promotion locked"
            promotion.failed -> "Promotion series reset"
            promotion.series.targetTier > promotion.series.lockedTier -> "Promotion ${promotion.series.wins}/2 wins · ${promotion.series.games}/3 matches"
            else -> null
        }
        val report = baseReport.copy(
            ghostOutcome = ghostOutcome,
            tier = Rival.tier(baseReport.after.conservativeRating),
            promotionProgress = promotionText
        )
        val nextHandicap = Rival.nextHandicap(oldRival.handicap, report.outcome)
        dao.upsertRivalState(oldRival.copy(
            mu = report.opponentAfter.mu,
            sigma = report.opponentAfter.sigma,
            handicap = nextHandicap,
            winStreak = if (report.outcome == MatchOutcome.WIN) oldRival.winStreak + 1 else 0,
            updatedAt = now
        ))
        dao.upsertParameter((parameters["global_skill_mu"] ?: OptimizerParameter("global_skill_mu", userMu)).copy(value = report.after.mu, updatedAt = now))
        dao.upsertParameter((parameters["global_skill_sigma"] ?: OptimizerParameter("global_skill_sigma", userSigma)).copy(value = report.after.sigma, updatedAt = now))
        listOf(
            OptimizerParameter("promotion_locked_tier", promotion.series.lockedTier.toDouble(), updatedAt = now),
            OptimizerParameter("promotion_target_tier", promotion.series.targetTier.toDouble(), updatedAt = now),
            OptimizerParameter("promotion_games", promotion.series.games.toDouble(), updatedAt = now),
            OptimizerParameter("promotion_wins", promotion.series.wins.toDouble(), updatedAt = now)
        ).forEach { dao.upsertParameter(it) }
        dao.insertMatchHistory(MatchHistory(
            at = now,
            opponent = report.opponent,
            perfYou = perfYou,
            perfOpp = perfRival,
            outcome = report.outcome.name,
            ratingBefore = report.before.conservativeRating,
            ratingAfter = report.after.conservativeRating
        ))
        if (ghost != null && ghostPerformance != null && ghostOutcome != null) {
            dao.insertMatchHistory(MatchHistory(
                at = now,
                opponent = "ghost:${ghost.takenAt}",
                perfYou = perfYou,
                perfOpp = ghostPerformance,
                outcome = ghostOutcome.name,
                ratingBefore = report.before.conservativeRating,
                ratingAfter = report.after.conservativeRating
            ))
        }
        maybeTuneLearningPolicy(now)
        return report
    }

    private suspend fun maybeTuneLearningPolicy(now: Long) {
        modelTuningMutex.withLock {
            val dao = learningModelDao ?: return@withLock
            val telemetry = telemetryDao ?: return@withLock
            if (telemetry.countByType("success_calibration_sample") < SuccessCalibrationFitter.MIN_SAMPLES) return@withLock
            val drift = calibrationDriftReport()
            val currentVersion = dao.parameters().firstOrNull {
                it.key == com.sibirskyspeak.learning.ModelGovernance.CURRENT_VERSION_KEY
            }?.value?.toInt()?.takeIf { it > 0 }
            if (currentVersion != null && drift?.drifted == true && rollbackModelSnapshot(now)) {
                telemetry.insert(TelemetryEvent(
                    timestamp = now,
                    eventType = "model_policy_rolled_back",
                    metadataJson = JSONObject()
                        .put("fromVersion", currentVersion)
                        .put("brierDelta", drift.brierDelta)
                        .put("biasDelta", drift.biasDelta)
                        .toString()
                ))
                dao.upsertParameter(OptimizerParameter("model:last_tuned_at", now.toDouble(), updatedAt = now))
                return@withLock
            }
            val last = dao.parameters().firstOrNull { it.key == "model:last_tuned_at" }?.value?.toLong() ?: 0L
            if (last > 0L && now - last < 30L * DAY_MILLIS) return@withLock
            val staged = tuneAndStageLearningPolicyUnlocked(500, 90, (now / DAY_MILLIS).toInt(), true, now)
                ?: return@withLock
            dao.upsertParameter(OptimizerParameter("model:last_tuned_at", now.toDouble(), updatedAt = now))
            telemetry.insert(TelemetryEvent(
                timestamp = now,
                eventType = "model_policy_evaluated",
                metadataJson = JSONObject()
                    .put("version", staged.version)
                    .put("promoted", staged.promoted)
                    .put("utilityLift", staged.tuning.comparison.utilityLift)
                    .put("utilityLiftLower95", staged.tuning.comparison.utilityLiftLower95)
                    .put("reasons", JSONArray(staged.decision.reasons))
                    .toString()
            ))
        }
    }

    /** True if there is a review that can be rolled back this session. */
    fun canUndo(): Boolean = lastUndo != null

    /** Queue-only actions make an older DB undo ambiguous; retire it explicitly. */
    fun clearUndo() { lastUndo = null }

    /**
     * Roll back the most recent [review]: restore the card's pre-review SRS state,
     * delete the log row it produced, and restore the note's encounter count.
     * Returns the restored card so the caller can re-present it, or null if there
     * was nothing to undo. A category may have graduated on the way in; we don't
     * un-graduate, which is harmless (graduation re-checks accuracy each session).
     */
    suspend fun undoLastReview(): Card? {
        val snapshot = lastUndo ?: return null
        runInTransaction {
            reviewLogDao.deleteLatestForCard(snapshot.card.id)
            cardDao.update(snapshot.card)
            noteDao.getById(snapshot.noteId)?.let {
                noteDao.update(it.copy(encounterCount = snapshot.priorEncounterCount))
            }
        }
        lastUndo = null
        invalidateNoteState()
        return snapshot.card
    }

    /** Permanently retire a card (e.g. a bad auto-generated item) from all queues. */
    suspend fun suspendCard(card: Card) {
        val live = cardDao.getByIds(listOf(card.id)).firstOrNull() ?: return
        cardDao.update(live.copy(suspended = true))
        invalidateNoteState()
    }

    /** Auto-parked leeches (suspended cards that lapsed past the threshold), with
     *  their notes, so the learner can fix or release them. */
    suspend fun leechCards(): List<Pair<Card, Note>> =
        cardDao.getLeechCards(LEECH_LAPSES).mapNotNull { card ->
            noteDao.getById(card.noteId)?.let { card to it }
        }

    /** Release a parked leech back into rotation with a clean slate (fresh learning). */
    suspend fun releaseLeech(card: Card, now: Long = System.currentTimeMillis()) {
        cardDao.update(
            card.copy(
                suspended = false,
                lapses = 0,
                state = CardState.NEW,
                due = now,
                reps = 0,
                stability = 0.0,
                difficulty = 0.0,
                consecutiveCorrect = 0,
                lastReview = null
            )
        )
        invalidateNoteState()
    }

    /**
     * Edit a note's learner-facing content in place (fix a bad gloss or example
     * straight from the review screen). Blank fields are left unchanged.
     */
    suspend fun updateNoteContent(
        noteId: Long,
        translation: String? = null,
        exampleSentence: String? = null,
        exampleTranslation: String? = null,
        mnemonic: String? = null
    ) {
        val note = noteDao.getById(noteId) ?: return
        val updated = note.copy(
                translation = translation?.trim()?.takeIf { it.isNotBlank() } ?: note.translation,
                exampleSentence = exampleSentence?.trim()?.takeIf { it.isNotBlank() } ?: note.exampleSentence,
                exampleTranslation = exampleTranslation?.trim()?.takeIf { it.isNotBlank() } ?: note.exampleTranslation,
                mnemonic = mnemonic?.trim()?.takeIf { it.isNotBlank() } ?: note.mnemonic
        )
        noteDao.update(updated)
        ensureReadableExampleCards(updated)
        invalidateNoteContent()
    }

    /**
     * Sentence-mining: take a word the learner just met while reading and the exact
     * sentence they saw it in, store that sentence as the word's example, and pull the
     * word into active study (LEARNING). This closes the loop between reading input
     * and spaced-repetition practice — you study words in the context you met them.
     * Returns the resolved/created note.
     */
    suspend fun mineSentence(
        token: String,
        sentence: String,
        translation: String? = null,
        now: Long = System.currentTimeMillis()
    ): Note? {
        val trimmedSentence = sentence.trim()
        val note = setWordStatus(token, WordStatus.LEARNING, now) ?: return null
        val fresh = noteDao.getById(note.id) ?: note
        noteDao.update(
            fresh.copy(
                exampleSentence = trimmedSentence.takeIf { it.isNotBlank() } ?: fresh.exampleSentence,
                exampleTranslation = translation?.trim()?.takeIf { it.isNotBlank() } ?: fresh.exampleTranslation
            )
        )
        // Only add context recall when the sentence has a real meaning attached.
        val minedNote = noteDao.getById(note.id) ?: fresh
        ensureReadableExampleCards(minedNote)
        invalidateNoteContent()
        return noteDao.getById(note.id)
    }

    private suspend fun ensureReadableExampleCards(note: Note) {
        if (CardFactory.hasReadableExample(note) && cardDao.getByNoteAndType(note.id, CardType.CLOZE) == null) {
            cardDao.insert(Card(noteId = note.id, cardType = CardType.CLOZE, queue = Queue.VOCAB, due = 0L))
            invalidateNoteStructure()
        }
    }

    suspend fun placeAfterLevel(level: String, now: Long = System.currentTimeMillis()): Int {
        val normalized = level.uppercase(Locale.ROOT)
        val index = CEFR_LEVELS.indexOf(normalized)
        if (index < 0) return 0
        val levels = CEFR_LEVELS.take(index + 1)
        val notes = noteDao.getByCefrLevels(levels)
            .filterNot { it.partOfSpeech.equals("lesson", ignoreCase = true) }
        if (notes.isEmpty()) return 0
        val noteIds = notes.map { it.id }
        val cards = cardDao.getCardsForNotes(noteIds)
        // Graduate every card for the placed levels into a coherent "known" FSRS state
        // (long stability, low difficulty) rather than the all-zero state that left
        // ~1k cards un-schedulable. A far-future due keeps them as a yearly refresher.
        val knownDue = now + 365L * DAY_MILLIS
        cardDao.updateAll(cards.map { card -> FsrsScheduler.markKnown(card, now, knownDue) })
        noteDao.updateAll(notes.map { note ->
            note.copy(
                status = WordStatus.KNOWN,
                encounterCount = maxOf(note.encounterCount, VOCAB_GRADUATION_ENCOUNTERS)
            )
        })
        invalidateNoteState()
        return notes.size
    }

    suspend fun searchNotes(query: String, limit: Int = 50): List<Note> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        return noteDao.search(trimmed, limit)
    }

    /** Mark a batch of reader surface tokens with one status in a single pass. */
    suspend fun setWordStatusBatch(tokens: Collection<String>, status: WordStatus): Int {
        var changed = 0
        runInTransaction {
            tokens.distinctBy { normalizeToken(it) }.forEach { token ->
                val normalized = normalizeToken(token)
                val before = formIndex()[normalized]?.let { noteDao.getById(it.id) } ?: noteDao.getByLemma(normalized)
                val after = setWordStatus(token, status)
                if (after != null && before?.status != status) changed += 1
            }
        }
        return changed
    }

    suspend fun readerRecommendation(): ReaderRecommendation? =
        readerTexts().minWithOrNull(compareBy<ReaderRecommendation> { distanceFromTarget(it.coverage) }.thenByDescending { it.dueOverlap }.thenByDescending { it.coverage })

    @Suppress("UNUSED_PARAMETER")
    suspend fun readerLookup(token: String, text: ReaderText, now: Long = System.currentTimeMillis()): Note? {
        val normalized = normalizeToken(token)
        val note = formIndex()[normalized]?.let { noteDao.getById(it.id) }
        if (note != null) {
            var credited = false
            runInTransaction {
                credited = readerEncounterDao?.insert(ReaderEncounter(text.id, note.id, now))?.let { it != -1L } ?: false
                if (credited) {
                    val live = noteDao.getById(note.id) ?: note
                    noteDao.update(live.copy(encounterCount = live.encounterCount + 1))
                    graduateVocabByEncounters()
                }
            }
            if (credited) {
                invalidateNoteState()
            }
            return noteDao.getById(note.id)
        }
        addNote(
            Note(
                russian = token,
                lemma = normalized,
                translation = "lookup pending",
                partOfSpeech = "unknown",
                tags = "reader_lookup"
            )
        )
        return noteDao.getByLemma(normalized)
    }

    suspend fun lookupReaderToken(token: String, readerTextId: Long, now: Long = System.currentTimeMillis()): Note? {
        val text = readerTextDao.getById(readerTextId) ?: return null
        return readerLookup(token, text, now)
    }

    suspend fun reviewedToday(now: Long = System.currentTimeMillis()): Int {
        val since = startOfLocalDay(now)
        return reviewLogDao.countSince(since) + (readingActivityDao?.countSince(since) ?: 0)
    }

    /**
     * Start-of-today in the device's local timezone. Using local (not UTC) day
     * boundaries keeps "reviewed today", the daily goal, and the new-card throttle
     * consistent with the streak counter — otherwise counts reset at the wrong hour
     * for every non-UTC user and drift across DST.
     */
    private fun startOfLocalDay(now: Long): Long {
        return java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Write a full-state backup if there's data worth saving. Never writes an empty
     * backup, so it can't clobber a good one during a post-wipe/pre-restore window.
     */
    suspend fun backupNow(): Boolean {
        val w = writeBackup ?: return false
        if (noteDao.count() == 0) return false
        w(exportFullState())
        return true
    }

    private suspend fun sessionCards(now: Long, limit: Int, plan: DailyPlan, mastery: List<UnitMastery>, generatedNewBudget: Int? = null): List<Card> {
        // Pull extra headroom so sibling-burying (one card per note per session) can
        // drop duplicates and still fill the session to [limit].
        val pull = (limit * 3).coerceAtLeast(limit)
        val due = if (plan.triageMode || plan.overdueBacklog) {
            cardDao.getOverdueCards(now - 2 * DAY_MILLIS, limit = pull)
                .ifEmpty { cardDao.getDueCards(now, limit = pull) }
        } else {
            cardDao.getDueCards(now, limit = pull)
        }
        val reviewedToday = reviewLogDao.getReviewedCardsSince(startOfLocalDay(now))
        val reviewedIds = reviewedToday.mapTo(HashSet()) { it.id }
        val reviewedNotes = reviewedToday.mapTo(HashSet()) { it.noteId }
        val dayBuriedDue = due.filter {
            it.queue != Queue.VOCAB || it.noteId !in reviewedNotes || it.id in reviewedIds
        }
        val dueSession = if (dayBuriedDue.isNotEmpty()) dueSessionCards(dayBuriedDue, now, limit) else emptyList()
        // Triage (a large overdue pile) is the only time we refuse new material —
        // the backlog must be cleared first.
        if (plan.triageMode || plan.overdueBacklog) {
            val practiced = reviewedToday.groupingBy(::skillBucket).eachCount()
            return finishWithConsolidation(warmStart(balanceSkills(dueSession, practiced))).take(limit)
        }
        // Otherwise BLEND: scheduled reviews come first (priority), then we top the
        // session up with new cards. This prevents the spaced-repetition "treadmill"
        // where a growing review pile permanently blocks new-word introduction. New
        // material is still capped independently by the daily lexeme budget, so this
        // never increases load beyond `newCardsPerDay` fresh words.
        if (dueSession.size >= limit) return finishWithConsolidation(warmStart(dueSession)).take(limit)
        val fresh = newCardSession(now, limit - dueSession.size, reviewedNotes, mastery, generatedNewBudget)
        val mixed = interleaveDailyCards(dueSession, fresh, reviewedToday)
        return finishWithConsolidation(ensureGrammarShare(mixed, now, limit)).take(limit)
    }

    /** Reserve roughly one card in six for already-unlocked grammar. This is a
     * floor, not a quota: due grammar can exceed it, and no locked concept leaks. */
    private suspend fun ensureGrammarShare(cards: List<Card>, now: Long, limit: Int): List<Card> {
        if (cards.size < 5) return cards
        val target = kotlin.math.ceil(minOf(cards.size, limit) * 0.16).toInt().coerceAtLeast(1)
        val existingGrammar = cards.count { it.queue == Queue.GRAMMAR }
        if (existingGrammar >= target) return cards
        val gate = conceptGate()
        val notesById = allNotesCached().associateBy { it.id }
        val existingIds = cards.mapTo(HashSet()) { it.id }
        val grammarPool = cardDao.getGrammarDrillCards(250)
        val cardsByNote = if (grammarPool.isEmpty()) emptyMap() else
            cardDao.getCardsForNotes(grammarPool.map { it.noteId }.distinct()).groupBy { it.noteId }
        val candidates = grammarPool.filter { card ->
            card.id !in existingIds && !card.suspended &&
                card.state != CardState.GRADUATED && (card.state == CardState.NEW || card.due <= now) &&
                !isConceptLocked(card, gate) && !isNewGrammarBeforeFirstEncounter(card, notesById) &&
                !isAdvancedFacetBeforeRecognitionMatures(card, cardsByNote)
        }.take(target - existingGrammar)
        if (candidates.isEmpty()) return cards
        val result = cards.toMutableList()
        for ((offset, grammar) in candidates.withIndex()) {
            if (result.size >= limit) {
                val replace = result.indexOfLast { it.queue == Queue.VOCAB && it.state == CardState.NEW }
                    .takeIf { it >= 0 } ?: result.indexOfLast { it.queue == Queue.VOCAB }
                if (replace >= 0) result.removeAt(replace) else break
            }
            val position = minOf(4 + offset * 6, result.size)
            result.add(position, grammar)
        }
        return result
    }

    /** Two secure recalls to start, then a 3-review / 1 established-facet /
     * 1-new-lexeme rhythm. This keeps urgency without producing a review wall. */
    private fun interleaveDailyCards(due: List<Card>, fresh: List<Card>, reviewedToday: List<Card>): List<Card> {
        val warm = due.filter { it.reps >= 3 && it.consecutiveCorrect >= 2 }.take(2)
        val practiced = reviewedToday.groupingBy(::skillBucket).eachCount()
        val remainingDue = ArrayDeque(balanceSkills(due.filterNot { it.id in warm.map { c -> c.id }.toSet() }, practiced))
        val establishedList = fresh.filter { it.reps > 0 || it.cardType != CardType.RU_TO_MEANING }
        val established = ArrayDeque(balanceSkills(establishedList, practiced))
        val newLexemes = ArrayDeque(balanceSkills(fresh.filterNot { it in establishedList }, practiced))
        return buildList {
            addAll(warm)
            while (remainingDue.isNotEmpty() || established.isNotEmpty() || newLexemes.isNotEmpty()) {
                repeat(3) { remainingDue.removeFirstOrNull()?.let(::add) }
                established.removeFirstOrNull()?.let(::add)
                newLexemes.removeFirstOrNull()?.let(::add)
            }
        }
    }

    /** Round-robin skill domains; within each domain zig-zag easy/hard so neither
     * grammar nor high-effort production can form an exhausting cluster. */
    private fun balanceSkills(cards: List<Card>, practicedToday: Map<Int, Int> = emptyMap()): List<Card> {
        // Authored aspect-pair contrast sets depend on adjacency; preserve their
        // pedagogical order rather than "balancing" it.
        if (cards.isNotEmpty() && cards.all { it.cardType == CardType.ASPECT_SELECT }) return cards
        val queues = cards.groupBy(::skillBucket).mapValues { (_, bucket) ->
            val sorted = bucket.sortedBy { it.difficulty + if (it.queue == Queue.GRAMMAR) 1.0 else 0.0 }
            val zigzag = mutableListOf<Card>()
            var low = 0
            var high = sorted.lastIndex
            var easyTurn = true
            while (low <= high) {
                zigzag += if (easyTurn) sorted[low++] else sorted[high--]
                easyTurn = !easyTurn
            }
            ArrayDeque(zigzag)
        }.toMutableMap()
        return buildList {
            while (queues.values.any { it.isNotEmpty() }) {
                queues.keys.sortedWith(compareBy<Int> { practicedToday[it] ?: 0 }.thenBy { it })
                    .forEach { key -> queues[key]?.removeFirstOrNull()?.let(::add) }
            }
        }
    }

    private fun skillBucket(card: Card): Int = CardPedagogy.profile(card.cardType).facet.ordinal

    private fun warmStart(cards: List<Card>): List<Card> {
        val warm = cards.filter { it.reps >= 3 && it.consecutiveCorrect >= 2 }.take(2)
        return warm + cards.filterNot { it.id in warm.map { c -> c.id }.toSet() }
    }

    /** Avoid ending on the most fragile item when a secure consolidation recall is available. */
    private fun finishWithConsolidation(cards: List<Card>): List<Card> {
        if (cards.size < 3 || cards.last().reps >= 3) return cards
        val index = cards.indexOfLast { it.reps >= 3 && it.consecutiveCorrect >= 2 }
        if (index <= 1) return cards
        return cards.toMutableList().also { list -> list += list.removeAt(index) }
    }

    /** Due-review session: surface scheduled cards plus their confusable partners. */
    private suspend fun dueSessionCards(base: List<Card>, now: Long, limit: Int): List<Card> {
        val session = mutableListOf<Card>()
        val sessionIds = mutableSetOf<Long>()
        val pairsByNote = buildMap<Long, MutableList<ConfusablePair>> {
            confusablePairDao.getAll().forEach { pair ->
                getOrPut(pair.firstNoteId) { mutableListOf() }.add(pair)
                getOrPut(pair.secondNoteId) { mutableListOf() }.add(pair)
            }
        }
        val partnerNoteIds = base.flatMap { card ->
            pairsByNote[card.noteId].orEmpty().map { pair ->
                if (pair.firstNoteId == card.noteId) pair.secondNoteId else pair.firstNoteId
            }
        }.distinct()
        val partnerCards = if (partnerNoteIds.isEmpty()) emptyMap() else
            cardDao.getCardsForNotes(partnerNoteIds).groupBy { it.noteId }
        // Bury VOCAB siblings: at most one vocab card per note per session, so the same
        // word's recognition and production cards never appear back-to-back (a buried
        // sibling stays due and surfaces next session). GRAMMAR cards are NOT buried —
        // the dueable grammar drills (aspect cues, case variants) are deliberately
        // grouped by note for contrast, and confusable partners are different notes.
        val vocabNotesInSession = mutableSetOf<Long>()
        fun tryAdd(card: Card): Boolean {
            if (card.suspended || card.state == CardState.GRADUATED) return false
            if (card.queue == Queue.VOCAB && card.noteId in vocabNotesInSession) return false
            if (!sessionIds.add(card.id)) return false
            session += card
            if (card.queue == Queue.VOCAB) vocabNotesInSession += card.noteId
            return true
        }
        for (card in base) {
            if (session.size >= limit) break
            tryAdd(card)
            for (pair in pairsByNote[card.noteId].orEmpty()) {
                if (session.size >= limit) break
                val partnerNoteId = if (pair.firstNoteId == card.noteId) pair.secondNoteId else pair.firstNoteId
                val partner = partnerCards[partnerNoteId].orEmpty()
                    .filter { it.matchesCardVariant(card) && it.id !in sessionIds }
                    .sortedWith(compareBy<Card> { it.due }.thenBy { it.id })
                    .firstOrNull { !it.suspended && it.state !in setOf(CardState.NEW, CardState.GRADUATED) && it.due <= now }
                if (partner != null) tryAdd(partner)
            }
        }
        return session.take(limit)
    }

    /**
     * New-card introduction, sequenced for how people actually learn:
     *  - **Throttled**: at most [LearningConfig.newCardsPerDay] new cards per day,
     *    so the future review load stays sustainable (anti-burnout).
     *  - **Comprehension-first**: a note's recognition card is introduced before its
     *    production card, which is introduced before its grammar drills (receptive
     *    knowledge scaffolds productive recall).
     *  - **Interleaved**: cards are pulled round-robin across notes rather than in
     *    note-sized blocks, which improves discrimination and fights boredom.
     *  - **One vocab card per note per session**: you no longer re-type the same
     *    word back-to-back; its other facets surface on later days.
     */
    private suspend fun newCardSession(now: Long, limit: Int, dayReviewedNotes: Set<Long> = emptySet(), mastery: List<UnitMastery>, generatedNewBudget: Int? = null): List<Card> {
        val introducedToday = reviewLogDao.countNewIntroducedSince(startOfLocalDay(now))
        val pacing = config()
        val normalNewBudget = (generatedNewBudget ?: pacing.newCardsPerDay).coerceAtLeast(0)
        var remainingLexemeBudget = (normalNewBudget + extraCreditToday(now) - introducedToday).coerceAtLeast(0)
        if (limit <= 0) return emptyList()
        val previouslyReviewedNotes = reviewLogDao.getReviewedNoteIds().toHashSet()

        // Pull a generous pool, already in curriculum order (A1 tier first, by unit,
        // then by frequency rank). Drop grammar drills whose teaching lesson the
        // learner hasn't seen yet — concept gating keeps "teach before test" true.
        val gate = conceptGate()
        val notesById = allNotesCached().associateBy { it.id }
        // Curriculum order sorts every tier-0 unit before tier 1+ (the uncapped
        // reading-matrix layer), including units the learner hasn't unlocked yet. A
        // deep locked-unit backlog (hundreds of units, thousands of cards) would
        // otherwise fill the whole pool with candidates the selection loop below
        // discards anyway, starving tier 1+ material that's actually eligible. Only
        // admit tier-0 candidates through the *next* locked unit (needed for the
        // supplemental-preview peek); anything further out is skipped here too.
        val firstLockedUnit = mastery.firstOrNull { !it.unlocked }?.unit
        val targetPoolSize = maxOf(limit * 4, 200)
        val pageSize = 200
        var offset = 0
        val pool = mutableListOf<Card>()
        while (pool.size < targetPoolSize) {
            val candidateCards = cardDao.getNewCardsOrderedPage(pageSize, offset)
            if (candidateCards.isEmpty()) break
            val cardsByNote = cardDao.getCardsForNotes(candidateCards.map { it.noteId }.distinct()).groupBy { it.noteId }
            for (card in candidateCards) {
                // Bury same-day siblings, except the authored drills deliberately
                // unlocked by a lesson the learner just completed.
                if (card.noteId in dayReviewedNotes && card.queue == Queue.VOCAB) continue
                if (isConceptLocked(card, gate)) continue
                if (isTaperedSiblingDrill(card, gate)) continue
                if (isNewGrammarBeforeFirstEncounter(card, notesById)) continue
                if (isAdvancedFacetBeforeRecognitionMatures(card, cardsByNote)) continue
                if (isChunkBeforeParentRecognitionMatures(card, notesById)) continue
                val unit = notesById[card.noteId]?.takeIf { it.tier == 0 }?.unit
                if (unit != null && firstLockedUnit != null && unit > firstLockedUnit) continue
                pool += card
            }
            offset += candidateCards.size
            if (candidateCards.size < pageSize) break
        }
        // Group by note, preserving the pool's curriculum order for *note* ordering
        // (first appearance of each note), then order each note's own cards
        // comprehension-first (lesson → recognition → production → grammar).
        val grouped = LinkedHashMap<Long, MutableList<Card>>()
        for (card in pool) grouped.getOrPut(card.noteId) { mutableListOf() }.add(card)
        val goalPriorities = goalDirectedPriorities()
        val prioritizedGroups = grouped.entries.sortedWith(
            compareByDescending<Map.Entry<Long, MutableList<Card>>> { goalPriorities[it.key] ?: 0 }
                .thenBy { grouped.keys.indexOf(it.key) }
        )
        val frontierUnits = grouped.keys.mapNotNull { notesById[it]?.takeIf { n -> n.tier == 0 }?.unit }
            .distinct().sorted().take(2)
        val activeUnit = frontierUnits.firstOrNull()
        val previewUnit = frontierUnits.getOrNull(1)
        var previewUsed = 0
        val previewLimit = maxOf(1, limit / 5)
        val byNote = LinkedHashMap<Long, ArrayDeque<Card>>()
        for ((noteId, cards) in prioritizedGroups) {
            byNote[noteId] = ArrayDeque(
                cards.sortedWith(compareBy<Card> { introductionTier(it) }.thenBy { introductionTier2(it) }.thenBy { it.id })
            )
        }
        val session = mutableListOf<Card>()
        val notesWithVocab = mutableSetOf<Long>()
        val newlyIntroducedNotes = mutableSetOf<Long>()
        // Only first contact with a lexeme spends the daily new-word budget. A
        // passive lesson or a later facet of a known word spends a session slot but
        // must not crowd textbook vocabulary out of the day's allowance.
        while (session.size < limit && byNote.values.any { it.isNotEmpty() }) {
            var madeProgress = false
            for ((noteId, queue) in byNote) {
                if (session.size >= limit) break
                // Skip a vocab card if this note already contributed one this session.
                while (queue.isNotEmpty() && queue.first().queue == Queue.VOCAB && noteId in notesWithVocab) {
                    queue.removeFirst()
                }
                val card = queue.firstOrNull() ?: continue
                val unit = notesById[noteId]?.unit
                val supplementalPreview = unit == previewUnit && notesById[noteId]?.tags?.contains("textbook") == true
                if (unit != null && firstLockedUnit != null && unit >= firstLockedUnit && !supplementalPreview) continue
                if (unit != null && activeUnit != null && unit > activeUnit) {
                    if (unit != previewUnit || previewUsed >= previewLimit) continue
                }
                val spendsLexeme = card.cardType != CardType.LESSON &&
                    noteId !in previouslyReviewedNotes && noteId !in newlyIntroducedNotes
                if (spendsLexeme && remainingLexemeBudget == 0) continue
                queue.removeFirst()
                if (spendsLexeme) {
                    remainingLexemeBudget -= 1
                    newlyIntroducedNotes += noteId
                }
                if (unit == previewUnit && previewUnit != activeUnit) previewUsed += 1
                if (card.queue == Queue.VOCAB) notesWithVocab += noteId
                session += card
                madeProgress = true
            }
            if (!madeProgress) break
        }
        return session
    }

    /** Unknown words are ranked by how many token occurrences they unlock in texts
     * the learner imported or explicitly marked as a target. */
    private suspend fun goalDirectedPriorities(): Map<Long, Int> {
        val targets = readerTextDao.getAll().filter {
            it.source.startsWith("target:", ignoreCase = true) || it.source.equals("local", ignoreCase = true)
        }
        if (targets.isEmpty()) return emptyMap()
        val index = formIndex()
        val known = knownNoteIds()
        val score = mutableMapOf<Long, Int>()
        targets.forEach { text ->
            readerWordOccurrences(text.body).forEach { token ->
                val note = index[normalizeToken(token.surface)]
                if (note != null && note.id !in known) score[note.id] = (score[note.id] ?: 0) + 1
            }
        }
        return score
    }

    private fun queueReason(card: Card, index: Int, queue: List<Card>, now: Long, notesById: Map<Long, Note>): String {
        if (card.cardType == CardType.LESSON) return "Textbook lesson: learn the rule before practice"
        val note = notesById[card.noteId]
        if (card.state != CardState.NEW && card.due <= now) {
            return if (index < 2 && card.reps >= 3) "Warm-up: a secure scheduled review" else "Due now: protects long-term memory"
        }
        if (card.cardType == CardType.CONCEPT_DRILL) {
            val siblingIndex = queue.filter { it.noteId == card.noteId && it.cardType == CardType.CONCEPT_DRILL }.indexOf(card)
            return when (siblingIndex) {
                0 -> "Guided practice: apply the textbook example"
                1 -> "Guided practice: try with less support"
                else -> "Independent textbook practice"
            }
        }
        if (note?.tags?.contains("textbook") == true) return "New textbook vocabulary${note.unit?.let { ": unit $it" }.orEmpty()}"
        if (card.cardType != CardType.RU_TO_MEANING) return "Next skill facet for a word you already recognize"
        return "New vocabulary in curriculum order"
    }

    /** Productive and pronunciation facets wait for stable receptive recall. */
    private fun isAdvancedFacetBeforeRecognitionMatures(card: Card, cardsByNote: Map<Long, List<Card>>): Boolean {
        if (card.cardType !in ADVANCED_FACETS) return false
        val recognition = cardsByNote[card.noteId].orEmpty()
            .firstOrNull { it.cardType == CardType.RU_TO_MEANING } ?: return true
        return recognition.reps < 3 || recognition.consecutiveCorrect < 2 ||
            recognition.state !in setOf(CardState.REVIEW, CardState.GRADUATED)
    }

    private data class ConceptGate(
        val locked: Set<String>,
        // concept id -> the one drill card id still allowed to surface while that
        // concept is on probation (see probationaryConceptCards below).
        val probationCard: Map<String, Long>,
        // Concepts whose CONCEPT_APPLY card has proven transfer (P4.3): new per-note
        // sibling drills for these concepts stop being introduced going forward.
        val tapered: Set<String> = emptySet()
    )

    /**
     * Grammar concepts the learner has not been taught yet (a LESSON card that
     * hasn't been reviewed) are hard-locked, plus a lighter "probation" gate on top:
     * a concept whose LESSON has been seen still only admits ONE of its drill cards
     * until that card's first attempt succeeds — the rest of its drills stay gated
     * until then. This is what stops "tapped Got it without reading" from being
     * functionally identical to "understood it." A miss on the probation card
     * doesn't need special handling here: ReviewViewModel's existing repair/scaffold
     * retry loop already reteaches any missed card in place, so once the learner
     * gets it right (on that attempt or a later retry of the same card), the concept
     * opens up for its other drills on the next query. Concepts with no lesson at
     * all (e.g. legacy/migrated decks) are never gated, so existing study is never
     * blocked by this.
     */
    private suspend fun conceptGate(): ConceptGate {
        val tapered = cardDao.getTaperedConceptIds().toHashSet()
        val withLessons = cardDao.getConceptIdsWithLessons().toHashSet()
        if (withLessons.isEmpty()) return ConceptGate(emptySet(), emptyMap(), tapered)
        val introduced = cardDao.getIntroducedConceptIds().toHashSet()
        val locked = withLessons - introduced
        if (introduced.isEmpty()) return ConceptGate(locked, emptyMap(), tapered)
        return ConceptGate(locked, probationaryConceptCards(), tapered)
    }

    /** For every introduced concept with no drill card that has ever succeeded, the
     * lowest-id drill card for that concept — the single attempt currently allowed
     * to prove it (repeat misses on it just keep it in probation; it doesn't need
     * to succeed on the very first try, only eventually). Concepts already proven
     * (or with no drill history yet to judge) are simply absent from the result. */
    private suspend fun probationaryConceptCards(): Map<String, Long> {
        val rows = cardDao.getGrammarDrillOutcomes()
        if (rows.isEmpty()) return emptyMap()
        val byConcept = rows.groupBy { row ->
            GrammarConcepts.forCard(
                Card(
                    noteId = 0,
                    cardType = row.cardType,
                    queue = Queue.GRAMMAR,
                    gramCase = row.gramCase,
                    gramContextCue = row.gramContextCue,
                    gramConcept = row.gramConcept
                )
            )?.id ?: row.gramConcept
        }
        return buildMap {
            for ((concept, drills) in byConcept) {
                if (concept == null) continue
                val provenSuccess = drills.any { it.everSucceeded }
                if (!provenSuccess) put(concept, drills.minOf { it.cardId })
            }
        }
    }

    /** True if [card] is a grammar drill whose teaching concept is still locked, or
     * whose concept is on probation and this isn't the one card admitted to prove it. */
    private fun isConceptLocked(card: Card, gate: ConceptGate): Boolean {
        if (card.cardType == CardType.LESSON) return false
        if (card.queue != Queue.GRAMMAR) return false
        val concept = GrammarConcepts.forCard(card)?.id ?: card.gramConcept ?: return false
        if (concept in gate.locked) return true
        val probationCardId = gate.probationCard[concept] ?: return false
        return card.id != probationCardId
    }

    /** True for a not-yet-introduced per-note grammar drill whose concept has already
     * proven transfer via CONCEPT_APPLY (P4.3 taper). LESSON, CONCEPT_APPLY, and
     * NOVEL_PRODUCE are never tapered — taper only affects their per-note siblings
     * (CASE_FILL, VERB_FORM, etc); the concept-level ladder cards keep going. */
    private fun isTaperedSiblingDrill(card: Card, gate: ConceptGate): Boolean {
        if (card.cardType in setOf(CardType.LESSON, CardType.CONCEPT_APPLY, CardType.NOVEL_PRODUCE)) return false
        if (card.queue != Queue.GRAMMAR) return false
        val concept = GrammarConcepts.forCard(card)?.id ?: card.gramConcept ?: return false
        return concept in gate.tapered
    }

    private fun isNewGrammarBeforeFirstEncounter(card: Card, notesById: Map<Long, Note>): Boolean {
        if (card.queue != Queue.GRAMMAR || card.cardType == CardType.LESSON) return false
        val note = notesById[card.noteId] ?: return false
        return note.encounterCount == 0
    }

    /** A CHUNK card's own note has no review history to judge (it's freshly minted) —
     * maturity is judged on the *parent* word's RU_TO_MEANING card instead (P4.4 L1). */
    private suspend fun isChunkBeforeParentRecognitionMatures(card: Card, notesById: Map<Long, Note>): Boolean {
        if (card.cardType != CardType.CHUNK) return false
        val parentId = notesById[card.noteId]?.chunkParentNoteId ?: return true
        val recognition = cardDao.getCardsForNote(parentId).firstOrNull { it.cardType == CardType.RU_TO_MEANING } ?: return true
        return recognition.reps < 3 || recognition.consecutiveCorrect < 2 ||
            recognition.state !in setOf(CardState.REVIEW, CardState.GRADUATED)
    }

    /** Coarse introduction tier: lesson (0) → receptive (1) → productive (2) → grammar (3). */
    private fun introductionTier(card: Card): Int = when (card.cardType) {
        CardType.LESSON -> -1
        CardType.RU_TO_MEANING, CardType.AUDIO_TO_RU -> 0
        CardType.MEANING_TO_RU, CardType.CLOZE, CardType.STRESS_MARK -> 1
        else -> 2
    }

    /** Within grammar, teach aspect and case before the larger verb paradigm. */
    private fun introductionTier2(card: Card): Int = when (card.cardType) {
        CardType.ASPECT_SELECT -> 0
        CardType.CASE_FILL -> 1
        CardType.VERB_FORM -> 2
        else -> 0
    }

    private fun Card.matchesCardVariant(other: Card): Boolean =
        cardType == other.cardType &&
            gramCase == other.gramCase &&
            gramGender == other.gramGender &&
            gramNumber == other.gramNumber &&
            gramContextCue == other.gramContextCue

    private fun Card.srsVariantKey(): String =
        listOf(cardType.name, gramCase, gramGender, gramNumber, gramContextCue).joinToString(":") { it ?: "" }

    private fun JSONObject.srsVariantKey(): String =
        listOf(
            getString("cardType"),
            optCleanString("gramCase"),
            optCleanString("gramGender"),
            optCleanString("gramNumber"),
            optCleanString("gramContextCue")
        ).joinToString(":") { it ?: "" }

    /**
     * Picks a concept's frame deterministically by day (so a rescheduled review
     * within the same day doesn't reshuffle the carrier) and realizes it against the
     * learner's own known-inventory words. Returns null if the content pipeline
     * hasn't shipped frames for this concept yet or the dependencies aren't wired
     * (e.g. tests without a real ContentDatabase) — callers keep the static fallback.
     */
    private suspend fun conceptApplyRealization(card: Card, now: Long): com.sibirskyspeak.generation.RealizedFrame? {
        val dao = contentDao ?: return null
        val realizer = frameRealizer ?: return null
        val conceptId = card.gramConcept ?: return null
        val frames = dao.framesForConcept(conceptId)
        if (frames.isEmpty()) return null
        val epochDay = now / DAY_MILLIS
        val frame = frames[Math.floorMod(epochDay + card.id, frames.size.toLong()).toInt()]
        return realizer.realize(frame, frameInventory(), epochDay, card.id)
    }

    private data class ChunkRealization(val blankedRu: String, val translation: String)

    /**
     * Finds a real corpus sentence containing this exact chunk phrase and blanks
     * just the chunk (P4.4 L1) — no authored translation needed, the sentence's own
     * English gloss already translates the chunk in context. Returns null if the
     * chunk can't be located verbatim (rare: collocation surfaces are mined from the
     * same corpus, but rating-based LIMIT can occasionally miss it) or dependencies
     * aren't wired; callers keep the static fallback.
     */
    private suspend fun chunkRealization(note: Note): ChunkRealization? {
        val dao = contentDao ?: return null
        val chunk = note.russian.takeIf { it.isNotBlank() } ?: return null
        val sentence = dao.sentencesContaining(chunk, limit = 3).firstOrNull() ?: return null
        val blanked = sentence.ruPlain.replaceFirst(chunk, "___", ignoreCase = true)
        if (blanked == sentence.ruPlain) return null
        return ChunkRealization(blanked, sentence.en)
    }

    /**
     * Finds a real sentence-bank sentence containing this verb and negates it
     * (P4.4 L2, transform/Transformer.kt) — infinite, novel carriers with zero
     * authored content. Tries several candidate sentences (rotated deterministically
     * by day for novelty) since not every sentence containing the lemma has it as a
     * negatable finite/non-imperative reading. Returns null if none work or
     * dependencies aren't wired; the caller keeps the static fallback.
     */
    private suspend fun transformRealization(note: Note, epochDay: Long): Transformer.Transformed? {
        val dao = contentDao ?: return null
        val morph = morphologyEngine ?: return null
        val unitMax = note.unit?.takeIf { note.tier == 0 } ?: Int.MAX_VALUE
        val sentences = dao.sentencesFor(unitMax, requiredLemma = note.lemma, limit = 10)
        if (sentences.isEmpty()) return null
        val offset = Math.floorMod(epochDay, sentences.size.toLong()).toInt()
        for (i in sentences.indices) {
            val candidate = sentences[(offset + i) % sentences.size]
            Transformer.negate(candidate.ruPlain, note.lemma, morph)?.let { return it }
        }
        return null
    }

    /**
     * Picks a real, unit-appropriate 5-9 token sentence containing this note's
     * lemma for elicited imitation (P6.1). Rotated deterministically by day for
     * novelty. Returns (ru, en) or null if no fitting sentence exists yet.
     */
    private suspend fun speakSentenceRealization(note: Note, epochDay: Long): Pair<String, String>? {
        val dao = contentDao ?: return null
        val unitMax = note.unit?.takeIf { note.tier == 0 } ?: Int.MAX_VALUE
        val sentences = dao.sentencesFor(unitMax, requiredLemma = note.lemma, limit = 20)
            .filter { it.tokenCount in 5..9 }
        if (sentences.isEmpty()) return null
        val offset = Math.floorMod(epochDay, sentences.size.toLong()).toInt()
        val chosen = sentences[offset]
        return chosen.ruStressed to chosen.en
    }

    private suspend fun promptFor(card: Card, now: Long, notesById: Map<Long, Note>? = null): ReviewPrompt? {
        val rawNote = notesById?.get(card.noteId) ?: noteDao.getById(card.noteId) ?: return null
        val (note, mined) = promptNote(rawNote)
        val partner = rawNote.aspectPartner?.let { notesById?.get(it) ?: noteDao.getById(it) }
        var prompt = buildPrompt(card, note, scheduler.preview(card, now), partner, mined?.targetPos?.takeIf { it >= 0 })
        val morphologyKey = when (card.cardType) {
            CardType.CASE_FILL -> listOfNotNull(card.gramCase, card.gramNumber ?: "SG").joinToString("_")
            CardType.VERB_FORM -> card.gramContextCue
            else -> null
        }
        if (morphologyKey != null) morphologyEngine?.inflect(note.lemma, morphologyKey)?.let { authoritative ->
            prompt = prompt.copy(expectedAnswer = authoritative)
        }
        if (card.cardType == CardType.CONCEPT_APPLY) {
            conceptApplyRealization(card, now)?.let { realized ->
                prompt = prompt.copy(
                    prompt = realized.ruBlanked,
                    expectedAnswer = realized.targetAnswer,
                    answerMode = AnswerMode.RUSSIAN_TYPED,
                    exampleTranslation = realized.en,
                    explanation = prompt.explanation
                )
            }
        }
        if (card.cardType == CardType.NOVEL_PRODUCE) {
            // Reuses the same per-concept frames as CONCEPT_APPLY, but the prompt
            // shows only the English cue (no Russian at all) and the full sentence
            // is the expected answer — the ladder's payoff (P4.4 L3). A distinct
            // card.id from the sibling CONCEPT_APPLY card already gives this its own
            // realize() seed, so no carrier collision between the two card types.
            conceptApplyRealization(card, now)?.let { realized ->
                prompt = prompt.copy(
                    prompt = realized.en,
                    expectedAnswer = realized.ru,
                    answerMode = AnswerMode.RUSSIAN_TYPED,
                    explanation = prompt.explanation
                )
            }
        }
        if (card.cardType == CardType.CHUNK) {
            chunkRealization(note)?.let { realized ->
                prompt = prompt.copy(
                    prompt = realized.blankedRu,
                    expectedAnswer = note.russian,
                    answerMode = AnswerMode.RUSSIAN_TYPED,
                    exampleTranslation = realized.translation
                )
            }
        }
        if (card.cardType == CardType.TRANSFORM) {
            transformRealization(note, now / DAY_MILLIS)?.let { realized ->
                prompt = prompt.copy(
                    prompt = "${realized.instruction}\n${realized.original}",
                    expectedAnswer = realized.result,
                    answerMode = AnswerMode.RUSSIAN_TYPED,
                    explanation = "Add «не» directly before the verb."
                )
            }
        }
        if (card.cardType == CardType.SPEAK_SENTENCE) {
            speakSentenceRealization(note, now / DAY_MILLIS)?.let { (ru, en) ->
                prompt = prompt.copy(
                    prompt = "",
                    expectedAnswer = ru,
                    answerMode = AnswerMode.SPEAK,
                    explanation = en
                )
            }
        }
        if (card.state == CardState.NEW && card.reps == 0 && prompt.lesson != null) {
            val enrichment = enrichmentFor(rawNote)
            val additions = buildList {
                enrichment.emoji?.let { add("Picture cue: $it") }
                if (enrichment.cognate) add("Cognate fast-track: this international word is already partly familiar.")
                enrichment.family.takeIf { it.size > 1 }?.let { family ->
                    add("Word family: " + family.take(6).joinToString(", ") { it.lemma })
                }
                enrichment.collocations.take(3).takeIf { it.isNotEmpty() }?.let { chunks ->
                    add("Useful chunks: " + chunks.joinToString(" · ") { it.chunk })
                }
            }
            val lesson = prompt.lesson
            if (additions.isNotEmpty() && lesson != null) prompt = prompt.copy(lesson = lesson.copy(
                body = lesson.body + additions
            ))
        }
        if (mined != null) prompt = prompt.copy(
            teachingHint = listOfNotNull(prompt.teachingHint, if (mined.unknownCount == 1) "i+1 context" else "Context with one extra gloss").joinToString(" · ")
        )
        return prompt
    }

    private suspend fun blockedGrammarPrompts(plan: DailyPlan, now: Long, notesById: Map<Long, Note>): List<ReviewPrompt> {
        val category = plan.openBlockedWith ?: return emptyList()
        val gate = conceptGate()
        val cards = when (category.kind) {
            "case" -> cardDao.getCaseDrillCards(category.gramCase.orEmpty(), category.gramGender.orEmpty(), category.gramNumber.orEmpty(), 5)
            "verb_form" -> cardDao.getVerbFormCards(category.contextCue.orEmpty(), 5)
            else -> cardDao.getAspectCards().filter { card ->
                    val note = notesById[card.noteId]
                    note?.aktionsart == category.aktionsart && note?.aspect == category.aspect && card.gramContextCue == category.contextCue
                }.take(5)
        }
        return cards.filterNot { isConceptLocked(it, gate) }.mapNotNull { promptFor(it, now, notesById) }
    }

    private suspend fun interleavedGrammarPrompts(excludeIds: Set<Long>, now: Long, notesById: Map<Long, Note>): List<ReviewPrompt> {
        val gate = conceptGate()
        return cardDao.getGrammarDrillCards(40)
            .filter { it.id !in excludeIds && it.cardType != CardType.LESSON }
            .filterNot { isConceptLocked(it, gate) }
            .take(10)
            .mapNotNull { promptFor(it, now, notesById) }
    }

    private suspend fun accuracyCategoriesCached(): List<CategoryKey> {
        val reviewCount = reviewLogDao.countAll()
        val cached = accuracyCache
        if (cached != null && accuracyCacheReviewCount == reviewCount) return cached
        return accuracyCategories().also {
            accuracyCache = it
            accuracyCacheReviewCount = reviewCount
        }
    }

    private suspend fun accuracyCategories(): List<CategoryKey> {
        val recent = reviewLogDao.recentCategoryRatings()
        val nounRatings = recent.asSequence()
            .filter { it.cardType == CardType.CASE_FILL && it.gramCase != null && it.gramGender != null && it.gramNumber != null }
            .groupBy({ Triple(it.gramCase!!, it.gramGender!!, it.gramNumber!!) }, { it.rating })
        val aspectRatings = recent.asSequence()
            .filter { it.cardType == CardType.ASPECT_SELECT && it.aktionsart != null && it.aspect != null && it.contextCue != null }
            .groupBy({ Triple(it.aktionsart!!, it.aspect!!, it.contextCue!!) }, { it.rating })
        val verbRatings = recent.asSequence()
            .filter { it.cardType == CardType.VERB_FORM && it.contextCue != null }
            .groupBy({ it.contextCue!! }, { it.rating })

        val nounKeys = cardDao.getCaseCategoryKeys()
            .map { key ->
                val ratings = nounRatings[Triple(key.gramCase, key.gramGender, key.gramNumber)].orEmpty().take(MIN_ACCURACY_SAMPLE)
                CategoryKey("case", key.gramCase, key.gramGender, key.gramNumber, accuracy = ratings.accuracyOrNull(), sampleSize = ratings.size)
            }

        val aspectKeys = cardDao.getAspectCategoryKeys()
            .map { key ->
                val ratings = aspectRatings[Triple(key.aktionsart, key.aspect, key.contextCue)].orEmpty().take(MIN_ACCURACY_SAMPLE)
                CategoryKey("aspect", aktionsart = key.aktionsart, aspect = key.aspect, contextCue = key.contextCue, accuracy = ratings.accuracyOrNull(), sampleSize = ratings.size)
            }
        val verbFormKeys = cardDao.getVerbFormCategoryKeys()
            .map { key ->
                val ratings = verbRatings[key].orEmpty().take(MIN_ACCURACY_SAMPLE)
                CategoryKey("verb_form", contextCue = key, accuracy = ratings.accuracyOrNull(), sampleSize = ratings.size)
            }
        return nounKeys + aspectKeys + verbFormKeys
    }

    private suspend fun refreshGraduationsIfNeeded(force: Boolean = false) {
        val reviewCount = reviewLogDao.countAll()
        if (!force && lastGraduationReviewCount == reviewCount) return
        val categories = accuracyCategories()
        accuracyCache = categories
        accuracyCacheReviewCount = reviewCount
        graduateEligibleCategories(categories)
        graduateVocabByEncounters()
        lastGraduationReviewCount = reviewCount
    }

    private suspend fun graduateEligibleCategories(categories: List<CategoryKey>) {
        val eligibleCategories = categories
            .filter { it.sampleSize >= MIN_ACCURACY_SAMPLE && (it.accuracy ?: 0.0) >= GRADUATION_ACCURACY }
        eligibleCategories.filter { it.kind == "case" }.forEach { category ->
            cardDao.graduateCaseCategory(category.gramCase.orEmpty(), category.gramGender.orEmpty(), category.gramNumber.orEmpty())
        }
        eligibleCategories.filter { it.kind == "aspect" }.forEach { category ->
            cardDao.graduateAspectCategory(category.aktionsart.orEmpty(), category.aspect.orEmpty(), category.contextCue.orEmpty())
        }
        eligibleCategories.filter { it.kind == "verb_form" }.forEach { category ->
            cardDao.graduateVerbFormCategory(category.contextCue.orEmpty())
        }
    }

    private suspend fun graduateVocabByEncounters() {
        cardDao.graduateVocabForReaderEncounters(VOCAB_GRADUATION_ENCOUNTERS)
    }

    private fun buildFormIndex(notes: List<Note>): Map<String, Note> {
        val map = HashMap<String, Note>()
        for (note in notes) {
            for (form in RussianForms.surfaceForms(note)) map.putIfAbsent(form, note)
        }
        return map
    }


    private fun coverageFor(text: ReaderText, index: Map<String, Note>, knownIds: Set<Long>, dueSoonNoteIds: Set<Long> = emptySet()): ReaderRecommendation {
        val tokens = readerWordOccurrences(text.body)
        val noteIds = tokens.mapNotNull { index[normalizeToken(it.surface)]?.id }
        val knownCount = noteIds.count { it in knownIds }
        val coverage = if (tokens.isEmpty()) 0.0 else knownCount.toDouble() / tokens.size
        return ReaderRecommendation(
            text = text,
            coverage = coverage,
            knownTokens = knownCount,
            totalTokens = tokens.size,
            status = statusForCoverage(coverage),
            authenticReady = false,
            dueOverlap = noteIds.distinct().count { it in dueSoonNoteIds }
        )
    }

    private fun List<Rating>.accuracyOrNull(): Double? =
        takeIf { it.isNotEmpty() }?.let { ratings -> ratings.count { it.value >= Rating.GOOD.value }.toDouble() / ratings.size }

    private fun distanceFromTarget(coverage: Double): Double =
        when {
            coverage in 0.93..0.96 -> 0.0
            coverage < 0.93 -> 0.93 - coverage
            else -> coverage - 0.96
        }

    private fun tokenize(text: String): List<String> =
        Regex("""[\p{L}\u0301]+""").findAll(text).map { it.value }.toList()

    private data class ReaderWordOccurrence(val surface: String, val isProperNoun: Boolean)

    private fun readerWordOccurrences(text: String): List<ReaderWordOccurrence> {
        val matches = Regex("""[\p{L}\u0301]+""").findAll(text).toList()
        return matches.mapIndexed { i, match ->
            val prevEnd = if (i == 0) 0 else matches[i - 1].range.last + 1
            val gapBefore = text.substring(prevEnd, match.range.first)
            val sentenceStart = i == 0 || gapBefore.any { it == '.' || it == '!' || it == '?' || it == '…' }
            ReaderWordOccurrence(
                surface = match.value,
                isProperNoun = !sentenceStart && match.value.firstOrNull()?.isUpperCase() == true
            )
        }
    }

    private fun normalizeToken(value: String): String = RussianForms.normalize(value)

    private fun statusForCoverage(coverage: Double): ReaderStatus =
        when {
            coverage < MIN_READER_COVERAGE -> ReaderStatus.TOO_HARD
            coverage <= PRODUCTIVE_COVERAGE_MAX -> ReaderStatus.PRODUCTIVE
            else -> ReaderStatus.EASY
        }


    private fun parseToken(token: String, note: Note): String? {
        val declension = note.declensionJson ?: return note.partOfSpeech
        val json = runCatching { JSONObject(declension) }.getOrNull() ?: return note.partOfSpeech
        val table = if (json.has("cases")) json.getJSONObject("cases") else json
        val normalized = normalizeToken(token)
        val matching = table.keys().asSequence().firstOrNull { key -> normalizeToken(table.optString(key)) == normalized }
        return matching?.replace('_', ' ') ?: note.partOfSpeech
    }

    private fun ruleSummaryFor(category: CategoryKey?): String =
        when (category?.kind) {
            "case" -> "Case drill: identify the required case from the carrier sentence, produce the inflected form, then check gender and number."
            "verb_form" -> "Verb form drill: identify the requested person, number, tense, or gender, then produce that conjugated Russian form."
            "aspect" -> "Aspect drill: start from Aktionsart, then decide whether the context supplies a boundary, duration, or completion cue."
            else -> "Brief rule: answer from production first; use reveal only after committing to a form."
        }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optCleanString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    companion object {
        private const val DAY_MILLIS = 86_400_000L
        // Rolling window for the true-retention instrument. Long enough to gather a
        // stable mature-review sample, short enough that recent calibration drift
        // actually moves the number (so interval-modifier and load adaptation keep
        // responding instead of freezing on a lifetime average).
        private const val RETENTION_WINDOW_DAYS = 90L
        // P4.5 contrastive-pair threshold: a confusion recurring this many times in
        // this many days is worth interrupting the queue for.
        private const val CONFUSION_WINDOW_DAYS = 14L
        private const val CONFUSION_MIN_EVENTS = 4
        // Half-saturation sample count for ColdStartModel.blend on item difficulty:
        // at this many observed reviews, the item's own fitted elo and the
        // cognitive-cost prior are weighted equally.
        private const val ITEM_PRIOR_STRENGTH = 20
        // A card the learner has lapsed (rated AGAIN) this many times is a "leech":
        // it keeps tripping them up and burning review time. We auto-park it so it
        // stops resurfacing; it lands in the Leeches list to fix or release.
        const val LEECH_LAPSES = 8
        // How many extra new cards each "extra credit" tap adds for the day.
        const val EXTRA_CREDIT_BATCH = 10
        private const val EXTRA_CREDIT_DAILY_LIMIT = EXTRA_CREDIT_BATCH
        private const val TRIAGE_THRESHOLD = 80
        private const val MIN_ACCURACY_SAMPLE = 30
        private const val REMINE_KNOWN_DELTA = 25
        private const val GRADUATION_ACCURACY = 0.90
        private const val VOCAB_GRADUATION_ENCOUNTERS = 15
        private const val READER_KNOWN_ENCOUNTERS = 3
        private const val MIN_READER_COVERAGE = 0.90
        private val READING_INTERVALS = intArrayOf(0, 1, 3, 7, 14, 30, 60, 90)
        private const val READING_XP = 30
        private const val PRODUCTIVE_COVERAGE_MAX = 0.96
        private const val AUTHENTIC_READY_COVERAGE = 0.90
        private const val DAILY_GOAL = 20
        private const val XP_PER_REVIEW = 10
        private const val XP_PER_LEVEL_STEP = 100
        private const val TELEMETRY_RETENTION_MILLIS = 180L * 24 * 60 * 60 * 1000
        private const val UNIT_MASTERY_THRESHOLD = 0.80
        // P6.5: how many units beyond the current (started) frontier stay open at
        // once — the DAG-flavored relaxation of the old fully-linear chain.
        private const val UNIT_SLIDING_WINDOW = 2
        // Facets deferred until a word's RU→meaning recognition is stable: every
        // productive skill (typing/speaking/building) AND listen-and-produce audio.
        // First contact is therefore a single clean recognition card — the word's
        // audio still auto-plays on it, so listening exposure isn't lost — which
        // maximizes new-word breadth per day and keeps the lexeme budget exact.
        private val ADVANCED_FACETS = setOf(
            CardType.MEANING_TO_RU, CardType.CLOZE, CardType.SPEAK, CardType.AUDIO_TO_RU,
            CardType.DICTATION, CardType.SENTENCE_BUILD,
            // TRANSFORM (P4.4 L2) is minted directly onto the verb note by
            // syncMissingTransformCards only once recognition is already mature, but
            // this is kept as the same defense-in-depth re-check the other advanced
            // facets get (e.g. after a relapse drags recognition back below threshold).
            CardType.TRANSFORM, CardType.SPEAK_SENTENCE
            // STRESS_MARK retired: no longer generated and the legacy cards are purged
            // by MIGRATION_14_15, so it must not resurface as a deferred facet.
        )
        private val CEFR_LEVELS = listOf("A1", "A2", "B1", "B2", "C1", "C2")
    }
}
