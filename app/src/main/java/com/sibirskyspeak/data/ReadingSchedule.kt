package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Persistent lightweight schedule for one connected-text "jumbo card". */
@Entity(
    tableName = "reading_schedules",
    primaryKeys = ["readerTextId"],
    foreignKeys = [ForeignKey(
        entity = ReaderText::class,
        parentColumns = ["id"],
        childColumns = ["readerTextId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("due")]
)
data class ReadingSchedule(
    val readerTextId: Long,
    val due: Long = 0L,
    val intervalDays: Int = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val lastCompleted: Long? = null
)

data class ReadingAssignment(
    val recommendation: ReaderRecommendation,
    val schedule: ReadingSchedule,
    val insertionIndex: Int,
    // P5.3: which modality this rep uses. Built so a future build-time audio pack
    // is a pure asset swap — see ReadingMode.forRep.
    val mode: ReadingMode = ReadingMode.READING
)

/**
 * P5.3 listening mode: a scheduled reading "jumbo card" alternates modality on
 * each rep, sharing the same ReadingSchedule SRS as plain reading. LISTENING reps
 * gate the text reveal behind TTS playback (tap-to-reveal early counts as a
 * listening miss) instead of showing it immediately.
 */
enum class ReadingMode {
    READING,
    LISTENING;

    companion object {
        fun forRep(reps: Int): ReadingMode = if (reps % 2 == 1) LISTENING else READING
    }
}
