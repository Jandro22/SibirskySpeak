package com.sibirskyspeak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun OnboardingPanel(
    onStartAtBeginning: () -> Unit,
    onTakePlacement: () -> Unit
) {
    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.onboarding_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your first session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("A few new words, one clear grammar idea, and a quick recall check.", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Button(
            onClick = onStartAtBeginning,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.ONBOARDING_BEGINNER)
        ) {
            Column {
                Text(stringResource(R.string.onboarding_beginner), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.onboarding_beginner_support), style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(
            onClick = onTakePlacement,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.ONBOARDING_PLACEMENT)
        ) {
            Column {
                Text(stringResource(R.string.onboarding_know_some), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.onboarding_know_some_support), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(R.string.onboarding_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
