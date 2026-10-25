package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackRetrySchedulerTest {
    private data class Request(val track: TrackKey, val revision: Int, val title: String)
    private val a = Request(TrackKey(MusicSource.NETEASE, 101), 1, "A")
    private val b = Request(TrackKey(MusicSource.QQMUSIC, 202, "B-mid", "B-media"), 2, "B")

    private fun scenario(change: (PlaybackRetryScheduler<Request>, (Request) -> Unit) -> Unit, expected: List<Request>) = runBlocking {
        var current = a
        val emitted = mutableListOf<Request>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val release = CompletableDeferred<Unit>()
        val scheduler = PlaybackRetryScheduler<Request>(scope,
            { it.track == current.track && it.revision == current.revision }, { release.await() })
        try {
            assertTrue(scheduler.schedule(a, 3000) { emitted += it })
            yield()
            change(scheduler) { current = it }
            release.complete(Unit)
            yield()
            assertEquals(expected, emitted)
        } finally { scope.cancel() }
    }

    @Test fun `cross source navigation rejects old pending retry`() = scenario({ _, set -> set(b) }, emptyList())
    @Test fun `same song replay advances revision and rejects old retry`() =
        scenario({ _, set -> set(a.copy(revision = 2)) }, emptyList())
    @Test fun `pause cancels retry even if track did not change`() = scenario({ s, _ -> s.cancel() }, emptyList())
    @Test fun `quality switch cancels pending retry`() = scenario({ s, _ -> s.cancel() }, emptyList())
    @Test fun `unchanged owner retries with its captured metadata`() = scenario({ _, _ -> }, listOf(a))
    @Test fun `duplicate errors do not schedule two retries`() = scenario({ s, _ ->
        assertFalse(s.schedule(a, 3000) { error("duplicate retry dispatched") })
    }, listOf(a))

    @Test fun `cancellation cannot be swallowed by a non cooperative wait`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val release = CompletableDeferred<Unit>()
        var emitted = 0
        val scheduler = PlaybackRetryScheduler<Request>(scope, { true }, {
            withContext(NonCancellable) { release.await() }
        })
        try {
            scheduler.schedule(a, 3000) { emitted++ }; yield()
            scheduler.cancel()
            release.complete(Unit); yield(); yield()
            assertEquals(0, emitted)
        } finally { scope.cancel() }
    }

    @Test fun `dispatch can invalidate scheduler without canceling itself`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val scheduler = PlaybackRetryScheduler<Request>(scope, { true }, {})
        var completed = false
        try {
            scheduler.schedule(a, 0) {
                assertFalse(scheduler.hasPending)
                scheduler.cancel()
                completed = true
            }
            yield()
            assertTrue(completed)
        } finally { scope.cancel() }
    }
}
