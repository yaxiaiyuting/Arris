/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · A：收藏专辑表（`ncrust_library` / `saved_albums`）的落盘编解码。
 * **纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.library

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * `ncrust_library` / `saved_albums` 的**编解码契约**（v2.5.5 · A）。
 *
 * ## 这个文件修的是什么
 *
 * v2.5.5 的 R8 全仓扫描（`docs/verification/v2.5.5/probe-r8-keys.md`）发现的第三个实例：
 *
 * ```text
 * com.takahashirinta.ncrust.library.AlbumInfo -> G4.a:
 *     long albumId -> a
 *     java.lang.String name -> b
 *     java.lang.String picUrl -> c
 *     java.lang.String artist -> d
 *     int songCount -> e
 * ```
 *
 * 落盘形状因此是
 * `[{"a":123,"b":"专辑名","c":"https://…","d":"艺人","e":12}]`。
 *
 * **它最容易被误判成安全**：同一个文件里的 `SongItem` 在 `network.**` 下被 `-keep` 保护，
 * 而 `AlbumInfo` 是 `library/` 包里的**顶层** `@Immutable data class` —— 不在任何
 * `-keep` 覆盖的包里（`network.**` / `lyric.**` / `playlist.**` / `local.**` / `crosssource.**`
 * 都不含 `library.AlbumInfo`）。上一版的探针把 `library.**` 整体记为「已由
 * SearchHistoryCodec 修好」，于是它漏了过去。
 *
 * 后果按严重度排：
 * - `albumId` 读成 0 ⇒ `LibraryScreen` 的 `key = { it.albumId }` 出现**重复 key**
 *   （`LazyColumn` 会直接抛异常）；
 * - 字母表位移 ⇒ 专辑名与艺人名互换、封面变成艺人名；
 * - 整段读不出来 ⇒ 用户收藏的专辑**静默清空**。
 *
 * ## 三条读法（与 `SearchHistoryCodec` / `OfflineTrackCodec` 同一手法）
 *
 * 1. **稳定名字**（`albumId` / `name` / `picUrl` / `artist` / `songCount`）；
 * 2. **已知旧单字母**（`a`~`e`，由 `mapping.txt` 取证）；
 * 3. **声明顺序兜底**：未知 key 集合时按 Gson 的写出顺序读 ——
 *    5 个字段**全部非空**（没有默认值，`AlbumInfo` 的构造器要求全传），
 *    所以不存在「null 被跳过导致错位」的风险，按位置读是安全的。
 *
 * ## 坏数据逐条丢弃
 *
 * 旧实现是 `runCatching { gson.fromJson(...) }.getOrDefault(emptyList())` ——
 * 一个字节坏了，**用户收藏的整张专辑表消失**。新实现逐条解析，坏的那条跳过。
 */
internal object SavedAlbumCodec {

    /**
     * 落盘 schema 版本。v1 = R8 决定 key；v2 = 显式名字；**v3 = 新增 `albumMid`**。
     * 版本号不落盘 —— 判「老条目」只看**字段缺失**（见 [AlbumDto.albumMid] 的 KDoc）。
     */
    const val SCHEMA_VERSION = 3

    /**
     * v1 的落盘 key（`mapping.txt` 取证）。
     *
     * ⚠️ v2.6.2 新增 `albumMid` 时**没有**往这里补第六个字母：v1 的这个结构**只写过
     * 五个字段**，第六个字母在任何真机上都不存在。凭空补一个 `f` 等于**发明**一条
     * 没有取证支撑的映射 —— 那比"读不出来"更糟（它会安静地把某个别的字段读成专辑身份）。
     * 所以 v1 形状读出来的 `albumMid` 恒为 `null`，语义正是「身份不可信」。
     */
    private val LEGACY_KEYS = listOf("a", "b", "c", "d", "e")

    private val STABLE_KEYS = listOf("albumId", "name", "picUrl", "artist", "songCount", "albumMid")

    private val gson = Gson()

    /**
     * 落盘形状。**每个字段一个显式名字。**
     *
     * `AlbumInfo` 本体是 UI 模型（`@Immutable`，被 `LibraryScreen` / `AlbumDetailScreen` 用），
     * 与 `SearchHistoryManager.HistoryItem` 一样**不加持久化注解** ——
     * 契约留在这一层，UI 模型保持干净。代价是下面这份 `toDto`/`fromDto` 必须手写。
     *
     * ## `albumMid`（v2.6.2 · P0）
     *
     * 音源内的**字符串**身份：qm 的 `albumMID`（`000MkMni19ClKG`），ncm 恒为 `null`
     * （它的十进制 `albumId` 就是身份）。加它的理由与 `AlbumItem.mid` 完全相同 ——
     * 这张表里的专辑将来要被「转到专辑」直接打开，而**数字 id 不是跨源可用的身份**。
     *
     * **可空 + 默认值**是硬要求：Gson 走 Unsafe 反序列化、不调用构造函数，
     * v2.6.2 之前落盘的每一条都没有这个 key，读出来必须是 `null`。
     * `null` 的语义是「**身份不可信**」（老数据，或确实没有字符串身份），
     * **不是**「不需要身份」—— 判据只看字段缺失，不看空串
     * （AGENTS.md v1.9.3 规则 2：空串往往是"服务端确实没有"的权威结论）。
     */
    internal data class AlbumDto(
        @SerializedName("albumId") val albumId: Long? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("picUrl") val picUrl: String? = null,
        @SerializedName("artist") val artist: String? = null,
        @SerializedName("songCount") val songCount: Int? = null,
        @SerializedName("albumMid") val albumMid: String? = null,
    )

    // ---------------------------------------------------------------- 编码 ----

    /** 专辑列表 → JSON 数组串。 */
    fun encode(albums: List<AlbumInfo>): String = gson.toJson(albums.map { it.toDto() })

    private fun AlbumInfo.toDto() = AlbumDto(
        albumId = albumId,
        name = name,
        picUrl = picUrl,
        artist = artist,
        songCount = songCount,
        albumMid = mid,
    )

    // ---------------------------------------------------------------- 解码 ----

    /**
     * JSON 数组串 → 专辑列表。坏条目**逐条**丢弃。
     * 顶层不是数组、或根本不是 JSON 时才返回空列表。
     */
    fun decode(json: String?): List<AlbumInfo> {
        if (json.isNullOrEmpty()) return emptyList()
        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (_: Exception) {
            return emptyList()
        }
        if (!root.isJsonArray) return emptyList()
        val out = ArrayList<AlbumInfo>(root.asJsonArray.size())
        for (element in root.asJsonArray) {
            val obj = element as? JsonObject ?: continue
            decodeEntry(obj)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 单条解码：稳定名字 → 已知旧单字母 → 声明顺序。
     *
     * `albumId` 缺失或非正的条目**丢弃**：它是 `LibraryScreen` 的 LazyColumn key，
     * 一个 0 会让同一个 key 出现两次（Compose 直接抛异常），
     * 而这里恰好是「宁可少一张专辑，也不能让收藏页崩」。
     *
     * ## v2.6.2 的三条读法（`albumMid` 这一维）
     *
     * | 落盘形状 | `albumMid` 读成 | 语义 |
     * |---|---|---|
     * | 稳定名字且**有** `albumMid` | 该值 | 身份可信 |
     * | 稳定名字但**没有** `albumMid`（v2.6.2 之前写的） | `null` | **身份不可信** |
     * | v1 单字母 `a`~`e` | `null` | **身份不可信**（那个结构从没写过第六个字段） |
     * | 未知 key 集合、6 个非空值 | 第 6 个值 | 按声明顺序读（`albumMid` 是最后一个声明的） |
     * | 未知 key 集合、5 个非空值 | `null` | **身份不可信**（没有第 6 个可读） |
     *
     * 全程**不猜**：任何"字段不在"的情形都落成 `null`，由消费方按「身份不可信」处置
     * （`AlbumInfo.identityTrusted == false`），绝不去推断一个 albumMid 出来。
     */
    internal fun decodeEntry(obj: JsonObject): AlbumInfo? {
        if (obj.has("albumId")) {
            val id = obj.longOf("albumId") ?: return null
            if (id <= 0L) return null
            return AlbumInfo(
                albumId = id,
                name = obj.stringOf("name").orEmpty(),
                picUrl = obj.stringOf("picUrl").orEmpty(),
                artist = obj.stringOf("artist").orEmpty(),
                songCount = obj.intOf("songCount") ?: 0,
                // 缺失即 null（老条目）—— **不看空串**，只有非空串才算身份。
                mid = obj.stringOf("albumMid")?.takeIf { it.isNotBlank() },
            )
        }
        if (obj.keySet().isNotEmpty() && obj.keySet().all { it in LEGACY_KEYS }) {
            val id = obj.longOf("a") ?: return null
            if (id <= 0L) return null
            return AlbumInfo(
                albumId = id,
                name = obj.stringOf("b").orEmpty(),
                picUrl = obj.stringOf("c").orEmpty(),
                artist = obj.stringOf("d").orEmpty(),
                songCount = obj.intOf("e") ?: 0,
                // v1 结构只写过 5 个字段 ⇒ 这里永远是「身份不可信」，这是**结论**不是兜底。
                mid = null,
            )
        }
        return fromDeclarationOrder(obj)
    }

    /**
     * 兜底：**未知 key 集合**时按声明顺序读。
     *
     * 前 5 个字段全部非空（`AlbumInfo` 的构造器要求前 5 个全传），Gson 一定会按声明顺序
     * 写满前 5 个，所以位置读在这里是安全的 —— 这是与 `OfflineTrack`（有可空字段、会被跳过）
     * 的关键区别，也是本兜底**不需要**「像不像 URL」那类启发式的原因。
     *
     * v2.6.2 起 `albumMid` 是**第 6 个、可空**：Gson 默认跳过 null，所以
     * 「5 个值」与「6 个值」都是合法形状，前者 ⇒ `albumMid = null`（身份不可信）。
     * 这里**不去**按"像不像 base62"猜第 5 个值是不是 mid —— 那正是铁律 14 禁止的猜。
     */
    private fun fromDeclarationOrder(obj: JsonObject): AlbumInfo? {
        val values = obj.entrySet().map { it.value }.filter { !it.isJsonNull }
        if (values.size < 5) return null
        val id = values[0].takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asLong }.getOrNull()
        } ?: return null
        if (id <= 0L) return null
        fun str(i: Int) = values[i].takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asString }.getOrNull()
        }.orEmpty()
        val count = values[4].takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asInt }.getOrNull()
        } ?: 0
        val mid = values.getOrNull(5)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asString }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        return AlbumInfo(
            albumId = id,
            name = str(1),
            picUrl = str(2),
            artist = str(3),
            songCount = count,
            mid = mid,
        )
    }

    private fun JsonObject.longOf(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asLong }.getOrNull()
        }

    private fun JsonObject.intOf(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asInt }.getOrNull()
        }

    private fun JsonObject.stringOf(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asString }.getOrNull()
        }

    // ------------------------------------------------------- 给单测的只读视图 ----

    internal fun stableKeys(): List<String> = STABLE_KEYS

    internal fun legacyKeys(): List<String> = LEGACY_KEYS
}
