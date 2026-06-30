package com.sibirskyspeak.data

import android.content.Context
import android.content.SharedPreferences
import com.sibirskyspeak.learning.Doctrine
import com.sibirskyspeak.scheduler.FsrsScheduler

/**
 * User-tunable study pacing levers and small persisted UI state. An interface
 * (implemented by [PrefsSettingsStore] in production) so tests — notably
 * ReviewViewModelTest — can supply an in-memory fake without an Android Context
 * or Robolectric.
 */
interface SettingsStore {
    var dailyGoal: Int
    var sessionSize: Int
    var newCardsPerDay: Int
    var desiredRetention: Double
    var doctrine: Doctrine
    var intervalModifier: Double
    /** The active FSRS weight vector (21 params). Defaults to stock FSRS-6 until the
     * on-device fit personalizes the high-leverage subset (init stability + decay). */
    var fsrsWeights: DoubleArray
    /** Local epoch-day on which the FSRS weight fit last ran (throttles it to daily). */
    var lastWeightFitDay: Long
    var reminderEnabled: Boolean
    var reminderHour: Int
    var readerFontScale: Float
    var lastBackupAt: Long
    var lastAdaptiveLoadDay: Long
    val learningExperimentVariant: String
    var unlockedAchievementIds: Set<String>
    fun newlyUnlocked(currentUnlocked: Set<String>): Set<String>
    fun readerProgress(textId: Long): Int
    fun setReaderProgress(textId: Long, tokenIndex: Int)

    companion object {
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
        const val MIN_INTERVAL_MODIFIER = 0.5
        const val MAX_INTERVAL_MODIFIER = 2.0
    }
}

/**
 * Lightweight SharedPreferences-backed implementation. Kept deliberately simple
 * (no DataStore/coroutines) because every value is a small scalar read on demand
 * by the repository, scheduler, reminder worker, and UI.
 */
class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("sibirsky_settings", Context.MODE_PRIVATE)

    override var dailyGoal: Int
        get() = prefs.getInt(KEY_DAILY_GOAL, SettingsStore.DEFAULT_DAILY_GOAL).coerceIn(SettingsStore.MIN_DAILY_GOAL, SettingsStore.MAX_DAILY_GOAL)
        set(value) = prefs.edit().putInt(KEY_DAILY_GOAL, value.coerceIn(SettingsStore.MIN_DAILY_GOAL, SettingsStore.MAX_DAILY_GOAL)).apply()

    override var sessionSize: Int
        get() = prefs.getInt(KEY_SESSION_SIZE, SettingsStore.DEFAULT_SESSION_SIZE).coerceIn(SettingsStore.MIN_SESSION_SIZE, SettingsStore.MAX_SESSION_SIZE)
        set(value) = prefs.edit().putInt(KEY_SESSION_SIZE, value.coerceIn(SettingsStore.MIN_SESSION_SIZE, SettingsStore.MAX_SESSION_SIZE)).apply()

    /** Cap on brand-new cards introduced per day. */
    override var newCardsPerDay: Int
        get() = prefs.getInt(KEY_NEW_CARDS_PER_DAY, SettingsStore.DEFAULT_NEW_CARDS_PER_DAY).coerceIn(SettingsStore.MIN_NEW_CARDS_PER_DAY, SettingsStore.MAX_NEW_CARDS_PER_DAY)
        set(value) = prefs.edit().putInt(KEY_NEW_CARDS_PER_DAY, value.coerceIn(SettingsStore.MIN_NEW_CARDS_PER_DAY, SettingsStore.MAX_NEW_CARDS_PER_DAY)).apply()

    /** FSRS desired retention (probability of recall at review time). */
    override var desiredRetention: Double
        get() = prefs.getFloat(KEY_RETENTION, SettingsStore.DEFAULT_RETENTION.toFloat()).toDouble().coerceIn(SettingsStore.MIN_RETENTION, SettingsStore.MAX_RETENTION)
        set(value) = prefs.edit().putFloat(KEY_RETENTION, value.coerceIn(SettingsStore.MIN_RETENTION, SettingsStore.MAX_RETENTION).toFloat()).apply()

    override var doctrine: Doctrine
        get() = prefs.getString(KEY_DOCTRINE, Doctrine.BALANCED.name)
            ?.let { runCatching { Doctrine.valueOf(it) }.getOrNull() }
            ?: Doctrine.BALANCED
        set(value) = prefs.edit().putString(KEY_DOCTRINE, value.name).apply()

    /**
     * Data-driven FSRS interval multiplier, learned from the user's own mature-card
     * retention vs their target (a lightweight personalization in place of a full
     * weight optimizer). 1.0 = neutral; >1 lengthens intervals when you retain better
     * than target, <1 shortens them when you forget more. Bounded so it can't run away.
     */
    override var intervalModifier: Double
        get() = prefs.getFloat(KEY_INTERVAL_MODIFIER, 1.0f).toDouble().coerceIn(SettingsStore.MIN_INTERVAL_MODIFIER, SettingsStore.MAX_INTERVAL_MODIFIER)
        set(value) = prefs.edit().putFloat(KEY_INTERVAL_MODIFIER, value.coerceIn(SettingsStore.MIN_INTERVAL_MODIFIER, SettingsStore.MAX_INTERVAL_MODIFIER).toFloat()).apply()

    // Persisted as a CSV string and parse-cached: read on every scheduler call, so
    // re-splitting on each access would be wasteful. A stored vector of the wrong
    // length (corrupt/old) falls back to the stock defaults.
    @Volatile private var cachedWeights: DoubleArray? = null
    override var fsrsWeights: DoubleArray
        get() = cachedWeights ?: run {
            val stored = prefs.getString(KEY_FSRS_WEIGHTS, null)
                ?.split(',')
                ?.mapNotNull { it.trim().toDoubleOrNull() }
                ?.takeIf { it.size == FsrsScheduler.DEFAULT_WEIGHTS.size }
                ?.toDoubleArray()
            (stored ?: FsrsScheduler.DEFAULT_WEIGHTS.copyOf()).also { cachedWeights = it }
        }
        set(value) {
            val clean = value.takeIf { it.size == FsrsScheduler.DEFAULT_WEIGHTS.size }
                ?: FsrsScheduler.DEFAULT_WEIGHTS.copyOf()
            cachedWeights = clean
            prefs.edit().putString(KEY_FSRS_WEIGHTS, clean.joinToString(",")).apply()
        }

    override var lastWeightFitDay: Long
        get() = prefs.getLong(KEY_LAST_WEIGHT_FIT_DAY, Long.MIN_VALUE)
        set(value) = prefs.edit().putLong(KEY_LAST_WEIGHT_FIT_DAY, value).apply()

    override var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()

    /** Hour of day (0-23, local) for the daily nudge. */
    override var reminderHour: Int
        get() = prefs.getInt(KEY_REMINDER_HOUR, SettingsStore.DEFAULT_REMINDER_HOUR).coerceIn(0, 23)
        set(value) = prefs.edit().putInt(KEY_REMINDER_HOUR, value.coerceIn(0, 23)).apply()

    /** Reader text size multiplier. */
    override var readerFontScale: Float
        get() = prefs.getFloat(KEY_READER_FONT_SCALE, 1.0f).coerceIn(SettingsStore.MIN_FONT_SCALE, SettingsStore.MAX_FONT_SCALE)
        set(value) = prefs.edit().putFloat(KEY_READER_FONT_SCALE, value.coerceIn(SettingsStore.MIN_FONT_SCALE, SettingsStore.MAX_FONT_SCALE)).apply()

    /** Epoch millis of the last successful full-state backup (0 if never). */
    override var lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKUP_AT, value).apply()

    /** Local epoch-day on which automatic workload tuning last ran. */
    override var lastAdaptiveLoadDay: Long
        get() = prefs.getLong(KEY_LAST_ADAPTIVE_LOAD_DAY, Long.MIN_VALUE)
        set(value) = prefs.edit().putLong(KEY_LAST_ADAPTIVE_LOAD_DAY, value).apply()

    /** Stable, installation-local learning experiment. Never changes mid-course. */
    override val learningExperimentVariant: String
        get() {
            prefs.getString(KEY_LEARNING_EXPERIMENT, null)?.let { return it }
            val variant = if (java.util.UUID.randomUUID().leastSignificantBits and 1L == 0L) "A" else "B"
            prefs.edit().putString(KEY_LEARNING_EXPERIMENT, variant).commit()
            return variant
        }

    /** Snapshot of achievement ids already seen-unlocked, for first-unlock notifications. */
    override var unlockedAchievementIds: Set<String>
        get() = prefs.getStringSet(KEY_UNLOCKED_ACHIEVEMENTS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_UNLOCKED_ACHIEVEMENTS, value).apply()

    /**
     * Diff the current set of unlocked achievement ids against what we've already
     * shown the user, returning the freshly unlocked ones and persisting the new
     * baseline. The first call ever "seeds" silently (returns empty) so existing
     * progress doesn't trigger a flood of notifications on upgrade/first launch.
     */
    override fun newlyUnlocked(currentUnlocked: Set<String>): Set<String> {
        val seeded = prefs.getBoolean(KEY_ACH_SEEDED, false)
        val newly = if (!seeded) emptySet() else currentUnlocked - unlockedAchievementIds
        prefs.edit()
            .putStringSet(KEY_UNLOCKED_ACHIEVEMENTS, currentUnlocked)
            .putBoolean(KEY_ACH_SEEDED, true)
            .apply()
        return newly
    }

    /** Persisted furthest reached token index per reader text; -1 means not started. */
    override fun readerProgress(textId: Long): Int = prefs.getInt(readerProgressKey(textId), -1)

    override fun setReaderProgress(textId: Long, tokenIndex: Int) {
        prefs.edit().putInt(readerProgressKey(textId), tokenIndex.coerceAtLeast(-1)).apply()
    }

    private fun readerProgressKey(textId: Long) = "reader_progress_$textId"

    companion object {
        private const val KEY_DAILY_GOAL = "daily_goal"
        private const val KEY_SESSION_SIZE = "session_size"
        private const val KEY_NEW_CARDS_PER_DAY = "new_cards_per_day"
        private const val KEY_RETENTION = "desired_retention"
        private const val KEY_DOCTRINE = "doctrine"
        private const val KEY_REMINDER_ENABLED = "reminder_enabled"
        private const val KEY_REMINDER_HOUR = "reminder_hour"
        private const val KEY_READER_FONT_SCALE = "reader_font_scale"
        private const val KEY_UNLOCKED_ACHIEVEMENTS = "unlocked_achievements"
        private const val KEY_ACH_SEEDED = "achievements_seeded"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_INTERVAL_MODIFIER = "interval_modifier"
        private const val KEY_FSRS_WEIGHTS = "fsrs_weights_v1"
        private const val KEY_LAST_WEIGHT_FIT_DAY = "last_weight_fit_day"
        private const val KEY_LAST_ADAPTIVE_LOAD_DAY = "last_adaptive_load_day"
        private const val KEY_LEARNING_EXPERIMENT = "learning_experiment_v1"
    }
}
