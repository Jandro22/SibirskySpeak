package com.sibirskyspeak.data

import org.json.JSONObject

/** Reads and caches the small bundled curriculum manifest independently of deck work. */
class CurriculumManifestService(
    private val loader: suspend () -> String?
) {
    @Volatile private var cached: List<ContentProvenance>? = null

    suspend fun provenance(): List<ContentProvenance> {
        cached?.let { return it }
        val fallback = listOf(
            ContentProvenance("tatoeba", "Example sentences from Tatoeba via OPUS", "CC-BY 2.0 FR"),
            ContentProvenance("wiktionary", "Lexical verification from Wiktionary via Kaikki", "CC BY-SA 4.0"),
            ContentProvenance("graded-curriculum", "SibirskySpeak graded curriculum and lesson authoring", "Project content")
        )
        val parsed = runCatching {
            val rows = JSONObject(loader().orEmpty()).optJSONObject("provenance")?.optJSONArray("sources")
                ?: return@runCatching fallback
            buildList {
                repeat(rows.length()) { index ->
                    val row = rows.optJSONObject(index) ?: return@repeat
                    val id = row.optString("id").trim()
                    val attribution = row.optString("attribution").trim()
                    val license = row.optString("license").trim()
                    if (id.isNotEmpty() && attribution.isNotEmpty() && license.isNotEmpty()) {
                        add(ContentProvenance(id, attribution, license))
                    }
                }
            }.ifEmpty { fallback }
        }.getOrDefault(fallback)
        cached = parsed
        return parsed
    }
}
