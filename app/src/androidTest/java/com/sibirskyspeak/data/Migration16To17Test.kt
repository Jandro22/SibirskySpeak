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
class Migration16To17Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migratesAdaptiveWorldModelTables() {
        helper.createDatabase(DB, 16).close()
        val db = helper.runMigrationsAndValidate(DB, 17, true, AppDatabase.MIGRATION_16_17)
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table'")
        val tables = buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
            cursor.close()
        }
        assertTrue("skill_rating" in tables)
        assertTrue("capacity_state" in tables)
        assertTrue("willingness_state" in tables)
        assertTrue("rival_state" in tables)
        assertTrue("ghost_snapshot" in tables)
        assertTrue("match_history" in tables)
        assertTrue("pace_log" in tables)
        assertTrue("bandit_pending" in tables)
    }

    companion object {
        private const val DB = "migration-16-17-test"
    }
}
