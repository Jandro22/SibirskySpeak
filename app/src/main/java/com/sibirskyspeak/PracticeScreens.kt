package com.sibirskyspeak

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sibirskyspeak.data.DailyPlan
import com.sibirskyspeak.data.GamificationStats
import com.sibirskyspeak.data.ReaderStatus
import com.sibirskyspeak.data.SessionPlan
import com.sibirskyspeak.review.ReviewUiState
import androidx.compose.runtime.key

// ---------------------------------------------------------------------------
// Practice home
// ---------------------------------------------------------------------------

@Composable
internal fun PracticeScreen(
    state: ReviewUiState,
    onStart: () -> Unit,
    onStartMicro: () -> Unit = {}
) {
    // Narrowed to the two fields these panels actually use, instead of passing the
    // whole ReviewUiState down five times: state also carries the active review
    // prompt, reader selection, typed-answer, and status-message fields, all of
    // which churn during a session and none of which these dashboard panels read —
    // passing the full object made every panel recompose on those unrelated changes.
    val dailyPlan = state.dailyPlan
    val sessionPlan = state.sessionPlan
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DailyPlanPanel(dailyPlan, sessionPlan, onStart, onStartMicro)
        PracticeFocusPanel(dailyPlan, sessionPlan)
        UnitMasteryPanel(sessionPlan)
        ProblemCardAuditPanel(sessionPlan)
        ReadingSuggestion(sessionPlan, onStart)
    }
}

@Composable
internal fun ProblemCardAuditPanel(sessionPlan: SessionPlan?) {
    val problems = sessionPlan?.problemCards.orEmpty()
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
internal fun UnitMasteryPanel(sessionPlan: SessionPlan?) {
    val units = sessionPlan?.unitMastery.orEmpty()
    if (units.isEmpty()) return
    val activeIndex = units.indexOfFirst { it.unlocked && it.progress < 0.80 }.let { if (it < 0) units.lastIndex else it }
    val active = units.getOrNull(activeIndex) ?: return
    val visible = listOf(active)
    // The focused milestone (above) intentionally hides the other ~100+
    // units so the card stays scannable, but that left no way to see the rest
    // at all — "no roadmap." Toggle into a compact, scrollable full list instead
    // of trying to cram every unit into the always-visible view.
    var showRoadmap by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    SectionCard {
        // This is the master curriculum progression (hand-authored spine + the
        // frequency-promoted band) that drives FSRS sequencing, not the literal
        // textbook-PDF content (that's a small supplementary vocab/reading layer).
        Text("Next milestone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "The tutor is guiding you toward this next useful ability.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        visible.forEach { unit ->
          // The visible milestone changes as mastery advances, so the stable key
          // keeps Compose tied to the curriculum identity rather than row position.
          key(unit.unit) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${unit.band} · Unit ${curriculumUnitNumber(unit, units)}${if (!unit.unlocked) " · locked" else ""}", fontWeight = FontWeight.SemiBold)
                    Text("${(unit.progress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary)
                }
                AppLinearProgressIndicator(
                    progress = { unit.progress.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(7.dp).clip(PillShape)
                )
                Text(
                    "Vocabulary ${unit.vocabularyMastered}/${unit.vocabularyTotal} · Grammar ${unit.grammarMastered}/${unit.grammarTotal}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                unit.canDoLabel?.let {
                    Text(
                        it.replaceFirstChar { first -> first.titlecase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
          }
        }
        OutlinedButton(onClick = { showRoadmap = !showRoadmap }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showRoadmap) "Hide roadmap" else "View roadmap · ${units.size} milestones")
        }
        AnimatedVisibility(visible = showRoadmap) {
            Column {
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(units, key = { it.stableKey }) { unit ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Unit numbers restart at 1 within every CEFR band (that's
                            // exactly why UnitMastery.stableKey exists), so "Unit 1"
                            // alone is ambiguous once every band is on screen at once.
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${unit.band} · Unit ${curriculumUnitNumber(unit, units)}${if (!unit.unlocked) " · locked" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (unit.unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                unit.canDoLabel?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (unit.unlocked) 0.9f else 0.55f)
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppLinearProgressIndicator(
                                    progress = { unit.progress.toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.width(70.dp).height(5.dp).clip(PillShape)
                                )
                                Text(
                                    "${(unit.progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(34.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Converts sparse storage ids into stable, learner-facing sequence numbers
 * without changing the underlying curriculum ids used by the scheduler. */
internal fun curriculumUnitNumber(
    unit: com.sibirskyspeak.data.UnitMastery,
    units: List<com.sibirskyspeak.data.UnitMastery>
): Int = units
    .asSequence()
    .filter { it.band == unit.band }
    .sortedBy { it.unit }
    .indexOfFirst { it.stableKey == unit.stableKey }
    .takeIf { it >= 0 }
    ?.plus(1)
    ?: unit.unit + 1

private fun curriculumUnitLabel(
    unit: com.sibirskyspeak.data.UnitMastery,
    units: List<com.sibirskyspeak.data.UnitMastery>
): String = "${unit.band} · Unit ${curriculumUnitNumber(unit, units)}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DailyPlanPanel(
    dailyPlan: DailyPlan?,
    sessionPlan: SessionPlan?,
    onStart: () -> Unit,
    onStartMicro: () -> Unit = {}
) {
    val plan = dailyPlan ?: return
    val prompts = sessionPlan?.reviewQueue.orEmpty()
    val sessionSize = prompts.size
    val backlog = plan.dueVocab + plan.dueGrammar
    val reader = sessionPlan?.readingAssignment?.recommendation
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
                        else -> "You're caught up for now. Manage imported material from Settings."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                )
            }
        }
        sessionPlan?.unitMastery?.firstOrNull { it.unlocked && it.progress < 0.80 }?.let { unit ->
            Spacer(Modifier.height(12.dp))
            Text(
                "${curriculumUnitLabel(unit, sessionPlan.unitMastery)}: ${(unit.progress * 100).toInt()}% mastered · " +
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
        // ONE adaptive route plus an explicitly bounded three-card fallback for
        // low-energy days (see docs/DESIGN_VISION.md). The learner never chooses a
        // pace/mode — the system generates the optimal session. Quick/Full/Stretch are an
        // INTERNAL decision of the pace engine, never user-facing buttons: onStart routes
        // to ReviewViewModel.startRecommendedSession(), which reads sessionPlan.blueprint.mode
        // (PaceController already set it — EARLY_STOP -> QUICK, STRETCH_ARMED -> STRETCH —
        // when the plan was generated) instead of this composable re-deriving it.
        // Adding material (import/reader text) is a Settings action, not a Study one — when
        // there's nothing sessionable, this card just says so above with no button.
        if (sessionSize > 0 || reader != null) {
            Spacer(Modifier.height(18.dp))
            Button(
                // A due reading must enter through the study-session state machine so
                // its checkpoint updates the reading schedule. Opening it as a manual
                // reader leaves the assignment due forever.
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.DASHBOARD_STUDY_BUTTON),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimary,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(if (sessionSize > 0) Icons.Filled.School else Icons.Filled.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (sessionSize > 0) "Study" else "Read", fontWeight = FontWeight.SemiBold)
            }
            if (sessionSize > 0) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onStartMicro,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.PRACTICE_SHORT_SESSION)
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.practice_short_session))
                }
            }
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
internal fun PracticeFocusPanel(dailyPlan: DailyPlan?, sessionPlan: SessionPlan?) {
    val plan = dailyPlan ?: return
    val prompts = sessionPlan?.reviewQueue.orEmpty()
    val game = sessionPlan?.gamification ?: GamificationStats.EMPTY
    val hasVocab = prompts.any { it.card.queue.name == "VOCAB" }
    val hasGrammar = prompts.any { it.card.queue.name == "GRAMMAR" }
    val hasNew = prompts.any { it.card.state.name == "NEW" }
    val hasReview = prompts.any { it.card.state.name != "NEW" }
    val ruleSummary = sessionPlan?.ruleSummary

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
internal fun ReadingSuggestion(sessionPlan: SessionPlan?, onStart: () -> Unit) {
    val reader = sessionPlan?.readingAssignment?.recommendation ?: return
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
                Text(
                    stringResource(R.string.reader_difficulty, reader.difficultyLabel.replaceFirstChar(Char::uppercase)) +
                        " · " + stringResource(
                            R.string.reader_difficulty_detail,
                            (reader.syntaxComplexity * 100).toInt(),
                            (reader.morphologyNovelty * 100).toInt(),
                            (reader.idiomDensity * 100).toInt()
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                sessionPlan.readingReason?.let { reason ->
                    Text(reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        AppLinearProgressIndicator(
            progress = { reader.coverage.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(PillShape),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Start guided reading")
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
