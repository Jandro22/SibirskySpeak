package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Persistent lightweight schedule for one connected-text "jumbo card". */
@Entity(
    tableName = "reading_schedules",
    primaryKeys = ["readerTextId"],
    foreignKeys = [ForeignKey(
        entity = ReaderText::class,
        parentColumns = ["id"],
        childColumns = ["readerTextId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("due")]
)
data class ReadingSchedule(
    val readerTextId: Long,
    val due: Long = 0L,
    val intervalDays: Int = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val lastCompleted: Long? = null
)

data class ReadingAssignment(
    val recommendation: ReaderRecommendation,
    val schedule: ReadingSchedule,
    val insertionIndex: Int
)
