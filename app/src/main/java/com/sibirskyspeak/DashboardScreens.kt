package com.sibirskyspeak

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.KeyboardArrowDown
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Dashboard
// ---------------------------------------------------------------------------

@Composable
internal fun DashboardPanel(
    state: ReviewUiState,
    onStart: () -> Unit,
    onLoadLeeches: () -> Unit = {},
    onReleaseLeech: (LeechItem) -> Unit = {},
    onSaveLeechEdit: (LeechItem, String?, String?, String?, String?) -> Unit = { _, _, _, _, _ -> },
    onStartExitTicket: () -> Unit = {},
    onDismissExitTicketOffer: () -> Unit = {},
    onSubmitExitTicketAnswer: (String) -> Unit = {},
    onCloseExitTicket: () -> Unit = {},
    onSpeakRussian: (String) -> Unit = {},
    onGoToBackupSettings: () -> Unit = {},
    onCustomizeToday: () -> Unit = {},
    onGoToGoalSettings: () -> Unit = {},
    onDismissGoalOffTrackPrompt: () -> Unit = {},
    onAbandonLearningGoal: () -> Unit = {}
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
        // The local backup (see BackupManager) lives in app-private storage, which
        // does NOT survive an uninstall or "Clear storage" — by design, per its own
        // doc comment. BackupManager now mirrors to public Downloads/SibirskySpeak
        // via MediaStore automatically (Android 10+, no folder picker, no
        // permission prompt) — backupLastDurableAt lands the moment the first
        // post-update session completes, so this card only shows for the narrow
        // window before that first automatic mirror, or on a pre-Android-10 device
        // where MediaStore.Downloads isn't available and a manually-chosen SAF
        // folder (Settings > Data) is the only durable option.
        if (state.exitTicketSession != null) {
            ExitTicketCard(state, onSubmitExitTicketAnswer, onCloseExitTicket, onSpeakRussian)
        } else if (state.exitTicketOfferUnit != null) {
            ExitTicketOfferCard(state.exitTicketOfferUnit, state.exitTicketOfferCanDo, onStartExitTicket, onDismissExitTicketOffer)
        }
        DashboardNextActionCard(state, onStart, onCustomizeToday)
        if (state.automaticPublicBackupEnabled && state.backupLastDurableAt <= 0L && stats.noteCount > 0) {
            BackupNotConfiguredCard(onGoToBackupSettings)
        }
        StreakCard(game)
        // At large font scales two equal cards become cramped and their labels
        // wrap into unreadable fragments. Stack them on narrow windows; tablets
        // and landscape retain the compact two-column layout.
        BoxWithConstraints {
            if (maxWidth < 420.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    DailyGoalCard(Modifier.fillMaxWidth(), game)
                    WordsKnownCard(Modifier.fillMaxWidth(), game)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DailyGoalCard(Modifier.weight(1f), game)
                    WordsKnownCard(Modifier.weight(1f), game)
                }
            }
        }
        FluencyForecastCard(state.fluencyForecast, state.goalStatus)
        if (state.showGoalOffTrackPrompt) {
            GoalOffTrackDialog(
                onRaiseCommitment = onDismissGoalOffTrackPrompt,
                onPushBackDate = {
                    onDismissGoalOffTrackPrompt()
                    onGoToGoalSettings()
                },
                onDropGoal = {
                    onDismissGoalOffTrackPrompt()
                    onAbandonLearningGoal()
                },
                onDismiss = onDismissGoalOffTrackPrompt
            )
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

/**
 * The local full-state backup (BackupManager) lives in app-private storage —
 * gone forever on uninstall or "Clear storage." BackupManager now mirrors
 * automatically to public Downloads/SibirskySpeak (Android 10+, no folder
 * picker, no permission prompt); this card is only reachable in the narrow
 * window before that first automatic mirror lands, or on a pre-Android-10
 * device where a manually-chosen SAF folder is the only durable option.
 * This is an informational first-run state, not a failed backup. Keep it
 * visually subordinate to the learning action so a brand-new learner is not
 * greeted by an alarming red warning before completing a first session.
 */
@Composable
internal fun BackupNotConfiguredCard(onGoToBackupSettings: () -> Unit) {
    val automatic = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    if (automatic) stringResource(R.string.dashboard_backup_waiting_title) else stringResource(R.string.dashboard_backup_folder_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                if (automatic) stringResource(R.string.dashboard_backup_waiting_body)
                else stringResource(R.string.dashboard_backup_folder_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!automatic) {
                Button(
                    onClick = onGoToBackupSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onErrorContainer,
                        contentColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(stringResource(R.string.dashboard_backup_choose_folder))
                }
            }
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
    onClose: () -> Unit,
    onSpeakRussian: (String) -> Unit
) {
    val session = state.exitTicketSession ?: return
    val item = session.items.getOrNull(state.exitTicketIndex)
    SectionCard {
        Text("${session.band} · Unit ${session.unit} quick check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        session.canDoLabel?.let { Text("Can-do: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        when {
            item != null -> {
                var answer by rememberSaveable(state.exitTicketIndex) { mutableStateOf("") }
                Text("${state.exitTicketIndex + 1} / ${session.items.size} · ${item.kind}", style = MaterialTheme.typography.labelMedium)
                Text(
                    when (item.kind) {
                        "recognition", "reading" -> "What does this mean? ${item.prompt}"
                        "listening" -> item.prompt
                        else -> "Write the connected Russian: ${item.prompt}"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                item.audioPrompt?.let { audio ->
                    OutlinedButton(onClick = { onSpeakRussian(audio) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Listen")
                    }
                }
                if (item.choices.isNotEmpty()) {
                    item.choices.forEach { choice ->
                        OutlinedButton(onClick = { onSubmit(choice) }, modifier = Modifier.fillMaxWidth()) { Text(choice) }
                    }
                }
                if (item.choices.isEmpty()) {
                    androidx.compose.material3.OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.EXIT_TICKET_INPUT),
                        label = { Text("Your answer") }
                    )
                    Button(onClick = { onSubmit(answer) }, modifier = Modifier.testTag(TestTags.EXIT_TICKET_SUBMIT)) { Text("Submit") }
                }
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
    onCustomize: () -> Unit = {}
) {
    val prompts = state.sessionPlan?.reviewQueue.orEmpty()
    val hasGrammar = prompts.any { it.card.queue.name == "GRAMMAR" }
    val hasNew = prompts.any { it.card.state.name == "NEW" }
    val leechCount = state.dashboardStats?.leechCount ?: 0
    val estimatedMinutes = (prompts.size * 0.3f).roundToInt().coerceAtLeast(1)
    HeroCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Today's path", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                Text(
                    when {
                        prompts.isNotEmpty() -> {
                            val cards = pluralStringResource(R.plurals.dashboard_cards, prompts.size, prompts.size)
                            val minutes = pluralStringResource(R.plurals.dashboard_minutes, estimatedMinutes, estimatedMinutes)
                            "$cards · about $minutes"
                        }
                        leechCount > 0 -> stringResource(R.string.dashboard_repair_leeches)
                        prompts.isEmpty() -> stringResource(R.string.dashboard_reviews_clear)
                        else -> stringResource(R.string.dashboard_reviews_clear_manage)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                )
                if (prompts.isNotEmpty()) {
                    Text(
                        stringResource(R.string.dashboard_adaptive_reason, state.sessionPlan?.adaptiveReason ?: "your recent accuracy and memory risk"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TodayFocusChip(if (prompts.isEmpty()) "Reviews clear" else "Memory review")
            if (hasNew) TodayFocusChip("New material")
            if (hasGrammar) TodayFocusChip("Grammar")
            if (leechCount > 0) TodayFocusChip("Repair needed")
        }
        // One action, same as the Practice screen: the session already interleaves
        // reviews + new vocab + grammar and folds reading in, so there is no separate
        // Read/Practice split. When caught up, the button starts a session that opens
        // the recommended text through the same state machine (see onClick below).
        // Adding material (import/reader text) is a Settings action, not a Dashboard one —
        // when there's nothing sessionable, this card just says so above with no button.
        val startSession = prompts.isNotEmpty()
        if (startSession) {
            Spacer(Modifier.height(14.dp))
            Button(
                // A due reading must enter through the study-session state machine (same
                // as the Practice tab's CTA) so its checkpoint updates the reading
                // schedule instead of opening a manual reader outside the session.
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.DASHBOARD_NEXT_ACTION_BUTTON),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimary,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Study")
            }
        }
        TextButton(
            onClick = onCustomize,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.DASHBOARD_ADJUST_TODAY),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f))
        ) {
            Text(stringResource(R.string.dashboard_adjust_today))
        }
    }
}

@Composable
private fun TodayFocusChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.28f))
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold
        )
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
            // A small living pulse on the flame when there's an active streak —
            // static achievement icons read as inert; this one gentle animation
            // gives that "still burning" a bit of the "satisfaction" a purely
            // static badge doesn't.
            val pulse = rememberInfiniteTransition(label = "streak-pulse")
            val flameScale by pulse.animateFloat(
                initialValue = 1f,
                targetValue = if (game.currentStreak > 0) 1.12f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "streak-flame-scale"
            )
            Icon(
                Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = if (game.currentStreak > 0) Color(0xFFE0612E) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer { scaleX = flameScale; scaleY = flameScale }
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
        ActivityHeatmap(game.activityHeatmap, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(
            "${com.sibirskyspeak.data.GamificationStats.HEATMAP_WEEKS} weeks - ${game.activeDays} active days total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * A compact GitHub/Anki-style activity heatmap: one column per calendar week,
 * one row per day-of-week (Sun top, Sat bottom), color intensity by review
 * count that day. Replaces the old "last 7 days" dot row, which couldn't show
 * any history beyond the current week.
 */
@Composable
internal fun ActivityHeatmap(dailyCounts: List<Int>, modifier: Modifier = Modifier) {
    if (dailyCounts.isEmpty()) return
    // dailyCounts is oldest-first, ending with today. Left-pad with "no data"
    // sentinel cells (-1) so the first real day lands under the correct
    // day-of-week row and every column is a genuine Sun-Sat calendar week —
    // the last (current) column is naturally partial until the week is over.
    val padded = remember(dailyCounts) {
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -(dailyCounts.size - 1))
        val firstDow = calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1
        List(firstDow) { -1 } + dailyCounts
    }
    val weeks = remember(padded) { padded.chunked(7) }
    val maxCount = remember(dailyCounts) { dailyCounts.maxOrNull()?.coerceAtLeast(1) ?: 1 }
    @Composable fun levelColor(count: Int): Color = when {
        count < 0 -> Color.Transparent
        count == 0 -> MaterialTheme.colorScheme.surfaceVariant
        else -> {
            // 4 intensity buckets scaled to this learner's own busiest day, so a
            // 10-review day and a 60-review day don't collapse into the same shade.
            val ratio = count.toDouble() / maxCount
            val alpha = when {
                ratio <= 0.25 -> 0.35f
                ratio <= 0.5 -> 0.55f
                ratio <= 0.75 -> 0.75f
                else -> 1.0f
            }
            MaterialTheme.colorScheme.primary.copy(alpha = alpha)
        }
    }
    // The grid is narrower than the card (14-15 columns of small cells vs. the
    // full card width), so left-aligning it (Row's default) left it looking
    // stuck to one edge instead of sitting in the middle of the card like the
    // streak number/best-streak stats above it.
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)) {
        weeks.forEachIndexed { columnIndex, week ->
            // A left-to-right stagger per week-column (not per cell — 98 individually
            // staggered cells would just look like slow, laggy loading) reads as the
            // history "building up" on first appearance instead of the whole grid
            // just snapping into existence.
            val columnAlpha = remember(columnIndex) { Animatable(0f) }
            LaunchedEffect(columnIndex) {
                delay(columnIndex * 18L)
                columnAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.graphicsLayer { alpha = columnAlpha.value }
            ) {
                repeat(7) { row ->
                    val count = week.getOrElse(row) { -1 }
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(levelColor(count))
                    )
                }
            }
        }
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
            color = if (game.goalReached) SuccessGreen else MaterialTheme.colorScheme.primary
        ) {
            Icon(
                if (game.goalReached) Icons.Filled.CheckCircle else Icons.Filled.School,
                contentDescription = null,
                tint = if (game.goalReached) SuccessGreen else MaterialTheme.colorScheme.primary,
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
                .clip(PillShape)
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
                .clickable(role = Role.Button) { expanded = !expanded },
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
                Text("Skill shape", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Ability means with uncertainty bands.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        val hasEvidence = skillRatings.any { it.observations > 0 }
        if (hasEvidence) {
            SkillRadarChart(axes, ratings)
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Text(
                    "Complete a few guided sessions and this view will start to take shape. It is a signal, not a grade.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val statLabels = remember(ratings, axes) {
            axes.take(4).map { skill ->
                val row = ratings[skill.name]
                val label = if (row == null || row.observations == 0) {
                    "Building"
                } else {
                    String.format(Locale.US, "%.1f ± %.1f", row.mu, row.sigma)
                }
                skill.name.lowercase().replace('_', ' ') to label
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
    // Ghost matches are useful for analysis but are not part of the learner's
    // current rival record or rank progression.
    val rankedHistory = history.filterNot { it.opponent.startsWith("ghost:", ignoreCase = true) }
    // rival_state.mu/sigma is the AI opponent's own TrueSkill rating, not the
    // learner's. The learner's conservative rating is stored in MatchHistory.
    val displayRating = rankedHistory.firstOrNull()?.ratingAfter
    val wins = rankedHistory.count { it.outcome.equals("WIN", ignoreCase = true) }
    val draws = rankedHistory.count { it.outcome.equals("DRAW", ignoreCase = true) }
    val losses = rankedHistory.count { it.outcome.equals("LOSS", ignoreCase = true) }
    val winRate = if (rankedHistory.isNotEmpty()) wins.toDouble() / rankedHistory.size else null
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(PillShape)
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
                Text("Ranked season", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Climb tiers through focused study sessions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val tierName = displayRating?.let { Rival.tier(it) }
                // Keying on the tier name (not displayRating) means this only pops on
                // an actual promotion/demotion, not on every minor rating wobble
                // within the same tier — a small celebratory beat exactly when it's
                // earned, not noise on every match.
                key(tierName) {
                    val scale = remember { androidx.compose.animation.core.Animatable(0.7f) }
                    LaunchedEffect(tierName) {
                        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                    }
                    Text(
                        displayRating?.let { "${Rival.tierEmoji(it)}  ${Rival.tier(it)}" } ?: "No ranked match yet",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                    )
                }
            }
            if (rivalState != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("Win streak", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${rivalState.winStreak}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("wins", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(PillShape),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Rating ${String.format(Locale.US, "%.1f", displayRating)}" +
                    (upper?.takeIf { it.isFinite() }?.let {
                        " · ${String.format(Locale.US, "%.1f", (it - displayRating).coerceAtLeast(0.0))} to next tier"
                    } ?: " · top tier"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (rankedHistory.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Recent record", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$wins–$losses–$draws", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Win rate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${(winRate!! * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Matches", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${rankedHistory.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Recent matches", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rankedHistory.take(4).forEach { match ->
                    val won = match.outcome.equals("WIN", ignoreCase = true)
                    val drew = match.outcome.equals("DRAW", ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(PillShape)
                                .background(
                                    when {
                                        won -> SuccessGreen
                                        drew -> MaterialTheme.colorScheme.outline
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                )
                        )
                        Text(
                            when {
                                won -> "Win"
                                drew -> "Draw"
                                else -> "Loss"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                won -> SuccessGreen
                                drew -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(match.opponent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "You ${(match.perfYou * 100).roundToInt()}% · rival ${(match.perfOpp * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${String.format(Locale.US, "%.1f", match.ratingBefore)} → ${String.format(Locale.US, "%.1f", match.ratingAfter)}",
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
                // "X/Y" here used to pair a count against its *minimum-readiness
                // threshold* (readyNominalRows/minNominalRows etc.), not a total —
                // e.g. "201/100" meant 201 verified rows against a 100-row minimum
                // bar, not "201 out of 100." That reads as a broken fraction (over
                // 100%) rather than "well past the minimum," so spell out what the
                // second number actually is instead of implying it's a denominator.
                FlowRowWithStats(
                    "Noun rows" to "${report.readyNominalRows} (min ${report.minNominalRows})",
                    "Aspect verbs" to "${report.aspectReadyVerbRows} (min ${report.minVerbRows})",
                    "Aktionsart" to "${report.verifiedAktionsartVerbRows} (min ${report.minVerbRows})",
                    "Ranked" to report.domainRankedRows.toString(),
                    "Readable examples" to report.exampleRows.toString(),
                    "90% texts" to report.targetTextsAtOrAbove90.toString()
                )
            }
        }
    }
}

/**
 * Weekly off-track fork: the tutor's alternative to either silently grinding
 * harder or silently letting the goal lapse. All three choices are explicit
 * and honest — this dialog itself makes no scheduling decision.
 */
@Composable
internal fun GoalOffTrackDialog(
    onRaiseCommitment: () -> Unit,
    onPushBackDate: () -> Unit,
    onDropGoal: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your goal is off track") },
        text = {
            Text(
                "At your current pace you won't reach it by the target date. " +
                    "Raise your daily commitment, push back the date, or drop the goal — whichever fits."
            )
        },
        confirmButton = {
            TextButton(onClick = onRaiseCommitment) { Text("Raise commitment") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onPushBackDate) { Text("Push back date") }
                TextButton(onClick = onDropGoal) { Text("Drop goal") }
            }
        }
    )
}

@Composable
internal fun FluencyForecastCard(
    forecast: com.sibirskyspeak.learning.FluencySimEngine.SimResult?,
    goalStatus: com.sibirskyspeak.learning.GoalStatus? = null
) {
    if (forecast == null) return
    val anyMilestone = listOf("A1", "A2", "B1", "B2", "C1", "C2").any { forecast.days(it) != null }
    SectionCard {
        Text("Days to Fluency Forecast", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        goalStatus?.let { goal ->
            val (statusLabel, statusColor) = when (goal.state) {
                com.sibirskyspeak.learning.GoalTrackState.ON_TRACK -> "on track" to MaterialTheme.colorScheme.secondary
                com.sibirskyspeak.learning.GoalTrackState.DRIFTING -> "drifting" to MaterialTheme.colorScheme.tertiary
                com.sibirskyspeak.learning.GoalTrackState.OFF_TRACK -> "off track" to MaterialTheme.colorScheme.error
            }
            val dateLabel = runCatching {
                java.time.LocalDate.ofEpochDay(goal.targetDateEpochDay)
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy"))
            }.getOrDefault("your target date")
            Text(
                "Goal: ${goal.targetLevel} by $dateLabel — $statusLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
        }

        if (!anyMilestone) {
            // Every milestone() call below silently no-ops when its level isn't
            // reached in the simulated horizon — with nothing reached yet, that used
            // to leave this card showing just a title and no content, which read as
            // the whole feature having disappeared. Say so explicitly instead.
            Text(
                "Still building a pace estimate — keep studying and this fills in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }

        if (forecast.isEarlyEstimate) {
            Text(
                "Early estimate — based on ${forecast.evidenceDays} of 14 recommended history days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
        }
        @Composable fun milestone(level: String, label: String) {
            val days = forecast.days(level) ?: return
            val range = forecast.ranges[level]
            val estimate = if (days == 0) "reached" else "~$days days"
            val interval = range?.takeIf { days > 0 }?.let { " (${it.low}–${it.high})" }.orEmpty()
            Text("• $level ($label): $estimate$interval", style = MaterialTheme.typography.bodyMedium)
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            milestone("A1", "Survival")
            milestone("A2", "Waystage")
            milestone("B1", "Threshold")
            milestone("B2", "Vantage")
            milestone("C1", "Effective")
            milestone("C2", "Fluency")
            
            Spacer(Modifier.height(4.dp))
            Text(
                "Based on steady-state pace: ${forecast.stablePace.roundToInt()} words/day, with review load stabilizing at ~${forecast.finalReviewLoad} reviews/day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
