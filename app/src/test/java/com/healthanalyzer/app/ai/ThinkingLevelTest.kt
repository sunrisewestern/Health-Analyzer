package com.healthanalyzer.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias L = ThinkingLevel

class ThinkingLevelTest {
    @Test
    fun storedNamesAndApiValuesAreStable() {
        // Names are persisted in the database and backups.
        assertEquals(
            listOf("DEFAULT", "OFF", "MINIMAL", "LOW", "MEDIUM", "HIGH", "XHIGH", "MAX"),
            ThinkingLevel.entries.map { it.name },
        )
        assertNull(L.DEFAULT.apiValue)
        assertEquals("xhigh", L.XHIGH.apiValue)
        assertEquals("max", L.MAX.apiValue)
    }

    @Test
    fun providerEntityDefaultThinkingLevel() {
        val provider = AiProviderEntity(
            name = "Test Provider",
            protocol = Protocol.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            encryptedKey = "",
            model = "o3-mini",
        )
        assertEquals(L.DEFAULT, provider.thinkingLevel)
    }

    @Test
    fun everyListStartsWithDefault() {
        for (p in Protocol.entries) for (m in listOf("", "gpt-4o", "gemini-2.0-flash", "claude-opus-5-5", "whatever")) {
            assertEquals(L.DEFAULT, Thinking.levelsFor(p, m).first())
        }
    }

    @Test
    fun openAiLevelsPerModel() {
        fun levels(m: String) = Thinking.levelsFor(Protocol.OPENAI, m).drop(1)
        assertEquals(listOf(L.LOW, L.MEDIUM, L.HIGH), levels("o3-mini"))
        assertEquals(listOf(L.MINIMAL, L.LOW, L.MEDIUM, L.HIGH), levels("gpt-5-mini"))
        assertEquals(listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH), levels("gpt-5.1"))
        assertEquals(listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH, L.XHIGH), levels("openai/gpt-5.2"))
        assertTrue(levels("gpt-4o").isEmpty())
        // Unknown models get the whole ladder.
        assertTrue(levels("deepseek-reasoner").containsAll(listOf(L.OFF, L.MINIMAL, L.XHIGH, L.MAX)))
        // The ChatGPT sign-in route can't switch reasoning off.
        assertFalse(L.OFF in Thinking.levelsFor(Protocol.CHATGPT, "gpt-5.2-codex"))
    }

    @Test
    fun openAiEffortValues() {
        fun effort(m: String, l: ThinkingLevel) = Thinking.openAiEffort(Protocol.OPENAI, m, l)
        assertNull(effort("o3-mini", L.DEFAULT))
        assertEquals("high", effort("o3-mini", L.HIGH))
        assertEquals("high", effort("o3-mini", L.MAX)) // clamped
        assertEquals("low", effort("o3-mini", L.OFF)) // can't disable -> lightest
        assertEquals("none", effort("gpt-5.2", L.OFF))
        assertEquals("xhigh", effort("gpt-5.2", L.XHIGH))
        assertEquals("minimal", effort("gpt-5", L.MINIMAL))
        // Unknown model: sent as chosen, Off sends nothing.
        assertEquals("max", effort("deepseek-v4", L.MAX))
        assertNull(effort("deepseek-v4", L.OFF))
        assertNull(effort("gpt-4o", L.HIGH))
    }

    @Test
    fun geminiLevelsPerModel() {
        fun levels(m: String) = Thinking.levelsFor(Protocol.GEMINI, m).drop(1)
        assertEquals(listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH, L.MAX), levels("gemini-2.5-flash"))
        assertEquals(listOf(L.LOW, L.MEDIUM, L.HIGH, L.MAX), levels("gemini-2.5-pro"))
        assertEquals(listOf(L.LOW, L.HIGH), levels("gemini-3-pro-preview"))
        assertEquals(listOf(L.LOW, L.MEDIUM, L.HIGH), levels("gemini-3.1-pro-preview"))
        assertEquals(listOf(L.MINIMAL, L.LOW, L.MEDIUM, L.HIGH), levels("models/gemini-3-flash-preview"))
        assertEquals(listOf(L.MINIMAL, L.LOW, L.MEDIUM, L.HIGH), levels("gemini-3.5-flash-lite"))
        assertEquals(listOf(L.LOW, L.MEDIUM, L.HIGH), levels("gemini-3.8-flash"))
        assertEquals(listOf(L.LOW, L.MEDIUM, L.HIGH), levels("gemini-4-argon"))
        assertEquals(listOf(L.LOW, L.MEDIUM, L.HIGH, L.XHIGH), levels("gemini-4-hydrogen"))
        assertTrue(levels("gemini-2.0-flash").isEmpty())
    }

    @Test
    fun geminiPayloadThinkingConfig() {
        assertNull(Thinking.geminiConfig("gemini-2.5-flash", L.DEFAULT))
        assertEquals(0, Thinking.geminiConfig("gemini-2.5-flash", L.OFF)!!.get("thinkingBudget").asInt)
        assertEquals(1024, Thinking.geminiConfig("gemini-2.5-flash", L.LOW)!!.get("thinkingBudget").asInt)
        assertEquals(16384, Thinking.geminiConfig("gemini-2.5-flash", L.HIGH)!!.get("thinkingBudget").asInt)
        assertEquals(32768, Thinking.geminiConfig("gemini-2.5-pro", L.MAX)!!.get("thinkingBudget").asInt)
        // 2.5 Pro can't disable thinking: Off becomes its lightest budget.
        assertEquals(1024, Thinking.geminiConfig("gemini-2.5-pro", L.OFF)!!.get("thinkingBudget").asInt)

        assertEquals("HIGH", Thinking.geminiConfig("gemini-3.8-flash", L.HIGH)!!.get("thinkingLevel").asString)
        assertEquals("LOW", Thinking.geminiConfig("gemini-3-pro", L.LOW)!!.get("thinkingLevel").asString)
        assertEquals("MINIMAL", Thinking.geminiConfig("gemini-3-flash-preview", L.OFF)!!.get("thinkingLevel").asString)
        assertEquals("XHIGH", Thinking.geminiConfig("gemini-4-hydrogen", L.MAX)!!.get("thinkingLevel").asString)
        assertEquals("HIGH", Thinking.geminiConfig("gemini-4-argon", L.XHIGH)!!.get("thinkingLevel").asString)
        assertFalse(Thinking.geminiConfig("gemini-4-argon", L.HIGH)!!.has("thinkingBudget"))
    }

    @Test
    fun anthropicLevelsPerModel() {
        fun levels(m: String) = Thinking.levelsFor(Protocol.ANTHROPIC, m).drop(1)
        val full = listOf(L.LOW, L.MEDIUM, L.HIGH, L.XHIGH, L.MAX)
        assertEquals(full, levels("claude-opus-5-5"))
        assertEquals(full, levels("claude-fable-5-2"))
        assertEquals(listOf(L.OFF) + full, levels("claude-opus-5"))
        assertEquals(full, levels("claude-sonnet-5-5"))
        assertEquals(full, levels("claude-haiku-5-5"))
        assertEquals(listOf(L.OFF) + full, levels("claude-sonnet-5"))
        assertEquals(listOf(L.OFF) + full, levels("claude-opus-4-7"))
        assertEquals(listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH, L.MAX), levels("claude-sonnet-4-6"))
        val budget = listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH)
        assertEquals(budget, levels("claude-haiku-4-5"))
        assertEquals(budget, levels("claude-sonnet-4-5-20250929"))
        assertEquals(budget, levels("claude-opus-4-20250514"))
        assertEquals(budget, levels("claude-3-7-sonnet-20250219"))
        assertEquals(budget, levels("kimi-k2"))
    }

    @Test
    fun anthropicRequestShape() {
        assertEquals(AnthropicThinking(), Thinking.anthropic("claude-opus-5-5", L.DEFAULT, 16000))
        assertEquals(
            AnthropicThinking(mapOf("type" to "adaptive"), "xhigh"),
            Thinking.anthropic("claude-opus-5-5", L.XHIGH, 16000),
        )
        // Opus 5.5 can't disable thinking: Off becomes low effort.
        assertEquals("low", Thinking.anthropic("claude-opus-5-5", L.OFF, 16000).effort)
        assertEquals(AnthropicThinking(mapOf("type" to "disabled")), Thinking.anthropic("claude-opus-5", L.OFF, 16000))
        assertEquals(
            AnthropicThinking(mapOf("type" to "adaptive"), "low"),
            Thinking.anthropic("claude-sonnet-5-5", L.OFF, 16000),
        )
        // 4.6 has no xhigh.
        assertEquals("high", Thinking.anthropic("claude-sonnet-4-6", L.XHIGH, 16000).effort)
        // Older models keep a fixed budget and never send effort.
        val old = Thinking.anthropic("claude-haiku-4-5", L.HIGH, 16000)
        assertEquals(mapOf("type" to "enabled", "budget_tokens" to 16000 - 1), old.thinking)
        assertNull(old.effort)
        assertEquals(AnthropicThinking(), Thinking.anthropic("claude-haiku-4-5", L.OFF, 16000))
        assertEquals(AnthropicThinking(), Thinking.anthropic("claude-haiku-4-5", L.HIGH, 1024))
    }
}
