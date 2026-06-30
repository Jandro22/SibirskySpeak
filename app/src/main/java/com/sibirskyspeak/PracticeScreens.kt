package com.sibirskyspeak

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sibirskyspeak.data.GamificationStats
import com.sibirskyspeak.data.ReaderStatus
import com.sibirskyspeak.review.ReviewUiState
import com.sibirskyspeak.learning.SessionMode

// ---------------------------------------------------------------------------
// Practice home
// ---------------------------------------------------------------------------

@Composable
internal fun PracticeScreen(
    state: ReviewUiState,
    onStart: (SessionMode) -> Unit,
    onRead: () -> Unit,
    onOpenReader: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DailyPlanPanel(state, onStart, onRead)
        PracticeFocusPanel(state)
        UnitMasteryPanel(state)
        ProblemCardAuditPanel(state)
        ReadingSuggestion(state, onOpenReader)
    }
}

@Composable
internal fun ProblemCardAuditPanel(state: ReviewUiState) {
    val problems = state.sessionPlan?.problemCards.orEmpty()
    if (problems.isEmpty()) return
    SectionCard {
        Text("Cards needing repair", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Generated from actual lapses and FSRS difficulty—not from a fixed word list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        problems.take(4).forEach { item ->
            Text("${item.russian} · ${item.lapses} lapses", fontWeight = FontWeight.SemiBold)
            Text(
                "${item.conciseMeaning} — ${item.recommendation}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun UnitMasteryPanel(state: ReviewUiState) {
    val units = state.sessionPlan?.unitMastery.orEmpty()
    if (units.isEmpty()) return
    val activeIndex = units.indexOfFirst { it.unlocked && it.progress < 0.80 }.let { if (it < 0) units.lastIndex else it }
    val visible = units.drop((activeIndex - 1).coerceAtLeast(0)).take(3)
    SectionCard {
        // This is the master curriculum progression (hand-authored spine + the
        // frequency-promoted band) that drives FSRS sequencing, not the literal
        // textbook-PDF content (that's a small supplementary vocab/reading layer).
        Text("Curriculum mastery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "A unit unlocks after 80% mastery; grammar requires repeated correct recall.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        visible.forEach { unit ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Unit ${unit.unit}${if (!unit.unlocked) " · locked" else ""}", fontWeight = FontWeight.SemiBold)
                Text("${(unit.progress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(
                progress = { unit.progress.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(7.dp).clip(RoundedCornerShape(99.dp))
            )
            Text(
                "Vocabulary ${unit.vocabularyMastered}/${unit.vocabularyTotal} · Grammar ${unit.grammarMastered}/${unit.grammarTotal}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DailyPlanPanel(state: ReviewUiState, onStart: (SessionMode) -> Unit, onRead: () -> Unit) {
    val plan = state.dailyPlan ?: return
    val prompts = state.sessionPlan?.reviewQueue.orEmpty()
    val sessionSize = prompts.size
    val backlog = plan.dueVocab + plan.dueGrammar
    val reader = state.sessionPlan?.readingAssignment?.recommendation
    val focus = plan.grammarFocus.firstOrNull()?.label?.takeIf { it.isNotBlank() }

    HeroCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Study", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                Text(
                    when {
                        (plan.triageMode || plan.overdueBacklog) && sessionSize > 0 -> "Older material comes first today; new material is paused."
                        sessionSize > 0 -> "Your session is generated from memory, energy, and recent accuracy."
                        reader != null -> "All caught up. Read the recommended text for fresh exposure."
                        else -> "You're caught up. Add a reader text or import notes for new material."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                )
            }
        }
        state.sessionPlan?.unitMastery?.firstOrNull { it.unlocked && it.progress < 0.80 }?.let { unit ->
            Spacer(Modifier.height(12.dp))
            Text(
                "Curriculum Unit ${unit.unit}: ${(unit.progress * 100).toInt()}% mastered · " +
                    "vocabulary ${unit.vocabularyMastered}/${unit.vocabularyTotal} · " +
                    "grammar ${unit.grammarMastered}/${unit.grammarTotal}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f)
            )
        }
        if (focus != null || plan.triageMode) {
            Spacer(Modifier.height(12.dp))
            Text(
                listOfNotNull(
                    focus?.let { "Focus: $it" },
                    if (plan.triageMode) "Triage mode is prioritizing older due cards." else null
                ).joinToString("  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f)
            )
        }
        Spacer(Modifier.height(18.dp))
        // ONE route, ONE button (see docs/DESIGN_VISION.md). The learner never chooses a
        // pace/mode — the system generates the optimal session. Quick/Full/Stretch are an
        // INTERNAL decision of the pace engine, never user-facing buttons. (The mode is
        // chosen for the session when it starts; until the generative PaceController lands
        // it defaults to FULL, which BlueprintBuilder already adapts by at-risk + accuracy.)
        Button(
            onClick = {
                // A due reading must enter through the study-session state machine so
                // its checkpoint updates the reading schedule. Opening it as a manual
                // reader leaves the assignment due forever.
                if (sessionSize > 0 || reader != null) onStart(SessionMode.FULL) else onRead()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onPrimary,
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(if (sessionSize > 0) Icons.Filled.School else Icons.Filled.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    sessionSize > 0 -> "Study"
                    reader != null -> "Read"
                    else -> "Add material"
                },
                fontWeight = FontWeight.SemiBold
            )
        }
        if (backlog > sessionSize && sessionSize > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (plan.triageMode)
                    "There is an older backlog. This session stays bounded and pulls the most urgent material first."
                else
                    "There is more due work than one sustainable session; the remainder will wait safely.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PracticeFocusPanel(state: ReviewUiState) {
    val plan = state.dailyPlan ?: return
    val prompts = state.sessionPlan?.reviewQueue.orEmpty()
    val game = state.sessionPlan?.gamification ?: GamificationStats.EMPTY
    val hasVocab = prompts.any { it.card.queue.name == "VOCAB" }
    val hasGrammar = prompts.any { it.card.queue.name == "GRAMMAR" }
    val hasNew = prompts.any { it.card.state.name == "NEW" }
    val hasReview = prompts.any { it.card.state.name != "NEW" }
    val ruleSummary = state.sessionPlan?.ruleSummary

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Today's Focus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (prompts.isEmpty()) "Nothing scheduled right now." else "The generated session balances these kinds of practice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (game.currentStreak > 0) {
                StatusTag("${game.currentStreak} day streak")
            }
            if (game.inputStreak > 0) {
                StatusTag("${game.inputStreak} day input streak")
            }
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hasReview) PracticeFocusChip("Memory review", null)
            if (hasNew) PracticeFocusChip("New material", null)
            if (hasVocab) PracticeFocusChip("Vocabulary", null)
            if (hasGrammar) PracticeFocusChip("Grammar", null)
        }
        // Grammar is woven into the one session automatically (interleaved with vocab
        // and reviews), so this is read-only context, not a set of separate actions:
        // it just flags weak recent patterns to slow down on, plus triage state.
        if (plan.grammarFocus.isNotEmpty() || plan.triageMode) {
            Spacer(Modifier.height(16.dp))
            Text("Focus", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (plan.grammarFocus.isNotEmpty()) {
                Text(
                    "Weak recent grammar patterns show up first in your session; slow down on these and read the reveal explanation before rating.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!ruleSummary.isNullOrBlank()) {
                Text(
                    ruleSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plan.grammarFocus.take(3).forEach { focus ->
                    if (focus.label.isNotBlank()) PracticeFocusChip(focus.label, focus.accuracy)
                }
                if (plan.triageMode) PracticeFocusChip("Older due cards first", null)
            }
        }
    }
}

@Composable
internal fun PracticeFocusChip(label: String, accuracy: Double?) {
    val suffix = accuracy?.let { " ${(it * 100).toInt()}%" }.orEmpty()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
    ) {
        Text(
            "$label$suffix",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun ReadingSuggestion(state: ReviewUiState, onOpenReader: (Long) -> Unit) {
    val reader = state.sessionPlan?.readingAssignment?.recommendation ?: return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CoverageRing(reader.coverage, Modifier.size(56.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Up Next: Reading", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    ReaderStatusChip(reader.status)
                }
                Text(reader.text.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatCount(reader.knownTokens)} of ${formatCount(reader.totalTokens)} tokens match material you've already seen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.sessionPlan.readingReason?.let { reason ->
                    Text(reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { reader.coverage.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp)),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = { onOpenReader(reader.text.id) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open Reader")
        }
    }
}

@Composable
internal fun ReaderStatusChip(status: ReaderStatus) {
    val (label, color) = when (status) {
        ReaderStatus.TOO_HARD -> "Hard" to MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
        ReaderStatus.PRODUCTIVE -> "Good fit" to MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        ReaderStatus.EASY -> "Easy" to MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
    }
    Surface(shape = MaterialTheme.shapes.small, color = color) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

