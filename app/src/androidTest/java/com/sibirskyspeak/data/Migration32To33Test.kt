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
class Migration32To33Test {
    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun addsOptionalParallelTranslationWithoutChangingExistingTexts() {
        val name = "migration-32-33"
        helper.createDatabase(name, 32).apply {
            execSQL("INSERT INTO reader_texts (id,title,body,source,createdAt) VALUES (7,'Text','дом','local',0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 33, true, AppDatabase.MIGRATION_32_33)
        db.query("SELECT body, translationBody FROM reader_texts WHERE id=7").use { cursor ->
            cursor.moveToFirst()
            assertEquals("дом", cursor.getString(0))
            assertNull(cursor.getString(1))
        }
        db.close()
    }
}
