package com.healthanalyzer.app

import android.app.Application
import com.healthanalyzer.app.ai.AiDatabase
import com.healthanalyzer.app.ai.PromptRenderer
import com.healthanalyzer.app.data.AppDatabase
import com.healthanalyzer.app.data.RunRepository
import com.healthanalyzer.app.samsung.SamsungHealthSource

class HealthAnalyzerApp : Application() {
    val samsungHealth by lazy { SamsungHealthSource(this) }
    val database by lazy { AppDatabase.create(this) }
    val repository by lazy { RunRepository(database.runDao(), samsungHealth, prefs = getSharedPreferences("sync_state", MODE_PRIVATE)) }
    val aiDatabase by lazy { AiDatabase.create(this) }
    val garmin by lazy { com.healthanalyzer.app.data.GarminConnection(this) }
    override fun onCreate() {
        super.onCreate()
        // Normally a no-op: WorkManager keeps the schedule itself. This restores it if it was ever lost.
        runCatching { com.healthanalyzer.app.backup.AutoBackupScheduler.sync(this, replace = false) }
    }

    val promptRenderer by lazy { PromptRenderer(this, database.runDao()) { from, to -> aiDatabase.aiDao().planBetween(from, to) } }
}
