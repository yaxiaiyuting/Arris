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
}
