package com.sibirskyspeak.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local full-state backup, kept in a separate app-private file so it survives a
 * destructive database migration or a corrupted Room file. App-private files do
 * NOT survive uninstall/clear-data; durable uninstall protection requires a mirror
 * outside app-private storage. The backup is the safety net that protects a
 * multi-year review history: the app writes a rolling backup while studying, and
 * restores from it automatically whenever it finds an empty database (see
 * [LearningRepository.seedIfEmpty]).
 *
 * Two generations are kept (latest + previous) and writes go through a temp file
 * + rename so a crash mid-write can never leave us with a truncated backup.
 *
 * The durable mirror (public Downloads/SibirskySpeak, via MediaStore) is
 * automatic on Android 10+ and needs no setup: apps can always write their own
 * files into MediaStore.Downloads without any permission prompt or folder
 * picker, unlike a SAF tree grant (some devices — confirmed on a real Pixel 8 —
 * won't let the system picker select internal storage's root or app-created
 * subfolders at all, making that flow a dead end for exactly the phones that
 * need it most). The older SAF-folder option ([setTreeUri]/[mirrorToSaf]) is
 * kept for anyone who deliberately wants a different location (e.g. a
 * cloud-synced folder), but it's no longer required for a durable backup to
 * exist — MediaStore.Downloads is the default, unconditional safety net now.
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

    /** Validated lazy reader for automatic restore; avoids loading multi-year JSONL
     * history into one String before Room can consume it. */
    fun readLines(): Sequence<String>? {
        val source = listOf(latest, previous).firstOrNull { it.exists() && it.length() > 0L && isValidBackupFile(it) } ?: return null
        return sequence {
            source.bufferedReader().use { reader ->
                while (true) yield(reader.readLine() ?: break)
            }
        }
    }

    /** Atomically replace the latest backup, rotating the prior one to [previous]. */
    fun write(content: String) = writeLines(content.lineSequence())

    /** Automatic-backup path that never materializes a second full-state String. */
    fun writeLines(lines: Sequence<String>) {
        if (!dir.exists() && !dir.mkdirs()) error("Could not create backup directory")
        if (latest.exists() && latest.length() > 0L) {
            latest.copyTo(previous, overwrite = true)
        }
        val tmp = File(dir, "full_state.tmp")
        var wroteData = false
        tmp.bufferedWriter().use { writer ->
            lines.forEach { raw ->
                val line = raw.trimEnd()
                if (line.isBlank()) return@forEach
                if (runCatching { JSONObject(line).optBoolean("_preferences", false) }.getOrDefault(false)) return@forEach
                writer.appendLine(line)
                wroteData = true
            }
            if (wroteData) writer.appendLine(preferenceLine())
        }
        if (!wroteData) { tmp.delete(); return }
        if (latest.exists() && !latest.delete()) error("Could not rotate current backup")
        if (!tmp.renameTo(latest)) {
            // Rename can fail across some filesystems; fall back to a copy.
            tmp.copyTo(latest, overwrite = true)
            if (!tmp.delete()) tmp.deleteOnExit()
        }
        check(isValidBackupFile(latest)) { "Backup validation failed after write" }
        prefs.edit()
            .putLong("backup_last_size", latest.length())
            .putLong("backup_last_validated_at", System.currentTimeMillis())
            .commit()
        // A revoked/unavailable mirror target must not make the already-successful
        // local backup look failed and trigger repeated huge exports every session.
        // MediaStore.Downloads is tried first since it needs no setup at all; the
        // SAF folder (if the learner deliberately chose one) mirrors there too, in
        // addition to — not instead of — the automatic Downloads copy.
        val mirroredToDownloads = runCatching { mirrorToDownloads(latest) }.getOrDefault(false)
        val mirroredToSaf = runCatching { mirrorToSaf(latest) }.getOrDefault(false)
        if (mirroredToDownloads || mirroredToSaf) {
            prefs.edit().putLong("backup_last_saf_at", System.currentTimeMillis()).apply()
        }
    }

    /**
     * Copy [source] into the public Downloads/SibirskySpeak folder via MediaStore —
     * no SAF tree grant, no permission prompt, works the moment the app is
     * installed. Android 10+ only (MediaStore.Downloads didn't exist before); older
     * devices fall back to whatever SAF folder the learner configured, if any.
     */
    private fun mirrorToDownloads(source: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = appContext.contentResolver
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "sibirskyspeak-$stamp.jsonl")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SibirskySpeak")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        val wrote = resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
            true
        } ?: false
        if (!wrote) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
        thinDownloads(resolver)
        return true
    }

    /** Same retention policy as [thin]: keep the 14 most recent snapshots, plus one
     * per week beyond that — applied to our own MediaStore.Downloads entries.
     * Only ever called from [mirrorToDownloads], which already gates on
     * `SDK_INT >= Q`; this annotation documents that guard for lint since it
     * can't see across the function boundary. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun thinDownloads(resolver: android.content.ContentResolver) {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'sibirskyspeak-%'"
        val selectionArgs = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/SibirskySpeak/")
        val entries = mutableListOf<Pair<Long, Long>>() // id to dateModified (seconds)
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) entries += cursor.getLong(idCol) to cursor.getLong(dateCol)
            }
        val weeks = mutableSetOf<String>()
        entries.forEachIndexed { index, (id, dateModifiedSeconds) ->
            if (index < 14) return@forEachIndexed
            val date = java.time.Instant.ofEpochSecond(dateModifiedSeconds).atZone(java.time.ZoneOffset.UTC)
            val week = "${date.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR)}-${date.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
            if (!weeks.add(week)) {
                val itemUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                runCatching { resolver.delete(itemUri, null, null) }
            }
        }
    }

    fun setTreeUri(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString("backup_tree_uri", uri.toString()).apply()
    }

    private fun mirrorToSaf(source: File): Boolean {
        val raw = prefs.getString("backup_tree_uri", null)?.takeIf(String::isNotBlank) ?: return false
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(raw)) ?: return false
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
        val target = root.createFile("application/json", "sibirskyspeak-$stamp.jsonl") ?: return false
        appContext.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: return false
        thin(root)
        return true
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

    /**
     * Database rows alone are not a full learner state: pacing, FSRS weights,
     * goals, reminder choices, and achievement state live in SharedPreferences.
     * Transient values containing database row ids are deliberately excluded,
     * because ids can be remapped during an additive restore.
     */
    fun enrichFullState(content: String): String {
        val values = JSONObject()
        prefs.all.toSortedMap().forEach { (key, value) ->
            if (key == "backup_tree_uri" || key == "last_backup_at" ||
                key == "plan_skeleton_card_ids" || key.startsWith("reader_progress_")) return@forEach
            val row = JSONObject()
            when (value) {
                is String -> row.put("type", "string").put("value", value)
                is Boolean -> row.put("type", "boolean").put("value", value)
                is Int -> row.put("type", "int").put("value", value)
                is Long -> row.put("type", "long").put("value", value)
                is Float -> row.put("type", "float").put("value", value.toDouble())
                is Set<*> -> row.put("type", "strings").put("value", JSONArray(value.filterIsInstance<String>().sorted()))
                else -> return@forEach
            }
            values.put(key, row)
        }
        val line = JSONObject().put("_preferences", true).put("values", values).toString()
        val withoutOldMetadata = content.lineSequence()
            .filterNot { raw -> runCatching { JSONObject(raw).optBoolean("_preferences", false) }.getOrDefault(false) }
            .joinToString("\n")
            .trimEnd()
        return withoutOldMetadata + "\n" + line
    }

    private fun preferenceLine(): String = enrichFullState("{}").lineSequence().last()

    fun restoreMetadata(content: String) {
        val payload = content.lineSequence().map(String::trim).filter(String::isNotEmpty)
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.optBoolean("_preferences", false) }
            ?.optJSONObject("values") ?: return
        val editor = prefs.edit()
        payload.keys().forEach { key ->
            val row = payload.optJSONObject(key) ?: return@forEach
            when (row.optString("type")) {
                "string" -> editor.putString(key, row.optString("value"))
                "boolean" -> editor.putBoolean(key, row.optBoolean("value"))
                "int" -> editor.putInt(key, row.optInt("value"))
                "long" -> editor.putLong(key, row.optLong("value"))
                "float" -> editor.putFloat(key, row.optDouble("value").toFloat())
                "strings" -> row.optJSONArray("value")?.let { array ->
                    editor.putStringSet(key, buildSet { repeat(array.length()) { add(array.getString(it)) } })
                }
            }
        }
        editor.commit()
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

    private fun isValidBackupFile(file: File): Boolean {
        var hasNote = false
        var hasRows = false
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                hasRows = true
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return false
                if (json.has("russian") && json.has("lemma")) hasNote = true
            }
        }
        return hasRows && hasNote
    }
}
