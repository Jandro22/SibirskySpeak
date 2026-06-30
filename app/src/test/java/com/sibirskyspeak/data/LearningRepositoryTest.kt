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
        listOf(
            GrammarConcepts.CONDITIONAL, GrammarConcepts.RELATIVE, GrammarConcepts.NUMERAL_CASE,
            GrammarConcepts.PARTICIPLE_ACTIVE, GrammarConcepts.PARTICIPLE_PASSIVE,
            GrammarConcepts.GERUND, GrammarConcepts.PASSIVE, GrammarConcepts.REPORTED,
            GrammarConcepts.ASPECT_NUANCE
        ).forEach { assertTrue(ConceptDrills.forConcept(it.id).size >= 5) }
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
    fun seedSyncsTextbookBootstrapIntoExistingDatabase() = runTest {
        val fixture = RepoFixture(
            bootstrapNotes = """
                {"russian":"\u0414\u043e\u0431\u0440\u044b\u0439 \u0434\u0435\u043d\u044c.","lemma":"textbook::dobryj den","pos":"phrase","translation":"Textbook phrase","exampleSentence":"\u0414\u043e\u0431\u0440\u044b\u0439 \u0434\u0435\u043d\u044c.","exampleTranslation":"Practice phrase.","tier":0,"unit":64,"cefrLevel":"A1","tags":"textbook matrix classroom mn1e"}
                {"russian":"\u0441\u0442\u0430\u0440\u043e\u0435","lemma":"non-textbook","pos":"phrase","translation":"Old row","tags":"matrix"}
            """.trimIndent(),
            bootstrapReaderTexts = """
                {"title":"MN reader","source":"textbook:test:mn1e","body":"\u0414\u043e\u0431\u0440\u044b\u0439 \u0434\u0435\u043d\u044c."}
            """.trimIndent()
        )
        fixture.notes.insert(Note(russian = "existing", lemma = "existing", translation = "existing", partOfSpeech = "noun"))

        fixture.repository.seedIfEmpty()
        fixture.repository.seedIfEmpty()

        assertNotNull(fixture.notes.getByLemma("textbook::dobryj den"))
        assertNull(fixture.notes.getByLemma("non-textbook"))
        assertEquals(1, fixture.readers.texts.count { it.title == "MN reader" })
    }

    @Test
    fun textbookSyncIsIdempotentWhenPayloadRepeatsALemma() = runTest {
        val fixture = RepoFixture(
            bootstrapNotes = """
                {"russian":"Привет","lemma":"textbook::privet","pos":"phrase","translation":"hello","tier":0,"unit":1,"tags":"textbook unit-1 a1"}
                {"russian":"Привет","lemma":"textbook::privet","pos":"phrase","translation":"hello","tier":0,"unit":1,"tags":"textbook unit-1 a1 duplicate-source"}
            """.trimIndent()
        )
        fixture.notes.insert(Note(russian = "existing", lemma = "existing", translation = "existing", partOfSpeech = "noun"))

        assertEquals(1, fixture.repository.syncBootstrapTextbookNotes())
        assertEquals(0, fixture.repository.syncBootstrapTextbookNotes())
        assertEquals(1, fixture.notes.notes.count { it.lemma == "textbook::privet" })
    }

    @Test
    fun textbookSyncRenumbersExistingUnitsAndRetiresRemovedNames() = runTest {
        val fixture = RepoFixture(
            bootstrapNotes = """{"russian":"март","lemma":"tb_март","pos":"word","translation":"March","tier":0,"unit":1,"tags":"textbook vocab mn1e unit-1 a1"}"""
        )
        val marchId = fixture.notes.insert(
            Note(russian = "март", lemma = "tb_март", translation = "March", partOfSpeech = "word", tier = 0, unit = 61, tags = "textbook vocab")
        )
        val nameId = fixture.notes.insert(
            Note(russian = "Варвара", lemma = "tb_варвара", translation = "Barbara", partOfSpeech = "word", tier = 0, unit = 61, tags = "textbook vocab")
        )
        fixture.cards.insert(Card(noteId = marchId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
        fixture.cards.insert(Card(noteId = nameId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))

        fixture.repository.seedIfEmpty()

        assertEquals(1, fixture.notes.getByLemma("tb_март")?.unit)
        assertEquals(WordStatus.IGNORED, fixture.notes.getByLemma("tb_варвара")?.status)
        assertEquals(CardState.GRADUATED, fixture.cards.cards.first { it.noteId == nameId }.state)
    }

    @Test
    fun maintenanceMergesDuplicateSrsHistoryAndReaderRows() = runTest {
        val fixture = RepoFixture()
        val first = fixture.notes.insert(Note(russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun", unit = 61, tags = "textbook"))
        val second = fixture.notes.insert(Note(russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun", unit = 1, tags = "textbook"))
        fixture.cards.insert(Card(noteId = first, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
        val partner = fixture.notes.insert(Note(russian = "partner", lemma = "partner", translation = "partner", partOfSpeech = "verb", aspectPartner = first))
        val reviewed = fixture.cards.insert(Card(noteId = second, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 4, due = 99_000L))
        fixture.logs.insert(ReviewLog(cardId = reviewed, reviewDatetime = 1_000L, rating = Rating.GOOD, stateBefore = CardState.REVIEW, scheduledDays = 4, elapsedDays = 1, source = ReviewSource.SRS_REVIEW))
        fixture.readers.insert(ReaderText(title = "Same", body = "Один текст.", source = "textbook"))
        fixture.readers.insert(ReaderText(title = "Same", body = "Один текст.", source = "textbook"))

        val duplicateReaderIds = fixture.readers.texts.filter { it.title == "Same" }.map { it.id }
        val firstReader = duplicateReaderIds[0]
        val secondReader = duplicateReaderIds[1]
        fixture.readingSchedules.insert(ReadingSchedule(firstReader, due = 500L, intervalDays = 1, reps = 1, lastCompleted = 100L))
        fixture.readingSchedules.insert(ReadingSchedule(secondReader, due = 9_000L, intervalDays = 7, reps = 4, lastCompleted = 400L))
        fixture.readingActivities.insert(ReadingActivity(readerTextId = secondReader, completedAt = 400L, mistakes = 0, intervalDays = 7))
        fixture.readerEncounters.insert(ReaderEncounter(firstReader, first, 100L))
        fixture.readerEncounters.insert(ReaderEncounter(secondReader, second, 200L))

        fixture.repository.performDataMaintenance()

        val merged = fixture.notes.notes.filter { it.lemma == "дом" }
        assertEquals(1, merged.size)
        assertEquals(1, merged.single().unit)
        val mergedCard = fixture.cards.cards.single { it.noteId == merged.single().id && it.cardType == CardType.RU_TO_MEANING }
        assertEquals(4, mergedCard.reps)
        assertEquals(mergedCard.id, fixture.logs.logs.single().cardId)
        assertEquals(1, fixture.readers.texts.count { it.title == "Same" })
        assertEquals(4, fixture.readingSchedules.get(firstReader)?.reps)
        assertNull(fixture.readingSchedules.get(secondReader))
        assertEquals(listOf(firstReader), fixture.readingActivities.activities.map { it.readerTextId }.distinct())
        assertEquals(1, fixture.readerEncounters.encounters.size)
        assertEquals(firstReader, fixture.readerEncounters.encounters.single().readerTextId)
        assertEquals(merged.single().id, fixture.readerEncounters.encounters.single().noteId)
        assertEquals(merged.single().id, fixture.notes.getById(partner)?.aspectPartner)
    }

    @Test
    fun ambiguousFunctionWordsDoNotGenerateEnglishToRussianProduction() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"по","lemma":"по","pos":"preposition","translation":"along, about, by, for","exampleSentence":"По какой-то причине.","exampleTranslation":"For some reason."}"""
        )
        val note = fixture.notes.getByLemma("по")!!
        val cards = fixture.cards.cards.filter { it.noteId == note.id }
        assertTrue(cards.any { it.cardType == CardType.RU_TO_MEANING })
        assertFalse(cards.any { it.cardType in setOf(CardType.MEANING_TO_RU, CardType.CLOZE, CardType.SENTENCE_BUILD) })
    }

    @Test
    fun maintenanceDoesNotRewriteAlreadySuspendedAmbiguousCards() = runTest {
        val fixture = RepoFixture(withTelemetry = true)
        val noteId = fixture.notes.insert(Note(russian = "по", lemma = "по", translation = "along, about", partOfSpeech = "preposition"))
        fixture.cards.insert(Card(noteId = noteId, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB))

        assertEquals(1, fixture.repository.performDataMaintenance())
        assertEquals(0, fixture.repository.performDataMaintenance())
        assertEquals(1, fixture.telemetry?.events?.count { it.eventType == "data_maintenance" })
    }

    @Test
    fun ignoredNoiseCountsAsReaderCoverageButNotAsKnownVocabulary() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "Том", lemma = "том", translation = "Tom", partOfSpeech = "noun", status = WordStatus.IGNORED))
        fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.GRADUATED))
        fixture.readers.insert(ReaderText(title = "Names", body = "Том пришёл.", source = "local"))

        val plan = fixture.repository.sessionPlan()

        assertEquals(0, plan.gamification.knownWords)
        assertTrue(fixture.repository.readerTexts().single().knownTokens > 0)
    }

    @Test
    fun upgradeRetiresBundledWordsRejectedByQualityGateWithoutDeletingHistory() = runTest {
        val bootstrap = """{"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tags":"general curated matrix"}"""
        val fixture = RepoFixture(bootstrapNotes = bootstrap, withTelemetry = true)
        val staleId = fixture.notes.insert(Note(russian = "bad", lemma = "bad", translation = "bad", partOfSpeech = "word", tags = "general matrix", status = WordStatus.IGNORED))
        val cardId = fixture.cards.insert(Card(noteId = staleId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.GRADUATED))
        fixture.logs.insert(ReviewLog(cardId = cardId, reviewDatetime = 1_000L, rating = Rating.GOOD, stateBefore = CardState.NEW, scheduledDays = 0, elapsedDays = 0, source = ReviewSource.SRS_REVIEW))

        fixture.repository.seedIfEmpty()

        assertEquals(WordStatus.NEW, fixture.notes.getById(staleId)?.status)
        assertTrue(fixture.cards.cards.single { it.id == cardId }.suspended)
        assertEquals(cardId, fixture.logs.logs.single().cardId)
        assertEquals(1, fixture.telemetry?.events?.count { it.eventType == "quality_retirement" })
        assertEquals(0, fixture.repository.sessionPlan().gamification.knownWords)
        assertEquals(0, fixture.repository.retireRejectedBootstrapNotes())
        assertEquals(1, fixture.telemetry?.events?.count { it.eventType == "quality_retirement" })
    }

    @Test
    fun anyTwoDayOverdueBacklogPausesNewWords() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 10, sessionSize = 10) })
        val oldId = fixture.notes.insert(Note(russian = "old", lemma = "old", translation = "old", partOfSpeech = "noun"))
        val overdue = fixture.cards.insert(Card(noteId = oldId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, due = 0L))
        fixture.repository.importJsonLines("""{"russian":"новый","lemma":"новый","pos":"word","translation":"new"}""")

        val plan = fixture.repository.sessionPlan(now = 3 * 86_400_000L)

        assertTrue(plan.dailyPlan.overdueBacklog)
        assertEquals(listOf(overdue), plan.reviewQueue.map { it.card.id })
    }

    @Test
    fun overdueBacklogStillAlternatesRecallModalities() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 10, sessionSize = 10) })
        listOf(
            CardType.AUDIO_TO_RU, CardType.AUDIO_TO_RU, CardType.AUDIO_TO_RU,
            CardType.RU_TO_MEANING, CardType.MEANING_TO_RU
        ).forEachIndexed { index, type ->
            val id = fixture.notes.insert(Note(russian = "word$index", lemma = "word$index", translation = "word $index", partOfSpeech = "word"))
            fixture.cards.insert(Card(noteId = id, cardType = type, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 1, due = 0L))
        }

        val types = fixture.repository.sessionPlan(now = 3 * 86_400_000L).reviewQueue.map { it.card.cardType }

        assertEquals(5, types.size)
        assertFalse(types.take(3).all { it == CardType.AUDIO_TO_RU })
    }

    @Test
    fun normalSessionReservesAtLeastSixteenPercentForUnlockedGrammar() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 20, sessionSize = 10) })
        fixture.repository.importJsonLines(
            (1..10).joinToString("\n") { i ->
                """{"russian":"word$i","lemma":"word$i","pos":"noun","translation":"word $i","gender":"M","tier":0,"unit":1,"encounterCount":1}"""
            }
        )

        val queue = fixture.repository.sessionPlan(now = 0L).reviewQueue

        assertTrue("expected at least two grammar cards in ${queue.map { it.card.cardType }}",
            queue.count { it.card.queue == Queue.GRAMMAR } >= 2)
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
    fun stressIsFoldedIntoMarkedHeadwordAndAudioInsteadOfStandaloneCard() = runTest {
        val fixture = RepoFixture()
        val jsonl = """
            {"russian":"\u043c\u043e\u043b\u043e\u043a\u043e\u0301","lemma":"\u043c\u043e\u043b\u043e\u043a\u043e","pos":"noun","translation":"milk","tier":0,"exampleSentence":"\u042f \u043f\u044c\u044e \u043c\u043e\u043b\u043e\u043a\u043e\u0301.","exampleTranslation":"I drink milk."}
        """.trimIndent()

        fixture.repository.importJsonLines(jsonl)

        val note = fixture.notes.getByLemma("\u043c\u043e\u043b\u043e\u043a\u043e")
        assertFalse(fixture.cards.cards.any { it.noteId == note?.id && it.cardType == CardType.STRESS_MARK })
        assertTrue(fixture.cards.cards.any { it.noteId == note?.id && it.cardType == CardType.AUDIO_TO_RU })
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

        // Due reviews keep priority (first), but new material now blends in behind
        // them so a growing review pile can't permanently stall new-word progress.
        assertEquals(dueCardId, session.reviewQueue.first().card.id)
        assertTrue(
            "new cards should blend in after due reviews (no SRS treadmill)",
            session.reviewQueue.drop(1).any { it.card.id == newCardId }
        )
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
        fixture.notes.update(troopNote.copy(status = WordStatus.KNOWN))
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
    fun readerCoverageDoesNotSilentlyCountCapitalizedUnknowns() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"иду","lemma":"иду","pos":"verb","translation":"I go","tier":0,"unit":1,"cefrLevel":"A1"}"""
        )
        val note = fixture.notes.getByLemma("иду")!!
        fixture.notes.update(note.copy(status = WordStatus.KNOWN))
        fixture.repository.addReaderText("With name", "иду Анна", "local")

        val recommendation = fixture.repository.readerTexts().first { it.text.title == "With name" }
        val tokens = fixture.repository.readerTokens(recommendation.text)

        assertEquals(0.5, recommendation.coverage, 0.0)
        assertFalse(tokens.first { it.surface == "Анна" }.known)
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
        fixture.notes.update(termNote.copy(status = WordStatus.KNOWN))
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
    fun textbookVocabFlowsIntoTheNewCardShuffleAsCleanRecognitionCards() = runTest {
        val fixture = RepoFixture()
        // A real-glossed Между нами textbook vocab note (generic pos "word", no
        // declension, tier-0 unit just after the spine) alongside a spine word. Both
        // must enter the daily shuffle as clean recognition cards; the lower-unit
        // spine word leads, and the textbook word must NOT spawn junk morphology
        // drills from data it doesn't have.
        fixture.repository.importJsonLines(
            """
            {"russian":"март","lemma":"tb_март","pos":"word","translation":"March","tier":0,"unit":61,"cefrLevel":"A1","tags":"textbook vocab mn1e unit-1 a1"}
            {"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1,"cefrLevel":"A1"}
            """.trimIndent()
        )
        val tb = fixture.notes.getByLemma("tb_март")!!
        val tbCards = fixture.cards.cards.filter { it.noteId == tb.id }
        assertTrue("textbook word is studyable", tbCards.any { it.cardType == CardType.RU_TO_MEANING })
        assertTrue(
            "textbook word must not get morphology drills",
            tbCards.none { it.cardType in setOf(CardType.CASE_FILL, CardType.GENDER_ID, CardType.VERB_FORM, CardType.ASPECT_SELECT) }
        )

        val session = fixture.repository.sessionPlan(now = 0L).reviewQueue
        assertEquals("дом", fixture.notes.getById(session.first().card.noteId)?.lemma)
        assertTrue("textbook vocab appears in the daily shuffle", session.any { it.card.noteId == tb.id })
        // First contact is a single recognition card per word (audio/production deferred).
        assertTrue(session.all { it.card.cardType == CardType.RU_TO_MEANING })
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
    fun recognitionOnlyNotesGetRecognitionAndListeningButNoProduction() = runTest {
        val fixture = RepoFixture()
        // A textbook word recovered in an oblique form ("университе́та = university,
        // genitive"), tagged "recognition_only". It is honest for recognition and
        // reader coverage, but reverse-production (typing the inflected form) and
        // speaking would be wrong, so those cards must not be built.
        val jsonl = """{"russian":"университе́та","lemma":"tb_университет","pos":"word","translation":"university","tier":0,"tags":"textbook vocab mn1e unit-6 a2 recognition_only"}"""
        fixture.repository.importJsonLines(jsonl)

        val note = fixture.notes.getByLemma("tb_университет")
        val cards = fixture.cards.cards.filter { it.noteId == note?.id }
        assertTrue("expected recognition card", cards.any { it.cardType == CardType.RU_TO_MEANING })
        assertTrue("expected listening card", cards.any { it.cardType == CardType.AUDIO_TO_RU })
        assertFalse("recognition-only must not get reverse production",
            cards.any { it.cardType == CardType.MEANING_TO_RU })
        assertFalse("recognition-only must not get a speaking card",
            cards.any { it.cardType == CardType.SPEAK })
        assertFalse("recognition-only must not get cloze",
            cards.any { it.cardType == CardType.CLOZE })
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
    fun newWordBudgetIsIndependentOfReviewGoal() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(dailyGoal = 5, newCardsPerDay = 80, sessionSize = 50) })
        val jsonl = (1..30).joinToString("\n") { i ->
            """{"russian":"word$i","lemma":"word$i","pos":"noun","translation":"word $i","tier":0,"unit":1}"""
        }
        fixture.repository.importJsonLines(jsonl)

        val session = fixture.repository.sessionPlan(now = 1_000L).reviewQueue

        assertEquals(30, session.size)
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
    fun matureWordFacetDoesNotStealTextbookNewWordBudget() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 1, sessionSize = 10) })
        fixture.repository.importJsonLines(
            """
            {"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1}
            {"russian":"март","lemma":"tb_март","pos":"word","translation":"March","tier":0,"unit":61,"tags":"textbook vocab"}
            """.trimIndent()
        )
        val oldNote = fixture.notes.getByLemma("дом")!!
        val recognition = fixture.cards.cards.first { it.noteId == oldNote.id && it.cardType == CardType.RU_TO_MEANING }
        fixture.cards.update(
            recognition.copy(
                state = CardState.REVIEW,
                reps = 3,
                consecutiveCorrect = 3,
                due = 99_000_000L,
                lastReview = 1_000L
            )
        )
        fixture.logs.insert(goodLog(recognition, 1_000L))

        val queue = fixture.repository.sessionPlan(now = 90_000_000L).reviewQueue
        val textbook = fixture.notes.getByLemma("tb_март")!!

        assertTrue("the mature word may advance to another skill facet",
            queue.any { it.card.noteId == oldNote.id && it.card.cardType != CardType.RU_TO_MEANING })
        assertTrue("the one-new-word allowance remains available to textbook vocabulary",
            queue.any { it.card.noteId == textbook.id && it.card.cardType == CardType.RU_TO_MEANING })
    }

    @Test
    fun sameDayReviewBuriesSiblingButAllowsTheSameCardToRelearn() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 0, sessionSize = 10) })
        fixture.repository.importJsonLines(
            """{"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1,"exampleSentence":"Это дом.","exampleTranslation":"This is a house."}"""
        )
        val note = fixture.notes.getByLemma("дом")!!
        val siblings = fixture.cards.cards.filter { it.noteId == note.id && it.queue == Queue.VOCAB }.take(2)
        siblings.forEach { fixture.cards.update(it.copy(state = CardState.REVIEW, due = 0L, reps = 3)) }
        fixture.logs.insert(goodLog(siblings.first(), 1_000L))

        val queue = fixture.repository.sessionPlan(now = 2_000L).reviewQueue

        assertTrue(queue.any { it.card.id == siblings.first().id })
        assertFalse("a different facet of today's word must stay buried",
            queue.any { it.card.id == siblings.last().id })
    }

    @Test
    fun textbookFrontierAllowsOnlyBoundedNextUnitPreview() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 20, sessionSize = 10) })
        val rows = buildList {
            repeat(6) { add("""{"russian":"u1-$it","lemma":"u1-$it","pos":"word","translation":"one $it","tier":0,"unit":1,"tags":"textbook vocab"}""") }
            repeat(6) { add("""{"russian":"u2-$it","lemma":"u2-$it","pos":"word","translation":"two $it","tier":0,"unit":2,"tags":"textbook vocab"}""") }
            add("""{"russian":"u3","lemma":"u3","pos":"word","translation":"three","tier":0,"unit":3,"tags":"textbook vocab"}""")
        }
        fixture.repository.importJsonLines(rows.joinToString("\n"))

        val units = fixture.repository.sessionPlan(now = 0L).reviewQueue.mapNotNull { it.note.unit }

        assertTrue(units.count { it == 2 } <= 2)
        assertFalse("later textbook units stay gated", 3 in units)
    }

    @Test
    fun queueExplainsCardPurposeAndReportsDailyCompletionState() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 1, sessionSize = 10) })
        fixture.repository.importJsonLines(
            """{"russian":"март","lemma":"tb_март","pos":"word","translation":"March","tier":0,"unit":1,"tags":"textbook vocab"}"""
        )
        val first = fixture.repository.sessionPlan(now = 1_000L)
        assertEquals(DailyLearningStatus.WORK_REMAINING, first.completion.status)
        assertTrue(first.reviewQueue.first().queueReason.orEmpty().contains("textbook", ignoreCase = true))

        fixture.repository.review(first.reviewQueue.first().card, Rating.GOOD, now = 2_000L)
        val done = fixture.repository.sessionPlan(now = 3_000L)

        assertEquals(DailyLearningStatus.NEW_LIMIT_REACHED, done.completion.status)
        assertTrue(done.completion.optionalReinforcementAvailable.not())
    }

    @Test
    fun productionMissRepairsWithRecognitionBeforeRetryingProduction() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1}"""
        )
        val note = fixture.notes.getByLemma("дом")!!
        val production = fixture.cards.cards.first { it.noteId == note.id && it.cardType == CardType.MEANING_TO_RU }

        val repair = fixture.repository.repairPromptFor(production)

        assertEquals(CardType.RU_TO_MEANING, repair?.card?.cardType)
    }

    @Test
    fun firstExposureRefreshesIntoRecallInsteadOfRepeatingTheTeachingCard() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"дом","lemma":"дом","pos":"word","translation":"house","tier":0,"unit":1}"""
        )
        val original = fixture.cards.cards.first { it.cardType == CardType.RU_TO_MEANING }
        val introduction = fixture.repository.promptForCard(original)!!
        assertEquals(com.sibirskyspeak.review.AnswerMode.LESSON, introduction.answerMode)

        fixture.repository.review(original, Rating.GOOD, now = 1_000L)
        val recall = fixture.repository.promptForCard(original, now = 2_000L)!!

        assertEquals(com.sibirskyspeak.review.AnswerMode.ENGLISH, recall.answerMode)
        assertEquals("house", recall.expectedAnswer)
    }

    @Test
    fun unitMasteryReportsVocabularyAndKeepsLaterUnitLocked() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 10, sessionSize = 10) })
        fixture.repository.importJsonLines(
            """
            {"russian":"один","lemma":"один","pos":"word","translation":"one","tier":0,"unit":1}
            {"russian":"два","lemma":"два","pos":"word","translation":"two","tier":0,"unit":2}
            """.trimIndent()
        )

        val plan = fixture.repository.sessionPlan(now = 0L)

        assertEquals(listOf(1, 2), plan.unitMastery.map { it.unit })
        assertTrue(plan.unitMastery.first().unlocked)
        assertFalse(plan.unitMastery.last().unlocked)
        assertFalse(plan.reviewQueue.any { it.note.unit == 2 })
    }

    @Test
    fun endOfSessionReaderPrefersTextContainingTodaysReviewedWords() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """
            {"russian":"дом","lemma":"дом","pos":"word","translation":"house","tier":0,"unit":1}
            {"russian":"книга","lemma":"книга","pos":"word","translation":"book","tier":0,"unit":1}
            """.trimIndent()
        )
        fixture.repository.addReaderText("Consolidation", "дом книга дом книга", "test")
        fixture.repository.addReaderText("Unrelated", "совсем другой текст", "test")
        for (lemma in listOf("дом", "книга")) {
            val note = fixture.notes.getByLemma(lemma)!!
            val card = fixture.cards.cards.first { it.noteId == note.id && it.cardType == CardType.RU_TO_MEANING }
            fixture.repository.review(card, Rating.GOOD, now = 199_000_000L)
        }

        val plan = fixture.repository.sessionPlan(now = 200_000_000L)

        assertEquals("Consolidation", plan.readerRecommendation?.text?.title)
        assertTrue(plan.readingReason.orEmpty().contains("practiced today"))
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

    @Test
    fun fullStateExportRoundTripsTelemetryButContentExportOmitsIt() = runTest {
        val source = RepoFixture(withTelemetry = true)
        source.repository.importJsonLines(
            """{"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":1}"""
        )
        val sourceNote = source.notes.notes.single()
        val sourceCard = source.cards.cards.first { it.noteId == sourceNote.id }
        source.repository.recordTelemetry(
            TelemetryEvent(
                eventType = "review_committed",
                sessionId = "s-1",
                cardId = sourceCard.id,
                noteId = sourceNote.id,
                rating = "GOOD",
                responseMs = 1234,
                wasRevealed = true,
                typedLength = 4,
                metadataJson = """{"k":"v"}"""
            )
        )

        // Content-only export must not carry telemetry rows.
        val contentExport = source.repository.exportJsonLines()
        assertFalse("content export must not include telemetry", contentExport.contains("_telemetry"))

        // Full-state export carries telemetry, which round-trips into a fresh repo.
        val fullExport = source.repository.exportFullState()
        assertTrue("full export should include telemetry", fullExport.contains("\"_telemetry\""))

        val restored = RepoFixture(withTelemetry = true)
        val importedNotes = restored.repository.importJsonLines(fullExport)
        assertEquals(1, importedNotes)
        assertEquals(1, restored.notes.notes.count { it.lemma == "дом" })

        val restoredEvents = restored.telemetry!!.getAll()
        assertEquals(1, restoredEvents.size)
        val event = restoredEvents.single()
        assertEquals("review_committed", event.eventType)
        assertEquals("s-1", event.sessionId)
        assertEquals(sourceCard.id, event.cardId)
        assertEquals(restored.notes.notes.single().id, event.noteId)
        assertEquals("GOOD", event.rating)
        assertEquals(1234L, event.responseMs)
        assertTrue(event.wasRevealed)
        assertEquals(4, event.typedLength)
        assertEquals("""{"k":"v"}""", event.metadataJson)
    }

    @Test
    fun dueReadingIsAFirstClassSessionAssignmentAndCleanRecallSpacesIt() = runTest {
        val fixture = RepoFixture()
        fixture.notes.insert(Note(
            russian = "\u0434\u043e\u043c",
            lemma = "\u0434\u043e\u043c",
            translation = "house",
            partOfSpeech = "noun",
            status = WordStatus.KNOWN
        ))
        val textId = fixture.repository.addReaderText("A house", "\u0414\u043e\u043c \u0434\u043e\u043c.")

        val plan = fixture.repository.sessionPlan(now = 1_000L)

        assertEquals(textId, plan.readingAssignment?.recommendation?.text?.id)
        assertEquals(0, plan.readingAssignment?.insertionIndex)

        fixture.repository.completeScheduledReading(textId, mistakes = 0, now = 1_000L)
        val scheduled = fixture.readingSchedules.get(textId)!!
        assertEquals(1, scheduled.reps)
        assertEquals(1, scheduled.intervalDays)
        assertEquals(1_000L + 86_400_000L, scheduled.due)
        assertNull(fixture.repository.sessionPlan(now = 2_000L).readingAssignment)
    }

    @Test
    fun difficultReadingCheckpointReturnsTomorrowAndCountsALapse() = runTest {
        val fixture = RepoFixture()
        val textId = fixture.repository.addReaderText("Hard passage", "text")

        fixture.repository.completeScheduledReading(textId, mistakes = 4, now = 5_000L)

        val scheduled = fixture.readingSchedules.get(textId)!!
        assertEquals(1, scheduled.intervalDays)
        assertEquals(1, scheduled.lapses)
        assertEquals(1, scheduled.reps)
    }

    @Test
    fun reviewReloadsLiveCardInsteadOfOverwritingWithFrozenSnapshot() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "word", lemma = "word", translation = "word", partOfSpeech = "noun"))
        val cardId = fixture.cards.insert(Card(
            noteId = noteId,
            cardType = CardType.RU_TO_MEANING,
            queue = Queue.VOCAB,
            state = CardState.REVIEW,
            reps = 5,
            stability = 4.0,
            difficulty = 5.0,
            lastReview = 1_000L
        ))
        val frozen = fixture.cards.cards.first { it.id == cardId }.copy(state = CardState.NEW, reps = 0, stability = 0.0)

        fixture.repository.review(frozen, Rating.GOOD, now = 86_401_000L)

        assertEquals(6, fixture.cards.cards.first { it.id == cardId }.reps)
        assertEquals(CardState.REVIEW, fixture.logs.logs.single().stateBefore)
    }

    @Test
    fun graduatedCardsNeverReturnToDueQueue() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "done", lemma = "done", translation = "done", partOfSpeech = "noun"))
        val cardId = fixture.cards.insert(Card(
            noteId = noteId,
            cardType = CardType.RU_TO_MEANING,
            queue = Queue.VOCAB,
            state = CardState.GRADUATED,
            due = 0L
        ))

        assertFalse(fixture.repository.sessionPlan(now = 10_000L).reviewQueue.any { it.card.id == cardId })
    }

    @Test
    fun cardReviewEncountersAloneDoNotAutoGraduateVocabulary() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(
            russian = "hard",
            lemma = "hard",
            translation = "hard",
            partOfSpeech = "noun",
            encounterCount = 15
        ))
        val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))

        fixture.repository.sessionPlan(now = 1_000L)

        assertEquals(CardState.NEW, fixture.cards.cards.first { it.id == cardId }.state)
    }

    @Test
    fun newCardPagingSkipsMoreThanOnePageOfImmatureFacets() = runTest {
        val fixture = RepoFixture()
        repeat(60) { index ->
            val noteId = fixture.notes.insert(Note(russian = "blocked$index", lemma = "blocked$index", translation = "blocked", partOfSpeech = "noun"))
            fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, due = Long.MAX_VALUE))
            listOf(CardType.MEANING_TO_RU, CardType.CLOZE, CardType.SPEAK, CardType.AUDIO_TO_RU).forEach { type ->
                fixture.cards.insert(Card(noteId = noteId, cardType = type, queue = Queue.VOCAB, state = CardState.NEW))
            }
        }
        val eligibleNote = fixture.notes.insert(Note(russian = "eligible", lemma = "eligible", translation = "eligible", partOfSpeech = "noun"))
        val eligibleCard = fixture.cards.insert(Card(noteId = eligibleNote, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.NEW))

        val plan = fixture.repository.sessionPlan(now = 1_000L)

        assertTrue(plan.reviewQueue.any { it.card.id == eligibleCard })
    }

    @Test
    fun dormantFacetsDoNotPretendTheDailyNewLimitWasReached() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "word", lemma = "word", translation = "word", partOfSpeech = "noun"))
        fixture.cards.insert(Card(
            noteId = noteId,
            cardType = CardType.RU_TO_MEANING,
            queue = Queue.VOCAB,
            state = CardState.REVIEW,
            due = Long.MAX_VALUE,
            reps = 1,
            consecutiveCorrect = 1
        ))
        fixture.cards.insert(Card(noteId = noteId, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB))

        val plan = fixture.repository.sessionPlan(now = 1_000L)

        assertTrue(plan.reviewQueue.isEmpty())
        assertEquals(DailyLearningStatus.SCHEDULED_COMPLETE, plan.completion.status)
    }

    @Test
    fun fullStateBackupRestoresReviewLogs() = runTest {
        val source = RepoFixture(withTelemetry = true)
        val noteId = source.repository.addNote(Note(russian = "word", lemma = "word", translation = "word", partOfSpeech = "noun"))
        val card = source.cards.cards.first { it.noteId == noteId && it.cardType == CardType.RU_TO_MEANING }
        source.repository.review(card, Rating.GOOD, now = 5_000L)

        val restored = RepoFixture(withTelemetry = true)
        restored.repository.importJsonLines(source.repository.exportFullState())

        assertEquals(1, restored.logs.logs.size)
        assertEquals(Rating.GOOD, restored.logs.logs.single().rating)
        assertEquals(CardState.NEW, restored.logs.logs.single().stateBefore)
    }

    @Test
    fun scheduledReadingXpSurvivesTelemetryRetentionCleanup() = runTest {
        val fixture = RepoFixture(withTelemetry = true)
        val textId = fixture.repository.addReaderText("Durable", "A short passage")

        fixture.repository.completeScheduledReading(textId, mistakes = 0, now = 100L)
        fixture.telemetry!!.deleteOlderThan(200L)

        assertTrue(fixture.telemetry.getAll().isEmpty())
        assertEquals(1, fixture.readingActivities.countAll())
        assertEquals(30, fixture.repository.gamificationStats(now = 300L).xp)
    }

    @Test
    fun sameDayRecoveryDoesNotInflateMatureRetention() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "word", lemma = "word", translation = "word", partOfSpeech = "noun"))
        val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
        fixture.logs.insert(ReviewLog(
            cardId = cardId,
            reviewDatetime = 1_000L,
            rating = Rating.GOOD,
            stateBefore = CardState.RELEARNING,
            scheduledDays = 1,
            elapsedDays = 0,
            source = ReviewSource.SRS_REVIEW
        ))

        assertEquals(0, fixture.logs.matureReviewCount())
        assertEquals(0, fixture.logs.matureRetainedCount())
    }

    @Test
    fun fullStateBackupPreservesStatusPairsReadingHistoryAndLegacyCardVariants() = runTest {
        val source = RepoFixture(withTelemetry = true)
        val firstId = source.repository.addNote(Note(
            russian = "first", lemma = "first", translation = "first", partOfSpeech = "noun", status = WordStatus.KNOWN
        ))
        val secondId = source.repository.addNote(Note(
            russian = "second", lemma = "second", translation = "second", partOfSpeech = "noun"
        ))
        source.pairs.insert(ConfusablePair(firstNoteId = firstId, secondNoteId = secondId, reason = "manual_test"))
        source.cards.insert(Card(
            noteId = firstId,
            cardType = CardType.STRESS_MARK,
            queue = Queue.VOCAB,
            gramContextCue = "legacy_variant",
            gramConcept = "LEGACY",
            state = CardState.REVIEW,
            reps = 9,
            stability = 12.0
        ))
        val textId = source.repository.addReaderText("History", "first second")
        source.repository.completeScheduledReading(textId, mistakes = 2, now = 123_000L)

        val restored = RepoFixture(withTelemetry = true)
        restored.repository.importJsonLines(source.repository.exportFullState())

        val restoredFirst = restored.notes.getByLemma("first")!!
        assertEquals(WordStatus.KNOWN, restoredFirst.status)
        assertTrue(restored.pairs.pairs.any { it.reason == "manual_test" })
        assertEquals(1, restored.readingActivities.countAll())
        val legacy = restored.cards.cards.single { it.noteId == restoredFirst.id && it.gramContextCue == "legacy_variant" }
        assertEquals("LEGACY", legacy.gramConcept)
        assertEquals(9, legacy.reps)
        assertEquals(CardState.REVIEW, legacy.state)
    }

    @Test
    fun editingNoteContentRefreshesReaderLookupCache() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.repository.addNote(Note(
            russian = "word", lemma = "word", translation = "old meaning", partOfSpeech = "noun"
        ))
        val textId = fixture.repository.addReaderText("Cache", "word")
        val text = fixture.readers.getById(textId)!!
        assertEquals("old meaning", fixture.repository.readerTokens(text).single().translation)

        fixture.repository.updateNoteContent(noteId, translation = "new meaning")

        assertEquals("new meaning", fixture.repository.readerTokens(text).single().translation)
    }

    @Test
    fun stalePromptCannotReviewARetiredCard() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.repository.addNote(Note(
            russian = "word", lemma = "word", translation = "meaning", partOfSpeech = "noun"
        ))
        val stale = fixture.cards.cards.first { it.noteId == noteId }
        fixture.repository.suspendCard(stale)

        var rejected = false
        try {
            fixture.repository.review(stale, Rating.GOOD, now = 1_000L)
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(fixture.logs.logs.isEmpty())
    }

    @Test
    fun localDayBoundaryUsesTheOffsetAtMidnightAcrossDst() = runTest {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        try {
            val fixture = RepoFixture()
            val noteId = fixture.notes.insert(Note(russian = "word", lemma = "word", translation = "word", partOfSpeech = "noun"))
            val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
            fixture.logs.insert(ReviewLog(
                cardId = cardId,
                reviewDatetime = Instant.parse("2025-03-09T04:30:00Z").toEpochMilli(),
                rating = Rating.GOOD,
                stateBefore = CardState.REVIEW,
                scheduledDays = 1,
                elapsedDays = 1,
                source = ReviewSource.SRS_REVIEW
            ))

            assertEquals(0, fixture.repository.reviewedToday(Instant.parse("2025-03-09T16:00:00Z").toEpochMilli()))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun markWordKnownGraduatesWithCoherentFsrsState() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun", status = WordStatus.NEW, tags = ""))
        // Two fresh VOCAB cards in the degenerate all-zero state bulk graduation left.
        fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
        fixture.cards.insert(Card(noteId = noteId, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB))

        fixture.repository.markWordKnown(noteId)

        val graduated = fixture.cards.cards.filter { it.noteId == noteId }
        assertTrue("all vocab cards graduate", graduated.all { it.state == CardState.GRADUATED })
        assertTrue("no card is left with degenerate FSRS state",
            graduated.none { it.stability <= 0.0 || it.difficulty <= 0.0 })
        assertTrue("difficulty stays within FSRS range", graduated.all { it.difficulty in 1.0..10.0 })
        assertEquals(WordStatus.KNOWN, fixture.notes.getById(noteId)?.status)
    }

    @Test
    fun placeAfterLevelGraduatesWithCoherentFsrsState() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"вода","lemma":"вода","pos":"noun","translation":"water","cefrLevel":"A1"}"""
        )

        val placed = fixture.repository.placeAfterLevel("A1")

        assertTrue("at least one note placed", placed >= 1)
        val cards = fixture.cards.cards.filter { it.queue == Queue.VOCAB }
        assertTrue(cards.isNotEmpty())
        assertTrue("placed cards graduate", cards.all { it.state == CardState.GRADUATED })
        assertTrue("placed cards carry coherent FSRS state",
            cards.none { it.stability <= 0.0 || it.difficulty <= 0.0 })
    }

    @Test
    fun retentionByCardTypeSplitsMatureReviewsPerFacet() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "стол", lemma = "стол", translation = "table", partOfSpeech = "noun", status = WordStatus.LEARNING, tags = ""))
        val recognition = Card(id = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW)), noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW)
        val production = Card(id = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB, state = CardState.REVIEW)), noteId = noteId, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB, state = CardState.REVIEW)
        // Recognition: 2 retained. Production: 1 retained, 1 lapsed → easier facet visible.
        fixture.logs.insert(matureLog(recognition, Rating.GOOD))
        fixture.logs.insert(matureLog(recognition, Rating.EASY))
        fixture.logs.insert(matureLog(production, Rating.GOOD))
        fixture.logs.insert(matureLog(production, Rating.AGAIN))

        val byType = fixture.repository.retentionByCardType(now = 10L * 86_400_000L).associateBy { it.cardType }

        assertEquals(2, byType[CardType.RU_TO_MEANING]?.total)
        assertEquals(2, byType[CardType.RU_TO_MEANING]?.retained)
        assertEquals(2, byType[CardType.MEANING_TO_RU]?.total)
        assertEquals(1, byType[CardType.MEANING_TO_RU]?.retained)
    }

    @Test
    fun repairConcatenatedExamplesSplitsLegacyNotesAndIsIdempotent() = runTest {
        val fixture = RepoFixture()
        fixture.notes.insert(Note(
            russian = "страх", lemma = "страх", translation = "fear", partOfSpeech = "noun", tags = "general matrix",
            exampleSentence = "Я испытываю страх - It scares me.", exampleTranslation = null
        ))
        // A clean note must be left untouched.
        fixture.notes.insert(Note(
            russian = "вода", lemma = "вода", translation = "water", partOfSpeech = "noun", tags = "",
            exampleSentence = "Вода на столе.", exampleTranslation = "Water is on the table."
        ))

        val firstPass = fixture.repository.repairConcatenatedExamples()
        assertEquals(1, firstPass)
        val fixed = fixture.notes.getByLemma("страх")!!
        assertEquals("Я испытываю страх", fixed.exampleSentence)
        assertEquals("It scares me.", fixed.exampleTranslation)
        // Clean note unchanged.
        assertEquals("Вода на столе.", fixture.notes.getByLemma("вода")?.exampleSentence)

        // Idempotent: a second pass finds nothing left to repair.
        assertEquals(0, fixture.repository.repairConcatenatedExamples())
    }

    private fun matureLog(card: Card, rating: Rating): ReviewLog =
        ReviewLog(
            cardId = card.id,
            reviewDatetime = 5L * 86_400_000L,
            rating = rating,
            stateBefore = CardState.REVIEW,
            scheduledDays = 3,
            elapsedDays = 3,
            source = ReviewSource.SRS_REVIEW
        )

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

}
