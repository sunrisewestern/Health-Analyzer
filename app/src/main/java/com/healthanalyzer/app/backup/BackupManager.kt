package com.healthanalyzer.app.backup

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.healthanalyzer.app.HealthAnalyzerApp
import com.healthanalyzer.app.ai.AiProviderEntity
import com.healthanalyzer.app.ai.AnalysisEntity
import com.healthanalyzer.app.ai.ChatEntity
import com.healthanalyzer.app.ai.ChatMessageEntity
import com.healthanalyzer.app.ai.KeyCipher
import com.healthanalyzer.app.ai.PromptTemplateEntity
import com.healthanalyzer.app.ai.Protocol
import com.healthanalyzer.app.data.RunDetailEntity
import com.healthanalyzer.app.data.RunEntity
import androidx.room.withTransaction
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class BackupKind(val fileTag: String, val label: String) {
    CONFIG("config", "Settings & API keys"),
    DATA("data", "Running data & AI history"),
}

/** What a backup holds, read from its manifest.json. */
data class BackupManifest(
    val kind: BackupKind,
    val createdAt: String,
    val device: String,
    val appVersion: String,
    val counts: Map<String, Int>,
) {
    fun summary(): String = counts.entries.joinToString(", ") { (k, v) -> "$v $k" }
}

/** WebDAV folder settings; the password is stored encrypted with [KeyCipher]. */
data class WebDavSettings(val url: String = "", val user: String = "", val password: String = "") {
    val isSet: Boolean get() = url.startsWith("http")

    companion object {
        const val PREFS = "webdav"
        fun load(context: Context): WebDavSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return WebDavSettings(
                url = p.getString("url", "").orEmpty(),
                user = p.getString("user", "").orEmpty(),
                password = p.getString("password", null)?.let { runCatching { KeyCipher.decrypt(it) }.getOrNull() }.orEmpty(),
            )
        }

        fun save(context: Context, s: WebDavSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("url", s.url.trim())
                .putString("user", s.user.trim())
                .putString("password", KeyCipher.encrypt(s.password))
                .apply()
        }
    }
}

/**
 * Builds and restores backup files. A backup is a zip with `manifest.json` plus:
 *  - config: `config.json` – AI providers with their API keys in plain text (the file is always
 *    password-encrypted for that reason), prompt templates, and the app's preference files
 *    (athlete profile, baseline, Garmin connection incl. its key, WebDAV, AI settings).
 *  - data: `runs.jsonl`, `run_details.jsonl`, `analyses.jsonl`, `chats.jsonl`, `chat_messages.jsonl`.
 * The zip is optionally encrypted with [BackupCrypto] (required for config).
 */
class BackupManager(private val context: Context) {

    private val app = context.applicationContext as HealthAnalyzerApp
    private val runDao get() = app.database.runDao()
    private val aiDao get() = app.aiDatabase.aiDao()
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun fileName(kind: BackupKind, encrypted: Boolean): String =
        "HealthAnalyzer-${kind.fileTag}-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}" +
            if (encrypted) ".habackup" else ".zip"

    // ---- Create -------------------------------------------------------------------------------

    /** Writes a backup to [out]. Returns its manifest. [password] null = unencrypted (data only). */
    suspend fun create(kind: BackupKind, out: File, password: CharArray?, progress: (String) -> Unit = {}): BackupManifest {
        require(kind == BackupKind.DATA || password != null) { "Settings backups need a password – they contain your API keys." }
        val zip = File.createTempFile("backup", ".zip", context.cacheDir)
        try {
            lateinit var manifest: BackupManifest
            ZipOutputStream(zip.outputStream().buffered()).use { z ->
                val counts = when (kind) {
                    BackupKind.CONFIG -> writeConfig(z)
                    BackupKind.DATA -> writeData(z, progress)
                }
                manifest = BackupManifest(
                    kind = kind,
                    createdAt = LocalDateTime.now().withNano(0).toString(),
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty(),
                    counts = counts,
                )
                // Written last because the counts are only known now; ZipFile reads entries in any order.
                z.putNextEntry(ZipEntry(MANIFEST)); z.write(gson.toJson(manifest).toByteArray()); z.closeEntry()
            }
            if (password != null) {
                progress("Encrypting…")
                BackupCrypto.encrypt(zip, out, password)
            } else {
                zip.copyTo(out, overwrite = true)
            }
            return manifest
        } finally {
            zip.delete()
        }
    }

    private suspend fun writeConfig(z: ZipOutputStream): Map<String, Int> {
        val providers = aiDao.allProviders()
        val prompts = aiDao.allPrompts()
        val root = JsonObject().apply {
            add("providers", JsonArray().apply {
                providers.forEach { p ->
                    add(gson.toJsonTree(p).asJsonObject.apply {
                        remove("encryptedKey")
                        addProperty("apiKey", runCatching { KeyCipher.decrypt(p.encryptedKey) }.getOrDefault(""))
                    })
                }
            })
            add("prompts", gson.toJsonTree(prompts))
            add("prefs", JsonObject().apply { PREF_FILES.forEach { name -> add(name, dumpPrefs(name)) } })
        }
        z.putNextEntry(ZipEntry(CONFIG_JSON)); z.write(gson.toJson(root).toByteArray()); z.closeEntry()
        val garmin = context.getSharedPreferences("garmin_connection", Context.MODE_PRIVATE).contains("key")
        val webdav = WebDavSettings.load(context).isSet
        return buildMap {
            put("AI providers", providers.size)
            put("prompts", prompts.size)
            if (garmin) put("Garmin key", 1)
            if (webdav) put("WebDAV login", 1)
        }
    }

    private suspend fun writeData(z: ZipOutputStream, progress: (String) -> Unit): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        val writer = OutputStreamWriter(z, Charsets.UTF_8)
        suspend fun <T> table(entry: String, label: String, pageSize: Int, page: suspend (Int, Int) -> List<T>) {
            z.putNextEntry(ZipEntry(entry))
            var offset = 0
            while (true) {
                val rows = page(pageSize, offset)
                rows.forEach { writer.write(gson.toJson(it)); writer.write("\n") }
                writer.flush()
                offset += rows.size
                progress("Backing up $label… $offset")
                if (rows.size < pageSize) break
            }
            z.closeEntry()
            counts[label] = offset
        }
        table(RUNS, "runs", 500) { l, o -> runDao.runsPage(l, o) }
        table(DETAILS, "run details", 25) { l, o -> runDao.detailsPage(l, o) }
        val analyses = aiDao.allAnalyses()
        table(ANALYSES, "AI analyses", Int.MAX_VALUE) { _, o -> if (o == 0) analyses else emptyList() }
        val chats = aiDao.allChats()
        table(CHATS, "chats", Int.MAX_VALUE) { _, o -> if (o == 0) chats else emptyList() }
        val messages = aiDao.allChatMessages()
        table(MESSAGES, "chat messages", Int.MAX_VALUE) { _, o -> if (o == 0) messages else emptyList() }
        counts.remove("run details")
        return counts
    }

    // ---- Restore ------------------------------------------------------------------------------

    /** Decrypts (if needed) into a plain zip and reads its manifest. Caller deletes [Opened.zip]. */
    fun open(file: File, password: CharArray?): Opened {
        val zip = if (BackupCrypto.isEncrypted(file)) {
            password ?: throw WrongPasswordException()
            File.createTempFile("restore", ".zip", context.cacheDir).also {
                try { BackupCrypto.decrypt(file, it, password) } catch (e: Exception) { it.delete(); throw e }
            }
        } else file
        val manifest = try {
            ZipFile(zip).use { z ->
                val e = z.getEntry(MANIFEST) ?: throw IOException("This isn't a HealthAnalyzer backup (no manifest)")
                gson.fromJson(z.getInputStream(e).reader().readText(), BackupManifest::class.java)
            }
        } catch (e: java.util.zip.ZipException) {
            throw IOException("This isn't a HealthAnalyzer backup file")
        }
        return Opened(zip, manifest, temporary = zip != file)
    }

    data class Opened(val zip: File, val manifest: BackupManifest, val temporary: Boolean) {
        fun cleanup() { if (temporary) zip.delete() }
    }

    /** Replaces the current settings or data with the backup's. */
    suspend fun restore(opened: Opened, progress: (String) -> Unit = {}) {
        ZipFile(opened.zip).use { z ->
            when (opened.manifest.kind) {
                BackupKind.CONFIG -> restoreConfig(z)
                BackupKind.DATA -> restoreData(z, progress)
            }
        }
    }

    private suspend fun restoreConfig(z: ZipFile) {
        val root = z.getInputStream(z.getEntry(CONFIG_JSON) ?: throw IOException("config.json missing"))
            .reader().use { JsonParser.parseReader(it).asJsonObject }
        val providers = root.getAsJsonArray("providers").map { e ->
            val o = e.asJsonObject
            val key = o.get("apiKey")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            o.remove("apiKey")
            o.addProperty("encryptedKey", KeyCipher.encrypt(key)) // re-encrypt with this phone's keystore
            if (runCatching { Protocol.valueOf(o.get("protocol").asString) }.isFailure) o.addProperty("protocol", Protocol.OPENAI.name)
            gson.fromJson(o, AiProviderEntity::class.java)
        }
        val prompts = root.getAsJsonArray("prompts").map { gson.fromJson(it, PromptTemplateEntity::class.java) }
        aiDao.deleteAllProviders(); aiDao.upsertProviders(providers)
        aiDao.deleteAllPrompts(); aiDao.upsertPrompts(prompts)
        root.getAsJsonObject("prefs")?.entrySet()?.forEach { (name, values) ->
            if (name in PREF_FILES) loadPrefs(name, values.asJsonObject)
        }
        // The backup's Garmin record may not match the runs on this phone.
        app.garmin.rebuildSyncMarker(app.repository)
    }

    private suspend fun restoreData(z: ZipFile, progress: (String) -> Unit) {
        // Check every table is present before deleting anything.
        listOf(RUNS, DETAILS, ANALYSES, CHATS, MESSAGES).forEach { if (z.getEntry(it) == null) throw IOException("Backup is missing $it") }
        app.database.withTransaction {
            runDao.deleteAllDetails(); runDao.deleteAllRuns()
            readLines(z, RUNS, 500, RunEntity::class.java) { batch, n -> runDao.upsertRuns(batch); progress("Restoring runs… $n") }
            readLines(z, DETAILS, 25, RunDetailEntity::class.java) { batch, n -> runDao.upsertDetails(batch); progress("Restoring run details… $n") }
        }
        aiDao.deleteAllChatMessages(); aiDao.deleteAllChats(); aiDao.deleteAllAnalyses()
        readLines(z, ANALYSES, 200, AnalysisEntity::class.java) { b, _ -> aiDao.upsertAnalyses(b) }
        readLines(z, CHATS, 200, ChatEntity::class.java) { b, _ -> aiDao.upsertChats(b) }
        readLines(z, MESSAGES, 500, ChatMessageEntity::class.java) { b, _ -> aiDao.upsertChatMessages(b) }
        // Match the Garmin "downloaded" record to the restored runs, so the next sync only fetches what's missing.
        app.garmin.rebuildSyncMarker(app.repository)
    }

    private suspend fun <T> readLines(z: ZipFile, entry: String, batchSize: Int, type: Class<T>, onBatch: suspend (List<T>, Int) -> Unit) {
        BufferedReader(InputStreamReader(z.getInputStream(z.getEntry(entry)), Charsets.UTF_8)).use { r ->
            val batch = ArrayList<T>(batchSize)
            var n = 0
            while (true) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue
                batch += gson.fromJson(line, type)
                n++
                if (batch.size >= batchSize) { onBatch(batch.toList(), n); batch.clear() }
            }
            if (batch.isNotEmpty()) onBatch(batch.toList(), n)
        }
    }

    // ---- SharedPreferences <-> JSON (secrets in plain text, re-encrypted on restore) ----------

    private fun prefs(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun dumpPrefs(name: String): JsonObject = JsonObject().apply {
        prefs(name).all.forEach { (key, value) ->
            val secret = key in SECRETS[name].orEmpty()
            add(key, JsonObject().apply {
                when (value) {
                    is String -> {
                        addProperty("t", "s")
                        addProperty("v", if (secret) runCatching { KeyCipher.decrypt(value) }.getOrDefault("") else value)
                    }
                    is Int -> { addProperty("t", "i"); addProperty("v", value) }
                    is Long -> { addProperty("t", "l"); addProperty("v", value) }
                    is Float -> { addProperty("t", "f"); addProperty("v", value) }
                    is Boolean -> { addProperty("t", "b"); addProperty("v", value) }
                    is Set<*> -> { addProperty("t", "set"); add("v", JsonArray().apply { value.forEach { add(it.toString()) } }) }
                    else -> return@forEach
                }
            })
        }
    }

    private fun loadPrefs(name: String, values: JsonObject) {
        val edit = prefs(name).edit().clear()
        values.entrySet().forEach { (key, e) ->
            val o = e.asJsonObject
            val v = o.get("v")
            when (o.get("t").asString) {
                "s" -> edit.putString(key, if (key in SECRETS[name].orEmpty()) KeyCipher.encrypt(v.asString) else v.asString)
                "i" -> edit.putInt(key, v.asInt)
                "l" -> edit.putLong(key, v.asLong)
                "f" -> edit.putFloat(key, v.asFloat)
                "b" -> edit.putBoolean(key, v.asBoolean)
                "set" -> edit.putStringSet(key, v.asJsonArray.map { it.asString }.toSet())
            }
        }
        edit.commit()
    }

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val CONFIG_JSON = "config.json"
        private const val RUNS = "runs.jsonl"
        private const val DETAILS = "run_details.jsonl"
        private const val ANALYSES = "analyses.jsonl"
        private const val CHATS = "chats.jsonl"
        private const val MESSAGES = "chat_messages.jsonl"

        /** Preference files that make up "settings". */
        val PREF_FILES = listOf("athlete_profile", "athlete_baseline", "ai_settings", "garmin_connection", WebDavSettings.PREFS)

        /** Values stored with [KeyCipher]; exported decrypted, re-encrypted on restore. */
        private val SECRETS = mapOf(
            "garmin_connection" to setOf("key", "token"),
            WebDavSettings.PREFS to setOf("password"),
        )
    }
}

