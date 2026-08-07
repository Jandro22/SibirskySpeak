package com.sibirskyspeak.learning

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.review.buildPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPedagogyTest {
    @Test
    fun everyPersistedCardTypeHasACompleteNonzeroStageProfile() {
        assertEquals(CardType.values().toSet(), CardPedagogy.profiles.keys)
        CardType.values().forEach { type ->
            val profile = CardPedagogy.profile(type)
            assertTrue("$type must declare a transfer target", profile.transferTarget.isNotBlank())
            assertTrue("$type must have positive cost", profile.cognitiveCost > 0.0)
            LearningStage.values().forEach { stage ->
                assertTrue("$type must remain selectable at $stage", profile.weight(stage) > 0.0)
            }
        }
    }

    @Test
    fun everyCardTypeUpdatesAnExplicitNormalizedSkillMixture() {
        CardType.values().forEach { type ->
            val weights = WorldModel.skillWeights(Card(noteId = 1, cardType = type, queue = Queue.VOCAB))
            assertTrue("$type has no learner skill model", weights.isNotEmpty())
            assertEquals("$type weights", 1.0, weights.values.sum(), 1e-9)
            assertTrue(weights.values.all { it > 0.0 })
        }
    }

    @Test
    fun speakingRemovesWrittenAnswerAfterScaffoldedAttempts() {
        val note = Note(id = 1, russian = "молоко́", lemma = "молоко", translation = "milk", partOfSpeech = "noun")
        val scaffolded = buildPrompt(Card(noteId = 1, cardType = CardType.SPEAK, queue = Queue.VOCAB, reps = 1), note, emptyMap())
        val independent = buildPrompt(Card(noteId = 1, cardType = CardType.SPEAK, queue = Queue.VOCAB, reps = 2), note, emptyMap())

        assertEquals(note.russian, scaffolded.prompt)
        assertTrue(independent.prompt.startsWith("Say in Russian:"))
        assertTrue(!independent.prompt.contains(note.russian))
    }

    @Test
    fun lowMeaningRetentionShiftsMatureSelectionTowardProduction() {
        val recognition = CardPedagogy.retentionCompensationBias(
            CardType.RU_TO_MEANING, isNew = false, meaningRetention = 0.48,
            meaningSampleSize = 116, targetRetention = 0.90
        )
        val production = CardPedagogy.retentionCompensationBias(
            CardType.MEANING_TO_RU, isNew = false, meaningRetention = 0.48,
            meaningSampleSize = 116, targetRetention = 0.90
        )

        assertTrue(recognition < 0.0)
        assertTrue(production > 0.0)
        assertEquals(0.0, CardPedagogy.retentionCompensationBias(
            CardType.RU_TO_MEANING, isNew = true, meaningRetention = 0.48,
            meaningSampleSize = 116, targetRetention = 0.90
        ), 0.0)
    }

    @Test
    fun smallSamplesDoNotChangeTheLearningDiet() {
        assertEquals(0.0, CardPedagogy.retentionCompensationBias(
            CardType.MEANING_TO_RU, isNew = false, meaningRetention = 0.25,
            meaningSampleSize = 4, targetRetention = 0.90
        ), 0.0)
    }
}
