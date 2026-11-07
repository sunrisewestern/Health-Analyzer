package com.healthanalyzer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthanalyzer.app.ai.AnalysisEntity
import com.healthanalyzer.app.ai.PromptRenderer
import java.text.DateFormat
import java.util.Date

@Composable
fun AnalyzeScreen(vm: AiViewModel, onOpenSettings: () -> Unit) {
    val chatMode by vm.chatMode.collectAsStateWithLifecycle()
    Column {
        SegmentedSwitcher(
            selectedIndex = if (chatMode) 1 else 0,
            options = listOf("Analyze", "Chat"),
            onSelect = { vm.setChatMode(it == 1) },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        if (chatMode) ChatPane(vm, onOpenSettings) else AnalyzePane(vm, onOpenSettings)
    }
}

@Composable
private fun AnalyzePane(vm: AiViewModel, onOpenSettings: () -> Unit) {
    val state by vm.analyze.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val prompts by vm.prompts.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val history by vm.analyses.collectAsStateWithLifecycle()
    val baseline by vm.baseline.collectAsStateWithLifecycle()
    val useBaseline by vm.useBaseline.collectAsStateWithLifecycle()
    var showPrompt by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val viewing = state.viewing
    if (viewing != null) {
        BackHandler { vm.view(null) }
        SavedAnalysisView(
            viewing,
            onBack = { vm.view(null) },
            onDelete = { vm.deleteAnalysis(viewing) },
            onSaveBaseline = { vm.saveAnalysisAsBaseline(viewing) },
            onChat = { vm.newChat(analysisId = viewing.id) },
        )
        return
    }

    val provider = providers.firstOrNull { it.id == state.providerId } ?: providers.firstOrNull()
    val prompt = prompts.firstOrNull { it.id == state.promptId } ?: prompts.firstOrNull()
    val usesRun = prompt?.let { "{{run}}" in it.userTemplate || "{{run}}" in it.systemPrompt } == true
    val run = runs.firstOrNull { it.id == state.runId }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // AI Coach Header
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcons.Sparkles(modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
                Column {
                    Text(
                        "AI Health Coach",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Actionable insights from your biometric running data",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Empty Providers Warning
        if (providers.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "No AI Provider Configured",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Add an AI provider (OpenAI, Claude, Gemini, or ChatGPT Codex) in Settings to analyze your workouts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onOpenSettings,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Configure in Settings")
                        }
                    }
                }
            }
        }

        // Setup Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Analysis Configuration",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Picker(
                        label = "AI Model Provider",
                        selected = provider,
                        options = providers,
                        display = { "${it.name} (${it.model.ifBlank { it.protocol.label }})" },
                        onSelect = { vm.select(providerId = it.id) },
                    )

                    Picker(
                        label = "Prompt Template",
                        selected = prompt,
                        options = prompts,
                        display = { it.name + if (it.savesBaseline) " ★ (Saves Baseline)" else "" },
                        onSelect = { vm.select(promptId = it.id) },
                    )

                    if (usesRun) {
                        Picker(
                            label = "Target Workout Run",
                            selected = run,
                            options = runs.take(60),
                            display = { PromptRenderer.runLabel(it) },
                            onSelect = { vm.selectRun(it.id) },
                            placeholder = runs.firstOrNull()?.let { "Latest: " + PromptRenderer.runLabel(it) } ?: "No runs synced",
                        )
                    }

                    val includesBaseline = prompt?.let { "{{baseline}}" in it.userTemplate || "{{baseline}}" in it.systemPrompt } == true
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Use Athlete Baseline",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    when {
                                        baseline == null -> "No baseline yet – run \"Build athlete baseline\""
                                        prompt?.savesBaseline == true -> "This prompt updates the baseline"
                                        includesBaseline -> "Prompt already includes {{baseline}}"
                                        else -> baseline!!.header()
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = useBaseline && baseline != null,
                                onCheckedChange = vm::setUseBaseline,
                                enabled = baseline != null && prompt?.savesBaseline != true && !includesBaseline,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }

                    // Action Button
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.running) {
                            OutlinedButton(
                                onClick = vm::stop,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Stop Analysis")
                            }
                        } else {
                            Button(
                                onClick = vm::start,
                                enabled = provider != null && prompt != null,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    AppIcons.Sparkles(modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                    Text("Analyze with AI", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (state.sentUser.isNotEmpty()) {
                            TextButton(
                                onClick = { showPrompt = !showPrompt },
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(if (showPrompt) "Hide Prompt" else "View Prompt")
                            }
                        }
                    }

                    if (state.running) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "Generating athletic analysis…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        state.error?.let { err ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Error: $err",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        if (showPrompt && state.sentUser.isNotEmpty()) {
            item { PromptBlock(state.sentSystem, state.sentUser) }
        }

        // Live / Current Output Card
        if (state.output.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Analysis Result",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = { clipboard.setText(AnnotatedString(state.output)) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text("Copy 📋", style = MaterialTheme.typography.labelSmall)
                                }
                                if (!state.running && prompt?.savesBaseline != true) {
                                    TextButton(
                                        onClick = vm::saveCurrentOutputAsBaseline,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text("Save Baseline ★", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        MarkdownText(state.output)
                    }
                }
            }
        }

        // History Section
        if (history.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Analysis History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${history.size} reports",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(history, key = { it.id }) { a ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.view(a) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                a.promptName,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            StatusBadge(
                                text = "${a.providerName} · ${a.model}",
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Text(
                            listOfNotNull(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(a.createdEpochMs)),
                                a.runLabel,
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (a.error != null) {
                            Text(
                                "Error: ${a.error}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedAnalysisView(
    a: AnalysisEntity,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onSaveBaseline: () -> Unit,
    onChat: () -> Unit,
) {
    var showPrompt by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    onClick = onBack,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AppIcons.ArrowLeft(modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (a.error == null && a.output.isNotBlank()) {
                        TextButton(onClick = onChat) { Text("Chat 💬") }
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(a.output)) }) {
                        Text("Copy 📋")
                    }
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(a.promptName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(a.createdEpochMs)),
                        "${a.providerName} · ${a.model}",
                        a.runLabel,
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showPrompt = !showPrompt }) {
                        Text(if (showPrompt) "Hide prompt sent" else "View prompt sent")
                    }
                    TextButton(onClick = onSaveBaseline) {
                        Text("Save as athlete baseline ★")
                    }
                }
            }
        }

        if (showPrompt) {
            item { PromptBlock(a.systemText, a.userText) }
        }

        a.error?.let {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                }
            }
        }

        if (a.output.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                ) {
                    MarkdownText(a.output, Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PromptBlock(system: String, user: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (system.isNotBlank()) {
                Text("System Prompt", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(system, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            Text("User Message Template", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(user, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
