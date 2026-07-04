package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration19To20Test {
    private val dbName = "migration-19-20"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateAddsSecondSenseColumnsWithoutLosingNotes() {
        helper.createDatabase(dbName, 19).apply {
            execSQL("INSERT INTO notes (id,russian,translation,partOfSpeech,lemma,audioPath,exampleSentence,exampleTranslation,exampleSentence2,exampleTranslation2,exampleSentence3,exampleTranslation3,aspectPartner,aspect,aktionsart,aktionsartConfidence,declensionJson,gender,generalFreqRank,domainFreqRank,encounterCount,status,tags,tier,unit,conceptId,cefrLevel,mnemonic) VALUES (1,'мир','world, peace','noun','мир',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,'NEW','',1,NULL,NULL,NULL,NULL)")
            close()
        }

        // Verify against the raw post-migration handle MigrationTestHelper hands
        // back, not a freshly opened Room instance — a Room.databaseBuilder(...)
        // targeting AppDatabase always opens at the *current* head version, so it
        // would need every migration up to head, not just this one, and would
        // silently break again the next time a migration is added on top.
        val db = helper.runMigrationsAndValidate(dbName, 20, true, AppDatabase.MIGRATION_19_20)
        // Pre-existing rows survive with the new columns defaulting to NULL.
        db.query("SELECT secondSense, secondSenseExample, secondSenseExampleTranslation FROM notes WHERE id = 1").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(true, cursor.isNull(0))
            assertEquals(true, cursor.isNull(1))
            assertEquals(true, cursor.isNull(2))
        }
        // The new columns are writable and readable going forward.
        db.execSQL("UPDATE notes SET secondSense = 'peace', secondSenseExample = 'Он хочет мира.', secondSenseExampleTranslation = 'He wants peace.' WHERE id = 1")
        db.query("SELECT secondSense FROM notes WHERE id = 1").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("peace", cursor.getString(0))
        }
    }
}
