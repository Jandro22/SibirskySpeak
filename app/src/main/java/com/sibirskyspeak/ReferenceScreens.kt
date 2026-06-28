package com.sibirskyspeak

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sibirskyspeak.data.GrammarConcept
import com.sibirskyspeak.data.GrammarConcepts
import com.sibirskyspeak.data.Note

// --- Grammar reference ------------------------------------------------------

/** Human label for a declension/conjugation table key (e.g. GEN_SG -> "Genitive sg"). */
internal fun paradigmKeyLabel(key: String): String {
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
internal fun paradigmRows(declensionJson: String?): List<Pair<String, String>> {
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
internal fun GrammarReferenceScreen(
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
            OutlinedButton(onClick = onClose) { Text(stringResource(R.string.action_done)) }
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
internal fun ParadigmCard(note: Note, onSpeak: (String) -> Unit) {
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
internal fun ConceptReferenceCard(concept: com.sibirskyspeak.data.GrammarConcept, onSpeak: (String) -> Unit) {
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
