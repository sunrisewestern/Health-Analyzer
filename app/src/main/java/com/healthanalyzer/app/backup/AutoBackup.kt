package com.healthanalyzer.app.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.healthanalyzer.app.ai.KeyCipher
import com.healthanalyzer.app.ai.LlmClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** How often automatic backups run. [hours] 0 = never. */
enum class AutoBackupInterval(val hours: Long, val label: String) {
    OFF(0, "Off"),
    H6(6, "Every 6 hours"),
    H12(12, "Every 12 hours"),
    DAILY(24, "Daily"),
    D3(72, "Every 3 days"),
    WEEKLY(168, "Weekly");

    companion object {
        /** Unknown or missing names (older or newer app versions) mean off. */
        fun fromName(name: String?): AutoBackupInterval = entries.firstOrNull { it.name == name } ?: OFF
    }
}

enum class AutoBackupDestination(val key: String, val label: String) {
    LOCAL("local", "Folder on this phone"),
    WEBDAV("webdav", "WebDAV");

    companion object {
        fun fromKey(key: String?): AutoBackupDestination? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Names of automatic backup files. They carry an "auto" tag so pruning never touches backups the
 * user made by hand, while still starting with "HealthAnalyzer-" so the restore list shows them.
 */
object AutoBackupFiles {
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val PATTERN = Regex("""HealthAnalyzer-auto-(config|data)-(\d{8}-\d{4})\.(habackup|zip)""")

    data class Parsed(val kind: BackupKind, val stamp: String)

    fun name(kind: BackupKind, encrypted: Boolean, at: LocalDateTime): String =
        "HealthAnalyzer-auto-${kind.fileTag}-${at.format(STAMP)}" + if (encrypted) ".habackup" else ".zip"

    /** Null for anything that isn't an automatic backup (manual backups, other files). */
    fun parse(name: String): Parsed? {
        val m = PATTERN.matchEntire(name) ?: return null
        val kind = BackupKind.entries.first { it.fileTag == m.groupValues[1] }
        return Parsed(kind, m.groupValues[2])
    }

    /**
     * The automatic backups to delete so that only the newest [keep] of each kind remain.
     * Age comes from the timestamp in the name, not from file dates a server may rewrite.
     */
    fun toPrune(names: Collection<String>, keep: Int): List<String> =
        names.distinct()
            .mapNotNull { n -> parse(n)?.let { n to it } }
            .groupBy { it.second.kind }
            .values
            .flatMap { group ->
                group.sortedWith(compareByDescending<Pair<String, Parsed>> { it.second.stamp }.thenByDescending { it.first })
                    .drop(keep.coerceAtLeast(1))
                    .map { it.first }
            }
}

/**
 * Automatic backup settings. Kept out of settings backups on purpose: the folder permission and the
 * last-run results only make sense on this phone. [password] is stored encrypted with [KeyCipher].
 */
data class AutoBackupSettings(
    val interval: AutoBackupInterval = AutoBackupInterval.OFF,
    val localEnabled: Boolean = false,
    val webDavEnabled: Boolean = false,
    /** SAF tree URI with a persisted read/write permission; blank = none chosen. */
    val folderUri: String = "",
    val folderLabel: String = "",
    val includeConfig: Boolean = false,
    val password: String = "",
    val keep: Int = DEFAULT_KEEP,
) {
    /** The password used to encrypt, or null when it is missing or too short to be used. */
    val effectivePassword: String? get() = password.takeIf { it.length >= MIN_PASSWORD }

    /** Settings backups hold API keys, so they are only made when they can be encrypted. */
    val kinds: List<BackupKind>
        get() = listOfNotNull(BackupKind.DATA, BackupKind.CONFIG.takeIf { includeConfig && effectivePassword != null })

    fun enabled(dest: AutoBackupDestination) = when (dest) {
        AutoBackupDestination.LOCAL -> localEnabled
        AutoBackupDestination.WEBDAV -> webDavEnabled
    }

    companion object {
        const val PREFS = "auto_backup"
        const val DEFAULT_KEEP = 10
        const val MIN_PASSWORD = 6
        val KEEP_OPTIONS = listOf(3, 5, 10, 20, 30)

        fun load(context: Context): AutoBackupSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AutoBackupSettings(
                interval = AutoBackupInterval.fromName(p.getString("interval", null)),
                localEnabled = p.getBoolean("local_enabled", false),
                webDavEnabled = p.getBoolean("webdav_enabled", false),
                folderUri = p.getString("folder_uri", "").orEmpty(),
                folderLabel = p.getString("folder_label", "").orEmpty(),
                includeConfig = p.getBoolean("include_config", false),
                password = p.getString("password", null)?.let { runCatching { KeyCipher.decrypt(it) }.getOrNull() }.orEmpty(),
                keep = p.getInt("keep", DEFAULT_KEEP).coerceAtLeast(1),
            )
        }

        fun save(context: Context, s: AutoBackupSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("interval", s.interval.name)
                .putBoolean("local_enabled", s.localEnabled)
                .putBoolean("webdav_enabled", s.webDavEnabled)
                .putString("folder_uri", s.folderUri)
                .putString("folder_label", s.folderLabel)
                .putBoolean("include_config", s.includeConfig)
                .putString("password", KeyCipher.encrypt(s.password))
                .putInt("keep", s.keep)
                .apply()
        }
    }
}

/** Result of the last automatic backup to one destination. */
data class AutoBackupStatus(val atMs: Long, val ok: Boolean, val message: String) {
    companion object {
        fun load(context: Context, dest: AutoBackupDestination): AutoBackupStatus? {
            val p = context.getSharedPreferences(AutoBackupSettings.PREFS, Context.MODE_PRIVATE)
            val at = p.getLong("${dest.key}_last_at", 0L)
            if (at == 0L) return null
            return AutoBackupStatus(at, p.getBoolean("${dest.key}_last_ok", false), p.getString("${dest.key}_last_msg", "").orEmpty())
        }

        fun save(context: Context, dest: AutoBackupDestination, s: AutoBackupStatus) {
            context.getSharedPreferences(AutoBackupSettings.PREFS, Context.MODE_PRIVATE).edit()
                .putLong("${dest.key}_last_at", s.atMs)
                .putBoolean("${dest.key}_last_ok", s.ok)
                .putString("${dest.key}_last_msg", s.message)
                .apply()
        }
    }
}

/**
 * Makes the backup files once with [BackupManager] (same format as manual backups), then hands them
 * to each destination on its own, so a failure on one doesn't stop the other. Old automatic backups
 * beyond [AutoBackupSettings.keep] are deleted after a successful write.
 */
class AutoBackupRunner(context: Context) {

    private val context = context.applicationContext

    suspend fun run(destinations: Set<AutoBackupDestination>): Map<AutoBackupDestination, AutoBackupStatus> = LOCK.withLock {
        withContext(Dispatchers.IO) {
            val settings = AutoBackupSettings.load(context)
            val dir = File(context.cacheDir, "auto_backup").apply { deleteRecursively(); mkdirs() }
            try {
                val files = try {
                    build(settings, dir)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failed = AutoBackupStatus(System.currentTimeMillis(), false, "Couldn't create the backup: ${describe(e)}")
                    return@withContext destinations.associateWith { failed.also { s -> AutoBackupStatus.save(context, it, s) } }
                }
                val what = settings.kinds.joinToString(" + ") { if (it == BackupKind.DATA) "data" else "settings" }
                val size = sizeText(files.sumOf { it.length() })
                destinations.associateWith { dest ->
                    val status = try {
                        when (dest) {
                            AutoBackupDestination.LOCAL -> toFolder(settings, files)
                            AutoBackupDestination.WEBDAV -> toWebDav(settings, files)
                        }
                        AutoBackupStatus(System.currentTimeMillis(), true, "Saved $what ($size)")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AutoBackupStatus(System.currentTimeMillis(), false, describe(e))
                    }
                    AutoBackupStatus.save(context, dest, status)
                    status
                }
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    private suspend fun build(settings: AutoBackupSettings, dir: File): List<File> {
        val manager = BackupManager(context)
        val now = LocalDateTime.now()
        val password = settings.effectivePassword
        return settings.kinds.map { kind ->
            File(dir, AutoBackupFiles.name(kind, password != null, now)).also { manager.create(kind, it, password?.toCharArray()) }
        }
    }

    private fun toFolder(settings: AutoBackupSettings, files: List<File>) {
        if (settings.folderUri.isBlank()) throw IOException("No folder chosen yet")
        val resolver = context.contentResolver
        val tree = Uri.parse(settings.folderUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

        /** File name -> document id of everything directly in the folder. */
        fun children(): Map<String, String> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val found = HashMap<String, String>()
            (resolver.query(uri, columns, null, null, null) ?: throw IOException("Couldn't read the backup folder")).use { c ->
                while (c.moveToNext()) found[c.getString(0) ?: continue] = c.getString(1) ?: continue
            }
            return found
        }
        fun delete(documentId: String) = DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, documentId))

        val existing = children()
        files.forEach { file ->
            // A same-minute rerun would otherwise be saved as "name (1)".
            existing[file.name]?.let { runCatching { delete(it) } }
            val doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", file.name)
                ?: throw IOException("Couldn't create ${file.name} in the folder")
            resolver.openOutputStream(doc, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Couldn't write ${file.name}")
        }
        val after = children()
        AutoBackupFiles.toPrune(after.keys, settings.keep).forEach { name -> runCatching { delete(after.getValue(name)) } }
    }

    private fun toWebDav(settings: AutoBackupSettings, files: List<File>) {
        val dav = WebDavSettings.load(context)
        if (!dav.isSet) throw IOException("WebDAV isn't set up yet")
        val client = WebDavClient(LlmClients.http, dav.url, dav.user, dav.password)
        client.ensureFolder()
        files.forEach { client.upload(it.name, it) }
        // The upload worked; a failed clean-up shouldn't turn that into an error.
        runCatching {
            AutoBackupFiles.toPrune(client.list().map { it.name }, settings.keep).forEach { runCatching { client.delete(it) } }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SecurityException -> "Access to the folder was lost – choose it again"
        is java.net.UnknownHostException -> "Couldn't reach the server (no connection, or wrong address)"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun sizeText(bytes: Long) = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    companion object {
        /** One run at a time, whether it comes from the schedule or the "Back up now" button. */
        private val LOCK = Mutex()
    }
}
