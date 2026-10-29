package com.takahashirinta.ncrust.listen

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一起听的网络头必须是**纯 ASCII**（v3.8.0）。
 *
 * 真机崩溃记录：`IllegalArgumentException: Unexpected char 0x4e00 at 20 in User-Agent
 * value: Arris/1.0 (Android; 一起听)` —— OkHttp 在建请求时直接抛，表现为"点一下就闪退"，
 * 而且异常发生点在请求构造，不在网络线程，看不出和中文有关。
 *
 * 所以这里把「UA 与房间 URL 都只含 ASCII」钉成用例：改文案时不至于又把中文写回 header。
 */
class RoomHeaderTest {

    @Test
    fun `房间 WebSocket URL 只含 ASCII（昵称会被百分号编码）`() {
        val url = RoomDirectory.roomUrl(
            base = "https://arris-listen.arris-listen.workers.dev",
            roomId = "123456",
            role = "host",
            name = "一起听房主",
        )
        assertTrue("URL 必须是 ASCII：$url", url.all { it.code in 0x20..0x7E })
        assertTrue(url.startsWith("wss://"))
        assertTrue(url.contains("/room/123456?role=host&name="))
    }

    @Test
    fun `ws 地址替换规则正确`() {
        assertTrue(
            RoomDirectory.roomUrl("wss://x.workers.dev", "abc", "member", "m")
                .startsWith("wss://x.workers.dev/room/abc"),
        )
        assertTrue(
            RoomDirectory.roomUrl("https://x.workers.dev/", "abc", "member", "m")
                .startsWith("wss://x.workers.dev/room/abc"),
        )
    }
}
