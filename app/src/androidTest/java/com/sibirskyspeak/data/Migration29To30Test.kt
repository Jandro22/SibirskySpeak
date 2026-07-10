package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration29To30Test {
    private val dbName = "migration-29-30"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationPreservesLegacyEncounterEvidenceInTypedChannel() {
        helper.createDatabase(dbName, 29).apply {
            execSQL("INSERT INTO notes (id,russian,translation,partOfSpeech,lemma,encounterCount,status,tags,tier) VALUES (1,'слово','word','noun','слово',7,'LEARNING','',1)")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 30, true, AppDatabase.MIGRATION_29_30)
        db.query("SELECT directRetrievals,passiveExposures,lookups FROM note_evidence WHERE noteId=1").use { cursor ->
            cursor.moveToFirst()
            assertEquals(7, cursor.getInt(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
        }
    }
}
