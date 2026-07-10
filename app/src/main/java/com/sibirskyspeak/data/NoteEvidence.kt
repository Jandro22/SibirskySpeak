package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Typed learning evidence for one lexical note. [Note.encounterCount] remains only
 * as a legacy compatibility total; new pedagogy must use these distinct channels. */
@Entity(tableName = "note_evidence")
data class NoteEvidence(
    @PrimaryKey val noteId: Long,
    val directRetrievals: Int = 0,
    val passiveExposures: Int = 0,
    val completedReadings: Int = 0,
    val lookups: Int = 0,
    val placementPriors: Int = 0,
    val lastDirectAt: Long? = null,
    val lastPassiveAt: Long? = null,
    val lastLookupAt: Long? = null
)
