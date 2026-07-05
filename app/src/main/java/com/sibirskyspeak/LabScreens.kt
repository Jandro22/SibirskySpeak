package com.sibirskyspeak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sibirskyspeak.data.WeeklyReport
import com.sibirskyspeak.review.ReviewUiState
import org.json.JSONObject

@Composable internal fun LabPanel(
    state: ReviewUiState,
    onStartCheckpoint: () -> Unit = {},
    onSubmitCheckpointAnswer: (String) -> Unit = {},
    onDismissCheckpoint: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Learning Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Diagnostics and experiments; FSRS remains the scheduler.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SkillRadarCard(state.skillRatings)
        RivalProgressCard(state.rivalState, state.matchHistory)
        CheckpointCard(state, onStartCheckpoint, onSubmitCheckpointAnswer, onDismissCheckpoint)
        state.dashboardStats?.let { DetailsSection(it, expanded = true, onToggle = {}) }
        state.weeklyReports.firstOrNull()?.let { report ->
            WeeklyLetterCard(report)
        }
    }
}

@Composable
private fun WeeklyLetterCard(report: WeeklyReport) {
    val json = remember(report.bodyJson) { runCatching { JSONObject(report.bodyJson) }.getOrNull() }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.MailOutline, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text("Weekly letter", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (json == null) {
            Text(
                "No summary yet — keep reviewing and check back next week.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val reviews = json.optInt("reviews", 0)
            val activeDays = json.optInt("activeDays", 0)
            val retention = if (json.isNull("retention")) null else json.optDouble("retention", Double.NaN).takeIf { !it.isNaN() }
            val attention = json.optString("attention", "").ifBlank { null }
            val recommendation = json.optString("recommendation", "").ifBlank { null }
            val topConfusion = json.optString("topConfusion", "").ifBlank { null }
                .takeUnless { json.isNull("topConfusion") }

            Text(
                "$reviews reviews across $activeDays active day${if (activeDays == 1) "" else "s"}" +
                    (retention?.let { " · ${(it * 100).toInt()}% retention" } ?: ""),
                style = MaterialTheme.typography.bodyMedium
            )
            attention?.let {
                Text("Focus area: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            recommendation?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            topConfusion?.let {
                Text("Watch for: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * P6.4: the app's only independent, unbiased assessment — writes no FSRS state
 * (see LearningRepository.recordCheckpointResult). Three states: idle (start
 * button + the historical calibration curve, if any results exist yet), in
 * progress (one item at a time, typed answer graded on submit), and the final
 * "N/M correct" summary left visible until dismissed.
 */
@Composable
private fun CheckpointCard(
    state: ReviewUiState,
    onStart: () -> Unit,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SectionCard {
        Text("Monthly checkpoint", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "An independent check with no effect on your review schedule — the only unbiased read on whether \"known\" is real.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        val session = state.checkpointSession
        val item = session?.items?.getOrNull(state.checkpointIndex)
        when {
            session == null -> {
                Button(onClick = onStart, modifier = Modifier.testTag(TestTags.CHECKPOINT_START)) { Text("Take checkpoint") }
                if (state.checkpointCalibration.isNotEmpty()) {
                    Text("Calibration (predicted vs. observed):", style = MaterialTheme.typography.labelMedium)
                    state.checkpointCalibration.forEach { bucket ->
                        Text(
                            "Predicted ${(bucket.predictedBucket * 100).toInt()}% → observed ${(bucket.observedAccuracy * 100).toInt()}% (n=${bucket.count})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item != null -> {
                var answer by rememberSaveable(state.checkpointIndex) { mutableStateOf("") }
                Text("${state.checkpointIndex + 1} / ${session.items.size}", style = MaterialTheme.typography.labelMedium)
                Text(
                    if (item.kind == "graduated_recall") "What does this mean? ${item.prompt}" else "Say it in Russian: ${item.prompt}",
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.CHECKPOINT_INPUT),
                    label = { Text("Your answer") }
                )
                Button(
                    onClick = { onSubmit(answer) },
                    modifier = Modifier.testTag(TestTags.CHECKPOINT_SUBMIT)
                ) { Text("Submit") }
            }
            else -> {
                Text(state.checkpointFeedback ?: "Checkpoint complete.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onDismiss, modifier = Modifier.testTag(TestTags.CHECKPOINT_DISMISS)) { Text("Done") }
            }
        }
        state.checkpointFeedback?.takeIf { item != null }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
