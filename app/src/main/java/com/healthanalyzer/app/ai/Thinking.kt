package com.healthanalyzer.app.ai

import com.google.gson.JsonObject
import kotlin.math.abs

/**
 * Reasoning/thinking effort sent to models with extended thinking capabilities. Declared from
 * least to most effort; which levels a model actually accepts comes from [Thinking.levelsFor].
 * Stored by name, so entries may be added but not renamed.
 */
enum class ThinkingLevel(val label: String, val apiValue: String?, val description: String) {
    DEFAULT("Default", null, "Model decides / unchanged"),
    OFF("Off", "off", "Disable thinking where supported"),
    MINIMAL("Minimal", "minimal", "Near-off thinking, fastest"),
    LOW("Low", "low", "Fast, light reasoning"),
    MEDIUM("Medium", "medium", "Balanced reasoning effort"),
    HIGH("High", "high", "Deep thinking"),
    XHIGH("Extra High", "xhigh", "Extended reasoning for hard problems"),
    MAX("Max", "max", "Maximum effort, slowest and costliest"),
}

private typealias L = ThinkingLevel

/** What to put in an Anthropic Messages request: the `thinking` object and `output_config.effort`. */
data class AnthropicThinking(val thinking: Map<String, Any>? = null, val effort: String? = null)

/**
 * Which thinking levels each model family accepts, and how a level is spelled on the wire.
 * A level the model doesn't accept is clamped to the nearest one it does, so a saved choice
 * never turns into a rejected request after switching models.
 */
object Thinking {
    /** Levels to offer for [model], always starting with [ThinkingLevel.DEFAULT]. */
    fun levelsFor(protocol: Protocol, model: String): List<ThinkingLevel> =
        listOf(L.DEFAULT) + when (protocol) {
            Protocol.OPENAI -> openAiLevels(model)
            // The subscription route has no way to switch reasoning off.
            Protocol.CHATGPT -> openAiLevels(model) - L.OFF
            Protocol.ANTHROPIC -> anthropicLevels(model)
            Protocol.GEMINI -> geminiLevels(model)
        }

    /** [level] if [model] accepts it, otherwise the nearest level it does accept. */
    fun resolve(protocol: Protocol, model: String, level: ThinkingLevel): ThinkingLevel =
        clamp(level, levelsFor(protocol, model))

    internal fun clamp(level: ThinkingLevel, supported: List<ThinkingLevel>): ThinkingLevel {
        if (level in supported || level == L.DEFAULT) return level
        val real = supported.filter { it != L.DEFAULT && it != L.OFF }
        if (real.isEmpty()) return L.DEFAULT
        // A model that can't stop thinking gets its lightest level instead.
        if (level == L.OFF) return real.first()
        // Nearest by rank; on a tie prefer the lower (cheaper) one.
        return real.minBy { abs(it.ordinal - level.ordinal) * 2 + if (it.ordinal > level.ordinal) 1 else 0 }
    }

    // ---- OpenAI-compatible (Chat Completions `reasoning_effort`, Responses `reasoning.effort`) ----

    private val GPT = Regex("""gpt-(\d+)(?:\.(\d+))?""")
    private val O_SERIES = Regex("""^o\d""")
    private val GENERIC = listOf(L.OFF, L.MINIMAL, L.LOW, L.MEDIUM, L.HIGH, L.XHIGH, L.MAX)

    private fun openAiLevels(model: String): List<ThinkingLevel> {
        val m = model.lowercase().substringAfterLast('/')
        val gpt = GPT.find(m)
        return when {
            gpt != null -> {
                val v = gpt.groupValues[1].toInt() * 10 + (gpt.groupValues[2].toIntOrNull() ?: 0)
                when {
                    v < 50 -> emptyList() // GPT-4 and earlier don't reason
                    v == 50 -> if ("codex" in m) listOf(L.LOW, L.MEDIUM, L.HIGH) else listOf(L.MINIMAL, L.LOW, L.MEDIUM, L.HIGH)
                    v == 51 -> listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH) + if ("codex-max" in m) listOf(L.XHIGH) else emptyList()
                    else -> listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH, L.XHIGH)
                }
            }
            O_SERIES.containsMatchIn(m) -> listOf(L.LOW, L.MEDIUM, L.HIGH)
            // Unknown model behind a compatible endpoint (DeepSeek, GLM, Kimi, OpenRouter, local…):
            // offer the whole ladder and send the choice as-is.
            else -> GENERIC
        }
    }

    /** The effort string for [level], or null to leave the request untouched. */
    fun openAiEffort(protocol: Protocol, model: String, level: ThinkingLevel): String? {
        val m = model.lowercase().substringAfterLast('/')
        return when (val r = resolve(protocol, model, level)) {
            L.DEFAULT -> null
            // "none" is only understood by models known to support it; elsewhere Off sends nothing.
            L.OFF -> if (GPT.containsMatchIn(m) || "gemini" in m) "none" else null
            else -> r.apiValue
        }
    }

    // ---- Anthropic ----

    private val CLAUDE = Regex("""claude-(fable|mythos|opus|sonnet|haiku)-(\d{1,2})(?:[-.](\d{1,2}))?(?!\d)""")
    private val CLAUDE_OLD = Regex("""claude-(\d)-(\d)-(opus|sonnet|haiku)""")

    /** Family and version×10 (Opus 4.7 → "opus" to 47), or null when [model] isn't a Claude id. */
    private fun claude(model: String): Pair<String, Int>? {
        val m = model.lowercase()
        CLAUDE.find(m)?.let {
            return it.groupValues[1] to it.groupValues[2].toInt() * 10 + (it.groupValues[3].toIntOrNull() ?: 0)
        }
        CLAUDE_OLD.find(m)?.let {
            return it.groupValues[3] to it.groupValues[1].toInt() * 10 + it.groupValues[2].toInt()
        }
        return null
    }

    private fun isFable(family: String) = family == "fable" || family == "mythos"

    /** Adaptive thinking + effort (4.6 and later) rather than a fixed token budget. */
    private fun usesEffort(family: String, v: Int) = isFable(family) || v >= 46

    private fun anthropicLevels(model: String): List<ThinkingLevel> {
        val (family, v) = claude(model) ?: return listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH)
        return when {
            !usesEffort(family, v) -> listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH)
            // Fable/Mythos and every 5.5-and-later model: effort only, no Off.
            isFable(family) || v >= 55 -> listOf(L.LOW, L.MEDIUM, L.HIGH, L.XHIGH, L.MAX)
            v == 46 -> listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH, L.MAX)
            else -> listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH, L.XHIGH, L.MAX)
        }
    }

    fun anthropic(model: String, level: ThinkingLevel, maxTokens: Int): AnthropicThinking {
        val r = resolve(Protocol.ANTHROPIC, model, level)
        if (r == L.DEFAULT) return AnthropicThinking()
        val c = claude(model)
        if (c != null && usesEffort(c.first, c.second)) {
            if (r == L.OFF) return AnthropicThinking(thinking = mapOf("type" to "disabled"))
            return AnthropicThinking(thinking = mapOf("type" to "adaptive"), effort = r.apiValue)
        }
        // Older Claude models and Anthropic-compatible endpoints: fixed thinking budget.
        val minBudget = 1024
        if (r == L.OFF || maxTokens <= minBudget) return AnthropicThinking()
        val budget = when (r) {
            L.LOW -> 2048
            L.HIGH -> 16384
            else -> 8192
        }.coerceIn(minBudget, maxTokens - 1)
        return AnthropicThinking(thinking = mapOf("type" to "enabled", "budget_tokens" to budget))
    }

    // ---- Gemini ----

    private val GEMINI = Regex("""gemini-(\d+)(?:\.(\d+))?""")

    private fun geminiVersion(model: String): Int? = GEMINI.find(model.lowercase())?.let {
        it.groupValues[1].toInt() * 10 + (it.groupValues[2].toIntOrNull() ?: 0)
    }

    private fun geminiLevels(model: String): List<ThinkingLevel> {
        val m = model.lowercase()
        val v = geminiVersion(m) ?: return listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH)
        val pro = "pro" in m
        return when {
            v < 25 -> emptyList()
            // Gemini 2.5: token budgets. Pro can't switch thinking off.
            v < 30 -> if (pro) listOf(L.LOW, L.MEDIUM, L.HIGH, L.MAX) else listOf(L.OFF, L.LOW, L.MEDIUM, L.HIGH, L.MAX)
            // Gemini 3+: named levels, and thinking can't be switched off.
            v >= 40 -> listOf(L.LOW, L.MEDIUM, L.HIGH) + if ("hydrogen" in m) listOf(L.XHIGH) else emptyList()
            pro -> if (v == 30) listOf(L.LOW, L.HIGH) else listOf(L.LOW, L.MEDIUM, L.HIGH)
            "lite" in m || v < 38 -> listOf(L.MINIMAL, L.LOW, L.MEDIUM, L.HIGH)
            else -> listOf(L.LOW, L.MEDIUM, L.HIGH)
        }
    }

    /** The `generationConfig.thinkingConfig` object, or null to leave it out. */
    fun geminiConfig(model: String, level: ThinkingLevel): JsonObject? {
        val r = resolve(Protocol.GEMINI, model, level)
        if (r == L.DEFAULT) return null
        val v = geminiVersion(model)
        return JsonObject().apply {
            if (v != null && v >= 30) {
                addProperty("thinkingLevel", r.name)
            } else {
                val budget = when (r) {
                    L.OFF -> 0
                    L.LOW -> 1024
                    L.HIGH -> 16384
                    L.MAX -> if ("pro" in model.lowercase()) 32768 else 24576
                    else -> 8192
                }
                addProperty("thinkingBudget", budget)
            }
        }
    }
}
