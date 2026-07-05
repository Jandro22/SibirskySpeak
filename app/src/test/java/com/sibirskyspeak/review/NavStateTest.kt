package com.sibirskyspeak.review

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

private val fakeSaverScope = object : SaverScope { override fun canBeSaved(value: Any) = true }

class NavStateTest {
    @Test fun pushReplaceAndPopHaveOneSourceOfTruth() {
        val nav = NavState(Dest.Dashboard)
        nav.push(Dest.Reader(42)); nav.replace(Dest.Reference)
        assertEquals(Dest.Reference, nav.current.value)
        assertTrue(nav.pop()); assertEquals(Dest.Dashboard, nav.current.value); assertFalse(nav.pop())
    }

    @Test fun replaceNeverGrowsTheStackButPushDoes() {
        val nav = NavState(Dest.Practice)
        nav.replace(Dest.Dashboard)
        nav.replace(Dest.Lab)
        assertEquals(1, nav.snapshot().size)
        nav.push(Dest.Study)
        assertEquals(2, nav.snapshot().size)
    }

    @Test fun tabDestFindsTheNearestNonOverlayEntryUnderAPushedOverlay() {
        val nav = NavState(Dest.Practice)
        nav.replace(Dest.Reader(7))
        nav.push(Dest.Study)
        assertEquals(Dest.Reader(7), nav.tabDest())
        nav.pop()
        assertEquals(Dest.Reader(7), nav.current.value)
    }

    @Test fun tabDestIsTheDestItselfWhenNotOverlaid() {
        val nav = NavState(Dest.Lab)
        assertEquals(Dest.Lab, nav.tabDest())
    }

    @Test fun saverRoundTripsTheWholeStackIncludingReaderTextId() {
        val nav = NavState(Dest.Dashboard)
        nav.replace(Dest.Reader(99))
        nav.push(Dest.Reference)
        val saved = with(NavStateSaver) { fakeSaverScope.save(nav) }
        val restored = NavStateSaver.restore(saved!!)!!
        assertEquals(Dest.Reference, restored.current.value)
        assertEquals(Dest.Reader(99), restored.tabDest())
        assertEquals(2, restored.snapshot().size)
    }

    @Test fun saverHandlesAReaderDestWithNoTextId() {
        val nav = NavState(Dest.Reader(null))
        val saved = with(NavStateSaver) { fakeSaverScope.save(nav) }
        val restored = NavStateSaver.restore(saved!!)!!
        assertEquals(Dest.Reader(null), restored.current.value)
    }
}
