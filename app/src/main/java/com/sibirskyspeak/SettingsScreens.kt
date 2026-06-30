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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.learning.Doctrine
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
    onImport: () -> Unit,
    onExport: () -> Unit,
    onFullBackup: () -> Unit,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onAdd: () -> Unit,
    onDailyGoal: (Int) -> Unit,
    onSessionSize: (Int) -> Unit,
    onNewCardsPerDay: (Int) -> Unit,
    onRetention: (Double) -> Unit,
    onDoctrine: (Doctrine) -> Unit,
    onPlaceAfterLevel: (String) -> Unit,
    onReminderEnabled: (Boolean) -> Unit,
    onReminderHour: (Int) -> Unit,
    onFontScale: (Float) -> Unit,
    onSearch: (String) -> Unit,
    onSpeakRussian: (String) -> Unit
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
                            DoctrinePicker(selected = state.doctrineSetting, onSelect = onDoctrine)
                        }
                        SectionCard {
                            Text("Placement", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Already know earlier course material? Start after a level and mark those notes known.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf("A1", "A2", "B1", "B2").forEach { level ->
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
                            val readerWordCount = state.readerBody.trim().split(Regex("\\s+")).count { it.isNotBlank() }
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
                                Button(onClick = onImport, enabled = state.importText.isNotBlank()) { Text("Import Notes") }
                                OutlinedButton(onClick = onExport) { Text("Export") }
                                OutlinedButton(onClick = onFullBackup) { Text("Full Backup") }
                                if (state.exportText.isNotBlank()) {
                                    OutlinedButton(onClick = { saveLauncher.launch("sibirskyspeak-export.jsonl") }) { Text("Save to File") }
                                }
                            }
                            Text(
                                "Export saves note content. Full Backup also includes SRS scheduling.",
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
                    }
                }
            }
        }
    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DoctrinePicker(selected: Doctrine, onSelect: (Doctrine) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Doctrine.entries.forEach { doctrine ->
            FilterChip(
                selected = selected == doctrine,
                onClick = { onSelect(doctrine) },
                label = { Text(doctrine.name.lowercase().replaceFirstChar { it.uppercase() }) }
            )
        }
    }
}

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
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
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
