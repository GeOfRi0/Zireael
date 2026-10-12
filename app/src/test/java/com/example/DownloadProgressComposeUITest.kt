package com.example

import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Jetpack Compose UI tests verifying that StateFlow download percentages
 * reflect accurately onto the UI using Compose test rule and derivedStateOf.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadProgressComposeUITest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `download percentage stateflow updates UI text accurately`() {
        val downloadedBytesFlow = MutableStateFlow(25L)
        val totalBytes = 100L

        composeTestRule.setContent {
            val downloaded by downloadedBytesFlow.collectAsState()
            val percent by remember(downloaded) {
                derivedStateOf {
                    if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0
                }
            }

            Text(text = "İlerleme: %$percent")
        }

        // Verify initial 25% is displayed
        composeTestRule.onNodeWithText("İlerleme: %25").assertIsDisplayed()

        // Update StateFlow to 75%
        downloadedBytesFlow.value = 75L
        composeTestRule.waitForIdle()

        // Verify updated 75% is displayed
        composeTestRule.onNodeWithText("İlerleme: %75").assertIsDisplayed()

        // Update StateFlow to 100%
        downloadedBytesFlow.value = 100L
        composeTestRule.waitForIdle()

        // Verify 100% completion is displayed
        composeTestRule.onNodeWithText("İlerleme: %100").assertIsDisplayed()
    }
}
