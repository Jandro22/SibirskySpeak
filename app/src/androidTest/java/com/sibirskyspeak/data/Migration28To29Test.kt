package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration28To29Test {
    private val dbName = "migration-28-29"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationAddsStableBandToExistingExitTicketResults() {
        helper.createDatabase(dbName, 28).apply {
            execSQL("INSERT INTO exit_ticket_results (id,unit,recognition,production,listening,reading,completedAt) VALUES (1,12,1,0,1,1,100)")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 29, true, AppDatabase.MIGRATION_28_29)
        db.query("SELECT unit, band FROM exit_ticket_results WHERE id=1").use { cursor ->
            cursor.moveToFirst()
            assertEquals(12, cursor.getInt(0))
            assertEquals("A1", cursor.getString(1))
        }
    }
}
