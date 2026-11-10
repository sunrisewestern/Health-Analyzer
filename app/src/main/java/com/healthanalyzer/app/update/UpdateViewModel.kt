package com.healthanalyzer.app.update

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healthanalyzer.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val latest: String) : UpdateStatus
    data class Available(val info: ReleaseInfo) : UpdateStatus
    data class Downloading(val info: ReleaseInfo, val progress: Float) : UpdateStatus
    data class ReadyToInstall(val info: ReleaseInfo, val apk: File) : UpdateStatus
    data class Failed(val message: String, val info: ReleaseInfo? = null) : UpdateStatus
}

data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.Idle,
    /** The reminder dialog is open. */
    val dialog: Boolean = false,
    val autoCheck: Boolean = true,
    val lastCheckedMs: Long = 0,
)

class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    val currentVersion: String = BuildConfig.VERSION_NAME

    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UpdateUiState(
            // Debug builds use their own package (.dev), so a release APK wouldn't replace them.
            autoCheck = prefs.getBoolean(KEY_AUTO, !BuildConfig.DEBUG),
            lastCheckedMs = prefs.getLong(KEY_LAST, 0),
        ),
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Called on app start: checks at most once a day and only reminds about versions not skipped. */
    fun checkOnLaunch() {
        val s = _state.value
        if (!s.autoCheck || job?.isActive == true) return
        if (System.currentTimeMillis() - s.lastCheckedMs < CHECK_INTERVAL_MS) return
        check(manual = false)
    }

    /** [manual] = from the Settings button: always shows the result, even for a skipped version. */
    fun check(manual: Boolean = true) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(status = UpdateStatus.Checking) }
            try {
                val info = UpdateManager.latestRelease()
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST, now).apply()
                val newer = compareVersions(info.version, currentVersion) > 0
                val skipped = prefs.getString(KEY_SKIPPED, null) == info.version
                _state.update {
                    it.copy(
                        status = if (newer) UpdateStatus.Available(info) else UpdateStatus.UpToDate(info.version),
                        dialog = newer && (manual || !skipped),
                        lastCheckedMs = now,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed background check stays quiet; only a manual one reports it.
                _state.update {
                    it.copy(status = if (manual) UpdateStatus.Failed(e.message ?: "Couldn't check for updates") else UpdateStatus.Idle)
                }
            }
        }
    }

    fun download(info: ReleaseInfo) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(status = UpdateStatus.Downloading(info, 0f), dialog = true) }
            try {
                val apk = UpdateManager.download(getApplication(), info) { p ->
                    _state.update { s ->
                        if (s.status is UpdateStatus.Downloading) s.copy(status = UpdateStatus.Downloading(info, p)) else s
                    }
                }
                _state.update { it.copy(status = UpdateStatus.ReadyToInstall(info, apk)) }
                install()
            } catch (e: CancellationException) {
                _state.update { it.copy(status = UpdateStatus.Available(info)) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = UpdateStatus.Failed(e.message ?: "Download failed", info)) }
            }
        }
    }

    fun cancelDownload() { job?.cancel() }

    /** Opens the system installer, or first the "install unknown apps" permission screen. */
    fun install() {
        val ready = _state.value.status as? UpdateStatus.ReadyToInstall ?: return
        val ctx = getApplication<Application>()
        try {
            if (UpdateManager.needsInstallPermission(ctx)) UpdateManager.openInstallPermissionSettings(ctx)
            else UpdateManager.install(ctx, ready.apk)
        } catch (e: Exception) {
            _state.update { it.copy(status = UpdateStatus.Failed("Couldn't open the installer: ${e.message}", ready.info)) }
        }
    }

    fun needsInstallPermission(): Boolean = UpdateManager.needsInstallPermission(getApplication())

    fun openReleasePage(info: ReleaseInfo?) = UpdateManager.openReleasePage(getApplication(), info)

    fun showDialog() = _state.update { it.copy(dialog = true) }

    fun dismissDialog() {
        if (_state.value.status is UpdateStatus.Downloading) job?.cancel()
        _state.update { it.copy(dialog = false) }
    }

    /** "Skip this version": no more automatic reminders until a newer release appears. */
    fun skip(info: ReleaseInfo) {
        prefs.edit().putString(KEY_SKIPPED, info.version).apply()
        _state.update { it.copy(dialog = false) }
    }

    fun setAutoCheck(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, on).apply()
        _state.update { it.copy(autoCheck = on) }
    }

    private companion object {
        const val KEY_AUTO = "autoCheck"
        const val KEY_LAST = "lastCheckMs"
        const val KEY_SKIPPED = "skippedVersion"
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
