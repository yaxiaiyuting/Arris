package com.takahashirinta.ncrust.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · C：qm 会员判定的纯逻辑单测。
 *
 * 会员判据是「能不能放出无损/母带」的唯一依据，写错的两个方向代价不对称：
 * 判宽了 ⇒ 用户点了无损却放不出来（体验差但能理解）；
 * 判严了 ⇒ 有会员的用户被当成没会员，功能等于不存在。
 */
class QqProfileTest {

    private val now = 1_800_000_000L

    @Test
    fun `vipType 为 0 时不是会员`() {
        assertFalse(QqProfile(vipType = 0).isVip(now))
    }

    @Test
    fun `vipType 大于 0 即为会员——不把具体档位数字写死进业务判断`() {
        // 腾讯新增会员档位时，老版本必须仍然认它是会员。
        assertTrue(QqProfile(vipType = 1).isVip(now))
        assertTrue(QqProfile(vipType = 2).isVip(now))
        assertTrue(QqProfile(vipType = 99).isVip(now))
    }

    @Test
    fun `会员已过期时按非会员处理`() {
        assertFalse(QqProfile(vipType = 2, vipExpireAt = now - 1).isVip(now))
    }

    @Test
    fun `到期时间未知时按会员处理`() {
        // 0 = 未知（服务端没给），不能因为「不知道啥时候过期」就剥夺会员能力。
        assertTrue(QqProfile(vipType = 2, vipExpireAt = 0L).isVip(now))
    }

    @Test
    fun `正好到期的那一刻算过期`() {
        assertFalse(QqProfile(vipType = 1, vipExpireAt = now).isVip(now))
        assertTrue(QqProfile(vipType = 1, vipExpireAt = now + 1).isVip(now))
    }

    @Test
    fun `默认资料是非会员且没有昵称`() {
        val p = QqProfile()
        assertEquals(0, p.vipType)
        assertEquals(0L, p.uid)
        assertFalse(p.isVip(now))
    }
}
