package com.sibirskyspeak.generation

import com.sibirskyspeak.morph.MorphologyEngine

/**
 * Assigned unit-capstone task: "write a couple of sentences about a topic using
 * target words." Grading is tiered and deliberately partial —
 * "grade what you can prove, stay silent on the rest" — never penalizing
 * unparseable extras the learner adds beyond what's being checked.
 */
data class MicroCompositionResult(
    /** Which of the requested due lemmas were actually used (any inflected form). */
    val foundLemmas: Set<String>,
    val missingLemmas: Set<String>,
    /** Adjacent adjective-noun pairs found in the answer that don't agree — only
     * reported when both readings are unambiguous; never a hard failure. */
    val agreementIssues: List<String>
) {
    /** Verified use of at least two of the requested due words (or all of them,
     * if fewer than two were asked for) is the bar for STRONG production evidence
     * (see CardPedagogy/CLAUDE.md principle 6). */
    val passed: Boolean get() = foundLemmas.size >= (foundLemmas.size + missingLemmas.size).coerceAtMost(2)
}

object MicroCompositionGrader {
    private val WORD = Regex("[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?")

    fun grade(answer: String, dueLemmas: List<String>, morph: MorphologyEngine): MicroCompositionResult {
        val tokens = WORD.findAll(answer).map { it.value }.toList()
        val usedLemmas = tokens.flatMapTo(mutableSetOf()) { token -> morph.analyze(token).map { it.lemma } }
        val normalizedDue = dueLemmas.map { MorphologyEngine.normalize(it) }
        val found = normalizedDue.filter { it in usedLemmas }.toSet()
        val missing = (normalizedDue - found).toSet()

        val issues = mutableListOf<String>()
        for (i in 0 until (tokens.size - 1).coerceAtLeast(0)) {
            val first = morph.analyze(tokens[i])
            val second = morph.analyze(tokens[i + 1])
            val isAdjBeforeNoun = first.any { it.pos in setOf("ADJF", "ADJS", "PRTF", "PRTS") } &&
                second.any { it.pos in setOf("NOUN", "NPRO") }
            if (isAdjBeforeNoun && !morph.agreementOk(tokens[i], tokens[i + 1])) {
                issues += "${tokens[i]} ${tokens[i + 1]}"
            }
        }
        return MicroCompositionResult(found, missing, issues)
    }
}
