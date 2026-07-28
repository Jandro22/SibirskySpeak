package com.sibirskyspeak.data

import com.sibirskyspeak.scheduler.FsrsScheduler
import com.sibirskyspeak.review.TemporarySessionMode
import com.sibirskyspeak.review.TemporarySessionPolicy
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.ChoiceArchetype
import com.sibirskyspeak.review.FakeSettingsStore
import com.sibirskyspeak.learning.EvidenceEvent
import com.sibirskyspeak.learning.EvidenceStrength
import com.sibirskyspeak.learning.FluencySimEngine
import com.sibirskyspeak.learning.LearningFacet
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
    fun launchMaintenanceReconcilesOncePerContentChecksum() = runTest {
        val settings = FakeSettingsStore()
        val fixture = RepoFixture(
            bootstrapManifest = """{"curriculumVersion":"test","contentChecksum":"checksum-a"}""",
            settingsStore = settings,
            withTelemetry = true
        )
        fixture.notes.insert(
            Note(russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun")
        )

        fixture.repository.performLaunchMaintenance()
        val firstSyncCount = fixture.telemetry!!.events.count {
            it.eventType == "maintenance_step_completed" &&
                it.metadataJson.contains("\"step\":\"sync_bootstrap_textbook_notes\"")
        }
        assertEquals(1, firstSyncCount)
        assertTrue(settings.launchMaintenanceToken.endsWith(":checksum-a"))

        fixture.repository.performLaunchMaintenance()
        val secondSyncCount = fixture.telemetry.events.count {
            it.eventType == "maintenance_step_completed" &&
                it.metadataJson.contains("\"step\":\"sync_bootstrap_textbook_notes\"")
        }
        assertEquals("same content must not rescan the full deck", firstSyncCount, secondSyncCount)
    }

    @Test
    fun calibrationEligibilityExcludesMatcherDisputesAndCapsRepeatedCards() {
        val repeated = (1L..7L).map { at ->
            TelemetryEvent(timestamp = at, eventType = "success_calibration_sample", cardId = 1L)
        }
        val disputedSample = TelemetryEvent(
            timestamp = 100L,
            eventType = "success_calibration_sample",
            cardId = 2L
        )
        val disputedReview = TelemetryEvent(
            timestamp = 110L,
            eventType = "review_committed",
            cardId = 2L,
            answerMode = AnswerMode.ENGLISH.name,
            answerMatch = "WRONG",
            rating = Rating.GOOD.name
        )
        val validSample = TelemetryEvent(
            timestamp = 200L,
            eventType = "success_calibration_sample",
            cardId = 3L
        )

        val eligible = eligibleSuccessCalibrationEvents(
            repeated + disputedSample + disputedReview + validSample
        )

        assertEquals(6, eligible.size)
        assertEquals(5, eligible.count { it.cardId == 1L })
        assertTrue(eligible.none { it.cardId == 2L })
        assertTrue(eligible.any { it.cardId == 3L })
    }

    @Test
    fun historicalMatcherRepairDropsOnlyDerivedItemDifficultyAndIsIdempotent() = runTest {
        val model = FakeLearningModelDao().apply {
            difficulties[10L] = ItemDifficulty(10L, observations = 4)
            difficulties[11L] = ItemDifficulty(11L, observations = 4)
        }
        val fixture = RepoFixture(withTelemetry = true, learningModelDao = model)
        fixture.telemetry!!.events += TelemetryEvent(
            timestamp = 1_000L,
            eventType = "review_committed",
            cardId = 10L,
            answerMode = AnswerMode.SPEAK.name,
            answerMatch = "WRONG",
            rating = Rating.GOOD.name
        )

        assertEquals(1, fixture.repository.repairHistoricalMatcherDisputes())
        assertNull(model.difficulties[10L])
        assertNotNull(model.difficulties[11L])
        assertEquals(0, fixture.repository.repairHistoricalMatcherDisputes())
    }

    @Test
    fun configuredFullDoseIsACeilingRatherThanAnAdaptiveSafetyOverride() = runTest {
        val bootstrap = (1..48).joinToString("\n") { index ->
            "{\"russian\":\"word$index\",\"lemma\":\"word$index\",\"pos\":\"noun\",\"translation\":\"word $index\",\"tier\":0,\"unit\":1}"
        }
        val fixture = RepoFixture(
            bootstrapNotes = bootstrap,
            config = { LearningConfig(dailyGoal = 40, sessionSize = 40, newCardsPerDay = 40) }
        )
        fixture.repository.seedIfEmpty()

        val plan = fixture.repository.sessionPlan(includeReaderInsights = false)

        assertTrue("cold start should still produce useful work", plan.reviewQueue.isNotEmpty())
        assertTrue(
            "the configured maximum must not force 40 fresh cards past the adaptive plan",
            plan.reviewQueue.size < 40
        )
    }

    @Test
    fun temporarySessionModesNeverRewriteAdaptivePlanOrLeakNewCardsIntoReviewsOnly() = runTest {
        val fixture = RepoFixture()
        fixture.repository.seedIfEmpty()
        val plan = fixture.repository.sessionPlan(includeReaderInsights = false)
        val reviewsOnly = TemporarySessionPolicy.queue(plan, TemporarySessionMode.REVIEWS_ONLY)
        assertTrue(reviewsOnly.none { it.card.state == CardState.NEW })
        assertTrue(TemporarySessionPolicy.queue(plan, TemporarySessionMode.READER_ONLY).isEmpty())
        assertTrue(TemporarySessionPolicy.queue(plan, TemporarySessionMode.FOCUS).size <= 8)
        assertEquals(plan.reviewQueue.size, fixture.repository.sessionPlan(includeReaderInsights = false).reviewQueue.size)
    }

    @Test
    fun learnerSnapshotFeedsSessionAndFluencyReadsFromTheSameModelRows() = runTest {
        val model = FakeLearningModelDao().apply {
            capacity = CapacityState(mu = 19.0, sigma = 3.0)
            willingness = WillingnessState(
                habit = 0.74,
                coeffsJson = "[0.2,0.8,-0.5,0.4,-0.6,-0.7]"
            )
            parameterRows["global_skill_mu"] = OptimizerParameter("global_skill_mu", 28.0)
            parameterRows["global_skill_sigma"] = OptimizerParameter("global_skill_sigma", 6.0)
            skillRows["production"] = SkillRating("production", mu = 1.5, sigma = 4.0)
            skillRows["not-a-real-skill"] = SkillRating("not-a-real-skill", mu = 99.0, sigma = 1.0)
            paceRows[1_000L] = PaceLog(1_000L, 12.0, 4, 0.9, 0.1, 0.9, "adaptive", "CLEAN_STOP")
        }
        val fixture = RepoFixture(withTelemetry = true, learningModelDao = model)
        fixture.notes.insert(Note(russian = "known", lemma = "known", translation = "known", partOfSpeech = "word", status = WordStatus.KNOWN))
        val activeNoteId = fixture.notes.insert(Note(russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun"))
        val activeCardId = fixture.cards.insert(
            Card(
                noteId = activeNoteId,
                cardType = CardType.RU_TO_MEANING,
                queue = Queue.VOCAB,
                state = CardState.REVIEW,
                due = 0L,
                reps = 2,
                consecutiveCorrect = 2,
                lastReview = 0L,
                stability = 3.0
            )
        )
        val now = 86_400_000L * 20
        val snapshot = fixture.repository.currentSnapshot(
            now = now,
            daily = fixture.repository.dailyPlan(now),
            gamification = fixture.repository.gamificationStats(now),
            recentTelemetry = fixture.repository.recentTelemetry(200)
        )

        assertEquals(19.0, snapshot.capacity.mu, 0.0)
        assertEquals(3.0, snapshot.capacity.sigma, 0.0)
        assertEquals(0.74, snapshot.willingness.habit, 0.0)
        assertEquals(1, snapshot.activeCards.count { it.id == activeCardId })
        assertEquals(2, snapshot.totalKnown)
        assertEquals(0.85, snapshot.recentAccuracy, 0.0)
        assertEquals(setOf(com.sibirskyspeak.learning.AbilitySkill.PRODUCTION), snapshot.world.skills.keys)

        val evidenceDays = model.allPaceLogs()
            .map { it.at / FluencySimEngine.DAY_MILLIS }
            .distinct()
            .size
        val expectedForecast = FluencySimEngine.runSimulation(
            currentCapacity = snapshot.capacity,
            currentWillingness = snapshot.willingness,
            initialActiveCards = snapshot.activeCards,
            totalKnownStart = snapshot.totalKnown,
            evidenceDays = evidenceDays,
            recentAccuracy = snapshot.recentAccuracy,
            startTimeMillis = now
        )
        assertEquals(expectedForecast, fixture.repository.getFluencyForecast(now))
        assertNotNull(fixture.repository.sessionPlan(now, includeReaderInsights = false).pace)
    }

    @Test
    fun worldSkillHelperKeepsWritePathFatigueSpecific() = runTest {
        val model = FakeLearningModelDao().apply {
            parameterRows["global_skill_mu"] = OptimizerParameter("global_skill_mu", 25.0)
            parameterRows["global_skill_sigma"] = OptimizerParameter("global_skill_sigma", 8.0)
            skillRows["production"] = SkillRating("production", mu = 1.0, sigma = 5.0)
        }
        val fixture = RepoFixture(withTelemetry = true, learningModelDao = model)
        val noteId = fixture.notes.insert(Note(russian = "слово", lemma = "слово", translation = "word", partOfSpeech = "noun"))
        val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
        val card = fixture.cards.cards.single { it.id == cardId }

        val low = fixture.repository.captureSuccessCalibrationExposure(card, fatigue = 0.1, at = 1_000L)
        val high = fixture.repository.captureSuccessCalibrationExposure(card, fatigue = 0.8, at = 1_000L)

        assertEquals(0.1, low?.sample?.fatigue ?: -1.0, 0.0)
        assertEquals(0.8, high?.sample?.fatigue ?: -1.0, 0.0)
    }

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

        assertTrue(ConceptDrills.coveredConceptIds().containsAll(upperConceptIds))
        assertEquals(GrammarConcepts.ALL.map { it.id }.toSet(), ConceptDrills.coveredConceptIds())
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
            {"russian":"войска́","lemma":"войска","pos":"noun","translation":"troops","gender":"PL","declensionJson":{"NOM_PL":"войска","GEN_PL":"войск"},"domainFreqRank":10,"cefrLevel":"B2","exampleSentence":"Здесь нет войск."}
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
                {"russian":"санкции","lemma":"санкция","pos":"noun","translation":"sanctions","gender":"F","declensionJson":{"NOM_PL":"санкции","GEN_PL":"санкций"},"domainFreqRank":1,"cefrLevel":"B2","exampleSentence":"Здесь нет санкций."}
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
    fun textbookSyncRefreshesTeachingContentWithoutReplacingLearnerRow() = runTest {
        val fixture = RepoFixture(
            bootstrapNotes = """
                {"russian":"\u0441\u0434\u0430\u0442\u044c","lemma":"tb_\u0441\u0434\u0430\u0442\u044c","pos":"verb","translation":"pass an exam; turn in","exampleSentence":"\u041e\u043d \u0441\u0434\u0430\u043b \u044d\u043a\u0437\u0430\u043c\u0435\u043d.","exampleTranslation":"He passed the exam.","declensionJson":"{\"INF\":\"\u0441\u0434\u0430\u0442\u044c\"}","tier":0,"unit":5,"tags":"textbook vocab"}
            """.trimIndent()
        )
        val existingId = fixture.notes.insert(
            Note(
                russian = "\u0441\u0434\u0430\u0442\u044c",
                lemma = "tb_\u0441\u0434\u0430\u0442\u044c",
                translation = "turn in",
                partOfSpeech = "word",
                exampleSentence = "\u0421\u0434\u0430\u044e \u043a\u0432\u0430\u0440\u0442\u0438\u0440\u0443.",
                declensionJson = "{\"INF\":\"tb_\u0441\u0434\u0430\u0442\u044c\"}",
                tags = "textbook vocab"
            )
        )

        assertEquals(0, fixture.repository.syncBootstrapTextbookNotes())

        val refreshed = fixture.notes.getByLemma("tb_\u0441\u0434\u0430\u0442\u044c")
        assertEquals(existingId, refreshed?.id)
        assertEquals("pass an exam; turn in", refreshed?.translation)
        assertEquals("He passed the exam.", refreshed?.exampleTranslation)
        assertEquals("{\"INF\":\"\u0441\u0434\u0430\u0442\u044c\"}", refreshed?.declensionJson)
    }

    @Test
    fun learnerContentRepairWidensOnlyTheKnownLegacyGloss() = runTest {
        val fixture = RepoFixture()
        fixture.notes.insert(
            Note(
                russian = "\u0432\u043e\u0442",
                lemma = "\u0432\u043e\u0442",
                translation = "here is",
                partOfSpeech = "particle"
            )
        )

        assertEquals(1, fixture.repository.repairLearnerContent())
        assertEquals("here; here is", fixture.notes.getByLemma("\u0432\u043e\u0442")?.translation)
        assertEquals(0, fixture.repository.repairLearnerContent())
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
    fun ignoredNoiseIsExcludedFromReaderCoverageAndKnownVocabulary() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "Том", lemma = "том", translation = "Tom", partOfSpeech = "noun", status = WordStatus.IGNORED))
        fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.GRADUATED))
        fixture.readers.insert(ReaderText(title = "Names", body = "Том пришёл.", source = "local"))

        val plan = fixture.repository.sessionPlan()
        val reader = fixture.repository.readerTexts().single()

        assertEquals(0, plan.gamification.knownWords)
        assertEquals(0, reader.knownTokens)
        assertEquals(0.0, reader.coverage, 0.0001)
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
    fun explicitlyMarkedMultisyllabicCourseWordGetsTargetedStressPractice() = runTest {
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
    fun acquisitionPracticeKeepsMeaningRecallSimpleAcrossEarlyRepeats() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"exampleSentence":"Это дом.","exampleTranslation":"This is a house."}"""
        )
        val card = fixture.cards.cards.first { it.cardType == CardType.RU_TO_MEANING }

        val repeat = fixture.repository.practicePromptFor(card, round = 2)

        assertNotNull(repeat)
        assertEquals(AnswerMode.ENGLISH, repeat?.answerMode)
        assertEquals("дом", repeat?.prompt)
        assertFalse(repeat?.prompt.orEmpty().contains("In context:"))
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
    fun readerLookupRecordsNeedForHelpButNeverGraduatesVocabulary() = runTest {
        val fixture = RepoFixture()
        fixture.repository.seedIfEmpty()

        repeat(15) {
            val textId = fixture.readers.insert(ReaderText(title = "t$it", body = "войска", source = "test"))
            fixture.repository.lookupReaderToken("войска", textId, now = it.toLong())
        }

        val note = fixture.notes.getByLemma("войска")
        assertEquals(0, note?.encounterCount)
        assertEquals(15, fixture.readerEncounters.encounters.size)
        assertTrue("reader lookup is exposure, not recall review", fixture.logs.logs.none { it.source == ReviewSource.READER_LOOKUP })
        assertFalse(fixture.cards.cards.filter { it.noteId == note?.id && it.queue == Queue.VOCAB }.all { it.state == CardState.GRADUATED })
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
        assertEquals(0, note?.encounterCount)
        assertEquals(1, fixture.readerEncounters.encounters.size)
        assertEquals(14L, fixture.readerEncounters.encounters.single().encounteredAt)
        assertFalse(
            "same-text repeated taps should not graduate vocab",
            fixture.cards.cards.filter { it.noteId == note?.id && it.queue == Queue.VOCAB }.all { it.state == CardState.GRADUATED }
        )
    }

    @Test
    fun passiveEvidenceDoesNotCountAsRecallMetrics() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "word", lemma = "word", translation = "word", partOfSpeech = "noun"))
        val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW))
        listOf(ReviewSource.READER_LOOKUP, ReviewSource.READING, ReviewSource.LISTENING, ReviewSource.PRODUCTION).forEachIndexed { index, source ->
            fixture.logs.insert(ReviewLog(
                cardId = cardId,
                reviewDatetime = 1_000L + index,
                rating = Rating.GOOD,
                stateBefore = CardState.REVIEW,
                scheduledDays = 5,
                elapsedDays = 5,
                source = source
            ))
        }

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
        // Keep only defensible default cues; generic RESULT/SINGLE_EVENT carriers
        // taught brittle cue-word shortcuts and were often semantically invalid.
        assertEquals(6, aspectCards.size)
        assertEquals(
            setOf("PROCESS", "HABITUAL", "COMPLETED"),
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
    fun sessionOrderVariesAcrossEquallyEligibleDueCards() = runTest {
        // Six same-difficulty, same-facet due cards give the balanceSkills zigzag two
        // full shuffle bands to work with. Anki shuffles its due queue so the same set
        // of cards doesn't drill in the exact same order every time; this asserts this
        // app's now-seeded shuffle actually varies the slot order (not a no-op) while
        // still returning the identical set of cards either way.
        val fixture = RepoFixture()
        val cardIds = (1..6).map { index ->
            val noteId = fixture.notes.insert(
                Note(russian = "due$index", lemma = "due$index", translation = "due $index", partOfSpeech = "noun")
            )
            fixture.cards.insert(
                Card(
                    noteId = noteId,
                    cardType = CardType.RU_TO_MEANING,
                    queue = Queue.VOCAB,
                    due = 100L,
                    state = CardState.REVIEW,
                    lastReview = 0L
                )
            )
        }.toSet()

        val orderA = fixture.repository.sessionPlan(now = 5_000L).reviewQueue.map { it.card.id }
        val orderB = fixture.repository.sessionPlan(now = 8_765_432L).reviewQueue.map { it.card.id }

        assertEquals("both sessions should surface the same due cards", cardIds, orderA.toSet())
        assertEquals("both sessions should surface the same due cards", cardIds, orderB.toSet())
        assertTrue("the two now-seeded sessions should not always produce the same order", orderA != orderB)
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
        assertTrue(target.syntaxComplexity > 0.0)
        assertTrue(target.difficultyScore > 0.0)
        assertTrue(target.difficultyLabel in setOf("gentle", "stretch", "challenging"))
        assertEquals(15, tokens.count { it.known })
        assertTrue(tokens.filter { it.known }.any { it.parse != null })
        assertTrue(stats.authenticReady)
        assertEquals(4, stats.noteCount)
        assertFalse(stats.importQualityReport.meetsDesignDocMinimum)
        assertTrue(stats.importQualityReport.warnings.isNotEmpty())
    }

    @Test
    fun importedReaderSourceCanBeCorrectedAfterImport() = runTest {
        val fixture = RepoFixture()
        val id = fixture.repository.addReaderText("Imported", "дом", "unknown")
        assertTrue(fixture.repository.updateReaderSource(id, "Author — CC BY 4.0 — https://example.test"))
        val updated = fixture.repository.readerTexts().single { it.text.id == id }
        assertEquals("Author — CC BY 4.0 — https://example.test", updated.text.source)
    }

    @Test
    fun readerBookmarksToggleAndRemainAttachedToTheirText() = runTest {
        val fixture = RepoFixture()
        val id = fixture.repository.addReaderText("Book", "дом книга", "local")
        assertTrue(fixture.repository.toggleReaderBookmark(id, 1, "книга"))
        assertEquals("книга", fixture.repository.readerBookmarks(id).single().label)
        assertFalse(fixture.repository.toggleReaderBookmark(id, 1))
        assertTrue(fixture.repository.readerBookmarks(id).isEmpty())
    }

    @Test
    fun readerBookmarksRoundTripThroughFullStateBackup() = runTest {
        val source = RepoFixture()
        val sourceId = source.repository.addReaderText("Book", "дом книга", "Author · CC BY")
        source.repository.toggleReaderBookmark(sourceId, 1, "книга")
        val payload = source.repository.exportFullState()

        val restored = RepoFixture()
        restored.repository.importJsonLines(payload)
        val restoredId = restored.readers.texts.single { it.title == "Book" }.id
        assertEquals("книга", restored.repository.readerBookmarks(restoredId).single().label)
    }

    @Test
    fun parallelReaderTranslationRoundTripsThroughFullStateBackup() = runTest {
        val source = RepoFixture()
        source.repository.addReaderText(
            title = "Parallel",
            body = "Анна читает. Потом она спит.",
            source = "story:test",
            translationBody = "Anna reads. Then she sleeps."
        )
        val payload = source.repository.exportFullState()

        val restored = RepoFixture()
        restored.repository.importJsonLines(payload)

        assertEquals(
            "Anna reads. Then she sleeps.",
            restored.readers.texts.single { it.title == "Parallel" }.translationBody
        )
    }

    @Test
    fun curriculumProvenanceComesFromBundledManifestAndCachesSafely() = runTest {
        val fixture = RepoFixture(
            bootstrapManifest = """{"provenance":{"sources":[{"id":"custom","attribution":"Curated source","license":"CC0"}]}}"""
        )
        val first = fixture.repository.curriculumProvenance()
        val second = fixture.repository.curriculumProvenance()
        assertEquals(listOf(ContentProvenance("custom", "Curated source", "CC0")), first)
        assertEquals(first, second)
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
    fun readerTextsCountDueOverlapAndPreferItAsATiebreaker() = runTest {
        val fixture = RepoFixture()
        val dueNoteId = fixture.notes.insert(Note(russian = "иду", lemma = "иду", translation = "I go", partOfSpeech = "verb", tier = 0, status = WordStatus.KNOWN))
        fixture.cards.insert(Card(noteId = dueNoteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, stability = 5.0, due = 0L))
        val farNoteId = fixture.notes.insert(Note(russian = "вижу", lemma = "вижу", translation = "I see", partOfSpeech = "verb", tier = 0, status = WordStatus.KNOWN))
        fixture.cards.insert(Card(noteId = farNoteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, stability = 5.0, due = 30L * 86_400_000L))
        fixture.repository.addReaderText("Due soon", "иду домой", "local")
        fixture.repository.addReaderText("Not due", "вижу дом", "local")

        val recs = fixture.repository.readerTexts(now = 0L)

        assertEquals(1, recs.first { it.text.title == "Due soon" }.dueOverlap)
        assertEquals(0, recs.first { it.text.title == "Not due" }.dueOverlap)
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
    fun conceptStaysOnProbationUntilFirstDrillSucceeds() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """
            {"russian":"Noun gender","lemma":"lesson_gender","pos":"lesson","translation":"Noun gender","conceptId":"GENDER","tier":0,"unit":1,"generalFreqRank":0}
            {"russian":"стол","lemma":"стол","pos":"noun","translation":"table","gender":"M","declensionJson":{"NOM_SG":"стол","GEN_SG":"стола"},"tier":0,"unit":1,"generalFreqRank":1}
            """.trimIndent()
        )
        val noteId = fixture.notes.getByLemma("стол")!!.id
        // A second drill mapped to the same concept via an explicit gramConcept (see
        // GrammarConcepts.forCard's priority order), so probation has more than one
        // candidate card to choose between — the scenario a single-drill concept can't
        // exercise.
        val secondDrillId = fixture.cards.insert(
            Card(noteId = noteId, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramConcept = "GENDER", gramCase = "ACC")
        )

        val lesson = fixture.repository.sessionPlan(now = 0L).reviewQueue.first { it.card.cardType == CardType.LESSON }.card
        fixture.repository.review(lesson, Rating.GOOD, now = 1_000L)
        // GENDER_ID additionally waits for a word encounter (see the test below);
        // clear that gate too so both drills are otherwise eligible.
        val firstVocab = fixture.repository.sessionPlan(now = 2_000L).reviewQueue.first { it.card.queue == Queue.VOCAB }.card
        fixture.repository.review(firstVocab, Rating.GOOD, now = 3_000L)

        val genderDrillIds = fixture.cards.cards
            .filter { it.gramConcept == "GENDER" && it.cardType != CardType.LESSON }
            .map { it.id }
            .toSet()
        assertEquals("fixture should include generated, baseline, and manual GENDER drills", 3, genderDrillIds.size)
        assertTrue("the manually-inserted card should be one of the two", secondDrillId in genderDrillIds)
        // Probation deterministically admits the lower-id drill — which one that is
        // isn't the point here, only that exactly one of the two gets in.
        val probationCardId = genderDrillIds.min()

        val onProbation = fixture.repository.sessionPlan(now = 4_000L).reviewQueue
        val surfaced = onProbation.filter { it.card.id in genderDrillIds }.map { it.card.id }.toSet()
        assertEquals("only the probation card should surface, not its sibling", setOf(probationCardId), surfaced)

        // Fail the probation card: its sibling must stay locked (a miss doesn't
        // hand out a free pass to the rest of the concept).
        fixture.repository.review(fixture.cards.cards.first { it.id == probationCardId }, Rating.AGAIN, now = 5_000L)
        val afterMiss = fixture.repository.sessionPlan(now = 6_000L).reviewQueue
        assertFalse(
            "the sibling drill must stay locked after a miss on the probation card",
            afterMiss.any { it.card.id != probationCardId && it.card.id in genderDrillIds }
        )

        // Succeed on the probation card (its next attempt, however it resurfaces):
        // the sibling should now be eligible.
        fixture.repository.review(fixture.cards.cards.first { it.id == probationCardId }, Rating.GOOD, now = 7_000L)
        val afterSuccess = fixture.repository.sessionPlan(now = 8_000L).reviewQueue
        assertTrue(
            "the sibling drill should open up once the probation card succeeds",
            afterSuccess.any { it.card.id == secondDrillId } ||
                fixture.cards.cards.any { it.id == secondDrillId && it.state == CardState.NEW }
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
            afterWordEncounter.any { it.card.gramConcept == "GENDER" && it.card.queue == Queue.GRAMMAR }
        )
    }

    @Test
    fun upperLevelLessonNotesCreateLockedConceptDrills() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """
            {"russian":"Numbers and nouns","lemma":"lesson_numeral_case","pos":"lesson","translation":"Numbers and nouns","conceptId":"NUMERAL_CASE","tier":0,"unit":26,"generalFreqRank":0}
            {"russian":"два дома","lemma":"два_дома","pos":"phrase","translation":"two houses","tier":0,"unit":26,"generalFreqRank":1}
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

        val afterLesson = fixture.repository.sessionPlan(now = 2_000L).reviewQueue
        assertFalse(afterLesson.any { it.card.cardType == CardType.CONCEPT_DRILL })
        fixture.repository.review(afterLesson.first { it.card.queue == Queue.VOCAB }.card, Rating.GOOD, now = 3_000L)

        val after = fixture.repository.sessionPlan(now = 4_000L).reviewQueue
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

    private fun testFrame(id: String, concept: String) = ContentFrame(
        id = id, concept = concept, band = "A1",
        slotsJson = """[{"role":"obj","pos":"noun","case":"GEN","number":"SG","target":true}]""",
        ruFrame = "У меня нет {obj}.", enFrame = "I don't have {obj}."
    )

    @Test
    fun syncMissingConceptApplyCardsOnlyCreatesCardsForConceptsWithShippedFrames() = runTest {
        val fixture = RepoFixture(contentDao = FakeContentDao(mapOf("GEN" to listOf(testFrame("gen_negation_net", "GEN")))))
        val genNoteId = fixture.notes.insert(
            Note(russian = "Genitive", lemma = "lesson_gen", translation = "Genitive", partOfSpeech = "lesson", conceptId = "GEN")
        )
        fixture.cards.insert(Card(noteId = genNoteId, cardType = CardType.LESSON, queue = Queue.GRAMMAR, gramConcept = "GEN"))
        val accNoteId = fixture.notes.insert(
            Note(russian = "Accusative", lemma = "lesson_acc", translation = "Accusative", partOfSpeech = "lesson", conceptId = "ACC")
        )
        fixture.cards.insert(Card(noteId = accNoteId, cardType = CardType.LESSON, queue = Queue.GRAMMAR, gramConcept = "ACC"))

        fixture.repository.seedIfEmpty()

        assertTrue(fixture.cards.cards.any { it.noteId == genNoteId && it.cardType == CardType.CONCEPT_APPLY && it.gramConcept == "GEN" })
        // ACC has a lesson but no shipped frame yet — no card should be fabricated for it.
        assertFalse(fixture.cards.cards.any { it.noteId == accNoteId && it.cardType == CardType.CONCEPT_APPLY })
    }

    @Test
    fun syncMissingNovelProduceCardsWaitsForConceptApplyToHaveAFewReps() = runTest {
        val fixture = RepoFixture(contentDao = FakeContentDao(mapOf("GEN" to listOf(testFrame("gen_negation_net", "GEN")))))
        val readyNoteId = fixture.notes.insert(
            Note(russian = "Genitive", lemma = "lesson_gen_ready", translation = "Genitive", partOfSpeech = "lesson", conceptId = "GEN")
        )
        fixture.cards.insert(Card(noteId = readyNoteId, cardType = CardType.LESSON, queue = Queue.GRAMMAR, gramConcept = "GEN", state = CardState.REVIEW, reps = 1))
        fixture.cards.insert(Card(noteId = readyNoteId, cardType = CardType.CONCEPT_APPLY, queue = Queue.GRAMMAR, gramConcept = "GEN", state = CardState.REVIEW, reps = 2))

        fixture.repository.seedIfEmpty()

        assertTrue(
            "NOVEL_PRODUCE should mint once CONCEPT_APPLY has a couple of reps",
            fixture.cards.cards.any { it.noteId == readyNoteId && it.cardType == CardType.NOVEL_PRODUCE }
        )
    }

    @Test
    fun taperedConceptStopsIntroducingNewPerNoteGrammarDrills() = runTest {
        val fixture = RepoFixture()
        val lessonNoteId = fixture.notes.insert(
            Note(russian = "Genitive", lemma = "lesson_gen", translation = "Genitive", partOfSpeech = "lesson", conceptId = "GEN")
        )
        fixture.cards.insert(
            Card(noteId = lessonNoteId, cardType = CardType.LESSON, queue = Queue.GRAMMAR, gramConcept = "GEN", state = CardState.REVIEW, reps = 1)
        )
        // A proven CONCEPT_APPLY card for GEN: reps>=4 and consecutiveCorrect>=3 (taper threshold).
        fixture.cards.insert(
            Card(noteId = lessonNoteId, cardType = CardType.CONCEPT_APPLY, queue = Queue.GRAMMAR, gramConcept = "GEN",
                state = CardState.REVIEW, reps = 4, consecutiveCorrect = 3)
        )
        val wordNoteId = fixture.notes.insert(
            Note(russian = "стол", lemma = "стол", translation = "table", partOfSpeech = "noun", tier = 0, unit = 1,
                gender = "M", encounterCount = 5)
        )
        // A not-yet-introduced per-note drill for the same concept (CASE_FILL derives
        // its concept from gramCase, matching how GrammarConcepts.forCard reads it).
        fixture.cards.insert(Card(noteId = wordNoteId, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramCase = "GEN", gramNumber = "SG"))

        val plan = fixture.repository.sessionPlan(now = 0L)

        assertFalse(
            "tapered concept's per-note drill should not be newly introduced",
            plan.reviewQueue.any { it.card.cardType == CardType.CASE_FILL }
        )
    }

    @Test
    fun launchMaintenanceDoesNotMintUnglossedChunks() = runTest {
        val fixture = RepoFixture(contentDao = FakeContentDao(
            chunksByLemma = mapOf("диван" to listOf(ContentCollocation("диван", "на диване", 8)))
        ))
        val matureNoteId = fixture.notes.insert(
            Note(russian = "диван", lemma = "диван", translation = "sofa", partOfSpeech = "noun", tier = 0, unit = 1, gender = "M")
        )
        fixture.cards.insert(
            Card(noteId = matureNoteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
                state = CardState.REVIEW, reps = 3, consecutiveCorrect = 2)
        )
        val immatureNoteId = fixture.notes.insert(
            Note(russian = "стол", lemma = "стол", translation = "table", partOfSpeech = "noun", tier = 0, unit = 1, gender = "M")
        )
        fixture.cards.insert(
            Card(noteId = immatureNoteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
                state = CardState.NEW, reps = 0, consecutiveCorrect = 0)
        )

        fixture.repository.seedIfEmpty()

        assertFalse(
            "a collocation without an authored gloss must not become a review card",
            fixture.cards.cards.any { it.cardType == CardType.CHUNK }
        )
    }

    @Test
    fun launchMaintenanceRetiresCaseLabelsAndPurgesInvalidRootMastery() = runTest {
        val model = FakeLearningModelDao().also {
            it.upsertMastery(ConceptMastery("root:тена", observations = 4))
            it.upsertMastery(ConceptMastery("root:стена", observations = 2))
        }
        val fixture = RepoFixture(
            contentDao = FakeContentDao(legacySingleLetterRoots = listOf("тена")),
            learningModelDao = model
        )
        val noteId = fixture.notes.insert(
            Note(
                russian = "вини́тельный",
                lemma = "tb_винительный",
                translation = "Accusative",
                partOfSpeech = "word",
                tags = "textbook vocab"
            )
        )
        fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
        fixture.cards.insert(Card(noteId = noteId, cardType = CardType.SENTENCE_BUILD, queue = Queue.VOCAB))
        val existentialId = fixture.notes.insert(
            Note(
                russian = "есть",
                lemma = "есть",
                translation = "there is, there are",
                partOfSpeech = "verb"
            )
        )
        fixture.cards.insert(Card(noteId = existentialId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
        fixture.cards.insert(Card(noteId = existentialId, cardType = CardType.VERB_FORM, queue = Queue.GRAMMAR))
        fixture.cards.insert(Card(noteId = existentialId, cardType = CardType.TRANSFORM, queue = Queue.VOCAB))

        fixture.repository.seedIfEmpty()

        assertTrue(fixture.cards.cards.filter { it.noteId == noteId }.all { it.suspended })
        assertFalse(model.masteries.containsKey("root:тена"))
        assertTrue(model.masteries.containsKey("root:стена"))
        assertFalse(fixture.cards.cards.first {
            it.noteId == existentialId && it.cardType == CardType.RU_TO_MEANING
        }.suspended)
        assertTrue(fixture.cards.cards.filter {
            it.noteId == existentialId && it.cardType in setOf(CardType.VERB_FORM, CardType.TRANSFORM)
        }.all { it.suspended })
    }

    @Test
    fun chunkCardWaitsForParentRecognitionMaturityEvenIfAlreadyMinted() = runTest {
        val fixture = RepoFixture()
        val parentId = fixture.notes.insert(
            Note(russian = "стол", lemma = "стол", translation = "table", partOfSpeech = "noun", tier = 0, unit = 1, gender = "M")
        )
        fixture.cards.insert(
            Card(noteId = parentId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
                state = CardState.NEW, reps = 0, consecutiveCorrect = 0)
        )
        val chunkNoteId = fixture.notes.insert(
            Note(russian = "на столе", lemma = "на столе", translation = "", partOfSpeech = "chunk", tier = 0, chunkParentNoteId = parentId)
        )
        fixture.cards.insert(Card(noteId = chunkNoteId, cardType = CardType.CHUNK, queue = Queue.VOCAB))

        val plan = fixture.repository.sessionPlan(now = 0L)

        assertFalse(
            "chunk card should not surface before its parent's recognition has matured",
            plan.reviewQueue.any { it.card.cardType == CardType.CHUNK }
        )
    }

    @Test
    fun syncMissingTransformCardsOnlyMintsForMatureVerbRecognition() = runTest {
        val fixture = RepoFixture()
        val matureId = fixture.notes.insert(
            Note(russian = "читать", lemma = "читать", translation = "to read", partOfSpeech = "verb", tier = 0, unit = 1)
        )
        fixture.cards.insert(
            Card(noteId = matureId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
                state = CardState.REVIEW, reps = 3, consecutiveCorrect = 2)
        )
        val immatureId = fixture.notes.insert(
            Note(russian = "писать", lemma = "писать", translation = "to write", partOfSpeech = "verb", tier = 0, unit = 1)
        )
        fixture.cards.insert(
            Card(noteId = immatureId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
                state = CardState.NEW, reps = 0, consecutiveCorrect = 0)
        )

        fixture.repository.seedIfEmpty()

        assertFalse(fixture.cards.cards.any { it.noteId == matureId && it.cardType == CardType.TRANSFORM })
        assertFalse(fixture.cards.cards.any { it.noteId == immatureId && it.cardType == CardType.TRANSFORM })
    }

    @Test
    fun transformCardWaitsForOwnRecognitionMaturityEvenIfAlreadyMinted() = runTest {
        val fixture = RepoFixture()
        val verbId = fixture.notes.insert(
            Note(russian = "писать", lemma = "писать", translation = "to write", partOfSpeech = "verb", tier = 0, unit = 1)
        )
        fixture.cards.insert(
            Card(noteId = verbId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
                state = CardState.NEW, reps = 0, consecutiveCorrect = 0)
        )
        fixture.cards.insert(Card(noteId = verbId, cardType = CardType.TRANSFORM, queue = Queue.VOCAB))

        val plan = fixture.repository.sessionPlan(now = 0L)

        assertFalse(
            "transform card should not surface before its own recognition has matured",
            plan.reviewQueue.any { it.card.cardType == CardType.TRANSFORM }
        )
    }

    @Test
    fun syncMissingSpeakSentenceCardsOnlyMintsForMatureRecognition() = runTest {
        val fixture = RepoFixture()
        val matureId = fixture.notes.insert(Note(russian = "стол", lemma = "стол", translation = "table", partOfSpeech = "noun", tier = 0, unit = 1))
        fixture.cards.insert(
            Card(noteId = matureId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 3, consecutiveCorrect = 2)
        )
        val immatureId = fixture.notes.insert(Note(russian = "окно", lemma = "окно", translation = "window", partOfSpeech = "noun", tier = 0, unit = 1))
        fixture.cards.insert(
            Card(noteId = immatureId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.NEW, reps = 0, consecutiveCorrect = 0)
        )

        fixture.repository.seedIfEmpty()

        assertFalse(fixture.cards.cards.any { it.noteId == matureId && it.cardType == CardType.SPEAK_SENTENCE })
        assertFalse(fixture.cards.cards.any { it.noteId == immatureId && it.cardType == CardType.SPEAK_SENTENCE })
    }

    @Test
    fun speakSentenceCardWaitsForOwnRecognitionMaturityEvenIfAlreadyMinted() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "окно", lemma = "окно", translation = "window", partOfSpeech = "noun", tier = 0))
        fixture.cards.insert(
            Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.NEW, reps = 0, consecutiveCorrect = 0)
        )
        fixture.cards.insert(Card(noteId = noteId, cardType = CardType.SPEAK_SENTENCE, queue = Queue.VOCAB))

        val plan = fixture.repository.sessionPlan(now = 0L)

        assertFalse(
            "speak-sentence card should not surface before its own recognition has matured",
            plan.reviewQueue.any { it.card.cardType == CardType.SPEAK_SENTENCE }
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
    fun placementAfterLevelCreatesUncertainRecognitionPriorOnly() = runTest {
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
        assertEquals(WordStatus.LEARNING, a1.status)
        assertEquals(WordStatus.NEW, a2.status)
        val a1Cards = fixture.cards.cards.filter { it.noteId == a1.id }
        assertTrue(a1Cards.first { it.cardType == CardType.RU_TO_MEANING }.state == CardState.GRADUATED)
        assertTrue(a1Cards.filterNot { it.cardType == CardType.RU_TO_MEANING }.all { it.state == CardState.NEW })
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
        assertEquals(WordStatus.NEW, fixture.notes.getByLemma("novoe")?.status)
    }

    @Test
    fun savingReaderGlossMovesUnknownWordIntoLearningWithCards() = runTest {
        val fixture = RepoFixture()

        fixture.repository.setWordStatus("novoe", WordStatus.LEARNING, now = 1_000L)
        val pending = fixture.notes.getByLemma("novoe")!!
        assertEquals(WordStatus.NEW, pending.status)
        assertEquals("lookup pending", pending.translation)

        val saved = fixture.repository.saveReaderWordGloss("novoe", "a new word", now = 2_000L)!!

        assertEquals(WordStatus.LEARNING, saved.status)
        assertEquals("a new word", saved.translation)
        assertTrue(fixture.cards.cards.any { it.noteId == saved.id && it.queue == Queue.VOCAB })
    }

    @Test
    fun dailyTargetSeparatesCardsFromReadingButCountsBothAsLearningActions() = runTest {
        val now = System.currentTimeMillis()
        val fixture = RepoFixture(config = { LearningConfig(dailyGoal = 2) })
        val noteId = fixture.notes.insert(Note(russian = "слово", lemma = "слово", translation = "word", partOfSpeech = "noun"))
        val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
        fixture.logs.insert(
            ReviewLog(
                cardId = cardId,
                reviewDatetime = now,
                rating = Rating.GOOD,
                stateBefore = CardState.REVIEW,
                scheduledDays = 1,
                elapsedDays = 1,
                source = ReviewSource.SRS_REVIEW
            )
        )
        fixture.readingActivities.insert(ReadingActivity(readerTextId = 1L, completedAt = now, mistakes = 0, intervalDays = 2))

        val stats = fixture.repository.gamificationStats(now)

        assertEquals(1, stats.reviewedToday)
        assertEquals(1, stats.readingToday)
        assertEquals(2, stats.learningActionsToday)
        assertTrue(stats.goalReached)
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
    fun lockedFutureCardsAreNotReportedAsAnExhaustedDailyBudget() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 10, sessionSize = 10) })
        fixture.repository.importJsonLines(
            """
            {"russian":"one","lemma":"one","pos":"word","translation":"one","tier":0,"unit":1}
            {"russian":"two","lemma":"two","pos":"word","translation":"two","tier":0,"unit":2}
            """.trimIndent()
        )
        val first = fixture.notes.getByLemma("one")!!
        fixture.notes.update(first.copy(status = WordStatus.KNOWN))

        val plan = fixture.repository.sessionPlan(now = 1_000L)

        assertTrue(plan.reviewQueue.isEmpty())
        assertEquals(DailyLearningStatus.SCHEDULED_COMPLETE, plan.completion.status)
    }

    @Test
    fun unitMasterySlidingWindowOpensUnitsAheadOnceTheFrontierIsStarted() = runTest {
        // P6.5: once the current (frontier) unit has genuine progress — not full
        // mastery, just started — the next two units open too, instead of staying
        // hard-locked behind 100% completion of everything before them.
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 10, sessionSize = 10) })
        fixture.repository.importJsonLines(
            """
            {"russian":"один","lemma":"один","pos":"word","translation":"one","tier":0,"unit":1}
            {"russian":"полтора","lemma":"полтора","pos":"word","translation":"one and a half","tier":0,"unit":1}
            {"russian":"два","lemma":"два","pos":"word","translation":"two","tier":0,"unit":2}
            {"russian":"три","lemma":"три","pos":"word","translation":"three","tier":0,"unit":3}
            {"russian":"четыре","lemma":"четыре","pos":"word","translation":"four","tier":0,"unit":4}
            """.trimIndent()
        )
        // Unit 1 is only half-mastered (one of its two words), so it's the frontier
        // (progress 50% < 80% threshold) — but it has genuinely been started.
        val unitOneNote = fixture.notes.getByLemma("один")!!
        val recognitionCard = fixture.cards.cards.first { it.noteId == unitOneNote.id && it.cardType == CardType.RU_TO_MEANING }
        fixture.cards.update(recognitionCard.copy(state = CardState.GRADUATED, reps = 3, consecutiveCorrect = 3))

        val mastery = fixture.repository.sessionPlan(now = 0L).unitMastery.associateBy { it.unit }

        assertTrue("frontier unit itself stays unlocked", mastery.getValue(1).unlocked)
        assertTrue("one unit past the started frontier should open", mastery.getValue(2).unlocked)
        assertTrue("two units past the started frontier should open", mastery.getValue(3).unlocked)
        assertFalse("three units past the frontier is outside the sliding window", mastery.getValue(4).unlocked)
    }

    @Test
    fun buildExitTicketSessionAssemblesMixedFacetsFromUnitInventory() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """
            {"russian":"дом","lemma":"дом","pos":"word","translation":"house","tier":0,"unit":1,"exampleSentence":"Это мой дом.","exampleTranslation":"This is my house."}
            {"russian":"книга","lemma":"книга","pos":"word","translation":"book","tier":0,"unit":1,"exampleSentence":"Я читаю книгу.","exampleTranslation":"I am reading a book."}
            {"russian":"стол","lemma":"стол","pos":"word","translation":"table","tier":0,"unit":1}
            {"russian":"два","lemma":"два","pos":"word","translation":"two","tier":0,"unit":2}
            """.trimIndent()
        )

        val session = fixture.repository.buildExitTicketSession(unit = 1)

        assertNotNull(session)
        assertEquals(1, session!!.unit)
        val kinds = session.items.map { it.kind }
        assertTrue("expected a recognition item", "recognition" in kinds)
        assertTrue("expected a production item", "production" in kinds)
        assertTrue("expected a listening item", "listening" in kinds)
        assertTrue("expected a reading item drawn from a note with an example sentence", "reading" in kinds)
        assertFalse("typed composition must never appear in a capstone", "composition" in kinds)
        assertTrue("every capstone response must be tap-only", session.items.all { it.choices.size >= 2 })
        assertTrue(
            "the correct choice must occur exactly once",
            session.items.all { item -> item.choices.count { it.equals(item.expectedAnswer, ignoreCase = true) } == 1 }
        )
        assertTrue(
            "correct answers must not be hard-coded into the first position",
            session.items.any { item -> !item.choices.first().equals(item.expectedAnswer, ignoreCase = true) }
        )
        // Every item's noteId must belong to unit 1 — never unit 2's inventory.
        val unitOneIds = setOf(
            fixture.notes.getByLemma("дом")!!.id,
            fixture.notes.getByLemma("книга")!!.id,
            fixture.notes.getByLemma("стол")!!.id
        )
        assertTrue(session.items.all { it.noteId == null || it.noteId in unitOneIds })
        assertTrue("listening must hide its carrier and expose it only as audio", session.items.first { it.kind == "listening" }.audioPrompt?.isNotBlank() == true)
    }

    @Test
    fun buildExitTicketSessionReturnsNullForAnEmptyOrSingleWordUnit() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"дом","lemma":"дом","pos":"word","translation":"house","tier":0,"unit":1}"""
        )

        assertNull(fixture.repository.buildExitTicketSession(unit = 1))
        assertNull(fixture.repository.buildExitTicketSession(unit = 99))
    }

    @Test
    fun dialogueOnlyCurriculumUnitStillBuildsATapOnlyCapstone() = runTest {
        val id = "b1_unit_030_dialogue"
        val dialogue = ContentDialogue(id, 30, "handle a situation", "Travel problem")
        val learnerTurns = listOf(
            "Мне нужна помощь." to "I need help.",
            "Где вокзал?" to "Where is the station?",
            "Спасибо за помощь." to "Thank you for the help."
        )
        val nodes = (1..6).map { index ->
            val learner = index % 2 == 0
            val pair = learnerTurns[((index - 1) / 2).coerceIn(0, learnerTurns.lastIndex)]
            ContentDialogueNode(
                id = "$id:$index", dialogueId = id,
                speaker = if (learner) "learner" else "npc",
                ru = if (learner) pair.first else "Что вы скажете?",
                en = if (learner) pair.second else "Respond in Russian: ${pair.second}",
                acceptableJson = if (learner) "[\"${pair.first}\"]" else null,
                nextIdsJson = if (index == 6) "[]" else "[\"$id:${index + 1}\"]"
            )
        }
        val fixture = RepoFixture(
            contentDao = FakeContentDao(dialogues = listOf(dialogue), dialogueNodes = mapOf(id to nodes))
        )
        fixture.repository.importJsonLines(
            """
            {"russian":"помощь","lemma":"помощь","pos":"noun","translation":"help","tier":0,"unit":29,"cefrLevel":"B1","exampleSentence":"Мне нужна помощь.","exampleTranslation":"I need help."}
            {"russian":"Unit lesson","lemma":"lesson_b1_30","pos":"lesson","translation":"Travel problems","tier":0,"unit":30,"cefrLevel":"B1"}
            """.trimIndent()
        )

        val session = fixture.repository.buildExitTicketSession(30, "B1")

        assertNotNull(session)
        assertTrue(session!!.items.size >= 4)
        assertEquals(3, session.items.count { it.kind == "dialogue" })
        assertTrue(session.items.all { it.choices.size >= 2 })
    }

    @Test
    fun unitCapstoneIncludesEveryLearnerTurnFromItsExactBandDialogue() = runTest {
        val id = "a1_unit_005_dialogue"
        val dialogue = ContentDialogue(id, 5, "use unit language", "Unit role-play")
        val nodes = (1..6).map { index ->
            val learner = index % 2 == 0
            ContentDialogueNode(
                id = "$id:$index", dialogueId = id,
                speaker = if (learner) "learner" else "npc",
                ru = if (learner) "Я читаю книгу." else "Что вы скажете?",
                en = if (learner) "I am reading a book." else "Respond in Russian",
                acceptableJson = if (learner) "[\"Я читаю книгу.\"]" else null,
                nextIdsJson = if (index == 6) "[]" else "[\"$id:${index + 1}\"]"
            )
        }
        val fixture = RepoFixture(contentDao = FakeContentDao(dialogues = listOf(dialogue), dialogueNodes = mapOf(id to nodes)))
        fixture.repository.importJsonLines(
            """
            {"russian":"книга","lemma":"книга","pos":"noun","translation":"book","tier":0,"unit":5,"cefrLevel":"A1","exampleSentence":"Я читаю книгу.","exampleTranslation":"I am reading a book."}
            {"russian":"дом","lemma":"дом","pos":"noun","translation":"house","tier":0,"unit":5,"cefrLevel":"A1","exampleSentence":"Это мой дом.","exampleTranslation":"This is my house."}
            {"russian":"чай","lemma":"чай","pos":"noun","translation":"tea","tier":0,"unit":5,"cefrLevel":"A1","exampleSentence":"Я пью чай.","exampleTranslation":"I drink tea."}
            """.trimIndent()
        )

        val session = fixture.repository.buildExitTicketSession(5, "A1")!!

        assertEquals(3, session.items.count { it.kind == "dialogue" })
        assertTrue(session.items.filter { it.kind == "dialogue" }.all { it.acceptableAnswers.isNotEmpty() })
        assertTrue(session.items.all { it.choices.size >= 2 })
    }

    @Test
    fun skippingCyrillicGraduatesOnlyFoundationLessons() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """
            {"russian":"А Б В","lemma":"lesson_cyrillic","pos":"lesson","translation":"Cyrillic","tier":0,"unit":0,"cefrLevel":"A1","tags":"lesson literacy a1"}
            {"russian":"мат мать","lemma":"lesson_hard_soft","pos":"lesson","translation":"Hard and soft","tier":0,"unit":0,"cefrLevel":"A1","tags":"lesson phonology a1"}
            {"russian":"Gender","lemma":"lesson_gender","pos":"lesson","translation":"Gender","conceptId":"GENDER","tier":0,"unit":1,"cefrLevel":"A1","tags":"lesson a1"}
            """.trimIndent()
        )

        val skipped = fixture.repository.graduateLiteracyFoundation(now = 123L)

        assertEquals(1, skipped.size)
        val skippedCards = fixture.cards.cards.filter { it.noteId in skipped && it.cardType == CardType.LESSON }
        assertTrue(skippedCards.all { it.state == CardState.GRADUATED && it.lastReview == 123L })
        assertTrue(fixture.cards.cards.any { it.noteId !in skipped && it.cardType == CardType.LESSON && it.state == CardState.NEW })
    }

    @Test
    fun completeExitTicketRecordsResultAndFeedsEvidenceForEveryInvolvedNote() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """
            {"russian":"дом","lemma":"дом","pos":"word","translation":"house","tier":0,"unit":1,"exampleSentence":"Это мой дом.","exampleTranslation":"This is my house."}
            {"russian":"книга","lemma":"книга","pos":"word","translation":"book","tier":0,"unit":1,"exampleSentence":"Я читаю книгу.","exampleTranslation":"I am reading a book."}
            {"russian":"стол","lemma":"стол","pos":"word","translation":"table","tier":0,"unit":1}
            """.trimIndent()
        )
        val session = fixture.repository.buildExitTicketSession(unit = 1)!!

        fixture.repository.completeExitTicket(session, session.items.map { true })

        val results = fixture.repository.exitTicketResults()
        assertEquals(1, results.size)
        assertEquals(1, results.first().unit)
        assertTrue(results.first().recognition)
    }

    @Test
    fun completeExitTicketAggregatesEveryFacetItemInsteadOfOnlyTheFirst() = runTest {
        val fixture = RepoFixture()
        val items = listOf(
            ExitTicketItem("recognition", null, "дом", "house"),
            ExitTicketItem("production", null, "house", "дом"),
            ExitTicketItem("dialogue", null, "turn 1", "ответ 1"),
            ExitTicketItem("dialogue", null, "turn 2", "ответ 2"),
            ExitTicketItem("dialogue", null, "turn 3", "ответ 3"),
            ExitTicketItem("transfer", null, "goal", "response"),
            ExitTicketItem("listening", null, "listen", "meaning"),
            ExitTicketItem("reading", null, "read", "meaning")
        )
        val session = ExitTicketSession(1, "A1", "use the unit", items)

        fixture.repository.completeExitTicket(
            session,
            listOf(true, false, true, false, false, false, false, true)
        )

        val result = fixture.repository.exitTicketResults().single()
        assertTrue(result.recognition)
        assertFalse("one early dialogue success must not hide four later form misses", result.production)
        assertFalse(result.listening)
        assertTrue(result.reading)
    }

    @Test
    fun gradeExitTicketAnswerMatchesRecognitionLeniantlyAndProductionExactly() = runTest {
        val fixture = RepoFixture()
        val recognitionItem = ExitTicketItem(kind = "recognition", noteId = 1, prompt = "дом", expectedAnswer = "house")
        val productionItem = ExitTicketItem(kind = "production", noteId = 1, prompt = "house", expectedAnswer = "дом")

        assertTrue(fixture.repository.gradeExitTicketAnswer(recognitionItem, "House"))
        assertFalse(fixture.repository.gradeExitTicketAnswer(recognitionItem, "table"))
        assertTrue(fixture.repository.gradeExitTicketAnswer(productionItem, "дом"))
        assertFalse(fixture.repository.gradeExitTicketAnswer(productionItem, "книга"))
    }

    @Test
    fun readerRecommendationPrefersPreferredDomainWhenCoverageIsTied() = runTest {
        // Phase G6 domain overlays, scaled down to reader-text selection (see
        // LearningRepository.domainBiasFor) — SettingsStore.preferredDomain
        // should bias which equally-covered text wins, matched against
        // ReaderText.source's real "target:<domain>"/"graded:<domain>" tags.
        val fixture = RepoFixture(config = { LearningConfig(preferredDomain = "business") })
        fixture.repository.importJsonLines(
            """{"russian":"дом","lemma":"дом","pos":"word","translation":"house","tier":0,"unit":1}"""
        )
        fixture.repository.addReaderText("Science text", "дом дом дом", "target:science")
        fixture.repository.addReaderText("Business text", "дом дом дом", "target:business")

        val recommendation = fixture.repository.readerRecommendation()

        assertEquals("Business text", recommendation?.text?.title)
    }

    @Test
    fun readerRecommendationIgnoresPreferredDomainWhenBlank() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(preferredDomain = "") })
        fixture.repository.importJsonLines(
            """{"russian":"дом","lemma":"дом","pos":"word","translation":"house","tier":0,"unit":1}"""
        )
        fixture.repository.addReaderText("First", "дом дом дом", "target:science")
        fixture.repository.addReaderText("Second", "дом дом дом", "target:business")

        // With no preference, the domain bias is a no-op and the existing
        // coverage/dueOverlap ranking alone decides — both are tied, so the
        // first-inserted text wins deterministically (minWithOrNull is stable).
        val recommendation = fixture.repository.readerRecommendation()

        assertEquals("First", recommendation?.text?.title)
    }

    @Test
    fun transformCardUsesRegisterLadderPairAtEffectiveB2AndAbove() = runTest {
        // No tier-0 notes are cefrLevel-tagged in this fixture, so
        // spineMasteryCefrOrdinal() (and thus effectiveCefrOrdinal()) falls
        // through to CEFR_LEVELS.lastIndex (C2) — comfortably >= B2, exercising
        // the gated register-ladder path.
        val pairsJson = """
            {"schemaVersion":1,"pairs":[
                {"id":"formal_say","band":"B2","fromRegister":"neutral","toRegister":"formal","source":"Он сказал, что решение готово.","answer":"Он сообщил о готовности решения."}
            ]}
        """.trimIndent()
        val fixture = RepoFixture(bootstrapTransformations = pairsJson)
        fixture.repository.importJsonLines(
            """{"russian":"говорить","lemma":"говорить","pos":"verb","translation":"to say","tier":0,"unit":1}"""
        )
        val note = fixture.notes.getByLemma("говорить")!!
        val card = fixture.cards.insert(Card(noteId = note.id, cardType = CardType.TRANSFORM, queue = Queue.VOCAB))

        val prompt = fixture.repository.promptForCard(fixture.cards.cards.first { it.id == card })

        assertNotNull(prompt)
        assertTrue("expected the register-ladder source sentence in the prompt", prompt!!.prompt.contains("Он сказал, что решение готово."))
        assertEquals("Он сообщил о готовности решения.", prompt.expectedAnswer)
    }

    @Test
    fun transformCardFallsBackToNegationWhenNoRegisterPairsAreLoaded() = runTest {
        val fixture = RepoFixture(bootstrapTransformations = null)
        fixture.repository.importJsonLines(
            """{"russian":"говорить","lemma":"говорить","pos":"verb","translation":"to say","tier":0,"unit":1,"exampleSentence":"Он говорит правду."}"""
        )
        val note = fixture.notes.getByLemma("говорить")!!
        val card = fixture.cards.insert(Card(noteId = note.id, cardType = CardType.TRANSFORM, queue = Queue.VOCAB))

        val prompt = fixture.repository.promptForCard(fixture.cards.cards.first { it.id == card })

        // No contentDao/morphologyEngine wired either, so transformRealization()
        // also can't fire — this exercises ReviewPrompt.kt's own static fallback,
        // confirming the register-ladder path never crashes or blocks when the
        // asset simply isn't there (the expected state until build_bootstrap.py
        // ships transformations.json as a real asset).
        assertEquals("a transform without a realizable sentence must be suppressed", null, prompt)
    }

    private val phonologyJson = """
        {"schemaVersion":1,"items":[
            {"id":"vowel_y_i","band":"A1","kind":"MINIMAL_PAIR","forms":["быть","бить"],"requiresAudioPack":false},
            {"id":"ik3_yes_no","band":"A2","kind":"INTONATION","forms":["Это дом?"],"requiresAudioPack":true}
        ]}
    """.trimIndent()

    @Test
    fun syncMissingPhonologyCardsMintsOntoTheAssignedFoundationLesson() = runTest {
        val fixture = RepoFixture(bootstrapPhonology = phonologyJson)
        val foundationId = fixture.notes.insert(
            Note(russian = "мат · мать", lemma = "lesson_hard_soft", translation = "Hard and soft", partOfSpeech = "lesson", tier = 0, unit = 0, tags = "lesson phonology a1")
        )

        fixture.repository.seedIfEmpty()

        val minted = fixture.cards.cards.filter { it.noteId == foundationId && it.cardType == CardType.PHONOLOGY_MINIMAL_PAIR }
        assertEquals(1, minted.size)
        assertEquals("быть\u001Fбить", minted.first().gramContextCue)
    }

    @Test
    fun syncMissingPhonologyCardsSkipsAudioPackGatedItems() = runTest {
        val audioOnly = """
            {"schemaVersion":1,"items":[
                {"id":"audio_pair","band":"A1","kind":"MINIMAL_PAIR","forms":["том","дом"],"requiresAudioPack":true}
            ]}
        """.trimIndent()
        val fixture = RepoFixture(bootstrapPhonology = audioOnly)
        fixture.notes.insert(
            Note(russian = "мат · мать", lemma = "lesson_hard_soft", translation = "Hard and soft", partOfSpeech = "lesson", tier = 0, unit = 0, tags = "lesson phonology a1")
        )

        fixture.repository.seedIfEmpty()

        assertFalse(fixture.cards.cards.any { it.cardType == CardType.PHONOLOGY_MINIMAL_PAIR })
    }

    @Test
    fun syncMissingPhonologyCardsDoesNotDependOnVocabularyMaturity() = runTest {
        val fixture = RepoFixture(bootstrapPhonology = phonologyJson)
        fixture.notes.insert(
            Note(russian = "мат · мать", lemma = "lesson_hard_soft", translation = "Hard and soft", partOfSpeech = "lesson", tier = 0, unit = 0, tags = "lesson phonology a1")
        )

        fixture.repository.seedIfEmpty()

        assertTrue(fixture.cards.cards.any { it.cardType == CardType.PHONOLOGY_MINIMAL_PAIR })
    }

    @Test
    fun phonologyMinimalPairPromptPlaysOneSideDeterministicallyAndGradesExactMatch() = runTest {
        val fixture = RepoFixture(bootstrapPhonology = phonologyJson)
        val noteId = fixture.notes.insert(
            Note(russian = "быть", lemma = "быть", translation = "to be", partOfSpeech = "verb", tier = 0, unit = 1)
        )
        val cardId = fixture.cards.insert(
            Card(noteId = noteId, cardType = CardType.PHONOLOGY_MINIMAL_PAIR, queue = Queue.VOCAB,
                gramContextCue = "бить", gramConcept = "PHONOLOGY_vowel_y_i")
        )
        val card = fixture.cards.cards.first { it.id == cardId }

        val prompt = fixture.repository.promptForCard(card)

        assertNotNull(prompt)
        assertEquals(com.sibirskyspeak.review.AnswerMode.CHOICE, prompt!!.answerMode)
        assertEquals(setOf("быть", "бить"), prompt.choices.toSet())
        assertTrue(prompt.expectedAnswer == "быть" || prompt.expectedAnswer == "бить")
        // Same day + card id must always resolve to the same side of the pair.
        val prompt2 = fixture.repository.promptForCard(card)
        assertEquals(prompt.expectedAnswer, prompt2!!.expectedAnswer)
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
    fun unreadableLibraryTextsDoNotCreatePhantomReadingDebt() = runTest {
        val fixture = RepoFixture()
        val pristineId = fixture.repository.addReaderText("Too hard", "unknown words only", "graded:c2")
        val startedId = fixture.repository.addReaderText("Started", "also unknown", "graded:c2")
        fixture.readingSchedules.update(
            fixture.readingSchedules.get(startedId)!!.copy(reps = 1, lastCompleted = 100L, due = 200L)
        )

        fixture.repository.sessionPlan(now = 1_000L)

        assertNull(fixture.readingSchedules.get(pristineId))
        assertNotNull(fixture.readingSchedules.get(startedId))
    }

    @Test
    fun readingAssignmentAlternatesListeningAndReadingModePerRep() = runTest {
        // P5.3: the same ReadingSchedule SRS alternates modality per rep instead of
        // needing a separate schedule/table for listening.
        val fixture = RepoFixture()
        fixture.notes.insert(Note(russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun", status = WordStatus.KNOWN))
        val textId = fixture.repository.addReaderText("A house", "Дом дом.")

        val firstAssignment = fixture.repository.sessionPlan(now = 1_000L).readingAssignment!!
        assertEquals(ReadingMode.READING, firstAssignment.mode)

        fixture.repository.completeScheduledReading(textId, mistakes = 0, now = 1_000L)
        fixture.readingSchedules.update(fixture.readingSchedules.get(textId)!!.copy(due = 1_000L))
        val secondAssignment = fixture.repository.sessionPlan(now = 2_000L).readingAssignment!!
        assertEquals(ReadingMode.LISTENING, secondAssignment.mode)
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
    fun versionedReviewCannotApplyTheSameScheduleTwice() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun"))
        val cardId = fixture.cards.insert(Card(
            noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
            state = CardState.REVIEW, stability = 5.0, difficulty = 5.0, due = 0L, lastReview = 0L
        ))
        val stale = fixture.cards.cards.first { it.id == cardId }

        fixture.repository.review(stale, Rating.GOOD, now = 86_400_000L, rejectIfAlreadyReviewed = true)
        var rejected = false
        try {
            fixture.repository.review(stale, Rating.GOOD, now = 86_400_001L, rejectIfAlreadyReviewed = true)
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue("a delayed second tap must be rejected", rejected)
        assertEquals(1, fixture.logs.logs.count { it.cardId == cardId })
        assertEquals(1, fixture.cards.cards.first { it.id == cardId }.reps)
    }

    @Test
    fun inlineReplySkipsACardThatIsNotDueOrWasAlreadyReviewed() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun"))
        val cardId = fixture.cards.insert(Card(
            noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
            state = CardState.REVIEW, stability = 5.0, difficulty = 5.0, due = 1_000L, lastReview = 0L
        ))

        assertNull(fixture.repository.gradeInlineEnglish(cardId, "house", now = 999L))
        assertEquals(true, fixture.repository.gradeInlineEnglish(cardId, "house", now = 1_000L))
        assertNull(fixture.repository.gradeInlineEnglish(cardId, "house", now = 1_001L))
        assertEquals(1, fixture.logs.logs.count { it.cardId == cardId })
    }

    @Test
    fun weakEvidenceNeverPersistsDifficultyOutsideFsrsDomain() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "скажи", lemma = "сказать", translation = "say", partOfSpeech = "verb"))
        val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.SPEAK, queue = Queue.VOCAB))
        val fresh = fixture.cards.cards.first { it.id == cardId }

        fixture.repository.review(fresh, Rating.GOOD, now = 1_000L, objectiveCorrect = true)

        val updated = fixture.cards.cards.first { it.id == cardId }
        assertTrue("difficulty must remain a valid FSRS value", updated.difficulty in 1.0..10.0)
        assertTrue(updated.stability.isFinite() && updated.stability > 0.0)
    }

    @Test
    fun releasingLeechUsesLiveRowAndClearsEveryScheduleField() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "слово", lemma = "слово", translation = "word", partOfSpeech = "noun"))
        val cardId = fixture.cards.insert(Card(
            noteId = noteId, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR,
            state = CardState.RELEARNING, suspended = true, lapses = 9, reps = 20,
            stability = 8.0, difficulty = 7.0, elapsedDays = 12, scheduledDays = 9,
            due = 123L, lastReview = 100L, gramContextCue = "STALE"
        ))
        val stale = fixture.cards.cards.first { it.id == cardId }
        fixture.cards.update(stale.copy(gramContextCue = "LIVE", reps = 21, scheduledDays = 11))

        fixture.repository.releaseLeech(stale, now = 5_000L)

        val released = fixture.cards.cards.first { it.id == cardId }
        assertEquals("LIVE", released.gramContextCue)
        assertEquals(CardState.NEW, released.state)
        assertFalse(released.suspended)
        assertEquals(0, released.reps)
        assertEquals(0, released.lapses)
        assertEquals(0, released.elapsedDays)
        assertEquals(0, released.scheduledDays)
        assertNull(released.lastReview)
    }

    @Test
    fun historicalActivityUsesOffsetAtEachInstantAcrossDst() = runTest {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        try {
            val fixture = RepoFixture(config = { LearningConfig(dailyGoal = 3) })
            val noteId = fixture.notes.insert(Note(russian = "word", lemma = "word", translation = "word", partOfSpeech = "noun"))
            val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB))
            fun log(at: String) = ReviewLog(
                cardId = cardId, reviewDatetime = Instant.parse(at).toEpochMilli(), rating = Rating.GOOD,
                stateBefore = CardState.REVIEW, scheduledDays = 1, elapsedDays = 1, source = ReviewSource.SRS_REVIEW
            )
            // 23:30 on Mar 8 (EST), then 23:30 on Mar 9 (EDT).
            fixture.logs.insert(log("2025-03-09T04:30:00Z"))
            fixture.logs.insert(log("2025-03-10T03:30:00Z"))

            val stats = fixture.repository.gamificationStats(Instant.parse("2025-03-10T16:00:00Z").toEpochMilli())
            assertEquals(2, stats.currentStreak)
            assertEquals(2, stats.activeDays)
            assertFalse(stats.achievements.first { it.id == "goal_met" }.unlocked)

            fixture.logs.insert(log("2025-03-10T03:31:00Z"))
            fixture.logs.insert(log("2025-03-10T03:32:00Z"))
            assertTrue(fixture.repository.gamificationStats(Instant.parse("2025-03-10T16:00:00Z").toEpochMilli())
                .achievements.first { it.id == "goal_met" }.unlocked)
        } finally {
            TimeZone.setDefault(previous)
        }
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
    fun placeAfterLevelMaturesOnlyRecognitionWithCoherentFsrsState() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"вода","lemma":"вода","pos":"noun","translation":"water","cefrLevel":"A1"}"""
        )

        val placed = fixture.repository.placeAfterLevel("A1")

        assertTrue("at least one note placed", placed >= 1)
        val cards = fixture.cards.cards.filter { it.queue == Queue.VOCAB }
        assertTrue(cards.isNotEmpty())
        val recognition = cards.first { it.cardType == CardType.RU_TO_MEANING }
        assertEquals(CardState.GRADUATED, recognition.state)
        assertTrue(recognition.stability > 0.0 && recognition.difficulty > 0.0)
        assertTrue(cards.filterNot { it.cardType == CardType.RU_TO_MEANING }.all { it.state == CardState.NEW })
    }

    @Test
    fun passiveReadingEvidenceCannotChangeProductionSchedule() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun", status = WordStatus.LEARNING))
        val recognitionId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, stability = 10.0, difficulty = 5.0, scheduledDays = 5, due = 100L, lastReview = 1L))
        val productionId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.MEANING_TO_RU, queue = Queue.VOCAB, state = CardState.REVIEW, stability = 10.0, difficulty = 5.0, scheduledDays = 5, due = 100L, lastReview = 1L))

        fixture.repository.recordEvidence(EvidenceEvent(noteId = noteId, facet = LearningFacet.CONTEXT, strength = EvidenceStrength.PRACTICE, correct = true, source = ReviewSource.READING, at = 86_400_000L))

        assertTrue(fixture.cards.cards.first { it.id == recognitionId }.stability > 10.0)
        assertEquals(10.0, fixture.cards.cards.first { it.id == productionId }.stability, 0.0)
    }

    @Test
    fun passiveEvidenceCapUsesTheLearnersLocalDayAcrossUtcMidnight() = runTest {
        val original = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT-03:00"))
        try {
            val fixture = RepoFixture()
            val noteId = fixture.notes.insert(Note(russian = "word", lemma = "word", translation = "word", partOfSpeech = "noun"))
            val cardId = fixture.cards.insert(Card(
                noteId = noteId,
                cardType = CardType.RU_TO_MEANING,
                queue = Queue.VOCAB,
                state = CardState.REVIEW,
                stability = 10.0,
                difficulty = 5.0,
                due = 0L,
                lastReview = 1L
            ))
            val first = 86_400_000L + 30 * 60_000L
            val second = 86_400_000L + 2 * 3_600_000L

            assertEquals(1, fixture.repository.recordEvidence(EvidenceEvent(
                noteId = noteId, facet = LearningFacet.CONTEXT, strength = EvidenceStrength.PRACTICE,
                correct = true, source = ReviewSource.READING, at = first
            )))
            assertEquals(0, fixture.repository.recordEvidence(EvidenceEvent(
                noteId = noteId, facet = LearningFacet.CONTEXT, strength = EvidenceStrength.PRACTICE,
                correct = true, source = ReviewSource.READING, at = second
            )))
            assertEquals(1, fixture.logs.logs.count { it.cardId == cardId && it.source == ReviewSource.READING })
        } finally {
            java.util.TimeZone.setDefault(original)
        }
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
    fun explicitRetentionWindowDoesNotSubtractASecondWindow() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(
            Note(
                russian = "стол",
                lemma = "стол",
                translation = "table",
                partOfSpeech = "noun",
                status = WordStatus.LEARNING,
                tags = ""
            )
        )
        val card = Card(
            id = fixture.cards.insert(
                Card(
                    noteId = noteId,
                    cardType = CardType.RU_TO_MEANING,
                    queue = Queue.VOCAB,
                    state = CardState.REVIEW
                )
            ),
            noteId = noteId,
            cardType = CardType.RU_TO_MEANING,
            queue = Queue.VOCAB,
            state = CardState.REVIEW
        )
        fixture.logs.insert(matureLog(card, Rating.AGAIN).copy(reviewDatetime = 2L * 86_400_000L))
        fixture.logs.insert(matureLog(card, Rating.GOOD).copy(reviewDatetime = 9L * 86_400_000L))

        val rows = fixture.repository
            .retentionByCardTypeSince(since = 7L * 86_400_000L)
            .associateBy { it.cardType }

        assertEquals(1, rows[CardType.RU_TO_MEANING]?.total)
        assertEquals(1, rows[CardType.RU_TO_MEANING]?.retained)
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

    @Test
    fun topConfusionPairOnlyReportsAPairThatRecurredEnough() = runTest {
        val fixture = RepoFixture()
        val diagnosis = com.sibirskyspeak.review.Diagnosis("GEN_SG", "DAT_SG")
        repeat(3) { fixture.repository.recordConfusionEvent(diagnosis, CardType.CASE_FILL, now = 1000L) }

        assertNull(fixture.repository.topConfusionPair(now = 2000L))

        fixture.repository.recordConfusionEvent(diagnosis, CardType.CASE_FILL, now = 1500L)
        val top = fixture.repository.topConfusionPair(now = 2000L)

        assertEquals("GEN_SG", top?.expectedKey)
        assertEquals("DAT_SG", top?.producedKey)
        assertEquals(CardType.CASE_FILL, top?.cardType)
        assertEquals(4, top?.count)
    }

    @Test
    fun topConfusionPairIgnoresEventsOutsideTheWindow() = runTest {
        val fixture = RepoFixture()
        val diagnosis = com.sibirskyspeak.review.Diagnosis("GEN_SG", "DAT_SG")
        val fourteenDaysMs = 14L * 86_400_000L
        repeat(4) { fixture.repository.recordConfusionEvent(diagnosis, CardType.CASE_FILL, now = 0L) }

        assertNull(fixture.repository.topConfusionPair(now = fourteenDaysMs + 86_400_000L))
    }

    @Test
    fun contrastivePairingPlacesTheConfusedCardImmediatelyAfterItsPair() = runTest {
        val fixture = RepoFixture(config = { LearningConfig(newCardsPerDay = 10, sessionSize = 10) })
        val genNoteId = fixture.notes.insert(Note(russian = "стол", lemma = "стол", translation = "table", partOfSpeech = "noun", tier = 0, gender = "M"))
        val genCard = fixture.cards.insert(Card(noteId = genNoteId, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramCase = "GEN", gramNumber = "SG", state = CardState.REVIEW, reps = 2, due = 0L))
        // The confused sibling: a different note's DAT_SG card, likely ordered far
        // away from genCard by ordinary due-date/curriculum ordering.
        val datNoteId = fixture.notes.insert(Note(russian = "окно", lemma = "окно", translation = "window", partOfSpeech = "noun", tier = 0, gender = "N"))
        val datCard = fixture.cards.insert(Card(noteId = datNoteId, cardType = CardType.CASE_FILL, queue = Queue.GRAMMAR, gramCase = "DAT", gramNumber = "SG", state = CardState.REVIEW, reps = 2, due = 0L))
        val diagnosis = com.sibirskyspeak.review.Diagnosis("GEN_SG", "DAT_SG")
        repeat(4) { fixture.repository.recordConfusionEvent(diagnosis, CardType.CASE_FILL, now = 0L) }

        val ids = fixture.repository.sessionPlan(now = 1000L).reviewQueue.map { it.card.id }
        val genIndex = ids.indexOf(genCard)
        val datIndex = ids.indexOf(datCard)

        assertTrue("both confused cards must appear in the plan", genIndex >= 0 && datIndex >= 0)
        assertEquals("the produced (DAT_SG) card must sit immediately after the expected (GEN_SG) card", genIndex + 1, datIndex)
    }

    @Test
    fun completingScheduledReadingCreditsUnlookedDueSoonWordsPositively() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "окно", lemma = "окно", translation = "window", partOfSpeech = "noun", tier = 0))
        val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, stability = 10.0, due = 3 * 86_400_000L))
        val textId = fixture.readers.insert(ReaderText(title = "t", body = "Окно было открыто."))

        fixture.repository.completeScheduledReading(textId, mistakes = 0, now = 0L)

        val updated = fixture.cards.cards.first { it.id == cardId }
        assertEquals(10.0 * (1.0 + 0.15 * 0.45 * 0.18), updated.stability, 1e-9)
    }

    @Test
    fun completingScheduledReadingGivesWeakNegativeForLookedUpWords() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "окно", lemma = "окно", translation = "window", partOfSpeech = "noun", tier = 0))
        val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, stability = 10.0, due = 3 * 86_400_000L))
        val textId = fixture.readers.insert(ReaderText(title = "t", body = "Окно было открыто."))
        fixture.readerEncounters.insert(ReaderEncounter(textId, noteId, 0L))

        fixture.repository.completeScheduledReading(textId, mistakes = 0, now = 0L)

        val updated = fixture.cards.cards.first { it.id == cardId }
        assertEquals(10.0 * (1.0 - 0.10 * 0.45 * 0.18), updated.stability, 1e-9)
    }

    @Test
    fun completingScheduledReadingNeverTouchesNewOrGraduatedCardsOrCardsNotDueSoon() = runTest {
        val fixture = RepoFixture()
        val newNoteId = fixture.notes.insert(Note(russian = "окно", lemma = "окно", translation = "window", partOfSpeech = "noun", tier = 0))
        val newCardId = fixture.cards.insert(Card(noteId = newNoteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.NEW, stability = 0.0, due = 3 * 86_400_000L))
        val farNoteId = fixture.notes.insert(Note(russian = "дверь", lemma = "дверь", translation = "door", partOfSpeech = "noun", tier = 0))
        val farCardId = fixture.cards.insert(Card(noteId = farNoteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, stability = 10.0, due = 30L * 86_400_000L))
        val textId = fixture.readers.insert(ReaderText(title = "t", body = "Окно и дверь были открыты."))

        fixture.repository.completeScheduledReading(textId, mistakes = 0, now = 0L)

        assertEquals(0.0, fixture.cards.cards.first { it.id == newCardId }.stability, 1e-9)
        assertEquals(CardState.NEW, fixture.cards.cards.first { it.id == newCardId }.state)
        assertEquals(10.0, fixture.cards.cards.first { it.id == farCardId }.stability, 1e-9)
    }

    @Test
    fun completingScheduledReadingDoesNotCreditAnAbandonedSession() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "окно", lemma = "окно", translation = "window", partOfSpeech = "noun", tier = 0))
        val cardId = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, stability = 10.0, due = 3 * 86_400_000L))
        val textId = fixture.readers.insert(ReaderText(title = "t", body = "Окно было открыто."))

        fixture.repository.completeScheduledReading(textId, mistakes = 0, abandoned = true, now = 0L)

        assertEquals(10.0, fixture.cards.cards.first { it.id == cardId }.stability, 1e-9)
    }

    @Test
    fun checkpointSessionSamplesGraduatedNotesWithPredictedRetrievabilityAndWritesNoFsrsState() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "стол", lemma = "стол", translation = "table", partOfSpeech = "noun", tier = 0))
        val cardId = fixture.cards.insert(
            Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.GRADUATED, stability = 30.0, lastReview = 0L)
        )

        val session = fixture.repository.buildCheckpointSession(now = 10L * 86_400_000L, graduatedSampleSize = 20, novelFrameSampleSize = 0)

        val item = session.items.single { it.itemKey == "note:$noteId" }
        assertEquals("graduated_recall", item.kind)
        assertEquals("table", item.expectedAnswer)
        assertNotNull(item.predictedP)
        assertTrue("predicted retrievability should be in (0,1)", item.predictedP!! in 0.0..1.0)

        fixture.repository.recordCheckpointResult(item, correct = false, now = 10L * 86_400_000L)

        // Answering a checkpoint item must never touch the card it assessed.
        val untouched = fixture.cards.cards.first { it.id == cardId }
        assertEquals(30.0, untouched.stability, 1e-9)
        assertEquals(CardState.GRADUATED, untouched.state)
        assertEquals(1, fixture.checkpointResults.results.size)
    }

    @Test
    fun checkpointCalibrationBucketsPredictedVsObservedAccuracy() = runTest {
        val fixture = RepoFixture()
        val highConfidenceItem = CheckpointItem("note:1", "graduated_recall", "стол", "table", predictedP = 0.95)
        val lowConfidenceItem = CheckpointItem("note:2", "graduated_recall", "окно", "window", predictedP = 0.15)
        fixture.repository.recordCheckpointResult(highConfidenceItem, correct = true, now = 0L)
        fixture.repository.recordCheckpointResult(highConfidenceItem, correct = true, now = 0L)
        fixture.repository.recordCheckpointResult(lowConfidenceItem, correct = false, now = 0L)

        val buckets = fixture.repository.checkpointCalibration(now = 0L)

        val high = buckets.first { it.predictedBucket == 0.9 }
        val low = buckets.first { it.predictedBucket == 0.1 }
        assertEquals(1.0, high.observedAccuracy, 1e-9)
        assertEquals(2, high.count)
        assertEquals(0.0, low.observedAccuracy, 1e-9)
        assertEquals(1, low.count)
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

    /**
     * P0.3 "no starvation" coverage, done deterministically rather than by hoping a
     * multi-hundred-day organic simulation happens to reach every type: each of the
     * card types with non-trivial minting/selection preconditions (grammar concept
     * gating, recognition-maturity re-checks, parent-chunk maturity) gets its own
     * minimal precondition-satisfying fixture, mirroring the individual minting
     * tests above. If any of these regress to ungated-but-unselectable, this fails
     * fast instead of only showing up as silence in a long-running simulation.
     */
    @Test
    fun everyNewerCardTypeSurfacesOnceItsPreconditionsAreMet() = runTest {
        val fixture = RepoFixture(
            contentDao = FakeContentDao(mapOf("GEN" to listOf(testFrame("gen_negation_net", "GEN"))))
        )

        // CONCEPT_APPLY / NOVEL_PRODUCE: concept introduced (LESSON graduated) with a
        // shipped frame; CONCEPT_APPLY already has reps so NOVEL_PRODUCE is unlocked.
        val lessonId = fixture.notes.insert(
            Note(russian = "Genitive", lemma = "lesson_gen", translation = "Genitive", partOfSpeech = "lesson", conceptId = "GEN", tier = 0, unit = 1, encounterCount = 1)
        )
        fixture.notes.insert(Note(russian = "дома", lemma = "дом_gen_cov", translation = "of the house", partOfSpeech = "noun", tier = 0, unit = 1, encounterCount = 1))
        fixture.cards.insert(Card(noteId = lessonId, cardType = CardType.LESSON, queue = Queue.GRAMMAR, gramConcept = "GEN", state = CardState.GRADUATED, reps = 1))
        // state=NEW (default): CONCEPT_APPLY is inserted directly here rather than via
        // syncMissingNovelProduceCards' auto-mint path, so it doesn't need reps>=2 —
        // that precondition only matters for NOVEL_PRODUCE's own *minting* trigger,
        // which this test bypasses by inserting NOVEL_PRODUCE directly too.
        fixture.cards.insert(Card(noteId = lessonId, cardType = CardType.CONCEPT_APPLY, queue = Queue.GRAMMAR, gramConcept = "GEN"))
        fixture.cards.insert(Card(noteId = lessonId, cardType = CardType.NOVEL_PRODUCE, queue = Queue.GRAMMAR, gramConcept = "GEN"))

        // CONCEPT_DRILL: an upper-level concept ConceptDrills actually covers, LESSON graduated.
        val conditionalId = fixture.notes.insert(
            Note(russian = "Conditional", lemma = "lesson_conditional", translation = "Conditional", partOfSpeech = "lesson", conceptId = GrammarConcepts.CONDITIONAL.id, tier = 0, unit = 2, encounterCount = 1)
        )
        fixture.notes.insert(Note(russian = "если", lemma = "если_cond_cov", translation = "if", partOfSpeech = "conjunction", tier = 0, unit = 2, encounterCount = 1))
        fixture.cards.insert(Card(noteId = conditionalId, cardType = CardType.LESSON, queue = Queue.GRAMMAR, gramConcept = GrammarConcepts.CONDITIONAL.id, state = CardState.GRADUATED, reps = 1))
        fixture.repository.seedIfEmpty()

        // Concept-probation ("only one unproven drill per concept surfaces at a
        // time") would otherwise block one of {CONCEPT_APPLY, NOVEL_PRODUCE} and
        // the auto-minted CONCEPT_DRILL card here — that gate is intentional and
        // has its own dedicated tests; record a prior success per concept so this
        // test isolates "does the card type's own pipeline work" from probation.
        fixture.logs.insert(goodLog(fixture.cards.cards.first { it.cardType == CardType.CONCEPT_APPLY }, time = 1L))
        fixture.cards.cards.firstOrNull { it.noteId == conditionalId && it.cardType == CardType.CONCEPT_DRILL }?.let {
            fixture.logs.insert(goodLog(it, time = 1L))
        }

        // VERB_FORM: any grammar-queue drill just needs its own note's first encounter.
        val verbId = fixture.notes.insert(Note(russian = "делать", lemma = "делать", translation = "to do", partOfSpeech = "verb", tier = 0, unit = 1, encounterCount = 1))
        fixture.cards.insert(Card(noteId = verbId, cardType = CardType.VERB_FORM, queue = Queue.GRAMMAR, gramContextCue = "PRES_3SG"))

        // ASPECT_SELECT: aspect-paired verbs via the real import path, then first encounter.
        fixture.repository.importJsonLines(
            """
            {"russian":"писа́ть","lemma":"писать","pos":"verb","translation":"to write","aspect":"IPF","aspectPartner":"написать","aktionsart":"activity","aktionsartConfidence":"high"}
            {"russian":"написа́ть","lemma":"написать","pos":"verb","translation":"to write completely","aspect":"PF","aspectPartner":"писать","aktionsart":"accomplishment","aktionsartConfidence":"high"}
            """.trimIndent()
        )
        listOfNotNull(fixture.notes.getByLemma("писать"), fixture.notes.getByLemma("написать")).forEach {
            fixture.notes.update(it.copy(encounterCount = 1))
        }

        // TRANSFORM / SPEAK_SENTENCE: re-checked at selection time, so both need their
        // own mature RU_TO_MEANING recognition, not just a first encounter.
        val transformVerbId = fixture.notes.insert(Note(russian = "читать", lemma = "читать_cov", translation = "to read", partOfSpeech = "verb", tier = 0, unit = 1))
        fixture.cards.insert(Card(noteId = transformVerbId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 3, consecutiveCorrect = 2))
        fixture.cards.insert(Card(noteId = transformVerbId, cardType = CardType.TRANSFORM, queue = Queue.VOCAB))

        val speakNoteId = fixture.notes.insert(Note(russian = "стол", lemma = "стол_cov", translation = "table", partOfSpeech = "noun", tier = 0, unit = 1))
        fixture.cards.insert(Card(noteId = speakNoteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 3, consecutiveCorrect = 2))
        fixture.cards.insert(Card(noteId = speakNoteId, cardType = CardType.SPEAK_SENTENCE, queue = Queue.VOCAB))

        // CHUNK: maturity is judged on the *parent* word, not the chunk note itself.
        val chunkParentId = fixture.notes.insert(Note(russian = "диван", lemma = "диван_cov", translation = "sofa", partOfSpeech = "noun", tier = 0, unit = 1))
        fixture.cards.insert(Card(noteId = chunkParentId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, state = CardState.REVIEW, reps = 3, consecutiveCorrect = 2))
        val chunkNoteId = fixture.notes.insert(Note(russian = "на диване", lemma = "на диване", translation = "", partOfSpeech = "chunk", tier = 0, chunkParentNoteId = chunkParentId))
        fixture.cards.insert(Card(noteId = chunkNoteId, cardType = CardType.CHUNK, queue = Queue.VOCAB))

        val plan = fixture.repository.sessionPlan(now = 0L, includeReaderInsights = false)
        val seen = plan.reviewQueue.map { it.card.cardType }.toSet()

        val expected = setOf(
            CardType.CONCEPT_DRILL,
            CardType.VERB_FORM, CardType.ASPECT_SELECT
        )
        val missing = expected - seen
        assertTrue("card types gated/unselectable despite satisfied preconditions: $missing", missing.isEmpty())
    }

    /**
     * P6.5: direct, fast regression proof for unitMastery()'s sliding-window unlock
     * (replaces a slow multi-day-simulation attempt at the same proof — see
     * SimHarnessTest's comment on why organic-growth throughput makes that
     * comparison unreliable). Unit 1 is "started" (one note mastered) but
     * incomplete, so the frontier sits at unit 1; units 2 and 3 (within
     * UNIT_SLIDING_WINDOW=2) must open too, while unit 4 stays locked — proving
     * the old "every prior unit 100% first" chain was actually replaced, not just
     * relabeled.
     */
    @Test
    fun slidingWindowUnlocksUnitsAheadOfAnIncompleteFrontier() = runTest {
        val fixture = RepoFixture()
        suspend fun vocabNote(unit: Int, mastered: Boolean): Long {
            val noteId = fixture.notes.insert(
                Note(russian = "word$unit${if (mastered) "a" else "b"}", lemma = "word$unit${if (mastered) "a" else "b"}", translation = "word", partOfSpeech = "noun", tier = 0, unit = unit)
            )
            fixture.cards.insert(
                Card(
                    noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB,
                    state = if (mastered) CardState.GRADUATED else CardState.NEW,
                    reps = if (mastered) 3 else 0, consecutiveCorrect = if (mastered) 3 else 0
                )
            )
            return noteId
        }
        vocabNote(unit = 1, mastered = true)
        vocabNote(unit = 1, mastered = false)
        vocabNote(unit = 2, mastered = false)
        vocabNote(unit = 3, mastered = false)
        vocabNote(unit = 4, mastered = false)

        val plan = fixture.repository.sessionPlan(now = 0L, includeReaderInsights = false)
        val byUnit = plan.unitMastery.associateBy { it.unit }

        assertTrue("frontier unit (started but incomplete) must stay unlocked", byUnit.getValue(1).unlocked)
        assertTrue("one unit ahead of an incomplete-but-started frontier must open", byUnit.getValue(2).unlocked)
        assertTrue("two units ahead (the sliding window) must open", byUnit.getValue(3).unlocked)
        assertFalse("three units ahead is past the sliding window and must stay locked", byUnit.getValue(4).unlocked)
    }

    // --- P2.5 streak insurance -------------------------------------------------

    /** Builds review-day buckets using the exact same (now + tzOffset) / DAY_MILLIS
     * arithmetic gamificationStats itself uses, so the test's notion of "which
     * epoch-day a timestamp falls in" can never drift from the production code's
     * regardless of the machine's local timezone. */
    private fun dayBucketTimestamp(now: Long, bucket: Long): Long {
        val dayMillis = 86_400_000L
        val tzOffset = java.util.TimeZone.getDefault().getOffset(now)
        return bucket * dayMillis - tzOffset + dayMillis / 2
    }

    @Test
    fun streakInsuranceBridgesAOneDayGapAndReportsWhichDayItInsured() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.notes.insert(Note(russian = "тест", lemma = "тест", translation = "test", partOfSpeech = "noun"))
        val card = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB)).let { id ->
            fixture.cards.cards.first { it.id == id }
        }
        val fixedNow = 2000L * 86_400_000L + 12 * 3_600_000L
        val tzOffset = java.util.TimeZone.getDefault().getOffset(fixedNow)
        val todayBucket = (fixedNow + tzOffset) / 86_400_000L
        // Active today and two days ago; yesterday has no activity at all — a
        // genuine one-day gap for insurance to bridge.
        fixture.logs.insert(goodLog(card, dayBucketTimestamp(fixedNow, todayBucket)))
        fixture.logs.insert(goodLog(card, dayBucketTimestamp(fixedNow, todayBucket - 2)))

        val withoutCredits = fixture.repository.gamificationStats(fixedNow)
        assertEquals("no credits available: the gap must break the streak", 1, withoutCredits.currentStreak)
        assertEquals(null, withoutCredits.insuredGapDay)

        val insuredFixture = RepoFixture(config = { LearningConfig(restDayCredits = 1) })
        val insuredNoteId = insuredFixture.notes.insert(Note(russian = "тест", lemma = "тест", translation = "test", partOfSpeech = "noun"))
        val insuredCard = insuredFixture.cards.insert(Card(noteId = insuredNoteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB)).let { id ->
            insuredFixture.cards.cards.first { it.id == id }
        }
        insuredFixture.logs.insert(goodLog(insuredCard, dayBucketTimestamp(fixedNow, todayBucket)))
        insuredFixture.logs.insert(goodLog(insuredCard, dayBucketTimestamp(fixedNow, todayBucket - 2)))

        val withCredits = insuredFixture.repository.gamificationStats(fixedNow)
        assertEquals("a spent credit must bridge the gap into one continuous streak", 3, withCredits.currentStreak)
        assertEquals(todayBucket - 1, withCredits.insuredGapDay)
    }

    @Test
    fun streakInsuranceConsumptionIsIdempotentPerGapDay() = runTest {
        // Mirrors ReviewViewModel.loadSession's guard: recomputing gamificationStats
        // for the SAME already-insured gap (e.g. reopening the app later the same
        // day) must report the same insuredGapDay, not a fresh one each time —
        // the caller (which owns settings) is what makes the actual spend
        // idempotent by comparing against lastInsuredGapDay, but that only works
        // if the repository consistently reports the identical day here.
        val fixture = RepoFixture(config = { LearningConfig(restDayCredits = 1) })
        val noteId = fixture.notes.insert(Note(russian = "тест", lemma = "тест", translation = "test", partOfSpeech = "noun"))
        val card = fixture.cards.insert(Card(noteId = noteId, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB)).let { id ->
            fixture.cards.cards.first { it.id == id }
        }
        val fixedNow = 2000L * 86_400_000L + 12 * 3_600_000L
        val tzOffset = java.util.TimeZone.getDefault().getOffset(fixedNow)
        val todayBucket = (fixedNow + tzOffset) / 86_400_000L
        fixture.logs.insert(goodLog(card, dayBucketTimestamp(fixedNow, todayBucket)))
        fixture.logs.insert(goodLog(card, dayBucketTimestamp(fixedNow, todayBucket - 2)))

        val first = fixture.repository.gamificationStats(fixedNow)
        val second = fixture.repository.gamificationStats(fixedNow)
        assertEquals(first.insuredGapDay, second.insuredGapDay)
        assertEquals(todayBucket - 1, first.insuredGapDay)
    }

    // --- P2.4 goal coverage -----------------------------------------------------

    @Test
    fun dashboardReportsGoalProgressForAReaderTextMarkedAsATarget() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"иду","lemma":"иду-goal","pos":"verb","translation":"I go","tier":0,"unit":1}"""
        )
        val known = fixture.notes.getByLemma("иду-goal")!!
        fixture.notes.update(known.copy(status = WordStatus.KNOWN))
        val textId = fixture.repository.addReaderText("Diplomatic briefing", "иду вижу", "local")

        assertEquals(null, fixture.repository.dashboardStats().goalProgress)

        assertTrue(fixture.repository.setReaderGoal(textId))
        val goal = fixture.repository.dashboardStats().goalProgress

        assertNotNull(goal)
        assertEquals("Diplomatic briefing", goal!!.textTitle)
        assertEquals(textId, goal.textId)
        // One of two tokens ("иду") is known: 50% coverage, one lemma still unknown.
        assertEquals(50, goal.coveragePct)
        assertEquals(1, goal.unknownLemmaCount)
    }

    // --- Learning goal (target CEFR level + date) coverage ----------------------

    @Test
    fun evaluateGoalFeasibilityReturnsNullForAnUnrecognizedCefrLevel() = runTest {
        val fixture = RepoFixture()
        assertNull(fixture.repository.evaluateGoalFeasibility("Z9", targetDateEpochDay = 1000, currentStablePace = 10.0))
    }

    @Test
    fun evaluateGoalFeasibilityIsPureArithmeticAgainstTheSuppliedPace() = runTest {
        // Regression guard: this must stay callable on every Settings slider tick,
        // so its result depends only on already-cached known-note counts and the
        // caller-supplied stablePace — never a fresh FluencySimEngine simulation.
        val fixture = RepoFixture()
        val feasibility = fixture.repository.evaluateGoalFeasibility(
            "B2", targetDateEpochDay = Long.MAX_VALUE / 2, currentStablePace = 5.0
        )
        assertNotNull(feasibility)
        assertEquals(com.sibirskyspeak.learning.GoalVerdict.COMFORTABLE, feasibility!!.verdict)
    }

    @Test
    fun setLearningGoalPersistsSettingsAndFiresGoalCreatedThenGoalReplannedTelemetry() = runTest {
        val settings = com.sibirskyspeak.review.FakeSettingsStore()
        val fixture = RepoFixture(withTelemetry = true, settingsStore = settings)
        val today = java.time.LocalDate.now().toEpochDay()

        fixture.repository.setLearningGoal("C1", targetDateEpochDay = today + 100)
        assertEquals("C1", settings.goalTargetLevel)
        assertEquals(today + 100, settings.goalTargetDateEpochDay)
        assertEquals("ACTIVE", settings.goalStatus)
        assertEquals("goal_created", fixture.telemetry!!.events.single().eventType)

        fixture.repository.setLearningGoal("B2", targetDateEpochDay = today + 120)
        assertEquals("B2", settings.goalTargetLevel)
        assertEquals("goal_replanned", fixture.telemetry.events.last().eventType)
    }

    @Test
    fun setLearningGoalRejectsUnknownLevelAndPastDate() = runTest {
        val settings = com.sibirskyspeak.review.FakeSettingsStore()
        val fixture = RepoFixture(settingsStore = settings)
        val today = java.time.LocalDate.now().toEpochDay()

        var rejected = false
        try {
            fixture.repository.setLearningGoal("Z9", today + 30)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals("", settings.goalTargetLevel)

        rejected = false
        try {
            fixture.repository.setLearningGoal("A1", today - 1)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals("", settings.goalTargetLevel)
    }

    @Test
    fun abandonLearningGoalClearsSettingsAndFiresTelemetry() = runTest {
        val settings = com.sibirskyspeak.review.FakeSettingsStore()
        val fixture = RepoFixture(withTelemetry = true, settingsStore = settings)
        fixture.repository.setLearningGoal("B2", targetDateEpochDay = System.currentTimeMillis() / (24L * 60 * 60 * 1000) + 100)

        fixture.repository.abandonLearningGoal()

        assertEquals("", settings.goalTargetLevel)
        assertEquals(Long.MIN_VALUE, settings.goalTargetDateEpochDay)
        assertEquals("ABANDONED", settings.goalStatus)
        assertEquals("goal_abandoned", fixture.telemetry!!.events.last().eventType)
    }

    @Test
    fun currentGoalStatusIsNullWithNoActiveGoal() = runTest {
        val settings = com.sibirskyspeak.review.FakeSettingsStore()
        val fixture = RepoFixture(settingsStore = settings)
        val forecast = FluencySimEngine.SimResult(null, null, null, null, null, null, stablePace = 10.0, finalReviewLoad = 0)

        assertNull(fixture.repository.currentGoalStatus(forecast))
    }

    @Test
    fun currentGoalStatusStaysActiveBelowTheMilestoneAndFlipsToAchievedAtOrAboveIt() = runTest {
        val settings = com.sibirskyspeak.review.FakeSettingsStore()
        val fixture = RepoFixture(withTelemetry = true, settingsStore = settings)
        fixture.repository.setLearningGoal("A1", targetDateEpochDay = System.currentTimeMillis() / (24L * 60 * 60 * 1000) + 100)
        val forecast = FluencySimEngine.SimResult(null, null, null, null, null, null, stablePace = 3.0, finalReviewLoad = 0)

        // No notes imported yet: well below A1's 700-word milestone.
        val beforeMilestone = fixture.repository.currentGoalStatus(forecast)
        assertNotNull(beforeMilestone)
        assertEquals("ACTIVE", settings.goalStatus)

        // Import 700 KNOWN notes so totalKnown reaches the A1 milestone exactly.
        val bootstrap = (1..700).joinToString("\n") { index ->
            "{\"russian\":\"слово$index\",\"lemma\":\"словоA1-$index\",\"pos\":\"noun\",\"translation\":\"word $index\",\"tier\":0,\"unit\":1,\"status\":\"KNOWN\"}"
        }
        fixture.repository.importJsonLines(bootstrap)

        val achieved = fixture.repository.currentGoalStatus(forecast)
        assertNotNull(achieved)
        assertEquals(com.sibirskyspeak.learning.GoalTrackState.ON_TRACK, achieved!!.state)
        assertEquals("ACHIEVED", settings.goalStatus)
        assertEquals("goal_achieved", fixture.telemetry!!.events.last().eventType)

        // Idempotent: calling again must not re-fire the achieved telemetry.
        val eventCountAfterFirstAchievement = fixture.telemetry.events.size
        fixture.repository.currentGoalStatus(forecast)
        assertEquals(eventCountAfterFirstAchievement, fixture.telemetry.events.size)
    }
    @Test
    fun earlyVocabularyRecallUsesObjectiveChoiceThenFadesToTypedMeaning() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """
            {"russian":"дом","lemma":"choice-house","pos":"noun","translation":"house","tier":0,"unit":1,"generalFreqRank":100}
            {"russian":"стол","lemma":"choice-table","pos":"noun","translation":"table","tier":0,"unit":1,"generalFreqRank":110}
            {"russian":"окно","lemma":"choice-window","pos":"noun","translation":"window","tier":0,"unit":1,"generalFreqRank":120}
            {"russian":"книга","lemma":"choice-book","pos":"noun","translation":"book","tier":0,"unit":1,"generalFreqRank":130}
            """.trimIndent()
        )
        val note = fixture.notes.getByLemma("choice-house")!!
        val card = fixture.cards.cards.first {
            it.noteId == note.id && it.cardType == CardType.RU_TO_MEANING
        }
        fixture.cards.update(card.copy(state = CardState.LEARNING, reps = 1))

        val supported = fixture.repository.promptForCard(card, now = 86_400_000L)!!
        assertEquals(AnswerMode.CHOICE, supported.answerMode)
        assertEquals(ChoiceArchetype.MEANING_RECOGNITION, supported.choiceArchetype)
        assertEquals(4, supported.choices.size)
        assertTrue(supported.expectedAnswer in supported.choices)

        fixture.cards.update(card.copy(state = CardState.REVIEW, reps = 3))
        val faded = fixture.repository.promptForCard(card, now = 2L * 86_400_000L)!!
        assertEquals(AnswerMode.ENGLISH, faded.answerMode)
        assertTrue(faded.choices.isEmpty())
        assertNull(faded.choiceArchetype)
    }

    @Test
    fun restoredQueueKeepsOnlyNewOrCurrentlyDueCards() = runTest {
        val fixture = RepoFixture()
        fixture.repository.importJsonLines(
            """{"russian":"дом","lemma":"restore-house","pos":"noun","translation":"house","tier":0,"unit":1,"exampleSentence":"Это дом.","exampleTranslation":"This is a house."}"""
        )
        val note = fixture.notes.getByLemma("restore-house")!!
        val cards = fixture.cards.cards.filter { it.noteId == note.id }.take(3)
        val newCard = cards[0].copy(state = CardState.NEW, due = 99_000L)
        val dueCard = cards[1].copy(state = CardState.REVIEW, reps = 3, due = 1_000L)
        val futureCard = cards[2].copy(state = CardState.REVIEW, reps = 3, due = 99_000L)
        for (card in listOf(newCard, dueCard, futureCard)) {
            fixture.cards.update(card)
        }

        val restored = fixture.repository.recoverablePromptsForCardIds(
            listOf(newCard.id, dueCard.id, futureCard.id),
            now = 2_000L
        )

        assertEquals(setOf(newCard.id, dueCard.id), restored.map { it.card.id }.toSet())
    }
}
