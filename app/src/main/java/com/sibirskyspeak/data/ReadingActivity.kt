package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable learning history for scheduled reading; unlike telemetry, never expires. */
@Entity(
    tableName = "reading_activities",
    foreignKeys = [ForeignKey(
        entity = ReaderText::class,
        parentColumns = ["id"],
        childColumns = ["readerTextId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("readerTextId"), Index("completedAt")]
)
data class ReadingActivity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val readerTextId: Long,
    val completedAt: Long,
    val mistakes: Int,
    val intervalDays: Int
)
