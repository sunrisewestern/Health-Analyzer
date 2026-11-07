package com.healthanalyzer.app.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healthanalyzer.app.HealthAnalyzerApp
import com.healthanalyzer.app.data.RunDetail
import com.healthanalyzer.app.data.RunEntity
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val permissionsGranted: Boolean? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val selected: RunDetail? = null,
    val filter: com.healthanalyzer.app.data.RunFilter = com.healthanalyzer.app.data.RunFilter(),
    val showDashboard: Boolean = false,
    val garminConnected: Boolean = false,
    val garminLastSync: String? = null,
    val progress: String? = null,
)

class RunsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app as HealthAnalyzerApp
    private val source = container.samsungHealth
    private val repository = container.repository

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val runs: StateFlow<List<RunEntity>> =
        repository.observeRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Checks permissions; if Samsung Health is missing or outdated, offers the fix. */
    fun checkPermissions(activity: Activity) = launchGuarded(activity) {
        val granted = source.hasAllPermissions()
        _state.update { it.copy(permissionsGranted = granted) }
    }

    fun requestPermissions(activity: Activity) = launchGuarded(activity) {
        val granted = source.requestPermissions(activity)
        _state.update {
            it.copy(
                permissionsGranted = granted,
                message = if (granted) "Access granted" else "Exercise and exercise-location access are both needed",
            )
        }
    }

    fun sync(activity: Activity, full: Boolean) = launchGuarded(activity) {
        val count = repository.sync(full)
        _state.update { it.copy(message = "Synced $count run(s) from Samsung Health") }
    }

    fun open(id: String) = viewModelScope.launch {
        _state.update { it.copy(selected = repository.getDetail(id)) }
    }

    /** Reads the picked .fit/.zip files and stores the runs in them. */
    fun importFit(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    uris.mapNotNull { uri ->
                        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "file"
                        resolver.openInputStream(uri)?.use { name to it.readBytes() }
                    }
                }
                val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.healthanalyzer.app.data.FitImporter.parseFiles(files)
                }
                val (stored, dupes) = repository.importRuns(parsed.runs)
                val parts = buildList {
                    add("Imported $stored run(s) from ${parsed.filesRead} FIT file(s)")
                    if (dupes > 0) add("$dupes already synced from Samsung")
                    if (parsed.skippedNonRunning > 0) add("${parsed.skippedNonRunning} non-running activities ignored")
                    if (parsed.failures.isNotEmpty()) add("${parsed.failures.size} failed: ${parsed.failures.first()}")
                }
                _state.update { it.copy(message = parts.joinToString(" · ")) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Import failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Imports a Samsung Health "Download personal data" export: either the folder ([isFolder],
     * from the folder picker) or a zip of it. Adds running dynamics and sweat loss to runs
     * already synced through the SDK, and adds any runs the SDK hasn't returned.
     */
    fun importSamsungExport(uri: android.net.Uri?, isFolder: Boolean) {
        if (uri == null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "Opening Samsung Health export…") }
            val app = getApplication<Application>()
            var tempZip: java.io.File? = null
            try {
                var stored = 0
                var dupes = 0
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val files: com.healthanalyzer.app.data.SamsungExportFiles = if (isFolder) {
                        com.healthanalyzer.app.samsung.SafSamsungExport(app.contentResolver, uri)
                    } else {
                        // ZipFile needs random access, so copy the picked zip to the cache first.
                        val f = java.io.File.createTempFile("samsung_export", ".zip", app.cacheDir).also { tempZip = it }
                        _state.update { it.copy(progress = "Copying zip…") }
                        app.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                            ?: throw IllegalArgumentException("Couldn't open the zip")
                        com.healthanalyzer.app.data.ZipSamsungExport(f)
                    }
                    try {
                        com.healthanalyzer.app.data.SamsungExportImporter.parse(
                            files,
                            onProgress = { done, total -> _state.update { it.copy(progress = "Reading Samsung runs $done/$total…") } },
                        ) { batch ->
                            val (s, d) = repository.importRuns(batch)
                            stored += s
                            dupes += d
                        }
                    } finally {
                        (files as? AutoCloseable)?.close()
                    }
                }
                val parts = buildList {
                    add("Samsung export: $stored run(s) stored")
                    if (result.withDynamics > 0) add("${result.withDynamics} with running dynamics")
                    if (dupes > 0) add("$dupes already imported from Garmin")
                    if (result.failures.isNotEmpty()) add("${result.failures.size} failed: ${result.failures.first()}")
                }
                _state.update { it.copy(message = parts.joinToString(" · ")) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Samsung export import failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                tempZip?.delete()
                _state.update { it.copy(busy = false, progress = null) }
            }
        }
    }

    /** Deletes every stored run (all sources). Garmin's sync marker is reset so a later sync refetches. */
    fun eraseAllRuns() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            val n = repository.eraseAll()
            garmin.resetSyncMarker()
            _state.update { it.copy(selected = null, message = "Erased $n run(s). Sync or import to load data again.") }
        } catch (e: Exception) {
            _state.update { it.copy(message = "Erase failed: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            refreshGarminState()
            _state.update { it.copy(busy = false) }
        }
    }

    // ---- Garmin Connect (Personal API) -----------------------------------------------------

    private val garmin = container.garmin

    init {
        refreshGarminState()
    }

    fun refreshGarminState() = _state.update {
        it.copy(garminConnected = garmin.isConnected, garminLastSync = garmin.lastSync?.toString())
    }

    /** Validates the key with Garmin and saves it (encrypted) if it works. */
    fun connectGarmin(apiKey: String, china: Boolean) = viewModelScope.launch {
        _state.update { it.copy(busy = true, progress = "Checking the key with Garmin…") }
        try {
            val runs = garmin.connect(apiKey, china)
            _state.update { it.copy(message = "Garmin connected · $runs run(s) in the last 30 days. Tap Sync Garmin on the Runs tab.") }
        } catch (e: Exception) {
            _state.update { it.copy(message = "Garmin: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            refreshGarminState()
            _state.update { it.copy(busy = false, progress = null) }
        }
    }

    fun disconnectGarmin() {
        garmin.disconnect()
        refreshGarminState()
        _state.update { it.copy(message = "Garmin disconnected. Runs already imported are kept.") }
    }

    fun syncGarmin(full: Boolean) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            val r = garmin.sync(repository, full) { p -> _state.update { it.copy(progress = p) } }
            val parts = buildList {
                add("Garmin: ${r.stored} run(s) stored from ${r.downloaded} new activities")
                if (r.duplicates > 0) add("${r.duplicates} already synced from Samsung")
                if (r.failures.isNotEmpty()) add("${r.failures.size} failed: ${r.failures.first()}")
            }
            _state.update { it.copy(message = parts.joinToString(" · ")) }
        } catch (e: Exception) {
            _state.update { it.copy(message = "Garmin sync failed: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            refreshGarminState()
            _state.update { it.copy(busy = false, progress = null) }
        }
    }

    fun setFilter(f: com.healthanalyzer.app.data.RunFilter) = _state.update { it.copy(filter = f) }

    fun setShowDashboard(show: Boolean) = _state.update { it.copy(showDashboard = show) }

    fun closeDetail() = _state.update { it.copy(selected = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun launchGuarded(activity: Activity, block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            block()
        } catch (e: ResolvablePlatformException) {
            // Samsung Health not installed / too old / needs the user's agreement.
            if (e.hasResolution) e.resolve(activity)
            _state.update { it.copy(message = describe(e)) }
        } catch (e: HealthDataException) {
            _state.update { it.copy(message = describe(e)) }
        } catch (e: Exception) {
            _state.update { it.copy(message = "Error: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun describe(e: HealthDataException): String {
        val hint = when (e.errorCode) {
            2003 -> " – app not registered. Turn on Samsung Health > Developer mode > Developer Mode for Data Read."
            else -> ""
        }
        return "Samsung Health error ${e.errorCode}: ${e.errorMessage}$hint"
    }
}
