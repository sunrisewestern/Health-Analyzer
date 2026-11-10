package com.healthanalyzer.app.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Resolved, decrypted settings for one request. */
data class LlmConfig(
    val protocol: Protocol,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val maxTokens: Int,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.DEFAULT,
    /** Called with new credentials when a client refreshes them (ChatGPT sign-in). */
    val onCredentialsRefreshed: (suspend (String) -> Unit)? = null,
)

open class LlmException(message: String) : Exception(message)

/** The server could not be reached at all (DNS, no route, connection refused). */
class NetworkException(message: String) : LlmException(message)

/** One message of a conversation. [role] is "user" or "assistant". */
data class ChatTurn(val role: String, val text: String) {
    val isUser: Boolean get() = role == USER
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/** One chat backend. [chat] emits text deltas as they arrive. */
interface LlmClient {
    /** Multi-turn: [turns] alternate user/assistant and end with a user turn. */
    fun chat(config: LlmConfig, system: String, turns: List<ChatTurn>): Flow<String>

    /** Single question (the Analyze tab). */
    fun stream(config: LlmConfig, system: String, user: String): Flow<String> =
        chat(config, system, listOf(ChatTurn(ChatTurn.USER, user)))

    suspend fun listModels(config: LlmConfig): List<String>
}

object LlmClients {
    val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES) // reasoning models can think for a long time
        // HTTP/2 pings keep the socket alive through NATs/carrier proxies while a model thinks
        // silently for minutes, and detect a dead connection instead of waiting out the read timeout.
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun forProtocol(p: Protocol): LlmClient = ResilientClient(
        when (p) {
            Protocol.OPENAI -> OpenAiCompatibleClient(http)
            Protocol.ANTHROPIC -> AnthropicClient
            Protocol.GEMINI -> GeminiClient(http)
            Protocol.CHATGPT -> ChatGptCodexClient(http)
        },
    )
}

/**
 * True for socket-level failures worth retrying: "Software caused connection abort",
 * "Connection reset", a broken pipe, an HTTP/2 stream reset… Timeouts are left out
 * (each one already waited minutes), and so are our own [LlmException]s (HTTP errors).
 */
internal fun Throwable.isTransientNetworkError(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is IOException && it !is InterruptedIOException }

/**
 * Wraps a client so a dropped connection is retried while nothing has been streamed yet
 * (a stale pooled connection after the phone slept, a Wi-Fi ↔ mobile switch), and so a
 * drop mid-answer surfaces as a readable [NetworkException] instead of a raw socket error.
 */
private class ResilientClient(private val base: LlmClient) : LlmClient {
    override fun chat(config: LlmConfig, system: String, turns: List<ChatTurn>): Flow<String> =
        base.chat(config, system, turns).retryBeforeFirstToken()

    override fun stream(config: LlmConfig, system: String, user: String): Flow<String> =
        base.stream(config, system, user).retryBeforeFirstToken()

    override suspend fun listModels(config: LlmConfig): List<String> = base.listModels(config)
}

internal fun Flow<String>.retryBeforeFirstToken(retries: Int = 2, backoffMs: Long = 1500): Flow<String> = flow {
    var attempt = 0
    while (true) {
        var emitted = false
        try {
            collect { emitted = true; emit(it) }
            return@flow
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!e.isTransientNetworkError()) throw e
            if (emitted || attempt >= retries) throw NetworkException(connectionDroppedMessage(e, emitted))
            attempt++
            // Dead sockets may still sit in the pool; make the retry open a fresh one.
            runCatching { LlmClients.http.connectionPool.evictAll() }
            delay(backoffMs * attempt)
        }
    }
}

private fun connectionDroppedMessage(e: Throwable, partial: Boolean): String {
    val root = generateSequence(e) { it.cause }.last()
    val what = if (partial) "The connection dropped before the answer finished" else "The connection to the AI provider dropped"
    return "$what (${root.message ?: root.javaClass.simpleName}). This happens when the network changes " +
        "(Wi-Fi ↔ mobile data, VPN reconnecting) or a battery saver cuts off the app. Tap Retry."
}

private val JSON = "application/json".toMediaType()

// Null-tolerant JSON access. Relays such as new-api / one-api send explicit nulls
// ("data": null, "error": null, "choices": null), which Gson's getAsJsonX() would crash on.
private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.arr(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
private fun JsonElement.objOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

/** Error text from a JSON body: {"error":{"message"}}, {"error":"…"}, or {"message":"…"}. */
private fun JsonObject.errorMessage(): String? =
    obj("error")?.let { it.str("message") ?: it.toString() } ?: str("error") ?: str("message")

private fun parseJson(text: String): JsonElement? = runCatching { JsonParser.parseString(text) }.getOrNull()

private fun Response.failure(): LlmException {
    val body = runCatching { body?.string() }.getOrNull().orEmpty()
    val detail = parseJson(body)?.objOrNull()?.errorMessage() ?: body
    return LlmException("HTTP $code: ${detail.take(500)}")
}

/** Reads a server-sent-events body and hands each `data:` payload to [onData]. */
private suspend fun Response.forEachSseData(onData: suspend (String) -> Boolean) {
    val source = body?.source() ?: return
    while (true) {
        currentCoroutineContext().ensureActive()
        val line = source.readUtf8Line() ?: break
        if (!line.startsWith("data:")) continue
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) continue
        if (!onData(data)) break
    }
}

private fun String.trimSlash() = trimEnd('/')

/** OpenAI Chat Completions; also DeepSeek, Qwen, Moonshot, OpenRouter, Ollama, LM Studio, vLLM… */
private class OpenAiCompatibleClient(private val http: OkHttpClient) : LlmClient {

    override fun chat(config: LlmConfig, system: String, turns: List<ChatTurn>): Flow<String> = flow {
        val messages = JsonArray().apply {
            if (system.isNotBlank()) add(JsonObject().apply { addProperty("role", "system"); addProperty("content", system) })
            turns.forEach { t -> add(JsonObject().apply { addProperty("role", t.role); addProperty("content", t.text) }) }
        }
        val body = JsonObject().apply {
            addProperty("model", config.model)
            add("messages", messages)
            addProperty("stream", true)
            if (config.thinkingLevel != ThinkingLevel.DEFAULT && config.thinkingLevel != ThinkingLevel.OFF) {
                config.thinkingLevel.apiValue?.let { addProperty("reasoning_effort", it) }
            }
        }
        val request = Request.Builder()
            .url(config.baseUrl.trimSlash() + "/chat/completions")
            .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.failure()
            resp.forEachSseData { data ->
                if (data == "[DONE]") return@forEachSseData false
                val obj = parseJson(data)?.objOrNull() ?: return@forEachSseData true
                if (obj.obj("error") != null || obj.str("error") != null) {
                    throw LlmException(obj.errorMessage() ?: obj.toString())
                }
                val delta = obj.arr("choices")?.firstOrNull()?.objOrNull()?.obj("delta")
                val text = delta?.str("content")
                if (!text.isNullOrEmpty()) emit(text)
                true
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(config: LlmConfig): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.baseUrl.trimSlash() + "/models")
            .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.failure()
            parseModelIds(resp.body?.string().orEmpty())
        }
    }
}

/**
 * Accepts the shapes OpenAI-compatible servers return for GET /models:
 * {"data":[{"id":…}]}, {"models":[…]}, a bare array, or entries that are plain strings.
 * new-api returns {"success":true,"data":null} when the token can use no models.
 */
internal fun parseModelIds(body: String): List<String> {
    val root = parseJson(body)
        ?: throw LlmException("The server didn't return JSON. Check that the base URL ends with /v1.")
    val list: JsonArray? = when {
        root.isJsonArray -> root.asJsonArray
        root.isJsonObject -> root.asJsonObject.let { it.arr("data") ?: it.arr("models") }
        else -> null
    }
    if (list == null) {
        val o = root.objOrNull()
        o?.errorMessage()?.let { throw LlmException(it) }
        throw LlmException(
            "The server returned no model list (data is null). On new-api/one-api this means the token " +
                "has no usable models: check the token's model limits and group, or enable models " +
                "without a price ratio. You can still type the model name by hand.",
        )
    }
    return list.mapNotNull { e ->
        when {
            e.isJsonPrimitive -> e.asString
            e.isJsonObject -> e.asJsonObject.let { it.str("id") ?: it.str("slug") ?: it.str("name") }
            else -> null
        }
    }.distinct().sorted()
}

/** Google Gemini generateContent API. */
private class GeminiClient(private val http: OkHttpClient) : LlmClient {

    override fun chat(config: LlmConfig, system: String, turns: List<ChatTurn>): Flow<String> = flow {
        val body = JsonObject().apply {
            if (system.isNotBlank()) {
                add("systemInstruction", JsonObject().apply { add("parts", textParts(system)) })
            }
            add("contents", JsonArray().apply {
                turns.forEach { t ->
                    add(JsonObject().apply {
                        addProperty("role", if (t.isUser) "user" else "model")
                        add("parts", textParts(t.text))
                    })
                }
            })
            val genConfig = JsonObject().apply {
                addProperty("maxOutputTokens", config.maxTokens)
                when (config.thinkingLevel) {
                    ThinkingLevel.DEFAULT -> {}
                    ThinkingLevel.OFF -> {
                        add("thinkingConfig", JsonObject().apply {
                            addProperty("thinkingBudget", 0)
                        })
                    }
                    ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH -> {
                        val isGemini3 = config.model.contains("gemini-3") || config.model.contains("gemini-3.")
                        add("thinkingConfig", JsonObject().apply {
                            if (isGemini3) {
                                addProperty("thinkingLevel", config.thinkingLevel.name)
                            } else {
                                val budget = when (config.thinkingLevel) {
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
            add("generationConfig", genConfig)
        }
        val model = config.model.removePrefix("models/")
        val request = Request.Builder()
            .url(config.baseUrl.trimSlash() + "/v1beta/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", config.apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.failure()
            resp.forEachSseData { data ->
                val obj = parseJson(data)?.objOrNull() ?: return@forEachSseData true
                obj.obj("error")?.let { throw LlmException(obj.errorMessage() ?: it.toString()) }
                obj.arr("candidates")?.firstOrNull()?.objOrNull()
                    ?.obj("content")?.arr("parts")
                    ?.forEach { part ->
                        val p = part.objOrNull() ?: return@forEach
                        if (p.str("thought") == "true") return@forEach
                        p.str("text")?.let { emit(it) }
                    }
                true
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(config: LlmConfig): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.baseUrl.trimSlash() + "/v1beta/models?pageSize=1000")
            .header("x-goog-api-key", config.apiKey)
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.failure()
            val obj = parseJson(resp.body?.string().orEmpty())?.objOrNull()
                ?: throw LlmException("The server didn't return JSON. Check the base URL.")
            val models = obj.arr("models")
                ?: throw LlmException(obj.errorMessage() ?: "The server returned no model list.")
            models.mapNotNull { m ->
                val o = m.objOrNull() ?: return@mapNotNull null
                // Relays (e.g. new-api) omit supportedGenerationMethods; keep those entries.
                val methods = o.arr("supportedGenerationMethods")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                o.str("name")?.removePrefix("models/")?.takeIf { methods == null || "generateContent" in methods }
            }.distinct().sorted()
        }
    }

    private fun textParts(text: String) = JsonArray().apply { add(JsonObject().apply { addProperty("text", text) }) }
}

/** Claude through the official Anthropic Java SDK. Also works with Anthropic-compatible base URLs. */
private object AnthropicClient : LlmClient {

    private fun client(config: LlmConfig) = AnthropicOkHttpClient.builder()
        .apiKey(config.apiKey)
        .baseUrl(config.baseUrl.trimSlash())
        .timeout(Duration.ofMinutes(10))
        .build()

    /** First-party Claude models that should fall back server-side when a request is refused. */
    private fun wantsFallback(config: LlmConfig): Boolean =
        config.baseUrl.contains("api.anthropic.com") &&
            listOf("claude-opus-5", "claude-fable-5", "claude-sonnet-5-5").any { config.model.startsWith(it) }

    override fun chat(config: LlmConfig, system: String, turns: List<ChatTurn>): Flow<String> = flow {
        val client = client(config)
        val params = MessageCreateParams.builder()
            .model(config.model)
            .maxTokens(config.maxTokens.toLong())
            .apply { if (system.isNotBlank()) system(system) }
            .apply { turns.forEach { t -> if (t.isUser) addUserMessage(t.text) else addAssistantMessage(t.text) } }
            .apply {
                if (wantsFallback(config)) {
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
                when (config.thinkingLevel) {
                    ThinkingLevel.DEFAULT, ThinkingLevel.OFF -> {}
                    ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH -> {
                        val minRequired = 1024
                        if (config.maxTokens > minRequired) {
                            val budget = when (config.thinkingLevel) {
                                ThinkingLevel.LOW -> 2048
                                ThinkingLevel.MEDIUM -> 8192
                                ThinkingLevel.HIGH -> 16384
                                else -> 8192
                            }.coerceIn(minRequired, config.maxTokens - 1)
                            putAdditionalBodyProperty(
                                "thinking",
                                JsonValue.from(mapOf("type" to "enabled", "budget_tokens" to budget)),
                            )
                        }
                    }
                }
            }
            .build()
        try {
            client.messages().createStreaming(params).use { response ->
                var stopReason: String? = null
                for (event in response.stream().iterator()) {
                    currentCoroutineContext().ensureActive()
                    val text = event.contentBlockDelta().flatMap { it.delta().text() }.map { it.text() }.orElse(null)
                    if (!text.isNullOrEmpty()) emit(text)
                    event.messageDelta().flatMap { it.delta().stopReason() }.ifPresent { stopReason = it.toString() }
                }
                if (stopReason.equals("refusal", ignoreCase = true)) {
                    emit("\n\n[The model declined to answer this request.]")
                } else if (stopReason.equals("max_tokens", ignoreCase = true)) {
                    emit("\n\n[Output stopped at the max-tokens limit set for this provider.]")
                }
            }
        } catch (e: AnthropicServiceException) {
            throw LlmException("Anthropic ${e.statusCode()}: ${e.message}")
        } finally {
            client.close()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(config: LlmConfig): List<String> = withContext(Dispatchers.IO) {
        val client = client(config)
        try {
            client.models().list().autoPager().map { it.id() }.sorted()
        } catch (e: AnthropicServiceException) {
            throw LlmException("Anthropic ${e.statusCode()}: ${e.message}")
        } finally {
            client.close()
        }
    }
}
