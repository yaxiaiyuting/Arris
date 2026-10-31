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
import com.google.gson.Gson
import com.takahashirinta.ncrust.account.AccountGetResponse
import com.takahashirinta.ncrust.account.SessionMonitor
import com.takahashirinta.ncrust.account.SongPrivilege
import com.takahashirinta.ncrust.account.VipInfoResponse
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.auth.QrLoginProtocol
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
    private suspend fun readEapi(path: String, payload: Map<String, String>): okhttp3.Response =
        com.takahashirinta.ncrust.auth.NeteaseSessionRequests.read { RetrofitClient.eapiPost(path, payload) }

    /** 会员状态（weapi）。字段见 `account.VipInfoResponse`。 */
    private const val VIP_INFO_PATH = "/api/music-vip-membership/client/vip/info"

    // 会话判定用的响应模型（account 包）走 Gson 映射：夹具单测用同一套 @SerializedName
    // 解析 spec/fixtures/entitlement 下的响应，字段映射错了单测就会红。
    private val gson = Gson()

    /**
     * 业务码里唯一需要在网络层就地处理的是 301（用户未登录）：立即把会话状态转 `Expired`，
     * 界面据此显示失效横幅。登录失效时 `account/get` 仍返回 200，只有这类需登录接口会 301，
     * 所以判定必须在读到业务码的地方做。
     */
    private fun reportBusinessCode(code: Int, response: okhttp3.Response) {
        response.request.tag(com.takahashirinta.ncrust.auth.NeteaseSessionSnapshot::class.java)?.let { SessionMonitor.reportApiCode(code, it) }
    }

    /**
     * `account/get`（会话判定用）。
     * 注意：登录失效时它**仍然 200**，只是 `account`/`profile` 为 null —— 判定见
     * `deriveSession`，不能只看 HTTP 状态码。
     */
    suspend fun getAccount(): AccountGetResponse = withContext(Dispatchers.IO) {
        val response = readEapi(ACCOUNT_GET_PATH, emptyMap())
        val body = response.body?.string() ?: throw Exception("empty response")
        val parsed = gson.fromJson(body, AccountGetResponse::class.java)
            ?: throw Exception("account/get 解析失败")
        reportBusinessCode(parsed.code ?: -1, response)
        parsed
    }

    /**
     * 黑胶 VIP / 音乐包状态。只应在会话 `Valid` 时调用。
     * 失败（网络异常 / 非 200 / 301）由调用方处理成「未知」——**绝不**显示成「未开通」。
     */
    suspend fun getVipInfo(userId: Long): VipInfoResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("userId", userId).toString()
        val response = com.takahashirinta.ncrust.auth.NeteaseSessionRequests.read { RetrofitClient.weapiPost(VIP_INFO_PATH, payload) }
        val body = response.body?.string() ?: throw Exception("empty response")
        val parsed = gson.fromJson(body, VipInfoResponse::class.java)
            ?: throw Exception("vip/info 解析失败")
        reportBusinessCode(parsed.code ?: -1, response)
        parsed
    }

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
        val response = readEapi(ACCOUNT_GET_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        reportBusinessCode(json.optInt("code", -1), response)
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
        val response = readEapi(ACCOUNT_GET_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        reportBusinessCode(json.optInt("code", -1), response)

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
    /** Full user playlist list, advancing by raw rows (including hidden special playlists). */
    suspend fun getAllUserPlaylists(
        uid: Long,
        pinned: com.takahashirinta.ncrust.auth.NeteaseSessionSnapshot = CookieManager.snapshot(),
    ): List<PlaylistInfo> {
        val all = LinkedHashMap<Long, PlaylistInfo>()
        var offset = 0
        repeat(100) {
            if (!sameAccount(pinned)) throw java.io.IOException("playlist account changed")
            val page = getUserPlaylists(uid, 100, offset)
            if (!sameAccount(pinned)) throw java.io.IOException("playlist account changed")
            page.playlists.forEach { all[it.id] = it }
            if (!page.more) return all.values.toList()
            if (page.rawCount <= 0) throw java.io.IOException("playlist paging incomplete")
            offset += page.rawCount
        }
        throw java.io.IOException("playlist paging limit reached")
    }

    suspend fun getUserPlaylists(uid: Long, limit: Int = 100, offset: Int = 0): UserPlaylistResult = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "uid" to uid.toString(),
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "includeVideo" to "false"
        )
        val response = readEapi(USER_PLAYLIST_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        if (code != 200) {
            reportBusinessCode(code, response)
            throw Exception("API error: code=$code")
        }

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
            more = json.optBoolean("more", false),
            rawCount = playlistArray.length(),
        )
    }

    /**
     * v1.3.0 · B4：取歌单元信息（不看曲目），供「是否本人自建 → 是否显示编辑/删除入口」判定。
     * 曲目仍走 [getPlaylistDetail]；这里用 n=0 的轻量请求，避免为了一个菜单拉 1000 首详情。
     */
    suspend fun fetchPlaylistInfo(playlistId: Long): PlaylistInfo? = withContext(Dispatchers.IO) {
        val payload = mapOf("id" to playlistId.toString(), "n" to "0", "s" to "0")
        val response = readEapi(PLAYLIST_DETAIL_PATH, payload)
        val body = response.body?.string() ?: return@withContext null
        val json = JSONObject(body)
        reportBusinessCode(json.optInt("code", -1), response)
        val playlist = json.optJSONObject("playlist") ?: return@withContext null
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
        val response = readEapi("/eapi/v1/artist/detail", payload)
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
        val response = readEapi("/eapi/v1/artist/songs", payload)
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
        val response = readEapi("/eapi/artist/albums", payload)
        response.body?.string() ?: throw Exception("empty response")
    }

    /**
     * 歌单详情的**显式结果**（H10）。
     *
     * 旧签名只返回 `List<SongItem>`，把三种完全不同的情况压成同一个形状：
     * ① 歌单确实只有 5 首；② 服务端说 20 首却只给了 5 首（`trackIds` 被截断）；
     * ③ 第二批 `v3/song/detail` 请求失败（少了一截）。调用方（本地同步）无从区分，
     * 于是「读了一半」会被当成「读全了」写进缓存、推进 `lastSyncedAt`。
     *
     * 现在把完整性**带出函数**，由调用方决定不完整要不要重试/降级。
     * 只要列表的既有调用点继续用 [getPlaylistDetail]（便利包装），签名不变。
     */
    @Immutable
    data class PlaylistDetailResult(
        /** 能用的曲目，保持 `trackIds` 的歌单顺序。 */
        val songs: List<SongItem>,
        /** 服务端声明的曲目总数（`playlist.trackCount`）。0 = 服务端没声明，判不了。 */
        val declaredCount: Int,
        /** 有多少批「补详情」请求没拿全（重试后仍失败，或只回来一部分）。 */
        val failedBatches: Int,
        /** 是否取全。判据见 [detailCompleteness]。 */
        val complete: Boolean,
    )

    /**
     * 完整性的**纯判定**（不碰网络，JVM 可单测）。
     *
     * 「有失败批次」一定不完整；声明了总数时还要求拿到的条数对得上。
     * 声明 0 首意味着服务端没给 `trackCount` —— 只能按失败批次判，
     * **不能**因为"没声明"就把未知当完整。
     */
    internal fun detailCompleteness(declaredCount: Int, collected: Int, failedBatches: Int): Boolean =
        failedBatches == 0 && (declaredCount <= 0 || collected >= declaredCount)

    /**
     * 只返回曲目列表的便利包装。**看不出完整性需求的调用点用它**（详情页、一键播放），
     * 需要区分「不完整」与「失败」的调用方用 [getPlaylistDetailResult]。
     */
    suspend fun getPlaylistDetail(playlistId: Long): List<SongItem> =
        getPlaylistDetailResult(playlistId).songs

    suspend fun getPlaylistDetailResult(playlistId: Long): PlaylistDetailResult = withContext(Dispatchers.IO) {
        // n=1000 requests more full-detail tracks; server still caps at ~20 in `tracks`,
        // but always returns the complete list in `trackIds`.
        val payload = mapOf(
            "id" to playlistId.toString(),
            "n" to "1000",
            "s" to "0"
        )
        val response = readEapi(PLAYLIST_DETAIL_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        if (code != 200) {
            reportBusinessCode(code, response)
            throw Exception("API error: code=$code")
        }

        // 200 但缺 `playlist` 对象 = 结构异常：曲目当然是空的，但**不能**报"完整"。
        val playlistObj = json.optJSONObject("playlist") ?: return@withContext PlaylistDetailResult(
            songs = emptyList(), declaredCount = 0, failedBatches = 0, complete = false,
        )
        // 声明总数是判"截断"的唯一权威：服务端说 20 首、只给 5 首时必须能被发现（H10）。
        val declaredCount = playlistObj.optInt("trackCount", 0).coerceAtLeast(0)
        // 权益信息与 tracks[] 并列，按 id 关联到每首歌（无版权判定靠 privilege.st）。
        val privileges = parsePrivileges(playlistObj.optJSONArray("privileges"))

        // Parse the partial track objects that carry full detail (typically first ~20).
        val tracksMap = mutableMapOf<Long, SongItem>()
        val trackArray = playlistObj.optJSONArray("tracks")
        if (trackArray != null) {
            for (i in 0 until trackArray.length()) {
                val song = parseSongTrack(trackArray.getJSONObject(i))
                tracksMap[song.id] = privileges[song.id]?.let { song.copy(privilege = it) } ?: song
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
        // 没有 trackIds 时 `tracks` 就是全部 —— 完整性只能拿声明总数比（H10）。
        if (allIds.isEmpty()) {
            val fetchedAll = tracksMap.values.toList()
            return@withContext PlaylistDetailResult(
                songs = fetchedAll,
                declaredCount = declaredCount,
                failedBatches = 0,
                complete = detailCompleteness(declaredCount, fetchedAll.size, 0),
            )
        }

        // Batch-fetch details for IDs not covered by the partial `tracks` array.
        val missingIds = allIds.filter { it !in tracksMap }
        val batchSize = 500
        // 只拿回一部分也算这一批没成功：少的那几首正是"没取全"的证据（H10）。
        var failedBatches = 0
        for (start in missingIds.indices step batchSize) {
            val batch = missingIds.subList(start, minOf(start + batchSize, missingIds.size))
            val fetched = fetchSongDetails(batch)
            if (fetched.size < batch.size) {
                failedBatches++
                Log.w("PlaylistApi", "batch returned ${fetched.size}/${batch.size} songs from server")
            }
            fetched.forEach { tracksMap[it.id] = it }
        }

        // Return songs in the original playlist order defined by trackIds.
        val songs = allIds.mapNotNull { tracksMap[it] }
        PlaylistDetailResult(
            songs = songs,
            declaredCount = declaredCount,
            failedBatches = failedBatches,
            complete = detailCompleteness(declaredCount, songs.size, failedBatches),
        )
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
        // 原唱/翻唱标注（v2.3.0 的字段，这里长期漏解析）。
        //
        // **症状**：搜索结果里有「原唱」角标，收藏库/歌单详情里同一首歌却没有 ——
        // 因为详情接口（`/eapi/v3/song/detail`、`/eapi/v6/playlist/detail` 等）也下发
        // `originCoverType`，但 `parseSongTrack` 只挑了 id/name/ar/al/dt，把它丢了。
        // 判据仍然只在 `SongTags` 一处（0/3/缺失 = 不打标签），这里只负责**把字段带出去**。
        val originCoverType = track.optInt("originCoverType", -1).takeIf { it >= 0 }
        val originSong = track.optJSONObject("originSongSimpleData")?.let { ref ->
            OriginSongRef(
                songId = ref.optLong("songId").takeIf { it > 0L },
                name = ref.optString("name").takeIf { it.isNotEmpty() },
                artists = ref.optJSONArray("artists")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val a = arr.optJSONObject(i) ?: return@mapNotNull null
                        val n = a.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        ArtistItem(id = a.optLong("id").takeIf { v -> v != 0L }, name = n)
                    }
                },
            )
        }
        return SongItem(
            id = track.optLong("id"),
            name = track.optString("name"),
            artists = artists,
            album = album,
            duration = track.optLong("dt"),
            originCoverType = originCoverType,
            originSong = originSong,
        )
    }

    /**
     * 详情接口的权益数组（`v3/song/detail`、`v6/playlist/detail` 都把它放在与歌曲并列的位置），
     * 按 id 关联到每首歌。缺 `st` 时按 0 处理——**不能**把"没有这个字段"当成"无版权"。
     */
    private fun parsePrivileges(array: JSONArray?): Map<Long, SongPrivilege> {
        if (array == null) return emptyMap()
        val map = mutableMapOf<Long, SongPrivilege>()
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val id = p.optLong("id", 0L)
            if (id <= 0L) continue
            map[id] = SongPrivilege(
                st = p.optInt("st", 0),
                pl = p.optInt("pl", 0),
                // 缺 fee 与 fee=0 是两件事：缺字段时让判定回落到取链响应项的 fee。
                fee = if (p.isNull("fee")) null else p.optInt("fee", 0),
            )
        }
        return map
    }

    private suspend fun fetchSongDetails(ids: List<Long>): List<SongItem> = withContext(Dispatchers.IO) {        val cArray = JSONArray()
        ids.forEach { id -> cArray.put(JSONObject().put("id", id)) }
        val payload = mapOf("c" to cArray.toString())
        // Single retry with 500ms delay to survive transient network blips on large playlists.
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val response = readEapi("/eapi/v3/song/detail", payload)
                val body = response.body?.string() ?: return@repeat
                val json = JSONObject(body)
                reportBusinessCode(json.optInt("code", -1), response)
                val songArray = json.optJSONArray("songs") ?: return@repeat
                val privileges = parsePrivileges(json.optJSONArray("privileges"))
                return@withContext (0 until songArray.length()).map { i ->
                    val song = parseSongTrack(songArray.getJSONObject(i))
                    privileges[song.id]?.let { song.copy(privilege = it) } ?: song
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消/超时**原样抛出**：吞掉它会让上层以为"这批就是没歌"，
                // 于是取消被伪装成数据缺失（H10 之后这里会直接变成 failedBatches）。
                throw e
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
        val more: Boolean,
        val rawCount: Int = playlists.size,
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
        val response = readEapi("/eapi/v2/discovery/recommend/songs", emptyMap())
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        // 登录失效时这个接口**仍然 200**，只是返回非个性化的通用列表；
        // 真正会 301 的是收藏等私有接口，但代码里统一上报：301 即转 Expired。
        reportBusinessCode(json.optInt("code", -1), response)
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
        val response = readEapi("/eapi/v1/discovery/recommend/resource", emptyMap())
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
        val response = readEapi("/eapi/toplist", emptyMap())
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
        val response = readEapi("/eapi/v1/radio/get", emptyMap())
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
            val response = readEapi(
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
        val qrimg: String?
    )

    /**
     * 申请二维码登录 key。
     *
     * 走 **eapi + PC 客户端身份 + type=3**（打 interfacepc，协议见 `spec/api/endpoints.md`）。
     * 旧的 weapi 形态（type=1 + noCheckToken，二维码带 web 会话的 chainId）2026-10 实测被回
     * `-462` 风控，二维码根本出不来。二维码内容也不再带 chainId——PC 身份不用它。
     */
    suspend fun getLoginQrKey(): LoginQrKey? = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPostPc(QrLoginProtocol.UNIKEY_PATH, QrLoginProtocol.unikeyFields())
        val body = response.body?.string() ?: return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        if (json.optInt("code", -1) != 200) return@withContext null
        val unikey = json.optString("unikey")
        if (unikey.isEmpty()) return@withContext null
        LoginQrKey(
            unikey = unikey,
            qrurl = QrLoginProtocol.qrContent(unikey),
            qrimg = json.optString("qrimg", "").takeIf { it.isNotEmpty() }
        )
    }

    data class LoginQrStatus(val code: Int, val cookie: String?)

    /**
     * 轮询二维码状态。code: 800=过期, 801=待扫码, 802=已扫码待确认, 803=成功。
     *
     * 803 时会话 cookie 在响应的 Set-Cookie 头里（同名取**最后一个非空** MUSIC_U），
     * 拼出完整 cookie 串交上层走 CookieManager/RetrofitClient 的既有保存路径。
     * 这次登录**还没落盘**，所以下面的校验必须显式带这份 cookie。
     */
    suspend fun checkLoginQr(key: String): LoginQrStatus = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPostPc(
            QrLoginProtocol.CLIENT_LOGIN_PATH,
            QrLoginProtocol.clientLoginFields(key)
        )
        val body = response.body?.string() ?: return@withContext LoginQrStatus(-1, null)
        val code = runCatching { JSONObject(body).optInt("code", -1) }.getOrDefault(-1)
        if (code == 803) {
            val setCookies = response.headers("Set-Cookie")
            // 日志只记业务码与 Set-Cookie 条数：cookie 内容一律不进日志。
            Log.d("PlaylistApi", "qr803 setCookie=${setCookies.size}")
            return@withContext LoginQrStatus(code, QrLoginProtocol.sessionCookie(setCookies))
        }
        LoginQrStatus(code, null)
    }

    /** 会话校验结果：Invalid = 服务端明确说没登录；Unknown = 网络 / 解析失败，判不了。 */
    enum class SessionCheck { Valid, Invalid, Unknown }

    /**
     * 用刚拿到的 cookie 打一次 `account/get`：`account` 非空才算这次登录真的成立。
     *
     * 轮询回 803 只说明手机上确认了，不等于服务端认这个会话；不校验就会出现「提示登录成功、
     * 进首页还是未登录」。网络失败与响应读不出都归 Unknown（**不能**当成无效），
     * 上层据此区分「重试」与「网络异常」。
     */
    suspend fun validateSession(cookie: String): SessionCheck = withContext(Dispatchers.IO) {
        val response = try {
            RetrofitClient.eapiPost(ACCOUNT_GET_PATH, emptyMap(), cookieOverride = cookie)
        } catch (e: Exception) {
            Log.w("PlaylistApi", "session check request failed: ${e.message}")
            return@withContext SessionCheck.Unknown
        }
        val body = try {
            response.body?.string()
        } catch (e: Exception) {
            null
        } ?: return@withContext SessionCheck.Unknown
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext SessionCheck.Unknown
        if (json.optJSONObject("account") != null) SessionCheck.Valid else SessionCheck.Invalid
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
        val response = readEapi(USER_PLAYLIST_PATH, payload)
        val body = response.body?.string() ?: return@withContext null
        val json = JSONObject(body)
        reportBusinessCode(json.optInt("code", -1), response)
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
            val response = readEapi(PLAYLIST_DETAIL_PATH, payload)
            val body = response.body?.string()
                ?: return@withContext LikedIdsResult.Failure("empty body")
            val json = JSONObject(body)
            // 业务码必须显式校验：v3.4.5 之前这里从不看 code，
            // 于是 -460 / 未登录都长成「收藏为空」。
            val code = json.optInt("code", -1)
            if (code != 200) {
                // 登录失效时这里返回 301：立即把会话转 Expired，界面显示失效横幅。
                reportBusinessCode(code, response)
                return@withContext LikedIdsResult.Failure("business code=$code")
            }
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
        val response = readEapi(
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
     * 收藏(like=true) / 取消收藏(false) 单曲（写入「我喜欢的音乐」）。
     *
     * 主通道是官方 Android 9.x 的 **xeapi** `/api/v1/radio/like`：
     * `alg=itembased&trackId=<id>&like=<bool>&time=3`，带易盾 token。
     * `time` 只是试听秒数（官方固定 3）；`checkToken`（同一份易盾 token）由 `RetrofitClient.xeapiPost`
     * 在 antiCheat 请求里补进业务体末尾——官方两端都把它放进体（`spec/api/xeapi.md` §6）。
     *
     * 为什么不再走老通道：weapi / eapi 的 `/api/radio/like` 写入的收藏当天在官方与两端都可见、
     * **隔天被服务端回滚**。de193ff / 25f0c39 把 `time` 从 Unix 时间戳改成 3 之后**仍复现**，
     * 所以「time 是诱因」这个旧结论是错的——真正的机制是服务端把非官方指纹的写入判为异常环境，
     * 例行审核时回滚（gmg137/netease-cloud-music-gtk#410 实测到 `524「当前环境异常，已取消喜欢」`）。
     * 调研与对照实验见 `spec/api/xeapi.md` 末节。
     *
     * weapi / eapi **只作回退**：两者都还在老通道上，**同样有被事后回滚的风险**；xeapi 失败时用它们
     * 只是「至少这次点亮了」的兜底，不是长久的修复。
     *
     * 注意：like 写操作历史上曾受网易账号/IP 级风险控制，本账号四种协议变体(eapi 最小/
     * eapi+PC 指纹/eapi+安卓身份/经典 weapi)均被 `-460「检测到您的网络环境存在风险」`或
     * 异常响应拦截而读取全部正常。该注释是既往观测，**不代表当前版本/当前账号的现状**——
     * 以实际响应为准。成功与否只由 `code == 200` 判定，绝不把非 200 响应当成功。
     *
     * 只返回布尔结果不够：调用方（LibraryManager）需要据此决定是否标记"仅本地/待同步"，
     * 并在成功后再做一次读回校验。这里额外记录脱敏的 HTTP 状态、业务码与耗时，便于诊断；
     * **不记录** Cookie、易盾 token、CSRF token 或完整加密请求体。
     */
    suspend fun likeSong(
        songId: Long,
        like: Boolean,
        /** 排任务时钉住的会话（S18）：切账号后不再用新凭据重发这个旧操作。 */
        pinned: com.takahashirinta.ncrust.auth.NeteaseSessionSnapshot? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        // 每个通道发之前都复核：A 会话的动作绝不能落到 B 的凭据上。
        if (pinned != null && !sameAccount(pinned)) {
            Log.w("PlaylistApi", "likeSong aborted: session changed id=$songId like=$like")
            return@withContext false
        }
        if (tryXeapiLike(songId, like, pinned?.loginGeneration) == 200) return@withContext true
        if (pinned != null && !sameAccount(pinned)) {
            Log.w("PlaylistApi", "likeSong aborted before weapi: session changed id=$songId")
            return@withContext false
        }
        if (tryWeapiLike(songId, like, pinned?.cookie) == 200) return@withContext true
        if (pinned != null && !sameAccount(pinned)) {
            Log.w("PlaylistApi", "likeSong aborted before eapi: session changed id=$songId")
            return@withContext false
        }
        tryEapiLike(songId, like, pinned?.cookie, pinned?.loginGeneration) == 200
    }

    /**
     * 会话归属复核（S18 / R1）：**只认登录代次**。
     *
     * 旧判据是 `CookieManager.isCurrent(pinned)`，它同时比较 `revision` —— 而同账号
     * **续期换票只推进 revision、不换登录代次**。于是后台换票会让一个仍在排队/正在重试的
     * 收藏写入被当成"换号"丢弃：用户点过的那次收藏静默消失。换号与登出都会推进代次，
     * 所以代次相同 = 同一个账号，用旧票发也只会落到同一个账号上。
     */
    private fun sameAccount(pinned: com.takahashirinta.ncrust.auth.NeteaseSessionSnapshot): Boolean =
        CookieManager.snapshot().loginGeneration == pinned.loginGeneration

    /**
     * xeapi `/api/v1/radio/like`——与官方 Android 9.x 同形（xeapi 加密 + 持久 deviceId + 易盾 token）。
     * 字段顺序固定，别改：顺序进加密体，换了顺序就是另一种指纹。
     */
    private fun tryXeapiLike(songId: Long, like: Boolean, pinnedGeneration: Long? = null): Int {
        val startedAt = System.currentTimeMillis()
        return try {
            val text = RetrofitClient.xeapiPost(
                uri = "/api/v1/radio/like",
                pairs = listOf(
                    "alg" to "itembased",
                    "trackId" to songId.toString(),
                    "like" to like.toString(),
                    "time" to "3"
                ),
                antiCheat = true,
                pinnedGeneration = pinnedGeneration,
            )
            val code = JSONObject(text).optInt("code", -1)
            Log.i(
                "PlaylistApi",
                "likeSong(xeapi) id=$songId like=$like code=$code elapsedMs=${System.currentTimeMillis() - startedAt}"
            )
            code
        } catch (e: Throwable) {
            Log.w(
                "PlaylistApi",
                "likeSong(xeapi) req failed id=$songId like=$like " +
                    "elapsedMs=${System.currentTimeMillis() - startedAt}: ${e.message}"
            )
            -1
        }
    }

    /** 回退通道 1：经典 weapi `/api/radio/like`。**老通道，收藏可能被服务端事后回滚**。 */
    private fun tryWeapiLike(songId: Long, like: Boolean, pinnedCookie: String? = null): Int {
        return try {
            val payload = JSONObject()
                .put("alg", "itembased")
                .put("trackId", songId)
                .put("like", like)
                .put("time", "3")
                .toString()
            val response = RetrofitClient.weapiPost(
                "/api/radio/like", payload, cookieOverride = pinnedCookie,
            )
            val body = response.body?.string() ?: return -1
            JSONObject(body).optInt("code", -1).also {
                Log.i("PlaylistApi", "likeSong(weapi) id=$songId like=$like code=$it")
            }
        } catch (e: Throwable) {
            Log.w("PlaylistApi", "likeSong(weapi) req failed id=$songId like=$like", e)
            -1
        }
    }

    /** 回退通道 2：eapi「官方 iPhone 指纹」`/eapi/radio/like`。**同样是老通道，可能被事后回滚**。 */
    private fun tryEapiLike(
        songId: Long,
        like: Boolean,
        pinnedCookie: String? = null,
        pinnedGeneration: Long? = null,
    ): Int {
        val payload = mapOf(
            "alg" to "itembased",
            "trackId" to songId.toString(),
            "like" to like.toString(),
            "time" to "3",
            "e_r" to "TRUE",
            "csrf_token" to (RetrofitClient.getCsrfToken().orEmpty())
        )
        val startedAt = System.currentTimeMillis()
        val http = try {
            RetrofitClient.eapiPostOfficial(
                "/eapi/radio/like", payload, pinnedCookie, pinnedGeneration,
            )
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

    private data class AlbumPage(val albums: List<CloudAlbum>, val more: Boolean)

    /** Complete bounded read: any failed/truncated page leaves the caller's old cache intact. */
    suspend fun getSubscribedAlbums(limit: Int = 100, offset: Int = 0): List<CloudAlbum> {
        val pageSize = limit.coerceIn(1, 100)
        var cursor = offset.coerceAtLeast(0)
        val generation = com.takahashirinta.ncrust.auth.CookieManager.snapshot().loginGeneration
        val all = LinkedHashMap<Long, CloudAlbum>()
        repeat(100) {
            val page = getSubscribedAlbumPage(pageSize, cursor)
            if (com.takahashirinta.ncrust.auth.CookieManager.snapshot().loginGeneration != generation)
                throw com.takahashirinta.ncrust.auth.NeteaseSessionChangedException("Login changed during album read")
            val oldSize = all.size
            page.albums.forEach { all[it.albumId] = it }
            if (!page.more) return all.values.toList()
            if (page.albums.isEmpty() || oldSize == all.size) throw java.io.IOException("Album page made no progress")
            cursor += page.albums.size
        }
        throw java.io.IOException("Album page limit exceeded")
    }

    private suspend fun getSubscribedAlbumPage(limit: Int, offset: Int): AlbumPage = withContext(Dispatchers.IO) {
        val response = readEapi("/eapi/album/sublist", mapOf(
            "limit" to limit.toString(), "offset" to offset.toString(), "total" to "true",
        ))
        response.use {
            val json = JSONObject(it.body?.string() ?: throw java.io.IOException("Empty album response"))
            val code = json.optInt("code", -1)
            reportBusinessCode(code, it)
            if (!it.isSuccessful || code != 200) throw java.io.IOException("Album read failed: code=$code")
            val arr = json.optJSONArray("data") ?: json.optJSONObject("data")?.optJSONArray("albums")
                ?: throw java.io.IOException("Missing album array")
            val albums = (0 until arr.length()).map { i ->
                val album = arr.getJSONObject(i)
                val id = album.optLong("id")
                if (id <= 0) throw java.io.IOException("Missing album identity")
                CloudAlbum(id, album.optString("name"),
                    album.optJSONArray("artists")?.optJSONObject(0)?.optString("name")
                        ?: album.optJSONObject("artist")?.optString("name").orEmpty(),
                    album.optString("picUrl"), album.optInt("size"))
            }
            // Older response shapes omit hasMore: request one extra page when exactly full.
            val more = when {
                json.has("hasMore") -> json.getBoolean("hasMore")
                json.has("more") -> json.getBoolean("more")
                else -> albums.size >= limit
            }
            AlbumPage(albums, more)
        }
    }

    /**
     * 收藏(sub=true) / 取消收藏(false) 专辑（eapi，与客户端一致）。
     *
     * @param pinned 排任务时钉住的会话（H01）。切账号/登出后这个旧操作直接作废，
     *   **不会**被新账号的凭据重发一遍。判据用**登录代次**而不是 cookie 字节：
     *   同账号续期只换票（revision 变、代次不变），那条订阅动作仍然属于同一个账号。
     */
    suspend fun subAlbum(
        albumId: Long,
        sub: Boolean,
        pinned: com.takahashirinta.ncrust.auth.NeteaseSessionSnapshot? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val session = CookieManager.snapshot()
        if (pinned != null && session.loginGeneration != pinned.loginGeneration) {
            Log.w("PlaylistApi", "subAlbum aborted: session changed id=$albumId sub=$sub")
            return@withContext false
        }
        val action = if (sub) "sub" else "unsub"
        val payload = mapOf("id" to albumId.toString())
        // 复核与发包之间没有挂起点：不传 cookieOverride 是为了让请求保留会话 tag，
        // 迟到的 301 仍能走 SessionMonitor 把会话转 Expired。
        RetrofitClient.eapiPost("/eapi/album/$action", payload, pinnedSession = session).use { response ->
            val body = response.body?.string() ?: return@withContext false
            val code = JSONObject(body).optInt("code", -1)
            reportBusinessCode(code, response)
            code == 200
        }
    }
}