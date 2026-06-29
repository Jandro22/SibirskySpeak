package com.sibirskyspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusMessageTest {
    @Test
    fun shortSuccessMessagesDismissQuickly() {
        assertEquals(2_500L, statusMessageAutoDismissMillis("Card updated"))
        assertEquals(2_500L, statusMessageAutoDismissMillis("Updated слово"))
    }

    @Test
    fun consequentialNoticesStayLongEnoughToRead() {
        assertEquals(7_000L, statusMessageAutoDismissMillis("Pace protected: 2 cards moved"))
        assertEquals(7_000L, statusMessageAutoDismissMillis("Parked this card"))
    }

    @Test
    fun errorsRequireExplicitDismissal() {
        assertNull(statusMessageAutoDismissMillis("Could not update card"))
        assertNull(statusMessageAutoDismissMillis("Import failed"))
    }
}
