package com.sibirskyspeak

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun firstRunOffersBeginnerAndPlacementPaths() {
        var selected = ""
        compose.setContent {
            SibirskySpeakTheme {
                OnboardingPanel(
                    onStartAtBeginning = { selected = "beginner" },
                    onTakePlacement = { selected = "placement" }
                )
            }
        }

        compose.onNodeWithTag(TestTags.ONBOARDING_BEGINNER).performClick()
        assertEquals("beginner", selected)
        compose.onNodeWithTag(TestTags.ONBOARDING_PLACEMENT).performClick()
        assertEquals("placement", selected)
    }
}
