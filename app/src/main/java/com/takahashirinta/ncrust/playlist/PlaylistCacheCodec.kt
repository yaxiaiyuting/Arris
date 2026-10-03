/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：歌单缓存的编解码与**结构演进迁移**。纯逻辑，无 Android 依赖，JVM 可单测。
 */

package com.takahashirinta.ncrust.playlist

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.Playlist
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.PlaylistTrack
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.musicSource

/**
 * 歌单缓存的编解码 + schema 迁移（v2.2.0）。
 *
 * ## 为什么这里必须有一套「迁移」而不是直接 Gson 反序列化
 *
 * 本项目 v1.9.3 固化的规矩是 **「加字段 = 加迁移逻辑 = 加单测」**：持久化结构会跨版本存活，
 * 而 Gson **走 Unsafe 反序列化、不调用构造函数** —— 老 JSON 里缺的字段在 Kotlin 侧就是 `null`
 * （即便声明成非空类型也不会报错）。两次真机踩坑（v1.9.0 的 `ttml`、v1.9.2 的 `romalrc`）
 * 都出在同一件事上。
 *
 * 本文件把这条规矩落成机制，而不是靠自觉：
 *
 * 1. **落盘一律走 DTO**（本文件里的 `*Dto`），字段**全部可空 + 有默认值**；
 * 2. 领域模型（[Playlist] / [PlaylistTrack]）**只由 DTO 显式构造**，
 *    构造时对「必填字段缺失」返回 null 并**丢弃该条目** —— 缺字段 ≠ 空值；
 * 3. DTO 与领域模型之间**不共享类**，所以将来给领域模型加字段时，
 *    「忘了加迁移」会表现为**编译错误**（DTO 里没有那个字段），而不是运行时的静默 null；
 * 4. 版本号显式落盘（[SCHEMA_VERSION]），未知/更高的版本**拒绝解释**而不是猜。
 *
 * ## 为什么 v1 → v2 的迁移是「丢弃」而不是「补默认值」
 *
 * v1 的条目**没有 `ownerId`**。歌单是**按账号隔离**的数据（同一个 `tid` 在不同账号下
 * 可能完全不是一回事，qm 的 `dirId` 更是账号内编号）。给缺 `ownerId` 的老条目
 * 「补上当前账号」是**最危险的一种迁移**：它会把「不知道属于谁」的数据**断言成属于当前账号**，
 * 于是 A 账号的歌单会在 B 账号下被当成自己的显示出来。
 *
 * 所以这里**丢弃**：代价是一次重新拉取（联网即可恢复），收益是**结构上不可能串号**。
 * 这与 `PlaylistKey.OWNER_ANONYMOUS` 用显式常量而不是空串是同一条原则。
 */
object PlaylistCacheCodec {

    /**
     * 当前 schema 版本。
     *
     * - **v1**：只有 `id`/`name`/… ，**没有 `ownerId`**（本版之前不存在，仅用于迁移测试与未来兼容）。
     * - **v2**：条目携带 `ownerId` 与 `dirId`/`isFavorite`，缓存按账号隔离。
     */
    const val SCHEMA_VERSION = 2

    /** 列表缓存的新鲜期：默认 10 分钟。TTL **只影响「要不要自动刷新」，不影响「能不能看」**。 */
    const val DEFAULT_TTL_MS = 10 * 60 * 1000L

    private val gson = Gson()

    /**
     * 编译期捕获的集合类型。用它们解析数组**不依赖字段的泛型签名**，
     * 因此不受 R8 混淆/属性裁剪影响（见 [ListEnvelope] 的 KDoc）。
     * proguard 已有 `-keep class * extends com.google.gson.reflect.TypeToken`。
     */
    private val playlistListType = object : TypeToken<List<PlaylistDto>>() {}.type
    private val songListType = object : TypeToken<List<SongDto>>() {}.type

    /** 解析信封里那段「字符串形式的数组」；`null`/空串/坏 JSON 一律返回 null（按 MALFORMED 处理）。 */
    private fun decodePlaylistRows(json: String?): List<PlaylistDto>? {
        if (json.isNullOrBlank()) return null
        return runCatching { gson.fromJson<List<PlaylistDto>>(json, playlistListType) }.getOrNull()
    }

    private fun decodeSongRows(json: String?): List<SongDto>? {
        if (json.isNullOrBlank()) return null
        return runCatching { gson.fromJson<List<SongDto>>(json, songListType) }.getOrNull()
    }

    // ---------------------------------------------------------------- DTO ----
    // 全部可空 + 默认值：Gson 不调用构造函数，缺字段时必须是 null 而不是抛异常。

    internal data class PlaylistDto(
        val id: String? = null,
        val source: String? = null,
        val ownerId: String? = null,
        val name: String? = null,
        val coverUrl: String? = null,
        val trackCount: Int = 0,
        val isOwned: Boolean = false,
        val isFavorite: Boolean = false,
        val updatedAt: Long = 0L,
        val dirId: Long = 0L,
    )

    internal data class TrackDto(
        val id: Long = 0L,
        val source: String? = null,
        val sourceId: String? = null,
        val mediaId: String? = null,
        val order: Int = 0,
        val addedAt: Long? = null,
    )

    /**
     * 曲目里一个艺人的落盘形状（v2.6.1：新增 [mid]）。
     *
     * **这不是冗余字段**：详情缓存存的是扁平 DTO，而不是直接把 `SongItem` 丢给 Gson，
     * 所以 `ArtistItem` 上新增的字段**不会**自动跟过来 —— 漏掉这一处，
     * 「QQ 歌单详情 → 长按曲目 → 转到歌手」就会退化成跳搜索
     * （ncm 歌单不受影响，因为它的身份就是 [id]）。
     *
     * [mid] 可空 + 有默认值：本字段出现之前落盘的条目里没有这个 key，
     * Gson 走 Unsafe 反序列化、不调用构造函数 ⇒ 读出来是 `null` ⇒
     * `ArtistNavigator` 判为「身份不可信」并跳搜索。这**是自愈的**：
     * 下一次联网刷新详情就会写回带 mid 的数据，所以**不需要** bump schema。
     */
    internal data class ArtistDto(
        val id: Long? = null,
        val name: String? = null,
        /** v2.6.1：qm 的 `singerMID`；ncm 恒为 null。 */
        val mid: String? = null,
    )

    internal data class AlbumDto(val id: Long? = null, val name: String? = null, val picUrl: String? = null)

    /**
     * 曲目的落盘形状。
     *
     * ## 为什么详情缓存存的是「歌曲」而不是「[PlaylistTrack] 身份」
     *
     * [PlaylistTrack] 只带身份（`TrackKey` + 序号），不带歌名/歌手/封面 —— 它足够用来
     * **判等与路由**，但**不够画出一个列表**。而「离线可看」要求缓存里必须有能画的东西。
     *
     * 所以详情缓存存 [SongItem] 的扁平 DTO：它本来就是本项目的「可持久化的歌曲」
     * （`LibraryManager.saved_songs`、`PlaybackStateManager.queue` 都这么存），
     * 且它同时携带 `source`/`sourceId`/`mediaId`，因此**能无损反推出 [PlaylistTrack]**
     * （见 [decodeDetail]）。一份数据、两个视图，不存两遍。
     *
     * 与 [TrackDto] 的关系：[TrackDto] 保留给「只有身份、没有元数据」的场景
     * （以及 v1 迁移测试），详情缓存走 [SongDto]。
     */
    internal data class SongDto(
        val id: Long = 0L,
        val name: String? = null,
        val artists: List<ArtistDto>? = null,
        val album: AlbumDto? = null,
        val duration: Long = 0L,
        val source: String? = null,
        val sourceId: String? = null,
        val mediaId: String? = null,
        val memberOnly: Boolean = false,
    )

    /**
     * 列表信封。
     *
     * ## 为什么数组存成**字符串**（`playlistsJson`）而不是 `List<PlaylistDto>`
     *
     * 这是 release 真机上踩出来的一个 R8 坑，不是风格选择：
     *
     * `List<PlaylistDto>` 的元素类型只能靠**字段的泛型签名 attribute** 得知。
     * R8 对本包（`playlist.**`）没有 keep 规则时，会把这个 attribute 丢掉，
     * 于是 Gson 看到的是**裸 `List`** ⇒ 元素按 `Object` 反序列化成 `LinkedTreeMap`
     * ⇒ 代码里 `as PlaylistDto` 直接 `ClassCastException` 崩溃。
     * **debug 构建完全正常**（不混淆），所以它只在 release APK 上出现 ——
     * PCL110 实测崩溃栈（retrace 后）：
     * `PlaylistCacheCodec.decodeList(PlaylistCacheCodec.kt:463)` ← `LinkedTreeMap cannot be cast to PlaylistDto`。
     *
     * 存成字符串之后，数组用**编译期捕获**的 `TypeToken<List<PlaylistDto>>` 解析
     * （项目里 `HomeSnapshot` / `LyricsCache` 用的就是这套，且 proguard 已有
     * `-keep class * extends com.google.gson.reflect.TypeToken`），
     * **不依赖任何字段泛型签名**，因此与 keep 规则无关、结构上不会再犯。
     *
     * 代价是 JSON 里多一层转义、肉眼可读性略降；正确性优先。
     */
    internal data class ListEnvelope(
        val version: Int = 0,
        val ownerId: String? = null,
        val savedAt: Long = 0L,
        val playlistsJson: String? = null,
    )

    /** 详情信封。数组同样存成字符串，理由见 [ListEnvelope]。 */
    internal data class DetailEnvelope(
        val version: Int = 0,
        val ownerId: String? = null,
        val savedAt: Long = 0L,
        val complete: Boolean = false,
        val total: Int = 0,
        val songsJson: String? = null,
    )

    // ------------------------------------------------------------- 读结果 ----

    /** 缓存读取被丢弃的原因。**每一种都要能在日志里一眼区分**，不要合并成「读失败」。 */
    enum class DropReason {
        /** 压根没有缓存。 */
        NONE,

        /** JSON 解析失败 / 结构不是预期的信封。 */
        MALFORMED,

        /** 老条目没有 `ownerId`（v1），**无法判定归属** ⇒ 丢弃，不猜。 */
        LEGACY_NO_OWNER,

        /** 落盘版本高于本版（用户降级安装）⇒ 拒绝解释。 */
        FUTURE_SCHEMA,

        /** 条目属于**另一个账号** ⇒ 丢弃（按源/按账号隔离的读侧兜底）。 */
        OWNER_MISMATCH,
    }

    /**
     * 列表缓存的读取结果。
     *
     * [fresh] 与「有没有数据」是**两件事**：TTL 过期只表示「该刷新了」，
     * 数据本身仍然要能画出来 —— 否则「离线可看」这条需求不成立。
     */
    sealed interface ListRead {
        data class Ok(
            val playlists: List<Playlist>,
            val savedAt: Long,
            val fresh: Boolean,
        ) : ListRead

        data class Dropped(val reason: DropReason) : ListRead
    }

    /** 详情缓存的读取结果。[complete] = 分页是否已经拉完（false 时曲目数可能少于 [total]）。 */
    sealed interface DetailRead {
        /**
         * @property songs 可直接渲染/播放的歌曲（顺序即歌单顺序）。
         * @property tracks 由 [songs] **无损反推**出的身份视图，供 [PlaylistLoadCoordinator] 判等用。
         *   两者一一对应、长度相等 —— 反推不出来的条目（`id <= 0`）在两侧都被丢掉。
         */
        data class Ok(
            val songs: List<SongItem>,
            val tracks: List<PlaylistTrack>,
            val savedAt: Long,
            val fresh: Boolean,
            val complete: Boolean,
            val total: Int,
        ) : DetailRead

        data class Dropped(val reason: DropReason) : DetailRead
    }

    // --------------------------------------------------------------- 编码 ----

    fun encodeList(ownerId: String, playlists: List<Playlist>, savedAt: Long): String =
        gson.toJson(
            ListEnvelope(
                version = SCHEMA_VERSION,
                ownerId = ownerId,
                savedAt = savedAt,
                playlistsJson = gson.toJson(playlists.map { it.toDto() }, playlistListType),
            )
        )

    fun encodeDetail(
        ownerId: String,
        songs: List<SongItem>,
        savedAt: Long,
        complete: Boolean,
        total: Int,
    ): String = gson.toJson(
        DetailEnvelope(
            version = SCHEMA_VERSION,
            ownerId = ownerId,
            savedAt = savedAt,
            complete = complete,
            total = total,
            songsJson = gson.toJson(songs.map { it.toDto() }, songListType),
        )
    )

    // --------------------------------------------------------------- 解码 ----

    /**
     * 解码列表缓存。
     *
     * @param expectedOwnerId 当前账号。**必须传**；缓存里记录的 `ownerId` 与它不一致时按
     *   [DropReason.OWNER_MISMATCH] 丢弃。这是「缓存 key 已经带了 ownerId」之外的第二道闸门 ——
     *   缓存 key 防的是「读错文件」，这里防的是「同一份 JSON 被写进了错误的 key」。
     * @param now 当前时间（注入以便单测）。
     */
    fun decodeList(
        raw: String?,
        expectedOwnerId: String,
        now: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): ListRead {
        if (raw.isNullOrBlank()) return ListRead.Dropped(DropReason.NONE)
        val env = runCatching { gson.fromJson(raw, ListEnvelope::class.java) }.getOrNull()
            ?: return ListRead.Dropped(DropReason.MALFORMED)

        when (checkVersion(env.version)) {
            VersionVerdict.Future -> return ListRead.Dropped(DropReason.FUTURE_SCHEMA)
            VersionVerdict.LegacyNoOwner -> return ListRead.Dropped(DropReason.LEGACY_NO_OWNER)
            VersionVerdict.Current -> Unit
        }
        if (env.ownerId.isNullOrBlank()) return ListRead.Dropped(DropReason.LEGACY_NO_OWNER)
        if (env.ownerId != expectedOwnerId) return ListRead.Dropped(DropReason.OWNER_MISMATCH)

        val rows = decodePlaylistRows(env.playlistsJson)
            ?: return ListRead.Dropped(DropReason.MALFORMED)
        // 条目级：必填字段缺失的一律丢弃（缺字段 ≠ 空值）。
        val decoded = rows.mapNotNull { it.toDomain() }
        if (decoded.isEmpty() && rows.isNotEmpty()) return ListRead.Dropped(DropReason.MALFORMED)
        return ListRead.Ok(
            playlists = decoded,
            savedAt = env.savedAt,
            fresh = isFresh(env.savedAt, now, ttlMs),
        )
    }

    fun decodeDetail(
        raw: String?,
        expectedOwnerId: String,
        now: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): DetailRead {
        if (raw.isNullOrBlank()) return DetailRead.Dropped(DropReason.NONE)
        val env = runCatching { gson.fromJson(raw, DetailEnvelope::class.java) }.getOrNull()
            ?: return DetailRead.Dropped(DropReason.MALFORMED)

        when (checkVersion(env.version)) {
            VersionVerdict.Future -> return DetailRead.Dropped(DropReason.FUTURE_SCHEMA)
            VersionVerdict.LegacyNoOwner -> return DetailRead.Dropped(DropReason.LEGACY_NO_OWNER)
            VersionVerdict.Current -> Unit
        }
        if (env.ownerId.isNullOrBlank()) return DetailRead.Dropped(DropReason.LEGACY_NO_OWNER)
        if (env.ownerId != expectedOwnerId) return DetailRead.Dropped(DropReason.OWNER_MISMATCH)

        val rows = decodeSongRows(env.songsJson)
            ?: return DetailRead.Dropped(DropReason.MALFORMED)
        // 条目级：`id <= 0` 或 source 缺失的一律丢弃（缺字段 ≠ 空值）。
        val decoded = rows.mapIndexedNotNull { i, dto -> dto.toDomain()?.let { i to it } }
        if (decoded.isEmpty() && rows.isNotEmpty()) return DetailRead.Dropped(DropReason.MALFORMED)
        val songs = decoded.map { it.second }
        // 身份视图由歌曲**无损反推**（序号 = 列表下标），不额外落盘一份 —— 两份数据必然会漂移。
        val tracks = decoded.mapNotNull { (i, song) ->
            PlaylistTrack.of(MusicSource.QQMUSIC, song, i)
                .takeIf { song.musicSource == MusicSource.QQMUSIC }
        }
        return DetailRead.Ok(
            songs = songs,
            tracks = tracks,
            savedAt = env.savedAt,
            fresh = isFresh(env.savedAt, now, ttlMs),
            complete = env.complete,
            total = env.total,
        )
    }

    // ------------------------------------------------------------- 迁移判定 ----

    internal enum class VersionVerdict { Current, LegacyNoOwner, Future }

    /**
     * 版本判定。**纯函数**，单测直接打这三条分支。
     *
     * - `version <= 1`（含「字段缺失 ⇒ 0」）⇒ [VersionVerdict.LegacyNoOwner]：v1 形状没有 ownerId；
     * - `version == SCHEMA_VERSION` ⇒ 正常；
     * - `version > SCHEMA_VERSION` ⇒ 用户装过更新的版本又降级回来，**拒绝解释**（不要猜）。
     */
    internal fun checkVersion(version: Int): VersionVerdict = when {
        version > SCHEMA_VERSION -> VersionVerdict.Future
        version < SCHEMA_VERSION -> VersionVerdict.LegacyNoOwner
        else -> VersionVerdict.Current
    }

    /**
     * 新鲜度判定。边界取**左闭右开**（`now - savedAt == ttlMs` 算过期），
     * 与 `LyricsCache.isTtmlFresh` 的边界语义保持一致。
     *
     * `savedAt <= 0`（字段缺失）**一律算过期** —— 这正是「缺字段按 miss 处理」那条规矩，
     * 否则一条没有时间戳的老条目会永远新鲜、永远不刷新。
     */
    internal fun isFresh(savedAt: Long, now: Long, ttlMs: Long): Boolean {
        if (savedAt <= 0L) return false
        val age = now - savedAt
        if (age < 0L) return false // 时钟回拨：不信任，按过期处理（下次联网即自愈）
        return age < ttlMs
    }

    // --------------------------------------------------------------- 映射 ----

    private fun Playlist.toDto() = PlaylistDto(
        id = key.id,
        source = key.source.key,
        ownerId = key.ownerId,
        name = name,
        coverUrl = coverUrl,
        trackCount = trackCount,
        isOwned = isOwned,
        isFavorite = isFavorite,
        updatedAt = updatedAt,
        dirId = dirId,
    )

    /**
     * DTO → 领域模型。**必填字段（id / source / ownerId）缺失时返回 null**。
     *
     * 注意 `source` 的解析用的是 [MusicSource.fromKey]，它对未知值**回落 ncm**。
     * 这在「读老数据」时是对的（v2.1.0 之前的队列 JSON 没有 source 字段 ⇒ 那确实是 ncm），
     * 但在这里**不能**直接用它判合法性 —— 老条目 `source` 为 null 时会被静默当成 ncm 歌单。
     * 所以下面**先判 null/空**再解析：缺 source 的条目直接丢弃。
     */
    private fun PlaylistDto.toDomain(): Playlist? {
        val id = id?.takeIf { it.isNotBlank() } ?: return null
        val srcKey = source?.takeIf { it.isNotBlank() } ?: return null
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return null
        return Playlist(
            key = PlaylistKey(MusicSource.fromKey(srcKey), id, owner),
            name = name.orEmpty(),
            coverUrl = coverUrl?.takeIf { it.isNotBlank() },
            trackCount = trackCount.coerceAtLeast(0),
            isOwned = isOwned,
            isFavorite = isFavorite,
            updatedAt = updatedAt.coerceAtLeast(0L),
            dirId = dirId.coerceAtLeast(0L),
        )
    }

    private fun SongItem.toDto() = SongDto(
        id = id,
        name = name,
        artists = artists?.map { ArtistDto(id = it.id, name = it.name, mid = it.mid) },
        album = album?.let { AlbumDto(id = it.id, name = it.name, picUrl = it.picUrl) },
        duration = duration ?: 0L,
        source = source,
        sourceId = sourceId,
        mediaId = mediaId,
        memberOnly = memberOnly == true,
    )

    /**
     * DTO → [SongItem]。**`id <= 0` 或 `source` 缺失时返回 null**（条目被丢弃）。
     *
     * `source` 先判 null/空再交给 [MusicSource.fromKey]：后者对未知值**回落 ncm**，
     * 那是为「v2.1.0 之前的队列 JSON 没有 source 字段」准备的兼容语义；
     * 这里若直接用它，一条 `source=null` 的损坏条目会被静默当成**ncm**歌曲，
     * 于是 QQ 歌单里混进一首「来自 ncm」的歌 —— 点下去会拿另一个平台的 id 去取链。
     */
    private fun SongDto.toDomain(): SongItem? {
        if (id <= 0L) return null
        val srcKey = source?.takeIf { it.isNotBlank() } ?: return null
        return SongItem(
            id = id,
            name = name.orEmpty(),
            artists = artists?.mapNotNull { a ->
                val n = a.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ArtistItem(id = a.id?.takeIf { it > 0L }, name = n, mid = a.mid?.takeIf { it.isNotBlank() })
            },
            album = album?.let {
                AlbumItem(
                    id = it.id?.takeIf { v -> v > 0L },
                    name = it.name?.takeIf { v -> v.isNotEmpty() },
                    picUrl = it.picUrl?.takeIf { v -> v.isNotEmpty() },
                )
            },
            duration = duration.takeIf { it > 0L },
            source = srcKey,
            sourceId = sourceId?.takeIf { it.isNotBlank() },
            mediaId = mediaId?.takeIf { it.isNotBlank() },
            memberOnly = memberOnly,
        )
    }

    /** 旧的「只有身份」形状仍保留：v1 迁移测试与将来的轻量清单会用。 */
    private fun PlaylistTrack.toDto() = TrackDto(
        id = trackKey.id,
        source = trackKey.source.key,
        sourceId = trackKey.sourceId,
        mediaId = trackKey.mediaId,
        order = order,
        addedAt = addedAt,
    )

    /** `id <= 0` 的条目没有可用身份 ⇒ 丢弃（与 [PlaylistTrack.of] 同一条判据）。 */
    @Suppress("unused")
    private fun TrackDto.toDomain(): PlaylistTrack? {
        if (id <= 0L) return null
        val srcKey = source?.takeIf { it.isNotBlank() } ?: return null
        return PlaylistTrack(
            source = MusicSource.fromKey(srcKey),
            trackKey = TrackKey(
                source = MusicSource.fromKey(srcKey),
                id = id,
                sourceId = sourceId?.takeIf { it.isNotBlank() },
                mediaId = mediaId?.takeIf { it.isNotBlank() },
            ),
            order = order,
            addedAt = addedAt,
        )
    }
}
