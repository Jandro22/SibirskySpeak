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
class Migration12To13Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationAddsStabilityBeforeDefaultingToZeroForOldRows() {
        helper.createDatabase(DB, 12).apply {
            execSQL(
                "INSERT INTO review_logs (id,cardId,reviewDatetime,rating,stateBefore,scheduledDays,elapsedDays,source) " +
                    "VALUES (1,1,0,'GOOD','NEW',1,0,'SRS_REVIEW')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 13, true, AppDatabase.MIGRATION_12_13)
        db.query("SELECT stabilityBefore FROM review_logs WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0.0, cursor.getDouble(0), 0.0001)
        }
    }

    companion object {
        private const val DB = "migration-12-13-test"
    }
}
