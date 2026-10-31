/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import android.util.Log
import java.net.InetAddress

/**
 * 清掉 **JVM 层的 DNS 正缓存**（v3.8.4）。
 *
 * ## 为什么需要它
 *
 * 用户开关代理（Clash / v2ray 一类 TUN 代理）时，系统的解析结果是**两套形态**：
 *
 * - 代理开：fake-ip 模式常把域名解析成 `198.18.0.0/15` 这样的假地址，由 TUN 接管；
 * - 代理关：解析回真实 IP。
 *
 * `InetAddress` 会在进程内缓存解析结果（Android 上 `networkaddress.cache.ttl` 量级是几十秒），
 * **它不会因为网络切换而失效**。于是「关掉代理」之后的一小段时间里，取链请求仍然往
 * 假地址上连 —— 直接不可达，表现为「未缓存的歌放不出来」，而已缓存的歌不受影响。
 *
 * OkHttp 的连接池可以通过 `evictAll()` 清掉，DNS 缓存没有公开 API，只能反射调
 * `InetAddress.clearDnsCache()`（隐藏方法，Android 上长期可用；失败就静默跳过，
 * 不能因为清缓存失败而影响播放）。
 */
object DnsCache {

    private const val TAG = "DnsCache"

    /** 清空解析缓存；重复调用无副作用。返回是否成功（失败只记日志）。 */
    fun clear(): Boolean {
        val ok = runCatching {
            val method = InetAddress::class.java.getDeclaredMethod("clearDnsCache")
            method.isAccessible = true
            method.invoke(null)
            true
        }.getOrElse { e ->
            Log.w(TAG, "clearDnsCache unavailable: ${e.javaClass.simpleName}")
            false
        }
        if (ok) Log.i(TAG, "DNS cache cleared")
        return ok
    }

    /** 假地址段（fake-ip 常用 `198.18.0.0/15`）—— 代理关掉后这些地址不可达。 */
    internal fun isFakeIp(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 198 && (b == 18 || b == 19)
    }
}
