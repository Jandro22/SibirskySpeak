package com.sibirskyspeak.review

import androidx.compose.runtime.saveable.Saver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Dest {
    // The REVIEWS/"Practice" landing tab — daily plan overview, not an active study
    // session (that's Study, entered by pushing on top of whichever tab is current).
    data object Practice : Dest
    data object Study : Dest
    data object Dashboard : Dest
    data class Reader(val textId: Long? = null) : Dest
    data object Lab : Dest
    data object Import : Dest
    data object Reference : Dest
    data object Settings : Dest
}

/**
 * Single source of truth for top-level navigation. Tab-level destinations
 * (Practice/Dashboard/Reader/Lab/Import) are entered via [replace] — switching
 * tabs doesn't grow the stack. Study and Reference are transient overlays over
 * whichever tab is current, entered via [push] and dismissed via [pop]; this
 * naturally restores the correct background tab without a parallel "last tab"
 * variable, since [tabDest] walks the stack for the nearest non-overlay entry.
 */
class NavState private constructor(initialStack: List<Dest>) {
    private val stack = initialStack.toMutableList()
    private val mutableCurrent = MutableStateFlow(stack.last())
    val current: StateFlow<Dest> = mutableCurrent.asStateFlow()

    constructor(start: Dest = Dest.Practice) : this(listOf(start))

    fun push(dest: Dest) { if (stack.lastOrNull() != dest) { stack += dest; mutableCurrent.value = dest } }
    fun replace(dest: Dest) { if (stack.isEmpty()) stack += dest else stack[stack.lastIndex] = dest; mutableCurrent.value = dest }
    fun pop(): Boolean { if (stack.size <= 1) return false; stack.removeAt(stack.lastIndex); mutableCurrent.value = stack.last(); return true }
    fun snapshot(): List<Dest> = stack.toList()

    /** Nearest non-overlay entry in the stack — the tab showing underneath a
     * pushed Study/Reference overlay, or the current destination itself if it's
     * already a tab. Never empty: the bottom of the stack is always a tab dest. */
    fun tabDest(): Dest = stack.lastOrNull { it !is Dest.Study && it !is Dest.Reference } ?: Dest.Practice

    companion object {
        fun fromStack(stack: List<Dest>): NavState = NavState(stack.ifEmpty { listOf(Dest.Practice) })
    }
}

private fun Dest.encode(): String = when (this) {
    Dest.Practice -> "Practice"
    Dest.Study -> "Study"
    Dest.Dashboard -> "Dashboard"
    Dest.Lab -> "Lab"
    Dest.Import -> "Import"
    Dest.Reference -> "Reference"
    Dest.Settings -> "Settings"
    is Dest.Reader -> "Reader:${textId ?: ""}"
}

private fun decodeDest(encoded: String): Dest = when {
    encoded == "Practice" -> Dest.Practice
    encoded == "Study" -> Dest.Study
    encoded == "Dashboard" -> Dest.Dashboard
    encoded == "Lab" -> Dest.Lab
    encoded == "Import" -> Dest.Import
    encoded == "Reference" -> Dest.Reference
    encoded == "Settings" -> Dest.Settings
    encoded.startsWith("Reader:") -> Dest.Reader(encoded.removePrefix("Reader:").toLongOrNull())
    else -> Dest.Practice
}

/** Survives rotation/process death by encoding the whole back stack as strings —
 * a Bundle-safe type — so a study session or opened reader text isn't lost. */
val NavStateSaver: Saver<NavState, List<String>> = Saver(
    save = { it.snapshot().map(Dest::encode) },
    restore = { NavState.fromStack(it.map(::decodeDest)) }
)
