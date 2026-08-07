package com.sibirskyspeak.data

import com.sibirskyspeak.EpisodeSnapshotCodec
import com.sibirskyspeak.DelayedRepairPlanner
import com.sibirskyspeak.TutorUiState
import com.sibirskyspeak.evaluateTutorResponse
import com.sibirskyspeak.observedTutorTask
import com.sibirskyspeak.generation.DialogueTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunicativeLearningTest {
    private val component = KnowledgeComponent(
        key = "FORM:42",
        kind = "FORM",
        capabilityKey = "A1:1",
        band = "A1",
        unit = 1,
        noteId = 42,
        due = 0,
        stabilityDays = 1.0,
        difficulty = 5.0,
        confidence = 0.2
    )

    @Test
    fun instructionCreatesNoSchedulingEvidence() {
        assertEquals(component, ComponentScheduler.update(component, success = true, evidenceWeight = 0.0, at = 1000))
    }

    @Test
    fun assistedSuccessAdvancesButDoesNotImplyMastery() {
        val assisted = ComponentScheduler.update(component, success = true, evidenceWeight = 0.35, at = 1000)
        val unsupported = ComponentScheduler.update(component, success = true, evidenceWeight = 1.0, at = 1000)

        assertTrue(assisted.confidence > component.confidence)
        assertTrue(assisted.confidence < unsupported.confidence)
        assertTrue(assisted.stabilityDays < unsupported.stabilityDays)
        assertEquals(1, assisted.reps)
    }

    @Test
    fun missSchedulesRepairWithoutErasingHistory() {
        val missed = ComponentScheduler.update(component.copy(reps = 4, lapses = 1), success = false, evidenceWeight = 0.45, at = 1000)
        assertEquals(5, missed.reps)
        assertEquals(2, missed.lapses)
        assertTrue(missed.confidence < component.confidence)
        assertTrue(missed.due > 1000)
    }

    @Test
    fun successfulEffortNearForgettingGrowsStabilityMoreThanImmediateRepetition() {
        val day = 86_400_000L
        val established = component.copy(reps = 3, stabilityDays = 5.0, lastEvidenceAt = 10 * day)
        val immediate = ComponentScheduler.update(established, success = true, evidenceWeight = 1.0, at = 10 * day)
        val delayed = ComponentScheduler.update(established, success = true, evidenceWeight = 1.0, at = 20 * day)

        assertTrue(ComponentScheduler.retrievability(established, 20 * day) < ComponentScheduler.retrievability(established, 10 * day))
        assertTrue(delayed.stabilityDays > immediate.stabilityDays)
        assertTrue(delayed.due > 20 * day)
    }

    @Test
    fun assistedCapabilityProgressIsNotCertified() {
        val progress = CapabilityProgress(
            capabilityKey = "A1:1",
            band = "A1",
            unit = 1,
            canDo = "introduce yourself",
            completedEpisodes = 3,
            successfulTransferProbes = 3,
            attemptedTransferProbes = 3,
            lastTransferScore = 1.0
        )
        assertNull(progress.certifiedAt)
        assertTrue(progress.isRouteReady())
        assertTrue(!progress.copy(successfulTransferProbes = 1).isRouteReady())
        assertTrue(!progress.copy(attemptedTransferProbes = 1, successfulTransferProbes = 1).isRouteReady())
    }

    @Test
    fun episodeContextNeverLeaksTheLearnerResponse() {
        val turns = listOf(
            DialogueTurn("npc", "npc", "Как вас зовут?", "What is your name?", emptyList()),
            DialogueTurn("learner", "learner", "Меня зовут Анна.", "My name is Anna.", listOf("Меня зовут Анна.")),
            DialogueTurn("npc2", "npc", "Очень приятно.", "Nice to meet you.", emptyList())
        )
        val note = Note(
            id = 42,
            russian = "зовут",
            translation = "(they) call",
            partOfSpeech = "verb",
            lemma = "звать",
            exampleSentence = "Как вас зовут?",
            exampleTranslation = "What is your name?",
            exampleSentence2 = "Его зовут Иван.",
            exampleTranslation2 = "His name is Ivan.",
            tier = 0,
            unit = 0,
            cefrLevel = "A1"
        )
        val secondNote = Note(
            id = 43,
            russian = "приятно",
            translation = "pleasant",
            partOfSpeech = "adverb",
            lemma = "приятно",
            exampleSentence = "Очень приятно.",
            exampleTranslation = "Nice to meet you.",
            tier = 0,
            unit = 0,
            cefrLevel = "A1"
        )

        val tasks = EpisodeTaskPlanner.plan(
            turns,
            listOf(note, secondNote),
            listOf(component.copy(noteId = note.id)),
            choiceNotes = listOf(
                note,
                secondNote,
                note.copy(id = 44, exampleTranslation = "Where do you live?"),
                note.copy(id = 45, exampleTranslation = "What do they call you?"),
                secondNote.copy(id = 46, exampleTranslation = "It was good to meet you."),
                secondNote.copy(id = 47, exampleTranslation = "The introduction went well.")
            )
        )

        val context = tasks.first { it.kind == EpisodeTaskKind.CONTEXT }
        val guided = tasks.first { it.kind == EpisodeTaskKind.GUIDED_RESPONSE }
        val transfer = tasks.first { it.kind == EpisodeTaskKind.TRANSFER }
        assertNull(context.english)
        assertEquals("Как вас зовут?", context.russian)
        assertTrue("Меня зовут Анна." !in context.russian)
        assertEquals("", guided.russian)
        assertEquals("Меня зовут Анна.", guided.expected)
        assertEquals("Его зовут Иван.", transfer.expected)
        assertTrue(transfer.novelContext)
        val listening = tasks.first { it.kind == EpisodeTaskKind.LISTENING }
        val reading = tasks.first { it.kind == EpisodeTaskKind.READING }
        assertTrue(ComponentKeys.sound(note.id) in listening.componentKeys)
        assertTrue(ComponentKeys.sound(secondNote.id) !in reading.componentKeys)
        assertTrue(guided.componentKeys.none { it.startsWith("SOUND:") })
        assertTrue(transfer.componentKeys.none { it.startsWith("SOUND:") })
    }

    @Test
    fun freshInstallComponentsIncludeGrammarConstructionWithoutMakingLessonLexemes() {
        val word = Note(
            id = 7, russian = "книга", translation = "book", partOfSpeech = "noun", lemma = "книга",
            tier = 0, unit = 1, cefrLevel = "A1"
        )
        val lesson = Note(
            id = 8, russian = "Gender", translation = "Gender", partOfSpeech = "lesson", lemma = "lesson_gender",
            tier = 0, unit = 1, cefrLevel = "A1", conceptId = "GENDER"
        )
        val knownWord = word.copy(id = 9, russian = "дом", lemma = "дом", status = WordStatus.KNOWN)

        val components = KnowledgeComponentFactory.forUnit(listOf(word, lesson, knownWord), "A1", 1, now = 123)

        assertEquals(setOf("MEANING", "FORM", "SOUND", "CONSTRUCTION"), components.map { it.kind }.toSet())
        assertTrue(components.any { it.key == ComponentKeys.construction("GENDER", "A1", 1) })
        assertTrue(components.none { it.noteId == lesson.id })
        assertTrue(components.none { it.noteId == knownWord.id })
    }

    @Test
    fun episodeCheckpointRoundTripsAtAnAnsweredStep() {
        val task = EpisodeTask(
            id = "listen:1",
            kind = EpisodeTaskKind.LISTENING,
            instruction = "Listen",
            russian = "дом",
            expected = "house",
            choices = listOf("home", "house"),
            componentKeys = listOf("SOUND:1", "MEANING:1"),
            supportLevel = 1
        )
        val state = TutorUiState(
            loading = false,
            episode = CommunicativeEpisode(
                id = "episode", capabilityKey = "A1:1", band = "A1", unit = 1,
                canDo = "identify an object", title = "At home", estimatedMinutes = 4,
                mode = EpisodeMode.RETRIEVE, focus = "bring meanings back before they fade",
                tasks = listOf(task)
            ),
            checked = true,
            correct = true,
            transferSuccesses = 1,
            transferAttempts = 1
        )

        val restored = EpisodeSnapshotCodec.decode(EpisodeSnapshotCodec.encode(state))!!

        assertEquals("episode", restored.episode!!.id)
        assertEquals(task, restored.task)
        assertEquals(EpisodeMode.RETRIEVE, restored.episode!!.mode)
        assertEquals("bring meanings back before they fade", restored.episode!!.focus)
        assertTrue(restored.checked)
        assertEquals(true, restored.correct)
        assertEquals(1, restored.transferAttempts)
    }

    @Test
    fun adaptivePolicyStartsWithAcquisitionThenShortensAHighFrictionRepairEpisode() {
        val now = 10 * 86_400_000L
        val progress = CapabilityProgress("A1:1", "A1", 1, "identify an object")
        val newComponents = (1L..3L).flatMap { noteId ->
            listOf("MEANING", "FORM", "SOUND").map { kind ->
                component.copy(key = "$kind:$noteId", kind = kind, noteId = noteId, reps = 0, lapses = 0)
            }
        }
        val acquire = AdaptiveEpisodePolicyEngine.decide(newComponents, emptyList(), progress, now)
        assertEquals(EpisodeMode.ACQUIRE, acquire.mode)
        assertEquals(5, acquire.targetMinutes)

        val fragile = newComponents.map { it.copy(reps = 4, lapses = 2, lastEvidenceAt = now - 3 * 86_400_000L) }
        val evidence = (0 until 10).map { index ->
            CapabilityEvidence(
                id = index.toLong() + 1,
                componentKey = fragile[index % fragile.size].key,
                episodeId = "prior",
                taskId = "task:$index",
                observedAt = now - index,
                taskKind = "CONTRAST",
                outcome = if (index < 4) "MISS" else "SUCCESS",
                supportLevel = 1,
                evidenceWeight = 0.65,
                responseMs = 20_000
            )
        }
        val repair = AdaptiveEpisodePolicyEngine.decide(fragile, evidence, progress.copy(completedEpisodes = 1), now)
        assertEquals(EpisodeMode.REPAIR, repair.mode)
        assertEquals(4, repair.targetMinutes)
        assertEquals(6, repair.maxTasks)
    }

    @Test
    fun repairPolicyRanksARepeatedLapseAheadOfUnseenConvenientMaterial() {
        val now = 5 * 86_400_000L
        val lapsed = component.copy(noteId = 10, key = "MEANING:10", reps = 5, lapses = 3, lastEvidenceAt = now - 2 * 86_400_000L)
        val unseen = component.copy(noteId = 11, key = "MEANING:11", reps = 0, lapses = 0, lastEvidenceAt = null)
        val policy = AdaptiveEpisodePolicy(
            EpisodeMode.REPAIR, "repair", 3, 5, 2, 1, 2, 0.4, "MEANING"
        )
        assertEquals(listOf(10L, 11L), AdaptiveEpisodePolicyEngine.rankNoteIds(listOf(unseen, lapsed), policy, now))
    }

    @Test
    fun routeNeedsObservedMeaningFormAndSoundInAdditionToTransferScreens() {
        val now = 1_000L
        val progress = CapabilityProgress(
            "A1:1", "A1", 1, "identify an object",
            completedEpisodes = 3, successfulTransferProbes = 3, attemptedTransferProbes = 3
        )
        val observed = (1L..2L).flatMap { noteId ->
            listOf("MEANING", "FORM", "SOUND").map { kind ->
                component.copy(
                    key = "$kind:$noteId", kind = kind, noteId = noteId,
                    reps = 2, stabilityDays = 3.0, confidence = 0.6, lastEvidenceAt = now
                )
            }
        }
        assertTrue(AdaptiveEpisodePolicyEngine.masteryProfile(observed, now).supportsRouteAdvance(progress))
        val noSoundEvidence = observed.map { if (it.kind == "SOUND") it.copy(reps = 0, confidence = 0.0) else it }
        assertFalse(AdaptiveEpisodePolicyEngine.masteryProfile(noSoundEvidence, now).supportsRouteAdvance(progress))
        assertFalse(AdaptiveEpisodePolicyEngine.masteryProfile(observed.map { it.copy(reps = 0) }, now).supportsRouteAdvance(progress))
    }

    @Test
    fun repairEpisodeKeepsTransferButAddsInterleavingAndAConfusionContrastWithinBudget() {
        fun note(id: Long, russian: String, translation: String) = Note(
            id = id, russian = russian, translation = translation, partOfSpeech = "noun", lemma = russian,
            exampleSentence = russian, exampleTranslation = translation,
            exampleSentence2 = "Это $russian.", exampleTranslation2 = "This is $translation.",
            tier = 0, unit = 1, cefrLevel = "A1"
        )
        val target = note(1, "дом", "house")
        val other = note(2, "дым", "smoke")
        val earlier = note(3, "книга", "book")
        val policy = AdaptiveEpisodePolicy(EpisodeMode.REPAIR, "repair", 4, 6, 2, 1, 1, 0.4, "MEANING")
        val tasks = EpisodeTaskPlanner.plan(
            turns = listOf(
                DialogueTurn("npc", "npc", "Что это?", "What is it?", emptyList()),
                DialogueTurn("learner", "learner", "Это дом.", "It is a house.", listOf("Это дом."))
            ),
            prioritizedNotes = listOf(target, other),
            components = listOf(component.copy(noteId = 1, key = "FORM:1")),
            policy = policy,
            interleavedNotes = listOf(earlier),
            contrasts = listOf(ContrastCandidate(target, other, "confusable_spelling"))
        )
        assertTrue(tasks.size <= policy.maxTasks)
        assertTrue(tasks.any { it.kind == EpisodeTaskKind.RETRIEVAL })
        assertTrue(tasks.any { it.kind == EpisodeTaskKind.CONTRAST && it.choices.toSet() == setOf("дом", "дым") })
        assertTrue(tasks.any { it.kind == EpisodeTaskKind.TRANSFER })
    }

    @Test
    fun retrievalEpisodeIncludesASeparateMediationTaskWhenBudgetAllows() {
        val note = Note(
            id = 1, russian = "дом", translation = "house", partOfSpeech = "noun", lemma = "дом",
            exampleSentence = "Это дом.", exampleTranslation = "This is a house.", tier = 0, unit = 1, cefrLevel = "A1"
        )
        val tasks = EpisodeTaskPlanner.plan(
            turns = listOf(DialogueTurn("npc", "npc", "Что это?", "What is it?", emptyList())),
            prioritizedNotes = listOf(note),
            components = listOf(component.copy(noteId = 1, key = "FORM:1")),
            policy = AdaptiveEpisodePolicy(EpisodeMode.RETRIEVE, "retrieve", 5, 10, 1, 0, 0, 0.0, "FORM")
        )
        val mediation = tasks.single { it.kind == EpisodeTaskKind.MEDIATION }
        assertEquals("Что это?", mediation.expected)
        assertEquals("What is it?", mediation.english)
    }

    @Test
    fun mediationUsesAnNpcReplyInsteadOfTheNonTranslatingSceneSetup() {
        val note = Note(
            id = 1, russian = "дом", translation = "house", partOfSpeech = "noun", lemma = "дом",
            exampleSentence = "Это дом.", exampleTranslation = "This is a house.", tier = 0, unit = 1, cefrLevel = "A1"
        )
        val tasks = EpisodeTaskPlanner.plan(
            turns = listOf(
                DialogueTurn("opening", "npc", "Давайте начнём.", "You are at home and need to identify someone.", emptyList()),
                DialogueTurn("learner", "learner", "Кто это?", "Who is this?", listOf("Кто это?")),
                DialogueTurn("reply", "npc", "Это Анна.", "This is Anna.", emptyList())
            ),
            prioritizedNotes = listOf(note),
            components = listOf(component.copy(noteId = 1, key = "FORM:1")),
            policy = AdaptiveEpisodePolicy(EpisodeMode.RETRIEVE, "retrieve", 5, 10, 1, 0, 0, 0.0, "FORM")
        )

        val mediation = tasks.single { it.kind == EpisodeTaskKind.MEDIATION }
        assertEquals("Это Анна.", mediation.expected)
        assertEquals("This is Anna.", mediation.english)
    }

    @Test
    fun aMissReturnsAfterTwoInterveningTasksAndCannotCreateAnInfiniteRepairLoop() {
        val original = EpisodeTask(
            "target", EpisodeTaskKind.CONTRAST, "Choose", "", expected = "дом",
            choices = listOf("дом", "дым"), componentKeys = listOf("MEANING:1"), supportLevel = 1
        )
        val fillers = (1..3).map { EpisodeTask("filler:$it", EpisodeTaskKind.NOTICE, "Notice", "x") }
        val episode = CommunicativeEpisode(
            id = "episode", capabilityKey = "A1:1", band = "A1", unit = 1,
            canDo = "identify an object", title = "At home", estimatedMinutes = 4,
            tasks = listOf(original) + fillers
        )
        val scheduled = DelayedRepairPlanner.schedule(episode, 0, original)
        assertEquals(listOf("target", "filler:1", "filler:2", "target:repair", "filler:3"), scheduled.tasks.map { it.id })
        assertEquals(scheduled, DelayedRepairPlanner.schedule(scheduled, 0, original))
        val repair = scheduled.tasks.first { it.kind == EpisodeTaskKind.REPAIR }
        assertEquals(scheduled, DelayedRepairPlanner.schedule(scheduled, 3, repair))
    }

    @Test
    fun tutorTilesDoNotMisclassifyRussianMorphologyAsATypo() {
        val task = EpisodeTask(
            "case", EpisodeTaskKind.GUIDED_RESPONSE, "Build", "",
            expected = "Я вижу стола.", supportLevel = 2
        )
        assertFalse(evaluateTutorResponse(task, "Я вижу стол."))
        assertTrue(evaluateTutorResponse(task, "я вижу стола"))
    }

    @Test
    fun tutorAcceptsMultipleAuthoredRepliesAndRepairsThroughTheAlternateCarrier() {
        val task = EpisodeTask(
            "reply", EpisodeTaskKind.GUIDED_RESPONSE, "Reply", "",
            expected = "Добрый день.", repairExpected = "Здравствуйте.",
            acceptable = listOf("Добрый день.", "Здравствуйте."),
            choices = listOf("Добрый день.", "Здравствуйте."), supportLevel = 1
        )
        assertTrue(evaluateTutorResponse(task, "Здравствуйте."))
        val episode = CommunicativeEpisode(
            capabilityKey = "A1:0", band = "A1", unit = 0, canDo = "greet someone",
            title = "Meeting", estimatedMinutes = 4,
            tasks = listOf(task, EpisodeTask("gap1", EpisodeTaskKind.NOTICE, "Notice", "x"), EpisodeTask("gap2", EpisodeTaskKind.NOTICE, "Notice", "y"))
        )
        val repair = DelayedRepairPlanner.schedule(episode, 0, task).tasks.first { it.kind == EpisodeTaskKind.REPAIR }
        assertEquals("Здравствуйте.", repair.expected)
        assertTrue(repair.novelContext)
    }

    @Test
    fun delayedRepairMovesTheEnglishCueWithItsAlternateCarrier() {
        val task = EpisodeTask(
            "transfer", EpisodeTaskKind.TRANSFER, "Respond", "",
            english = "Who is this?", expected = "Кто это?",
            repairExpected = "Что вы скажете?", repairEnglish = "What will you say?"
        )
        val episode = CommunicativeEpisode(
            capabilityKey = "A1:0", band = "A1", unit = 0, canDo = "identify someone",
            title = "At home", estimatedMinutes = 4,
            tasks = listOf(task, EpisodeTask("gap1", EpisodeTaskKind.NOTICE, "Notice", "x"))
        )

        val repair = DelayedRepairPlanner.schedule(episode, 0, task).tasks.first { it.kind == EpisodeTaskKind.REPAIR }
        assertEquals("Что вы скажете?", repair.expected)
        assertEquals("What will you say?", repair.english)
    }

    @Test
    fun communicativeRubricAcceptsIntelligibleRewordingButRequiresMeaningAndConstruction() {
        val task = EpisodeTask(
            "open", EpisodeTaskKind.PRODUCTION_PROBE, "Respond", "",
            expected = "Я вижу большой дом.", acceptable = listOf("Я вижу большой дом."),
            semanticAnchors = listOf("дом"), constructionCues = listOf("вижу"),
            minimumMeaningCoverage = 0.55, supportLevel = 0
        )
        assertTrue(evaluateTutorResponse(task, "Большой дом я вижу."))
        assertFalse(evaluateTutorResponse(task, "Большую книгу я вижу."))
        assertFalse(evaluateTutorResponse(task, "Это большой дом."))
    }

    @Test
    fun branchSpecificFactReturnsAfterInterveningTurnsAndResolvesFromTheLearnersChoice() {
        val first = DialogueTurn(
            "l0", "learner", "", "Choose a reply", listOf("Первый ответ.", "Второй ответ."),
            responseFeedback = mapOf("Первый ответ." to "Факт один.", "Второй ответ." to "Факт два."),
            responseFacts = mapOf("Первый ответ." to "Факт один.", "Второй ответ." to "Факт два.")
        )
        val turns = listOf(
            first,
            DialogueTurn("l1", "learner", "", "Continue", listOf("Продолжим.")),
            DialogueTurn("l2", "learner", "", "Continue", listOf("Хорошо."))
        )
        val tasks = EpisodeTaskPlanner.plan(
            turns, emptyList(), emptyList(),
            AdaptiveEpisodePolicy(EpisodeMode.ACQUIRE, "learn", 5, 20, 0, 0, 0, 0.0, "MEANING")
        )
        val gapIndex = tasks.indexOfFirst { it.kind == EpisodeTaskKind.INFORMATION_GAP }
        assertTrue(gapIndex > tasks.indexOfFirst { it.id == "guided:1" })
        val episode = CommunicativeEpisode(
            capabilityKey = "A1:0", band = "A1", unit = 0, canDo = "exchange information",
            title = "A real gap", estimatedMinutes = 5, tasks = tasks
        )
        val state = TutorUiState(
            loading = false, episode = episode, taskIndex = gapIndex,
            acceptedAnswers = mapOf("guided:0" to "Второй ответ.")
        )
        assertEquals("Факт два.", state.task?.expected)
        assertEquals(setOf("Факт один.", "Факт два."), state.task?.choices?.toSet())
    }

    @Test
    fun runtimeBudgetKeepsEveryBandInsideTheShortEpisodeContract() {
        val cases = listOf(
            Triple("A1", 6, 7), Triple("A2", 8, 8), Triple("B1", 12, 9),
            Triple("B2", 16, 10), Triple("C1", 20, 11)
        )
        cases.forEach { (band, authoredTurns, maxTasks) ->
            val turns = (0 until authoredTurns).map { index -> DialogueTurn(
                "l$index", "learner", "", "Respond", listOf("Ответ $index а.", "Ответ $index б."),
                responseFacts = mapOf("Ответ $index а." to "Факт $index а.", "Ответ $index б." to "Факт $index б.")
            ) }
            val tasks = EpisodeTaskPlanner.plan(
                turns, emptyList(), emptyList(),
                AdaptiveEpisodePolicy(EpisodeMode.ACQUIRE, "learn", 5, maxTasks, 0, 0, 0, 0.0, "MEANING")
            )
            assertTrue("$band exceeded its short-episode budget", tasks.size <= maxTasks)
            assertTrue("$band lost all active response practice", tasks.any { it.kind == EpisodeTaskKind.GUIDED_RESPONSE })
        }
    }

    @Test
    fun authoredSupportFadesFromChoicesToTilesWithoutRemovingTheCommunicativeCue() {
        val note = Note(
            id = 44, russian = "дом", translation = "house", partOfSpeech = "noun", lemma = "дом",
            exampleSentence = "Это дом.", exampleTranslation = "This is a house.", tier = 0, unit = 1, cefrLevel = "A1"
        )
        val turns = listOf(
            DialogueTurn("reply", "learner", "Это дом.", "It is a house.", listOf("Это дом.", "Вот дом."))
        )
        fun guided(stage: Int) = EpisodeTaskPlanner.plan(
            turns = turns,
            prioritizedNotes = listOf(note),
            components = listOf(component.copy(noteId = note.id, key = "FORM:${note.id}")),
            policy = AdaptiveEpisodePolicy(EpisodeMode.RETRIEVE, "retrieve", 4, 12, 1, 0, 0, 0.0, "FORM", stage),
            register = "polite",
            intention = "identify the place"
        ).single { it.kind == EpisodeTaskKind.GUIDED_RESPONSE }

        val choices = guided(0)
        val tiles = guided(1)
        val contextOnly = guided(2)
        assertTrue(choices.choices.size >= 2)
        assertTrue(choices.english != null)
        assertTrue(tiles.choices.isEmpty())
        assertTrue(tiles.english != null)
        assertEquals(2, tiles.supportLevel)
        assertEquals("It is a house.", contextOnly.english)
        assertEquals(1, contextOnly.supportLevel)
        assertTrue(contextOnly.novelContext)
    }

    @Test
    fun pragmaticsTaskGradesSocialFitAndExplainsARegisterMismatch() {
        val note = Note(
            id = 45, russian = "уточнить", translation = "clarify", partOfSpeech = "verb", lemma = "уточнить",
            exampleSentence = "Уточните, пожалуйста.", exampleTranslation = "Please clarify.", tier = 0, unit = 20, cefrLevel = "B2"
        )
        val task = EpisodeTaskPlanner.plan(
            turns = listOf(DialogueTurn("npc", "request", "Что вы имеете в виду?", "What do you mean?", emptyList())),
            prioritizedNotes = listOf(note),
            components = listOf(component.copy(noteId = null, key = "CONSTRUCTION:clarification", kind = "CONSTRUCTION")),
            policy = AdaptiveEpisodePolicy(EpisodeMode.RETRIEVE, "retrieve", 5, 16, 1, 0, 0, 0.0, "CONSTRUCTION", 2),
            register = "formal",
            intention = "clarify a disagreement"
        ).single { it.kind == EpisodeTaskKind.PRAGMATICS }

        val mismatch = task.choices.first { it != task.expected }
        assertTrue(task.instruction.contains("formal"))
        assertTrue(task.responseFeedback.getValue(mismatch).contains("confrontational"))
        assertTrue(task.responseFeedback.getValue(task.expected!!).contains("social goal"))
        assertEquals(listOf("CONSTRUCTION:clarification"), task.componentKeys)
    }

    @Test
    fun listeningProfilesCoverSpeakerSpeedEmotionPhoneAnnouncementAndNoiseConditions() {
        val policy = AdaptiveEpisodePolicy(EpisodeMode.ACQUIRE, "listen", 5, 12, 2, 0, 0, 0.0, "SOUND")
        val profiles = (0L..13L).map { id ->
            val target = Note(
                id = id, russian = "слово$id", translation = "word$id", partOfSpeech = "noun", lemma = "слово$id",
                exampleSentence = "Это слово$id.", exampleTranslation = "This is word$id.", tier = 0, unit = 1, cefrLevel = "A1"
            )
            val distractor = target.copy(id = id + 100, translation = "other$id", exampleTranslation = "This is other$id.")
            EpisodeTaskPlanner.plan(emptyList(), listOf(target, distractor), emptyList(), policy)
                .first { it.kind == EpisodeTaskKind.LISTENING }
        }
        val conditions = profiles.mapNotNull { it.audioCondition }.toSet()
        assertTrue(setOf(
            "fast casual reductions", "urgent emotional tone", "warm emotional tone",
            "telephone-quality", "public announcement", "controlled background noise"
        ).all { it in conditions })
        assertTrue(profiles.map { it.voiceVariant }.toSet().size >= 3)
        assertTrue(profiles.map { it.audioRate }.toSet().size >= 5)
    }

    @Test
    fun choiceOrderingIsStableButDoesNotLeakOneUniversalCorrectPosition() {
        val positions = (0..5).map { seed ->
            val first = orderedEpisodeChoices("house", listOf("book", "smoke"), seed)
            assertEquals(first, orderedEpisodeChoices("house", listOf("book", "smoke"), seed))
            first.indexOf("house")
        }.toSet()
        assertEquals(setOf(0, 1, 2), positions)
    }

    @Test
    fun choiceSelectionUsesTheWholePoolAndVariesAcrossEpisodes() {
        val distractors = listOf("book", "smoke", "orange juice", "train station", "good evening")
        val pools = (0..8).map { seed ->
            orderedEpisodeChoices("house", distractors, seed).filter { it != "house" }.toSet()
        }.toSet()

        assertTrue(pools.size >= 3)
        assertTrue(pools.flatten().toSet().size >= 4)
    }

    @Test
    fun meaningDistractorsMatchShapeAndRejectLongCorruptedText() {
        fun note(id: Long, meaning: String) = Note(
            id = id, russian = "слово$id", translation = "word$id", partOfSpeech = "word", lemma = "word$id",
            exampleSentence = "Пример $id.", exampleTranslation = meaning, tier = 0, unit = 0, cefrLevel = "A1"
        )
        val target = note(1, "I heard that he passed the exam.")
        val choices = meaningMatchedDistractors(
            target.exampleTranslation!!,
            target,
            listOf(
                target,
                note(2, "Everything is going well."),
                note(3, "She works at the station."),
                note(4, "Where is the giraffe?"),
                note(5, "orange juice"),
                note(6, "And you will not find yourself in an unloved home\nAnd you will run into fear like mines in the dark")
            ),
            seed = 0
        )

        assertEquals(setOf("Everything is going well.", "She works at the station."), choices.take(2).toSet())
        assertTrue(choices.none { '\n' in it || it.endsWith("?") || it == "orange juice" })
    }

    @Test
    fun staleCurriculumComponentsMoveOrRetireWithoutLosingMemory() {
        val remembered = component.copy(
            key = "MEANING:7", noteId = 7, capabilityKey = "A1:0", band = "A1", unit = 0,
            reps = 9, stabilityDays = 42.0, confidence = 0.8
        )
        val movedNote = Note(
            id = 7, russian = "слово", translation = "word", partOfSpeech = "noun", lemma = "слово",
            tier = 0, unit = 12, cefrLevel = "A2"
        )

        val moved = reconcileComponentMembership(remembered, movedNote)
        assertEquals("A2:12", moved.capabilityKey)
        assertEquals(9, moved.reps)
        assertEquals(42.0, moved.stabilityDays, 0.0)
        assertFalse(moved.retired)

        val unassigned = reconcileComponentMembership(remembered, movedNote.copy(unit = null))
        assertTrue(unassigned.retired)
        assertEquals(9, unassigned.reps)
    }

    @Test
    fun malformedOptionalDialogueFallsBackInsteadOfBlockingAnEpisode() {
        val dialogue = ContentDialogue("a1_unit_001_dialogue", 1, "introduce yourself", "Introductions")
        assertTrue(safelyScriptedTurns(dialogue, emptyList()).isEmpty())
    }

    @Test
    fun authoredScenarioFamiliesCycleWithoutDependingOnDatabaseOrder() {
        val base = ContentDialogue("a1_unit_001_dialogue", 1, "introduce", "Base")
        val second = ContentDialogue("a1_unit_001_dialogue:scenario-2", 1, "introduce", "Class")
        val tenth = ContentDialogue("a1_unit_001_dialogue:scenario-10", 1, "introduce", "Shop")
        val values = listOf(tenth, second, base)

        assertEquals(base, selectDialogueVariant(values, base.id, 0))
        assertEquals(second, selectDialogueVariant(values, base.id, 1))
        assertEquals(tenth, selectDialogueVariant(values, base.id, 2))
        assertEquals(base, selectDialogueVariant(values, base.id, 3))
    }

    @Test
    fun transferModeSelectsTheUnitsBlindFamilyWhileLearningModesKeepCues() {
        val base = ContentDialogue("a1_unit_001_dialogue", 1, "introduce", "Meeting")
        val practice = ContentDialogue("a1_unit_001_dialogue:family-2", 1, "introduce", "Class")
        val blind = ContentDialogue(
            "a1_unit_001_dialogue:family-3:blind-transfer", 1, "introduce", "Shop",
            blindTransfer = true
        )
        val values = listOf(blind, practice, base)

        assertEquals(blind, selectDialogueVariant(values, base.id, 3, EpisodeMode.TRANSFER))
        assertFalse(selectDialogueVariant(values, base.id, 2, EpisodeMode.RETRIEVE)!!.blindTransfer)
    }

    @Test
    fun transferModeCreatesAnUnsupportedSpeechProbeWithAssistedFallbackKeptSeparate() {
        val note = Note(
            id = 9, russian = "дом", translation = "house", partOfSpeech = "noun", lemma = "дом",
            exampleSentence = "Это дом.", exampleTranslation = "This is a house.",
            exampleSentence2 = "Вот дом.", exampleTranslation2 = "Here is the house.", tier = 0, unit = 1, cefrLevel = "A1"
        )
        val policy = AdaptiveEpisodePolicy(EpisodeMode.TRANSFER, "transfer", 4, 6, 2, 0, 0, 0.0, "FORM", supportStage = 3)
        val probe = EpisodeTaskPlanner.plan(
            emptyList(), listOf(note), listOf(component.copy(noteId = 9)), policy,
            blindAssessment = true
        )
            .first { it.kind == EpisodeTaskKind.PRODUCTION_PROBE }
        assertEquals(0, probe.supportLevel)
        assertTrue(probe.novelContext)
        assertTrue(evaluateTutorResponse(probe, "вот дом"))
        assertFalse(evaluateTutorResponse(probe, "дом вот"))

        val fallback = observedTutorTask(probe, repairing = false, speechFallback = true)
        assertEquals(EpisodeTaskKind.TRANSFER, fallback.kind)
        assertEquals(2, fallback.supportLevel)
        assertTrue(fallback.id.endsWith(":assisted"))
    }

    @Test
    fun onlyAuthoredBlindFamiliesCanCreateCueFreeCertificationProbes() {
        val note = Note(
            id = 10, russian = "дом", translation = "house", partOfSpeech = "noun", lemma = "дом",
            exampleSentence = "Это дом.", exampleTranslation = "This is a house.",
            exampleSentence2 = "Вот дом.", exampleTranslation2 = "Here is the house.", tier = 0, unit = 1, cefrLevel = "A1"
        )
        val turns = listOf(DialogueTurn("l0", "learner", "", "Say where the house is", listOf("Вот дом.")))
        val policy = AdaptiveEpisodePolicy(EpisodeMode.TRANSFER, "transfer", 5, 20, 1, 0, 0, 0.0, "FORM", supportStage = 3)
        val blind = EpisodeTaskPlanner.plan(turns, listOf(note), listOf(component.copy(noteId = 10)), policy, blindAssessment = true)
        assertTrue(blind.any { it.kind == EpisodeTaskKind.PRODUCTION_PROBE })
        assertTrue(blind.filter { it.kind == EpisodeTaskKind.PRODUCTION_PROBE }.all { it.supportLevel == 0 && it.choices.isEmpty() })
        assertTrue(blind.none { it.kind in setOf(EpisodeTaskKind.NOTICE, EpisodeTaskKind.READING, EpisodeTaskKind.LISTENING) })

        val fallback = EpisodeTaskPlanner.plan(turns, listOf(note), listOf(component.copy(noteId = 10)), policy, blindAssessment = false)
        assertTrue(fallback.none { it.kind == EpisodeTaskKind.PRODUCTION_PROBE })
    }

    @Test
    fun certificationRequiresTwoIndependentSuccessfulProbeEpisodesAndRouteReadiness() {
        val at = 1_000L
        val progress = CapabilityProgress(
            "A1:1", "A1", 1, "identify an object",
            completedEpisodes = 3, successfulTransferProbes = 3, attemptedTransferProbes = 3
        )
        val components = (1L..2L).flatMap { noteId ->
            listOf("MEANING", "FORM", "SOUND").map { kind ->
                component.copy(
                    key = "$kind:$noteId", kind = kind, noteId = noteId,
                    reps = 2, confidence = 0.7, stabilityDays = 4.0, lastEvidenceAt = at
                )
            }
        }
        val mastery = AdaptiveEpisodePolicyEngine.masteryProfile(components, at)
        fun probe(episode: String, support: Int = 0) = CapabilityEvidence(
            componentKey = "FORM:1", episodeId = episode, taskId = "speak",
            observedAt = at, taskKind = EpisodeTaskKind.PRODUCTION_PROBE.name,
            outcome = "SUCCESS", supportLevel = support,
            evidenceWeight = if (support == 0) 0.8 else 0.35, novelContext = true
        )
        assertNull(certificationTime(progress, mastery, listOf(probe("one")), at))
        assertEquals(at, certificationTime(progress, mastery, listOf(probe("one"), probe("two")), at))
        assertNull(certificationTime(progress, mastery, listOf(probe("one"), probe("two", support = 2)), at))
        assertNull(certificationTime(progress.copy(completedEpisodes = 1), mastery, listOf(probe("one"), probe("two")), at))
    }
}
