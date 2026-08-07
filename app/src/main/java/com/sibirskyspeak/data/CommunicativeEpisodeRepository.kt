package com.sibirskyspeak.data

import androidx.room.withTransaction
import com.sibirskyspeak.generation.DialogueEngine
import com.sibirskyspeak.generation.DialogueTurn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.ceil
import kotlin.random.Random

enum class EpisodeTaskKind { CONTEXT, RETRIEVAL, LISTENING, READING, NOTICE, CONTRAST, PRAGMATICS, GUIDED_RESPONSE, INFORMATION_GAP, MEDIATION, TRANSFER, PRODUCTION_PROBE, REPAIR }

/** A learner-facing step in one coherent communicative episode. */
data class EpisodeTask(
    val id: String,
    val kind: EpisodeTaskKind,
    val instruction: String,
    val russian: String,
    val english: String? = null,
    val expected: String? = null,
    /** Same component elicited through a different carrier after an intervening gap. */
    val repairExpected: String? = null,
    /** English cue paired with [repairExpected]. Null means the alternate answer
     * is valid for the original cue rather than a different carrier sentence. */
    val repairEnglish: String? = null,
    /** Meaning-equivalent or contextually valid responses, not spelling aliases only. */
    val acceptable: List<String> = emptyList(),
    /** The interlocutor's authored consequence for the selected valid response. */
    val responseFeedback: Map<String, String> = emptyMap(),
    /** Resolve the expected answer from an earlier branch response. */
    val dependsOnTaskId: String? = null,
    val expectedByAnswer: Map<String, String> = emptyMap(),
    /** Rubric for communicative grading beyond one memorized carrier sentence. */
    val semanticAnchors: List<String> = emptyList(),
    val constructionCues: List<String> = emptyList(),
    val minimumMeaningCoverage: Double = 0.55,
    val choices: List<String> = emptyList(),
    val audioRate: Float = 1.0f,
    val audioPitch: Float = 1.0f,
    val voiceVariant: Int = 0,
    val audioCondition: String? = null,
    val componentKeys: List<String> = emptyList(),
    val supportLevel: Int = 0,
    val novelContext: Boolean = false
)

data class CommunicativeEpisode(
    val id: String = UUID.randomUUID().toString(),
    /** Stable authored scenario family used for coverage and repetition telemetry. */
    val sourceDialogueId: String? = null,
    val sourceReaderTextId: Long? = null,
    val capabilityKey: String,
    val band: String,
    val unit: Int,
    val canDo: String,
    val title: String,
    val objective: String = canDo,
    val informationGap: String = "",
    val setting: String? = null,
    val register: String = "neutral",
    val activity: String = "interaction",
    val blindAssessment: Boolean = false,
    val estimatedMinutes: Int,
    val mode: EpisodeMode = EpisodeMode.ACQUIRE,
    val focus: String = "build usable Russian",
    val tasks: List<EpisodeTask>
)

data class EpisodeOverview(
    val capabilityKey: String,
    val band: String,
    val unit: Int,
    val canDo: String,
    val estimatedMinutes: Int,
    val mode: EpisodeMode,
    val focus: String,
    val completedEpisodes: Int,
    val transferAccuracy: Double?
)

data class EpisodeCompletion(
    val routeReady: Boolean,
    val certified: Boolean,
    val observedNotes: Int,
    val totalNotes: Int,
    val memoryReadiness: Double
)

internal data class ContrastCandidate(val target: Note, val distractor: Note, val reason: String)

/** Keeps durable memory when curriculum content moves, but prevents an obsolete
 * unit assignment from surfacing advanced or unscoped material in an early route. */
internal fun reconcileComponentMembership(component: KnowledgeComponent, note: Note?): KnowledgeComponent {
    if (component.noteId == null) return component
    val validUnit = note?.unit
    val validBand = note?.cefrLevel
    val eligible = note != null && note.tier == 0 && validUnit != null && validBand != null &&
        note.status != WordStatus.KNOWN && note.status != WordStatus.IGNORED
    if (!eligible) return if (component.retired) component else component.copy(retired = true)
    val expectedCapability = ComponentKeys.capability(validBand!!, validUnit!!)
    return if (component.capabilityKey == expectedCapability && component.band == validBand && component.unit == validUnit) {
        component
    } else component.copy(capabilityKey = expectedCapability, band = validBand, unit = validUnit)
}

internal fun certificationTime(
    progress: CapabilityProgress,
    mastery: CapabilityMasteryProfile,
    evidence: List<CapabilityEvidence>,
    at: Long,
    requiredCompletions: Int = 3
): Long? {
    progress.certifiedAt?.let { return it }
    val independentProbeEpisodes = evidence.asSequence()
        .filter {
            it.taskKind == EpisodeTaskKind.PRODUCTION_PROBE.name &&
                it.outcome == "SUCCESS" && it.supportLevel == 0 && it.evidenceWeight >= 0.75
        }
        .map { it.episodeId }
        .distinct()
        .count()
    return at.takeIf { independentProbeEpisodes >= 2 && mastery.supportsRouteAdvance(progress, requiredCompletions) }
}

/** A malformed optional dialogue must degrade to note-based practice, not block the unit. */
internal fun safelyScriptedTurns(dialogue: ContentDialogue, nodes: List<ContentDialogueNode>): List<DialogueTurn> =
    runCatching { DialogueEngine(dialogue, nodes).scriptedTurns() }.getOrDefault(emptyList())

/** Selects a stable authored scenario family without making the database order
 * part of the learner model. The unsuffixed id remains the canonical first
 * family for backups and older content assets. */
internal fun selectDialogueVariant(
    dialogues: List<ContentDialogue>,
    baseId: String,
    completedEpisodes: Int,
    mode: EpisodeMode? = null
): ContentDialogue? {
    val allVariants = dialogues
        .filter { it.id == baseId || it.id.startsWith("$baseId:") }
        .sortedBy { id ->
            if (id.id == baseId) 0
            else id.id.substringAfter(":family-", "").substringBefore(':').toIntOrNull()
                ?: id.id.substringAfter(":scenario-", "").substringBefore(':').toIntOrNull()
                ?: Int.MAX_VALUE
        }
    val variants = if (mode == EpisodeMode.TRANSFER) {
        allVariants.filter { it.blindTransfer || ":blind-transfer" in it.id }.ifEmpty { allVariants }
    } else allVariants.filterNot { it.blindTransfer || ":blind-transfer" in it.id }.ifEmpty { allVariants }
    if (variants.isEmpty()) return null
    return variants[Math.floorMod(completedEpisodes, variants.size)]
}

/** Stable across process restoration, while preventing a universal correct-answer position. */
internal fun orderedEpisodeChoices(correct: String, distractors: List<String>, seed: Int): List<String> {
    if (correct.isBlank()) return emptyList()
    // Select from the whole candidate pool. Taking the first two before ordering
    // made the same distractors recur in every task and episode.
    val alternatives = distractors.filter { it.isNotBlank() && it != correct }
        .distinct()
        .shuffled(Random(seed))
        .take(2)
    val size = alternatives.size + 1
    val correctIndex = Math.floorMod(seed, size)
    return alternatives.toMutableList().apply { add(correctIndex, correct) }
}

private enum class MeaningShape { PHRASE, STATEMENT, QUESTION }

private fun meaningShape(value: String): MeaningShape {
    val trimmed = value.trim()
    val wordCount = Regex("[\\p{L}\\p{N}']+").findAll(trimmed).count()
    return when {
        trimmed.endsWith("?") -> MeaningShape.QUESTION
        wordCount >= 3 || trimmed.endsWith(".") || trimmed.endsWith("!") -> MeaningShape.STATEMENT
        else -> MeaningShape.PHRASE
    }
}

private fun choiceSafeMeaning(value: String): Boolean {
    val trimmed = value.trim()
    val wordCount = Regex("[\\p{L}\\p{N}']+").findAll(trimmed).count()
    return trimmed.isNotBlank() && '\n' !in trimmed && trimmed.length <= 96 && wordCount <= 14
}

/** Chooses meanings that are comparable answer types instead of arbitrary nearby
 * curriculum rows. This keeps phrases with phrases, questions with questions,
 * and similarly sized statements with statements. */
internal fun meaningMatchedDistractors(
    correct: String,
    target: Note,
    candidates: List<Note>,
    seed: Int
): List<String> {
    val targetShape = meaningShape(correct)
    val targetWords = Regex("[\\p{L}\\p{N}']+").findAll(correct).count()
    fun candidateMeaning(note: Note): String = note.exampleTranslation?.takeIf(String::isNotBlank) ?: note.translation
    return candidates.asSequence()
        .filter { it.id != target.id }
        .map { it to candidateMeaning(it).trim() }
        .filter { (_, value) -> choiceSafeMeaning(value) && value != correct && meaningShape(value) == targetShape }
        .distinctBy { (_, value) -> value }
        .sortedWith(compareBy<Pair<Note, String>>(
            { (note, _) -> if (note.partOfSpeech == target.partOfSpeech) 0 else 1 },
            { (_, value) -> kotlin.math.abs(Regex("[\\p{L}\\p{N}']+").findAll(value).count() - targetWords) },
            { (_, value) -> kotlin.math.abs(value.length - correct.length) },
            { (note, value) -> 31 * note.id.hashCode() + value.hashCode() + seed }
        ))
        .map { it.second }
        .take(6)
        .toList()
}

/**
 * The new top-level learning orchestrator. It plans from a communicative outcome,
 * then uses component due state to select what that episode should recycle.
 */
@Singleton
class CommunicativeEpisodeRepository @Inject constructor(
    private val database: AppDatabase,
    private val learningDao: CommunicativeLearningDao,
    private val noteDao: NoteDao,
    private val readerTextDao: ReaderTextDao,
    private val readerEncounterDao: ReaderEncounterDao,
    private val contentDao: ContentDao,
    private val confusablePairDao: ConfusablePairDao,
    private val telemetryDao: TelemetryDao,
    private val settings: SettingsStore,
    private val assets: AssetBootstrap,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    suspend fun recordEpisodeResumed(
        episode: CommunicativeEpisode,
        taskIndex: Int,
        checked: Boolean,
        checkpointSavedAt: Long?,
        at: Long = System.currentTimeMillis()
    ) = withContext(computeDispatcher) {
        telemetryDao.insert(TelemetryEvent(
            timestamp = at,
            eventType = "episode_resumed",
            sessionId = episode.id,
            metadataJson = JSONObject()
                .put("capabilityKey", episode.capabilityKey)
                .put("mode", episode.mode.name)
                .put("taskIndex", taskIndex)
                .put("tasks", episode.tasks.size)
                .put("checked", checked)
                .put("checkpointAgeMs", checkpointSavedAt?.let { (at - it).coerceAtLeast(0L) } ?: JSONObject.NULL)
                .toString()
        ))
    }

    suspend fun recordIncompatibleEpisodeSnapshot(
        payload: String,
        at: Long = System.currentTimeMillis()
    ) = withContext(computeDispatcher) {
        val root = runCatching { JSONObject(payload) }.getOrNull()
        telemetryDao.insert(TelemetryEvent(
            timestamp = at,
            eventType = "episode_snapshot_discarded",
            sessionId = root?.optJSONObject("episode")?.optString("id")?.takeIf(String::isNotBlank),
            metadataJson = JSONObject()
                .put("snapshotVersion", root?.optInt("version") ?: JSONObject.NULL)
                .put("taskIndex", root?.optInt("taskIndex") ?: JSONObject.NULL)
                .put("reason", "incompatible_or_invalid")
                .toString()
        ))
    }

    @Volatile private var componentCurriculumReconciled = false
    private data class UnitGoal(val band: String, val unit: Int, val canDo: String)

    @Volatile private var unitGoalsCache: List<UnitGoal>? = null

    suspend fun nextOverview(now: Long = System.currentTimeMillis()): EpisodeOverview = withContext(computeDispatcher) {
        reconcileComponentCurriculumMembership()
        settings.pendingReaderEpisodeTextId.takeIf { it >= 0L }?.let { textId ->
            readerTextDao.getById(textId)?.let { text ->
                return@withContext EpisodeOverview(
                    capabilityKey = "READER:$textId", band = "READER", unit = 0,
                    canDo = "explain and reuse “${text.title}”", estimatedMinutes = 5,
                    mode = EpisodeMode.TRANSFER, focus = "carry connected-text meaning into interaction",
                    completedEpisodes = 0, transferAccuracy = null
                )
            }
            settings.pendingReaderEpisodeTextId = -1L
        }
        val goal = chooseGoal(now)
        val progress = ensureProgress(goal)
        ensureComponents(goal, now)
        val components = learningDao.componentsForCapability(ComponentKeys.capability(goal.band, goal.unit))
        val policy = policyFor(components, progress, now)
        EpisodeOverview(
            capabilityKey = ComponentKeys.capability(goal.band, goal.unit),
            band = goal.band,
            unit = goal.unit,
            canDo = goal.canDo,
            estimatedMinutes = policy.targetMinutes,
            mode = policy.mode,
            focus = policy.focus,
            completedEpisodes = progress.completedEpisodes,
            transferAccuracy = progress.lastTransferScore
        )
    }

    suspend fun buildNextEpisode(now: Long = System.currentTimeMillis()): CommunicativeEpisode = withContext(computeDispatcher) {
        reconcileComponentCurriculumMembership()
        settings.pendingReaderEpisodeTextId.takeIf { it >= 0L }?.let { textId ->
            buildReaderFollowUp(textId)?.let { episode ->
                settings.pendingReaderEpisodeTextId = -1L
                return@withContext episode
            }
            settings.pendingReaderEpisodeTextId = -1L
        }
        val goal = chooseGoal(now)
        val capabilityKey = ComponentKeys.capability(goal.band, goal.unit)
        ensureComponents(goal, now)
        val progress = ensureProgress(goal)
        val components = learningDao.componentsForCapability(capabilityKey)
        val policy = policyFor(components, progress, now)
        val componentsByNote = components.filter { it.noteId != null }.groupBy { it.noteId!! }
        val notes = noteDao.getByIds(componentsByNote.keys.toList()).associateBy { it.id }
        val prioritizedNotes = AdaptiveEpisodePolicyEngine.rankNoteIds(components, policy, now)
            .mapNotNull(notes::get)
            .take(max(5, policy.newNoteLimit))
        val studiedCapabilityKeys = learningDao.allProgress()
            .filter { it.completedEpisodes > 0 }
            .mapTo(hashSetOf()) { it.capabilityKey }
        val interleavedNotes = learningDao.dueComponents(now, 80)
            .asSequence()
            .filter { it.reps > 0 && it.noteId != null && it.capabilityKey != capabilityKey && it.capabilityKey in studiedCapabilityKeys }
            .distinctBy { it.noteId }
            .mapNotNull { it.noteId }
            .filter { it !in componentsByNote }
            .take(policy.interleaveLimit)
            .toList()
            .let { noteDao.getByIds(it) }
        val contrasts = contrastCandidates(prioritizedNotes, policy.contrastLimit)

        val dialogueId = "${goal.band.lowercase()}_unit_${goal.unit.toString().padStart(3, '0')}_dialogue"
        // Cycle through authored scenario families as a capability receives
        // repeated episodes, while the adaptive policy still controls memory
        // mode, task mix, and repair intensity.
        val dialogue = selectDialogueVariant(
            contentDao.dialoguesFor(goal.unit), dialogueId, progress.completedEpisodes, policy.mode
        )
        val settings = dialogue?.settingsJson?.let { payload ->
            runCatching { JSONArray(payload) }.getOrNull()?.let { array ->
                (0 until array.length()).map(array::getString)
            }
        }.orEmpty()
        val setting = settings.getOrNull(Math.floorMod(progress.completedEpisodes, settings.size.coerceAtLeast(1)))
        val turns = dialogue?.let { content ->
            safelyScriptedTurns(content, contentDao.nodesForDialogue(content.id)).mapIndexed { index, turn ->
                if (index == 0 && turn.speaker.equals("npc", true) && setting != null) turn.copy(
                    en = "Setting: $setting. Objective: ${content.objective}. ${content.informationGap}"
                ) else turn
            }
        }.orEmpty()
        val tasks = EpisodeTaskPlanner.plan(
            turns, prioritizedNotes, components, policy, interleavedNotes, contrasts,
            register = dialogue?.register ?: "neutral",
            intention = dialogue?.intention ?: "clarify",
            blindAssessment = dialogue?.blindTransfer == true,
            choiceSeed = progress.completedEpisodes,
            choiceNotes = notes.values.toList()
        )
        val safeTasks = tasks.ifEmpty {
            listOf(EpisodeTask("empty", EpisodeTaskKind.CONTEXT, "This capability is ready for new material.", goal.canDo))
        }
        CommunicativeEpisode(
            capabilityKey = capabilityKey,
            sourceDialogueId = dialogue?.id,
            band = goal.band,
            unit = goal.unit,
            canDo = goal.canDo,
            title = dialogue?.let { "${setting ?: it.title.substringBefore(':')}: ${it.objective}" } ?: "Practice: ${goal.canDo}",
            objective = dialogue?.objective ?: goal.canDo,
            informationGap = dialogue?.informationGap.orEmpty(),
            setting = setting,
            register = dialogue?.register ?: "neutral",
            activity = dialogue?.activity ?: "interaction",
            blindAssessment = dialogue?.blindTransfer == true,
            estimatedMinutes = policy.targetMinutes,
            mode = policy.mode,
            focus = policy.focus,
            tasks = safeTasks
        ).also { episode ->
            telemetryDao.insert(TelemetryEvent(
                eventType = "episode_started",
                sessionId = episode.id,
                metadataJson = JSONObject()
                    .put("capabilityKey", capabilityKey)
                    .put("sourceDialogueId", dialogue?.id ?: JSONObject.NULL)
                    .put("canDo", goal.canDo)
                    .put("tasks", episode.tasks.size)
                    .put("mode", policy.mode.name)
                    .put("focus", policy.focus)
                    .put("targetMinutes", policy.targetMinutes)
                    .put("recentMissRate", policy.recentMissRate)
                    .put("weakestKind", policy.weakestKind)
                    .toString()
            ))
        }
    }

    /** Removes not-yet-consumed lexical tasks whose components were retired from
     * Reader/Settings while the learner had this episode open. Completed steps stay
     * in place so progress and the persisted task index remain coherent. */
    suspend fun reconcileRetiredComponents(
        episode: CommunicativeEpisode,
        firstPendingTask: Int
    ): CommunicativeEpisode = withContext(computeDispatcher) {
        val tasks = episode.tasks.filterIndexed { index, task ->
            if (index < firstPendingTask || task.componentKeys.isEmpty()) return@filterIndexed true
            task.componentKeys.any { key -> learningDao.component(key)?.retired != true }
        }
        episode.copy(tasks = tasks)
    }

    suspend fun recordTask(
        episode: CommunicativeEpisode,
        task: EpisodeTask,
        success: Boolean,
        responseMs: Long,
        at: Long = System.currentTimeMillis()
    ) = withContext(computeDispatcher) {
        database.withTransaction {
            val weight = evidenceWeight(task)
            var recordedAny = false
            val readinessBefore = mutableListOf<Double>()
            val readinessAfter = mutableListOf<Double>()
            val componentKinds = linkedSetOf<String>()
            task.componentKeys.forEach { key ->
                if (learningDao.evidenceForTaskComponent(episode.id, task.id, key) != null) return@forEach
                val current = learningDao.component(key) ?: return@forEach
                readinessBefore += AdaptiveEpisodePolicyEngine.memoryReadiness(current, at)
                componentKinds += current.kind
                val updated = ComponentScheduler.update(current, success, weight, at)
                readinessAfter += AdaptiveEpisodePolicyEngine.memoryReadiness(updated, at)
                learningDao.updateComponent(updated)
                val inserted = learningDao.insertEvidence(CapabilityEvidence(
                    componentKey = key,
                    episodeId = episode.id,
                    taskId = task.id,
                    observedAt = at,
                    taskKind = task.kind.name,
                    outcome = if (success) "SUCCESS" else "MISS",
                    supportLevel = task.supportLevel,
                    evidenceWeight = weight,
                    responseMs = responseMs,
                    novelContext = task.novelContext
                ))
                recordedAny = recordedAny || inserted != -1L
            }
            if (recordedAny || task.componentKeys.isEmpty()) telemetryDao.insert(TelemetryEvent(
                timestamp = at,
                eventType = "episode_task_completed",
                sessionId = episode.id,
                responseMs = responseMs,
                metadataJson = JSONObject()
                    .put("capabilityKey", episode.capabilityKey)
                    .put("taskKind", task.kind.name)
                    .put("mode", episode.mode.name)
                    .put("supportLevel", task.supportLevel)
                    .put("evidenceWeight", weight)
                    .put("novelContext", task.novelContext)
                    .put("success", success)
                    .put("componentKinds", JSONArray(componentKinds.toList()))
                    .put("readinessBefore", readinessBefore.takeIf { it.isNotEmpty() }?.average() ?: JSONObject.NULL)
                    .put("readinessAfter", readinessAfter.takeIf { it.isNotEmpty() }?.average() ?: JSONObject.NULL)
                    .toString()
            ))
        }
    }

    suspend fun finishEpisode(
        episode: CommunicativeEpisode,
        transferSuccesses: Int,
        transferAttempts: Int,
        at: Long = System.currentTimeMillis()
    ): EpisodeCompletion = withContext(computeDispatcher) {
        if (episode.sourceReaderTextId != null) {
            if (telemetryDao.countByTypeAndSession("reader_followup_completed", episode.id) == 0) {
                telemetryDao.insert(TelemetryEvent(
                    timestamp = at,
                    eventType = "reader_followup_completed",
                    sessionId = episode.id,
                    metadataJson = JSONObject().put("readerTextId", episode.sourceReaderTextId).toString()
                ))
            }
            return@withContext EpisodeCompletion(false, false, 0, 0, 0.0)
        }
        val requiredCompletions = requiredCompletions(episode.band, episode.unit)
        database.withTransaction {
            val existing = learningDao.progress(episode.capabilityKey) ?: CapabilityProgress(
                episode.capabilityKey, episode.band, episode.unit, episode.canDo
            )
            val currentMastery = AdaptiveEpisodePolicyEngine.masteryProfile(
                learningDao.componentsForCapability(episode.capabilityKey), at
            )
            if (telemetryDao.countByTypeAndSession("episode_completed", episode.id) > 0) {
                return@withTransaction EpisodeCompletion(
                    routeReady = currentMastery.supportsRouteAdvance(existing, requiredCompletions),
                    certified = existing.certifiedAt != null,
                    observedNotes = currentMastery.observedNotes,
                    totalNotes = currentMastery.totalNotes,
                    memoryReadiness = currentMastery.averageMemoryReadiness
                )
            }
            val totalAttempts = existing.attemptedTransferProbes + transferAttempts
            val totalSuccesses = existing.successfulTransferProbes + transferSuccesses
            val progressed = existing.copy(
                canDo = episode.canDo,
                completedEpisodes = existing.completedEpisodes + 1,
                successfulTransferProbes = totalSuccesses,
                attemptedTransferProbes = totalAttempts,
                lastTransferScore = if (transferAttempts > 0) transferSuccesses.toDouble() / transferAttempts else existing.lastTransferScore,
                lastEpisodeAt = at
            )
            val certifiedAt = certificationTime(
                progressed,
                currentMastery,
                learningDao.recentEvidenceForCapability(episode.capabilityKey, 200),
                at,
                requiredCompletions
            )
            val updated = progressed.copy(certifiedAt = certifiedAt)
            learningDao.upsertProgress(updated)
            val routeReady = currentMastery.supportsRouteAdvance(updated, requiredCompletions)
            telemetryDao.insert(TelemetryEvent(
                timestamp = at,
                eventType = "episode_completed",
                sessionId = episode.id,
                metadataJson = JSONObject()
                    .put("capabilityKey", episode.capabilityKey)
                    .put("transferSuccesses", transferSuccesses)
                    .put("transferAttempts", transferAttempts)
                    .put("certified", certifiedAt != null)
                    .put("routeReady", routeReady)
                    .put("requiredCompletions", requiredCompletions)
                    .put("observedNotes", currentMastery.observedNotes)
                    .put("totalNotes", currentMastery.totalNotes)
                    .put("memoryReadiness", currentMastery.averageMemoryReadiness)
                    .toString()
            ))
            EpisodeCompletion(
                routeReady = routeReady,
                certified = certifiedAt != null,
                observedNotes = currentMastery.observedNotes,
                totalNotes = currentMastery.totalNotes,
                memoryReadiness = currentMastery.averageMemoryReadiness
            )
        }
    }

    private suspend fun buildReaderFollowUp(textId: Long): CommunicativeEpisode? {
        val text = readerTextDao.getById(textId) ?: return null
        val encounters = readerEncounterDao.getForText(textId)
        val notes = noteDao.getByIds(encounters.map { it.noteId }).filter { it.status != WordStatus.IGNORED }
        val componentKeys = notes.flatMap { note ->
            val band = note.cefrLevel ?: return@flatMap emptyList()
            val unit = note.unit ?: return@flatMap emptyList()
            val capability = ComponentKeys.capability(band, unit)
            listOf(
                KnowledgeComponent(ComponentKeys.meaning(note.id), "MEANING", capability, band, unit, noteId = note.id),
                KnowledgeComponent(ComponentKeys.form(note.id), "FORM", capability, band, unit, noteId = note.id),
                KnowledgeComponent(ComponentKeys.sound(note.id), "SOUND", capability, band, unit, noteId = note.id)
            ).onEach { component -> if (learningDao.component(component.key) == null) learningDao.upsertComponent(component) }
                .map { it.key }
        }.distinct()
        val russianSentences = text.body.split(Regex("(?<=[.!?…])\\s+")).filter(String::isNotBlank)
        val englishSentences = text.translationBody.orEmpty().split(Regex("(?<=[.!?…])\\s+")).filter(String::isNotBlank)
        fun conciseSummary(value: String): String = value.split(Regex("(?<=[.!?…])\\s+"))
            .filter(String::isNotBlank).take(2).joinToString(" ").take(320)
        val otherSummaries = readerTextDao.getAll().asSequence()
            .filter { it.id != textId }.mapNotNull { it.translationBody?.takeIf(String::isNotBlank) }
            .map(::conciseSummary).filter(String::isNotBlank).take(2).toList()
        val tasks = buildList {
            add(EpisodeTask(
                id = "reader:context:$textId", kind = EpisodeTaskKind.CONTEXT,
                instruction = "Return to the situation. Read for the speaker's goal and the missing detail.",
                russian = text.body
            ))
            text.translationBody?.takeIf { it.isNotBlank() && otherSummaries.isNotEmpty() }?.let { fullSummary ->
                val summary = conciseSummary(fullSummary)
                add(EpisodeTask(
                    id = "reader:summary:$textId", kind = EpisodeTaskKind.READING,
                    instruction = "Choose the meaning that best summarizes the passage.", russian = text.title,
                    expected = summary,
                    choices = orderedEpisodeChoices(summary, otherSummaries, textId.hashCode()),
                    componentKeys = componentKeys.filter { it.startsWith("MEANING:") }, supportLevel = 1,
                    novelContext = true
                ))
            }
            russianSentences.firstOrNull()?.let { sentence ->
                add(EpisodeTask(
                    id = "reader:relay:$textId", kind = EpisodeTaskKind.MEDIATION,
                    instruction = "Relay the opening to someone who did not read it.", russian = "",
                    english = englishSentences.firstOrNull() ?: "Explain the opening in Russian.",
                    expected = sentence, acceptable = listOf(sentence),
                    componentKeys = componentKeys, supportLevel = 2, novelContext = true
                ))
            }
            russianSentences.lastOrNull()?.let { sentence ->
                add(EpisodeTask(
                    id = "reader:character:$textId", kind = EpisodeTaskKind.GUIDED_RESPONSE,
                    instruction = "Answer as a person in the passage and move the situation forward.", russian = "",
                    english = englishSentences.lastOrNull() ?: "Respond in Russian as the speaker.",
                    expected = sentence, acceptable = listOf(sentence),
                    componentKeys = componentKeys, supportLevel = 2, novelContext = true
                ))
            }
            notes.firstOrNull { !it.exampleSentence2.isNullOrBlank() }?.let { note ->
                add(EpisodeTask(
                    id = "reader:ending:$textId:${note.id}", kind = EpisodeTaskKind.GUIDED_RESPONSE,
                    instruction = "Change the ending while reusing a key expression from the passage.", russian = "",
                    english = note.exampleTranslation2 ?: note.translation,
                    expected = note.exampleSentence2 ?: note.russian,
                    componentKeys = listOf(ComponentKeys.form(note.id)), supportLevel = 2, novelContext = true
                ))
            }
            notes.firstOrNull { !it.exampleSentence3.isNullOrBlank() }?.let { note ->
                add(EpisodeTask(
                    id = "reader:transfer:$textId:${note.id}", kind = EpisodeTaskKind.TRANSFER,
                    instruction = "Reuse the passage vocabulary in a second, unrelated real-world situation.", russian = "",
                    english = note.exampleTranslation3 ?: note.translation,
                    expected = note.exampleSentence3 ?: note.russian,
                    componentKeys = listOf(ComponentKeys.form(note.id)), supportLevel = 1, novelContext = true
                ))
            }
        }
        if (tasks.size < 2) return null
        return CommunicativeEpisode(
            sourceReaderTextId = textId,
            capabilityKey = "READER:$textId", band = "READER", unit = 0,
            canDo = "explain and reuse “${text.title}”", title = "From reading to interaction: ${text.title}",
            estimatedMinutes = 5, mode = EpisodeMode.TRANSFER,
            focus = "summarize, mediate, answer in character, and transfer passage language",
            tasks = tasks
        )
    }

    private suspend fun policyFor(
        components: List<KnowledgeComponent>,
        progress: CapabilityProgress,
        now: Long
    ): AdaptiveEpisodePolicy = AdaptiveEpisodePolicyEngine.decide(
        components = components,
        recentEvidence = learningDao.recentEvidenceForCapability(progress.capabilityKey),
        progress = progress,
        now = now
    )

    private suspend fun contrastCandidates(notes: List<Note>, limit: Int): List<ContrastCandidate> {
        if (limit <= 0 || notes.isEmpty()) return emptyList()
        val prioritizedIds = notes.mapTo(hashSetOf()) { it.id }
        val pairs = notes.flatMap { confusablePairDao.getForNote(it.id) }.distinctBy { it.id }
        val partnerIds = pairs.flatMap { listOf(it.firstNoteId, it.secondNoteId) }
            .filter { it !in prioritizedIds }
            .distinct()
        val byId = (notes + noteDao.getByIds(partnerIds)).associateBy { it.id }
        return pairs.mapNotNull { pair ->
            val first = byId[pair.firstNoteId] ?: return@mapNotNull null
            val second = byId[pair.secondNoteId] ?: return@mapNotNull null
            val target = when {
                first.id in prioritizedIds -> first
                second.id in prioritizedIds -> second
                else -> return@mapNotNull null
            }
            val distractor = if (target.id == first.id) second else first
            if (target.russian.equals(distractor.russian, ignoreCase = true)) null
            else ContrastCandidate(target, distractor, pair.reason)
        }.distinctBy { setOf(it.target.id, it.distractor.id) }.take(limit)
    }

    private suspend fun chooseGoal(now: Long): UnitGoal {
        val goals = unitGoals()
        val progress = learningDao.allProgress().associateBy { it.capabilityKey }
        val nextLearningGoal = goals.firstOrNullSuspend { goal ->
            val key = ComponentKeys.capability(goal.band, goal.unit)
            val current = progress[key] ?: return@firstOrNullSuspend true
            val profile = AdaptiveEpisodePolicyEngine.masteryProfile(
                learningDao.componentsForCapability(key),
                now
            )
            !profile.supportsRouteAdvance(current, requiredCompletions(goal.band, goal.unit))
        }
        // Every third completed episode can revisit an overdue, previously studied
        // capability. Components projected from untouched future cards do not get to
        // hijack the route merely because their legacy due value is zero.
        val completedEpisodes = progress.values.sumOf { it.completedEpisodes }
        val studiedKeys = progress.filterValues { it.completedEpisodes > 0 }.keys
        val dueReviewKey = if (completedEpisodes > 0 && completedEpisodes % 3 == 2) {
            learningDao.dueComponents(now, 40).firstOrNull { it.capabilityKey in studiedKeys }?.capabilityKey
        } else null
        return dueReviewKey?.let { key -> goals.firstOrNull { ComponentKeys.capability(it.band, it.unit) == key } }
            ?: nextLearningGoal
            ?: goals.first()
    }

    private suspend fun ensureProgress(goal: UnitGoal): CapabilityProgress {
        val key = ComponentKeys.capability(goal.band, goal.unit)
        return learningDao.progress(key)?.let { current ->
            if (current.canDo == goal.canDo) current else current.copy(canDo = goal.canDo).also { learningDao.upsertProgress(it) }
        } ?: CapabilityProgress(key, goal.band, goal.unit, goal.canDo).also { learningDao.upsertProgress(it) }
    }

    /** Sum of the authored recurrence budget for this can-do cluster.  This is what
     * turns the 600-family content contract into exactly 3,020 expected route
     * completions instead of advancing after three nominal screens per unit. */
    private suspend fun requiredCompletions(band: String, unit: Int): Int {
        val baseId = "${band.lowercase()}_unit_${unit.toString().padStart(3, '0')}_dialogue"
        val families = contentDao.dialoguesFor(unit).filter { it.id == baseId || it.id.startsWith("$baseId:") }
        return families.sumOf { it.expectedCompletions }.coerceAtLeast(3)
    }

    private suspend fun ensureComponents(goal: UnitGoal, now: Long) {
        val key = ComponentKeys.capability(goal.band, goal.unit)
        // Include deliberately retired components so normal bootstrap never
        // silently reactivates something the learner parked in Settings.
        val existingKeys = learningDao.allComponentsForCapability(key).mapTo(hashSetOf()) { it.key }
        val unitNotes = noteDao.getAll().filter {
            it.tier == 0 && it.unit == goal.unit && (it.cefrLevel ?: "A1") == goal.band
        }
        val missing = KnowledgeComponentFactory.forUnit(unitNotes, goal.band, goal.unit, now)
            .filter { it.key !in existingKeys }
        if (missing.isNotEmpty()) learningDao.upsertComponents(missing)
    }

    private suspend fun reconcileComponentCurriculumMembership() {
        if (componentCurriculumReconciled) return
        database.withTransaction {
            val notesById = noteDao.getAll().associateBy { it.id }
            learningDao.allComponents().forEach { component ->
                val reconciled = reconcileComponentMembership(component, component.noteId?.let(notesById::get))
                if (reconciled != component) learningDao.updateComponent(reconciled)
            }
        }
        componentCurriculumReconciled = true
    }

    private fun evidenceWeight(task: EpisodeTask): Double = when {
        task.kind == EpisodeTaskKind.NOTICE || task.kind == EpisodeTaskKind.CONTEXT -> 0.0
        // On-device ASR is independent production evidence, but recognition noise
        // prevents treating one transcript as perfectly diagnostic.
        task.kind == EpisodeTaskKind.PRODUCTION_PROBE -> 0.80
        task.supportLevel >= 3 -> 0.10
        task.supportLevel == 2 && task.novelContext -> 0.45
        task.supportLevel == 2 -> 0.35
        task.supportLevel == 1 -> 0.65
        else -> 1.0
    }

    private suspend fun unitGoals(): List<UnitGoal> = unitGoalsCache ?: run {
        val payload = assets.readTextAsset("units.json") ?: error("Missing units.json curriculum asset")
        val rows = JSONObject(payload).getJSONArray("units")
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            UnitGoal(row.optString("band", "A1"), row.getInt("unit"), row.getString("canDo"))
        }.distinctBy { ComponentKeys.capability(it.band, it.unit) }
            .sortedWith(compareBy<UnitGoal> { listOf("A1", "A2", "B1", "B2", "C1", "C2").indexOf(it.band) }.thenBy { it.unit })
            .also { unitGoalsCache = it }
    }

    private suspend inline fun <T> Iterable<T>.firstOrNullSuspend(crossinline predicate: suspend (T) -> Boolean): T? {
        for (item in this) if (predicate(item)) return item
        return null
    }
}

internal object KnowledgeComponentFactory {
    fun forUnit(notes: List<Note>, band: String, unit: Int, now: Long): List<KnowledgeComponent> {
        val capabilityKey = ComponentKeys.capability(band, unit)
        val lexical = notes.filter {
            !it.partOfSpeech.equals("lesson", ignoreCase = true) &&
                it.status != WordStatus.KNOWN &&
                it.status != WordStatus.IGNORED
        }.flatMap { note ->
            listOf(
                KnowledgeComponent(ComponentKeys.meaning(note.id), "MEANING", capabilityKey, band, unit, noteId = note.id, due = now),
                KnowledgeComponent(ComponentKeys.form(note.id), "FORM", capabilityKey, band, unit, noteId = note.id, due = now),
                KnowledgeComponent(ComponentKeys.sound(note.id), "SOUND", capabilityKey, band, unit, noteId = note.id, due = now)
            )
        }
        val constructions = notes.mapNotNull { it.conceptId }.distinct().map { concept ->
            KnowledgeComponent(
                key = ComponentKeys.construction(concept, band, unit),
                kind = "CONSTRUCTION",
                capabilityKey = capabilityKey,
                band = band,
                unit = unit,
                conceptId = concept,
                due = now
            )
        }
        return lexical + constructions
    }
}

/** Pure task composition so answer leakage and episode shape are unit-testable. */
internal object EpisodeTaskPlanner {
    /**
     * Older generated dialogue rows embedded a generic Russian instruction plus
     * the actual English meaning in one long string. A response task must always
     * expose the meaning the learner is being asked to express; otherwise a tile
     * bank is an answer-without-a-question puzzle. New content stores the concise
     * cue directly, while this extraction keeps upgraded installs usable.
     */
    private fun responseCue(turn: DialogueTurn): String? {
        val embedded = Regex("Communicate either [“\"]([^”\"]+)[”\"]")
            .find(turn.en)?.groupValues?.getOrNull(1)
        return (embedded ?: turn.en).trim().takeIf(String::isNotBlank)
    }

    fun plan(
        turns: List<DialogueTurn>,
        prioritizedNotes: List<Note>,
        components: List<KnowledgeComponent>,
        choiceNotes: List<Note> = prioritizedNotes
    ): List<EpisodeTask> = plan(
        turns,
        prioritizedNotes,
        components,
        AdaptiveEpisodePolicy(
            mode = EpisodeMode.ACQUIRE,
            focus = "build a small usable base in this situation",
            targetMinutes = 5,
            maxTasks = 20,
            newNoteLimit = 2,
            interleaveLimit = 0,
            contrastLimit = 0,
            recentMissRate = 0.0,
            weakestKind = "MEANING"
        ),
        choiceNotes = choiceNotes
    )

    fun plan(
        turns: List<DialogueTurn>,
        prioritizedNotes: List<Note>,
        components: List<KnowledgeComponent>,
        policy: AdaptiveEpisodePolicy,
        interleavedNotes: List<Note> = emptyList(),
        contrasts: List<ContrastCandidate> = emptyList(),
        register: String = "neutral",
        intention: String = "clarify",
        blindAssessment: Boolean = false,
        choiceSeed: Int = 0,
        choiceNotes: List<Note> = prioritizedNotes
    ): List<EpisodeTask> = buildList {
        interleavedNotes.take(policy.interleaveLimit).forEach { note ->
            val expected = note.exampleTranslation?.takeIf(String::isNotBlank) ?: note.translation
            val choices = orderedEpisodeChoices(
                expected,
                meaningMatchedDistractors(expected, note, choiceNotes, 31 * note.id.hashCode() + choiceSeed),
                31 * note.id.hashCode() + choiceSeed
            )
            if (choices.size >= 2) add(EpisodeTask(
                id = "retrieval:${note.id}",
                kind = EpisodeTaskKind.RETRIEVAL,
                instruction = "Reconnect an earlier phrase before today's situation.",
                russian = note.exampleSentence?.takeIf(String::isNotBlank) ?: note.russian,
                expected = expected,
                choices = choices,
                componentKeys = listOf(ComponentKeys.meaning(note.id)),
                supportLevel = 1,
                novelContext = true
            ))
        }
        // Only NPC setup belongs in the context. Including the first learner turn
        // here would print the answer immediately before asking the learner for it.
        val opening = turns.takeWhile { !it.speaker.equals("learner", ignoreCase = true) }
        if (opening.isNotEmpty()) {
            add(EpisodeTask(
                id = "context",
                kind = EpisodeTaskKind.CONTEXT,
                instruction = "Listen to the opening. You do not need every word.",
                russian = opening.joinToString("\n") { it.ru }
            ))
        }
        prioritizedNotes.take(policy.newNoteLimit).forEachIndexed { index, note ->
            val correct = note.exampleTranslation?.takeIf { it.isNotBlank() } ?: note.translation
            val choices = orderedEpisodeChoices(
                correct,
                meaningMatchedDistractors(correct, note, choiceNotes, 31 * note.id.hashCode() + choiceSeed),
                31 * note.id.hashCode() + choiceSeed
            )
            if (choiceSafeMeaning(correct) && choices.size >= 2) {
                val listeningProfile = Math.floorMod(note.id.hashCode(), 7)
                add(EpisodeTask(
                    id = if (index == 0) "listening:${note.id}" else "reading:${note.id}",
                    kind = if (index == 0) EpisodeTaskKind.LISTENING else EpisodeTaskKind.READING,
                    instruction = if (index == 0) "Listen, then choose the meaning." else "Read, then choose the meaning.",
                    russian = note.exampleSentence?.takeIf { it.isNotBlank() } ?: note.russian,
                    expected = correct,
                    choices = choices,
                    componentKeys = if (index == 0) {
                        listOf(ComponentKeys.sound(note.id), ComponentKeys.meaning(note.id))
                    } else {
                        listOf(ComponentKeys.meaning(note.id))
                    },
                    supportLevel = 1,
                    audioRate = listOf(0.92f, 1.20f, 1.14f, 0.88f, 1.0f, 0.84f, 1.0f)[listeningProfile],
                    audioPitch = listOf(1.0f, 1.02f, 1.12f, 0.94f, 0.90f, 0.88f, 1.0f)[listeningProfile],
                    voiceVariant = Math.floorMod((note.id * 13).hashCode(), 4),
                    audioCondition = listOf(
                        "clear", "fast casual reductions", "urgent emotional tone", "warm emotional tone",
                        "telephone-quality", "public announcement", "controlled background noise"
                    )[listeningProfile]
                ))
            }
            add(EpisodeTask(
                id = "notice:$index:${note.id}",
                kind = EpisodeTaskKind.NOTICE,
                instruction = "Notice the useful expression in context.",
                russian = note.exampleSentence?.takeIf { it.isNotBlank() } ?: note.russian,
                english = note.exampleTranslation?.takeIf { it.isNotBlank() } ?: note.translation,
                expected = note.russian,
                componentKeys = listOf(ComponentKeys.meaning(note.id), ComponentKeys.sound(note.id)),
                supportLevel = 3
            ))
        }
        contrasts.take(policy.contrastLimit).forEach { contrast ->
            add(EpisodeTask(
                id = "contrast:${contrast.target.id}:${contrast.distractor.id}",
                kind = EpisodeTaskKind.CONTRAST,
                instruction = "Choose the Russian that means this. The close alternative is here on purpose.",
                russian = "",
                english = contrast.target.translation,
                expected = contrast.target.russian,
                choices = orderedEpisodeChoices(
                    contrast.target.russian,
                    listOf(contrast.distractor.russian),
                    31 * (contrast.target.id * 31L + contrast.distractor.id).hashCode() + choiceSeed
                ),
                componentKeys = listOf(ComponentKeys.meaning(contrast.target.id)),
                supportLevel = 1,
                novelContext = true
            ))
        }
        if (prioritizedNotes.firstOrNull()?.cefrLevel in setOf("B2", "C1", "C2")) {
            add(pragmaticsTask(register, intention, prioritizedNotes.first().id, components))
        }
        val learnerTurns = turns.filter { it.speaker.equals("learner", true) && it.acceptable.isNotEmpty() }
        learnerTurns
            .forEachIndexed { index, turn ->
                if (index >= 2) {
                    val priorIndex = index - 2
                    val prior = learnerTurns[priorIndex]
                    val facts = prior.responseFacts.values.distinct()
                    if (facts.size >= 2) add(EpisodeTask(
                        id = "information-gap:$priorIndex",
                        kind = EpisodeTaskKind.INFORMATION_GAP,
                        instruction = "Two turns later: which detail did the other speaker reveal after your choice?",
                        russian = "",
                        choices = facts,
                        dependsOnTaskId = "guided:$priorIndex",
                        expectedByAnswer = prior.responseFacts,
                        supportLevel = 1,
                        novelContext = true
                    ))
                }
                val expected = turn.acceptable.first()
                val expectedWords = Regex("[\\p{L}\\p{N}-]+")
                    .findAll(expected)
                    .map { RussianForms.normalize(it.value) }
                    .toSet()
                val matched = prioritizedNotes.filter { note ->
                    RussianForms.surfaceForms(note).any { RussianForms.normalize(it) in expectedWords }
                }
                val keys = matched.map { ComponentKeys.form(it.id) }
                    // A dialogue may realize a construction without containing one
                    // of the five prioritized lexemes. In that case assign only the
                    // authored construction, never an unrelated convenient word.
                    .ifEmpty { components.filter { it.kind == "CONSTRUCTION" }.take(2).map { it.key } }
                val semanticAnchors = matched.flatMap(RussianForms::surfaceForms)
                    .map(RussianForms::normalize).filter(String::isNotBlank).distinct()
                val acceptableTokenSets = turn.acceptable.map { answer ->
                    Regex("[\\p{L}\\p{N}-]+").findAll(answer).map { RussianForms.normalize(it.value) }.toSet()
                }
                val sharedTokens = acceptableTokenSets.reduceOrNull { shared, tokens -> shared.intersect(tokens) }.orEmpty()
                add(EpisodeTask(
                    id = "guided:$index",
                    kind = if (policy.mode == EpisodeMode.TRANSFER && policy.supportStage >= 3 && blindAssessment) EpisodeTaskKind.PRODUCTION_PROBE else EpisodeTaskKind.GUIDED_RESPONSE,
                    instruction = if (policy.supportStage == 0) {
                        "Choose the Russian response that expresses this meaning."
                    } else {
                        "Build the Russian response that expresses this meaning."
                    },
                    russian = "",
                    // Supported construction still needs a communicative cue.
                    // Only an authored blind production probe may hide it.
                    english = responseCue(turn).takeUnless { blindAssessment && policy.mode == EpisodeMode.TRANSFER },
                    expected = expected,
                    repairExpected = turn.acceptable.lastOrNull(),
                    acceptable = turn.acceptable,
                    // Generated linear carrier banks have no authored
                    // conversational consequence. Only preserve feedback when
                    // a genuinely branching authored turn supplies alternatives.
                    responseFeedback = turn.responseFeedback.takeIf {
                        turn.responseFacts.values.distinct().size >= 2
                    }.orEmpty(),
                    semanticAnchors = semanticAnchors,
                    constructionCues = sharedTokens.filter { token ->
                        semanticAnchors.none { anchor -> token == anchor } && token.length > 1
                    },
                    choices = if (policy.supportStage == 0) {
                        turn.acceptable.filterIndexed { answerIndex, _ -> answerIndex == 0 || answerIndex == turn.acceptable.lastIndex }.distinct()
                    } else emptyList(),
                    componentKeys = keys.distinct(),
                    supportLevel = when {
                        policy.supportStage == 0 -> 2
                        policy.supportStage == 1 -> 2
                        policy.supportStage == 2 || !blindAssessment -> 1
                        else -> 0
                    },
                    novelContext = policy.supportStage >= 2
                ))
            }
        // Higher-level learners must also relay meaning, not only answer for
        // themselves. This is a distinct CEFR mediation activity: the cue is
        // presented in English and the learner reconstructs the Russian request
        // or statement in a new turn.
        if (policy.mode != EpisodeMode.ACQUIRE) {
            // The root NPC turn is scene-setting copy whose English side may
            // describe the scenario instead of translating the Russian. Prefer
            // an actual NPC consequence after a learner turn for mediation.
            val reply = turns.dropWhile { !it.speaker.equals("learner", true) }
                .drop(1)
                .firstOrNull { it.speaker.equals("npc", true) }
                ?: turns.firstOrNull { it.speaker.equals("npc", true) }
            // A generic scene opener is not a mediation carrier. If there is no
            // real NPC reply after a learner move, omit mediation for this arc.
            reply?.takeIf { candidate ->
                turns.indexOf(candidate) > turns.indexOfFirst { it.speaker.equals("learner", true) }
            }?.let { turn ->
                add(EpisodeTask(
                    id = "mediation:${turn.nodeId}",
                    kind = EpisodeTaskKind.MEDIATION,
                    instruction = "Relay the other speaker's meaning in Russian.",
                    russian = "",
                    english = turn.en,
                    expected = turn.ru,
                    componentKeys = prioritizedNotes.firstOrNull()?.let { note ->
                        listOf(ComponentKeys.meaning(note.id), ComponentKeys.form(note.id))
                    }.orEmpty(),
                    supportLevel = 2,
                    novelContext = true
                ))
            }
        }
        prioritizedNotes.firstOrNull { note ->
            !note.exampleSentence2.isNullOrBlank() && !note.exampleTranslation2.isNullOrBlank()
        }?.let { note ->
            val independent = policy.mode == EpisodeMode.TRANSFER && blindAssessment
            add(EpisodeTask(
                id = "transfer:${note.id}",
                kind = if (independent) EpisodeTaskKind.PRODUCTION_PROBE else EpisodeTaskKind.TRANSFER,
                instruction = if (independent) "Say the Russian response without seeing it." else "Use the language in a different situation.",
                russian = "",
                english = note.exampleTranslation2,
                expected = note.exampleSentence2,
                repairExpected = note.exampleSentence3,
                repairEnglish = note.exampleTranslation3,
                componentKeys = listOf(ComponentKeys.form(note.id)),
                supportLevel = if (independent) 0 else 2,
                novelContext = true
            ))
        } ?: prioritizedNotes.firstOrNull()?.let { note ->
            val independent = policy.mode == EpisodeMode.TRANSFER && blindAssessment
            add(EpisodeTask(
                id = "transfer:fallback:${note.id}",
                kind = if (independent) EpisodeTaskKind.PRODUCTION_PROBE else EpisodeTaskKind.TRANSFER,
                instruction = if (independent) "Say the Russian expression without seeing it." else "Use the expression in a different prompt.",
                russian = "",
                english = note.translation,
                expected = note.russian,
                repairExpected = note.exampleSentence2,
                repairEnglish = note.exampleTranslation2,
                componentKeys = listOf(ComponentKeys.form(note.id)),
                supportLevel = if (independent) 0 else 2,
                novelContext = true
            ))
        }
    }.let { tasks ->
        if (policy.mode == EpisodeMode.TRANSFER && blindAssessment) {
            tasks.filter { it.kind == EpisodeTaskKind.CONTEXT || it.kind == EpisodeTaskKind.PRODUCTION_PROBE }
        } else tasks
    }.let { fitToBudget(it, policy) }

    private fun fitToBudget(tasks: List<EpisodeTask>, policy: AdaptiveEpisodePolicy): List<EpisodeTask> {
        if (tasks.size <= policy.maxTasks) return tasks
        val selected = linkedSetOf<EpisodeTask>()
        fun include(kind: EpisodeTaskKind) {
            if (selected.size < policy.maxTasks) tasks.firstOrNull { it.kind == kind }?.let(selected::add)
        }
        include(EpisodeTaskKind.CONTEXT)
        if (selected.size < policy.maxTasks) {
            tasks.firstOrNull { it.kind == EpisodeTaskKind.LISTENING || it.kind == EpisodeTaskKind.READING }?.let(selected::add)
        }
        include(EpisodeTaskKind.GUIDED_RESPONSE)
        if (selected.size < policy.maxTasks) {
            tasks.lastOrNull { it.kind == EpisodeTaskKind.TRANSFER || it.kind == EpisodeTaskKind.PRODUCTION_PROBE }
                ?.let(selected::add)
        }
        if (policy.mode == EpisodeMode.REPAIR) {
            include(EpisodeTaskKind.CONTRAST)
            include(EpisodeTaskKind.RETRIEVAL)
        } else include(EpisodeTaskKind.RETRIEVAL)
        include(EpisodeTaskKind.MEDIATION)
        include(EpisodeTaskKind.INFORMATION_GAP)
        include(EpisodeTaskKind.PRAGMATICS)
        tasks.forEach { if (selected.size < policy.maxTasks) selected += it }
        return tasks.filter { it in selected }
    }

    private fun pragmaticsTask(
        register: String,
        intention: String,
        noteId: Long,
        components: List<KnowledgeComponent>
    ): EpisodeTask {
        val (expected, distractor, mismatch) = when (register) {
            "formal" -> Triple(
                "Не могли бы вы уточнить, что именно вы имеете в виду?",
                "Ну и что вы там имели в виду?",
                "The second version is colloquial and confrontational for a formal exchange."
            )
            "informal" -> Triple(
                "Скажи, пожалуйста, что ты имеешь в виду.",
                "Не соблаговолите ли вы разъяснить вышеизложенное?",
                "The second version is comically over-formal for a familiar conversation."
            )
            "polite" -> Triple(
                "Поясните, пожалуйста, эту важную деталь.",
                "Уточните.",
                "The bare imperative is grammatical but unnecessarily blunt here."
            )
            else -> Triple(
                "Поясните, пожалуйста, что вы имеете в виду.",
                "Это же очевидно, разве нет?",
                "The second version sounds sarcastic and does not genuinely request clarification."
            )
        }
        return EpisodeTask(
            id = "pragmatics:$noteId:$register:$intention",
            kind = EpisodeTaskKind.PRAGMATICS,
            instruction = "Both options contain grammatical Russian. Choose the one whose tone fits the $register setting and the intention to $intention.",
            russian = "",
            english = "Clarify the important point while preserving the relationship.",
            expected = expected,
            acceptable = listOf(expected),
            choices = listOf(distractor, expected),
            responseFeedback = mapOf(
                expected to "The wording fits the $register register and accomplishes the social goal.",
                distractor to mismatch
            ),
            componentKeys = components.filter { it.kind == "CONSTRUCTION" }.take(1).map { it.key },
            supportLevel = 1,
            novelContext = true
        )
    }
}

/** Lightweight component scheduler; exercise choice no longer owns interval state. */
object ComponentScheduler {
    private const val DAY_MS = 86_400_000L

    /** Stability is defined as the interval where unsupported recall is ~90%. */
    fun retrievability(component: KnowledgeComponent, at: Long): Double {
        val last = component.lastEvidenceAt ?: return if (component.reps == 0) 0.5 else 0.9
        val elapsedDays = ((at - last).coerceAtLeast(0L)).toDouble() / DAY_MS
        val stability = component.stabilityDays.coerceAtLeast(0.25)
        // FSRS-style power forgetting curve calibrated so R(stability) = 0.9.
        return (1.0 + elapsedDays / (9.0 * stability)).let { 1.0 / it }.coerceIn(0.0, 1.0)
    }

    fun update(component: KnowledgeComponent, success: Boolean, evidenceWeight: Double, at: Long): KnowledgeComponent {
        val weight = evidenceWeight.coerceIn(0.0, 1.0)
        if (weight == 0.0) return component
        val priorStability = component.stabilityDays.coerceAtLeast(0.25)
        val retrievability = retrievability(component, at)
        val stability = if (success) {
            val difficultyScale = (11.0 - component.difficulty.coerceIn(1.0, 10.0)) / 10.0
            // Successful effort near the edge of forgetting produces more durable
            // growth; heavy support proportionally attenuates that benefit.
            priorStability * (1.0 + weight * difficultyScale * (0.18 + 2.8 * (1.0 - retrievability)))
        } else {
            // A strong unsupported miss is highly diagnostic. An assisted miss is
            // weaker evidence and therefore reduces stability less aggressively.
            max(0.25, priorStability * (0.35 + 0.30 * (1.0 - weight)))
        }
        val confidence = if (success) {
            component.confidence + (1.0 - component.confidence) * 0.22 * weight
        } else {
            component.confidence * (1.0 - 0.45 * weight)
        }.coerceIn(0.0, 0.999)
        val intervalDays = if (success) max(1, ceil(stability).toInt()) else 1
        return component.copy(
            due = at + intervalDays * DAY_MS,
            stabilityDays = stability,
            difficulty = (component.difficulty + if (success) -0.12 * weight else 0.65 * weight).coerceIn(1.0, 10.0),
            confidence = confidence,
            reps = component.reps + 1,
            lapses = component.lapses + if (success) 0 else 1,
            lastEvidenceAt = at
        )
    }
}
