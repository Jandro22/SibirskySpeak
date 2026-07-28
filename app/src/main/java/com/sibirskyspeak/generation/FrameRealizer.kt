package com.sibirskyspeak.generation

import com.sibirskyspeak.data.ContentFrame
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.morph.MorphologyEngine
import org.json.JSONArray
import kotlin.random.Random

/**
 * A single slot in a curated clause template. Mirrors the schema authored in
 * tools/preprocess/frames.json and resolved there by build_frames.py — both
 * sides must walk the same case/number/tense/person/aspect/agreesWith logic
 * so a frame that passes the build-time gate is guaranteed inflectable here.
 */
data class FrameSlot(
    val role: String,
    val pos: String,
    val case: String? = null,
    val number: String = "SG",
    val tense: String? = null,
    val person: Int? = null,
    val aspect: String? = null,
    val agreesWith: String? = null,
    val feats: String? = null,
    val fixedLemma: String? = null,
    val poolLemmas: Set<String> = emptySet(),
    val target: Boolean = false
)

/** Known-inventory fillers the realizer may draw from, pre-filtered by POS. */
data class FrameInventory(val nouns: List<Note>, val verbs: List<Note>, val adjectives: List<Note>)

data class RealizedFrame(
    val ru: String,
    val en: String,
    val targetSlot: String,
    val targetAnswer: String,
    /** [ru] with the target slot's surface replaced by a blank, for fill-in-the-blank prompts. */
    val ruBlanked: String
)

private data class ChosenLemma(
    val lemma: String,
    val gender: String?,
    val english: String
)

/**
 * Fills a [ContentFrame] template with fresh, known-inventory words via
 * [MorphologyEngine]. Every word choice and every surface form is
 * deterministic for a given (frameId, epochDay, cardId) triple: stable within
 * a day so a card doesn't reshuffle mid-review, novel across days so mature
 * cards stop testing one memorized sentence (see CLAUDE.md's "transfer over
 * item memory" principle).
 */
class FrameRealizer(private val morph: MorphologyEngine) {

    fun realize(frame: ContentFrame, inventory: FrameInventory, epochDay: Long, cardId: Long): RealizedFrame? {
        val slots = parseSlots(frame.slotsJson)
        val seed = frame.id.hashCode().toLong() * 1_000_003L xor (epochDay * 31 + cardId)
        val rng = Random(seed)
        val chosen = HashMap<String, ChosenLemma>()
        val forms = HashMap<String, String>()
        val englishForms = HashMap<String, String>()
        var targetRole: String? = null
        var targetLemma: String? = null

        for (slot in slots.sortedBy { if (it.agreesWith != null) 1 else 0 }) {
            val entry = pickLemma(slot, inventory, rng) ?: return null
            chosen[slot.role] = entry
            val feats = featsFor(slot, chosen) ?: return null
            val surface = morph.inflect(entry.lemma, feats) ?: return null
            forms[slot.role] = surface
            englishForms[slot.role] = englishSurface(slot, entry.english, frame.enFrame) ?: return null
            if (slot.target) { targetRole = slot.role; targetLemma = surface }
        }

        val role = targetRole ?: return null
        val answer = targetLemma ?: return null
        val ru = runCatching { fillTemplate(frame.ruFrame, forms) }.getOrNull() ?: return null
        val en = runCatching { sentenceCase(fillTemplate(frame.enFrame, englishForms)) }.getOrNull() ?: return null
        val blankedForms = HashMap(forms).apply { put(role, "___") }
        val ruBlanked = runCatching { fillTemplate(frame.ruFrame, blankedForms) }.getOrNull() ?: return null
        return RealizedFrame(ru, en, role, answer, ruBlanked)
    }

    private fun pickLemma(slot: FrameSlot, inventory: FrameInventory, rng: Random): ChosenLemma? {
        if (slot.fixedLemma != null) {
            val lemma = MorphologyEngine.normalize(slot.fixedLemma)
            val english = FIXED_ENGLISH[lemma] ?: return null
            return ChosenLemma(lemma, null, english)
        }
        val pool = when (slot.pos) {
            "noun" -> inventory.nouns
            "adj" -> inventory.adjectives
            else -> inventory.verbs
        }
        var candidates = pool
        if (slot.poolLemmas.isNotEmpty()) {
            candidates = candidates.filter { MorphologyEngine.normalize(it.lemma) in slot.poolLemmas }
        }
        if (slot.aspect != null) {
            candidates = candidates.filter { it.aspect == slot.aspect }
        }
        if (candidates.isEmpty()) return null
        val note = candidates[rng.nextInt(candidates.size)]
        return ChosenLemma(MorphologyEngine.normalize(note.lemma), note.gender, note.translation)
    }

    /** English templates must be filled from meanings, never from Russian inflected
     * forms. The frame grammar supplies tense/number, so apply only the small amount
     * of English morphology needed by the authored templates. */
    private fun englishSurface(slot: FrameSlot, rawMeaning: String, template: String): String? {
        var gloss = rawMeaning
            .substringBefore(';')
            .substringBefore(',')
            .substringBefore(" / ")
            .substringBefore(" (")
            .trim()
            .removePrefix("to ")
            .removePrefix("a ")
            .removePrefix("an ")
            .removePrefix("the ")
            .trim()
        if (gloss.isBlank() || CYRILLIC.containsMatchIn(gloss)) return null

        gloss = when (slot.pos) {
            "noun" -> if (needsEnglishPlural(slot, template)) pluralizeEnglish(gloss) else gloss
            "verb" -> inflectEnglishVerb(gloss, slot)
            else -> gloss
        }
        return gloss.takeIf { it.isNotBlank() && !CYRILLIC.containsMatchIn(it) }
    }

    private fun needsEnglishPlural(slot: FrameSlot, template: String): Boolean =
        slot.number == "PL" || Regex("""(?i)(?:three|five|a lot of)\s+\{${Regex.escape(slot.role)}\}""")
            .containsMatchIn(template)

    private fun pluralizeEnglish(phrase: String): String {
        val words = phrase.split(' ', limit = 2)
        val noun = words.first()
        val plural = IRREGULAR_PLURALS[noun.lowercase()] ?: when {
            noun.endsWith("ch", true) || noun.endsWith("sh", true) ||
                noun.endsWith("s", true) || noun.endsWith("x", true) || noun.endsWith("z", true) -> noun + "es"
            noun.length > 1 && noun.endsWith("y", true) && noun[noun.lastIndex - 1].lowercaseChar() !in "aeiou" -> noun.dropLast(1) + "ies"
            else -> noun + "s"
        }
        return if (words.size == 1) plural else "$plural ${words[1]}"
    }

    private fun inflectEnglishVerb(phrase: String, slot: FrameSlot): String {
        val words = phrase.split(' ', limit = 2)
        val verb = words.first().lowercase()
        val inflected = when {
            slot.tense == "PAST" -> IRREGULAR_PAST[verb] ?: when {
                verb.endsWith("e") -> verb + "d"
                verb.endsWith("y") && verb.length > 1 && verb[verb.lastIndex - 1] !in "aeiou" -> verb.dropLast(1) + "ied"
                else -> verb + "ed"
            }
            slot.tense == "PRES" && slot.person == 3 && slot.number == "SG" ->
                IRREGULAR_THIRD_PERSON[verb] ?: when {
                    verb.endsWith("ch") || verb.endsWith("sh") || verb.endsWith("s") ||
                        verb.endsWith("x") || verb.endsWith("z") || verb.endsWith("o") -> verb + "es"
                    verb.endsWith("y") && verb.length > 1 && verb[verb.lastIndex - 1] !in "aeiou" -> verb.dropLast(1) + "ies"
                    else -> verb + "s"
                }
            else -> verb
        }
        return if (words.size == 1) inflected else "$inflected ${words[1]}"
    }

    private fun sentenceCase(value: String): String =
        value.replace(Regex("""\ba ([aeiouAEIOU])"""), "an $1")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun featsFor(slot: FrameSlot, chosen: Map<String, ChosenLemma>): String? {
        return when (slot.pos) {
            "noun" -> slot.case?.let { "${it}_${slot.number}" }
            "adj" -> {
                val case = slot.case ?: return null
                val target = slot.agreesWith?.let { chosen[it] } ?: return null
                adjFeats(case, target.gender, slot.number)
            }
            else -> verbFeats(slot, slot.agreesWith?.let { chosen[it]?.gender })
        }
    }

    private fun adjFeats(case: String, gender: String?, number: String): String? = when {
        number == "PL" -> "PL_$case"
        gender == "M" -> "${case}_SG"
        gender == "F" -> "FEM_$case"
        gender == "N" -> "NEUT_$case"
        else -> null
    }

    private fun verbFeats(slot: FrameSlot, gender: String?): String? {
        slot.feats?.let { return it }
        return when (slot.tense) {
            "PRES", "FUT" -> {
                val person = slot.person ?: return null
                "${slot.tense}_$person${slot.number}"
            }
            "PAST" -> when {
                slot.number == "PL" -> "PAST_PL"
                slot.agreesWith != null && gender != null -> "PAST_$gender"
                else -> null
            }
            else -> null
        }
    }

    private fun fillTemplate(template: String, forms: Map<String, String>): String {
        var result = template
        for ((role, surface) in forms) result = result.replace("{$role}", surface)
        require("{" !in result) { "unfilled placeholder in template: $template" }
        return result
    }

    companion object {
        private val CYRILLIC = Regex("""[\u0400-\u04FF]""")
        private val FIXED_ENGLISH = mapOf("быть" to "be")
        private val IRREGULAR_PLURALS = mapOf(
            "child" to "children", "man" to "men", "woman" to "women", "person" to "people"
        )
        private val IRREGULAR_THIRD_PERSON = mapOf(
            "be" to "is", "have" to "has", "do" to "does", "go" to "goes"
        )
        private val IRREGULAR_PAST = mapOf(
            "be" to "was", "begin" to "began", "buy" to "bought", "come" to "came",
            "do" to "did", "give" to "gave", "go" to "went", "have" to "had",
            "know" to "knew", "make" to "made", "read" to "read", "say" to "said",
            "see" to "saw", "send" to "sent", "speak" to "spoke", "take" to "took",
            "think" to "thought", "write" to "wrote"
        )

        fun parseSlots(slotsJson: String): List<FrameSlot> {
            val array = JSONArray(slotsJson)
            return (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                fun stringOrNull(key: String): String? = if (obj.has(key)) obj.getString(key) else null
                FrameSlot(
                    role = obj.getString("role"),
                    pos = obj.getString("pos"),
                    case = stringOrNull("case"),
                    number = obj.optString("number", "SG"),
                    tense = stringOrNull("tense"),
                    person = if (obj.has("person")) obj.getInt("person") else null,
                    aspect = stringOrNull("aspect"),
                    agreesWith = stringOrNull("agreesWith"),
                    feats = stringOrNull("feats"),
                    fixedLemma = stringOrNull("fixedLemma"),
                    poolLemmas = obj.optJSONArray("poolLemmas")?.let { arr ->
                        (0 until arr.length()).map { MorphologyEngine.normalize(arr.getString(it)) }.toSet()
                    } ?: emptySet(),
                    target = obj.optBoolean("target", false)
                )
            }
        }
    }
}
