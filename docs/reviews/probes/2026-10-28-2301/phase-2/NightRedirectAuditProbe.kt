package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.bili.*
import java.net.Proxy
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class NightRedirectAuditProbe {
    @Test fun realRedirectBuilderRetainsCredentialsForNonAccountHost() {
        val mirror = BiliAuthStore.javaClass.getDeclaredField("mirror").apply { isAccessible = true }
        val beforeMirror = mirror.get(BiliAuthStore)
        val beforeClient = BiliApi.clientForTest
        var captured: Request? = null
        val transport = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("LOCAL FIXTURE ONLY").body("{}".toResponseBody()).build()
        }.build()
        try {
            mirror.set(BiliAuthStore, BiliCredential(sessdata = "LOCAL_FAKE_COOKIE", biliJct = "LOCAL_FAKE_CSRF"))
            BiliApi.clientForTest = transport
            val get = BiliApi.javaClass.getDeclaredMethod("get", String::class.java, String::class.java).apply { isAccessible = true }
            get.invoke(BiliApi, "https://api.bilibili.com/local-fixture", null)
            val original = captured!!
            assertTrue(original.header("Cookie")!!.contains("LOCAL_FAKE_COOKIE"))
            val redirect = Response.Builder().request(original).protocol(Protocol.HTTP_1_1).code(302)
                .message("LOCAL REDIRECT MODEL").header("Location", "https://fixture.invalid/subtitle.json")
                .body("".toResponseBody()).build()
            val cls = Class.forName("okhttp3.internal.http.RetryAndFollowUpInterceptor")
            val interceptor = cls.getDeclaredConstructor(OkHttpClient::class.java).newInstance(transport)
            val method = cls.declaredMethods.single { it.name == "followUpRequest" }.apply { isAccessible = true }
            val next = method.invoke(interceptor, redirect, null) as Request
            assertFalse(BiliApi.isAccountDomain(next.url.toString()))
            assertTrue(next.header("Cookie")!!.contains("LOCAL_FAKE_COOKIE"))
            assertEquals(original.header("Referer"), next.header("Referer"))
            assertTrue(transport.followRedirects)
            println("AUDIT real BiliApi.get plus OkHttp 4.12 followUpRequest: cross-host redirect model retains SESSDATA to fixture.invalid. Initial HTTP intercepted locally; redirect request was constructed only, never sent. No claim of live provider redirect or exfiltration")
        } finally {
            mirror.set(BiliAuthStore, beforeMirror)
            BiliApi.clientForTest = beforeClient
            transport.connectionPool.evictAll()
            transport.dispatcher.executorService.shutdown()
        }
    }
}
