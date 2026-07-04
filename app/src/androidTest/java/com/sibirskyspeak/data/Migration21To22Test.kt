package com.sibirskyspeak.data
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class) class Migration21To22Test {
 @get:Rule val helper=MigrationTestHelper(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),AppDatabase::class.java, emptyList(),FrameworkSQLiteOpenHelperFactory())
 @Test fun createsWeeklyReports(){ val n="migration-21-22";helper.createDatabase(n,21).close();helper.runMigrationsAndValidate(n,22,true,AppDatabase.MIGRATION_21_22).close() }
}
