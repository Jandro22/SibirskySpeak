package com.sibirskyspeak.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("sibirsky_settings", Context.MODE_PRIVATE)
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
        mirrorToSaf(content)
    }

    fun setTreeUri(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString("backup_tree_uri", uri.toString()).apply()
    }

    private fun mirrorToSaf(content: String) {
        val raw = prefs.getString("backup_tree_uri", null)?.takeIf(String::isNotBlank) ?: return
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(raw)) ?: return
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
        val target = root.createFile("application/json", "sibirskyspeak-$stamp.jsonl") ?: return
        appContext.contentResolver.openOutputStream(target.uri, "w")?.use { it.write(content.toByteArray()) }
        thin(root)
    }

    private fun thin(root: DocumentFile) {
        val files = root.listFiles().filter { it.name?.startsWith("sibirskyspeak-") == true }
            .sortedByDescending(DocumentFile::lastModified)
        val weeks = mutableSetOf<String>()
        files.forEachIndexed { index, file ->
            if (index < 14) return@forEachIndexed
            val date = java.time.Instant.ofEpochMilli(file.lastModified()).atZone(java.time.ZoneOffset.UTC)
            val week = "${date.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR)}-${date.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
            if (!weeks.add(week)) file.delete()
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
