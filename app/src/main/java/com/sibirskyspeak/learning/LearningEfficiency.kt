package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.ContentCollocation
import com.sibirskyspeak.data.ContentRootFamily
import com.sibirskyspeak.data.MinedExample
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.SemanticNeighbor
import com.sibirskyspeak.data.SentenceCandidate
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.scheduler.FsrsScheduler
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

private val WORD = Regex("[А-Яа-яЁё]+")
private val LATIN_WORD = Regex("[a-z]+")

/** Pure i+1 miner. Morphological lemmas are baked into the content DB at build time;
 * runtime uses a known surface/lemma set only to estimate sentence comprehensibility. */
object ExampleMiner {
    data class Ranked(val example: MinedExample, val coverage: Double, val isIPlusOne: Boolean)

    fun rank(
        note: Note,
        candidates: List<SentenceCandidate>,
        knownForms: Set<String>,
        knownCount: Int,
        now: Long = System.currentTimeMillis()
    ): List<Ranked> {
        val known = knownForms.mapTo(HashSet()) { normalizeRu(it) }
        val targetForms = setOf(normalizeRu(note.lemma), normalizeRu(note.russian))
        return candidates.mapNotNull { candidate ->
            val tokens = WORD.findAll(candidate.ruPlain).map { normalizeRu(it.value) }.toList()
            if (tokens.isEmpty() || candidate.targetPos !in tokens.indices) return@mapNotNull null
            // The build-time morphology index is authoritative for inflected target
            // forms (e.g. книга -> книгу). Runtime string equality to the lemma would
            // silently discard exactly the contextual examples we want.
            val knownOther = tokens.withIndex().count { (index, token) -> index == candidate.targetPos || token in known || token in targetForms }
            val unknownTokens = tokens.withIndex().filterNot { (index, token) -> index != candidate.targetPos && token in known }
                .map { it.value }.distinct()
            val targetUnknown = true // target position is supplied by the lemma index
            val unknownCount = unknownTokens.size
            // True i+1 (<=1 unknown) is the goal and scores highest, but a learner with a
            // tiny known-set can't satisfy it yet — and an example that ships WITH its
            // English translation is still comprehensible input. So the unknown ceiling
            // relaxes for beginners and tightens toward strict i+1 as the known-set grows,
            // rather than starving the example backfill exactly when it matters most.
            val maxUnknown = when {
                knownCount < 150 -> 4
                knownCount < 600 -> 3
                else -> 2
            }
            if (unknownCount > maxUnknown) return@mapNotNull null
            val coverage = knownOther.toDouble() / tokens.size
            val lengthFit = 1.0 - (abs(tokens.size - 6.5) / 6.5).coerceIn(0.0, 1.0)
            val anchored = anchorGloss(note.translation, candidate.en)
            val senseScore = senseOverlap(anchored, candidate.en)
            val iPlusOne = unknownCount == 1 && targetUnknown
            val score = (if (iPlusOne) 3.0 else 0.0) +
                1.5 * coverage + lengthFit + 0.8 * senseScore +
                0.4 * candidate.rating.coerceIn(0.0, 1.0) + if (candidate.audio) 0.2 else 0.0
            Ranked(
                MinedExample(
                    noteId = note.id,
                    ru = tidyPunctuation(candidate.ruStressed.ifBlank { candidate.ruPlain }),
                    en = tidyPunctuation(candidate.en),
                    sentenceId = candidate.id,
                    anchoredGloss = anchored,
                    score = score,
                    knownAtMine = knownCount,
                    targetPos = candidate.targetPos,
                    unknownCount = unknownCount,
                    createdAt = now
                ),
                coverage,
                iPlusOne
            )
        }.sortedWith(compareByDescending<Ranked> { it.example.score }.thenBy { it.example.sentenceId })
    }

    fun anchorGloss(gloss: String, english: String): String {
        val senses = gloss.split(Regex("\\s*(?:;|,(?=\\s)|/|\\bor\\b)\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotBlank() }
        if (senses.size <= 1) return gloss.trim()
        return senses.maxWithOrNull(compareBy<String> { senseOverlap(it, english) }.thenBy { -it.length })
            ?: senses.first()
    }

    private fun senseOverlap(sense: String, english: String): Double {
        val a = LATIN_WORD.findAll(sense.lowercase(Locale.ROOT)).map { it.value }.filterNot { it in STOP }.toSet()
        val b = LATIN_WORD.findAll(english.lowercase(Locale.ROOT)).map { it.value }.filterNot { it in STOP }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / sqrt((a.size * b.size).toDouble())
    }

    fun tidyPunctuation(text: String): String = text
        .replace(Regex("\\s+([,.;:!?…])"), "$1")
        .replace(Regex("([«(])\\s+"), "$1")
        .replace(Regex("\\s+([»)])"), "$1")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    private fun normalizeRu(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("ё", "е").replace(Regex("\\p{M}+"), "").trim()

    private val STOP = setOf("a", "an", "the", "to", "of", "in", "on", "for", "and", "or", "is", "be", "with")
}

object CognateDetector {
    private val map = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ы' to "y", 'э' to "e", 'ю' to "yu", 'я' to "ya", 'ь' to "", 'ъ' to ""
    )

    fun isCognate(russian: String, englishGloss: String): Boolean {
        val translit = russian.lowercase(Locale.ROOT).map { map[it] ?: it.toString() }.joinToString("")
        return LATIN_WORD.findAll(englishGloss.lowercase(Locale.ROOT)).map { it.value }
            .any { it.length >= 5 && similarity(translit, it) >= 0.62 }
    }

    internal fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            var diagonal = prev[0]; prev[0] = i + 1
            for (j in b.indices) {
                val old = prev[j + 1]
                prev[j + 1] = minOf(prev[j + 1] + 1, prev[j] + 1, diagonal + if (a[i] == b[j]) 0 else 1)
                diagonal = old
            }
        }
        return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
    }
}

data class Enrichment(
    val collocations: List<ContentCollocation> = emptyList(),
    val family: List<ContentRootFamily> = emptyList(),
    val emoji: String? = null,
    val neighbors: List<SemanticNeighbor> = emptyList(),
    val cognate: Boolean = false
)

/** Four-parameter Bayesian Knowledge Tracing update per concept/root. */
object MasteryModel {
    fun update(prior: Double, success: Boolean, learn: Double = 0.12, guess: Double = 0.20, slip: Double = 0.10): Double {
        val p = prior.coerceIn(0.001, 0.999)
        val posterior = if (success) {
            p * (1.0 - slip) / (p * (1.0 - slip) + (1.0 - p) * guess)
        } else {
            p * slip / (p * slip + (1.0 - p) * (1.0 - guess))
        }
        return (posterior + (1.0 - posterior) * learn).coerceIn(0.001, 0.999)
    }
}

/** Tiny diagonal LinUCB policy. It self-tunes card/format preferences without a
 * server or a data-hungry model; arrays are a few dozen doubles in practice. */
class ContextualBandit(private val dimensions: Int, private val alpha: Double = 0.35) {
    data class Arm(var pulls: Int = 0, val reward: DoubleArray, val precision: DoubleArray)
    data class Snapshot(val action: String, val pulls: Int, val reward: DoubleArray, val precision: DoubleArray)
    private val arms = linkedMapOf<String, Arm>()

    fun choose(context: DoubleArray, actions: Collection<String>): String? = actions.maxByOrNull { action ->
        score(action, context)
    }

    fun score(action: String, context: DoubleArray): Double {
        val arm = arms.getOrPut(action) { Arm(reward = DoubleArray(dimensions), precision = DoubleArray(dimensions) { 1.0 }) }
        return context.indices.take(dimensions).sumOf { i ->
            val mean = arm.reward[i] / arm.precision[i]
            context[i] * mean + alpha * abs(context[i]) / sqrt(arm.precision[i])
        }
    }

    fun update(action: String, context: DoubleArray, reward: Double) {
        val arm = arms.getOrPut(action) { Arm(reward = DoubleArray(dimensions), precision = DoubleArray(dimensions) { 1.0 }) }
        context.indices.take(dimensions).forEach { i ->
            arm.precision[i] += context[i] * context[i]
            arm.reward[i] += context[i] * reward
        }
        arm.pulls++
    }

    fun snapshot(): List<Snapshot> = arms.map { (action, arm) ->
        Snapshot(action, arm.pulls, arm.reward.copyOf(), arm.precision.copyOf())
    }

    fun restore(states: Collection<Snapshot>) {
        arms.clear()
        states.forEach { snapshot ->
            arms[snapshot.action] = Arm(
                pulls = snapshot.pulls,
                reward = snapshot.reward.copyOf(),
                precision = snapshot.precision.copyOf()
            )
        }
    }
}

/** Closed-form review intensity inspired by the Memorize/Hawkes control policy. */
object ReviewControl {
    fun intensity(retrievability: Double, reviewCost: Double = 1.0, forgettingCost: Double = 4.0): Double =
        sqrt((forgettingCost / reviewCost).coerceAtLeast(0.0)) * (1.0 - retrievability.coerceIn(0.0, 1.0))

    fun optimalRetention(reviewBudgetPerCard: Double): Double =
        (1.0 - reviewBudgetPerCard.coerceAtLeast(0.0) / 2.0).coerceIn(0.85, 0.90)
}

/** A "hard" productive card (recall/produce/listen) vs. an easy receptive one, used by
 * the scorer's hard/easy load smoothing. Single source of truth shared with the caller. */
fun AnswerMode.isHardProduction(): Boolean =
    this == AnswerMode.RUSSIAN_TYPED || this == AnswerMode.RUSSIAN_STRESS_TYPED ||
        this == AnswerMode.SPEAK || this == AnswerMode.AUDIO_ONLY

enum class SessionMode { QUICK, FULL, STRETCH }
enum class SessionPhase { WARM_UP, CORE, COOL_DOWN }
enum class FlowState { STRUGGLING, STEADY, FLOW }

data class SessionBlueprint(
    val mode: SessionMode,
    val atRiskCardIds: Set<Long>,
    val reviewBudget: Int,
    val newBudget: Int,
    val warmUpCount: Int,
    val coolDownCount: Int,
    val readingInsertions: List<Int>,
    val targetRetention: Double
) {
    val totalBudget: Int get() = reviewBudget + newBudget
}

object BlueprintBuilder {
    private const val DAY_MS = 86_400_000.0

    fun build(
        cards: List<Card>,
        now: Long,
        desiredRetention: Double,
        dailyNewCap: Int,
        capacity: Int,
        backlog: Boolean,
        recentAccuracy: Double,
        mode: SessionMode = SessionMode.FULL,
        successProbability: ((Card) -> Double)? = null,
        decay: Double = 0.1542
    ): SessionBlueprint {
        val target = desiredRetention.coerceIn(0.85, 0.90)
        val risks = cards.filter { it.state != CardState.NEW && it.state != CardState.GRADUATED }.mapNotNull { card ->
            val elapsed = ((now - (card.lastReview ?: now)).coerceAtLeast(0) / DAY_MS)
            val rNow = FsrsScheduler.retrievabilityOf(elapsed, card.stability, decay)
            val rTonight = FsrsScheduler.retrievabilityOf(elapsed + 1.0, card.stability, decay)
            val unified = successProbability?.invoke(card) ?: rNow
            card.takeIf { unified < target || rTonight < target }
        }.sortedBy { card ->
            val elapsed = ((now - (card.lastReview ?: now)).coerceAtLeast(0) / DAY_MS)
            successProbability?.invoke(card) ?: FsrsScheduler.retrievabilityOf(elapsed, card.stability, decay)
        }
        val hardCapacity = when (mode) { SessionMode.QUICK -> risks.size; SessionMode.FULL -> capacity; SessionMode.STRETCH -> capacity + max(3, capacity / 4) }
            .coerceAtLeast(0)
        val reviews = minOf(risks.size, hardCapacity)
        val adaptiveNew = when {
            mode == SessionMode.QUICK || backlog -> 0
            recentAccuracy < 0.75 -> dailyNewCap / 2
            recentAccuracy > 0.90 && mode == SessionMode.STRETCH -> dailyNewCap + 3
            else -> dailyNewCap
        }.coerceAtMost((hardCapacity - reviews).coerceAtLeast(0))
        val total = reviews + adaptiveNew
        return SessionBlueprint(
            mode = mode,
            atRiskCardIds = risks.take(reviews).mapTo(linkedSetOf()) { it.id },
            reviewBudget = reviews,
            newBudget = adaptiveNew,
            warmUpCount = minOf(3, max(0, total / 8)),
            coolDownCount = if (total > 2) 1 else 0,
            readingInsertions = if (total >= 6) listOf(total / 2, total) else listOf(total),
            targetRetention = target
        )
    }
}

data class LiveSessionState(
    val shown: Int = 0,
    val recent: List<Pair<Long, Boolean>> = emptyList(),
    val recentNoteIds: List<Long> = emptyList(),
    val lapsedShownAt: Map<Long, Int> = emptyMap(),
    val introducedConcepts: Set<String> = emptySet(),
    // Whether each of the most-recently-shown cards was a "hard" productive card, so
    // the scorer can actually alternate hard/easy. Last element = previous card.
    val recentHard: List<Boolean> = emptyList()
) {
    val flow: FlowState get() {
        if (recent.size < 3) return FlowState.STEADY
        val accuracy = recent.takeLast(4).count { it.second }.toDouble() / minOf(4, recent.size)
        val speed = recent.takeLast(3).map { it.first }.average()
        return when { accuracy < 0.70 -> FlowState.STRUGGLING; accuracy >= 0.95 && speed < 6_000 -> FlowState.FLOW; else -> FlowState.STEADY }
    }
}

/** Live scorer used after every answer. It enforces sibling/lesson/lapse-spacing
 * constraints before applying urgency, interleaving, load, contrast and arc scores. */
object NextCardSelector {
    fun select(
        pool: List<ReviewPrompt>,
        blueprint: SessionBlueprint,
        live: LiveSessionState,
        now: Long,
        confusableNoteIds: Set<Pair<Long, Long>> = emptySet(),
        targetDifficulty: Double = 0.85,
        productionRatio: Double = 0.25,
        successProbability: (ReviewPrompt) -> Double = { prompt ->
            val elapsed = ((now - (prompt.card.lastReview ?: now)).coerceAtLeast(0) / 86_400_000.0)
            if (prompt.card.state == CardState.NEW) 1.0 else FsrsScheduler.retrievabilityOf(elapsed, prompt.card.stability, 0.1542)
        },
        policyBias: (ReviewPrompt) -> Double = { 0.0 }
    ): ReviewPrompt? {
        val phase = when {
            live.shown < blueprint.warmUpCount -> SessionPhase.WARM_UP
            live.shown >= blueprint.totalBudget - blueprint.coolDownCount -> SessionPhase.COOL_DOWN
            else -> SessionPhase.CORE
        }
        val eligible = pool.filter { prompt ->
            prompt.note.id !in live.recentNoteIds.takeLast(1) &&
                (prompt.card.gramConcept == null || prompt.card.cardType == CardType.LESSON || prompt.card.gramConcept in live.introducedConcepts) &&
                (live.lapsedShownAt[prompt.card.id]?.let { live.shown - it >= recoveryGap(live.flow) } != false) &&
                !(live.flow == FlowState.STRUGGLING && prompt.card.state == CardState.NEW)
        }
        return eligible.maxByOrNull { prompt ->
            score(prompt, phase, blueprint, live, now, confusableNoteIds, targetDifficulty, productionRatio, successProbability(prompt)) + policyBias(prompt)
        }
    }

    private fun score(
        prompt: ReviewPrompt,
        phase: SessionPhase,
        blueprint: SessionBlueprint,
        live: LiveSessionState,
        now: Long,
        pairs: Set<Pair<Long, Long>>,
        targetDifficulty: Double,
        productionRatio: Double,
        successProbability: Double
    ): Double {
        val card = prompt.card
        val retention = successProbability.coerceIn(0.0, 1.0)
        val urgency = if (card.id in blueprint.atRiskCardIds) 3.0 + ReviewControl.intensity(retention) else ReviewControl.intensity(retention)
        val hard = prompt.answerMode.isHardProduction()
        // Alternate hard/easy against the ACTUAL previous card's difficulty (not, as
        // before, "have >=2 cards been shown" — which never alternated). Neutral when
        // there's no prior card yet.
        val load = when (live.recentHard.lastOrNull()) {
            null -> 0.0
            hard -> -0.35
            else -> 0.35
        }
        val difficultyFit = (1.0 - abs(card.difficulty.coerceIn(0.0, 1.0) - targetDifficulty)).coerceIn(0.0, 1.0)
        val productionTilt = if (hard) productionRatio else (1.0 - productionRatio) * 0.15
        val arc = when (phase) {
            SessionPhase.WARM_UP -> if (!hard && card.state != CardState.NEW) 1.5 else -0.5
            SessionPhase.CORE -> if (card.id in blueprint.atRiskCardIds || card.state == CardState.NEW) 0.8 else 0.0
            SessionPhase.COOL_DOWN -> if (!hard || CognateDetector.isCognate(prompt.note.russian, prompt.note.translation)) 2.0 else -1.0
        }
        val contrast = live.recentNoteIds.lastOrNull()?.let { last -> if ((last to prompt.note.id) in pairs || (prompt.note.id to last) in pairs) 0.5 else 0.0 } ?: 0.0
        return 2.2 * urgency + 0.5 * difficultyFit + productionTilt + load + arc + contrast + if (prompt.note.id !in live.recentNoteIds.takeLast(3)) 0.4 else 0.0
    }

    fun recoveryGap(flow: FlowState): Int = when (flow) { FlowState.STRUGGLING -> 4; FlowState.STEADY -> 6; FlowState.FLOW -> 8 }
}

enum class MpcAction { CARD, STOP, STRETCH }

data class MpcInputs(
    val targetAccuracy: Double = 0.85,
    val fatigue: Double = 0.0,
    val debtRatio: Double = 0.0,
    val debtLimit: Double = 0.35,
    val pReturn: Double = 0.8,
    val stretchAlreadyOffered: Boolean = false,
    val minimumEvidenceCards: Int = 4
)

object SessionMpcController {
    fun decide(hasCard: Boolean, live: LiveSessionState, inputs: MpcInputs): MpcAction {
        if (!hasCard) return MpcAction.STOP
        if (live.shown < inputs.minimumEvidenceCards) return MpcAction.CARD
        val recent = live.recent.takeLast(4)
        val accuracy = if (recent.isEmpty()) inputs.targetAccuracy else recent.count { it.second }.toDouble() / recent.size
        val baseline = live.recent.take(3).map { it.first }.average().takeIf { !it.isNaN() } ?: Double.MAX_VALUE
        val speedHolding = live.recent.takeLast(3).map { it.first }.average().let { it.isNaN() || it <= baseline * 1.05 }
        val canStretch = !inputs.stretchAlreadyOffered && live.shown >= 4 &&
            accuracy > inputs.targetAccuracy && speedHolding && inputs.fatigue < 0.5 &&
            inputs.debtRatio < inputs.debtLimit && inputs.pReturn >= 0.80

        val cardUtility = 0.9 + if (live.flow == FlowState.FLOW) 0.25 else 0.0 - 0.8 * inputs.fatigue
        val stopUtility = 0.35 + 1.5 * inputs.fatigue + 2.0 * (inputs.targetAccuracy - accuracy).coerceAtLeast(0.0) +
            if (live.shown >= 4) 0.15 else -0.5
        val stretchUtility = if (canStretch) 1.25 + 0.8 * (accuracy - inputs.targetAccuracy) - 0.4 * inputs.fatigue else Double.NEGATIVE_INFINITY
        return when {
            stopUtility > maxOf(cardUtility, stretchUtility) -> MpcAction.STOP
            stretchUtility > cardUtility -> MpcAction.STRETCH
            else -> MpcAction.CARD
        }
    }
}

/** Chains high-coverage corpus sentences by lexical overlap into a narrow read. */
object NarrowReadingGenerator {
    fun chain(candidates: List<MinedExample>, limit: Int = 5): List<MinedExample> {
        if (candidates.isEmpty()) return emptyList()
        val remaining = candidates.sortedByDescending { it.score }.toMutableList()
        val result = mutableListOf(remaining.removeAt(0))
        while (remaining.isNotEmpty() && result.size < limit) {
            val prior = WORD.findAll(result.last().ru).map { it.value.lowercase(Locale.ROOT) }.toSet()
            val next = remaining.maxByOrNull { candidate ->
                WORD.findAll(candidate.ru).map { it.value.lowercase(Locale.ROOT) }.toSet().intersect(prior).size * 2 + candidate.score
            } ?: break
            remaining.remove(next); result += next
        }
        return result
    }
}

/** Seven-day MPC-lite: deterministic rollouts are deliberately bounded for phones. */
object SessionLookahead {
    data class Choice(val newCards: Int, val projectedReviews: Int, val utility: Double)
    fun choose(cap: Int, dueForecast: List<Int>, retention: Double): Choice = (0..cap).map { added ->
        val projectedReviews = dueForecast.sum() + (added * (1.0 + (1.0 - retention) * 3.0)).toInt()
        // Concave benefit (diminishing returns on cramming more new cards into one day)
        // minus the linear future-review load each adds. The old form used a linear
        // benefit and a tiny 0.06 cost, so utility rose monotonically and it always
        // returned `cap` — a no-op. ln() gives a genuine interior optimum.
        val utility = retention * ln(1.0 + added) - projectedReviews * 0.02
        Choice(added, projectedReviews, utility)
    }.maxBy { it.utility }
}
