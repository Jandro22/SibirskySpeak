package com.sibirskyspeak.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Note::class, Card::class, ReviewLog::class, ConfusablePair::class, ReaderText::class, ReadingSchedule::class, ReaderEncounter::class, ReadingActivity::class, TelemetryEvent::class],
    version = 13,
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

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sibirsky_speak.db"
                )
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
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
    }
}
