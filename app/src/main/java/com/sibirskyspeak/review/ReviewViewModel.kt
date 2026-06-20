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
import kotlin.math.ln

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
    // Furthest token the user has reached in the open reader text; -1 means not started.
    val readerProgressIndex: Int = -1,
    // Achievements unlocked since the user last looked (for the celebratory toast).
    val newlyUnlocked: List<Achievement> = emptyList(),
    // Per-sitting counters (reset when the study screen is opened) that drive the
    // in-session progress line and the end-of-session summary.
    val sessionReviewed: Int = 0,
    val sessionCorrect: Int = 0,
    // Parked leeches available to fix or release, for the Leeches management view.
    val leeches: List<LeechItem> = emptyList()
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
            answerFeedback = if (evaluation.accepted) evaluation.message else diagnosticFeedbackFor(prompt, mutableState.value.typedAnswer) ?: evaluation.message
        )
        if (!evaluation.accepted) {
            mutableState.value = mutableState.value.copy(ratingInProgress = true)
            viewModelScope.launch {
                runCatching {
                    repository.review(prompt.card, Rating.AGAIN)
                }.onSuccess { becameLeech ->
                    sessionCounterDeltas.addLast(SessionCounterDelta(reviewed = 1, correct = 0))
                    mutableState.value = mutableState.value.copy(
                        ratingInProgress = false,
                        autoRatedAgain = true,
                        sessionReviewed = mutableState.value.sessionReviewed + 1,
                        statusMessage = if (becameLeech) "Parked this card — it kept tripping you up. Find it under Leeches." else mutableState.value.statusMessage
                    )
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
        // A LESSON is a teaching screen, not a graded card — keep it out of the
        // sitting's accuracy so the percentage reflects real recall.
        val countable = prompt.card.cardType != com.sibirskyspeak.data.CardType.LESSON
        val wasCorrect = mutableState.value.isAnswerCorrect == true
        val delta = SessionCounterDelta(
            reviewed = if (countable) 1 else 0,
            correct = if (countable && wasCorrect) 1 else 0
        )
        mutableState.value = mutableState.value.copy(ratingInProgress = true)
        viewModelScope.launch {
            runCatching {
                repository.review(prompt.card, rating)
            }.onSuccess { becameLeech ->
                sessionCounterDeltas.addLast(delta)
                mutableState.value = mutableState.value.copy(
                    sessionReviewed = mutableState.value.sessionReviewed + delta.reviewed,
                    sessionCorrect = mutableState.value.sessionCorrect + delta.correct,
                    statusMessage = if (becameLeech) "Parked this card — it kept tripping you up. Find it under Leeches." else mutableState.value.statusMessage
                )
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
            val delta = if (sessionCounterDeltas.isNotEmpty()) sessionCounterDeltas.removeLast() else SessionCounterDelta(0, 0)
            loadSession(status = "Undid last review")
            // Surface the restored card again, fresh, regardless of queue order.
            val prompt = repository.promptForCard(restored)
            mutableState.value = mutableState.value.copy(
                prompt = prompt ?: mutableState.value.prompt,
                revealed = false,
                typedAnswer = "",
                isAnswerCorrect = null,
                answerMatch = null,
                answerFeedback = null,
                autoRatedAgain = false,
                sessionReviewed = (mutableState.value.sessionReviewed - delta.reviewed).coerceAtLeast(0),
                sessionCorrect = (mutableState.value.sessionCorrect - delta.correct).coerceAtLeast(0)
            )
        }
    }

    /** Retire the current card permanently (bad auto-generated content). */
    fun suspendCurrentCard() {
        val prompt = mutableState.value.prompt ?: return
        if (mutableState.value.ratingInProgress) return
        viewModelScope.launch {
            runCatching { repository.suspendCard(prompt.card) }
                .onSuccess { loadSession(status = "Card suspended. It is out of all review queues.") }
                .onFailure { mutableState.value = mutableState.value.copy(statusMessage = it.message ?: "Could not suspend card") }
        }
    }

    fun markCurrentWordKnown() {
        val prompt = mutableState.value.prompt ?: return
        if (mutableState.value.ratingInProgress) return
        viewModelScope.launch {
            runCatching { repository.markWordKnown(prompt.card.noteId) }
                .onSuccess { loadSession(status = "Marked known. Vocab practice for this word is retired.") }
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
            val delta = if (sessionCounterDeltas.isNotEmpty()) sessionCounterDeltas.removeLast() else SessionCounterDelta(1, 0)
            mutableState.value = mutableState.value.copy(
                autoRatedAgain = false,
                revealed = true,
                isAnswerCorrect = true,
                answerMatch = AnswerMatch.CLOSE,
                answerFeedback = "Auto-Again undone for this slip. Grade the recall you actually had; use Again if the miss was real.",
                ratingInProgress = false,
                // The auto-AGAIN already counted this card; the upcoming rate() will
                // count it again, so roll the auto-count back to avoid double-counting.
                sessionReviewed = (mutableState.value.sessionReviewed - delta.reviewed).coerceAtLeast(0),
                sessionCorrect = (mutableState.value.sessionCorrect - delta.correct).coerceAtLeast(0)
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
            val recommendation = mutableState.value.allReaderTexts.firstOrNull { it.text.id == id }
                ?: repository.readerTexts().firstOrNull { it.text.id == id }
                ?: return@launch
            val progress = settings.readerProgress(id)
            mutableState.value = mutableState.value.copy(
                selectedReaderTextId = id,
                readerTokens = repository.readerTokens(recommendation.text),
                selectedToken = null,
                lookupResult = null,
                readerProgressIndex = progress,
                readerProgressByText = mutableState.value.readerProgressByText + (id to progress)
            )
        }
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
        status: String? = mutableState.value.statusMessage
    ) {
        val plan = repository.sessionPlan()
        // Personalize FSRS intervals from the learner's own retention vs target.
        recalibrateScheduling(plan.dashboardStats)
        val step = keepStep
        val allReaders = repository.readerTexts()
        val current = mutableState.value
        val readerRecommendation = recommendNextReader(allReaders)
        val readerProgressByText = allReaders.associate { it.text.id to settings.readerProgress(it.text.id) }
        val selectedReader = current.selectedReaderTextId?.let { id ->
            allReaders.firstOrNull { it.text.id == id }
        } ?: readerRecommendation
        // Detect achievements unlocked since last seen, for the celebratory toast.
        val unlockedIds = plan.gamification.achievements.filter { it.unlocked }.map { it.id }.toSet()
        val freshIds = settings.newlyUnlocked(unlockedIds)
        val freshAchievements = plan.gamification.achievements.filter { it.id in freshIds }
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
            readerProgressIndex = current.selectedReaderTextId?.let { readerProgressByText[it] } ?: -1,
            newlyUnlocked = if (freshAchievements.isNotEmpty()) freshAchievements else current.newlyUnlocked,
            sessionReviewed = current.sessionReviewed,
            sessionCorrect = current.sessionCorrect,
            leeches = current.leeches
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
        val target = settings.desiredRetention.coerceIn(0.80, 0.97)
        // Interval scales ~ ln(retention); to move observed→target, scale by their ratio.
        val modifier = (ln(target) / ln(clampedObserved)).coerceIn(0.5, 2.0)
        settings.intervalModifier = modifier
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
        sessionCounterDeltas.clear()
        mutableState.value = mutableState.value.copy(sessionReviewed = 0, sessionCorrect = 0)
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
    fun editLeech(item: LeechItem, translation: String?, exampleSentence: String?, exampleTranslation: String?) {
        viewModelScope.launch {
            runCatching {
                repository.updateNoteContent(item.card.noteId, translation, exampleSentence, exampleTranslation)
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
    fun editCurrentCard(translation: String?, exampleSentence: String?, exampleTranslation: String?) {
        val prompt = mutableState.value.prompt ?: return
        viewModelScope.launch {
            runCatching {
                repository.updateNoteContent(prompt.card.noteId, translation, exampleSentence, exampleTranslation)
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
