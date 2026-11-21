package com.healthanalyzer.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthanalyzer.app.ai.PlanWorkoutEntity
import com.healthanalyzer.app.ai.Recap
import com.healthanalyzer.app.sync.AutoSyncInterval
import com.healthanalyzer.app.sync.AutoSyncSettings
import com.healthanalyzer.app.sync.AutoSyncStatus
import com.healthanalyzer.app.sync.SourceOutcome
import com.healthanalyzer.app.ui.AutoSyncSection
import com.healthanalyzer.app.ui.TodayCard
import com.healthanalyzer.app.ui.theme.HealthAnalyzerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AutoSyncSectionTest {
    @get:Rule
    val rule = createComposeRule()

    private var changed: AutoSyncSettings? = null
    private var syncNow = 0
    private var allow = 0

    private fun show(
        settings: AutoSyncSettings = AutoSyncSettings(),
        status: AutoSyncStatus = AutoSyncStatus(),
        syncing: Boolean = false,
        needsBackground: Boolean = false,
    ) = rule.setContent {
        HealthAnalyzerTheme {
            AutoSyncSection(
                settings = settings,
                status = status,
                syncing = syncing,
                onAllowHealthConnectBackground = if (needsBackground) ({ allow++ }) else null,
                onChange = { changed = it },
                onSyncNow = { syncNow++ },
            )
        }
    }

    @Test
    fun defaults_dailyAndOnOpen_notSyncedYet() {
        show()
        rule.onNodeWithText("Daily").assertIsDisplayed()
        rule.onNodeWithText("Not synced automatically yet.").assertIsDisplayed()
        rule.onNodeWithText("Sync now").performClick()
        assertEquals(1, syncNow)
    }

    @Test
    fun turningOffSyncOnOpen_reportsNewSettings() {
        show()
        rule.onNodeWithTag("auto_sync_on_open").performClick()
        assertEquals(AutoSyncSettings(onOpen = false), changed)
    }

    @Test
    fun pickingInterval_reportsNewSettings() {
        show()
        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("Every 6 hours").performClick()
        assertEquals(AutoSyncInterval.H6, changed?.interval)
    }

    @Test
    fun lastSyncStatus_listsSources() {
        show(status = AutoSyncStatus(System.currentTimeMillis(), 2, listOf(SourceOutcome("Garmin", true, "2 new run(s)"))))
        rule.onNodeWithText("2 new run(s)", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Garmin: 2 new run(s)", substring = true).assertIsDisplayed()
    }

    @Test
    fun whileSyncing_buttonDisabled() {
        show(syncing = true)
        rule.onNodeWithText("Syncing…").assertIsDisplayed()
        rule.onNodeWithText("Sync now").assertIsNotEnabled()
    }

    @Test
    fun healthConnectBackgroundHint_onlyWhenNeeded() {
        show(needsBackground = true)
        rule.onNodeWithText("Allow").performClick()
        assertEquals(1, allow)
    }

    @Test
    fun healthConnectBackgroundHint_hiddenWhenScheduleOff() {
        show(settings = AutoSyncSettings(interval = AutoSyncInterval.OFF, onOpen = false), needsBackground = true)
        rule.onNodeWithText("Allow").assertDoesNotExist()
        rule.onNodeWithTag("auto_sync_on_open").assertIsOff()
    }

    @Test
    fun todayCard_showsSyncingBeforeBriefing() {
        rule.setContent {
            HealthAnalyzerTheme {
                TodayCard(
                    todaysPlan = listOf(PlanWorkoutEntity(date = LocalDate.now().toString(), type = "EASY", title = "Easy run", target = "")),
                    runsToday = emptyList(),
                    recap = Recap(),
                    running = true,
                    hasPlace = false,
                    onRefresh = {},
                    onOpenDay = {},
                    syncing = true,
                )
            }
        }
        rule.onNodeWithTag("today_recap_syncing").assertIsDisplayed()
        rule.onNodeWithTag("today_recap_button").assertDoesNotExist()
    }
}
