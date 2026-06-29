package com.sibirskyspeak

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sibirskyspeak.data.ReaderRecommendation
import com.sibirskyspeak.data.ReaderStatus
import com.sibirskyspeak.data.ReaderToken
import com.sibirskyspeak.data.WordStatus
import com.sibirskyspeak.review.ReviewUiState

// ---------------------------------------------------------------------------
// Reader
// ---------------------------------------------------------------------------

@Composable
internal fun ReaderPanel(
    state: ReviewUiState,
    onLookup: (String) -> Unit,
    onOpen: (Long) -> Unit,
    onClose: () -> Unit,
    onMarkVisible: (List<String>, WordStatus) -> Unit,
    onProgress: (Int) -> Unit,
    onCheckpointAnswer: (String) -> Unit,
    onAddText: () -> Unit,
    onSpeakRussian: (String) -> Unit
) {
    val selected = state.selectedReaderTextId?.let { id -> state.allReaderTexts.firstOrNull { it.text.id == id } }
    if (selected == null) {
        ReaderBookshelf(state, onOpen, onAddText, onSpeakRussian)
    } else {
        ReaderTextScreen(state, selected, onLookup, onClose, onMarkVisible, onProgress, onSpeakRussian, onCheckpointAnswer)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderBookshelf(
    state: ReviewUiState,
    onOpen: (Long) -> Unit,
    onAddText: () -> Unit,
    onSpeakRussian: (String) -> Unit
) {
    val texts = state.allReaderTexts.sortedWith(compareByDescending<ReaderRecommendation> { it.coverage }.thenBy { it.text.title })
    val recommended = state.readerRecommendation
    var shelfQuery by rememberSaveable { mutableStateOf("") }
    val matchingTexts = texts
        .asSequence()
        .filter { shelfQuery.isBlank() || it.text.title.contains(shelfQuery, ignoreCase = true) }
        .filterNot { it.text.id == recommended?.text?.id }
        .take(24)
        .toList()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Filled.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Bookshelf", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Pick a text by coverage, length, and difficulty fit.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onAddText) {
                    Icon(Icons.Filled.Add, contentDescription = "Add reader text")
                }
            }
            if (recommended != null) {
                Spacer(Modifier.height(14.dp))
                ReaderRecommendationCard(recommended, onOpen, onSpeakRussian)
            }
        }
        if (texts.isEmpty()) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Filled.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No reader texts yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Add a Russian text, then come back here for tap-to-learn reading and audio preview.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = onAddText, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add Reader Text")
                }
            }
        } else {
            OutlinedTextField(
                value = shelfQuery,
                onValueChange = { shelfQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Find a text") },
                trailingIcon = {
                    if (shelfQuery.isNotBlank()) {
                        IconButton(onClick = { shelfQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                }
            )
            // A real shelf: each text is a tappable book standing on the boards.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    matchingTexts.forEachIndexed { index, item ->
                        BookCover(
                            item = item,
                            index = index,
                            recommended = item.text.id == state.readerRecommendation?.text?.id,
                            progressIndex = state.readerProgressByText[item.text.id] ?: -1,
                            onOpen = onOpen
                        )
                    }
                }
            }
            Text(
                if (shelfQuery.isBlank()) "Showing the 24 best-fit texts. Search to find another."
                else "${matchingTexts.size} matching ${if (matchingTexts.size == 1) "text" else "texts"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun ReaderRecommendationCard(item: ReaderRecommendation, onOpen: (Long) -> Unit, onSpeakRussian: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(item.text.id) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CoverageRing(item.coverage, Modifier.size(52.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Best next read", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    ReaderStatusChip(item.status)
                }
                Text(item.text.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatCount(item.knownTokens)} / ${formatCount(item.totalTokens)} familiar tokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Chosen for the 93-96% coverage sweet spot and unfinished progress.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onSpeakRussian(item.text.body) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Preview recommended text")
            }
        }
    }
}

internal val BookPalette = listOf(
    Color(0xFF6B4E8E),
    Color(0xFF2E6E5B),
    Color(0xFFB5523B),
    Color(0xFF356C9E),
    Color(0xFF8A6D2F),
    Color(0xFF7A3B5E),
    Color(0xFF3F6B3A),
    Color(0xFF9E5B2E)
)

@Composable
internal fun BookCover(
    item: ReaderRecommendation,
    index: Int,
    recommended: Boolean,
    progressIndex: Int,
    onOpen: (Long) -> Unit
) {
    val base = BookPalette[index % BookPalette.size]
    val deep = lerp(base, Color.Black, 0.28f)
    val spine = lerp(base, Color.Black, 0.5f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "book-scale")
    val pct = (item.coverage * 100).toInt()
    val statusLabel = when (item.status) {
        ReaderStatus.TOO_HARD -> "Hard"
        ReaderStatus.PRODUCTIVE -> "Good fit"
        ReaderStatus.EASY -> "Easy"
    }
    val reached = (progressIndex + 1).coerceIn(0, item.totalTokens)
    val progressLabel = when {
        reached == 0 -> "Not started"
        reached >= item.totalTokens -> "Finished"
        else -> "In progress ${((reached.toFloat() / item.totalTokens.coerceAtLeast(1)) * 100).toInt()}%"
    }
    Box(
        modifier = Modifier
            .width(152.dp)
            .height(198.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 13.dp, bottomEnd = 13.dp))
            .background(Brush.linearGradient(listOf(base, deep)))
            .clickable(interactionSource = interaction, indication = null) { onOpen(item.text.id) }
    ) {
        // Book spine down the left edge.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(11.dp)
                .align(Alignment.CenterStart)
                .background(spine)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 22.dp, top = 16.dp, end = 12.dp, bottom = 12.dp)
        ) {
            Text(
                item.text.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 22.sp
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatCount(item.totalTokens),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.22f))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        "$pct% known",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(progressLabel, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.92f), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.82f), fontWeight = FontWeight.SemiBold)
        }
        if (recommended) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.24f))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text("Next", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

// Words per virtualized LazyColumn item in the reader's word flow. Small enough
// that each chunk's FlowRow lays out fast; large enough to keep the item count
// (and thus scroll-state bookkeeping) modest for a long text.
internal const val READER_WORD_CHUNK_SIZE = 40

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderTextScreen(
    state: ReviewUiState,
    selected: ReaderRecommendation,
    onLookup: (String) -> Unit,
    onClose: () -> Unit,
    onMarkVisible: (List<String>, WordStatus) -> Unit,
    onProgress: (Int) -> Unit,
    onSpeakRussian: (String) -> Unit,
    onCheckpointAnswer: (String) -> Unit
) {
    val tokenCount = state.readerTokens.size.coerceAtLeast(1)
    val reachedCount = (state.readerProgressIndex + 1).coerceIn(0, state.readerTokens.size)
    val progress = (reachedCount.toFloat() / tokenCount).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "reader-progress"
    )
    val coveredCount = state.readerTokens.count { it.status == WordStatus.KNOWN || it.status == WordStatus.IGNORED }
    val newTokens = state.readerTokens
        .filter { it.status == WordStatus.NEW }
        .distinctBy { it.normalized }
        .map { it.surface }
    val reachedNewTokens = state.readerTokens
        .mapIndexedNotNull { index, token -> token.takeIf { index <= state.readerProgressIndex && it.status == WordStatus.NEW } }
        .distinctBy { it.normalized }
        .map { it.surface }
    var confirmKnownBatch by remember(selected.text.id, newTokens.size) { mutableStateOf(false) }
    // Sentence-by-sentence "Listen" mode: its own TTS engine so it doesn't clash with
    // the tap-a-word pronunciation, with karaoke-style current-sentence highlighting.
    val readerTts = rememberRussianTts()
    val sentences = remember(selected.text.id) { splitIntoSentences(selected.text.body) }
    var playingSentence by remember(selected.text.id) { mutableStateOf(-1) }
    var isPlaying by remember(selected.text.id) { mutableStateOf(false) }
    LaunchedEffect(selected.text.id) {
        readerTts.stopSpeaking(); isPlaying = false; playingSentence = -1
    }
    // Chunking keeps each LazyColumn item's FlowRow small and stable, so only
    // chunks near the viewport (plus a little overscan) ever get composed/laid
    // out — long texts no longer force every word in the story into one pass.
    val tokenChunks = remember(state.readerTokens) { state.readerTokens.withIndex().chunked(READER_WORD_CHUNK_SIZE) }
    val readingListState = rememberLazyListState()
    LaunchedEffect(selected.text.id) {
        if (state.readerProgressIndex > 0) {
            readingListState.scrollToItem(
                (state.readerProgressIndex / READER_WORD_CHUNK_SIZE)
                    .coerceAtMost(tokenChunks.lastIndex.coerceAtLeast(0))
            )
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(selected.text.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ReaderStatusChip(selected.status)
                        }
                        Text(
                            "${(selected.coverage * 100).toInt()}% coverage · ${formatCount(reachedCount)} reached · ${formatCount(coveredCount)} covered" +
                                if (newTokens.isNotEmpty()) " · ${formatCount(newTokens.size)} new" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            if (isPlaying) {
                                readerTts.stopSpeaking(); isPlaying = false; playingSentence = -1
                            } else {
                                isPlaying = true; playingSentence = -1
                                readerTts.speakSentences(
                                    sentences,
                                    onSentenceStart = { playingSentence = it },
                                    onDone = { isPlaying = false; playingSentence = -1 }
                                )
                            }
                        }) {
                            Icon(
                                if (isPlaying) Icons.Filled.Close else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isPlaying) "Stop reading" else "Listen to the text"
                            )
                        }
                        TextButton(onClick = onClose) {
                            Text(if (state.inSessionReading) "Postpone" else "Close")
                        }
                    }
                }
                if (isPlaying && playingSentence in sentences.indices) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            sentences[playingSentence],
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
        // Paper-like reading surface: words flow as real text, not buttons. The
        // surface itself takes the remaining vertical space in the screen; the
        // LazyColumn inside virtualizes by chunk so only the visible portion of
        // a long story is ever composed.
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = readingListState,
                contentPadding = PaddingValues(
                    horizontal = 18.dp,
                    vertical = 22.dp
                ),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(tokenChunks) { chunk ->
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        chunk.forEach { (index, token) ->
                            ReaderWord(
                                token = token,
                                consolidation = token.lemma in state.sessionPlan?.consolidationLemmas.orEmpty(),
                                selected = state.selectedToken?.normalized == token.normalized,
                                reached = index <= state.readerProgressIndex,
                                enabled = !state.readerLookupInProgress,
                                fontScale = state.readerFontScale,
                                onClick = {
                                    onProgress(index)
                                    onLookup(token.surface)
                                    onSpeakRussian(token.stressForm ?: token.surface)
                                }
                            )
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        WordLegend()
                        ReaderCheckpointCard(state = state, onAnswer = onCheckpointAnswer)
                        if (newTokens.isNotEmpty()) {
                            ReaderNewWordsCard(
                                newTokens = newTokens,
                                reachedNewTokens = reachedNewTokens,
                                onMarkVisible = onMarkVisible,
                                onConfirmKnown = { confirmKnownBatch = true }
                            )
                        }
                    }
                }
                // Leave room so the last lines aren't hidden behind the pinned word card.
                if (state.selectedToken != null) {
                    item { Spacer(Modifier.height(260.dp)) }
                }
            }
        }
    }
    if (confirmKnownBatch) {
        AlertDialog(
            onDismissRequest = { confirmKnownBatch = false },
            title = { Text("Mark reached known?") },
            text = {
                Text(
                    "This marks ${formatCount(reachedNewTokens.size)} reached new words as already known, counts them toward coverage, and skips practice for them. Use Mark Reached Learning if any word still needs review."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmKnownBatch = false
                    onMarkVisible(reachedNewTokens, WordStatus.KNOWN)
                }) { Text("Mark reached known") }
            },
            dismissButton = {
                TextButton(onClick = { confirmKnownBatch = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderNewWordsCard(
    newTokens: List<String>,
    reachedNewTokens: List<String>,
    onMarkVisible: (List<String>, WordStatus) -> Unit,
    onConfirmKnown: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("New words in this text", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${formatCount(newTokens.size)} unique new words. Mark only those you reached.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onMarkVisible(reachedNewTokens, WordStatus.LEARNING) },
                    enabled = reachedNewTokens.isNotEmpty()
                ) { Text("Learn reached") }
                OutlinedButton(onClick = onConfirmKnown, enabled = reachedNewTokens.isNotEmpty()) {
                    Text("Already knew")
                }
            }
        }
    }
}

@Composable
internal fun ReaderCheckpointCard(state: ReviewUiState, onAnswer: (String) -> Unit) {
    val questions = state.readerCheckpointQuestions
    if (questions.isEmpty()) return
    val unlockAt = (state.readerTokens.size * 0.6f).toInt().coerceAtLeast(1)
    val unlocked = state.readerProgressIndex + 1 >= unlockAt
    val question = questions.getOrNull(state.readerCheckpointIndex)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Text("Reading checkpoint", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            when {
                !unlocked -> Text(
                    "Read at least 60% of the text to unlock a short meaning-in-context check (${state.readerProgressIndex.plus(1).coerceAtLeast(0)}/$unlockAt words reached).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                question == null -> Text(
                    state.readerCheckpointFeedback ?: "Checkpoint complete. You retrieved these words inside connected text.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                else -> {
                    Text("${state.readerCheckpointIndex + 1} of ${questions.size}: What does “${question.russian}” mean here?", fontWeight = FontWeight.SemiBold)
                    question.choices.forEach { choice ->
                        OutlinedButton(onClick = { onAnswer(choice) }, modifier = Modifier.fillMaxWidth()) {
                            Text(choice, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                        }
                    }
                    state.readerCheckpointFeedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (question == null && state.inSessionReading) {
                Button(
                    onClick = { onAnswer(com.sibirskyspeak.review.ReviewViewModel.COMPLETE_READING) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Complete reading") }
            }
        }
    }
}

@Composable
internal fun ReaderContinueCard(
    tokens: List<ReaderToken>,
    progressIndex: Int,
    onSpeakRussian: (String) -> Unit
) {
    if (tokens.isEmpty() || progressIndex < 0 || progressIndex >= tokens.lastIndex) return
    val start = (progressIndex + 1).coerceIn(0, tokens.lastIndex)
    val preview = tokens
        .drop(start)
        .take(16)
        .joinToString(" ") { it.stressForm ?: it.surface }
        .takeIf { it.isNotBlank() }
        ?: return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Filled.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Continue Reading", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Around word ${formatCount(start + 1)} of ${formatCount(tokens.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { onSpeakRussian(preview) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear continue preview")
            }
        }
    }
}

@Composable
internal fun ReaderMetricChip(value: String, label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun WordStatus.statusHighlight(): Color = when (this) {
    WordStatus.NEW -> Color(0xFF4C8DFF)
    WordStatus.LEARNING -> Color(0xFFE0A21E)
    WordStatus.KNOWN, WordStatus.IGNORED -> Color.Transparent
}

@Composable
internal fun ReaderWord(
    token: ReaderToken,
    consolidation: Boolean,
    selected: Boolean,
    reached: Boolean,
    enabled: Boolean,
    fontScale: Float,
    onClick: () -> Unit
) {
    val accent = token.status.statusHighlight()
    val targetBackground = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        consolidation -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
        accent != Color.Transparent -> accent.copy(alpha = 0.22f)
        reached -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
        else -> Color.Transparent
    }
    val background by animateColorAsState(targetBackground, tween(220), label = "reader-word-background")
    val wordScale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "reader-word-scale"
    )
    val borderMod = if (selected || consolidation) {
        Modifier.border(
            BorderStroke(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary),
            RoundedCornerShape(5.dp)
        )
    } else {
        Modifier
    }
    Text(
        text = token.leading + token.surface + token.trailing,
        modifier = Modifier
            .scale(wordScale)
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .then(borderMod)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = (20 * fontScale).sp, lineHeight = (32 * fontScale).sp),
        color = MaterialTheme.colorScheme.onSurface
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WordLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LegendDot(WordStatus.NEW.statusHighlight(), "New")
        LegendDot(WordStatus.LEARNING.statusHighlight(), "Learning")
        LegendDot(MaterialTheme.colorScheme.onSurfaceVariant, "Known / ignored")
        LegendDot(MaterialTheme.colorScheme.tertiary, "Practised today")
    }
}

@Composable
internal fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (color == Color.Transparent) MaterialTheme.colorScheme.onSurfaceVariant else color.copy(alpha = 0.45f))
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WordDetailCard(
    token: ReaderToken,
    state: ReviewUiState,
    onSetStatus: (WordStatus) -> Unit,
    onClearSelection: () -> Unit,
    onSpeakRussian: (String) -> Unit,
    onMine: (String, String?) -> Unit = { _, _ -> }
) {
    // The sentence the learner met this word in, for sentence-mining into study.
    val readerBody = remember(state.selectedReaderTextId, state.allReaderTexts) {
        state.allReaderTexts.firstOrNull { it.text.id == state.selectedReaderTextId }?.text?.body
            ?: state.readerRecommendation?.text?.body
    }
    val miningSentence = remember(readerBody, token.surface) {
        readerBody?.let { body ->
            splitIntoSentences(body).firstOrNull { it.contains(token.surface, ignoreCase = true) }
        }
    }
    var miningTranslation by rememberSaveable(token.surface, miningSentence) { mutableStateOf("") }
    var pendingStatus by remember(token.surface) { mutableStateOf<WordStatus?>(null) }
    fun requestStatus(status: WordStatus) {
        if (status == token.status) return
        when (status) {
            WordStatus.KNOWN, WordStatus.IGNORED -> pendingStatus = status
            WordStatus.NEW, WordStatus.LEARNING -> onSetStatus(status)
        }
    }
    SectionCard(emphasis = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val spokenHeadword = token.stressForm ?: token.surface
                    Text(spokenHeadword, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { onSpeakRussian(spokenHeadword) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear word", modifier = Modifier.size(20.dp))
                    }
                    CurrentWordStatusPill(token.status)
                }
                val gloss = token.translation?.takeIf { it != "lookup pending" }
                Text(
                    gloss ?: "Not in your deck yet. Mark it to start tracking.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (gloss == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                token.parse?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onClearSelection) { Text(stringResource(R.string.action_done)) }
        }
        state.lookupResult?.takeIf { it.isNotBlank() }?.let { message ->
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (state.readerLookupInProgress) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp)))
        }
        Spacer(Modifier.height(14.dp))
        Text("Word status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Use Learning for words you want to practise. Known counts toward coverage and stops practice; Ignore hides names or noise.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WordStatusChip("New", WordStatus.NEW, token.status, ::requestStatus)
            WordStatusChip("Learning", WordStatus.LEARNING, token.status, ::requestStatus)
            WordStatusChip("Known", WordStatus.KNOWN, token.status, ::requestStatus)
            WordStatusChip("Ignore", WordStatus.IGNORED, token.status, ::requestStatus)
        }
        if (miningSentence != null && token.status != WordStatus.KNOWN && token.status != WordStatus.IGNORED) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = miningTranslation,
                onValueChange = { miningTranslation = it },
                label = { Text("Sentence meaning") },
                placeholder = { Text("Add this to create cloze recall") },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            )
            Text(
                "Without a meaning, this saves the example only; with one, it also creates context recall.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onMine(miningSentence, miningTranslation.trim().takeIf { it.isNotBlank() }) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (miningTranslation.isBlank()) "Save sentence for this word" else "Create context recall")
            }
        }
        val examples = listOf(
            token.exampleSentence to token.exampleTranslation,
            token.exampleSentence2 to token.exampleTranslation2,
            token.exampleSentence3 to token.exampleTranslation3
        ).filter { !it.first.isNullOrBlank() }

        if (examples.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val header = if (examples.size > 1) "Examples" else "Example"
                    Text(header, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    examples.forEachIndexed { idx, (ru, en) ->
                        val prefix = if (examples.size > 1) "${idx + 1}. " else ""
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(prefix + ru, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { ru?.let(onSpeakRussian) }, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear example", modifier = Modifier.size(18.dp))
                                }
                            }
                            if (!en.isNullOrBlank()) {
                                Text(en, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    pendingStatus?.let { status ->
        WordStatusConfirmDialog(
            status = status,
            token = token,
            onDismiss = { pendingStatus = null },
            onConfirm = {
                pendingStatus = null
                onSetStatus(status)
            }
        )
    }
}

@Composable
internal fun WordStatusConfirmDialog(
    status: WordStatus,
    token: ReaderToken,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val word = token.stressForm ?: token.surface
    val (title, body, confirmLabel) = when (status) {
        WordStatus.KNOWN -> Triple(
            "Mark known?",
            "This counts $word toward reader coverage and stops practice for it. Choose Learning if you still want review.",
            "Mark known"
        )
        WordStatus.IGNORED -> Triple(
            "Ignore word?",
            "This hides $word from new-word counts and practice. Use it for names, typos, or text noise.",
            "Ignore"
        )
        WordStatus.NEW, WordStatus.LEARNING -> return
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
internal fun CurrentWordStatusPill(status: WordStatus) {
    val accent = when (status) {
        WordStatus.NEW -> Color(0xFF4C8DFF)
        WordStatus.LEARNING -> Color(0xFFE0A21E)
        WordStatus.KNOWN -> Color(0xFF2E9E5B)
        WordStatus.IGNORED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val container by animateColorAsState(accent.copy(alpha = 0.14f), tween(180), label = "word-status-pill")
    Surface(shape = MaterialTheme.shapes.small, color = container) {
        Text(
            status.name.lowercase().replaceFirstChar { it.titlecase() },
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun WordStatusChip(
    label: String,
    status: WordStatus,
    current: WordStatus,
    onSetStatus: (WordStatus) -> Unit
) {
    val selected = current == status
    val accent = when (status) {
        WordStatus.NEW -> Color(0xFF4C8DFF)
        WordStatus.LEARNING -> Color(0xFFE0A21E)
        WordStatus.KNOWN -> Color(0xFF2E9E5B)
        WordStatus.IGNORED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onSetStatus(status) },
        shape = RoundedCornerShape(50),
        color = if (selected) accent else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = if (selected) 1f else 0.5f))
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else accent
        )
    }
}

