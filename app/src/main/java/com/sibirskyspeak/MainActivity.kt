package com.sibirskyspeak

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.sibirskyspeak.notify.Reminders
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.sibirskyspeak.audio.RussianTextToSpeech
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReaderRecommendation
import com.sibirskyspeak.data.Achievement
import com.sibirskyspeak.data.GamificationStats
import com.sibirskyspeak.data.ReaderStatus
import com.sibirskyspeak.data.ReaderToken
import com.sibirskyspeak.data.SettingsStore
import com.sibirskyspeak.data.WordStatus
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.AnswerMatch
import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.review.ReviewUiState
import com.sibirskyspeak.review.ReviewViewModel
import com.sibirskyspeak.review.ReviewViewModelFactory
import com.sibirskyspeak.review.SessionStep

class MainActivity : ComponentActivity() {
    private val reviewViewModel: ReviewViewModel by viewModels {
        val app = application as SibirskySpeakApp
        ReviewViewModelFactory(app.repository, app.settings)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Reminders.ensureChannel(this)
        Reminders.schedule(this)
        maybeRequestNotificationPermission()
        setContent {
            SibirskySpeakTheme {
                ReviewScreen(reviewViewModel)
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

// ---------------------------------------------------------------------------
// Theme
// ---------------------------------------------------------------------------

private val BrandLight = lightColorScheme(
    primary = Color(0xFF246D69),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB6ECE6),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDDAE),
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF7D5260),
    background = Color(0xFFF6F8F6),
    onBackground = Color(0xFF162022),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF162022),
    surfaceVariant = Color(0xFFE4ECEA),
    onSurfaceVariant = Color(0xFF536265),
    outline = Color(0xFFB8C7C5),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFF8BE0D4),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF00504C),
    onPrimaryContainer = Color(0xFFB6ECE6),
    secondary = Color(0xFFFFCA7A),
    onSecondary = Color(0xFF452B00),
    secondaryContainer = Color(0xFF624000),
    onSecondaryContainer = Color(0xFFFFDDAE),
    tertiary = Color(0xFFE8B4CB),
    background = Color(0xFF0E1417),
    onBackground = Color(0xFFE8F0F2),
    surface = Color(0xFF121A1E),
    onSurface = Color(0xFFE8F0F2),
    surfaceVariant = Color(0xFF253238),
    onSurfaceVariant = Color(0xFFB6C8CC),
    outline = Color(0xFF405259),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
private fun SibirskySpeakTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic && !dark -> dynamicLightColorScheme(context)
        dark -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}

// ---------------------------------------------------------------------------
// Scaffold + navigation
// ---------------------------------------------------------------------------

private val MainTabs = listOf(SessionStep.REVIEWS, SessionStep.READER, SessionStep.DASHBOARD, SessionStep.IMPORT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(viewModel: ReviewViewModel) {
    val state by viewModel.state.collectAsState()
    val tts = rememberRussianTts()
    val context = LocalContext.current
    var studyActive by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = studyActive) { studyActive = false }

    val activeTab = state.sessionStep.mainTab()
    // The reader word card stays pinned to the bottom while the story scrolls.
    val showWordCard = !studyActive && activeTab == SessionStep.READER && state.selectedToken != null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SibirskySpeak", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Russian review & reader",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !studyActive,
                enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140))
            ) {
                MainBottomBar(
                    selected = activeTab,
                    onSelect = { step ->
                        studyActive = false
                        viewModel.setSessionStep(step)
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val target = if (studyActive) SessionStep.RULE else activeTab
                AnimatedContent(
                    targetState = target,
                    transitionSpec = {
                        val forward = tabIndex(targetState) >= tabIndex(initialState)
                        val dir = if (forward) 1 else -1
                        (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { dir * it / 6 })
                            .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -dir * it / 8 })
                            .using(SizeTransform(clip = false))
                    },
                    label = "main-tab"
                ) { tab ->
                    when (tab) {
                        SessionStep.RULE -> StudySessionScreen(
                            state = state,
                            onAnswerChanged = viewModel::setTypedAnswer,
                            onChoice = viewModel::chooseAnswer,
                            onReveal = viewModel::reveal,
                            onRate = viewModel::rate,
                            onContinue = viewModel::continueAfterRating,
                            onSpeak = { p -> tts.speak(p.note.russian) },
                            onExit = { studyActive = false },
                            onUndo = viewModel::undoLastReview,
                            onKnewIt = viewModel::overrideKnewIt
                        )
                        SessionStep.REVIEWS -> PracticeScreen(
                            state = state,
                            onStart = { studyActive = true },
                            onRead = { viewModel.setSessionStep(SessionStep.READER) }
                        )
                        SessionStep.READER -> ReaderPanel(
                            state = state,
                            onLookup = viewModel::lookupReaderToken,
                            onOpen = viewModel::openReaderText,
                            onClose = viewModel::closeReaderText,
                            onMarkVisible = viewModel::markVisibleWords,
                            onProgress = viewModel::recordReaderProgress
                        )
                        SessionStep.DASHBOARD -> DashboardPanel(state)
                        SessionStep.IMPORT -> ImportExportPanel(
                            state = state,
                            onImportText = viewModel::setImportText,
                            onImport = viewModel::importJsonLines,
                            onExport = viewModel::exportJsonLines,
                            onFullBackup = viewModel::exportFullState,
                            onTitle = viewModel::setReaderTitle,
                            onBody = viewModel::setReaderBody,
                            onAdd = viewModel::addReaderText,
                            onDailyGoal = viewModel::setDailyGoal,
                            onSessionSize = viewModel::setSessionSize,
                            onRetention = viewModel::setRetention,
                            onReminderEnabled = { enabled ->
                                viewModel.setReminderEnabled(enabled)
                                Reminders.schedule(context)
                            },
                            onReminderHour = { hour ->
                                viewModel.setReminderHour(hour)
                                Reminders.schedule(context)
                            },
                            onFontScale = viewModel::setReaderFontScale,
                            onSearch = viewModel::setSearchQuery
                        )
                        else -> PracticeScreen(
                            state = state,
                            onStart = { studyActive = true },
                            onRead = { viewModel.setSessionStep(SessionStep.READER) }
                        )
                    }
                }
                state.statusMessage?.let { StatusBanner(it) }
                // Leave room so the story doesn't hide behind the pinned word card.
                Spacer(Modifier.height(if (showWordCard) 300.dp else 8.dp))
            }
            AnimatedVisibility(
                visible = showWordCard,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(120))
            ) {
                state.selectedToken?.let { token ->
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        WordDetailCard(
                            token = token,
                            state = state,
                            onSetStatus = viewModel::setReaderWordStatus,
                            onClearSelection = viewModel::clearSelectedToken
                        )
                    }
                }
            }
            AchievementUnlockOverlay(
                achievements = state.newlyUnlocked,
                onDismiss = viewModel::dismissNewlyUnlocked,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun AchievementUnlockOverlay(
    achievements: List<Achievement>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val achievement = achievements.firstOrNull()
    AnimatedVisibility(
        visible = achievement != null,
        modifier = modifier,
        enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { -it } + fadeIn(tween(180)),
        exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(140))
    ) {
        // Auto-dismiss after a few seconds; tapping dismisses immediately.
        achievement?.let { a ->
            LaunchedEffect(a.id) {
                kotlinx.coroutines.delay(3500)
                onDismiss()
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(34.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Achievement unlocked!", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.SemiBold)
                            Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(a.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f))
                        }
                        if (achievements.size > 1) {
                            Text("+${achievements.size - 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberRussianTts(): RussianTextToSpeech {
    val context = LocalContext.current
    val tts = remember { RussianTextToSpeech(context) }
    DisposableEffect(tts) {
        onDispose { tts.shutdown() }
    }
    return tts
}

@Composable
private fun MainBottomBar(selected: SessionStep, onSelect: (SessionStep) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        MainTabs.forEach { tab ->
            val isSelected = selected == tab
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = tab.label(),
                        modifier = Modifier.size(if (isSelected) 26.dp else 24.dp)
                    )
                },
                label = { Text(tab.label(), fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Practice home
// ---------------------------------------------------------------------------

@Composable
private fun PracticeScreen(
    state: ReviewUiState,
    onStart: () -> Unit,
    onRead: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DailyPlanPanel(state, onStart, onRead)
        ReadingSuggestion(state, onRead)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DailyPlanPanel(state: ReviewUiState, onStart: () -> Unit, onRead: () -> Unit) {
    val plan = state.dailyPlan ?: return
    val sessionSize = state.sessionPlan?.reviewQueue?.size ?: 0
    val remaining = (sessionSize - state.reviewedToday).coerceAtLeast(0)
    val backlog = plan.dueVocab + plan.dueGrammar
    val reader = state.readerRecommendation
    val progress = if (sessionSize == 0) 1f else (state.reviewedToday.toFloat() / sessionSize).coerceIn(0f, 1f)

    HeroCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ProgressRing(
                progress = progress,
                modifier = Modifier.size(108.dp),
                trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f),
                color = MaterialTheme.colorScheme.onPrimary
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text("today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Today's Plan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                Text(
                    when {
                        sessionSize > 0 -> "$remaining of $sessionSize cards left in this session."
                        reader != null -> "All caught up. Read the recommended text for fresh exposure."
                        else -> "You're caught up. Add a reader text or import notes for new material."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroPill(animatedInt(state.reviewedToday).toString(), "done today")
            HeroPill(sessionSize.toString(), "session")
            if (reader != null) HeroPill("${(reader.coverage * 100).toInt()}%", "reader fit")
        }
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onStart,
                enabled = sessionSize > 0,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimary,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (sessionSize > 0) "Start Practice" else "All Clear", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onRead,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Filled.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Read")
            }
        }
        if (backlog > sessionSize && sessionSize > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                "${formatCount(backlog)} cards wait in the backlog, but today's session is capped at $sessionSize to stay manageable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun ReadingSuggestion(state: ReviewUiState, onRead: () -> Unit) {
    val reader = state.readerRecommendation ?: return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CoverageRing(reader.coverage, Modifier.size(56.dp))
            Column(Modifier.weight(1f)) {
                Text("Up Next: Reading", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(reader.text.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${(reader.coverage * 100).toInt()}% of the words match material you've already seen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open Reader")
        }
    }
}

// ---------------------------------------------------------------------------
// Study session
// ---------------------------------------------------------------------------

@Composable
private fun StudySessionScreen(
    state: ReviewUiState,
    onAnswerChanged: (String) -> Unit,
    onChoice: (String) -> Unit,
    onReveal: () -> Unit,
    onRate: (Rating) -> Unit,
    onContinue: () -> Unit,
    onSpeak: (ReviewPrompt) -> Unit,
    onExit: () -> Unit,
    onUndo: () -> Unit,
    onKnewIt: () -> Unit
) {
    val sessionSize = state.sessionPlan?.reviewQueue?.size ?: 0
    val position = (state.reviewedToday).coerceAtMost(sessionSize)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Practice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (sessionSize > 0) "Card ${(position + 1).coerceAtMost(sessionSize)} of $sessionSize" else "Answer, check, then rate recall.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.canUndo) {
                    TextButton(onClick = onUndo, enabled = !state.ratingInProgress) { Text("Undo") }
                }
                OutlinedButton(onClick = onExit) { Text("Exit") }
            }
        }
        if (sessionSize > 0) {
            val p = (position.toFloat() / sessionSize).coerceIn(0f, 1f)
            val animated by animateFloatAsState(p, spring(stiffness = Spring.StiffnessLow), label = "session-progress")
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(99.dp))
            )
        }
        val prompt = state.prompt
        AnimatedContent(
            targetState = prompt?.card?.id ?: -1L,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(spring(stiffness = Spring.StiffnessLow), initialScale = 0.94f) + slideInHorizontally(tween(260)) { it / 4 })
                    .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -it / 5 })
                    .using(SizeTransform(clip = false))
            },
            label = "review-card"
        ) {
            if (prompt == null) {
                SessionCompleteCard(state.sessionPlan?.gamification ?: GamificationStats.EMPTY)
            } else {
                ReviewContent(
                    state = state,
                    prompt = prompt,
                    onAnswerChanged = onAnswerChanged,
                    onChoice = onChoice,
                    onReveal = onReveal,
                    onRate = onRate,
                    onContinue = onContinue,
                    onSpeak = { onSpeak(prompt) },
                    onKnewIt = onKnewIt
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReviewContent(
    state: ReviewUiState,
    prompt: ReviewPrompt,
    onAnswerChanged: (String) -> Unit,
    onChoice: (String) -> Unit,
    onReveal: () -> Unit,
    onRate: (Rating) -> Unit,
    onContinue: () -> Unit,
    onSpeak: () -> Unit,
    onKnewIt: () -> Unit
) {
    SectionCard(emphasis = true) {
        Text(reviewTaskTitle(prompt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(reviewTaskHelp(prompt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusTag(if (prompt.card.queue.name == "VOCAB") "Vocabulary" else "Grammar")
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
        Spacer(Modifier.height(18.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    prompt.prompt.ifBlank { "Listen and type what you hear" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                reviewContext(prompt)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (prompt.answerMode == AnswerMode.CHOICE) {
            if (!state.revealed) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    prompt.choices.forEach { choice ->
                        Button(onClick = { onChoice(choice) }) { Text(choice) }
                    }
                }
            }
        } else if (!state.revealed) {
            LetterTileBank(
                expected = prompt.expectedAnswer,
                cardId = prompt.card.id,
                hint = answerHint(prompt),
                onChange = onAnswerChanged
            )
        }
        Spacer(Modifier.height(8.dp))
        AnimatedVisibility(
            visible = !state.revealed,
            enter = fadeIn(tween(180)) + slideInVertically(tween(200)) { it / 8 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(140)) { it / 8 }
        ) {
            Button(onClick = onReveal, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Check Answer", fontWeight = FontWeight.SemiBold)
            }
        }
        AnimatedVisibility(
            visible = state.revealed,
            enter = fadeIn(tween(200)) + slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 6 },
            exit = fadeOut(tween(120))
        ) {
            RevealPanel(state, prompt, onRate, onContinue, onKnewIt)
        }
    }
}

@Composable
private fun RevealPanel(state: ReviewUiState, prompt: ReviewPrompt, onRate: (Rating) -> Unit, onContinue: () -> Unit, onKnewIt: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ResultBanner(state, prompt)
        prompt.explanation?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.autoRatedAgain) {
            StatusBanner("Marked Again automatically because the answer was missed.")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onKnewIt,
                    enabled = !state.ratingInProgress,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("I knew it")
                }
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onContinue()
                    },
                    enabled = !state.ratingInProgress,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.ratingInProgress) "Saving..." else "Next Card", fontWeight = FontWeight.SemiBold)
                }
            }
            return
        }
        Text("How well did you recall it?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Rating.entries.forEach { rating ->
                val accent = rating.accent()
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRate(rating)
                    },
                    enabled = !state.ratingInProgress,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp, horizontal = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)
                ) {
                    Text(
                        if (state.ratingInProgress) "..." else rating.shortLabel(prompt.intervalPreview[rating] ?: 0),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultBanner(state: ReviewUiState, prompt: ReviewPrompt) {
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
                Text(prompt.expectedAnswer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                state.answerFeedback?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Reader
// ---------------------------------------------------------------------------

@Composable
private fun ReaderPanel(
    state: ReviewUiState,
    onLookup: (String) -> Unit,
    onOpen: (Long) -> Unit,
    onClose: () -> Unit,
    onMarkVisible: (List<String>, WordStatus) -> Unit,
    onProgress: (Int) -> Unit
) {
    val selected = state.selectedReaderTextId?.let { id -> state.allReaderTexts.firstOrNull { it.text.id == id } }
    if (selected == null) {
        ReaderBookshelf(state, onOpen)
    } else {
        ReaderTextScreen(state, selected, onLookup, onClose, onMarkVisible, onProgress)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderBookshelf(state: ReviewUiState, onOpen: (Long) -> Unit) {
    val texts = state.allReaderTexts.sortedWith(compareByDescending<ReaderRecommendation> { it.coverage }.thenBy { it.text.title })
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard {
            Text("Bookshelf", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Pick a text to read. Coverage is the share of words you've actually reviewed or encountered.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (texts.isEmpty()) {
            SectionCard {
                Text("No reader texts yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Add texts from Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
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
                    texts.forEachIndexed { index, item ->
                        BookCover(item = item, index = index, onOpen = onOpen)
                    }
                }
            }
        }
    }
}

private val BookPalette = listOf(
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
private fun BookCover(item: ReaderRecommendation, index: Int, onOpen: (Long) -> Unit) {
    val base = BookPalette[index % BookPalette.size]
    val deep = lerp(base, Color.Black, 0.28f)
    val spine = lerp(base, Color.Black, 0.5f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "book-scale")
    val pct = (item.coverage * 100).toInt()
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
                    "${item.totalTokens} words",
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
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderTextScreen(
    state: ReviewUiState,
    selected: ReaderRecommendation,
    onLookup: (String) -> Unit,
    onClose: () -> Unit,
    onMarkVisible: (List<String>, WordStatus) -> Unit,
    onProgress: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(selected.text.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Tap a word to look it up, then mark how well you know it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onClose) { Text("Shelf") }
        }
        WordLegend()
        // Paper-like reading surface: words flow as real text, not buttons.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        ) {
            FlowRow(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.readerTokens.forEachIndexed { index, token ->
                    ReaderWord(
                        token = token,
                        selected = state.selectedToken?.normalized == token.normalized,
                        enabled = !state.readerLookupInProgress,
                        fontScale = state.readerFontScale,
                        onClick = {
                            onProgress(index)
                            onLookup(token.surface)
                        }
                    )
                }
            }
        }
        if (state.readerTokens.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onMarkVisible(state.readerTokens.map { it.surface }, WordStatus.LEARNING) }) {
                    Text("Mark Visible Learning")
                }
                OutlinedButton(onClick = { onMarkVisible(state.readerTokens.map { it.surface }, WordStatus.KNOWN) }) {
                    Text("Mark Visible Known")
                }
            }
        }
    }
}

private fun WordStatus.statusHighlight(): Color = when (this) {
    WordStatus.NEW -> Color(0xFF4C8DFF)
    WordStatus.LEARNING -> Color(0xFFE0A21E)
    WordStatus.KNOWN, WordStatus.IGNORED -> Color.Transparent
}

@Composable
private fun ReaderWord(
    token: ReaderToken,
    selected: Boolean,
    enabled: Boolean,
    fontScale: Float,
    onClick: () -> Unit
) {
    val accent = token.status.statusHighlight()
    val background = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        accent != Color.Transparent -> accent.copy(alpha = 0.22f)
        else -> Color.Transparent
    }
    val borderMod = if (selected) {
        Modifier.border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(5.dp))
    } else {
        Modifier
    }
    Text(
        text = token.surface,
        modifier = Modifier
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
private fun WordLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LegendDot(WordStatus.NEW.statusHighlight(), "New")
        LegendDot(WordStatus.LEARNING.statusHighlight(), "Learning")
        LegendDot(MaterialTheme.colorScheme.onSurfaceVariant, "Known / ignored")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
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
private fun WordDetailCard(
    token: ReaderToken,
    state: ReviewUiState,
    onSetStatus: (WordStatus) -> Unit,
    onClearSelection: () -> Unit
) {
    SectionCard(emphasis = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(token.stressForm ?: token.surface, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val gloss = token.translation?.takeIf { it != "lookup pending" }
                Text(
                    gloss ?: "Not in your deck yet — mark it to start tracking.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (gloss == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                token.parse?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onClearSelection) { Text("Done") }
        }
        if (state.readerLookupInProgress) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp)))
        }
        Spacer(Modifier.height(14.dp))
        Text("Mark this word", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WordStatusChip("Learning", WordStatus.LEARNING, token.status, onSetStatus)
            WordStatusChip("Known", WordStatus.KNOWN, token.status, onSetStatus)
            WordStatusChip("New", WordStatus.NEW, token.status, onSetStatus)
            WordStatusChip("Ignore", WordStatus.IGNORED, token.status, onSetStatus)
        }
    }
}

@Composable
private fun WordStatusChip(
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

// ---------------------------------------------------------------------------
// Dashboard
// ---------------------------------------------------------------------------

@Composable
private fun DashboardPanel(state: ReviewUiState) {
    val stats = state.dashboardStats ?: return
    val game = state.sessionPlan?.gamification ?: GamificationStats.EMPTY
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LevelCard(game)
        StreakCard(game)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DailyGoalCard(Modifier.weight(1f), game)
            WordsKnownCard(Modifier.weight(1f), game)
        }
        AchievementsCard(game)
        DetailsSection(stats, showDetails) { showDetails = !showDetails }
    }
}

@Composable
private fun LevelCard(game: GamificationStats) {
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
private fun StreakCard(game: GamificationStats) {
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
                    if (game.currentStreak == 1) "day streak" else "day streak",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Best", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${game.longestStreak}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val labels = listOf("S", "M", "T", "W", "T", "F", "S")
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
        Text("Last 7 days · ${game.activeDays} active days total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DailyGoalCard(modifier: Modifier, game: GamificationStats) {
    val progress = if (game.dailyGoal == 0) 1f else game.reviewedToday.toFloat() / game.dailyGoal
    MiniCard(modifier) {
        ProgressRing(
            progress = progress,
            modifier = Modifier.size(84.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            color = if (game.goalReached) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.primary
        ) {
            if (game.goalReached) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E9E5B), modifier = Modifier.size(30.dp))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${game.reviewedToday}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("/ ${game.dailyGoal}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Daily goal", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            if (game.goalReached) "Done for today!" else "${(game.dailyGoal - game.reviewedToday).coerceAtLeast(0)} to go",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WordsKnownCard(modifier: Modifier, game: GamificationStats) {
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
private fun AchievementsCard(game: GamificationStats) {
    val unlocked = game.achievements.count { it.unlocked }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text("Achievements", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$unlocked / ${game.achievements.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            game.achievements.forEach { AchievementBadge(it) }
        }
    }
}

@Composable
private fun AchievementBadge(achievement: Achievement) {
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
private fun MiniCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
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
private fun DetailsSection(stats: com.sibirskyspeak.data.DashboardStats, expanded: Boolean, onToggle: () -> Unit) {
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
                val report = stats.importQualityReport
                Text("Import readiness", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                FlowRowWithStats(
                    "Noun rows" to "${report.readyNominalRows}/${report.minNominalRows}",
                    "Aspect verbs" to "${report.aspectReadyVerbRows}/${report.minVerbRows}",
                    "Aktionsart" to "${report.verifiedAktionsartVerbRows}/${report.minVerbRows}",
                    "Ranked" to report.domainRankedRows.toString(),
                    "Examples" to report.exampleRows.toString(),
                    "90% texts" to report.targetTextsAtOrAbove90.toString()
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings / import-export
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportExportPanel(
    state: ReviewUiState,
    onImportText: (String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onFullBackup: () -> Unit,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onAdd: () -> Unit,
    onDailyGoal: (Int) -> Unit,
    onSessionSize: (Int) -> Unit,
    onRetention: (Double) -> Unit,
    onReminderEnabled: (Boolean) -> Unit,
    onReminderHour: (Int) -> Unit,
    onFontScale: (Float) -> Unit,
    onSearch: (String) -> Unit
) {
    val context = LocalContext.current
    // Save the exported JSON Lines to a user-chosen file via the system picker.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && state.exportText.isNotBlank()) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(state.exportText.toByteArray()) }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tune your study pace, reminders, and reader, then manage your local data.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionCard {
            Text("Study Pace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            SettingSlider(
                label = "Daily goal",
                valueLabel = "${state.dailyGoalSetting} cards",
                value = state.dailyGoalSetting.toFloat(),
                range = SettingsStore.MIN_DAILY_GOAL.toFloat()..SettingsStore.MAX_DAILY_GOAL.toFloat(),
                onChange = { onDailyGoal(it.toInt()) }
            )
            Spacer(Modifier.height(10.dp))
            SettingSlider(
                label = "Cards per session",
                valueLabel = "${state.sessionSizeSetting}",
                value = state.sessionSizeSetting.toFloat(),
                range = SettingsStore.MIN_SESSION_SIZE.toFloat()..SettingsStore.MAX_SESSION_SIZE.toFloat(),
                onChange = { onSessionSize(it.toInt()) }
            )
            Spacer(Modifier.height(10.dp))
            SettingSlider(
                label = "Target retention",
                valueLabel = "${(state.retentionSetting * 100).toInt()}%",
                value = state.retentionSetting.toFloat(),
                range = SettingsStore.MIN_RETENTION.toFloat()..SettingsStore.MAX_RETENTION.toFloat(),
                onChange = { onRetention(it.toDouble()) }
            )
            Text(
                "Higher retention means shorter intervals and more reviews.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Daily reminder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(checked = state.reminderEnabled, onCheckedChange = onReminderEnabled)
            }
            if (state.reminderEnabled) {
                Spacer(Modifier.height(10.dp))
                SettingSlider(
                    label = "Reminder time",
                    valueLabel = "%02d:00".format(state.reminderHour),
                    value = state.reminderHour.toFloat(),
                    range = 0f..23f,
                    onChange = { onReminderHour(it.toInt()) }
                )
            }
        }
        SectionCard {
            Text("Reader", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            SettingSlider(
                label = "Text size",
                valueLabel = "${(state.readerFontScale * 100).toInt()}%",
                value = state.readerFontScale,
                range = SettingsStore.MIN_FONT_SCALE..SettingsStore.MAX_FONT_SCALE,
                onChange = onFontScale
            )
        }
        SectionCard {
            Text("Search Deck", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearch,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                singleLine = true,
                label = { Text("Russian, lemma, or meaning") }
            )
            if (state.searchQuery.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                if (state.searchResults.isEmpty()) {
                    Text("No matches.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.searchResults.take(20).forEach { note ->
                            Column {
                                Text(note.russian, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(note.translation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        SectionCard {
            Text("Add Reader Text", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = state.readerTitle, onValueChange = onTitle, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, label = { Text("Text title") })
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = state.readerBody, onValueChange = onBody, modifier = Modifier.fillMaxWidth(), minLines = 4, shape = MaterialTheme.shapes.small, label = { Text("Russian text") })
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAdd, modifier = Modifier.align(Alignment.End)) { Text("Add Text") }
        }
        SectionCard {
            Text("Import / Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Import adds notes. Export saves content only. Full Backup includes scheduling state.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = state.importText, onValueChange = onImportText, modifier = Modifier.fillMaxWidth(), minLines = 6, shape = MaterialTheme.shapes.small, label = { Text("JSON Lines notes") })
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onImport) { Text("Import") }
                OutlinedButton(onClick = onExport) { Text("Export") }
                OutlinedButton(onClick = onFullBackup) { Text("Full Backup") }
                if (state.exportText.isNotBlank()) {
                    OutlinedButton(onClick = { saveLauncher.launch("sibirskyspeak-export.jsonl") }) { Text("Save to File") }
                }
            }
            if (state.exportText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = state.exportText, onValueChange = {}, modifier = Modifier.fillMaxWidth(), minLines = 6, shape = MaterialTheme.shapes.small, label = { Text("Exported JSON Lines") })
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

@Composable
private fun SessionCompleteCard(game: GamificationStats) {
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
                if (game.goalReached) "Daily goal smashed! 🎉" else "Session complete! 🎉",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                "You reviewed ${game.reviewedToday} ${if (game.reviewedToday == 1) "card" else "cards"} today.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroPill("${game.currentStreak}", "day streak")
                HeroPill("Lvl ${game.level}", "level")
                HeroPill("${game.knownWords}", "words known")
            }
        }
    }
}

@Composable
private fun EmptyQueue(step: SessionStep) {
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
private fun HeroCard(content: @Composable ColumnScope.() -> Unit) {
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
private fun SectionCard(emphasis: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (emphasis) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun StatusBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StatusTag(label: String) {
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
private fun LetterTileBank(
    expected: String,
    cardId: Long,
    hint: String,
    onChange: (String) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val answer = remember(cardId, expected) { expected.split("/", ";", ",").firstOrNull()?.trim().orEmpty() }
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
private fun AnswerTile(label: String, used: Boolean, onClick: () -> Unit) {
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
private fun HeroPill(value: String, label: String) {
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
private fun StatPill(value: String, label: String) {
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
private fun FlowRowWithStats(vararg stats: Pair<String, String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.forEach { (label, value) -> StatPill(value, label) }
    }
}

@Composable
private fun ProgressRing(
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
private fun CoverageRing(coverage: Double, modifier: Modifier = Modifier) {
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
private fun animatedInt(target: Int): Int {
    val value by animateIntAsState(target, animationSpec = tween(700, easing = FastOutSlowInEasing), label = "counter")
    return value
}

// ---------------------------------------------------------------------------
// Mapping helpers
// ---------------------------------------------------------------------------

private fun tabIndex(step: SessionStep): Int = when (step.mainTab()) {
    SessionStep.REVIEWS -> 0
    SessionStep.READER -> 1
    SessionStep.DASHBOARD -> 2
    SessionStep.IMPORT -> 3
    else -> 0
}

@Composable
private fun Rating.accent(): Color = when (this) {
    Rating.AGAIN -> Color(0xFFD2453B)
    Rating.HARD -> Color(0xFFE08A1E)
    Rating.GOOD -> Color(0xFF2E9E5B)
    Rating.EASY -> Color(0xFF2F73D8)
}

private fun SessionStep.icon(): ImageVector =
    when (mainTab()) {
        SessionStep.REVIEWS -> Icons.Filled.School
        SessionStep.READER -> Icons.Filled.AutoStories
        SessionStep.DASHBOARD -> Icons.Filled.Insights
        SessionStep.IMPORT -> Icons.Filled.Settings
        else -> Icons.Filled.School
    }

private fun SessionStep.label(): String =
    when (this) {
        SessionStep.REVIEWS -> "Practice"
        SessionStep.RULE -> "Grammar Tip"
        SessionStep.BLOCKED -> "Focused Grammar"
        SessionStep.INTERLEAVED -> "Mixed Grammar"
        SessionStep.READER -> "Read"
        SessionStep.IMPORT -> "Settings"
        SessionStep.DASHBOARD -> "Progress"
    }

private fun SessionStep.mainTab(): SessionStep =
    when (this) {
        SessionStep.DASHBOARD -> SessionStep.DASHBOARD
        SessionStep.READER -> SessionStep.READER
        SessionStep.IMPORT -> SessionStep.IMPORT
        else -> SessionStep.REVIEWS
    }

private fun reviewTaskTitle(prompt: ReviewPrompt): String =
    when (prompt.card.cardType) {
        CardType.RU_TO_MEANING -> "Translate this Russian word"
        CardType.MEANING_TO_RU -> "Recall the Russian word"
        CardType.CLOZE -> "Fill in the missing Russian word"
        CardType.AUDIO_TO_RU -> "Listen and type the Russian"
        CardType.CASE_FILL -> "Choose the right Russian form"
        CardType.VERB_FORM -> "Conjugate this Russian verb"
        CardType.ASPECT_SELECT -> "Pick the verb form that fits"
    }

private fun reviewTaskHelp(prompt: ReviewPrompt): String =
    when (prompt.card.cardType) {
        CardType.RU_TO_MEANING -> "Type the English meaning. The Russian word is the thing being tested."
        CardType.MEANING_TO_RU -> "Type the Russian word for this English meaning."
        CardType.CLOZE -> "Use the sentence context and type the missing Russian word."
        CardType.AUDIO_TO_RU -> "Tap Hear Russian, then type what you hear."
        CardType.CASE_FILL -> "Use the sentence and case label to type the inflected form."
        CardType.VERB_FORM -> "Use the grammar label to type the conjugated verb form."
        CardType.ASPECT_SELECT -> "Choose the form that matches whether the action is bounded or ongoing."
    }

private fun answerHint(prompt: ReviewPrompt): String =
    when (prompt.answerMode) {
        AnswerMode.ENGLISH -> "Type the English meaning."
        AnswerMode.RUSSIAN_TYPED -> "Type in Russian. Stress marks and small spelling slips are okay."
        AnswerMode.AUDIO_ONLY -> "Type the Russian you heard. Small spelling slips are okay."
        AnswerMode.CHOICE -> "Pick one of the choices."
    }

private fun reviewContext(prompt: ReviewPrompt): String? =
    when (prompt.card.cardType) {
        CardType.RU_TO_MEANING -> prompt.note.exampleSentence?.let { "Example: $it" }
        CardType.MEANING_TO_RU -> null
        CardType.CLOZE, CardType.CASE_FILL, CardType.VERB_FORM, CardType.ASPECT_SELECT -> prompt.note.exampleTranslation?.let { "Meaning: $it" }
        CardType.AUDIO_TO_RU -> null
    }

private fun formatDays(days: Int): String =
    when (days) {
        0 -> "10m"
        1 -> "1d"
        else -> "${days}d"
    }

private fun Rating.shortLabel(days: Int): String =
    "${name.lowercase().replaceFirstChar { it.titlecase() }}\n${formatDays(days)}"

private fun ReaderStatus.label(): String =
    when (this) {
        ReaderStatus.TOO_HARD -> "hard"
        ReaderStatus.PRODUCTIVE -> "good"
        ReaderStatus.EASY -> "easy"
    }

private fun formatCount(value: Int): String =
    "%,d".format(value)
