/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.reco

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.PlaylistApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 首页「音乐人推荐」卡片的触发配置与判定（v1.4.0 · 任务 B）。
 *
 * ## 触发设计（B0 实测后定稿，见 commit 说明）
 * 原方案「对收藏艺人的 simiArtist 列表做匹配」**不可行**：B0 实测
 *  - 任务里写的 `/eapi/simi/artist` 根本不存在（匿名与登录态都是 404）；
 *  - 真实端点是 `/eapi/discovery/simiArtist`（参数名 `artistid`，匿名 301、必须登录）；
 *  - 但目标艺人 122618229 自己的相似列表返回空，本账号 top20 收藏艺人的相似列表里
 *    也 0 次命中它 —— 这条路对目标艺人永远不触发。
 *
 * 因此采用任务里预先约定的**降级方案：风格锚点艺人**——
 * 「用户收藏单曲的艺人 ∩ 锚点 ≠ ∅」即认为口味命中，在首页插入卡片。
 * 锚点有两个来源（并集）：
 *  1. 手动配置：prefs `artist_reco_anchor_ids`（CSV，默认空）；
 *  2. 自动推导：目标艺人的热门曲 → simiSong → 结果里的其他艺人（7 天 TTL 缓存）。
 *
 * ## 不影响其他用户
 * 所有配置默认空：`artist_reco_enabled=false`、`artist_reco_target_id=0`，
 * 此时 [shouldShow] 恒为 false，卡片不显示、也不发任何请求。
 */
object ArtistReco {
    private const val TAG = "ArtistReco"
    private const val PREFS = "ncrust_settings"

    const val KEY_ENABLED = "artist_reco_enabled"
    const val KEY_TARGET = "artist_reco_target_id"
    const val KEY_ANCHORS = "artist_reco_anchor_ids"
    private const val KEY_AUTO_ANCHORS = "artist_reco_auto_anchor_ids"
    private const val KEY_AUTO_AT = "artist_reco_auto_anchor_at"

    /** 自动锚点缓存有效期：7 天。 */
    private const val AUTO_TTL_MS = 7L * 24 * 60 * 60 * 1000
    /** 推导锚点时取样几首目标艺人的热门曲（每首一次 simiSong 请求）。 */
    private const val AUTO_SEED_SONGS = 3

    /**
     * 自动锚点数量上限（v1.5.0 · A）。
     *
     * 3 首种子曲 × 每首最多 20 条相似歌曲 —— 不做裁剪时锚点集合可能膨胀到 60 个，
     * 而锚点集合就是「口味命中」的判定集，越大越容易对任何歌单都命中，卡片就失去意义。
     * 按频次降序取前 20：只保留「在多首种子曲里反复出现」的高置信度艺人。
     * S6 实测自动推导出 9 个（低于上限），因此本上限对现有账号是零行为变化。
     */
    internal const val AUTO_MAX_ANCHORS = 20

    /**
     * 单飞锁：MainActivity 每次切页都会调 [refreshAutoAnchors]，用户快速连续切页会在
     * TTL 写回之前并发进入，每个调用都走一遍网络 —— 同一份推导被重复执行多次。
     * 加锁后只有第一个真正跑推导，其余在锁内二次检查 TTL 后直接读缓存。
     */
    private val autoRefreshLock = Mutex()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun targetId(ctx: Context): Long = prefs(ctx).getLong(KEY_TARGET, 0L)

    fun setTarget(ctx: Context, artistId: Long) {
        prefs(ctx).edit().putLong(KEY_TARGET, artistId).apply()
    }

    fun manualAnchors(ctx: Context): Set<Long> = parseIds(prefs(ctx).getString(KEY_ANCHORS, "").orEmpty())

    fun setManualAnchors(ctx: Context, ids: Collection<Long>) {
        prefs(ctx).edit().putString(KEY_ANCHORS, ids.joinToString(",")).apply()
    }

    fun autoAnchors(ctx: Context): Set<Long> =
        parseIds(prefs(ctx).getString(KEY_AUTO_ANCHORS, "").orEmpty())

    private fun autoAnchorsFresh(ctx: Context): Boolean {
        val at = prefs(ctx).getLong(KEY_AUTO_AT, 0L)
        return at > 0L && System.currentTimeMillis() - at < AUTO_TTL_MS
    }

    private fun parseIds(raw: String): Set<Long> =
        raw.split(',', ';', ' ').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toLongOrNull() }.toSet()

    /** 配置是否完整：开启 + 配了目标艺人 + 至少有一个可用锚点。 */
    fun isConfigured(ctx: Context): Boolean =
        isEnabled(ctx) && targetId(ctx) != 0L && (manualAnchors(ctx) + autoAnchors(ctx)).isNotEmpty()

    /**
     * 口味命中判定：收藏单曲（本地已加载部分）里的艺人只要有一个落在锚点集合里，
     * 就认为"与目标艺人风格相近"，首页显示卡片。纯本地计算，不发请求。
     */
    fun shouldShow(ctx: Context): Boolean {
        if (!isConfigured(ctx)) return false
        val anchors = manualAnchors(ctx) + autoAnchors(ctx)
        val mine = LibraryManager.getSavedSongs(ctx)
            .flatMap { it.artists.orEmpty() }
            .mapNotNull { it.id }
            .toHashSet()
        return mine.any { it in anchors }
    }

    /**
     * 自动推导锚点：目标艺人的热门曲 → [PlaylistApi.getSimilarSongs] → 结果里除目标之外的艺人。
     * 带 7 天 TTL；force=true 时无视 TTL。返回推导后的锚点集合（失败时返回既有缓存）。
     *
     * v1.5.0 · A 的三点收敛（S6 真机实测后定稿）：
     *  1. **去重**：以艺人 id 为 key 收进 map，同一艺人无论命中多少次只留一个；
     *  2. **排序**：按「在几首种子曲的相似列表里出现过」降序，同频次保持首次出现顺序
     *     （稳定排序），再截断到 [AUTO_MAX_ANCHORS]；
     *  3. **缓存**：7 天 TTL 之内不再发请求；单飞锁保证并发切页只推导一次；
     *     推导结果为空时**保留旧缓存**而不是写空。
     */
    suspend fun refreshAutoAnchors(ctx: Context, force: Boolean = false): Set<Long> {
        val target = targetId(ctx)
        if (target == 0L) return emptySet()
        if (!force && autoAnchorsFresh(ctx)) return autoAnchors(ctx)
        return autoRefreshLock.withLock {
            // 双检：等锁期间可能已经有别的调用把 TTL 刷好了。
            if (!force && autoAnchorsFresh(ctx)) return@withLock autoAnchors(ctx)
            try {
                val seeds = PlaylistApi.getArtistTopSongIds(target, AUTO_SEED_SONGS)
                // 去重 + 频次统计：LinkedHashMap 的 key 天然去重，value 记「在几首种子曲的
                // 相似列表里出现过」；迭代顺序 = 首次出现顺序，稳定排序会保留它。
                val hits = LinkedHashMap<Long, Int>()
                var okSeeds = 0
                for (seed in seeds) {
                    val songs = runCatching { PlaylistApi.getSimilarSongs(seed) }.getOrNull() ?: continue
                    okSeeds++
                    for (s in songs) {
                        for (a in s.artists.orEmpty()) {
                            val aid = a.id ?: continue
                            if (aid == target) continue
                            hits[aid] = (hits[aid] ?: 0) + 1
                        }
                    }
                }
                val found = rankAnchors(hits, target).toCollection(linkedSetOf())
                if (found.isEmpty()) {
                    // 空结果不代表「没有相似艺人」，更可能是网络抖动 / 端点限流。
                    // 直接写回会把上一份好缓存冲掉、卡片静默消失到 TTL 结束 —— 保留旧值。
                    Log.w(TAG, "refreshAutoAnchors: empty (seeds=${seeds.size} okSeeds=$okSeeds), keep cached ${autoAnchors(ctx).size}")
                    return@withLock autoAnchors(ctx)
                }
                // 手动锚点永远不写回自动缓存；两者在读取处取并集。
                prefs(ctx).edit()
                    .putString(KEY_AUTO_ANCHORS, found.joinToString(","))
                    .putLong(KEY_AUTO_AT, System.currentTimeMillis())
                    .apply()
                // 推导过程此前完全不可观测（只在失败时 Log.e），线上无法判断卡片为何不出现。
                Log.i(TAG, "refreshAutoAnchors target=$target seeds=${seeds.size}/$okSeeds candidates=${hits.size} kept=${found.size}: ${found.joinToString(",")}")
                found
            } catch (e: Exception) {
                Log.e(TAG, "refreshAutoAnchors failed: ${e.message}")
                autoAnchors(ctx)
            }
        }
    }

    /**
     * 纯逻辑：把「艺人 id → 在几首种子曲的相似列表里出现过」的统计表排成最终锚点列表。
     *
     *  - 去重：map 的 key 天然唯一，同一艺人无论命中多少次都只出现一次；
     *  - 排序：按频次降序；Kotlin 的 sortedByDescending 是稳定排序，同频次保持首次出现
     *    顺序，所以同一份服务端数据每次推导结果完全一致（可复现、可与历史对比）；
     *  - 截断：最多 [max] 个，按置信度保留；
     *  - 过滤：目标艺人自身与非法 id（<= 0）永远不进锚点集。
     *
     * 抽成纯函数是为了能在 JVM 单测里直接验证排序/截断/过滤，不必起设备。
     */
    internal fun rankAnchors(hits: Map<Long, Int>, target: Long, max: Int = AUTO_MAX_ANCHORS): List<Long> =
        hits.entries
            .filter { it.key != target && it.key > 0L }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(max)
}
