/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · B：qm JSON → SongItem 映射。**纯逻辑，JVM 可单测。**
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
 * qm 的搜索/详情响应 → 本应用统一的 [SongItem]（v2.1.0 · B）。
 *
 * 这一层是纯函数（只吃 [JSONObject]，不碰网络），因此可以用**真实响应片段**做单测 ——
 * 而这里恰恰是最需要单测的地方：QQ 的字段名与 ncm 完全不同（`singer`/`interval`/`mid`），
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

    /**
     * QQ 旧版搜索响应里的**总数**（v3.4.11）。
     *
     * 实测（2026-10-10，匿名）`client_search_cp?new_json=1&w=周杰伦&n=30&p=1`：
     * `data.song.totalnum = 999`、`data.song.curnum = 30`。
     * 注意**不在** `data.totalnum`（那一层没有这个键）—— 它在 `data.song` 里面，
     * 而 `singer` / `album` 两个子对象各有自己的 `totalnum`。写错层的表现是
     * 「总数恒为 null」（回落到卡片的字段名），不崩、也不报错。
     *
     * @param key 子对象名：`song` / `singer` / `album`。
     * @return 总数；取不到返回 null（**不是 0** —— 「不知道」与「没有」是两件事）。
     */
    fun totalCountOf(json: JSONObject, key: String = "song"): Int? {
        val data = json.optJSONObject("data") ?: return null
        val sub = data.optJSONObject(key) ?: return null
        // 有的响应把它放在子对象的 totalnum，有的放在 curnum 之外的同级。
        val total = sub.optInt("totalnum", -1)
        if (total >= 0) return total
        val legacy = data.optInt("totalnum", -1)
        return legacy.takeIf { it >= 0 }
    }

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

    /**
     * 单首歌的对象 → [SongItem]；缺 id 或 mid 时返回 null（这种条目没法取链）。
     *
     * ## J10：新旧字段**成组**兼容，不许逐字段零散兜底
     *
     * 同一个 QQ 曲目有两种形状：
     *
     * | 字段 | 新版（`new_json=1` / `musicu`） | 旧版（`client_search_cp` 原样） |
     * |---|---|---|
     * | 数字 id | `id` | `songid` |
     * | mid | `mid` | `songmid` |
     * | 标题 | `name` / `title` | `songname` |
     * | 专辑 | `album{ id, mid, name, pmid }` | `albumid` / `albummid` / `albumname` / `pmid`（**摊在 song 层**） |
     *
     * 旧实现只给 **mid** 做了兼容（`mid ?: songmid`），其余字段仍只读新名字 —— 于是
     * 同一条旧响应的解析结果是「身份对（mid）、标题空、专辑丢、数字 id 落散列兜底」，
     * 而它的新版形状解析出的是**另一条 `TrackKey`**（`SourceIds.qqId` 在没有 songid 时
     * 走 FNV 散列兜底，两个 id 必然不同）。身份判等只看 `(source, id)`，所以收藏 /
     * 队列 / 缓存里同一首歌变成两份。
     *
     * 修法是**成组**读：三组字段各自「新名优先、旧名兜底」，绝不出现「mid 认旧的、
     * id 只认新的」这种半截状态。字段缺失仍然如实为空（不猜）。
     */
    fun fromSongObject(item: JSONObject?): SongItem? {
        if (item == null) return null
        val mid = item.optString("mid").takeIf { it.isNotEmpty() }
            ?: item.optString("songmid").takeIf { it.isNotEmpty() }
            ?: return null
        // 旧版把数字 id 叫 `songid`；缺了就保持 0（散列兜底），与既有的埋点口径一致。
        val rawId = item.optLong("id", 0L).takeIf { it > 0L }
            ?: item.optLong("songid", 0L).takeIf { it > 0L }
            ?: 0L
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
                // 必须在这里带出去。旧代码只取数字 `id`，于是「转到歌手」把它当 ncm id 查，
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

        val album = albumOf(item)
        val trackName = item.optString("name").takeIf { it.isNotEmpty() }
            ?: item.optString("title").takeIf { it.isNotEmpty() }
            // J10：旧形状的标题叫 `songname`。少这一条 ⇒ 旧条目标是空标题，
            // 而空标题在下游是「不可解析」（`candidateToSong` 直接丢）。
            ?: item.optString("songname").takeIf { it.isNotEmpty() }
            ?: ""

        return SongItem(
            id = syntheticId,
            name = trackName,
            artists = artists,
            album = album,
            // `interval` 是**秒**（ncm 那边 `dt` 是毫秒）—— 不乘 1000 会让进度条与歌词全错。
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
     * 专辑字段的**成组**读取（J10）：新版嵌套 `album{…}` 与旧版 song 层扁平字段。
     *
     * ### v2.6.2 · P0：`album` 对象上的**三个候选分两个职责**，绝不能互相顶替
     *
     * · 封面 = `pmid`（**封面照片 id**，形如 `000MkMni19ClKG_5`）优先，回落 `mid`；
     * · **身份** = `mid`（albumMID，base62，形如 `000MkMni19ClKG`）—— 这是
     *   `music.musichallAlbum.AlbumSongList` 的 `albumMid` 参数唯一认的东西，
     *   也是「转到专辑」要跳过去的那张专辑的键。
     *
     * 旧代码把 `pmid ?: mid` 读进一个局部 val 只喂给封面，`AlbumItem` 没有字段装身份，
     * 于是跳转只能拿数字 `album.id` 去 ncm 查 —— 陈奕迅 `22276` → 陈小云（真机复现）。
     * 实测 QQ 服务端**碰巧**能容忍把 `pmid` 当 `albumMid` 传（内部会剥掉 `_N`），
     * 但那是服务端的宽容而不是契约，所以身份这一路只认 `mid`，
     * 且由 `AlbumNavigator` 的值域闸门（base62，`_` 不合法）把 `pmid` 挡在门外。
     *
     * ### J10：旧形状的专辑是**摊在 song 层**的
     *
     * `albumid` / `albummid` / `albumname`（以及可选的 `pmid`）。只兼容 `mid` 而不兼容
     * 它们，会让旧条目「专辑身份与封面一起丢」—— 而那正是「同一首歌两份收藏」的另一半。
     * 两者都没有时返回 null（如实留空，不造一个空专辑）。
     */
    private fun albumOf(item: JSONObject): AlbumItem? {
        val albumObj = item.optJSONObject("album")
        val rawId = albumObj?.optLong("id", 0L)?.takeIf { v -> v > 0L }
            ?: item.optLong("albumid", 0L).takeIf { v -> v > 0L }
        val albumMid = albumObj?.optString("mid")?.takeIf { it.isNotEmpty() }
            ?: item.optString("albummid").takeIf { it.isNotEmpty() }
        val name = albumObj?.optString("name")?.takeIf { it.isNotEmpty() }
            ?: item.optString("albumname").takeIf { it.isNotEmpty() }
        // 封面照片 id：`pmid` **只喂封面**，绝不落到 [AlbumItem.mid]（那是身份）。
        val photoId = albumObj?.optString("pmid")?.takeIf { it.isNotEmpty() }
            ?: albumObj?.optString("mid")?.takeIf { it.isNotEmpty() }
            ?: item.optString("pmid").takeIf { it.isNotEmpty() }
            ?: albumMid
        if (rawId == null && albumMid == null && name == null) return null
        return AlbumItem(
            id = rawId,
            name = name,
            picUrl = photoId?.let { m -> COVER_TEMPLATE.format(m) },
            mid = albumMid,
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
