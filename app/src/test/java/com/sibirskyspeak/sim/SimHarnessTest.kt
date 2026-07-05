package com.sibirskyspeak.sim

import com.sibirskyspeak.data.CardType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real LearningRepository (sessionPlan -> answer -> review) through
 * many simulated learners and days. This is the CI gate for P0.3: a scheduler,
 * gating, or budget regression that deadlocks new-card introduction, lets review
 * debt run away, or starves a card type must fail here, not three weeks into real
 * use (as the historical unit-gating deadlock did).
 */
class SimHarnessTest {
    // The organic-growth check below covers every type that a normal curriculum-
    // order study session reaches without engineered preconditions. VERB_FORM,
    // ASPECT_SELECT, CONCEPT_DRILL, and TRANSFORM are verb-anchored and, in this
    // narrow/cold-start fixture (no telemetry, no capacity signal), the pacer's
    // conservative new-card throttling means organic growth can take far longer
    // than is practical for a fast test to reach a verb note at all — that is a
    // property of curriculum pacing/throughput, not of whether the card type's
    // generation and selection pipeline itself works. That question is answered
    // deterministically (given satisfied preconditions, not organic growth) by
    // LearningRepositoryTest.everyNewerCardTypeSurfacesOnceItsPreconditionsAreMet.
    // CASE_FILL joined this exclusion list once case drills got CEFR-paced (see
    // CardFactory.minCefrOrdinalForCase): plural and non-accusative-singular case
    // forms now wait for A2/B1, which shrinks the case-fill-eligible note pool within
    // any bounded early-curriculum window to a handful of notes. Verified directly at
    // seed time (32 CASE_FILL cards generated, unsuspended, for this fixture's
    // maxUnit=100 deck) — they exist and aren't blocked, but reaching one of that
    // small pool via the interleaved/budgeted selection loop before 400+ organic
    // sim-days run out is no longer reliable, the same throughput property the other
    // excluded types already have. Covered deterministically instead by
    // CardFactoryTest's case-pacing tests and ReviewViewModelTest's caseFillOnlyFixture.
    private val organicGrowthTypes = CardType.entries - setOf(
        CardType.VERB_FORM, CardType.ASPECT_SELECT, CardType.CONCEPT_DRILL, CardType.TRANSFORM,
        CardType.CASE_FILL
    )

    @Test fun noDeadlockAcrossSeededLearners() = runTest {
        val outcomes = (0 until 5).map { seed -> SimHarness(seed).run(days = 60) }
        assertTrue("expected new notes to keep being introduced", outcomes.all { it.introducedNotes > 0 })
        assertTrue("expected curriculum to advance past unit 3", outcomes.all { it.maxIntroducedUnit >= 3 })
    }

    @Test fun reviewDebtStaysBounded() = runTest {
        val outcomes = (0 until 5).map { seed -> SimHarness(seed).run(days = 60) }
        assertTrue("debt ratio should never explode past 2x sustainable load", outcomes.all { it.maxDebtRatio < 2.0 })
    }

    @Test fun mostCardTypesSurfaceViaOrganicGrowth() = runTest {
        // Wider unit window than the deadlock/debt checks: authored concept drills
        // and later grammar mechanics are seeded across dozens of units.
        val seen = SimHarness(seed = 11, maxUnit = 100).run(days = 400).typesSeen
        val missing = organicGrowthTypes - seen
        assertTrue("card types never surfaced in 400 sim-days of organic growth: $missing", missing.isEmpty())
    }

    // historicalStrictLinearUnitGateDeadlockIsDetectable (organic-growth version) was
    // removed: at the real repository's cold-start throughput (no telemetry/capacity
    // signal), overall new-card introduction is slow enough that a maxUnit=1 vs
    // maxUnit=12 comparison over any test-practical number of days barely differs —
    // confirmed empirically (healthy=28 vs confined=26 vocab notes at 90 days),
    // making that comparison an unreliable regression signal regardless of whether
    // the sliding-window unlock is actually working. The direct, fast, and reliable
    // regression proof for the sliding-window mechanism itself now lives in
    // LearningRepositoryTest.slidingWindowUnlocksUnitsAheadOfAnIncompleteFrontier,
    // which asserts on unitMastery()'s output directly instead of hoping days of
    // simulated organic growth reveal the difference.
}
