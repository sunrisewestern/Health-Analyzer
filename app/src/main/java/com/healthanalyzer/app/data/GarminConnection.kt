package com.healthanalyzer.app.data

import android.content.Context
import com.healthanalyzer.app.ai.KeyCipher
import com.healthanalyzer.app.ai.LlmClients
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit

/** Result of a Garmin Connect sync. */
data class GarminSyncResult(
    val listed: Int,
    val downloaded: Int,
    val stored: Int,
    val duplicates: Int,
    val failures: List<String>,
)

/**
 * Garmin Connect Personal API connection: the user's API key (encrypted with the Android
 * Keystore), region, the cached 24-hour access token, and which activities were already fetched.
 */
class GarminConnection(private val context: Context) {

    private companion object {
        const val PARALLEL = 4
    }

    private val prefs get() = context.getSharedPreferences("garmin_connection", Context.MODE_PRIVATE)

    val isConnected: Boolean get() = prefs.getString("key", null) != null
    val china: Boolean get() = prefs.getBoolean("china", false)
    val lastSync: LocalDate? get() = prefs.getString("lastSync", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun api() = GarminPersonalApi(LlmClients.http, china)

    /** Checks the key by getting a token and listing activities; saves it only if that works. */
    suspend fun connect(apiKey: String, china: Boolean): Int {
        val api = GarminPersonalApi(LlmClients.http, china)
        val token = api.token(apiKey)
        val runs = api.listActivities(token, since = LocalDate.now().minusDays(30)).count { it.isRun }
        prefs.edit()
            .putString("key", KeyCipher.encrypt(apiKey.trim()))
            .putBoolean("china", china)
            .putString("token", KeyCipher.encrypt(token.value))
            .putLong("tokenExpires", token.expiresAtMs)
            .apply()
        return runs
    }

    fun disconnect() {
        prefs.edit().clear().apply()
    }

    /** Forgets which activities were downloaded, so the next sync fetches everything again (key is kept). */
    fun resetSyncMarker() {
        prefs.edit().remove("lastSync").remove("fetched").apply()
    }

    /**
     * Makes the "already downloaded" record match the runs actually stored, e.g. after a backup was
     * restored, so the next sync fetches only what's missing instead of the whole history.
     */
    suspend fun rebuildSyncMarker(repository: RunRepository) {
        val ids = repository.garminActivityIds()
        val latest = repository.latestGarminStart()
        prefs.edit().apply {
            putStringSet("fetched", ids)
            if (latest != null) putString("lastSync", java.time.Instant.ofEpochMilli(latest).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString())
            else remove("lastSync")
        }.apply()
    }

    private suspend fun token(): GarminPersonalApi.Token {
        val cached = prefs.getString("token", null)
        val expires = prefs.getLong("tokenExpires", 0)
        if (cached != null && expires - System.currentTimeMillis() > 10 * 60_000) {
            return GarminPersonalApi.Token(KeyCipher.decrypt(cached), expires)
        }
        val key = prefs.getString("key", null)?.let(KeyCipher::decrypt)
            ?: throw GarminApiException("Garmin isn't connected – add your Personal API key in Settings.")
        val fresh = api().token(key)
        prefs.edit().putString("token", KeyCipher.encrypt(fresh.value)).putLong("tokenExpires", fresh.expiresAtMs).apply()
        return fresh
    }

    /**
     * Downloads new running activities as FIT files and stores them through [repository].
     * Incremental: lists activities from 2 days before the last sync (or before the newest Garmin
     * run stored, if there's no sync date) and downloads only those not stored or fetched before,
     * [PARALLEL] at a time. [full] re-lists all history (known activities are still skipped unless [refetch]).
     */
    suspend fun sync(
        repository: RunRepository,
        full: Boolean,
        refetch: Boolean = false,
        progress: (String) -> Unit = {},
    ): GarminSyncResult {
        val api = api()
        var token = token()
        val newestStored = repository.latestGarminStart()
            ?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
        val since = if (full) null else (lastSync ?: newestStored)?.minusDays(2)
        progress("Listing Garmin activities…")
        val runs = try {
            api.listActivities(token, since)
        } catch (e: GarminApiException) {
            // A token can be revoked early (key deleted/rotated): get a new one once.
            prefs.edit().remove("token").apply()
            token = token()
            api.listActivities(token, since)
        }.filter { it.isRun }

        // Known = recorded as fetched, or already in the database (survives restores and lost prefs).
        val done = (prefs.getStringSet("fetched", emptySet())!! + repository.garminActivityIds()).toMutableSet()
        val todo = runs.filter { refetch || it.id.toString() !in done }
        if (todo.isEmpty()) {
            prefs.edit().putStringSet("fetched", done.toSet()).putString("lastSync", LocalDate.now().toString()).apply()
            return GarminSyncResult(runs.size, 0, 0, 0, emptyList())
        }
        var stored = 0
        var duplicates = 0
        var downloaded = 0
        val failures = mutableListOf<String>()

        val lock = Any()
        todo.chunked(12).forEach { batch ->
            // Download a few at a time; each activity is one HTTP request plus FIT parsing.
            val parsed = kotlinx.coroutines.coroutineScope {
                val gate = kotlinx.coroutines.sync.Semaphore(PARALLEL)
                batch.map { a ->
                    async {
                        gate.withPermit {
                            try {
                                val bytes = api.downloadActivityFile(token, a.id)
                                val result = FitImporter.parseFiles(listOf("garmin-${a.id}.zip" to bytes))
                                synchronized(lock) {
                                    downloaded++
                                    failures += result.failures
                                    done += a.id.toString()
                                }
                                progress("Downloaded $downloaded of ${todo.size} new Garmin activities…")
                                result.runs.map { r ->
                                    r.copy(run = r.run.copy(sourceDetail = "Garmin Connect activity ${a.id}" +
                                        (a.name?.let { " · $it" } ?: "")))
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                synchronized(lock) { failures += "activity ${a.id}: ${e.message}" }
                                emptyList()
                            }
                        }
                    }
                }.awaitAll().flatten()
            }
            val (s, d) = repository.importRuns(parsed)
            stored += s
            duplicates += d
            prefs.edit().putStringSet("fetched", done.toSet()).apply()
        }
        prefs.edit().putString("lastSync", LocalDate.now().toString()).apply()
        return GarminSyncResult(runs.size, downloaded, stored, duplicates, failures)
    }
}
