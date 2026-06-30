package com.sibirskyspeak.data

import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.learning.Doctrine
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
    val authenticReady: Boolean
)

enum class ReaderStatus {
    TOO_HARD,
    PRODUCTIVE,
    EASY
}

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
    val intervalModifier: Double = 1.0
)

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
    val matchHistory: List<MatchHistory> = emptyList()
)

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
    val vocabularyMastered: Int,
    val vocabularyTotal: Int,
    val grammarMastered: Int,
    val grammarTotal: Int,
    val unlocked: Boolean
) {
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
    val dueToday: Int
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
    val reviewedToday: Int,
    val dailyGoal: Int,
    val activeDays: Int,
    val last7Days: List<Boolean>,
    val achievements: List<Achievement>
) {
    val goalReached: Boolean get() = dailyGoal > 0 && reviewedToday >= dailyGoal

    companion object {
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
    val sessionSize: Int = 25,
    // Cap on brand-new cards introduced per day. Throttling new material is the
    // single biggest lever against overload/burnout in spaced repetition — it
    // keeps the future review load (and the daily session) sustainable.
    val newCardsPerDay: Int = 15,
    val desiredRetention: Double = 0.90,
    val doctrine: Doctrine = Doctrine.BALANCED
)
