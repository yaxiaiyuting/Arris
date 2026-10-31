package com.takahashirinta.ncrust.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代理开关与 DNS 形态（v3.8.4）。
 *
 * Clash 一类 TUN 代理在 fake-ip 模式下把域名解析成 `198.18.0.0/15`，关掉代理后应回到真实 IP。
 * JVM 的解析缓存不随网络切换失效，所以「关掉代理后未缓存的歌放不出来」。
 * 这里钉住假地址段的判定，清缓存本身在真机验证。
 */
class DnsCacheTest {

    @Test
    fun `fake-ip 段被识别`() {
        assertTrue(DnsCache.isFakeIp("198.18.0.1"))
        assertTrue(DnsCache.isFakeIp("198.19.255.254"))
    }

    @Test
    fun `真实地址与异常输入不算 fake-ip`() {
        assertFalse(DnsCache.isFakeIp("198.20.0.1"))
        assertFalse(DnsCache.isFakeIp("202.108.0.1"))
        assertFalse(DnsCache.isFakeIp("2001:db8::1"))
        assertFalse(DnsCache.isFakeIp("not-an-ip"))
    }

    @Test
    fun `清缓存不抛异常`() {
        // 反射调用隐藏方法；某些 ROM 上可能不可用，必须静默失败而不是影响播放。
        DnsCache.clear()
    }
}
