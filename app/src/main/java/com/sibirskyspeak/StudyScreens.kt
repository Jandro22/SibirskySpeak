package com.sibirskyspeak

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.sibirskyspeak.audio.RussianSpeechRecognizer
import com.sibirskyspeak.audio.AnswerSoundEffects
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.GamificationStats
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.AnswerMatch
import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.review.ReviewUiState
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Study session
// ---------------------------------------------------------------------------

@Composable
internal fun StudySessionScreen(
    state: ReviewUiState,
    onAnswerChanged: (String) -> Unit,
    onChoice: (String) -> Unit,
    onReveal: () -> Unit,
    onRate: (Rating) -> Unit,
    onContinue: () -> Unit,
    onCorrectionChanged: (String) -> Unit,
    onSubmitCorrection: () -> Unit,
    onSpeak: (ReviewPrompt) -> Unit,
    onExit: () -> Unit,
    onUndo: () -> Unit,
    onKnewIt: () -> Unit,
    onSuspend: () -> Unit,
    onKnowWord: () -> Unit,
    onStartSession: () -> Unit,
    onSaveEdit: (String?, String?, String?, String?) -> Unit,
    onExtraCredit: () -> Unit = {},
    onReadNext: () -> Unit = {}
) {
    LaunchedEffect(Unit) { if (!state.inStudySession) onStartSession() }
    var editing by remember { mutableStateOf(false) }
    var retireAction by remember { mutableStateOf<ReviewRetireAction?>(null) }
    val sessionSize = state.sessionPlan?.reviewQueue?.size ?: 0
    val prompt = state.prompt
    // Live goal setting (updates instantly), not the gamification snapshot.
    val dailyGoal = state.dailyGoalSetting.coerceAtLeast(1)
    val remainingCards = sessionSize
    val sittingTotal = state.sessionCompletedCards + remainingCards
    val goalProgress = if (sittingTotal > 0) {
        (state.sessionCompletedCards.toFloat() / sittingTotal).coerceIn(0f, 1f)
    } else 1f
    val animatedGoalProgress by animateFloatAsState(
        targetValue = goalProgress,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "sitting-progress"
    )
    val headerMessage = when {
        prompt == null -> state.sessionPlan?.completion?.message ?: "Session complete."
        state.revealed -> "Check the answer, then rate your recall."
        prompt.answerMode == AnswerMode.AUDIO_ONLY -> "Audio started automatically. Type what you heard."
        sessionSize > 0 -> "$sessionSize cards ready now. ${state.reviewedToday}/$dailyGoal reviews today."
        else -> "Answer, check, then rate recall."
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            tonalElevation = 1.dp
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Practice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            PracticeStageChip(
                                when {
                                    prompt == null -> "Done"
                                    state.revealed -> "Rate"
                                    else -> "Answer"
                                }
                            )
                        }
                        Text(
                            headerMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Compact icon actions so the header never squishes on narrow phones.
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (prompt != null && prompt.card.queue.name == "VOCAB") {
                            IconButton(
                                onClick = { retireAction = ReviewRetireAction.MARK_KNOWN },
                                enabled = !state.ratingInProgress
                            ) {
                                Icon(Icons.Filled.DoneAll, contentDescription = "Mark known and stop vocab practice")
                            }
                        }
                        if (prompt != null) {
                            IconButton(onClick = { editing = true }, enabled = !state.ratingInProgress) {
                                Icon(Icons.Filled.Edit, contentDescription = "Fix this card")
                            }
                            IconButton(
                                onClick = { retireAction = ReviewRetireAction.SUSPEND_CARD },
                                enabled = !state.ratingInProgress
                            ) {
                                Icon(Icons.Filled.Block, contentDescription = "Suspend this card permanently")
                            }
                        }
                        if (state.canUndo) {
                            IconButton(onClick = onUndo, enabled = !state.ratingInProgress) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo last review")
                            }
                        }
                        IconButton(onClick = onExit) {
                            Icon(Icons.Filled.Close, contentDescription = "Exit practice")
                        }
                    }
                }
                LinearProgressIndicator(
                    progress = { animatedGoalProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                if (prompt != null) {
                    val queue = state.sessionPlan?.reviewQueue.orEmpty()
                    SessionProgressStrip(
                        newCount = queue.count { it.card.state.name == "NEW" },
                        learningCount = queue.count { it.card.state.name == "LEARNING" || it.card.state.name == "RELEARNING" },
                        reviewCount = queue.count { it.card.state.name == "REVIEW" || it.card.state.name == "GRADUATED" },
                        prompt = prompt,
                        reviewedToday = state.reviewedToday,
                        dailyGoal = dailyGoal
                    )
                }
            }
        }
        AnimatedContent(
            targetState = prompt?.card?.id ?: -1L,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(spring(stiffness = Spring.StiffnessLow), initialScale = 0.94f) + slideInHorizontally(tween(260)) { it / 4 })
                    .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -it / 5 })
                    .using(SizeTransform(clip = false))
            },
            label = "review-card"
        ) { targetCardId ->
            if (targetCardId == -1L || prompt == null) {
                SessionCompleteCard(
                    state.sessionPlan?.gamification ?: GamificationStats.EMPTY,
                    onDone = onExit,
                    reader = state.sessionPlan?.readingAssignment?.recommendation,
                    sessionReviewed = state.sessionReviewed,
                    sessionCorrect = state.sessionCorrect,
                    onExtraCredit = onExtraCredit,
                    onReadNext = onReadNext
                )
            } else {
                ReviewContent(
                    state = state,
                    prompt = prompt,
                    onAnswerChanged = onAnswerChanged,
                    onChoice = onChoice,
                    onReveal = onReveal,
                    onRate = onRate,
                    onContinue = onContinue,
                    onCorrectionChanged = onCorrectionChanged,
                    onSubmitCorrection = onSubmitCorrection,
                    onSpeak = { onSpeak(prompt) },
                    onKnewIt = onKnewIt
                )
            }
        }
    }
    if (editing && prompt != null) {
        EditCardDialog(
            note = prompt.note,
            onDismiss = { editing = false },
            onSave = { t, ex, exT, mnemonic ->
                onSaveEdit(t, ex, exT, mnemonic)
                editing = false
            }
        )
    }
    retireAction?.let { action ->
        ReviewRetireConfirmDialog(
            action = action,
            prompt = prompt,
            onDismiss = { retireAction = null },
            onConfirm = {
                retireAction = null
                when (action) {
                    ReviewRetireAction.MARK_KNOWN -> onKnowWord()
                    ReviewRetireAction.SUSPEND_CARD -> onSuspend()
                }
            }
        )
    }
}

internal enum class ReviewRetireAction {
    MARK_KNOWN,
    SUSPEND_CARD
}

@Composable
internal fun ReviewRetireConfirmDialog(
    action: ReviewRetireAction,
    prompt: ReviewPrompt?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val word = prompt?.note?.russian.orEmpty()
    val (title, body, confirmLabel) = when (action) {
        ReviewRetireAction.MARK_KNOWN -> Triple(
            "Mark known?",
            "This retires vocab practice for ${word.ifBlank { "this word" }}. Use it only when the word is already familiar outside this card.",
            "Mark known"
        )
        ReviewRetireAction.SUSPEND_CARD -> Triple(
            "Suspend card?",
            "This removes only this card from review queues. Use it for broken or unhelpful prompts; use Fix when the content can be repaired.",
            "Suspend"
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
internal fun EditCardDialog(
    note: Note,
    onDismiss: () -> Unit,
    onSave: (String?, String?, String?, String?) -> Unit
) {
    var translation by remember(note.id) { mutableStateOf(note.translation) }
    var example by remember(note.id) { mutableStateOf(note.exampleSentence.orEmpty()) }
    var exampleTranslation by remember(note.id) { mutableStateOf(note.exampleTranslation.orEmpty()) }
    var mnemonic by remember(note.id) { mutableStateOf(note.mnemonic.orEmpty()) }
    val sentenceGlossReady = example.trim().isNotBlank() &&
        exampleTranslation.trim().isNotBlank() &&
        !exampleTranslation.trim().equals(translation.trim(), ignoreCase = true) &&
        exampleTranslation.trim().split(Regex("\\s+")).size >= 2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fix “${note.russian}”") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it },
                    label = { Text("Meaning") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = example,
                    onValueChange = { example = it },
                    label = { Text("Example (Russian)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = exampleTranslation,
                    onValueChange = { exampleTranslation = it },
                    label = { Text("Sentence meaning") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mnemonic,
                    onValueChange = { mnemonic = it },
                    label = { Text("Memory hook (optional)") },
                    supportingText = { Text("A sound, image, or personal association—shown only when useful.") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (example.isNotBlank()) {
                    Text(
                        if (sentenceGlossReady) {
                            "Ready for readable context and cloze recall."
                        } else {
                            "Use a full sentence meaning here; one-word glosses do not create context recall."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sentenceGlossReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    translation.trim().takeIf { it.isNotBlank() && it != note.translation },
                    example.trim().takeIf { it.isNotBlank() && it != note.exampleSentence },
                    exampleTranslation.trim().takeIf { it.isNotBlank() && it != note.exampleTranslation },
                    mnemonic.trim().takeIf { it.isNotBlank() && it != note.mnemonic }
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionProgressStrip(
    newCount: Int,
    learningCount: Int,
    reviewCount: Int,
    prompt: ReviewPrompt,
    reviewedToday: Int,
    dailyGoal: Int
) {
    val concept = prompt.teachingHint?.takeIf { prompt.card.queue.name == "GRAMMAR" }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // AnkiDroid-style remaining counts: new (blue) · learning (red) · review (green).
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            QueueCount(newCount, Color(0xFF2F73D8))
            QueueCount(learningCount, Color(0xFFD2453B))
            QueueCount(reviewCount, Color(0xFF2E9E5B))
        }
        Text(
            "$reviewedToday today · goal $dailyGoal",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PracticeFocusChip(if (prompt.card.queue.name == "VOCAB") "Vocabulary" else "Grammar", null)
        PracticeFocusChip(prompt.answerMode.modeLabel(), null)
        concept?.let { PracticeFocusChip(it, null) }
    }
}

/** A single AnkiDroid-style colored remaining-count number. */
@Composable
internal fun QueueCount(count: Int, color: Color) {
    Text(
        count.toString(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (count > 0) color else color.copy(alpha = 0.35f)
    )
}

@Composable
internal fun PracticeStageChip(label: String) {
    val isRating = label == "Rate"
    val container by animateColorAsState(
        targetValue = if (isRating) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        animationSpec = tween(260),
        label = "stage-chip-container"
    )
    val content by animateColorAsState(
        targetValue = if (isRating) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
        animationSpec = tween(260),
        label = "stage-chip-content"
    )
    // Static text (no vertical AnimatedContent — that was clipping the chip's height);
    // the color still animates between Answer/Rate/Done states.
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
internal fun AutoPlayCardAudio(cardId: Long, onSpeak: () -> Unit) {
    LaunchedEffect(cardId) {
        onSpeak()
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewContent(
    state: ReviewUiState,
    prompt: ReviewPrompt,
    onAnswerChanged: (String) -> Unit,
    onChoice: (String) -> Unit,
    onReveal: () -> Unit,
    onRate: (Rating) -> Unit,
    onContinue: () -> Unit,
    onCorrectionChanged: (String) -> Unit,
    onSubmitCorrection: () -> Unit,
    onSpeak: () -> Unit,
    onKnewIt: () -> Unit
) {
    val answerSounds = remember { AnswerSoundEffects() }
    DisposableEffect(answerSounds) { onDispose { answerSounds.release() } }
    LaunchedEffect(state.feedbackSequence) {
        if (state.feedbackSequence > 0) state.feedbackCorrect?.let(answerSounds::play)
    }
    // Only auto-play audio when hearing the Russian can't give the answer away:
    // listening cards (the audio *is* the prompt) and recognition cards (the Russian
    // word is already shown). For production, cloze, stress, and choice cards, auto-
    // play would speak the very answer the learner is meant to recall — so it's off;
    // they can still tap "Hear Russian" any time (and after reveal).
    if (prompt.answerMode == AnswerMode.AUDIO_ONLY || prompt.answerMode == AnswerMode.ENGLISH) {
        AutoPlayCardAudio(cardId = prompt.card.id, onSpeak = onSpeak)
    }
    // A lesson is a teaching screen, not a quiz: render it on its own and bail out
    // of the normal answer/reveal flow.
    prompt.lesson?.let { lesson ->
        LessonCard(lesson = lesson, onSpeak = onSpeak, onGotIt = { onRate(Rating.GOOD) }, ratingInProgress = state.ratingInProgress)
        return
    }

    // Offer tiles for Russian typing and for listening (AUDIO_ONLY) so the learner
    // rarely needs a Russian keyboard at all. LetterTileBank switches to whole-word
    // tiles automatically for multi-word answers, so short phrases work too.
    val supportsTiles = prompt.answerMode == AnswerMode.RUSSIAN_TYPED ||
        prompt.answerMode == AnswerMode.AUDIO_ONLY
    var keyboardMode by rememberSaveable(prompt.card.id) { mutableStateOf(!supportsTiles) }
    SectionCard(emphasis = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(reviewTaskTitle(prompt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(reviewTaskHelp(prompt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusTag(if (prompt.card.queue.name == "VOCAB") "Vocab" else "Grammar")
                if (prompt.note.tier == 0 && prompt.note.unit != null) {
                    Text(
                        "${prompt.note.cefrLevel ?: "A1"} - Unit ${prompt.note.unit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        prompt.queueReason?.let { reason ->
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "Why this card: $reason",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusTag(
                        when (prompt.answerMode) {
                            AnswerMode.ENGLISH -> "English"
                            AnswerMode.RUSSIAN_TYPED -> "Russian"
                            AnswerMode.RUSSIAN_STRESS_TYPED -> "Stress"
                            AnswerMode.AUDIO_ONLY -> "Listening"
                            AnswerMode.SPEAK -> "Speaking"
                            AnswerMode.CHOICE -> "Choice"
                            AnswerMode.LESSON -> "Lesson"
                        }
                    )
                    // Only offer "Hear Russian" before answering on recognition cards,
                    // where the Russian is the prompt. On production/choice/stress cards
                    // it would just read out the answer — that audio comes on reveal.
                    if (prompt.answerMode == AnswerMode.ENGLISH) {
                        AssistChip(
                            onClick = onSpeak,
                            label = { Text("Hear Russian") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            colors = AssistChipDefaults.assistChipColors(
                                leadingIconContentColor = MaterialTheme.colorScheme.primary,
                                labelColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
                prompt.teachingHint?.takeIf { prompt.card.queue.name == "GRAMMAR" }?.let { hint ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(hint, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                // First-exposure cue: a brand-new word you've never been tested on.
                // Orients the learner to treat it as a learn-then-reveal moment (and
                // rate honestly) rather than feeling they failed a word they never saw.
                if (!state.revealed && prompt.card.queue.name == "VOCAB" &&
                    prompt.card.state.name == "NEW" && prompt.card.reps == 0
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("New word — first time. Try it, then reveal to learn it.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Text(
                    prompt.prompt.ifBlank { "Listen and type what you hear" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (prompt.answerMode == AnswerMode.AUDIO_ONLY) {
                    AudioPracticeButton(onClick = onSpeak)
                }
                reviewContext(prompt)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (prompt.answerMode == AnswerMode.CHOICE) {
            if (!state.revealed) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    prompt.choices.forEachIndexed { index, choice ->
                        ChoiceAnswerButton(choice = choice, index = index, onClick = { onChoice(choice) })
                    }
                }
            }
        } else if (!state.revealed) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (prompt.answerMode == AnswerMode.SPEAK) {
                        SpeakingAnswerInput(
                            cardId = prompt.card.id,
                            recognized = state.typedAnswer,
                            onRecognized = onAnswerChanged
                        )
                    } else {
                        if (supportsTiles) {
                            InputModeToggle(
                                keyboardMode = keyboardMode,
                                onKeyboard = {
                                    if (!keyboardMode) {
                                        keyboardMode = true
                                        onAnswerChanged("")
                                    }
                                },
                                onTiles = {
                                    if (keyboardMode) {
                                        keyboardMode = false
                                        onAnswerChanged("")
                                    }
                                }
                            )
                        } else {
                            Text(
                                "Type your answer. Audio plays automatically when the card appears.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AnimatedContent(
                            targetState = keyboardMode,
                            transitionSpec = {
                                (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { if (targetState) -it / 8 else it / 8 })
                                    .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { if (targetState) it / 8 else -it / 8 })
                                    .using(SizeTransform(clip = false))
                            },
                            label = "answer-mode"
                        ) { useKeyboard ->
                            if (useKeyboard) {
                                KeyboardAnswerInput(
                                    value = state.typedAnswer,
                                    prompt = prompt,
                                    onChange = onAnswerChanged,
                                    onDone = onReveal
                                )
                            } else {
                                LetterTileBank(
                                    expected = prompt.expectedAnswer,
                                    cardId = prompt.card.id,
                                    hint = answerHint(prompt),
                                    onChange = onAnswerChanged
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        AnimatedVisibility(
            visible = !state.revealed && prompt.answerMode != AnswerMode.CHOICE,
            enter = fadeIn(tween(180)) + slideInVertically(tween(200)) { it / 8 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(140)) { it / 8 }
        ) {
            val hasAnswer = state.typedAnswer.isNotBlank()
            PrimaryPracticeButton(
                hasAnswer = hasAnswer,
                onClick = onReveal,
                modifier = Modifier.fillMaxWidth()
            )
        }
        AnimatedVisibility(
            visible = state.revealed,
            enter = fadeIn(tween(200)) + slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 6 },
            exit = fadeOut(tween(120))
        ) {
            RevealPanel(state, prompt, onRate, onContinue, onKnewIt, onSpeak, onCorrectionChanged, onSubmitCorrection)
        }
    }
}

@Composable
internal fun SpeakingAnswerInput(
    cardId: Long,
    recognized: String,
    onRecognized: (String) -> Unit
) {
    val context = LocalContext.current
    val recognitionAvailable = remember { RussianSpeechRecognizer.isAvailable(context) }
    val recognizer = rememberRussianSpeechRecognizer()
    var listening by rememberSaveable(cardId) { mutableStateOf(false) }
    var helperText by rememberSaveable(cardId) { mutableStateOf("Tap the mic and say the Russian aloud.") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            helperText = "Microphone ready. Tap the mic and say the Russian aloud."
        } else {
            helperText = "Microphone permission is needed for speaking practice."
        }
    }

    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        helperText = "Listening..."
        recognizer.startListening(
            onResult = { result ->
                listening = false
                if (result.isBlank()) {
                    helperText = "Nothing recognized. Try once more."
                } else {
                    onRecognized(result)
                    helperText = "Recognized. Check the answer when it looks right."
                }
            },
            onPartial = { partial ->
                if (partial.isNotBlank()) onRecognized(partial)
            },
            onError = { error ->
                listening = false
                helperText = error
            },
            onReadyForSpeech = {
                listening = true
                helperText = "Listening..."
            },
            onEndOfSpeech = {
                listening = false
                helperText = "Processing speech..."
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!recognitionAvailable) {
            Text(
                "Speech recognition is not available on this device. Type the Russian instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = recognized,
                onValueChange = onRecognized,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                singleLine = true,
                label = { Text("Russian you said") }
            )
        } else {
            Text(helperText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    if (listening) {
                        recognizer.stop()
                        listening = false
                        helperText = "Stopped. Tap the mic to try again."
                    } else {
                        startListening()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (listening) "Listening..." else "Start Mic", fontWeight = FontWeight.SemiBold)
            }
        }
        if (recognized.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Recognized", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(recognized, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun rememberRussianSpeechRecognizer(): RussianSpeechRecognizer {
    val context = LocalContext.current
    val recognizer = remember { RussianSpeechRecognizer(context) }
    DisposableEffect(recognizer) {
        onDispose { recognizer.shutdown() }
    }
    return recognizer
}

@Composable
internal fun LessonCard(
    lesson: com.sibirskyspeak.review.LessonContent,
    onSpeak: () -> Unit,
    onGotIt: () -> Unit,
    ratingInProgress: Boolean
) {
    SectionCard(emphasis = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            StatusTag("New grammar")
        }
        Spacer(Modifier.height(10.dp))
        Text(lesson.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(lesson.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (lesson.exampleRu.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(lesson.exampleRu, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        AssistChip(
                            onClick = onSpeak,
                            label = { Text("Hear") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                    if (lesson.exampleEn.isNotBlank()) {
                        Text(lesson.exampleEn, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onGotIt,
            enabled = !ratingInProgress,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)
        ) {
            Text(if (ratingInProgress) "Saving..." else "Got it", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun AudioPracticeButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "audio-button-scale"
    )
    Button(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Play Russian", fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun ChoiceAnswerButton(choice: String, index: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "choice-scale"
    )
    OutlinedButton(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (pressed) 0.7f else 0.35f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                shape = RoundedCornerShape(99.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    ('A' + index).toString(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(choice, modifier = Modifier.weight(1f), textAlign = TextAlign.Start, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun PrimaryPracticeButton(
    hasAnswer: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "primary-practice-scale"
    )
    val container by animateColorAsState(
        targetValue = if (hasAnswer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        animationSpec = tween(220),
        label = "primary-practice-container"
    )
    val content by animateColorAsState(
        targetValue = if (hasAnswer) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        animationSpec = tween(220),
        label = "primary-practice-content"
    )
    Button(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier.scale(scale),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        AnimatedContent(
            targetState = hasAnswer,
            transitionSpec = {
                (fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.92f))
                    .togetherWith(fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.92f))
            },
            label = "primary-practice-label"
        ) { ready ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(if (ready) Icons.Filled.CheckCircle else Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (ready) "Check Answer" else "Show Answer", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun InputModeToggle(
    keyboardMode: Boolean,
    onKeyboard: () -> Unit,
    onTiles: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InputModeButton(
            label = "Tiles",
            selected = !keyboardMode,
            modifier = Modifier.weight(1f),
            onClick = onTiles
        )
        InputModeButton(
            label = "Keyboard",
            selected = keyboardMode,
            modifier = Modifier.weight(1f),
            onClick = onKeyboard
        )
    }
}

@Composable
internal fun InputModeButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "input-mode-scale"
    )
    if (selected) {
        Button(
            onClick = onClick,
            interactionSource = interaction,
            modifier = modifier.scale(scale),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp, horizontal = 8.dp)
        ) {
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            interactionSource = interaction,
            modifier = modifier.scale(scale),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.36f)),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp, horizontal = 8.dp)
        ) {
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun KeyboardAnswerInput(
    value: String,
    prompt: ReviewPrompt,
    onChange: (String) -> Unit,
    onDone: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        label = { Text(answerHint(prompt)) },
        keyboardOptions = KeyboardOptions(
            capitalization = if (prompt.answerMode == AnswerMode.ENGLISH) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboard?.hide()
                onDone()
            }
        )
    )
}

@Composable
internal fun RevealPanel(
    state: ReviewUiState,
    prompt: ReviewPrompt,
    onRate: (Rating) -> Unit,
    onContinue: () -> Unit,
    onKnewIt: () -> Unit,
    onSpeak: () -> Unit,
    onCorrectionChanged: (String) -> Unit,
    onSubmitCorrection: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    // Reinforce correct pronunciation on reveal for the cards where prompt-side
    // auto-play was suppressed (production, cloze, choice, stress, speak) — so you
    // hear the right Russian right after you commit your answer. Recognition/listening
    // cards already played on the prompt, so don't repeat them.
    if (prompt.answerMode != AnswerMode.ENGLISH && prompt.answerMode != AnswerMode.AUDIO_ONLY) {
        LaunchedEffect(prompt.card.id) {
            delay(220)
            onSpeak()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ResultBanner(state, prompt, onSpeak)
        prompt.explanation?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        prompt.note.mnemonic?.takeIf { it.isNotBlank() }?.let { hook ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
            ) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Column {
                        Text("Memory hook", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(hook, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        // Reinforce with the word in context (and its meaning) now that the answer is in.
        reviewRevealContext(prompt)?.let { (ru, en) ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("In context", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text(ru, style = MaterialTheme.typography.bodyMedium)
                    en?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (state.autoRatedAgain) {
            StatusBanner("Miss saved as Again automatically. Override only for a typo, mis-tap, or prompt issue.")
            if (state.correctionRequired) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Correct it before moving on", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("Rebuild the expected answer once. This turns feedback into active correction.", style = MaterialTheme.typography.bodySmall)
                        CorrectionPractice(
                            state = state,
                            prompt = prompt,
                            onChange = onCorrectionChanged,
                            onSubmit = onSubmitCorrection
                        )
                        if (!state.correctionAccepted) {
                            Button(onClick = onSubmitCorrection, enabled = state.correctionAnswer.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Text("Check correction")
                            }
                        } else {
                            StatusBanner("Correction complete. This card will return after a short gap.")
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onKnewIt,
                    enabled = !state.ratingInProgress,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Override miss")
                }
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onContinue()
                    },
                    enabled = !state.ratingInProgress && (!state.correctionRequired || state.correctionAccepted),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.ratingInProgress) "Saving..." else "Next Card", fontWeight = FontWeight.SemiBold)
                }
            }
            return
        }
        // Thin separator between the revealed answer and the grading bar (Anki's
        // "answer line"), for clearer visual structure.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        )
        RatingDecisionGuide(state)
        state.suggestedRating?.let { suggested ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = suggested.accent().copy(alpha = 0.16f),
                border = BorderStroke(1.dp, suggested.accent().copy(alpha = 0.5f))
            ) {
                Text(
                    "Suggested: ${suggested.name.lowercase().replaceFirstChar { it.titlecase() }} · based on accuracy and response time",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = suggested.accent()
                )
            }
        }
        // AnkiDroid-style answer bar: all four grades in a single row across the bottom.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Rating.entries.forEach { rating ->
                RatingButton(
                    rating = rating,
                    intervalDays = prompt.intervalPreview[rating] ?: 0,
                    saving = state.ratingInProgress,
                    suggested = state.suggestedRating == rating,
                    modifier = Modifier
                        .weight(1f)
                        .height(84.dp),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRate(rating)
                    }
                )
            }
        }
    }
}

@Composable
internal fun RatingDecisionGuide(state: ReviewUiState) {
    val (label, body) = when (state.answerMatch) {
        AnswerMatch.CLOSE -> "Close answer" to "Hard is usually right if you had to think or spelling was rough. Use Good if the form was clear."
        AnswerMatch.EXACT -> "Correct answer" to "Good is normal recall. Easy is valid when it was instant—or when you genuinely knew the word before this course. Use Mark known if it needs no further vocab practice."
        AnswerMatch.WRONG -> "Missed answer" to "Again is the right grade for a miss. Use Hard only if you genuinely knew it and made a small slip."
        else -> "Recall check" to "Rate the effort you actually felt. Easy can also mean prior knowledge; Mark known retires a word you no longer need to practise."
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Filled.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun RatingButton(
    rating: Rating,
    intervalDays: Int,
    saving: Boolean,
    suggested: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "rating-button-scale"
    )
    val container by animateColorAsState(
        targetValue = rating.accent(),
        animationSpec = tween(200),
        label = "rating-button-color"
    )
    Button(
        onClick = onClick,
        enabled = !saving,
        interactionSource = interaction,
        modifier = modifier.scale(scale),
        shape = MaterialTheme.shapes.small,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = Color.White),
        border = if (suggested) BorderStroke(3.dp, Color.White) else null
    ) {
        // AnkiDroid-style: the next interval on top, with a plain-language recall cue.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                formatDays(intervalDays),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                color = Color.White.copy(alpha = 0.85f)
            )
            Text(
                if (saving) "…" else rating.name.lowercase().replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Text(
                rating.recallCaption(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                color = Color.White.copy(alpha = 0.86f)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CorrectionPractice(
    state: ReviewUiState,
    prompt: ReviewPrompt,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    if (state.correctionAccepted) return
    if (prompt.answerMode == AnswerMode.CHOICE && prompt.choices.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            prompt.choices.forEachIndexed { index, choice ->
                ChoiceAnswerButton(choice, index) {
                    onChange(choice)
                    onSubmit()
                }
            }
        }
        return
    }
    var keyboardMode by rememberSaveable(prompt.card.id, state.correctionRequired) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        InputModeToggle(
            keyboardMode = keyboardMode,
            onKeyboard = { keyboardMode = true; onChange("") },
            onTiles = { keyboardMode = false; onChange("") }
        )
        if (keyboardMode) {
            OutlinedTextField(
                value = state.correctionAnswer,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Expected answer") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() })
            )
        } else {
            LetterTileBank(
                expected = prompt.expectedAnswer,
                cardId = prompt.card.id xor 0x5F3759DFL,
                hint = "Build the correction with tiles; keyboard remains optional.",
                onChange = onChange
            )
        }
    }
}

@Composable
internal fun ResultBanner(state: ReviewUiState, prompt: ReviewPrompt, onSpeak: () -> Unit) {
    val matched = state.isAnswerCorrect == true
    val color = if (matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val title = when (state.answerMatch) {
        AnswerMatch.EXACT -> "Correct!"
        AnswerMatch.CLOSE -> "Close enough"
        else -> "Expected answer"
    }
    val pop by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "result-pop"
    )
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = pop; scaleY = pop },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = color.copy(alpha = 0.12f)),
        border = BorderStroke(1.5.dp, color.copy(alpha = 0.55f))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (matched) Icons.Filled.CheckCircle else Icons.Filled.School,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(28.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(prompt.expectedAnswer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (state.typedAnswer.isNotBlank()) {
                            Text("You answered: ${state.typedAnswer}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.answerFeedback?.let {
                    AnswerFeedbackPanel(
                        feedback = it,
                        matched = matched
                    )
                }
            }
            IconButton(onClick = onSpeak) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear answer", tint = color)
            }
        }
    }
}

@Composable
internal fun AnswerFeedbackPanel(feedback: String, matched: Boolean) {
    val accent = if (matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                if (matched) Icons.Filled.Insights else Icons.Filled.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (matched) "Adjustment" else "Why this missed",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    feedback,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun ReviewPrompt.speechText(): String =
    when (answerMode) {
        AnswerMode.ENGLISH -> note.russian
        AnswerMode.AUDIO_ONLY -> expectedAnswer
        AnswerMode.SPEAK -> expectedAnswer
        AnswerMode.RUSSIAN_STRESS_TYPED -> expectedAnswer
        AnswerMode.RUSSIAN_TYPED -> listOfNotNull(
            exampleSentence,
            prompt.russianLinesForSpeech(),
            expectedAnswer
        ).firstOrNull { it.hasRussianTextForSpeech() } ?: expectedAnswer
        AnswerMode.CHOICE -> listOfNotNull(
            prompt.russianLinesForSpeech(),
            expectedAnswer
        ).firstOrNull { it.hasRussianTextForSpeech() }
            ?: expectedAnswer
        AnswerMode.LESSON -> lesson?.exampleRu?.takeIf { it.isNotBlank() } ?: note.russian
    }

internal fun String.hasRussianTextForSpeech(): Boolean =
    Regex("""\p{IsCyrillic}+""").containsMatchIn(this)

internal fun String.russianLinesForSpeech(): String? {
    val cyrillic = Regex("""\p{IsCyrillic}+""")
    return lineSequence()
        .map { it.trim() }
        .filter { cyrillic.containsMatchIn(it) }
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
}

