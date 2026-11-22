package com.takahashirinta.ncrust.network

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.1.9：**地址族回退真的作用到了 OkHttp 的路由表上**（不是只有纯函数对）。
 *
 * 纯函数单测只能证明 `select()` 返回的顺序；这条用真实 OkHttp 打本地端口，记录
 * `connectStart` 的实际尝试顺序，证明 DNS 的返回值确实决定了 OkHttp 先连哪个地址。
 * 真机上缺的正是这条链路证据 —— 「策略切了、日志里却只看到 v4」时无法判断是谁没生效。
 */
class AddressFamilyOrderingTest {

    private fun requestedAddresses(dns: Dns, port: Int): List<String> = run {
        val attempted = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun connectStart(
                    call: Call,
                    inetSocketAddress: InetSocketAddress,
                    proxy: Proxy,
                ) {
                    inetSocketAddress.address?.hostAddress?.let { attempted += it }
                }
            })
            .build()
        runCatching {
            client.newCall(
                Request.Builder().url("http://ncrust.invalid:$port/").build(),
            ).execute().close()
        }
        attempted.toList()
    }

    private fun fixedDns(vararg addresses: InetAddress): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses.toList()
    }

    private fun withServer(block: (Int) -> Unit) {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        try {
            val acceptor = thread(isDaemon = true) {
                runCatching {
                    repeat(4) {
                        server.accept().close()
                    }
                }
            }
            block(server.localPort)
            acceptor.join(3_000)
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun `默认策略先连 IPv4`() = withServer { port ->
        AddressPreference.clearIpv6Preference()
        val v4 = InetAddress.getByName("127.0.0.1")
        val v6 = InetAddress.getByName("::1")
        // 顺序故意给 v6 在前：默认策略必须把它过滤掉，只留 v4。
        val dns = AddressPreference.IPv4FirstDns(fixedDns(v6, v4))
        val attempted = requestedAddresses(dns, port)
        assertTrue("默认策略不该尝试 IPv6：$attempted", attempted.all { it == "127.0.0.1" })
    }

    @Test
    fun `回退窗口内先连 IPv6、失败后再落到 IPv4`() = withServer { port ->
        val v4 = InetAddress.getByName("127.0.0.1")
        val v6 = InetAddress.getByName("::1")
        AddressPreference.preferIpv6For(windowMs = 60_000L)
        try {
            val dns = AddressPreference.IPv4FirstDns(fixedDns(v4, v6))
            val attempted = requestedAddresses(dns, port)
            assertTrue("回退窗口内必须先尝试 IPv6：$attempted", attempted.isNotEmpty())
            assertTrue(
                "首个尝试地址应是 IPv6，实际：$attempted",
                InetAddress.getByName(attempted.first()) is Inet6Address,
            )
            assertEquals("IPv4 必须仍作为兜底保留在最后", "127.0.0.1", attempted.last())
        } finally {
            AddressPreference.clearIpv6Preference()
        }
    }
}
