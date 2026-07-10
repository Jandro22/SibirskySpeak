package com.sibirskyspeak.data

import org.json.JSONObject

/** Pure, non-mutating restore inspection kept outside the repository transaction
 * coordinator so UI preview and tests cannot accidentally write learner state. */
object ImportPreviewer {
    fun preview(jsonLines: String): ImportPreview {
        var notes = 0; var cards = 0; var reviews = 0; var readers = 0; var history = 0; var settings = false
        val errors = mutableListOf<String>()
        jsonLines.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val json = runCatching { JSONObject(line) }.getOrElse {
                if (errors.size < 5) errors += "Line ${index + 1}: invalid JSON"
                return@forEachIndexed
            }
            when {
                json.optBoolean("_preferences", false) -> settings = true
                json.optBoolean("_readerText", false) -> readers++
                json.optBoolean("_history", false) || json.optBoolean("_telemetry", false) || json.optBoolean("_model", false) -> history++
                json.optBoolean("_confusablePair", false) -> Unit
                json.has("russian") && json.has("lemma") && json.has("translation") -> {
                    notes++
                    json.optJSONArray("_cards")?.let { array ->
                        cards += array.length()
                        repeat(array.length()) { cardIndex -> reviews += array.optJSONObject(cardIndex)?.optJSONArray("_reviews")?.length() ?: 0 }
                    }
                }
                else -> if (errors.size < 5) errors += "Line ${index + 1}: unknown row type"
            }
        }
        if (notes == 0) errors += "No learner notes found"
        return ImportPreview(errors.isEmpty(), notes, cards, reviews, readers, history, settings, errors)
    }
}
