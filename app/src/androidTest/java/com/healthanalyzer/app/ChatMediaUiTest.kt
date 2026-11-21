package com.healthanalyzer.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthanalyzer.app.ai.ChatAttachment
import com.healthanalyzer.app.ui.MessageMedia
import com.healthanalyzer.app.ui.PendingMediaRow
import com.healthanalyzer.app.ui.theme.HealthAnalyzerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMediaUiTest {
    @get:Rule
    val rule = createComposeRule()

    // File names that don't exist: thumbnails fall back to a placeholder, no real media needed.
    private val photo = ChatAttachment(ChatAttachment.IMAGE, listOf("missing-photo.jpg"))
    private val video = ChatAttachment(ChatAttachment.VIDEO, listOf("missing-frame.jpg"), durationMs = 75_000)

    @Test
    fun pendingRowShowsItemsAndRemoves() {
        val removed = mutableListOf<ChatAttachment>()
        rule.setContent { HealthAnalyzerTheme { PendingMediaRow(listOf(photo, video), importing = 1, onRemove = { removed += it }) } }

        rule.onNodeWithTag("pendingMedia_0").assertIsDisplayed()
        rule.onNodeWithTag("pendingMedia_1").assertIsDisplayed()
        rule.onNodeWithTag("mediaImporting").assertIsDisplayed()
        rule.onNodeWithText("▶ 1:15").assertIsDisplayed()
        rule.onNodeWithTag("removeMedia_1").performClick()
        assertEquals(listOf(video), removed)
    }

    @Test
    fun emptyRowShowsNothing() {
        rule.setContent { HealthAnalyzerTheme { PendingMediaRow(emptyList(), importing = 0, onRemove = {}) } }
        rule.onNodeWithTag("pendingMedia").assertDoesNotExist()
    }

    @Test
    fun sentMessageShowsThumbnails() {
        rule.setContent { HealthAnalyzerTheme { MessageMedia(listOf(photo, video)) } }
        rule.onAllNodesWithContentDescription("Photo").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Video").assertCountEquals(1)
    }
}
