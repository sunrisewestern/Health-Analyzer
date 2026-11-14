package com.healthanalyzer.app.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Background run of one destination's automatic backup. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dest = AutoBackupDestination.fromKey(inputData.getString(KEY_DESTINATION)) ?: return Result.failure()
        // Settings may have changed since this run was queued.
        if (!AutoBackupScheduler.isActive(applicationContext, dest)) return Result.success()
        val status = AutoBackupRunner(applicationContext).run(setOf(dest))[dest]
        // A server can be briefly unreachable; a folder problem needs the user, so it waits for the next period.
        val retry = status?.ok == false && dest == AutoBackupDestination.WEBDAV && runAttemptCount < MAX_RETRIES
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_DESTINATION = "destination"
        private const val MAX_RETRIES = 2
    }
}

/**
 * Keeps WorkManager's periodic work in line with [AutoBackupSettings]. Each destination has its own
 * work so WebDAV can wait for a network connection without holding back the local folder.
 * WorkManager persists the schedule across app restarts and reboots.
 */
object AutoBackupScheduler {

    fun workName(dest: AutoBackupDestination) = "auto_backup_${dest.key}"

    /** Scheduled only when switched on and set up far enough to work. */
    fun isActive(context: Context, dest: AutoBackupDestination): Boolean {
        val s = AutoBackupSettings.load(context)
        if (s.interval == AutoBackupInterval.OFF || !s.enabled(dest)) return false
        return when (dest) {
            AutoBackupDestination.LOCAL -> s.folderUri.isNotBlank()
            AutoBackupDestination.WEBDAV -> WebDavSettings.load(context).isSet
        }
    }

    /**
     * [replace] true after a settings change (applies the new interval, keeping the running schedule);
     * false at app start, where existing work is left untouched.
     */
    fun sync(context: Context, replace: Boolean) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val interval = AutoBackupSettings.load(context).interval
        AutoBackupDestination.entries.forEach { dest ->
            if (!isActive(context, dest)) {
                wm.cancelUniqueWork(workName(dest))
                return@forEach
            }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(interval.hours, TimeUnit.HOURS)
                .setInputData(workDataOf(AutoBackupWorker.KEY_DESTINATION to dest.key))
                .apply {
                    if (dest == AutoBackupDestination.WEBDAV) {
                        setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    }
                }
                .build()
            wm.enqueueUniquePeriodicWork(
                workName(dest),
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
