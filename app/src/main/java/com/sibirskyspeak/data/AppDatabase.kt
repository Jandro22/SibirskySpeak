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
    entities = [Note::class, NoteEvidence::class, NoteForm::class, Card::class, ReviewLog::class, ConfusablePair::class, ReaderText::class, ReaderBookmark::class, ReadingSchedule::class, ReaderEncounter::class, ReadingActivity::class, TelemetryEvent::class, MinedExample::class, ItemDifficulty::class, ConceptMastery::class, OptimizerParameter::class, SkillRating::class, CapacityState::class, WillingnessState::class, RivalState::class, GhostSnapshot::class, MatchHistory::class, PaceLog::class, BanditPending::class, BanditArmState::class, WeeklyReport::class, ConfusionEvent::class, CheckpointResult::class, CurriculumState::class, CurriculumMigrationReport::class, ExitTicketResult::class, KnowledgeComponent::class, CapabilityEvidence::class, CapabilityProgress::class],
    version = 34,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun noteEvidenceDao(): NoteEvidenceDao
    abstract fun noteFormDao(): NoteFormDao
    abstract fun cardDao(): CardDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun confusablePairDao(): ConfusablePairDao
    abstract fun readerTextDao(): ReaderTextDao
    abstract fun readerBookmarkDao(): ReaderBookmarkDao
    abstract fun readingScheduleDao(): ReadingScheduleDao
    abstract fun readerEncounterDao(): ReaderEncounterDao
    abstract fun readingActivityDao(): ReadingActivityDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun minedExampleDao(): MinedExampleDao
    abstract fun learningModelDao(): LearningModelDao
    abstract fun weeklyReportDao(): WeeklyReportDao
    abstract fun confusionEventDao(): ConfusionEventDao
    abstract fun checkpointResultDao(): CheckpointResultDao
    abstract fun curriculumStateDao(): CurriculumStateDao
    abstract fun communicativeLearningDao(): CommunicativeLearningDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sibirsky_speak.db"
                )
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34)
                    // Only versions before the first real migration (7) are allowed to
                    // wipe destructively — those predate the JSON backup/restore safety
                    // net, so there's nothing worth preserving. Any version from 7 on
                    // must go through an explicit Migration; a missing one should fail
                    // loudly instead of silently deleting the learner's review history.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6)
                    .build()
                    .also { instance = it }
            }

        val MIGRATION_7_8 = object : Migration(7, 8) {
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

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `mnemonic` TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
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

        val MIGRATION_10_11 = object : Migration(10, 11) {
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

        val MIGRATION_11_12 = object : Migration(11, 12) {
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
        val MIGRATION_12_13 = object : Migration(12, 13) {
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
        val MIGRATION_13_14 = object : Migration(13, 14) {
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
        val MIGRATION_14_15 = object : Migration(14, 15) {
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

        /** Composite indexes for the hot due/new-card queue predicates. Separate
         * indexes on due and queue forced SQLite to filter tens of thousands of
         * inactive/suspended rows during every session-plan rebuild. */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cards_state_suspended_due` ON `cards` (`state`, `suspended`, `due`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cards_queue_state_suspended_due` ON `cards` (`queue`, `state`, `suspended`, `due`)")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `secondSense` TEXT")
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `secondSenseExample` TEXT")
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `secondSenseExampleTranslation` TEXT")
            }
        }

        /** v20 was already used for second-sense content before the evidence-bus
         * work landed, so the master plan's v19->20 column addition is v20->21 in
         * the real database lineage. */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `review_logs` ADD COLUMN `evidenceStrength` TEXT")
            }
        }
        val MIGRATION_21_22 = object : Migration(21,22) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS `weekly_reports` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `generatedAt` INTEGER NOT NULL, `periodStart` INTEGER NOT NULL, `bodyJson` TEXT NOT NULL)") }
        }
        /** P4.4 L1: chunk notes link back to the vocab note their collocation was
         * mined for (see Note.chunkParentNoteId). */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `chunkParentNoteId` INTEGER")
            }
        }
        /** P4.5: persisted confusion classifications (review/AnswerDiagnosis.kt)
         * feeding contrastive-pair insertion and the weekly letter. */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `confusion_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `expectedKey` TEXT NOT NULL, `producedKey` TEXT NOT NULL, `cardType` TEXT NOT NULL, `at` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_confusion_events_at` ON `confusion_events` (`at`)")
            }
        }
        /** P6.4: the monthly checkpoint — an unbiased, FSRS-state-free assessment
         * used to calibrate whether "known" is real (see LearningRepository's
         * buildCheckpointSession/recordCheckpointResult/checkpointCalibration). */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `checkpoint_results` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `itemKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `predictedP` REAL, `correct` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_checkpoint_results_at` ON `checkpoint_results` (`at`)")
            }
        }
        /** CEFR curriculum rework: `cefrLevel` existed on [Note] but tier-2 (formal/
         *  political domain) and the non-promoted slice of tier-1 (general reading
         *  matrix) were never tagged with one at all — seedIfEmpty() only imports the
         *  bootstrap asset on a truly empty install, so a regenerated asset with the
         *  new tags never reaches an install that already seeded its notes. Backfill
         *  both tiers here with the exact same rank-threshold bands build_bootstrap.py
         *  now uses (tier 2 floored at B2 — see tier2_cefr_level/CEFR_BY_RANK there),
         *  so CardDao's new CEFR gate and CardFactory's case-pacing gate actually see a
         *  level for every note instead of treating untagged content as ungated.
         *
         *  Then fix the *existing* debt directly: tier-2 cards already introduced
         *  before this level even existed (state past NEW) get their due date pushed
         *  out ~75 days, so a backlog built under the old "tier only deprioritizes,
         *  never blocks" rule doesn't dump its entire formal/political-register
         *  backlog into the very next few sessions. They still return — a learner who
         *  is actually ready keeps that vocabulary — just not all at once, right when
         *  the new CEFR gate is trying to let genuinely A1/A2 material catch up.
         *
         *  Finally, un-shown (state = NEW) CASE_FILL cards that violate the new
         *  per-level case pacing (see CardFactory.minCefrOrdinalForCase — plural forms
         *  wait for B1, non-accusative singular cases wait for A2) are suspended: this
         *  mirrors exactly what a fresh install would generate today, for cards nobody
         *  has seen yet, so there's no learning history to preserve. */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE notes SET cefrLevel =
                        CASE
                            WHEN COALESCE(domainFreqRank, 999999) <= 250 THEN 'B2'
                            WHEN COALESCE(domainFreqRank, 999999) <= 600 THEN 'C1'
                            ELSE 'C2'
                        END
                    WHERE tier = 2 AND cefrLevel IS NULL
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE notes SET cefrLevel =
                        CASE
                            WHEN COALESCE(generalFreqRank, 999999999) <= 800 THEN 'A1'
                            WHEN COALESCE(generalFreqRank, 999999999) <= 1500 THEN 'A2'
                            WHEN COALESCE(generalFreqRank, 999999999) <= 2750 THEN 'B1'
                            WHEN COALESCE(generalFreqRank, 999999999) <= 4500 THEN 'B2'
                            WHEN COALESCE(generalFreqRank, 999999999) <= 5500 THEN 'C1'
                            ELSE 'C2'
                        END
                    WHERE tier = 1 AND cefrLevel IS NULL
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE cards SET due = due + (75 * 24 * 60 * 60 * 1000)
                    WHERE suspended = 0 AND state != 'NEW' AND noteId IN (
                        SELECT id FROM notes WHERE tier = 2
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE cards SET suspended = 1
                    WHERE cardType = 'CASE_FILL' AND state = 'NEW' AND noteId IN (
                        SELECT id FROM notes WHERE cefrLevel = 'A1'
                    ) AND (
                        gramNumber = 'PL' OR gramCase != 'ACC'
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE cards SET suspended = 1
                    WHERE cardType = 'CASE_FILL' AND state = 'NEW' AND gramNumber = 'PL'
                        AND noteId IN (SELECT id FROM notes WHERE cefrLevel = 'A2')
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE confusion_events ADD COLUMN category TEXT NOT NULL DEFAULT 'ORTHOGRAPHY'")
                db.execSQL("UPDATE confusion_events SET category = CASE cardType WHEN 'CASE_FILL' THEN 'CASE_ENDING' WHEN 'ADJ_AGREE' THEN 'AGREEMENT' WHEN 'VERB_FORM' THEN 'VERB_CONJUGATION' ELSE 'ORTHOGRAPHY' END")
                db.execSQL("UPDATE notes SET conceptId = 'GEN_CHUNK_POSSESSION' WHERE conceptId = 'GEN' AND unit = 6")
                db.execSQL("UPDATE notes SET conceptId = 'PREP_CHUNK_LOCATION' WHERE conceptId = 'PREP' AND unit = 7")
                db.execSQL("UPDATE notes SET conceptId = 'DAT_CHUNK_EXPERIENCER' WHERE conceptId = 'DAT' AND unit = 8")
                db.execSQL("UPDATE notes SET conceptId = 'INS_CHUNK_WITH' WHERE conceptId = 'INS' AND unit = 9")
                db.execSQL("UPDATE cards SET gramConcept = 'GEN_CHUNK_POSSESSION' WHERE gramConcept = 'GEN' AND noteId IN (SELECT id FROM notes WHERE unit = 6)")
                db.execSQL("UPDATE cards SET gramConcept = 'PREP_CHUNK_LOCATION' WHERE gramConcept = 'PREP' AND noteId IN (SELECT id FROM notes WHERE unit = 7)")
                db.execSQL("UPDATE cards SET gramConcept = 'DAT_CHUNK_EXPERIENCER' WHERE gramConcept = 'DAT' AND noteId IN (SELECT id FROM notes WHERE unit = 8)")
                db.execSQL("UPDATE cards SET gramConcept = 'INS_CHUNK_WITH' WHERE gramConcept = 'INS' AND noteId IN (SELECT id FROM notes WHERE unit = 9)")
            }
        }

        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS curriculum_state (id INTEGER NOT NULL PRIMARY KEY, version TEXT NOT NULL, checksum TEXT NOT NULL, manifestJson TEXT NOT NULL, installedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS curriculum_migration_reports (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, fromVersion TEXT, toVersion TEXT NOT NULL, appeared INTEGER NOT NULL, moved INTEGER NOT NULL, retired INTEGER NOT NULL, detailsJson TEXT NOT NULL, createdAt INTEGER NOT NULL, shown INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS exit_ticket_results (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, unit INTEGER NOT NULL, recognition INTEGER NOT NULL, production INTEGER NOT NULL, listening INTEGER NOT NULL, reading INTEGER NOT NULL, completedAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exit_ticket_results ADD COLUMN band TEXT NOT NULL DEFAULT 'A1'")
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS note_evidence (noteId INTEGER NOT NULL PRIMARY KEY, directRetrievals INTEGER NOT NULL, passiveExposures INTEGER NOT NULL, completedReadings INTEGER NOT NULL, lookups INTEGER NOT NULL, placementPriors INTEGER NOT NULL, lastDirectAt INTEGER, lastPassiveAt INTEGER, lastLookupAt INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS note_forms (surface TEXT NOT NULL, noteId INTEGER NOT NULL, PRIMARY KEY(surface))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_forms_noteId ON note_forms(noteId)")
                // The old total mixed several sources. Preserve it conservatively as
                // legacy direct evidence; all new events are typed from this version on.
                db.execSQL("INSERT INTO note_evidence(noteId,directRetrievals,passiveExposures,completedReadings,lookups,placementPriors,lastDirectAt,lastPassiveAt,lastLookupAt) SELECT id,encounterCount,0,0,0,0,NULL,NULL,NULL FROM notes WHERE encounterCount > 0")
            }
        }

        // A "chunk" note (raw collocation like "дверь открытой", translation="")
        // is meant to carry only its own CardType.CHUNK production card, minted
        // directly by syncMissingChunkCards. But syncPedagogicalFacets swept every
        // tier-0 note — chunk notes included — through CardFactory.cardsFor(),
        // which unconditionally adds RU_TO_MEANING/MEANING_TO_RU/AUDIO_TO_RU/SPEAK
        // cards. Those have no real expected answer (there's no translation to
        // recall), so a miss on one soft-locks the wrong-answer correction UI,
        // which has nothing meaningful to rebuild. CardFactory now refuses to
        // generate anything for a chunk note; this migration removes the
        // already-minted bad cards (and their review logs) from existing installs.
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM review_logs WHERE cardId IN (" +
                        "SELECT c.id FROM cards c JOIN notes n ON c.noteId = n.id " +
                        "WHERE n.partOfSpeech = 'chunk' AND c.cardType != 'CHUNK')"
                )
                db.execSQL(
                    "DELETE FROM cards WHERE id IN (" +
                        "SELECT c.id FROM cards c JOIN notes n ON c.noteId = n.id " +
                        "WHERE n.partOfSpeech = 'chunk' AND c.cardType != 'CHUNK')"
                )
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS reader_bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, readerTextId INTEGER NOT NULL, tokenIndex INTEGER NOT NULL, label TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(readerTextId) REFERENCES reader_texts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reader_bookmarks_readerTextId_tokenIndex ON reader_bookmarks(readerTextId, tokenIndex)")
            }
        }

        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reader_texts ADD COLUMN translationBody TEXT")
            }
        }

        /**
         * Card formats stop being the unit of memory in v34. Keep the legacy tables
         * for lossless backup/rollback, but project their history into knowledge
         * components with deliberately conservative evidence weights. In particular,
         * historical Russian tile/word-bank success is assisted practice, not proof of
         * unsupported production.
         */
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `knowledge_components` (
                        `key` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `capabilityKey` TEXT NOT NULL,
                        `band` TEXT NOT NULL,
                        `unit` INTEGER NOT NULL,
                        `noteId` INTEGER,
                        `conceptId` TEXT,
                        `due` INTEGER NOT NULL,
                        `stabilityDays` REAL NOT NULL,
                        `difficulty` REAL NOT NULL,
                        `confidence` REAL NOT NULL,
                        `reps` INTEGER NOT NULL,
                        `lapses` INTEGER NOT NULL,
                        `lastEvidenceAt` INTEGER,
                        `retired` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_components_noteId` ON `knowledge_components` (`noteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_components_capabilityKey` ON `knowledge_components` (`capabilityKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_components_due` ON `knowledge_components` (`due`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_components_kind_due` ON `knowledge_components` (`kind`, `due`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `capability_evidence` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `componentKey` TEXT NOT NULL,
                        `episodeId` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `observedAt` INTEGER NOT NULL,
                        `taskKind` TEXT NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `supportLevel` INTEGER NOT NULL,
                        `evidenceWeight` REAL NOT NULL,
                        `responseMs` INTEGER,
                        `novelContext` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        FOREIGN KEY(`componentKey`) REFERENCES `knowledge_components`(`key`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_capability_evidence_componentKey` ON `capability_evidence` (`componentKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_capability_evidence_episodeId` ON `capability_evidence` (`episodeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_capability_evidence_observedAt` ON `capability_evidence` (`observedAt`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_capability_evidence_episodeId_taskId_componentKey` ON `capability_evidence` (`episodeId`, `taskId`, `componentKey`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `capability_progress` (
                        `capabilityKey` TEXT NOT NULL,
                        `band` TEXT NOT NULL,
                        `unit` INTEGER NOT NULL,
                        `canDo` TEXT NOT NULL,
                        `completedEpisodes` INTEGER NOT NULL,
                        `successfulTransferProbes` INTEGER NOT NULL,
                        `attemptedTransferProbes` INTEGER NOT NULL,
                        `lastTransferScore` REAL,
                        `lastEpisodeAt` INTEGER,
                        `certifiedAt` INTEGER,
                        PRIMARY KEY(`capabilityKey`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_capability_progress_band` ON `capability_progress` (`band`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_capability_progress_unit` ON `capability_progress` (`unit`)")

                // Carry route position forward from the old unit exit tickets. Their
                // scoring did not distinguish assisted from unsupported production,
                // so it may mark an episode route complete but never certifies a
                // capability in the new evidence model.
                db.execSQL("""
                    INSERT OR IGNORE INTO capability_progress
                    (`capabilityKey`,`band`,`unit`,`canDo`,`completedEpisodes`,`successfulTransferProbes`,`attemptedTransferProbes`,`lastTransferScore`,`lastEpisodeAt`,`certifiedAt`)
                    SELECT band || ':' || unit, band, unit, '',
                           CASE WHEN MAX(CASE WHEN recognition=1 AND production=1 AND listening=1 AND reading=1 THEN 1 ELSE 0 END)=1 THEN 3 ELSE 1 END,
                           CASE WHEN MAX(CASE WHEN recognition=1 AND production=1 AND listening=1 AND reading=1 THEN 1 ELSE 0 END)=1 THEN 3 ELSE MAX(CASE WHEN production=1 THEN 1 ELSE 0 END) END,
                           CASE WHEN MAX(CASE WHEN recognition=1 AND production=1 AND listening=1 AND reading=1 THEN 1 ELSE 0 END)=1 THEN 3 ELSE 1 END,
                           CAST(MAX(CASE WHEN production=1 THEN 1 ELSE 0 END) AS REAL), MAX(completedAt), NULL
                    FROM exit_ticket_results
                    GROUP BY band, unit
                """.trimIndent())

                // One meaning, form, and sound component per curriculum lexeme. The
                // representative legacy card contributes timing, never a mastery claim.
                for ((kind, type, prefix) in listOf(
                    Triple("MEANING", "RU_TO_MEANING", "MEANING:"),
                    Triple("FORM", "MEANING_TO_RU", "FORM:"),
                    Triple("SOUND", "AUDIO_TO_RU", "SOUND:")
                )) {
                    db.execSQL("""
                        INSERT OR IGNORE INTO knowledge_components
                        (`key`,`kind`,`capabilityKey`,`band`,`unit`,`noteId`,`conceptId`,`due`,`stabilityDays`,`difficulty`,`confidence`,`reps`,`lapses`,`lastEvidenceAt`,`retired`)
                        SELECT '$prefix' || n.id, '$kind', COALESCE(n.cefrLevel,'A1') || ':' || COALESCE(n.unit,0),
                               COALESCE(n.cefrLevel,'A1'), COALESCE(n.unit,0), n.id, NULL,
                               COALESCE(MIN(c.due),0), COALESCE(MAX(c.stability),0),
                               COALESCE(MAX(NULLIF(c.difficulty,0)),5),
                               CASE WHEN COALESCE(MAX(c.reps),0) > 0 THEN 0.20 ELSE 0 END,
                               COALESCE(MAX(c.reps),0), COALESCE(MAX(c.lapses),0), MAX(c.lastReview),
                               CASE WHEN n.status = 'IGNORED' THEN 1 ELSE 0 END
                        FROM notes n LEFT JOIN cards c ON c.noteId=n.id AND c.cardType='$type'
                        WHERE n.tier=0 AND n.partOfSpeech != 'lesson'
                        GROUP BY n.id
                    """.trimIndent())
                }

                db.execSQL("""
                    INSERT OR IGNORE INTO knowledge_components
                    (`key`,`kind`,`capabilityKey`,`band`,`unit`,`noteId`,`conceptId`,`due`,`stabilityDays`,`difficulty`,`confidence`,`reps`,`lapses`,`lastEvidenceAt`,`retired`)
                    SELECT 'CONSTRUCTION:' || COALESCE(n.cefrLevel,'A1') || ':' || COALESCE(n.unit,0) || ':' || c.gramConcept,
                           'CONSTRUCTION', COALESCE(n.cefrLevel,'A1') || ':' || COALESCE(n.unit,0),
                           COALESCE(n.cefrLevel,'A1'), COALESCE(n.unit,0), NULL, c.gramConcept,
                           MIN(c.due), MAX(c.stability), COALESCE(MAX(NULLIF(c.difficulty,0)),5),
                           CASE WHEN MAX(c.reps)>0 THEN 0.15 ELSE 0 END,
                           MAX(c.reps), MAX(c.lapses), MAX(c.lastReview), 0
                    FROM cards c JOIN notes n ON n.id=c.noteId
                    WHERE c.gramConcept IS NOT NULL
                    GROUP BY COALESCE(n.cefrLevel,'A1'), COALESCE(n.unit,0), c.gramConcept
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO capability_evidence
                    (`componentKey`,`episodeId`,`taskId`,`observedAt`,`taskKind`,`outcome`,`supportLevel`,`evidenceWeight`,`responseMs`,`novelContext`,`source`)
                    SELECT
                        CASE
                            WHEN c.gramConcept IS NOT NULL THEN 'CONSTRUCTION:' || COALESCE(n.cefrLevel,'A1') || ':' || COALESCE(n.unit,0) || ':' || c.gramConcept
                            WHEN c.cardType IN ('AUDIO_TO_RU','DICTATION','SPEAK','SPEAK_SENTENCE','PHONOLOGY_MINIMAL_PAIR') THEN 'SOUND:' || n.id
                            WHEN c.cardType='RU_TO_MEANING' THEN 'MEANING:' || n.id
                            ELSE 'FORM:' || n.id
                        END,
                        'legacy:' || l.id, 'legacy:' || l.id, l.reviewDatetime, 'LEGACY_' || c.cardType,
                        CASE WHEN l.rating='AGAIN' THEN 'MISS' ELSE 'SUCCESS' END,
                        CASE WHEN c.cardType='LESSON' THEN 3 WHEN c.cardType='RU_TO_MEANING' THEN 1 ELSE 2 END,
                        CASE
                            WHEN c.cardType='LESSON' THEN 0.0
                            WHEN c.cardType='RU_TO_MEANING' THEN 0.55
                            WHEN c.cardType IN ('SPEAK','SPEAK_SENTENCE') THEN 0.45
                            ELSE 0.35
                        END,
                        NULL, 0, 'LEGACY_CARD'
                    FROM review_logs l JOIN cards c ON c.id=l.cardId JOIN notes n ON n.id=c.noteId
                    WHERE n.tier=0 AND n.partOfSpeech!='lesson'
                      AND EXISTS (
                          SELECT 1 FROM knowledge_components kc WHERE kc.`key` =
                            CASE
                                WHEN c.gramConcept IS NOT NULL THEN 'CONSTRUCTION:' || COALESCE(n.cefrLevel,'A1') || ':' || COALESCE(n.unit,0) || ':' || c.gramConcept
                                WHEN c.cardType IN ('AUDIO_TO_RU','DICTATION','SPEAK','SPEAK_SENTENCE','PHONOLOGY_MINIMAL_PAIR') THEN 'SOUND:' || n.id
                                WHEN c.cardType='RU_TO_MEANING' THEN 'MEANING:' || n.id
                                ELSE 'FORM:' || n.id
                            END
                      )
                """.trimIndent())
            }
        }
    }
}
