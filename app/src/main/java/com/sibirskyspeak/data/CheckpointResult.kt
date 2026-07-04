package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One answer from the monthly checkpoint (P6.4) — the independent, unbiased
 * assessment that never writes FSRS state. All ordinary evidence comes from
 * scheduler-chosen moments, which biases everything optimistic; this is the
 * only measurement of whether "known" is real, and the outcome metric for
 * self-experiments (Phase 7).
 */
@Entity(tableName = "checkpoint_results", indices = [Index("at")])
data class CheckpointResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    /** Stable identifier for the assessed item (noteId or frameId, as a string). */
    val itemKey: String,
    /** "graduated_recall" | "novel_frame" | "text_comprehension" */
    val kind: String,
    /** The scheduler's/model's predicted probability of success, or null when
     * there's no prior FSRS estimate to compare against (novel/unseen items). */
    val predictedP: Double?,
    val correct: Boolean
)

/** One bucket of the calibration curve: predicted-vs-observed retrievability. */
data class CalibrationBucket(val predictedBucket: Double, val observedAccuracy: Double, val count: Int)
