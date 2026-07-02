package com.sibirskyspeak.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementTestTest {
    @Test
    fun questionBankCoversEveryLevelTwice() {
        assertEquals(PlacementTest.LEVELS.size * 2, PlacementTest.QUESTIONS.size)
        val byLevel = PlacementTest.QUESTIONS.groupingBy { it.level }.eachCount()
        PlacementTest.LEVELS.forEach { level ->
            assertEquals("$level should have exactly 2 questions", 2, byLevel[level])
        }
    }

    @Test
    fun everyQuestionHasAValidCorrectIndexAndDistinctChoices() {
        PlacementTest.QUESTIONS.forEach { q ->
            assertTrue("${q.prompt}: correctIndex in range", q.correctIndex in q.choices.indices)
            assertEquals("${q.prompt}: choices must be distinct", q.choices.toSet().size, q.choices.size)
            assertTrue("${q.prompt}: at least 2 choices", q.choices.size >= 2)
        }
    }

    @Test
    fun correctAnswerPositionsAreBalancedAndNotGuessable() {
        val counts = PlacementTest.QUESTIONS.groupingBy { it.correctIndex }.eachCount()
        assertEquals(setOf(0, 1, 2, 3), counts.keys)
        assertTrue("no answer position should dominate", counts.values.maxOrNull()!! <= 4)
    }

    @Test
    fun allCorrectSuggestsTheHighestLevel() {
        val allCorrect = List(PlacementTest.QUESTIONS.size) { true }
        assertEquals("C2", PlacementTest.suggestedLevel(allCorrect))
    }

    @Test
    fun allWrongSuggestsNoPlacement() {
        val allWrong = List(PlacementTest.QUESTIONS.size) { false }
        assertNull(PlacementTest.suggestedLevel(allWrong))
    }

    @Test
    fun stopsAdvancingAtTheFirstFullyMissedLevel() {
        // A1 and A2 mostly right, B1 both wrong, B2/C1/C2 all right anyway —
        // the streak must stop at A2 regardless of later correct answers,
        // since a learner who fails B1 shouldn't be placed past it.
        val answers = mutableListOf<Boolean>()
        PlacementTest.QUESTIONS.forEach { q ->
            answers += when (q.level) {
                "A1", "A2" -> true
                "B1" -> false
                else -> true
            }
        }
        assertEquals("A2", PlacementTest.suggestedLevel(answers))
    }

    @Test
    fun halfCreditDoesNotSkipPrerequisites() {
        val byLevel = PlacementTest.LEVELS.associateWith { 1 }
        assertNull(PlacementTest.suggestedLevel(byLevel))
    }

    @Test
    fun zeroOfTwoBreaksTheStreakImmediately() {
        val byLevel = linkedMapOf("A1" to 2, "A2" to 0, "B1" to 2)
        assertEquals("A1", PlacementTest.suggestedLevel(byLevel))
    }

    @Test
    fun emptyMapSuggestsNoPlacement() {
        assertNull(PlacementTest.suggestedLevel(emptyMap()))
    }
}
