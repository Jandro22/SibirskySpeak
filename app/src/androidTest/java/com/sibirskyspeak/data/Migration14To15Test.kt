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
class Migration14To15Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationPurgesOnlyStressMarkCards() {
        helper.createDatabase(DB, 14).apply {
            execSQL(
                "INSERT INTO notes (id,russian,translation,partOfSpeech,lemma,audioPath,exampleSentence,exampleTranslation,exampleSentence2,exampleTranslation2,exampleSentence3,exampleTranslation3,aspectPartner,aspect,aktionsart,aktionsartConfidence,declensionJson,gender,generalFreqRank,domainFreqRank,encounterCount,status,tags,tier,unit,conceptId,cefrLevel,mnemonic) " +
                    "VALUES (1,'дом','house','noun','дом',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,'NEW','',1,NULL,NULL,NULL,NULL)"
            )
            execSQL(
                "INSERT INTO cards (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps,lapses,state,lastReview,gramCase,gramGender,gramNumber,gramContextCue,gramConcept,consecutiveCorrect,suspended) " +
                    "VALUES (1,1,'STRESS_MARK','VOCAB',0,0,0,0,0,0,0,'NEW',NULL,NULL,NULL,NULL,NULL,NULL,0,0)"
            )
            execSQL(
                "INSERT INTO cards (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps,lapses,state,lastReview,gramCase,gramGender,gramNumber,gramContextCue,gramConcept,consecutiveCorrect,suspended) " +
                    "VALUES (2,1,'RU_TO_MEANING','VOCAB',0,0,0,0,0,0,0,'NEW',NULL,NULL,NULL,NULL,NULL,NULL,0,0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 15, true, AppDatabase.MIGRATION_14_15)
        db.query("SELECT COUNT(*) FROM cards WHERE cardType = 'STRESS_MARK'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM cards WHERE id = 2").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    companion object {
        private const val DB = "migration-14-15-test"
    }
}
