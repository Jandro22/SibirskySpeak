package com.sibirskyspeak

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sibirskyspeak.audio.RussianTextToSpeech
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.review.AnswerMode
import com.sibirskyspeak.review.ReviewPrompt
import com.sibirskyspeak.review.ReviewUiState
import com.sibirskyspeak.review.ReviewViewModel
import com.sibirskyspeak.review.ReviewViewModelFactory
import com.sibirskyspeak.review.SessionStep

class MainActivity : ComponentActivity() {
    private val reviewViewModel: ReviewViewModel by viewModels {
        ReviewViewModelFactory((application as SibirskySpeakApp).repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SibirskySpeakTheme {
                ReviewScreen(reviewViewModel)
            }
        }
    }
}

@Composable
private fun SibirskySpeakTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(
            primary = Color(0xFF8BE0D4),
            onPrimary = Color(0xFF003735),
            secondary = Color(0xFFFFCA7A),
            tertiary = Color(0xFFE8B4CB),
            background = Color(0xFF0E1417),
            surface = Color(0xFF121A1E),
            surfaceVariant = Color(0xFF253238),
            onSurface = Color(0xFFE8F0F2),
            onSurfaceVariant = Color(0xFFB6C8CC),
            outline = Color(0xFF405259),
            error = Color(0xFFFFB4AB)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF246D69),
            secondary = Color(0xFF7D5B16),
            tertiary = Color(0xFF7D5260),
            background = Color(0xFFF6F8F6),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE4ECEA),
            onSurface = Color(0xFF162022),
            onSurfaceVariant = Color(0xFF536265),
            outline = Color(0xFFB8C7C5),
            error = Color(0xFFBA1A1A)
        )
    }

    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(viewModel: ReviewViewModel) {
    val state by viewModel.state.collectAsState()
    val tts = rememberRussianTts()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SibirskySpeak", fontWeight = FontWeight.SemiBold)
                        Text("Russian review and reader", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text("${state.reviewedToday} today${if (state.dailyPlan?.triageMode == true) " - triage" else ""}")
                        },
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                DailyPlanPanel(state)
                StepSwitcher(state, viewModel::setSessionStep)
                state.statusMessage?.let { StatusBanner(it) }

                when (state.sessionStep) {
                    SessionStep.RULE -> RulePanel(state)
                    SessionStep.READER -> ReaderPanel(
                        state = state,
                        onLookup = viewModel::lookupReaderToken,
                        onTitle = viewModel::setReaderTitle,
                        onBody = viewModel::setReaderBody,
                        onAdd = viewModel::addReaderText
                    )
                    SessionStep.IMPORT -> ImportExportPanel(
                        state = state,
                        onImportText = viewModel::setImportText,
                        onImport = viewModel::importJsonLines,
                        onExport = viewModel::exportJsonLines,
                        onFullBackup = viewModel::exportFullState
                    )
                    SessionStep.DASHBOARD -> DashboardPanel(state)
                    SessionStep.REVIEWS, SessionStep.BLOCKED, SessionStep.INTERLEAVED -> {
                        val prompt = state.prompt
                        if (prompt == null) {
                            EmptyQueue(state.sessionStep)
                        } else {
                            ReviewContent(
                                state = state,
                                prompt = prompt,
                                onAnswerChanged = viewModel::setTypedAnswer,
                                onChoice = viewModel::chooseAnswer,
                                onReveal = viewModel::reveal,
                                onRate = viewModel::rate,
                                onSpeak = { tts.speak(prompt.note.russian) }
                            )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepSwitcher(state: ReviewUiState, onStep: (SessionStep) -> Unit) {
    SectionCard {
        Text("Workspace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SessionStep.values().forEach { step ->
                FilterChip(
                    selected = step == state.sessionStep,
                    onClick = { onStep(step) },
                    label = { Text(step.label()) }
                )
            }
        }
    }
}

@Composable
private fun DailyPlanPanel(state: ReviewUiState) {
    val plan = state.dailyPlan ?: return
    val totalDue = plan.dueVocab + plan.dueGrammar
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Today's Plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (totalDue == 0) "No scheduled cards due" else "$totalDue cards waiting",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatPill("${state.reviewedToday}", "done")
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatPill("${plan.dueVocab}", "vocab")
            StatPill("${plan.dueGrammar}", "grammar")
        }
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { if (totalDue == 0) 1f else state.reviewedToday.toFloat() / (state.reviewedToday + totalDue) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
        )
        plan.openBlockedWith?.let {
            Spacer(Modifier.height(12.dp))
            Text("Blocked intro: ${it.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (plan.grammarFocus.isNotEmpty()) {
            Text(
                "Grammar focus: ${plan.grammarFocus.joinToString { it.label }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RulePanel(state: ReviewUiState) {
    SectionCard {
        Text("Brief Rule", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            state.sessionPlan?.ruleSummary ?: "Answer from production first; reveal only after committing to a form.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewContent(
    state: ReviewUiState,
    prompt: ReviewPrompt,
    onAnswerChanged: (String) -> Unit,
    onChoice: (String) -> Unit,
    onReveal: () -> Unit,
    onRate: (Rating) -> Unit,
    onSpeak: () -> Unit
) {
    SectionCard(emphasis = true) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(prompt.card.queue.name.lowercase().replaceFirstChar { it.titlecase() }) })
            AssistChip(onClick = {}, label = { Text(prompt.card.cardType.name.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }) })
            AssistChip(onClick = onSpeak, label = { Text("Play audio") })
        }
        Spacer(Modifier.height(18.dp))
        Text(
            prompt.prompt.ifBlank { "Listen and type what you hear" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        prompt.note.exampleTranslation?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(18.dp))
        if (prompt.answerMode == AnswerMode.CHOICE && !state.revealed) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                prompt.choices.forEach { choice ->
                    Button(onClick = { onChoice(choice) }, shape = RoundedCornerShape(10.dp)) { Text(choice) }
                }
            }
        } else if (prompt.answerMode != AnswerMode.AUDIO_ONLY || !state.revealed) {
            OutlinedTextField(
                value = state.typedAnswer,
                onValueChange = onAnswerChanged,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("Your answer") },
                supportingText = { Text("Type Russian or English depending on the prompt.") }
            )
        }
        Spacer(Modifier.height(4.dp))
        if (!state.revealed) {
            Button(onClick = onReveal, modifier = Modifier.align(Alignment.End), shape = RoundedCornerShape(10.dp)) {
                Text("Reveal Answer")
            }
        } else {
            RevealPanel(state, prompt, onRate)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RevealPanel(state: ReviewUiState, prompt: ReviewPrompt, onRate: (Rating) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ResultBanner(state, prompt)
        prompt.explanation?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Rate recall", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Rating.entries.forEach { rating ->
                Button(onClick = { onRate(rating) }, shape = RoundedCornerShape(10.dp)) {
                    Text("${rating.name.lowercase().replaceFirstChar { it.titlecase() }} - ${formatDays(prompt.intervalPreview[rating] ?: 0)}")
                }
            }
        }
    }
}

@Composable
private fun ResultBanner(state: ReviewUiState, prompt: ReviewPrompt) {
    val matched = state.isAnswerCorrect == true
    val color = if (matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = color.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (matched) "Matched" else "Expected answer", style = MaterialTheme.typography.labelLarge, color = color)
            Text(if (matched) prompt.expectedAnswer else prompt.expectedAnswer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderPanel(
    state: ReviewUiState,
    onLookup: (String) -> Unit,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onAdd: () -> Unit
) {
    val recommendation = state.readerRecommendation
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard {
            Text("Reader", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (recommendation == null) {
                Text("Add a Russian text below to estimate coverage and look up tokens.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(recommendation.text.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatPill("${(recommendation.coverage * 100).toInt()}%", "coverage")
                    StatPill("${recommendation.knownTokens}/${recommendation.totalTokens}", "known")
                }
                Spacer(Modifier.height(12.dp))
                if (recommendation.authenticReady) {
                    StatusBanner("Authentic transition ready: this target-source sample is at or above 90% coverage.")
                }
                Text(recommendation.text.body, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.readerTokens.distinctBy { it.surface }.take(28).forEach { token ->
                        FilterChip(
                            selected = token.known,
                            onClick = { onLookup(token.surface) },
                            label = { Text("${token.surface}${if (token.known) " ok" else ""}") }
                        )
                    }
                }
                state.lookupResult?.let {
                    Spacer(Modifier.height(8.dp))
                    StatusBanner(it)
                }
                val parsed = state.readerTokens.filter { it.known }.take(6)
                if (parsed.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        parsed.joinToString("\n") { token ->
                            "${token.surface}: ${token.lemma ?: "?"} - ${token.parse ?: "parse"} - ${token.translation ?: ""}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (state.allReaderTexts.isNotEmpty()) {
            SectionCard {
                Text("Saved Texts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    state.allReaderTexts.joinToString("\n") { "${it.text.title} - ${(it.coverage * 100).toInt()}% coverage" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SectionCard {
            Text("Add Text", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = state.readerTitle, onValueChange = onTitle, modifier = Modifier.fillMaxWidth(), label = { Text("Text title") })
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = state.readerBody, onValueChange = onBody, modifier = Modifier.fillMaxWidth(), minLines = 4, label = { Text("Russian text") })
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAdd, modifier = Modifier.align(Alignment.End), shape = RoundedCornerShape(10.dp)) { Text("Add Text") }
        }
    }
}

@Composable
private fun DashboardPanel(state: ReviewUiState) {
    val stats = state.dashboardStats ?: return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard {
            Text("Dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            FlowRowWithStats(
                "Notes" to stats.noteCount.toString(),
                "Vocab" to stats.vocabCards.toString(),
                "Grammar" to stats.grammarCards.toString(),
                "Due vocab" to stats.dueVocab.toString(),
                "Due grammar" to stats.dueGrammar.toString(),
                "Reviewed" to stats.reviewedToday.toString(),
                "Reader avg" to "${(stats.averageReaderCoverage * 100).toInt()}%"
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (stats.authenticReady) "Authentic text transition is ready." else "Authentic transition waits for a target-source sample at 90%+ coverage.",
                color = if (stats.authenticReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionCard {
            val report = stats.importQualityReport
            Text("Import Readiness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            FlowRowWithStats(
                "Noun rows" to "${report.readyNominalRows}/${report.minNominalRows}",
                "Aspect verbs" to "${report.aspectReadyVerbRows}/${report.minVerbRows}",
                "Aktionsart" to "${report.verifiedAktionsartVerbRows}/${report.minVerbRows}",
                "Ranked" to report.domainRankedRows.toString(),
                "Examples" to report.exampleRows.toString(),
                "90% texts" to report.targetTextsAtOrAbove90.toString()
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (report.meetsDesignDocMinimum) "Design-doc dataset minimum is satisfied." else "Dataset is still below the design-doc minimum.",
                color = if (report.meetsDesignDocMinimum) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            report.warnings.take(4).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportExportPanel(
    state: ReviewUiState,
    onImportText: (String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onFullBackup: () -> Unit
) {
    SectionCard {
        Text("Import / Export", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Paste JSON Lines to import notes or restore a full backup. Export saves content only; Full Backup includes scheduling state.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = state.importText, onValueChange = onImportText, modifier = Modifier.fillMaxWidth(), minLines = 6, label = { Text("JSON Lines notes") })
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onImport, shape = RoundedCornerShape(10.dp)) { Text("Import") }
            OutlinedButton(onClick = onExport, shape = RoundedCornerShape(10.dp)) { Text("Export") }
            OutlinedButton(onClick = onFullBackup, shape = RoundedCornerShape(10.dp)) { Text("Full Backup") }
        }
        if (state.exportText.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = state.exportText, onValueChange = {}, modifier = Modifier.fillMaxWidth(), minLines = 6, label = { Text("Exported JSON Lines") })
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
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${step.label()} queue is clear", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Cards will appear here when due or after import.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionCard(emphasis: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (emphasis) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun StatusBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StatPill(value: String, label: String) {
    OutlinedCard(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowWithStats(vararg stats: Pair<String, String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.forEach { (label, value) -> StatPill(value, label) }
    }
}

private fun SessionStep.label(): String =
    when (this) {
        SessionStep.REVIEWS -> "Reviews"
        SessionStep.RULE -> "Rule"
        SessionStep.BLOCKED -> "Blocked"
        SessionStep.INTERLEAVED -> "Interleaved"
        SessionStep.READER -> "Reader"
        SessionStep.IMPORT -> "Import"
        SessionStep.DASHBOARD -> "Dashboard"
    }

private fun formatDays(days: Int): String =
    when (days) {
        0 -> "10m"
        1 -> "1d"
        else -> "${days}d"
    }
