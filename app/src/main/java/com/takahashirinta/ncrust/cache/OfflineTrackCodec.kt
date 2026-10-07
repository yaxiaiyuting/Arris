/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · A：离线曲目索引（`ncrust_offline` / `tracks`）的落盘编解码。
 * **纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.cache

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * `ncrust_offline` / `tracks` 的**编解码契约**（v2.5.5 · A）。
 *
 * ## 这个文件修的是什么
 *
 * v2.5.4 的探针把 `ncrust_offline/tracks` 列为「R8 单字母 key 的**第四个实例、唯一未修的一个**」，
 * 并在真机上取到了实际形状：
 *
 * ```json
 * [{"a":1959528822,"b":"…","e":…,"f":"exhigh","g":"song:1959528822:exhigh","i":1790344822158}]
 * ```
 *
 * 对照 `app/build/outputs/mapping/release/mapping.txt`：
 *
 * ```text
 * com.takahashirinta.ncrust.cache.OfflineTrack -> …:
 *     long songId -> a
 *     java.lang.String name -> b
 *     java.lang.String artist -> c
 *     java.lang.String albumPicUrl -> d
 *     java.lang.Long durationMs -> e
 *     java.lang.String level -> f
 *     java.lang.String cacheKey -> g
 *     java.lang.Long approxBytes -> h
 *     java.lang.Long completedAt -> i
 * ```
 *
 * 注意真机上**没有 `h`**：它（`approxBytes`）值为 null，Gson 整条 key 省掉。
 * 这不是「字母表跳过了 h」—— 按「有几个 key 就按顺序对几个字段」去解的人，
 * 从 `i` 开始就全错位了（`completedAt` 会被读成 `level` 那一档）。
 * 所以本文件的旧形状读取**按字母逐个查**，绝不按位置。
 *
 * 同 APK 内读写自洽 ⇒ 不崩；但下一次构建的混淆映射一变，老数据一条都读不出来
 * ⇒ 用户的离线曲目清单**静默消失**（而缓存字节还在，表现是「占用空间里的歌不见了」）。
 *
 * ## 为什么不用 `-keep class …cache.**`
 *
 * 加 keep 会**改掉全局混淆映射**：v2.5.4 已经明确把它列为**非首选**手段
 * （AGENTS.md v2.5.4 规则 1 第二条）。而且这里还有一条更硬的理由：
 * 已经落盘的 `a`~`i` 是**既有事实**，加 keep 只影响**将来**写出来的形状，
 * 对老数据一点用都没有 —— 迁移逻辑无论如何都要写。既然如此，
 * 把契约写进代码（`@SerializedName` + 多形状读）是唯一同时解决两端的做法。
 *
 * ## 三条读法（与 `SearchHistoryCodec` 同一手法）
 *
 * 1. **稳定名字**（`songId` / `name` / …）：本版之后写入的形状；
 * 2. **已知旧单字母**（[LEGACY_KEYS]，由 mapping.txt + 真机 XML 双重取证）：按字母查，
 *    **不按位置**；
 * 3. **声明顺序兜底**：未知 key 集合时（= 字母表又变了）按 Gson 的写出顺序读 ——
 *    只对「有哪些 key 都认不出来」这一种情况生效，宁可少几个元数据字段，
 *    也不要把用户整份离线清单丢光。
 *
 * 三条读法都由 `OfflineTrackCodecTest` 用真实样本钉住。
 *
 * ## 坏数据逐条丢弃（v2.5.4 规则 1 第四条）
 *
 * 旧实现是 `runCatching { … }` 包住整个 `fromJson`：**一个字节坏了，整份清单消失**。
 * 新实现逐条解析，解析不出来的那一条跳过。
 * （主键 `songId <= 0` 的条目仍然丢弃 —— 补不出合理的默认值，
 * 与 `OfflineLibrary.remove` 的 `songId <= 0` 守卫同源。）
 */
internal object OfflineTrackCodec {

    /**
     * 落盘 schema 版本。
     *
     * - **v1**：9 个字段，落盘 key 由 R8 决定（真机上是 `a`~`i`，缺 null 的那些）；
     * - **v2**（当前）：字段不变，key 由 [TrackDto] 的 `@SerializedName` 固定成字段本名。
     *
     * **版本号不落盘**：`tracks` 是一个数组（`[{…}]`），塞版本号要么改形状
     * （老版本读到就整段丢），要么多一个哨兵条目（会被当成一首歌）。
     * 判定老/新只靠**字段是否存在**，与 `SearchHistoryCodec` / `LyricsCache` 的口径一致。
     */
    const val SCHEMA_VERSION = 2

    /**
     * v1 那 9 个字段在真机上的**实际落盘 key**（顺序 = 字段声明顺序）。
     *
     * 取证两处：`mapping.txt`（v2.5.4 release）与
     * `docs/verification/v2.5.4/EVIDENCE.md` 记下的真机 `ncrust_offline.xml` 摘录。
     */
    private val LEGACY_KEYS = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i")

    private val STABLE_KEYS = listOf(
        "songId", "name", "artist", "albumPicUrl", "durationMs",
        "level", "cacheKey", "approxBytes", "completedAt",
    )

    private val gson = Gson()

    /**
     * 落盘形状。**每个字段一个显式名字。**
     *
     * 全部可空 + 有默认值：与 `OfflineTrack` 的既有约定一致
     * （Gson 走 Unsafe 反序列化、不调用构造函数），本文件的 [decodeEntry] 也自己补默认值。
     */
    internal data class TrackDto(
        @SerializedName("songId") val songId: Long? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("artist") val artist: String? = null,
        @SerializedName("albumPicUrl") val albumPicUrl: String? = null,
        @SerializedName("durationMs") val durationMs: Long? = null,
        @SerializedName("level") val level: String? = null,
        @SerializedName("cacheKey") val cacheKey: String? = null,
        @SerializedName("approxBytes") val approxBytes: Long? = null,
        @SerializedName("completedAt") val completedAt: Long? = null,
    )

    // ---------------------------------------------------------------- 编码 ----

    /** 条目列表 → JSON 数组串。null 字段整条 key 不写（与 v1 形状保持同构）。 */
    fun encode(tracks: List<OfflineTrack>): String = gson.toJson(tracks.map { it.toDto() })

    private fun OfflineTrack.toDto() = TrackDto(
        songId = songId,
        name = name,
        artist = artist,
        albumPicUrl = albumPicUrl,
        durationMs = durationMs,
        level = level,
        cacheKey = cacheKey,
        approxBytes = approxBytes,
        completedAt = completedAt,
    )

    // ---------------------------------------------------------------- 解码 ----

    /**
     * JSON 数组串 → 条目列表。坏条目**逐条**丢弃，不是整段丢光。
     * 顶层不是数组、或根本不是 JSON 时才返回空列表。
     */
    fun decode(json: String?): List<OfflineTrack> {
        if (json.isNullOrEmpty()) return emptyList()
        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (_: Exception) {
            return emptyList()
        }
        if (!root.isJsonArray) return emptyList()
        val out = ArrayList<OfflineTrack>(root.asJsonArray.size())
        for (element in root.asJsonArray) {
            val obj = element as? JsonObject ?: continue
            decodeEntry(obj)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 单条解码。识别顺序：**稳定名字 → 已知旧单字母 → 声明顺序**。
     *
     * `songId` 缺失或非正的条目直接丢弃（主键，补不出合理默认值）。
     */
    internal fun decodeEntry(obj: JsonObject): OfflineTrack? {
        if (obj.has("songId")) return fromStableNames(obj)
        if (obj.keySet().isNotEmpty() && obj.keySet().all { it in LEGACY_KEYS }) {
            return fromLegacyLetters(obj)
        }
        return fromDeclarationOrder(obj)
    }

    private fun fromStableNames(obj: JsonObject): OfflineTrack? {
        val songId = obj.longOf("songId") ?: return null
        if (songId <= 0L) return null
        return OfflineTrack(
            songId = songId,
            name = obj.stringOf("name"),
            artist = obj.stringOf("artist"),
            albumPicUrl = obj.stringOf("albumPicUrl"),
            durationMs = obj.longOf("durationMs"),
            level = obj.stringOf("level"),
            cacheKey = obj.stringOf("cacheKey"),
            approxBytes = obj.longOf("approxBytes"),
            completedAt = obj.longOf("completedAt"),
        )
    }

    /**
     * v1 形状：`a`~`i`，**按字母逐个查**。
     *
     * 绝不按位置读：真机上 `approxBytes`（`h`）为 null ⇒ 整条 key 被 Gson 省掉，
     * 位置读会让 `completedAt`（`i`）落到 `h` 的槽位上。
     */
    private fun fromLegacyLetters(obj: JsonObject): OfflineTrack? {
        val songId = obj.longOf("a") ?: return null
        if (songId <= 0L) return null
        return OfflineTrack(
            songId = songId,
            name = obj.stringOf("b"),
            artist = obj.stringOf("c"),
            albumPicUrl = obj.stringOf("d"),
            durationMs = obj.longOf("e"),
            level = obj.stringOf("f"),
            cacheKey = obj.stringOf("g"),
            approxBytes = obj.longOf("h"),
            completedAt = obj.longOf("i"),
        )
    }

    /**
     * 兜底：**未知 key 集合**时按声明顺序读。
     *
     * 之所以敢按顺序读，是因为 Gson 只做两件事：按字段声明顺序写、跳过 null 字段。
     * 于是第一个值恒为 `songId`（非空 `Long`）、最后一个非空值恒为
     * `completedAt`（`OfflineLibrary.record` 一定传 `now`）。
     * 中间的值按**类型 + 形状**认：
     *  - 数字 ⇒ `durationMs` / `approxBytes`（按出现顺序取第一个当 `durationMs`）；
     *  - 以 `song:` 开头的字符串 ⇒ `cacheKey`；
     *  - 其余字符串里第一个 URL ⇒ `albumPicUrl`，剩下按先后来的是 `name` / `artist` / `level`。
     *
     * 这条路径**只在 R8 换了字母表时才被走到**（真机取证是 `a`~`i`）。
     * 它存在的意义是「宁可少几个元数据字段，也不要把用户整份离线清单丢光」。
     */
    private fun fromDeclarationOrder(obj: JsonObject): OfflineTrack? {
        val values = obj.entrySet().map { it.value }.filter { !it.isJsonNull }
        if (values.size < 2) return null
        val songId = values.first().takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asLong }.getOrNull()
        } ?: return null
        if (songId <= 0L) return null

        val rest = values.drop(1).dropLast(0)
        // 最后一个值当作 completedAt（record 一定写它）。
        val body = rest.dropLast(1)
        val completedAt = rest.lastOrNull()?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asLong }.getOrNull()
        }

        var durationMs: Long? = null
        var approxBytes: Long? = null
        var cacheKey: String? = null
        var albumPicUrl: String? = null
        val strings = mutableListOf<String>()
        for (v in body) {
            if (!v.isJsonPrimitive) continue
            val p = v.asJsonPrimitive
            when {
                p.isNumber -> {
                    val n = runCatching { p.asLong }.getOrNull() ?: continue
                    if (durationMs == null) durationMs = n else if (approxBytes == null) approxBytes = n
                }
                p.isString -> {
                    val s = runCatching { p.asString }.getOrNull() ?: continue
                    when {
                        s.startsWith("song:") && cacheKey == null -> cacheKey = s
                        isUrl(s) && albumPicUrl == null -> albumPicUrl = s
                        else -> strings.add(s)
                    }
                }
            }
        }
        // 剩下的字符串按声明顺序：name, artist, level
        return OfflineTrack(
            songId = songId,
            name = strings.getOrNull(0),
            artist = strings.getOrNull(1),
            albumPicUrl = albumPicUrl,
            durationMs = durationMs,
            level = strings.getOrNull(2),
            cacheKey = cacheKey,
            approxBytes = approxBytes,
            completedAt = completedAt,
        )
    }

    /** 只用来在**未知 key 集合**下区分封面 URL 与其它字符串，不参与任何业务判定。 */
    private fun isUrl(text: String): Boolean =
        text.startsWith("http://") || text.startsWith("https://") || text.startsWith("//")

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
}
