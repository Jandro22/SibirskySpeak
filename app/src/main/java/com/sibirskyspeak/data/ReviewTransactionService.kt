package com.sibirskyspeak.data

import javax.inject.Inject

/** Narrow review/undo boundary; transaction semantics stay owned by the repository adapter. */
class ReviewTransactionService @Inject constructor(private val repository: LearningRepository) {
    suspend fun review(
        card: Card,
        rating: Rating,
        objectiveCorrect: Boolean? = null,
        instructionalExposure: Boolean = false
    ): Boolean = repository.review(
        card,
        rating,
        objectiveCorrect = objectiveCorrect,
        instructionalExposure = instructionalExposure
    )

    suspend fun undoLastReview(): Card? = repository.undoLastReview()

    fun clearUndo() = repository.clearUndo()

    fun canUndo(): Boolean = repository.canUndo()
}
