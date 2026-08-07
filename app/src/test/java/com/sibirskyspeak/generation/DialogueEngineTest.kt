package com.sibirskyspeak.generation

import com.sibirskyspeak.data.ContentDialogue
import com.sibirskyspeak.data.ContentDialogueNode
import com.sibirskyspeak.review.AnswerMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class DialogueEngineTest {
    private fun dbFile(): File = sequenceOf(File("src/main/assets/tatoeba.db"), File("app/src/main/assets/tatoeba.db")).first { it.exists() }

    private fun loadShippedDialogue(): Pair<ContentDialogue, List<ContentDialogueNode>>? {
        val url = "jdbc:sqlite:${dbFile().absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            val present = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='dialogue'"
            ).use { rs -> rs.next() && rs.getInt(1) > 0 }
            if (!present) return null
            val dialogue = conn.createStatement().executeQuery("SELECT * FROM dialogue LIMIT 1").use { rs ->
                if (!rs.next()) return null
                ContentDialogue(rs.getString("id"), rs.getInt("unit_min"), rs.getString("function"), rs.getString("title"))
            }
            val nodes = mutableListOf<ContentDialogueNode>()
            conn.prepareStatement("SELECT * FROM dialogue_node WHERE dialogueId=?").use { st ->
                st.setString(1, dialogue.id)
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        nodes += ContentDialogueNode(
                            rs.getString("id"), rs.getString("dialogueId"), rs.getString("speaker"),
                            rs.getString("ru"), rs.getString("en"), rs.getString("acceptable_json"), rs.getString("next_ids_json")
                        )
                    }
                }
            }
            return dialogue to nodes
        }
    }

    @Test fun walksTheShippedDialogueToCompletionOnAcceptedAnswers() {
        val (dialogue, nodes) = loadShippedDialogue() ?: return // gated separately when regenerating the asset
        val engine = DialogueEngine(dialogue, nodes)

        var guard = 0
        while (!engine.isComplete() && guard < 100) {
            val turn = engine.current()
            if (turn.speaker == "learner") {
                val evaluation = engine.submit(turn.acceptable.first())
                assertTrue("the node's own first acceptable answer must be accepted", evaluation.accepted)
            } else {
                engine.advance()
            }
            guard++
        }
        assertTrue("dialogue should reach completion within a bounded number of turns", engine.isComplete())
    }

    @Test fun rejectsAWrongAnswerAndDoesNotAdvance() {
        val (dialogue, nodes) = loadShippedDialogue() ?: return
        val engine = DialogueEngine(dialogue, nodes)
        while (engine.current().speaker != "learner" && !engine.isComplete()) engine.advance()
        if (engine.isComplete()) return
        val before = engine.current().nodeId

        val evaluation = engine.submit("совершенно неправильный ответ")

        assertEquals(AnswerMatch.WRONG, evaluation.match)
        assertFalse(evaluation.accepted)
        assertEquals("a rejected answer must not advance the dialogue", before, engine.current().nodeId)
    }

    @Test fun equivalentStressVariantsAdvanceAlongTheSameSourcedArc() {
        val (dialogue, nodes) = loadShippedDialogue() ?: return
        fun atFirstLearner(): DialogueEngine = DialogueEngine(dialogue, nodes).also { engine ->
            while (engine.current().speaker != "learner" && !engine.isComplete()) engine.advance()
        }
        val first = atFirstLearner()
        val alternatives = first.current().acceptable
        assertTrue(alternatives.size >= 2)
        first.submit(alternatives.first())
        val firstConsequence = first.current().nodeId

        val second = atFirstLearner()
        second.submit(alternatives.last())
        val secondConsequence = second.current().nodeId

        assertEquals("equivalent written variants should follow the same sourced arc", firstConsequence, secondConsequence)
    }
}
