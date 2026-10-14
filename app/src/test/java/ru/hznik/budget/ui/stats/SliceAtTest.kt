package ru.hznik.budget.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Попадание пальцем в долю кольца: угол от верха по часовой стрелке. */
class SliceAtTest {
    private val values = listOf(50L, 25L, 25L) // 0–180°, 180–270°, 270–360°

    @Test
    fun `angle picks the slice it falls into`() {
        assertEquals(0, sliceAt(10f, values))
        assertEquals(0, sliceAt(179f, values))
        assertEquals(1, sliceAt(180f, values))
        assertEquals(2, sliceAt(300f, values))
        assertEquals(2, sliceAt(359.99f, values))
    }

    @Test
    fun `no spending has no slices`() {
        assertNull(sliceAt(10f, listOf(0L)))
    }
}
