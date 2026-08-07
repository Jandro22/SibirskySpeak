package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration33To34Test {
    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun projectsLegacyHistoryConservativelyWithoutChangingIt() {
        val name = "migration-33-34"
        helper.createDatabase(name, 33).apply {
            execSQL("""
                INSERT INTO notes
                (id,russian,translation,partOfSpeech,lemma,encounterCount,status,tags,tier,unit,cefrLevel)
                VALUES (7,'дом','house','noun','дом',0,'NEW','',0,1,'A1')
            """.trimIndent())
            execSQL("""
                INSERT INTO cards
                (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps,lapses,state,lastReview,consecutiveCorrect,suspended)
                VALUES (8,7,'RU_TO_MEANING','RECOGNITION',123,4.0,5.0,0,1,2,0,'REVIEW',100,1,0)
            """.trimIndent())
            execSQL("""
                INSERT INTO review_logs
                (id,cardId,reviewDatetime,rating,stateBefore,scheduledDays,elapsedDays,source,stabilityBefore)
                VALUES (9,8,100,'GOOD','LEARNING',1,0,'REVIEW',2.0)
            """.trimIndent())
            execSQL("""
                INSERT INTO exit_ticket_results
                (id,unit,band,recognition,production,listening,reading,completedAt)
                VALUES (10,1,'A1',1,1,1,1,110)
            """.trimIndent())
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 34, true, AppDatabase.MIGRATION_33_34)
        db.query("SELECT kind,noteId,reps FROM knowledge_components WHERE `key`='MEANING:7'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("MEANING", cursor.getString(0))
            assertEquals(7L, cursor.getLong(1))
            assertEquals(2, cursor.getInt(2))
        }
        db.query("SELECT supportLevel,evidenceWeight FROM capability_evidence WHERE componentKey='MEANING:7'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
            assertEquals(0.55, cursor.getDouble(1), 0.0)
        }
        db.query("SELECT rating FROM review_logs WHERE id=9").use { cursor ->
            cursor.moveToFirst()
            assertEquals("GOOD", cursor.getString(0))
        }
        db.query("SELECT completedEpisodes,successfulTransferProbes,attemptedTransferProbes,certifiedAt FROM capability_progress WHERE capabilityKey='A1:1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(3, cursor.getInt(0))
            assertEquals(3, cursor.getInt(1))
            assertEquals(3, cursor.getInt(2))
            assertNull(if (cursor.isNull(3)) null else cursor.getString(3))
        }
        db.close()
    }
}
