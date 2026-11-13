package com.healthanalyzer.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthanalyzer.app.ai.AiProviderEntity
import com.healthanalyzer.app.ai.AthleteProfile
import com.healthanalyzer.app.ai.Baseline
import com.healthanalyzer.app.ai.CodexAuth
import com.healthanalyzer.app.ai.PROVIDER_PRESETS
import com.healthanalyzer.app.ai.PromptRenderer
import com.healthanalyzer.app.ai.PromptTemplateEntity
import com.healthanalyzer.app.ai.Protocol
import com.healthanalyzer.app.backup.BackupKind
import com.healthanalyzer.app.data.GarminPersonalApi
import com.healthanalyzer.app.ui.theme.GarminBlue
import com.healthanalyzer.app.ui.theme.SamsungGreen
import com.healthanalyzer.app.ui.theme.ThemeConfig
import com.healthanalyzer.app.ui.theme.ThemeMode
import com.healthanalyzer.app.ui.theme.ThemePreset
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Multi-layer settings hierarchy:
 * - Layer 1 (HUB): High-level category dashboard with live status summaries.
 * - Layer 2 (Category screens): Focused configuration per area (Profile, AI, Appearance, Data, Backup, Guides).
 * - Layer 3 (Item editors): Full-screen editors for individual providers, prompts, and baseline text.
 */
enum class SettingsLayer {
    HUB,
    ATHLETE_PROFILE,
    AI_CONFIG,
    APPEARANCE,
    DATA_INTEGRATIONS,
    BACKUP_RESTORE,
    SETUP_GUIDES,
    TRAINING_PLAN,
}

@Composable
fun SettingsScreen(vm: AiViewModel, activity: Activity, runsVm: RunsViewModel) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val prompts by vm.prompts.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val baseline by vm.baseline.collectAsStateWithLifecycle()
    val useBaseline by vm.useBaseline.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val profileRevision by vm.profileRevision.collectAsStateWithLifecycle()
    val targetSection by vm.targetSettingsSection.collectAsStateWithLifecycle()
    val themeConfig by vm.themeConfig.collectAsStateWithLifecycle()

    var currentLayer by rememberSaveable { mutableStateOf(SettingsLayer.HUB) }
    var aiSubTab by rememberSaveable { mutableIntStateOf(0) }
    var editingProvider by remember { mutableStateOf<ProviderForm?>(null) }
    var editingPrompt by remember { mutableStateOf<PromptTemplateEntity?>(null) }
    var editingBaseline by remember { mutableStateOf(false) }
    var helpGuide by remember { mutableStateOf<SetupGuide?>(null) }
    val backupVm: BackupViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    // Hoisted scroll states so returning from a deeper layer preserves exact scroll position
    val hubListState = rememberLazyListState()
    val profileListState = rememberLazyListState()
    val aiListState = rememberLazyListState()
    val appearanceListState = rememberLazyListState()
    val dataListState = rememberLazyListState()
    val backupListState = rememberLazyListState()
    val guidesListState = rememberLazyListState()
    val planListState = rememberLazyListState()

    // Respond to deep-link requests (e.g. from AnalyzeScreen: "Add an AI provider in Settings")
    LaunchedEffect(targetSection) {
        if (targetSection == "ai") {
            currentLayer = SettingsLayer.AI_CONFIG
            vm.clearTargetSettingsSection()
        }
    }

    helpGuide?.let { GuideDialog(it, onDismiss = { helpGuide = null }) }

    val provider = editingProvider
    val prompt = editingPrompt

    // Layer 3: Item editors take precedence
    when {
        provider != null -> {
            BackHandler {
                editingProvider = null
                vm.clearModels()
                vm.clearTest()
                vm.resetCodexEditorState()
            }
            ProviderEditor(
                vm = vm,
                initial = provider,
                onDone = {
                    editingProvider = null
                    vm.clearModels()
                    vm.clearTest()
                    vm.resetCodexEditorState()
                },
            )
        }
        prompt != null -> {
            BackHandler { editingPrompt = null }
            PromptEditor(
                initial = prompt,
                onSave = {
                    vm.savePrompt(it)
                    editingPrompt = null
                },
                onCancel = { editingPrompt = null },
            )
        }
        editingBaseline -> {
            BackHandler { editingBaseline = false }
            BaselineEditor(
                initial = baseline?.text.orEmpty(),
                onSave = {
                    vm.editBaseline(it)
                    editingBaseline = false
                },
                onCancel = { editingBaseline = false },
            )
        }
        // Layer 2: Category Screens
        currentLayer != SettingsLayer.HUB -> {
            BackHandler { currentLayer = SettingsLayer.HUB }
            when (currentLayer) {
                SettingsLayer.ATHLETE_PROFILE -> AthleteProfileSubScreen(
                    profile = profile,
                    profileRevision = profileRevision,
                    importing = importing,
                    listState = profileListState,
                    onImportSamsung = { vm.importProfileFromSamsung(activity) },
                    onSaveProfile = vm::saveProfile,
                    onBack = { currentLayer = SettingsLayer.HUB },
                )
                SettingsLayer.AI_CONFIG -> AiConfigSubScreen(
                    vm = vm,
                    providers = providers,
                    prompts = prompts,
                    baseline = baseline,
                    useBaseline = useBaseline,
                    listState = aiListState,
                    subTab = aiSubTab,
                    onSubTabChange = { aiSubTab = it },
                    onAddProvider = { editingProvider = ProviderForm() },
                    onEditProvider = { editingProvider = vm.formFor(it) },
                    onDeleteProvider = vm::deleteProvider,
                    onNewPrompt = {
                        editingPrompt = PromptTemplateEntity(name = "", systemPrompt = "", userTemplate = "{{athlete}}\n\n{{run}}")
                    },
                    onEditPrompt = { editingPrompt = it },
                    onDuplicatePrompt = {
                        editingPrompt = it.copy(id = 0L, name = "${it.name} (Copy)")
                    },
                    onDeletePrompt = vm::deletePrompt,
                    onRestoreDefaultPrompts = vm::restoreDefaultPrompts,
                    onUseBaselineChange = vm::setUseBaseline,
                    onEditBaseline = { editingBaseline = true },
                    onClearBaseline = vm::clearBaseline,
                    onBack = { currentLayer = SettingsLayer.HUB },
                )
                SettingsLayer.APPEARANCE -> AppearanceSubScreen(
                    themeConfig = themeConfig,
                    listState = appearanceListState,
                    onUpdateTheme = vm::saveThemeConfig,
                    onBack = { currentLayer = SettingsLayer.HUB },
                )
                SettingsLayer.DATA_INTEGRATIONS -> DataIntegrationsSubScreen(
                    runsVm = runsVm,
                    activity = activity,
                    listState = dataListState,
                    onHelp = { helpGuide = it },
                    onBack = { currentLayer = SettingsLayer.HUB },
                )
                SettingsLayer.BACKUP_RESTORE -> BackupSubScreen(
                    backupVm = backupVm,
                    listState = backupListState,
                    onRestored = { kind ->
                        if (kind == BackupKind.CONFIG) {
                            vm.reloadFromStorage()
                            runsVm.refreshGarminState()
                        } else {
                            vm.openChat(null)
                        }
                    },
                    onBack = { currentLayer = SettingsLayer.HUB },
                )
                SettingsLayer.SETUP_GUIDES -> SetupGuidesSubScreen(
                    listState = guidesListState,
                    onBack = { currentLayer = SettingsLayer.HUB },
                )
                SettingsLayer.TRAINING_PLAN -> TrainingPlanSubScreen(
                    vm = vm,
                    listState = planListState,
                    onBack = { currentLayer = SettingsLayer.HUB },
                )
                SettingsLayer.HUB -> Unit
            }
        }
        // Layer 1: Main Settings Hub
        else -> {
            SettingsHubScreen(
                profile = profile,
                providers = providers,
                prompts = prompts,
                themeConfig = themeConfig,
                runsVm = runsVm,
                listState = hubListState,
                recapEnabled = vm.recapSettings.collectAsStateWithLifecycle().value.enabled,
                onNavigate = { currentLayer = it },
            )
        }
    }
}

// -----------------------------------------------------------------------------
// LAYER 1: SETTINGS HUB
// -----------------------------------------------------------------------------

@Composable
private fun SettingsHubScreen(
    profile: AthleteProfile,
    providers: List<AiProviderEntity>,
    prompts: List<PromptTemplateEntity>,
    themeConfig: ThemeConfig,
    runsVm: RunsViewModel,
    listState: LazyListState,
    recapEnabled: Boolean,
    onNavigate: (SettingsLayer) -> Unit,
) {
    val runsState by runsVm.state.collectAsStateWithLifecycle()
    val runs by runsVm.runs.collectAsStateWithLifecycle()

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(
                    "Settings & Preferences",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Personalize athlete biometrics, AI coaching models, data sync, and backups",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // Live System Overview Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "System Status",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        StatusBadge(
                            text = if (runsState.garminConnected) "Garmin Active" else "Ready",
                            containerColor = if (runsState.garminConnected) GarminBlue.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (runsState.garminConnected) GarminBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                            dotColor = if (runsState.garminConnected) GarminBlue else MaterialTheme.colorScheme.primary,
                        )
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OverviewMetricTile(
                            label = "Athlete Profile",
                            value = profile.maxHeartRate?.let { "$it bpm max" } ?: "Not calibrated",
                            subtitle = if (profile.useCustomZones) "Custom HR zones" else "Standard 5 zones",
                            modifier = Modifier.weight(1f),
                        )
                        OverviewMetricTile(
                            label = "AI Engine",
                            value = if (providers.isEmpty()) "No provider" else "${providers.size} provider(s)",
                            subtitle = "${prompts.size} prompt template(s)",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OverviewMetricTile(
                            label = "Data Records",
                            value = "${runs.size} runs",
                            subtitle = if (runsState.garminConnected) "Garmin connected" else "Manual / Samsung",
                            modifier = Modifier.weight(1f),
                        )
                        OverviewMetricTile(
                            label = "Security",
                            value = "Local encrypted",
                            subtitle = "AES-GCM key cipher",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Section: Personalization & Intelligence
        item { SectionHeader("Athlete & Intelligence") }
        item {
            CategoryTile(
                title = "Athlete Profile & Biometrics",
                subtitle = "Height, weight, max HR, resting HR, custom HR zones, coaching notes",
                badgeText = if (profile.maxHeartRate != null) "${profile.maxHeartRate} bpm · ${profile.language}" else "Setup required",
                icon = { AppIcons.User(modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) },
                iconBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                onClick = { onNavigate(SettingsLayer.ATHLETE_PROFILE) },
            )
        }
        item {
            CategoryTile(
                title = "AI Models & Prompts",
                subtitle = "LLM endpoints (OpenAI, Claude, Gemini, ChatGPT), coaching prompts & baseline",
                badgeText = "${providers.size} providers · ${prompts.size} prompts",
                icon = { AppIcons.Sparkles(modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.tertiary) },
                iconBackground = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                onClick = { onNavigate(SettingsLayer.AI_CONFIG) },
            )
        }
        item {
            CategoryTile(
                title = "Training Plan",
                subtitle = "Today's coach briefing on the Plan tab and the weather location it uses",
                badgeText = if (recapEnabled) "Today's recap on" else "Today's recap off",
                icon = { AppIcons.Calendar(modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) },
                iconBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                onClick = { onNavigate(SettingsLayer.TRAINING_PLAN) },
            )
        }
        item {
            CategoryTile(
                title = "Theme & Appearance",
                subtitle = "Dark/Light display mode, curated athletic color presets, and custom palette",
                badgeText = "${themeConfig.preset.title} · ${themeConfig.mode.label}",
                icon = { AppIcons.Palette(modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) },
                iconBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                onClick = { onNavigate(SettingsLayer.APPEARANCE) },
            )
        }

        // Section: Integrations & Data
        item { SectionHeader("Integrations & Data") }
        item {
            CategoryTile(
                title = "Data Sources & Integrations",
                subtitle = "Garmin Connect Personal API, Samsung Health sync, and FIT imports",
                badgeText = if (runsState.garminConnected) "Garmin Active" else "${runs.size} runs stored",
                icon = { AppIcons.Database(modifier = Modifier.size(22.dp), tint = GarminBlue) },
                iconBackground = GarminBlue.copy(alpha = 0.15f),
                onClick = { onNavigate(SettingsLayer.DATA_INTEGRATIONS) },
            )
        }
        item {
            CategoryTile(
                title = "Backup & Cloud Restore",
                subtitle = "Encrypted archives of keys, prompts, and workouts to phone or WebDAV",
                badgeText = "File / WebDAV",
                icon = { AppIcons.Cloud(modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary) },
                iconBackground = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                onClick = { onNavigate(SettingsLayer.BACKUP_RESTORE) },
            )
        }

        // Section: Help & Guides
        item { SectionHeader("Help & Documentation") }
        item {
            CategoryTile(
                title = "Setup Guides & Walkthroughs",
                subtitle = "Step-by-step instructions for Samsung Health exports, Garmin FIT, and API keys",
                badgeText = "${SetupGuide.entries.size} guides",
                icon = { AppIcons.Book(modifier = Modifier.size(22.dp), tint = SamsungGreen) },
                iconBackground = SamsungGreen.copy(alpha = 0.15f),
                onClick = { onNavigate(SettingsLayer.SETUP_GUIDES) },
            )
        }

        item {
            val updateVm: com.healthanalyzer.app.update.UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            com.healthanalyzer.app.update.UpdateSettingsCard(updateVm)
        }

        item {
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "HealthAnalyzer v${com.healthanalyzer.app.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Local-first & private · Your biometric telemetry never leaves this device without your consent.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun OverviewMetricTile(
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun CategoryTile(
    title: String,
    subtitle: String,
    badgeText: String,
    icon: @Composable () -> Unit,
    iconBackground: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Text(
                    badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            AppIcons.ChevronRight(
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubScreenHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            trailingAction?.invoke()
        }
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp),
            )
        }
    }
}

// -----------------------------------------------------------------------------
// LAYER 2: TRAINING PLAN
// -----------------------------------------------------------------------------

@Composable
private fun TrainingPlanSubScreen(vm: AiViewModel, listState: LazyListState, onBack: () -> Unit) {
    val settings by vm.recapSettings.collectAsStateWithLifecycle()
    val searching by vm.placeSearching.collectAsStateWithLifecycle()
    val planInAnalyses by vm.planInAnalyses.collectAsStateWithLifecycle()
    var city by rememberSaveable(settings.place?.name) { mutableStateOf(settings.place?.name?.substringBefore(',').orEmpty()) }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SubScreenHeader(
                title = "Training Plan",
                subtitle = "Options for the Plan tab.",
                onBack = onBack,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Planning advice in analyses", fontWeight = FontWeight.SemiBold)
                        Text(
                            "On: run analyses and training reviews end with suggestions for your coming sessions. " +
                                "Off: they only review, and planning stays on the Plan tab. " +
                                "Applies to prompts that contain {{planning}}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = planInAnalyses, onCheckedChange = vm::setPlanInAnalyses)
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Today's recap", fontWeight = FontWeight.SemiBold)
                            Text(
                                "A card at the top of the Plan tab with today's workout, the weather and a short AI coach " +
                                    "briefing (when to run, what to wear, pace adjustments). Written once a day with your " +
                                    "first AI provider, and again when today's plan changes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = settings.enabled, onCheckedChange = vm::setRecapEnabled)
                    }
                    if (settings.enabled) {
                        OutlinedTextField(
                            value = city,
                            onValueChange = { city = it },
                            label = { Text("City for the weather") },
                            placeholder = { Text("e.g. Toronto") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.setRecapCity(city) },
                                enabled = !searching,
                                shape = RoundedCornerShape(10.dp),
                            ) { Text(if (searching) "Looking up…" else "Save location") }
                            if (settings.place != null) {
                                TextButton(onClick = { city = ""; vm.setRecapCity("") }) {
                                    Text("Remove", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Text(
                            settings.place?.let { "Using ${it.name} (%.2f, %.2f).".format(it.lat, it.lon) }
                                ?: "No location – the recap leaves the weather out.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "The forecast comes from Open-Meteo (free, no account); only the city's coordinates are sent there. " +
                                "The AI model reads the forecast – it doesn't look up the weather itself.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// LAYER 2: ATHLETE PROFILE & BIOMETRICS
// -----------------------------------------------------------------------------

@Composable
private fun AthleteProfileSubScreen(
    profile: AthleteProfile,
    profileRevision: Int,
    importing: Boolean,
    listState: LazyListState,
    onImportSamsung: () -> Unit,
    onSaveProfile: (AthleteProfile) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SubScreenHeader(
                title = "Athlete Profile",
                subtitle = "Biometric metrics and heart-rate zones are auto-injected into prompts via {{athlete}}.",
                onBack = onBack,
                trailingAction = {
                    OutlinedButton(
                        onClick = onImportSamsung,
                        enabled = !importing,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(if (importing) "Importing…" else "Import Samsung")
                    }
                },
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    profile.importedFrom?.let {
                        Text(
                            "Last imported: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = SamsungGreen,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    androidx.compose.runtime.key(profileRevision) {
                        ProfileEditor(profile, onSave = onSaveProfile)
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// LAYER 2: AI MODELS & PROMPTS
// -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiConfigSubScreen(
    vm: AiViewModel,
    providers: List<AiProviderEntity>,
    prompts: List<PromptTemplateEntity>,
    baseline: Baseline?,
    useBaseline: Boolean,
    listState: LazyListState,
    subTab: Int,
    onSubTabChange: (Int) -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (AiProviderEntity) -> Unit,
    onDeleteProvider: (AiProviderEntity) -> Unit,
    onNewPrompt: () -> Unit,
    onEditPrompt: (PromptTemplateEntity) -> Unit,
    onDuplicatePrompt: (PromptTemplateEntity) -> Unit,
    onDeletePrompt: (PromptTemplateEntity) -> Unit,
    onRestoreDefaultPrompts: () -> Unit,
    onUseBaselineChange: (Boolean) -> Unit,
    onEditBaseline: () -> Unit,
    onClearBaseline: () -> Unit,
    onBack: () -> Unit,
) {
    val tabTitles = listOf("Providers (${providers.size})", "Prompts (${prompts.size})", "Baseline")

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SubScreenHeader(
                title = "AI & Prompts",
                subtitle = "Manage AI model providers, prompt templates, and baseline athletic context.",
                onBack = onBack,
            )
        }

        item {
            SegmentedSwitcher(
                selectedIndex = subTab,
                options = tabTitles,
                onSelect = onSubTabChange,
            )
        }

        when (subTab) {
            0 -> {
                // Providers Sub-layer
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Configured Providers",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Button(
                            onClick = onAddProvider,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("+ Add Provider")
                        }
                    }
                }
                if (providers.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AppIcons.Sparkles(modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    "No AI Providers Configured",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Add an OpenAI, Anthropic Claude, Google Gemini, or ChatGPT Codex connection to analyze workouts.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Spacer(Modifier.height(4.dp))
                                Button(onClick = onAddProvider, shape = RoundedCornerShape(10.dp)) {
                                    Text("+ Add First Provider")
                                }
                            }
                        }
                    }
                }
                items(providers, key = { "p${it.id}" }) { p ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onEditProvider(p) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        p.name,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.align(Alignment.CenterVertically),
                                    )
                                    StatusBadge(
                                        text = p.protocol.label,
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.CenterVertically),
                                    )
                                }
                                Text(
                                    "Model: ${p.model.ifBlank { "Not set" }}${if (p.thinkingLevel != com.healthanalyzer.app.ai.ThinkingLevel.DEFAULT) " · ${p.thinkingLevel.label} Thinking" else ""}\n${p.baseUrl}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onDeleteProvider(p) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            1 -> {
                // Prompts Sub-layer
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Prompt Templates",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onRestoreDefaultPrompts) { Text("Defaults") }
                            Button(
                                onClick = onNewPrompt,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("+ New")
                            }
                        }
                    }
                }
                items(prompts, key = { "t${it.id}" }) { t ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onEditPrompt(t) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        t.name,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.align(Alignment.CenterVertically),
                                    )
                                    if (t.savesBaseline) {
                                        StatusBadge(
                                            text = "Saves Baseline ★",
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            contentColor = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.align(Alignment.CenterVertically),
                                        )
                                    }
                                }
                                Text(
                                    t.userTemplate.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("{{") }.orEmpty(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { onDuplicatePrompt(t) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text("Duplicate")
                                }
                                TextButton(
                                    onClick = { onDeletePrompt(t) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                // Baseline Sub-layer
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            BaselineSection(
                                baseline = baseline,
                                useBaseline = useBaseline,
                                onUseChange = onUseBaselineChange,
                                onEdit = onEditBaseline,
                                onClear = onClearBaseline,
                            )
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// LAYER 2: THEME & APPEARANCE
// -----------------------------------------------------------------------------

private val QUICK_SWATCHES = listOf(
    Color(0xFF00E5BE), // Runner Teal
    Color(0xFF0089D6), // Garmin Blue
    Color(0xFF2DB84C), // Samsung Green
    Color(0xFFFF5722), // Trackside Orange
    Color(0xFFFF3B5C), // Cardio Crimson
    Color(0xFF9D65FF), // AI Purple
    Color(0xFF3B82F6), // Cobalt Blue
    Color(0xFFF59E0B), // Podium Gold
    Color(0xFF00E5FF), // Cyber Cyan
    Color(0xFFEC4899), // Neon Pink
    Color(0xFF10B981), // Emerald
    Color(0xFF8B5CF6), // Royal Violet
)

@Composable
private fun AppearanceSubScreen(
    themeConfig: ThemeConfig,
    listState: LazyListState,
    onUpdateTheme: (ThemeConfig) -> Unit,
    onBack: () -> Unit,
) {
    var editingSlot by rememberSaveable { mutableIntStateOf(0) } // 0 = Primary, 1 = Secondary, 2 = Tertiary
    val currentSlotColor = when (editingSlot) {
        0 -> themeConfig.activePrimary
        1 -> themeConfig.activeSecondary
        else -> themeConfig.activeTertiary
    }
    var hexInput by remember(editingSlot, themeConfig.preset, currentSlotColor) {
        mutableStateOf(ThemeConfig.toHex(currentSlotColor))
    }

    fun applySlotColor(newColor: Color) {
        val argb = newColor.toArgb()
        val baseP = themeConfig.activePrimary.toArgb()
        val baseS = themeConfig.activeSecondary.toArgb()
        val baseT = themeConfig.activeTertiary.toArgb()
        onUpdateTheme(
            themeConfig.copy(
                preset = ThemePreset.CUSTOM,
                customPrimaryArgb = if (editingSlot == 0) argb else baseP,
                customSecondaryArgb = if (editingSlot == 1) argb else baseS,
                customTertiaryArgb = if (editingSlot == 2) argb else baseT,
            ),
        )
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SubScreenHeader(
                title = "Theme & Appearance",
                subtitle = "Customize display mode, select a curated color preset, or craft your own palette.",
                onBack = onBack,
                trailingAction = {
                    if (themeConfig != ThemeConfig()) {
                        TextButton(onClick = { onUpdateTheme(ThemeConfig()) }) {
                            Text("Reset")
                        }
                    }
                },
            )
        }

        // Live Preview Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Live Theme Preview",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        StatusBadge(
                            text = themeConfig.preset.title,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            dotColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusBadge(
                            text = "Primary Pace · 4:15 /km",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                        StatusBadge(
                            text = "HR Zone 4 · 172 bpm",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.secondary,
                        )
                        StatusBadge(
                            text = "AI Insight ★",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }

        // Display Mode
        item { SectionHeader("Display Mode") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SegmentedSwitcher(
                        selectedIndex = ThemeMode.entries.indexOf(themeConfig.mode),
                        options = ThemeMode.entries.map { it.label },
                        onSelect = { idx -> onUpdateTheme(themeConfig.copy(mode = ThemeMode.entries[idx])) },
                    )
                    if (themeConfig.mode != ThemeMode.LIGHT) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("AMOLED Pure Black", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Uses true #000000 pitch-black background in dark mode for OLED screens.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = themeConfig.amoledBlack,
                                onCheckedChange = { onUpdateTheme(themeConfig.copy(amoledBlack = it)) },
                            )
                        }
                    }
                }
            }
        }

        // Color Presets
        item { SectionHeader("Curated Color Presets") }
        items(ThemePreset.selectablePresets.chunked(2), key = { it.first().name }) { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowPresets.forEach { preset ->
                    val isSelected = themeConfig.preset == preset
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                onUpdateTheme(
                                    themeConfig.copy(
                                        preset = preset,
                                        customPrimaryArgb = preset.primary.toArgb(),
                                        customSecondaryArgb = preset.secondary.toArgb(),
                                        customTertiaryArgb = preset.tertiary.toArgb(),
                                    ),
                                )
                            },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        ),
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ColorDot(preset.primary, size = 18.dp)
                                    ColorDot(preset.secondary, size = 18.dp)
                                    ColorDot(preset.tertiary, size = 18.dp)
                                }
                                if (isSelected) {
                                    Text(
                                        "✓",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                preset.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Text(
                                preset.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                minLines = 2,
                                maxLines = 2,
                            )
                        }
                    }
                }
                if (rowPresets.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        // Custom Palette Editor
        item { SectionHeader("Custom Accent Palette") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Fine-tune individual accent roles with swatches, hex codes, or RGB sliders.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SegmentedSwitcher(
                        selectedIndex = editingSlot,
                        options = listOf("Primary", "Secondary", "Tertiary"),
                        onSelect = { editingSlot = it },
                    )

                    // Quick Swatches (2 rows of 6)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QUICK_SWATCHES.chunked(6).forEach { swatchRow ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                swatchRow.forEach { swatch ->
                                    val selected = swatch.toArgb() == currentSlotColor.toArgb()
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(swatch)
                                            .border(
                                                width = if (selected) 3.dp else 1.dp,
                                                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.25f),
                                                shape = CircleShape,
                                            )
                                            .clickable {
                                                hexInput = ThemeConfig.toHex(swatch)
                                                applySlotColor(swatch)
                                            },
                                    )
                                }
                            }
                        }
                    }

                    // Hex input & current color box
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(currentSlotColor)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                        )
                        OutlinedTextField(
                            value = hexInput,
                            onValueChange = { input ->
                                hexInput = input.uppercase().take(7)
                                ThemeConfig.parseHex(hexInput)?.let { parsed ->
                                    applySlotColor(parsed)
                                }
                            },
                            label = { Text("Hex Color (#RRGGBB)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // RGB Sliders
                    val r = (currentSlotColor.red * 255).roundToInt()
                    val g = (currentSlotColor.green * 255).roundToInt()
                    val b = (currentSlotColor.blue * 255).roundToInt()

                    RgbSliderRow("R", r, Color(0xFFFF5252)) { newR ->
                        val c = Color(newR, g, b)
                        hexInput = ThemeConfig.toHex(c)
                        applySlotColor(c)
                    }
                    RgbSliderRow("G", g, Color(0xFF2DB84C)) { newG ->
                        val c = Color(r, newG, b)
                        hexInput = ThemeConfig.toHex(c)
                        applySlotColor(c)
                    }
                    RgbSliderRow("B", b, Color(0xFF3B82F6)) { newB ->
                        val c = Color(r, g, newB)
                        hexInput = ThemeConfig.toHex(c)
                        applySlotColor(c)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
    )
}

@Composable
private fun RgbSliderRow(
    channel: String,
    value: Int,
    trackColor: Color,
    onValueChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            channel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = trackColor,
            modifier = Modifier.width(18.dp),
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = trackColor,
                activeTrackColor = trackColor,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(30.dp),
        )
    }
}

// -----------------------------------------------------------------------------
// LAYER 2: DATA SOURCES & INTEGRATIONS
// -----------------------------------------------------------------------------

@Composable
private fun DataIntegrationsSubScreen(
    runsVm: RunsViewModel,
    activity: Activity,
    listState: LazyListState,
    onHelp: (SetupGuide) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SubScreenHeader(
                title = "Data & Integrations",
                subtitle = "Manage workout data, live synchronization, and storage options.",
                onBack = onBack,
            )
        }

        item { SectionHeader("Garmin Connect Integration") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    GarminSection(runsVm)
                }
            }
        }

        item { SectionHeader("Samsung Health & Data Storage") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    DataManagementSection(runsVm, activity, onHelp = onHelp)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// LAYER 2: BACKUP & RESTORE
// -----------------------------------------------------------------------------

@Composable
private fun BackupSubScreen(
    backupVm: BackupViewModel,
    listState: LazyListState,
    onRestored: (BackupKind) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SubScreenHeader(
                title = "Backup & Cloud Restore",
                subtitle = "Safeguard API keys, athlete profile, and workout logs with encrypted backups.",
                onBack = onBack,
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    BackupSection(backupVm, onRestored = onRestored)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// LAYER 2: SETUP GUIDES
// -----------------------------------------------------------------------------

@Composable
private fun SetupGuidesSubScreen(
    listState: LazyListState,
    onBack: () -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SubScreenHeader(
                title = "Setup Guides",
                subtitle = "Step-by-step instructions for data exports and integration setup.",
                onBack = onBack,
            )
        }

        items(SetupGuide.entries, key = { "guide_${it.name}" }) { guide ->
            GuideCard(guide)
        }
    }
}

// -----------------------------------------------------------------------------
// REUSABLE COMPONENTS & LAYER 3 EDITORS
// -----------------------------------------------------------------------------

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ProviderEditor(vm: AiViewModel, initial: ProviderForm, onDone: () -> Unit) {
    var form by remember { mutableStateOf(initial) }
    val models by vm.models.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        thinkingLevel = preset.defaultThinkingLevel,
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
            Picker(
                label = "Thinking Level",
                selected = form.thinkingLevel,
                options = com.healthanalyzer.app.ai.ThinkingLevel.entries,
                display = { "${it.label} — ${it.description}" },
                onSelect = { form = form.copy(thinkingLevel = it) },
            )
            Text(
                "Sets reasoning effort or thinking budget for reasoning models (OpenAI o-series, Gemini 2.5/3, Claude 3.7, DeepSeek). Default lets the model decide.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
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
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
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

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                if (initial.id != 0L) {
                    TextButton(onClick = {
                        val copyName = if (name.trim() == initial.name) "${name.trim()} (Copy)" else name.trim()
                        onSave(initial.copy(id = 0L, name = copyName, systemPrompt = system, userTemplate = user, savesBaseline = savesBaseline))
                    }) {
                        Text("Duplicate")
                    }
                } else {
                    Spacer(Modifier.size(38.dp))
                }
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
            PlaceholderAwareTextField(
                value = system,
                onValueChange = { system = it },
                label = "System Prompt",
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            PlaceholderAwareTextField(
                value = user,
                onValueChange = { user = it },
                label = "User Message Template",
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
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Supported Placeholders", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Tip: Type {{ anywhere in the prompt to insert placeholders from the dropdown list.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
                    Text(PromptRenderer.PLACEHOLDER_HELP, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (initial.id != 0L) {
                    OutlinedButton(
                        onClick = {
                            val copyName = if (name.trim() == initial.name) "${name.trim()} (Copy)" else name.trim()
                            onSave(initial.copy(id = 0L, name = copyName, systemPrompt = system, userTemplate = user, savesBaseline = savesBaseline))
                        },
                        enabled = name.isNotBlank() && user.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Save as Copy")
                    }
                }
                Button(
                    onClick = {
                        onSave(initial.copy(name = name.trim(), systemPrompt = system, userTemplate = user, savesBaseline = savesBaseline))
                    },
                    enabled = name.isNotBlank() && user.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (initial.id == 0L) "Save Prompt Template" else "Save Changes", fontWeight = FontWeight.Bold)
                }
            }
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
    var useCustomZones by remember(profile) { mutableStateOf(profile.useCustomZones) }
    val zoneStarts = remember(profile) {
        androidx.compose.runtime.mutableStateListOf(*Array(4) { profile.customZoneStarts.getOrNull(it)?.toString().orEmpty() })
    }

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use my own heart-rate zones", fontWeight = FontWeight.SemiBold)
                Text(
                    "Off: 5 zones at 60/70/80/90% of max HR. On: enter where zones 2–5 start, e.g. copied from " +
                        "Samsung Health → Exercise settings → Heart rate zones (the SDK can't read them).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = useCustomZones, onCheckedChange = { useCustomZones = it })
        }
        val parsedZones = zoneStarts.map { it.toIntOrNull() ?: 0 }
        val zonesValid = AthleteProfile.validZoneStarts(parsedZones)
        if (useCustomZones) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until 4).forEach { i ->
                    NumberField(zoneStarts[i], { zoneStarts[i] = it }, "Z${i + 2} from", Modifier.weight(1f))
                }
            }
            if (!zonesValid) {
                Text(
                    "Enter 4 rising bpm values (40–230).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
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
                        useCustomZones = useCustomZones,
                        customZoneStarts = if (zonesValid) parsedZones else profile.customZoneStarts,
                    ),
                )
            },
            enabled = !useCustomZones || zonesValid,
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
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
