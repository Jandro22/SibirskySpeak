package com.sibirskyspeak.data

import com.sibirskyspeak.scheduler.FsrsScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertFalse(fixture.cards.cards.any { it.cardType == CardType.CASE_FILL && it.gramCase == "NOM" })
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
    fun importCreatesPastTenseVerbFormDrillsOnly() = runTest {
        val fixture = RepoFixture()
        val jsonl = """
            {"russian":"\u043f\u0438\u0441\u0430\u0442\u044c","lemma":"\u043f\u0438\u0441\u0430\u0442\u044c","pos":"verb","translation":"to write","aspect":"IPF","aktionsart":"activity","aktionsartConfidence":"high","domainFreqRank":1,"exampleSentence":"\u041e\u043d\u0430 \u043f\u0438\u0441\u0430\u043b\u0430 \u043f\u0438\u0441\u044c\u043c\u043e."}
        """.trimIndent()

        fixture.repository.importJsonLines(jsonl)

        val note = fixture.notes.getByLemma("\u043f\u0438\u0441\u0430\u0442\u044c")
        val verbForms = fixture.cards.cards.filter { it.noteId == note?.id && it.cardType == CardType.VERB_FORM }
        // Past tense is regular and trustworthy.
        assertTrue(verbForms.any { it.gramContextCue == "PAST_F" })
        assertTrue(verbForms.any { it.gramContextCue == "PAST_PL" })
        assertTrue(verbForms.all { it.queue == Queue.GRAMMAR })
        // No present-tense drills: \u043f\u0438\u0441\u0430\u0442\u044c is irregular (\u043f\u0438\u0448\u0443), so deriving present
        // forms would teach a wrong answer. We never generate them.
        assertTrue(verbForms.none { it.gramContextCue?.startsWith("PRES_") == true })
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
    fun sessionPlanSuggestsDueReviewsBeforeNewCards() = runTest {
        val fixture = RepoFixture()
        val dueNoteId = fixture.notes.insert(Note(russian = "due", lemma = "due", translation = "due", partOfSpeech = "noun"))
        val newNoteId = fixture.notes.insert(Note(russian = "new", lemma = "new", translation = "new", partOfSpeech = "noun"))
        val newCardId = fixture.cards.insert(Card(noteId = newNoteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, due = 1L))
        val dueCardId = fixture.cards.insert(
            Card(
                noteId = dueNoteId,
                cardType = CardType.RU_TO_MEANING,
                queue = Queue.VOCAB,
                due = 100L,
                state = CardState.REVIEW,
                lastReview = 0L
            )
        )

        val session = fixture.repository.sessionPlan(now = 200L)

        assertEquals(dueCardId, session.reviewQueue.first().card.id)
        assertFalse(session.reviewQueue.drop(1).any { it.card.id == newCardId })
    }

    @Test
    fun sessionPlanPairsOnlyMatchingAspectCue() = runTest {
        val fixture = RepoFixture()
        val firstNoteId = fixture.notes.insert(
            Note(
                russian = "write",
                lemma = "write",
                translation = "write",
                partOfSpeech = "verb",
                aspect = "IPF",
                aktionsart = "activity"
            )
        )
        val secondNoteId = fixture.notes.insert(
            Note(
                russian = "finish-write",
                lemma = "finish-write",
                translation = "finish writing",
                partOfSpeech = "verb",
                aspect = "PF",
                aktionsart = "accomplishment"
            )
        )
        fixture.pairs.insert(ConfusablePair(firstNoteId = firstNoteId, secondNoteId = secondNoteId, reason = "aspect_partner"))
        val firstHasCue = fixture.cards.insert(
            Card(
                noteId = firstNoteId,
                cardType = CardType.ASPECT_SELECT,
                queue = Queue.GRAMMAR,
                due = 100L,
                state = CardState.REVIEW,
                lastReview = 0L,
                gramContextCue = "HAS_CUE"
            )
        )
        val secondNoCue = fixture.cards.insert(
            Card(
                noteId = secondNoteId,
                cardType = CardType.ASPECT_SELECT,
                queue = Queue.GRAMMAR,
                due = 100L,
                state = CardState.REVIEW,
                lastReview = 0L,
                gramContextCue = "NO_CUE"
            )
        )
        fixture.cards.insert(
            Card(
                noteId = secondNoteId,
                cardType = CardType.ASPECT_SELECT,
                queue = Queue.GRAMMAR,
                due = 170L,
                state = CardState.REVIEW,
                lastReview = 0L,
                gramContextCue = "HAS_CUE"
            )
        )
        val secondHasCue = fixture.cards.insert(
            Card(
                noteId = secondNoteId,
                cardType = CardType.ASPECT_SELECT,
                queue = Queue.GRAMMAR,
                due = 150L,
                state = CardState.REVIEW,
                lastReview = 0L,
                gramContextCue = "HAS_CUE"
            )
        )

        val ids = fixture.repository.sessionPlan(now = 200L).reviewQueue.map { it.card.id }

        assertEquals(listOf(firstHasCue, secondHasCue, secondNoCue), ids.take(3))
    }

    @Test
    fun fullStateImportRestoresEachCaseCardByGrammarVariant() = runTest {
        val fixture = RepoFixture()
        val jsonl = """{"russian":"term","lemma":"term","pos":"noun","translation":"term","gender":"M","declensionJson":{"NOM_SG":"term","GEN_SG":"terma","DAT_SG":"termu"},"_cards":[{"cardType":"CASE_FILL","queue":"GRAMMAR","state":"REVIEW","stability":3.0,"difficulty":4.0,"elapsedDays":1,"scheduledDays":3,"reps":7,"lapses":0,"due":3000,"lastReview":1000,"consecutiveCorrect":2,"gramCase":"GEN","gramGender":"M","gramNumber":"SG"},{"cardType":"CASE_FILL","queue":"GRAMMAR","state":"RELEARNING","stability":1.5,"difficulty":8.0,"elapsedDays":2,"scheduledDays":0,"reps":9,"lapses":2,"due":2000,"lastReview":1500,"consecutiveCorrect":0,"gramCase":"DAT","gramGender":"M","gramNumber":"SG"}]}"""

        assertEquals(1, fixture.repository.importJsonLines(jsonl))

        val note = fixture.notes.getByLemma("term")
        val caseCards = fixture.cards.cards.filter { it.noteId == note?.id && it.cardType == CardType.CASE_FILL }
        val gen = caseCards.first { it.gramCase == "GEN" }
        val dat = caseCards.first { it.gramCase == "DAT" }
        assertEquals(CardState.REVIEW, gen.state)
        assertEquals(7, gen.reps)
        assertEquals(3000L, gen.due)
        assertEquals(CardState.RELEARNING, dat.state)
        assertEquals(9, dat.reps)
        assertEquals(2000L, dat.due)
    }

    @Test
    fun readerCoverageStatusTokensAndDashboardTrackAuthenticReadiness() = runTest {
        val fixture = RepoFixture()
        fixture.repository.seedIfEmpty()
        val troopNote = fixture.notes.notes.first { it.translation == "troops" }
        val troopCard = fixture.cards.cards.first { it.noteId == troopNote.id && it.queue == Queue.VOCAB }
        fixture.repository.review(troopCard, Rating.GOOD, now = 10_000L)
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
        val termNote = fixture.notes.notes.first { it.lemma == "term" }
        val termCard = fixture.cards.cards.first { it.noteId == termNote.id && it.queue == Queue.VOCAB }
        fixture.repository.review(termCard, Rating.GOOD, now = 10_000L)
        fixture.repository.addReaderText("Target", List(30) { "term" }.joinToString(" "), "target:fixture")

        val report = fixture.repository.importQualityReport()

        assertTrue(report.meetsDesignDocMinimum)
        assertEquals(200, report.readyNominalRows)
        assertEquals(100, report.aspectReadyVerbRows)
        assertEquals(100, report.verifiedAktionsartVerbRows)
        assertEquals(1, report.targetTextsAtOrAbove90)
    }

    @Test
    fun newCardSessionThrottlesInterleavesAndLeadsWithComprehension() = runTest {
        val fixture = RepoFixture()
        // 30 simple noun notes => lots of new cards, each with two vocab cards
        // (recognition + production) and no grammar/example.
        val jsonl = (1..30).joinToString("\n") { i ->
            """{"russian":"слово$i","lemma":"слово$i","pos":"noun","translation":"word $i"}"""
        }
        fixture.repository.importJsonLines(jsonl)

        val session = fixture.repository.sessionPlan(now = 0L).reviewQueue

        // Throttled to the default new-cards-per-day budget.
        assertTrue("expected throttle to <= 15, was ${session.size}", session.size <= 15)
        assertTrue(session.isNotEmpty())
        // No word is drilled twice in one session (one vocab card per note).
        val perNote = session.groupBy { it.card.noteId }
        assertTrue("a note appeared more than once", perNote.values.all { it.size == 1 })
        // Comprehension-first: recognition cards lead; production is deferred.
        assertTrue(session.all { it.card.cardType == CardType.RU_TO_MEANING })
    }

    @Test
    fun emptyDatabaseAutoRestoresFromBackupInsteadOfReseeding() = runTest {
        // Source deck with real review state, captured into a backup.
        var backup: String? = null
        val source = RepoFixture(writeBackup = { backup = it })
        source.repository.seedIfEmpty()
        val prompt = source.repository.nextPrompt(now = 1_000L)
        source.repository.review(prompt!!.card, Rating.GOOD, now = 2_000L)
        assertTrue(source.repository.backupNow())
        assertNotNull(backup)
        val sourceNoteCount = source.notes.count()

        // A fresh, empty install (simulating a destructive wipe) restores the backup
        // rather than re-seeding bootstrap data.
        val recovered = RepoFixture(
            bootstrapNotes = """{"russian":"x","lemma":"x","pos":"noun","translation":"x"}""",
            restoreBackup = { backup }
        )
        recovered.repository.seedIfEmpty()

        assertEquals(sourceNoteCount, recovered.notes.count())
        // Restored, not bootstrapped: the single bootstrap note is not what we got.
        assertNull(recovered.notes.getByLemma("x"))
        // SRS history survived (a reviewed card carries reps > 0).
        assertTrue(recovered.cards.cards.any { it.reps > 0 })
    }

    @Test
    fun backupIsNotWrittenForEmptyDatabase() = runTest {
        var backup: String? = null
        val fixture = RepoFixture(writeBackup = { backup = it })
        // No seed: DB is empty, so we must not overwrite a (potentially good) backup.
        assertFalse(fixture.repository.backupNow())
        assertNull(backup)
    }

    @Test
    fun adjectivesGetAgreementDrillsAndNounsGetGenderDrills() = runTest {
        val fixture = RepoFixture()
        val jsonl = """
            {"russian":"я́дерный","lemma":"ядерный","pos":"adjective","translation":"nuclear","gender":"M","declensionJson":{"NOM_SG":"ядерный","FEM_NOM":"ядерная","NEUT_NOM":"ядерное","PL_NOM":"ядерные"},"domainFreqRank":1,"exampleSentence":"Ядерный фактор важен."}
            {"russian":"госуда́рство","lemma":"государство","pos":"noun","translation":"state","gender":"N","declensionJson":{"NOM_SG":"государство","GEN_SG":"государства"},"domainFreqRank":2,"exampleSentence":"Государство большое."}
        """.trimIndent()
        fixture.repository.importJsonLines(jsonl)

        val adj = fixture.notes.getByLemma("ядерный")
        val adjCards = fixture.cards.cards.filter { it.noteId == adj?.id && it.cardType == CardType.ADJ_AGREE }
        assertEquals(setOf("FEM", "NEUT", "PL"), adjCards.mapNotNull { it.gramContextCue }.toSet())

        val noun = fixture.notes.getByLemma("государство")
        val genderCards = fixture.cards.cards.filter { it.noteId == noun?.id && it.cardType == CardType.GENDER_ID }
        assertEquals(1, genderCards.size)
        assertEquals("N", genderCards.first().gramGender)
    }

    @Test
    fun suspendedCardsAreSkippedByEveryQueue() = runTest {
        val fixture = RepoFixture()
        fixture.repository.seedIfEmpty()
        val due = fixture.cards.insert(
            Card(noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, due = 50L, state = CardState.REVIEW, lastReview = 0L)
        )
        val card = fixture.cards.cards.first { it.id == due }
        assertTrue(fixture.repository.sessionPlan(now = 100L).reviewQueue.any { it.card.id == due })

        fixture.repository.suspendCard(card)

        assertFalse(fixture.repository.sessionPlan(now = 100L).reviewQueue.any { it.card.id == due })
        assertTrue(fixture.cards.cards.first { it.id == due }.suspended)
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
        bootstrapReaderTexts: String? = null,
        restoreBackup: (suspend () -> String?)? = null,
        writeBackup: (suspend (String) -> Unit)? = null
    ) {
        val notes = FakeNoteDao()
        val cards = FakeCardDao(notes)
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
            bootstrapReaderTexts = { bootstrapReaderTexts },
            restoreBackup = restoreBackup,
            writeBackup = writeBackup
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

        override suspend fun insertAll(notes: List<Note>): List<Long> = notes.map { insert(it) }

        override suspend fun getById(id: Long): Note? = notes.firstOrNull { it.id == id }
        override suspend fun getByLemma(lemma: String): Note? = notes.firstOrNull { it.lemma == lemma }
        override suspend fun getByLemmas(lemmas: List<String>): List<Note> = notes.filter { it.lemma in lemmas }
        override suspend fun count(): Int = notes.size
        override fun observeAll(): Flow<List<Note>> = flowOf(notes)
        override suspend fun getAll(): List<Note> = notes.toList()
        override suspend fun search(query: String, limit: Int): List<Note> =
            notes.filter {
                it.russian.contains(query, true) || it.lemma.contains(query, true) || it.translation.contains(query, true)
            }.take(limit)
        override suspend fun update(note: Note) {
            notes.replaceAll { if (it.id == note.id) note else it }
        }
    }

    private class FakeCardDao(private val notes: FakeNoteDao) : CardDao {
        val cards = mutableListOf<Card>()
        private var nextId = 1L

        override suspend fun getDueCards(now: Long, limit: Int): List<Card> =
            cards.filter { it.due <= now && it.state != CardState.NEW && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
        override suspend fun getDueCardsByQueue(now: Long, queue: Queue, limit: Int): List<Card> =
            cards.filter { it.due <= now && it.queue == queue && it.state != CardState.NEW && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
        override suspend fun getOverdueCards(cutoff: Long, limit: Int): List<Card> =
            cards.filter { it.due <= cutoff && it.state != CardState.NEW && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
        override suspend fun getAllDueCards(now: Long): List<Card> =
            cards.filter { it.due <= now && it.state != CardState.NEW && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id })
        override suspend fun getNewCards(limit: Int): List<Card> =
            cards.filter { it.state == CardState.NEW && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
        override suspend fun getByNoteAndType(noteId: Long, cardType: CardType): Card? = cards.firstOrNull { it.noteId == noteId && it.cardType == cardType }
        override suspend fun countDue(now: Long): Int = cards.count { it.due <= now && it.state != CardState.NEW && !it.suspended }
        override suspend fun countDueByQueue(now: Long, queue: Queue): Int = cards.count { it.due <= now && it.queue == queue && it.state != CardState.NEW }
        override suspend fun countByQueue(queue: Queue): Int = cards.count { it.queue == queue }
        override suspend fun getGrammarCardsForNounCategory(gramCase: String, gramGender: String, gramNumber: String): List<Card> =
            cards.filter { it.queue == Queue.GRAMMAR && it.gramCase == gramCase && it.gramGender == gramGender && it.gramNumber == gramNumber }
        override suspend fun getGrammarCardsForNotes(noteIds: List<Long>): List<Card> = cards.filter { it.queue == Queue.GRAMMAR && it.noteId in noteIds }
        override suspend fun getAspectCards(): List<Card> = cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.ASPECT_SELECT }
        override suspend fun getAllGrammarCards(): List<Card> = cards.filter { it.queue == Queue.GRAMMAR }
        override suspend fun getCaseCategoryKeys(): List<CaseCategoryRow> =
            cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.CASE_FILL && it.gramCase != null && it.gramGender != null && it.gramNumber != null }
                .map { CaseCategoryRow(it.gramCase.orEmpty(), it.gramGender.orEmpty(), it.gramNumber.orEmpty()) }
                .distinct()
        override suspend fun getAspectCategoryKeys(): List<AspectCategoryRow> =
            cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.ASPECT_SELECT && it.gramContextCue != null }
                .mapNotNull { card ->
                    val note = notes.notes.firstOrNull { it.id == card.noteId } ?: return@mapNotNull null
                    val aktionsart = note.aktionsart ?: return@mapNotNull null
                    val aspect = note.aspect ?: return@mapNotNull null
                    AspectCategoryRow(aktionsart, aspect, card.gramContextCue.orEmpty())
                }
                .distinct()
        override suspend fun getVerbFormCategoryKeys(): List<String> =
            cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.VERB_FORM && it.gramContextCue != null }
                .map { it.gramContextCue.orEmpty() }
                .distinct()
        override suspend fun getCaseDrillCards(gramCase: String, gramGender: String, gramNumber: String, limit: Int): List<Card> =
            cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.CASE_FILL && it.gramCase == gramCase && it.gramGender == gramGender && it.gramNumber == gramNumber }
                .sortedWith(compareBy<Card> { it.due }.thenBy { it.id })
                .take(limit)
        override suspend fun getVerbFormCards(formKey: String, limit: Int): List<Card> =
            cards.filter { it.queue == Queue.GRAMMAR && it.cardType == CardType.VERB_FORM && it.gramContextCue == formKey }
                .sortedWith(compareBy<Card> { it.due }.thenBy { it.id })
                .take(limit)
        override suspend fun getGrammarDrillCards(limit: Int): List<Card> =
            cards.filter { it.queue == Queue.GRAMMAR }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
        override suspend fun getCardsForNote(noteId: Long): List<Card> = cards.filter { it.noteId == noteId }
        override suspend fun getAllVocabCards(): List<Card> = cards.filter { it.queue == Queue.VOCAB }
        override suspend fun getKnownVocabNoteIds(): List<Long> =
            cards.filter {
                it.queue == Queue.VOCAB &&
                    (it.state == CardState.GRADUATED || it.reps > 0 || it.consecutiveCorrect > 0 || it.lastReview != null)
            }.map { it.noteId }.distinct()
        override suspend fun update(card: Card) {
            cards.replaceAll { if (it.id == card.id) card else it }
        }
        override suspend fun updateAll(cards: List<Card>) {
            cards.forEach { update(it) }
        }
        override suspend fun graduateCaseCategory(gramCase: String, gramGender: String, gramNumber: String): Int {
            var changed = 0
            cards.replaceAll { card ->
                if (card.queue == Queue.GRAMMAR && card.cardType == CardType.CASE_FILL &&
                    card.gramCase == gramCase && card.gramGender == gramGender && card.gramNumber == gramNumber &&
                    card.state != CardState.GRADUATED
                ) {
                    changed += 1
                    card.copy(state = CardState.GRADUATED)
                } else card
            }
            return changed
        }
        override suspend fun graduateAspectCategory(aktionsart: String, aspect: String, contextCue: String): Int {
            var changed = 0
            cards.replaceAll { card ->
                val note = notes.notes.firstOrNull { it.id == card.noteId }
                if (card.queue == Queue.GRAMMAR && card.cardType == CardType.ASPECT_SELECT &&
                    card.gramContextCue == contextCue && note?.aktionsart == aktionsart && note.aspect == aspect &&
                    card.state != CardState.GRADUATED
                ) {
                    changed += 1
                    card.copy(state = CardState.GRADUATED)
                } else card
            }
            return changed
        }
        override suspend fun graduateVerbFormCategory(formKey: String): Int {
            var changed = 0
            cards.replaceAll { card ->
                if (card.queue == Queue.GRAMMAR && card.cardType == CardType.VERB_FORM &&
                    card.gramContextCue == formKey && card.state != CardState.GRADUATED
                ) {
                    changed += 1
                    card.copy(state = CardState.GRADUATED)
                } else card
            }
            return changed
        }
        override suspend fun graduateVocabForEncounteredNotes(minEncounterCount: Int): Int {
            val eligible = notes.notes.filter { it.encounterCount >= minEncounterCount }.map { it.id }.toSet()
            var changed = 0
            cards.replaceAll { card ->
                if (card.queue == Queue.VOCAB && card.noteId in eligible && card.state != CardState.GRADUATED) {
                    changed += 1
                    card.copy(state = CardState.GRADUATED)
                } else card
            }
            return changed
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
        override suspend fun countAll(): Int = logs.size
        override suspend fun countNewIntroducedSince(since: Long): Int =
            logs.count { it.reviewDatetime >= since && it.stateBefore == CardState.NEW }
        override suspend fun deleteLatestForCard(cardId: Long) {
            val last = logs.filter { it.cardId == cardId }.maxByOrNull { it.id } ?: return
            logs.remove(last)
        }
        override suspend fun reviewDayBuckets(tzOffset: Long, dayMillis: Long): List<Long> =
            logs.map { (it.reviewDatetime + tzOffset) / dayMillis }.distinct().sortedDescending()
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
        override suspend fun verbFormCategoryRatings(formKey: String, limit: Int): List<Rating> =
            logs.sortedByDescending { it.reviewDatetime }
                .filter { log ->
                    val card = cards.cards.firstOrNull { it.id == log.cardId }
                    card?.cardType == CardType.VERB_FORM && card.gramContextCue == formKey
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
        override suspend fun insertAll(texts: List<ReaderText>): List<Long> = texts.map { insert(it) }
        override suspend fun count(): Int = texts.size
        override suspend fun getAll(): List<ReaderText> = texts
        override suspend fun getById(id: Long): ReaderText? = texts.firstOrNull { it.id == id }
    }
}
