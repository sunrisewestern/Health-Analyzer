package com.healthanalyzer.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healthanalyzer.app.ai.LlmClients
import com.healthanalyzer.app.backup.BackupKind
import com.healthanalyzer.app.backup.BackupManager
import com.healthanalyzer.app.backup.RemoteFile
import com.healthanalyzer.app.backup.WebDavClient
import com.healthanalyzer.app.backup.WebDavSettings
import com.healthanalyzer.app.backup.WrongPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A backup file picked for restoring, before and after it is unlocked. */
data class PendingRestore(
    val file: File,
    val sourceName: String,
    val opened: BackupManager.Opened? = null,
    val error: String? = null,
)

data class BackupState(
    val busy: Boolean = false,
    val progress: String? = null,
    val message: String? = null,
    val webdav: WebDavSettings = WebDavSettings(),
    /** Backups found on the WebDAV server, shown as a picker; null = picker closed. */
    val remote: List<RemoteFile>? = null,
    val pending: PendingRestore? = null,
    /** Set after a successful restore so the screen can reload; cleared by [BackupViewModel.consumeRestored]. */
    val restored: BackupKind? = null,
)

class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = BackupManager(app)
    private val _state = MutableStateFlow(BackupState(webdav = WebDavSettings.load(app)))
    val state: StateFlow<BackupState> = _state.asStateFlow()

    fun fileName(kind: BackupKind, encrypted: Boolean) = manager.fileName(kind, encrypted)

    private fun dav(s: WebDavSettings = _state.value.webdav) = WebDavClient(LlmClients.http, s.url, s.user, s.password)

    private fun run(start: String, block: suspend () -> String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, progress = start, message = null) }
        val msg = try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            "Failed: ${e.message ?: e.javaClass.simpleName}"
        }
        _state.update { it.copy(busy = false, progress = null, message = msg.ifBlank { null }) }
    }

    private fun progress(p: String) = _state.update { it.copy(progress = p) }

    /** Checks the server and login (creating the folder if needed), then saves the settings. */
    fun saveWebDav(s: WebDavSettings) = run("Connecting to WebDAV…") {
        dav(s).ensureFolder()
        WebDavSettings.save(getApplication(), s)
        _state.update { it.copy(webdav = s) }
        "WebDAV connected and saved"
    }

    fun backupToUri(kind: BackupKind, password: CharArray?, uri: Uri) = run("Preparing backup…") {
        val tmp = File.createTempFile("backup_out", ".bak", getApplication<Application>().cacheDir)
        try {
            val m = manager.create(kind, tmp, password, ::progress)
            progress("Saving file…")
            getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Couldn't write to the chosen file")
            "Saved ${kind.label.lowercase()} backup (${m.summary()}, ${sizeText(tmp.length())})"
        } finally {
            tmp.delete()
        }
    }

    fun backupToWebDav(kind: BackupKind, password: CharArray?) = run("Preparing backup…") {
        val tmp = File.createTempFile("backup_out", ".bak", getApplication<Application>().cacheDir)
        try {
            val m = manager.create(kind, tmp, password, ::progress)
            val name = manager.fileName(kind, password != null)
            progress("Uploading ${sizeText(tmp.length())}…")
            dav().run { ensureFolder(); upload(name, tmp) }
            "Uploaded $name (${m.summary()})"
        } finally {
            tmp.delete()
        }
    }

    // ---- Restore ------------------------------------------------------------------------------

    fun pickLocal(uri: Uri?) {
        if (uri == null) return
        run("Reading backup…") {
            val app = getApplication<Application>()
            val tmp = File.createTempFile("restore_in", ".bak", app.cacheDir)
            app.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("Couldn't open the file")
            stage(tmp, uri.lastPathSegment?.substringAfterLast('/') ?: "backup file")
            ""
        }
    }

    fun listRemote() = run("Listing WebDAV backups…") {
        val files = dav().list().filter { it.name.startsWith("HealthAnalyzer-") }
        _state.update { it.copy(remote = files) }
        if (files.isEmpty()) "No HealthAnalyzer backups in that WebDAV folder yet" else ""
    }

    fun closeRemote() = _state.update { it.copy(remote = null) }

    fun pickRemote(f: RemoteFile) {
        _state.update { it.copy(remote = null) }
        run("Downloading ${f.name}…") {
            val tmp = File.createTempFile("restore_in", ".bak", getApplication<Application>().cacheDir)
            dav().download(f.name, tmp)
            stage(tmp, f.name)
            ""
        }
    }

    /** Unencrypted files open straight away; encrypted ones wait for [unlock]. */
    private fun stage(file: File, name: String) {
        val pending = if (com.healthanalyzer.app.backup.BackupCrypto.isEncrypted(file)) PendingRestore(file, name)
        else PendingRestore(file, name, opened = manager.open(file, null))
        _state.update { it.copy(pending = pending) }
    }

    fun unlock(password: CharArray) {
        val p = _state.value.pending ?: return
        run("Decrypting…") {
            try {
                val opened = manager.open(p.file, password)
                _state.update { it.copy(pending = p.copy(opened = opened, error = null)) }
            } catch (e: WrongPasswordException) {
                _state.update { it.copy(pending = p.copy(error = e.message)) }
            }
            ""
        }
    }

    fun confirmRestore() {
        val p = _state.value.pending ?: return
        val opened = p.opened ?: return
        _state.update { it.copy(pending = null) }
        run("Restoring…") {
            try {
                manager.restore(opened, ::progress)
                _state.update { it.copy(restored = opened.manifest.kind, webdav = WebDavSettings.load(getApplication())) }
                "Restored ${opened.manifest.kind.label.lowercase()} from ${opened.manifest.createdAt.replace('T', ' ')} (${opened.manifest.summary()})"
            } finally {
                opened.cleanup(); p.file.delete()
            }
        }
    }

    fun cancelRestore() {
        _state.value.pending?.let { it.opened?.cleanup(); it.file.delete() }
        _state.update { it.copy(pending = null) }
    }

    fun consumeRestored() = _state.update { it.copy(restored = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun sizeText(bytes: Long) = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}
