package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.bili.*
import com.takahashirinta.ncrust.lyric.LrcParser
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ScheduledBiliSubtitleAuditProbe {
    @Test fun subtitleCdnRequestReceivesTheFullLoginCookie()=runBlocking {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val req=chain.request()
            assertEquals("aisubtitle.hdslb.com",req.url.host)
            assertTrue(req.header("Cookie").orEmpty().contains("SESSDATA=fixture-session"))
            assertTrue(req.header("Cookie").orEmpty().contains("bili_jct=fixture-csrf"))
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("local fixture")
                .body("""{"body":[]}""".toResponseBody()).build()
        }.build()
        val oldClient=BiliApi.clientForTest
        val mirror=BiliAuthStore.javaClass.getDeclaredField("mirror").apply { isAccessible=true }
        val oldMirror=mirror.get(BiliAuthStore)
        try {
            BiliApi.clientForTest=client
            BiliAuthStore.setMirrorForTest(BiliCredential(sessdata="fixture-session",biliJct="fixture-csrf",dedeUserId="123"))
            assertNotNull(BiliApi.subtitleBody("//aisubtitle.hdslb.com/local-fixture.json"))
            println("AUDIT subtitle credential scope: the real subtitleBody entry attaches the full login Cookie to hdslb.com; no DNS or socket was opened")
        } finally {
            BiliApi.clientForTest=oldClient;mirror.set(BiliAuthStore,oldMirror)
            client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun subtitlesAfterOneHundredMinutesAreDroppedByTheLrcParser() {
        val cues=listOf(
            BiliSubtitleCue(5_999_000,5_999_500,"♪ before",1.0),
            BiliSubtitleCue(6_000_000,6_001_000,"♪ at boundary",1.0),
            BiliSubtitleCue(6_002_000,6_003_000,"♪ after",1.0),
        )
        val lyric=BiliSubtitle.lyricCues(cues)
        assertEquals(3,lyric.size)
        val lrc=BiliSubtitle.lyricLrc(lyric)
        assertTrue(lrc.contains("[100:00.00]at boundary"))
        val parsed=LrcParser.parse(lrc)
        assertEquals(1,parsed.size)
        assertEquals("before",parsed.single().text)
        println("AUDIT long Bili lyric: three valid music cues survive lyric filtering, but the generated 100-minute timestamps are discarded by LrcParser")
    }
}
