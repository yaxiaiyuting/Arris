/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.listen

import android.content.Context

/**
 * 一起听的中继配置（v3.8.1）。
 *
 * ## 为什么必须可配置
 *
 * `*.workers.dev` 在国内网络基本不可达（被墙），所以**内置默认地址只适合当占位**。
 * 用户需要能把它换成：
 * - 自己域名下的同一个 Worker（绑定自定义域名后走 Cloudflare 的常规 Anycast，通常可达）
 * - 自建中继（任何实现了 `/room/<id>` WebSocket + `/rooms` 的服务）
 * - 局域网直连（`IP:端口`，不需要任何服务器）
 *
 * 存在 `ncrust_settings` 里，不上传、不参与同步。
 */
object TogetherPrefs {

    private const val PREFS = "ncrust_settings"
    private const val KEY_BASE = "together_relay_base"
    private const val KEY_IPS = "together_relay_ips"

    /** 默认值：我们部署的 Worker（**仅作占位**，国内多半连不上）。 */
    const val DEFAULT_BASE = "https://arris-listen.arris-listen.workers.dev"

    fun base(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BASE

    fun setBase(context: Context, value: String) {
        val normalized = normalize(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE, normalized)
            .apply()
    }

    /**
     * 固定 IP 列表（逗号分隔，可空）。
     *
     * ## 为什么需要这个
     *
     * `*.workers.dev` 在国内被 **DNS 污染**（实测平板解析到 104.244.46.165，ping 全丢），
     * 但 Cloudflare 的 Anycast IP 本身可达 —— 实测不带代理直连
     * `104.21.16.1` / `172.67.128.1` 用正确 SNI 访问我们的 Worker 都返回 200。
     *
     * 所以客户端支持「域名用于 SNI/Host，连接目标固定到这几个 IP」：
     * TLS 证书校验仍然按域名，安全性与正常访问一致，只是绕开被污染的 DNS。
     */
    fun ips(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_IPS, null)
            ?.split(',', ' ', ';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.filter { ip -> ip.all { it.isDigit() || it == '.' } }
            .orEmpty()

    fun setIps(context: Context, raw: String) {
        val cleaned = raw.split(',', ' ', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.all { c -> c.isDigit() || c == '.' } }
            .joinToString(",")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_IPS, cleaned)
            .apply()
    }

    /** 默认优选 IP（2026-10-30 实测可用：直连返回 200）。 */
    const val DEFAULT_IPS = "104.21.16.1,172.67.128.1"

    /** 容忍用户只输入域名（补 https://），去掉尾部斜杠。 */
    fun normalize(raw: String): String {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty()) return DEFAULT_BASE
        return when {
            t.startsWith("http://") || t.startsWith("https://") ||
                t.startsWith("ws://") || t.startsWith("wss://") -> t
            else -> "https://$t"
        }
    }
}
