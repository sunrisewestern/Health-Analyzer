package com.healthanalyzer.app.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthanalyzer.app.ui.MarkdownText
import java.text.DateFormat
import java.util.Date

/** The "new version available" reminder; shown over any tab. */
@Composable
fun UpdateDialog(vm: UpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.dialog) return
    // Re-read the install permission when the user comes back from the system settings screen.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val needsPermission = remember(resumes, state.status) { vm.needsInstallPermission() }

    val status = state.status
    val info = when (status) {
        is UpdateStatus.Available -> status.info
        is UpdateStatus.Downloading -> status.info
        is UpdateStatus.ReadyToInstall -> status.info
        is UpdateStatus.Failed -> status.info
        else -> null
    } ?: return

    AlertDialog(
        onDismissRequest = { if (status !is UpdateStatus.Downloading) vm.dismissDialog() },
        title = { Text("Update available: v${info.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "You have v${vm.currentVersion}. " + sizeLabel(info.apkSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotBlank()) {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        MarkdownText(info.notes)
                    }
                }
                when (status) {
                    is UpdateStatus.Downloading -> {
                        LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading… ${(status.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                    is UpdateStatus.ReadyToInstall -> if (needsPermission) {
                        Text(
                            "Allow HealthAnalyzer to install apps in the next screen, then come back and tap Install.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is UpdateStatus.Failed -> Text(
                        status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (status) {
                is UpdateStatus.Downloading -> TextButton(onClick = { vm.cancelDownload() }) { Text("Cancel") }
                is UpdateStatus.ReadyToInstall -> Button(onClick = { vm.install() }) {
                    Text(if (needsPermission) "Allow installs" else "Install")
                }
                is UpdateStatus.Failed -> Button(onClick = { vm.download(info) }) { Text("Try again") }
                else -> Button(onClick = { vm.download(info) }) { Text("Download & install") }
            }
        },
        dismissButton = {
            if (status !is UpdateStatus.Downloading) {
                Row {
                    if (status is UpdateStatus.Available) {
                        TextButton(onClick = { vm.skip(info) }) { Text("Skip version") }
                    } else {
                        TextButton(onClick = { vm.openReleasePage(info) }) { Text("Open GitHub") }
                    }
                    TextButton(onClick = { vm.dismissDialog() }) { Text("Later") }
                }
            }
        },
    )
}

/** "App updates" card in Settings: current version, manual check, auto-check switch. */
@Composable
fun UpdateSettingsCard(vm: UpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App Updates", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Installed: v${vm.currentVersion} · from GitHub releases",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val line = when (val s = state.status) {
                UpdateStatus.Checking -> "Checking…"
                is UpdateStatus.UpToDate -> "You're up to date (latest v${s.latest})."
                is UpdateStatus.Available -> "v${s.info.version} is available."
                is UpdateStatus.Downloading -> "Downloading v${s.info.version}… ${(s.progress * 100).toInt()}%"
                is UpdateStatus.ReadyToInstall -> "v${s.info.version} downloaded – ready to install."
                is UpdateStatus.Failed -> s.message
                UpdateStatus.Idle -> state.lastCheckedMs.takeIf { it > 0 }
                    ?.let { "Last checked " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
            }
            line?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.status is UpdateStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val st = state.status
                if (st is UpdateStatus.Available || st is UpdateStatus.Downloading || st is UpdateStatus.ReadyToInstall) {
                    Button(onClick = { vm.showDialog() }) { Text("View update") }
                } else {
                    OutlinedButton(onClick = { vm.check() }, enabled = st != UpdateStatus.Checking) { Text("Check for updates") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Remind me when a new version is out",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.autoCheck, onCheckedChange = vm::setAutoCheck)
            }
        }
    }
}

private fun sizeLabel(bytes: Long): String =
    if (bytes > 0) "Download size: %.1f MB.".format(bytes / 1_048_576.0) else ""
