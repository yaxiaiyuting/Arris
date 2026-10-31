package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.bili.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NightBiliGenerationAuditProbe {
    private class LocalApi : BiliAuthEndpoints {
        val firstCode = CompletableDeferred<BiliQrCode?>()
        var generateCount = 0
        val firstPoll = CompletableDeferred<BiliQrPoll>()
        override suspend fun qrGenerate(): BiliQrCode? = if (generateCount++ == 0) firstCode.await() else BiliQrCode("local-new", "new")
        override suspend fun qrPoll(qrcodeKey: String): BiliQrPoll = if (qrcodeKey == "old") firstPoll.await() else BiliQrPoll.Success(BiliCredential(sessdata = "LOCAL_NEW_FAKE"))
        override suspend fun navWithCookie(cookie: String): BiliNavResult = error("no real or simulated account write allowed")
    }
    @Test fun supersededGenerateResponseCancelsNewWaitingSession() = runBlocking {
        val api = LocalApi()
        val login = BiliQrLogin(api, clock = { 1L }, sleep = {})
        val old = async(start = CoroutineStart.UNDISPATCHED) { login.prepare() }
        login.reset()
        assertEquals(BiliQrLogin.State.WAITING, login.prepare())
        assertEquals("new", login.qrCode!!.qrcodeKey)
        api.firstCode.complete(BiliQrCode("local-old", "old"))
        assertEquals(BiliQrLogin.State.CANCELLED, old.await())
        assertEquals(BiliQrLogin.State.CANCELLED, login.state)
        assertEquals("new", login.qrCode!!.qrcodeKey)
        println("AUDIT real prepare generation mismatch emits CANCELLED into the NEW controller session; no HTTP/UI/finishLogin executed")
    }
    @Test fun supersededPollResponseCancelsNewConfirmedSession() = runBlocking {
        val api = LocalApi().apply { firstCode.complete(BiliQrCode("local-old", "old")) }
        val login = BiliQrLogin(api, clock = { 1L }, sleep = {})
        login.prepare()
        val old = async(start = CoroutineStart.UNDISPATCHED) { login.pollOnce() }
        login.reset()
        login.prepare()
        assertEquals(BiliQrLogin.State.CONFIRMED, login.pollOnce())
        assertEquals("LOCAL_NEW_FAKE", login.credential!!.sessdata)
        api.firstPoll.complete(BiliQrPoll.Pending)
        assertEquals(BiliQrLogin.State.CANCELLED, old.await())
        assertEquals(BiliQrLogin.State.CANCELLED, login.state)
        assertEquals("LOCAL_NEW_FAKE", login.credential!!.sessdata)
        println("AUDIT stale poll does not adopt old credentials, but overwrites new CONFIRMED with CANCELLED. Coroutine Job deliberately remains active; Compose dismissal protection is outside this proof")
    }
}
