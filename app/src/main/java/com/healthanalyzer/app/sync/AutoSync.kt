package com.healthanalyzer.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.healthanalyzer.app.HealthAnalyzerApp
import com.healthanalyzer.app.healthconnect.HealthConnectSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** How often new workouts are pulled in the background. */
enum class AutoSyncInterval(val hours: Long, val label: String) {
    OFF(0, "Off"),
    H3(3, "Every 3 hours"),
    H6(6, "Every 6 hours"),
    H12(12, "Every 12 hours"),
    DAILY(24, "Daily");

    companion object {
        /** Missing means daily (the default); unknown names from other app versions too. */
        fun fromName(name: String?): AutoSyncInterval = entries.firstOrNull { it.name == name } ?: DAILY
    }
}

data class AutoSyncSettings(
    val interval: AutoSyncInterval = AutoSyncInterval.DAILY,
    /** Also syncs whenever the app is opened (at most every [OPEN_THROTTLE_MS]). */
    val onOpen: Boolean = true,
) {
    companion object {
        private const val PREFS = "auto_sync"

        fun load(context: Context): AutoSyncSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AutoSyncSettings(AutoSyncInterval.fromName(p.getString("interval", null)), p.getBoolean("onOpen", true))
        }

        fun save(context: Context, s: AutoSyncSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("interval", s.interval.name)
                .putBoolean("onOpen", s.onOpen)
                .apply()
        }
    }
}

/** What one source did in a sync run. */
data class SourceOutcome(val source: String, val ok: Boolean, val text: String)

/** The last finished automatic sync, shown in Settings. */
data class AutoSyncStatus(
    val finishedEpochMs: Long = 0,
    val newRuns: Int = 0,
    val outcomes: List<SourceOutcome> = emptyList(),
    /**
     * When every connected source last synced without a problem. Throttling counts only these, so
     * a background run that had to skip Health Connect, or failed offline, doesn't stop the next
     * app open from trying again.
     */
    val completeEpochMs: Long = 0,
) {
    val summary: String
        get() = outcomes.joinToString(" · ") { "${it.source}: ${it.text}" }.ifEmpty { "No connected source to sync" }

    companion object {
        private const val PREFS = "auto_sync"

        fun load(context: Context): AutoSyncStatus {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val outcomes = p.getString("lastOutcomes", "").orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size == 3) SourceOutcome(parts[0], parts[1] == "1", parts[2]) else null
            }
            return AutoSyncStatus(p.getLong("lastFinished", 0), p.getInt("lastNewRuns", 0), outcomes, p.getLong("lastComplete", 0))
        }

        fun save(context: Context, s: AutoSyncStatus) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("lastFinished", s.finishedEpochMs)
                .putInt("lastNewRuns", s.newRuns)
                .putLong("lastComplete", s.completeEpochMs)
                .putString(
                    "lastOutcomes",
                    s.outcomes.joinToString("\n") { "${it.source}\t${if (it.ok) "1" else "0"}\t${it.text.replace('\t', ' ').replace('\n', ' ')}" },
                )
                .apply()
        }
    }
}

/**
 * Pulls new workouts from every source that is set up: Samsung Health, then Garmin, then Health
 * Connect (which skips workouts the other two already brought in). None of the three tells an app
 * when new data arrives, so "when data appears" means: on a schedule, when the app opens, and
 * right before today's briefing is written.
 */
class AutoSyncRunner(private val app: HealthAnalyzerApp) {

    /**
     * Syncs unless another run finished less than [skipIfWithinMs] ago (0 = always). Runs never
     * overlap: a caller that waited for another run re-checks that age, so it usually skips.
     * [background] true from the worker, where Health Connect needs its background-read grant.
     * Returns null when skipped.
     */
    suspend fun run(background: Boolean, skipIfWithinMs: Long = 0): AutoSyncStatus? = lock.withLock {
        val last = AutoSyncStatus.load(app)
        if (skipIfWithinMs > 0 && System.currentTimeMillis() - last.completeEpochMs < skipIfWithinMs) return@withLock null
        val dao = app.database.runDao()
        val before = dao.runCount()
        val outcomes = buildList {
            samsung()?.let(::add)
            garmin()?.let(::add)
            healthConnect(background)?.let(::add)
        }
        val now = System.currentTimeMillis()
        val status = AutoSyncStatus(
            now, (dao.runCount() - before).coerceAtLeast(0), outcomes,
            completeEpochMs = if (outcomes.all { it.ok }) now else last.completeEpochMs,
        )
        AutoSyncStatus.save(app, status)
        shared(app).value = status
        status
    }

    private suspend fun samsung(): SourceOutcome? {
        // Not installed, not agreed to, or no access yet: not set up, so nothing to report.
        val ready = guarded { app.samsungHealth.hasAllPermissions() }.getOrDefault(false)
        if (!ready) return null
        return guarded { app.repository.sync(full = false) }.fold(
            { SourceOutcome(SAMSUNG, true, "$it run(s) checked") },
            { SourceOutcome(SAMSUNG, false, it.message ?: it.javaClass.simpleName) },
        )
    }

    private suspend fun garmin(): SourceOutcome? {
        if (!app.garmin.isConnected) return null
        return guarded { app.garmin.sync(app.repository, full = false) }.fold(
            { r -> SourceOutcome(GARMIN, r.failures.isEmpty(), "${r.stored} new run(s)" + if (r.failures.isNotEmpty()) ", ${r.failures.size} failed" else "") },
            { SourceOutcome(GARMIN, false, it.message ?: it.javaClass.simpleName) },
        )
    }

    private suspend fun healthConnect(background: Boolean): SourceOutcome? {
        val hc = app.healthConnect
        if (!hc.isAvailable) return null
        val granted = guarded { hc.grantedPermissions() }.getOrDefault(emptySet())
        if (HealthConnectSource.READ_EXERCISE !in granted) return null
        if (background && !hc.canReadInBackground(granted)) {
            return SourceOutcome(HEALTH_CONNECT, false, "skipped in background – allow background access, or open the app")
        }
        return guarded { app.repository.syncHealthConnect(hc, full = false) }.fold(
            { r -> SourceOutcome(HEALTH_CONNECT, true, "${r.runs} run(s), ${r.workouts} other workout(s)") },
            { SourceOutcome(HEALTH_CONNECT, false, it.message ?: it.javaClass.simpleName) },
        )
    }

    /** runCatching that lets cancellation through. */
    private inline fun <T> guarded(block: () -> T): Result<T> =
        runCatching(block).onFailure { if (it is CancellationException) throw it }

    companion object {
        const val SAMSUNG = "Samsung Health"
        const val GARMIN = "Garmin"
        const val HEALTH_CONNECT = "Health Connect"

        /** Opening the app syncs at most this often. */
        const val OPEN_THROTTLE_MS = 15 * 60_000L

        /** Today's briefing reuses a sync this recent instead of starting another. */
        const val RECAP_FRESH_MS = 2 * 60_000L

        /** Shared by every runner, so the worker and the app never sync at the same time. */
        private val lock = Mutex()

        /**
         * Runs [block] while no automatic sync is running, and keeps one from starting meanwhile.
         * For every other write to the run database: manual syncs, imports, erasing.
         */
        suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

        @Volatile private var latest: MutableStateFlow<AutoSyncStatus>? = null

        private fun shared(context: Context): MutableStateFlow<AutoSyncStatus> =
            latest ?: synchronized(this) { latest ?: MutableStateFlow(AutoSyncStatus.load(context)).also { latest = it } }

        /** The last finished sync, from whichever caller ran it (worker, app open, briefing). */
        fun status(context: Context): StateFlow<AutoSyncStatus> = shared(context)
    }
}

class AutoSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (AutoSyncSettings.load(applicationContext).interval == AutoSyncInterval.OFF) return Result.success()
        // Shorter than the period, so a run right after opening the app isn't repeated.
        AutoSyncRunner(applicationContext as HealthAnalyzerApp).run(background = true, skipIfWithinMs = AutoSyncRunner.OPEN_THROTTLE_MS)
        return Result.success()
    }
}

/** Keeps WorkManager's periodic sync in line with [AutoSyncSettings]; WorkManager keeps it across reboots. */
object AutoSyncScheduler {
    const val WORK_NAME = "auto_sync"

    fun sync(context: Context, replace: Boolean) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val interval = AutoSyncSettings.load(context).interval
        if (interval == AutoSyncInterval.OFF) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        // No network requirement: Samsung Health and Health Connect are on the phone; Garmin just fails that run.
        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(interval.hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(
            WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
