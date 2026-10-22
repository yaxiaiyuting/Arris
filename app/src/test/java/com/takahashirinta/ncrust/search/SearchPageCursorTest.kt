package com.takahashirinta.ncrust.search

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPageCursorTest {
    @Test fun failedSecondPageIsRetriedBeforeThird() = runBlocking {
        val cursor = SearchPageCursor()
        val requested = mutableListOf<Int>()
        cursor.loadNext { requested += it; false }
        cursor.loadNext { requested += it; true }
        cursor.loadNext { requested += it; true }
        assertEquals(listOf(2, 2, 3), requested)
    }

    @Test fun cancelledOrThrowingRequestDoesNotConsumeAPage() = runBlocking {
        val cursor = SearchPageCursor()
        try { cursor.loadNext { throw CancellationException() } } catch (_: CancellationException) { }
        try { cursor.loadNext { error("network") } } catch (_: IllegalStateException) { }
        cursor.loadNext { assertEquals(2, it); true }
    }

    @Test fun resetDuringRequestDoesNotAdvanceTheNewQuery() = runBlocking {
        val cursor = SearchPageCursor()
        cursor.loadNext { cursor.reset(); true }
        cursor.loadNext { assertEquals(2, it); true }
    }
}
