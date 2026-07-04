package com.sibirskyspeak.learning

import com.sibirskyspeak.data.ReviewSource

/** A modality-independent observation. Scheduling consumers must honor [strength]
 * rather than inferring confidence from the screen that emitted the event. */
data class EvidenceEvent(
    val noteId: Long? = null,
    val conceptId: String? = null,
    val facet: LearningFacet,
    val strength: EvidenceStrength,
    val correct: Boolean,
    val source: ReviewSource,
    val at: Long = System.currentTimeMillis()
) {
    init {
        require(noteId != null || conceptId != null) { "Evidence must identify a note or concept" }
        require(source != ReviewSource.READER_LOOKUP) { "Lookups are not retrieval evidence" }
    }
}
