package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamBitrateTest {
    @Test fun `actual format takes priority over file metadata`() {
        assertEquals(320000L, StreamBitrate.resolve(320000, 4000000, 240000))
    }
    @Test fun `offline and lossless bitrate comes from full file size not cached spans`() {
        assertEquals(1000000L, StreamBitrate.resolve(-1, 30000000, 240000))
    }
    @Test fun `unknown metadata remains unknown instead of inventing a bitrate`() {
        assertEquals(0L, StreamBitrate.resolve(-1, 0, 240000))
        assertEquals(0L, StreamBitrate.resolve(0, 30000000, -9223372036854775807))
    }
    @Test fun `large file computation does not overflow long multiplication`() {
        assertEquals(8000000000000L, StreamBitrate.resolve(-1, 1000000000000000L, 1000000L))
    }

    @Test fun `cache metadata is read only when the file size can decide the bitrate`() {
        var cacheReads = 0
        val bytes = { cacheReads++; 30_000_000L }
        assertEquals(320_000L, StreamBitrate.resolve(320_000, 240_000L, bytes))
        assertEquals(0L, StreamBitrate.resolve(-1, 0L, bytes))
        assertEquals(0, cacheReads)
        assertEquals(1_000_000L, StreamBitrate.resolve(-1, 240_000L, bytes))
        assertEquals(1, cacheReads)
    }

    @Test fun `a minute of samples reuses known length while duration remains live`() {
        var reads = 0
        val length = StreamBitrate.FileLength<String> { reads++; 30_000_000L }
        repeat(120) {
            assertEquals(1_000_000L, StreamBitrate.resolve(-1, 240_000L) { length.read("first") })
        }
        assertEquals(1, reads)
        assertEquals(2_000_000L, StreamBitrate.resolve(-1, 120_000L) { length.read("first") })
        assertEquals(1, reads)
    }

    @Test fun `unknown length is retried and a new item may reuse the same URI`() {
        var sourceBytes = 0L
        val length = StreamBitrate.FileLength<String> { sourceBytes }
        assertEquals(0L, length.read("first"))
        sourceBytes = 100L
        assertEquals(100L, length.read("first"))
        sourceBytes = 200L
        assertEquals(200L, length.read("second"))
        length.reset()
        sourceBytes = 300L
        assertEquals(300L, length.read("second"))
    }
}
