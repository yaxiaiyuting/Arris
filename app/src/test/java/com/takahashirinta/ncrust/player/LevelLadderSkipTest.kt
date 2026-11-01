package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降级阶梯跳过（v3.8.7）。
 *
 * 取链是串行阶梯，每档一个来回；没有会员的账号每首歌都要白跑几档才落地。
 * 这里钉住「连续两首被拒才记档」的判定，避免把某一首的版权限制误判成账号不行。
 */
class LevelLadderSkipTest {

    private class Gate {
        val denied = mutableMapOf<String, Long>()
        val rejects = mutableMapOf<String, Int>()
        var now = 0L
        fun denied(level: String) = (denied[level] ?: 0L) > now
        fun rejected(level: String) {
            val n = (rejects[level] ?: 0) + 1
            rejects[level] = n
            if (n >= 2 && !denied(level)) denied[level] = now + 10 * 60_000L
        }
        fun accepted(level: String) {
            rejects.remove(level); denied.remove(level)
        }
    }

    @Test
    fun `一次拒绝不记档`() {
        val g = Gate()
        g.rejected("lossless")
        assertTrue(!g.denied("lossless"))
    }

    @Test
    fun `连续两首被拒后跳过该档`() {
        val g = Gate()
        g.rejected("lossless"); g.rejected("lossless")
        assertTrue(g.denied("lossless"))
    }

    @Test
    fun `成功一次就恢复`() {
        val g = Gate()
        g.rejected("lossless"); g.rejected("lossless")
        assertTrue(g.denied("lossless"))
        g.accepted("lossless")
        assertTrue(!g.denied("lossless"))
    }

    @Test
    fun `记档到期后重新尝试`() {
        val g = Gate()
        g.rejected("hires"); g.rejected("hires")
        assertTrue(g.denied("hires"))
        g.now += 10 * 60_000L + 1
        assertEquals(false, g.denied("hires"))
    }

    @Test
    fun `阶梯过滤后仍保留至少一档`() {
        val tiers = listOf("lossless", "exhigh", "higher", "standard")
        val g = Gate()
        tiers.forEach { g.rejected(it); g.rejected(it) }
        val effective = tiers.filterNot { g.denied(it) }.ifEmpty { tiers }
        assertTrue(effective.isNotEmpty())
        assertEquals(tiers, effective)
    }
}
