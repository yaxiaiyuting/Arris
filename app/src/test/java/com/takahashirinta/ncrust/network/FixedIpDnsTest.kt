package com.takahashirinta.ncrust.network

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定 IP 解析（v3.8.1）。
 *
 * 场景：`*.workers.dev` 在国内被 **DNS 污染**（实测平板解析到 104.244.46.165，ping 全丢），
 * 但 Cloudflare Anycast IP 本身可达（实测 104.21.16.1 / 172.67.128.1 用正确 SNI 返回 200）。
 * 所以客户端把「域名用于 SNI/Host、连接目标固定到给到的 IP」，证书校验不变。
 */
class FixedIpDnsTest {

    @Test
    fun `只对指定域名生效 且按给定顺序返回`() {
        val dns = AddressPreference.fixedFor("relay.example.com", listOf("104.21.16.1", "172.67.128.1"))
        val got = dns.lookup("relay.example.com")
        assertEquals(2, got.size)
        assertEquals("104.21.16.1", got[0].hostAddress)
        assertEquals("172.67.128.1", got[1].hostAddress)
    }

    @Test
    fun `其它域名仍走系统解析`() {
        val dns = AddressPreference.fixedFor("relay.example.com", listOf("104.21.16.1"))
        val local = dns.lookup("localhost")
        assertTrue("localhost 应走系统解析", local.isNotEmpty())
        assertTrue(local.all { it.isLoopbackAddress })
    }

    @Test
    fun `IP 列表为空时退回 IPv4 优先`() {
        val dns = AddressPreference.fixedFor("relay.example.com", emptyList())
        val local = dns.lookup("localhost")
        assertTrue(local.isNotEmpty())
    }

    @Test
    fun `非法 IP 串被忽略 不至于整条连接失败`() {
        val dns = AddressPreference.fixedFor("relay.example.com", listOf("not-an-ip", "104.21.16.1"))
        val got = dns.lookup("relay.example.com")
        assertEquals(1, got.size)
        assertEquals("104.21.16.1", got[0].hostAddress)
    }
}
