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
    /** 快照归属的账号指纹（S14）；老版本没有这个 key ⇒ 读成 null ⇒ 视为"无归属"。 */
    private const val KEY_OWNER = "owner"

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
        owner: String? = com.takahashirinta.ncrust.auth.CookieManager.accountOwner(),
    ) {
        if (daily == null && playlists == null && newSongs == null && toplists == null) return
        val app = context.applicationContext
        val started = com.takahashirinta.ncrust.auth.CookieManager.snapshot()
        ioScope.launch {
            runCatching {
                // H15：**归属在发起调用时就已经定了**（默认参数在调用线程求值）。
                //
                // 旧实现在序列化**末尾**读一次 `accountOwner()` 写进 KEY_OWNER：
                // 换账号发生在这次异步写盘的中途时，A 的列表会被贴上 B 的标签落盘，
                // 于是 B 的冷启动 `restoreIntoCache`（它按 owner 过滤）看到的是一份
                // "属于 B" 的 A 的数据。`CookieManager` 换号时清理只能清已经存在的值，
                // 挡不住这个**还没提交**的旧任务。
                //
                // 现在：归属变了就整份丢弃（旧快照留着，它自带旧 owner，恢复时会被过滤掉），
                // 绝不给旧数据盖上新的账号标签。
                if (!sameOwner(owner, com.takahashirinta.ncrust.auth.CookieManager.accountOwner())) {
                    return@runCatching
                }
                // S13：语义定死 —— `null` = 本次没提供（保持旧值）；
                // 空列表 = 服务端明确给了空表 ⇒ **必须覆盖**，否则时间戳更新了、
                // 旧推荐却在下次冷启动又冒出来。
                val editor = prefs(app).edit()
                daily?.let {
                    if (it.isEmpty()) editor.remove(KEY_DAILY)
                    else editor.putString(KEY_DAILY, gson.toJson(it.take(MAX_ITEMS), songListType))
                }
                playlists?.let {
                    if (it.isEmpty()) editor.remove(KEY_PLAYLISTS)
                    else editor.putString(KEY_PLAYLISTS, gson.toJson(it.take(MAX_ITEMS), cardListType))
                }
                newSongs?.let {
                    if (it.isEmpty()) editor.remove(KEY_NEW_SONGS)
                    else editor.putString(KEY_NEW_SONGS, gson.toJson(it.take(MAX_ITEMS), songListType))
                }
                toplists?.let {
                    if (it.isEmpty()) editor.remove(KEY_TOPLISTS)
                    else editor.putString(KEY_TOPLISTS, gson.toJson(it.take(MAX_ITEMS), cardListType))
                }
                editor.putLong(KEY_SAVED_AT, System.currentTimeMillis())
                // 归属随快照一起落盘：换账号后 restore 会被 owner 过滤掉，不会读到别人的推荐。
                // 这里写的是**调用时捕获的** owner（H15），不是在收尾时重新读的"当前账号"。
                if (owner == null) editor.remove(KEY_OWNER) else editor.putString(KEY_OWNER, owner)
                com.takahashirinta.ncrust.auth.CookieManager.ifCurrent(started) {
                    if (sameOwner(owner, com.takahashirinta.ncrust.auth.CookieManager.accountOwner())) editor.apply()
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
        val p = runCatching { prefs(context) }.getOrNull() ?: return false
        // S14：账号不匹配就整份不恢复（个性化块）。公共榜单在 owner 缺失时仍可复用，
        // 但它和个性化内容存在同一个快照里，这里统一按"整份不可信"处理更安全。
        val current = com.takahashirinta.ncrust.auth.CookieManager.accountOwner()
        val stored = p.getString(KEY_OWNER, null)
        if (!sameOwner(stored, current)) return false
        val started = com.takahashirinta.ncrust.auth.CookieManager.snapshot()
        val daily = readList<SongItem>(p, KEY_DAILY, songListType)
        val playlists = readList<PlaylistApi.PlaylistCard>(p, KEY_PLAYLISTS, cardListType)
        val newSongs = readList<SongItem>(p, KEY_NEW_SONGS, songListType)
        val toplists = readList<PlaylistApi.PlaylistCard>(p, KEY_TOPLISTS, cardListType)
        return com.takahashirinta.ncrust.auth.CookieManager.ifCurrent(started) {
            if (!sameOwner(stored, com.takahashirinta.ncrust.auth.CookieManager.accountOwner())) return@ifCurrent false
            ContentCache.bindOwner(current)
            var restored = false
            daily?.let { if (ContentCache.homeDailySongs == null) { ContentCache.homeDailySongs = it; restored = true } }
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

    /**
     * 两份归属是不是**同一个账号**（H15 的唯一判据，纯函数、JVM 可单测）。
     *
     * 单拎出来而不是直接写 `a == b`，是为了把「null 该不该等于 null」这件事钉成一条
     * 有注释的约定：**未登录（null）与未登录是同一个归属**（游客也有"自己的"首页），
     * 而「未登录 → 已登录」永远是换号。写成可测的函数之后，
     * `save` 的提交闸门与 `restoreIntoCache` 的读取闸门也共用同一条规则。
     */
    internal fun sameOwner(captured: String?, current: String?): Boolean = captured == current

    private fun <T> readList(p: SharedPreferences, key: String, type: java.lang.reflect.Type): List<T>? {
        val raw = p.getString(key, null) ?: return null
        return runCatching { gson.fromJson<List<T>>(raw, type) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
