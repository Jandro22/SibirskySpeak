package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration10To11Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationCreatesReaderEncountersTable() {
        helper.createDatabase(DB, 10).close()
        val db = helper.runMigrationsAndValidate(DB, 11, true, AppDatabase.MIGRATION_10_11)
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table'")
        val tables = buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
            cursor.close()
        }
        assertTrue("reader_encounters" in tables)
    }

    companion object {
        private const val DB = "migration-10-11-test"
    }
}
