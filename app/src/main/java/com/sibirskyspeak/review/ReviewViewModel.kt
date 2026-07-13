package com.sibirskyspeak.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sibirskyspeak.data.Achievement
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.ContentProvenance
import com.sibirskyspeak.data.DashboardStats
import com.sibirskyspeak.data.DailyPlan
import com.sibirskyspeak.data.MatchHistory
import com.sibirskyspeak.data.LearningRepository
import com.sibirskyspeak.data.ReviewTransactionService
import com.sibirskyspeak.data.SessionPlanner
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReaderRecommendation
import com.sibirskyspeak.data.ReaderBookmark
import com.sibirskyspeak.data.ReadingActivity
import com.sibirskyspeak.data.ReaderToken
import com.sibirskyspeak.data.SessionPlan
import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.data.StudyClock
import com.sibirskyspeak.data.SkillRating
import com.sibirskyspeak.data.RivalState
import com.sibirskyspeak.data.TelemetryEvent
import com.sibirskyspeak.data.WordStatus
import com.sibirskyspeak.data.WeeklyReport
import com.sibirskyspeak.scheduler.FsrsScheduler
import com.sibirskyspeak.scheduler.FsrsWeightFitter
import com.sibirskyspeak.learning.LiveSessionState
import com.sibirskyspeak.learning.NextCardSelector
import com.sibirskyspeak.learning.isHardProduction
import com.sibirskyspeak.learning.ReviewControl
import com.sibirskyspeak.learning.ContextualBandit
import com.sibirskyspeak.learning.CardPedagogy
import com.sibirskyspeak.learning.FatigueModel
import com.sibirskyspeak.learning.MatchReport
import com.sibirskyspeak.learning.ObjectiveAttempt
import com.sibirskyspeak.learning.PerformanceModel
import com.sibirskyspeak.learning.MpcAction
import com.sibirskyspeak.learning.MpcInputs
import com.sibirskyspeak.learning.SessionMpcController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Exception details belong in telemetry/logs, never in learner-facing copy. */
private fun safeUserMessage(error: Throwable, fallback: String): String = when (error) {
    is java.io.IOException -> "$fallback Check your storage and try again."
    is IllegalArgumentException -> "$fallback Check the entered data and try again."
    else -> fallback
}

private val STRICT_FORM_CARD_TYPES = setOf(
    CardType.CASE_FILL,
    CardType.ADJ_AGREE,
    CardType.VERB_FORM,
    CardType.CONCEPT_DRILL
)

/** Objective responses must not silently become self-rated successes after a miss.
 * Recognition cards and ASR pronunciation practice remain self-rated; typed/choice
 * and audio-to-form tasks are directly gradeable and commit a miss immediately. */
internal fun shouldAutoCommitMiss(prompt: ReviewPrompt): Boolean = when (prompt.answerMode) {
    AnswerMode.CHOICE,
    AnswerMode.RUSSIAN_TYPED,
    AnswerMode.RUSSIAN_STRESS_TYPED,
    AnswerMode.AUDIO_ONLY -> true
    AnswerMode.ENGLISH,
    AnswerMode.SPEAK,
    AnswerMode.LESSON -> false
}

enum class SessionStep {
    REVIEWS,
    RULE,
    BLOCKED,
    INTERLEAVED,
    READER,
    IMPORT,
    DASHBOARD,
    LAB
}

/** One-session learner override; never persisted into the adaptive model. */
enum class TemporarySessionMode {
    BALANCED,
    REVIEWS_ONLY,
    RECOVERY,
    READER_ONLY,
    FOCUS
}

data class ReviewUiState(
    val fluencyForecast: com.sibirskyspeak.learning.FluencySimEngine.SimResult? = null,
    /** Goal-vs-projection gap, recomputed alongside [fluencyForecast] (same daily
     * throttle) — null when no active goal exists. */
    val goalStatus: com.sibirskyspeak.learning.GoalStatus? = null,
    /** True for one weekly check after the goal first turns OFF_TRACK — surfaces
     * the three-way agency fork (raise commitment / push back date / drop goal)
     * instead of silently grinding or silently letting the goal lapse. */
    val showGoalOffTrackPrompt: Boolean = false,
    // Pull-to-refresh's own transient flag — deliberately NOT threaded through
    // loadSession()'s full-rebuild ReviewUiState(...) constructor (unlike
    // fluencyForecast, which needed fixing for the opposite reason). This one
    // should reset to false as soon as that same reload lands, which "not
    // preserved across a full rebuild" already gives for free.
    val isRefreshing: Boolean = false,
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
    val readerBookmarks: List<ReaderBookmark> = emptyList(),
    val readerHistory: List<ReadingActivity> = emptyList(),
    val selectedToken: ReaderToken? = null,
    val dashboardStats: DashboardStats? = null,
    val lookupResult: String? = null,
    val importText: String = "",
    val exportText: String = "",
    val readerTitle: String = "",
    val readerBody: String = "",
    val readerSource: String = "local",
    val selectedReaderTextId: Long? = null,
    val readerProgressByText: Map<Long, Int> = emptyMap(),
    val readerLookupInProgress: Boolean = false,
    val statusMessage: String? = null,
    val sessionStep: SessionStep = SessionStep.REVIEWS,
    val ratingInProgress: Boolean = false,
    val autoRatedAgain: Boolean = false,
    val suggestedRating: Rating? = null,
    val correctionRequired: Boolean = false,
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
    val goalTargetLevelSetting: String = "",
    val goalTargetDateEpochDaySetting: Long = Long.MIN_VALUE,
    /** Live (arithmetic-only) feasibility preview while the Settings goal sliders
     * are being dragged — never triggers a fresh simulation. */
    val goalFeasibilityPreview: com.sibirskyspeak.learning.GoalFeasibility? = null,
    val adaptiveEnabled: Boolean = true,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = SettingsStore.DEFAULT_REMINDER_HOUR,
    val readerFontScale: Float = 1.0f,
    val backupTreeUri: String = "",
    val automaticPublicBackupEnabled: Boolean = true,
    val externalBackupEncryptionConfigured: Boolean = false,
    val backupRecoveryKey: String? = null,
    val contentProvenance: List<ContentProvenance> = emptyList(),
    val backupLastSuccessAt: Long = 0L,
    val backupLastSizeBytes: Long = 0L,
    val backupLastValidatedAt: Long = 0L,
    val backupLastDurableAt: Long = 0L,
    val importPreview: com.sibirskyspeak.data.ImportPreview? = null,
    val restDayCredits: Int = 0,
    val weeklyReports: List<WeeklyReport> = emptyList(),
    // P6.4 monthly checkpoint (Lab): an independent assessment session that
    // writes no FSRS state at all. checkpointSession is non-null exactly while
    // one is in progress; checkpointCalibration is the historical predicted-vs-
    // observed curve shown once at least one result exists.
    val checkpointSession: com.sibirskyspeak.data.CheckpointSession? = null,
    val checkpointIndex: Int = 0,
    val checkpointResults: List<Boolean> = emptyList(),
    val checkpointFeedback: String? = null,
    val checkpointCalibration: List<com.sibirskyspeak.data.CalibrationBucket> = emptyList(),
    val skeletonReady: Boolean = false,
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
    val sessionStoppedEarly: Boolean = false,
    val stoppedQueueRemaining: Int = 0,
    val temporarySessionMode: TemporarySessionMode = TemporarySessionMode.BALANCED,
    val session: SessionState = SessionState(),
    val showOnboarding: Boolean = true,
    // Parked leeches available to fix or release, for the Leeches management view.
    val leeches: List<LeechItem> = emptyList(),
    // Placement quiz (Settings > Study > Placement): a short fixed staircase that
    // estimates a starting level instead of making the learner guess at the manual
    // "After X" buttons.
    val placementActive: Boolean = false,
    val placementQuestionIndex: Int = 0,
    val placementAnswers: List<Boolean> = emptyList(),
    val placementCompleted: Boolean = false,
    // The suggested level once completed=true, or null meaning "no placement —
    // start from the true beginning" (distinct from "quiz not finished yet").
    val placementResult: String? = null,
    // Phase G6 / P6.5: "Unit N complete — quick check?" offer shown on the
    // session-complete screen once a unit first crosses the mastery threshold.
    // Never a hard lock — dismissExitTicketOffer/skipExitTicket both let the
    // learner continue with zero friction (see ReviewViewModel.maybeOfferExitTicket).
    val exitTicketOfferUnit: Int? = null,
    val exitTicketOfferBand: String? = null,
    val exitTicketOfferCanDo: String? = null,
    val exitTicketSession: com.sibirskyspeak.data.ExitTicketSession? = null,
    val exitTicketIndex: Int = 0,
    val exitTicketResults: List<Boolean> = emptyList(),
    val exitTicketFeedback: String? = null,
    val exitTicketComplete: Boolean = false,
    // Phase G4: shown once when the bundled curriculum content changed since
    // the last launch (LearningRepository.syncCurriculumManifest already
    // records this on seed; this just surfaces the pending report, if any).
    val curriculumMigrationReport: com.sibirskyspeak.data.CurriculumMigrationReport? = null
)

data class ReaderCheckpointQuestion(
    val russian: String,
    val expected: String,
    val choices: List<String>,
    val noteId: Long? = null,
    val kind: String = "LEXICAL"
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

@dagger.hilt.android.lifecycle.HiltViewModel
class ReviewViewModel @javax.inject.Inject constructor(
    private val repository: LearningRepository,
    private val settings: SettingsStore,
    // Dispatcher for CPU-bound work (the FSRS weight fit). Injectable so tests can
    // pass a deterministic test dispatcher instead of the real Default pool; the
    // production binding lives in di/AppModule.kt.
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val reviewTransactions: ReviewTransactionService = ReviewTransactionService(repository),
    private val sessionPlanner: SessionPlanner = SessionPlanner(repository),
    private val clock: StudyClock = StudyClock()
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        ReviewUiState(
            showOnboarding = !settings.onboardingCompleted,
            externalBackupEncryptionConfigured = repository.isExternalBackupEncryptionConfigured()
        )
    )
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()
    // The in-progress typed answer lives in its own flow, not in ReviewUiState, so a
    // keystroke doesn't re-emit the whole 60-field state and recompose the entire
    // screen. Only the answer-input subtree (which collects this) recomposes while
    // typing. Reset to "" whenever a fresh card is shown (see loadSession).
    private val mutableTypedAnswer = MutableStateFlow("")
    val typedAnswer: StateFlow<String> = mutableTypedAnswer.asStateFlow()
    // Same rationale as typedAnswer above: the post-miss correction field updates on
    // every keystroke, so it lives outside ReviewUiState too. Reset to "" everywhere
    // correctionRequired is cleared or a fresh card is shown.
    private val mutableCorrectionAnswer = MutableStateFlow("")
    val correctionAnswer: StateFlow<String> = mutableCorrectionAnswer.asStateFlow()
    private val sessionTracking = SessionTrackingStateHolder()
    private val sessionCounterDeltas get() = sessionTracking.counterDeltas
    private val activeStudyQueue get() = sessionTracking.activeQueue
    // Review actions, lifecycle restores, and explicit refreshes can all request a
    // plan reload. Serializing them prevents an older completion reload from
    // publishing a prompt after a newer session has already started.
    private val sessionLoadMutex = Mutex()
    private val sessionOriginCardIds get() = sessionTracking.originCardIds
    private var sessionState
        get() = sessionTracking.sessionState
        set(value) { sessionTracking.sessionState = value }
    // A re-entrancy guard: "is a session claimed right now," not "is there a session to
    // show" (that's ReviewUiState.inStudySession) and not "is the UI currently displaying
    // the study screen" (that's MainActivity's local `studyActive`). All three track
    // related but distinct things and must not be collapsed into one — see the comment on
    // `studyActive` in MainActivity.kt for the two safe patterns for setting that one.
    // Must be set true synchronously, before any suspending work, by every function that
    // checks it (see startStudySession/debugStartSessionWithCardType) — setting it only
    // after an await lets two rapid calls both pass the guard.
    private var studySessionActive = false
    private var temporarySessionMode = TemporarySessionMode.BALANCED
    private var telemetrySessionId: String? = null
    private var promptShownAt: Long = clock.now()
    private var studyPausedAt: Long? = null
    private var answerRevealedAt: Long = 0L
    private val failureCounts = mutableMapOf<Long, Int>()
    private val acquisitionSuccesses = mutableMapOf<Long, Int>()
    // Consecutive fast (<= FAST_RECALL_MS), correct acquisition-practice recalls per
    // card — lets scheduleAcquisitionPractice/rateUnscheduledPrompt stop early on an
    // obviously-already-known word instead of always grinding out acquisitionTarget()
    // reps regardless of how confidently the learner is answering.
    private val acquisitionFastStreak = mutableMapOf<Long, Int>()
    private val responseSamples = mutableListOf<Pair<Long, Boolean>>()
    private val objectiveAttempts = mutableListOf<ObjectiveAttempt>()
    private var sessionStartedAt: Long = 0L
    private var fatigueAdjusted = false
    private var scheduledReadingPresented = false
    private var readingCommitInProgress = false
    private var queueBeforeLastReview: List<ReviewPrompt>? = null
    private val sessionShownNotes = mutableListOf<Long>()
    private val sessionShownHard = mutableListOf<Boolean>()
    private val sessionShownTypes = mutableListOf<CardType>()
    private val lapsedShownAt = mutableMapOf<Long, Int>()
    // Cards SessionMpcController.decide has already returned MpcAction.GRACE for (see
    // its handling in advanceFrozenQueue) — caps the grace to a single attempt per card
    // per session so a card that keeps failing still gets deferred instead of holding
    // the session open indefinitely. Fed back into MpcInputs.justFailedUngracedCardId
    // so the controller itself never re-grants a card that's already had its one extra
    // rep.
    private val graceGrantedCardIds = mutableSetOf<Long>()
    // Phase G3: how many times each ErrorCategory has recurred this sitting —
    // in-memory only, reset per session like the other per-sitting maps above.
    // Drives immediateRepairIfRecurring's deterministic category->CardType
    // repair injection, distinct from P4.5's DB-persisted, cross-session
    // confusion-pair reordering (applyContrastivePairing/applyInterferenceSeeding).
    private val sessionErrorCategoryCounts = mutableMapOf<com.sibirskyspeak.review.ErrorCategory, Int>()
    // Units the learner has already been offered an exit ticket for this app
    // session (skipped or completed) — in-memory only, so a skip never persists
    // as a permanent block; the offer can resurface on a future app launch if the
    // learner still hasn't taken it. Populated lazily from recorded results too.
    private val exitTicketOfferedUnits = mutableSetOf<String>()
    private var flowOffered = false
    // Confidence-rebuild window state (see MpcAction.RECOVER / SessionMpcController):
    // >0 means the next NextCardSelector.select() call should bias toward
    // high-success-probability material; recoveryAttempted latches true once a
    // window has run its course this sitting, so persistent struggle escalates to
    // STOP rather than re-triggering RECOVER forever.
    private var recoveryWindowRemaining = 0
    private var recoveryAttempted = false
    private var lastPauseSignature: String? = null
    private val nextCardBandit = ContextualBandit(dimensions = 6)

    init {
        viewModelScope.launch {
            // Never let a startup error (bad import, transient DB issue) leave the
            // app stuck on a blank screen with no feedback.
            runCatching {
                repository.banditArmStates().let(nextCardBandit::restore)
                repository.seedIfEmpty(runMaintenance = false)
                val skeletonIds = settings.planSkeletonCardIds.split(',').mapNotNull(String::toLongOrNull)
                val skeleton = repository.promptsForCardIds(skeletonIds)
                if (skeleton.isNotEmpty()) {
                    // Paint a card immediately so the Study screen never opens to a
                    // spinner, WITHOUT marking the session as actually started: doing
                    // that here (as this used to) sets state.inStudySession = true
                    // before the user has tapped anything, which makes
                    // StudySessionScreen's `if (!state.inStudySession) onStartSession()`
                    // gate silently skip the real startStudySession() call — so the
                    // session telemetry and pace_log recording never run for that
                    // sitting. loadSession() below still adopts this skeleton into
                    // activeStudyQueue once startStudySession() actually flips
                    // studySessionActive (see the race note on loadSession's
                    // preserveStudyQueue param), so nothing here is wasted.
                    mutableState.value = mutableState.value.copy(prompt = skeleton.first(), skeletonReady = true, sessionProgressTotal = skeleton.size)
                }
                val restored = restoreSessionIfAvailable()
                loadSession(preserveStudyQueue = restored, includeReaderInsights = false)
                repository.pendingCurriculumMigrationReport()?.let { report ->
                    mutableState.value = mutableState.value.copy(curriculumMigrationReport = report)
                    runCatching {
                        repository.recordTelemetry(TelemetryEvent(
                            eventType = "curriculum_migration_shown",
                            metadataJson = JSONObject()
                                .put("fromVersion", report.fromVersion ?: "unknown")
                                .put("toVersion", report.toVersion)
                                .put("appeared", report.appeared)
                                .put("retired", report.retired)
                                .toString()
                        ))
                    }
                }
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    statusMessage = safeUserMessage(error, "Couldn't load your session.")
                )
                runCatching {
                    repository.recordTelemetry(TelemetryEvent(
                        eventType = "startup_error",
                        metadataJson = JSONObject()
                            .put("type", error::class.java.name)
                            .put("message", error.message ?: "unknown error")
                            .put("origin", error.stackTrace.firstOrNull()?.toString())
                            .toString()
                    ))
                }
            }
            // Reader coverage is useful enrichment, but it must never block the
            // interactive plan. Whole-deck repair/maintenance is intentionally not
            // launched inline above: even on a background dispatcher its database
            // churn and allocations can stall first interaction on large
            // real-world decks. It still needs to run once per launch so new
            // content syncs and cleanups reach existing installs, so it's deferred
            // the same way reader coverage is.
            viewModelScope.launch(computeDispatcher) {
                // Let Compose finish first layout and accept input before competing
                // for CPU/memory bandwidth with whole-library reader coverage.
                delay(2_000)
                runCatching { refreshReaderInsights() }
                runCatching { repository.performLaunchMaintenance() }
            }
            // Backups are written after completed sessions. Materializing an 80MB
            // full-state JSON snapshot during first-launch seeding competes with
            // bootstrap parsing and can exhaust the normal Android heap.
        }
    }

    /** Rebuilds a compact session checkpoint from durable card ids after process death. */
    private suspend fun restoreSessionIfAvailable(): Boolean {
        val saved = SessionState.fromJson(settings.sessionSnapshotJson) ?: return false
        val prompts = repository.recoverablePromptsForCardIds(saved.queueCardIds)
        if (prompts.isEmpty()) {
            settings.sessionSnapshotJson = ""
            return false
        }
        activeStudyQueue.clear()
        activeStudyQueue += prompts
        sessionOriginCardIds.clear()
        sessionOriginCardIds += saved.queueCardIds
        sessionState = saved.copy(
            phase = SessionPhase.ANSWERING,
            queueCardIds = prompts.map { it.card.id },
            currentCardId = prompts.first().card.id
        )
        studySessionActive = true
        telemetrySessionId = saved.sessionId ?: UUID.randomUUID().toString()
        sessionStartedAt = saved.startedAt.takeIf { it > 0L } ?: clock.now()
        mutableState.value = mutableState.value.copy(
            prompt = prompts.first(),
            inStudySession = true,
            session = sessionState,
            sessionReviewed = saved.reviewed,
            sessionCorrect = saved.correct,
            sessionCompletedCards = saved.completedActions,
            sessionProgressTotal = sessionOriginCardIds.size,
            statusMessage = "Session restored. Answer this card again to continue safely."
        )
        // A restored prompt is intentionally shown unrevealed. This prevents a process
        // restart after reveal from bypassing the answer/commit boundary.
        mutableTypedAnswer.value = ""
        mutableCorrectionAnswer.value = ""
        return true
    }

    private fun dispatchSession(event: SessionEvent) {
        sessionState = SessionReducer.reduce(sessionState, event)
        mutableState.value = mutableState.value.copy(session = sessionState)
        settings.sessionSnapshotJson = if (sessionState.isActive) sessionState.toJson() else ""
    }

    private fun syncSessionQueueFromDurablePlan() {
        if (!studySessionActive || activeStudyQueue.isEmpty()) return
        sessionState = sessionState.copy(
            phase = if (sessionState.phase == SessionPhase.PAUSED) SessionPhase.PAUSED else SessionPhase.ANSWERING,
            queueCardIds = activeStudyQueue.map { it.card.id },
            currentCardId = activeStudyQueue.first().card.id
        )
        settings.sessionSnapshotJson = sessionState.toJson()
    }

    fun completeOnboarding() {
        settings.onboardingCompleted = true
        mutableState.value = mutableState.value.copy(showOnboarding = false)
    }

    /** Write a full-state backup at most once per day, on a background dispatcher. */
    private fun maybeBackup(force: Boolean = false) {
        val now = clock.now()
        if (!force && now - settings.lastBackupAt < BACKUP_INTERVAL_MS) return
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
        // Retention is an input to the current planner, not only a future-card
        // preference. Rebuild the visible plan after the slider is released so
        // the dashboard immediately reflects the new workload.
        viewModelScope.launch { loadSession(preserveStudyQueue = false) }
    }

    fun setAdaptiveEnabled(value: Boolean) {
        settings.adaptiveEnabled = value
        mutableState.value = mutableState.value.copy(adaptiveEnabled = value)
        viewModelScope.launch { loadSession(preserveStudyQueue = false) }
    }

    /**
     * Live feasibility preview while the goal sliders are being dragged in
     * Settings. Deliberately cheap (arithmetic against the already-cached
     * fluencyForecast.stablePace) — never launches a fresh FluencySimEngine
     * simulation, so it's safe to call on every slider tick.
     */
    fun previewGoalFeasibility(level: String, targetDateEpochDay: Long) {
        viewModelScope.launch {
            val stablePace = mutableState.value.fluencyForecast?.stablePace ?: 0.0
            val feasibility = repository.evaluateGoalFeasibility(level, targetDateEpochDay, stablePace)
            mutableState.value = mutableState.value.copy(goalFeasibilityPreview = feasibility)
        }
    }

    /** Persists the goal the user committed to (not fired on every slider tick —
     * see [previewGoalFeasibility] for the live preview) and refreshes the
     * dashboard's goal-status line immediately rather than waiting for the next
     * daily/weekly throttle. */
    fun commitLearningGoal(level: String, targetDateEpochDay: Long) {
        viewModelScope.launch {
            repository.setLearningGoal(level, targetDateEpochDay)
            val forecast = mutableState.value.fluencyForecast
            val goalStatus = forecast?.let { repository.currentGoalStatus(it) }
            mutableState.value = mutableState.value.copy(
                goalTargetLevelSetting = settings.goalTargetLevel,
                goalTargetDateEpochDaySetting = settings.goalTargetDateEpochDay,
                goalFeasibilityPreview = null,
                goalStatus = goalStatus
            )
        }
    }

    /** "Drop goal" from the off-track agency fork, or from Settings directly. */
    fun abandonLearningGoal() {
        viewModelScope.launch {
            repository.abandonLearningGoal()
            mutableState.value = mutableState.value.copy(
                goalTargetLevelSetting = settings.goalTargetLevel,
                goalTargetDateEpochDaySetting = settings.goalTargetDateEpochDay,
                goalFeasibilityPreview = null,
                goalStatus = null,
                showGoalOffTrackPrompt = false
            )
        }
    }

    /** "Raise commitment" (ack, keep the goal as-is) or "Push back date" (navigates
     * to Settings, MainActivity wires the actual screen switch) from the off-track
     * fork — either way the prompt itself is done until the next weekly check. */
    fun dismissGoalOffTrackPrompt() {
        mutableState.value = mutableState.value.copy(showGoalOffTrackPrompt = false)
    }

    /** Starts adaptive pacing from a clean evidence baseline and opens a fuller,
     * user-configured study allowance for the rest of today. Cards, review history,
     * due ordering, and teach-before-test gates remain untouched. */
    fun resetAdaptivePacing() {
        if (studySessionActive) {
            mutableState.value = mutableState.value.copy(statusMessage = "Finish the current study session before resetting pacing")
            return
        }
        val now = clock.now()
        val offset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val day = (now + offset) / (24L * 60 * 60 * 1000)
        settings.dailyGoal = SettingsStore.DAILY_CARD_TARGET
        settings.sessionSize = SettingsStore.DAILY_CARD_TARGET
        settings.newCardsPerDay = SettingsStore.DAILY_CARD_TARGET
        settings.adaptiveResetAt = now
        settings.adaptiveBoostDay = day
        settings.lastAdaptiveLoadDay = Long.MIN_VALUE
        settings.lastFluencyForecastDay = Long.MIN_VALUE
        settings.sessionSnapshotJson = ""
        viewModelScope.launch {
            repository.resetAdaptivePacing(now)
            loadSession(status = "Adaptive pacing reset; today has a fuller study allowance")
        }
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

    fun setBackupTreeUri(value: String) {
        settings.backupTreeUri = value
        settings.lastBackupAt = 0L
        mutableState.value = mutableState.value.copy(backupTreeUri = value)
        maybeBackup()
    }

    fun setAutomaticPublicBackupEnabled(value: Boolean) {
        settings.automaticPublicBackupEnabled = value
        mutableState.value = mutableState.value.copy(automaticPublicBackupEnabled = value)
        if (value) maybeBackup(force = true)
    }

    fun setTemporarySessionMode(mode: TemporarySessionMode) {
        if (studySessionActive) return
        temporarySessionMode = mode
        mutableState.value = mutableState.value.copy(temporarySessionMode = mode)
    }

    fun configureExternalBackupEncryption(password: String) {
        viewModelScope.launch {
            runCatching { repository.configureExternalBackupEncryption(password) }
                .onSuccess { recovery ->
                    mutableState.value = mutableState.value.copy(
                        externalBackupEncryptionConfigured = true,
                        backupRecoveryKey = recovery,
                        statusMessage = "Encrypted external backups enabled. Save the recovery key before leaving this screen."
                    )
                    maybeBackup(force = true)
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        statusMessage = safeUserMessage(error, "Could not enable encrypted backups.")
                    )
                }
        }
    }

    fun clearExternalBackupEncryption() {
        repository.clearExternalBackupEncryption()
        mutableState.value = mutableState.value.copy(
            externalBackupEncryptionConfigured = false,
            backupRecoveryKey = null,
            statusMessage = "External backups will be written without encryption."
        )
    }

    fun dismissBackupRecoveryKey() {
        mutableState.value = mutableState.value.copy(backupRecoveryKey = null)
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

    fun recordAudioPlayed(prompt: ReviewPrompt) {
        viewModelScope.launch {
            repository.recordTelemetry(telemetryForPrompt("audio_played", prompt).copy(
                metadataJson = pedagogyMetadata(prompt)
                    .put("afterReveal", mutableState.value.revealed)
                    .toString()
            ))
        }
    }

    fun setCorrectionAnswer(value: String) {
        mutableCorrectionAnswer.value = value
        mutableState.value = mutableState.value.copy(answerFeedback = null)
    }

    fun submitCorrection() {
        val state = mutableState.value
        val prompt = state.prompt ?: return
        if (!state.correctionRequired || state.correctionAccepted) return
        val correctionAnswer = mutableCorrectionAnswer.value
        // Correction is a reconstruction check, not a second stress-mark test.
        // Sentence tiles deliberately omit punctuation and combining stress marks,
        // so keep the original card's strictness while grading the correction with
        // the ordinary stress-insensitive Russian normalizer.
        val evaluation = evaluatePromptAnswer(prompt, correctionAnswer, correction = true)
        mutableState.value = state.copy(
            correctionAccepted = evaluation.accepted,
            answerFeedback = if (evaluation.accepted) "Corrected. Retrieve it again when it returns." else "Not yet — rebuild the expected answer.",
            feedbackSequence = state.feedbackSequence + 1,
            feedbackCorrect = evaluation.accepted
        )
        if (evaluation.accepted) dispatchSession(SessionEvent.AcceptCorrection)
        viewModelScope.launch {
            repository.recordTelemetry(telemetryForPrompt("active_correction", prompt).copy(
                answerMatch = evaluation.match.name,
                typedLength = correctionAnswer.length,
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
        answerRevealedAt = clock.now()
        val typed = mutableTypedAnswer.value
        val evaluation = evaluatePromptAnswer(prompt, typed)
        mutableState.value = mutableState.value.copy(
            revealed = true,
            isAnswerCorrect = evaluation.accepted,
            answerMatch = evaluation.match,
            answerFeedback = if (evaluation.accepted) evaluation.message else diagnosticFeedbackFor(prompt, typed) ?: evaluation.message,
            suggestedRating = suggestedRating(evaluation, prompt, clock.now() - promptShownAt),
            feedbackSequence = mutableState.value.feedbackSequence + 1,
            feedbackCorrect = evaluation.accepted
        )
        dispatchSession(SessionEvent.Reveal)
        viewModelScope.launch {
            repository.recordTelemetry(telemetryForPrompt("answer_revealed", prompt).copy(
                answerMatch = evaluation.match.name,
                responseMs = (clock.now() - promptShownAt).coerceAtLeast(0),
                wasRevealed = true,
                typedLength = typed.length
            ))
        }
        if (!evaluation.accepted) {
            classifyAnswer(prompt, typed)?.let { diagnosis ->
                viewModelScope.launch { runCatching { repository.recordConfusionEvent(diagnosis, prompt.card.cardType) } }
                immediateRepairIfRecurring(diagnosis, prompt)
            }
        }
        // A committed miss is auto-graded AGAIN (honest scheduling); a receptive
        // recognition prompt instead reveals and lets the learner self-grade, so a
        // typo or valid synonym never silently becomes an FSRS lapse. Production
        // (typed Russian) is committed. So is any multiple-CHOICE answer: tapping a
        // wrong gender/aspect/stress option IS a commitment and must count as a miss,
        // otherwise grammar drills could be self-graded "Good" after a wrong tap.
        val committedMiss = shouldAutoCommitMiss(prompt)
        if (!evaluation.accepted && committedMiss) {
            if (prompt.practiceOnly) {
                commitPracticeMiss(prompt)
                return
            }
            mutableState.value = mutableState.value.copy(ratingInProgress = true)
            viewModelScope.launch {
                runCatching {
                    queueBeforeLastReview = activeStudyQueue.toList()
                    val exposure = repository.captureSuccessCalibrationExposure(
                        prompt.card,
                        FatigueModel.estimate(responseSamples.map { it.first }, responseSamples.map { it.second }),
                        promptShownAt
                    )
                    reviewTransactions.review(prompt.card, Rating.AGAIN, objectiveCorrect = false) to exposure
                }.onSuccess { (becameLeech, exposure) ->
                    sessionCounterDeltas.addLast(SessionCounterDelta(reviewed = 1, correct = 0))
                    mutableState.value = mutableState.value.copy(
                        ratingInProgress = false,
                        autoRatedAgain = true,
                        sessionReviewed = mutableState.value.sessionReviewed + 1,
                        sessionCompletedCards = mutableState.value.sessionCompletedCards + 1,
                        statusMessage = if (becameLeech) "Parked this card — it kept tripping you up. Find it under Leeches." else mutableState.value.statusMessage
                    )
                    recordReviewTelemetry(prompt, Rating.AGAIN, becameLeech, autoRated = true, calibrationExposure = exposure)
                    if (becameLeech) {
                        advanceFrozenQueue(prompt, Rating.GOOD)
                        dispatchSession(SessionEvent.ReviewCommitted(activeStudyQueue.firstOrNull()?.card?.id, reviewed = 1, correct = 0))
                        mutableState.value = mutableState.value.copy(autoRatedAgain = false, correctionRequired = false)
                        loadSession(preserveStudyQueue = true)
                    } else {
                        handleFailure(prompt, recordSample = true)
                        dispatchSession(SessionEvent.RequireCorrection)
                        mutableCorrectionAnswer.value = ""
                        mutableState.value = mutableState.value.copy(
                            correctionRequired = true,
                            correctionAccepted = false
                        )
                    }
                }.onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        ratingInProgress = false,
                        statusMessage = safeUserMessage(error, "Could not save review.")
                    )
                }
            }
        }
    }

    private fun commitPracticeMiss(prompt: ReviewPrompt) {
        reviewTransactions.clearUndo()
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
            mutableCorrectionAnswer.value = ""
            mutableState.value = mutableState.value.copy(
                ratingInProgress = false,
                autoRatedAgain = true,
                correctionRequired = true,
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
                val exposure = if (countable) repository.captureSuccessCalibrationExposure(
                    prompt.card,
                    FatigueModel.estimate(responseSamples.map { it.first }, responseSamples.map { it.second }),
                    promptShownAt
                ) else null
                reviewTransactions.review(
                    prompt.card,
                    rating,
                    objectiveCorrect = if (countable) wasCorrect else null,
                    instructionalExposure = prompt.answerMode == AnswerMode.LESSON &&
                        prompt.card.cardType == CardType.RU_TO_MEANING
                ) to exposure
            }.onSuccess { (becameLeech, exposure) ->
                sessionCounterDeltas.addLast(delta)
                mutableState.value = mutableState.value.copy(
                    sessionReviewed = mutableState.value.sessionReviewed + delta.reviewed,
                    sessionCorrect = mutableState.value.sessionCorrect + delta.correct,
                    sessionCompletedCards = mutableState.value.sessionCompletedCards + 1,
                    statusMessage = if (becameLeech) "Parked this card — it kept tripping you up. Find it under Leeches." else mutableState.value.statusMessage
                )
                recordReviewTelemetry(prompt, rating, becameLeech, autoRated = false, calibrationExposure = exposure)
                recordResponseSample(prompt, rating)
                if (rating == Rating.AGAIN) {
                    if (becameLeech) {
                        advanceFrozenQueue(prompt, Rating.GOOD)
                        dispatchSession(SessionEvent.ReviewCommitted(activeStudyQueue.firstOrNull()?.card?.id, delta.reviewed, delta.correct))
                        mutableState.value = mutableState.value.copy(ratingInProgress = false, autoRatedAgain = false, correctionRequired = false)
                        loadSession(preserveStudyQueue = true)
                    } else {
                        handleFailure(prompt, recordSample = false)
                        dispatchSession(SessionEvent.RequireCorrection)
                        mutableCorrectionAnswer.value = ""
                        mutableState.value = mutableState.value.copy(
                            ratingInProgress = false,
                            autoRatedAgain = true,
                            correctionRequired = true,
                            correctionAccepted = false
                        )
                    }
                } else {
                    failureCounts.remove(prompt.card.id)
                    advanceFrozenQueue(prompt, rating)
                    scheduleAcquisitionPractice(prompt, rating)
                    dispatchSession(SessionEvent.ReviewCommitted(activeStudyQueue.firstOrNull()?.card?.id, delta.reviewed, delta.correct))
                    loadSession(preserveStudyQueue = true)
                }
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    ratingInProgress = false,
                    statusMessage = safeUserMessage(error, "Could not save review.")
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
        // Escalation ceiling reached (repair -> hints -> full-form reveal at failures==4)
        // and it STILL failed: bench this card for the rest of the sitting instead of
        // looping it forever (the classic Anki leech failloop). It resurfaces on its own
        // schedule next time, not immediately.
        if (failures > SITTING_BENCH_THRESHOLD) {
            failureCounts.remove(prompt.card.id)
            advanceFrozenQueue(prompt, Rating.AGAIN, bench = true)
            if (recordSample) recordResponseSample(prompt, Rating.AGAIN)
            mutableState.value = mutableState.value.copy(
                statusMessage = "Not clicking today — parked for this sitting, we'll come back to it fresh."
            )
            repository.recordTelemetry(telemetryForPrompt("leech_benched", prompt).copy(
                metadataJson = JSONObject().put("failures", failures).toString()
            ))
            return
        }
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
        reviewTransactions.clearUndo()
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
                    val committedAt = if (answerRevealedAt > 0) answerRevealedAt else clock.now()
                    val responseMs = (committedAt - promptShownAt).coerceAtLeast(0)
                    val fast = responseMs <= FAST_RECALL_MS
                    val fastStreak = if (fast) (acquisitionFastStreak[prompt.card.id] ?: 0) + 1 else 0
                    acquisitionFastStreak[prompt.card.id] = fastStreak
                    // A fixed quota of acquisitionTarget() reps treats a word the
                    // learner nails instantly the same as one they barely scrape by on
                    // — which is exactly what read as "constantly on repeat" even for
                    // clearly-already-solid words. Two fast, confident recalls in a row
                    // (after the initial one) are as strong evidence of acquisition as
                    // grinding out the full quota, so stop early instead.
                    val earlyStop = count >= 2 && fastStreak >= 2
                    if (count < acquisitionTarget() && !earlyStop) {
                        repository.practicePromptFor(prompt.card, count)?.let { next ->
                            remaining.add(minOf(experimentGap(), remaining.size), next.copy(
                                practiceOnly = true,
                                queueReason = "Acquisition recall ${count + 1} of ${acquisitionTarget()}"
                            ))
                        }
                    }
                } else {
                    acquisitionFastStreak.remove(prompt.card.id)
                    val scaffold = repository.scaffoldPromptFor(prompt.card, (failureCounts[prompt.card.id] ?: 0) + 1)
                    scaffold?.let { remaining.add(minOf(3, remaining.size), it) }
                }
                repository.recordTelemetry(telemetryForPrompt("acquisition_practice", prompt).copy(rating = rating.name))
            }
            activeStudyQueue.clear(); activeStudyQueue += remaining
            mutableCorrectionAnswer.value = ""
            mutableState.value = mutableState.value.copy(
                ratingInProgress = false,
                sessionCompletedCards = mutableState.value.sessionCompletedCards + 1,
                autoRatedAgain = !success,
                correctionRequired = !success,
                correctionAccepted = false,
                canUndo = false
            )
            dispatchSession(SessionEvent.ReviewCommitted(activeStudyQueue.firstOrNull()?.card?.id, reviewed = 0, correct = 0))
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
        val elapsed = ((if (answerRevealedAt > 0) answerRevealedAt else clock.now()) - promptShownAt).coerceAtLeast(0)
        val engineJudgedCorrect = mutableState.value.isAnswerCorrect
        val sampledCorrect = engineJudgedCorrect ?: (rating != Rating.AGAIN)
        // FatigueModel/SessionMpcController compare this history's rolling latency
        // against an early-session baseline to detect fatigue/struggle. Storing raw
        // wall-clock ms let a plain format-mix effect masquerade as fatigue: grammar
        // drills (CASE_FILL, VERB_FORM, ...) legitimately take longer to reason through
        // than a quick recognition tap, so a session that opened with fast cards and
        // then hit a run of grammar cards read as a latency spike and tripped the
        // "sustained struggle"/severe-fatigue stop after just a couple of them — kicking
        // the learner to the session-complete screen mid-flow. Normalizing by each
        // format's expected response time (PerformanceModel.targetTimeMs) keeps the
        // comparison to "slower than expected for this format", not "slower than
        // whatever format happened to open the session".
        val normalizedElapsed = (elapsed.toDouble() * FATIGUE_REFERENCE_TARGET_MS /
            PerformanceModel.targetTimeMs(prompt.answerMode).coerceAtLeast(1)).toLong().coerceAtLeast(1L)
        responseSamples += normalizedElapsed to sampledCorrect
        if (engineJudgedCorrect != null) {
            objectiveAttempts += ObjectiveAttempt(
                itemId = prompt.card.id,
                correct = engineJudgedCorrect,
                responseMs = elapsed,
                answerMode = prompt.answerMode,
                itemDifficulty = 25.0 + (CardPedagogy.profile(prompt.card.cardType).cognitiveCost - 0.8) * 2.2
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
                }
                repository.upsertBanditArmStates(nextCardBandit.snapshot())
            }
        }
        sessionShownNotes += prompt.note.id
        sessionShownHard += prompt.answerMode.isHardProduction()
        sessionShownTypes += prompt.card.cardType
        if (rating == Rating.AGAIN) lapsedShownAt[prompt.card.id] = mutableState.value.sessionCompletedCards
        if (!fatigueAdjusted && responseSamples.size >= 4) {
            val fatigue = FatigueModel.estimate(responseSamples.map { it.first }, responseSamples.map { it.second })
            if (fatigue >= 0.65) {
                val before = activeStudyQueue.size
                val removableNew = activeStudyQueue.count { it.card.state == com.sibirskyspeak.data.CardState.NEW && it.card.reps == 0 }
                val configuredTarget = settings.sessionSize.coerceAtLeast(1)
                val requiredRemaining = (configuredTarget - mutableState.value.sessionCompletedCards).coerceAtLeast(0)
                val nonNewRemaining = activeStudyQueue.size - removableNew
                // Fatigue protection may discard surplus optional new material, but
                // never remove the only cards that could satisfy the learner's
                // explicitly selected session dose. Previously a 40-card session
                // could lose all of its new cards after only four observations and
                // then look legitimately complete on the next reload.
                val removableSurplus = (removableNew - (requiredRemaining - nonNewRemaining).coerceAtLeast(0))
                    .coerceAtLeast(0)
                // Backlog mode already suppresses new material. Avoid a warning and
                // telemetry event when fatigue protection would change nothing.
                if (removableSurplus == 0) return
                var removedBudget = removableSurplus
                val iterator = activeStudyQueue.listIterator()
                while (iterator.hasNext() && removedBudget > 0) {
                    val candidate = iterator.next()
                    if (candidate.card.state == com.sibirskyspeak.data.CardState.NEW && candidate.card.reps == 0) {
                        iterator.remove()
                        removedBudget -= 1
                    }
                }
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

    private fun evaluatePromptAnswer(
        prompt: ReviewPrompt,
        actual: String,
        correction: Boolean = false
    ): AnswerEvaluation =
        when (prompt.answerMode) {
            AnswerMode.ENGLISH -> evaluateEnglishAnswer(prompt.expectedAnswer, actual)
            // NOVEL_PRODUCE (P4.4 L3): the learner composes a whole sentence from an
            // English cue with no Russian shown, so Russian's genuinely free word
            // order must not be penalized — grade word-order-free instead of the
            // normal fixed-string comparison.
            AnswerMode.RUSSIAN_TYPED, AnswerMode.AUDIO_ONLY, AnswerMode.SPEAK ->
                if (prompt.card.cardType == CardType.NOVEL_PRODUCE) {
                    evaluateWordOrderFreeRussianAnswer(prompt.expectedAnswer, actual)
                } else if (prompt.card.cardType == CardType.SPEAK_SENTENCE) {
                    // Elicited imitation (P6.1): order-aware, per-token, ASR-tolerant —
                    // distinct from both the free-order NOVEL_PRODUCE grading above and
                    // the fixed-string comparison below (single-word SPEAK cards).
                    evaluateElicitedImitation(prompt.expectedAnswer, actual)
                } else {
                    evaluateRussianAnswer(
                        expected = prompt.expectedAnswer,
                        actual = actual,
                        allowTypos = prompt.card.cardType !in STRICT_FORM_CARD_TYPES
                    )
                }
            AnswerMode.RUSSIAN_STRESS_TYPED -> evaluateRussianAnswer(
                prompt.expectedAnswer,
                actual,
                ignoreStress = correction
            )
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

    private fun advanceFrozenQueue(prompt: ReviewPrompt, rating: Rating, repairPrompt: ReviewPrompt? = null, bench: Boolean = false) {
        if (!studySessionActive) return
        val updated = recoveryQueueAfter(activeStudyQueue, prompt, rating, repairPrompt, bench).toMutableList()
        val blueprint = mutableState.value.sessionPlan?.blueprint
        if (blueprint != null && updated.isNotEmpty()) {
            val live = LiveSessionState(
                shown = mutableState.value.sessionCompletedCards + 1,
                recent = responseSamples.takeLast(4),
                recentNoteIds = sessionShownNotes.takeLast(4),
                recentHard = sessionShownHard.takeLast(4),
                recentCardTypes = sessionShownTypes.takeLast(5),
                lapsedShownAt = lapsedShownAt,
                // Repository gating already removed locked drills; treating every
                // remaining concept as introduced preserves that hard constraint.
                introducedConcepts = updated.mapNotNull { it.card.gramConcept }.toSet(),
                recoveryWindowRemaining = recoveryWindowRemaining,
                recoveryAttempted = recoveryAttempted
            )
            val context = banditContext()
            val selectedNext = NextCardSelector.select(
                updated, blueprint, live, clock.now(),
                mutableState.value.sessionPlan?.confusablePairs.orEmpty(),
                policyBias = { candidate ->
                    nextCardBandit.score(candidate.card.cardType.name, context) * 0.25
            })
            // The queue was already gated when the plan was built. If a live
            // selector still cannot score a candidate after a miss, keep studying
            // from that valid queue instead of treating selector uncertainty as an
            // empty session and discarding every remaining card.
            val selectorFallback = selectedNext == null && updated.isNotEmpty()
            val next = selectedNext ?: updated.firstOrNull()
            val pace = mutableState.value.sessionPlan?.pace
            val fatigue = FatigueModel.estimate(responseSamples.map { it.first }, responseSamples.map { it.second })
            val mpcInputs = MpcInputs(
                fatigue = fatigue,
                debtRatio = pace?.debtRatio ?: 0.0,
                debtLimit = 0.35,
                pReturn = pace?.pReturn ?: 0.8,
                stretchAlreadyOffered = flowOffered,
                justFailedUngracedCardId = if (rating == Rating.AGAIN && prompt.card.id !in graceGrantedCardIds) prompt.card.id else null,
                minimumSessionCards = settings.sessionSize.coerceAtLeast(1)
            )
            val queueBeforeDecision = updated.size
            val decision = SessionMpcController.decide(
                hasCard = next != null,
                live = live,
                inputs = mpcInputs
            )
            // Accumulated (not overwritten) across possibly multiple STOP/GRACE events in
            // one sitting — a grace attempt can fail too, re-tripping a later stop — so
            // this must reflect everything deferred all sitting, not just the latest
            // event. startStudySession resets it to 0, so nothing leaks across sessions.
            fun accumulateDeferred(justDeferred: Int) = mutableState.value.stoppedQueueRemaining + justDeferred
            when (decision) {
                MpcAction.GRACE -> {
                    // The card that just failed hasn't had its one extra attempt yet —
                    // abandoning it here (like a plain STOP would) leaves it with an
                    // extra lapse and zero correction, so it just fails again next time
                    // and re-trips this same stop. distinctBy is required: recoveryQueueAfter
                    // queues two entries for the same failed card (a repair copy and a
                    // "final recovery check" copy) — without deduping, both would count
                    // as this card's one grace rep.
                    val gracedCardId = mpcInputs.justFailedUngracedCardId!!
                    graceGrantedCardIds += gracedCardId
                    val graceEntries = updated.filter { it.card.id == gracedCardId }.distinctBy { it.card.id }
                    val totalDeferred = accumulateDeferred(updated.size - graceEntries.size)
                    updated.clear()
                    updated += graceEntries
                    mutableState.value = mutableState.value.copy(
                        sessionStoppedEarly = mutableState.value.sessionStoppedEarly || totalDeferred > 0,
                        stoppedQueueRemaining = totalDeferred,
                        statusMessage = "Finishing the card you just missed before wrapping up" +
                            if (totalDeferred > 0) "; $totalDeferred more deferred because accuracy/load made more practice low-value today." else "."
                    )
                }
                MpcAction.STOP -> {
                    val totalDeferred = accumulateDeferred(updated.size)
                    updated.clear()
                    mutableState.value = mutableState.value.copy(
                        sessionStoppedEarly = mutableState.value.sessionStoppedEarly || (next != null),
                        stoppedQueueRemaining = totalDeferred,
                        statusMessage = if (next != null) {
                            "Protected stop: $totalDeferred prompts deferred because accuracy/load made more practice low-value today."
                        } else {
                            "Session complete."
                        }
                    )
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
                MpcAction.RECOVER -> {
                    // Don't eject: bias the next few cards toward well-known material so
                    // the sitting has a real chance to recover before we consider this a
                    // genuine fatigue stop. See recoveryWindowRemaining bookkeeping below.
                    recoveryWindowRemaining = RECOVERY_WINDOW_SIZE
                    mutableState.value = mutableState.value.copy(
                        statusMessage = "Let's rebuild some confidence with a few easier reviews, then carry on."
                    )
                    next?.let { updated.remove(it); updated.add(0, it) }
                }
                MpcAction.CARD -> next?.let { updated.remove(it); updated.add(0, it) }
            }
            // Advance the recovery window one card at a time. Latching recoveryAttempted
            // the instant the window empties (rather than only when SessionMpcController
            // notices) means struggle that persists past the window escalates to STOP on
            // its very next decision instead of silently re-triggering RECOVER forever.
            if (recoveryWindowRemaining > 0) {
                recoveryWindowRemaining -= 1
                if (recoveryWindowRemaining == 0) recoveryAttempted = true
            }
            viewModelScope.launch {
                repository.recordTelemetry(TelemetryEvent(
                    eventType = "mpc_decision",
                    sessionId = telemetrySessionId,
                    cardId = next?.card?.id,
                    sessionRemaining = updated.size,
                    metadataJson = JSONObject()
                        .put("decision", decision.name)
                        .put("queueBefore", queueBeforeDecision)
                        .put("queueAfter", updated.size)
                        .put("shown", live.shown)
                        .put(
                            "recentAccuracy",
                            live.recent.takeIf { it.isNotEmpty() }
                                ?.let { samples -> samples.count { it.second }.toDouble() / samples.size }
                                ?: 1.0
                        )
                        .put("fatigue", fatigue)
                        .put("debtRatio", mpcInputs.debtRatio)
                        .put("debtLimit", mpcInputs.debtLimit)
                        .put("pReturn", mpcInputs.pReturn)
                        .put("hasNext", next != null)
                        .put("selectorFallback", selectorFallback)
                        .toString()
                ))
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
        newCardLimit = settings.newCardsPerDay,
        metadataJson = pedagogyMetadata(prompt).toString()
    )

    private fun pedagogyMetadata(prompt: ReviewPrompt): JSONObject {
        val profile = CardPedagogy.profile(prompt.card.cardType)
        return JSONObject()
            .put("learningStage", CardPedagogy.stage(prompt).name)
            .put("learningFacet", profile.facet.name)
            .put("evidenceStrength", profile.evidence.name)
            .put("cognitiveCost", profile.cognitiveCost)
            .put("promptChars", prompt.prompt.length)
            .put("answerChars", prompt.expectedAnswer.length)
            .put("sentenceWords", prompt.exampleSentence?.let { Regex("""\p{L}+""").findAll(it).count() } ?: 0)
            .put("supportOnly", prompt.supportOnly)
            .put("practiceOnly", prompt.practiceOnly)
    }

    private suspend fun recordReviewTelemetry(
        prompt: ReviewPrompt,
        rating: Rating,
        becameLeech: Boolean,
        autoRated: Boolean,
        calibrationExposure: com.sibirskyspeak.learning.CalibrationExposure?
    ) {
        val committedAt = if (answerRevealedAt > 0) answerRevealedAt else clock.now()
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
            metadataJson = pedagogyMetadata(prompt)
                .put("stateBefore", prompt.card.state.name)
                .put("repsBefore", prompt.card.reps)
                .put("lapsesBefore", prompt.card.lapses)
                .put("autoRated", autoRated)
                .put("becameLeech", becameLeech)
                .put("easyInterpretation", easyInterpretation)
                .put("suggestedRating", mutableState.value.suggestedRating?.name)
                .put("experimentVariant", settings.learningExperimentVariant)
                .put("ratingDecisionMs", if (answerRevealedAt > 0) (clock.now() - answerRevealedAt).coerceAtLeast(0) else JSONObject.NULL)
                .toString()
        ))
        val actualRecall = mutableState.value.answerMatch?.let { it != AnswerMatch.WRONG }
        if (actualRecall != null && calibrationExposure != null) {
            repository.recordSuccessCalibrationSample(
                card = prompt.card,
                exposure = calibrationExposure,
                correct = actualRecall,
                at = committedAt
            )
        }
        if (actualRecall != null && !autoRated) {
            val predictedRecall = rating != Rating.AGAIN
            repository.recordTelemetry(telemetryForPrompt("calibration_sample", prompt).copy(
                rating = rating.name,
                answerMatch = mutableState.value.answerMatch?.name,
                responseMs = responseMs,
                metadataJson = pedagogyMetadata(prompt)
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
            val restored = reviewTransactions.undoLastReview() ?: return@launch
            repository.recordTelemetry(TelemetryEvent(eventType = "review_undo", sessionId = telemetrySessionId, cardId = restored.id, noteId = restored.noteId))
            val delta = if (sessionCounterDeltas.isNotEmpty()) sessionCounterDeltas.removeLast() else SessionCounterDelta(0, 0)
            queueBeforeLastReview?.let { snapshot ->
                activeStudyQueue.clear()
                activeStudyQueue += snapshot
            }
            queueBeforeLastReview = null
            loadSession(status = "Undid last review", preserveStudyQueue = true)
            mutableCorrectionAnswer.value = ""
            mutableState.value = mutableState.value.copy(
                revealed = false,
                isAnswerCorrect = null,
                answerMatch = null,
                answerFeedback = null,
                autoRatedAgain = false,
                correctionRequired = false,
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
        reviewTransactions.clearUndo()
        queueBeforeLastReview = null
        viewModelScope.launch {
            runCatching { repository.suspendCard(prompt.card) }
                .onSuccess {
                    activeStudyQueue.removeAll { it.card.id == prompt.card.id }
                    repository.recordTelemetry(telemetryForPrompt("card_suspended", prompt))
                    loadSession(status = "Card suspended. It is out of all review queues.", preserveStudyQueue = true)
                }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = safeUserMessage(it, "Could not suspend card.")) }
        }
    }

    fun markCurrentWordKnown() {
        val prompt = mutableState.value.prompt ?: return
        if (mutableState.value.ratingInProgress) return
        reviewTransactions.clearUndo()
        queueBeforeLastReview = null
        viewModelScope.launch {
            runCatching { repository.markWordKnown(prompt.card.noteId) }
                .onSuccess {
                    activeStudyQueue.removeAll { it.card.noteId == prompt.card.noteId && it.card.queue.name == "VOCAB" }
                    repository.recordTelemetry(telemetryForPrompt("mark_known", prompt))
                    loadSession(status = "Marked known. Vocab practice for this word is retired.", preserveStudyQueue = true)
                }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = safeUserMessage(it, "Could not mark known.")) }
        }
    }

    fun placeAfterLevel(level: String) {
        viewModelScope.launch {
            runCatching { repository.placeAfterLevel(level) }
                .onSuccess { count -> loadSession(keepStep = SessionStep.IMPORT, status = "Placed after $level: marked $count notes known") }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = safeUserMessage(it, "Could not place level.")) }
        }
    }

    fun startPlacementTest() {
        val initial = PlacementSessionController.initial()
        mutableState.value = mutableState.value.copy(
            placementActive = true,
            placementQuestionIndex = initial.questionIndex,
            placementAnswers = initial.answers,
            placementCompleted = initial.completed,
            placementResult = initial.result
        )
    }

    fun answerPlacementQuestion(choiceIndex: Int) {
        val state = mutableState.value
        if (!state.placementActive || state.placementCompleted) return
        val step = PlacementSessionController.answer(
            PlacementStep(state.placementQuestionIndex, state.placementAnswers, state.placementCompleted, state.placementResult),
            choiceIndex
        )
        mutableState.value = state.copy(
            placementAnswers = step.answers,
            placementQuestionIndex = step.questionIndex,
            placementCompleted = step.completed,
            placementResult = step.result
        )
    }

    fun applyPlacementResult() {
        applyPlacementAtLevel(mutableState.value.placementResult)
    }

    fun applyPlacementAtLevel(level: String?) {
        dismissPlacementTest()
        if (level != null) placeAfterLevel(level)
    }

    fun dismissPlacementTest() {
        mutableState.value = mutableState.value.copy(
            placementActive = false,
            placementQuestionIndex = 0,
            placementAnswers = emptyList(),
            placementCompleted = false,
            placementResult = null
        )
    }

    /**
     * "I actually knew this" escape hatch after a typed answer was auto-failed.
     * Rolls back the silent AGAIN and reopens the rating buttons so the learner
     * can grade their true recall.
     */
    fun overrideKnewIt() {
        if (!mutableState.value.autoRatedAgain) return
        viewModelScope.launch {
            reviewTransactions.undoLastReview()
            queueBeforeLastReview?.let { snapshot ->
                activeStudyQueue.clear()
                activeStudyQueue += snapshot
            }
            queueBeforeLastReview = null
            mutableState.value.prompt?.let { repository.recordTelemetry(telemetryForPrompt("auto_miss_overridden", it)) }
            val delta = if (sessionCounterDeltas.isNotEmpty()) sessionCounterDeltas.removeLast() else SessionCounterDelta(1, 0)
            mutableCorrectionAnswer.value = ""
            mutableState.value = mutableState.value.copy(
                autoRatedAgain = false,
                correctionRequired = false,
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

    fun toggleReaderBookmark(tokenIndex: Int, label: String = "") {
        val textId = mutableState.value.selectedReaderTextId ?: return
        viewModelScope.launch {
            runCatching { repository.toggleReaderBookmark(textId, tokenIndex, label) }
                .onSuccess { mutableState.value = mutableState.value.copy(readerBookmarks = repository.readerBookmarks(textId)) }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = safeUserMessage(it, "Could not update bookmark.")) }
        }
    }

    fun setReaderGoal(id: Long) {
        // Must stay on the default (Main) dispatcher: refreshReaderInsights() does a
        // non-atomic read-modify-write of mutableState.value (read `current`, write
        // `current.copy(...)`), which every other mutation in this class relies on
        // being serialized against by virtue of running on the same dispatcher. Moving
        // this one call to computeDispatcher let it race a concurrent Main-thread write
        // (e.g. rate()) and silently clobber it.
        viewModelScope.launch {
            runCatching { repository.setReaderGoal(id) }
                .onSuccess { refreshReaderInsights(); mutableState.value = mutableState.value.copy(statusMessage = "Reading goal set") }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = safeUserMessage(it, "Could not set goal.")) }
        }
    }

    fun updateReaderSource(textId: Long, source: String) {
        viewModelScope.launch {
            runCatching { repository.updateReaderSource(textId, source) }
                .onSuccess { changed ->
                    if (changed) refreshReaderInsights()
                    mutableState.value = mutableState.value.copy(statusMessage = if (changed) "Reader source updated" else "Reader text not found")
                }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = safeUserMessage(it, "Could not update reader source.")) }
        }
    }

    /** P6.4: start a monthly checkpoint session (Lab). Independent of the study
     * session — writes no FSRS state, so it can run any time without disturbing
     * due dates or the regular queue. */
    fun startCheckpoint() {
        viewModelScope.launch {
            runCatching { repository.buildCheckpointSession() }
                .onSuccess { session ->
                    mutableState.value = mutableState.value.copy(
                        checkpointSession = session,
                        checkpointIndex = 0,
                        checkpointResults = emptyList(),
                        checkpointFeedback = null
                    )
                }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = safeUserMessage(it, "Could not start checkpoint.")) }
        }
    }

    fun submitCheckpointAnswer(answer: String) {
        val current = mutableState.value
        val session = current.checkpointSession ?: return
        val item = session.items.getOrNull(current.checkpointIndex) ?: return
        viewModelScope.launch {
            val correct = repository.gradeCheckpointAnswer(item, answer)
            runCatching { repository.recordCheckpointResult(item, correct) }
            val nextIndex = current.checkpointIndex + 1
            val done = nextIndex >= session.items.size
            mutableState.value = mutableState.value.copy(
                checkpointIndex = nextIndex,
                checkpointResults = current.checkpointResults + correct,
                checkpointFeedback = when {
                    done -> {
                        val results = current.checkpointResults + correct
                        "Checkpoint complete: ${results.count { it }}/${results.size} correct."
                    }
                    correct -> "Correct."
                    else -> "Not quite — ${item.expectedAnswer}"
                },
                checkpointCalibration = if (done) repository.checkpointCalibration() else current.checkpointCalibration
            )
        }
    }

    fun dismissCheckpoint() {
        mutableState.value = mutableState.value.copy(
            checkpointSession = null,
            checkpointIndex = 0,
            checkpointResults = emptyList(),
            checkpointFeedback = null
        )
    }

    /**
     * Phase G6 / P6.5: after a study session finishes, offer a quick exit-ticket
     * check for the most recently completed unit (the frontier unit as soon as it
     * first crosses the mastery threshold) — a lightweight surface, never a lock.
     * Skips units already offered this app session or that already have a
     * recorded ExitTicketResult (so re-finishing a session doesn't nag repeatedly).
     */
    /**
     * Phase G3: the moment an [ErrorCategory] recurs a second time this sitting,
     * pulls a card of that category's deterministic repair type
     * ([repairCardTypeFor]) forward to be shown next — preferring one on the
     * same note the miss just happened on, otherwise the first match already
     * queued today. Never fabricates a card; a session with no matching card
     * already in [activeStudyQueue] is a silent no-op.
     */
    private fun immediateRepairIfRecurring(diagnosis: com.sibirskyspeak.review.Diagnosis, missedPrompt: ReviewPrompt) {
        val count = (sessionErrorCategoryCounts[diagnosis.category] ?: 0) + 1
        sessionErrorCategoryCounts[diagnosis.category] = count
        if (count < 2) return
        val repairType = repairCardTypeFor(diagnosis.category)
        if (repairType == null) {
            recordRepairTelemetry(diagnosis, missedPrompt, injected = false, reason = "no_repair_type_for_category")
            return
        }
        val candidateIndex = activeStudyQueue.indexOfFirst {
            it.card.cardType == repairType && it.card.noteId == missedPrompt.card.noteId
        }.let { sameNote -> if (sameNote >= 0) sameNote else activeStudyQueue.indexOfFirst { it.card.cardType == repairType } }
        if (candidateIndex <= 0) {
            recordRepairTelemetry(diagnosis, missedPrompt, injected = false, reason = "no_candidate_queued")
            return
        }
        val repair = activeStudyQueue.removeAt(candidateIndex)
        // One intervening retrieval prevents the contrast itself becoming a cue,
        // while keeping corrective comparison close enough to remain useful.
        activeStudyQueue.add(if (activeStudyQueue.isEmpty()) 0 else 1, repair)
        recordRepairTelemetry(diagnosis, missedPrompt, injected = true, reason = null)
    }

    /** Phase G3 observability: without this, a same-session repair injection
     * (or its silent no-op when no candidate card is already queued) leaves no
     * trace anywhere — unlike P4.5's DB-persisted confusion_events, which are at
     * least indirectly inspectable. */
    private fun recordRepairTelemetry(diagnosis: com.sibirskyspeak.review.Diagnosis, missedPrompt: ReviewPrompt, injected: Boolean, reason: String?) {
        viewModelScope.launch {
            runCatching {
                repository.recordTelemetry(TelemetryEvent(
                    eventType = "immediate_repair_injection",
                    sessionId = telemetrySessionId,
                    cardId = missedPrompt.card.id,
                    noteId = missedPrompt.card.noteId,
                    cardType = missedPrompt.card.cardType.name,
                    metadataJson = JSONObject()
                        .put("category", diagnosis.category.name)
                        .put("injected", injected)
                        .apply { reason?.let { put("reason", it) } }
                        .toString()
                ))
            }
        }
    }

    private fun maybeOfferExitTicket() {
        val mastery = mutableState.value.sessionPlan?.unitMastery.orEmpty()
        val completed = mastery.lastOrNull { it.progress >= 0.80 && it.vocabularyTotal + it.grammarTotal > 0 } ?: return
        val completedUnit = completed.unit
        val stableKey = completed.stableKey
        if (stableKey in exitTicketOfferedUnits) return
        viewModelScope.launch {
            if (repository.exitTicketResults().any { it.unit == completedUnit && it.band == completed.band }) {
                exitTicketOfferedUnits += stableKey
                return@launch
            }
            val session = runCatching { repository.buildExitTicketSession(completedUnit, completed.band) }.getOrNull()
            if (session == null || session.items.isEmpty()) {
                exitTicketOfferedUnits += stableKey
                return@launch
            }
            mutableState.value = mutableState.value.copy(
                exitTicketOfferUnit = completedUnit,
                exitTicketOfferBand = completed.band,
                exitTicketOfferCanDo = session.canDoLabel
            )
            runCatching {
                repository.recordTelemetry(TelemetryEvent(
                    eventType = "exit_ticket_offered", sessionId = telemetrySessionId,
                    metadataJson = JSONObject().put("unit", completedUnit).toString()
                ))
            }
        }
    }

    /** Learner tapped "quick check" — assembles and starts the mixed proof session. */
    fun startExitTicket() {
        val unit = mutableState.value.exitTicketOfferUnit ?: return
        val band = mutableState.value.exitTicketOfferBand
        viewModelScope.launch {
            runCatching { repository.buildExitTicketSession(unit, band) }
                .onSuccess { session ->
                    if (session == null) { dismissExitTicketOffer(); return@onSuccess }
                    mutableState.value = mutableState.value.copy(
                        exitTicketSession = session,
                        exitTicketIndex = 0,
                        exitTicketResults = emptyList(),
                        exitTicketFeedback = null,
                        exitTicketComplete = false
                    )
                    runCatching {
                        repository.recordTelemetry(TelemetryEvent(
                            eventType = "exit_ticket_started", sessionId = telemetrySessionId,
                            metadataJson = JSONObject().put("unit", unit).put("items", session.items.size).toString()
                        ))
                    }
                }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = safeUserMessage(it, "Could not start quick check.")) }
        }
    }

    fun submitExitTicketAnswer(answer: String) {
        val current = mutableState.value
        val session = current.exitTicketSession ?: return
        val item = session.items.getOrNull(current.exitTicketIndex) ?: return
        val correct = repository.gradeExitTicketAnswer(item, answer)
        val results = current.exitTicketResults + correct
        val nextIndex = current.exitTicketIndex + 1
        val done = nextIndex >= session.items.size
        mutableState.value = mutableState.value.copy(
            exitTicketIndex = nextIndex,
            exitTicketResults = results,
            exitTicketFeedback = if (correct) "Correct." else "Not quite — ${item.expectedAnswer}",
            exitTicketComplete = done
        )
        if (done) viewModelScope.launch {
            runCatching { repository.completeExitTicket(session, results) }
            exitTicketOfferedUnits += "${session.band}:${session.unit}"
            mutableState.value = mutableState.value.copy(exitTicketOfferUnit = null, exitTicketOfferBand = null, exitTicketOfferCanDo = null)
            runCatching {
                repository.recordTelemetry(TelemetryEvent(
                    eventType = "exit_ticket_completed", sessionId = telemetrySessionId,
                    metadataJson = JSONObject()
                        .put("unit", session.unit)
                        .put("correct", results.count { it })
                        .put("total", results.size)
                        .toString()
                ))
            }
        }
    }

    /** Dismisses the offer banner with zero friction — this must never block the
     * learner from continuing normally (Phase G6 requirement). */
    fun dismissExitTicketOffer() {
        val unit = mutableState.value.exitTicketOfferUnit
        val band = mutableState.value.exitTicketOfferBand ?: "A1"
        if (unit != null) {
            exitTicketOfferedUnits += "$band:$unit"
            viewModelScope.launch {
                runCatching {
                    repository.recordTelemetry(TelemetryEvent(
                        eventType = "exit_ticket_dismissed", sessionId = telemetrySessionId,
                        metadataJson = JSONObject().put("unit", unit).toString()
                    ))
                }
            }
        }
        mutableState.value = mutableState.value.copy(exitTicketOfferUnit = null, exitTicketOfferBand = null, exitTicketOfferCanDo = null)
    }

    /** Marks the pending content-update report as shown so it never nags again. */
    fun dismissCurriculumMigrationReport() {
        val report = mutableState.value.curriculumMigrationReport ?: return
        mutableState.value = mutableState.value.copy(curriculumMigrationReport = null)
        viewModelScope.launch { runCatching { repository.markCurriculumMigrationReportShown(report.id) } }
    }

    /** Closes an in-progress or completed exit-ticket mini-session panel. */
    fun closeExitTicket() {
        mutableState.value = mutableState.value.copy(
            exitTicketSession = null,
            exitTicketIndex = 0,
            exitTicketResults = emptyList(),
            exitTicketFeedback = null,
            exitTicketComplete = false
        )
    }

    private suspend fun openReaderTextNow(id: Long, inSession: Boolean) {
        val recommendation = mutableState.value.allReaderTexts.firstOrNull { it.text.id == id }
            ?: repository.readerTexts().firstOrNull { it.text.id == id }
            ?: return
        val progress = settings.readerProgress(id)
        val tokens = repository.readerTokens(recommendation.text)
        val bookmarks = repository.readerBookmarks(id)
        val history = repository.readerHistory(id)
        val questions = buildReaderCheckpoint(recommendation.text.body, tokens, mutableState.value.sessionPlan?.consolidationLemmas.orEmpty().toSet())
        mutableState.value = mutableState.value.copy(
            selectedReaderTextId = id,
            readerTokens = tokens,
            readerBookmarks = bookmarks,
            readerHistory = history,
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
                .put("kind", question.kind)
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
                    statusMessage = safeUserMessage(error, "Could not save reading progress.")
                )
            } finally {
                readingCommitInProgress = false
            }
        }
    }

    private fun buildReaderCheckpoint(body: String, tokens: List<ReaderToken>, consolidation: Set<String>): List<ReaderCheckpointQuestion> {
        val structural = buildList {
            val normalizedWords = tokens.map { it.normalized }
            if (normalizedWords.any { it == "\u043d\u0435" || it == "\u043d\u0435\u0442" }) add(ReaderCheckpointQuestion(
                russian = body.take(180),
                expected = "The passage explicitly negates an action or state",
                choices = listOf("The passage explicitly negates an action or state", "The passage makes only an affirmative statement"),
                kind = "NEGATION"
            ))
            tokens.firstOrNull { it.aktionsart != null || it.parse?.contains("Verb", ignoreCase = true) == true }
                ?.takeIf { it.aktionsart != null }
                ?.let { verb ->
                    val bounded = verb.aktionsart in setOf("achievement", "accomplishment", "semelfactive")
                    add(ReaderCheckpointQuestion(
                        russian = "In this passage, how is ${verb.surface} presented?",
                        expected = if (bounded) "As a bounded event with an endpoint" else "As an ongoing, repeated, or unbounded situation",
                        choices = listOf("As a bounded event with an endpoint", "As an ongoing, repeated, or unbounded situation"),
                        kind = "ASPECT"
                    ))
                }
            val relation = when {
                normalizedWords.any { it == "\u043d\u043e" || it == "\u043e\u0434\u043d\u0430\u043a\u043e" } -> "contrast"
                normalizedWords.any { it == "\u043f\u043e\u044d\u0442\u043e\u043c\u0443" } -> "result"
                normalizedWords.any { it == "\u043f\u043e\u0442\u043e\u043c\u0443" } && normalizedWords.any { it == "\u0447\u0442\u043e" } -> "cause"
                else -> null
            }
            relation?.let { expected -> add(ReaderCheckpointQuestion(
                russian = "What relationship connects the ideas in this passage?",
                expected = expected.replaceFirstChar(Char::uppercase),
                choices = listOf("Contrast", "Cause", "Result"),
                kind = "DISCOURSE"
            )) }
        }.distinctBy { it.kind }.take(2)
        val candidates = tokens
            .filter { !it.translation.isNullOrBlank() }
            .distinctBy { it.normalized }
            .sortedByDescending { it.lemma in consolidation }
            .take(3)
        val distractors = tokens.mapNotNull { it.translation?.trim() }.filter { it.isNotBlank() }.distinct()
        val lexical = candidates.mapIndexed { index, token ->
            val expected = token.translation!!.trim()
            val choices = (listOf(expected) + distractors.filterNot { it.equals(expected, true) }.take(3))
                .distinct().sortedBy { (it.hashCode() xor token.normalized.hashCode() xor index) }
            ReaderCheckpointQuestion(token.surface, expected, choices, kind = "LEXICAL")
        }.filter { it.choices.size >= 2 }
        return (structural + lexical).take(3)
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
                        statusMessage = safeUserMessage(error, "Could not postpone reading.")
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
        mutableState.value = mutableState.value.copy(importText = value, importPreview = null)
    }

    fun previewImport() {
        val payload = mutableState.value.importText
        viewModelScope.launch(computeDispatcher) {
            val preview = repository.previewImport(payload)
            mutableState.value = mutableState.value.copy(importPreview = preview)
        }
    }

    fun importJsonLines() {
        val payload = mutableState.value.importText
        viewModelScope.launch {
            val preview = repository.previewImport(payload)
            if (!preview.valid) {
                mutableState.value = mutableState.value.copy(importPreview = preview, statusMessage = preview.errors.joinToString("; "))
                return@launch
            }
            runCatching { repository.importJsonLines(payload) }
                .onSuccess { count -> loadSession(keepStep = SessionStep.IMPORT, status = "Imported $count notes. Check import readiness for readable examples.") }
                .onFailure { error -> mutableState.value = mutableState.value.copy(statusMessage = safeUserMessage(error, "Import failed.")) }
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

    fun setReaderSource(value: String) {
        mutableState.value = mutableState.value.copy(readerSource = value)
    }

    fun addReaderText() {
        val title = mutableState.value.readerTitle
        val body = mutableState.value.readerBody
        val source = mutableState.value.readerSource.trim().ifBlank { "local" }
        if (body.isBlank()) {
            mutableState.value = mutableState.value.copy(statusMessage = "Reader text body is empty")
            return
        }
        viewModelScope.launch {
            repository.addReaderText(title, body, source)
            loadSession(keepStep = SessionStep.READER, status = "Added reader text")
        }
    }

    private suspend fun loadSession(
        keepStep: SessionStep = mutableState.value.sessionStep,
        status: String? = mutableState.value.statusMessage,
        preserveStudyQueue: Boolean = false,
        // preserveStudyQueue means we're still mid-review, not on the dashboard/reader
        // screen — the same reasoning startStudySession() already uses at cold start
        // (see loadSession(includeReaderInsights = false) there): reader coverage
        // needs a morphology index over the whole deck and a scan of every text, so it
        // must never be the thing standing between a rating and the next prompt.
        // Without this, canReusePlan going false right after a review (most visibly
        // when the MPC's Protected Stop clears activeStudyQueue) forced that full
        // reader-coverage rebuild on every single subsequent action — a ~4.5-5s stall
        // with no loading indicator, during which a frustrated extra tap would land on
        // whatever the rebuild produced once it finally finished, undoing the very
        // stop the pacer had just decided to make. refreshReaderInsights() backfills
        // this in the background the same way it does after cold start.
        includeReaderInsights: Boolean = !preserveStudyQueue
    ) = sessionLoadMutex.withLock {
        loadSessionLocked(keepStep, status, preserveStudyQueue, includeReaderInsights)
    }

    private suspend fun loadSessionLocked(
        keepStep: SessionStep,
        status: String?,
        preserveStudyQueue: Boolean,
        includeReaderInsights: Boolean
    ) {
        val loadStartedAt = clock.now()
        val current = mutableState.value
        val finishingSession = preserveStudyQueue && studySessionActive && activeStudyQueue.isEmpty()
        if (finishingSession) {
            // Publish the completion surface before the expensive full-plan rebuild.
            // Keep the session logically active until the final state is committed so
            // no lifecycle/Compose callback can start a replacement session during the
            // save window.
            mutableState.value = current.copy(
                prompt = null,
                ratingInProgress = true,
                statusMessage = "Saving session…"
            )
        }
        val canReusePlan = preserveStudyQueue && studySessionActive && activeStudyQueue.isNotEmpty() && current.sessionPlan != null
        val freshPlan = if (canReusePlan) current.sessionPlan!! else sessionPlanner.plan(
            includeReaderInsights = includeReaderInsights
        )
        if (!canReusePlan) settings.planSkeletonCardIds = freshPlan.reviewQueue.take(5).joinToString(",") { it.card.id.toString() }
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
        // A normal sitting is a refillable daily target, not a one-shot adaptive
        // slice. If the first slice runs out before 40 completed cards, append the
        // fresh plan instead of publishing a misleading session-complete screen.
        val sessionTarget = settings.sessionSize.coerceAtLeast(1)
        val shouldRefillSession = preserveStudyQueue && studySessionActive && activeStudyQueue.isEmpty() &&
            current.sessionCompletedCards < sessionTarget && freshPlan.reviewQueue.isNotEmpty()
        if (shouldRefillSession) {
            activeStudyQueue += freshPlan.reviewQueue
            sessionOriginCardIds += freshPlan.reviewQueue.map { it.card.id }
        }
        val plan = if (preserveStudyQueue && studySessionActive) {
            freshPlan.copy(reviewQueue = activeStudyQueue.toList())
        } else freshPlan
        syncSessionQueueFromDurablePlan()
        // Personalize FSRS from the learner's own history: first re-fit the weight
        // subset (so the curve used below reflects the latest fit), then nudge the
        // global interval multiplier toward target, then adapt the daily new-card load.
        maybeRefitWeights()
        recalibrateScheduling(plan.dashboardStats)
        adaptDailyLoad(plan)
        val step = keepStep
        // Every full (non-reused) rebuild used to construct a brand-new ReviewUiState
        // without threading fluencyForecast through at all, silently resetting it to
        // the class default (null) — refreshReaderInsights()'s one-time cold-start
        // computation was the only thing that ever set it, and the very next real
        // session action (first card shown, first review) wiped it out again. That's
        // what made the "days to fluency" forecast look like it had vanished.
        //
        // Always THREAD IT THROUGH rather than recomputing it here: an earlier fix
        // called repository.getFluencyForecast() on this exact synchronous path,
        // which measured 34s on-device (telemetry: plan_published loadMs=34389) —
        // FluencySimEngine.simulate() runs a real day-by-day projection up to 10
        // simulated years, and this whole function runs on the Main dispatcher (see
        // the loadSession doc comment on why it must). That's a straight freeze, not
        // a "slow_load" nuisance. Recomputation now happens only in
        // maybeRefreshFluencyForecast() below, off the Main dispatcher, at most once
        // per local day.
        val forecast = current.fluencyForecast
        val goalStatus = current.goalStatus
        val showGoalOffTrackPrompt = current.showGoalOffTrackPrompt
        maybeRefreshFluencyForecast()
        val allReaders = if (canReusePlan || !includeReaderInsights) current.allReaderTexts else repository.readerTexts()
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
        } else if (current.selectedReaderTextId != null) {
            selectedReader?.text?.let { repository.readerTokens(it) }.orEmpty()
        } else {
            // Merely recommending a reader on Dashboard/Practice must not tokenize
            // its full body or build the morphology index. openReaderTextNow() does
            // that work when the learner actually opens a text.
            emptyList()
        }
        // Spend a streak-insurance credit exactly once per specific gap day: the
        // repository's streak computation is a pure recomputation over config()
        // snapshots (it can't write settings), so the actual deduction happens
        // here, guarded by lastInsuredGapDay so re-loading the same already-
        // charged gap (e.g. reopening the app the same day) can't double-spend.
        plan.gamification.insuredGapDay?.let { gapDay ->
            if (gapDay != settings.lastInsuredGapDay && settings.restDayCredits > 0) {
                settings.restDayCredits -= 1
                settings.lastInsuredGapDay = gapDay
            }
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
            fluencyForecast = forecast,
            goalStatus = goalStatus,
            showGoalOffTrackPrompt = showGoalOffTrackPrompt,
            readerRecommendation = readerRecommendation,
            allReaderTexts = allReaders,
            readerTokens = readerTokens,
            readerBookmarks = if (current.selectedReaderTextId != null) current.readerBookmarks else emptyList(),
            readerHistory = if (current.selectedReaderTextId != null) current.readerHistory else emptyList(),
            dashboardStats = plan.dashboardStats.copy(intervalModifier = settings.intervalModifier),
            importText = current.importText,
            exportText = current.exportText,
            readerTitle = current.readerTitle,
            readerBody = current.readerBody,
            readerSource = current.readerSource,
            selectedReaderTextId = current.selectedReaderTextId,
            readerProgressByText = readerProgressByText,
            statusMessage = status,
            sessionStep = step,
            ratingInProgress = current.ratingInProgress,
            canUndo = reviewTransactions.canUndo(),
            dailyGoalSetting = settings.dailyGoal,
            sessionSizeSetting = settings.sessionSize,
            newCardsPerDaySetting = settings.newCardsPerDay,
            retentionSetting = settings.desiredRetention,
            goalTargetLevelSetting = settings.goalTargetLevel,
            goalTargetDateEpochDaySetting = settings.goalTargetDateEpochDay,
            goalFeasibilityPreview = current.goalFeasibilityPreview,
            adaptiveEnabled = settings.adaptiveEnabled,
            reminderEnabled = settings.reminderEnabled,
            reminderHour = settings.reminderHour,
            readerFontScale = settings.readerFontScale,
            backupTreeUri = settings.backupTreeUri,
            automaticPublicBackupEnabled = settings.automaticPublicBackupEnabled,
            externalBackupEncryptionConfigured = repository.isExternalBackupEncryptionConfigured(),
            contentProvenance = repository.curriculumProvenance(),
            backupLastSuccessAt = settings.lastBackupAt,
            backupLastSizeBytes = settings.lastBackupSizeBytes,
            backupLastValidatedAt = settings.lastBackupValidatedAt,
            backupLastDurableAt = settings.lastDurableBackupAt,
            restDayCredits = settings.restDayCredits,
            weeklyReports = repository.weeklyReports(),
            checkpointSession = current.checkpointSession,
            checkpointIndex = current.checkpointIndex,
            checkpointResults = current.checkpointResults,
            checkpointFeedback = current.checkpointFeedback,
            checkpointCalibration = repository.checkpointCalibration(),
            skeletonReady = current.skeletonReady,
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
            sessionStoppedEarly = current.sessionStoppedEarly,
            stoppedQueueRemaining = current.stoppedQueueRemaining,
            temporarySessionMode = temporarySessionMode,
            inSessionReading = current.inSessionReading,
            readerCheckpointMistakes = current.readerCheckpointMistakes,
            inStudySession = current.inStudySession,
            leeches = current.leeches,
            session = sessionState,
            showOnboarding = !settings.onboardingCompleted
        )
        // A rebuilt session always presents a fresh card, so clear any in-progress
        // input (this mirrors the old ReviewUiState rebuild that defaulted it to "").
        mutableTypedAnswer.value = ""
        mutableCorrectionAnswer.value = ""
        val nextPrompt = mutableState.value.prompt
        if (nextPrompt != null && nextPrompt.card.id != previousPromptId) {
            promptShownAt = clock.now()
            answerRevealedAt = 0L
            repository.recordTelemetry(telemetryForPrompt("card_shown", nextPrompt).copy(
                metadataJson = pedagogyMetadata(nextPrompt)
                    .put("state", nextPrompt.card.state.name)
                    .put("reps", nextPrompt.card.reps)
                    .put("lapses", nextPrompt.card.lapses)
                    .toString()
            ))
            viewModelScope.launch {
                repository.recordBanditExposure(
                    card = nextPrompt.card,
                    action = nextPrompt.card.cardType.name,
                    context = banditContext(),
                    showAt = promptShownAt,
                    fatigue = FatigueModel.estimate(responseSamples.map { it.first }, responseSamples.map { it.second })
                )
            }
            if (!nextPrompt.exampleSentence.isNullOrBlank()) {
                repository.recordTelemetry(telemetryForPrompt("context_card_shown", nextPrompt))
            }
            val lessonBody = nextPrompt.lesson?.body.orEmpty()
            if (lessonBody.any { it.startsWith("Cognate fast-track:") }) {
                repository.recordTelemetry(telemetryForPrompt("cognate_fasttrack", nextPrompt))
            }
            if (lessonBody.any { it.startsWith("Useful chunks:") }) {
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
            val rivalPerformance = repository.expectedRivalPerformance(objectiveAttempts.map { it.itemId })
            val report = repository.finishAdaptiveSession(
                observedMinutes = effectiveMinutes,
                fatigue = fatigue,
                debtRatio = mutableState.value.sessionPlan?.pace?.debtRatio ?: 0.0,
                completed = true,
                cleanFinish = !fatigueAdjusted,
                perfYou = performance,
                perfRival = rivalPerformance,
                rankedMatch = objectiveAttempts.size >= MIN_RANKED_MATCH_CARDS,
                stoppedEarly = mutableState.value.sessionStoppedEarly
            )
            maybeBackup(force = true)
            val day = clock.now() / (24L * 60 * 60 * 1000)
            val streak = mutableState.value.sessionPlan?.gamification?.currentStreak ?: 0
            if (streak >= 7 && streak % 7 == 0 && settings.lastRestCreditAwardDay != day) {
                settings.restDayCredits = (settings.restDayCredits + 1).coerceAtMost(2)
                settings.lastRestCreditAwardDay = day
            }
            studySessionActive = false
            studyPausedAt = null
            mutableState.value = mutableState.value.copy(
                inStudySession = false,
                matchReport = report,
                ratingInProgress = false,
                statusMessage = status
            )
            dispatchSession(SessionEvent.Clear)
            maybeOfferExitTicket()
        } else if (preserveStudyQueue && studySessionActive) {
            maybeStartScheduledReading()
        }
        // Flag main-thread-relevant slowness on full rebuilds (the cold-start path),
        // now that the heavy work is dispatched off the UI thread by sessionPlan().
        if (!canReusePlan) {
            val loadMs = clock.now() - loadStartedAt
            repository.recordTelemetry(TelemetryEvent(
                eventType = "plan_published",
                sessionId = telemetrySessionId,
                sessionRemaining = plan.reviewQueue.size,
                dueCount = plan.dailyPlan.dueVocab + plan.dailyPlan.dueGrammar,
                newCardLimit = settings.newCardsPerDay,
                metadataJson = JSONObject()
                    .put("loadMs", loadMs)
                    .put("step", keepStep.name)
                    .put("dailyStatus", plan.completion.status.name)
                    .put("triage", plan.dailyPlan.triageMode)
                    .put("overdueBacklog", plan.dailyPlan.overdueBacklog)
                    .put("readerInsightsIncluded", includeReaderInsights)
                    .put("stopPolicy", plan.pace?.stretchStopPolicy?.name)
                    .put("adaptiveNewBudget", plan.pace?.newItemBudget)
                    .put("blueprintNewBudget", plan.blueprint?.newBudget)
                    .put("blueprintReviewBudget", plan.blueprint?.reviewBudget)
                    .put("adaptiveTrust", plan.adaptiveTrust)
                    .toString()
            ))
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
        // Keep the save indicator visible for the whole reload, then release it only
        // after the new prompt or completion state has been published.
        if (mutableState.value.ratingInProgress) {
            mutableState.value = mutableState.value.copy(ratingInProgress = false)
        }
    }

    /** Fill reader recommendations and coverage after the first usable UI state is
     * visible. This work scales with the entire reader library, not today's queue. */
    private suspend fun refreshReaderInsights() {
        val readers = repository.readerTexts()
        val stats = repository.dashboardStats(recommendations = readers)
        val forecast = repository.getFluencyForecast()
        val goalStatus = repository.currentGoalStatus(forecast)
        val current = mutableState.value
        val recommendation = recommendNextReader(readers)
        mutableState.value = current.copy(
            allReaderTexts = readers,
            readerRecommendation = recommendation,
            readerProgressByText = readers.associate { it.text.id to settings.readerProgress(it.text.id) },
            dashboardStats = stats.copy(intervalModifier = settings.intervalModifier),
            fluencyForecast = forecast,
            goalStatus = goalStatus,
            sessionPlan = current.sessionPlan?.copy(
                readerRecommendation = recommendation,
                dashboardStats = stats
            )
        )
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
        val now = clock.now()
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

    /**
     * Pull-to-refresh on the Dashboard/Practice/Lab tabs. The daily plan already
     * recomputes on its own on every cold start and after every review — what was
     * missing was a way for the learner to *ask* for that recompute on demand
     * instead of it only ever happening implicitly, which is what made new
     * material feel like it was silently trickling in on every reopen rather
     * than being predictably "all there" the moment it changed. This doesn't
     * change what generates the plan, only gives explicit, visible control over
     * when it's asked to run again.
     */
    fun refreshNow() {
        if (mutableState.value.isRefreshing || studySessionActive) return
        mutableState.value = mutableState.value.copy(isRefreshing = true)
        viewModelScope.launch {
            runCatching { loadSession(preserveStudyQueue = false) }
            mutableState.value = mutableState.value.copy(isRefreshing = false)
        }
    }

    /** Once per local day, move the new-word cap by at most two. Backlog and weak
     * retention reduce load; strong retention plus a light forecast adds one. */
    private fun adaptDailyLoad(plan: SessionPlan) {
        val now = clock.now()
        val offset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val day = (now + offset) / (24L * 60 * 60 * 1000)
        if (settings.lastAdaptiveLoadDay == day) return
        if (settings.adaptiveBoostDay == day) {
            settings.lastAdaptiveLoadDay = day
            return
        }
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

    /**
     * At most once per local day, recompute the "days to fluency" forecast in the
     * background. FluencySimEngine.simulate() runs a real day-by-day projection up
     * to 10 simulated years and measured 34s on this account's history — running it
     * on loadSession()'s own (Main-dispatcher) path froze the UI outright, which is
     * a strictly worse failure mode than the forecast just being a day stale. This
     * fires from every loadSession() call but only actually does the expensive work
     * once daily, off Main, and writes back with `.copy()` so it can't race/clobber
     * whatever loadSession() itself just finished computing.
     */
    private fun maybeRefreshFluencyForecast() {
        val now = clock.now()
        val offset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val day = (now + offset) / (24L * 60 * 60 * 1000)
        if (settings.lastFluencyForecastDay == day) return
        settings.lastFluencyForecastDay = day
        viewModelScope.launch(computeDispatcher) {
            val forecast = runCatching { repository.getFluencyForecast() }.getOrNull() ?: return@launch
            // Weekly cadence differs from this daily forecast refresh, so it needs
            // its own throttle even though it piggybacks on the same background
            // block (reusing the forecast this block already paid the simulation
            // cost for, rather than running a second one on its own schedule).
            val weekElapsed = settings.goalLastWeeklyCheckDay == Long.MIN_VALUE || day - settings.goalLastWeeklyCheckDay >= 7
            var offTrackThisWeek = false
            val goalStatus = runCatching {
                if (weekElapsed) {
                    settings.goalLastWeeklyCheckDay = day
                    repository.weeklyGoalCheck(forecast, now)?.also {
                        offTrackThisWeek = it.state == com.sibirskyspeak.learning.GoalTrackState.OFF_TRACK
                    }
                } else {
                    repository.currentGoalStatus(forecast, now)
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                mutableState.value = mutableState.value.copy(
                    fluencyForecast = forecast,
                    goalStatus = goalStatus,
                    showGoalOffTrackPrompt = mutableState.value.showGoalOffTrackPrompt || offTrackThisWeek
                )
            }
        }
    }

    /**
     * Clears every per-sitting tracking collection/flag. Shared by [startStudySession] and
     * [debugStartSessionWithCardType] so the two can't drift out of sync — a prior real
     * session's fatigue/flow/reading-interstitial state must never leak into the next one.
     */
    private fun resetSessionTrackingState() {
        sessionTracking.resetCounters()
        failureCounts.clear()
        acquisitionSuccesses.clear()
        acquisitionFastStreak.clear()
        responseSamples.clear()
        objectiveAttempts.clear()
        sessionShownNotes.clear()
        sessionShownHard.clear()
        sessionShownTypes.clear()
        lapsedShownAt.clear()
        graceGrantedCardIds.clear()
        sessionErrorCategoryCounts.clear()
        fatigueAdjusted = false
        flowOffered = false
        recoveryWindowRemaining = 0
        recoveryAttempted = false
        scheduledReadingPresented = false
    }

    /** Entry point for every "Study" CTA. Session shape is no longer picked at plan
     * time (QUICK/FULL/STRETCH) — the queue is always the plan's generously-sized
     * pool, and how far into it the learner actually gets is a real-time decision
     * (see SessionMpcController) rather than something pre-declared here. */
    fun startRecommendedSession() {
        startStudySession()
    }

    /** Reset the per-sitting counters when the learner (re)opens the study screen. */
    fun startStudySession() {
        startStudySession(recordStartTelemetry = true)
    }

    private fun startStudySession(recordStartTelemetry: Boolean) {
        if (studySessionActive) return
        resetSessionTrackingState()
        studySessionActive = true
        studyPausedAt = null
        sessionStartedAt = clock.now()
            telemetrySessionId = UUID.randomUUID().toString()
            lastPauseSignature = null
            mutableState.value = mutableState.value.copy(
                sessionStoppedEarly = false,
                stoppedQueueRemaining = 0
            )
        promptShownAt = clock.now()
        answerRevealedAt = 0L
        val plan = mutableState.value.sessionPlan
        val requestedMode = temporarySessionMode
        val plannedQueue = plan?.let { TemporarySessionPolicy.queue(it, requestedMode) }.orEmpty()
        temporarySessionMode = TemporarySessionMode.BALANCED
        // Nothing to review, but a reading is due: open it directly instead of (a)
        // running the review-session bookkeeping below — recordPace/observeReturn's
        // willingness update/session_start telemetry are meant to reflect real review
        // activity, and would otherwise log a false zero-review data point every time
        // the learner just wants to read — and (b) synchronously showing
        // prompt = null ("Session complete") for a frame before the async block below
        // would otherwise get around to opening the reader. openReaderTextNow records
        // its own "scheduled_reading_shown" telemetry, so nothing is left unrecorded.
        val readingAssignment = plan?.readingAssignment
        if (plannedQueue.isEmpty() && readingAssignment != null) {
            activeStudyQueue.clear()
            sessionOriginCardIds.clear()
            mutableCorrectionAnswer.value = ""
            scheduledReadingPresented = true
            mutableState.value = mutableState.value.copy(
                sessionPlan = plan.copy(reviewQueue = plannedQueue),
                sessionReviewed = 0,
                sessionCorrect = 0,
                sessionCompletedCards = 0,
                sessionProgressCompleted = 0,
                sessionProgressTotal = 0,
                correctionRequired = false,
                correctionAccepted = false,
                fatigueAdjusted = false,
                matchReport = null,
                inStudySession = true,
                temporarySessionMode = TemporarySessionMode.BALANCED
            )
            viewModelScope.launch { openReaderTextNow(readingAssignment.recommendation.text.id, inSession = true) }
            return
        }
        // Nothing to review and no reading fallback either. The cached plan can be
        // stale, or BlueprintBuilder's newBudget/reviewBudget can simply be an
        // accuracy/capacity estimate that doesn't match what sessionCards() actually
        // found available (see BlueprintBuilder.build) — either way, faking a session
        // here used to flip studySessionActive, show "Session complete" for a frame,
        // and log a zero-progress session_start/session_paused telemetry pair. That is
        // what surfaced to the learner as sessions repeatedly "getting cut short."
        // Decline to enter a session, report the real status, and refresh the plan in
        // the background so a stale cache can't cause the same false start twice.
        if (plannedQueue.isEmpty()) {
            studySessionActive = false
            mutableState.value = mutableState.value.copy(
                statusMessage = plan?.completion?.message ?: "Nothing ready right now.",
                temporarySessionMode = TemporarySessionMode.BALANCED
            )
            viewModelScope.launch { loadSession(status = null) }
            return
        }
        activeStudyQueue.clear()
        activeStudyQueue += plannedQueue
        sessionOriginCardIds.clear()
        sessionOriginCardIds += activeStudyQueue.map { it.card.id }
        mutableCorrectionAnswer.value = ""
        mutableState.value = mutableState.value.copy(
            sessionPlan = plan?.copy(reviewQueue = plannedQueue),
            prompt = activeStudyQueue.firstOrNull(),
            sessionReviewed = 0,
            sessionCorrect = 0,
            sessionCompletedCards = 0,
            sessionProgressCompleted = 0,
            sessionProgressTotal = sessionOriginCardIds.size,
            correctionRequired = false,
            correctionAccepted = false,
            fatigueAdjusted = false,
            matchReport = null,
            inStudySession = true,
            temporarySessionMode = TemporarySessionMode.BALANCED
        )
        dispatchSession(SessionEvent.Start(
            queueCardIds = activeStudyQueue.map { it.card.id },
            sessionId = telemetrySessionId ?: UUID.randomUUID().toString(),
            startedAt = sessionStartedAt
        ))
        if (recordStartTelemetry) {
            viewModelScope.launch { recordSessionStartTelemetry(plan) }
        }
    }

    fun startMicroSession() {
        // The short-session route must trim before session_start telemetry and the
        // durable session snapshot are written. Starting the normal route first
        // used to record a full queue, then overwrite it with three cards, which
        // made adaptive completion metrics and resume behavior disagree.
        startStudySession(recordStartTelemetry = false)
        // A reading-only plan intentionally leaves the session guard active while
        // it opens the reader, but it has no review queue to trim. Never emit a
        // zero-card session_start or dispatch an empty resumable session for that
        // case.
        if (!studySessionActive || activeStudyQueue.isEmpty()) return
        // Trim the plan's generously-sized queue down and bias it toward at-risk
        // reviews for this deliberately short, manually-triggered session — but
        // never let the preference empty the queue outright (e.g. a new account
        // with only NEW cards planned and nothing yet due for review).
        val atRisk = mutableState.value.sessionPlan?.blueprint?.atRiskCardIds.orEmpty()
        val preferred = activeStudyQueue.filter { it.card.id in atRisk }
            .ifEmpty { activeStudyQueue.filter { it.card.state != com.sibirskyspeak.data.CardState.NEW } }
            .ifEmpty { activeStudyQueue }
        activeStudyQueue.clear()
        activeStudyQueue += preferred
        while (activeStudyQueue.size > 3) activeStudyQueue.removeAt(activeStudyQueue.lastIndex)
        sessionOriginCardIds.clear(); sessionOriginCardIds += activeStudyQueue.map { it.card.id }
        mutableState.value = mutableState.value.copy(
            sessionPlan = mutableState.value.sessionPlan?.copy(reviewQueue = activeStudyQueue.toList()),
            prompt = activeStudyQueue.firstOrNull(), sessionProgressTotal = activeStudyQueue.size
        )
        dispatchSession(SessionEvent.Start(
            queueCardIds = activeStudyQueue.map { it.card.id },
            sessionId = telemetrySessionId ?: UUID.randomUUID().toString(),
            startedAt = sessionStartedAt
        ))
        viewModelScope.launch { recordSessionStartTelemetry(mutableState.value.sessionPlan) }
    }

    private suspend fun recordSessionStartTelemetry(plan: SessionPlan?) {
        repository.observeReturn(sessionStartedAt)
        plan?.pace?.let { repository.recordPace(it, sessionStartedAt) }
        activeStudyQueue.firstOrNull()?.let { first ->
            repository.recordBanditExposure(
                card = first.card,
                action = first.card.cardType.name,
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
                .put("stopPolicy", plan?.pace?.stretchStopPolicy?.name ?: "UNKNOWN")
                .toString()
        ))
        maybeStartScheduledReading()
    }

    /**
     * Debug-only: jump straight into a single card of [cardType] instead of waiting
     * for the adaptive session to eventually surface one. Marked `practiceOnly` so
     * rating it runs the existing unscheduled-prompt path (see [rateUnscheduledPrompt])
     * and never touches the card's real FSRS state — this is a preview, not a review.
     *
     * Deliberately skips [startStudySession]'s `session_start` telemetry, bandit-exposure
     * recording, pace recording, and scheduled-reading check — a debug preview isn't real
     * usage and shouldn't feed the adaptive models. It also doesn't filter by [CardState] or
     * concept-gating, so it can surface a card a real learner couldn't reach yet (e.g. a
     * grammar drill still locked behind an unintroduced lesson concept) — that's fine for
     * previewing what a card TYPE looks like, just don't read it as "what the queue would
     * show next."
     */
    /** See MainActivity's `--ez debug_freeze_adaptive` intent extra: lets a debug build be
     * driven manually (adb, QA pass) without capacity/willingness/rival/pace-log writes
     * polluting the real learner's adaptive model. Enforced here too, not just at the
     * intent-reading call site, for the same reason [debugStartSessionWithCardType] does. */
    fun setDebugFreezeAdaptiveModel(frozen: Boolean) {
        if (!com.sibirskyspeak.BuildConfig.DEBUG) return
        repository.debugFreezeAdaptiveModel = frozen
    }

    fun debugStartSessionWithCardType(cardType: com.sibirskyspeak.data.CardType, onStarted: () -> Unit = {}) {
        // The Settings screen already hides the button behind BuildConfig.DEBUG, but that's
        // a UI-layer gate a future screen could forget to repeat — bypassing the adaptive
        // queue is not something a release build should ever be able to trigger, so enforce
        // it here too.
        if (!com.sibirskyspeak.BuildConfig.DEBUG) return
        if (studySessionActive) {
            mutableState.value = mutableState.value.copy(
                statusMessage = "Exit the current session before jumping to a debug card type."
            )
            return
        }
        // A prior session's "undo last review" affordance can still be armed here (canUndo
        // survives a session-complete transition) and would otherwise try to restore a
        // snapshot of a queue this call is about to replace — clear it first, matching
        // suspendCurrentCard/markCurrentWordKnown.
        reviewTransactions.clearUndo()
        queueBeforeLastReview = null
        // Claim the slot synchronously, before the suspending DB lookup below, so a second
        // rapid tap (e.g. two different debug buttons) can't also pass the guard above while
        // this call is still in flight — mirrors startStudySession, which sets this flag
        // before doing any suspending work for the same reason. Released again if the lookup
        // fails or finds no matching card, so a dead end doesn't wedge the app in "session
        // active" with nothing actually running.
        studySessionActive = true
        viewModelScope.launch {
            runCatching { repository.debugPromptForCardType(cardType) }
                .onSuccess { debugPrompt ->
                    if (debugPrompt == null) {
                        studySessionActive = false
                        mutableState.value = mutableState.value.copy(
                            statusMessage = "No ${cardType.name} card exists yet in this database."
                        )
                        return@onSuccess
                    }
                    resetSessionTrackingState()
                    studyPausedAt = null
                    sessionStartedAt = clock.now()
                    telemetrySessionId = UUID.randomUUID().toString()
                    lastPauseSignature = null
                    promptShownAt = clock.now()
                    answerRevealedAt = 0L
                    activeStudyQueue.clear()
                    activeStudyQueue += debugPrompt.copy(practiceOnly = true, queueReason = "Debug preview — not scored")
                    sessionOriginCardIds.clear()
                    sessionOriginCardIds += activeStudyQueue.map { it.card.id }
                    mutableCorrectionAnswer.value = ""
                    mutableState.value = mutableState.value.copy(
                        prompt = activeStudyQueue.firstOrNull(),
                        sessionReviewed = 0,
                        sessionCorrect = 0,
                        sessionCompletedCards = 0,
                        sessionProgressCompleted = 0,
                        sessionProgressTotal = sessionOriginCardIds.size,
                        sessionStoppedEarly = false,
                        stoppedQueueRemaining = 0,
                        correctionRequired = false,
                        correctionAccepted = false,
                        fatigueAdjusted = false,
                        matchReport = null,
                        inStudySession = true
                    )
                    dispatchSession(SessionEvent.Start(
                        queueCardIds = activeStudyQueue.map { it.card.id },
                        sessionId = telemetrySessionId ?: UUID.randomUUID().toString(),
                        startedAt = sessionStartedAt
                    ))
                    onStarted()
                }
                .onFailure {
                    studySessionActive = false
                    mutableState.value = mutableState.value.copy(
                        statusMessage = safeUserMessage(it, "Could not load a $cardType card.")
                    )
                }
        }
    }

    /** Keep the frozen queue resumable, but make pauses visible in telemetry. */
    fun recordStudyScreenExit() {
        if (!studySessionActive) return
        if (studyPausedAt == null) studyPausedAt = clock.now()
        dispatchSession(SessionEvent.Pause(studyPausedAt ?: clock.now()))
        val signature = "$telemetrySessionId:${activeStudyQueue.size}:${mutableState.value.sessionCompletedCards}"
        if (lastPauseSignature == signature) return
        lastPauseSignature = signature
        viewModelScope.launch {
            repository.recordTelemetry(TelemetryEvent(
                eventType = "session_paused",
                sessionId = telemetrySessionId,
                sessionRemaining = activeStudyQueue.size,
                metadataJson = JSONObject()
                    .put("reviewed", mutableState.value.sessionReviewed)
                    .put("actions", mutableState.value.sessionCompletedCards)
                    .put("dailyStatus", mutableState.value.sessionPlan?.completion?.status?.name)
                    .put("dueCount", mutableState.value.dailyPlan?.let { it.dueVocab + it.dueGrammar })
                    .toString()
            ))
        }
    }

    /** Exclude time spent backgrounded or outside Study from response-time signals. */
    fun recordStudyScreenResume() {
        if (!studySessionActive) return
        val pausedAt = studyPausedAt ?: return
        val resumedAt = clock.now()
        val pausedMs = (resumedAt - pausedAt).coerceAtLeast(0)
        promptShownAt += pausedMs
        if (answerRevealedAt > 0) answerRevealedAt += pausedMs
        studyPausedAt = null
        dispatchSession(SessionEvent.Resume)
        lastPauseSignature = null
        viewModelScope.launch {
            repository.recordTelemetry(TelemetryEvent(
                eventType = "session_resumed",
                sessionId = telemetrySessionId,
                sessionRemaining = activeStudyQueue.size,
                metadataJson = JSONObject().put("pausedMs", pausedMs).toString()
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
        // Reference format for response-time normalization in recordResponseSample();
        // an arbitrary but stable denominator, not tied to any particular AnswerMode.
        const val FATIGUE_REFERENCE_TARGET_MS = 12_000.0
        // Matches the "instant_recall" threshold already used to interpret a self-rated
        // EASY review elsewhere (recordReviewTelemetry) — kept the same value so "fast"
        // means the same thing everywhere in this file, not a second tunable to drift.
        const val FAST_RECALL_MS = 3_000L
        // handleFailure's scaffold escalates by failure count: 1 = repair, 2-3 = hints,
        // 4+ = full-form reveal. A failure past the reveal (5+) means the scaffold
        // ceiling didn't help — bench the card for the rest of this sitting rather
        // than looping it (see SITTING_BENCH_THRESHOLD usage in handleFailure).
        const val SITTING_BENCH_THRESHOLD = 4
        // Confidence-rebuild window length after SessionMpcController.decide returns
        // RECOVER (see advanceFrozenQueue) — short enough to not derail a session that
        // was actually going fine, long enough to give struggle a real chance to ease.
        const val RECOVERY_WINDOW_SIZE = 3
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
                mutableState.value = mutableState.value.copy(statusMessage = safeUserMessage(it, "Could not release leech."))
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
                mutableState.value = mutableState.value.copy(statusMessage = safeUserMessage(it, "Could not update leech."))
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
            }.onFailure { mutableState.value = mutableState.value.copy(statusMessage = safeUserMessage(it, "Could not update card.")) }
        }
    }

    private fun promptForStep(step: SessionStep, plan: SessionPlan?): ReviewPrompt? =
        when (step) {
            SessionStep.REVIEWS -> plan?.reviewQueue?.firstOrNull()
            SessionStep.BLOCKED -> plan?.blockedGrammar?.firstOrNull()
            SessionStep.INTERLEAVED -> plan?.interleavedGrammar?.firstOrNull()
            SessionStep.RULE, SessionStep.READER, SessionStep.IMPORT, SessionStep.DASHBOARD, SessionStep.LAB -> null
        }

    private fun recommendNextReader(texts: List<ReaderRecommendation>): ReaderRecommendation? =
        recommendNextReaderUi(texts, settings::readerProgress)
}

internal fun recoveryQueueAfter(
    queue: List<ReviewPrompt>,
    current: ReviewPrompt,
    rating: Rating,
    repairPrompt: ReviewPrompt? = null,
    bench: Boolean = false
): List<ReviewPrompt> {
    val remaining = queue.toMutableList()
    if (remaining.firstOrNull()?.card?.id == current.card.id) remaining.removeAt(0) else remaining.remove(current)
    // Benched: the scaffold ceiling (repair -> hints -> full-form reveal) already
    // failed once more. FSRS already recorded the lapse via the normal AGAIN
    // rating that ran before this — grinding further in one sitting is the classic
    // Anki leech loop, not learning. Just drop it from today's queue.
    if (bench) return remaining
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
