package com.sibirskyspeak.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousQueuePolicyTest {
    @Test fun `priority recovery moves one card without dropping the rest of the queue`() {
        val queue = mutableListOf(11L, 22L, 33L, 22L, 44L)

        assertTrue(queue.moveFirstMatchToFront { it == 22L })

        assertEquals(listOf(22L, 11L, 33L, 22L, 44L), queue)
        assertEquals(5, queue.size)
    }

    @Test fun `missing recovery card leaves the complete queue untouched`() {
        val queue = mutableListOf(11L, 22L, 33L)

        assertFalse(queue.moveFirstMatchToFront { it == 99L })

        assertEquals(listOf(11L, 22L, 33L), queue)
    }
}
