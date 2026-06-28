package com.sibirskyspeak.data

import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.LessonContent
import com.sibirskyspeak.review.buildPrompt
import com.sibirskyspeak.scheduler.Scheduler
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
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
    // Reads/writes a full-state JSON backup that lives outside the DB, used to
    // auto-recover study history if the database is ever wiped (destructive
    // migration, corruption, reinstall). Null in tests that don't exercise it.
    private val restoreBackup: (suspend () -> String?)? = null,
    private val writeBackup: (suspend (String) -> Unit)? = null,
    private val telemetryDao: TelemetryDao? = null,
    private val readingScheduleDao: ReadingScheduleDao? = null,
    private val readerEncounterDao: ReaderEncounterDao? = null,
    private val readingActivityDao: ReadingActivityDao? = null
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
        val readerKnown = readerEncounterDao?.noteIdsWithMinimumEncounters(READER_KNOWN_ENCOUNTERS).orEmpty()
        val statusKnown = notes.filter { note ->
            note.status == WordStatus.KNOWN ||
                note.status == WordStatus.IGNORED
        }.map { it.id }
        return (cardKnown + readerKnown + statusKnown).toHashSet()
    }

    suspend fun seedIfEmpty() {
        if (noteDao.count() > 0) {
            syncMissingConceptDrillCards()
            runCatching { syncBootstrapTextbookNotes() }
            runCatching { syncBootstrapReaderTexts() }
            runCatching { performDataMaintenance() }
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
        var noteId = 0L
        runInTransaction {
            noteId = noteDao.insert(note)
            cardDao.insertAll(cardsFor(note.copy(id = noteId)))
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
                        mnemonic = json.optCleanString("mnemonic")
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
                                            stabilityBefore = rj.optDouble("stabilityBefore", 0.0)
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
        return listOf(noteLines, readerLines, pairLines, telemetryLines).filter { it.isNotBlank() }.joinToString("\n")
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
                noteDao.update(stale.copy(status = WordStatus.IGNORED))
                cardDao.graduateVocabForNote(stale.id, Long.MAX_VALUE)
            }
            textbookRows.forEach { json ->
                    val tags = json.optString("tags", "")
                    val lemma = json.getString("lemma")
                    val current = existingByLemma[lemma]
                    if (current != null) {
                        // Upgrade existing installs from the old 61..69 numbering and
                        // refresh corrected concise glosses without touching SRS state.
                        noteDao.update(current.copy(
                            russian = json.getString("russian"),
                            translation = json.getString("translation"),
                            unit = json.optIntOrNull("unit"),
                            cefrLevel = json.optCleanString("cefrLevel"),
                            mnemonic = json.optCleanString("mnemonic") ?: current.mnemonic,
                            tags = tags
                        ))
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

            noteDao.getAll().filter(::isAmbiguousFunctionNote).forEach { note ->
                changes += cardDao.suspendAmbiguousProduction(note.id)
            }
        }
        invalidateNoteStructure()
        if (changes > 0) recordTelemetry(TelemetryEvent(eventType = "data_maintenance", metadataJson = "{\"changes\":$changes}"))
        telemetryDao?.deleteOlderThan(System.currentTimeMillis() - TELEMETRY_RETENTION_MILLIS)
        return changes
    }

    suspend fun recordTelemetry(event: TelemetryEvent) {
        telemetryDao?.insert(event)
    }

    suspend fun recentTelemetry(limit: Int = 1000): List<TelemetryEvent> = telemetryDao?.recent(limit).orEmpty()

    /** Per-review rows (card-grouped, oldest first) for the on-device FSRS weight fit. */
    suspend fun reviewSamplesForFitting(): List<ReviewFitRow> = reviewLogDao.reviewFitRows()

    private fun wordStatusRank(status: WordStatus): Int = when (status) {
        WordStatus.NEW -> 0
        WordStatus.LEARNING -> 1
        WordStatus.KNOWN -> 2
        WordStatus.IGNORED -> 3
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun isAmbiguousFunctionNote(note: Note): Boolean {
        val pos = note.partOfSpeech.lowercase()
        val functionWord = pos in setOf("preposition", "conjunction", "particle", "pronoun", "conj.", "prep.")
        return functionWord && note.translation.split(',', ';', '/').count { it.isNotBlank() } > 1
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
        val body = text.body
        val matches = Regex("""[\p{L}́]+""").findAll(body).toList()
        return matches.mapIndexed { i, match ->
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
                        cardDao.graduateVocabForNote(match.id, Long.MAX_VALUE)
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
            cardDao.graduateVocabForNote(noteId, Long.MAX_VALUE)
        }
        invalidateNoteState()
    }

    suspend fun dashboardStats(now: Long = System.currentTimeMillis()): DashboardStats =
        dashboardStatsFrom(now, readerTexts())

    private suspend fun dashboardStatsFrom(now: Long, recommendations: List<ReaderRecommendation>): DashboardStats {
        val notes = allNotesCached()
        val targetCoverages = recommendations.filter { it.text.source.startsWith("target:", ignoreCase = true) }.map { it.coverage }
        val qualityReport = importQualityReport(notes, recommendations)
        val retentionWindowStart = now - RETENTION_WINDOW_DAYS * DAY_MILLIS
        val matureReviews = reviewLogDao.matureReviewCount(retentionWindowStart)
        val matureRetained = reviewLogDao.matureRetainedCount(retentionWindowStart)
        val dueByDay = cardDao.countDueByDay(now, now + 7 * DAY_MILLIS, DAY_MILLIS)
            .associate { it.day to it.count }
        val forecast = List(7) { day -> dueByDay[day] ?: 0 }
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
            dueForecast = forecast
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
            triageMode = dueCount > TRIAGE_THRESHOLD,
            overdueBacklog = cardDao.getOverdueCards(now - 2 * DAY_MILLIS, limit = 1).isNotEmpty()
        )
    }

    suspend fun sessionPlan(now: Long = System.currentTimeMillis()): SessionPlan {
        refreshGraduationsIfNeeded()
        syncReadingSchedules()
        val notesById = allNotesCached().associateBy { it.id }
        val categories = accuracyCategoriesCached()
        val daily = dailyPlanFromCategories(now, categories)
        val blocked = blockedGrammarPrompts(daily, now, notesById)
        // Compute once; reuse for both readerRecommendation and dashboardStats.
        val allTexts = readerTexts()
        val reviewedNoteIds = reviewLogDao.getReviewedCardsSince(startOfLocalDay(now)).mapTo(HashSet()) { it.noteId }
        val consolidationReader = consolidationReader(allTexts, reviewedNoteIds)
        val mastery = unitMastery()
        val cards = sessionCards(now, config().sessionSize, daily, mastery)
        val prompts = cards.mapIndexedNotNull { index, card ->
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
        val readingAssignment = dueReadingAssignment(allTexts, consolidationReader, prompts.size, now)
        val introducedToday = reviewLogDao.countNewIntroducedSince(startOfLocalDay(now))
        val completion = when {
            daily.triageMode || daily.overdueBacklog -> DailyCompletion(DailyLearningStatus.BACKLOG_REMAINING, "Overdue review backlog remaining — new material is paused.", allTexts.isNotEmpty())
            prompts.isNotEmpty() || readingAssignment != null -> DailyCompletion(DailyLearningStatus.WORK_REMAINING, "Scheduled cards and connected reading are still available.", readingAssignment != null)
            introducedToday >= config().newCardsPerDay || cardDao.getNewCardsOrdered(1).isNotEmpty() -> DailyCompletion(DailyLearningStatus.NEW_LIMIT_REACHED, "Scheduled work complete; today's new-word allowance is exhausted or remaining siblings are buried.", allTexts.isNotEmpty())
            else -> DailyCompletion(DailyLearningStatus.SCHEDULED_COMPLETE, "Scheduled work complete for today.", allTexts.isNotEmpty())
        }
        return SessionPlan(
            ruleSummary = ruleSummaryFor(daily.openBlockedWith),
            reviewQueue = prompts,
            blockedGrammar = blocked,
            interleavedGrammar = interleavedGrammarPrompts(blocked.map { it.card.id }.toSet(), now, notesById),
            readerRecommendation = consolidationReader
                ?: allTexts.minWithOrNull(compareBy<ReaderRecommendation> { distanceFromTarget(it.coverage) }.thenByDescending { it.coverage }),
            dashboardStats = dashboardStatsFrom(now, allTexts),
            dailyPlan = daily,
            gamification = gamificationStats(now),
            completion = completion,
            unitMastery = mastery,
            readingReason = if (reviewedNoteIds.isNotEmpty() && allTexts.isNotEmpty()) {
                "Consolidates words practiced today in connected text"
            } else null,
            problemCards = problemCardAudit(notesById),
            consolidationLemmas = notesById.values.filter { it.id in reviewedNoteIds }.mapTo(linkedSetOf()) { it.lemma },
            readingAssignment = readingAssignment
        )
    }

    private suspend fun dueReadingAssignment(
        texts: List<ReaderRecommendation>,
        consolidation: ReaderRecommendation?,
        cardCount: Int,
        now: Long
    ): ReadingAssignment? {
        val dao = readingScheduleDao ?: return null
        val due = dao.getAll().filter { it.due <= now }.associateBy { it.readerTextId }
        if (due.isEmpty()) return null
        val readable = texts.filter { it.text.id in due && it.coverage >= MIN_READER_COVERAGE }
        val recommendation = consolidation?.takeIf { it.text.id in due && it.coverage >= MIN_READER_COVERAGE }
            ?: readable.minWithOrNull(compareBy<ReaderRecommendation> { distanceFromTarget(it.coverage) }.thenBy { due[it.text.id]?.reps ?: 0 })
            ?: return null
        val insertion = when {
            cardCount <= 1 -> 0
            cardCount <= 4 -> 1
            else -> (cardCount / 3).coerceIn(3, cardCount - 1)
        }
        return ReadingAssignment(recommendation, due.getValue(recommendation.text.id), insertion)
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
            }
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

    private suspend fun unitMastery(): List<UnitMastery> {
        val notes = allNotesCached().filter { it.tier == 0 && it.unit != null && it.status != WordStatus.IGNORED }
        val byId = notes.associateBy { it.id }
        val vocab = cardDao.getAllVocabCards().filter { it.noteId in byId && it.cardType == CardType.RU_TO_MEANING && !it.suspended }
        val grammar = cardDao.getAllGrammarCards().filter { it.noteId in byId && it.cardType != CardType.LESSON && !it.suspended }
        var priorComplete = true
        return notes.groupBy { it.unit!! }.toSortedMap().map { (unit, unitNotes) ->
            val ids = unitNotes.mapTo(HashSet()) { it.id }
            val unitVocab = vocab.filter { it.noteId in ids }
            val unitGrammar = grammar.filter { it.noteId in ids }
            val result = UnitMastery(
                unit = unit,
                vocabularyMastered = unitVocab.count {
                    it.state == CardState.GRADUATED || (it.reps >= 2 && it.consecutiveCorrect >= 2)
                },
                vocabularyTotal = unitVocab.size,
                grammarMastered = unitGrammar.count { it.reps >= 2 && it.consecutiveCorrect >= 2 },
                grammarTotal = unitGrammar.size,
                unlocked = priorComplete
            )
            priorComplete = priorComplete && result.progress >= UNIT_MASTERY_THRESHOLD
            result
        }
    }

    suspend fun gamificationStats(now: Long = System.currentTimeMillis()): GamificationStats {
        val dailyGoal = config().dailyGoal
        val tzOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val days = (reviewLogDao.reviewDayBuckets(tzOffset, DAY_MILLIS) +
            readingActivityDao?.dayBuckets(tzOffset, DAY_MILLIS).orEmpty()).distinct()
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
        val xp = reviewLogDao.weightedXp() + (readingActivityDao?.countAll() ?: 0) * READING_XP
        // Level L costs L * XP_PER_LEVEL_STEP to advance; spend xp level by level.
        var level = 1
        var remaining = xp
        while (remaining >= level * XP_PER_LEVEL_STEP) {
            remaining -= level * XP_PER_LEVEL_STEP
            level += 1
        }
        val knownWords = knownNoteIds().size
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
        val daySet = (reviewLogDao.reviewDayBuckets(tzOffset, DAY_MILLIS) +
            readingActivityDao?.dayBuckets(tzOffset, DAY_MILLIS).orEmpty()).toHashSet()
        val todayBucket = (now + tzOffset) / DAY_MILLIS
        var streak = 0
        if (todayBucket in daySet || (todayBucket - 1) in daySet) {
            var day = if (todayBucket in daySet) todayBucket else todayBucket - 1
            while (day in daySet) { streak += 1; day -= 1 }
        }
        return ReminderInfo(
            currentStreak = streak,
            studiedToday = todayBucket in daySet,
            dueToday = cardDao.countDue(now) + if (readingScheduleDao?.nextDue(now) != null) 1 else 0
        )
    }

    suspend fun nextPrompt(now: Long = System.currentTimeMillis()): ReviewPrompt? =
        sessionPlan(now).reviewQueue.firstOrNull()

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

    /** Build a non-scheduling acquisition recall while rotating through examples. */
    suspend fun practicePromptFor(card: Card, round: Int, now: Long = System.currentTimeMillis()): ReviewPrompt? {
        val live = cardDao.getCardsForNote(card.noteId).firstOrNull { it.id == card.id } ?: card
        return promptFor(live.copy(reps = live.reps + round.coerceAtLeast(1)), now)?.copy(practiceOnly = true)
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
        val note = noteDao.getById(live.noteId) ?: return null
        val meaning = buildPrompt(live.copy(cardType = CardType.RU_TO_MEANING), note, emptyMap()).expectedAnswer
        val exampleRu = note.exampleSentence.orEmpty()
        val exampleEn = note.exampleTranslation.orEmpty()
        val mnemonic = note.mnemonic?.takeIf { it.isNotBlank() }
        val content = LessonContent(
            title = "Reset and reconnect: ${note.russian}",
            body = buildString {
                append("Meaning here: $meaning")
                if (mnemonic != null) append("\n\nMemory hook: $mnemonic")
                append("\n\nRead the example, then retrieve it again after a short gap.")
            },
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
            teachingHint = if (mnemonic == null && live.lapses >= 2) "Add a memory hook with the pencil if this keeps failing" else "Contextual reset",
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
    suspend fun review(card: Card, rating: Rating, now: Long = System.currentTimeMillis()): Boolean {
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
        val (updatedCard, log) = scheduler.review(live, rating, now)
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
        return becameLeech
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
        if (hasReadableExample(note) && cardDao.getByNoteAndType(note.id, CardType.CLOZE) == null) {
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
        cardDao.updateAll(cards.map { card ->
            card.copy(
                state = CardState.GRADUATED,
                due = now + 365L * DAY_MILLIS,
                scheduledDays = maxOf(card.scheduledDays, 365),
                reps = maxOf(card.reps, 1),
                consecutiveCorrect = maxOf(card.consecutiveCorrect, 1),
                lastReview = now
            )
        })
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
        readerTexts().minWithOrNull(compareBy<ReaderRecommendation> { distanceFromTarget(it.coverage) }.thenByDescending { it.coverage })

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

    private suspend fun sessionCards(now: Long, limit: Int, plan: DailyPlan, mastery: List<UnitMastery>): List<Card> {
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
        val fresh = newCardSession(now, limit - dueSession.size, reviewedNotes, mastery)
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
        val locked = lockedConceptIds()
        val notesById = allNotesCached().associateBy { it.id }
        val existingIds = cards.mapTo(HashSet()) { it.id }
        val grammarPool = cardDao.getGrammarDrillCards(250)
        val cardsByNote = if (grammarPool.isEmpty()) emptyMap() else
            cardDao.getCardsForNotes(grammarPool.map { it.noteId }.distinct()).groupBy { it.noteId }
        val candidates = grammarPool.filter { card ->
            card.id !in existingIds && !card.suspended &&
                card.state != CardState.GRADUATED && (card.state == CardState.NEW || card.due <= now) &&
                !isConceptLocked(card, locked) && !isNewGrammarBeforeFirstEncounter(card, notesById) &&
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
        // Authored all-grammar contrast sets (especially aspect pairs) depend on
        // adjacency; preserve their pedagogical order rather than "balancing" it.
        if (cards.all { it.queue == Queue.GRAMMAR }) return cards
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

    private fun skillBucket(card: Card): Int = when (card.cardType) {
        CardType.RU_TO_MEANING -> 0
        CardType.AUDIO_TO_RU, CardType.DICTATION, CardType.STRESS_MARK -> 1
        CardType.MEANING_TO_RU, CardType.CLOZE, CardType.SENTENCE_BUILD -> 2
        CardType.SPEAK -> 3
        else -> 4
    }

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
    private suspend fun newCardSession(now: Long, limit: Int, dayReviewedNotes: Set<Long> = emptySet(), mastery: List<UnitMastery>): List<Card> {
        val introducedToday = reviewLogDao.countNewIntroducedSince(startOfLocalDay(now))
        val pacing = config()
        val normalNewBudget = pacing.newCardsPerDay.coerceAtLeast(0)
        var remainingLexemeBudget = (normalNewBudget + extraCreditToday(now) - introducedToday).coerceAtLeast(0)
        if (limit <= 0) return emptyList()
        val previouslyReviewedNotes = reviewLogDao.getReviewedNoteIds().toHashSet()

        // Pull a generous pool, already in curriculum order (A1 tier first, by unit,
        // then by frequency rank). Drop grammar drills whose teaching lesson the
        // learner hasn't seen yet — concept gating keeps "teach before test" true.
        val locked = lockedConceptIds()
        val notesById = allNotesCached().associateBy { it.id }
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
                if (isConceptLocked(card, locked)) continue
                if (isNewGrammarBeforeFirstEncounter(card, notesById)) continue
                if (isAdvancedFacetBeforeRecognitionMatures(card, cardsByNote)) continue
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
        val firstLockedUnit = mastery.firstOrNull { !it.unlocked }?.unit
        val frontierUnits = grouped.keys.mapNotNull { notesById[it]?.takeIf { n -> n.tier == 0 }?.unit }
            .distinct().sorted().take(2)
        val activeUnit = frontierUnits.firstOrNull()
        val previewUnit = frontierUnits.getOrNull(1)
        var previewUsed = 0
        val previewLimit = maxOf(1, limit / 5)
        val byNote = LinkedHashMap<Long, ArrayDeque<Card>>()
        for ((noteId, cards) in grouped) {
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

    private fun isNewGrammarBeforeFirstEncounter(card: Card, notesById: Map<Long, Note>): Boolean {
        if (card.queue != Queue.GRAMMAR || card.cardType == CardType.LESSON) return false
        val note = notesById[card.noteId] ?: return false
        return note.encounterCount == 0
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

    private suspend fun promptFor(card: Card, now: Long, notesById: Map<Long, Note>? = null): ReviewPrompt? {
        val note = notesById?.get(card.noteId) ?: noteDao.getById(card.noteId) ?: return null
        val partner = note.aspectPartner?.let { notesById?.get(it) ?: noteDao.getById(it) }
        return buildPrompt(card, note, scheduler.preview(card, now), partner)
    }

    private suspend fun blockedGrammarPrompts(plan: DailyPlan, now: Long, notesById: Map<Long, Note>): List<ReviewPrompt> {
        val category = plan.openBlockedWith ?: return emptyList()
        val locked = lockedConceptIds()
        val cards = when (category.kind) {
            "case" -> cardDao.getCaseDrillCards(category.gramCase.orEmpty(), category.gramGender.orEmpty(), category.gramNumber.orEmpty(), 5)
            "verb_form" -> cardDao.getVerbFormCards(category.contextCue.orEmpty(), 5)
            else -> cardDao.getAspectCards().filter { card ->
                    val note = notesById[card.noteId]
                    note?.aktionsart == category.aktionsart && note?.aspect == category.aspect && card.gramContextCue == category.contextCue
                }.take(5)
        }
        return cards.filterNot { isConceptLocked(it, locked) }.mapNotNull { promptFor(it, now, notesById) }
    }

    private suspend fun interleavedGrammarPrompts(excludeIds: Set<Long>, now: Long, notesById: Map<Long, Note>): List<ReviewPrompt> {
        val locked = lockedConceptIds()
        return cardDao.getGrammarDrillCards(40)
            .filter { it.id !in excludeIds && it.cardType != CardType.LESSON }
            .filterNot { isConceptLocked(it, locked) }
            .take(10)
            .mapNotNull { promptFor(it, now, notesById) }
    }

    private fun cardsFor(note: Note): List<Card> = buildList {
        // A lesson note (pos = "lesson") teaches one grammar concept and produces a
        // single LESSON card — no vocab/drill cards. Seeing it is what unlocks the
        // concept's drills (concept gating).
        if (note.partOfSpeech.equals("lesson", ignoreCase = true)) {
            add(Card(noteId = note.id, cardType = CardType.LESSON, queue = Queue.GRAMMAR, due = 0L, gramConcept = note.conceptId))
            ConceptDrills.forConcept(note.conceptId).forEach { drill ->
                add(
                    Card(
                        noteId = note.id,
                        cardType = CardType.CONCEPT_DRILL,
                        queue = Queue.GRAMMAR,
                        due = 0L,
                        gramContextCue = drill.id,
                        gramConcept = drill.conceptId
                    )
                )
            }
            return@buildList
        }
        // The frequency "reading-matrix" layer (tag contains "matrix") gets rich vocab
        // and comprehension study cards AND keeps its declension tables — but ONLY to
        // feed the reader coverage index, never to generate morphology drills. Its
        // tables are rule-engine output (decline_noun(animate=False), oblique cases
        // unvalidated against the deck), so case/gender/agreement/aspect drills built
        // from them would teach wrong forms. Morphology drilling stays restricted to
        // the verified curated course (tier 0) and the domain corpus. We key on the
        // "matrix" tag, not tier, because the default tier (1) also covers imported /
        // test notes that legitimately need grammar drills.
        val isReadingMatrix = note.tags.contains("matrix")
        // A "recognition_only" note (e.g. a textbook word recovered in an oblique
        // form — "университе́та = university (gen.)") is honest for *recognition* and
        // reader coverage, but reverse-production would wrongly ask the learner to
        // type that exact inflected form. Such notes get recognition + listening only.
        val recognitionOnly = note.tags.contains("recognition_only")
        add(Card(noteId = note.id, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
        if (!isAmbiguousFunctionNote(note) && !recognitionOnly) {
            add(Card(noteId = note.id, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB))
        }
        // Cloze blanks a word inside the example sentence — only useful if the learner
        // can read that sentence, i.e. it ships with a real sentence-level translation.
        if (hasReadableExample(note) && !isAmbiguousFunctionNote(note) && !recognitionOnly) {
            add(Card(noteId = note.id, cardType = CardType.CLOZE, queue = Queue.VOCAB))
            // SENTENCE_BUILD and DICTATION make the learner handle a whole sentence.
            // Keep them to the hand-authored spine with SHORT, controlled sentences —
            // not the promoted/reading-matrix layer's arbitrary (often long, hard)
            // deck sentences, which would be a brutal typing grind.
            if (!isReadingMatrix && note.hasShortExample()) {
                add(Card(noteId = note.id, cardType = CardType.SENTENCE_BUILD, queue = Queue.GRAMMAR, gramConcept = note.conceptId))
                add(Card(noteId = note.id, cardType = CardType.DICTATION, queue = Queue.VOCAB))
            }
        }
        // Listening via on-device Russian TTS works for any word, no audio asset needed.
        add(Card(noteId = note.id, cardType = CardType.AUDIO_TO_RU, queue = Queue.VOCAB))
        // Speaking: the learner says the word aloud and on-device speech recognition
        // checks it — the only card that trains production/pronunciation. Restricted to
        // the curated course (tier 0) so it stays focused on active study vocabulary.
        if (note.tier == 0 && !recognitionOnly) {
            add(Card(noteId = note.id, cardType = CardType.SPEAK, queue = Queue.VOCAB))
        }
        // Stress remains on the marked headword and is reinforced by audio instead
        // of multiplying every lexeme with a standalone tap-the-vowel card.
        if (!isReadingMatrix) caseCards(note).forEach(::add)
        if (!isReadingMatrix) verbFormCards(note).forEach(::add)
        if (!isReadingMatrix) adjectiveAgreementCards(note).forEach(::add)
        if (!isReadingMatrix) genderCard(note)?.let(::add)
        // ASPECT_SELECT requires a verified Aktionsart (design F8): the drill's
        // whole point is reasoning from inherent temporal structure, so a verb
        // without Aktionsart never produces a half-formed aspect card.
        val isAspectDrillable = !isReadingMatrix &&
            note.aspect != "BI" &&
            !note.tags.contains("no_aspect_pair") &&
            !note.aktionsart.isNullOrBlank() &&
            note.aspectPartner != null &&
            !note.aspect.isNullOrBlank()
        if (isAspectDrillable) {
            ASPECT_CONTEXT_CUES.forEach { cue ->
                add(Card(noteId = note.id, cardType = CardType.ASPECT_SELECT, queue = Queue.GRAMMAR, due = 0L, gramContextCue = cue, gramConcept = GrammarConcepts.ASPECT.id))
            }
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

    /** True when the note's example is a short (2-7 word) sentence, suitable for
     *  sentence-building / dictation without becoming a typing grind. */
    private fun Note.hasShortExample(): Boolean {
        val s = exampleSentence ?: return false
        val words = Regex("""\p{IsCyrillic}+""").findAll(s).count()
        return words in 2..7
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

    // Past tense can be derived safely; present/future conjugations appear only
    // when the note ships an explicit verified table in declensionJson. This
    // keeps productive practice for пишу/люблю/вижу without teaching guesses.
    private fun verbFormCards(note: Note): List<Card> {
        if (!note.partOfSpeech.equals("verb", ignoreCase = true)) return emptyList()
        return RussianForms.verbForms(note).keys
            .filter { key -> key in VERB_FORM_KEYS }
            .map { key ->
                Card(
                    noteId = note.id,
                    cardType = CardType.VERB_FORM,
                    queue = Queue.GRAMMAR,
                    gramContextCue = key,
                    gramConcept = verbFormConcept(note, key)
                )
            }
    }

    private fun verbFormConcept(note: Note, key: String): String =
        when {
            key.startsWith("PRES_") && note.aspect == "PF" -> GrammarConcepts.FUTURE.id
            key.startsWith("PRES_") -> GrammarConcepts.PRESENT.id
            else -> GrammarConcepts.PAST.id
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


    private fun coverageFor(text: ReaderText, index: Map<String, Note>, knownIds: Set<Long>): ReaderRecommendation {
        val tokens = readerWordOccurrences(text.body)
        val knownCount = tokens.count { token ->
            val note = index[normalizeToken(token.surface)]
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

    private fun importQualityReport(notes: List<Note>, recommendations: List<ReaderRecommendation>): ImportQualityReport {
        val readyNominals = notes.count { it.isNominalReady() }
        val aspectReadyVerbs = notes.count { it.isAspectReadyVerb() }
        val verifiedAktionsartVerbs = notes.count { it.isAspectReadyVerb() && it.hasVerifiedAktionsart() }
        val domainRanked = notes.count { it.domainFreqRank != null }
        val examples = notes.count { hasReadableExample(it) }
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
            hasReadableExample(this)

    private fun Note.isAspectReadyVerb(): Boolean =
        partOfSpeech.lowercase(Locale.ROOT) == "verb" &&
            aspectPartner != null &&
            !aspect.isNullOrBlank() &&
            !aktionsart.isNullOrBlank() &&
            domainFreqRank != null &&
            hasReadableExample(this)

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
        // A card the learner has lapsed (rated AGAIN) this many times is a "leech":
        // it keeps tripping them up and burning review time. We auto-park it so it
        // stops resurfacing; it lands in the Leeches list to fix or release.
        const val LEECH_LAPSES = 8
        // How many extra new cards each "extra credit" tap adds for the day.
        const val EXTRA_CREDIT_BATCH = 10
        private const val EXTRA_CREDIT_DAILY_LIMIT = EXTRA_CREDIT_BATCH
        private const val TRIAGE_THRESHOLD = 80
        private const val MIN_ACCURACY_SAMPLE = 30
        private const val GRADUATION_ACCURACY = 0.90
        private const val VOCAB_GRADUATION_ENCOUNTERS = 15
        private const val READER_KNOWN_ENCOUNTERS = 3
        private const val MIN_READER_COVERAGE = 0.90
        private val READING_INTERVALS = intArrayOf(0, 1, 3, 7, 14, 30, 60, 90)
        private const val READING_XP = 30
        private const val PRODUCTIVE_COVERAGE_MAX = 0.96
        private const val AUTHENTIC_READY_COVERAGE = 0.90
        private const val DESIGN_DOC_MIN_NOMINAL_ROWS = 200
        private const val DESIGN_DOC_MIN_VERB_ROWS = 100
        private const val DAILY_GOAL = 20
        private const val XP_PER_REVIEW = 10
        private const val XP_PER_LEVEL_STEP = 100
        private const val TELEMETRY_RETENTION_MILLIS = 180L * 24 * 60 * 60 * 1000
        private const val UNIT_MASTERY_THRESHOLD = 0.80
        // Facets deferred until a word's RU→meaning recognition is stable: every
        // productive skill (typing/speaking/building) AND listen-and-produce audio.
        // First contact is therefore a single clean recognition card — the word's
        // audio still auto-plays on it, so listening exposure isn't lost — which
        // maximizes new-word breadth per day and keeps the lexeme budget exact.
        private val ADVANCED_FACETS = setOf(
            CardType.MEANING_TO_RU, CardType.CLOZE, CardType.SPEAK, CardType.AUDIO_TO_RU,
            CardType.DICTATION, CardType.SENTENCE_BUILD, CardType.STRESS_MARK
        )
        private val CASES = setOf("NOM", "ACC", "GEN", "DAT", "INS", "PREP")
        private val NUMBERS = setOf("SG", "PL")
        private val NOUN_GENDERS = setOf("M", "F", "N", "PL")
        private val CEFR_LEVELS = listOf("A1", "A2", "B1", "B2", "C1")
        private val ASPECT_CONTEXT_CUES = listOf("PROCESS", "HABITUAL", "COMPLETED", "RESULT", "SINGLE_EVENT")
        private val VERB_FORM_KEYS = setOf(
            "PAST_M", "PAST_F", "PAST_N", "PAST_PL",
            "PRES_1SG", "PRES_2SG", "PRES_3SG", "PRES_1PL", "PRES_2PL", "PRES_3PL"
        )
    }
}
