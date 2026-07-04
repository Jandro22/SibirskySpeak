package com.sibirskyspeak.morph

import com.sibirskyspeak.data.ContentDao
import java.text.Normalizer
import java.util.LinkedHashMap

data class Analysis(val lemma: String, val pos: String, val feats: Set<String>)

/** Query-only Russian morphology backed by the bundled, build-time generated
 * indexes. No morphology library or model runs on device. */
class MorphologyEngine(private val dao: ContentDao, cacheSize: Int = 2_048) {
    private val maxEntries = cacheSize.coerceAtLeast(32)
    private val inflectionCache = lru<String, String?>()
    private val analysisCache = lru<String, List<Analysis>>()

    fun inflect(lemma: String, feats: String): String? {
        val key = "${normalize(lemma)}|${canonical(feats)}"
        synchronized(inflectionCache) { if (inflectionCache.containsKey(key)) return inflectionCache[key] }
        val result = dao.inflection(normalize(lemma), canonical(feats))?.stressed
        synchronized(inflectionCache) { inflectionCache[key] = result }
        return result
    }

    fun analyze(surface: String): List<Analysis> {
        val key = normalize(surface)
        synchronized(analysisCache) { analysisCache[key]?.let { return it } }
        val result = dao.analyses(key).map { Analysis(it.lemma, it.pos, it.feats.split('+').filter(String::isNotBlank).toSet()) }
        synchronized(analysisCache) { analysisCache[key] = result }
        return result
    }

    /** True when at least one adjective and noun reading shares case, number and,
     * where singular agreement requires it, gender. */
    fun agreementOk(adjective: String, noun: String): Boolean {
        val adjectives = analyze(adjective).filter { it.pos in setOf("ADJF", "ADJS", "PRTF", "PRTS") }
        val nouns = analyze(noun).filter { it.pos in setOf("NOUN", "NPRO") }
        return adjectives.any { adj -> nouns.any { n ->
            shared(adj.feats, n.feats, CASES) && shared(adj.feats, n.feats, NUMBERS) &&
                ("PLUR" in n.feats || shared(adj.feats, n.feats, GENDERS))
        } }
    }

    private fun shared(a: Set<String>, b: Set<String>, domain: Set<String>): Boolean =
        (a intersect domain).isNotEmpty() && (a intersect domain) == (b intersect domain)

    private fun <K, V> lru() = object : LinkedHashMap<K, V>(maxEntries, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > maxEntries
    }

    companion object {
        private val CASES = setOf("NOM", "GEN", "DAT", "ACC", "INS", "PRE")
        private val NUMBERS = setOf("SG", "PL")
        private val GENDERS = setOf("M", "F", "N")
        fun normalize(value: String): String = Normalizer.normalize(value.lowercase().replace('ё', 'е'), Normalizer.Form.NFD)
            .replace("\u0301", "").replace("\u0308", "").trim()
        fun canonical(value: String): String = value.trim().uppercase()
    }
}
