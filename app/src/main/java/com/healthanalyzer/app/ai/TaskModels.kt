package com.healthanalyzer.app.ai

import android.content.Context

/** The jobs the AI does; each can use its own provider, model and thinking level. */
enum class AiTask(val label: String, val description: String) {
    ANALYSIS("Run analysis", "Analyze tab: prompts run on a workout"),
    CHAT("Chat", "Replies in conversations"),
    PLAN("Training plan", "Creating and changing the plan"),
    BRIEFING("Today's briefing", "The coach's card on the Plan tab"),
}

/**
 * Overrides for one [AiTask]. Empty fields fall back to the provider's own settings.
 * A model ID belongs to one provider, so [model] only applies with [providerId] set and only
 * when that provider is the one used; [thinkingLevel] applies to whichever provider is used
 * (or only [providerId] when set), clamped to what the model accepts.
 */
data class TaskModel(
    /** Provider picked by default for this task; null = the usual default (first / last used). */
    val providerId: Long? = null,
    /** Model ID instead of the provider's; blank = the provider's model. */
    val model: String = "",
    /** null = the provider's thinking level. */
    val thinkingLevel: ThinkingLevel? = null,
) {
    val isDefault: Boolean get() = providerId == null && model.isBlank() && thinkingLevel == null
}

/** Per-task overrides, kept in the "ai_settings" preferences (so they are backed up). */
data class TaskModels(val tasks: Map<AiTask, TaskModel> = emptyMap()) {
    operator fun get(task: AiTask): TaskModel = tasks[task] ?: TaskModel()

    fun with(task: AiTask, value: TaskModel): TaskModels = TaskModels(tasks + (task to value))

    /**
     * The provider for [task]: the one picked on screen ([chosenId]), else the task's own, else
     * the first. Returned as a copy with the task's model and thinking level applied; the copy is
     * only for building the request and labels and must never be saved.
     */
    fun providerFor(task: AiTask, providers: List<AiProviderEntity>, chosenId: Long? = null): AiProviderEntity? {
        val p = providers.firstOrNull { it.id == chosenId }
            ?: providers.firstOrNull { it.id == liveProviderId(task, providers) }
            ?: providers.firstOrNull()
            ?: return null
        return live(providers).apply(task, p)
    }

    /** The task's provider if it still exists; a deleted one counts as no choice. */
    fun liveProviderId(task: AiTask, providers: List<AiProviderEntity>): Long? =
        get(task).providerId?.takeIf { id -> providers.any { it.id == id } }

    /**
     * Copy where tasks pointing at a deleted provider act as "Default": their model ID is
     * dropped (it belonged to that provider) and their thinking level applies to any provider.
     */
    fun live(providers: List<AiProviderEntity>): TaskModels = TaskModels(
        tasks.mapValues { (task, t) ->
            if (t.providerId != null && liveProviderId(task, providers) == null) t.copy(providerId = null, model = "") else t
        },
    )

    /** [provider] with [task]'s overrides applied where they belong to it. */
    fun apply(task: AiTask, provider: AiProviderEntity): AiProviderEntity {
        val t = get(task)
        val ownsProvider = t.providerId == provider.id
        val model = if (ownsProvider && t.model.isNotBlank()) t.model.trim() else provider.model
        val level = t.thinkingLevel?.takeIf { t.providerId == null || ownsProvider } ?: provider.thinkingLevel
        if (model == provider.model && level == provider.thinkingLevel) return provider
        return provider.copy(model = model, thinkingLevel = Thinking.resolve(provider.protocol, model, level))
    }

    companion object {
        private const val PREFS = "ai_settings"
        private fun key(task: AiTask, field: String) = "task.${task.name}.$field"

        fun load(context: Context): TaskModels {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return TaskModels(
                AiTask.entries.associateWith { task ->
                    TaskModel(
                        providerId = p.getLong(key(task, "provider"), -1L).takeIf { it >= 0 },
                        model = p.getString(key(task, "model"), "").orEmpty(),
                        thinkingLevel = p.getString(key(task, "thinking"), null)
                            ?.let { name -> ThinkingLevel.entries.firstOrNull { it.name == name } },
                    )
                },
            )
        }

        fun save(context: Context, models: TaskModels) {
            val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            for (task in AiTask.entries) {
                val t = models[task]
                e.putLong(key(task, "provider"), t.providerId ?: -1L)
                e.putString(key(task, "model"), t.model.trim())
                if (t.thinkingLevel == null) e.remove(key(task, "thinking")) else e.putString(key(task, "thinking"), t.thinkingLevel.name)
            }
            e.apply()
        }
    }
}
