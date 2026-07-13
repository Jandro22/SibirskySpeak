package com.sibirskyspeak.review

import com.sibirskyspeak.data.CardState
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.SessionPlan

/** Pure, testable queue policy for one-session learner overrides. */
object TemporarySessionPolicy {
    fun queue(plan: SessionPlan, mode: TemporarySessionMode): List<ReviewPrompt> {
        val queue = plan.reviewQueue
        return when (mode) {
            TemporarySessionMode.BALANCED -> queue
            TemporarySessionMode.REVIEWS_ONLY -> queue.filter { it.card.state != CardState.NEW }
            TemporarySessionMode.RECOVERY -> {
                val atRisk = plan.blueprint?.atRiskCardIds.orEmpty()
                queue.filter { it.card.id in atRisk }
                    .ifEmpty { queue.filter { it.card.state != CardState.NEW } }
            }
            TemporarySessionMode.READER_ONLY -> emptyList()
            TemporarySessionMode.FOCUS -> {
                val focusTypes = setOf(CardType.RU_TO_MEANING, CardType.MEANING_TO_RU, CardType.CLOZE, CardType.CASE_FILL)
                queue.filter { it.card.cardType in focusTypes }.ifEmpty { queue }.take(8)
            }
        }
    }
}
