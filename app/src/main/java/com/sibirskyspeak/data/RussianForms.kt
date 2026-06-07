package com.sibirskyspeak.data

import java.text.Normalizer
import java.util.Locale

/**
 * Lightweight Russian form derivation used by the reader and grammar drills.
 * It only derives regular forms; irregular verbs fall back to stored data.
 */
object RussianForms {
    private val ruLocale = Locale("ru")
    private val presentEndings = mapOf(
        "A" to listOf("\u044e", "\u0435\u0448\u044c", "\u0435\u0442", "\u0435\u043c", "\u0435\u0442\u0435", "\u044e\u0442"),
        "I" to listOf("\u044e", "\u0438\u0448\u044c", "\u0438\u0442", "\u0438\u043c", "\u0438\u0442\u0435", "\u044f\u0442")
    )
    private val presentLabels = listOf("1SG", "2SG", "3SG", "1PL", "2PL", "3PL")

    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.trim().lowercase(ruLocale).replace('\u0451', '\u0435'), Normalizer.Form.NFD)
        return decomposed
            .replace("\u0301", "")
            .replace("\u0308", "")
    }

    fun pastMasculine(infinitive: String): String? = verbForm(infinitive, "PAST_M")

    fun pastForms(infinitive: String): List<String> {
        val forms = verbForms(infinitive)
        return listOf("PAST_M", "PAST_F", "PAST_N", "PAST_PL").mapNotNull(forms::get)
    }

    fun verbForm(infinitive: String, key: String): String? = verbForms(infinitive)[key]

    fun verbForm(note: Note, key: String): String? = verbForms(note)[key]

    fun verbForms(note: Note): Map<String, String> {
        val forms = linkedMapOf<String, String>()
        regularPastForms(normalize(note.lemma)).forEach { (key, form) -> forms[key] = form }
        storedVerbForms(note).forEach { (key, form) -> forms[key] = normalize(form) }
        return forms
    }

    fun verbForms(infinitive: String): Map<String, String> {
        val word = normalize(infinitive)
        val forms = linkedMapOf<String, String>()
        regularPastForms(word).forEach { (key, form) -> forms[key] = form }
        return forms
    }

    private fun storedVerbForms(note: Note): Map<String, String> {
        val raw = note.declensionJson ?: return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            val forms = if (obj.has("verbForms")) obj.getJSONObject("verbForms") else obj
            forms.keys().asSequence()
                .filter { key -> key.startsWith("PRES_") || key.startsWith("FUT_") || key.startsWith("IMP_") }
                .mapNotNull { key -> forms.optString(key).takeIf { it.isNotBlank() }?.let { key to it } }
                .toMap()
        }.getOrElse { emptyMap() }
    }

    private fun regularPastForms(infinitive: String): Map<String, String> {
        val reflexive = infinitive.endsWith("\u0442\u044c\u0441\u044f")
        val stem = when {
            reflexive -> infinitive.dropLast(4)
            infinitive.endsWith("\u0442\u044c") -> infinitive.dropLast(2)
            else -> return emptyMap()
        }
        val suffixes = if (reflexive) {
            listOf("\u043b\u0441\u044f", "\u043b\u0430\u0441\u044c", "\u043b\u043e\u0441\u044c", "\u043b\u0438\u0441\u044c")
        } else {
            listOf("\u043b", "\u043b\u0430", "\u043b\u043e", "\u043b\u0438")
        }
        return listOf("PAST_M", "PAST_F", "PAST_N", "PAST_PL").zip(suffixes)
            .associate { (key, suffix) -> key to stem + suffix }
    }

    private fun regularPresentForms(infinitive: String): Map<String, String> {
        val reflexive = infinitive.endsWith("\u0442\u044c\u0441\u044f")
        val bare = if (reflexive) infinitive.dropLast(2) else infinitive
        val conjugation = when {
            bare.endsWith("\u043e\u0432\u0430\u0442\u044c") || bare.endsWith("\u0435\u0432\u0430\u0442\u044c") -> "A"
            bare.endsWith("\u0430\u0442\u044c") || bare.endsWith("\u044f\u0442\u044c") || bare.endsWith("\u0435\u0442\u044c") -> "A"
            bare.endsWith("\u0438\u0442\u044c") -> "I"
            else -> return emptyMap()
        }
        val stem = when {
            bare.endsWith("\u043e\u0432\u0430\u0442\u044c") -> bare.dropLast(5) + "\u0443"
            bare.endsWith("\u0435\u0432\u0430\u0442\u044c") -> bare.dropLast(5) + "\u044e"
            bare.endsWith("\u0442\u044c") -> bare.dropLast(2)
            else -> return emptyMap()
        }
        return presentLabels.zip(presentEndings.getValue(conjugation))
            .associate { (label, ending) ->
                val form = stem + ending
                val reflexiveEnding = if (label in setOf("1SG", "2SG")) "\u0441\u044c" else "\u0441\u044f"
                "PRES_$label" to if (reflexive) form + reflexiveEnding else form
            }
    }

    fun surfaceForms(note: Note): Set<String> {
        val forms = linkedSetOf(normalize(note.russian), normalize(note.lemma))
        note.declensionJson?.let { json ->
            runCatching {
                val obj = org.json.JSONObject(json)
                val table = if (obj.has("cases")) obj.getJSONObject("cases") else obj
                table.keys().forEach { key ->
                    val value = table.optString(key)
                    if (value.isNotBlank()) forms += normalize(value)
                }
            }
        }
        if (note.partOfSpeech.equals("verb", ignoreCase = true)) {
            forms += verbForms(note).values
        }
        forms.removeAll { it.isBlank() }
        return forms
    }
}
