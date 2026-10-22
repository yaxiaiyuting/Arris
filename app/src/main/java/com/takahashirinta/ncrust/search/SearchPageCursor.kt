package com.takahashirinta.ncrust.search

/** Advance only after a page is published successfully. A reset invalidates an older request. */
internal class SearchPageCursor {
    private var page = 1
    private var generation = 0

    fun reset() {
        page = 1
        generation++
    }

    suspend fun loadNext(request: suspend (Int) -> Boolean) {
        val started = generation
        val candidate = page + 1
        if (request(candidate) && generation == started) page = candidate
    }
}
