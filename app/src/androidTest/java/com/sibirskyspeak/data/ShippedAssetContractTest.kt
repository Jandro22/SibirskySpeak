package com.sibirskyspeak.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Loads the exact packaged JSONL/Kotlin concept boundary on a device. */
@RunWith(AndroidJUnit4::class)
class ShippedAssetContractTest {
    @Test
    fun shippedBootstrapNotesAndConceptsAreReadable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val contract = context.assets.open("curriculum_contract.json").bufferedReader().use { JSONObject(it.readText()) }
        assertTrue(contract.getInt("contractVersion") >= 1)

        val required = contract.getJSONArray("noteRequiredFields").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        var rows = 0
        context.assets.open("bootstrap_notes.jsonl").bufferedReader().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val note = JSONObject(line)
            required.forEach { field -> assertTrue("missing $field", note.has(field) && note.optString(field).isNotBlank()) }
            rows++
        }
        assertTrue(rows > 0)
    }
}
