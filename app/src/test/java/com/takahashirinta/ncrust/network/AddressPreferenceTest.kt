package com.takahashirinta.ncrust.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多地址解析顺序（v3.8.1）。
 *
 * 背景：真机对照实验证明 8s 级超时的直接原因是「DNS 首个 IPv6 地址不回 TCP，
 * 而单地址连接等待长于调用截止」——调用在尝试 IPv4 前就被取消
 * （docs/verification/v3.8.1/address-family.md）。
 *
 * 这里钉住策略语义：**IPv4 前置、IPv6 保底、纯 IPv6 环境保持不变**。
 */
class AddressPreferenceTest {

    private fun addr(host: String): InetAddress = InetAddress.getByName(host)

    @Test
    fun `IPv6 在前时会被排到 IPv4 之后`() {
        val v6 = addr("2409:8c6a:b021:1400:0:4:0:3")
        val v4 = addr("117.135.207.67")
        val ordered = AddressPreference.order(listOf(v6, v4))
        assertEquals("有 IPv4 时就只用 IPv4", listOf(v4), ordered)
    }

    @Test
    fun `多个 IPv4 保持原有相对顺序`() {
        val a = addr("1.1.1.1")
        val b = addr("2.2.2.2")
        val c = addr("2001::1")
        assertEquals(listOf(a, b), AddressPreference.order(listOf(c, a, b)))
    }

    @Test
    fun `纯 IPv6 环境保持原样（不把可达性砍掉）`() {
        val only6 = listOf(addr("2001::1"), addr("2001::2"))
        assertEquals(only6, AddressPreference.order(only6))
        assertTrue(AddressPreference.order(only6).all { it is Inet6Address })
    }

    @Test
    fun `纯 IPv4 与空列表都原样返回`() {
        val only4 = listOf(addr("1.1.1.1"))
        assertEquals(only4, AddressPreference.order(only4))
        assertEquals(emptyList<InetAddress>(), AddressPreference.order(emptyList()))
    }
}
