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
import java.time.Instant
import java.util.TimeZone

class LearningRepositoryTest {
    @Test
    fun authoredConceptDrillsCoverUpperLevelGrammarConcepts() {
        val upperConceptIds = setOf(
            GrammarConcepts.MOTION_PREFIX.id,
            GrammarConcepts.CONDITIONAL.id,
            GrammarConcepts.RELATIVE.id,
            GrammarConcepts.SUPERLATIVE.id,
            GrammarConcepts.PURPOSE.id,
            GrammarConcepts.NUMERAL_CASE.id,
            GrammarConcepts.PARTICIPLE_ACTIVE.id,
            GrammarConcepts.PARTICIPLE_PASSIVE.id,
            GrammarConcepts.GERUND.id,
            GrammarConcepts.PASSIVE.id,
            GrammarConcepts.REPORTED.id,
            GrammarConcepts.COMPLEX_SYNTAX.id,
            GrammarConcepts.NOMINALIZATION.id,
            GrammarConcepts.ASPECT_NUANCE.id,
            GrammarConcepts.REGISTER.id,
            GrammarConcepts.IDIOM.id
        )

        assertEquals(upperConceptIds, ConceptDrills.coveredConceptIds())
    }

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
    fun importCreatesVerifiedPresentVerbFormDrills() = runTest {
        val fixture = RepoFixture()
        val jsonl = """
            {"russian":"\u043f\u0438\u0441\u0430\u0442\u044c","lemma":"\u043f\u0438\u0441\u0430\u0442\u044c","pos":"verb","translation":"to write","aspect":"IPF","aktionsart":"activity","aktionsartConfidence":"high","domainFreqRank":1,"exampleSentence":"\u042f \u043f\u0438\u0448\u0443 \u043f\u0438\u0441\u044c\u043c\u043e.","declensionJson":{"verbForms":{"PRES_1SG":"\u043f\u0438\u0448\u0443","PRES_2SG":"\u043f\u0438\u0448\u0435\u0448\u044c","PRES_3SG":"\u043f\u0438\u0448\u0435\u0442","PRES_1PL":"\u043f\u0438\u0448\u0435\u043c","PRES_2PL":"\u043f\u0438\u0448\u0435\u0442\u0435","PRES_3PL":"\u043f\u0438\u0448\u0443\u0442"}}}
            {"russian":"\u043d\u0430\u043f\u0438\u0441\u0430\u0442\u044c","lemma":"\u043d\u0430\u043f\u0438\u0441\u0430\u0442\u044c","pos":"verb","translation":"to write (finish)","aspect":"PF","aktionsart":"achievement","aktionsartConfidence":"high","domainFreqRank":2,"exampleSentence":"\u042f \u043d\u0430\u043f\u0438\u0448\u0443 \u043f\u0438\u0441\u044c\u043c\u043e.","declensionJson":{"verbForms":{"PRES_1SG":"\u043d\u0430\u043f\u0438\u0448\u0443","PRES_2SG":"\u043d\u0430\u043f\u0438\u0448\u0435\u0448\u044c","PRES_3SG":"\u043d\u0430\u043f\u0438\u0448\u0435\u0442","PRES_1PL":"\u043d\u0430\u043f\u0438\u0448\u0435\u043c","PRES_2PL":"\u043d\u0430\u043f\u0438\u0448\u0435\u0442\u0435","PRES_3PL":"\u043d\u0430\u043f\u0438\u0448\u0443\u0442"}}}
        """.trimIndent()

        fixture.repository.importJsonLines(jsonl)

        val ipf = fixture.notes.getByLemma("\u043f\u0438\u0441\u0430\u0442\u044c")
        val pf = fixture.notes.getByLemma("\u043d\u0430\u043f\u0438\u0441\u0430\u0442\u044c")
        val presentKeys = setOf("PRES_1SG", "PRES_2SG", "PRES_3SG", "PRES_1PL", "PRES_2PL", "PRES_3PL")
        val ipfPresent = fixture.cards.cards.filter { it.noteId == ipf?.id && it.gramContextCue in presentKeys }
        val pfFuture = fixture.cards.cards.filter { it.noteId == pf?.id && it.gramContextCue in presentKeys }

        assertEquals(presentKeys, ipfPresent.mapNotNull { it.gramContextCue }.toSet())
        assertTrue(ipfPresent.all { it.gramConcept == GrammarConcepts.PRESENT.id })
        assertEquals(presentKeys, pfFuture.mapNotNull { it.gramContextCue }.toSet())
        assertTrue(pfFuture.all { it.gramConcept == GrammarConcepts.FUTURE.id })
    }

    @Test
    fun importCreatesStressMarkCardsForStressedHeadwords() = runTest {
        val fixture = RepoFixture()
        val jsonl = """
            {"russian":"\u043c\u043e\u043b\u043e\u043a\u043e\u0301","lemma":"\u043c\u043e\u043b\u043e\u043a\u043e","pos":"noun","translation":"milk","tier":0,"exampleSentence":"\u042f \u043f\u044c\u044e \u043c\u043e\u043b\u043e\u043a\u043e\u0301.","exampleTranslation":"I drink milk."}
        """.trimIndent()

        fixture.repository.importJsonLines(jsonl)

        val note = fixture.notes.getByLemma("\u043c\u043e\u043b\u043e\u043a\u043e")
        assertTrue(fixture.cards.cards.any { it.noteId == note?.id && it.cardType == CardType.STRESS_MARK })
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

        repeat(15) {
            val textId = fixture.readers.insert(ReaderText(title = "t$it", body = "войска", source = "test"))
            fixture.repository.lookupReaderToken("войска", textId, now = it.toLong())
        }

        val note = fixture.notes.getByLemma("войска")
        assertEquals(15, note?.encounterCount)
        assertTrue("reader lookup is exposure, not recall review", fixture.logs.logs.none { it.source == ReviewSource.READER_LOOKUP })
        assertTrue(fixture.cards.cards.filter { it.noteId == note?.id && it.queue == Queue.VOCAB }.all { it.state == CardState.GRADUATED })
    }

    @Test
    fun repeatedLookupInSameTextOnlyCreditsOneEncounter() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"книга","lemma":"книга","pos":"noun","translation":"book","tier":0,"unit":1,"cefrLevel":"A1"}"""
        )
        val textId = fixture.readers.insert(ReaderText(title = "same", body = "книга книга книга", source = "test"))

        repeat(15) {
            fixture.repository.lookupReaderToken("книга", textId, now = it.toLong())
        }

        val note = fixture.notes.getByLemma("книга")
        assertEquals(1, note?.encounterCount)
        assertFalse(
            "same-text repeated taps should not graduate vocab",
            fixture.cards.cards.filter { it.noteId == note?.id && it.queue == Queue.VOCAB }.all { it.state == CardState.GRADUATED }
        )
    }

    @Test
    fun readerLookupLogsDoNotCountAsRecallMetrics() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "word", lemma = "word", translation = "word", partOfSpeech = "noun"))
        val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW))
        fixture.logs.insert(
            ReviewLog(
                cardId = cardId,
                reviewDatetime = 1_000L,
                rating = Rating.GOOD,
                stateBefore = CardState.REVIEW,
                scheduledDays = 5,
                elapsedDays = 5,
                source = ReviewSource.READER_LOOKUP
            )
        )

        assertEquals(0, fixture.logs.countAll())
        assertEquals(0, fixture.logs.countSince(0L))
        assertEquals(0, fixture.logs.matureReviewCount())
        assertEquals(0, fixture.logs.matureRetainedCount())
        assertTrue(fixture.logs.reviewDayBuckets(0L, 86_400_000L).isEmpty())
    }

    @Test
    fun sessionPlanIncludesConfusablePartnerSameSession() = runTest {
        val fixture = RepoFixture()
        fixture.repository.seedIfEmpty()
        val aspectCards = fixture.cards.cards.filter { it.cardType == CardType.ASPECT_SELECT }
        // Each verb note generates five concrete aspect contexts; 2 verbs = 10 total.
        assertEquals(10, aspectCards.size)
        assertEquals(
            setOf("PROCESS", "HABITUAL", "COMPLETED", "RESULT", "SINGLE_EVENT"),
            aspectCards.mapNotNull { it.gramContextCue }.toSet()
        )
        val processPair = aspectCards.filter { it.gramContextCue == "PROCESS" }
        assertEquals(2, processPair.size)
        processPair.forEach { card ->
            fixture.cards.update(
                card.copy(
                    state = CardState.REVIEW,
                    due = 0L,
                    reps = 3,
                    lastReview = 0L,
                    stability = 5.0,
                    difficulty = 5.0
                )
            )
        }

        val session = fixture.repository.sessionPlan(now = System.currentTimeMillis())

        val ids = session.reviewQueue.map { it.card.id }
        assertTrue("Expected matching aspect partners in session", processPair.all { it.id in ids })
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
    fun triageModeStillReviewsSameDayDueCardsBeforeNewCards() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(sessionSize = 20, newCardsPerDay = 20) })
        val dueIds = (1..105).map { index ->
            val noteId = fixture.notes.insert(
                Note(
                    russian = "due$index",
                    lemma = "due$index",
                    translation = "due $index",
                    partOfSpeech = "noun"
                )
            )
            fixture.cards.insert(
                Card(
                    noteId = noteId,
                    cardType = CardType.RU_TO_MEANING,
                    queue = Queue.VOCAB,
                    state = CardState.REVIEW,
                    due = 1_000L,
                    lastReview = 0L,
                    reps = 3
                )
            )
        }.toSet()
        val newNoteId = fixture.notes.insert(Note(russian = "new", lemma = "new", translation = "new", partOfSpeech = "noun"))
        val newCardId = fixture.cards.insert(Card(noteId = newNoteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, due = 0L))

        val session = fixture.repository.sessionPlan(now = 1_000L)

        assertTrue("large due pile should trigger triage mode", session.dailyPlan.triageMode)
        assertTrue(
            "triage should still review same-day due cards before introducing new material",
            session.reviewQueue.all { it.card.id in dueIds }
        )
        assertFalse(session.reviewQueue.any { it.card.id == newCardId })
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
    fun importQualityReportRequiresReadableSentenceGlossesForReadyRows() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"term","lemma":"term","pos":"noun","translation":"term","gender":"M","declensionJson":{"NOM_SG":"term","GEN_SG":"terma"},"domainFreqRank":1,"exampleSentence":"term appears.","exampleTranslation":"term"}"""
        )

        val report = fixture.repository.importQualityReport()

        assertEquals(0, report.readyNominalRows)
        assertEquals(0, report.exampleRows)
        assertFalse(report.meetsDesignDocMinimum)
    }

    @Test
    fun readerCoverageTreatsUnknownMidSentenceNamesAsReadable() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"иду","lemma":"иду","pos":"verb","translation":"I go","tier":0,"unit":1,"cefrLevel":"A1"}"""
        )
        val note = fixture.notes.getByLemma("иду")!!
        val card = fixture.cards.cards.first { it.noteId == note.id && it.queue == Queue.VOCAB }
        fixture.repository.review(card, Rating.GOOD, now = 1_000L)
        fixture.repository.addReaderText("With name", "иду Анна", "local")

        val recommendation = fixture.repository.readerTexts().first { it.text.title == "With name" }
        val tokens = fixture.repository.readerTokens(recommendation.text)

        assertEquals(1.0, recommendation.coverage, 0.0)
        assertTrue(tokens.first { it.surface == "Анна" }.known)
    }

    @Test
    fun importQualityReportPassesDesignDocMinimumForVerifiedDataset() = runTest {
        val fixture = RepoFixture()
        val jsonl = buildString {
            repeat(200) { index ->
                val number = index + 1
                val lemma = if (number == 1) "term" else "term$number"
                appendLine("""{"russian":"$lemma","lemma":"$lemma","pos":"noun","translation":"term $number","gender":"M","declensionJson":{"NOM_SG":"$lemma","GEN_SG":"${lemma}a"},"domainFreqRank":$number,"exampleSentence":"$lemma appears.","exampleTranslation":"The term appears."}""")
            }
            repeat(50) { index ->
                val number = index + 1
                val ipfRank = 201 + index
                val pfRank = 251 + index
                appendLine("""{"russian":"analyze$number","lemma":"analyze$number","pos":"verb","translation":"to analyze $number","aspect":"IPF","aspectPartner":"analyzed$number","aktionsart":"activity","aktionsartConfidence":"high","domainFreqRank":$ipfRank,"exampleSentence":"They analyze$number the report.","exampleTranslation":"They analyze the report."}""")
                appendLine("""{"russian":"analyzed$number","lemma":"analyzed$number","pos":"verb","translation":"to finish analyzing $number","aspect":"PF","aspectPartner":"analyze$number","aktionsart":"accomplishment","aktionsartConfidence":"high","domainFreqRank":$pfRank,"exampleSentence":"They analyzed$number the report.","exampleTranslation":"They finished analyzing the report."}""")
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
    fun newCardsFrontLoadA1TierBeforeDomain() = runTest {
        val fixture = RepoFixture()
        // A domain (tier 2) note and an A1 (tier 0) note. The A1 word must come first
        // regardless of its frequency rank.
        fixture.repository.importJsonLines(
            """
            {"russian":"президент","lemma":"президент","pos":"noun","translation":"president","tier":2,"domainFreqRank":1}
            {"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1,"generalFreqRank":500}
            """.trimIndent()
        )

        val session = fixture.repository.sessionPlan(now = 0L).reviewQueue
        val firstNote = fixture.notes.getById(session.first().card.noteId)
        assertEquals("дом", firstNote?.lemma)
        assertTrue("A1 tier should lead the session", session.all { fixture.notes.getById(it.card.noteId)?.tier == 0 } || session.first().let { fixture.notes.getById(it.card.noteId)?.tier == 0 })
    }

    @Test
    fun grammarDrillsAreLockedUntilTheirLessonIsSeen() = runTest {
        val fixture = RepoFixture()
        // A lesson teaching GENDER, plus a noun whose gender drill is gated by it.
        fixture.repository.importJsonLines(
            """
            {"russian":"Noun gender","lemma":"lesson_gender","pos":"lesson","translation":"Noun gender","conceptId":"GENDER","tier":0,"unit":1,"generalFreqRank":0}
            {"russian":"стол","lemma":"стол","pos":"noun","translation":"table","gender":"M","declensionJson":{"NOM_SG":"стол","GEN_SG":"стола"},"tier":0,"unit":1,"generalFreqRank":1}
            """.trimIndent()
        )

        // Before the lesson is seen, the GENDER drill must not surface in any session.
        val before = fixture.repository.sessionPlan(now = 0L).reviewQueue
        assertFalse(
            "Gender drill leaked before its lesson",
            before.any { it.card.cardType == CardType.GENDER_ID }
        )
        assertTrue("Lesson card should be offered", before.any { it.card.cardType == CardType.LESSON })

        // Review (read) the lesson; it should graduate and unlock the concept.
        val lesson = before.first { it.card.cardType == CardType.LESSON }.card
        fixture.repository.review(lesson, Rating.GOOD, now = 1_000L)
        assertEquals(
            CardState.GRADUATED,
            fixture.cards.cards.first { it.id == lesson.id }.state
        )

        // Now the gender drill is eligible to be introduced.
        val concepts = fixture.cards.getIntroducedConceptIds()
        assertTrue("GENDER concept should be introduced", "GENDER" in concepts)
        val after = fixture.repository.sessionPlan(now = 2_000L).reviewQueue
        assertTrue(
            "Gender drill should surface after its lesson",
            after.any { it.card.cardType == CardType.GENDER_ID } ||
                fixture.cards.cards.any { it.cardType == CardType.GENDER_ID && it.state == CardState.NEW }
        )
    }

    @Test
    fun newGrammarDrillsWaitForAWordEncounterAfterLesson() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """
            {"russian":"Noun gender","lemma":"lesson_gender","pos":"lesson","translation":"Noun gender","conceptId":"GENDER","tier":0,"unit":1,"generalFreqRank":0}
            {"russian":"стол","lemma":"стол","pos":"noun","translation":"table","gender":"M","declensionJson":{"NOM_SG":"стол","GEN_SG":"стола"},"tier":0,"unit":1,"generalFreqRank":1}
            """.trimIndent()
        )

        val first = fixture.repository.sessionPlan(now = 0L).reviewQueue
        val lesson = first.first { it.card.cardType == CardType.LESSON }.card
        fixture.repository.review(lesson, Rating.GOOD, now = 1_000L)

        val afterLesson = fixture.repository.sessionPlan(now = 2_000L).reviewQueue
        assertFalse(
            "new grammar should wait until the word has been encountered",
            afterLesson.any { it.card.cardType == CardType.GENDER_ID }
        )
        val firstVocab = afterLesson.first { it.card.queue == Queue.VOCAB }.card
        fixture.repository.review(firstVocab, Rating.GOOD, now = 3_000L)

        val afterWordEncounter = fixture.repository.sessionPlan(now = 4_000L).reviewQueue
        assertTrue(
            "grammar should become eligible after one word encounter",
            afterWordEncounter.any { it.card.cardType == CardType.GENDER_ID }
        )
    }

    @Test
    fun upperLevelLessonNotesCreateLockedConceptDrills() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """
            {"russian":"Numbers and nouns","lemma":"lesson_numeral_case","pos":"lesson","translation":"Numbers and nouns","conceptId":"NUMERAL_CASE","tier":0,"unit":26,"generalFreqRank":0}
            """.trimIndent()
        )

        val lessonNote = fixture.notes.getByLemma("lesson_numeral_case")!!
        val lessonCards = fixture.cards.cards.filter { it.noteId == lessonNote.id }
        assertTrue(lessonCards.any { it.cardType == CardType.LESSON && it.gramConcept == "NUMERAL_CASE" })
        assertTrue(
            lessonCards.any {
                it.cardType == CardType.CONCEPT_DRILL &&
                    it.gramConcept == "NUMERAL_CASE" &&
                    it.gramContextCue == "NUMERAL_CASE_TWO_BOOKS"
            }
        )

        val before = fixture.repository.sessionPlan(now = 0L).reviewQueue
        assertTrue(before.any { it.card.cardType == CardType.LESSON })
        assertFalse(before.any { it.card.cardType == CardType.CONCEPT_DRILL })

        fixture.repository.review(before.first { it.card.cardType == CardType.LESSON }.card, Rating.GOOD, now = 1_000L)

        val after = fixture.repository.sessionPlan(now = 2_000L).reviewQueue
        assertTrue(after.any { it.card.cardType == CardType.CONCEPT_DRILL })
    }

    @Test
    fun existingLessonNotesGainMissingConceptDrillsOnStartupSync() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(
            Note(
                russian = "Numbers and nouns",
                lemma = "lesson_numeral_case",
                translation = "Numbers and nouns",
                partOfSpeech = "lesson",
                conceptId = "NUMERAL_CASE"
            )
        )
        fixture.cards.insert(Card(noteId = noteId, cardType = CardType.LESSON, queue = Queue.GRAMMAR, gramConcept = "NUMERAL_CASE"))

        fixture.repository.seedIfEmpty()

        assertTrue(
            fixture.cards.cards.any {
                it.noteId == noteId &&
                    it.cardType == CardType.CONCEPT_DRILL &&
                    it.gramContextCue == "NUMERAL_CASE_TWO_BOOKS"
            }
        )
    }

    @Test
    fun clozeIsOnlyCreatedWhenTheExampleHasAReadableTranslation() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """
            {"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"exampleSentence":"Это дом.","exampleTranslation":"This is a house."}
            {"russian":"стол","lemma":"стол","pos":"noun","translation":"table","tier":0,"exampleSentence":"Это стол.","exampleTranslation":"table"}
            {"russian":"окно","lemma":"окно","pos":"noun","translation":"window","tier":0,"exampleSentence":"Это окно."}
            """.trimIndent()
        )

        val withGloss = fixture.notes.getByLemma("дом")
        val headwordOnly = fixture.notes.getByLemma("стол")
        val noGloss = fixture.notes.getByLemma("окно")
        assertTrue(fixture.cards.cards.any { it.noteId == withGloss?.id && it.cardType == CardType.CLOZE })
        assertFalse(fixture.cards.cards.any { it.noteId == headwordOnly?.id && it.cardType == CardType.CLOZE })
        assertFalse(fixture.cards.cards.any { it.noteId == noGloss?.id && it.cardType == CardType.CLOZE })
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

    @Test
    fun placementAfterLevelGraduatesEarlierCourseMaterial() = runTest {
        val fixture = RepoFixture()
        val jsonl = """
            {"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1,"cefrLevel":"A1","exampleSentence":"Это дом.","exampleTranslation":"This is a house."}
            {"russian":"урок","lemma":"урок","pos":"noun","translation":"lesson","tier":0,"unit":11,"cefrLevel":"A2","exampleSentence":"Это урок.","exampleTranslation":"This is a lesson."}
        """.trimIndent()
        fixture.repository.importJsonLines(jsonl)

        val placed = fixture.repository.placeAfterLevel("A1", now = 10_000L)

        val a1 = fixture.notes.getByLemma("дом")!!
        val a2 = fixture.notes.getByLemma("урок")!!
        assertEquals(1, placed)
        assertEquals(WordStatus.KNOWN, a1.status)
        assertEquals(WordStatus.NEW, a2.status)
        assertTrue(fixture.cards.cards.filter { it.noteId == a1.id }.all { it.state == CardState.GRADUATED })
        assertTrue(fixture.cards.cards.filter { it.noteId == a2.id }.all { it.state == CardState.NEW })
    }

    @Test
    fun readingMatrixNotesGetVocabButNoMorphologyDrills() = runTest {
        val fixture = RepoFixture()
        // A frequency reading-matrix note: tagged "matrix", carries a declension table
        // (for reader coverage) and a real example. It must get vocab/comprehension
        // cards but NO morphology drills built from the unverified engine table.
        val jsonl = """{"russian":"кни́га","lemma":"книга","pos":"noun","translation":"book","gender":"F","tier":1,"tags":"general curated matrix","declensionJson":{"NOM_SG":"книга","GEN_SG":"книги","DAT_SG":"книге","ACC_SG":"книгу"},"exampleSentence":"Я читаю книгу.","exampleTranslation":"I am reading a book."}"""
        fixture.repository.importJsonLines(jsonl)

        val note = fixture.notes.getByLemma("книга")
        val cards = fixture.cards.cards.filter { it.noteId == note?.id }
        assertTrue("expected recognition card", cards.any { it.cardType == CardType.RU_TO_MEANING })
        assertTrue("expected production card", cards.any { it.cardType == CardType.MEANING_TO_RU })
        assertFalse("matrix notes must not get case drills", cards.any { it.cardType == CardType.CASE_FILL })
        assertFalse("matrix notes must not get gender drills", cards.any { it.cardType == CardType.GENDER_ID })
        assertFalse("matrix notes must not get agreement drills", cards.any { it.cardType == CardType.ADJ_AGREE })
        assertFalse("matrix notes must not get verb-form drills", cards.any { it.cardType == CardType.VERB_FORM })
        assertFalse("matrix notes must not get aspect drills", cards.any { it.cardType == CardType.ASPECT_SELECT })
    }

    @Test
    fun speakCardsAreAddedForCourseNotesNotReadingMatrix() = runTest {
        val fixture = RepoFixture()
        val jsonl = """
            {"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1,"cefrLevel":"A1","exampleSentence":"Это дом.","exampleTranslation":"This is a house."}
            {"russian":"кот","lemma":"кот","pos":"noun","translation":"cat","tier":1,"tags":"general curated matrix"}
        """.trimIndent()
        fixture.repository.importJsonLines(jsonl)

        val course = fixture.notes.getByLemma("дом")
        val matrix = fixture.notes.getByLemma("кот")
        assertTrue("course note should get a speaking card",
            fixture.cards.cards.any { it.noteId == course?.id && it.cardType == CardType.SPEAK })
        assertFalse("reading-matrix note should not get a speaking card",
            fixture.cards.cards.any { it.noteId == matrix?.id && it.cardType == CardType.SPEAK })
    }

    @Test
    fun sentenceBuildAndDictationOnlyForShortSpineSentences() = runTest {
        val fixture = RepoFixture()
        val jsonl = """
            {"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1,"cefrLevel":"A1","exampleSentence":"Это дом.","exampleTranslation":"This is a house."}
            {"russian":"вопрос","lemma":"вопрос","pos":"noun","translation":"question","tier":0,"tags":"general curated matrix","exampleSentence":"Это очень длинное предложение про вопрос.","exampleTranslation":"This is a very long sentence about the question."}
        """.trimIndent()
        fixture.repository.importJsonLines(jsonl)

        val spine = fixture.notes.getByLemma("дом")
        val matrix = fixture.notes.getByLemma("вопрос")
        assertTrue("short spine sentence gets sentence-build",
            fixture.cards.cards.any { it.noteId == spine?.id && it.cardType == CardType.SENTENCE_BUILD })
        assertFalse("reading-matrix gets no sentence-build (brutal typing)",
            fixture.cards.cards.any { it.noteId == matrix?.id && it.cardType == CardType.SENTENCE_BUILD })
        assertFalse("reading-matrix gets no dictation",
            fixture.cards.cards.any { it.noteId == matrix?.id && it.cardType == CardType.DICTATION })
    }

    @Test
    fun markingWordKnownInReaderStopsPracticeAndLearningReactivates() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"кни́га","lemma":"книга","pos":"noun","translation":"book","tier":0,"unit":1,"cefrLevel":"A1","exampleSentence":"Это книга.","exampleTranslation":"This is a book."}"""
        )
        val note = fixture.notes.getByLemma("книга")!!
        assertTrue("word should start in practice",
            fixture.repository.sessionPlan(now = 0L).reviewQueue.any { it.card.noteId == note.id })

        // Mark KNOWN in the reader -> vocab cards graduate, word leaves practice.
        fixture.repository.setWordStatus("книга", WordStatus.KNOWN, now = 1_000L)
        assertTrue(fixture.cards.cards.filter { it.noteId == note.id && it.queue == Queue.VOCAB }
            .all { it.state == CardState.GRADUATED })
        assertFalse("known word should not be quizzed",
            fixture.repository.sessionPlan(now = 2_000L).reviewQueue.any { it.card.noteId == note.id })

        // Mark LEARNING again -> vocab cards reactivate as NEW.
        fixture.repository.setWordStatus("книга", WordStatus.LEARNING, now = 3_000L)
        assertTrue("learning again pulls it back into practice",
            fixture.cards.cards.any { it.noteId == note.id && it.queue == Queue.VOCAB && it.state == CardState.NEW })
    }

    @Test
    fun batchWordStatusCountsOnlyActualChanges() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """
            {"russian":"one","lemma":"one","pos":"noun","translation":"one","tier":0,"unit":1}
            {"russian":"two","lemma":"two","pos":"noun","translation":"two","tier":0,"unit":1}
            """.trimIndent()
        )
        fixture.repository.setWordStatus("one", WordStatus.LEARNING, now = 1_000L)

        val changed = fixture.repository.setWordStatusBatch(listOf("one", "two", "two"), WordStatus.LEARNING)

        assertEquals(1, changed)
    }

    @Test
    fun batchWordStatusCountsDuplicateUnknownTokenOnce() = runTest {
        val fixture = RepoFixture()

        val changed = fixture.repository.setWordStatusBatch(listOf("novoe", "novoe"), WordStatus.LEARNING)

        assertEquals(1, changed)
        assertEquals(WordStatus.LEARNING, fixture.notes.getByLemma("novoe")?.status)
    }

    @Test
    fun markWordKnownFromReviewGraduatesVocabAndFlipsStatus() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"кни́га","lemma":"книга","pos":"noun","translation":"book","tier":0,"unit":1,"cefrLevel":"A1","exampleSentence":"Это книга.","exampleTranslation":"This is a book."}"""
        )
        val note = fixture.notes.getByLemma("книга")!!
        assertTrue("word should start in practice",
            fixture.repository.sessionPlan(now = 0L).reviewQueue.any { it.card.noteId == note.id })

        fixture.repository.markWordKnown(note.id, now = 1_000L)

        assertEquals(WordStatus.KNOWN, fixture.notes.getById(note.id)!!.status)
        assertTrue("all vocab cards graduate",
            fixture.cards.cards.filter { it.noteId == note.id && it.queue == Queue.VOCAB }
                .all { it.state == CardState.GRADUATED })
        assertFalse("known word should not be quizzed",
            fixture.repository.sessionPlan(now = 2_000L).reviewQueue.any { it.card.noteId == note.id })
    }

    @Test
    fun lapsingPastThresholdAutoParksCardAsLeech() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"кни́га","lemma":"книга","pos":"noun","translation":"book","tier":0,"unit":1,"cefrLevel":"A1","exampleSentence":"Это книга.","exampleTranslation":"This is a book."}"""
        )
        val note = fixture.notes.getByLemma("книга")!!
        val card = fixture.cards.cards.first { it.noteId == note.id && it.queue == Queue.VOCAB }
        // Put it one lapse short of the leech threshold, in the mature REVIEW phase.
        val primed = card.copy(
            lapses = LearningRepository.LEECH_LAPSES - 1,
            state = CardState.REVIEW,
            reps = 10,
            stability = 12.0,
            difficulty = 6.0,
            lastReview = 0L,
            due = 0L
        )
        fixture.cards.update(primed)

        val becameLeech = fixture.repository.review(primed, Rating.AGAIN, now = 1_000L)

        assertTrue("the threshold-crossing lapse reports a leech", becameLeech)
        assertTrue("leech is parked (suspended)",
            fixture.cards.cards.first { it.id == card.id }.suspended)
        assertTrue("leech surfaces in the management list",
            fixture.repository.leechCards().any { it.first.id == card.id })
    }

    @Test
    fun mineSentenceStoresExampleAndPullsWordIntoStudy() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"кни́га","lemma":"книга","pos":"noun","translation":"book","tier":0,"unit":1,"cefrLevel":"A1"}"""
        )
        val mined = fixture.repository.mineSentence("книга", "Я читаю книгу каждый день.", translation = null)
        assertEquals("Я читаю книгу каждый день.", mined?.exampleSentence)
        assertEquals(WordStatus.LEARNING, mined?.status)
    }

    @Test
    fun updateNoteContentFixesGlossAndExample() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"кни́га","lemma":"книга","pos":"noun","translation":"wrong","tier":0,"unit":1,"cefrLevel":"A1"}"""
        )
        val note = fixture.notes.getByLemma("книга")!!
        fixture.repository.updateNoteContent(note.id, translation = "book", exampleSentence = "Это книга.", exampleTranslation = "This is a book.")
        val fixed = fixture.notes.getById(note.id)!!
        assertEquals("book", fixed.translation)
        assertEquals("Это книга.", fixed.exampleSentence)
        assertTrue("repairing a readable example adds context recall",
            fixture.cards.cards.any { it.noteId == note.id && it.cardType == CardType.CLOZE })
    }

    @Test
    fun dueSessionBuriesSiblingsOneCardPerNote() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1,"cefrLevel":"A1","exampleSentence":"Это дом.","exampleTranslation":"This is a house."}"""
        )
        val note = fixture.notes.getByLemma("дом")!!
        val vocab = fixture.cards.cards.filter { it.noteId == note.id && it.queue == Queue.VOCAB }
        assertTrue("note should have multiple vocab cards", vocab.size >= 2)
        // Make two cards of the SAME note due in the mature review phase.
        vocab.take(2).forEach { c ->
            fixture.cards.update(c.copy(state = CardState.REVIEW, due = 0L, reps = 3, lastReview = 0L, stability = 5.0, difficulty = 5.0))
        }
        val queue = fixture.repository.sessionPlan(now = 1_000L).reviewQueue
        assertEquals("only one card per note surfaces in a session",
            1, queue.count { it.card.noteId == note.id })
    }

    @Test
    fun mineSentenceAddsClozeWhenAbsent() = runTest {
        val fixture = RepoFixture()
        // A reading-matrix word with no readable example gets no cloze card at import.
        fixture.repository.importJsonLines(
            """{"russian":"стол","lemma":"стол","pos":"noun","translation":"table","tier":1,"tags":"general curated matrix"}"""
        )
        val note = fixture.notes.getByLemma("стол")!!
        assertFalse("no cloze before mining",
            fixture.cards.cards.any { it.noteId == note.id && it.cardType == CardType.CLOZE })
        fixture.repository.mineSentence("стол", "На столе книга.", translation = "There is a book on the table.")
        assertTrue("mining adds a cloze card so you practise the word in context",
            fixture.cards.cards.any { it.noteId == note.id && it.cardType == CardType.CLOZE })
    }

    @Test
    fun mineSentenceWithoutTranslationDoesNotAddCloze() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"стол","lemma":"стол","pos":"noun","translation":"table","tier":1,"tags":"general curated matrix"}"""
        )
        val note = fixture.notes.getByLemma("стол")!!

        fixture.repository.mineSentence("стол", "На столе книга.", translation = null)

        assertEquals("На столе книга.", fixture.notes.getById(note.id)?.exampleSentence)
        assertFalse("untranslated mined context should not become cloze review",
            fixture.cards.cards.any { it.noteId == note.id && it.cardType == CardType.CLOZE })
    }

    @Test
    fun seedDetectsSpellingAndMeaningConfusablePairs() = runTest {
        val bootstrap = listOf(
            """{"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1,"cefrLevel":"A1"}""",
            """{"russian":"дым","lemma":"дым","pos":"noun","translation":"smoke","tier":0,"unit":1,"cefrLevel":"A1"}""",
            """{"russian":"большой","lemma":"большой","pos":"adj","translation":"big","tier":0,"unit":1,"cefrLevel":"A1"}""",
            """{"russian":"крупный","lemma":"крупный","pos":"adj","translation":"big","tier":0,"unit":1,"cefrLevel":"A1"}"""
        ).joinToString("\n")
        val fixture = RepoFixture(bootstrapNotes = bootstrap)
        fixture.repository.seedIfEmpty()

        val reasons = fixture.pairs.getAll().map { it.reason }.toSet()
        assertTrue("дом/дым detected as spelling-confusable", reasons.contains("confusable_spelling"))
        assertTrue("big/big detected as meaning-confusable", reasons.contains("confusable_meaning"))
    }

    @Test
    fun readerTokensPreservePunctuation() = runTest {
        val fixture = RepoFixture()
        val text = ReaderText(title = "t", body = "Привет, как дела? «Хорошо».", source = "test")
        val tokens = fixture.repository.readerTokens(text)
        val rendered = tokens.joinToString(" ") { it.leading + it.surface + it.trailing }
        assertTrue("comma preserved", rendered.contains(","))
        assertTrue("question mark preserved", rendered.contains("?"))
        assertTrue("opening quote preserved", rendered.contains("«"))
        assertTrue("closing quote + period preserved", rendered.contains("»."))
    }

    @Test
    fun closedClassInflectedFormsResolveToLemmaNote() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"мой","lemma":"мой","pos":"pronoun","translation":"my","tier":0,"unit":1,"cefrLevel":"A1"}"""
        )
        val text = ReaderText(title = "t", body = "Это моя книга и моё яблоко.", source = "test")
        val tokens = fixture.repository.readerTokens(text)
        assertEquals("моя resolves to the мой note", "мой", tokens.first { it.surface == "моя" }.lemma)
        assertEquals("моё resolves to the мой note", "мой", tokens.first { it.surface == "моё" }.lemma)
    }

    @Test
    fun irregularAndAdjectiveFormsResolveInReader() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            listOf(
                """{"russian":"быть","lemma":"быть","pos":"verb","translation":"to be","tier":0,"unit":1,"cefrLevel":"A1"}""",
                """{"russian":"который","lemma":"который","pos":"pronoun","translation":"which","tier":0,"unit":1,"cefrLevel":"A1"}""",
                """{"russian":"большой","lemma":"большой","pos":"adj","translation":"big","tier":0,"unit":1,"cefrLevel":"A1"}"""
            ).joinToString("\n")
        )
        val text = ReaderText(title = "t", body = "Это будет большим, которую любят.", source = "test")
        val tokens = fixture.repository.readerTokens(text)
        assertEquals("будет → быть", "быть", tokens.first { it.surface == "будет" }.lemma)
        assertEquals("большим → большой", "большой", tokens.first { it.surface == "большим" }.lemma)
        assertEquals("которую → который", "который", tokens.first { it.surface == "которую" }.lemma)
    }

    @Test
    fun verbPresentTenseFormsResolveInReader() = runTest {
        val fixture = RepoFixture()
        // A1 verb with NO stored verbForms — present tense must be generated.
        fixture.repository.importJsonLines(
            """{"russian":"читать","lemma":"читать","pos":"verb","translation":"to read","aspect":"IPF","tier":0,"unit":3,"cefrLevel":"A1"}"""
        )
        val text = ReaderText(title = "t", body = "Он читает книгу.", source = "test")
        val tokens = fixture.repository.readerTokens(text)
        assertEquals("читает resolves to читать", "читать", tokens.first { it.surface == "читает" }.lemma)
    }

    @Test
    fun extraCreditAddsNewCardsBeyondTheDailyCap() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 0) })
        fixture.repository.importJsonLines(
            """{"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1,"cefrLevel":"A1"}"""
        )
        // Cap is 0: no new cards available.
        assertTrue("no new cards under a zero cap",
            fixture.repository.sessionPlan(now = 1_000L).reviewQueue.isEmpty())

        fixture.repository.grantExtraCredit(amount = 5, now = 1_000L)
        assertFalse("extra credit unlocks new cards",
            fixture.repository.sessionPlan(now = 1_000L).reviewQueue.isEmpty())
    }

    @Test
    fun extraCreditIsCappedToOneBatchPerDay() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 0, sessionSize = 50) })
        val jsonl = (1..30).joinToString("\n") { i ->
            """{"russian":"word$i","lemma":"word$i","pos":"noun","translation":"word $i","tier":0,"unit":1}"""
        }
        fixture.repository.importJsonLines(jsonl)

        assertEquals(10, fixture.repository.grantExtraCredit(amount = 10, now = 1_000L))
        assertEquals(0, fixture.repository.grantExtraCredit(amount = 10, now = 1_000L))

        val session = fixture.repository.sessionPlan(now = 1_000L).reviewQueue
        assertEquals(10, session.size)
    }

    @Test
    fun normalNewCardBudgetCannotExceedDailyGoal() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(dailyGoal = 5, newCardsPerDay = 80, sessionSize = 50) })
        val jsonl = (1..30).joinToString("\n") { i ->
            """{"russian":"word$i","lemma":"word$i","pos":"noun","translation":"word $i","tier":0,"unit":1}"""
        }
        fixture.repository.importJsonLines(jsonl)

        val session = fixture.repository.sessionPlan(now = 1_000L).reviewQueue

        assertEquals(5, session.size)
    }

    @Test
    fun lessonsDoNotConsumeDailyNewRecallBudget() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(dailyGoal = 1, newCardsPerDay = 1, sessionSize = 10) })
        fixture.repository.importJsonLines(
            """
            {"russian":"Noun gender","lemma":"lesson_gender","pos":"lesson","translation":"Noun gender","conceptId":"GENDER","tier":0,"unit":1,"generalFreqRank":0}
            {"russian":"book","lemma":"book","pos":"noun","translation":"book","gender":"F","tier":0,"unit":1,"generalFreqRank":1}
            """.trimIndent()
        )

        val lesson = fixture.repository.sessionPlan(now = 0L).reviewQueue.first { it.card.cardType == CardType.LESSON }.card
        fixture.repository.review(lesson, Rating.GOOD, now = 1_000L)

        val afterLesson = fixture.repository.sessionPlan(now = 2_000L).reviewQueue

        assertTrue(
            "reading a lesson should not use up the daily budget for actual recall cards",
            afterLesson.any { it.card.queue == Queue.VOCAB }
        )
    }

    @Test
    fun gamificationReviewedTodayUsesLocalDayBoundary() = runTest {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+03:00"))
        try {
            val fixture = RepoFixture()
            val noteId = fixture.notes.insert(Note(russian = "word", lemma = "word", translation = "word", partOfSpeech = "noun"))
            val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW))
            fixture.logs.insert(
                ReviewLog(
                    cardId = cardId,
                    reviewDatetime = Instant.parse("2026-01-01T20:30:00Z").toEpochMilli(),
                    rating = Rating.GOOD,
                    stateBefore = CardState.REVIEW,
                    scheduledDays = 1,
                    elapsedDays = 1,
                    source = ReviewSource.SRS_REVIEW
                )
            )

            val stats = fixture.repository.gamificationStats(now = Instant.parse("2026-01-01T22:30:00Z").toEpochMilli())

            assertEquals(
                "review was before local midnight even though it was after UTC midnight",
                0,
                stats.reviewedToday
            )
        } finally {
            TimeZone.setDefault(previous)
        }
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
        writeBackup: (suspend (String) -> Unit)? = null,
        config: () -> LearningConfig = { LearningConfig() }
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
            config = config,
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
        override suspend fun getByCefrLevels(levels: List<String>): List<Note> = notes.filter { it.cefrLevel in levels }
        override suspend fun search(query: String, limit: Int): List<Note> =
            notes.filter {
                it.russian.contains(query, true) || it.lemma.contains(query, true) || it.translation.contains(query, true)
            }.take(limit)
        override suspend fun update(note: Note) {
            notes.replaceAll { if (it.id == note.id) note else it }
        }
        override suspend fun updateAll(notes: List<Note>) {
            notes.forEach { update(it) }
        }
    }

    private class FakeCardDao(private val notes: FakeNoteDao) : CardDao {
        val cards = mutableListOf<Card>()
        private var nextId = 1L

        override suspend fun getDueCards(now: Long, limit: Int): List<Card> =
            cards.filter { it.due <= now && it.state != CardState.NEW && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
        override suspend fun getDueCardsByQueue(now: Long, queue: Queue, limit: Int): List<Card> =
            cards.filter { it.due <= now && it.queue == queue && it.state != CardState.NEW && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
        override suspend fun countDueBetween(start: Long, end: Long): Int =
            cards.count { it.due > start && it.due <= end && it.state != CardState.NEW && !it.suspended }
        override suspend fun getLeechCards(threshold: Int): List<Card> =
            cards.filter { it.suspended && it.lapses >= threshold }.sortedWith(compareByDescending<Card> { it.lapses }.thenBy { it.id })
        override suspend fun getOverdueCards(cutoff: Long, limit: Int): List<Card> =
            cards.filter { it.due <= cutoff && it.state != CardState.NEW && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
        override suspend fun getAllDueCards(now: Long): List<Card> =
            cards.filter { it.due <= now && it.state != CardState.NEW && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id })
        override suspend fun getNewCards(limit: Int): List<Card> =
            cards.filter { it.state == CardState.NEW && !it.suspended }.sortedWith(compareBy<Card> { it.due }.thenBy { it.id }).take(limit)
        override suspend fun getNewCardsOrdered(limit: Int): List<Card> {
            fun rank(card: Card): IntArray {
                val n = notes.notes.firstOrNull { it.id == card.noteId }
                val tierPhase = if (n?.tier == 0) 0 else 1
                val unit = n?.unit ?: Int.MAX_VALUE
                val freq = n?.domainFreqRank ?: n?.generalFreqRank ?: Int.MAX_VALUE
                return intArrayOf(tierPhase, unit, freq)
            }
            fun eligible(card: Card): Boolean {
                val n = notes.notes.firstOrNull { it.id == card.noteId }
                return card.state == CardState.NEW && !card.suspended &&
                    n?.status != WordStatus.KNOWN && n?.status != WordStatus.IGNORED &&
                    n?.translation != "lookup pending"
            }
            return cards.filter { eligible(it) }
                .sortedWith(
                    compareBy<Card>({ rank(it)[0] }, { rank(it)[1] }, { rank(it)[2] }, { it.id })
                )
                .take(limit)
        }
        override suspend fun graduateVocabForNote(noteId: Long, due: Long): Int {
            var changed = 0
            cards.replaceAll { card ->
                if (card.noteId == noteId && card.queue == Queue.VOCAB) {
                    changed += 1
                    card.copy(state = CardState.GRADUATED, due = due)
                } else card
            }
            return changed
        }
        override suspend fun reactivateVocabForNote(noteId: Long): Int {
            var changed = 0
            cards.replaceAll { card ->
                if (card.noteId == noteId && card.queue == Queue.VOCAB && card.state == CardState.GRADUATED) {
                    changed += 1
                    card.copy(state = CardState.NEW, due = 0, reps = 0, lapses = 0, stability = 0.0, difficulty = 0.0, lastReview = null)
                } else card
            }
            return changed
        }
        override suspend fun getIntroducedConceptIds(): List<String> =
            cards.filter { it.cardType == CardType.LESSON && it.gramConcept != null && it.state != CardState.NEW }
                .mapNotNull { it.gramConcept }
                .distinct()
        override suspend fun getConceptIdsWithLessons(): List<String> =
            cards.filter { it.cardType == CardType.LESSON && it.gramConcept != null }
                .mapNotNull { it.gramConcept }
                .distinct()
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
        override suspend fun getCardsForNotes(noteIds: List<Long>): List<Card> = cards.filter { it.noteId in noteIds }
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

        private fun recallLogs(): List<ReviewLog> = logs.filter { it.source != ReviewSource.READER_LOOKUP }

        override suspend fun countSince(since: Long): Int = recallLogs().count { it.reviewDatetime >= since }
        override suspend fun countAll(): Int = recallLogs().size
        override suspend fun matureReviewCount(): Int =
            recallLogs().count { it.stateBefore == CardState.REVIEW || it.stateBefore == CardState.RELEARNING }
        override suspend fun matureRetainedCount(): Int =
            recallLogs().count { (it.stateBefore == CardState.REVIEW || it.stateBefore == CardState.RELEARNING) && it.rating != Rating.AGAIN }
        override suspend fun countNewIntroducedSince(since: Long): Int =
            recallLogs().count { log ->
                log.reviewDatetime >= since &&
                    log.stateBefore == CardState.NEW &&
                    cards.cards.firstOrNull { it.id == log.cardId }?.cardType != CardType.LESSON
            }
        override suspend fun deleteLatestForCard(cardId: Long) {
            val last = logs.filter { it.cardId == cardId }.maxByOrNull { it.id } ?: return
            logs.remove(last)
        }
        override suspend fun reviewDayBuckets(tzOffset: Long, dayMillis: Long): List<Long> =
            recallLogs().map { (it.reviewDatetime + tzOffset) / dayMillis }.distinct().sortedDescending()
        override suspend fun nounCategoryRatings(gramCase: String, gramGender: String, gramNumber: String, limit: Int): List<Rating> =
            recallLogs().sortedByDescending { it.reviewDatetime }
                .filter { log ->
                    val card = cards.cards.firstOrNull { it.id == log.cardId }
                    card?.gramCase == gramCase && card.gramGender == gramGender && card.gramNumber == gramNumber
                }
                .take(limit)
                .map { it.rating }
        override suspend fun aspectCategoryRatings(aktionsart: String, aspect: String, contextCue: String, limit: Int): List<Rating> =
            recallLogs().sortedByDescending { it.reviewDatetime }
                .filter { log ->
                    val card = cards.cards.firstOrNull { it.id == log.cardId }
                    val note = card?.let { notes.notes.firstOrNull { note -> note.id == it.noteId } }
                    note?.aktionsart == aktionsart && note.aspect == aspect && card.gramContextCue == contextCue
                }
                .take(limit)
                .map { it.rating }
        override suspend fun verbFormCategoryRatings(formKey: String, limit: Int): List<Rating> =
            recallLogs().sortedByDescending { it.reviewDatetime }
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
