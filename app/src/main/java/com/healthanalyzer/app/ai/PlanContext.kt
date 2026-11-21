package com.healthanalyzer.app.ai

import android.content.Context

/**
 * The saved training plan as context for analyses and chats: the coach's summary of the plan,
 * then plan vs recorded for the last 4 weeks and the next 14 days (the {{plan_review:4}} text).
 */
object PlanContext {

    private const val PREFS = "ai_settings"
    private const val KEY = "planContextInAnalyses"
    const val TEMPLATE = "{{plan_review:4}}"
    private const val NO_PLAN = "(no training plan saved)"

    /** Analyses get the plan unless switched off on the Analyze tab. */
    fun useInAnalyses(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setUseInAnalyses(context: Context, use: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, use).apply()

    /** Whether a prompt already asks for the plan itself. */
    fun inTemplate(vararg texts: String): Boolean = texts.any { "{{plan_review" in it }

    /** Null when no plan is saved. */
    suspend fun text(renderer: PromptRenderer, summary: String): String? =
        build(renderer.render(TEMPLATE, null), summary)

    internal fun build(review: String, summary: String): String? {
        val r = review.trim()
        if (r.isEmpty() || r.startsWith(NO_PLAN)) return null
        return buildString {
            if (summary.isNotBlank()) append("Coach's summary of the plan: ").append(summary.trim()).append("\n\n")
            append(r)
        }
    }

    const val HEADER = "My current training plan (planned vs recorded, then the coming sessions)"
}
