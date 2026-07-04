package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration18To19Test {
    private val dbName = "migration-18-19"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateAddsHotQueueIndexesWithoutLosingCards() {
        helper.createDatabase(dbName, 18).apply {
            execSQL("INSERT INTO notes (id,russian,translation,partOfSpeech,lemma,audioPath,exampleSentence,exampleTranslation,exampleSentence2,exampleTranslation2,exampleSentence3,exampleTranslation3,aspectPartner,aspect,aktionsart,aktionsartConfidence,declensionJson,gender,generalFreqRank,domainFreqRank,encounterCount,status,tags,tier,unit,conceptId,cefrLevel,mnemonic) VALUES (1,'дом','house','noun','дом',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,'NEW','',1,NULL,NULL,NULL,NULL)")
            execSQL("INSERT INTO cards (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps,lapses,state,lastReview,gramCase,gramGender,gramNumber,gramContextCue,gramConcept,consecutiveCorrect,suspended) VALUES (1,1,'RU_TO_MEANING','VOCAB',0,0,0,0,0,0,0,'NEW',NULL,NULL,NULL,NULL,NULL,NULL,0,0)")
            close()
        }

        // Verify against the raw post-migration handle MigrationTestHelper hands
        // back, not a freshly opened Room instance — a Room.databaseBuilder(...)
        // targeting AppDatabase always opens at the *current* head version, so it
        // would need every migration up to head, not just this one, and would
        // silently break again the next time a migration is added on top.
        val db = helper.runMigrationsAndValidate(dbName, 19, true, AppDatabase.MIGRATION_18_19)
        db.query("SELECT COUNT(*) FROM cards").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }
}
