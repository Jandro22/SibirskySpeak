package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Rating
import kotlin.math.roundToInt

/**
 * Coordinates the many concrete task formats as observations of a smaller set of
 * learnable capabilities. Cards still provide task variety; they no longer all
 * deserve unrelated lifetime review debt merely because their UI differs.
 */
object CapabilityScheduler {
    private const val DAY_MILLIS = 86_400_000L

    private val conceptScopedTypes = setOf(
        CardType.CASE_FILL,
        CardType.VERB_FORM,
        CardType.ADJ_AGREE,
        CardType.GENDER_ID,
        CardType.ASPECT_SELECT,
        CardType.CONCEPT_DRILL,
        CardType.CONCEPT_APPLY,
        CardType.TRANSFORM,
        CardType.NOVEL_PRODUCE
    )

    fun key(card: Card): String {
        if (card.cardType == CardType.LESSON) return "lesson:${card.id}"
        val facet = CardPedagogy.profile(card.cardType).facet.name
        val concept = card.gramConcept?.takeIf { card.cardType in conceptScopedTypes }
        return if (concept != null) "concept:$concept:$facet" else "note:${card.noteId}:$facet"
    }

    fun isConceptScoped(card: Card): Boolean =
        card.cardType in conceptScopedTypes && !card.gramConcept.isNullOrBlank()

    /** One representative task per capability. Rotation among near-equally-due
     * siblings keeps formats/carriers varied without multiplying the queue. */
    fun collapse(cards: List<Card>, now: Long, preserveInputOrder: Boolean = false): List<Card> {
        val representatives = cards.groupBy(::key).values.map { group -> representative(group, now, preserveInputOrder) }
        return if (preserveInputOrder) representatives
        else representatives.sortedWith(compareBy<Card> { it.due }.thenBy { it.id })
    }

    private fun representative(group: List<Card>, now: Long, preserveInputOrder: Boolean): Card {
        if (group.size == 1) return group.first()
        if (preserveInputOrder && group.all { it.state == CardState.NEW }) {
            // Preserve the first curriculum carrier when a capability spans notes,
            // but rotate equivalent task formats on the same note. Otherwise the
            // first dormant production variant can block CLOZE/SPEAK/DICTATION from
            // ever even being sampled while the new-card frontier keeps advancing.
            if (group.map { it.noteId }.distinct().size > 1) return group.first()
            val ordered = group.sortedBy { it.id }
            val epochDay = Math.floorDiv(now, DAY_MILLIS)
            return ordered[Math.floorMod(epochDay, ordered.size.toLong()).toInt()]
        }
        val earliest = group.minOf { it.due }
        val frontier = group.filter { it.due <= earliest + DAY_MILLIS }.sortedBy { it.id }
        val epochDay = Math.floorDiv(now, DAY_MILLIS)
        val turn = epochDay + group.maxOf { it.reps }.toLong()
        return frontier[Math.floorMod(turn, frontier.size.toLong()).toInt()]
    }

    /**
     * A successful task provides bounded evidence for sibling tasks measuring the
     * same capability. Strong evidence defers them more; ASR/choice/open-production
     * evidence defers them less. Failures deliberately do not transfer.
     */
    fun transferSuccess(
        reviewed: Card,
        scheduled: Card,
        sibling: Card,
        rating: Rating,
        strength: EvidenceStrength,
        now: Long
    ): Card? {
        if (rating == Rating.AGAIN || key(reviewed) != key(sibling)) return null
        if (sibling.id == reviewed.id || sibling.suspended || sibling.state in setOf(CardState.NEW, CardState.GRADUATED)) return null
        val weight = when (strength) {
            EvidenceStrength.STRONG -> 0.65
            EvidenceStrength.MODERATE -> 0.40
            EvidenceStrength.PRACTICE -> 0.22
            EvidenceStrength.INSTRUCTION -> 0.0
        }
        if (weight <= 0.0 || scheduled.scheduledDays <= 0) return null
        val intervalDays = (scheduled.scheduledDays * weight).roundToInt().coerceAtLeast(1)
        val sourceStability = scheduled.stability.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val priorStability = sibling.stability.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val stability = if (priorStability == 0.0) sourceStability * weight
            else priorStability + (sourceStability - priorStability).coerceAtLeast(0.0) * weight
        val difficulty = scheduled.difficulty.takeIf { it.isFinite() && it in 1.0..10.0 }
            ?: sibling.difficulty.coerceIn(1.0, 10.0)
        return sibling.copy(
            due = maxOf(sibling.due, now + intervalDays * DAY_MILLIS),
            stability = stability.coerceAtLeast(0.1),
            difficulty = difficulty,
            elapsedDays = 0,
            scheduledDays = maxOf(sibling.scheduledDays, intervalDays),
            reps = maxOf(sibling.reps, 1),
            state = sibling.state,
            lastReview = sibling.lastReview ?: now,
            consecutiveCorrect = maxOf(sibling.consecutiveCorrect, 1)
        )
    }
}
