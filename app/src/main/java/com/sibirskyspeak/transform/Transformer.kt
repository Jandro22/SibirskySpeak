package com.sibirskyspeak.transform

import com.sibirskyspeak.morph.MorphologyEngine

/**
 * Deterministic sentence transforms over real sentence-bank carriers (P4.4 L2).
 * Each transform either produces exactly one grammatically forced answer or
 * returns null — "only transformations with a unique deterministic answer are
 * generated" (CLAUDE.md Phase 4). No dependency parser runs on device: these
 * rely only on [MorphologyEngine.analyze] token-by-token, which is guaranteed
 * to resolve every token in a sentence-bank sentence (see
 * tools/preprocess/build_sentence_bank.py, which only admits sentences where
 * every token already has an analysis reading).
 */
object Transformer {
    private val WORD = Regex("[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?")

    data class Transformed(val instruction: String, val original: String, val result: String, val expectedAnswer: String)

    /** Inserts "не" immediately before the sentence's finite verb reading of
     * [verbLemma] — Russian's regular, position-fixed negation. Returns null if the
     * lemma isn't found as a non-imperative VERB/INFN reading in the sentence, or if
     * it's already negated (a second "не" would not be a unique/natural answer). */
    fun negate(sentence: String, verbLemma: String, morph: MorphologyEngine): Transformed? {
        val matches = WORD.findAll(sentence).toList()
        val target = matches.firstOrNull { isFiniteVerbReading(it.value, verbLemma, morph) } ?: return null
        val precedingWord = matches.takeWhile { it.range.first < target.range.first }.lastOrNull()
        if (precedingWord != null && MorphologyEngine.normalize(precedingWord.value) == "не") return null
        val insertion = if (target.range.first == 0) "Не " else "не "
        val verbPiece = if (target.range.first == 0) target.value.replaceFirstChar { it.lowercase() } else target.value
        return Transformed(
            instruction = "Rewrite in the negative.",
            original = sentence,
            result = sentence.substring(0, target.range.first) + insertion + verbPiece + sentence.substring(target.range.last + 1),
            expectedAnswer = insertion.trim() + " " + verbPiece
        )
    }

    private fun isFiniteVerbReading(surface: String, lemma: String, morph: MorphologyEngine): Boolean {
        val readings = morph.analyze(surface).filter { it.lemma == lemma }
        if (readings.isEmpty()) return false
        return readings.any { reading ->
            (reading.pos == "VERB" || reading.pos == "INFN") && "IMP" !in reading.feats
        }
    }
}
