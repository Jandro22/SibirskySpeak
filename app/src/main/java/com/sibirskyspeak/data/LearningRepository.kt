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
    val sessionSize: Int = 25
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
    private val config: () -> LearningConfig = { LearningConfig() }
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

    private fun invalidateNoteStructure() {
        notesCache = null
        formIndexCache = null
        knownIdsCache = null
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

    private suspend fun computeKnownNoteIds(notes: List<Note>): Set<Long> {
        val cardKnown = cardDao.getAllVocabCards()
            .filter { card ->
                card.state == CardState.GRADUATED ||
                    card.reps > 0 ||
                    card.consecutiveCorrect > 0 ||
                    card.lastReview != null
            }
            .map { it.noteId }
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
                    tags = json.optString("tags", "")
                )
                val noteId = addNote(note)
                if (partnerLemma != null) pendingPartners += noteId to partnerLemma
                // Restore SRS state if this is a full-state backup
                val cardsJson = if (json.has("_cards")) json.optJSONArray("_cards") else null
                if (cardsJson != null) {
                    val fresh = cardDao.getCardsForNote(noteId)
                    val freshByVariant = fresh.associateBy { it.srsVariantKey() }
                    repeat(cardsJson.length()) { ci ->
                        val cj = cardsJson.getJSONObject(ci)
                        val existing = freshByVariant[cj.srsVariantKey()] ?: return@repeat
                        cardDao.update(existing.copy(
                            state = CardState.valueOf(cj.getString("state")),
                            stability = cj.getDouble("stability"),
                            difficulty = cj.getDouble("difficulty"),
                            elapsedDays = cj.getInt("elapsedDays"),
                            scheduledDays = cj.getInt("scheduledDays"),
                            reps = cj.getInt("reps"),
                            lapses = cj.getInt("lapses"),
                            due = cj.getLong("due"),
                            lastReview = if (cj.isNull("lastReview")) null else cj.getLong("lastReview"),
                            consecutiveCorrect = cj.optInt("consecutiveCorrect", 0)
                        ))
                    }
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
        var added = 0
        payload.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val json = JSONObject(line)
                val title = json.optString("title", "Imported Text")
                if (title !in existingTitles) {
                    addReaderText(title, json.getString("body"), json.optString("source", "local"))
                    existingTitles += title
                    added += 1
                }
            }
        return added
    }

    suspend fun importReaderTextsJsonLines(jsonLines: String): Int {
        var imported = 0
        jsonLines.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val json = JSONObject(line)
                addReaderText(
                    title = json.optString("title", "Imported Text"),
                    body = json.getString("body"),
                    source = json.optString("source", "local")
                )
                imported += 1
            }
        return imported
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
        val notes = noteDao.getAll()
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
        importQualityReport(noteDao.getAll(), readerTexts())

    suspend fun dailyPlan(now: Long = System.currentTimeMillis()): DailyPlan {
        val categories = accuracyCategories()
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
        graduateEligibleCategories()
        graduateVocabByEncounters()
        val daily = dailyPlan(now)
        val blocked = blockedGrammarPrompts(daily, now)
        // Compute once; reuse for both readerRecommendation and dashboardStats.
        val allTexts = readerTexts()
        return SessionPlan(
            ruleSummary = ruleSummaryFor(daily.openBlockedWith),
            reviewQueue = sessionCards(now, config().sessionSize).mapNotNull { promptFor(it, now) },
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
            run = if (previous != null && day == previous!! + 1) run + 1 else 1
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
            achievement("polyglot", "Polyglot", "Know 1,000 words", knownWords >= 1000),
            achievement("words_2000", "Erudite", "Know 2,000 words", knownWords >= 2000),
            achievement("words_5000", "Native Range", "Know 5,000 words", knownWords >= 5000),
            // --- Reviews done ---
            achievement("rev_10", "Warming Up", "10 reviews", totalReviews >= 10),
            achievement("rev_50", "In the Groove", "50 reviews", totalReviews >= 50),
            achievement("centurion", "Centurion", "100 reviews", totalReviews >= 100),
            achievement("rev_500", "Workhorse", "500 reviews", totalReviews >= 500),
            achievement("dedicated", "Dedicated", "1,000 reviews", totalReviews >= 1000),
            achievement("rev_5000", "Relentless", "5,000 reviews", totalReviews >= 5000),
            achievement("rev_10000", "Machine", "10,000 reviews", totalReviews >= 10000),
            achievement("rev_25000", "Unstoppable", "25,000 reviews", totalReviews >= 25000),
            // --- Streaks ---
            achievement("streak_3", "Habit Forming", "3-day streak", longestStreak >= 3),
            achievement("week_warrior", "Week Warrior", "7-day streak", longestStreak >= 7),
            achievement("streak_14", "Fortnight", "14-day streak", longestStreak >= 14),
            achievement("month_master", "Month Master", "30-day streak", longestStreak >= 30),
            achievement("streak_60", "Two Months", "60-day streak", longestStreak >= 60),
            achievement("streak_100", "Century Streak", "100-day streak", longestStreak >= 100),
            achievement("streak_200", "Iron Will", "200-day streak", longestStreak >= 200),
            achievement("streak_365", "Year of Russian", "365-day streak", longestStreak >= 365),
            // --- Levels ---
            achievement("level_5", "Apprentice", "Reach level 5", level >= 5),
            achievement("level_10", "Adept", "Reach level 10", level >= 10),
            achievement("level_20", "Expert", "Reach level 20", level >= 20),
            achievement("level_50", "Master", "Reach level 50", level >= 50),
            achievement("level_100", "Grandmaster", "Reach level 100", level >= 100),
            // --- XP ---
            achievement("xp_1k", "Spark", "Earn 1,000 XP", xp >= 1000),
            achievement("xp_10k", "Charged", "Earn 10,000 XP", xp >= 10000),
            achievement("xp_100k", "Overcharged", "Earn 100,000 XP", xp >= 100000),
            // --- Consistency (active days) ---
            achievement("days_10", "Regular", "10 active days", activeDays >= 10),
            achievement("days_50", "Committed", "50 active days", activeDays >= 50),
            achievement("days_100", "Devoted", "100 active days", activeDays >= 100),
            // --- Daily intensity ---
            achievement("goal_double", "Overachiever", "Double the daily goal", reviewedToday >= dailyGoal * 2),
            achievement("goal_triple", "Marathon", "Triple the daily goal", reviewedToday >= dailyGoal * 3)
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
        val (updatedCard, log) = scheduler.review(card, rating, now)
        cardDao.update(updatedCard)
        reviewLogDao.insert(log)
        note?.let { noteDao.update(it.copy(encounterCount = it.encounterCount + 1)) }
        invalidateNoteState()
        graduateEligibleCategories()
        graduateVocabByEncounters()
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

    suspend fun searchNotes(query: String, limit: Int = 50): List<Note> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        return noteDao.search(trimmed, limit)
    }

    /** Mark a batch of reader surface tokens with one status in a single pass. */
    suspend fun setWordStatusBatch(tokens: Collection<String>, status: WordStatus): Int {
        var changed = 0
        tokens.distinctBy { normalizeToken(it) }.forEach { token ->
            setWordStatus(token, status)
            changed += 1
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
            graduateVocabByEncounters()
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
        val startOfDay = now - (now % DAY_MILLIS)
        return reviewLogDao.countSince(startOfDay)
    }

    private suspend fun sessionCards(now: Long, limit: Int): List<Card> {
        val plan = dailyPlan(now)
        val base = if (plan.triageMode) {
            cardDao.getOverdueCards(now - 2 * DAY_MILLIS, limit = limit)
        } else {
            cardDao.getDueCards(now, limit = limit)
        }.ifEmpty { cardDao.getNewCards(limit = limit) }
        val session = mutableListOf<Card>()
        val sessionIds = mutableSetOf<Long>()
        val newCardSession = base.all { it.state == CardState.NEW }
        for (card in base) {
            if (sessionIds.add(card.id)) session += card
            for (pair in confusablePairDao.getForNote(card.noteId)) {
                val partnerNoteId = if (pair.firstNoteId == card.noteId) pair.secondNoteId else pair.firstNoteId
                val partner = cardDao.getCardsForNote(partnerNoteId)
                    .filter { it.matchesCardVariant(card) && it.id !in sessionIds }
                    .sortedWith(compareBy<Card> { it.due }.thenBy { it.id })
                    .firstOrNull { if (newCardSession) it.state == CardState.NEW else it.state != CardState.NEW && it.due <= now }
                if (partner != null && sessionIds.add(partner.id)) session += partner
            }
        }
        return session.take(limit)
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
        val cards = when (category.kind) {
            "case" -> cardDao.getCaseDrillCards(category.gramCase.orEmpty(), category.gramGender.orEmpty(), category.gramNumber.orEmpty(), 5)
            "verb_form" -> cardDao.getVerbFormCards(category.contextCue.orEmpty(), 5)
            else -> cardDao.getAspectCards().filter { card ->
                    val note = noteDao.getById(card.noteId)
                    note?.aktionsart == category.aktionsart && note?.aspect == category.aspect && card.gramContextCue == category.contextCue
                }.take(5)
        }
        return cards.mapNotNull { promptFor(it, now) }
    }

    private suspend fun interleavedGrammarPrompts(excludeIds: Set<Long>, now: Long): List<ReviewPrompt> =
        cardDao.getGrammarDrillCards(20)
            .filter { it.id !in excludeIds }
            .take(10)
            .mapNotNull { promptFor(it, now) }

    private fun cardsFor(note: Note): List<Card> = buildList {
        add(Card(noteId = note.id, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
        add(Card(noteId = note.id, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB))
        if (!note.exampleSentence.isNullOrBlank()) add(Card(noteId = note.id, cardType = CardType.CLOZE, queue = Queue.VOCAB))
        if (!note.audioPath.isNullOrBlank()) add(Card(noteId = note.id, cardType = CardType.AUDIO_TO_RU, queue = Queue.VOCAB))
        // The general reading-matrix layer carries declension tables purely to
        // feed the reader form index (coverage); grammar drilling stays focused
        // on the curated domain corpus, so general notes get no CASE_FILL cards.
        val isGeneral = note.tags.contains("general")
        if (!isGeneral) caseCards(note).forEach(::add)
        if (!isGeneral) verbFormCards(note).forEach(::add)
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
            add(Card(noteId = note.id, cardType = CardType.ASPECT_SELECT, queue = Queue.GRAMMAR, due = 0L, gramContextCue = "NO_CUE"))
            add(Card(noteId = note.id, cardType = CardType.ASPECT_SELECT, queue = Queue.GRAMMAR, due = 0L, gramContextCue = "HAS_CUE"))
        }
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
                    gramNumber = gramNumber
                )
            }
            .toList()
    }

    private fun verbFormCards(note: Note): List<Card> {
        if (!note.partOfSpeech.equals("verb", ignoreCase = true)) return emptyList()
        return RussianForms.verbForms(note.lemma).keys
            .filter { key -> key in VERB_FORM_KEYS }
            .map { key ->
                Card(
                    noteId = note.id,
                    cardType = CardType.VERB_FORM,
                    queue = Queue.GRAMMAR,
                    gramContextCue = key
                )
            }
    }

    private suspend fun accuracyCategories(): List<CategoryKey> {
        val cards = cardDao.getAllGrammarCards()
        val nounKeys = cards
            .filter { it.cardType == CardType.CASE_FILL && it.gramCase != null && it.gramGender != null && it.gramNumber != null }
            .map { Triple(it.gramCase.orEmpty(), it.gramGender.orEmpty(), it.gramNumber.orEmpty()) }
            .distinct()
            .map { key ->
                val ratings = reviewLogDao.nounCategoryRatings(key.first, key.second, key.third, MIN_ACCURACY_SAMPLE)
                CategoryKey("case", key.first, key.second, key.third, accuracy = ratings.accuracyOrNull(), sampleSize = ratings.size)
            }

        val noteById = noteDao.getAll().associateBy { it.id }
        val aspectKeys = cards
            .filter { it.cardType == CardType.ASPECT_SELECT }
            .mapNotNull { card ->
                val note = noteById[card.noteId] ?: return@mapNotNull null
                Triple(note.aktionsart ?: return@mapNotNull null, note.aspect ?: return@mapNotNull null, card.gramContextCue ?: "NO_CUE")
            }
            .distinct()
            .map { key ->
                val ratings = reviewLogDao.aspectCategoryRatings(key.first, key.second, key.third, MIN_ACCURACY_SAMPLE)
                CategoryKey("aspect", aktionsart = key.first, aspect = key.second, contextCue = key.third, accuracy = ratings.accuracyOrNull(), sampleSize = ratings.size)
            }
        val verbFormKeys = cards
            .filter { it.cardType == CardType.VERB_FORM && it.gramContextCue != null }
            .map { it.gramContextCue.orEmpty() }
            .distinct()
            .map { key ->
                val ratings = reviewLogDao.verbFormCategoryRatings(key, MIN_ACCURACY_SAMPLE)
                CategoryKey("verb_form", contextCue = key, accuracy = ratings.accuracyOrNull(), sampleSize = ratings.size)
            }
        return nounKeys + aspectKeys + verbFormKeys
    }

    private suspend fun graduateEligibleCategories() {
        val categories = accuracyCategories()
            .filter { it.sampleSize >= MIN_ACCURACY_SAMPLE && (it.accuracy ?: 0.0) >= GRADUATION_ACCURACY }
        categories.filter { it.kind == "case" }.forEach { category ->
            cardDao.getGrammarCardsForNounCategory(category.gramCase.orEmpty(), category.gramGender.orEmpty(), category.gramNumber.orEmpty())
                .filter { it.state != CardState.GRADUATED }
                .forEach { cardDao.update(it.copy(state = CardState.GRADUATED)) }
        }
        categories.filter { it.kind == "aspect" }.forEach { category ->
            cardDao.getAspectCards()
                .filter { card ->
                    val note = noteDao.getById(card.noteId)
                    note?.aktionsart == category.aktionsart && note?.aspect == category.aspect && card.gramContextCue == category.contextCue
                }
                .filter { it.state != CardState.GRADUATED }
                .forEach { cardDao.update(it.copy(state = CardState.GRADUATED)) }
        }
        categories.filter { it.kind == "verb_form" }.forEach { category ->
            cardDao.getVerbFormCards(category.contextCue.orEmpty(), Int.MAX_VALUE)
                .filter { it.state != CardState.GRADUATED }
                .forEach { cardDao.update(it.copy(state = CardState.GRADUATED)) }
        }
    }

    private suspend fun graduateVocabByEncounters() {
        noteDao.getAll()
            .filter { it.encounterCount >= VOCAB_GRADUATION_ENCOUNTERS }
            .forEach { note ->
                cardDao.getCardsForNote(note.id)
                    .filter { it.queue == Queue.VOCAB && it.state != CardState.GRADUATED }
                    .forEach { cardDao.update(it.copy(state = CardState.GRADUATED)) }
            }
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
        private val VERB_FORM_KEYS = setOf("PRES_1SG", "PRES_2SG", "PRES_3SG", "PRES_1PL", "PRES_2PL", "PRES_3PL", "PAST_M", "PAST_F", "PAST_N", "PAST_PL")
    }
}
