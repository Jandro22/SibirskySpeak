package com.sibirskyspeak.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Local full-state backup, kept OUTSIDE the Room database file so it survives a
 * destructive schema migration, a corrupted DB, or "clear app data" partial
 * states. This is the safety net that protects a multi-year review history: the
 * app writes a rolling backup while studying, and restores from it automatically
 * whenever it finds an empty database (see [LearningRepository.seedIfEmpty]).
 *
 * Two generations are kept (latest + previous) and writes go through a temp file
 * + rename so a crash mid-write can never leave us with a truncated backup.
 */
class BackupManager(context: Context) {
    private val dir = File(context.filesDir, "backups")
    private val latest = File(dir, "full_state_latest.jsonl")
    private val previous = File(dir, "full_state_previous.jsonl")

    /** Newest non-empty backup, preferring latest then falling back to previous. */
    fun read(): String? = listOf(latest, previous).asSequence()
        .mapNotNull { file ->
            if (!file.exists() || file.length() <= 0L) null
            else runCatching { file.readText() }.getOrNull()
        }
        .firstOrNull(::isValidBackup)

    /** Atomically replace the latest backup, rotating the prior one to [previous]. */
    fun write(content: String) {
        if (content.isBlank()) return
        if (!dir.exists() && !dir.mkdirs()) error("Could not create backup directory")
        if (latest.exists() && latest.length() > 0L) {
            latest.copyTo(previous, overwrite = true)
        }
        val tmp = File(dir, "full_state.tmp")
        tmp.writeText(content)
        if (latest.exists() && !latest.delete()) error("Could not rotate current backup")
        if (!tmp.renameTo(latest)) {
            // Rename can fail across some filesystems; fall back to a copy.
            tmp.copyTo(latest, overwrite = true)
            if (!tmp.delete()) tmp.deleteOnExit()
        }
    }

    private fun isValidBackup(content: String): Boolean {
        var hasNote = false
        var hasRows = false
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            hasRows = true
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return false
            if (json.has("russian") && json.has("lemma")) hasNote = true
        }
        return hasRows && hasNote
    }
}
