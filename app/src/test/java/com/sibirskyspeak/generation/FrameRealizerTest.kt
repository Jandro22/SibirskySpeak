package com.sibirskyspeak.generation

import com.sibirskyspeak.data.ContentDao
import com.sibirskyspeak.data.ContentFrame
import com.sibirskyspeak.data.MorphAnalysisRow
import com.sibirskyspeak.data.ParadigmForm
import com.sibirskyspeak.data.RussianForms
import com.sibirskyspeak.morph.MorphologyEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Read-only ContentDao backed directly by the shipped tatoeba.db asset via JDBC — no
 * Robolectric/Room needed for a pure-JVM property test of frame realization. */
private class JdbcContentDao(private val conn: Connection) : ContentDao {
    override suspend fun candidatesForLemma(lemma: String, limit: Int) = throw NotImplementedError()
    override suspend fun chunksForLemma(lemma: String, limit: Int) = throw NotImplementedError()
    override suspend fun familyForLemma(lemma: String, limit: Int) = throw NotImplementedError()
    override suspend fun singleLetterPrefixRoots() = emptyList<String>()
    override suspend fun emojiForLemma(lemma: String) = throw NotImplementedError()
    override suspend fun neighborsForLemma(lemma: String, limit: Int) = throw NotImplementedError()
    override suspend fun metadata(key: String) = throw NotImplementedError()
    override fun paradigm(lemma: String) = throw NotImplementedError()
    override suspend fun sentencesFor(unitMax: Int, bandMax: String, requiredLemma: String?, requiredFeat: String?, limit: Int) = throw NotImplementedError()
    override suspend fun sentencesContaining(chunk: String, limit: Int) = throw NotImplementedError()
    override suspend fun dialoguesFor(unitMax: Int) = throw NotImplementedError()
    override suspend fun nodesForDialogue(dialogueId: String) = throw NotImplementedError()

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

    override suspend fun framesForConcept(conceptId: String) = throw NotImplementedError()

    override suspend fun allFrames(): List<ContentFrame> {
        val out = mutableListOf<ContentFrame>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM frame ORDER BY id").use { rs ->
                while (rs.next()) out += ContentFrame(rs.getString("id"), rs.getString("concept"), rs.getString("band"), rs.getString("slots_json"), rs.getString("ru_frame"), rs.getString("en_frame"))
            }
        }
        return out
    }
}

class FrameRealizerTest {
    private fun dbFile(): File = sequenceOf(File("src/main/assets/tatoeba.db"), File("app/src/main/assets/tatoeba.db")).first { it.exists() }
    private fun notesFile(): File = sequenceOf(File("src/main/assets/bootstrap_notes.jsonl"), File("app/src/main/assets/bootstrap_notes.jsonl")).first { it.exists() }

    private fun loadFrames(conn: Connection): List<ContentFrame> = runCatching {
        kotlinx.coroutines.runBlocking { JdbcContentDao(conn).allFrames() }
    }.getOrThrow()

    private fun loadInventory(): FrameInventory {
        val nouns = mutableListOf<FrameLexeme>(); val verbs = mutableListOf<FrameLexeme>(); val adjectives = mutableListOf<FrameLexeme>()
        notesFile().useLines { lines ->
            lines.forEach { line ->
                val row = JSONObject(line)
                if (row.optInt("tier", -1) != 0) return@forEach
                val note = FrameLexeme(
                    translation = row.optString("translation"),
                    partOfSpeech = row.optString("pos"), lemma = row.optString("lemma"),
                    gender = row.optString("gender", null), aspect = row.optString("aspect", null)
                )
                when (note.partOfSpeech) {
                    "noun" -> nouns += note
                    "verb" -> verbs += note
                    "adjective" -> adjectives += note
                }
            }
        }
        return FrameInventory(nouns, verbs, adjectives)
    }

    @Test fun realizationsAreValidPresentAndDeterministic() {
        val url = "jdbc:sqlite:${dbFile().absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            val dao = JdbcContentDao(conn)
            val morph = MorphologyEngine(dao)
            val realizer = FrameRealizer(morph)
            val frames = runCatching { kotlinx.coroutines.runBlocking { dao.allFrames() } }.getOrElse { emptyList() }
            if (frames.isEmpty()) return // frame table only exists once build_frames.py has been run
            val inventory = loadInventory()
            var attempts = 0
            var successes = 0
            for (frame in frames) {
                val slotRoles = FrameRealizer.parseSlots(frame.slotsJson).map { it.role }.toSet()
                for (epochDay in 1L..20L) {
                    for (cardId in 1L..2L) {
                        attempts++
                        val result = realizer.realize(frame, inventory, epochDay, cardId) ?: continue
                        successes++
                        assertTrue("target slot ${result.targetSlot} must be one of the frame's slots", result.targetSlot in slotRoles)
                        assertTrue(result.targetAnswer.isNotBlank())
                        assertTrue("{" !in result.ru)
                        assertTrue("{" !in result.en)
                        assertTrue("English meaning contains Cyrillic: ${result.en}", !Regex("""[\u0400-\u04FF]""").containsMatchIn(result.en))
                        // determinism: same (frame, day, card) reproduces the same output
                        val again = realizer.realize(frame, inventory, epochDay, cardId)
                        assertEquals(result, again)
                    }
                }
            }
            assertTrue("expected at least 1000 realization attempts, got $attempts", attempts >= 1000)
            assertTrue("too many frames failed to realize at all: $successes/$attempts", successes >= attempts * 8 / 10)
        }
    }

    @Test fun sameFrameDiffersAcrossDays() {
        val url = "jdbc:sqlite:${dbFile().absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            val dao = JdbcContentDao(conn)
            val realizer = FrameRealizer(MorphologyEngine(dao))
            val frames = runCatching { kotlinx.coroutines.runBlocking { dao.allFrames() } }.getOrElse { emptyList() }
            if (frames.isEmpty()) return
            val inventory = loadInventory()
            var varied = 0
            for (frame in frames) {
                val outputs = (1L..10L).mapNotNull { day -> realizer.realize(frame, inventory, day, cardId = 1L)?.ru }.toSet()
                if (outputs.size > 1) varied++
            }
            assertTrue("expected most frames to vary across days, only $varied/${frames.size} did", varied >= frames.size * 7 / 10)
        }
    }

    @Test fun morphologyNormalizationRecomposesCyrillicShortI() {
        assertEquals("край", MorphologyEngine.normalize("край"))
        assertEquals("край", RussianForms.normalize("край"))
    }

    @Test fun englishTemplateUsesMeaningsInsteadOfRussianSurfaceForms() {
        val url = "jdbc:sqlite:${dbFile().absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            val realizer = FrameRealizer(MorphologyEngine(JdbcContentDao(conn)))
            val frame = ContentFrame(
                id = "english_regression",
                concept = "NOM_PL",
                band = "A1",
                slotsJson = """[{"role":"subj","pos":"noun","case":"NOM","number":"PL","target":true},{"role":"verb","pos":"verb","tense":"PRES","person":3,"number":"PL"}]""",
                ruFrame = "{subj} {verb} здесь.",
                enFrame = "{subj} {verb} here."
            )
            val inventory = FrameInventory(
                nouns = listOf(FrameLexeme(translation = "student", partOfSpeech = "noun", lemma = "студент", gender = "M", aspect = null)),
                verbs = listOf(FrameLexeme(translation = "to work", partOfSpeech = "verb", lemma = "работать", aspect = "IPF", gender = null)),
                adjectives = emptyList()
            )

            val result = realizer.realize(frame, inventory, epochDay = 1L, cardId = 1L)

            assertEquals("Students work here.", result?.en)
            assertTrue(result?.ru?.contains("здесь") == true)
        }
    }
}
