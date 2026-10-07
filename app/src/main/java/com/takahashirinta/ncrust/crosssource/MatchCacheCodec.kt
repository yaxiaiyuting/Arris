/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · E：跨源匹配缓存的落盘 DTO 与迁移。**纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.crosssource

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey

/**
 * 跨源匹配缓存的编解码与迁移（v2.4.0 · E）。
 *
 * ## 四条约定（与 `PlaylistCacheCodec` / `LocalPlaylistCodec` 同源）
 *
 * 1. **落盘一律走 DTO**，字段**全部可空 + 有默认值** —— Gson 走 Unsafe 反序列化、
 *    不调用构造函数，老 JSON 里缺的 key 在 Kotlin 侧就是 `null`；
 * 2. 领域模型（[MatchConfidence] 等）**只由 DTO 显式构造**，「必填字段缺失」⇒ 丢弃该条目；
 * 3. DTO 与领域模型**不共享类** ⇒ 将来给领域模型加字段时，
 *    「忘了加迁移」会表现为**编译错误**，而不是运行时的静默 null；
 * 4. 版本号显式落盘，未知/更高的版本**拒绝解释**而不是猜。
 *
 * ## 本结构有**两个**版本号，第二个是这一版特有的
 *
 * | 版本号 | 什么时候 +1 | 不加会怎样 |
 * |---|---|---|
 * | [SCHEMA_VERSION] | 落盘**结构**变（加字段、改 key 名） | 老数据读不出来 |
 * | [ALGORITHM_VERSION] | 匹配**算法**变（改判据、改阈值、改归一化） | **缓存里全是按旧规则得出的结论**，而它们的 `confidence` 看起来仍然合法 —— 用户会看到「明明不一样的两条被合并了」，且**清缓存才能恢复** |
 *
 * 第二个版本号是任务书 3.4「匹配算法升级时，缓存结构加版本号 + 迁移逻辑 + 单测」
 * 的直接落点：算法一变，旧条目**整体作废**（按 miss 处理、下次重进页面重算），
 * 而不是「尽量解释」—— 解释一个用旧阈值算出来的 `EXACT` 是没有意义的。
 *
 * ## 为什么数组存成 JSON 字符串
 *
 * 与 `PlaylistCacheCodec` 同样的理由：`List<AliasDto>` 的元素类型只能靠字段的
 * **泛型签名 attribute** 得知，R8 会把它丢掉，于是 Gson 产出 `LinkedTreeMap` ⇒
 * release 包上 `ClassCastException`（debug 完全正常）。
 * 存成字符串之后用**编译期捕获**的 `TypeToken` 解析，与 keep 规则无关、结构上不会再犯。
 */
object MatchCacheCodec {

    /** 落盘结构版本。v1 = 首版（`aliases` + `confidence` + `reason` + `overlap`）。 */
    const val SCHEMA_VERSION = 1

    /**
     * 匹配算法版本。**改 `CrossSourceMatcher` 的任何判据/阈值/归一化都要 +1。**
     *
     * 历史：
     * - v1：首版（艺人 = 名称包含召回 + 专辑重合 3 张；专辑 = 名 + 艺人 + 曲目名 90%；单曲 = 名 + 艺人 + 时长 2s + 版本标记）
     */
    const val ALGORITHM_VERSION = 1

    /** 匹配结果的有效期。目录数据变化很慢，7 天足够；而账号态的东西**不进这个缓存**。 */
    const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val gson = Gson()
    private val aliasListType = object : TypeToken<List<AliasDto>>() {}.type

    /** 别名（跨源的另一侧身份）。字段名即落盘契约，**不要改名**（改名 = 老缓存全部失效）。 */
    internal data class AliasDto(
        val source: String? = null,
        val id: String? = null,
        val name: String? = null,
        val sourceId: String? = null,
        val mediaId: String? = null,
    )

    internal data class EnvelopeDto(
        val schemaVersion: Int? = null,
        val algorithmVersion: Int? = null,
        val savedAt: Long? = null,
        val confidence: String? = null,
        val reason: String? = null,
        val overlap: Int? = null,
        val aliasesJson: String? = null,
    )

    /** 读结果。`Dropped` 一定带得走原因，方便在日志里一眼区分「没有」与「读坏了」。 */
    internal sealed interface Read {
        data class Ok(
            val confidence: MatchConfidence,
            val reason: String,
            val overlap: Int,
            val aliases: List<AliasDto>,
            val savedAt: Long,
            val fresh: Boolean,
        ) : Read

        data class Dropped(val reason: DropReason) : Read
    }

    internal enum class DropReason {
        /** 没有这条记录（不是错误）。 */
        ABSENT,

        /** 空串 / 解析失败。 */
        MALFORMED,

        /** 结构版本比本版高（降级安装）。拒绝解释。 */
        FUTURE_SCHEMA,

        /** 算法版本与当前不一致 ⇒ 结论不可信，作废。 */
        STALE_ALGORITHM,

        /** 所有别名条目都解不出来。 */
        NO_ALIASES,
    }

    /**
     * 结构版本裁决。`null` 视为 **v0**（没有信封的裸载荷），按 v1 之前的形状处理。
     *
     * 之所以把 `null` 单列而不是直接当 v1：这一版之后如果给 [EnvelopeDto] 换形状，
     * 「信封都没写」与「信封写了但版本是 1」必须能分开，
     * 否则下一次迁移就得靠猜（v1.9.2 的 `romalrc` 就是这么踩的）。
     */
    internal enum class VersionVerdict { Bare, Current, Legacy, Future }

    internal fun checkVersion(version: Int?): VersionVerdict = when {
        version == null -> VersionVerdict.Bare
        version > SCHEMA_VERSION -> VersionVerdict.Future
        version < SCHEMA_VERSION -> VersionVerdict.Legacy
        else -> VersionVerdict.Current
    }

    /**
     * 新鲜度。TTL 左闭右开（`now - savedAt == ttlMs` 算过期），与
     * `PlaylistCacheCodec.isFresh` / `LyricsCache.isTtmlFresh` 口径一致。
     *
     * `savedAt <= 0`（缺失 / 非法）**一律算过期**，否则一个没有时间戳的老条目会永远新鲜。
     * 时钟回拨同样按过期处理（下次联网即自愈）。
     */
    internal fun isFresh(savedAt: Long, now: Long, ttlMs: Long = TTL_MS): Boolean {
        if (savedAt <= 0L) return false
        val age = now - savedAt
        if (age < 0L) return false
        return age < ttlMs
    }

    /**
     * 编码一条匹配结果。
     *
     * **`confidence < MEDIUM` 的结果不允许编码**（铁律 17：匹配失败不缓存）。
     * 这里用 `require` 而不是静默返回 null —— 那是调用方的逻辑错误，
     * 应该在单测里炸出来，而不是在真机上表现为「缓存里多了一条不该有的记录」。
     */
    internal fun encode(
        confidence: MatchConfidence,
        reason: String,
        aliases: List<AliasDto>,
        overlap: Int,
        savedAt: Long,
    ): String {
        require(confidence.mergeable) {
            "只缓存可合并的匹配结果（confidence=$confidence）；失败结果必须留给下次重试"
        }
        val env = EnvelopeDto(
            schemaVersion = SCHEMA_VERSION,
            algorithmVersion = ALGORITHM_VERSION,
            savedAt = savedAt,
            confidence = confidence.name,
            reason = reason,
            overlap = overlap,
            aliasesJson = gson.toJson(aliases),
        )
        return gson.toJson(env)
    }

    /**
     * 解码。**绝不抛异常** —— 缓存读坏的表现必须是「这次没命中」，不是崩溃。
     */
    internal fun decode(raw: String?, now: Long, ttlMs: Long = TTL_MS): Read {
        if (raw.isNullOrBlank()) return Read.Dropped(DropReason.ABSENT)
        val env = runCatching { gson.fromJson(raw, EnvelopeDto::class.java) }.getOrNull()
            ?: return Read.Dropped(DropReason.MALFORMED)

        when (checkVersion(env.schemaVersion)) {
            VersionVerdict.Future -> return Read.Dropped(DropReason.FUTURE_SCHEMA)
            // Bare（没有信封）与 Legacy 都按 v1 的形状解释：这一版只有 v1，
            // 两者都意味着「写它的人没有写版本号」，字段本身仍是同一套。
            VersionVerdict.Bare, VersionVerdict.Legacy, VersionVerdict.Current -> Unit
        }

        // 算法版本不符 ⇒ 结论作废。**注意这里不抛、也不返回 Ok(false)**：
        // 它是「这条缓存不存在」，调用方会去重算。
        if (env.algorithmVersion != ALGORITHM_VERSION) {
            return Read.Dropped(DropReason.STALE_ALGORITHM)
        }

        val confidence = env.confidence
            ?.let { name -> MatchConfidence.entries.firstOrNull { it.name == name } }
            ?: return Read.Dropped(DropReason.MALFORMED)
        if (!confidence.mergeable) return Read.Dropped(DropReason.MALFORMED)

        val aliases = env.aliasesJson
            ?.let { json -> runCatching { gson.fromJson<List<AliasDto>>(json, aliasListType) }.getOrNull() }
            .orEmpty()
            .filter { it.source != null && it.id != null }
        if (aliases.isEmpty()) return Read.Dropped(DropReason.NO_ALIASES)

        val savedAt = env.savedAt ?: 0L
        return Read.Ok(
            confidence = confidence,
            reason = env.reason.orEmpty(),
            overlap = env.overlap ?: 0,
            aliases = aliases,
            savedAt = savedAt,
            fresh = isFresh(savedAt, now, ttlMs),
        )
    }

    // ------------------------------------------------------------ DTO 转换 ----

    internal fun aliasOf(source: MusicSource, id: String, name: String): AliasDto =
        AliasDto(source = source.key, id = id, name = name)

    internal fun aliasOf(key: TrackKey): AliasDto = AliasDto(
        source = key.source.key,
        id = key.id.toString(),
        name = null,
        sourceId = key.sourceId,
        mediaId = key.mediaId,
    )

    internal fun artistAlias(dto: AliasDto): ArtistKey? {
        val source = MusicSource.values().firstOrNull { it.key == dto.source } ?: return null
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        return ArtistKey(source, id, dto.name.orEmpty())
    }

    internal fun albumAlias(dto: AliasDto): AlbumKey? {
        val source = MusicSource.values().firstOrNull { it.key == dto.source } ?: return null
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        return AlbumKey(source, id, dto.name.orEmpty())
    }

    internal fun trackAlias(dto: AliasDto): TrackKey? {
        val source = MusicSource.values().firstOrNull { it.key == dto.source } ?: return null
        val id = dto.id?.toLongOrNull() ?: return null
        if (id <= 0L) return null
        return TrackKey(source, id, dto.sourceId, dto.mediaId)
    }
}

/**
 * 缓存 key 的形状（v2.4.0 · E）。**纯逻辑，抽出来是为了让单测能钉住它。**
 *
 * 形状：`<kind>:<sourceKey>:<id>`。**音源必须在 key 里** ——
 * ncm 的 `6452` 与 QQ 的 `6452` 是两个完全不同的东西，
 * 少了音源段就会互相覆盖（这正是 v2.1.0 给 id 加标志位要解决的那类问题）。
 */
object MatchCacheKeys {

    const val PREFIX_ARTIST = "artist:"
    const val PREFIX_ALBUM = "album:"
    const val PREFIX_TRACK = "track:"

    fun artist(key: ArtistKey): String = PREFIX_ARTIST + key.tag

    fun album(key: AlbumKey): String = PREFIX_ALBUM + key.tag

    fun track(key: TrackKey): String = PREFIX_TRACK + key.tag

    /** 解析回 `(kind, sourceKey)`，用于 LRU 裁剪与诊断；形状不符返回 null。 */
    fun parse(key: String): Pair<String, String>? {
        val parts = key.split(':')
        if (parts.size != 3) return null
        return parts[0] to parts[1]
    }

    /** 全部缓存 key 的前缀（清理用）。 */
    val allPrefixes: List<String> get() = listOf(PREFIX_ARTIST, PREFIX_ALBUM, PREFIX_TRACK)
}
