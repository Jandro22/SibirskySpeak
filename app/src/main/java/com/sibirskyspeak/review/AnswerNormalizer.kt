package com.sibirskyspeak.review

import java.text.Normalizer
import java.util.Locale

enum class AnswerMatch {
    EXACT,
    CLOSE,
    WRONG
}

data class AnswerEvaluation(
    val match: AnswerMatch,
    val expected: String,
    val message: String? = null
) {
    val accepted: Boolean get() = match != AnswerMatch.WRONG
}

fun normalizeRussian(input: String, ignoreStress: Boolean = true): String {
    var value = input.trim().lowercase(Locale("ru"))
    if (ignoreStress) value = value.replace('ё', 'е')
    value = Normalizer.normalize(value, Normalizer.Form.NFD)
    if (ignoreStress) {
        value = value.replace("\u0301", "")
    }
    val cleaned = value
        .let { if (ignoreStress) it.replace("\u0308", "") else it }
        // Preserve U+0306 (breve): NFD represents й as и + breve. Dropping it
        // incorrectly makes й and и grade as the same Russian letter.
        .replace(if (ignoreStress) Regex("""[^\p{L}\p{N}\u0306\s-]""") else Regex("""[^\p{L}\p{N}\u0301\u0306\u0308\s-]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return Normalizer.normalize(cleaned, Normalizer.Form.NFC)
}

fun isRussianAnswerCorrect(expected: String, actual: String, ignoreStress: Boolean = true): Boolean =
    evaluateRussianAnswer(expected, actual, ignoreStress).accepted

fun evaluateRussianAnswer(expected: String, actual: String, ignoreStress: Boolean = true): AnswerEvaluation {
    val normalizedActual = normalizeRussian(actual, ignoreStress)
    if (normalizedActual.isBlank()) {
        return AnswerEvaluation(AnswerMatch.WRONG, expected)
    }

    val acceptable = russianAnswerAlternatives(expected)
    val normalizedExpected = acceptable.map { it to normalizeRussian(it, ignoreStress) }
        .filter { it.second.isNotBlank() }
    val exact = normalizedExpected.firstOrNull { it.second == normalizedActual }
    if (exact != null) {
        return AnswerEvaluation(AnswerMatch.EXACT, exact.first)
    }
    if (!ignoreStress) {
        val stressOnlyMiss = normalizedExpected.firstOrNull { (_, candidate) ->
            normalizeRussian(candidate, ignoreStress = true) == normalizeRussian(normalizedActual, ignoreStress = true)
        }
        if (stressOnlyMiss != null) {
            return AnswerEvaluation(
                match = AnswerMatch.WRONG,
                expected = stressOnlyMiss.first,
                message = "Right letters, but the stress differs. Expected stress: ${stressOnlyMiss.first}"
            )
        }
        return AnswerEvaluation(AnswerMatch.WRONG, expected)
    }

    val closest = normalizedExpected
        .map { (display, normalized) -> display to levenshteinDistance(normalized, normalizedActual) }
        .minByOrNull { it.second }
    if (closest != null && closest.second <= allowedTypoDistance(normalizedActual, normalizeRussian(closest.first, ignoreStress))) {
        return AnswerEvaluation(
            match = AnswerMatch.CLOSE,
            expected = closest.first,
            message = "Close enough. Spelling: ${closest.first}"
        )
    }

    return AnswerEvaluation(AnswerMatch.WRONG, expected)
}

fun isEnglishAnswerCorrect(expected: String, actual: String): Boolean =
    evaluateEnglishAnswer(expected, actual).accepted

fun evaluateEnglishAnswer(expected: String, actual: String): AnswerEvaluation {
    if (normalizeEnglish(actual).isBlank()) return AnswerEvaluation(AnswerMatch.WRONG, expected)
    val actualVariants = englishVariants(actual)
    val acceptable = englishAnswerAlternatives(expected)
        .flatMap { englishVariants(it) }
        .toSet()
    if (actualVariants.any { it in acceptable }) return AnswerEvaluation(AnswerMatch.EXACT, expected)
    // Typo tolerance (parity with Russian grading): accept a near-miss within a small,
    // length-scaled edit budget so "goverment"/"recieve" aren't marked wrong on a card
    // you actually knew. Short words still require an exact match (budget 0).
    val closest = actualVariants
        .flatMap { typed -> acceptable.map { target -> typed to target } }
        .map { (typed, target) -> EnglishTypoCandidate(typed, target, levenshteinDistance(typed, target)) }
        .minByOrNull { it.distance }
    if (closest != null && closest.distance <= allowedTypoDistance(closest.typed, closest.target)) {
        return AnswerEvaluation(
            match = AnswerMatch.CLOSE,
            expected = expected,
            message = "Close enough. Spelling: ${closest.target}"
        )
    }
    return AnswerEvaluation(AnswerMatch.WRONG, expected)
}

private data class EnglishTypoCandidate(val typed: String, val target: String, val distance: Int)

/**
 * Normalized acceptable variants of an English gloss so trivially-correct answers
 * aren't marked wrong: as written, with any "(parenthetical)" hint removed, and with a
 * leading "to " infinitive marker dropped ("to go" ≡ "go"). Operates on raw text
 * (parentheses are stripped by normalization) and normalizes each variant.
 */
private fun englishVariants(raw: String): Set<String> {
    val out = linkedSetOf<String>()
    for (form in listOf(raw, raw.replace(Regex("""\([^)]*\)"""), " "))) {
        val n = normalizeEnglish(form)
        if (n.isNotBlank()) {
            out += n
            if (n.startsWith("to ")) out += n.removePrefix("to ").trim()
        }
    }
    return out
}

private fun normalizeEnglish(input: String): String =
    input
        .trim()
        .lowercase(Locale.ENGLISH)
        .replace(Regex("""[^\p{L}\p{N}\s]"""), "")
        .replace(Regex("""\s+"""), " ")

private fun englishAnswerAlternatives(expected: String): List<String> =
    (listOf(expected) + expected.split(",", ";", "/", " or "))
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun russianAnswerAlternatives(expected: String): List<String> =
    expected
        .replace(" или ", "/")
        .split("/", ";", ",")
        .flatMap { part ->
            val trimmed = part.trim()
            // Accept both the full form and a parenthetical-stripped form.
            val noParens = trimmed.replace(Regex("""\([^)]*\)"""), " ").replace(Regex("""\s+"""), " ").trim()
            listOf(trimmed, noParens)
        }
        .filter { it.isNotBlank() }
        .distinct()
        .ifEmpty { listOf(expected) }

private fun allowedTypoDistance(actual: String, expected: String): Int {
    val length = maxOf(actual.length, expected.length)
    return when {
        length <= 3 -> 0
        length <= 6 -> 1
        length <= 12 -> 2
        else -> (length * 0.18).toInt().coerceAtLeast(2)
    }
}

private fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(
                previous[j] + 1,
                current[j - 1] + 1,
                substitution
            )
        }
        val next = previous
        previous = current
        current = next
    }
    return previous[b.length]
}
