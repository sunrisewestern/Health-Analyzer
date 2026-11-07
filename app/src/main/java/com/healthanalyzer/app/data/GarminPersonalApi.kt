package com.healthanalyzer.app.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate

/** One activity from the Garmin activity list. */
data class GarminActivity(
    val id: Long,
    val name: String?,
    val typeKey: String,
    val startEpochMs: Long?,
    val distanceM: Double?,
) {
    /** running, treadmill_running, trail_running, track_running, indoor_running, virtual_run… */
    val isRun: Boolean get() = typeKey.contains("run")
}

class GarminApiException(message: String) : IOException(message)

/**
 * Garmin Connect **Personal API** (your own data, pulled with a self-generated key).
 *
 * From Garmin's Personal API guide, FAQ and OpenAPI spec (developer.garmin.com/gc-developer-program/personal):
 *  - Key: created at connect.garmin.com/app/settings/personalApiToken; shown once; expires after 30–365 days.
 *  - Token: POST /personalapitoken-service/personal-api/personal-api-key/token with
 *    `Authorization: ApiKey <key>`; access tokens last 24 h; no refresh tokens (just get a new one).
 *  - Data: GET /activitylist-service/activities/search/activities, GET /download-service/files/activity/{id} (zip).
 */
class GarminPersonalApi(
    private val http: OkHttpClient,
    china: Boolean = false,
) {
    val baseUrl = if (china) CHINA_URL else GLOBAL_URL

    data class Token(val value: String, val expiresAtMs: Long)

    suspend fun token(apiKey: String): Token = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/personalapitoken-service/personal-api/personal-api-key/token")
            .header("Authorization", "ApiKey ${apiKey.trim()}")
            // Garmin answers 406 to "Accept: application/json" here; its own example sends no Accept.
            .header("Accept", "*/*")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GarminApiException(
                    when (resp.code) {
                        401, 403 -> "Garmin rejected the API key (HTTP ${resp.code}). Check it hasn't expired or been deleted, " +
                            "and that the China/global setting matches your account."
                        else -> "Garmin token request failed: HTTP ${resp.code} ${body.take(200)}"
                    },
                )
            }
            parseToken(body, System.currentTimeMillis())
        }
    }

    /** Lists activities that started on or after [since] (local date), oldest first, across all pages. */
    suspend fun listActivities(token: Token, since: LocalDate?, pageSize: Int = 100): List<GarminActivity> =
        withContext(Dispatchers.IO) {
            val all = mutableListOf<GarminActivity>()
            var start = 0
            while (true) {
                val url = "$baseUrl/activitylist-service/activities/search/activities".toHttpUrl().newBuilder()
                    .addQueryParameter("start", start.toString())
                    .addQueryParameter("limit", pageSize.toString())
                    .addQueryParameter("sortOrder", "asc")
                    .apply { since?.let { addQueryParameter("startDate", it.toString()) } }
                    .build()
                val page = getJson(token, url.toString())
                val items = parseActivities(page)
                all += items
                if (items.size < pageSize) break
                start += pageSize
            }
            all
        }

    /** Downloads the activity's original file: a zip containing the .fit file. */
    suspend fun downloadActivityFile(token: Token, activityId: Long): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/download-service/files/activity/$activityId")
            .header("Authorization", "Bearer ${token.value}")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw GarminApiException("Download of activity $activityId failed: HTTP ${resp.code}")
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun getJson(token: Token, url: String): JsonElement {
        for (accept in listOf("application/json", "*/*")) {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${token.value}")
                .header("Accept", accept)
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 406 && accept != "*/*") return@use // try again accepting anything
                if (!resp.isSuccessful) throw GarminApiException("Garmin request failed: HTTP ${resp.code} ${body.take(200)}")
                return JsonParser.parseString(body)
            }
        }
        throw GarminApiException("Garmin request failed: HTTP 406")
    }

    companion object {
        const val GLOBAL_URL = "https://personal.connectapi.garmin.com"
        const val CHINA_URL = "https://personal.connectapi.garmin.cn"
        const val KEY_PAGE = "https://connect.garmin.com/app/settings/personalApiToken"
        const val KEY_PAGE_CN = "https://connect.garmin.cn/app/settings/personalApiToken"
        private const val DEFAULT_TOKEN_LIFETIME_MS = 24 * 3600_000L

        /**
         * Garmin's docs don't show the token response body, so accept the common shapes:
         * {"access_token": …, "expires_in": s}, {"accessToken": …}, {"token": …}, or a bare token string.
         */
        internal fun parseToken(body: String, nowMs: Long): Token {
            val trimmed = body.trim()
            val json = runCatching { JsonParser.parseString(trimmed) }.getOrNull()
            fun JsonObject.str(vararg names: String) =
                names.firstNotNullOfOrNull { n -> get(n)?.takeIf { it.isJsonPrimitive }?.asString }
            fun JsonObject.long(vararg names: String) =
                names.firstNotNullOfOrNull { n -> get(n)?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull() }
            return when {
                json != null && json.isJsonObject -> {
                    val o = json.asJsonObject
                    val inner = o.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: o
                    val value = inner.str("access_token", "accessToken", "token", "accessTokenValue")
                        ?: throw GarminApiException("Unexpected token response from Garmin: ${trimmed.take(200)}")
                    val expiresIn = inner.long("expires_in", "expiresIn")
                    val expiresAt = inner.long("expires_at", "expiresAt", "expirationTimestamp")
                    Token(
                        value,
                        when {
                            expiresAt != null -> if (expiresAt < 10_000_000_000L) expiresAt * 1000 else expiresAt
                            expiresIn != null -> nowMs + expiresIn * 1000
                            else -> nowMs + DEFAULT_TOKEN_LIFETIME_MS
                        },
                    )
                }
                json != null && json.isJsonPrimitive -> Token(json.asString, nowMs + DEFAULT_TOKEN_LIFETIME_MS)
                trimmed.isNotEmpty() && trimmed.none { it.isWhitespace() } -> Token(trimmed, nowMs + DEFAULT_TOKEN_LIFETIME_MS)
                else -> throw GarminApiException("Empty token response from Garmin")
            }
        }

        internal fun parseActivities(page: JsonElement): List<GarminActivity> {
            val list = when {
                page.isJsonArray -> page.asJsonArray
                page.isJsonObject -> page.asJsonObject.get("activityList")?.takeIf { it.isJsonArray }?.asJsonArray
                else -> null
            } ?: return emptyList()
            return list.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = o.get("activityId")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@mapNotNull null
                val type = o.get("activityType")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("typeKey")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                GarminActivity(
                    id = id,
                    name = o.get("activityName")?.takeIf { it.isJsonPrimitive }?.asString,
                    typeKey = type,
                    startEpochMs = o.get("beginTimestamp")?.takeIf { it.isJsonPrimitive }?.asLong,
                    distanceM = o.get("distance")?.takeIf { it.isJsonPrimitive }?.asDouble,
                )
            }
        }
    }
}
