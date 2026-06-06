package com.sibirskyspeak.data

import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.review.buildPrompt
import com.sibirskyspeak.scheduler.Scheduler
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.Locale

data class CategoryKey(
    val kind: String,
    val gramCase: String? = null,
    val gramGender: String? = null,
    val gramNumber: String? = null,
    val aktionsart: String? = null,
    val aspect: String? = null,
    val contextCue: String? = null,
    val accuracy: Double? = null,
    val sampleSize: Int = 0
) {
    val label: String
        get() = if (kind == "case") {
            listOfNotNull(gramCase, gramGender, gramNumber).joinToString(" ")
        } else if (kind == "verb_form") {
            contextCue.orEmpty()
        } else {
            listOfNotNull(aktionsart, aspect, contextCue).joinToString(" ")
        }
}

data class DailyPlan(
    val grammarFocus: List<CategoryKey>,
    val openBlockedWith: CategoryKey?,
    val dueVocab: Int,
    val dueGrammar: Int,
    val triageMode: Boolean
)

data class ReaderRecommendation(
    val text: ReaderText,
    val coverage: Double,
    val knownTokens: Int,
    val totalTokens: Int,
    val status: ReaderStatus,
    val authenticReady: Boolean
)

enum class ReaderStatus {
    TOO_HARD,
    PRODUCTIVE,
    EASY
}

data class ReaderToken(
    val surface: String,
    val normalized: String,
    val known: Boolean,
    val status: WordStatus,
    val lemma: String?,
    val parse: String?,
    val aktionsart: String?,
    val stressForm: String?,
    val translation: String?,
    val exampleSentence: String?
)

data class DashboardStats(
    val noteCount: Int,
    val vocabCards: Int,
    val grammarCards: Int,
    val dueVocab: Int,
    val dueGrammar: Int,
    val reviewedToday: Int,
    val averageReaderCoverage: Double,
    val bestTargetCoverage: Double?,
    val authenticReady: Boolean,
    val importQualityReport: ImportQualityReport
)

data class ImportQualityReport(
    val totalNotes: Int,
    val readyNominalRows: Int,
    val aspectReadyVerbRows: Int,
    val verifiedAktionsartVerbRows: Int,
    val domainRankedRows: Int,
    val exampleRows: Int,
    val targetTextsAtOrAbove90: Int,
    val minNominalRows: Int,
    val minVerbRows: Int,
    val meetsDesignDocMinimum: Boolean,
    val warnings: List<String>
)

data class SessionPlan(
    val ruleSummary: String,
    val reviewQueue: List<ReviewPrompt>,
    val blockedGrammar: List<ReviewPrompt>,
    val interleavedGrammar: List<ReviewPrompt>,
    val readerRecommendation: ReaderRecommendation?,
    val dashboardStats: DashboardStats,
    val dailyPlan: DailyPlan,
    val gamification: GamificationStats = GamificationStats.EMPTY
)

data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val unlocked: Boolean
)

data class ReminderInfo(
    val currentStreak: Int,
    val studiedToday: Boolean,
    val dueToday: Int
)

data class GamificationStats(
    val knownWords: Int,
    val totalReviews: Int,
    val xp: Int,
    val level: Int,
    val xpIntoLevel: Int,
    val xpForLevel: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val reviewedToday: Int,
    val dailyGoal: Int,
    val activeDays: Int,
    val last7Days: List<Boolean>,
    val achievements: List<Achievement>
) {
    val goalReached: Boolean get() = dailyGoal > 0 && reviewedToday >= dailyGoal

    companion object {
        val EMPTY = GamificationStats(
            knownWords = 0, totalReviews = 0, xp = 0, level = 1, xpIntoLevel = 0,
            xpForLevel = 100, currentStreak = 0, longestStreak = 0, reviewedToday = 0,
            dailyGoal = 20, activeDays = 0, last7Days = List(7) { false }, achievements = emptyList()
        )
    }
}

/** User-tunable study pacing levers, read live on each session build. */
data class LearningConfig(
    val dailyGoal: Int = 20,
    val sessionSize: Int = 25,
    // Cap on brand-new cards introduced per day. Throttling new material is the
    // single biggest lever against overload/burnout in spaced repetition — it
    // keeps the future review load (and the daily session) sustainable.
    val newCardsPerDay: Int = 15
)

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
    // Reads/writes a full-state JSON backup that lives outside the DB, used to
    // auto-recover study history if the database is ever wiped (destructive
    // migration, corruption, reinstall). Null in tests that don't exercise it.
    private val restoreBackup: (suspend () -> String?)? = null,
    private val writeBackup: (suspend (String) -> Unit)? = null
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
    @Volatile private var lastGraduationReviewCount: Int? = null
    @Volatile private var accuracyCacheReviewCount: Int? = null
    @Volatile private var accuracyCache: List<CategoryKey>? = null

    private fun invalidateNoteStructure() {
        notesCache = null
        formIndexCache = null
        knownIdsCache = null
        lastGraduationReviewCount = null
        accuracyCacheReviewCount = null
        accuracyCache = null
    }

    private fun invalidateNoteState() {
        notesCache = null
        knownIdsCache = null
    }

    private suspend fun allNotesCached(): List<Note> =
        notesCache ?: noteDao.getAll().also { notesCache = it }

    private suspend fun formIndex(): Map<String, Note> =
        formIndexCache ?: buildFormIndex(allNotesCached()).also { formIndexCache = it }

    private suspend fun knownNoteIds(): Set<Long> =
        knownIdsCache ?: computeKnownNoteIds(allNotesCached()).also { knownIdsCache = it }

    private suspend fun runInTransaction(block: suspend () -> Unit) {
        val runner = transactionRunner
        if (runner != null) runner(block) else block()
    }

    private suspend fun computeKnownNoteIds(notes: List<Note>): Set<Long> {
        val cardKnown = cardDao.getKnownVocabNoteIds()
        val statusKnown = notes.filter { note ->
            note.status == WordStatus.KNOWN ||
                note.status == WordStatus.IGNORED ||
                note.encounterCount >= READER_KNOWN_ENCOUNTERS
        }.map { it.id }
        return (cardKnown + statusKnown).toHashSet()
    }

    suspend fun seedIfEmpty() {
        if (noteDao.count() > 0) return
        val runner = transactionRunner ?: { block -> block() }
        // Safety net first: if a local backup exists, the empty DB is almost
        // certainly the result of a wipe (destructive migration / reinstall), not a
        // first run. Restore the user's history instead of re-seeding bootstrap data.
        val backup = restoreBackup?.invoke()?.takeIf { it.isNotBlank() }
        if (backup != null) {
            var restored = 0
            runner { restored = importJsonLines(backup) }
            if (restored > 0) {
                // Reader texts aren't in the note backup; re-sync shipped ones.
                runCatching { syncBootstrapReaderTexts() }
                return
            }
        }
        var imported = 0
        runner {
            imported = bootstrapNotes?.invoke()?.takeIf { it.isNotBlank() }?.let { importJsonLines(it) } ?: 0
            bootstrapReaderTexts?.invoke()?.takeIf { it.isNotBlank() }?.let { importReaderTextsJsonLines(it) }
        }
        if (imported > 0) return

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

    suspend fun addNote(note: Note): Long {
        val noteId = noteDao.insert(note)
        cardDao.insertAll(cardsFor(note.copy(id = noteId)))
        invalidateNoteStructure()
        return noteId
    }

    suspend fun importJsonLines(jsonLines: String): Int {
        val pendingPartners = mutableListOf<Pair<Long, String>>()
        var imported = 0
        runInTransaction {
            jsonLines.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val json = JSONObject(line)
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
                        audioPath = json.optCleanString("audioPath"),
                        tags = json.optString("tags", ""),
                        tier = json.optInt("tier", 1),
                        unit = json.optIntOrNull("unit"),
                        conceptId = json.optCleanString("conceptId"),
                        cefrLevel = json.optCleanString("cefrLevel")
                    )
                    val noteId = addNote(note)
                    if (partnerLemma != null) pendingPartners += noteId to partnerLemma
                    // Restore SRS state if this is a full-state backup.
                    val cardsJson = if (json.has("_cards")) json.optJSONArray("_cards") else null
                    if (cardsJson != null) {
                        val freshByVariant = cardDao.getCardsForNote(noteId).associateBy { it.srsVariantKey() }
                        val updates = buildList {
                            repeat(cardsJson.length()) { ci ->
                                val cj = cardsJson.getJSONObject(ci)
                                val existing = freshByVariant[cj.srsVariantKey()] ?: return@repeat
                                add(
                                    existing.copy(
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
                                )
                            }
                        }
                        if (updates.isNotEmpty()) cardDao.updateAll(updates)
                    }
                    imported += 1
                }

            pendingPartners.forEach { (noteId, partnerLemma) ->
                val note = noteDao.getById(noteId) ?: return@forEach
                val partner = noteDao.getByLemma(partnerLemma) ?: return@forEach
                noteDao.update(note.copy(aspectPartner = partner.id))
                confusablePairDao.insert(ConfusablePair(firstNoteId = note.id, secondNoteId = partner.id, reason = "aspect_partner"))
                // Re-run cardsFor so BI/no_aspect_pair guards apply and both NO_CUE + HAS_CUE are added
                insertMissingAspectCards(note.id)
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
        val missing = cardsFor(noteDao.getById(noteId) ?: return)
            .filter { it.cardType == CardType.ASPECT_SELECT && it.gramContextCue !in existingAspectCues }
        if (missing.isNotEmpty()) cardDao.insertAll(missing)
    }

    suspend fun exportJsonLines(): String = exportLines(includeSrs = false)

    suspend fun exportFullState(): String = exportLines(includeSrs = true)

    private suspend fun exportLines(includeSrs: Boolean): String {
        val notes = noteDao.getAll()
        val noteById = notes.associateBy { it.id }
        // Pre-fetch all cards so we don't call suspend functions inside joinToString's lambda
        val cardsByNoteId: Map<Long, List<Card>> = if (includeSrs) {
            notes.associate { note -> note.id to cardDao.getCardsForNote(note.id) }
        } else emptyMap()
        return notes
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
                    put("audioPath", note.audioPath)
                    put("tags", note.tags)
                    put("tier", note.tier)
                    put("unit", note.unit)
                    put("conceptId", note.conceptId)
                    put("cefrLevel", note.cefrLevel)
                    val cards = cardsByNoteId[note.id]
                    if (!cards.isNullOrEmpty()) {
                        put("_cards", org.json.JSONArray().apply {
                            cards.forEach { card ->
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
                                })
                            }
                        })
                    }
                }.toString()
            }
    }

    suspend fun addReaderText(title: String, body: String, source: String = "local"): Long =
        readerTextDao.insert(ReaderText(title = title.ifBlank { "Imported Text" }, body = body, source = source))

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

    suspend fun readerTexts(): List<ReaderRecommendation> {
        val index = formIndex()
        val known = knownNoteIds()
        val covered = readerTextDao.getAll().map { coverageFor(it, index, known) }
        val targetReady = covered.any { it.text.source.startsWith("target:", ignoreCase = true) && it.coverage >= AUTHENTIC_READY_COVERAGE }
        return covered.map { it.copy(authenticReady = targetReady) }
    }

    suspend fun readerTokens(text: ReaderText): List<ReaderToken> {
        val notes = allNotesCached()
        val index = formIndex()
        val known = knownNoteIds()
        val statusById = HashMap<Long, WordStatus>(notes.size)
        for (n in notes) statusById[n.id] = n.status
        return tokenize(text.body).map { token ->
            val normalized = normalizeToken(token)
            val note = index[normalized]
            val freshStatus = note?.let { statusById[it.id] } ?: WordStatus.NEW
            val derivedKnown = note != null && note.id in known
            val status = when {
                note != null && freshStatus != WordStatus.NEW -> freshStatus
                derivedKnown -> WordStatus.KNOWN
                else -> WordStatus.NEW
            }
            ReaderToken(
                surface = token,
                normalized = normalized,
                known = status == WordStatus.KNOWN || status == WordStatus.IGNORED,
                status = status,
                lemma = note?.lemma,
                parse = note?.let { parseToken(token, it) },
                aktionsart = note?.aktionsart,
                stressForm = note?.russian,
                translation = note?.translation,
                exampleSentence = note?.exampleSentence
            )
        }
    }

    /**
     * Explicitly set the reading status of a tapped word. Creates a lightweight
     * tracking note if the word isn't in the deck yet, so status survives.
     */
    suspend fun setWordStatus(token: String, status: WordStatus): Note? {
        val normalized = normalizeToken(token)
        val match = formIndex()[normalized] ?: noteDao.getByLemma(normalized)
        if (match != null) {
            // Re-read the live row so we don't write back a stale encounterCount.
            val fresh = noteDao.getById(match.id) ?: match
            noteDao.update(fresh.copy(status = status))
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

    suspend fun dashboardStats(now: Long = System.currentTimeMillis()): DashboardStats =
        dashboardStatsFrom(now, readerTexts())

    private suspend fun dashboardStatsFrom(now: Long, recommendations: List<ReaderRecommendation>): DashboardStats {
        val notes = allNotesCached()
        val targetCoverages = recommendations.filter { it.text.source.startsWith("target:", ignoreCase = true) }.map { it.coverage }
        val qualityReport = importQualityReport(notes, recommendations)
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
            importQualityReport = qualityReport
        )
    }

    suspend fun importQualityReport(): ImportQualityReport =
        importQualityReport(allNotesCached(), readerTexts())

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
            triageMode = dueCount > TRIAGE_THRESHOLD
        )
    }

    suspend fun sessionPlan(now: Long = System.currentTimeMillis()): SessionPlan {
        refreshGraduationsIfNeeded()
        val categories = accuracyCategoriesCached()
        val daily = dailyPlanFromCategories(now, categories)
        val blocked = blockedGrammarPrompts(daily, now)
        // Compute once; reuse for both readerRecommendation and dashboardStats.
        val allTexts = readerTexts()
        return SessionPlan(
            ruleSummary = ruleSummaryFor(daily.openBlockedWith),
            reviewQueue = sessionCards(now, config().sessionSize, daily).mapNotNull { promptFor(it, now) },
            blockedGrammar = blocked,
            interleavedGrammar = interleavedGrammarPrompts(blocked.map { it.card.id }.toSet(), now),
            readerRecommendation = allTexts.minWithOrNull(compareBy<ReaderRecommendation> { distanceFromTarget(it.coverage) }.thenByDescending { it.coverage }),
            dashboardStats = dashboardStatsFrom(now, allTexts),
            dailyPlan = daily,
            gamification = gamificationStats(now)
        )
    }

    suspend fun gamificationStats(now: Long = System.currentTimeMillis()): GamificationStats {
        val dailyGoal = config().dailyGoal
        val tzOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val days = reviewLogDao.reviewDayBuckets(tzOffset, DAY_MILLIS)
        val daySet = days.toHashSet()
        val todayBucket = (now + tzOffset) / DAY_MILLIS

        // Current streak: count back from today (or yesterday, if nothing yet today).
        var currentStreak = 0
        if (todayBucket in daySet || (todayBucket - 1) in daySet) {
            var day = if (todayBucket in daySet) todayBucket else todayBucket - 1
            while (day in daySet) {
                currentStreak += 1
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

        val totalReviews = reviewLogDao.countAll()
        val xp = totalReviews * XP_PER_REVIEW
        // Level L costs L * XP_PER_LEVEL_STEP to advance; spend xp level by level.
        var level = 1
        var remaining = xp
        while (remaining >= level * XP_PER_LEVEL_STEP) {
            remaining -= level * XP_PER_LEVEL_STEP
            level += 1
        }
        val knownWords = knownNoteIds().size
        val reviewedToday = reviewLogDao.countSince(now - (now % DAY_MILLIS))
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
            longestStreak = longestStreak,
            reviewedToday = reviewedToday,
            dailyGoal = dailyGoal,
            activeDays = days.size,
            last7Days = last7,
            achievements = achievements
        )
    }

    private fun achievement(id: String, title: String, description: String, unlocked: Boolean) =
        Achievement(id, title, description, unlocked)

    /** Cheap stats for the daily reminder notification (no form-index build). */
    suspend fun reminderInfo(now: Long = System.currentTimeMillis()): ReminderInfo {
        val tzOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val daySet = reviewLogDao.reviewDayBuckets(tzOffset, DAY_MILLIS).toHashSet()
        val todayBucket = (now + tzOffset) / DAY_MILLIS
        var streak = 0
        if (todayBucket in daySet || (todayBucket - 1) in daySet) {
            var day = if (todayBucket in daySet) todayBucket else todayBucket - 1
            while (day in daySet) { streak += 1; day -= 1 }
        }
        return ReminderInfo(
            currentStreak = streak,
            studiedToday = todayBucket in daySet,
            dueToday = cardDao.countDue(now)
        )
    }

    suspend fun nextPrompt(now: Long = System.currentTimeMillis()): ReviewPrompt? =
        sessionPlan(now).reviewQueue.firstOrNull()

    /** Build a review prompt for a specific card (used to re-present after undo). */
    suspend fun promptForCard(card: Card, now: Long = System.currentTimeMillis()): ReviewPrompt? =
        promptFor(card, now)

    suspend fun grammarDrillPrompts(now: Long = System.currentTimeMillis(), limit: Int = 10): List<ReviewPrompt> {
        val plan = sessionPlan(now)
        return (plan.blockedGrammar + plan.interleavedGrammar).take(limit)
    }

    suspend fun review(card: Card, rating: Rating, now: Long = System.currentTimeMillis()) {
        val note = noteDao.getById(card.noteId)
        // Snapshot the live card + encounter count before mutating, for undo.
        lastUndo = UndoSnapshot(card = card, noteId = card.noteId, priorEncounterCount = note?.encounterCount ?: 0)
        // A lesson is "done" the moment it's read: graduate it so it never recurs.
        // We still log it (stateBefore = NEW) so it counts as the concept's
        // introduction — that is what unlocks the concept's drills.
        if (card.cardType == CardType.LESSON) {
            val graduated = card.copy(
                state = CardState.GRADUATED,
                reps = card.reps + 1,
                lastReview = now,
                due = now + 365L * DAY_MILLIS
            )
            cardDao.update(graduated)
            reviewLogDao.insert(
                ReviewLog(
                    cardId = card.id,
                    reviewDatetime = now,
                    rating = rating,
                    stateBefore = card.state,
                    scheduledDays = 365,
                    elapsedDays = 0,
                    source = ReviewSource.GRAMMAR_DRILL
                )
            )
            note?.let { noteDao.update(it.copy(encounterCount = it.encounterCount + 1)) }
            invalidateNoteState()
            return
        }
        val (updatedCard, log) = scheduler.review(card, rating, now)
        cardDao.update(updatedCard)
        reviewLogDao.insert(log)
        note?.let { noteDao.update(it.copy(encounterCount = it.encounterCount + 1)) }
        invalidateNoteState()
        refreshGraduationsIfNeeded(force = true)
    }

    /** True if there is a review that can be rolled back this session. */
    fun canUndo(): Boolean = lastUndo != null

    /**
     * Roll back the most recent [review]: restore the card's pre-review SRS state,
     * delete the log row it produced, and restore the note's encounter count.
     * Returns the restored card so the caller can re-present it, or null if there
     * was nothing to undo. A category may have graduated on the way in; we don't
     * un-graduate, which is harmless (graduation re-checks accuracy each session).
     */
    suspend fun undoLastReview(): Card? {
        val snapshot = lastUndo ?: return null
        lastUndo = null
        reviewLogDao.deleteLatestForCard(snapshot.card.id)
        cardDao.update(snapshot.card)
        noteDao.getById(snapshot.noteId)?.let {
            noteDao.update(it.copy(encounterCount = snapshot.priorEncounterCount))
        }
        invalidateNoteState()
        return snapshot.card
    }

    /** Permanently retire a card (e.g. a bad auto-generated item) from all queues. */
    suspend fun suspendCard(card: Card) {
        cardDao.update(card.copy(suspended = true))
        invalidateNoteState()
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
                setWordStatus(token, status)
                changed += 1
            }
        }
        return changed
    }

    suspend fun readerRecommendation(): ReaderRecommendation? =
        readerTexts().minWithOrNull(compareBy<ReaderRecommendation> { distanceFromTarget(it.coverage) }.thenByDescending { it.coverage })

    @Suppress("UNUSED_PARAMETER")
    suspend fun readerLookup(token: String, text: ReaderText, now: Long = System.currentTimeMillis()): Note? {
        val normalized = normalizeToken(token)
        val note = formIndex()[normalized]?.let { noteDao.getById(it.id) }
        if (note != null) {
            noteDao.update(note.copy(encounterCount = note.encounterCount + 1))
            invalidateNoteState()
            val card = cardDao.getCardsForNote(note.id).firstOrNull()
            if (card != null) {
                reviewLogDao.insert(
                    ReviewLog(
                        cardId = card.id,
                        reviewDatetime = now,
                        rating = Rating.GOOD,
                        stateBefore = card.state,
                        scheduledDays = card.scheduledDays,
                        elapsedDays = 0,
                        source = ReviewSource.READER_LOOKUP
                    )
                )
            }
            refreshGraduationsIfNeeded(force = true)
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

    suspend fun reviewedToday(now: Long = System.currentTimeMillis()): Int =
        reviewLogDao.countSince(startOfLocalDay(now))

    /**
     * Start-of-today in the device's local timezone. Using local (not UTC) day
     * boundaries keeps "reviewed today", the daily goal, and the new-card throttle
     * consistent with the streak counter — otherwise counts reset at the wrong hour
     * for every non-UTC user and drift across DST.
     */
    private fun startOfLocalDay(now: Long): Long {
        val tz = java.util.TimeZone.getDefault().getOffset(now).toLong()
        return ((now + tz) / DAY_MILLIS) * DAY_MILLIS - tz
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

    private suspend fun sessionCards(now: Long, limit: Int, plan: DailyPlan): List<Card> {
        val due = if (plan.triageMode) {
            cardDao.getOverdueCards(now - 2 * DAY_MILLIS, limit = limit)
        } else {
            cardDao.getDueCards(now, limit = limit)
        }
        // When real reviews are due we never dilute them with new material; due
        // cards (with their confusable partners) keep their existing ordering.
        if (due.isNotEmpty()) return dueSessionCards(due, now, limit)
        // Otherwise introduce new cards with research-based pacing.
        return newCardSession(now, limit)
    }

    /** Due-review session: surface scheduled cards plus their confusable partners. */
    private suspend fun dueSessionCards(base: List<Card>, now: Long, limit: Int): List<Card> {
        val session = mutableListOf<Card>()
        val sessionIds = mutableSetOf<Long>()
        for (card in base) {
            if (sessionIds.add(card.id)) session += card
            for (pair in confusablePairDao.getForNote(card.noteId)) {
                val partnerNoteId = if (pair.firstNoteId == card.noteId) pair.secondNoteId else pair.firstNoteId
                val partner = cardDao.getCardsForNote(partnerNoteId)
                    .filter { it.matchesCardVariant(card) && it.id !in sessionIds }
                    .sortedWith(compareBy<Card> { it.due }.thenBy { it.id })
                    .firstOrNull { it.state != CardState.NEW && it.due <= now }
                if (partner != null && sessionIds.add(partner.id)) session += partner
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
    private suspend fun newCardSession(now: Long, limit: Int): List<Card> {
        val introducedToday = reviewLogDao.countNewIntroducedSince(startOfLocalDay(now))
        val budget = (config().newCardsPerDay - introducedToday).coerceAtLeast(0)
        val take = minOf(limit, budget)
        if (take == 0) return emptyList()

        // Pull a generous pool, already in curriculum order (A1 tier first, by unit,
        // then by frequency rank). Drop grammar drills whose teaching lesson the
        // learner hasn't seen yet — concept gating keeps "teach before test" true.
        val locked = lockedConceptIds()
        val pool = cardDao.getNewCardsOrdered(limit = maxOf(limit * 4, 200))
            .filterNot { isConceptLocked(it, locked) }
        // Group by note, preserving the pool's curriculum order for *note* ordering
        // (first appearance of each note), then order each note's own cards
        // comprehension-first (lesson → recognition → production → grammar).
        val grouped = LinkedHashMap<Long, MutableList<Card>>()
        for (card in pool) grouped.getOrPut(card.noteId) { mutableListOf() }.add(card)
        val byNote = LinkedHashMap<Long, ArrayDeque<Card>>()
        for ((noteId, cards) in grouped) {
            byNote[noteId] = ArrayDeque(
                cards.sortedWith(compareBy<Card> { introductionTier(it) }.thenBy { introductionTier2(it) }.thenBy { it.id })
            )
        }
        val session = mutableListOf<Card>()
        val notesWithVocab = mutableSetOf<Long>()
        // Round-robin across notes until the budget is filled or material runs out.
        while (session.size < take && byNote.values.any { it.isNotEmpty() }) {
            for ((noteId, queue) in byNote) {
                if (session.size >= take) break
                // Skip a vocab card if this note already contributed one this session.
                while (queue.isNotEmpty() && queue.first().queue == Queue.VOCAB && noteId in notesWithVocab) {
                    queue.removeFirst()
                }
                val card = queue.removeFirstOrNull() ?: continue
                if (card.queue == Queue.VOCAB) notesWithVocab += noteId
                session += card
            }
        }
        return session
    }

    /**
     * Grammar concepts the learner has not been taught yet: concepts that have a
     * LESSON card which hasn't been reviewed. Drills on these stay dormant until the
     * lesson is seen. Concepts with no lesson at all (e.g. legacy/migrated decks)
     * are never locked, so existing study is never blocked.
     */
    private suspend fun lockedConceptIds(): Set<String> {
        val withLessons = cardDao.getConceptIdsWithLessons().toHashSet()
        if (withLessons.isEmpty()) return emptySet()
        val introduced = cardDao.getIntroducedConceptIds().toHashSet()
        return (withLessons - introduced)
    }

    /** True if [card] is a grammar drill whose teaching concept is still locked. */
    private fun isConceptLocked(card: Card, locked: Set<String>): Boolean {
        if (locked.isEmpty()) return false
        if (card.cardType == CardType.LESSON) return false
        if (card.queue != Queue.GRAMMAR) return false
        val concept = GrammarConcepts.forCard(card)?.id ?: card.gramConcept ?: return false
        return concept in locked
    }

    /** Coarse introduction tier: lesson (0) → receptive (1) → productive (2) → grammar (3). */
    private fun introductionTier(card: Card): Int = when (card.cardType) {
        CardType.LESSON -> -1
        CardType.RU_TO_MEANING, CardType.AUDIO_TO_RU -> 0
        CardType.MEANING_TO_RU, CardType.CLOZE -> 1
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

    private suspend fun promptFor(card: Card, now: Long): ReviewPrompt? {
        val note = noteDao.getById(card.noteId) ?: return null
        return buildPrompt(card, note, scheduler.preview(card, now), note.aspectPartner?.let { noteDao.getById(it) })
    }

    private suspend fun blockedGrammarPrompts(plan: DailyPlan, now: Long): List<ReviewPrompt> {
        val category = plan.openBlockedWith ?: return emptyList()
        val locked = lockedConceptIds()
        val cards = when (category.kind) {
            "case" -> cardDao.getCaseDrillCards(category.gramCase.orEmpty(), category.gramGender.orEmpty(), category.gramNumber.orEmpty(), 5)
            "verb_form" -> cardDao.getVerbFormCards(category.contextCue.orEmpty(), 5)
            else -> cardDao.getAspectCards().filter { card ->
                    val note = noteDao.getById(card.noteId)
                    note?.aktionsart == category.aktionsart && note?.aspect == category.aspect && card.gramContextCue == category.contextCue
                }.take(5)
        }
        return cards.filterNot { isConceptLocked(it, locked) }.mapNotNull { promptFor(it, now) }
    }

    private suspend fun interleavedGrammarPrompts(excludeIds: Set<Long>, now: Long): List<ReviewPrompt> {
        val locked = lockedConceptIds()
        return cardDao.getGrammarDrillCards(40)
            .filter { it.id !in excludeIds && it.cardType != CardType.LESSON }
            .filterNot { isConceptLocked(it, locked) }
            .take(10)
            .mapNotNull { promptFor(it, now) }
    }

    private fun cardsFor(note: Note): List<Card> = buildList {
        // A lesson note (pos = "lesson") teaches one grammar concept and produces a
        // single LESSON card — no vocab/drill cards. Seeing it is what unlocks the
        // concept's drills (concept gating).
        if (note.partOfSpeech.equals("lesson", ignoreCase = true)) {
            add(Card(noteId = note.id, cardType = CardType.LESSON, queue = Queue.GRAMMAR, due = 0L, gramConcept = note.conceptId))
            return@buildList
        }
        // The general reading-matrix layer carries declension tables purely to
        // feed the reader form index (coverage); grammar drilling stays focused
        // on the curated domain corpus, so general notes get only vocab cards.
        val isGeneral = note.tags.contains("general")
        add(Card(noteId = note.id, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
        add(Card(noteId = note.id, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB))
        // Cloze blanks a word inside the example sentence — only useful if the learner
        // can read that sentence, i.e. it ships with a real sentence-level translation.
        if (hasReadableExample(note)) add(Card(noteId = note.id, cardType = CardType.CLOZE, queue = Queue.VOCAB))
        // Listening: a real audio asset, or any curated domain word (the on-device
        // Russian TTS supplies the audio at review time, so no asset is needed).
        if (!note.audioPath.isNullOrBlank() || !isGeneral) {
            add(Card(noteId = note.id, cardType = CardType.AUDIO_TO_RU, queue = Queue.VOCAB))
        }
        if (!isGeneral) caseCards(note).forEach(::add)
        if (!isGeneral) verbFormCards(note).forEach(::add)
        if (!isGeneral) adjectiveAgreementCards(note).forEach(::add)
        if (!isGeneral) genderCard(note)?.let(::add)
        // ASPECT_SELECT requires a verified Aktionsart (design F8): the drill's
        // whole point is reasoning from inherent temporal structure, so a verb
        // without Aktionsart never produces a half-formed aspect card.
        val isAspectDrillable = !isGeneral &&
            note.aspect != "BI" &&
            !note.tags.contains("no_aspect_pair") &&
            !note.aktionsart.isNullOrBlank() &&
            note.aspectPartner != null &&
            !note.aspect.isNullOrBlank()
        if (isAspectDrillable) {
            add(Card(noteId = note.id, cardType = CardType.ASPECT_SELECT, queue = Queue.GRAMMAR, due = 0L, gramContextCue = "NO_CUE", gramConcept = GrammarConcepts.ASPECT.id))
            add(Card(noteId = note.id, cardType = CardType.ASPECT_SELECT, queue = Queue.GRAMMAR, due = 0L, gramContextCue = "HAS_CUE", gramConcept = GrammarConcepts.ASPECT.id))
        }
    }

    /**
     * True when the note ships an example sentence the learner can actually read: a
     * sentence plus a real sentence-level translation (more than one word, and not
     * just the headword gloss). Gates comprehension-dependent cards like CLOZE.
     */
    private fun hasReadableExample(note: Note): Boolean {
        val sentence = note.exampleSentence?.trim().orEmpty()
        val gloss = note.exampleTranslation?.trim().orEmpty()
        if (sentence.isBlank() || gloss.isBlank()) return false
        if (gloss.equals(note.translation.trim(), ignoreCase = true)) return false
        // A real translation of a sentence has multiple words.
        return gloss.split(Regex("\\s+")).size >= 2
    }

    // Adjective–noun agreement: produce the feminine, neuter, and plural nominative
    // forms (the masculine is the citation form). Russian agreement is one of the
    // highest-frequency grammar skills and the forms already ship in the data, but
    // they were previously only used for reader matching, never drilled.
    private fun adjectiveAgreementCards(note: Note): List<Card> {
        if (!note.partOfSpeech.equals("adjective", ignoreCase = true)) return emptyList()
        val json = note.declensionJson ?: return emptyList()
        val table = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val source = if (table.has("cases")) table.getJSONObject("cases") else table
        val masc = RussianForms.normalize(source.optString("NOM_SG").ifBlank { note.lemma })
        return listOf("FEM" to "FEM_NOM", "NEUT" to "NEUT_NOM", "PL" to "PL_NOM").mapNotNull { (cue, key) ->
            val form = source.optString(key)
            if (form.isBlank() || RussianForms.normalize(form) == masc) return@mapNotNull null
            Card(noteId = note.id, cardType = CardType.ADJ_AGREE, queue = Queue.GRAMMAR, gramContextCue = cue, gramConcept = GrammarConcepts.ADJ_AGREE.id)
        }
    }

    // Noun gender recall. Gender drives every agreement choice but was stored and
    // never tested; one fast card per noun makes the pattern explicit.
    private fun genderCard(note: Note): Card? {
        if (!note.partOfSpeech.equals("noun", ignoreCase = true)) return null
        val gender = note.gender?.uppercase(Locale.ROOT) ?: return null
        if (gender !in NOUN_GENDERS) return null
        return Card(noteId = note.id, cardType = CardType.GENDER_ID, queue = Queue.GRAMMAR, gramGender = gender, gramConcept = GrammarConcepts.GENDER.id)
    }

    private fun caseCards(note: Note): List<Card> {
        val json = note.declensionJson ?: return emptyList()
        val gender = note.gender ?: return emptyList()
        val table = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val source = if (table.has("cases")) table.getJSONObject("cases") else table
        val nominativeByNumber = mapOf(
            "SG" to source.optString("NOM_SG"),
            "PL" to source.optString("NOM_PL")
        )
        return source.keys().asSequence()
            .map { key -> key.uppercase(Locale.ROOT) }
            .mapNotNull { key ->
                val parts = key.split("_")
                val gramCase = parts.getOrNull(0)?.takeIf { it in CASES } ?: return@mapNotNull null
                if (gramCase == "NOM") return@mapNotNull null
                val gramNumber = parts.getOrNull(1)?.takeIf { it in NUMBERS } ?: if (gender == "PL") "PL" else "SG"
                val answer = source.optString(key)
                val nominative = nominativeByNumber[gramNumber].orEmpty()
                if (answer.isBlank() || RussianForms.normalize(answer) == RussianForms.normalize(nominative)) return@mapNotNull null
                Card(
                    noteId = note.id,
                    cardType = CardType.CASE_FILL,
                    queue = Queue.GRAMMAR,
                    gramCase = gramCase,
                    gramGender = gender,
                    gramNumber = gramNumber,
                    gramConcept = gramCase
                )
            }
            .toList()
    }

    // Only drill the past-tense paradigm. Russian past tense is regular (stem + л
    // with gender/number agreement), so derived answers are trustworthy. Present
    // tense is riddled with consonant mutation (писать→пишу, любить→люблю) that we
    // cannot derive from the infinitive, and the deck ships no present tables — so
    // drilling it would teach learners incorrect forms. Past-only keeps the grammar
    // sense (gender/number agreement) without ever presenting a wrong answer.
    private fun verbFormCards(note: Note): List<Card> {
        if (!note.partOfSpeech.equals("verb", ignoreCase = true)) return emptyList()
        return RussianForms.verbForms(note.lemma).keys
            .filter { key -> key in VERB_FORM_KEYS }
            .map { key ->
                Card(
                    noteId = note.id,
                    cardType = CardType.VERB_FORM,
                    queue = Queue.GRAMMAR,
                    gramContextCue = key,
                    gramConcept = GrammarConcepts.PAST.id
                )
            }
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
        val nounKeys = cardDao.getCaseCategoryKeys()
            .map { key ->
                val ratings = reviewLogDao.nounCategoryRatings(key.gramCase, key.gramGender, key.gramNumber, MIN_ACCURACY_SAMPLE)
                CategoryKey("case", key.gramCase, key.gramGender, key.gramNumber, accuracy = ratings.accuracyOrNull(), sampleSize = ratings.size)
            }

        val aspectKeys = cardDao.getAspectCategoryKeys()
            .map { key ->
                val ratings = reviewLogDao.aspectCategoryRatings(key.aktionsart, key.aspect, key.contextCue, MIN_ACCURACY_SAMPLE)
                CategoryKey("aspect", aktionsart = key.aktionsart, aspect = key.aspect, contextCue = key.contextCue, accuracy = ratings.accuracyOrNull(), sampleSize = ratings.size)
            }
        val verbFormKeys = cardDao.getVerbFormCategoryKeys()
            .map { key ->
                val ratings = reviewLogDao.verbFormCategoryRatings(key, MIN_ACCURACY_SAMPLE)
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
        cardDao.graduateVocabForEncounteredNotes(VOCAB_GRADUATION_ENCOUNTERS)
    }

    private fun buildFormIndex(notes: List<Note>): Map<String, Note> {
        val map = HashMap<String, Note>()
        for (note in notes) {
            for (form in RussianForms.surfaceForms(note)) map.putIfAbsent(form, note)
        }
        return map
    }


    private fun coverageFor(text: ReaderText, index: Map<String, Note>, knownIds: Set<Long>): ReaderRecommendation {
        val tokens = tokenize(text.body)
        val knownCount = tokens.count { token ->
            val note = index[normalizeToken(token)]
            note != null && note.id in knownIds
        }
        val coverage = if (tokens.isEmpty()) 0.0 else knownCount.toDouble() / tokens.size
        return ReaderRecommendation(
            text = text,
            coverage = coverage,
            knownTokens = knownCount,
            totalTokens = tokens.size,
            status = statusForCoverage(coverage),
            authenticReady = false
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

    private fun normalizeToken(value: String): String = RussianForms.normalize(value)

    private fun statusForCoverage(coverage: Double): ReaderStatus =
        when {
            coverage < MIN_READER_COVERAGE -> ReaderStatus.TOO_HARD
            coverage <= PRODUCTIVE_COVERAGE_MAX -> ReaderStatus.PRODUCTIVE
            else -> ReaderStatus.EASY
        }

    private fun importQualityReport(notes: List<Note>, recommendations: List<ReaderRecommendation>): ImportQualityReport {
        val readyNominals = notes.count { it.isNominalReady() }
        val aspectReadyVerbs = notes.count { it.isAspectReadyVerb() }
        val verifiedAktionsartVerbs = notes.count { it.isAspectReadyVerb() && it.hasVerifiedAktionsart() }
        val domainRanked = notes.count { it.domainFreqRank != null }
        val examples = notes.count { !it.exampleSentence.isNullOrBlank() }
        val targetReady = recommendations.count { it.text.source.startsWith("target:", ignoreCase = true) && it.coverage >= AUTHENTIC_READY_COVERAGE }
        val warnings = buildList {
            if (readyNominals < DESIGN_DOC_MIN_NOMINAL_ROWS) add("Need $DESIGN_DOC_MIN_NOMINAL_ROWS noun/adjective rows with declension, gender, domain rank, and example.")
            if (aspectReadyVerbs < DESIGN_DOC_MIN_VERB_ROWS) add("Need $DESIGN_DOC_MIN_VERB_ROWS verb rows with aspect partner, Aktionsart, domain rank, and example.")
            if (verifiedAktionsartVerbs < DESIGN_DOC_MIN_VERB_ROWS) add("Need $DESIGN_DOC_MIN_VERB_ROWS aspect-ready verbs with high/manual Aktionsart verification.")
            if (targetReady == 0) add("Need at least one target-source reader text at 90%+ coverage.")
        }
        return ImportQualityReport(
            totalNotes = notes.size,
            readyNominalRows = readyNominals,
            aspectReadyVerbRows = aspectReadyVerbs,
            verifiedAktionsartVerbRows = verifiedAktionsartVerbs,
            domainRankedRows = domainRanked,
            exampleRows = examples,
            targetTextsAtOrAbove90 = targetReady,
            minNominalRows = DESIGN_DOC_MIN_NOMINAL_ROWS,
            minVerbRows = DESIGN_DOC_MIN_VERB_ROWS,
            meetsDesignDocMinimum = warnings.isEmpty(),
            warnings = warnings
        )
    }

    private fun Note.isNominalReady(): Boolean =
        partOfSpeech.lowercase(Locale.ROOT) in setOf("noun", "adjective") &&
            !declensionJson.isNullOrBlank() &&
            !gender.isNullOrBlank() &&
            domainFreqRank != null &&
            !exampleSentence.isNullOrBlank()

    private fun Note.isAspectReadyVerb(): Boolean =
        partOfSpeech.lowercase(Locale.ROOT) == "verb" &&
            aspectPartner != null &&
            !aspect.isNullOrBlank() &&
            !aktionsart.isNullOrBlank() &&
            domainFreqRank != null &&
            !exampleSentence.isNullOrBlank()

    private fun Note.hasVerifiedAktionsart(): Boolean =
        aktionsartConfidence?.lowercase(Locale.ROOT) in setOf("high", "manual", "verified")

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

    private fun JSONObject.optCleanString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    companion object {
        private const val DAY_MILLIS = 86_400_000L
        private const val TRIAGE_THRESHOLD = 80
        private const val MIN_ACCURACY_SAMPLE = 30
        private const val GRADUATION_ACCURACY = 0.90
        private const val VOCAB_GRADUATION_ENCOUNTERS = 15
        private const val READER_KNOWN_ENCOUNTERS = 3
        private const val MIN_READER_COVERAGE = 0.90
        private const val PRODUCTIVE_COVERAGE_MAX = 0.96
        private const val AUTHENTIC_READY_COVERAGE = 0.90
        private const val DESIGN_DOC_MIN_NOMINAL_ROWS = 200
        private const val DESIGN_DOC_MIN_VERB_ROWS = 100
        private const val DAILY_GOAL = 20
        private const val XP_PER_REVIEW = 10
        private const val XP_PER_LEVEL_STEP = 100
        private val CASES = setOf("NOM", "ACC", "GEN", "DAT", "INS", "PREP")
        private val NUMBERS = setOf("SG", "PL")
        private val NOUN_GENDERS = setOf("M", "F", "N", "PL")
        // Past-tense only: see verbFormCards. Present tense can't be derived
        // reliably from the infinitive, so we don't drill it.
        private val VERB_FORM_KEYS = setOf("PAST_M", "PAST_F", "PAST_N", "PAST_PL")
    }
}
