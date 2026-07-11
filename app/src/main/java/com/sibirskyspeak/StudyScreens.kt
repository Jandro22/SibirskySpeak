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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import kotlinx.coroutines.flow.StateFlow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.sibirskyspeak.audio.RussianSpeechRecognizer
import com.sibirskyspeak.audio.AnswerSoundEffects
import com.sibirskyspeak.data.Note
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.GamificationStats
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.AnswerMatch
import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.review.ReviewUiState
import com.sibirskyspeak.review.isNewVocabularyIntroduction
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Study session
// ---------------------------------------------------------------------------

@Composable
internal fun StudySessionScreen(
    state: ReviewUiState,
    typedAnswer: StateFlow<String>,
    correctionAnswer: StateFlow<String>,
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
    onReadNext: () -> Unit = {}
) {
    // Cold start / process-death restoration can compose this screen before the async
    // startup plan lands (state.sessionPlan == null) — e.g. Dest.Study survives via
    // NavState's rememberSaveable while ReviewViewModel's state does not. Calling
    // onStartSession() then would silently start an empty session (no reviewQueue to
    // pull from) instead of resuming, since startStudySession has nothing to build a
    // queue from yet. Keying on plan-readiness makes the effect wait and re-fire once
    // the plan lands, rather than firing exactly once against unready state.
    //
    // This is ALSO how a plain nav.push(Dest.Study) from the Dashboard starts a
    // session at all (see MainActivity: that onStart doesn't call startStudySession
    // itself). But answering the last card of a session also flips inStudySession
    // back to false as ordinary end-of-sitting bookkeeping (LearningRepository ends
    // the match, ReviewViewModel.loadSession sets inStudySession = false) — while
    // this same composable instance is still mounted showing the completion screen.
    // Without a per-mount guard, that transition re-satisfies this exact condition
    // and silently launches a brand new session/lesson with zero learner input: the
    // "session finishes, then instantly jumps into another grammar lesson" bug.
    // Firing at most once per mount preserves both the cold-start and Dashboard-tap
    // cases (first satisfying transition after this screen appears) without
    // re-arming every time a later session in the same sitting completes.
    var autoStartAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(state.inStudySession, state.sessionPlan != null) {
        if (!autoStartAttempted && !state.inStudySession && state.sessionPlan != null) {
            autoStartAttempted = true
            onStartSession()
        }
    }
    // Hoisted here (once per session) rather than inside ReviewContent: earcon
    // synthesis is ~26k sin/exp sample computations, and ReviewContent is re-entered
    // fresh by AnimatedContent for every new card — recreating it per-card meant
    // resynthesizing the same two constant waveforms from scratch on every review.
    val answerSounds = remember { AnswerSoundEffects() }
    DisposableEffect(answerSounds) { onDispose { answerSounds.release() } }
    var editing by remember { mutableStateOf(false) }
    var retireAction by remember { mutableStateOf<ReviewRetireAction?>(null) }
    var actionsExpanded by remember { mutableStateOf(false) }
    val prompt = state.prompt
    val headerMessage = when {
        prompt == null && state.sessionStoppedEarly ->
            "Adaptive stop: ${state.stoppedQueueRemaining} prompts were deferred to protect retention and tomorrow's workload."
        prompt == null -> state.sessionPlan?.completion?.message ?: "Session complete."
        state.revealed -> "Check the answer, then rate your recall."
        prompt.answerMode == AnswerMode.AUDIO_ONLY -> "Listen once from memory; replay only if you need it."
        prompt.answerMode == AnswerMode.LESSON -> "Study this item, hear it, then continue when it feels familiar."
        prompt.card.queue.name == "GRAMMAR" -> "Answer from context; feedback will explain the rule after you check."
        else -> "Recall the answer, then check it and rate how easily it came back."
    }
    // The card's own content (prompt, explanation, context panels) can grow tall
    // enough to push its primary action below the fold — the learner had to
    // scroll down after every single card just to reach "Got it" or the rating
    // row. Splitting into a scrollable content pane plus a fixed footer
    // (StudyActionBar) keeps that action reachable without scrolling, while the
    // readable content above it still scrolls normally. Requires a bounded-height
    // parent (MainActivity gives `studyActive` its own branch for this, the same
    // way it already does for the reader's LazyColumn).
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                tonalElevation = 1.dp
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Practice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            PracticeStageChip(
                                when {
                                    prompt == null && state.sessionStoppedEarly -> "Stopped"
                                    prompt == null -> "Done"
                                    state.revealed -> "Rate"
                                    prompt.answerMode == AnswerMode.LESSON -> "Learn"
                                    else -> "Answer"
                                }
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (prompt != null) {
                                Box {
                                    IconButton(
                                        onClick = { actionsExpanded = true },
                                        enabled = !state.ratingInProgress,
                                        modifier = Modifier.testTag(TestTags.SESSION_MORE_MENU)
                                    ) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "More card actions")
                                    }
                                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Fix card content") },
                                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                            onClick = { actionsExpanded = false; editing = true }
                                        )
                                        if (prompt.card.queue.name == "VOCAB" && !prompt.isNewVocabularyIntroduction()) {
                                            DropdownMenuItem(
                                                text = { Text("Mark word known") },
                                                leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = null) },
                                                onClick = { actionsExpanded = false; retireAction = ReviewRetireAction.MARK_KNOWN }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Suspend this card") },
                                            leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                                            onClick = { actionsExpanded = false; retireAction = ReviewRetireAction.SUSPEND_CARD }
                                        )
                                    }
                                }
                            }
                            if (state.canUndo) {
                                IconButton(
                                    onClick = onUndo,
                                    enabled = !state.ratingInProgress,
                                    modifier = Modifier.testTag(TestTags.SESSION_UNDO)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo last review")
                                }
                            }
                            // A door, not an X: leaving mid-sitting is a positive, explicit
                            // choice ("I'm done for today") available any time, not an
                            // interruption to dismiss — see Part 3 of the pacing rework.
                            IconButton(onClick = onExit, modifier = Modifier.testTag(TestTags.SESSION_EXIT)) {
                                Icon(Icons.Filled.MeetingRoom, contentDescription = "Finish for today")
                            }
                        }
                    }
                    Text(
                        if (prompt != null && state.sessionCompletedCards > 0) "$headerMessage · ${state.sessionCompletedCards} done" else headerMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // A rating on the last queued card forces a full (non-reused) session
            // rebuild — reader coverage, curriculum matrix, pace/world-model refit —
            // which measured 3-5s+ on-device with nothing but a static "Session
            // complete" screen to show for it (see the "Saving..." button label,
            // which is easy to miss). This makes that wait visible instead of
            // reading as a freeze; ratingInProgress stays true for the whole
            // loadSession() call in that case, see ReviewViewModel.rate.
            AnimatedVisibility(visible = state.ratingInProgress) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                        stoppedEarly = state.sessionStoppedEarly,
                        deferredPrompts = state.stoppedQueueRemaining,
                        matchReport = state.matchReport,
                        tomorrowReviews = state.dashboardStats?.dueForecast?.getOrNull(0) ?: 0,
                        tomorrowMinutes = kotlin.math.ceil((state.dashboardStats?.dueForecast?.getOrNull(0) ?: 0) * 0.35).toInt(),
                        tomorrowNewCards = state.newCardsPerDaySetting,
                        onReadNext = onReadNext
                    )
                } else {
                    ReviewContent(
                        state = state,
                        prompt = prompt,
                        answerSounds = answerSounds,
                        typedAnswerFlow = typedAnswer,
                        correctionAnswerFlow = correctionAnswer,
                        onAnswerChanged = onAnswerChanged,
                        onChoice = onChoice,
                        onReveal = onReveal,
                        onCorrectionChanged = onCorrectionChanged,
                        onSubmitCorrection = onSubmitCorrection,
                        onSpeak = { onSpeak(prompt) },
                        onKnowWord = onKnowWord
                    )
                }
            }
        }
        if (prompt != null) {
            StudyActionBar(
                state = state,
                prompt = prompt,
                onRate = onRate,
                onContinue = onContinue,
                onKnewIt = onKnewIt,
                onKnowWord = onKnowWord
            )
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
    // Only the grammar teaching concept is worth surfacing here — the card type
    // ("Vocab"/"Grammar") and answer mode ("English"/"Russian"…) are already shown on
    // the card header just below, so repeating them as chips was pure duplication.
    val concept = prompt.teachingHint?.takeIf { prompt.card.queue.name == "GRAMMAR" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Session queue: $newCount new, $learningCount learning, $reviewCount review. $reviewedToday of $dailyGoal reviewed today."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Remaining-in-queue counts, labeled so the colored numbers aren't a riddle:
        // new (blue) · learning (red) · review (green).
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            QueueCount(newCount, "new", Color(0xFF2F73D8))
            QueueCount(learningCount, "learning", Color(0xFFD2453B))
            QueueCount(reviewCount, "review", Color(0xFF2E9E5B))
        }
        Text(
            if (reviewedToday > dailyGoal) {
                "Goal met · +${reviewedToday - dailyGoal} extra"
            } else {
                "$reviewedToday of $dailyGoal today"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    concept?.let {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PracticeFocusChip(it, null)
        }
    }
}

/** A single AnkiDroid-style colored remaining-count number. */
@Composable
internal fun QueueCount(count: Int, label: String, color: Color) {
    val tint = if (count > 0) color else color.copy(alpha = 0.35f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = tint
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
    answerSounds: AnswerSoundEffects,
    typedAnswerFlow: StateFlow<String>,
    correctionAnswerFlow: StateFlow<String>,
    onAnswerChanged: (String) -> Unit,
    onChoice: (String) -> Unit,
    onReveal: () -> Unit,
    onCorrectionChanged: (String) -> Unit,
    onSubmitCorrection: () -> Unit,
    onSpeak: () -> Unit,
    onKnowWord: () -> Unit
) {
    LaunchedEffect(state.feedbackSequence) {
        if (state.feedbackSequence > 0) state.feedbackCorrect?.let(answerSounds::play)
    }
    // Only auto-play audio when hearing the Russian can't give the answer away:
    // listening cards (the audio *is* the prompt) and recognition cards (the Russian
    // word is already shown). For production, cloze, stress, and choice cards, auto-
    // play would speak the very answer the learner is meant to recall — so it's off;
    // they can still tap "Hear Russian" any time (and after reveal).
    if (prompt.answerMode == AnswerMode.AUDIO_ONLY || prompt.answerMode == AnswerMode.ENGLISH ||
        prompt.isNewVocabularyIntroduction()
    ) {
        AutoPlayCardAudio(cardId = prompt.card.id, onSpeak = onSpeak)
    }
    // A lesson is a teaching screen, not a quiz: render it on its own and bail out
    // of the normal answer/reveal flow.
    prompt.lesson?.let { lesson ->
        LessonCard(
            lesson = lesson,
            isVocabIntro = prompt.isNewVocabularyIntroduction(),
            onSpeak = onSpeak
        )
        return
    }

    // The typed answer is collected here (not read from ReviewUiState) so a keystroke
    // only recomposes this quiz card, not the whole screen — see ReviewViewModel.
    val typedAnswer by typedAnswerFlow.collectAsStateWithLifecycle()
    val correctionAnswer by correctionAnswerFlow.collectAsStateWithLifecycle()
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
                    // The reason phrases ("Warm-up: a secure scheduled review") already
                    // carry their own colon, so frame with an em dash to avoid "card: …:".
                    "Why this card — $reason",
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
                // First-attempt cue for a card type you haven't been drilled on yet.
                // The genuine first-contact "new word" intro is the separate LESSON
                // card; by design productive facets (audio/typing/cloze) are deferred
                // until recognition is stable, so when one first appears the *word* is
                // already familiar — only the format is new. Saying "new word" here
                // would be false and make a fair card feel like a failure.
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
                            Text("New exercise for this word — try it, then reveal to learn it.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                    // Skip-ahead for words you already know: retires this word's vocab
                    // practice immediately instead of spending review slots learning it.
                    TextButton(
                        onClick = onKnowWord,
                        enabled = !state.ratingInProgress,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("I already know this word", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Text(
                    prompt.prompt.ifBlank {
                        if (prompt.card.cardType == CardType.AUDIO_TO_RU) "Word dictation: type what you hear"
                        else "Sentence dictation: type what you hear"
                    },
                    style = RussianDisplay
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
                        ChoiceAnswerButton(
                            choice = prompt.choiceLabels[choice] ?: choice,
                            index = index,
                            onClick = { onChoice(choice) }
                        )
                    }
                }
            }
        } else if (!state.revealed) {
            Surface(
                modifier = Modifier.fillMaxWidth().imePadding(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (prompt.answerMode == AnswerMode.SPEAK) {
                        SpeakingAnswerInput(
                            cardId = prompt.card.id,
                            recognized = typedAnswer,
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
                            // Only recognition (ENGLISH) cards reaching this non-tile path
                            // actually auto-play audio; promising it on stress/other typed
                            // cards (which deliberately don't auto-play the answer) is false.
                            Text(
                                if (prompt.answerMode == AnswerMode.ENGLISH)
                                    "Type your answer. Audio plays automatically when the card appears."
                                else
                                    "Type your answer.",
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
                                    value = typedAnswer,
                                    prompt = prompt,
                                    onChange = onAnswerChanged,
                                    // The keyboard's Done key should never turn an
                                    // accidental empty submission into an FSRS lapse.
                                    onDone = { if (typedAnswer.isNotBlank()) onReveal() }
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
            val hasAnswer = typedAnswer.isNotBlank()
            PrimaryPracticeButton(
                hasAnswer = hasAnswer,
                blankMeansMiss = prompt.card.cardType in setOf(
                    CardType.MEANING_TO_RU,
                    CardType.CLOZE,
                    CardType.CASE_FILL,
                    CardType.ADJ_AGREE,
                    CardType.VERB_FORM,
                    CardType.DICTATION,
                    CardType.SENTENCE_BUILD
                ),
                onClick = onReveal,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.ANSWER_SHOW)
            )
        }
        AnimatedVisibility(
            visible = state.revealed,
            enter = fadeIn(tween(200)) + slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 6 },
            exit = fadeOut(tween(120))
        ) {
            RevealPanel(state, prompt, typedAnswer, correctionAnswer, onSpeak, onCorrectionChanged, onSubmitCorrection)
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
    // Grading itself stays self-rated (Hard/Good/Easy) — this is purely a quality hint
    // next to the transcript, not fed into evaluation, since ASR confidence reflects
    // the recognizer's certainty about the transcript, not pronunciation accuracy.
    var recognizedConfidence by rememberSaveable(cardId) { mutableStateOf<Float?>(null) }
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
        recognizedConfidence = null
        recognizer.startListening(
            onResult = { result, confidence ->
                listening = false
                if (result.isBlank()) {
                    recognizedConfidence = null
                    helperText = "Nothing recognized. Try once more."
                } else {
                    onRecognized(result)
                    recognizedConfidence = confidence
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recognized", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        // Low confidence means the recognizer itself wasn't sure of the
                        // transcript — a hint to double-check, not a pronunciation score.
                        if ((recognizedConfidence ?: 1f) < 0.5f) {
                            Text(
                                "· uncertain",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
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
    // The same teaching layout introduces both grammar concepts and brand-new
    // vocabulary, so the chip must name the right thing — calling a vocab card a
    // "Grammar lesson" is just wrong and undermines trust in the labeling.
    isVocabIntro: Boolean = false
) {
    SectionCard(emphasis = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            StatusTag(if (isVocabIntro) "New vocabulary" else "Grammar lesson")
        }
        Spacer(Modifier.height(10.dp))
        Text(lesson.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (isVocabIntro) {
            Spacer(Modifier.height(8.dp))
            AssistChip(
                onClick = onSpeak,
                label = { Text("Hear word") },
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            lesson.body.forEach { paragraph ->
                Text(paragraph, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
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
                        Text(
                            lesson.exampleRu,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
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
        // "Got it" / "I already know this word" now live in StudyActionBar, pinned
        // below the scrollable content instead of at the end of a lesson body that
        // could be long enough to push them off-screen.
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    blankMeansMiss: Boolean = false
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
                Text(
                    if (ready) "Check Answer" else if (blankMeansMiss) "I don't know — show answer" else "Show Answer",
                    fontWeight = FontWeight.SemiBold
                )
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
        modifier = Modifier.fillMaxWidth().testTag(TestTags.ANSWER_INPUT_FIELD),
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
    typedAnswer: String,
    correctionAnswer: String,
    onSpeak: () -> Unit,
    onCorrectionChanged: (String) -> Unit,
    onSubmitCorrection: () -> Unit
) {
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
        ResultBanner(state, prompt, typedAnswer, onSpeak)
        prompt.explanation?.let {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Medium
                )
            }
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
                            correctionAnswer = correctionAnswer,
                            onChange = onCorrectionChanged,
                            onSubmit = onSubmitCorrection
                        )
                        if (!state.correctionAccepted) {
                            Button(
                                onClick = onSubmitCorrection,
                                enabled = correctionAnswer.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().testTag(TestTags.CORRECTION_CHECK)
                            ) {
                                Text("Check correction")
                            }
                        } else {
                            StatusBanner("Correction complete. This card will return after a short gap.")
                        }
                    }
                }
            }
        } else if (prompt.practiceOnly || prompt.supportOnly) {
            StatusBanner("Quick learning check — this choice does not change the card's long-term interval.")
        } else {
            // Thin separator between the revealed answer and the grading bar (Anki's
            // "answer line"), for clearer visual structure. The grading bar itself now
            // lives in StudyActionBar, pinned below the scrollable content.
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
        }
    }
}

/**
 * The one action the learner needs next — pinned at a fixed position below the
 * scrollable card content (see [StudySessionScreen]) instead of living at the end
 * of it. Long prompts, explanations, and context panels used to push "Got it" and
 * the rating row below the fold, forcing a scroll after every single card just to
 * answer it. This bar is always in view, and shows exactly one of: a lesson's
 * "Got it", the correction flow's "Next Card", the practice-only "Got it"/"Try
 * again", or the standard four-grade rating row — whichever the current card and
 * reveal state call for. Nothing is shown before reveal: the Reveal/Check button
 * stays inline, since reading the prompt first is the point.
 */
@Composable
internal fun StudyActionBar(
    state: ReviewUiState,
    prompt: ReviewPrompt,
    onRate: (Rating) -> Unit,
    onContinue: () -> Unit,
    onKnewIt: () -> Unit,
    onKnowWord: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    // A translucent, slightly elevated surface (rather than the plain background)
    // gives the pinned bar a visible edge against the scrollable content above it
    // — both a deliberate bit of Material depth/transparency, and a clearer touch
    // boundary so a tap that starts just above the bar reads as "still in the
    // scrollable content" instead of ambiguously landing on both.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                prompt.lesson != null -> {
                    Button(
                        onClick = { onRate(Rating.GOOD) },
                        enabled = !state.ratingInProgress,
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.LESSON_GOT_IT),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)
                    ) {
                        Text(if (state.ratingInProgress) "Saving..." else "Got it", fontWeight = FontWeight.SemiBold)
                    }
                    if (prompt.isNewVocabularyIntroduction()) {
                        TextButton(
                            onClick = onKnowWord,
                            enabled = !state.ratingInProgress,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("I already know this word")
                        }
                    }
                }
                !state.revealed -> Unit
                state.autoRatedAgain -> {
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
                            modifier = Modifier.weight(1f).testTag(TestTags.CORRECTION_NEXT_CARD)
                        ) {
                            Text(if (state.ratingInProgress) "Saving..." else "Next Card", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                prompt.practiceOnly || prompt.supportOnly -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onRate(Rating.AGAIN) },
                            enabled = !state.ratingInProgress,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Try again")
                        }
                        Button(
                            onClick = { onRate(Rating.GOOD) },
                            enabled = !state.ratingInProgress,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (state.ratingInProgress) "Saving…" else "Got it", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                else -> {
                    // AnkiDroid-style answer bar: all four grades in a single row.
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Rating.entries.forEach { rating ->
                            RatingButton(
                                rating = rating,
                                intervalDays = prompt.intervalPreview[rating] ?: 0,
                                saving = state.ratingInProgress,
                                suggested = state.suggestedRating == rating,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(84.dp)
                                    .testTag(
                                        when (rating) {
                                            Rating.AGAIN -> TestTags.RATE_AGAIN
                                            Rating.HARD -> TestTags.RATE_HARD
                                            Rating.GOOD -> TestTags.RATE_GOOD
                                            Rating.EASY -> TestTags.RATE_EASY
                                        }
                                    ),
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRate(rating)
                                }
                            )
                        }
                    }
                }
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
    correctionAnswer: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    if (state.correctionAccepted) return
    if (prompt.answerMode == AnswerMode.CHOICE && prompt.choices.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            prompt.choices.forEachIndexed { index, choice ->
                ChoiceAnswerButton(prompt.choiceLabels[choice] ?: choice, index) {
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
                value = correctionAnswer,
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
internal fun ResultBanner(state: ReviewUiState, prompt: ReviewPrompt, typedAnswer: String, onSpeak: () -> Unit) {
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
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        if (typedAnswer.isNotBlank()) {
                            Text("You answered: $typedAnswer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            exampleSentence,
            prompt.russianLinesForSpeech(),
            expectedAnswer
        ).firstOrNull { it.hasRussianTextForSpeech() }
            ?: expectedAnswer
        AnswerMode.LESSON -> if (isNewVocabularyIntroduction()) {
            note.russian
        } else {
            lesson?.exampleRu?.takeIf { it.isNotBlank() } ?: note.russian
        }
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
