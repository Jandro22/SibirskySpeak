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
    return value
        .let { if (ignoreStress) it.replace("\u0308", "") else it }
        .replace(if (ignoreStress) Regex("""[^\p{L}\p{N}\s-]""") else Regex("""[^\p{L}\p{N}\u0301\u0308\s-]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
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

fun isEnglishAnswerCorrect(expected: String, actual: String): Boolean {
    val normalizedActual = normalizeEnglish(actual)
    if (normalizedActual.isBlank()) return false
    val acceptable = expected
        .split(",", ";", "/", " or ")
        .map(::normalizeEnglish)
        .filter { it.isNotBlank() }
    return normalizedActual == normalizeEnglish(expected) || normalizedActual in acceptable
}

private fun normalizeEnglish(input: String): String =
    input
        .trim()
        .lowercase(Locale.ENGLISH)
        .replace(Regex("""[^\p{L}\p{N}\s]"""), "")
        .replace(Regex("""\s+"""), " ")

private fun russianAnswerAlternatives(expected: String): List<String> =
    expected
        .split("/", ";", ",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
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
