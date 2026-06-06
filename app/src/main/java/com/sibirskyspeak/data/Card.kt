package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("noteId"), Index("due"), Index("queue")]
)
data class Card(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val cardType: CardType,
    val queue: Queue,
    val due: Long = System.currentTimeMillis(),
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val elapsedDays: Int = 0,
    val scheduledDays: Int = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val state: CardState = CardState.NEW,
    val lastReview: Long? = null,
    val gramCase: String? = null,
    val gramGender: String? = null,
    val gramNumber: String? = null,
    val gramContextCue: String? = null,
    val consecutiveCorrect: Int = 0
)
