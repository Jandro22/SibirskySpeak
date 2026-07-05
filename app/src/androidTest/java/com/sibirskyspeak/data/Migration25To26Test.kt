package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration25To26Test {
    private val dbName = "migration-25-26"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    private fun noteInsert(id: Long, tier: Int, cefrLevel: String?, generalFreqRank: Int?, domainFreqRank: Int?) =
        "INSERT INTO notes (id,russian,translation,partOfSpeech,lemma,audioPath,exampleSentence,exampleTranslation," +
            "exampleSentence2,exampleTranslation2,exampleSentence3,exampleTranslation3,aspectPartner,aspect," +
            "aktionsart,aktionsartConfidence,declensionJson,gender,generalFreqRank,domainFreqRank,encounterCount," +
            "status,tags,tier,unit,conceptId,cefrLevel,mnemonic,secondSense,secondSenseExample," +
            "secondSenseExampleTranslation,chunkParentNoteId) VALUES " +
            "($id,'слово','word','noun','слово',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL," +
            "${generalFreqRank ?: "NULL"},${domainFreqRank ?: "NULL"},0,'NEW','',$tier,NULL,NULL," +
            "${cefrLevel?.let { "'$it'" } ?: "NULL"},NULL,NULL,NULL,NULL,NULL)"

    private fun cardInsert(
        id: Long, noteId: Long, cardType: String, state: String, due: Long,
        gramCase: String? = null, gramNumber: String? = null, suspended: Int = 0
    ) = "INSERT INTO cards (id,noteId,cardType,queue,due,stability,difficulty,elapsedDays,scheduledDays,reps," +
        "lapses,state,lastReview,gramCase,gramGender,gramNumber,gramContextCue,gramConcept,consecutiveCorrect," +
        "suspended) VALUES ($id,$noteId,'$cardType','GRAMMAR',$due,0.0,0.0,0,0,0,0,'$state',NULL," +
        "${gramCase?.let { "'$it'" } ?: "NULL"},NULL,${gramNumber?.let { "'$it'" } ?: "NULL"},NULL,NULL,0,$suspended)"

    @Test
    fun migrateBackfillsCefrLevelsDefersTier2DebtAndSuspendsOverPacedCaseDrills() {
        helper.createDatabase(dbName, 25).apply {
            // Tier-2 domain notes with no cefrLevel yet: a high-rank one (core institutional
            // vocab, -> B2) and a low-rank one (obscure/unranked, -> C2).
            execSQL(noteInsert(id = 1, tier = 2, cefrLevel = null, generalFreqRank = null, domainFreqRank = 100))
            execSQL(noteInsert(id = 2, tier = 2, cefrLevel = null, generalFreqRank = null, domainFreqRank = 5000))
            // Tier-1 general note with no cefrLevel yet, rank puts it comfortably in A1.
            execSQL(noteInsert(id = 3, tier = 1, cefrLevel = null, generalFreqRank = 500, domainFreqRank = null))
            // Tier-0 notes already tagged (must be left exactly as-is).
            execSQL(noteInsert(id = 4, tier = 0, cefrLevel = "A1", generalFreqRank = null, domainFreqRank = null))
            execSQL(noteInsert(id = 5, tier = 0, cefrLevel = "A2", generalFreqRank = null, domainFreqRank = null))

            // Tier-2 debt: one already-reviewed card (should be deferred) and one
            // still-NEW card on the same note (should NOT be touched by the defer step).
            execSQL(cardInsert(id = 1, noteId = 1, cardType = "RU_TO_MEANING", state = "REVIEW", due = 1_000L))
            execSQL(cardInsert(id = 2, noteId = 1, cardType = "RU_TO_MEANING", state = "NEW", due = 2_000L))

            // A1 note: ACC/SG must survive, GEN/SG must be suspended (non-accusative).
            execSQL(cardInsert(id = 3, noteId = 4, cardType = "CASE_FILL", state = "NEW", due = 0L, gramCase = "ACC", gramNumber = "SG"))
            execSQL(cardInsert(id = 4, noteId = 4, cardType = "CASE_FILL", state = "NEW", due = 0L, gramCase = "GEN", gramNumber = "SG"))
            // A2 note: GEN/SG must survive (singular unlocked at A2), GEN/PL must be suspended.
            execSQL(cardInsert(id = 5, noteId = 5, cardType = "CASE_FILL", state = "NEW", due = 0L, gramCase = "GEN", gramNumber = "SG"))
            execSQL(cardInsert(id = 6, noteId = 5, cardType = "CASE_FILL", state = "NEW", due = 0L, gramCase = "GEN", gramNumber = "PL"))
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 26, true, AppDatabase.MIGRATION_25_26)

        val cefrByNote = mutableMapOf<Long, String?>()
        db.query("SELECT id, cefrLevel FROM notes").use { cursor ->
            while (cursor.moveToNext()) cefrByNote[cursor.getLong(0)] = if (cursor.isNull(1)) null else cursor.getString(1)
        }
        assertEquals("B2", cefrByNote[1L])
        assertEquals("C2", cefrByNote[2L])
        assertEquals("A1", cefrByNote[3L])
        assertEquals("A1", cefrByNote[4L]) // untouched, already tagged
        assertEquals("A2", cefrByNote[5L]) // untouched, already tagged

        val dueById = mutableMapOf<Long, Long>()
        db.query("SELECT id, due FROM cards").use { cursor ->
            while (cursor.moveToNext()) dueById[cursor.getLong(0)] = cursor.getLong(1)
        }
        val seventyFiveDaysMs = 75L * 24 * 60 * 60 * 1000
        assertEquals(1_000L + seventyFiveDaysMs, dueById[1L]) // reviewed tier-2 card: deferred
        assertEquals(2_000L, dueById[2L]) // still-NEW tier-2 card: untouched

        val suspendedById = mutableMapOf<Long, Int>()
        db.query("SELECT id, suspended FROM cards").use { cursor ->
            while (cursor.moveToNext()) suspendedById[cursor.getLong(0)] = cursor.getInt(1)
        }
        assertEquals(0, suspendedById[3L]) // A1 ACC/SG: kept
        assertEquals(1, suspendedById[4L]) // A1 GEN/SG: suspended (non-accusative at A1)
        assertEquals(0, suspendedById[5L]) // A2 GEN/SG: kept
        assertEquals(1, suspendedById[6L]) // A2 GEN/PL: suspended (plural before B1)
    }
}
