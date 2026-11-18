package com.healthanalyzer.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthanalyzer.app.ai.PlanWorkoutEntity
import com.healthanalyzer.app.ai.Recap
import com.healthanalyzer.app.ui.TodayCard
import com.healthanalyzer.app.ui.theme.HealthAnalyzerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TodayCardTest {
    @get:Rule
    val rule = createComposeRule()

    private val today = LocalDate.now().toString()
    private val easy = PlanWorkoutEntity(date = today, type = "EASY", title = "Easy run", target = "6'30/km")

    private fun show(recap: Recap, onRefresh: () -> Unit = {}) = rule.setContent {
        HealthAnalyzerTheme {
            TodayCard(
                todaysPlan = listOf(easy),
                runsToday = emptyList(),
                recap = recap,
                running = false,
                hasPlace = true,
                onRefresh = onRefresh,
                onOpenDay = {},
            )
        }
    }

    @Test
    fun withoutBriefing_showsPlanAndWriteButton() {
        var clicks = 0
        show(Recap()) { clicks++ }
        rule.onNodeWithTag("today_card").assertIsDisplayed()
        rule.onNodeWithText("Easy run · 6'30/km").assertIsDisplayed()
        rule.onNodeWithText("Write briefing").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun withTodaysBriefing_showsTextAndRefresh() {
        show(Recap(key = "$today|x", text = "Run before noon."))
        rule.onNodeWithText("Run before noon.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("today_recap_button").assertIsDisplayed()
        rule.onNodeWithText("Refresh").assertIsDisplayed()
    }

    @Test
    fun failedBriefing_offersTryAgain() {
        show(Recap(key = "$today|x", error = "No provider"))
        rule.onNodeWithText("No provider").assertIsDisplayed()
        rule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun staleBriefing_fromEarlierDay_isHidden() {
        show(Recap(key = "2000-01-01|x", text = "Old advice"))
        rule.onNodeWithText("Old advice", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Write briefing").assertIsDisplayed()
    }
}
