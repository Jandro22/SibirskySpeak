package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration30To31Test {
    private val dbName = "migration-30-31"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationRemovesBadCardsAndLogsOnChunkNotesButKeepsTheirChunkCardAndOrdinaryNoteCards() {
        helper.createDatabase(dbName, 30).apply {
            // A regular vocab note keeps all its cards untouched.
            execSQL("INSERT INTO notes (id,russian,translation,partOfSpeech,lemma,encounterCount,status,tags,tier) VALUES (1,'дверь','door','noun','дверь',0,'LEARNING','',0)")
            execSQL("INSERT INTO cards (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps,lapses,state,consecutiveCorrect,suspended) VALUES (100,1,'RU_TO_MEANING','VOCAB',0,0,0,0,0,0,0,'NEW',0,0)")

            // A chunk note (translation="") minted from a collocation of that parent.
            execSQL("INSERT INTO notes (id,russian,translation,partOfSpeech,lemma,encounterCount,status,tags,tier,chunkParentNoteId) VALUES (2,'дверь открытой','','chunk','дверь открытой',0,'LEARNING','chunk',0,1)")
            // Its legitimate CHUNK card -- must survive.
            execSQL("INSERT INTO cards (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps,lapses,state,consecutiveCorrect,suspended) VALUES (200,2,'CHUNK','VOCAB',0,0,0,0,0,0,0,'NEW',0,0)")
            // Bad baseline cards syncPedagogicalFacets used to mint for it -- must be removed.
            execSQL("INSERT INTO cards (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps,lapses,state,consecutiveCorrect,suspended) VALUES (201,2,'RU_TO_MEANING','VOCAB',0,0,0,3,0,3,0,'LEARNING',0,0)")
            execSQL("INSERT INTO cards (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps,lapses,state,consecutiveCorrect,suspended) VALUES (202,2,'MEANING_TO_RU','VOCAB',0,0,0,0,0,0,0,'NEW',0,0)")
            // A review log against the bad card -- must be removed with it (no orphan FK row).
            execSQL("INSERT INTO review_logs (id,cardId,reviewDatetime,rating,stateBefore,scheduledDays,elapsedDays,source,stabilityBefore) VALUES (900,201,0,'AGAIN','NEW',0,0,'STUDY',0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 31, true, AppDatabase.MIGRATION_30_31)

        db.query("SELECT id FROM cards ORDER BY id").use { cursor ->
            val ids = mutableListOf<Long>()
            while (cursor.moveToNext()) ids.add(cursor.getLong(0))
            assertEquals(listOf(100L, 200L), ids)
        }
        db.query("SELECT COUNT(*) FROM review_logs WHERE cardId = 201").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }
}
