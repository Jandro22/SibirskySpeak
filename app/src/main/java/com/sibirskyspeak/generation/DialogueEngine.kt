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
data class DialogueTurn(
    val nodeId: String,
    val speaker: String,
    val ru: String,
    val en: String,
    val acceptable: List<String>,
    /** Authored consequence shown after each valid communicative choice. */
    val responseFeedback: Map<String, String> = emptyMap(),
    /** Branch-specific Russian fact the learner must retain for a later check. */
    val responseFacts: Map<String, String> = emptyMap()
)

class DialogueEngine(private val dialogue: ContentDialogue, nodes: List<ContentDialogueNode>) {
    private val byId = nodes.associateBy { it.id }
    private val rootId: String = nodes.map { it.id }.first { candidate ->
        nodes.none { candidate in nextIdsForNode(it) }
    }
    private var currentId: String = rootId

    val title: String get() = dialogue.title

    fun current(): DialogueTurn = turnFor(currentId)

    /** Deterministic first branch, suitable for an assigned assessment. */
    fun scriptedTurns(): List<DialogueTurn> {
        val result = mutableListOf<DialogueTurn>()
        val visited = mutableSetOf<String>()
        var id: String? = rootId
        while (id != null && visited.add(id)) {
            result += turnFor(id)
            id = nextIdsFor(id).firstOrNull()
        }
        return result
    }

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
        val best = acceptable.mapIndexed { index, candidate -> index to evaluateRussianAnswer(candidate, answer) }
            .minByOrNull { it.second.match.ordinal }
        val evaluation = best?.second
            ?: AnswerEvaluation(com.sibirskyspeak.review.AnswerMatch.WRONG, acceptable.firstOrNull().orEmpty())
        if (evaluation.accepted) {
            val next = nextIdsFor(currentId)
            if (next.isNotEmpty()) {
                // Acceptable answers are authored in contiguous branch groups
                // (stress/punctuation variants stay on the same consequence).
                val branch = ((best!!.first * next.size) / acceptable.size).coerceAtMost(next.lastIndex)
                currentId = next[branch]
            }
        }
        return evaluation
    }

    /** Advances past an NPC turn (no grading needed) to the next turn. */
    fun advance() {
        val nextId = nextIdsFor(currentId).firstOrNull() ?: return
        currentId = nextId
    }

    private fun turnFor(nodeId: String): DialogueTurn {
        val node = byId.getValue(nodeId)
        val acceptable = parseAcceptable(node.acceptableJson)
        val next = nextIdsForNode(node)
        val consequenceByAnswer = if (node.speaker == "learner" && acceptable.isNotEmpty() && next.isNotEmpty()) {
            acceptable.mapIndexedNotNull { index, answer ->
                val branch = ((index * next.size) / acceptable.size).coerceAtMost(next.lastIndex)
                byId[next[branch]]?.let { consequence ->
                    answer to consequence
                }
            }.toMap()
        } else emptyMap()
        val feedback = consequenceByAnswer.mapValues { (_, consequence) ->
            listOf(consequence.ru, consequence.en).filter(String::isNotBlank).joinToString(" — ")
        }
        val facts = consequenceByAnswer.mapValues { (_, consequence) -> consequence.ru }
        return DialogueTurn(node.id, node.speaker, node.ru, node.en, acceptable, feedback, facts)
    }

    private fun nextIdsFor(nodeId: String): List<String> {
        val node = byId.getValue(nodeId)
        return nextIdsForNode(node)
    }

    private fun nextIdsForNode(node: ContentDialogueNode): List<String> {
        val array = JSONArray(node.nextIdsJson)
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun parseAcceptable(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getString(it) }
    }
}
