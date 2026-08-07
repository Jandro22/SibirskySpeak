package com.sibirskyspeak

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import com.sibirskyspeak.data.CommunicativeEpisode
import com.sibirskyspeak.data.EpisodeTask
import com.sibirskyspeak.data.EpisodeTaskKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TutorScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun state(task: EpisodeTask) = TutorUiState(
        loading = false,
        episode = CommunicativeEpisode(
            id = "episode", capabilityKey = "A1:1", band = "A1", unit = 1,
            canDo = "identify an object", title = "At home", estimatedMinutes = 4,
            tasks = listOf(task)
        )
    )

    @Test
    fun listeningHidesTheRussianTranscriptAndRequiresAChoice() {
        var answer = ""
        val task = EpisodeTask(
            id = "listen", kind = EpisodeTaskKind.LISTENING, instruction = "Listen, then choose the meaning.",
            russian = "дом", expected = "house", choices = listOf("book", "house"), supportLevel = 1
        )
        compose.setContent {
            SibirskySpeakTheme {
                EpisodeSurface(state(task), { answer = it }, {}, {}, {}, { _, _, _, _, _ -> })
            }
        }

        compose.onAllNodesWithText("дом").assertCountEquals(0)
        compose.onNodeWithTag(TestTags.TUTOR_LISTEN).assertIsDisplayed()
        compose.onNodeWithTag("${TestTags.TUTOR_CHOICE_PREFIX}_1").performClick()
        assertEquals("house", answer)
        compose.onNodeWithTag(TestTags.TUTOR_CHECK).assertIsDisplayed()
    }

    @Test
    fun guidedResponseUsesSupportedTilesWithoutPrintingTheAnswer() {
        val task = EpisodeTask(
            id = "guided", kind = EpisodeTaskKind.GUIDED_RESPONSE, instruction = "Build your reply.",
            russian = "", english = "This is a house.", expected = "Это дом.", supportLevel = 2
        )
        compose.setContent {
            SibirskySpeakTheme {
                EpisodeSurface(state(task), {}, {}, {}, {}, { _, _, _, _, _ -> })
            }
        }

        compose.onAllNodesWithText("Это дом.").assertCountEquals(0)
        compose.onNodeWithText("Tap tiles to build the answer").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.ANSWER_TILE_ASSEMBLED).assertIsDisplayed()
        compose.onNodeWithTag("${TestTags.ANSWER_TILE_PREFIX}_0").assertIsDisplayed()
        compose.onNodeWithText("supported practice", substring = true).assertIsDisplayed()
    }

    @Test
    fun confusionContrastUsesRussianChoicesWithoutOpeningAKeyboard() {
        val answer = mutableStateOf("")
        val task = EpisodeTask(
            id = "contrast", kind = EpisodeTaskKind.CONTRAST,
            instruction = "Choose the Russian that means this.", russian = "", english = "house",
            expected = "дом", choices = listOf("дом", "дым"), supportLevel = 1
        )
        compose.setContent {
            SibirskySpeakTheme { EpisodeSurface(state(task).copy(answer = answer.value), { answer.value = it }, {}, {}, {}, { _, _, _, _, _ -> }) }
        }

        compose.onNodeWithText("дом").assertIsDisplayed()
        compose.onNodeWithText("дым").assertIsDisplayed().performClick()
        assertEquals("дым", answer.value)
        compose.onNodeWithContentDescription("Selected answer").assertIsDisplayed()
        compose.onAllNodesWithText("Tap tiles to build the answer").assertCountEquals(0)
    }

    @Test
    fun aMissOffersAContinueToDelayedRetryInsteadOfImmediateCopying() {
        var continued = false
        val task = EpisodeTask(
            id = "guided", kind = EpisodeTaskKind.GUIDED_RESPONSE, instruction = "Build your reply.",
            russian = "", english = "This is a house.", expected = "Это дом.", supportLevel = 2
        )
        val missed = state(task).copy(
            checked = true,
            correct = false,
            feedback = "Study the correction now. It will return after other material."
        )
        compose.setContent {
            SibirskySpeakTheme { EpisodeSurface(missed, {}, {}, { continued = true }, {}, { _, _, _, _, _ -> }) }
        }

        compose.onNodeWithText("Continue; retry later").assertIsDisplayed().performClick()
        assertEquals(true, continued)
        compose.onAllNodesWithText("Rebuild once").assertCountEquals(0)
        compose.onNodeWithText("это").assertIsNotEnabled()
    }

    @Test
    fun productionProbeHidesTheAnswerAndCanFallBackToKeyboardFreeTiles() {
        val fallback = mutableStateOf(false)
        val answer = mutableStateOf("")
        val task = EpisodeTask(
            id = "speak", kind = EpisodeTaskKind.PRODUCTION_PROBE,
            instruction = "Say the Russian response without seeing it.", russian = "",
            english = "This is a house.", expected = "Это дом.", supportLevel = 0, novelContext = true
        )
        compose.setContent {
            SibirskySpeakTheme {
                EpisodeSurface(
                    state(task).copy(answer = answer.value, speechFallback = fallback.value),
                    { answer.value = it }, {}, {}, { fallback.value = true }, { _, _, _, _, _ -> }
                )
            }
        }

        compose.onAllNodesWithText("Это дом.").assertCountEquals(0)
        compose.onNodeWithTag(TestTags.TUTOR_SPEECH_FALLBACK).assertIsDisplayed().performClick()
        compose.onNodeWithText("Tap tiles to build the answer").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}
