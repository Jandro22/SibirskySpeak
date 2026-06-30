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
class Migration15To16Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun createsMinedCacheAndAdaptiveModelTablesWithoutDestructiveMigration() {
        helper.createDatabase(DB, 15).close()
        val db = helper.runMigrationsAndValidate(DB, 16, true, AppDatabase.MIGRATION_15_16)
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertTrue("mined_examples" in tables)
        assertTrue("item_difficulty" in tables)
        assertTrue("concept_mastery" in tables)
        assertTrue("optimizer_parameters" in tables)
        db.close()
    }

    companion object { private const val DB = "migration-15-16" }
}
