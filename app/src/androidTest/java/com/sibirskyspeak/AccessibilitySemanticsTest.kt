package com.sibirskyspeak

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.sibirskyspeak.review.SessionStep
import org.junit.Rule
import org.junit.Test

/** Small regression checks for the controls most often used with TalkBack/switch access. */
class AccessibilitySemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bottomNavigationExposesNamesActionsAndSelectedState() {
        compose.setContent {
            SibirskySpeakTheme {
                MainBottomBar(selected = SessionStep.REVIEWS, onSelect = {})
            }
        }

        compose.onNodeWithContentDescription("Practice").assertHasClickAction().assertIsSelected()
        compose.onNodeWithContentDescription("Progress").assertHasClickAction()
        compose.onNodeWithContentDescription("Lab").assertHasClickAction()
        compose.onNodeWithContentDescription("Settings").assertHasClickAction()
        compose.onNodeWithTag(TestTags.NAV_PRACTICE).assertHasClickAction()
    }
}
