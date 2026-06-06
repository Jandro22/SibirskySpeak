package com.sibirskyspeak.review

import java.text.Normalizer
import java.util.Locale

fun normalizeRussian(input: String, ignoreStress: Boolean = true): String {
    var value = input.trim().lowercase(Locale("ru")).replace('ё', 'е')
    value = Normalizer.normalize(value, Normalizer.Form.NFD)
    if (ignoreStress) {
        value = value.replace("\u0301", "")
    }
    return value.replace("\u0308", "")
}

fun isRussianAnswerCorrect(expected: String, actual: String, ignoreStress: Boolean = true): Boolean =
    normalizeRussian(expected, ignoreStress) == normalizeRussian(actual, ignoreStress)
