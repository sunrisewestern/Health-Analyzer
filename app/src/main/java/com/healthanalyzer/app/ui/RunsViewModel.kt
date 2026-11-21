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
    /** Health Connect exists on this phone (it may still need an update). */
    val healthConnectAvailable: Boolean = false,
    val healthConnectNeedsUpdate: Boolean = false,
    /** Exercise read access is granted, so Health Connect can be synced. */
    val healthConnectConnected: Boolean = false,
    /** How many of the permissions asked for are granted, e.g. "9 of 12". */
    val healthConnectAccess: String? = null,
    /** Health Connect supports background reads but the app may not read in the background yet. */
    val healthConnectNeedsBackgroundAccess: Boolean = false,
    /** An automatic sync of all connected sources is running. */
    val autoSyncing: Boolean = false,
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
        // Sleep, energy score and all-day heart rate were added later: ask for them once.
        val prefs = activity.getSharedPreferences("sync_state", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("recoveryAsked", false)) {
            prefs.edit().putBoolean("recoveryAsked", true).apply()
            runCatching { source.requestRecoveryPermissions(activity) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        val count = com.healthanalyzer.app.sync.AutoSyncRunner.exclusive { repository.sync(full) }
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
                val (stored, dupes) = com.healthanalyzer.app.sync.AutoSyncRunner.exclusive { repository.importRuns(parsed.runs) }
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
                val result = com.healthanalyzer.app.sync.AutoSyncRunner.exclusive { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
                } }
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
            val n = com.healthanalyzer.app.sync.AutoSyncRunner.exclusive {
                repository.eraseAll().also { garmin.resetSyncMarker() }
            }
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

    // ---- Automatic sync of every connected source ---------------------------------------------

    private val autoSync = com.healthanalyzer.app.sync.AutoSyncRunner(container)

    private val _autoSyncSettings = MutableStateFlow(com.healthanalyzer.app.sync.AutoSyncSettings.load(app))
    val autoSyncSettings: StateFlow<com.healthanalyzer.app.sync.AutoSyncSettings> = _autoSyncSettings.asStateFlow()

    val autoSyncStatus: StateFlow<com.healthanalyzer.app.sync.AutoSyncStatus> =
        com.healthanalyzer.app.sync.AutoSyncRunner.status(app)

    fun setAutoSyncSettings(s: com.healthanalyzer.app.sync.AutoSyncSettings) {
        val intervalChanged = s.interval != _autoSyncSettings.value.interval
        com.healthanalyzer.app.sync.AutoSyncSettings.save(getApplication(), s)
        _autoSyncSettings.value = s
        if (intervalChanged) com.healthanalyzer.app.sync.AutoSyncScheduler.sync(getApplication(), replace = true)
    }

    /** On every return to the app, if switched on; skipped when a sync finished in the last 15 minutes. */
    fun syncOnOpen() {
        // Imports and manual syncs hold the same lock, so this waits for them instead of overlapping.
        if (!_autoSyncSettings.value.onOpen) return
        runAutoSync(com.healthanalyzer.app.sync.AutoSyncRunner.OPEN_THROTTLE_MS)
    }

    /** "Sync now" in Settings: every connected source, right away. */
    fun syncAllNow() = runAutoSync(0, always = true)

    private fun runAutoSync(skipIfWithinMs: Long, always: Boolean = false) {
        if (_state.value.autoSyncing) return
        viewModelScope.launch {
            _state.update { it.copy(autoSyncing = true) }
            try {
                val status = autoSync.run(background = false, skipIfWithinMs = skipIfWithinMs) ?: return@launch
                if (always || status.newRuns > 0) {
                    _state.update { it.copy(message = "Auto sync: ${status.newRuns} new run(s) · ${status.summary}") }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(message = "Auto sync failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(autoSyncing = false) }
                refreshGarminState()
            }
        }
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
            val r = com.healthanalyzer.app.sync.AutoSyncRunner.exclusive {
                garmin.sync(repository, full) { p -> _state.update { it.copy(progress = p) } }
            }
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

    // ---- Health Connect ---------------------------------------------------------------------

    val healthConnect = container.healthConnect

    /** Re-reads availability and grants; called on every return to the app, since both change outside it. */
    fun refreshHealthConnectState() = viewModelScope.launch {
        runCatching {
            val available = healthConnect.isAvailable
            val granted = if (available) healthConnect.grantedPermissions() else emptySet()
            val wanted = if (available) healthConnect.permissionsToRequest() else emptySet()
            _state.update {
                it.copy(
                    healthConnectAvailable = available,
                    healthConnectNeedsUpdate = healthConnect.needsUpdate,
                    healthConnectConnected = com.healthanalyzer.app.healthconnect.HealthConnectSource.READ_EXERCISE in granted,
                    healthConnectAccess = "${wanted.count { p -> p in granted }} of ${wanted.size}",
                    healthConnectNeedsBackgroundAccess = available && healthConnect.supportsBackgroundRead &&
                        !healthConnect.canReadInBackground(granted),
                )
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    /** Result of Health Connect's permission screen. */
    fun onHealthConnectPermissions(granted: Set<String>) {
        val ok = com.healthanalyzer.app.healthconnect.HealthConnectSource.READ_EXERCISE in granted
        _state.update {
            it.copy(message = if (ok) "Health Connect connected. Tap Sync Health Connect on the Runs tab." else "Health Connect: exercise access is needed to sync")
        }
        refreshHealthConnectState()
    }

    fun syncHealthConnect(full: Boolean) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            val r = com.healthanalyzer.app.sync.AutoSyncRunner.exclusive {
                repository.syncHealthConnect(healthConnect, full) { p -> _state.update { it.copy(progress = p) } }
            }
            val parts = buildList {
                add("Health Connect: ${r.runs} run(s) and ${r.workouts} other workout(s) stored")
                if (r.duplicates > 0) add("${r.duplicates} already here from another source")
                if (r.recoveryDays > 0) add("${r.recoveryDays} day(s) of sleep / resting HR")
            }
            _state.update { it.copy(message = parts.joinToString(" · ")) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(message = "Health Connect sync failed: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            _state.update { it.copy(busy = false, progress = null) }
            refreshHealthConnectState()
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
