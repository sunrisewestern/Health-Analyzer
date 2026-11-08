package com.healthanalyzer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthanalyzer.app.ui.AiViewModel
import com.healthanalyzer.app.ui.AppRoot
import com.healthanalyzer.app.ui.RunsViewModel
import com.healthanalyzer.app.ui.theme.HealthAnalyzerTheme

class MainActivity : ComponentActivity() {

    private val runsViewModel: RunsViewModel by viewModels()
    private val aiViewModel: AiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeConfig by aiViewModel.themeConfig.collectAsStateWithLifecycle()
            HealthAnalyzerTheme(themeConfig = themeConfig) {
                AppRoot(runsViewModel, aiViewModel, activity = this)
            }
        }
        runsViewModel.checkPermissions(this)
    }
}
