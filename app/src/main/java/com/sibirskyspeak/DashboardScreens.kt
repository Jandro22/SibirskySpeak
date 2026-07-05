package com.sibirskyspeak

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sibirskyspeak.data.Achievement
import com.sibirskyspeak.data.GamificationStats
import com.sibirskyspeak.data.SkillRating
import com.sibirskyspeak.review.LeechItem
import com.sibirskyspeak.review.ReviewUiState
import com.sibirskyspeak.learning.AbilitySkill
import com.sibirskyspeak.learning.Rival
import java.util.Locale

// ---------------------------------------------------------------------------
// Dashboard
// ---------------------------------------------------------------------------

@Composable
internal fun DashboardPanel(
    state: ReviewUiState,
    onStart: () -> Unit,
    onOpenReader: (Long) -> Unit,
    onRead: () -> Unit,
    onTimeBudget: (Int) -> Unit = {},
    onLoadLeeches: () -> Unit = {},
    onReleaseLeech: (LeechItem) -> Unit = {},
    onSaveLeechEdit: (LeechItem, String?, String?, String?, String?) -> Unit = { _, _, _, _, _ -> },
    onApplyDoctrineNudge: () -> Unit = {},
    onDismissDoctrineNudge: () -> Unit = {},
    onStartExitTicket: () -> Unit = {},
    onDismissExitTicketOffer: () -> Unit = {},
    onSubmitExitTicketAnswer: (String) -> Unit = {},
    onCloseExitTicket: () -> Unit = {}
) {
    val stats = state.dashboardStats
    if (stats == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    state.statusMessage ?: "Loading your learning dashboard…",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }
    val game = state.sessionPlan?.gamification ?: GamificationStats.EMPTY
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var editingLeech by remember { mutableStateOf<LeechItem?>(null) }
    LaunchedEffect(stats.leechCount) { if (stats.leechCount > 0) onLoadLeeches() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        state.sessionPlan?.doctrineNudge?.let { nudge ->
            DoctrineNudgeCard(nudge, onApplyDoctrineNudge, onDismissDoctrineNudge)
        }
        if (state.exitTicketSession != null) {
            ExitTicketCard(state, onSubmitExitTicketAnswer, onCloseExitTicket)
        } else if (state.exitTicketOfferUnit != null) {
            ExitTicketOfferCard(state.exitTicketOfferUnit, state.exitTicketOfferCanDo, onStartExitTicket, onDismissExitTicketOffer)
        }
        DashboardNextActionCard(state, onStart, onOpenReader, onRead, onTimeBudget)
        stats.goalProgress?.let { goal ->
            SectionCard {
                Text("${goal.unknownLemmaCount} words to «${goal.textTitle}»", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${goal.coveragePct}% coverage · ${if (goal.deltaThisWeek >= 0) "+" else ""}${goal.deltaThisWeek}% this week", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        StreakCard(game)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DailyGoalCard(Modifier.weight(1f), game)
            WordsKnownCard(Modifier.weight(1f), game)
        }
        if (stats.leechCount > 0) LeechCard(state.leeches, stats.leechCount, onReleaseLeech, onEdit = { editingLeech = it })
        DetailsSection(stats, showDetails) { showDetails = !showDetails }
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

@Composable
internal fun DoctrineNudgeCard(
    nudge: com.sibirskyspeak.learning.DoctrineNudge,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text("Pace check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(nudge.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApply, modifier = Modifier.testTag(TestTags.DOCTRINE_NUDGE_APPLY)) {
                Text("Switch to ${nudge.suggested.name.lowercase().replaceFirstChar(Char::uppercase)}")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.DOCTRINE_NUDGE_DISMISS)) { Text("Not now") }
        }
    }
}

/**
 * Phase G6 / P6.5: "Unit N complete — quick check?" offer, shown once a unit's
 * own vocabulary/grammar first crosses the mastery threshold (see
 * ReviewViewModel.maybeOfferExitTicket). Skipping this is always zero-friction —
 * dismissing it never blocks the learner from continuing normally.
 */
@Composable
internal fun ExitTicketOfferCard(
    unit: Int,
    canDo: String?,
    onStart: () -> Unit,
    onDismiss: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text("Unit $unit complete — quick check?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    canDo?.let { "Can you $it?" } ?: "A short mixed check over what you just learned.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, modifier = Modifier.testTag(TestTags.EXIT_TICKET_START)) { Text("Quick check") }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.EXIT_TICKET_DISMISS)) { Text("Skip") }
        }
    }
}

/**
 * The exit ticket's mixed mini proof session (Phase G6 / P6.5): one item at a
 * time, exactly like CheckpointCard, over the four facets units.json defines
 * (recognition/production/listening/reading — see LearningRepository.
 * buildExitTicketSession). Writes no FSRS state directly; ReviewViewModel feeds
 * the result to the evidence bus at PRACTICE strength on completion.
 */
@Composable
internal fun ExitTicketCard(
    state: ReviewUiState,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit
) {
    val session = state.exitTicketSession ?: return
    val item = session.items.getOrNull(state.exitTicketIndex)
    SectionCard {
        Text("Unit ${session.unit} quick check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        when {
            item != null -> {
                var answer by rememberSaveable(state.exitTicketIndex) { mutableStateOf("") }
                Text("${state.exitTicketIndex + 1} / ${session.items.size} · ${item.kind}", style = MaterialTheme.typography.labelMedium)
                Text(
                    when (item.kind) {
                        "recognition", "reading" -> "What does this mean? ${item.prompt}"
                        else -> "Say it in Russian: ${item.prompt}"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                androidx.compose.material3.OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.EXIT_TICKET_INPUT),
                    label = { Text("Your answer") }
                )
                Button(onClick = { onSubmit(answer) }, modifier = Modifier.testTag(TestTags.EXIT_TICKET_SUBMIT)) { Text("Submit") }
            }
            else -> {
                val correct = state.exitTicketResults.count { it }
                Text(
                    state.exitTicketFeedback ?: "Quick check complete: $correct/${session.items.size} correct.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(onClick = onClose, modifier = Modifier.testTag(TestTags.EXIT_TICKET_CLOSE)) { Text("Done") }
            }
        }
        state.exitTicketFeedback?.takeIf { item != null }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LeechCard(
    leeches: List<LeechItem>,
    leechCount: Int,
    onRelease: (LeechItem) -> Unit,
    onEdit: (LeechItem) -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text("Parked leeches ($leechCount)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Cards that kept tripping you up. Check the prompt, fix bad content, or retry as a fresh card.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        leeches.take(20).forEach { item ->
          key(item.card.id) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.russian, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(item.cardLabel) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                            disabledLabelColor = MaterialTheme.colorScheme.error
                        )
                    )
                    Text(
                        "${item.translation} · ${item.lapses} lapses",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Prompt: ${item.promptPreview}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Answer: ${item.expectedAnswer}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { onEdit(item) }) { Text("Fix") }
                    TextButton(onClick = { onRelease(item) }) { Text("Retry as new") }
                }
            }
          }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DashboardNextActionCard(
    state: ReviewUiState,
    onStart: () -> Unit,
    onOpenReader: (Long) -> Unit,
    onRead: () -> Unit,
    onTimeBudget: (Int) -> Unit = {}
) {
    val prompts = state.sessionPlan?.reviewQueue.orEmpty()
    val reader = state.sessionPlan?.readingAssignment?.recommendation
    val hasGrammar = prompts.any { it.card.queue.name == "GRAMMAR" }
    val hasNew = prompts.any { it.card.state.name == "NEW" }
    val leechCount = state.dashboardStats?.leechCount ?: 0
    val readingFirst = prompts.isEmpty()
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Next Best Step", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        prompts.isNotEmpty() -> "A sustainable session is ready, generated from memory risk and recent effort."
                        leechCount > 0 -> "Reviews are clear. Repair parked leeches, then read for fresh input."
                        reader != null -> "Reviews are clear. Reading keeps Russian input flowing."
                        else -> "Reviews are clear. Add reader text or import notes when you want new material."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PracticeFocusChip(if (prompts.isEmpty()) "Reviews clear" else "Memory review", null)
            if (hasNew) PracticeFocusChip("New material", null)
            if (hasGrammar) PracticeFocusChip("Grammar", null)
            if (leechCount > 0) PracticeFocusChip("Repair needed", null)
            if (reader != null) PracticeFocusChip("${(reader.coverage * 100).toInt()}% reader fit", null)
        }
        Spacer(Modifier.height(14.dp))
        Text("Time available", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 15, 45).forEach { minutes ->
                AssistChip(
                    onClick = { onTimeBudget(minutes) },
                    label = { Text("$minutes min") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (state.preferredSessionMinutes == minutes) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
        state.sessionPlan?.timeBudget?.let { budget ->
            Text("${budget.spilledCards} cards safely spill beyond this session", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        // One action, same as the Practice screen: the session already interleaves
        // reviews + new vocab + grammar and folds reading in, so there is no separate
        // Read/Practice split. When caught up, the button opens the recommended text.
        val startSession = prompts.isNotEmpty() && !readingFirst
        Button(
            onClick = { if (startSession) onStart() else reader?.let { onOpenReader(it.text.id) } ?: onRead() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(if (startSession) Icons.Filled.School else Icons.Filled.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    startSession -> "Study"
                    reader != null -> "Read"
                    else -> "Add material"
                }
            )
        }
    }
}

@Composable
internal fun LevelCard(game: GamificationStats) {
    val progress = if (game.xpForLevel == 0) 0f else game.xpIntoLevel.toFloat() / game.xpForLevel
    HeroCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ProgressRing(
                progress = progress,
                modifier = Modifier.size(96.dp),
                trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f),
                color = MaterialTheme.colorScheme.onPrimary
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LVL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                    Text("${game.level}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                    Text("${animatedInt(game.xp)} XP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                }
                Text(
                    "${game.xpIntoLevel} / ${game.xpForLevel} XP to level ${game.level + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                )
                Text(
                    "Every card you review earns XP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
internal fun StreakCard(game: GamificationStats) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(
                Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = if (game.currentStreak > 0) Color(0xFFE0612E) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp)
            )
            Column(Modifier.weight(1f)) {
                Text("${game.currentStreak}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    when (game.currentStreak) {
                        0 -> "No streak yet"
                        1 -> "day streak"
                        else -> "days streak"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Best", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${game.longestStreak}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${"🛡".repeat(game.restDayCredits)}", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // last7Days is a rolling window: index 6 is today, index 0 is six days ago.
            // Derive the real weekday letter for each cell so the labels actually match
            // the days shown (a fixed S-M-T-W-T-F-S week would mislabel every dot).
            val labels = remember(game.last7Days.size) {
                val letters = listOf("S", "M", "T", "W", "T", "F", "S")
                val cal = java.util.Calendar.getInstance()
                (6 downTo 0).map { offset ->
                    val c = cal.clone() as java.util.Calendar
                    c.add(java.util.Calendar.DAY_OF_YEAR, -offset)
                    letters[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
                }
            }
            game.last7Days.forEachIndexed { i, active ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (active) Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
                    }
                    Text(labels.getOrElse(i) { "" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Last 7 days - ${game.activeDays} active days total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun DailyGoalCard(modifier: Modifier, game: GamificationStats) {
    val progress = if (game.dailyGoal == 0) 1f else game.reviewedToday.toFloat() / game.dailyGoal
    MiniCard(modifier) {
        ProgressRing(
            progress = progress,
            modifier = Modifier.size(84.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            color = if (game.goalReached) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.primary
        ) {
            Icon(
                if (game.goalReached) Icons.Filled.CheckCircle else Icons.Filled.School,
                contentDescription = null,
                tint = if (game.goalReached) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("Practice today", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            if (game.goalReached) "Complete" else "In progress",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun WordsKnownCard(modifier: Modifier, game: GamificationStats) {
    MiniCard(modifier) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("${animatedInt(game.knownWords)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("words known", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AchievementsCard(game: GamificationStats) {
    val unlocked = game.achievements.count { it.unlocked }
    val preview = remember(game.achievements) { game.achievements.filter { it.unlocked }.takeLast(3) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text("Achievements", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$unlocked / ${game.achievements.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse achievements" else "Expand achievements",
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!expanded && preview.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preview.forEach { achievement ->
                    PracticeFocusChip(achievement.title, null)
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    game.achievements.forEach { AchievementBadge(it) }
                }
            }
        }
    }
}

@Composable
internal fun SkillRadarCard(skillRatings: List<SkillRating>) {
    val ratings = remember(skillRatings) { skillRatings.associateBy { it.skill.uppercase() } }
    val axes = remember { AbilitySkill.values().toList() }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Skill radar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Ability means with uncertainty bands.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        SkillRadarChart(axes, ratings)
        Spacer(Modifier.height(12.dp))
        val statLabels = remember(ratings, axes) {
            axes.take(4).map { skill ->
                val row = ratings[skill.name]
                val value = row?.mu ?: 0.0
                val uncertainty = row?.sigma ?: 0.0
                skill.name.lowercase().replace('_', ' ') to String.format(Locale.US, "%.1f ± %.1f", value, uncertainty)
            }.toTypedArray()
        }
        FlowRowWithStats(*statLabels)
    }
}

@Composable
private fun SkillRadarChart(axes: List<AbilitySkill>, ratings: Map<String, SkillRating>) {
    val values = remember(ratings) {
        axes.map { skill ->
            val row = ratings[skill.name]
            val mean = row?.mu ?: 0.0
            val sigma = row?.sigma ?: 0.0
            val center = (0.5 + mean / 20.0).coerceIn(0.08, 0.95)
            val lower = (center - sigma / 20.0).coerceIn(0.05, center)
            Triple(skill, center, lower)
        }
    }
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val primaryColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(300.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val radiusPx = minOf(widthPx, heightPx) * 0.30f
        val centerPx = androidx.compose.ui.geometry.Offset(widthPx / 2f, heightPx / 2f)
        val angleStep = (2 * Math.PI / values.size).toFloat()
        fun angleFor(index: Int) = -Math.PI.toFloat() / 2f + index * angleStep
        fun point(index: Int, value: Float): androidx.compose.ui.geometry.Offset {
            val angle = angleFor(index)
            return androidx.compose.ui.geometry.Offset(
                x = centerPx.x + kotlin.math.cos(angle) * radiusPx * value,
                y = centerPx.y + kotlin.math.sin(angle) * radiusPx * value
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            repeat(4) { ring ->
                val ringValue = (ring + 1) / 4f
                values.indices.forEach { index ->
                    val p1 = point(index, ringValue)
                    val p2 = point((index + 1) % values.size, ringValue)
                    drawLine(outlineColor, p1, p2, strokeWidth = 1.2f)
                }
            }
            values.indices.forEach { index ->
                val p = point(index, 1f)
                drawLine(outlineColor, centerPx, p, strokeWidth = 1.2f)
            }
            fun pathFor(valueSelector: (Triple<AbilitySkill, Double, Double>) -> Double): androidx.compose.ui.graphics.Path {
                val path = androidx.compose.ui.graphics.Path()
                values.forEachIndexed { index, triple ->
                    val p = point(index, valueSelector(triple).toFloat())
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                return path
            }
            drawPath(pathFor { it.third }, primaryColor.copy(alpha = 0.16f))
            drawPath(pathFor { it.second }, primaryColor.copy(alpha = 0.34f))
            values.forEachIndexed { index, triple ->
                val outer = point(index, triple.second.toFloat())
                drawCircle(primaryColor, radius = 6f, center = outer)
            }
        }
        val labelBoxWidth = 78.dp
        val labelBoxHeight = 32.dp
        val labelBoxWidthPx = with(density) { labelBoxWidth.toPx() }
        val labelBoxHeightPx = with(density) { labelBoxHeight.toPx() }
        values.forEachIndexed { index, triple ->
            val label = triple.first.name.lowercase().replace('_', ' ')
            val angle = angleFor(index)
            val cosA = kotlin.math.cos(angle)
            val sinA = kotlin.math.sin(angle)
            val labelRadiusPx = radiusPx * 1.28f
            val anchorXPx = centerPx.x + cosA * labelRadiusPx
            val anchorYPx = centerPx.y + sinA * labelRadiusPx
            // Anchor the box edge nearest the center at the octagon point so text radiates outward
            // instead of overlapping its neighbors when points sit close together.
            val hFraction = when {
                cosA > 0.35f -> 0f
                cosA < -0.35f -> 1f
                else -> 0.5f
            }
            val vFraction = when {
                sinA > 0.35f -> 0f
                sinA < -0.35f -> 1f
                else -> 0.5f
            }
            val offsetX = with(density) { (anchorXPx - labelBoxWidthPx * hFraction).toDp() }
            val offsetY = with(density) { (anchorYPx - labelBoxHeightPx * vFraction).toDp() }
            val textAlign = when (hFraction) {
                0f -> TextAlign.Start
                1f -> TextAlign.End
                else -> TextAlign.Center
            }
            val boxAlignment = when (vFraction) {
                0f -> Alignment.TopCenter
                1f -> Alignment.BottomCenter
                else -> Alignment.Center
            }
            Box(
                modifier = Modifier
                    .offset(x = offsetX, y = offsetY)
                    .width(labelBoxWidth)
                    .height(labelBoxHeight),
                contentAlignment = boxAlignment
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun RivalProgressCard(rivalState: com.sibirskyspeak.data.RivalState?, history: List<com.sibirskyspeak.data.MatchHistory>) {
    // rival_state.mu/sigma is the AI opponent's own TrueSkill rating (see
    // LearningRepository.finishAdaptiveSession: it's set from report.opponentAfter), not the
    // learner's — using it here would show the learner their opponent's tier. The learner's own
    // conservative rating (mu - 3*sigma) after each match is already stored as
    // MatchHistory.ratingAfter, so the most recent match has exactly the right number.
    val displayRating = history.firstOrNull()?.ratingAfter
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Rival / season", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    displayRating?.let { Rival.tier(it) } ?: "No ranked match yet",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (rivalState != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("Streak", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${rivalState.winStreak}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(rivalState.persona, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (displayRating != null) {
            Spacer(Modifier.height(14.dp))
            val tierIdx = Rival.tierIndex(displayRating)
            val lower = Rival.tierBoundaries[tierIdx]
            val upper = Rival.tierBoundaries.getOrNull(tierIdx + 1)
            val progress = when {
                upper == null || !upper.isFinite() -> 1f
                !lower.isFinite() -> 1f
                else -> ((displayRating - lower) / (upper - lower)).toFloat().coerceIn(0f, 1f)
            }
            AppLinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Rating ${"%.1f".format(displayRating)}" +
                    (upper?.takeIf { it.isFinite() }?.let { " · ${"%.1f".format((it - displayRating).coerceAtLeast(0.0))} to next tier" } ?: " · top tier"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Recent matches", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                history.take(4).forEach { match ->
                    val won = match.outcome.equals("WIN", ignoreCase = true)
                    val drew = match.outcome.equals("DRAW", ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    when {
                                        won -> Color(0xFF3DAA6B)
                                        drew -> MaterialTheme.colorScheme.outline
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                )
                        )
                        Text(
                            match.opponent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${"%.1f".format(match.ratingBefore)} → ${"%.1f".format(match.ratingAfter)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AchievementBadge(achievement: Achievement) {
    val unlocked = achievement.unlocked
    val container = if (unlocked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val content = if (unlocked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (unlocked) 0.4f else 0.2f)), MaterialTheme.shapes.medium)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            if (unlocked) Icons.Filled.EmojiEvents else Icons.Filled.Lock,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(28.dp)
        )
        Text(achievement.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = content, textAlign = TextAlign.Center, maxLines = 2)
        Text(achievement.description, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.8f), textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
internal fun MiniCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
internal fun DetailsSection(stats: com.sibirskyspeak.data.DashboardStats, expanded: Boolean, onToggle: () -> Unit) {
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Detailed stats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = if (expanded) 180f else 0f }
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(12.dp))
                FlowRowWithStats(
                    "Notes" to stats.noteCount.toString(),
                    "Vocab" to stats.vocabCards.toString(),
                    "Grammar" to stats.grammarCards.toString(),
                    "Vocab backlog" to formatCount(stats.dueVocab),
                    "Grammar backlog" to formatCount(stats.dueGrammar),
                    "Reviewed" to stats.reviewedToday.toString(),
                    "Reader avg" to "${(stats.averageReaderCoverage * 100).toInt()}%"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (stats.authenticReady) "Authentic text transition is ready." else "Authentic transition waits for a target-source sample at 90%+ coverage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stats.authenticReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text("Retention", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                FlowRowWithStats(
                    "True retention" to (stats.matureRetention?.let { "${(it * 100).toInt()}%" } ?: "—"),
                    "Mature reviews" to stats.matureReviewSample.toString(),
                    "Leeches" to stats.leechCount.toString(),
                    "Interval tuning" to String.format(Locale.US, "%.2f×", stats.intervalModifier)
                )
                if (stats.matureReviewSample in 1 until 30) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Retention firms up after ~30 mature reviews.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (stats.dueForecast.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Coming due (next 7 days)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stats.dueForecast.joinToString(" · ") { it.toString() },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(16.dp))
                val report = stats.importQualityReport
                Text("Import readiness", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                FlowRowWithStats(
                    "Noun rows" to "${report.readyNominalRows}/${report.minNominalRows}",
                    "Aspect verbs" to "${report.aspectReadyVerbRows}/${report.minVerbRows}",
                    "Aktionsart" to "${report.verifiedAktionsartVerbRows}/${report.minVerbRows}",
                    "Ranked" to report.domainRankedRows.toString(),
                    "Readable examples" to report.exampleRows.toString(),
                    "90% texts" to report.targetTextsAtOrAbove90.toString()
                )
            }
        }
    }
}
