package com.healthanalyzer.app.ai

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingLevelTest {

    @Test
    fun thinkingLevelEnumValues() {
        assertEquals("Default", ThinkingLevel.DEFAULT.label)
        assertNull(ThinkingLevel.DEFAULT.apiValue)

        assertEquals("Off", ThinkingLevel.OFF.label)
        assertEquals("off", ThinkingLevel.OFF.apiValue)

        assertEquals("Low", ThinkingLevel.LOW.label)
        assertEquals("low", ThinkingLevel.LOW.apiValue)

        assertEquals("Medium", ThinkingLevel.MEDIUM.label)
        assertEquals("medium", ThinkingLevel.MEDIUM.apiValue)

        assertEquals("High", ThinkingLevel.HIGH.label)
        assertEquals("high", ThinkingLevel.HIGH.apiValue)
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
        assertEquals(ThinkingLevel.DEFAULT, provider.thinkingLevel)

        val updated = provider.copy(thinkingLevel = ThinkingLevel.HIGH)
        assertEquals(ThinkingLevel.HIGH, updated.thinkingLevel)
    }

    @Test
    fun openAiPayloadReasoningEffort() {
        fun buildPayload(level: ThinkingLevel): JsonObject {
            val body = JsonObject().apply {
                addProperty("model", "o3-mini")
                addProperty("stream", true)
                if (level != ThinkingLevel.DEFAULT && level != ThinkingLevel.OFF) {
                    level.apiValue?.let { addProperty("reasoning_effort", it) }
                }
            }
            return body
        }

        // DEFAULT and OFF should NOT include reasoning_effort (avoids 400 on standard models)
        assertFalse(buildPayload(ThinkingLevel.DEFAULT).has("reasoning_effort"))
        assertFalse(buildPayload(ThinkingLevel.OFF).has("reasoning_effort"))

        // LOW, MEDIUM, HIGH should set reasoning_effort
        assertEquals("low", buildPayload(ThinkingLevel.LOW).get("reasoning_effort").asString)
        assertEquals("medium", buildPayload(ThinkingLevel.MEDIUM).get("reasoning_effort").asString)
        assertEquals("high", buildPayload(ThinkingLevel.HIGH).get("reasoning_effort").asString)
    }

    @Test
    fun geminiPayloadThinkingConfig() {
        fun buildGeminiGenConfig(model: String, level: ThinkingLevel): JsonObject {
            return JsonObject().apply {
                addProperty("maxOutputTokens", 16000)
                when (level) {
                    ThinkingLevel.DEFAULT -> {}
                    ThinkingLevel.OFF -> {
                        add("thinkingConfig", JsonObject().apply {
                            addProperty("thinkingBudget", 0)
                        })
                    }
                    ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH -> {
                        val isGemini3 = model.contains("gemini-3") || model.contains("gemini-3.")
                        add("thinkingConfig", JsonObject().apply {
                            if (isGemini3) {
                                addProperty("thinkingLevel", level.name)
                            } else {
                                val budget = when (level) {
                                    ThinkingLevel.LOW -> 1024
                                    ThinkingLevel.MEDIUM -> 8192
                                    ThinkingLevel.HIGH -> 16384
                                    else -> 8192
                                }
                                addProperty("thinkingBudget", budget)
                            }
                        })
                    }
                }
            }
        }

        // Default should not contain thinkingConfig
        assertFalse(buildGeminiGenConfig("gemini-2.5-flash", ThinkingLevel.DEFAULT).has("thinkingConfig"))

        // OFF sets thinkingBudget to 0
        val offConfig = buildGeminiGenConfig("gemini-2.5-flash", ThinkingLevel.OFF).getAsJsonObject("thinkingConfig")
        assertEquals(0, offConfig.get("thinkingBudget").asInt)

        // Gemini 2.5 sets numeric thinkingBudget
        val g25Low = buildGeminiGenConfig("gemini-2.5-flash", ThinkingLevel.LOW).getAsJsonObject("thinkingConfig")
        assertEquals(1024, g25Low.get("thinkingBudget").asInt)

        val g25High = buildGeminiGenConfig("gemini-2.5-flash", ThinkingLevel.HIGH).getAsJsonObject("thinkingConfig")
        assertEquals(16384, g25High.get("thinkingBudget").asInt)

        // Gemini 3 sets string thinkingLevel (e.g. HIGH)
        val g3High = buildGeminiGenConfig("gemini-3.8-flash", ThinkingLevel.HIGH).getAsJsonObject("thinkingConfig")
        assertEquals("HIGH", g3High.get("thinkingLevel").asString)

        val g3Low = buildGeminiGenConfig("gemini-3-pro", ThinkingLevel.LOW).getAsJsonObject("thinkingConfig")
        assertEquals("LOW", g3Low.get("thinkingLevel").asString)
    }
}
