package com.takahashirinta.ncrust.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HeightClassTest {
    @Test
    fun `thresholds are regular at 720 compact at 560 tiny below`() {
        assertEquals(HeightClass.Regular, heightClassFor(896f))
        assertEquals(HeightClass.Regular, heightClassFor(720f))
        assertEquals(HeightClass.Compact, heightClassFor(719.9f))
        assertEquals(HeightClass.Compact, heightClassFor(560f))
        assertEquals(HeightClass.Tiny, heightClassFor(559.9f))
        assertEquals(HeightClass.Tiny, heightClassFor(427f))
        assertEquals(HeightClass.Tiny, heightClassFor(0f))
    }
}
