package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reader_texts")
data class ReaderText(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val source: String = "local",
    val createdAt: Long = System.currentTimeMillis()
)
