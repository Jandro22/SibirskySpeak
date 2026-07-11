package com.sibirskyspeak

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.SessionPlan
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.review.ReviewUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DashboardNextActionCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun primaryActionAndAdjustTodayAreSeparateActions() {
        val note = Note(russian = "дом", translation = "house", partOfSpeech = "noun", lemma = "дом")
        val prompt = ReviewPrompt(
            card = Card(noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB),
            note = note,
            prompt = "дом",
            expectedAnswer = "house",
            answerMode = AnswerMode.ENGLISH,
            intervalPreview = emptyMap()
        )
        var started = false
        var customized = false
        compose.setContent {
            SibirskySpeakTheme {
                DashboardNextActionCard(
                    state = ReviewUiState(sessionPlan = SessionPlan("focus", listOf(prompt), emptyList(), emptyList(), null, com.sibirskyspeak.data.DashboardStats(1, 1, 0, 0, 0, 0, 0.0, null, false, com.sibirskyspeak.data.ImportQualityReport(0, 0, 0, 0, 0, 0, 0, 0, 0, false, emptyList())) , com.sibirskyspeak.data.DailyPlan(emptyList(), null, 0, 0, false))),
                    onStart = { started = true },
                    onCustomize = { customized = true }
                )
            }
        }
        compose.onNodeWithTag(TestTags.DASHBOARD_NEXT_ACTION_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.DASHBOARD_ADJUST_TODAY).performClick()
        assertTrue(started)
        assertTrue(customized)
    }
}
