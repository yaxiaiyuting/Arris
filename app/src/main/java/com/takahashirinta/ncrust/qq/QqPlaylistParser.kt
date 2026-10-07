/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：qm 歌单响应的**纯解析**（无 IO、无 Android 依赖、JVM 可单测）。
 */

package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.Playlist
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.PlaylistTrack
import org.json.JSONObject

/**
 * 一次 QQ 歌单请求的结果分类（v2.2.0）。
 *
 * **分类型而不是返回 `null`**：这四种情况在 UI 上的处置完全不同 ——
 * [NeedLogin] 要给「去登录」、[NotFound] 只能回退、[Network] 要给「重试」、
 * [Failure] 是客户端 bug（要能打日志定位）。用 null 表示全部，等于把这四种混成一种。
 */
sealed interface QqPlaylistResult<out T> {
    data class Ok<T>(val value: T) : QqPlaylistResult<T>

    /** 登录态已失效。判据见 [QqPlaylistParser.classify]。 */
    data object NeedLogin : QqPlaylistResult<Nothing>

    /** 歌单/目录不存在或不属于当前账号（实测 `10004`）。**重试无意义**。 */
    data object NotFound : QqPlaylistResult<Nothing>

    /** 服务端返回了其它非 0 业务码，或响应结构不是预期的。 */
    data class Failure(val code: Int) : QqPlaylistResult<Nothing>

    /** 网络层失败（`QqClient` 返回 null）。 */
    data object Network : QqPlaylistResult<Nothing>
}

/** 用户信息（v2.2.0）。只保留「歌单页要展示的」那几项。 */
data class QqUserBrief(
    val nick: String?,
    val avatarUrl: String?,
)

/**
 * 歌单详情的一页（v2.2.0）。
 *
 * @property tracks 本页曲目的**身份**（[PlaylistTrack.order] 是全局序号，已加上页偏移）。
 * @property songs 本页曲目的**可渲染/可播放形态**，与 [tracks] **一一对应、等长**。
 *   两者都从**同一个** `QqSongMapper.fromSongObject` 结果派生，不存两份数据。
 * @property total 服务端声明的总曲数。
 * @property hasMore 是否还有下一页（实测 `hasmore`）。
 * @property dirId 目录号（`dirinfo.dirid`）。「我喜欢」恒为 201。
 * @property tid 全局歌单 id（`dirinfo.id`）。
 * @property name 歌单名（`dirinfo.title`）。
 * @property coverUrl 封面（`dirinfo.picurl`）。
 * @property encryptUin 加密账号（`dirinfo.encrypt_uin`）—— 收藏歌单接口必须用它。
 */
data class QqPlaylistPage(
    val tracks: List<PlaylistTrack>,
    val songs: List<SongItem>,
    val total: Int,
    val hasMore: Boolean,
    val dirId: Long,
    val tid: Long,
    val name: String?,
    val coverUrl: String?,
    val encryptUin: String?,
)

/**
 * qm 歌单响应的解析（v2.2.0）。**纯函数**，夹具是四轮真机实测的真实响应形状。
 *
 * ## 字段命名（本文件最容易写错的地方）
 *
 * 实测同一份数据有**两套命名**，第 1 轮探针就因为照抄参考实现的别名而全部拿到 `None`：
 *
 * | 接口 | 风格 | 例 |
 * |---|---|---|
 * | `GetPlaylistByUin`（列表） | **camelCase** | `dirId` / `dirName` / `songNum` / `tid` |
 * | `CgiGetDiss` 的 `dirinfo`（详情） | **下划线** | `dirid` / `songnum` / `encrypt_uin` |
 * | `CgiGetDiss` 的 `songlist[]`（曲目） | **下划线** | `file.media_mid` / `interval` |
 *
 * 所以下面每个取值都**显式写死字段名**，并且对同一语义给出候选名（`dirid` / `dirId`），
 * 因为服务端历史上换过命名。
 */
object QqPlaylistParser {

    /** 「我喜欢」的固定目录号（实测 + 参考实现 `like_song(dirid=201)` 一致）。 */
    const val FAVORITE_DIR_ID = 201L

    /** 服务端业务码 → 结果分类。 */
    fun classify(code: Int): QqPlaylistResult<Unit> = when (code) {
        0 -> QqPlaylistResult.Ok(Unit)
        // 实测：无 cookie 调 GetLoginUserInfo / uin 非法时返回。
        CODE_NEED_LOGIN, CODE_BAD_UIN -> QqPlaylistResult.NeedLogin
        // 实测：dirid 指向不存在的目录，或 disstid 打不开（榜单就是这一类）。
        CODE_DIR_NOT_FOUND -> QqPlaylistResult.NotFound
        else -> QqPlaylistResult.Failure(code)
    }

    const val CODE_NEED_LOGIN = 1000
    const val CODE_DIR_NOT_FOUND = 10004
    const val CODE_BAD_UIN = 80030
    const val CODE_FAV_NEEDS_EUIN = 80050

    /** 响应信封：业务数据在 `req` 里（`QqClient.musicu` 已经把这一层剥掉了，这里防御性兼容）。 */
    private fun dataOf(response: JSONObject?): JSONObject? {
        val r = response ?: return null
        return r.optJSONObject("data") ?: r
    }

    private fun codeOf(response: JSONObject?): Int = response?.optInt("code", -1) ?: -1

    /**
     * 解析 `GetLoginUserInfo` → 昵称 / 头像。
     *
     * 实测结构：`data.info.{nick, logo}`（`data` 下还有 `identify`/`banner`/`pendantInfo` 等，
     * 与用户信息无关）。**`uin` 不在这里** —— 它来自 cookie（`QqCookie.uinOf`）。
     */
    fun parseUserInfo(response: JSONObject?): QqPlaylistResult<QqUserBrief> {
        val code = codeOf(response)
        classify(code).let { if (it !is QqPlaylistResult.Ok) return it.cast() }
        val data = dataOf(response) ?: return QqPlaylistResult.Failure(code)
        // 实测 data.info 才是资料块；兼容 data 本身。
        val info = data.optJSONObject("info") ?: data
        return QqPlaylistResult.Ok(
            QqUserBrief(
                nick = info.optString("nick").takeIf { it.isNotBlank() },
                avatarUrl = normalizeCover(info.optString("logo")),
            )
        )
    }

    /**
     * 解析 `GetPlaylistByUin` → 用户自己的歌单（含「我喜欢」）。
     *
     * @param ownerId 当前账号的 uin。**必须传**：它进 [PlaylistKey]，是「双账号天然隔离」的根据。
     */
    fun parseOwnedPlaylists(response: JSONObject?, ownerId: String): QqPlaylistResult<List<Playlist>> {
        val code = codeOf(response)
        classify(code).let { if (it !is QqPlaylistResult.Ok) return it.cast() }
        val arr = dataOf(response)?.optJSONArray("v_playlist")
            ?: return QqPlaylistResult.Ok(emptyList())
        val out = ArrayList<Playlist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(parsePlaylistObject(o, ownerId, owned = true) ?: continue)
        }
        return QqPlaylistResult.Ok(out)
    }

    /**
     * 解析 `CgiGetPlaylistFavInfo` → 收藏（他人）的歌单。
     *
     * 这些歌单**不是本人自建**（`isOwned = false`），且它们的 `dirid` 不是自己的目录号，
     * 详情要用 `disstid`(tid) 打开。
     */
    fun parseFavPlaylists(response: JSONObject?, ownerId: String): QqPlaylistResult<List<Playlist>> {
        val code = codeOf(response)
        if (code == CODE_FAV_NEEDS_EUIN) {
            // 传了裸 uin：这是**客户端 bug**，不是「没登录」，不要误导用户去重新登录。
            return QqPlaylistResult.Failure(code)
        }
        classify(code).let { if (it !is QqPlaylistResult.Ok) return it.cast() }
        val arr = dataOf(response)?.optJSONArray("v_list")
            ?: return QqPlaylistResult.Ok(emptyList())
        val out = ArrayList<Playlist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(parsePlaylistObject(o, ownerId, owned = false) ?: continue)
        }
        return QqPlaylistResult.Ok(out)
    }

    /**
     * 单个歌单对象 → [Playlist]。缺少可判定身份（既没有 `tid` 也没有 `dirId`）时返回 null。
     *
     * **`tid` 优先作为 id**：它全局唯一；`dirId` 只是账号内的目录号，两个账号的 `dirId=1`
     * 是两个不同的歌单，拿它当 id 会让缓存串号。`dirId` 作为**载荷**放进 [Playlist.dirId]。
     * 只有当服务端没给 `tid` 时才退而用 `dirId`（此时 id 前缀 `dir:` 以示区别，
     * 避免与真实的 tid 撞号）。
     */
    private fun parsePlaylistObject(o: JSONObject, ownerId: String, owned: Boolean): Playlist? {
        val tid = o.optLong("tid", 0L).takeIf { it > 0L }
            ?: o.optLong("dissid", 0L).takeIf { it > 0L }
        val dirId = o.optLong("dirId", 0L).takeIf { it > 0L }
            ?: o.optLong("dirid", 0L).takeIf { it > 0L }
            ?: 0L
        val id = when {
            tid != null -> tid.toString()
            dirId > 0L -> "dir:$dirId"
            else -> return null
        }
        val name = o.optString("dirName").takeIf { it.isNotBlank() }
            ?: o.optString("title").takeIf { it.isNotBlank() }
            ?: o.optString("name").takeIf { it.isNotBlank() }
            ?: ""
        // 封面：列表用 picUrl/bigpicUrl，收藏列表用 picurl。实测列表给的是 http://，
        // 统一升到 https（y.gtimg.cn 支持 https；明文请求在部分 ROM 上会被拦）。
        val cover = normalizeCover(
            o.optString("bigpicUrl").takeIf { it.isNotBlank() }
                ?: o.optString("picUrl").takeIf { it.isNotBlank() }
                ?: o.optString("picurl").takeIf { it.isNotBlank() }
        )
        val count = o.optInt("songNum", -1).takeIf { it >= 0 }
            ?: o.optInt("songnum", -1).takeIf { it >= 0 }
            ?: o.optInt("song_cnt", 0)
        val updated = o.optLong("updateTime", 0L).takeIf { it > 0L }
            ?: o.optLong("update_time", 0L).takeIf { it > 0L }
            ?: o.optLong("orderTime", 0L).takeIf { it > 0L }
            ?: 0L
        return Playlist(
            key = PlaylistKey(MusicSource.QQMUSIC, id, ownerId),
            name = name,
            coverUrl = cover,
            trackCount = count.coerceAtLeast(0),
            // 实测：`GetPlaylistByUin` 返回的就是「我的歌单」（含我喜欢）⇒ 自建；
            // 收藏列表返回的是他人的 ⇒ 非自建。这与 ncm 一侧要用 creator 判定不同，
            // 因为 QQ 的列表接口本身就把两者分开了。
            isOwned = owned,
            isFavorite = dirId == FAVORITE_DIR_ID,
            updatedAt = updated,
            dirId = dirId,
        )
    }

    /**
     * 解析 `CgiGetDiss` → 一页曲目 + 元数据。
     *
     * @param key 目标歌单的身份。它的 `id` 就是 `tid`。
     * @param pageOffset 本页第一首在歌单内的**全局序号**。实测 `song_begin` 是 offset，
     *   所以第 n 页的 `order` 必须从 `song_begin` 起算 —— 否则「第 2 页的 order 又从 0 开始」，
     *   合并多页时会互相覆盖。
     */
    fun parseDetailPage(
        response: JSONObject?,
        key: PlaylistKey,
        pageOffset: Int,
    ): QqPlaylistResult<QqPlaylistPage> {
        val code = codeOf(response)
        classify(code).let { if (it !is QqPlaylistResult.Ok) return it.cast() }
        val data = dataOf(response) ?: return QqPlaylistResult.Failure(code)
        val info = data.optJSONObject("dirinfo") ?: JSONObject()
        val arr = data.optJSONArray("songlist")

        val tracks = ArrayList<PlaylistTrack>(arr?.length() ?: 0)
        val songs = ArrayList<SongItem>(arr?.length() ?: 0)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // **必须复用 QqSongMapper**：songmid / file.media_mid 的映射只有这一处，
                // 另写一套就是「另起一套身份模型」（实测 10 首里 6 首 mid != media_mid）。
                val song = QqSongMapper.fromSongObject(o) ?: continue
                val t = PlaylistTrack.of(MusicSource.QQMUSIC, song, pageOffset + i) ?: continue
                tracks.add(t)
                songs.add(song)
            }
        }
        val total = data.optInt("total_song_num", -1).takeIf { it >= 0 }
            ?: info.optInt("songnum", 0)
        return QqPlaylistResult.Ok(
            QqPlaylistPage(
                tracks = tracks,
                songs = songs,
                total = total.coerceAtLeast(0),
                // 实测 hasmore: 0/1。用 `!= 0` 而不是 `== 1`，服务端给 2 也能正确判「还有」。
                hasMore = data.optInt("hasmore", 0) != 0,
                dirId = info.optLong("dirid", 0L).takeIf { it > 0L }
                    ?: info.optLong("dirId", 0L),
                tid = info.optLong("id", 0L).takeIf { it > 0L }
                    ?: key.id.toLongOrNull() ?: 0L,
                name = info.optString("title").takeIf { it.isNotBlank() },
                coverUrl = normalizeCover(info.optString("picurl")),
                encryptUin = info.optString("encrypt_uin").takeIf { it.isNotBlank() },
            )
        )
    }

    /**
     * 封面 URL 归一化：`http://` → `https://`，空串 → null。
     *
     * 实测 QQ 的**列表**接口给 `http://y.gtimg.cn/...`，**详情**接口给 `https://...`。
     * 明文 URL 在部分 ROM（Android 9+ 默认 `cleartextTrafficPermitted=false`）上会被拦，
     * 表现是「封面全白但其它正常」，很难查。这里统一升到 https（同一台 CDN，路径不变）。
     */
    fun normalizeCover(url: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        return if (u.startsWith("http://")) "https://" + u.removePrefix("http://") else u
    }

    /** `QqPlaylistResult<Unit>` → `QqPlaylistResult<T>`（错误分支的转发）。 */
    @Suppress("UNCHECKED_CAST")
    private fun <T> QqPlaylistResult<Unit>.cast(): QqPlaylistResult<T> = when (this) {
        is QqPlaylistResult.Ok -> QqPlaylistResult.Ok(Unit) as QqPlaylistResult<T>
        QqPlaylistResult.NeedLogin -> QqPlaylistResult.NeedLogin
        QqPlaylistResult.NotFound -> QqPlaylistResult.NotFound
        QqPlaylistResult.Network -> QqPlaylistResult.Network
        is QqPlaylistResult.Failure -> this
    }
}
