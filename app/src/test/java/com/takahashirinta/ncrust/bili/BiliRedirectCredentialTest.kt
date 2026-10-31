package com.takahashirinta.ncrust.bili

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class BiliRedirectCredentialTest {
    private fun request(url:String)=Request.Builder().url(url).header("Cookie","SESSDATA=fixture")
        .header("Referer","https://www.bilibili.com/").header("Origin","https://www.bilibili.com").build()
    @Test fun aRedirectOutsideTheAccountFamilyIsAnonymous() {
        val next=BiliCredentialBoundary.sanitize(request("https://fixture.invalid/subtitle"))
        assertNull(next.header("Cookie"));assertNull(next.header("Referer"));assertNull(next.header("Origin"))
    }
    @Test fun legitimateHttpsAccountHopsKeepCredentials() {
        val next=BiliCredentialBoundary.sanitize(request("https://passport.bilibili.com/x"))
        assertEquals("SESSDATA=fixture",next.header("Cookie"))
    }
    @Test fun aDowngradeCannotCarryAnAccountCookie() {
        assertNull(BiliCredentialBoundary.sanitize(request("http://api.bilibili.com/x")).header("Cookie"))
    }
    @Test fun aPostBodyCannotBeRedirectedToAnotherHost() {
        val post=request("https://fixture.invalid/x").newBuilder().post("refresh_token=fixture".toRequestBody()).build()
        assertTrue(runCatching {BiliCredentialBoundary.sanitize(post)}.exceptionOrNull() is java.io.IOException)
    }
}
