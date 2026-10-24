package com.takahashirinta.ncrust.qq

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqMembershipExpiryTest {
    @Test fun `full timestamp keeps time of day in Shanghai timezone`() {
        val full = QqMembershipExpiry.parse("2026-10-02 03:03:34")
        assertEquals(1790881414L, full)
        assertEquals(full, QqMembershipExpiry.parse((full * 1000).toString()))
        assertEquals(full, QqMembershipExpiry.parse(full.toString()))
    }
    @Test fun `date only stays valid to the end of that calendar day`() {
        assertEquals(1790956799L, QqMembershipExpiry.parse("2026-10-02"))
    }
    @Test fun `invalid expiry is unknown and never a lenient rolled date`() {
        for (s in listOf("", "2026-02-30", "2026-10-02 garbage", "0", "123", "99999999999999999999")) {
            assertEquals(s, 0L, QqMembershipExpiry.parse(s))
        }
    }
    @Test fun `svip is a sibling of identity in the recorded QQ response shape`() {
        val profile = QqApi.parseProfile(JSONObject("""{"code":0,"data":{"svip":1,"send":"2026-10-02","identity":{"vip":1,"overdate":"2026-09-02 03:03:34"}}}"""))!!
        assertEquals(2, profile.vipType)
        assertEquals(1790956799L, profile.vipExpireAt)
    }

    @Test fun `svip missing its own expiry stays unknown instead of borrowing green diamond date`() {
        val profile = QqApi.parseProfile(JSONObject("""{"code":0,"data":{"svip":1,"identity":{"vip":1,"overdate":"2026-10-02 03:03:34"}}}"""))!!
        assertEquals(0L, profile.vipExpireAt)
        assertTrue(profile.isVip())
    }
    @Test fun `ordinary membership uses its precise green diamond expiry`() {
        val profile = QqApi.parseProfile(JSONObject("""{"code":0,"data":{"svip":0,"identity":{"vip":1,"overdate":"2026-10-02 03:03:34"}}}"""))!!
        assertEquals(1, profile.vipType)
        assertEquals(1790881414L, profile.vipExpireAt)
    }
}
