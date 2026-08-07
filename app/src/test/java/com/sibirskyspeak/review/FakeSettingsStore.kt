package com.sibirskyspeak.review

import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.scheduler.FsrsScheduler

/** In-memory SettingsStore for tests — no Android Context/SharedPreferences needed. */
internal class FakeSettingsStore : SettingsStore {
    override var dailyGoal: Int = SettingsStore.DEFAULT_DAILY_GOAL
    override var sessionSize: Int = SettingsStore.DEFAULT_SESSION_SIZE
    override var newCardsPerDay: Int = SettingsStore.DEFAULT_NEW_CARDS_PER_DAY
    override var desiredRetention: Double = SettingsStore.DEFAULT_RETENTION
    override var intervalModifier: Double = 1.0
    override var fsrsWeights: DoubleArray = FsrsScheduler.DEFAULT_WEIGHTS.copyOf()
    override var lastWeightFitDay: Long = Long.MIN_VALUE
    override var reminderEnabled: Boolean = true
    override var reminderHour: Int = SettingsStore.DEFAULT_REMINDER_HOUR
    override var readerFontScale: Float = 1.0f
    override var lastBackupAt: Long = 0L
    override var backupDataVersion: Int = 0
    override var readerFormIndexVersion: Int = 0
    override var backupTreeUri: String = ""
    override var automaticPublicBackupEnabled: Boolean = true
    override var onlineGlossLookupEnabled: Boolean = true
    override val lastBackupSizeBytes: Long = 0L
    override val lastBackupValidatedAt: Long = 0L
    override val lastDurableBackupAt: Long = 0L
    override var restDayCredits: Int = 0
    override var lastRestCreditAwardDay: Long = Long.MIN_VALUE
    override var lastInsuredGapDay: Long = Long.MIN_VALUE
    override var planSkeletonCardIds: String = ""
    override var lastAdaptiveLoadDay: Long = Long.MIN_VALUE
    override var adaptiveResetAt: Long = 0L
    override var adaptiveBoostDay: Long = Long.MIN_VALUE
    override var lastFluencyForecastDay: Long = Long.MIN_VALUE
    override var preferredDomain: String = ""
    override var adaptiveEnabled: Boolean = true
    override var goalTargetLevel: String = ""
    override var goalTargetDateEpochDay: Long = Long.MIN_VALUE
    override var goalCreatedAtEpochDay: Long = Long.MIN_VALUE
    override var goalStatus: String = ""
    override var goalLastWeeklyCheckDay: Long = Long.MIN_VALUE
    override var goalLastVelocityWordsKnown: Int = 0
    override var lastStablePaceWordsPerDay: Double = 0.0
    override var sessionSnapshotJson: String = ""
    override var episodeSnapshotJson: String = ""
    override var pendingReaderEpisodeTextId: Long = -1L
    override var onboardingCompleted: Boolean = false
    override var launchMaintenanceToken: String = ""
    override var lastMicroReadingAttemptDay: Long = Long.MIN_VALUE
    override val learningExperimentVariant: String = "A"
    override var unlockedAchievementIds: Set<String> = emptySet()

    private var achievementsSeeded = false
    private val readerProgress = mutableMapOf<Long, Int>()

    override fun newlyUnlocked(currentUnlocked: Set<String>): Set<String> {
        val newly = if (!achievementsSeeded) emptySet() else currentUnlocked - unlockedAchievementIds
        unlockedAchievementIds = currentUnlocked
        achievementsSeeded = true
        return newly
    }

    override fun readerProgress(textId: Long): Int = readerProgress[textId] ?: -1

    override fun setReaderProgress(textId: Long, tokenIndex: Int) {
        readerProgress[textId] = tokenIndex.coerceAtLeast(-1)
    }
}
