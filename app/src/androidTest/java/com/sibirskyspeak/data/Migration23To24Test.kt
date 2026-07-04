package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration23To24Test {
    private val dbName = "migration-23-24"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateCreatesConfusionEventsTableWithoutLosingExistingNotes() {
        helper.createDatabase(dbName, 23).apply {
            execSQL("INSERT INTO notes (id,russian,translation,partOfSpeech,lemma,audioPath,exampleSentence,exampleTranslation,exampleSentence2,exampleTranslation2,exampleSentence3,exampleTranslation3,aspectPartner,aspect,aktionsart,aktionsartConfidence,declensionJson,gender,generalFreqRank,domainFreqRank,encounterCount,status,tags,tier,unit,conceptId,cefrLevel,mnemonic,secondSense,secondSenseExample,secondSenseExampleTranslation,chunkParentNoteId) VALUES (1,'дом','house','noun','дом',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,'NEW','',1,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 24, true, AppDatabase.MIGRATION_23_24)
        db.execSQL("INSERT INTO confusion_events (expectedKey,producedKey,cardType,at) VALUES ('GEN_SG','DAT_SG','CASE_FILL',1000)")
        db.query("SELECT COUNT(*) FROM confusion_events").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM notes").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }
}
