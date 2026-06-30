package com.sibirskyspeak.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sibirskyspeak.scheduler.FsrsScheduler

@Database(
    entities = [Note::class, Card::class, ReviewLog::class, ConfusablePair::class, ReaderText::class, ReadingSchedule::class, ReaderEncounter::class, ReadingActivity::class, TelemetryEvent::class, MinedExample::class, ItemDifficulty::class, ConceptMastery::class, OptimizerParameter::class, SkillRating::class, CapacityState::class, WillingnessState::class, RivalState::class, GhostSnapshot::class, MatchHistory::class, PaceLog::class, BanditPending::class, BanditArmState::class],
    version = 18,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun cardDao(): CardDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun confusablePairDao(): ConfusablePairDao
    abstract fun readerTextDao(): ReaderTextDao
    abstract fun readingScheduleDao(): ReadingScheduleDao
    abstract fun readerEncounterDao(): ReaderEncounterDao
    abstract fun readingActivityDao(): ReadingActivityDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun minedExampleDao(): MinedExampleDao
    abstract fun learningModelDao(): LearningModelDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sibirsky_speak.db"
                )
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
                    // Only versions before the first real migration (7) are allowed to
                    // wipe destructively — those predate the JSON backup/restore safety
                    // net, so there's nothing worth preserving. Any version from 7 on
                    // must go through an explicit Migration; a missing one should fail
                    // loudly instead of silently deleting the learner's review history.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6)
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `telemetry_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `sessionId` TEXT,
                        `cardId` INTEGER,
                        `noteId` INTEGER,
                        `cardType` TEXT,
                        `queue` TEXT,
                        `answerMode` TEXT,
                        `rating` TEXT,
                        `answerMatch` TEXT,
                        `responseMs` INTEGER,
                        `wasRevealed` INTEGER NOT NULL,
                        `typedLength` INTEGER NOT NULL,
                        `queueReason` TEXT,
                        `sessionRemaining` INTEGER,
                        `dueCount` INTEGER,
                        `newCardLimit` INTEGER,
                        `metadataJson` TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_telemetry_events_timestamp` ON `telemetry_events` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_telemetry_events_eventType` ON `telemetry_events` (`eventType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_telemetry_events_sessionId` ON `telemetry_events` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_telemetry_events_cardId` ON `telemetry_events` (`cardId`)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `mnemonic` TEXT")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `reading_schedules` (
                        `readerTextId` INTEGER NOT NULL,
                        `due` INTEGER NOT NULL,
                        `intervalDays` INTEGER NOT NULL,
                        `reps` INTEGER NOT NULL,
                        `lapses` INTEGER NOT NULL,
                        `lastCompleted` INTEGER,
                        PRIMARY KEY(`readerTextId`),
                        FOREIGN KEY(`readerTextId`) REFERENCES `reader_texts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_schedules_due` ON `reading_schedules` (`due`)")
                db.execSQL("INSERT OR IGNORE INTO `reading_schedules` (`readerTextId`, `due`, `intervalDays`, `reps`, `lapses`, `lastCompleted`) SELECT `id`, 0, 0, 0, 0, NULL FROM `reader_texts`")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `reader_encounters` (
                        `readerTextId` INTEGER NOT NULL,
                        `noteId` INTEGER NOT NULL,
                        `encounteredAt` INTEGER NOT NULL,
                        PRIMARY KEY(`readerTextId`, `noteId`),
                        FOREIGN KEY(`readerTextId`) REFERENCES `reader_texts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reader_encounters_noteId` ON `reader_encounters` (`noteId`)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `reading_activities` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `readerTextId` INTEGER NOT NULL,
                        `completedAt` INTEGER NOT NULL,
                        `mistakes` INTEGER NOT NULL,
                        `intervalDays` INTEGER NOT NULL,
                        FOREIGN KEY(`readerTextId`) REFERENCES `reader_texts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_activities_readerTextId` ON `reading_activities` (`readerTextId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_activities_completedAt` ON `reading_activities` (`completedAt`)")
                // Recover every retained historical completion for XP/streaks. The
                // old telemetry payload stored the text id inside JSON; assigning
                // legacy rows to a valid text is enough for aggregate history and
                // avoids relying on JSON1 on older Android SQLite builds.
                db.execSQL("""
                    INSERT INTO `reading_activities` (`readerTextId`, `completedAt`, `mistakes`, `intervalDays`)
                    SELECT (SELECT MIN(readerTextId) FROM reading_schedules), timestamp, 0, 1
                    FROM telemetry_events
                    WHERE eventType = 'scheduled_reading_completed'
                      AND EXISTS (SELECT 1 FROM reading_schedules)
                """.trimIndent())
                // Schedules predate the telemetry event on some installs, so retain
                // their last completion too unless the timestamp was recovered above.
                db.execSQL("""
                    INSERT INTO `reading_activities` (`readerTextId`, `completedAt`, `mistakes`, `intervalDays`)
                    SELECT s.readerTextId, s.lastCompleted, 0, s.intervalDays
                    FROM reading_schedules s
                    WHERE s.lastCompleted IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM reading_activities a WHERE a.completedAt = s.lastCompleted
                      )
                """.trimIndent())
            }
        }

        // Records the stability a card carried into each review, so the on-device
        // FSRS weight fit can reconstruct the forgetting curve. Existing rows keep
        // 0.0 (the fitter skips them); new reviews populate it going forward.
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `review_logs` ADD COLUMN `stabilityBefore` REAL NOT NULL DEFAULT 0.0")
            }
        }

        // Repair "already known" vocab cards that earlier bulk-graduation left in a
        // degenerate FSRS state (stability=0, difficulty=0): not a point on any
        // forgetting curve, so they poisoned forecasts and would mis-schedule the
        // moment they returned. Give them the same coherent known state new
        // graduations now write (see FsrsScheduler.markKnown). LESSON cards graduate
        // with stability 0 by design and live in the GRAMMAR queue, so the VOCAB
        // filter leaves them — and every genuinely reviewed card (stability>0) —
        // untouched.
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE cards
                    SET stability = ${FsrsScheduler.KNOWN_STABILITY_DAYS},
                        difficulty = ${FsrsScheduler.KNOWN_DIFFICULTY},
                        scheduledDays = ${FsrsScheduler.KNOWN_STABILITY_DAYS.toInt()},
                        reps = MAX(reps, 1),
                        consecutiveCorrect = MAX(consecutiveCorrect, 1)
                    WHERE state = 'GRADUATED' AND queue = 'VOCAB'
                      AND (stability <= 0.0 OR difficulty <= 0.0)
                    """.trimIndent()
                )
            }
        }

        // Retire the legacy STRESS_MARK card type. It is no longer generated and was
        // removed from ADVANCED_FACETS, so the existing cards would otherwise sit
        // forever — never surfacing, but inflating the deck (~19%) and the in-memory
        // caches. They carry effectively no review history; their review_logs (if any)
        // cascade-delete with them. The enum value and prompt builder stay so older
        // full-state backups containing stress cards remain importable.
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM cards WHERE cardType = 'STRESS_MARK'")
            }
        }

        /** Learner-specific sentence cache plus the three tiny adaptive models. The
         * large immutable corpus remains in ContentDatabase and never enters backups. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `mined_examples` (
                        `noteId` INTEGER NOT NULL,
                        `ru` TEXT NOT NULL,
                        `en` TEXT NOT NULL,
                        `sentenceId` INTEGER NOT NULL,
                        `anchoredGloss` TEXT NOT NULL,
                        `score` REAL NOT NULL,
                        `source` TEXT NOT NULL,
                        `knownAtMine` INTEGER NOT NULL,
                        `targetPos` INTEGER NOT NULL,
                        `unknownCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`noteId`),
                        FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mined_examples_sentenceId` ON `mined_examples` (`sentenceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mined_examples_createdAt` ON `mined_examples` (`createdAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `item_difficulty` (`cardId` INTEGER NOT NULL, `elo` REAL NOT NULL, `observations` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`cardId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `concept_mastery` (`concept` TEXT NOT NULL, `probability` REAL NOT NULL, `observations` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`concept`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `optimizer_parameters` (`key` TEXT NOT NULL, `value` REAL NOT NULL, `observations` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `item_difficulty` ADD COLUMN `sigma` REAL NOT NULL DEFAULT 8.3333")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `skill_rating` (
                        `skill` TEXT NOT NULL,
                        `muGlobalShare` REAL NOT NULL,
                        `mu` REAL NOT NULL,
                        `sigma` REAL NOT NULL,
                        `observations` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`skill`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `capacity_state` (
                        `id` INTEGER NOT NULL,
                        `mu` REAL NOT NULL,
                        `sigma` REAL NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        CHECK(`id` = 0)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `willingness_state` (
                        `id` INTEGER NOT NULL,
                        `habit` REAL NOT NULL,
                        `coeffsJson` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        CHECK(`id` = 0)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `rival_state` (
                        `id` INTEGER NOT NULL,
                        `mu` REAL NOT NULL,
                        `sigma` REAL NOT NULL,
                        `handicap` REAL NOT NULL,
                        `winStreak` INTEGER NOT NULL,
                        `persona` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        CHECK(`id` = 0)
                    )
                """.trimIndent())
                db.execSQL("CREATE TABLE IF NOT EXISTS `ghost_snapshot` (`takenAt` INTEGER NOT NULL, `muGlobal` REAL NOT NULL, `sigma` REAL NOT NULL, PRIMARY KEY(`takenAt`))")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `match_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `at` INTEGER NOT NULL,
                        `opponent` TEXT NOT NULL,
                        `perfYou` REAL NOT NULL,
                        `perfOpp` REAL NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `ratingBefore` REAL NOT NULL,
                        `ratingAfter` REAL NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pace_log` (
                        `at` INTEGER NOT NULL,
                        `T` REAL NOT NULL,
                        `N` INTEGER NOT NULL,
                        `rho` REAL NOT NULL,
                        `debtRatio` REAL NOT NULL,
                        `pReturn` REAL NOT NULL,
                        `doctrine` TEXT NOT NULL,
                        `modeChosen` TEXT NOT NULL,
                        PRIMARY KEY(`at`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `bandit_pending` (
                        `showAt` INTEGER NOT NULL,
                        `itemId` INTEGER NOT NULL,
                        `action` TEXT NOT NULL,
                        `contextJson` TEXT NOT NULL,
                        `p0` REAL NOT NULL,
                        PRIMARY KEY(`showAt`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `bandit_arm_state` (
                        `action` TEXT NOT NULL,
                        `rewardJson` TEXT NOT NULL,
                        `precisionJson` TEXT NOT NULL,
                        `pulls` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`action`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `bandit_arm_state` (
                        `action` TEXT NOT NULL,
                        `rewardJson` TEXT NOT NULL,
                        `precisionJson` TEXT NOT NULL,
                        `pulls` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`action`)
                    )
                """.trimIndent())
            }
        }
    }
}
