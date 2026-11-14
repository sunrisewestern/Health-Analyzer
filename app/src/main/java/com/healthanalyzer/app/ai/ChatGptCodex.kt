package com.healthanalyzer.app.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.Base64

/**
 * "Sign in with ChatGPT" the way OpenAI's Codex CLI does it (device-code OAuth), so requests use
 * the ChatGPT plan's Codex allowance instead of an API key.
 *
 * Protocol details follow the open-source Codex CLI (openai/codex, codex-rs/login, v0.167.0).
 * This is not an official API for third-party apps: OpenAI can change or block it at any time.
 */
object CodexAuth {
    const val ISSUER = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val VERIFICATION_URL = "$ISSUER/codex/device"
    const val BASE_URL = "https://chatgpt.com/backend-api/codex"
    const val ORIGINATOR = "codex_cli_rs"
    /** Sent to the models endpoint, which filters models by client version. */
    const val CLIENT_VERSION = "0.167.0"

    private val JSON = "application/json".toMediaType()

    data class DeviceCode(val userCode: String, val deviceAuthId: String, val intervalSec: Long)

    /** Stored (encrypted) as the provider's key. */
    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val accountId: String?,
        val expiresAtSec: Long?,
        val email: String?,
        val plan: String?,
    ) {
        fun toJson(): String = JsonObject().apply {
            addProperty("access_token", accessToken)
            addProperty("refresh_token", refreshToken)
            addProperty("id_token", idToken)
            accountId?.let { addProperty("account_id", it) }
            expiresAtSec?.let { addProperty("expires_at", it) }
            email?.let { addProperty("email", it) }
            plan?.let { addProperty("plan", it) }
        }.toString()

        fun expiresSoon(nowSec: Long = System.currentTimeMillis() / 1000) =
            expiresAtSec != null && expiresAtSec - nowSec < 120

        companion object {
            fun fromJson(json: String): Tokens? = runCatching {
                val o = JsonParser.parseString(json).asJsonObject
                Tokens(
                    accessToken = o.get("access_token").asString,
                    refreshToken = o.get("refresh_token")?.asString.orEmpty(),
                    idToken = o.get("id_token")?.asString.orEmpty(),
                    accountId = o.get("account_id")?.asString,
                    expiresAtSec = o.get("expires_at")?.asLong,
                    email = o.get("email")?.asString,
                    plan = o.get("plan")?.asString,
                )
            }.getOrNull()

            /** Builds tokens from a token-endpoint response, reading claims from the JWTs. */
            fun fromTokenResponse(o: JsonObject, previous: Tokens? = null): Tokens {
                val access = o.get("access_token")?.asString ?: throw LlmException("Token response had no access_token")
                val id = o.get("id_token")?.asString ?: previous?.idToken.orEmpty()
                val idClaims = jwtClaims(id)
                val auth = idClaims?.get("https://api.openai.com/auth")?.takeIf { it.isJsonObject }?.asJsonObject
                return Tokens(
                    accessToken = access,
                    refreshToken = o.get("refresh_token")?.asString ?: previous?.refreshToken.orEmpty(),
                    idToken = id,
                    accountId = auth?.get("chatgpt_account_id")?.asString ?: previous?.accountId,
                    expiresAtSec = jwtClaims(access)?.get("exp")?.asLong,
                    email = idClaims?.get("email")?.asString ?: previous?.email,
                    plan = auth?.get("chatgpt_plan_type")?.asString ?: previous?.plan,
                )
            }
        }
    }

    /** Reads a JWT's payload without verifying it (we only need our own claims). */
    fun jwtClaims(jwt: String): JsonObject? = runCatching {
        val part = jwt.split('.')[1]
        val json = String(Base64.getUrlDecoder().decode(part.padEnd((part.length + 3) / 4 * 4, '=')))
        JsonParser.parseString(json).asJsonObject
    }.getOrNull()

    suspend fun requestDeviceCode(http: OkHttpClient): DeviceCode = withContext(Dispatchers.IO) {
        val body = JsonObject().apply { addProperty("client_id", CLIENT_ID) }.toString().toRequestBody(JSON)
        val req = Request.Builder().url("$ISSUER/api/accounts/deviceauth/usercode").post(body).build()
        http.send(req).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 404) throw LlmException("Device-code sign-in isn't available right now (HTTP 404).")
            if (!resp.isSuccessful) throw LlmException("Sign-in request failed: HTTP ${resp.code} ${text.take(200)}")
            val o = JsonParser.parseString(text).asJsonObject
            DeviceCode(
                userCode = (o.get("user_code") ?: o.get("usercode")).asString,
                deviceAuthId = o.get("device_auth_id").asString,
                intervalSec = o.get("interval")?.asString?.trim()?.toLongOrNull()?.coerceIn(2, 30) ?: 5,
            )
        }
    }

    /** Polls until the user approves the code (up to 15 minutes), then exchanges it for tokens. */
    suspend fun awaitTokens(http: OkHttpClient, code: DeviceCode): Tokens = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 15 * 60_000
        val pollBody = JsonObject().apply {
            addProperty("device_auth_id", code.deviceAuthId)
            addProperty("user_code", code.userCode)
        }.toString()
        var grant: JsonObject? = null
        while (grant == null) {
            currentCoroutineContext().ensureActive()
            val req = Request.Builder().url("$ISSUER/api/accounts/deviceauth/token")
                .post(pollBody.toRequestBody(JSON)).build()
            try {
                http.send(req, attempts = 2).use { resp ->
                    when {
                        resp.isSuccessful -> grant = JsonParser.parseString(resp.body!!.string()).asJsonObject
                        resp.code == 403 || resp.code == 404 -> Unit // not approved yet
                        else -> throw LlmException("Sign-in failed: HTTP ${resp.code}")
                    }
                }
            } catch (e: NetworkException) {
                // Keep waiting: the user may be switching apps or networks while approving.
            }
            if (grant == null) {
                if (System.currentTimeMillis() > deadline) throw LlmException("The code expired after 15 minutes.")
                delay(code.intervalSec * 1000)
            }
        }
        val g = grant!!
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", g.get("authorization_code").asString)
            .add("redirect_uri", "$ISSUER/deviceauth/callback")
            .add("code_verifier", g.get("code_verifier").asString)
            .build()
        val req = Request.Builder().url("$ISSUER/oauth/token").post(form).build()
        http.send(req).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException("Token exchange failed: HTTP ${resp.code} ${text.take(200)}")
            Tokens.fromTokenResponse(JsonParser.parseString(text).asJsonObject)
        }
    }

    suspend fun refresh(http: OkHttpClient, tokens: Tokens): Tokens = withContext(Dispatchers.IO) {
        if (tokens.refreshToken.isBlank()) throw LlmException("Signed out – sign in with ChatGPT again.")
        val body = JsonObject().apply {
            addProperty("grant_type", "refresh_token")
            addProperty("client_id", CLIENT_ID)
            addProperty("refresh_token", tokens.refreshToken)
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url("$ISSUER/oauth/token").post(body).build()
        http.send(req).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw LlmException("ChatGPT session expired (HTTP ${resp.code}) – sign in again in Settings.")
            }
            Tokens.fromTokenResponse(JsonParser.parseString(text).asJsonObject, previous = tokens)
        }
    }
}

/**
 * Runs a request, retrying only failures that happen before anything reaches the server
 * (DNS lookup, connection refused/unreachable) - e.g. while a VPN or Wi-Fi reconnects.
 * Those are safe to repeat even for one-time codes.
 */
internal suspend fun OkHttpClient.send(req: Request, attempts: Int = 4): Response {
    var last: IOException? = null
    repeat(attempts) { i ->
        try {
            return newCall(req).execute()
        } catch (e: UnknownHostException) {
            last = e
        } catch (e: ConnectException) {
            last = e
        } catch (e: NoRouteToHostException) {
            last = e
        }
        if (i < attempts - 1) delay(1500L * (i + 1))
    }
    throw NetworkException(
        "Can't reach ${req.url.host} - check your internet connection or VPN, then try again. (${last?.message})",
    )
}

/** Streams from the Codex Responses endpoint with ChatGPT sign-in tokens. */
internal class ChatGptCodexClient(private val http: OkHttpClient) : LlmClient {

    /** Returns valid tokens, refreshing (and reporting the new ones) when needed. */
    private suspend fun tokens(config: LlmConfig, forceRefresh: Boolean = false): CodexAuth.Tokens {
        val current = CodexAuth.Tokens.fromJson(config.apiKey)
            ?: throw LlmException("Not signed in – tap \"Sign in with ChatGPT\" in this provider's settings.")
        if (!forceRefresh && !current.expiresSoon()) return current
        val fresh = CodexAuth.refresh(http, current)
        config.onCredentialsRefreshed?.invoke(fresh.toJson())
        return fresh
    }

    private fun Request.Builder.auth(t: CodexAuth.Tokens) = apply {
        header("Authorization", "Bearer ${t.accessToken}")
        t.accountId?.let { header("chatgpt-account-id", it) }
        header("originator", CodexAuth.ORIGINATOR)
    }

    override fun chat(config: LlmConfig, system: String, turns: List<ChatTurn>): Flow<String> = flow {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("instructions", system.ifBlank { "You are a helpful assistant." })
            add("input", JsonArray().apply {
                turns.forEach { t ->
                    add(JsonObject().apply {
                        addProperty("type", "message")
                        addProperty("role", t.role)
                        add("content", JsonArray().apply {
                            // The Responses API types past assistant text as output_text.
                            add(JsonObject().apply {
                                addProperty("type", if (t.isUser) "input_text" else "output_text")
                                addProperty("text", t.text)
                            })
                        })
                    })
                }
            })
            addProperty("stream", true)
            addProperty("store", false)
            // The Responses API nests effort under "reasoning" (unlike Chat Completions).
            Thinking.openAiEffort(config.protocol, config.model, config.thinkingLevel)
                ?.let { add("reasoning", JsonObject().apply { addProperty("effort", it) }) }
        }.toString()

        var t = tokens(config)
        for (attempt in 0..1) {
            val req = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/responses")
                .auth(t)
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val retry = http.send(req).use { resp ->
                if (resp.code == 401 && attempt == 0) return@use true
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    val msg = runCatching {
                        val o = JsonParser.parseString(text).asJsonObject
                        o.get("detail")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: o.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.get("message")?.asString
                    }.getOrNull() ?: text.take(300)
                    throw LlmException("ChatGPT HTTP ${resp.code}: $msg")
                }
                val source = resp.body!!.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val o = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                    when (o.get("type")?.asString) {
                        "response.output_text.delta" -> o.get("delta")?.takeIf { it.isJsonPrimitive }?.asString?.let { emit(it) }
                        "response.failed", "error" -> {
                            val err = o.getAsJsonObject("response")?.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
                                ?: o.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
                            throw LlmException("ChatGPT: " + (err?.get("message")?.asString ?: o.toString().take(300)))
                        }
                        "response.completed" -> break
                    }
                }
                false
            }
            if (!retry) return@flow
            t = tokens(config, forceRefresh = true)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(config: LlmConfig): List<String> = withContext(Dispatchers.IO) {
        val t = tokens(config)
        val req = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/models?client_version=${CodexAuth.CLIENT_VERSION}")
            .auth(t)
            .build()
        http.send(req).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException("ChatGPT HTTP ${resp.code}: ${text.take(300)}")
            parseModelIds(text)
        }
    }
}
