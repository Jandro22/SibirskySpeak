package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the supported historical path preserves representative learner rows. */
@RunWith(AndroidJUnit4::class)
class MigrationChainTest {
    private val dbName = "migration-chain-7-33"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun version7RowsSurviveTheFullMigrationChain() {
        helper.createDatabase(dbName, 7).apply {
            execSQL("INSERT INTO notes (id,russian,translation,partOfSpeech,lemma,encounterCount,status,tags,tier,unit,conceptId,cefrLevel) VALUES (7,'дом','house','noun','дом',4,'LEARNING','fixture',0,1,'GEN','A1')")
            execSQL("INSERT INTO cards (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps,lapses,state,lastReview,gramCase,gramGender,gramNumber,gramContextCue,gramConcept,consecutiveCorrect,suspended) VALUES (7,7,'RU_TO_MEANING','VOCAB',1234,2.0,4.0,2,3,5,1,'REVIEW',1000,NULL,NULL,NULL,NULL,NULL,3,0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            33,
            true,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
            AppDatabase.MIGRATION_12_13,
            AppDatabase.MIGRATION_13_14,
            AppDatabase.MIGRATION_14_15,
            AppDatabase.MIGRATION_15_16,
            AppDatabase.MIGRATION_16_17,
            AppDatabase.MIGRATION_17_18,
            AppDatabase.MIGRATION_18_19,
            AppDatabase.MIGRATION_19_20,
            AppDatabase.MIGRATION_20_21,
            AppDatabase.MIGRATION_21_22,
            AppDatabase.MIGRATION_22_23,
            AppDatabase.MIGRATION_23_24,
            AppDatabase.MIGRATION_24_25,
            AppDatabase.MIGRATION_25_26,
            AppDatabase.MIGRATION_26_27,
            AppDatabase.MIGRATION_27_28,
            AppDatabase.MIGRATION_28_29,
            AppDatabase.MIGRATION_29_30,
            AppDatabase.MIGRATION_30_31,
            AppDatabase.MIGRATION_31_32,
            AppDatabase.MIGRATION_32_33
        )
        db.query("SELECT russian, encounterCount, cefrLevel FROM notes WHERE id=7").use { cursor ->
            cursor.moveToFirst()
            assertEquals("дом", cursor.getString(0))
            assertEquals(4, cursor.getInt(1))
            assertEquals("A1", cursor.getString(2))
        }
        db.query("SELECT due, reps, lapses, state FROM cards WHERE id=7").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1234L, cursor.getLong(0))
            assertEquals(5, cursor.getInt(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals("REVIEW", cursor.getString(3))
        }
    }
}
