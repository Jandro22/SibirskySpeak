package com.sibirskyspeak

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sibirskyspeak.data.CommunicativeEpisode
import com.sibirskyspeak.data.CommunicativeEpisodeRepository
import com.sibirskyspeak.data.EpisodeCompletion
import com.sibirskyspeak.data.EpisodeOverview
import com.sibirskyspeak.data.EpisodeMode
import com.sibirskyspeak.data.EpisodeTask
import com.sibirskyspeak.data.EpisodeTaskKind
import com.sibirskyspeak.data.LearnerDataLifecycle
import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.review.evaluateRussianAnswer
import com.sibirskyspeak.review.evaluateElicitedImitation
import com.sibirskyspeak.review.SPEECH_SELF_CHECK_MARKER
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

data class TutorUiState(
    val showOnboarding: Boolean = false,
    val loading: Boolean = true,
    val overview: EpisodeOverview? = null,
    val episode: CommunicativeEpisode? = null,
    val taskIndex: Int = 0,
    val answer: String = "",
    val checked: Boolean = false,
    val correct: Boolean? = null,
    val feedback: String? = null,
    val resetKey: Int = 0,
    val completed: Boolean = false,
    val transferSuccesses: Int = 0,
    val transferAttempts: Int = 0,
    val repairing: Boolean = false,
    val speechFallback: Boolean = false,
    val saving: Boolean = false,
    val completion: EpisodeCompletion? = null,
    /** Canonical accepted response per task, used to resolve later information gaps. */
    val acceptedAnswers: Map<String, String> = emptyMap(),
    val error: String? = null
) {
    val task: EpisodeTask? get() = episode?.tasks?.getOrNull(taskIndex)?.let { raw ->
        val priorAnswer = raw.dependsOnTaskId?.let(acceptedAnswers::get)
        val resolved = priorAnswer?.let(raw.expectedByAnswer::get)
        when {
            resolved != null -> raw.copy(expected = resolved)
            raw.dependsOnTaskId != null -> raw.copy(
                kind = EpisodeTaskKind.CONTEXT,
                instruction = "No branch-specific detail was established, so continue to the next turn.",
                choices = emptyList(), expected = null
            )
            else -> raw
        }
    }
}

internal object EpisodeSnapshotCodec {
    fun encode(state: TutorUiState): String {
        val episode = state.episode ?: return ""
        return JSONObject().apply {
            put("version", 12)
            put("savedAt", System.currentTimeMillis())
            put("taskIndex", state.taskIndex)
            put("checked", state.checked)
            put("correct", state.correct)
            put("repairing", state.repairing)
            put("speechFallback", state.speechFallback)
            put("transferSuccesses", state.transferSuccesses)
            put("transferAttempts", state.transferAttempts)
            put("acceptedAnswers", JSONObject(state.acceptedAnswers))
            put("episode", JSONObject().apply {
                put("id", episode.id); put("sourceDialogueId", episode.sourceDialogueId ?: JSONObject.NULL)
                put("sourceReaderTextId", episode.sourceReaderTextId ?: JSONObject.NULL)
                put("capabilityKey", episode.capabilityKey); put("band", episode.band)
                put("unit", episode.unit); put("canDo", episode.canDo); put("title", episode.title)
                put("objective", episode.objective); put("informationGap", episode.informationGap)
                put("setting", episode.setting); put("register", episode.register); put("activity", episode.activity)
                put("blindAssessment", episode.blindAssessment)
                put("estimatedMinutes", episode.estimatedMinutes)
                put("mode", episode.mode.name); put("focus", episode.focus)
                put("tasks", JSONArray().apply { episode.tasks.forEach { task -> put(JSONObject().apply {
                    put("id", task.id); put("kind", task.kind.name); put("instruction", task.instruction)
                    put("russian", task.russian); put("english", task.english); put("expected", task.expected)
                    put("repairExpected", task.repairExpected)
                    put("repairEnglish", task.repairEnglish)
                    put("acceptable", JSONArray(task.acceptable))
                    put("responseFeedback", JSONObject(task.responseFeedback))
                    put("dependsOnTaskId", task.dependsOnTaskId)
                    put("expectedByAnswer", JSONObject(task.expectedByAnswer))
                    put("semanticAnchors", JSONArray(task.semanticAnchors))
                    put("constructionCues", JSONArray(task.constructionCues))
                    put("minimumMeaningCoverage", task.minimumMeaningCoverage)
                    put("choices", JSONArray(task.choices)); put("componentKeys", JSONArray(task.componentKeys))
                    put("audioRate", task.audioRate); put("audioPitch", task.audioPitch)
                    put("voiceVariant", task.voiceVariant); put("audioCondition", task.audioCondition)
                    put("supportLevel", task.supportLevel); put("novelContext", task.novelContext)
                }) } })
            })
        }.toString()
    }

    fun decode(payload: String): TutorUiState? = runCatching {
        if (payload.isBlank()) return null
        val root = JSONObject(payload)
        val version = root.optInt("version")
        // Earlier snapshots can contain obsolete dialogue carriers or the old
        // fixed distractor sets. Restart the short episode from current content.
        if (version != 12) return null
        val value = root.getJSONObject("episode")
        val tasksJson = value.getJSONArray("tasks")
        val tasks = (0 until tasksJson.length()).map { index ->
            val task = tasksJson.getJSONObject(index)
            fun strings(name: String): List<String> {
                val array = task.optJSONArray(name) ?: return emptyList()
                return (0 until array.length()).map(array::getString)
            }
            EpisodeTask(
                id = task.getString("id"),
                kind = EpisodeTaskKind.valueOf(task.getString("kind")),
                instruction = task.getString("instruction"),
                russian = task.getString("russian"),
                english = task.optString("english").takeIf { it.isNotBlank() && it != "null" },
                expected = task.optString("expected").takeIf { it.isNotBlank() && it != "null" },
                repairExpected = task.optString("repairExpected").takeIf { it.isNotBlank() && it != "null" },
                repairEnglish = task.optString("repairEnglish").takeIf { it.isNotBlank() && it != "null" },
                acceptable = strings("acceptable"),
                responseFeedback = task.optJSONObject("responseFeedback")?.let { feedback ->
                    feedback.keys().asSequence().associateWith(feedback::getString)
                }.orEmpty(),
                dependsOnTaskId = task.optString("dependsOnTaskId").takeIf { it.isNotBlank() && it != "null" },
                expectedByAnswer = task.optJSONObject("expectedByAnswer")?.let { expectedByAnswer ->
                    expectedByAnswer.keys().asSequence().associateWith(expectedByAnswer::getString)
                }.orEmpty(),
                semanticAnchors = strings("semanticAnchors"),
                constructionCues = strings("constructionCues"),
                minimumMeaningCoverage = task.optDouble("minimumMeaningCoverage", 0.55),
                choices = strings("choices"),
                audioRate = task.optDouble("audioRate", 1.0).toFloat(),
                audioPitch = task.optDouble("audioPitch", 1.0).toFloat(),
                voiceVariant = task.optInt("voiceVariant"),
                audioCondition = task.optString("audioCondition").takeIf { it.isNotBlank() && it != "null" },
                componentKeys = strings("componentKeys"),
                supportLevel = task.optInt("supportLevel"),
                novelContext = task.optBoolean("novelContext")
            )
        }.map { task ->
            if (version <= 6 && task.kind == EpisodeTaskKind.CONTEXT) task.copy(
                instruction = "Listen to the opening. You do not need every word.",
                english = null
            ) else task
        }
        if (tasks.isEmpty()) return null
        val episode = CommunicativeEpisode(
            id = value.getString("id"),
            sourceDialogueId = value.optString("sourceDialogueId").takeIf { it.isNotBlank() && it != "null" },
            sourceReaderTextId = value.optLong("sourceReaderTextId", -1L).takeIf { it >= 0L },
            capabilityKey = value.getString("capabilityKey"), band = value.getString("band"),
            unit = value.getInt("unit"), canDo = value.getString("canDo"), title = value.getString("title"),
            objective = value.optString("objective", value.getString("canDo")),
            informationGap = value.optString("informationGap"),
            setting = value.optString("setting").takeIf { it.isNotBlank() && it != "null" },
            register = value.optString("register", "neutral"),
            activity = value.optString("activity", "interaction"),
            blindAssessment = value.optBoolean("blindAssessment"),
            estimatedMinutes = value.getInt("estimatedMinutes"),
            mode = runCatching { EpisodeMode.valueOf(value.optString("mode", EpisodeMode.ACQUIRE.name)) }.getOrDefault(EpisodeMode.ACQUIRE),
            focus = value.optString("focus", "build usable Russian"),
            tasks = tasks
        )
        val index = root.optInt("taskIndex").coerceIn(0, tasks.lastIndex)
        val correct = if (root.isNull("correct")) null else root.optBoolean("correct")
        val acceptedAnswers = root.optJSONObject("acceptedAnswers")?.let { answers ->
            answers.keys().asSequence().associateWith(answers::getString)
        }.orEmpty()
        TutorUiState(
            loading = false,
            episode = episode,
            taskIndex = index,
            checked = root.optBoolean("checked"),
            correct = correct,
            feedback = if (root.optBoolean("checked")) {
                if (correct == true) "That completes the meaning." else "Study the correction now. It will return after other material."
            } else null,
            repairing = root.optBoolean("repairing"),
            speechFallback = root.optBoolean("speechFallback"),
            transferSuccesses = root.optInt("transferSuccesses"),
            transferAttempts = root.optInt("transferAttempts"),
            acceptedAnswers = acceptedAnswers,
            resetKey = 1
        )
    }.getOrNull()
}

/** Inserts a missed item after intervening material; immediate copying is not durable retrieval. */
internal object DelayedRepairPlanner {
    fun schedule(episode: CommunicativeEpisode, taskIndex: Int, task: EpisodeTask): CommunicativeEpisode {
        if (task.kind == EpisodeTaskKind.REPAIR || episode.tasks.any { it.id == "${task.id}:repair" }) return episode
        val repair = task.copy(
            id = "${task.id}:repair",
            kind = EpisodeTaskKind.REPAIR,
            instruction = "Try the missed idea again after the gap. Rebuild it without looking back.",
            expected = task.repairExpected ?: task.expected,
            english = task.repairEnglish ?: task.english,
            acceptable = task.repairExpected?.let(::listOf) ?: task.acceptable,
            choices = emptyList(),
            novelContext = task.repairExpected != null
        )
        val tasks = episode.tasks.toMutableList()
        tasks.add((taskIndex + 3).coerceAtMost(tasks.size), repair)
        return episode.copy(tasks = tasks)
    }
}

/** Tile construction cannot produce a keyboard typo, so an ending mismatch is real evidence. */
private fun acceptedTutorCandidate(task: EpisodeTask, answer: String, speechFallback: Boolean): String? {
    val candidates = task.acceptable.ifEmpty { listOfNotNull(task.expected) }
    if (task.choices.isNotEmpty() && answer in candidates) return answer
    return candidates.firstOrNull { candidate ->
        if (task.kind == EpisodeTaskKind.PRODUCTION_PROBE && !speechFallback) {
            answer != SPEECH_SELF_CHECK_MARKER && (
                evaluateElicitedImitation(candidate, answer).accepted || communicativeRubricAccepts(task, candidate, answer)
            )
        } else evaluateRussianAnswer(candidate, answer, allowTypos = false).accepted || communicativeRubricAccepts(task, candidate, answer)
    }
}

private fun communicativeRubricAccepts(task: EpisodeTask, candidate: String, answer: String): Boolean {
    if (task.semanticAnchors.isEmpty()) return false
    fun tokens(value: String): Set<String> = Regex("[\\p{L}\\p{N}-]+")
        .findAll(value).map { com.sibirskyspeak.data.RussianForms.normalize(it.value) }
        .filter(String::isNotBlank).toSet()
    val answerTokens = tokens(answer)
    if (answerTokens.isEmpty()) return false
    val anchorHit = task.semanticAnchors.any { anchor ->
        val normalized = com.sibirskyspeak.data.RussianForms.normalize(anchor)
        normalized in answerTokens
    }
    if (!anchorHit) return false
    if (!task.constructionCues.all { com.sibirskyspeak.data.RussianForms.normalize(it) in answerTokens }) return false
    val reference = tokens(candidate)
    if (reference.isEmpty()) return false
    val coverage = answerTokens.intersect(reference).size.toDouble() / reference.size
    return coverage >= task.minimumMeaningCoverage
}

internal fun evaluateTutorResponse(task: EpisodeTask, answer: String, speechFallback: Boolean = false): Boolean =
    acceptedTutorCandidate(task, answer, speechFallback) != null

internal fun observedTutorTask(task: EpisodeTask, repairing: Boolean, speechFallback: Boolean): EpisodeTask = when {
    repairing && task.kind != EpisodeTaskKind.REPAIR -> task.copy(
        id = "${task.id}:repair", kind = EpisodeTaskKind.REPAIR, supportLevel = 3, novelContext = false
    )
    speechFallback && task.kind == EpisodeTaskKind.PRODUCTION_PROBE -> task.copy(
        id = "${task.id}:assisted", kind = EpisodeTaskKind.TRANSFER, supportLevel = 2
    )
    else -> task
}

@HiltViewModel
class TutorViewModel @Inject constructor(
    private val repository: CommunicativeEpisodeRepository,
    private val learnerData: LearnerDataLifecycle,
    private val settings: SettingsStore
) : ViewModel() {
    private val mutableState = MutableStateFlow(TutorUiState(showOnboarding = !settings.onboardingCompleted))
    val state: StateFlow<TutorUiState> = mutableState.asStateFlow()
    private var taskShownAt = System.currentTimeMillis()

    private var bootstrapped = false

    init { refreshOverview() }

    fun refreshOverview() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            val result = runCatching {
                if (!bootstrapped) {
                    learnerData.initialize()
                    bootstrapped = true
                }
                val snapshot = settings.episodeSnapshotJson
                EpisodeSnapshotCodec.decode(snapshot)?.let { restored ->
                    val savedAt = runCatching { JSONObject(snapshot).optLong("savedAt").takeIf { it > 0L } }.getOrNull()
                    repository.recordEpisodeResumed(
                        restored.episode!!,
                        restored.taskIndex,
                        restored.checked,
                        savedAt
                    )
                    mutableState.value = restored.copy(showOnboarding = !settings.onboardingCompleted)
                    return@launch
                }
                if (snapshot.isNotBlank()) {
                    repository.recordIncompatibleEpisodeSnapshot(snapshot)
                    settings.episodeSnapshotJson = ""
                }
                repository.nextOverview()
            }
            result.onSuccess { mutableState.value = TutorUiState(loading = false, overview = it, showOnboarding = !settings.onboardingCompleted) }
                .onFailure { mutableState.value = TutorUiState(loading = false, error = "Could not prepare the next episode.", showOnboarding = !settings.onboardingCompleted) }
        }
    }

    /** Tools can change global word status while this ViewModel retains an active
     * episode. Reconcile before revealing it again so "Known" takes effect now,
     * rather than only after the stale episode snapshot is completed. */
    fun syncAfterTools() {
        val state = mutableState.value
        val episode = state.episode
        if (episode == null || state.completed || state.saving) {
            if (episode == null) refreshOverview()
            return
        }
        val firstPending = state.taskIndex + if (state.checked) 1 else 0
        viewModelScope.launch {
            runCatching { repository.reconcileRetiredComponents(episode, firstPending) }
                .onSuccess { reconciled ->
                    if (reconciled.tasks == episode.tasks) return@onSuccess
                    if (firstPending >= reconciled.tasks.size) {
                        settings.episodeSnapshotJson = ""
                        mutableState.value = TutorUiState(
                            loading = false,
                            overview = state.overview,
                            showOnboarding = !settings.onboardingCompleted
                        )
                        refreshOverview()
                    } else {
                        val next = state.copy(
                            episode = reconciled,
                            taskIndex = firstPending,
                            answer = "",
                            checked = false,
                            correct = null,
                            feedback = null,
                            repairing = false,
                            speechFallback = false,
                            resetKey = state.resetKey + 1
                        )
                        mutableState.value = next
                        persistEpisode(next)
                    }
                }
                .onFailure { mutableState.value = state.copy(error = "Could not refresh this episode after the word-status change.") }
        }
    }

    private fun persistEpisode(state: TutorUiState) {
        settings.episodeSnapshotJson = EpisodeSnapshotCodec.encode(state)
    }

    fun startEpisode() {
        if (mutableState.value.loading || mutableState.value.episode != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            runCatching { repository.buildNextEpisode() }
                .onSuccess {
                    taskShownAt = System.currentTimeMillis()
                    val next = TutorUiState(loading = false, overview = mutableState.value.overview, episode = it)
                    mutableState.value = next
                    persistEpisode(next)
                }
                .onFailure { mutableState.value = mutableState.value.copy(loading = false, error = "Could not start the episode.") }
        }
    }

    fun setAnswer(answer: String) {
        val state = mutableState.value
        if (state.checked || state.saving) return
        mutableState.value = state.copy(answer = answer)
    }

    fun useSpeechFallback() {
        val state = mutableState.value
        if (state.checked || state.saving || state.task?.kind != EpisodeTaskKind.PRODUCTION_PROBE) return
        val next = state.copy(answer = "", speechFallback = true, resetKey = state.resetKey + 1)
        mutableState.value = next
        persistEpisode(next)
    }

    fun completeOnboarding() {
        settings.onboardingCompleted = true
        mutableState.value = mutableState.value.copy(showOnboarding = false)
    }

    fun clearError() { mutableState.value = mutableState.value.copy(error = null) }

    fun checkAnswer() {
        val state = mutableState.value
        val task = state.task ?: return
        if (task.expected.isNullOrBlank()) return
        if (state.checked || state.answer.isBlank() || state.saving) return
        val accepted = evaluateTutorResponse(task, state.answer, state.speechFallback)
        val canonicalAnswer = acceptedTutorCandidate(task, state.answer, state.speechFallback)
        val transferAttempt = task.kind in setOf(EpisodeTaskKind.TRANSFER, EpisodeTaskKind.PRODUCTION_PROBE) && !state.repairing
        val observedTask = observedTutorTask(task, state.repairing, state.speechFallback)
        mutableState.value = state.copy(saving = true)
        viewModelScope.launch {
            runCatching { repository.recordTask(state.episode!!, observedTask, accepted, System.currentTimeMillis() - taskShownAt) }
                .onSuccess {
                    val updatedEpisode = if (accepted) state.episode else {
                        DelayedRepairPlanner.schedule(state.episode!!, state.taskIndex, observedTask)
                    }
                    val consequence = task.responseFeedback[state.answer]
                        ?: acceptedTutorCandidate(task, state.answer, state.speechFallback)?.let(task.responseFeedback::get)
                    val next = state.copy(
                        episode = updatedEpisode,
                        checked = true,
                        correct = accepted,
                        feedback = if (accepted) consequence ?: "That completes the meaning."
                            else consequence ?: "Study the correction now. It will return after other material.",
                        saving = false,
                        transferAttempts = state.transferAttempts + if (transferAttempt) 1 else 0,
                        transferSuccesses = state.transferSuccesses + if (transferAttempt && accepted) 1 else 0,
                        acceptedAnswers = if (canonicalAnswer != null) state.acceptedAnswers + (task.id to canonicalAnswer) else state.acceptedAnswers
                    )
                    mutableState.value = next
                    persistEpisode(next)
                }
                .onFailure { mutableState.value = state.copy(saving = false, error = "Could not save this response. Try again.") }
        }
    }

    fun continueTask() {
        val state = mutableState.value
        if (state.saving) return
        val episode = state.episode ?: return
        val task = state.task ?: return
        val requiresCheckedResponse = task.choices.isNotEmpty() ||
            task.kind == EpisodeTaskKind.GUIDED_RESPONSE ||
            task.kind == EpisodeTaskKind.MEDIATION ||
            task.kind == EpisodeTaskKind.TRANSFER ||
            task.kind == EpisodeTaskKind.PRODUCTION_PROBE ||
            task.kind == EpisodeTaskKind.REPAIR
        if (requiresCheckedResponse && task.expected != null && !state.checked) return
        mutableState.value = state.copy(saving = true)
        viewModelScope.launch {
            runCatching {
                var completion: EpisodeCompletion? = null
                if (task.kind == EpisodeTaskKind.CONTEXT || task.kind == EpisodeTaskKind.NOTICE) {
                    repository.recordTask(episode, task, success = true, responseMs = System.currentTimeMillis() - taskShownAt)
                }
                if (state.taskIndex >= episode.tasks.lastIndex) {
                    completion = repository.finishEpisode(episode, state.transferSuccesses, state.transferAttempts)
                }
                completion
            }.onSuccess { completion ->
                if (state.taskIndex >= episode.tasks.lastIndex) {
                    mutableState.value = state.copy(completed = true, saving = false, completion = completion)
                    settings.episodeSnapshotJson = ""
                    learnerData.requestCheckpoint()
                } else {
                    taskShownAt = System.currentTimeMillis()
                    val next = state.copy(
                        taskIndex = state.taskIndex + 1,
                        answer = "",
                        checked = false,
                        correct = null,
                        feedback = null,
                        resetKey = state.resetKey + 1,
                        repairing = false,
                        speechFallback = false,
                        saving = false
                    )
                    mutableState.value = next
                    persistEpisode(next)
                }
            }.onFailure { mutableState.value = state.copy(saving = false, error = "Could not save your place. Try again.") }
        }
    }

    fun finishSummary() { refreshOverview() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TutorScreen(
    viewModel: TutorViewModel,
    onOpenTools: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tts = rememberRussianTts()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SibirskySpeak", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenTools, modifier = Modifier.testTag(TestTags.TUTOR_OPEN_TOOLS)) {
                        Icon(Icons.Filled.Build, contentDescription = "Open practice, reader, and settings")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                state.showOnboarding -> TutorOnboarding(viewModel::completeOnboarding)
                state.loading -> CircularProgressIndicator()
                state.error != null -> ErrorEpisode(
                    state.error!!,
                    if (state.episode == null) viewModel::refreshOverview else viewModel::clearError
                )
                state.completed -> EpisodeSummary(state, viewModel::finishSummary)
                state.episode == null -> ContinueSurface(state.overview, viewModel::startEpisode, onOpenTools)
                else -> EpisodeSurface(
                    state = state,
                    onAnswer = viewModel::setAnswer,
                    onCheck = viewModel::checkAnswer,
                    onContinue = viewModel::continueTask,
                    onSpeechFallback = viewModel::useSpeechFallback,
                    onSpeak = { text, rate, pitch, voice, condition -> tts.speak(text, rate, pitch, voice, condition) }
                )
            }
        }
    }
}

@Composable
private fun TutorOnboarding(onStart: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Learn Russian by using it", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Each short episode begins with a real situation, teaches only what you need, and ends with a response in context.")
        Text("Episodes are the active core. Extensive reading, listening, writing, and real conversation remain part of the route to C1.")
        Text("Assisted Russian input is available by default, so your phone keyboard never becomes the lesson.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().testTag(TestTags.TUTOR_ONBOARDING_START)) { Text("Start with the first capability") }
    }
}

@Composable
private fun ContinueSurface(overview: EpisodeOverview?, onContinue: () -> Unit, onOpenTools: () -> Unit) {
    val mode = overview?.mode ?: EpisodeMode.ACQUIRE
    val stage = mode.learnerStage()
    val completed = overview?.completedEpisodes ?: 0
    val foundationProgress = (completed.coerceAtMost(3) / 3f).coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("YOUR CURRICULUM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(
                    overview?.band ?: "A1",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text("Unit ${overview?.unit ?: 1}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text("Your next step", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Text(stage.title, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
                    }
                    Text("${overview?.estimatedMinutes ?: 4} min", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
                Text("CAN-DO GOAL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(overview?.canDo ?: "Use Russian in a practical situation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stage.explanation, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("WHY THIS NOW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text((overview?.focus ?: "Build usable Russian").replaceFirstChar { it.uppercase() } + ".")
                    }
                }
                AppLinearProgressIndicator(
                    progress = { foundationProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    when {
                        completed < 3 -> "$completed of 3 foundation episodes completed"
                        mode == EpisodeMode.TRANSFER -> "Foundation complete · now proving independent use"
                        else -> "Foundation complete · strengthening memory before advancing"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                overview?.transferAccuracy?.let { accuracy ->
                    Text("Latest independent use: ${(accuracy * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag(TestTags.TUTOR_CONTINUE)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Start ${stage.action} · ${overview?.estimatedMinutes ?: 4} min", fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("Learn → recall → strengthen → use independently", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("The tutor keeps you on this can-do goal until your meaning, listening, and speaking evidence is ready to carry forward.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onOpenTools, modifier = Modifier.fillMaxWidth()) { Text("Reader, extra practice & settings") }
    }
}

private data class LearnerStage(val title: String, val action: String, val explanation: String)

private fun EpisodeMode.learnerStage(): LearnerStage = when (this) {
    EpisodeMode.ACQUIRE -> LearnerStage("LEARN", "learning", "Meet only the language needed for this situation, then use it immediately.")
    EpisodeMode.RETRIEVE -> LearnerStage("RECALL", "recall", "Bring useful Russian back before it fades, inside the same practical goal.")
    EpisodeMode.REPAIR -> LearnerStage("STRENGTHEN", "strengthening", "Repair the exact meaning, sound, or form that has been fragile.")
    EpisodeMode.TRANSFER -> LearnerStage("USE", "independent use", "Use familiar Russian in a new situation with less support.")
}

@Composable
internal fun EpisodeSurface(
    state: TutorUiState,
    onAnswer: (String) -> Unit,
    onCheck: () -> Unit,
    onContinue: () -> Unit,
    onSpeechFallback: () -> Unit,
    onSpeak: (String, Float, Float, Int, String?) -> Unit
) {
    val episode = state.episode ?: return
    val task = state.task ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                episode.mode.learnerStage().title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Step ${state.taskIndex + 1} of ${episode.tasks.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AppLinearProgressIndicator(
            progress = { (state.taskIndex + if (state.checked) 1 else 0).toFloat() / episode.tasks.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(episode.canDo.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (state.taskIndex == 0) {
            val contextDetails = buildList {
                episode.setting?.let { add("$it · ${episode.activity} · ${episode.register} register") }
                if (!episode.objective.equals(episode.canDo, ignoreCase = true)) add("Objective: ${episode.objective}")
                if (episode.informationGap.isNotBlank()) add("Information gap: ${episode.informationGap}")
                add("Why now: ${episode.focus}")
            }
            if (contextDetails.isNotEmpty()) Text(contextDetails.joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
        }
        if (episode.blindAssessment) Text("BLIND TRANSFER · unseen wording · no answer cues", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(task.instruction, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (task.russian.isNotBlank() && task.kind != EpisodeTaskKind.LISTENING) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(task.russian, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    task.english?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    OutlinedButton(onClick = { onSpeak(task.russian, task.audioRate, task.audioPitch, task.voiceVariant, task.audioCondition) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Listen")
                    }
                }
            }
        } else task.english?.let { cue ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("MEANING TO EXPRESS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(cue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (task.kind == EpisodeTaskKind.LISTENING) {
            task.audioCondition?.let { condition ->
                Text("Listening condition: $condition", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { onSpeak(task.russian, task.audioRate, task.audioPitch, task.voiceVariant, task.audioCondition) }, modifier = Modifier.fillMaxWidth().testTag(TestTags.TUTOR_LISTEN)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Play Russian")
            }
        }
        if (task.kind == EpisodeTaskKind.PRODUCTION_PROBE && !state.speechFallback) {
            if (!state.checked) {
                Text(
                    "This optional check uses Android's on-device Russian recognizer. Audio and transcripts are not stored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SpeakingAnswerInput(
                    cardId = (episode.id + task.id).hashCode().toLong(),
                    expectedAnswer = task.expected.orEmpty(),
                    recognized = state.answer,
                    onRecognized = onAnswer,
                    onRecognition = { _, _ -> },
                    onRecognitionStatus = {},
                    allowSelfCheck = false
                )
                OutlinedButton(
                    onClick = onSpeechFallback,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.TUTOR_SPEECH_FALLBACK)
                ) { Text("Use supported tiles instead") }
                Button(
                    onClick = onCheck,
                    enabled = state.answer.isNotBlank() && !state.saving,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.TUTOR_CHECK)
                ) { Text("Check speech") }
            } else {
                Surface(
                    color = if (state.correct == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (state.correct == true) "Independent speaking evidence recorded."
                            else "The recognizer did not match enough of the response. You will retry with support later.",
                            fontWeight = FontWeight.SemiBold
                        )
                        if (state.correct == false) Text(task.expected.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Button(onClick = onContinue, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag(TestTags.TUTOR_NEXT)) {
                    Text(if (state.taskIndex == episode.tasks.lastIndex) "Finish episode" else "Continue")
                }
            }
        } else if (task.choices.isNotEmpty()) {
            task.choices.forEachIndexed { index, choice ->
                val selected = state.answer == choice
                val modifier = Modifier.fillMaxWidth().testTag("${TestTags.TUTOR_CHOICE_PREFIX}_$index")
                if (selected) {
                    Button(onClick = { onAnswer(choice) }, enabled = !state.checked && !state.saving, modifier = modifier) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Selected answer")
                        Spacer(Modifier.size(6.dp))
                        Text(choice)
                    }
                } else {
                    OutlinedButton(onClick = { onAnswer(choice) }, enabled = !state.checked && !state.saving, modifier = modifier) {
                        Text(choice)
                    }
                }
            }
            if (state.checked) {
                if (task.kind == EpisodeTaskKind.LISTENING) {
                    Text("You heard: ${task.russian}", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (state.correct == true) state.feedback ?: "Communicative goal achieved."
                    else listOfNotNull(state.feedback, task.expected?.let { "One valid response is: $it" }).joinToString("\n"),
                    color = if (state.correct == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            when {
                !state.checked -> Button(
                    onClick = onCheck,
                    enabled = state.answer.isNotBlank() && !state.saving,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.TUTOR_CHECK)
                ) { Text("Check meaning") }
                else -> Button(onClick = onContinue, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag(TestTags.TUTOR_NEXT)) {
                    Text(if (state.taskIndex == episode.tasks.lastIndex) "Finish episode" else "Continue")
                }
            }
        } else if (task.expected != null && task.kind !in setOf(EpisodeTaskKind.NOTICE)) {
            LetterTileBank(
                expected = task.expected,
                cardId = (episode.id + task.id).hashCode().toLong(),
                hint = "Build the Russian response. This is supported practice, not a keyboard test.",
                onChange = onAnswer,
                resetKey = state.resetKey,
                enabled = !state.checked && !state.saving
            )
            if (state.checked) {
                Surface(
                    color = if (state.correct == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.feedback.orEmpty(), fontWeight = FontWeight.SemiBold)
                        if (state.correct == false) Text(task.expected, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            when {
                !state.checked -> Button(onClick = onCheck, enabled = state.answer.isNotBlank() && !state.saving, modifier = Modifier.fillMaxWidth().testTag(TestTags.TUTOR_CHECK)) { Text("Check response") }
                state.correct == false -> Button(onClick = onContinue, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag(TestTags.TUTOR_NEXT)) { Text("Continue; retry later") }
                else -> Button(onClick = onContinue, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag(TestTags.TUTOR_NEXT)) { Text(if (state.taskIndex == episode.tasks.lastIndex) "Finish episode" else "Continue") }
            }
        } else {
            Button(onClick = onContinue, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag(TestTags.TUTOR_NEXT)) {
                Text(if (state.taskIndex == episode.tasks.lastIndex) "Finish episode" else "Continue")
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun EpisodeSummary(state: TutorUiState, onDone: () -> Unit) {
    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
        Text("Episode complete", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(state.episode?.canDo.orEmpty(), textAlign = TextAlign.Center)
        Text("Memory focus: ${state.episode?.focus.orEmpty()}", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val attempts = state.transferAttempts
        Text(if (attempts == 0) "You built context for the next episode." else "Transfer evidence: ${state.transferSuccesses}/$attempts")
        state.completion?.let { completion ->
            Text(
                when {
                    completion.certified -> "Capability verified across repeated unsupported speaking probes."
                    completion.routeReady -> "This capability is ready to carry forward; future speech probes can verify it."
                    else -> "Coverage: ${completion.observedNotes}/${completion.totalNotes} useful items observed."
                },
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun ErrorEpisode(message: String, onRetry: () -> Unit) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message)
        Button(onClick = onRetry) { Text("Try again") }
    }
}
