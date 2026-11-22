package com.takahashirinta.ncrust.network

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.1.9：传输层失败后的**地址族回退**（真机 PCL110 实测）。
 *
 * 现场：`interface3.music.163.com` 只有一个 A 记录 `117.135.207.67`，该地址
 * `nc :443` 连续超时；同域名 AAAA `2409:8c6a:b021:1400:0:4:0:3` 的 443 建连成功。
 * `order()` 的「有 v4 就只用 v4」把客户端钉死在死地址上 ⇒ 8s 调用截止 + 重试 + 失败。
 */
class AddressFallbackTest {

    private fun addr(host: String): InetAddress = InetAddress.getByName(host)

    private val v4 = "117.135.207.67"
    private val v6 = "2409:8c6a:b021:1400:0:4:0:3"

    @Test
    fun `默认策略与 order 逐字节一致`() {
        val all = listOf(addr(v6), addr(v4))
        assertEquals(AddressPreference.order(all), AddressPreference.select(all, preferIpv6 = false))
    }

    @Test
    fun `回退窗口内 IPv6 排前、IPv4 保留作兜底`() {
        val all = listOf(addr(v4), addr(v6))
        val selected = AddressPreference.select(all, preferIpv6 = true)
        assertEquals(listOf(addr(v6), addr(v4)), selected)
    }

    @Test
    fun `回退窗口内只有一族时原样返回`() {
        val only4 = listOf(addr(v4))
        val only6 = listOf(addr(v6))
        assertEquals(only4, AddressPreference.select(only4, preferIpv6 = true))
        assertEquals(only6, AddressPreference.select(only6, preferIpv6 = true))
        assertEquals(emptyList<InetAddress>(), AddressPreference.select(emptyList(), preferIpv6 = true))
    }

    @Test
    fun `失败一次切 IPv6、再失败一次切回 v4`() {
        AddressPreference.clearIpv6Preference()
        val now = 1_000_000L
        assertTrue("首次失败应切到 IPv6 优先", AddressPreference.onTransportFailure(now))
        assertTrue(AddressPreference.isIpv6Preferred(now + 1_000L))
        assertTrue("IPv6 优先下再失败应退回 v4-only", !AddressPreference.onTransportFailure(now + 2_000L))
        assertTrue(!AddressPreference.isIpv6Preferred(now + 3_000L))
    }

    @Test
    fun `窗口过期后自动回到 IPv4 优先`() {
        AddressPreference.clearIpv6Preference()
        val now = 5_000_000L
        AddressPreference.preferIpv6For(windowMs = 3_000L, nowMs = now)
        assertTrue(AddressPreference.isIpv6Preferred(now + 2_999L))
        assertTrue(!AddressPreference.isIpv6Preferred(now + 3_000L))
    }

    @Test
    fun `网络切换可以立刻清掉偏置`() {
        AddressPreference.preferIpv6For()
        AddressPreference.clearIpv6Preference()
        assertTrue(!AddressPreference.isIpv6Preferred())
    }
}
