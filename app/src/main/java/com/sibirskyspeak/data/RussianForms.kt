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

    /** All recognizable surface forms of a verb (past, stored, and regular present),
     *  for reader word-matching only — NOT for drill generation. */
    fun verbSurfaceForms(note: Note): Set<String> {
        val forms = linkedSetOf<String>()
        verbForms(note).values.forEach { forms += normalize(it) }
        regularPresentForms(normalize(note.lemma)).values.forEach { forms += normalize(it) }
        return forms
    }

    // All gender/number/case endings an adjective can take. We append the full
    // (hard + soft) superset to the stem: a few spellings won't be valid Russian, but
    // they simply never appear in text, while every real inflection IS covered — so the
    // reader recognizes "большая/большим/большую…" from the "большой" headword.
    private val ADJECTIVE_ENDINGS = listOf(
        "ый", "ий", "ой", "ого", "его", "ому", "ему", "ым", "им", "ом", "ем",
        "ая", "яя", "ой", "ей", "ую", "юю", "ое", "ее", "ые", "ие", "ых", "их", "ыми", "ими"
    )

    // All singular+plural case endings across the three declensions. Appended as a
    // superset to the noun stem (and a fleeting-vowel variant), so real inflections
    // resolve in the reader even when the deck's stored table is partial or wrong
    // (e.g. fleeting-vowel nouns like "рынок" → "рынке", not the generated "рыноке").
    private val NOUN_ENDINGS = listOf(
        "", "а", "я", "у", "ю", "ом", "ем", "е", "и", "ы", "ой", "ей",
        "ь", "ью", "ам", "ям", "ами", "ями", "ах", "ях", "ов", "ев", "ей"
    )

    /** Recognizable surface forms of a noun (superset declension), reader-only. */
    fun nounSurfaceForms(note: Note): Set<String> {
        val raw = note.lemma.trim().lowercase(ruLocale)
        if (raw.length < 2) return emptySet()
        val last = raw.last()
        val stems = linkedSetOf(
            if (last in "аяоейь") raw.dropLast(1) else raw
        )
        // Fleeting vowel: drop a penultimate о/е between consonants (рынок→рынк, отец→отц).
        if (last !in "аяоеийуыюёь" && raw.length >= 3 && raw[raw.length - 2] in "ое") {
            stems += raw.dropLast(2) + last
        }
        val forms = linkedSetOf(normalize(raw))
        for (stem in stems) for (e in NOUN_ENDINGS) forms += normalize(stem + e)
        forms.removeAll { it.isBlank() }
        return forms
    }

    /** Recognizable surface forms of an adjective (full declension + comparative),
     *  for reader word-matching only. */
    fun adjectiveSurfaceForms(note: Note): Set<String> {
        // Strip the masculine-nominative ending on the PRECOMPOSED lemma (before
        // normalize decomposes й), then build forms stem + ending and normalize each.
        val raw = note.lemma.trim().lowercase(ruLocale)
        val stem = when {
            raw.length >= 4 && (raw.endsWith("ый") || raw.endsWith("ий") || raw.endsWith("ой")) -> raw.dropLast(2)
            else -> return emptySet()
        }
        val forms = linkedSetOf(normalize(raw))
        ADJECTIVE_ENDINGS.forEach { forms += normalize(stem + it) }
        forms += normalize(stem + "ее") // regular comparative (интересный → интереснее)
        forms += normalize(stem + "ей")
        IRREGULAR_COMPARATIVES[normalize(raw)]?.let { forms += it }
        forms.removeAll { it.isBlank() }
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

    // Closed-class function words (pronouns, possessives, determiners) decline
    // irregularly and rarely ship a declensionJson, yet their inflected forms are
    // everywhere in real text. Without this, a reader word like "моя" never matches the
    // "мой" note and shows as unknown. Forms are normalized on use, so ё/stress are fine.
    private val CLOSED_CLASS_FORMS: Map<String, List<String>> = mapOf(
        "я" to listOf("я", "меня", "мне", "мной", "мною"),
        "ты" to listOf("ты", "тебя", "тебе", "тобой", "тобою"),
        "он" to listOf("он", "его", "него", "ему", "нему", "им", "ним", "нём"),
        "оно" to listOf("оно", "его", "него", "ему", "нему", "им", "ним", "нём"),
        "она" to listOf("она", "её", "неё", "ей", "ней", "ею", "нею"),
        "мы" to listOf("мы", "нас", "нам", "нами"),
        "вы" to listOf("вы", "вас", "вам", "вами"),
        "они" to listOf("они", "их", "них", "им", "ним", "ими", "ними"),
        "мой" to listOf("мой", "моя", "моё", "мои", "моего", "моей", "моих", "моему", "моим", "моём", "мою", "моими"),
        "твой" to listOf("твой", "твоя", "твоё", "твои", "твоего", "твоей", "твоих", "твоему", "твоим", "твоём", "твою", "твоими"),
        "свой" to listOf("свой", "своя", "своё", "свои", "своего", "своей", "своих", "своему", "своим", "своём", "свою", "своими"),
        "наш" to listOf("наш", "наша", "наше", "наши", "нашего", "нашей", "наших", "нашему", "нашим", "нашем", "нашу", "нашими"),
        "ваш" to listOf("ваш", "ваша", "ваше", "ваши", "вашего", "вашей", "ваших", "вашему", "вашим", "вашем", "вашу", "вашими"),
        "этот" to listOf("этот", "эта", "это", "эти", "этого", "этой", "этих", "этому", "этим", "этом", "эту", "этими"),
        "тот" to listOf("тот", "та", "то", "те", "того", "той", "тех", "тому", "тем", "том", "ту", "теми"),
        "весь" to listOf("весь", "вся", "всё", "все", "всего", "всей", "всех", "всему", "всем", "всём", "всю", "всеми"),
        "сам" to listOf("сам", "сама", "само", "сами", "самого", "самой", "самих", "самому", "самим", "самом", "саму", "самими"),
        "кто" to listOf("кто", "кого", "кому", "кем", "ком"),
        "что" to listOf("что", "чего", "чему", "чем", "чём"),
        // High-frequency irregular/suppletive words whose forms can't be derived.
        "быть" to listOf("быть", "есть", "буду", "будешь", "будет", "будем", "будете", "будут", "был", "была", "было", "были", "будь", "будьте", "будучи"),
        "мочь" to listOf("мочь", "могу", "можешь", "может", "можем", "можете", "могут", "мог", "могла", "могло", "могли"),
        "хотеть" to listOf("хотеть", "хочу", "хочешь", "хочет", "хотим", "хотите", "хотят", "хотел", "хотела", "хотело", "хотели"),
        "идти" to listOf("идти", "иду", "идёшь", "идёт", "идём", "идёте", "идут", "шёл", "шла", "шло", "шли", "иди", "идите"),
        "который" to listOf("который", "которого", "которому", "которым", "котором", "которая", "которой", "которую", "которое", "которые", "которых", "которыми"),
        "человек" to listOf("человек", "человека", "человеку", "человеком", "человеке", "люди", "людей", "людям", "людьми", "людях"),
        "себя" to listOf("себя", "себе", "собой", "собою"),
        "самый" to listOf("самый", "самого", "самому", "самым", "самом", "самая", "самой", "самую", "самое", "самые", "самых", "самыми"),
        // Irregular neuter -мя noun and a few function words / numerals.
        "время" to listOf("время", "времени", "временем", "времена", "времён", "временам", "временами", "временах"),
        "о" to listOf("о", "об", "обо"),
        "один" to listOf("один", "одна", "одно", "одни", "одного", "одной", "одному", "одним", "одном", "одну", "одними", "одних"),
        "два" to listOf("два", "две", "двух", "двум", "двумя"),
        "восемьдесят" to listOf("восемьдесят", "восьмидесяти", "восьмьюдесятью")
    ).mapKeys { normalize(it.key) } // keys must match normalize() output (NFD decomposes й/ё)

    // Irregular comparatives that aren't stem + ее.
    private val IRREGULAR_COMPARATIVES: Map<String, String> = mapOf(
        "большой" to "больше", "хороший" to "лучше", "плохой" to "хуже",
        "маленький" to "меньше", "высокий" to "выше", "низкий" to "ниже",
        "старый" to "старше", "молодой" to "младше", "далёкий" to "дальше",
        "близкий" to "ближе", "дорогой" to "дороже", "дешёвый" to "дешевле",
        "широкий" to "шире", "узкий" to "уже", "долгий" to "дольше", "ранний" to "раньше"
    ).mapKeys { normalize(it.key) }.mapValues { normalize(it.value) }

    fun surfaceForms(note: Note): Set<String> {
        val forms = linkedSetOf(normalize(note.russian), normalize(note.lemma))
        CLOSED_CLASS_FORMS[normalize(note.lemma)]?.forEach { forms += normalize(it) }
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
            forms += verbSurfaceForms(note)
        }
        if (note.partOfSpeech.equals("adj", ignoreCase = true) ||
            note.partOfSpeech.equals("adjective", ignoreCase = true)
        ) {
            forms += adjectiveSurfaceForms(note)
        }
        if (note.partOfSpeech.equals("noun", ignoreCase = true)) {
            forms += nounSurfaceForms(note)
        }
        forms.removeAll { it.isBlank() }
        return forms
    }
}
