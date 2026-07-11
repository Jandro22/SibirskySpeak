package com.sibirskyspeak.data

import javax.inject.Inject

/** Injectable wall clock boundary for session timing and deterministic future tests. */
open class StudyClock @Inject constructor() {
    open fun now(): Long = System.currentTimeMillis()
}
