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
class Migration13To14Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationHealsDegenerateGraduatedVocabCardsOnly() {
        helper.createDatabase(DB, 13).apply {
            execSQL(
                "INSERT INTO notes (id,russian,translation,partOfSpeech,lemma,audioPath,exampleSentence,exampleTranslation,exampleSentence2,exampleTranslation2,exampleSentence3,exampleTranslation3,aspectPartner,aspect,aktionsart,aktionsartConfidence,declensionJson,gender,generalFreqRank,domainFreqRank,encounterCount,status,tags,tier,unit,conceptId,cefrLevel,mnemonic) " +
                    "VALUES (1,'дом','house','noun','дом',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,'NEW','',1,NULL,NULL,NULL,NULL)"
            )
            // Degenerate: graduated VOCAB card with stability=0 — this must be healed.
            execSQL(
                "INSERT INTO cards (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps,lapses,state,lastReview,gramCase,gramGender,gramNumber,gramContextCue,gramConcept,consecutiveCorrect,suspended) " +
                    "VALUES (1,1,'RU_TO_MEANING','VOCAB',0,0,0,0,0,0,0,'GRADUATED',NULL,NULL,NULL,NULL,NULL,NULL,0,0)"
            )
            // A LESSON/GRAMMAR card graduated with stability=0 by design — must NOT be touched.
            execSQL(
                "INSERT INTO cards (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps,lapses,state,lastReview,gramCase,gramGender,gramNumber,gramContextCue,gramConcept,consecutiveCorrect,suspended) " +
                    "VALUES (2,1,'LESSON','GRAMMAR',0,0,0,0,0,1,0,'GRADUATED',NULL,NULL,NULL,NULL,NULL,NULL,0,0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 14, true, AppDatabase.MIGRATION_13_14)
        db.query("SELECT stability, difficulty, scheduledDays, reps, consecutiveCorrect FROM cards WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(365.0, cursor.getDouble(0), 0.0001)
            assertEquals(3.0, cursor.getDouble(1), 0.0001)
            assertEquals(365, cursor.getInt(2))
            assertEquals(1, cursor.getInt(3))
            assertEquals(1, cursor.getInt(4))
        }
        db.query("SELECT stability, difficulty FROM cards WHERE id = 2").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0.0, cursor.getDouble(0), 0.0001)
            assertEquals(0.0, cursor.getDouble(1), 0.0001)
        }
    }

    companion object {
        private const val DB = "migration-13-14-test"
    }
}
