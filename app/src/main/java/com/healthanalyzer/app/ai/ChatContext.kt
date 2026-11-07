package com.healthanalyzer.app.ai

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Builds the system prompt and turn list for a chat conversation. */
object ChatContext {

    private const val MAX_ANALYSIS_CHARS = 12_000
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val BASE = """
        You are an experienced running coach and exercise physiologist chatting with the athlete in
        the HealthAnalyzer app. Use the athlete data below whenever it is relevant and quote the
        numbers you rely on. Say clearly when the data can't support a conclusion. Don't give medical
        diagnoses. Keep replies short and conversational unless asked for detail.
        Format with simple Markdown only (bold, bullet lists, small tables). Never use LaTeX or math
        markup: write VO2max, 5'58"/km, 138 bpm as plain text.
        Reply in {{language}}.
    """.trimIndent()

    private const val TRAINING = "{{running_stats:12}}\n\n{{weekly_summary:12}}\n\n{{recent_runs:28}}"

    suspend fun system(
        chat: ChatEntity,
        renderer: PromptRenderer,
        baseline: Baseline?,
        analyses: List<AnalysisEntity>,
        extraSystem: String,
    ): String = buildString {
        append(renderer.render(BASE, null))
        append("\n\nToday is ").append(java.time.LocalDate.now()).append('.')
        if (chat.includeProfile) {
            append("\n\n## Athlete profile\n").append(renderer.render("{{athlete}}", null))
        }
        if (chat.includeBaseline && baseline != null) {
            append("\n\n## ").append(baseline.header()).append('\n').append(baseline.text)
        }
        if (chat.includeTraining) {
            append("\n\n## Recent training data\n").append(renderer.render(TRAINING, null))
        }
        if (analyses.isNotEmpty()) {
            append("\n\n## Earlier analyses the athlete shared for this chat")
            analyses.forEach { a -> append("\n\n### ").append(label(a)).append('\n').append(a.output.take(MAX_ANALYSIS_CHARS)) }
        }
        if (extraSystem.isNotBlank()) append("\n\n").append(extraSystem)
    }

    /** "Analyze this run · 2026-11-01 16:19 · Outdoor run · 6.89 km · 2026-11-02 09:10 · Claude" */
    fun label(a: AnalysisEntity): String = listOfNotNull(
        a.promptName,
        a.runLabel,
        "written " + Instant.ofEpochMilli(a.createdEpochMs).atZone(ZoneId.systemDefault()).format(DATE),
        "${a.providerName} · ${a.model}",
    ).joinToString(" · ")

    /**
     * Saved messages → turns for the API. Failed replies are left out, and back-to-back messages
     * from the same side are merged, since some APIs require user/assistant to alternate.
     */
    fun turns(messages: List<ChatMessageEntity>): List<ChatTurn> {
        val out = mutableListOf<ChatTurn>()
        messages.filter { it.error == null && it.text.isNotBlank() }.forEach { m ->
            val last = out.lastOrNull()
            if (last != null && last.role == m.role) out[out.lastIndex] = last.copy(text = last.text + "\n\n" + m.text)
            else out += ChatTurn(m.role, m.text)
        }
        while (out.firstOrNull()?.isUser == false) out.removeAt(0)
        return out
    }
}
