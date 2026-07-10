package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.Index

/** Persisted normalized surface-form index, avoiding full morphology regeneration
 * whenever the process-level reader cache is cold. */
@Entity(tableName = "note_forms", primaryKeys = ["surface"], indices = [Index("noteId")])
data class NoteForm(val surface: String, val noteId: Long)
