package com.sibirskyspeak.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sibirskyspeak.data.DashboardStats
import com.sibirskyspeak.data.DailyPlan
import com.sibirskyspeak.data.LearningRepository
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReaderRecommendation
import com.sibirskyspeak.data.ReaderToken
import com.sibirskyspeak.data.SessionPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val reviewedToday: Int = 0,
    val dailyPlan: DailyPlan? = null,
    val sessionPlan: SessionPlan? = null,
    val readerRecommendation: ReaderRecommendation? = null,
    val allReaderTexts: List<ReaderRecommendation> = emptyList(),
    val readerTokens: List<ReaderToken> = emptyList(),
    val dashboardStats: DashboardStats? = null,
    val lookupResult: String? = null,
    val importText: String = "",
    val exportText: String = "",
    val readerTitle: String = "",
    val readerBody: String = "",
    val statusMessage: String? = null,
    val sessionStep: SessionStep = SessionStep.REVIEWS
)

class ReviewViewModel(
    private val repository: LearningRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.seedIfEmpty()
            loadSession()
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
        val correct = when (prompt.answerMode) {
            AnswerMode.ENGLISH -> mutableState.value.typedAnswer.trim().equals(prompt.expectedAnswer, ignoreCase = true)
            AnswerMode.RUSSIAN_TYPED, AnswerMode.AUDIO_ONLY -> isRussianAnswerCorrect(prompt.expectedAnswer, mutableState.value.typedAnswer)
            AnswerMode.CHOICE -> mutableState.value.typedAnswer == prompt.expectedAnswer
        }
        mutableState.value = mutableState.value.copy(revealed = true, isAnswerCorrect = correct)
    }

    fun rate(rating: Rating) {
        val prompt = mutableState.value.prompt ?: return
        viewModelScope.launch {
            repository.review(prompt.card, rating)
            loadSession()
        }
    }

    fun lookupReaderToken(token: String) {
        val recommendation = mutableState.value.readerRecommendation ?: return
        viewModelScope.launch {
            val note = repository.readerLookup(token, recommendation.text)
            loadSession(
                keepStep = SessionStep.READER,
                status = note?.let { "${it.russian} = ${it.translation}" } ?: "Added $token"
            )
        }
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
        mutableState.value = ReviewUiState(
            prompt = promptForStep(step, plan),
            reviewedToday = repository.reviewedToday(),
            dailyPlan = plan.dailyPlan,
            sessionPlan = plan,
            readerRecommendation = plan.readerRecommendation,
            allReaderTexts = repository.readerTexts(),
            readerTokens = plan.readerRecommendation?.text?.let { repository.readerTokens(it) }.orEmpty(),
            dashboardStats = plan.dashboardStats,
            importText = mutableState.value.importText,
            exportText = mutableState.value.exportText,
            readerTitle = mutableState.value.readerTitle,
            readerBody = mutableState.value.readerBody,
            statusMessage = status,
            sessionStep = step
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
