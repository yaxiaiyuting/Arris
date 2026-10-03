/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：QQ 歌单缓存的落盘（应用私有目录，按 source + ownerId + playlistId 隔离）。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import android.content.SharedPreferences
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.playlist.PlaylistCacheCodec
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.Playlist
import com.takahashirinta.ncrust.source.PlaylistKey

/**
 * QQ 歌单的本地缓存（v2.2.0）。
 *
 * ## 隐私（任务书第 13 条）
 *
 * - 落盘位置：`SharedPreferences`，`Context.MODE_PRIVATE` ⇒ **应用私有目录**
 *   （`/data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_qq_playlists.xml`），
 *   其它应用读不到（除非设备已 root —— 那是设备所有者自己的能力，不是本应用的泄露）；
 * - **不上报**：本应用不把歌单数据发给任何第三方（QQ 官方的接口除外，那是数据来源本身）；
 * - **不写明文日志**：本文件与整条链路上，歌单名/歌名/昵称/uin **都不进 `Log`**
 *   （只打 id、数量、错误码）。
 *
 * ## 缓存 key = `source + ownerId + playlistId`
 *
 * 单条详情用 `detail:<source>:<ownerId>:<tid>`，列表用 `list:<source>:<ownerId>`。
 * 三元组缺一不可：
 * - 少了 `source`：将来接入更多音源时，同名 id 会互相覆盖；
 * - 少了 `ownerId`：**换账号会读到上一个账号的歌单**（这是本版最不能出的错）。
 *
 * 键里带了 `ownerId` 之外，[PlaylistCacheCodec] 在读取时还会**再校验一次** JSON 里的
 * `ownerId`（[PlaylistCacheCodec.DropReason.OWNER_MISMATCH]）—— 两道闸门，
 * 防的是「同一份 JSON 被写进了错误的 key」这种写侧 bug。
 *
 * ## 容量
 *
 * 详情条目按 [MAX_DETAIL_ENTRIES] 条 LRU 上限裁剪（按 `savedAt` 最旧优先）。
 * 不设上限的话，一个重度用户翻过的每个歌单都会永久留在 prefs 里，
 * 而 `SharedPreferences` 是**整个文件读进内存**的 —— 这正是「缓存无限增长」的经典写法。
 */
object QqPlaylistStore {

    const val PREFS = "ncrust_qq_playlists"

    /** 详情条目上限。列表条目每个账号只有一条，不需要裁剪。 */
    const val MAX_DETAIL_ENTRIES = 30

    internal const val SEP = ":"

    // ------------------------------------------------------------ key 构造 ----

    /** `list:qqmusic:<ownerId>`。 */
    internal fun listKey(source: MusicSource, ownerId: String): String =
        "list$SEP${source.key}$SEP$ownerId"

    /** `list_at:qqmusic:<ownerId>`。 */
    internal fun listAtKey(source: MusicSource, ownerId: String): String =
        "list_at$SEP${source.key}$SEP$ownerId"

    /** `detail:qqmusic:<ownerId>:<playlistId>`。 */
    internal fun detailKey(source: MusicSource, ownerId: String, playlistId: String): String =
        "detail$SEP${source.key}$SEP$ownerId$SEP$playlistId"

    private const val PREFIX_DETAIL = "detail$SEP"
    private const val PREFIX_LIST = "list$SEP"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 列表 ----

    fun readList(context: Context, ownerId: String, now: Long): PlaylistCacheCodec.ListRead {
        val p = prefs(context)
        val raw = p.getString(listKey(MusicSource.QQMUSIC, ownerId), null)
        return PlaylistCacheCodec.decodeList(raw, ownerId, now)
    }

    fun writeList(
        context: Context,
        ownerId: String,
        playlists: List<Playlist>,
        savedAt: Long,
    ) {
        prefs(context).edit()
            .putString(listKey(MusicSource.QQMUSIC, ownerId), PlaylistCacheCodec.encodeList(ownerId, playlists, savedAt))
            .putLong(listAtKey(MusicSource.QQMUSIC, ownerId), savedAt)
            .putInt(KEY_SCHEMA, PlaylistCacheCodec.SCHEMA_VERSION)
            .apply()
    }

    fun listSavedAt(context: Context, ownerId: String): Long =
        prefs(context).getLong(listAtKey(MusicSource.QQMUSIC, ownerId), 0L)

    // ---------------------------------------------------------------- 详情 ----

    fun readDetail(
        context: Context,
        key: PlaylistKey,
        now: Long,
    ): PlaylistCacheCodec.DetailRead {
        val raw = prefs(context).getString(
            detailKey(key.source, key.ownerId, key.id), null,
        )
        return PlaylistCacheCodec.decodeDetail(raw, key.ownerId, now)
    }

    fun writeDetail(
        context: Context,
        key: PlaylistKey,
        songs: List<SongItem>,
        savedAt: Long,
        complete: Boolean,
        total: Int,
    ) {
        prefs(context).edit()
            .putString(
                detailKey(key.source, key.ownerId, key.id),
                PlaylistCacheCodec.encodeDetail(key.ownerId, songs, savedAt, complete, total),
            )
            .putLong(detailAtKey(key.source, key.ownerId, key.id), savedAt)
            .putInt(KEY_SCHEMA, PlaylistCacheCodec.SCHEMA_VERSION)
            .apply()
        pruneDetails(context)
    }

    internal fun detailAtKey(source: MusicSource, ownerId: String, playlistId: String): String =
        "detail_at$SEP${source.key}$SEP$ownerId$SEP$playlistId"

    // ---------------------------------------------------------------- 维护 ----

    /**
     * 裁剪详情条目到 [MAX_DETAIL_ENTRIES]。
     *
     * 用 `commit()` 而不是 `apply()`：裁剪发生在写路径上，读-改-写之间有竞争窗口，
     * 用同步提交把它关掉（这里的数据量很小，代价可接受）。
     */
    private fun pruneDetails(context: Context) {
        val p = prefs(context)
        val detailKeys = p.all.keys.filter { it.startsWith(PREFIX_DETAIL) }
        if (detailKeys.size <= MAX_DETAIL_ENTRIES) return
        val victims = detailKeys
            .map { k -> k to p.getLong("detail_at$SEP" + k.removePrefix(PREFIX_DETAIL), 0L) }
            .sortedBy { it.second }
            .take(detailKeys.size - MAX_DETAIL_ENTRIES)
        val e = p.edit()
        for ((k, _) in victims) {
            e.remove(k)
            e.remove("detail_at$SEP" + k.removePrefix(PREFIX_DETAIL))
        }
        e.commit()
    }

    /** 清空全部 QQ 歌单缓存（设置页「清除缓存」与登出时调用）。 */
    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    /**
     * 只清某个账号的缓存。登出时调用 —— **不要**顺手清掉另一个账号的，
     * 那会让「登出 ncm」意外把 QQ 的离线歌单也清掉（反之亦然）。
     */
    fun clearOwner(context: Context, ownerId: String) {
        val p = prefs(context)
        val suffix = SEP + ownerId
        val victims = p.all.keys.filter {
            (it.startsWith(PREFIX_LIST) || it.startsWith(PREFIX_DETAIL) ||
                it.startsWith("list_at$SEP") || it.startsWith("detail_at$SEP")) && it.endsWith(suffix)
        }
        if (victims.isEmpty()) return
        val e = p.edit()
        victims.forEach { e.remove(it) }
        e.commit()
    }

    /** 缓存里的条目数（诊断/测试用）。 */
    fun entryCount(context: Context): Int = prefs(context).all.size

    private const val KEY_SCHEMA = "schema_version"
}
