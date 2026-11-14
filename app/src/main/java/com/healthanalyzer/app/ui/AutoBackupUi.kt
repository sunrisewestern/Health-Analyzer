package com.healthanalyzer.app.ui

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.healthanalyzer.app.backup.AutoBackupDestination
import com.healthanalyzer.app.backup.AutoBackupInterval
import com.healthanalyzer.app.backup.AutoBackupRunner
import com.healthanalyzer.app.backup.AutoBackupScheduler
import com.healthanalyzer.app.backup.AutoBackupSettings
import com.healthanalyzer.app.backup.AutoBackupStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AutoBackupState(
    val settings: AutoBackupSettings = AutoBackupSettings(),
    val local: AutoBackupStatus? = null,
    val webDav: AutoBackupStatus? = null,
    val running: Boolean = false,
)

class AutoBackupViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(AutoBackupSettings.PREFS, Application.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AutoBackupState> = _state.asStateFlow()

    // Scheduled runs write their result to the same preferences while this screen is open.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.contains("_last_")) _state.update { it.copy(local = status(AutoBackupDestination.LOCAL), webDav = status(AutoBackupDestination.WEBDAV)) }
    }

    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    override fun onCleared() { prefs.unregisterOnSharedPreferenceChangeListener(listener) }

    private fun status(dest: AutoBackupDestination) = AutoBackupStatus.load(getApplication(), dest)

    private fun read() = AutoBackupState(
        settings = AutoBackupSettings.load(getApplication()),
        local = status(AutoBackupDestination.LOCAL),
        webDav = status(AutoBackupDestination.WEBDAV),
    )

    /** Saves the change and brings the background schedule in line with it. */
    fun update(reschedule: Boolean = true, change: (AutoBackupSettings) -> AutoBackupSettings) {
        val next = change(_state.value.settings)
        AutoBackupSettings.save(getApplication(), next)
        _state.update { it.copy(settings = next) }
        // save() is asynchronous on disk but the scheduler reads the in-memory preferences, which are already current.
        if (reschedule) runCatching { AutoBackupScheduler.sync(getApplication(), replace = true) }
    }

    /** Keeps access to the picked folder across restarts and gives up the previous one. */
    fun setFolder(uri: Uri?) {
        if (uri == null) return
        val resolver = getApplication<Application>().contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
        val old = _state.value.settings.folderUri
        if (old.isNotBlank() && old != uri.toString()) runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        val label = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "Storage root" } }
            .getOrDefault(uri.lastPathSegment.orEmpty())
        update { it.copy(folderUri = uri.toString(), folderLabel = label) }
    }

    fun backUpNow() {
        val s = _state.value.settings
        val destinations = AutoBackupDestination.entries.filter { s.enabled(it) }.toSet()
        if (destinations.isEmpty() || _state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true) }
            try {
                AutoBackupRunner(getApplication()).run(destinations)
            } finally {
                _state.update { read().copy(running = false) }
            }
        }
    }
}

private val STATUS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * The automatic-backup switch for one destination, shown inside that destination's tab of the
 * backup section. The local tab also holds the folder choice.
 */
@Composable
fun AutoBackupDestinationRow(
    dest: AutoBackupDestination,
    webDavReady: Boolean,
    vm: AutoBackupViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    // Switching on before a folder is chosen goes straight to the folder picker.
    var enableAfterPick by rememberSaveable { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.setFolder(uri)
        if (uri != null && enableAfterPick) vm.update { it.copy(localEnabled = true) }
        enableAfterPick = false
    }
    val local = dest == AutoBackupDestination.LOCAL

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DestinationRow(
            title = if (local) "Automatic backup to this phone" else "Automatic backup to WebDAV",
            subtitle = when {
                local -> if (s.folderUri.isBlank()) "No folder chosen yet" else "Folder: ${s.folderLabel}"
                !webDavReady -> "Save the WebDAV login above first"
                else -> "Uses the WebDAV folder above"
            },
            checked = s.enabled(dest),
            enabled = local || webDavReady || s.webDavEnabled,
            onChange = { on ->
                when {
                    local && on && s.folderUri.isBlank() -> { enableAfterPick = true; pickFolder.launch(null) }
                    local -> vm.update { it.copy(localEnabled = on) }
                    else -> vm.update { it.copy(webDavEnabled = on) }
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s.enabled(dest)) StatusLine(if (local) state.local else state.webDav, Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            if (local) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { pickFolder.launch(null) }, shape = RoundedCornerShape(10.dp)) {
                    Text(if (s.folderUri.isBlank()) "Choose folder" else "Change folder")
                }
            }
        }
    }
}

/** Settings section: when and how automatic backups run. Destinations are switched on in the tabs above. */
@Composable
fun AutoBackupSection(vm: AutoBackupViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    var password by rememberSaveable { mutableStateOf(s.password) }
    val targets = listOfNotNull("this phone".takeIf { s.localEnabled }, "WebDAV".takeIf { s.webDavEnabled })

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Automatic backup", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Switch it on under “File on this phone” or “WebDAV” above. Older automatic backups are removed; " +
                "backups you make by hand are never touched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker(
                "Interval", s.interval, AutoBackupInterval.entries, { it.label },
                { picked -> vm.update { it.copy(interval = picked) } },
                Modifier.weight(1f),
            )
            Picker(
                "Keep", s.keep, AutoBackupSettings.KEEP_OPTIONS, { "Newest $it" },
                { picked -> vm.update { it.copy(keep = picked) } },
                Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            password,
            { typed -> password = typed; vm.update(reschedule = false) { it.copy(password = typed) } },
            label = { Text("Backup password (optional)") },
            supportingText = {
                Text(
                    when {
                        password.isEmpty() -> "Without a password, automatic backups are not encrypted and hold running data only."
                        password.length < AutoBackupSettings.MIN_PASSWORD -> "Too short to be used – needs ${AutoBackupSettings.MIN_PASSWORD}+ characters."
                        else -> "Automatic backups are encrypted. You'll need this password to restore – it can't be recovered."
                    },
                )
            },
            isError = password.isNotEmpty() && password.length < AutoBackupSettings.MIN_PASSWORD,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = PASSWORD_KEYS,
            singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Include settings & API keys", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Running data and AI history are always backed up. Settings contain your API keys, so they need the password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = s.includeConfig && s.effectivePassword != null,
                onCheckedChange = { on -> vm.update(reschedule = false) { it.copy(includeConfig = on) } },
                enabled = s.effectivePassword != null,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    state.running -> "Backing up…"
                    targets.isEmpty() -> "Not switched on for this phone or WebDAV yet."
                    s.interval == AutoBackupInterval.OFF -> "Schedule is off – only “Back up now” runs."
                    else -> "${s.interval.label} to ${targets.joinToString(" and ")}, keeping the newest ${s.keep}."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = vm::backUpNow,
                enabled = !state.running && targets.isNotEmpty(),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Back up now") }
        }
    }
}

@Composable
private fun DestinationRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Time and result of the last run to one destination; failures in the error colour. */
@Composable
private fun StatusLine(status: AutoBackupStatus?, modifier: Modifier = Modifier) {
    val text = status?.let {
        val time = Instant.ofEpochMilli(it.atMs).atZone(ZoneId.systemDefault()).format(STATUS_TIME)
        if (it.ok) "Last backup $time · ${it.message}" else "Failed $time · ${it.message}"
    } ?: "No backup yet"
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (status?.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
