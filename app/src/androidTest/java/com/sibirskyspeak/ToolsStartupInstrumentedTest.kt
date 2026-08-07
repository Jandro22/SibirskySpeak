package com.sibirskyspeak

import android.app.KeyguardManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test

class ToolsStartupInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    @Test
    fun tutorToActionablePracticePublishesWithinColdStartBudget() {
        // A secure physical-device keyguard immediately pauses MainActivity, so a
        // locked CI/QA phone cannot measure Compose readiness. Treat that as an
        // unavailable test environment rather than a 30-second app-start failure.
        val keyguard = compose.activity.getSystemService(KeyguardManager::class.java)
        assumeFalse("Device is securely locked", keyguard.isDeviceLocked)

        compose.waitUntil(30_000) {
            hasTag(TestTags.TUTOR_CONTINUE) || hasTag(TestTags.TUTOR_ONBOARDING_START)
        }
        if (hasTag(TestTags.TUTOR_ONBOARDING_START)) {
            compose.onNodeWithTag(TestTags.TUTOR_ONBOARDING_START).performClick()
            compose.waitUntil(30_000) { hasTag(TestTags.TUTOR_CONTINUE) }
        }

        val startedAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(TestTags.TUTOR_OPEN_TOOLS).performClick()
        compose.waitUntil(5_000) { hasTag(TestTags.NAV_PRACTICE) }
        val shellMs = SystemClock.elapsedRealtime() - startedAt
        compose.waitUntil(15_000) { hasTag(TestTags.TOOLS_CONTINUE_EPISODES) }
        val actionableMs = SystemClock.elapsedRealtime() - startedAt

        Log.i("ToolsStartup", "shellMs=$shellMs actionableMs=$actionableMs")
        assertTrue("Tools shell took ${shellMs}ms", shellMs <= 2_000)
        assertTrue("Practice plan took ${actionableMs}ms", actionableMs <= 12_000)
    }
}
