package com.healthanalyzer.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.healthanalyzer.app.HealthAnalyzerApp
import com.healthanalyzer.app.ai.AiKeepAlive
import com.healthanalyzer.app.ai.AiProviderEntity
import com.healthanalyzer.app.ai.AnalysisEntity
import com.healthanalyzer.app.ai.AthleteProfile
import com.healthanalyzer.app.ai.BASELINE_PROMPT
import com.healthanalyzer.app.ai.CodexAuth
import com.healthanalyzer.app.ai.Baseline
import com.healthanalyzer.app.ai.DEFAULT_PROMPTS
import com.healthanalyzer.app.ai.SHOE_PROMPTS
import com.healthanalyzer.app.ai.withPlanReview
import com.healthanalyzer.app.ai.KeyCipher
import com.healthanalyzer.app.ai.LlmClients
import com.healthanalyzer.app.ai.LlmConfig
import com.healthanalyzer.app.ai.PromptRenderer
import com.healthanalyzer.app.ai.PromptTemplateEntity
import com.healthanalyzer.app.data.RunEntity
import com.healthanalyzer.app.ui.theme.ThemeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalyzeState(
    val providerId: Long? = null,
    val promptId: Long? = null,
    /** null = latest run. */
    val runId: String? = null,
    val running: Boolean = false,
    val output: String = "",
    val error: String? = null,
    val sentSystem: String = "",
    val sentUser: String = "",
    /** A saved analysis being viewed instead of the live one. */
    val viewing: AnalysisEntity? = null,
)

sealed interface ProviderTestState {
    data object Idle : ProviderTestState
    data object Running : ProviderTestState
    data class Passed(val reply: String, val firstTokenMs: Long, val totalMs: Long) : ProviderTestState
    data class Failed(val message: String) : ProviderTestState
}

/** Editable copy of a provider. [apiKey] is plain text and only set when the user types a new one. */
data class ProviderForm(
    val id: Long = 0,
    val name: String = "",
    val protocol: com.healthanalyzer.app.ai.Protocol = com.healthanalyzer.app.ai.Protocol.OPENAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    val hasStoredKey: Boolean = false,
    val model: String = "",
    val maxTokens: String = "16000",
    val extraSystem: String = "",
    val thinkingLevel: com.healthanalyzer.app.ai.ThinkingLevel = com.healthanalyzer.app.ai.ThinkingLevel.DEFAULT,
)

class AiViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app as HealthAnalyzerApp
    private val dao = container.aiDatabase.aiDao()
    private val repository = container.repository
    private val renderer: PromptRenderer = container.promptRenderer

    val providers: StateFlow<List<AiProviderEntity>> =
        dao.observeProviders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val prompts: StateFlow<List<PromptTemplateEntity>> =
        dao.observePrompts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val analyses: StateFlow<List<AnalysisEntity>> =
        dao.observeAnalyses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val runs: StateFlow<List<RunEntity>> =
        repository.observeRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _analyze = MutableStateFlow(AnalyzeState())
    val analyze: StateFlow<AnalyzeState> = _analyze.asStateFlow()

    private val _profile = MutableStateFlow(AthleteProfile.load(app))
    val profile: StateFlow<AthleteProfile> = _profile.asStateFlow()

    /** Bumped whenever the stored profile is replaced, so the editor drops unsaved field edits. */
    private val _profileRevision = MutableStateFlow(0)
    val profileRevision: StateFlow<Int> = _profileRevision.asStateFlow()

    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models.asStateFlow()

    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage.asStateFlow()

    private val _targetSettingsSection = MutableStateFlow<String?>(null)
    val targetSettingsSection: StateFlow<String?> = _targetSettingsSection.asStateFlow()
    fun navigateToSettings(section: String? = null) { _targetSettingsSection.value = section }
    fun clearTargetSettingsSection() { _targetSettingsSection.value = null }

    private val _themeConfig = MutableStateFlow(ThemeConfig.load(app))
    val themeConfig: StateFlow<ThemeConfig> = _themeConfig.asStateFlow()

    private val _baseline = MutableStateFlow(Baseline.load(app))
    val baseline: StateFlow<Baseline?> = _baseline.asStateFlow()

    private val _useBaseline = MutableStateFlow(Baseline.useInAnalyses(app))
    val useBaseline: StateFlow<Boolean> = _useBaseline.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            if (dao.promptCount() == 0) {
                dao.insertPrompts(DEFAULT_PROMPTS)
                markBaselinePromptSeeded()
            } else if (!baselinePromptSeeded() && dao.baselinePromptCount() == 0) {
                // Installs from before the baseline feature get the new prompt once.
                dao.insertPrompts(listOf(BASELINE_PROMPT))
                markBaselinePromptSeeded()
            }
            seedNewPromptsOnce()
            addPlanReviewToReviewPromptsOnce()
        }
    }

    /** Training-review prompts saved before {{plan_review}} existed get it once; later edits are left alone. */
    private suspend fun addPlanReviewToReviewPromptsOnce() {
        if (prefs().getBoolean("planReviewAdded", false)) return
        val changed = dao.allPrompts().filter { it.name.startsWith("Training review") }
            .mapNotNull { p -> withPlanReview(p.userTemplate)?.let { p.copy(userTemplate = it) } }
        if (changed.isNotEmpty()) dao.upsertPrompts(changed)
        prefs().edit().putBoolean("planReviewAdded", true).apply()
    }

    private fun prefs() = getApplication<Application>().getSharedPreferences("ai_settings", android.content.Context.MODE_PRIVATE)
    /** Adds prompts introduced after install (e.g. shoe advice) once, unless the user already has one by that name. */
    private suspend fun seedNewPromptsOnce() {
        val seeded = prefs().getStringSet("seededPrompts", emptySet())!!
        val existing = dao.promptNames().toSet()
        val missing = SHOE_PROMPTS.filter { it.name !in seeded && it.name !in existing }
        if (missing.isNotEmpty()) dao.insertPrompts(missing)
        prefs().edit().putStringSet("seededPrompts", seeded + SHOE_PROMPTS.map { it.name }).apply()
    }

    private fun baselinePromptSeeded() = prefs().getBoolean("baselinePromptSeeded", false)
    private fun markBaselinePromptSeeded() = prefs().edit().putBoolean("baselinePromptSeeded", true).apply()

    // ---- Analysis ---------------------------------------------------------------------------

    fun select(providerId: Long? = _analyze.value.providerId, promptId: Long? = _analyze.value.promptId) =
        _analyze.update { it.copy(providerId = providerId, promptId = promptId) }

    fun selectRun(runId: String?) = _analyze.update { it.copy(runId = runId) }

    /** Called from a run's detail screen: pick that run and the first prompt that uses {{run}}. */
    fun prepareForRun(runId: String) = _analyze.update { s ->
        val prompt = prompts.value.firstOrNull { "{{run}}" in it.userTemplate } ?: prompts.value.firstOrNull()
        s.copy(runId = runId, promptId = prompt?.id ?: s.promptId, viewing = null)
    }.also { _chatMode.value = false }

    fun start() {
        val s = _analyze.value
        val provider = providers.value.firstOrNull { it.id == s.providerId } ?: providers.value.firstOrNull()
        val prompt = prompts.value.firstOrNull { it.id == s.promptId } ?: prompts.value.firstOrNull()
        if (provider == null) return _analyze.update { it.copy(error = "Add an AI provider in Settings first.") }
        if (prompt == null) return _analyze.update { it.copy(error = "Add a prompt in Settings first.") }

        job?.cancel()
        job = viewModelScope.launch {
            AiKeepAlive.acquire(getApplication())
            _analyze.update {
                it.copy(running = true, output = "", error = null, viewing = null, providerId = provider.id, promptId = prompt.id)
            }
            var output = ""
            var error: String? = null
            var system = ""
            var user = ""
            var runLabel: String? = null
            var runId: String? = null
            try {
                val needsRun = "{{run}}" in prompt.userTemplate || "{{run}}" in prompt.systemPrompt
                val detail = if (needsRun) {
                    val id = s.runId ?: repository.latestRunId()
                    id?.let { repository.getDetail(it) }
                } else null
                runId = detail?.run?.id
                runLabel = detail?.run?.let { PromptRenderer.runLabel(it) }
                system = listOf(renderer.render(prompt.systemPrompt, detail), provider.extraSystem)
                    .filter { it.isNotBlank() }.joinToString("\n\n")
                user = renderer.render(prompt.userTemplate, detail)
                val savedBaseline = _baseline.value
                val templateHasBaseline = "{{baseline}}" in prompt.userTemplate || "{{baseline}}" in prompt.systemPrompt
                if (_useBaseline.value && savedBaseline != null && !templateHasBaseline && !prompt.savesBaseline) {
                    user = "${savedBaseline.header()}:\n${savedBaseline.text}\n\n$user"
                }
                _analyze.update { it.copy(sentSystem = system, sentUser = user) }

                val config = provider.toConfig()
                LlmClients.forProtocol(provider.protocol).stream(config, system, user).collect { delta ->
                    output += delta
                    _analyze.update { it.copy(output = output) }
                }
                if (prompt.savesBaseline && output.isNotBlank()) {
                    saveBaseline(output, "${provider.name} · ${provider.model}")
                    _settingsMessage.value = "Saved as your athlete baseline"
                }
            } catch (e: CancellationException) {
                error = "Stopped."
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                _analyze.update { it.copy(error = error) }
            } finally {
                AiKeepAlive.release(getApplication())
                _analyze.update { it.copy(running = false) }
                if (output.isNotEmpty() || error != null) {
                    val record = AnalysisEntity(
                        createdEpochMs = System.currentTimeMillis(),
                        providerName = provider.name,
                        model = provider.model,
                        promptName = prompt.name,
                        runId = runId,
                        runLabel = runLabel,
                        systemText = system,
                        userText = user,
                        output = output,
                        error = error,
                    )
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { dao.insertAnalysis(record) }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun view(a: AnalysisEntity?) = _analyze.update { it.copy(viewing = a) }

    // ---- Chat -------------------------------------------------------------------------------

    /** Analyze tab mode: false = one-shot analysis, true = chat. */
    private val _chatMode = MutableStateFlow(false)
    val chatMode: StateFlow<Boolean> = _chatMode.asStateFlow()
    fun setChatMode(chat: Boolean) { _chatMode.value = chat }

    val chats: StateFlow<List<com.healthanalyzer.app.ai.ChatEntity>> =
        dao.observeChats().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _openChatId = MutableStateFlow<Long?>(null)
    val openChatId: StateFlow<Long?> = _openChatId.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val openChat: StateFlow<com.healthanalyzer.app.ai.ChatEntity?> = _openChatId
        .flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(null) else dao.observeChat(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val chatMessages: StateFlow<List<com.healthanalyzer.app.ai.ChatMessageEntity>> = _openChatId
        .flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(emptyList()) else dao.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The reply being streamed right now (chat id → text so far); saved as a message when done. */
    data class LiveReply(val chatId: Long, val text: String)
    private val _liveReply = MutableStateFlow<LiveReply?>(null)
    val liveReply: StateFlow<LiveReply?> = _liveReply.asStateFlow()
    private var chatJob: Job? = null

    /** Starts a conversation; [analysisId] pre-selects a saved analysis as context. */
    fun newChat(analysisId: Long? = null) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val last = chats.value.firstOrNull()
        val id = dao.upsertChat(
            com.healthanalyzer.app.ai.ChatEntity(
                title = "New chat",
                createdEpochMs = now,
                updatedEpochMs = now,
                providerId = last?.providerId ?: _analyze.value.providerId,
                // Carry over the last chat's context choices; a fresh install defaults to profile + baseline.
                includeProfile = last?.includeProfile ?: true,
                includeBaseline = last?.includeBaseline ?: true,
                includeTraining = last?.includeTraining ?: false,
                analysisIds = analysisId?.toString().orEmpty(),
            ),
        )
        _chatMode.value = true
        _analyze.update { it.copy(viewing = null) }
        _openChatId.value = id
    }

    fun openChat(id: Long?) { _openChatId.value = id }

    fun deleteChat(id: Long) = viewModelScope.launch {
        if (_liveReply.value?.chatId == id) chatJob?.cancel()
        dao.deleteMessages(id)
        dao.deleteChat(id)
        if (_openChatId.value == id) _openChatId.value = null
    }

    fun clearAllChats() = viewModelScope.launch {
        chatJob?.cancel()
        _liveReply.value = null
        dao.deleteAllChatMessages()
        dao.deleteAllChats()
        _openChatId.value = null
    }

    fun updateChat(c: com.healthanalyzer.app.ai.ChatEntity) = viewModelScope.launch { dao.upsertChat(c) }

    fun sendChat(text: String) {
        val chat = openChat.value ?: return
        val question = text.trim()
        if (question.isEmpty() || chatJob?.isActive == true) return
        chatJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            dao.upsertMessage(com.healthanalyzer.app.ai.ChatMessageEntity(chatId = chat.id, role = com.healthanalyzer.app.ai.ChatTurn.USER, text = question, createdEpochMs = now))
            val title = if (chat.title == "New chat") question.lineSequence().first().take(48) else chat.title
            dao.upsertChat(chat.copy(title = title, updatedEpochMs = now))
            reply(chat.id)
        }
    }

    /** Drops a failed reply and asks again. */
    fun retryChat(failed: com.healthanalyzer.app.ai.ChatMessageEntity) {
        if (chatJob?.isActive == true) return
        chatJob = viewModelScope.launch {
            dao.deleteMessage(failed.id)
            reply(failed.chatId)
        }
    }

    fun stopChat() { chatJob?.cancel() }

    private suspend fun reply(chatId: Long) {
        val chat = dao.getChat(chatId) ?: return
        val provider = providers.value.firstOrNull { it.id == chat.providerId } ?: providers.value.firstOrNull()
        val modelLabel = provider?.let { "${it.name} · ${it.model}" }
        var output = ""
        var error: String? = null
        _liveReply.value = LiveReply(chatId, "")
        AiKeepAlive.acquire(getApplication())
        try {
            if (provider == null) throw IllegalStateException("Add an AI provider in Settings first.")
            val analyses = chat.analysisIdList.takeIf { it.isNotEmpty() }?.let { dao.analysesByIds(it) }.orEmpty()
            val system = com.healthanalyzer.app.ai.ChatContext.system(chat, renderer, _baseline.value, analyses, provider.extraSystem)
            val turns = com.healthanalyzer.app.ai.ChatContext.turns(dao.messages(chatId))
            LlmClients.forProtocol(provider.protocol).chat(provider.toConfig(), system, turns).collect { delta ->
                output += delta
                _liveReply.value = LiveReply(chatId, output)
            }
            if (output.isBlank()) error = "The model returned an empty reply."
        } catch (e: CancellationException) {
            error = "Stopped."
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            AiKeepAlive.release(getApplication())
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                dao.upsertMessage(
                    com.healthanalyzer.app.ai.ChatMessageEntity(
                        chatId = chatId,
                        role = com.healthanalyzer.app.ai.ChatTurn.ASSISTANT,
                        text = output,
                        createdEpochMs = System.currentTimeMillis(),
                        error = error,
                        model = modelLabel,
                    ),
                )
                dao.getChat(chatId)?.let { dao.upsertChat(it.copy(updatedEpochMs = System.currentTimeMillis())) }
                _liveReply.value = null
            }
        }
    }

    // ---- Training plan ----------------------------------------------------------------------

    data class PlanState(val generating: Boolean = false, val receivedChars: Int = 0, val error: String? = null)

    val planWorkouts: StateFlow<List<com.healthanalyzer.app.ai.PlanWorkoutEntity>> =
        dao.observePlan().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _plan = MutableStateFlow(PlanState())
    val plan: StateFlow<PlanState> = _plan.asStateFlow()

    private val _planInfo = MutableStateFlow(com.healthanalyzer.app.ai.PlanInfo.load(app))
    val planInfo: StateFlow<com.healthanalyzer.app.ai.PlanInfo> = _planInfo.asStateFlow()

    private var planJob: Job? = null

    /**
     * Asks the model for a plan of whole Monday–Sunday weeks (see [TrainingPlan.period]) and puts
     * it on the calendar. Workouts already planned in that period are replaced only once the new
     * plan has parsed; earlier days are kept.
     */
    fun generatePlan(providerId: Long?, goal: String, weeks: Int, runsPerWeek: Int, fromToday: Boolean = false) {
        val provider = providers.value.firstOrNull { it.id == providerId } ?: providers.value.firstOrNull()
        if (provider == null) {
            _plan.value = PlanState(error = "Add an AI provider in Settings first.")
            return
        }
        val request = com.healthanalyzer.app.ai.PlanRequest(goal.trim(), weeks, runsPerWeek, fromToday)
        // Remember the request right away so the dialog reopens with it even if this attempt fails.
        savePlanInfo(_planInfo.value.copy(goal = request.goal, weeks = weeks, runsPerWeek = runsPerWeek))
        planJob?.cancel()
        planJob = viewModelScope.launch {
            AiKeepAlive.acquire(getApplication())
            _plan.value = PlanState(generating = true)
            try {
                val today = java.time.LocalDate.now()
                val (start, end) = com.healthanalyzer.app.ai.TrainingPlan.period(today, weeks, fromToday)
                val kept = if (today.isBefore(start)) dao.planBetween(today.toString(), start.minusDays(1).toString()) else emptyList()
                val system = listOf(renderer.render(com.healthanalyzer.app.ai.TrainingPlan.SYSTEM, null), provider.extraSystem)
                    .filter { it.isNotBlank() }.joinToString("\n\n")
                val user = renderer.render(com.healthanalyzer.app.ai.TrainingPlan.userTemplate(request, today, start, end, kept), null)
                val output = StringBuilder()
                LlmClients.forProtocol(provider.protocol).stream(provider.toConfig(), system, user).collect { delta ->
                    output.append(delta)
                    _plan.update { it.copy(receivedChars = output.length) }
                }
                val now = System.currentTimeMillis()
                val parsed = com.healthanalyzer.app.ai.TrainingPlan.parse(output.toString(), start, end, now)
                container.aiDatabase.withTransaction {
                    dao.deletePlanFrom(start.toString())
                    dao.upsertPlanWorkouts(parsed.workouts)
                }
                savePlanInfo(
                    _planInfo.value.copy(summary = parsed.summary, createdEpochMs = now, source = "${provider.name} · ${provider.model}"),
                )
                _plan.value = PlanState()
                _settingsMessage.value = "Plan added to your calendar: ${parsed.workouts.size} workouts"
            } catch (e: CancellationException) {
                _plan.value = PlanState()
                throw e
            } catch (e: Exception) {
                _plan.value = PlanState(error = e.message ?: e.javaClass.simpleName)
            } finally {
                AiKeepAlive.release(getApplication())
            }
        }
    }

    fun stopPlan() { planJob?.cancel() }

    // ---- Revising the plan in conversation --------------------------------------------------

    data class RevisionMessage(val fromAthlete: Boolean, val text: String)

    data class RevisionState(
        val messages: List<RevisionMessage> = emptyList(),
        val running: Boolean = false,
        val error: String? = null,
        val canUndo: Boolean = false,
    )

    private val _revision = MutableStateFlow(RevisionState())
    val revision: StateFlow<RevisionState> = _revision.asStateFlow()

    /** The upcoming workouts as they were before the last applied change, and the day it was made. */
    private var beforeRevision: Pair<String, List<com.healthanalyzer.app.ai.PlanWorkoutEntity>>? = null

    /**
     * Sends the upcoming plan and the athlete's [request] to the model and replaces the plan from
     * today on with its answer. The plan's end date stays where it is.
     */
    fun revisePlan(providerId: Long?, request: String) {
        val text = request.trim()
        if (text.isEmpty() || _revision.value.running) return
        val provider = providers.value.firstOrNull { it.id == providerId } ?: providers.value.firstOrNull()
        if (provider == null) {
            _revision.update { it.copy(error = "Add an AI provider in Settings first.") }
            return
        }
        val earlier = _revision.value.messages.filter { it.fromAthlete }.map { it.text }
        _revision.update { it.copy(messages = it.messages + RevisionMessage(true, text), running = true, error = null) }
        planJob?.cancel()
        planJob = viewModelScope.launch {
            AiKeepAlive.acquire(getApplication())
            try {
                val today = java.time.LocalDate.now()
                val upcoming = dao.planBetween(today.toString(), "9999-12-31")
                if (upcoming.isEmpty()) throw IllegalStateException("There are no planned workouts from today on. Create a plan first.")
                val sunday = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
                val end = maxOf(sunday, java.time.LocalDate.parse(upcoming.last().date))
                val system = listOf(renderer.render(com.healthanalyzer.app.ai.TrainingPlan.REVISE_SYSTEM, null), provider.extraSystem)
                    .filter { it.isNotBlank() }.joinToString("\n\n")
                val user = renderer.render(
                    com.healthanalyzer.app.ai.TrainingPlan.reviseTemplate(text, today, today, end, upcoming, _planInfo.value.goal, earlier),
                    null,
                )
                val output = StringBuilder()
                LlmClients.forProtocol(provider.protocol).stream(provider.toConfig(), system, user).collect { output.append(it) }
                val parsed = com.healthanalyzer.app.ai.TrainingPlan.parse(output.toString(), today, end, System.currentTimeMillis())
                container.aiDatabase.withTransaction {
                    dao.deletePlanFrom(today.toString())
                    dao.upsertPlanWorkouts(parsed.workouts)
                }
                beforeRevision = today.toString() to upcoming
                _revision.update {
                    it.copy(
                        messages = it.messages + RevisionMessage(false, parsed.summary.ifBlank { "Plan updated." }),
                        running = false,
                        canUndo = true,
                    )
                }
            } catch (e: CancellationException) {
                _revision.update { it.copy(messages = it.messages.dropLast(1), running = false) }
                throw e
            } catch (e: Exception) {
                // The failed request leaves the conversation so it isn't sent again as "already applied".
                _revision.update { it.copy(messages = it.messages.dropLast(1), running = false, error = e.message ?: e.javaClass.simpleName) }
            } finally {
                AiKeepAlive.release(getApplication())
            }
        }
    }

    /** Puts back the upcoming workouts from before the last applied change. */
    fun undoRevision() = viewModelScope.launch {
        val (from, workouts) = beforeRevision ?: return@launch
        container.aiDatabase.withTransaction {
            dao.deletePlanFrom(from)
            dao.upsertPlanWorkouts(workouts)
        }
        beforeRevision = null
        _revision.update { it.copy(messages = it.messages + RevisionMessage(false, "Undone – the plan is back to how it was before the last change."), canUndo = false) }
    }

    /** Ends the conversation; the plan stays as it is now. */
    fun closeRevision() {
        if (_revision.value.running) planJob?.cancel()
        beforeRevision = null
        _revision.value = RevisionState()
    }

    fun dismissPlanError() = _plan.update { it.copy(error = null) }

    fun deletePlanWorkout(id: Long) = viewModelScope.launch { dao.deletePlanWorkout(id) }

    /** Removes planned workouts from today on; past days stay as history. */
    fun clearUpcomingPlan() = viewModelScope.launch {
        dao.deletePlanFrom(java.time.LocalDate.now().toString())
        savePlanInfo(_planInfo.value.copy(summary = "", createdEpochMs = 0, source = ""))
    }

    private fun savePlanInfo(info: com.healthanalyzer.app.ai.PlanInfo) {
        com.healthanalyzer.app.ai.PlanInfo.save(getApplication(), info)
        _planInfo.value = info
    }

    // ---- Today's recap (Plan tab) -----------------------------------------------------------

    private val _recapSettings = MutableStateFlow(com.healthanalyzer.app.ai.RecapSettings.load(app))
    val recapSettings: StateFlow<com.healthanalyzer.app.ai.RecapSettings> = _recapSettings.asStateFlow()

    private val _recap = MutableStateFlow(com.healthanalyzer.app.ai.Recap.load(app))
    val recap: StateFlow<com.healthanalyzer.app.ai.Recap> = _recap.asStateFlow()

    private val _recapRunning = MutableStateFlow(false)
    val recapRunning: StateFlow<Boolean> = _recapRunning.asStateFlow()

    private val _placeSearching = MutableStateFlow(false)
    val placeSearching: StateFlow<Boolean> = _placeSearching.asStateFlow()

    private var recapJob: Job? = null

    fun setRecapEnabled(enabled: Boolean) = saveRecapSettings(_recapSettings.value.copy(enabled = enabled))

    /** Looks up [city] and uses it for the weather; blank removes the place. */
    fun setRecapCity(city: String) {
        if (city.isBlank()) {
            saveRecapSettings(_recapSettings.value.copy(place = null))
            _settingsMessage.value = "Weather location removed"
            return
        }
        viewModelScope.launch {
            _placeSearching.value = true
            try {
                val place = com.healthanalyzer.app.ai.Weather.geocode(LlmClients.http, city)
                if (place == null) {
                    _settingsMessage.value = "Couldn't find \"${city.trim()}\". Try the city name only, e.g. Toronto."
                } else {
                    saveRecapSettings(_recapSettings.value.copy(place = place))
                    _settingsMessage.value = "Weather location: ${place.name}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _settingsMessage.value = "Location lookup failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _placeSearching.value = false
            }
        }
    }

    private fun saveRecapSettings(s: com.healthanalyzer.app.ai.RecapSettings) {
        com.healthanalyzer.app.ai.RecapSettings.save(getApplication(), s)
        _recapSettings.value = s
    }

    /**
     * Writes today's briefing unless the cached one still fits (same day, today's workouts and
     * place). A failed attempt is cached too, so opening the tab doesn't retry; [force] does.
     */
    fun refreshRecap(force: Boolean = false) {
        val settings = _recapSettings.value
        if (!settings.enabled || recapJob?.isActive == true) return
        recapJob = viewModelScope.launch {
            val today = java.time.LocalDate.now()
            val todays = dao.planBetween(today.toString(), today.toString())
            val key = com.healthanalyzer.app.ai.TodayRecap.key(today, todays, settings.place)
            if (!force && _recap.value.key == key) return@launch
            val provider = providers.value.firstOrNull { it.id == _analyze.value.providerId } ?: providers.value.firstOrNull()
                ?: dao.allProviders().firstOrNull()
            _recapRunning.value = true
            _recap.value = com.healthanalyzer.app.ai.Recap(key = key)
            var weatherLine = ""
            var weatherText: String? = null
            var output = ""
            var error: String? = null
            try {
                settings.place?.let { place ->
                    try {
                        val w = com.healthanalyzer.app.ai.Weather.today(LlmClients.http, place)
                        weatherLine = "${place.name.substringBefore(',')}: ${w.shortLine()}"
                        weatherText = w.promptText()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        weatherLine = "Weather unavailable (${e.message ?: e.javaClass.simpleName})"
                    }
                }
                if (provider == null) throw IllegalStateException("Add an AI provider in Settings for a coach's briefing.")
                AiKeepAlive.acquire(getApplication())
                try {
                    val zone = java.time.ZoneId.systemDefault()
                    val runDao = container.database.runDao()
                    val runsToday = runDao.runsSince(today.atStartOfDay(zone).toInstant().toEpochMilli())
                    val weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                    val weekRuns = runDao.runsSince(weekStart.atStartOfDay(zone).toInstant().toEpochMilli())
                    val weekPlan = dao.planBetween(weekStart.toString(), weekStart.plusDays(6).toString())
                    val done = runsToday.joinToString("; ") { r ->
                        val km = (r.distanceM ?: 0f) / 1000.0
                        "%s %.2f km in %s".format(
                            PromptRenderer.typeLabel(r.exerciseType), km, com.healthanalyzer.app.data.RunAnalysis.formatDuration(r.durationSec),
                        ) + (r.meanHeartRate?.let { ", avg HR %.0f".format(it) } ?: "")
                    }
                    val user = com.healthanalyzer.app.ai.TodayRecap.userPrompt(
                        today, todays, done,
                        weekPlannedKm = weekPlan.sumOf { (it.distanceKm ?: 0f).toDouble() },
                        weekDoneKm = weekRuns.sumOf { (it.distanceM ?: 0f).toDouble() } / 1000,
                        weather = weatherText,
                    )
                    val system = listOf(renderer.render(com.healthanalyzer.app.ai.TodayRecap.SYSTEM, null), provider.extraSystem)
                        .filter { it.isNotBlank() }.joinToString("\n\n")
                    LlmClients.forProtocol(provider.protocol)
                        .stream(provider.toConfig(), system, renderer.render(user, null))
                        .collect { delta ->
                            output += delta
                            _recap.value = _recap.value.copy(text = output, weatherLine = weatherLine, error = null)
                        }
                    if (output.isBlank()) error = "The model returned an empty reply."
                } finally {
                    AiKeepAlive.release(getApplication())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                _recapRunning.value = false
            }
            val r = com.healthanalyzer.app.ai.Recap(key, output.trim(), weatherLine, error, System.currentTimeMillis())
            com.healthanalyzer.app.ai.Recap.save(getApplication(), r)
            _recap.value = r
        }
    }

    // ---- Baseline ---------------------------------------------------------------------------

    fun saveBaseline(text: String, source: String) {
        val b = Baseline(text.trim(), System.currentTimeMillis(), source)
        Baseline.save(getApplication(), b)
        _baseline.value = b
    }

    /** "Save as baseline" on a finished or saved analysis. */
    fun saveAnalysisAsBaseline(a: AnalysisEntity) {
        saveBaseline(a.output, "${a.providerName} · ${a.model}")
        _settingsMessage.value = "Saved as your athlete baseline"
    }

    fun saveCurrentOutputAsBaseline() {
        val s = _analyze.value
        val provider = providers.value.firstOrNull { it.id == s.providerId }
        saveBaseline(s.output, provider?.let { "${it.name} · ${it.model}" }.orEmpty())
        _settingsMessage.value = "Saved as your athlete baseline"
    }

    fun editBaseline(text: String) {
        val current = _baseline.value
        if (text.isBlank()) return clearBaseline()
        val b = Baseline(text.trim(), current?.createdEpochMs ?: System.currentTimeMillis(),
            listOfNotNull(current?.source?.takeIf { it.isNotBlank() }, "edited").joinToString(", "))
        Baseline.save(getApplication(), b)
        _baseline.value = b
        _settingsMessage.value = "Baseline saved"
    }

    fun clearBaseline() {
        Baseline.save(getApplication(), null)
        _baseline.value = null
        _settingsMessage.value = "Baseline cleared"
    }

    fun setUseBaseline(use: Boolean) {
        Baseline.setUseInAnalyses(getApplication(), use)
        _useBaseline.value = use
    }

    // ---- Samsung Health profile import ------------------------------------------------------

    fun importProfileFromSamsung(activity: android.app.Activity) = viewModelScope.launch {
        _importing.value = true
        try {
            val source = container.samsungHealth
            val granted = source.ensureProfilePermissions(activity)
            if (granted.isEmpty()) {
                _settingsMessage.value = "No Samsung Health access granted for profile, body composition or heart rate."
                return@launch
            }
            val s = source.readProfile(granted)
            val old = _profile.value
            val merged = old.copy(
                birthDate = s.birthDate ?: old.birthDate,
                sex = s.sex ?: old.sex,
                heightCm = s.heightCm ?: old.heightCm,
                weightKg = s.weightKg ?: old.weightKg,
                bodyFatPercent = s.bodyFatPercent ?: old.bodyFatPercent,
                restingHeartRate = s.restingHeartRate ?: old.restingHeartRate,
                importedFrom = "Samsung Health, ${java.time.LocalDate.now()}",
            )
            saveProfile(merged)
            val found = listOfNotNull(
                s.birthDate?.let { "birth date" },
                s.sex?.let { "sex" },
                s.heightCm?.let { "height" },
                s.weightKg?.let { "weight" },
                s.bodyFatPercent?.let { "body fat (${s.bodyMeasuredOn})" },
                s.restingHeartRate?.let { "resting HR ≈ $it (lowest 30-min average, median of ${s.restingHeartRateDays} days)" },
            )
            _settingsMessage.value = if (found.isEmpty()) "Samsung Health had no profile data to import."
            else "Imported from Samsung Health: ${found.joinToString(", ")}"
        } catch (e: com.samsung.android.sdk.health.data.error.ResolvablePlatformException) {
            if (e.hasResolution) e.resolve(activity)
            _settingsMessage.value = "Samsung Health error ${e.errorCode}: ${e.errorMessage}"
        } catch (e: com.samsung.android.sdk.health.data.error.HealthDataException) {
            _settingsMessage.value = "Samsung Health error ${e.errorCode}: ${e.errorMessage}"
        } catch (e: Exception) {
            _settingsMessage.value = "Import failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            _importing.value = false
        }
    }

    fun deleteAnalysis(a: AnalysisEntity) = viewModelScope.launch {
        dao.deleteAnalysis(a)
        _analyze.update { if (it.viewing?.id == a.id) it.copy(viewing = null) else it }
    }

    fun clearAllAnalyses() = viewModelScope.launch {
        dao.deleteAllAnalyses()
        _analyze.update { it.copy(viewing = null) }
    }

    // ---- Settings ---------------------------------------------------------------------------

    fun formFor(p: AiProviderEntity?): ProviderForm = p?.let {
        ProviderForm(
            id = it.id, name = it.name, protocol = it.protocol, baseUrl = it.baseUrl,
            hasStoredKey = it.encryptedKey.isNotEmpty(), model = it.model,
            maxTokens = it.maxTokens.toString(), extraSystem = it.extraSystem,
            thinkingLevel = it.thinkingLevel,
        )
    } ?: ProviderForm()

    // pendingCodex is read when called, before the editor closes and resets the sign-in state.
    fun saveProvider(form: ProviderForm, pendingCodex: String? = _codexCredentials.value) = viewModelScope.launch {
        val existing = providers.value.firstOrNull { it.id == form.id }
        val newKey = if (form.protocol == com.healthanalyzer.app.ai.Protocol.CHATGPT) pendingCodex else form.apiKey.trim()
        val encrypted = when {
            !newKey.isNullOrBlank() -> KeyCipher.encrypt(newKey)
            else -> existing?.encryptedKey.orEmpty()
        }
        val id = dao.upsertProvider(
            AiProviderEntity(
                id = form.id,
                name = form.name.ifBlank { form.protocol.label },
                protocol = form.protocol,
                baseUrl = form.baseUrl.trim(),
                encryptedKey = encrypted,
                model = form.model.trim(),
                maxTokens = form.maxTokens.toIntOrNull()?.coerceIn(256, 128_000) ?: 16000,
                extraSystem = form.extraSystem,
                thinkingLevel = form.thinkingLevel,
            ),
        )
        if (_analyze.value.providerId == null) select(providerId = if (form.id == 0L) id else form.id)
        _settingsMessage.value = "Saved ${form.name}"
    }

    fun deleteProvider(p: AiProviderEntity) = viewModelScope.launch { dao.deleteProvider(p) }

    fun fetchModels(form: ProviderForm) = viewModelScope.launch {
        _models.value = emptyList()
        try {
            val config = formConfig(form, 1024)
            _models.value = LlmClients.forProtocol(form.protocol).listModels(config)
            _settingsMessage.value = "Found ${_models.value.size} models"
        } catch (e: Exception) {
            _settingsMessage.value = "Couldn't list models: ${e.message}"
        }
    }

    fun clearModels() { _models.value = emptyList() }

    private val _test = MutableStateFlow<ProviderTestState>(ProviderTestState.Idle)
    val test: StateFlow<ProviderTestState> = _test.asStateFlow()
    private var testJob: Job? = null

    /** Sends a tiny real request with the form's settings and reports the reply and timing. */
    fun testProvider(form: ProviderForm) {
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _test.value = ProviderTestState.Running
            val started = System.currentTimeMillis()
            var firstTokenMs: Long? = null
            try {
                val config = formConfig(form, 256)
                val reply = StringBuilder()
                kotlinx.coroutines.withTimeout(90_000) {
                    LlmClients.forProtocol(form.protocol)
                        .stream(config, "", "Connection test. Reply with exactly: OK")
                        .collect { delta ->
                            if (firstTokenMs == null) firstTokenMs = System.currentTimeMillis() - started
                            reply.append(delta)
                        }
                }
                val total = System.currentTimeMillis() - started
                _test.value = if (reply.isBlank()) {
                    ProviderTestState.Failed("Connected, but the model returned no text (${total} ms).")
                } else {
                    ProviderTestState.Passed(reply.toString().trim().take(200), firstTokenMs ?: total, total)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _test.value = ProviderTestState.Failed("No reply within 90 s.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _test.value = ProviderTestState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun clearTest() { testJob?.cancel(); _test.value = ProviderTestState.Idle }

    fun savePrompt(p: PromptTemplateEntity) = viewModelScope.launch {
        dao.upsertPrompt(p)
        _settingsMessage.value = "Saved prompt ${p.name}"
    }

    fun deletePrompt(p: PromptTemplateEntity) = viewModelScope.launch { dao.deletePrompt(p) }

    /** Re-adds any default prompt that's missing (by name); existing prompts are left alone. */
    fun restoreDefaultPrompts() = viewModelScope.launch {
        val existing = dao.promptNames().toSet()
        val missing = DEFAULT_PROMPTS.filter { it.name !in existing }
        if (missing.isNotEmpty()) dao.insertPrompts(missing)
        _settingsMessage.value = if (missing.isEmpty()) "All default prompts are already there" else "Added ${missing.size} default prompt(s)"
    }

    fun saveProfile(p: AthleteProfile) {
        AthleteProfile.save(getApplication(), p)
        _profile.value = p
        _profileRevision.value++
        _settingsMessage.value = "Profile saved"
    }

    fun saveThemeConfig(config: ThemeConfig) {
        ThemeConfig.save(getApplication(), config)
        _themeConfig.value = config
    }

    fun dismissSettingsMessage() { _settingsMessage.value = null }

    /** Re-reads settings kept outside the database, e.g. after a settings backup was restored. */
    fun reloadFromStorage() {
        val app = getApplication<Application>()
        _profile.value = AthleteProfile.load(app)
        _profileRevision.value++
        _baseline.value = Baseline.load(app)
        _useBaseline.value = Baseline.useInAnalyses(app)
        _themeConfig.value = ThemeConfig.load(app)
        _planInfo.value = com.healthanalyzer.app.ai.PlanInfo.load(app)
        _recapSettings.value = com.healthanalyzer.app.ai.RecapSettings.load(app)
        _recap.value = com.healthanalyzer.app.ai.Recap.load(app)
    }

    private fun AiProviderEntity.toConfig() = LlmConfig(
        protocol = protocol,
        baseUrl = baseUrl,
        apiKey = KeyCipher.decrypt(encryptedKey),
        model = model,
        maxTokens = maxTokens,
        thinkingLevel = thinkingLevel,
        onCredentialsRefreshed = { fresh -> persistCredentials(id, fresh) },
    )

    /** Config for the provider being edited; refreshed ChatGPT tokens flow back into the form. */
    private fun formConfig(form: ProviderForm, maxTokens: Int): LlmConfig {
        val existing = providers.value.firstOrNull { it.id == form.id }
        val key = (_codexCredentials.value ?: form.apiKey).ifBlank {
            existing?.encryptedKey?.let(KeyCipher::decrypt).orEmpty()
        }
        return LlmConfig(
            protocol = form.protocol,
            baseUrl = form.baseUrl.trim(),
            apiKey = key.trim(),
            model = form.model.trim(),
            maxTokens = maxTokens,
            thinkingLevel = form.thinkingLevel,
            onCredentialsRefreshed = { fresh ->
                _codexCredentials.value = fresh
                if (form.id != 0L) persistCredentials(form.id, fresh)
            },
        )
    }

    /** Stores rotated credentials so the old refresh token is never reused. */
    private suspend fun persistCredentials(providerId: Long, plain: String) {
        val current = providers.value.firstOrNull { it.id == providerId } ?: return
        dao.upsertProvider(current.copy(encryptedKey = KeyCipher.encrypt(plain)))
    }

    // ---- ChatGPT (Codex) sign-in ------------------------------------------------------------

    private val _codexLogin = MutableStateFlow<CodexLoginState>(CodexLoginState.Idle)
    val codexLogin: StateFlow<CodexLoginState> = _codexLogin.asStateFlow()

    /** Token JSON from a sign-in or refresh in the editor, not yet saved with the provider. */
    private val _codexCredentials = MutableStateFlow<String?>(null)
    val codexCredentials: StateFlow<String?> = _codexCredentials.asStateFlow()

    private var codexJob: Job? = null

    fun startCodexLogin() {
        codexJob?.cancel()
        codexJob = viewModelScope.launch {
            try {
                _codexLogin.value = CodexLoginState.Requesting
                val http = LlmClients.http
                val code = CodexAuth.requestDeviceCode(http)
                _codexLogin.value = CodexLoginState.WaitingForUser(code.userCode, CodexAuth.VERIFICATION_URL)
                val tokens = CodexAuth.awaitTokens(http, code)
                _codexCredentials.value = tokens.toJson()
                _codexLogin.value = CodexLoginState.SignedIn(tokens.email, tokens.plan)
            } catch (e: CancellationException) {
                _codexLogin.value = CodexLoginState.Idle
                throw e
            } catch (e: Exception) {
                _codexLogin.value = CodexLoginState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelCodexLogin() { codexJob?.cancel(); _codexLogin.value = CodexLoginState.Idle }

    /** Called when the provider editor opens or closes. */
    fun resetCodexEditorState() {
        codexJob?.cancel()
        _codexLogin.value = CodexLoginState.Idle
        _codexCredentials.value = null
    }

    /** Who a saved ChatGPT provider is signed in as, for display. */
    fun codexAccount(form: ProviderForm): String? {
        val json = _codexCredentials.value
            ?: providers.value.firstOrNull { it.id == form.id }?.encryptedKey?.takeIf { it.isNotEmpty() }?.let(KeyCipher::decrypt)
            ?: return null
        val t = CodexAuth.Tokens.fromJson(json) ?: return null
        return listOfNotNull(t.email, t.plan?.let { "plan: $it" }).joinToString(" · ").ifBlank { "signed in" }
    }
}

sealed interface CodexLoginState {
    data object Idle : CodexLoginState
    data object Requesting : CodexLoginState
    data class WaitingForUser(val code: String, val url: String) : CodexLoginState
    data class SignedIn(val email: String?, val plan: String?) : CodexLoginState
    data class Failed(val message: String) : CodexLoginState
}
