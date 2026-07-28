package com.sibirskyspeak.data

import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.learning.Pace
import com.sibirskyspeak.learning.SessionBlueprint

// Public data models for the learning domain (session plans, dashboard stats,
// gamification, reader recommendations, etc.). Extracted from LearningRepository
// to keep that file focused on behaviour rather than the shapes it returns.
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
    val triageMode: Boolean,
    val overdueBacklog: Boolean = false
)

data class ReaderRecommendation(
    val text: ReaderText,
    val coverage: Double,
    val knownTokens: Int,
    val totalTokens: Int,
    val status: ReaderStatus,
    val authenticReady: Boolean,
    // Count of distinct text lemmas with a card due within 48h (P5.2): the day's
    // chapter deliberately smuggles in the words FSRS wants reviewed.
    val dueOverlap: Int = 0,
    /** Heuristic difficulty signals beyond simple vocabulary coverage. */
    val syntaxComplexity: Double = 0.0,
    val morphologyNovelty: Double = 0.0,
    val idiomDensity: Double = 0.0,
    val difficultyScore: Double = 0.0
) {
    val difficultyLabel: String
        get() = when {
            difficultyScore < 0.30 -> "gentle"
            difficultyScore < 0.55 -> "stretch"
            else -> "challenging"
        }
}

data class ContentProvenance(
    val id: String,
    val attribution: String,
    val license: String
)

enum class ReaderStatus {
    TOO_HARD,
    PRODUCTIVE,
    EASY
}

/** One question in the monthly checkpoint (P6.4). Never linked to a Card — this
 * is deliberately outside the FSRS graph so answering it can't change scheduling. */
data class CheckpointItem(
    val itemKey: String,
    val kind: String,
    val prompt: String,
    val expectedAnswer: String,
    val predictedP: Double?
)

data class CheckpointSession(val items: List<CheckpointItem>, val generatedAt: Long)

/**
 * One question in a unit exit ticket (Phase G6 / P6.5): a short mixed proof
 * assembled from existing card/task types over a single unit's own vocabulary,
 * never a new card type. [kind] mirrors the units.json exitTicket facets —
 * "recognition" | "production" | "listening" | "reading" | "dialogue" |
 * "transfer" — and drives grading in LearningRepository.gradeExitTicketAnswer.
 *
 * Every runtime capstone item is tap-only: [choices] contains the correct
 * [expectedAnswer] exactly once plus calibrated distractors. No capstone may
 * fall back to a text field.
 */
data class ExitTicketItem(
    val kind: String,
    val noteId: Long?,
    val prompt: String,
    val expectedAnswer: String,
    val choices: List<String> = emptyList(),
    /** Text spoken by TTS while remaining hidden from the learner. */
    val audioPrompt: String? = null,
    val acceptableAnswers: List<String> = emptyList(),
    val targetLemmas: List<String> = emptyList(),
    val evidenceNoteIds: List<Long> = emptyList()
)

data class ExitTicketGrade(val correct: Boolean, val feedback: String)

/** An assigned unit capstone embedded in the normal study route. */
data class ExitTicketSession(
    val unit: Int,
    val band: String = "A1",
    val canDoLabel: String?,
    val items: List<ExitTicketItem>
)

/** Phase G10: one phonology.json MINIMAL_PAIR item usable as a real on-device
 * card today (requiresAudioPack=false only — see LearningRepository.
 * phonologyMinimalPairs()). */
data class PhonologyMinimalPair(
    val id: String,
    val formA: String,
    val formB: String
)

data class ReaderToken(
    val surface: String,
    val normalized: String,
    val known: Boolean,
    val status: WordStatus,
    // Punctuation glued to the front/back of this word in the source text (e.g. the
    // opening «, the trailing comma or period), so the reader can render real
    // punctuation around the clickable word instead of dropping it.
    val leading: String = "",
    val trailing: String = "",
    val lemma: String?,
    val parse: String?,
    val aktionsart: String?,
    val stressForm: String?,
    val translation: String?,
    val exampleSentence: String?,
    val exampleTranslation: String? = null,
    val exampleSentence2: String? = null,
    val exampleTranslation2: String? = null,
    val exampleSentence3: String? = null,
    val exampleTranslation3: String? = null
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
    val importQualityReport: ImportQualityReport,
    // Retention instruments: true retention on mature cards (fraction of mature-card
    // reviews not lapsed), how many mature reviews that's based on, parked leeches,
    // and the count of cards coming due over each of the next 7 days.
    val matureRetention: Double? = null,
    val matureReviewSample: Int = 0,
    val leechCount: Int = 0,
    val dueForecast: List<Int> = emptyList(),
    // Current data-driven FSRS interval multiplier (1.0 = neutral), surfaced for display.
    val intervalModifier: Double = 1.0,
    val goalProgress: GoalProgress? = null
)

data class GoalProgress(val textId: Long, val textTitle: String, val coveragePct: Int, val unknownLemmaCount: Int, val deltaThisWeek: Int = 0)

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

data class ImportPreview(
    val valid: Boolean,
    val notes: Int = 0,
    val cards: Int = 0,
    val reviews: Int = 0,
    val readerTexts: Int = 0,
    val historyRows: Int = 0,
    val restoresSettings: Boolean = false,
    val errors: List<String> = emptyList()
)

data class SessionPlan(
    val ruleSummary: String,
    val reviewQueue: List<ReviewPrompt>,
    val blockedGrammar: List<ReviewPrompt>,
    val interleavedGrammar: List<ReviewPrompt>,
    val readerRecommendation: ReaderRecommendation?,
    val dashboardStats: DashboardStats,
    val dailyPlan: DailyPlan,
    val gamification: GamificationStats = GamificationStats.EMPTY,
    val completion: DailyCompletion = DailyCompletion(),
    val unitMastery: List<UnitMastery> = emptyList(),
    val readingReason: String? = null,
    val problemCards: List<ProblemCardSummary> = emptyList(),
    val consolidationLemmas: Set<String> = emptySet(),
    val readingAssignment: ReadingAssignment? = null,
    val blueprint: SessionBlueprint? = null,
    val pace: Pace? = null,
    val confusablePairs: Set<Pair<Long, Long>> = emptySet(),
    val skillRatings: List<SkillRating> = emptyList(),
    val rivalState: RivalState? = null,
    val matchHistory: List<MatchHistory> = emptyList(),
    val levelConstraint: String? = null,
    val adaptiveTrust: Double = 0.35,
    val adaptiveReason: String = "Cold-start settings prior"
)

/**
 * The route is complete when the scheduler has no required work left. This is
 * deliberately separate from the learner's optional daily review target: a
 * route can finish before that target is reached when adaptive pacing stops at
 * the safe scheduled boundary.
 */
val SessionPlan.routeComplete: Boolean
    get() = completion.status == DailyLearningStatus.SCHEDULED_COMPLETE ||
        completion.status == DailyLearningStatus.NEW_LIMIT_REACHED

/** Progress for route UI. Before completion, the daily target is the best
 * available progress denominator; once the scheduler says the route is done,
 * the route ring must report 100% even if the optional target is higher. */
val SessionPlan.routeProgress: Float
    get() = if (routeComplete) 1f else {
        val target = gamification.dailyGoal
        if (target <= 0) 1f else (gamification.learningActionsToday.toFloat() / target).coerceIn(0f, 1f)
    }

data class ProblemCardSummary(
    val cardId: Long,
    val russian: String,
    val conciseMeaning: String,
    val cardType: CardType,
    val reviews: Int,
    val lapses: Int,
    val difficulty: Double,
    val recommendation: String
)

data class UnitMastery(
    val unit: Int,
    val band: String = "A1",
    val vocabularyMastered: Int,
    val vocabularyTotal: Int,
    val grammarMastered: Int,
    val grammarTotal: Int,
    val unlocked: Boolean,
    /** Learner-facing outcome from the curriculum manifest, when available. */
    val canDoLabel: String? = null
) {
    val stableKey: String get() = "$band:$unit"
    val progress: Double get() =
        if (vocabularyTotal + grammarTotal == 0) 0.0
        else (vocabularyMastered + grammarMastered).toDouble() / (vocabularyTotal + grammarTotal)
}

enum class DailyLearningStatus { WORK_REMAINING, BACKLOG_REMAINING, NEW_LIMIT_REACHED, SCHEDULED_COMPLETE }

data class DailyCompletion(
    val status: DailyLearningStatus = DailyLearningStatus.SCHEDULED_COMPLETE,
    val message: String = "Scheduled work complete.",
    val optionalReinforcementAvailable: Boolean = false
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
    val dueToday: Int,
    val estimatedMinutes: Int = 0
)

data class GamificationStats(
    val knownWords: Int,
    val totalReviews: Int,
    val xp: Int,
    val level: Int,
    val xpIntoLevel: Int,
    val xpForLevel: Int,
    val currentStreak: Int,
    val inputStreak: Int = 0,
    val longestStreak: Int,
    /** Card reviews completed today, kept separate from reading activity. */
    val reviewedToday: Int,
    /** Completed scheduled reading activities today. */
    val readingToday: Int = 0,
    /** The unit used by the daily goal: cards plus completed reading activities. */
    val learningActionsToday: Int = reviewedToday + readingToday,
    val dailyGoal: Int,
    val activeDays: Int,
    val last7Days: List<Boolean>,
    // Per-day review counts for the trailing HEATMAP_DAYS days (oldest first, today
    // last) — an Anki/GitHub-style activity heatmap needs intensity, not just the
    // active/inactive boolean last7Days gives. Fixed length (zero-filled for days
    // with no history yet) so the UI can lay it out as a stable grid.
    val activityHeatmap: List<Int> = emptyList(),
    val achievements: List<Achievement>,
    val restDayCredits: Int = 0,
    // The specific day-bucket (see LearningRepository.startOfLocalDay) that streak
    // insurance bridged to keep currentStreak alive, or null if no gap was insured.
    // LearningRepository is a pure query over config() snapshots and can't write
    // settings itself, so the actual credit deduction happens in ReviewViewModel
    // (which owns the mutable SettingsStore) by comparing this against the last
    // day it already charged for — see ReviewViewModel.loadSession.
    val insuredGapDay: Long? = null
) {
    val goalReached: Boolean get() = dailyGoal > 0 && learningActionsToday >= dailyGoal

    companion object {
        // 14 weeks: enough to read as a real GitHub/Anki-style heatmap while still
        // fitting a compact mobile card without horizontal scrolling.
        const val HEATMAP_WEEKS = 14
        const val HEATMAP_DAYS = HEATMAP_WEEKS * 7
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
    // Pagination hint only (how many prompts to materialize into the visible
    // queue at once) — no longer a hard per-session ceiling. The queue refills
    // automatically as it's consumed; real-time stop/recover decisions live in
    // SessionMpcController, not here.
    val sessionSize: Int = 25,
    // Cold-start prior blended with the continuously-derived budget (see
    // PaceController.adoptForSessionSettings) — not an independent hard daily
    // ceiling; PaceController/BlueprintBuilder regulate new-card introduction
    // live from capacity/accuracy/debt/fatigue.
    val newCardsPerDay: Int = 15,
    val desiredRetention: Double = 0.90,
    val restDayCredits: Int = 0,
    // Phase G6 (domain overlays, scaled down — see LearningRepository.
    // domainBiasFor): SettingsStore.preferredDomain, a build-time validated
    // domain tag (e.g. "business", "science") matched against the "target:"/
    // "graded:" prefix on ReaderText.source. Empty means no preference.
    val preferredDomain: String = "",
    val adaptiveEnabled: Boolean = true
)
