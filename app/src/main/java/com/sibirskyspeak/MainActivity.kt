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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.sibirskyspeak.audio.RussianSpeechRecognizer
import com.sibirskyspeak.audio.RussianTextToSpeech
import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.data.GrammarConcept
import com.sibirskyspeak.data.GrammarConcepts
import com.sibirskyspeak.data.Note
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
import com.sibirskyspeak.review.LeechItem
import com.sibirskyspeak.review.ReviewUiState
import com.sibirskyspeak.review.ReviewViewModel
import com.sibirskyspeak.review.ReviewViewModelFactory
import com.sibirskyspeak.review.SessionStep
import java.util.Locale

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
    var settingsArea by rememberSaveable { mutableStateOf(SettingsArea.STUDY) }
    var showReference by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = studyActive) { studyActive = false }
    BackHandler(enabled = showReference && !studyActive) { showReference = false }

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
                ),
                actions = {
                    if (!studyActive) {
                        IconButton(onClick = { showReference = !showReference }) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Grammar reference")
                        }
                    }
                }
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
                            onSpeak = { p -> tts.speak(p.speechText()) },
                            onExit = { studyActive = false },
                            onUndo = viewModel::undoLastReview,
                            onKnewIt = viewModel::overrideKnewIt,
                            onSuspend = viewModel::suspendCurrentCard,
                            onKnowWord = viewModel::markCurrentWordKnown,
                            onStartSession = viewModel::startStudySession,
                            onSaveEdit = viewModel::editCurrentCard
                        )
                        SessionStep.REVIEWS -> PracticeScreen(
                            state = state,
                            onStart = { studyActive = true },
                            onRead = { viewModel.setSessionStep(SessionStep.READER) },
                            onOpenReader = { id ->
                                viewModel.setSessionStep(SessionStep.READER)
                                viewModel.openReaderText(id)
                            }
                        )
                        SessionStep.READER -> ReaderPanel(
                            state = state,
                            onLookup = viewModel::lookupReaderToken,
                            onOpen = viewModel::openReaderText,
                            onClose = viewModel::closeReaderText,
                            onMarkVisible = viewModel::markVisibleWords,
                            onProgress = viewModel::recordReaderProgress,
                            onAddText = {
                                settingsArea = SettingsArea.READER
                                viewModel.setSessionStep(SessionStep.IMPORT)
                            },
                            onSpeakRussian = tts::speak
                        )
                        SessionStep.DASHBOARD -> DashboardPanel(
                            state = state,
                            onStart = { studyActive = true },
                            onOpenReader = { id ->
                                viewModel.setSessionStep(SessionStep.READER)
                                viewModel.openReaderText(id)
                            },
                            onRead = { viewModel.setSessionStep(SessionStep.READER) },
                            onLoadLeeches = viewModel::loadLeeches,
                            onReleaseLeech = viewModel::releaseLeech
                        )
                        SessionStep.IMPORT -> ImportExportPanel(
                            state = state,
                            selectedArea = settingsArea,
                            onSelectedArea = { settingsArea = it },
                            onImportText = viewModel::setImportText,
                            onImport = viewModel::importJsonLines,
                            onExport = viewModel::exportJsonLines,
                            onFullBackup = viewModel::exportFullState,
                            onTitle = viewModel::setReaderTitle,
                            onBody = viewModel::setReaderBody,
                            onAdd = viewModel::addReaderText,
                            onDailyGoal = viewModel::setDailyGoal,
                            onSessionSize = viewModel::setSessionSize,
                            onNewCardsPerDay = viewModel::setNewCardsPerDay,
                            onRetention = viewModel::setRetention,
                            onPlaceAfterLevel = viewModel::placeAfterLevel,
                            onReminderEnabled = { enabled ->
                                viewModel.setReminderEnabled(enabled)
                                Reminders.schedule(context)
                            },
                            onReminderHour = { hour ->
                                viewModel.setReminderHour(hour)
                                Reminders.schedule(context)
                            },
                            onFontScale = viewModel::setReaderFontScale,
                            onSearch = viewModel::setSearchQuery,
                            onSpeakRussian = tts::speak
                        )
                        else -> PracticeScreen(
                            state = state,
                            onStart = { studyActive = true },
                            onRead = { viewModel.setSessionStep(SessionStep.READER) },
                            onOpenReader = { id ->
                                viewModel.setSessionStep(SessionStep.READER)
                                viewModel.openReaderText(id)
                            }
                        )
                    }
                }
                state.statusMessage?.let { StatusBanner(it, onDismiss = viewModel::dismissStatusMessage) }
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
                            onClearSelection = viewModel::clearSelectedToken,
                            onSpeakRussian = tts::speak,
                            onMine = viewModel::mineSentence
                        )
                    }
                }
            }
            AchievementUnlockOverlay(
                achievements = state.newlyUnlocked,
                onDismiss = viewModel::dismissNewlyUnlocked,
                modifier = Modifier.align(Alignment.TopCenter)
            )
            // Grammar reference overlay (drawn on top of the active tab).
            if (showReference && !studyActive) {
                GrammarReferenceScreen(
                    query = state.searchQuery,
                    results = state.searchResults,
                    onQuery = viewModel::setSearchQuery,
                    onSpeak = tts::speak,
                    onClose = { showReference = false },
                    modifier = Modifier.matchParentSize()
                )
            }
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
    onRead: () -> Unit,
    onOpenReader: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DailyPlanPanel(state, onStart, onRead, onOpenReader)
        PracticeFocusPanel(state)
        ReadingSuggestion(state, onOpenReader)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DailyPlanPanel(state: ReviewUiState, onStart: () -> Unit, onRead: () -> Unit, onOpenReader: (Long) -> Unit) {
    val plan = state.dailyPlan ?: return
    val prompts = state.sessionPlan?.reviewQueue.orEmpty()
    val sessionSize = prompts.size
    val backlog = plan.dueVocab + plan.dueGrammar
    val reader = state.readerRecommendation
    val game = state.sessionPlan?.gamification ?: GamificationStats.EMPTY
    val dailyGoal = game.dailyGoal.coerceAtLeast(1)
    val progress = (state.reviewedToday.toFloat() / dailyGoal).coerceIn(0f, 1f)
    val goalRemaining = (dailyGoal - state.reviewedToday).coerceAtLeast(0)
    val newCount = prompts.count { it.card.state.name == "NEW" }
    val grammarCount = prompts.count { it.card.queue.name == "GRAMMAR" }
    val focus = plan.grammarFocus.firstOrNull()?.label?.takeIf { it.isNotBlank() }

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
                    Text("goal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Today's Practice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                Text(
                    when {
                        sessionSize > 0 && goalRemaining > 0 -> "$sessionSize cards are ready. $goalRemaining more reviews hit today's goal."
                        sessionSize > 0 -> "$sessionSize cards are ready if you want to keep the streak warm."
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
            HeroPill(dailyGoal.toString(), "daily goal")
            HeroPill(sessionSize.toString(), "ready now")
            if (prompts.isNotEmpty()) HeroPill(newCount.toString(), "new cards")
            if (grammarCount > 0) HeroPill(grammarCount.toString(), "grammar")
            if (reader != null) HeroPill("${(reader.coverage * 100).toInt()}%", "reader fit")
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (sessionSize > 0) {
                        onStart()
                    } else {
                        reader?.let { onOpenReader(it.text.id) } ?: onRead()
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimary,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(if (sessionSize > 0) Icons.Filled.School else Icons.Filled.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (sessionSize > 0) "Start ${sessionSize}-Card Session" else "Read Next", fontWeight = FontWeight.SemiBold)
            }
            if (sessionSize > 0) {
                OutlinedButton(
                    onClick = { reader?.let { onOpenReader(it.text.id) } ?: onRead() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Filled.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Read")
                }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PracticeFocusPanel(state: ReviewUiState) {
    val plan = state.dailyPlan ?: return
    val prompts = state.sessionPlan?.reviewQueue.orEmpty()
    val game = state.sessionPlan?.gamification ?: GamificationStats.EMPTY
    val vocabCount = prompts.count { it.card.queue.name == "VOCAB" }
    val grammarCount = prompts.count { it.card.queue.name == "GRAMMAR" }
    val newCount = prompts.count { it.card.state.name == "NEW" }
    val dueCount = prompts.size - newCount

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Session Mix", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (prompts.isEmpty()) "No review cards are waiting right now." else "A quick scan of what practice will ask from you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (game.currentStreak > 0) {
                StatusTag("${game.currentStreak} day streak")
            }
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PracticeMetricTile("Due", dueCount.toString(), Icons.Filled.Bolt, MaterialTheme.colorScheme.primaryContainer)
            PracticeMetricTile("New", newCount.toString(), Icons.Filled.School, MaterialTheme.colorScheme.secondaryContainer)
            PracticeMetricTile("Vocab", vocabCount.toString(), Icons.Filled.AutoStories, MaterialTheme.colorScheme.surfaceVariant)
            PracticeMetricTile("Grammar", grammarCount.toString(), Icons.Filled.Insights, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f))
        }
        if (plan.grammarFocus.isNotEmpty() || plan.triageMode) {
            Spacer(Modifier.height(16.dp))
            Text("Focus", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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
private fun PracticeMetricTile(label: String, value: String, icon: ImageVector, container: Color) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PracticeFocusChip(label: String, accuracy: Double?) {
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
private fun ReadingSuggestion(state: ReviewUiState, onOpenReader: (Long) -> Unit) {
    val reader = state.readerRecommendation ?: return
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
private fun ReaderStatusChip(status: ReaderStatus) {
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
    onKnewIt: () -> Unit,
    onSuspend: () -> Unit,
    onKnowWord: () -> Unit,
    onStartSession: () -> Unit,
    onSaveEdit: (String?, String?, String?) -> Unit
) {
    LaunchedEffect(Unit) { onStartSession() }
    var editing by remember { mutableStateOf(false) }
    val sessionSize = state.sessionPlan?.reviewQueue?.size ?: 0
    val prompt = state.prompt
    val game = state.sessionPlan?.gamification ?: GamificationStats.EMPTY
    val dailyGoal = game.dailyGoal.coerceAtLeast(1)
    val goalProgress = (state.reviewedToday.toFloat() / dailyGoal).coerceIn(0f, 1f)
    val animatedGoalProgress by animateFloatAsState(
        targetValue = goalProgress,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "practice-goal-progress"
    )
    val headerMessage = when {
        prompt == null -> "Session complete."
        state.revealed -> "Check the answer, then rate your recall."
        prompt.answerMode == AnswerMode.AUDIO_ONLY -> "Audio started automatically. Type what you heard."
        sessionSize > 0 -> "$sessionSize cards ready now. ${state.reviewedToday}/$dailyGoal reviews today."
        else -> "Answer, check, then rate recall."
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
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
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (prompt != null && prompt.card.queue.name == "VOCAB") {
                            TextButton(onClick = onKnowWord, enabled = !state.ratingInProgress) {
                                Text("Know it", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (prompt != null) {
                            IconButton(onClick = { editing = true }, enabled = !state.ratingInProgress) {
                                Icon(Icons.Filled.Edit, contentDescription = "Fix this card")
                            }
                            TextButton(onClick = onSuspend, enabled = !state.ratingInProgress) {
                                Text("Suspend", style = MaterialTheme.typography.labelMedium)
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
        ) {
            if (prompt == null) {
                SessionCompleteCard(
                    state.sessionPlan?.gamification ?: GamificationStats.EMPTY,
                    onDone = onExit,
                    sessionReviewed = state.sessionReviewed,
                    sessionCorrect = state.sessionCorrect
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
            onSave = { t, ex, exT ->
                onSaveEdit(t, ex, exT)
                editing = false
            }
        )
    }
}

@Composable
private fun EditCardDialog(
    note: Note,
    onDismiss: () -> Unit,
    onSave: (String?, String?, String?) -> Unit
) {
    var translation by remember(note.id) { mutableStateOf(note.translation) }
    var example by remember(note.id) { mutableStateOf(note.exampleSentence.orEmpty()) }
    var exampleTranslation by remember(note.id) { mutableStateOf(note.exampleTranslation.orEmpty()) }
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
                    label = { Text("Example translation") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    translation.trim().takeIf { it.isNotBlank() && it != note.translation },
                    example.trim().takeIf { it.isNotBlank() && it != note.exampleSentence },
                    exampleTranslation.trim().takeIf { it.isNotBlank() && it != note.exampleTranslation }
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionProgressStrip(
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
            "${reviewedToday.coerceAtMost(dailyGoal)} / $dailyGoal today",
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
private fun QueueCount(count: Int, color: Color) {
    Text(
        count.toString(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (count > 0) color else color.copy(alpha = 0.35f)
    )
}

@Composable
private fun PracticeStageChip(label: String) {
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
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content
    ) {
        AnimatedContent(
            targetState = label,
            transitionSpec = {
                (fadeIn(tween(160)) + slideInVertically(tween(180)) { it / 2 })
                    .togetherWith(fadeOut(tween(100)) + slideOutVertically(tween(120)) { -it / 2 })
            },
            label = "stage-chip-label"
        ) { text ->
            Text(
                text,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AutoPlayCardAudio(cardId: Long, onSpeak: () -> Unit) {
    LaunchedEffect(cardId) {
        onSpeak()
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
        Spacer(Modifier.height(18.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
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
            RevealPanel(state, prompt, onRate, onContinue, onKnewIt, onSpeak)
        }
    }
}

@Composable
private fun SpeakingAnswerInput(
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
private fun rememberRussianSpeechRecognizer(): RussianSpeechRecognizer {
    val context = LocalContext.current
    val recognizer = remember { RussianSpeechRecognizer(context) }
    DisposableEffect(recognizer) {
        onDispose { recognizer.shutdown() }
    }
    return recognizer
}

@Composable
private fun LessonCard(
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
private fun AudioPracticeButton(onClick: () -> Unit) {
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
private fun ChoiceAnswerButton(choice: String, index: Int, onClick: () -> Unit) {
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
private fun PrimaryPracticeButton(
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
private fun InputModeToggle(
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
private fun InputModeButton(
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
private fun KeyboardAnswerInput(
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
private fun RevealPanel(state: ReviewUiState, prompt: ReviewPrompt, onRate: (Rating) -> Unit, onContinue: () -> Unit, onKnewIt: () -> Unit, onSpeak: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    // Reinforce correct pronunciation on reveal for the cards where prompt-side
    // auto-play was suppressed (production, cloze, choice, stress, speak) — so you
    // hear the right Russian right after you commit your answer. Recognition/listening
    // cards already played on the prompt, so don't repeat them.
    if (prompt.answerMode != AnswerMode.ENGLISH && prompt.answerMode != AnswerMode.AUDIO_ONLY) {
        LaunchedEffect(prompt.card.id) { onSpeak() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ResultBanner(state, prompt, onSpeak)
        prompt.explanation?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        // Thin separator between the revealed answer and the grading bar (Anki's
        // "answer line"), for clearer visual structure.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        )
        RatingDecisionGuide(state)
        // AnkiDroid-style answer bar: all four grades in a single row across the bottom.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Rating.entries.forEach { rating ->
                RatingButton(
                    rating = rating,
                    intervalDays = prompt.intervalPreview[rating] ?: 0,
                    saving = state.ratingInProgress,
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
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
private fun RatingDecisionGuide(state: ReviewUiState) {
    val (label, body) = when (state.answerMatch) {
        AnswerMatch.CLOSE -> "Close answer" to "Hard is usually right if you had to think or spelling was rough. Use Good if the form was clear."
        AnswerMatch.EXACT -> "Correct answer" to "Good is the default for solid recall. Use Easy only when it came instantly."
        else -> "Recall check" to "Rate the effort you actually felt; the interval preview shows the scheduling result."
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
private fun RatingButton(
    rating: Rating,
    intervalDays: Int,
    saving: Boolean,
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
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = Color.White)
    ) {
        // AnkiDroid-style: the next interval on top, the grade label below it.
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
        }
    }
}

@Composable
private fun ResultBanner(state: ReviewUiState, prompt: ReviewPrompt, onSpeak: () -> Unit) {
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
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onSpeak) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear answer", tint = color)
            }
        }
    }
}

private fun ReviewPrompt.speechText(): String =
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

private fun String.hasRussianTextForSpeech(): Boolean =
    Regex("""\p{IsCyrillic}+""").containsMatchIn(this)

private fun String.russianLinesForSpeech(): String? {
    val cyrillic = Regex("""\p{IsCyrillic}+""")
    return lineSequence()
        .map { it.trim() }
        .filter { cyrillic.containsMatchIn(it) }
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
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
    onProgress: (Int) -> Unit,
    onAddText: () -> Unit,
    onSpeakRussian: (String) -> Unit
) {
    val selected = state.selectedReaderTextId?.let { id -> state.allReaderTexts.firstOrNull { it.text.id == id } }
    if (selected == null) {
        ReaderBookshelf(state, onOpen, onAddText, onSpeakRussian)
    } else {
        ReaderTextScreen(state, selected, onLookup, onClose, onMarkVisible, onProgress, onSpeakRussian)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderBookshelf(
    state: ReviewUiState,
    onOpen: (Long) -> Unit,
    onAddText: () -> Unit,
    onSpeakRussian: (String) -> Unit
) {
    val texts = state.allReaderTexts.sortedWith(compareByDescending<ReaderRecommendation> { it.coverage }.thenBy { it.text.title })
    val recommended = state.readerRecommendation
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
                        BookCover(
                            item = item,
                            index = index,
                            recommended = item.text.id == state.readerRecommendation?.text?.id,
                            onOpen = onOpen
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderRecommendationCard(item: ReaderRecommendation, onOpen: (Long) -> Unit, onSpeakRussian: (String) -> Unit) {
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
            }
            IconButton(onClick = { onSpeakRussian(item.text.body) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Preview recommended text")
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
private fun BookCover(item: ReaderRecommendation, index: Int, recommended: Boolean, onOpen: (Long) -> Unit) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderTextScreen(
    state: ReviewUiState,
    selected: ReaderRecommendation,
    onLookup: (String) -> Unit,
    onClose: () -> Unit,
    onMarkVisible: (List<String>, WordStatus) -> Unit,
    onProgress: (Int) -> Unit,
    onSpeakRussian: (String) -> Unit
) {
    val tokenCount = state.readerTokens.size.coerceAtLeast(1)
    val progress = (state.readerProgressIndex.toFloat() / tokenCount).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "reader-progress"
    )
    val knownCount = state.readerTokens.count { it.status == WordStatus.KNOWN || it.status == WordStatus.IGNORED }
    val learningCount = state.readerTokens.count { it.status == WordStatus.LEARNING }
    val newTokens = state.readerTokens
        .filter { it.status == WordStatus.NEW }
        .distinctBy { it.normalized }
        .map { it.surface }
    // Sentence-by-sentence "Listen" mode: its own TTS engine so it doesn't clash with
    // the tap-a-word pronunciation, with karaoke-style current-sentence highlighting.
    val readerTts = rememberRussianTts()
    val sentences = remember(selected.text.id) { splitIntoSentences(selected.text.body) }
    var playingSentence by remember(selected.text.id) { mutableStateOf(-1) }
    var isPlaying by remember(selected.text.id) { mutableStateOf(false) }
    LaunchedEffect(selected.text.id) {
        readerTts.stopSpeaking(); isPlaying = false; playingSentence = -1
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(selected.text.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            ReaderStatusChip(selected.status)
                        }
                        Text("Tap any word for meaning, status, and examples.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        OutlinedButton(onClick = onClose) { Text("Shelf") }
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
                        .height(8.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderMetricChip("${(selected.coverage * 100).toInt()}%", "coverage")
                    ReaderMetricChip(formatCount(state.readerProgressIndex.coerceAtMost(state.readerTokens.size)), "reached")
                    ReaderMetricChip(formatCount(knownCount), "known")
                    if (learningCount > 0) ReaderMetricChip(formatCount(learningCount), "learning")
                    if (newTokens.isNotEmpty()) ReaderMetricChip(formatCount(newTokens.size), "new")
                }
            }
        }
        WordLegend()
        ReaderContinueCard(
            tokens = state.readerTokens,
            progressIndex = state.readerProgressIndex,
            onSpeakRussian = onSpeakRussian
        )
        // Paper-like reading surface: words flow as real text, not buttons.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        ) {
            FlowRow(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                state.readerTokens.forEachIndexed { index, token ->
                    ReaderWord(
                        token = token,
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
        if (newTokens.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("New words in this text", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${formatCount(newTokens.size)} unique new words are highlighted. Batch-mark them after a pass, or tap individual words as you read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onMarkVisible(newTokens, WordStatus.LEARNING) }) {
                            Text("Mark All Learning")
                        }
                        Button(onClick = { onMarkVisible(newTokens, WordStatus.KNOWN) }) {
                            Text("Mark All Known")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderContinueCard(
    tokens: List<ReaderToken>,
    progressIndex: Int,
    onSpeakRussian: (String) -> Unit
) {
    if (tokens.isEmpty() || progressIndex <= 0) return
    val start = progressIndex.coerceIn(0, tokens.lastIndex)
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
private fun ReaderMetricChip(value: String, label: String) {
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

private fun WordStatus.statusHighlight(): Color = when (this) {
    WordStatus.NEW -> Color(0xFF4C8DFF)
    WordStatus.LEARNING -> Color(0xFFE0A21E)
    WordStatus.KNOWN, WordStatus.IGNORED -> Color.Transparent
}

@Composable
private fun ReaderWord(
    token: ReaderToken,
    selected: Boolean,
    reached: Boolean,
    enabled: Boolean,
    fontScale: Float,
    onClick: () -> Unit
) {
    val accent = token.status.statusHighlight()
    val targetBackground = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
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
    val borderMod = if (selected) {
        Modifier.border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(5.dp))
    } else {
        Modifier
    }
    Text(
        text = token.surface,
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
    onClearSelection: () -> Unit,
    onSpeakRussian: (String) -> Unit,
    onMine: (String) -> Unit = {}
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
            TextButton(onClick = onClearSelection) { Text("Done") }
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
            "New stays highlighted. Learning tracks it. Known counts toward reader coverage. Ignore hides names or noise.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WordStatusChip("New", WordStatus.NEW, token.status, onSetStatus)
            WordStatusChip("Learning", WordStatus.LEARNING, token.status, onSetStatus)
            WordStatusChip("Known", WordStatus.KNOWN, token.status, onSetStatus)
            WordStatusChip("Ignore", WordStatus.IGNORED, token.status, onSetStatus)
        }
        if (miningSentence != null && token.status != WordStatus.KNOWN && token.status != WordStatus.IGNORED) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = { onMine(miningSentence) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Study this word in this sentence")
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
}

@Composable
private fun CurrentWordStatusPill(status: WordStatus) {
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
private fun DashboardPanel(
    state: ReviewUiState,
    onStart: () -> Unit,
    onOpenReader: (Long) -> Unit,
    onRead: () -> Unit,
    onLoadLeeches: () -> Unit = {},
    onReleaseLeech: (LeechItem) -> Unit = {}
) {
    val stats = state.dashboardStats ?: return
    val game = state.sessionPlan?.gamification ?: GamificationStats.EMPTY
    var showDetails by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(stats.leechCount) { if (stats.leechCount > 0) onLoadLeeches() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LevelCard(game)
        DashboardNextActionCard(state, onStart, onOpenReader, onRead)
        StreakCard(game)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DailyGoalCard(Modifier.weight(1f), game)
            WordsKnownCard(Modifier.weight(1f), game)
        }
        AchievementsCard(game)
        if (stats.leechCount > 0) LeechCard(state.leeches, stats.leechCount, onReleaseLeech)
        DetailsSection(stats, showDetails) { showDetails = !showDetails }
    }
}

@Composable
private fun LeechCard(leeches: List<LeechItem>, leechCount: Int, onRelease: (LeechItem) -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text("Parked leeches ($leechCount)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Cards that kept tripping you up. Fix them in the deck, or release one to try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        leeches.take(20).forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.russian, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${item.translation} · ${item.lapses} lapses",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onRelease(item) }) { Text("Release") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardNextActionCard(
    state: ReviewUiState,
    onStart: () -> Unit,
    onOpenReader: (Long) -> Unit,
    onRead: () -> Unit
) {
    val prompts = state.sessionPlan?.reviewQueue.orEmpty()
    val reader = state.readerRecommendation
    val game = state.sessionPlan?.gamification ?: GamificationStats.EMPTY
    val remainingGoal = (game.dailyGoal - state.reviewedToday).coerceAtLeast(0)
    val grammarCount = prompts.count { it.card.queue.name == "GRAMMAR" }
    val newCount = prompts.count { it.card.state.name == "NEW" }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Next Best Step", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        prompts.isNotEmpty() && remainingGoal > 0 -> "${prompts.size} cards are ready. $remainingGoal more reviews completes today's goal."
                        prompts.isNotEmpty() -> "${prompts.size} cards are ready for extra practice."
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
            PracticeFocusChip(if (prompts.isEmpty()) "Reviews clear" else "${prompts.size} ready", null)
            if (newCount > 0) PracticeFocusChip("$newCount new", null)
            if (grammarCount > 0) PracticeFocusChip("$grammarCount grammar", null)
            if (reader != null) PracticeFocusChip("${(reader.coverage * 100).toInt()}% reader fit", null)
        }
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (prompts.isNotEmpty()) {
                        onStart()
                    } else {
                        reader?.let { onOpenReader(it.text.id) } ?: onRead()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(if (prompts.isNotEmpty()) Icons.Filled.School else Icons.Filled.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (prompts.isEmpty()) "Read Next" else "Practice")
            }
            if (prompts.isNotEmpty()) {
                OutlinedButton(onClick = { reader?.let { onOpenReader(it.text.id) } ?: onRead() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Read")
                }
            }
        }
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
        Text("Last 7 days - ${game.activeDays} active days total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val preview = game.achievements.filter { it.unlocked }.takeLast(3)
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

private enum class SettingsArea(val label: String, val summary: String) {
    STUDY("Study", "Pace, placement, and reminders"),
    READER("Reader", "Text size, new texts, and deck search"),
    DATA("Data", "Import, export, and backups")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportExportPanel(
    state: ReviewUiState,
    selectedArea: SettingsArea,
    onSelectedArea: (SettingsArea) -> Unit,
    onImportText: (String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onFullBackup: () -> Unit,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onAdd: () -> Unit,
    onDailyGoal: (Int) -> Unit,
    onSessionSize: (Int) -> Unit,
    onNewCardsPerDay: (Int) -> Unit,
    onRetention: (Double) -> Unit,
    onPlaceAfterLevel: (String) -> Unit,
    onReminderEnabled: (Boolean) -> Unit,
    onReminderHour: (Int) -> Unit,
    onFontScale: (Float) -> Unit,
    onSearch: (String) -> Unit,
    onSpeakRussian: (String) -> Unit
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
                selectedArea.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            SettingsAreaPicker(selected = selectedArea, onSelect = onSelectedArea)
        }
        AnimatedContent(
            targetState = selectedArea,
            transitionSpec = {
                (fadeIn(tween(160)) + slideInHorizontally(tween(180)) { it / 10 })
                    .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(140)) { -it / 12 })
                    .using(SizeTransform(clip = false))
            },
            label = "settings-area"
        ) { area ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (area) {
                    SettingsArea.STUDY -> {
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
                                label = "New cards per day",
                                valueLabel = "${state.newCardsPerDaySetting}",
                                value = state.newCardsPerDaySetting.toFloat(),
                                range = SettingsStore.MIN_NEW_CARDS_PER_DAY.toFloat()..SettingsStore.MAX_NEW_CARDS_PER_DAY.toFloat(),
                                onChange = { onNewCardsPerDay(it.toInt()) }
                            )
                            Spacer(Modifier.height(10.dp))
                            SettingSlider(
                                label = "Target retention",
                                valueLabel = "${(state.retentionSetting * 100).toInt()}%",
                                value = state.retentionSetting.toFloat(),
                                range = SettingsStore.MIN_RETENTION.toFloat()..SettingsStore.MAX_RETENTION.toFloat(),
                                rangeLabel = { "${(it * 100).toInt()}%" },
                                onChange = { onRetention(it.toDouble()) }
                            )
                            Text(
                                "Higher retention means shorter intervals and more reviews.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SectionCard {
                            Text("Placement", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Already know earlier course material? Start after a level and mark those notes known.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf("A1", "A2", "B1", "B2").forEach { level ->
                                    OutlinedButton(onClick = { onPlaceAfterLevel(level) }) {
                                        Text("After $level")
                                    }
                                }
                            }
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
                                rangeLabel = { "%02d:00".format(it.toInt()) },
                                onChange = { onReminderHour(it.toInt()) }
                            )
                            }
                        }
                    }
                    SettingsArea.READER -> {
                        SectionCard {
                            Text("Reader", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            SettingSlider(
                                label = "Text size",
                                valueLabel = "${(state.readerFontScale * 100).toInt()}%",
                                value = state.readerFontScale,
                                range = SettingsStore.MIN_FONT_SCALE..SettingsStore.MAX_FONT_SCALE,
                                rangeLabel = { "${(it * 100).toInt()}%" },
                                onChange = onFontScale
                            )
                        }
                        SectionCard {
                            Text("Add Reader Text", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            val readerBodyReady = state.readerBody.isNotBlank()
                            val readerWordCount = state.readerBody.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                            OutlinedTextField(value = state.readerTitle, onValueChange = onTitle, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, label = { Text("Text title") })
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(value = state.readerBody, onValueChange = onBody, modifier = Modifier.fillMaxWidth(), minLines = 4, shape = MaterialTheme.shapes.small, label = { Text("Russian text") })
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (readerBodyReady) "${formatCount(readerWordCount)} words ready for tap-to-learn reading." else "Paste Russian text to enable reader import.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { onSpeakRussian(state.readerBody) }, enabled = readerBodyReady) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Preview Audio")
                                }
                                Button(onClick = onAdd, enabled = readerBodyReady) { Text("Add Text") }
                            }
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
                            Spacer(Modifier.height(10.dp))
                            if (state.searchQuery.isBlank()) {
                                Text(
                                    "Search your deck before reading to preview forms and hear words aloud.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                if (state.searchResults.isEmpty()) {
                                    Text("No deck matches for this search.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        state.searchResults.take(20).forEach { note ->
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(note.russian, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                                    Text(note.translation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                IconButton(onClick = { onSpeakRussian(note.russian) }) {
                                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear word")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    SettingsArea.DATA -> {
                        SectionCard {
                            Text("Import / Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Paste JSON Lines to add notes, or export a copy of your deck and review state.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(value = state.importText, onValueChange = onImportText, modifier = Modifier.fillMaxWidth(), minLines = 6, shape = MaterialTheme.shapes.small, label = { Text("JSON Lines notes") })
                            Text(
                                "Import is additive; existing notes and scheduling stay intact.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = onImport, enabled = state.importText.isNotBlank()) { Text("Import Notes") }
                                OutlinedButton(onClick = onExport) { Text("Export") }
                                OutlinedButton(onClick = onFullBackup) { Text("Full Backup") }
                                if (state.exportText.isNotBlank()) {
                                    OutlinedButton(onClick = { saveLauncher.launch("sibirskyspeak-export.jsonl") }) { Text("Save to File") }
                                }
                            }
                            Text(
                                "Export saves note content. Full Backup also includes SRS scheduling.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.exportText.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(value = state.exportText, onValueChange = {}, modifier = Modifier.fillMaxWidth(), minLines = 6, shape = MaterialTheme.shapes.small, label = { Text("Exported JSON Lines") })
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsAreaPicker(selected: SettingsArea, onSelect: (SettingsArea) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsArea.entries.forEach { area ->
            FilterChip(
                selected = selected == area,
                onClick = { onSelect(area) },
                label = { Text(area.label) }
            )
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    rangeLabel: (Float) -> String = { it.settingRangeLabel() },
    onChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(rangeLabel(range.start), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(rangeLabel(range.endInclusive), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Float.settingRangeLabel(): String =
    if (this % 1f == 0f) toInt().toString() else "${(this * 100).toInt()}%"

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

@Composable
private fun SessionCompleteCard(
    game: GamificationStats,
    onDone: () -> Unit,
    sessionReviewed: Int = 0,
    sessionCorrect: Int = 0
) {
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
private fun StatusBanner(message: String, onDismiss: (() -> Unit)? = null) {
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

private fun reviewTaskHelp(prompt: ReviewPrompt): String =
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
        CardType.STRESS_MARK -> "Type the word with its stressed vowel marked."
        CardType.LESSON -> "Read the explanation, then continue when it feels familiar."
    }

private fun answerHint(prompt: ReviewPrompt): String =
    when (prompt.answerMode) {
        AnswerMode.ENGLISH -> "Type the English meaning."
        AnswerMode.RUSSIAN_TYPED -> "Type in Russian. Stress marks and small spelling slips are okay."
        AnswerMode.RUSSIAN_STRESS_TYPED -> "Type Russian with the stress mark."
        AnswerMode.AUDIO_ONLY -> "Type the Russian you heard. Small spelling slips are okay."
        AnswerMode.SPEAK -> "Tap the mic and say it aloud."
        AnswerMode.CHOICE -> "Pick one of the choices."
        AnswerMode.LESSON -> "Read the lesson, then tap Got it."
    }

private fun AnswerMode.modeLabel(): String =
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
private fun ReviewPrompt.hasSentenceGloss(): Boolean {
    val gloss = exampleTranslation?.trim().orEmpty()
    return gloss.isNotBlank() &&
        !gloss.equals(note.translation.trim(), ignoreCase = true) &&
        gloss.split(Regex("\\s+")).size >= 2
}

private fun reviewContext(prompt: ReviewPrompt): String? =
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
private fun reviewRevealContext(prompt: ReviewPrompt): Pair<String, String?>? =
    when (prompt.card.cardType) {
        CardType.RU_TO_MEANING, CardType.MEANING_TO_RU, CardType.CLOZE ->
            prompt.exampleSentence?.let { ru ->
                ru to prompt.exampleTranslation?.takeIf { prompt.hasSentenceGloss() }
            }
        else -> null
    }

private fun formatDays(days: Int): String =
    when (days) {
        0 -> "10m"
        1 -> "1d"
        else -> "${days}d"
    }

private fun Rating.shortLabel(days: Int): String =
    "${name.lowercase().replaceFirstChar { it.titlecase() }}\n${formatDays(days)}"

private fun Rating.recallCaption(): String =
    when (this) {
        Rating.AGAIN -> "Forgot"
        Rating.HARD -> "Slow"
        Rating.GOOD -> "Solid"
        Rating.EASY -> "Instant"
    }

private fun ReaderStatus.label(): String =
    when (this) {
        ReaderStatus.TOO_HARD -> "hard"
        ReaderStatus.PRODUCTIVE -> "good"
        ReaderStatus.EASY -> "easy"
    }

private fun formatCount(value: Int): String =
    "%,d".format(value)

/** Split a reader passage into sentences for sentence-by-sentence audio playback. */
private fun splitIntoSentences(text: String): List<String> =
    text.split(Regex("(?<=[.!?…])\\s+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }

// --- Grammar reference ------------------------------------------------------

/** Human label for a declension/conjugation table key (e.g. GEN_SG -> "Genitive sg"). */
private fun paradigmKeyLabel(key: String): String {
    val caseNames = mapOf(
        "NOM" to "Nominative", "GEN" to "Genitive", "DAT" to "Dative",
        "ACC" to "Accusative", "INS" to "Instrumental", "PREP" to "Prepositional",
        "FEM" to "Feminine", "NEUT" to "Neuter", "PL" to "Plural", "M" to "Masculine"
    )
    val verbNames = mapOf(
        "PRES_1SG" to "I (я)", "PRES_2SG" to "you (ты)", "PRES_3SG" to "he/she (он)",
        "PRES_1PL" to "we (мы)", "PRES_2PL" to "you (вы)", "PRES_3PL" to "they (они)",
        "PAST_M" to "past (m.)", "PAST_F" to "past (f.)", "PAST_N" to "past (n.)", "PAST_PL" to "past (pl.)"
    )
    verbNames[key]?.let { return it }
    val parts = key.split("_")
    val main = caseNames[parts.getOrNull(0)] ?: parts.getOrNull(0).orEmpty()
    val num = when (parts.getOrNull(1)) { "SG" -> "sg"; "PL" -> "pl"; else -> "" }
    return listOf(main, num).filter { it.isNotBlank() }.joinToString(" ")
}

/** Parse a note's declensionJson into ordered (label, form) rows for display. */
private fun paradigmRows(declensionJson: String?): List<Pair<String, String>> {
    if (declensionJson.isNullOrBlank()) return emptyList()
    val json = runCatching { org.json.JSONObject(declensionJson) }.getOrNull() ?: return emptyList()
    val table = when {
        json.has("cases") -> json.optJSONObject("cases")
        json.has("verbForms") -> json.optJSONObject("verbForms")
        else -> json
    } ?: return emptyList()
    val order = listOf(
        "NOM_SG", "GEN_SG", "DAT_SG", "ACC_SG", "INS_SG", "PREP_SG",
        "NOM_PL", "GEN_PL", "DAT_PL", "ACC_PL", "INS_PL", "PREP_PL",
        "FEM_NOM", "NEUT_NOM", "PL_NOM",
        "PRES_1SG", "PRES_2SG", "PRES_3SG", "PRES_1PL", "PRES_2PL", "PRES_3PL",
        "PAST_M", "PAST_F", "PAST_N", "PAST_PL"
    )
    val keys = table.keys().asSequence().toList()
    val sorted = keys.sortedBy { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    return sorted.mapNotNull { k ->
        val v = table.optString(k).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        paradigmKeyLabel(k) to v
    }
}

@Composable
private fun GrammarReferenceScreen(
    query: String,
    results: List<Note>,
    onQuery: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Grammar reference", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onClose) { Text("Done") }
        }

        // --- Word paradigm lookup ---
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            label = { Text("Look up a word's forms") },
            modifier = Modifier.fillMaxWidth()
        )
        if (query.isBlank()) {
            Text(
                "Search a deck word to inspect declension or conjugation tables. The concept cards below are always available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val withTables = results.filter { paradigmRows(it.declensionJson).isNotEmpty() }
            if (withTables.isEmpty()) {
                Text(
                    "No form table for this search yet. Try the dictionary form, or use the concept reference below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                withTables.take(5).forEach { note -> ParadigmCard(note = note, onSpeak = onSpeak) }
            }
        }

        // --- Concept browser (all grammar rules) ---
        Text("All grammar concepts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        com.sibirskyspeak.data.GrammarConcepts.ALL.forEach { concept ->
            ConceptReferenceCard(concept = concept, onSpeak = onSpeak)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ParadigmCard(note: Note, onSpeak: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(note.russian, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { onSpeak(note.russian) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear", modifier = Modifier.size(18.dp))
                }
            }
            Text(note.translation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            paradigmRows(note.declensionJson).forEach { (label, form) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(form, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ConceptReferenceCard(concept: com.sibirskyspeak.data.GrammarConcept, onSpeak: (String) -> Unit) {
    var expanded by rememberSaveable(concept.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(concept.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse concept" else "Expand concept",
                    modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 180f else 0f }
                )
            }
            if (!expanded) {
                Text(concept.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(concept.lesson, style = MaterialTheme.typography.bodyMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(concept.exampleRu, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(concept.exampleEn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onSpeak(concept.exampleRu) }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear example")
                        }
                    }
                }
            }
        }
    }
}
