package com.sibirskyspeak.data

import javax.inject.Inject

/** Narrow planning boundary for UI callers; the legacy repository remains the storage adapter. */
class SessionPlanner @Inject constructor(private val repository: LearningRepository) {
    suspend fun plan(now: Long = System.currentTimeMillis(), includeReaderInsights: Boolean = true): SessionPlan =
        repository.sessionPlan(now, includeReaderInsights)
}
