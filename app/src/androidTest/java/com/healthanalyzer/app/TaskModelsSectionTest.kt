package com.healthanalyzer.app

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthanalyzer.app.ai.AiProviderEntity
import com.healthanalyzer.app.ai.AiTask
import com.healthanalyzer.app.ai.Protocol
import com.healthanalyzer.app.ai.TaskModel
import com.healthanalyzer.app.ai.TaskModels
import com.healthanalyzer.app.ai.ThinkingLevel
import com.healthanalyzer.app.ui.ProviderModels
import com.healthanalyzer.app.ui.TaskModelsSection
import com.healthanalyzer.app.ui.theme.HealthAnalyzerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskModelsSectionTest {
    @get:Rule
    val rule = createComposeRule()

    private val providers = listOf(
        AiProviderEntity(1, "Claude", Protocol.ANTHROPIC, "https://api.anthropic.com", "", "claude-opus-5-5"),
        AiProviderEntity(2, "Gemini", Protocol.GEMINI, "https://generativelanguage.googleapis.com", "", "gemini-2.5-flash"),
    )
    private var models = TaskModels()
    private val fetched = mutableListOf<Long>()

    private fun show(initial: TaskModels = TaskModels(), listed: Map<Long, ProviderModels> = emptyMap()) {
        models = initial
        rule.setContent {
            var state by mutableStateOf(initial)
            HealthAnalyzerTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TaskModelsSection(
                        providers = providers,
                        taskModels = state,
                        onChange = { task, value ->
                            state = state.with(task, value)
                            models = state
                        },
                        providerModels = listed,
                        onFetchModels = { fetched += it },
                    )
                }
            }
        }
    }

    @Test
    fun listsEveryTask_withDefaults() {
        show()
        for (task in AiTask.entries) {
            rule.onNodeWithTag("task_model_${task.name}").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(task.label).assertIsDisplayed()
        }
    }

    @Test
    fun pickingProvider_showsModelField_andSavesModel() {
        show()
        rule.onNodeWithTag("task_provider_PLAN").performScrollTo().performClick()
        rule.onNodeWithText("Gemini").performClick()
        assertEquals(2L, models[AiTask.PLAN].providerId)
        rule.onNodeWithTag("task_model_field_PLAN").performScrollTo().performTextInput("gemini-2.5-pro")
        rule.waitForIdle()
        assertEquals("gemini-2.5-pro", models[AiTask.PLAN].model)
        // Other tasks stay on their defaults.
        assertEquals(TaskModel(), models[AiTask.CHAT])
    }

    @Test
    fun pickingThinkingLevel_savesIt() {
        show(TaskModels().with(AiTask.BRIEFING, TaskModel(providerId = 1)))
        rule.onNodeWithTag("task_thinking_BRIEFING").performScrollTo().performClick()
        rule.onNodeWithText(ThinkingLevel.LOW.label, substring = true).performClick()
        assertEquals(ThinkingLevel.LOW, models[AiTask.BRIEFING].thinkingLevel)
    }

    @Test
    fun reset_clearsOverrides() {
        show(TaskModels().with(AiTask.CHAT, TaskModel(providerId = 2, model = "x", thinkingLevel = ThinkingLevel.HIGH)))
        rule.onNodeWithTag("task_model_reset_CHAT").performScrollTo().performClick()
        assertNull(models[AiTask.CHAT].providerId)
        assertEquals(TaskModel(), models[AiTask.CHAT])
    }

    @Test
    fun fetchModels_asksForThatTasksProvider() {
        show(TaskModels().with(AiTask.CHAT, TaskModel(providerId = 2)))
        rule.onNodeWithTag("task_fetch_models_CHAT").performScrollTo().performClick()
        assertEquals(listOf(2L), fetched)
    }

    @Test
    fun pickingFetchedModel_savesIt() {
        show(
            TaskModels().with(AiTask.PLAN, TaskModel(providerId = 2)),
            listed = mapOf(2L to ProviderModels(models = listOf("gemini-2.5-flash", "gemini-2.5-pro"))),
        )
        rule.onNodeWithTag("task_model_picker_PLAN").performScrollTo().performClick()
        rule.onNodeWithText("gemini-2.5-pro").performClick()
        assertEquals("gemini-2.5-pro", models[AiTask.PLAN].model)
        rule.onNodeWithTag("task_model_field_PLAN").assertTextContains("gemini-2.5-pro")
    }

    @Test
    fun fetchError_isShown() {
        show(
            TaskModels().with(AiTask.ANALYSIS, TaskModel(providerId = 1)),
            listed = mapOf(1L to ProviderModels(error = "Couldn't list models: 401")),
        )
        rule.onNodeWithText("Couldn't list models: 401").performScrollTo().assertIsDisplayed()
    }
}
