package com.sibirskyspeak.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration31To32Test {
    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun addsReaderBookmarksWithUniqueTextPosition() {
        val name = "migration-31-32"
        helper.createDatabase(name, 31).apply {
            execSQL("INSERT INTO reader_texts (id,title,body,source,createdAt) VALUES (7,'Text','дом','local',0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 32, true, AppDatabase.MIGRATION_31_32)
        db.execSQL("INSERT INTO reader_bookmarks (readerTextId,tokenIndex,label,createdAt) VALUES (7,2,'house',10)")
        db.query("SELECT COUNT(*) FROM reader_bookmarks WHERE readerTextId=7 AND tokenIndex=2").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }
}
