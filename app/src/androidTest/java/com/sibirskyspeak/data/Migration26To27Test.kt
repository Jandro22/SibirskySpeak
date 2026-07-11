package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration26To27Test {
    private val dbName = "migration-26-27"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationAddsCategoryAndMapsLegacyGrammarConcepts() {
        helper.createDatabase(dbName, 26).apply {
            execSQL("INSERT INTO notes (id,russian,translation,partOfSpeech,lemma,encounterCount,status,tags,tier,unit,conceptId) VALUES (1,'слово','word','noun','слово',0,'LEARNING','',1,6,'GEN')")
            execSQL("INSERT INTO cards (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps,lapses,state,lastReview,gramConcept,consecutiveCorrect,suspended) VALUES (1,1,'CASE_FILL','GRAMMAR',0,0.0,0.0,0,0,0,0,'NEW',NULL,'GEN',0,0)")
            execSQL("INSERT INTO confusion_events (id,expectedKey,producedKey,cardType,at) VALUES (1,'GEN','ACC','CASE_FILL',100)")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 27, true, AppDatabase.MIGRATION_26_27)
        db.query("SELECT category FROM confusion_events WHERE id=1").use { cursor ->
            cursor.moveToFirst()
            assertEquals("CASE_ENDING", cursor.getString(0))
        }
        db.query("SELECT conceptId FROM notes WHERE id=1").use { cursor ->
            cursor.moveToFirst()
            assertEquals("GEN_CHUNK_POSSESSION", cursor.getString(0))
        }
        db.query("SELECT gramConcept FROM cards WHERE id=1").use { cursor ->
            cursor.moveToFirst()
            assertEquals("GEN_CHUNK_POSSESSION", cursor.getString(0))
        }
    }
}
