package com.sibirskyspeak.data

/**
 * Repairs bilingual example sentences. A large slice of the imported deck stored the
 * Russian example and its English translation jammed into one field
 * (`"Русский текст - English translation"`) with `exampleTranslation` left empty. That
 * leaks the answer into context-recognition cards, breaks function-word meaning
 * disambiguation, and disqualifies the note from CLOZE/dictation generation. This
 * splits the two halves back apart.
 */
object ExampleText {
    // Order matters only for readability; the picker below chooses by position, so a
    // sentence mixing dash styles still splits at the rightmost qualifying boundary.
    private val SEPARATORS = listOf(" — ", " – ", " - ")

    private fun hasCyrillic(s: String): Boolean = s.any { it in 'Ѐ'..'ӿ' }
    private fun hasLatin(s: String): Boolean = s.any { it in 'a'..'z' || it in 'A'..'Z' }

    /**
     * Split `"Русский - English"` into (russian, english), or null when the string is
     * not a bilingual concatenation — a clean Russian sentence, an all-English string,
     * or a Russian sentence that merely uses an em-dash copula ("Гидрофон — это …").
     *
     * Picks the *rightmost* separator whose suffix is purely Latin (no Cyrillic) and
     * whose prefix still contains Cyrillic, so an em-dash inside the Russian half stays
     * with the Russian rather than cutting the sentence early.
     */
    fun splitBilingual(raw: String): Pair<String, String>? {
        val text = raw.trim()
        if (!hasCyrillic(text) || !hasLatin(text)) return null
        var bestPos = -1
        var bestLeft: String? = null
        var bestRight: String? = null
        for (sep in SEPARATORS) {
            var idx = text.indexOf(sep)
            while (idx >= 0) {
                if (idx > bestPos) {
                    val left = text.substring(0, idx).trim()
                    val right = text.substring(idx + sep.length).trim()
                    if (left.isNotEmpty() && right.isNotEmpty() &&
                        hasCyrillic(left) && hasLatin(right) && !hasCyrillic(right)
                    ) {
                        bestPos = idx
                        bestLeft = left
                        bestRight = right
                    }
                }
                idx = text.indexOf(sep, idx + 1)
            }
        }
        return bestLeft?.let { it to bestRight!! }
    }
}

/**
 * Return a copy with any `"Русский - English"` example concatenation split into its
 * sentence/translation pair. Only acts on a pair whose translation is blank and whose
 * sentence actually splits, so it is a no-op on already-clean notes (idempotent, safe
 * to run on every import and on a one-time repair pass).
 */
fun Note.withSplitExamples(): Note {
    fun split(sentence: String?, translation: String?): Pair<String?, String?> {
        if (!translation.isNullOrBlank() || sentence.isNullOrBlank()) return sentence to translation
        val parts = ExampleText.splitBilingual(sentence) ?: return sentence to translation
        return parts.first to parts.second
    }

    val (s1, t1) = split(exampleSentence, exampleTranslation)
    val (s2, t2) = split(exampleSentence2, exampleTranslation2)
    val (s3, t3) = split(exampleSentence3, exampleTranslation3)
    if (s1 == exampleSentence && t1 == exampleTranslation &&
        s2 == exampleSentence2 && t2 == exampleTranslation2 &&
        s3 == exampleSentence3 && t3 == exampleTranslation3
    ) {
        return this
    }
    return copy(
        exampleSentence = s1, exampleTranslation = t1,
        exampleSentence2 = s2, exampleTranslation2 = t2,
        exampleSentence3 = s3, exampleTranslation3 = t3
    )
}
