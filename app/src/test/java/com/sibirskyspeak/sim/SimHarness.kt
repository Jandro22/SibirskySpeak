package com.sibirskyspeak.sim

import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.ContentCollocation
import com.sibirskyspeak.data.ContentFrame
import com.sibirskyspeak.data.FakeContentDao
import com.sibirskyspeak.data.GrammarConcepts
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.RepoFixture
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.review.AnswerMode
import org.json.JSONObject
import java.io.File

/**
 * Drives the REAL LearningRepository (via RepoFixture's in-memory DAOs) through a
 * multi-day study loop: sessionPlan() -> SyntheticLearner answers each prompt ->
 * review(), with performLaunchMaintenance() re-run periodically so the P4.3/P4.4
 * concept/production-ladder card sync jobs (which only mint once a note's
 * recognition has matured) get a chance to fire as the simulated learner
 * progresses. This is the pedagogy regression gate: a scheduler, gating, or budget
 * change that deadlocks new-card introduction, lets review debt run away, or
 * starves a card type fails here before it ever reaches a real study session.
 *
 * The deck is the real bootstrap asset, filtered to the early tier-0 units (plus
 * every concept lesson note, which gating needs) so a run stays seconds, not
 * minutes, while still exercising curriculum order, unit mastery, concept gates,
 * and every generated card variant those notes carry. A FakeContentDao stands in
 * for the real ContentDatabase (frames.json / collocation table) so the
 * frame-backed card types (CONCEPT_APPLY, NOVEL_PRODUCE, CHUNK) can mint at all —
 * without a contentDao those sync jobs return immediately by design.
 */
internal class SimHarness(seed: Int, maxUnit: Int = MAX_UNIT) {
    private val subset = bootstrapSubset(maxUnit)
    private val fixture = RepoFixture(
        bootstrapNotes = subset.jsonLines,
        contentDao = FakeContentDao(
            // Every concept gets a frame so CONCEPT_APPLY/NOVEL_PRODUCE (P4.3/P4.4 L3)
            // can mint once their lesson note ships — mirrors the real content
            // pipeline's frames.json covering the full GrammarConcepts inventory.
            framesByConcept = GrammarConcepts.ALL.associate { it.id to listOf(genericFrame(it.id)) },
            // One collocation per candidate lemma so CHUNK (P4.4 L1) can mint once
            // that lemma's recognition matures; FrameRealizer/sentence lookups still
            // return empty from this fake, so realized prompts fall back to the
            // static ReviewPrompt branch — sufficient to prove the card *surfaces*.
            chunksByLemma = subset.chunkableLemmas.associateWith { lemma ->
                listOf(ContentCollocation(lemma, "$lemma вот так", 5))
            }
        )
    )
    private val learner = SyntheticLearner(seed)
    private var noteByIdCache: Map<Long, Note> = emptyMap()

    data class Outcome(
        val introducedNotes: Int,
        // Excludes lesson notes (partOfSpeech == "lesson"): those are always present
        // regardless of maxUnit (concept lessons ship unconditionally, see
        // bootstrapSubset below) and auto-graduate on first review, so counting them
        // dilutes any comparison that's meant to isolate real curriculum vocabulary
        // progress (e.g. the strict-linear-gate regression proof below).
        val introducedVocabNotes: Int,
        val maxIntroducedUnit: Int,
        val maxDebtRatio: Double,
        val typesSeen: Set<CardType>,
        val reviewsCompleted: Int
    )

    suspend fun run(days: Int, maxReviewsPerDay: Int = 60, maintenanceEveryDays: Int = 5, clockJumps: Boolean = false): Outcome {
        fixture.repository.seedIfEmpty(runMaintenance = true)
        noteByIdCache = fixture.notes.notes.associateBy { it.id }
        var now = START_EPOCH_MS
        var maxDebt = 0.0
        val typesSeen = mutableSetOf<CardType>()
        var reviews = 0
        repeat(days) { day ->
            // Recognition maturity (what gates CHUNK/TRANSFORM/SPEAK_SENTENCE minting)
            // only develops as reviews accumulate, so re-run maintenance periodically
            // rather than once at seed time.
            if (day > 0 && day % maintenanceEveryDays == 0) fixture.repository.performLaunchMaintenance()
            val plan = fixture.repository.sessionPlan(now, includeReaderInsights = false)
            maxDebt = maxOf(maxDebt, plan.pace?.debtRatio ?: 0.0)
            for (prompt in plan.reviewQueue.take(maxReviewsPerDay)) {
                val card = prompt.card
                typesSeen += card.cardType
                val elapsedDays = ((now - (card.lastReview ?: now)) / DAY_MS).toInt()
                val note = noteByIdCache[card.noteId]
                val item = SimItem(card.id, note?.unit ?: 0, note?.generalFreqRank ?: 5000, card.cardType)
                val correct = prompt.answerMode == AnswerMode.LESSON || learner.answer(item, elapsedDays)
                val rating = if (correct) Rating.GOOD else Rating.AGAIN
                fixture.repository.review(card, rating, now, objectiveCorrect = correct)
                reviews += 1
            }
            // Exercise timezone/device-clock corrections: a wall clock can move
            // backwards after NTP or a user changes the timezone. Scheduling must
            // remain finite and keep the queue progressing rather than producing
            // negative elapsed intervals or NaN model values.
            now = if (clockJumps && day > 0 && day % 11 == 0) now - 3L * DAY_MS else now + DAY_MS
        }
        val introduced = fixture.cards.cards
            .filter { it.state != CardState.NEW }
            .mapTo(mutableSetOf()) { it.noteId }
        val introducedVocab = introduced.filter { noteByIdCache[it]?.partOfSpeech != "lesson" }
        return Outcome(
            introducedNotes = introduced.size,
            introducedVocabNotes = introducedVocab.size,
            maxIntroducedUnit = introduced.maxOfOrNull { noteByIdCache[it]?.unit ?: 0 } ?: 0,
            maxDebtRatio = maxDebt,
            typesSeen = typesSeen,
            reviewsCompleted = reviews
        )
    }

    private data class Subset(val jsonLines: String, val chunkableLemmas: List<String>)

    companion object {
        private const val DAY_MS = 86_400_000L
        // A fixed, timezone-safe "day 1000" so runs are reproducible everywhere.
        private const val START_EPOCH_MS = 1000L * 86_400_000L
        private const val MAX_UNIT = 12
        private val CHUNKABLE_POS = setOf("noun", "verb", "adjective")

        private fun genericFrame(conceptId: String) = ContentFrame(
            id = "sim_$conceptId",
            concept = conceptId,
            band = "A1",
            slotsJson = """[{"role":"obj","pos":"noun","case":"GEN","number":"SG","target":true}]""",
            ruFrame = "У меня нет {obj}.",
            enFrame = "I don't have {obj}."
        )

        private fun bootstrapSubset(maxUnit: Int): Subset {
            val file = sequenceOf(
                File("src/main/assets/bootstrap_notes.jsonl"),
                File("app/src/main/assets/bootstrap_notes.jsonl")
            ).first { it.exists() }
            val lemmas = mutableListOf<String>()
            val lines = file.useLines { lines ->
                lines.filter { line ->
                    val row = JSONObject(line)
                    if (row.optInt("tier", 0) != 0) return@filter false
                    // Concept lesson notes must always ship: they gate every drill.
                    val included = row.has("conceptId") || (row.has("unit") && row.optInt("unit") <= maxUnit)
                    if (included && row.optString("pos") in CHUNKABLE_POS) {
                        row.optString("lemma").takeIf { it.isNotBlank() }?.let { lemmas += it }
                    }
                    included
                }.toList()
            }
            return Subset(lines.joinToString("\n"), lemmas)
        }
    }
}
