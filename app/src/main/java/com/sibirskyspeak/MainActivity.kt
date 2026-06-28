package com.sibirskyspeak

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.sibirskyspeak.notify.Reminders
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sibirskyspeak.audio.RussianTextToSpeech
import com.sibirskyspeak.data.Achievement
import com.sibirskyspeak.review.ReviewViewModel
import com.sibirskyspeak.review.ReviewViewModelFactory
import com.sibirskyspeak.review.SessionStep
import kotlinx.coroutines.delay

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
internal val MainTabs = listOf(SessionStep.REVIEWS, SessionStep.DASHBOARD, SessionStep.IMPORT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewScreen(viewModel: ReviewViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tts = rememberRussianTts()
    val context = LocalContext.current
    var studyActive by rememberSaveable { mutableStateOf(false) }
    var settingsArea by rememberSaveable { mutableStateOf(SettingsArea.STUDY) }
    var showReference by rememberSaveable { mutableStateOf(false) }
    val activeTab = state.sessionStep.mainTab()
    // Manual reader browsing (reached from the Practice/Dashboard "Read" actions)
    // lives on the READER tab; in-session scheduled reading instead rides on the
    // study session. Treat both as "in the reader" for back-handling and layout.
    val inReader = (!studyActive && activeTab == SessionStep.READER) || state.inSessionReading
    BackHandler(enabled = studyActive) { studyActive = false }
    BackHandler(enabled = showReference && !studyActive) { showReference = false }
    // Back out of the manual reader: close an open text first, else return to Practice.
    BackHandler(enabled = !studyActive && activeTab == SessionStep.READER) {
        if (state.selectedReaderTextId != null) viewModel.closeReaderText()
        else viewModel.setSessionStep(SessionStep.REVIEWS)
    }

    // The reader word card stays pinned to the bottom while the story scrolls.
    val showWordCard = inReader && state.selectedToken != null
    // An open reader text renders its tokens in a LazyColumn for virtualization
    // (see ReaderTextScreen), which needs bounded height constraints from its
    // parent. That's incompatible with the shared verticalScroll Column every
    // other tab uses, so this case gets its own non-scrolling, weighted layout
    // below instead of sharing the general scrollable container.
    val readerTextOpen = inReader && state.selectedReaderTextId != null

    @Composable
    fun MainTabContent(tab: SessionStep) {
        when (tab) {
            SessionStep.RULE -> StudySessionScreen(
                state = state,
                onAnswerChanged = viewModel::setTypedAnswer,
                onChoice = viewModel::chooseAnswer,
                onReveal = viewModel::reveal,
                onRate = viewModel::rate,
                onContinue = viewModel::continueAfterRating,
                onCorrectionChanged = viewModel::setCorrectionAnswer,
                onSubmitCorrection = viewModel::submitCorrection,
                onSpeak = { p -> tts.speak(p.speechText()) },
                onExit = { studyActive = false },
                onUndo = viewModel::undoLastReview,
                onKnewIt = viewModel::overrideKnewIt,
                onSuspend = viewModel::suspendCurrentCard,
                onKnowWord = viewModel::markCurrentWordKnown,
                onStartSession = viewModel::startStudySession,
                onSaveEdit = viewModel::editCurrentCard,
                onExtraCredit = viewModel::grantExtraCredit,
                onReadNext = {
                    viewModel.startStudySession()
                    studyActive = true
                }
            )
            SessionStep.REVIEWS -> PracticeScreen(
                state = state,
                onStart = { studyActive = true },
                onRead = { viewModel.setSessionStep(SessionStep.READER) },
                onOpenReader = { id ->
                    viewModel.setSessionStep(SessionStep.READER)
                    viewModel.openReaderText(id)
                },
                onFocusedGrammar = {
                    viewModel.setSessionStep(SessionStep.BLOCKED)
                    studyActive = true
                },
                onMixedGrammar = {
                    viewModel.setSessionStep(SessionStep.INTERLEAVED)
                    studyActive = true
                }
            )
            SessionStep.READER -> ReaderPanel(
                state = state,
                onLookup = viewModel::lookupReaderToken,
                onOpen = viewModel::openReaderText,
                onClose = viewModel::closeReaderText,
                onMarkVisible = viewModel::markVisibleWords,
                onProgress = viewModel::recordReaderProgress,
                onCheckpointAnswer = viewModel::answerReaderCheckpoint,
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
                onReleaseLeech = viewModel::releaseLeech,
                onSaveLeechEdit = viewModel::editLeech
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
                },
                onFocusedGrammar = {
                    viewModel.setSessionStep(SessionStep.BLOCKED)
                    studyActive = true
                },
                onMixedGrammar = {
                    viewModel.setSessionStep(SessionStep.INTERLEAVED)
                    studyActive = true
                }
            )
        }
    }

    @Composable
    fun AnimatedMainTab(modifier: Modifier = Modifier) {
        val target = if (studyActive) {
            if (state.inSessionReading) SessionStep.READER else SessionStep.RULE
        } else activeTab
        AnimatedContent(
            modifier = modifier,
            targetState = target,
            transitionSpec = {
                val forward = tabIndex(targetState) >= tabIndex(initialState)
                val dir = if (forward) 1 else -1
                (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { dir * it / 6 })
                    .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -dir * it / 8 })
                    .using(SizeTransform(clip = false))
            },
            label = "main-tab"
        ) { tab -> MainTabContent(tab) }
    }

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
                visible = !studyActive && !readerTextOpen,
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
            if (readerTextOpen) {
                // The open-text reader virtualizes its tokens with a LazyColumn,
                // which needs a bounded-height parent instead of the shared
                // verticalScroll used below for every other tab.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    AnimatedMainTab(modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp))
                    state.statusMessage?.let {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            StatusBanner(it, onDismiss = viewModel::dismissStatusMessage)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    AnimatedMainTab()
                    state.statusMessage?.let { StatusBanner(it, onDismiss = viewModel::dismissStatusMessage) }
                    // Leave room so the story doesn't hide behind the pinned word card.
                    Spacer(Modifier.height(if (showWordCard) 300.dp else 8.dp))
                }
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
                    query = state.referenceQuery,
                    results = state.referenceResults,
                    onQuery = viewModel::setReferenceQuery,
                    onSpeak = tts::speak,
                    onClose = { showReference = false },
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }
}

@Composable
internal fun AchievementUnlockOverlay(
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
internal fun rememberRussianTts(): RussianTextToSpeech {
    val context = LocalContext.current
    val tts = remember { RussianTextToSpeech(context) }
    DisposableEffect(tts) {
        onDispose { tts.shutdown() }
    }
    return tts
}

@Composable
internal fun MainBottomBar(selected: SessionStep, onSelect: (SessionStep) -> Unit) {
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
