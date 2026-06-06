package com.sibirskyspeak.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight SharedPreferences-backed user settings. Kept deliberately simple
 * (no DataStore/coroutines) because every value is a small scalar read on demand
 * by the repository, scheduler, reminder worker, and UI. All study-pacing levers
 * that used to be hardcoded constants now live here so the learner can tune them.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("sibirsky_settings", Context.MODE_PRIVATE)

    var dailyGoal: Int
        get() = prefs.getInt(KEY_DAILY_GOAL, DEFAULT_DAILY_GOAL).coerceIn(MIN_DAILY_GOAL, MAX_DAILY_GOAL)
        set(value) = prefs.edit().putInt(KEY_DAILY_GOAL, value.coerceIn(MIN_DAILY_GOAL, MAX_DAILY_GOAL)).apply()

    var sessionSize: Int
        get() = prefs.getInt(KEY_SESSION_SIZE, DEFAULT_SESSION_SIZE).coerceIn(MIN_SESSION_SIZE, MAX_SESSION_SIZE)
        set(value) = prefs.edit().putInt(KEY_SESSION_SIZE, value.coerceIn(MIN_SESSION_SIZE, MAX_SESSION_SIZE)).apply()

    /** Cap on brand-new cards introduced per day. */
    var newCardsPerDay: Int
        get() = prefs.getInt(KEY_NEW_CARDS_PER_DAY, DEFAULT_NEW_CARDS_PER_DAY).coerceIn(MIN_NEW_CARDS_PER_DAY, MAX_NEW_CARDS_PER_DAY)
        set(value) = prefs.edit().putInt(KEY_NEW_CARDS_PER_DAY, value.coerceIn(MIN_NEW_CARDS_PER_DAY, MAX_NEW_CARDS_PER_DAY)).apply()

    /** FSRS desired retention (probability of recall at review time). */
    var desiredRetention: Double
        get() = prefs.getFloat(KEY_RETENTION, DEFAULT_RETENTION.toFloat()).toDouble().coerceIn(MIN_RETENTION, MAX_RETENTION)
        set(value) = prefs.edit().putFloat(KEY_RETENTION, value.coerceIn(MIN_RETENTION, MAX_RETENTION).toFloat()).apply()

    var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()

    /** Hour of day (0-23, local) for the daily nudge. */
    var reminderHour: Int
        get() = prefs.getInt(KEY_REMINDER_HOUR, DEFAULT_REMINDER_HOUR).coerceIn(0, 23)
        set(value) = prefs.edit().putInt(KEY_REMINDER_HOUR, value.coerceIn(0, 23)).apply()

    /** Reader text size multiplier. */
    var readerFontScale: Float
        get() = prefs.getFloat(KEY_READER_FONT_SCALE, 1.0f).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        set(value) = prefs.edit().putFloat(KEY_READER_FONT_SCALE, value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)).apply()

    /** Snapshot of achievement ids already seen-unlocked, for first-unlock notifications. */
    var unlockedAchievementIds: Set<String>
        get() = prefs.getStringSet(KEY_UNLOCKED_ACHIEVEMENTS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_UNLOCKED_ACHIEVEMENTS, value).apply()

    /**
     * Diff the current set of unlocked achievement ids against what we've already
     * shown the user, returning the freshly unlocked ones and persisting the new
     * baseline. The first call ever "seeds" silently (returns empty) so existing
     * progress doesn't trigger a flood of notifications on upgrade/first launch.
     */
    fun newlyUnlocked(currentUnlocked: Set<String>): Set<String> {
        val seeded = prefs.getBoolean(KEY_ACH_SEEDED, false)
        val newly = if (!seeded) emptySet() else currentUnlocked - unlockedAchievementIds
        prefs.edit()
            .putStringSet(KEY_UNLOCKED_ACHIEVEMENTS, currentUnlocked)
            .putBoolean(KEY_ACH_SEEDED, true)
            .apply()
        return newly
    }

    /** Persisted reading position (token index) per reader text. */
    fun readerProgress(textId: Long): Int = prefs.getInt(readerProgressKey(textId), 0)

    fun setReaderProgress(textId: Long, tokenIndex: Int) {
        prefs.edit().putInt(readerProgressKey(textId), tokenIndex.coerceAtLeast(0)).apply()
    }

    private fun readerProgressKey(textId: Long) = "reader_progress_$textId"

    companion object {
        private const val KEY_DAILY_GOAL = "daily_goal"
        private const val KEY_SESSION_SIZE = "session_size"
        private const val KEY_NEW_CARDS_PER_DAY = "new_cards_per_day"
        private const val KEY_RETENTION = "desired_retention"
        private const val KEY_REMINDER_ENABLED = "reminder_enabled"
        private const val KEY_REMINDER_HOUR = "reminder_hour"
        private const val KEY_READER_FONT_SCALE = "reader_font_scale"
        private const val KEY_UNLOCKED_ACHIEVEMENTS = "unlocked_achievements"
        private const val KEY_ACH_SEEDED = "achievements_seeded"

        const val DEFAULT_DAILY_GOAL = 20
        const val DEFAULT_SESSION_SIZE = 25
        const val DEFAULT_NEW_CARDS_PER_DAY = 15
        const val DEFAULT_RETENTION = 0.9
        const val DEFAULT_REMINDER_HOUR = 19

        const val MIN_DAILY_GOAL = 5
        const val MAX_DAILY_GOAL = 200
        const val MIN_SESSION_SIZE = 5
        const val MAX_SESSION_SIZE = 100
        const val MIN_NEW_CARDS_PER_DAY = 0
        const val MAX_NEW_CARDS_PER_DAY = 80
        const val MIN_RETENTION = 0.80
        const val MAX_RETENTION = 0.97
        const val MIN_FONT_SCALE = 0.8f
        const val MAX_FONT_SCALE = 1.8f
    }
}
