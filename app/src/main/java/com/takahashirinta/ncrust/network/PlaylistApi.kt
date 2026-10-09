/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug2「我喜欢的歌无法全部加载」）：
 *   - ③ getLikedTrackIds 增加 trackCount 完整性校验：trackIds 被服务端截断时，
 *        改用 /eapi/v3/playlist/track/all 按 limit/offset 循环补齐，批间 delay(250ms) 限流，
 *        并以「页码上限 / 空页 / 整页重复」三重条件保证正常终止。 */

package com.takahashirinta.ncrust.network

import android.util.Log
import androidx.compose.runtime.Immutable
import com.takahashirinta.ncrust.network.crypto.EapiCrypto
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object PlaylistApi {
    private const val USER_PLAYLIST_PATH = "/eapi/user/playlist"
    private const val PLAYLIST_DETAIL_PATH = "/eapi/v6/playlist/detail"
    private const val ACCOUNT_GET_PATH = "/eapi/w/nuser/account/get"

    // Bug2-③：红心歌单 trackIds 被截断时的补齐参数。
    private const val PLAYLIST_TRACK_ALL_PATH = "/eapi/v3/playlist/track/all"
    private const val LIKED_FILL_PAGE_SIZE = 500
    private const val LIKED_FILL_DELAY_MS = 250L
    // 兜底上限：500 × 40 = 20000 首，足以覆盖任何真实收藏量，同时杜绝意外的无限循环。
    private const val MAX_LIKED_FILL_PAGES = 40

    /**
     * 获取当前登录用户的 UID
     */
    suspend fun getCurrentUserId(): Long = withContext(Dispatchers.IO) {
        val payload = emptyMap<String, String>()
        val response = RetrofitClient.eapiPost(ACCOUNT_GET_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val account = json.optJSONObject("account")
            ?: json.optJSONObject("profile")
            ?: throw Exception("no account data, body=$body")
        val userId = account.optLong("id", 0)
        if (userId == 0L) throw Exception("UID not found in: $body")
        userId
    }

    @Immutable
    data class UserProfile(
        val userId: Long,
        val nickname: String,
        val avatarUrl: String
    )

    suspend fun getUserProfile(): UserProfile = withContext(Dispatchers.IO) {
        val payload = emptyMap<String, String>()
        val response = RetrofitClient.eapiPost(ACCOUNT_GET_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)

        val account = json.optJSONObject("account")
        val profile = json.optJSONObject("profile")

        UserProfile(
            userId = profile?.optLong("userId", account?.optLong("id", 0) ?: 0)
                ?: account?.optLong("id", 0) ?: 0,
            nickname = profile?.optString("nickname", "")?.ifEmpty { "用户" }
                ?: account?.optString("userName", "")?.ifEmpty { "用户" }
                ?: "用户",
            avatarUrl = profile?.optString("avatarUrl", "")
                ?: account?.optString("avatarUrl", "") ?: ""
        )
    }
    suspend fun getUserPlaylists(uid: Long, limit: Int = 100, offset: Int = 0): UserPlaylistResult = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "uid" to uid.toString(),
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "includeVideo" to "false"
        )
        val response = RetrofitClient.eapiPost(USER_PLAYLIST_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        if (code != 200) throw Exception("API error: code=$code")

        val playlists = mutableListOf<PlaylistInfo>()
        val playlistArray = json.optJSONArray("playlist") ?: JSONArray()
        for (i in 0 until playlistArray.length()) {
            val item = playlistArray.getJSONObject(i)
            // 红心歌单（「我喜欢的音乐」等 specialType!=0 的特殊歌单）不作为普通歌单收藏展示，跳过。
            if (item.optInt("specialType") != 0) continue
            playlists.add(
                PlaylistInfo(
                    id = item.optLong("id"),
                    name = item.optString("name"),
                    coverImgUrl = item.optString("coverImgUrl"),
                    trackCount = item.optInt("trackCount"),
                    creatorUserId = item.optJSONObject("creator")?.optLong("userId") ?: 0,
                    specialType = item.optInt("specialType"),
                    privacy = item.optInt("privacy"),
                    subscribed = item.optBoolean("subscribed", false),
                    description = item.optString("description")
                )
            )
        }

        UserPlaylistResult(
            playlists = playlists,
            total = json.optInt("total", 0),
            more = json.optBoolean("more", false)
        )
    }

    /**
     * v1.3.0 · B4：取歌单元信息（不看曲目），供「是否本人自建 → 是否显示编辑/删除入口」判定。
     * 曲目仍走 [getPlaylistDetail]；这里用 n=0 的轻量请求，避免为了一个菜单拉 1000 首详情。
     */
    suspend fun fetchPlaylistInfo(playlistId: Long): PlaylistInfo? = withContext(Dispatchers.IO) {
        val payload = mapOf("id" to playlistId.toString(), "n" to "0", "s" to "0")
        val response = RetrofitClient.eapiPost(PLAYLIST_DETAIL_PATH, payload)
        val body = response.body?.string() ?: return@withContext null
        val playlist = JSONObject(body).optJSONObject("playlist") ?: return@withContext null
        PlaylistInfo(
            id = playlist.optLong("id", playlistId),
            name = playlist.optString("name"),
            coverImgUrl = playlist.optString("coverImgUrl"),
            trackCount = playlist.optInt("trackCount"),
            creatorUserId = playlist.optJSONObject("creator")?.optLong("userId") ?: 0,
            specialType = playlist.optInt("specialType"),
            privacy = playlist.optInt("privacy"),
            subscribed = playlist.optBoolean("subscribed", false),
            description = playlist.optString("description")
        )
    }

    suspend fun getArtistDetail(artistId: Long): String = withContext(Dispatchers.IO) {
        val payload = mapOf("id" to artistId.toString())
        val response = RetrofitClient.eapiPost("/eapi/v1/artist/detail", payload)
        response.body?.string() ?: throw Exception("empty response")
    }

    /**
     * 目标艺人的热门曲 id（v1.4.0 · 音乐人推荐卡片的锚点推导用）。
     * 实测 `/eapi/v1/artist/songs` 对任意艺人都返回 200（周杰伦/小众艺人都可），
     * 而 `/eapi/v1/artist/detail` 与 `/eapi/artist/albums` 已 400 失效（别用）。
     */
    suspend fun getArtistTopSongIds(artistId: Long, limit: Int = 3): List<Long> = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "id" to artistId.toString(),
            "limit" to limit.toString(),
            "offset" to "0",
            "order" to "hot"
        )
        val response = RetrofitClient.eapiPost("/eapi/v1/artist/songs", payload)
        val root = JSONObject(response.body?.string() ?: return@withContext emptyList())
        val arr = root.optJSONArray("songs") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optLong("id")?.takeIf { it > 0L }
        }
    }

    suspend fun getArtistAlbums(artistId: Long): String = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "id" to artistId.toString(),
            "limit" to "50",
            "offset" to "0"
        )
        val response = RetrofitClient.eapiPost("/eapi/artist/albums", payload)
        response.body?.string() ?: throw Exception("empty response")
    }

    suspend fun getPlaylistDetail(playlistId: Long): List<SongItem> = withContext(Dispatchers.IO) {
        // n=1000 requests more full-detail tracks; server still caps at ~20 in `tracks`,
        // but always returns the complete list in `trackIds`.
        val payload = mapOf(
            "id" to playlistId.toString(),
            "n" to "1000",
            "s" to "0"
        )
        val response = RetrofitClient.eapiPost(PLAYLIST_DETAIL_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        if (code != 200) throw Exception("API error: code=$code")

        val playlistObj = json.optJSONObject("playlist") ?: return@withContext emptyList()

        // Parse the partial track objects that carry full detail (typically first ~20).
        val tracksMap = mutableMapOf<Long, SongItem>()
        val trackArray = playlistObj.optJSONArray("tracks")
        if (trackArray != null) {
            for (i in 0 until trackArray.length()) {
                val song = parseSongTrack(trackArray.getJSONObject(i))
                tracksMap[song.id] = song
            }
        }

        // Collect every ID in playlist order from the always-complete `trackIds` array.
        val allIds = mutableListOf<Long>()
        val trackIdsArray = playlistObj.optJSONArray("trackIds")
        if (trackIdsArray != null) {
            for (i in 0 until trackIdsArray.length()) {
                allIds.add(trackIdsArray.getJSONObject(i).optLong("id"))
            }
        }

        // If trackIds is absent (e.g. very short playlists already fully in tracks), use tracks order.
        if (allIds.isEmpty()) return@withContext tracksMap.values.toList()

        // Batch-fetch details for IDs not covered by the partial `tracks` array.
        val missingIds = allIds.filter { it !in tracksMap }
        val batchSize = 500
        for (start in missingIds.indices step batchSize) {
            val batch = missingIds.subList(start, minOf(start + batchSize, missingIds.size))
            val fetched = fetchSongDetails(batch)
            if (fetched.isEmpty() && batch.isNotEmpty()) {
                Log.w("PlaylistApi", "batch of ${batch.size} songs returned empty from server")
            }
            fetched.forEach { tracksMap[it.id] = it }
        }

        // Return songs in the original playlist order defined by trackIds.
        allIds.mapNotNull { tracksMap[it] }
    }

    private fun parseSongTrack(track: JSONObject): SongItem {
        val artistArray = track.optJSONArray("ar")
        val artists: List<ArtistItem>? = artistArray?.let {
            (0 until it.length()).map { j ->
                val a = it.getJSONObject(j)
                // id 带出来供"转到歌手"回调直接跳转, 免二次 detail 查询
                ArtistItem(id = a.optLong("id").takeIf { v -> v != 0L }, name = a.optString("name"))
            }
        }
        val albumJson = track.optJSONObject("al")
        val album: AlbumItem? = albumJson?.let {
            AlbumItem(id = it.optLong("id"), name = it.optString("name"), picUrl = it.optString("picUrl"))
        }
        return SongItem(
            id = track.optLong("id"),
            name = track.optString("name"),
            artists = artists,
            album = album,
            duration = track.optLong("dt")
        )
    }

    private suspend fun fetchSongDetails(ids: List<Long>): List<SongItem> = withContext(Dispatchers.IO) {        val cArray = JSONArray()
        ids.forEach { id -> cArray.put(JSONObject().put("id", id)) }
        val payload = mapOf("c" to cArray.toString())
        // Single retry with 500ms delay to survive transient network blips on large playlists.
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val response = RetrofitClient.eapiPost("/eapi/v3/song/detail", payload)
                val body = response.body?.string() ?: return@repeat
                val songArray = JSONObject(body).optJSONArray("songs") ?: return@repeat
                return@withContext (0 until songArray.length()).map { i ->
                    parseSongTrack(songArray.getJSONObject(i))
                }
            } catch (e: Exception) {
                lastError = e
                Log.w("PlaylistApi", "fetchSongDetails attempt ${attempt + 1} failed: ${e.message}")
                if (attempt == 0) delay(500)
            }
        }
        lastError?.let { Log.e("PlaylistApi", "fetchSongDetails gave up after retry", it) }
        emptyList()
    }

    @Immutable
    data class PlaylistInfo(
        val id: Long,
        val name: String,
        val coverImgUrl: String,
        val trackCount: Int,
        val creatorUserId: Long,
        val specialType: Int,
        val privacy: Int,
        /** 是否「我收藏的」（他人歌单）。判归属见 [isOwnedBy]。 */
        val subscribed: Boolean = false,
        val description: String = ""
    ) {
        /**
         * 是否本人自建（可编辑/删除）。
         *
         * 实测（2026-09）：收藏的歌单里 userId / creator.userId 都是**原作者**，
         * 所以不能用 userId 判归属 —— 必须 creator.userId == 我 **且** 未订阅。
         */
        fun isOwnedBy(uid: Long): Boolean = !subscribed && creatorUserId == uid
    }

    data class UserPlaylistResult(
        val playlists: List<PlaylistInfo>,
        val total: Int,
        val more: Boolean
    )

    @Immutable
    data class PlaylistCard(
        val id: Long,
        val name: String,
        val coverUrl: String,
        val playCount: Long = 0,
        val trackCount: Int = 0
    )

    // ==================== Discovery ====================

    suspend fun getDailyRecommendSongs(): List<SongItem> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost("/eapi/v2/discovery/recommend/songs", emptyMap())
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val arr = json.optJSONArray("recommend") ?: json.optJSONArray("data")
            ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SongItem(
                id = s.optLong("id"),
                name = s.optString("name"),
                artists = s.optJSONArray("artists")?.let { ar ->
                    (0 until ar.length()).map { j ->
                        ArtistItem(name = ar.getJSONObject(j).optString("name"))
                    }
                },
                album = s.optJSONObject("album")?.let {
                    AlbumItem(id = it.optLong("id"), name = it.optString("name"), picUrl = it.optString("picUrl"))
                },
                duration = s.optLong("duration").takeIf { it != 0L }
            )
        }
    }

    suspend fun getRecommendPlaylists(): List<PlaylistCard> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost("/eapi/v1/discovery/recommend/resource", emptyMap())
        val body = response.body?.string() ?: throw Exception("empty response")
        val arr = JSONObject(body).optJSONArray("recommend") ?: return@withContext emptyList()
        (0 until minOf(arr.length(), 10)).map { i ->
            val item = arr.getJSONObject(i)
            PlaylistCard(
                id = item.optLong("id"),
                name = item.optString("name"),
                coverUrl = item.optString("picUrl"),
                playCount = item.optLong("playCount"),
                trackCount = if (item.optString("name") == "私人雷达") 35 else item.optInt("trackCount")
            )
        }
    }

    /**
     * 榜单列表（v1.2.0 · E）。/eapi/toplist 匿名即可读，实测返回 63 个榜单。
     * 榜单本质就是歌单，因此复用 PlaylistCard 承载、点进去走 getPlaylistDetail(id)，
     * 不需要任何新解析代码。默认只取前 [limit] 个（首页/搜索页只展示精选入口）。
     */
    suspend fun getToplists(limit: Int = 12): List<PlaylistCard> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost("/eapi/toplist", emptyMap())
        val body = response.body?.string() ?: throw Exception("empty response")
        val arr = JSONObject(body).optJSONArray("list") ?: return@withContext emptyList()
        (0 until minOf(arr.length(), limit)).map { i ->
            val item = arr.getJSONObject(i)
            PlaylistCard(
                id = item.optLong("id"),
                name = item.optString("name"),
                coverUrl = item.optString("coverImgUrl"),
                playCount = item.optLong("playCount"),
                trackCount = item.optInt("trackCount"),
            )
        }
    }

    suspend fun getTopSongs(limit: Int = 30, offset: Int = 0): List<SongItem> = withContext(Dispatchers.IO) {
        val body = RetrofitClient.get("/api/v1/discovery/new/songs?limit=$limit&offset=$offset")
        val json = JSONObject(body)
        val arr = json.optJSONArray("data") ?: json.optJSONArray("songs")
            ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SongItem(
                id = s.optLong("id"),
                name = s.optString("name"),
                artists = s.optJSONArray("artists")?.let { ar ->
                    (0 until ar.length()).map { j ->
                        ArtistItem(name = ar.getJSONObject(j).optString("name"))
                    }
                },
                album = s.optJSONObject("album")?.let {
                    AlbumItem(id = it.optLong("id"), name = it.optString("name"), picUrl = it.optString("picUrl"))
                },
                duration = s.optLong("duration").takeIf { it != 0L }
            )
        }
    }

    /**
     * 私人 FM 电台歌曲（客户端端点 /eapi/v1/radio/get, 返回 `data[]` 每项
     * 是 `{ "song": {...} }` 嵌套, 与 recommend 的老平铺结构不同）。
     *
     * 这是真正意义上的 FM: 由网易按收听历史/偏好生成, 不是"相似歌曲"或
     * "每日推荐"——Infinity 无限播放应该用这个作为主数据源。
     */
    suspend fun getPersonalFm(): List<SongItem> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost("/eapi/v1/radio/get", emptyMap())
        val body = response.body?.string() ?: throw Exception("empty response")
        val arr = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            // data[i].song 是完整歌曲对象（含 id/name/ar/al/dt）; 某些响应直接把歌曲摊在 data[i] 顶层
            val s = arr.getJSONObject(i).optJSONObject("song") ?: arr.getJSONObject(i)
            SongItem(
                id = s.optLong("id"),
                name = s.optString("name"),
                artists = (s.optJSONArray("ar") ?: s.optJSONArray("artists"))?.let { ar ->
                    (0 until ar.length()).map { j ->
                        ArtistItem(
                            id = ar.getJSONObject(j).optLong("id").takeIf { it != 0L },
                            name = ar.getJSONObject(j).optString("name")
                        )
                    }
                },
                album = (s.optJSONObject("al") ?: s.optJSONObject("album"))?.let {
                    AlbumItem(
                        id = it.optLong("id").takeIf { v -> v != 0L },
                        name = it.optString("name"),
                        picUrl = it.optString("picUrl")
                    )
                },
                duration = (s.optLong("dt").takeIf { it != 0L }
                    ?: s.optLong("duration").takeIf { it != 0L })
            )
        }
    }

    // FM垃圾桶：对当前 FM 歌曲执行不喜欢操作
    suspend fun fmTrash(songId: Long): Boolean = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost(
            "/eapi/radio/trash/add",
            mapOf("songId" to songId.toString(), "alg" to "itembased", "time" to "25")
        )
        val body = response.body?.string() ?: return@withContext false
        JSONObject(body).optInt("code", -1) == 200
    }

    /**
     * 相似歌曲（Infinity 无限播放的数据源）。
     *
     * 主端点用客户端 eapi /eapi/v1/discovery/similarSong；该端点若失效/返回空
     * （实测会退化成每日推荐），回退官方 weapi /api/discovery/simiSong。
     * 返回歌曲是老格式(artists/album/duration), 解析时新旧字段都兜底;
     * artists[].id 带出来, 供后续"转到歌手/专辑"回调直接使用。
     */
    /**
     * 相似歌曲。
     *
     * ## v3.4.11：默认条数 20 → **50**
     *
     * 这条链路的消费者只有一个：`MainActivity.launchInfinity()` ——
     * 「相似无限」模式下每次从**这一批**里挑下一首。
     * 所以 20 就是**候选池的大小**，池子小 → 很快绕回听过的歌 →
     * 用户描述的「随机感不好」（他推测是「总是落在附近三十首」，方向对、机制在池子）。
     *
     * 50 而不是更大：接口的 `limit` 越大单次响应越慢，而这条链路是**播放中**触发的
     * （唱到最后一首时才补货），不能让用户等。50 是「一次网络往返能拿到的、
     * 又明显大于一屏队列」的量级；真要更大的池子应该做**多页轮转**（未做，见发布说明）。
     */
    suspend fun getSimilarSongs(songId: Long, limit: Int = 50): List<SongItem> = withContext(Dispatchers.IO) {
        val payloadJson = JSONObject(
            mapOf("songid" to songId.toString(), "limit" to limit.toString(), "offset" to "0")
        ).toString()

        val eapi = runCatching {
            val response = RetrofitClient.eapiPost(
                "/eapi/v1/discovery/similarSong",
                mapOf("songid" to songId.toString(), "limit" to limit.toString(), "offset" to "0")
            )
            response.body?.string()?.let { parseSimilarSongs(it) } ?: emptyList()
        }.getOrDefault(emptyList())
        if (eapi.isNotEmpty()) return@withContext eapi

        runCatching {
            val response = RetrofitClient.weapiPost("/api/discovery/simiSong", payloadJson)
            response.body?.string()?.let { parseSimilarSongs(it) } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun parseSimilarSongs(body: String): List<SongItem> {
        val arr = JSONObject(body).optJSONArray("songs") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SongItem(
                id = s.optLong("id"),
                name = s.optString("name"),
                artists = (s.optJSONArray("artists") ?: s.optJSONArray("ar"))?.let { ar ->
                    (0 until ar.length()).map { j ->
                        val a = ar.getJSONObject(j)
                        ArtistItem(id = a.optLong("id").takeIf { it != 0L }, name = a.optString("name"))
                    }
                },
                album = (s.optJSONObject("album") ?: s.optJSONObject("al"))?.let {
                    AlbumItem(id = it.optLong("id").takeIf { v -> v != 0L }, name = it.optString("name"), picUrl = it.optString("picUrl"))
                },
                duration = (s.optLong("duration").takeIf { it != 0L } ?: s.optLong("dt")).takeIf { it != 0L }
            )
        }
    }

    // ==================== 原生扫码登录（平板 / 大屏） ====================

    data class LoginQrKey(
        val unikey: String,
        val qrurl: String,
        val qrimg: String?,
        // 本次登录会话的 web 设备 id: chainId 与轮询 Cookie 必须一致, 官方 App
        // 才能把"确认"绑到我们这条轮询会话上。
        val sDeviceId: String
    )

    /**
     * 申请二维码登录 key。
     *
     * 必须走**网页 weapi** 通道, 且按新版协议带上 `noCheckToken` 与二维码里的
     * `chainId`: 官方 App 扫码后会把确认绑到 chainId 对应的会话上, 缺了 chainId
     * 就会一直停在"已扫码待确认", 永远等不到 803。
     */
    suspend fun getLoginQrKey(): LoginQrKey? = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("type", 1).put("noCheckToken", true).toString()
        val response = RetrofitClient.weapiPost("/api/login/qrcode/unikey", payload)
        val body = response.body?.string() ?: return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        if (json.optInt("code", -1) != 200) return@withContext null
        val unikey = json.optString("unikey")
        if (unikey.isEmpty()) return@withContext null
        val sDeviceId = randomSDeviceId()
        val chainId = "v1_${sDeviceId}_web_login_${System.currentTimeMillis()}"
        LoginQrKey(
            unikey = unikey,
            qrurl = "https://music.163.com/login?codekey=$unikey&chainId=$chainId",
            qrimg = json.optString("qrimg", "").takeIf { it.isNotEmpty() },
            sDeviceId = sDeviceId
        )
    }

    data class LoginQrStatus(val code: Int, val cookie: String?)

    /**
     * 轮询二维码状态。code: 800=过期, 801=待扫码, 802=已扫码待确认, 803=成功。
     * 成功时 cookie 在响应的 Set-Cookie 头里, 拼出完整 cookie 串交上层走
     * CookieManager/RetrofitClient 的既有保存路径。
     *
     * 轮询需带上与 chainId 一致的 sDeviceId cookie(以及反风控的 os/NMTID),
     * 否则服务端不认这条会话。
     */
    suspend fun checkLoginQr(key: String, sDeviceId: String): LoginQrStatus = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("type", 1)
            .put("noCheckToken", true)
            .put("key", key)
            .toString()
        val extraCookies = "os=pc; NMTID=${randomHex(16)}; sDeviceId=$sDeviceId"
        val response = RetrofitClient.weapiPost("/api/login/qrcode/client/login", payload, extraCookies)
        val body = response.body?.string() ?: return@withContext LoginQrStatus(-1, null)
        val code = runCatching { JSONObject(body).optInt("code", -1) }.getOrDefault(-1)
        if (code == 803) {
            val setCookies = response.headers("Set-Cookie")
            Log.d(
                "PlaylistApi",
                "qr803 setCookie=${setCookies.size} hasMusicU=${setCookies.any { it.contains("MUSIC_U=") }} " +
                    "body=${body.take(160)}"
            )
        }
        LoginQrStatus(code, if (code == 803) extractSessionCookie(response) else null)
    }

    private const val HEX_CHARS = "0123456789ABCDEF"

    /** 52 位十六进制设备 id, 与 go-musicfox 的 sDeviceId 格式一致。 */
    private fun randomSDeviceId(): String =
        buildString(52) { repeat(52) { append(HEX_CHARS[kotlin.random.Random.nextInt(HEX_CHARS.length)]) } }

    private fun randomHex(len: Int): String =
        buildString(len) { repeat(len) { append(HEX_CHARS[kotlin.random.Random.nextInt(16)]) } }

    /** 从 Set-Cookie 头拼出会话 cookie 串(仅当含 MUSIC_U 时有效)。 */
    private fun extractSessionCookie(response: okhttp3.Response): String? {
        val cookies = response.headers("Set-Cookie")
            .mapNotNull { it.substringBefore(";").takeIf { p -> p.contains("=") } }
        return cookies.joinToString("; ").takeIf { it.contains("MUSIC_U=") }
    }

    // ==================== 云端收藏（收藏单曲 / 收藏专辑） ====================

    /**
     * 找到「我喜欢的音乐」（红心歌单）的 playlist id。
     *
     * 该歌单在 `/eapi/user/playlist` 中作为**特殊歌单**出现
     * （名称「我喜欢的音乐」/ `specialType == 5`，普通自建歌单 `specialType` 为 0），
     * 正是收藏页单曲 tab 的数据源。依次按**名称 → `specialType == 5` → 任意特殊歌单**
     * 三级兜底，与官方 weapi 的 likelist 等价，但走已证明可用的 playlist-detail 路径。
     *
     * ## v3.4.5 · P0（移植自上游 `de193ff`）：为什么必须精确命中
     *
     * 旧实现取「**第一个** `specialType != 0`」的歌单。这个谓词**不唯一** ——
     * 账号里只要还有别的特殊歌单且排在红心歌单之前，读端拿到的就是**另一个歌单**的
     * trackIds，与 like 的**写入目标**（服务端固定的红心歌单）不是同一个。
     *
     * 后果比「计数不对」更脏：本仓库的收藏库是**只加不减**的
     * （`SavedSongSync.merge`，云端只负责追加），所以错歌单的 id 会被当作
     * 「云端有、本地没有」而**追加进本地收藏** —— 把别处的歌**导入**「我的收藏」。
     * 上游把这条的症状记为「收藏后隔天消失」，本仓库因为只加不减不会消失，
     * 但读错源这件事是一样的。
     *
     * 挑选规则已抽成纯函数 [pickLikedPlaylistIdFrom]（JVM 可单测）；
     * 这里只负责取数与解 JSON。
     */
    suspend fun getLikedPlaylistId(uid: Long): Long? = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "uid" to uid.toString(),
            "limit" to "200",
            "offset" to "0",
            "includeVideo" to "false"
        )
        val response = RetrofitClient.eapiPost(USER_PLAYLIST_PATH, payload)
        val body = response.body?.string() ?: return@withContext null
        val json = JSONObject(body)
        pickLikedPlaylistIdFrom(json.optJSONArray("playlist"))
    }

    /**
     * 读取「我喜欢的音乐」单曲 ID 的**显式结果**（v3.4.5 · 移植自上游 `6b5b470`）。
     *
     * ## 为什么不能再用 `List<Long>` 表示失败
     *
     * 旧签名把**三种完全不同的语义**压进同一个返回值 `emptyList()`：
     *
     * 1. 服务端**权威确认**「收藏为空」—— 这是真的空；
     * 2. HTTP 200 但**业务码非 200**（风控 `-460` / 未登录 / 参数错）；
     * 3. **结构异常**（空 body / 缺 `playlist` / 缺 `trackIds`）。
     *
     * 调用方（`LibraryManager.refreshFromCloud`）无法区分它们，于是 2、3 会被当成 1
     * 覆盖掉 `cachedLikedIds` 并**落盘** —— 一次抖动就让收藏页表头显示「0 首」、
     * 分页底表变空（`getLikedSongIds` / `loadAllLikedSongs` 都拿不到 id），
     * 且**重启后仍然是空**，直到下一次成功刷新。
     *
     * 收藏单曲**本体**不会因此丢失：本仓库的收藏库是只加不减的
     * （`SavedSongSync.merge`，云端只负责追加）。丢的是 `liked_ids` 这份底表。
     *
     * 所以：`Success(emptyList())` = 服务端确认收藏为空；`Failure` = 「不知道」，
     * 调用方**必须保留现有缓存**。
     */
    sealed interface LikedIdsResult {
        data class Success(val ids: List<Long>) : LikedIdsResult
        data class Failure(val reason: String) : LikedIdsResult
    }

    /**
     * 获取「我喜欢的音乐」全部单曲 ID（红心歌单，有序、去重）。
     *
     * trackIds 通常一次就完整，但服务端对大歌单可能截断。这里以响应中的 `trackCount`
     * 为准做完整性校验，缺多少就按 limit/offset 循环补齐（Bug2-③），
     * 避免「我喜欢的歌」只拿到前一部分却无人察觉。
     *
     * 终止性由三重条件保证，不会死循环：①页数上限 MAX_LIKED_FILL_PAGES；
     * ②某页返回空；③整页都是重复 id（说明服务端不再前进）。
     *
     * v3.4.5：**失败不再返回空列表**，而是 [LikedIdsResult.Failure]，由调用方决定
     * 是否保留本地数据（见 [LikedIdsResult] 的 KDoc）。
     *
     * 补齐循环的**部分失败不算 Failure**：head 已经是服务端权威给的一段，
     * 取不满只是「少了一点」，按 Success 返回并打日志；把它降级成 Failure 会让
     * 一张本来完好的底表完全不更新。这条语义与 v3.4.5 之前一致，没有改动。
     */
    suspend fun getLikedTrackIds(uid: Long): LikedIdsResult = withContext(Dispatchers.IO) {
        val playlistId = try {
            getLikedPlaylistId(uid)
        } catch (e: Exception) {
            return@withContext LikedIdsResult.Failure("getLikedPlaylistId threw: ${e.message}")
        } ?: return@withContext LikedIdsResult.Failure("liked playlist not found")

        val payload = mapOf(
            "id" to playlistId.toString(),
            "n" to "1000",
            "s" to "0"
        )
        val head: List<Long>
        val declaredCount: Int
        try {
            val response = RetrofitClient.eapiPost(PLAYLIST_DETAIL_PATH, payload)
            val body = response.body?.string()
                ?: return@withContext LikedIdsResult.Failure("empty body")
            val json = JSONObject(body)
            // 业务码必须显式校验：v3.4.5 之前这里从不看 code，
            // 于是 -460 / 未登录都长成「收藏为空」。
            val code = json.optInt("code", -1)
            if (code != 200) return@withContext LikedIdsResult.Failure("business code=$code")
            val playlistObj = json.optJSONObject("playlist")
                ?: return@withContext LikedIdsResult.Failure("missing playlist object")
            val trackIds = playlistObj.optJSONArray("trackIds")
                ?: return@withContext LikedIdsResult.Failure("missing trackIds")
            head = (0 until trackIds.length()).map { trackIds.getJSONObject(it).optLong("id") }
            declaredCount = playlistObj.optInt("trackCount", head.size)
        } catch (e: Exception) {
            return@withContext LikedIdsResult.Failure("request threw: ${e.message}")
        }

        if (head.size >= declaredCount) return@withContext LikedIdsResult.Success(head.distinct())

        // LinkedHashSet：保序 + 去重，重复 id 不会让结果变长。
        val all = LinkedHashSet<Long>(maxOf(head.size, declaredCount).coerceAtLeast(16))
        all.addAll(head)
        var offset = head.size
        var pages = 0
        while (all.size < declaredCount && pages < MAX_LIKED_FILL_PAGES) {
            pages++
            val page = runCatching { fetchTrackIdsPage(playlistId, offset, LIKED_FILL_PAGE_SIZE) }
                .getOrDefault(emptyList())
            if (page.isEmpty()) break
            val added = page.count { all.add(it) }
            offset += page.size
            if (added == 0) break
            if (all.size < declaredCount) delay(LIKED_FILL_DELAY_MS)
        }
        Log.i(
            "PlaylistApi",
            "getLikedTrackIds: declared=" + declaredCount + " head=" + head.size +
                " final=" + all.size + " pages=" + pages
        )
        LikedIdsResult.Success(all.toList())
    }

    /** 分页读取红心歌单第 offset 起的 limit 个单曲 id（Bug2-③ 补齐用）。 */
    private suspend fun fetchTrackIdsPage(playlistId: Long, offset: Int, limit: Int): List<Long> {
        val response = RetrofitClient.eapiPost(
            PLAYLIST_TRACK_ALL_PATH,
            mapOf(
                "id" to playlistId.toString(),
                "limit" to limit.toString(),
                "offset" to offset.toString()
            )
        )
        val body = response.body?.string() ?: return emptyList()
        val songs = JSONObject(body).optJSONArray("songs") ?: return emptyList()
        return (0 until songs.length()).map { songs.getJSONObject(it).optLong("id") }
    }

    /** 按 ID 批量拉取单曲详情（eapi/v3/song/detail），供收藏单曲分页 lazy 加载用。 */
    suspend fun getSongsByIds(ids: List<Long>): List<SongItem> = fetchSongDetails(ids)

    /**
     * 收藏(`like=true`) / 取消收藏(`false`) 单曲 —— 写入「我喜欢的音乐」。
     *
     * ## v3.4.5 · P0（移植自上游 `de193ff`）：`time` 不是时间戳，通道也要换
     *
     * 主通道改为与官方网页/参考实现完全一致的 weapi `/api/radio/like`：
     * `alg=itembased & trackId=<id> & like=<bool> & time=3`。
     *
     * **`time` 的语义是「试听秒数」，官方固定为 `3`**。旧实现把它当成 Unix 秒级
     * 时间戳发送（`System.currentTimeMillis() / 1000` ≈ 1.7e9），上游判断服务端风控
     * 会先接受、随后在例行审核里把这条 like 判为异常并**回滚** —— 那是「收藏后隔天
     * 消失」的服务端诱因。
     *
     * 本仓库因为收藏库是**只加不减**的（`SavedSongSync.merge` 规则 3），
     * 服务端回滚**不会**让歌从本地列表里消失；但这条 like 会**没有真正落到云端**：
     * 换机 / 重装 / 清数据后丢失，官方客户端与网页看不到，也不喂 ncm 的推荐。
     * 所以读端「列表还在」不代表写端没问题。
     *
     * weapi 被账号级风控（`-460「检测到您的网络环境存在风险」`）拦截时回退到
     * eapi 客户端协议（`/eapi/radio/like` + 客户端身份头 + 真随机 deviceId +
     * 加密响应 AES 解密）；回退路径同样使用 `time=3`。
     *
     * ## 关于风控的那段历史观测（保留，避免后人误读）
     *
     * like 写操作历史上曾受网易账号/IP 级风险控制，本账号四种协议变体（eapi 最小 /
     * eapi+PC 指纹 / eapi+安卓身份 / 经典 weapi）均被 `-460` 或异常响应拦截，
     * 而**读取全部正常**。该注释是**既往观测，不代表当前版本/当前账号的现状** ——
     * 以实际响应为准。成功与否只由 `code == 200` 判定，**绝不把非 200 响应当成功**。
     *
     * 额外记录脱敏的 HTTP 状态、业务码与耗时，便于诊断；**不记录** Cookie、
     * CSRF token 或完整加密请求体。
     */
    suspend fun likeSong(songId: Long, like: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (tryWeapiLike(songId, like) == 200) return@withContext true
        tryEapiLike(songId, like) == 200
    }

    /** weapi 主通道。返回业务码；请求失败返回 -1（调用方据此回退 eapi）。 */
    private fun tryWeapiLike(songId: Long, like: Boolean): Int {
        return try {
            val payload = JSONObject()
                .put("alg", "itembased")
                .put("trackId", songId)
                .put("like", like)
                .put("time", "3")
                .toString()
            val response = RetrofitClient.weapiPost("/api/radio/like", payload)
            val body = response.body?.string() ?: return -1
            JSONObject(body).optInt("code", -1).also {
                Log.i("PlaylistApi", "likeSong(weapi) id=$songId like=$like code=$it")
            }
        } catch (e: Throwable) {
            Log.w("PlaylistApi", "likeSong(weapi) req failed id=$songId like=$like", e)
            -1
        }
    }

    /** eapi 回退通道（官方安卓客户端协议）。返回业务码；失败返回 -1。 */
    private fun tryEapiLike(songId: Long, like: Boolean): Int {
        val payload = mapOf(
            "alg" to "itembased",
            "trackId" to songId.toString(),
            "like" to like.toString(),
            // 试听秒数，官方固定 3 —— 不是时间戳（见 likeSong 的 KDoc）。
            "time" to "3",
            "e_r" to "TRUE",
            "csrf_token" to (RetrofitClient.getCsrfToken().orEmpty())
        )
        val startedAt = System.currentTimeMillis()
        val http = try {
            RetrofitClient.eapiPostOfficial("/eapi/radio/like", payload)
        } catch (e: Throwable) {
            Log.w("PlaylistApi", "likeSong(eapi) req failed id=$songId like=$like", e)
            return -1
        }
        val elapsed = System.currentTimeMillis() - startedAt
        val raw = try { http.body?.bytes() } catch (_: Throwable) { null }
        if (raw == null) {
            Log.w("PlaylistApi", "likeSong(eapi) id=$songId like=$like http=${http.code} no body elapsedMs=$elapsed")
            return -1
        }
        val plain = EapiCrypto.decryptResponse(java.util.Base64.getEncoder().encodeToString(raw))
        val jsonText = plain.ifEmpty { String(raw) }
        var code = -1
        var parseOk = false
        try {
            code = JSONObject(jsonText).optInt("code", -1)
            parseOk = true
        } catch (_: Throwable) { /* parseOk stays false */ }
        Log.i(
            "PlaylistApi",
            "likeSong(eapi/client) id=$songId like=$like http=${http.code} " +
                "code=$code parsed=$parseOk elapsedMs=$elapsed"
        )
        return code
    }

    /** 收藏的专辑（云端的「我收藏的专辑」，weapi）。 */
    @Immutable
    data class CloudAlbum(
        val albumId: Long,
        val name: String,
        val artist: String,
        val picUrl: String,
        val songCount: Int
    )

    suspend fun getSubscribedAlbums(limit: Int = 100, offset: Int = 0): List<CloudAlbum> = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "total" to "true"
        )
        // 客户端(接口域)用的是 eapi 端点 /eapi/album/sublist；weapi 那条网页端读取为空，
        // 故改用 eapi 读「我收藏的专辑」。eapi 返回 data 直接是专辑数组(非 weapi 的 data.albums)。
        val response = RetrofitClient.eapiPost("/eapi/album/sublist", payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val arr: JSONArray = when {
            json.optJSONArray("data") != null -> json.optJSONArray("data")
            json.optJSONObject("data") != null -> json.optJSONObject("data").optJSONArray("albums")
            else -> return@withContext emptyList()
        }
        (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            CloudAlbum(
                albumId = a.optLong("id"),
                name = a.optString("name"),
                artist = a.optJSONArray("artists")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)?.optString("name")
                    ?: a.optJSONObject("artist")?.optString("name") ?: "",
                picUrl = a.optString("picUrl"),
                songCount = a.optInt("size")
            )
        }
    }

    /** 收藏(sub=true) / 取消收藏(false) 专辑（eapi，与客户端一致）。 */
    suspend fun subAlbum(albumId: Long, sub: Boolean): Boolean = withContext(Dispatchers.IO) {
        val action = if (sub) "sub" else "unsub"
        val payload = mapOf("id" to albumId.toString())
        val response = RetrofitClient.eapiPost("/eapi/album/$action", payload)
        val body = response.body?.string() ?: return@withContext false
        JSONObject(body).optInt("code", -1) == 200
    }
}