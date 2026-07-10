package com.sibirskyspeak

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.learning.PlacementTest
import com.sibirskyspeak.review.ReviewUiState

// ---------------------------------------------------------------------------
// Settings / import-export
// ---------------------------------------------------------------------------

internal enum class SettingsArea(val label: String, val summary: String) {
    STUDY("Study", "Pace, placement, and reminders"),
    READER("Reader", "Text size, new texts, and deck search"),
    DATA("Data", "Import, export, and backups")
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
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onAdd: () -> Unit,
    onDailyGoal: (Int) -> Unit,
    onSessionSize: (Int) -> Unit,
    onNewCardsPerDay: (Int) -> Unit,
    onRetention: (Double) -> Unit,
    onAdaptiveEnabled: (Boolean) -> Unit,
    onPlaceAfterLevel: (String) -> Unit,
    onStartPlacementTest: () -> Unit,
    onAnswerPlacementQuestion: (Int) -> Unit,
    onApplyPlacementResult: () -> Unit,
    onDismissPlacementTest: () -> Unit,
    onReminderEnabled: (Boolean) -> Unit,
    onReminderHour: (Int) -> Unit,
    onFontScale: (Float) -> Unit,
    onSearch: (String) -> Unit,
    onSpeakRussian: (String) -> Unit,
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
                            if (state.reminderEnabled) {
                                Spacer(Modifier.height(10.dp))
                                SettingSlider(
                                label = "Reminder time",
                                valueLabel = "%02d:00".format(state.reminderHour),
                                value = state.reminderHour.toFloat(),
                                range = 0f..23f,
                                rangeLabel = { "%02d:00".format(it.toInt()) },
                                onChange = { onReminderHour(it.toInt()) }
                            )
                            }
                        }
                    }
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
                            OutlinedTextField(value = state.readerBody, onValueChange = onBody, modifier = Modifier.fillMaxWidth(), minLines = 4, shape = MaterialTheme.shapes.small, label = { Text("Russian text") })
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (readerBodyReady) "${formatCount(readerWordCount)} words ready for tap-to-learn reading." else "Paste Russian text to enable reader import.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
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
                                "Last local backup: ${if (state.backupLastValidatedAt > 0) "validated · ${state.backupLastSizeBytes / 1024 / 1024} MB" else "not yet validated"}. " +
                                    "Durable copy (Downloads/SibirskySpeak" + (if (state.backupTreeUri.isNotBlank()) " + your custom folder" else "") + "): " +
                                    (if (state.backupLastDurableAt > 0) "current" else "waiting for next completed session") + ".",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Export saves note content. Full Backup includes SRS, and mirrors rolling snapshots to Downloads/SibirskySpeak automatically " +
                                    "(Android 10+, no setup needed) plus your custom folder if you've chosen one.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.exportText.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(value = state.exportText, onValueChange = {}, modifier = Modifier.fillMaxWidth(), minLines = 6, shape = MaterialTheme.shapes.small, label = { Text("Exported JSON Lines") })
                            }
                        }
                        SectionCard {
                            Text("Content credits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Example sentences from Tatoeba (tatoeba.org), licensed under CC BY 2.0 FR. Corpus packaging may use the OPUS Tatoeba mirror. Russian audio is generated on-device by your system TTS.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                                    com.sibirskyspeak.data.CardType.entries.forEach { cardType ->
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
    if (state.placementActive) {
        PlacementQuizDialog(
            state = state,
            onAnswer = onAnswerPlacementQuestion,
            onApply = onApplyPlacementResult,
            onDismiss = onDismissPlacementTest
        )
    }
}

@Composable
internal fun PlacementQuizDialog(
    state: ReviewUiState,
    onAnswer: (Int) -> Unit,
    onApply: () -> Unit,
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
                Text(
                    state.placementResult?.let { "You already know material through $it. Place after $it and mark those notes known?" }
                        ?: "This looks like a good place to start from the beginning — no placement needed."
                )
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
    rangeLabel: (Float) -> String = { it.settingRangeLabel() },
    onChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
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
