package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted "which wrong form did the learner produce" classification (P4.5),
 * from review/AnswerDiagnosis.kt's [com.sibirskyspeak.review.classifyAnswer]. A
 * recurring (expectedKey, producedKey, cardType) pair is the input to contrastive-
 * pair insertion (LearningRepository.topConfusionPair) and the weekly letter's
 * "top confusion this week" line.
 */
@Entity(tableName = "confusion_events", indices = [Index("at")])
data class ConfusionEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expectedKey: String,
    val producedKey: String,
    val cardType: CardType,
    val at: Long
)

/** Aggregate row for grouping confusion_events by pair (Room POJO, not an entity). */
data class ConfusionPairCount(
    val expectedKey: String,
    val producedKey: String,
    val cardType: CardType,
    val count: Int
)
