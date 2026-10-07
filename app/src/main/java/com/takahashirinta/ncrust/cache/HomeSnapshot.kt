/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v1.5.1 · C —— 首页数据的**磁盘**快照。
 *
 * [ContentCache] 是纯内存的（进程被杀就没了），所以「断网重启还能看到上次的内容」
 * 必须落盘。这里只做一件事：把首页那四块数据（每日推荐 / 推荐歌单 / 新歌 / 榜单）
 * 用 Gson 写进一个独立的 SharedPreferences，冷启动时再灌回 ContentCache。
 *
 * 为什么不复用 ContentCache 的写法：它的注释明确写了「网络加载的临时数据快照，
 * 不需要持久化」；这里不推翻那个决定，而是**另加一层只读优先、可随时丢弃的快照**：
 *  - 灌进 ContentCache 后，一切照旧（有网时后台刷新立刻覆盖）；
 *  - 任何一步失败都被吞掉（快照是"锦上添花"，绝不因为它坏了而影响启动）。
 *
 * 体积：四块各截断到 [MAX_ITEMS] 条，实测整包 JSON 约百 KB 量级，
 * 解析放在 IO 线程（AppWarmup），主线程只读已经解析好的内存值。
 */
object HomeSnapshot {

    private const val PREFS_NAME = "ncrust_home_cache"
    private const val KEY_DAILY = "daily_songs"
    private const val KEY_PLAYLISTS = "recommend_playlists"
    private const val KEY_NEW_SONGS = "new_songs"
    private const val KEY_TOPLISTS = "toplists"
    private const val KEY_SAVED_AT = "saved_at"

    /** 每块最多存多少条：首屏够用即可，避免 prefs 无限膨胀。 */
    private const val MAX_ITEMS = 60

    private val gson = Gson()
    private val songListType = object : TypeToken<List<SongItem>>() {}.type
    private val cardListType = object : TypeToken<List<PlaylistApi.PlaylistCard>>() {}.type

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 最近一次成功写入的时间戳（0 = 从来没写过）。 */
    fun savedAt(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_SAVED_AT, 0L) }.getOrDefault(0L)

    /**
     * 异步落盘。传 null 的块保持原值不变（例如只刷新了新歌，就别把榜单冲掉）。
     * 任何异常都被吞掉 —— 快照写失败不该影响任何用户可见行为。
     */
    fun save(
        context: Context,
        daily: List<SongItem>? = null,
        playlists: List<PlaylistApi.PlaylistCard>? = null,
        newSongs: List<SongItem>? = null,
        toplists: List<PlaylistApi.PlaylistCard>? = null,
    ) {
        if (daily == null && playlists == null && newSongs == null && toplists == null) return
        val app = context.applicationContext
        ioScope.launch {
            runCatching {
                val editor = prefs(app).edit()
                daily?.takeIf { it.isNotEmpty() }
                    ?.let { editor.putString(KEY_DAILY, gson.toJson(it.take(MAX_ITEMS), songListType)) }
                playlists?.takeIf { it.isNotEmpty() }
                    ?.let { editor.putString(KEY_PLAYLISTS, gson.toJson(it.take(MAX_ITEMS), cardListType)) }
                newSongs?.takeIf { it.isNotEmpty() }
                    ?.let { editor.putString(KEY_NEW_SONGS, gson.toJson(it.take(MAX_ITEMS), songListType)) }
                toplists?.takeIf { it.isNotEmpty() }
                    ?.let { editor.putString(KEY_TOPLISTS, gson.toJson(it.take(MAX_ITEMS), cardListType)) }
                editor.putLong(KEY_SAVED_AT, System.currentTimeMillis())
                editor.apply()
            }
        }
    }

    /**
     * 把快照灌回 [ContentCache]（**只在内存缓存里没有该块时才灌**，避免把 warmup
     * 刚拿到的更新数据覆盖回旧快照）。返回是否灌进去至少一块。
     *
     * 调用方应在 IO 线程调用（有磁盘读 + JSON 解析）。
     */
    fun restoreIntoCache(context: Context): Boolean {
        val p = runCatching { prefs(context) }.getOrNull() ?: return false
        var restored = false
        if (ContentCache.homeDailySongs == null) {
            readList<SongItem>(p, KEY_DAILY, songListType)?.let { ContentCache.homeDailySongs = it; restored = true }
        }
        if (ContentCache.homeRecommendPlaylists == null) {
            readList<PlaylistApi.PlaylistCard>(p, KEY_PLAYLISTS, cardListType)?.let { ContentCache.homeRecommendPlaylists = it; restored = true }
        }
        if (ContentCache.homeNewSongs == null) {
            readList<SongItem>(p, KEY_NEW_SONGS, songListType)?.let { ContentCache.homeNewSongs = it; restored = true }
        }
        if (ContentCache.toplistItems == null) {
            readList<PlaylistApi.PlaylistCard>(p, KEY_TOPLISTS, cardListType)?.let { ContentCache.putToplist(it); restored = true }
        }
        return restored
    }

    private fun <T> readList(p: SharedPreferences, key: String, type: java.lang.reflect.Type): List<T>? {
        val raw = p.getString(key, null) ?: return null
        return runCatching { gson.fromJson<List<T>>(raw, type) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
