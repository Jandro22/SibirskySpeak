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
class Migration9To10Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationBackfillsScheduleForEveryExistingReaderText() {
        helper.createDatabase(DB, 9).apply {
            execSQL("INSERT INTO reader_texts (id,title,body,source,createdAt) VALUES (1,'Story','Body text','bootstrap',0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 10, true, AppDatabase.MIGRATION_9_10)
        db.query("SELECT readerTextId, due, intervalDays, reps, lapses, lastCompleted FROM reading_schedules WHERE readerTextId = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals(0, cursor.getInt(4))
            assertEquals(true, cursor.isNull(5))
        }
    }

    companion object {
        private const val DB = "migration-9-10-test"
    }
}
