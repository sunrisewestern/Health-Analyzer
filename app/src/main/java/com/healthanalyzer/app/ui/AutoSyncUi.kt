package com.healthanalyzer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.healthanalyzer.app.sync.AutoSyncInterval
import com.healthanalyzer.app.sync.AutoSyncSettings
import com.healthanalyzer.app.sync.AutoSyncStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

/** Settings → Data & Integrations: background sync of every connected source. */
@Composable
fun AutoSyncSection(
    settings: AutoSyncSettings,
    status: AutoSyncStatus,
    syncing: Boolean,
    /** Shown when Health Connect could read in the background but isn't allowed to yet. */
    onAllowHealthConnectBackground: (() -> Unit)?,
    onChange: (AutoSyncSettings) -> Unit,
    onSyncNow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Pulls new workouts from Samsung Health, Garmin and Health Connect, whichever are connected. " +
                "None of them tells apps when new data arrives, so the app checks on a schedule, " +
                "when you open it, and right before today's briefing is written.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Picker(
            "Background sync", settings.interval, AutoSyncInterval.entries, { it.label },
            { picked -> onChange(settings.copy(interval = picked)) },
            Modifier.testTag("auto_sync_interval"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sync when the app opens", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "At most every 15 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = settings.onOpen,
                onCheckedChange = { onChange(settings.copy(onOpen = it)) },
                modifier = Modifier.testTag("auto_sync_on_open"),
            )
        }
        if (onAllowHealthConnectBackground != null && settings.interval != AutoSyncInterval.OFF) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Health Connect only syncs while the app is open until you allow background access.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAllowHealthConnectBackground) { Text("Allow") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    syncing -> "Syncing…"
                    status.finishedEpochMs == 0L -> "Not synced automatically yet."
                    else -> "Last sync " + Instant.ofEpochMilli(status.finishedEpochMs).atZone(ZoneId.systemDefault()).format(WHEN) +
                        " · ${status.newRuns} new run(s)\n" + status.summary
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (syncing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("auto_sync_status"),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSyncNow, enabled = !syncing, shape = RoundedCornerShape(10.dp)) { Text("Sync now") }
        }
    }
}
