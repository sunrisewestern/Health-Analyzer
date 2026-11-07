package com.healthanalyzer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthanalyzer.app.ai.AnalysisEntity
import com.healthanalyzer.app.ai.ChatEntity
import com.healthanalyzer.app.ai.ChatMessageEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
private fun whenText(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(WHEN)

/** Chat mode of the Analyze tab: conversation list, or the open conversation. */
@Composable
fun ChatPane(vm: AiViewModel, onOpenSettings: () -> Unit) {
    val openId by vm.openChatId.collectAsStateWithLifecycle()
    if (openId == null) ChatList(vm, onOpenSettings) else {
        BackHandler { vm.openChat(null) }
        ChatConversation(vm)
    }
}

@Composable
private fun ChatList(vm: AiViewModel, onOpenSettings: () -> Unit) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Coach Chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Ask follow-up questions with your baseline and past analyses as context",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { vm.newChat() }, enabled = providers.isNotEmpty(), shape = RoundedCornerShape(10.dp)) {
                    Text("+ New chat")
                }
            }
        }
        if (providers.isEmpty()) {
            item {
                TextButton(onClick = onOpenSettings) { Text("Add an AI provider in Settings to start chatting →") }
            }
        }
        if (chats.isEmpty() && providers.isNotEmpty()) {
            item {
                Text(
                    "No conversations yet. Start one, then tap Context to choose what the coach knows: " +
                        "your profile, saved baseline, recent training, and any past analyses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        items(chats, key = { it.id }) { c ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { vm.openChat(c.id) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            whenText(c.updatedEpochMs) + " · " + contextSummary(c, baselineSaved = true).ifEmpty { "no extra context" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { vm.deleteChat(c.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private fun contextSummary(c: ChatEntity, baselineSaved: Boolean): String = listOfNotNull(
    "Profile".takeIf { c.includeProfile },
    "Baseline".takeIf { c.includeBaseline && baselineSaved },
    "Training".takeIf { c.includeTraining },
    c.analysisIdList.size.takeIf { it > 0 }?.let { "$it analys${if (it == 1) "is" else "es"}" },
).joinToString(" · ")

private val SUGGESTIONS = listOf(
    "How has my training gone this month?",
    "What should my next week look like?",
    "Which shoes suit my running?",
    "Explain the analysis in simpler terms",
)

@Composable
private fun ChatConversation(vm: AiViewModel) {
    val chat by vm.openChat.collectAsStateWithLifecycle()
    val messages by vm.chatMessages.collectAsStateWithLifecycle()
    val live by vm.liveReply.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val baseline by vm.baseline.collectAsStateWithLifecycle()
    val c = chat ?: return
    val liveText = live?.takeIf { it.chatId == c.id }?.text
    val busy = live != null
    var draft by rememberSaveable(c.id) { mutableStateOf("") }
    var showContext by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val provider = providers.firstOrNull { it.id == c.providerId } ?: providers.firstOrNull()

    // Keep the newest text in view while a reply streams in.
    val itemCount = messages.size + (if (liveText != null) 1 else 0)
    LaunchedEffect(itemCount, liveText?.length) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1, Int.MAX_VALUE / 2)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = { vm.openChat(null) },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcons.ArrowLeft(modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    provider?.let { "${it.name} · ${it.model}" } ?: "No provider",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { showContext = true }) { Text("Context") }
        }
        // What the coach knows
        Surface(
            onClick = { showContext = true },
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Coach sees: " + contextSummary(c, baseline != null).ifEmpty { "only this conversation" } + "  ·  tap to change",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && liveText == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 24.dp)) {
                        Text("Ask your coach anything about your running.", style = MaterialTheme.typography.bodyMedium)
                        Text("Try one of these:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SUGGESTIONS.forEach { s ->
                            Surface(
                                onClick = { vm.sendChat(s) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            ) {
                                Text(s, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                        }
                    }
                }
            }
            items(messages, key = { it.id }) { m -> MessageBubble(m, onRetry = { vm.retryChat(m) }, canRetry = !busy && m == messages.last()) }
            if (liveText != null) {
                item(key = "live") {
                    AssistantBubble(liveText.ifEmpty { "Thinking…" }, footer = null, isError = false)
                }
            }
        }

        // Input
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message your coach…") },
                shape = RoundedCornerShape(16.dp),
                maxLines = 5,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
            Spacer(Modifier.size(8.dp))
            if (busy) {
                Button(onClick = vm::stopChat, shape = RoundedCornerShape(14.dp)) { Text("Stop") }
            } else {
                Button(
                    onClick = { vm.sendChat(draft); draft = "" },
                    enabled = draft.isNotBlank() && provider != null,
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Send") }
            }
        }
    }

    if (showContext) ContextDialog(vm, c, onDismiss = { showContext = false })
}

@Composable
private fun MessageBubble(m: ChatMessageEntity, onRetry: () -> Unit, canRetry: Boolean) {
    if (m.role == com.healthanalyzer.app.ai.ChatTurn.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    m.text,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    } else {
        val footer = listOfNotNull(m.model, whenText(m.createdEpochMs)).joinToString(" · ")
        Column {
            AssistantBubble(
                text = if (m.error != null && m.text.isBlank()) "⚠ ${m.error}" else m.text +
                    (m.error?.let { "\n\n⚠ $it" } ?: ""),
                footer = footer,
                isError = m.error != null,
            )
            if (m.error != null && canRetry) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun AssistantBubble(text: String, footer: String?, isError: Boolean) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            color = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            MarkdownText(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        if (footer != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Copy", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Chooses the provider and what context is sent with every message of this chat. */
@Composable
private fun ContextDialog(vm: AiViewModel, chat: ChatEntity, onDismiss: () -> Unit) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val baseline by vm.baseline.collectAsStateWithLifecycle()
    val history by vm.analyses.collectAsStateWithLifecycle()
    var c by remember(chat.id) { mutableStateOf(chat) }
    val analyses = remember(history) { history.filter { it.error == null && it.output.isNotBlank() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat context") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 520.dp)) {
                item {
                    Picker(
                        label = "AI provider",
                        selected = providers.firstOrNull { it.id == c.providerId } ?: providers.firstOrNull(),
                        options = providers,
                        display = { "${it.name} (${it.model})" },
                        onSelect = { c = c.copy(providerId = it.id) },
                    )
                }
                item {
                    Text(
                        "Sent with every message. Changes apply from your next message.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                item { ToggleRow("Athlete profile", "Age, weight, heart rates, goals", c.includeProfile) { c = c.copy(includeProfile = it) } }
                item {
                    ToggleRow(
                        "Running baseline",
                        baseline?.header() ?: "No baseline saved yet – build one on the Analyze tab",
                        c.includeBaseline && baseline != null,
                        enabled = baseline != null,
                    ) { c = c.copy(includeBaseline = it) }
                }
                item { ToggleRow("Recent training data", "12-month stats, 12 weekly totals, last 28 days of runs", c.includeTraining) { c = c.copy(includeTraining = it) } }
                item {
                    Text(
                        "Analysis history" + if (c.analysisIdList.isNotEmpty()) " (${c.analysisIdList.size} selected)" else "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (analyses.isEmpty()) {
                    item { Text("No saved analyses yet.", style = MaterialTheme.typography.bodySmall) }
                }
                items(analyses.take(40), key = { it.id }) { a -> AnalysisCheckRow(a, a.id in c.analysisIdList) { on ->
                    val ids = c.analysisIdList.toMutableList().apply { if (on) add(a.id) else remove(a.id) }
                    c = c.copy(analysisIds = ids.distinct().joinToString(","))
                } }
            }
        },
        confirmButton = { Button(onClick = { vm.updateChat(c); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun AnalysisCheckRow(a: AnalysisEntity, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(a.promptName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(a.runLabel, whenText(a.createdEpochMs)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}
