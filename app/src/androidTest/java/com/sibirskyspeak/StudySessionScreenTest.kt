package com.sibirskyspeak

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.ExitTicketItem
import com.sibirskyspeak.data.ExitTicketSession
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

    private fun russianPrompt(): ReviewPrompt = ReviewPrompt(
        card = card.copy(cardType = CardType.CASE_FILL),
        note = note,
        prompt = "I see the house",
        expectedAnswer = "дом",
        answerMode = AnswerMode.RUSSIAN_TYPED,
        intervalPreview = emptyMap()
    )

    @Test
    fun capstoneIsAFullTapOnlyRoomWithNoTextEntryAction() {
        var selected: String? = null
        val capstone = ExitTicketSession(
            unit = 7,
            band = "A1",
            canDoLabel = "handle a short everyday exchange",
            items = listOf(
                ExitTicketItem(
                    kind = "dialogue",
                    noteId = 1L,
                    prompt = "Partner: Как дела?",
                    expectedAnswer = "Хорошо, спасибо.",
                    choices = listOf("Хорошо, спасибо.", "До свидания.", "Меня зовут Анна.")
                )
            )
        )
        compose.setContent {
            SibirskySpeakTheme {
                StudySessionScreen(
                    state = ReviewUiState(exitTicketSession = capstone, inStudySession = true),
                    typedAnswer = MutableStateFlow(""),
                    correctionAnswer = MutableStateFlow(""),
                    onAnswerChanged = {},
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
                    onSaveEdit = { _, _, _, _ -> },
                    onSubmitCapstoneAnswer = { selected = it }
                )
            }
        }

        compose.onNodeWithText("Unit 7 capstone").assertExists()
        compose.onNodeWithText("Finish later").assertExists()
        compose.onNodeWithText("Tap one answer. No Russian keyboard is needed.").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("${TestTags.EXIT_TICKET_CHOICE_PREFIX}_0").performClick()
        assertEquals("Хорошо, спасибо.", selected)
    }

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

    @Test
    fun russianAnswersStartWithTilesInsteadOfAKeyboard() {
        compose.setContent {
            SibirskySpeakTheme {
                StudySessionScreen(
                    state = ReviewUiState(prompt = russianPrompt(), inStudySession = true),
                    typedAnswer = MutableStateFlow(""),
                    correctionAnswer = MutableStateFlow(""),
                    onAnswerChanged = {},
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

        compose.onNodeWithText("Tap tiles to build the answer").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun novelRussianProductionCannotFallBackToKeyboardInput() {
        val prompt = russianPrompt().copy(
            card = russianPrompt().card.copy(cardType = CardType.NOVEL_PRODUCE),
            prompt = "Say: I see the house",
            expectedAnswer = "Я вижу дом."
        )
        compose.setContent {
            SibirskySpeakTheme {
                StudySessionScreen(
                    state = ReviewUiState(prompt = prompt, inStudySession = true),
                    typedAnswer = MutableStateFlow(""),
                    correctionAnswer = MutableStateFlow(""),
                    onAnswerChanged = {},
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

        compose.onNodeWithText("Tap tiles to build the answer").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun promptAutoplayHasOneOwnerAcrossUnrelatedRecomposition() {
        var spoken = 0
        val state = androidx.compose.runtime.mutableStateOf(
            ReviewUiState(prompt = recallPrompt(), inStudySession = true)
        )
        compose.setContent {
            SibirskySpeakTheme {
                StudySessionScreen(
                    state = state.value,
                    typedAnswer = MutableStateFlow(""),
                    correctionAnswer = MutableStateFlow(""),
                    onAnswerChanged = {},
                    onChoice = {},
                    onReveal = {},
                    onRate = {},
                    onContinue = {},
                    onCorrectionChanged = {},
                    onSubmitCorrection = {},
                    onSpeak = { spoken += 1 },
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

        compose.waitForIdle()
        assertEquals(1, spoken)
        compose.runOnIdle {
            state.value = state.value.copy(statusMessage = "unrelated")
        }
        compose.waitForIdle()
        assertEquals(1, spoken)
    }

    @Test
    fun revealedCardShowsCorrectAnswerInResultBanner() {
        compose.setContent {
            SibirskySpeakTheme {
                StudySessionScreen(
                    state = ReviewUiState(
                        prompt = recallPrompt(),
                        revealed = true,
                        isAnswerCorrect = false,
                        answerFeedback = "Check the meaning again.",
                        inStudySession = true
                    ),
                    typedAnswer = MutableStateFlow("wrong"),
                    correctionAnswer = MutableStateFlow(""),
                    onAnswerChanged = {},
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

        compose.onNodeWithText("Correct answer: house").assertExists()
        compose.onNodeWithText("Check the meaning again.").assertExists()
    }
}
