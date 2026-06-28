package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Privacy-local product/learning telemetry. Never stores the learner's raw answer;
 * it records timing, state, grading, and queue context for later on-device analysis. */
@Entity(
    tableName = "telemetry_events",
    indices = [Index("timestamp"), Index("eventType"), Index("sessionId"), Index("cardId")]
)
data class TelemetryEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String,
    val sessionId: String? = null,
    val cardId: Long? = null,
    val noteId: Long? = null,
    val cardType: String? = null,
    val queue: String? = null,
    val answerMode: String? = null,
    val rating: String? = null,
    val answerMatch: String? = null,
    val responseMs: Long? = null,
    val wasRevealed: Boolean = false,
    val typedLength: Int = 0,
    val queueReason: String? = null,
    val sessionRemaining: Int? = null,
    val dueCount: Int? = null,
    val newCardLimit: Int? = null,
    val metadataJson: String = "{}"
)
