package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.network.SongItem
import org.junit.Assert.*
import org.junit.Test

class EveningQueueAuditProbe {
    @Test fun realQueueRowsGiveTwoSourcesTheSameLazyItemKey() {
        val ne = SongItem(12345L, "local netease fixture", null, null, null, source = "netease")
        val qq = SongItem(12345L, "local qq fixture", null, null, null, source = "qqmusic", sourceId = "fake_mid")
        val method = Class.forName("com.takahashirinta.ncrust.ui.player.QueueViewKt")
            .getDeclaredMethod("buildQueueRows", List::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val rows = method.invoke(null, listOf(ne, qq), 0, false) as List<*>
        val keys = rows.map { row ->
            row!!.javaClass.getDeclaredMethod("getKey").apply { isAccessible = true }.invoke(row) as String
        }
        assertEquals(2, keys.count { it == "s-12345" })
        assertTrue(keys.size > keys.toSet().size)
        println("AUDIT invokes real private buildQueueRows: allowed distinct-source songs share s-12345. No Compose layout executed; duplicate-key layout exception and drag visuals remain static call-chain consequences")
    }
}
