/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · A/B：qm 的**目录接口**（艺人 / 专辑 / 曲目列表）。
 * 纯逻辑部分（请求构造 + 响应映射），**无 Android 依赖，JVM 可单测**。
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
 * qm 的艺人（v2.4.0 · A）。
 *
 * @property id QQ 的**数字** `singerID`。它与 ncm 的艺人 id **互不相通**
 *   （实测周杰伦 `4558` vs `6452`，见 `probe-artist-mapping.md` P1），
 *   本应用只用它做诊断与排序，**不做跨源键**。
 * @property mid `singerMID`（base62 字符串）—— **这才是本应用打开 QQ 艺人页的键**。
 * @property albumCount 该源的「专辑数」标签（`albumNum`），只用于候选排序。
 */
data class QqArtist(
    val id: Long,
    val mid: String,
    val name: String,
    val albumCount: Int = 0,
    val songCount: Int = 0,
)

/**
 * qm 的专辑（v2.4.0 · A）。
 *
 * @property trackCount 曲目数。**只有旧版 `fcg_v8_singer_album.fcg` 的
 *   `latest_song.song_count` 给得到它**；`musicu` 的 `GetAlbumList` 恒为 0
 *   （见 `probe-artist-api.md`）—— 所以艺人页必须走旧版通道，否则会退化成 N+1 次请求。
 */
data class QqAlbum(
    val id: Long,
    val mid: String,
    val name: String,
    val trackCount: Int? = null,
    val publishDate: String? = null,
    val company: String? = null,
    val albumType: String? = null,
    val singerName: String? = null,
)

/**
 * qm 的一张专辑 + 它的曲目（v2.4.0 · B）。
 */
data class QqAlbumDetail(
    val mid: String,
    val name: String? = null,
    val publishDate: String? = null,
    val trackCount: Int? = null,
    val songs: List<SongItem> = emptyList(),
)

/**
 * `musicu.fcg` 的 module / method 常量（v2.4.0 · A/B）。
 *
 * **这些取值全部来自 2026-09 的实测**（`probe-catalog-api.py`），不是文档推断。
 * 其中两条是「试错试出来的」，写在这里防止下一个人再走一遍：
 *
 * 1. `music.musichallAlbum.AlbumInfoServer/GetAlbumInfo` 实测 **`code=40000`**（不可用），
 *    专辑曲目只能走 `AlbumSongList/GetAlbumSongList`；
 * 2. `music.web_singer_info_svr/get_singer_album` 实测返回**空列表**（`total=0`），
 *    艺人专辑列表只能走旧版 CGI。
 */
object QqCatalogRequests {

    const val MODULE_SINGER_INFO = "music.web_singer_info_svr"
    const val METHOD_SINGER_DETAIL = "get_singer_detail_info"

    const val MODULE_ALBUM_SONG_LIST = "music.musichallAlbum.AlbumSongList"
    const val METHOD_ALBUM_SONG_LIST = "GetAlbumSongList"

    const val MODULE_ALBUM_LIST = "music.musichallAlbum.AlbumListServer"
    const val METHOD_ALBUM_LIST = "GetAlbumList"

    private const val LEGACY_HOST = "https://c.y.qq.com"

    /** 艺人搜索（`t=9` 是歌手）。旧版 GET 比 `musicu` 的搜索通道稳定（v2.1.0 已实测）。 */
    fun legacySingerSearchUrl(keyword: String, limit: Int, page: Int): String =
        LEGACY_HOST + "/soso/fcgi-bin/client_search_cp" +
            "?p=" + page.coerceAtLeast(1) +
            "&n=" + limit.coerceIn(1, 60) +
            "&w=" + java.net.URLEncoder.encode(keyword, "UTF-8") +
            "&format=json&t=9&new_json=1"

    /** 专辑搜索（`t=8`）。曲目数在 `song_count` 里。 */
    fun legacyAlbumSearchUrl(keyword: String, limit: Int, page: Int): String =
        LEGACY_HOST + "/soso/fcgi-bin/client_search_cp" +
            "?p=" + page.coerceAtLeast(1) +
            "&n=" + limit.coerceIn(1, 60) +
            "&w=" + java.net.URLEncoder.encode(keyword, "UTF-8") +
            "&format=json&t=8&new_json=1"

    /**
     * 艺人的专辑列表。
     *
     * `order=1` 实测是「按时间倒序」；`num` 上限实测 100 有效（周杰伦 43 张一次拿全）。
     */
    fun legacySingerAlbumUrl(singerMid: String, num: Int, begin: Int, order: Int = 1): String =
        LEGACY_HOST + "/v8/fcg-bin/fcg_v8_singer_album.fcg" +
            "?singermid=" + java.net.URLEncoder.encode(singerMid, "UTF-8") +
            "&num=" + num.coerceIn(1, 100) +
            "&begin=" + begin.coerceAtLeast(0) +
            "&order=" + order +
            "&format=json&platform=yqq"

    /** 艺人的曲目列表（艺人页的「热门单曲」tab）。 */
    fun singerDetailEnvelope(singerMid: String): JSONObject = JSONObject()
        .put("module", MODULE_SINGER_INFO)
        .put("method", METHOD_SINGER_DETAIL)
        .put("param", JSONObject().put("singermid", singerMid))

    /** 专辑曲目列表。`num` 实测 200 一次拿全（叶惠美 11 首）。 */
    fun albumSongListEnvelope(albumMid: String, num: Int = 200, begin: Int = 0): JSONObject = JSONObject()
        .put("module", MODULE_ALBUM_SONG_LIST)
        .put("method", METHOD_ALBUM_SONG_LIST)
        .put(
            "param",
            JSONObject()
                .put("albumMid", albumMid)
                .put("begin", begin.coerceAtLeast(0))
                .put("num", num.coerceIn(1, 200)),
        )

    /**
     * **批量可播放性预检**的请求体（v2.4.0 · D）。
     *
     * ## 为什么是自己拼而不是复用 `QqRequests.vkey`
     *
     * 已有的那个构造器是「**一首歌 × N 个档位**」（为了一次往返拿完降级阶梯）。
     * 预检要的是反过来的形状：**N 首歌 × 1 个档位**。
     * 两个方向混在一个函数里会让 `filename` / `songmid` / `songtype` 三个数组
     * 的等长约束变得不可读，而那个约束一旦破坏，服务端**不报错**、
     * 只是把结果错位返回（第 i 个 purl 属于第 j 首歌）—— 那会直接导致
     * 「给错单曲」级别的错误，必须让形状一眼可验。
     *
     * @param entries `(songmid, mediaMid)` 列表。**`mediaMid` 必须是 `file.media_mid`**，
     *   传 `songmid` 会拿到一个「格式正确但指向不存在文件」的 purl（v2.1.0 实测）。
     * @return 请求体；`entries` 为空时返回 null（调用方据此**不发请求**）。
     */
    fun availabilityProbeEnvelope(entries: List<Pair<String, String>>): JSONObject? {
        if (entries.isEmpty()) return null
        val filenames = JSONArray()
        val songmids = JSONArray()
        val songtypes = JSONArray()
        for ((songMid, mediaMid) in entries) {
            filenames.put(QqQuality.fileNameFor(QqFileType.M500, mediaMid))
            songmids.put(songMid)
            songtypes.put(0)
        }
        return JSONObject()
            .put("module", "vkey.GetVkeyServer")
            .put("method", "CgiGetVkey")
            .put(
                "param",
                JSONObject()
                    .put("guid", "10000")
                    .put("songmid", songmids)
                    .put("songtype", songtypes)
                    .put("uin", "0")
                    .put("loginflag", 1)
                    .put("platform", "20")
                    .put("filename", filenames),
            )
    }
}

/**
 * QQ 目录响应 → 本应用的模型（v2.4.0 · A/B）。**纯函数，用真实响应片段做单测。**
 *
 * 与 [QqSongMapper] 的关系：曲目本体**复用同一个 `fromSongObject`**。
 * 实测专辑曲目、艺人曲目、搜索结果三处的 `songInfo` 字段完全同构
 * （`probe-album-api.md`），所以这里不写第二套映射 —— 少一套映射就少一处会漂移的地方。
 */
object QqCatalogMapper {

    /** 歌手搜索响应：`data.singer.list[]`。 */
    fun artistsFromSearch(json: JSONObject?): List<QqArtist> {
        val list = json?.optJSONObject("data")
            ?.optJSONObject("singer")
            ?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<QqArtist>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val mid = o.optString("singerMID").takeIf { it.isNotEmpty() } ?: continue
            val name = o.optString("singerName").takeIf { it.isNotEmpty() } ?: continue
            out += QqArtist(
                id = o.optLong("singerID", 0L),
                mid = mid,
                name = name,
                albumCount = o.optInt("albumNum", 0),
                songCount = o.optInt("songNum", 0),
            )
        }
        return out
    }

    /** 艺人专辑列表：`data.list[]`（旧版 CGI）。 */
    fun albumsFromSingerAlbum(json: JSONObject?): List<QqAlbum> {
        val list = json?.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<QqAlbum>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val mid = o.optString("albumMID").takeIf { it.isNotEmpty() } ?: continue
            val name = o.optString("albumName").takeIf { it.isNotEmpty() } ?: continue
            out += QqAlbum(
                id = o.optString("albumID").toLongOrNull() ?: 0L,
                mid = mid,
                name = name,
                trackCount = o.optJSONObject("latest_song")?.optInt("song_count", 0)
                    ?.takeIf { it > 0 } ?: o.optInt("song_count", 0).takeIf { it > 0 },
                publishDate = o.optString("pubTime").takeIf { it.isNotEmpty() },
                company = o.optString("company").takeIf { it.isNotEmpty() },
                albumType = o.optString("albumtype").takeIf { it.isNotEmpty() },
                singerName = o.optString("singerName").takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    /** 专辑搜索：`data.album.list[]`。曲目数在 `song_count`。 */
    fun albumsFromSearch(json: JSONObject?): List<QqAlbum> {
        val list = json?.optJSONObject("data")
            ?.optJSONObject("album")
            ?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<QqAlbum>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val mid = o.optString("albumMID").takeIf { it.isNotEmpty() } ?: continue
            val name = o.optString("albumName").takeIf { it.isNotEmpty() } ?: continue
            out += QqAlbum(
                id = o.optLong("albumID", 0L),
                mid = mid,
                name = name,
                trackCount = o.optInt("song_count", 0).takeIf { it > 0 },
                publishDate = o.optString("publicTime").takeIf { it.isNotEmpty() },
                singerName = o.optString("singerName").takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    /**
     * 艺人曲目列表。
     *
     * ★ **信封已经剥过一层**：`QqClient.musicu` 返回的是响应里的 `req` 对象
     * （见 `QqClient.execute` 的 `json.optJSONObject("req") ?: json.optJSONObject(module)`），
     * 所以服务端的 `req.data.songlist` 到这里是 `data.songlist`。
     * 写成 `payload.optJSONArray("songlist")` 会在真机上表现为「一条都读不出来」——
     * 这个错误在单测里能立刻抓到，所以 `QqCatalogMapperTest` 用**真实响应片段**钉住了它。
     */
    fun songsFromSingerDetail(payload: JSONObject?): List<SongItem> {
        val arr = payload?.optJSONObject("data")?.optJSONArray("songlist") ?: return emptyList()
        return mapSongs(arr)
    }

    /** 专辑曲目：`data.songList[].songInfo`。信封同样已剥一层。 */
    fun albumDetailFromSongList(payload: JSONObject?, fallbackMid: String): QqAlbumDetail {
        val data = payload?.optJSONObject("data") ?: return QqAlbumDetail(fallbackMid)
        val arr = data.optJSONArray("songList") ?: return QqAlbumDetail(fallbackMid)
        val infos = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            val info = arr.optJSONObject(i)?.optJSONObject("songInfo") ?: continue
            infos.add(info)
        }
        val first = infos.firstOrNull()
        val albumObj = first?.optJSONObject("album")
        return QqAlbumDetail(
            mid = albumObj?.optString("mid")?.takeIf { it.isNotEmpty() } ?: fallbackMid,
            name = albumObj?.optString("name")?.takeIf { it.isNotEmpty() },
            publishDate = albumObj?.optString("time_public")?.takeIf { it.isNotEmpty() },
            trackCount = data.optInt("totalNum", 0).takeIf { it > 0 } ?: infos.size,
            songs = mapSongs(JSONArray(infos)),
        )
    }

    /** 曲目对象数组 → [SongItem]，**复用 `QqSongMapper` 的唯一映射**。 */
    private fun mapSongs(arr: JSONArray): List<SongItem> {
        val out = ArrayList<SongItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            QqSongMapper.fromSongObject(o)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 批量预检响应 → `songmid → (purl 是否非空, result)`。
     *
     * 用 **songmid** 而不是数组下标做 key：服务端返回的 `midurlinfo` 顺序**没有任何保证**
     * （实测按请求顺序返回，但那是观察不是契约）。用下标关联一旦顺序变了，
     * 就会把 A 歌的可播放状态标到 B 歌上 —— 又是一个「给错单曲」。
     */
    fun availabilityFromProbe(response: JSONObject?): Map<String, QqProbeEntry> {
        val data = response?.optJSONObject("data") ?: return emptyMap()
        val list = data.optJSONArray("midurlinfo") ?: return emptyMap()
        val out = HashMap<String, QqProbeEntry>(list.length())
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            val mid = e.optString("songmid").takeIf { it.isNotEmpty() } ?: continue
            out[mid] = QqProbeEntry(
                playable = e.optString("purl").isNotEmpty(),
                result = e.optInt("result", -1),
            )
        }
        return out
    }

    /** ncm 侧把 `privilege.pl` 直接当可播判据；QQ 侧对应的就是 `purl` 非空。 */
    fun artistsOf(item: JSONObject): List<ArtistItem> {
        val arr = item.optJSONArray("singer") ?: return emptyList()
        val out = ArrayList<ArtistItem>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val name = a.optString("name").takeIf { it.isNotEmpty() } ?: continue
            // v2.6.1 · P0：与 [QqSongMapper] 同一条纪律 —— `singer.mid` 必须带出去，
            // 否则这条路上的曲目一样会把 QQ 的数字 singerID 当 ncm id 用。
            // 两条映射路径的行为必须逐值一致（有单测钉住）。
            out += ArtistItem(
                id = a.optLong("id", 0L).takeIf { it > 0L },
                name = name,
                mid = a.optString("mid").takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    /**
     * 与 [QqSongMapper] 共用的封面模板（这里保留一份是为了纯函数的可测性）。
     *
     * v2.6.2 · P0：与 [QqSongMapper.fromSongObject] **逐值一致** —— 封面取
     * `pmid ?: mid`，**身份只取 `mid`**。两条链路各自解析同一个 `album` 对象，
     * 分叉的表现是「搜索进来的歌跳得对、歌单/歌手页进来的歌跳错」，
     * 只在特定入口复现（`QqAlbumMidMappingTest` 钉住了这一条）。
     */
    fun albumItemOf(item: JSONObject): AlbumItem? {
        val album = item.optJSONObject("album") ?: return null
        val photoId = album.optString("pmid").takeIf { it.isNotEmpty() }
            ?: album.optString("mid").takeIf { it.isNotEmpty() }
        return AlbumItem(
            id = album.optLong("id", 0L).takeIf { it > 0L },
            name = album.optString("name").takeIf { it.isNotEmpty() },
            picUrl = photoId?.let { "https://y.qq.com/music/photo_new/T002R500x500M000$it.jpg" },
            mid = album.optString("mid").takeIf { it.isNotEmpty() },
        )
    }

    /** 合成 id 的辅助（与 `QqSongMapper` 同一套规则，供需要「先算 id 再查」的地方用）。 */
    fun syntheticId(item: JSONObject): Long? {
        val mid = item.optString("mid").takeIf { it.isNotEmpty() }
            ?: item.optString("songmid").takeIf { it.isNotEmpty() } ?: return null
        return SourceIds.qqId(item.optLong("id", 0L), mid)
    }

    /** 该曲目的音源常量（避免调用方到处写字符串）。 */
    const val SOURCE_KEY: String = "qqmusic"

    init {
        // 编译期自证：常量与枚举不脱钩（改了枚举而忘了这里会编译错或立即失败）。
        check(SOURCE_KEY == MusicSource.QQMUSIC.key) { "SOURCE_KEY 与 MusicSource.QQMUSIC 不一致" }
    }
}

/**
 * 一次预检的结果（v2.4.0 · D）。
 *
 * @property playable `purl` 非空 ⇒ 服务端**真的愿意给这个文件**。这是唯一诚实的「能播」判据。
 * @property result QQ 的业务码。`104003` = `QqApi.RESULT_NEED_LOGIN_OR_VIP`。
 */
data class QqProbeEntry(
    val playable: Boolean,
    val result: Int,
)
