package com.sibirskyspeak.data

import kotlin.math.max

/** The learning job an episode is doing, independent of its surface exercise mix. */
enum class EpisodeMode { ACQUIRE, RETRIEVE, REPAIR, TRANSFER }

data class AdaptiveEpisodePolicy(
    val mode: EpisodeMode,
    val focus: String,
    val targetMinutes: Int,
    val maxTasks: Int,
    val newNoteLimit: Int,
    val interleaveLimit: Int,
    val contrastLimit: Int,
    val recentMissRate: Double,
    val weakestKind: String,
    /** 0 choices -> 1 tiles -> 2 context-only tiles -> 3 unsupported speech. */
    val supportStage: Int = 0
)

data class CapabilityMasteryProfile(
    val observedNotes: Int,
    val totalNotes: Int,
    val observedKinds: Set<String>,
    val averageMemoryReadiness: Double,
    val dueStudiedComponents: Int,
    val fragileComponents: Int
) {
    /** Screen completion alone can never move the route past an unobserved capability. */
    fun supportsRouteAdvance(progress: CapabilityProgress, requiredCompletions: Int = 3): Boolean {
        val requiredNotes = minOf(6, totalNotes)
        val requiredKinds = if (totalNotes == 0) emptySet() else setOf("MEANING", "FORM", "SOUND")
        return progress.isRouteReady(requiredCompletions) &&
            observedNotes >= requiredNotes &&
            observedKinds.containsAll(requiredKinds) &&
            averageMemoryReadiness >= 0.45
    }
}

/**
 * Pure policy layer for selecting desirable difficulty rather than selecting a card type.
 * It combines memory risk, modality gaps, recent failure, and real response speed.
 */
object AdaptiveEpisodePolicyEngine {
    fun masteryProfile(
        components: List<KnowledgeComponent>,
        now: Long
    ): CapabilityMasteryProfile {
        val lexical = components.filter { it.noteId != null }
        val observed = components.filter { it.reps > 0 }
        val readiness = observed.map { memoryReadiness(it, now) }
        return CapabilityMasteryProfile(
            observedNotes = lexical.filter { it.reps > 0 }.mapNotNull { it.noteId }.distinct().size,
            totalNotes = lexical.mapNotNull { it.noteId }.distinct().size,
            observedKinds = observed.mapTo(linkedSetOf()) { it.kind },
            averageMemoryReadiness = readiness.averageOrZero(),
            dueStudiedComponents = observed.count { it.due <= now },
            fragileComponents = observed.count {
                memoryReadiness(it, now) < 0.45 || it.lapses.toDouble() / max(1, it.reps) >= 0.25
            }
        )
    }

    fun decide(
        components: List<KnowledgeComponent>,
        recentEvidence: List<CapabilityEvidence>,
        progress: CapabilityProgress,
        now: Long
    ): AdaptiveEpisodePolicy {
        val profile = masteryProfile(components, now)
        val recent = recentEvidence.sortedByDescending { it.observedAt }.take(24)
        val missRate = if (recent.isEmpty()) 0.0 else recent.count { it.outcome == "MISS" }.toDouble() / recent.size
        val novelShare = if (components.isEmpty()) 1.0 else components.count { it.reps == 0 }.toDouble() / components.size
        val fragileShare = if (components.isEmpty()) 0.0 else profile.fragileComponents.toDouble() / components.size
        val mode = when {
            progress.completedEpisodes == 0 && novelShare >= 0.35 -> EpisodeMode.ACQUIRE
            missRate >= 0.28 || fragileShare >= 0.22 -> EpisodeMode.REPAIR
            progress.completedEpisodes < 3 -> EpisodeMode.RETRIEVE
            profile.dueStudiedComponents >= 2 -> EpisodeMode.RETRIEVE
            else -> EpisodeMode.TRANSFER
        }
        val weakestKind = components
            .groupBy { it.kind }
            .minByOrNull { (_, values) -> values.map { memoryReadiness(it, now) }.averageOrZero() }
            ?.key ?: "MEANING"
        val responseMedian = recent.mapNotNull { it.responseMs }.sorted().let { values ->
            if (values.isEmpty()) null else values[values.size / 2]
        }
        val targetMinutes = when {
            missRate >= 0.40 || (responseMedian ?: 0L) >= 15_000L -> 4
            else -> 5
        }
        val focus = when (mode) {
            EpisodeMode.ACQUIRE -> "build a small usable base in this situation"
            EpisodeMode.RETRIEVE -> "bring ${kindLabel(weakestKind)} back before it fades"
            EpisodeMode.REPAIR -> "separate and repair fragile ${kindLabel(weakestKind)}"
            EpisodeMode.TRANSFER -> "use familiar Russian in a different situation"
        }
        // Phone telemetry showed a sharp completion cliff once a short episode
        // grew from six tasks to twelve: the six-task episodes completed, while
        // the longer arcs were repeatedly left after their opening steps. Keep
        // the cognitive arc, but fit it to an honest four-to-five minute visit.
        // Higher bands receive slightly more room for mediation/pragmatics.
        val bandCeiling = when (progress.band) {
            "A1" -> 7
            "A2" -> 8
            "B1" -> 9
            "B2" -> 10
            else -> 11
        }
        val frictionAdjustment = if (targetMinutes <= 4) -1 else 0
        return AdaptiveEpisodePolicy(
            mode = mode,
            focus = focus,
            targetMinutes = targetMinutes,
            maxTasks = (bandCeiling + frictionAdjustment).coerceAtLeast(5),
            newNoteLimit = if (mode == EpisodeMode.ACQUIRE) 3 else 2,
            interleaveLimit = if (progress.completedEpisodes > 0) 1 else 0,
            contrastLimit = if (mode == EpisodeMode.REPAIR) 2 else 1,
            recentMissRate = missRate,
            weakestKind = weakestKind,
            supportStage = progress.completedEpisodes.coerceIn(0, 3)
        )
    }

    /** Rank notes by the job of this episode, not by a single stale due timestamp. */
    fun rankNoteIds(
        components: List<KnowledgeComponent>,
        policy: AdaptiveEpisodePolicy,
        now: Long
    ): List<Long> = components.filter { it.noteId != null }
        .groupBy { it.noteId!! }
        .map { (noteId, values) ->
            val novelty = values.count { it.reps == 0 }.toDouble() / values.size
            val risk = values.maxOf { 1.0 - ComponentScheduler.retrievability(it, now) }
            val lapse = values.maxOf { it.lapses.toDouble() / max(1, it.reps) }
            val overdue = values.count { it.reps > 0 && it.due <= now }.toDouble() / values.size
            val formGap = values.filter { it.kind == "FORM" }.maxOfOrNull { 1.0 - memoryReadiness(it, now) } ?: 0.0
            val score = when (policy.mode) {
                EpisodeMode.ACQUIRE -> novelty * 4.0 + risk + overdue
                EpisodeMode.RETRIEVE -> risk * 3.0 + overdue * 2.0 + lapse
                EpisodeMode.REPAIR -> lapse * 4.0 + risk * 2.0 + formGap
                EpisodeMode.TRANSFER -> formGap * 3.0 + risk + overdue
            }
            noteId to score
        }
        .sortedWith(compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first })
        .map { it.first }

    fun memoryReadiness(component: KnowledgeComponent, now: Long): Double {
        if (component.reps == 0) return 0.0
        return (0.7 * ComponentScheduler.retrievability(component, now) + 0.3 * component.confidence)
            .coerceIn(0.0, 1.0)
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "SOUND" -> "listening memory"
        "FORM" -> "spoken forms"
        "CONSTRUCTION" -> "grammar patterns"
        else -> "meanings"
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
