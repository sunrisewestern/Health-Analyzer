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
    val repository by lazy { RunRepository(database.runDao(), samsungHealth) }
    val aiDatabase by lazy { AiDatabase.create(this) }
    val garmin by lazy { com.healthanalyzer.app.data.GarminConnection(this) }
    val promptRenderer by lazy { PromptRenderer(this, database.runDao()) }
}
