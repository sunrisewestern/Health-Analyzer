package com.healthanalyzer.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TaskModelsTest {
    private val claude = AiProviderEntity(
        id = 1, name = "Anthropic", protocol = Protocol.ANTHROPIC, baseUrl = "https://api.anthropic.com",
        encryptedKey = "", model = "claude-opus-5-5", thinkingLevel = ThinkingLevel.HIGH,
    )
    private val gemini = AiProviderEntity(
        id = 2, name = "Gemini", protocol = Protocol.GEMINI, baseUrl = "https://generativelanguage.googleapis.com",
        encryptedKey = "", model = "gemini-2.5-flash",
    )
    private val providers = listOf(claude, gemini)

    @Test
    fun noOverrides_usesProviderAsIs() {
        val p = TaskModels().providerFor(AiTask.CHAT, providers)
        assertSame(claude, p)
    }

    @Test
    fun taskProvider_isDefault_andModelAndThinkingApply() {
        val m = TaskModels().with(AiTask.PLAN, TaskModel(providerId = 2, model = "gemini-2.5-pro", thinkingLevel = ThinkingLevel.LOW))
        val p = m.providerFor(AiTask.PLAN, providers)!!
        assertEquals(2L, p.id)
        assertEquals("gemini-2.5-pro", p.model)
        assertEquals(ThinkingLevel.LOW, p.thinkingLevel)
        // Other tasks are untouched.
        assertSame(claude, m.providerFor(AiTask.CHAT, providers))
    }

    @Test
    fun providerPickedOnScreen_wins_andModelOverrideDoesNotLeak() {
        val m = TaskModels().with(AiTask.PLAN, TaskModel(providerId = 2, model = "gemini-2.5-pro"))
        val p = m.providerFor(AiTask.PLAN, providers, chosenId = 1)!!
        assertEquals("claude-opus-5-5", p.model)
    }

    @Test
    fun thinkingOnly_appliesToAnyProvider_whenNoTaskProvider() {
        val m = TaskModels().with(AiTask.BRIEFING, TaskModel(thinkingLevel = ThinkingLevel.LOW))
        assertEquals(ThinkingLevel.LOW, m.providerFor(AiTask.BRIEFING, providers)!!.thinkingLevel)
        assertEquals("claude-opus-5-5", m.providerFor(AiTask.BRIEFING, providers)!!.model)
    }

    @Test
    fun deletedTaskProvider_fallsBackToFirst_withoutOverrides() {
        val m = TaskModels().with(AiTask.ANALYSIS, TaskModel(providerId = 99, model = "x"))
        assertSame(claude, m.providerFor(AiTask.ANALYSIS, providers))
        assertEquals(null, m.liveProviderId(AiTask.ANALYSIS, providers))
    }

    @Test
    fun deletedTaskProvider_keepsThinkingLevel_asDefault() {
        val m = TaskModels().with(AiTask.CHAT, TaskModel(providerId = 99, model = "x", thinkingLevel = ThinkingLevel.LOW))
        val p = m.providerFor(AiTask.CHAT, providers)!!
        assertEquals("claude-opus-5-5", p.model)
        assertEquals(ThinkingLevel.LOW, p.thinkingLevel)
        assertEquals(TaskModel(thinkingLevel = ThinkingLevel.LOW), m.live(providers)[AiTask.CHAT])
    }

    @Test
    fun planDefaultsToFourWeeks() {
        assertEquals(4, DEFAULT_WEEKS)
        assertEquals(4, PlanInfo().weeks)
    }
}
