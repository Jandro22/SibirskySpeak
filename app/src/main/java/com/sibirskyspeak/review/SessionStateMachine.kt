package com.sibirskyspeak.review

import org.json.JSONArray
import org.json.JSONObject

/** Explicit, serializable session state used to make lifecycle/race behavior testable. */
enum class SessionPhase {
    IDLE,
    ANSWERING,
    REVEALED,
    CORRECTION,
    PAUSED,
    COMPLETED,
    STOPPED
}

data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val queueCardIds: List<Long> = emptyList(),
    val currentCardId: Long? = null,
    val sessionId: String? = null,
    val startedAt: Long = 0L,
    val reviewed: Int = 0,
    val correct: Int = 0,
    val completedActions: Int = 0
) {
    val isActive: Boolean get() = phase != SessionPhase.IDLE && phase != SessionPhase.COMPLETED && queueCardIds.isNotEmpty()

    fun toJson(): String = JSONObject()
        .put("phase", phase.name)
        .put("queueCardIds", JSONArray(queueCardIds))
        .put("currentCardId", currentCardId)
        .put("sessionId", sessionId)
        .put("startedAt", startedAt)
        .put("reviewed", reviewed)
        .put("correct", correct)
        .put("completedActions", completedActions)
        .toString()

    companion object {
        fun fromJson(raw: String): SessionState? = runCatching {
            if (raw.isBlank()) return null
            val json = JSONObject(raw)
            val phase = SessionPhase.valueOf(json.optString("phase"))
            val idsJson = json.optJSONArray("queueCardIds") ?: return null
            val ids = (0 until idsJson.length()).map { idsJson.getLong(it) }.distinct()
            if (ids.isEmpty()) return null
            SessionState(
                phase = phase.takeIf { it != SessionPhase.IDLE && it != SessionPhase.COMPLETED } ?: return null,
                queueCardIds = ids,
                currentCardId = json.optLong("currentCardId").takeIf { it > 0L },
                sessionId = json.optString("sessionId").takeIf { it.isNotBlank() },
                startedAt = json.optLong("startedAt").coerceAtLeast(0L),
                reviewed = json.optInt("reviewed").coerceAtLeast(0),
                correct = json.optInt("correct").coerceAtLeast(0),
                completedActions = json.optInt("completedActions").coerceAtLeast(0)
            )
        }.getOrNull()
    }
}

sealed interface SessionEvent {
    data class Start(val queueCardIds: List<Long>, val sessionId: String, val startedAt: Long) : SessionEvent
    data object Reveal : SessionEvent
    data object RequireCorrection : SessionEvent
    data object AcceptCorrection : SessionEvent
    data class ReviewCommitted(val nextCardId: Long?, val reviewed: Int, val correct: Int) : SessionEvent
    data class Pause(val at: Long) : SessionEvent
    data object Resume : SessionEvent
    data class Stop(val remaining: Int) : SessionEvent
    data object Clear : SessionEvent
}

/** Pure reducer: repeated taps and lifecycle events become harmless event sequences. */
object SessionReducer {
    fun reduce(state: SessionState, event: SessionEvent): SessionState = when (event) {
        is SessionEvent.Start -> {
            val ids = event.queueCardIds.distinct()
            if (ids.isEmpty()) SessionState()
            else SessionState(
                phase = SessionPhase.ANSWERING,
                queueCardIds = ids,
                currentCardId = ids.first(),
                sessionId = event.sessionId,
                startedAt = event.startedAt
            )
        }
        SessionEvent.Reveal -> state.takeIf { it.phase == SessionPhase.ANSWERING }
            ?.copy(phase = SessionPhase.REVEALED) ?: state
        SessionEvent.RequireCorrection -> state.takeIf { it.phase == SessionPhase.REVEALED }
            ?.copy(phase = SessionPhase.CORRECTION) ?: state
        SessionEvent.AcceptCorrection -> state.takeIf { it.phase == SessionPhase.CORRECTION }
            ?.copy(phase = SessionPhase.REVEALED) ?: state
        is SessionEvent.ReviewCommitted -> {
            val remaining = state.queueCardIds.filterNot { it == state.currentCardId }
            val next = event.nextCardId?.takeIf { it in remaining } ?: remaining.firstOrNull()
            state.copy(
                phase = if (next == null) SessionPhase.COMPLETED else SessionPhase.ANSWERING,
                queueCardIds = remaining,
                currentCardId = next,
                reviewed = (state.reviewed + event.reviewed).coerceAtLeast(0),
                correct = (state.correct + event.correct).coerceAtLeast(0),
                completedActions = state.completedActions + 1
            )
        }
        is SessionEvent.Pause -> state.takeIf { it.isActive }?.copy(phase = SessionPhase.PAUSED) ?: state
        SessionEvent.Resume -> state.takeIf { it.phase == SessionPhase.PAUSED }
            ?.copy(phase = SessionPhase.ANSWERING) ?: state
        is SessionEvent.Stop -> state.takeIf { it.isActive }?.copy(phase = SessionPhase.STOPPED) ?: state
        SessionEvent.Clear -> SessionState()
    }
}
