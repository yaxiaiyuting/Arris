/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.0 · P0：收藏库（`ncrust_library` / `saved_songs`）的落盘编解码。
 * **纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.library

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.TrackKey

/**
 * `ncrust_library` / `saved_songs` 的**编解码契约**（v2.6.0 · P0）。
 *
 * ## 这个文件修的是什么
 *
 * v2.6.0 之前，`saved_songs` 里直接落一个 `List<SongItem>` —— **没有任何
 * 「这一条是怎么进来的」的信息**。于是「云端删掉的」与「用户手动加入库的」
 * 在同步时完全同形（都是「id 不在云端 likedIds 里」），同步按「云端为真源」
 * 把两者一起丢掉。用户看到的就是：**QQ 歌曲长按「加入库」→ 短暂出现 → 刷新后消失**。
 * 完整根因链见 `docs/verification/v2.6.0/probe-qq-import-loss.md`。
 *
 * 修法是给每一条加两个字段（`origin` / `tombstoned`），于是**必须**同时写迁移逻辑
 * （铁律 3：加字段 = 加迁移逻辑 = 加单测）。
 *
 * ## 三种落盘形状，读路径**全部要认**
 *
 * | # | 形状 | 出处 | 例子 |
 * |---|---|---|---|
 * | 1 | **v1 裸 `SongItem` 数组** | v2.6.0 之前的所有版本 | `[{"id":123,"name":"…","ar":[…]}]` |
 * | 2 | 稳定名字信封 | v2.6.0 起写出的形状 | `[{"trackKey":"netease:123","origin":"REMOTE",…}]` |
 * | 3 | 未知 key 集合 ⇒ 按声明顺序兜底 | 将来某次混淆映射变化 | —— |
 *
 * **形状 1 是这次迁移的真实对象**，不是假想的：任何从 v2.5.6 及更早升级上来的用户，
 * 磁盘上就是那个形状。它**没有** `origin` 字段，所以正确的解释只能是
 * 「这是同步来的」(`REMOTE`) —— 事实上 v2.6.0 之前也确实只可能是同步来的
 * （手动加入的条目活不过一次刷新，所以磁盘上留不下）。
 *
 * 这与 `SavedAlbumCodec` / `SearchHistoryCodec` / `OfflineTrackCodec` 是同一套手法，
 * 但多一条**外层形状判别**：那两个 codec 的数组元素形状只有一种，
 * 这里同一个 key 下先后存在过两种**完全不同的元素形状**。
 *
 * ## 坏数据逐条丢弃
 *
 * 与 v2.5.4/v2.5.5 的四条纪律一致：一个字节坏了只跳过那一条，
 * **不许** `runCatching { … }.getOrDefault(emptyList())` 让用户整份收藏消失。
 */
internal object SavedSongCodec {

    /** 落盘 schema 版本（v1 = 裸 SongItem 数组；v2 = 显式名字信封）。版本号不落盘。 */
    const val SCHEMA_VERSION = 2

    /** 信封字段的稳定名字。 */
    private val STABLE_KEYS = listOf("trackKey", "origin", "addedAt", "tombstoned", "song")

    /**
     * v2 信封的**已知旧单字母**。
     *
     * **本表为空是有意的、且是正确取值**：v2 信封在 v2.6.0 才第一次出现，
     * 而它**从第一版起**就带 `@SerializedName`（本文件的 DTO 就是第一版），
     * 所以不存在一份「被 R8 混淆过的 v2 信封」落盘数据。
     * `SavedAlbumCodec` 的 `a`~`e` 之所以存在，是因为那个 DTO 在加
     * `@SerializedName` **之前**已经被 R8 混淆着写过盘了。
     *
     * 保留这个常量（而不是删掉走兜底）是为了让「形状 2」这条路在代码里**显式存在**：
     * 将来若真的出现一份单字母信封，只需往这里填字母表，读路径不用改结构。
     */
    private val LEGACY_KEYS = emptyList<String>()

    /**
     * 形状判别的判据：一个 JSON 对象**可能是 v2 信封**当且仅当它含有
     * [STABLE_KEYS] 里的**任意一个** key。
     *
     * 为什么「任意一个」而不是「全部」：将来若再给信封加字段，老信封缺的
     * 只是那一个新 key —— 用「全部」判别会让老信封被误判成 v1 裸 SongItem，
     * 于是 `origin` / `tombstoned` 一起丢失、**tombstone 语义静默失效**
     * （用户删掉的歌会在下一次刷新时复活）。
     *
     * ⚠️ 这个函数**单独用不足以分流**：一个 key 集合完全未知的对象（形状 3）
     * 在这里返回 `false`，与 v1 无法区分。真正的分流判据是
     * [decodeEntry] 开头的「有没有 `id` + `name`」——
     * `looksLikeEnvelope` 只回答「是不是**已知**的信封形状」。
     */
    private fun looksLikeEnvelope(obj: JsonObject): Boolean =
        STABLE_KEYS.any { obj.has(it) }

    private val gson = Gson()

    /**
     * 落盘形状。**每个字段一个显式名字**（铁律 18）。
     *
     * `trackKey` 存的是 `SourceIds.trackKey` 的字符串（`netease:123` /
     * `qqmusic:456`）。它是 `song` 的**冗余**信息 —— 冗余是有意的：
     *
     * 1. 它是**身份**，而 `song.source` 是**载荷**里可以缺失的字符串
     *    （v2.5.4 的教训：老条目可能只剩裸 id）。身份单独落一份，
     *    读的时候就不必依赖 `song` 的完整性；
     * 2. 读路径在 `trackKey` 缺失/解析不出来时**回落到 `TrackKey.ofSong(song)`**
     *    （那里有 bit62 兜底），所以两条路都通向同一个答案。
     */
    internal data class SavedSongDto(
        @SerializedName("trackKey") val trackKey: String? = null,
        /** `REMOTE` / `LOCAL`。未知取值按 `REMOTE` 解释（见 [decodeEntry]）。 */
        @SerializedName("origin") val origin: String? = null,
        @SerializedName("addedAt") val addedAt: Long? = null,
        @SerializedName("tombstoned") val tombstoned: Boolean? = null,
        /**
         * 曲目本体。**这里直接复用 `network.SongItem`**（它逐个字段都有
         * `@SerializedName`，且 `network.**` 在 `proguard-rules.pro` 里有 keep）
         * —— 为它再抄一份 DTO 只会造出一个会漂移的影子结构。
         */
        @SerializedName("song") val song: SongItem? = null,
    )

    // ---------------------------------------------------------------- 编码 ----

    /** 条目列表 → JSON 数组串。 */
    fun encode(entries: List<SavedSongEntry>): String = gson.toJson(entries.map { it.toDto() })

    private fun SavedSongEntry.toDto() = SavedSongDto(
        trackKey = trackKey.tag,
        origin = origin.name,
        addedAt = addedAt,
        tombstoned = tombstoned,
        song = song,
    )

    // ---------------------------------------------------------------- 解码 ----

    /**
     * JSON 数组串 → 条目列表。坏条目**逐条**丢弃。
     * 顶层不是数组、或根本不是 JSON 时才返回空列表。
     */
    fun decode(json: String?): List<SavedSongEntry> {
        if (json.isNullOrEmpty()) return emptyList()
        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (_: Exception) {
            return emptyList()
        }
        if (!root.isJsonArray) return emptyList()
        val out = ArrayList<SavedSongEntry>(root.asJsonArray.size())
        for (element in root.asJsonArray) {
            val obj = element as? JsonObject ?: continue
            decodeEntry(obj)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 单条解码：**先判形状**，再走对应路径。
     *
     * - 信封 → 稳定名字 / 已知旧单字母 / 声明顺序；
     * - 裸 `SongItem` → 直接用 Gson 反序列化，`origin = REMOTE`、`tombstoned = false`、
     *   `addedAt = 0`（「不知道什么时候加的」，是一个诚实的取值）。
     *
     * 身份兜底顺序：`trackKey` 字符串 → `TrackKey.ofSong(song)`（带 bit62 回落）。
     * 两条都拿不到（`song` 缺失或 id 非法）⇒ **丢弃这一条**：
     * 没有身份就没有判重、没有 delete、没有 tombstone，留着它只会在
     * `LazyColumn` 的 `key` 上撞车。
     */
    internal fun decodeEntry(obj: JsonObject): SavedSongEntry? {
        // ★ 形状 1 的**决定性**判据：`SongItem` 一定有 `id` 与 `name` 两个 primitive
        // （它们是必填的非空构造参数），而 v2 信封的五个 key 里**没有这两个名字**。
        //
        // 为什么不能只靠 `looksLikeEnvelope` 来分流：那样「key 集合完全未知」
        // （= 形状 3 唯一的触发条件）会被判成 v1 并走 Gson 反序列化，
        // 于是形状 3 永远不可达 —— 一条**永不执行**的兜底路径等于没有兜底。
        // 这个 bug 是被 `未知 key 集合按声明顺序兜底` 那条用例照出来的。
        if (obj.has("id") && obj.has("name")) return decodeLegacySongItem(obj)

        if (looksLikeEnvelope(obj)) {
            return when {
                obj.has("trackKey") || obj.has("song") -> decodeEnvelopeByNames(obj)
                LEGACY_KEYS.isNotEmpty() &&
                    obj.keySet().all { it in LEGACY_KEYS } -> decodeEnvelopeByLegacyLetters(obj)
                else -> decodeEnvelopeByDeclarationOrder(obj)
            }
        }
        // 既不是 v1（没有 id+name）也不是已知信封（一个稳定 key 都没有）
        // ⇒ 只剩「未知 key 集合」这一种可能：按声明顺序试一次，
        // 字段数不足 5 时 `decodeEnvelopeByDeclarationOrder` 会丢弃它。
        return decodeEnvelopeByDeclarationOrder(obj)
    }

    /** 形状 2：v2 信封，稳定名字。 */
    private fun decodeEnvelopeByNames(obj: JsonObject): SavedSongEntry? {
        val song = obj.get("song")?.takeIf { it.isJsonObject }?.let {
            runCatching { gson.fromJson(it, SongItem::class.java) }.getOrNull()
        } ?: return null
        val key = parseKey(obj.stringOf("trackKey")) ?: TrackKey.ofSong(song)
        return SavedSongEntry(
            trackKey = key,
            origin = parseOrigin(obj.stringOf("origin")),
            addedAt = obj.longOf("addedAt") ?: 0L,
            tombstoned = obj.booleanOf("tombstoned") ?: false,
            song = song,
        )
    }

    /** 形状 3a：v2 信封，被混淆过的单字母 key（当前 [LEGACY_KEYS] 为空 ⇒ 走不到）。 */
    private fun decodeEnvelopeByLegacyLetters(obj: JsonObject): SavedSongEntry? {
        val song = obj.get(LEGACY_KEYS[4])?.takeIf { it.isJsonObject }?.let {
            runCatching { gson.fromJson(it, SongItem::class.java) }.getOrNull()
        } ?: return null
        val key = parseKey(obj.stringOf(LEGACY_KEYS[0])) ?: TrackKey.ofSong(song)
        return SavedSongEntry(
            trackKey = key,
            origin = parseOrigin(obj.stringOf(LEGACY_KEYS[1])),
            addedAt = obj.longOf(LEGACY_KEYS[2]) ?: 0L,
            tombstoned = obj.booleanOf(LEGACY_KEYS[3]) ?: false,
            song = song,
        )
    }

    /**
     * 形状 3：**未知 key 集合**时按声明顺序兜底。
     *
     * 位置读在这里安全的**唯一理由**是：Gson 对一个字段全为 null 的对象会写出
     * `{}`，而我们的写路径**永远写满 5 个 key**（`song` 必非 null，`addedAt`
     * 必非 null，`origin`/`tombstoned`/`trackKey` 也必非 null）。
     * 所以「key 数 = 5」在 v2 信封上是恒真的。
     *
     * 一旦发现 key 数不是 5，宁可**丢掉这一条**（`null`）也不猜 ——
     * 按位置硬读一份缺字段的数据会造出「歌名变成歌手名」这类静默错位
     * （`OfflineTrackCodec` 的 `a b e f g i` 缺 `h` 就是同形状的坑）。
     */
    private fun decodeEnvelopeByDeclarationOrder(obj: JsonObject): SavedSongEntry? {
        val values = obj.entrySet().map { it.value }.filter { !it.isJsonNull }
        if (values.size != STABLE_KEYS.size) return null
        val song = values[4].takeIf { it.isJsonObject }?.let {
            runCatching { gson.fromJson(it, SongItem::class.java) }.getOrNull()
        } ?: return null
        val key = parseKey(values[0].asStringOrNull()) ?: TrackKey.ofSong(song)
        return SavedSongEntry(
            trackKey = key,
            origin = parseOrigin(values[1].asStringOrNull()),
            addedAt = values[2].asLongOrNull() ?: 0L,
            tombstoned = values[3].asBooleanOrNull() ?: false,
            song = song,
        )
    }

    /**
     * 形状 1：**v1 裸 `SongItem`**（v2.6.0 之前落盘的样子）。
     *
     * `origin = REMOTE`：这是**唯一有证据支撑**的解释 —— v2.6.0 之前，
     * 手动「加入库」的条目活不过一次 `refreshFromCloud`（它在重建时被丢掉、
     * 随后 flush 把丢掉的结果写回磁盘），所以磁盘上**不可能**存在用户手动加的条目。
     *
     * `addedAt = 0`：v1 没有这个字段。用 0 而不是 `now`，因为 0 的语义
     * 「不知道」是诚实的，而填 `now` 会把「迁移那一刻」伪装成「用户加入那一刻」，
     * 让 tombstone 的 `MAX_TOMBSTONES` 淘汰顺序失去意义。
     */
    private fun decodeLegacySongItem(obj: JsonObject): SavedSongEntry? {
        val song = runCatching { gson.fromJson(obj, SongItem::class.java) }.getOrNull() ?: return null
        if (song.id <= 0L) return null
        return SavedSongEntry(
            trackKey = TrackKey.ofSong(song),
            origin = SavedSongOrigin.REMOTE,
            addedAt = 0L,
            tombstoned = false,
            song = song,
        )
    }

    // ---------------------------------------------------------------- 小工具 ----

    /**
     * `netease:123` → [TrackKey]。解析不出来返回 null（由调用方回落到 `ofSong`）。
     *
     * 走 `SourceIds.parseTrackKey`：它**只接受「已知音源 key + 正整数 id」**，
     * 未知音源返回 null 而不是猜成 ncm —— 猜错会把一首 QQ 曲目
     * 拿去问 ncm 要播放链。
     */
    private fun parseKey(text: String?): TrackKey? {
        val (source, id) = com.takahashirinta.ncrust.source.SourceIds.parseTrackKey(text) ?: return null
        return TrackKey(source, id)
    }

    /**
     * `origin` 字符串 → 枚举。**未知取值按 `REMOTE` 解释**。
     *
     * 为什么不是 `LOCAL`：`REMOTE` 与 `LOCAL` 的差别只体现在
     * 「同步时排在最前」与「永远保留」两点上，而 `REMOTE` 条目**同样**永远保留
     * （规则 3：云端没有 + 本地有 ⇒ 保留）。也就是说在**同步语义**上两者等价，
     * 差别只是排序。把未知值当 `LOCAL` 会让一份坏数据把条目顶到列表最前，
     * 而当 `REMOTE` 的最坏后果只是位置不同。
     */
    private fun parseOrigin(text: String?): SavedSongOrigin =
        SavedSongOrigin.values().firstOrNull { it.name == text } ?: SavedSongOrigin.REMOTE

    private fun JsonObject.longOf(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asLong }.getOrNull()
        }

    private fun JsonObject.booleanOf(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asBoolean }.getOrNull()
        }

    private fun JsonObject.stringOf(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asString }.getOrNull()
        }

    private fun JsonElement.asStringOrNull(): String? =
        takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    private fun JsonElement.asLongOrNull(): Long? =
        takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

    private fun JsonElement.asBooleanOrNull(): Boolean? =
        takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }

    // ------------------------------------------------------- 给单测的只读视图 ----

    internal fun stableKeys(): List<String> = STABLE_KEYS

    internal fun legacyKeys(): List<String> = LEGACY_KEYS

    /** 判定一条 JSON 对象是不是 v2 信封（单测要钉住「v1 不会被误判」）。 */
    internal fun isEnvelope(obj: JsonObject): Boolean = looksLikeEnvelope(obj)
}
