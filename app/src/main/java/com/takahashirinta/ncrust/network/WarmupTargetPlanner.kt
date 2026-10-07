/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.2 · P1：连接预热目标清单的**纯逻辑**。
 */

package com.takahashirinta.ncrust.network

/**
 * 「该预热哪些 host」的判定，抽成纯函数（JVM 可单测，不碰 Android、不碰网络）。
 *
 * ## 为什么这条判定值得单测（v3.3.2 的缺陷就是它错了）
 *
 * v3.1.0 的 [ConnectionWarmup] 把目标写成了一张**手抄的常量表**：
 * ```kotlin
 * private val HOSTS = listOf("https://interface3.music.163.com/", "https://music.163.com/")
 * ```
 * 手抄本身不是错 —— 错的是它与「业务真正会打的 host」之间**没有任何机制保证一致**。
 * 实测复核时发现更糟的一层：预热用的是**自己新建的 `OkHttpClient`**，
 * 而 OkHttp 的连接复用要求「同池 **且** Address 兼容」，
 * 它两条都不满足（详见 `RetrofitClient.baseClient` 的 KDoc）——
 * 于是这两条预热连接进了一个业务侧永远不会去查的池子。
 *
 * 所以这里把「目标从哪来」改成**由业务端点常量推导**：只要
 * [ConnectionWarmup] 的清单来自本函数、而本函数的输入来自 `RetrofitClient` 的
 * 端点常量，就再也不会出现「预热了一个没人调的 host」或「漏了取链那个 host」——
 * 那两种错都不会报错，只会静默地让优化等于零。
 *
 * ## 三条边界（都有单测）
 *
 * 1. **去重**：`interface.music.163.com` 与 `interface3.music.163.com` 是**两个**
 *    host（eapi 默认域 vs 取链域），都必须留；但同一个 host 写两遍只预热一次。
 * 2. **有界**：预热是**尽力而为**的旁路，清单长度必须有上限，
 *    否则「加一个音源就多几通冷启动请求」，与铁律 24（未启用的音源不产生流量）冲突。
 * 3. **跳过集**：留给「这条路径当前不该产生流量」的显式排除（大小写不敏感）。
 */
internal object WarmupTargetPlanner {

    /**
     * 预热目标上限。
     *
     * 取 **4** 而不是「有多少写多少」：本应用同时活跃的 host 恰好 4 个
     * （取链 `interface3`、eapi `interface`、REST `music`、歌词镜像 amll 在另一套客户端里），
     * 而并发的冷连接越多，每条分到的带宽越少、首通反而更慢
     * （铁律 23：并行必须有上限，理由与 `BoundedParallel.DEFAULT_MAX_CONCURRENCY` 同源）。
     */
    const val MAX_TARGETS = 4

    /**
     * 把候选 host 整理成「有序、去重、有界」的预热清单。
     *
     * @param candidates 业务侧真正会打到的 host，**顺序即优先级**（越靠前越先预热）。
     *   允许带 scheme 与结尾斜杠，也允许只有 host。空串/空白项被丢弃。
     * @param skip 不预热的 host（大小写不敏感，按规范化后的 `host` 比较）。
     * @param limit 上限；`<= 0` 时返回空清单（配置错误不该让调用方拿到「全部」）。
     * @return 规范化后的 host（**不含** scheme、不含结尾斜杠），可能为空。
     */
    fun plan(
        candidates: List<String>,
        skip: Set<String> = emptySet(),
        limit: Int = MAX_TARGETS,
    ): List<String> {
        if (limit <= 0) return emptyList()
        val skipped = skip.mapNotNullTo(HashSet()) { normalizeHost(it) }
        val out = ArrayList<String>(minOf(candidates.size, limit))
        val seen = HashSet<String>()
        for (raw in candidates) {
            val host = normalizeHost(raw) ?: continue
            if (host in skipped) continue
            if (!seen.add(host)) continue
            out.add(host)
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * `https://interface3.music.163.com/` → `interface3.music.163.com`。
     *
     * 用字符串处理而不是 `java.net.URI`：本函数在**冷启动路径**上被调用，
     * 而 `URI` 会为每个输入做一次完整解析与异常构造；这里的输入形状是固定的常量。
     * 纯逻辑保持零依赖也让单测不需要 Robolectric。
     *
     * @return null = 这一项不是可用的 host（空串、只有 scheme、全是斜杠）。
     */
    fun normalizeHost(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        // 去 scheme（大小写不敏感）
        val schemeAt = s.indexOf("://")
        if (schemeAt >= 0) s = s.substring(schemeAt + 3)
        // 去 path / query / fragment / userinfo
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        // 去端口（预热按 host 复用连接，端口不参与这里的判重）
        if (!s.startsWith("[")) s = s.substringBefore(':')   // IPv6 字面量 [::1] 不剪
        s = s.trim().lowercase()
        return s.takeIf { it.isNotEmpty() }
    }
}
