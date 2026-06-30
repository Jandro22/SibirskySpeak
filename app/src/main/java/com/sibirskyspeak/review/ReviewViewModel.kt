package com.sibirskyspeak.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sibirskyspeak.data.Achievement
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.DashboardStats
import com.sibirskyspeak.data.DailyPlan
import com.sibirskyspeak.data.MatchHistory
import com.sibirskyspeak.data.LearningRepository
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReaderRecommendation
import com.sibirskyspeak.data.ReaderToken
import com.sibirskyspeak.data.SessionPlan
import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.data.SkillRating
import com.sibirskyspeak.data.RivalState
import com.sibirskyspeak.data.TelemetryEvent
import com.sibirskyspeak.data.WordStatus
import com.sibirskyspeak.scheduler.FsrsScheduler
import com.sibirskyspeak.scheduler.FsrsWeightFitter
import com.sibirskyspeak.learning.LiveSessionState
import com.sibirskyspeak.learning.NextCardSelector
import com.sibirskyspeak.learning.isHardProduction
import com.sibirskyspeak.learning.ReviewControl
import com.sibirskyspeak.learning.ContextualBandit
import com.sibirskyspeak.learning.Doctrine
import com.sibirskyspeak.learning.FatigueModel
import com.sibirskyspeak.learning.MatchReport
import com.sibirskyspeak.learning.ObjectiveAttempt
import com.sibirskyspeak.learning.PerformanceModel
import com.sibirskyspeak.learning.MpcAction
import com.sibirskyspeak.learning.MpcInputs
import com.sibirskyspeak.learning.SessionMpcController
import com.sibirskyspeak.learning.SessionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** A full (non-reused) session rebuild slower than this emits a `slow_load` telemetry
 *  event, so main-thread regressions are observable without a USB profiler. ~18 frames. */
private const val SLOW_LOAD_MS = 300L

/** Competitive ratings need a block, not a coin flip disguised as a match. */
private const val MIN_RANKED_MATCH_CARDS = 5

private val STRICT_FORM_CARD_TYPES = setOf(
    CardType.CASE_FILL,
    CardType.ADJ_AGREE,
    CardType.VERB_FORM,
    CardType.CONCEPT_DRILL
)

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
    val matchReport: MatchReport? = null,
    val skillRatings: List<SkillRating> = emptyList(),
    val rivalState: RivalState? = null,
    val matchHistory: List<MatchHistory> = emptyList(),
    val canUndo: Boolean = false,
    // Settings mirror (persisted in SettingsStore; surfaced for the Settings UI).
    val dailyGoalSetting: Int = SettingsStore.DEFAULT_DAILY_GOAL,
    val sessionSizeSetting: Int = SettingsStore.DEFAULT_SESSION_SIZE,
    val newCardsPerDaySetting: Int = SettingsStore.DEFAULT_NEW_CARDS_PER_DAY,
    val retentionSetting: Double = SettingsStore.DEFAULT_RETENTION,
    val doctrineSetting: Doctrine = Doctrine.BALANCED,
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
    val sessionProgressCompleted: Int = 0,
    val sessionProgressTotal: Int = 0,
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
    private val settings: SettingsStore,
    // Dispatcher for CPU-bound work (the FSRS weight fit). Injectable so tests can
    // pass a deterministic test dispatcher instead of the real Default pool.
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()
    // The in-progress typed answer lives in its own flow, not in ReviewUiState, so a
    // keystroke doesn't re-emit the whole 60-field state and recompose the entire
    // screen. Only the answer-input subtree (which collects this) recomposes while
    // typing. Reset to "" whenever a fresh card is shown (see loadSession).
    private val mutableTypedAnswer = MutableStateFlow("")
    val typedAnswer: StateFlow<String> = mutableTypedAnswer.asStateFlow()
    private val sessionCounterDeltas = ArrayDeque<SessionCounterDelta>()
    private val activeStudyQueue = mutableListOf<ReviewPrompt>()
    private val sessionOriginCardIds = linkedSetOf<Long>()
    private var studySessionActive = false
    private var telemetrySessionId: String? = null
    private var promptShownAt: Long = System.currentTimeMillis()
    private var answerRevealedAt: Long = 0L
    private val failureCounts = mutableMapOf<Long, Int>()
    private val acquisitionSuccesses = mutableMapOf<Long, Int>()
    private val responseSamples = mutableListOf<Pair<Long, Boolean>>()
    private val objectiveAttempts = mutableListOf<ObjectiveAttempt>()
    private var sessionStartedAt: Long = 0L
    private var fatigueAdjusted = false
    private var scheduledReadingPresented = false
    private var readingCommitInProgress = false
    private var queueBeforeLastReview: List<ReviewPrompt>? = null
    private val sessionShownNotes = mutableListOf<Long>()
    private val sessionShownHard = mutableListOf<Boolean>()
    private val lapsedShownAt = mutableMapOf<Long, Int>()
    private var flowOffered = false
    private val nextCardBandit = ContextualBandit(dimensions = 6)

    init {
        viewModelScope.launch {
            // Never let a startup error (bad import, transient DB issue) leave the
            // app stuck on a blank screen with no feedback.
            runCatching {
                repository.banditArmStates().let(nextCardBandit::restore)
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

    fun setDoctrine(value: Doctrine) {
        settings.doctrine = value
        mutableState.value = mutableState.value.copy(doctrineSetting = settings.doctrine)
        viewModelScope.launch { loadSession() }
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
        mutableTypedAnswer.value = value
    }

    fun setCorrectionAnswer(value: String) {
        mutableState.value = mutableState.value.copy(correctionAnswer = value, answerFeedback = null)
    }

    fun submitCorrection() {
        val state = mutableState.value
        val prompt = state.prompt ?: return
        if (!state.correctionRequired || state.correctionAccepted) return
        val evaluation = evaluatePromptAnswer(prompt, state.correctionAnswer)
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
        mutableTypedAnswer.value = value
        reveal()
    }

    fun reveal() {
        val state = mutableState.value
        // Close double-tap and IME/button races synchronously. A committed production
        // miss writes to FSRS, so processing one physical attempt twice is data loss.
        if (state.revealed || state.ratingInProgress || state.autoRatedAgain) return
        val prompt = state.prompt ?: return
        answerRevealedAt = System.currentTimeMillis()
        val typed = mutableTypedAnswer.value
        val evaluation = evaluatePromptAnswer(prompt, typed)
        mutableState.value = mutableState.value.copy(
            revealed = true,
            isAnswerCorrect = evaluation.accepted,
            answerMatch = evaluation.match,
            answerFeedback = if (evaluation.accepted) evaluation.message else diagnosticFeedbackFor(prompt, typed) ?: evaluation.message,
            suggestedRating = suggestedRating(evaluation, prompt, System.currentTimeMillis() - promptShownAt),
            feedbackSequence = mutableState.value.feedbackSequence + 1,
            feedbackCorrect = evaluation.accepted
        )
        viewModelScope.launch {
            repository.recordTelemetry(telemetryForPrompt("answer_revealed", prompt).copy(
                answerMatch = evaluation.match.name,
                responseMs = (System.currentTimeMillis() - promptShownAt).coerceAtLeast(0),
                wasRevealed = true,
                typedLength = typed.length
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
                com.sibirskyspeak.data.CardType.SENTENCE_BUILD
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
                    repository.review(prompt.card, Rating.AGAIN, objectiveCorrect = false)
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
                        handleFailure(prompt, recordSample = true)
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
        // Practice misses do not touch FSRS, but override still needs to restore the
        // queue before the miss inserted a scaffold.
        queueBeforeLastReview = activeStudyQueue.toList()
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
            sessionCounterDeltas.addLast(SessionCounterDelta(reviewed = 0, correct = 0))
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
                repository.review(prompt.card, rating, objectiveCorrect = if (countable) wasCorrect else null)
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
                        handleFailure(prompt, recordSample = false)
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

    private suspend fun handleFailure(prompt: ReviewPrompt, recordSample: Boolean) {
        val failures = (failureCounts[prompt.card.id] ?: 0) + 1
        failureCounts[prompt.card.id] = failures
        val repair = if (failures >= 2) repository.scaffoldPromptFor(prompt.card, failures) else repository.repairPromptFor(prompt.card)
        advanceFrozenQueue(prompt, Rating.AGAIN, repair)
        if (recordSample) recordResponseSample(prompt, Rating.AGAIN)
        if (failures >= 2) repository.recordTelemetry(telemetryForPrompt("scaffold_inserted", prompt).copy(
            metadataJson = JSONObject().put("supportLevel", failures).toString()
        ))
        if (failures >= 2) repository.recordTelemetry(telemetryForPrompt("hint_used", prompt).copy(
            metadataJson = JSONObject().put("level", failures).toString()
        ))
    }

    private fun rateUnscheduledPrompt(prompt: ReviewPrompt, rating: Rating) {
        if (mutableState.value.ratingInProgress) return
        repository.clearUndo()
        queueBeforeLastReview = if (rating == Rating.AGAIN) activeStudyQueue.toList() else null
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
            if (!success) sessionCounterDeltas.addLast(SessionCounterDelta(reviewed = 0, correct = 0))
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
        val engineJudgedCorrect = mutableState.value.isAnswerCorrect
        val sampledCorrect = engineJudgedCorrect ?: (rating != Rating.AGAIN)
        responseSamples += elapsed to sampledCorrect
        if (engineJudgedCorrect != null) {
            objectiveAttempts += ObjectiveAttempt(
                itemId = prompt.card.id,
                correct = engineJudgedCorrect,
                responseMs = elapsed,
                answerMode = prompt.answerMode,
                itemDifficulty = 25.0 + when (prompt.card.cardType) {
                    CardType.MEANING_TO_RU, CardType.DICTATION, CardType.SPEAK -> 2.0
                    CardType.RU_TO_MEANING, CardType.ASPECT_SELECT, CardType.GENDER_ID -> -1.0
                    else -> 0.0
                }
            )
        }
        val priorFatigue = FatigueModel.estimate(responseSamples.dropLast(1).map { it.first }, responseSamples.dropLast(1).map { it.second })
        val currentFatigue = FatigueModel.estimate(responseSamples.map { it.first }, responseSamples.map { it.second })
        if (engineJudgedCorrect != null) {
            viewModelScope.launch {
                repository.resolveBanditCredits(
                    itemId = prompt.card.id,
                    recalled = engineJudgedCorrect,
                    responseMs = elapsed,
                    fatigueDelta = (currentFatigue - priorFatigue).coerceAtLeast(0.0),
                    currentShowAt = promptShownAt
                ).forEach { credit ->
                    nextCardBandit.update(credit.action, credit.context, credit.reward)
                    repository.upsertBanditArmState(nextCardBandit.snapshot().first { it.action == credit.action })
                }
            }
        }
        sessionShownNotes += prompt.note.id
        sessionShownHard += prompt.answerMode.isHardProduction()
        if (rating == Rating.AGAIN) lapsedShownAt[prompt.card.id] = mutableState.value.sessionCompletedCards
        if (!fatigueAdjusted && responseSamples.size >= 4) {
            val fatigue = FatigueModel.estimate(responseSamples.map { it.first }, responseSamples.map { it.second })
            if (fatigue >= 0.65) {
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
                    statusMessage = if (removed > 0) "Good place to stop: optional new material moved to tomorrow. Finish on the next easy win." else "Good place to stop: no more new material this sitting."
                )
                viewModelScope.launch { repository.recordTelemetry(TelemetryEvent(
                    eventType = "fatigue_adjustment", sessionId = telemetrySessionId,
                    metadataJson = JSONObject().put("removedNew", removed).put("variant", settings.learningExperimentVariant).toString()
                )) }
            }
        }
        if (!flowOffered && responseSamples.size >= 6) {
            val live = LiveSessionState(recent = responseSamples.takeLast(4))
            if (live.flow == com.sibirskyspeak.learning.FlowState.FLOW) {
                flowOffered = true
                mutableState.value = mutableState.value.copy(statusMessage = "You're in flow. Finish this set, then Stretch is available if you want it.")
                viewModelScope.launch { repository.recordTelemetry(TelemetryEvent(
                    eventType = "flow_stretch_offered", sessionId = telemetrySessionId,
                    metadataJson = JSONObject().put("rollingAccuracy", 1.0).toString()
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
        // Multiple-choice speed is weak evidence: a fast correct tap can be a guess.
        if (prompt.answerMode == AnswerMode.CHOICE) return Rating.GOOD
        return if (prompt.card.reps > 0 && elapsedMs <= slowAt / 3) Rating.EASY else Rating.GOOD
    }

    private fun evaluatePromptAnswer(prompt: ReviewPrompt, actual: String): AnswerEvaluation =
        when (prompt.answerMode) {
            AnswerMode.ENGLISH -> evaluateEnglishAnswer(prompt.expectedAnswer, actual)
            AnswerMode.RUSSIAN_TYPED, AnswerMode.AUDIO_ONLY, AnswerMode.SPEAK -> evaluateRussianAnswer(
                expected = prompt.expectedAnswer,
                actual = actual,
                allowTypos = prompt.card.cardType !in STRICT_FORM_CARD_TYPES
            )
            AnswerMode.RUSSIAN_STRESS_TYPED -> evaluateRussianAnswer(prompt.expectedAnswer, actual, ignoreStress = false)
            AnswerMode.CHOICE -> {
                if (prompt.card.cardType == CardType.STRESS_MARK) {
                    evaluateRussianAnswer(prompt.expectedAnswer, actual, ignoreStress = false)
                } else {
                    val correct = actual.trim().equals(prompt.expectedAnswer.trim(), ignoreCase = true)
                    AnswerEvaluation(if (correct) AnswerMatch.EXACT else AnswerMatch.WRONG, prompt.expectedAnswer)
                }
            }
            AnswerMode.LESSON -> AnswerEvaluation(AnswerMatch.EXACT, prompt.expectedAnswer)
        }

    private fun advanceFrozenQueue(prompt: ReviewPrompt, rating: Rating, repairPrompt: ReviewPrompt? = null) {
        if (!studySessionActive) return
        val updated = recoveryQueueAfter(activeStudyQueue, prompt, rating, repairPrompt).toMutableList()
        val blueprint = mutableState.value.sessionPlan?.blueprint
        if (blueprint != null && updated.isNotEmpty()) {
            val live = LiveSessionState(
                shown = mutableState.value.sessionCompletedCards + 1,
                recent = responseSamples.takeLast(4),
                recentNoteIds = sessionShownNotes.takeLast(4),
                recentHard = sessionShownHard.takeLast(4),
                lapsedShownAt = lapsedShownAt,
                // Repository gating already removed locked drills; treating every
                // remaining concept as introduced preserves that hard constraint.
                introducedConcepts = updated.mapNotNull { it.card.gramConcept }.toSet()
            )
            val context = banditContext()
            val next = NextCardSelector.select(
                updated, blueprint, live, System.currentTimeMillis(),
                mutableState.value.sessionPlan?.confusablePairs.orEmpty(),
                policyBias = { candidate ->
                nextCardBandit.score(candidate.answerMode.name, context) * 0.25
            })
            val pace = mutableState.value.sessionPlan?.pace
            val fatigue = FatigueModel.estimate(responseSamples.map { it.first }, responseSamples.map { it.second })
            when (SessionMpcController.decide(
                hasCard = next != null,
                live = live,
                inputs = MpcInputs(
                    fatigue = fatigue,
                    debtRatio = pace?.debtRatio ?: 0.0,
                    debtLimit = 0.35,
                    pReturn = pace?.pReturn ?: 0.8,
                    stretchAlreadyOffered = flowOffered
                )
            )) {
                MpcAction.STOP -> {
                    updated.clear()
                    mutableState.value = mutableState.value.copy(statusMessage = "Clean finish: today's marginal gain is lower than protecting tomorrow.")
                }
                MpcAction.STRETCH -> {
                    val existing = updated.mapTo(HashSet()) { it.card.id }
                    val additions = (mutableState.value.sessionPlan?.blockedGrammar.orEmpty() + mutableState.value.sessionPlan?.interleavedGrammar.orEmpty())
                        .filter { it.card.id !in existing }
                        .distinctBy { it.card.id }
                        .take(3)
                    updated += additions
                    flowOffered = true
                    mutableState.value = mutableState.value.copy(statusMessage = "Stretch earned: a short transfer block was added while accuracy and energy are high.")
                    next?.let { updated.remove(it); updated.add(0, it) }
                }
                MpcAction.CARD -> next?.let { updated.remove(it); updated.add(0, it) }
            }
        }
        activeStudyQueue.clear()
        activeStudyQueue += updated
    }

    private fun banditContext(): DoubleArray {
        val recent = responseSamples.takeLast(4)
        val accuracy = if (recent.isEmpty()) 0.85 else recent.count { it.second }.toDouble() / recent.size
        val latency = if (recent.isEmpty()) 0.5 else (recent.map { it.first }.average() / 15_000.0).coerceIn(0.0, 2.0)
        val progress = mutableState.value.let { state -> state.sessionProgressCompleted.toDouble() / state.sessionProgressTotal.coerceAtLeast(1) }
        return doubleArrayOf(1.0, accuracy, latency, progress, if (fatigueAdjusted) 1.0 else 0.0, if (flowOffered) 1.0 else 0.0)
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
            typedLength = mutableTypedAnswer.value.length,
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
        val actualRecall = mutableState.value.answerMatch?.let { it != AnswerMatch.WRONG }
        if (actualRecall != null) {
            repository.recordSuccessCalibrationSample(
                card = prompt.card,
                correct = actualRecall,
                fatigue = FatigueModel.estimate(responseSamples.map { it.first }, responseSamples.map { it.second }),
                at = committedAt
            )
        }
        if (actualRecall != null && !autoRated) {
            val predictedRecall = rating != Rating.AGAIN
            repository.recordTelemetry(telemetryForPrompt("calibration_sample", prompt).copy(
                rating = rating.name,
                answerMatch = mutableState.value.answerMatch?.name,
                responseMs = responseMs,
                metadataJson = JSONObject()
                    .put("predictedRecall", predictedRecall)
                    .put("actualRecall", actualRecall)
                    .put("calibrated", predictedRecall == actualRecall)
                    .toString()
            ))
        }
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
                    statusMessage = "Reading consolidated. Back to your review queue.",
                    // The frozen plan otherwise keeps offering the assignment that
                    // was just completed on the session-complete screen.
                    sessionPlan = mutableState.value.sessionPlan?.copy(readingAssignment = null)
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
                    mutableState.value = mutableState.value.copy(
                        inSessionReading = false,
                        selectedReaderTextId = null,
                        sessionPlan = mutableState.value.sessionPlan?.copy(readingAssignment = null)
                    )
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
        val loadStartedAt = System.currentTimeMillis()
        val current = mutableState.value
        val canReusePlan = preserveStudyQueue && studySessionActive && activeStudyQueue.isNotEmpty() && current.sessionPlan != null
        val freshPlan = if (canReusePlan) current.sessionPlan!! else repository.sessionPlan()
        // Study can be opened before the asynchronous startup plan is ready. In
        // that race startStudySession() freezes an empty queue; when the plan lands,
        // adopt it here so the visible prompt and the queue cannot diverge.
        if (studySessionActive && !preserveStudyQueue) {
            activeStudyQueue.clear()
            activeStudyQueue += freshPlan.reviewQueue
            if (sessionOriginCardIds.isEmpty()) sessionOriginCardIds += activeStudyQueue.map { it.card.id }
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
        // Re-tokenizing the full reader body is wasteful when nothing about the reader
        // changed — and loadSession runs after every review via the canReusePlan fast
        // path. Reuse the existing tokens then; every reader interaction (open, lookup,
        // mark) recomputes them explicitly, so they can't go stale while reused here.
        val readerTokens = if (canReusePlan) {
            current.readerTokens
        } else {
            selectedReader?.text?.let { repository.readerTokens(it) }.orEmpty()
        }
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
            readerTokens = readerTokens,
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
            doctrineSetting = settings.doctrine,
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
            skillRatings = plan.skillRatings,
            rivalState = plan.rivalState,
            matchHistory = plan.matchHistory,
            newlyUnlocked = if (freshAchievements.isNotEmpty()) {
                freshAchievements
            } else {
                // Drop an overlay if its underlying condition was repaired/reverted
                // (for example, the quality-retirement coverage inflation fix).
                current.newlyUnlocked.filter { it.id in unlockedIds }
            },
            sessionReviewed = current.sessionReviewed,
            sessionCorrect = current.sessionCorrect,
            sessionCompletedCards = current.sessionCompletedCards,
            sessionProgressCompleted = if (studySessionActive) {
                sessionOriginCardIds.count { id -> activeStudyQueue.none { it.card.id == id } }
            } else current.sessionProgressCompleted,
            sessionProgressTotal = if (studySessionActive) sessionOriginCardIds.size else current.sessionProgressTotal,
            inSessionReading = current.inSessionReading,
            readerCheckpointMistakes = current.readerCheckpointMistakes,
            inStudySession = current.inStudySession,
            leeches = current.leeches
        )
        // A rebuilt session always presents a fresh card, so clear any in-progress
        // input (this mirrors the old ReviewUiState rebuild that defaulted it to "").
        mutableTypedAnswer.value = ""
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
            viewModelScope.launch {
                repository.recordBanditExposure(
                    card = nextPrompt.card,
                    action = nextPrompt.answerMode.name,
                    context = banditContext(),
                    showAt = promptShownAt,
                    fatigue = FatigueModel.estimate(responseSamples.map { it.first }, responseSamples.map { it.second })
                )
            }
            if (!nextPrompt.exampleSentence.isNullOrBlank()) {
                repository.recordTelemetry(telemetryForPrompt("context_card_shown", nextPrompt))
            }
            val lessonBody = nextPrompt.lesson?.body.orEmpty()
            if (lessonBody.contains("Cognate fast-track:")) {
                repository.recordTelemetry(telemetryForPrompt("cognate_fasttrack", nextPrompt))
            }
            if (lessonBody.contains("Useful chunks:")) {
                repository.recordTelemetry(telemetryForPrompt("chunk_card", nextPrompt))
            }
        }
        if (preserveStudyQueue && studySessionActive && activeStudyQueue.isEmpty()) {
            // Per-facet retention at session end, so a weak aggregate (e.g. 78%) can be
            // traced to the quiz types actually dragging it down rather than guessed at.
            val retentionByType = JSONObject()
            runCatching {
                repository.retentionByCardType().forEach { row ->
                    retentionByType.put(
                        row.cardType.name,
                        JSONObject().put("n", row.total).put("retained", row.retained)
                    )
                }
            }
            repository.recordTelemetry(TelemetryEvent(
                eventType = "session_complete",
                sessionId = telemetrySessionId,
                sessionRemaining = 0,
                metadataJson = JSONObject()
                    .put("reviewed", mutableState.value.sessionReviewed)
                    .put("correct", mutableState.value.sessionCorrect)
                    .put("actions", mutableState.value.sessionCompletedCards)
                    .put("retentionByCardType", retentionByType)
                    .toString()
            ))
            val fatigue = FatigueModel.estimate(responseSamples.map { it.first }, responseSamples.map { it.second })
            val performance = PerformanceModel.score(objectiveAttempts)
            val effectiveMinutes = PerformanceModel.effectiveMinutes(objectiveAttempts)
            val accuracy = if (responseSamples.isEmpty()) 0.0 else responseSamples.count { it.second }.toDouble() / responseSamples.size
            val rivalPerformance = repository.expectedRivalPerformance(objectiveAttempts.map { it.itemId })
            val report = repository.finishAdaptiveSession(
                goodMinutes = if (accuracy >= 0.8) effectiveMinutes else effectiveMinutes * accuracy,
                fatigue = fatigue,
                debtRatio = mutableState.value.sessionPlan?.pace?.debtRatio ?: 0.0,
                completed = true,
                cleanFinish = !fatigueAdjusted,
                perfYou = performance,
                perfRival = rivalPerformance,
                rankedMatch = objectiveAttempts.size >= MIN_RANKED_MATCH_CARDS
            )
            studySessionActive = false
            mutableState.value = mutableState.value.copy(inStudySession = false, matchReport = report)
        } else if (preserveStudyQueue && studySessionActive) {
            maybeStartScheduledReading()
        }
        // Flag main-thread-relevant slowness on full rebuilds (the cold-start path),
        // now that the heavy work is dispatched off the UI thread by sessionPlan().
        if (!canReusePlan) {
            val loadMs = System.currentTimeMillis() - loadStartedAt
            if (loadMs >= SLOW_LOAD_MS) {
                repository.recordTelemetry(TelemetryEvent(
                    eventType = "slow_load",
                    sessionId = telemetrySessionId,
                    metadataJson = JSONObject()
                        .put("loadMs", loadMs)
                        .put("step", keepStep.name)
                        .toString()
                ))
            }
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
        val workloadPerItem = settings.dailyGoal.toDouble() / stats.noteCount.coerceAtLeast(1)
        val frontier = ReviewControl.optimalRetention(workloadPerItem)
        val target = minOf(settings.desiredRetention.coerceIn(0.85, 0.90), frontier)
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
        // The fit is a grid + golden-section MLE over the full review history —
        // pure CPU; keep it off the main thread (this runs inside loadSession).
        val result = withContext(computeDispatcher) { FsrsWeightFitter.fit(rows, settings.fsrsWeights) }
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
            loadSession(status = null)
            val ready = mutableState.value.sessionPlan?.reviewQueue?.size ?: 0
            val status = when {
                ready > 0 -> "Extra credit: $ready more ${if (ready == 1) "card is" else "cards are"} ready."
                granted == 0 -> "Extra credit is already used for today. Reading is the better next step now."
                else -> "No more eligible cards are ready. Reading is the better next step now."
            }
            mutableState.value = mutableState.value.copy(statusMessage = status)
            // The completion screen belongs to an ended sitting. Freeze the newly
            // granted plan into a fresh session instead of showing live cards under
            // the old counters and telemetry id.
            if (ready > 0 && !studySessionActive) startStudySession()
        }
    }

    /** Reset the per-sitting counters when the learner (re)opens the study screen. */
    fun startStudySession(mode: SessionMode = SessionMode.FULL) {
        if (studySessionActive) return
        sessionCounterDeltas.clear()
        failureCounts.clear()
        acquisitionSuccesses.clear()
        responseSamples.clear()
        objectiveAttempts.clear()
        sessionShownNotes.clear()
        sessionShownHard.clear()
        lapsedShownAt.clear()
        fatigueAdjusted = false
        flowOffered = false
        scheduledReadingPresented = false
        studySessionActive = true
        sessionStartedAt = System.currentTimeMillis()
        telemetrySessionId = UUID.randomUUID().toString()
        promptShownAt = System.currentTimeMillis()
        answerRevealedAt = 0L
        val plan = mutableState.value.sessionPlan
        val base = plan?.reviewQueue.orEmpty()
        val plannedQueue = when (mode) {
            SessionMode.QUICK -> {
                val atRisk = plan?.blueprint?.atRiskCardIds.orEmpty()
                base.filter { it.card.id in atRisk }.ifEmpty { base.filter { it.card.state != com.sibirskyspeak.data.CardState.NEW } }
            }
            SessionMode.FULL -> base
            SessionMode.STRETCH -> {
                val ids = base.mapTo(HashSet()) { it.card.id }
                base + (plan?.blockedGrammar.orEmpty() + plan?.interleavedGrammar.orEmpty())
                    .filter { it.card.id !in ids }.distinctBy { it.card.id }.take(5)
            }
        }
        activeStudyQueue.clear()
        activeStudyQueue += plannedQueue
        sessionOriginCardIds.clear()
        sessionOriginCardIds += activeStudyQueue.map { it.card.id }
        mutableState.value = mutableState.value.copy(
            sessionPlan = plan?.copy(
                reviewQueue = plannedQueue,
                blueprint = plan.blueprint?.copy(mode = mode)
            ),
            prompt = activeStudyQueue.firstOrNull(),
            sessionReviewed = 0,
            sessionCorrect = 0,
            sessionCompletedCards = 0,
            sessionProgressCompleted = 0,
            sessionProgressTotal = sessionOriginCardIds.size,
            correctionRequired = false,
            correctionAnswer = "",
            correctionAccepted = false,
            fatigueAdjusted = false,
            matchReport = null,
            inStudySession = true
        )
        viewModelScope.launch {
            repository.observeReturn(sessionStartedAt)
            plan?.pace?.let { repository.recordPace(it, mode, sessionStartedAt) }
            activeStudyQueue.firstOrNull()?.let { first ->
                repository.recordBanditExposure(
                    card = first.card,
                    action = first.answerMode.name,
                    context = banditContext(),
                    showAt = promptShownAt,
                    fatigue = 0.0
                )
            }
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
                    .put("mode", mode.name)
                    .toString()
            ))
            maybeStartScheduledReading()
        }
    }

    /** Keep the frozen queue resumable, but make pauses visible in telemetry. */
    fun recordStudyScreenExit() {
        if (!studySessionActive) return
        viewModelScope.launch {
            repository.recordTelemetry(TelemetryEvent(
                eventType = "session_paused",
                sessionId = telemetrySessionId,
                sessionRemaining = activeStudyQueue.size,
                metadataJson = JSONObject()
                    .put("reviewed", mutableState.value.sessionReviewed)
                    .put("actions", mutableState.value.sessionCompletedCards)
                    .toString()
            ))
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
                val refreshed = when {
                    prompt.supportOnly -> repository.scaffoldPromptFor(prompt.card, prompt.supportLevel)
                    prompt.practiceOnly -> repository.practicePromptFor(prompt.card, round = 1)
                    else -> repository.promptForCard(prompt.card)
                }?.copy(
                    queueReason = prompt.queueReason,
                    supportOnly = prompt.supportOnly,
                    practiceOnly = prompt.practiceOnly,
                    supportLevel = prompt.supportLevel
                )
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
