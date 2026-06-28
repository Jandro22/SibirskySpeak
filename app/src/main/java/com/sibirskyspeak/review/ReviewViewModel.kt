package com.sibirskyspeak.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sibirskyspeak.data.Achievement
import com.sibirskyspeak.data.DashboardStats
import com.sibirskyspeak.data.DailyPlan
import com.sibirskyspeak.data.LearningRepository
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReaderRecommendation
import com.sibirskyspeak.data.ReaderToken
import com.sibirskyspeak.data.SessionPlan
import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.data.TelemetryEvent
import com.sibirskyspeak.data.WordStatus
import com.sibirskyspeak.scheduler.FsrsScheduler
import com.sibirskyspeak.scheduler.FsrsWeightFitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import kotlin.math.pow

/** Minimum gap between automatic full-state backups (~once per active day). */
private const val BACKUP_INTERVAL_MS = 20L * 60 * 60 * 1000

/** Mature-review sample needed before the FSRS interval modifier starts adapting. */
private const val MIN_OPTIMIZE_SAMPLE = 100

enum class SessionStep {
    REVIEWS,
    RULE,
    BLOCKED,
    INTERLEAVED,
    READER,
    IMPORT,
    DASHBOARD
}

data class ReviewUiState(
    val prompt: ReviewPrompt? = null,
    val typedAnswer: String = "",
    val revealed: Boolean = false,
    val isAnswerCorrect: Boolean? = null,
    val answerMatch: AnswerMatch? = null,
    val answerFeedback: String? = null,
    val reviewedToday: Int = 0,
    val dailyPlan: DailyPlan? = null,
    val sessionPlan: SessionPlan? = null,
    val readerRecommendation: ReaderRecommendation? = null,
    val allReaderTexts: List<ReaderRecommendation> = emptyList(),
    val readerTokens: List<ReaderToken> = emptyList(),
    val selectedToken: ReaderToken? = null,
    val dashboardStats: DashboardStats? = null,
    val lookupResult: String? = null,
    val importText: String = "",
    val exportText: String = "",
    val readerTitle: String = "",
    val readerBody: String = "",
    val selectedReaderTextId: Long? = null,
    val readerProgressByText: Map<Long, Int> = emptyMap(),
    val readerLookupInProgress: Boolean = false,
    val statusMessage: String? = null,
    val sessionStep: SessionStep = SessionStep.REVIEWS,
    val ratingInProgress: Boolean = false,
    val autoRatedAgain: Boolean = false,
    val suggestedRating: Rating? = null,
    val correctionRequired: Boolean = false,
    val correctionAnswer: String = "",
    val correctionAccepted: Boolean = false,
    val fatigueAdjusted: Boolean = false,
    val feedbackSequence: Int = 0,
    val feedbackCorrect: Boolean? = null,
    val readerCheckpointQuestions: List<ReaderCheckpointQuestion> = emptyList(),
    val readerCheckpointIndex: Int = 0,
    val readerCheckpointFeedback: String? = null,
    val inSessionReading: Boolean = false,
    val readerCheckpointMistakes: Int = 0,
    val inStudySession: Boolean = false,
    val canUndo: Boolean = false,
    // Settings mirror (persisted in SettingsStore; surfaced for the Settings UI).
    val dailyGoalSetting: Int = SettingsStore.DEFAULT_DAILY_GOAL,
    val sessionSizeSetting: Int = SettingsStore.DEFAULT_SESSION_SIZE,
    val newCardsPerDaySetting: Int = SettingsStore.DEFAULT_NEW_CARDS_PER_DAY,
    val retentionSetting: Double = SettingsStore.DEFAULT_RETENTION,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = SettingsStore.DEFAULT_REMINDER_HOUR,
    val readerFontScale: Float = 1.0f,
    // Deck search (Settings/Import area).
    val searchQuery: String = "",
    val searchResults: List<Note> = emptyList(),
    // Grammar reference overlay search — kept separate from deck search above so
    // opening the reference panel never clobbers (or is clobbered by) an in-progress
    // deck search, since both used to share one query/results pair.
    val referenceQuery: String = "",
    val referenceResults: List<Note> = emptyList(),
    // Furthest token the user has reached in the open reader text; -1 means not started.
    val readerProgressIndex: Int = -1,
    // Achievements unlocked since the user last looked (for the celebratory toast).
    val newlyUnlocked: List<Achievement> = emptyList(),
    // Per-sitting counters (reset when the study screen is opened) that drive the
    // in-session progress line and the end-of-session summary.
    val sessionReviewed: Int = 0,
    val sessionCorrect: Int = 0,
    val sessionCompletedCards: Int = 0,
    // Parked leeches available to fix or release, for the Leeches management view.
    val leeches: List<LeechItem> = emptyList()
)

data class ReaderCheckpointQuestion(
    val russian: String,
    val expected: String,
    val choices: List<String>,
    val noteId: Long? = null
)

/** A parked leech surfaced to the learner: the card plus its word and gloss. */
data class LeechItem(
    val card: com.sibirskyspeak.data.Card,
    val note: Note,
    val russian: String,
    val translation: String,
    val lapses: Int,
    val cardLabel: String,
    val promptPreview: String,
    val expectedAnswer: String
)

private data class SessionCounterDelta(val reviewed: Int, val correct: Int)

class ReviewViewModel(
    private val repository: LearningRepository,
    private val settings: SettingsStore
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()
    private val sessionCounterDeltas = ArrayDeque<SessionCounterDelta>()
    private val activeStudyQueue = mutableListOf<ReviewPrompt>()
    private var studySessionActive = false
    private var telemetrySessionId: String? = null
    private var promptShownAt: Long = System.currentTimeMillis()
    private var answerRevealedAt: Long = 0L
    private val failureCounts = mutableMapOf<Long, Int>()
    private val acquisitionSuccesses = mutableMapOf<Long, Int>()
    private val responseSamples = mutableListOf<Pair<Long, Boolean>>()
    private var fatigueAdjusted = false
    private var scheduledReadingPresented = false
    private var readingCommitInProgress = false
    private var queueBeforeLastReview: List<ReviewPrompt>? = null

    init {
        viewModelScope.launch {
            // Never let a startup error (bad import, transient DB issue) leave the
            // app stuck on a blank screen with no feedback.
            runCatching {
                repository.seedIfEmpty()
                repository.syncBootstrapReaderTexts()
                loadSession()
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    statusMessage = "Couldn't load your session: ${error.message ?: "unknown error"}"
                )
            }
            maybeBackup()
        }
    }

    /** Write a full-state backup at most once per day, on a background dispatcher. */
    private fun maybeBackup() {
        val now = System.currentTimeMillis()
        if (now - settings.lastBackupAt < BACKUP_INTERVAL_MS) return
        viewModelScope.launch {
            runCatching { if (repository.backupNow()) settings.lastBackupAt = now }
        }
    }

    // --- Settings -----------------------------------------------------------

    fun setDailyGoal(value: Int) {
        settings.dailyGoal = value
        mutableState.value = mutableState.value.copy(dailyGoalSetting = settings.dailyGoal)
        viewModelScope.launch { loadSession() }
    }

    fun setSessionSize(value: Int) {
        settings.sessionSize = value
        mutableState.value = mutableState.value.copy(sessionSizeSetting = settings.sessionSize)
        viewModelScope.launch { loadSession() }
    }

    fun setNewCardsPerDay(value: Int) {
        settings.newCardsPerDay = value
        mutableState.value = mutableState.value.copy(newCardsPerDaySetting = settings.newCardsPerDay)
        viewModelScope.launch { loadSession() }
    }

    fun setRetention(value: Double) {
        settings.desiredRetention = value
        mutableState.value = mutableState.value.copy(retentionSetting = settings.desiredRetention)
    }

    fun setReminderEnabled(value: Boolean) {
        settings.reminderEnabled = value
        mutableState.value = mutableState.value.copy(reminderEnabled = value)
    }

    fun setReminderHour(value: Int) {
        settings.reminderHour = value
        mutableState.value = mutableState.value.copy(reminderHour = settings.reminderHour)
    }

    fun setReaderFontScale(value: Float) {
        settings.readerFontScale = value
        mutableState.value = mutableState.value.copy(readerFontScale = settings.readerFontScale)
    }

    fun dismissNewlyUnlocked() {
        mutableState.value = mutableState.value.copy(newlyUnlocked = emptyList())
    }

    fun dismissStatusMessage() {
        mutableState.value = mutableState.value.copy(statusMessage = null)
    }

    // --- Deck search --------------------------------------------------------

    fun setSearchQuery(value: String) {
        mutableState.value = mutableState.value.copy(searchQuery = value)
        viewModelScope.launch {
            val results = repository.searchNotes(value)
            // Guard against out-of-order responses: only apply if query still current.
            if (mutableState.value.searchQuery == value) {
                mutableState.value = mutableState.value.copy(searchResults = results)
            }
        }
    }

    // --- Grammar reference search -------------------------------------------

    fun setReferenceQuery(value: String) {
        mutableState.value = mutableState.value.copy(referenceQuery = value)
        viewModelScope.launch {
            val results = repository.searchNotes(value)
            if (mutableState.value.referenceQuery == value) {
                mutableState.value = mutableState.value.copy(referenceResults = results)
            }
        }
    }

    fun setSessionStep(step: SessionStep) {
        mutableState.value = mutableState.value.copy(sessionStep = step, prompt = promptForStep(step, mutableState.value.sessionPlan))
    }

    fun setTypedAnswer(value: String) {
        mutableState.value = mutableState.value.copy(typedAnswer = value)
    }

    fun setCorrectionAnswer(value: String) {
        mutableState.value = mutableState.value.copy(correctionAnswer = value, answerFeedback = null)
    }

    fun submitCorrection() {
        val state = mutableState.value
        val prompt = state.prompt ?: return
        if (!state.correctionRequired || state.correctionAccepted) return
        val evaluation = when (prompt.answerMode) {
            AnswerMode.ENGLISH -> evaluateEnglishAnswer(prompt.expectedAnswer, state.correctionAnswer)
            AnswerMode.RUSSIAN_STRESS_TYPED -> evaluateRussianAnswer(prompt.expectedAnswer, state.correctionAnswer, ignoreStress = false)
            else -> evaluateRussianAnswer(prompt.expectedAnswer, state.correctionAnswer)
        }
        mutableState.value = state.copy(
            correctionAccepted = evaluation.accepted,
            answerFeedback = if (evaluation.accepted) "Corrected. Retrieve it again when it returns." else "Not yet — rebuild the expected answer.",
            feedbackSequence = state.feedbackSequence + 1,
            feedbackCorrect = evaluation.accepted
        )
        viewModelScope.launch {
            repository.recordTelemetry(telemetryForPrompt("active_correction", prompt).copy(
                answerMatch = evaluation.match.name,
                typedLength = state.correctionAnswer.length,
                metadataJson = JSONObject().put("accepted", evaluation.accepted).toString()
            ))
        }
    }

    fun chooseAnswer(value: String) {
        mutableState.value = mutableState.value.copy(typedAnswer = value)
        reveal()
    }

    fun reveal() {
        val prompt = mutableState.value.prompt ?: return
        answerRevealedAt = System.currentTimeMillis()
        val evaluation = when (prompt.answerMode) {
            AnswerMode.ENGLISH -> {
                evaluateEnglishAnswer(prompt.expectedAnswer, mutableState.value.typedAnswer)
            }
            AnswerMode.RUSSIAN_TYPED, AnswerMode.AUDIO_ONLY, AnswerMode.SPEAK -> evaluateRussianAnswer(prompt.expectedAnswer, mutableState.value.typedAnswer)
            AnswerMode.RUSSIAN_STRESS_TYPED -> evaluateRussianAnswer(prompt.expectedAnswer, mutableState.value.typedAnswer, ignoreStress = false)
            AnswerMode.CHOICE -> {
                if (prompt.card.cardType == com.sibirskyspeak.data.CardType.STRESS_MARK) {
                    evaluateRussianAnswer(prompt.expectedAnswer, mutableState.value.typedAnswer, ignoreStress = false)
                } else {
                    val correct = mutableState.value.typedAnswer.trim().equals(prompt.expectedAnswer.trim(), ignoreCase = true)
                    AnswerEvaluation(if (correct) AnswerMatch.EXACT else AnswerMatch.WRONG, prompt.expectedAnswer)
                }
            }
            // A lesson has nothing to grade; "Got it" rates it directly via rate().
            AnswerMode.LESSON -> AnswerEvaluation(AnswerMatch.EXACT, prompt.expectedAnswer)
        }
        mutableState.value = mutableState.value.copy(
            revealed = true,
            isAnswerCorrect = evaluation.accepted,
            answerMatch = evaluation.match,
            answerFeedback = if (evaluation.accepted) evaluation.message else diagnosticFeedbackFor(prompt, mutableState.value.typedAnswer) ?: evaluation.message,
            suggestedRating = suggestedRating(evaluation, prompt, System.currentTimeMillis() - promptShownAt),
            feedbackSequence = mutableState.value.feedbackSequence + 1,
            feedbackCorrect = evaluation.accepted
        )
        viewModelScope.launch {
            repository.recordTelemetry(telemetryForPrompt("answer_revealed", prompt).copy(
                answerMatch = evaluation.match.name,
                responseMs = (System.currentTimeMillis() - promptShownAt).coerceAtLeast(0),
                wasRevealed = true,
                typedLength = mutableState.value.typedAnswer.length
            ))
        }
        // A committed miss is auto-graded AGAIN (honest scheduling); a receptive
        // recognition prompt instead reveals and lets the learner self-grade, so a
        // typo or valid synonym never silently becomes an FSRS lapse. Production
        // (typed Russian) is committed. So is any multiple-CHOICE answer: tapping a
        // wrong gender/aspect/stress option IS a commitment and must count as a miss,
        // otherwise grammar drills could be self-graded "Good" after a wrong tap.
        val committedMiss = prompt.answerMode == AnswerMode.CHOICE ||
            prompt.card.cardType in setOf(
                com.sibirskyspeak.data.CardType.MEANING_TO_RU,
                com.sibirskyspeak.data.CardType.CLOZE,
                com.sibirskyspeak.data.CardType.CASE_FILL,
                com.sibirskyspeak.data.CardType.VERB_FORM,
                com.sibirskyspeak.data.CardType.DICTATION,
                com.sibirskyspeak.data.CardType.SENTENCE_BUILD,
                com.sibirskyspeak.data.CardType.SPEAK
            )
        if (!evaluation.accepted && committedMiss) {
            if (prompt.practiceOnly) {
                commitPracticeMiss(prompt)
                return
            }
            mutableState.value = mutableState.value.copy(ratingInProgress = true)
            viewModelScope.launch {
                runCatching {
                    queueBeforeLastReview = activeStudyQueue.toList()
                    repository.review(prompt.card, Rating.AGAIN)
                }.onSuccess { becameLeech ->
                    sessionCounterDeltas.addLast(SessionCounterDelta(reviewed = 1, correct = 0))
                    mutableState.value = mutableState.value.copy(
                        ratingInProgress = false,
                        autoRatedAgain = true,
                        sessionReviewed = mutableState.value.sessionReviewed + 1,
                        sessionCompletedCards = mutableState.value.sessionCompletedCards + 1,
                        statusMessage = if (becameLeech) "Parked this card — it kept tripping you up. Find it under Leeches." else mutableState.value.statusMessage
                    )
                    recordReviewTelemetry(prompt, Rating.AGAIN, becameLeech, autoRated = true)
                    if (becameLeech) {
                        advanceFrozenQueue(prompt, Rating.GOOD)
                        mutableState.value = mutableState.value.copy(autoRatedAgain = false, correctionRequired = false)
                        loadSession(preserveStudyQueue = true)
                    } else {
                        handleFailure(prompt)
                        mutableState.value = mutableState.value.copy(
                            correctionRequired = true,
                            correctionAnswer = "",
                            correctionAccepted = false
                        )
                    }
                }.onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        ratingInProgress = false,
                        statusMessage = error.message ?: "Could not save review"
                    )
                }
            }
        }
    }

    private fun commitPracticeMiss(prompt: ReviewPrompt) {
        repository.clearUndo()
        queueBeforeLastReview = null
        mutableState.value = mutableState.value.copy(ratingInProgress = true)
        viewModelScope.launch {
            val remaining = activeStudyQueue.toMutableList()
            val index = remaining.indexOfFirst { it.card.id == prompt.card.id && it.practiceOnly }
            if (index >= 0) remaining.removeAt(index)
            val failures = (failureCounts[prompt.card.id] ?: 0) + 1
            failureCounts[prompt.card.id] = failures
            repository.scaffoldPromptFor(prompt.card, failures)?.let { remaining.add(minOf(3, remaining.size), it) }
            activeStudyQueue.clear(); activeStudyQueue += remaining
            recordResponseSample(prompt, Rating.AGAIN)
            repository.recordTelemetry(telemetryForPrompt("acquisition_practice", prompt).copy(
                rating = Rating.AGAIN.name,
                answerMatch = AnswerMatch.WRONG.name,
                metadataJson = JSONObject().put("autoRated", true).toString()
            ))
            mutableState.value = mutableState.value.copy(
                ratingInProgress = false,
                autoRatedAgain = true,
                correctionRequired = true,
                correctionAnswer = "",
                correctionAccepted = false,
                sessionCompletedCards = mutableState.value.sessionCompletedCards + 1,
                canUndo = false
            )
        }
    }

    fun rate(rating: Rating) {
        val prompt = mutableState.value.prompt ?: return
        if (mutableState.value.autoRatedAgain) return
        if (mutableState.value.ratingInProgress) return
        if (prompt.supportOnly || prompt.practiceOnly) {
            rateUnscheduledPrompt(prompt, rating)
            return
        }
        // A LESSON is a teaching screen, not a graded card — keep it out of the
        // sitting's accuracy so the percentage reflects real recall.
        val countable = prompt.card.cardType != com.sibirskyspeak.data.CardType.LESSON &&
            prompt.answerMode != AnswerMode.LESSON
        val wasCorrect = mutableState.value.isAnswerCorrect == true
        val delta = SessionCounterDelta(
            reviewed = if (countable) 1 else 0,
            correct = if (countable && wasCorrect) 1 else 0
        )
        mutableState.value = mutableState.value.copy(ratingInProgress = true)
        viewModelScope.launch {
            runCatching {
                queueBeforeLastReview = activeStudyQueue.toList()
                repository.review(prompt.card, rating)
            }.onSuccess { becameLeech ->
                sessionCounterDeltas.addLast(delta)
                mutableState.value = mutableState.value.copy(
                    sessionReviewed = mutableState.value.sessionReviewed + delta.reviewed,
                    sessionCorrect = mutableState.value.sessionCorrect + delta.correct,
                    sessionCompletedCards = mutableState.value.sessionCompletedCards + 1,
                    statusMessage = if (becameLeech) "Parked this card — it kept tripping you up. Find it under Leeches." else mutableState.value.statusMessage
                )
                recordReviewTelemetry(prompt, rating, becameLeech, autoRated = false)
                recordResponseSample(prompt, rating)
                if (rating == Rating.AGAIN) {
                    if (becameLeech) {
                        advanceFrozenQueue(prompt, Rating.GOOD)
                        mutableState.value = mutableState.value.copy(ratingInProgress = false, autoRatedAgain = false, correctionRequired = false)
                        loadSession(preserveStudyQueue = true)
                    } else {
                        handleFailure(prompt)
                        mutableState.value = mutableState.value.copy(
                            ratingInProgress = false,
                            autoRatedAgain = true,
                            correctionRequired = true,
                            correctionAnswer = "",
                            correctionAccepted = false
                        )
                    }
                } else {
                    failureCounts.remove(prompt.card.id)
                    advanceFrozenQueue(prompt, rating)
                    scheduleAcquisitionPractice(prompt, rating)
                    loadSession(preserveStudyQueue = true)
                }
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    ratingInProgress = false,
                    statusMessage = error.message ?: "Could not save review"
                )
            }
        }
    }

    fun continueAfterRating() {
        if (mutableState.value.correctionRequired && !mutableState.value.correctionAccepted) return
        viewModelScope.launch { loadSession(preserveStudyQueue = true) }
    }

    private suspend fun handleFailure(prompt: ReviewPrompt) {
        val failures = (failureCounts[prompt.card.id] ?: 0) + 1
        failureCounts[prompt.card.id] = failures
        val repair = if (failures >= 2) repository.scaffoldPromptFor(prompt.card, failures) else repository.repairPromptFor(prompt.card)
        advanceFrozenQueue(prompt, Rating.AGAIN, repair)
        recordResponseSample(prompt, Rating.AGAIN)
        if (failures >= 2) repository.recordTelemetry(telemetryForPrompt("scaffold_inserted", prompt).copy(
            metadataJson = JSONObject().put("supportLevel", failures).toString()
        ))
    }

    private fun rateUnscheduledPrompt(prompt: ReviewPrompt, rating: Rating) {
        if (mutableState.value.ratingInProgress) return
        repository.clearUndo()
        queueBeforeLastReview = null
        mutableState.value = mutableState.value.copy(ratingInProgress = true)
        viewModelScope.launch {
            val remaining = activeStudyQueue.toMutableList().also { list ->
                val index = list.indexOfFirst { it === prompt || (it.card.id == prompt.card.id && it.supportOnly == prompt.supportOnly && it.practiceOnly == prompt.practiceOnly) }
                if (index >= 0) list.removeAt(index)
            }
            val success = rating != Rating.AGAIN
            if (prompt.supportOnly) {
                repository.promptForCard(prompt.card)?.let { recall ->
                    remaining.add(minOf(experimentGap(), remaining.size), recall.copy(queueReason = "Recall after adaptive support"))
                }
                repository.recordTelemetry(telemetryForPrompt("scaffold_completed", prompt).copy(rating = rating.name))
            } else if (prompt.practiceOnly) {
                if (success) {
                    val count = (acquisitionSuccesses[prompt.card.id] ?: 1) + 1
                    acquisitionSuccesses[prompt.card.id] = count
                    if (count < acquisitionTarget()) {
                        repository.practicePromptFor(prompt.card, count)?.let { next ->
                            remaining.add(minOf(experimentGap(), remaining.size), next.copy(
                                practiceOnly = true,
                                queueReason = "Acquisition recall ${count + 1} of ${acquisitionTarget()}"
                            ))
                        }
                    }
                } else {
                    val scaffold = repository.scaffoldPromptFor(prompt.card, (failureCounts[prompt.card.id] ?: 0) + 1)
                    scaffold?.let { remaining.add(minOf(3, remaining.size), it) }
                }
                repository.recordTelemetry(telemetryForPrompt("acquisition_practice", prompt).copy(rating = rating.name))
            }
            activeStudyQueue.clear(); activeStudyQueue += remaining
            mutableState.value = mutableState.value.copy(
                ratingInProgress = false,
                sessionCompletedCards = mutableState.value.sessionCompletedCards + 1,
                autoRatedAgain = !success,
                correctionRequired = !success,
                correctionAnswer = "",
                correctionAccepted = false,
                canUndo = false
            )
            if (success) loadSession(preserveStudyQueue = true)
        }
    }

    private suspend fun scheduleAcquisitionPractice(prompt: ReviewPrompt, rating: Rating) {
        if (rating == Rating.AGAIN || prompt.card.cardType != com.sibirskyspeak.data.CardType.RU_TO_MEANING) return
        if (prompt.answerMode == AnswerMode.LESSON) return
        if (prompt.queueReason?.contains("First recall") != true && prompt.card.reps > 0) return
        acquisitionSuccesses[prompt.card.id] = 1
        repository.practicePromptFor(prompt.card, 1)?.let { next ->
            activeStudyQueue.add(minOf(experimentGap(), activeStudyQueue.size), next.copy(
                practiceOnly = true,
                queueReason = "Acquisition recall 2 of ${acquisitionTarget()}"
            ))
        }
    }

    private fun acquisitionTarget() = if (settings.learningExperimentVariant == "A") 3 else 4
    private fun experimentGap() = if (settings.learningExperimentVariant == "A") 6 else 8

    private fun recordResponseSample(prompt: ReviewPrompt, rating: Rating) {
        if (prompt.answerMode == AnswerMode.LESSON) return
        val elapsed = ((if (answerRevealedAt > 0) answerRevealedAt else System.currentTimeMillis()) - promptShownAt).coerceAtLeast(0)
        responseSamples += elapsed to (rating != Rating.AGAIN)
        if (!fatigueAdjusted && responseSamples.size >= 8) {
            val baseline = responseSamples.take(3).map { it.first }.average().coerceAtLeast(1.0)
            val recent = responseSamples.takeLast(3).map { it.first }.average()
            val recentMisses = responseSamples.takeLast(4).count { !it.second }
            if (recent > baseline * 1.8 || recentMisses >= 2) {
                val before = activeStudyQueue.size
                val removableNew = activeStudyQueue.count { it.card.state == com.sibirskyspeak.data.CardState.NEW && it.card.reps == 0 }
                // Backlog mode already suppresses new material. Avoid a warning and
                // telemetry event when fatigue protection would change nothing.
                if (removableNew == 0) return
                activeStudyQueue.removeAll { it.card.state == com.sibirskyspeak.data.CardState.NEW && it.card.reps == 0 }
                val removed = before - activeStudyQueue.size
                fatigueAdjusted = true
                mutableState.value = mutableState.value.copy(
                    fatigueAdjusted = true,
                    statusMessage = if (removed > 0) "Pace protected: $removed optional new ${if (removed == 1) "card" else "cards"} moved to tomorrow." else "Pace protected: no more new material this sitting."
                )
                viewModelScope.launch { repository.recordTelemetry(TelemetryEvent(
                    eventType = "fatigue_adjustment", sessionId = telemetrySessionId,
                    metadataJson = JSONObject().put("removedNew", removed).put("variant", settings.learningExperimentVariant).toString()
                )) }
            }
        }
    }

    private fun suggestedRating(evaluation: AnswerEvaluation, prompt: ReviewPrompt, elapsedMs: Long): Rating {
        if (!evaluation.accepted) return Rating.AGAIN
        val slowAt = when (prompt.answerMode) {
            AnswerMode.CHOICE -> 4_000L
            AnswerMode.ENGLISH -> 7_000L
            else -> 10_000L
        }
        if (evaluation.match == AnswerMatch.CLOSE || elapsedMs > slowAt) return Rating.HARD
        return if (prompt.card.reps > 0 && elapsedMs <= slowAt / 3) Rating.EASY else Rating.GOOD
    }

    private fun advanceFrozenQueue(prompt: ReviewPrompt, rating: Rating, repairPrompt: ReviewPrompt? = null) {
        if (!studySessionActive) return
        val updated = recoveryQueueAfter(activeStudyQueue, prompt, rating, repairPrompt)
        activeStudyQueue.clear()
        activeStudyQueue += updated
    }

    private fun telemetryForPrompt(eventType: String, prompt: ReviewPrompt): TelemetryEvent = TelemetryEvent(
        eventType = eventType,
        sessionId = telemetrySessionId,
        cardId = prompt.card.id,
        noteId = prompt.card.noteId,
        cardType = prompt.card.cardType.name,
        queue = prompt.card.queue.name,
        answerMode = prompt.answerMode.name,
        queueReason = prompt.queueReason,
        sessionRemaining = activeStudyQueue.size,
        dueCount = mutableState.value.dailyPlan?.let { it.dueVocab + it.dueGrammar },
        newCardLimit = settings.newCardsPerDay
    )

    private suspend fun recordReviewTelemetry(
        prompt: ReviewPrompt,
        rating: Rating,
        becameLeech: Boolean,
        autoRated: Boolean
    ) {
        val committedAt = if (answerRevealedAt > 0) answerRevealedAt else System.currentTimeMillis()
        val responseMs = (committedAt - promptShownAt).coerceAtLeast(0)
        val easyInterpretation = when {
            rating != Rating.EASY -> null
            prompt.card.state == com.sibirskyspeak.data.CardState.NEW || prompt.card.reps == 0 -> "prior_knowledge_candidate"
            responseMs <= 3_000 -> "instant_recall"
            else -> "self_rated_easy"
        }
        repository.recordTelemetry(telemetryForPrompt("review_committed", prompt).copy(
            rating = rating.name,
            answerMatch = mutableState.value.answerMatch?.name,
            responseMs = responseMs,
            wasRevealed = mutableState.value.revealed,
            typedLength = mutableState.value.typedAnswer.length,
            metadataJson = JSONObject()
                .put("stateBefore", prompt.card.state.name)
                .put("repsBefore", prompt.card.reps)
                .put("lapsesBefore", prompt.card.lapses)
                .put("autoRated", autoRated)
                .put("becameLeech", becameLeech)
                .put("easyInterpretation", easyInterpretation)
                .put("suggestedRating", mutableState.value.suggestedRating?.name)
                .put("experimentVariant", settings.learningExperimentVariant)
                .put("ratingDecisionMs", if (answerRevealedAt > 0) (System.currentTimeMillis() - answerRevealedAt).coerceAtLeast(0) else JSONObject.NULL)
                .toString()
        ))
    }

    /**
     * Roll back the last committed review and re-present that card. Works both for
     * explicit ratings and for the silent auto-AGAIN on a missed answer.
     */
    fun undoLastReview() {
        if (mutableState.value.ratingInProgress) return
        viewModelScope.launch {
            val restored = repository.undoLastReview() ?: return@launch
            repository.recordTelemetry(TelemetryEvent(eventType = "review_undo", sessionId = telemetrySessionId, cardId = restored.id, noteId = restored.noteId))
            val delta = if (sessionCounterDeltas.isNotEmpty()) sessionCounterDeltas.removeLast() else SessionCounterDelta(0, 0)
            queueBeforeLastReview?.let { snapshot ->
                activeStudyQueue.clear()
                activeStudyQueue += snapshot
            }
            queueBeforeLastReview = null
            loadSession(status = "Undid last review", preserveStudyQueue = true)
            mutableState.value = mutableState.value.copy(
                revealed = false,
                typedAnswer = "",
                isAnswerCorrect = null,
                answerMatch = null,
                answerFeedback = null,
                autoRatedAgain = false,
                correctionRequired = false,
                correctionAnswer = "",
                correctionAccepted = false,
                sessionReviewed = (mutableState.value.sessionReviewed - delta.reviewed).coerceAtLeast(0),
                sessionCorrect = (mutableState.value.sessionCorrect - delta.correct).coerceAtLeast(0),
                sessionCompletedCards = (mutableState.value.sessionCompletedCards - 1).coerceAtLeast(0)
            )
        }
    }

    /** Retire the current card permanently (bad auto-generated content). */
    fun suspendCurrentCard() {
        val prompt = mutableState.value.prompt ?: return
        if (mutableState.value.ratingInProgress) return
        repository.clearUndo()
        queueBeforeLastReview = null
        viewModelScope.launch {
            runCatching { repository.suspendCard(prompt.card) }
                .onSuccess {
                    activeStudyQueue.removeAll { it.card.id == prompt.card.id }
                    repository.recordTelemetry(telemetryForPrompt("card_suspended", prompt))
                    loadSession(status = "Card suspended. It is out of all review queues.", preserveStudyQueue = true)
                }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = it.message ?: "Could not suspend card") }
        }
    }

    fun markCurrentWordKnown() {
        val prompt = mutableState.value.prompt ?: return
        if (mutableState.value.ratingInProgress) return
        repository.clearUndo()
        queueBeforeLastReview = null
        viewModelScope.launch {
            runCatching { repository.markWordKnown(prompt.card.noteId) }
                .onSuccess {
                    activeStudyQueue.removeAll { it.card.noteId == prompt.card.noteId && it.card.queue.name == "VOCAB" }
                    repository.recordTelemetry(telemetryForPrompt("mark_known", prompt))
                    loadSession(status = "Marked known. Vocab practice for this word is retired.", preserveStudyQueue = true)
                }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = it.message ?: "Could not mark known") }
        }
    }

    fun placeAfterLevel(level: String) {
        viewModelScope.launch {
            runCatching { repository.placeAfterLevel(level) }
                .onSuccess { count -> loadSession(keepStep = SessionStep.IMPORT, status = "Placed after $level: marked $count notes known") }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = it.message ?: "Could not place level") }
        }
    }

    /**
     * "I actually knew this" escape hatch after a typed answer was auto-failed.
     * Rolls back the silent AGAIN and reopens the rating buttons so the learner
     * can grade their true recall.
     */
    fun overrideKnewIt() {
        if (!mutableState.value.autoRatedAgain) return
        viewModelScope.launch {
            repository.undoLastReview()
            queueBeforeLastReview?.let { snapshot ->
                activeStudyQueue.clear()
                activeStudyQueue += snapshot
            }
            queueBeforeLastReview = null
            mutableState.value.prompt?.let { repository.recordTelemetry(telemetryForPrompt("auto_miss_overridden", it)) }
            val delta = if (sessionCounterDeltas.isNotEmpty()) sessionCounterDeltas.removeLast() else SessionCounterDelta(1, 0)
            mutableState.value = mutableState.value.copy(
                autoRatedAgain = false,
                correctionRequired = false,
                correctionAnswer = "",
                correctionAccepted = false,
                revealed = true,
                isAnswerCorrect = true,
                answerMatch = AnswerMatch.CLOSE,
                answerFeedback = "Auto-Again undone for this slip. Grade the recall you actually had; use Again if the miss was real.",
                ratingInProgress = false,
                // The auto-AGAIN already counted this card; the upcoming rate() will
                // count it again, so roll the auto-count back to avoid double-counting.
                sessionReviewed = (mutableState.value.sessionReviewed - delta.reviewed).coerceAtLeast(0),
                sessionCorrect = (mutableState.value.sessionCorrect - delta.correct).coerceAtLeast(0),
                sessionCompletedCards = (mutableState.value.sessionCompletedCards - 1).coerceAtLeast(0)
            )
        }
    }

    fun markVisibleWords(tokens: List<String>, status: WordStatus) {
        if (tokens.isEmpty()) return
        val recommendation = mutableState.value.currentReaderRecommendation() ?: return
        viewModelScope.launch {
            val count = repository.setWordStatusBatch(tokens, status)
            val refreshedTexts = repository.readerTexts()
            val selected = refreshedTexts.firstOrNull { it.text.id == recommendation.text.id } ?: recommendation
            val statusText = status.name.lowercase()
            mutableState.value = mutableState.value.copy(
                allReaderTexts = refreshedTexts,
                readerRecommendation = recommendNextReader(refreshedTexts),
                readerTokens = repository.readerTokens(selected.text),
                selectedToken = null,
                statusMessage = if (count == 0) "No word statuses changed." else "Marked $count ${if (count == 1) "word" else "words"} $statusText"
            )
        }
    }

    fun lookupReaderToken(token: String) {
        val recommendation = mutableState.value.currentReaderRecommendation() ?: return
        val normalized = mutableState.value.readerTokens.firstOrNull { it.surface == token }?.normalized
        mutableState.value = mutableState.value.copy(readerLookupInProgress = true, lookupResult = "Looking up $token...")
        viewModelScope.launch {
            val note = repository.readerLookup(token, recommendation.text)
            repository.recordTelemetry(TelemetryEvent(
                eventType = "reader_lookup",
                sessionId = telemetrySessionId,
                noteId = note?.id,
                metadataJson = JSONObject().put("readerTextId", recommendation.text.id).put("resolved", note != null && note.translation != "lookup pending").toString()
            ))
            val refreshedTexts = repository.readerTexts()
            val selected = refreshedTexts.firstOrNull { it.text.id == recommendation.text.id } ?: recommendation
            val tokens = repository.readerTokens(selected.text)
            mutableState.value = mutableState.value.copy(
                allReaderTexts = refreshedTexts,
                readerRecommendation = recommendNextReader(refreshedTexts),
                readerTokens = tokens,
                selectedToken = tokens.firstOrNull { it.surface == token || it.normalized == normalized },
                lookupResult = note?.let { "${it.russian} = ${it.translation}" } ?: "Added $token as lookup pending",
                readerLookupInProgress = false
            )
        }
    }

    fun setReaderWordStatus(status: com.sibirskyspeak.data.WordStatus) {
        val token = mutableState.value.selectedToken ?: return
        val recommendation = mutableState.value.currentReaderRecommendation() ?: return
        viewModelScope.launch {
            repository.setWordStatus(token.surface, status)
            val refreshedTexts = repository.readerTexts()
            val selected = refreshedTexts.firstOrNull { it.text.id == recommendation.text.id } ?: recommendation
            val tokens = repository.readerTokens(selected.text)
            mutableState.value = mutableState.value.copy(
                allReaderTexts = refreshedTexts,
                readerRecommendation = recommendNextReader(refreshedTexts),
                readerTokens = tokens,
                selectedToken = tokens.firstOrNull { it.normalized == token.normalized },
                statusMessage = readerStatusMessage(token.surface, status)
            )
        }
    }

    /**
     * Sentence-mining from the reader: take the selected word and the sentence it
     * appears in, store that sentence as the word's example, and pull it into study.
     */
    fun mineSentence(sentence: String, translation: String? = null) {
        val token = mutableState.value.selectedToken ?: return
        val recommendation = mutableState.value.currentReaderRecommendation() ?: return
        viewModelScope.launch {
            val note = repository.mineSentence(token.surface, sentence, translation)
            val refreshedTexts = repository.readerTexts()
            val selected = refreshedTexts.firstOrNull { it.text.id == recommendation.text.id } ?: recommendation
            val tokens = repository.readerTokens(selected.text)
            val miningStatus = note?.let {
                if (translation.isNullOrBlank()) {
                    "Saved ${it.russian} with this sentence. Add a meaning later to create context recall."
                } else {
                    "Created context recall for ${it.russian} with this sentence."
                }
            } ?: "Could not add word"
            mutableState.value = mutableState.value.copy(
                allReaderTexts = refreshedTexts,
                readerRecommendation = recommendNextReader(refreshedTexts),
                readerTokens = tokens,
                selectedToken = tokens.firstOrNull { it.normalized == token.normalized },
                statusMessage = miningStatus
            )
        }
    }

    fun clearSelectedToken() {
        mutableState.value = mutableState.value.copy(selectedToken = null, lookupResult = null)
    }

    fun openReaderText(id: Long) {
        viewModelScope.launch {
            openReaderTextNow(id, inSession = false)
        }
    }

    private suspend fun openReaderTextNow(id: Long, inSession: Boolean) {
        val recommendation = mutableState.value.allReaderTexts.firstOrNull { it.text.id == id }
            ?: repository.readerTexts().firstOrNull { it.text.id == id }
            ?: return
        val progress = settings.readerProgress(id)
        val tokens = repository.readerTokens(recommendation.text)
        val questions = buildReaderCheckpoint(tokens, mutableState.value.sessionPlan?.consolidationLemmas.orEmpty().toSet())
        mutableState.value = mutableState.value.copy(
            selectedReaderTextId = id,
            readerTokens = tokens,
            selectedToken = null,
            lookupResult = null,
            readerProgressIndex = progress,
            readerProgressByText = mutableState.value.readerProgressByText + (id to progress),
            readerCheckpointQuestions = questions,
            readerCheckpointIndex = 0,
            readerCheckpointFeedback = null,
            readerCheckpointMistakes = 0,
            inSessionReading = inSession
        )
        repository.recordTelemetry(TelemetryEvent(
            eventType = if (inSession) "scheduled_reading_shown" else "reader_open",
            sessionId = telemetrySessionId,
            metadataJson = JSONObject().put("readerTextId", id).put("progress", progress).toString()
        ))
    }

    fun answerReaderCheckpoint(answer: String) {
        if (readingCommitInProgress) return
        val state = mutableState.value
        val question = state.readerCheckpointQuestions.getOrNull(state.readerCheckpointIndex)
        if (question == null) {
            if (answer == COMPLETE_READING && state.inSessionReading) finishScheduledReading(state.readerCheckpointMistakes)
            return
        }
        val correct = answer.trim().equals(question.expected.trim(), ignoreCase = true)
        val next = if (correct) state.readerCheckpointIndex + 1 else state.readerCheckpointIndex
        val mistakes = state.readerCheckpointMistakes + if (correct) 0 else 1
        mutableState.value = state.copy(
            readerCheckpointIndex = next,
            readerCheckpointMistakes = mistakes,
            readerCheckpointFeedback = if (correct) {
                if (next >= state.readerCheckpointQuestions.size) "Checkpoint complete — meaning transferred into the text." else "Correct. One more."
            } else "Not quite. Re-read the highlighted word in its sentence, then try again."
        )
        viewModelScope.launch { repository.recordTelemetry(TelemetryEvent(
            eventType = "reader_checkpoint_answer",
            sessionId = telemetrySessionId,
            noteId = question.noteId,
            answerMatch = if (correct) AnswerMatch.EXACT.name else AnswerMatch.WRONG.name,
            metadataJson = JSONObject()
                .put("readerTextId", state.selectedReaderTextId)
                .put("questionIndex", state.readerCheckpointIndex)
                .put("correct", correct)
                .toString()
        )) }
        if (correct && next >= state.readerCheckpointQuestions.size && state.inSessionReading) {
            finishScheduledReading(mistakes)
        }
    }

    private fun finishScheduledReading(mistakes: Int) {
        if (readingCommitInProgress) return
        val state = mutableState.value
        val readerTextId = state.selectedReaderTextId ?: return
        readingCommitInProgress = true
        // Close the double-tap window synchronously, before the database suspend.
        mutableState.value = state.copy(inSessionReading = false)
        viewModelScope.launch {
            try {
                repository.completeScheduledReading(readerTextId, mistakes)
                mutableState.value = mutableState.value.copy(
                    selectedReaderTextId = null,
                    selectedToken = null,
                    inSessionReading = false,
                    readerCheckpointQuestions = emptyList(),
                    readerCheckpointIndex = 0,
                    sessionCompletedCards = mutableState.value.sessionCompletedCards + 1,
                    statusMessage = "Reading consolidated. Back to your review queue."
                )
                loadSession(preserveStudyQueue = true)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    inSessionReading = true,
                    statusMessage = error.message ?: "Could not save reading progress"
                )
            } finally {
                readingCommitInProgress = false
            }
        }
    }

    private fun buildReaderCheckpoint(tokens: List<ReaderToken>, consolidation: Set<String>): List<ReaderCheckpointQuestion> {
        val candidates = tokens
            .filter { !it.translation.isNullOrBlank() }
            .distinctBy { it.normalized }
            .sortedByDescending { it.lemma in consolidation }
            .take(3)
        val distractors = tokens.mapNotNull { it.translation?.trim() }.filter { it.isNotBlank() }.distinct()
        return candidates.mapIndexed { index, token ->
            val expected = token.translation!!.trim()
            val choices = (listOf(expected) + distractors.filterNot { it.equals(expected, true) }.take(3))
                .distinct().sortedBy { (it.hashCode() xor token.normalized.hashCode() xor index) }
            ReaderCheckpointQuestion(token.surface, expected, choices)
        }.filter { it.choices.size >= 2 }
    }

    /** Record the furthest token index the learner has reached, for "continue reading". */
    fun recordReaderProgress(tokenIndex: Int) {
        val id = mutableState.value.selectedReaderTextId ?: return
        if (tokenIndex <= mutableState.value.readerProgressIndex) return
        settings.setReaderProgress(id, tokenIndex)
        mutableState.value = mutableState.value.copy(
            readerProgressIndex = tokenIndex,
            readerProgressByText = mutableState.value.readerProgressByText + (id to tokenIndex)
        )
        if (tokenIndex % 20 == 0) viewModelScope.launch {
            repository.recordTelemetry(TelemetryEvent(
                eventType = "reader_progress",
                sessionId = telemetrySessionId,
                metadataJson = JSONObject().put("readerTextId", id).put("tokenIndex", tokenIndex).toString()
            ))
        }
    }

    fun closeReaderText() {
        if (readingCommitInProgress) return
        if (mutableState.value.inSessionReading) {
            val id = mutableState.value.selectedReaderTextId ?: return
            readingCommitInProgress = true
            mutableState.value = mutableState.value.copy(inSessionReading = false)
            viewModelScope.launch {
                try {
                    repository.completeScheduledReading(id, mutableState.value.readerCheckpointMistakes, abandoned = true)
                    mutableState.value = mutableState.value.copy(inSessionReading = false, selectedReaderTextId = null)
                    loadSession(preserveStudyQueue = true, status = "Reading postponed until tomorrow.")
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    mutableState.value = mutableState.value.copy(
                        inSessionReading = true,
                        statusMessage = error.message ?: "Could not postpone reading"
                    )
                } finally {
                    readingCommitInProgress = false
                }
            }
            return
        }
        mutableState.value = mutableState.value.copy(
            selectedReaderTextId = null,
            selectedToken = null,
            lookupResult = null,
            readerCheckpointQuestions = emptyList(),
            readerCheckpointIndex = 0,
            readerCheckpointFeedback = null
        )
    }

    fun setImportText(value: String) {
        mutableState.value = mutableState.value.copy(importText = value)
    }

    fun importJsonLines() {
        val payload = mutableState.value.importText
        viewModelScope.launch {
            runCatching { repository.importJsonLines(payload) }
                .onSuccess { count -> loadSession(keepStep = SessionStep.IMPORT, status = "Imported $count notes. Check import readiness for readable examples.") }
                .onFailure { error -> mutableState.value = mutableState.value.copy(statusMessage = error.message ?: "Import failed") }
        }
    }

    fun exportJsonLines() {
        viewModelScope.launch {
            val exported = repository.exportJsonLines()
            mutableState.value = mutableState.value.copy(exportText = exported, statusMessage = "Exported ${exported.lines().filter { it.isNotBlank() }.size} notes (content only)")
        }
    }

    fun exportFullState() {
        viewModelScope.launch {
            val exported = repository.exportFullState()
            mutableState.value = mutableState.value.copy(exportText = exported, statusMessage = "Full backup: ${exported.lines().filter { it.isNotBlank() }.size} notes with SRS state")
        }
    }

    fun setReaderTitle(value: String) {
        mutableState.value = mutableState.value.copy(readerTitle = value)
    }

    fun setReaderBody(value: String) {
        mutableState.value = mutableState.value.copy(readerBody = value)
    }

    fun addReaderText() {
        val title = mutableState.value.readerTitle
        val body = mutableState.value.readerBody
        if (body.isBlank()) {
            mutableState.value = mutableState.value.copy(statusMessage = "Reader text body is empty")
            return
        }
        viewModelScope.launch {
            repository.addReaderText(title, body)
            loadSession(keepStep = SessionStep.READER, status = "Added reader text")
        }
    }

    private suspend fun loadSession(
        keepStep: SessionStep = mutableState.value.sessionStep,
        status: String? = mutableState.value.statusMessage,
        preserveStudyQueue: Boolean = false
    ) {
        val current = mutableState.value
        val canReusePlan = preserveStudyQueue && studySessionActive && activeStudyQueue.isNotEmpty() && current.sessionPlan != null
        val freshPlan = if (canReusePlan) current.sessionPlan!! else repository.sessionPlan()
        // Study can be opened before the asynchronous startup plan is ready. In
        // that race startStudySession() freezes an empty queue; when the plan lands,
        // adopt it here so the visible prompt and the queue cannot diverge.
        if (studySessionActive && !preserveStudyQueue) {
            activeStudyQueue.clear()
            activeStudyQueue += freshPlan.reviewQueue
        }
        if (canReusePlan) {
            val scheduled = activeStudyQueue.filterNot { it.supportOnly || it.practiceOnly }
            val refreshedById = repository.promptsForCards(scheduled.map { it.card })
                .associateBy { it.card.id }
            val refreshed = activeStudyQueue.mapNotNull { queued ->
                if (queued.supportOnly || queued.practiceOnly) queued
                else refreshedById[queued.card.id]?.copy(queueReason = queued.queueReason)
            }
            activeStudyQueue.clear()
            activeStudyQueue += refreshed
        }
        val plan = if (preserveStudyQueue && studySessionActive) {
            freshPlan.copy(reviewQueue = activeStudyQueue.toList())
        } else freshPlan
        // Personalize FSRS from the learner's own history: first re-fit the weight
        // subset (so the curve used below reflects the latest fit), then nudge the
        // global interval multiplier toward target, then adapt the daily new-card load.
        maybeRefitWeights()
        recalibrateScheduling(plan.dashboardStats)
        adaptDailyLoad(plan)
        val step = keepStep
        val allReaders = if (canReusePlan) current.allReaderTexts else repository.readerTexts()
        val readerRecommendation = plan.readerRecommendation ?: recommendNextReader(allReaders)
        val readerProgressByText = allReaders.associate { it.text.id to settings.readerProgress(it.text.id) }
        val selectedReader = current.selectedReaderTextId?.let { id ->
            allReaders.firstOrNull { it.text.id == id }
        } ?: readerRecommendation
        // Detect achievements unlocked since last seen, for the celebratory toast.
        val unlockedIds = plan.gamification.achievements.filter { it.unlocked }.map { it.id }.toSet()
        val freshIds = settings.newlyUnlocked(unlockedIds)
        val freshAchievements = plan.gamification.achievements.filter { it.id in freshIds }
        val previousPromptId = current.prompt?.card?.id
        mutableState.value = ReviewUiState(
            prompt = promptForStep(step, plan),
            reviewedToday = repository.reviewedToday(),
            dailyPlan = plan.dailyPlan,
            sessionPlan = plan,
            readerRecommendation = readerRecommendation,
            allReaderTexts = allReaders,
            readerTokens = selectedReader?.text?.let { repository.readerTokens(it) }.orEmpty(),
            dashboardStats = plan.dashboardStats.copy(intervalModifier = settings.intervalModifier),
            importText = current.importText,
            exportText = current.exportText,
            readerTitle = current.readerTitle,
            readerBody = current.readerBody,
            selectedReaderTextId = current.selectedReaderTextId,
            readerProgressByText = readerProgressByText,
            statusMessage = status,
            sessionStep = step,
            canUndo = repository.canUndo(),
            dailyGoalSetting = settings.dailyGoal,
            sessionSizeSetting = settings.sessionSize,
            newCardsPerDaySetting = settings.newCardsPerDay,
            retentionSetting = settings.desiredRetention,
            reminderEnabled = settings.reminderEnabled,
            reminderHour = settings.reminderHour,
            readerFontScale = settings.readerFontScale,
            searchQuery = current.searchQuery,
            searchResults = current.searchResults,
            referenceQuery = current.referenceQuery,
            referenceResults = current.referenceResults,
            readerProgressIndex = current.selectedReaderTextId?.let { readerProgressByText[it] } ?: -1,
            readerCheckpointQuestions = current.readerCheckpointQuestions,
            readerCheckpointIndex = current.readerCheckpointIndex,
            readerCheckpointFeedback = current.readerCheckpointFeedback,
            fatigueAdjusted = fatigueAdjusted,
            newlyUnlocked = if (freshAchievements.isNotEmpty()) freshAchievements else current.newlyUnlocked,
            sessionReviewed = current.sessionReviewed,
            sessionCorrect = current.sessionCorrect,
            sessionCompletedCards = current.sessionCompletedCards,
            inSessionReading = current.inSessionReading,
            readerCheckpointMistakes = current.readerCheckpointMistakes,
            inStudySession = current.inStudySession,
            leeches = current.leeches
        )
        val nextPrompt = mutableState.value.prompt
        if (nextPrompt != null && nextPrompt.card.id != previousPromptId) {
            promptShownAt = System.currentTimeMillis()
            answerRevealedAt = 0L
            repository.recordTelemetry(telemetryForPrompt("card_shown", nextPrompt).copy(
                metadataJson = JSONObject()
                    .put("state", nextPrompt.card.state.name)
                    .put("reps", nextPrompt.card.reps)
                    .put("lapses", nextPrompt.card.lapses)
                    .toString()
            ))
        }
        if (preserveStudyQueue && studySessionActive && activeStudyQueue.isEmpty()) {
            repository.recordTelemetry(TelemetryEvent(
                eventType = "session_complete",
                sessionId = telemetrySessionId,
                sessionRemaining = 0,
                metadataJson = JSONObject()
                    .put("reviewed", mutableState.value.sessionReviewed)
                    .put("correct", mutableState.value.sessionCorrect)
                    .put("actions", mutableState.value.sessionCompletedCards)
                    .toString()
            ))
            studySessionActive = false
            mutableState.value = mutableState.value.copy(inStudySession = false)
        } else if (preserveStudyQueue && studySessionActive) {
            maybeStartScheduledReading()
        }
    }

    /**
     * Lightweight FSRS personalization: nudge a bounded global interval multiplier so
     * the learner's *actual* mature-card retention drifts toward their target. If they
     * retain better than target, intervals lengthen (less wasted review); if worse,
     * they shorten (less forgetting). Only acts once there's enough data to be stable.
     */
    private fun recalibrateScheduling(stats: DashboardStats) {
        val observed = stats.matureRetention ?: return
        if (stats.matureReviewSample < MIN_OPTIMIZE_SAMPLE) return
        val clampedObserved = observed.coerceIn(0.70, 0.995)
        val target = settings.desiredRetention.coerceIn(0.80, 0.97)
        // Exact interval multiplier under FSRS's own forgetting curve
        // R = (1 + factor·t/S)^(-decay): scaling the interval by
        //   m = (target^(-1/decay) - 1) / (observed^(-1/decay) - 1)
        // moves the learner's achieved retention from `observed` to `target`. This is
        // model-consistent with the scheduler (vs. the older ln(target)/ln(observed)
        // approximation, which systematically under-corrects away from the bounds).
        val decay = FsrsScheduler.decayOf(settings.fsrsWeights)
        val numerator = target.pow(-1.0 / decay) - 1.0
        val denominator = clampedObserved.pow(-1.0 / decay) - 1.0
        if (denominator <= 0.0 || !numerator.isFinite() || !denominator.isFinite()) return
        settings.intervalModifier = (numerator / denominator).coerceIn(0.5, 2.0)
    }

    /**
     * Re-fit the high-leverage FSRS weights (per-rating initial stability + decay)
     * from the learner's own review history. Runs at most once per local day, only
     * once enough mature history exists for at least one parameter to be estimated.
     * The fit is bounded and blended (see [FsrsWeightFitter]) so it can only nudge.
     */
    private suspend fun maybeRefitWeights() {
        val now = System.currentTimeMillis()
        val offset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val day = (now + offset) / (24L * 60 * 60 * 1000)
        if (settings.lastWeightFitDay == day) return
        settings.lastWeightFitDay = day
        val rows = repository.reviewSamplesForFitting()
        if (rows.isEmpty()) return
        val result = FsrsWeightFitter.fit(rows, settings.fsrsWeights)
        if (!result.changed) return
        settings.fsrsWeights = result.weights
        repository.recordTelemetry(TelemetryEvent(
            eventType = "fsrs_weights_refit",
            sessionId = telemetrySessionId,
            metadataJson = JSONObject()
                .put("decaySamples", result.decaySamples)
                .put("initSamples", JSONObject(result.initStabilitySamples.mapKeys { it.key.toString() }))
                .put("decay", FsrsScheduler.decayOf(settings.fsrsWeights))
                .toString()
        ))
    }

    /** Once per local day, move the new-word cap by at most two. Backlog and weak
     * retention reduce load; strong retention plus a light forecast adds one. */
    private fun adaptDailyLoad(plan: SessionPlan) {
        val now = System.currentTimeMillis()
        val offset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val day = (now + offset) / (24L * 60 * 60 * 1000)
        if (settings.lastAdaptiveLoadDay == day) return
        val stats = plan.dashboardStats
        val retention = stats.matureRetention
        val forecastPeak = stats.dueForecast.maxOrNull() ?: 0
        val delta = adaptiveNewCardDelta(
            triage = plan.dailyPlan.triageMode || plan.dailyPlan.overdueBacklog,
            forecastPeak = forecastPeak,
            sessionSize = settings.sessionSize,
            retention = retention,
            sampleSize = stats.matureReviewSample,
            targetRetention = settings.desiredRetention,
            dailyGoal = settings.dailyGoal
        )
        if (delta != 0) settings.newCardsPerDay = settings.newCardsPerDay + delta
        settings.lastAdaptiveLoadDay = day
    }

    /** Voluntarily pull in another batch of new cards today, beyond the daily cap. */
    fun grantExtraCredit() {
        viewModelScope.launch {
            val granted = repository.grantExtraCredit()
            val status = if (granted > 0) {
                "Extra credit: $granted more new cards added for today."
            } else {
                "Extra credit already added for today. Reading is the better next step now."
            }
            loadSession(status = status)
        }
    }

    /** Reset the per-sitting counters when the learner (re)opens the study screen. */
    fun startStudySession() {
        if (studySessionActive) return
        sessionCounterDeltas.clear()
        failureCounts.clear()
        acquisitionSuccesses.clear()
        responseSamples.clear()
        fatigueAdjusted = false
        scheduledReadingPresented = false
        studySessionActive = true
        telemetrySessionId = UUID.randomUUID().toString()
        promptShownAt = System.currentTimeMillis()
        answerRevealedAt = 0L
        activeStudyQueue.clear()
        activeStudyQueue += mutableState.value.sessionPlan?.reviewQueue.orEmpty()
        mutableState.value = mutableState.value.copy(
            prompt = activeStudyQueue.firstOrNull(),
            sessionReviewed = 0,
            sessionCorrect = 0,
            sessionCompletedCards = 0,
            correctionRequired = false,
            correctionAnswer = "",
            correctionAccepted = false,
            fatigueAdjusted = false,
            inStudySession = true
        )
        viewModelScope.launch {
            repository.recordTelemetry(TelemetryEvent(
                eventType = "session_start",
                sessionId = telemetrySessionId,
                sessionRemaining = activeStudyQueue.size,
                dueCount = mutableState.value.dailyPlan?.let { it.dueVocab + it.dueGrammar },
                newCardLimit = settings.newCardsPerDay,
                metadataJson = JSONObject()
                    .put("vocab", activeStudyQueue.count { it.card.queue.name == "VOCAB" })
                    .put("grammar", activeStudyQueue.count { it.card.queue.name == "GRAMMAR" })
                    .put("overdueBacklog", mutableState.value.dailyPlan?.overdueBacklog == true)
                    .put("experimentVariant", settings.learningExperimentVariant)
                    .put("acquisitionTarget", acquisitionTarget())
                    .toString()
            ))
            maybeStartScheduledReading()
        }
    }

    private suspend fun maybeStartScheduledReading() {
        if (!studySessionActive || scheduledReadingPresented || mutableState.value.inSessionReading) return
        val assignment = mutableState.value.sessionPlan?.readingAssignment ?: return
        if (mutableState.value.sessionCompletedCards < assignment.insertionIndex) return
        scheduledReadingPresented = true
        openReaderTextNow(assignment.recommendation.text.id, inSession = true)
    }

    companion object {
        const val COMPLETE_READING = "__complete_scheduled_reading__"
    }

    /** Load the parked-leech list for the management view. */
    fun loadLeeches() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(leeches = leechItems())
        }
    }

    /** Release a parked leech back into study with a clean slate. */
    fun releaseLeech(item: LeechItem) {
        viewModelScope.launch {
            runCatching {
                repository.releaseLeech(item.card)
            }.onSuccess {
                loadSession(status = "Released ${item.russian} back into study")
                mutableState.value = mutableState.value.copy(leeches = leechItems())
            }.onFailure {
                mutableState.value = mutableState.value.copy(statusMessage = it.message ?: "Could not release leech")
            }
        }
    }

    /** Save a content repair from the leech dashboard, then refresh the leech preview. */
    fun editLeech(item: LeechItem, translation: String?, exampleSentence: String?, exampleTranslation: String?, mnemonic: String?) {
        viewModelScope.launch {
            runCatching {
                repository.updateNoteContent(item.card.noteId, translation, exampleSentence, exampleTranslation, mnemonic)
            }.onSuccess {
                mutableState.value = mutableState.value.copy(leeches = leechItems(), statusMessage = "Updated ${item.russian}")
            }.onFailure {
                mutableState.value = mutableState.value.copy(statusMessage = it.message ?: "Could not update leech")
            }
        }
    }

    private suspend fun leechItems(): List<LeechItem> =
        repository.leechCards().map { (card, note) ->
            val prompt = buildPrompt(card, note, emptyMap())
            LeechItem(
                card = card,
                note = note,
                russian = note.russian,
                translation = note.translation,
                lapses = card.lapses,
                cardLabel = card.cardType.readableLabel(),
                promptPreview = prompt.prompt.compactForPreview(),
                expectedAnswer = prompt.expectedAnswer.compactForPreview()
            )
        }

    private fun readerStatusMessage(surface: String, status: WordStatus): String =
        when (status) {
            WordStatus.LEARNING -> "$surface is now learning and can enter practice."
            WordStatus.KNOWN -> "$surface marked known; it counts toward coverage and stops practice."
            WordStatus.IGNORED -> "$surface ignored for reader counts and practice."
            WordStatus.NEW -> "$surface reset to new."
        }

    /** Save an in-place edit to the current card's word from the review screen. */
    fun editCurrentCard(translation: String?, exampleSentence: String?, exampleTranslation: String?, mnemonic: String?) {
        val prompt = mutableState.value.prompt ?: return
        viewModelScope.launch {
            runCatching {
                repository.updateNoteContent(prompt.card.noteId, translation, exampleSentence, exampleTranslation, mnemonic)
            }.onSuccess {
                val refreshed = repository.promptForCard(prompt.card)
                mutableState.value = mutableState.value.copy(prompt = refreshed ?: prompt, statusMessage = "Card updated")
            }.onFailure { mutableState.value = mutableState.value.copy(statusMessage = it.message ?: "Could not update card") }
        }
    }

    private fun promptForStep(step: SessionStep, plan: SessionPlan?): ReviewPrompt? =
        when (step) {
            SessionStep.REVIEWS -> plan?.reviewQueue?.firstOrNull()
            SessionStep.BLOCKED -> plan?.blockedGrammar?.firstOrNull()
            SessionStep.INTERLEAVED -> plan?.interleavedGrammar?.firstOrNull()
            SessionStep.RULE, SessionStep.READER, SessionStep.IMPORT, SessionStep.DASHBOARD -> null
        }

    private fun recommendNextReader(texts: List<ReaderRecommendation>): ReaderRecommendation? =
        recommendNextReaderUi(texts, settings::readerProgress)
}

internal fun recoveryQueueAfter(
    queue: List<ReviewPrompt>,
    current: ReviewPrompt,
    rating: Rating,
    repairPrompt: ReviewPrompt? = null
): List<ReviewPrompt> {
    val remaining = queue.toMutableList()
    if (remaining.firstOrNull()?.card?.id == current.card.id) remaining.removeAt(0) else remaining.remove(current)
    if (rating == Rating.AGAIN && !current.card.suspended) {
        val repair = (repairPrompt ?: current).copy(queueReason = when (current.card.cardType) {
            com.sibirskyspeak.data.CardType.CASE_FILL,
            com.sibirskyspeak.data.CardType.ADJ_AGREE,
            com.sibirskyspeak.data.CardType.VERB_FORM,
            com.sibirskyspeak.data.CardType.ASPECT_SELECT,
            com.sibirskyspeak.data.CardType.GENDER_ID,
            com.sibirskyspeak.data.CardType.CONCEPT_DRILL -> "Repair: focus on the grammar pattern that caused the miss"
            com.sibirskyspeak.data.CardType.STRESS_MARK,
            com.sibirskyspeak.data.CardType.SPEAK,
            com.sibirskyspeak.data.CardType.AUDIO_TO_RU,
            com.sibirskyspeak.data.CardType.DICTATION -> "Repair: hear and produce the sound pattern again"
            com.sibirskyspeak.data.CardType.RU_TO_MEANING -> "Repair: reconnect this form with its meaning"
            else -> "Repair: retrieve the word again without support"
        })
        remaining.add(minOf(6, remaining.size), repair)
        remaining += current.copy(queueReason = "Final recovery check before the session ends")
    } else if (
        current.answerMode == AnswerMode.LESSON &&
        current.card.cardType == com.sibirskyspeak.data.CardType.RU_TO_MEANING
    ) {
        remaining.add(
            minOf(6, remaining.size),
            current.copy(queueReason = "First recall after a spaced introduction")
        )
    }
    return remaining
}

internal fun adaptiveNewCardDelta(
    triage: Boolean,
    forecastPeak: Int,
    sessionSize: Int,
    retention: Double?,
    sampleSize: Int,
    targetRetention: Double,
    dailyGoal: Int
): Int = when {
    triage || forecastPeak > sessionSize * 2 -> -2
    sampleSize < 20 -> 0
    retention != null && retention < 0.85 -> -2
    retention != null && retention < targetRetention - 0.03 -> -1
    retention != null && retention > targetRetention + 0.03 && forecastPeak < dailyGoal -> 1
    else -> 0
}

private fun ReviewUiState.currentReaderRecommendation(): ReaderRecommendation? =
    selectedReaderTextId?.let { id -> allReaderTexts.firstOrNull { it.text.id == id } } ?: readerRecommendation

internal fun recommendNextReaderUi(
    texts: List<ReaderRecommendation>,
    progressFor: (Long) -> Int
): ReaderRecommendation? {
    val ranked = texts.sortedWith(compareBy<ReaderRecommendation> { distanceFromTargetUi(it.coverage) }.thenByDescending { it.coverage })
    return ranked.firstOrNull { !it.isFinishedFor(progressFor(it.text.id)) } ?: ranked.firstOrNull()
}

private fun ReaderRecommendation.isFinishedFor(progressIndex: Int): Boolean =
    totalTokens > 1 && progressIndex >= totalTokens - 1

private fun com.sibirskyspeak.data.CardType.readableLabel(): String =
    name.lowercase()
        .split('_')
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

private fun String.compactForPreview(maxLength: Int = 140): String {
    val compact = lineSequence().joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= maxLength) compact else compact.take(maxLength - 1).trimEnd() + "..."
}

private fun distanceFromTargetUi(coverage: Double): Double =
    when {
        coverage in 0.93..0.96 -> 0.0
        coverage < 0.93 -> 0.93 - coverage
        else -> coverage - 0.96
    }
