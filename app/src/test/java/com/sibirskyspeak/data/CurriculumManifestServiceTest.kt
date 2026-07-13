package com.sibirskyspeak.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CurriculumManifestServiceTest {
    @Test fun parsesAndCachesManifestSources() = runTest {
        var loads = 0
        val service = CurriculumManifestService {
            loads += 1
            """{"provenance":{"sources":[{"id":"demo","attribution":"Demo source","license":"CC0"}]}}"""
        }
        val first = service.provenance()
        val second = service.provenance()
        assertEquals(listOf(ContentProvenance("demo", "Demo source", "CC0")), first)
        assertEquals(first, second)
        assertEquals(1, loads)
        assertSame(first, second)
    }

    @Test fun malformedManifestFallsBackToKnownSources() = runTest {
        val sources = CurriculumManifestService { "not-json" }.provenance()
        assertEquals(setOf("tatoeba", "wiktionary", "graded-curriculum"), sources.map { it.id }.toSet())
    }
}
