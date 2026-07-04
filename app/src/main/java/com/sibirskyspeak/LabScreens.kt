package com.sibirskyspeak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sibirskyspeak.review.ReviewUiState

@Composable internal fun LabPanel(state: ReviewUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Learning Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Diagnostics and experiments; FSRS remains the scheduler.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SkillRadarCard(state.skillRatings)
        RivalProgressCard(state.rivalState, state.matchHistory)
        state.dashboardStats?.let { DetailsSection(it, expanded = true, onToggle = {}) }
        state.weeklyReports.firstOrNull()?.let { report ->
            SectionCard { Text("Weekly letter", style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold); Text(report.bodyJson) }
        }
    }
}
