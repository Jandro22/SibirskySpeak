package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "review_logs",
    foreignKeys = [
        ForeignKey(
            entity = Card::class,
            parentColumns = ["id"],
            childColumns = ["cardId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("cardId"), Index("reviewDatetime")]
)
data class ReviewLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val reviewDatetime: Long,
    val rating: Rating,
    val stateBefore: CardState,
    val scheduledDays: Int,
    val elapsedDays: Int,
    val source: ReviewSource,
    // The card's stability going *into* this review (the value carried over from the
    // previous review). Together with [elapsedDays] and the recall outcome this is
    // exactly what the on-device FSRS weight fit needs to reconstruct the forgetting
    // curve. 0.0 for NEW-card first reviews and for rows logged before this column
    // existed (those are simply skipped by the fitter).
    val stabilityBefore: Double = 0.0
)
