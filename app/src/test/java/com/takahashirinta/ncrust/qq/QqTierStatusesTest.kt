/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.player.QualityStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class QqTierStatusesTest {
    @Test
    fun `one batch can distinguish denied and missing tiers`() {
        val entries = mapOf(QqFileType.AI00 to QqRejection.NEED_VIP, QqFileType.RS01 to QqRejection.NO_FILE)
        assertEquals(
            mapOf("jymaster" to QualityStatus.NO_ENTITLEMENT, "hires" to QualityStatus.SONG_LACKS_TIER),
            qqTierStatuses(entries.keys.toList(), entries::get),
        )
    }

    @Test
    fun `unknown or incomplete outcomes leave the tier unlabelled`() {
        val types = listOf(QqFileType.F000, QqFileType.RS01, QqFileType.M500)
        val entries = mapOf(QqFileType.F000 to QqRejection.NONE, QqFileType.RS01 to QqRejection.UNKNOWN)
        assertEquals(emptyMap<String, QualityStatus>(), qqTierStatuses(types, entries::get))
    }
    @Test
    fun `an explicit no file outcome differs from an incomplete entry`() {
        val missing = JSONObject().put("purl", JSONObject.NULL).put("result", 0)
        val incomplete = JSONObject().put("purl", JSONObject.NULL)
        assertEquals(null, qqPlaybackPath(missing))
        assertEquals(QqRejection.NO_FILE, classifyQqRejection(qqVkeyRejectionInput(missing, loggedIn = true)))
        assertEquals(QqRejection.UNKNOWN, classifyQqRejection(qqVkeyRejectionInput(incomplete, loggedIn = true)))
    }

    @Test
    fun `a purchase requirement needs an explicit unpaid value`() {
        val incomplete = JSONObject().put("result", 0).put("pneedbuy", 1)
        assertEquals(QqRejection.NO_FILE, classifyQqRejection(qqVkeyRejectionInput(incomplete, loggedIn = true)))
        val denied = incomplete.put("isbuy", 0)
        assertEquals(QqRejection.NEED_PURCHASE, classifyQqRejection(qqVkeyRejectionInput(denied, loggedIn = true)))
    }

}
