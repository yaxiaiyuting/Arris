/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * `ncrust_stats` / `snapshot` 的落盘编解码。**纯逻辑，无 Android 依赖，JVM 可单测。**
 * 形状照抄 `cache/OfflineTrackCodec.kt`（本仓库持久化 DTO 的正确写法）。
 */

package com.takahashirinta.ncrust.stats

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * 播放统计的**落盘契约**。
 *
 * ## 为什么每个字段都要显式 `@SerializedName`
 *
 * AGENTS.md v2.5.4 规则 1（本仓库的硬约束）：**持久化结构的字段名是对外契约，
 * 不许交给 R8 决定**。本仓库已经为此付过四次学费（`local.**` / `crosssource.**` /
 * `cache.**` / `playback_state` 的 `PositionEntry`），症状都是「同一个 APK 内读写自洽 ⇒
 * 不崩，只在下一次混淆映射变化时静默清空用户数据」—— 比崩溃难发现一个数量级。
 *
 * `proguard-rules.pro` 已有全局规则
 * `-keepclassmembers,allowobfuscation class * { @SerializedName <fields>; }`，
 * Gson 读的是**注解里的字符串常量**，字段怎么改名都不影响落盘形状。
 * `StatsCodecTest` 逐个断言这些 key 的字面值 —— 谁改了字段名，用例先红，
 * 而不是用户的数据先消失。
 *
 * ## 为什么不写 `schema` 版本号
 *
 * 与 `OfflineTrackCodec` 同一条纪律：**判定老/新只看字段是否存在**。
 * 多一个版本号就多一个能写错、能对不上的状态；而本文件是**本版新开**的
 * （`ncrust_stats` 之前不存在），没有任何历史形状需要区分。
 * 将来加字段时：新字段一律**可空 + 有默认值**（Gson 走 Unsafe、不调用构造函数），
 * 读路径按「字段缺失 = 老数据 = 按缺省语义处理」来写。
 *
 * ## 坏数据逐条丢弃
 *
 * 反序列化**不用** `gson.fromJson(json, SnapshotDto::class.java)` 一把梭：
 * 那样一个字节坏了就是「用户全部统计清零」。这里先 `JsonParser` 出对象树，
 * 逐字段取，单曲表**逐条**解析 —— 解析不出来的那一条跳过，其余照常保留。
 * 顶层不是对象时才返回空快照。
 */
internal object StatsCodec {

    /**
     * 落盘 key 的**唯一事实来源**（顺序 = 单测断言顺序）。
     *
     * 写路径只经 [SnapshotDto] / [SongDto]；这份清单是给守卫单测用的，
     * 它让「有人把 `@SerializedName` 删了、靠字段名落盘」这件事必然被测出来。
     */
    private val SNAPSHOT_KEYS = listOf(
        "totalMs", "totalPlays", "firstDayKey",
        "byDay", "bySource", "playsBySource", "songs",
    )

    private val SONG_KEYS = listOf(
        "key", "name", "artist", "ms", "plays", "lastAtMs", "durationMs",
    )

    private val gson = Gson()

    /** 根对象形状。全部可空 + 有默认值：Gson 走 Unsafe，字段缺失时是 null 而不是 0。 */
    internal data class SnapshotDto(
        @SerializedName("totalMs") val totalMs: Long? = null,
        @SerializedName("totalPlays") val totalPlays: Long? = null,
        @SerializedName("firstDayKey") val firstDayKey: String? = null,
        @SerializedName("byDay") val byDay: Map<String, Long>? = null,
        @SerializedName("bySource") val bySource: Map<String, Long>? = null,
        @SerializedName("playsBySource") val playsBySource: Map<String, Long>? = null,
        @SerializedName("songs") val songs: List<SongDto>? = null,
    )

    /** 单曲形状。 */
    internal data class SongDto(
        @SerializedName("key") val key: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("artist") val artist: String? = null,
        @SerializedName("ms") val ms: Long? = null,
        @SerializedName("plays") val plays: Long? = null,
        @SerializedName("lastAtMs") val lastAtMs: Long? = null,
        @SerializedName("durationMs") val durationMs: Long? = null,
    )

    // ---------------------------------------------------------------- 编码 ----

    fun encode(snapshot: StatsSnapshot): String = gson.toJson(
        SnapshotDto(
            totalMs = snapshot.totalMs,
            totalPlays = snapshot.totalPlays,
            firstDayKey = snapshot.firstDayKey,
            byDay = snapshot.byDay,
            bySource = snapshot.bySource,
            playsBySource = snapshot.playsBySource,
            songs = snapshot.songs.values.map { it.toDto() },
        )
    )

    private fun SongStat.toDto() = SongDto(
        key = key,
        name = name,
        artist = artist,
        ms = ms,
        plays = plays,
        lastAtMs = lastAtMs,
        durationMs = durationMs,
    )

    // ---------------------------------------------------------------- 解码 ----

    fun decode(json: String?): StatsSnapshot {
        if (json.isNullOrEmpty()) return StatsSnapshot.EMPTY
        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (_: Exception) {
            return StatsSnapshot.EMPTY
        }
        val obj = root as? JsonObject ?: return StatsSnapshot.EMPTY

        val songs = LinkedHashMap<String, SongStat>()
        obj.arrayOrNull("songs")?.forEach { element ->
            val songObj = element as? JsonObject ?: return@forEach
            decodeSong(songObj)?.let { songs[it.key] = it }
        }

        return StatsSnapshot(
            totalMs = longOf(obj, "totalMs") ?: 0L,
            totalPlays = longOf(obj, "totalPlays") ?: 0L,
            firstDayKey = stringOf(obj, "firstDayKey"),
            byDay = longMapOf(obj.objectOrNull("byDay")),
            bySource = longMapOf(obj.objectOrNull("bySource")),
            playsBySource = longMapOf(obj.objectOrNull("playsBySource")),
            songs = songs,
        )
    }

    /**
     * 单条解码。`key` 缺失或为空的条目直接丢弃 —— 它是主键，
     * 补不出合理默认值（补成 `""` 会让所有坏条目合并成同一首歌）。
     */
    private fun decodeSong(obj: JsonObject): SongStat? {
        val key = stringOf(obj, "key") ?: return null
        val ms = longOf(obj, "ms") ?: 0L
        val plays = longOf(obj, "plays") ?: 0L
        // 「一点数据都没有」的条目也丢掉：它在 Top-N 里就是一行空白，
        // 留着只会占额度、把真正的歌挤出去。
        if (ms <= 0L && plays <= 0L) return null
        return SongStat(
            key = key,
            name = stringOf(obj, "name") ?: "",
            artist = stringOf(obj, "artist") ?: "",
            ms = ms,
            plays = plays,
            lastAtMs = longOf(obj, "lastAtMs") ?: 0L,
            durationMs = longOf(obj, "durationMs") ?: 0L,
        )
    }

    /** 只接受「值是非负整数」的条目，其余逐个跳过（不整段丢光）。 */
    private fun longMapOf(obj: JsonObject?): Map<String, Long> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for ((k, v) in obj.entrySet()) {
            if (!v.isJsonPrimitive) continue
            val n = runCatching { v.asLong }.getOrNull() ?: continue
            if (n > 0L) out[k] = n
        }
        return out
    }

    /**
     * 刻意**不**叫 `getAsJsonArray` / `getAsJsonObject`：Gson 的 `JsonObject` 上已经有这两个
     * 同名成员方法，而成员方法优先于扩展函数 —— 同名的话下面这两条防御根本不会被调到
     * （Gson 那两个是裸 `(JsonArray) members.get(name)`：类型不对时**抛 ClassCastException**，
     * 对一个被写坏的文件来说就是「整个统计页打不开」）。
     */
    private fun JsonObject.arrayOrNull(key: String): List<JsonElement>? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList()

    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun longOf(obj: JsonObject, key: String): Long? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asLong }.getOrNull()
        }

    private fun stringOf(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asString }.getOrNull()
        }?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------- 给单测的只读视图 ----

    /** 根对象的落盘 key（单测断言「字段名不再由 R8 决定」）。 */
    internal fun snapshotKeys(): List<String> = SNAPSHOT_KEYS

    /** 单曲条目的落盘 key。 */
    internal fun songKeys(): List<String> = SONG_KEYS
}
