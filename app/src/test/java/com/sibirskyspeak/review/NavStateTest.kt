package com.sibirskyspeak.review

import org.junit.Assert.*
import org.junit.Test

class NavStateTest {
    @Test fun pushReplaceAndPopHaveOneSourceOfTruth() {
        val nav = NavState(Dest.Dashboard)
        nav.push(Dest.Reader(42)); nav.replace(Dest.Reference)
        assertEquals(Dest.Reference, nav.current.value)
        assertTrue(nav.pop()); assertEquals(Dest.Dashboard, nav.current.value); assertFalse(nav.pop())
    }
}
