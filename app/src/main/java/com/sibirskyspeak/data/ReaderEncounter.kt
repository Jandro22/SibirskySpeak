package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Most recent explicit lookup of a note in a text. A lookup is evidence of need,
 * never positive exposure or mastery; the composite key keeps only its latest time. */
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
