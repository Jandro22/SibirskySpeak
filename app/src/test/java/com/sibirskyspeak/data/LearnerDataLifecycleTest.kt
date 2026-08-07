package com.sibirskyspeak.data

import com.sibirskyspeak.review.FakeSettingsStore
import dagger.Lazy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerDataLifecycleTest {
    @Test
    fun primaryCheckpointForcesNewBackupShapeAndThenHonorsDailyThrottle() = runTest {
        val written = mutableListOf<String>()
        val fixture = RepoFixture(writeBackupLines = { lines -> written += lines.toList() })
        val noteId = fixture.notes.insert(Note(
            russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun",
            tier = 0, unit = 1, cefrLevel = "A1"
        ))
        fixture.communicativeLearning.upsertComponent(KnowledgeComponent(
            key = ComponentKeys.form(noteId), kind = "FORM", capabilityKey = "A1:1",
            band = "A1", unit = 1, noteId = noteId, reps = 2
        ))
        val settings = FakeSettingsStore().apply {
            lastBackupAt = 1_000L
            backupDataVersion = 0
        }
        val lifecycle = LearnerDataLifecycle(Lazy { fixture.repository }, settings)

        assertTrue(lifecycle.checkpointIfDue(now = 2_000L))
        assertTrue(written.any { it.contains("\"kind\":\"knowledgeComponent\"") })
        assertEquals(PRIMARY_BACKUP_DATA_VERSION, settings.backupDataVersion)
        assertEquals(2_000L, settings.lastBackupAt)

        val linesAfterFirstWrite = written.size
        assertFalse(lifecycle.checkpointIfDue(now = 2_000L + PRIMARY_BACKUP_INTERVAL_MS - 1))
        assertEquals(linesAfterFirstWrite, written.size)
    }

    @Test
    fun failedCheckpointDoesNotAdvanceSuccessMarkers() = runTest {
        val fixture = RepoFixture(writeBackupLines = { error("disk unavailable") })
        fixture.notes.insert(Note(russian = "мир", lemma = "мир", translation = "world", partOfSpeech = "noun"))
        val settings = FakeSettingsStore().apply {
            lastBackupAt = 7L
            backupDataVersion = 0
        }
        val lifecycle = LearnerDataLifecycle(Lazy { fixture.repository }, settings)

        val result = runCatching { lifecycle.checkpointIfDue(now = 9L) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(7L, settings.lastBackupAt)
        assertEquals(0, settings.backupDataVersion)
    }

    @Test
    fun backupDueHandlesClockRollbackWithoutSuppressingShapeUpgrade() {
        assertTrue(learnerBackupDue(lastBackupAt = 50_000L, backupDataVersion = 0, now = 1_000L))
        assertFalse(learnerBackupDue(
            lastBackupAt = 50_000L,
            backupDataVersion = PRIMARY_BACKUP_DATA_VERSION,
            now = 1_000L
        ))
    }
}
