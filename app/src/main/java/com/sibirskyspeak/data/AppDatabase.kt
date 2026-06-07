package com.sibirskyspeak.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Note::class, Card::class, ReviewLog::class, ConfusablePair::class, ReaderText::class],
    version = 7,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun cardDao(): CardDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun confusablePairDao(): ConfusablePairDao
    abstract fun readerTextDao(): ReaderTextDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sibirsky_speak.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
