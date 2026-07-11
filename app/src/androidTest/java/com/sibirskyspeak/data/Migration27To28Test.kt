package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration27To28Test {
    private val dbName = "migration-27-28"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationCreatesCurriculumAndExitTicketTables() {
        val db = helper.createDatabase(dbName, 27)
        db.close()

        val migrated = helper.runMigrationsAndValidate(dbName, 28, true, AppDatabase.MIGRATION_27_28)
        migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('curriculum_state','curriculum_migration_reports','exit_ticket_results')").use { cursor ->
            assertEquals(3, cursor.count)
        }
        migrated.execSQL("INSERT INTO curriculum_state (id,version,checksum,manifestJson,installedAt) VALUES (0,'v1','abc','{}',10)")
        migrated.query("SELECT version, checksum FROM curriculum_state WHERE id=0").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("v1", cursor.getString(0))
            assertEquals("abc", cursor.getString(1))
        }
    }
}
