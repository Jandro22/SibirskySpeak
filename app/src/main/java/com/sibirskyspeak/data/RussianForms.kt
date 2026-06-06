package com.sibirskyspeak.data

import java.text.Normalizer
import java.util.Locale

/**
 * Lightweight Russian form derivation used by the reader and aspect drills.
 * It only derives regular forms; irregular verbs fall back to stored data.
 */
object RussianForms {
    private val ruLocale = Locale("ru")

    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.trim().lowercase(ruLocale).replace('ё', 'е'), Normalizer.Form.NFD)
        return decomposed
            .replace("\u0301", "")
            .replace("\u0308", "")
    }

    fun pastMasculine(infinitive: String): String? {
        val word = normalize(infinitive)
        return when {
            word.endsWith("ться") -> word.dropLast(4) + "лся"
            word.endsWith("ть") -> word.dropLast(2) + "л"
            else -> null
        }
    }

    fun pastForms(infinitive: String): List<String> {
        val word = normalize(infinitive)
        val reflexive = word.endsWith("ться")
        val stem = when {
            reflexive -> word.dropLast(4)
            word.endsWith("ть") -> word.dropLast(2)
            else -> return emptyList()
        }
        val suffixes = if (reflexive) {
            listOf("лся", "лась", "лось", "лись")
        } else {
            listOf("л", "ла", "ло", "ли")
        }
        return suffixes.map { stem + it }
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
            forms += pastForms(note.lemma)
        }
        forms.removeAll { it.isBlank() }
        return forms
    }
}
