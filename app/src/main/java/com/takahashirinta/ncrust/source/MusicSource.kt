/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · A：多音源架构的地基。**纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.source

/**
 * 音乐音源（v2.1.0 · A）。
 *
 * 现在的取值只有 ncm 与 qm 两个，但架构上这里是**唯一**「音源」概念的定义处：
 * 队列里的每一首歌、每一个播放 URL 缓存条目、每一次歌词请求都必须能回答「你属于哪个音源」。
 *
 * ## 为什么 key 是字符串而不是 ordinal
 *
 * [key] 会被写进 SharedPreferences（队列持久化）、离线缓存的 URL query 参数、
 * 以及 MediaItem 的 mediaId。用 ordinal 的话，「以后在中间插一个音源」会让所有历史数据
 * 静默指错音源；字符串 key 是稳定且可读的，也让 JSON 里能直接看出来源。
 *
 * ## 未知 key 一律回落 [NETEASE]（不是抛异常）
 *
 * 这是**向后兼容的硬要求**：v2.1.0 之前持久化的队列 JSON 里没有 `source` 字段，
 * 离线缓存的 key 形状是 `song:<id>:<level>`（没有音源段）。读到一个不认识的 key 时
 * 唯一正确的解释就是「这是旧数据、来自 ncm」，抛异常等于让老用户开不了机。
 */
enum class MusicSource(val key: String) {
    NETEASE("netease"),
    QQMUSIC("qqmusic"),

    /**
     * v3.1.0 · B：B 站**音频区**（`au<auid>`）。
     *
     * ## 为什么 key 必须显式写死
     *
     * `key` 会进队列持久化 JSON、离线缓存 key、`MediaItem.mediaId`。v2.1.0 的注释已经写明了
     * 用 ordinal 的后果；这里再加一条**只属于 B 站**的理由：它是**默认关闭**的音源
     * （`bilibili_enabled`），一个老版本的 App 读到 `"bilibili"` 会经
     * [fromKey] 回落成 [DEFAULT] —— 也就是把一首 B 站曲目当成 ncm 的同号歌曲。
     * 这正是 [SourceIds.BILI_ID_FLAG] 存在的理由（id 自带的标志位不会丢）。
     */
    BILIBILI("bilibili"),
    ;

    /** 该音源的歌曲是否需要 `sourceId`（qm 取链必须带 songmid；B 站用 auid 就够）。 */
    val requiresSourceId: Boolean get() = this == QQMUSIC

    companion object {
        /** 旧数据、未知 key 的归属。 */
        val DEFAULT = NETEASE

        /**
         * 全部音源（顺序即 UI 顺序）。
         *
         * v3.1.0：**它不再等于「可登录的音源」** —— B 站没有账号体系接入（本轮不做登录），
         * 靠设置里的 `bilibili_enabled` 独立开关控制显隐。需要「另一个**可登录**音源」的调用方
         * （跨源匹配、无版权提示）请用 [loginSources]。
         */
        val selectable: List<MusicSource> = listOf(NETEASE, QQMUSIC, BILIBILI)

        /**
         * v3.1.0 · B：**可登录**的音源（ncm / qm）。
         *
         * 与 [selectable] 分开是必需的，不是洁癖：[otherThan] 的语义是
         * 「另一个**能拿到这首歌**的地方」，它被用在
         * 「此源无版权，可切另一源」的提示与跨源匹配上。把 B 站塞进去会让
         * ncm 的无版权提示变成「去 B 站试试」——而 B 站在默认关闭时根本不可用。
         *
         * `PlaybackGuardTest` 与 `PlaylistModelsTest` 都断言过
         * `otherThan(NETEASE) == QQMUSIC`，本版**不改这两条语义**。
         */
        val loginSources: List<MusicSource> = listOf(NETEASE, QQMUSIC)

        /** 解析字符串 key；null / 空串 / 不认识的值一律回落 [DEFAULT]。 */
        fun fromKey(key: String?): MusicSource =
            values().firstOrNull { it.key == key } ?: DEFAULT

        /**
         * v2.3.0 · C：**另一个**可登录音源（用于「此源无版权，可切另一源」的提示）。
         *
         * 只有一个可选音源时返回 null（调用方据此**不发提示** —— 不能提示用户去一个不存在的地方）。
         * 返回值来自 [loginSources]，所以它与 UI 上音源切换的顺序、以及将来新增第三个
         * **可登录**音源时的行为是同一份定义，不需要再改这里。
         *
         * ⚠️ v3.1.0 起它**不再**遍历 [selectable]：B 站不是可登录音源，
         * 也不该出现在「换一个源试试」的提示里（默认关闭时那条提示是死路）。
         */
        fun otherThan(source: MusicSource): MusicSource? =
            loginSources.firstOrNull { it != source }
    }
}

/**
 * 音源感知的标识符编解码（v2.1.0 · A）。**纯逻辑，JVM 可单测。**
 *
 * 三套标识符在本应用里各有用途，且**都必须能把「音源」带在字符串里**，
 * 否则两个平台的同名 id 会互相串台（qm 的数字 songid 与 ncm 的 songId 完全可能撞号）：
 *
 * | 用途 | 形状 | 谁在用 |
 * |---|---|---|
 * | [trackKey] | `netease:123` / `qqmusic:456` | 内部缓存 key、日志、诊断 |
 * | [mediaId] | `song:123`（ncm，与 v2.0.2 逐字节相同）/ `song:qqmusic:456` | media3 的 MediaItem、Android Auto browse tree |
 * | 离线缓存 key | `song:123:lossless`（ncm，形状不变）/ `song:q:456:lossless` | `OfflineKeys` |
 *
 * **ncm 一侧的形状一律保持 v2.0.2 原样**：已有用户的队列 JSON、离线缓存清单、
 * offload 缓存目录里的 URL 全部指向这些字符串，改形状 = 让老用户的离线缓存全部失效。
 */
object SourceIds {

    /** media3 `MediaItem.mediaId` 的前缀，v1.3.0 起就是它，不要改。 */
    const val MEDIA_ID_PREFIX = "song:"

    /** 离线缓存 key 里 QQ 音源的短标记（比 `qqmusic` 短，且不会与 `toLongOrNull()` 混淆）。 */
    const val OFFLINE_QQ_TAG = "q"

    /** `netease:123`。 */
    fun trackKey(source: MusicSource, id: Long): String = source.key + ":" + id

    /**
     * 解析 [trackKey]。**只接受「已知音源 key + 正整数 id」**，其余返回 null ——
     * 这个函数用来读外部输入（日志解析、诊断），宁可返回 null 也不要猜。
     */
    fun parseTrackKey(text: String?): Pair<MusicSource, Long>? {
        if (text.isNullOrEmpty()) return null
        val sep = text.indexOf(':')
        if (sep <= 0) return null
        val source = MusicSource.values().firstOrNull { it.key == text.substring(0, sep) } ?: return null
        val id = text.substring(sep + 1).toLongOrNull() ?: return null
        if (id <= 0L) return null
        return source to id
    }

    /**
     * ncm：`song:123`（**与 v2.0.2 逐字节相同**）；qm：`song:qqmusic:456`。
     * [songId] <= 0 时返回 null，让调用方自己决定怎么办（不要造一个假的 mediaId 出来）。
     */
    fun mediaId(source: MusicSource, id: Long): String? {
        if (id <= 0L) return null
        return if (source == MusicSource.NETEASE) {
            MEDIA_ID_PREFIX + id
        } else {
            MEDIA_ID_PREFIX + source.key + ":" + id
        }
    }

    /**
     * 解析 mediaId。兼容三种历史/新形状：
     * - `song:123` → (NETEASE, 123)：v2.1.0 之前写入 ExoPlayer 与通知栏的形状，**必须继续认**；
     * - `song:qqmusic:456` → (QQMUSIC, 456)；
     * - `song:<未知音源>:456` / `song:abc` → null（调用方按「无法解析」处理，不要猜成 ncm，
     *   因为用错音源取链会拿到 404 或别人的歌）。
     */
    fun parseMediaId(mediaId: String?): Pair<MusicSource, Long>? {
        if (mediaId.isNullOrEmpty() || !mediaId.startsWith(MEDIA_ID_PREFIX)) return null
        val rest = mediaId.removePrefix(MEDIA_ID_PREFIX)
        val sep = rest.indexOf(':')
        if (sep < 0) {
            val id = rest.toLongOrNull() ?: return null
            return if (id > 0L) MusicSource.NETEASE to id else null
        }
        val sourceKey = rest.substring(0, sep)
        val source = MusicSource.values().firstOrNull { it.key == sourceKey } ?: return null
        val id = rest.substring(sep + 1).toLongOrNull() ?: return null
        return if (id > 0L) source to id else null
    }

    /**
     * qm 曲目的**数字 id**（v2.1.0 · A）。
     *
     * ## 为什么必须把它和 ncm 的 id 隔离开
     *
     * 本应用有 10+ 处**以裸 `Long` 歌曲 id 作唯一键**的结构，且它们全都跨版本持久化：
     * 队列去重与持久化（`ncrust_playback_state`）、续播进度表、离线曲目索引、
     * **离线音频缓存 key**（`song:<id>:<level>`）、歌词缓存 key、收藏 id 列表、ExoPlayer 的 mediaId。
     * ncm 的 songId 与 qm 的 songid 各自独立编号，撞号是迟早的事 ——
     * 一旦撞上，后果按严重度排：**播出另一首歌的音频字节** > 串歌词 > 串续播进度 > 收藏错乱。
     *
     * ## 做法：把 QQ 的 id 抬到一个 ncm 永远到不了的正数区间
     *
     * `qqId = (1L shl 62) or rawId`。ncm 的 id 是十进制百万~十亿量级（远小于 2^40），
     * 永远不可能触到 2^62。于是：
     *
     * - **所有既有结构一个字节都不用改**，也不需要给它们做数据迁移
     *   （对照方案是在 OfflineKeys / LyricsCache / PlaybackStateManager / OfflineLibrary /
     *   LibraryManager 五个文件里各做一次「key 带音源 + 老 key 兼容读」，迁移面大得多，
     *   而本仓库 v1.9.3 的教训正是「加字段 = 加迁移逻辑 = 加单测」）；
     * - 撞号从「需要每个调用点都记得带音源」变成**结构上不可能**；
     * - id 仍是 `Long`，不引入新的类型与装箱。
     *
     * ## 反解必须无损
     *
     * [qqRawId] 用掩码取回真实 songid。真实 songid 是 9~10 位十进制数，
     * 不可能占到位 62（真占了就退回散列兜底，见下），所以掩码是无损的。
     */
    const val QQ_ID_FLAG: Long = 1L shl 62

    /**
     * v3.1.0 · B：B 站音频曲目的 id 标志位。
     *
     * ## 为什么 B 站**必须**有标志位，而 ncm/QQ 的隔离方案在这里不成立
     *
     * QQ 的隔离靠「ncm id 远小于 `2^62`」这条**值域**事实。B 站的 `auid` 破坏了这个前提：
     * 实测样本 `22760301`（约 2.3×10⁷）与 ncm 的 songId 是**同一个量级**
     * （ncm 百万~十亿）。也就是说 `TrackKey(BILIBILI, 22760301)` 与
     * `TrackKey(NETEASE, 22760301)` 在**裸 id** 上无法区分 —— 而本应用有 10+ 处以裸
     * `Long` 为键的跨版本持久化结构（队列、续播进度、离线缓存 key、歌词缓存 key……）。
     *
     * 所以 B 站走和 QQ 一样的做法：**把 id 抬到一个 ncm 永远到不了的区间**。
     * 用位 61（`1L shl 61`）而不是位 62，是为了让两个标志位互不干扰：
     * - 位 62 = qm（[QQ_ID_FLAG]）
     * - 位 61 = B 站（本常量）
     *
     * 判序必须是「先看位 62、再看位 61」（见 [sourceOfId]）：两个位同时置位的 id
     * 在本应用里不会被造出来（[qqId] / [biliId] 各自只置自己那一位），
     * 但读外部数据时先判谁是有定义的 —— 先判 QQ，与 v2.1.5 起的历史行为一致。
     */
    const val BILI_ID_FLAG: Long = 1L shl 61

    /** 是否为 [qqId] 造出来的 qm id。 */
    fun isQqId(id: Long): Boolean = (id and QQ_ID_FLAG) != 0L

    /** v3.1.0：是否为 [biliId] 造出来的 B 站 id。 */
    fun isBiliId(id: Long): Boolean = !isQqId(id) && (id and BILI_ID_FLAG) != 0L

    /**
     * v3.1.0：造一个 B 站的数字 id（`BILI_ID_FLAG or auid`）。
     *
     * @param rawAuid 服务端给的 `sid`（音频 auid）。**<= 0 或已占到任一标志位时返回 0**——
     *   与 QQ 那条路的散列兜底不同：B 站的 `auid` 是接口的**必需参数**（不是可替代的身份），
     *   造一个假 id 只会让取链必然 404。调用方拿到 0 应当把该条目整个丢掉。
     */
    fun biliId(rawAuid: Long): Long {
        if (rawAuid <= 0L || rawAuid >= BILI_ID_FLAG) return 0L
        return BILI_ID_FLAG or rawAuid
    }

    /** v3.1.0：反解 [biliId]；传入的不是 B 站 id 时返回 null（理由同 [qqRawId]）。 */
    fun biliRawId(id: Long): Long? = if (isBiliId(id)) id and (BILI_ID_FLAG - 1L) else null

    /**
     * 从**裸 id** 反推音源（v2.1.5）。纯逻辑，JVM 可单测。
     *
     * 只有一个判据能用：[QQ_ID_FLAG]。ncm 的 songId 是十进制百万~十亿量级
     * （远小于 `2^40`），**永远不可能**触到位 62 —— 所以「带标志位 ⇒ qm」
     * 是一个结构性的、不会误判的结论，而不是启发式。
     *
     * ## 为什么需要它
     *
     * 有些持久化路径只存得下裸 id（`PlaybackStateManager` 的 `song_id` 就是），
     * 那条路恢复出来的曲目**没有音源字符串**。若按「null ⇒ ncm」处理，
     * 一首 QQ 曲目会被拿去问 ncm 的歌词接口（id 是 `2^62` 量级，必然查不到），
     * 表现就是「冷启动恢复 QQ 歌曲时永远没有歌词」。
     * 有标志位在，这个二义性本来就不存在，不该丢掉这条信息。
     *
     * **注意它推不出 songmid**：QQ 取链与取词都需要 songmid，而那只能来自队列里的
     * [com.takahashirinta.ncrust.network.SongItem.sourceId]。所以本函数只负责
     * 「别问错平台」，不负责「能不能取到」。
     */
    fun sourceOfId(id: Long): MusicSource = when {
        isQqId(id) -> MusicSource.QQMUSIC
        // v3.1.0：位 61 = B 站。**必须排在最后**：判序与 v2.1.5 起的历史行为一致
        // （先 QQ），改判序会让「位 62 已置位」的历史数据被重新解释成 B 站。
        isBiliId(id) -> MusicSource.BILIBILI
        else -> MusicSource.NETEASE
    }

    /**
     * 造一个 qm 的数字 id。
     *
     * @param rawSongId 服务端给的 songid。**<= 0 或已经占到标志位时**改用 [sourceId] 的散列兜底
     *   （某些接口只给 songmid 不给 songid；兜底必须是确定性的，因为队列持久化、
     *   离线缓存、续播进度都拿它当 key）。
     */
    fun qqId(rawSongId: Long, sourceId: String): Long {
        val raw = if (rawSongId > 0L && rawSongId < QQ_ID_FLAG) rawSongId else hashSourceId(sourceId)
        return QQ_ID_FLAG or raw
    }

    /**
     * 反解 [qqId]；传入的不是 qm id 时返回 null。
     *
     * 返回 null 而不是「原样返回」是**有意的**：调用方拿到 null 说明「这不是一个 qm 的 id」，
     * 此时把 id 当 QQ 的 songid 用一定是个 bug，静默通过只会让它跑到取链那一步才炸。
     */
    fun qqRawId(id: Long): Long? = if (isQqId(id)) id and (QQ_ID_FLAG - 1L) else null

    /**
     * v2.5.4 · C：这个 QQ id 是不是「**服务端没给 songid** ⇒ 用 songmid 散列兜底」
     * 造出来的。
     *
     * ## 为什么需要它，以及为什么它必须是纯函数
     *
     * 埋点要回答「兜底路径的可播率」，就必须在**播放确认**那一刻判断
     * 「现在这首歌的 id 是兜底来的吗」。若把判断结果存成旁路标记
     * （last-write-wins 的字段），跨源切歌/预载接续会让它指错歌 ——
     * 这正是 v2.1.5 修掉「歌词串台」时的同一个形状。
     *
     * 所以判据**从 id 与 songmid 直接算出来**，不存任何状态：
     * 兜底 id 的定义就是 `QQ_ID_FLAG or hashSourceId(songmid)`
     * （见 [qqId]），把它重算一遍再比较即可。
     *
     * 误判面：只有当真实 songid 恰好等于 `hashSourceId(songmid)` 时才会误判，
     * 概率约 2⁻⁶² —— 比 64 位整数上的随机碰撞还小，且**不会造成任何行为变化**
     * （它只影响一个本地计数器）。
     *
     * @param sourceId QQ 的 songmid。缺失时返回 false（判不出来就不计）。
     */
    fun isSynthesizedQqId(id: Long, sourceId: String?): Boolean {
        if (!isQqId(id) || sourceId.isNullOrEmpty()) return false
        return qqRawId(id) == hashSourceId(sourceId)
    }

    /**
     * songmid 的确定性散列（FNV-1a 64 位），只取低 62 位以免撞上标志位。
     *
     * 不用 `String.hashCode()`：它只有 32 位，且碰撞在这里没有任何兜底手段。
     */
    private fun hashSourceId(sourceId: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (ch in sourceId) {
            hash = hash xor (ch.code.toLong() and 0xffL)
            hash *= 0x100000001b3L // FNV prime
        }
        return (hash and (QQ_ID_FLAG - 1L)).let { if (it == 0L) 1L else it }
    }
}
