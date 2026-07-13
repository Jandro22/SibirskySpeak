package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A durable place in a reader text that the learner explicitly wants to revisit. */
@Entity(
    tableName = "reader_bookmarks",
    foreignKeys = [ForeignKey(
        entity = ReaderText::class,
        parentColumns = ["id"],
        childColumns = ["readerTextId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["readerTextId", "tokenIndex"], unique = true)]
)
data class ReaderBookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val readerTextId: Long,
    val tokenIndex: Int,
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
