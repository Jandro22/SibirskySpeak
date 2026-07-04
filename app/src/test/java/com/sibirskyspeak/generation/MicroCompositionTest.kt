package com.sibirskyspeak.generation

import com.sibirskyspeak.data.ContentDao
import com.sibirskyspeak.data.ContentDialogue
import com.sibirskyspeak.data.ContentDialogueNode
import com.sibirskyspeak.data.ContentFrame
import com.sibirskyspeak.data.MorphAnalysisRow
import com.sibirskyspeak.data.ParadigmForm
import com.sibirskyspeak.morph.MorphologyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

private class JdbcContentDaoForMicroComposition(private val conn: Connection) : ContentDao {
    override suspend fun candidatesForLemma(lemma: String, limit: Int) = throw NotImplementedError()
    override suspend fun chunksForLemma(lemma: String, limit: Int) = throw NotImplementedError()
    override suspend fun familyForLemma(lemma: String, limit: Int) = throw NotImplementedError()
    override suspend fun emojiForLemma(lemma: String) = throw NotImplementedError()
    override suspend fun neighborsForLemma(lemma: String, limit: Int) = throw NotImplementedError()
    override suspend fun metadata(key: String) = throw NotImplementedError()
    override fun paradigm(lemma: String) = throw NotImplementedError()
    override suspend fun sentencesFor(unitMax: Int, requiredLemma: String?, requiredFeat: String?, limit: Int) = throw NotImplementedError()
    override suspend fun sentencesContaining(chunk: String, limit: Int) = throw NotImplementedError()
    override suspend fun framesForConcept(conceptId: String) = throw NotImplementedError()
    override suspend fun allFrames(): List<ContentFrame> = throw NotImplementedError()
    override suspend fun dialoguesFor(unitMax: Int) = throw NotImplementedError()
    override suspend fun nodesForDialogue(dialogueId: String) = throw NotImplementedError()
    override fun inflection(lemma: String, feats: String): ParadigmForm? = null

    override fun analyses(surfaceNorm: String): List<MorphAnalysisRow> {
        val out = mutableListOf<MorphAnalysisRow>()
        conn.prepareStatement("SELECT * FROM analysis WHERE surface_norm=?").use { st ->
            st.setString(1, surfaceNorm)
            st.executeQuery().use { rs ->
                while (rs.next()) out += MorphAnalysisRow(rs.getString("surface_norm"), rs.getString("lemma"), rs.getString("pos"), rs.getString("feats"))
            }
        }
        return out
    }
}

class MicroCompositionTest {
    private fun dbFile(): File = sequenceOf(File("src/main/assets/tatoeba.db"), File("app/src/main/assets/tatoeba.db")).first { it.exists() }

    @Test fun findsRequestedLemmasUsedInAnyInflectedForm() {
        val url = "jdbc:sqlite:${dbFile().absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            val present = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='analysis'"
            ).use { rs -> rs.next() && rs.getInt(1) > 0 }
            if (!present) return

            val morph = MorphologyEngine(JdbcContentDaoForMicroComposition(conn))
            val result = MicroCompositionGrader.grade(
                answer = "Вчера я читал интересную книгу дома.",
                dueLemmas = listOf("читать", "книга", "стол"),
                morph = morph
            )

            assertTrue("читать used as читал should be found", "читать" in result.foundLemmas)
            assertTrue("книга used directly should be found", "книга" in result.foundLemmas)
            assertTrue("стол never appears, should be missing", "стол" in result.missingLemmas)
            assertTrue("2 of 3 due words used should pass", result.passed)
        }
    }

    @Test fun failsWhenTooFewDueWordsAreUsed() {
        val url = "jdbc:sqlite:${dbFile().absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            val present = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='analysis'"
            ).use { rs -> rs.next() && rs.getInt(1) > 0 }
            if (!present) return

            val morph = MorphologyEngine(JdbcContentDaoForMicroComposition(conn))
            val result = MicroCompositionGrader.grade(
                answer = "Сегодня хорошая погода.",
                dueLemmas = listOf("читать", "книга", "стол"),
                morph = morph
            )

            assertEquals(0, result.foundLemmas.size)
            assertFalse(result.passed)
        }
    }
}
