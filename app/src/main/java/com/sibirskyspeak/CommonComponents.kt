package com.sibirskyspeak

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReaderRecommendation
import com.sibirskyspeak.data.GamificationStats
import com.sibirskyspeak.data.ReaderStatus
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.review.SessionStep
import com.sibirskyspeak.review.meaningLine
import com.sibirskyspeak.learning.MatchOutcome
import com.sibirskyspeak.learning.MatchReport
import com.sibirskyspeak.learning.CardPedagogy
import com.sibirskyspeak.learning.LearningFacet

// ---------------------------------------------------------------------------

@Composable
internal fun SessionCompleteCard(
    game: GamificationStats,
    onDone: () -> Unit,
    reader: ReaderRecommendation? = null,
    sessionReviewed: Int = 0,
    sessionCorrect: Int = 0,
    matchReport: MatchReport? = null,
    saving: Boolean = false,
    tomorrowReviews: Int = 0,
    tomorrowMinutes: Int = 0,
    tomorrowNewCards: Int = 0,
    onReadNext: () -> Unit = {}
) {
    val sessionAccuracy = if (sessionReviewed > 0) sessionCorrect.toDouble() / sessionReviewed else null
    val lowAccuracy = sessionAccuracy != null && sessionAccuracy < 0.8
    val protectPacing = game.goalReached || lowAccuracy
    val prioritizeReading = reader != null && protectPacing
    val pop by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "session-complete"
    )
    HeroCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = pop; scaleY = pop },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(52.dp))
            Text(
                if (game.goalReached) "Daily goal complete" else "Scheduled work complete",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                // tomorrowReviews only counts cards already scheduled to come due —
                // it deliberately excludes NEW cards, which don't get a due date
                // until their first review. Without the "+ up to N new" half this
                // read as "tomorrow's total session," which for an account doing a
                // lot of first-time learning (or a lot of "already know this")
                // dramatically undercounts what will actually show up.
                if (tomorrowNewCards > 0) {
                    "Tomorrow: $tomorrowReviews due + up to $tomorrowNewCards new, ~$tomorrowMinutes min"
                } else {
                    "Tomorrow: $tomorrowReviews reviews due, ~$tomorrowMinutes min"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Daily target: ${game.learningActionsToday} / ${game.dailyGoal} learning actions (${if (game.dailyGoal > 0) (game.learningActionsToday * 100 / game.dailyGoal).coerceAtMost(100) else 100}%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f)
            )
            Text(
                if (sessionReviewed > 0)
                    "This sitting: ${(sessionCorrect * 100) / sessionReviewed}% accurate."
                else
                    "Scheduled practice is complete.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sessionReviewed > 0) HeroPill("${(sessionCorrect * 100) / sessionReviewed}%", "this sitting")
                HeroPill("${game.currentStreak}", "day streak")
                HeroPill("Lvl ${game.level}", "level")
            }
            matchReport?.let { report ->
                // "Rival wins" read as a flat defeat headline right next to "Protected
                // stop" and a streak/level celebration — tonally it looked like the app
                // was praising and scolding the same session in the same breath. Match
                // the softer, competitive framing already used for WIN/DRAW, and when
                // the rating dip is a direct consequence of the (correct) protected
                // stop, say so instead of leaving it unexplained.
                val result = when (report.outcome) {
                    MatchOutcome.WIN -> "Rival defeated"
                    MatchOutcome.DRAW -> "Rival draw"
                    MatchOutcome.LOSS -> "Rival ahead"
                }
                Text(
                    "$result · rating ${"%.1f".format(report.before.conservativeRating)} → ${"%.1f".format(report.after.conservativeRating)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                report.ghostOutcome?.let { ghost ->
                    Text(
                        "21-day Ghost: ${ghost.name.lowercase().replaceFirstChar { it.uppercase() }} · ${report.tier}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f)
                    )
                }
                report.promotionProgress?.let { progress ->
                    Text(progress, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (prioritizeReading) {
                Text(
                    "Reviews are done. Reading now reinforces today's words without adding more SRS load.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f),
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onReadNext,
                    enabled = !saving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimary,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Filled.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Read Next", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = onDone,
                    enabled = !saving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimary,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (saving) "Saving…" else "Back to Practice", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (protectPacing) {
                if (prioritizeReading) {
                    TextButton(onClick = onDone, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) {
                        Text("Back to Practice")
                    }
                }
                Text(
                    if (lowAccuracy && reader != null) {
                        "Stop here or read next; let the difficult retrievals settle."
                    } else if (lowAccuracy) {
                        "Stop here and let the difficult retrievals settle."
                    } else if (reader != null) {
                        "Reading next reinforces today's work without adding review debt."
                    } else {
                        "A clean finish protects tomorrow's return."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
internal fun EmptyQueue(step: SessionStep) {
    SectionCard {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Text("${step.label()} queue is clear", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Cards appear here when due or after import.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
internal fun HeroCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f))
    ) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                    )
                )
            )
        ) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            // Emphasis cards carry primary learning content (including reader
            // definitions); transparency lets the busy content behind them bleed
            // through and materially lowers text contrast.
            containerColor = if (emphasis) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (emphasis) 3.dp else 1.dp)
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
internal fun StatusBanner(message: String, onDismiss: (() -> Unit)? = null) {
    val dismissAfter = statusMessageAutoDismissMillis(message)
    LaunchedEffect(message, dismissAfter) {
        if (dismissAfter != null && onDismiss != null) {
            delay(dismissAfter)
            onDismiss()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        if (onDismiss != null) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss message", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Success and informational banners clear themselves; errors wait for the user. */
internal fun statusMessageAutoDismissMillis(message: String): Long? {
    val normalized = message.lowercase()
    if (listOf(
            "couldn't", "could not", "failed", "error", "is empty", "exception",
            "permission", "denied", "not available", "unknown error"
        ).any(normalized::contains)
    ) return null
    return when {
        normalized.startsWith("card updated") || normalized.startsWith("updated ") -> 2_500L
        normalized.startsWith("parked ") || normalized.startsWith("pace protected") -> 7_000L
        else -> 4_000L
    }
}

/**
 * Every determinate progress bar in the app should go through here, not through
 * [LinearProgressIndicator] directly. The default M3 "stop indicator" — a dot drawn
 * at the very end of the track — reads as a rendering glitch in a mastery/coverage
 * bar rather than a deliberate range hint, so this bakes the suppression in once
 * instead of relying on every call site to remember it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppLinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor
) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        drawStopIndicator = {}
    )
}

@Composable
internal fun StatusTag(label: String) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Tap-to-build answer input (constructed response / "word bank"). Replaces free
 * typing: research on retrieval practice shows constructed responses retain
 * better than passive recognition, while tile assembly removes keyboard friction
 * (the approach Duolingo uses). Short answers use letters, long single words use
 * word parts, and phrases use whole words. The assembled string feeds the existing
 * answer evaluation untouched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LetterTileBank(
    expected: String,
    cardId: Long,
    hint: String,
    onChange: (String) -> Unit,
    resetKey: Any? = null,
    enabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current
    // Strip the combining stress mark so it never becomes its own phantom tile;
    // answers are scored stress-insensitively anyway.
    val answer = remember(cardId, expected, resetKey) { tileAnswerText(expected) }
    val wordMode = answer.contains(' ')
    val partMode = !wordMode && answer.length >= 7
    val cyrillic = answer.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
    val tiles = remember(cardId, expected, resetKey) {
        if (wordMode) {
            val words = sentenceTileWords(answer)
            val decoyPool = if (cyrillic) listOf("и", "в", "не", "на", "с", "по") else listOf("the", "a", "to", "of", "is", "in")
            (words + decoyPool.filter { it !in words }.shuffled().take(2)).shuffled()
        } else if (partMode) {
            val normalized = answer.lowercase().filter { !it.isWhitespace() }
            val endings = if (cyrillic) {
                listOf("ыми", "ими", "ого", "ему", "ому", "ая", "яя", "ое", "ее", "ые", "ие", "ый", "ий", "ой", "ую", "юю")
            } else emptyList()
            val ending = endings.firstOrNull { normalized.endsWith(it) && normalized.length > it.length + 1 }
            if (ending != null) {
                val stem = normalized.dropLast(ending.length)
                val decoys = listOf("ый", "ая", "ое", "ые", "ий", "яя", "ее", "ие")
                    .filter { it != ending }
                    .shuffled()
                    .take(3)
                (listOf(stem, ending) + decoys).shuffled()
            } else {
                // No recognized inflectional ending (a noun/verb, an already-inflected
                // form, or a non-Cyrillic answer) — chunk arbitrarily, but still add a
                // couple of decoy chunks so the exercise isn't a trivial single-order
                // reassembly of exactly the right pieces.
                val realChunks = normalized.chunked(3)
                val pool = if (cyrillic) "абвгдеёжзийклмнопрстуфхцчшщъыьэюя" else "abcdefghijklmnopqrstuvwxyz"
                val decoys = generateSequence { (1..3).map { pool.random() }.joinToString("") }
                    .filterNot { it in realChunks }
                    .distinct()
                    .take(2)
                    .toList()
                (realChunks + decoys).shuffled()
            }
        } else {
            val letters = answer.lowercase().filter { !it.isWhitespace() }.map { it.toString() }
            val pool = if (cyrillic) "абвгдеёжзийклмнопрстуфхцчшщъыьэюя" else "abcdefghijklmnopqrstuvwxyz"
            val decoyCount = when { letters.size <= 3 -> 2; letters.size <= 6 -> 3; else -> 4 }
            val decoys = pool.map { it.toString() }.filter { it !in letters }.shuffled().take(decoyCount)
            (letters + decoys).shuffled()
        }
    }
    val separator = if (wordMode) " " else ""
    var selected by remember(cardId, expected, resetKey) { mutableStateOf(emptyList<Int>()) }
    fun emit(next: List<Int>) {
        selected = next
        onChange(next.joinToString(separator) { tiles[it] })
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Assembled-answer slot.
        Surface(
            modifier = Modifier.fillMaxWidth().testTag(TestTags.ANSWER_TILE_ASSEMBLED),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = selected.joinToString(separator) { tiles[it] }.ifEmpty { "Tap tiles to build the answer" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                if (selected.isNotEmpty()) {
                    Text(
                        "⌫",
                        modifier = Modifier
                            .testTag(TestTags.ANSWER_TILE_CLEAR)
                            .clip(PillShape)
                            .clickable(enabled = enabled) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                emit(selected.dropLast(1))
                            }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
        // Tile bank.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.forEachIndexed { index, tile ->
                AnswerTile(
                    label = tile,
                    used = index in selected,
                    enabled = enabled,
                    modifier = Modifier.testTag("${TestTags.ANSWER_TILE_PREFIX}_$index"),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        emit(selected + index)
                    }
                )
            }
        }
        Text(
            if (partMode) "Build the word from meaningful parts. $hint" else hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Keeps alternative short-form answers convenient without treating sentence
 * punctuation as an answer separator. */
internal fun tileAnswerText(expected: String): String {
    val trimmed = expected.trim()
    val compact = trimmed.split(Regex("\\s+")).size == 1
    return (if (compact) trimmed.split("/", ";", ",").firstOrNull().orEmpty() else trimmed)
        .trim()
        .replace("́", "")
}

/** Sentence tiles must not carry boundary punctuation: punctuation belongs to the
 * assembled answer, not to a word tile that gives away the final word. */
internal fun sentenceTileWords(expected: String): List<String> =
    Regex("[\\p{L}\\p{N}]+(?:[-'][\\p{L}\\p{N}]+)*")
        .findAll(tileAnswerText(expected))
        .map { it.value.lowercase() }
        .toList()

@Composable
internal fun AnswerTile(
    label: String,
    used: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(stiffness = Spring.StiffnessHigh), label = "tile-scale")
    val container by animateColorAsState(
        if (used || !enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primaryContainer,
        label = "tile-color"
    )
    Surface(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(MaterialTheme.shapes.small)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled && !used, onClick = onClick),
        color = container,
        contentColor = if (used || !enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
        tonalElevation = if (used) 0.dp else 2.dp
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun HeroPill(value: String, label: String) {
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f))
    }
}

@Composable
internal fun StatPill(value: String, label: String) {
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)), MaterialTheme.shapes.small)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FlowRowWithStats(vararg stats: Pair<String, String>) {
    // Center each wrapped line as a group. Without the explicit horizontal
    // alignment, a final stat pill can sit alone against the left edge and make
    // an otherwise balanced card feel unfinished on phone widths.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        stats.forEach { (label, value) -> StatPill(value, label) }
    }
}

@Composable
internal fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: androidx.compose.ui.unit.Dp = 10.dp,
    trackColor: Color,
    color: Color,
    content: @Composable () -> Unit = {}
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow), label = "ring")
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = animated * 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        content()
    }
}

@Composable
internal fun CoverageRing(coverage: Double, modifier: Modifier = Modifier) {
    val pct = (coverage * 100).toInt()
    val ringColor by animateColorAsState(
        when {
            coverage >= 0.9 -> MaterialTheme.colorScheme.primary
            coverage >= 0.6 -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.error
        },
        label = "coverage-color"
    )
    ProgressRing(
        progress = coverage.toFloat(),
        modifier = modifier,
        strokeWidth = 6.dp,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        color = ringColor
    ) {
        Text("$pct%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun animatedInt(target: Int): Int {
    val value by animateIntAsState(target, animationSpec = tween(700, easing = FastOutSlowInEasing), label = "counter")
    return value
}

// ---------------------------------------------------------------------------
// Mapping helpers
// ---------------------------------------------------------------------------

internal fun tabIndex(step: SessionStep): Int = when (step.mainTab()) {
    SessionStep.REVIEWS -> 0
    SessionStep.DASHBOARD -> 1
    SessionStep.LAB -> 2
    SessionStep.IMPORT -> 3
    else -> 0
}

@Composable
internal fun Rating.accent(): Color = when (this) {
    Rating.AGAIN -> Color(0xFFD2453B)
    Rating.HARD -> Color(0xFFE08A1E)
    Rating.GOOD -> SuccessGreen
    Rating.EASY -> Color(0xFF2F73D8)
}

internal fun SessionStep.icon(): ImageVector =
    when (mainTab()) {
        SessionStep.REVIEWS -> Icons.Filled.School
        SessionStep.READER -> Icons.Filled.AutoStories
        SessionStep.DASHBOARD -> Icons.Filled.Dashboard
        SessionStep.LAB -> Icons.Filled.Insights
        SessionStep.IMPORT -> Icons.Filled.Settings
        else -> Icons.Filled.School
    }

internal fun SessionStep.label(): String =
    when (this) {
        SessionStep.REVIEWS -> "Practice"
        SessionStep.RULE -> "Grammar Tip"
        SessionStep.BLOCKED -> "Focused Grammar"
        SessionStep.INTERLEAVED -> "Mixed Grammar"
        SessionStep.READER -> "Read"
        SessionStep.IMPORT -> "Settings"
        SessionStep.DASHBOARD -> "Progress"
        SessionStep.LAB -> "Insights"
    }

internal fun SessionStep.mainTab(): SessionStep =
    when (this) {
        SessionStep.DASHBOARD -> SessionStep.DASHBOARD
        SessionStep.LAB -> SessionStep.LAB
        // The reader is its own destination so it actually renders when selected;
        // it has no bottom-nav item (it's reached from the Practice/Dashboard
        // "Read" actions), which is why it isn't one of the MainTabs.
        SessionStep.READER -> SessionStep.READER
        SessionStep.IMPORT -> SessionStep.IMPORT
        else -> SessionStep.REVIEWS
    }

/** [NavState]'s tab-level Dest -> the SessionStep MainTabContent/MainBottomBar
 * render against. [NavState.tabDest] never returns Study/Reference/Settings, so
 * those fall back to REVIEWS only defensively; they're unreachable in practice. */
internal fun com.sibirskyspeak.review.Dest.toSessionStep(): SessionStep = when (this) {
    com.sibirskyspeak.review.Dest.Practice -> SessionStep.REVIEWS
    com.sibirskyspeak.review.Dest.Dashboard -> SessionStep.DASHBOARD
    is com.sibirskyspeak.review.Dest.Reader -> SessionStep.READER
    com.sibirskyspeak.review.Dest.Lab -> SessionStep.LAB
    com.sibirskyspeak.review.Dest.Import -> SessionStep.IMPORT
    com.sibirskyspeak.review.Dest.Study,
    com.sibirskyspeak.review.Dest.Reference,
    com.sibirskyspeak.review.Dest.Settings -> SessionStep.REVIEWS
}

/** The inverse mapping, used when the bottom nav bar picks a new tab. */
internal fun SessionStep.toTabDest(): com.sibirskyspeak.review.Dest = when (mainTab()) {
    SessionStep.DASHBOARD -> com.sibirskyspeak.review.Dest.Dashboard
    SessionStep.READER -> com.sibirskyspeak.review.Dest.Reader()
    SessionStep.LAB -> com.sibirskyspeak.review.Dest.Lab
    SessionStep.IMPORT -> com.sibirskyspeak.review.Dest.Import
    else -> com.sibirskyspeak.review.Dest.Practice
}

internal fun reviewTaskTitle(prompt: ReviewPrompt): String =
    when (prompt.card.cardType) {
        CardType.RU_TO_MEANING -> "Translate this Russian word"
        CardType.MEANING_TO_RU -> "Recall the Russian word"
        CardType.CLOZE -> "Fill in the missing Russian word"
        CardType.AUDIO_TO_RU -> "Listen and build the Russian word"
        CardType.SPEAK -> "Say the Russian aloud"
        CardType.CASE_FILL -> "Choose the right Russian form"
        CardType.VERB_FORM -> "Conjugate this Russian verb"
        CardType.ADJ_AGREE -> "Make the adjective agree"
        CardType.GENDER_ID -> "Identify the noun gender"
        CardType.ASPECT_SELECT -> "Pick the verb form that fits"
        CardType.CONCEPT_DRILL -> "Practice the grammar concept"
        CardType.CONCEPT_APPLY -> "Apply the rule in a new sentence"
        CardType.CHUNK -> "Type this common Russian chunk"
        CardType.TRANSFORM -> "Rewrite the sentence"
        CardType.NOVEL_PRODUCE -> "Write a sentence of your own"
        CardType.SPEAK_SENTENCE -> "Listen, then repeat the sentence"
        CardType.DICTATION -> "Dictation: listen and build"
        CardType.SENTENCE_BUILD -> "Build the Russian sentence"
        CardType.STRESS_MARK -> "Mark the stress"
        CardType.LESSON -> "Read the grammar lesson"
        CardType.PHONOLOGY_MINIMAL_PAIR -> "Listen and choose the word you heard"
    }

internal fun reviewFacetLabel(prompt: ReviewPrompt): String = when (CardPedagogy.profile(prompt.card.cardType).facet) {
    LearningFacet.MEANING -> "Meaning"
    LearningFacet.FORM -> "Production"
    LearningFacet.CONTEXT -> "Context"
    LearningFacet.LISTENING -> "Listening"
    LearningFacet.PRONUNCIATION -> "Pronunciation"
    LearningFacet.SYNTAX -> "Grammar"
    LearningFacet.MORPHOLOGY -> "Morphology"
    LearningFacet.INSTRUCTION -> "Lesson"
}

internal fun reviewTaskHelp(prompt: ReviewPrompt): String =
    when (prompt.card.cardType) {
        CardType.RU_TO_MEANING -> "Type the English meaning."
        CardType.MEANING_TO_RU -> "Build the Russian word from the tiles, or switch to the keyboard."
        CardType.CLOZE -> "Use the sentence context, then build or type the missing Russian word."
        CardType.AUDIO_TO_RU -> "Listen, then build or type the Russian word you hear."
        CardType.SPEAK -> "Use the mic to say the Russian word or phrase aloud."
        CardType.CASE_FILL -> if (prompt.card.reps >= 2) {
            if (prompt.answerMode == AnswerMode.CHOICE) {
                "Read the sentence cues and choose the form that fits the required case."
            } else {
                "Read the sentence cues and type the inflected form that fits the required case."
            }
        } else if (prompt.answerMode == AnswerMode.CHOICE) {
            "Use the case cue and choose the ending that completes the form."
        } else {
            "Use the sentence and case label to type the inflected form."
        }
        CardType.VERB_FORM -> if (prompt.answerMode == AnswerMode.CHOICE) {
            "Use the person and tense label to choose the conjugation ending."
        } else {
            "Use the grammar label to type the conjugated verb form."
        }
        CardType.ADJ_AGREE -> if (prompt.answerMode == AnswerMode.CHOICE) {
            if (prompt.card.reps == 0) "Use the labeled endings as a reference and match the noun's gender or number."
            else "Recall the agreement category and choose its ending."
        } else {
            "Use the noun context to type the matching adjective form."
        }
        CardType.GENDER_ID -> "Choose the gender that fits this noun."
        CardType.ASPECT_SELECT -> if (prompt.answerMode == AnswerMode.CHOICE) {
            "Choose the form that matches whether the action is bounded or ongoing."
        } else {
            "Type the form that matches whether the action is bounded or ongoing."
        }
        CardType.CONCEPT_DRILL -> "Use the rule from the lesson to answer this authored grammar prompt."
        CardType.CONCEPT_APPLY -> "This is a brand-new sentence — apply the rule, don't recall a memorized one."
        CardType.CHUNK -> "Type the whole chunk, not just the headword — word order and any preposition matter."
        CardType.TRANSFORM -> "Build or type the whole rewritten sentence, following the instruction exactly."
        CardType.NOVEL_PRODUCE -> "There's no Russian shown — compose your own sentence expressing the English cue."
        CardType.SPEAK_SENTENCE -> "Listen to the sentence, then repeat it aloud from memory — word order matters."
        CardType.DICTATION -> "Listen, then build or type the Russian sentence you hear."
        CardType.SENTENCE_BUILD -> "Read the English meaning, then tap the Russian words in order."
        CardType.STRESS_MARK -> "Choose the spelling with the stressed vowel marked."
        CardType.LESSON -> "Read the explanation, then continue when it feels familiar."
        CardType.PHONOLOGY_MINIMAL_PAIR -> "Listen closely — this pair differs by a single sound. Choose the word you heard."
    }

internal fun answerHint(prompt: ReviewPrompt): String =
    when (prompt.answerMode) {
        AnswerMode.ENGLISH -> "Type the English meaning."
        AnswerMode.RUSSIAN_TYPED -> if (prompt.card.cardType in setOf(
                CardType.CASE_FILL,
                CardType.ADJ_AGREE,
                CardType.VERB_FORM,
                CardType.CONCEPT_DRILL,
                CardType.CONCEPT_APPLY
            )) {
                "Build the exact Russian form. Stress marks are optional."
            } else {
                "Type in Russian. Stress marks and small spelling slips are okay."
            }
        AnswerMode.RUSSIAN_STRESS_TYPED -> "Type Russian with the stress mark."
        AnswerMode.AUDIO_ONLY -> "Type the Russian you heard. Small spelling slips are okay."
        AnswerMode.SPEAK -> "Tap the mic and say it aloud."
        AnswerMode.CHOICE -> "Pick one of the choices."
        AnswerMode.LESSON -> "Read the lesson, then tap Got it."
    }

internal fun AnswerMode.modeLabel(): String =
    when (this) {
        AnswerMode.ENGLISH -> "English recall"
        AnswerMode.RUSSIAN_TYPED -> "Russian typing"
        AnswerMode.RUSSIAN_STRESS_TYPED -> "Stress mark"
        AnswerMode.AUDIO_ONLY -> "Listening"
        AnswerMode.SPEAK -> "Speaking"
        AnswerMode.CHOICE -> "Multiple choice"
        AnswerMode.LESSON -> "Lesson"
    }

/**
 * True when the note's example translation is a real sentence gloss (multi-word and
 * not merely the headword), so it can be shown as comprehensible input.
 */
internal fun ReviewPrompt.hasSentenceGloss(): Boolean {
    val gloss = exampleTranslation?.trim().orEmpty()
    return gloss.isNotBlank() &&
        !gloss.equals(note.translation.trim(), ignoreCase = true) &&
        gloss.split(Regex("\\s+")).size >= 2
}

internal fun reviewContext(prompt: ReviewPrompt): String? =
    when (prompt.card.cardType) {
        // Recognition (produce the English meaning): show ONLY the Russian example on
        // the prompt side — seeing the word in a real sentence aids recognition, but we
        // must NOT show the English translation here or it gives the answer away and
        // destroys retrieval practice. The translation is shown on reveal instead.
        // The recognition prompt already embeds the sentence for context-bound function
        // words and higher-rep cards ("…\n\nWhat does X mean here?" / "In context: …").
        // Only add the standalone "Example:" line when it isn't already on screen, so the
        // same sentence isn't printed two (and, with the reveal block, three) times.
        CardType.RU_TO_MEANING -> prompt.exampleSentence
            ?.takeUnless { prompt.prompt.contains(it) }
            ?.let { "Example: $it" }
        CardType.MEANING_TO_RU -> null
        // CLOZE blanks the target word IN its example, so the English translation would
        // hand over the very word you must produce — withhold it (the Russian carrier is
        // already comprehensible context) and show the meaning only on reveal.
        CardType.CLOZE -> null
        // Case/verb drills show the Russian carrier in the prompt; the meaning
        // helps comprehension without revealing the answer (an inflected FORM, not the
        // dictionary word the gloss names), so keep it.
        CardType.VERB_FORM, CardType.ASPECT_SELECT, CardType.CONCEPT_DRILL, CardType.STRESS_MARK ->
            if (prompt.hasSentenceGloss()) meaningLine(prompt.exampleTranslation.orEmpty()) else null
        // The English cue is the realized frame's translation (LearningRepository
        // overrides exampleTranslation with it) — always show it, since the Russian
        // carrier has its target slot blanked and needs the English for context.
        CardType.CONCEPT_APPLY -> prompt.exampleTranslation?.takeIf { it.isNotBlank() }?.let { meaningLine(it) }
        // The prompt is the real sentence with the chunk blanked; the English
        // translation of that same sentence is the context needed to produce it.
        CardType.CHUNK -> prompt.exampleTranslation?.takeIf { it.isNotBlank() }?.let { meaningLine(it) }
        // Russian-to-Russian rewrite: the instruction and original sentence are
        // already the whole prompt; no separate English line applies.
        CardType.TRANSFORM -> null
        // The English cue IS prompt.prompt for this card type; no separate line.
        CardType.NOVEL_PRODUCE -> null
        // No translation before the attempt — imitation tests whether the learner
        // can parse the Russian unaided; the gloss is shown on reveal instead.
        CardType.SPEAK_SENTENCE -> null
        // Agreement drills use an intentionally simple carrier noun that usually differs
        // from the note's corpus example. Showing that example's English translation here
        // falsely implies that it translates the drill phrase (for example, "___ дома").
        CardType.CASE_FILL, CardType.ADJ_AGREE -> null
        CardType.GENDER_ID -> null
        CardType.AUDIO_TO_RU -> null
        CardType.SPEAK -> prompt.exampleTranslation?.takeIf { it.isNotBlank() }?.let { meaningLine(it) }
        CardType.DICTATION -> null
        CardType.SENTENCE_BUILD -> null
        CardType.LESSON -> null
        // Like AUDIO_TO_RU: no meaning shown pre-answer, this is a pure ear test.
        CardType.PHONOLOGY_MINIMAL_PAIR -> null
    }

/**
 * Comprehensible-input reinforcement shown on the *reveal* side (after answering), so
 * the learner sees the word living in a full sentence with its meaning — the part we
 * deliberately withheld from the recognition prompt to keep retrieval honest. Returns a
 * Russian line and (when available) its English gloss for the vocab cards.
 */
internal fun reviewRevealContext(prompt: ReviewPrompt): Pair<String, String?>? =
    when (prompt.card.cardType) {
        // CASE_FILL is deliberately excluded here too: its carrier phrase is a synthetic
        // agreement drill that usually differs from the note's corpus example (see
        // reviewContext above), so showing that example on reveal would be just as
        // misleading as showing it on the prompt.
        CardType.RU_TO_MEANING, CardType.MEANING_TO_RU, CardType.CLOZE, CardType.AUDIO_TO_RU,
        CardType.VERB_FORM ->
            prompt.exampleSentence?.let { ru ->
                ru to prompt.exampleTranslation?.takeIf { prompt.hasSentenceGloss() }
            }
        else -> null
    }

internal fun formatDays(days: Int): String =
    when (days) {
        0 -> "10m"
        1 -> "1d"
        else -> "${days}d"
    }

internal fun Rating.shortLabel(days: Int): String =
    "${name.lowercase().replaceFirstChar { it.titlecase() }}\n${formatDays(days)}"

internal fun Rating.recallCaption(): String =
    when (this) {
        Rating.AGAIN -> "Forgot"
        Rating.HARD -> "Slow"
        Rating.GOOD -> "Solid"
        Rating.EASY -> "Instant"
    }

internal fun ReaderStatus.label(): String =
    when (this) {
        ReaderStatus.TOO_HARD -> "hard"
        ReaderStatus.PRODUCTIVE -> "good"
        ReaderStatus.EASY -> "easy"
    }

internal fun formatCount(value: Int): String =
    "%,d".format(value)

/** Split a reader passage into sentences for sentence-by-sentence audio playback. */
internal fun splitIntoSentences(text: String): List<String> =
    text.split(Regex("(?<=[.!?…])\\s+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
