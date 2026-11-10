package com.healthanalyzer.app.ui

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Tab(val label: String) {
    RUNS("Runs"),
    PLAN("Plan"),
    ANALYZE("Analyze"),
    SETTINGS("Settings"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppRoot(runsVm: RunsViewModel, aiVm: AiViewModel, activity: Activity) {
    var tab by rememberSaveable { mutableIntStateOf(Tab.RUNS.ordinal) }
    val snackbar = remember { SnackbarHostState() }
    val message by aiVm.settingsMessage.collectAsStateWithLifecycle()
    val isKeyboardOpen = WindowInsets.isImeVisible

    val updateVm: com.healthanalyzer.app.update.UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    LaunchedEffect(Unit) { updateVm.checkOnLaunch() }
    com.healthanalyzer.app.update.UpdateDialog(updateVm)

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long)
            aiVm.dismissSettingsMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!isKeyboardOpen) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 6.dp,
                ) {
                    Tab.entries.forEach { t ->
                        val isSelected = tab == t.ordinal
                        val iconColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { tab = t.ordinal },
                            icon = {
                                when (t) {
                                    Tab.RUNS -> AppIcons.Runner(modifier = Modifier.size(24.dp), tint = iconColor)
                                    Tab.PLAN -> AppIcons.Calendar(modifier = Modifier.size(24.dp), tint = iconColor)
                                    Tab.ANALYZE -> AppIcons.Sparkles(modifier = Modifier.size(24.dp), tint = iconColor)
                                    Tab.SETTINGS -> AppIcons.Tune(modifier = Modifier.size(24.dp), tint = iconColor)
                                }
                            },
                            label = {
                                Text(
                                    t.label,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            when (Tab.entries[tab]) {
                Tab.RUNS -> RunsScreen(runsVm, activity, onAnalyzeRun = { id ->
                    aiVm.prepareForRun(id)
                    tab = Tab.ANALYZE.ordinal
                })
                Tab.PLAN -> PlanScreen(aiVm, onOpenSettings = { aiVm.navigateToSettings("ai"); tab = Tab.SETTINGS.ordinal })
                Tab.ANALYZE -> AnalyzeScreen(aiVm, onOpenSettings = { tab = Tab.SETTINGS.ordinal })
                Tab.SETTINGS -> SettingsScreen(aiVm, activity, runsVm)
            }
        }
    }
}
