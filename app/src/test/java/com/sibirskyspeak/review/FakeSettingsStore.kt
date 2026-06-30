package com.sibirskyspeak.review

import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.learning.Doctrine
import com.sibirskyspeak.scheduler.FsrsScheduler

/** In-memory SettingsStore for tests — no Android Context/SharedPreferences needed. */
internal class FakeSettingsStore : SettingsStore {
    override var dailyGoal: Int = SettingsStore.DEFAULT_DAILY_GOAL
    override var sessionSize: Int = SettingsStore.DEFAULT_SESSION_SIZE
    override var newCardsPerDay: Int = SettingsStore.DEFAULT_NEW_CARDS_PER_DAY
    override var desiredRetention: Double = SettingsStore.DEFAULT_RETENTION
    override var doctrine: Doctrine = Doctrine.BALANCED
    override var intervalModifier: Double = 1.0
    override var fsrsWeights: DoubleArray = FsrsScheduler.DEFAULT_WEIGHTS.copyOf()
    override var lastWeightFitDay: Long = Long.MIN_VALUE
    override var reminderEnabled: Boolean = true
    override var reminderHour: Int = SettingsStore.DEFAULT_REMINDER_HOUR
    override var readerFontScale: Float = 1.0f
    override var lastBackupAt: Long = 0L
    override var lastAdaptiveLoadDay: Long = Long.MIN_VALUE
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
