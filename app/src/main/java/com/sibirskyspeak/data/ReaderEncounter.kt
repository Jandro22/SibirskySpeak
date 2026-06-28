package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** One durable exposure credit per note per text. */
@Entity(
    tableName = "reader_encounters",
    primaryKeys = ["readerTextId", "noteId"],
    foreignKeys = [
        ForeignKey(entity = ReaderText::class, parentColumns = ["id"], childColumns = ["readerTextId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("noteId")]
)
data class ReaderEncounter(
    val readerTextId: Long,
    val noteId: Long,
    val encounteredAt: Long = System.currentTimeMillis()
)
