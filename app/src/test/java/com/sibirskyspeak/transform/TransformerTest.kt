package com.sibirskyspeak.transform

import com.sibirskyspeak.data.ContentDao
import com.sibirskyspeak.data.ContentFrame
import com.sibirskyspeak.data.MorphAnalysisRow
import com.sibirskyspeak.data.ParadigmForm
import com.sibirskyspeak.morph.MorphologyEngine
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Read-only ContentDao backed directly by the shipped tatoeba.db asset via JDBC —
 * mirrors generation/FrameRealizerTest's harness for a pure-JVM property test. */
private class JdbcContentDao(private val conn: Connection) : ContentDao {
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
    override suspend fun dialoguesFor(unitMax: Int) = throw NotImplementedError()
    override suspend fun nodesForDialogue(dialogueId: String) = throw NotImplementedError()
    override suspend fun allFrames(): List<ContentFrame> = throw NotImplementedError()

    override fun inflection(lemma: String, feats: String): ParadigmForm? {
        conn.prepareStatement("SELECT * FROM paradigm WHERE lemma=? AND feats=? ORDER BY surface LIMIT 1").use { st ->
            st.setString(1, lemma); st.setString(2, feats)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                return ParadigmForm(rs.getString("lemma"), rs.getString("pos"), rs.getString("feats"), rs.getString("surface"), rs.getString("stressed"))
            }
        }
    }

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

class TransformerTest {
    private fun dbFile(): File = sequenceOf(File("src/main/assets/tatoeba.db"), File("app/src/main/assets/tatoeba.db")).first { it.exists() }
    private fun notesFile(): File = sequenceOf(File("src/main/assets/bootstrap_notes.jsonl"), File("app/src/main/assets/bootstrap_notes.jsonl")).first { it.exists() }

    private fun tier0VerbLemmas(limit: Int): List<String> {
        val lemmas = mutableListOf<String>()
        notesFile().useLines { lines ->
            for (line in lines) {
                val row = JSONObject(line)
                if (row.optInt("tier", -1) == 0 && row.optString("pos") == "verb") {
                    lemmas += row.optString("lemma")
                    if (lemmas.size >= limit) break
                }
            }
        }
        return lemmas
    }

    @Test fun negatesRealSentencesForMostCommonVerbs() {
        val url = "jdbc:sqlite:${dbFile().absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            val present = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='paradigm'"
            ).use { rs -> rs.next() && rs.getInt(1) > 0 }
            if (!present) return // paradigm table only exists once build_paradigms.py has been run

            val dao = JdbcContentDao(conn)
            val morph = MorphologyEngine(dao)
            val lemmas = tier0VerbLemmas(120)
            var attempts = 0
            var successes = 0
            for (lemma in lemmas) {
                val sentenceIds = mutableListOf<Long>()
                conn.prepareStatement("SELECT sentence_id FROM lemma_index WHERE lemma=? AND pos='VERB' LIMIT 5").use { st ->
                    st.setString(1, lemma)
                    st.executeQuery().use { rs -> while (rs.next()) sentenceIds += rs.getLong(1) }
                }
                for (sid in sentenceIds) {
                    val ruPlain = conn.prepareStatement("SELECT ru_plain FROM sentence WHERE id=?").use { st ->
                        st.setLong(1, sid)
                        st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                    } ?: continue
                    attempts++
                    val result = Transformer.negate(ruPlain, lemma, morph) ?: continue
                    successes++
                    assertTrue("result must differ from original", result.result != result.original)
                    assertTrue("expected answer must be non-blank", result.expectedAnswer.isNotBlank())
                    // The transform must be a pure single-token insertion: the result's
                    // words equal the original's words with exactly one extra "не"/"Не"
                    // token spliced in (word-boundary tokenization, not substring search,
                    // since "не" is a substring of unrelated words like "Мне").
                    val wordRegex = Regex("[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?")
                    val originalWords = wordRegex.findAll(result.original).map { it.value.lowercase() }.toList()
                    val resultWords = wordRegex.findAll(result.result).map { it.value.lowercase() }.toList()
                    assertTrue("result must have exactly one more word than original: $originalWords -> $resultWords",
                        resultWords.size == originalWords.size + 1)
                    // Find the first point of divergence (the sentence may already
                    // contain other instances of "не" unrelated to this transform, so
                    // don't just search for any "не" — walk both lists in lockstep).
                    val divergeAt = originalWords.indices.firstOrNull { resultWords[it] != originalWords[it] } ?: originalWords.size
                    assertTrue("the inserted word must be не at the point of divergence", resultWords[divergeAt] == "не")
                    assertTrue("everything after the inserted не must match the original tail unchanged",
                        resultWords.drop(divergeAt + 1) == originalWords.drop(divergeAt))
                }
            }
            assertTrue("expected at least 40 real (lemma, sentence) attempts, got $attempts", attempts >= 40)
            assertTrue("too few successful negations: $successes/$attempts", successes >= attempts / 4)
        }
    }
}
