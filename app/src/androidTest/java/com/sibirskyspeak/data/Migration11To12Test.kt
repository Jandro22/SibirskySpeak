package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration11To12Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationRecoversCompletionFromExistingScheduleLastCompleted() {
        helper.createDatabase(DB, 11).apply {
            execSQL("INSERT INTO reader_texts (id,title,body,source,createdAt) VALUES (1,'Story','Body text','bootstrap',0)")
            // No matching telemetry event, so the first backfill INSERT recovers nothing;
            // the second must pick this up from the schedule's own lastCompleted instead.
            execSQL("INSERT INTO reading_schedules (readerTextId,due,intervalDays,reps,lapses,lastCompleted) VALUES (1,86400000,3,2,0,50000)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 12, true, AppDatabase.MIGRATION_11_12)
        db.query("SELECT readerTextId, completedAt, mistakes, intervalDays FROM reading_activities WHERE readerTextId = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(50000L, cursor.getLong(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals(3, cursor.getInt(3))
        }
    }

    companion object {
        private const val DB = "migration-11-12-test"
    }
}
