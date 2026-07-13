package com.sibirskyspeak.review

import com.sibirskyspeak.learning.PlacementTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementSessionControllerTest {
    @Test
    fun answersAdvanceAndCompleteWithTheConservativeLevel() {
        var step = PlacementSessionController.initial()
        PlacementTest.QUESTIONS.forEach { question ->
            step = PlacementSessionController.answer(step, question.correctIndex)
        }
        assertTrue(step.completed)
        assertEquals("C2", step.result)
        assertEquals(PlacementTest.QUESTIONS.size, step.answers.size)
    }

    @Test
    fun firstMissStopsPlacementAtTheBeginning() {
        val step = PlacementSessionController.answer(PlacementSessionController.initial(), 1)
        assertFalse(step.completed)
        var current = step
        repeat(PlacementTest.QUESTIONS.size - 1) {
            current = PlacementSessionController.answer(current, PlacementTest.QUESTIONS[current.questionIndex].correctIndex)
        }
        assertTrue(current.completed)
        assertEquals(null, current.result)
    }
}
