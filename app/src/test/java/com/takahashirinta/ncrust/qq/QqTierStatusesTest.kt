/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.player.QualityStatus
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
}
