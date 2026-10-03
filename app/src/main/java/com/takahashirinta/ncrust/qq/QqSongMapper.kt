/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · B：QQ 音乐 JSON → SongItem 映射。**纯逻辑，JVM 可单测。**
 */

package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import org.json.JSONArray
import org.json.JSONObject

/**
 * QQ 音乐的搜索/详情响应 → 本应用统一的 [SongItem]（v2.1.0 · B）。
 *
 * 这一层是纯函数（只吃 [JSONObject]，不碰网络），因此可以用**真实响应片段**做单测 ——
 * 而这里恰恰是最需要单测的地方：QQ 的字段名与网易云完全不同（`singer`/`interval`/`mid`），
 * 映射错一个字段的表现是「歌能搜到、点进去没封面/时长为 0」，只在真机上肉眼可见。
 *
 * ## 三个 id 的关系（这是本文件最容易被写错的地方）
 *
 * | 字段 | 用途 | 来源 |
 * |---|---|---|
 * | `songid`（数字） | 本应用内部的 [SongItem.id]（**带 QQ 标志位**） | `id` |
 * | `songmid`（字符串） | 取链接口的 `songmid` 参数、歌词请求、身份标识 | `mid` |
 * | `media_mid` | **拼 URL 文件名用的那个 mid** | `file.media_mid` |
 *
 * `mid` 与 `media_mid` 实测经常不同（《晴天》`mid=0039MnYb0qxYhV` / `media_mid=003Qui1q2u1Zho`），
 * 而文件名按 media_mid 拼。两个都留着（[SongItem.sourceId] / [SongItem.mediaId]），
 * 取链时优先后者、失败再试前者（见 `QqApi.fetchPlayUrl`）。
 */
object QqSongMapper {

    /** QQ 封面 URL 的固定形状（与官方 web 播放器一致）。 */
    private const val COVER_TEMPLATE = "https://y.qq.com/music/photo_new/T002R500x500M000%s.jpg"

    /**
     * 从搜索响应里取出歌曲列表。
     *
     * 兼容两种响应形状：新版自适应结果是 `req.data.body.item_song.items`，
     * 旧版是 `req.data.body.song.list`。两个都试 —— 服务端按客户端身份切换形状是常态，
     * 只认一种会在某次服务端灰度后整片搜不出歌（表现是「搜索没反应」）。
     */
    fun songsFromSearchResponse(json: JSONObject?): List<SongItem> {
        val body = json?.optJSONObject("req")?.optJSONObject("data")?.optJSONObject("body") ?: return emptyList()
        val arrays = listOfNotNull(
            body.optJSONObject("item_song")?.optJSONArray("items"),
            body.optJSONObject("song")?.optJSONArray("list"),
        )
        for (arr in arrays) {
            val out = mapArray(arr)
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /**
     * 旧版 `client_search_cp` 的响应：`data.song.list[]`。
     *
     * 加 `new_json=1` 之后条目字段与新版**完全一致**（实测），所以这里直接复用
     * [fromSongObject]，不需要第二套映射 —— 少一套映射就少一处会漂移的地方。
     */
    fun songsFromLegacySearch(json: JSONObject?): List<SongItem> {
        val list = json?.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list") ?: return emptyList()
        return mapArray(list)
    }

    /**
     * v3.3.0 · 需求 2：**歌词搜索**（`t=7`）的旧版响应。
     *
     * 与 [songsFromLegacySearch] 的唯一差别是条目装在 **`data.lyric.list`** 而不是
     * `data.song.list`。实测（2026-10，`t=7&w=让我掉下眼泪的`）：
     * ```json
     * {"code":0,"data":{"lyric":{"list":[{"mid":"…","id":…,"title":"成都","singer":[…],"album":{…}}]}}}
     * ```
     * 而 `t=0` 返回的是 `data.song.list`。
     *
     * 条目的**字段形状与单曲搜索逐字相同**，所以复用 [mapArray] 而不是抄一份 ——
     * 抄一份的下场是某天单曲那条修了缺陷、歌词这条没跟上（仓库里已经有过同类教训）。
     */
    fun songsFromLegacyLyricSearch(json: JSONObject?): List<SongItem> {
        val list = json?.optJSONObject("data")
            ?.optJSONObject("lyric")
            ?.optJSONArray("list") ?: return emptyList()
        return mapArray(list)
    }

    private fun mapArray(arr: JSONArray): List<SongItem> {
        val out = ArrayList<SongItem>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            fromSongObject(item)?.let { out.add(it) }
        }
        return out
    }

    /** 单首歌的对象 → [SongItem]；缺 id 或 mid 时返回 null（这种条目没法取链）。 */
    fun fromSongObject(item: JSONObject?): SongItem? {
        if (item == null) return null
        val mid = item.optString("mid").takeIf { it.isNotEmpty() }
            ?: item.optString("songmid").takeIf { it.isNotEmpty() }
            ?: return null
        val rawId = item.optLong("id", 0L)
        val syntheticId = SourceIds.qqId(rawId, mid)
        // v2.5.4 · C：解析期埋点（唯一落点）。
        // 「服务端没给 songid」在这里是**唯一可判**的地方：`optLong("id", 0L)` 把
        // 「字段缺失」与「字段是 0」压成了同一个值，两者都走散列兜底。
        // 一次 AtomicLong 自增 + 一次毫秒时间戳，无分配、无 IO、无网络。
        QqProbeCounters.onParsed(
            hadSongId = rawId > 0L && rawId < SourceIds.QQ_ID_FLAG,
            // media_mid 缺失时 QqApi 会回落 songmid 取链；这里只记事实，不改行为。
            hadMediaMid = item.optJSONObject("file")
                ?.optString("media_mid")?.isNotEmpty() == true,
        )

        val artists = item.optJSONArray("singer")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = a.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                // v2.6.1 · P0：`singer[].mid`（singerMID）是**唯一**能打开 QQ 艺人页的身份，
                // 必须在这里带出去。旧代码只取数字 `id`，于是「转到歌手」把它当网易云 id 查，
                // 周杰伦(4558) 跳到马洪波 —— 真机复现过的 P0。
                // 实测 QQ 搜索响应里 `singer[]` 条目的字段是
                // `{id, mid, name, pmid, title, title_highlight, type, uin}`（2026-09，匿名可复现）；
                // v2.4.0 注释里「QQ 一侧拿不到 singerMID」是对**专辑接口**的观察，不适用于搜索。
                ArtistItem(
                    id = a.optLong("id", 0L).takeIf { it > 0L },
                    name = name,
                    mid = a.optString("mid").takeIf { it.isNotEmpty() },
                )
            }
        }

        val albumObj = item.optJSONObject("album")
        // v2.6.2 · P0：`album` 对象上的**三个候选分两个职责**，绝不能互相顶替：
        //
        //   · 封面 = `pmid`（**封面照片 id**，形如 `000MkMni19ClKG_5`）优先，回落 `mid`；
        //   · **身份** = `mid`（albumMID，base62，形如 `000MkMni19ClKG`）—— 这是
        //     `music.musichallAlbum.AlbumSongList` 的 `albumMid` 参数唯一认的东西，
        //     也是「转到专辑」要跳过去的那张专辑的键。
        //
        // 旧代码把 `pmid ?: mid` 读进一个局部 val 只喂给封面，`AlbumItem` 没有字段装身份，
        // 于是跳转只能拿数字 `album.id` 去网易云查 —— 陈奕迅 `22276` → 陈小云（真机复现）。
        // 实测 QQ 服务端**碰巧**能容忍把 `pmid` 当 `albumMid` 传（内部会剥掉 `_N`），
        // 但那是服务端的宽容而不是契约，所以身份这一路只认 `mid`，
        // 且由 `AlbumNavigator` 的值域闸门（base62，`_` 不合法）把 `pmid` 挡在门外。
        val albumPhotoId = albumObj?.optString("pmid")?.takeIf { it.isNotEmpty() }
            ?: albumObj?.optString("mid")?.takeIf { it.isNotEmpty() }
        val albumMid = albumObj?.optString("mid")?.takeIf { it.isNotEmpty() }
        val album = albumObj?.let {
            AlbumItem(
                id = it.optLong("id", 0L).takeIf { v -> v > 0L },
                name = it.optString("name").takeIf { v -> v.isNotEmpty() },
                picUrl = albumPhotoId?.let { m -> COVER_TEMPLATE.format(m) },
                mid = albumMid,
            )
        }

        return SongItem(
            id = syntheticId,
            name = item.optString("name").takeIf { it.isNotEmpty() }
                ?: item.optString("title").takeIf { it.isNotEmpty() }
                ?: "",
            artists = artists,
            album = album,
            // `interval` 是**秒**（网易云那边 `dt` 是毫秒）—— 不乘 1000 会让进度条与歌词全错。
            duration = item.optLong("interval", 0L).takeIf { it > 0L }?.times(1000L),
            source = MusicSource.QQMUSIC.key,
            sourceId = mid,
            mediaId = mediaMidOf(item, mid),
            // v2.1.4：会员专享判定。`pay.pay_play == 1` 表示「播放需要付费」，
            // 官方搜索响应里 VIP 专享曲就是它。与 [isPaywalled] 共用同一个出口，
            // 避免同一个字段在两处各写一遍（那正是会漂移的地方）。
            memberOnly = isPaywalled(item),
        )
    }

    /**
     * 取 `media_mid`（拼文件名用）；没有就回落 `mid`。
     *
     * 单拎出来是因为它是**唯一一处「两个 mid 二选一」**的判定，
     * 取链与单测都要用同一条规则。
     */
    fun mediaMidOf(item: JSONObject, fallbackMid: String): String {
        val fromFile = item.optJSONObject("file")?.optString("media_mid")?.takeIf { it.isNotEmpty() }
        return fromFile ?: fallbackMid
    }

    /**
     * 该曲是否**需要付费/会员才能完整播放**。只用于 UI 提示，**不用来阻止播放** ——
     * 真正的判据是服务端返回的 purl（见 `QqApi.fetchPlayUrl`）。
     * `pay.pay_play == 1` 表示「播放需要付费」。
     */
    fun isPaywalled(item: JSONObject?): Boolean =
        item?.optJSONObject("pay")?.optInt("pay_play", 0) == 1

    /** 该曲是否有 30 秒试听片段（无权限时服务端会给 `file.size_try`）。 */
    fun hasTrialOnly(item: JSONObject?): Boolean {
        val file = item?.optJSONObject("file") ?: return false
        val trySize = file.optLong("size_try", 0L)
        val fullSize = file.optLong("size_128mp3", 0L)
        return trySize > 0L && fullSize <= 0L
    }
}
