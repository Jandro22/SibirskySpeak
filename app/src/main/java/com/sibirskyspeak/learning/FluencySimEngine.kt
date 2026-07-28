package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.scheduler.FsrsScheduler
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

/** Runs the production pace controller and FSRS scheduler forward day by day. */
object FluencySimEngine {
    const val DAY_MILLIS = 86_400_000L
    // Was 3_650 (10 simulated years). PaceController.generatePace() (called once
    // per simulated day) does several O(n) scans over the accumulated `cards` list
    // internally (reviewLoadNow/atRisk/dueForecast), and that list only ever grows
    // over the simulated horizon — so total cost is roughly O(days * finalCardCount),
    // which measured 34s on-device for a real account (see
    // ReviewViewModel.maybeRefreshFluencyForecast, which now also runs this off the
    // Main dispatcher as the primary fix). Rewriting PaceController's internals to
    // avoid the scans isn't safe to do here — it's also the real production
    // scheduling path, not just this simulation. Capping the horizon at 4 years
    // bounds the worst case to roughly (1460/3650)^2 ≈ 16% of the original cost
    // instead. Past a few years, "reached in 2,800 days" isn't meaningfully more
    // useful to a learner than "not reached within 4 years" — the UI (see
    // FluencyForecastCard) already handles an unreached milestone gracefully.
    private const val MAX_DAYS = 1_460
    private val MILESTONES = linkedMapOf(
        "A1" to 700, "A2" to 1_353, "B1" to 2_538,
        "B2" to 4_209, "C1" to 5_192, "C2" to 6_983
    )

    /** Known-word count threshold for [level], or null if not a recognized CEFR band. */
    fun milestoneThreshold(level: String): Int? = MILESTONES[level]

    data class DayRange(val low: Int, val high: Int)

    data class SimResult(
        val daysToA1: Int?,
        val daysToA2: Int?,
        val daysToB1: Int?,
        val daysToB2: Int?,
        val daysToC1: Int?,
        val daysToC2: Int?,
        val stablePace: Double,
        val finalReviewLoad: Int,
        val ranges: Map<String, DayRange> = emptyMap(),
        val evidenceDays: Int = 0,
        /** Clean-baseline capacity, independent of today's review backlog. */
        val sustainablePace: Double = stablePace,
        /** Number of mature cards present when the forecast started. */
        val startingReviewLoad: Int = 0,
        val startingKnownWords: Int = 0,
        val sustainableMinutes: Double = 0.0,
        val returnProbability: Double = 0.0,
        val simulatedDays: Int = 0
    ) {
        val isEarlyEstimate: Boolean get() = evidenceDays < 14
        fun days(level: String): Int? = when (level) {
            "A1" -> daysToA1; "A2" -> daysToA2; "B1" -> daysToB1
            "B2" -> daysToB2; "C1" -> daysToC1; "C2" -> daysToC2
            else -> null
        }
    }

    fun runSimulation(
        currentCapacity: CapacityBelief,
        currentWillingness: WillingnessBelief,
        initialActiveCards: List<Card>,
        totalKnownStart: Int,
        evidenceDays: Int = 0,
        recentAccuracy: Double = 0.88,
        startTimeMillis: Long = System.currentTimeMillis()
    ): SimResult {
        val core = simulate(
            currentCapacity, currentWillingness, initialActiveCards,
            totalKnownStart, recentAccuracy, startTimeMillis
        )
        // Data scarcity widens the interval. At zero history the displayed range
        // is deliberately broad; it tightens smoothly through the first month.
        val uncertainty = (0.50 - evidenceDays.coerceIn(0, 30) / 30.0 * 0.35).coerceIn(0.15, 0.50)
        val ranges = MILESTONES.keys.mapNotNull { level ->
            core.days(level)?.let { estimateDays ->
                level to DayRange(
                    floor(estimateDays * (1.0 - uncertainty)).toInt().coerceAtLeast(0),
                    ceil(estimateDays * (1.0 + uncertainty)).toInt()
                )
            }
        }.toMap()
        return core.copy(ranges = ranges, evidenceDays = evidenceDays.coerceAtLeast(0))
    }

    private fun simulate(
        initialCapacity: CapacityBelief,
        initialWillingness: WillingnessBelief,
        initialCards: List<Card>,
        knownStart: Int,
        accuracy: Double,
        start: Long
    ): SimResult {
        val cards = initialCards.map { it.copy() }.toMutableList()
        var known = knownStart.coerceIn(0, MILESTONES.getValue("C2"))
        // Held fixed for the simulated horizon — see the note further down where the
        // per-day loop used to re-estimate this from simulated workload.
        val capacity = initialCapacity
        var willingness = initialWillingness
        val reached = MILESTONES.mapValuesTo(linkedMapOf()) { (_, threshold) -> if (known >= threshold) 0 else null }
        val scheduler = FsrsScheduler()
        val random = Random(42)
        val paceSamples = ArrayDeque<Int>()
        val reviewSamples = ArrayDeque<Int>()
        var nextId = (cards.maxOfOrNull(Card::id) ?: 0L) + 1L
        var simulatedDays = 0
        val startingReviewLoad = cards.count { it.state != CardState.NEW && it.state != CardState.GRADUATED }
        val baselineInputs = PaceInputs(
            capacity = initialCapacity,
            willingness = initialWillingness,
            totalKnown = known,
            recentAccuracy = accuracy,
            medianReviewMinutes = 0.18
        )
        val sustainablePace = PaceController.sustainableNewItemRate(baselineInputs, start)
        val startingReturnProbability = WillingnessModel.returnProbability(initialWillingness, ReturnContext())

        for (day in 1..MAX_DAYS) {
            simulatedDays = day
            if (known >= MILESTONES.getValue("C2")) break
            val now = start + day * DAY_MILLIS
            val returnContext = ReturnContext(
                hoursSinceLastZ = 0.0,
                streakZ = 0.5,
                lastSessionFatigue = 0.2,
                lastDebtRatio = 0.0
            )
            val returned = random.nextDouble() < WillingnessModel.returnProbability(willingness, returnContext)
            willingness = WillingnessModel.updateReturn(willingness, returnContext, returned, step = 0.02)
            if (!returned) {
                // A skipped day is not evidence that the learner opened a session
                // and quit; only let habit decay naturally.
                willingness = willingness.copy(habit = WillingnessModel.transition(willingness.habit, WillingnessSignals()))
                paceSamples.addBounded(0)
                reviewSamples.addBounded(0)
                continue
            }

            val pace = PaceController.generatePace(
                PaceInputs(
                    capacity = capacity,
                    willingness = willingness,
                    returnContext = returnContext,
                    activeCards = cards,
                    totalKnown = known,
                    recentAccuracy = accuracy,
                    medianReviewMinutes = 0.18
                ),
                now = now
            )
            var reviews = 0
            for (index in cards.indices) {
                val card = cards[index]
                if (card.state != CardState.NEW && card.state != CardState.GRADUATED && card.due <= now) {
                    val rating = if (random.nextDouble() < accuracy) Rating.GOOD else Rating.AGAIN
                    cards[index] = scheduler.review(card, rating, now).first
                    reviews++
                }
            }

            val added = pace.newItemBudget.coerceAtMost(MILESTONES.getValue("C2") - known)
            repeat(added) {
                val fresh = Card(
                    id = nextId, noteId = nextId++, cardType = CardType.RU_TO_MEANING,
                    queue = Queue.VOCAB, due = now, state = CardState.NEW
                )
                // A newly introduced word receives its first real FSRS review now,
                // so it becomes future review debt instead of remaining dormant.
                cards += scheduler.review(fresh, Rating.GOOD, now).first
            }
            known += added
            MILESTONES.forEach { (level, threshold) ->
                if (reached[level] == null && known >= threshold) reached[level] = day
            }

            // Deliberately NOT re-estimating `capacity` from simulated workload here.
            // An earlier version fed `pace.targetMinutes` back in as if it were an
            // observed session (maxOf(workload, pace.targetMinutes * 0.8)) — circular,
            // since targetMinutes is derived from this same capacity belief, and it
            // collapsed mu from 12.0 to 8.2 in 50 simulated days. Removing that term
            // alone wasn't enough: `added` itself is downstream of capacity (via the
            // pace controller's debt/budget governor), so feeding workload built from
            // `added` back into the SAME belief it came from is still an indirect
            // version of the same loop — verified this by tracing a real cold-start
            // account (mu=12, sigma=8, 21 known words, ~212-card review load): capacity
            // still walked down to its 5.0 floor and got permanently stuck there, which
            // then permanently zeroed the new-card budget for the rest of the 10-year
            // simulation (2026-07-06). `currentCapacity` already represents the best
            // real estimate of this learner's demonstrated ability (from actual
            // completed-session telemetry) — hold it fixed for the simulated horizon
            // instead of re-deriving it from the simulation's own decisions. Willingness
            // does not have this problem: its inputs (completed/cleanFinish/debt-high)
            // are just booleans describing the day, not a magnitude fed back into itself.
            willingness = willingness.copy(
                habit = WillingnessModel.transition(
                    willingness.habit,
                    WillingnessSignals(completed = true, cleanFinish = true, reviewDebtHigh = pace.debtRatio >= PaceController.debtDelta(known))
                )
            )
            paceSamples.addBounded(added)
            reviewSamples.addBounded(reviews)
        }

        return SimResult(
            daysToA1 = reached["A1"], daysToA2 = reached["A2"], daysToB1 = reached["B1"],
            daysToB2 = reached["B2"], daysToC1 = reached["C1"], daysToC2 = reached["C2"],
            stablePace = maxOf(paceSamples.average().takeIf(Double::isFinite) ?: 0.0, sustainablePace),
            finalReviewLoad = if (reviewSamples.isEmpty()) 0 else reviewSamples.average().toInt(),
            sustainablePace = sustainablePace,
            startingReviewLoad = startingReviewLoad,
            startingKnownWords = knownStart.coerceAtLeast(0),
            sustainableMinutes = initialCapacity.sustainableMinutes,
            returnProbability = startingReturnProbability,
            simulatedDays = simulatedDays
        )
    }

    private fun ArrayDeque<Int>.addBounded(value: Int) {
        addLast(value)
        if (size > 60) removeFirst()
    }
}
