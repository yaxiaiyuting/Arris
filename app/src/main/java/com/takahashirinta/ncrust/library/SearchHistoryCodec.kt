/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.4 · B：搜索历史的落盘编解码 + 音源字段迁移。**纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.library

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds

/**
 * `search_history` 里那三段 JSON 的**编解码契约**（v2.5.4 · B）。
 *
 * ## 这个文件修的是两个 bug，不是一个
 *
 * ### bug 1（任务书点名的）：只存 id，不存音源
 *
 * `HistoryItem` 原先只有 5 个字段，`addSong` 一次都没读 `song.source` / `song.sourceId`
 * / `song.mediaId`。读回来重建的 `SongItem` 三个字段全是 `null`，于是
 * `SongItem.musicSource`（读**字符串**口径）把一首 QQ 曲目认成 ncm —— 角标错、
 * 单曲页路由错、点下去必然取不到链。本版补上三个字段。
 *
 * ### bug 2（探针没发现，靠**真机取证**才看见的）：落盘字段名由 R8 决定
 *
 * 真机（S6 / SM-G9209，v2.5.2 的 release 包）上 `search_history.xml` 的实际内容是：
 *
 * ```json
 * [{"a":4611686018530183086,"b":"残酷な天使のテーゼ",
 *   "c":"https://y.qq.com/music/photo_new/T002R500x500M000000pmPam3gTtA5_1.jpg",
 *   "d":"高橋洋子","e":1790344822158}]
 * ```
 *
 * `a b c d e` 就是 `id title coverUrl subtitle timestamp` 被 R8 混淆后的字段名 ——
 * `app/build/outputs/mapping/release/mapping.txt` 里写得很清楚：
 *
 * ```text
 * com.takahashirinta.ncrust.library.SearchHistoryManager$HistoryItem -> G4.k:
 *     long id -> a
 *     java.lang.String title -> b
 *     java.lang.String coverUrl -> c
 *     java.lang.String subtitle -> d
 *     long timestamp -> e
 * ```
 *
 * 这与 `proguard-rules.pro` 里 v2.3.0 记下的那次事故是**同一类问题的第三个实例**
 * （前两个是 `local.**` 的本地歌单、`crosssource.**` 的匹配缓存）：
 * 持久化结构的字段名变成了「构建的副产物」，下一次混淆映射一变，老数据就一条都读不出来
 * —— 同一个 APK 内读写自洽所以**不崩**，用户的搜索历史只会**静默消失**。
 *
 * 本版的做法不是再加一条 `-keep`（那会**改掉全局混淆映射**，反倒可能让
 * `cache.**` 里 `ncrust_offline` 的 `tracks`（同样是单字母 key）在升级时读不出来），
 * 而是把契约**写进代码**：
 *
 * 1. **写**：只经 [EntryDto]，每个字段显式 `@SerializedName("<稳定名字>")`。
 *    `proguard-rules.pro` 已有全局规则
 *    `-keepclassmembers,allowobfuscation class * { @SerializedName <fields>; }`，
 *    Gson 读的是**注解里的字符串常量**，字段本身怎么改名都不影响落盘形状；
 * 2. **读**：先认稳定名字；认不出来就按**已知的旧单字母形状**读（[LEGACY_KEYS]，
 *    由 mapping.txt 与真机 XML 双重取证）；再认不出来就按**声明顺序**兜底
 *    （Gson 只省略 null 字段、不重排，所以前两个 key 恒为 id/title、最后一个恒为
 *    timestamp，中间两个用「像不像 URL」区分）。
 *
 * 三条读法都由 [SearchHistoryCodecTest] 用真实样本钉住。
 *
 * ## 为什么读路径手写而不用 Gson 反射整个 `List<HistoryItem>`
 *
 * 因为要同时认三种 key 形状，而 Gson 的反射适配器只认一种（要么字段名、要么注解名）。
 * 手写解析还顺带把「坏 JSON 只丢坏的那一条」变成可能 —— 旧的 `catch { mutableListOf() }`
 * 是**整段丢光**。
 *
 * ## 与 `LyricsCache` 迁移策略的关系（`AGENTS.md`「加字段 = 加迁移逻辑 = 加单测」）
 *
 * 规则 1（新字段可空 + 有默认值）在这里**不适用**：本文件不靠 Gson 反射构造
 * `HistoryItem`，缺字段时由 [decodeEntry] 显式补默认值，不存在「Unsafe 反序列化
 * 绕过构造函数」那条路径。规则 2（判「老条目」只看字段缺失）**适用且已遵守**：
 * `source == null` 才是老条目；空串是「读不懂的未知值」，交给 `MusicSource.fromKey`。
 * 规则 3（缺字段自愈）**有意不做**：见 [SearchHistoryMigration] 的说明 ——
 * 把推断结果写回去会把「这条记录不完整」这个事实抹掉。
 */
object SearchHistoryCodec {

    /**
     * 落盘 schema 版本。
     *
     * - **v1**：5 个字段（`id`/`title`/`coverUrl`/`subtitle`/`timestamp`），
     *   落盘 key 由 R8 决定（真机上是 `a`~`e`）；
     * - **v2**（当前）：v1 + `source` / `sourceId` / `mediaId`，key 由 [EntryDto] 的
     *   `@SerializedName` 固定成字段本名。
     *
     * **版本号不落盘**：它是数组而不是对象（`[{…},{…}]`），塞版本号要么改形状
     * （老版本读到就整段丢），要么多一个哨兵条目（会被当成一条历史）。
     * 判定老/新只靠**字段是否存在**，与 `LyricsCache` 的口径一致。
     */
    const val SCHEMA_VERSION = 2

    /**
     * v1 那 5 个字段在真机上的**实际落盘 key**。
     *
     * 取证两处，缺一不可：
     * 1. `app/build/outputs/mapping/release/mapping.txt`（v2.5.3 release 的混淆映射）
     *    —— `id→a, title→b, coverUrl→c, subtitle→d, timestamp→e`；
     * 2. S6 真机 `/data/data/com.takahashirinta.ncrust/shared_prefs/search_history.xml`
     *    —— 内容与上面的映射逐字对得上。
     */
    private val LEGACY_KEYS = listOf("a", "b", "c", "d", "e")

    private val STABLE_KEYS = listOf("id", "title", "coverUrl", "subtitle", "timestamp")

    private val gson = Gson()

    /**
     * 落盘形状。**每个字段一个显式名字**，理由见类文档的 bug 2。
     * 全部可空：缺字段时 [decodeEntry] 自己按位置/缺省补，不依赖 Gson 的 Unsafe 构造。
     */
    internal data class EntryDto(
        @SerializedName("id") val id: Long? = null,
        @SerializedName("title") val title: String? = null,
        @SerializedName("coverUrl") val coverUrl: String? = null,
        @SerializedName("subtitle") val subtitle: String? = null,
        @SerializedName("timestamp") val timestamp: Long? = null,
        @SerializedName("source") val source: String? = null,
        @SerializedName("sourceId") val sourceId: String? = null,
        @SerializedName("mediaId") val mediaId: String? = null,
    )

    // ---------------------------------------------------------------- 编码 ----

    /** 条目列表 → JSON 数组串。null 字段照旧整条 key 不写（与 v1 形状保持同构）。 */
    fun encode(items: List<SearchHistoryManager.HistoryItem>): String =
        gson.toJson(items.map { it.toDto() })

    private fun SearchHistoryManager.HistoryItem.toDto() = EntryDto(
        id = id,
        title = title,
        coverUrl = coverUrl,
        subtitle = subtitle,
        timestamp = timestamp,
        source = source,
        sourceId = sourceId,
        mediaId = mediaId,
    )

    // ---------------------------------------------------------------- 解码 ----

    /**
     * JSON 数组串 → 条目列表。
     *
     * 与旧实现的区别：坏数据**逐条**丢弃，不是整段丢光（旧代码是
     * `catch (_: Exception) { mutableListOf() }`，一个字节的损坏 = 用户整段历史消失）。
     * 顶层不是数组、或根本不是 JSON时才返回空列表。
     */
    fun decode(json: String?): List<SearchHistoryManager.HistoryItem> {
        if (json.isNullOrEmpty()) return emptyList()
        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (_: Exception) {
            return emptyList()
        }
        if (!root.isJsonArray) return emptyList()
        val out = ArrayList<SearchHistoryManager.HistoryItem>(root.asJsonArray.size())
        for (element in root.asJsonArray) {
            val obj = element as? JsonObject ?: continue
            decodeEntry(obj)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 单条解码。识别顺序：**稳定名字 → 旧单字母 → 声明顺序**。
     *
     * `id` 缺失或非正的条目直接丢弃：它是这张表的主键，补不出合理的默认值
     * （`OfflineLibrary` 对 `songId <= 0` 的条目也是同一处理）。
     */
    internal fun decodeEntry(obj: JsonObject): SearchHistoryManager.HistoryItem? {
        if (obj.has("id")) {
            val id = obj.longOf("id") ?: return null
            if (id <= 0L) return null
            return SearchHistoryManager.HistoryItem(
                id = id,
                title = obj.stringOf("title").orEmpty(),
                coverUrl = obj.stringOf("coverUrl"),
                subtitle = obj.stringOf("subtitle"),
                timestamp = obj.longOf("timestamp") ?: 0L,
                source = obj.stringOf("source"),
                sourceId = obj.stringOf("sourceId"),
                mediaId = obj.stringOf("mediaId"),
            )
        }
        // v1 形状：五个单字母 key，顺序固定（mapping.txt + 真机 XML）。
        if (obj.keySet().all { it in LEGACY_KEYS } && obj.keySet().isNotEmpty()) {
            val id = obj.longOf("a") ?: return null
            if (id <= 0L) return null
            return SearchHistoryManager.HistoryItem(
                id = id,
                title = obj.stringOf("b").orEmpty(),
                coverUrl = obj.stringOf("c"),
                subtitle = obj.stringOf("d"),
                timestamp = obj.longOf("e") ?: 0L,
            )
        }
        return decodeByDeclarationOrder(obj)
    }

    /**
     * 兜底：**未知 key 集合**时按声明顺序读。
     *
     * 之所以敢按顺序读，是因为 Gson 只做两件事：按字段声明顺序写、跳过 null 字段。
     * 于是：
     *  - 第 1 个恒为 `id`（非空 `Long`）、第 2 个恒为 `title`（非空 `String`）；
     *  - 最后 1 个恒为 `timestamp`（有默认值 `System.currentTimeMillis()`，非空）；
     *  - 中间剩下的字符串里，`coverUrl` 是 URL、`subtitle` 是艺人名 ——
     *    用「像不像 URL」区分（[looksLikeUrl]）。
     *
     * 这条路径**只在 R8 换了字母表时才被走到**（真机取证是 a~e）。它存在的意义是
     * 「宁可少一个封面，也不要把用户整段历史丢光」。
     */
    private fun decodeByDeclarationOrder(obj: JsonObject): SearchHistoryManager.HistoryItem? {
        val values = obj.entrySet().map { it.value }.filter { !it.isJsonNull }
        if (values.size < 3) return null
        val id = values.first().takeIf { it.isJsonPrimitive }?.asLong ?: return null
        if (id <= 0L) return null
        val title = values.getOrNull(1)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val ts = values.last().takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        val middle = values.subList(2, values.size - 1)
            .mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
        var cover: String? = null
        var subtitle: String? = null
        for (text in middle) {
            if (cover == null && looksLikeUrl(text)) cover = text else if (subtitle == null) subtitle = text
        }
        return SearchHistoryManager.HistoryItem(
            id = id,
            title = title,
            coverUrl = cover,
            subtitle = subtitle,
            timestamp = ts,
        )
    }

    /** 封面 URL 的判据：只用来在**未知 key 集合**下区分封面与艺人名，不参与任何业务判定。 */
    private fun looksLikeUrl(text: String): Boolean =
        text.startsWith("http://") || text.startsWith("https://") || text.startsWith("//")

    /**
     * `timestamp` 的**缺失补 0**（而不是补「现在」）。
     *
     * 补「现在」会让一条读不懂的老记录变成「刚刚搜过」，并因此**顶掉一条真的新记录**
     * （`add` 是头插 + 截断到 10 条）。0 的语义是「很久以前」，会被 14 天 TTL
     * 立刻剪掉 —— 这正是对一条无法解释时间戳的记录最保守的处理。
     */
    private fun JsonObject.longOf(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asLong }.getOrNull()
        }

    private fun JsonObject.stringOf(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asString }.getOrNull()
        }?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------- 给单测的只读视图 ----

    /** 落盘 key 的**唯一事实来源**（供单测断言「字段名不再由 R8 决定」）。 */
    internal fun stableKeys(): List<String> = STABLE_KEYS

    /** v1 的落盘 key，供单测构造老样本。 */
    internal fun legacyKeys(): List<String> = LEGACY_KEYS

    /** 这张表里 QQ 曲目的落盘 key 集合（[MusicSource.QQMUSIC] 的 `key`）。 */
    internal fun qqSourceKey(): String = MusicSource.QQMUSIC.key

    /** 供单测断言「bit62 推断」与生产代码用的是同一条规则。 */
    internal fun isQqId(id: Long): Boolean = SourceIds.isQqId(id)
}
