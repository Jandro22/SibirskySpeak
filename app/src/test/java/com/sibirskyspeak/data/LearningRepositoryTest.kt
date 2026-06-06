package com.sibirskyspeak.data

import com.sibirskyspeak.scheduler.FsrsScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningRepositoryTest {
    @Test
    fun jsonImportCreatesCardsQueuesTagsAndAspectPartner() = runTest {
        val fixture = RepoFixture()
        val jsonl = """
            {"russian":"писа́ть","lemma":"писать","pos":"verb","translation":"to write","aspect":"IPF","aspectPartner":"написать","aktionsart":"activity","aktionsartConfidence":"high","exampleSentence":"Я писал письмо."}
            {"russian":"написа́ть","lemma":"написать","pos":"verb","translation":"to write completely","aspect":"PF","aspectPartner":"писать","aktionsart":"accomplishment","aktionsartConfidence":"high","exampleSentence":"Я написал письмо."}
            {"russian":"войска́","lemma":"войска","pos":"noun","translation":"troops","gender":"PL","declensionJson":{"NOM_PL":"войска","GEN_PL":"войск"},"domainFreqRank":10,"exampleSentence":"Войска у границы."}
        """.trimIndent()

        assertEquals(3, fixture.repository.importJsonLines(jsonl))

        val pisat = fixture.notes.getByLemma("писать")
        val napisat = fixture.notes.getByLemma("написать")
        assertNotNull(pisat)
        assertEquals(napisat?.id, pisat?.aspectPartner)
        assertTrue(fixture.cards.cards.any { it.cardType == CardType.ASPECT_SELECT && it.queue == Queue.GRAMMAR })
        assertTrue(fixture.cards.cards.any { it.cardType == CardType.CASE_FILL && it.gramCase == "GEN" && it.gramGender == "PL" && it.gramNumber == "PL" })
        assertTrue(fixture.pairs.pairs.isNotEmpty())
    }

    @Test
    fun seedUsesBootstrapAssetsWhenProvided() = runTest {
        val fixture = RepoFixture(
            bootstrapNotes = """
                {"russian":"санкции","lemma":"санкция","pos":"noun","translation":"sanctions","gender":"F","declensionJson":{"NOM_PL":"санкции","GEN_PL":"санкций"},"domainFreqRank":1}
            """.trimIndent(),
            bootstrapReaderTexts = """
                {"title":"Target","source":"target:test","body":"санкции санкции неизвестно"}
            """.trimIndent()
        )

        fixture.repository.seedIfEmpty()

        assertEquals(1, fixture.notes.count())
        assertEquals(1, fixture.readers.count())
        assertTrue(fixture.cards.cards.any { it.cardType == CardType.CASE_FILL })
        assertEquals("target:test", fixture.readers.texts.first().source)
    }

    @Test
    fun reviewLogsAndIncrementsEncounters() = runTest {
        val fixture = RepoFixture()
        fixture.repository.seedIfEmpty()
        val prompt = fixture.repository.nextPrompt(now = 1_000L)

        assertNotNull(prompt)
        fixture.repository.review(prompt!!.card, Rating.GOOD, now = 2_000L)

        val note = fixture.notes.getById(prompt.card.noteId)
        assertEquals(1, note?.encounterCount)
        assertEquals(1, fixture.logs.logs.size)
        val expectedSource = if (prompt.card.queue == Queue.GRAMMAR) ReviewSource.GRAMMAR_DRILL else ReviewSource.SRS_REVIEW
        assertEquals(expectedSource, fixture.logs.logs.first().source)
    }

    @Test
    fun accuracyGraduatesCaseAndAspectCategoriesAfterThirtyGoodAnswers() = runTest {
        val fixture = RepoFixture()
        fixture.repository.seedIfEmpty()
        val grammarCards = fixture.cards.cards.filter { it.queue == Queue.GRAMMAR }
        val caseCard = grammarCards.first { it.cardType == CardType.CASE_FILL }
        val aspectCard = grammarCards.first { it.cardType == CardType.ASPECT_SELECT }
        repeat(30) {
            fixture.logs.insert(goodLog(caseCard, it.toLong()))
            fixture.logs.insert(goodLog(aspectCard, it.toLong()))
        }

        fixture.repository.sessionPlan(now = 100_000L)

        assertEquals(CardState.GRADUATED, fixture.cards.cards.first { it.id == caseCard.id }.state)
        assertEquals(CardState.GRADUATED, fixture.cards.cards.first { it.id == aspectCard.id }.state)
    }

    @Test
    fun readerLookupLogsEncounterAndVocabGraduatesAtFifteenEncounters() = runTest {
        val fixture = RepoFixture()
        fixture.repository.seedIfEmpty()
        val text = fixture.readers.texts.first()

        repeat(15) {
            fixture.repository.readerLookup("войска", text, now = it.toLong())
        }

        val note = fixture.notes.getByLemma("войска")
        assertEquals(15, note?.encounterCount)
        assertTrue(fixture.logs.logs.any { it.source == ReviewSource.READER_LOOKUP })
        assertTrue(fixture.cards.cards.filter { it.noteId == note?.id && it.queue == Queue.VOCAB }.all { it.state == CardState.GRADUATED })
    }

    @Test
    fun sessionPlanIncludesConfusablePartnerSameSession() = runTest {
        val fixture = RepoFixture()
        fixture.repository.seedIfEmpty()
        val aspectCards = fixture.cards.cards.filter { it.cardType == CardType.ASPECT_SELECT }
        // Each verb note generates NO_CUE + HAS_CUE = 2 cards per verb; 2 verbs = 4 total
        assertEquals(4, aspectCards.size)
        assertTrue("Expected at least one NO_CUE card", aspectCards.any { it.gramContextCue == "NO_CUE" })
        assertTrue("Expected at least one HAS_CUE card", aspectCards.any { it.gramContextCue == "HAS_CUE" })

        val session = fixture.repository.sessionPlan(now = System.currentTimeMillis())

        val ids = session.reviewQueue.map { it.card.id }
        assertTrue("Expected at least one aspect card in session", aspectCards.any { it.id in ids })
    }

    @Test
    fun readerCoverageStatusTokensAndDashboardTrackAuthenticReadiness() = runTest {
        val fixture = RepoFixture()
        fixture.repository.seedIfEmpty()
        fixture.repository.addReaderText(
            "Target sample",
            List(15) { "войска" }.joinToString(" ") + " неизвестно",
            "target:manual"
        )

        val recommendations = fixture.repository.readerTexts()
        val target = recommendations.first { it.text.title == "Target sample" }
        val tokens = fixture.repository.readerTokens(target.text)
        val stats = fixture.repository.dashboardStats()

        assertEquals(ReaderStatus.PRODUCTIVE, target.status)
        assertTrue(target.authenticReady)
        assertEquals(15, tokens.count { it.known })
        assertTrue(tokens.filter { it.known }.any { it.parse != null })
        assertTrue(stats.authenticReady)
        assertEquals(4, stats.noteCount)
        assertFalse(stats.importQualityReport.meetsDesignDocMinimum)
        assertTrue(stats.importQualityReport.warnings.isNotEmpty())
    }

    @Test
    fun importQualityReportPassesDesignDocMinimumForVerifiedDataset() = runTest {
        val fixture = RepoFixture()
        val jsonl = buildString {
            repeat(200) { index ->
                val number = index + 1
                val lemma = if (number == 1) "term" else "term$number"
                appendLine("""{"russian":"$lemma","lemma":"$lemma","pos":"noun","translation":"term $number","gender":"M","declensionJson":{"NOM_SG":"$lemma","GEN_SG":"${lemma}a"},"domainFreqRank":$number,"exampleSentence":"$lemma appears."}""")
            }
            repeat(50) { index ->
                val number = index + 1
                val ipfRank = 201 + index
                val pfRank = 251 + index
                appendLine("""{"russian":"analyze$number","lemma":"analyze$number","pos":"verb","translation":"to analyze $number","aspect":"IPF","aspectPartner":"analyzed$number","aktionsart":"activity","aktionsartConfidence":"high","domainFreqRank":$ipfRank,"exampleSentence":"They analyze$number the report."}""")
                appendLine("""{"russian":"analyzed$number","lemma":"analyzed$number","pos":"verb","translation":"to finish analyzing $number","aspect":"PF","aspectPartner":"analyze$number","aktionsart":"accomplishment","aktionsartConfidence":"high","domainFreqRank":$pfRank,"exampleSentence":"They analyzed$number the report."}""")
            }
        }

        assertEquals(300, fixture.repository.importJsonLines(jsonl))
        fixture.repository.addReaderText("Target", List(30) { "term" }.joinToString(" "), "target:fixture")

        val report = fixture.repository.importQualityReport()

        assertTrue(report.meetsDesignDocMinimum)
        assertEquals(200, report.readyNominalRows)
        assertEquals(100, report.aspectReadyVerbRows)
        assertEquals(100, report.verifiedAktionsartVerbRows)
        assertEquals(1, report.targetTextsAtOrAbove90)
    }

    private fun goodLog(card: Card, time: Long): ReviewLog =
        ReviewLog(
            cardId = card.id,
            reviewDatetime = time,
            rating = Rating.GOOD,
            stateBefore = card.state,
            scheduledDays = 1,
            elapsedDays = 1,
            source = if (card.queue == Queue.GRAMMAR) ReviewSource.GRAMMAR_DRILL else ReviewSource.SRS_REVIEW
        )

    private class RepoFixture(
        bootstrapNotes: String? = null,
        bootstrapReaderTexts: String? = null
    ) {
        val notes = FakeNoteDao()
        val cards = FakeCardDao()
        val logs = FakeReviewLogDao(cards, notes)
        val pairs = FakeConfusablePairDao()
        val readers = FakeReaderTextDao()
        val repository = LearningRepository(
            notes,
            cards,
            logs,
            pairs,
            readers,
            FsrsScheduler(),
            bootstrapNotes = { bootstrapNotes },
            bootstrapReaderTexts = { bootstrapReaderTexts }
        )
    }

    private class FakeNoteDao : NoteDao {
        val notes = mutableListOf<Note>()
        private var nextId = 1L

        override suspend fun insert(note: Note): Long {
            val id = nextId++
            notes += note.copy(id = id)
            return id
        }

        override suspend fun getById(id: Long): Note? = notes.firstOrNull { it.id == id }
        override suspend fun getByLemma(lemma: String): Note? = notes.firstOrNull { it.lemma == lemma }
        override suspend fun getByLemmas(lemmas: List<String>): List<Note> = notes.filter { it.lemma in lemmas }
        override suspend fun count(): Int = notes.size
        override fun observeAll(): Flow<List<Note>> = flowOf(notes)
        override suspend fun getAll(): List<Note> = notes.toList()
        override suspend fun update(note: Note) {
            notes.replaceAll { if (it.id == note.id) note else it }
        }
    }

    private class FakeCardDao : CardDao {
        val cards = mutableListOf<Card>()
        private var nextId = 1L

        override suspend fun getDueCards(now: Long, limit: Int): List<Card> = cards.filter { it.due <= now }.sortedBy { it.due }.take(limit)
        override suspend fun getDueCardsByQueue(now: Long, queue: Queue, limit: Int): List<Card> = cards.filter { it.due <= now && it.queue == queue }.take(limit)
        override suspend fun getOverdueCards(cutoff: Long, limit: Int): List<Card> = cards.filter { it.due <= cutoff }.take(limit)
        override suspend fun getAllDueCards(now: Long): List<Card> = cards.filter { it.due <= now }
        override suspend fun getNewCards(limit: Int): List<Card> = cards.filter { it.state == CardState.NEW }.sortedBy { it.due }.take(limit)
        override suspend fun getByNoteAndType(noteId: Long, cardType: CardType): Card? = cards.firstOrNull { it.noteId == noteId && it.cardType == cardType }
        override suspend fun countDue(now: Long): Int = cards.count { it.due <= now }
        override suspend fun countDueByQueue(now: Long, queue: Queue): Int = cards.count { it.due <= now && it.queue == queue }
        override suspend fun countByQueue(queue: Queue): Int = cards.count { it.queue == queue }
        override suspend fun getGrammarCardsForNounCategory(gramCase: String, gramGender: String, gramNumber: String): List<Card> =
            cards.filter { it.queue == Queue.GRAMMAR && it.gramCase == gramCase && it.gramGender == gramGender && it.gramNumber == gramNumber }
        override suspend fun getGrammarCardsForNotes(noteIds: List<Long>): List<Card> = cards.filter { it.queue == Queue.GRAMMAR && it.noteId in noteIds }
        override suspend fun getAspectCards(): List<Card> = cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.ASPECT_SELECT }
        override suspend fun getAllGrammarCards(): List<Card> = cards.filter { it.queue == Queue.GRAMMAR }
        override suspend fun getCaseDrillCards(gramCase: String, gramGender: String, gramNumber: String, limit: Int): List<Card> =
            cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.CASE_FILL && it.gramCase == gramCase && it.gramGender == gramGender && it.gramNumber == gramNumber }.take(limit)
        override suspend fun getGrammarDrillCards(limit: Int): List<Card> = cards.filter { it.queue == Queue.GRAMMAR }.take(limit)
        override suspend fun getCardsForNote(noteId: Long): List<Card> = cards.filter { it.noteId == noteId }
        override suspend fun update(card: Card) {
            cards.replaceAll { if (it.id == card.id) card else it }
        }
        override suspend fun insert(card: Card): Long {
            val id = nextId++
            cards += card.copy(id = id)
            return id
        }
        override suspend fun insertAll(cards: List<Card>): List<Long> = cards.map { insert(it) }
    }

    private class FakeReviewLogDao(
        private val cards: FakeCardDao,
        private val notes: FakeNoteDao
    ) : ReviewLogDao {
        val logs = mutableListOf<ReviewLog>()
        private var nextId = 1L

        override suspend fun insert(log: ReviewLog) {
            logs += log.copy(id = nextId++)
        }

        override suspend fun countSince(since: Long): Int = logs.count { it.reviewDatetime >= since }
        override suspend fun nounCategoryRatings(gramCase: String, gramGender: String, gramNumber: String, limit: Int): List<Rating> =
            logs.sortedByDescending { it.reviewDatetime }
                .filter { log ->
                    val card = cards.cards.firstOrNull { it.id == log.cardId }
                    card?.gramCase == gramCase && card.gramGender == gramGender && card.gramNumber == gramNumber
                }
                .take(limit)
                .map { it.rating }
        override suspend fun aspectCategoryRatings(aktionsart: String, aspect: String, contextCue: String, limit: Int): List<Rating> =
            logs.sortedByDescending { it.reviewDatetime }
                .filter { log ->
                    val card = cards.cards.firstOrNull { it.id == log.cardId }
                    val note = card?.let { notes.notes.firstOrNull { note -> note.id == it.noteId } }
                    note?.aktionsart == aktionsart && note.aspect == aspect && card.gramContextCue == contextCue
                }
                .take(limit)
                .map { it.rating }
    }

    private class FakeConfusablePairDao : ConfusablePairDao {
        val pairs = mutableListOf<ConfusablePair>()
        private var nextId = 1L
        override suspend fun insert(pair: ConfusablePair): Long {
            val id = nextId++
            pairs += pair.copy(id = id)
            return id
        }
        override suspend fun getForNote(noteId: Long): List<ConfusablePair> = pairs.filter { it.firstNoteId == noteId || it.secondNoteId == noteId }
        override suspend fun getAll(): List<ConfusablePair> = pairs
    }

    private class FakeReaderTextDao : ReaderTextDao {
        val texts = mutableListOf<ReaderText>()
        private var nextId = 1L
        override suspend fun insert(text: ReaderText): Long {
            val id = nextId++
            texts += text.copy(id = id)
            return id
        }
        override suspend fun count(): Int = texts.size
        override suspend fun getAll(): List<ReaderText> = texts
        override suspend fun getById(id: Long): ReaderText? = texts.firstOrNull { it.id == id }
    }
}
