package com.sibirskyspeak

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
        Text("Learning insights", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("A calm readout of what your guided practice is building.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        InsightSummaryCard(state)
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
        RivalProgressCard(state.rivalState, state.matchHistory)
        CheckpointCard(state, onStartCheckpoint, onSubmitCheckpointAnswer, onDismissCheckpoint)
        // Was hardcoded to expanded = true with onToggle = {} — the collapse arrow
        // rendered and rotated but tapping it did nothing, since there was no state
        // for it to flip. This is the only DetailsSection call site in the app not
        // wired to real state (compare DashboardScreens.kt's showDetails toggle).
        var detailsExpanded by rememberSaveable { mutableStateOf(false) }
        state.dashboardStats?.let { DetailsSection(it, detailsExpanded) { detailsExpanded = !detailsExpanded } }
        state.weeklyReports.firstOrNull()?.let { report ->
            WeeklyLetterCard(report)
        }
    }
}

@Composable
private fun InsightSummaryCard(state: ReviewUiState) {
    val observed = state.skillRatings.filter { it.observations > 0 }
    val strongest = observed.maxByOrNull { it.mu }
    val nextFocus = observed.minByOrNull { it.mu }
    val grammarFocus = state.dailyPlan?.grammarFocus?.firstOrNull()?.label?.takeIf { it.isNotBlank() }
    val message = when {
        observed.isEmpty() -> "Keep following the guided sessions. After a few completed reviews, this page will explain what is getting stronger and what the tutor is bringing back."
        strongest == null -> "Your guided practice is building a useful base. Keep going and the tutor will sharpen the picture."
        nextFocus == null || strongest.skill == nextFocus.skill -> "Your practice is moving together as one system. More completed sessions will reveal the next useful edge."
        else -> "Your strongest signal is ${insightSkillLabel(strongest.skill)}. The next area the tutor is watching is ${insightSkillLabel(nextFocus.skill)}."
    }
    HeroCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Your learning path", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f))
                grammarFocus?.let {
                    Text("Current guided focus: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f))
                }
            }
        }
    }
}

private fun insightSkillLabel(raw: String): String = when (raw.lowercase()) {
    "reading" -> "reading and lessons"
    "listening" -> "listening"
    "production" -> "producing Russian"
    "vocab", "vocabulary" -> "vocabulary"
    else -> raw.lowercase().replace('_', ' ')
}

/** One band's mastery dot in [ProficiencyMapCard]: filled + labeled when reached,
 * a muted outline when not — replaces the previous plain "●A1 ○B1" text glyphs,
 * which read as a rendering artifact rather than a deliberate visual on most
 * fonts/devices. */
@Composable
private fun ProficiencyBandDot(band: String, reached: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(26.dp),
        shape = CircleShape,
        color = if (reached) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = if (reached) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                band,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (reached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ProficiencyMapCard(ratings: List<com.sibirskyspeak.data.SkillRating>) {
    val bands = listOf("A1", "A2", "B1", "B2", "C1", "C2")
    fun ordinal(mu: Double) = when {
        mu < -5 -> 0; mu < 0 -> 1; mu < 5 -> 2; mu < 10 -> 3; mu < 15 -> 4; else -> 5
    }
    // "reading" evidence comes from WorldModel.skillWeights() on every graded
    // review, not just the Reader feature — a LESSON card (any grammar/vocab
    // intro) already contributes 50% weight to it, since reading the lesson body
    // is technically reading Russian text. That's real signal, but the plain
    // "Reading" label reads as "text comprehension from the Reader," which is a
    // distinct feature the learner may not have touched at all — hence a
    // reported "A2 in reading" despite doing no reading exercises "in proper."
    // Label it honestly instead of quietly conflating the two.
    val core = listOf("reading" to "Reading (incl. lessons)", "listening" to "Listening", "production" to "Production")
    SectionCard {
        Text("Proficiency map", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Skill evidence from graded reviews and checkpoints, by CEFR band",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            core.forEach { (skill, label) ->
                val rating = ratings.firstOrNull { it.skill == skill }
                val reached = rating?.takeIf { it.observations > 0 }?.let { ordinal(it.mu) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    if (reached == null) {
                        Text(
                            "No checkpoint evidence yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            bands.forEachIndexed { index, band ->
                                ProficiencyBandDot(band = band, reached = index <= reached)
                            }
                        }
                    }
                }
            }
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
