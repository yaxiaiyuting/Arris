/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · P0-B：URL 预加载缓存的**新鲜度判据**。纯逻辑，JVM 可单测。
 */

package com.takahashirinta.ncrust.player

/**
 * 一条预加载 URL 缓存条目的**新鲜度判据**（v3.1.0 · P0-B）。**纯函数，JVM 可单测。**
 *
 * ## 为什么把它从 `PlayerViewModel` 里抽出来
 *
 * v3.0.0 及更早，判据是内联在三个调用点上的同一个表达式：
 *
 * ```kotlin
 * preloadCache[songId]?.takeIf {
 *     it.requestedLevel == quality && System.currentTimeMillis() - it.timestamp <= CACHE_TTL_MS
 * }
 * ```
 *
 * 三处各写一遍意味着「改一处漏两处」是可预期的。更要命的是它**只支持一种 TTL 模型**：
 * 「写入时刻 + 固定常量」。而 v3.1.0 接入的 B 站**必须**用另一种模型 ——
 * 它的音频流 URL 自带 `deadline` 参数（实测 `now + 7200s`，而响应里的 `timeout` 是 10800s，
 * 两者不一致），也就是说**过期时刻是服务端给的绝对时间，不是一个时长**。
 *
 * 于是把判据收成一个纯函数，并对两种模型都给出口：
 *
 * | 模型 | 谁在用 | 判据 |
 * |---|---|---|
 * | 显式过期时刻 [PreloadCacheEntry.expiresAtMs] 非空 | B 站（及将来任何给 TTL 的音源） | `now < expiresAtMs` |
 * | 显式过期时刻为空 | ncm / qm（**行为与 v3.0.0 逐字相同**） | `now - timestamp <= ttlMs` |
 *
 * ## 铁律 22 的落点
 *
 * 「URL 预加载必须处理 TTL，过期不得使用」—— 这里就是那个「不得使用」。
 * 过期的条目**不删**（调用方照旧会覆盖写），只是判为不可用：
 * 删它需要让判据产生副作用，而一个会写状态的 `isFresh` 是没法在单测里安心用的。
 */
object PreloadCachePolicy {

    /**
     * ncm / QQ 的默认 TTL（毫秒）。**值必须与 v3.0.0 的 `CACHE_TTL_MS` 逐字相同** ——
     * 这个常量搬了一次家，但语义没有变：那两家的 URL 实测在 5 分钟内可复用。
     */
    const val DEFAULT_TTL_MS: Long = 5 * 60 * 1000L

    /**
     * 这条缓存能不能用。
     *
     * @param entry 缓存条目；null（没有条目）返回 false。
     * @param requestedLevel 本次要用的档位。**必须相等**才可用 ——
     *   降档重试时把上一档（可能已经证明播不出声）的 URL 原样喂回播放器，
     *   是 v2.2.1 修过的 P0。
     * @param nowMs 当前时刻。显式传入让单测能钉死边界（`now == expiresAtMs` 必须判过期）。
     * @param defaultTtlMs 显式过期时刻缺失时的兜底 TTL。
     */
    fun isFresh(
        entry: PreloadCacheEntry?,
        requestedLevel: String,
        nowMs: Long = System.currentTimeMillis(),
        defaultTtlMs: Long = DEFAULT_TTL_MS,
    ): Boolean {
        if (entry == null) return false
        if (entry.requestedLevel != requestedLevel) return false
        val expiry = entry.expiresAtMs
        // ① 显式过期时刻：**边界取「过期」**（now == expiresAt ⇒ 不可用）。
        //    服务端的 deadline 是秒级、我们是毫秒级，保守一侧才不会在边界那一刻拿到 403。
        if (expiry != null) return nowMs < expiry
        // ② 固定 TTL（v3.0.0 的既有模型，逐字不变）。
        return nowMs - entry.timestamp <= defaultTtlMs
    }
}

/**
 * 预加载 URL 缓存条目（v3.1.0 从 `PlayerViewModel` 的私有嵌套类提出来）。
 *
 * 提出来只有一个目的：让 [PreloadCachePolicy] 能在**不碰 Android** 的情况下单测。
 * 字段与语义与 v3.0.0 **逐字相同**，只多了一个可空字段 [expiresAtMs]。
 *
 * ⚠️ 本类型**不落盘**（纯内存），所以按「加字段 = 加迁移逻辑 = 加单测」的规矩
 * 这里不需要迁移逻辑；但新字段的两种取值（null / 非 null）**各自有单测**。
 */
data class PreloadCacheEntry(
    val url: String,
    val actualLevel: String,
    /** 取链时用的请求档位：降档重试时只有档位一致才允许命中缓存。 */
    val requestedLevel: String,
    /** A3：实际文件参数与档位上限，走缓存开播时算音质状态要用。 */
    val br: Long = 0L,
    val type: String = "",
    val songMaxLevel: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * v3.1.0 · B：**绝对**过期时刻；`null` = 本音源没给显式 TTL（ncm / QQ）。
     *
     * 由 `SongUrlResult.expiresAtMs` 一路带过来，唯一的产出者是 Provider 的取链实现
     * （B 站那条从 URL 的 `deadline` 反推）。
     */
    val expiresAtMs: Long? = null,
)
