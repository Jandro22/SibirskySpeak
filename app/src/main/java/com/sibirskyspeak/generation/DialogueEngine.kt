package com.sibirskyspeak.generation

import com.sibirskyspeak.data.ContentDialogue
import com.sibirskyspeak.data.ContentDialogueNode
import com.sibirskyspeak.review.AnswerEvaluation
import com.sibirskyspeak.review.evaluateRussianAnswer
import org.json.JSONArray

/**
 * A scripted conversation (P6.2), authored per curriculum function in
 * tools/preprocess/dialogues.json. Covers the register/pragmatics dimension
 * (ты/вы, formulaic speech) nothing else in the app touches. Turns emit
 * chunk/lemma evidence at the caller's discretion — this engine only tracks
 * state and grades the learner's typed turn against the acceptable set.
 */
data class DialogueTurn(val nodeId: String, val speaker: String, val ru: String, val en: String, val acceptable: List<String>)

class DialogueEngine(private val dialogue: ContentDialogue, nodes: List<ContentDialogueNode>) {
    private val byId = nodes.associateBy { it.id }
    private var currentId: String = nodes.first().id

    val title: String get() = dialogue.title

    fun current(): DialogueTurn = turnFor(currentId)

    /** True once the current node has no further turns (the dialogue is complete). */
    fun isComplete(): Boolean = nextIdsFor(currentId).isEmpty()

    /** Grades the learner's answer against the current node's acceptable set (near-
     * miss, not exact match — see [evaluateRussianAnswer]) and, if accepted, advances
     * past both this learner turn and the NPC turn(s) that follow it automatically. */
    fun submit(answer: String): AnswerEvaluation {
        val node = byId.getValue(currentId)
        require(node.speaker == "learner") { "submit() called on a non-learner turn" }
        val acceptable = parseAcceptable(node.acceptableJson)
        // AnswerMatch is declared EXACT, CLOSE, WRONG — lower ordinal is the better
        // match, so pick the acceptable candidate that grades best, not worst.
        val evaluation = acceptable.map { evaluateRussianAnswer(it, answer) }.minByOrNull { it.match.ordinal }
            ?: AnswerEvaluation(com.sibirskyspeak.review.AnswerMatch.WRONG, acceptable.firstOrNull().orEmpty())
        if (evaluation.accepted) advance()
        return evaluation
    }

    /** Advances past an NPC turn (no grading needed) to the next turn. */
    fun advance() {
        val nextId = nextIdsFor(currentId).firstOrNull() ?: return
        currentId = nextId
    }

    private fun turnFor(nodeId: String): DialogueTurn {
        val node = byId.getValue(nodeId)
        return DialogueTurn(node.id, node.speaker, node.ru, node.en, parseAcceptable(node.acceptableJson))
    }

    private fun nextIdsFor(nodeId: String): List<String> {
        val node = byId.getValue(nodeId)
        val array = JSONArray(node.nextIdsJson)
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun parseAcceptable(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getString(it) }
    }
}
