package com.sibirskyspeak.transform

import com.sibirskyspeak.morph.MorphologyEngine
import org.json.JSONObject

/**
 * A single authored, build-time-validated register-ladder transformation pair
 * (Phase G6 §13.6): [source] is the neutral-register sentence shown as the
 * prompt, [answer] is its formal-register rewrite — the deterministic expected
 * answer, exactly like the negation transform's [Transformer.Transformed.
 * expectedAnswer]. Mirrors tools/preprocess/transformations.json's schema
 * 1:1 ({"id","band","fromRegister","toRegister","source","answer"}).
 */
data class RegisterPair(
    val id: String,
    val band: String,
    val fromRegister: String,
    val toRegister: String,
    val source: String,
    val answer: String
)

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

    /**
     * Parses the register-ladder asset (Phase G6 §13.6, schema mirrored by
     * [RegisterPair]). Never throws — a missing/malformed asset (see the
     * shipping-gap note on LearningRepository's bootstrapTransformations
     * provider) just yields an empty list, so the feature is silently inactive
     * rather than crashing.
     */
    fun parseRegisterPairs(json: String): List<RegisterPair> = runCatching {
        val root = JSONObject(json)
        val pairs = root.optJSONArray("pairs") ?: return@runCatching emptyList()
        (0 until pairs.length()).mapNotNull { i ->
            val obj = pairs.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val source = obj.optString("source").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val answer = obj.optString("answer").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RegisterPair(
                id = id,
                band = obj.optString("band", "B2"),
                fromRegister = obj.optString("fromRegister", "neutral"),
                toRegister = obj.optString("toRegister", "formal"),
                source = source,
                answer = answer
            )
        }
    }.getOrDefault(emptyList())

    /** Deterministically picks one pair for a given (day, cardId), rotating
     * across days like [negate]'s sentence rotation so a mature card isn't
     * stuck testing one memorized pair forever. */
    fun pickRegisterPair(pairs: List<RegisterPair>, epochDay: Long, cardId: Long): RegisterPair? {
        if (pairs.isEmpty()) return null
        val offset = Math.floorMod(epochDay + cardId, pairs.size.toLong()).toInt()
        return pairs[offset]
    }
}
