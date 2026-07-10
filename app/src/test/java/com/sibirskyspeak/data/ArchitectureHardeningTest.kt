package com.sibirskyspeak.data

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureHardeningTest {
    @Test fun typedEvidenceSeparatesLookupDirectReadingAndPlacement() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.repository.addNote(Note(russian = "слово", lemma = "слово", translation = "word", partOfSpeech = "noun"))
        val textId = fixture.repository.addReaderText("Evidence", "слово")
        fixture.repository.lookupReaderToken("слово", textId, now = 10)
        val card = fixture.cards.cards.first { it.noteId == noteId && it.cardType == CardType.RU_TO_MEANING }
            .copy(state = CardState.REVIEW, due = 0, reps = 1, stability = 1.0)
        fixture.cards.update(card)
        fixture.repository.review(card, Rating.GOOD, now = 20, objectiveCorrect = true)
        fixture.repository.completeScheduledReading(textId, mistakes = 0, now = 30)

        val evidence = fixture.evidence.get(noteId)!!
        assertEquals(1, evidence.lookups)
        assertEquals(1, evidence.directRetrievals)
        assertEquals(1, evidence.completedReadings)
        assertEquals(0, evidence.placementPriors)
    }

    @Test fun undoRestoresTypedEvidenceWithCardAndLog() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.repository.addNote(Note(russian = "дом", lemma = "дом", translation = "house", partOfSpeech = "noun"))
        val card = fixture.cards.cards.first { it.noteId == noteId }.copy(state = CardState.REVIEW, reps = 1, stability = 1.0)
        fixture.cards.update(card)
        fixture.repository.review(card, Rating.GOOD, now = 100)
        assertEquals(1, fixture.evidence.get(noteId)?.directRetrievals)

        fixture.repository.undoLastReview()
        assertNull(fixture.evidence.get(noteId))
        assertTrue(fixture.logs.logs.isEmpty())
    }

    @Test fun restorePreviewRejectsTruncationAndReportsFullStateShape() = runTest {
        val fixture = RepoFixture()
        assertFalse(fixture.repository.previewImport("{\"russian\":").valid)
        fixture.repository.addNote(Note(russian = "мир", lemma = "мир", translation = "world", partOfSpeech = "noun"))
        val preview = fixture.repository.previewImport(fixture.repository.exportFullState())
        assertTrue(preview.valid)
        assertEquals(1, preview.notes)
        assertTrue(preview.cards > 0)
    }

    @Test fun automaticBackupUsesLazyLineWriter() = runTest {
        var lines = 0
        var monolithicCalled = false
        val fixture = RepoFixture(
            writeBackup = { monolithicCalled = true },
            writeBackupLines = { sequence -> sequence.forEach { lines++ } }
        )
        fixture.repository.addNote(Note(russian = "тест", lemma = "тест", translation = "test", partOfSpeech = "noun"))

        assertTrue(fixture.repository.backupNow())
        assertTrue(lines > 0)
        assertFalse(monolithicCalled)
    }

    @Test fun concurrentReviewsSerializeAndUndoOnlyTheLatestCommit() = runTest {
        val fixture = RepoFixture()
        val noteId = fixture.repository.addNote(Note(russian = "ряд", lemma = "ряд", translation = "row", partOfSpeech = "noun"))
        val card = fixture.cards.cards.first { it.noteId == noteId }.copy(state = CardState.REVIEW, reps = 1, stability = 1.0)
        fixture.cards.update(card)

        listOf(100L, 200L).map { at -> async { fixture.repository.review(card, Rating.GOOD, at) } }.awaitAll()
        assertEquals(2, fixture.logs.logs.size)
        fixture.repository.undoLastReview()
        assertEquals(1, fixture.logs.logs.size)
        assertEquals(1, fixture.evidence.get(noteId)?.directRetrievals)
    }
}
