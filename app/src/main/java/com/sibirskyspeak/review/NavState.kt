package com.sibirskyspeak.review

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Dest {
    data object Study : Dest
    data object Dashboard : Dest
    data class Reader(val textId: Long? = null) : Dest
    data object Lab : Dest
    data object Import : Dest
    data object Reference : Dest
    data object Settings : Dest
}

class NavState(start: Dest = Dest.Study) {
    private val stack = mutableListOf(start)
    private val mutableCurrent = MutableStateFlow(start)
    val current: StateFlow<Dest> = mutableCurrent.asStateFlow()
    fun push(dest: Dest) { if (stack.lastOrNull() != dest) { stack += dest; mutableCurrent.value = dest } }
    fun replace(dest: Dest) { if (stack.isEmpty()) stack += dest else stack[stack.lastIndex] = dest; mutableCurrent.value = dest }
    fun pop(): Boolean { if (stack.size <= 1) return false; stack.removeAt(stack.lastIndex); mutableCurrent.value = stack.last(); return true }
    fun snapshot(): List<Dest> = stack.toList()
}
