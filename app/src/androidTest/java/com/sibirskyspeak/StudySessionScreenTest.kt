package com.sibirskyspeak

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Queue
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.review.ReviewUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Renders [StudySessionScreen] directly against a hand-built [ReviewUiState] instead of a
 * full ReviewViewModel/LearningRepository stack — that stack takes 17 constructor
 * dependencies and isn't worth wiring up just to check that a button is tagged, visible,
 * and wired to the right callback. Every tag exercised here mirrors a real interaction
 * this app's manual QA pass relied on this session (see TestTags.kt).
 */
class StudySessionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val note = Note(russian = "дом", translation = "house", partOfSpeech = "noun", lemma = "дом")
    private val card = Card(noteId = 1, cardType = CardType.RU_TO_MEANING, queue = Queue.VOCAB, reps = 3)

    private fun recallPrompt(): ReviewPrompt = ReviewPrompt(
        card = card,
        note = note,
        prompt = "дом",
        expectedAnswer = "house",
        answerMode = AnswerMode.ENGLISH,
        intervalPreview = emptyMap()
    )

    private fun lessonPrompt(): ReviewPrompt = ReviewPrompt(
        card = card.copy(cardType = CardType.LESSON, queue = Queue.GRAMMAR, reps = 0),
        note = note,
        prompt = "New word: дом",
        expectedAnswer = "Got it",
        answerMode = AnswerMode.LESSON,
        intervalPreview = emptyMap(),
        lesson = com.sibirskyspeak.review.LessonContent(
            title = "New word: дом",
            body = listOf("Meaning: house"),
            exampleRu = "",
            exampleEn = ""
        )
    )

    @Test
    fun ratingButtonsInvokeTheirOwnRating() {
        var lastRating: Rating? = null
        compose.setContent {
            SibirskySpeakTheme {
                StudySessionScreen(
                    state = ReviewUiState(prompt = recallPrompt(), revealed = true, inStudySession = true),
                    typedAnswer = MutableStateFlow(""),
                    correctionAnswer = MutableStateFlow(""),
                    onAnswerChanged = {},
                    onChoice = {},
                    onReveal = {},
                    onRate = { lastRating = it },
                    onContinue = {},
                    onCorrectionChanged = {},
                    onSubmitCorrection = {},
                    onSpeak = {},
                    onExit = {},
                    onUndo = {},
                    onKnewIt = {},
                    onSuspend = {},
                    onKnowWord = {},
                    onStartSession = {},
                    onSaveEdit = { _, _, _, _ -> }
                )
            }
        }

        compose.onNodeWithTag(TestTags.RATE_AGAIN).performClick()
        assertEquals("expected AGAIN", Rating.AGAIN, lastRating)

        compose.onNodeWithTag(TestTags.RATE_EASY).performClick()
        assertEquals("expected EASY", Rating.EASY, lastRating)
    }

    @Test
    fun lessonGotItRatesGoodAndGraduatesTheLesson() {
        var lastRating: Rating? = null
        compose.setContent {
            SibirskySpeakTheme {
                StudySessionScreen(
                    state = ReviewUiState(prompt = lessonPrompt(), inStudySession = true),
                    typedAnswer = MutableStateFlow(""),
                    correctionAnswer = MutableStateFlow(""),
                    onAnswerChanged = {},
                    onChoice = {},
                    onReveal = {},
                    onRate = { lastRating = it },
                    onContinue = {},
                    onCorrectionChanged = {},
                    onSubmitCorrection = {},
                    onSpeak = {},
                    onExit = {},
                    onUndo = {},
                    onKnewIt = {},
                    onSuspend = {},
                    onKnowWord = {},
                    onStartSession = {},
                    onSaveEdit = { _, _, _, _ -> }
                )
            }
        }

        compose.onNodeWithTag(TestTags.LESSON_GOT_IT).performClick()
        // Matches the app's own rule: a LESSON card graduates immediately on any
        // rating, and its single button always rates GOOD (StudyScreens.kt).
        assertEquals("expected GOOD", Rating.GOOD, lastRating)
    }

    @Test
    fun answerInputFieldForwardsTypedTextToCallback() {
        var typed = ""
        compose.setContent {
            SibirskySpeakTheme {
                StudySessionScreen(
                    state = ReviewUiState(prompt = recallPrompt(), inStudySession = true),
                    typedAnswer = MutableStateFlow(""),
                    correctionAnswer = MutableStateFlow(""),
                    onAnswerChanged = { typed = it },
                    onChoice = {},
                    onReveal = {},
                    onRate = {},
                    onContinue = {},
                    onCorrectionChanged = {},
                    onSubmitCorrection = {},
                    onSpeak = {},
                    onExit = {},
                    onUndo = {},
                    onKnewIt = {},
                    onSuspend = {},
                    onKnowWord = {},
                    onStartSession = {},
                    onSaveEdit = { _, _, _, _ -> }
                )
            }
        }

        compose.onNodeWithTag(TestTags.ANSWER_INPUT_FIELD).performTextInput("house")
        assertEquals("house", typed)
    }
}
