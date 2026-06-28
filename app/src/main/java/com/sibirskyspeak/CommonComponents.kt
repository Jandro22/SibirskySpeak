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
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReaderRecommendation
import com.sibirskyspeak.data.GamificationStats
import com.sibirskyspeak.data.ReaderStatus
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.review.SessionStep

// ---------------------------------------------------------------------------

@Composable
internal fun SessionCompleteCard(
    game: GamificationStats,
    onDone: () -> Unit,
    reader: ReaderRecommendation? = null,
    sessionReviewed: Int = 0,
    sessionCorrect: Int = 0,
    onExtraCredit: () -> Unit = {},
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
                if (game.goalReached) "Daily goal complete" else "Session complete",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                if (sessionReviewed > 0)
                    "This sitting: $sessionReviewed ${if (sessionReviewed == 1) "card" else "cards"}, ${(sessionCorrect * 100) / sessionReviewed}% right · ${game.reviewedToday} today."
                else
                    "You reviewed ${game.reviewedToday} ${if (game.reviewedToday == 1) "card" else "cards"} today.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sessionReviewed > 0) HeroPill("${(sessionCorrect * 100) / sessionReviewed}%", "this sitting")
                HeroPill("${game.currentStreak}", "day streak")
                HeroPill("Lvl ${game.level}", "level")
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimary,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Back to Practice", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (protectPacing) {
                if (prioritizeReading) {
                    TextButton(onClick = onDone, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) {
                        Text("Back to Practice")
                    }
                }
                TextButton(onClick = onExtraCredit, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Extra credit")
                }
                Text(
                    if (lowAccuracy && reader != null) {
                        "Add more cards only if the misses were slips; otherwise stop here or read and let reviews settle."
                    } else if (lowAccuracy) {
                        "Add more cards only if the misses were slips; otherwise stop here and let reviews settle."
                    } else if (reader != null) {
                        "Add more cards only if this session felt easy; otherwise read and let the reviews settle."
                    } else {
                        "Daily goal is done. Add more cards only if this session felt easy."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center
                )
            } else {
                OutlinedButton(
                    onClick = onExtraCredit,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f))
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Extra credit (+10 cards)", fontWeight = FontWeight.SemiBold)
                }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
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
            Column(Modifier.padding(20.dp), content = content)
        }
    }
}

@Composable
internal fun SectionCard(emphasis: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (emphasis) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
internal fun StatusBanner(message: String, onDismiss: (() -> Unit)? = null) {
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
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss message", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
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
 * (the approach Duolingo uses). Tiles are the answer's letters (or words, for
 * multi-word answers) plus a few decoys, shuffled. The assembled string feeds the
 * existing answer evaluation untouched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LetterTileBank(
    expected: String,
    cardId: Long,
    hint: String,
    onChange: (String) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    // Strip the combining stress mark so it never becomes its own phantom tile;
    // answers are scored stress-insensitively anyway.
    val answer = remember(cardId, expected) {
        expected.split("/", ";", ",").firstOrNull()?.trim().orEmpty().replace("́", "")
    }
    val wordMode = answer.contains(' ')
    val cyrillic = answer.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
    val tiles = remember(cardId, expected) {
        if (wordMode) {
            val words = answer.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            val decoyPool = if (cyrillic) listOf("и", "в", "не", "на", "с", "по") else listOf("the", "a", "to", "of", "is", "in")
            (words + decoyPool.filter { it !in words }.shuffled().take(2)).shuffled()
        } else {
            val letters = answer.lowercase().filter { !it.isWhitespace() }.map { it.toString() }
            val pool = if (cyrillic) "абвгдеёжзийклмнопрстуфхцчшщъыьэюя" else "abcdefghijklmnopqrstuvwxyz"
            val decoyCount = when { letters.size <= 3 -> 2; letters.size <= 6 -> 3; else -> 4 }
            val decoys = pool.map { it.toString() }.filter { it !in letters }.shuffled().take(decoyCount)
            (letters + decoys).shuffled()
        }
    }
    val separator = if (wordMode) " " else ""
    var selected by remember(cardId, expected) { mutableStateOf(emptyList<Int>()) }
    fun emit(next: List<Int>) {
        selected = next
        onChange(next.joinToString(separator) { tiles[it] })
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Assembled-answer slot.
        Surface(
            modifier = Modifier.fillMaxWidth(),
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
                            .clip(RoundedCornerShape(50))
                            .clickable {
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
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        emit(selected + index)
                    }
                )
            }
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AnswerTile(label: String, used: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(stiffness = Spring.StiffnessHigh), label = "tile-scale")
    val container by animateColorAsState(
        if (used) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primaryContainer,
        label = "tile-color"
    )
    Surface(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(MaterialTheme.shapes.small)
            .clickable(interactionSource = interaction, indication = null, enabled = !used, onClick = onClick),
        color = container,
        contentColor = if (used) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onPrimaryContainer,
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    SessionStep.IMPORT -> 2
    else -> 0
}

@Composable
internal fun Rating.accent(): Color = when (this) {
    Rating.AGAIN -> Color(0xFFD2453B)
    Rating.HARD -> Color(0xFFE08A1E)
    Rating.GOOD -> Color(0xFF2E9E5B)
    Rating.EASY -> Color(0xFF2F73D8)
}

internal fun SessionStep.icon(): ImageVector =
    when (mainTab()) {
        SessionStep.REVIEWS -> Icons.Filled.School
        SessionStep.READER -> Icons.Filled.AutoStories
        SessionStep.DASHBOARD -> Icons.Filled.Insights
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
    }

internal fun SessionStep.mainTab(): SessionStep =
    when (this) {
        SessionStep.DASHBOARD -> SessionStep.DASHBOARD
        // The reader is its own destination so it actually renders when selected;
        // it has no bottom-nav item (it's reached from the Practice/Dashboard
        // "Read" actions), which is why it isn't one of the MainTabs.
        SessionStep.READER -> SessionStep.READER
        SessionStep.IMPORT -> SessionStep.IMPORT
        else -> SessionStep.REVIEWS
    }

internal fun reviewTaskTitle(prompt: ReviewPrompt): String =
    when (prompt.card.cardType) {
        CardType.RU_TO_MEANING -> "Translate this Russian word"
        CardType.MEANING_TO_RU -> "Recall the Russian word"
        CardType.CLOZE -> "Fill in the missing Russian word"
        CardType.AUDIO_TO_RU -> "Listen and type the Russian"
        CardType.SPEAK -> "Say the Russian aloud"
        CardType.CASE_FILL -> "Choose the right Russian form"
        CardType.VERB_FORM -> "Conjugate this Russian verb"
        CardType.ADJ_AGREE -> "Make the adjective agree"
        CardType.GENDER_ID -> "Identify the noun gender"
        CardType.ASPECT_SELECT -> "Pick the verb form that fits"
        CardType.CONCEPT_DRILL -> "Practice the grammar concept"
        CardType.DICTATION -> "Dictation: listen and type"
        CardType.SENTENCE_BUILD -> "Build the Russian sentence"
        CardType.STRESS_MARK -> "Mark the stress"
        CardType.LESSON -> "Read the grammar lesson"
    }

internal fun reviewTaskHelp(prompt: ReviewPrompt): String =
    when (prompt.card.cardType) {
        CardType.RU_TO_MEANING -> "Type the English meaning. The Russian word is the thing being tested."
        CardType.MEANING_TO_RU -> "Type the Russian word for this English meaning."
        CardType.CLOZE -> "Use the sentence context and type the missing Russian word."
        CardType.AUDIO_TO_RU -> "Audio plays automatically. Type what you hear."
        CardType.SPEAK -> "Use the mic to say the Russian word or phrase aloud."
        CardType.CASE_FILL -> "Use the sentence and case label to type the inflected form."
        CardType.VERB_FORM -> "Use the grammar label to type the conjugated verb form."
        CardType.ADJ_AGREE -> "Use the noun context to type the matching adjective form."
        CardType.GENDER_ID -> "Choose the gender that fits this noun."
        CardType.ASPECT_SELECT -> "Choose the form that matches whether the action is bounded or ongoing."
        CardType.CONCEPT_DRILL -> "Use the rule from the lesson to answer this authored grammar prompt."
        CardType.DICTATION -> "Listen to the Russian sentence and type what you hear."
        CardType.SENTENCE_BUILD -> "Translate the English sentence by writing the Russian sentence."
        CardType.STRESS_MARK -> "Choose the spelling with the stressed vowel marked."
        CardType.LESSON -> "Read the explanation, then continue when it feels familiar."
    }

internal fun answerHint(prompt: ReviewPrompt): String =
    when (prompt.answerMode) {
        AnswerMode.ENGLISH -> "Type the English meaning."
        AnswerMode.RUSSIAN_TYPED -> "Type in Russian. Stress marks and small spelling slips are okay."
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
        CardType.RU_TO_MEANING -> prompt.exampleSentence?.let { "Example: $it" }
        CardType.MEANING_TO_RU -> null
        // CLOZE blanks the target word IN its example, so the English translation would
        // hand over the very word you must produce — withhold it (the Russian carrier is
        // already comprehensible context) and show the meaning only on reveal.
        CardType.CLOZE -> null
        // Case/verb/agreement drills show the Russian carrier in the prompt; the meaning
        // helps comprehension without revealing the answer (an inflected FORM, not the
        // dictionary word the gloss names), so keep it.
        CardType.CASE_FILL, CardType.VERB_FORM, CardType.ADJ_AGREE, CardType.ASPECT_SELECT, CardType.CONCEPT_DRILL, CardType.STRESS_MARK ->
            if (prompt.hasSentenceGloss()) "Meaning: ${prompt.exampleTranslation}" else null
        CardType.GENDER_ID -> null
        CardType.AUDIO_TO_RU -> null
        CardType.SPEAK -> prompt.exampleTranslation?.takeIf { it.isNotBlank() }?.let { "Meaning: $it" }
        CardType.DICTATION -> null
        CardType.SENTENCE_BUILD -> null
        CardType.LESSON -> null
    }

/**
 * Comprehensible-input reinforcement shown on the *reveal* side (after answering), so
 * the learner sees the word living in a full sentence with its meaning — the part we
 * deliberately withheld from the recognition prompt to keep retrieval honest. Returns a
 * Russian line and (when available) its English gloss for the vocab cards.
 */
internal fun reviewRevealContext(prompt: ReviewPrompt): Pair<String, String?>? =
    when (prompt.card.cardType) {
        CardType.RU_TO_MEANING, CardType.MEANING_TO_RU, CardType.CLOZE ->
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
        Rating.EASY -> "Instant / knew"
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

