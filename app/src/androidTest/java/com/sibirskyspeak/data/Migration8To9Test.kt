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
class Migration8To9Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationAddsMnemonicColumnWithoutLosingExistingNotes() {
        helper.createDatabase(DB, 8).apply {
            execSQL(
                "INSERT INTO notes (id,russian,translation,partOfSpeech,lemma,audioPath,exampleSentence,exampleTranslation,exampleSentence2,exampleTranslation2,exampleSentence3,exampleTranslation3,aspectPartner,aspect,aktionsart,aktionsartConfidence,declensionJson,gender,generalFreqRank,domainFreqRank,encounterCount,status,tags,tier,unit,conceptId,cefrLevel) " +
                    "VALUES (1,'дом','house','noun','дом',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,'NEW','',1,NULL,NULL,NULL)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 9, true, AppDatabase.MIGRATION_8_9)
        db.query("SELECT russian, mnemonic FROM notes WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("дом", cursor.getString(0))
            assertEquals(true, cursor.isNull(1))
        }
    }

    companion object {
        private const val DB = "migration-8-9-test"
    }
}
