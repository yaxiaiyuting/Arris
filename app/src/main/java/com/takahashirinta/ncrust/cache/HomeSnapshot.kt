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
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import java.lang.reflect.Type
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 首页的离线快照。每块最多保存 [MAX_ITEMS] 条，冷启动时补入 [ContentCache]。 */
object HomeSnapshot {

    private const val PREFS_NAME = "ncrust_home_cache"
    private const val KEY_SAVED_AT = "saved_at"
    private const val KEY_DAILY_DAY = "daily_reco_daykey"
    /** 快照归属的账号指纹（S14）；老版本没有这个 key ⇒ 读成 null ⇒ 视为"无归属"。 */
    private const val KEY_OWNER = "owner"

    /** 每块最多存多少条：首屏够用即可，避免 prefs 无限膨胀。 */
    private const val MAX_ITEMS = 60

    private val gson = Gson()
    private val songListType = object : TypeToken<List<SongItem>>() {}.type
    private val cardListType = object : TypeToken<List<PlaylistApi.PlaylistCard>>() {}.type

    private enum class Section(val key: String) {
        DAILY("daily_songs"),
        PLAYLISTS("recommend_playlists"),
        NEW_SONGS("new_songs"),
        TOPLISTS("toplists"),
    }

    private data class Update(val section: Section, val json: String?)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeSequence = AtomicLong()
    // 在会话锁内访问。较早的写入仍可补全它独有的栏目。
    private val writtenSequence = LongArray(Section.entries.size)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 最近一次成功写入的时间戳（0 = 从来没写过）。 */
    fun savedAt(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_SAVED_AT, 0L) }.getOrDefault(0L)

    /**
     * 异步更新快照。同一账号下，null 保留旧栏目，空列表清除旧栏目。
     *
     * @param owner 这份快照**属于哪个账号**。默认值在**调用线程**求值 —— 也就是
     *   在「请求发出 / 列表产生」的那一刻，而不是在工作线程收尾时（H15，见下）。
     */
    fun save(
        context: Context,
        daily: List<SongItem>? = null,
        playlists: List<PlaylistApi.PlaylistCard>? = null,
        newSongs: List<SongItem>? = null,
        toplists: List<PlaylistApi.PlaylistCard>? = null,
        owner: String? = CookieManager.accountOwner(),
        dailyDayKey: String? = null,
    ) {
        if (daily == null && playlists == null && newSongs == null && toplists == null) return
        val started = CookieManager.snapshot()
        if (!sameOwner(owner, started.accountOwner)) return
        val sequence = writeSequence.incrementAndGet()
        val app = context.applicationContext
        ioScope.launch {
            runCatching {
                if (!CookieManager.isCurrent(started)) return@runCatching
                val updates = listOfNotNull(
                    encode(Section.DAILY, daily, songListType),
                    encode(Section.PLAYLISTS, playlists, cardListType),
                    encode(Section.NEW_SONGS, newSongs, songListType),
                    encode(Section.TOPLISTS, toplists, cardListType),
                )
                val storage = prefs(app)
                val editor = storage.edit()
                CookieManager.ifCurrent(started) {
                    val pending = updates.filter { sequence > writtenSequence[it.section.ordinal] }
                    if (pending.isEmpty()) return@ifCurrent

                    if (!sameOwner(storage.all[KEY_OWNER], owner)) editor.clear()
                    for ((section, json) in pending) {
                        if (json == null) editor.remove(section.key) else editor.putString(section.key, json)
                        if (section == Section.DAILY) {
                            if (json == null || dailyDayKey == null) editor.remove(KEY_DAILY_DAY)
                            else editor.putString(KEY_DAILY_DAY, dailyDayKey)
                        }
                    }
                    editor.putLong(KEY_SAVED_AT, System.currentTimeMillis())
                    if (owner == null) editor.remove(KEY_OWNER) else editor.putString(KEY_OWNER, owner)
                    editor.apply()
                    for (update in pending) writtenSequence[update.section.ordinal] = sequence
                }
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
        val started = CookieManager.snapshot()
        val values = runCatching { prefs(context).all }.getOrNull() ?: return false
        if (!sameOwner(values[KEY_OWNER], started.accountOwner)) return false
        val daily = readList<SongItem>(values[Section.DAILY.key], songListType)
        val dailyDayKey = values[KEY_DAILY_DAY] as? String
        val playlists = readList<PlaylistApi.PlaylistCard>(values[Section.PLAYLISTS.key], cardListType)
        val newSongs = readList<SongItem>(values[Section.NEW_SONGS.key], songListType)
        val toplists = readList<PlaylistApi.PlaylistCard>(values[Section.TOPLISTS.key], cardListType)
        return CookieManager.ifCurrent(started) {
            ContentCache.bindOwner(started.accountOwner)
            var restored = false
            if (daily != null && ContentCache.homeDailySongs == null) {
                ContentCache.putHomeDailySongs(daily, dailyDayKey)
                restored = true
            }
            playlists?.let { if (ContentCache.homeRecommendPlaylists == null) { ContentCache.homeRecommendPlaylists = it; restored = true } }
            newSongs?.let { if (ContentCache.homeNewSongs == null) { ContentCache.homeNewSongs = it; restored = true } }
            toplists?.let { if (ContentCache.toplistItems == null) { ContentCache.putToplist(it); restored = true } }
            restored
        } ?: false
    }

    /** 清除整份首页快照（登出/换账号时调用）。 */
    fun clear(context: Context) {
        runCatching { prefs(context).edit().clear().apply() }
    }

    /** null 是游客归属，其他类型的磁盘值视为不匹配。 */
    internal fun sameOwner(captured: Any?, current: String?): Boolean = captured == current

    private fun encode(section: Section, items: List<*>?, type: Type): Update? {
        if (items == null) return null
        val json = if (items.isEmpty()) null else gson.toJson(items.take(MAX_ITEMS), type)
        return Update(section, json)
    }

    private fun <T> readList(value: Any?, type: Type): List<T>? {
        val json = value as? String ?: return null
        return runCatching { gson.fromJson<List<T>>(json, type) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
