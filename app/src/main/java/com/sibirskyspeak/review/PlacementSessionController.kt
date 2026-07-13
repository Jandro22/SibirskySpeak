package com.sibirskyspeak.review

import com.sibirskyspeak.learning.PlacementTest

/** Pure placement-session reducer; the ViewModel only owns persistence/UI state. */
data class PlacementStep(
    val questionIndex: Int,
    val answers: List<Boolean>,
    val completed: Boolean,
    val result: String?
)

object PlacementSessionController {
    fun initial(): PlacementStep = PlacementStep(0, emptyList(), false, null)

    fun answer(step: PlacementStep, choiceIndex: Int): PlacementStep {
        if (step.completed) return step
        val question = PlacementTest.QUESTIONS.getOrNull(step.questionIndex) ?: return step.copy(completed = true)
        val answers = step.answers + (choiceIndex == question.correctIndex)
        val next = step.questionIndex + 1
        return if (next >= PlacementTest.QUESTIONS.size) {
            PlacementStep(next, answers, true, PlacementTest.suggestedLevel(answers))
        } else {
            PlacementStep(next, answers, false, null)
        }
    }
}
