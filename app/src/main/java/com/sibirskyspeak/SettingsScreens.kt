package com.sibirskyspeak

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.learning.GoalVerdict
import com.sibirskyspeak.learning.PlacementTest
import com.sibirskyspeak.review.ReviewUiState
import com.sibirskyspeak.review.LeechItem
import com.sibirskyspeak.review.TemporarySessionMode
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Settings / import-export
// ---------------------------------------------------------------------------

internal enum class SettingsArea(val label: String, val summary: String) {
    STUDY("Study", "Pace, placement, and reminders"),
    REPAIR("Repair", "Difficult and parked cards"),
    READER("Reader", "Text size, new texts, and deck search"),
    DATA("Data", "Import, export, and backups")
}

@Composable
private fun RepairSettingsPanel(
    state: ReviewUiState,
    onLoadLeeches: () -> Unit,
    onReleaseLeech: (LeechItem) -> Unit,
    onSaveLeechEdit: (LeechItem, String?, String?, String?, String?) -> Unit
) {
    var editingLeech by remember { mutableStateOf<LeechItem?>(null) }
    val problemCards = state.sessionPlan?.problemCards.orEmpty()
    val leechCount = state.dashboardStats?.leechCount ?: state.leeches.size
    LaunchedEffect(Unit) { onLoadLeeches() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProblemCardAuditPanel(state.sessionPlan)
        if (problemCards.isEmpty()) {
            SectionCard {
                Text("Cards needing repair", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "No repeatedly missed active cards need attention right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (leechCount > 0) {
            LeechCard(
                leeches = state.leeches,
                leechCount = leechCount,
                onRelease = onReleaseLeech,
                onEdit = { editingLeech = it }
            )
        }
    }
    editingLeech?.let { item ->
        EditCardDialog(
            note = item.note,
            onDismiss = { editingLeech = null },
            onSave = { translation, example, exampleTranslation, mnemonic ->
                onSaveLeechEdit(item, translation, example, exampleTranslation, mnemonic)
                editingLeech = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImportExportPanel(
    state: ReviewUiState,
    selectedArea: SettingsArea,
    onSelectedArea: (SettingsArea) -> Unit,
    onImportText: (String) -> Unit,
    onPreviewImport: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onFullBackup: () -> Unit,
    onBackupTree: (String) -> Unit,
    onAutomaticPublicBackup: (Boolean) -> Unit,
    onConfigureBackupEncryption: (String) -> Unit,
    onClearBackupEncryption: () -> Unit,
    onDismissBackupRecoveryKey: () -> Unit,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onSource: (String) -> Unit,
    onAdd: () -> Unit,
    onDailyGoal: (Int) -> Unit,
    onSessionSize: (Int) -> Unit,
    onNewCardsPerDay: (Int) -> Unit,
    onRetention: (Double) -> Unit,
    onPreviewGoalFeasibility: (String, Long) -> Unit,
    onCommitLearningGoal: (String, Long) -> Unit,
    onAbandonLearningGoal: () -> Unit,
    onAdaptiveEnabled: (Boolean) -> Unit,
    onResetAdaptivePacing: () -> Unit,
    onTemporarySessionMode: (TemporarySessionMode) -> Unit,
    onPlaceAfterLevel: (String) -> Unit,
    onStartPlacementTest: () -> Unit,
    onAnswerPlacementQuestion: (Int) -> Unit,
    onApplyPlacementResult: () -> Unit,
    onApplyPlacementAtLevel: (String?) -> Unit,
    onDismissPlacementTest: () -> Unit,
    onReminderEnabled: (Boolean) -> Unit,
    onReminderHour: (Int) -> Unit,
    onFontScale: (Float) -> Unit,
    onOnlineGlossLookup: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onSpeakRussian: (String) -> Unit,
    onLoadLeeches: () -> Unit,
    onReleaseLeech: (LeechItem) -> Unit,
    onSaveLeechEdit: (LeechItem, String?, String?, String?, String?) -> Unit,
    onDebugStartCardType: (com.sibirskyspeak.data.CardType) -> Unit
) {
    val context = LocalContext.current
    // Save the exported JSON Lines to a user-chosen file via the system picker.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && state.exportText.isNotBlank()) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(state.exportText.toByteArray()) }
            }
        }
    }
    val backupTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                onBackupTree(uri.toString())
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard {
            Text(stringResource(R.string.label_settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                selectedArea.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            SettingsAreaPicker(selected = selectedArea, onSelect = onSelectedArea)
        }
        AnimatedContent(
            targetState = selectedArea,
            transitionSpec = {
                (fadeIn(tween(160)) + slideInHorizontally(tween(180)) { it / 10 })
                    .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(140)) { -it / 12 })
                    .using(SizeTransform(clip = false))
            },
            label = "settings-area"
        ) { area ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (area) {
                    SettingsArea.STUDY -> {
                        var dailyGoal by remember(state.dailyGoalSetting) { mutableFloatStateOf(state.dailyGoalSetting.toFloat()) }
                        var sessionSize by remember(state.sessionSizeSetting) { mutableFloatStateOf(state.sessionSizeSetting.toFloat()) }
                        var newCardsPerDay by remember(state.newCardsPerDaySetting) { mutableFloatStateOf(state.newCardsPerDaySetting.toFloat()) }
                        var retention by remember(state.retentionSetting) { mutableFloatStateOf(state.retentionSetting.toFloat()) }
                        var reminderHour by remember(state.reminderHour) { mutableFloatStateOf(state.reminderHour.toFloat()) }
                        SectionCard {
                            Text("Daily workload", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Choose a sustainable target. The tutor estimates minutes from your recent pace and shifts toward easier review when fatigue or accuracy dips; it never discards assigned cards. Changes apply after you finish moving a slider.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            SettingSlider(
                                label = "Daily learning target",
                                valueLabel = "${dailyGoal.roundToInt()} actions",
                                value = dailyGoal,
                                range = SettingsStore.MIN_DAILY_GOAL.toFloat()..SettingsStore.MAX_DAILY_GOAL.toFloat(),
                                steps = SettingsStore.MAX_DAILY_GOAL - SettingsStore.MIN_DAILY_GOAL - 1,
                                onChange = { dailyGoal = it },
                                onChangeFinished = { onDailyGoal(dailyGoal.roundToInt()) }
                            )
                            Spacer(Modifier.height(10.dp))
                            SettingSlider(
                                label = "Session target",
                                valueLabel = "${sessionSize.roundToInt()} cards",
                                value = sessionSize,
                                range = SettingsStore.MIN_SESSION_SIZE.toFloat()..SettingsStore.MAX_SESSION_SIZE.toFloat(),
                                steps = SettingsStore.MAX_SESSION_SIZE - SettingsStore.MIN_SESSION_SIZE - 1,
                                onChange = { sessionSize = it },
                                onChangeFinished = { onSessionSize(sessionSize.roundToInt()) }
                            )
                            Spacer(Modifier.height(10.dp))
                            SettingSlider(
                                label = "New cards per day",
                                valueLabel = "${newCardsPerDay.roundToInt()} cards",
                                value = newCardsPerDay,
                                range = SettingsStore.MIN_NEW_CARDS_PER_DAY.toFloat()..SettingsStore.MAX_NEW_CARDS_PER_DAY.toFloat(),
                                steps = SettingsStore.MAX_NEW_CARDS_PER_DAY - SettingsStore.MIN_NEW_CARDS_PER_DAY - 1,
                                onChange = { newCardsPerDay = it },
                                onChangeFinished = { onNewCardsPerDay(newCardsPerDay.roundToInt()) }
                            )
                            Spacer(Modifier.height(10.dp))
                            SettingSlider(
                                label = "Desired retention",
                                valueLabel = "${(retention * 100).roundToInt()}%",
                                value = retention,
                                range = SettingsStore.MIN_RETENTION.toFloat()..SettingsStore.MAX_RETENTION.toFloat(),
                                steps = 16,
                                rangeLabel = { "${(it * 100).roundToInt()}%" },
                                onChange = { retention = it },
                                onChangeFinished = { onRetention(retention.toDouble()) }
                            )
                            Text(
                                "Retention is the share of mature cards the scheduler aims to have you recall at review time. Higher targets mean shorter intervals and more review work.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SectionCard {
                            Text("Learning goal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Commit to a CEFR level and a date. Once set, the tutor nudges your pace toward it while adapting card difficulty and order to fatigue and accuracy.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))

                            val cefrLevels = listOf("A1", "A2", "B1", "B2", "C1", "C2")
                            val hasGoal = state.goalTargetLevelSetting.isNotEmpty()
                            var selectedLevel by remember(state.goalTargetLevelSetting) {
                                mutableStateOf(state.goalTargetLevelSetting.ifEmpty { "B2" })
                            }
                            val nowDay = remember {
                                val now = System.currentTimeMillis()
                                val offset = java.util.TimeZone.getDefault().getOffset(now).toLong()
                                (now + offset) / 86_400_000L
                            }
                            val initialMonths = if (hasGoal) {
                                (((state.goalTargetDateEpochDaySetting - nowDay).coerceAtLeast(0)) / 30f).roundToInt().coerceIn(3, 60)
                            } else 18
                            var months by remember(state.goalTargetDateEpochDaySetting) { mutableFloatStateOf(initialMonths.toFloat()) }

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                cefrLevels.forEach { level ->
                                    FilterChip(
                                        selected = selectedLevel == level,
                                        onClick = { selectedLevel = level },
                                        label = { Text(level) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            SettingSlider(
                                label = "Target date",
                                valueLabel = "${months.roundToInt()} months from now",
                                value = months,
                                range = 3f..60f,
                                steps = 56,
                                onChange = { months = it },
                                onChangeFinished = {}
                            )
                            // Live, arithmetic-only preview — recomputed whenever the
                            // chip or slider changes, never on every recomposition.
                            LaunchedEffect(selectedLevel, months.roundToInt()) {
                                onPreviewGoalFeasibility(selectedLevel, nowDay + months.roundToInt() * 30L)
                            }
                            Spacer(Modifier.height(10.dp))
                            state.goalFeasibilityPreview?.let { feasibility ->
                                val (verdictLabel, verdictColor) = when (feasibility.verdict) {
                                    GoalVerdict.COMFORTABLE -> "Comfortable at your current pace." to MaterialTheme.colorScheme.secondary
                                    GoalVerdict.STRETCH -> "A stretch — the tutor will nudge your pace up toward it." to MaterialTheme.colorScheme.tertiary
                                    GoalVerdict.UNSUSTAINABLE -> "Not realistic without risking burnout — consider a later date." to MaterialTheme.colorScheme.error
                                }
                                if (feasibility.currentPace > 0.0) {
                                    Text(
                                        "Needs ~${feasibility.requiredPace.roundToInt()} new words/day " +
                                            "(sustainable baseline: ~${feasibility.currentPace.roundToInt()}/day). $verdictLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = verdictColor
                                    )
                                } else {
                                    Text(
                                        "The tutor is still building a reliable pace estimate; keep the date flexible for now.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    onCommitLearningGoal(selectedLevel, nowDay + months.roundToInt() * 30L)
                                }) {
                                    Text(if (hasGoal) "Update goal" else "Set goal")
                                }
                                if (hasGoal) {
                                    OutlinedButton(onClick = onAbandonLearningGoal) { Text("Drop goal") }
                                }
                            }
                        }
                        SectionCard {
                            Text("Study Pace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Adaptive tutor", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        state.sessionPlan?.adaptiveReason ?: "Learns workload from your results",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    state.sessionPlan?.let {
                                        Text("Influence ${(it.adaptiveTrust * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Switch(checked = state.adaptiveEnabled, onCheckedChange = onAdaptiveEnabled)
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = onResetAdaptivePacing, modifier = Modifier.fillMaxWidth()) {
                                Text("Reset pacing + open a fuller day")
                            }
                            Text(
                                "Keeps your cards and review history, ignores old test-session evidence, and uses your configured limits today.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SectionCard {
                            Text("This session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Choose a safe temporary emphasis. It resets after you start a session and does not retrain the adaptive tutor.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    TemporarySessionMode.BALANCED to "Balanced",
                                    TemporarySessionMode.REVIEWS_ONLY to "Reviews only",
                                    TemporarySessionMode.RECOVERY to "Recovery",
                                    TemporarySessionMode.READER_ONLY to "Reader only",
                                    TemporarySessionMode.FOCUS to "Focus · 8 cards"
                                ).forEach { (mode, label) ->
                                    FilterChip(
                                        selected = state.temporarySessionMode == mode,
                                        onClick = { onTemporarySessionMode(mode) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                        SectionCard {
                            Text("Placement", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Not sure where you stand? Take a two-minute quiz for a suggested level, " +
                                    "or jump straight to a level you already know below.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onStartPlacementTest, modifier = Modifier.fillMaxWidth()) {
                                Text("Take a quick placement quiz")
                            }
                            Spacer(Modifier.height(12.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Mirrors LearningRepository.CEFR_LEVELS. C1 was previously
                                // missing here even though the backend already supported it —
                                // the picker just hadn't kept up with the curriculum's ceiling.
                                listOf("A1", "A2", "B1", "B2", "C1", "C2").forEach { level ->
                                    OutlinedButton(onClick = { onPlaceAfterLevel(level) }) {
                                        Text("After $level")
                                    }
                                }
                            }
                        }
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Daily reminder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Switch(checked = state.reminderEnabled, onCheckedChange = onReminderEnabled)
                            }
                            AnimatedVisibility(visible = state.reminderEnabled) {
                                Column {
                                    Spacer(Modifier.height(10.dp))
                                    SettingSlider(
                                        label = "Reminder time",
                                        valueLabel = "%02d:00".format(reminderHour.roundToInt()),
                                        value = reminderHour,
                                        range = 0f..23f,
                                        rangeLabel = { "%02d:00".format(it.toInt()) },
                                        onChange = { reminderHour = it },
                                        onChangeFinished = { onReminderHour(reminderHour.roundToInt()) }
                                    )
                                }
                            }
                        }
                    }
                    SettingsArea.REPAIR -> RepairSettingsPanel(
                        state = state,
                        onLoadLeeches = onLoadLeeches,
                        onReleaseLeech = onReleaseLeech,
                        onSaveLeechEdit = onSaveLeechEdit
                    )
                    SettingsArea.READER -> {
                        SectionCard {
                            Text("Reader", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            SettingSlider(
                                label = "Text size",
                                valueLabel = "${(state.readerFontScale * 100).toInt()}%",
                                value = state.readerFontScale,
                                range = SettingsStore.MIN_FONT_SCALE..SettingsStore.MAX_FONT_SCALE,
                                rangeLabel = { "${(it * 100).toInt()}%" },
                                onChange = onFontScale
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Online gloss assist", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Uses a free public translation service when an unknown reader word is tapped. It is enabled by default, may be imperfect, and never blocks offline reading.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = state.onlineGlossLookupEnabled,
                                    onCheckedChange = onOnlineGlossLookup,
                                    modifier = Modifier.semantics { contentDescription = "Online gloss assist" }
                                )
                            }
                        }
                        SectionCard {
                            Text("Add Reader Text", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            val readerBodyReady = state.readerBody.isNotBlank()
                            val readerWordCount = remember(state.readerBody) {
                                state.readerBody.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                            }
                            OutlinedTextField(value = state.readerTitle, onValueChange = onTitle, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, label = { Text("Text title") })
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(value = state.readerSource, onValueChange = onSource, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, singleLine = true, label = { Text(stringResource(R.string.reader_source_license_label)) }, supportingText = { Text(stringResource(R.string.reader_source_license_help)) })
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(value = state.readerBody, onValueChange = onBody, modifier = Modifier.fillMaxWidth(), minLines = 4, shape = MaterialTheme.shapes.small, label = { Text("Russian text") })
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (readerBodyReady) "${formatCount(readerWordCount)} words ready for tap-to-learn reading." else "Paste Russian text to enable reader import.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            // Keep both actions reachable at 200% text size and on
                            // narrow phones; a fixed Row could push Add Text off-screen.
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(onClick = { onSpeakRussian(state.readerBody) }, enabled = readerBodyReady) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Preview Audio")
                                }
                                Button(onClick = onAdd, enabled = readerBodyReady) { Text("Add Text") }
                            }
                        }
                        SectionCard {
                            Text("Search Deck", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = state.searchQuery,
                                onValueChange = onSearch,
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                                singleLine = true,
                                label = { Text("Russian, lemma, or meaning") }
                            )
                            Spacer(Modifier.height(10.dp))
                            if (state.searchQuery.isBlank()) {
                                Text(
                                    "Search your deck before reading to preview forms and hear words aloud.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                if (state.searchResults.isEmpty()) {
                                    Text("No deck matches for this search.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        state.searchResults.take(20).forEach { note ->
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(note.russian, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                                    Text(note.translation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                IconButton(onClick = { onSpeakRussian(note.russian) }) {
                                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear word")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    SettingsArea.DATA -> {
                        var encryptionPassword by remember { mutableStateOf("") }
                        SectionCard {
                            Text("Import / Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Paste JSON Lines to add notes, or export a copy of your deck and review state.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "For cloze/context practice, include exampleSentence plus a real sentence meaning in exampleTranslation.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(value = state.importText, onValueChange = onImportText, modifier = Modifier.fillMaxWidth(), minLines = 6, shape = MaterialTheme.shapes.small, label = { Text("JSON Lines notes") })
                            Text(
                                "Import is additive; existing notes and scheduling stay intact. One-word glosses do not count as readable examples.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = onPreviewImport, enabled = state.importText.isNotBlank()) { Text("Preview Restore") }
                                Button(onClick = onImport, enabled = state.importPreview?.valid == true) { Text("Commit Import") }
                                OutlinedButton(onClick = onExport) { Text("Export") }
                                OutlinedButton(onClick = onFullBackup) { Text("Full Backup") }
                                OutlinedButton(onClick = { backupTreeLauncher.launch(null) }) {
                                    Text(if (state.backupTreeUri.isBlank()) "Choose Custom Backup Folder (optional)" else "Change Custom Backup Folder")
                                }
                                if (state.exportText.isNotBlank()) {
                                    OutlinedButton(onClick = { saveLauncher.launch("sibirskyspeak-export.jsonl") }) { Text("Save to File") }
                                }
                            }
                            state.importPreview?.let { preview ->
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    if (preview.valid) "Ready: ${preview.notes} notes · ${preview.cards} cards · ${preview.reviews} reviews · ${preview.readerTexts} texts · settings ${if (preview.restoresSettings) "included" else "not included"}"
                                    else "Cannot import: ${preview.errors.joinToString("; ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (preview.valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (!state.automaticPublicBackupEnabled) {
                                    "Last local backup: ${if (state.backupLastValidatedAt > 0) "validated · ${state.backupLastSizeBytes / 1024 / 1024} MB" else "not yet validated"}. Automatic Downloads backup is off" +
                                        (if (state.backupTreeUri.isNotBlank()) "; custom folder is available" else "; manual export is available") + "."
                                } else {
                                    "Last local backup: ${if (state.backupLastValidatedAt > 0) "validated · ${state.backupLastSizeBytes / 1024 / 1024} MB" else "not yet validated"}. " +
                                        "Durable copy (Downloads/SibirskySpeak" + (if (state.backupTreeUri.isNotBlank()) " + your custom folder" else "") + "): " +
                                        (if (state.backupLastDurableAt > 0) "current" else "waiting for next completed session") + "."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (state.automaticPublicBackupEnabled) {
                                    "Export saves note content. Full Backup includes SRS, and mirrors rolling snapshots to Downloads/SibirskySpeak automatically (Android 10+, no setup needed) plus your custom folder if you've chosen one."
                                } else {
                                    "Export saves note content. Full Backup includes SRS locally; automatic public mirroring is off until you turn it back on."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Automatic Downloads backup", fontWeight = FontWeight.SemiBold)
                                    Text(
                                    "Enabled by default: a rolling copy is saved to the public Downloads/SibirskySpeak folder. This copy is not encrypted unless you configure external backup encryption.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    modifier = Modifier
                                        .testTag(TestTags.SETTINGS_AUTOMATIC_PUBLIC_BACKUP)
                                        .semantics { contentDescription = "Automatic Downloads backup" },
                                    checked = state.automaticPublicBackupEnabled,
                                    onCheckedChange = onAutomaticPublicBackup
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.backup_encryption_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (state.externalBackupEncryptionConfigured) {
                                    stringResource(R.string.backup_encryption_enabled_body)
                                } else {
                                    stringResource(R.string.backup_encryption_disabled_body)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.externalBackupEncryptionConfigured) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = onClearBackupEncryption) {
                                    Text(stringResource(R.string.backup_encryption_disable))
                                }
                            } else {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = encryptionPassword,
                                    onValueChange = { encryptionPassword = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    label = { Text(stringResource(R.string.backup_encryption_password_label)) }
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        onConfigureBackupEncryption(encryptionPassword)
                                        encryptionPassword = ""
                                    },
                                    enabled = encryptionPassword.length >= 8,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(stringResource(R.string.backup_encryption_enable)) }
                            }
                            if (state.exportText.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(value = state.exportText, onValueChange = {}, modifier = Modifier.fillMaxWidth(), minLines = 6, shape = MaterialTheme.shapes.small, label = { Text("Exported JSON Lines") })
                            }
                        }
                        SectionCard {
                            Text("Content credits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            if (state.contentProvenance.isEmpty()) {
                                Text(
                                    "Content provenance is unavailable until the bundled manifest is read.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.contentProvenance.forEach { source ->
                                        Column {
                                            Text(source.attribution, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                            Text("License: ${source.license}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Text(
                                        "Russian audio is generated on-device by your system TTS.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (com.sibirskyspeak.BuildConfig.DEBUG) {
                            SectionCard {
                                Text("Debug: jump to card type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Debug builds only. Opens an existing card of the chosen type as an unscored preview " +
                                        "— the adaptive session may otherwise take dozens of turns to surface a rare type.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(12.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    com.sibirskyspeak.data.CardType.entries
                                        .filterNot { it == com.sibirskyspeak.data.CardType.STRESS_MARK }
                                        .forEach { cardType ->
                                        OutlinedButton(onClick = { onDebugStartCardType(cardType) }) {
                                            Text(cardType.name)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    state.backupRecoveryKey?.let { recoveryKey ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = onDismissBackupRecoveryKey,
            title = { Text(stringResource(R.string.backup_encryption_recovery_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.backup_encryption_recovery_body))
                    Text(recoveryKey, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(recoveryKey))
                    onDismissBackupRecoveryKey()
                }) { Text(stringResource(R.string.backup_encryption_copy_close)) }
            },
            dismissButton = { TextButton(onClick = onDismissBackupRecoveryKey) { Text(stringResource(R.string.backup_encryption_close)) } }
        )
    }
    if (state.placementActive) {
        PlacementQuizDialog(
            state = state,
            onAnswer = onAnswerPlacementQuestion,
            onApply = onApplyPlacementResult,
            onApplyAtLevel = onApplyPlacementAtLevel,
            onDismiss = onDismissPlacementTest
        )
    }
}

@Composable
internal fun PlacementQuizDialog(
    state: ReviewUiState,
    onAnswer: (Int) -> Unit,
    onApply: () -> Unit,
    onApplyAtLevel: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (state.placementCompleted) "Suggested placement"
                else "Placement quiz · ${state.placementQuestionIndex + 1} / ${PlacementTest.QUESTIONS.size}"
            )
        },
        text = {
            if (state.placementCompleted) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                    state.placementResult?.let { "You already know material through $it. Place after $it and mark those notes known?" }
                        ?: "This looks like a good place to start from the beginning — no placement needed."
                    )
                    Text(
                        "${state.placementAnswers.count { it }} / ${state.placementAnswers.size} correct. This quiz measures recognition only; short production reviews will verify transfer before the app treats the words as mastered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.placementResult?.let { suggested ->
                        val lower = PlacementTest.LEVELS.getOrNull(PlacementTest.LEVELS.indexOf(suggested) - 1)
                        if (lower != null) {
                            OutlinedButton(onClick = { onApplyAtLevel(lower) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Start conservatively after $lower")
                            }
                        }
                    }
                }
            } else {
                val question = PlacementTest.QUESTIONS.getOrNull(state.placementQuestionIndex)
                if (question == null) {
                    Text("Something went wrong loading the quiz.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(question.prompt, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        question.choices.forEachIndexed { index, choice ->
                            OutlinedButton(onClick = { onAnswer(index) }, modifier = Modifier.fillMaxWidth()) {
                                Text(choice)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.placementCompleted) {
                TextButton(onClick = onApply) { Text(if (state.placementResult != null) "Apply" else "OK") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (state.placementCompleted) "Not now" else stringResource(R.string.action_cancel)) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsAreaPicker(selected: SettingsArea, onSelect: (SettingsArea) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsArea.entries.forEach { area ->
            FilterChip(
                selected = selected == area,
                onClick = { onSelect(area) },
                label = { Text(area.label) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    rangeLabel: (Float) -> String = { it.settingRangeLabel() },
    onChange: (Float) -> Unit,
    onChangeFinished: (() -> Unit)? = null
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            steps = steps,
            // Matches the LinearProgressIndicator fix elsewhere: the default M3 stop
            // indicator dot at the track's end reads as a rendering glitch, not a range hint.
            track = { sliderState -> SliderDefaults.Track(sliderState = sliderState, drawStopIndicator = {}) }
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(rangeLabel(range.start), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(rangeLabel(range.endInclusive), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun Float.settingRangeLabel(): String =
    if (this % 1f == 0f) toInt().toString() else "${(this * 100).toInt()}%"

// ---------------------------------------------------------------------------
// Shared building blocks
