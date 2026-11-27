package com.healthanalyzer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.healthanalyzer.app.ai.AiProviderEntity
import com.healthanalyzer.app.ai.AiTask
import com.healthanalyzer.app.ai.TaskModel
import com.healthanalyzer.app.ai.TaskModels
import com.healthanalyzer.app.ai.Thinking
import com.healthanalyzer.app.ai.ThinkingLevel

/** Models listed for one saved provider, for picking a task's model ID. */
data class ProviderModels(val loading: Boolean = false, val models: List<String> = emptyList(), val error: String? = null)

/** One entry of the thinking picker; [level] null = use the provider's own level. */
private data class ThinkingChoice(val level: ThinkingLevel?)

private const val NO_PROVIDER = -1L

/** Settings → AI → Providers: which provider, model and thinking level each AI task uses. */
@Composable
fun TaskModelsSection(
    providers: List<AiProviderEntity>,
    taskModels: TaskModels,
    onChange: (AiTask, TaskModel) -> Unit,
    providerModels: Map<Long, ProviderModels> = emptyMap(),
    onFetchModels: (providerId: Long) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Models per task", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "Use a different model or thinking level for each job, e.g. a fast model for the daily briefing and a " +
                "deep-thinking one for plans. Blank fields use the provider's own settings. A provider you pick on a screen " +
                "still wins for that request; the overrides apply when it is this task's provider.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AiTask.entries.forEachIndexed { i, task ->
            if (i > 0) HorizontalDivider()
            // A task whose provider was deleted is shown, and edited, as "Default".
            TaskModelRow(task, taskModels.live(providers)[task], providers, providerModels, onFetchModels) { onChange(task, it) }
        }
    }
}

@Composable
private fun TaskModelRow(
    task: AiTask,
    value: TaskModel,
    providers: List<AiProviderEntity>,
    providerModels: Map<Long, ProviderModels>,
    onFetchModels: (Long) -> Unit,
    onChange: (TaskModel) -> Unit,
) {
    val provider = providers.firstOrNull { it.id == value.providerId }
    Column(Modifier.testTag("task_model_${task.name}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(task.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!value.isDefault) {
                TextButton(onClick = { onChange(TaskModel()) }, modifier = Modifier.testTag("task_model_reset_${task.name}")) { Text("Reset") }
            }
        }
        Picker(
            label = "Provider",
            selected = provider?.id ?: NO_PROVIDER,
            options = listOf(NO_PROVIDER) + providers.map { it.id },
            display = { id -> providers.firstOrNull { it.id == id }?.name ?: "Default (last used or first)" },
            // A model ID belongs to one provider, so switching providers clears it.
            onSelect = { id ->
                val newId = id.takeIf { it != NO_PROVIDER }
                if (newId != value.providerId) onChange(value.copy(providerId = newId, model = ""))
            },
            modifier = Modifier.testTag("task_provider_${task.name}"),
        )
        if (provider != null) {
            // Local text state so typing doesn't wait on the settings round trip.
            var text by remember(task, provider.id) { mutableStateOf(value.model) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onChange(value.copy(model = it)) },
                label = { Text("Model ID") },
                placeholder = { Text(provider.model.ifBlank { "Provider's model" }) },
                supportingText = { Text("Blank = ${provider.model.ifBlank { "the provider's model" }}") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("task_model_field_${task.name}"),
            )
            val listed = providerModels[provider.id] ?: ProviderModels()
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onFetchModels(provider.id) },
                    enabled = !listed.loading,
                    modifier = Modifier.testTag("task_fetch_models_${task.name}"),
                ) { Text(if (listed.loading) "Fetching…" else if (listed.models.isEmpty()) "Fetch models" else "Refresh") }
                if (listed.models.isNotEmpty()) {
                    Picker(
                        label = "",
                        selected = listed.models.firstOrNull { it == text.trim() },
                        options = listed.models,
                        display = { it },
                        onSelect = { picked -> text = picked; onChange(value.copy(model = picked)) },
                        placeholder = "Pick from ${listed.models.size}…",
                        modifier = Modifier.weight(1f).testTag("task_model_picker_${task.name}"),
                    )
                }
            }
            listed.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        val model = provider?.let { value.model.ifBlank { it.model } }
        val levels = provider?.let { Thinking.levelsFor(it.protocol, model!!) } ?: ThinkingLevel.entries
        // Show what will actually be sent: a level the model doesn't accept becomes the nearest one.
        fun sent(level: ThinkingLevel) = provider?.let { Thinking.resolve(it.protocol, model!!, level) } ?: level
        val choices = listOf(ThinkingChoice(null)) + levels.map { ThinkingChoice(it) }
        Picker(
            label = "Thinking level",
            selected = ThinkingChoice(value.thinkingLevel?.let(::sent)),
            options = choices,
            display = { c ->
                c.level?.let { "${it.label} — ${it.description}" }
                    ?: "Provider's setting" + (provider?.let { " (${sent(it.thinkingLevel).label})" } ?: "")
            },
            onSelect = { onChange(value.copy(thinkingLevel = it.level)) },
            modifier = Modifier.testTag("task_thinking_${task.name}"),
        )
    }
}
