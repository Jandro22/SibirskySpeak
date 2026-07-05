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
    onDismissCheckpoint: () -> Unit = {},
    onDismissMigrationReport: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Learning Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Diagnostics and experiments; FSRS remains the scheduler.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.curriculumMigrationReport?.let { report ->
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Content updated", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${report.appeared} new, ${report.retired} retired since your last update.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = onDismissMigrationReport,
                            modifier = Modifier.testTag(TestTags.DISMISS_MIGRATION_REPORT)
                        ) { Text("Got it") }
                    }
                }
            }
        }
        SkillRadarCard(state.skillRatings)
        ProficiencyMapCard(state.skillRatings)
        state.sessionPlan?.levelConstraint?.let { constraint ->
            SectionCard { Text(constraint, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold) }
        }
        CurriculumCompletenessCard(state.curriculumCompleteness)
        RivalProgressCard(state.rivalState, state.matchHistory)
        CheckpointCard(state, onStartCheckpoint, onSubmitCheckpointAnswer, onDismissCheckpoint)
        state.dashboardStats?.let { DetailsSection(it, expanded = true, onToggle = {}) }
        state.weeklyReports.firstOrNull()?.let { report ->
            WeeklyLetterCard(report)
        }
    }
}

/** Phase G11: how much of the Tatoeba corpus is fully parseable with the
 * currently-shipped grammar+vocab spine, by CEFR band — the honest number
 * behind "40 concepts is too few" turned into a dashboard readout. */
@Composable
private fun CurriculumCompletenessCard(bands: Map<String, com.sibirskyspeak.data.CurriculumCompletenessBand>) {
    if (bands.isEmpty()) return
    SectionCard {
        Text("Curriculum completeness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "% of on-device example sentences fully parseable with what's shipped so far",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val order = listOf("A1", "A2", "B1", "B2+")
        order.forEach { band ->
            bands[band]?.let { metrics ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(band, fontWeight = FontWeight.SemiBold)
                    Text("${metrics.percent}% (${metrics.parseableSentences}/${metrics.corpusSentences})")
                }
            }
        }
    }
}

@Composable
private fun ProficiencyMapCard(ratings: List<com.sibirskyspeak.data.SkillRating>) {
    val bands = listOf("A1", "A2", "B1", "B2", "C1", "C2")
    fun ordinal(mu: Double) = when {
        mu < -5 -> 0; mu < 0 -> 1; mu < 5 -> 2; mu < 10 -> 3; mu < 15 -> 4; else -> 5
    }
    val core = listOf("reading", "listening", "production")
    SectionCard {
        Text("Proficiency map", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Checkpoint-calibrated skill evidence by CEFR band", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        core.forEach { skill ->
            val rating = ratings.firstOrNull { it.skill == skill }
            val reached = rating?.takeIf { it.observations > 0 }?.let { ordinal(it.mu) }
            Text("${skill.replaceFirstChar(Char::uppercase)}  " + bands.mapIndexed { index, band ->
                when { reached == null -> "·"; index <= reached -> "●$band"; else -> "○$band" }
            }.joinToString("  "), style = MaterialTheme.typography.bodySmall)
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
