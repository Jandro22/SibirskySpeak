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
import com.sibirskyspeak.data.WordStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Minimum gap between automatic full-state backups (~once per active day). */
private const val BACKUP_INTERVAL_MS = 20L * 60 * 60 * 1000

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
    val readerLookupInProgress: Boolean = false,
    val statusMessage: String? = null,
    val sessionStep: SessionStep = SessionStep.REVIEWS,
    val ratingInProgress: Boolean = false,
    val autoRatedAgain: Boolean = false,
    val canUndo: Boolean = false,
    // Settings mirror (persisted in SettingsStore; surfaced for the Settings UI).
    val dailyGoalSetting: Int = SettingsStore.DEFAULT_DAILY_GOAL,
    val sessionSizeSetting: Int = SettingsStore.DEFAULT_SESSION_SIZE,
    val newCardsPerDaySetting: Int = SettingsStore.DEFAULT_NEW_CARDS_PER_DAY,
    val retentionSetting: Double = SettingsStore.DEFAULT_RETENTION,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = SettingsStore.DEFAULT_REMINDER_HOUR,
    val readerFontScale: Float = 1.0f,
    // Deck search.
    val searchQuery: String = "",
    val searchResults: List<Note> = emptyList(),
    // Furthest token the user has reached in the open reader text ("continue reading").
    val readerProgressIndex: Int = 0,
    // Achievements unlocked since the user last looked (for the celebratory toast).
    val newlyUnlocked: List<Achievement> = emptyList()
)

class ReviewViewModel(
    private val repository: LearningRepository,
    private val settings: SettingsStore
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()

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

    fun setSessionStep(step: SessionStep) {
        mutableState.value = mutableState.value.copy(sessionStep = step, prompt = promptForStep(step, mutableState.value.sessionPlan))
    }

    fun setTypedAnswer(value: String) {
        mutableState.value = mutableState.value.copy(typedAnswer = value)
    }

    fun chooseAnswer(value: String) {
        mutableState.value = mutableState.value.copy(typedAnswer = value)
        reveal()
    }

    fun reveal() {
        val prompt = mutableState.value.prompt ?: return
        val evaluation = when (prompt.answerMode) {
            AnswerMode.ENGLISH -> {
                val correct = isEnglishAnswerCorrect(prompt.expectedAnswer, mutableState.value.typedAnswer)
                AnswerEvaluation(if (correct) AnswerMatch.EXACT else AnswerMatch.WRONG, prompt.expectedAnswer)
            }
            AnswerMode.RUSSIAN_TYPED, AnswerMode.AUDIO_ONLY -> evaluateRussianAnswer(prompt.expectedAnswer, mutableState.value.typedAnswer)
            AnswerMode.CHOICE -> {
                val correct = mutableState.value.typedAnswer.trim().equals(prompt.expectedAnswer.trim(), ignoreCase = true)
                AnswerEvaluation(if (correct) AnswerMatch.EXACT else AnswerMatch.WRONG, prompt.expectedAnswer)
            }
        }
        mutableState.value = mutableState.value.copy(
            revealed = true,
            isAnswerCorrect = evaluation.accepted,
            answerMatch = evaluation.match,
            answerFeedback = evaluation.message
        )
        if (!evaluation.accepted) {
            mutableState.value = mutableState.value.copy(ratingInProgress = true)
            viewModelScope.launch {
                runCatching {
                    repository.review(prompt.card, Rating.AGAIN)
                }.onSuccess {
                    mutableState.value = mutableState.value.copy(ratingInProgress = false, autoRatedAgain = true)
                }.onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        ratingInProgress = false,
                        statusMessage = error.message ?: "Could not save review"
                    )
                }
            }
        }
    }

    fun rate(rating: Rating) {
        val prompt = mutableState.value.prompt ?: return
        if (mutableState.value.autoRatedAgain) return
        if (mutableState.value.ratingInProgress) return
        mutableState.value = mutableState.value.copy(ratingInProgress = true)
        viewModelScope.launch {
            runCatching {
                repository.review(prompt.card, rating)
            }.onSuccess {
                loadSession()
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    ratingInProgress = false,
                    statusMessage = error.message ?: "Could not save review"
                )
            }
        }
    }

    fun continueAfterRating() {
        viewModelScope.launch { loadSession() }
    }

    /**
     * Roll back the last committed review and re-present that card. Works both for
     * explicit ratings and for the silent auto-AGAIN on a missed answer.
     */
    fun undoLastReview() {
        if (mutableState.value.ratingInProgress) return
        viewModelScope.launch {
            val restored = repository.undoLastReview() ?: return@launch
            loadSession(status = "Undid last review")
            // Surface the restored card again, fresh, regardless of queue order.
            val prompt = repository.promptForCard(restored)
            if (prompt != null) {
                mutableState.value = mutableState.value.copy(
                    prompt = prompt,
                    revealed = false,
                    typedAnswer = "",
                    isAnswerCorrect = null,
                    answerMatch = null,
                    answerFeedback = null,
                    autoRatedAgain = false
                )
            }
        }
    }

    /**
     * "I actually knew this" escape hatch after a typed answer was auto-failed.
     * Rolls back the silent AGAIN and reopens the rating buttons so the learner
     * can grade their true recall.
     */
    /** Retire the current card permanently (bad auto-generated content). */
    fun suspendCurrentCard() {
        val prompt = mutableState.value.prompt ?: return
        if (mutableState.value.ratingInProgress) return
        viewModelScope.launch {
            runCatching { repository.suspendCard(prompt.card) }
                .onSuccess { loadSession(status = "Card suspended — it won't come back.") }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = it.message ?: "Could not suspend card") }
        }
    }

    fun overrideKnewIt() {
        if (!mutableState.value.autoRatedAgain) return
        viewModelScope.launch {
            repository.undoLastReview()
            mutableState.value = mutableState.value.copy(
                autoRatedAgain = false,
                revealed = true,
                isAnswerCorrect = true,
                answerMatch = AnswerMatch.EXACT,
                ratingInProgress = false
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
            mutableState.value = mutableState.value.copy(
                allReaderTexts = refreshedTexts,
                readerRecommendation = refreshedTexts.minWithOrNull(compareBy<ReaderRecommendation> { distanceFromTargetUi(it.coverage) }.thenByDescending { it.coverage }),
                readerTokens = repository.readerTokens(selected.text),
                selectedToken = null,
                statusMessage = "Marked $count words ${status.name.lowercase()}"
            )
        }
    }

    fun lookupReaderToken(token: String) {
        val recommendation = mutableState.value.currentReaderRecommendation() ?: return
        val normalized = mutableState.value.readerTokens.firstOrNull { it.surface == token }?.normalized
        mutableState.value = mutableState.value.copy(readerLookupInProgress = true, lookupResult = "Looking up $token...")
        viewModelScope.launch {
            val note = repository.readerLookup(token, recommendation.text)
            val refreshedTexts = repository.readerTexts()
            val selected = refreshedTexts.firstOrNull { it.text.id == recommendation.text.id } ?: recommendation
            val tokens = repository.readerTokens(selected.text)
            mutableState.value = mutableState.value.copy(
                allReaderTexts = refreshedTexts,
                readerRecommendation = refreshedTexts.minWithOrNull(compareBy<ReaderRecommendation> { distanceFromTargetUi(it.coverage) }.thenByDescending { it.coverage }),
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
                readerRecommendation = refreshedTexts.minWithOrNull(compareBy<ReaderRecommendation> { distanceFromTargetUi(it.coverage) }.thenByDescending { it.coverage }),
                readerTokens = tokens,
                selectedToken = tokens.firstOrNull { it.normalized == token.normalized }
            )
        }
    }

    fun clearSelectedToken() {
        mutableState.value = mutableState.value.copy(selectedToken = null, lookupResult = null)
    }

    fun openReaderText(id: Long) {
        viewModelScope.launch {
            val recommendation = mutableState.value.allReaderTexts.firstOrNull { it.text.id == id }
                ?: repository.readerTexts().firstOrNull { it.text.id == id }
                ?: return@launch
            mutableState.value = mutableState.value.copy(
                selectedReaderTextId = id,
                readerTokens = repository.readerTokens(recommendation.text),
                selectedToken = null,
                lookupResult = null,
                readerProgressIndex = settings.readerProgress(id)
            )
        }
    }

    /** Record the furthest token index the learner has reached, for "continue reading". */
    fun recordReaderProgress(tokenIndex: Int) {
        val id = mutableState.value.selectedReaderTextId ?: return
        if (tokenIndex <= mutableState.value.readerProgressIndex) return
        settings.setReaderProgress(id, tokenIndex)
        mutableState.value = mutableState.value.copy(readerProgressIndex = tokenIndex)
    }

    fun closeReaderText() {
        mutableState.value = mutableState.value.copy(selectedReaderTextId = null, selectedToken = null, lookupResult = null)
    }

    fun setImportText(value: String) {
        mutableState.value = mutableState.value.copy(importText = value)
    }

    fun importJsonLines() {
        val payload = mutableState.value.importText
        viewModelScope.launch {
            runCatching { repository.importJsonLines(payload) }
                .onSuccess { count -> loadSession(keepStep = SessionStep.IMPORT, status = "Imported $count notes") }
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
        status: String? = mutableState.value.statusMessage
    ) {
        val plan = repository.sessionPlan()
        val step = keepStep
        val allReaders = repository.readerTexts()
        val current = mutableState.value
        val selectedReader = current.selectedReaderTextId?.let { id ->
            allReaders.firstOrNull { it.text.id == id }
        } ?: plan.readerRecommendation
        // Detect achievements unlocked since last seen, for the celebratory toast.
        val unlockedIds = plan.gamification.achievements.filter { it.unlocked }.map { it.id }.toSet()
        val freshIds = settings.newlyUnlocked(unlockedIds)
        val freshAchievements = plan.gamification.achievements.filter { it.id in freshIds }
        mutableState.value = ReviewUiState(
            prompt = promptForStep(step, plan),
            reviewedToday = repository.reviewedToday(),
            dailyPlan = plan.dailyPlan,
            sessionPlan = plan,
            readerRecommendation = plan.readerRecommendation,
            allReaderTexts = allReaders,
            readerTokens = selectedReader?.text?.let { repository.readerTokens(it) }.orEmpty(),
            dashboardStats = plan.dashboardStats,
            importText = current.importText,
            exportText = current.exportText,
            readerTitle = current.readerTitle,
            readerBody = current.readerBody,
            selectedReaderTextId = current.selectedReaderTextId,
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
            readerProgressIndex = current.selectedReaderTextId?.let { settings.readerProgress(it) } ?: 0,
            newlyUnlocked = if (freshAchievements.isNotEmpty()) freshAchievements else current.newlyUnlocked
        )
    }

    private fun promptForStep(step: SessionStep, plan: SessionPlan?): ReviewPrompt? =
        when (step) {
            SessionStep.REVIEWS -> plan?.reviewQueue?.firstOrNull()
            SessionStep.BLOCKED -> plan?.blockedGrammar?.firstOrNull()
            SessionStep.INTERLEAVED -> plan?.interleavedGrammar?.firstOrNull()
            SessionStep.RULE, SessionStep.READER, SessionStep.IMPORT, SessionStep.DASHBOARD -> null
        }
}

private fun ReviewUiState.currentReaderRecommendation(): ReaderRecommendation? =
    selectedReaderTextId?.let { id -> allReaderTexts.firstOrNull { it.text.id == id } } ?: readerRecommendation

private fun distanceFromTargetUi(coverage: Double): Double =
    when {
        coverage in 0.93..0.96 -> 0.0
        coverage < 0.93 -> 0.93 - coverage
        else -> coverage - 0.96
    }
