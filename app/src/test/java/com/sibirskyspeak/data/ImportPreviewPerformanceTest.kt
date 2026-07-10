package com.sibirskyspeak.data

import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPreviewPerformanceTest {
    @Test fun previewScalesToLargeDeckWithoutQuadraticParsing() {
        val payload = (1..10_000).joinToString("\n") { index ->
            """{"russian":"слово$index","lemma":"слово$index","translation":"word $index","pos":"noun"}"""
        }
        lateinit var preview: ImportPreview
        val elapsed = measureTimeMillis { preview = ImportPreviewer.preview(payload) }
        assertEquals(10_000, preview.notes)
        assertTrue("10k-line preview took ${elapsed}ms", elapsed < 5_000)
    }
}
