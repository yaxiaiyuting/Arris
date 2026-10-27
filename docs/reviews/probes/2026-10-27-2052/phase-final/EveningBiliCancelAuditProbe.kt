package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.bili.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EveningBiliCancelAuditProbe {
    private class LocalApi : BiliAuthEndpoints {
        val generate = CompletableDeferred<BiliQrCode?>()
        val polled = CompletableDeferred<Unit>()
        val result = CompletableDeferred<BiliQrPoll>()
        override suspend fun qrGenerate(): BiliQrCode? = generate.await()
        override suspend fun qrPoll(qrcodeKey: String): BiliQrPoll {
            polled.complete(Unit)
            return result.await()
        }
        override suspend fun navWithCookie(cookie: String): BiliNavResult = error("nav must not be called")
    }
    @Test fun lateGenerationResponseChangesCancelledControllerBackToWaiting() = runBlocking {
        val api = LocalApi()
        val login = BiliQrLogin(api, clock = { 1L }, sleep = {})
        val call = async(start = CoroutineStart.UNDISPATCHED) { login.prepare() }
        login.cancel()
        assertEquals(BiliQrLogin.State.CANCELLED, login.state)
        api.generate.complete(BiliQrCode("local fixture", "fixture"))
        assertEquals(BiliQrLogin.State.WAITING, call.await())
        assertNotNull(login.qrCode)
        println("AUDIT real BiliQrLogin.prepare accepts a late local generate result after cancel and returns WAITING. Controller cancellation, not coroutine Job cancellation; no WebView or HTTP executed")
    }
    @Test fun lateSuccessfulPollOverwritesCancellationAndRetainsCredential() = runBlocking {
        val api = LocalApi().apply { generate.complete(BiliQrCode("local fixture", "fixture")) }
        val login = BiliQrLogin(api, clock = { 1L }, sleep = {})
        login.prepare()
        val call = async(start = CoroutineStart.UNDISPATCHED) { login.poll() }
        api.polled.await()
        login.cancel()
        assertEquals(BiliQrLogin.State.CANCELLED, login.state)
        api.result.complete(BiliQrPoll.Success(BiliCredential(sessdata = "LOCAL_FAKE_ONLY")))
        assertEquals(BiliQrLogin.State.CONFIRMED, call.await())
        assertNotNull(login.credential)
        println("AUDIT real BiliQrLogin.poll overwrites CANCELLED with CONFIRMED after its in-flight injected API completes. No finishLogin/persistence/cloud write. Compose Job cancellation may independently prevent the UI race")
    }
}
