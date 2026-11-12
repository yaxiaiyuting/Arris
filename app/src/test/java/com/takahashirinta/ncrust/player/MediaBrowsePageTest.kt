package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBrowsePageTest {
    @Test
    fun buildsOnlyTheRequestedPage() {
        val songs = (0 until 5_000).toList()
        var mapped = 0
        val page = songs.mediaBrowsePage(10, 50).map {
            mapped++
            it
        }
        assertEquals((500 until 550).toList(), page)
        assertEquals(50, mapped)
        assertEquals(listOf(4_998, 4_999), songs.mediaBrowsePage(1_666, 3))
    }

    @Test
    fun legacyFullListAndOversizedPageStaySafe() {
        val songs = listOf("daily", "radio", "liked")
        assertSame(songs, songs.mediaBrowsePage(0, Int.MAX_VALUE))
        assertTrue(songs.mediaBrowsePage(Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertTrue(songs.mediaBrowsePage(-1, 3).isEmpty())
        assertTrue(songs.mediaBrowsePage(0, 0).isEmpty())
    }
}
