package com.healthanalyzer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthanalyzer.app.backup.BackupKind
import com.healthanalyzer.app.backup.WebDavSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PASSWORD_KEYS = androidx.compose.foundation.text.KeyboardOptions(
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
    autoCorrectEnabled = false,
)

/** Settings section: back up / restore settings and data, to a file on the phone or WebDAV. */
@Composable
fun BackupSection(vm: BackupViewModel, onRestored: (BackupKind) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var toWebDav by rememberSaveable { mutableStateOf(false) }
    var asking by remember { mutableStateOf<BackupKind?>(null) }
    // Kept across the system file-save screen.
    var pendingKind by remember { mutableStateOf<BackupKind?>(null) }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val kind = pendingKind
        if (uri != null && kind != null) vm.backupToUri(kind, pendingPassword, uri)
        pendingKind = null; pendingPassword = null
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.pickLocal(it) }

    LaunchedEffect(state.restored) {
        state.restored?.let { onRestored(it); vm.consumeRestored() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SegmentedSwitcher(
            selectedIndex = if (toWebDav) 1 else 0,
            options = listOf("File on this phone", "WebDAV"),
            onSelect = { toWebDav = it == 1 },
        )
        if (toWebDav) WebDavForm(state.webdav, busy = state.busy, onSave = vm::saveWebDav)
        val ready = !state.busy && (!toWebDav || state.webdav.isSet)

        BackupRow(
            title = "Settings & API keys",
            subtitle = "AI providers with keys, prompts, athlete profile, baseline, Garmin key, WebDAV login. Always password-protected.",
            enabled = ready,
            onBackup = { asking = BackupKind.CONFIG },
        )
        BackupRow(
            title = "Running data & AI history",
            subtitle = "All runs with samples and GPS routes, saved AI analyses and chats.",
            enabled = ready,
            onBackup = { asking = BackupKind.DATA },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Restore from a backup", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Settings or data – the file says which. You'll see what's in it before anything is replaced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { if (toWebDav) vm.listRemote() else openFile.launch(arrayOf("*/*")) },
                enabled = ready,
                shape = RoundedCornerShape(10.dp),
            ) { Text("Restore") }
        }
        state.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        state.message?.takeIf { it.isNotBlank() }?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::dismissMessage) { Text("OK") }
            }
        }
    }

    asking?.let { kind ->
        PasswordDialog(
            kind = kind,
            onDismiss = { asking = null },
            onConfirm = { password ->
                asking = null
                if (toWebDav) vm.backupToWebDav(kind, password)
                else {
                    pendingKind = kind; pendingPassword = password
                    saveFile.launch(vm.fileName(kind, password != null))
                }
            },
        )
    }
    state.remote?.let { files ->
        AlertDialog(
            onDismissRequest = vm::closeRemote,
            title = { Text("Backups on WebDAV") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(files, key = { it.name }) { f ->
                        Column(Modifier.fillMaxWidth().clickable { vm.pickRemote(f) }.padding(vertical = 8.dp)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(
                                    f.modifiedEpochMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) },
                                    f.sizeBytes?.let { if (it > 1 shl 20) "%.1f MB".format(it / 1048576.0) else "%.0f KB".format(it / 1024.0) },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeRemote) { Text("Close") } },
        )
    }
    state.pending?.let { RestoreDialog(vm, it) }
}

@Composable
private fun BackupRow(title: String, subtitle: String, enabled: Boolean, onBackup: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.width(8.dp))
        Button(onClick = onBackup, enabled = enabled, shape = RoundedCornerShape(10.dp)) { Text("Back up") }
    }
}

@Composable
private fun WebDavForm(saved: WebDavSettings, busy: Boolean, onSave: (WebDavSettings) -> Unit) {
    var url by rememberSaveable { mutableStateOf(saved.url) }
    var user by rememberSaveable { mutableStateOf(saved.user) }
    var password by rememberSaveable { mutableStateOf(saved.password) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            url, { url = it },
            label = { Text("Folder URL") },
            placeholder = { Text("https://dav.jianguoyun.com/dav/HealthAnalyzer/") },
            supportingText = { Text("Nextcloud: https://<server>/remote.php/dav/files/<user>/HealthAnalyzer/") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled = false),
            singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email, autoCorrectEnabled = false), singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            password, { password = it },
            label = { Text("Password / app password") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = PASSWORD_KEYS,
            singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (saved.isSet) "Saved: ${saved.url}" else "Not set up yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            OutlinedButton(
                onClick = { onSave(WebDavSettings(url.trim(), user.trim(), password)) },
                enabled = !busy && url.trim().startsWith("http"),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Test & save") }
        }
    }
}

@Composable
private fun PasswordDialog(kind: BackupKind, onDismiss: () -> Unit, onConfirm: (CharArray?) -> Unit) {
    val required = kind == BackupKind.CONFIG
    var encrypt by remember { mutableStateOf(true) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val useIt = required || encrypt
    val valid = !useIt || (pw.length >= 6 && pw == pw2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Back up ${kind.label.lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (required) "This backup contains your API keys and Garmin key in readable form, so it's encrypted with a password. " +
                        "You'll need the password to restore – it can't be recovered."
                    else "Your runs include GPS routes and heart-rate data. Encrypting is recommended if the file leaves your phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!required) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { encrypt = !encrypt }) {
                        Checkbox(checked = encrypt, onCheckedChange = { encrypt = it })
                        Text("Encrypt with a password", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (useIt) {
                    OutlinedTextField(pw, { pw = it }, label = { Text("Password (6+ characters)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = PASSWORD_KEYS, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        pw2, { pw2 = it }, label = { Text("Repeat password") },
                        isError = pw2.isNotEmpty() && pw2 != pw,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = PASSWORD_KEYS, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(if (useIt) pw.toCharArray() else null) }, enabled = valid) { Text("Back up") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RestoreDialog(vm: BackupViewModel, p: PendingRestore) {
    var pw by remember(p.file) { mutableStateOf("") }
    val opened = p.opened
    val state by vm.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = vm::cancelRestore,
        title = { Text(if (opened == null) "Unlock backup" else "Restore ${opened.manifest.kind.label.lowercase()}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (opened == null) {
                    Text("${p.sourceName} is password-protected.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        pw, { pw = it }, label = { Text("Backup password") },
                        isError = p.error != null,
                        supportingText = p.error?.let { { Text(it) } },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = PASSWORD_KEYS, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    val m = opened.manifest
                    Text("From ${m.device}, ${m.createdAt.replace('T', ' ')}", style = MaterialTheme.typography.bodyMedium)
                    Text("Contains: ${m.summary()}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        when (m.kind) {
                            BackupKind.CONFIG -> "Replaces your AI providers, prompts, athlete profile, baseline, Garmin connection and WebDAV login. " +
                                "Runs, analyses and chats are not touched."
                            BackupKind.DATA -> "Replaces ALL runs, saved analyses and chats on this phone with the backup's. " +
                                "Settings and API keys are not touched."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (opened == null) Button(onClick = { vm.unlock(pw.toCharArray()) }, enabled = pw.isNotEmpty() && !state.busy) { Text("Unlock") }
            else Button(onClick = vm::confirmRestore) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } },
    )
}
