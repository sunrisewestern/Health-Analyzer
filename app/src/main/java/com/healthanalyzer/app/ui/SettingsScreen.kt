package com.healthanalyzer.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthanalyzer.app.ai.AthleteProfile
import com.healthanalyzer.app.ai.Baseline
import com.healthanalyzer.app.ai.CodexAuth
import com.healthanalyzer.app.ai.PROVIDER_PRESETS
import com.healthanalyzer.app.ai.PromptRenderer
import com.healthanalyzer.app.ai.PromptTemplateEntity
import com.healthanalyzer.app.ai.Protocol
import com.healthanalyzer.app.data.GarminPersonalApi
import com.healthanalyzer.app.ui.theme.GarminBlue
import com.healthanalyzer.app.ui.theme.SamsungGreen
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: AiViewModel, activity: Activity, runsVm: RunsViewModel) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val prompts by vm.prompts.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val baseline by vm.baseline.collectAsStateWithLifecycle()
    val useBaseline by vm.useBaseline.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()

    var editingProvider by remember { mutableStateOf<ProviderForm?>(null) }
    var editingPrompt by remember { mutableStateOf<PromptTemplateEntity?>(null) }
    var editingBaseline by remember { mutableStateOf(false) }
    var helpGuide by remember { mutableStateOf<SetupGuide?>(null) }
    val backupVm: BackupViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    helpGuide?.let { GuideDialog(it, onDismiss = { helpGuide = null }) }

    Column {
        val provider = editingProvider
        val prompt = editingPrompt
        when {
            provider != null -> {
                BackHandler { editingProvider = null; vm.clearModels(); vm.clearTest(); vm.resetCodexEditorState() }
                ProviderEditor(vm, provider, onDone = { editingProvider = null; vm.clearModels(); vm.clearTest(); vm.resetCodexEditorState() })
            }
            prompt != null -> {
                BackHandler { editingPrompt = null }
                PromptEditor(prompt, onSave = { vm.savePrompt(it); editingPrompt = null }, onCancel = { editingPrompt = null })
            }
            editingBaseline -> {
                BackHandler { editingBaseline = false }
                BaselineEditor(baseline?.text.orEmpty(), onSave = { vm.editBaseline(it); editingBaseline = false }, onCancel = { editingBaseline = false })
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text(
                        "Settings & Preferences",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Data import & storage
                item { SectionHeader("Data Sources & Storage") }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            DataManagementSection(runsVm, activity, onHelp = { helpGuide = it })
                        }
                    }
                }

                // Backup & restore
                item { SectionHeader("Backup & Restore") }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            BackupSection(backupVm, onRestored = { kind ->
                                if (kind == com.healthanalyzer.app.backup.BackupKind.CONFIG) {
                                    vm.reloadFromStorage()
                                    runsVm.refreshGarminState()
                                } else {
                                    vm.openChat(null)
                                }
                            })
                        }
                    }
                }

                // Setup guides
                item { SectionHeader("Setup Guides") }
                items(SetupGuide.entries, key = { "g${it.name}" }) { GuideCard(it) }

                // Section 1: Garmin Connect
                item { SectionHeader("Garmin Connect Integration") }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            GarminSection(runsVm)
                        }
                    }
                }

                // Section 2: AI Providers
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SectionHeader("AI Model Providers")
                        TextButton(onClick = { editingProvider = ProviderForm() }) {
                            Text("+ Add Provider")
                        }
                    }
                }
                items(providers, key = { "p${it.id}" }) { p ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { editingProvider = vm.formFor(p) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(p.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    StatusBadge(
                                        text = p.protocol.label,
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    "Model: ${p.model.ifBlank { "Not set" }}\n${p.baseUrl}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.deleteProvider(p) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // Section 3: Prompts
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SectionHeader("Prompt Templates")
                        Row {
                            TextButton(onClick = vm::restoreDefaultPrompts) { Text("Defaults") }
                            TextButton(onClick = {
                                editingPrompt = PromptTemplateEntity(name = "", systemPrompt = "", userTemplate = "{{athlete}}\n\n{{run}}")
                            }) { Text("+ New") }
                        }
                    }
                }
                items(prompts, key = { "t${it.id}" }) { t ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { editingPrompt = t },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(t.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    if (t.savesBaseline) {
                                        StatusBadge(
                                            text = "Saves Baseline ★",
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            contentColor = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                                Text(
                                    t.userTemplate.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("{{") }.orEmpty(),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.deletePrompt(t) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // Section 4: Athlete Baseline
                item { SectionHeader("Athlete Baseline") }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            BaselineSection(
                                baseline = baseline,
                                useBaseline = useBaseline,
                                onUseChange = vm::setUseBaseline,
                                onEdit = { editingBaseline = true },
                                onClear = vm::clearBaseline,
                            )
                        }
                    }
                }

                // Section 5: Athlete Profile
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SectionHeader("Athlete Biometric Profile")
                        OutlinedButton(
                            onClick = { vm.importProfileFromSamsung(activity) },
                            enabled = !importing,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(if (importing) "Importing…" else "Import Samsung Health")
                        }
                    }
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Profile data is automatically formatted into prompts via {{athlete}} placeholder." +
                                    (profile.importedFrom?.let { "\nLast imported: $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ProfileEditor(profile, onSave = vm::saveProfile)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ProviderEditor(vm: AiViewModel, initial: ProviderForm, onDone: () -> Unit) {
    var form by remember { mutableStateOf(initial) }
    val models by vm.models.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    onClick = onDone,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AppIcons.ArrowLeft(modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text(if (initial.id == 0L) "Add Provider" else "Edit Provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(38.dp))
            }
        }
        if (initial.id == 0L) {
            item {
                Picker("Start from a Preset", null, PROVIDER_PRESETS, { "${it.name} (${it.protocol.label})" }, { preset ->
                    form = form.copy(
                        name = preset.name,
                        protocol = preset.protocol,
                        baseUrl = preset.baseUrl,
                        model = preset.defaultModel,
                    )
                    vm.clearModels()
                })
            }
        }
        item {
            OutlinedTextField(
                form.name,
                { form = form.copy(name = it) },
                label = { Text("Provider Name") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Picker("Protocol", form.protocol, Protocol.entries, { it.label }, { form = form.copy(protocol = it) })
        }
        item {
            OutlinedTextField(
                form.baseUrl,
                { form = form.copy(baseUrl = it) },
                label = { Text("Base URL") },
                supportingText = {
                    Text(
                        when (form.protocol) {
                            Protocol.OPENAI -> "Up to /v1 – the app adds /chat/completions"
                            Protocol.ANTHROPIC -> "e.g. https://api.anthropic.com (no /v1)"
                            Protocol.GEMINI -> "e.g. https://generativelanguage.googleapis.com"
                            Protocol.CHATGPT -> "Default: ${CodexAuth.BASE_URL}"
                        },
                    )
                },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (form.protocol == Protocol.CHATGPT) {
            item { CodexSignInCard(vm, form) }
        } else item {
            OutlinedTextField(
                form.apiKey,
                { form = form.copy(apiKey = it) },
                label = { Text("API Key") },
                placeholder = { Text(if (form.hasStoredKey) "Saved – leave blank to keep" else "Not needed for local servers") },
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                form.model,
                { form = form.copy(model = it) },
                label = { Text("Model ID") },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { vm.fetchModels(form) }, enabled = form.baseUrl.length > 8) { Text("Fetch Available Models") }
                if (models.isNotEmpty()) {
                    Picker("", null, models, { it }, { form = form.copy(model = it) }, placeholder = "Pick from ${models.size}…")
                }
            }
        }
        item {
            OutlinedTextField(
                form.maxTokens,
                { form = form.copy(maxTokens = it.filter(Char::isDigit)) },
                label = { Text("Max Output Tokens") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                form.extraSystem,
                { form = form.copy(extraSystem = it) },
                label = { Text("Extra System Instructions (Optional)") },
                shape = RoundedCornerShape(12.dp),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            val test by vm.test.collectAsStateWithLifecycle()
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Connection Test", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { vm.testProvider(form) },
                            enabled = test != ProviderTestState.Running && form.baseUrl.length > 8 && form.model.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text(if (test == ProviderTestState.Running) "Testing…" else "Test Connection") }
                    }
                    when (val t = test) {
                        ProviderTestState.Idle -> Text(
                            "Sends a lightweight test ping to verify endpoint and API key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ProviderTestState.Running -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
                        is ProviderTestState.Passed -> Text(
                            "✓ Verified · First token: ${t.firstTokenMs} ms · Total: ${t.totalMs} ms\nReply: ${t.reply}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        is ProviderTestState.Failed -> Text(
                            "✗ Connection failed: ${t.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item {
            Button(
                onClick = { vm.saveProvider(form); vm.clearTest(); onDone() },
                enabled = form.baseUrl.isNotBlank() && form.model.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save Provider", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun PromptEditor(initial: PromptTemplateEntity, onSave: (PromptTemplateEntity) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var system by remember { mutableStateOf(initial.systemPrompt) }
    var user by remember { mutableStateOf(initial.userTemplate) }
    var savesBaseline by remember { mutableStateOf(initial.savesBaseline) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    onClick = onCancel,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AppIcons.ArrowLeft(modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text(if (initial.id == 0L) "New Prompt Template" else "Edit Prompt Template", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(38.dp))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = savesBaseline, onCheckedChange = { savesBaseline = it })
                Text("Save generated answer as athlete baseline ★", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("Prompt Name") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                system,
                { system = it },
                label = { Text("System Prompt") },
                shape = RoundedCornerShape(12.dp),
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                user,
                { user = it },
                label = { Text("User Message Template") },
                shape = RoundedCornerShape(12.dp),
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Supported Placeholders", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(PromptRenderer.PLACEHOLDER_HELP, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Button(
                onClick = {
                    onSave(initial.copy(name = name.trim(), systemPrompt = system, userTemplate = user, savesBaseline = savesBaseline))
                },
                enabled = name.isNotBlank() && user.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save Prompt Template", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ProfileEditor(profile: AthleteProfile, onSave: (AthleteProfile) -> Unit) {
    var maxHr by remember(profile) { mutableStateOf(profile.maxHeartRate?.toString().orEmpty()) }
    var restHr by remember(profile) { mutableStateOf(profile.restingHeartRate?.toString().orEmpty()) }
    var birthDate by remember(profile) { mutableStateOf(profile.birthDate?.toString().orEmpty()) }
    var sex by remember(profile) { mutableStateOf(profile.sex.orEmpty()) }
    var height by remember(profile) { mutableStateOf(profile.heightCm?.let { "%.0f".format(it) }.orEmpty()) }
    var weight by remember(profile) { mutableStateOf(profile.weightKg?.let { "%.1f".format(it) }.orEmpty()) }
    var bodyFat by remember(profile) { mutableStateOf(profile.bodyFatPercent?.let { "%.1f".format(it) }.orEmpty()) }
    var notes by remember(profile) { mutableStateOf(profile.notes) }
    var language by remember(profile) { mutableStateOf(profile.language) }

    @Composable
    fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, decimal: Boolean = false) =
        OutlinedTextField(
            value,
            { v -> onChange(v.filter { it.isDigit() || (decimal && it == '.') }) },
            label = { Text(label) },
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
            singleLine = true,
            modifier = modifier,
        )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                birthDate,
                { birthDate = it },
                label = { Text("Birth Date") },
                placeholder = { Text("yyyy-mm-dd") },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.weight(1.4f),
            )
            OutlinedTextField(
                sex,
                { sex = it },
                label = { Text("Sex") },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(height, { height = it }, "Height (cm)", Modifier.weight(1f), decimal = true)
            NumberField(weight, { weight = it }, "Weight (kg)", Modifier.weight(1f), decimal = true)
            NumberField(bodyFat, { bodyFat = it }, "Body Fat %", Modifier.weight(1f), decimal = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(maxHr, { maxHr = it }, "Max HR (bpm)", Modifier.weight(1f))
            NumberField(restHr, { restHr = it }, "Resting HR", Modifier.weight(1f))
        }
        Text(
            "Leave Max HR empty to auto-detect from your highest recorded run.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            language,
            { language = it },
            label = { Text("Response Language") },
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            notes,
            { notes = it },
            label = { Text("Athlete Notes & Target Races") },
            placeholder = { Text("e.g. training for Boston Marathon, recovering from Achilles tightness") },
            shape = RoundedCornerShape(12.dp),
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onSave(
                    profile.copy(
                        maxHeartRate = maxHr.toIntOrNull(),
                        restingHeartRate = restHr.toIntOrNull(),
                        birthDate = runCatching { LocalDate.parse(birthDate.trim()) }.getOrNull(),
                        sex = sex.trim().ifBlank { null },
                        heightCm = height.toFloatOrNull(),
                        weightKg = weight.toFloatOrNull(),
                        bodyFatPercent = bodyFat.toFloatOrNull(),
                        notes = notes,
                        language = language.ifBlank { "English" },
                    ),
                )
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save Athlete Profile", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun BaselineSection(
    baseline: Baseline?,
    useBaseline: Boolean,
    onUseChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Inject Baseline into Analyses", fontWeight = FontWeight.SemiBold)
                Text(
                    "Automatically provides your athlete baseline context to prompts that don't already define {{baseline}}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = useBaseline, onCheckedChange = onUseChange)
        }
        if (baseline == null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "No baseline saved yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Run the \"Build athlete baseline\" prompt in the Analyze tab, or manually write your athletic background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.Start)) {
                        Text("Write Baseline Manually")
                    }
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(baseline.header(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    if (expanded) {
                        MarkdownText(baseline.text)
                    } else {
                        Text(
                            baseline.text.take(200) + if (baseline.text.length > 200) "… (tap to expand)" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(10.dp)) { Text("Edit Baseline") }
                TextButton(onClick = onClear) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun BaselineEditor(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    onClick = onCancel,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AppIcons.ArrowLeft(modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text("Edit Athlete Baseline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(38.dp))
            }
        }
        item {
            OutlinedTextField(
                text,
                { text = it },
                minLines = 12,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Baseline Text (Markdown)") },
            )
        }
        item {
            Button(
                onClick = { onSave(text) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save Baseline", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun CodexSignInCard(vm: AiViewModel, form: ProviderForm) {
    val login by vm.codexLogin.collectAsStateWithLifecycle()
    val credentials by vm.codexCredentials.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val account = remember(credentials, form.id) { vm.codexAccount(form) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sign in with ChatGPT", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Uses your ChatGPT plan's Codex allowance instead of an API key, through the same sign-in " +
                    "as OpenAI's Codex CLI. This isn't an official API for other apps: OpenAI may change or block " +
                    "it, and using it may conflict with OpenAI's terms. Use at your own risk.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            account?.let {
                StatusBadge(
                    text = "Signed in as: $it",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                )
            }
            when (val s = login) {
                CodexLoginState.Idle, is CodexLoginState.SignedIn ->
                    OutlinedButton(
                        onClick = vm::startCodexLogin,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(if (account == null) "Sign in with ChatGPT" else "Sign in again")
                    }
                CodexLoginState.Requesting -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
                is CodexLoginState.WaitingForUser -> {
                    Text("1. Open the sign-in page and log in to ChatGPT.\n2. Enter this code (valid 15 minutes):")
                    Text(s.code, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                clipboard.setText(AnnotatedString(s.code))
                                uriHandler.openUri(s.url)
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Copy code & open page") }
                        TextButton(onClick = vm::cancelCodexLogin) { Text("Cancel") }
                    }
                    Text("Waiting for authorization…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
                }
                is CodexLoginState.Failed -> {
                    Text("✗ ${s.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = vm::startCodexLogin, shape = RoundedCornerShape(10.dp)) { Text("Try again") }
                }
            }
            if (credentials != null && form.id == 0L) {
                Text("Tap Save below to keep this sign-in.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GarminSection(runsVm: RunsViewModel) {
    val state by runsVm.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var key by remember { mutableStateOf("") }
    var china by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.garminConnected) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(
                    text = "Connected",
                    containerColor = GarminBlue.copy(alpha = 0.15f),
                    contentColor = GarminBlue,
                    dotColor = GarminBlue,
                )
                Text(
                    state.garminLastSync?.let { "Last synced: $it" } ?: "Ready to sync",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Personal API key is active. Garmin will email a week before key expiration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { runsVm.syncGarmin(full = true) },
                    enabled = !state.busy,
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Sync All Garmin History") }
                TextButton(onClick = runsVm::disconnectGarmin) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            Text(
                "Pull all your running workouts (with high-precision FIT telemetry) directly from Garmin Connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    uriHandler.openUri(if (china) GarminPersonalApi.KEY_PAGE_CN else GarminPersonalApi.KEY_PAGE)
                },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Create a key on Garmin Connect ↗", color = GarminBlue) }
            OutlinedTextField(
                key,
                { key = it },
                label = { Text("Personal API Key") },
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = china, onCheckedChange = { china = it })
                Text("Garmin China account (garmin.cn)", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { runsVm.connectGarmin(key, china); key = "" },
                enabled = key.isNotBlank() && !state.busy,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (state.busy) "Connecting…" else "Connect Garmin")
            }
        }
        state.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.message?.takeIf { it.startsWith("Garmin") }?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = runsVm::dismissMessage) { Text("OK") }
            }
        }
    }
}
