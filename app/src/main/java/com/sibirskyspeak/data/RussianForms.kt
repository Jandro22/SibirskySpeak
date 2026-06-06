package com.sibirskyspeak.data

import java.util.Locale

/**
 * Lightweight Russian form derivation used by the reader (to recognise
 * inflected tokens) and the aspect drill (to present finite past-tense
 * choices). This is deliberately rule-based and partial: it covers the
 * regular past tense and the nominal paradigm forms that already ship in
 * each Note's declension table. It never invents irregular stems — callers
 * fall back to the citation form when a verb is irregular.
 */
object RussianForms {

    private val ruLocale = Locale("ru")

    fun normalize(value: String): String =
        value.trim().lowercase(ruLocale).replace("́", "").replace('ё', 'е')

    /** Masculine past tense, e.g. писать -> писал, развернуть -> развернул. */
    fun pastMasculine(infinitive: String): String? {
        val w = normalize(infinitive)
        return when {
            w.endsWith("ться") -> w.dropLast(4) + "лся"
            w.endsWith("ть") -> w.dropLast(2) + "л"
            else -> null // -ти / -чь and other irregular stems: don't guess
        }
    }

    /** Regular past-tense forms (m/f/n/pl) for reader matching. Empty if irregular. */
    fun pastForms(infinitive: String): List<String> {
        val w = normalize(infinitive)
        val refl = w.endsWith("ться")
        val stem = when {
            refl -> w.dropLast(4)
            w.endsWith("ть") -> w.dropLast(2)
            else -> return emptyList()
        }
        val suffixes = if (refl) listOf("лся", "лась", "лось", "лись") else listOf("л", "ла", "ло", "ли")
        return suffixes.map { stem + it }
    }

    /** All recognisable surface forms for a Note, normalized for matching. */
    fun surfaceForms(note: Note): Set<String> {
        val forms = linkedSetOf(normalize(note.russian), normalize(note.lemma))
        note.declensionJson?.let { json ->
            runCatching {
                val obj = org.json.JSONObject(json)
                val table = if (obj.has("cases")) obj.getJSONObject("cases") else obj
                table.keys().forEach { key ->
                    val v = table.optString(key)
                    if (v.isNotBlank()) forms += normalize(v)
                }
            }
        }
        if (note.partOfSpeech.equals("verb", ignoreCase = true)) {
            forms += pastForms(note.lemma)
        }
        forms.removeAll { it.isBlank() }
        return forms
    }
}
