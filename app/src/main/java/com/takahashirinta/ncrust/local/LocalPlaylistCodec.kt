/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · B：本地歌单的**编解码与结构演进迁移**。纯逻辑，无 Android 依赖，JVM 可单测。
 */

package com.takahashirinta.ncrust.local

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.TrackKey

/**
 * 本地歌单的落盘编解码 + schema 迁移（v2.3.0 · B）。
 *
 * ## 骨架照抄 `PlaylistCacheCodec`（v2.2.0 已经把「加字段 = 加迁移逻辑 = 加单测」做成机制）
 *
 * 1. **落盘一律走 DTO**（本文件里的 `*Dto`），字段**全部可空 + 有默认值** ——
 *    Gson 走 Unsafe 反序列化、不调用构造函数，老 JSON 里缺的 key 在 Kotlin 侧就是 `null`；
 * 2. 领域模型（[LocalPlaylist] / [LocalPlaylistTrack]）**只由 DTO 显式构造**，
 *    构造时对「必填字段缺失」返回 null 并丢弃该条目 —— 缺字段 ≠ 空值；
 * 3. DTO 与领域模型**不共享类**，所以将来给领域模型加字段时，
 *    「忘了加迁移」会表现为**编译错误**（DTO 里没有那个字段），而不是运行时的静默 null；
 * 4. 版本号显式落盘（[SCHEMA_VERSION]），未知/更高的版本**拒绝解释**而不是猜。
 *
 * ## 与 v2.2.0 的关键差异：`tombstoned` 缺字段补 `false`，**不丢弃**
 *
 * v2.2.0 对缺 `ownerId` 的老条目选择**丢弃**，因为「补上当前账号」会把数据断言成属于某个账号，
 * 那很危险。**本文件不能照抄这个决定**，因为 `tombstoned` 的两个可能默认值都有害：
 *
 * | 缺失时补 | 后果 |
 * |---|---|
 * | `true` | 用户**从没删过**的歌全被当成「已删」⇒ 下一次同步整张歌单一首都进不来 |
 * | 丢弃条目 | 用户手动加的歌与已同步的歌**全部消失**（静默数据丢失） |
 *
 * 正确的一侧是 **`false`**：它的语义是「没有删除记录」= 没删过。
 * 这是唯一不丢数据、也不误判删除的选择。`LocalPlaylistCodecTest` 有专门的
 * v0 → v1 迁移用例钉住它（含「补 true 会怎样」的反向断言）。
 *
 * ## 为什么 v1 就是当前版本（没有历史包袱）
 *
 * 本地歌单是 **v2.3.0 才出现**的概念（`probe-local-playlist.md`：全仓此前只有一句注释
 * 说它「尚不存在」）。所以 v1 是第一个版本。**但迁移逻辑现在就写**，理由有两条：
 *
 * 1. `tombstoned` 的默认值语义是**结构性的**（见上），它必须在代码里被显式表达一次，
 *    否则下一个加字段的人会以为「可空 + 默认值」这句口诀就够了；
 * 2. 「老版本 JSON」在真机上**立刻**就会出现：用户在 v2.3.0 建了歌单、退回 v2.2.1、
 *    再升回来 —— 或者更现实地，内部测试包与发布包交替安装。
 */
object LocalPlaylistCodec {

    /**
     * 当前 schema 版本。
     *
     * - **v1**（当前）：`LocalPlaylistDto` + `LocalTrackDto`（含 `tombstoned`）。
     * - 被识别为「v0」的输入 = **没有任何版本号**的 JSON（手写 / 早期内部包）。
     *   解析时按「所有可选字段缺失」处理，`tombstoned` 补 `false`。
     */
    const val SCHEMA_VERSION = 1

    private val gson = Gson()

    /**
     * 编译期捕获的集合类型。用它们解析数组**不依赖字段的泛型签名**，
     * 因此不受 R8 混淆/属性裁剪影响（proguard 已有
     * `-keep class * extends com.google.gson.reflect.TypeToken`）。
     */
    private val playlistListType = object : TypeToken<List<LocalPlaylistDto>>() {}.type
    private val trackListType = object : TypeToken<List<LocalTrackDto>>() {}.type
    private val songListType = object : TypeToken<SongItem>() {}.type

    // ------------------------------------------------------------------ DTO ----
    // 全部可空 + 默认值：Gson 不调用构造函数，缺字段时必须是 null 而不是抛异常。
    // 命名刻意与领域模型不同前缀，且**不共享类** —— 见类文档第 3 条。

    internal data class LocalPlaylistDto(
        val source: String? = null,
        val id: String? = null,
        val ownerId: String? = null,
        val name: String? = null,
        val createdAt: Long? = null,
        val updatedAt: Long? = null,
        val lastSyncedAt: Long? = null,
        val dirId: Long? = null,
    )

    internal data class LocalTrackDto(
        val source: String? = null,
        val trackId: Long? = null,
        val sourceId: String? = null,
        val mediaId: String? = null,
        val origin: String? = null,
        val addedAt: Long? = null,
        /** ★ 缺失时补 `false`（见类文档）。**故意声明成可空**，好让「缺失」与「显式 false」可分。 */
        val tombstoned: Boolean? = null,
        /** 渲染载荷。整首 [SongItem] 的 JSON；解析失败按「没有载荷」处理，**不丢条目**。 */
        val song: String? = null,
    )

    /** 一个歌单的落盘信封：曲目 + 版本号。 */
    internal data class PlaylistEnvelopeDto(
        val schemaVersion: Int? = null,
        val tracks: List<LocalTrackDto>? = null,
    )

    // -------------------------------------------------------------- 编解码 ----

    /** 歌单列表 → JSON。 */
    fun encodePlaylists(playlists: List<LocalPlaylist>): String =
        gson.toJson(playlists.map { it.toDto() }, playlistListType)

    /** JSON → 歌单列表。坏 JSON / 版本过高 ⇒ 空列表（**不抛**，调用方按「没有本地歌单」处理）。 */
    fun decodePlaylists(json: String?): List<LocalPlaylist> {
        if (json.isNullOrBlank()) return emptyList()
        val rows = runCatching {
            gson.fromJson<List<LocalPlaylistDto>>(json, playlistListType)
        }.getOrNull() ?: return emptyList()
        return rows.mapNotNull { it.toModel() }
    }

    /** 单个歌单的曲目 → JSON（带版本信封）。 */
    fun encodeTracks(tracks: List<LocalPlaylistTrack>): String =
        gson.toJson(
            PlaylistEnvelopeDto(
                schemaVersion = SCHEMA_VERSION,
                tracks = tracks.map { it.toDto() },
            ),
        )

    /**
     * JSON → 曲目列表。
     *
     * ## 「v0」是怎么被识别与迁掉的
     *
     * 两种历史形状都会被吃到：
     * - **裸数组** `[{...}, ...]`（没有任何版本号）；
     * - **信封** `{"schemaVersion":1,"tracks":[...]}`。
     *
     * 前者被当作 v0：`tombstoned` 等可选字段缺失 ⇒ 走 [LocalTrackDto.toModel] 里的默认值。
     * 两条路径最终都汇进同一个 `toModel()`，**迁移逻辑只有一处**。
     *
     * 更高的版本号（`> SCHEMA_VERSION`）⇒ **拒绝解释**并返回空列表：
     * 那是「降级安装」的场景，猜一个未来格式比什么都不显示更危险
     * （v2.2.0 的 `PlaylistCacheCodec` 对未知版本也是这个策略）。
     */
    fun decodeTracks(json: String?): List<LocalPlaylistTrack> {
        if (json.isNullOrBlank()) return emptyList()
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            // v0：裸数组
            val rows = runCatching {
                gson.fromJson<List<LocalTrackDto>>(trimmed, trackListType)
            }.getOrNull() ?: return emptyList()
            return rows.mapNotNull { it.toModel() }
        }
        val env = runCatching {
            gson.fromJson(trimmed, PlaylistEnvelopeDto::class.java)
        }.getOrNull() ?: return emptyList()
        val version = env.schemaVersion
        if (version != null && version > SCHEMA_VERSION) return emptyList()
        return env.tracks.orEmpty().mapNotNull { it.toModel() }
    }

    // ------------------------------------------------------------- 映射 ----

    private fun LocalPlaylist.toDto(): LocalPlaylistDto = LocalPlaylistDto(
        source = key.source.key,
        id = key.id,
        ownerId = key.ownerId,
        name = name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSyncedAt = lastSyncedAt,
        dirId = dirId,
    )

    private fun LocalPlaylistDto.toModel(): LocalPlaylist? {
        // 必填字段缺失 ⇒ 丢弃该条目（缺字段 ≠ 空值）。
        val src = source?.takeIf { it.isNotBlank() } ?: return null
        val pid = id?.takeIf { it.isNotBlank() } ?: return null
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return null
        val nm = name?.takeIf { it.isNotBlank() } ?: return null
        return LocalPlaylist(
            key = PlaylistKey(MusicSource.fromKey(src), pid, owner),
            name = nm,
            createdAt = createdAt ?: 0L,
            updatedAt = updatedAt ?: 0L,
            lastSyncedAt = lastSyncedAt ?: 0L,
            dirId = dirId ?: 0L,
        )
    }

    private fun LocalPlaylistTrack.toDto(): LocalTrackDto = LocalTrackDto(
        source = trackKey.source.key,
        trackId = trackKey.id,
        sourceId = trackKey.sourceId,
        mediaId = trackKey.mediaId,
        origin = origin.name,
        addedAt = addedAt,
        tombstoned = tombstoned,
        song = song?.let { runCatching { gson.toJson(it) }.getOrNull() },
    )

    private fun LocalTrackDto.toModel(): LocalPlaylistTrack? {
        val tid = trackId ?: return null
        if (tid <= 0L) return null
        // `source == null` 的解释与 [TrackKey.of] 完全一致：先看 id 有没有 QQ 的标志位，
        // 都没有才回落 ncm。**不猜成 ncm**（v2.1.5 的教训）。
        val key = TrackKey.of(source, tid, sourceId, mediaId)
        return LocalPlaylistTrack(
            trackKey = key,
            // 缺 origin ⇒ REMOTE 是保守的一侧：LOCAL 会在任何未来策略变化里被当作
            // 「用户亲手放的」而获得额外保护，把不知道来源的数据断言成那种保护是不诚实的。
            origin = runCatching {
                LocalTrackOrigin.valueOf(origin ?: LocalTrackOrigin.REMOTE.name)
            }.getOrDefault(LocalTrackOrigin.REMOTE),
            addedAt = addedAt ?: 0L,
            // ★ 缺 tombstoned ⇒ false（「没有删除记录」= 没删过）。见类文档的取值表。
            tombstoned = tombstoned ?: false,
            song = song?.takeIf { it.isNotBlank() }?.let {
                runCatching { gson.fromJson<SongItem>(it, songListType) }.getOrNull()
            },
        )
    }
}
