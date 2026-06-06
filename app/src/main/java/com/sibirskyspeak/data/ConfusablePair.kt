package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "confusable_pairs",
    foreignKeys = [
        ForeignKey(Note::class, ["id"], ["firstNoteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Note::class, ["id"], ["secondNoteId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("firstNoteId"), Index("secondNoteId")]
)
data class ConfusablePair(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val firstNoteId: Long,
    val secondNoteId: Long,
    val reason: String
)
