package com.healthanalyzer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthanalyzer.app.data.GarminPersonalApi
import com.healthanalyzer.app.ui.theme.GarminBlue
import com.healthanalyzer.app.ui.theme.SamsungGreen

/** Step-by-step setup instructions shown in Settings (and from the import menu). */
enum class SetupGuide(
    val title: String,
    val summary: String,
    val accent: Color,
    val steps: List<String>,
    val note: String,
    val link: Pair<String, String>? = null,
) {
    SAMSUNG_EXPORT(
        title = "Export Samsung Health data",
        summary = "Adds running dynamics and sweat loss that live sync can't read",
        accent = SamsungGreen,
        steps = listOf(
            "Open Samsung Health, tap ⋮ (More options) on the Home screen, then Settings.",
            "Tap \"Download personal data\", then Download. Confirm with your Samsung account if asked.",
            "Wait for it to finish – a long history can take several minutes. The data is saved to phone storage, usually Download › Samsung Health › samsunghealth_<name>_<date> (the completion screen shows the exact place).",
            "In HealthAnalyzer, tap Import on the Runs tab → \"Samsung Health export (folder)\" and pick that samsunghealth_… folder. Picking the \"Samsung Health\" folder above it also works – the newest export is used.",
            "Copied the export to a computer? Zip the samsunghealth_… folder and use \"Samsung Health export (.zip)\" instead.",
        ),
        note = "Android won't let apps open the whole Download folder, so pick the export folder itself. Re-importing is safe: runs already synced get enriched, not duplicated.",
    ),
    GARMIN_FIT(
        title = "Export Garmin FIT files",
        summary = "One activity, your whole history, or straight from the watch",
        accent = GarminBlue,
        steps = listOf(
            "One run: sign in at connect.garmin.com in a browser, open Activities → All Activities, open the run, tap the ⚙ gear (top right) → Export Original. You get a .zip with the .fit inside – import the zip as it is.",
            "Whole history: on garmin.com open Account → Data Management → Export Your Data → Request Data Export. Garmin emails a download link when it's ready (can take hours or days). Import that big zip directly – nested zips are read automatically.",
            "From the watch: connect it to a computer by USB and copy the .fit files from the GARMIN › Activity folder to your phone.",
            "In HealthAnalyzer, tap Import on the Runs tab → \"Garmin FIT files\" and pick one or more .fit or .zip files.",
        ),
        note = "Only running activities are imported (road, treadmill, track, trail). A run also recorded by your Galaxy Watch is kept once.",
        link = "Open Garmin data export ↗" to "https://www.garmin.com/account/datamanagement/",
    ),
    SAMSUNG_DEVELOPER_MODE(
        title = "Enable Samsung Health developer mode",
        summary = "Needed once so live Samsung sync can read your runs",
        accent = SamsungGreen,
        steps = listOf(
            "Open Samsung Health, tap ⋮ (More options) → Settings → About Samsung Health.",
            "Tap the version number about 10 times quickly until a message says developer mode is on.",
            "Go back to Settings. A new \"Developer mode\" item appears near the bottom – open it and turn on \"Developer Mode for Data Read\".",
            "Return to HealthAnalyzer, tap \"Grant Samsung Health\" on the Runs tab, allow access, then tap Sync Samsung.",
        ),
        note = "HealthAnalyzer isn't a registered Samsung partner app, so without this, sync fails with error 2003. It only affects your phone and you can turn it off at any time.",
    ),
    GARMIN_PERSONAL_API(
        title = "Set up the Garmin Personal API",
        summary = "Pull runs straight from Garmin Connect, no files needed",
        accent = GarminBlue,
        steps = listOf(
            "Sign in to Garmin Connect in a browser (the button below opens the right page; use the garmin.cn one for a China account).",
            "Create a Personal API key and pick how long it should last (30–365 days).",
            "Copy the key right away – Garmin shows it only once.",
            "In HealthAnalyzer: Settings → Garmin Connect Integration, paste it into Personal API Key, tick \"Garmin China account\" if yours is on garmin.cn, then tap Connect Garmin.",
            "Tap Sync Garmin on the Runs tab for new runs, or \"Sync All Garmin History\" in Settings for everything.",
        ),
        note = "The key is stored encrypted on this phone. Garmin emails you a week before it expires – create a new key and paste it again.",
        link = "Create a key on Garmin Connect ↗" to GarminPersonalApi.KEY_PAGE,
    ),
}

@Composable
fun GuideCard(guide: SetupGuide, startExpanded: Boolean = false) {
    var expanded by rememberSaveable(guide.name) { mutableStateOf(startExpanded) }
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).padding(0.dp)) {
                    Surface(shape = CircleShape, color = guide.accent, modifier = Modifier.size(8.dp)) {}
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(guide.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(guide.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppIcons.ChevronRight(
                    modifier = Modifier.size(18.dp).rotate(if (expanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    guide.steps.forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            Surface(
                                shape = CircleShape,
                                color = guide.accent.copy(alpha = 0.15f),
                                modifier = Modifier.size(22.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = guide.accent, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        }
                    }
                    Text(
                        guide.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    guide.link?.let { (label, url) ->
                        TextButton(onClick = { uriHandler.openUri(url) }, contentPadding = PaddingValues(0.dp)) {
                            Text(label, color = guide.accent)
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Import" button with a menu: Samsung Health export (folder or zip) and Garmin FIT files.
 * [onHelp] opens the matching guide.
 */
@Composable
fun ImportMenuButton(runsVm: RunsViewModel, enabled: Boolean, onHelp: (SetupGuide) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        runsVm.importSamsungExport(it, isFolder = true)
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        runsVm.importSamsungExport(it, isFolder = false)
    }
    val fitPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        runsVm.importFit(it)
    }
    Box {
        TextButton(
            onClick = { open = true },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) { Text("Import ▾", style = MaterialTheme.typography.labelMedium) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { MenuLabel("Samsung Health export (folder)", "The samsunghealth_… folder") },
                onClick = { open = false; folderPicker.launch(null) },
            )
            DropdownMenuItem(
                text = { MenuLabel("Samsung Health export (.zip)", "The export folder, zipped") },
                onClick = { open = false; zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            )
            DropdownMenuItem(
                text = { MenuLabel("Garmin FIT files", ".fit files or Garmin zips") },
                onClick = { open = false; fitPicker.launch(arrayOf("*/*")) },
            )
            androidx.compose.material3.HorizontalDivider()
            DropdownMenuItem(
                text = { Text("How do I export from Samsung Health?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) },
                onClick = { open = false; onHelp(SetupGuide.SAMSUNG_EXPORT) },
            )
            DropdownMenuItem(
                text = { Text("How do I get Garmin FIT files?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) },
                onClick = { open = false; onHelp(SetupGuide.GARMIN_FIT) },
            )
        }
    }
}

@Composable
private fun MenuLabel(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A guide in a dialog (used from the Runs tab import menu). */
@Composable
fun GuideDialog(guide: SetupGuide, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            androidx.compose.foundation.lazy.LazyColumn { item { GuideCard(guide, startExpanded = true) } }
        },
    )
}

/** Settings section: import buttons, stored-data count and the erase action. */
@Composable
fun DataManagementSection(runsVm: RunsViewModel, activity: android.app.Activity, onHelp: (SetupGuide) -> Unit) {
    val state by runsVm.state.collectAsStateWithLifecycle()
    val runs by runsVm.runs.collectAsStateWithLifecycle()
    var confirmErase by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val bySource = runs.groupingBy { it.sourceOrDefault }.eachCount()
        Text(
            if (runs.isEmpty()) "No runs stored on this phone."
            else "${runs.size} runs stored · " + bySource.entries.joinToString(" · ") { (s, n) ->
                "$n ${if (s == com.healthanalyzer.app.data.SOURCE_GARMIN_FIT) "Garmin" else "Samsung"}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Add data", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            ImportMenuButton(runsVm, enabled = !state.busy, onHelp = onHelp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Resync all Samsung history", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Re-reads every run from Samsung Health. Imported running dynamics are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { runsVm.sync(activity, full = true) },
                enabled = !state.busy && state.permissionsGranted == true,
                shape = RoundedCornerShape(10.dp),
            ) { Text("Resync") }
        }
        state.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.message?.takeIf { !it.startsWith("Garmin") }?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = runsVm::dismissMessage) { Text("OK") }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Erase loaded data", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Deletes all runs from this app. Samsung Health and Garmin Connect keep theirs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { confirmErase = true },
                enabled = runs.isNotEmpty() && !state.busy,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
            ) { Text("Erase", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase ${runs.size} runs?") },
            text = {
                Text(
                    "This removes every run, with its heart-rate samples and GPS route, from HealthAnalyzer. " +
                        "It can't be undone here, but you can load them again with Sync or Import.\n\n" +
                        "Kept: AI providers, prompts, saved analyses, athlete profile and baseline, and your Garmin key.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmErase = false; runsVm.eraseAllRuns() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Erase all runs") }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel") } },
        )
    }
}
