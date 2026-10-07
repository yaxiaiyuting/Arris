/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · A：每首歌曲续播进度（`ncrust_playback_state` / `song_positions`）的落盘编解码。
 * **纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.player

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * `ncrust_playback_state` / `song_positions` 的**编解码契约**（v2.5.5 · A）。
 *
 * ## 这个文件修的是什么
 *
 * v2.5.5 的 R8 全仓扫描（`docs/verification/v2.5.5/probe-r8-keys.md`）发现
 * **此前所有文档都没提过的第二个实例**：
 *
 * ```text
 * com.takahashirinta.ncrust.player.PlaybackStateManager$PositionEntry -> H4.F:
 *     long posMs -> a
 *     long savedAtMs -> b
 * ```
 *
 * 落盘形状因此是 `{"1959528822":{"a":48631,"b":1790344822158}}` —— 两个字段都被
 * R8 混淆成了单字母。它与 `search_history` / `ncrust_offline/tracks` 是同一类问题，
 * 但**后果最隐蔽的一个**：
 *
 * - 字母表一变（比如将来 `posMs` 被排到 `b`），`a` 会被当成 `savedAtMs`、
 *   `b` 会被当成 `posMs` ⇒ 续播位置变成 1790344822158ms ≈ 28 天；
 * - 或者两个字段都读成 0 ⇒ 「续播静默失效」，用户感知为**「这应用不记住我听到哪」**；
 * - 它不崩、不报错、日志里什么都没有。而 `PositionEntry` 又不在任何 `-keep` 覆盖的包里
 *   （`network.**` / `lyric.**` / `playlist.**` / `local.**` / `crosssource.**` 都不含 `player.**`）。
 *
 * ## 三条读法（与 `SearchHistoryCodec` / `OfflineTrackCodec` 同一手法）
 *
 * 1. **稳定名字**（`posMs` / `savedAtMs`）：本版之后写入的形状；
 * 2. **已知旧单字母**（`a` / `b`，由 `mapping.txt` 取证）；
 * 3. **声明顺序兜底**：未知 key 集合时按「第一个数字 = posMs、第二个 = savedAtMs」读。
 *
 * 两个字段都是**非空 Long 且一定会写**，所以旧形状恒为两个 key、不存在
 * `ncrust_offline/tracks` 那种「null 字段被 Gson 省掉」的错位风险；
 * 兜底路径因此只需按顺序取两个数字。
 *
 * ## 坏数据逐条丢弃
 *
 * 旧实现是 `runCatching { gson.fromJson(...) }.getOrDefault(mutableMapOf())` ——
 * 一个字节坏了，**用户全部歌曲的续播记录消失**。新实现逐条解析，坏的那条跳过。
 */
internal object PlaybackPositionCodec {

    /** 落盘 schema 版本（v1 = R8 决定 key；v2 = 显式名字）。版本号不落盘，只靠字段形状判。 */
    const val SCHEMA_VERSION = 2

    /** v1 的落盘 key（`mapping.txt` 取证：`posMs→a`、`savedAtMs→b`）。 */
    private val LEGACY_KEYS = listOf("a", "b")

    private val STABLE_KEYS = listOf("posMs", "savedAtMs")

    private val gson = Gson()

    /**
     * 落盘形状。`PositionEntry` 本体也带同名 `@SerializedName`（写路径直接用它），
     * 这里再声明一份是为了让**读路径**有一个显式的契约对象可断言，
     * 也为了让将来给 `PositionEntry` 加字段时必须在两处同时做决定。
     */
    internal data class PositionDto(
        @SerializedName("posMs") val posMs: Long? = null,
        @SerializedName("savedAtMs") val savedAtMs: Long? = null,
    )

    // ---------------------------------------------------------------- 编码 ----

    /**
     * `songId -> PositionEntry` → JSON 对象串。
     *
     * key 是**裸 songId 的十进制字符串**（Gson 对 `Map<Long, _>` 的默认行为），
     * 与 v1 逐字节同构 —— 这本版**没有改 key 的形状**，只改了内层字段名。
     */
    fun encode(map: Map<Long, PlaybackStateManager.PositionEntry>): String {
        val out = JsonObject()
        for ((songId, entry) in map) {
            val obj = JsonObject()
            obj.addProperty("posMs", entry.posMs)
            obj.addProperty("savedAtMs", entry.savedAtMs)
            out.add(songId.toString(), obj)
        }
        return gson.toJson(out)
    }

    // ---------------------------------------------------------------- 解码 ----

    /**
     * JSON 对象串 → `songId -> PositionEntry`。坏条目**逐条**丢弃。
     * 顶层不是对象、或根本不是 JSON 时才返回空表。
     */
    fun decode(json: String?): MutableMap<Long, PlaybackStateManager.PositionEntry> {
        val out = mutableMapOf<Long, PlaybackStateManager.PositionEntry>()
        if (json.isNullOrEmpty()) return out
        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (_: Exception) {
            return out
        }
        if (!root.isJsonObject) return out
        for ((key, value) in root.asJsonObject.entrySet()) {
            val songId = key.toLongOrNull() ?: continue
            if (songId <= 0L) continue
            val obj = value as? JsonObject ?: continue
            decodeEntry(obj)?.let { out[songId] = it }
        }
        return out
    }

    /** 单条解码：稳定名字 → 已知旧单字母 → 声明顺序。 */
    internal fun decodeEntry(obj: JsonObject): PlaybackStateManager.PositionEntry? {
        if (obj.has("posMs") || obj.has("savedAtMs")) {
            val pos = obj.longOf("posMs") ?: return null
            return PlaybackStateManager.PositionEntry(pos, obj.longOf("savedAtMs") ?: 0L)
        }
        if (obj.keySet().isNotEmpty() && obj.keySet().all { it in LEGACY_KEYS }) {
            val pos = obj.longOf("a") ?: return null
            return PlaybackStateManager.PositionEntry(pos, obj.longOf("b") ?: 0L)
        }
        return fromDeclarationOrder(obj)
    }

    /**
     * 兜底：**未知 key 集合**时按声明顺序读（第一个数字 = `posMs`，第二个 = `savedAtMs`）。
     *
     * 只在 R8 换了字母表时才走到。意义是「宁可续播位置不准，也不要让用户全部
     * 续播记录消失」—— 而位置本身还有 `MIN_RESUMABLE_MS` / 95% 两道合法性闸门，
     * 一个荒谬的值会被 `getSongPosition` 拒掉。
     */
    private fun fromDeclarationOrder(obj: JsonObject): PlaybackStateManager.PositionEntry? {
        val nums = obj.entrySet()
            .map { it.value }
            .filter { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            .mapNotNull { runCatching { it.asLong }.getOrNull() }
        if (nums.isEmpty()) return null
        return PlaybackStateManager.PositionEntry(nums[0], nums.getOrElse(1) { 0L })
    }

    private fun JsonObject.longOf(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asLong }.getOrNull()
        }

    // ------------------------------------------------------- 给单测的只读视图 ----

    internal fun stableKeys(): List<String> = STABLE_KEYS

    internal fun legacyKeys(): List<String> = LEGACY_KEYS
}
