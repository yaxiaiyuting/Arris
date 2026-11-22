package com.takahashirinta.ncrust.network

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.1.10 · B：**按 host 的地址族健康记忆**。
 *
 * 它要回答两个此前无法回答的问题：
 * 1. 回退窗口内该先试哪一族（而不是无条件 IPv6）；
 * 2. 回退到底有没有救回来（`failover=ok|fail`，以及 attempts/recovered/failed 计数）。
 */
class AddressHealthTest {

    private val host = "interface3.music.163.com"

    private fun addr(ip: String): InetAddress = InetAddress.getByName(ip)

    @Test
    fun `没有记忆时窗口内不偏置任何一族`() {
        AddressHealth.clear()
        assertNull(AddressHealth.preferredWithinWindow(host, 1_000L))
    }

    @Test
    fun `最近成功的那一族在窗口内优先`() {
        AddressHealth.clear()
        AddressHealth.onConnectSuccess(host, AddressFamily.V6, 1_000L)
        assertEquals(AddressFamily.V6, AddressHealth.preferredWithinWindow(host, 2_000L))
        AddressHealth.onConnectSuccess(host, AddressFamily.V4, 3_000L)
        assertEquals("更近的成功覆盖更早的", AddressFamily.V4, AddressHealth.preferredWithinWindow(host, 4_000L))
    }

    @Test
    fun `最近失败的那一族会让位给另一族`() {
        AddressHealth.clear()
        AddressHealth.onConnectSuccess(host, AddressFamily.V6, 1_000L)
        // v6 刚失败 ⇒ 窗口内先试 v4
        AddressHealth.onTransportFailure(host, AddressFamily.V6, 2_000L)
        assertEquals(AddressFamily.V4, AddressHealth.preferredWithinWindow(host, 3_000L))
        // v4 也失败 ⇒ 反过来先试 v6（两个方向都试，不永久偏置）
        AddressHealth.onTransportFailure(host, AddressFamily.V4, 4_000L)
        assertEquals(AddressFamily.V6, AddressHealth.preferredWithinWindow(host, 5_000L))
    }

    @Test
    fun `记忆超过 TTL 后视为没有记忆`() {
        AddressHealth.clear()
        AddressHealth.onConnectSuccess(host, AddressFamily.V4, 1_000L)
        assertNull(
            "10 分钟前的成功不该继续主导顺序",
            AddressHealth.preferredWithinWindow(host, 1_000L + AddressHealth.MEMORY_TTL_MS + 1L),
        )
    }

    @Test
    fun `换族成功记 ok、换族仍失败记 fail`() {
        AddressHealth.clear()
        // ① 第一次失败：登记一次回退，等另一族的结果
        assertEquals(FailoverVerdict.NONE, AddressHealth.onTransportFailure(host, AddressFamily.V4, 1_000L))
        assertEquals(Triple(1, 0, 0), AddressHealth.counters(host))
        // ② 另一族成功 ⇒ 回退有效
        assertEquals(FailoverVerdict.OK, AddressHealth.onConnectSuccess(host, AddressFamily.V6, 2_000L))
        assertEquals(Triple(1, 1, 0), AddressHealth.counters(host))
        // ③ v6 失败、换到 v4 也失败 ⇒ 回退失败
        assertEquals(FailoverVerdict.NONE, AddressHealth.onTransportFailure(host, AddressFamily.V6, 3_000L))
        assertEquals(FailoverVerdict.FAIL, AddressHealth.onTransportFailure(host, AddressFamily.V4, 4_000L))
        assertEquals(Triple(2, 1, 1), AddressHealth.counters(host))
        assertEquals("回退胜率 1/2", 1 to 2, AddressHealth.failoverWinRate())
    }

    @Test
    fun `同一族恢复不算回退成功`() {
        AddressHealth.clear()
        AddressHealth.onTransportFailure(host, AddressFamily.V4, 1_000L)
        // 同一族又成功：那次失败是瞬时的，不能算「换族救回来」
        assertEquals(FailoverVerdict.NONE, AddressHealth.onConnectSuccess(host, AddressFamily.V4, 2_000L))
        assertEquals(0, AddressHealth.counters(host).second)
    }

    @Test
    fun `等待回退结果有过期时间`() {
        AddressHealth.clear()
        AddressHealth.onTransportFailure(host, AddressFamily.V4, 1_000L)
        val late = 1_000L + AddressHealth.PENDING_TTL_MS + 1L
        assertEquals(
            "两分钟之后才到的成功不是回退的功劳",
            FailoverVerdict.NONE,
            AddressHealth.onConnectSuccess(host, AddressFamily.V6, late),
        )
        assertEquals(0, AddressHealth.counters(host).second)
    }

    @Test
    fun `一族都不失败时不产生回退样本`() {
        AddressHealth.clear()
        AddressHealth.onConnectSuccess(host, AddressFamily.V4, 1_000L)
        AddressHealth.onConnectSuccess(host, AddressFamily.V6, 2_000L)
        assertNull("没有失败 ⇒ 没有回退胜率", AddressHealth.failoverWinRate())
    }

    @Test
    fun `换网清空记忆`() {
        AddressHealth.clear()
        AddressHealth.onConnectSuccess(host, AddressFamily.V4, 1_000L)
        AddressHealth.onNetworkRouteChanged()
        assertNull(AddressHealth.preferredWithinWindow(host, 2_000L))
        assertEquals(Triple(0, 0, 0), AddressHealth.counters(host))
    }

    @Test
    fun `解析不出地址时事件被忽略`() {
        AddressHealth.clear()
        assertEquals(FailoverVerdict.NONE, AddressHealth.onTransportFailure(host, null, 1_000L))
        assertEquals(FailoverVerdict.NONE, AddressHealth.onConnectSuccess(host, null, 2_000L))
        assertNull(AddressHealth.preferredWithinWindow(host, 3_000L))
    }

    @Test
    fun `地址族分类与对合`() {
        assertEquals(AddressFamily.V4, AddressFamily.of(addr("117.135.207.67")))
        assertEquals(AddressFamily.V6, AddressFamily.of(addr("2409:8c6a:b021:1400:0:4:0:3")))
        assertNull(AddressFamily.of(null))
        assertEquals(AddressFamily.V6, AddressFamily.V4.opposite())
        assertEquals(AddressFamily.V4, AddressFamily.V6.opposite())
    }

    @Test
    fun `窗口内按成功族排前、另一族保留兜底`() {
        AddressHealth.clear()
        val v4 = addr("117.135.207.67")
        val v6 = addr("2409:8c6a:b021:1400:0:4:0:3")
        val all = listOf(v6, v4)
        // 窗口外：preferred 不生效，仍是 v4-only（既有策略，一行未改）
        assertEquals(listOf(v4), AddressPreference.select(all, preferIpv6 = false, preferred = AddressFamily.V6))
        // 窗口内无记忆：v6 优先（既有行为）
        assertEquals(listOf(v6, v4), AddressPreference.select(all, preferIpv6 = true, preferred = null))
        // 窗口内记忆说 v4 好：v4 排前，v6 仍留作兜底
        assertEquals(listOf(v4, v6), AddressPreference.select(all, preferIpv6 = true, preferred = AddressFamily.V4))
        assertTrue(
            "兜底不能被砍掉",
            AddressPreference.select(all, preferIpv6 = true, preferred = AddressFamily.V4).size == 2,
        )
    }
}
