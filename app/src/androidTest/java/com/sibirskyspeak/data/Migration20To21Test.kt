package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration20To21Test {
    private val dbName = "migration-20-21"

    @get:Rule val helper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun addsNullableEvidenceStrengthWithoutChangingHistory() {
        helper.createDatabase(dbName, 20).close()
        val db = helper.runMigrationsAndValidate(dbName, 21, true, AppDatabase.MIGRATION_20_21)
        db.query("PRAGMA table_info(review_logs)").use { cursor ->
            val name = cursor.getColumnIndex("name")
            val notNull = cursor.getColumnIndex("notnull")
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(name) == "evidenceStrength") {
                found = true
                assertEquals(0, cursor.getInt(notNull))
            }
            assertEquals(true, found)
        }
    }
}
